import { setTimeout as sleep } from 'node:timers/promises'
import { operationSignal } from '../graphql-smoke/support.mjs'

const BATCH = 200 // the import endpoints' document cap
const HEADERS = { 'Content-Type': 'application/json', 'X-Forwarded-Proto': 'https' }

async function post(baseUrl, path, token, body) {
  for (let attempt = 0; ; attempt += 1) {
    const response = await fetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers: { ...HEADERS, Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
      signal: operationSignal(120_000),
    })
    const text = await response.text()
    // A 429 is the server asking for a moment, not a refusal of the batch: wait and resend.
    if (response.status === 429 && attempt < 8) {
      await sleep(1_000 * (attempt + 1))
      continue
    }
    if (response.status !== 200) throw new Error(`${path} returned ${response.status}: ${text.slice(0, 300)}`)
    return JSON.parse(text)
  }
}

// The server's cap refusals are INVALID rows worded "... is full (N entities)".
const isCapRefusal = (row) => row.status === 'INVALID' && /\bfull\b/i.test(row.message ?? '')

/**
 * Posts `documents` in batches of 200 to an import endpoint and tallies the per-row statuses.
 * Stops at the first batch containing a cap refusal and says so; other failures are counted and
 * the first few sampled, never fatal (the probe measures whatever workspace did land).
 */
export async function importDocuments({ baseUrl, token, path, field, documents }) {
  const counts = {}
  const failures = []
  let refused
  for (let index = 0; index < documents.length && !refused; index += BATCH) {
    const report = await post(baseUrl, path, token, { [field]: documents.slice(index, index + BATCH) })
    for (const row of report.results ?? []) {
      counts[row.status] = (counts[row.status] ?? 0) + 1
      if (isCapRefusal(row)) refused ??= row.message
      else if (!['CREATED', 'CREATED_WITH_FINDINGS', 'EXISTS'].includes(row.status) && failures.length < 3) {
        failures.push(`${row.status}: ${String(row.message ?? '').slice(0, 160)}`)
      }
    }
  }
  return { counts, failures, refused }
}

/** The server's own active count for a list endpoint (`GET <path>?pageSize=1` -> `total`). */
export async function activeTotal(baseUrl, token, path) {
  const response = await fetch(`${baseUrl}${path}?pageSize=1`, {
    headers: { ...HEADERS, Authorization: `Bearer ${token}` },
    signal: operationSignal(30_000),
  })
  if (response.status !== 200) throw new Error(`${path} returned ${response.status}`)
  return (await response.json()).total
}
