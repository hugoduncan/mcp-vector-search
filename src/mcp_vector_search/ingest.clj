(ns mcp-vector-search.ingest
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.regex Pattern]
           [java.io File]))

(defn- segment->regex
  "Convert a segment to its regex representation."
  [{:keys [type value pattern]}]
  (case type
    :literal (Pattern/quote value)
    :glob (case pattern
            "**" ".*?"
            "*" "[^/]*")
    :capture (str "(?<" (:name (meta {::capture pattern})) ">" pattern ")")))

(defn- build-pattern
  "Build a regex pattern from segments with named groups."
  [segments]
  (let [pattern-str (str/join
                      (map-indexed
                        (fn [idx {:keys [type] :as segment}]
                          (case type
                            :literal (Pattern/quote (:value segment))
                            :glob (case (:pattern segment)
                                    "**" ".*?"
                                    "*" "[^/]*")
                            :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
                        segments))]
    (Pattern/compile pattern-str)))

(defn- extract-captures
  "Extract named group captures from a regex matcher.
  Returns a map of capture name to captured value."
  [matcher segments]
  (let [capture-names (keep #(when (= :capture (:type %))
                               (:name %))
                            segments)]
    (into {}
          (map (fn [name]
                 [(keyword name) (.group matcher name)])
               capture-names))))

(defn- walk-files
  "Walk directory tree and return all files."
  [^File dir]
  (when (.isDirectory dir)
    (tree-seq
      (fn [^File f] (.isDirectory f))
      (fn [^File f] (seq (.listFiles f)))
      dir)))

(defn files-from-path-spec
  "Apply a path spec to the filesystem and return matching files with metadata.

  Takes a path spec map with:
  - :segments - parsed segment structure
  - :base-path - starting directory for filesystem walk
  - :base-metadata (optional) - metadata to merge with captures

  Returns a sequence of maps:
  - :file - java.io.File object
  - :path - file path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures"
  [{:keys [segments base-path base-metadata]}]
  (let [pattern (build-pattern segments)
        base-file (io/file base-path)]
    (if (.isFile base-file)
      ;; Literal file path
      (let [path (.getPath base-file)]
        (when (re-matches pattern path)
          [{:file base-file
            :path path
            :captures {}
            :metadata (or base-metadata {})}]))
      ;; Directory - walk and match
      (let [files (if (.isDirectory base-file)
                   (filter #(.isFile ^File %) (walk-files base-file))
                   [])]
        (into []
          (keep (fn [^File file]
                  (let [path (.getPath file)
                        matcher (re-matcher pattern path)]
                    (when (.matches matcher)
                      (let [captures (extract-captures matcher segments)]
                        {:file file
                         :path path
                         :captures captures
                         :metadata (merge base-metadata captures)}))))
                files))))))

(defn ingest
  "Ingest documents into the vector search system.
  Takes a system map and config map.
  Returns nil."
  [system config]
  ;; TODO: Implement document ingestion
  nil)
