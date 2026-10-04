#!/usr/bin/env node
// Leaf classifier through the real app on an emulator or phone: every photo is shrunk exactly like the UI does
// and sent to checkPhoto (quality gate, classifier, thresholds, Resolver), so the numbers are the app's own.
// The model install check (D15) compares two builds on the same photos.
//
//   node scripts/photo-eval.mjs photos.csv [out.csv]   # photos.csv: path,label (label "other" = not a crop leaf)
//   ADB=/path/to/adb DEVICE=emulator-5554 node scripts/photo-eval.mjs photos.csv
//
// Prints: answered (CONFIDENT), right when answered, "healthy" said for a sick leaf, non-crop photos answered,
// and the median checkPhoto time measured inside the page (excludes the photo upload over DevTools).
import { readFileSync, writeFileSync } from 'node:fs'
import { connectApp } from './lib/devtools.mjs'

const [list, out] = process.argv.slice(2)
if (!list) { console.error('usage: node scripts/photo-eval.mjs photos.csv [out.csv]'); process.exit(2) }
const rows = readFileSync(list, 'utf8').trim().split(/\r?\n/).slice(1).map(line => {
  const [path, label] = line.split(',')
  return { path, label }
})
const keepAlive = setInterval(() => {}, 1000)
const { evaluate, close } = await connectApp(process.env.DEVICE)

const results = []
for (const [i, row] of rows.entries()) {
  const b64 = JSON.stringify(readFileSync(row.path).toString('base64'))
  let d
  try {
    d = await evaluate(`__e2e.shrink(${b64}).then(async small => {
      const started = performance.now()
      const d = await __e2e.reply('checkPhoto', [small, '', 'sw'], 60000)
      return { ...d, ms: performance.now() - started }
    })`, 120000)
  } catch (e) {  // the WebView cannot open the file (not a photo): the UI never sends it, so skip it
    console.error(`skipped ${row.path}: ${e.message}`)
    continue
  }
  results.push({ ...row, status: d.status, said: d.label, prob: d.prob, ms: d.ms })
  if ((i + 1) % 50 === 0) console.error(`${i + 1}/${rows.length}`)
}

const confident = results.filter(r => r.status === 'CONFIDENT')
const crop = results.filter(r => r.label !== 'other')
const cropAnswered = confident.filter(r => r.label !== 'other')
const right = cropAnswered.filter(r => r.said === r.label)
const sickCalledHealthy = cropAnswered.filter(r => !r.label.endsWith('_healthy') && r.said?.endsWith('_healthy'))
const sayHealthy = cropAnswered.filter(r => r.said?.endsWith('_healthy'))
const nonCrop = results.filter(r => r.label === 'other')
const ms = results.map(r => r.ms).sort((a, b) => a - b)
const pct = (a, b) => b ? `${(100 * a / b).toFixed(1)}% (${a}/${b})` : '-'
console.log(`crop photos answered ${pct(cropAnswered.length, crop.length)}, right when answered ${pct(right.length, cropAnswered.length)}`)
console.log(`"healthy" right ${pct(sayHealthy.length - sickCalledHealthy.length, sayHealthy.length)}; sick leaf called healthy ${sickCalledHealthy.length}`
  + (sickCalledHealthy.length ? ` (${sickCalledHealthy.map(r => r.path.split(/[\\/]/).pop()).join(', ')})` : ''))
if (nonCrop.length) console.log(`non-crop photos answered ${pct(nonCrop.filter(r => r.status === 'CONFIDENT').length, nonCrop.length)}`)
console.log(`checkPhoto median ${ms[ms.length >> 1].toFixed(0)} ms, p90 ${ms[Math.floor(ms.length * 0.9)].toFixed(0)} ms (in the page)`)
if (out) writeFileSync(out, 'path,label,status,said,prob,ms\n' + results.map(r => [r.path, r.label, r.status, r.said, r.prob, r.ms.toFixed(0)].join(',')).join('\n') + '\n')
clearInterval(keepAlive)
close?.()
process.exit(0)
