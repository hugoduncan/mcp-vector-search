(ns mcp-vector-search.ingest-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest :as sut]
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
  ;; Test the unified pipeline multimethod and helper functions
  (testing "process-document multimethod"

    (testing "helper: generate-segment-id"
      (is (= "path/file.txt" (#'sut/generate-segment-id "path/file.txt")))
      (is (= "path/file.txt#0" (#'sut/generate-segment-id "path/file.txt" 0)))
      (is (= "path/file.txt#5" (#'sut/generate-segment-id "path/file.txt" 5))))

    (testing "helper: build-lc4j-metadata"
      (let [clj-meta {:type "doc" :version "v1"}
            lc4j-meta (#'sut/build-lc4j-metadata clj-meta)]
        (is (instance? Metadata lc4j-meta))
        (is (= "doc" (.getString lc4j-meta "type")))
        (is (= "v1" (.getString lc4j-meta "version")))))

    (testing "helper: create-segment-descriptor"
      (let [file-id "path/file.txt"
            segment-id "path/file.txt#0"
            text-to-embed "text to embed"
            content-to-store "stored text"
            metadata {:source "test"}
            result (#'sut/create-segment-descriptor file-id segment-id text-to-embed content-to-store metadata)]
        (is (= file-id (:file-id result)))
        (is (= segment-id (:segment-id result)))
        (is (= text-to-embed (:text-to-embed result)))
        (is (= content-to-store (:content-to-store result)))
        (is (= file-id (get-in result [:metadata :file-id])))
        (is (= segment-id (get-in result [:metadata :segment-id])))
        (is (= "test" (get-in result [:metadata :source])))))

    (testing ":whole-document strategy returns single segment"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/pd-whole")
            test-file (io/file test-dir "doc.txt")]
        (.mkdirs test-dir)
        (spit test-file "test content")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:source "test"}
                segment-descriptors (sut/process-document :whole-document
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
                segment-descriptors (sut/process-document :namespace-doc
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
                                  (sut/process-document :namespace-doc
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
                segment-descriptors (sut/process-document :file-path
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
                   (sut/process-document :unknown-strategy
                                         "path"
                                         "content"
                                         {}))))))

(deftest chunked-document-test
  ;; Test :chunked pipeline strategy for splitting documents into chunks
  (testing "chunked document strategy"

    (testing "small document (< chunk-size) produces single chunk"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-small")
            test-file (io/file test-dir "small.txt")
            content "Short text"]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:source "test"}
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-medium")
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
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-meta")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Word "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:category "docs" :version "v1"}
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-unique")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Text "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {}
                segment-descriptors (sut/process-document :chunked
                                                          path
                                                          content
                                                          metadata)
                segment-ids (map :segment-id segment-descriptors)]
            ;; All segment IDs should be unique
            (is (= (count segment-ids) (count (set segment-ids))))
            ;; Segment IDs should follow the pattern "path-chunk-N"
            (is (every? #(re-matches #".*-chunk-\d+" %) segment-ids)))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "custom chunk-size and chunk-overlap from metadata"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-custom")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 200 "A "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                ;; Use smaller chunk size to force more chunks
                metadata {:chunk-size 100 :chunk-overlap 20}
                segment-descriptors (sut/process-document :chunked
                                                          path
                                                          content
                                                          metadata)]
            ;; With smaller chunk size, should produce multiple chunks
            (is (> (count segment-descriptors) 1)))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "chunk offset tracking"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/chunked-offset")
            test-file (io/file test-dir "doc.txt")
            content (apply str (repeat 100 "Word "))]
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {}
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/overlap")
            test-file (io/file test-dir "doc.txt")
            ;; Create content that will span multiple chunks with default settings (512 size, 100 overlap)
            paragraph (apply str (repeat 100 "Word "))  ; ~500 chars
            content (str paragraph "\n\n" paragraph "\n\n" paragraph)]  ; ~1500 chars
        (.mkdirs test-dir)
        (spit test-file content)
        (try
          (let [path (.getPath test-file)
                metadata {:chunk-size 512 :chunk-overlap 100}
                segment-descriptors (sut/process-document :chunked
                                                          path
                                                          content
                                                          metadata)]
            ;; Should produce multiple chunks
            (is (> (count segment-descriptors) 1)
                "Content should be split into multiple chunks")
            ;; Verify overlap between adjacent chunks
            (doseq [[chunk-n chunk-n+1] (partition 2 1 segment-descriptors)]
              (let [content-n (:text-to-embed chunk-n)
                    content-n+1 (:text-to-embed chunk-n+1)
                    overlap-size 100
                    ;; Get last overlap-size chars from chunk N
                    suffix-n (subs content-n (max 0 (- (count content-n) overlap-size)))
                    ;; Get first overlap-size chars from chunk N+1
                    prefix-n+1 (subs content-n+1 0 (min overlap-size (count content-n+1)))]
                ;; The overlap may not be exactly 100 chars due to paragraph boundaries,
                ;; but there should be some overlap between adjacent chunks.
                ;; Check if suffix appears in the prefix (accounting for boundary trimming)
                (is (.contains content-n+1 (subs suffix-n (max 0 (- (count suffix-n) 50))))
                    (str "Chunks should have some overlap. "
                         "Chunk " (get-in chunk-n [:metadata :chunk-index])
                         " suffix: " (pr-str (subs suffix-n (max 0 (- (count suffix-n) 50))))
                         ", Chunk " (get-in chunk-n+1 [:metadata :chunk-index])
                         " content: " (pr-str (subs content-n+1 0 (min 100 (count content-n+1)))))))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "propagates base metadata to all chunks"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/base-meta")
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
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/captures")
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
                file-maps (sut/files-from-path-spec path-spec)
                _ (is (= 1 (count file-maps)) "Should find one matching file")
                file-map (first file-maps)
                path (:path file-map)
                full-metadata (merge (:metadata file-map)
                                     {:chunk-size 200 :chunk-overlap 50})
                segment-descriptors (sut/process-document :chunked
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
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ingest_test/name-prop")
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
                file-maps (sut/files-from-path-spec path-spec)
                file-map (first file-maps)
                path (:path file-map)
                segment-descriptors (sut/process-document :chunked
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
          (defmethod sut/process-document :test-multi-segment
            [_strategy path content metadata]
            (let [lines (clojure.string/split content #"\n")
                  file-id path]
              (map-indexed
                (fn [idx line]
                  (let [segment-id (#'sut/generate-segment-id file-id idx)
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
          (remove-method sut/process-document :test-multi-segment)
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
          (defmethod sut/process-document :test-varying-metadata
            [_strategy path content metadata]
            (let [lines (clojure.string/split content #"\n")
                  file-id path]
              (map-indexed
                (fn [idx line]
                  (let [segment-id (#'sut/generate-segment-id file-id idx)
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

          (remove-method sut/process-document :test-varying-metadata)
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
          (defmethod sut/process-document :test-malformed
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

          (remove-method sut/process-document :test-malformed)
          (finally
            (.delete test-file)
            (.delete test-dir)))))))
