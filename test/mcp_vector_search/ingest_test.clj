(ns mcp-vector-search.ingest-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-vector-search.ingest :as sut]
            [clojure.java.io :as io]))

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
                          :base-path (.getPath test-file)}
                results (sut/files-from-path-spec path-spec)]
            (is (= 1 (count results)))
            (is (= (.getPath test-file) (:path (first results))))
            (is (= {} (:captures (first results)))))
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
                          :base-path (.getPath test-dir)}
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
                          :base-path (.getPath test-dir)}
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
                          :base-path (.getPath test-dir)}
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
                          :base-path (.getPath test-dir)}
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
                          :base-path (.getPath test-dir)}
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
                          :base-metadata {:source "docs" :type "guide"}}
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
                          :base-path (.getPath test-dir)}
                results (sut/files-from-path-spec path-spec)]
            (is (empty? results)))
          (finally
            (.delete test-dir)))))))

(deftest ingest-test
  ;; Test document ingestion into the vector search system
  (testing "ingest"
    (testing "accepts system and config maps"
      (let [system {:embedding-model :mock-model
                    :embedding-store :mock-store}
            config {:sources []}
            result (sut/ingest system config)]
        (is (nil? result))))))
