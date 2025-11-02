(ns mcp-vector-search.integration-test
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.lifecycle :as lifecycle]
    [mcp-vector-search.resources :as resources]
    [mcp-vector-search.tools :as tools])
  (:import
    (dev.langchain4j.model.embedding.onnx.allminilml6v2
      AllMiniLmL6V2EmbeddingModel)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)))

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
                system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                              :embedding-store (InMemoryEmbeddingStore.)})

                ingest-result (ingest/ingest system processed-config)]

            ;; Verify ingestion succeeded
            (is (= 2 (:ingested ingest-result)))
            (is (= 0 (:failed ingest-result)))

            ;; Create search tool and search for sports-related content
            (let [search-tool (tools/search-tool system processed-config)
                  impl (:implementation search-tool)
                  search-result (impl nil {:query "sports" :limit 2})]

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

                system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                              :embedding-store (InMemoryEmbeddingStore.)})

                ingest-result (ingest/ingest system processed-config)]

            ;; Verify both versions were ingested
            (is (= 2 (:ingested ingest-result)))

            ;; Search should find versioned content
            (let [search-tool (tools/search-tool system processed-config)
                  impl (:implementation search-tool)
                  search-result (impl nil {:query "guide" :limit 2})]

              (is (false? (:isError search-result)))

              (let [content-text (-> search-result :content first :text)
                    results (json/read-str content-text)]

                (is (= 2 (count results)))

                ;; Both results should contain "guide content"
                (is (every? #(re-find #"guide content" (get % "content"))
                            results)))))
          (finally
            (fs/delete-tree temp-dir)))))

    (testing "uses all path segment types: literal, glob, and capture"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          (let [;; Create directory structure demonstrating all segment types
                docs-dir (fs/create-dirs (fs/file temp-dir "docs"))
                v1-tutorials (fs/create-dirs (fs/file docs-dir "v1" "tutorials"))
                v1-ref (fs/create-dirs (fs/file docs-dir "v1" "reference"))
                v2-api (fs/create-dirs (fs/file docs-dir "v2" "api"))

                ;; Create files with different languages and versions
                file1 (fs/file v1-tutorials "clj-guide.md")
                file2 (fs/file v1-ref "java-guide.md")
                file3 (fs/file v2-api "clj-guide.md")

                _ (spit file1 "Clojure v1 tutorial")
                _ (spit file2 "Java v1 reference")
                _ (spit file3 "Clojure v2 API guide")

                ;; Path spec combining all segment types:
                ;; - temp-dir/docs/ (literal)
                ;; - "(?<version>v[0-9]+)" (capture)
                ;; - "/" (literal)
                ;; - "**" (recursive glob - matches any subdirectory depth)
                ;; - "/" (literal)
                ;; - "*" (single-level glob - matches any filename)
                ;; - "-guide.md" (literal)
                path-pattern (str temp-dir "/docs/(?<version>v[0-9]+)/**/*-guide.md")
                user-config {:sources [{:type "guides"
                                        :path path-pattern}]}

                processed-config (config/process-config user-config)

                system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                              :embedding-store (InMemoryEmbeddingStore.)
                              :metadata-values {}})

                ingest-result (ingest/ingest system processed-config)]

            ;; Verify all matching files were ingested
            (is (= 3 (:ingested ingest-result)))
            (is (= 0 (:failed ingest-result)))

            ;; Verify metadata was captured from path segments
            (is (= #{"v1" "v2"} (:version (:metadata-values @system))))
            (is (= #{"guides"} (:type (:metadata-values @system))))

            ;; Verify search tool has dynamic schema with captured metadata
            (let [search-tool (tools/search-tool system processed-config)
                  metadata-schema (get-in search-tool [:inputSchema :properties "metadata"])]

              ;; Schema should include all discovered metadata fields
              (is (= #{"version" "type" "doc-id" "file-id" "segment-id"} (set (keys (:properties metadata-schema)))))

              ;; Each field should have enum constraints with sorted values
              (is (= ["v1" "v2"] (get-in metadata-schema [:properties "version" :enum])))
              (is (= ["guides"] (get-in metadata-schema [:properties "type" :enum])))

              ;; Test filtering by captured metadata
              (let [impl (:implementation search-tool)
                    v1-result (impl nil {:query "guide" :metadata {"version" "v1"}})
                    v1-results (json/read-str (-> v1-result :content first :text))]

                (is (false? (:isError v1-result)))
                (is (= 2 (count v1-results)))
                (is (every? #(re-find #"v1" (get % "content")) v1-results))

                ;; Search for v2 guides only
                (let [v2-result (impl nil {:query "guide" :metadata {"version" "v2"}})
                      v2-results (json/read-str (-> v2-result :content first :text))]

                  (is (false? (:isError v2-result)))
                  (is (= 1 (count v2-results)))
                  (is (re-find #"v2|API" (get (first v2-results) "content")))))))
          (finally
            (fs/delete-tree temp-dir)))))))

(deftest resource-integration-test
  ;; Test MCP resource discoverability and readability
  (testing "MCP resources"

    (testing "resources are discoverable via resource-definitions"
      (let [system (lifecycle/start)
            resource-defs (resources/resource-definitions lifecycle/system)]

        ;; Verify all 5 resources are present
        (is (= 5 (count resource-defs)))

        ;; Verify resource names
        (is (contains? resource-defs "ingestion-status"))
        (is (contains? resource-defs "ingestion-stats"))
        (is (contains? resource-defs "ingestion-failures"))
        (is (contains? resource-defs "path-metadata"))
        (is (contains? resource-defs "watch-stats"))

        ;; Verify each resource has required fields
        (doseq [[_name resource-def] resource-defs]
          (is (contains? resource-def :name))
          (is (contains? resource-def :uri))
          (is (contains? resource-def :mime-type))
          (is (contains? resource-def :description))
          (is (contains? resource-def :implementation))
          (is (= "application/json" (:mime-type resource-def))))))

    (testing "resources are readable and return valid JSON"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          (let [;; Create test files
                doc1-path (fs/file temp-dir "test.md")
                _ (spit doc1-path "Test content")

                user-config {:sources [{:name "test-docs"
                                        :path (str (fs/file temp-dir "*.md"))}]}

                processed-config (config/process-config user-config)
                _system (lifecycle/start)
                _ (ingest/ingest lifecycle/system processed-config)

                resource-defs (resources/resource-definitions lifecycle/system)]

            ;; Test ingestion-status resource
            (let [status-impl (get-in resource-defs ["ingestion-status" :implementation])
                  status-result (status-impl nil "ingestion://status")]

              (is (false? (:isError status-result)))
              (is (vector? (:contents status-result)))
              (is (= 1 (count (:contents status-result))))

              (let [content (first (:contents status-result))
                    data (json/read-str (:text content))]

                (is (= "ingestion://status" (:uri content)))
                (is (= "application/json" (:mimeType content)))
                (is (contains? data "total-documents"))
                (is (contains? data "total-segments"))
                (is (contains? data "total-errors"))
                (is (= 1 (get data "total-documents")))))

            ;; Test ingestion-stats resource
            (let [stats-impl (get-in resource-defs ["ingestion-stats" :implementation])
                  stats-result (stats-impl nil "ingestion://stats")]

              (is (false? (:isError stats-result)))

              (let [content (first (:contents stats-result))
                    data (json/read-str (:text content))]

                (is (= "ingestion://stats" (:uri content)))
                (is (contains? data "sources"))
                (is (vector? (get data "sources")))))

            ;; Test ingestion-failures resource
            (let [failures-impl (get-in resource-defs ["ingestion-failures" :implementation])
                  failures-result (failures-impl nil "ingestion://failures")]

              (is (false? (:isError failures-result)))

              (let [content (first (:contents failures-result))
                    data (json/read-str (:text content))]

                (is (= "ingestion://failures" (:uri content)))
                (is (contains? data "failures"))
                (is (vector? (get data "failures")))))

            ;; Test path-metadata resource
            (let [metadata-impl (get-in resource-defs ["path-metadata" :implementation])
                  metadata-result (metadata-impl nil "ingestion://metadata")]

              (is (false? (:isError metadata-result)))

              (let [content (first (:contents metadata-result))
                    data (json/read-str (:text content))]

                (is (= "ingestion://metadata" (:uri content)))
                (is (contains? data "path-specs"))
                (is (vector? (get data "path-specs")))))

            ;; Test watch-stats resource
            (let [watch-impl (get-in resource-defs ["watch-stats" :implementation])
                  watch-result (watch-impl nil "ingestion://watch-stats")]

              (is (false? (:isError watch-result)))

              (let [content (first (:contents watch-result))
                    data (json/read-str (:text content))]

                (is (= "ingestion://watch-stats" (:uri content)))
                (is (contains? data "enabled"))
                (is (contains? data "sources"))
                (is (contains? data "events"))
                (is (contains? data "debounce")))))
          (finally
            (lifecycle/stop)
            (fs/delete-tree temp-dir)))))))


(deftest code-analysis-integration-test
  ;; Test complete pipeline for :code-analysis strategy
  (testing "code-analysis strategy integration"

    (testing "ingests Clojure file and enables search"
      (let [test-fixtures-dir "test/resources/code_analysis_test"
            user-config {:sources [{:name "test-code"
                                    :path (str test-fixtures-dir "/sample.clj")
                                    :ingest :code-analysis}]}
            processed-config (config/process-config user-config)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})
            ingest-result (ingest/ingest system processed-config)]

        ;; Verify ingestion succeeded
        (is (= 1 (:ingested ingest-result)))
        (is (= 0 (:failed ingest-result)))

        ;; Verify metadata values were captured
        (is (contains? (:metadata-values @system) :element-type))
        (is (= #{"clojure"} (:language (:metadata-values @system))))

        ;; Verify search works
        (let [search-tool (tools/search-tool system processed-config)
              impl (:implementation search-tool)
              search-result (impl nil {:query "function" :limit 5})]
          (is (false? (:isError search-result)))
          (is (pos? (count (json/read-str (-> search-result :content first :text))))))))

    (testing "metadata filtering works"
      (let [test-fixtures-dir "test/resources/code_analysis_test"
            user-config {:sources [{:path (str test-fixtures-dir "/sample.clj")
                                    :ingest :code-analysis}]}
            processed-config (config/process-config user-config)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})
            _ (ingest/ingest system processed-config)
            search-tool (tools/search-tool system processed-config)
            impl (:implementation search-tool)]

        ;; Search with metadata filter should succeed
        (let [result (impl nil {:query "sample" :metadata {"element-type" "namespace"}})]
          (is (false? (:isError result)))
          (is (pos? (count (json/read-str (-> result :content first :text))))))))

    (testing "ingests Java file"
      (let [test-fixtures-dir "test/resources/code_analysis_test"
            user-config {:sources [{:path (str test-fixtures-dir "/Sample.java")
                                    :ingest :code-analysis}]}
            processed-config (config/process-config user-config)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})
            ingest-result (ingest/ingest system processed-config)]

        (is (= 1 (:ingested ingest-result)))
        (is (= #{"java"} (:language (:metadata-values @system))))))

    (testing "handles files with partial errors"
      (let [test-fixtures-dir "test/resources/code_analysis_test"
            user-config {:sources [{:path (str test-fixtures-dir "/error.clj")
                                    :ingest :code-analysis}]}
            processed-config (config/process-config user-config)
            system (atom {:embedding-model (AllMiniLmL6V2EmbeddingModel.)
                          :embedding-store (InMemoryEmbeddingStore.)
                          :metadata-values {}})
            ingest-result (ingest/ingest system processed-config)]

        ;; File has syntax errors but also valid elements, so it ingests successfully
        (is (= 1 (:ingested ingest-result)))))))
