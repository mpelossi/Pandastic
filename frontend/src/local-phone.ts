/// <reference types="vite/client" />
import type { ChatStatus, HubStatus, PhoneMode, SmsResult } from './native'

type LocalPhone = { number: string; mode: PhoneMode; chat: ChatStatus; hub: HubStatus; brain?: boolean }
declare global {
  interface Window { __pandasticLocalPhone?: LocalPhone }
}

// Injected only by the explicitly enabled development server. APK/normal previews stay unchanged.
export const isLocalPhone = !window.PandasticNative && Boolean(window.__pandasticLocalPhone)
let state = isLocalPhone ? window.__pandasticLocalPhone : undefined
export const localPhoneNumber = state?.number
export const localPhoneState = () => state
/** Docker demo: this local phone's questions and photos go to the real Java brain (desktop/BrainServer.java). */
export const hasLocalBrain = Boolean(state?.brain)
export type LocalBrainInfo = { classifier?: string; classifierStub?: boolean; llm?: string | null; runtimeAvailable?: boolean }
let brainInfo: LocalBrainInfo | undefined
export const localBrainInfo = () => brainInfo

function receive(next: LocalPhone) {
  state = next
  window.dispatchEvent(new CustomEvent('pandastic:chat', { detail: next.chat }))
  window.dispatchEvent(new CustomEvent('pandastic:hub', { detail: next.hub }))
}

if (isLocalPhone) {
  const events = new EventSource('/__phone/events')
  events.onmessage = event => receive(JSON.parse(event.data) as LocalPhone)
  // EventSource reconnects and receives the complete snapshot, including messages missed while away.
  if (import.meta.hot) import.meta.hot.dispose(() => events.close())
}

if (hasLocalBrain) {
  void fetch('/__phone/brain', { signal: AbortSignal.timeout(10000) }).then(response => response.ok ? response.json() : undefined)
    .then(info => { brainInfo = info; window.dispatchEvent(new CustomEvent('pandastic:local-brain')) }).catch(() => undefined)
}

export async function askLocalBrain<T>(path: '/ask' | '/photo', body: { text: string; lang: string; image?: string }): Promise<T | { status: string; error: string; escalate: boolean }> {
  try {
    const response = await fetch(`/__phone${path}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: AbortSignal.timeout(90000),
    })
    return await response.json() as T
  } catch { return { status: 'ERROR', error: 'timeout', escalate: true } }
}

export async function updateLocalPhone(operation: string, value?: unknown) {
  try {
    const response = await fetch('/__phone/update', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ operation, value }), signal: AbortSignal.timeout(5000),
    })
    if (!response.ok) throw new Error('Local phone setting failed')
    receive(await response.json() as LocalPhone)
  } catch {
    window.dispatchEvent(new CustomEvent('pandastic:local-error'))
  }
}

export async function sendLocalSms(number: string, body: string): Promise<SmsResult> {
  try {
    const response = await fetch('/__phone/send', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: crypto.randomUUID(), number: number.trim(), body }), signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) return { ok: false, error: 'invalid' }
    return await response.json() as SmsResult
  } catch { return { ok: false, error: 'unknown' } }
}
