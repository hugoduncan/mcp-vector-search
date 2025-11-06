# mcp-vector-search Claude Marketplace

This directory contains Claude Code plugins for mcp-vector-search.

## Plugin Structure

```
.claude-plugin/
├── marketplace.json                          # Marketplace metadata
├── plugins/
│   ├── config-guide/
│   │   ├── .claude-plugin/plugin.json       # Plugin metadata
│   │   └── skills/
│   │       └── config-guide/
│   │           └── SKILL.md                 # Configuration guide skill
│   └── embed-guide/
│       ├── .claude-plugin/plugin.json       # Plugin metadata
│       └── skills/
│           └── embed-guide/
│               └── SKILL.md                 # Library integration guide skill
└── README.md                                # This file
```

## Plugins

### config-guide
Guides users through writing `.mcp-vector-search/config.edn` configuration files. Covers path specifications, ingest strategies, metadata extraction, and file watching.

**Usage**: Type `/config-guide` in Claude Code

### embed-guide
Guides MCP server developers through embedding mcp-vector-search as a library. Covers classpath vs filesystem sources, resource bundling, and JAR packaging.

**Usage**: Type `/embed-guide` in Claude Code

## Documentation Sharing

**Note on documentation organization**: While Claude Code skills support additional files (like `reference.md`, `examples.md`) within each skill directory, there is no mechanism for sharing documentation files between skills or referencing project documentation directly from `doc/`.

As a result:
- Each skill contains complete inline documentation
- Content is duplicated from `doc/using.md` and `doc/library-usage.md`
- Skills are self-contained and can be used independently
- Documentation updates must be applied to both project docs and skill files

This approach ensures skills work correctly while accepting the trade-off of content duplication.

## Testing

No unit tests are provided for the plugins as they contain documentation skills that are self-documenting and have no code logic to test.
