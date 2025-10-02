(ns mcp-vector-search.lifecycle
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
             :metadata-values (atom {})}))
  @system)

(defn stop
  "Stop the system and release resources.
  Returns nil."
  []
  (reset! system nil))
