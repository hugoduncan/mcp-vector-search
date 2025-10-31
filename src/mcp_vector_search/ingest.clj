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
      Document
      Metadata)
    (dev.langchain4j.data.document.splitter
      DocumentSplitters)
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
  - :ingest - processing strategy

  Returns a sequence of maps:
  - :file - java.io.File object
  - :path - file path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures
  - :ingest - processing strategy"
  [{:keys [segments base-path base-metadata ingest]}]
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
            :ingest ingest}]))
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
                             :ingest ingest}))))
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

(defn- create-segment-descriptor
  "Create a segment descriptor with all required fields.

  Parameters:
  - file-id: Shared identifier for all segments from the same file
  - segment-id: Unique identifier for this specific segment
  - text-to-embed: Text content to use for embedding generation
  - content-to-store: Text content to store in the vector database
  - metadata: Metadata map (will be enhanced with file-id, segment-id, and doc-id)"
  [file-id segment-id text-to-embed content-to-store metadata]
  (let [enhanced-metadata (assoc metadata
                                 :file-id file-id
                                 :segment-id segment-id
                                 :doc-id file-id)]
    {:file-id file-id
     :segment-id segment-id
     :text-to-embed text-to-embed
     :content-to-store content-to-store
     :metadata enhanced-metadata}))

(defmulti process-document
  "Process a document and return a sequence of segment descriptors.

  Dispatches on strategy keyword. Implementations should transform content
  into segment descriptors that specify what to embed and what to store.

  Parameters:
  - strategy: Keyword identifying the processing strategy
  - path: File path (becomes file-id)
  - content: File content string
  - metadata: Base metadata map (without file-id/segment-id)

  Returns: Sequence of segment descriptor maps with keys:
  - :file-id - Shared ID for all segments from this file
  - :segment-id - Unique ID for this segment
  - :text-to-embed - Text content to use for embedding generation
  - :content-to-store - Text content to store in the vector database
  - :metadata - Enhanced metadata including both IDs"
  (fn [strategy _path _content _metadata] strategy))

(defmethod process-document :whole-document
  [_strategy path content metadata]
  (let [file-id path
        segment-id (generate-segment-id file-id)]
    [(create-segment-descriptor file-id segment-id content content metadata)]))

(defmethod process-document :namespace-doc
  [_strategy path content metadata]
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
              enhanced-metadata (assoc metadata :namespace namespace)]
          [(create-segment-descriptor file-id segment-id docstring content enhanced-metadata)])))))

(defmethod process-document :file-path
  [_strategy path content metadata]
  (let [file-id path
        segment-id (generate-segment-id file-id)]
    [(create-segment-descriptor file-id segment-id content path metadata)]))

(def ^:private splitter-cache
  "Memoized cache for DocumentSplitter instances keyed by [chunk-size chunk-overlap]."
  (memoize
    (fn [chunk-size chunk-overlap]
      (DocumentSplitters/recursive (int chunk-size) (int chunk-overlap)))))

(defn- validate-chunk-config
  "Validates chunk configuration values, throwing detailed errors for invalid settings."
  [chunk-size chunk-overlap path]
  (when-not (and (integer? chunk-size) (pos? chunk-size))
    (throw (ex-info (str "Invalid :chunk-size for " path ": must be a positive integer")
                    {:chunk-size chunk-size :path path})))
  (when-not (and (integer? chunk-overlap) (>= chunk-overlap 0))
    (throw (ex-info (str "Invalid :chunk-overlap for " path ": must be a non-negative integer")
                    {:chunk-overlap chunk-overlap :path path})))
  (when-not (< chunk-overlap chunk-size)
    (throw (ex-info (str "Invalid chunk configuration for " path ": :chunk-overlap must be less than :chunk-size")
                    {:chunk-size chunk-size :chunk-overlap chunk-overlap :path path}))))

(defmethod process-document :chunked
  [_strategy path content metadata]
  (let [chunk-size (get metadata :chunk-size 512)
        chunk-overlap (get metadata :chunk-overlap 100)
        _ (validate-chunk-config chunk-size chunk-overlap path)
        splitter (splitter-cache chunk-size chunk-overlap)
        document (Document/from content)
        chunks (.split splitter document)
        chunk-count (count chunks)
        file-id path]
    (second
     (reduce
      (fn [[char-offset result] [chunk-index chunk]]
        (let [chunk-text (.text chunk)
              segment-id (str file-id "-chunk-" chunk-index)
              enhanced-metadata (assoc metadata
                                       :chunk-index chunk-index
                                       :chunk-count chunk-count
                                       :chunk-offset char-offset)]
          [(+ char-offset (- (count chunk-text) chunk-overlap))
           (conj result (create-segment-descriptor file-id segment-id chunk-text chunk-text enhanced-metadata))]))
      [0 []]
      (map-indexed vector chunks)))))

(defn- validate-segment
  "Validate that a segment descriptor has all required fields and valid values.
  Returns the segment if valid, throws ex-info if invalid."
  [segment]
  (let [required-keys #{:file-id :segment-id :text-to-embed :content-to-store :metadata}
        missing-keys (remove #(contains? segment %) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info "Malformed segment descriptor: missing required keys"
                      {:missing-keys missing-keys
                       :segment segment})))
    ;; Validate text-to-embed
    (let [text-to-embed (:text-to-embed segment)]
      (when-not (string? text-to-embed)
        (throw (ex-info "Malformed segment descriptor: :text-to-embed must be a string"
                        {:text-to-embed text-to-embed
                         :segment segment})))
      (when (empty? text-to-embed)
        (throw (ex-info "Malformed segment descriptor: :text-to-embed cannot be empty"
                        {:segment segment}))))
    ;; Validate content-to-store
    (let [content-to-store (:content-to-store segment)]
      (when-not (string? content-to-store)
        (throw (ex-info "Malformed segment descriptor: :content-to-store must be a string"
                        {:content-to-store content-to-store
                         :segment segment})))
      (when (empty? content-to-store)
        (throw (ex-info "Malformed segment descriptor: :content-to-store cannot be empty"
                        {:segment segment})))))
  segment)

(defn ingest-file
  "Ingest a single file into the embedding store.

  Takes a system map with :embedding-model, :embedding-store,
  and :metadata-values, and a file map from files-from-path-spec
  with :file, :path, :metadata, and :ingest strategy keys.

  Returns the file map on success, or the file map with an :error key on
  failure."
  [{:keys [embedding-model embedding-store metadata-values]}
   {:keys [file path metadata ingest] :as file-map}]
  (try
    (let [content (slurp file)
          ;; Process document to get segment descriptors
          segment-descriptors (process-document ingest path content metadata)]
      ;; Validate all segment descriptors
      (doseq [descriptor segment-descriptors]
        (validate-segment descriptor))
      ;; Create embeddings and store segments
      (doseq [{:keys [file-id text-to-embed content-to-store metadata]} segment-descriptors]
        (let [lc4j-metadata (build-lc4j-metadata metadata)
              response (.embed ^EmbeddingModel embedding-model text-to-embed)
              embedding (.content ^dev.langchain4j.model.output.Response response)
              storage-segment (TextSegment/from content-to-store lc4j-metadata)]
          (.add ^InMemoryEmbeddingStore embedding-store file-id embedding storage-segment)))
      ;; Track metadata from all segment descriptors
      (when metadata-values
        (doseq [descriptor segment-descriptors]
          (record-metadata-values metadata-values (:metadata descriptor))))
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
