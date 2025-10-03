(ns mcp-vector-search.parse-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [mcp-vector-search.parse :as sut]))

(deftest parse-first-ns-form-test
  ;; Test parsing ns forms from Clojure source content
  (testing "parse-first-ns-form"

    (testing "parses simple ns form"
      (let [content "(ns foo.bar)"
            result (sut/parse-first-ns-form content)]
        (is (list? result))
        (is (= 'ns (first result)))
        (is (= 'foo.bar (second result)))))

    (testing "parses ns form with docstring"
      (let [content "(ns foo.bar \"A namespace\")"
            result (sut/parse-first-ns-form content)]
        (is (list? result))
        (is (= 3 (count result)))
        (is (= "A namespace" (nth result 2)))))

    (testing "parses ns form with requires and other forms"
      (let [content "(ns foo.bar\n  (:require [clojure.string :as str]))\n(def x 1)"
            result (sut/parse-first-ns-form content)]
        (is (list? result))
        (is (= 'ns (first result)))
        (is (= 'foo.bar (second result)))))

    (testing "finds first ns form when preceded by comments"
      (let [content ";; Comment\n(ns foo.bar)"
            result (sut/parse-first-ns-form content)]
        (is (list? result))
        (is (= 'foo.bar (second result)))))

    (testing "finds first ns form when preceded by other forms"
      (let [content "(def x 1)\n(ns foo.bar)"
            result (sut/parse-first-ns-form content)]
        (is (list? result))
        (is (= 'foo.bar (second result)))))

    (testing "returns nil when no ns form present"
      (let [content "(def x 1)\n(defn foo [] :bar)"
            result (sut/parse-first-ns-form content)]
        (is (nil? result))))

    (testing "returns nil on malformed content"
      (let [content "this is not clojure {{{["
            result (sut/parse-first-ns-form content)]
        (is (nil? result))))))

(deftest extract-namespace-test
  ;; Test extracting namespace symbol from ns forms
  (testing "extract-namespace"

    (testing "extracts simple namespace"
      (let [ns-form '(ns foo.bar)
            result (sut/extract-namespace ns-form)]
        (is (= "foo.bar" result))))

    (testing "extracts namespace with docstring"
      (let [ns-form '(ns foo.bar "doc")
            result (sut/extract-namespace ns-form)]
        (is (= "foo.bar" result))))

    (testing "extracts namespace with requires"
      (let [ns-form '(ns foo.bar (:require [clojure.string]))
            result (sut/extract-namespace ns-form)]
        (is (= "foo.bar" result))))

    (testing "returns nil for non-ns form"
      (let [form '(def x 1)
            result (sut/extract-namespace form)]
        (is (nil? result))))

    (testing "returns nil for malformed ns form"
      (let [ns-form '(ns)
            result (sut/extract-namespace ns-form)]
        (is (nil? result))))

    (testing "returns nil for non-list"
      (let [form :not-a-list
            result (sut/extract-namespace form)]
        (is (nil? result))))))

(deftest extract-docstring-test
  ;; Test extracting docstrings from ns forms, handling both inline and metadata forms
  (testing "extract-docstring"

    (testing "extracts inline docstring"
      (let [ns-form '(ns foo.bar "This is a docstring")
            result (sut/extract-docstring ns-form)]
        (is (= "This is a docstring" result))))

    (testing "extracts inline docstring when followed by requires"
      (let [ns-form '(ns foo.bar "Docstring" (:require [clojure.string]))
            result (sut/extract-docstring ns-form)]
        (is (= "Docstring" result))))

    (testing "returns nil when no docstring present"
      (let [ns-form '(ns foo.bar)
            result (sut/extract-docstring ns-form)]
        (is (nil? result))))

    (testing "returns nil when third element is not a string"
      (let [ns-form '(ns foo.bar (:require [clojure.string]))
            result (sut/extract-docstring ns-form)]
        (is (nil? result))))

    (testing "returns nil for malformed ns form"
      (let [ns-form '(ns)
            result (sut/extract-docstring ns-form)]
        (is (nil? result))))

    (testing "returns nil for non-list"
      (let [form :not-a-list
            result (sut/extract-docstring form)]
        (is (nil? result))))))
