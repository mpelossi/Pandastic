#!/usr/bin/env node
// SMS lab: test the SMS helper end to end on emulators, by hand or automatically.
//
// The Android emulator no longer delivers SMS from one emulator to another, so this script plays the
// carrier: it watches each emulator's sent messages (content://sms/sent) and delivers them to the other
// one with `adb emu sms send`, as if the SIM numbers below were real. Phones that exist only in the
// terminal ("virtual phones") can text the helper too, like a farmer's basic phone.
//
//   node scripts/sms-lab.mjs setup    # configure: helper phone (capable, SMS helper on, Swahili; --en) + Basic phone
//   node scripts/sms-lab.mjs relay    # be the carrier while you text by hand in the Basic phone's Chat screen
//   node scripts/sms-lab.mjs phone    # a basic phone in this terminal: type an SMS, read the helper's reply
//   node scripts/sms-lab.mjs test     # automated SMS suite (clears the helper's history first: rate limits)
//
//   HUB=emulator-5554 BASIC=emulator-5556 node scripts/sms-lab.mjs ...   (these are the defaults)
//   Start the second emulator with: emulator -avd pandastic_basic -port 5556 (or: make emulator-basic)
import { createInterface } from 'node:readline'
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ADB, adbFor, connectApp, sleep } from './lib/devtools.mjs'
import { execFileSync } from 'node:child_process'

const HUB = process.env.HUB || 'emulator-5554'
const BASIC = process.env.BASIC || 'emulator-5556'
// The SIM numbers the lab pretends each phone has (Uganda format; nothing is really sent).
const NUMBER = { hub: '+256772000001', basic: '+256772000002', phone: '+256772000003', phone2: '+256772000004',
  stranger: '+256779999999' }
const APK = new URL('../android/app/build/outputs/apk/debug/app-debug.apk', import.meta.url).pathname

const digits = n => String(n).replace(/[^0-9]/g, '')
const shellQuote = value => "'" + String(value).replaceAll("'", "'\\''") + "'"
const same = (a, b) => digits(a).slice(-9) === digits(b).slice(-9)
const online = serial => {
  try { return execFileSync(ADB, ['-s', serial, 'get-state'], { encoding: 'utf8' }).trim() === 'device' } catch { return false }
}
const nameOf = number => same(number, NUMBER.hub) ? 'helper phone' : same(number, NUMBER.basic) ? 'Basic phone'
  : same(number, NUMBER.phone) ? 'terminal phone' : number

// ---- the carrier ---------------------------------------------------------------------------------

// One carrier per phone pair: two would deliver every SMS twice (and the helper would answer twice).
const CARRIER_PID = join(tmpdir(), `pandastic-sms-carrier-${HUB}-${BASIC}.pid`)
function runningCarrier() {
  try {
    const pid = Number(readFileSync(CARRIER_PID, 'utf8'))
    if (pid && pid !== process.pid) { process.kill(pid, 0); return pid }
  } catch { /* none, or a stale file */ }
  return null
}

/** Reads new rows of a device's sent box. `content query` prints one row per line; a body may span lines. */
function sentSince(serial, lastId) {
  let out = ''
  try {
    out = adbFor(serial)('shell', 'content', 'query', '--uri', 'content://sms/sent', '--projection', '_id:address:body',
      '--where', `"_id>${lastId}"`, '--sort', '"_id ASC"')
  } catch { return [] }
  const rows = []
  for (const line of out.split('\n')) {
    const m = line.match(/^Row: \d+ _id=(\d+), address=([^,]*), body=(.*)$/)
    if (m) rows.push({ id: Number(m[1]), to: m[2], body: m[3] })
    else if (rows.length && line && !line.startsWith('No result')) rows.at(-1).body += '\n' + line
  }
  return rows
}
function lastSentId(serial) {
  const rows = sentSince(serial, 0)
  return rows.length ? Math.max(...rows.map(r => r.id)) : 0
}
/** Delivers one SMS into an emulator as if it came from `from` (the emulator splits long text into parts). */
function deliver(serial, from, body) {
  adbFor(serial)('emu', 'sms', 'send', String(from).replace(/[^0-9+]/g, ''), body.replace(/\s*\n\s*/g, ' '))
}

