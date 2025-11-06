(ns mcp-vector-search.ingest.chunked
  "Chunked document ingestion strategy.

  Splits large documents into smaller chunks using LangChain4j's recursive text
  splitter. Each chunk is embedded and stored separately with metadata tracking
  chunk position and overlap.

  Configuration options (via metadata):
  - :chunk-size - Maximum characters per chunk (default: 512)
  - :chunk-overlap - Overlap between consecutive chunks (default: 100)

  The splitter instances are cached based on chunk-size and chunk-overlap to
  avoid recreating them for each document."
  (:require
    [mcp-vector-search.ingest.common :as common])
  (:import
    (dev.langchain4j.data.document
      Document
      DocumentSplitter)
    (dev.langchain4j.data.document.splitter
      DocumentSplitters)
    (dev.langchain4j.data.segment
      TextSegment)))


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


(defn- ^long next-chunk-offset
  "Calculate the character offset for the next chunk."
  ^long [^long current-offset chunk-text ^long chunk-overlap]
  (+ current-offset (- (count chunk-text) chunk-overlap)))


(defmethod common/process-document :chunked
  [_strategy path content metadata]
  (let [chunk-size (get metadata :chunk-size 512)
        chunk-overlap (get metadata :chunk-overlap 100)
        _ (validate-chunk-config chunk-size chunk-overlap path)
        ^DocumentSplitter splitter (splitter-cache chunk-size chunk-overlap)
        document (Document/from content)
        chunks (.split splitter document)
        chunk-count (count chunks)
        file-id path]
    (loop [indexed-chunks (map-indexed vector chunks)
           char-offset (long 0)
           result []]
      (if-let [[chunk-index ^TextSegment chunk] (first indexed-chunks)]
        (let [chunk-text (.text chunk)
              segment-id (common/generate-segment-id file-id chunk-index)
              enhanced-metadata (assoc metadata
                                       :chunk-index chunk-index
                                       :chunk-count chunk-count
                                       :chunk-offset char-offset)
              descriptor (common/create-segment-descriptor file-id segment-id chunk-text chunk-text enhanced-metadata)]
          (recur (rest indexed-chunks)
                 (next-chunk-offset char-offset chunk-text chunk-overlap)
                 (conj result descriptor)))
        result))))
