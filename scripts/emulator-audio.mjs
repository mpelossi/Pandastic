#!/usr/bin/env node
// Enable the emulator's separate "Virtual microphone uses host audio input" switch.
// Uses its existing authenticated loopback endpoint; never opens a control port.
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { homedir } from 'node:os'
import http2 from 'node:http2'

const serial = process.argv[2]
if (!/^emulator-\d+$/.test(serial || '')) throw new Error('Usage: node scripts/emulator-audio.mjs emulator-SERIAL')
const runtime = process.env.XDG_RUNTIME_DIR || `/run/user/${process.getuid?.()}`
const directories = [resolve(runtime, 'avd/running'), resolve(homedir(), '.android/avd/running')]
let config
for (const directory of directories) {
  let files
  try { files = readdirSync(directory) } catch { continue }
  for (const file of files.filter(file => /^pid_\d+\.ini$/.test(file))) {
    const values = Object.fromEntries(readFileSync(resolve(directory, file), 'utf8').split('\n').map(line => {
      const equals = line.indexOf('=')
      return [line.slice(0, equals).trim(), line.slice(equals + 1).trim()]
    }))
    if (`emulator-${values['port.serial']}` === serial) config = values
  }
}
if (!config || !/^\d+$/.test(config['grpc.port']) || !config['grpc.token']) {
  console.warn(`${serial}: enable Extended controls → Microphone → Virtual microphone uses host audio input.`)
  process.exit(0)
}
const client = http2.connect(`http://127.0.0.1:${config['grpc.port']}`)
try {
  await new Promise((resolve, reject) => {
    client.once('error', reject)
    const request = client.request({
      ':method': 'POST', ':path': '/android.emulation.control.EmulatorController/setMicrophoneState',
      'content-type': 'application/grpc', te: 'trailers', authorization: `Bearer ${config['grpc.token']}`,
    })
    request.setTimeout(5000, () => { request.close(); reject(new Error('Emulator microphone control timed out')) })
    let status
    request.on('response', headers => { status = headers['grpc-status'] })
    request.on('trailers', headers => { status = headers['grpc-status'] })
    request.on('data', () => {})
    request.on('error', reject)
    request.on('end', () => status === '0' ? resolve() : reject(new Error(`Emulator microphone control failed (${status})`)))
    // gRPC frame + MicrophoneState { realAudioEnabled: true } (SDK emulator_controller.proto).
    request.end(Buffer.from([0, 0, 0, 0, 2, 8, 1]))
  })
  console.log(`${serial}: host microphone input enabled.`)
} finally { client.close() }
