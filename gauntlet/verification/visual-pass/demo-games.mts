// Three tiny demo games for mock mode's "Build a Game in One Shot" results (written by hand for the demo, not by
// a model). record-mock.mts runs the real headless-browser probe on them, so the checks and screenshots are real.

const SHELL = (title: string, body: string) =>
  `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title><style>html,body{margin:0;height:100%;background:#0b1020;overflow:hidden;font-family:system-ui,sans-serif}canvas{display:block;width:100vw;height:100vh}</style></head><body><canvas id="c"></canvas><script>${body}</script></body></html>`;

const EMBER = `
const c=document.getElementById('c'),x=c.getContext('2d');let W,H;function fit(){W=c.width=innerWidth;H=c.height=innerHeight}fit();addEventListener('resize',fit);
let s={y:H/2,v:0,heat:40,score:0,best:0,pipes:[],t:0,state:'start',parts:[]};
function flap(){if(s.state!=='play'){s={...s,y:H/2,v:0,heat:40,score:0,pipes:[],state:'play'};return}s.v=-7;s.heat=Math.max(0,s.heat-6)}
addEventListener('keydown',e=>{if(e.code==='Space'||e.code==='ArrowUp'){e.preventDefault();flap()}});addEventListener('pointerdown',flap);
let last=performance.now();function loop(now){const dt=Math.min(40,now-last)/16.7;last=now;s.t+=dt;
if(s.state==='play'){s.v+=0.38*dt;s.y+=s.v*dt;s.heat=Math.min(100,s.heat+0.12*dt);if(s.t%90<dt)s.pipes.push({x:W,gap:120+Math.random()*(H-300),p:false});
for(const p of s.pipes){p.x-=3.2*dt;if(!p.p&&p.x<W*0.3){p.p=true;s.score++}if(p.x<W*0.3+18&&p.x+70>W*0.3-18&&(s.y<p.gap||s.y>p.gap+170))s.state='over'}
s.pipes=s.pipes.filter(p=>p.x>-80);if(s.y>H||s.y<0||s.heat>=100)s.state='over';s.best=Math.max(s.best,s.score)}
for(let i=0;i<3;i++)s.parts.push({x:W*0.3-14,y:s.y+Math.random()*10-5,vx:-2-Math.random()*2,vy:Math.random()*2-1,l:1});
const g=x.createLinearGradient(0,0,0,H);g.addColorStop(0,'#1a0f2e');g.addColorStop(1,'#3b1d1d');x.fillStyle=g;x.fillRect(0,0,W,H);
x.fillStyle='#4a5568';for(const p of s.pipes){x.fillRect(p.x,0,70,p.gap);x.fillRect(p.x,p.gap+170,70,H)}
for(const p of s.parts){p.x+=p.vx*dt;p.y+=p.vy*dt;p.l-=0.03*dt;x.fillStyle='rgba(255,'+Math.round(120+p.l*100)+',40,'+Math.max(0,p.l)+')';x.beginPath();x.arc(p.x,p.y,6*p.l+1,0,7);x.fill()}s.parts=s.parts.filter(p=>p.l>0);
x.fillStyle='#ffb347';x.beginPath();x.arc(W*0.3,s.y,16,0,7);x.fill();x.fillStyle='#fff';x.beginPath();x.arc(W*0.3+6,s.y-5,4,0,7);x.fill();
x.fillStyle='#222';x.fillRect(20,20,220,18);x.fillStyle=s.heat>75?'#ff4d4d':'#ffa726';x.fillRect(20,20,2.2*s.heat,18);x.fillStyle='#fff';x.font='bold 14px system-ui';x.fillText('HEAT',250,34);
x.font='bold 36px system-ui';x.textAlign='center';x.fillText(s.score,W/2,70);x.font='16px system-ui';x.fillText('best '+s.best,W/2,95);
if(s.state!=='play'){x.font='bold 42px system-ui';x.fillText(s.state==='start'?'EMBER WING':'GAME OVER',W/2,H/2-20);x.font='20px system-ui';x.fillText('Space / click to '+(s.state==='start'?'start':'restart'),W/2,H/2+20)}x.textAlign='left';
requestAnimationFrame(loop)}requestAnimationFrame(loop);`;

