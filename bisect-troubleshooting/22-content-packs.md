# Resource Packs and Datapacks

Two ways to change a server without mods. Both work on vanilla clients.

| | Resource pack | Datapack |
|---|---|---|
| **Changes** | Textures, sounds, models, fonts | Recipes, loot tables, advancements, functions, worldgen |
| **Runs on** | Client | Server |
| **Players install** | Auto-prompted on join | Nothing — server-side only |
| **Location** | Hosted at a URL | `world/datapacks/` |

## Resource packs

### Setup

Three properties in `server.properties`:

```properties
resource-pack=https://example.com/pack.zip
resource-pack-sha1=<40-character hex hash>
require-resource-pack=false
```

| Property | Meaning |
|---|---|
| `resource-pack` | **Direct download URL.** Must start the download immediately. |
| `resource-pack-sha1` | SHA-1 of that exact file. Optional but strongly recommended. |
| `require-resource-pack` | `true` disconnects players who decline |
| `resource-pack-prompt` | Custom message (JSON text component) |

Edit while the server is **stopped**, or it'll be overwritten on shutdown.

### The URL must be a direct download

Opening it in a browser must start the file download — **no preview page, no
landing page, no "click here to download".** This is the single most common
failure.

| Host | Direct link |
|---|---|
| **Dropbox** | Change `?dl=0` to `?dl=1` |
| **Google Drive** | Awkward. Use `https://drive.google.com/uc?export=download&id=<FILE_ID>` — often breaks on large files due to the virus-scan interstitial. **Prefer something else.** |
| **GitHub** | Use the **raw** URL, or a release asset |
| **Cloudflare R2 / S3** | Works well. Good for large packs. |
| **Dedicated MC pack hosts** | Purpose-built, generate the SHA-1 for you |

### The SHA-1

A fingerprint of the exact zip. The client verifies the download matches.

```bash
sha1sum pack.zip          # Linux
shasum -a 1 pack.zip      # macOS
certutil -hashfile pack.zip SHA1    # Windows
```

**Regenerate the hash every time you change the pack.** A stale hash is the
second most common failure — the client downloads the new file, sees a mismatch,
and rejects it. Symptom: the pack silently fails to apply for everyone after an
update.

### Failures

| Symptom | Cause |
|---|---|
| Pack never prompts | URL not a direct download, or `resource-pack` empty |
| "Failed to download" | URL unreachable, or file moved |
| Downloads then rejects | **SHA-1 mismatch** — regenerate it |
| Works for some players | CDN caching or partial propagation |
| Everyone kicked on join | `require-resource-pack=true` with a broken pack. **Set it false while debugging.** |
| Pack applies, textures missing | Pack format version wrong for the MC version |

`pack.mcmeta` must declare the right `pack_format` for your Minecraft version.
Wrong format = pack loads but assets don't apply.

## Datapacks

Server-side. No player action needed.

### Install

1. Drop the datapack `.zip` (or folder) into `world/datapacks/`.
2. `reload` in console, or restart.
3. Verify with `datapack list`.

```
datapack list
datapack list available
datapack list enabled
datapack enable "file/mypack.zip"
datapack disable "file/mypack.zip"
```

**`reload` is safe for datapacks** — unlike plugin `/reload`, which is a known
source of leaks. Different mechanism entirely.

### What they can do

- Custom crafting recipes
- Loot tables — mob drops, chest contents
- Advancements
- Functions — command scripts on triggers or schedules
- Predicates and item modifiers
- World generation — biomes, structures, dimensions
- Tags — grouping blocks/items for other rules

Substantial gameplay change with **zero client installation** is the appeal. For
a friends server that wants something different without a modpack, datapacks are
badly underrated.

### Sources

[Vanilla Tweaks](https://vanillatweaks.net) (excellent, modular),
[Modrinth datapacks](https://modrinth.com/datapacks),
[Planet Minecraft](https://www.planetminecraft.com/data-packs/).

### Failures

| Symptom | Cause |
|---|---|
| `Failed to load datapacks, can't proceed with server load` | **A malformed datapack blocks startup.** Remove it. |
| Datapack listed but does nothing | `pack_format` wrong for the MC version |
| Recipes don't appear | Needs `reload` or a rejoin |
| Broke after an MC update | Datapack format changed between versions |

That first one matters: **a bad datapack can prevent the server booting
entirely.** If you see it in a startup failure, that's not noise — pull the most
recently added datapack out of `world/datapacks/` and retry.

Datapacks live **inside the world folder**, so they travel with world backups and
migrations. Convenient, but it also means a corrupt datapack survives a world
restore.

## Server-side "modding" without mods

Combined, these get surprisingly far on a vanilla client:

| Want | Use |
|---|---|
| Custom textures | Resource pack |
| Custom recipes/loot | Datapack |
| Custom items with custom models | Resource pack + datapack (custom model data) |
| New mechanics | Datapack functions, or a plugin |
| New blocks/entities | **Needs mods.** No way around it. |

Custom model data is the standard trick: a datapack gives an item a numeric tag,
and the resource pack maps that number to a different model. That's how most
"custom items" on vanilla-compatible servers work.
