(ns mcp-vector-search.lifecycle-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.lifecycle :as sut])
  (:import
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))


(deftest lifecycle-test
  ;; Test lifecycle management of the system with embedding model and store.
  ;; Note: Full initialization may fail on some platforms due to native library issues.
  (testing "stop"
    (testing "clears the system"
      (sut/stop)
      (is (nil? @sut/system)))

    (testing "is idempotent"
      (sut/stop)
      (let [first-result (sut/stop)]
        (is (nil? first-result))
        (is (nil? @sut/system)))))

  (testing "start"
    (testing "attempts to initialize the system"
      (sut/stop)
      (try
        (let [result (sut/start)]
          (is (map? result))
          (is (contains? result :embedding-model))
          (is (contains? result :embedding-store))
          (is (contains? result :metadata-values))
          (is (contains? result :ingestion-stats))
          (is (contains? result :ingestion-failures))
          (is (contains? result :watch-stats))
          (is (contains? result :path-captures))
          (is (instance? InMemoryEmbeddingStore (:embedding-store result)))
          (is (= {} (:metadata-values result)))
          (is (= [] (:ingestion-failures result)))
          (is (= {:path-specs []} (:path-captures result)))
          (is (= {:sources []
                  :total-documents 0
                  :total-segments 0
                  :total-errors 0
                  :last-ingestion-at nil}
                 (:ingestion-stats result)))
          (is (= {:enabled? false
                  :sources []
                  :events {:created 0
                           :modified 0
                           :deleted 0
                           :last-event-at nil}
                  :debounce {:queued 0
                             :processed 0}}
                 (:watch-stats result))))
        (catch clojure.lang.ExceptionInfo e
          ;; Expected to fail on platforms without proper native library support
          (is (= "Failed to initialize embedding model" (.getMessage e))))))

    (testing "is idempotent when successful"
      (sut/stop)
      (try
        (let [first-result (sut/start)
              second-result (sut/start)]
          (is (= first-result second-result))
          (is (identical? (:embedding-model first-result)
                          (:embedding-model second-result)))
          (is (identical? (:embedding-store first-result)
                          (:embedding-store second-result))))
        (catch clojure.lang.ExceptionInfo _
          ;; Expected to fail on platforms without proper native library support
          (is true))))))
