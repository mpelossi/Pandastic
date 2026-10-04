#!/usr/bin/env node
// Weights are local artifacts, not APK/git assets. llama.cpp loads this GGUF on the capable phone.
import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream, existsSync, mkdirSync, readdirSync, renameSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'

const root = fileURLToPath(new URL('../', import.meta.url))
const base = 'Qwen3.5-0.8B-Q4_K_M.gguf'
const tuned = 'Qwen3.5-0.8B-pandastic-Q4_K_M.gguf'
const sha256 = 'bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517'
const bytes = 532517120
const args = process.argv.slice(2)
if (args.includes('--help')) { console.log('Usage: node scripts/install-qwen.mjs [--device emulator-5554]\nUses a recognized local GGUF under ml/artifacts or QWEN_MODEL; otherwise downloads and verifies the base Q4_K_M model.'); process.exit(0) }
if (args.length && (args.length !== 2 || args[0] !== '--device' || !args[1])) throw new Error('Usage: install-qwen.mjs [--device SERIAL]')
const serial = args[1] || process.env.HUB || 'emulator-5554'
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || resolve(homedir(), 'Android/Sdk'), 'platform-tools/adb')
const run = (...values) => execFileSync(adb, ['-s', serial, ...values], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
const files = []
function walk(directory) {
  if (!existsSync(directory)) return
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) walk(path)
    else if ([base, tuned].includes(entry.name) && statSync(path).size > 100000000) files.push(path)
  }
}
walk(resolve(root, 'ml/artifacts'))
let model = process.env.QWEN_MODEL ? resolve(process.env.QWEN_MODEL) : files.find(file => file.endsWith(tuned)) || files.find(file => file.endsWith(base))
for (const name of [tuned, base]) {
  if (model && name !== model.split('/').at(-1)) continue
  try {
    if (Number(run('shell', 'run-as', 'org.pandastic.relay', 'stat', '-c', '%s', `files/models/${name}`)) > 100000000) {
      console.log(`${serial}: ${name} already installed.`)
      process.exit(0)
    }
  } catch { /* not installed yet */ }
}
if (!model) {
  model = resolve(root, 'ml/artifacts/llm', base)
  mkdirSync(dirname(model), { recursive: true })
  console.log(`Downloading Qwen3.5-0.8B Q4_K_M (${Math.round(bytes / 1000000)} MB)…`)
  const response = await fetch(`https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/${base}`, { signal: AbortSignal.timeout(1800000) })
  if (!response.ok || !response.body) throw new Error(`Qwen download failed: HTTP ${response.status}`)
  const temporary = `${model}.download`
  await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary))
  renameSync(temporary, model)
}
const name = model.split('/').at(-1)
if (![base, tuned].includes(name)) throw new Error(`Use one of the model filenames accepted by the app: ${base}, ${tuned}`)
let magic = ''
const hash = createHash('sha256')
for await (const chunk of createReadStream(model)) { if (!magic) magic = chunk.subarray(0, 4).toString(); hash.update(chunk) }
if (magic !== 'GGUF') throw new Error('Model is not a GGUF file')
const digest = hash.digest('hex')
if (name === base && (statSync(model).size !== bytes || digest !== sha256)) throw new Error('Base Qwen file failed its published SHA-256/size check')
console.log(`Verified ${name}: sha256 ${digest}`)
run('shell', 'run-as', 'org.pandastic.relay', 'mkdir', '-p', 'files/models')
execFileSync(adb, ['-s', serial, 'push', model, '/data/local/tmp/pandastic-qwen.gguf'], { stdio: 'inherit' })
try {
  run('shell', 'run-as', 'org.pandastic.relay', 'cp', '/data/local/tmp/pandastic-qwen.gguf', `files/models/${name}.tmp`)
  run('shell', 'run-as', 'org.pandastic.relay', 'mv', `files/models/${name}.tmp`, `files/models/${name}`)
} finally { run('shell', 'rm', '-f', '/data/local/tmp/pandastic-qwen.gguf') }
console.log(`${serial}: installed ${name}. Restart the app or tap Models → Load models.`)
