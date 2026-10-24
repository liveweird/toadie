#!/usr/bin/env node
import { randomBytes } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { login } from '../graphql-smoke/api-check.mjs'
import {
  buildImage, composeNames, loadSamples, removeImage, startCompose,
} from '../graphql-smoke/orchestration.mjs'
import {
  cleanupStack, privateTempDir, removeTemp, repositoryRoot, setCommandSignal,
} from '../graphql-smoke/support.mjs'
import { activeTotal, importDocuments } from './load.mjs'
import { appMemory, containerState, measureEndpoints } from './measure.mjs'
import { renderReport } from './report.mjs'
import {
  catalogReplicator, entityReplicator, GROUP, planCatalogReplicas, planEntityReplicas,
} from './replicate.mjs'

const DEFAULT_CONCURRENCY = [1, 2, 3, 5, 10]

export function usage() {
  return `Usage: npm run probe:capacity -- --entities N --files N [--concurrency 1,2,3,5,10] [--keep]

Provisions its own disposable Compose project (random name, loopback port chosen at runtime),
builds the current checkout, loads the 15 sample blueprints, replicates the Port entity sample
and the Backstage catalog sample up to the requested sizes (the server's entity caps apply:
2,000 per blueprint, 10,000 total), then fires K simultaneous whole-workspace reads for every K
in --concurrency and prints a markdown report. It never accepts an existing URL, project or
volume. The project and its volume are removed at the end; --keep leaves them up and prints
the exact removal command.`
}

export function parseArgs(argv) {
  if (argv.includes('--help') || argv.includes('-h')) return { help: true }
  const options = { entities: 1000, files: 1000, concurrency: DEFAULT_CONCURRENCY, keep: false }
  const count = (name, value, minimum) => {
    const parsed = Number(value)
    if (!Number.isInteger(parsed) || parsed < minimum) throw new Error(`${name} needs an integer >= ${minimum}\n\n${usage()}`)
    return parsed
  }
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index]
    const value = argv[index + 1]
    if (option === '--keep') options.keep = true
    else if (option === '--entities' && value !== undefined) options.entities = count(option, argv[++index], 0)
    else if (option === '--files' && value !== undefined) options.files = count(option, argv[++index], 0)
    else if (option === '--concurrency' && value !== undefined) {
      options.concurrency = argv[++index].split(',').map((item) => count(option, item, 1))
    } else throw new Error(`Unknown or incomplete option: ${option}\n\n${usage()}`)
  }
  return options
}

export function probeId(now = Date.now(), random = randomBytes(4).toString('hex')) {
  return `toadie-cap-${now.toString(36)}-${random}`
}

const numbered = (directory) => readdirSync(directory).filter((file) => /^\d\d-.*\.json$/.test(file)).sort()
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'))

async function loadEntities({ baseUrl, token, root, entities }) {
  const port = join(root, 'sample-data/port/commerce-payments')
  const replicator = entityReplicator(
    numbered(join(port, 'blueprints')).map((file) => readJson(join(port, 'blueprints', file))),
    numbered(join(port, 'entities')).map((file) => readJson(join(port, 'entities', file))),
  )
  const plan = planEntityReplicas(replicator, entities)
  const notes = [`entities: ${plan.replicas} replicas planned (${plan.total} documents), planning stopped by ${plan.stoppedBy}`]
  for (let from = 0; from < plan.replicas; from += GROUP) {
    const result = await importDocuments({
      baseUrl, token, path: '/api/v1/entities/import', field: 'documents',
      documents: replicator.generate(from, Math.min(from + GROUP, plan.replicas)),
    })
    for (const failure of result.failures) notes.push(`entities: ${failure}`)
    if (result.refused) {
      notes.push(`entities: the server refused further creates (${result.refused})`)
      break
    }
  }
  return notes
}

async function loadFiles({ baseUrl, token, root, files }) {
  const source = readFileSync(join(root, 'sample-data/backstage/commerce-payments/catalog-info.yaml'), 'utf8')
  const replicator = catalogReplicator(YAML.parseAllDocuments(source).map((document) => document.toJS()).filter(Boolean))
  const plan = planCatalogReplicas(replicator, files)
  const notes = [`files: ${plan.replicas} replicas planned (${plan.total} documents)`]
  const result = await importDocuments({
    baseUrl, token, path: '/api/v1/files/import', field: 'files', documents: replicator.generate(0, plan.replicas),
  })
  for (const failure of result.failures) notes.push(`files: ${failure}`)
  if (result.refused) notes.push(`files: the server refused further creates (${result.refused})`)
  return notes
}

