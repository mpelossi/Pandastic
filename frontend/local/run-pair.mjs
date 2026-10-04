import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { fileURLToPath } from 'node:url'

const cwd = fileURLToPath(new URL('../', import.meta.url))
const basic = process.env.BASIC_PORT || '5173'
const capable = process.env.CAPABLE_PORT || '5174'
// Docker sets 0.0.0.0 so the published ports reach the phones; locally they stay on loopback.
const host = process.env.PANDASTIC_HOST || '127.0.0.1'
if (![basic, capable].every(port => /^\d{4,5}$/.test(port) && Number(port) >= 1024 && Number(port) <= 65535) || basic === capable) throw new Error('Choose two different ports between 1024 and 65535')
const token = randomUUID()
const children = []
let stopping = false
function stop(code = 0) {
  if (stopping) return
  stopping = true
  for (const child of children) child.kill('SIGTERM')
  process.exitCode = code
}
for (const [port, peer, mode] of [[basic, capable, 'lite'], [capable, basic, 'capable']]) {
  const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', host, '--port', port, '--strictPort'], {
    cwd, stdio: 'inherit', env: { ...process.env, PANDASTIC_PHONE_PORT: port, PANDASTIC_PEER_PORT: peer, PANDASTIC_PHONE_MODE: mode, PANDASTIC_PHONE_TOKEN: token },
  })
  children.push(child)
  child.on('error', error => { console.error(error.message); stop(1) })
  child.on('exit', (code, signal) => { if (!stopping) { console.error(`Phone ${port} stopped (${signal || code}). Closing the pair.`); stop(code || 1) } })
}
// Optional page showing both phones side by side (Docker sets PANDASTIC_DEMO_PORT=8080).
const demoPort = process.env.PANDASTIC_DEMO_PORT
if (demoPort) {
  const page = readFileSync(new URL('demo.html', import.meta.url), 'utf8').replace('__BASIC_PORT__', basic).replace('__CAPABLE_PORT__', capable)
  const demo = createServer((req, res) => {
    res.writeHead(req.url === '/' ? 200 : 404, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(req.url === '/' ? page : 'Not found')
  })
  demo.on('error', error => { console.error(`Demo page: ${error.message}`); stop(1) })
  demo.listen(Number(demoPort), host, () => console.log(`Both phones:   http://localhost:${demoPort}`))
  children.push({ kill: () => { demo.close(); demo.closeAllConnections() } })
}
process.on('SIGINT', () => stop())
process.on('SIGTERM', () => stop())
console.log(`\nBasic phone:   http://127.0.0.1:${basic} (number ${basic})\nCapable phone: http://127.0.0.1:${capable} (number ${capable})\nOpen both. Send “P 1 12000” from the basic phone. Ctrl+C stops both.\n`)
