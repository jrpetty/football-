// Co-op supply runs: forming a party in camp, setting out together and
// coming home. The player who plans the run leads it; friends join with
// survivors they lead. The mission side is in scenes/missioncoop.js.
import { S, NET, getS, playerOf, canControl } from '../game/state.js'
import { INFECTION } from '../game/data.js'
import { sfx } from '../core/audio.js'
import { uid } from '../core/util.js'

export const COOP_MAX = 6 // survivors on one run, all players together
export const PER_PLAYER = 4

export class Coop {
  constructor(game) {
    this.game = game
    this.runs = new Map() // open runs I can see (mine included), by id
    this.mine = null // the run I am forming
    this.joined = null // the id of a run I have joined, while it forms
    this.active = null // the run I am out on
    this.pending = [] // messages for a mission still being built
  }
  get net() {
    return this.game.net
  }
  changed() {
    this.game.ui?.coopChanged?.()
    if (this.game.scene === this.game.map) this.game.map?.panel?.render?.()
  }
  total(run) {
    return Object.values(run.roster).reduce((a, ids) => a + ids.length, 0)
  }
  busyIds() {
    const out = new Set()
    for (const r of this.runs.values()) for (const ids of Object.values(r.roster)) for (const id of ids) out.add(id)
    return out
  }
  // Survivors this player could bring.
  available(pid = NET.pid) {
    const busy = this.busyIds()
    return S.survivors.filter((s) => s.status === 'ok' && !(s.infection >= INFECTION.sick) && !busy.has(s.id) && (S.mp?.owner?.[s.id] === pid || !S.mp?.owner?.[s.id]))
  }

  // ---------------------------------------------------------------- leader
  open(loc, ids) {
    if (this.mine) return this.mine
    const run = { id: uid('r'), leader: NET.pid, locId: loc.id, locName: loc.name, level: loc.level, roster: { [NET.pid]: ids.map(String) }, at: Date.now() }
    this.mine = run
    this.runs.set(run.id, run)
    this.net.relay('*', { k: 'runOpen', run })
    sfx('radio')
    this.changed()
    return run
  }
  // the leader's own picks change in the planner
  setMine(ids) {
    const run = this.mine
    if (!run) return
    const want = ids.map(String)
    if (JSON.stringify(want) === JSON.stringify(run.roster[NET.pid])) return
    run.roster[NET.pid] = want
    this.net.relay('*', { k: 'runRoster', id: run.id, roster: run.roster })
  }
  close() {
    const run = this.mine
    if (!run) return
    this.net.relay('*', { k: 'runClose', id: run.id })
    this.runs.delete(run.id)
    this.mine = null
    this.changed()
  }
  // Everyone sets out. Returns the run for the mission to carry.
  go(loadout) {
    const run = this.mine
    if (!run) return null
    run.seed = Math.floor(Math.random() * 2147483647)
    run.loadout = loadout
    this.net.relay('*', { k: 'runGo', id: run.id, seed: run.seed, roster: run.roster, loadout, locId: run.locId })
    this.runs.delete(run.id)
    this.mine = null
    this.active = run
    this.changed()
    return run
  }
  ended() {
    this.active = null
    this.changed()
  }

  // ---------------------------------------------------------------- joiner
  join(id, ids) {
    const run = this.runs.get(id)
    if (!run || run.leader === NET.pid) return
    this.joined = id
    this.net.relay(run.leader, { k: 'runJoin', id, ids: ids.map(String) })
    sfx('select')
    this.changed()
  }
  leave(id = this.joined) {
    const run = this.runs.get(id)
    if (run) this.net.relay(run.leader, { k: 'runLeave', id })
    if (this.joined === id) this.joined = null
    this.changed()
  }
  dismiss(id) {
    if (this.joined === id) this.leave(id)
    this.runs.delete(id)
    this.changed()
  }
  leaderLost(id) {
    if (this.active?.id !== id) return
    this.active = null
    this.game.endCoopRemote({ result: 'lost', title: 'The run broke up', text: `${playerOf(this.game.mission?.coop?.run.leader)?.name || 'The leader'} lost their connection. Your survivors make their own way home.` })
  }

