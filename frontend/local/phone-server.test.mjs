import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'node:net'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function unusedPort() {
  const server = createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const port = String(server.address().port)
  await new Promise(resolve => server.close(resolve))
  return port
}
async function waitFor(check) {
  for (let i = 0; i < 120; i++) { const value = await check(); if (value) return value; await delay(100) }
  throw new Error('Timed out waiting for local phones')
}

test('two real local phone processes', { timeout: 45000 }, async t => {
  const directory = mkdtempSync(join(tmpdir(), 'pandastic-phones-test-'))
  const basic = await unusedPort(), helper = await unusedPort()
  let processPair, output = ''
  const url = (port, path) => `http://127.0.0.1:${port}/__phone/${path}`
  const get = async port => (await fetch(url(port, 'state'))).json()
  const post = async (port, path, body) => {
    const response = await fetch(url(port, path), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    return { status: response.status, body: await response.json() }
  }
  const update = (port, operation, value) => post(port, 'update', { operation, value })
  const send = (port, number, body, id = crypto.randomUUID()) => post(port, 'send', { id, number, body })
  async function start() {
    processPair = spawn(process.execPath, ['local/run-pair.mjs'], { cwd, env: { ...process.env, BASIC_PORT: basic, CAPABLE_PORT: helper, PANDASTIC_PHONE_STATE_DIR: directory }, stdio: ['ignore', 'pipe', 'pipe'] })
    processPair.stdout.on('data', chunk => { output += chunk })
    processPair.stderr.on('data', chunk => { output += chunk })
    await waitFor(async () => {
      if (processPair.exitCode !== null) throw new Error(output)
      try { await get(basic); await get(helper); return true } catch { return false }
    })
  }
  async function stop() {
    if (processPair.exitCode === null) {
      const finished = new Promise(resolve => processPair.once('exit', resolve))
      processPair.kill('SIGTERM')
      await finished
    }
  }
  t.after(async () => { await stop(); rmSync(directory, { recursive: true, force: true }) })
  await start()

  await t.test('ports identify the phones and configure their roles', async () => {
    assert.equal((await get(basic)).number, basic)
    assert.equal((await get(basic)).mode, 'lite')
    assert.equal((await get(helper)).mode, 'capable')
    assert.equal((await get(helper)).hub.enabled, true)
    assert.equal((await get(basic)).chat.peer, helper)
  })
  await t.test('question crosses HTTP and the SQLite price reply comes back', async () => {
    assert.equal((await send(basic, helper, 'P 1 12000')).body.ok, true)
    const state = await waitFor(async () => { const value = await get(basic); return value.chat.messages.length === 2 && value })
    assert.equal(state.chat.messages[0].status, 'sent')
    assert.equal(state.chat.messages[1].direction, 'in')
    assert.match(state.chat.messages[1].body, /15,500/)
    assert.match(state.chat.messages[1].body, /23%/)
    assert.match(state.chat.messages[1].body, /2026-08/)
    assert.match(state.chat.messages[1].body, /Onyesho/)
    // The helper answers in the background: the question and its reply are in the helper log, not its chat.
    const hub = await get(helper)
    assert.equal(hub.chat.messages.length, 0)
    assert.equal(hub.hub.recent.at(-1).status, 'sent')
    assert.match(hub.hub.recent.at(-1).reply, /15,500/)
  })
  await t.test('manual replies travel in the reverse direction without an echo', async () => {
    assert.equal((await send(helper, basic, 'Hello Mama')).body.ok, true)
    await delay(400)
    assert.equal((await get(basic)).chat.messages.at(-1).body, 'Hello Mama')
    assert.equal((await get(helper)).chat.messages.length, 1)
  })
  await t.test('English helper and symptom safety wording', async () => {
    await update(helper, 'lang', 'en')
    await send(basic, helper, 'coffee leaves have yellow spots')
    const state = await waitFor(async () => { const value = await get(basic); return value.chat.messages.at(-1).direction === 'in' && value })
    assert.match(state.chat.messages.at(-1).body, /Not sure from words alone.*Do not spray yet.*extension officer/)
  })
  await t.test('messages arrive while auto replies are switched off', async () => {
    await update(helper, 'enabled', false)
    const count = (await get(basic)).chat.messages.length
    await send(basic, helper, '?')
    await delay(400)
    assert.equal((await get(basic)).chat.messages.length, count + 1)
    assert.equal((await get(helper)).chat.messages.at(-1).body, '?')
  })
  await t.test('repeated send requests are delivered only once', async () => {
    const count = (await get(helper)).chat.messages.length
    const id = crypto.randomUUID()
    assert.equal((await send(basic, helper, 'One message only', id)).body.ok, true)
    assert.equal((await send(basic, helper, 'One message only', id)).body.ok, true)
    assert.equal((await get(helper)).chat.messages.length, count + 1)
  })
  await t.test('allowlist blocks auto replies and basic mode cannot enable a hub', async () => {
    await update(helper, 'contacts', [])
    await update(helper, 'enabled', true)
    assert.equal((await get(helper)).hub.enabled, false)
    await update(basic, 'enabled', true)
    assert.equal((await get(basic)).hub.enabled, false)
    await update(helper, 'contacts', [{ name: 'Mama', number: basic }])
    await update(helper, 'enabled', true)
  })
  await t.test('unknown destinations and invalid message bodies fail', async () => {
    assert.equal((await send(basic, '12345', 'Hello')).body.error, 'peer')
    assert.equal((await send(basic, helper, ' ')).status, 400)
    assert.equal((await send(basic, helper, 'x'.repeat(481))).status, 400)
  })
  await t.test('peer deliveries require the pair token and cross-origin writes are blocked', async () => {
    assert.equal((await post(helper, 'deliver', { from: basic, id: 'fake', body: 'Fake SMS', automatic: false })).status, 403)
    const response = await fetch(url(helper, 'update'), { method: 'POST', headers: { Origin: 'https://example.com', 'Content-Type': 'application/json' }, body: JSON.stringify({ operation: 'enabled', value: false }) })
    assert.equal(response.status, 403)
  })
  await t.test('automatic replies do not loop when both phones are capable', async () => {
    await update(basic, 'mode', 'capable')
    await update(basic, 'enabled', true)
    const count = (await get(basic)).chat.messages.length
    await send(basic, helper, '?')
    await waitFor(async () => (await get(basic)).chat.messages.length === count + 2)
    await delay(700)
    assert.equal((await get(basic)).chat.messages.length, count + 2)
    await update(basic, 'mode', 'lite')
  })
  await t.test('histories and settings survive a restart of both processes', async () => {
    const before = await get(basic)
    await stop(); await start()
    assert.deepEqual((await get(basic)).chat.messages, before.chat.messages)
    assert.equal((await get(helper)).hub.lang, 'en')
    assert.equal((await get(basic)).mode, 'lite')
  })
  await t.test('clearing one phone history preserves the peer history', async () => {
    await update(basic, 'clearChat')
    assert.equal((await get(basic)).chat.messages.length, 0)
    assert.ok((await get(helper)).chat.messages.length > 0)
  })
  await t.test('the hub stops auto replying after twelve questions in an hour', async () => {
    await update(helper, 'clearHub')
    for (let i = 0; i < 13; i++) await send(basic, helper, '?')
    const state = await get(helper)
    assert.equal(state.hub.recent.length, 13)
    assert.equal(state.hub.recent.at(-1).status, 'rate_limited')
    await waitFor(async () => (await get(helper)).hub.answeredToday === 12)
  })
})
