import * as THREE from 'three'
import { DEFEND, PLAYER } from '../config'
import { clamp, clamp01, angleDelta } from '../core/math'
import type { Player } from '../entities/player'
import { kitTexture, numberTexture } from './textures'
import { KITS } from '../config'

// An articulated player, and the gait that drives it.
//
// The model is a real skeleton rather than limbs hinged at the hip: legs bend at
// the knee, arms at the elbow, and the torso counter-rotates against the hips.
// That is what separates something that reads as *running* from sticks swinging
// back and forth.
//
// The gait is driven by distance covered, not by a fixed timer. Stride length
// grows with pace and the cycle frequency is speed ÷ stride, so the feet stay
// planted where they land instead of skating along underneath the player — the
// single biggest giveaway in a procedural run cycle.

// Limb rotations are about X. Because the model faces +Z, a positive angle
// swings a limb *backwards* and a negative one forwards.
interface Limb {
  hip: THREE.Group // shoulder for an arm
  knee: THREE.Group // elbow for an arm
  foot?: THREE.Object3D
}

interface Mats {
  jersey: THREE.Material
  shorts: THREE.Material
  socks: THREE.Material
  skin: THREE.Material
  boot: THREE.Material
  hair: THREE.Material
}

const MODEL_HEIGHT = 1.9 // the proportions below are built at this height

export class PlayerRig {
  readonly group = new THREE.Group() // world position + facing
  readonly ring: THREE.Mesh
  private body = new THREE.Group() // scaled to real human height
  private root = new THREE.Group() // bob, lean and bank live here
  private hips = new THREE.Group()
  private torso = new THREE.Group()
  private head = new THREE.Group()
  private legs: Limb[] = []
  private arms: Limb[] = []

  private phase = Math.random() * Math.PI * 2
  private lean = 0
  private bank = 0
  private prevHeading = 0
  private idle = 0
  // The gait's own idea of where the spine is, kept apart from the bones so a
  // contact pose can write them freely without corrupting the run cycle.
  private spine = { rootX: 0, rootZ: 0, hipsY: 0, torsoY: 0, torsoX: 0 }

