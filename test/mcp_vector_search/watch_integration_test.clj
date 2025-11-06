(ns mcp-vector-search.watch-integration-test
  "Integration tests for watch re-indexing logic without beholder timing dependencies."
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.ingest.chunked]
    [mcp-vector-search.ingest.common :as common]
    [mcp-vector-search.watch :as watch])
  (:import
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.model.output
      Response)
    (dev.langchain4j.store.embedding
      EmbeddingSearchRequest
      EmbeddingSearchResult)
    (dev.langchain4j.store.embedding.filter.comparison
      IsEqualTo)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)
    (java.util
      Collection)))


(defn- count-embeddings
  "Count embeddings in the store."
  [^InMemoryEmbeddingStore embedding-store
   ^AllMiniLmL6V2EmbeddingModel embedding-model]
  (let [^Response response (.embed embedding-model (TextSegment/from "test"))
        query-embedding (.content response)
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int 1000))
                    (.build))
        ^EmbeddingSearchResult results (.search embedding-store request)]
    (count (.matches results))))


(defn- count-embeddings-by-file-id
  "Count embeddings with a specific file-id in the store."
  [^InMemoryEmbeddingStore embedding-store
   ^AllMiniLmL6V2EmbeddingModel embedding-model
   file-id]
  (let [^Response response (.embed embedding-model (TextSegment/from "test"))
        query-embedding (.content response)
        metadata-filter (IsEqualTo. "file-id" file-id)
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding query-embedding)
                    (.maxResults (int 1000))
                    (.filter metadata-filter)
                    (.build))
        ^EmbeddingSearchResult results (.search embedding-store request)]
    (count (.matches results))))


;; Test strategy for multi-segment files

;; Define at load time to ensure it's available for all test runs
(defmethod common/process-document :test-watch-multi-segment
  [_strategy path content metadata]
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
                                         :line-number idx)]
            {:file-id file-id
             :segment-id segment-id
             :text-to-embed line
             :content-to-store line
             :metadata enhanced-metadata}))
        lines))))


(deftest re-indexing-logic-test
  ;; Test re-indexing logic that would be triggered by watch events

  (testing "re-indexing logic"

    (testing "re-ingests modified files (simulating MODIFY event)"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Initial content")
                config {:sources [{:path (str (fs/file temp-dir "*.md"))}]}
                parsed-config (config/process-config config)]

            ;; Initial ingest
            (ingest/ingest system parsed-config)
            (is (= 1 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

            ;; Simulate MODIFY event: remove old, re-ingest new
            (let [^InMemoryEmbeddingStore store (:embedding-store @system)
                  ^Collection ids [canonical-path]]
              (.removeAll store ids))
            (spit test-file "Modified content")

            (let [file-map {:file test-file
                            :path canonical-path
                            :captures {}
                            :metadata {}
                            :ingest :whole-document}]
              (ingest/ingest-file system file-map))

            ;; Should still have 1 document
            (is (= 1 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system)))
                "Should have 1 document after re-indexing"))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes deleted files (simulating DELETE event)"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Content to delete")
                config {:sources [{:path (str (fs/file temp-dir "*.md"))}]}
                parsed-config (config/process-config config)]

            ;; Initial ingest
            (ingest/ingest system parsed-config)
            (is (= 1 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

            ;; Simulate DELETE event
            (let [^InMemoryEmbeddingStore store (:embedding-store @system)
                  ^Collection ids [canonical-path]]
              (.removeAll store ids))

            ;; Should have 0 documents
            (is (zero? (count-embeddings (:embedding-store @system)
                                         (:embedding-model @system)))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "adds newly created files (simulating CREATE event)"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          ;; Start with empty store
          (is (zero? (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

          ;; Simulate CREATE event: ingest new file
          (let [new-file (fs/file temp-dir "new.md")
                _ (spit new-file "New content")
                file-map {:file new-file
                          :path (str new-file)
                          :captures {}
                          :metadata {}
                          :ingest :whole-document}]
            (ingest/ingest-file system file-map))

          ;; Should have 1 document
          (is (= 1 (count-embeddings (:embedding-store @system)
                                     (:embedding-model @system))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "handles multiple rapid changes (simulating debouncing)"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :whole-document}]

            ;; Simulate rapid changes (last one wins after debouncing)
            (dotimes [i 5]
              (spit test-file (str "Change " i))
              (let [^InMemoryEmbeddingStore store (:embedding-store @system)
                    ^Collection ids [canonical-path]]
                (.removeAll store ids))
              (ingest/ingest-file system file-map))

            ;; Should have exactly 1 document
            (is (= 1 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system)))))
          (finally
            (fs/delete-tree temp-dir)))))))


