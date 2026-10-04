#!/usr/bin/env node
// Build-time download only. The verified multilingual model is bundled in the APK.
import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'

const model = fileURLToPath(new URL('../ml/artifacts/speech/ggml-tiny-q5_1.bin', import.meta.url))
const bytes = 32152673
const sha256 = '818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7'
async function verified(path) {
  if (!existsSync(path) || statSync(path).size !== bytes) return false
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex') === sha256
}
if (!await verified(model)) {
  mkdirSync(dirname(model), { recursive: true })
  const partial = `${model}.part-${process.pid}`
  try {
    console.log('Downloading multilingual Whisper Tiny Q5_1 (32.2 MB)…')
    const response = await fetch('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin', { signal: AbortSignal.timeout(180000) })
    if (!response.ok || !response.body) throw new Error(`Whisper download failed: HTTP ${response.status}`)
    await pipeline(Readable.fromWeb(response.body), createWriteStream(partial))
    if (!await verified(partial)) throw new Error('Whisper Tiny size or SHA-256 does not match the published model')
    renameSync(partial, model)
  } finally { rmSync(partial, { force: true }) }
}
console.log('Whisper Tiny: verified 32,152,673 bytes, English + Swahili, bundled for offline use.')
