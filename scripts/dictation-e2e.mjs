#!/usr/bin/env node
// Real Android microphone -> bundled Tiny -> composer regression, including offline operation.
// DEVICE=emulator-5556 node scripts/dictation-e2e.mjs speech.pcm 'price of coffee' --offline --lang en
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { resolve } from 'node:path'
import http2 from 'node:http2'
import { connectApp, sleep } from './lib/devtools.mjs'

const serial = process.env.DEVICE || 'emulator-5556'
const args = process.argv.slice(2)
const [file, expected] = args
const offline = args.includes('--offline')
const language = args.includes('--lang') ? args[args.indexOf('--lang') + 1] : undefined
assert(!language || ['sw', 'en'].includes(language), '--lang must be sw or en')
assert(/^emulator-\d+$/.test(serial) && file && expected,
  'Usage: DEVICE=emulator-5556 node scripts/dictation-e2e.mjs speech.pcm expected-words [--offline] [--lang en|sw]')
const audio = readFileSync(file)
assert(audio.length > 0 && audio.length % 2 === 0 && audio.length <= 32000 * 30, 'Use up to 30 seconds of 16 kHz mono PCM')
let config
for (const directory of [resolve(process.env.XDG_RUNTIME_DIR || `/run/user/${process.getuid()}`, 'avd/running'), resolve(homedir(), '.android/avd/running')]) {
  let files
  try { files = readdirSync(directory) } catch { continue }
  for (const name of files.filter(name => /^pid_\d+\.ini$/.test(name))) {
    const values = Object.fromEntries(readFileSync(resolve(directory, name), 'utf8').split('\n').map(line => {
      const equals = line.indexOf('=')
      return [line.slice(0, equals).trim(), line.slice(equals + 1).trim()]
    }))
    if (`emulator-${values['port.serial']}` === serial) config = values
  }
}
assert(config?.['grpc.token'] && /^\d+$/.test(config['grpc.port']), 'No authenticated emulator audio endpoint')
const client = http2.connect(`http://127.0.0.1:${config['grpc.port']}`)
const headers = path => ({ ':method': 'POST', ':path': `/android.emulation.control.EmulatorController/${path}`,
  'content-type': 'application/grpc', te: 'trailers', authorization: `Bearer ${config['grpc.token']}` })
