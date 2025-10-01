An MCP server to index and serve documents based on semantic embedding.

The server uses a configuration specifying the sources to index.

It provides a search tool for searching the indexed sources.

The specification includes concept tags that are included as metadata on
the embedded documents and can be used to constrain searches.

The embeddings are all maintained in memory and are rebuilt on server
start.
