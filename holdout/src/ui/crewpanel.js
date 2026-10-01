// Crew: the roster, each survivor's sheet (skills, traits, equipment, job),
// and the armory of items with equip, sell and repair shortcuts.
import { RES, ITEMS, QUALITY, RARITY, MODS, STATIONS, OCCUPATIONS, SKILLS, SKILL_KEYS, UTILITIES, TRAITS } from '../game/data.js'
import { S, getS, survivorStats, survivorLevel, equip, unequip, itemOf, itemName, itemValue, removeItem, ownerOf, workEff, assign, slots, workersOf, gain, day, killSurvivor, log } from '../game/state.js'
import { sellMult } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { bus, h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { costList, resChip, bar, qualityTag, condBar, itemCard, skillRows, traitTags, hpBar, seg, plural, resIcon } from './common.js'

// ---------------------------------------------------------------- one survivor
export function renderSurvivor(ui, id) {
  const s = getS(id)
  if (!s) return null
  const st = survivorStats(s)
  const job = s.job ? S.stations.find((x) => x.id === s.job) : null
  const status = s.status === 'mission' ? 'On a supply run' : s.status === 'injured' ? 'Injured · recovering' : job ? STATIONS[job.type].name : 'No job · builds and forages'
  const head = h(
    'div.sheet-head',
    h('img.por.big', { src: ui.game.portrait(s) }),
    h(
      'div.sh-info',
      traitTags(s),
      h('p.perk', h('b', OCCUPATIONS[s.occ].name + ': '), OCCUPATIONS[s.occ].perk),
      hpBar(s),
      h('div.sh-meta', h('span', `Level ${survivorLevel(s)}`), h('span', `Age ${s.age}`), h('span', `Joined day ${s.joined}`), h('span', `${s.kills} kills`), h('span', `${s.runs} runs`)),
    ),
  )
  const jobRow = h(
    'section.card',
    h('h3', 'Job', h('small', status)),
    h(
      'div.jobrow',
      h('button.btn.small', { disabled: s.status === 'mission', onclick: () => pickJob(ui, s) }, h('i', { html: icon('hammer') }), job ? 'Change job' : 'Give a job'),
      job ? h('button.btn.small.ghost', { onclick: () => (assign(s, null), ui.refreshPanel()) }, 'Unassign') : null,
      job ? h('button.btn.small.ghost', { onclick: () => ui.openStation(job.id) }, 'Open station') : null,
    ),
  )
  const slotsEl = h(
    'section.card',
    h('h3', 'Equipment', h('small', `${Math.round(st.dmg)} dmg · ${st.range.toFixed(1)} m · ${Math.round(st.dr * 100)}% armor`)),
    ['weapon', 'armor', 'gear'].map((slot) => equipSlot(ui, s, slot)),
    utilSlot(ui, s),
  )
  const stats = h(
    'section.card',
    h('h3', 'On a run'),
    h(
      'div.statgrid',
      stat('Health', st.maxHp),
      stat('Speed', `${st.speed.toFixed(1)} m/s`),
      stat('Carry', st.carry),
      stat('Search', `${Math.round(st.search * 100)}%`),
      stat('Loot', `+${Math.round((st.loot - 1) * 100)}%`),
      stat('Accuracy', st.gun ? `${Math.round(st.acc * 100)}%` : '—'),
      stat('Noise', `${Math.round(st.noiseMult * 100)}%`),
      stat('Stealth', st.stealth ? `${Math.round(st.stealth * 100)}%` : '—'),
    ),
  )
  const skills = h('section.card', h('h3', 'Skills', h('small', 'Skills grow by doing: working stations, fighting, searching.')), skillRows(s))
  const actions = h(
    'div.pactions',
    h(
      'button.btn.small.ghost.danger',
      {
        disabled: s.status === 'mission' || S.survivors.length <= 1,
        onclick: () =>
          ui.confirm(`Send ${s.first} away?`, 'They leave the camp for good, taking nothing with them.', 'Send away', () => {
            for (const k of Object.keys(s.equip)) s.equip[k] = null
            S.survivors = S.survivors.filter((x) => x !== s)
            log(`${s.name} was sent away.`, 'bad')
            bus.emit('change')
            ui.closePanel()
          }, { danger: true }),
      },
      'Send away',
    ),
  )
  return ui.frame(s.name, h('span', OCCUPATIONS[s.occ].name), [head, jobRow, slotsEl, stats, skills, actions], { icon: 'people' })
}
function stat(label, v) {
  return h('div.stat', h('span', label), h('b', v))
}
function equipSlot(ui, s, slot) {
  const it = itemOf(s.equip[slot])
  const label = { weapon: 'Weapon', armor: 'Armor', gear: 'Gear' }[slot]
  return h(
    'div.eslot',
    h('span.es-label', label),
    it
      ? h('div.es-item', { onclick: () => pickItem(ui, s, slot) }, h('b', itemName(it)), qualityTag(it.q), h('span.es-stat', ITEMS[it.id].slot === 'gear' ? ITEMS[it.id].desc : ''), it.mods?.length ? h('span.mod', MODS[it.mods[0]].name) : null, condBar(it))
      : h('div.es-item.empty', { onclick: () => pickItem(ui, s, slot) }, slot === 'weapon' ? 'Fists' : 'Nothing'),
    it ? h('button.mini', { onclick: () => (unequip(s, slot), ui.refreshPanel()) }, 'Remove') : null,
  )
}
function utilSlot(ui, s) {
  const opts = [[null, 'None'], ...UTILITIES.map((k) => [k, RES[k].name])]
  return h(
    'div.eslot',
    h('span.es-label', 'Utility'),
    h(
      'div.es-util',
      opts.map(([k, name]) => h('button.chip' + ((s.util || null) === k ? '.on' : ''), { disabled: k && S.res[k] < 1 && s.util !== k, onclick: () => ((s.util = k), sfx('click'), ui.refreshPanel()), 'data-tip': k ? `${RES[k].desc}<br><em>${fmt(S.res[k])} in storage</em>` : 'Carries no utility item' }, k ? h('i', { html: resIcon(k) }) : null, name)),
    ),
  )
}
export function pickJob(ui, s) {
  const opts = S.stations
    .filter((st) => st.level > 0 && slots(st) > 0)
    .map((st) => ({ st, e: workEff(s, st.type), free: slots(st) - workersOf(st).filter((w) => w !== s).length }))
    .sort((a, b) => b.e - a.e)
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', `Job for ${s.first}`),
      h(
        'div.picklist',
        h('button.pickrow', { onclick: () => (assign(s, null), close(), ui.refreshPanel()) }, h('div.pr-main', h('b', 'No job'), h('span', 'Helps build, hauls supplies, rests by the fire'))),
        opts.map(({ st, e, free }) =>
          h(
            'button.pickrow' + (free <= 0 ? '.full' : ''),
            {
              disabled: free <= 0 && s.job !== st.id,
              onclick: () => {
                if (assign(s, st)) sfx('select')
                close()
                ui.refreshPanel()
              },
            },
            h('div.pr-main', h('b', STATIONS[st.type].name, ` L${st.level}`), h('span', STATIONS[st.type].skill ? SKILLS[STATIONS[st.type].skill].name : 'Any skill', OCCUPATIONS[s.occ].fx.station?.[st.type] ? h('em.good', ` · ${OCCUPATIONS[s.occ].name} bonus`) : null)),
            h('div.pr-job', free > 0 ? `${free} free` : s.job === st.id ? 'Current' : 'Full'),
            h('div.pr-eff' + (e >= 1.2 ? '.good' : e < 0.85 ? '.bad' : ''), `${Math.round(e * 100)}%`),
          ),
        ),
      ),
    ),
    { actions: [h('button.btn.ghost', { onclick: () => close() }, 'Close')] },
  )
}
export function pickItem(ui, s, slot) {
  const items = S.items.filter((it) => ITEMS[it.id].slot === slot).sort((a, b) => itemValue(b) - itemValue(a))
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', `${slot[0].toUpperCase() + slot.slice(1)} for ${s.first}`),
      items.length
        ? h(
            'div.igrid',
            items.map((it) =>
              itemCard(it, {
                selected: s.equip[slot] === it.uid,
                onclick: () => {
                  equip(s, it.uid)
                  sfx('select')
                  close()
                  ui.refreshPanel()
                },
              }),
            ),
          )
        : h('p.note', 'Nothing like that in storage. Craft it at a bench, find it on a run or buy it from the trader.'),
    ),
    { actions: [h('button.btn.ghost', { onclick: () => close() }, 'Close')] },
  )
}

