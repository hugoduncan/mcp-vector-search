(ns mcp-vector-search.resources-test
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is testing]]
    [mcp-vector-search.resources :as resources])
  (:import
    (java.time
      Instant)))

;;; Test Fixtures

(defn create-test-system
  "Create a test system atom with sample data."
  []
  (atom
    {:ingestion-stats {:total-documents 42
                       :total-segments 100
                       :total-errors 2
                       :last-ingestion-at (Instant/parse "2025-01-15T10:30:00Z")
                       :sources [{:path-spec {:pattern "**/*.md"}
                                  :files-matched 20
                                  :files-processed 18
                                  :segments-created 50
                                  :errors 1}
                                 {:path-spec {:pattern "**/*.txt"}
                                  :files-matched 25
                                  :files-processed 24
                                  :segments-created 50
                                  :errors 1}]}
     :ingestion-failures [{:file-path "/docs/broken.md"
                          :error-type :read-error
                          :message "File not found"
                          :source-path "**/*.md"
                          :timestamp (Instant/parse "2025-01-15T10:29:00Z")}
                         {:file-path "/data/bad.txt"
                          :error-type :parse-error
                          :message "Invalid format"
                          :source-path "**/*.txt"
                          :timestamp (Instant/parse "2025-01-15T10:31:00Z")}]
     :path-captures {:path-specs [{:path "/docs/(?<cat>[^/]+)/*.md"
                                   :captures {:cat #{"api" "guide"}}}
                                  {:path "/src/(?<ns>[^/]+)/*.clj"
                                   :captures {:ns #{"core" "util"}}}]}
     :watch-stats {:enabled? true
                         :sources [{:path "/docs/**/*.md"
                                    :watching? true}
                                   {:path "/data/**/*.txt"
                                    :watching? false}]
                         :events {:created 5
                                  :modified 10
                                  :deleted 2
                                  :last-event-at (Instant/parse "2025-01-15T11:00:00Z")}
                         :debounce {:queued 8
                                    :processed 17}}}))

;;; Tests

(deftest test-resource-definitions
  ;; Test that resource-definitions returns all 5 resources with correct structure
  (testing "resource-definitions"
    (testing "returns all 5 resources"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)]
        (is (= 5 (count resources)))
        (is (contains? resources "ingestion-status"))
        (is (contains? resources "ingestion-stats"))
        (is (contains? resources "ingestion-failures"))
        (is (contains? resources "path-metadata"))
        (is (contains? resources "watch-stats"))))

    (testing "has correct structure for each resource"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)]
        (doseq [[_name resource] resources]
          (is (contains? resource :name))
          (is (contains? resource :uri))
          (is (contains? resource :mime-type))
          (is (contains? resource :description))
          (is (contains? resource :implementation))
          (is (fn? (:implementation resource)))
          (is (= "application/json" (:mime-type resource)))
          (is (string? (:uri resource)))
          (is (re-matches #"ingestion://.*" (:uri resource))))))))

(deftest test-ingestion-status-resource
  ;; Test ingestion://status resource returns valid JSON with correct data
  (testing "ingestion-status resource"
    (testing "returns valid JSON"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-status" :implementation])
            response (impl nil "ingestion://status")]
        (is (false? (:isError response)))
        (is (vector? (:contents response)))
        (is (= 1 (count (:contents response))))
        (let [content (first (:contents response))]
          (is (= "ingestion://status" (:uri content)))
          (is (= "application/json" (:mimeType content)))
          (is (string? (:text content))))))

    (testing "contains correct data"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-status" :implementation])
            response (impl nil "ingestion://status")
            content (first (:contents response))
            data (json/read-str (:text content) :key-fn keyword)]
        (is (= 42 (:total-documents data)))
        (is (= 100 (:total-segments data)))
        (is (= 2 (:total-errors data)))
        (is (string? (:last-ingestion-at data)))))))

(deftest test-ingestion-stats-resource
  ;; Test ingestion://stats resource returns per-source statistics
  (testing "ingestion-stats resource"
    (testing "returns valid JSON with sources"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-stats" :implementation])
            response (impl nil "ingestion://stats")]
        (is (false? (:isError response)))
        (let [content (first (:contents response))
              data (json/read-str (:text content) :key-fn keyword)]
          (is (contains? data :sources))
          (is (= 2 (count (:sources data)))))))

    (testing "contains correct per-source data"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-stats" :implementation])
            response (impl nil "ingestion://stats")
            content (first (:contents response))
            data (json/read-str (:text content) :key-fn keyword)
            first-source (first (:sources data))]
        (is (contains? first-source :path-spec))
        (is (= 20 (:files-matched first-source)))
        (is (= 18 (:files-processed first-source)))
        (is (= 50 (:segments-created first-source)))
        (is (= 1 (:errors first-source)))))))

