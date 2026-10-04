import { labelNames, money, monthYear, strings } from './i18n'
import { ux } from './ux'
import type { Decision, Lang } from './native'

/** Keep the existing confidence, retake, source and escalation rules in conversational replies. */
export function decisionText(decision: Decision, lang: Lang, photo = false): string {
  // The on-phone language model's own words come first (checked against the fixed answer below them); without
  // them, what it understood.
  const answer = answerText(decision, lang, photo)
  const ai = decision.ai_reply ? `🤖 ${decision.ai_reply}` : decision.understood
  return ai ? `${ai}\n\n${answer}` : answer
}

function answerText(decision: Decision, lang: Lang, photo: boolean): string {
  const t = strings[lang]
  if (decision.price) {
    const p = decision.price
    const stale = decision.status === 'PRICE_STALE' || p.stale
    return [decision.title, decision.message, `${t.marketPrice}: ${money(p.low)}${p.high !== p.low ? `–${money(p.high)}` : ''} ${p.currency}/${p.unit} (${monthYear(p.date, lang)}).`, p.offer != null ? `${t.yourOffer}: ${money(p.offer)} ${p.currency}/${p.unit}` : '', !stale && p.offer != null && p.gap_pct != null ? p.gap_pct < -5 ? t.priceLow(Math.round(-p.gap_pct)) : t.priceFair : '', stale ? t.priceOld(monthYear(p.date, lang)) : '', t.sayingPrice].filter(Boolean).join('\n\n')
  }
  if (!photo) return [decision.title, decision.message || decision.advice_long || decision.advice_sms || ux[lang].noAnswer].filter(Boolean).join('\n\n')
  if (decision.status === 'RETAKE') return `${t.retakeTitle}\n\n${t.retake[decision.quality ?? 'blur'] ?? t.retake.blur}\n\n${t.photoTips}`
  if (decision.status === 'CONFIDENT') {
    const healthy = decision.label?.endsWith('_healthy')
    const label = decision.label ? labelNames[lang][decision.label] ?? decision.label.replace(/_/g, ' ') : t.notSure
    return [healthy ? t.healthy : label, decision.advice_long || decision.advice_sms || decision.message, decision.prob != null ? `${t.sure}: ${Math.round(decision.prob * 100)}%` : '', healthy ? t.sayingHealthy : t.sayingProblem].filter(Boolean).join('\n\n')
  }
  return [decision.title || t.notSure, decision.message || `${decision.status === 'UNSUPPORTED' ? t.unsupported + ' ' : ''}${t.askPerson}`, t.dontSprayYet].filter(Boolean).join('\n\n')
}
