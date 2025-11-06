(ns mcp-vector-search.main
  "MCP vector search server main entry point.

  ## Responsibilities
  Orchestrates server startup by coordinating configuration loading, system
  initialization, document ingestion, file watching setup, and MCP server
  creation. Handles shutdown hooks to ensure clean resource cleanup."
  (:gen-class)
  (:require
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.lifecycle :as lifecycle]
    [mcp-vector-search.resources :as resources]
    [mcp-vector-search.tools :as tools]))


(def ^:private default-config-path
  ".mcp-vector-search/config.edn")


(defn start
  "Start MCP vector search server.
  Takes a config file path or uses default config."
  [{:keys [config-path] :or {config-path default-config-path}}]
  (try
    (let [config-path (str config-path)
          cfg (config/load-config config-path)
          parsed-config (config/process-config cfg)]

      (lifecycle/start)
      (ingest/ingest lifecycle/system parsed-config)

      ;; Start file watches after initial ingest
      (lifecycle/start-watches parsed-config)

      (let [search-tool (tools/search-tool lifecycle/system parsed-config)
            resource-defs (resources/resource-definitions lifecycle/system)]
        (with-open [server (mcp-server/create-server
                             {:transport {:type :stdio}
                              :tools {(:name search-tool) search-tool}
                              :resources resource-defs
                              :capabilities {:logging {}}})]
          ;; Add server to system for logging access
          (lifecycle/set-server server)
          (.addShutdownHook
            (Runtime/getRuntime)
            (Thread. #(do
                        (lifecycle/stop)
                        ((:stop server)))))
          ;; Keep the main thread alive
          @(promise))))
    (catch Exception e
      (binding [*out* *err*]
        (println  "Unexpected error:" (.getMessage e)))
      (.printStackTrace e)
      (System/exit 1))))


(defn -main
  "Start MCP vector search server"
  [& args]
  (start {:config-path (or (first args) default-config-path)}))
