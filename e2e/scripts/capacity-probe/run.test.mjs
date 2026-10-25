import assert from 'node:assert/strict'
import test from 'node:test'
import { appEnvironmentLines } from '../graphql-smoke/orchestration.mjs'
import { parseArgs, probeId } from './run.mjs'

test('arguments parse sizes, the concurrency list and --keep', () => {
  const parsed = parseArgs(['--entities', '300', '--files', '250', '--concurrency', '1,3', '--keep'])
  assert.deepEqual({ ...parsed, appEnv: { ...parsed.appEnv } }, { entities: 300, files: 250, concurrency: [1, 3], keep: true, appEnv: {} })
  assert.deepEqual(parseArgs([]).concurrency, [1, 2, 3, 5, 10])
  assert.equal(parseArgs(['--help']).help, true)
})

test('the probe cannot be pointed at an existing deployment', () => {
  for (const option of ['--base-url', '--url', '--project', '--namespace']) {
    assert.throws(() => parseArgs([option, 'http://localhost:8081']), /Unknown or incomplete option/)
  }
})

test('invalid sizes and concurrency are refused', () => {
  assert.throws(() => parseArgs(['--entities', '-1']), /integer >= 0/)
  assert.throws(() => parseArgs(['--files', 'many']), /integer >= 0/)
  assert.throws(() => parseArgs(['--concurrency', '1,0']), /integer >= 1/)
  assert.throws(() => parseArgs(['--entities']), /Unknown or incomplete option/)
})

test('probe project names are unique and never collide with the long-lived stacks', () => {
  const first = probeId()
  const second = probeId()
  assert.notEqual(first, second)
  assert.match(first, /^toadie-cap-[a-z0-9]+-[a-f0-9]{8}$/)
  assert.notEqual(first, 'toadie')
})

test('--app-env is repeatable and keeps everything after the first equals sign', () => {
  assert.deepEqual({ ...parseArgs([]).appEnv }, {})
  assert.deepEqual(
    { ...parseArgs(['--app-env', 'MALLOC_ARENA_MAX=2', '--app-env', 'JAVA_OPTS=-XX:MaxDirectMemorySize=64m -Dx=a=b', '--app-env', 'EMPTY=']).appEnv },
    { MALLOC_ARENA_MAX: '2', JAVA_OPTS: '-XX:MaxDirectMemorySize=64m -Dx=a=b', EMPTY: '' },
  )
})

test('--app-env refuses malformed entries and duplicate keys', () => {
  for (const bad of ['NOEQUALS', '=value', '1BAD=x', 'BAD KEY=x', 'A-B=x']) {
    assert.throws(() => parseArgs(['--app-env', bad]), /needs KEY=VALUE/)
  }
  assert.throws(() => parseArgs(['--app-env']), /Unknown or incomplete option/)
  assert.throws(() => parseArgs(['--app-env', 'A=1', '--app-env', 'A=2']), /A was given twice/)
  assert.equal(Object.getPrototypeOf(parseArgs(['--app-env', '__proto__=x']).appEnv), null)
})

test('--app-env cannot point the probe at an existing database, mail server, URL or exporter', () => {
  for (const key of [
    'POSTGRES_JDBC_URL', 'POSTGRES_R2DBC_URL', 'POSTGRES_PASSWORD', 'SMTP_HOST', 'MAIL_APP_URL', 'MAIL_TRANSPORT',
    'OTEL_EXPORTER_OTLP_ENDPOINT', 'OTEL_EXPORTER_OTLP_PROTOCOL', 'SOME_SERVICE_URL', 'PEER_HOST', 'COLLECTOR_ENDPOINT',
  ]) {
    assert.throws(() => parseArgs(['--app-env', `${key}=x`]), /is refused/, key)
  }
  for (const key of ['JAVA_OPTS', 'MALLOC_ARENA_MAX', 'SERVER_OPTS']) assert.doesNotThrow(() => parseArgs(['--app-env', `${key}=x`]))
})

test('app environment lands in the override file as quoted scalars', () => {
  assert.equal(appEnvironmentLines({}), '')
  assert.equal(
    appEnvironmentLines({ JAVA_OPTS: '-Xmx64m # not a comment: "q"', MALLOC_ARENA_MAX: 2 }),
    '      JAVA_OPTS: "-Xmx64m # not a comment: \\"q\\""\n      MALLOC_ARENA_MAX: "2"\n',
  )
  // Compose interpolates $ in the override file; $$ is its escape for a literal dollar sign.
  assert.equal(appEnvironmentLines({ JAVA_OPTS: '-Dx=$HOME ${Y}' }), '      JAVA_OPTS: "-Dx=$$HOME $${Y}"\n')
})