async function safely(read) {
  try {
    return await read()
  } catch {
    return {}
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  if (options.help) {
    console.log(usage())
    return
  }
  const root = repositoryRoot(import.meta.url)
  const temp = await privateTempDir()
  const cleanup = cleanupStack()
  cleanup.add(() => removeTemp(temp))
  const id = probeId()
  const names = composeNames(id)
  const abortController = new AbortController()
  setCommandSignal(abortController.signal)
  let interrupted = false
  const onSignal = (signal) => {
    if (interrupted) return
    interrupted = true
    process.exitCode = signal === 'SIGINT' ? 130 : 143
    abortController.abort(new Error(`Interrupted by ${signal}`))
  }
  process.once('SIGINT', onSignal)
  process.once('SIGTERM', onSignal)

  // The project, its volume and the image are removed on any failure; --keep spares them only
  // after a fully successful run, so a half-started stack is never left behind.
  const stack = cleanupStack()
  let succeeded = false
  cleanup.add(async () => {
    if (!(options.keep && succeeded)) await stack.run()
  })
  let primaryFailure
  try {
    console.log(`Owned capacity probe resources: Compose project ${id}; image ${names.image}`)
    stack.add(() => removeImage(names.image))
    console.log('Stage: building image')
    await buildImage({ root, image: names.image })
    const deployment = await startCompose({ root, temp, id, image: names.image, cleanup: stack })
    const [baseUrl] = await deployment.targets()
    console.log('Stage: deployment ready; loading the sample blueprints')
    await loadSamples({
      root, temp, baseUrl, password: deployment.password, areas: ['blueprints'], extraEnv: { TOADIE_SOURCE_BASE: '' },
    })
    const { value: token } = await login(baseUrl, deployment.password)

    const notes = []
    if (options.entities > 0) {
      console.log(`Stage: replicating up to ${options.entities} entities`)
      notes.push(...await loadEntities({ baseUrl, token, root, entities: options.entities }))
    }
    if (options.files > 0) {
      console.log(`Stage: replicating up to ${options.files} catalog files`)
      notes.push(...await loadFiles({ baseUrl, token, root, files: options.files }))
    }
    const workspace = {
      entities: await activeTotal(baseUrl, token, '/api/v1/entities'),
      files: await activeTotal(baseUrl, token, '/api/v1/files'),
      notes,
    }

    console.log(`Stage: measuring (concurrency ${options.concurrency.join(',')})`)
    const before = await safely(() => appMemory(names.app))
    const cells = await measureEndpoints({ baseUrl, token, concurrencies: options.concurrency })
    const after = await safely(() => appMemory(names.app))
    const state = await safely(() => containerState(names.app))
    console.log(`\n${renderReport({
      workspace, cells, memory: { before, after, restartCount: state.restartCount ?? 'unknown', oomKilled: state.oomKilled },
    })}`)
    succeeded = true
    if (options.keep) {
      console.log(`Kept ${id} (--keep). Remove it with:\n  docker compose -p ${id} down -v --remove-orphans\n  docker image rm ${names.image}`)
    }
  } catch (error) {
    primaryFailure = error
    throw error
  } finally {
    process.removeListener('SIGINT', onSignal)
    process.removeListener('SIGTERM', onSignal)
    console.log(options.keep && succeeded ? 'Stage: cleaning temporary files only' : `Stage: cleaning owned run ${id}`)
    try {
      await cleanup.run()
    } catch (cleanupFailure) {
      if (!primaryFailure) throw cleanupFailure
      console.error(`Cleanup for owned run ${id} also failed: ${errorMessage(cleanupFailure)}`)
    }
  }
}

function errorMessage(error) {
  if (error instanceof AggregateError) return error.errors.map((cause) => cause.message).join('; ')
  return error.message
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(errorMessage(error))
    if (!process.exitCode) process.exitCode = 1
  })
}
