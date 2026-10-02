// Small shared UI builders: resource costs, bars, item cards, skill rows.
import { RES, ITEMS, QUALITY, RARITY, MODS, SKILLS, SKILL_KEYS, SKILL_MAX, xpForLevel, itemStatLine, OCCUPATIONS, TRAITS } from '../game/data.js'
import { S, itemName, itemValue, wearPerUse, survivorStats, ownerOf } from '../game/state.js'
import { h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'

export const resIcon = (k) => icon(k === 'pammo' || k === 'rammo' || k === 'shells' ? 'ammo' : k) || icon('parts')

// A row of resource amounts. have=true colours what you can't afford.
export function costList(cost, { have = true, mult = 1, small = false } = {}) {
  const ents = Object.entries(cost || {}).filter(([, v]) => v > 0)
  if (!ents.length) return h('span.cost.free', 'Free')
  return h(
    'span.cost' + (small ? '.small' : ''),
    ents.map(([k, v]) => {
      const n = Math.ceil(v * mult)
      const short = have && (S.res[k] || 0) < n - 1e-6
      return h('span.ci' + (short ? '.short' : ''), { title: RES[k].name, style: { '--c': RES[k].color } }, h('i.ic', { html: resIcon(k) }), fmt(n))
    }),
  )
}
export function resChip(k, v, extra = '') {
  return h('span.ci', { title: RES[k].name, style: { '--c': RES[k].color } }, h('i.ic', { html: resIcon(k) }), (extra || '') + fmt(v))
}
export function bar(frac, cls = '', label = null) {
  return h('div.bar' + (cls ? '.' + cls : ''), h('i', { style: { width: `${clamp(frac, 0, 1) * 100}%` } }), label ? h('span', label) : null)
}
export function qualityTag(q) {
  const Q = QUALITY[q ?? 1]
  if (!Q || q === 1) return null
  return h('span.qtag', { style: { '--q': Q.color } }, Q.name)
}
export function condBar(it) {
  if (!ITEMS[it.id].dur) return null
  const c = it.cond ?? 100
  return h('div.cond' + (c <= 0 ? '.broken' : c < 35 ? '.low' : ''), { title: `Condition ${Math.round(c)}%` }, h('i', { style: { width: `${clamp(c, 0, 100)}%` } }))
}
// A compact item card used in pickers, the armory and the market.
export function itemCard(it, { onclick = null, owner = true, price = null, selected = false, actions = null } = {}) {
  const I = ITEMS[it.id]
  const R = RARITY[I.rarity]
  const who = owner ? ownerOf(it.uid) : null
  return h(
    'div.icard' + (onclick ? '.click' : '') + (selected ? '.sel' : '') + ((it.cond ?? 100) <= 0 ? '.broken' : ''),
    { onclick, style: { '--rar': R.color, '--q': QUALITY[it.q ?? 1].color } },
    h('div.ic-top', h('b', itemName(it)), qualityTag(it.q), h('span.rar', R.name)),
    h('div.ic-stat', itemStatLine(it.id, it.q ?? 1, it.mods || [])),
    it.mods?.length ? h('div.ic-mods', it.mods.map((m) => h('span.mod', MODS[m].name))) : null,
    condBar(it),
    h('div.ic-foot', who ? h('span.who', who.first) : h('span.who.free', I.slot), price != null ? h('span.price', resChip('cash', price)) : null, actions),
  )
}
export function skillRows(s) {
  return h(
    'div.skills',
    SKILL_KEYS.map((k) => {
      const lv = s.skills[k]
      const need = xpForLevel(lv)
      const frac = lv >= SKILL_MAX ? 1 : (s.xp[k] || 0) / need
      return h('div.skill', { title: SKILLS[k].desc }, h('span.sk-name', SKILLS[k].name), h('span.sk-pips', Array.from({ length: SKILL_MAX }, (_, i) => h('i' + (i < lv ? '.on' : '')))), h('span.sk-lv', lv), h('div.sk-xp', h('i', { style: { width: `${frac * 100}%` } })))
    }),
  )
}
export function traitTags(s) {
  return h(
    'div.traits',
    h('span.trait.occ', { title: OCCUPATIONS[s.occ].perk }, OCCUPATIONS[s.occ].name),
    s.traits.map((t) => h('span.trait' + (TRAITS[t].good ? '.good' : '.bad'), { title: TRAITS[t].desc }, TRAITS[t].name)),
  )
}
export function hpBar(s) {
  const st = survivorStats(s)
  return bar(s.hp / st.maxHp, s.status === 'injured' ? 'hp.low' : 'hp', `${Math.round(s.hp)} / ${st.maxHp}`)
}
export const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`
export function seg(opts, value, onChange) {
  return h(
    'div.seg',
    opts.map(([v, label, title]) => h('button' + (v === value ? '.on' : ''), { title: title || '', onclick: () => onChange(v) }, label)),
  )
}
export function stepper(value, min, max, onChange, fmtFn = (v) => v) {
  return h('div.stepper', h('button', { onclick: () => onChange(Math.max(min, value - 1)), disabled: value <= min }, '−'), h('span', String(fmtFn(value))), h('button', { onclick: () => onChange(Math.min(max, value + 1)), disabled: value >= max }, '+'))
}
