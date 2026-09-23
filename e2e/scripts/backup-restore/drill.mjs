import { randomBytes } from 'node:crypto'
import { execFile } from 'node:child_process'
import { chmod, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const executeFile = promisify(execFile)

export function drillProjectName(now = Date.now(), random = randomBytes(4).toString('hex')) {
  return `toadie-backup-drill-${now.toString(36)}-${random}`
}

export function assertDisposableProject(project) {
  if (!/^toadie-backup-drill-[a-z0-9]+-[a-f0-9]{8}$/.test(project)) {
    throw new Error(`Refusing non-disposable Compose project: ${project}`)
  }
}

export function composeDocument(password) {
  return `services:
  source:
    image: postgres:18-alpine
    environment: &database
      POSTGRES_DB: toadie
      POSTGRES_USER: toadie
      POSTGRES_PASSWORD: ${password}
    volumes:
      - source-data:/var/lib/postgresql
      - ./exchange:/exchange
    healthcheck: &healthcheck
      test: ["CMD-SHELL", "pg_isready -U toadie -d toadie"]
      interval: 1s
      timeout: 3s
      retries: 30
  restored:
    image: postgres:18-alpine
    environment: *database
    volumes:
      - restored-data:/var/lib/postgresql
      - ./exchange:/exchange
    healthcheck: *healthcheck
volumes:
  source-data:
  restored-data:
`
}

const fixture = String.raw`
CREATE TABLE drill_records (
  id BIGSERIAL PRIMARY KEY,
  label TEXT NOT NULL UNIQUE,
  payload JSONB NOT NULL
);
INSERT INTO drill_records (label, payload) VALUES
  ('zażółć', '{"kind":"unicode","active":true}'),
  ('quote''and-newline', jsonb_build_object('kind', 'escaping', 'lines', E'one\ntwo'));
CREATE VIEW drill_summary AS SELECT count(*) AS records FROM drill_records;
`

const fingerprint = String.raw`SELECT jsonb_build_object(
  'rows', (SELECT count(*) FROM drill_records),
  'digest', (SELECT md5(string_agg(id || ':' || label || ':' || payload::text, '|' ORDER BY id)) FROM drill_records),
  'viewRows', (SELECT records FROM drill_summary),
  'nextId', (SELECT last_value FROM drill_records_id_seq)
)::text;`

async function command(program, args, options = {}) {
  try {
    return await executeFile(program, args, {
      ...options,
      timeout: options.timeout ?? 120_000,
      maxBuffer: 4 * 1024 * 1024,
      encoding: 'utf8',
    })
  } catch (error) {
    const detail = typeof error.stderr === 'string' ? error.stderr.trim().slice(-2_000) : ''
    const output = typeof error.stdout === 'string' ? error.stdout.trim().slice(-2_000) : ''
    const reason = detail || output || error.message
    throw new Error(`${program} failed${reason ? `: ${reason}` : ''}`, { cause: error })
  }
}

async function main() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
  const project = drillProjectName()
  assertDisposableProject(project)
  const temporary = await mkdtemp(join(tmpdir(), 'toadie-backup-drill-'))
  await chmod(temporary, 0o700)
  const exchange = join(temporary, 'exchange')
  const composeFile = join(temporary, 'compose.yaml')
  // Both rootless PostgreSQL containers need the shared synthetic archive. The parent remains
  // private (0700), while this leaf is writable by the container uid on Linux Docker hosts.
  await mkdir(exchange, { mode: 0o777 })
  await chmod(exchange, 0o777)
  await writeFile(composeFile, composeDocument(randomBytes(24).toString('base64url')), { mode: 0o600 })
  await chmod(composeFile, 0o600)
  const compose = ['compose', '--project-name', project, '--file', composeFile]
  const environment = { ...process.env }
  delete environment.COMPOSE_FILE
  delete environment.COMPOSE_PROJECT_NAME

  // A detached `compose up` can outlive the Node process. Keep signal handling active until
  // `down` finishes, and abort only the current work command, never the cleanup command.
  const work = new AbortController()
  const onInterrupt = (signal) => work.abort(new Error(`Backup drill interrupted by ${signal}`))
  const onSigint = () => onInterrupt('SIGINT')
  const onSigterm = () => onInterrupt('SIGTERM')
  process.on('SIGINT', onSigint)
  process.on('SIGTERM', onSigterm)
  const runWork = (args, options = {}) => command('docker', args, {
    ...options, cwd: root, env: environment, signal: work.signal,
  })

  console.log(`Starting disposable backup drill ${project}`)
  try {
    await runWork([...compose, 'up', '--detach', '--wait', '--wait-timeout', '60'], { timeout: 90_000 })
    await runWork([...compose, 'exec', '-T', 'source', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'toadie', '-d', 'toadie', '--command', fixture])
    await runWork([...compose, 'exec', '-T', 'source', 'pg_dump', '--format=custom', '--no-owner', '--no-acl', '--file=/exchange/toadie.dump', '--username=toadie', '--dbname=toadie'])
    await runWork([...compose, 'exec', '-T', 'source', 'pg_restore', '--list', '/exchange/toadie.dump'])
    await runWork([...compose, 'exec', '-T', 'restored', 'pg_restore', '--exit-on-error', '--no-owner', '--no-acl', '--username=toadie', '--dbname=toadie', '/exchange/toadie.dump'])
    const query = (service) => runWork([
      ...compose, 'exec', '-T', service, 'psql', '--tuples-only', '--no-align', '--username=toadie', '--dbname=toadie', '--command', fingerprint,
    ])
    const [source, restored] = await Promise.all([query('source'), query('restored')])
    if (source.stdout.trim() !== restored.stdout.trim()) {
      throw new Error(`Restore verification mismatch: source=${source.stdout.trim()} restored=${restored.stdout.trim()}`)
    }
    console.log(`Backup/restore drill passed: ${restored.stdout.trim()}`)
  } finally {
    assertDisposableProject(project)
    try {
      await command('docker', [...compose, 'down', '--volumes', '--remove-orphans', '--timeout', '15'], {
        cwd: root, env: environment, timeout: 60_000,
      })
    } finally {
      process.off('SIGINT', onSigint)
      process.off('SIGTERM', onSigterm)
      await rm(temporary, { recursive: true, force: true })
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
