(ns mcp-vector-search.config-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.config :as sut]))

(deftest read-test
  ;; Test reading EDN configuration files
  (testing "read"
    (testing "parses valid EDN file"
      (let [test-file (io/file "test/mcp_vector_search/test-resources/config_test/valid.edn")]
        (.mkdirs (.getParentFile test-file))
        (spit test-file "{:foo \"bar\" :baz 42}")
        (try
          (let [result (sut/read (.getPath test-file))]
            (is (map? result))
            (is (= "bar" (:foo result)))
            (is (= 42 (:baz result))))
          (finally
            (.delete test-file)))))

    (testing "handles nested structures"
      (let [test-file (io/file "test/mcp_vector_search/test-resources/config_test/nested.edn")]
        (.mkdirs (.getParentFile test-file))
        (spit test-file "{:outer {:inner {:value 123}}}")
        (try
          (let [result (sut/read (.getPath test-file))]
            (is (= 123 (get-in result [:outer :inner :value]))))
          (finally
            (.delete test-file)))))))

(deftest parse-path-spec-test
  ;; Test parsing path specifications with literals, globs, and named captures
  (testing "parse-path-spec"

    (testing "parses literal-only paths"
      (let [result (sut/parse-path-spec "/simple/path/file.md")]
        (is (= [{:type :literal :value "/simple/path/file.md"}]
               (:segments result)))
        (is (= "/simple/path/file.md" (:base-path result)))))

    (testing "parses single glob pattern"
      (let [result (sut/parse-path-spec "/docs/*.md")]
        (is (= [{:type :literal :value "/docs/"}
                {:type :glob :pattern "*"}
                {:type :literal :value ".md"}]
               (:segments result)))
        (is (= "/docs/" (:base-path result)))))

    (testing "parses recursive glob pattern"
      (let [result (sut/parse-path-spec "/docs/**/guide.md")]
        (is (= [{:type :literal :value "/docs/"}
                {:type :glob :pattern "**"}
                {:type :literal :value "/guide.md"}]
               (:segments result)))
        (is (= "/docs/" (:base-path result)))))

    (testing "parses named capture with simple regex"
      (let [result (sut/parse-path-spec "/v(?<version>[0-9]+)/doc.md")]
        (is (= [{:type :literal :value "/v"}
                {:type :capture :name "version" :pattern "[0-9]+"}
                {:type :literal :value "/doc.md"}]
               (:segments result)))
        (is (= "/v" (:base-path result)))))

    (testing "parses named capture with complex regex"
      (let [result (sut/parse-path-spec "/(?<version>[^/]*)/file")]
        (is (= [{:type :literal :value "/"}
                {:type :capture :name "version" :pattern "[^/]*"}
                {:type :literal :value "/file"}]
               (:segments result)))
        (is (= "/" (:base-path result)))))

    (testing "parses mixed pattern with multiple captures"
      (let [result (sut/parse-path-spec "/(?<lang>[^/]*)/(?<ver>v[0-9]+)/**/*.md")]
        (is (= [{:type :literal :value "/"}
                {:type :capture :name "lang" :pattern "[^/]*"}
                {:type :literal :value "/"}
                {:type :capture :name "ver" :pattern "v[0-9]+"}
                {:type :literal :value "/"}
                {:type :glob :pattern "**"}
                {:type :literal :value "/"}
                {:type :glob :pattern "*"}
                {:type :literal :value ".md"}]
               (:segments result)))
        (is (= "/" (:base-path result)))))

    (testing "parses glob and capture combination"
      (let [result (sut/parse-path-spec "/docs/(?<version>[^/]*)/**.md")]
        (is (= [{:type :literal :value "/docs/"}
                {:type :capture :name "version" :pattern "[^/]*"}
                {:type :literal :value "/"}
                {:type :glob :pattern "**"}
                {:type :literal :value ".md"}]
               (:segments result)))
        (is (= "/docs/" (:base-path result)))))

    (testing "handles capture at end of path"
      (let [result (sut/parse-path-spec "/docs/(?<name>[^.]+)\\.md")]
        (is (= [{:type :literal :value "/docs/"}
                {:type :capture :name "name" :pattern "[^.]+"}
                {:type :literal :value "\\.md"}]
               (:segments result)))
        (is (= "/docs/" (:base-path result)))))

    (testing "correctly parses /** between literals"
      (let [result (sut/parse-path-spec "/docs/**/file.md")]
        (is (= [{:type :literal :value "/docs/"}
                {:type :glob :pattern "**"}
                {:type :literal :value "/file.md"}]
               (:segments result)))
        (is (= "/docs/" (:base-path result)))))

    (testing "correctly parses multiple special characters in order"
      (let [result (sut/parse-path-spec "/(?<version>v[0-9]+)/**/*.md")]
        (is (= [{:type :literal :value "/"}
                {:type :capture :name "version" :pattern "v[0-9]+"}
                {:type :literal :value "/"}
                {:type :glob :pattern "**"}
                {:type :literal :value "/"}
                {:type :glob :pattern "*"}
                {:type :literal :value ".md"}]
               (:segments result)))
        (is (= "/" (:base-path result)))))

    (testing "throws on malformed capture - missing closing"
      (is (thrown? Exception (sut/parse-path-spec "/(?<version[^/]*)/file"))))

    (testing "throws on malformed capture - missing name"
      (is (thrown? Exception (sut/parse-path-spec "/(?<>[^/]*)/file"))))

    (testing "throws on invalid regex in capture"
      (is (thrown? Exception (sut/parse-path-spec "/(?<ver>[[[)/file"))))))

(deftest process-config-test
  ;; Test processing user config into internal format
  (testing "process-config"

    (testing "uses default description when not provided"
      (let [config {:sources [{:path "/docs/*.md"}]}
            result (sut/process-config config)]
        (is (= sut/default-search-description (:description result)))))

    (testing "uses custom description when provided"
      (let [config {:sources [{:path "/docs/*.md"}]
                    :description "Custom search description"}
            result (sut/process-config config)]
        (is (= "Custom search description" (:description result)))))

    (testing "includes description even without sources"
      (let [config {}
            result (sut/process-config config)]
        (is (= sut/default-search-description (:description result)))))

    (testing "adds default :pipeline strategy"
      (let [config {:sources [{:path "/docs/*.md"}]}
            result (sut/process-config config)]
        (is (= 1 (count (:path-specs result))))
        (is (= :whole-document (get-in result [:path-specs 0 :pipeline])))))

    (testing "preserves explicit :pipeline strategy"
      (let [config {:sources [{:path "/docs/*.md"
                               :pipeline :custom-pipeline}]}
            result (sut/process-config config)]
        (is (= :custom-pipeline (get-in result [:path-specs 0 :pipeline])))))

    (testing ":pipeline is not included in base-metadata"
      (let [config {:sources [{:path "/docs/*.md"
                               :pipeline :whole-document
                               :category "docs"}]}
            result (sut/process-config config)]
        (is (= {:category "docs"} (get-in result [:path-specs 0 :base-metadata])))
        (is (nil? (get-in result [:path-specs 0 :base-metadata :pipeline])))))))
