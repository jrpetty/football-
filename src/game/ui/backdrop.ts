import * as THREE from 'three'
import { World } from '../match/world'
import { Scene3D } from '../render3d/scene'
import { FIELD } from '../config'
import type { MatchConfig } from '../types'

// The title screen's stage: the real stadium, at the moment before kick-off,
// with a camera drifting round the ball.
//
// It is the actual scene and the actual world — the same Scene3D the match
// draws with, driven by a World that is never stepped, so nothing moves but the
// players breathing, the ball turning on the spot and the camera. That is the
// point of building the game's look once: the first thing you see is the game.
//
// It costs a WebGL context and a render a frame, so it is throttled, it is
// capped at a modest resolution, it stops the moment a match starts, and it
// declines to start at all where it can't work (no WebGL) or shouldn't (an
// automated browser, which would spend the test's time drawing a picture no
// test looks at).
export class MenuBackdrop {
  private scene: Scene3D
  private world: World
  private cam: THREE.PerspectiveCamera
  private raf = 0
  private t0 = performance.now()
  private last = 0
  private running = true
  private still: boolean

  static wanted(): boolean {
    if (location.search.includes('backdrop')) return true
    if (navigator.webdriver) return false
    return true
  }

  constructor(container: HTMLElement, quality: MatchConfig['quality']) {
    const cfg: MatchConfig = {
      teamSize: 4, halfLength: 60, mode: 'match', singleKeeper: false,
      view: '3d',
      // The backdrop never needs the best the machine can do: it sits behind a
      // scrim, and the match it leads to is going to want the frame budget.
      quality: quality === 'high' ? 'medium' : quality,
      position: 'FWD', heightSens: 2, curveSens: 1, humanControlled: false,
    }
    this.still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    this.scene = new Scene3D(container, cfg.quality)
    this.world = new World(cfg)
    this.cam = new THREE.PerspectiveCamera(34, 1, 0.1, 400)
    // Nothing is claimed, so nobody has a name over their head.
    this.resize()
    window.addEventListener('resize', this.onResize)
    this.frame(performance.now())
    this.raf = requestAnimationFrame(this.tick)
  }

  private onResize = () => this.resize()

  private resize() {
    const w = window.innerWidth
    const h = window.innerHeight
    this.scene.resize(w, h)
    this.cam.aspect = w / h
    this.cam.updateProjectionMatrix()
  }

  private tick = (now: number) => {
    if (!this.running) return
    this.raf = requestAnimationFrame(this.tick)
    // Thirty frames a second is plenty for a slow drift, and half the work.
    if (now - this.last < 33) return
    this.frame(now)
  }

  private frame(now: number) {
    const dt = this.last ? Math.min(0.1, (now - this.last) / 1000) : 0.016
    this.last = now
    const t = this.still ? 0 : (now - this.t0) / 1000

    const w = this.world
    // A slow turn on the spot, so the ball reads as a ball and not a picture of one.
    w.ball.spin = 0.16

    const cx = FIELD.length / 2
    const cz = FIELD.width / 2
    // Low, close, and swinging a few degrees either side of straight down the
    // pitch: the goal and the far stand behind, the ball in front of them.
    const a = Math.sin(t * 0.11) * 0.42 - 0.12
    const dist = 4.6 + Math.sin(t * 0.07) * 0.5
    const fx = Math.cos(a)
    const fz = Math.sin(a)
    // Looking a little to the left of the ball puts it on the right of the
    // screen, clear of the text.
    const rx = -fz
    const rz = fx
    const lateral = 0.4 + (this.cam.aspect > 1.2 ? 0.7 : 0)
    this.cam.position.set(cx - fx * dist, 0.42 + Math.sin(t * 0.09) * 0.06, cz - fz * dist)
    this.cam.lookAt(cx - rx * lateral + fx * 6, 1.05, cz - rz * lateral + fz * 6)

    this.scene.sync(w, -1, false, -1, dt, true)
    this.scene.render(this.cam)
  }

  stop() {
    this.running = false
    cancelAnimationFrame(this.raf)
    window.removeEventListener('resize', this.onResize)
    this.scene.dispose()
  }
}
