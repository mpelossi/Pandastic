#!/usr/bin/env node
// Create a separate virtual phone using an installed system image. Never copy phone data.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, isAbsolute, resolve } from 'node:path'

const [sdk, helper, name = 'pandastic_basic'] = process.argv.slice(2)
if (!sdk || !helper || ![helper, name].every(value => /^[A-Za-z0-9_.-]+$/.test(value)) || helper === name) {
  throw new Error('Usage: node scripts/create-basic-avd.mjs SDK_DIRECTORY HELPER_AVD [BASIC_AVD]')
}
const root = process.env.ANDROID_AVD_HOME || resolve(process.env.ANDROID_USER_HOME || resolve(homedir(), '.android'), 'avd')
const index = resolve(root, `${name}.ini`)
const directory = resolve(root, `${name}.avd`)
if (existsSync(index)) {
  console.log(`Using existing Basic phone AVD ${name}.`)
  process.exit(0)
}
function properties(file) {
  return Object.fromEntries(readFileSync(file, 'utf8').split(/\r?\n/).filter(line => line.includes('=') && !line.startsWith('#')).map(line => {
    const separator = line.indexOf('=')
    return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()]
  }))
}
const helperIndex = properties(resolve(root, `${helper}.ini`))
const helperDirectory = helperIndex.path || resolve(dirname(root), helperIndex['path.rel'])
const template = properties(resolve(helperDirectory, 'config.ini'))
const image = template['image.sysdir.1']
if (!image || !existsSync(isAbsolute(image) ? image : resolve(sdk, image))) throw new Error(`Helper ${helper} has no installed system image.`)
if (existsSync(directory)) throw new Error(`Unregistered AVD folder ${directory} already exists; choose another BASIC_AVD instead of overwriting it.`)
// Copy hardware and system-image metadata only. Userdata, SD cards, snapshots and models stay separate.
const config = Object.fromEntries(Object.entries(template).filter(([key]) =>
  key.startsWith('hw.') || key.startsWith('tag.') || ['PlayStore.enabled', 'abi.type', 'image.sysdir.1', 'target', 'vm.heapSize', 'skin.dynamic', 'skin.name', 'skin.path', 'showDeviceFrame'].includes(key)))
for (const key of Object.keys(config)) if (key.endsWith('.path') && key !== 'skin.path') delete config[key]
Object.assign(config, {
  AvdId: name, 'avd.ini.displayname': 'Pandastic Basic Phone', 'avd.ini.encoding': 'UTF-8',
  'disk.dataPartition.size': '4G', 'hw.ramSize': '2048', 'hw.cpu.ncore': '2', 'hw.sdCard': 'no',
  'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
})
mkdirSync(directory, { recursive: true })
writeFileSync(resolve(directory, 'config.ini'), Object.entries(config).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { flag: 'wx' })
writeFileSync(index, `avd.ini.encoding=UTF-8\npath=${directory}\ntarget=${template.target || helperIndex.target}\n`, { flag: 'wx' })
console.log(`Created ${name} using the installed ${image} image with its own fresh phone storage.`)