(deftest multi-segment-watch-test
  ;; Test watch operations with multi-segment files

  (testing "multi-segment file operations"

    (testing "creates multiple segments for multi-line file"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                ;; Create file with 3 lines
                _ (spit test-file "Line 1\nLine 2\nLine 3")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :test-watch-multi-segment}]

            ;; Ingest multi-segment file
            (ingest/ingest-file system file-map)

            ;; Should have 3 segments total
            (is (= 3 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

            ;; All segments should have the same file-id
            (is (= 3 (count-embeddings-by-file-id (:embedding-store @system)
                                                  (:embedding-model @system)
                                                  canonical-path))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all segments on file modification"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Line 1\nLine 2\nLine 3")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :test-watch-multi-segment}]

            ;; Initial ingest - 3 segments
            (ingest/ingest-file system file-map)
            (is (= 3 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

            ;; Simulate MODIFY: remove all segments with file-id
            (let [removed-count (#'watch/remove-by-file-id
                                 (:embedding-store @system)
                                 (:embedding-model @system)
                                 canonical-path)]
              (is (= 3 removed-count) "Should remove all 3 segments"))

            ;; Verify all segments removed
            (is (zero? (count-embeddings (:embedding-store @system)
                                         (:embedding-model @system))))

            ;; Re-ingest with modified content (2 lines now)
            (spit test-file "New line 1\nNew line 2")
            (ingest/ingest-file system file-map)

            ;; Should have 2 new segments
            (is (= 2 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system)))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all segments on file deletion"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.txt")
                canonical-path (.getCanonicalPath test-file)
                _ (spit test-file "Line 1\nLine 2\nLine 3\nLine 4")
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :test-watch-multi-segment}]

            ;; Initial ingest - 4 segments
            (ingest/ingest-file system file-map)
            (is (= 4 (count-embeddings (:embedding-store @system)
                                       (:embedding-model @system))))

            ;; Simulate DELETE: remove all segments with file-id
            (let [removed-count (#'watch/remove-by-file-id
                                 (:embedding-store @system)
                                 (:embedding-model @system)
                                 canonical-path)]
              (is (= 4 removed-count) "Should remove all 4 segments"))

            ;; Verify all segments removed
            (is (zero? (count-embeddings (:embedding-store @system)
                                         (:embedding-model @system)))))
          (finally
            (fs/delete-tree temp-dir)))))))


