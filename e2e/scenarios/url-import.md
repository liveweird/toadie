# Import from a URL (the SSRF guard through the UI)

- **Spec**: [tests/url-import.spec.ts](../tests/url-import.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (shared
  workspace)
- **Owns** (exclusive server-side state): nothing — the scenario never stores anything; the
  fetch is refused before any content exists

## Scenario: fetching a private URL is refused with the public-https message

1. The admin signs in and opens the import page (`/catalog-files/import`).
2. They paste `https://127.0.0.1/catalog-info.yaml` into the **Fetch from URL** field and
   click **Fetch**.
   - *Expected*: the server's SSRF guard answers a uniform `400`; the page shows the fixed
     "must be a public https address" error, and the YAML textarea stays empty — the flow
     stops at the guard.

## Not covered here (and why)

- **The happy network path (fetching a real raw.githubusercontent.com file)** — deliberately
  not e2e: the suite must run without external network. The response-handling logic (200
  body, 404/redirect/oversize → 502) is covered by the server suite (`UrlFetchTest`) against
  a local fixture server, and the page's fetch→textarea wiring by
  `ImportCatalogFiles.test.tsx`.
- **The guard matrix (schemes, userinfo, private ranges, unresolvable hosts) and the
  `catalog_file.fetch_blocked` audit** — server-tested exhaustively (`UrlFetchTest`).
- **GitHub/GitLab blob-link normalization** — pure client logic, unit-tested in
  `catalogImport.test.ts`.
