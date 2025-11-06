(ns mcp-vector-search.ingest.chunked-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.ingest.chunked]
    [mcp-vector-search.ingest.common :as common])
  (:import
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))


(deftest chunked-document-test
  ;; Test :chunked pipeline strategy for splitting documents into chunks
  (testing "chunked document strategy"

    (testing "small document (< chunk-size) produces single chunk"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-small")
            test-file (io/file test-dir "small.txt")
            content "Short text"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:source "test"}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            (is (= 1 (count segment-descriptors)))
            (let [segment (first segment-descriptors)]
              (is (= path (:file-id segment)))
              (is (= content (:text-to-embed segment)))
              (is (= content (:content-to-store segment)))
              (is (= path (get-in segment [:metadata :doc-id])))
              (is (= path (get-in segment [:metadata :file-id])))
              (is (= 0 (get-in segment [:metadata :chunk-index])))
              (is (= 1 (get-in segment [:metadata :chunk-count])))
              (is (= 0 (get-in segment [:metadata :chunk-offset])))
              (is (= "test" (get-in segment [:metadata :source])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "medium document (1500 chars) produces multiple chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-medium")
            test-file (io/file test-dir "medium.txt")
            ;; Create content with paragraphs to test paragraph splitting
            para1 (apply str (repeat 50 "First para. "))
            para2 (apply str (repeat 50 "Second para. "))
            para3 (apply str (repeat 50 "Third para. "))
            content (str para1 "\n\n" para2 "\n\n" para3)]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1))
            ;; Verify all chunks have the same chunk-count
            (is (apply = (map #(get-in % [:metadata :chunk-count]) segment-descriptors)))
            ;; Verify chunk-count matches actual count
            (is (= (count segment-descriptors) (get-in (first segment-descriptors) [:metadata :chunk-count])))
            ;; Verify chunk indices are sequential
            (is (= (range (count segment-descriptors))
                   (map #(get-in % [:metadata :chunk-index]) segment-descriptors)))
            ;; Verify all chunks share the same doc-id and file-id
            (is (apply = path (map #(get-in % [:metadata :doc-id]) segment-descriptors)))
            (is (apply = path (map #(get-in % [:metadata :file-id]) segment-descriptors))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "chunk metadata includes all required fields"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-meta")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Word "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:category "docs" :version "v1"}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            ;; All segments should have required chunk metadata
            (doseq [segment segment-descriptors]
              (is (contains? (:metadata segment) :doc-id))
              (is (contains? (:metadata segment) :file-id))
              (is (contains? (:metadata segment) :segment-id))
              (is (contains? (:metadata segment) :chunk-index))
              (is (contains? (:metadata segment) :chunk-count))
              (is (contains? (:metadata segment) :chunk-offset))
              ;; Base metadata should be preserved
              (is (= "docs" (get-in segment [:metadata :category])))
              (is (= "v1" (get-in segment [:metadata :version])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "segment-id uniqueness across chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-unique")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Text "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)
                segment-ids (map :segment-id segment-descriptors)]
            ;; All segment IDs should be unique
            (is (= (count segment-ids) (count (set segment-ids))))
            ;; Segment IDs should follow the pattern "path#N"
            (is (every? #(re-matches #".*#\d+" %) segment-ids)))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "custom chunk-size and chunk-overlap from metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-custom")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 200 "A "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                ;; Use smaller chunk size to force more chunks
                metadata {:chunk-size 100 :chunk-overlap 20}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            ;; With smaller chunk size, should produce multiple chunks
            (is (> (count segment-descriptors) 1)))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "chunk offset tracking"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/chunked-offset")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Word "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            ;; First chunk should start at offset 0
            (is (= 0 (get-in (first segment-descriptors) [:metadata :chunk-offset])))
            ;; Verify each chunk's content matches the substring at its offset
            (doseq [segment segment-descriptors]
              (let [offset (get-in segment [:metadata :chunk-offset])
                    chunk-content (:text-to-embed segment)
                    expected-content (subs content offset (min (count content)
                                                               (+ offset (count chunk-content))))]
                (is (= expected-content chunk-content)
                    (str "Chunk at offset " offset " does not match content substring"))))
            ;; Offsets should be increasing (but may not be strictly sequential due to overlap)
            (when (> (count segment-descriptors) 1)
              (is (> (get-in (second segment-descriptors) [:metadata :chunk-offset]) 0))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))


(deftest chunk-overlap-and-metadata-test
  ;; Test chunk overlap behavior and metadata propagation for :chunked strategy
  (testing "chunk overlap and metadata propagation"

    (testing "verifies overlap between adjacent chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/overlap")
            test-file (io/file test-dir "doc.txt")
            ;; Create content that will span multiple chunks with default settings (512 size, 100 overlap)
            paragraph (apply str (repeat 100 "Word "))  ; ~500 chars
            content (str paragraph "\n\n" paragraph "\n\n" paragraph)]  ; ~1500 chars
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 512 :chunk-overlap 100}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             metadata)]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1)
                "Content should be split into multiple chunks")
            ;; Verify overlap between adjacent chunks
            (doseq [[chunk-n chunk-n+1] (partition 2 1 segment-descriptors)]
              (let [^String content-n (:text-to-embed chunk-n)
                    ^String content-n+1 (:text-to-embed chunk-n+1)
                    overlap-size 100
                    ;; Get last overlap-size chars from chunk N
                    suffix-n (subs content-n (max 0 (- (count content-n) overlap-size)))
                    ;; Get first overlap-size chars from chunk N+1
                    prefix-n+1 (subs content-n+1 0 (min overlap-size (count content-n+1)))]
                ;; The overlap may not be exactly 100 chars due to paragraph boundaries,
                ;; but there should be some overlap between adjacent chunks.
                ;; Check if suffix appears in the prefix (accounting for boundary trimming)
                (is (.contains content-n+1 (subs suffix-n (max 0 (- (count suffix-n) (quot overlap-size 2)))))
                    (str "Chunks should have some overlap. "
                         "Chunk " (get-in chunk-n [:metadata :chunk-index])
                         " suffix: " (pr-str (subs suffix-n (max 0 (- (count suffix-n) (quot overlap-size 2)))))
                         ", Chunk " (get-in chunk-n+1 [:metadata :chunk-index])
                         " content: " (pr-str (subs content-n+1 0 (min 100 (count content-n+1)))))))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "propagates base metadata to all chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/base-meta")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 200 "Text "))]  ; Force multiple chunks
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                base-metadata {:project "test-project"
                               :type "documentation"
                               :version "v1.0"
                               :chunk-size 200
                               :chunk-overlap 50}
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             base-metadata)]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1))
            ;; All chunks should have base metadata
            (doseq [segment segment-descriptors]
              (is (= "test-project" (get-in segment [:metadata :project])))
              (is (= "documentation" (get-in segment [:metadata :type])))
              (is (= "v1.0" (get-in segment [:metadata :version])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "propagates path spec captures to all chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/captures")
            category-dir (io/file test-dir "guides")
            test-file (io/file category-dir "install.md")
            content (apply str (repeat 200 "Guide text "))]  ; Force multiple chunks
        (.mkdirs category-dir)
        (spit test-file content)
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "category" :pattern "[^/]+"}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "docname" :pattern "[^.]+"}
                                      {:type :literal :value ".md"}]
                           :base-path (.getPath test-dir)
                           :base-metadata {:source "docs"
                                           :chunk-size 200
                                           :chunk-overlap 50}
                           :ingest :chunked}
                file-maps (ingest/files-from-path-spec path-spec)
                _ (is (= 1 (count file-maps)) "Should find one matching file")
                file-map (first file-maps)
                path (:path file-map)
                full-metadata (merge (:metadata file-map)
                                     {:chunk-size 200 :chunk-overlap 50})
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             full-metadata)]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1))
            ;; All chunks should have captures from path spec
            (doseq [segment segment-descriptors]
              (is (= "guides" (get-in segment [:metadata :category]))
                  "Capture 'category' should be in all chunks")
              (is (= "install" (get-in segment [:metadata :docname]))
                  "Capture 'docname' should be in all chunks")
              (is (= "docs" (get-in segment [:metadata :source]))
                  "Base metadata 'source' should be in all chunks")))
          (finally
            (.delete test-file)
            (.delete category-dir)
            (.delete test-dir)))))

    (testing "propagates :name from source config to all chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/name-prop")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 200 "Content "))]  ; Force multiple chunks
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-file)}]
                           :base-path (.getPath test-file)
                           :base-metadata {:name "Test Documentation"
                                           :chunk-size 200
                                           :chunk-overlap 50}
                           :ingest :chunked}
                file-maps (ingest/files-from-path-spec path-spec)
                file-map (first file-maps)
                path (:path file-map)
                segment-descriptors (common/process-document :chunked
                                                             path
                                                             content
                                                             (:metadata file-map))]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1))
            ;; All chunks should have :name from source config
            (doseq [segment segment-descriptors]
              (is (= "Test Documentation" (get-in segment [:metadata :name]))
                  ":name field should be in all chunks")))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))


