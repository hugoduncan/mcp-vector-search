(ns mcp-vector-search.tools
  (:require [clojure.data.json :as json])
  (:import [dev.langchain4j.store.embedding EmbeddingSearchRequest]))

(defn- embed-query
  "Embed a query string using the embedding model.
  Returns the embedding vector."
  [embedding-model query]
  (-> embedding-model
      (.embed query)
      (.content)))

(defn- search-documents
  "Search the embedding store for similar documents.
  Returns a list of EmbeddingMatch objects."
  [embedding-store query-embedding max-results]
  (let [request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int max-results))
                    (.build))]
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
  Returns a tool specification map."
  [system]
  {:name "search"
   :description "Search indexed documents using semantic similarity"
   :inputSchema {:type "object"
                 :properties {"query" {:type "string"
                                       :description "The search query"}
                              "limit" {:type "number"
                                       :description "Maximum number of results to return"
                                       :default 10}}
                 :required ["query"]}
   :implementation (fn [{:keys [query limit]}]
                     (try
                       (let [{:keys [embedding-model embedding-store]} system
                             max-results (or limit 10)
                             query-embedding (embed-query embedding-model query)
                             matches (search-documents embedding-store query-embedding max-results)
                             results-json (format-search-results matches)]
                         {:content [{:type "text"
                                     :text results-json}]
                          :isError false})
                       (catch Exception e
                         {:content [{:type "text"
                                     :text (str "Search error: " (.getMessage e))}]
                          :isError true})))})
