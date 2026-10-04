import type { Lang } from './native'

// Swahili first. Swahili strings are machine-written and need review by a native speaker
// before any real deployment (stated in README.md).
const sw = {
  appTagline: 'Msaidizi wa shamba lako',
  hello: 'Habari!',
  whatToday: 'Unahitaji nini leo?',
  leafTitle: 'Angalia jani',
  leafHint: 'Piga picha ya jani moja',
  priceTitle: 'Bei ni sawa?',
  priceHint: 'Linganisha bei ya mnunuzi',
  helperTitle: 'Msaidizi wa SMS',
  helperOn: 'Umewashwa',
  helperOff: 'Umezimwa',
  answeredToday: (n: number) => n === 1 ? 'Swali 1 limejibiwa leo' : `Maswali ${n} yamejibiwa leo`,
  offline: 'Inafanya kazi bila intaneti',
  back: 'Rudi',
  demoBadge: 'Majaribio: si akili bandia halisi bado',

  takePhoto: 'Piga picha',
  choosePhoto: 'Chagua picha iliyopo',
  photoTips: 'Jani moja, karibu, kwenye mwanga mzuri.',
  checkThis: 'Angalia picha hii',
  checking: 'Ninaangalia jani…',
  another: 'Angalia jani lingine',

  healthy: 'Jani ni zima',
  notSure: 'Sina uhakika',
  askPerson: 'Uliza mtu: afisa ugani au chama cha ushirika.',
  dontSprayYet: 'Usinyunyizie dawa bado.',
  sayingProblem: 'Kabla ya kunyunyiza dawa, uliza afisa ugani.',
  sayingHealthy: 'Kagua chini ya majani kila wiki.',
  retakeTitle: 'Piga picha tena',
  retake: { blur: 'Picha haiko wazi. Shika simu bila kutikisika.', dark: 'Picha ina giza. Nenda kwenye mwanga.', bright: 'Picha ina mwanga mwingi. Epuka jua moja kwa moja.' } as Record<string, string>,
  unsupportedTitle: 'Sijui kitu hiki',
  unsupported: 'Ninajua majani ya kahawa tu kwa sasa.',
  maybe: (name: string) => `Huenda ni: ${name}`,
  sure: 'Uhakika',
  source: 'Chanzo',
  listen: 'Sikiliza',
  askOfficer: 'Muulize afisa',
  shareCard: 'Shiriki',
  whatToDo: 'Ufanye nini',

  whichCrop: 'Unauza nini?',
  coffee: 'Kahawa',
  maize: 'Mahindi',
  beans: 'Maharage',
  offerQuestion: 'Mnunuzi amekupa bei gani?',
  perKg: 'UGX kwa kilo',
  checkPrice: 'Angalia bei',
  priceFair: 'Bei ni nzuri',
  priceLow: (pct: number) => `Bei iko chini kwa ${pct}%`,
  marketPrice: 'Bei ya soko',
  yourOffer: 'Bei uliyopewa',
  priceOld: (date: string) => `Bei hizi ni za ${date}. Huenda zimebadilika.`,
  sayingPrice: 'Uliza chama kabla ya kuuza.',
  noPrice: 'Sina bei ya zao hili sasa.',

  helperIntro: 'Mama akiwa shambani anatuma SMS. Simu hii inajibu yenyewe, bila intaneti.',
  turnOn: 'Washa msaidizi',
  turnOff: 'Zima msaidizi',
  needsPermission: 'Ruhusu SMS ili msaidizi afanye kazi.',
  whoCanAsk: 'Nani anaweza kuuliza',
  name: 'Jina',
  number: 'Namba ya simu',
  add: 'Ongeza',
  remove: 'Ondoa',
  replyLanguage: 'Lugha ya majibu',
  codesTitle: 'Namba fupi kwa Mama',
  codes: [['1', 'Kahawa'], ['2', 'Mahindi'], ['3', 'Maharage'], ['P', 'Bei'], ['?', 'Msaada']] as [string, string][],
  codesExample: 'Mfano: "P 1 12000" inauliza kama 12,000 ni bei nzuri ya kahawa.',
  officerTitle: 'Afisa ugani',
  officerHint: 'Namba hii inatumika kwenye kitufe cha "Muulize afisa".',
  recent: 'Ujumbe wa karibuni',
  noMessages: 'Bado hakuna ujumbe. Mama akituma SMS, utaiona hapa.',
  privacy: 'Ujumbe unakaa kwenye simu hii tu.',
  clearHistory: 'Futa historia',
  rateLimited: 'Haikujibiwa: ujumbe mwingi kwa saa moja',
  failed: 'Haikutumwa',
  pending: 'Inasubiri',
}

type Strings = typeof sw

