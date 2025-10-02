(ns mcp-vector-search.tools
  (:require [clojure.data.json :as json])
  (:import [dev.langchain4j.store.embedding EmbeddingSearchRequest]
           [dev.langchain4j.store.embedding.filter Filter]
           [dev.langchain4j.store.embedding.filter.comparison IsEqualTo]))

(defn- embed-query
  "Embed a query string using the embedding model.
  Returns the embedding vector."
  [embedding-model query]
  (-> embedding-model
      (.embed query)
      (.content)))

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
  [embedding-store query-embedding max-results metadata-filter]
  (let [builder (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int max-results)))
        builder (if metadata-filter
                  (.filter builder metadata-filter)
                  builder)
        request (.build builder)]
    (-> embedding-store
        (.search request)
        (.matches))))

(defn- format-search-results
  "Format search results as a JSON string.
  Each result contains content and score."
  [matches]
  (let [results (mapv (fn [match]
                        {"content" (-> match .embedded .text)
                         "score" (.score match)})
                      matches)]
    (json/write-str results)))

(defn search-tool
  "Create a search tool specification for the MCP server.
  Takes a system map with :embedding-model and :embedding-store.
  Takes a config map with :description for the tool description.
  Returns a tool specification map."
  [system config]
  {:name "search"
   :description (:description config)
   :inputSchema {:type "object"
                 :properties {"query" {:type "string"
                                       :description "The search query"}
                              "limit" {:type "number"
                                       :description "Maximum number of results to return"
                                       :default 10}
                              "metadata" {:type "object"
                                         :description "Metadata filters as key-value pairs to match documents"
                                         :additionalProperties true}}
                 :required ["query"]}
   :implementation (fn [{:keys [query limit metadata]}]
                     (try
                       (let [{:keys [embedding-model embedding-store]} system
                             max-results (or limit 10)
                             query-embedding (embed-query embedding-model query)
                             metadata-filter (build-metadata-filter metadata)
                             matches (search-documents embedding-store query-embedding max-results metadata-filter)
                             results-json (format-search-results matches)]
                         {:content [{:type "text"
                                     :text results-json}]
                          :isError false})
                       (catch Exception e
                         {:content [{:type "text"
                                     :text (str "Search error: " (.getMessage e))}]
                          :isError true})))})
