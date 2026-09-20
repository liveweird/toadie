import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { operationSignal } from './support.mjs'

const JSON_HEADERS = { 'Content-Type': 'application/json', 'X-Forwarded-Proto': 'https' }

async function request(baseUrl, path, { token, body, method, expected = 200, json = true, sensitive = false } = {}) {
  const headers = { ...JSON_HEADERS }
  if (token) headers.Authorization = `${token.kind} ${token.value}`
  const response = await fetch(`${baseUrl}${path}`, {
    method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: operationSignal(30_000),
  })
  const raw = await response.text()
  assert.equal(
    response.status,
    expected,
    `${path} returned ${response.status}${sensitive ? '' : `: ${raw.slice(0, 500)}`}`,
  )
  return json && raw ? JSON.parse(raw) : raw
}

export async function login(baseUrl, password) {
  const result = await request(baseUrl, '/api/v1/login', {
    body: { email: 'admin@toadie.local', password },
    sensitive: true,
  })
  return { kind: 'Bearer', value: result.token }
}

export async function createIntegrationClient(baseUrl, jwt, name) {
  return request(baseUrl, '/api/v1/integration-clients', {
    token: jwt,
    body: { name },
    expected: 201,
    sensitive: true,
  })
}

async function graphql(baseUrl, apiKey, query) {
  const result = await request(baseUrl, '/integration/graphql', {
    token: { kind: 'Bearer', value: apiKey },
    body: { query },
  })
  assert.deepEqual(result.errors, undefined, JSON.stringify(result.errors))
  return result.data
}

const BLUEPRINT_QUERY = `{ blueprints(pageSize:100) { total items {
  id identifier title description icon schema relations mirrorProperties calculationProperties
  aggregationProperties ownership hierarchyRelations createdBy creatorName creatorDeleted
  createdAt updatedAt system
} } }`

const ENTITY_QUERY = (page) => `{ entities(pageSize:10,page:${page}) { total items {
  id blueprint blueprintId identifier title icon team properties relations findings { code field message }
  createdBy creatorName creatorDeleted createdAt updatedAt
} } }`

export function compareRows(restRows, graphRows, stringIds = new Set()) {
  const indexed = new Map(restRows.map((row) => [String(row.id), row]))
  assert.equal(indexed.size, restRows.length, 'REST response contains duplicate IDs')
  assert.equal(graphRows.length, restRows.length)
  const seen = new Set()
  for (const actual of graphRows) {
    assert.equal(seen.has(actual.id), false, `GraphQL response contains duplicate ID ${actual.id}`)
    seen.add(actual.id)
    const expected = indexed.get(actual.id)
    assert.ok(expected, `GraphQL row ${actual.id} is absent from REST`)
    for (const [field, value] of Object.entries(actual)) {
      const restValue = stringIds.has(field) ? String(expected[field]) : (expected[field] ?? null)
      assert.deepEqual(value, restValue, `${actual.id}.${field}`)
    }
  }
  assert.deepEqual(seen, new Set(indexed.keys()), 'GraphQL response IDs differ from REST')
}

export async function assertSeedParity(baseUrls, jwt, apiKey, schemaPath) {
  const expectedSdl = await readFile(schemaPath, 'utf8')
  for (const baseUrl of baseUrls) {
    const restBlueprints = (await request(baseUrl, '/api/v1/blueprints?pageSize=100', { token: jwt })).items
    const restEntities = (await request(baseUrl, '/api/v1/entities?pageSize=100', { token: jwt })).items
    const gqlBlueprints = (await graphql(baseUrl, apiKey, BLUEPRINT_QUERY)).blueprints
    const gqlEntities = []
    for (let page = 1; page <= 6; page += 1) {
      const result = (await graphql(baseUrl, apiKey, ENTITY_QUERY(page))).entities
      assert.equal(result.total, 59)
      gqlEntities.push(...result.items)
    }
    assert.equal(gqlBlueprints.total, 11)
    assert.equal(restBlueprints.length, 11)
    assert.equal(restEntities.length, 59)
    compareRows(restBlueprints, gqlBlueprints.items, new Set(['id', 'createdBy']))
    compareRows(restEntities, gqlEntities, new Set(['id', 'blueprintId', 'createdBy']))

    // Pin real read-time behavior as well as transport parity: a shared omission in REST and
    // GraphQL must not let this deployment check pass.
    const storefront = gqlEntities.find((row) => row.blueprint === 'service' && row.identifier === 'storefront-web')
    assert.ok(storefront, 'storefront-web sample entity is missing')
    assert.equal(storefront.properties.domain_title, 'Commerce')
    assert.equal(storefront.properties.stack, 'typescript, javascript, react')
    assert.equal(storefront.properties.risk, 'low')
    const workload = gqlEntities.find((row) => row.blueprint === 'workload' && row.identifier === 'storefront-web-production')
    assert.ok(workload, 'storefront-web-production sample entity is missing')
    assert.equal(workload.team, 'retail-tribe')
    assert.equal(workload.properties.service_lifecycle, 'production')
    assert.equal(workload.properties.env_type, 'production')
    assert.deepEqual(workload.properties.languages, ['typescript', 'javascript'])
    const platformTeam = gqlEntities.find((row) => row.blueprint === '_team' && row.identifier === 'platform-tribe')
    assert.ok(platformTeam, 'platform-tribe sample entity is missing')
    assert.equal(platformTeam.properties.member_count, 1)

    const report = (await graphql(baseUrl, apiKey,
      '{ errors { checkedEntities checkedBlueprints entities { total } blueprints { total } } }')).errors
    // The sample carries no source references, so since 2.9.1 every entity row — and, since
    // 2.10.0, every blueprint row too — is exactly the report-only SOURCE_MISSING finding, and
    // nothing else.
    assert.deepEqual(report, {
      checkedEntities: 59,
      checkedBlueprints: 11,
      entities: { total: 59 },
      blueprints: { total: 11 },
    })
    const restReport = await request(baseUrl, '/api/v1/entities/errors', { token: jwt })
    assert.equal(restReport.checkedEntities, 59)
    assert.equal(restReport.checkedBlueprints, 11)
    assert.equal(restReport.entities.length, 59)
    for (const row of restReport.entities) {
      assert.deepEqual(row.findings.map((f) => [f.code, f.field]), [['SOURCE_MISSING', 'source']],
        `unexpected findings on ${row.blueprint}/${row.identifier}`)
    }
    assert.equal(restReport.blueprints.length, 11)
    for (const row of restReport.blueprints) {
      assert.deepEqual(row.findings.map((f) => [f.code, f.field]), [['SOURCE_MISSING', 'source']],
        `unexpected findings on blueprint ${row.identifier}`)
    }
    const actualSdl = await request(baseUrl, '/integration/graphql/schema', {
      token: { kind: 'Bearer', value: apiKey }, json: false,
    })
    assert.equal(actualSdl, expectedSdl)
  }
}