(deftest validate-chunk-config-test
  ;; Test validation of chunk configuration parameters
  (testing "validate-chunk-config"

    (testing "rejects negative chunk-size"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/validate-negative-size")
            test-file (io/file test-dir "doc.txt")
            content "Test content"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size -1 :chunk-overlap 0}]
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"Invalid :chunk-size.*must be a positive integer"
                  (common/process-document :chunked path content metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "rejects zero chunk-size"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/validate-zero-size")
            test-file (io/file test-dir "doc.txt")
            content "Test content"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 0 :chunk-overlap 0}]
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"Invalid :chunk-size.*must be a positive integer"
                  (common/process-document :chunked path content metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "rejects negative chunk-overlap"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/validate-negative-overlap")
            test-file (io/file test-dir "doc.txt")
            content "Test content"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 512 :chunk-overlap -10}]
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"Invalid :chunk-overlap.*must be a non-negative integer"
                  (common/process-document :chunked path content metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "rejects chunk-overlap equal to chunk-size"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/validate-overlap-eq-size")
            test-file (io/file test-dir "doc.txt")
            content "Test content"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 512 :chunk-overlap 512}]
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"Invalid chunk configuration.*:chunk-overlap must be less than :chunk-size"
                  (common/process-document :chunked path content metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "rejects chunk-overlap greater than chunk-size"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest/chunked_test/validate-overlap-gt-size")
            test-file (io/file test-dir "doc.txt")
            content "Test content"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 100 :chunk-overlap 200}]
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"Invalid chunk configuration.*:chunk-overlap must be less than :chunk-size"
                  (common/process-document :chunked path content metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))
