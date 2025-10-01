(ns mcp-vector-search.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:refer-clojure :exclude [read]))

(defn read
  "Read and parse an EDN configuration file.
  Returns the parsed configuration map."
  [path]
  (-> path
      io/file
      slurp
      edn/read-string))
