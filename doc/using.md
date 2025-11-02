# Using mcp-vector-search

This guide covers configuring and using the mcp-vector-search MCP server to index and search documents using semantic embeddings.

## Overview

mcp-vector-search indexes documents using the AllMiniLmL6V2 embedding model and provides a search tool for semantic similarity queries. All embeddings are stored in memory and rebuilt on server start.

## Configuration

### Configuration File Location

Create a configuration file at:
- `~/.mcp-vector-search/config.edn` (global)
- `.mcp-vector-search/config.edn` (project-specific)

The server reads the configuration file at startup and indexes all specified sources.

### Basic Configuration

```clojure
{:sources [{:path "/path/to/docs/**/*.md"}]}
```

### Full Configuration Structure

```clojure
{:description "Custom search tool description"
 :watch? true
 :sources [{:path "/docs/**/*.md"
            :name "Documentation"
            :ingest :whole-document
            :watch? true
            :custom-key "custom-value"}]}
```

## Path Specifications

Path specs define which files to index and support two source types:
- **Filesystem sources**: Use `:path` key (absolute paths with leading `/`)
- **Classpath sources**: Use `:class-path` key (relative paths without leading `/`)

All path specs support:
- **Literal paths**: Direct file or directory references
- **Globs**: `*` (single level), `**` (recursive)
- **Named captures**: `(?<name>regex)` - extracted as metadata

### Filesystem vs Classpath Sources

**Filesystem sources** (`:path`):
- Use absolute paths with leading `/`
- Support file watching for automatic re-indexing
- Files are read from the filesystem

**Classpath sources** (`:class-path`):
- Use relative paths without leading `/`
- File watching is not available (classpath resources are read-only)
- Resources are discovered from the classpath (JARs, resource directories)
- Useful when embedding mcp-vector-search as a library

Sources must specify exactly one of `:path` or `:class-path`.

### Filesystem Path Spec Examples

```clojure
;; All markdown files recursively
{:path "/docs/**/*.md"}

;; Single directory level
{:path "/docs/*.md"}

;; Capture directory name as metadata
{:path "/docs/(?<category>[^/]+)/*.md"}

;; Multiple captures
{:path "/(?<project>[^/]+)/(?<version>[^/]+)/**/*.clj"}

;; Literal file
{:path "/docs/README.md"}
```

### Classpath Path Spec Examples

```clojure
;; All markdown files recursively from classpath
{:class-path "docs/**/*.md"}

;; Single directory level
{:class-path "docs/*.md"}

;; Capture directory name as metadata
{:class-path "docs/(?<category>[^/]+)/*.md"}

;; All resources recursively
{:class-path "**/*.md"}

;; Specific resource
{:class-path "docs/README.md"}
```

### Mixed Filesystem and Classpath Configuration

You can combine both filesystem and classpath sources in the same configuration:

```clojure
{:sources [
  ;; Filesystem documentation
  {:path "/Users/me/docs/**/*.md"
   :source "local"}

  ;; Bundled library documentation from classpath
  {:class-path "lib-docs/**/*.md"
   :source "library"}

  ;; Clojure source from classpath
  {:class-path "my/app/**/*.clj"
   :ingest :namespace-doc
   :source "library-code"}]}
```

### Metadata

Metadata comes from two sources:

1. **Base metadata**: Any additional keys in the source map (except `:path`, `:class-path`, `:name`, `:ingest`, `:watch?`)
2. **Captures**: Values extracted from named groups in the path spec

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "my-project"
            :type "documentation"}]}
```

For a file `/docs/api/functions.md`:
- Metadata: `{:project "my-project", :type "documentation", :category "api"}`

The `:name` key, if provided, is also added to metadata.

Classpath sources work identically:

```clojure
{:sources [{:class-path "docs/(?<category>[^/]+)/*.md"
            :project "my-library"
            :type "documentation"}]}
