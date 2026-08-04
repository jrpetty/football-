# Development — Writing Plugins and Mods

For when troubleshooting turns into building. Both ecosystems use **Gradle** and
Java (or Kotlin).

## Plugin or mod?

| | Plugin | Mod |
|---|---|---|
| **Runs on** | Paper/Spigot/Purpur | Forge/NeoForge/Fabric |
| **Client needs it** | No | Usually yes |
| **Can add blocks/items** | Not really (resource pack tricks only) | Yes |
| **API stability** | Good — Bukkit API is stable across versions | Poor — breaks most versions |
| **Difficulty** | Lower | Higher |
| **Distribution** | SpigotMC, Modrinth, Hangar | CurseForge, Modrinth |

**If it's server-side behaviour, write a plugin.** Plugins are far easier to
maintain, and the Bukkit API rarely breaks between Minecraft versions while mod
APIs break constantly.

## Paper plugins

### Setup

Use the [Paper plugin template](https://github.com/PaperMC/paper-plugin-template)
or generate a Gradle project.

`build.gradle.kts`:

```kotlin
plugins {
    java
    id("io.papermc.paperweight.userdev") version "..."
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
```

**paperweight-userdev** gives you deobfuscated internal (NMS) types during
development and remaps your output so it runs against the obfuscated server. You
only need it if you're touching internals — plain Paper API doesn't.

The **Run-Task** Gradle plugin downloads a Paper server and runs it for you.
Worth setting up early; the edit-build-test loop is the whole job.

### Structure

`src/main/resources/plugin.yml`:

```yaml
name: MyPlugin
version: 1.0.0
main: com.example.myplugin.MyPlugin
api-version: '1.21'
depend: [Vault]
softdepend: [PlaceholderAPI]
commands:
  mycommand:
    description: Does a thing
    usage: /mycommand
permissions:
  myplugin.use:
    default: op
```

Main class:

```java
public class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Enabled");
        getServer().getPluginManager().registerEvents(new MyListener(), this);
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled");
    }
}
```

Listener:

```java
public class MyListener implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage("Welcome");
    }
}
```

### Rules that matter

- **Never block the main thread.** No database queries, HTTP calls, or file I/O
  in an event handler. Use `BukkitScheduler.runTaskAsynchronously`. Blocking the
  main thread is *the* way plugins destroy TPS — and it'll show up in
  [spark](07-performance.md) as your plugin sitting at the top of the sampler.
- **Bukkit API is not thread-safe.** Async tasks must hop back to the main thread
  (`runTask`) before touching worlds, entities, or players.
- **Register permissions** in `plugin.yml` so LuckPerms can see them.
- **Clean up in `onDisable`** — cancel tasks, close connections, save data.
- **Prefer the Paper API over NMS.** NMS breaks every version; Paper API doesn't.

## NeoForge mods

### Setup

Start from the [NeoForge MDK](https://github.com/neoforged/MDK) — a preconfigured
Gradle project that downloads and decompiles Minecraft, applies access
transformers and NeoForge's patches, and generates IDE run configs.

Two Gradle plugin choices:

| | Use when |
|---|---|
| **ModDevGradle** | Default. Simpler, more streamlined buildscripts. |
| **NeoGradle** | You need multiple NeoForge/Minecraft versions in one project. |

Functionally equivalent for most projects — take ModDevGradle unless you have the
multi-version need.

### Structure

`src/main/resources/META-INF/neoforge.mods.toml`:

```toml
modLoader = "javafml"
loaderVersion = "[21,)"
license = "MIT"

[[mods]]
modId = "mymod"
version = "1.0.0"
displayName = "My Mod"

[[dependencies.mymod]]
    modId = "neoforge"
    type = "required"
    versionRange = "[21.1.0,)"
    ordering = "NONE"
    side = "BOTH"
```

Main class:

```java
@Mod(MyMod.MODID)
public class MyMod {
    public static final String MODID = "mymod";

    public MyMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) { }
}
```

### Sidedness — the thing that catches everyone

Mods run on **client** and **server**, and code that references client classes
crashes on a dedicated server.

```java
if (FMLEnvironment.dist == Dist.CLIENT) { }
```

Keep client-only code in separate classes, guarded, and never referenced from
common code. **This is the bug behind most `NoClassDefFoundError:
net/minecraft/client/...` crashes** — including the ones you'll see from users
who uploaded a client pack ([06](06-crash-reports.md#client-mods-on-a-server)).

### Mixins

For patching Minecraft's own code. Powerful and fragile.

```java
@Mixin(SomeVanillaClass.class)
public class SomeVanillaClassMixin {
    @Inject(method = "someMethod", at = @At("HEAD"), cancellable = true)
    private void onSomeMethod(CallbackInfo ci) { }
}
```

Mixins are the reason [mixin errors](06-crash-reports.md#mixins) are so common in
crash reports — every mixin is a bet that the target code looks the way you
expect, and that bet loses on version changes and when another mod patches the
same method. Use them only when there's no API path.

## Testing

- **Both sides.** Dedicated server *and* client. A mod that works in singleplayer
  can fail instantly on a server.
- **Fresh world**, then an existing one.
- **With other mods** — conflicts only appear in company.
- **Profile with spark** before releasing. Your plugin sitting at the top of
  someone else's sampler is how you get a reputation.

## Publishing

| Platform | For |
|---|---|
| [Modrinth](https://modrinth.com) | Both. Open source, good API, growing fast. |
| [CurseForge](https://curseforge.com) | Mods. Largest audience, launcher integration. |
| [Hangar](https://hangar.papermc.io) | Paper plugins. Official. |
| [SpigotMC](https://spigotmc.org/resources/) | Plugins. Large legacy audience. |

Ship a `LICENSE`, a changelog, and the **exact** Minecraft and loader versions
supported. Version-range ambiguity is the top cause of "your mod is broken"
reports that aren't.

## Docs

- [PaperMC developer docs](https://docs.papermc.io/paper/dev/)
- [NeoForged docs](https://docs.neoforged.net/)
- [Fabric wiki](https://wiki.fabricmc.net/)
- [Velocity plugin docs](https://docs.papermc.io/velocity/dev/creating-your-first-plugin/)
