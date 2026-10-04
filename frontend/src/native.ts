// Bridge to the Android app (window.PandasticNative, see NativeBridge.java).
// In a desktop browser there is no bridge, so a clearly labelled demo mode answers instead.
import { askLocalBrain, hasLocalBrain, isLocalPhone, localBrainInfo, localPhoneState, sendLocalSms, updateLocalPhone } from './local-phone'
export { hasLocalBrain, isLocalPhone, localPhoneNumber } from './local-phone'

export type Lang = 'sw' | 'en'
export type PhoneMode = 'lite' | 'capable'
export type ChatMessage = { id: number; number: string; body: string; direction: 'in' | 'out'; time: number; status: string }
export type ChatStatus = { peer: string; messages: ChatMessage[]; smsPermission: boolean }
export type SmsResult = { ok: boolean; error?: string }
export type ModelFile = { installed: boolean; loaded: boolean; bytes: number; error?: string | null }
export type ModelStatus = {
  classifier?: ModelFile & { version: string; stub: boolean }
  language?: ModelFile & { name: string; runtimeAvailable: boolean }
  knowledge?: ModelFile
  busy?: boolean
  error?: string
}

export type Price = {
  commodity: string
  name?: string
  market?: string | null
  pricetype?: string
  low: number
  high: number
  currency: string
  unit: string
  date: string
  source_id: string
  offer?: number | null
  gap_pct?: number | null
  stale?: boolean
}

// Contracts §2 decision object. Fields are optional because the interim resolver sends fewer.
export type Decision = {
  status: string
  intent?: string
  title?: string
  message?: string
  translation?: string
  crop?: string
  label?: string
  prob?: number
  runner_up?: string
  runner_up_prob?: number
  advice_sms?: string
  advice_long?: string
  source?: { id: string; title?: string; publisher?: string; url?: string; licence?: string }
  price?: Price
  escalate?: boolean
  stub?: boolean
  /** Who read the words: 'model', 'model_agreed', 'keywords_model_disagreed', ... (BrainHost). */
  nlu?: string
  /** One fixed line, e.g. "AI ya simu imeelewa: bei, kahawa, 12,000." Present only when the on-phone language model read the words. */
  understood?: string
  /** The on-phone model's own wording of this answer, checked word by word against it (ReplyWriter.java). */
  ai_reply?: string
  quality?: string
  error?: string
}

export type HubContact = { name: string; number: string }
export type PhoneContacts = { contacts: HubContact[]; number?: string; numberSource?: string; error?: string }
export type HubEntry = { id: number; contact: string; question: string; reply: string | null; status: string; receivedAt: number }
export type HubStatus = {
  enabled: boolean
  running: boolean
  lang: Lang
  contacts: HubContact[]
  smsPermission: boolean
  notificationPermission: boolean
  answeredToday: number
  recent: HubEntry[]
}

type Native = {
  phoneInfo(): string
  phoneContacts?(id: string): void
  setPhoneMode(mode: PhoneMode): void
  chatStatus(): string
  setSmsPeer(number: string): void
  enableSms(): void
  sendSms(id: string, number: string, body: string): void
  clearChatHistory(): void
  checkPhoto(id: string, base64Jpeg: string, text: string, lang: string): void
  ask(id: string, text: string, lang: string): void
  info(): string
  modelStatus(): string
  manageModels(id: string, action: 'load' | 'unload' | 'import'): void
  hubStatus(): string
  setHubEnabled(enabled: boolean): void
  setHubContacts(json: string): void
  setHubLang(lang: string): void
  clearHubHistory(): void
  draftSms(number: string, body: string): void
  share(text: string): void
  speak(text: string, lang: string): boolean
  voices(): string
  stopSpeaking(): void
  dictationStatus?(): string
  startDictation?(id: string, lang: string): void
  stopDictation?(): void
  cancelDictation?(): void
}

