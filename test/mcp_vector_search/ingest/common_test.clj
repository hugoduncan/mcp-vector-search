(ns mcp-vector-search.ingest.common-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-vector-search.ingest.common :as sut])
  (:import [dev.langchain4j.data.document Metadata]))

(deftest generate-segment-id-test
  ;; Test segment ID generation for single and multi-segment documents
  ;; Verifies the function produces correct IDs based on arity

  (testing "generate-segment-id"
    (testing "returns file-id unchanged for single-segment documents"
      (is (= "path/file.txt" (sut/generate-segment-id "path/file.txt"))))

    (testing "appends #index for multi-segment documents"
      (is (= "path/file.txt#0" (sut/generate-segment-id "path/file.txt" 0)))
      (is (= "path/file.txt#5" (sut/generate-segment-id "path/file.txt" 5))))))

(deftest build-lc4j-metadata-test
  ;; Test metadata conversion from Clojure maps to LangChain4j Metadata objects
  ;; Verifies keyword keys are converted to strings and values are accessible

  (testing "build-lc4j-metadata"
    (testing "converts Clojure map to LangChain4j Metadata"
      (let [clj-meta {:type "doc" :version "v1"}
            lc4j-meta (sut/build-lc4j-metadata clj-meta)]
        (is (instance? Metadata lc4j-meta))
        (is (= "doc" (.getString lc4j-meta "type")))
        (is (= "v1" (.getString lc4j-meta "version")))))))

(deftest create-segment-descriptor-test
  ;; Test segment descriptor creation with enhanced metadata
  ;; Verifies all fields are correctly set and metadata is enhanced with IDs

  (testing "create-segment-descriptor"
    (testing "creates descriptor with all required fields"
      (let [file-id "path/file.txt"
            segment-id "path/file.txt#0"
            text-to-embed "text to embed"
            content-to-store "stored text"
            metadata {:source "test"}
            result (sut/create-segment-descriptor
                     file-id segment-id text-to-embed content-to-store metadata)]
        (is (= file-id (:file-id result)))
        (is (= segment-id (:segment-id result)))
        (is (= text-to-embed (:text-to-embed result)))
        (is (= content-to-store (:content-to-store result)))
        (is (= file-id (get-in result [:metadata :file-id])))
        (is (= segment-id (get-in result [:metadata :segment-id])))
        (is (= file-id (get-in result [:metadata :doc-id])))
        (is (= "test" (get-in result [:metadata :source])))))))
