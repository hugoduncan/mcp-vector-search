(ns mcp-vector-search.documentation-verification-test
  "Tests to verify documentation examples work correctly."
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.config :as config]))


(deftest readme-quick-start-config-test
  ;; Verify the Quick Start config example is valid
  (testing "README.md Quick Start config parses correctly"
    (let [config-data {:sources [{:path "test/resources/verification_test/**/*.md"}]}
          processed (config/process-config config-data)]
      (is (map? processed))
      (is (vector? (:path-specs processed)))
      (is (= 1 (count (:path-specs processed)))))))


(deftest basic-ingest-strategies-test
  ;; Verify all basic ingest strategies mentioned in docs are valid
  (testing "All documented ingest strategies are supported"
    (let [strategies [:whole-document :namespace-doc :file-path :code-analysis :chunked]]
      (doseq [strategy strategies]
        (testing (str "Strategy " strategy " is recognized")
          ;; Just verify the config accepts it without error
          (let [config-data {:sources [{:path "test/resources/verification_test/**/*.md"
                                        :ingest strategy}]}
                processed (config/process-config config-data)]
            (is (map? processed))
            (is (= strategy (get-in processed [:path-specs 0 :ingest])))))))))


(deftest metadata-example-test
  ;; Verify metadata examples from docs work correctly
  (testing "Metadata from config is included"
    (let [config-data {:sources [{:path "test/resources/verification_test/**/*.md"
                                  :project "myapp"
                                  :type "documentation"}]}
          processed (config/process-config config-data)]
      (is (= {:project "myapp" :type "documentation"}
             (get-in processed [:path-specs 0 :base-metadata]))))))


(deftest path-pattern-with-capture-test
  ;; Verify named capture patterns work
  (testing "Named captures in path patterns"
    (let [config-data {:sources [{:path "test/resources/(?<category>[^/]+)/*.md"}]}
          processed (config/process-config config-data)
          segments (get-in processed [:path-specs 0 :segments])]
      (is (some #(= :capture (:type %)) segments)
          "Should have at least one capture segment"))))


(deftest file-watching-config-test
  ;; Verify file watching config examples are valid
  (testing "Global watch configuration"
    (let [config-data {:watch? true
                       :sources [{:path "test/resources/verification_test/**/*.md"}]}
          processed (config/process-config config-data)]
      (is (true? (:watch? processed)))))

  (testing "Per-source watch override"
    (let [config-data {:watch? false
                       :sources [{:path "test/resources/verification_test/**/*.md"
                                  :watch? true}]}
          processed (config/process-config config-data)]
      (is (false? (:watch? processed)))
      (is (true? (get-in processed [:path-specs 0 :watch?]))))))


(deftest composable-strategy-config-test
  ;; Verify composable strategy syntax from docs
  (testing "Composable single-segment strategy configuration"
    (let [config-data {:sources [{:path "test/resources/verification_test/**/*.md"
                                  :ingest :single-segment
                                  :embedding :namespace-doc
                                  :content-strategy :file-path}]}
          processed (config/process-config config-data)]
      (is (= :single-segment (get-in processed [:path-specs 0 :ingest])))
      (is (= :namespace-doc (get-in processed [:path-specs 0 :embedding])))
      (is (= :file-path (get-in processed [:path-specs 0 :content-strategy]))))))
