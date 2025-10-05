(ns mcp-vector-search.watch-integration-test
  "Integration tests for watch re-indexing logic (without hawk timing dependencies)."
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest])
  (:import
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding
      EmbeddingSearchRequest)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

(defn- count-embeddings
  "Count embeddings in the store."
  [embedding-store embedding-model]
  (let [query-embedding (.content (.embed embedding-model (TextSegment/from "test")))
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int 1000))
                    (.build))
        results (.search embedding-store request)]
    (count (.matches results))))

(deftest re-indexing-logic-test
  ;; Test re-indexing logic that would be triggered by watch events

  (testing "re-indexing logic"

    (testing "re-ingests modified files (simulating MODIFY event)"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Initial content")
                config {:sources [{:path (str (fs/file temp-dir "*.md"))}]}
                parsed-config (config/process-config config)]

            ;; Initial ingest
            (ingest/ingest system parsed-config)
            (is (= 1 (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

            ;; Simulate MODIFY event: remove old, re-ingest new
            (.removeAll (:embedding-store system) [canonical-path])
            (spit test-file "Modified content")

            (let [file-map {:file test-file
                            :path canonical-path
                            :captures {}
                            :metadata {}
                            :embedding :whole-document
                            :ingest :whole-document}]
              (ingest/ingest-file system file-map))

            ;; Should still have 1 document
            (is (= 1 (count-embeddings (:embedding-store system)
                                       (:embedding-model system)))
                "Should have 1 document after re-indexing"))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes deleted files (simulating DELETE event)"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Content to delete")
                config {:sources [{:path (str (fs/file temp-dir "*.md"))}]}
                parsed-config (config/process-config config)]

            ;; Initial ingest
            (ingest/ingest system parsed-config)
            (is (= 1 (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

            ;; Simulate DELETE event
            (.removeAll (:embedding-store system) [canonical-path])

            ;; Should have 0 documents
            (is (zero? (count-embeddings (:embedding-store system)
                                         (:embedding-model system)))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "adds newly created files (simulating CREATE event)"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          ;; Start with empty store
          (is (zero? (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

          ;; Simulate CREATE event: ingest new file
          (let [new-file (fs/file temp-dir "new.md")
                _ (spit new-file "New content")
                file-map {:file new-file
                          :path (str new-file)
                          :captures {}
                          :metadata {}
                          :embedding :whole-document
                          :ingest :whole-document}]
            (ingest/ingest-file system file-map))

          ;; Should have 1 document
          (is (= 1 (count-embeddings (:embedding-store system)
                                     (:embedding-model system))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "handles multiple rapid changes (simulating debouncing)"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :embedding :whole-document
                          :ingest :whole-document}]

            ;; Simulate rapid changes (last one wins after debouncing)
            (dotimes [i 5]
              (spit test-file (str "Change " i))
              (.removeAll (:embedding-store system) [canonical-path])
              (ingest/ingest-file system file-map))

            ;; Should have exactly 1 document
            (is (= 1 (count-embeddings (:embedding-store system)
                                       (:embedding-model system)))))
          (finally
            (fs/delete-tree temp-dir)))))))
