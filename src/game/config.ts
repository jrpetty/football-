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
  // Bounce. Restitution is not a constant in a real ball: the harder it hits,
  // the further it deforms and the more it loses, which is exactly why a driven
  // shot skids off the turf while the same ball dropped gently sits up. So it's
  // a line falling with impact speed rather than one number.
  //   e(v) = restitution − restitutionFalloff·v, floored at restitutionMin
  // At a 2 m drop (6.3 m/s) that gives ~0.69, a 0.94 m rebound — the real figure
  // for grass. At 20 m/s it gives 0.44, so hard balls stay down.
  restitution: 0.8,
  restitutionFalloff: 0.018,
  restitutionMin: 0.38,
  // Coulomb friction between ball and turf, which is what converts sliding into
  // rolling and what lets spin change where a bounce goes. Real grass is ~0.4-0.6.
  turfFriction: 0.5,
  // How much side-spin drags the ball sideways as it lands. Bounded by the same
  // friction budget as everything else at the contact patch.
  bounceSideKick: 0.09,
  settleBounce: 0.9, // below this vertical rebound, settle into a roll
  // How fast spin bleeds off in flight. This was 0.85/s, which took three
  // quarters of the spin off a ball during a 1.6 s flight — so a curler stopped
  // curling right when a real one is biting hardest. A real football sheds very
  // little over the couple of seconds it is in the air.
  spinDecay: 0.12,
  // Magnus, sideways. Identical physics to the vertical case below — the air
  // does not care which axis a ball is spinning about — so it is the same
  // constant applied to the side-spin, and `spin` is carried in the same unit:
  // the speed of the ball's surface at its equator, in m/s. This used to be an
  // arbitrary number against an arbitrary spin scale, which is how the curve
  // ended up bending a whipped cross about half as far again as a real one.
  magnus: 0.045,
  groundMagnus: 0.55, // fraction of the bend that survives while rolling
  // Vertical-plane Magnus: topspin pushes the ball down, backspin holds it up.
  // Same speed-scaled form as the sideways bend.
  // Derived rather than guessed: a = ½ρA·Cl·v²/m with Cl ≈ 0.9·S, where S is the
  // spin ratio (surface speed ÷ travel speed). Since vSpin is carried as that
  // surface speed, the whole thing collapses to magnusVertical · vSpin · v.
  magnusVertical: 0.045,
  // Cap on side-spin, as surface speed. 12 m/s against a 34 m/s ball is a spin
  // ratio of 0.35 — beyond anything a human has actually hit, which is the
  // point: it's a ceiling, not a target.
  maxSpin: 12,
  // vSpin is carried as the speed of the ball's own surface at the contact
  // patch, in m/s, signed so positive is topspin. Keeping it in the same unit as
  // the ball's travel is what makes the bounce a subtraction rather than a fudge
  // factor: a ball rolling at 8 m/s has vSpin 8 and no slip at all.
  maxVSpin: 45,
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
  // Momentum, and the whole feel of the game. These were arcade numbers (34
  // m/s², full sprint in a fifth of a second, stopping inside a metre); a real
  // footballer needs a second and a half to wind up and three or four metres to
  // pull up. Three separate budgets, because a body doesn't change velocity
  // equally in every direction:
  //   accel   — driving forward. Falls away as you approach your top speed,
  //             since most of your effort goes into holding the pace you have.
  //   brake   — planting to slow down, which you can do harder than you can
  //             accelerate: two feet against the ground instead of one.
  //   lateral — pushing sideways out of a stride. This is what stops a run at
  //             pace from turning on a pin; you arc instead.
  accel: 8.2,
  brake: 9.5,
  lateral: 11,
  turnRate: 8, // rad/s the heading can slew (momentum on direction changes)
  staminaMax: 100,
  // Stamina. Flat-out sprinting used to empty the tank in seven seconds, which
  // meant the sprint key was a trap rather than a decision. A footballer can
  // repeat hard sprints for the better part of half a minute before it really
  // tells, and takes a good while jogging to get it back.
  sprintDrain: 5.5, // stamina/s while sprinting
  pressDrain: 6, // extra stamina/s while actively pressing
  slideDrain: 14, // one-off cost of a slide tackle
  staminaRegen: 5.5, // stamina/s recovered when not sprinting
  tiredFactor: 0.72, // speed multiplier when fully gassed
  // How long each contact takes to play out. A prod with the instep is over
  // almost before you see it; a full-blooded strike is a wind-up, a plant and a
  // long follow-through, and it should read as taking real time to deliver.
  touchAnimTime: 0.2,
  strikeAnimMin: 0.26, // a rolled short pass
  strikeAnimMax: 0.46, // everything you have
  headerAnimTime: 0.36,
  cushionAnimTime: 0.32,
  // The ball is only "at your feet" below this height; above it you are playing
  // it out of the air instead of dribbling it.
  controlHeight: 0.62,
  // How high you can still reach the ball — knee, thigh, chest, and finally a
  // header at full stretch. Beyond this it sails over you.
  aerialReach: 2.25,
  headerHeight: 1.5, // above this you're heading it rather than using a foot
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
  // How steep a full upward flick gets, which depends on how hard you hit it —
  // because it does in life. Height comes from getting under the ball, and you
  // cannot scoop something and hammer it at the same time: a delicate chip lifts
  // steeply (41°) and a full-blooded long ball is struck through and stays flat
  // (13°). Tuned at the top end so full power with a full flick, struck from
  // your own goal line, crosses the far goal line right around the top of the
  // cage — the brief for the biggest ball in the game.
  loftAngleSoft: 0.72,
  loftAngleHard: 0.195,
  // A struck ball rides up the boot: even a "flat" shot leaves the ground for a
  // while. Without this the neutral strike was a pure grubber, which made the
  // whole downward half of the flick range do literally nothing — the HUD said
  // DRIVEN and the trajectory was byte-identical. Now a full down-flick takes
  // this away and puts topspin on instead, so keeping a hard ball on the deck
  // is a thing you do rather than the default. Scaled by power, so a rolled
  // pass still rolls. 5° at full power.
  naturalLoft: 0.088,
  // Striking technique follows from the same flick: coming over the top of the
  // ball drives it down with topspin, getting under it lifts it with backspin.
  // Both are expressed as a fraction of the ball's own speed, because that's
  // what spin physically is once it reaches the turf — the speed of the surface
  // against the speed of travel. Hit a ball harder and you put proportionally
  // more on it, and a driven ball arrives already near rolling, so it skids on
  // instead of pulling up.
  topspinFromLoft: 0.8, // full down-flick: surface at 80% of travel
  backspinFromLoft: 0.32, // full up-flick, before the power taper below
  // Below which loft the ball is simply driven along the floor. A flick doesn't
  // have to be perfect to keep the ball down — this is a pass you should be able
  // to play at pace, not a trick shot.
  driveLoft: -0.4,
  // A driven ball is struck through the middle rather than under it, so more of
  // the same effort ends up as pace.
  driveBonus: 0.06,
  // How hard you must flick during a touch before it counts as a skill move
  // rather than ordinary aiming drift (pixels).
  skillFlickMin: 105,
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
  // Side-spin from a full sideways flick, as a fraction of the ball's own speed
  // — the same way technique spin works vertically, and the same way spin
  // actually behaves. Tuned against the real benchmark rather than by feel: a
  // curled strike should deviate 3-5 m over the first 25 m of flight, which is
  // what a free kick from the edge of the box does.
  sideSpin: 0.15,
  // Striking across your own body puts a little on it whether you asked for it
  // or not, as a fraction of the deliberate amount.
  sideSpinAcrossBody: 0.35,
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

