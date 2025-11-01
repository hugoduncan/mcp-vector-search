(ns mcp-vector-search.ingest.single-segment-test
  (:require
    [clojure.java.io :as io]
    [clojure.string]
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.ingest.common :as common]
    [mcp-vector-search.ingest.single-segment :as sut]))

(deftest embed-content-test
  ;; Test embedding strategy multimethod
  (testing "embed-content multimethod"

    (testing ":whole-document returns full content as text"
      (let [result (sut/embed-content :whole-document "p" "abc" {})]
        (is (map? result))
        (is (= "abc" (:text result)))))

    (testing ":namespace-doc extracts docstring and namespace"
      (let [clj-content "(ns my.ns \"Doc\")"
            result (sut/embed-content :namespace-doc "p" clj-content {})]
        (is (map? result))
        (is (= "Doc" (:text result)))
        (is (= "my.ns" (get-in result [:metadata :namespace])))))

    (testing ":namespace-doc throws on missing ns form"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"No ns form found"
            (sut/embed-content :namespace-doc "p" "txt" {}))))

    (testing ":namespace-doc throws on missing docstring"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"No namespace docstring found"
            (sut/embed-content :namespace-doc "p" "(ns x)" {}))))

    (testing ":namespace-doc preserves original metadata"
      (let [clj-content "(ns foo \"D\")"
            result (sut/embed-content :namespace-doc "p" clj-content {:k "v"})]
        ;; Enhanced metadata should not affect original
        (is (= "foo" (get-in result [:metadata :namespace])))))))

(deftest extract-content-test
  ;; Test content extraction strategy multimethod
  (testing "extract-content multimethod"

    (testing ":whole-document returns full content"
      (is (= "full" (sut/extract-content :whole-document "p" "full" {}))))

    (testing ":file-path returns path only"
      (is (= "/p" (sut/extract-content :file-path "/p" "c" {}))))

    (testing "metadata parameter is available but unused by built-ins"
      (is (= "c" (sut/extract-content :whole-document "p" "c" {:x 1})))
      (is (= "p" (sut/extract-content :file-path "p" "c" {:x 1}))))))

(deftest process-document-forwarding-test
  ;; Test forwarding implementations maintain backward compatibility
  (testing "process-document forwarding"

    (testing ":whole-document forwards to :single-segment"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/fw-whole")
            test-file (io/file test-dir "d.txt")]
        (.mkdirs test-dir)
        (spit test-file "tc")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:s "t"}
                descriptors (common/process-document :whole-document path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= path (:file-id seg)))
              (is (= path (:segment-id seg)))
              (is (= content (:text-to-embed seg)))
              (is (= content (:content-to-store seg)))
              (is (= "t" (get-in seg [:metadata :s])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing ":namespace-doc forwards to :single-segment"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/fw-ns")
            test-file (io/file test-dir "c.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns ex \"Doc\")\n(defn f [] :x)")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:t "src"}
                descriptors (common/process-document :namespace-doc path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= path (:file-id seg)))
              (is (= path (:segment-id seg)))
              (is (= "Doc" (:text-to-embed seg)))
              (is (= content (:content-to-store seg)))
              (is (= "ex" (get-in seg [:metadata :namespace])))
              (is (= "src" (get-in seg [:metadata :t])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing ":file-path forwards to :single-segment"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/fw-path")
            test-file (io/file test-dir "d.txt")]
        (.mkdirs test-dir)
        (spit test-file "full content")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:c "d"}
                descriptors (common/process-document :file-path path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= path (:file-id seg)))
              (is (= path (:segment-id seg)))
              (is (= content (:text-to-embed seg)))
              (is (= path (:content-to-store seg)))
              (is (= "d" (get-in seg [:metadata :c])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))

