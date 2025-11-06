# mcp-vector-search Marketplace

This repository includes a Claude Code marketplace that provides plugins to help users configure and integrate mcp-vector-search.

## Marketplace Structure

```
.claude-plugin/
  marketplace.json        # Marketplace catalog and metadata
  plugins/
    config-assistant/     # Configuration guidance plugin
    embed-assistant/      # Library integration guidance plugin
```

## Available Plugins

### config-assistant

Guides users through creating and configuring `.mcp-vector-search/config.edn` files.

**Features:**
- Source configuration with path patterns and glob syntax
- Ingest strategy selection (`:whole-document`, `:namespace-doc`, `:code-analysis`, `:chunked`, `:file-path`)
- Metadata extraction using named captures and custom keys
- File watching configuration
- Validation and troubleshooting assistance

**References:** `doc/using.md`, `doc/path-spec.md`

### embed-assistant

Guides MCP server developers through integrating mcp-vector-search as a library.

**Features:**
- Adding deps.edn dependency
- Configuring classpath vs filesystem sources
- Resource bundling and directory structure
- Configuration loading strategies
- JAR packaging considerations

**References:** `doc/library-usage.md`

## Installing the Marketplace

To use these plugins:

```bash
# Clone or add the marketplace repository
/plugin marketplace add hugoduncan/mcp-vector-search

# Install specific plugins
/plugin install config-assistant@mcp-vector-search-plugins
/plugin install embed-assistant@mcp-vector-search-plugins
```

## Marketplace Metadata

- **Name:** mcp-vector-search-plugins
- **Version:** 1.0.0
- **Owner:** Hugo Duncan
- **Description:** Claude Code plugins for mcp-vector-search configuration and library integration

## Development

Plugins are defined in the `.claude-plugin/plugins/` directory. Each plugin follows the standard Claude Code plugin structure with:
- `.claude-plugin/plugin.json` - Plugin metadata
- `skills/` - Skill definition files

The marketplace catalog is defined in `.claude-plugin/marketplace.json`.

## See Also

- [Claude Code Marketplace Documentation](https://docs.claude.com/en/docs/claude-code/plugin-marketplaces)
- [Configuration Guide](using.md)
- [Library Usage Guide](library-usage.md)
- [Path Specification Syntax](path-spec.md)
