import { lazy, Suspense, useEffect, useState } from 'react'
import { Icon, type IconName } from './Icons'
import { ModeSetup } from './PhoneSetup'
import Chat, { type LocalEntry } from './Chat'
import Settings from './Settings'
import { load, save } from './storage'
import { ux } from './ux'
import * as native from './native'
import type { ChatStatus, HubStatus, Lang, PhoneMode } from './native'

const Models = lazy(() => import('./Models'))
type Screen = 'chat' | 'models' | 'settings'

export default function App() {
  const [lang, setLang] = useState<Lang>(() => load('pandastic.lang', 'sw') === 'en' ? 'en' : 'sw')
  const [mode, setMode] = useState<PhoneMode | undefined>(() => {
    const saved = native.phoneInfo().mode
    return saved === 'lite' || saved === 'capable' ? saved : undefined
  })
  const [screen, setScreen] = useState<Screen>('chat')
  const [hub, setHub] = useState<HubStatus>(native.hubStatus)
  const [chat, setChat] = useState<ChatStatus>(native.chatStatus)
  const [entries, setEntries] = useState<LocalEntry[]>([])
  const [localError, setLocalError] = useState(false)
  const capable = mode === 'capable'
  const t = ux[lang]

  useEffect(() => native.onHubChange(setHub), [])
  useEffect(() => native.onChatChange(setChat), [])
  useEffect(() => {
    const failed = () => setLocalError(true)
    const saved = () => setLocalError(false)
    window.addEventListener('pandastic:local-error', failed)
    window.addEventListener('pandastic:hub', saved)
    return () => { window.removeEventListener('pandastic:local-error', failed); window.removeEventListener('pandastic:hub', saved) }
  }, [])
  useEffect(() => {
    document.documentElement.lang = lang
    save('pandastic.lang', lang)
  }, [lang])
  useEffect(() => {
    const onPop = (event: PopStateEvent) => {
      const next = event.state?.screen
      setScreen(next === 'settings' || (capable && next === 'models') ? next : 'chat')
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [capable])

  function open(next: Screen) {
    if (screen === next) return
    window.history.pushState({ screen: next }, '')
    setScreen(next)
  }
  function chooseMode(next: PhoneMode) {
    native.setPhoneMode(next)
    setMode(next)
    if (next === 'lite') {
      setEntries([])
      native.stopSpeaking()
      if (screen === 'models') open('chat')
    }
  }

  if (!mode) return <div className="setup-shell"><ModeSetup lang={lang} setLang={setLang} choose={chooseMode} /></div>

  return <div className="app-shell">
    <main className={`main-content ${screen === 'chat' ? 'message-main' : 'page-main'}`}>
      {localError && <p className="composer-error" role="alert">{t.localSettingFailed}</p>}
      <div className="chat-view" hidden={screen !== 'chat'}>
        <Chat lang={lang} capable={capable} visible={screen === 'chat'} chat={chat} hub={hub} entries={entries} setEntries={setEntries} openSettings={() => open('settings')} />
      </div>
      {screen === 'models' && capable && <Suspense fallback={<p className="page" role="status">{t.modelWorking}</p>}><Models lang={lang} /></Suspense>}
      {screen === 'settings' && <Settings lang={lang} setLang={setLang} mode={mode} chooseMode={chooseMode} chat={chat} hub={hub} />}
    </main>
    <nav className="bottom-nav" aria-label={t.chat}>
      <NavButton screen="chat" current={screen} icon="message" text={t.chat} open={open} />
      {capable && <NavButton screen="models" current={screen} icon="models" text={t.models} open={open} />}
      <NavButton screen="settings" current={screen} icon="settings" text={t.settings} open={open} />
    </nav>
  </div>
}

function NavButton({ screen, current, icon, text, open }: { screen: Screen; current: Screen; icon: IconName; text: string; open: (screen: Screen) => void }) {
  return <button onClick={() => open(screen)} aria-current={current === screen ? 'page' : undefined}>
    <Icon name={icon} size={20} /><span>{text}</span>
  </button>
}
