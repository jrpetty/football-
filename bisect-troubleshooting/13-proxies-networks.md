# Proxies and Multi-Server Networks

A proxy sits in front of several backend servers and moves players between them
without a disconnect. It's how lobby/survival/creative networks work.

## Which proxy

| Proxy | Status |
|---|---|
| **Velocity** | **The current answer.** Modern, fast, secure forwarding built in. PaperMC's recommendation. |
| **BungeeCord** | The original. Still widely deployed. Legacy forwarding is insecure by design. |
| **Waterfall** | BungeeCord fork by PaperMC. **End of life** — PaperMC now directs users to Velocity. |

**Use Velocity for anything new.** The security model alone justifies it (below).

## Architecture

```
Players ──▶ Proxy (public port 25565) ──┬──▶ Lobby    (backend, firewalled)
                                        ├──▶ Survival (backend, firewalled)
                                        └──▶ Creative (backend, firewalled)
```

Requirements:

- Each backend is a **separate server** — on BisectHosting, a separate service or
  allocation.
- **Only the proxy is publicly reachable.** Backends must not be.
- Every backend runs `online-mode=false` — **authentication happens at the
  proxy.** This is the one legitimate use of offline mode, and it is only safe
  when backends are genuinely unreachable from outside.

## IP forwarding — and the security trap

Without forwarding, every backend sees all players connecting from `127.0.0.1`
with a proxy-generated UUID. Bans, permissions, and per-player data all break.

**BungeeCord legacy forwarding is fundamentally insecure.** Velocity's own
documentation says so. The backend simply trusts whatever identity the incoming
connection claims. **If a backend is reachable from the internet, anyone can
connect directly to it and impersonate any player or UUID — including your
admin.** That's a full server compromise via a config default.

**Velocity modern forwarding** solves it with a shared secret: the backend
verifies the proxy is genuine before trusting the forwarded identity.

### Velocity setup

`velocity.toml`:

```toml
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

Backend `paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: <contents of forwarding.secret>
```

Backend `server.properties`: `online-mode=false`.

Note the asymmetry — `online-mode=false` in `server.properties` but
`online-mode: true` under the Velocity block. That's correct: the proxy
authenticates, and the backend trusts the proxy's verified result.

### BungeeCord setup

`config.yml` on the proxy: `ip_forward: true`.
Backend `spigot.yml`: `bungeecord: true`.
Backend `server.properties`: `online-mode=false`.

**And firewall the backends.** With legacy forwarding this is not optional
hardening — it is the only thing standing between your network and trivial
impersonation.

## Firewalling backends on shared hosting

The hard part on a panel host, where you don't control the firewall.

Options:

1. **Ask support.** They can often restrict a port to the proxy's IP.
2. **Same-node placement**, if the host supports private networking between your
   own containers.
3. **Velocity modern forwarding**, which removes the impersonation risk even if a
   backend is reachable. **This is the strongest reason to choose Velocity on
   shared hosting** — it's the only option that's safe by default when you can't
   control the network.

## Forced hosts

Route domains to specific backends:

```toml
[forced-hosts]
"creative.example.com" = ["creative"]
"survival.example.com" = ["survival"]
```

Players land directly on the right server from the address they typed.

## Plugin split

Proxy and backend plugins are **different APIs**. A Bukkit/Paper plugin will not
run on Velocity.

| Layer | Runs | Handles |
|---|---|---|
| **Proxy** | Velocity/BungeeCord plugins | Cross-server chat, global bans, queues, routing, MOTD |
| **Backend** | Paper/Spigot plugins | Gameplay, world protection, per-server economy |

LuckPerms installs on **both** — proxy for network-wide groups, backends for
gameplay permissions, sharing one MySQL database. Getting this wrong produces the
classic "permissions work on one server but not another".

## Common failures

| Symptom | Cause |
|---|---|
| `Unable to connect to <server>` | Backend down, wrong `address` in proxy config, or wrong port |
| Everyone appears as `127.0.0.1` | IP forwarding not enabled on both sides |
| Kicked instantly on backend join | Forwarding mode mismatch between proxy and backend |
| `If you wish to use IP forwarding, please enable it in your BungeeCord config` | Backend expects forwarding, proxy isn't sending it |
| Duplicate/wrong UUIDs, lost player data | Offline-mode UUIDs on a backend not behind proper forwarding |
| Players connect directly to a backend, bypassing the proxy | **Backend not firewalled.** Fix urgently. |
| Permissions inconsistent across servers | LuckPerms not sharing a database, or missing on the proxy |

## Is a network worth it?

Costs: N servers to pay for and maintain, a proxy, a shared database, more config
surface, and a whole extra class of failure.

Worth it when you genuinely need isolated worlds with different rulesets or
versions, or when a single server can't carry your player count.

**Not worth it** for a friends server, or for separating a creative world —
Multiverse-Core gives you multiple worlds in one server for a fraction of the
complexity. Most small servers that build a network regret it.
