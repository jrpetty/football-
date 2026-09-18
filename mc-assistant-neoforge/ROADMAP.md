# MC Assistant — where this is going

## Now: Assistants (built)

A player hires individual specialists. Each one is **directed by you**: you pick
its job, mark its patch, hand it its tools, and it works that job unattended
until it needs something. Nine jobs — farmer, lumberjack, miner, rancher, guard,
smelter, fisher, storekeeper, hauler — plus beds and shifts, veteran levels and
branches, a work record, and revival by Memory Core.

The defining constraint: **an assistant is a tool you aim.** It does not decide
what the base needs. That is deliberate, and it stays that way.

---

## Later: Village Folk (design note — NOT built)

A **separate unit type**, not a change to assistants. Village Folk are the same
underlying skill set, but organised as a settlement that runs itself.

### What makes them different

| | Assistant | Village Folk |
|---|---|---|
| Who decides the job | the player | the village |
| Role | fixed until you change it | switches as the village's needs change |
| Purpose | do the task you assigned | keep the settlement alive and growing |
| Zone | you mark it | the village plans and claims its own |

### The shape of it

- **A leader** — foreman / mayor. Reads what the settlement is short of and
  assigns roles accordingly: three on food when stores are low, two on iron when
  the smithy is dry. Roles are reassigned as conditions change rather than fixed.
- **Role switching** — a villager is not "a farmer"; it is someone currently
  farming. It carries every skill and takes whichever the leader needs.
- **Supply chains** — the miner's ore reaches the smelter's input chest; the
  smelter's ingots reach the store. Output is routed, not stranded.
- **A request queue** — the smelter is out of fuel and says so; the next
  lumberjack delivery is routed to it. Needs are published, not guessed at.
- **Priority under scarcity** — when upkeep only covers four of six, the
  important work continues instead of everyone stalling together.
- **They build it themselves** — houses, stores, walls, roads. The village
  decides its own layout and grows into it.
- **They gather for themselves** — their own resource targets, not the player's.

### The end state

A settlement that is genuinely self-sustaining: it feeds itself, defends itself,
decides what to build, and grows without instruction. The player is a neighbour
and a trading partner, not an operator.

### Why keep them separate

The two answer different fantasies. Assistants are **your crew** — precise,
directed, accountable to you. Village Folk are **a place that lives** — you
influence it, you don't run it. Merging them would make assistants unpredictable
(they'd wander off to do what "the village" wanted) and make the village feel
micromanaged. Separate units, shared skill code underneath.

### Order of work, when we get to it

1. Supply chains between assistants — routing output to the right input chest.
   Valuable on its own, and the foundation everything else sits on.
2. The request queue — publishing needs so another worker can satisfy them.
3. The leader — reading the settlement's state and assigning roles.
4. Priority under scarcity.
5. Self-directed building and layout.

Steps 1 and 2 are worth building for assistants regardless; 3 onward is what
turns them into Village Folk.