```

For a classpath resource `docs/api/functions.md`:
- Metadata: `{:project "my-library", :type "documentation", :category "api"}`

## Ingest Pipeline Strategies

Ingest pipeline strategies control how documents are processed, embedded, and stored. Set via the `:ingest` key.

### Understanding File-ID vs Segment-ID

The ingestion system uses two levels of identification:

- **File-ID**: Identifies the source file (the file path). Used when updating or removing documents during file watching.
- **Segment-ID**: Identifies individual chunks/segments within a file. A single file can produce multiple segments.

Each segment map contains: `:file-id`, `:segment-id`, `:content`, `:embedding`, and `:metadata`.

### Strategy Architecture

The ingest pipeline separates two concerns:

1. **Embedding strategy** - What content to use for semantic search (what to embed)
2. **Content strategy** - What content to store and return in search results (what to store)

**Note**: The convenience strategies (`:whole-document`, `:namespace-doc`, `:file-path`) are implemented as shortcuts that forward to `:single-segment` with predefined strategy combinations. Use the simpler syntax unless you need custom combinations.

**Convenience syntax** - Use simple `:ingest` values (recommended for most cases):
```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :whole-document}]}
```

**Composable syntax** - Mix and match embedding and content strategies:
```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :single-segment
            :embedding :namespace-doc
            :content-strategy :file-path}]}
```

This searches by namespace documentation but returns only file paths, minimizing memory usage.

### Built-in Ingest Pipeline Strategies

#### `:whole-document` (default)

Embeds and stores the entire file content as a single segment.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :whole-document}]}
```

**Use when**: You want to search across complete documents and return full content.

**Characteristics**:
- One segment per file (1:1 relationship)
- Both embedding and storage use full content
- Simple and straightforward for most use cases

#### `:namespace-doc`

For Clojure source files - embeds only the namespace docstring but stores the full file content.

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :namespace-doc}]}
```

**Use when**: You want to search Clojure namespaces by their documentation while still returning the complete source code.

**Requirements**:
- File must contain a valid `ns` form
- Namespace must have a docstring
- Adds `:namespace` to metadata (e.g., `{:namespace "my.app.core"}`)

**Characteristics**:
- Embedding uses only namespace docstring
- Storage includes full file content
- One segment per file (1:1 relationship)

#### `:file-path`

Embeds the full content but stores only the file path.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :file-path}]}
```

**Use when**:
- You only need to discover which files match a query
- You want to reduce memory usage
- Your client will read file content separately

**Characteristics**:
- Embedding uses full file content
- Storage contains only the file path
- One segment per file (1:1 relationship)
- Reduces memory footprint for large document sets

#### `:chunked`

Splits documents into smaller segments using LangChain4j's recursive text splitter. Enables better semantic search for large documents by creating embeddings for focused chunks rather than entire files.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :chunked
            :chunk-size 512
            :chunk-overlap 100}]}
```

**Use when**: You have large documents and need precise fact-based retrieval where specific information may be buried in lengthy content.

**Configuration**:
- `:chunk-size` - Maximum characters per chunk (default: 512, approximately 128 tokens)
- `:chunk-overlap` - Characters to overlap between chunks (default: 100, approximately 20% overlap)
  - **Note**: LangChain4j's recursive paragraph splitter prioritizes semantic boundaries (paragraph breaks) over exact overlap amounts. Adjacent chunks may have less overlap than specified if splitting at a paragraph boundary. This behavior preserves semantic coherence at the cost of strict overlap guarantees.

**Characteristics**:
- Multiple segments per file (1:N relationship)
- Each chunk is embedded and stored independently
- All chunks from the same file share the same `:doc-id` for batch removal during updates
- Chunk metadata includes: `:chunk-index` (position), `:chunk-count` (total chunks), `:chunk-offset` (character offset)

**Chunk Sizing Guidance**:
- **Smaller chunks (256-512 chars)**: Better for precise fact-based retrieval, specific details
- **Larger chunks (1024+ chars)**: Better for broader context, understanding relationships
- **Overlap (10-20%)**: Recommended to preserve context at chunk boundaries

**Search Result Interpretation**:
- Search may return multiple chunks from the same document
- Each chunk is ranked independently by similarity
- Use `:chunk-index` and `:chunk-count` metadata to understand relative position
- Use `:chunk-offset` to locate chunks in original document
- Consider assembling adjacent chunks for full context

**Example Configurations**:

```clojure
;; Fine-grained retrieval for technical docs
{:sources [{:path "/docs/**/*.md"
            :ingest :chunked
            :chunk-size 384
            :chunk-overlap 75}]}

