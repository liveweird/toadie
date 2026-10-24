// Pure synthetic-workspace generation for the capacity probe: replicates the committed Port and
// Backstage commerce/payments samples k times so a disposable stack reaches a chosen size.
// No I/O here; run.mjs reads the sample files and load.mjs posts the documents.

export const MAX_ENTITIES_PER_BLUEPRINT = 2000 // Entity.kt MAX_ENTITIES_PER_BLUEPRINT
export const MAX_ENTITIES_TOTAL = 10_000 // Entity.kt MAX_ENTITIES_TOTAL

// Shared across the whole workspace: the org's few domains, environments, clusters and the two
// tribes. Squads and users are copied once per GROUP of replicas; everything else per replica.
const SHARED_BLUEPRINTS = new Set(['domain', 'environment', 'cluster'])
const TRIBES = new Set(['platform-tribe', 'retail-tribe'])
export const GROUP = 5

// Backstage: Groups, Users and Domains stay shared; everything else is copied per replica.
const SHARED_KINDS = new Set(['Group', 'User', 'Domain'])

function isUserProperty(definition) {
  return definition?.format === 'user' || definition?.items?.format === 'user'
}

/**
 * Port entity replicator over the parsed blueprints and the parsed entity files (each an array of
 * documents, in dependency order). Relation values are remapped by the relation's TARGET blueprint,
 * never by name: `payments`/`invoicing` exist as identifiers in two different blueprints.
 */
export function entityReplicator(blueprints, entityFiles) {
  const relationTarget = Object.fromEntries(blueprints.map((blueprint) => [
    blueprint.identifier,
    Object.fromEntries(Object.entries(blueprint.relations ?? {}).map(([key, relation]) => [key, relation.target])),
  ]))
  const userProperties = Object.fromEntries(blueprints.map((blueprint) => [
    blueprint.identifier,
    Object.entries(blueprint.schema?.properties ?? {}).filter(([, definition]) => isUserProperty(definition)).map(([key]) => key),
  ]))

  const group = (k) => Math.floor(k / GROUP)
  function mapIdentifier(blueprint, identifier, k) {
    if (identifier == null) return identifier
    if (SHARED_BLUEPRINTS.has(blueprint)) return identifier
    if (blueprint === '_team') return TRIBES.has(identifier) ? identifier : `${identifier}-g${group(k)}`
    if (blueprint === '_user') return `${identifier}-g${group(k)}`
    return `${identifier}-r${k}`
  }
  const mapMany = (blueprint, value, k) => (
    Array.isArray(value) ? value.map((item) => mapIdentifier(blueprint, item, k)) : mapIdentifier(blueprint, value, k)
  )

  function clone(entity, k) {
    const out = structuredClone(entity)
    out.identifier = mapIdentifier(entity.blueprint, entity.identifier, k)
    if (out.title && out.identifier !== entity.identifier) {
      out.title = `${out.title} ${out.identifier.slice(entity.identifier.length + 1)}`
    }
    if (out.team != null) out.team = mapMany('_team', out.team, k)
    for (const [relation, value] of Object.entries(out.relations ?? {})) {
      out.relations[relation] = mapMany(relationTarget[entity.blueprint]?.[relation], value, k)
    }
    for (const property of userProperties[entity.blueprint] ?? []) {
      if (out.properties?.[property] != null) out.properties[property] = mapMany('_user', out.properties[property], k)
    }
    delete out.sourceUrl
    return out
  }

  const isShared = (entity) => SHARED_BLUEPRINTS.has(entity.blueprint)
    || (entity.blueprint === '_team' && TRIBES.has(entity.identifier))
  function emits(entity, k) {
    if (isShared(entity)) return k === 0
    if (entity.blueprint === '_team' || entity.blueprint === '_user') return k % GROUP === 0
    return true
  }

  return {
    /** Documents for replicas [from, to), blueprint-file-major so a document follows what it references. */
    generate(from, to) {
      const documents = []
      for (const file of entityFiles) {
        for (let k = from; k < to; k += 1) {
          for (const entity of file) if (emits(entity, k)) documents.push(clone(entity, k))
        }
      }
      return documents
    },
  }
}

/**
 * How many whole replicas fit in `limit` entities without crossing the server's caps. Returns the
 * replica count, the planned total and per-blueprint counts, and what stopped the plan
 * (`limit`, `total-cap` or `blueprint-cap:<identifier>`).
 */
export function planEntityReplicas(replicator, limit) {
  const byBlueprint = {}
  let total = 0
  let replicas = 0
  const cap = Math.min(limit, MAX_ENTITIES_TOTAL)
  const capped = limit > MAX_ENTITIES_TOTAL ? 'total-cap' : 'limit'
  for (;;) {
    const next = replicator.generate(replicas, replicas + 1)
    if (next.length === 0) return { replicas, total, byBlueprint, stoppedBy: 'empty-sample' }
    if (total + next.length > cap) return { replicas, total, byBlueprint, stoppedBy: capped }
    const added = {}
    for (const entity of next) added[entity.blueprint] = (added[entity.blueprint] ?? 0) + 1
    const blocked = Object.entries(added).find(([blueprint, n]) => (byBlueprint[blueprint] ?? 0) + n > MAX_ENTITIES_PER_BLUEPRINT)
    if (blocked) return { replicas, total, byBlueprint, stoppedBy: `blueprint-cap:${blocked[0]}` }
    for (const [blueprint, n] of Object.entries(added)) byBlueprint[blueprint] = (byBlueprint[blueprint] ?? 0) + n
    total += next.length
    replicas += 1
  }
}

/** Backstage replicator over the parsed documents of the commerce/payments sample. */
export function catalogReplicator(documents) {
  const names = new Set(documents.map((document) => document.metadata.name))
  const sharedNames = new Set(documents.filter((document) => SHARED_KINDS.has(document.kind)).map((document) => document.metadata.name))
  const emits = (document, k) => k === 0 || !SHARED_KINDS.has(document.kind)

  // Keeps any `kind:namespace/` prefix; a value that is not a copied document's name passes through.
  function remap(value, k) {
    if (typeof value !== 'string') return value
    const match = value.match(/^((?:[a-z]+:)?(?:[a-z0-9-]+\/)?)([a-z0-9-]+)$/i)
    if (!match || !names.has(match[2]) || sharedNames.has(match[2])) return value
    return `${match[1]}${match[2]}-r${k}`
  }

  function clone(document, k) {
    const out = structuredClone(document)
    delete out.apiVersion
    if (!SHARED_KINDS.has(document.kind)) out.metadata.name = `${document.metadata.name}-r${k}`
    for (const [key, value] of Object.entries(out.spec ?? {})) {
      out.spec[key] = Array.isArray(value) ? value.map((item) => remap(item, k)) : remap(value, k)
    }
    return out
  }

  return {
    generate(from, to) {
      const out = []
      for (let k = from; k < to; k += 1) {
        for (const document of documents) if (emits(document, k)) out.push(clone(document, k))
      }
      return out
    },
    countFor: (k) => documents.filter((document) => emits(document, k)).length,
  }
}

/** Whole replicas of the catalog sample that fit in `limit` files. */
export function planCatalogReplicas(replicator, limit) {
  let total = 0
  let replicas = 0
  for (;;) {
    const next = replicator.countFor(replicas)
    if (next === 0 || total + next > limit) return { replicas, total }
    total += next
    replicas += 1
  }
}
