#!/usr/bin/env node
// Real Android Contacts -> native bridge -> Settings dropdown. Never sends an SMS.
import assert from 'node:assert/strict'
import { connectApp, sleep } from './lib/devtools.mjs'
const devices = [[process.env.HUB || 'emulator-5554', '+256772000001', '+256772000002', 9391], [process.env.BASIC || 'emulator-5556', '+256772000002', '+256772000001', 9392]]
const inputSelector = '.sms-settings-panel input[role=combobox]'
for (const [serial, own, peer, port] of devices) {
  const app = await connectApp(serial, port)
  const original = JSON.parse(await app.evaluate('PandasticNative.hubStatus()'))
  const originalChat = JSON.parse(await app.evaluate('PandasticNative.chatStatus()'))
  const waitFor = async expression => {
    for (let i = 0; i < 50; i++) {
      if (await app.evaluate(expression)) return
      await sleep(100)
    }
    assert.fail(`Timed out: ${expression}`)
  }
  const search = async value => {
    await app.evaluate(`(() => {
      const input = document.querySelector(${JSON.stringify(inputSelector)});
      input.focus();
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(value)});
      input.dispatchEvent(new Event('input', {bubbles: true}));
    })()`)
    await sleep(100)
  }
  const optionSelector = '.contact-dropdown [role=option]'
  const selectPeer = async () => {
    await search(peer)
    assert.equal(await app.evaluate(`document.querySelectorAll(${JSON.stringify(optionSelector)}).length`), 1)
    await app.evaluate(`document.querySelector(${JSON.stringify(optionSelector)}).click()`)
    await sleep(100)
  }
  try {
    const info = await app.evaluate('JSON.parse(PandasticNative.phoneInfo())')
    assert.equal(info.number, own)
    assert.equal(info.numberSource, 'lab')
    const contacts = await app.evaluate("__e2e.result('__pandasticContactsReply', 'phoneContacts', [], 10000)")
    const contact = contacts.contacts.find(contact => contact.number === peer)
    assert.ok(contact, 'The opposite phone must exist in Android Contacts')
    await app.evaluate('document.querySelector(".bottom-nav button:last-child").click()')
    await waitFor(`Boolean(document.querySelector(${JSON.stringify(inputSelector)}))`)
    // Android may keep window focus on its floating IME after install/relaunch. A real tap restores
    // WebView focus; DOM .focus() alone then moves the active element without emitting blur events.
    const heading = await app.evaluate('(()=>{const r=document.querySelector(".settings-page h1").getBoundingClientRect(); return {x:Math.round((r.x+r.width/2)*devicePixelRatio),y:Math.round((r.y+r.height/2+24)*devicePixelRatio)}})()')
    app.adb('shell', 'input', 'tap', String(heading.x), String(heading.y)) // lab phones: 24 dp status bar
    await waitFor('document.hasFocus()')
    assert.equal(await app.evaluate('document.querySelector(".own-number-summary strong").textContent'), own)
    assert.equal(await app.evaluate('Boolean(document.querySelector("#own-number"))'), false)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".sms-settings-panel input[type=tel], #sms-peer, .number-form"))'), false)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".contact-dropdown"))'), false, 'Dropdown starts closed')
    await search(contact.name.toLowerCase())
    assert.equal(await app.evaluate(`document.querySelectorAll(${JSON.stringify(optionSelector)}).length`), 1)
    await selectPeer()
    assert.equal(await app.evaluate('JSON.parse(PandasticNative.chatStatus()).peer'), peer)
    assert.equal(await app.evaluate('document.querySelector(".sms-settings-panel .selected-contact strong").textContent'), contact.name)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".contact-dropdown"))'), false, 'Selection closes the dropdown')

    // Removing the selected card clears the destination, never the address book or conversations.
    await app.evaluate('document.querySelector(".sms-settings-panel .selected-contact").click()')
    await waitFor('JSON.parse(PandasticNative.chatStatus()).peer === ""')
    assert.equal(await app.evaluate('Boolean(document.querySelector(".sms-settings-panel .selected-contact"))'), false)
    const remaining = await app.evaluate("__e2e.result('__pandasticContactsReply', 'phoneContacts', [], 10000)")
    assert.deepEqual(remaining.contacts, contacts.contacts)
    assert.deepEqual((await app.evaluate('JSON.parse(PandasticNative.chatStatus())')).messages, originalChat.messages)
    await search('no-such-contact-xyz')
    assert.equal(await app.evaluate(`document.querySelectorAll(${JSON.stringify(optionSelector)}).length`), 0)
    assert.ok(await app.evaluate('Boolean(document.querySelector(".contact-dropdown [role=status]"))'))

    // Formatted phone search and keyboard selection work without manual number entry.
    await search(`${peer.slice(0, 4)} ${peer.slice(4, 8)}`)
    assert.equal(await app.evaluate(`document.querySelectorAll(${JSON.stringify(optionSelector)}).length`), 1)
    await app.evaluate(`document.querySelector(${JSON.stringify(inputSelector)}).dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown', bubbles:true}))`)
    await sleep(100)
    assert.ok(await app.evaluate(`Boolean(document.querySelector(${JSON.stringify(inputSelector)}).getAttribute('aria-activedescendant'))`))
    await app.evaluate(`document.querySelector(${JSON.stringify(inputSelector)}).dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}))`)
    await waitFor(`JSON.parse(PandasticNative.chatStatus()).peer === ${JSON.stringify(peer)}`)
    await search('')
    await app.evaluate(`document.querySelector(${JSON.stringify(inputSelector)}).dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}))`)
    await sleep(100)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".contact-dropdown"))'), false)
    await app.evaluate(`document.querySelector(${JSON.stringify(inputSelector)}).focus(); document.querySelector(${JSON.stringify(inputSelector)}).click()`)
    await sleep(100)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".contact-dropdown"))'), true)
    await app.evaluate('document.querySelector(".settings-language button").focus()')
    await sleep(100)
    const blurred = await app.evaluate('({open:Boolean(document.querySelector(".contact-dropdown")), active:document.activeElement.outerHTML, focused:document.hasFocus()})')
    assert.equal(blurred.open, false, `Leaving the field closes the dropdown: ${JSON.stringify(blurred)}`)
    await search('')
    await app.evaluate('document.querySelector(".settings-page h1").dispatchEvent(new PointerEvent("pointerdown", {bubbles:true}))')
    await sleep(100)
    assert.equal(await app.evaluate('Boolean(document.querySelector(".contact-dropdown"))'), false, 'Tapping outside closes the dropdown')
    if (info.mode === 'capable') {
      await app.evaluate(`PandasticNative.setHubContacts(${JSON.stringify(JSON.stringify(original.contacts.filter(contact => contact.number !== peer)))})`)
      await sleep(150)
      await app.evaluate('document.querySelector(".settings-tabs button:nth-child(2)").click()')
      await sleep(100)
      await app.evaluate('document.querySelector(".add-phone-button").click()')
      await waitFor(`Boolean(document.querySelector(${JSON.stringify(inputSelector)}))`)
      assert.equal(await app.evaluate('Boolean(document.querySelector("#contact-number, #contact-name"))'), false)
      assert.equal(await app.evaluate('Boolean(document.querySelector("#add-phone-form button[type=submit]"))'), false)
      await selectPeer()
      await waitFor(`JSON.parse(PandasticNative.hubStatus()).contacts.some(contact => contact.number === ${JSON.stringify(peer)})`)
      assert.equal(await app.evaluate('Boolean(document.querySelector("#add-phone-form"))'), false, 'Selecting immediately saves and closes the picker')
      assert.equal(await app.evaluate('document.querySelector(".add-phone-button").getAttribute("aria-expanded")'), 'false')
      await app.evaluate(`Array.from(document.querySelectorAll('.contacts .selected-contact')).find(button => button.querySelector('small').textContent === ${JSON.stringify(peer)}).click()`)
      await waitFor(`!JSON.parse(PandasticNative.hubStatus()).contacts.some(contact => contact.number === ${JSON.stringify(peer)})`)
      assert.ok((await app.evaluate("__e2e.result('__pandasticContactsReply', 'phoneContacts', [], 10000)")).contacts.some(contact => contact.number === peer))
    }
    console.log(`PASS ${serial}: automatic own number, name/number dropdown search, tap and keyboard selection, removal preserves address book/history, no number form${info.mode === 'capable' ? ', immediate allowed-contact selection/removal' : ''}`)
  } finally {
    await app.evaluate(`PandasticNative.setSmsPeer(${JSON.stringify(originalChat.peer)}); PandasticNative.setHubContacts(${JSON.stringify(JSON.stringify(original.contacts))}); PandasticNative.setHubEnabled(${original.enabled}); document.querySelector('.bottom-nav button:first-child').click()`)
    app.close()
  }
}
