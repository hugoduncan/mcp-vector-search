(ns mcp-vector-search.ingest-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest :as sut]
    [mcp-vector-search.ingest.chunked]
    [mcp-vector-search.ingest.common :as common]
    [mcp-vector-search.tools :as tools])
  (:import
    (dev.langchain4j.data.document
      Metadata)
    (dev.langchain4j.data.embedding
      Embedding)
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

(deftest files-from-path-spec-test
  ;; Test file matching and metadata capture from path specifications
  (testing "files-from-path-spec"

    (testing "matches literal path"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/literal")
            test-file (io/file test-dir "exact.md")]
        (.mkdirs test-dir)
        (spit test-file "content")
        (try
          (let [path-spec {:segments [{:type :literal
                                       :value (.getPath test-file)}]
                           :base-path (.getPath test-file)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)]
            (is (= 1 (count results)))
            (is (= (.getPath test-file) (:path (first results))))
            (is (= {} (:captures (first results))))
            (is (= :whole-document (:ingest (first results)))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "matches single-level glob"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/glob")
            file1 (io/file test-dir "doc1.md")
            file2 (io/file test-dir "doc2.md")
            file3 (io/file test-dir "other.txt")]
        (.mkdirs test-dir)
        (spit file1 "content1")
        (spit file2 "content2")
        (spit file3 "other")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :glob :pattern "*"}
                                      {:type :literal :value ".md"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)
                paths (set (map :path results))]
            (is (= 2 (count results)))
            (is (contains? paths (.getPath file1)))
            (is (contains? paths (.getPath file2)))
            (is (not (contains? paths (.getPath file3)))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete file3)
            (.delete test-dir)))))

    (testing "matches recursive glob"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/recursive")
            subdir (io/file test-dir "sub")
            file1 (io/file test-dir "root.md")
            file2 (io/file subdir "nested.md")]
        (.mkdirs subdir)
        (spit file1 "root")
        (spit file2 "nested")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :glob :pattern "**"}
                                      {:type :literal :value ".md"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)
                paths (set (map :path results))]
            (is (= 2 (count results)))
            (is (contains? paths (.getPath file1)))
            (is (contains? paths (.getPath file2))))
          (finally
            (.delete file2)
            (.delete file1)
            (.delete subdir)
            (.delete test-dir)))))

    (testing "extracts single capture"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/capture")
            file1 (io/file test-dir "v1.0")
            file2 (io/file test-dir "v2.3")]
        (.mkdirs test-dir)
        (spit file1 "v1")
        (spit file2 "v2")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/v"}
                                      {:type :capture :name "version" :pattern "[0-9.]+"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)
                by-path (into {} (map (fn [r] [(:path r) r]) results))]
            (is (= 2 (count results)))
            (is (= "1.0" (get-in by-path [(.getPath file1) :captures :version])))
            (is (= "2.3" (get-in by-path [(.getPath file2) :captures :version]))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "extracts multiple captures"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi")
            subdir (io/file test-dir "clj")
            file1 (io/file subdir "v1-guide.md")]
        (.mkdirs subdir)
        (spit file1 "content")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "lang" :pattern "[^/]+"}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "ver" :pattern "v[0-9]+"}
                                      {:type :literal :value "-"}
                                      {:type :capture :name "topic" :pattern "[^.]+"}
                                      {:type :literal :value ".md"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)]
            (is (= 1 (count results)))
            (is (= "clj" (get-in results [0 :captures :lang])))
            (is (= "v1" (get-in results [0 :captures :ver])))
            (is (= "guide" (get-in results [0 :captures :topic]))))
          (finally
            (.delete file1)
            (.delete subdir)
            (.delete test-dir)))))

    (testing "combines glob and captures"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/combined")
            v1-dir (io/file test-dir "v1" "guides")
            v2-dir (io/file test-dir "v2" "tutorials")
            file1 (io/file v1-dir "install.md")
            file2 (io/file v2-dir "setup.md")]
        (.mkdirs v1-dir)
        (.mkdirs v2-dir)
        (spit file1 "guide")
        (spit file2 "tutorial")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "version" :pattern "v[0-9]+"}
                                      {:type :literal :value "/"}
                                      {:type :glob :pattern "**"}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "doc" :pattern "[^.]+"}
                                      {:type :literal :value ".md"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)
                by-path (into {} (map (fn [r] [(:path r) r]) results))]
            (is (= 2 (count results)))
            (is (= "v1" (get-in by-path [(.getPath file1) :captures :version])))
            (is (= "install" (get-in by-path [(.getPath file1) :captures :doc])))
            (is (= "v2" (get-in by-path [(.getPath file2) :captures :version])))
            (is (= "setup" (get-in by-path [(.getPath file2) :captures :doc]))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete v1-dir)
            (.delete v2-dir)
            (.delete test-dir)))))

    (testing "merges captures with base metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/metadata")
            file1 (io/file test-dir "v1.txt")]
        (.mkdirs test-dir)
        (spit file1 "data")
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/"}
                                      {:type :capture :name "version" :pattern "v[0-9]+"}
                                      {:type :literal :value ".txt"}]
                           :base-path (.getPath test-dir)
                           :base-metadata {:source "docs" :type "guide"}
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)]
            (is (= 1 (count results)))
            (is (= {:source "docs" :type "guide" :version "v1"}
                   (:metadata (first results)))))
          (finally
            (.delete file1)
            (.delete test-dir)))))

    (testing "returns empty sequence when no matches"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/empty")]
        (.mkdirs test-dir)
        (try
          (let [path-spec {:segments [{:type :literal :value (.getPath test-dir)}
                                      {:type :literal :value "/nonexistent.md"}]
                           :base-path (.getPath test-dir)
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)]
            (is (empty? results)))
          (finally
            (.delete test-dir)))))))