/**
 * Polls both emulators' sent boxes and delivers each message to its recipient: another emulator, or a
 * virtual phone (onVirtual). Returns a stop function.
 */
function startCarrier({ onVirtual = () => {}, log = console.log } = {}) {
  // If `relay` already runs, it delivers between the emulators; here only the virtual phones are served.
  const relayPid = runningCarrier()
  const phones = [{ serial: HUB, number: NUMBER.hub },
    ...(online(BASIC) && !relayPid ? [{ serial: BASIC, number: NUMBER.basic }] : [])]
  for (const p of phones) p.last = lastSentId(p.serial)
  let stopped = false, round = 0
  ;(async () => {
    while (!stopped) {
      round++
      for (const p of phones) {
        // A wiped SMS history (human-test reset) restarts the ids: follow it down, or new SMS would be missed.
        if (round % 8 === 0) { const top = lastSentId(p.serial); if (top < p.last) p.last = top }
        for (const sms of sentSince(p.serial, p.last)) {
          p.last = sms.id
          const target = phones.find(q => q !== p && same(q.number, sms.to))
          if (relayPid && same(sms.to, NUMBER.basic)) continue  // the running relay delivers this one
          if (target) {
            log(`  [carrier] ${nameOf(p.number)} -> ${nameOf(target.number)}: ${oneLine(sms.body)}`)
            try { deliver(target.serial, p.number, sms.body) }
            catch (e) { log(`  [carrier] could not deliver to ${target.serial}: ${e.message.split('\n')[0]}`) }
          } else onVirtual({ from: p.number, to: sms.to, body: sms.body, at: Date.now() })
        }
      }
      await sleep(700)
    }
  })()
  return () => { stopped = true }
}
const oneLine = text => text.replace(/\s+/g, ' ').slice(0, 160)

// ---- phone-level identity and Contacts (not the app's settings) -----------------------------------------

/** Names in each phone's own Contacts app: the helper is the daughter's phone, the Basic phone is Noor's. */
const CONTACT_NAME = { [HUB]: 'Mama Noor', [BASIC]: 'Amani (binti)' }

/**
 * What a real phone already has before Pandastic is installed: its SIM number (lab-only file the debug build
 * reads, since the emulator's SIM number is generic) and the other person in its Contacts app. Never touches
 * the app's own settings, so a tester still sets up Pandastic from its first screen.
 */
