import { useEffect, useState } from 'react'
import * as native from './native'
import type { Lang } from './native'

export function useReadAloud(lang: Lang, visible: boolean, context: string) {
  const [voices, setVoices] = useState(native.speechStatus)
  const [speaking, setSpeaking] = useState<string>()
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    const event = (event: Event) => {
      const state = (event as CustomEvent<{ state: string }>).detail.state
      setVoices(native.speechStatus())
      if (state === 'done' || state === 'stopped' || state === 'error') setSpeaking(undefined)
      if (state === 'error') setFailed(true)
    }
    window.addEventListener('pandastic:speech', event)
    const timer = setInterval(() => setVoices(current => current.ready ? current : native.speechStatus()), 1000)
    return () => { window.removeEventListener('pandastic:speech', event); clearInterval(timer); native.stopSpeaking() }
  }, [])
  useEffect(() => {
    native.stopSpeaking(); setSpeaking(undefined); setFailed(false)
  }, [visible, lang, context])
  function toggle(id: string, text: string) {
    setFailed(false)
    if (speaking === id) { native.stopSpeaking(); setSpeaking(undefined); return }
    if (native.speak(text, lang)) setSpeaking(id)
    else { setSpeaking(undefined); setFailed(true) }
  }
  return { available: Boolean(voices.ready && voices[lang]), speaking, failed, toggle }
}