  constructor(mats: Mats, team: 'home' | 'away', role: string, num: number) {
    this.group.add(this.body)
    this.body.add(this.root)
    this.root.add(this.hips)
    this.hips.add(this.torso)

    const kit = KITS[team]

    // ---- torso ----
    const pelvis = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.24, 0.26), mats.shorts)
    pelvis.position.y = 0.02
    pelvis.castShadow = true
    this.hips.add(pelvis)

    // Torso in two parts so the silhouette tapers. A single capsule read as a
    // slab: same width at the shoulders as at the belt, which is what made the
    // body look like a sign rather than a person.
    const chest = new THREE.Mesh(new THREE.CapsuleGeometry(0.22, 0.26, 5, 14), mats.jersey)
    chest.scale.set(1.24, 1, 0.78)
    chest.position.y = 0.46
    chest.castShadow = true
    this.torso.add(chest)

    const waist = new THREE.Mesh(new THREE.CapsuleGeometry(0.19, 0.14, 4, 12), mats.jersey)
    waist.scale.set(1.05, 1, 0.72)
    waist.position.y = 0.18
    waist.castShadow = true
    this.torso.add(waist)

    // Deltoid caps, so the arms grow out of the shoulders instead of being
    // stuck onto the side of a box.
    for (const side of [-1, 1]) {
      const delt = new THREE.Mesh(new THREE.SphereGeometry(0.105, 12, 10), mats.jersey)
      delt.scale.set(1, 0.9, 0.85)
      delt.position.set(0.245 * side, 0.55, 0)
      delt.castShadow = true
      this.torso.add(delt)
    }

    // Squad number across the shoulders.
    const numTex = new THREE.CanvasTexture(numberTexture(num, kit.secondary))
    numTex.colorSpace = THREE.SRGBColorSpace
    const back = new THREE.Mesh(
      new THREE.PlaneGeometry(0.3, 0.3),
      new THREE.MeshStandardMaterial({ map: numTex, transparent: true, roughness: 0.7 }),
    )
    back.position.set(0, 0.42, -0.185)
    back.rotation.y = Math.PI
    this.torso.add(back)

    // ---- head, on its own group so it can be held level while the body works ----
    this.torso.add(this.head)
    this.head.position.y = 0.62
    const neck = new THREE.Mesh(new THREE.CylinderGeometry(0.065, 0.08, 0.12, 8), mats.skin)
    neck.position.y = 0.03
    this.head.add(neck)
    const skull = new THREE.Mesh(new THREE.SphereGeometry(0.145, 18, 16), mats.skin)
    skull.scale.set(1, 1.06, 1.04)
    skull.position.y = 0.19
    skull.castShadow = true
    this.head.add(skull)
    // Hair used to sweep 0.6π down from the crown, which is most of the sphere —
    // dark material over nearly the whole head, so close up it read as a blank
    // helmet. A cap over the top third leaves a face below it.
    const hair = new THREE.Mesh(
      new THREE.SphereGeometry(0.152, 18, 14, 0, Math.PI * 2, 0, Math.PI * 0.38),
      mats.hair,
    )
    hair.position.y = 0.2
    this.head.add(hair)
    // The back of the head keeps its hair all the way down to the nape.
    const nape = new THREE.Mesh(
      new THREE.SphereGeometry(0.15, 16, 12, Math.PI * 0.62, Math.PI * 0.76, Math.PI * 0.2, Math.PI * 0.5),
      mats.hair,
    )
    nape.position.y = 0.19
    this.head.add(nape)

    // A face, at the smallest scale that still reads: two eyes, a brow, ears.
    // None of it is visible at playing distance, and all of it is the difference
    // between a person and a mannequin when the camera comes in close.
    const eyeGeo = new THREE.SphereGeometry(0.021, 8, 8)
    const browGeo = new THREE.BoxGeometry(0.052, 0.014, 0.02)
    for (const side of [-1, 1]) {
      const eye = new THREE.Mesh(eyeGeo, mats.hair)
      eye.position.set(0.052 * side, 0.205, 0.126)
      this.head.add(eye)
      const brow = new THREE.Mesh(browGeo, mats.hair)
      brow.position.set(0.055 * side, 0.238, 0.125)
      brow.rotation.z = -0.12 * side
      this.head.add(brow)
      const ear = new THREE.Mesh(new THREE.SphereGeometry(0.032, 8, 8), mats.skin)
      ear.scale.set(0.45, 1, 0.75)
      ear.position.set(0.142 * side, 0.19, 0.005)
      this.head.add(ear)
    }
    const nose = new THREE.Mesh(new THREE.ConeGeometry(0.028, 0.055, 8), mats.skin)
    nose.rotation.x = Math.PI / 2
    nose.position.set(0, 0.182, 0.142)
    this.head.add(nose)

    // ---- legs: hip → thigh → knee → shin → foot ----
    for (const side of [-1, 1]) {
      const hip = new THREE.Group()
      hip.position.set(0.115 * side, 0.0, 0)
      this.hips.add(hip)

      // The leg was shorts to the knee and socks below it — no skin anywhere,
      // which is what made the players look like they were wearing tights.
      // A real kit shows bare leg from mid-thigh to just below the knee.
      const shortLeg = new THREE.Mesh(new THREE.CapsuleGeometry(0.1, 0.09, 4, 10), mats.shorts)
      shortLeg.position.y = -0.13
      shortLeg.castShadow = true
      hip.add(shortLeg)

      const thigh = new THREE.Mesh(new THREE.CapsuleGeometry(0.088, 0.16, 4, 10), mats.skin)
      thigh.position.y = -0.29
      thigh.castShadow = true
      hip.add(thigh)

      const knee = new THREE.Group()
      knee.position.y = -0.44
      hip.add(knee)

      // Bare shin down to where the sock starts, then the sock to the ankle.
      const shin = new THREE.Mesh(new THREE.CapsuleGeometry(0.07, 0.08, 4, 10), mats.skin)
      shin.position.y = -0.08
      shin.castShadow = true
      knee.add(shin)

      const sock = new THREE.Mesh(new THREE.CapsuleGeometry(0.072, 0.16, 4, 10), mats.socks)
      sock.position.y = -0.27
      sock.castShadow = true
      knee.add(sock)

      const foot = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.085, 0.27), mats.boot)
      foot.position.set(0, -0.42, 0.055)
      foot.castShadow = true
      knee.add(foot)

      this.legs.push({ hip, knee, foot })
    }

    // ---- arms: shoulder → upper arm → elbow → forearm ----
    const sleeveMat = role === 'GK' ? mats.jersey : mats.jersey
    for (const side of [-1, 1]) {
      const shoulder = new THREE.Group()
      shoulder.position.set(0.265 * side, 0.55, 0)
      this.torso.add(shoulder)

      const sleeve = new THREE.Mesh(new THREE.CylinderGeometry(0.088, 0.08, 0.15, 10), sleeveMat)
      sleeve.position.y = -0.06
      sleeve.castShadow = true
      shoulder.add(sleeve)

      const upper = new THREE.Mesh(new THREE.CapsuleGeometry(0.062, 0.16, 4, 8), mats.skin)
      upper.position.y = -0.17
      upper.castShadow = true
      shoulder.add(upper)

      const elbow = new THREE.Group()
      elbow.position.y = -0.29
      shoulder.add(elbow)

      const fore = new THREE.Mesh(new THREE.CapsuleGeometry(0.055, 0.16, 4, 8), mats.skin)
      fore.position.y = -0.14
      fore.castShadow = true
      elbow.add(fore)

      this.arms.push({ hip: shoulder, knee: elbow })
    }

    // Ground marker for whoever you're controlling.
    this.ring = new THREE.Mesh(
      new THREE.TorusGeometry(PLAYER.radius + 0.16, 0.05, 8, 32),
      new THREE.MeshStandardMaterial({
        color: '#eafff0',
        emissive: '#7dff9a',
        emissiveIntensity: 0.6,
      }),
    )
    this.ring.rotation.x = -Math.PI / 2
    this.ring.position.y = 0.05
    this.ring.visible = false
    this.group.add(this.ring)

    // Scale the assembled body to a real footballer's height. Measuring it
    // rather than assuming keeps proportions honest if a limb is adjusted.
    const built = new THREE.Box3().setFromObject(this.body)
    const h = built.max.y - built.min.y
    if (h > 0.1) this.body.scale.setScalar(PLAYER.height / h)
    // Sit the feet on the turf.
    this.root.position.y = (PLAYER.height / MODEL_HEIGHT) * 0.88
  }

  // Pose the skeleton for this frame.
  pose(p: Player, x: number, y: number, dt: number) {
    this.group.position.set(x, 0, y)
    // The model faces +Z while a heading of 0 means +X, hence the quarter turn.
    this.group.rotation.y = Math.PI / 2 - p.heading

    const speed = p.speed
    const fast = clamp01(speed / PLAYER.sprintSpeed)
    const moving = speed > 0.35

    if (p.sliding) {
      this.poseSlide(1)
      return
    }
    // Still on the floor after the slide, hauling yourself back upright. This is
    // the cost of the move made visible: the player you dived at is already
    // past you and you are watching it happen from the grass.
    if (p.slideRecover > 0) {
      this.poseSlide(clamp01(p.slideRecover / DEFEND.slideRecovery))
      return
    }

    // --- cadence from distance covered, so the feet don't skate ---
    // A full cycle is two steps; the ground it covers grows with pace.
    const cycleLength = 2.15 + speed * 0.23
    const cycleFreq = moving ? speed / cycleLength : 0
    this.phase += cycleFreq * Math.PI * 2 * dt
    if (this.phase > Math.PI * 2) this.phase -= Math.PI * 2
    this.idle += dt

    // --- posture ---
    // Lean into the run, and bank into a turn like a real change of direction.
    const targetLean = fast * 0.3
    this.lean += (targetLean - this.lean) * clamp01(dt * 8)
    const turn = angleDelta(this.prevHeading, p.heading) / Math.max(dt, 1e-3)
    this.prevHeading = p.heading
    const targetBank = clamp(turn * 0.06 * fast, -0.28, 0.28)
    this.bank += (targetBank - this.bank) * clamp01(dt * 6)

    const p1 = this.phase
    if (moving) {
      const hipAmp = 0.24 + fast * 0.72
      const kneeAmp = 0.42 + fast * 1.25
      const armAmp = 0.2 + fast * 0.72
      const elbowBase = 0.3 + fast * 0.95
      const bobAmp = 0.012 + fast * 0.05

      // Legs. Positive is backwards, so the forward reach is the negative half.
      this.legs.forEach((leg, i) => {
        const ph = p1 + (i === 0 ? 0 : Math.PI)
        leg.hip.rotation.x = -Math.sin(ph) * hipAmp
        // The knee folds through the swing to clear the ground and straightens
        // as the foot reaches out to land — knees only bend one way.
        const swing = Math.max(0, Math.cos(ph))
        leg.knee.rotation.x = kneeAmp * Math.pow(swing, 1.3) + 0.1
        // Keep the foot roughly level rather than pointing wherever the shin does.
        if (leg.foot) leg.foot.rotation.x = -leg.knee.rotation.x * 0.55
        // The gait owns every axis it can be left in. A strike steps the plant
        // foot out sideways, and nothing here reset that, so a player who took
        // one shot ran bow-legged for the rest of the match.
        leg.hip.rotation.z = (i === 0 ? 1 : -1) * 0.02
      })

      // Arms drive opposite the legs, and tuck in tighter the faster you go.
      this.arms.forEach((arm, i) => {
        const ph = p1 + (i === 0 ? Math.PI : 0)
        arm.hip.rotation.x = -Math.sin(ph) * armAmp
        arm.hip.rotation.z = (i === 0 ? 1 : -1) * (0.06 + fast * 0.12)
        arm.knee.rotation.x = elbowBase + Math.max(0, Math.sin(ph)) * 0.5
      })

      // The body rises and falls twice per cycle, once for each footfall.
      const bob = -bobAmp * (0.5 - 0.5 * Math.cos(p1 * 2))
      this.root.position.y = (PLAYER.height / MODEL_HEIGHT) * 0.88 + bob
      this.spine.rootX = this.lean
      this.spine.rootZ = this.bank
      // Hips and shoulders wind against each other, as they do when you run.
      this.spine.hipsY = Math.sin(p1) * 0.1 * fast
      this.spine.torsoY = -Math.sin(p1) * 0.16 * fast
      this.spine.torsoX = fast * 0.06 * Math.cos(p1 * 2)

      this.root.rotation.x = this.spine.rootX
      this.root.rotation.z = this.spine.rootZ
      this.hips.rotation.y = this.spine.hipsY
      this.torso.rotation.y = this.spine.torsoY
      this.torso.rotation.x = this.spine.torsoX
    } else {
      // Idle: settle upright with a slow breath and a little weight shift.
      const breathe = Math.sin(this.idle * 1.6) * 0.012
      const sway = Math.sin(this.idle * 0.7) * 0.03
      this.legs.forEach((leg, i) => {
        leg.hip.rotation.x += (0 - leg.hip.rotation.x) * clamp01(dt * 9)
        leg.knee.rotation.x += (0.08 - leg.knee.rotation.x) * clamp01(dt * 9)
        leg.hip.rotation.z = (i === 0 ? 1 : -1) * 0.03
        if (leg.foot) leg.foot.rotation.x += (0 - leg.foot.rotation.x) * clamp01(dt * 9)
      })
      this.arms.forEach((arm, i) => {
        arm.hip.rotation.x += (0.06 - arm.hip.rotation.x) * clamp01(dt * 8)
        arm.hip.rotation.z = (i === 0 ? 1 : -1) * (0.1 + sway * 0.5)
        arm.knee.rotation.x += (0.22 - arm.knee.rotation.x) * clamp01(dt * 8)
      })
      // The spine settles through the rig's own state rather than by reading
      // back off the bones. A contact pose writes those bones directly, so a
      // lerp that starts from "wherever the bone is now" would be easing from a
      // value the kick put there — and any pose that adds an offset would then
      // compound it every single frame.
      this.spine.rootX += (0 - this.spine.rootX) * clamp01(dt * 8)
      this.spine.rootZ += (sway * 0.3 - this.spine.rootZ) * clamp01(dt * 5)
      this.spine.hipsY += (sway - this.spine.hipsY) * clamp01(dt * 5)
      this.spine.torsoY += (-sway * 0.6 - this.spine.torsoY) * clamp01(dt * 5)
      this.spine.torsoX += (0 - this.spine.torsoX) * clamp01(dt * 8)

      this.root.position.y = (PLAYER.height / MODEL_HEIGHT) * 0.88 + breathe
      this.root.rotation.x = this.spine.rootX
      this.root.rotation.z = this.spine.rootZ
      this.hips.rotation.y = this.spine.hipsY
      this.torso.rotation.y = this.spine.torsoY
      this.torso.rotation.x = this.spine.torsoX
    }

    // Shielding: side-on already (the sim turned the body), so what the pose
    // adds is the arm across and the weight settled back over the ball.
    if (p.shielding) this.poseShield()

    // A contact overrides the limbs involved for the duration of the movement.
    if (p.kickTimer > 0) {
      if (p.kickKind === 'header') this.poseHeader(p)
      else if (p.kickKind === 'cushion') this.poseCushion(p)
      else if (p.kickKind === 'touch') this.poseTouch(p)
      else this.poseStrike(p)
    }

    // Hold the head level and looking ahead, whatever the body is doing.
    this.head.rotation.x = -this.root.rotation.x * 0.8 - this.torso.rotation.x
    this.head.rotation.y = -this.torso.rotation.y * 0.7
    this.head.rotation.z = -this.bank * 0.5
  }

  // Progress through the current contact, 0 at the start and 1 at the end.
  private phaseOf(p: Player): number {
    return 1 - clamp01(p.kickTimer / Math.max(p.kickAnimLen, 1e-3))
  }

  // The right-click touch: a short prod with the instep to move the ball on.
  // No wind-up worth the name and no follow-through — the foot goes out, meets
  // the ball and comes straight back under you, because the whole point of the
  // touch button is that you stay balanced and ready to go again.
  private poseTouch(p: Player) {
    const t = this.phaseOf(p)
    const leg = this.legs[p.kickLeg]
    const other = this.legs[1 - p.kickLeg]
    // Out and back inside the same movement.
    const reach = Math.sin(clamp01(t) * Math.PI) * (0.34 + p.kickPower * 0.3)

    leg.hip.rotation.x = -reach
    leg.knee.rotation.x = 0.12 + reach * 0.35
    if (leg.foot) leg.foot.rotation.x = -0.12 - reach * 0.25
    // The standing leg stays under you; a touch shouldn't cost you your balance.
    other.hip.rotation.x = 0.05
    other.knee.rotation.x = 0.16
    // Barely any body in it — a hint of shoulder rotation and nothing else.
    this.torso.rotation.y = this.spine.torsoY + reach * 0.12
    this.arms[1 - p.kickLeg].hip.rotation.x = -reach * 0.2
  }

  // The left-click strike, on one continuous scale from a pushed pass to
  // everything you have. Power decides how far the leg goes back, how much the
  // trunk gets into it, how wide the plant foot goes and how long the
  // follow-through runs — so you can read the weight of a ball off the swing
  // before you see where it went.
  private poseStrike(p: Player) {
    const t = this.phaseOf(p)
    const pw = p.kickPower
    const leg = this.legs[p.kickLeg]
    const other = this.legs[1 - p.kickLeg]
    const swingArm = this.arms[1 - p.kickLeg] // opposite arm counterweights
    const freeArm = this.arms[p.kickLeg]
    const side = p.kickLeg === 0 ? 1 : -1

    // A hard strike loads for longer before it comes through.
    const load = 0.28 + pw * 0.1
    // How far back the leg is drawn, and how far past the ball it carries.
    const back = 0.3 + pw * 0.95
    const through = 0.55 + pw * 1.15
    const cock = 0.45 + pw * 1.0 // knee fold at the top of the backswing

    let hip: number
    let knee: number
    let e = 0
    if (t < load) {
      const k = t / load
      const ease = k * k * (3 - 2 * k) // smooth into the top of the backswing
      hip = ease * back
      knee = ease * cock
    } else {
      const k = (t - load) / (1 - load)
      e = 1 - Math.pow(1 - k, 3) // fast off the top, decaying through the follow
      hip = back - e * (back + through)
      // The knee snaps straight through the ball, then folds again as the leg
      // carries up — which is what makes a full strike read as a whip.
      knee = cock * (1 - e) + 0.05 + Math.max(0, e - 0.55) * pw * 1.1
    }

    leg.hip.rotation.x = hip
    leg.knee.rotation.x = Math.max(0.03, knee)
    if (leg.foot) leg.foot.rotation.x = -knee * 0.5 - 0.1

    // The plant leg: braced and, on a heavy strike, stepped out to the side to
    // open the hips up. You cannot hit through a ball with your feet together.
    const plant = pw * 0.34
    other.hip.rotation.x = 0.08 + pw * 0.22
    other.knee.rotation.x = 0.18 + pw * 0.3
    other.hip.rotation.z = -side * plant

    // Trunk: rotate away on the backswing, whip through on contact, and lean
    // back a touch as the leg comes up.
    const trunk = hip * (0.18 + pw * 0.22)
    this.torso.rotation.y = trunk
    this.hips.rotation.y = -trunk * 0.45
    this.root.rotation.x = this.lean + Math.max(0, -hip) * (0.08 + pw * 0.12)
    this.root.rotation.z = side * pw * 0.16 * e

    // Arms: the opposite one flies out for balance, harder the bigger the hit.
    swingArm.hip.rotation.x = -hip * (0.4 + pw * 0.3)
    swingArm.hip.rotation.z = -side * (0.12 + pw * 0.75)
    swingArm.knee.rotation.x = 0.35 + pw * 0.5
    freeArm.hip.rotation.z = side * (0.1 + pw * 0.35)
    freeArm.hip.rotation.x = hip * 0.25
  }

  // A header: coil back away from the ball, then snap the whole torso through
  // it. The power comes from the trunk, not the neck, so the back is what moves.
  private poseHeader(p: Player) {
    const t = this.phaseOf(p)
    let arch: number
    if (t < 0.4) {
      arch = -(t / 0.4) * 0.55 // lean back and load
    } else {
      const k = (t - 0.4) / 0.6
      arch = -0.55 + (1 - Math.pow(1 - k, 3)) * 1.0 // snap through
    }
    this.root.rotation.x = this.lean + arch
    this.torso.rotation.x = arch * 0.45
    // Arms come out for balance, the way they always do when you jump to head one.
    for (let i = 0; i < this.arms.length; i++) {
      this.arms[i].hip.rotation.x = -0.5 - arch * 0.3
      this.arms[i].hip.rotation.z = (i === 0 ? 1 : -1) * 0.55
      this.arms[i].knee.rotation.x = 0.5
    }
    // Both legs trail slightly, as if you've come off the ground for it.
    for (const leg of this.legs) {
      leg.hip.rotation.x = -0.2
      leg.knee.rotation.x = 0.45
    }
  }

  // Cushioning a dropping ball: get side-on, lift the foot or thigh to meet it
  // and withdraw as it arrives — the whole trick is taking the pace off.
  private poseCushion(p: Player) {
    const t = this.phaseOf(p)
    // Up to meet it, then down and away with the ball.
    const raise = Math.sin(clamp01(t) * Math.PI) * 0.9
    const leg = this.legs[p.kickLeg]
    const other = this.legs[1 - p.kickLeg]
    leg.hip.rotation.x = -raise
    leg.knee.rotation.x = 0.25 + raise * 0.8
    if (leg.foot) leg.foot.rotation.x = -0.3 - raise * 0.3
    other.hip.rotation.x = 0.1
    other.knee.rotation.x = 0.2
    // Open the body up and drop the near shoulder into it.
    this.torso.rotation.y = raise * 0.35
    this.arms[p.kickLeg].hip.rotation.z = (p.kickLeg === 0 ? 1 : -1) * (0.35 + raise * 0.5)
    this.arms[1 - p.kickLeg].hip.rotation.x = -raise * 0.4
    this.root.rotation.x = this.lean - raise * 0.18
  }

  // Holding someone off: near arm across their run, far shoulder dropped, knees
  // bent and the weight sat back over the ball.
  private poseShield() {
    this.arms[0].hip.rotation.z = 1.15
    this.arms[0].hip.rotation.x = -0.35
    this.arms[0].knee.rotation.x = 0.9
    this.arms[1].hip.rotation.z = -0.45
    this.arms[1].hip.rotation.x = 0.25
    this.legs[0].hip.rotation.x = Math.min(this.legs[0].hip.rotation.x, -0.1) - 0.1
    this.legs[0].knee.rotation.x = Math.max(this.legs[0].knee.rotation.x, 0.35)
    this.legs[1].knee.rotation.x = Math.max(this.legs[1].knee.rotation.x, 0.3)
    this.root.rotation.z = this.spine.rootZ - 0.18
    this.torso.rotation.y = this.spine.torsoY + 0.3
    this.root.position.y -= 0.02
  }

  // Committed and grounded: one leg extended through the ball, body low. `down`
  // is 1 while the slide is live and eases to 0 as the player climbs back to
  // their feet, so the same pose covers both the tackle and the price of it.
  private poseSlide(down: number) {
    const d = clamp01(down)
    const stand = (PLAYER.height / MODEL_HEIGHT) * 0.88
    const floor = (PLAYER.height / MODEL_HEIGHT) * 0.34
    this.root.position.y = floor + (stand - floor) * (1 - d)
    this.root.rotation.x = -0.95 * d
    this.root.rotation.z = 0.35 * d
    this.legs[0].hip.rotation.x = -1.25 * d
    this.legs[0].knee.rotation.x = 0.08 + 0.5 * (1 - d)
    this.legs[1].hip.rotation.x = 0.55 * d
    this.legs[1].knee.rotation.x = 1.15 * d + 0.1
    this.arms[0].hip.rotation.x = 0.9 * d
    this.arms[1].hip.rotation.x = -0.7 * d
    this.arms[0].knee.rotation.x = 0.4
    this.arms[1].knee.rotation.x = 0.5
    this.torso.rotation.y = 0.2 * d
    this.head.rotation.x = 0.6 * d
    // Keep the gait's spine state in step, so standing up doesn't snap.
    this.spine.rootX = this.root.rotation.x
    this.spine.rootZ = this.root.rotation.z
    this.spine.torsoY = this.torso.rotation.y
  }

  setVisible(v: boolean) {
    this.body.visible = v
  }
}

