(ns mcp-vector-search.ingest.single-segment
  "Composable single-segment document processing strategies.

  This namespace provides a composable architecture for ingesting documents that
  are processed as a single segment. Unlike multi-segment strategies (e.g., :chunked),
  single-segment strategies process each file into exactly one embedding/storage pair.

  ## Architecture

  The composability is achieved through two independent multimethods:

  - `embed-content`: Determines WHAT text to embed for semantic search
  - `extract-content`: Determines WHAT content to store in the database

  These strategies can be mixed and matched to achieve different behaviors:
  - Embed full content, store full content (memory-intensive, complete retrieval)
  - Embed namespace docstring, store full content (targeted search, complete retrieval)
  - Embed full content, store path only (full search, minimal storage)
  - Embed namespace docstring, store path only (targeted search, minimal storage)

  ## Multimethods

  ### embed-content
  Extracts text to embed for similarity search, optionally enhancing metadata.

  Parameters: [strategy path content metadata]
  Returns: Either a string OR a map with:
    - :text (required) - The text to embed
    - :metadata (optional) - Enhanced metadata to merge with input metadata

  Built-in strategies:
  - :whole-document - Embeds complete file content
  - :namespace-doc - Embeds Clojure namespace docstring, adds :namespace to metadata

  ### extract-content
  Extracts content to store in the vector database.

  Parameters: [strategy path content metadata]
  Returns: String to store (full content, path only, or custom extraction)

  Built-in strategies:
  - :whole-document - Stores complete file content
  - :file-path - Stores only the file path

  ## Orchestration

  The :single-segment process-document implementation coordinates both strategies:

  1. Calls embed-content to get text and optional metadata enhancement
  2. Merges any enhanced metadata with input metadata
  3. Calls extract-content to get storage content
  4. Creates a single segment descriptor with both results

  ## Convenience Forwarding

  For ergonomic configuration, the original strategy names (:whole-document,
  :namespace-doc, :file-path) are preserved as forwarding implementations that
  call :single-segment with appropriate :embedding and :content-strategy parameters.

  This allows existing configs to work unchanged:
    {:ingest :whole-document}  ; Still works
    {:ingest :namespace-doc}   ; Still works
    {:ingest :file-path}       ; Still works

  While enabling new compositions:
    {:ingest :single-segment
     :embedding :namespace-doc
     :content-strategy :file-path}

  ## Usage Examples

  Direct strategy usage:
    (embed-content :whole-document path content metadata)
    ;=> {:text \"full content here\"}

    (embed-content :namespace-doc path clj-content metadata)
    ;=> {:text \"Namespace docstring\"
    ;    :metadata {:namespace \"my.ns\"}}

    (extract-content :whole-document path content metadata)
    ;=> \"full content here\"

    (extract-content :file-path path content metadata)
    ;=> \"/path/to/file\"

  Process document with composition:
    (process-document :single-segment
                      path
                      content
                      {:embedding :namespace-doc
                       :content-strategy :file-path})
    ;=> [{:file-id path
    ;     :segment-id path
    ;     :text-to-embed \"Namespace docstring\"
    ;     :content-to-store \"/path/to/file\"
    ;     :metadata {:namespace \"my.ns\" ...}}]

  Via forwarding (ergonomic):
    (process-document :whole-document path content metadata)
    ; Equivalent to :single-segment with :embedding :whole-document, :content-strategy :whole-document"
  (:require
    [mcp-vector-search.ingest.common :as common]
    [mcp-vector-search.parse :as parse]))

;;; Embedding strategies

(defmulti embed-content
  "Extract text to embed from file content, optionally enhancing metadata.

  Dispatches on embedding strategy keyword. Implementations determine what
  text should be embedded for semantic similarity search.

  Parameters:
  - strategy: Keyword identifying the embedding strategy
  - path: File path string
  - content: File content string
  - metadata: Base metadata map

  Returns: Either:
  - String: The text to embed (metadata unchanged)
  - Map with:
    - :text (required): The text to embed
    - :metadata (optional): Enhanced metadata to merge with input metadata

  Built-in strategies:
  - :whole-document - Returns full content as embedding text
  - :namespace-doc - Parses Clojure ns form, returns docstring and adds :namespace"
  (fn [strategy _path _content _metadata] strategy))

(defmulti extract-content
  "Extract content to store in vector database.

  Dispatches on content strategy keyword. Implementations determine what
  content should be stored and returned during search.

  Parameters:
  - strategy: Keyword identifying the content strategy
  - path: File path string
  - content: File content string
  - metadata: Metadata map (potentially enhanced by embed-content)

  Returns: String content to store in the vector database

  Built-in strategies:
  - :whole-document - Returns full file content
  - :file-path - Returns file path only"
  (fn [strategy _path _content _metadata] strategy))

;;; Embedding strategy implementations

(defmethod embed-content :whole-document
  [_strategy _path content _metadata]
  {:text content})

(defmethod embed-content :namespace-doc
  [_strategy _path content _metadata]
  (let [ns-form (parse/parse-first-ns-form content)]
    (when-not ns-form
      (throw (ex-info "No ns form found" {:type :parse-error})))
    (let [docstring (parse/extract-docstring ns-form)]
      (when-not docstring
        (throw (ex-info "No namespace docstring found" {:type :parse-error})))
      (let [namespace (parse/extract-namespace ns-form)]
        (when-not namespace
          (throw (ex-info "Could not extract namespace" {:type :parse-error})))
        {:text docstring
         :metadata {:namespace namespace}}))))

;;; Content strategy implementations

(defmethod extract-content :whole-document
  [_strategy _path content _metadata]
  content)

(defmethod extract-content :file-path
  [_strategy path _content _metadata]
  path)

;;; Process-document orchestration

(defmethod common/process-document :single-segment
  [_strategy path content {:keys [embedding content-strategy] :as metadata}]
  (let [;; Remove configuration keys from metadata before processing
        base-metadata (dissoc metadata :embedding :content-strategy)
        embed-result (embed-content embedding path content base-metadata)
        ;; Handle both map result (with optional metadata) and string result
        text-to-embed (if (map? embed-result) (:text embed-result) embed-result)
        enhanced-metadata (if (map? embed-result)
                            (merge base-metadata (:metadata embed-result))
                            base-metadata)
        content-to-store (extract-content content-strategy path content enhanced-metadata)
        file-id path
        segment-id (common/generate-segment-id file-id)]
    [(common/create-segment-descriptor
       file-id segment-id text-to-embed content-to-store enhanced-metadata)]))

;;; Forwarding implementations for convenience

(defmethod common/process-document :whole-document
  [_strategy path content metadata]
  (common/process-document
    :single-segment path content
    (assoc metadata :embedding :whole-document :content-strategy :whole-document)))

(defmethod common/process-document :namespace-doc
  [_strategy path content metadata]
  (common/process-document
    :single-segment path content
    (assoc metadata :embedding :namespace-doc :content-strategy :whole-document)))

(defmethod common/process-document :file-path
  [_strategy path content metadata]
  (common/process-document
    :single-segment path content
    (assoc metadata :embedding :whole-document :content-strategy :file-path)))
