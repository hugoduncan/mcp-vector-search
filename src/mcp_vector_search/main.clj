(ns mcp-vector-search.main
  "MCP vector search server main entry point"
  (:gen-class)
  (:require
    [mcp-clj.log :as log]
    [mcp-clj.mcp-server.core :as mcp-server]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.lifecycle :as lifecycle]
    [mcp-vector-search.tools :as tools]))


(defn start
  "Start MCP vector search server.
  Takes a config file path or uses default config."
  [{:keys [config-path] :or {config-path ".mcp-vector-search/config.edn"}}]
  (try
    (log/info :vector-search-server {:msg "Starting"})

    (let [system (lifecycle/start)
          cfg (config/read config-path)
          parsed-config (config/process-config cfg)]

      (ingest/ingest system parsed-config)

      (let [search-tool (tools/search-tool system parsed-config)]
        (with-open [server (mcp-server/create-server
                             {:transport {:type :stdio}
                              :tools {(:name search-tool) search-tool}})]
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
      (System/exit 1))))


(defn -main
  "Start MCP vector search server"
  [& args]
  (start {:config-path (or (first args) "config.edn")}))
