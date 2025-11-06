(ns mcp-vector-search.ingest.code-analysis-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest.code-analysis]
    [mcp-vector-search.ingest.common :as common]))


(deftest process-document-test
  ;; Test clj-kondo integration for analyzing Clojure source files
  (testing "process-document with :code-analysis strategy"
    (testing "analyzes a Clojure file with multiple vars"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "returns segment descriptors"
          (is (seq result)))

        (testing "creates segments for namespace and vars"
          (is (>= (count result) 4)))

        (testing "segment has required structure"
          (let [first-segment (first result)]
            (is (contains? first-segment :file-id))
            (is (contains? first-segment :segment-id))
            (is (contains? first-segment :text-to-embed))
            (is (contains? first-segment :content-to-store))
            (is (contains? first-segment :metadata))))

        (testing "segment metadata includes element info"
          (let [first-segment (first result)
                metadata (:metadata first-segment)]
            (is (contains? metadata :element-type))
            (is (contains? metadata :element-name))
            (is (contains? metadata :language))
            (is (contains? metadata :visibility))
            (is (contains? metadata :namespace))))))

    (testing "visibility filtering with :public-only"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {:visibility :public-only})]
        (testing "excludes private vars"
          (let [element-names (map #(get-in % [:metadata :element-name]) result)]
            (is (not (some #(= % "sample/private-fn") element-names)))))

        (testing "includes public vars"
          (let [element-names (map #(get-in % [:metadata :element-name]) result)]
            (is (some #(= % "sample/public-fn") element-names))))))

    (testing "element-type filtering"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {:element-types #{:namespace}})]
        (testing "only includes specified element types"
          (is (= 1 (count result))))

        (testing "filters to namespace only"
          (let [types (map #(get-in % [:metadata :element-type]) result)]
            (is (every? #(= "namespace" %) types))))))

    (testing "metadata fields"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {})
            first-segment (first result)
            metadata (:metadata first-segment)]
        (testing "includes language field"
          (is (= "clojure" (:language metadata))))

        (testing "includes visibility field"
          (is (contains? #{"public" "private"} (:visibility metadata))))

        (testing "includes namespace field"
          (is (= "sample" (:namespace metadata))))))

    (testing "invalid configuration handling"
      (testing "throws ex-info for invalid visibility"
        (let [test-file "test/resources/code_analysis_test/sample.clj"]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Invalid :visibility"
                (common/process-document :code-analysis test-file nil {:visibility :invalid})))))

      (testing "throws ex-info for invalid element-types"
        (let [test-file "test/resources/code_analysis_test/sample.clj"]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Invalid :element-types"
                (common/process-document :code-analysis test-file nil {:element-types "not-a-set"}))))))

    (testing "docstring vs element name fallback"
      (let [test-file "test/resources/code_analysis_test/nodoc.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "uses element name when no docstring present"
          (let [segments (filter #(= "nodoc/no-docstring-fn" (get-in % [:metadata :element-name])) result)
                segment (first segments)]
            (is (= "nodoc/no-docstring-fn" (:text-to-embed segment)))))

        (testing "creates segments for all functions"
          (is (>= (count result) 2)))))

    (testing "macro detection"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "identifies macros with :macro type"
          (let [macro-segments (filter #(= "macro" (get-in % [:metadata :element-type])) result)]
            (is (seq macro-segments))
            (is (some #(= "sample/sample-macro" (get-in % [:metadata :element-name])) macro-segments))))))

    (testing "content storage as EDN"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {})
            first-segment (first result)]
        (testing "stores clj-kondo analysis as EDN string"
          (is (string? (:content-to-store first-segment)))
          (let [parsed (read-string (:content-to-store first-segment))]
            (is (map? parsed))
            (is (contains? parsed :name))))))))


(deftest java-analysis-test
  ;; Test Java file analysis using clj-kondo
  (testing "Java file parsing"
    (testing "analyzes Java classes, methods, and fields"
      (let [test-file "test/resources/code_analysis_test/Sample.java"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "returns segments for Java elements"
          (is (seq result))
          (is (>= (count result) 6) "Should have class + fields + methods"))

        (testing "includes class definition"
          (let [class-seg (first (filter #(= "class" (get-in % [:metadata :element-type])) result))]
            (is (some? class-seg))
            (is (= "test.Sample" (get-in class-seg [:metadata :element-name])))
            (is (= "java" (get-in class-seg [:metadata :language])))
            (is (= "public" (get-in class-seg [:metadata :visibility])))))

        (testing "includes field definitions"
          (let [fields (filter #(= "field" (get-in % [:metadata :element-type])) result)]
            (is (>= (count fields) 2) "Should have at least publicField and protectedField")))

        (testing "includes method definitions"
          (let [methods (filter #(= "method" (get-in % [:metadata :element-type])) result)]
            (is (>= (count methods) 3) "Should have at least publicMethod, protectedMethod, and add")))

        (testing "extracts visibility correctly"
          (let [protected-field (first (filter #(= "test.Sample.protectedField"
                                                   (get-in % [:metadata :element-name])) result))
                protected-method (first (filter #(= "test.Sample.protectedMethod"
                                                    (get-in % [:metadata :element-name])) result))]
            (is (= "protected" (get-in protected-field [:metadata :visibility])))
            (is (= "protected" (get-in protected-method [:metadata :visibility])))))

        (testing "extracts javadoc for methods"
          (let [public-method (first (filter #(= "test.Sample.publicMethod"
                                                 (get-in % [:metadata :element-name])) result))]
            (is (some? public-method))
            (is (str/includes? (:text-to-embed public-method) "public method")
                "Should use javadoc as embedding text")))))

    (testing "Java visibility filtering with :public-only"
      (let [test-file "test/resources/code_analysis_test/Sample.java"
            result (common/process-document :code-analysis test-file nil {:visibility :public-only})]
        (testing "excludes protected members"
          (let [element-names (map #(get-in % [:metadata :element-name]) result)]
            (is (not (some #(= % "test.Sample.protectedField") element-names)))
            (is (not (some #(= % "test.Sample.protectedMethod") element-names)))))

        (testing "includes public members"
          (let [element-names (map #(get-in % [:metadata :element-name]) result)]
            (is (some #(= % "test.Sample.publicField") element-names))
            (is (some #(= % "test.Sample.publicMethod") element-names))))))))


(deftest constructor-detection-test
  ;; Test that Java constructors are properly detected and typed
  (testing "constructor detection"
    (testing "identifies constructors as distinct from methods"
      (let [test-file "test/resources/code_analysis_test/WithConstructor.java"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "includes constructor definitions"
          (let [constructors (filter #(= "constructor" (get-in % [:metadata :element-type])) result)]
            (is (= 2 (count constructors))
                "Should detect both constructors")))

        (testing "constructors have correct element names"
          (let [constructor-names (set (map #(get-in % [:metadata :element-name])
                                            (filter #(= "constructor" (get-in % [:metadata :element-type])) result)))]
            (is (contains? constructor-names "test.WithConstructor.WithConstructor")
                "Constructor element names should match pattern Class.Constructor")))

        (testing "constructors use javadoc for embedding"
          (let [constructors (filter #(= "constructor" (get-in % [:metadata :element-type])) result)
                first-constructor (first constructors)]
            (is (str/includes? (:text-to-embed first-constructor) "Constructor")
                "Should use javadoc as embedding text for constructors")))

        (testing "methods are not confused with constructors"
          (let [methods (filter #(= "method" (get-in % [:metadata :element-type])) result)]
            (is (= 2 (count methods))
                "Should have exactly 2 methods (getName and getValue)")))))

    (testing "element-types filtering with :constructor"
      (let [test-file "test/resources/code_analysis_test/WithConstructor.java"
            result (common/process-document :code-analysis test-file nil {:element-types #{:constructor}})]
        (testing "only includes constructors when filtered"
          (is (= 2 (count result)))
          (let [types (set (map #(get-in % [:metadata :element-type]) result))]
            (is (= #{"constructor"} types)
                "All elements should be constructors")))))))


(deftest error-handling-test
  ;; Test handling of files with syntax errors
  (testing "error handling for invalid files"
    (testing "handles files with syntax errors gracefully"
      (let [test-file "test/resources/code_analysis_test/error.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "returns empty result when analysis fails due to syntax errors"
          (is (empty? result)))))

    (testing "handles non-existent files gracefully"
      (let [test-file "test/resources/code_analysis_test/nonexistent.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "returns empty sequence for non-existent file"
          (is (empty? result)))))))


(deftest nil-namespace-handling-test
  ;; Test that elements without namespace/package don't include :namespace in metadata
  (testing "nil namespace handling"
    (testing "excludes :namespace from metadata when nil"
      (let [test-file "test/resources/code_analysis_test/NoPackage.java"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "returns segments for Java elements without package"
          (is (seq result)))

        (testing "metadata does not contain :namespace key"
          (let [class-seg (first (filter #(= "class" (get-in % [:metadata :element-type])) result))
                metadata (:metadata class-seg)]
            (is (some? class-seg))
            (is (not (contains? metadata :namespace))
                "Metadata should not contain :namespace key when element has no package")))

        (testing "metadata does not have string 'nil' value"
          (let [all-metadata (map :metadata result)]
            (is (every? #(not= "nil" (:namespace %)) all-metadata)
                "No metadata should have 'nil' as namespace value")))))))


(deftest edge-case-test
  ;; Test edge cases for visibility filtering and element-types filtering
  (testing "edge cases"
    (testing "all-private file with :public-only filters private vars"
      (let [test-file "test/resources/code_analysis_test/all-private.clj"
            result (common/process-document :code-analysis test-file nil {:visibility :public-only})]
        (testing "excludes all private vars"
          (let [element-names (map #(get-in % [:metadata :element-name]) result)]
            (is (not (some #(str/includes? % "private-fn") element-names))
                "Should exclude all private functions")
            (is (not (some #(str/includes? % "private-var") element-names))
                "Should exclude all private vars")))

        (testing "includes only the public namespace"
          (is (= 1 (count result))
              "Should only have the namespace segment when all vars are private")
          (let [first-seg (first result)]
            (is (= "namespace" (get-in first-seg [:metadata :element-type]))
                "The only element should be the namespace")))))

    (testing "multiple element-types filter"
      (let [test-file "test/resources/code_analysis_test/sample.clj"
            result (common/process-document :code-analysis test-file nil {:element-types #{:var :macro}})]
        (testing "includes only specified element types"
          (let [types (set (map #(get-in % [:metadata :element-type]) result))]
            (is (every? #(contains? #{"var" "macro"} %) types)
                "All returned elements should be either var or macro")))

        (testing "excludes namespace when not in element-types filter"
          (let [types (map #(get-in % [:metadata :element-type]) result)]
            (is (not (some #(= "namespace" %) types))
                "Should not include namespace elements when not in filter")))))

    (testing "empty string docstrings fall back to element names"
      (let [test-file "test/resources/code_analysis_test/empty-docstring.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "uses element name when docstring is empty string"
          (let [empty-doc-seg (first (filter #(= "empty-docstring/fn-with-empty-doc"
                                                 (get-in % [:metadata :element-name])) result))]
            (is (some? empty-doc-seg))
            (is (= "empty-docstring/fn-with-empty-doc" (:text-to-embed empty-doc-seg))
                "Should use element name when docstring is empty string")))

        (testing "uses element name when docstring is whitespace-only"
          (let [ws-doc-seg (first (filter #(= "empty-docstring/fn-with-whitespace-doc"
                                              (get-in % [:metadata :element-name])) result))]
            (is (some? ws-doc-seg))
            (is (= "empty-docstring/fn-with-whitespace-doc" (:text-to-embed ws-doc-seg))
                "Should use element name when docstring is whitespace-only")))))

    (testing "files without namespace declaration"
      (let [test-file "test/resources/code_analysis_test/no-ns.clj"
            result (common/process-document :code-analysis test-file nil {})]
        (testing "analyzes vars in files without namespace"
          (is (seq result)
              "Should still analyze files without namespace declaration"))

        (testing "var segments have appropriate metadata"
          (let [var-seg (first (filter #(= "var" (get-in % [:metadata :element-type])) result))]
            (is (some? var-seg))
            (is (contains? (:metadata var-seg) :element-name)
                "Should have element-name even without namespace")))))))
