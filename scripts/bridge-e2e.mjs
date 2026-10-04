#!/usr/bin/env node
// End-to-end test of the UI <-> native connectors on a running emulator or phone with a debug build.
// It drives the real window.PandasticNative inside the app's WebView (Chrome DevTools protocol over adb),
// so it checks the Java bridge, BrainHost, the knowledge base, the classifier and the JSON the UI reads.
//
//   node scripts/bridge-e2e.mjs            # bridge, questions, photo, hub settings (restored afterwards)
//   node scripts/bridge-e2e.mjs --sms      # also an SMS round trip through the hub (grants SMS permissions,
//                                          # turns the hub on if needed, restores its state at the end)
//   node scripts/bridge-e2e.mjs --photo a.jpg --photo b.jpg   # also real photos, shrunk exactly like the UI does;
//                                          # a file named <label>__*.jpg (e.g. coffee_rust__x.jpg) is also scored
//   ADB=/path/to/adb DEVICE=emulator-5554 node scripts/bridge-e2e.mjs
//
// Needs Node 22+ (global WebSocket). Exit code 1 if any check fails.
import { readFileSync } from 'node:fs'
import { basename } from 'node:path'
import { PACKAGE, adbFor, connectApp, sleep } from './lib/devtools.mjs'

const withSms = process.argv.includes('--sms')
const photos = process.argv.flatMap((arg, i) => arg === '--photo' ? [process.argv[i + 1]] : [])
const adb = adbFor(process.env.DEVICE)

// Every status the Brain may send (Decision.java) plus the bridge's own ERROR.
const STATUSES = ['CONFIDENT', 'UNCERTAIN', 'UNSUPPORTED', 'RETAKE', 'ASK_CROP', 'TEXT_ONLY', 'PRICE', 'PRICE_STALE',
  'NO_DATA', 'HELP', 'ERROR']
