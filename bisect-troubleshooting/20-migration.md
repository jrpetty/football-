# Migration

Moving a server between hosts, plans, or software. Same discipline every time:
**back up, transfer, restore, test, cut over.**

## Before you start

- **The source server must be fully stopped.** Copying a running world is the
  most reliable way to corrupt it — you'll capture a half-written region file.
- Realistic downtime: **15–60 minutes** for transfer plus a few minutes to test.
  Large modded worlds run longer.
- Have the destination **built and tested** before you touch DNS.

## What to transfer

| Item | Path | Notes |
|---|---|---|
| **World** | `world/` | Plus `world_nether/`, `world_the_end/` on Paper/Spigot |
| **Plugins** | `plugins/` | Include the config subfolders |
| **Mods** | `mods/` | Must match the loader version exactly |
| **Configs** | `config/` | Modded server configs |
| **Server config** | `server.properties` | Review before reusing — **`server-port` will differ** |
| **Access lists** | `whitelist.json`, `ops.json`, `banned-*.json` | Easy to forget |
| **Player data** | inside `world/playerdata/` | Travels with the world |
| **Databases** | MySQL dumps | Export/import separately — **does not travel with files** |

**Don't blindly copy `server.properties`.** `server-port` and `server-ip` are
host-specific; carrying them over is a direct route to
[a server that binds where nothing routes](11-platform-pterodactyl.md#allocations).
Copy the gameplay settings, re-enter the network ones.

## Procedure

1. **Announce downtime.**
2. **Stop the source server.** Wait for full offline.
3. **Back up** — panel backup *and* a local download.
4. **Download** via SFTP. The web File Manager is unreliable at modpack scale.
5. **Prepare the destination:** matching Minecraft version, matching loader
   version, matching server software.
6. **Upload** world, plugins/mods, configs.
7. **Re-enter** `server-port` and leave `server-ip` empty.
8. **Import databases** if any.
9. **Start and test** — see the checklist below.
10. **Cut over DNS.** [17-domains-dns.md](17-domains-dns.md).
11. **Keep the old server up** for a few hours for cached-DNS stragglers.

## Test before cutover

- [ ] Server reaches `Done (X.XXXs)!`
- [ ] World loads with builds intact — **check a known landmark, not just spawn**
- [ ] Plugins/mods loaded without errors in `latest.log`
- [ ] You can connect
- [ ] Ops and whitelist correct
- [ ] Player inventories intact (log in as a test player)
- [ ] Permissions work (`lp user <you> info`)
- [ ] Economy balances present, if applicable
- [ ] Databases connected — no `Communications link failure`

## Minimising downtime

The domain trick, from [17](17-domains-dns.md#migrating-with-a-domain):

1. **Lower DNS TTL to 300 at least 24 hours ahead**, so the old TTL expires
   everywhere first. Doing this at cutover doesn't help — players still hold the
   old value for the length of the *previous* TTL.
2. Build and fully test the new server while the old one runs.
3. Stop the old server, do a final world sync, start the new one.
4. Update DNS.

The final sync matters: a world copied hours earlier loses everything players did
since. **Copy the world twice** — once early to prove the process works, once at
cutover for the real data.

## Changing server software

### Vanilla/Forge/NeoForge/Fabric → Paper

The world layout differs. Nested dimensions must be split out:

```
world/DIM-1/  →  world_nether/DIM-1/
world/DIM1/   →  world_the_end/DIM1/
```

Modded blocks become air or vanish. **If the world was ever modded, expect
holes.**

### Paper → Vanilla/modded

Reverse the split — merge `world_nether/` and `world_the_end/` back under
`world/`.

### Modded → different modpack

**Generally don't.** Different mod sets mean different block registries;
everything from the old pack's mods disappears. Start a new world unless the
packs share a mod base.

### Version upgrades

Minecraft usually upgrades worlds forward automatically. **It cannot go
backwards** — opening a 1.21 world on 1.20 will fail or corrupt.

**Always back up before a version upgrade.** Test the upgrade on a copy first if
the world matters.

## Moving into BisectHosting

They document this and support will help. Broad shape:

1. Buy the plan and note the panel allocation.
2. Install the matching server software via **Minecraft Tools**.
3. Upload files via SFTP.
4. Set `server-port` to the new allocation.
5. Verify `eula=true`.
6. Start and test.

Live chat will walk through it; loader and modpack setup is within their free
support scope ([01](01-bisecthosting.md#support-model)).

## Moving away

Same process reversed. Two things people lose:

- **Panel backups don't come with you.** Download them first.
- **MySQL databases must be exported separately** — they aren't in the file tree.

Note the [3-day refund window](01-bisecthosting.md#policies) if that's a factor.

## Common failures

| Symptom | Cause |
|---|---|
| World loads as brand new | Wrong folder name — check `level-name` in `server.properties` |
| Builds missing | Copied the wrong world folder, or only part of it |
| Nether/End empty | Forgot `world_nether/` and `world_the_end/` on Paper |
| Plugins load, settings gone | Copied `plugins/*.jar` but not the config subfolders |
| Nobody can connect | `server-port` not updated, or `server-ip` copied over |
| Permissions gone | LuckPerms on MySQL and the database wasn't migrated |
| `Failed to load level` | Version mismatch — usually a downgrade attempt |
| Players lost inventories | Incomplete `world/playerdata/`, or an offline↔online UUID change |

That last one deserves emphasis: **switching between `online-mode=true` and
`false` changes every player's UUID**, which orphans their playerdata,
permissions, and economy balances. If you're migrating and changing auth mode at
the same time, don't — do them separately, and expect to remap UUIDs.
