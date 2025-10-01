(ns mcp-vector-search.tools-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-vector-search.tools :as sut]))

(deftest search-tool-test
  ;; Test search tool specification creation
  (testing "search-tool"
    (testing "returns a valid tool specification"
      (let [system {:embedding-model :mock-model
                    :embedding-store :mock-store}
            tool (sut/search-tool system)]
        (is (map? tool))
        (is (= "search" (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (fn? (:implementation tool)))))

    (testing "tool implementation returns expected format"
      (let [system {:embedding-model :mock-model
                    :embedding-store :mock-store}
            tool (sut/search-tool system)
            impl (:implementation tool)
            result (impl {:query "test query" :limit 5})]
        (is (map? result))
        (is (vector? (:content result)))
        (is (false? (:isError result)))))))
