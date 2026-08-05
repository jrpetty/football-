// Central tuning. Every number that shapes how the game *feels* lives here so
// balancing is one file, not a scavenger hunt. Distances are metres, time is
// seconds, angles are radians — the render layer converts metres → pixels.

export const SIM = {
  dt: 1 / 120, // fixed physics step (s). We integrate at 120Hz for stable bounces.
  gravity: 9.81, // m/s² — real gravity; the flight shape comes from drag, not fudged weight
  // Largest frame delta we'll simulate. Anything longer is treated as a stall and
  // the excess is dropped, so the physics can't explode after a hitch — but keep
  // it generous enough that even a machine struggling at ~4 fps still plays at
  // true speed instead of sliding into slow motion.
  maxFrameDt: 0.26,
  maxStepsPerFrame: 32, // catch-up ceiling (32 x 1/120s ≈ 266ms), so a hitch can't spiral
}

// Pitch is a compact, arena-style small-sided field (think 3v3/6v6), which keeps
// the action dense and the physics legible.
export const FIELD = {
  length: 58, // x-axis, goal to goal
  width: 38, // y-axis, touchline to touchline
  margin: 6, // run-off around the pitch that the camera can see
  goalWidth: 7.2, // mouth width (along y)
  goalHeight: 2.6, // crossbar height (z)
  goalDepth: 2.2, // how deep the net sits behind the line
  centerRadius: 6,
  boxDepth: 12, // penalty area depth from the goal line
  boxWidth: 24, // penalty area width
  cornerRadius: 0.9,
}

// A real size-5 football: 22cm across, 430g. Flight is governed by quadratic
// aerodynamic drag rather than flat damping, which is what makes a driven ball
// hold its line while a floated one dies into its landing.
export const BALL = {
  radius: 0.11, // m — matches a real ball; the old 0.34 was a beach ball
  mass: 0.43, // kg
  // Quadratic drag constant k in a = -k|v|v, folding in air density, drag
  // coefficient, frontal area and mass. ~0.0135 is the textbook value for a
  // football; trimmed slightly so long balls still carry on an arena pitch.
  dragK: 0.0098,
  // Rolling resistance on grass, as a near-constant deceleration. Tuned for an
  // arena pitch: a firm 22 m/s pass dies after ~50m rather than rolling forever,
  // which is what made the ball feel floaty and unresponsive.
  rollFriction: 3.0,
  restitution: 0.56, // vertical bounciness off turf
  bounceGrip: 0.78, // horizontal pace retained through a bounce
  settleBounce: 0.9, // below this vertical rebound, settle into a roll
  spinDecay: 0.85, // how fast spin bleeds off per second
  // Magnus: sideways acceleration = magnus * spin * speed. Speed-scaled, so a
  // firm strike bends hard and a dying ball straightens out.
  // Sideways acceleration = magnus * spin * speed. Speed- and time-scaled, so
  // the bend really shows on the big stuff — a full-flick long ball swings ~5m
  // across the pitch — while a short driven pass stays honest.
  magnus: 0.021,
  groundMagnus: 0.55, // fraction of the bend that survives while rolling
  // Vertical-plane Magnus: topspin pushes the ball down, backspin holds it up.
  // Same speed-scaled form as the sideways bend.
  magnusVertical: 0.042,
  // How much vertical spin the turf converts into pace on a bounce — topspin
  // skids the ball on, backspin checks it back.
  bounceSpinBite: 0.42,
  maxSpin: 26,
  maxSpeed: 42, // hard cap so a mishit can't launch to the moon
  settleSpeed: 0.25, // below this it's treated as coming to rest
}

export const PLAYER = {
  // Real human scale. A footballer is ~1.8m tall and roughly 0.45m across the
  // shoulders; the body radius is a little wider than that for comfortable
  // personal space, but nothing like the 0.62 (a 1.24m-wide person) it was —
  // that is what made the ball look small and everything else chunky.
  height: 1.8,
  radius: 0.4,
  reach: 1.5, // how far in front the ball can be while still "controlled"
  walkSpeed: 3.4,
  runSpeed: 6.0,
  sprintSpeed: 8.4,
  accel: 34, // m/s² — responsive but momentum still matters
  decel: 28,
  turnRate: 11, // rad/s the heading can slew (momentum on direction changes)
  staminaMax: 100,
  sprintDrain: 14, // stamina/s while sprinting
  pressDrain: 9, // extra stamina/s while actively pressing
  slideDrain: 20, // one-off cost of a slide tackle
  staminaRegen: 8, // stamina/s recovered when not sprinting
  tiredFactor: 0.72, // speed multiplier when fully gassed
}