declare global {
  interface Window {
    PandasticNative?: Native
    __pandasticReply?: (id: string, decision: Decision) => void
    __pandasticSmsReply?: (id: string, result: SmsResult) => void
    __pandasticModelReply?: (id: string, result: SmsResult) => void
    __pandasticContactsReply?: (id: string, result: PhoneContacts) => void
  }
}

const native = window.PandasticNative
export const isDemo = !native
export const canSms = Boolean(native) || isLocalPhone

export function phoneInfo(): { mode?: PhoneMode; totalRamMb?: number; number?: string; numberSource?: string } {
  try {
    if (native) return JSON.parse(native.phoneInfo())
    if (isLocalPhone) return { mode: localPhoneState()?.mode }
    const mode = localStorage.getItem('pandastic.phone-mode')
    return { mode: mode === 'lite' || mode === 'capable' ? mode : undefined }
  } catch { return {} }
}

export const canSuggestContacts = Boolean(native?.phoneContacts)
export function phoneContacts(): Promise<PhoneContacts> {
  if (!native?.phoneContacts) return Promise.resolve({ contacts: [] })
  const id = crypto.randomUUID()
  return new Promise(resolve => {
    const timer = setTimeout(() => { contactRequests.delete(id); resolve({ contacts: [], error: 'unavailable' }) }, 90000)
    contactRequests.set(id, value => { clearTimeout(timer); resolve(value) })
    native.phoneContacts!(id)
  })
}
const contactRequests = new Map<string, (value: PhoneContacts) => void>()
window.__pandasticContactsReply = (id, value) => {
  const complete = contactRequests.get(id)
  contactRequests.delete(id)
  complete?.(value)
}

export function setPhoneMode(mode: PhoneMode) {
  if (native) native.setPhoneMode(mode)
  else if (isLocalPhone) void updateLocalPhone('mode', mode)
  else {
    try { localStorage.setItem('pandastic.phone-mode', mode) } catch { /* session only */ }
    if (mode === 'lite') setHubEnabled(false)
  }
}

export function validNumber(number: string): boolean {
  if (isLocalPhone) return /^\d{4,5}$/.test(number.trim()) && Number(number) >= 1024 && Number(number) <= 65535
  const digits = number.replace(/[^0-9]/g, '')
  return /^\+?[0-9 ()-]+$/.test(number.trim()) && digits.length >= 7 && digits.length <= 15
}

export function sameNumber(a: string, b: string): boolean {
  if (isLocalPhone) return validNumber(a) && validNumber(b) && a.trim() === b.trim()
  if (!validNumber(a) || !validNumber(b)) return false
  const digits = (value: string) => value.replace(/[^0-9]/g, '')
  const international = (value: string) => value.trim().startsWith('+') ? digits(value) : value.trim().startsWith('00') ? digits(value).slice(2) : null
  const fullA = international(a), fullB = international(b)
  return digits(a).slice(-9) === digits(b).slice(-9) && (!fullA || !fullB || fullA === fullB)
}