(deftest chunked-pipeline-watch-test
  ;; Test watch operations with chunked pipeline strategy
  ;; Verify that file modifications correctly remove all old chunks and create new ones

  (testing "chunked pipeline file operations"

    (testing "creates multiple chunks for large file"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                ;; Create content >1500 chars to generate multiple chunks
                ;; With default 512 char chunks, this should create 3-4 chunks
                test-content (str/join "\n\n"
                                       ["# Document Title"
                                        "This is a test document with substantial content to verify chunked ingestion."
                                        (apply str (repeat 200 "A "))
                                        (apply str (repeat 200 "B "))
                                        (apply str (repeat 200 "C "))
                                        "Final paragraph with some concluding text."])
                _ (spit test-file test-content)
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :chunked}]

            ;; Ingest chunked file
            (ingest/ingest-file system file-map)

            ;; Should have multiple chunks (at least 2)
            (let [chunk-count (count-embeddings (:embedding-store @system)
                                                (:embedding-model @system))]
              (is (>= chunk-count 2)
                  (str "Should create at least 2 chunks, got " chunk-count)))

            ;; All chunks should have the same file-id
            (is (= (count-embeddings (:embedding-store @system)
                                     (:embedding-model @system))
                   (count-embeddings-by-file-id (:embedding-store @system)
                                                (:embedding-model @system)
                                                canonical-path))
                "All chunks should share the same file-id"))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all chunks on file modification"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                ;; Initial content with 3+ chunks
                initial-content (str/join "\n\n"
                                          [(apply str (repeat 200 "A "))
                                           (apply str (repeat 200 "B "))
                                           (apply str (repeat 200 "C "))])
                _ (spit test-file initial-content)
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :chunked}]

            ;; Initial ingest
            (ingest/ingest-file system file-map)
            (let [initial-count (count-embeddings (:embedding-store @system)
                                                  (:embedding-model @system))]
              (is (>= initial-count 2)
                  (str "Initial ingestion should create at least 2 chunks, got " initial-count))

              ;; Simulate MODIFY: remove all chunks by file-id
              (let [removed-count (#'watch/remove-by-file-id
                                   (:embedding-store @system)
                                   (:embedding-model @system)
                                   canonical-path)]
                (is (= initial-count removed-count)
                    (str "Should remove all " initial-count " chunks, removed " removed-count)))

              ;; Verify all chunks removed
              (is (zero? (count-embeddings (:embedding-store @system)
                                           (:embedding-model @system)))
                  "All chunks should be removed after file-id based removal")

              ;; Re-ingest with modified content (different size → different chunk count)
              (let [modified-content (str/join "\n\n"
                                               ["Short content"
                                                (apply str (repeat 150 "D "))])
                    _ (spit test-file modified-content)]
                (ingest/ingest-file system file-map)

                ;; Should have new chunks (possibly different count)
                (let [new-count (count-embeddings (:embedding-store @system)
                                                  (:embedding-model @system))]
                  (is (>= new-count 1)
                      (str "Re-ingestion should create at least 1 chunk, got " new-count))))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "removes all chunks on file deletion"
      (let [temp-dir (fs/create-temp-dir)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                test-content (str/join "\n\n"
                                       [(apply str (repeat 200 "A "))
                                        (apply str (repeat 200 "B "))
                                        (apply str (repeat 200 "C "))
                                        (apply str (repeat 200 "D "))])
                _ (spit test-file test-content)
                file-map {:file test-file
                          :path canonical-path
                          :captures {}
                          :metadata {}
                          :ingest :chunked}]

            ;; Initial ingest
            (ingest/ingest-file system file-map)
            (let [chunk-count (count-embeddings (:embedding-store @system)
                                                (:embedding-model @system))]
              (is (>= chunk-count 2)
                  (str "Should create at least 2 chunks, got " chunk-count))

              ;; Simulate DELETE: remove all chunks by file-id
              (let [removed-count (#'watch/remove-by-file-id
                                   (:embedding-store @system)
                                   (:embedding-model @system)
                                   canonical-path)]
                (is (= chunk-count removed-count)
                    (str "Should remove all " chunk-count " chunks, removed " removed-count)))

              ;; Verify all chunks removed
              (is (zero? (count-embeddings (:embedding-store @system)
                                           (:embedding-model @system)))
                  "All chunks should be removed after deletion")))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "verifies chunk metadata during re-indexing"
      (let [temp-dir (fs/create-temp-dir)
            _ (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                     :embedding-store (InMemoryEmbeddingStore.)
                     :metadata-values {}})]
        (try
          (let [test-file (fs/file temp-dir "test.md")
                canonical-path (.getCanonicalPath test-file)
                test-content (str/join "\n\n"
                                       [(apply str (repeat 200 "A "))
                                        (apply str (repeat 200 "B "))
                                        (apply str (repeat 200 "C "))])
                _ (spit test-file test-content)
                metadata {:custom "value"
                          :chunk-size 400
                          :chunk-overlap 80}
                ;; Process document directly to get segments
                segments (common/process-document :chunked
                                                  canonical-path
                                                  test-content
                                                  metadata)]

            ;; Verify all segments have required chunk metadata
            (is (every? #(contains? (:metadata %) :chunk-index) segments)
                "All segments should have :chunk-index")
            (is (every? #(contains? (:metadata %) :chunk-count) segments)
                "All segments should have :chunk-count")
            (is (every? #(contains? (:metadata %) :chunk-offset) segments)
                "All segments should have :chunk-offset")

            ;; Verify custom metadata propagated to all chunks
            (is (every? #(= "value" (get-in % [:metadata :custom])) segments)
                "Custom metadata should propagate to all chunks")

            ;; Verify chunk indices are sequential
            (is (= (range (count segments))
                   (map #(get-in % [:metadata :chunk-index]) segments))
                "Chunk indices should be sequential from 0")

            ;; Verify all chunks know the total count
            (is (every? #(= (count segments) (get-in % [:metadata :chunk-count])) segments)
                "All chunks should have same :chunk-count"))
          (finally
            (fs/delete-tree temp-dir)))))))
