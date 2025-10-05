---
description: Update README following modern best practices for developer-focused documentation
---

Rewrite @README.md following README best practices to help users
understand and use mcp-vector-search within 5 minutes.

## Structure Requirements

**Above the fold** (first screen):
- Clear project title and one-line description
- Quick explanation: semantic search for your documents
- Minimal config example (copy-pasteable)
- Link to key sections

**Essential sections in order**:
1. **Quick Start** - Basic config + how to use search tool in Claude Code
2. **What & Why** - What mcp-vector-search does, key benefits, when to use it (link to @doc/about.md for details)
3. **Installation** - MCP server setup for Claude Code
4. **Basic Configuration** - Simple config examples with path specs
5. **Using the Search Tool** - How to search from Claude Code
6. **Advanced Features** - Embedding strategies, ingest strategies, file watching, metadata
7. **Development** - Setup, testing, contribution basics

## Content Guidelines

**Front-load critical info**:
- Lead with config example + search usage
- Show realistic use cases: documentation search, code discovery, knowledge bases
- Include example search queries and expected results

**Make it scannable**:
- Use clear headers and bullet points
- Keep paragraphs short (2-3 sentences max)
- Add table of contents

**Config examples must**:
- Be copy-pasteable and tested
- Show complete working configurations
- Start simple (basic path), then show advanced (captures, strategies)
- Include both `.mcp-vector-search/config.edn` location and example content

**Search examples must**:
- Show realistic queries
- Demonstrate metadata filtering
- Show different use cases (find docs, find code, etc.)

**Advanced features**:
- Briefly introduce embedding strategies (:whole-document, :namespace-doc)
- Briefly introduce ingest strategies (:whole-document, :file-path)
- Mention file watching
- Link to @doc/using.md for comprehensive details
- Link to @doc/path-spec.md for path syntax reference

## Remove/Minimize

- Detailed path spec syntax (link to @doc/path-spec.md)
- Every configuration option (show common cases, link to @doc/using.md)
- Lengthy strategy explanations (show basic examples, link to @doc/using.md)
- Implementation details (brief overview only)

## Link to extensive documentation

Reference these docs for detailed information:
- @doc/about.md - Project purpose and benefits
- @doc/install.md - Detailed installation instructions
- @doc/using.md - Configuration, strategies, and file watching
- @doc/path-spec.md - Formal path specification syntax

Verify all code examples work with current codebase. Test configuration
examples and installation instructions. Ensure the README helps users
succeed quickly rather than explaining everything comprehensively.