function seedPhone(serial) {
  const adb = adbFor(serial)
  const own = serial === HUB ? NUMBER.hub : NUMBER.basic
  const peer = serial === HUB ? NUMBER.basic : NUMBER.hub
  const label = CONTACT_NAME[serial]
  adb('shell', 'run-as', 'org.pandastic.relay', 'mkdir', '-p', 'shared_prefs')
  execFileSync(ADB, ['-s', serial, 'shell', 'run-as', 'org.pandastic.relay', 'tee', 'shared_prefs/pandastic_lab.xml'], {
    input: `<?xml version="1.0" encoding="utf-8"?><map><string name="own_number">${own}</string></map>`, stdio: ['pipe', 'ignore', 'pipe'],
  })
  const rows = adb('shell', 'content', 'query', '--uri', 'content://com.android.contacts/data/phones',
    '--projection', 'raw_contact_id:data1:display_name')
  const existing = rows.split('\n').map(line => line.match(/raw_contact_id=(\d+), data1=([^,]*), display_name=(.*)$/)).find(m => m && same(m[2], peer))
  if (existing) {
    if (existing[3] !== label) {  // older lab runs used other names: keep one entry, with the story's name
      // Clear the old given/family parts too, or the provider rebuilds the display name from them.
      adb('shell', 'content', 'update', '--uri', 'content://com.android.contacts/data', '--bind', shellQuote(`data1:s:${label}`),
        ...['data2', 'data3', 'data4', 'data5', 'data6'].flatMap(column => ['--bind', `${column}:n:`]),
        '--where', shellQuote(`raw_contact_id=${existing[1]} AND mimetype='vnd.android.cursor.item/name'`))
    }
    return
  }
  adb('shell', 'content', 'insert', '--uri', 'content://com.android.contacts/raw_contacts', '--bind', 'account_type:n:', '--bind', 'account_name:n:')
  const inserted = adb('shell', 'content', 'query', '--uri', 'content://com.android.contacts/raw_contacts', '--projection', '_id', '--sort', '"_id DESC"')
  const rawId = inserted.match(/_id=(\d+)/)?.[1]
  if (!rawId) { console.warn(`${serial}: add ${label} (${peer}) in Contacts by hand.`); return }
  adb('shell', 'content', 'insert', '--uri', 'content://com.android.contacts/data', '--bind', `raw_contact_id:l:${rawId}`, '--bind', 'mimetype:s:vnd.android.cursor.item/name', '--bind', shellQuote(`data1:s:${label}`))
  adb('shell', 'content', 'insert', '--uri', 'content://com.android.contacts/data', '--bind', `raw_contact_id:l:${rawId}`, '--bind', 'mimetype:s:vnd.android.cursor.item/phone_v2', '--bind', `data1:s:${peer}`, '--bind', 'data2:i:2')
}

/** `seed`: phone identity + Contacts on both emulators, nothing in the app (human-test resets use this). */
async function seed() {
  for (const serial of [HUB, BASIC].filter(online)) {
    seedPhone(serial)
    console.log(`${serial}: ${serial === HUB ? NUMBER.hub : NUMBER.basic}, Contacts has ${CONTACT_NAME[serial]} (${serial === HUB ? NUMBER.basic : NUMBER.hub})`)
  }
}

// ---- setup ---------------------------------------------------------------------------------------