let demoChat: ChatStatus = { peer: '', messages: [], smsPermission: false }
try { demoChat.peer = localStorage.getItem('pandastic.sms-peer') ?? '' } catch { /* session only */ }
const chatListeners = new Set<(status: ChatStatus) => void>()
window.addEventListener('pandastic:chat', event => {
  chatListeners.forEach(listener => listener((event as CustomEvent<ChatStatus>).detail))
})
export function chatStatus(): ChatStatus {
  if (isLocalPhone) return localPhoneState()!.chat
  if (!native) return demoChat
  try { return JSON.parse(native.chatStatus()) } catch { return { peer: '', messages: [], smsPermission: false } }
}
export function onChatChange(listener: (status: ChatStatus) => void): () => void {
  chatListeners.add(listener)
  return () => { chatListeners.delete(listener) }
}
export function setSmsPeer(number: string) {
  if (native) native.setSmsPeer(number)
  else if (isLocalPhone) void updateLocalPhone('peer', number.trim())
  else {
    demoChat = { ...demoChat, peer: number.trim() }
    try { localStorage.setItem('pandastic.sms-peer', demoChat.peer) } catch { /* session only */ }
    chatListeners.forEach(listener => listener(demoChat))
  }
}
export function enableSms() { native?.enableSms() }
export function clearChatHistory() {
  if (native) native.clearChatHistory()
  else if (isLocalPhone) void updateLocalPhone('clearChat')
  else {
    demoChat = { ...demoChat, messages: [] }
    chatListeners.forEach(listener => listener(demoChat))
  }
}
const smsWaiting = new Map<string, (result: SmsResult) => void>()
window.__pandasticSmsReply = (id, result) => {
  smsWaiting.get(id)?.(result)
  smsWaiting.delete(id)
}
export function sendSms(number: string, body: string): Promise<SmsResult> {
  if (isLocalPhone) return sendLocalSms(number, body)
  if (!native) return Promise.resolve({ ok: false, error: 'browser' })
  const id = crypto.randomUUID()
  return new Promise(resolve => {
    // A permission dialog can stay open; do not treat a timeout as permission to retry a send.
    const timer = setTimeout(() => {
      smsWaiting.delete(id)
      resolve({ ok: false, error: 'unknown' })
    }, 180000)
    smsWaiting.set(id, result => { clearTimeout(timer); resolve(result) })
    native.sendSms(id, number, body)
  })
}
const waiting = new Map<string, (decision: Decision) => void>()
window.__pandasticReply = (id, decision) => {
  waiting.get(id)?.(decision)
  waiting.delete(id)
}

function call(start: (id: string) => void, timeoutMs = 45000): Promise<Decision> {
  const id = crypto.randomUUID()
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      waiting.delete(id)
      resolve({ status: 'ERROR', error: 'timeout', escalate: true })
    }, timeoutMs)
    waiting.set(id, decision => { clearTimeout(timer); resolve(decision) })
    start(id)
  })
}

/** Shrinks the photo before it crosses the bridge; the model only needs 224 px. */
async function toJpegBase64(file: File, maxSide = 640): Promise<string> {
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bitmap.width * scale)
  canvas.height = Math.round(bitmap.height * scale)
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close()
  return canvas.toDataURL('image/jpeg', 0.88).split(',')[1]
}

export async function photoPreview(file: File): Promise<string> {
  return `data:image/jpeg;base64,${await toJpegBase64(file, 320)}`
}

export async function checkPhoto(file: File, lang: Lang, text = ''): Promise<Decision> {
  if (!native && hasLocalBrain) return askLocalBrain<Decision>('/photo', { image: await toJpegBase64(file), text, lang })
  if (!native) return demoPhoto(file)
  const base64 = await toJpegBase64(file)
  return call(id => native.checkPhoto(id, base64, text, lang))
}

export function ask(text: string, lang: Lang): Promise<Decision> {
  if (!native && hasLocalBrain) return askLocalBrain<Decision>('/ask', { text, lang })
  if (!native) return demoAsk(text)
  return call(id => native.ask(id, text, lang))
}

export function modelInfo(): { classifier?: string; classifierStub?: boolean } {
  if (!native && hasLocalBrain) return localBrainInfo() ?? {}
  if (!native) return { classifier: 'demo', classifierStub: true }
  try { return JSON.parse(native.info()) } catch { return {} }
}

