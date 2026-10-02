// The camp has fallen: a short closing sequence over the ruins. The screen
// draws in to a letterbox and the colour drains away, the camera drifts
// slowly round the camp, and the story of the camp comes up a card at a
// time: how it ended, how long it held, what it did, who stood out, and the
// names of everyone it lost.
import { S, day } from '../game/state.js'
import { h, fmt } from '../core/util.js'
import { sfx, setAmbience } from '../core/audio.js'

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

// The numbers, worked out once.
export function endingStats() {
  const st = S.stats || {}
  const all = [...(S.survivors || []), ...(S.fallen || [])]
  const places = (st.places || []).length
  const days = day()
  return [
    { k: 'Days survived', v: days },
    { k: 'Infected put down', v: st.kills || 0 },
    { k: places === 1 ? 'Place searched' : 'Places searched', v: places, sub: places ? `${st.runs || 0} supply run${st.runs === 1 ? '' : 's'}` : null },
    { k: 'Cupboards, cars and crates searched', v: st.searched || 0 },
    { k: 'Things built', v: st.built || 0, sub: st.crafted ? `${fmt(st.crafted)} crafted` : null },
    { k: 'Hordes faced', v: st.raids || 0, sub: st.raidsLost ? `${st.raidsLost} broke through` : 'every one held' },
    { k: 'People taken in', v: st.recruited || 0, sub: st.rescued ? `${st.rescued} rescued` : null },
    { k: 'The most it ever was', v: Math.max(st.peak || 0, all.length), sub: 'people in camp' },
    { k: 'Kilometres travelled', v: Math.round(st.km || 0) },
    { k: 'Notes found', v: st.notes || 0 },
    { k: 'Lost', v: st.deaths || 0, bad: true },
  ].filter((x) => x.v || x.bad || x.k === 'Days survived')
}

// People who stood out, from the memorial and the last survivors.
function honours() {
  const mem = S.stats?.memorial || []
  const out = []
  const best = mem.slice().sort((a, b) => (b.kills || 0) - (a.kills || 0))[0]
  if (best?.kills) out.push({ t: 'Deadliest', n: best.name, d: `${best.kills} infected` })
  const first = mem[mem.length - 1]
  if (first) out.push({ t: 'The first we lost', n: first.name, d: `Day ${first.day}` })
  const last = mem[0]
  if (last && last !== first) out.push({ t: 'The last to fall', n: last.name, d: last.cause })
  return out
}

function causeLine() {
  const f = S.fall
  if (!f) return 'The last of them is gone.'
  if (f.raid || /horde|overran|defending/i.test(f.cause)) return 'The horde broke through the wall, and nobody was left to close it.'
  if (/run|Left behind/i.test(f.cause)) return 'The last of them went out on a run and never came back.'
  if (/turned|infect/i.test(f.cause)) return 'The infection took the last of them.'
  return `${f.cause}.`
}

// Plays the sequence; resolves when it is on its last card.
export async function playEnding(ui) {
  const game = ui.game
  if (document.querySelector('.ending')) return
  document.body.classList.add('ending-on')
  setAmbience(0.012)
  sfx('alarm')
  const stats = endingStats()
  const hon = honours()
  const mem = S.stats?.memorial || []
  const name = S.mp?.name || S.campName || 'The camp'
  const bars = [h('div.end-bar.top'), h('div.end-bar.bot')]
  const stage = h('div.end-stage')
  const root = h('div.ending', bars, stage)
  document.body.append(root)
  game.ending = true
  // a card fades in, holds, and (unless it stays) fades out
  const card = async (el, hold, stay = false) => {
    stage.append(el)
    await wait(40)
    el.classList.add('in')
    await wait(hold)
    if (stay) return
    el.classList.remove('in')
    el.classList.add('out')
    await wait(900)
    el.remove()
  }
  const skip = h('button.end-skip', { onclick: () => (root.dataset.skip = '1') }, 'Skip')
  root.append(skip)
  const fast = () => root.dataset.skip === '1'
  await wait(1400)
  if (!fast()) await card(h('div.end-card.title', h('small', `Day ${day()}`), h('h1', 'The camp has fallen'), h('p', causeLine())), 4200)
  if (!fast()) await card(h('div.end-card', h('p.big', `${name === 'The camp' ? 'They' : name} held out for`), h('h1.num', `${day()} day${day() === 1 ? '' : 's'}`)), 3000)
  // the record: numbers count up one after another
  // columns that leave no card alone on its row
  const n = stats.length
  const grid = h('div.end-stats', { style: { '--cols': n <= 5 ? n : [3, 4, 4, 3, 5, 4, 4][Math.min(6, n - 6)] } })
  const rec = h('div.end-card.record', h('h2', 'What they did'), grid, hon.length ? h('div.end-hon', hon.map((x) => h('div.hon', h('small', x.t), h('b', x.n), h('span', x.d)))) : null)
  stage.innerHTML = ''
  stage.append(rec)
  await wait(40)
  rec.classList.add('in')
  for (const s of stats) {
    const b = h('b', '0')
    grid.append(h('div.es' + (s.bad ? '.bad' : ''), b, h('span', s.k), s.sub ? h('small', s.sub) : null))
    if (fast()) b.textContent = fmt(s.v)
    else {
      const steps = 14
      for (let i = 1; i <= steps; i++) {
        b.textContent = fmt(Math.round((s.v * i) / steps))
        await wait(28)
      }
      sfx('click')
      await wait(90)
    }
  }
  // the names
  if (mem.length) {
    const list = h(
      'div.end-mem',
      h('h3', 'In memory'),
      h(
        'div.mem-roll',
        mem.slice(0, 40).map((m) => h('div.mem', h('b', m.name), h('span', `${m.occ} · day ${m.day}`), h('small', m.cause))),
      ),
    )
    rec.append(list)
  }
  rec.append(
    h(
      'div.end-acts',
      h('button.btn.go.big', { onclick: () => (cleanup(), game.newGame()) }, 'Start a new camp'),
      h('button.btn.big.ghost', { onclick: () => (cleanup(), location.reload()) }, 'Back to the title'),
    ),
  )
  skip.remove()
  function cleanup() {
    root.remove()
    document.body.classList.remove('ending-on')
    game.ending = false
  }
}

