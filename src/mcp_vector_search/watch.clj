(ns mcp-vector-search.watch
  "File watching with debouncing for automatic re-indexing.

  ## Responsibilities
  Monitors configured file paths for changes and automatically re-indexes
  modified documents. Handles file creation, modification, and deletion events.
  Provides lifecycle management for watch resources.

  ## Implementation Notes
  Uses beholder library for filesystem watching. Events are debounced (500ms) to
  avoid excessive re-indexing when multiple rapid changes occur. Path
  normalization handles symlinks (e.g., /var -> /private/var on macOS).
  Modified and deleted files have all segments removed by querying file-id
  metadata and extracting internal IDs. This supports multi-segment files where
  multiple embeddings share the same file-id."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [mcp-clj.mcp-server.logging :as logging]
    [mcp-vector-search.config :as config]
    [mcp-vector-search.ingest :as ingest]
    [mcp-vector-search.util :as util]
    [nextjournal.beholder :as beholder])
  (:import
    (dev.langchain4j.data.segment
      TextSegment)
    (dev.langchain4j.model.embedding
      EmbeddingModel)
    (dev.langchain4j.store.embedding
      EmbeddingMatch
      EmbeddingSearchRequest
      EmbeddingStore)
    (dev.langchain4j.store.embedding.filter.comparison
      IsEqualTo)
    (java.io
      File)
    (java.time
      Instant)
    (java.util
      Collection
      Timer)))


;; Debouncing

(defonce debounce-state (atom {}))
(defonce debounce-timer (atom nil))

(def debounce-ms 500)


(def ^:private max-search-results
  "Maximum number of search results when querying for embeddings to remove."
  10000)


(defn- log-if-server
  "Log a message if server is available in the system."
  [system level data]
  (when-let [server (:server system)]
    (case level
      :info (logging/info server data :logger "watch")
      :warn (logging/warn server data :logger "watch")
      :error (logging/error server data :logger "watch")
      :debug (logging/debug server data :logger "watch"))))