export function modelStatus(): ModelStatus {
  const brain = localBrainInfo()
  if (!native && brain) return {
    classifier: { installed: true, loaded: true, version: brain.classifier ?? '', stub: Boolean(brain.classifierStub), bytes: 0 },
    language: { installed: Boolean(brain.llm), loaded: Boolean(brain.llm), name: brain.llm ?? 'Qwen3.5-0.8B-pandastic-Q4_K_M.gguf', runtimeAvailable: Boolean(brain.runtimeAvailable), bytes: 0 },
    knowledge: { installed: true, loaded: true, bytes: 0 },
  }
  if (!native) return {
    classifier: { installed: true, loaded: false, version: 'Preview', stub: true, bytes: 0 },
    language: { installed: false, loaded: false, name: 'Qwen3.5-0.8B-Q4_K_M.gguf', runtimeAvailable: false, bytes: 0 },
    knowledge: { installed: true, loaded: false, bytes: 0 },
  }
  try { return JSON.parse(native.modelStatus()) } catch { return { error: 'model_status' } }
}
const modelListeners = new Set<(status: ModelStatus) => void>()
window.addEventListener('pandastic:models', event => {
  modelListeners.forEach(listener => listener((event as CustomEvent<ModelStatus>).detail))
})
window.addEventListener('pandastic:local-brain', () => {
  const status = modelStatus()
  modelListeners.forEach(listener => listener(status))
})
export function onModelsChange(listener: (status: ModelStatus) => void): () => void {
  modelListeners.add(listener)
  return () => { modelListeners.delete(listener) }
}
const modelWaiting = new Map<string, (result: SmsResult) => void>()
window.__pandasticModelReply = (id, result) => {
  modelWaiting.get(id)?.(result)
  modelWaiting.delete(id)
}
export function manageModels(action: 'load' | 'unload' | 'import'): Promise<SmsResult> {
  if (!native) return Promise.resolve({ ok: false, error: 'browser' })
  const id = crypto.randomUUID()
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      modelWaiting.delete(id)
      resolve({ ok: false, error: 'unknown' })
    }, 600000)
    modelWaiting.set(id, result => { clearTimeout(timer); resolve(result) })
    native.manageModels(id, action)
  })
}

// ---- SMS helper ---------------------------------------------------------------------------

let demoHub: HubStatus = {
  enabled: false, running: false, lang: 'sw', smsPermission: false, notificationPermission: false,
  answeredToday: 0, contacts: [], recent: [],
}
const hubListeners = new Set<(status: HubStatus) => void>()
window.addEventListener('pandastic:hub', event => {
  const status = (event as CustomEvent<HubStatus>).detail
  hubListeners.forEach(listener => listener(status))
})

export function hubStatus(): HubStatus {
  if (isLocalPhone) return localPhoneState()!.hub
  if (!native) return demoHub
  try { return JSON.parse(native.hubStatus()) } catch { return { ...demoHub, enabled: false, recent: [], contacts: [] } }
}

export function onHubChange(listener: (status: HubStatus) => void): () => void {
  hubListeners.add(listener)
  return () => { hubListeners.delete(listener) }
}

function demoUpdate(change: Partial<HubStatus>) {
  demoHub = { ...demoHub, ...change }
  hubListeners.forEach(listener => listener(demoHub))
}

export function setHubEnabled(enabled: boolean) {
  if (native) native.setHubEnabled(enabled)
  else if (isLocalPhone) void updateLocalPhone('enabled', enabled)
  else demoUpdate({ enabled, running: enabled })
}

export function setHubContacts(contacts: HubContact[]) {
  if (native) native.setHubContacts(JSON.stringify(contacts))
  else if (isLocalPhone) void updateLocalPhone('contacts', contacts)
  else demoUpdate({ contacts })
}

export function setHubLang(lang: Lang) {
  if (native) native.setHubLang(lang)
  else if (isLocalPhone) void updateLocalPhone('lang', lang)
  else demoUpdate({ lang })
}

export function clearHubHistory() {
  if (native) native.clearHubHistory()
  else if (isLocalPhone) void updateLocalPhone('clearHub')
  else demoUpdate({ recent: [], answeredToday: 0 })
}

// ---- People and voice ---------------------------------------------------------------------

/** Opens the SMS app with a draft. The person reads it and decides whether to send. */
export function draftSms(number: string, body: string) {
  if (native) native.draftSms(number, body)
  else window.open(`sms:${encodeURIComponent(number)}?body=${encodeURIComponent(body)}`)
}

