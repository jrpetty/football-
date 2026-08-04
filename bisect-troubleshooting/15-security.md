# Security

Minecraft servers are a real target. They run arbitrary third-party code, are
publicly reachable, and are usually administered by people who aren't sysadmins.

## Threat model

| Threat | What it looks like |
|---|---|
| **Griefing** | Builds destroyed, chests emptied. Opportunistic or organised. |
| **ForceOP exploits** | Malicious books, signs, or chat payloads that trick the server into granting op |
| **Backdoored plugins/mods** | Trojanised jars with hidden admin commands or remote access |
| **RCE vulnerabilities** | Remote code execution — the server host itself is compromised |
| **DDoS** | Volumetric attacks against your IP |
| **Account compromise** | Panel or billing credentials stolen |
| **Insider abuse** | A staff member with too many permissions |

## Historical incidents worth knowing

**Log4Shell (CVE-2021-44228, December 2021)** — a critical RCE in Log4j. A chat
message could execute arbitrary code on the server *and* on connected clients.
This is why every BisectHosting startup command carries
`-Dlog4j2.formatMsgNoLookups=true` and `-javaagent:/Log4jPatcher.jar`. Those are
mitigations, not errors — **don't remove them.**

**BleedingPipe (July 2023)** — an RCE affecting a large set of Forge mods via
unsafe deserialization in network packet handling. Exposed thousands of mods.
Reinforced the lesson that a mod is arbitrary code with full server privileges.

The general lesson: **every mod and plugin you install runs with the server's
full privileges.** There is no sandbox. Source matters enormously.

## Hardening checklist

### Access

- **Don't op people.** `op` is permission level 4 — total control, bypassing your
  permissions plugin entirely. Use LuckPerms groups with specific nodes.
  See [12-plugins-permissions.md](12-plugins-permissions.md#permissions-vs-op).
- **Whitelist private servers.** `white-list=true` plus
  `enforce-whitelist=true`. The single most effective control for a friends
  server, and it costs nothing.
- **Keep `online-mode=true`.** Offline mode lets anyone join as any username —
  including your admin account, inheriting their permissions and inventory. The
  only legitimate exception is a backend behind a properly-secured proxy
  ([13](13-proxies-networks.md#ip-forwarding--and-the-security-trap)).
- **2FA on your panel and billing accounts.** Losing the panel loses everything.
- **Separate panel subusers** for staff instead of sharing the main login. The
  **Users** tab exists for this.

### Software supply chain

- **Install only from SpigotMC, Modrinth, Hangar, CurseForge, or BuiltByBit.**
  Never from a random Discord link, a YouTube description, or a "leaked premium
  plugin" site — leaked-plugin sites are a primary backdoor distribution channel,
  and that's the point of them.
- **Keep server software and plugins updated.** Old versions have public,
  weaponised exploits.
- **Remove abandoned plugins.** Unmaintained code doesn't get security fixes.
- **Audit before installing.** Check the author, download count, update recency,
  and reviews. A plugin with 40 downloads and no history deserves scepticism.

### Data

- **Automated backups**, plus periodic off-panel copies. Ransomware and
  destructive griefing both end at "restore from backup".
- **CoreProtect from day one.** Rollback capability is worthless if you install
  it after the grief.

### Network

- **Don't publicise your raw IP** if you have a subdomain. It's the main way
  small servers get DDoSed.
- **Firewall proxy backends.** See
  [13](13-proxies-networks.md#firewalling-backends-on-shared-hosting).
- DDoS protection is included on BisectHosting and handled upstream.

### Monitoring

- Watch console for unexpected `op` grants, unfamiliar plugin load lines, or
  commands you didn't run.
- CoreProtect `/co lookup` on anything suspicious.
- Unexplained TPS drops can indicate crypto-mining payloads in a backdoored jar.

## Anti-cheat

For public survival/PvP servers.

| Plugin | Notes |
|---|---|
| **GrimAC** | Open source, simulation-based. The current recommendation. |
| **Spartan** | Paid, actively maintained |
| **NoCheatPlus** | Free, older, still used |

Anti-cheat is only for **plugin servers**. Modded servers generally can't run it
meaningfully — mods legitimately change movement and combat in ways anti-cheat
reads as cheating.

Expect false positives with ViaVersion, high-latency players, and elytra flight.
Tune before enabling auto-punishment, or you'll ban legitimate players.

## Signs of compromise

| Sign | Meaning |
|---|---|
| Players opped you didn't op | ForceOP exploit or backdoor |
| Unknown plugins in `plugins/` | Backdoor dropping payloads |
| Console commands you didn't run | Active intrusion |
| Outbound connections to unknown hosts | Possible C2 or mining |
| Sudden unexplained CPU load | Mining payload |
| Config files modified without your action | Compromise |

### If compromised

1. **Stop the server.** Immediately.
2. **Change every password** — panel, billing, email, database, and any reused
   credential.
3. **Restore from a backup predating the compromise.** Don't try to clean in
   place; you cannot prove you found everything.
4. **Rebuild the plugin/mod set from trusted sources.** Don't reuse the existing
   jars — one of them is the problem.
5. **Audit `ops.json`**, permissions, and whitelist.
6. **Tell BisectHosting**, especially if you suspect RCE — it may affect the node.

## Realistic posture

A private friends server needs: whitelist on, online-mode true, backups
automated, plugins from trusted sources, and no unnecessary ops. That's genuinely
most of the risk handled.

A public server additionally needs: anti-cheat, CoreProtect, region protection,
a staff permission ladder, and someone actually watching logs.

**Don't over-engineer a five-friend server.** The whitelist does most of the work
— the threat model for a whitelisted private server is almost entirely "a friend
does something dumb", and CoreProtect plus backups covers that.