async function setup() {
  if (!online(HUB)) throw new Error(`${HUB} is not running: start the emulator first (make run)`)
  const devices = [HUB, ...(online(BASIC) ? [BASIC] : [])]
  if (!online(BASIC)) console.log(`note: ${BASIC} is not running; only the terminal phone will work (start it with: make emulator-basic)`)
  for (const serial of devices) {
    const adb = adbFor(serial)
    const installed = adb('shell', 'pm', 'list', 'packages', 'org.pandastic.relay').includes('org.pandastic.relay')
    if (!installed || process.argv.includes('--install')) {
      if (!existsSync(APK)) throw new Error('no debug APK: run make build first')
      console.log(`${serial}: installing ${APK}`)
      adb('install', '-r', APK)
    }
    for (const p of ['RECEIVE_SMS', 'SEND_SMS', 'POST_NOTIFICATIONS', 'READ_CONTACTS', 'READ_PHONE_NUMBERS']) {
      try { adb('shell', 'pm', 'grant', 'org.pandastic.relay', `android.permission.${p}`) } catch { /* older Android */ }
    }
    try { adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+org.pandastic.relay') } catch { /* no battery prompt */ }
    if (/^emulator-\d+$/.test(serial)) seedPhone(serial)
  }

  const hub = await connectApp(HUB, 9333)
  await hub.evaluate(`PandasticNative.setPhoneMode('capable')`)
  await hub.evaluate(`PandasticNative.setHubContacts(${JSON.stringify(JSON.stringify([
    { name: 'Mama Noor', number: NUMBER.basic }, { name: 'Noor (kabambe)', number: NUMBER.phone },
    { name: 'Juma (kabambe)', number: NUMBER.phone2 }]))})`)
  await hub.evaluate(`PandasticNative.setHubLang('${process.argv.includes('--en') ? 'en' : 'sw'}')`)  // Swahili first
  await hub.evaluate(`PandasticNative.setHubEnabled(true)`)
  let status = {}
  for (let i = 0; i < 20 && !status.running; i++) { await sleep(500); status = JSON.parse(await hub.evaluate('PandasticNative.hubStatus()')) }
  hub.close()
  if (!status.running) throw new Error(`SMS helper did not start on ${HUB}: ${JSON.stringify(status)}`)
  console.log(`${HUB}: helper phone ${NUMBER.hub}, SMS helper ON, replies in the SMS's language (default ${status.lang === 'en' ? 'English' : 'Swahili'}), allows ${NUMBER.basic} + ${NUMBER.phone}`)

  if (online(BASIC)) {
    const basic = await connectApp(BASIC, 9334)
    await basic.evaluate(`PandasticNative.setPhoneMode('lite')`)
    await basic.evaluate(`PandasticNative.setSmsPeer(${JSON.stringify(NUMBER.hub)})`)
    basic.close()
    console.log(`${BASIC}: Basic phone ${NUMBER.basic}, chats with ${NUMBER.hub}`)
  }
  console.log(`\nNext: node scripts/sms-lab.mjs relay   (then type in the Basic phone's Chat screen)\n   or: node scripts/sms-lab.mjs phone   (type here)`)
}

// ---- interactive -----------------------------------------------------------------------------------

async function relay() {
  const other = runningCarrier()
  if (other) { console.log(`A carrier is already running (pid ${other}); not starting a second one.`); return }
  writeFileSync(CARRIER_PID, String(process.pid))
  const release = () => { try { if (Number(readFileSync(CARRIER_PID, 'utf8')) === process.pid) unlinkSync(CARRIER_PID) } catch { /* gone */ } }
  process.on('exit', release)
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => process.exit(0))
  console.log(`Carrier running between ${HUB} (${NUMBER.hub}) and ${online(BASIC) ? `${BASIC} (${NUMBER.basic})` : 'no Basic phone'}. Ctrl+C to stop.`)
  startCarrier({ onVirtual: sms => console.log(`  [carrier] ${nameOf(sms.from)} -> ${sms.to}: ${oneLine(sms.body)}  (no such phone in the lab)`) })
  await new Promise(() => {})
}

async function phone() {
  const me = process.argv[3] && /\d/.test(process.argv[3]) ? process.argv[3] : NUMBER.phone
  console.log(`You are a basic phone, ${me}. Type an SMS for the helper phone (${NUMBER.hub}); Ctrl+D to stop.`)
  console.log(`Try: P 1 12000 | bei ya kahawa 12000 | mahindi bei 900 | ? | majani ya kahawa yana unga wa njano`)
  startCarrier({
    log: () => {},
    onVirtual: sms => { if (same(sms.to, me)) console.log(`\n< ${sms.body}\n`) },
  })
  const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: '> ' })
  rl.prompt()
  rl.on('line', line => {
    if (line.trim()) { deliver(HUB, me, line.trim()); console.log('  (sent; the helper answers in 1-30 s)') }
    rl.prompt()
  })
  await new Promise(resolve => rl.on('close', resolve))
  process.exit(0)
}

// ---- automated suite -----------------------------------------------------------------------------