// The bridge methods the UI may call: read from `type Native` in frontend/src/native.ts, so this test follows
// whatever the frontend declares.
const nativeTs = readFileSync(new URL('../frontend/src/native.ts', import.meta.url), 'utf8')
const BRIDGE = [...(nativeTs.match(/type Native = \{([\s\S]*?)\n\}/)?.[1] ?? '').matchAll(/^\s+(\w+)\(/gm)].map(m => m[1])

// ---- results ---------------------------------------------------------------------------------

const results = []
async function check(name, fn) {
  const started = Date.now()
  try {
    const note = await fn()
    results.push({ name, ok: true, ms: Date.now() - started, note: note ?? '' })
  } catch (e) {
    results.push({ name, ok: false, ms: Date.now() - started, note: e.message })
  }
  const r = results.at(-1)
  console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}  ${r.ms} ms  ${r.note}`)
}
function expect(condition, message) { if (!condition) throw new Error(message) }
const isNum = v => typeof v === 'number' && Number.isFinite(v)
const isStrOrNull = v => v === null || v === undefined || typeof v === 'string'

/** The fields the UI reads (frontend/src/native.ts type Decision), with the types it expects. */
function expectDecision(d, where) {
  expect(d && typeof d === 'object', `${where}: not an object`)
  expect(STATUSES.includes(d.status), `${where}: unknown status ${d.status}`)
  expect(typeof d.escalate === 'boolean', `${where}: escalate is ${typeof d.escalate}`)
  for (const key of ['intent', 'title', 'message', 'translation', 'crop', 'label', 'runner_up', 'advice_sms',
    'advice_long', 'quality', 'error']) expect(isStrOrNull(d[key]), `${where}: ${key} is ${typeof d[key]}`)
  for (const key of ['prob', 'runner_up_prob']) expect(d[key] == null || isNum(d[key]), `${where}: ${key} is ${d[key]}`)
  if (d.stub !== undefined) expect(typeof d.stub === 'boolean', `${where}: stub is ${typeof d.stub}`)
  if (d.source) expect(typeof d.source.id === 'string' && isStrOrNull(d.source.title), `${where}: bad source`)
  if (d.price) {
    const p = d.price
    expect(typeof p.commodity === 'string' && isNum(p.low) && isNum(p.high) && p.low <= p.high, `${where}: bad price range`)
    expect(typeof p.currency === 'string' && typeof p.unit === 'string' && typeof p.date === 'string', `${where}: bad price units`)
    expect(typeof p.source_id === 'string', `${where}: price without source_id`)
    expect(p.offer == null || isNum(p.offer), `${where}: offer is ${p.offer}`)
    expect(p.gap_pct == null || isNum(p.gap_pct), `${where}: gap_pct is ${p.gap_pct}`)
    expect(p.stale === undefined || typeof p.stale === 'boolean', `${where}: stale is ${p.stale}`)
  }
  // Safety rules the UI relies on (contracts §2): only a photo can be CONFIDENT, and it never escalates.
  if (d.status === 'CONFIDENT') expect(d.escalate === false && typeof d.label === 'string', `${where}: CONFIDENT shape`)
  if (['UNCERTAIN', 'UNSUPPORTED', 'RETAKE', 'ERROR'].includes(d.status)) expect(d.escalate, `${where}: ${d.status} must escalate`)
}

// ---- checks ----------------------------------------------------------------------------------

// Node's WebSocket does not keep the process alive on its own: without this, a slow answer ends the run.
const keepAlive = setInterval(() => {}, 1000)
const page = await connectApp(process.env.DEVICE)
const { evaluate } = page
const ask = (text, lang) => evaluate(`__e2e.reply('ask', [${JSON.stringify(text)}, ${JSON.stringify(lang)}], 60000)`)

await check('bridge exposes every method native.ts declares', async () => {
  const missing = await evaluate(`${JSON.stringify(BRIDGE)}.filter(m => typeof window.PandasticNative?.[m] !== 'function')`)
  expect(missing.length === 0, `missing: ${missing.join(', ')}`)
  return page.url
})

// ---- phone modes: a Basic ('lite') phone never runs models and talks to a capable phone by SMS ----
const isStr = v => typeof v === 'string'
const phone = JSON.parse(await evaluate('PandasticNative.phoneInfo()'))
// Switching to Basic mode turns the SMS helper off; remember it so the end of the run can restore it.
const helperWasOn = JSON.parse(await evaluate('PandasticNative.hubStatus()')).enabled
await check('phoneInfo(): mode and RAM', async () => {
  expect(['', 'lite', 'capable'].includes(phone.mode ?? ''), `mode ${phone.mode}`)
  expect(isNum(phone.totalRamMb) && phone.totalRamMb > 0, `totalRamMb ${phone.totalRamMb}`)
  return `mode '${phone.mode}', ${phone.totalRamMb} MB RAM`
})
const setMode = mode => evaluate(`__e2e.hubEvent(() => PandasticNative.setPhoneMode('${mode}'), 5000)`)

let chat
await check('chatStatus(): every field the chat screen reads', async () => {
  chat = JSON.parse(await evaluate('PandasticNative.chatStatus()'))
  expect(isStr(chat.peer) && typeof chat.smsPermission === 'boolean' && Array.isArray(chat.messages), 'shape')
  expect(chat.messages.every(m => isNum(m.id) && isStr(m.number) && isStr(m.body) && ['in', 'out'].includes(m.direction)
    && isNum(m.time) && isStr(m.status)), 'message shape')
  return `peer '${chat.peer}', ${chat.messages.length} messages`
})
await check('setSmsPeer: a valid number is kept and announced; a bad one is refused', async () => {
  const detail = await evaluate(`__e2e.event('pandastic:chat', () => PandasticNative.setSmsPeer('+256 700 999 123'), 5000)`)
  expect(detail.peer.replace(/[^0-9]/g, '') === '256700999123', `peer ${detail.peer}`)
  await evaluate(`PandasticNative.setSmsPeer('not a number')`)
  const now = JSON.parse(await evaluate('PandasticNative.chatStatus()'))
  expect(now.peer === detail.peer, `bad number replaced the peer: ${now.peer}`)
})
await evaluate(`PandasticNative.setSmsPeer(${JSON.stringify(chat?.peer ?? '')})`).catch(() => undefined)

await check('Basic phone: no models, questions and photos politely refused', async () => {
  await setMode('lite')
  expect(JSON.parse(await evaluate('PandasticNative.phoneInfo()')).mode === 'lite', 'mode not saved')
  expect(await evaluate('PandasticNative.info()') === '{}', 'info() should be empty')
  const d = await ask('P 1 12000', 'sw')
  expectDecision(d, 'lite ask')
  expect(d.status === 'ERROR' && d.error === 'phone_mode', `${d.status} ${d.error}`)
  const hubNow = JSON.parse(await evaluate('PandasticNative.hubStatus()'))
  expect(hubNow.enabled === false, 'the SMS helper must be off on a Basic phone')
  const r = await evaluate(`__e2e.result('__pandasticModelReply', 'manageModels', ['load'], 5000)`)
  expect(r.ok === false && r.error === 'unavailable', `manageModels on lite: ${JSON.stringify(r)}`)
})
await setMode('capable')

let info
await check('info(): answers at once, even while models load', async () => {
  const started = Date.now()
  info = JSON.parse(await evaluate('PandasticNative.info()'))
  expect(Date.now() - started < 1000, `info() blocked the page for ${Date.now() - started} ms`)
  return info.loading ? 'models still loading' : 'all loaded'
})

await check('info(): knowledge base, classifier (and LLM, if side-loaded) finish loading', async () => {
  const started = Date.now()
  while (info.loading && Date.now() - started < 120000) {
    await sleep(500)
    info = JSON.parse(await evaluate('PandasticNative.info()'))
  }
  expect(!info.loading, 'still loading after 120 s')
  expect(info.brain === true, `brain ${info.brain}, brainError ${info.brainError}`)
  expect(typeof info.classifier === 'string', `classifier ${info.classifier}, error ${info.classifierError}`)
  expect(typeof info.classifierStub === 'boolean', 'classifierStub not boolean')
  return `${Math.round((Date.now() - started) / 1000)} s: classifier ${info.classifier}${info.classifierStub ? ' (STUB)' : ''}, llm ${info.llm ?? 'none (keywords only)'}`
})

await check('modelStatus(): every field the Models screen reads', async () => {
  const m = JSON.parse(await evaluate('PandasticNative.modelStatus()'))
  const file = (f, extra) => f && typeof f.installed === 'boolean' && typeof f.loaded === 'boolean' && isNum(f.bytes) && extra(f)
  expect(file(m.classifier, f => isStr(f.version) && typeof f.stub === 'boolean'), `classifier ${JSON.stringify(m.classifier)}`)
  expect(file(m.language, f => isStr(f.name) && typeof f.runtimeAvailable === 'boolean'), `language ${JSON.stringify(m.language)}`)
  expect(file(m.knowledge, () => true), `knowledge ${JSON.stringify(m.knowledge)}`)
  expect(typeof m.busy === 'boolean', 'busy')
  expect(m.classifier.loaded && m.knowledge.loaded, 'loaded models not reported as loaded')
  return `classifier ${m.classifier.version} ${(m.classifier.bytes / 1e6).toFixed(1)} MB, LLM ${m.language.installed ? (m.language.bytes / 1e6).toFixed(0) + ' MB' : 'not installed'}`
})

await check('manageModels: unknown action refused', async () => {
  const r = await evaluate(`__e2e.result('__pandasticModelReply', 'manageModels', ['format_disk'], 5000)`)
  expect(r.ok === false && r.error === 'action', JSON.stringify(r))
})

await check('manageModels: release then load again', async () => {
  const off = await evaluate(`__e2e.result('__pandasticModelReply', 'manageModels', ['unload'], 30000)`)
  expect(off.ok, `unload ${JSON.stringify(off)}`)
  const m = JSON.parse(await evaluate('PandasticNative.modelStatus()'))
  expect(!m.classifier.loaded && !m.language.loaded && !m.knowledge.loaded, 'still loaded after unload')
  const started = Date.now()
  const on = await evaluate(`__e2e.result('__pandasticModelReply', 'manageModels', ['load'], 180000)`)
  expect(on.ok, `load ${JSON.stringify(on)}`)
  return `reload ${Math.round((Date.now() - started) / 1000)} s`
})

// The language model reads every message; its reading is used only where the keywords found nothing (measured).
await check('ask: the language model reads every message and the answer says what it understood', async () => {
  if (!info.llm) return `SKIP: no language model on this phone (${info.loading ? 'still loading' : 'not installed'})`
  const d = await ask('emmwanyi zange zirwadde amakoola gafuuse kyenvu', 'sw')
  expectDecision(d, 'luganda')
  expect(d.nlu === 'model' || d.nlu === 'model_agreed', `nlu ${d.nlu}: the model was not used`)
  expect(/^AI ya simu imeelewa: /.test(d.understood ?? ''), `understood ${d.understood}`)
  const price = await ask('P 1 12000', 'sw')
  expect(price.status === 'PRICE' && price.nlu !== 'keywords', `price code: ${price.status}, nlu ${price.nlu}`)
  return `Luganda: ${d.nlu}, "${d.understood}"; "P 1 12000": ${price.nlu}, "${price.understood}"`
})

// The model also says the fixed answer in its own words, checked word by word (ReplyWriter); greedy, so stable.
await check('ask: a model-written reply, if any, keeps the warnings', async () => {
  if (!info.llm) return 'SKIP: no language model on this phone'
  const d = await ask('my coffee leaves have orange powder underneath', 'en')
  expectDecision(d, 'rust in words')
  // A rewrite is optional (the fixed answer is used when one fails a check); when present it keeps the warnings.
  if (d.ai_reply === undefined) return 'no rewrite passed the checks: fixed answer used'
  expect(/spray/i.test(d.ai_reply) && /not sure/i.test(d.ai_reply), `ai_reply ${d.ai_reply}`)
  return `"${d.ai_reply}"`
})

await check('ask: SMS price code "P 1 12000"', async () => {
  const d = await ask('P 1 12000', 'sw')
  expectDecision(d, 'P 1 12000')
  expect(['PRICE', 'PRICE_STALE'].includes(d.status) && d.price?.offer === 12000, `${d.status}, offer ${d.price?.offer}`)
  return `${d.price.commodity} ${d.price.low}-${d.price.high} ${d.price.currency}, gap ${d.price.gap_pct}%`
})

// The Price screen builds this exact sentence (App.tsx PriceCheck: cropWords + "bei"/"price" + offer).
const uiPhrases = { sw: { coffee: 'kahawa', maize: 'mahindi', bean: 'maharage' }, en: { coffee: 'coffee', maize: 'maize', bean: 'beans' } }
for (const [lang, crops] of Object.entries(uiPhrases)) {
  for (const [crop, word] of Object.entries(crops)) {
    const text = `${word} ${lang === 'sw' ? 'bei' : 'price'} 1500`
    await check(`ask: Price screen sentence "${text}"`, async () => {
      const d = await ask(text, lang)
      expectDecision(d, text)
      expect(['PRICE', 'PRICE_STALE'].includes(d.status), `status ${d.status}: the Price screen would show "not sure"`)
      expect(d.crop === crop || d.crop?.startsWith(crop), `crop ${d.crop}, expected ${crop}`)
      expect(d.price.offer === 1500, `offer ${d.price.offer}`)
      return `${d.status} ${d.price.commodity} ${d.price.low}-${d.price.high}, gap ${d.price.gap_pct}%${d.price.stale ? ', stale' : ''}`
    })
  }
}

await check('ask: "?" gives the menu without escalating', async () => {
  const d = await ask('?', 'sw')
  expectDecision(d, '?')
  expect(d.status === 'HELP' && !d.escalate && d.message, `${d.status} escalate=${d.escalate}`)
})

await check('ask: symptom in words is never CONFIDENT and escalates', async () => {
  const d = await ask('majani ya kahawa yana unga wa njano', 'sw')
  expectDecision(d, 'symptom text')
  expect(d.status !== 'CONFIDENT' && d.escalate, `${d.status} escalate=${d.escalate}`)
  expect(typeof d.message === 'string' && d.message.length > 0, 'no message for the card')
  return d.status
})

await check('ask: price question with no crop asks which crop (the LLM must not invent one)', async () => {
  const d = await ask('how much is it today', 'en')
  expectDecision(d, 'no crop')
  expect(d.status === 'ASK_CROP' && d.crop === 'unknown', `${d.status} for crop ${d.crop}: ${(d.message ?? '').slice(0, 60)}`)
})

await check('checkPhoto: leaf-like photo gets a decision the card can render', async () => {
  const d = await evaluate(`__e2e.reply('checkPhoto', [__e2e.jpeg('leaf'), '', 'sw'], 60000)`)
  expectDecision(d, 'leaf photo')
  expect(d.status !== 'ERROR', `bridge error ${d.error}`)
  expect(typeof d.stub === 'boolean', 'photo decisions carry stub')
  return `${d.status} ${d.label ?? ''} ${d.prob ?? ''}${d.stub ? ' (stub model)' : ''}`
})

await check('checkPhoto: dark photo asks for a retake', async () => {
  const d = await evaluate(`__e2e.reply('checkPhoto', [__e2e.jpeg('dark'), '', 'en'], 60000)`)
  expectDecision(d, 'dark photo')
  expect(d.status === 'RETAKE' && d.quality === 'dark', `${d.status} ${d.quality}`)
})

await check('checkPhoto: bytes that are not a photo give ERROR, not a crash', async () => {
  const d = await evaluate(`__e2e.reply('checkPhoto', [btoa('not a photo'), '', 'sw'], 30000)`)
  expectDecision(d, 'garbage photo')
  expect(d.status === 'ERROR' && d.escalate, `${d.status}`)
  return d.error
})

for (const file of photos) {
  const expected = basename(file).includes('__') ? basename(file).split('__')[0] : null
  await check(`checkPhoto: ${basename(file)}`, async () => {
    const b64 = JSON.stringify(readFileSync(file).toString('base64'))
    const d = await evaluate(`__e2e.shrink(${b64}).then(small => __e2e.reply('checkPhoto', [small, '', 'sw'], 60000))`)
    expectDecision(d, file)
    if (expected) expect(d.label === expected || d.status !== 'CONFIDENT', `CONFIDENT ${d.label}, truth ${expected}`)
    return `${d.status} ${d.label} p=${d.prob}${d.runner_up ? ` (2nd ${d.runner_up} ${d.runner_up_prob})` : ''}`
  })
}

let hub
await check('hubStatus(): every field the SMS helper screen reads', async () => {
  hub = JSON.parse(await evaluate('PandasticNative.hubStatus()'))
  for (const key of ['enabled', 'running', 'smsPermission', 'notificationPermission']) expect(typeof hub[key] === 'boolean', `${key}`)
  expect(['sw', 'en'].includes(hub.lang), `lang ${hub.lang}`)
  expect(Array.isArray(hub.contacts) && hub.contacts.every(c => typeof c.name === 'string' && typeof c.number === 'string'), 'contacts')
  expect(isNum(hub.answeredToday), 'answeredToday')
  expect(Array.isArray(hub.recent) && hub.recent.every(e => isNum(e.id) && typeof e.contact === 'string'
    && typeof e.question === 'string' && isStrOrNull(e.reply) && typeof e.status === 'string' && isNum(e.receivedAt)), 'recent')
  return `enabled ${hub.enabled}, running ${hub.running}, ${hub.contacts.length} contacts, ${hub.recent.length} recent`
})

if (hub) {
  const otherLang = hub.lang === 'sw' ? 'en' : 'sw'
  await check('setHubLang: change is announced to the UI and kept', async () => {
    const detail = await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubLang('${otherLang}'), 5000)`)
    expect(detail.lang === otherLang, `event lang ${detail.lang}`)
    expect(JSON.parse(await evaluate('PandasticNative.hubStatus()')).lang === otherLang, 'not persisted')
  })
  await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubLang('${hub.lang}'), 5000)`).catch(() => undefined)

  const testContact = { name: 'E2E test', number: '+256700999123' }
  await check('setHubContacts: list is announced and kept', async () => {
    const list = JSON.stringify(JSON.stringify([...hub.contacts, testContact]))
    const detail = await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubContacts(${list}), 5000)`)
    expect(detail.contacts.some(c => c.number === testContact.number), 'test contact missing from event')
  })
  await check('setHubContacts: malformed JSON is ignored, list unchanged', async () => {
    const detail = await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubContacts('[{oops'), 5000)`)
    expect(detail.contacts.length === hub.contacts.length + 1, `now ${detail.contacts.length} contacts`)
  })

  if (withSms) {
    await check('SMS round trip: allowlisted "P 1 12000" is answered by the hub', async () => {
      for (const p of ['RECEIVE_SMS', 'SEND_SMS', 'POST_NOTIFICATIONS']) {
        try { adb('shell', 'pm', 'grant', PACKAGE, `android.permission.${p}`) } catch { /* older Android */ }
      }
      try { adb('shell', 'dumpsys', 'deviceidle', 'whitelist', `+${PACKAGE}`) } catch { /* no battery prompt either way */ }
      if (!hub.enabled) await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubEnabled(true), 10000)`)
      const since = Date.now()
      adb('emu', 'sms', 'send', testContact.number.replace('+', ''), 'P 1 12000')
      for (let i = 0; i < 60; i++) {
        await sleep(1000)
        const now = JSON.parse(await evaluate('PandasticNative.hubStatus()'))
        const entry = now.recent.find(e => e.question === 'P 1 12000' && e.receivedAt >= since - 60000)
        if (entry && !['received', 'pending'].includes(entry.status)) {  // pending = reply queued, not yet sent
          expect(entry.status === 'answered' && entry.reply, `status ${entry.status}`)
          return `${Math.round((Date.now() - since) / 1000)} s: ${entry.reply.slice(0, 70)}…`
        }
      }
      throw new Error('no answered entry within 60 s (is the hub running?)')
    })
    if (!hub.enabled) await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubEnabled(false), 10000)`).catch(() => undefined)
  }
  await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubContacts(${JSON.stringify(JSON.stringify(hub.contacts))}), 5000)`)
    .catch(() => undefined)
}