;; Broader context for narrative content
{:sources [{:path "/articles/**/*.md"
            :ingest :chunked
            :chunk-size 1024
            :chunk-overlap 200}]}

;; Compare whole-document vs chunked
{:sources [
  ;; Small reference docs - whole document works well
  {:path "/api-reference/**/*.md"
   :ingest :whole-document}

  ;; Large guides - chunking improves precision
  {:path "/guides/**/*.md"
   :ingest :chunked
   :chunk-size 512
   :chunk-overlap 100}]}
```

#### `:code-analysis`

Analyzes Clojure and Java source files using clj-kondo to extract code elements (vars, namespaces, classes, methods, fields, macros). Creates one searchable segment per code element with docstrings for semantic search and complete analysis metadata for detailed results.

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis}]}
```

**Use when**: You want to search code by documentation or API discovery, finding functions/classes/methods based on their purpose rather than file names.

**Configuration Options**:

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :visibility :public-only        ; :all (default) | :public-only
            :element-types #{:var :macro}}]} ; optional filter
```

**`:visibility`** - Controls which elements to include based on access level:
- `:all` (default) - Include all elements regardless of visibility
- `:public-only` - Include only public elements
  - Clojure: Excludes vars with `^:private` or `{:private true}` metadata
  - Java: Excludes members with `private` or `protected` access modifiers

**`:element-types`** (optional) - Set of element types to include:
- If omitted: Include all element types
- If provided: Only include specified types
- Valid types: `:var`, `:macro`, `:namespace`, `:class`, `:method`, `:field`, `:constructor`

**Characteristics**:
- Multiple segments per file (1:N relationship, one per code element)
- Embedding uses docstring if present, otherwise element name
- Content stores complete clj-kondo analysis map as EDN string
- Supports both Clojure (.clj, .cljs, .cljc) and Java (.java) files
- All segments share same `:doc-id` (file path) for batch removal during updates

**Segment Metadata**:

Each segment includes these metadata fields:
- `:element-type` - Type of code element ("var", "macro", "namespace", "class", "method", "field", "constructor")
- `:element-name` - Qualified name (e.g., "my.ns/my-fn" or "com.example.MyClass.myMethod")
- `:language` - Source language ("clojure" or "java")
- `:namespace` - Containing namespace (Clojure) or package (Java)
- `:visibility` - Access level ("public", "private", or "protected")
- Plus standard metadata: `:doc-id`, `:file-id`, `:segment-id`
- Plus any path spec captures from configuration

**Example Configurations**:

```clojure
;; Search all code elements in Clojure source
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis}]}

;; Search only public API
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :visibility :public-only}]}

;; Search only vars and macros (exclude namespaces)
{:sources [{:path "/src/**/*.clj"
            :ingest :code-analysis
            :element-types #{:var :macro}}]}

;; Java source code analysis
{:sources [{:path "/src/**/*.java"
            :ingest :code-analysis
            :visibility :public-only}]}

;; Multi-language codebase
{:sources [
  {:path "/src/clj/**/*.clj"
   :ingest :code-analysis
   :language-tag "clojure"}

  {:path "/src/java/**/*.java"
   :ingest :code-analysis
   :language-tag "java"}]}
```

**Search Examples**:

```json
// Find authentication-related functions
{
  "query": "authenticate user credentials",
  "limit": 10,
  "metadata": {
    "element-type": "var",
    "visibility": "public"
  }
}

// Find methods in a specific package (broader match)
{
  "query": "configuration management",
  "metadata": {
    "element-type": "method",
    "namespace": "com.example"
  }
}

// Find a specific method (exact match)
{
  "query": "load configuration",
  "metadata": {
    "element-name": "com.example.ConfigManager.loadConfig"
  }
}

// Find macros in a namespace
{
  "query": "control flow",
  "metadata": {
    "element-type": "macro",
    "namespace": "my.app.macros"
  }
}
```

**Note on Metadata Filtering**: All metadata filters use exact string matching only. Glob patterns like `"com.example.*"` are not supported. For broader matches, filter by `namespace` (package for Java) instead of `element-name`.

**Error Handling**:

Files with clj-kondo analysis errors:
- Successfully analyzed elements are still indexed
- Analysis failures are logged as warnings
- Entire ingestion continues (does not fail)

**Use Cases**:
- **API Discovery**: Find functions/methods by purpose rather than name
- **Code Search**: Semantic search across codebase documentation
- **Refactoring Aid**: Locate related functionality across files
- **Onboarding**: Help new developers discover relevant APIs
- **Documentation**: Enable AI-powered code exploration

#### `:single-segment` - Composable Strategies

Enables composing any embedding strategy with any content strategy for single-segment processing. Requires both `:embedding` and `:content-strategy` configuration keys.

**Configuration**:
```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :single-segment
            :embedding :namespace-doc
            :content-strategy :file-path}]}
