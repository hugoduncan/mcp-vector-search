(ns mcp-vector-search.ingest-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest :as sut])
  (:import
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
                           :embedding :whole-document
                           :ingest :whole-document}
                results (sut/files-from-path-spec path-spec)]
            (is (= 1 (count results)))
            (is (= (.getPath test-file) (:path (first results))))
            (is (= {} (:captures (first results))))
            (is (= :whole-document (:embedding (first results))))
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                           :embedding :whole-document
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
                            :embedding :whole-document
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
                            :embedding :whole-document
                            :ingest :whole-document}
                           {:file file2
                            :path (.getPath file2)
                            :metadata {:version "v2"}
                            :embedding :whole-document
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
                        :embedding :whole-document
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
                          :embedding :whole-document
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
                          :embedding :whole-document
                          :ingest :whole-document}
                         {:segments [{:type :literal :value (.getPath file2)}]
                          :base-path (.getPath file2)
                          :embedding :whole-document
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
                          :embedding :whole-document
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
                          :embedding :whole-document
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 3 (:ingested result)))
            (is (= #{:version :type} (set (keys @metadata-values))))
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
                           :embedding :whole-document
                           :ingest :whole-document}]}
                config2 {:path-specs
                         [{:segments [{:type :literal :value (.getPath file2)}]
                           :base-path (.getPath file2)
                           :base-metadata {:category "type2"}
                           :embedding :whole-document
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
                          :embedding :whole-document
                          :ingest :whole-document}]}
                result (sut/ingest system config)]
            (is (= 1 (:ingested result)))
            (is (= {} @metadata-values)))
          (finally
            (.delete file1)
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
                            :embedding :namespace-doc
                            :ingest :whole-document}]
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
                            :embedding :namespace-doc
                            :ingest :whole-document}]
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
                            :embedding :namespace-doc
                            :ingest :whole-document}]
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
                            :embedding :namespace-doc
                            :ingest :whole-document}]
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
                            :embedding :namespace-doc
                            :ingest :whole-document}]
                result (sut/ingest-files system file-maps)]
            (is (= 1 (:ingested result)))
            (is (= #{"com.example"} (:namespace @metadata-values)))
            (is (= #{"lib"} (:type @metadata-values))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))
