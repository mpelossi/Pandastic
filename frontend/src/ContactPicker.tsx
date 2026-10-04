import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Icon } from './Icons'
import { ux } from './ux'
import { sameNumber, type HubContact, type Lang } from './native'

export type ContactsState = 'idle' | 'loading' | 'ready' | 'permission' | 'unavailable'

export default function ContactPicker({ lang, contacts, state, selected, excluded = [], onSelect, retry }: {
  lang: Lang; contacts: HubContact[]; state: ContactsState; selected?: HubContact
  excluded?: string[]; onSelect: (contact?: HubContact) => void; retry: () => void
}) {
  const t = ux[lang]
  const id = useId()
  const picker = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const query = search.trim().toLocaleLowerCase()
  const digits = query.replace(/[^0-9]/g, '')
  const matches = contacts.filter(contact => !excluded.some(number => number && sameNumber(number, contact.number))
    && (!query || contact.name.toLocaleLowerCase().includes(query)
      || (digits.length > 0 && contact.number.replace(/[^0-9]/g, '').includes(digits))))
  // The dropdown scrolls; search still covers the complete address book.
  const options = matches.slice(0, 40)
  const loading = state === 'idle' || state === 'loading'
  useEffect(() => {
    if (open && active >= 0) document.getElementById(`${id}-option-${active}`)?.scrollIntoView({ block: 'nearest' })
  }, [open, active, id])
  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent) => {
      if (!picker.current?.contains(event.target as Node)) { setOpen(false); setActive(-1) }
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open])

  function choose(contact: HubContact) {
    onSelect(contact); setSearch(''); setOpen(false); setActive(-1)
    input.current?.blur()
  }
  function key(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); setOpen(true)
      setActive(current => event.key === 'ArrowDown' ? Math.min(current + 1, options.length - 1)
        : current < 0 ? options.length - 1 : Math.max(0, current - 1))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (open && options[active]) choose(options[active])
    } else if (event.key === 'Escape') { event.preventDefault(); setOpen(false); setActive(-1) }
  }

  return <div ref={picker} className="contact-picker" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) { setOpen(false); setActive(-1) }
  }}>
    <label htmlFor={`${id}-search`}>{t.chooseContact}</label>
    {selected && <button type="button" className="selected-contact" aria-label={`${t.remove} ${selected.name}`} onClick={() => {
      onSelect(undefined); setSearch(''); setActive(-1); input.current?.focus()
    }}>
      <span className="avatar">{selected.name.slice(0, 1)}</span>
      <span className="contact-name"><strong>{selected.name}</strong><small>{selected.number}</small></span>
      <Icon name="close" size={19} />
    </button>}
    {state === 'ready' ? <div className="contact-combobox">
      <input ref={input} id={`${id}-search`} type="search" role="combobox" aria-autocomplete="list"
        aria-expanded={open} aria-controls={`${id}-options`} aria-activedescendant={open && options[active] ? `${id}-option-${active}` : undefined}
        autoComplete="off" placeholder={t.findContact} value={search} onFocus={() => setOpen(true)}
        onClick={() => setOpen(true)} onKeyDown={key} onChange={event => { setSearch(event.target.value); setOpen(true); setActive(-1) }} />
      <span className={`contact-chevron${open ? ' open' : ''}`}><Icon name="arrow" size={18} /></span>
      {open && <div className="contact-dropdown">
        <ul id={`${id}-options`} role="listbox" aria-label={t.chooseContact}>
          {options.map((contact, index) => <li key={contact.number} role="presentation"><button type="button" role="option" tabIndex={-1}
            id={`${id}-option-${index}`} aria-selected={Boolean(selected && sameNumber(selected.number, contact.number))}
            className={active === index ? 'contact-option-active' : undefined}
            onPointerDown={event => event.preventDefault()} onMouseEnter={() => setActive(index)} onClick={() => choose(contact)}>
            <span className="avatar">{contact.name.slice(0, 1)}</span>
            <span className="contact-name"><strong>{contact.name}</strong><small>{contact.number}</small></span>
            {selected && sameNumber(selected.number, contact.number) && <Icon name="check" size={18} />}
          </button></li>)}
        </ul>
        {!options.length && <p className="field-hint" role="status">{t.noMatchingContacts}</p>}
      </div>}
    </div> : <>
      <p className="field-hint" role="status">{loading ? t.loadingContacts : state === 'permission' ? t.contactsPermission : t.contactsUnavailable}</p>
      {!loading && <button type="button" className="secondary compact" onClick={retry}>{t.chooseContact}</button>}
    </>}
  </div>
}
