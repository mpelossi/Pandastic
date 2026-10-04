import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Icon } from './Icons'
import ContactPicker, { type ContactsState } from './ContactPicker'
import { ModeCards, LanguageChoice } from './PhoneSetup'
import { ux } from './ux'
import { strings } from './i18n'
import * as native from './native'
import { load, save } from './storage'
import type { ChatStatus, HubStatus, HubContact, Lang, PhoneMode } from './native'

export default function Settings({ lang, setLang, mode, chooseMode, chat, hub }: {
  lang: Lang; setLang: (lang: Lang) => void; mode: PhoneMode; chooseMode: (mode: PhoneMode) => void; chat: ChatStatus; hub: HubStatus
}) {
  const t = ux[lang]
  const [peer, setPeer] = useState(chat.peer)
  const [asked, setAsked] = useState(false)
  const [detectedNumber, setDetectedNumber] = useState(() => native.localPhoneNumber || native.phoneInfo().number || '')
  const [ownNumber, setOwnNumber] = useState(() => native.localPhoneNumber || native.phoneInfo().number || load('pandastic.own-number'))
  const [deviceContacts, setDeviceContacts] = useState<HubContact[]>([])
  const [contactsState, setContactsState] = useState<ContactsState>(native.canSuggestContacts ? 'idle' : native.isLocalPhone ? 'ready' : 'unavailable')
  const [ownNumberSaved, setOwnNumberSaved] = useState(false)
  const [ownNumberError, setOwnNumberError] = useState('')
  const [smsView, setSmsView] = useState<'send' | 'reply'>('send')
  const [addingContact, setAddingContact] = useState(false)
  const addButton = useRef<HTMLButtonElement>(null)
  const activeView = mode === 'lite' ? 'send' : smsView
  const memory = native.phoneInfo().totalRamMb
  const contacts = native.canSuggestContacts ? deviceContacts : [...hub.contacts,
    ...(chat.peer && !hub.contacts.some(contact => native.sameNumber(contact.number, chat.peer)) ? [{ name: chat.peer, number: chat.peer }] : [])]
  const selectedPeer = peer ? contacts.find(contact => native.sameNumber(contact.number, peer)) || { name: peer, number: peer } : undefined
  useEffect(() => setPeer(chat.peer), [chat.peer])
  useEffect(() => {
    let active = true
    const detect = () => {
      const number = native.localPhoneNumber || native.phoneInfo().number || ''
      setDetectedNumber(number)
      if (number) setOwnNumber(number)
    }
    const read = async () => {
      if (!native.canSuggestContacts) return
      setContactsState('loading')
      const result = await native.phoneContacts()
      if (!active) return
      setDeviceContacts(result.contacts)
      setContactsState(result.error === 'permission' ? 'permission' : result.error ? 'unavailable' : 'ready')
      detect()
    }
    detect(); void read()
    window.addEventListener('pandastic:phone', detect)
    return () => { active = false; window.removeEventListener('pandastic:phone', detect) }
  }, [])

  async function refreshContacts() {
    setContactsState('loading')
    const result = await native.phoneContacts()
    setDeviceContacts(result.contacts)
    setContactsState(result.error === 'permission' ? 'permission' : result.error ? 'unavailable' : 'ready')
    if (result.number) { setDetectedNumber(result.number); setOwnNumber(result.number) }
  }
  function saveOwnNumber(event: FormEvent) {
    event.preventDefault()
    if (native.isLocalPhone) return
    if (ownNumber.trim() && !native.validNumber(ownNumber)) { setOwnNumberError(t.invalidNumber); setOwnNumberSaved(false); return }
    save('pandastic.own-number', ownNumber.trim())
    setOwnNumberError(''); setOwnNumberSaved(true)
  }
  function selectAllowedContact(contact?: HubContact) {
    if (!contact || hub.contacts.some(other => native.sameNumber(other.number, contact.number))) return
    native.setHubContacts([...hub.contacts, contact])
    setAddingContact(false)
    addButton.current?.focus()
  }
  return <section className="page settings-page">
    <h1>{t.settings}</h1>
    <div className="setting-row settings-language"><h2>{t.language}</h2><LanguageChoice lang={lang} setLang={setLang} /></div>
    {detectedNumber && <div className="own-number-summary"><span>{t.ownNumber}</span><strong>{detectedNumber}</strong><small>{t.numberDetected}</small></div>}
    {mode === 'capable' && <div className="settings-tabs" role="group" aria-label={t.smsSettings}>
      <button type="button" aria-pressed={activeView === 'send'} onClick={() => setSmsView('send')}><Icon name="send" size={18} />{t.sendSettings}</button>
      <button type="button" aria-pressed={activeView === 'reply'} onClick={() => setSmsView('reply')}><Icon name="message" size={18} />{t.replySettings}{hub.enabled && <span className="status-dot" />}</button>
    </div>}
    {activeView === 'send' && <section className="settings-section sms-settings-panel"><h2>{t.contactSettings}</h2><p className="field-hint">{t.contactDestinationHint}</p>
      <ContactPicker lang={lang} contacts={contacts} state={contactsState} selected={selectedPeer} excluded={[detectedNumber]}
        onSelect={contact => { const number = contact?.number || ''; setPeer(number); native.setSmsPeer(number) }} retry={() => void refreshContacts()} />
      {!native.isDemo && <div className="setting-row"><div><h3>{t.smsSetup}</h3><p className="field-hint">{chat.smsPermission ? t.granted : t.notGranted}</p></div>{!chat.smsPermission && <button className="secondary compact" onClick={native.enableSms}>{t.allowSms}</button>}</div>}
    </section>}
    {activeView === 'reply' && <section className="settings-section sms-settings-panel"><div className="setting-row helper-setting"><div><h2>{t.aiReplies}</h2><p className="field-hint">{t.aiRepliesHint}</p></div><button className="switch" role="switch" aria-label={t.aiReplies} aria-checked={hub.enabled} disabled={!hub.enabled && hub.contacts.length === 0} onClick={() => { setAsked(true); native.setHubEnabled(!hub.enabled) }}><span /></button></div>
        <p className="helper-status"><span className={`status-dot ${hub.enabled ? 'capable-dot' : 'inactive-dot'}`} />{hub.enabled ? hub.running ? t.helperOn : t.helperStarting : t.helperOff}{hub.enabled && ` · ${strings[lang].answeredToday(hub.answeredToday)}`}</p>
        {hub.contacts.length === 0 && <p className="field-hint">{t.noContacts}</p>}
        {asked && !hub.enabled && !hub.smsPermission && <p className="field-error" role="alert">{t.permission}</p>}
        <h3 className="subsection-title">{t.allowedPhones}</h3>
        {hub.contacts.length > 0 && <ul className="contacts">{hub.contacts.map(contact => <li key={contact.number}><button type="button" className="selected-contact" aria-label={`${t.remove} ${contact.name}`} onClick={() => { const contacts = hub.contacts.filter(other => other.number !== contact.number); native.setHubContacts(contacts); if (!contacts.length) native.setHubEnabled(false) }}><span className="avatar">{(contact.name || '?').slice(0, 1)}</span><span className="contact-name"><strong>{contact.name}</strong><small>{contact.number}</small></span><Icon name="close" size={19} /></button></li>)}</ul>}
        <button ref={addButton} type="button" className="text-button add-phone-button" aria-expanded={addingContact} aria-controls="add-phone-form" onClick={() => setAddingContact(!addingContact)}><Icon name={addingContact ? 'close' : 'plus'} size={18} />{addingContact ? t.cancel : t.add}</button>
        {addingContact && <div id="add-phone-form" className="add-contact">
          <ContactPicker lang={lang} contacts={contacts} state={contactsState} excluded={[detectedNumber, ...hub.contacts.map(contact => contact.number)]}
            onSelect={selectAllowedContact} retry={() => void refreshContacts()} />
        </div>}
        <div className="setting-row"><h3>{t.replyLanguage}</h3><LanguageChoice lang={hub.lang} setLang={native.setHubLang} /></div>
    </section>}
    <div className="settings-footer"><details className="settings-disclosure"><summary><span>{t.storage}</span><Icon name="arrow" size={17} /></summary><div className="disclosure-content"><p className="field-hint">{t.privacy}</p><button className="danger-link" disabled={!chat.messages.length && !hub.recent.length} onClick={() => { if (window.confirm(t.clearConfirm)) { native.clearChatHistory(); native.clearHubHistory() } }}><Icon name="trash" size={18} />{t.clear}</button></div></details>
    <details className="settings-disclosure phone-demo"><summary><span>{t.phoneSettings}<small>{t.demoSetup}</small></span><span className="phone-mode-label">{ownNumber || (mode === 'lite' ? t.basic : t.capable)}</span><Icon name="arrow" size={17} /></summary><div className="disclosure-content">
      {!detectedNumber && <form className="own-number-form" onSubmit={saveOwnNumber}><label htmlFor="own-number">{t.ownNumber}</label><p className="field-hint">{t.ownNumberHint}</p><input id="own-number" type="tel" inputMode="tel" autoComplete="tel" placeholder={t.numberPlaceholder} value={ownNumber} aria-invalid={Boolean(ownNumberError)} aria-describedby={ownNumberError ? 'own-number-error' : undefined} onChange={event => { setOwnNumber(event.target.value); setOwnNumberSaved(false); setOwnNumberError('') }} />{ownNumberError && <p id="own-number-error" className="field-error" role="alert">{ownNumberError}</p>}<div className="form-action"><button className="secondary compact" type="submit">{t.save}</button>{ownNumberSaved && <span className="saved-note" role="status"><Icon name="check" size={16} />{t.saved}</span>}</div></form>}
      <h2 className="demo-mode-title">{t.demoSetup}</h2><ModeCards lang={lang} mode={mode} choose={chooseMode} /><p className="footnote">{t.modeHelp}{memory != null && ` ${t.memory}: ${(memory / 1024).toFixed(1)} GB.`}</p></div></details>
    </div>{native.isDemo && <p className="footnote preview-note">{native.isLocalPhone ? t.localSmsNote : t.browser}</p>}
  </section>
}
