(ns mcp-vector-search.main
  "MCP vector search server main entry point.

  ## Responsibilities
  Orchestrates server startup by coordinating configuration loading, system
  initialization, document ingestion, file watching setup, and MCP server
  creation. Handles shutdown hooks to ensure clean resource cleanup."
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.lifecycle :as lifecycle]
    [mcp-vector-search.tools :as tools]))

(def ^:private default-config-path
  ".mcp-vector-search/config.edn")

(defn start
  "Start MCP vector search server.
  Takes a config file path or uses default config."
  [{:keys [config-path] :or {config-path default-config-path}}]
  (try
    (log/info :vector-search-server {:msg "Starting"})

    (let [config-path (str config-path)
          system (lifecycle/start)
          cfg (config/read config-path)
          parsed-config (config/process-config cfg)]

      (ingest/ingest system parsed-config)

      ;; Start file watches after initial ingest
      (lifecycle/start-watches parsed-config)

      (let [search-tool (tools/search-tool system parsed-config)]
        (with-open [server (mcp-server/create-server
                             {:transport {:type :stdio}
                              :tools {(:name search-tool) search-tool}
                              :capabilities {:logging {}}})]
          ;; Add server to system for logging access
          (lifecycle/set-server server)
          (log/info :vector-search-server {:msg "Started"})
          (.addShutdownHook
            (Runtime/getRuntime)
            (Thread. #(do
                        (log/info :shutting-down-vector-search-server)
                        (lifecycle/stop)
                        ((:stop server)))))
          ;; Keep the main thread alive
          @(promise))))
    (catch Exception e
      (log/error :vector-search-server {:error (.getMessage e)})
      (.printStackTrace e)
      (System/exit 1))))

(defn -main
  "Start MCP vector search server"
  [& args]
  (start {:config-path (or (first args) default-config-path)}))
