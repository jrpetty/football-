# Domains and DNS

## Why bother

A custom address gives you:

- Something memorable — `play.example.com` over `123.45.67.89:25581`
- **Portability** — change hosts without every player updating anything
- **Port hiding** — no `:25581` suffix, via SRV
- Your raw IP stays less exposed ([15](15-security.md#network))

The portability point is the real one. If you ever migrate hosts, a domain turns
an hours-long coordination problem into a DNS edit.

## The two records

| Record | Points to | Purpose |
|---|---|---|
| **A** | An **IP address** | Where the server is |
| **SRV** | A **hostname + port** | Lets players omit the port |

**A gets you connected. SRV hides the port.** You always need A; you need SRV
only when your port isn't 25565.

## A record

| Field | Value |
|---|---|
| Type | `A` |
| Name / Host | `play` (for `play.example.com`), or `@` for the root domain |
| Value / Target | Your server's **IP address, no port** |
| TTL | 300 (5 min) while setting up |

If your port **is** 25565, you're finished — `play.example.com` works.

## SRV record

Needed when your port isn't 25565 — which on BisectHosting shared plans is
normal.

| Field | Value |
|---|---|
| Type | `SRV` |
| Service | `_minecraft` |
| Protocol | `_tcp` |
| Name / Host | `play` (matching your A record) |
| Priority | `0` |
| Weight | `5` |
| Port | Your **actual** port, e.g. `25581` |
| Target | `play.example.com` — the **hostname**, not the IP |

Registrar UIs vary considerably. Some want one combined field:

```
_minecraft._tcp.play    SRV    0 5 25581 play.example.com.
```

Common mistakes:

- **Target set to an IP.** SRV targets must be hostnames. Point it at your A
  record's name.
- **Underscores omitted.** `_minecraft` and `_tcp` both need them.
- **Service/protocol duplicated into the Name field** when the registrar already
  has separate fields — produces `_minecraft._tcp._minecraft._tcp.play`.
- **Trailing dot** required by some registrars on the target, rejected by others.
  Follow the registrar's examples.

## Verify

```bash
dig +short A play.example.com
dig +short SRV _minecraft._tcp.play.example.com
nslookup -type=SRV _minecraft._tcp.play.example.com
```

Or use [dnschecker.org](https://dnschecker.org) to see propagation globally.

Expected SRV output:

```
0 5 25581 play.example.com.
```

## Propagation

Changes take **5 minutes to 24 hours**, depending on resolvers and cached TTLs.

Practical handling:

- Set TTL to **300** before making changes.
- Test from a device that hasn't cached the old value — mobile data works well.
- Flush local DNS: `ipconfig /flushdns` (Windows),
  `sudo dscacheutil -flushcache` (macOS).
- **The Minecraft client caches DNS for the session.** Fully restart the client,
  not just the server list entry.

## Free options

No domain? These work:

- Free subdomain services aimed at Minecraft (search "free minecraft DNS")
- Some hosts include a free subdomain — check whether your plan does
- No-IP and similar dynamic DNS providers

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Unknown host` | A record missing or not propagated |
| Connects only with the port typed | SRV missing or malformed |
| Old IP still resolving | TTL caching — wait, or flush |
| Works for some players, not others | Partial propagation. Wait. |
| Worked, then broke | Domain expired, or the host's IP changed |
| SRV ignored | Some clients/launchers skip SRV — test with vanilla |

## Migrating with a domain

The reason to have one. See [20-migration.md](20-migration.md).

1. Lower TTL to **300 at least 24 hours before** the move, so the old long TTL
   has expired everywhere.
2. Build and fully test the new server.
3. Update the A record (and SRV port if it changed).
4. Keep the old server running a few hours for stragglers on cached DNS.

Do the TTL reduction first. If you drop it at cutover time, players are still
holding the old value for however long the *previous* TTL was — which is exactly
the outage you were trying to avoid.
