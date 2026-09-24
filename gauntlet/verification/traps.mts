import fs from 'node:fs';
import { runCodeTests } from '../src/scoring/code-sandbox.ts';
const T = (f: string) => JSON.parse(fs.readFileSync(new URL('../tests/' + f, import.meta.url), 'utf8'));
const alg = T('coding/algorithms.json'), dbg = T('coding/debug-and-edge-cases.json'), hard = T('coding/hard.json');
const get = (t: any, id: string) => t.cases.find((c: any) => c.id === id).expected;
async function run(label: string, code: string, exp: any) {
  const r = await runCodeTests(code, exp.functionName, exp.tests, 2000);
  const fails = r.items.map((x, i) => (x.passed ? null : `#${i + 1}:${(x.detail ?? '').slice(0, 40)}`)).filter(Boolean);
  console.log(`${label}: ${r.passed}/${r.total}${r.loadError ? ' LOAD ' + r.loadError : ''}  ${fails.slice(0, 4).join(' | ')}`);
}
await run('A5 brute (recompute per cut)', fs.readFileSync('coding/alg/a5/brute.js', 'utf8'), get(alg, 'a5'));
await run('A6 brute O(n^2)', fs.readFileSync('coding/alg/a6/brute.js', 'utf8'), get(alg, 'a6'));
await run('A4 memo recursion', `function countSplits(d, lim){ const M=1000000007, L=String(lim).length, memo=new Map(); function go(i){ if(i===d.length) return 1; if(memo.has(i)) return memo.get(i); let t=0, v=0; if(d[i]!=='0') for(let j=i;j<d.length&&j-i<L;j++){ v=v*10+(d.charCodeAt(j)-48); if(v>lim) break; t=(t+go(j+1))%M; } memo.set(i,t); return t; } return go(0); }`, get(alg, 'a4'));
// recursive-descent evaluator for H1 (correct semantics, but recursive)
const rd = `function evaluate(s){ let i=0; const t=[]; for(let k=0;k<s.length;){ const c=s[k]; if(c===' '||c==='\\t'){k++;continue;} if(/[0-9]/.test(c)){ const m=/^\\d+(\\.\\d+)?/.exec(s.slice(k)); if(s[k+m[0].length]==='.') return null; t.push(+m[0]); k+=m[0].length; continue;} if('+-*/%^()'.includes(c)){t.push(c);k++;continue;} return null; }
 if(!t.length) return null; let p=0; const E=()=>{ let v=T(); while(t[p]==='+'||t[p]==='-'){ const o=t[p++]; const r=T(); v=o==='+'?v+r:v-r;} return v;};
 const T=()=>{ let v=U(); while(t[p]==='*'||t[p]==='/'||t[p]==='%'){ const o=t[p++]; const r=U(); if((o==='/'||o==='%')&&r===0) throw 0; v=o==='*'?v*r:o==='/'?v/r:v%r;} return v;};
 const U=()=>{ if(t[p]==='-'){p++; return -U();} if(t[p]==='+'){p++; return +U();} return P();};
 const P=()=>{ const b=A(); if(t[p]==='^'){ p++; const e=U(); const r=b**e; if(!isFinite(r)) throw 0; return r;} return b;};
 const A=()=>{ const x=t[p++]; if(typeof x==='number') return x; if(x==='('){ const v=E(); if(t[p++]!==')') throw 0; return v;} throw 0; };
 try{ const v=E(); if(p!==t.length||!isFinite(v)) return null; return Object.is(v,-0)?0:v; }catch(e){ if(e instanceof RangeError) throw e; return null; } }`;
await run('H1 recursive descent', rd, get(hard, 'h1'));
await run('E2 naive utf16', "function smartTruncate(t,n){ if (t.length<=n) return t; if(n<1) return ''; return t.slice(0,n-1).trimEnd()+'\\u2026'; }", get(dbg, 'e2'));
await run('E5 lenient roman', "function romanToInt(s){const v={I:1,V:5,X:10,L:50,C:100,D:500,M:1000};let t=0;for(let i=0;i<s.length;i++){const a=v[s[i]],b=v[s[i+1]]||0;t+=a<b?-a:a;}return t;}", get(dbg, 'e5'));
await run('H2 via RegExp (backtracking)', "function regexMatch(p,t){ return new RegExp('^(?:'+p.replace(/[{}^$]/g,(c)=>'\\\\'+c)+')$','s').test(t); }", get(hard, 'h2'));