const en: Strings = {
  appTagline: 'Your farm helper',
  hello: 'Hello!',
  whatToday: 'What do you need today?',
  leafTitle: 'Check a leaf',
  leafHint: 'Take a photo of one leaf',
  priceTitle: 'Is the price fair?',
  priceHint: "Compare the buyer's offer",
  helperTitle: 'SMS helper',
  helperOn: 'On',
  helperOff: 'Off',
  answeredToday: (n: number) => n === 1 ? '1 question answered today' : `${n} questions answered today`,
  offline: 'Works without internet',
  back: 'Back',
  demoBadge: 'Demo: not the real AI yet',

  takePhoto: 'Take a photo',
  choosePhoto: 'Choose a saved photo',
  photoTips: 'One leaf, close up, in good light.',
  checkThis: 'Check this photo',
  checking: 'Looking at the leaf…',
  another: 'Check another leaf',

  healthy: 'The leaf looks healthy',
  notSure: 'Not sure',
  askPerson: 'Ask a person: the extension officer or the cooperative.',
  dontSprayYet: 'Do not spray yet.',
  sayingProblem: 'Ask the extension officer before you spray.',
  sayingHealthy: 'Check under the leaves every week.',
  retakeTitle: 'Take the photo again',
  retake: { blur: 'The photo is blurry. Hold the phone still.', dark: 'The photo is too dark. Move into the light.', bright: 'The photo is too bright. Avoid direct sun.' },
  unsupportedTitle: "I don't know this",
  unsupported: 'For now I only know coffee leaves.',
  maybe: (name: string) => `It might be: ${name}`,
  sure: 'Sure',
  source: 'Source',
  listen: 'Listen',
  askOfficer: 'Ask the officer',
  shareCard: 'Share',
  whatToDo: 'What to do',

  whichCrop: 'What are you selling?',
  coffee: 'Coffee',
  maize: 'Maize',
  beans: 'Beans',
  offerQuestion: 'What price did the buyer offer?',
  perKg: 'UGX per kilo',
  checkPrice: 'Check the price',
  priceFair: 'The price is fair',
  priceLow: (pct: number) => `The price is ${pct}% too low`,
  marketPrice: 'Market price',
  yourOffer: 'Your offer',
  priceOld: (date: string) => `These prices are from ${date}. They may have changed.`,
  sayingPrice: 'Ask the cooperative before you sell.',
  noPrice: "I don't have a price for this crop yet.",

  helperIntro: 'When Mama is in the field she sends an SMS. This phone answers by itself, without internet.',
  turnOn: 'Turn on the helper',
  turnOff: 'Turn off the helper',
  needsPermission: 'Allow SMS so the helper can work.',
  whoCanAsk: 'Who can ask',
  name: 'Name',
  number: 'Phone number',
  add: 'Add',
  remove: 'Remove',
  replyLanguage: 'Reply language',
  codesTitle: 'Short codes for Mama',
  codes: [['1', 'Coffee'], ['2', 'Maize'], ['3', 'Beans'], ['P', 'Price'], ['?', 'Help']],
  codesExample: 'Example: "P 1 12000" asks whether 12,000 is a fair coffee price.',
  officerTitle: 'Extension officer',
  officerHint: 'Used by the "Ask the officer" button.',
  recent: 'Recent messages',
  noMessages: 'No messages yet. When Mama sends an SMS, you will see it here.',
  privacy: 'Messages stay on this phone only.',
  clearHistory: 'Clear history',
  rateLimited: 'Not answered: too many messages in one hour',
  failed: 'Not sent',
  pending: 'Waiting',
}

export const strings: Record<Lang, Strings> = { sw, en }

// Display names for classifier labels. Unknown labels fall back to the advice text.
export const labelNames: Record<Lang, Record<string, string>> = {
  sw: {
    coffee_rust: 'Kutu ya majani ya kahawa', coffee_miner: 'Mchimba majani', coffee_cercospora: 'Doa la jicho la kahawia',
    coffee_phoma: 'Doa la Phoma', coffee_healthy: 'Jani la kahawa ni zima',
    maize_fall_armyworm: 'Viwavijeshi vamizi', maize_leaf_blight: 'Ukungu wa majani ya mahindi', maize_streak_virus: 'Michirizi ya mahindi',
    maize_lethal_necrosis: 'Ugonjwa hatari wa mahindi (MLN)', maize_leaf_spot: 'Madoa ya majani ya mahindi', maize_healthy: 'Jani la mahindi ni zima',
    bean_rust: 'Kutu ya maharage', bean_angular_leaf_spot: 'Madoa pembe ya maharage', bean_healthy: 'Jani la maharage ni zima',
  },
  en: {
    coffee_rust: 'Coffee leaf rust', coffee_miner: 'Coffee leaf miner', coffee_cercospora: 'Brown eye spot',
    coffee_phoma: 'Phoma leaf spot', coffee_healthy: 'Healthy coffee leaf',
    maize_fall_armyworm: 'Fall armyworm', maize_leaf_blight: 'Maize leaf blight', maize_streak_virus: 'Maize streak virus',
    maize_lethal_necrosis: 'Maize lethal necrosis', maize_leaf_spot: 'Maize leaf spot', maize_healthy: 'Healthy maize leaf',
    bean_rust: 'Bean rust', bean_angular_leaf_spot: 'Angular leaf spot', bean_healthy: 'Healthy bean leaf',
  },
}

const monthNames: Record<Lang, string[]> = {
  sw: ['Januari', 'Februari', 'Machi', 'Aprili', 'Mei', 'Juni', 'Julai', 'Agosti', 'Septemba', 'Oktoba', 'Novemba', 'Desemba'],
  en: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
}

/** "2026-08" → "Agosti 2026" */
export function monthYear(date: string, lang: Lang): string {
  const [year, month] = date.split('-')
  const name = monthNames[lang][Number(month) - 1]
  return name ? `${name} ${year}` : date
}

export function money(value: number): string {
  return Math.round(value).toLocaleString('en-US')
}
