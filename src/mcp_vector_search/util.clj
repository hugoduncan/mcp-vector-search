(ns mcp-vector-search.util
  "Shared utility functions."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.io
      File)
    (java.util.regex
      Matcher
      Pattern)))


(defn normalize-file-path
  "Normalize a file path by resolving symlinks to canonical path.

  For absolute paths, returns canonical path (resolving symlinks like /var -> /private/var).
  For relative paths, returns original path unchanged (preserves test compatibility).

  Handles both File objects and path strings.

  Parameters:
  - file-or-path: Either a java.io.File object or a path string

  Returns:
  - Normalized path string"
  [file-or-path]
  (let [^File file (if (instance? File file-or-path)
                     file-or-path
                     (io/file file-or-path))
        path (.getPath file)]
    (if (.isAbsolute file)
      (try
        (.getCanonicalPath file)
        (catch Exception _
          path))
      path)))


(defn build-pattern
  "Build a regex pattern from segments with named groups."
  [segments]
  (let [pattern-str
        (str/join
          (mapv
            (fn [{:keys [type] :as segment}]
              (case type
                :literal (Pattern/quote (:value segment))
                :glob    (case (:pattern segment)
                           "**" ".*?"
                           "*"  "[^/]*")
                :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
            segments))]
    (Pattern/compile pattern-str)))


(defn extract-captures
  "Extract named group captures from a regex matcher.
  Returns a map of capture name to captured value."
  [^Matcher matcher segments]
  (let [capture-names (keep #(when (= :capture (:type %))
                               (:name %))
                            segments)]
    (into {}
          (map (fn [^String name]
                 [(keyword name) (.group matcher name)])
               capture-names))))
