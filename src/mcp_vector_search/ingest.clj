(ns mcp-vector-search.ingest
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.regex Pattern]
           [java.io File]
           [dev.langchain4j.data.segment TextSegment]
           [dev.langchain4j.data.document Metadata]))

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

;;; Ingestion

(defn- ingest-file
  "Ingest a single file into the embedding store.

  Takes a system map with :embedding-model and :embedding-store, and a file map
  from files-from-path-spec with :file, :path, and :metadata keys.

  Returns the file map on success, or the file map with an :error key on failure."
  [{:keys [embedding-model embedding-store]} {:keys [file path metadata] :as file-map}]
  (try
    (let [content (slurp file)
          string-metadata (into {} (map (fn [[k v]] [(name k) v]) metadata))
          lc4j-metadata (Metadata/from string-metadata)
          segment (TextSegment/from content lc4j-metadata)
          response (.embed embedding-model segment)
          embedding (.content response)]
      (.add embedding-store embedding segment)
      file-map)
    (catch Exception e
      (assoc file-map :error (.getMessage e)))))

(defn ingest-files
  "Ingest a sequence of files from path-spec results.

  Takes a system map and a sequence of file maps from files-from-path-spec.
  Returns a map with :ingested count, :failed count, and :failures sequence."
  [system file-maps]
  (let [results (map #(ingest-file system %) file-maps)
        successes (filter (complement :error) results)
        failures (filter :error results)]
    {:ingested (count successes)
     :failed (count failures)
     :failures failures}))

(defn ingest
  "Ingest documents into the vector search system.

  Takes a system map with :embedding-model and :embedding-store, and a config
  map with :path-specs (a sequence of path spec maps).

  Returns ingestion statistics map with :ingested, :failed, and :failures."
  [system {:keys [path-specs]}]
  (let [all-files (mapcat files-from-path-spec path-specs)]
    (ingest-files system all-files)))
