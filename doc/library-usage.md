# Using mcp-vector-search as a Library

This guide explains how to embed mcp-vector-search within your own MCP server to provide semantic search capabilities over bundled documentation or resources.

## Overview

mcp-vector-search can be used as a library to add semantic search to custom MCP servers. This is useful when:
- You want to bundle documentation with your MCP server
- You need semantic search over application-specific knowledge bases
- You want to combine vector search with your own custom tools

## Adding the Dependency

Add mcp-vector-search to your `deps.edn`:

```clojure
{:deps
 {org.hugoduncan/mcp-vector-search
  {:git/url "https://github.com/hugoduncan/mcp-vector-search"
   :git/sha "LATEST_SHA_HERE"}}}
```

**Note**: Replace `LATEST_SHA_HERE` with the latest commit SHA from the repository.

## Bundling Resources

### 1. Create Resource Directory

Place your documentation in the `resources` directory of your project:

```
my-mcp-server/
├── resources/
│   └── .mcp-vector-search/
│       └── config.edn          # Configuration for bundled resources
│   └── docs/
│       ├── guide.md
│       ├── api/
│       │   └── reference.md
│       └── examples/
│           └── usage.md
├── src/
│   └── my_server/
│       └── main.clj
└── deps.edn
```

### 2. Configure Classpath Sources

Create `resources/.mcp-vector-search/config.edn` with classpath sources:

```clojure
{:description "Search my-server documentation"
 :sources [{:class-path "docs/**/*.md"
            :name "Documentation"
            :type "docs"}]}
```

**Key points**:
- Use `:class-path` instead of `:path`
- Paths are relative (no leading `/`)
- Resources will be bundled in the JAR when you package your server

### 3. Start the Server from Your Code

In your MCP server's main namespace:

```clojure
(ns my-server.main
  (:require
    [mcp-vector-search.main :as vector-search]))

(defn -main []
  ;; Start vector search with classpath config
  ;; The config at resources/.mcp-vector-search/config.edn
  ;; will be found automatically on the classpath
  (vector-search/start)

  ;; Your server code here
  ...)
```

The server will:
1. Search for `.mcp-vector-search/config.edn` on the classpath
2. Discover and index resources from `:class-path` sources
3. Provide the search tool via MCP

## Configuration Loading Order

When using the library, configuration is loaded from:
1. **Classpath**: `resources/.mcp-vector-search/config.edn` (bundled)
2. **Project directory**: `./.mcp-vector-search/config.edn` (runtime override)
3. **Home directory**: `~/.mcp-vector-search/config.edn` (user-specific)

The first location found is used. This allows:
- Bundling default configuration in your JAR
- Providing project-specific overrides during development
- User customization via home directory

## Example Configurations

### Bundled API Documentation

```clojure
;; resources/.mcp-vector-search/config.edn
{:description "Search API documentation and examples"
 :sources [{:class-path "api-docs/**/*.md"
            :category "api"}
           {:class-path "examples/**/*.clj"
            :ingest :namespace-doc
            :category "examples"}]}
```

### Mixed Classpath and Filesystem

You can combine bundled resources with filesystem sources:

```clojure
{:description "Search bundled docs and local project files"
 :sources [
   ;; Bundled documentation (from JAR)
   {:class-path "lib-docs/**/*.md"
    :source "library"
    :type "docs"}

   ;; Local project documentation (filesystem)
   {:path "/Users/me/project/docs/**/*.md"
    :source "project"
    :type "docs"}

   ;; Local source code (filesystem)
   {:path "/Users/me/project/src/**/*.clj"
    :ingest :namespace-doc
    :source "project"
    :type "source"}]}
```

### Multi-Language Documentation

```clojure
{:description "Search multi-language documentation"
 :sources [{:class-path "docs/(?<lang>en|es|fr)/**/*.md"
            :type "documentation"}]}
```

For resource `docs/en/guide.md`:
- Metadata: `{:type "documentation", :lang "en"}`

## Packaging

When you build a JAR of your MCP server, resources are automatically included:

```bash
# Build your server JAR
clojure -T:build uber

# The resulting JAR contains:
# - Your server code
# - mcp-vector-search library
# - All resources (docs, config)
```

Users can run your server without needing to configure vector search separately - the documentation is bundled and indexed automatically.

## Path Spec Syntax

