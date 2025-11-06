(ns mcp-vector-search.ingest
  "Document ingestion with pluggable processing strategies.

  ## Responsibilities
  Matches files from parsed path specs, processes their content using configurable
  strategies, and stores embeddings in the vector database. Tracks metadata
  values for dynamic schema generation.

  ## Strategy Extensibility
  The `process-document` multimethod (defined in `ingest.common`) enables
  multiple ingestion strategies to coexist. This namespace requires strategy
  namespaces that extend the multimethod with their implementations:
  - `ingest.single-segment` - Single-segment strategies with composable embedding/content
  - `ingest.chunked` - Multi-segment chunked document processing

  Built-in single-segment strategies (via `ingest.single-segment`):
  - `:whole-document` - Embeds and stores entire files
  - `:namespace-doc` - Embeds namespace docstrings but stores full source
  - `:file-path` - Embeds full content but stores only file paths
  - `:single-segment` - Direct composition of :embedding and :content-strategy

  Built-in multi-segment strategy:
  - `:chunked` - Splits documents into chunks with configurable size/overlap

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
    [mcp-vector-search.ingest.code-analysis]
    [mcp-vector-search.ingest.common :as common]
    [mcp-vector-search.ingest.single-segment]
    [mcp-vector-search.util :as util])
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
    (io.github.classgraph
      ClassGraph)
    (java.io
      File
      IOException)
    (java.time
      Instant)
    (java.util.regex
      Matcher
      Pattern)))


(defn- walk-files
  "Walk directory tree and return all files."
  [^File dir]
  (when (.isDirectory dir)
    (tree-seq
      (fn [^File f] (.isDirectory f))
      (fn [^File f] (seq (.listFiles f)))
      dir)))


(defn- parent-directory
  "Extract the parent directory from a path.
  Returns the path up to the last slash, or the entire path if no slash is found.
  Returns nil for blank paths."
  [path]
  (when-not (str/blank? path)
    (let [last-slash (.lastIndexOf ^String path "/")]
      (if (pos? last-slash)
        (subs path 0 last-slash)
        path))))


(defn- files-from-classpath-spec
  "Apply a path spec to classpath resources and return matching resources with metadata.

  Takes a path spec map with:
  - :segments - parsed segment structure
  - :base-path - path prefix for filtering resources
  - :base-metadata (optional) - metadata to merge with captures
  - :ingest - processing strategy
  - :path (optional) - original path string for tracking

  Returns a sequence of maps:
  - :file - nil (no File object for classpath resources)
  - :path - resource path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures
  - :ingest - processing strategy
  - :source-path - original path spec string for statistics tracking"
  [{:keys [segments base-path base-metadata ingest path embedding content-strategy] :as _path-spec}]
  (let [pattern (util/build-pattern segments)
        ;; ClassGraph's acceptPaths requires directory paths, not file paths
        dir-prefix (parent-directory base-path)
        class-graph (ClassGraph.)
        scan-result (-> (if dir-prefix
                          (.acceptPaths class-graph (into-array String [dir-prefix]))
                          class-graph)
                        (.scan))
        resources (.getAllResources scan-result)]
    (try
      (let [result (into []
                         (keep (fn [resource]
                                 (let [resource-path (.getPath resource)
                                       matcher (re-matcher pattern resource-path)]
                                   (when (.matches matcher)
                                     (let [captures (util/extract-captures matcher segments)
                                           strategy-keys (cond-> {}
                                                           embedding (assoc :embedding embedding)
                                                           content-strategy (assoc :content-strategy content-strategy))]
                                       {:file        nil  ; No File object for classpath resources
                                        :path        resource-path
                                        :captures    captures
                                        :metadata    (merge base-metadata captures strategy-keys)
                                        :ingest      ingest
                                        :source-path path})))))
                         resources)]
        result)
      (finally
        (.close scan-result)))))