(defn- remove-by-file-id
  "Remove all embeddings with the given file-id from the embedding store.

  Searches for all embeddings with matching file-id metadata, extracts their
  internal IDs, and removes them. This handles multi-segment files where
  multiple embeddings share the same file-id.

  Returns the number of embeddings removed."
  [^EmbeddingStore embedding-store ^EmbeddingModel embedding-model file-id]
  (let [;; Create a dummy query embedding for search (similarity doesn't matter)
        dummy-query (.content (.embed embedding-model (TextSegment/from "dummy")))
        ;; Create metadata filter for file-id
        metadata-filter (IsEqualTo. "file-id" file-id)
        ;; Search with large max results to get all matches
        request (-> (EmbeddingSearchRequest/builder)
                    (.queryEmbedding dummy-query)
                    (.maxResults (int max-search-results))
                    (.filter metadata-filter)
                    (.build))
        matches (-> embedding-store
                    (.search request)
                    .matches)
        ;; Extract internal IDs from matches
        ids (mapv #(.embeddingId ^EmbeddingMatch %) matches)]
    (when (seq ids)
      (.removeAll embedding-store ^Collection ids))
    (count ids)))


(defn- process-pending-events
  "Process all pending debounced events."
  [system-atom]
  (let [system-map @system-atom
        [old-state _new-state] (swap-vals! debounce-state (constantly {}))
        events-to-process
        (reduce-kv
          (fn [acc path {:keys [event path-spec]}]
            (conj acc {:path path :event event :path-spec path-spec}))
          []
          old-state)]

    ;; Process each event
    (doseq [{:keys [path event path-spec]} events-to-process]
      (try
        (log-if-server system-map :info {:event event :path path})
        (let [kind (:kind event)]
          (cond
            (#{:create :modify} kind)
            (let [file (io/file path)
                  base-metadata (:base-metadata path-spec)
                  ingest (:ingest path-spec)
                  pattern (util/build-pattern (:segments path-spec))
                  matcher (re-matcher pattern path)]
              (if (.matches matcher)
                (let [captures (util/extract-captures matcher (:segments path-spec))
                      metadata (merge base-metadata captures)
                      file-map {:file file
                                :path path
                                :captures captures
                                :metadata metadata
                                :ingest ingest}]

                  (when (= kind :modify)
                    (log-if-server
                      system-map
                      :info
                      {:event "file-modified" :path path})
                    ;; Remove all segments with this file-id before re-adding
                    (let [embedding-store (:embedding-store system-map)
                          embedding-model (:embedding-model system-map)
                          removed-count (remove-by-file-id embedding-store embedding-model path)]
                      (log-if-server
                        system-map
                        :info
                        {:event "segments-removed" :file-id path :count removed-count})))
                  (when (= kind :create)
                    (log-if-server
                      system-map
                      :info
                      {:event "file-created" :path path}))
                  (let [result (ingest/ingest-file system-atom file-map)]
                    (when (:error result)
                      (log-if-server system-map :error {:event "ingest-failed"
                                                        :path path
                                                        :error (:error result)}))))
                ;; Pattern didn't match - this shouldn't happen if filter is correct
                (log-if-server system-map :warn {:event "file-not-matched"
                                                 :path path
                                                 :pattern (str pattern)})))

            (= kind :delete)
            (do
              (log-if-server system-map :info {:event "file-deleted" :path path})
              (let [embedding-store (:embedding-store system-map)
                    embedding-model (:embedding-model system-map)
                    removed-count (remove-by-file-id embedding-store embedding-model path)]
                (log-if-server
                  system-map
                  :info
                  {:event "segments-removed" :file-id path :count removed-count}))))
          ;; Update watch statistics for processed events
          (swap! system-atom
                 (fn [current]
                   (update current :watch-stats
                           (fn [stats]
                             (-> stats
                                 (update-in [:debounce :processed] inc)
                                 (update-in [:events (case kind
                                                       :create :created
                                                       :modify :modified
                                                       :delete :deleted)]
                                            inc)))))))
        (catch Exception e
          (log-if-server system-map :error {:event "watch-error"
                                            :path path
                                            :error (.getMessage e)})
          (.printStackTrace e))))))


(defn- schedule-debounce-flush
  "Schedule a flush of pending events after debounce period."
  [system-atom]
  ;; Only schedule if not already scheduled
  (when-not @debounce-timer
    (let [timer (java.util.Timer. true)]
      (reset! debounce-timer timer)
      (.schedule timer
                 (proxy [java.util.TimerTask] []
                   (run
                     []
                     (try
                       (process-pending-events system-atom)
                       (finally
                         (reset! debounce-timer nil)
                         ;; Reschedule if more events arrived
                         (when (seq @debounce-state)
                           (schedule-debounce-flush system-atom))))))
                 (long debounce-ms)))))


(defn- debounce-event
  "Add event to debounce state and schedule processing."
  [system-atom path event path-spec]
  (swap! debounce-state
         assoc
         path
         {:event event
          :path-spec path-spec})
  ;; Update watch statistics
  (swap! system-atom
         (fn [current]
           (update current :watch-stats
                   (fn [stats]
                     (-> stats
                         (update-in [:debounce :queued] inc)
                         (assoc-in [:events :last-event-at] (Instant/now)))))))
  (schedule-debounce-flush system-atom))


;; Watch setup

(defn- should-watch-recursively?
  "Determine if watching should be recursive based on path-spec segments."
  [segments base-path]
  (let [remaining (drop-while #(= :literal (:type %)) segments)]
    (boolean (some #(and (= :glob (:type %))
                         (= "**" (:pattern %)))
                   remaining))))


(defn- matches-path-spec?
  "Check if a file path matches the path spec pattern."
  [path segments]
  (let [pattern (util/build-pattern segments)
        matcher (re-matcher pattern path)]
    (.matches matcher)))


(defn- should-process-event?
  "Determine if a watch event should be processed.
  Returns true if the path is a file and matches the path-spec pattern."
  [path-str normalized-segments system-atom watch-path]
  (let [file (io/file path-str)
        is-file? (.isFile file)
        file-path (util/normalize-file-path path-str)
        matches? (matches-path-spec? file-path normalized-segments)]
    (when (and is-file? (not matches?))
      (log-if-server @system-atom :info {:event "file-filtered-out"
                                         :path file-path
                                         :watch-path watch-path}))
    (and is-file? matches?)))


(defn- setup-watch
  "Setup a watch for a single path-spec.
  Returns a beholder watcher handle."
  [system-atom path-spec]
  (let [{:keys [base-path segments]} path-spec
        ;; Convert relative paths to absolute, then normalize to handle symlinks
        base-file-for-absolute (io/file base-path)
        absolute-base (if (.isAbsolute base-file-for-absolute)
                        base-path
                        (.getCanonicalPath base-file-for-absolute))
        ;; Normalize to handle symlinks (e.g., /var -> /private/var on macOS)
        normalized-base (util/normalize-file-path absolute-base)
        base-file (io/file normalized-base)
        watch-path (if (.isFile base-file)
                     (.getParent base-file)
                     normalized-base)
        ;; Build pattern using absolute normalized base
        ;; First, determine what the original literal prefix would be as absolute
        original-relative-base (str/join (map :value (take-while #(= :literal (:type %)) segments)))
        ;; Preserve trailing separator if present
        has-trailing-sep? (str/ends-with? original-relative-base "/")
        normalized-base-with-sep (if (and has-trailing-sep?
                                          (not (str/ends-with? normalized-base "/")))
                                   (str normalized-base "/")
                                   normalized-base)
        ;; Build the full pattern string from segments
        relative-pattern-str (str/join (mapv (fn [{:keys [type] :as segment}]
                                               (case type
                                                 :literal (:value segment)
                                                 :glob (case (:pattern segment)
                                                         "**" "**"
                                                         "*" "*")
                                                 :capture (str "(?<" (:name segment) ">" (:pattern segment) ")")))
                                             segments))
        ;; Replace the relative base with the absolute normalized base
        normalized-pattern (str/replace-first
                             relative-pattern-str
                             original-relative-base
                             normalized-base-with-sep)
        ;; Parse the normalized pattern back into segments
        normalized-segments (:segments (config/parse-path-spec normalized-pattern))
        ;; Create normalized path-spec for event processing
        normalized-path-spec (assoc path-spec :segments normalized-segments)
        recursive? (should-watch-recursively? normalized-segments normalized-base)
        system-map @system-atom]

    (try
      (log-if-server system-map :info {:event "watch-started"
                                       :path watch-path
                                       :recursive recursive?})
      ;; Beholder automatically watches directories recursively using directory-watcher.
      ;; Unlike hawk which required explicit recursive configuration, beholder handles
      ;; this natively on all platforms (macOS via JNA, Linux/Windows via directory-watcher).
      (beholder/watch
        ;; Callback runs asynchronously, needs fresh system state
        ;; (system-map captured above would be stale)
        (fn [{:keys [type ^java.nio.file.Path path]}]
          (let [path-str (str path)]
            (when (should-process-event? path-str normalized-segments system-atom watch-path)
              (debounce-event system-atom
                              (util/normalize-file-path path-str)
                              {:kind type :file (io/file path-str)}
                              normalized-path-spec))))
        watch-path)
      (catch Exception e
        (log-if-server system-map :error {:event "watch-setup-failed"
                                          :path normalized-base
                                          :error (.getMessage e)})
        (binding [*out* *err*]
          (println "Failed to setup watch for" base-path ":" (.getMessage e)))
        nil))))


(defn start-watches
  "Start file watching for all path-specs with :watch? enabled.
  Takes a system atom and parsed config with :path-specs.
  Returns a vector of beholder watcher handles.

  Excludes classpath sources (read-only) from watching."
  [system-atom {:keys [path-specs watch?] :as _config}]
  (let [global-watch? (boolean watch?)
        watched-specs (filter #(and (get % :watch? global-watch?)
                                    (= :filesystem (:source-type %)))
                              path-specs)]
    (when (seq watched-specs)
      ;; Update watch statistics
      (swap! system-atom
             (fn [current]
               (update current :watch-stats
                       (fn [stats]
                         (-> stats
                             (assoc :enabled? true)
                             (assoc :sources
                                    (mapv (fn [spec]
                                            {:path (str/join (map :value (filter #(= :literal (:type %))
                                                                                 (:segments spec))))
                                             :watching? true})
                                          watched-specs)))))))
      (into [] (keep #(setup-watch system-atom %)) watched-specs))))


(defn stop-watches
  "Stop all file watches.
  Takes a vector of beholder watcher handles."
  [watchers]
  (doseq [watcher watchers]
    (try
      (beholder/stop watcher)
      (catch Exception e
        (println "Error stopping watch:" (.getMessage e)))))

  ;; Cancel debounce timer
  (when-let [^Timer timer @debounce-timer]
    (.cancel timer)
    (reset! debounce-timer nil))

  ;; Clear debounce state
  (reset! debounce-state {}))
