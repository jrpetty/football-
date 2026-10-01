// The journal: story threads with what is known so far and where it might
// lead, requests from people in camp, and every note found in the city.
import { SIGNAL } from '../game/data.js'
import { S, day, vehicleOf } from '../game/state.js'
import { storyState, THREADS, NOTES, noteText, locName, markJournalSeen } from '../game/story.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'
import { icon } from './icons.js'

let tab = 'story'
let openNote = null

export function renderJournal(ui) {
  const st = storyState()
  markJournalSeen()
  const tabs = h(
    'div.tabs',
    [['story', 'Story'], ['notes', `Notes${st.unread.length ? ` (${st.unread.length} new)` : ''}`]].map(([id, label]) => h('button' + (tab === id ? '.on' : ''), { onclick: () => ((tab = id), sfx('click'), ui.refreshPanel()) }, label)),
  )
  const active = Object.values(st.threads).filter((t) => !t.done).length + st.personal.filter((p) => !p.done && !p.failed).length
  if (tab === 'notes' && st.unread.length) st.unread = []
  const body = tab === 'story' ? storyTab(ui, st) : notesTab(ui, st)
  return ui.frame('Journal', `${active} open · ${st.notes.length} note${st.notes.length === 1 ? '' : 's'} found`, body, { icon: 'book', tabs })
}

// A place the story points at: click to open it on the city map.
function leadChip(ui, id, checked, known) {
  return h(
    'button.lead' + (checked ? '.checked' : '') + (known ? '.known' : ''),
    { 'data-tip': checked ? 'Searched: nothing here' : 'Open on the city map', onclick: () => (checked ? null : openOnMap(ui, id)) },
    h('i', { html: icon(checked ? 'close' : 'map') }),
    locName(id),
  )
}
function openOnMap(ui, id) {
  sfx('click')
  const g = ui.game
  const go = () => {
    const L = g.map?.locs?.get(id)
    if (L) g.map.select(L.loc)
  }
  if (g.scene === g.map) return go()
  ui.closePanel()
  g.openMap()
  setTimeout(go, g.map ? 50 : 400)
}

function storyTab(ui, st) {
  const out = []
  const order = ['wheels', 'dana', 'kessler', 'codebook', 'convoy']
  const open = order.filter((id) => st.threads[id] && !st.threads[id].done)
  const done = order.filter((id) => st.threads[id]?.done)
  if (!open.length && !st.personal.some((p) => !p.done && !p.failed)) out.push(h('p.note', 'Nothing open right now. Keep the radio on and keep searching desks.'))
  for (const id of open) out.push(threadCard(ui, id, st.threads[id]))
  for (const p of st.personal.filter((x) => !x.done && !x.failed)) out.push(personalCard(ui, p))
  // what lies ahead: hints at threads not yet open
  const ahead = []
  if (!st.threads.dana) ahead.push('Someone keeps broadcasting on the emergency band. A radio tower would hear them sooner.')
  if (!st.threads.codebook) ahead.push(`Calling the coast will take more than a working mast (Signal phase ${SIGNAL.length}).`)
  if (ahead.length) out.push(h('section.card.j-ahead', h('h3', 'Rumours'), ahead.map((t) => h('p.note', t))))
  if (done.length || st.personal.some((p) => p.done || p.failed))
    out.push(
      h(
        'section.card.j-done',
        h('h3', 'Behind you'),
        done.map((id) => h('div.kv', h('span', THREADS[id].name), h('small.good', `Done · day ${st.threads[id].done}`))),
        st.personal.filter((p) => p.done || p.failed).map((p) => h('div.kv', h('span', `${p.askerName}'s ${p.rel}, ${p.npc.first}`), h('small' + (p.done ? '.good' : '.bad'), p.done ? `Found · day ${p.done}` : `Trail went cold · day ${p.failed}`))),
      ),
    )
  return out
}

