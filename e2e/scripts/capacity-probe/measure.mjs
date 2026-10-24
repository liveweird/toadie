import { command, operationSignal } from '../graphql-smoke/support.mjs'
import { parseVmStatus } from './report.mjs'

/** The whole-workspace reads: every one materialises the full workspace in the app. */
export const ENDPOINTS = [
  '/api/v1/entities/graph',
  '/api/v1/entities/errors',
  '/api/v1/files/graph',
  '/api/v1/files/errors',
  '/api/v1/files/export',
]

async function timedGet(baseUrl, path, token) {
  const started = performance.now()
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      headers: { Authorization: `Bearer ${token}`, 'X-Forwarded-Proto': 'https' },
      signal: operationSignal(180_000),
    })
    const body = await response.arrayBuffer()
    return { status: response.status, ms: performance.now() - started, bytes: body.byteLength }
  } catch {
    return { status: 'ERR', ms: performance.now() - started, bytes: 0 }
  }
}

/**
 * For each endpoint and concurrency K: K simultaneous GETs with the same token, one round.
 * One sequential, unrecorded request per endpoint first so a cold JIT/cache does not decide K=1.
 */
export async function measureEndpoints({ baseUrl, token, concurrencies, endpoints = ENDPOINTS, onCell = () => {} }) {
  const cells = []
  for (const endpoint of endpoints) {
    await timedGet(baseUrl, endpoint, token)
    for (const concurrency of concurrencies) {
      const samples = await Promise.all(Array.from({ length: concurrency }, () => timedGet(baseUrl, endpoint, token)))
      const cell = { endpoint, concurrency, samples }
      cells.push(cell)
      onCell(cell)
    }
  }
  return cells
}

// The image's start script execs the JVM, but find it by command line rather than assume PID 1.
const FIND_JAVA = 'for d in /proc/[0-9]*; do if tr "\\0" " " < "$d/cmdline" 2>/dev/null | grep -q java; then cat "$d/status"; break; fi; done'

/** VmRSS/VmHWM of the app container's java process. */
export async function appMemory(container) {
  const { stdout } = await command('docker', ['exec', container, 'sh', '-c', FIND_JAVA], { timeout: 30_000 })
  return parseVmStatus(stdout)
}

/** Restart count and OOM flag as the container runtime recorded them. */
export async function containerState(container) {
  const { stdout } = await command('docker', [
    'inspect', '--format', '{{.RestartCount}} {{.State.OOMKilled}}', container,
  ], { timeout: 30_000 })
  const [restartCount, oomKilled] = stdout.trim().split(' ')
  return { restartCount: Number(restartCount), oomKilled: oomKilled === 'true' }
}