// Mouse-driven striking, modelled on Pro Soccer Online: power comes from how
// long you hold the button, and the *flick* of the mouse in the final moments of
// the charge decides height and curve. Flick up to lift it, down to drive it
// along the ground, sideways to bend it. Over-flick and you'll skin it.
export const CONTROL = {
  strikeCharge: 0.95, // seconds for the strike (left) bar to fill
  touchCharge: 0.5, // seconds for the touch (right) bar to fill — deliberately quicker
  // Only mouse motion in this final slice of the charge counts as the flick.
  // Kept forgiving enough that a flick still registers in full on a machine
  // running at a low frame rate, where the motion spans several frames.
  flickWindow: 0.24,
  flickRef: 190, // pixels of flick that equal a "full" input before sensitivity
  heightSensitivity: 1.6, // Kick Height Sensitivity — scales upward flick → loft
  curveSensitivity: 1.15, // Kick Curve Sensitivity — scales sideways flick → spin
  // Launch angle at a full upward flick. Tuned so a full-power full-flick ball
  // travels the length of the pitch and arrives around crossbar height at the
  // far goal — a proper long ball, not a punt into orbit.
  // 22°. Measured end-to-end, and re-tuned once lofted balls started carrying
  // real backspin: struck at full power from your own goal line the ball crosses
  // the far goal line right around the top of the cage, with an ~8m apex.
  maxLoftAngle: 0.386,
  // Striking technique follows from the same flick: coming over the top of the
  // ball drives it down with topspin, getting under it lifts it with backspin.
  // So a down-flick genuinely dips and an up-flick genuinely floats.
  spinFromLoft: 9,
}

// Kick model. Power (0..1) scales between min and max release speed. Passing,
// shooting and clearances share the model but differ in ceilings and loft.
export const KICK = {
  passMin: 9,
  passMax: 26,
  shotMin: 16,
  shotMax: 34,
  // A human "strike" is one continuous scale from a rolled pass to a rocket —
  // there is no separate pass/shoot button, exactly as in PSO.
  strikeMin: 7.5,
  strikeMax: 34,
  // A close-control touch: nudge the ball ahead/aside to keep it under control.
  touchMin: 3.5,
  touchMax: 13,
  throughBias: 1.06, // through balls carry a touch more weight
  loftAngle: 0.62, // launch pitch (rad) for a full lofted ball
  chipAngle: 0.9, // steeper, softer chip over a keeper
  // Spin imparted at a full sideways flick. Kept modest: with the speed-scaled
  // Magnus term this still bends a firm strike a few metres, which is about what
  // a real curled ball does over this distance.
  maxSpinFromAim: 7,
  firstTouchError: 0.12, // base positional error on a first touch (m), scaled by pace
  // Touch distances are tuned to stay INSIDE the control reach — a sprinting
  // touch pushes the ball near the edge (riskier) but keepable, not lost.
  dribbleTouch: 0.65, // base distance the ball is pushed ahead when dribbling
  sprintTouchBonus: 0.55, // heavier touch (further ahead) while sprinting = more risk
  chargeRate: 1.35, // how fast the power meter fills per second held
}

// The pitch is enclosed, arena-style: no throw-ins, no corners, no goal kicks.
// The ball simply rebounds off the boards and stays live, so play never stops.
export const WALL = {
  restitution: 0.66, // how much pace survives a rebound
  friction: 0.94, // pace kept along the wall
  height: 6, // how tall the barrier is drawn; physically it always rebounds
}

export const DEFEND = {
  tackleRange: 1.7,
  tackleWindow: 0.28, // timing window (s) where a standing tackle wins cleanly
  slideRange: 3.0,
  slideRecovery: 0.9, // seconds the slider is grounded/exposed after a slide
  interceptRadius: 1.1, // moving into this radius of a loose/passed ball claims it
  foulChance: 0.28, // mistimed challenges can concede a free kick
}

export const GK = {
  reach: 2.6, // dive reach beyond the keeper's body
  diveSpeed: 13,
  speed: 6.5,
  rushSpeed: 7.4,
  lineDepth: 1.4, // resting distance off the goal line
  catchPower: 24, // shots slower than this can be caught cleanly (else parried)
  reactionError: 0.18, // fraction of shots that beat a well-positioned keeper
}

export const MATCH = {
  defaultHalfLength: 120, // seconds per half (arena-paced)
  kickoffDelay: 1.2,
  afterGoalDelay: 2.4,
  restartDelay: 0.8,
}

// Two team liveries. Purely cosmetic — no attribute differences (no pay-to-win).
export interface Kit {
  name: string
  primary: string
  secondary: string
  accent: string
  gk: string
}

export const KITS: Record<'home' | 'away', Kit> = {
  home: { name: 'Riverside', primary: '#2f7df6', secondary: '#eaf1ff', accent: '#0b2d63', gk: '#f6c945' },
  away: { name: 'Lakeside', primary: '#ec4d4d', secondary: '#2a0d0d', accent: '#7a1414', gk: '#33d6a6' },
}
