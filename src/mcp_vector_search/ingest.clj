(ns mcp-vector-search.ingest
  "Document ingestion with pluggable processing strategies.

  ## Responsibilities
  Matches files from parsed path specs, processes their content using configurable
  strategies, and stores embeddings in the vector database. Tracks metadata
  values for dynamic schema generation.

  ## Implementation Notes
  Uses the `process-document` multimethod to support different processing
  strategies. Built-in strategies include `:whole-document` (embeds and stores
  entire files), `:namespace-doc` (embeds namespace docstrings but stores full
  source), and `:file-path` (stores only paths). File matching combines regex
  patterns from path specs with filesystem walking."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.logging :as logging]
    [mcp-vector-search.parse :as parse])
  (:import
    (dev.langchain4j.data.document
      Metadata)
    (dev.langchain4j.data.embedding
      Embedding)
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding
      EmbeddingModel)
    (dev.langchain4j.store.embedding
      EmbeddingStore)
    (dev.langchain4j.store.embedding.inmemory
      InMemoryEmbeddingStore)
    (java.io
      File)
    (java.util.regex
      Matcher
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
  [^Matcher matcher segments]
  (let [capture-names (keep #(when (= :capture (:type %))
                               (:name %))
                            segments)]
    (into {}
          (map (fn [^String name]
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
  - :pipeline - processing strategy

  Returns a sequence of maps:
  - :file - java.io.File object
  - :path - file path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures
  - :pipeline - processing strategy"
  [{:keys [segments base-path base-metadata pipeline]}]
  (let [base-file (io/file base-path)
        ;; Normalize paths only if they're absolute (to handle symlinks like /var -> /private/var)
        ;; Keep relative paths as-is to preserve test compatibility
        normalize-path (fn [^File f]
                         (let [path (.getPath f)]
                           (if (.isAbsolute f)
                             (try
                               (.getCanonicalPath f)
                               (catch Exception _
                                 path))
                             path)))
        ;; Normalize segments if base-path is absolute (similar to watch.clj logic)
        normalized-base (if (.isAbsolute base-file)
                          (try
                            (.getCanonicalPath (io/file base-path))
                            (catch Exception _ base-path))
                          base-path)
        ;; Rebuild segments with normalized base if it changed
        normalized-segments (if (= base-path normalized-base)
                              segments
                              (let [prefix-literals (take-while #(= :literal (:type %)) segments)
                                    raw-base (str/join (map :value prefix-literals))
                                    remaining (drop-while #(= :literal (:type %)) segments)
                                    ;; Check if there was a trailing separator after the base
                                    ;; by looking at the next segment's position in the original path
                                    first-remaining (first remaining)
                                    needs-separator? (and first-remaining
                                                         (not= :literal (:type first-remaining))
                                                         (str/ends-with? raw-base "/"))]
                                (vec (concat [{:type :literal :value (if needs-separator?
                                                                       (str normalized-base "/")
                                                                       normalized-base)}]
                                             remaining))))
        pattern (build-pattern normalized-segments)]
    (if (.isFile base-file)
      ;; Literal file path
      (let [path (normalize-path base-file)]
        (when (re-matches pattern path)
          [{:file      base-file
            :path      path
            :captures  {}
            :metadata  (or base-metadata {})
            :pipeline pipeline}]))
      ;; Directory - walk and match
      (let [files (if (.isDirectory base-file)
                    (filter #(.isFile ^File %) (walk-files base-file))
                    [])]
        (into []
              (keep (fn [^File file]
                      (let [path    (normalize-path file)
                            matcher (re-matcher pattern path)]
                        (when (.matches matcher)
                          (let [captures (extract-captures matcher normalized-segments)]
                            {:file      file
                             :path      path
                             :captures  captures
                             :metadata  (merge base-metadata captures)
                             :pipeline pipeline}))))
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

;;; Unified pipeline protocol

(defn- generate-segment-id
  "Generate a segment ID for a document segment.
  For single-segment documents, returns the file-id.
  For multi-segment documents, appends #index to the file-id."
  ([file-id]
   file-id)
  ([file-id index]
   (str file-id "#" index)))

(defn- build-lc4j-metadata
  "Convert a Clojure metadata map to a LangChain4j Metadata object.
  Converts keyword keys to strings."
  [metadata]
  (let [string-metadata (into {} (map (fn [[k v]] [(name k) v]) metadata))]
    (Metadata/from string-metadata)))

(defn- create-segment-map
  "Create a segment map with all required fields.

  Parameters:
  - file-id: Shared identifier for all segments from the same file
  - segment-id: Unique identifier for this specific segment
  - content: Text content to store in the vector database
  - embedding: LangChain4j Embedding object
  - metadata: Metadata map (will be enhanced with file-id and segment-id)"
  [file-id segment-id content embedding metadata]
  (let [enhanced-metadata (assoc metadata
                                 :file-id file-id
                                 :segment-id segment-id)]
    {:file-id file-id
     :segment-id segment-id
     :content content
     :embedding embedding
     :metadata enhanced-metadata}))

(defmulti process-document
  "Process a document and return a sequence of segment maps.

  Dispatches on strategy keyword. Implementations should:
  1. Create embeddings from content
  2. Store embeddings in the embedding-store
  3. Return sequence of segment maps

  Parameters:
  - strategy: Keyword identifying the processing strategy
  - embedding-model: LangChain4j EmbeddingModel instance
  - embedding-store: EmbeddingStore for storing embeddings
  - path: File path (becomes file-id)
  - content: File content string
  - metadata: Base metadata map (without file-id/segment-id)

  Returns: Sequence of maps with keys:
  - :file-id - Shared ID for all segments from this file
  - :segment-id - Unique ID for this segment
  - :content - Text content stored in vector DB
  - :embedding - LangChain4j Embedding object
  - :metadata - Enhanced metadata including both IDs"
  (fn [strategy _model _store _path _content _metadata] strategy))

(defmethod process-document :whole-document
  [_strategy ^EmbeddingModel embedding-model ^EmbeddingStore embedding-store path content metadata]
  (let [file-id path
        segment-id (generate-segment-id file-id)
        ;; Preserve :doc-id for backward compatibility
        enhanced-metadata (assoc metadata
                                 :doc-id path
                                 :file-id file-id
                                 :segment-id segment-id)
        lc4j-metadata (build-lc4j-metadata enhanced-metadata)
        segment (TextSegment/from content lc4j-metadata)
        response (.embed embedding-model segment)
        embedding (.content ^dev.langchain4j.model.output.Response response)]
    (.add ^InMemoryEmbeddingStore embedding-store file-id embedding segment)
    [(create-segment-map file-id segment-id content embedding enhanced-metadata)]))

(defmethod process-document :namespace-doc
  [_strategy ^EmbeddingModel embedding-model ^EmbeddingStore embedding-store path content metadata]
  (let [ns-form (parse/parse-first-ns-form content)]
    (when-not ns-form
      (throw (ex-info "No ns form found" {})))
    (let [docstring (parse/extract-docstring ns-form)]
      (when-not docstring
        (throw (ex-info "No namespace docstring found" {})))
      (let [namespace (parse/extract-namespace ns-form)]
        (when-not namespace
          (throw (ex-info "Could not extract namespace" {})))
        (let [file-id path
              segment-id (generate-segment-id file-id)
              ;; Add namespace and preserve :doc-id for backward compatibility
              enhanced-metadata (assoc metadata
                                       :namespace namespace
                                       :doc-id path
                                       :file-id file-id
                                       :segment-id segment-id)
              lc4j-metadata (build-lc4j-metadata enhanced-metadata)
              ;; Embed the docstring but store the full content
              docstring-segment (TextSegment/from docstring lc4j-metadata)
              response (.embed embedding-model docstring-segment)
              embedding (.content ^dev.langchain4j.model.output.Response response)
              ;; Create segment with full content for storage
              full-segment (TextSegment/from content lc4j-metadata)]
          (.add ^InMemoryEmbeddingStore embedding-store file-id embedding full-segment)
          [(create-segment-map file-id segment-id content embedding enhanced-metadata)])))))

(defmethod process-document :file-path
  [_strategy ^EmbeddingModel embedding-model ^EmbeddingStore embedding-store path content metadata]
  (let [file-id path
        segment-id (generate-segment-id file-id)
        ;; Preserve :doc-id for backward compatibility
        enhanced-metadata (assoc metadata
                                 :doc-id path
                                 :file-id file-id
                                 :segment-id segment-id)
        lc4j-metadata (build-lc4j-metadata enhanced-metadata)
        ;; Embed full content
        content-segment (TextSegment/from content lc4j-metadata)
        response (.embed embedding-model content-segment)
        embedding (.content ^dev.langchain4j.model.output.Response response)
        ;; Store only the path
        path-segment (TextSegment/from path lc4j-metadata)]
    (.add ^InMemoryEmbeddingStore embedding-store file-id embedding path-segment)
    [(create-segment-map file-id segment-id path embedding enhanced-metadata)]))

(defn- validate-segment
  "Validate that a segment map has all required fields.
  Returns the segment if valid, throws ex-info if invalid."
  [segment]
  (let [required-keys #{:file-id :segment-id :content :embedding :metadata}
        missing-keys (remove #(contains? segment %) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info "Malformed segment map: missing required keys"
                      {:missing-keys missing-keys
                       :segment segment}))))
  segment)

(defn ingest-file
  "Ingest a single file into the embedding store.

  Takes a system map with :embedding-model, :embedding-store,
  and :metadata-values, and a file map from files-from-path-spec
  with :file, :path, :metadata, and :pipeline strategy keys.

  Returns the file map on success, or the file map with an :error key on
  failure."
  [{:keys [embedding-model embedding-store metadata-values]}
   {:keys [file path metadata pipeline] :as file-map}]
  (try
    (let [content (slurp file)
          ;; Process document using unified pipeline
          segments (process-document pipeline
                                     embedding-model
                                     embedding-store
                                     path
                                     content
                                     metadata)]
      ;; Validate all segments
      (doseq [segment segments]
        (validate-segment segment))
      ;; Track metadata from all segments
      (when metadata-values
        (doseq [segment segments]
          (record-metadata-values metadata-values (:metadata segment))))
      file-map)
    (catch Exception e
      (assoc file-map :error (.getMessage e)))))

(defn- log-if-server
  "Log a message if server is available in the system."
  [system level data]
  (when-let [server (:server system)]
    (case level
      :info (logging/info server data :logger "ingest")
      :warn (logging/warn server data :logger "ingest")
      :error (logging/error server data :logger "ingest")
      :debug (logging/debug server data :logger "ingest"))))

(defn ingest-files
  "Ingest a sequence of files from path-spec results.

  Takes a system map and a sequence of file maps from files-from-path-spec.
  Returns a map with :ingested count, :failed count, and :failures sequence."
  [system file-maps]
  (let [results (map #(ingest-file system %) file-maps)
        successes (filter (complement :error) results)
        failures (filter :error results)]
    (when (seq failures)
      (doseq [failure failures]
        (log-if-server system :error {:event "ingest-failed"
                                      :path (:path failure)
                                      :error (:error failure)})))
    (log-if-server system :info {:event "ingest-complete"
                                 :ingested (count successes)
                                 :failed (count failures)})
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
