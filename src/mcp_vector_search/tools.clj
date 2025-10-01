(ns mcp-vector-search.tools)

(defn search-tool
  "Create a search tool specification for the MCP server.
  Takes a system map.
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
                     ;; TODO: Implement actual search using system embedding-store
                     {:content [{:type "text"
                                 :text (str "Search results for: " query
                                            " (limit: " (or limit 10) ")")}]
                      :isError false})})
