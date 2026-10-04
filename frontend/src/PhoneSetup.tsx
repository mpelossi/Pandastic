import { useState } from 'react'
import { Icon, Panda } from './Icons'
import { ux } from './ux'
import * as native from './native'
import type { Lang, PhoneMode } from './native'

export function LanguageChoice({ lang, setLang }: { lang: Lang; setLang: (lang: Lang) => void }) {
  return <div className="segmented" role="group" aria-label="Language / Lugha">
    <button aria-pressed={lang === 'sw'} onClick={() => setLang('sw')}>Kiswahili</button>
    <button aria-pressed={lang === 'en'} onClick={() => setLang('en')}>English</button>
  </div>
}
export function ModeCards({ lang, mode, choose }: { lang: Lang; mode?: PhoneMode; choose: (mode: PhoneMode) => void }) {
  const t = ux[lang]
  return <div className="mode-options" role="group" aria-label={t.phoneMode}>
    {(['lite', 'capable'] as PhoneMode[]).map(value => <button key={value} className="mode-option" aria-pressed={mode === value} onClick={() => choose(value)}>
      <span className="mode-icon"><Icon name={value === 'lite' ? 'basicPhone' : 'leafScan'} size={25} /></span>
      <span className="mode-description"><strong>{value === 'lite' ? t.basic : t.capable}</strong><span>{value === 'lite' ? t.basicHint : t.capableHint}</span></span>
      <span className="radio-indicator">{mode === value && <Icon name="check" size={14} />}</span>
    </button>)}
  </div>
}
export function ModeSetup({ lang, choose, setLang }: { lang: Lang; choose: (mode: PhoneMode) => void; setLang: (lang: Lang) => void }) {
  const t = ux[lang]
  const [selected, setSelected] = useState<PhoneMode>()
  const memory = native.phoneInfo().totalRamMb
  return <main className="setup-page">
    <div className="setup-art"><Panda size={112} /></div>
    <h1>{t.phoneMode}</h1><p className="page-intro">{t.chooseIntro}</p>
    <ModeCards lang={lang} mode={selected} choose={setSelected} />
    <p className="selection-detail">{selected === 'lite' ? t.basicDetail : selected === 'capable' ? t.capableDetail : t.modeHint}</p>
    {memory != null && <p className="memory-note">{t.memory}: {(memory / 1024).toFixed(1)} GB</p>}
    <div className="setup-language"><LanguageChoice lang={lang} setLang={setLang} /></div>
    <button className="primary continue-button" disabled={!selected} onClick={() => selected && choose(selected)}>{t.continue}<Icon name="arrow" size={20} /></button>
  </main>
}

