(ns mcp-vector-search.watch-integration-test
  "Integration tests for watch re-indexing logic (without hawk timing dependencies)."
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
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
    (dev.langchain4j.store.embedding.filter.comparison
      IsEqualTo)
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

(defn- count-embeddings-by-file-id
  "Count embeddings with a specific file-id in the store."
  [embedding-store embedding-model file-id]
  (let [query-embedding (.content (.embed embedding-model (TextSegment/from "test")))
        metadata-filter (IsEqualTo. "file-id" file-id)
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int 1000))
                    (.filter metadata-filter)
                    (.build))
        results (.search embedding-store request)]
    (count (.matches results))))

;;; Test strategy for multi-segment files

;; Define at load time to ensure it's available for all test runs
(defmethod ingest/process-document :test-watch-multi-segment
  [_strategy embedding-model embedding-store path content metadata]
  (let [lines (if (empty? content)
                [""]
                (str/split-lines content))
        file-id path]
    (vec
      (map-indexed
        (fn [idx line]
          (let [segment-id (if (zero? idx)
                             file-id
                             (str file-id "#" idx))
                enhanced-metadata (assoc metadata
                                         :file-id file-id
                                         :segment-id segment-id
                                         :line-number idx)
                string-metadata (into {} (map (fn [[k v]] [(name k) v]) enhanced-metadata))
                lc4j-metadata (dev.langchain4j.data.document.Metadata/from string-metadata)
                segment (TextSegment/from line lc4j-metadata)
                response (.embed embedding-model segment)
                embedding (.content response)]
            (.add embedding-store segment-id embedding segment)
            {:file-id file-id
             :segment-id segment-id
             :content line
             :embedding embedding
             :metadata enhanced-metadata}))
        lines))))

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
                            :pipeline :whole-document}]
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
                          :pipeline :whole-document}]
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
                          :pipeline :whole-document}]

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

(deftest multi-segment-watch-test
  ;; Test watch operations with multi-segment files

  (testing "multi-segment file operations"

    (testing "creates multiple segments for multi-line file"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                ;; Create file with 3 lines
                _ (spit test-file "Line 1\nLine 2\nLine 3")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :pipeline :test-watch-multi-segment}]

            ;; Ingest multi-segment file
            (ingest/ingest-file system file-map)

            ;; Should have 3 segments total
            (is (= 3 (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

            ;; All segments should have the same file-id
            (is (= 3 (count-embeddings-by-file-id (:embedding-store system)
                                                   (:embedding-model system)
                                                   canonical-path))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all segments on file modification"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Line 1\nLine 2\nLine 3")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :pipeline :test-watch-multi-segment}]

            ;; Initial ingest - 3 segments
            (ingest/ingest-file system file-map)
            (is (= 3 (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

            ;; Simulate MODIFY: remove all segments with file-id
            (let [removed-count (#'watch/remove-by-file-id
                                  (:embedding-store system)
                                  (:embedding-model system)
                                  canonical-path)]
              (is (= 3 removed-count) "Should remove all 3 segments"))

            ;; Verify all segments removed
            (is (zero? (count-embeddings (:embedding-store system)
                                         (:embedding-model system))))

            ;; Re-ingest with modified content (2 lines now)
            (spit test-file "New line 1\nNew line 2")
            (ingest/ingest-file system file-map)

            ;; Should have 2 new segments
            (is (= 2 (count-embeddings (:embedding-store system)
                                       (:embedding-model system)))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all segments on file deletion"
      (let [temp-dir (fs/create-temp-dir)
            system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :metadata-values (atom {})}]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Line 1\nLine 2\nLine 3\nLine 4")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :pipeline :test-watch-multi-segment}]

            ;; Initial ingest - 4 segments
            (ingest/ingest-file system file-map)
            (is (= 4 (count-embeddings (:embedding-store system)
                                       (:embedding-model system))))

            ;; Simulate DELETE: remove all segments with file-id
            (let [removed-count (#'watch/remove-by-file-id
                                  (:embedding-store system)
                                  (:embedding-model system)
                                  canonical-path)]
              (is (= 4 removed-count) "Should remove all 4 segments"))

            ;; Verify all segments removed
            (is (zero? (count-embeddings (:embedding-store system)
                                         (:embedding-model system)))))
          (finally
            (fs/delete-tree temp-dir)))))))
