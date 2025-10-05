# Installing mcp-vector-search MCP Server

This guide describes how to install the mcp-vector-search MCP server for use with various MCP clients.

## Prerequisites

- Clojure CLI tools installed
- Java 11 or later (required for LangChain4j embedding model)

## Setup

### Configure Global Clojure Alias

Add the following alias to your `~/.clojure/deps.edn` file:

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

**Note:** Replace `LATEST_SHA_HERE` with the latest commit SHA from the repository.

## Client Configuration

### Claude Code

Add the mcp-vector-search server using the Claude Code CLI:

```bash
claude mcp add mcp-vector-search -- $(which clojure) -X:mcp-vector-search
```

### Claude Desktop

Add to your Claude Desktop MCP settings:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

**Linux:** `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "mcp-vector-search": {
      "command": "clojure",
      "args": ["-X:mcp-vector-search"]
    }
  }
}
```

### Codex

Add to your Codex MCP configuration file (location varies by installation):

```json
{
  "mcpServers": {
    "mcp-vector-search": {
      "command": "clojure",
      "args": ["-X:mcp-vector-search"]
    }
  }
}
```

## Configuration

Before starting the server, you need to create a configuration file at `.mcp-vector-search/config.edn` in your home directory or project directory. See [using.md](using.md) for detailed configuration instructions.

Minimal example:

```clojure
{:sources [{:path "/path/to/docs/**/*.md"}]}
```

## Verification

After configuration, restart your MCP client. The mcp-vector-search server should be available with a search tool for querying indexed documents.

You can verify the installation by:
1. Checking that the server appears in your client's MCP server list
2. Looking for the search tool in available tools
3. Running a simple search query against your indexed documents

## Troubleshooting

- **Server fails to start:** Verify that the `:git/sha` in `~/.clojure/deps.edn` is correct and accessible
- **Dependencies missing:** Ensure you have Clojure 1.12.3 or compatible version installed
- **Git access issues:** Make sure you can access GitHub and clone repositories
- **Native access errors:** Ensure the `--enable-native-access=ALL-UNNAMED` JVM flag is included in the alias
- **No documents indexed:** Check that the config file exists at `.mcp-vector-search/config.edn` and that the paths in `:sources` point to valid files
- **Java version issues:** The embedding model requires Java 11 or later
