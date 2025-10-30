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
            :pipeline :whole-document
            :watch? true
            :custom-key "custom-value"}]}
```

## Path Specifications

Path specs define which files to index and support:
- **Literal paths**: `/docs/README.md`
- **Globs**: `*` (single level), `**` (recursive)
- **Named captures**: `(?<name>regex)` - extracted as metadata

### Path Spec Examples

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

### Metadata

Metadata comes from two sources:

1. **Base metadata**: Any additional keys in the source map (except `:path`, `:name`, `:pipeline`, `:watch?`)
2. **Captures**: Values extracted from named groups in the path spec

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "my-project"
            :type "documentation"}]}
```

For a file `/docs/api/functions.md`:
- Metadata: `{:project "my-project", :type "documentation", :category "api"}`

The `:name` key, if provided, is also added to metadata.

## Processing Strategies

Processing strategies control how documents are processed, embedded, and stored. Set via the `:pipeline` key.

### Understanding File-ID vs Segment-ID

The ingestion system uses two levels of identification:

- **File-ID**: Identifies the source file (the file path). Used when updating or removing documents during file watching.
- **Segment-ID**: Identifies individual chunks/segments within a file. A single file can produce multiple segments.

Each segment map contains: `:file-id`, `:segment-id`, `:content`, `:embedding`, and `:metadata`.

### Built-in Pipeline Strategies

#### `:whole-document` (default)

Embeds and stores the entire file content as a single segment.

```clojure
{:sources [{:path "/docs/**/*.md"
            :pipeline :whole-document}]}
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
            :pipeline :namespace-doc}]}
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
            :pipeline :file-path}]}
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

## File Watching

Enable automatic re-indexing when files change.

### Global Watch Configuration

```clojure
{:watch? true
 :sources [{:path "/docs/**/*.md"}
           {:path "/src/**/*.clj"}]}
```

All sources will be watched for changes.

### Per-Source Watch Configuration

```clojure
{:watch? false
 :sources [{:path "/docs/**/*.md"
            :watch? true}
           {:path "/archive/**/*.md"
            :watch? false}]}
```

Individual source settings override the global `:watch?` flag.

### Watch Behavior

- **Debouncing**: Events are debounced (500ms) to avoid excessive re-indexing
- **Operations**:
  - File created → index new file
  - File modified → remove old version, re-index
  - File deleted → remove from index
- **Path matching**: Only files matching the path spec pattern are processed
- **Recursive**: Automatically watches subdirectories when using `**` glob

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
   :pipeline :namespace-doc
   :type "clojure"}

  {:path "/projects/(?<project>[^/]+)/docs/**/*.md"
   :type "markdown"}]}
```

### Development with Watch

```clojure
{:watch? true
 :sources [
  {:path "/src/**/*.clj"
   :pipeline :namespace-doc
   :category "source"}

  {:path "/test/**/*.clj"
   :pipeline :namespace-doc
   :category "test"}

  {:path "/doc/**/*.md"
   :category "docs"}]}
```

### Memory-Efficient Path Discovery

```clojure
{:sources [{:path "/large-archive/**/*.txt"
            :pipeline :file-path
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

When using `:pipeline :namespace-doc`:
- File must contain a valid `ns` form
- Namespace must have a docstring (first string after namespace symbol)
- Check for syntax errors in the `ns` form

### Memory Issues

For large document sets:
- Use `:pipeline :file-path` to reduce stored content
- Reduce the number of indexed files
- Consider splitting into multiple server instances

## Advanced Topics

### Custom Pipeline Strategies

To add custom processing strategies, implement the `process-document` multimethod in your fork:

```clojure
(defmethod mcp-vector-search.ingest/process-document :custom-strategy
  [_strategy embedding-model embedding-store path content metadata]
  ;; Process content and return sequence of segment maps
  ;; Each segment map must have: :file-id, :segment-id, :content, :embedding, :metadata
  (let [file-id path
        segment-id (mcp-vector-search.ingest/generate-segment-id file-id)
        ;; Your custom processing logic here
        ...]
    [...]))  ; Return sequence of segment maps
```

**Multi-segment strategies**: Custom strategies can produce multiple segments per file (1:N relationship). For example, you might:
- Split documents by paragraphs or sections
- Extract functions/classes from code files
- Create both whole-document and chunked embeddings

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

## Migration Guide

If you have an existing configuration using the deprecated `:embedding` and `:ingest` keys, migrate to the `:pipeline` key.

### Old Configuration Format (Deprecated)

```clojure
{:sources [
  {:path "/docs/**/*.md"
   :embedding :whole-document
   :ingest :whole-document}

  {:path "/src/**/*.clj"
   :embedding :namespace-doc
   :ingest :whole-document}

  {:path "/archive/**/*.txt"
   :embedding :whole-document
   :ingest :file-path}]}
```

### New Configuration Format

```clojure
{:sources [
  {:path "/docs/**/*.md"
   :pipeline :whole-document}

  {:path "/src/**/*.clj"
   :pipeline :namespace-doc}

  {:path "/archive/**/*.txt"
   :pipeline :file-path}]}
```

### Migration Rules

- **`:embedding :whole-document` + `:ingest :whole-document`** → `:pipeline :whole-document`
- **`:embedding :namespace-doc` + `:ingest :whole-document`** → `:pipeline :namespace-doc`
- **`:embedding :whole-document` + `:ingest :file-path`** → `:pipeline :file-path`

The `:pipeline` key consolidates both embedding and ingestion strategies into a single unified pipeline concept.