(deftest test-ingestion-failures-resource
  ;; Test ingestion://failures resource returns error list
  (testing "ingestion-failures resource"
    (testing "returns valid JSON with failures"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-failures" :implementation])
            response (impl nil "ingestion://failures")]
        (is (false? (:isError response)))
        (let [content (first (:contents response))
              data (json/read-str (:text content) :key-fn keyword)]
          (is (contains? data :failures))
          (is (= 2 (count (:failures data)))))))

    (testing "contains correct failure data"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["ingestion-failures" :implementation])
            response (impl nil "ingestion://failures")
            content (first (:contents response))
            data (json/read-str (:text content) :key-fn keyword)
            first-failure (first (:failures data))]
        (is (= "/docs/broken.md" (:file-path first-failure)))
        (is (= "read-error" (:error-type first-failure)))
        (is (= "File not found" (:message first-failure)))
        (is (= "**/*.md" (:source-path first-failure)))
        (is (string? (:timestamp first-failure)))))))

(deftest test-path-metadata-resource
  ;; Test ingestion://metadata resource returns path captures
  (testing "path-metadata resource"
    (testing "returns valid JSON with path-specs"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["path-metadata" :implementation])
            response (impl nil "ingestion://metadata")]
        (is (false? (:isError response)))
        (let [content (first (:contents response))
              data (json/read-str (:text content) :key-fn keyword)]
          (is (contains? data :path-specs))
          (is (= 2 (count (:path-specs data)))))))

    (testing "contains correct path spec data"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["path-metadata" :implementation])
            response (impl nil "ingestion://metadata")
            content (first (:contents response))
            data (json/read-str (:text content) :key-fn keyword)
            first-spec (first (:path-specs data))]
        (is (= "/docs/(?<cat>[^/]+)/*.md" (:path first-spec)))
        (is (contains? first-spec :captures))))))

(deftest test-watch-stats-resource
  ;; Test ingestion://watch-stats resource returns watch statistics
  (testing "watch-stats resource"
    (testing "returns valid JSON with watch data"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["watch-stats" :implementation])
            response (impl nil "ingestion://watch-stats")]
        (is (false? (:isError response)))
        (let [content (first (:contents response))
              data (json/read-str (:text content) :key-fn keyword)]
          (is (contains? data :enabled))
          (is (contains? data :sources))
          (is (contains? data :events))
          (is (contains? data :debounce)))))

    (testing "contains correct watch statistics"
      (let [system (create-test-system)
            resources (resources/resource-definitions system)
            impl (get-in resources ["watch-stats" :implementation])
            response (impl nil "ingestion://watch-stats")
            content (first (:contents response))
            data (json/read-str (:text content) :key-fn keyword)]
        (is (true? (:enabled data)))
        (is (= 2 (count (:sources data))))
        (is (= 5 (get-in data [:events :created])))
        (is (= 10 (get-in data [:events :modified])))
        (is (= 2 (get-in data [:events :deleted])))
        (is (= 8 (get-in data [:debounce :queued])))
        (is (= 17 (get-in data [:debounce :processed])))))))

(deftest test-error-handling
  ;; Test error handling for malformed system state
  (testing "error handling"
    (testing "handles missing system data gracefully"
      (let [empty-system (atom {:ingestion-stats {:total-documents 0
                                                   :total-segments 0
                                                   :total-errors 0
                                                   :last-ingestion-at nil
                                                   :sources []}
                                :ingestion-failures []
                                :path-captures {:path-specs []}
                                :watch-stats {:enabled? false
                                              :sources []
                                              :events {:created 0
                                                       :modified 0
                                                       :deleted 0
                                                       :last-event-at nil}
                                              :debounce {:queued 0
                                                         :processed 0}}})
            resources (resources/resource-definitions empty-system)
            impl (get-in resources ["ingestion-status" :implementation])
            response (impl nil "ingestion://status")]
        ;; Should return valid JSON with empty/zero values
        (is (false? (:isError response)))
        (is (vector? (:contents response)))
        (let [content (first (:contents response))
              data (json/read-str (:text content) :key-fn keyword)]
          (is (= "application/json" (:mimeType content)))
          (is (= 0 (:total-documents data)))
          (is (= 0 (:total-segments data))))))))