// Build the material set for a team's kit once and share it across every player.
export function makeKitMaterials(team: 'home' | 'away', role: string): Mats {
  const kit = KITS[team]
  const style = team === 'home' ? 'stripes' : 'hoops'
  const jerseyCanvas =
    role === 'GK'
      ? kitTexture(kit.gk, '#1a1d24', '#0f1116', 'hoops')
      : kitTexture(kit.primary, kit.secondary, kit.accent, style)
  const map = new THREE.CanvasTexture(jerseyCanvas)
  map.colorSpace = THREE.SRGBColorSpace
  return {
    jersey: new THREE.MeshStandardMaterial({ map, roughness: 0.68 }),
    shorts: new THREE.MeshStandardMaterial({ color: role === 'GK' ? '#1a1d24' : kit.accent, roughness: 0.72 }),
    socks: new THREE.MeshStandardMaterial({ color: role === 'GK' ? '#22262e' : kit.secondary, roughness: 0.82 }),
    skin: new THREE.MeshStandardMaterial({ color: '#e5b08a', roughness: 0.85 }),
    boot: new THREE.MeshStandardMaterial({ color: '#14161c', roughness: 0.35, metalness: 0.1 }),
    hair: new THREE.MeshStandardMaterial({ color: '#2c211b', roughness: 0.95 }),
  }
}