Classpath sources use the same path spec syntax as filesystem sources, but without leading `/`:

```clojure
;; Filesystem (absolute)
{:path "/docs/**/*.md"}

;; Classpath (relative)
{:class-path "docs/**/*.md"}
```

All features work identically:
- **Globs**: `*` (single level), `**` (recursive)
- **Named captures**: `(?<name>regex)` for metadata extraction
- **Literals**: Direct resource paths

See [path-spec.md](path-spec.md) for complete path specification syntax.

## Limitations

### No File Watching

Classpath sources do not support file watching:
- Resources in JARs are read-only
- Classpath resources are indexed once at startup
- The `:watch?` flag has no effect on `:class-path` sources

For development with live reloading, use filesystem sources instead:

```clojure
;; Development config (local file)
{:watch? true
 :sources [{:path "/Users/me/project/resources/docs/**/*.md"}]}

;; Production config (bundled)
{:sources [{:class-path "docs/**/*.md"}]}
```

### Resource Discovery Performance

Classpath resource discovery walks the entire classpath looking for resources. For large classpaths:
- Use specific base paths to limit scope (e.g., `docs/**/*.md` not `**/*.md`)
- Consider excluding patterns if needed
- Initial indexing may take longer than filesystem sources

## Troubleshooting

### Configuration Not Found

If the server can't find your config:

```
No configuration file found
```

Check:
1. File is in `resources/.mcp-vector-search/config.edn`
2. `resources/` is on the classpath (should be automatic in standard Clojure projects)
3. File is valid EDN syntax

### Resources Not Indexed

If resources aren't being indexed:

```
No documents indexed from classpath sources
```

Check:
1. Resources exist in `resources/` directory
2. Path patterns match your directory structure
3. No leading `/` in `:class-path` values
4. Resources are included in the JAR (for packaged servers)

Verify resources are on classpath:

```clojure
(require '[clojure.java.io :as io])
(io/resource "docs/guide.md")  ;; Should return URL
```

### JAR Build Issues

If resources are missing from the JAR:

1. Ensure `resources/` is in `:paths` in `deps.edn`:

```clojure
{:paths ["src" "resources"]
 :deps {...}}
```

2. Verify the JAR contains resources:

```bash
jar tf my-server.jar | grep docs
```

## Complete Example

Here's a complete example of an MCP server with bundled documentation:

### Directory Structure

```
my-mcp-server/
├── resources/
│   ├── .mcp-vector-search/
│   │   └── config.edn
│   └── docs/
│       ├── README.md
│       └── api/
│           └── reference.md
├── src/
│   └── my_server/
│       └── main.clj
├── deps.edn
└── build.clj
```

### Configuration

```clojure
;; resources/.mcp-vector-search/config.edn
{:description "Search my-server documentation"
 :sources [{:class-path "docs/**/*.md"
            :project "my-server"
            :type "documentation"}]}
```

### Main Server Code

```clojure
;; src/my_server/main.clj
(ns my-server.main
  (:require
    [mcp-vector-search.main :as vector-search]
    [mcp-vector-search.server :as mcp-server]))

(defn -main []
  ;; Start vector search (loads classpath config automatically)
  (vector-search/start)

  ;; Add your custom tools here
  ;; ...

  ;; Start MCP server
  (mcp-server/start!))
```

### Dependencies

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.3"}
        org.hugoduncan/mcp-vector-search
        {:git/url "https://github.com/hugoduncan/mcp-vector-search"
         :git/sha "LATEST_SHA_HERE"}}
 :aliases
 {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.9.6"}}
          :ns-default build}}}
```

### Build Configuration

```clojure
;; build.clj
(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/my-server.jar")

(defn uber [_]
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis (b/create-basis)
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (b/create-basis)
           :main 'my-server.main}))
```

### Building and Running

```bash
# Build the JAR
clojure -T:build uber

# Run the server
java -jar target/my-server.jar
```

Users can now run your MCP server with semantic search over your bundled documentation, without any configuration needed.

## See Also

- **[using.md](using.md)** - Complete configuration reference for all strategies and options
- **[path-spec.md](path-spec.md)** - Formal syntax for path specifications and captures
- **[install.md](install.md)** - Installation instructions for MCP clients
- **[about.md](about.md)** - Project purpose and use cases
- **[../README.md](../README.md)** - Quick start guide
