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
            :embedding :whole-document
            :ingest :whole-document
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

1. **Base metadata**: Any additional keys in the source map (except `:path`, `:name`, `:embedding`, `:ingest`, `:watch?`)
2. **Captures**: Values extracted from named groups in the path spec

```clojure
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "my-project"
            :type "documentation"}]}
```

For a file `/docs/api/functions.md`:
- Metadata: `{:project "my-project", :type "documentation", :category "api"}`

The `:name` key, if provided, is also added to metadata.

## Embedding Strategies

Embedding strategies control how document content is converted to embeddings.

### `:whole-document` (default)

Embeds the entire file content as a single segment.

```clojure
{:sources [{:path "/docs/**/*.md"
            :embedding :whole-document}]}
```

**Use when**: You want to search across complete documents.

### `:namespace-doc`

For Clojure source files - embeds only the namespace docstring but stores the full file content.

```clojure
{:sources [{:path "/src/**/*.clj"
            :embedding :namespace-doc}]}
```

**Use when**: You want to search Clojure namespaces by their documentation while still returning the complete source code.

**Requirements**:
- File must contain a valid `ns` form
- Namespace must have a docstring
- Adds `:namespace` to metadata (e.g., `{:namespace "my.app.core"}`)

## Ingest Strategies

Ingest strategies control what content is stored in the vector database alongside embeddings.

### `:whole-document` (default)

Stores the complete file content.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :whole-document}]}
```

**Use when**: You want search results to return the full content.

### `:file-path`

Stores only the file path, not the content.

```clojure
{:sources [{:path "/docs/**/*.md"
            :ingest :file-path}]}
```

**Use when**:
- You only need to discover which files match a query
- You want to reduce memory usage
- Your client will read file content separately

The embedding is still based on the full content (or based on the embedding strategy), but search results only contain the file path.

## Strategy Combinations

You can mix embedding and ingest strategies:

```clojure
{:sources [
  ;; Search by namespace doc, return full source
  {:path "/src/**/*.clj"
   :embedding :namespace-doc
   :ingest :whole-document}

  ;; Search by full content, return only paths
  {:path "/docs/**/*.md"
   :embedding :whole-document
   :ingest :file-path}]}
```

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
   :embedding :namespace-doc
   :type "clojure"}

  {:path "/projects/(?<project>[^/]+)/docs/**/*.md"
   :type "markdown"}]}
```

### Development with Watch

```clojure
{:watch? true
 :sources [
  {:path "/src/**/*.clj"
   :embedding :namespace-doc
   :category "source"}

  {:path "/test/**/*.clj"
   :embedding :namespace-doc
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

When using `:embedding :namespace-doc`:
- File must contain a valid `ns` form
- Namespace must have a docstring (first string after namespace symbol)
- Check for syntax errors in the `ns` form

### Memory Issues

For large document sets:
- Use `:ingest :file-path` to reduce stored content
- Reduce the number of indexed files
- Consider splitting into multiple server instances

## Advanced Topics

### Custom Strategies

To add custom embedding or ingest strategies, implement the multimethods in your fork:

```clojure
(defmethod mcp-vector-search.ingest/embed-content :custom-strategy
  [_strategy embedding-model content metadata path]
  ;; Return {:embedding-response ... :segment ... :metadata ...}
  )

(defmethod mcp-vector-search.ingest/ingest-segments :custom-strategy
  [_strategy embedding-store embedding-result]
  ;; Store embeddings, return nil
  )
```

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

All embeddings are automatically tagged with a `:doc-id` metadata field containing the file path. This enables:
- Removing documents when files are deleted (watch mode)
- Updating documents when files are modified (watch mode)
- Identifying the source file for search results