function threadCard(ui, id, t) {
  const T = THREADS[id]
  const goal = T.goals[Math.min(t.stage, T.goals.length - 1)]
  const kids = [h('h3', h('span', T.name), h('small', `since day ${t.started}`)), h('div.j-goal', h('i', { html: icon('goals') }), h('b', goal)), h('p.j-blurb', T.blurb)]
  if (id === 'wheels') {
    const v = vehicleOf('van')
    kids.push(h('p.note', T.hints[0]), h('div.kv', h('span', ''), h('button.btn.small', { onclick: () => ui.openMotorPool() }, h('span', { html: icon('truck') }), ' Motor pool')))
    if (v && !v.broken) kids.push(h('p.good', 'The van runs.'))
  }
  if (id === 'kessler' && t.stage === 0) {
    const n = storyState().notes.filter((x) => NOTES[x]?.thread === 'kessler').length
    kids.push(h('div.kv', h('span', 'Kessler files found'), h('b', `${n} / 3`)), h('p.note', 'Kessler files turn up in desks, filing cabinets, lockers and lab shelves, more often in offices, schools, pharmacies and the hospital.'))
  }
  if (t.clues?.length) kids.push(h('div.j-clues', h('small', 'What you know'), h('ul', t.clues.map((c) => h('li', c)))))
  if (t.cands?.length || t.checked?.length) {
    const many = (t.cands || []).length > 6
    kids.push(
      h(
        'div.j-leads',
        h('small', t.cands?.length === 1 ? 'Where to go' : many ? `Could be any of ${t.cands.length} places` : 'Could be at'),
        h('div.leads', [...(t.cands || []).slice(0, 8).map((lid) => leadChip(ui, lid, false, t.cands.length === 1)), ...(t.checked || []).map((lid) => leadChip(ui, lid, true))]),
      ),
    )
  }
  if (id === 'dana') kids.push(h('p.note.dim', 'Signal phase 3 needs the dish array calibrated: Dana can do it. So can Broadcast Engineering research, the long way round.'))
  if (id === 'codebook') kids.push(h('p.note.dim', `Signal phase ${SIGNAL.length} needs the codebook.`))
  return h('section.card.thread', kids)
}

function personalCard(ui, p) {
  const left = p.until - day()
  return h(
    'section.card.thread.personal',
    h('h3', h('span', `${p.askerName}'s ${p.rel}`), h('small' + (left <= 2 ? '.bad' : ''), left > 0 ? `${left} day${left === 1 ? '' : 's'} before the trail goes cold` : 'Last day')),
    h('div.j-goal', h('i', { html: icon('people') }), h('b', `Find ${p.npc.first}`)),
    h('p.j-blurb', p.clue),
    h('div.j-leads', h('small', 'Could be at'), h('div.leads', [...(p.cands || []).map((lid) => leadChip(ui, lid, false, p.cands.length === 1)), ...(p.checked || []).map((lid) => leadChip(ui, lid, true))])),
  )
}

function notesTab(ui, st) {
  if (!st.notes.length) return [h('p.note', 'No notes yet. Desks, filing cabinets, lockers and bookshelves on runs sometimes hold more than cash.')]
  const groups = [['kessler', 'Kessler Biotech'], ['dana', 'WKRN'], [null, 'Ashford']]
  return groups.map(([thr, label]) => {
    const list = st.notes.filter((id) => (NOTES[id].thread === thr || (thr === null && !['kessler', 'dana'].includes(NOTES[id].thread))))
    if (!list.length) return null
    return h(
      'section.card',
      h('h3', label, h('small', `${list.length}`)),
      list.map((id) =>
        h(
          'div.jnote' + (openNote === id ? '.open' : ''),
          h('button.jn-title', { onclick: () => ((openNote = openNote === id ? null : id), sfx('click'), ui.refreshPanel()) }, h('i', { html: icon('log') }), NOTES[id].title),
          openNote === id ? h('div.jn-text', noteText(id).split('\n').map((line) => (line ? h('p', line) : null))) : null,
        ),
      ),
    )
  })
}
