// Pure result summarising and markdown rendering for the capacity probe.

/** `{ status, ms, bytes }` samples -> "200x8, 429x2" (numeric order, errors last). */
export function statusHistogram(samples) {
  const counts = new Map()
  for (const { status } of samples) counts.set(status, (counts.get(status) ?? 0) + 1)
  const rank = (status) => (typeof status === 'number' ? status : Number.POSITIVE_INFINITY)
  return [...counts.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || String(a).localeCompare(String(b)))
    .map(([status, count]) => `${status}x${count}`)
    .join(', ')
}

/** Nearest-rank percentile of a numeric list; `undefined` for an empty list. */
export function percentile(values, p) {
  if (values.length === 0) return undefined
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))]
}

/** One table row's numbers: histogram, p50/max latency of every sample, KiB of the largest 200 body. */
export function summarize(samples) {
  const latencies = samples.map((sample) => sample.ms)
  const bodies = samples.filter((sample) => sample.status === 200).map((sample) => sample.bytes)
  return {
    statuses: statusHistogram(samples),
    p50: percentile(latencies, 0.5),
    max: latencies.length ? Math.max(...latencies) : undefined,
    kib: bodies.length ? Math.max(...bodies) / 1024 : undefined,
  }
}

/** Extracts VmRSS / VmHWM (kB) from the text of /proc/<pid>/status. */
export function parseVmStatus(text) {
  const read = (key) => {
    const match = text.match(new RegExp(`^${key}:\\s+(\\d+)\\s*kB`, 'm'))
    return match ? Number(match[1]) : undefined
  }
  return { rssKb: read('VmRSS'), hwmKb: read('VmHWM') }
}

const mib = (kb) => (kb === undefined ? 'n/a' : `${(kb / 1024).toFixed(0)} MiB`)
const ms = (value) => (value === undefined ? 'n/a' : String(Math.round(value)))
const kib = (value) => (value === undefined ? 'n/a' : value.toFixed(0))

/**
 * Renders the whole probe result as markdown.
 * `cells`: [{ endpoint, concurrency, samples }]; `memory`: { before, after, restartCount, oomKilled }
 * (before/after are parseVmStatus results); `workspace`: { entities, files, notes } counts as reached.
 */
export function renderReport({ workspace, cells, memory }) {
  const lines = ['## Capacity probe', '']
  lines.push(`Workspace: ${workspace.entities} entities, ${workspace.files} catalog files.`)
  for (const note of workspace.notes ?? []) lines.push(`- ${note}`)
  lines.push('', '### Whole-workspace reads', '')
  lines.push('| Endpoint | Concurrency | Statuses | p50 ms | max ms | Payload KiB |')
  lines.push('|---|---:|---|---:|---:|---:|')
  for (const { endpoint, concurrency, samples } of cells) {
    const row = summarize(samples)
    lines.push(`| \`${endpoint}\` | ${concurrency} | ${row.statuses} | ${ms(row.p50)} | ${ms(row.max)} | ${kib(row.kib)} |`)
  }
  lines.push('', '### App process', '')
  lines.push('| | VmRSS | VmHWM |')
  lines.push('|---|---:|---:|')
  lines.push(`| before reads | ${mib(memory.before.rssKb)} | ${mib(memory.before.hwmKb)} |`)
  lines.push(`| after reads | ${mib(memory.after.rssKb)} | ${mib(memory.after.hwmKb)} |`)
  lines.push('', `Container restarts: ${memory.restartCount}${memory.oomKilled ? ' (OOM-killed)' : ''}`, '')
  return lines.join('\n')
}
