# Ontology Errors report (Port migration phase 8, v2.5.0)

- **Spec**: [tests/entity-errors.spec.ts](../tests/entity-errors.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (entities
  and saved queries carry no admin gate; the admin token is used only for setup/cleanup
  authorization)
- **Owns** (exclusive server-side state): one throwaway blueprint (`e2e-ee-*-bp`, a `name`
  string property plus a `calculationProperties.broken` expression that never compiles), one
  entity of it (`e2e-ee-*-e1`, created before `name` is required so it starts clean, carrying a
  `sourceUrl` so it never trips SOURCE_MISSING, then made stale by a full-replace PUT adding
  `schema.required: ["name"]`), a SECOND entity of it (`e2e-ee-*-e2`, created with a `name` value
  so it never goes stale, but no `sourceUrl` so its only finding is the report-only
  SOURCE_MISSING, 2.9.1), and one throwaway PRIVATE saved entity query (`e2e-ee-*-q`) whose
  `MATCH` names a blueprint identifier (`e2e-ee-*-nope`) that never exists. All deleted at the
  end (saved query, then both entities, then blueprint).

## Scenario: stale entities, a broken calculation and a broken saved query land on the Ontology Errors report

1. The admin signs in and seeds a throwaway blueprint via the API: a `name` string property
   (not yet required) and a `calculationProperties.broken` entry whose jq expression is
   malformed.
   - *Expected*: the creation succeeds (`201`) — a calculation is never compiled at write time.
2. They seed one entity of that blueprint without a `name` value, carrying a `sourceUrl`.
   - *Expected*: the creation succeeds (`201`) — `name` isn't required yet.
3. They seed a SECOND entity of that blueprint with a `name` value but no `sourceUrl`.
   - *Expected*: the creation succeeds (`201`) — it never goes stale, but has no source
     reference.
4. They save a PRIVATE saved entity query whose `MATCH` names a blueprint identifier that has
   never existed.
   - *Expected*: the creation succeeds (`201`) — a saved query is only parse-checked at save
     time, never validated against the active registry.
5. They read the blueprint back and PUT it again with `schema.required` naming `name` (a full
   replace, so the broken calculation is resent unchanged alongside it).
   - *Expected*: the replace succeeds (`204`); the first seeded entity is now missing a required
     property; the second (which already carries `name`) is unaffected.
6. They open the **Ontology Errors** report (`/ontology/errors`).
   - *Expected*: the first entity's row links to its editor and carries the localized "Required
     property missing" finding; the second entity's row links to its editor and carries the
     localized "No source" finding; the blueprint's row links to its editor and carries the
     localized "Calculation does not compile" finding; the saved query's row shows its name and
     carries the localized "Unknown blueprint" finding.
7. They toggle the **Stale** class chip off, then back on.
   - *Expected*: the first entity's row (its only finding is class Stale) disappears entirely
     while the chip is off, and the blueprint, second-entity, and saved-query rows are
     unaffected; the first entity's row returns once the chip is back on.
8. They toggle the **Source** class chip off, then back on.
   - *Expected*: the second entity's row (its only finding is class Source) disappears entirely
     while the chip is off, and the first entity's row (which carries a source) is unaffected;
     the second entity's row returns once the chip is back on.
9. They hide every registered blueprint pill.
   - *Expected*: the entity and blueprint rows disappear, while the broken saved-query row
     and its "Unknown blueprint" diagnostic remain visible because blueprint filtering does
     not apply to saved queries.
10. They click **Open in graph** on the saved query's row.
   - *Expected*: the app navigates to the Entity graph; its shared "Entity query" editor now
     holds the saved query's exact text, the graph request answers `400` (the blueprint still
     doesn't exist), and the query bar's diagnostics name the same missing blueprint identifier.
11. Cleanup (API): the saved query, then both entities, then the blueprint.

## Not covered here (and why)

- **The remaining vocabulary classes this report covers — unresolved ownership
  (`OWNERSHIP_UNRESOLVED`/`OWNERSHIP_PATH_STALE`) and the mirror/aggregation-path stale codes
  — and the other REPORTED-vs-SHOWN filter semantics (`q`/`team` narrowing entity and
  blueprint rows, never saved queries)** — pinned exhaustively by `EntityErrorsCheckTest` (the
  pure checker rule table) and `EntityErrorsTest` (the route, including every filter
  combination and the read-budget refusals); this journey sticks to the classes an ordinary user
  is most likely to hit — a stale entity, a source-less entity, a broken calculation, and a
  broken saved query — plus the class-chip filters and the graph handoff a UI regression could
  actually hide.
- **The quarantined-calculation code and the compile-message content itself** — the compiled
  jq error text is jackson-jq's own message, pinned server-side (`JqCalculationTest`,
  `EntityErrorsCheckTest`); this journey only asserts the localized code label renders.