  // ---------------------------------------------------------------- messages
  onRelay(from, d) {
    const run = d.id ? this.runs.get(d.id) : null
    switch (d.k) {
      case 'runOpen': {
        const r = d.run
        if (!r || r.leader !== from || typeof r.id !== 'string') return
        this.runs.set(r.id, { id: r.id, leader: from, locId: r.locId, locName: String(r.locName || 'the city').slice(0, 40), level: +r.level || 1, roster: r.roster || {}, at: Date.now() })
        if (this.game.scene !== this.game.mission) this.game.ui?.toast(`${playerOf(from)?.name || 'Someone'} is heading to ${r.locName}. Join the run from the card.`, 'story')
        sfx('radio')
        return this.changed()
      }
      case 'runRoster':
        if (run && run.leader === from && d.roster) {
          run.roster = d.roster
          // dropped from the run by the leader
          if (this.joined === run.id && !run.roster[NET.pid]) this.joined = null
          this.changed()
        }
        return
      case 'runClose':
        if (run && run.leader === from) {
          this.runs.delete(d.id)
          if (this.joined === d.id) this.joined = null
          this.changed()
        }
        return
      case 'runJoin': {
        const mine = this.mine
        if (!mine || mine.id !== d.id || !Array.isArray(d.ids)) return
        // only survivors that are theirs (or nobody's), free and well
        const busy = this.busyIds()
        const room = COOP_MAX - this.total(mine) + (mine.roster[from]?.length || 0)
        const ok = d.ids
          .map(String)
          .filter((id) => {
            const s = getS(id)
            return s && s.status === 'ok' && !(s.infection >= INFECTION.sick) && (!busy.has(id) || mine.roster[from]?.includes(id)) && canControlFor(s, from)
          })
          .slice(0, Math.min(PER_PLAYER, room))
        if (ok.length) mine.roster[from] = ok
        else delete mine.roster[from]
        this.net.relay('*', { k: 'runRoster', id: mine.id, roster: mine.roster })
        if (ok.length) this.game.ui?.toast(`${playerOf(from)?.name || 'A friend'} joins the run with ${ok.map((id) => getS(id)?.first).join(', ')}.`, 'good')
        return this.changed()
      }
      case 'runLeave': {
        const mine = this.mine
        if (!mine || mine.id !== d.id || !mine.roster[from]) return
        delete mine.roster[from]
        this.net.relay('*', { k: 'runRoster', id: mine.id, roster: mine.roster })
        return this.changed()
      }
      case 'runGo': {
        this.runs.delete(d.id)
        if (this.joined === d.id) this.joined = null
        if (d.roster?.[NET.pid]) {
          this.active = { id: d.id, leader: from, roster: d.roster, seed: d.seed, locId: d.locId, loadout: d.loadout || {} }
          this.game.startCoopRemote(this.active)
        }
        return this.changed()
      }
      // ---- out on the run
      case 'rs':
      case 'rf':
      case 'runEnd': {
        const m = this.game.mission
        if (!m?.remote || m.coop.run.id !== d.run || m.coop.run.leader !== from) {
          if (this.active?.id === d.run) this.pending.push(d)
          return
        }
        return this.toMission(m, d)
      }
      case 'ping':
        return this.game.showPing?.(from, { k: 'ping', x: +d.x, z: +d.z, w: String(d.w || '') })
      case 'rc': {
        const m = this.game.mission
        if (m?.coop?.role === 'leader' && m.coop.run.id === d.run) m.coopOrder(from, d)
        return
      }
    }
  }
  toMission(m, d) {
    if (d.k === 'rs') m.coopApplySetup(d.setup)
    else if (d.k === 'rf') m.coopFrame(d)
    else if (d.k === 'runEnd') {
      this.active = null
      this.game.endCoopRemote(d.report)
    }
  }
  // the mission is built: catch up on what arrived meanwhile
  flushPending(m) {
    const list = this.pending.filter((d) => d.run === m.coop.run.id)
    this.pending = []
    const setup = list.find((d) => d.k === 'rs')
    if (setup) this.toMission(m, setup)
    for (const d of list) if (d.k !== 'rs') this.toMission(m, d)
  }
}

// may player pid give orders to survivor s?
function canControlFor(s, pid) {
  const o = S.mp?.owner?.[s.id]
  return !o || o === pid
}
void canControl
