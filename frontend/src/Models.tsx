import { useEffect, useState } from 'react'
import { Icon } from './Icons'
import { ux } from './ux'
import * as native from './native'
import type { Lang, ModelFile, ModelStatus } from './native'

export default function Models({ lang }: { lang: Lang }) {
  const t = ux[lang]
  const [status, setStatus] = useState<ModelStatus>(native.modelStatus)
  const [action, setAction] = useState<'load' | 'unload' | 'import'>()
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const busy = Boolean(action || status.busy)
  useEffect(() => native.onModelsChange(setStatus), [])
  const anyLoaded = status.classifier?.loaded || status.language?.loaded || status.knowledge?.loaded

  async function manage(next: 'load' | 'unload' | 'import') {
    if (busy || native.isDemo) return
    setAction(next); setError(''); setMessage('')
    try {
      const result = await native.manageModels(next)
      if (result.ok) setMessage(next === 'import' ? t.modelImported : next === 'unload' ? t.modelReleased : t.modelsLoaded)
      else if (result.error !== 'cancelled') setError(next === 'import' ? t.importFailed : t.modelFailed)
    } catch { setError(next === 'import' ? t.importFailed : t.modelFailed) }
    finally { setStatus(native.modelStatus()); setAction(undefined) }
  }
  function state(file?: ModelFile) {
    return native.isDemo && !native.hasLocalBrain ? t.preview : file?.loaded ? t.loaded : file?.installed ? t.installed : t.notInstalled
  }
  function size(file?: ModelFile) {
    if (!file?.bytes) return ''
    if (file.bytes < 1024) return `${file.bytes} B`
    return file.bytes >= 1024 * 1024 ? `${(file.bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.round(file.bytes / 1024)} KB`
  }

  return <section className="page models-page">
    <h1>{t.models}</h1><p className="page-intro">{t.modelIntro}</p>
    <div className="model-list">
      <section className="model-card">
        <div className="model-title"><Icon name="message" size={21} /><h2>{t.languageModel}</h2><span className={`model-state ${status.language?.loaded ? 'model-loaded' : ''}`}>{state(status.language)}</span></div>
        <p className="model-name">{status.language?.name || 'Qwen3.5-0.8B · Q4_K_M'}{size(status.language) && ` · ${size(status.language)}`}</p>
        <p className="field-hint">{t.languageHint}</p>
        {!native.isDemo && status.language && !status.language.runtimeAvailable && <p className="model-warning">{t.runtimeMissing}</p>}
        <button className="secondary compact" disabled={busy || native.isDemo} onClick={() => void manage('import')}><Icon name="plus" size={17} />{t.importModel}</button>
        <p className="model-import-hint">{t.modelImportHint}</p>
      </section>
      <section className="model-card">
        <div className="model-title"><Icon name="image" size={21} /><h2>{t.imageModel}</h2><span className={`model-state ${status.classifier?.loaded ? 'model-loaded' : ''}`}>{state(status.classifier)}</span></div>
        <p className="model-name">{status.classifier?.stub ? t.previewModel : status.classifier?.version.includes('efficientnet') ? 'EfficientNet-B0' : 'MobileNetV4'}{size(status.classifier) && ` · ${size(status.classifier)}`}</p>
        <p className="field-hint">{t.imageHint}</p>
        {status.classifier?.error && <p className="field-error">{t.modelFailed}</p>}
      </section>
      <section className="model-card">
        <div className="model-title"><Icon name="models" size={21} /><h2>{t.knowledge}</h2><span className={`model-state ${status.knowledge?.loaded ? 'model-loaded' : ''}`}>{state(status.knowledge)}</span></div>
        <p className="model-name">{t.bundled}{size(status.knowledge) && ` · ${size(status.knowledge)}`}</p><p className="field-hint">{t.knowledgeHint}</p>
        {status.knowledge?.error && <p className="field-error">{t.modelFailed}</p>}
      </section>
    </div>
    <div className="model-actions">
      <button className="primary compact" disabled={busy || native.isDemo || Boolean(status.error)} onClick={() => void manage('load')}>{t.loadModels}</button>
      <button className="secondary compact" disabled={busy || native.isDemo || !anyLoaded} onClick={() => void manage('unload')}>{t.releaseModels}</button>
      <button className="icon-button" aria-label={t.refresh} disabled={busy} onClick={() => setStatus(native.modelStatus())}><Icon name="again" size={19} /></button>
    </div>
    <p className="footnote">{t.modelReleaseHint}</p>
    {busy && <p className="model-feedback" role="status">{t.modelWorking}</p>}
    {message && <p className="model-feedback" role="status">{message}</p>}
    {(error || status.error) && <p className="field-error model-feedback" role="alert">{error || t.modelStatusFailed}</p>}
    {native.isDemo && !native.hasLocalBrain && <p className="footnote">{t.preview}</p>}
  </section>
}