```

**Embedding Strategies**:
- `:whole-document` - Embed full file content
- `:namespace-doc` - Embed only namespace docstring (Clojure files)

**Content Strategies**:
- `:whole-document` - Store full file content
- `:file-path` - Store only file path

**Use when**: You want a combination not provided by the convenience strategies (`:whole-document`, `:namespace-doc`, `:file-path`).

**Example Use Cases**:

```clojure
;; Search by namespace docs, return paths only (minimal memory)
{:sources [{:path "/src/**/*.clj"
            :ingest :single-segment
            :embedding :namespace-doc
            :content-strategy :file-path}]}

;; Search full content, return paths only (reduce memory usage)
{:sources [{:path "/docs/**/*.md"
            :ingest :single-segment
            :embedding :whole-document
            :content-strategy :file-path}]}

;; Same as convenience :namespace-doc
{:sources [{:path "/src/**/*.clj"
            :ingest :single-segment
            :embedding :namespace-doc
            :content-strategy :whole-document}]}
```

## File Watching

Enable automatic re-indexing when filesystem files change.

**Note**: File watching is only available for filesystem sources (`:path`). Classpath sources (`:class-path`) are read-only and do not support file watching.

### Global Watch Configuration

```clojure
{:watch? true
 :sources [{:path "/docs/**/*.md"}
           {:path "/src/**/*.clj"}]}
```

All filesystem sources will be watched for changes. Classpath sources automatically skip watching regardless of the `:watch?` setting.

### Per-Source Watch Configuration

```clojure
{:watch? false
 :sources [{:path "/docs/**/*.md"
            :watch? true}
           {:path "/archive/**/*.md"
            :watch? false}
           {:class-path "lib-docs/**/*.md"}]}  ;; Never watched
```

Individual source settings override the global `:watch?` flag for filesystem sources. Classpath sources are never watched.

### Watch Behavior

- **Debouncing**: Events are debounced (500ms) to avoid excessive re-indexing
- **Operations**:
  - File created → index new file
  - File modified → remove old version, re-index
  - File deleted → remove from index
- **Path matching**: Only files matching the path spec pattern are processed
- **Recursive**: Automatically watches subdirectories when using `**` glob
- **Classpath exclusion**: Sources with `:class-path` automatically skip watch setup

## Search Tool

The server provides a `search` tool with the following parameters:

### Parameters

- `query` (required): The search query text
- `limit` (optional): Maximum number of results (default: 10)
- `metadata` (optional): Metadata filters as key-value pairs

### Metadata Filtering

Metadata filters use exact matching and are combined with AND logic:

```json
{
  "query": "authentication",
  "limit": 5,
  "metadata": {
    "category": "api",
    "project": "my-project"
  }
}
```

This returns up to 5 results matching "authentication" where `category` is "api" AND `project` is "my-project".

The search tool dynamically generates JSON schema enums based on metadata values discovered during indexing, so clients can offer valid filter values.

## Complete Configuration Examples

### Documentation Site

```clojure
{:description "Search project documentation"
 :watch? true
 :sources [{:path "/docs/(?<section>[^/]+)/**/*.md"
            :project "myapp"
            :type "docs"}]}
```

### Multi-Project Codebase

```clojure
{:sources [
  {:path "/projects/(?<project>[^/]+)/src/**/*.clj"
   :ingest :namespace-doc
   :type "clojure"}

  {:path "/projects/(?<project>[^/]+)/docs/**/*.md"
   :type "markdown"}]}
```

### Development with Watch

```clojure
{:watch? true
 :sources [
  {:path "/src/**/*.clj"
   :ingest :namespace-doc
   :category "source"}

  {:path "/test/**/*.clj"
   :ingest :namespace-doc
   :category "test"}

  {:path "/doc/**/*.md"
   :category "docs"}]}
