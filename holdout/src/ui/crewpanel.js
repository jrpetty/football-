// Crew: the roster, each survivor's sheet (skills, traits, equipment, job),
// and the armory of items with equip, sell and repair shortcuts.
import { RES, ITEMS, QUALITY, RARITY, MODS, STATIONS, OCCUPATIONS, SKILLS, SKILL_KEYS, UTILITIES, TRAITS, INFECTION, SEC_PER_DAY, PERKS, PERK_LEVELS } from '../game/data.js'
import { canControl, S, getS, survivorStats, survivorLevel, equip, unequip, gearLock, itemOf, itemName, itemValue, removeItem, ownerOf, workEff, assign, slots, workersOf, gain, day, killSurvivor, log, infectionStage, treatInfection, researchDone, choosePerk, perkOf } from '../game/state.js'
import { sellMult } from '../game/economy.js'
import { sfx } from '../core/audio.js'
import { bus, h, fmt, clamp } from '../core/util.js'
import { icon } from './icons.js'
import { leaderChip, leaderBanner } from './netui.js'
import { costList, resChip, bar, qualityTag, condBar, itemCard, skillRows, traitTags, hpBar, seg, plural, resIcon } from './common.js'

// ---------------------------------------------------------------- one survivor
export function renderSurvivor(ui, id) {
  const s = getS(id)
  if (!s) return null
  const st = survivorStats(s)
  const job = s.job ? S.stations.find((x) => x.id === s.job) : null
  const post = s.status === 'outpost' ? (S.outposts || []).find((o) => o.crew.includes(s.id)) : null
  const status = s.status === 'mission' ? 'On a supply run' : post ? `Holding the ${post.name} outpost` : s.status === 'injured' ? 'Injured · recovering' : job ? STATIONS[job.type].name : 'No job · builds and forages'
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
      h('button.btn.small', { disabled: s.status === 'mission' || s.status === 'outpost', onclick: () => pickJob(ui, s) }, h('i', { html: icon('hammer') }), job ? 'Change job' : 'Give a job'),
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
    h('h4.subhead', 'Perception'),
    h(
      'div.statgrid',
      stat('Sight', `${Math.round(st.sight)} m`, 'How far they see in daylight. Night, fog and facing away shorten it.'),
      stat('Hearing', `${Math.round(st.hearing)} m`, 'Zombies moving within this range show as ripples, even through walls.'),
      stat('Night', st.nightSight >= 0.95 ? 'Clear' : st.nightSight > 0.2 ? 'Good' : st.nightSight < -0.1 ? 'Poor' : 'Normal', st.torch ? 'Carries a flashlight: a long beam after dark.' : 'Everyone carries a small torch after dark.'),
      stat('Wall sense', st.wallSense ? `${Math.round(st.wallSense)} m` : '—', 'Sees movement through one wall within this range.'),
      stat('Trap spotting', `${st.trapSpot.toFixed(st.trapSpot % 1 ? 1 : 0)} m`, st.trapSpot > 4 ? 'Spots traps from this far without fail.' : 'Only notices traps up close, and not always.'),
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
  // another player leads this survivor: look, don't touch
  const ctl = canControl(s)
  if (!ctl) for (const el of [jobRow, slotsEl, actions]) el.inert = true
  const perks = perksCard(ui, s)
  if (!ctl && perks) perks.inert = true
  const inf = infectionCard(ui, s)
  if (!ctl && inf) inf.inert = true
  return ui.frame(s.name, h('span', OCCUPATIONS[s.occ].name, leaderChip(s)), [leaderBanner(s), head, inf, perks, jobRow, slotsEl, stats, skills, actions], { icon: 'people' })
}
function stat(label, v, tip = null) {
  return h('div.stat', tip ? { 'data-tip': `<b>${label}</b>${tip}` } : null, h('span', label), h('b', v))
}
function equipSlot(ui, s, slot) {
  const it = itemOf(s.equip[slot])
  const label = { weapon: 'Weapon', armor: 'Armor', gear: 'Gear' }[slot]
  const lock = gearLock(s)
  const open = lock ? () => (sfx('error'), ui.toast(`${s.first} is away (${lock.toLowerCase()}): gear can only change hands in camp.`, 'bad')) : () => pickItem(ui, s, slot)
  return h(
    'div.eslot' + (lock ? '.locked' : ''),
    h('span.es-label', label),
    it
      ? h('div.es-item', { onclick: open, 'data-tip': lock ? `Locked: ${lock.toLowerCase()}` : 'Click to change or swap with someone' }, h('b', itemName(it)), qualityTag(it.q), h('span.es-stat', ITEMS[it.id].slot === 'gear' ? ITEMS[it.id].desc : ''), it.mods?.length ? h('span.mod', MODS[it.mods[0]].name) : null, condBar(it))
      : h('div.es-item.empty', { onclick: open }, slot === 'weapon' ? 'Fists' : 'Nothing'),
    it && !lock ? h('button.mini', { onclick: () => (unequip(s, slot), ui.refreshPanel()) }, 'Remove') : null,
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
// Pick gear for a slot: from storage, or swapped with someone else in camp.
export function pickItem(ui, s, slot) {
  const all = S.items.filter((it) => ITEMS[it.id].slot === slot).sort((a, b) => itemValue(b) - itemValue(a))
  const free = all.filter((it) => !ownerOf(it.uid))
  const held = all.filter((it) => ownerOf(it.uid) && ownerOf(it.uid) !== s)
  const mine = itemOf(s.equip[slot])
  let close
  const take = (it) => {
    const who = ownerOf(it.uid)
    if (!equip(s, it.uid)) return sfx('error')
    sfx('select')
    if (who && who !== s) ui.toast(mine ? `${s.first} and ${who.first} swapped: ${who.first} now has the ${itemName(mine)}.` : `${s.first} took the ${itemName(it)} from ${who.first}.`, 'good')
    close()
    ui.refreshPanel()
  }
  close = ui.modal(
    h(
      'div.gearpick',
      h('h2', `${slot[0].toUpperCase() + slot.slice(1)} for ${s.first}`),
      mine ? h('p.note', `${s.first} has the ${itemName(mine)}. Taking something another survivor carries swaps them: they get the ${itemName(mine)}.`) : null,
      h('h3', 'In storage', h('small', `${free.length}`)),
      free.length ? h('div.igrid', free.map((it) => itemCard(it, { onclick: () => take(it) }))) : h('p.note', 'Nothing like that in storage. Craft it at a bench, find it on a run or buy it from the trader.'),
      held.length
        ? [
            h('h3', 'Carried by others', h('small', 'click to swap')),
            h(
              'div.igrid',
              held.map((it) => {
                const who = ownerOf(it.uid)
                const lock = gearLock(who) || (canControl(who) ? null : 'Led by another player')
                return h('div.swapwrap' + (lock ? '.locked' : ''), { 'data-tip': lock ? `${who.first}: ${lock.toLowerCase()}. Gear only changes hands in camp.` : `Swap with ${who.first}` }, itemCard(it, { onclick: lock ? null : () => take(it) }), lock ? h('span.swaplock', h('i', { html: icon('lock') }), lock) : null)
              }),
            ),
          ]
        : null,
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
      'div.crewrow' + (s.status === 'injured' ? '.hurt' : s.status === 'mission' || s.status === 'outpost' ? '.away' : ''),
      { onclick: () => ui.openSurvivor(s.id) },
      h('span.cr-name', h('img.por.sm', { src: ui.game.portrait(s) }), h('span', h('b', leaderChip(s, true), s.name, s.perkChoices?.length ? h('i.perktag', { 'data-tip': 'A perk to choose' }, '★') : null, s.infection > 0 ? h('i.inftag', { 'data-tip': `${infectionStage(s)} · ${Math.round(s.infection)}%` }, `${Math.round(s.infection)}%`) : null), h('small', OCCUPATIONS[s.occ].name))),
      h('span.cr-job', s.status === 'mission' ? 'On a run' : s.status === 'outpost' ? 'At an outpost' : job ? STATIONS[job.type].name : h('em', 'None')),
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
  const list = S.survivors.filter((s) => s.status !== 'mission' && canControl(s))
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
              disabled: !!gearLock(s),
              onclick: () => {
                if (!equip(s, it.uid)) return sfx('error')
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

// ---------------------------------------------------------------- infection
function infectionCard(ui, s) {
  if (!(s.infection > 0)) return null
  const stage = infectionStage(s)
  const left = ((100 - s.infection) / INFECTION.perDay) * SEC_PER_DAY * 3
  const canCure = s.infection < INFECTION.cureBelow
  const anti = researchDone('antiviral')
  return h(
    'section.card.infect',
    h('h3', h('span', h('i.inl', { html: icon('specimen') }), stage), h('small', `${Math.round(s.infection)}% · turns in about ${Math.max(1, Math.round(left / 60))} h untreated`)),
    h('div.bar.infect', h('i', { style: { width: `${Math.min(100, s.infection)}%` } }), h('b.mark', { style: { left: `${INFECTION.fever}%` } }), h('b.mark', { style: { left: `${INFECTION.cureBelow}%` } }), h('b.mark', { style: { left: `${INFECTION.sick}%` } })),
    h('p.note', s.infection >= INFECTION.sick ? 'Too sick to go on runs and barely able to work. An antiviral can only slow it now.' : s.infection >= INFECTION.fever ? 'Feverish: works slower and has less health.' : 'No symptoms yet.', ' A patient at a staffed Infirmary gets worse much more slowly.'),
    anti
      ? h('div.kv', h('span', canCure ? 'An antiviral cures it now.' : `Past ${INFECTION.cureBelow}%: an antiviral knocks it back by ${INFECTION.knock}.`), h('button.btn.small' + (canCure ? '.go' : ''), { disabled: S.res.antiviral < 1, onclick: () => (treatInfection(s) ? (sfx('levelup'), ui.toast(canCure ? `${s.first} is cured` : 'Infection slowed', 'good')) : sfx('error'), ui.refreshPanel()), 'data-tip': `You have ${Math.floor(S.res.antiviral)} antiviral${S.res.antiviral === 1 ? '' : 's'}` }, 'Give antiviral'))
      : h('p.note.bad', 'There is no cure yet. Research Antiviral Serum at the Research Desk (it needs specimens from special infected).'),
  )
}

// ---------------------------------------------------------------- perks
function perksCard(ui, s) {
  const have = s.perks || []
  const pending = s.perkChoices || []
  if (!have.length && !pending.length) return h('section.card.perks', h('h3', 'Perks', h('small', `at level ${PERK_LEVELS.join(', ')} in any skill`)), h('p.note', 'Reach level 5 in a skill to choose the first perk for it.'))
  return h(
    'section.card.perks' + (pending.length ? '.ready' : ''),
    h('h3', 'Perks', h('small', pending.length ? `${pending.length} to choose` : `${have.length} taken`)),
    pending.map((c) =>
      h(
        'div.perkpick',
        h('div.pp-head', h('b', `${SKILLS[c.skill].name} ${PERK_LEVELS[c.tier]}`), h('small', 'Choose one')),
        h(
          'div.pp-opts',
          PERKS[c.skill][c.tier].map((p) => h('button.perkopt', { onclick: () => (choosePerk(s, c.skill, c.tier, p.id) && (sfx('levelup'), ui.toast(`${s.first}: ${p.name}`, 'good')), ui.refreshPanel()) }, h('b', p.name), h('span', p.desc))),
        ),
      ),
    ),
    have.length ? h('div.perklist', have.map((id) => { const p = perkOf(id); return p ? h('span.perkchip', { 'data-tip': `<b>${p.name}</b>${SKILLS[p.skill].name} ${PERK_LEVELS[p.tier]}: ${p.desc}` }, h('i', { html: icon('star') }), p.name) : null })) : null,
  )
}
