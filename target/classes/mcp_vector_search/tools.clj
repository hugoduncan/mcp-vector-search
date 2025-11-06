(ns mcp-vector-search.tools
  "MCP search tool creation with dynamic schema generation.

  ## Responsibilities
  Creates the MCP search tool specification with metadata filtering support.
  Dynamically generates JSON schema with enum constraints based on metadata
  values discovered during ingestion, ensuring clients only use valid filters.

  ## Implementation Notes
  Schema is generated on-demand from the system's `:metadata-values` atom.
  Metadata filters use LangChain4j `IsEqualTo` comparisons combined with AND
  logic. Search results include both content and similarity scores."
  (:require
    [clojure.data.json :as json]
    [mcp-clj.mcp-server.logging :as logging])
  (:import
    (dev.langchain4j.data.embedding
      Embedding)
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding
      EmbeddingModel)
    (dev.langchain4j.store.embedding
      EmbeddingMatch
      EmbeddingSearchRequest
      EmbeddingStore)
    (dev.langchain4j.store.embedding.filter
      Filter)
    (dev.langchain4j.store.embedding.filter.comparison
      IsEqualTo)))


(defn- embed-query
  "Embed a query string using the embedding model.
  Returns the embedding vector."
  [^EmbeddingModel embedding-model ^String query]
  (let [response (.embed embedding-model query)]
    (.content ^dev.langchain4j.model.output.Response response)))


(defn- build-metadata-filter
  "Build a metadata filter from a map of key-value pairs.
  Combines multiple filters with AND logic.
  Returns nil if metadata-map is empty or nil."
  [metadata-map]
  (when (seq metadata-map)
    (let [filters (map (fn [[k v]]
                         (IsEqualTo. (name k) v))
                       metadata-map)]
      (reduce #(Filter/and %1 %2) filters))))


(defn- search-documents
  "Search the embedding store for similar documents.
  Optionally filters by metadata.
  Returns a list of EmbeddingMatch objects."
  [^EmbeddingStore embedding-store ^Embedding query-embedding max-results metadata-filter]
  (let [builder (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int max-results)))
        builder (if metadata-filter
                  (.filter builder metadata-filter)
                  builder)
        request (.build builder)]
    (-> embedding-store
        (.search request)
        ^dev.langchain4j.store.embedding.EmbeddingSearchResult .matches)))


(defn- format-search-results
  "Format search results as a JSON string.
  Each result contains content and score."
  [matches]
  (let [results (mapv (fn [^EmbeddingMatch match]
                        (let [^TextSegment embedded (.embedded match)]
                          {"content" (.text embedded)
                           "score" (.score match)}))
                      matches)]
    (json/write-str results)))


(defn- build-metadata-schema
  "Build JSON schema for metadata parameter based on discovered metadata values.
  Takes a metadata-values map containing {field-key #{values...}}, or nil.
  Returns a schema map with properties for each field with enum constraints."
  [metadata-values]
  (if (empty? metadata-values)
    {:type "object"
     :description "Metadata filters as key-value pairs to match documents"
     :additionalProperties false}
    {:type "object"
     :description "Metadata filters as key-value pairs to match documents"
     :properties (into {}
                       (map (fn [[k values]]
                              [(name k) {:type "string"
                                         :enum (vec (sort values))}])
                            metadata-values))
     :additionalProperties false}))


(defn search-tool
  "Create a search tool specification for the MCP server.

  Takes a system atom with :embedding-model, :embedding-store, and :metadata-values.
  Takes a config map with :description for the tool description.
  Returns a tool specification map."
  [system-atom config]
  (let [system-map @system-atom
        metadata-schema (build-metadata-schema (:metadata-values system-map))]
    {:name           "search"
     :description    (:description config)
     :inputSchema    {:type     "object"
                      :properties
                      {"query"    {:type        "string"
                                   :description "The search query"}
                       "limit"
                       {:type        "number"
                        :description "Maximum number of results to return"
                        :default     10}
                       "metadata" metadata-schema}
                      :required ["query"]}
     :implementation (fn [context {:keys [query limit metadata]}]
                       (try
                         (when (and context (:server context))
                           (logging/info context {:query query}))
                         (let [current-system @system-atom
                               {:keys [embedding-model embedding-store]}
                               current-system
                               max-results     (or limit 10)
                               query-embedding (embed-query
                                                 embedding-model
                                                 query)
                               metadata-filter (build-metadata-filter metadata)
                               matches         (search-documents
                                                 embedding-store
                                                 query-embedding
                                                 max-results
                                                 metadata-filter)
                               results-json    (format-search-results matches)]
                           {:content [{:type "text"
                                       :text results-json}]
                            :isError false})
                         (catch Exception e
                           {:content [{:type "text"
                                       :text (str
                                               "Search error: "
                                               (.getMessage e))}]
                            :isError true})))}))
