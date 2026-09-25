# Sample data

The sample catalog is available as two independent demos. They describe the same fictional
commerce and payments landscape with vocabulary suited to each catalog paradigm.

- [Backstage software catalog](backstage/commerce-payments/README.md) — one 34-document
  `catalog-info.yaml` covering Backstage's seven fixed kinds.
- [Port ontology](port/commerce-payments/README.md) — fifteen blueprint definitions and 76
  entity instances, including computed properties and parallel `composition` and `deployment`
  hierarchies, loaded linked to their public raw-GitHub source references by default.

Neither demo is seeded by a migration. Start the stack, choose one of the guides above, and load
only the model you want to explore. Loading one demo does not require loading the other.

## AI agents over MCP

An agent (Claude Code, Claude Desktop, any MCP client) can read and — with a write-scope key —
write the Port ontology through `POST /integration/mcp`. See [mcp/README.md](mcp/README.md) for the
key, the client config and a first-session recipe.
