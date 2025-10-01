(ns mcp-vector-search.ingest-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-vector-search.ingest :as sut]))

(deftest ingest-test
  ;; Test document ingestion into the vector search system
  (testing "ingest"
    (testing "accepts system and config maps"
      (let [system {:embedding-model :mock-model
                    :embedding-store :mock-store}
            config {:sources []}
            result (sut/ingest system config)]
        (is (nil? result))))))