// ---------------------------------------------------------------- roster
let crewSort = 'name'
export function renderCrew(ui) {
  const list = [...S.survivors]
  const key = {
    name: (s) => s.name,
    job: (s) => (s.job ? STATIONS[S.stations.find((x) => x.id === s.job)?.type]?.name || '' : 'zz'),
    level: (s) => -survivorLevel(s),
    hp: (s) => s.hp / survivorStats(s).maxHp,
  }[crewSort]
  list.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0))
  const head = h('div.crewhead', h('span', 'Survivor'), h('span', 'Job'), ...SKILL_KEYS.map((k) => h('span.sk', { 'data-tip': SKILLS[k].name }, SKILLS[k].short)), h('span', 'Weapon'), h('span', 'Health'))
  const rows = list.map((s) => {
    const job = s.job ? S.stations.find((x) => x.id === s.job) : null
    const st = survivorStats(s)
    return h(
      'div.crewrow' + (s.status === 'injured' ? '.hurt' : s.status === 'mission' ? '.away' : ''),
      { onclick: () => ui.openSurvivor(s.id) },
      h('span.cr-name', h('img.por.sm', { src: ui.game.portrait(s) }), h('span', h('b', s.name), h('small', OCCUPATIONS[s.occ].name))),
      h('span.cr-job', s.status === 'mission' ? 'On a run' : job ? STATIONS[job.type].name : h('em', 'None')),
      ...SKILL_KEYS.map((k) => h('span.sk' + (s.skills[k] >= 6 ? '.hi' : s.skills[k] <= 1 ? '.lo' : ''), s.skills[k])),
      h('span.cr-wpn', st.weapon.name),
      h('span.cr-hp', bar(s.hp / st.maxHp, s.status === 'injured' ? 'hp.low' : 'hp')),
    )
  })
  const idle = S.survivors.filter((s) => !s.job && s.status === 'ok').length
  return ui.frame(
    'Crew',
    `${plural(S.survivors.length, 'survivor')} · ${idle} without a job`,
    [
      seg([['name', 'Name'], ['job', 'Job'], ['level', 'Level'], ['hp', 'Health']], crewSort, (v) => ((crewSort = v), ui.refreshPanel())),
      h('div.crew', head, rows),
      h('p.note', 'Click someone to see their sheet, change their job or swap their gear.'),
    ],
    { icon: 'people' },
  )
}