(deftest process-document-single-segment-test
  ;; Test direct :single-segment usage with explicit strategies
  (testing "process-document :single-segment"

    (testing "composes :whole-document embedding + :whole-document content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-ww")
            test-file (io/file test-dir "d.txt")]
        (.mkdirs test-dir)
        (spit test-file "data")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :whole-document
                          :content-strategy :whole-document
                          :k "v"}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= content (:text-to-embed seg)))
              (is (= content (:content-to-store seg)))
              (is (= "v" (get-in seg [:metadata :k])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "composes :whole-document embedding + :file-path content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-wp")
            test-file (io/file test-dir "d.txt")]
        (.mkdirs test-dir)
        (spit test-file "data")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :whole-document
                          :content-strategy :file-path}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= content (:text-to-embed seg)))
              (is (= path (:content-to-store seg)))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "composes :namespace-doc embedding + :whole-document content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-nw")
            test-file (io/file test-dir "c.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns a.b \"AB\")")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :namespace-doc
                          :content-strategy :whole-document}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= "AB" (:text-to-embed seg)))
              (is (= content (:content-to-store seg)))
              (is (= "a.b" (get-in seg [:metadata :namespace])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "composes :namespace-doc embedding + :file-path content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-np")
            test-file (io/file test-dir "c.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns x.y \"XY\")")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :namespace-doc
                          :content-strategy :file-path
                          :extra "val"}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= "XY" (:text-to-embed seg)))
              (is (= path (:content-to-store seg)))
              (is (= "x.y" (get-in seg [:metadata :namespace])))
              (is (= "val" (get-in seg [:metadata :extra])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "merges enhanced metadata from embed-content"
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-meta")
            test-file (io/file test-dir "c.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns m.n \"MN\")")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :namespace-doc
                          :content-strategy :file-path
                          :base "meta"}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              ;; Enhanced :namespace from embed-content should be present
              (is (= "m.n" (get-in seg [:metadata :namespace])))
              ;; Original base metadata should be preserved
              (is (= "meta" (get-in seg [:metadata :base])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "enhanced metadata is passed to extract-content"
      ;; While built-in extract-content strategies don't use metadata,
      ;; this ensures the enhanced metadata flows through correctly
      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/ss-flow")
            test-file (io/file test-dir "c.clj")]
        (.mkdirs test-dir)
        (spit test-file "(ns flow \"F\")")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :namespace-doc
                          :content-strategy :whole-document}
                descriptors (common/process-document :single-segment path content metadata)]
            ;; The enhanced metadata with :namespace should be in the final descriptor
            (is (= "flow" (get-in (first descriptors) [:metadata :namespace]))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))

    (testing "supports custom embedding and content strategies"
      ;; Define custom strategies to validate extension mechanism
      (defmethod sut/embed-content :first-line
        [_strategy _path content _metadata]
        (let [first-line (first (clojure.string/split-lines content))]
          {:text first-line
           :metadata {:line-count (count (clojure.string/split-lines content))}}))

      (defmethod sut/extract-content :summary
        [_strategy _path content metadata]
        (format "Lines: %d, First: %s"
                (:line-count metadata)
                (first (clojure.string/split-lines content))))

      (let [test-dir (io/file "test/mcp_vector_search/test-resources/ss_test/custom")
            test-file (io/file test-dir "m.txt")]
        (.mkdirs test-dir)
        (spit test-file "A\nB\nC")
        (try
          (let [path (.getPath test-file)
                content (slurp test-file)
                metadata {:embedding :first-line
                          :content-strategy :summary
                          :type "test"}
                descriptors (common/process-document :single-segment path content metadata)]
            (is (= 1 (count descriptors)))
            (let [seg (first descriptors)]
              (is (= "A" (:text-to-embed seg)))
              (is (= "Lines: 3, First: A" (:content-to-store seg)))
              (is (= 3 (get-in seg [:metadata :line-count])))
              (is (= "test" (get-in seg [:metadata :type])))))
          (finally
            (.delete test-file)
            (.delete test-dir)))))))
