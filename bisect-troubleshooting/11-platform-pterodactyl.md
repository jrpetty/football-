# The Platform Underneath — Pterodactyl, Wings, Docker

Starbase is a customised Pterodactyl. Knowing the stock architecture explains
most panel behaviour that otherwise looks arbitrary.

## Components

| Component | What it is | Written in |
|---|---|---|
| **Panel** | The web UI. Auth, config storage, orchestration. | PHP (Laravel) + React |
| **Wings** | The per-node daemon. Actually creates and controls Docker containers. | Go |
| **Docker** | Container runtime. One container per server. | — |
| **Eggs** | Config templates defining how a game installs and launches | JSON |

The Panel **never touches your files directly.** It tells Wings what to do; Wings
does it. This is why the panel can be responsive while your server is
unreachable, and why "the panel says Running" is not proof the game server is up.

## Multi-node

Each Wings node manages its own containers independently. The Panel aggregates
status across nodes.

Consequences worth knowing:

- **A node outage affects only servers on that node.** Other customers are fine,
  which is why "is it down for everyone?" is the wrong question — ask "is it down
  for everyone on my node?"
- **The panel can be up while your node is down**, and vice versa.
- **Your neighbours share your node's CPU and disk.** Relevant to
  [performance](07-performance.md#shared-hosting-reality).

## Containers

Each server runs in its own Docker container with an isolated network. Wings
enforces limits through **cgroups**:

| Limit | Enforced how | Failure mode |
|---|---|---|
| **Memory** | cgroup memory cap | **Kernel OOM-kill → exit 137** |
| **CPU** | cgroup quota / shares | Throttling, not killing. Low TPS. |
| **Disk** | Wings-enforced quota | Write failures, `No space left on device` |
| **Network** | Isolated per-server bridge | Cross-container traffic blocked by default |

**The memory limit is a hard kernel-level ceiling.** This is the mechanism behind
[the headroom rule](04-java-memory.md#the-headroom-rule) — nothing negotiates
with the OOM killer. When the container's total RSS crosses the cap, the kernel
picks the biggest process (Java) and sends SIGKILL. Java gets no chance to log.

**CPU limits throttle rather than kill**, which is why CPU starvation shows up as
bad TPS rather than a crash. Minecraft's main tick loop is single-threaded, so a
container with several cores can still tick badly if one core is contended.

## Eggs

An egg is a JSON template defining:

- The Docker image to use
- The install script (run once at creation/reinstall)
- The startup command template
- User-editable variables and their validation rules
- Log parsing rules and stop commands

**This is where the `@`-versus-`-jar` question is decided.** The egg's startup
template is fixed; you edit the variables it exposes. When the template is
Forge-family (`java ... @{{SERVER_JARFILE}}`) and you put a `.jar` in the
variable, you get the [argfile trap](05-modloaders.md#the-argfile-trap).

It also explains why **jar menu operations overwrite manual Startup edits** — the
install script rewrites the variables it owns.

BisectHosting layers `bhbash.sh` on top of the stock egg flow, adding loader
auto-detection, the Log4j patch injection, and log directory management.

## Allocations

An allocation is an **IP:port pair** assigned to your server. Every server needs
at least one.

- Your **primary allocation** is the game port.
- **Extra allocations** are needed for anything listening separately — Geyser's
  Bedrock UDP port, a dynmap web server, a proxy's backend port.
- On shared-IP plans your port is **not 25565**; the panel assigns one from the
  node's range. This is the root of most
  [connection failures](09-networking.md#get-the-address-right-first).
- `server-port` in `server.properties` **must match** the allocation. Change one
  without the other and the server binds where nothing routes.

## The wrapper

Between Wings and Java sits a process wrapper. On BisectHosting that's
`node /wrapper.js "$MODIFIED_STARTUP"`, launched from `bhbash.sh`.

Its job is to spawn the game as a child process and bridge stdin/stdout so the
panel console can read output and send commands — containers don't give you an
interactive TTY for free.

**This layer is invisible until it breaks**, and when it breaks it produces the
most misleading failure in the whole stack: no Java output at all, because the
thing that would have relayed Java's output is the thing that died. See
[03-exit-codes.md](03-exit-codes.md#the-wrapper-segfaulted--the-bisecthosting-case).

## The filesystem

Everything you can touch lives under `/home/container`, mounted into the
container as its working directory.

```
/home/container/
├── logs/                    latest.log, rotated .log.gz
├── crash-reports/
├── mods/          or        plugins/
├── config/
├── world/                   (+ world_nether/, world_the_end/ on Paper)
├── libraries/               Forge/NeoForge launch layout
├── server.properties
├── eula.txt
├── user_jvm_args.txt        Forge/NeoForge
└── run.sh                   Forge/NeoForge
```

Outside it — `/wrapper.js`, `/bhbash.sh`, `/Log4jPatcher.jar`, the JREs — is the
image. **Read-only to you, and the host's responsibility.** Any error naming a
path outside `/home/container` is an escalation, not a debugging task.

## Disk

Wings enforces a disk quota. When it's hit:

- The server can't save the world — **a direct route to corruption**
- Logs stop writing
- Uploads fail

Usual consumers, in order: rotated logs that never got cleaned, old backups
stored in the server directory, duplicate modpack uploads, and oversized dynmap
or map-render tiles.

Housekeeping: clear `logs/*.log.gz` periodically, delete the modpack zip after
unarchiving, and keep backups in the **Backups** system rather than the server
directory.

## SFTP

Wings runs an SFTP server authenticating against panel credentials — that's why
the SFTP username has the `user.serverid` form. It's the same filesystem the web
File Manager shows, just a faster path to it. Use it for anything above a few
hundred megabytes; the web manager is unreliable at modpack scale.

## Behaviour this explains

| Behaviour | Reason |
|---|---|
| Panel says Running, server unreachable | Panel shows Wings' view; the game process inside can be crash-looping |
| Restart doesn't apply Startup changes | Variables resolve at container start — needs a full stop |
| `server.properties` edits vanish | The running server rewrites it on shutdown. Edit while stopped. |
| Jar install wipes manual Startup edits | The egg's install script owns those variables |
| Exit 137 with heap set "correctly" | cgroup caps **total** container memory, not just heap |
| Console disconnects but server survives | The console is a websocket to Wings, not the server itself |
| Can't reach another of your servers by localhost | Per-server isolated Docker networks. Use the public allocation. |
