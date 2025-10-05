(ns mcp-vector-search.watch-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.watch :as watch]
    [mcp-vector-search.config :as config])
  (:import
    (java.io
      File)))

(deftest watch-configuration-test
  ;; Test that watch configuration is properly handled in config processing

  (testing "process-config"

    (testing "defaults :watch? to false when not specified"
      (let [cfg {:sources [{:path "/docs/*.md"}]}
            result (config/process-config cfg)]
        (is (false? (:watch? result)))
        (is (false? (-> result :path-specs first :watch?)))))

    (testing "applies global :watch? to all sources"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md"}
                           {:path "/src/*.clj"}]}
            result (config/process-config cfg)]
        (is (true? (:watch? result)))
        (is (every? :watch? (:path-specs result)))))

    (testing "per-source :watch? overrides global default"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md" :watch? false}
                           {:path "/src/*.clj"}]}
            result (config/process-config cfg)]
        (is (true? (:watch? result)))
        (is (false? (-> result :path-specs first :watch?)))
        (is (true? (-> result :path-specs second :watch?)))))

    (testing "explicit false overrides global true"
      (let [cfg {:watch? true
                 :sources [{:path "/docs/*.md" :watch? false}]}
            result (config/process-config cfg)]
        (is (false? (-> result :path-specs first :watch?)))))

    (testing "does not include :watch? in metadata"
      (let [cfg {:sources [{:path "/docs/*.md" :watch? true}]}
            result (config/process-config cfg)]
        (is (not (contains? (-> result :path-specs first :base-metadata) :watch?)))))))

(deftest path-matching-test
  ;; Test that file paths are correctly matched against path specs

  (testing "matches-path-spec?"

    (testing "matches literal paths"
      (let [segments [{:type :literal :value "/docs/readme.md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/other.md" segments)))))

    (testing "matches single-level globs"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/guide.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/sub/readme.md" segments)))))

    (testing "matches recursive globs"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/sub/guide.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/a/b/c/deep.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/readme.txt" segments)))))

    (testing "matches patterns with captures"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :capture :name "version" :pattern "v[0-9]+"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]]
        (is (true? (#'watch/matches-path-spec? "/docs/v1/readme.md" segments)))
        (is (true? (#'watch/matches-path-spec? "/docs/v2/guide.md" segments)))
        (is (false? (#'watch/matches-path-spec? "/docs/latest/readme.md" segments)))))))

(deftest recursive-watch-detection-test
  ;; Test detection of whether watching should be recursive

  (testing "should-watch-recursively?"

    (testing "detects recursive glob after base path"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (true? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "returns false for single-level globs only"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (false? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "returns false for literal-only paths"
      (let [segments [{:type :literal :value "/docs/readme.md"}]
            base-path "/docs/readme.md"]
        (is (false? (#'watch/should-watch-recursively? segments base-path)))))

    (testing "detects ** in middle of pattern"
      (let [segments [{:type :literal :value "/docs/"}
                      {:type :capture :name "version" :pattern "v[0-9]+"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "**"}
                      {:type :literal :value "/"}
                      {:type :glob :pattern "*"}
                      {:type :literal :value ".md"}]
            base-path "/docs/"]
        (is (true? (#'watch/should-watch-recursively? segments base-path)))))))

