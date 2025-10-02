(ns mcp-vector-search.integration-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [babashka.fs :as fs]
            [mcp-vector-search.config :as config]
            [mcp-vector-search.ingest :as ingest]
            [mcp-vector-search.tools :as tools])
  (:import [dev.langchain4j.model.embedding.onnx.allminilml6v2 AllMiniLmL6V2EmbeddingModel]
           [dev.langchain4j.store.embedding.inmemory InMemoryEmbeddingStore]))

(deftest end-to-end-integration-test
  ;; Test complete pipeline from config to search results
  (testing "end-to-end integration"

    (testing "ingests and searches documents with user config format"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          (let [;; Create test files
                doc1-path (fs/file temp-dir "football.md")
                doc2-path (fs/file temp-dir "cooking.md")

                _ (spit (fs/file doc1-path) "I love playing football and soccer")
                _ (spit (fs/file doc2-path) "Cooking pasta is fun")

                ;; Create user config with raw path specs
                user-config {:sources [{:name "test-docs"
                                        :path (str (fs/file temp-dir "*.md"))}]}

                ;; Process config to internal format
                processed-config (config/process-config user-config)

                ;; Create system and ingest
                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}

                ingest-result (ingest/ingest system processed-config)]

            ;; Verify ingestion succeeded
            (is (= 2 (:ingested ingest-result)))
            (is (= 0 (:failed ingest-result)))

            ;; Create search tool and search for sports-related content
            (let [search-tool (tools/search-tool system)
                  impl (:implementation search-tool)
                  search-result (impl {:query "sports" :limit 2})]

              ;; Verify search succeeded
              (is (false? (:isError search-result)))
              (is (vector? (:content search-result)))

              ;; Parse and verify results
              (let [content-text (-> search-result :content first :text)
                    results (json/read-str content-text)]

                (is (vector? results))
                (is (pos? (count results)))

                ;; Top result should be the football document
                (let [top-result (first results)]
                  (is (= "I love playing football and soccer"
                         (get top-result "content")))
                  (is (number? (get top-result "score")))
                  (is (pos? (get top-result "score")))))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "handles path specs with captures"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          (let [;; Create versioned directory structure
                v1-dir (fs/create-dirs (fs/file temp-dir "v1"))
                v2-dir (fs/create-dirs (fs/file temp-dir "v2"))

                doc1-path (fs/file v1-dir "guide.md")
                doc2-path (fs/file v2-dir "guide.md")

                _ (spit (fs/file doc1-path) "Version 1 guide content")
                _ (spit (fs/file doc2-path) "Version 2 guide content")

                ;; User config with capture in path spec
                user-config {:sources [{:name "versioned-docs"
                                        :path (str (fs/file temp-dir "(?<version>v[0-9]+)/guide.md"))}]}

                processed-config (config/process-config user-config)

                system {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                        :embedding-store (InMemoryEmbeddingStore.)}

                ingest-result (ingest/ingest system processed-config)]

            ;; Verify both versions were ingested
            (is (= 2 (:ingested ingest-result)))

            ;; Search should find versioned content
            (let [search-tool (tools/search-tool system)
                  impl (:implementation search-tool)
                  search-result (impl {:query "guide" :limit 2})]

              (is (false? (:isError search-result)))

              (let [content-text (-> search-result :content first :text)
                    results (json/read-str content-text)]

                (is (= 2 (count results)))

                ;; Both results should contain "guide content"
                (is (every? #(re-find #"guide content" (get % "content"))
                            results)))))
          (finally
            (fs/delete-tree temp-dir)))))))
