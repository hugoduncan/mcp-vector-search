(ns mcp-vector-search.util
  "Shared utility functions."
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

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
  (let [file (if (instance? File file-or-path)
               file-or-path
               (io/file file-or-path))
        path (.getPath file)]
    (if (.isAbsolute file)
      (try
        (.getCanonicalPath file)
        (catch Exception _
          path))
      path)))