const PROBE_SOURCE = 'https://example.com/graphql-smoke/probe.json'

export async function assertLiveFindings(baseUrls, jwt, apiKey, marker) {
  const identifier = `smoke_${marker.replaceAll('-', '_')}`
  const blueprint = { identifier, title: 'GraphQL smoke probe', schema: { properties: {} }, relations: {} }
  const createdBlueprint = await request(baseUrls[0], '/api/v1/blueprints', {
    token: jwt, body: blueprint, expected: 201,
  })
  const createdEntity = await request(baseUrls[0], '/api/v1/entities', {
    token: jwt,
    // A source reference keeps the errors-report row equal to the entity's own findings (since
    // 2.9.1 a source-less entity would additionally carry the report-only SOURCE_MISSING).
    body: { blueprint: identifier, identifier: 'probe', title: 'Before', properties: {}, relations: {}, sourceUrl: PROBE_SOURCE },
    expected: 201,
  })
  try {
    await request(baseUrls[0], `/api/v1/entities/${createdEntity.id}`, {
      token: jwt, method: 'PUT',
      body: { blueprint: identifier, identifier: 'probe', title: 'After REST update', properties: {}, relations: {}, sourceUrl: PROBE_SOURCE },
      expected: 204,
    })
    blueprint.schema = {
      properties: { required_probe: { type: 'string' } },
      required: ['required_probe'],
    }
    await request(baseUrls[0], `/api/v1/blueprints/${createdBlueprint.id}`, {
      token: jwt, method: 'PUT', body: blueprint, expected: 204,
    })
    for (const baseUrl of baseUrls) {
      const row = (await graphql(baseUrl, apiKey,
        `{ entity(id:"${createdEntity.id}") { title findings { code field message } } }`)).entity
      const rest = await request(baseUrl, `/api/v1/entities/${createdEntity.id}`, { token: jwt })
      assert.equal(row.title, 'After REST update')
      assert.ok(row.findings.length > 0)
      assert.deepEqual(row.findings, rest.findings)
      const report = (await graphql(baseUrl, apiKey,
        `{ errors(blueprints:["${identifier}"]) { entities { total items { id findings { code field message } } } } }`))
        .errors.entities
      assert.equal(report.total, 1)
      assert.deepEqual(report.items[0].findings, row.findings)
    }
  } finally {
    await request(baseUrls[0], `/api/v1/entities/${createdEntity.id}`, { token: jwt, method: 'DELETE', expected: 204 })
    await request(baseUrls[0], `/api/v1/blueprints/${createdBlueprint.id}`, { token: jwt, method: 'DELETE', expected: 204 })
  }
}

export async function revokeAndAssert(baseUrls, jwt, clientId, apiKey) {
  await request(baseUrls[0], `/api/v1/integration-clients/${clientId}/revoke`, {
    token: jwt, method: 'POST', expected: 204,
  })
  for (const baseUrl of baseUrls) {
    await request(baseUrl, '/integration/graphql', {
      token: { kind: 'Bearer', value: apiKey },
      body: { query: '{ blueprints { total } }' },
      expected: 401,
    })
  }
}