(deftest ingest-files-test
  ;; Test ingestion of files with their metadata into the embedding store
  (testing "ingest-files"

    (testing "ingests single file with metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/ingest")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "test content")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:source "test"}
                            :ingest :whole-document}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 0 (:failed result)))
            (is (empty? (:failures result))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "ingests multiple files"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-ingest")
            file1 (io/file test-dir "doc1.txt")
            file2 (io/file test-dir "doc2.txt")]
        (.mkdirs test-dir)
        (spit file1 "content 1")
        (spit file2 "content 2")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {:version "v1"}
                            :ingest :whole-document}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {:version "v2"}
                            :ingest :whole-document}]
                result (sut/ingest-files system file-maps)]
            (is (= 2 (:ingested result)))
            (is (= 0 (:failed result))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "handles file read errors"
      (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)}
            nonexistent-file (io/file "nonexistent.txt")
            file-maps [{:file nonexistent-file
                        :path (.getPath nonexistent-file)
                        :metadata {}
                        :ingest :whole-document}]
            result (sut/ingest-files system file-maps)]
        (is (= 0 (:ingested result)))
        (is (= 1 (:failed result)))
        (is (= 1 (count (:failures result))))
        (is (some? (:error (first (:failures result)))))))))

(deftest ingest-test
  ;; Test end-to-end ingestion with path specs
  (testing "ingest"

    (testing "ingests files from path-specs"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/e2e")
            file1 (io/file test-dir "v1.md")
            file2 (io/file test-dir "v2.md")]
        (.mkdirs test-dir)
        (spit file1 "version 1")
        (spit file2 "version 2")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath test-dir)}
                                     {:type :literal :value "/"}
                                     {:type :capture :name "version" :pattern "v[0-9]+"}
                                     {:type :literal :value ".md"}]
                          :base-path (.getPath test-dir)
                          :base-metadata {:type "doc"}
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 2 (:ingested result)))
            (is (= 0 (:failed result))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "handles multiple path-specs"
      (let [test-dir1 (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-spec1")
            test-dir2 (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-spec2")
            file1 (io/file test-dir1 "a.txt")
            file2 (io/file test-dir2 "b.txt")]
        (.mkdirs test-dir1)
        (.mkdirs test-dir2)
        (spit file1 "a")
        (spit file2 "b")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath file1)}]
                          :base-path (.getPath file1)
                          :ingest :whole-document}
                         {:segments [{:type :literal :value (.getPath file2)}]
                          :base-path (.getPath file2)
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 2 (:ingested result)))
            (is (= 0 (:failed result))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir1)
            (.delete test-dir2)))))

    (testing "returns empty stats when no files match"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/empty-spec")]
        (.mkdirs test-dir)
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath test-dir)}
                                     {:type :literal :value "/nonexistent.txt"}]
                          :base-path (.getPath test-dir)
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 0 (:ingested result)))
            (is (= 0 (:failed result))))
          (finally
            (.delete test-dir)))))))

