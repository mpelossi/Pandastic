import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Icon, Panda } from './Icons'
import { strings } from './i18n'
import { ux } from './ux'
import { decisionText } from './answers'
import { load, save } from './storage'
import * as native from './native'
import type { ChatStatus, HubStatus, Lang } from './native'
import { useDictation } from './useDictation'
import { useReadAloud } from './useReadAloud'

export type LocalEntry = { id: string; body: string; direction: 'in' | 'out'; image?: string; source?: string; demo?: boolean }
type Attachment = { file: File; url: string }

export default function Chat({ lang, capable, visible, chat, hub, entries, setEntries, openSettings }: {
  lang: Lang; capable: boolean; visible: boolean; chat: ChatStatus; hub: HubStatus
  entries: LocalEntry[]; setEntries: (entries: LocalEntry[]) => void; openSettings: () => void
}) {
  const t = ux[lang]
  const [target, setTarget] = useState<'sms' | 'local'>(capable && !native.isLocalPhone ? 'local' : 'sms')
  const local = capable && target === 'local'
  const [draft, setDraft] = useState(() => load('pandastic.sms-draft'))
  const [localDraft, setLocalDraft] = useState('')
  const [attachment, setAttachment] = useState<Attachment>()
  const [attachOpen, setAttachOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const camera = useRef<HTMLInputElement>(null)
  const gallery = useRef<HTMLInputElement>(null)
  const attachControl = useRef<HTMLDivElement>(null)
  const textarea = useRef<HTMLTextAreaElement>(null)
  const end = useRef<HTMLDivElement>(null)
  const request = useRef(0)
  const currentRole = useRef(capable)
  currentRole.current = capable
  const mounted = useRef(true)
  const current = local ? localDraft : draft
  const dictation = useDictation({ lang, active: visible && !busy, context: local ? 'local' : `sms:${chat.peer}`, value: current, setValue: local ? setLocalDraft : setDraft })
  const readAloud = useReadAloud(lang, visible, local ? 'local' : `sms:${chat.peer}`)
  const dictationError = dictation.error === 'unsupported' ? t.dictationUnsupported : dictation.error === 'permission' ? t.dictationPermission : dictation.error === 'network' ? t.dictationNetwork : dictation.error === 'language' ? t.dictationLanguage : dictation.error === 'no-speech' ? t.dictationNoSpeech : dictation.error ? t.dictationFailed : ''
  const messages = chat.messages.filter(message => native.sameNumber(message.number, chat.peer))
  const empty = local ? entries.length === 0 : messages.length === 0
  const lastId = local ? entries.at(-1)?.id : messages.at(-1)?.id
  const contact = hub.contacts.find(contact => native.sameNumber(contact.number, chat.peer))
  const peers = [...new Set(chat.messages.map(message => message.number))].filter((number, index, all) => all.findIndex(other => native.sameNumber(other, number)) === index)
  if (chat.peer && !peers.some(number => native.sameNumber(number, chat.peer))) peers.unshift(chat.peer)

  useEffect(() => { save('pandastic.sms-draft', draft) }, [draft])
  useEffect(() => () => { if (attachment) URL.revokeObjectURL(attachment.url) }, [attachment])
  useEffect(() => {
    const field = textarea.current
    if (field) { field.style.height = 'auto'; field.style.height = `${Math.min(field.scrollHeight, 132)}px` }
  }, [current, target])
  useEffect(() => {
    if (visible && lastId != null) end.current?.scrollIntoView({ block: 'nearest' })
  }, [lastId, target, busy, visible])
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; request.current++ }
  }, [])
  useEffect(() => {
    request.current++
    setBusy(false); setError(''); setAttachment(undefined); setAttachOpen(false)
    setTarget(capable && !native.isLocalPhone ? 'local' : 'sms')
    if (!capable) setLocalDraft('')
  }, [capable])
  useEffect(() => {
    if (!attachOpen) return
    const close = (event: PointerEvent) => {
      if (!attachControl.current?.contains(event.target as Node)) setAttachOpen(false)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setAttachOpen(false)
    }
    window.addEventListener('pointerdown', close)
    window.addEventListener('keydown', escape)
    return () => { window.removeEventListener('pointerdown', close); window.removeEventListener('keydown', escape) }
  }, [attachOpen])

  function choose(file?: File) {
    if (!file || !capable || busy || dictation.listening) return
    if (!file.type.startsWith('image/')) { setError(t.photoInvalid); return }
    if (file.size > 20 * 1024 * 1024) { setError(t.photoTooLarge); return }
    setTarget('local'); setError('')
    setAttachment({ file, url: URL.createObjectURL(file) })
    textarea.current?.focus()
  }
  function pick(kind: 'camera' | 'gallery') {
    setAttachOpen(false)
    const input = kind === 'camera' ? camera : gallery
    input.current?.click()
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || dictation.listening || (!current.trim() && !(local && attachment))) return
    if (!local && (!native.validNumber(chat.peer) || !native.canSms)) return
    const id = ++request.current
    const active = () => mounted.current && request.current === id && (!local || currentRole.current)
    const body = current.trim()
    const picture = local ? attachment : undefined
    setBusy(true); setError(''); setAttachOpen(false)
    try {
      if (local) {
        const image = picture ? await native.photoPreview(picture.file) : undefined
        if (!active()) return
        const history = [...entries, { id: crypto.randomUUID(), body, image, direction: 'out' as const }].slice(-80)
        setEntries(history); setLocalDraft(''); setAttachment(undefined)
        const decision = picture ? await native.checkPhoto(picture.file, lang, body) : await native.ask(body, lang)
        if (active()) setEntries([...history, { id: crypto.randomUUID(), body: decisionText(decision, lang, Boolean(picture)), direction: 'in' as const, source: decision.source?.title, demo: decision.stub }].slice(-80))
      } else {
        const result = await native.sendSms(chat.peer, body)
        if (!active()) return
        if (result.ok) setDraft('')
        else setError(result.error === 'permission' ? t.denied : result.error === 'unknown' ? t.unknown : native.isLocalPhone ? t.localSendFailed : t.sendFailed)
      }
    } catch {
      if (active()) setError(local ? t.noAnswer : t.sendFailed)
    } finally { if (active()) setBusy(false) }
  }

  return <>
    <h1 className="visually-hidden">{t.chat}</h1>
    {native.isLocalPhone && <p className="local-phone-note">{t.localPhone} <strong>{native.localPhoneNumber}</strong> → {chat.peer || '…'} · {t.simulatedSms}</p>}
    <div className={`conversation ${empty ? 'conversation-empty' : ''}`}>
      {empty ? <div className="empty-panda"><Panda size={132} /></div> : <ol className="message-list" aria-label={t.messages}>
        {local ? entries.map(entry => <li key={entry.id} className={`message-row message-${entry.direction}`}>
          <div className={`message-bubble ${entry.image ? 'message-with-photo' : ''}`}>
            {entry.image && <img className="message-photo" src={entry.image} alt={t.attachmentAlt} />}
            {entry.body && <p>{entry.body}</p>}
            {entry.source && <small className="message-source">{strings[lang].source}: {entry.source}</small>}
            {entry.demo && <small className="message-source">{t.demo}</small>}
            {entry.direction === 'in' && entry.body && readAloud.available && <button type="button" className="read-aloud-button" aria-label={readAloud.speaking === entry.id ? t.stopReading : t.readAloud} aria-pressed={readAloud.speaking === entry.id} onClick={() => readAloud.toggle(entry.id, entry.body)}><Icon name={readAloud.speaking === entry.id ? 'stop' : 'speaker'} size={18} /><span>{readAloud.speaking === entry.id ? t.stopReading : t.readAloud}</span></button>}
          </div>
        </li>) : messages.map(message => <li key={message.id} className={`message-row message-${message.direction}`}>
          <div className="message-bubble"><p>{message.body}</p><small className={message.status === 'failed' ? 'message-failed' : ''}>
            {new Date(message.time).toLocaleTimeString(lang === 'sw' ? 'sw' : 'en', { hour: '2-digit', minute: '2-digit' })}
            {message.direction === 'out' && ` · ${message.status === 'sending' ? t.sending : message.status === 'sent' ? t.sent : message.status === 'submitted' ? t.submitted : message.status === 'unknown' ? t.unknown : t.failed}`}
          </small>{message.direction === 'in' && readAloud.available && <button type="button" className="read-aloud-button" aria-label={readAloud.speaking === `sms-${message.id}` ? t.stopReading : t.readAloud} aria-pressed={readAloud.speaking === `sms-${message.id}`} onClick={() => readAloud.toggle(`sms-${message.id}`, message.body)}><Icon name={readAloud.speaking === `sms-${message.id}` ? 'stop' : 'speaker'} size={18} /><span>{readAloud.speaking === `sms-${message.id}` ? t.stopReading : t.readAloud}</span></button>}</div>
        </li>)}
      </ol>}
      {busy && local && <p className="thinking" role="status">{t.thinking}</p>}
      <div ref={end} />
    </div>
    <div className="composer-area">
      {error && <p className="composer-error" role="alert">{error}</p>}
      {dictationError && <p className="composer-error" role="alert">{dictationError}</p>}
      {readAloud.failed && <p className="composer-error" role="alert">{t.speechFailed}</p>}
      {dictation.listening && <p className="dictation-status" role="status">{dictation.processing ? t.transcribingOffline : t.listening}{dictation.interim && ` · ${dictation.interim}`}</p>}
      <form className="composer" onSubmit={event => void submit(event)}>
        {local && attachment && <div className="attachment-preview">
          <img src={attachment.url} alt={t.attachmentAlt} />
          <span><strong>{attachment.file.name}</strong><small>{t.photoCaption}</small></span>
          <button className="icon-button" type="button" aria-label={t.removePhoto} disabled={busy} onClick={() => setAttachment(undefined)}><Icon name="close" size={18} /></button>
        </div>}
        <textarea ref={textarea} rows={1} maxLength={480} aria-label={local ? t.askComposer : t.composer} placeholder={local ? t.askComposer : t.composer} value={current} disabled={busy || dictation.listening} onChange={event => local ? setLocalDraft(event.target.value) : setDraft(event.target.value)} onKeyDown={event => {
          if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault()
            if (!event.repeat && !busy) event.currentTarget.form?.requestSubmit()
          }
        }} />
        <div className="composer-tools">
          {capable && <div className="attach-control" ref={attachControl}>
            <button className="attach-button" type="button" aria-label={t.attach} aria-expanded={attachOpen} aria-haspopup="dialog" disabled={busy || dictation.listening} onClick={() => setAttachOpen(!attachOpen)}><Icon name={attachOpen ? 'close' : 'plus'} size={22} /></button>
            {attachOpen && <div className="attach-menu" role="dialog" aria-label={t.attach}>
              <button type="button" onClick={() => pick('camera')}><Icon name="camera" size={21} />{t.camera}</button>
              <button type="button" onClick={() => pick('gallery')}><Icon name="image" size={21} />{t.gallery}</button>
            </div>}
          </div>}
          <div className="chat-destination">
            {capable && <select aria-label={t.to} className="target-picker" value={local ? 'local' : 'sms'} disabled={busy || dictation.listening} onChange={event => { setTarget(event.target.value as 'sms' | 'local'); setError('') }}>
              <option value="local">{t.local}</option><option value="sms" disabled={Boolean(attachment)}>{t.sms}</option>
            </select>}
            {!local && (peers.length > 1 ? <select className="peer-picker" aria-label={t.availablePhones} value={peers.find(number => native.sameNumber(number, chat.peer)) ?? ''} disabled={busy || dictation.listening} onChange={event => { native.setSmsPeer(event.target.value); setError('') }}>
              {!chat.peer && <option value="">{t.choosePhone}</option>}
              {peers.map(number => <option key={number} value={number}>{hub.contacts.find(contact => native.sameNumber(contact.number, number))?.name || number}</option>)}
            </select> : <button className="destination-button" type="button" disabled={busy} onClick={openSettings}><Icon name="phone" size={14} /><span>{contact?.name || chat.peer || t.choosePhone}</span></button>)}
          </div>
          <button className={`icon-button dictate-button ${dictation.listening ? 'dictate-active' : ''}`} type="button" aria-label={dictation.processing ? t.transcribingOffline : dictation.listening ? t.stopDictation : t.dictate} aria-pressed={dictation.listening} aria-describedby="dictation-note" title={dictation.supported ? t.dictate : t.dictationUnsupported} disabled={busy || dictation.processing} onClick={dictation.toggle}><Icon name={dictation.listening ? 'stop' : 'microphone'} size={21} /></button>
          <button className="send-button" type="submit" aria-label={local ? t.ask : t.send} title={t.keyboardHint} aria-keyshortcuts="Control+Enter Meta+Enter" disabled={busy || dictation.listening || (!current.trim() && !(local && attachment)) || (!local && (!native.validNumber(chat.peer) || !native.canSms))}><Icon name="arrow" size={21} /></button>
        </div>
      </form>
      <p className="composer-note" id="dictation-note">{native.isDemo ? t.dictationNote : t.nativeDictationNote}</p>
      {!local && <p className="composer-note">{native.isLocalPhone ? t.localSmsNote : native.isDemo ? t.browser : t.smsNote}</p>}
    </div>
    {capable && <>
      <input className="visually-hidden" ref={camera} type="file" accept="image/*" capture="environment" tabIndex={-1} aria-hidden="true" onChange={event => { choose(event.target.files?.[0]); event.target.value = '' }} />
      <input className="visually-hidden" ref={gallery} type="file" accept="image/*" tabIndex={-1} aria-hidden="true" onChange={event => { choose(event.target.files?.[0]); event.target.value = '' }} />
    </>}
  </>
}
