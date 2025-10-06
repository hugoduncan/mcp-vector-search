(ns mcp-vector-search.lifecycle
  "System lifecycle management for embedding and storage resources.

  ## Responsibilities
  Manages the lifecycle of core system components: the AllMiniLmL6V2 embedding
  model, in-memory vector store, metadata tracking atom, and file watchers.
  Provides start/stop functions to initialize and cleanly shut down resources.

  ## Implementation Notes
  Uses a global atom to hold system state, ensuring single initialization of
  the embedding model (which has native dependencies requiring JVM flags).
  Coordinates with watch namespace for file monitoring lifecycle."
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

(defn set-server
  "Set the MCP server in the system map.
  Takes an MCP server instance.
  Returns the updated system map."
  [server]
  (when @system
    (swap! system assoc :server server))
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