(deftest metadata-tracking-test
  ;; Test tracking of metadata field values during ingestion
  (testing "metadata tracking"

    (testing "tracks distinct metadata values"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/tracking")
            file1 (io/file test-dir "v1.md")
            file2 (io/file test-dir "v2.md")
            file3 (io/file test-dir "v1-copy.md")]
        (.mkdirs test-dir)
        (spit file1 "version 1")
        (spit file2 "version 2")
        (spit file3 "version 1 copy")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath test-dir)}
                                     {:type :literal :value "/"}
                                     {:type :capture :name "version" :pattern "v[0-9]+"}
                                     {:type :glob :pattern "*"}
                                     {:type :literal :value ".md"}]
                          :base-path (.getPath test-dir)
                          :base-metadata {:type "doc"}
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 3 (:ingested result)))
            (is (= #{:version :type :doc-id :file-id :segment-id} (set (keys @metadata-values))))
            (is (= #{"v1" "v2"} (:version @metadata-values)))
            (is (= #{"doc"} (:type @metadata-values))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete file3)
            (.delete test-dir)))))

    (testing "accumulates metadata across multiple ingestions"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/accumulate")
            file1 (io/file test-dir "a.txt")
            file2 (io/file test-dir "b.txt")]
        (.mkdirs test-dir)
        (spit file1 "content a")
        (spit file2 "content b")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                config1 {:path-specs
                         [{:segments [{:type :literal :value (.getPath file1)}]
                           :base-path (.getPath file1)
                           :base-metadata {:category "type1"}
                           :ingest :whole-document}]}
                config2 {:path-specs
                         [{:segments [{:type :literal :value (.getPath file2)}]
                           :base-path (.getPath file2)
                           :base-metadata {:category "type2"}
                           :ingest :whole-document}]}]
            (sut/ingest system config1)
            (is (= #{"type1"} (:category @metadata-values)))
            (sut/ingest system config2)
            (is (= #{"type1" "type2"} (:category @metadata-values))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "handles empty metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/no-meta")
            file1 (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit file1 "content")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath file1)}]
                          :base-path (.getPath file1)
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 1 (:ingested result)))
            (is (= #{:doc-id :file-id :segment-id} (set (keys @metadata-values))))
            (is (= #{(.getPath file1)} (:doc-id @metadata-values))))
          (finally
            (.delete file1)
            (.delete test-dir)))))

    (testing "tracks :doc-id in metadata values"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/doc-id")
            file1 (io/file test-dir "doc1.txt")
            file2 (io/file test-dir "doc2.txt")]
        (.mkdirs test-dir)
        (spit file1 "content 1")
        (spit file2 "content 2")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                config {:path-specs
                        [{:segments [{:type :literal :value (.getPath test-dir)}
                                     {:type :literal :value "/"}
                                     {:type :glob :pattern "*"}
                                     {:type :literal :value ".txt"}]
                          :base-path (.getPath test-dir)
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 2 (:ingested result)))
            (is (= #{(.getPath file1) (.getPath file2)} (:doc-id @metadata-values))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))))

(deftest namespace-doc-embedding-test
  ;; Test :namespace-doc embedding strategy that embeds namespace docstrings
  ;; while storing full file content
  (testing "namespace-doc embedding strategy"

    (testing "ingests Clojure file with namespace docstring"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/ns-doc")
            test-file (io/file test-dir "with_doc.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns foo.bar \"A namespace\" (:require [clojure.string]))\n(defn f [] :x)")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:source "test"}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 0 (:failed result)))
            (is (= #{"foo.bar"} (:namespace @metadata-values)))
            (is (= #{"test"} (:source @metadata-values))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "skips file without namespace docstring"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/ns-no-doc")
            test-file (io/file test-dir "no_doc.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns foo.baz)\n(defn g [] :y)")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 0 (:ingested result)))
            (is (= 1 (:failed result)))
            (is (= "No namespace docstring found" (:error (first (:failures result))))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "skips file without ns form"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/no-ns")
            test-file (io/file test-dir "no_ns.txt")]
        (.mkdirs test-dir)
        (spit test-file "just some text")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 0 (:ingested result)))
            (is (= 1 (:failed result)))
            (is (= "No ns form found" (:error (first (:failures result))))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "skips malformed file"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/malformed")
            test-file (io/file test-dir "bad.clj")]
        (.mkdirs test-dir)
        (spit test-file "this is {{{ malformed [[[")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 0 (:ingested result)))
            (is (= 1 (:failed result))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "adds namespace to metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/ns-meta")
            test-file (io/file test-dir "meta.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns com.example \"Example NS\")")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:type "lib"}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= #{"com.example"} (:namespace @metadata-values)))
            (is (= #{"lib"} (:type @metadata-values))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "adds :doc-id to metadata with :namespace-doc strategy"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/ns-doc-id")
            test-file (io/file test-dir "code.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns my.ns \"Docs\")")
        (try
          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :namespace-doc}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= #{(.getPath test-file)} (:doc-id @metadata-values))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))

(deftest file-path-ingest-test
  ;; Test :file-path ingest strategy that stores only file paths instead of full content
  (testing "file-path ingest strategy"

    (testing "stores only file path as segment content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/file-path")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "This is the full document content that should not be stored")
        (try
          (let [embedding-store (InMemoryEmbeddingStore.)
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store embedding-store}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:source "test"}
                            :ingest :file-path}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 0 (:failed result)))
            ;; Verify content via search - should return path, not file content
            (let [search-tool (tools/search-tool system {})
                  impl (:implementation search-tool)
                  search-result (impl {} {:query "document content" :limit 1})]
              (is (false? (:isError search-result)) (str "Search failed: " (-> search-result :content first :text)))
              (let [content-text (-> search-result :content first :text)
                    results (json/read-str content-text)
                    returned-content (get (first results) "content")]
                (is (= (.getPath test-file) returned-content))
                (is (not= "This is the full document content that should not be stored" returned-content)))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "preserves metadata from embedding phase"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/fp-meta")
            test-file (io/file test-dir "test.txt")]
        (.mkdirs test-dir)
        (spit test-file "content")
        (try
          (let [metadata-values (atom {})
                embedding-store (InMemoryEmbeddingStore.)
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store embedding-store
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:category "docs" :version "v1"}
                            :ingest :file-path}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= #{"docs"} (:category @metadata-values)))
            (is (= #{"v1"} (:version @metadata-values)))
            ;; Verify metadata via search with filter
            (let [search-tool (tools/search-tool system {})
                  impl (:implementation search-tool)
                  search-result (impl {} {:query "content" :limit 1 :metadata {:category "docs"}})
                  content-text (-> search-result :content first :text)
                  results (json/read-str content-text)]
              (is (= 1 (count results)))
              (is (= (.getPath test-file) (get (first results) "content")))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    ;; NOTE: The combination of :namespace-doc with :file-path
    ;; is not supported by the unified process-document pipeline. The pipeline
    ;; has three strategies: :whole-document, :namespace-doc, and :file-path.
    ;; The :file-path strategy embeds full content and stores only the path.
    ;; The :namespace-doc strategy embeds docstring and stores full content.
    ;; To get namespace-aware search with file-path storage, a new unified
    ;; strategy would be needed.

    (testing "supports multiple files with different paths"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/fp-multi")
            file1 (io/file test-dir "doc1.txt")
            file2 (io/file test-dir "doc2.txt")]
        (.mkdirs test-dir)
        (spit file1 "content one")
        (spit file2 "content two")
        (try
          (let [embedding-store (InMemoryEmbeddingStore.)
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store embedding-store}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {}
                            :ingest :file-path}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {}
                            :ingest :file-path}]
                result (sut/ingest-files system file-maps)]
            (is (= 2 (:ingested result)))
            ;; Verify both paths are returned via search
            (let [search-tool (tools/search-tool system {})
                  impl (:implementation search-tool)
                  search-result (impl {} {:query "content" :limit 2})
                  content-text (-> search-result :content first :text)
                  results (json/read-str content-text)
                  paths (set (map #(get % "content") results))]
              (is (= 2 (count results)))
              (is (contains? paths (.getPath file1)))
              (is (contains? paths (.getPath file2)))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))))

(deftest process-document-test
  ;; Test the unified pipeline multimethod
  (testing "process-document multimethod"

    (testing ":whole-document strategy returns single segment"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/pd-whole")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "test content")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:source "test"}
                segment-descriptors (common/process-document :whole-document
                                                          path
                                                          content
                                                          metadata)]
            (is (= 1 (count segment-descriptors)))
            (let [segment (first segment-descriptors)]
              (is (= path (:file-id segment)))
              (is (= path (:segment-id segment)))
              (is (= content (:text-to-embed segment)))
              (is (= content (:content-to-store segment)))
              (is (= path (get-in segment [:metadata :file-id])))
              (is (= path (get-in segment [:metadata :segment-id])))
              (is (= path (get-in segment [:metadata :doc-id])))
              (is (= "test" (get-in segment [:metadata :source])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing ":namespace-doc strategy returns single segment with namespace"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/pd-ns")
            test-file (io/file test-dir "code.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns example.core \"Core namespace\")\n(defn foo [] :bar)")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:type "src"}
                segment-descriptors (common/process-document :namespace-doc
                                                          path
                                                          content
                                                          metadata)]
            (is (= 1 (count segment-descriptors)))
            (let [segment (first segment-descriptors)]
              (is (= path (:file-id segment)))
              (is (= path (:segment-id segment)))
              (is (= "Core namespace" (:text-to-embed segment)))
              (is (= content (:content-to-store segment)))
              (is (= "example.core" (get-in segment [:metadata :namespace])))
              (is (= path (get-in segment [:metadata :file-id])))
              (is (= path (get-in segment [:metadata :segment-id])))
              (is (= path (get-in segment [:metadata :doc-id])))
              (is (= "src" (get-in segment [:metadata :type])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing ":namespace-doc strategy throws on missing docstring"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/pd-ns-fail")
            test-file (io/file test-dir "no_doc.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns example.nodoc)\n(defn bar [] :baz)")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {}]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"No namespace docstring found"
                                  (common/process-document :namespace-doc
                                                        path
                                                        content
                                                        metadata))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing ":file-path strategy stores path as content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/pd-path")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "full content here")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:category "docs"}
                segment-descriptors (common/process-document :file-path
                                                          path
                                                          content
                                                          metadata)]
            (is (= 1 (count segment-descriptors)))
            (let [segment (first segment-descriptors)]
              (is (= path (:file-id segment)))
              (is (= path (:segment-id segment)))
              (is (= content (:text-to-embed segment)))
              ;; Content should be path, not full content
              (is (= path (:content-to-store segment)))
              (is (= path (get-in segment [:metadata :file-id])))
              (is (= path (get-in segment [:metadata :segment-id])))
              (is (= path (get-in segment [:metadata :doc-id])))
              (is (= "docs" (get-in segment [:metadata :category])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "multimethod dispatch on unknown strategy throws"
      (is (thrown? IllegalArgumentException
                   (common/process-document :unknown-strategy
                                         "path"
                                         "content"
                                         {}))))))


(deftest error-tracking-test
  ;; Test error tracking and statistics updates during ingestion
  (testing "error tracking"

    (testing "classifies and records read errors"
      (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                    :embedding-store (InMemoryEmbeddingStore.)
                    :ingestion-failures []}
            nonexistent-file (io/file "nonexistent.txt")
            file-maps [{:file nonexistent-file
                        :path (.getPath nonexistent-file)
                        :metadata {}
                        :ingest :whole-document
                        :source-path "/test/*"}]
            system-atom (atom system)
            result (sut/ingest-file system system-atom (first file-maps))]
        (is (some? (:error result)))
        (is (= :read-error (:error-type result)))
        (is (= 1 (count (:ingestion-failures @system-atom))))
        (let [error (first (:ingestion-failures @system-atom))]
          (is (= (.getPath nonexistent-file) (:file-path error)))
          (is (= :read-error (:error-type error)))
          (is (= "/test/*" (:source-path error)))
          (is (some? (:message error)))
          (is (some? (:timestamp error))))))

    (testing "classifies and records parse errors"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/parse-err")
            test-file (io/file test-dir "bad.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns foo)")
        (try
          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :ingestion-failures []}
                system-atom (atom system)
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :namespace-doc
                            :source-path "/src/**/*.clj"}]
                result (sut/ingest-file system system-atom (first file-maps))]
            (is (some? (:error result)))
            (is (= :parse-error (:error-type result)))
            (is (= 1 (count (:ingestion-failures @system-atom))))
            (let [error (first (:ingestion-failures @system-atom))]
              (is (= :parse-error (:error-type error)))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "bounded queue drops oldest errors when full"
      (let [system-atom (atom {:ingestion-failures []})
            ;; Add 25 errors to exceed limit of 20
            errors (map (fn [n]
                          {:file-path (str "file" n)
                           :error-type :read-error
                           :message "error"
                           :source-path "/test/*"
                           :timestamp (java.time.Instant/now)})
                        (range 25))]
        (doseq [error errors]
          (#'sut/add-error-to-queue system-atom error))
        ;; Should only have last 20 errors
        (is (= 20 (count (:ingestion-failures @system-atom))))
        ;; First error should be file5 (dropped file0-file4)
        (is (= "file5" (:file-path (first (:ingestion-failures @system-atom)))))
        ;; Last error should be file24
        (is (= "file24" (:file-path (last (:ingestion-failures @system-atom)))))))))

(deftest statistics-tracking-test
  ;; Test ingestion statistics tracking during file processing
  (testing "statistics tracking"

    (testing "tracks files-matched and files-processed"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/stats")
            file1 (io/file test-dir "doc1.txt")
            file2 (io/file test-dir "doc2.txt")]
        (.mkdirs test-dir)
        (spit file1 "content 1")
        (spit file2 "content 2")
        (try
          (let [system-atom (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                                   :embedding-store (InMemoryEmbeddingStore.)
                                   :metadata-values {}
                                   :ingestion-failures []
                                   :ingestion-stats {:sources [{:path "/test/**/*.txt"
                                                                :files-matched 0
                                                                :files-processed 0
                                                                :segments-created 0
                                                                :errors 0}]
                                                     :total-documents 0
                                                     :total-segments 0
                                                     :total-errors 0
                                                     :last-ingestion-at nil}})
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {}
                            :ingest :whole-document
                            :source-path "/test/**/*.txt"}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {}
                            :ingest :whole-document
                            :source-path "/test/**/*.txt"}]
                result (sut/ingest-files system-atom file-maps)]
            (is (= 2 (:ingested result)))
            (is (= 0 (:failed result)))
            (let [stats (get-in @system-atom [:ingestion-stats])
                  source-stats (first (:sources stats))]
              (is (= 2 (:files-matched source-stats)))
              (is (= 2 (:files-processed source-stats)))
              (is (= 2 (:segments-created source-stats)))
              (is (= 0 (:errors source-stats)))
              (is (= 2 (:total-documents stats)))
              (is (= 2 (:total-segments stats)))
              (is (= 0 (:total-errors stats)))
              (is (some? (:last-ingestion-at stats)))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "tracks errors in statistics"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/stats-err")
            file1 (io/file test-dir "good.txt")
            nonexistent (io/file test-dir "missing.txt")]
        (.mkdirs test-dir)
        (spit file1 "content")
        (try
          (let [system-atom (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                                   :embedding-store (InMemoryEmbeddingStore.)
                                   :metadata-values {}
                                   :ingestion-failures []
                                   :ingestion-stats {:sources [{:path "/test/**/*.txt"
                                                                :files-matched 0
                                                                :files-processed 0
                                                                :segments-created 0
                                                                :errors 0}]
                                                     :total-documents 0
                                                     :total-segments 0
                                                     :total-errors 0
                                                     :last-ingestion-at nil}})
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {}
                            :ingest :whole-document
                            :source-path "/test/**/*.txt"}
                           {:file nonexistent
                            :path (.getPath nonexistent)
                            :metadata {}
                            :ingest :whole-document
                            :source-path "/test/**/*.txt"}]
                result (sut/ingest-files system-atom file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 1 (:failed result)))
            (let [stats (get-in @system-atom [:ingestion-stats])
                  source-stats (first (:sources stats))]
              (is (= 2 (:files-matched source-stats)))
              (is (= 1 (:files-processed source-stats)))
              (is (= 1 (:segments-created source-stats)))
              (is (= 1 (:errors source-stats)))
              (is (= 1 (:total-documents stats)))
              (is (= 1 (:total-segments stats)))
              (is (= 1 (:total-errors stats)))))
          (finally
            (.delete file1)
            (.delete test-dir)))))

    (testing "tracks segment counts correctly"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/stats-seg")
            file1 (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit file1 "content")
        (try
          ;; Use whole-document strategy which creates 1 segment
          (let [system-atom (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                                   :embedding-store (InMemoryEmbeddingStore.)
                                   :metadata-values {}
                                   :ingestion-failures []
                                   :ingestion-stats {:sources [{:path "/test/**/*.txt"
                                                                :files-matched 0
                                                                :files-processed 0
                                                                :segments-created 0
                                                                :errors 0}]
                                                     :total-documents 0
                                                     :total-segments 0
                                                     :total-errors 0
                                                     :last-ingestion-at nil}})
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {}
                            :ingest :whole-document
                            :source-path "/test/**/*.txt"}]
                result (sut/ingest-files system-atom file-maps)]
            (is (= 1 (:ingested result)) (str "Ingestion failed with failures: " (pr-str (:failures result))))
            (let [stats (get-in @system-atom [:ingestion-stats])
                  source-stats (first (:sources stats))]
              (is (= 1 (:files-matched source-stats)))
              (is (= 1 (:files-processed source-stats)))
              ;; Should have 1 segment
              (is (= 1 (:segments-created source-stats)))
              (is (= (:segments-created source-stats) (:total-segments stats)))))
          (finally
            (.delete file1)
            (.delete test-dir)))))))

