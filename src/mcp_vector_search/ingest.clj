(ns mcp-vector-search.ingest
  "Document ingestion with pluggable processing strategies.

  ## Responsibilities
  Matches files from parsed path specs, processes their content using configurable
  strategies, and stores embeddings in the vector database. Tracks metadata
  values for dynamic schema generation.

  ## Strategy Extensibility
  The `process-document` multimethod (defined in `ingest.common`) enables
  multiple ingestion strategies to coexist. This namespace requires strategy
  namespaces (e.g., `ingest.chunked`) that extend the multimethod with their
  implementations.

  Built-in strategies include `:whole-document` (embeds and stores entire files),
  `:namespace-doc` (embeds namespace docstrings but stores full source),
  `:file-path` (stores only paths), and `:chunked` (splits documents into chunks).

  To add a new strategy:
  1. Create namespace `mcp-vector-search.ingest.<strategy-name>`
  2. Require `mcp-vector-search.ingest.common`
  3. Implement `(defmethod common/process-document :strategy-keyword ...)`
  4. Require the new namespace here to ensure it's loaded

  ## Implementation Notes
  File matching combines regex patterns from path specs with filesystem walking."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.logging :as logging]
    [mcp-vector-search.ingest.chunked]
    [mcp-vector-search.ingest.common :as common]
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

;;; Strategy implementations

(defmethod common/process-document :whole-document
  [_strategy path content metadata]
  (let [file-id path
        segment-id (common/generate-segment-id file-id)]
    [(common/create-segment-descriptor file-id segment-id content content metadata)]))

(defmethod common/process-document :namespace-doc
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
              segment-id (common/generate-segment-id file-id)
              enhanced-metadata (assoc metadata :namespace namespace)]
          [(common/create-segment-descriptor file-id segment-id docstring content enhanced-metadata)])))))

(defmethod common/process-document :file-path
  [_strategy path content metadata]
  (let [file-id path
        segment-id (common/generate-segment-id file-id)]
    [(common/create-segment-descriptor file-id segment-id content path metadata)]))

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
          segment-descriptors (common/process-document ingest path content metadata)]
      ;; Validate all segment descriptors
      (doseq [descriptor segment-descriptors]
        (validate-segment descriptor))
      ;; Create embeddings and store segments
      (doseq [{:keys [file-id text-to-embed content-to-store metadata]} segment-descriptors]
        (let [lc4j-metadata (common/build-lc4j-metadata metadata)
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
