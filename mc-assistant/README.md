# mc-assistant — an autonomous, self-aware Minecraft companion

A Minecraft bot that plays alongside you: it **gathers food and resources**,
**defends you**, follows you around, and understands **plain-English commands**.
It runs a constant "instinct" loop so it looks after itself — eating when
hungry, fighting what attacks it, and retreating when it's about to die.

Built on [Mineflayer](https://github.com/PrismarineJS/mineflayer). The
natural-language brain uses **Claude** (Anthropic) when an API key is present,
and falls back to a built-in keyword parser when it isn't — so it works either
way.

> **Java Edition only.** Mineflayer speaks the Java-Edition protocol. It cannot
> join Bedrock (phone/console/Windows-10) servers.

---

## What it does

| Category | Capabilities |
|---|---|
| **Food** | Hunts nearby animals (`hunt`), auto-eats when hunger drops, eats on command (`eat`). |
| **Resources** | Mines/collects wood, stone, coal, iron, gold, diamond, dirt, sand and more within a **20-block radius** by default (`gather <resource> [amount]`, radius configurable). |
| **Building** | Builds layouts you pick, right where it's facing: **wall, platform, pillar, tower, house, bridge** — with your choice of size and material (`build house`, `build a stone wall 8 long 3 high`, `build 5x5 platform`). |
| **Defense** | Fights hostiles that come near it or you, equips its best weapon + armor, guards you on command (`guard`, `attack`). |
| **Movement** | `come`, `follow`, `goto <x> <y> <z>`, `stop`. |
| **Crafting** | Crafts by player rules — planks, sticks, crafting table, tools, torches, furnace, chest (`craft stone pickaxe`). Finds a crafting table for 3x3 recipes or tells you it needs one. |
| **Smelting** | Real furnace, real fuel, real time: `smelt iron`, `cook my meat` (cooks whatever raw meat it holds), sand → glass, cobble → stone. Says what's missing (furnace/fuel/materials). |
| **Farming** | `farm` harvests ripe wheat/carrots/potatoes/beetroot in its radius, replants from the drops, and seeds bare farmland. |
| **Delivery** | `bring me 16 wood` gathers then walks back and hands it over; `give me the iron` / "hand over the loot" tosses items at your feet. |
| **Task menu** | `menu` opens a preset task list in game — clickable buttons (if the bot is op'd) or numbered picks (`m 3`). Presets are yours to edit in `presets.json`, including **multi-step jobs** ("Full wood run: chop → plank → stash"). |
| **Logistics** | Stashes loot in a nearby chest (`deposit`), drops items (`drop <item>`), holds a named tool (`equip axe`). |
| **Awareness** | Reports `status` / `inventory`, and narrates what it's doing/feeling in chat. |

### Player rules — no cheating

The bot is a real player entity and is held to player limits on purpose:

- **Tool tiers are enforced.** Mining stone needs a pickaxe; iron needs stone
  tier or better; diamond needs iron+. If it lacks the tool it says so
  (*"I can't mine iron yet — I need a stone pickaxe or better. Ask me to craft
  one."*) instead of pretending.
- **It has to craft like you do**: logs → planks → sticks → crafting table →
  tools. `craft pickaxe` makes the best tier its materials allow.
- **It gets hungry and takes damage** — real hunger bar, real HP, no flying,
  no creative. It eats, hunts, and retreats to survive.
- **If a resource isn't nearby, it says it can't.** Gathering scans a
  20-block radius (configurable); *"Can't do that here — no iron within 20
  blocks of me."*

### The task menu

Say **`menu`** (or `!menu`). If the bot has op (`/op Assistant`), it sends a
clickable list — click a task and it goes. Without op, it lists numbered
presets; reply `m 3` (or just `3`) to pick. Edit **`presets.json`** to make
the menu yours — each entry is a label plus any command the bot knows:

```json
{ "label": "Get logs (16)", "action": "gather", "args": { "resource": "wood", "amount": 16 } }
```

The **survival loop** (`src/skills/survival.js`) runs every ~0.7s and enforces
priorities on its own, without being told: **survive (flee) → defend → feed →
resume standing orders.**

---

## Quick start

Requires **Node.js 18+** and a **Java-Edition** server you can reach (a local
`localhost:25565` world in offline/LAN mode is easiest for testing).

```bash
cd mc-assistant
npm install
cp .env.example .env      # then edit .env
npm start
```

In game, talk to it (owner only by default):

```
!follow me
!gather 16 wood
!hunt
!guard
!build house                             # or: build a cobblestone wall 8 long 3 high
!build 5x5 platform
grab me some stone then come back        # natural language (needs an API key)
!status
!stop
```

Building happens **in front of the bot, facing the way it looks** — walk it to
the spot (`come` / `follow`), face it the right way, then ask. Houses and
towers get a doorway on the side facing it. It builds with the material you
name (if it's carrying any), otherwise common blocks from its inventory.

It also answers to **whispers** (`/msg Assistant follow me`) and to messages
that mention its name.

---

## Configuration

Everything is environment-driven — see **`.env.example`** for the full list.
The essentials:

- `MC_HOST` / `MC_PORT` — your server (default `localhost:25565`).
- `MC_USERNAME` — the bot's name.
- `MC_AUTH` — `offline` for cracked/LAN servers, `microsoft` for premium accounts.
- `MC_OWNER` — who it obeys/protects. If unset, the **first player to talk to
  it** becomes the owner for that session.
- `ANTHROPIC_API_KEY` — enables the Claude brain. Without it, the keyword parser
  handles the common commands above.
- `MC_MODEL` — defaults to `claude-opus-4-8`; set `claude-haiku-4-5` for faster,
  cheaper in-game replies.

---

## How it fits together

```
index.js            → connect + reconnect loop
  bot.js            → build bot, load plugins, chat throttling, shared state (bot.assistant)
    state.js        → the self-report: health, hunger, threats, inventory (the "self-awareness")
    commands.js     → the action registry — the single source of truth for what it can do
                      (also generates the Claude tool schemas)
    chat.js         → decides what's addressed to it, enforces owner policy, routes to a brain
    brain/
      llm.js        → Claude: plain English → tool calls, grounded in current state
      rules.js      → offline keyword parser (no API key needed)
    menu.js         → the task menu: presets.json -> clickable tellraw / numbered picks
    skills/
      movement.js   → come / follow / goto / retreat            (mineflayer-pathfinder)
      gather.js     → mine resources within a radius, tool-tier rules (mineflayer-collectblock)
      build.js      → blueprint layouts: wall/platform/pillar/tower/house/bridge
      craft.js      → player-rules crafting: planks -> sticks -> table -> tools
      smelt.js      → furnace smelting/cooking with real fuel
      farm.js       → harvest ripe crops, replant, seed farmland
      food.js       → hunt animals, eat
      defense.js    → fight hostiles, guard, equip gear         (mineflayer-pvp, armor-manager)
      inventory.js  → deposit to chests, drop items, hand deliveries to the owner
      survival.js   → the autonomy loop (eat / defend / flee)
```

Because the brain can only call tools defined in `commands.js`, adding a new
capability is one place: write a skill function, register it, and both the AI
and the keyword parser can use it.

---

## Notes & limits

- **Testing** requires a live Java server; there's no offline simulation.
- Pathfinding/combat quality depends on terrain, gear, and server version.
- The bot won't provoke neutral mobs (endermen, piglins, wolves) — it only
  defends against clearly hostile ones (see `HOSTILES` in `state.js`).
- On premium (`microsoft`) auth, first run may prompt a Microsoft device login
  in the console.
