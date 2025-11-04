# mcp-vector-search

**Semantic search for your documents, available to AI agents via MCP.**

Index documentation, code, and knowledge bases with semantic embeddings. AI assistants can search on-demand without loading everything into context.

```clojure
;; .mcp-vector-search/config.edn
{:sources [{:path "/docs/**/*.md"}]}
```

```
# Search from Claude Code
"Find authentication documentation"
→ Returns relevant docs based on semantic similarity
```

**Quick Links**: [Quick Start](#quick-start) · [Installation](#installation) · [What & Why](#what--why) · [Advanced Features](#advanced-features)

---

## Table of Contents

- [Quick Start](#quick-start)
- [What & Why](#what--why)
- [Installation](#installation)
- [Basic Configuration](#basic-configuration)
- [Using the Search Tool](#using-the-search-tool)
- [Claude Code Plugins](#claude-code-plugins)
- [Advanced Features](#advanced-features)
- [Development](#development)
- [Documentation](#documentation)

## Quick Start

**1. Install the MCP server** (requires Clojure CLI tools):

```bash
# Add to ~/.clojure/deps.edn
{:aliases
 {:mcp-vector-search
  {:replace-paths []
   :replace-deps {org.hugoduncan/mcp-vector-search
                  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
                   :git/sha "LATEST_SHA_HERE"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
   :exec-fn mcp-vector-search.main/start}}}

# Configure Claude Code
claude mcp add mcp-vector-search -- $(which clojure) -X:mcp-vector-search
```

**2. Create config file** at `~/.mcp-vector-search/config.edn` or `.mcp-vector-search/config.edn`:

```clojure
{:sources [{:path "/path/to/your/docs/**/*.md"}]}
```

**3. Restart Claude Code** - the search tool is now available.

**4. Search from Claude Code**:

```
"Search for authentication best practices"
"Find code examples for user validation"
"Show me documentation about API rate limiting"
```

The assistant will use the search tool to find relevant content semantically, without you loading all documents into context.

## What & Why

### What It Does

mcp-vector-search is an MCP server that:
- **Indexes documents** using semantic embeddings (AllMiniLmL6V2 model)
- **Provides a search tool** to AI assistants via the Model Context Protocol
- **Enables semantic search** - find content by meaning, not just keywords
- **Filters by metadata** - narrow searches to specific projects, categories, or file types
- **Works as a library** - embed in your own MCP servers with bundled documentation

### Why Use It

**Expand what's accessible, not what's loaded.** AI agents have limited context windows. Instead of loading all documentation upfront, they search when needed.

**Key benefits**:
- Work with knowledge bases larger than context windows
- Discover related content through semantic similarity
- Retrieve information just-in-time, not speculatively
- The search tool doesn't consume context - only results do

**When to use**:
- Large documentation sets (hundreds of pages)
- Multi-project codebases (find patterns across projects)
- Historical context (design docs, decision logs)
- API references (look up functions and examples as needed)
- Custom MCP servers needing semantic search over bundled documentation

See [doc/about.md](doc/about.md) for a detailed explanation of the problem and solution.

**Library usage**: mcp-vector-search can be embedded in your own MCP servers to provide semantic search over bundled resources. See [doc/library-usage.md](doc/library-usage.md) for details.

## Installation

### Prerequisites

- Clojure CLI tools
- Java 11+ (required for embedding model)

### Configure Clojure Alias

Add to `~/.clojure/deps.edn`:

```clojure
{:aliases
 {:mcp-vector-search
  {:replace-paths []
   :replace-deps {org.hugoduncan/mcp-vector-search
                  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
                   :git/sha "LATEST_SHA_HERE"}
                  org.clojure/clojure {:mvn/version "1.12.3"}}
   :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
   :exec-fn mcp-vector-search.main/start}}}
```

**Note**: Replace `LATEST_SHA_HERE` with the latest commit SHA from this repository.

### Add to MCP Client

**Claude Code:**
```bash
claude mcp add mcp-vector-search -- $(which clojure) -X:mcp-vector-search
```

**Claude Desktop, Cline, and other MCP clients:** See [doc/install.md](doc/install.md) for setup instructions and troubleshooting.

## Basic Configuration

Configuration file location (first found is used):
- `.mcp-vector-search/config.edn` (project-specific)
- `~/.mcp-vector-search/config.edn` (global)

### Minimal Example

```clojure
{:sources [{:path "/docs/**/*.md"}]}
```

Indexes all markdown files recursively under `/docs/`.

### Path Patterns and Metadata

Path specs support globs (`*`, `**`), regex captures, and custom metadata:

```clojure
;; Basic: recursive glob
{:sources [{:path "/docs/**/*.md"}]}

;; With capture and metadata
{:sources [{:path "/docs/(?<category>[^/]+)/*.md"
            :project "myapp"
            :type "documentation"}]}
;; File /docs/api/auth.md gets metadata:
;; {:project "myapp", :type "documentation", :category "api"}
```

Any config keys except `:path`, `:name`, `:ingest`, and `:watch?` become metadata. See [doc/path-spec.md](doc/path-spec.md) and [doc/using.md](doc/using.md) for full syntax and examples.

## Using the Search Tool

Simply ask the AI assistant to find information:

```
"Find documentation about rate limiting"
"Search for API documentation in the admin category"
"Show me code examples of input validation"
```

The assistant uses semantic search automatically. Searches can filter by metadata (e.g., category, project, type).

**Tool parameters:**
- `query` (required): Search text
- `limit` (optional): Max results (default: 10)
- `metadata` (optional): Filter by key-value pairs (AND logic)

## Claude Code Plugins

Two Claude Code skills are available to guide configuration and library integration:

### Installing Plugins

Add the mcp-vector-search marketplace to Claude Code:

```bash
/plugin marketplace add hugoduncan/mcp-vector-search
```

Then install both plugins:

```bash
/plugin install config-guide@mcp-vector-search-plugins
/plugin install embed-guide@mcp-vector-search-plugins
```

Or install the marketplace from a local clone:

```bash
# From the mcp-vector-search directory
/plugin marketplace add ./
```

### Available Plugins

**config-guide** - Interactive guide for writing `.mcp-vector-search/config.edn` files:
- Path specifications (filesystem/classpath, globs, captures)
- Ingest strategies (whole-document, namespace-doc, code-analysis, chunked, file-path)
- Metadata extraction
- File watching setup

Usage: `/config-guide` in Claude Code

**embed-guide** - Interactive guide for embedding mcp-vector-search as a library:
- Adding dependencies
- Resource bundling
- Classpath vs filesystem sources
- JAR packaging with build scripts

Usage: `/embed-guide` in Claude Code

Both skills provide comprehensive, interactive guidance with examples and best practices.

## Advanced Features

### Ingest Strategies

Control how documents are processed, embedded, and stored:

- **`:whole-document`** (default) - Embeds and stores complete file content
- **`:namespace-doc`** - Embeds Clojure namespace docstrings, returns full source
- **`:file-path`** - Embeds content but stores only the file path (saves memory)
- **`:code-analysis`** - Analyzes code structure, creates segments per element (functions, classes, etc.)
- **`:chunked`** - Splits large documents into smaller segments

```clojure
{:sources [{:path "/src/**/*.clj"
            :ingest :namespace-doc}]}
```

### File Watching

Auto-reindex when files change:

```clojure
{:watch? true
 :sources [{:path "/docs/**/*.md"}]}
```

### Custom Tool Description

Customize the search tool description for AI assistants:

```clojure
{:description "Search internal API documentation and code examples"
 :sources [{:path "/docs/**/*.md"}]}
```

**For complete configuration details**, including strategy options, advanced path patterns, metadata filtering, and troubleshooting, see [doc/using.md](doc/using.md).

## Development

### Setup

```bash
git clone https://github.com/hugoduncan/mcp-vector-search
cd mcp-vector-search
```

### Running Locally

```bash
# Start server with default config (~/.mcp-vector-search/config.edn)
clojure -X:run

# Use custom config
clojure -X:run :config-path '"/path/to/config.edn"'
```

### Testing

```bash
# Run all tests
clojure -M:test

# Run specific namespace
clojure -M:test --focus mcp-vector-search.config-test
```

### REPL Development

```clojure
(require 'clojure.repl.deps)
(clojure.repl.deps/sync-deps)
```

### Project Structure

- `main.clj` - Server startup and coordination
- `config.clj` - Configuration parsing and path spec handling
- `ingest.clj` - File matching, embedding, and storage (with strategy multimethods)
- `tools.clj` - MCP search tool definition
- `lifecycle.clj` - System lifecycle management
- `server.clj` - MCP protocol implementation

### Contributing

Contributions welcome! Please:
1. Run tests before submitting PRs
2. Follow existing code style
3. Add tests for new features
4. Update documentation

## Documentation

- **[doc/about.md](doc/about.md)** - Project purpose, problem/solution, use cases
- **[doc/install.md](doc/install.md)** - Detailed installation for various MCP clients
- **[doc/using.md](doc/using.md)** - Complete configuration reference, strategies, troubleshooting
- **[doc/library-usage.md](doc/library-usage.md)** - Using mcp-vector-search as a library in custom MCP servers
- **[doc/path-spec.md](doc/path-spec.md)** - Formal path specification syntax and grammar
- **[CLAUDE.md](CLAUDE.md)** - Developer/AI assistant technical reference with architecture details

## License

Copyright © 2025 Hugo Duncan

Distributed under the Eclipse Public License 2.0.