(defn- files-from-filesystem-spec
  "Apply a path spec to the filesystem and return matching files with metadata.

  Takes a path spec map with:
  - :segments - parsed segment structure
  - :base-path - starting directory for filesystem walk
  - :base-metadata (optional) - metadata to merge with captures
  - :ingest - processing strategy
  - :path (optional) - original path string for tracking

  Returns a sequence of maps:
  - :file - java.io.File object
  - :path - file path string
  - :captures - map of captured values
  - :metadata - merged base metadata and captures
  - :ingest - processing strategy
  - :source-path - original path spec string for statistics tracking"
  [{:keys [segments base-path base-metadata ingest path embedding content-strategy] :as _path-spec}]
  (let [base-file (io/file base-path)
        ;; Normalize base path to handle symlinks (e.g., /var -> /private/var)
        normalized-base (util/normalize-file-path base-file)
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
        pattern (util/build-pattern normalized-segments)
        strategy-keys (cond-> {}
                        embedding (assoc :embedding embedding)
                        content-strategy (assoc :content-strategy content-strategy))]
    (if (.isFile base-file)
      ;; Literal file path
      (let [file-path (util/normalize-file-path base-file)]
        (when (re-matches pattern file-path)
          [{:file        base-file
            :path        file-path
            :captures    {}
            :metadata    (merge (or base-metadata {}) strategy-keys)
            :ingest      ingest
            :source-path path}]))
      ;; Directory - walk and match
      (let [files (if (.isDirectory base-file)
                    (filter #(.isFile ^File %) (walk-files base-file))
                    [])]
        (into []
              (keep (fn [^File file]
                      (let [file-path (util/normalize-file-path file)
                            matcher   (re-matcher pattern file-path)]
                        (when (.matches matcher)
                          (let [captures (util/extract-captures matcher normalized-segments)]
                            {:file        file
                             :path        file-path
                             :captures    captures
                             :metadata    (merge base-metadata captures strategy-keys)
                             :ingest      ingest
                             :source-path path}))))
                    files))))))


(defn files-from-path-spec
  "Apply a path spec and return matching files/resources with metadata.

  Dispatches to filesystem or classpath implementation based on :source-type.

  Takes a path spec map with:
  - :source-type - :filesystem or :classpath
  - :segments - parsed segment structure
  - :base-path - starting directory for filesystem walk or path prefix for classpath
  - :base-metadata (optional) - metadata to merge with captures
  - :ingest - processing strategy
  - :path (optional) - original path string for tracking

  Returns a sequence of maps with :file, :path, :captures, :metadata, :ingest, :source-path"
  [{:keys [source-type] :as path-spec}]
  (case source-type
    :filesystem (files-from-filesystem-spec path-spec)
    :classpath  (files-from-classpath-spec path-spec)
    ;; Default to filesystem for backward compatibility
    (files-from-filesystem-spec path-spec)))


(defn- record-metadata-values
  "Record metadata field values in the system atom.
  Updates the metadata-values to track all distinct values seen for each metadata key."
  [system-atom metadata]
  (swap! system-atom
         (fn [current]
           (update current :metadata-values
                   (fn [metadata-values]
                     (reduce (fn [acc [k v]]
                               (update acc k (fnil conj #{}) v))
                             metadata-values
                             metadata))))))


(defn- record-path-captures
  "Record captured values per path spec in the system atom.
  Updates the path-captures to track all distinct captured values for each path spec.

  Takes:
  - system-atom: the system atom
  - path-spec-path: the original path string from the path spec
  - captures: map of capture names to captured values

  Updates the path-captures structure to maintain:
  {:path-specs [{:path \"...\" :captures {:category #{\"api\" \"guides\"}}}]}"
  [system-atom path-spec-path captures]
  (when (seq captures)
    (swap! system-atom
           (fn [current]
             (update current :path-captures
                     (fn [path-captures]
                       (let [existing-specs (:path-specs path-captures)
                             existing-spec (first (filter #(= (:path %) path-spec-path) existing-specs))]
                         (if existing-spec
                           ;; Update existing path spec's captures
                           (update path-captures :path-specs
                                   (fn [specs]
                                     (mapv (fn [spec]
                                             (if (= (:path spec) path-spec-path)
                                               (update spec :captures
                                                       (fn [current-captures]
                                                         (reduce (fn [acc [k v]]
                                                                   (update acc k (fnil conj #{}) v))
                                                                 (or current-captures {})
                                                                 captures)))
                                               spec))
                                           specs)))
                           ;; Add new path spec
                           (update path-captures :path-specs
                                   conj
                                   {:path path-spec-path
                                    :captures (into {} (map (fn [[k v]] [k #{v}]) captures))})))))))))


(defn- classify-error-type
  "Classify an exception into an error type keyword.
  Returns one of: :read-error, :parse-error, :embedding-error, :validation-error"
  [^Exception e]
  (or (-> e ex-data :type)
      (cond
        (instance? IOException e) :read-error
        :else :embedding-error)))


(def ^:private max-error-queue-size
  "Maximum number of errors to retain in the failures queue."
  20)


(def ^:private max-sources-tracked
  "Maximum number of sources to track in ingestion statistics."
  100)


(defn- add-error-to-queue
  "Add an error to the bounded failures queue (max 20 items).
  When queue is full, drops oldest error."
  [system-atom error-record]
  (swap! system-atom
         (fn [current]
           (update current :ingestion-failures
                   (fn [queue]
                     (let [queue-vec (or queue [])
                           new-queue (conj queue-vec error-record)]
                       (if (> (count new-queue) max-error-queue-size)
                         (vec (rest new-queue))
                         new-queue)))))))


(defn- update-stats-map
  "Pure function to update ingestion statistics.

  Takes current stats map, source path to update, stats deltas, and timestamp.
  Returns updated stats map with both per-source and total statistics updated.

  Parameters:
  - current-stats: The current :ingestion-stats map
  - source-path: The path string identifying which source to update
  - stats-update: Map of deltas to apply (e.g. {:files-processed 1, :segments-created 5})
  - timestamp: Timestamp to set as :last-ingestion-at

  Returns updated :ingestion-stats map."
  [current-stats source-path stats-update timestamp]
  (-> current-stats
      (update :sources
              (fn [sources]
                (mapv (fn [source]
                        (if (= (:path source) source-path)
                          (merge-with + source stats-update)
                          source))
                      sources)))
      (update :total-documents (fnil + 0) (get stats-update :files-processed 0))
      (update :total-segments (fnil + 0) (get stats-update :segments-created 0))
      (update :total-errors (fnil + 0) (get stats-update :errors 0))
      (assoc :last-ingestion-at timestamp)))


(defn- update-ingestion-stats
  "Update ingestion statistics in the system atom.
  Updates both per-source and total statistics."
  [system source-path stats-update]
  (swap! system
         update-in
         [:ingestion-stats]
         update-stats-map
         source-path
         stats-update
         (Instant/now)))


(defn- validate-segment
  "Validate that a segment descriptor has all required fields and valid values.
  Returns the segment if valid, throws ex-info if invalid."
  [segment]
  (let [required-keys #{:file-id :segment-id :text-to-embed :content-to-store :metadata}
        missing-keys (remove #(contains? segment %) required-keys)]
    (when (seq missing-keys)
      (throw (ex-info "Malformed segment descriptor: missing required keys"
                      {:type :validation-error
                       :missing-keys missing-keys
                       :segment segment})))
    ;; Validate text-to-embed
    (let [text-to-embed (:text-to-embed segment)]
      (when-not (string? text-to-embed)
        (throw (ex-info "Malformed segment descriptor: :text-to-embed must be a string"
                        {:type :validation-error
                         :text-to-embed text-to-embed
                         :segment segment})))
      (when (empty? text-to-embed)
        (throw (ex-info "Malformed segment descriptor: :text-to-embed cannot be empty"
                        {:type :validation-error
                         :segment segment}))))
    ;; Validate content-to-store
    (let [content-to-store (:content-to-store segment)]
      (when-not (string? content-to-store)
        (throw (ex-info "Malformed segment descriptor: :content-to-store must be a string"
                        {:type :validation-error
                         :content-to-store content-to-store
                         :segment segment})))
      (when (empty? content-to-store)
        (throw (ex-info "Malformed segment descriptor: :content-to-store cannot be empty"
                        {:type :validation-error
                         :segment segment})))))
  segment)


(defn ingest-file
  "Ingest a single file or classpath resource into the embedding store.

  Takes a system atom containing :embedding-model and :embedding-store,
  and a file map from files-from-path-spec with :file, :path, :metadata,
  :ingest strategy, :source-path, and :captures keys.
  For classpath resources, :file will be nil and :path contains the resource path.

  Returns the file map on success, or the file map with :error and :error-type
  keys on failure. Updates state (metadata, failures, captures) in the system atom."
  [system-atom {:keys [file path metadata ingest source-path captures] :as file-map}]
  (let [{:keys [embedding-model embedding-store]} @system-atom]
    (try
      (let [content (if file
                      ;; Filesystem resource
                      (slurp file)
                      ;; Classpath resource
                      (let [resource-url (io/resource path)]
                        (when-not resource-url
                          (throw (ex-info (str "Classpath resource not found: " path)
                                          {:type :read-error
                                           :path path
                                           :source-path source-path})))
                        (slurp resource-url)))
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
        (doseq [descriptor segment-descriptors]
          (record-metadata-values system-atom (:metadata descriptor)))
        ;; Track path captures
        (record-path-captures system-atom source-path captures)
        ;; Record success statistics
        (assoc file-map :segment-count (count segment-descriptors)))
      (catch Exception e
        (let [error-type (classify-error-type e)
              error-msg (.getMessage e)
              error-record {:file-path    path
                            :error-type   error-type
                            :message      error-msg
                            :source-path  source-path
                            :timestamp    (Instant/now)}]
          ;; Record error in failures queue
          (add-error-to-queue system-atom error-record)
          (assoc file-map
                 :error error-msg
                 :error-type error-type))))))


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

  Takes a system atom and a sequence of file maps from files-from-path-spec.
  Updates ingestion statistics for each source-path.
  Returns a map with :ingested count, :failed count, and :failures sequence."
  [system-atom file-maps]
  (let [system-map @system-atom
        results (map #(ingest-file system-atom %) file-maps)
        successes (filter (complement :error) results)
        failures (filter :error results)
        ;; Group results by source-path for statistics updates
        by-source (group-by :source-path results)]
    ;; Update statistics for each source
    (doseq [[source-path source-results] by-source]
      (let [source-successes (filter (complement :error) source-results)
            source-failures (filter :error source-results)
            total-segments (reduce + 0 (map #(or (:segment-count %) 0) source-successes))]
        (update-ingestion-stats system-atom source-path
                                {:files-matched (count source-results)
                                 :files-processed (count source-successes)
                                 :segments-created total-segments
                                 :errors (count source-failures)})))
    ;; Log failures and completion
    (when (seq failures)
      (doseq [failure failures]
        (log-if-server system-map :error {:event "ingest-failed"
                                          :path (:path failure)
                                          :error (:error failure)})))
    (log-if-server system-map :info {:event "ingest-complete"
                                     :ingested (count successes)
                                     :failed (count failures)})
    {:ingested (count successes)
     :failed (count failures)
     :failures failures}))


(defn ingest
  "Ingest documents into the vector search system.

  Takes a system atom with :embedding-model and :embedding-store,
  and a parsed-config map with :path-specs (a sequence of path spec maps).

  Initializes per-source statistics in the system atom before ingestion.
  Returns ingestion statistics map with :ingested, :failed, and :failures."
  [system-atom {:keys [path-specs] :as _parsed-config}]
  ;; Initialize per-source statistics in system atom if not already present
  (swap! system-atom
         (fn [current]
           (let [existing-sources (set (map :path (get-in current [:ingestion-stats :sources])))
                 current-count (count (get-in current [:ingestion-stats :sources]))
                 new-sources (remove #(existing-sources (:path %))
                                     (map (fn [spec]
                                            {:path (:path spec)
                                             :files-matched 0
                                             :files-processed 0
                                             :segments-created 0
                                             :errors 0})
                                          path-specs))
                 available-slots (- max-sources-tracked current-count)
                 sources-to-add (if (pos? available-slots)
                                  (take available-slots new-sources)
                                  [])]
             (when (< (count sources-to-add) (count new-sources))
               (log-if-server
                 current
                 :warn
                 {:event "sources-limit-reached"
                  :max-sources max-sources-tracked
                  :current-count current-count
                  :attempted-to-add (count new-sources)
                  :actually-added (count sources-to-add)}))
             (update-in current [:ingestion-stats :sources] concat sources-to-add))))
  (let [all-files (mapcat files-from-path-spec path-specs)]
    (ingest-files system-atom all-files)))
