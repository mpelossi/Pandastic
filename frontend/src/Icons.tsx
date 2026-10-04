import type { CSSProperties } from 'react'

// Pictograms are drawn thick and simple so they read at arm's length and without words.
const paths = {
  microphone: <><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/></>,
  stop: <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>,
  models: <><rect x="5" y="5" width="14" height="14" rx="3"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/></>,
  message: <><path d="M20 11.5a8 8 0 0 1-8 8H4l-2 2v-10a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8Z"/><path d="M7 9h8M7 13h5"/></>,
  settings: <><path d="M4 7h16M4 17h16"/><circle cx="8" cy="7" r="3" fill="var(--paper, white)"/><circle cx="16" cy="17" r="3" fill="var(--paper, white)"/></>,
  send: <><path d="m4 12 16-8-5 16-4-6-7-2Z"/><path d="m11 14 9-10"/></>,
  phone: <><rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4M10 5h4"/></>,
  arrow: <><path d="M5 12h14m-5-5 5 5-5 5"/></>,
  leafScan: <><path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3"/><path d="M17 7s-7-.6-8.2 4.1c-1.1 4.4 3.8 6 5.9 3.3S17 7 17 7ZM7.5 16.5l5.5-5.5"/></>,
  scale: <><path d="M12 3.5v17M7.5 20.5h9M4.5 7h15"/><circle cx="12" cy="4" r="1.2"/><path d="m4.5 7-3 6.5a3 3 0 0 0 6 0L4.5 7ZM19.5 7l-3 6.5a3 3 0 0 0 6 0l-3-6.5Z"/></>,
  basicPhone: <><rect x="3.5" y="5" width="10" height="17" rx="2.2"/><rect x="5.8" y="7.5" width="5.4" height="4.2" rx=".6"/><path d="M6.6 15h.01M8.5 15h.01M10.4 15h.01M6.6 18h.01M8.5 18h.01M10.4 18h.01"/><path d="M15.5 2.5h5a1.2 1.2 0 0 1 1.2 1.2v3.6a1.2 1.2 0 0 1-1.2 1.2h-2.3l-2.2 2v-2h-.5a1.2 1.2 0 0 1-1.2-1.2V3.7a1.2 1.2 0 0 1 1.2-1.2Z"/></>,
  camera: <><path d="M8 5 10 2.5h4L16 5h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4Z"/><circle cx="12" cy="12.5" r="4"/></>,
  image: <><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L6 21"/></>,
  back: <path d="M15 4.5 7.5 12l7.5 7.5"/>,
  speaker: <><path d="M11 5 6 9H3v6h3l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/></>,
  person: <><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7"/></>,
  share: <><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/></>,
  check: <path d="M5 12.5 9.5 17 19 7"/>,
  alert: <><path d="M12 3 2 20h20L12 3Z"/><path d="M12 10v4M12 17h.01"/></>,
  question: <><circle cx="12" cy="12" r="9.5"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.7M12 17h.01"/></>,
  offline: <><path d="M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19h.01"/><path d="m3 3 18 18"/></>,
  cherry: <><circle cx="8.5" cy="15.5" r="5"/><circle cx="16.5" cy="14" r="4"/><path d="M10 10.5c.5-3.5 3-6.5 7-7.5M16 10c0-3 .5-5 1-7"/></>,
  maize: <><path d="M12 3c2.8 0 4 4 4 9s-1.4 9-4 9-4-4-4-9 1.2-9 4-9Z"/><path d="M8.3 8h7.4M8 12h8M8.3 16h7.4M12 3v18"/><path d="M8.5 20c-2.5-1-4.5-3.5-5-7M15.5 20c2.5-1 4.5-3.5 5-7"/></>,
  bean: <path d="M7.5 4.5c3-1.8 6.5.2 6.8 3.2.2 2 2.7 2.4 3.9 5.3 1.4 3.6-1.3 7.1-5.5 6.4-4.7-.8-7.8-3.4-8.7-7.4-.6-3 .6-6 3.5-7.5Z"/>,
  again: <><path d="M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5"/><path d="M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5"/></>,
  plus: <path d="M12 5v14M5 12h14"/>,
  close: <path d="m6 6 12 12M6 18 18 6"/>,
  trash: <><path d="M4 7h16M10 11v6M14 11v6M5.5 7l1 13h11l1-13M9 7V4h6v3"/></>,
}

export type IconName = keyof typeof paths

export function Icon({ name, size = 24, stroke = 2.2, className = '' }: { name: IconName; size?: number; stroke?: number; className?: string }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">{paths[name]}</svg>
}

export function Panda({ size = 48, className = '', style }: { size?: number; className?: string; style?: CSSProperties }) {
  return <svg width={size} height={size} viewBox="0 0 80 80" className={className} style={style} aria-hidden="true">
    <circle cx="21" cy="21" r="12" fill="#2B1E16"/><circle cx="59" cy="21" r="12" fill="#2B1E16"/>
    <ellipse cx="40" cy="43" rx="29" ry="28" fill="#ffffff"/>
    <ellipse cx="28" cy="39" rx="9" ry="11" transform="rotate(25 28 39)" fill="#2B1E16"/>
    <ellipse cx="52" cy="39" rx="9" ry="11" transform="rotate(-25 52 39)" fill="#2B1E16"/>
    <circle cx="30" cy="37" r="2.7" fill="white"/><circle cx="50" cy="37" r="2.7" fill="white"/>
    <ellipse cx="40" cy="50" rx="5" ry="3.5" fill="#2B1E16"/>
    <path d="M40 53v4m-7-1q7 9 14 0" stroke="#2B1E16" strokeWidth="2" fill="none" strokeLinecap="round"/>
    <path d="M58 61c6-1 11-6 11-12-6 0-11 5-11 12Z" fill="#1D5C3A"/>
  </svg>
}