await check('voices(): the offline voice engine starts with the page', async () => {
  let v = JSON.parse(await evaluate('PandasticNative.voices()'))
  for (let i = 0; i < 20 && !v.ready; i++) { await sleep(250); v = JSON.parse(await evaluate('PandasticNative.voices()')) }
  expect(v.ready, 'TTS engine not ready after 5 s')
  expect(typeof v.sw === 'boolean' && typeof v.en === 'boolean' && typeof v.speaking === 'boolean', JSON.stringify(v))
  return `Swahili voice ${v.sw}, English voice ${v.en}`
})

await check('speak: reads aloud, announces start, stops on request', async () => {
  const v = JSON.parse(await evaluate('PandasticNative.voices()'))
  const lang = v.sw ? 'sw' : 'en'
  const started = await evaluate(`__e2e.event('pandastic:speech', () => { window.__e2eSpoke = PandasticNative.speak(
    ${JSON.stringify('Habari Noor. Sina uhakika kwa maneno pekee. Usinyunyizie dawa bado. Uliza afisa ugani.')}, '${lang}') }, 8000)`)
  expect(await evaluate('window.__e2eSpoke') === true, 'speak() returned false')
  expect(started.state === 'start', `first event ${started.state}`)
  const stopped = await evaluate(`__e2e.event('pandastic:speech', () => PandasticNative.stopSpeaking(), 5000)`)
  expect(['stopped', 'done'].includes(stopped.state), `after stop: ${stopped.state}`)
  expect(await evaluate(`PandasticNative.speak('', '${lang}')`) === false, 'empty text should not speak')
  return `${lang} voice: start -> ${stopped.state}`
})

if (phone.mode === 'lite') await setMode('lite').catch(() => undefined)
else if (helperWasOn) await evaluate(`__e2e.hubEvent(() => PandasticNative.setHubEnabled(true), 10000)`).catch(() => undefined)
else if (!phone.mode) console.log("note: phone mode was not chosen yet; it is now 'capable'")
page.close()
clearInterval(keepAlive)
const failed = results.filter(r => !r.ok).length
console.log(`\n${results.length - failed}/${results.length} passed${withSms ? '' : ' (SMS round trip skipped: --sms)'}`)
process.exit(failed ? 1 : 0)
