import { createRequire } from 'node:module'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Development transport only. Each Vite process owns a phone and sends over HTTP to its peer.
export function localPhonePlugin() {
  if (!process.env.PANDASTIC_PHONE_PORT) return null
  const { DatabaseSync } = createRequire(import.meta.url)('node:sqlite')
  const number = process.env.PANDASTIC_PHONE_PORT
  const peer = process.env.PANDASTIC_PEER_PORT
  const token = process.env.PANDASTIC_PHONE_TOKEN
  const initialMode = process.env.PANDASTIC_PHONE_MODE === 'lite' ? 'lite' : 'capable'
  if (![number, peer].every(validPort) || !token || number === peer) throw new Error('Invalid local phone configuration')
  const directory = resolve(process.env.PANDASTIC_PHONE_STATE_DIR || '.local-phones')
  // Docker: the helper phone's real Java brain (desktop/BrainServer.java). Without it, the labelled local demo answers.
  // A deployed demo (README.md) is opened by its server's name or IP: PANDASTIC_PUBLIC_HOST adds that origin.
  const publicHost = process.env.PANDASTIC_PUBLIC_HOST
  const origins = [`http://localhost:${number}`, `http://127.0.0.1:${number}`, ...(publicHost ? [`http://${publicHost}:${number}`, `https://${publicHost}:${number}`] : [])]
  const brainUrl = process.env.PANDASTIC_BRAIN_URL?.replace(/\/$/, '')
  // Docker demo: answer every allowlisted SMS, personal ones with the menu. The app (HubPolicy) leaves those unanswered.
  const answerAll = process.env.PANDASTIC_HUB_ANSWER_ALL === '1'
  // Automatic replies per contact per hour (airtime); a shared demo raises it.
  const hourlyLimit = Number(process.env.PANDASTIC_HUB_HOURLY_LIMIT) || 12
  async function brain(path, body) {
    const response = await fetch(`${brainUrl}${path}`, {
      method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json' },
      body: body && JSON.stringify(body), signal: AbortSignal.timeout(90000),
    })
    if (!response.ok) throw new Error(`brain ${response.status}`)
    return response.json()
  }
  mkdirSync(directory, { recursive: true })
  const file = resolve(directory, `${number}.json`)
  const db = new DatabaseSync(resolve('../android/app/src/main/assets/models/knowledge.sqlite'), { readOnly: true })
  let state = {
    mode: initialMode, number,
    chat: { peer, messages: [], smsPermission: true },
    hub: { enabled: initialMode === 'capable', running: initialMode === 'capable', lang: 'sw',
      contacts: [{ name: initialMode === 'lite' ? 'Helper' : 'Mama', number: peer }],
      smsPermission: true, notificationPermission: true, answeredToday: 0, recent: [] },
    delivered: [], nextId: 1, answeredDate: new Date().toISOString().slice(0, 10),
  }
  try { state = { ...state, ...JSON.parse(readFileSync(file, 'utf8')), number } } catch { /* first launch */ }
  state.chat.messages.forEach(message => { if (message.status === 'sending') message.status = 'unknown' })
  state.hub.recent.forEach(entry => { if (entry.status === 'pending') entry.status = 'cancelled' })
  const clients = new Set()
  const timers = new Set()
  const snapshot = () => ({ mode: state.mode, number, chat: state.chat, hub: state.hub, brain: Boolean(brainUrl) })
  function publish() {
    state.chat.messages = state.chat.messages.slice(-300)
    state.hub.recent = state.hub.recent.slice(-100)
    state.delivered = state.delivered.slice(-600)
    writeFileSync(file, JSON.stringify(state))
    for (const client of clients) client.write(`data: ${JSON.stringify(snapshot())}\n\n`)
  }
  function addMessage(remote, body, direction, status, time = Date.now()) {
    const message = { id: state.nextId++, number: remote, body, direction, time, status }
    state.chat.messages.push(message)
    return message
  }
  async function send(id, remote, body, automatic = false) {
    if (remote !== peer) return { ok: false, error: 'peer' }
    const previous = state.chat.messages.find(message => message.requestId === id)
    if (previous) return previous.status === 'sent' ? { ok: true } : { ok: false, error: 'unknown' }
    // The helper answers in the background: its automatic replies stay out of its own chat (the helper log has them).
    const message = automatic ? { status: 'sending' } : addMessage(remote, body, 'out', 'sending')
    message.requestId = id
    publish()
    try {
      const result = await fetch(`http://127.0.0.1:${peer}/__phone/deliver`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Pandastic-Phone': token },
        body: JSON.stringify({ id, from: number, body, automatic }), signal: AbortSignal.timeout(5000),
      })
      if (!result.ok) throw new Error('delivery failed')
      message.status = 'sent'
      publish()
      return { ok: true }
    } catch (error) {
      // A timeout may follow delivery. Keep the uncertain result visible and never retry automatically.
      const refused = error.cause?.code === 'ECONNREFUSED'
      message.status = refused ? 'failed' : 'unknown'
      publish()
      return { ok: false, error: refused ? 'offline' : 'unknown' }
    }
  }
  return {
    name: 'pandastic-local-phone',
    apply: 'serve',
    config() { return { cacheDir: resolve(directory, `vite-${number}`) } },
    transformIndexHtml() {
      return [{ tag: 'script', children: `window.__pandasticLocalPhone=${JSON.stringify(snapshot()).replace(/</g, '\\u003c')};`, injectTo: 'head-prepend' }]
    },
    configureServer(server) {
      server.middlewares.use('/__phone', async (req, res) => {
        const respond = (data, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)) }
        const path = req.url?.split('?')[0]
        // The peer delivery route is private to the pair; UI writes must come from this origin.
        if (path === '/deliver') {
          if (req.headers['x-pandastic-phone'] !== token) return respond({ error: 'forbidden' }, 403)
        } else if (req.headers.origin && !origins.includes(req.headers.origin)) {
          return respond({ error: 'origin' }, 403)
        }
        if (req.method === 'GET' && path === '/state') return respond(snapshot())
        if (req.method === 'GET' && path === '/brain') {
          if (!brainUrl) return respond({ error: 'no_brain' }, 404)
          try { return respond(await brain('/info')) } catch { return respond({ error: 'brain_unavailable' }, 502) }
        }
        if (req.method === 'GET' && path === '/events') {
          res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
          res.write(`data: ${JSON.stringify(snapshot())}\n\n`)
          clients.add(res)
          const heartbeat = setInterval(() => res.write(': heartbeat\n\n'), 15000)
          res.on('close', () => { clients.delete(res); clearInterval(heartbeat) })
          return
        }
        if (req.method !== 'POST') return respond({ error: 'not_found' }, 404)
        try {
          let raw = ''
          for await (const chunk of req) {
            raw += chunk
            if (raw.length > (path === '/photo' ? 4000000 : 8192)) return respond({ error: 'too_large' }, 413)
          }
          const data = JSON.parse(raw)
          if (path === '/send') {
            if (typeof data.id !== 'string' || !data.id || typeof data.body !== 'string' || !data.body.trim() || data.body.length > 480) return respond({ error: 'invalid' }, 400)
            return respond(await send(data.id, data.number, data.body.trim()))
          }
          if ((path === '/ask' || path === '/photo') && brainUrl) {
            if (typeof data.text !== 'string' || data.text.length > 480 || !['sw', 'en'].includes(data.lang) || (path === '/photo' && typeof data.image !== 'string')) return respond({ error: 'invalid' }, 400)
            try { return respond(await brain(path, { text: data.text, lang: data.lang, image: data.image })) }
            catch { return respond({ status: 'ERROR', error: 'brain_unavailable', escalate: true }, 502) }
          }
          if (path === '/deliver') {
            if (data.from !== peer || typeof data.id !== 'string' || !data.id || typeof data.body !== 'string' || !data.body.trim() || data.body.length > 1000 || typeof data.automatic !== 'boolean') return respond({ error: 'invalid' }, 400)
            if (state.delivered.includes(data.id)) return respond({ ok: true })
            state.delivered.push(data.id)
            const receivedAt = Date.now()
            // The SMS shows in the chat unless the helper answers it automatically (then it lives in the helper log only).
            const show = () => { addMessage(data.from, data.body, 'in', 'received', receivedAt); publish() }
            // Automatic responses never cause another response, even if both phones enable their hub.
            if (data.automatic || state.mode !== 'capable' || !state.hub.enabled || !state.hub.contacts.some(contact => contact.number === data.from)) show()
            else {
              const now = new Date().toISOString().slice(0, 10)
              if (state.answeredDate !== now) { state.answeredDate = now; state.hub.answeredToday = 0 }
              const recent = state.hub.recent.filter(entry => entry.contact === data.from && entry.receivedAt > Date.now() - 3600000)
              const entry = { id: state.nextId++, contact: data.from, question: data.body, reply: null, status: recent.length >= hourlyLimit ? 'rate_limited' : 'pending', receivedAt }
              state.hub.recent.push(entry)
              if (entry.status !== 'pending') show()
              else {
                const timer = setTimeout(async () => {
                  timers.delete(timer)
                  if (!state.hub.enabled || state.mode !== 'capable' || !state.hub.contacts.some(contact => contact.number === data.from)) { entry.status = 'cancelled'; show(); return }
                  if (brainUrl) {
                    // Same rule as HubService: personal messages get no reply, and the log keeps no copy.
                    let answer
                    try { answer = await brain('/sms', { text: data.body }) }
                    catch (error) { console.error(`Brain unavailable (${error.message}); local demo answer sent.`) }
                    // A personal message is not answered and shows in the chat like any SMS.
                    if (answer && !answer.farming && !answerAll) { entry.status = 'personal'; entry.question = ''; show(); return }
                    entry.reply = answer ? answer.reply : localAnswer(db, data.body, state.hub.lang)
                  } else entry.reply = localAnswer(db, data.body, state.hub.lang)
                  const result = await send(`reply-${data.id}`, data.from, entry.reply, true)
                  entry.status = result.ok ? 'sent' : 'failed'
                  if (result.ok) state.hub.answeredToday++
                  if (result.ok) publish(); else show()
                }, 250)
                timers.add(timer)
              }
            }
            publish()
            return respond({ ok: true })
          }
          if (path === '/update') {
            const { operation, value } = data
            if (operation === 'peer' && (value === '' || validPort(value))) state.chat.peer = value
            else if (operation === 'mode' && ['lite', 'capable'].includes(value)) { state.mode = value; if (value === 'lite') state.hub.enabled = false }
            else if (operation === 'enabled' && typeof value === 'boolean') state.hub.enabled = value && state.mode === 'capable' && state.hub.contacts.length > 0
            else if (operation === 'contacts' && Array.isArray(value) && value.length <= 20 && value.every(contact => typeof contact.name === 'string' && validPort(contact.number))) { state.hub.contacts = value; if (!value.length) state.hub.enabled = false }
            else if (operation === 'lang' && ['sw', 'en'].includes(value)) state.hub.lang = value
            else if (operation === 'clearChat') state.chat.messages = []
            else if (operation === 'clearHub') { state.hub.recent = []; state.hub.answeredToday = 0 }
            else return respond({ error: 'invalid' }, 400)
            state.hub.running = state.hub.enabled && state.mode === 'capable'
            publish()
            return respond(snapshot())
          }
          return respond({ error: 'not_found' }, 404)
        } catch { return respond({ error: 'invalid_request' }, 400) }
      })
      server.httpServer?.on('close', () => {
        for (const timer of timers) clearTimeout(timer)
        for (const client of clients) client.end()
        db.close()
      })
    },
  }
}

