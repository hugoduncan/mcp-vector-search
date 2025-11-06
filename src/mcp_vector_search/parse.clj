(ns mcp-vector-search.parse
  "Parsing utilities for Clojure source files.

  ## Responsibilities
  Provides parsing functions for extracting structural information from Clojure
  source code. Used by the `:namespace-doc` embedding strategy to extract
  namespace names and docstrings for indexing."
  (:require
    [clojure.edn :as edn]))


(defn parse-first-ns-form
  "Parse the first ns form from Clojure source content.
  Returns the ns form as a list, or nil if no ns form found or parsing fails."
  [content]
  (try
    (let [reader (java.io.PushbackReader. (java.io.StringReader. content))]
      (loop []
        (when-let [form (try (edn/read {:eof nil} reader)
                             (catch Exception _ nil))]
          (if (and (list? form) (= 'ns (first form)))
            form
            (recur)))))
    (catch Exception _
      nil)))


(defn extract-namespace
  "Extract the namespace symbol from an ns form.
  Returns the namespace as a string, or nil if extraction fails."
  [ns-form]
  (try
    (when (and (list? ns-form) (= 'ns (first ns-form)) (>= (count ns-form) 2))
      (let [ns-name (second ns-form)]
        (cond
          (symbol? ns-name) (str ns-name)
          (and (map? ns-name) (:name ns-name)) (str (:name ns-name))
          :else nil)))
    (catch Exception _
      nil)))


(defn extract-docstring
  "Extract the docstring from an ns form.
  Returns the docstring as a string, or nil if no docstring found.

  Handles both inline docstrings:
    (ns foo.bar \"docstring\" ...)
  And metadata docstrings:
    (ns ^{:doc \"docstring\"} foo.bar ...)"
  [ns-form]
  (try
    (when (and (list? ns-form) (= 'ns (first ns-form)) (>= (count ns-form) 2))
      (let [ns-name (second ns-form)
            ;; Check for metadata on the namespace symbol
            meta-doc (when (symbol? ns-name)
                       (-> ns-name meta :doc))
            ;; Check for inline docstring (third element)
            inline-doc (when (and (>= (count ns-form) 3)
                                  (string? (nth ns-form 2)))
                         (nth ns-form 2))]
        (or meta-doc inline-doc)))
    (catch Exception _
      nil)))