// Playing the ball out of the air. Cushioning kills the pace and drops it dead;
// volleying and heading trade control for power, which is the whole bargain.
export const AERIAL = {
  cushionKeep: 0.14, // fraction of incoming pace a cushioned touch leaves on
  cushionError: 0.34, // metres of scatter, scaled by how hard it arrived
  // Volleys come off harder than a grounded strike — you're adding the ball's
  // own pace to your own. Kept just under a real-world rocket (~38 m/s) so the
  // hardest contact in the game is still a football rather than a bullet.
  volleyBonus: 1.12,
  volleySpread: 3.4, // ...and far less accurately (degrees at full power)
  headerPower: 13, // base speed off the head
  headerRunBonus: 0.85, // how much of your run speed carries into a header
  headerSpread: 4.5, // headers are the least precise contact of all
  // Headers get their own launch angle rather than borrowing the strike's,
  // because nodding a ball is a different act from kicking one. Neutral is a
  // looping 17°; flick up and you can loop it right over a keeper, flick down
  // and you steer it into the turf — the header everyone actually wants.
  headerAngle: 0.3,
  headerAngleRange: 0.45, // how far the flick swings that angle either way
}

// Defending. There is no tackle button: you win the ball with the same two
// clicks you use for everything else — right click to take it off someone and
// keep it, left click to hammer it clear. The only dedicated defensive move is
// the slide, and that one is all precision.
// Shielding. Hold it and you turn side-on with the ball behind you: the oldest
// piece of close control there is, and the one thing a game with nothing glued
// to anybody genuinely needs. There is no magnet here and nothing is attached —
// what changes is your *body*. You go side-on, you slow to a shuffle, you can't
// strike, and your body becomes a soft, wide surface the ball dies against
// instead of the hard one it pings off. Keeping it is then purely a matter of
// where you stand.
export const SHIELD = {
  speed: 2.4, // you are shuffling, not running
  // Side-on with an arm across, so you cover more ground than usual. This is
  // the whole mechanic: a bigger body, in the right place.
  bodyRadius: 0.66,
  keep: 0.12, // pace the ball retains off a shielding body — it deadens
  carry: 0.85, // ...and takes your movement with it instead
  turnRate: 5.5, // rad/s you can pivot while shielding — deliberately slow
  // An opponent cannot play a ball your body is in front of. Measured as how
  // far their line to the ball passes from your centre, so it is geometry and
  // nothing else: step aside and they have it.
  block: 0.62,
}

export const DEFEND = {
  // The sliding body is the hitbox and nothing more — no magnet, no assist. It's
  // a capsule the width of the player, as long as a person lying down, swept
  // along the ground. Get it right and you take the ball; get it wrong and
  // you're on the floor while they go past you.
  slideBodyLength: 1.35,
  slideBallHeight: 0.7, // you can't slide-tackle a ball above your grounded body
  slideBoost: 3.2, // extra pace the launch gives you over your running speed
  slideTime: 0.55, // how long you're actually sliding
  slideRecovery: 0.85, // seconds grounded and beaten after the slide ends
  slideWonRecovery: 0.55, // ...cut to this fraction if you actually won the ball
  slideClear: 15, // how hard a won slide knocks the ball away
  slideCooldown: 1.1, // you can't spam it
  interceptRadius: 1.1, // AI: moving into this radius of a loose ball claims it
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