export function validPort(value) {
  return typeof value === 'string' && /^\d{4,5}$/.test(value) && Number(value) >= 1024 && Number(value) <= 65535
}

// Browser SMS demo: templates + the bundled SQLite prices, without pretending to run native AI.
export function localAnswer(db, text, lang) {
  const english = lang === 'en'
  const normalized = text.toLowerCase().trim()
  const code = normalized.match(/^p\s+([123])(?:\s+([\d,]+))?$/)
  const crop = code ? { 1: 'coffee', 2: 'maize', 3: 'beans' }[code[1]] : /\b(coffee|kahawa|emmwanyi)\b/.test(normalized) ? 'coffee' : /\b(maize|mahindi|kasooli)\b/.test(normalized) ? 'maize' : /\b(beans|maharage|ebijanjaalo)\b/.test(normalized) ? 'beans' : null
  if (code || /\b(price|bei|sell|kuuza)\b/.test(normalized)) {
    if (!crop) return english ? 'Local demo: Which crop? Coffee, maize or beans?' : 'Onyesho la ndani: Zao gani? Kahawa, mahindi au maharage?'
    const commodity = { coffee: 'coffee_arabica_parchment', maize: 'maize_grain', beans: 'beans_dry' }[crop]
    const price = db.prepare('SELECT p.*, s.publisher FROM latest_prices p JOIN sources s ON s.id=p.source_id WHERE p.commodity=? AND p.market IS NULL LIMIT 1').get(commodity)
    if (!price) return english ? 'Local demo: No saved price for this crop. Ask the cooperative.' : 'Onyesho la ndani: Hakuna bei iliyohifadhiwa. Uliza chama.'
    const offerText = code ? code[2] : normalized.match(/\d[\d,]*/)?.[0]
    const offer = Number((offerText || '').replaceAll(',', ''))
    const low = Number(price.price_low).toLocaleString('en-US')
    const high = Number(price.price_high).toLocaleString('en-US')
    const name = english ? { coffee: 'Arabica parchment coffee', maize: 'Maize', beans: 'Beans' }[crop] : { coffee: 'Kahawa Arabica (parchment)', maize: 'Mahindi', beans: 'Maharage' }[crop]
    const gap = offer > 0 && offer < price.price_low ? Math.round((price.price_low - offer) / price.price_low * 100) : 0
    const comparison = gap ? english ? ` Your offer is ${gap}% below the reference.` : ` Bei yako iko chini kwa ${gap}%.` : ''
    return english ? `Local demo: ${name}: ${price.currency} ${low}${low !== high ? `–${high}` : ''}/${price.unit}, ${price.date} (${price.publisher}).${comparison} Ask the cooperative before selling.` : `Onyesho la ndani: ${name}: ${price.currency} ${low}${low !== high ? `–${high}` : ''}/${price.unit}, ${price.date} (${price.publisher}).${comparison} Uliza chama kabla ya kuuza.`
  }
  if (normalized === '?' || /\b(help|msaada)\b/.test(normalized)) return english ? 'Local demo: P 1 12000 = coffee price; P 2 = maize; P 3 = beans. Describe a leaf problem, or ask an extension officer.' : 'Onyesho la ndani: P 1 12000 = bei ya kahawa; P 2 = mahindi; P 3 = maharage. Eleza tatizo la majani, au uliza afisa ugani.'
  return english ? 'Not sure from words alone. Do not spray yet. Ask an extension officer. Local demo: native AI runs in the Android app.' : 'Sina uhakika kwa maneno pekee. Usinyunyizie dawa bado. Uliza afisa ugani. Onyesho la ndani: AI inaendesha kwenye app ya Android.'
}
