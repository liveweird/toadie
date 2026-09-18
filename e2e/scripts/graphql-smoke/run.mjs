#!/usr/bin/env node
import { join } from 'node:path'
import {
  assertLiveFindings, assertSeedParity, createIntegrationClient, login, revokeAndAssert,
} from './api-check.mjs'
import { buildImage, loadSamples, startCompose, startKubernetes } from './orchestration.mjs'
import {
  cleanupStack, command, privateTempDir, removeTemp, repositoryRoot, runId, setCommandSignal,
} from './support.mjs'

function usage() {
  return `Usage: node e2e/scripts/graphql-smoke/run.mjs compose
       node e2e/scripts/graphql-smoke/run.mjs kubernetes --context orbstack

Creates an isolated deployment, loads the committed Port sample, verifies REST/GraphQL parity
before and after an app restart, proves live findings and key revocation, then removes everything
it created. Kubernetes is deliberately restricted to the explicit orbstack context.`
}

function parseArgs(argv) {
  if (argv.includes('--help') || argv.includes('-h')) return { help: true }
  const [target, ...rest] = argv
  if (!['compose', 'kubernetes'].includes(target)) throw new Error(usage())
  const result = { target }
  for (let index = 0; index < rest.length; index += 1) {
    const option = rest[index]
    if (option === '--context' && target === 'kubernetes' && rest[index + 1]) result.context = rest[++index]
    else throw new Error(`Unknown or incomplete option: ${option}\n\n${usage()}`)
  }
  if (target === 'kubernetes' && result.context !== 'orbstack') {
    throw new Error('Kubernetes smoke requires explicit --context orbstack')
  }
  return result
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
  const id = runId()
  const image = `${id}:local`
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

  let primaryFailure
  try {
    const ownedResource = options.target === 'compose'
      ? `Compose project ${id}`
      : `Kubernetes namespace ${id} in context ${options.context}`
    console.log(`Owned GraphQL smoke resources: ${ownedResource}; image ${image}`)
    cleanup.add(() => removeOwnedImage(image, options.target === 'kubernetes' ? options.context : undefined))
    console.log('Stage: building image')
    await buildImage({ root, image, context: options.target === 'kubernetes' ? options.context : undefined })
    const deployment = options.target === 'compose'
      ? await startCompose({ root, temp, id, image, cleanup })
      : await startKubernetes({ root, temp, id, image, context: options.context, cleanup })
    console.log('Stage: deployment ready')
    let targets = await deployment.targets()
    console.log('Stage: loading Port sample')
    await loadSamples({ root, temp, baseUrl: targets[0], password: deployment.password })
    const jwt = await login(targets[0], deployment.password)
    const created = await createIntegrationClient(targets[0], jwt, `deployment smoke ${id}`)
    const schemaPath = join(root, 'server/src/main/resources/graphql/schema.graphqls')

    console.log('Stage: checking parity and live findings before restart')
    await assertSeedParity(targets, jwt, created.apiKey, schemaPath)
    await assertLiveFindings(targets, jwt, created.apiKey, id)
    console.log('Stage: restarting app deployment')
    targets = await deployment.restart()
    console.log('Stage: checking parity after restart')
    await assertSeedParity(targets, jwt, created.apiKey, schemaPath)
    console.log('Stage: revoking integration key and checking every replica')
    await revokeAndAssert(targets, jwt, created.client.id, created.apiKey)
    console.log(`GraphQL deployment smoke passed for ${options.target}: 11 blueprints, 59 entities, exact SDL, live findings, restart persistence, and revocation.`)
  } catch (error) {
    primaryFailure = error
    throw error
  } finally {
    process.removeListener('SIGINT', onSignal)
    process.removeListener('SIGTERM', onSignal)
    console.log(`Stage: cleaning owned run ${id}`)
    try {
      await cleanup.run()
    } catch (cleanupFailure) {
      if (!primaryFailure) throw cleanupFailure
      console.error(`Cleanup for owned run ${id} also failed: ${errorMessage(cleanupFailure)}`)
    }
  }
}

async function removeOwnedImage(image, context) {
  const contextArgs = context ? ['--context', context] : []
  try {
    await command('docker', [...contextArgs, 'image', 'rm', '--force', image], { timeout: 60_000, ignoreSignal: true })
  } catch (error) {
    const stderr = error.cause?.stderr ?? ''
    if (/No such image|No such object/i.test(stderr)) return
    throw error
  }
}

function errorMessage(error) {
  if (error instanceof AggregateError) return error.errors.map((cause) => cause.message).join('; ')
  return error.message
}

main().catch((error) => {
  console.error(errorMessage(error))
  if (!process.exitCode) process.exitCode = 1
})
