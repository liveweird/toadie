import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseVmStatus, percentile, renderReport, statusHistogram, summarize,
} from './report.mjs'

const sample = (status, ms, bytes = 0) => ({ status, ms, bytes })

test('status histogram orders numeric statuses first and counts transport errors last', () => {
  const samples = [sample(429, 5), sample(200, 5), sample('ERR', 5), sample(200, 5), sample(502, 5)]
  assert.equal(statusHistogram(samples), '200x2, 429x1, 502x1, ERRx1')
  assert.equal(statusHistogram([]), '')
})

test('percentile is nearest-rank and tolerates empty and single inputs', () => {
  assert.equal(percentile([], 0.5), undefined)
  assert.equal(percentile([7], 0.5), 7)
  assert.equal(percentile([10, 40, 20, 30], 0.5), 20)
  assert.equal(percentile([10, 40, 20, 30], 1), 40)
})

test('summary takes latency from every sample but payload only from successful responses', () => {
  const row = summarize([sample(200, 100, 2048), sample(429, 10, 90_000), sample(200, 300, 4096)])
  assert.equal(row.statuses, '200x2, 429x1')
  assert.equal(row.p50, 100)
  assert.equal(row.max, 300)
  assert.equal(row.kib, 4)
  assert.equal(summarize([sample(500, 1, 10)]).kib, undefined)
})

test('VM status parsing reads RSS and high-water mark and survives a missing field', () => {
  const text = 'Name:\tjava\nVmPeak:\t 900000 kB\nVmHWM:\t  532480 kB\nVmRSS:\t  518144 kB\nThreads:\t40\n'
  assert.deepEqual(parseVmStatus(text), { rssKb: 518144, hwmKb: 532480 })
  assert.deepEqual(parseVmStatus('Name:\tjava\n'), { rssKb: undefined, hwmKb: undefined })
})

test('report renders one row per endpoint and concurrency plus the memory section', () => {
  const markdown = renderReport({
    workspace: { entities: 300, files: 297, notes: ['entities: stopped by limit'] },
    cells: [
      { endpoint: '/api/v1/entities/graph', concurrency: 1, samples: [sample(200, 120, 1024 * 512)] },
      { endpoint: '/api/v1/entities/graph', concurrency: 3, samples: [sample(200, 400, 1024 * 512), sample(429, 20), sample(200, 380, 1024 * 512)] },
    ],
    memory: { before: { rssKb: 300 * 1024, hwmKb: 310 * 1024 }, after: { rssKb: 500 * 1024, hwmKb: 520 * 1024 }, restartCount: 0, oomKilled: false },
  })
  assert.match(markdown, /Workspace: 300 entities, 297 catalog files\./)
  assert.match(markdown, /- entities: stopped by limit/)
  assert.match(markdown, /\| `\/api\/v1\/entities\/graph` \| 1 \| 200x1 \| 120 \| 120 \| 512 \|/)
  assert.match(markdown, /\| `\/api\/v1\/entities\/graph` \| 3 \| 200x2, 429x1 \| 380 \| 400 \| 512 \|/)
  assert.match(markdown, /\| before reads \| 300 MiB \| 310 MiB \|/)
  assert.match(markdown, /\| after reads \| 500 MiB \| 520 MiB \|/)
  assert.match(markdown, /Container restarts: 0\n/)
})

test('report flags an OOM-killed restarting container and unreadable memory', () => {
  const markdown = renderReport({
    workspace: { entities: 1, files: 1 },
    cells: [],
    memory: { before: {}, after: {}, restartCount: 2, oomKilled: true },
  })
  assert.match(markdown, /Container restarts: 2 \(OOM-killed\)/)
  assert.match(markdown, /\| after reads \| n\/a \| n\/a \|/)
})
