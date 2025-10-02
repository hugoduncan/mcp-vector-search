(ns mcp-vector-search.tools-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [mcp-vector-search.tools :as sut])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2 AllMiniLmL6V2EmbeddingModel]
           [dev.langchain4j.store.embedding.inmemory InMemoryEmbeddingStore]
           [dev.langchain4j.data.segment TextSegment]
           [dev.langchain4j.data.document Metadata]))

(deftest search-tool-test
  ;; Test search tool specification and implementation with actual embeddings
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

    (testing "searches documents by semantic similarity"
      (let [embedding-model (AllMiniLmL6V2EmbeddingModel.)
            embedding-store (InMemoryEmbeddingStore.)
            system {:embedding-model embedding-model
                    :embedding-store embedding-store}

            doc1 "I like football"
            doc2 "I enjoy basketball"
            doc3 "Pizza is delicious"

            seg1 (TextSegment/from doc1 (Metadata/from {}))
            seg2 (TextSegment/from doc2 (Metadata/from {}))
            seg3 (TextSegment/from doc3 (Metadata/from {}))]

        (.add embedding-store (.content (.embed embedding-model seg1)) seg1)
        (.add embedding-store (.content (.embed embedding-model seg2)) seg2)
        (.add embedding-store (.content (.embed embedding-model seg3)) seg3)

        (let [tool (sut/search-tool system)
              impl (:implementation tool)
              result (impl {:query "sports" :limit 2})]
          (is (map? result))
          (is (false? (:isError result)))
          (is (vector? (:content result)))
          (is (= 1 (count (:content result))))

          (let [content-text (-> result :content first :text)
                parsed-results (json/read-str content-text)]
            (is (vector? parsed-results))
            (is (= 2 (count parsed-results)))
            (is (every? #(contains? % "content") parsed-results))
            (is (every? #(contains? % "score") parsed-results))
            (is (every? #(number? (get % "score")) parsed-results))
            (let [top-result (first parsed-results)]
              (is (or (= doc1 (get top-result "content"))
                      (= doc2 (get top-result "content")))))))))

    (testing "respects limit parameter"
      (let [embedding-model (AllMiniLmL6V2EmbeddingModel.)
            embedding-store (InMemoryEmbeddingStore.)
            system {:embedding-model embedding-model
                    :embedding-store embedding-store}]

        (dotimes [i 5]
          (let [text (str "Document " i)
                seg (TextSegment/from text (Metadata/from {}))]
            (.add embedding-store (.content (.embed embedding-model seg)) seg)))

        (let [tool (sut/search-tool system)
              impl (:implementation tool)
              result (impl {:query "doc" :limit 3})]
          (is (false? (:isError result)))
          (let [content-text (-> result :content first :text)
                parsed-results (json/read-str content-text)]
            (is (<= (count parsed-results) 3))))))

    (testing "uses default limit when not specified"
      (let [embedding-model (AllMiniLmL6V2EmbeddingModel.)
            embedding-store (InMemoryEmbeddingStore.)
            system {:embedding-model embedding-model
                    :embedding-store embedding-store}
            seg (TextSegment/from "test" (Metadata/from {}))]

        (.add embedding-store (.content (.embed embedding-model seg)) seg)

        (let [tool (sut/search-tool system)
              impl (:implementation tool)
              result (impl {:query "test"})]
          (is (false? (:isError result)))
          (is (vector? (:content result))))))

    (testing "handles errors gracefully"
      (let [system {:embedding-model nil
                    :embedding-store nil}
            tool (sut/search-tool system)
            impl (:implementation tool)
            result (impl {:query "test"})]
        (is (true? (:isError result)))
        (is (vector? (:content result)))
        (is (string? (-> result :content first :text)))
        (is (re-find #"(?i)error" (-> result :content first :text)))))))
