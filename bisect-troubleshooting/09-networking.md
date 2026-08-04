# Connectivity

## Get the address right first

Most "can't connect" reports are a wrong address. Check the **Network** tab.

- **With a dedicated IP:** `123.45.67.89` — default port 25565, no port needed.
- **Without one (Budget plans):** `123.45.67.89:25581` — **the port is
  mandatory** and is usually *not* 25565.
- **Subdomain:** `play.yourserver.com` — may or may not need the port depending
  on whether an SRV record is configured.

The most common single mistake: **omitting the port on a shared-IP plan.** The
client silently tries 25565, nothing is listening there, connection refused.

## Error signatures

| Client sees | Meaning | Fix |
|---|---|---|
| `io.netty...ConnectException: Connection refused` | Reached the host, nothing listening on that port | Server offline, or wrong port |
| `Connection timed out` | No response at all | Wrong IP, firewall, or network issue |
| `io.netty...UnresolvedAddressException` | Hostname didn't resolve | Typo, or DNS not propagated |
| `Failed to login: Invalid session` | Auth failure | Restart the launcher; check `online-mode` |
| `Outdated server` / `Outdated client` | Version mismatch | Match client to server version |
| `You are not whitelisted on this server` | Whitelist active | Add the player |
| `Connection lost: Internal Exception: java.io.IOException` | Connection dropped mid-session | Network instability or a server-side crash |
| `Mod rejections` / missing mods list | Client mod set ≠ server mod set | Install the matching pack |
| `The server is still starting` | Not finished booting | Wait |

## Triage

### 1. Is the server actually running?

Panel state indicator, and the console showing `Done (X.XXXs)! For help, type
"help"`. **A panel that says "Running" while the console shows a crash loop is
common** — check the console, not the badge.

`Done` is the line that means "accepting connections". Anything before it means
still booting.

### 2. Can anyone connect?

| Who's affected | Points at |
|---|---|
| Nobody can connect | Server, address, or host |
| One player can't | That player's client, network, or account |
| Some can, some can't | Version mismatch, whitelist, or a regional network issue |

### 3. Check server.properties

Stop the server before editing — a running instance rewrites this file on
shutdown.

| Setting | Should be | Notes |
|---|---|---|
| `server-ip=` | **Empty** | The commonest self-inflicted lockout. Setting this on a hosted server binds to the wrong interface and blocks everyone. Leave it blank. |
| `server-port=` | Your panel-allocated port | Must match the Network tab exactly |
| `online-mode=` | `true` | `false` disables authentication — see below |
| `white-list=` | As intended | `true` means only listed players get in |
| `enforce-whitelist=` | As intended | Kicks non-whitelisted players already online |
| `max-players=` | Sensible | Full server refuses new connections |
| `network-compression-threshold=` | `256` default | `-1` disables; can help on fast local networks, hurts over the internet |
| `prevent-proxy-connections=` | `false` | `true` can block legitimate VPN players |

### 4. Modded — mod list mismatch

The client is rejected with a list of missing or extra mods.

Both sides need the **same loader, same Minecraft version, and matching content
mods**. Server-side-only mods are fine on the server alone; client-only mods
(shaders, minimaps) are fine on the client alone. Everything that adds blocks,
items, or entities must be on both.

Cleanest fix: everyone installs the same pack version from the same source.

## online-mode

`online-mode=true` (the default) authenticates joining players against Mojang's
account database.

Setting it to `false` — "offline mode" or "cracked":

- Allows unauthenticated clients
- **Removes all identity verification** — anyone can join as any username,
  including your admin account, and receive their permissions and inventory
- Breaks UUID consistency, which can scramble player data on later re-enable
- Is required for some proxy setups (BungeeCord/Velocity), where authentication
  happens at the proxy instead — that's the one legitimate case

If a player can't authenticate, the right fixes are restarting their launcher,
checking Mojang/Microsoft service status, and confirming their account. Turning
off `online-mode` to work around it trades a login inconvenience for an open
door.

## Whitelist and bans

Manage from **Minecraft Tools → Player Manager**, or by console:

```
whitelist add <player>
whitelist remove <player>
whitelist list
whitelist on|off
whitelist reload

ban <player>       pardon <player>
ban-ip <ip>        pardon-ip <ip>
banlist
```

Backing files: `whitelist.json`, `banned-players.json`, `banned-ips.json`,
`ops.json`. Editing them by hand requires correct UUIDs — the console commands
resolve those for you, so prefer them.

**Note:** `enforce-whitelist=false` (default) means enabling the whitelist
doesn't kick players already online. Set it `true` if you need immediate effect.

## Ping and rubber-banding

TPS is fine, but players lag.

| Cause | Check |
|---|---|
| Geographic distance | Server region vs where players actually are |
| Player's own connection | Have them run a speed test; test wired vs WiFi |
| Route congestion | `tracert`/`mtr` to the server IP |
| DDoS mitigation | Included on BisectHosting; can add latency during an active attack |
| `network-compression-threshold` | Rarely, but tuning it can help |

Distinguishing: **low TPS = server problem; normal TPS with high ping = network
problem.** Check `/spark tps` before assuming.

## DDoS

BisectHosting includes DDoS protection. During an active attack you may see
latency spikes or brief unavailability while mitigation engages.

Signs: sudden mass disconnects with the server itself healthy, and normal TPS
throughout.

Not much to do from your side beyond opening a ticket so they can confirm and
tune mitigation. **Don't publish your raw IP** if you have a subdomain — it's
the main way small servers get targeted.
