# Integration clients and GraphQL key lifecycle

- **Spec**: [tests/integration-clients.spec.ts](../tests/integration-clients.spec.ts)
- **Actors**: the seed administrator and one disposable regular user
- **Owns**: one uniquely named integration client, revoked before completion, and one disposable
  user, deleted before completion; seeded accounts and ontology data are read only

## Scenario: admin creates a client, its key reads GraphQL, non-admin access is hidden, and revoke rejects the key

1. The administrator creates a disposable regular user, then opens **Integration clients**,
   creates a uniquely named client, and chooses the **Write** scope in the create form's Scope
   select before submitting.
   - *Expected*: the create response is `201` and its client carries `scope: "write"`; the
     returned key has the `toadie_int_` prefix and the one-time panel reveals that same key only
     after **Show password** is used.
   - At a 390px viewport, the full key wraps inside its display and both reveal and copy
     controls remain in the viewport.
2. The journey sends `query { blueprints { items { identifier } } }` to the GraphQL endpoint with
   the new integration key.
   - *Expected*: the response is `200`, has no GraphQL errors, and returns the paged blueprint
     items collection. Existing ontology data is not changed.
3. The disposable regular user signs in.
   - *Expected*: **Integration clients** is absent from navigation and a direct visit to
     `/integration-clients` redirects to the Port home page.
4. The administrator returns, opens the final list page containing the newly created client
   (its row shows the **Write** Scope column badge), and confirms **Revoke**.
   - *Expected*: revocation returns `204`; replaying the same GraphQL request with that key returns
     `401`.
5. Cleanup deletes the disposable user. A `finally` cleanup also revokes the client and deletes
   the user if an earlier assertion fails; already-revoked/missing responses are accepted.