(deftest path-captures-tracking-test
  ;; Test tracking of captured metadata values per path spec
  (testing "path captures tracking"

    (testing "tracks single capture value per path spec"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/capture-track")
            file1 (io/file test-dir "v1.md")]
        (.mkdirs test-dir)
        (spit file1 "content")
        (try
          (let [path-captures (atom {:path-specs []})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :path-captures path-captures}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {:version "v1"}
                            :captures {:version "v1"}
                            :ingest :whole-document
                            :source-path "/test/(?<version>v[0-9]+).md"}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 1 (count (:path-specs @path-captures))))
            (let [spec (first (:path-specs @path-captures))]
              (is (= "/test/(?<version>v[0-9]+).md" (:path spec)))
              (is (= #{"v1"} (:version (:captures spec))))))
          (finally
            (.delete file1)
            (.delete test-dir)))))

    (testing "accumulates multiple capture values for same path spec"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-capture")
            file1 (io/file test-dir "v1.md")
            file2 (io/file test-dir "v2.md")]
        (.mkdirs test-dir)
        (spit file1 "content1")
        (spit file2 "content2")
        (try
          (let [path-captures (atom {:path-specs []})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :path-captures path-captures}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {:version "v1"}
                            :captures {:version "v1"}
                            :ingest :whole-document
                            :source-path "/test/(?<version>v[0-9]+).md"}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {:version "v2"}
                            :captures {:version "v2"}
                            :ingest :whole-document
                            :source-path "/test/(?<version>v[0-9]+).md"}]
                result (sut/ingest-files system file-maps)]
            (is (= 2 (:ingested result)))
            (is (= 1 (count (:path-specs @path-captures))))
            (let [spec (first (:path-specs @path-captures))]
              (is (= "/test/(?<version>v[0-9]+).md" (:path spec)))
              (is (= #{"v1" "v2"} (:version (:captures spec))))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir)))))

    (testing "tracks multiple capture fields per path spec"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-field")
            subdir (io/file test-dir "clj")
            file1 (io/file subdir "v1-guide.md")]
        (.mkdirs subdir)
        (spit file1 "content")
        (try
          (let [path-captures (atom {:path-specs []})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :path-captures path-captures}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {:lang "clj" :ver "v1" :topic "guide"}
                            :captures {:lang "clj" :ver "v1" :topic "guide"}
                            :ingest :whole-document
                            :source-path "/test/(?<lang>[^/]+)/(?<ver>v[0-9]+)-(?<topic>[^.]+).md"}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 1 (count (:path-specs @path-captures))))
            (let [spec (first (:path-specs @path-captures))]
              (is (= "/test/(?<lang>[^/]+)/(?<ver>v[0-9]+)-(?<topic>[^.]+).md" (:path spec)))
              (is (= #{"clj"} (:lang (:captures spec))))
              (is (= #{"v1"} (:ver (:captures spec))))
              (is (= #{"guide"} (:topic (:captures spec))))))
          (finally
            (.delete file1)
            (.delete subdir)
            (.delete test-dir)))))

    (testing "handles different path specs independently"
      (let [test-dir1 (io/file "test/mcp_vector_search/test-resources/ingest_test/spec1")
            test-dir2 (io/file "test/mcp_vector_search/test-resources/ingest_test/spec2")
            file1 (io/file test-dir1 "v1.md")
            file2 (io/file test-dir2 "api.md")]
        (.mkdirs test-dir1)
        (.mkdirs test-dir2)
        (spit file1 "content1")
        (spit file2 "content2")
        (try
          (let [path-captures (atom {:path-specs []})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :path-captures path-captures}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {:version "v1"}
                            :captures {:version "v1"}
                            :ingest :whole-document
                            :source-path "/test1/(?<version>v[0-9]+).md"}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {:category "api"}
                            :captures {:category "api"}
                            :ingest :whole-document
                            :source-path "/test2/(?<category>[^.]+).md"}]
                result (sut/ingest-files system file-maps)]
            (is (= 2 (:ingested result)))
            (is (= 2 (count (:path-specs @path-captures))))
            (let [spec1 (first (filter #(= "/test1/(?<version>v[0-9]+).md" (:path %)) (:path-specs @path-captures)))
                  spec2 (first (filter #(= "/test2/(?<category>[^.]+).md" (:path %)) (:path-specs @path-captures)))]
              (is (some? spec1))
              (is (some? spec2))
              (is (= #{"v1"} (:version (:captures spec1))))
              (is (= #{"api"} (:category (:captures spec2))))))
          (finally
            (.delete file1)
            (.delete file2)
            (.delete test-dir1)
            (.delete test-dir2)))))

    (testing "skips tracking when no captures present"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/no-capture")
            file1 (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit file1 "content")
        (try
          (let [path-captures (atom {:path-specs []})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :path-captures path-captures}
                file-maps [{:file file1
                            :path (.getPath file1)
                            :metadata {}
                            :captures {}
                            :ingest :whole-document
                            :source-path "/test/*.txt"}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            ;; No path specs should be added since there are no captures
            (is (= 0 (count (:path-specs @path-captures)))))
          (finally
            (.delete file1)
            (.delete test-dir)))))))

(deftest multi-segment-ingestion-test
  ;; Test that ingest-file handles multiple segments correctly
  (testing "multi-segment ingestion"

    (testing "validates all segments from process-document"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-seg")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "segment1\nsegment2\nsegment3")
        (try
          ;; Define a test strategy that returns multiple segments
          (defmethod common/process-document :test-multi-segment
            [_strategy path content metadata]
            (let [lines (clojure.string/split content #"\n")
                  file-id path]
              (map-indexed
                (fn [idx line]
                  (let [segment-id (common/generate-segment-id file-id idx)
                        enhanced-metadata (assoc metadata :line-num idx)]
                    {:file-id file-id
                     :segment-id segment-id
                     :text-to-embed line
                     :content-to-store line
                     :metadata enhanced-metadata}))
                lines)))

          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {:source "test"}
                            :ingest :test-multi-segment}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= 0 (:failed result)))
            ;; Verify metadata from all segments was tracked
            (is (= #{0 1 2} (:line-num @metadata-values)))
            (is (= #{"test"} (:source @metadata-values))))

          ;; Remove the test method
          (remove-method common/process-document :test-multi-segment)
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "tracks metadata from all segments independently"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/multi-meta")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "line1\nline2")
        (try
          ;; Define a test strategy with different metadata per segment
          (defmethod common/process-document :test-varying-metadata
            [_strategy path content metadata]
            (let [lines (clojure.string/split content #"\n")
                  file-id path]
              (map-indexed
                (fn [idx line]
                  (let [segment-id (common/generate-segment-id file-id idx)
                        ;; Add segment-specific metadata
                        enhanced-metadata (assoc metadata
                                                :segment-type (if (even? idx) "even" "odd"))]
                    {:file-id file-id
                     :segment-id segment-id
                     :text-to-embed line
                     :content-to-store line
                     :metadata enhanced-metadata}))
                lines)))

          (let [metadata-values (atom {})
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)
                        :metadata-values metadata-values}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :test-varying-metadata}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            ;; Both segment types should be tracked
            (is (= #{"even" "odd"} (:segment-type @metadata-values))))

          (remove-method common/process-document :test-varying-metadata)
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "rejects malformed segments"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/malformed-seg")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "content")
        (try
          ;; Define a test strategy that returns malformed segments
          (defmethod common/process-document :test-malformed
            [_strategy _path _content _metadata]
            [{:file-id "path"
              ;; Missing :segment-id, :text-to-embed, :content-to-store, :metadata
              }])

          (let [system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}
                file-maps [{:file test-file
                            :path (.getPath test-file)
                            :metadata {}
                            :ingest :test-malformed}]
                result (sut/ingest-files system file-maps)]
            ;; Should fail with error
            (is (= 0 (:ingested result)))
            (is (= 1 (:failed result)))
            (is (re-find #"Malformed segment descriptor" (:error (first (:failures result))))))

          (remove-method common/process-document :test-malformed)
          (finally
            (.delete test-file)
            (.delete test-dir)))))))

(deftest update-stats-map-test
  ;; Test pure function that updates ingestion statistics
  (testing "update-stats-map"

    (testing "updates matching source statistics"
      (let [current-stats {:sources [{:path "/test/**/*.txt"
                                      :files-matched 0
                                      :files-processed 0
                                      :segments-created 0
                                      :errors 0}]
                           :total-documents 0
                           :total-segments 0
                           :total-errors 0
                           :last-ingestion-at nil}
            stats-update {:files-matched 2
                         :files-processed 1
                         :segments-created 3
                         :errors 1}
            timestamp (java.time.Instant/parse "2025-01-15T10:30:00Z")
            result (#'sut/update-stats-map current-stats "/test/**/*.txt" stats-update timestamp)]
        (is (= 2 (get-in result [:sources 0 :files-matched])))
        (is (= 1 (get-in result [:sources 0 :files-processed])))
        (is (= 3 (get-in result [:sources 0 :segments-created])))
        (is (= 1 (get-in result [:sources 0 :errors])))
        (is (= timestamp (:last-ingestion-at result)))))

    (testing "accumulates stats for repeated updates to same source"
      (let [initial-stats {:sources [{:path "/src/**/*.clj"
                                      :files-matched 5
                                      :files-processed 5
                                      :segments-created 10
                                      :errors 0}]
                           :total-documents 5
                           :total-segments 10
                           :total-errors 0
                           :last-ingestion-at nil}
            stats-update {:files-matched 3
                         :files-processed 2
                         :segments-created 4
                         :errors 1}
            timestamp (java.time.Instant/now)
            result (#'sut/update-stats-map initial-stats "/src/**/*.clj" stats-update timestamp)]
        (is (= 8 (get-in result [:sources 0 :files-matched])))
        (is (= 7 (get-in result [:sources 0 :files-processed])))
        (is (= 14 (get-in result [:sources 0 :segments-created])))
        (is (= 1 (get-in result [:sources 0 :errors])))))

    (testing "updates total statistics"
      (let [current-stats {:sources [{:path "/test/*"}]
                           :total-documents 10
                           :total-segments 15
                           :total-errors 2
                           :last-ingestion-at nil}
            stats-update {:files-processed 5
                         :segments-created 8
                         :errors 1}
            timestamp (java.time.Instant/now)
            result (#'sut/update-stats-map current-stats "/test/*" stats-update timestamp)]
        (is (= 15 (:total-documents result)))
        (is (= 23 (:total-segments result)))
        (is (= 3 (:total-errors result)))))

    (testing "only updates matching source, leaves others unchanged"
      (let [current-stats {:sources [{:path "/src/**/*.clj"
                                      :files-matched 5
                                      :files-processed 5
                                      :segments-created 10
                                      :errors 0}
                                     {:path "/test/**/*.clj"
                                      :files-matched 3
                                      :files-processed 3
                                      :segments-created 6
                                      :errors 0}]
                           :total-documents 8
                           :total-segments 16
                           :total-errors 0
                           :last-ingestion-at nil}
            stats-update {:files-matched 2
                         :files-processed 2
                         :segments-created 4
                         :errors 0}
            timestamp (java.time.Instant/now)
            result (#'sut/update-stats-map current-stats "/src/**/*.clj" stats-update timestamp)]
        (is (= 7 (get-in result [:sources 0 :files-matched])))
        (is (= 7 (get-in result [:sources 0 :files-processed])))
        (is (= 14 (get-in result [:sources 0 :segments-created])))
        (is (= 3 (get-in result [:sources 1 :files-matched])))
        (is (= 3 (get-in result [:sources 1 :files-processed])))
        (is (= 6 (get-in result [:sources 1 :segments-created])))))

    (testing "handles missing keys in stats-update with zero"
      (let [current-stats {:sources [{:path "/docs/**/*.md"
                                      :files-matched 0
                                      :files-processed 0
                                      :segments-created 0
                                      :errors 0}]
                           :total-documents 0
                           :total-segments 0
                           :total-errors 0
                           :last-ingestion-at nil}
            stats-update {:files-processed 1}
            timestamp (java.time.Instant/now)
            result (#'sut/update-stats-map current-stats "/docs/**/*.md" stats-update timestamp)]
        (is (= 1 (:total-documents result)))
        (is (= 0 (:total-segments result)))
        (is (= 0 (:total-errors result)))))

    (testing "updates timestamp"
      (let [old-timestamp (java.time.Instant/parse "2025-01-01T00:00:00Z")
            new-timestamp (java.time.Instant/parse "2025-01-15T12:00:00Z")
            current-stats {:sources [{:path "/test/*"}]
                           :total-documents 0
                           :total-segments 0
                           :total-errors 0
                           :last-ingestion-at old-timestamp}
            stats-update {:files-processed 1}
            result (#'sut/update-stats-map current-stats "/test/*" stats-update new-timestamp)]
        (is (= new-timestamp (:last-ingestion-at result)))
        (is (not= old-timestamp (:last-ingestion-at result)))))

    (testing "is pure - does not modify input"
      (let [current-stats {:sources [{:path "/test/*"
                                      :files-matched 0}]
                           :total-documents 0}
            original-sources (:sources current-stats)
            stats-update {:files-matched 5}
            timestamp (java.time.Instant/now)
            _result (#'sut/update-stats-map current-stats "/test/*" stats-update timestamp)]
        (is (= 0 (get-in current-stats [:sources 0 :files-matched])))
        (is (= original-sources (:sources current-stats)))))))