export function share(text: string) {
  if (native) native.share(text)
  else if (navigator.share) void navigator.share({ text }).catch(() => undefined)
}

export function canSpeak(lang: Lang): boolean {
  if (!native) return false
  try { return Boolean(JSON.parse(native.voices())[lang]) } catch { return false }
}

export function speechStatus(): { ready?: boolean; sw?: boolean; en?: boolean; speaking?: boolean } {
  if (!native) return { ready: false }
  try { return JSON.parse(native.voices()) } catch { return { ready: false } }
}

export function speak(text: string, lang: Lang): boolean {
  return native ? native.speak(text, lang) : false
}

export function stopSpeaking() { native?.stopSpeaking() }

// ---- Demo answers (desktop browser only) ---------------------------------------------------

const demoAdvice: Record<string, string> = {
  coffee_rust: 'Coffee leaf rust: yellow powdery spots under the leaves; leaves fall and branches can die. 1) Keep the recommended spacing and prune to open the bush. 2) Remove fallen leaves. 3) Spray copper under the leaves only if the extension officer agrees.',
  coffee_healthy: 'No disease seen on this leaf. Keep checking under the leaves every week.',
}

async function demoPhoto(file: File): Promise<Decision> {
  await new Promise(resolve => setTimeout(resolve, 1400))
  const pick = file.size % 4
  if (pick === 0) return { status: 'CONFIDENT', crop: 'coffee', label: 'coffee_rust', prob: 0.86, runner_up: 'coffee_cercospora', runner_up_prob: 0.07, advice_long: demoAdvice.coffee_rust, source: { id: 'plantwise-rw014-coffee-rust', title: 'PlantwisePlus: Coffee leaf rust' }, escalate: false, stub: true }
  if (pick === 1) return { status: 'CONFIDENT', crop: 'coffee', label: 'coffee_healthy', prob: 0.91, advice_long: demoAdvice.coffee_healthy, source: { id: 'plantwise-rw014-coffee-rust', title: 'PlantwisePlus: Coffee leaf rust' }, escalate: false, stub: true }
  if (pick === 2) return { status: 'UNCERTAIN', crop: 'coffee', label: 'coffee_cercospora', prob: 0.48, runner_up: 'coffee_rust', runner_up_prob: 0.39, escalate: true, stub: true }
  return { status: 'RETAKE', quality: 'blur', escalate: true, stub: true }
}

const demoPrices: Record<string, Price> = {
  coffee: { commodity: 'coffee_arabica_parchment', low: 15500, high: 15500, currency: 'UGX', unit: 'KG', date: '2026-08', source_id: 'ucda-2026-08' },
  maize: { commodity: 'maize_grain', low: 1273, high: 1888, currency: 'UGX', unit: 'KG', date: '2026-08', source_id: 'wfp-hdx-uga-derived' },
  beans: { commodity: 'beans_dry', low: 2875, high: 4273, currency: 'UGX', unit: 'KG', date: '2026-08', source_id: 'wfp-hdx-uga-derived' },
}

async function demoAsk(text: string): Promise<Decision> {
  await new Promise(resolve => setTimeout(resolve, 700))
  const crop = /kahawa|coffee/.test(text) ? 'coffee' : /mahindi|maize/.test(text) ? 'maize' : /maharage|beans/.test(text) ? 'beans' : null
  const offer = Number((text.match(/\d[\d,]*/)?.[0] ?? '').replace(/,/g, '')) || null
  if (!crop) return { status: 'NO_DATA', escalate: true }
  const price = { ...demoPrices[crop], offer, gap_pct: offer ? Math.round((offer - demoPrices[crop].low) / demoPrices[crop].low * 1000) / 10 : null, stale: false }
  return { status: 'PRICE', crop, price, escalate: false, stub: true }
}
