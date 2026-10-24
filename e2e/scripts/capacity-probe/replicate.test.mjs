import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import test from 'node:test'
import YAML from 'yaml'
import { repositoryRoot } from '../graphql-smoke/support.mjs'
import {
  catalogReplicator, entityReplicator, GROUP, MAX_ENTITIES_PER_BLUEPRINT, MAX_ENTITIES_TOTAL,
  planCatalogReplicas, planEntityReplicas,
} from './replicate.mjs'

const root = repositoryRoot(new URL('./run.mjs', import.meta.url))
const portDirectory = join(root, 'sample-data/port/commerce-payments')
const numbered = (directory) => readdirSync(directory).filter((file) => /^\d\d-.*\.json$/.test(file)).sort()
const blueprints = numbered(join(portDirectory, 'blueprints'))
  .map((file) => JSON.parse(readFileSync(join(portDirectory, 'blueprints', file), 'utf8')))
const entityFiles = numbered(join(portDirectory, 'entities'))
  .map((file) => JSON.parse(readFileSync(join(portDirectory, 'entities', file), 'utf8')))
const replicator = entityReplicator(blueprints, entityFiles)
const sampleSize = entityFiles.flat().length

const key = (entity) => `${entity.blueprint}/${entity.identifier}`
const asList = (value) => (Array.isArray(value) ? value : value == null ? [] : [value])

test('entity replicas have unique identifiers and every reference resolves inside the generated set', () => {
  const documents = replicator.generate(0, 2 * GROUP + 1)
  const keys = documents.map(key)
  assert.equal(new Set(keys).size, keys.length)
  const known = new Set(keys)
  const targets = Object.fromEntries(blueprints.map((blueprint) => [
    blueprint.identifier, Object.fromEntries(Object.entries(blueprint.relations ?? {}).map(([name, relation]) => [name, relation.target])),
  ]))
  let relationCount = 0
  for (const entity of documents) {
    for (const [relation, value] of Object.entries(entity.relations ?? {})) {
      for (const identifier of asList(value)) {
        relationCount += 1
        assert.ok(known.has(`${targets[entity.blueprint][relation]}/${identifier}`), `${key(entity)} -> ${relation} -> ${identifier}`)
      }
    }
    for (const team of asList(entity.team)) assert.ok(known.has(`_team/${team}`), `${key(entity)} team ${team}`)
  }
  assert.ok(relationCount > documents.length / 2, 'the sample should be relation-rich')
})

test('shared entities appear once; squads and users once per group of replicas; the rest per replica', () => {
  const countOf = (documents, blueprint) => documents.filter((entity) => entity.blueprint === blueprint).length
  const one = replicator.generate(0, 1)
  const many = replicator.generate(0, GROUP * 2)
  for (const shared of ['domain', 'environment', 'cluster']) assert.equal(countOf(many, shared), countOf(one, shared))
  assert.equal(countOf(many, '_user'), countOf(one, '_user') * 2)
  assert.equal(countOf(many, 'service'), countOf(one, 'service') * GROUP * 2)
  assert.ok(many.length > one.length && many.length < one.length * GROUP * 2)
  assert.ok(one.length <= sampleSize)
})

test('an identifier used by two blueprints is remapped through the relation target, not by name', () => {
  const documents = replicator.generate(0, 2)
  const identifiersOf = (blueprint) => documents.filter((entity) => entity.blueprint === blueprint).map((entity) => entity.identifier)
  // The sample has a shared `payments` domain and a per-replica `payments` system (and an
  // `invoicing` product beside the `invoicing` system): only the per-replica copy is suffixed.
  assert.ok(identifiersOf('domain').includes('payments'))
  assert.ok(identifiersOf('system').includes('payments-r1'))
  assert.ok(identifiersOf('product').includes('invoicing-r1') && identifiersOf('system').includes('invoicing-r1'))
  const system = documents.find((entity) => entity.blueprint === 'system' && entity.identifier === 'payments-r1')
  assert.deepEqual(asList(system.relations.domain), ['payments'])
})

test('generation does not mutate the sample and drops source references', () => {
  const before = JSON.stringify(entityFiles)
  const documents = replicator.generate(3, 5)
  assert.equal(JSON.stringify(entityFiles), before)
  assert.ok(documents.every((entity) => !('sourceUrl' in entity)))
})

test('entity plan fills the limit with whole replicas and reports what stopped it', () => {
  const plan = planEntityReplicas(replicator, 300)
  assert.ok(plan.total <= 300 && plan.total > 300 - 80, `total ${plan.total}`)
  assert.equal(plan.total, replicator.generate(0, plan.replicas).length)
  assert.equal(plan.stoppedBy, 'limit')
  assert.equal(Object.values(plan.byBlueprint).reduce((a, b) => a + b, 0), plan.total)
})

test('entity plan never crosses the total cap, whatever is asked', () => {
  const plan = planEntityReplicas(replicator, MAX_ENTITIES_TOTAL * 3)
  assert.ok(plan.total <= MAX_ENTITIES_TOTAL)
  assert.equal(plan.stoppedBy, 'total-cap')
})

test('entity plan stops at the per-blueprint cap before the total cap', () => {
  const lopsided = { generate: (from) => (from < 3000 ? [{ blueprint: 'service', identifier: `s-${from}` }] : []) }
  const plan = planEntityReplicas(lopsided, MAX_ENTITIES_TOTAL)
  assert.equal(plan.total, MAX_ENTITIES_PER_BLUEPRINT)
  assert.equal(plan.stoppedBy, 'blueprint-cap:service')
})

const catalogDocuments = YAML.parseAllDocuments(
  readFileSync(join(root, 'sample-data/backstage/commerce-payments/catalog-info.yaml'), 'utf8'),
).map((document) => document.toJS()).filter(Boolean)
const catalog = catalogReplicator(catalogDocuments)

test('catalog replicas share Groups, Users and Domains and copy everything else with suffixed names', () => {
  const documents = catalog.generate(0, 3)
  const names = documents.map((document) => `${document.kind}/${document.metadata.name}`)
  assert.equal(new Set(names).size, names.length)
  const sharedKinds = new Set(['Group', 'User', 'Domain'])
  const sharedCount = catalogDocuments.filter((document) => sharedKinds.has(document.kind)).length
  assert.equal(documents.length, catalogDocuments.length + 2 * (catalogDocuments.length - sharedCount))
  assert.ok(documents.filter((document) => !sharedKinds.has(document.kind)).every((document) => /-r[012]$/.test(document.metadata.name)))
  assert.ok(documents.every((document) => !('apiVersion' in document)))
})

test('catalog references keep their kind:namespace prefix and point at the same replica', () => {
  const documents = catalog.generate(2, 3)
  const component = documents.find((document) => document.spec?.system)
  assert.match(component.spec.system, /^system:default\/[a-z0-9-]+-r2$/)
  assert.equal(component.spec.owner.replace(/-r2$/, ''), component.spec.owner, 'groups stay shared')
  assert.ok(documents.some((document) => document.spec?.dependsOn?.some((reference) => /-r2$/.test(reference))))
})

test('catalog plan fits whole replicas in the requested file count', () => {
  const plan = planCatalogReplicas(catalog, 300)
  assert.ok(plan.total <= 300 && plan.total > 300 - catalogDocuments.length)
  assert.equal(plan.total, catalog.generate(0, plan.replicas).length)
  assert.equal(planCatalogReplicas(catalog, 5).replicas, 0)
})
