(ns mcp-vector-search.ingest.common
  "Shared helper functions and strategy protocol for document ingestion.

  Provides common utilities used across all ingestion strategies:
  - Segment ID generation for single and multi-segment documents
  - Metadata conversion between Clojure and LangChain4j formats
  - Segment descriptor creation with standardized fields
  - process-document multimethod for strategy-based ingestion

  The process-document multimethod enables multiple ingestion strategies
  to coexist by dispatching on a strategy keyword. Each strategy can have
  its own implementation in a separate namespace while using the shared
  helper functions defined here."
  (:import
    (dev.langchain4j.data.document
      Metadata)))


(defn generate-segment-id
  "Generate a segment ID for a document segment.

  For single-segment documents (1-arity), returns the file-id unchanged.
  For multi-segment documents (2-arity), appends #index to the file-id.

  Parameters:
  - file-id: The file path or identifier
  - index: (optional) The segment index for multi-segment documents

  Returns: String segment ID

  Examples:
    (generate-segment-id \"path/file.txt\")
    ;=> \"path/file.txt\"

    (generate-segment-id \"path/file.txt\" 0)
    ;=> \"path/file.txt#0\"

    (generate-segment-id \"path/file.txt\" 5)
    ;=> \"path/file.txt#5\""
  ([file-id]
   file-id)
  ([file-id index]
   (str file-id "#" index)))


(defn build-lc4j-metadata
  "Convert a Clojure metadata map to a LangChain4j Metadata object.

  Converts keyword keys to strings as required by LangChain4j.

  Parameters:
  - metadata: Clojure map with keyword or string keys

  Returns: dev.langchain4j.data.document.Metadata instance

  Example:
    (build-lc4j-metadata {:type \"doc\" :version \"v1\"})
    ;=> #<Metadata ...>"
  [metadata]
  (let [string-metadata (into {} (map (fn [[k v]] [(name k) v]) metadata))]
    (Metadata/from string-metadata)))


(defn create-segment-descriptor
  "Create a segment descriptor with all required fields.

  A segment descriptor specifies what text to embed and what content to store
  for a document segment. Metadata is automatically enhanced with file-id,
  segment-id, and doc-id fields.

  Parameters:
  - file-id: Shared identifier for all segments from the same file
  - segment-id: Unique identifier for this specific segment
  - text-to-embed: Text content to use for embedding generation
  - content-to-store: Text content to store in the vector database
  - metadata: Base metadata map (will be enhanced)

  Returns: Map with keys:
  - :file-id - Shared ID for all segments from this file
  - :segment-id - Unique ID for this segment
  - :text-to-embed - Text used for embedding
  - :content-to-store - Text stored in database
  - :metadata - Enhanced metadata map with :file-id, :segment-id, :doc-id

  Example:
    (create-segment-descriptor
      \"path/file.txt\"
      \"path/file.txt#0\"
      \"text to embed\"
      \"stored text\"
      {:source \"test\"})
    ;=> {:file-id \"path/file.txt\"
    ;    :segment-id \"path/file.txt#0\"
    ;    :text-to-embed \"text to embed\"
    ;    :content-to-store \"stored text\"
    ;    :metadata {:source \"test\"
    ;               :file-id \"path/file.txt\"
    ;               :segment-id \"path/file.txt#0\"
    ;               :doc-id \"path/file.txt\"}}"
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


;; Ingestion strategy protocol

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
