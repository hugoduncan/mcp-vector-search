(ns mcp-vector-search.lifecycle
  (:require
    [mcp-vector-search.watch :as watch])
  (:import
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

(defonce system (atom nil))

(defn- create-embedding-model
  "Create an embedding model instance."
  []
  (try
    (AllMiniLmL6V2EmbeddingModel.)
    (catch Exception e
      (throw (ex-info "Failed to initialize embedding model"
                      {:error (.getMessage e)}
                      e)))))

(defn start
  "Initialize the system with embedding model and store.
  Returns the system map."
  []
  (when-not @system
    (reset! system
            {:embedding-model (create-embedding-model)
             :embedding-store (InMemoryEmbeddingStore.)
             :metadata-values (atom {})
             :watches nil}))
  @system)

(defn start-watches
  "Start file watches for the system.
  Takes a processed config map.
  Returns the updated system map."
  [config]
  (when @system
    (let [watchers (watch/start-watches @system config)]
      (swap! system assoc :watches watchers)))
  @system)

(defn stop
  "Stop the system and release resources.
  Returns nil."
  []
  (when @system
    (when-let [watchers (:watches @system)]
      (watch/stop-watches watchers)))
  (reset! system nil))
