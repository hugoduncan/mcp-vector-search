(ns mcp-vector-search.end-to-end-test
  "End-to-end integration test using MCP client to connect to server"
  (:require
    [babashka.fs :as fs]
    [clojure.data.json :as json]
    [clojure.test :refer [deftest testing is]]
    [mcp-clj.mcp-client.core :as client]))

(deftest ^:integ mcp-client-server-integration-test
  ;; Test complete MCP protocol flow: client connects to server,
  ;; lists tools, calls search tool, and verifies results.
  ;; Validates full end-to-end integration using stdio transport.
  (testing "MCP client connects to mcp-vector-search server"

    (testing "establishes connection and initializes"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          ;; Create test documents for searching
          (let [doc1-path (fs/file temp-dir "football.md")
                doc2-path (fs/file temp-dir "cooking.md")
                _ (spit doc1-path "I love playing football and soccer")
                _ (spit doc2-path "Cooking pasta is delicious")

                ;; Create config file
                config-dir (fs/create-dirs
                             (fs/file temp-dir ".mcp-vector-search"))
                config-path (fs/file config-dir "config.edn")
                _ (spit config-path
                        (pr-str {:sources
                                 [{:name "test-docs"
                                   :path (str (fs/file temp-dir "*.md"))}]}))]

            ;; Create MCP client that connects to this server via stdio
            (with-open [mcp-client
                        (client/create-client
                          {:transport    {:type    :stdio
                                          :command "clojure"
                                          :args    ["-M:run"
                                                    (str config-path)]}
                           :client-info  {:name    "end-to-end-test-client"
                                          :version "1.0.0"}
                           :capabilities {}})]

              ;; Wait for initialization
              (client/wait-for-ready mcp-client 30000)

              ;; Verify client is ready
              (is (client/client-ready? mcp-client))
              (is (not (client/client-error? mcp-client)))

              ;; List available tools
              (let [tools-result @(client/list-tools mcp-client)]
                (is (contains? tools-result :tools))
                (is (vector? (:tools tools-result)))
                (is (= 1 (count (:tools tools-result))))

                (let [search-tool (first (:tools tools-result))]
                  (is (= "search" (:name search-tool)))
                  (is (string? (:description search-tool)))
                  (is (map? (:inputSchema search-tool)))))

              ;; Call search tool with sports query
              (let [search-result @(client/call-tool
                                     mcp-client
                                     "search"
                                     {:query "sports" :limit 2})]

                (is (false? (:isError search-result)))
                (is (vector? (:content search-result)))

                ;; Parse JSON results
                (let [content-text (-> search-result :content first :text)
                      results (json/read-str content-text)]

                  (is (vector? results))
                  (is (pos? (count results)))

                  ;; Top result should be football document
                  (let [top-result (first results)]
                    (is (= "I love playing football and soccer"
                           (get top-result "content")))
                    (is (number? (get top-result "score")))
                    (is (pos? (get top-result "score"))))))

              ;; Test metadata filtering
              (let [search-result @(client/call-tool
                                     mcp-client
                                     "search"
                                     {:query "delicious"
                                      :limit 10
                                      :metadata {"name" "test-docs"}})]

                (is (false? (:isError search-result)))
                (let [content-text (-> search-result :content first :text)
                      results (json/read-str content-text)]
                  (is (re-find #"pasta" (get (first results) "content")))))))

          (finally
            (fs/delete-tree temp-dir)))))))

(deftest ^:integ mcp-client-server-with-captures-test
  ;; Test that MCP server correctly handles path specs with captures
  ;; and that metadata filtering works through the MCP protocol.
  (testing "MCP client searches with path captures and metadata filtering"

    (testing "handles versioned documents with capture metadata"
      (let [temp-dir (fs/create-temp-dir)]
        (try
          ;; Create versioned directory structure
          (let [v1-dir (fs/create-dirs (fs/file temp-dir "v1"))
                v2-dir (fs/create-dirs (fs/file temp-dir "v2"))
                doc1-path (fs/file v1-dir "guide.md")
                doc2-path (fs/file v2-dir "guide.md")
                _ (spit doc1-path "Version 1 guide content")
                _ (spit doc2-path "Version 2 guide content")

                ;; Config with capture in path spec
                config-dir (fs/create-dirs
                             (fs/file temp-dir ".mcp-vector-search"))
                config-path (fs/file config-dir "config.edn")
                _ (spit config-path
                        (pr-str
                          {:sources
                           [{:name "versioned-docs"
                             :path (str
                                     (fs/file
                                       temp-dir
                                       "(?<version>v[0-9]+)/guide.md"))}]}))]

            ;; Connect via MCP client
            (with-open [mcp-client (client/create-client
                                     {:transport
                                      {:type    :stdio
                                       :command "clojure"
                                       :args    ["-M:run" (str config-path)]}
                                      :client-info {:name "capture-test-client"
                                                    :version "1.0.0"}
                                      :capabilities {}})]

              (client/wait-for-ready mcp-client 30000)

              ;; Search without metadata filter - should find both versions
              (let [all-results @(client/call-tool
                                   mcp-client
                                   "search"
                                   {:query "guide" :limit 10})
                    all-content (-> all-results :content first :text)
                    all-docs (json/read-str all-content)]

                (is (false? (:isError all-results)))
                (is (= 2 (count all-docs))))

              ;; Search with version filter - should find only v1
              (let [v1-results @(client/call-tool
                                  mcp-client
                                  "search"
                                  {:query "guide"
                                   :limit 10
                                   :metadata {"version" "v1"}})
                    v1-content (-> v1-results :content first :text)
                    v1-docs (json/read-str v1-content)]

                (is (false? (:isError v1-results)))
                (is (= 1 (count v1-docs)))
                (is (re-find #"Version 1" (get (first v1-docs) "content"))))

              ;; Search with version filter - should find only v2
              (let [v2-results @(client/call-tool
                                  mcp-client
                                  "search"
                                  {:query "guide"
                                   :limit 10
                                   :metadata {"version" "v2"}})
                    v2-content (-> v2-results :content first :text)
                    v2-docs (json/read-str v2-content)]

                (is (false? (:isError v2-results)))
                (is (= 1 (count v2-docs)))
                (is (re-find #"Version 2" (get (first v2-docs) "content"))))))

          (finally
            (fs/delete-tree temp-dir)))))))
