# About mcp-vector-search

## The Problem: Context Limitations

AI coding agents have finite context windows. They can't load:
- Entire codebases
- All documentation
- Historical discussions and decisions
- Related project files
- API references and guides

This forces difficult tradeoffs: what information should be included upfront? What might be relevant later? Agents often work with incomplete information, leading to suboptimal decisions or missed connections.

## The Solution: On-Demand Semantic Search

mcp-vector-search provides AI agents with **cheap, on-demand access** to large knowledge bases through semantic search.

Instead of loading everything into context, agents can:
1. **Search when needed** - retrieve information only when it becomes relevant
2. **Find semantic matches** - discover related content even without exact keywords
3. **Filter by metadata** - narrow searches to specific projects, categories, or types
4. **Preserve context** - the search tool itself doesn't consume context; only results do

## Why This Matters

### More Information Available

Agents can effectively work with knowledge bases far larger than their context windows. Documentation, code, specifications, and historical context become accessible without the upfront cost.

### Better Decisions

With access to more complete information, agents make more informed decisions about architecture, implementation approaches, and problem-solving strategies.

### Discovery Over Prediction

Rather than guessing what might be relevant, agents discover connections through semantic similarity. A question about "authentication" might surface related discussions about "security", "sessions", or "tokens" without explicit keyword matching.

### Just-In-Time Retrieval

Information is retrieved when it's actually needed, not loaded speculatively. This maximizes the effective use of limited context.

## What mcp-vector-search Provides

An MCP server that:
- **Indexes documents** using semantic embeddings (AllMiniLmL6V2 model)
- **Provides a search tool** accessible to AI assistants via the Model Context Protocol
- **Supports flexible configuration** with path specs, globs, captures, and metadata
- **Offers multiple strategies** for embedding and storage
- **Watches files** for automatic reindexing during development
- **Filters by metadata** to constrain searches to relevant subsets

## The MCP Advantage

The Model Context Protocol makes tools available to agents **without consuming context**. The search tool is there when needed, but dormant when not. Only the search results—the information the agent actually requests—enter the context.

This creates a scalable pattern: agents can have access to vast knowledge bases while maintaining focused, efficient context usage.

## Use Cases

### Large Documentation Sets

Make extensive documentation searchable without loading it all upfront. Agents discover relevant sections based on the current task.

### Multi-Project Codebases

Index code across multiple projects. Agents find related implementations, patterns, and examples from the broader codebase.

### Historical Context

Index decision logs, design documents, and past discussions. Agents can discover why things were built a certain way.

### API and Library References

Make reference documentation available on-demand. Agents look up function signatures, usage examples, and best practices as needed.

### Development Workflow

With file watching enabled, the search index stays current as you work. Agents always have access to the latest information.

## Getting Started

See [install.md](install.md) for installation instructions and [using.md](using.md) for configuration and usage details.

The key insight: **expand what's accessible, not what's loaded**. mcp-vector-search gives agents the ability to reach beyond their context limitations and find the information they need, when they need it.