const KICK = `
const c=document.getElementById('c'),x=c.getContext('2d');let W,H;function fit(){W=c.width=innerWidth;H=c.height=innerHeight}fit();addEventListener('resize',fit);
let aim=0,power=0,holding=false,ball=null,keeper=0,kv=2,score=0,shots=0,best=0,msg='Aim with ← →, hold Space for power';
function shoot(){if(ball)return;ball={x:W/2,y:H*0.72,vx:Math.sin(aim)*power*0.16,vy:-Math.cos(aim)*power*0.16};shots++;power=0}
addEventListener('keydown',e=>{if(e.code==='ArrowLeft')aim=Math.max(-0.7,aim-0.08);if(e.code==='ArrowRight')aim=Math.min(0.7,aim+0.08);if(e.code==='Space'){e.preventDefault();holding=true}});
addEventListener('keyup',e=>{if(e.code==='Space'){holding=false;shoot()}});addEventListener('pointerdown',e=>{holding=true;aim=Math.max(-0.7,Math.min(0.7,(e.clientX-W/2)/W*2))});addEventListener('pointerup',()=>{holding=false;shoot()});
let last=performance.now();function loop(now){const dt=Math.min(40,now-last)/16.7;last=now;if(holding)power=Math.min(100,power+1.6*dt);
keeper+=kv*dt;if(Math.abs(keeper)>W*0.12)kv=-kv;
if(ball){ball.x+=ball.vx*dt;ball.y+=ball.vy*dt;if(ball.y<H*0.2){const inGoal=Math.abs(ball.x-W/2)<W*0.16,saved=Math.abs(ball.x-(W/2+keeper))<40;if(inGoal&&!saved){score++;msg='GOAL!'}else msg=saved?'Saved!':'Wide!';best=Math.max(best,score);ball=null}}
x.fillStyle='#1f7a3a';x.fillRect(0,0,W,H);x.strokeStyle='#e8f5e9';x.lineWidth=4;x.strokeRect(W/2-W*0.3,H*0.12,W*0.6,H*0.4);x.strokeRect(W/2-W*0.16,H*0.12,W*0.32,H*0.1);
x.fillStyle='#fff';x.fillRect(W/2-W*0.16,H*0.1,W*0.32,8);x.fillStyle='#ffd54f';x.fillRect(W/2+keeper-35,H*0.18,70,18);
const bx=ball?ball.x:W/2,by=ball?ball.y:H*0.72;x.fillStyle='#fff';x.beginPath();x.arc(bx,by,11,0,7);x.fill();
if(!ball){x.strokeStyle='rgba(255,255,255,.7)';x.setLineDash([8,8]);x.beginPath();x.moveTo(W/2,H*0.72);x.lineTo(W/2+Math.sin(aim)*160,H*0.72-Math.cos(aim)*160);x.stroke();x.setLineDash([])}
x.fillStyle='#111';x.fillRect(20,H-40,200,16);x.fillStyle='#ff7043';x.fillRect(20,H-40,2*power,16);
x.fillStyle='#fff';x.font='bold 28px system-ui';x.fillText('Goals '+score+' / '+shots+'   best '+best,20,40);x.font='18px system-ui';x.fillText(msg,20,70);
requestAnimationFrame(loop)}requestAnimationFrame(loop);`;

const SNAKE = `
const c=document.getElementById('c'),x=c.getContext('2d');let W,H;function fit(){W=c.width=innerWidth;H=c.height=innerHeight}fit();addEventListener('resize',fit);
const N=24,M=16;let snake=[[6,8],[5,8],[4,8]],dir=[1,0],q=[],food=[15,5],score=0,best=0,acc=0,over=false;const portals=[[10,3],[18,12]];
addEventListener('keydown',e=>{const m={ArrowUp:[0,-1],ArrowDown:[0,1],ArrowLeft:[-1,0],ArrowRight:[1,0],KeyW:[0,-1],KeyS:[0,1],KeyA:[-1,0],KeyD:[1,0]}[e.code];if(m&&q.length<2)q.push(m);if(over&&e.code==='Space'){snake=[[6,8],[5,8],[4,8]];dir=[1,0];score=0;over=false}});
let last=performance.now();function loop(now){acc+=now-last;last=now;const step=Math.max(60,140-score*4);
while(acc>step&&!over){acc-=step;const n=q.shift();if(n&&(n[0]!==-dir[0]||n[1]!==-dir[1]))dir=n;let h=[(snake[0][0]+dir[0]+N)%N,(snake[0][1]+dir[1]+M)%M];
portals.forEach((p,i)=>{if(h[0]===p[0]&&h[1]===p[1]){const o=portals[1-i];h=[(o[0]+dir[0]+N)%N,(o[1]+dir[1]+M)%M]}});
if(snake.some(s=>s[0]===h[0]&&s[1]===h[1])){over=true;break}snake.unshift(h);if(h[0]===food[0]&&h[1]===food[1]){score++;best=Math.max(best,score);food=[(food[0]*7+3)%N,(food[1]*5+2)%M]}else snake.pop()}
const cw=W/N,ch=(H-50)/M;x.fillStyle='#101a2e';x.fillRect(0,0,W,H);x.fillStyle='#16223b';for(let i=0;i<N;i++)for(let j=0;j<M;j++)if((i+j)%2)x.fillRect(i*cw,50+j*ch,cw,ch);
portals.forEach((p,i)=>{x.strokeStyle=i?'#f472b6':'#38bdf8';x.lineWidth=4;x.beginPath();x.arc((p[0]+.5)*cw,50+(p[1]+.5)*ch,Math.min(cw,ch)*.4,0,7);x.stroke()});
x.fillStyle='#fbbf24';x.beginPath();x.arc((food[0]+.5)*cw,50+(food[1]+.5)*ch,Math.min(cw,ch)*.35,0,7);x.fill();
snake.forEach((s,i)=>{x.fillStyle=i?'#34d399':'#a7f3d0';x.fillRect(s[0]*cw+2,50+s[1]*ch+2,cw-4,ch-4)});
x.fillStyle='#fff';x.font='bold 24px system-ui';x.fillText('Portal Snake   score '+score+'   best '+best,16,34);if(over){x.textAlign='center';x.font='bold 44px system-ui';x.fillText('GAME OVER · Space to restart',W/2,H/2);x.textAlign='left'}
requestAnimationFrame(loop)}requestAnimationFrame(loop);`;

export const DEMO_GAMES: Record<string, { title: string; js: string }> = {
  g01: { title: 'Ember Wing', js: EMBER },
  g02: { title: 'Spot Kick', js: KICK },
  g03: { title: 'Portal Snake', js: SNAKE },
};

/** A game as a model with the given flaw would ship it. */
export function demoGame(caseId: string, flaw: 'none' | 'crash-on-key' | 'no-keys'): string {
  const g = DEMO_GAMES[caseId]!;
  let js = g.js;
  if (flaw === 'crash-on-key') js = js.replace("addEventListener('keydown',e=>{", "addEventListener('keydown',e=>{undefinedHandler(e);");
  if (flaw === 'no-keys') js = js.replace(/addEventListener\('keydown'/g, "addEventListener('keydownx'").replace(/addEventListener\('pointerdown'/g, "addEventListener('pointerdownx'");
  return SHELL(g.title, js);
}