```

### Memory-Efficient Path Discovery

```clojure
{:sources [{:path "/large-archive/**/*.txt"
            :ingest :file-path
            :archive "historical"}]}
```

This creates embeddings for semantic search but only stores file paths, reducing memory usage for large document sets.

## Running the Server

The server is typically started automatically by your MCP client. For manual testing:

```bash
clojure -X:mcp-vector-search
```

Or with a custom config path:

```bash
clojure -X:mcp-vector-search :config-path '"/path/to/config.edn"'
```

## Troubleshooting

### No Results Found

- Verify files exist at the specified paths
- Check path spec pattern matches your files
- Try a broader search query
- Check metadata filters aren't too restrictive

### Files Not Being Indexed

- Verify glob patterns match your directory structure
- Check file permissions
- Review server logs for errors during ingestion
- Ensure path uses forward slashes `/` (even on Windows)

### Watch Not Working

- Verify `:watch? true` is set globally or per-source
- Check the base path (literal prefix) exists and is accessible
- On macOS, symlinked paths (like `/var`) are automatically normalized
- Review server logs for watch setup errors

### Namespace Embedding Errors

When using `:ingest :namespace-doc`:
- File must contain a valid `ns` form
- Namespace must have a docstring (first string after namespace symbol)
- Check for syntax errors in the `ns` form

### Memory Issues

For large document sets:
- Use `:ingest :file-path` to reduce stored content
- Reduce the number of indexed files
- Consider splitting into multiple server instances

## Advanced Topics

### Custom Embedding and Content Strategies

You can extend the composable strategy system by implementing custom embedding or content strategies.

**Custom Embedding Strategy** (what to search):
```clojure
(ns mcp-vector-search.ingest.single-segment
  ...)

(defmethod embed-content :custom-embedding
  [_strategy path content metadata]
  ;; Return map with :text (required) and optionally :metadata (enhancements)
  {:text (extract-relevant-text content)
   :metadata {:custom-field "value"}})
```

**Custom Content Strategy** (what to return):
```clojure
(ns mcp-vector-search.ingest.single-segment
  ...)

(defmethod extract-content :custom-content
  [_strategy path content metadata]
  ;; Return string to store
  (format-content-for-storage content))
```

Then use in configuration:
```clojure
{:sources [{:path "/data/**/*.txt"
            :ingest :single-segment
            :embedding :custom-embedding
            :content-strategy :custom-content}]}
```

### Custom Multi-Segment Strategies

For strategies that produce multiple segments per file (like `:chunked`), implement the `process-document` multimethod directly:

```clojure
(ns mcp-vector-search.ingest.common
  ...)

(defmethod process-document :custom-multi-segment
  [_strategy path content metadata]
  ;; Return sequence of segment descriptor maps
  ;; Each must have: :file-id, :segment-id, :text-to-embed, :content-to-store, :metadata
  (let [segments (split-into-segments content)]
    (map-indexed
      (fn [idx segment]
        (create-segment-descriptor
          path
          (generate-segment-id path idx)
          (:text segment)
          (:content segment)
          metadata))
      segments)))
```

**Multi-segment use cases**:
- Split documents by paragraphs or sections
- Extract functions/classes from code files
- Create overlapping chunks with custom logic

Each segment must have a unique `:segment-id` and share the same `:file-id` (the file path).

### Regex Captures

Named captures use standard Java regex syntax:

```clojure
;; Capture version numbers
{:path "/docs/v(?<version>\\d+\\.\\d+)/**/*.md"}

;; Capture until next slash
{:path "/(?<category>[^/]+)/(?<subcategory>[^/]+)/*.md"}

;; Capture with alternatives
{:path "/docs/(?<lang>en|es|fr)/**/*.md"}
```

Note: Backslashes must be escaped in EDN strings (`\\d` not `\d`).

### Document IDs

All embeddings are automatically tagged with a `:doc-id` metadata field containing the file path. This is equivalent to the file-ID used by the pipeline system. This enables:
- Removing documents when files are deleted (watch mode)
- Updating documents when files are modified (watch mode)
- Identifying the source file for search results

When a file is updated or deleted during file watching, all segments with matching `:doc-id` (file-ID) are removed before re-indexing.
