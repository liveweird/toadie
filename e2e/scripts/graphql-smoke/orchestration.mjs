import { chmod, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import {
  background, command, randomSecret, unusedPort, waitForReady, writePrivate,
} from './support.mjs'

export function composeNames(id) {
  return {
    project: id,
    postgres: `${id}-postgres`,
    mailpit: `${id}-mailpit`,
    app: `${id}-app`,
    image: `${id}:local`,
  }
}

export function composeEnvironment(environment = process.env) {
  const result = { ...environment }
  delete result.COMPOSE_FILE
  delete result.COMPOSE_PROJECT_NAME
  return result
}

export async function composeAppUrl({ root, compose, env, execute = command }) {
  const { stdout } = await execute('docker', [...compose, 'port', 'app', '8081'], { cwd: root, env })
  const match = stdout.trim().match(/127\.0\.0\.1:(\d+)$/)
  if (!match) throw new Error(`Could not determine Compose app port: ${stdout.trim()}`)
  return `http://127.0.0.1:${match[1]}`
}

export async function buildImage({ root, image, context }) {
  const contextArgs = context ? ['--context', context] : []
  await command('docker', [...contextArgs, 'build', '--tag', image, '.'], { cwd: root, timeout: 900_000 })
}

export async function startCompose({ root, temp, id, image, cleanup }) {
  const names = composeNames(id)
  const override = join(temp, 'compose.override.yaml')
  const password = 'changeme'
  await writePrivate(override, `services:
  postgres:
    container_name: ${names.postgres}
    ports: !override []
  mailpit:
    container_name: ${names.mailpit}
    ports: !override []
  app:
    container_name: ${names.app}
    image: ${image}
    environment:
      MAIL_APP_URL: http://localhost
    ports: !override
      - "127.0.0.1::8081"
`)
  const compose = ['compose', '--project-name', names.project, '-f', 'docker-compose.yaml', '-f', override]
  const env = composeEnvironment()
  cleanup.add(() => command('docker', [...compose, 'down', '--volumes', '--remove-orphans', '--timeout', '15'], {
    cwd: root, env, timeout: 90_000, ignoreSignal: true,
  }))
  await command('docker', [...compose, 'up', '--detach', '--no-build', '--wait', '--wait-timeout', '180'], {
    cwd: root, env, timeout: 240_000,
  })
  let baseUrl = await composeAppUrl({ root, compose, env })
  await waitForReady(baseUrl)
  return {
    password,
    async targets() { return [baseUrl] },
    async restart() {
      await command('docker', [...compose, 'restart', '--timeout', '15', 'app'], { cwd: root, env, timeout: 90_000 })
      baseUrl = await composeAppUrl({ root, compose, env })
      await waitForReady(baseUrl)
      return [baseUrl]
    },
  }
}

async function kubectlJson(root, context, file) {
  const { stdout } = await command('kubectl', [
    '--context', context, 'create', '--dry-run=client', '--validate=false', '--output=json', '--filename', file,
  ], {
    cwd: root,
  })
  return JSON.parse(stdout)
}

function inNamespace(resource, namespace) {
  resource.metadata.namespace = namespace
  return resource
}

export async function derivedKubernetesResources({ root, context, namespace, image, secrets }) {
  const [pvc, postgresDeployment, postgresService, appDeployment, appService] = await Promise.all([
    kubectlJson(root, context, 'k8s/postgres-data-persistentvolumeclaim.yaml'),
    kubectlJson(root, context, 'k8s/postgres-deployment.yaml'),
    kubectlJson(root, context, 'k8s/postgres-service.yaml'),
    kubectlJson(root, context, 'k8s/app-deployment.yaml'),
    kubectlJson(root, context, 'k8s/app-service.yaml'),
  ])
  appDeployment.spec.replicas = 2
  const appContainer = appDeployment.spec.template.spec.containers.find((container) => container.name === 'toadie-app')
  appContainer.image = image
  appContainer.imagePullPolicy = 'Never'
  return [
    { apiVersion: 'v1', kind: 'Secret', metadata: { name: 'toadie-secrets', namespace }, type: 'Opaque', stringData: secrets },
    { apiVersion: 'v1', kind: 'ConfigMap', metadata: { name: 'toadie-config', namespace }, data: { INTEGRATION_ENABLED: 'true' } },
    inNamespace(pvc, namespace),
    inNamespace(postgresDeployment, namespace),
    inNamespace(postgresService, namespace),
    inNamespace(appDeployment, namespace),
    inNamespace(appService, namespace),
  ]
}

async function startPodForwards({ context, namespace, cleanup }) {
  const { stdout } = await command('kubectl', [
    '--context', context, '--namespace', namespace, 'get', 'pods',
    '--selector', 'io.kompose.service=app', '--output=json',
  ])
  const pods = JSON.parse(stdout).items
    .filter((pod) => !pod.metadata.deletionTimestamp)
    .filter((pod) => pod.status.conditions?.some((condition) => condition.type === 'Ready' && condition.status === 'True'))
  if (pods.length !== 2) throw new Error(`Expected two ready app replicas, found ${pods.length}`)
  const forwards = []
  const urls = []
  for (const pod of pods) {
    const port = await unusedPort()
    const process = background('kubectl', [
      '--context', context, '--namespace', namespace, 'port-forward', `pod/${pod.metadata.name}`,
      `${port}:8081`, '--address', '127.0.0.1',
    ])
    forwards.push(process)
    cleanup.add(() => process.stop())
    const url = `http://127.0.0.1:${port}`
    await waitForReady(url, { timeout: 30_000 })
    process.assertRunning()
    urls.push(url)
  }
  return { urls, async stop() { await Promise.all(forwards.map((forward) => forward.stop())) } }
}

export async function startKubernetes({ root, temp, id, image, context, cleanup }) {
  if (context !== 'orbstack') throw new Error('Kubernetes smoke is restricted to --context orbstack')
  const namespace = id
  const password = `A1!${randomSecret(24)}`
  const resourcesPath = join(temp, 'kubernetes-resources.json')
  await writePrivate(resourcesPath, JSON.stringify({
    apiVersion: 'v1', kind: 'List', items: await derivedKubernetesResources({
      root, context, namespace, image,
      secrets: {
        JWT_SECRET: randomSecret(48),
        ADMIN_INITIAL_PASSWORD: password,
        POSTGRES_PASSWORD: randomSecret(32),
        POSTGRES_USER: 'toadie',
      },
    }),
  }))
  await command('kubectl', ['--context', context, 'create', 'namespace', namespace], { timeout: 30_000 })
  // Arm deletion only after create succeeds. A collision must never delete a namespace we did not create.
  cleanup.add(() => command('kubectl', [
    '--context', context, 'delete', 'namespace', namespace, '--ignore-not-found=true', '--wait=true', '--timeout=120s',
  ], { timeout: 135_000, ignoreSignal: true }))
  await command('kubectl', ['--context', context, 'apply', '--filename', resourcesPath], {
    timeout: 60_000, sensitive: true,
  })
  await command('kubectl', [
    '--context', context, '--namespace', namespace, 'rollout', 'status', 'deployment/postgres', '--timeout=180s',
  ], { timeout: 200_000 })
  await command('kubectl', [
    '--context', context, '--namespace', namespace, 'rollout', 'status', 'deployment/app', '--timeout=240s',
  ], { timeout: 260_000 })
  let activeForwards = await startPodForwards({ context, namespace, cleanup })
  return {
    password,
    async targets() { return activeForwards.urls },
    async restart() {
      await activeForwards.stop()
      await command('kubectl', [
        '--context', context, '--namespace', namespace, 'rollout', 'restart', 'deployment/app',
      ])
      await command('kubectl', [
        '--context', context, '--namespace', namespace, 'rollout', 'status', 'deployment/app', '--timeout=240s',
      ], { timeout: 260_000 })
      activeForwards = await startPodForwards({ context, namespace, cleanup })
      return activeForwards.urls
    },
  }
}

export async function loadSamples({ root, temp, baseUrl, password }) {
  const shimDirectory = join(temp, 'bin')
  await mkdir(shimDirectory, { mode: 0o700 })
  const curlShim = join(shimDirectory, 'curl')
  await writePrivate(curlShim, '#!/bin/sh\nexec /usr/bin/curl -H "X-Forwarded-Proto: https" "$@"\n')
  await chmod(curlShim, 0o700)
  const env = {
    ...process.env,
    PATH: `${shimDirectory}:${process.env.PATH}`,
    TOADIE_URL: baseUrl,
    TOADIE_PASSWORD: password,
  }
  for (const area of ['blueprints', 'entities']) {
    await command('bash', [`sample-data/port/commerce-payments/${area}/load.sh`], {
      cwd: root, env, timeout: 180_000,
    })
  }
}
