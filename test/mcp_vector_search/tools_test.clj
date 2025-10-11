(ns mcp-vector-search.tools-test
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.tools :as sut])
  (:import
    (dev.langchain4j.data.document
      Metadata)
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

(deftest search-tool-test
  ;; Test search tool specification and implementation with actual embeddings
  (testing "search-tool"
    (testing "returns a valid tool specification"
      (let [system {:embedding-model :mock-model
                    :embedding-store :mock-store}
            config {:description "Test description"}
            tool (sut/search-tool system config)]
        (is (map? tool))
        (is (= "search" (:name tool)))
        (is (= "Test description" (:description tool)))
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

        (let [config {:description "Test search"}
              tool (sut/search-tool system config)
              impl (:implementation tool)
              result (impl nil {:query "sports" :limit 2})]
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

        (let [config {:description "Test search"}
              tool (sut/search-tool system config)
              impl (:implementation tool)
              result (impl nil {:query "doc" :limit 3})]
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

        (let [config {:description "Test search"}
              tool (sut/search-tool system config)
              impl (:implementation tool)
              result (impl nil {:query "test"})]
          (is (false? (:isError result)))
          (is (vector? (:content result))))))

    (testing "handles errors gracefully"
      (let [system {:embedding-model nil
                    :embedding-store nil}
            config {:description "Test search"}
            tool (sut/search-tool system config)
            impl (:implementation tool)
            result (impl nil {:query "test"})]
        (is (true? (:isError result)))
        (is (vector? (:content result)))
        (is (string? (-> result :content first :text)))
        (is (re-find #"(?i)error" (-> result :content first :text)))))

    (testing "filters by metadata"
      (let [embedding-model (AllMiniLmL6V2EmbeddingModel.)
            embedding-store (InMemoryEmbeddingStore.)
            system {:embedding-model embedding-model
                    :embedding-store embedding-store}

            doc1 "AI content"
            doc2 "ML content"
            doc3 "Tech content"

            meta1 (Metadata/from {"category" "ai" "author" "alice"})
            meta2 (Metadata/from {"category" "ml" "author" "bob"})
            meta3 (Metadata/from {"category" "ai" "author" "bob"})

            seg1 (TextSegment/from doc1 meta1)
            seg2 (TextSegment/from doc2 meta2)
            seg3 (TextSegment/from doc3 meta3)]

        (.add embedding-store (.content (.embed embedding-model seg1)) seg1)
        (.add embedding-store (.content (.embed embedding-model seg2)) seg2)
        (.add embedding-store (.content (.embed embedding-model seg3)) seg3)

        (let [config {:description "Test search"}
              tool (sut/search-tool system config)
              impl (:implementation tool)
              result (impl nil {:query "content" :metadata {"category" "ai"}})]
          (is (false? (:isError result)))
          (let [content-text (-> result :content first :text)
                parsed-results (json/read-str content-text)]
            (is (= 2 (count parsed-results)))
            (is (every? #(or (= doc1 (get % "content"))
                             (= doc3 (get % "content")))
                        parsed-results))))))

    (testing "filters by multiple metadata fields"
      (let [embedding-model (AllMiniLmL6V2EmbeddingModel.)
            embedding-store (InMemoryEmbeddingStore.)
            system {:embedding-model embedding-model
                    :embedding-store embedding-store}

            doc1 "AI article"
            doc2 "ML paper"
            doc3 "Tech blog"

            meta1 (Metadata/from {"category" "ai" "author" "alice"})
            meta2 (Metadata/from {"category" "ml" "author" "bob"})
            meta3 (Metadata/from {"category" "ai" "author" "bob"})

            seg1 (TextSegment/from doc1 meta1)
            seg2 (TextSegment/from doc2 meta2)
            seg3 (TextSegment/from doc3 meta3)]

        (.add embedding-store (.content (.embed embedding-model seg1)) seg1)
        (.add embedding-store (.content (.embed embedding-model seg2)) seg2)
        (.add embedding-store (.content (.embed embedding-model seg3)) seg3)

        (let [config {:description "Test search"}
              tool (sut/search-tool system config)
              impl (:implementation tool)
              result (impl nil {:query "text" :metadata {"category" "ai" "author" "bob"}})]
          (is (false? (:isError result)))
          (let [content-text (-> result :content first :text)
                parsed-results (json/read-str content-text)]
            (is (= 1 (count parsed-results)))
            (is (= doc3 (get (first parsed-results) "content")))))))))

(deftest metadata-schema-test
  ;; Test dynamic metadata schema generation from discovered metadata values
  (testing "metadata schema generation"

    (testing "generates schema with enum constraints for discovered metadata"
      (let [metadata-values (atom {:category #{"ai" "ml"}
                                   :author #{"alice" "bob"}})
            system {:embedding-model :mock-model
                    :embedding-store :mock-store
                    :metadata-values metadata-values}
            config {:description "Test"}
            tool (sut/search-tool system config)
            metadata-schema (get-in tool [:inputSchema :properties "metadata"])]
        (is (= "object" (:type metadata-schema)))
        (is (false? (:additionalProperties metadata-schema)))
        (is (map? (:properties metadata-schema)))
        (is (= #{"category" "author"} (set (keys (:properties metadata-schema)))))
        (is (= "string" (get-in metadata-schema [:properties "category" :type])))
        (is (= ["ai" "ml"] (get-in metadata-schema [:properties "category" :enum])))
        (is (= "string" (get-in metadata-schema [:properties "author" :type])))
        (is (= ["alice" "bob"] (get-in metadata-schema [:properties "author" :enum])))))

    (testing "generates empty schema when no metadata discovered"
      (let [metadata-values (atom {})
            system {:embedding-model :mock-model
                    :embedding-store :mock-store
                    :metadata-values metadata-values}
            config {:description "Test"}
            tool (sut/search-tool system config)
            metadata-schema (get-in tool [:inputSchema :properties "metadata"])]
        (is (= "object" (:type metadata-schema)))
        (is (false? (:additionalProperties metadata-schema)))
        (is (nil? (:properties metadata-schema)))))

    (testing "sorts enum values alphabetically"
      (let [metadata-values (atom {:priority #{"low" "high" "medium"}})
            system {:embedding-model :mock-model
                    :embedding-store :mock-store
                    :metadata-values metadata-values}
            config {:description "Test"}
            tool (sut/search-tool system config)
            priority-enum (get-in tool [:inputSchema :properties "metadata" :properties "priority" :enum])]
        (is (= ["high" "low" "medium"] priority-enum))))))
