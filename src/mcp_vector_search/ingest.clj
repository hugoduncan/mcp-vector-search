(ns mcp-vector-search.ingest
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-vector-search.parse :as parse])
  (:import
    (dev.langchain4j.data.document
      Metadata)
    (dev.langchain4j.data.segment
      TextSegment)
    (java.io
      File)
    (java.util.regex
      Pattern)))

(defn- build-pattern
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
  - :embedding - embedding strategy
  - :ingest - ingest strategy

  Returns a sequence of maps:
  - :file - java.io.File object
  - :path - file path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures
  - :embedding - embedding strategy
  - :ingest - ingest strategy"
  [{:keys [segments base-path base-metadata embedding ingest]}]
  (let [pattern   (build-pattern segments)
        base-file (io/file base-path)]
    (if (.isFile base-file)
      ;; Literal file path
      (let [path (.getPath base-file)]
        (when (re-matches pattern path)
          [{:file      base-file
            :path      path
            :captures  {}
            :metadata  (or base-metadata {})
            :embedding embedding
            :ingest    ingest}]))
      ;; Directory - walk and match
      (let [files (if (.isDirectory base-file)
                    (filter #(.isFile ^File %) (walk-files base-file))
                    [])]
        (into []
              (keep (fn [^File file]
                      (let [path    (.getPath file)
                            matcher (re-matcher pattern path)]
                        (when (.matches matcher)
                          (let [captures (extract-captures matcher segments)]
                            {:file      file
                             :path      path
                             :captures  captures
                             :metadata  (merge base-metadata captures)
                             :embedding embedding
                             :ingest    ingest}))))
                    files))))))

;; Ingestion strategies

(defn- record-metadata-values
  "Record metadata field values in the system's metadata-values atom.
  Updates the atom to track all distinct values seen for each metadata key."
  [metadata-values-atom metadata]
  (swap! metadata-values-atom
         (fn [current]
           (reduce (fn [acc [k v]]
                     (update acc k (fnil conj #{}) v))
                   current
                   metadata))))

(defn- embed-whole-document
  "Embedding strategy: embed entire document as single segment.
  Returns map with :embedding-response and :segment."
  [embedding-model content metadata]
  (let [string-metadata (into {} (map (fn [[k v]] [(name k) v]) metadata))
        lc4j-metadata   (Metadata/from string-metadata)
        segment         (TextSegment/from content lc4j-metadata)
        response        (.embed embedding-model segment)]
    {:embedding-response response
     :segment segment}))

(defn- ingest-whole-document
  "Ingest strategy: add single embedding to store.
  Returns nil on success."
  [embedding-store embedding-result]
  (let [embedding (.content (:embedding-response embedding-result))
        segment (:segment embedding-result)]
    (.add embedding-store embedding segment)))

(defmulti embed-content
  "Dispatch embedding based on strategy.
  Returns map with :embedding-response and :segment (or :segments)."
  (fn [strategy _embedding-model _content _metadata] strategy))

(defmethod embed-content :whole-document
  [_strategy embedding-model content metadata]
  (embed-whole-document embedding-model content metadata))

(defmethod embed-content :namespace-doc
  [_strategy embedding-model content metadata]
  (let [ns-form (parse/parse-first-ns-form content)]
    (when-not ns-form
      (throw (ex-info "No ns form found" {})))
    (let [docstring (parse/extract-docstring ns-form)]
      (when-not docstring
        (throw (ex-info "No namespace docstring found" {})))
      (let [namespace (parse/extract-namespace ns-form)]
        (when-not namespace
          (throw (ex-info "Could not extract namespace" {})))
        (let [enhanced-metadata (assoc metadata :namespace namespace)
              string-metadata   (into {} (map (fn [[k v]] [(name k) v]) enhanced-metadata))
              lc4j-metadata     (Metadata/from string-metadata)
              ;; Embed the docstring but store the full content
              docstring-segment (TextSegment/from docstring lc4j-metadata)
              response          (.embed embedding-model docstring-segment)
              ;; Create segment with full content for storage
              full-segment      (TextSegment/from content lc4j-metadata)]
          {:embedding-response response
           :segment full-segment
           :metadata enhanced-metadata})))))

(defmulti ingest-segments
  "Dispatch ingest based on strategy.
  Returns nil on success."
  (fn [strategy _embedding-store _embedding-result] strategy))

(defmethod ingest-segments :whole-document
  [_strategy embedding-store embedding-result]
  (ingest-whole-document embedding-store embedding-result))

(defmethod ingest-segments :file-path
  [_strategy embedding-store embedding-result]
  (let [embedding (.content (:embedding-response embedding-result))
        ;; Get metadata from the original segment (which has all metadata from embedding phase)
        original-segment (:segment embedding-result)
        original-metadata (.metadata original-segment)
        ;; Create new segment with just the path as content, preserving metadata
        path (:path embedding-result)
        path-segment (TextSegment/from path original-metadata)]
    (.add embedding-store embedding path-segment)))

(defn- ingest-file
  "Ingest a single file into the embedding store.

  Takes a system map with :embedding-model, :embedding-store,
  and :metadata-values, and a file map from files-from-path-spec
  with :file, :path, :metadata, :embedding, and :ingest strategy keys.

  Returns the file map on success, or the file map with an :error key on
  failure."
  [{:keys [embedding-model embedding-store metadata-values]}
   {:keys [file path metadata embedding ingest] :as file-map}]
  (try
    (let [content         (slurp file)
          embedding-result (embed-content embedding embedding-model content metadata)
          ;; Use enhanced metadata if strategy provided it, otherwise use original
          final-metadata  (or (:metadata embedding-result) metadata)
          ;; Add path to embedding-result for ingest strategies that need it
          embedding-result-with-path (assoc embedding-result :path path)]
      (ingest-segments ingest embedding-store embedding-result-with-path)
      (when metadata-values
        (record-metadata-values metadata-values final-metadata))
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

  Takes a system map with :embedding-model and :embedding-store, and a
  parsed-config map with :path-specs (a sequence of path spec maps).

  Returns ingestion statistics map with :ingested, :failed, and :failures."
  [system {:keys [path-specs] :as _parsed-config}]
  (let [all-files (mapcat files-from-path-spec path-specs)]
    (ingest-files system all-files)))
