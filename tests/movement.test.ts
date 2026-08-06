// Movement audit: top speeds, acceleration, braking and turning, each next to
// what a real footballer does. Momentum is the whole feel of the game, so it
// gets measured rather than eyeballed.
import { World } from '../src/game/match/world'
import { emptyCommand } from '../src/game/types'
import { SIM, FIELD, PLAYER } from '../src/game/config'
;(globalThis as unknown as { window: unknown }).window = globalThis
const cfg = { teamSize:1, halfLength:6000, mode:'training' as const, singleKeeper:false, view:'3d' as const, position:'MID' as const, heightSens:1.6, curveSens:1.15, humanControlled:true }

console.log('player movement:')
for (const [label, sprint, walk] of [['walk',false,true],['run',false,false],['sprint',true,false]] as const) {
  const w = new World(cfg)
  const p = w.getControlledPlayer()!
  p.x = 5; p.y = FIELD.width/2; p.vx = 0; p.vy = 0; p.heading = 0
  let t = 0, t90 = 0, top = 0
  const target = walk ? PLAYER.walkSpeed : sprint ? PLAYER.sprintSpeed : PLAYER.runSpeed
  for (let i = 0; i < 120*6; i++) {
    const c = emptyCommand(); c.move = {x:1,y:0}; c.aim = {x:1,y:0}; c.sprint = sprint; c.walk = walk
    w.update(SIM.dt, c); t += SIM.dt
    top = Math.max(top, p.speed)
    if (!t90 && p.speed >= target*0.9) t90 = t
  }
  console.log(`  ${label.padEnd(7)} top ${top.toFixed(2)} m/s, reached 90% in ${t90.toFixed(2)}s   (a real player takes ~1.5-2.5s to hit top speed)`)
}
// Stop distance from a sprint.
{
  const w = new World(cfg); const p = w.getControlledPlayer()!
  p.x = 5; p.y = FIELD.width/2; p.heading = 0
  for (let i=0;i<120*4;i++){const c=emptyCommand();c.move={x:1,y:0};c.aim={x:1,y:0};c.sprint=true;w.update(SIM.dt,c)}
  const x0 = p.x; let t=0
  for (let i=0;i<120*4;i++){const c=emptyCommand();c.aim={x:1,y:0};w.update(SIM.dt,c);t+=SIM.dt;if(p.speed<0.2)break}
  console.log(`  stopping from a sprint: ${(p.x-x0).toFixed(2)} m in ${t.toFixed(2)}s   (a real player needs ~3-5 m)`)
}
// 180 turn.
{
  const w = new World(cfg); const p = w.getControlledPlayer()!
  p.x = 20; p.y = FIELD.width/2; p.heading = 0
  for (let i=0;i<120*4;i++){const c=emptyCommand();c.move={x:1,y:0};c.aim={x:1,y:0};c.sprint=true;w.update(SIM.dt,c)}
  let t=0
  for (let i=0;i<120*4;i++){const c=emptyCommand();c.move={x:-1,y:0};c.aim={x:-1,y:0};c.sprint=true;w.update(SIM.dt,c);t+=SIM.dt;if(p.vx<-PLAYER.sprintSpeed*0.8)break}
  console.log(`  full sprint 180: ${t.toFixed(2)}s to be running back the other way   (real: ~1.2-1.8s)`)
}