// ---------------------------------------------------------------- items
let itemFilter = 'all'
export function renderItems(ui) {
  const filters = [['all', 'All'], ['weapon', 'Weapons'], ['armor', 'Armor'], ['gear', 'Gear'], ['free', 'Unused'], ['worn', 'Worn']]
  let items = [...S.items]
  if (itemFilter === 'free') items = items.filter((it) => !ownerOf(it.uid))
  else if (itemFilter === 'worn') items = items.filter((it) => ITEMS[it.id].dur && it.cond < 60)
  else if (itemFilter !== 'all') items = items.filter((it) => ITEMS[it.id].slot === itemFilter)
  items.sort((a, b) => (ITEMS[a.id].slot < ITEMS[b.id].slot ? -1 : ITEMS[a.id].slot > ITEMS[b.id].slot ? 1 : itemValue(b) - itemValue(a)))
  const sm = sellMult()
  const cards = items.map((it) => {
    const who = ownerOf(it.uid)
    const price = Math.round(itemValue(it) * 0.6 * sm)
    return itemCard(it, {
      actions: h(
        'span.ic-acts',
        h('button.mini', { onclick: () => giveItem(ui, it) }, who ? 'Swap' : 'Equip'),
        h(
          'button.mini',
          {
            onclick: () =>
              ui.confirm(`Sell ${itemName(it)}?`, `The trader pays ${price} cash.${who ? ` ${who.first} loses it.` : ''}`, 'Sell', () => {
                removeItem(it.uid)
                gain({ cash: price })
                sfx('coin')
                ui.refreshPanel()
              }),
          },
          `Sell ${price}`,
        ),
      ),
    })
  })
  return ui.frame('Items', `${plural(S.items.length, 'item')} in camp`, [seg(filters, itemFilter, (v) => ((itemFilter = v), ui.refreshPanel())), cards.length ? h('div.igrid', cards) : h('p.note', 'Nothing here.')], { icon: 'items' })
}
function giveItem(ui, it) {
  const slot = ITEMS[it.id].slot
  const list = S.survivors.filter((s) => s.status !== 'mission')
  let close
  close = ui.modal(
    h(
      'div',
      h('h2', `Who gets the ${itemName(it)}?`),
      h(
        'div.picklist',
        list.map((s) => {
          const cur = itemOf(s.equip[slot])
          return h(
            'button.pickrow',
            {
              onclick: () => {
                equip(s, it.uid)
                sfx('select')
                close()
                ui.refreshPanel()
              },
            },
            h('img.por', { src: ui.game.portrait(s) }),
            h('div.pr-main', h('b', s.name), h('span', OCCUPATIONS[s.occ].name)),
            h('div.pr-job', cur ? `Has ${itemName(cur)}` : 'Empty slot'),
          )
        }),
      ),
    ),
    { actions: [h('button.btn.ghost', { onclick: () => close() }, 'Close')] },
  )
}
