import { execFile, spawn } from 'node:child_process'
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { promisify } from 'node:util'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const execFileAsync = promisify(execFile)
let activeSignal

export function setCommandSignal(signal) {
  activeSignal = signal
}

export function operationSignal(timeout) {
  const deadline = AbortSignal.timeout(timeout)
  return activeSignal ? AbortSignal.any([activeSignal, deadline]) : deadline
}

export function repositoryRoot(scriptUrl) {
  return resolve(dirname(fileURLToPath(scriptUrl)), '../../..')
}

export function runId() {
  return `toadie-gql-${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`
}

export function randomSecret(bytes = 32) {
  return randomBytes(bytes).toString('base64url')
}

export async function privateTempDir() {
  const directory = await mkdtemp(join(tmpdir(), 'toadie-graphql-smoke-'))
  await chmod(directory, 0o700)
  return directory
}

export async function writePrivate(path, contents) {
  await writeFile(path, contents, { mode: 0o600 })
  await chmod(path, 0o600)
}

export async function command(command, args, options = {}) {
  const timeout = options.timeout ?? 120_000
  try {
    return await execFileAsync(command, args, {
      cwd: options.cwd,
      env: options.env,
      input: options.input,
      signal: options.ignoreSignal ? undefined : activeSignal,
      timeout,
      maxBuffer: options.maxBuffer ?? 16 * 1024 * 1024,
      encoding: 'utf8',
    })
  } catch (error) {
    const stderr = !options.sensitive && typeof error.stderr === 'string' ? error.stderr.trim().slice(-4_000) : ''
    throw new Error(`${command} failed${stderr ? `: ${stderr}` : ''}`, { cause: error })
  }
}

export function background(commandName, args, options = {}) {
  const child = spawn(commandName, args, {
    cwd: options.cwd,
    env: options.env,
    stdio: options.stdio ?? ['ignore', 'ignore', 'ignore'],
  })
  let failure
  child.once('error', (error) => { failure = error })
  return {
    child,
    assertRunning() {
      if (failure) throw failure
      if (child.exitCode !== null) throw new Error(`${commandName} exited early with ${child.exitCode}`)
    },
    async stop() {
      if (child.exitCode !== null) return
      child.kill('SIGTERM')
      await Promise.race([
        new Promise((resolve) => child.once('exit', resolve)),
        new Promise((resolve) => setTimeout(resolve, 5_000)),
      ])
      if (child.exitCode === null) {
        child.kill('SIGKILL')
        await Promise.race([
          new Promise((resolve) => child.once('exit', resolve)),
          new Promise((resolve) => setTimeout(resolve, 2_000)),
        ])
      }
    },
  }
}

export async function unusedPort() {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  await new Promise((resolve) => server.close(resolve))
  return address.port
}

export async function waitForReady(baseUrl, options = {}) {
  const deadline = Date.now() + (options.timeout ?? 180_000)
  let lastError
  while (Date.now() < deadline) {
    activeSignal?.throwIfAborted()
    try {
      const response = await fetch(`${baseUrl}/readyz`, {
        headers: { 'X-Forwarded-Proto': 'https' },
        signal: operationSignal(2_500),
      })
      if (response.ok) return
      lastError = new Error(`readiness returned ${response.status}`)
    } catch (error) {
      lastError = error
    }
    await sleep(500, undefined, activeSignal ? { signal: activeSignal } : undefined)
  }
  throw new Error(`Timed out waiting for ${baseUrl}/readyz`, { cause: lastError })
}

export function cleanupStack() {
  const callbacks = []
  let cleaning
  return {
    add(callback) { callbacks.unshift(callback) },
    async run() {
      if (!cleaning) {
        cleaning = (async () => {
          const failures = []
          for (const callback of callbacks) {
            try { await callback() } catch (error) { failures.push(error) }
          }
          if (failures.length) throw new AggregateError(failures, 'Smoke cleanup failed')
        })()
      }
      return cleaning
    },
  }
}

export async function removeTemp(directory) {
  await rm(directory, { recursive: true, force: true })
}
