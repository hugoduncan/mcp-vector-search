(ns mcp-vector-search.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [mcp-vector-search.config :as sut]
            [clojure.java.io :as io]))

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
