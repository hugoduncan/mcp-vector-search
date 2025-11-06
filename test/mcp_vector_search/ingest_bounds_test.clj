(ns mcp-vector-search.ingest-bounds-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest :as sut])
  (:import
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))


(deftest sources-bounds-checking-test
  ;; Test that per-source statistics initialization has bounds checking
  (testing "sources bounds checking during initialization"

    (testing "allows adding sources up to max limit"
      (let [system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :ingestion-stats {:sources []
                                            :total-documents 0
                                            :total-segments 0
                                            :total-errors 0}})
            path-specs (mapv (fn [i]
                               {:path (str "/nonexistent/test" i "/*")
                                :segments []
                                :base-path "/nonexistent"})
                             (range 10))]
        (with-redefs [sut/files-from-path-spec (constantly [])]
          (sut/ingest system {:path-specs path-specs})
          (is (= 10 (count (get-in @system [:ingestion-stats :sources])))))))

    (testing "limits sources to max-sources-tracked constant"
      (let [max-sources-value @#'sut/max-sources-tracked
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :ingestion-stats {:sources []
                                            :total-documents 0
                                            :total-segments 0
                                            :total-errors 0}})
            path-specs (mapv (fn [i]
                               {:path (str "/nonexistent/test" i "/*")
                                :segments []
                                :base-path "/nonexistent"})
                             (range (+ max-sources-value 10)))]
        (with-redefs [sut/files-from-path-spec (constantly [])]
          (sut/ingest system {:path-specs path-specs})
          (is (= max-sources-value (count (get-in @system [:ingestion-stats :sources])))))))

    (testing "does not add duplicate sources"
      (let [system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :ingestion-stats {:sources [{:path "/existing/*"
                                                       :files-matched 5
                                                       :files-processed 5
                                                       :segments-created 10
                                                       :errors 0}]
                                            :total-documents 0
                                            :total-segments 0
                                            :total-errors 0}})
            path-specs [{:path "/existing/*" :segments [] :base-path "/existing"}
                        {:path "/new/*" :segments [] :base-path "/new"}]]
        (with-redefs [sut/files-from-path-spec (constantly [])]
          (sut/ingest system {:path-specs path-specs})
          (let [sources (get-in @system [:ingestion-stats :sources])]
            (is (= 2 (count sources)))
            (is (= 5 (:files-matched (first sources))))
            (is (= "/existing/*" (:path (first sources))))
            (is (= "/new/*" (:path (second sources))))))))))
