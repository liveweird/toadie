import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { assertDisposableProject, composeDocument, drillProjectName } from './drill.mjs'

test('drill project names are unique and accepted by the cleanup guard', () => {
  const first = drillProjectName(1, '00000001')
  const second = drillProjectName(1, '00000002')
  assert.notEqual(first, second)
  assert.doesNotThrow(() => assertDisposableProject(first))
})

test('cleanup guard rejects development and malformed project names', () => {
  for (const project of ['toadie', 'toadie-ci', 'toadie-backup-drill-owned', '']) {
    assert.throws(() => assertDisposableProject(project), /Refusing non-disposable/)
  }
})

test('drill compose owns two isolated volumes and publishes no ports', () => {
  const document = composeDocument('test-secret')
  assert.match(document, /source-data:\/var\/lib\/postgresql/)
  assert.match(document, /restored-data:\/var\/lib\/postgresql/)
  assert.doesNotMatch(document, /external:/)
  assert.doesNotMatch(document, /ports:/)
  assert.doesNotMatch(document, /postgres-data/)
})

test('SIGTERM interrupts work and removes only the disposable Compose project', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'toadie-drill-signal-test-'))
  const docker = join(temporary, 'docker')
  const calls = join(temporary, 'calls.txt')
  await writeFile(docker, String.raw`#!/bin/sh
printf '%s\n' "$*" >> "$DRILL_TEST_CALLS"
case " $* " in
  *" up "*) exec sleep 60 ;;
esac
`)
  await chmod(docker, 0o755)
  const child = spawn(process.execPath, [resolve(import.meta.dirname, 'drill.mjs')], {
    cwd: resolve(import.meta.dirname, '../..'),
    env: {
      ...process.env,
      PATH: `${temporary}:${process.env.PATH ?? ''}`,
      DRILL_TEST_CALLS: calls,
    },
    stdio: 'ignore',
  })
  try {
    let logged = ''
    for (let attempt = 0; attempt < 100; attempt += 1) {
      logged = await readFile(calls, 'utf8').catch(() => '')
      if (logged.includes(' up ')) break
      await delay(50)
    }
    assert.match(logged, / up /, 'the fake Docker work command must start')
    child.kill('SIGTERM')
    const [code] = await Promise.race([
      once(child, 'exit'),
      delay(5_000).then(() => { throw new Error('interrupted drill did not exit') }),
    ])
    assert.notEqual(code, 0)
    logged = await readFile(calls, 'utf8')
    assert.match(logged, / down --volumes --remove-orphans /)
    assert.doesNotMatch(logged, /--project-name toadie(?:\s|$)/)
  } finally {
    child.kill('SIGKILL')
    await rm(temporary, { recursive: true, force: true })
  }
})