const GSM7 = /^[@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&'()*+,\-./0-9:;<=>?¡A-ZÄÖÑÜ§¿a-zäöñüà^{}\\[~\]|€]*$/
function segments(text) {
  if (GSM7.test(text)) return text.length <= 160 ? 1 : Math.ceil(text.length / 153)
  return text.length <= 70 ? 1 : Math.ceil(text.length / 67)
}

async function test() {
  if (!online(HUB)) throw new Error(`${HUB} is not running`)
  const hub = await connectApp(HUB, 9333)
  const before = JSON.parse(await hub.evaluate('PandasticNative.hubStatus()'))
  if (!before.running) throw new Error('the SMS helper is off: run node scripts/sms-lab.mjs setup first')
  if (!process.argv.includes('--keep-history')) await hub.evaluate('PandasticNative.clearHubHistory()')
  const sw = before.lang !== 'en'
  console.log(`helper ${HUB}, replies in ${sw ? 'Swahili' : 'English'}; virtual phones ${NUMBER.phone}, ${NUMBER.stranger}\n`)

  const inbox = []
  const stop = startCarrier({ log: () => {}, onVirtual: sms => inbox.push(sms) })
  const results = []
  async function sms(name, from, text, check, { timeoutMs = 45000, expectReply = true } = {}) {
    const sentAt = Date.now()
    deliver(HUB, from, text)
    let reply, handled = null
    // No reply expected: wait until the helper has decided (its log entry leaves "pending"; the language
    // model may take a while), or 12 s for a number it ignores entirely, then a little longer for a reply.
    const deadline = sentAt + (expectReply ? timeoutMs : 60000)
    while (!reply && Date.now() < deadline) {
      await sleep(400)
      reply = inbox.find(m => same(m.to, from) && m.at >= sentAt)
      if (!expectReply && !reply) {
        const recent = JSON.parse(await hub.evaluate('PandasticNative.hubStatus()')).recent
        handled = recent.find(e => e.receivedAt >= sentAt - 3000 && e.status !== 'pending')?.status ?? handled
        if (handled || Date.now() - sentAt > 12000) { await sleep(3000); reply = inbox.find(m => same(m.to, from) && m.at >= sentAt); break }
      }
    }
    if (reply) inbox.splice(inbox.indexOf(reply), 1)
    let ok, note
    if (!expectReply) {
      ok = !reply
      note = reply ? `unexpected reply: ${oneLine(reply.body)}` : `no reply, as expected${handled ? ` (helper log: ${handled})` : ''}`
    }
    else if (!reply) {  // say why: the helper's log knows (rate_limited, failed, still pending)
      const entry = JSON.parse(await hub.evaluate('PandasticNative.hubStatus()')).recent
        .find(e => e.question === text && e.receivedAt >= sentAt - 5000)
      ok = false
      note = entry ? `no reply: helper log says "${entry.status}"${entry.status === 'rate_limited' ? ' (10 per number per hour; run without --keep-history)' : ''}`
        : 'no reply, and the helper never logged the SMS'
    }
    else {
      const parts = segments(reply.body)
      const problem = parts > 2 ? `${parts} SMS parts (max 2)` : !reply.body.startsWith('Pandastic:') ? 'reply without the Pandastic: prefix'
        : check(reply.body)
      ok = !problem
      note = `${((reply.at - sentAt) / 1000).toFixed(1)} s, ${parts} part${parts > 1 ? 's' : ''}: ${oneLine(reply.body)}${problem ? `  <- ${problem}` : ''}`
    }
    results.push({ ok })
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}\n      > ${text}\n      < ${note}`)
  }
  const has = (re, why) => body => re.test(body) ? null : why

  await sms('price code with an offer', NUMBER.phone, 'P 1 12000', has(/UGX ?15,500/, 'no UGX 15,500 coffee price'))
  await sms('Swahili coffee price question', NUMBER.phone, 'bei ya kahawa leo ni ngapi? wananipa 12000',
    has(/UGX/, 'no price in the reply'))
  await sms('maize price without a price word', NUMBER.phone, 'mahindi 900', has(/UGX/, 'no maize price'))
  await sms('beans price in English', NUMBER.phone, 'beans price 3000', has(/UGX/, 'no beans price'))
  await sms('menu', NUMBER.phone, '?', has(/P ?1|bei|price/i, 'menu does not explain the price code'))
  await sms('symptom in words: never a confident diagnosis', NUMBER.phone, 'majani ya kahawa yana unga wa njano',
    body => /hakika|sure|picha|photo|afisa|officer|uliza|ask/i.test(body) ? null : 'no "not sure / ask a person / photo" guidance')
  await sms('price without a crop asks which crop', NUMBER.phone, 'bei ni ngapi leo?',
    has(/kahawa|mahindi|maharage|coffee|maize|beans/i, 'does not ask which crop'))
  // Read by the language model when one is side-loaded (another sender: 10 answers per number per hour).
  const safe = body => /hakika|sure|afisa|officer|uliza|ask/i.test(body) ? null : 'no "not sure / ask a person" wording'
  // "emmwanyi" = coffee in Luganda; the keywords don't know it, so no disease of another crop may be named.
  await sms('Luganda coffee problem: safe, no other crop\'s disease', NUMBER.phone2,
    'emmwanyi zange zirwadde amakoola gafuuse kyenvu',
    body => safe(body) ?? (/mahindi|maize|maharage|bean/i.test(body) ? 'names a maize/bean disease for coffee' : null),
    { timeoutMs: 60000 })
  await sms('symptom the keywords miss (fine-tuned LLM names it, still not sure)', NUMBER.phone2,
    'coffee leaves have grey spots with brown ring', safe, { timeoutMs: 60000 })
  await sms('pest the keywords miss (fine-tuned LLM names it, still not sure)', NUMBER.phone2,
    'Wadudu wanachimba ndani ya majani ya kahawa', safe, { timeoutMs: 60000 })
  // The helper is the daughter's phone: her mother's personal texts must get no automatic reply.
  for (const text of ['Habari mwanangu, shule inaendaje?', 'Nimekutumia pesa ya ada', 'how is school?', 'nisaidie pesa ya ada'])
    await sms(`personal message gets no reply: "${text}"`, NUMBER.phone, text, () => null, { expectReply: false })
  await sms('"nisaidie" alone (help me) gets the menu', NUMBER.phone2, 'Nisaidie tafadhali',
    has(/P ?1|bei|price/i, 'no menu'))
  await sms('greeting + a farming question still gets the answer', NUMBER.phone2,
    'Habari mwanangu, bei ya kahawa ni ngapi leo?', has(/UGX/, 'no price'))
  await sms('unknown number gets no reply (allowlist)', NUMBER.stranger, 'P 1 12000', () => null, { expectReply: false })
  await sms('own echo is ignored (no reply loops)', NUMBER.phone, 'Pandastic: test echo', () => null, { expectReply: false })

  if (online(BASIC)) {
    const basic = await connectApp(BASIC, 9334)
    const sentAt = Date.now()
    const r = await basic.evaluate(`__e2e.result('__pandasticSmsReply', 'sendSms', [${JSON.stringify(NUMBER.hub)}, 'bei ya kahawa 12000'], 30000)`)
    let got
    while (!got && Date.now() - sentAt < 60000) {
      await sleep(700)
      got = JSON.parse(await basic.evaluate('PandasticNative.chatStatus()')).messages
        .find(m => m.direction === 'in' && same(m.number, NUMBER.hub) && m.time >= sentAt)
    }
    const ok = r.ok && got && /UGX/.test(got.body)
    results.push({ ok })
    console.log(`${ok ? 'PASS' : 'FAIL'}  Basic phone app -> helper -> Basic phone chat\n      > bei ya kahawa 12000\n      < ${got ? `${((got.time - sentAt) / 1000).toFixed(1)} s: ${oneLine(got.body)}` : `no reply in the chat (send ${JSON.stringify(r)})`}`)
    basic.close()
  } else console.log(`SKIP  Basic phone app round trip (${BASIC} not running)`)

  stop()
  const after = JSON.parse(await hub.evaluate('PandasticNative.hubStatus()'))
  hub.close()
  const failed = results.filter(r => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} passed; helper answered ${after.answeredToday} today`)
  process.exit(failed ? 1 : 0)
}

const keepAlive = setInterval(() => {}, 1000)  // Node's WebSocket alone does not keep the process alive
const command = process.argv[2]
const commands = { setup, seed, relay, phone, test }
if (!commands[command]) {
  console.log('usage: node scripts/sms-lab.mjs setup | seed | relay | phone [number] | test [--keep-history]')
  process.exit(2)
}
try {
  await commands[command]()
} catch (e) {
  console.error(`error: ${e.message}`)
  process.exit(1)
}
clearInterval(keepAlive)
process.exit(0)
