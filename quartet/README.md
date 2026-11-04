# 🎭 Quartet — four minds, one situation

Four independent AI minds, dropped into a real situation from today's world.
Each plays one person and knows only what that person knows. They want what
that person wants and hide what that person hides. Nobody is scripted. You
watch what happens.

It's a separate experience from [Genesis](../world/README.md), the
civilisation simulator, but it shares the same AI connections.

## Run it

```bash
cd quartet
npm install
npm run dev          # http://localhost:5176
```

Or open **`quartet.html`** (repo root) directly in a browser. Once GitHub Pages
is enabled it's also published at **`/quartet/`**.

## The scenarios

18 situations, each with its own places, four roles, shared stakes, actions,
random events and a clock:

| | | |
|---|---|---|
| 🏝️ **Stranded** — plane crash on a Pacific atoll | 🚀 **Runway** — four founders, 12 weeks of cash | 🛟 **Lifeboat** — North Sea, at night |
| 🏠 **Flatshare** — London, rent due, someone's lost their job | ⚖️ **The Verdict** — a jury that must be unanimous | 🏥 **Night Shift** — A&E after a coach crash |
| 📰 **Deadline** — a leak that could topple a minister | 🧊 **Winter-Over** — Antarctic station, polar night | 🍷 **The Will** — four siblings, their mother's house |
| 🚐 **Route 66** — a failing van and a wedding in LA | 📺 **The House** — reality TV, eviction on Sunday | ❄️ **Snowbound** — blizzard, mountain hut, an injury |
| 🔐 **Locked In** — an escape room that really won't open | 🌍 **The Summit** — a climate deal by midnight | 🍳 **Opening Night** — a critic at table nine |
| 🗳️ **War Room** — a long-shot mayoral campaign | 🥐 **The Last Loaf** — a family bakery vs a chain | 💍 **The Big Day** — a flooded wedding venue |

**Every run is different.** The people are generated fresh each time, with
names from around the world, a temperament (Big Five), a quirk, a life story
and a face. Each role carries a goal and a **secret**. Events arrive at random,
and every action can succeed or fail depending on effort, energy and
temperament.

## How the minds work

Each turn, all four minds are asked in parallel what they think, say and do.
Each one is told:

- the situation
- where they are and who is with them
- the stakes
- their own body and feelings
- what they privately think of the others
- what they remember, including what was said in their hearing

Each mind replies with:

- a private **thought**
- something to **say** (to someone, or to the room)
- an **action** from the scenario (fish, pitch investors, re-examine the evidence…) or a move to another place
- or something done **to someone**: comfort, confront, persuade, help, give, use their item, or confess
- their **vote**, when the group faces a hard choice
- changes in how much they trust the people around them
- in vote and verdict scenarios, their **position**

An optional **narrator** mind ties each turn together in prose. It can nudge the
situation, and it writes the epilogue and what becomes of each person.

## How a scenario plays

Every scenario is told in **three acts**. Events come more often as the story
goes on, the strain grows in the last act, and the run ends on a **finale**.

- **Hard choices.** Each scenario has three dilemmas the group must vote on,
  such as eating the spoiling food or rationing it, burning the shelter as a
  beacon, or publishing on one source. The majority wins. Being outvoted
  stings, and people remember who voted against them.
- **Secrets.** Everyone carries one. Confessing it relieves the pressure and
  may be forgiven. Being confronted while stressed can drag it out, and an
  exposed secret costs trust.
- **Items.** The flare gun, the satellite messenger, the first-aid kit, the
  credit card and others do something real when used, or can be handed to
  someone else.
- **People act on people.** Comforting eases stress and builds trust.
  Confronting wounds. Helping someone with their work makes it far likelier to
  succeed. Persuading can change someone's position.
- **Cohesion.** Trust across the group shapes how well they work together.
  Strain and blame wear it down, and when bad blood runs deep, rows break out.
- **Endings.** The ending weighs the stakes, the goal, how close they ended
  up and who is still standing. It names the person who held things together
  and lists the key moments of the run.

### Mix the minds

Every character can run on a different model. Pit Claude against DeepSeek
against Qwen against a model on your own laptop in the same lifeboat:

- **Claude**: Opus 5.5 (default), Sonnet 5.5, Haiku 5.5, Fable 5.1
- **Chinese APIs**: DeepSeek, Qwen (Alibaba Cloud), Kimi (Moonshot), GLM (Zhipu)
- **On your computer**: Ollama, LM Studio, or any OpenAI-compatible server (llama.cpp, vLLM…)
- **Instinct**: no AI at all. Characters act sensibly and speak in stock lines.

Most cloud APIs refuse calls made directly from a web page. For those, run
`npm run proxy` and tick **Use local proxy**. The proxy forwards requests only
on `localhost:8787` and listens only on your machine. For Ollama on the hosted
site, start it with `OLLAMA_ORIGINS=*`. For LM Studio, enable CORS in its
server settings.

API keys stay in your browser's localStorage. On the same site they're shared
with Genesis, so a key you set up in one works in the other.

## Watching

- **On stage:** act title cards, banners for events, choices, votes (with who
  voted which way) and secrets, numbers that float up as work succeeds or
  fails, arcs between people who comfort, confront, help or persuade each
  other, storms that shake the stage, and a red edge as tension rises.
- **Stage:** each scenario's places, with the four people, rendered in 3D,
  walking between them. Each is dressed for their job and posed for what
  they're doing and feeling: talking with their hands, working, thinking,
  slumped, arms crossed, hunched in fear. Faces blink and react, mouths move
  with speech, and the light follows the time of day. Speech bubbles,
  "thinking…" dots and emotes float above.
- **Script tab:** the whole story as a play script.
- **Thoughts tab:** what everyone is privately thinking.
- **Bonds tab:** a 4×4 matrix of who trusts whom.
- **Person view (click someone):** their secret, goal, needs, opinions of the
  others, and memories.
- **Ending:** the outcome tier (triumph, success, mixed, failure or disaster),
  an epilogue and everyone's fate. You can download the whole script as
  Markdown, or replay with the same people or a new cast.

## Architecture

```
src/engine/   scenarios.ts (the 18 situations) · drama.ts (acts, choices, items) ·
              cast.ts (people) ·
              engine.ts (turns, actions, events, endings) · prompts.ts
src/ai/       providers.ts + client.ts (shared with Genesis) · host.ts (settings, costs)
src/ui/       Stage.tsx (the scene) · rig3d.ts + cast3d.ts (3D people) ·
              Figure.tsx (2D fallback & portraits)
src/App.tsx   menu, cast setup, play, ending, settings
test/         every scenario plays to an ending; mock-AI run; prompt sizes
```

```bash
npm test             # engine tests, no API key needed
npm run build        # dist/
npm run build:single # ../quartet.html
```
