import assert from 'node:assert/strict'
import test from 'node:test'
import { parseArgs, probeId } from './run.mjs'

test('arguments parse sizes, the concurrency list and --keep', () => {
  assert.deepEqual(
    parseArgs(['--entities', '300', '--files', '250', '--concurrency', '1,3', '--keep']),
    { entities: 300, files: 250, concurrency: [1, 3], keep: true },
  )
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
