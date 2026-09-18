import assert from 'node:assert/strict'
import { access, mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { compareRows } from './api-check.mjs'
import { composeAppUrl, composeEnvironment, composeNames } from './orchestration.mjs'
import {
  cleanupStack, command, repositoryRoot, runId, setCommandSignal, writePrivate,
} from './support.mjs'

test('run identifiers and Compose resources are isolated per invocation', () => {
  const first = runId()
  const second = runId()
  assert.notEqual(first, second)
  assert.match(first, /^toadie-gql-[a-z0-9]+-[a-f0-9]{8}$/)
  const firstNames = composeNames(first)
  const secondNames = composeNames(second)
  for (const key of Object.keys(firstNames)) assert.notEqual(firstNames[key], secondNames[key])
  assert.equal(firstNames.project, first)
})

test('repository root resolves to deployment and sample sources', async () => {
  const root = repositoryRoot(new URL('./run.mjs', import.meta.url))
  await Promise.all([
    access(join(root, 'Dockerfile')),
    access(join(root, 'sample-data/port/commerce-payments/blueprints/01-team.json')),
  ])
})

test('row parity rejects a duplicated GraphQL row that hides an omission', () => {
  const rest = [{ id: 1, title: 'one' }, { id: 2, title: 'two' }]
  assert.throws(
    () => compareRows(rest, [{ id: '1', title: 'one' }, { id: '1', title: 'one' }], new Set(['id'])),
    /duplicate ID 1/,
  )
})

test('cleanup is reverse ordered, complete after a failure, and idempotent', async () => {
  const cleanup = cleanupStack()
  const events = []
  cleanup.add(async () => events.push('first-added'))
  cleanup.add(async () => { events.push('failing'); throw new Error('expected') })
  cleanup.add(async () => events.push('last-added'))
  await assert.rejects(cleanup.run(), AggregateError)
  await assert.rejects(cleanup.run(), AggregateError)
  assert.deepEqual(events, ['last-added', 'failing', 'first-added'])
})

test('Compose isolation ignores inherited project and file selection', () => {
  const environment = composeEnvironment({
    PATH: '/bin',
    COMPOSE_FILE: '/tmp/unrelated.yaml',
    COMPOSE_PROJECT_NAME: 'unrelated-project',
  })
  assert.deepEqual(environment, { PATH: '/bin' })
})

test('Compose app URL is rediscovered from the current published port', async () => {
  const outputs = ['127.0.0.1:32001\n', '127.0.0.1:32769\n']
  const execute = async () => ({ stdout: outputs.shift() })
  const options = { root: '/repo', compose: ['compose', '--project-name', 'owned'], env: {}, execute }
  assert.equal(await composeAppUrl(options), 'http://127.0.0.1:32001')
  assert.equal(await composeAppUrl(options), 'http://127.0.0.1:32769')
})

test('interrupt aborts active subprocesses while cleanup commands remain available', async () => {
  const controller = new AbortController()
  setCommandSignal(controller.signal)
  try {
    const held = command(process.execPath, ['-e', 'setTimeout(() => {}, 30000)'], { timeout: 35_000 })
    controller.abort(new Error('test interrupt'))
    let timeout
    try {
      await Promise.race([
        assert.rejects(held, /failed/),
        new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('subprocess did not abort')), 2_000) }),
      ])
    } finally {
      clearTimeout(timeout)
    }
    const result = await command(process.execPath, ['-e', 'process.stdout.write("cleanup-ok")'], {
      ignoreSignal: true,
    })
    assert.equal(result.stdout, 'cleanup-ok')
  } finally {
    setCommandSignal(undefined)
  }
})

test('secret-bearing generated files are mode 0600', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'toadie-graphql-smoke-test-'))
  try {
    const path = join(directory, 'secret.json')
    await writePrivate(path, '{"secret":"not-logged"}')
    assert.equal((await stat(path)).mode & 0o777, 0o600)
    assert.equal(await readFile(path, 'utf8'), '{"secret":"not-logged"}')
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
