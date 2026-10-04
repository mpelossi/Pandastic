import { useEffect, useRef, useState } from 'react'
import type { Lang } from './native'

type Result = { isFinal: boolean; 0: { transcript: string } }
type Recognition = {
  lang: string; continuous: boolean; interimResults: boolean; maxAlternatives: number
  onresult: ((event: { results: ArrayLike<Result> }) => void) | null
  onerror: ((event: { error: string }) => void) | null
  onend: (() => void) | null
  onprocessing?: (() => void) | null
  start(): void; stop(): void; abort(): void
}
type SpeechWindow = Window & {
  SpeechRecognition?: new () => Recognition
  webkitSpeechRecognition?: new () => Recognition
}
export type DictationError = 'unsupported' | 'permission' | 'network' | 'language' | 'no-speech' | 'failed'

// System WebView has no browser recognition API. Use the Android bridge there.
class NativeRecognition implements Recognition {
  lang = 'en-US'; continuous = false; interimResults = true; maxAlternatives = 1
  onresult: Recognition['onresult'] = null
  onerror: Recognition['onerror'] = null
  onend: Recognition['onend'] = null
  onprocessing: Recognition['onprocessing'] = null
  private id = crypto.randomUUID()
  private receive = (event: Event) => {
    const detail = (event as CustomEvent<{ id: string; state: string; text?: string; error?: string }>).detail
    if (detail.id !== this.id) return
    if (detail.state === 'partial' || detail.state === 'result') {
      this.onresult?.({ results: [{ isFinal: detail.state === 'result', 0: { transcript: detail.text || '' } }] })
    } else if (detail.state === 'processing') {
      this.onprocessing?.()
    } else if (detail.state === 'error') {
      this.onerror?.({ error: detail.error === 'permission' ? 'not-allowed' : detail.error || 'failed' })
    } else if (detail.state === 'end') {
      window.removeEventListener('pandastic:dictation', this.receive)
      this.onend?.()
    }
  }
  start() {
    window.addEventListener('pandastic:dictation', this.receive)
    window.PandasticNative!.startDictation!(this.id, this.lang.startsWith('sw') ? 'sw' : 'en')
  }
  stop() { window.PandasticNative!.stopDictation!() }
  abort() {
    window.removeEventListener('pandastic:dictation', this.receive)
    window.PandasticNative!.cancelDictation!()
  }
}

export function useDictation({ lang, active, context, value, setValue }: {
  lang: Lang; active: boolean; context: string; value: string; setValue: (value: string) => void
}) {
  const browser = window as SpeechWindow
  const Constructor = window.PandasticNative?.startDictation ? NativeRecognition : browser.SpeechRecognition || browser.webkitSpeechRecognition
  const [listening, setListening] = useState(false)
  const [processing, setProcessing] = useState(false)
  const [error, setError] = useState<DictationError>()
  const [interim, setInterim] = useState('')
  const recognition = useRef<Recognition | undefined>(undefined)
  const generation = useRef(0)
  const update = useRef(setValue)
  update.current = setValue

  function cancel() {
    generation.current++
    recognition.current?.abort()
    recognition.current = undefined
    setListening(false); setProcessing(false); setInterim('')
  }
  useEffect(() => {
    if (!active) cancel()
  }, [active])
  useEffect(() => { cancel(); setError(undefined) }, [lang, context])
  useEffect(() => () => {
    generation.current++
    recognition.current?.abort()
  }, [])

  function toggle() {
    if (processing) return
    if (recognition.current) { recognition.current.stop(); return }
    if (!Constructor) { setError('unsupported'); return }
    if (!active) return
    setError(undefined)
    const session = ++generation.current
    const currentSession = () => generation.current === session
    let instance: Recognition
    try { instance = new Constructor() }
    catch { setError('failed'); return }
    recognition.current = instance
    setProcessing(false)
    instance.lang = lang === 'sw' ? 'sw-KE' : 'en-US'
    instance.continuous = false
    instance.interimResults = true
    instance.maxAlternatives = 1
    const prefix = value.trimEnd()
    instance.onresult = event => {
      if (!currentSession()) return
      const final: string[] = [], partial: string[] = []
      for (let index = 0; index < event.results.length; index++) {
        const result = event.results[index]
        const words = result.isFinal ? final : partial
        words.push(result[0].transcript)
      }
      if (final.length) update.current([prefix, ...final].filter(Boolean).join(' ').slice(0, 480))
      setInterim(partial.join(' '))
    }
    instance.onprocessing = () => { if (currentSession()) setProcessing(true) }
    instance.onerror = event => {
      if (!currentSession() || event.error === 'aborted') return
      setError(event.error === 'unsupported' ? 'unsupported' : event.error === 'not-allowed' || event.error === 'service-not-allowed' ? 'permission' : event.error === 'network' ? 'network' : event.error === 'language' || event.error === 'language-not-supported' ? 'language' : event.error === 'no-speech' ? 'no-speech' : 'failed')
      setListening(false); setProcessing(false); setInterim('')
    }
    instance.onend = () => {
      if (!currentSession()) return
      recognition.current = undefined
      setListening(false); setProcessing(false); setInterim('')
    }
    try { setListening(true); instance.start() }
    catch { instance.abort(); recognition.current = undefined; setListening(false); setError('failed') }
  }
  return { supported: Boolean(Constructor), listening, processing, interim, error, toggle }
}