const frame = bytes => {
  const header = Buffer.alloc(5); header.writeUInt32BE(bytes.length, 1)
  return Buffer.concat([header, bytes])
}
const varint = number => {
  const bytes = []
  do { bytes.push((number & 127) | (number > 127 ? 128 : 0)); number >>>= 7 } while (number)
  return Buffer.from(bytes)
}
async function rpc(method, packets, pace = 0) {
  const request = client.request(headers(method))
  const response = new Promise((resolve, reject) => {
    let status, data = []
    request.on('response', headers => { status = headers['grpc-status'] })
    request.on('trailers', headers => { status = headers['grpc-status'] })
    request.on('data', bytes => data.push(bytes))
    request.on('error', reject)
    request.setTimeout(45000, () => { request.close(); reject(new Error(`${method} timed out`)) })
    request.on('end', () => status === '0' ? resolve(Buffer.concat(data)) : reject(new Error(`${method}: gRPC ${status}`)))
  })
  for (const packet of packets) {
    request.write(frame(packet))
    if (pace) await sleep(pace)
  }
  request.end()
  return response
}
function inject(pcm) {
  // AudioPacket.format: 16000 Hz, mono, S16, real time, paced at 100ms per packet.
  const format = Buffer.from([8, ...varint(16000), 24, 1, 32, 1])
  const packets = []
  for (let offset = 0; offset < pcm.length; offset += 3200) {
    const chunk = pcm.subarray(offset, offset + 3200)
    packets.push(Buffer.concat([Buffer.from([10, format.length]), format, Buffer.from([26]), varint(chunk.length), chunk]))
  }
  return rpc('injectAudio', packets, 100)
}
const keep = setInterval(() => {}, 1000)
let page, originalDraft, micState, originalLanguage, wifi, data
async function changeLanguage(language) {
  await page.evaluate(`document.querySelector('nav button:last-child').click()`)
  await sleep(100)
  await page.evaluate(`[...document.querySelectorAll('.settings-language button')].find(b=>b.textContent===${JSON.stringify(language === 'sw' ? 'Kiswahili' : 'English')}).click()`)
  await sleep(100)
  await page.evaluate(`document.querySelector('nav button:first-child').click()`)
  await sleep(100)
}
try {
  page = await connectApp(serial, Number(serial.slice(9)) + 4000)
  const { evaluate } = page
  for (let i = 0; i < 50; i++) {
    if (await evaluate(`Boolean(document.querySelector('.dictate-button'))`)) break
    await sleep(100)
  }
  assert(await evaluate(`Boolean(document.querySelector('.dictate-button'))`), 'Choose a phone mode first')
  await evaluate(`document.querySelector('nav button:first-child').click()`)
  await sleep(100)
  originalLanguage = await evaluate('document.documentElement.lang')
  if (language && language !== originalLanguage) await changeLanguage(language)
  assert(await evaluate(`Boolean(document.querySelector('.dictate-button'))`), 'Open Chat first')
  const status = JSON.parse(await evaluate('PandasticNative.dictationStatus()'))
  assert(status.available && status.permission && !status.listening, `Speech provider not ready: ${JSON.stringify(status)}`)
  if (offline) {
    assert.equal(status.offline, true, 'Native dictation must use the bundled offline engine')
    wifi = page.adb('shell', 'settings', 'get', 'global', 'wifi_on')
    data = page.adb('shell', 'settings', 'get', 'global', 'mobile_data')
    page.adb('shell', 'svc', 'wifi', 'disable')
    page.adb('shell', 'svc', 'data', 'disable')
    await sleep(1500)
    assert.equal(page.adb('shell', 'settings', 'get', 'global', 'wifi_on'), '0')
    assert.equal(page.adb('shell', 'settings', 'get', 'global', 'mobile_data'), '0')
    const network = page.adb('shell', 'dumpsys', 'connectivity').split('\n').find(line => /Active default network:/.test(line))
    assert(network && /none/.test(network), `Device still has a network: ${network}`)
    console.log('Wi-Fi and mobile data disabled; no active default network.')
  }
  originalDraft = await evaluate(`document.querySelector('textarea').value`)
  micState = await rpc('getMicrophoneState', [Buffer.alloc(0)])
  await rpc('setMicrophoneState', [Buffer.from([8, 0])])
  await evaluate(`window.__dictationTestEvents=[]; window.__dictationTestListener=e=>__dictationTestEvents.push(e.detail); window.addEventListener('pandastic:dictation',__dictationTestListener)`)
  async function run(pcm) {
    await evaluate(`__dictationTestEvents.length=0;document.querySelector('.dictate-button').click()`)
    for (let i = 0; i < 30; i++) {
      if (await evaluate(`__dictationTestEvents.some(e=>e.state==='start'||e.state==='error')`)) break
      await sleep(100)
    }
    assert(await evaluate(`__dictationTestEvents.some(e=>e.state==='start')`), 'Recognition did not start')
    await inject(pcm)
    for (let i = 0; i < 600; i++) {
      if (await evaluate(`__dictationTestEvents.some(e=>e.state==='end')`)) break
      await sleep(100)
    }
    const events = await evaluate('__dictationTestEvents')
    assert(events.some(e => e.state === 'end'), 'Recognition did not finish')
    assert.equal(await evaluate(`document.querySelector('.dictate-button').getAttribute('aria-pressed')`), 'false')
    return events
  }
  const silent = await run(Buffer.alloc(32000 * 6))
  assert(silent.some(e => e.error === 'no-speech'), JSON.stringify(silent))
  assert.equal(await evaluate(`document.querySelector('textarea').value`), originalDraft, 'Silence changed the draft')
  console.log('PASS: silence preserves draft and ends listening without inference')
  const beforeMessages = await evaluate(`document.querySelectorAll('.message-row').length`)
  const spoken = await run(Buffer.concat([Buffer.alloc(16000), audio, Buffer.alloc(32000 * 2)]))
  const result = spoken.find(e => e.state === 'result')
  assert(spoken.some(e=>e.state === 'processing'), 'Offline processing state was not announced')
  assert(result?.text.toLowerCase().includes(expected.toLowerCase()), JSON.stringify(spoken))
  const draft = await evaluate(`document.querySelector('textarea').value`)
  assert.equal(draft, [originalDraft.trimEnd(), result.text].filter(Boolean).join(' ').slice(0, 480))
  assert.equal(await evaluate(`document.querySelectorAll('.message-row').length`), beforeMessages, 'Dictation sent a message')
  console.log(`PASS: real speech reaches the draft without sending: ${result.text}`)
  await evaluate(`__dictationTestEvents.length=0;document.querySelector('.dictate-button').click()`)
  for (let i = 0; i < 30; i++) {
    if (await evaluate(`__dictationTestEvents.some(e=>e.state==='start')`)) break
    await sleep(100)
  }
  await evaluate(`document.querySelector('nav button:last-child').click()`)
  await sleep(300)
  assert.equal(JSON.parse(await evaluate('PandasticNative.dictationStatus()')).listening, false, 'Leaving Chat did not cancel')
  await evaluate(`(() => { const id=__dictationTestEvents.find(e=>e.state==='requesting').id;
    window.dispatchEvent(new CustomEvent('pandastic:dictation',{detail:{id,state:'result',text:'late result'}}));
    document.querySelector('nav button:first-child').click(); })()`)
  assert.equal(await evaluate(`document.querySelector('textarea').value`), draft, 'Late result changed the draft')
  console.log('PASS: leaving Chat cancels recognition and ignores late results')
} finally {
  if (page) {
    await page.evaluate(`PandasticNative.cancelDictation();window.removeEventListener('pandastic:dictation',window.__dictationTestListener)` ).catch(() => {})
    if (originalDraft !== undefined) await page.evaluate(`(() => { const field=document.querySelector('textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(field,${JSON.stringify(originalDraft)});field.dispatchEvent(new Event('input',{bubbles:true})); })()`).catch(() => {})
    if (originalLanguage && language && originalLanguage !== language) await changeLanguage(originalLanguage).catch(() => {})
    if (wifi !== undefined) page.adb('shell', 'svc', 'wifi', wifi === '1' ? 'enable' : 'disable')
    if (data !== undefined) page.adb('shell', 'svc', 'data', data === '1' ? 'enable' : 'disable')
    page.close()
  }
  if (micState) await rpc('setMicrophoneState', [micState.subarray(5)]).catch(() => {})
  client.close(); clearInterval(keep)
}
