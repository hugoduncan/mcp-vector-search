(ns mcp-vector-search.manual-watch-test
  "Manual test script for file watching functionality.

  Due to hawk's async behavior and platform-specific timing, automated
  integration tests for file watching are unreliable. This manual test
  script provides a way to verify watch functionality interactively.

  Run this with: clojure -M -m mcp-vector-search.manual-watch-test"
  (:require
    [babashka.fs :as fs]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.watch :as watch])
  (:import
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding
      EmbeddingSearchRequest)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

(defn count-embeddings
  "Count total embeddings in the store."
  [embedding-store embedding-model]
  (let [query-embedding (.content (.embed embedding-model (TextSegment/from "test")))
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int 1000))
                    (.build))
        results (.search embedding-store request)]
    (count (.matches results))))

(defn -main
  [& args]
  (let [temp-dir (fs/create-temp-dir {:prefix "watch-test"})
        _ (println "Test directory:" (str temp-dir))
        system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                :embedding-store (InMemoryEmbeddingStore.)
                :metadata-values (atom {})}
        config {:watch? true
                :sources [{:path (str (fs/file temp-dir "*.md"))}]}
        parsed-config (config/process-config config)]

    (println "\nStarting file watches...")
    (let [watchers (watch/start-watches system parsed-config)]

      (println "\nWatch setup complete. Waiting for events...")
      (println "Test directory:" (str temp-dir))
      (println "\nInstructions:")
      (println "1. Create files in the test directory with .md extension")
      (println "2. Modify existing files")
      (println "3. Delete files")
      (println "4. Press Ctrl+C to exit and clean up")
      (println "\nCurrent count:" (count-embeddings (:embedding-store system)
                                                     (:embedding-model system)))

      (try
        ;; Keep running and periodically show count
        (loop []
          (Thread/sleep 2000)
          (println "Current embedding count:" (count-embeddings (:embedding-store system)
                                                                (:embedding-model system)))
          (recur))
        (finally
          (println "\nCleaning up...")
          (watch/stop-watches watchers)
          (fs/delete-tree temp-dir)
          (println "Done."))))))
