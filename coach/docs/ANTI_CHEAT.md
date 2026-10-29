# Anti-cheat compatibility

**Short version.** Sightline is built to sit in the same category as OBS,
Discord and Medal: a separate program that looks at a window through the
operating system's screen-capture API. It never touches the game. That is
the strongest position a coaching app can take with anti-cheat systems.

**Nobody can guarantee compatibility.** No publisher or anti-cheat vendor
publishes an allowlist. Their rules on "unauthorized third-party software" are
broad, and they can change. Sightline will never try to hide from anti-cheat;
it only stays out of the things anti-cheat looks for.

## What Sightline does and does not do

| | Sightline | Why it matters |
| --- | --- | --- |
| Reads game memory or files | **Never** | Memory reading is what wallhacks and ESPs do: the core thing anti-cheat detects. |
| Injects code, loads a DLL into the game, hooks DirectX/Vulkan | **Never** | Injection and render hooks are how cheats and many overlays work. Some games crash or flag them. |
| Draws over the game (overlay) | **Never** | Sightline has its own normal window; advice is spoken and shown there. |
| Sends keyboard/mouse input, aims, automates | **Never** | Input automation is the clearest cheat signal, and Activision has acted against screen-reading tools that automated aiming. |
| Kernel driver, admin rights, services | **None** | Sightline runs as an ordinary user program. |
| Sees the game | OS window capture (Windows Graphics Capture through Chromium), the same API as browser screen sharing and many streaming tools | It sees pixels the player already sees, from outside the game process. |
| Global keys (talk, mark, stop) | Standard OS hotkey registration (`RegisterHotKey`), as Discord and OBS use | It doesn't read the game's input; the OS just tells Sightline its key was pressed. Holding the talk key is recognised from the OS's own repeated reports of that key, not from a keyboard hook. |
| Reads numbers off the HUD on this PC | From the same captured frame, in the app's own process: the player marks where a number sits, and the digits' shapes are learned from the cloud model's readings | Pixels only, as before. Nothing in or about the game process is read. |
| Network | Only Anthropic's API (your key), and only with cloud features on; one-time model downloads from GitHub; for AoE IV, if you opt in, AoE4World's public match summaries after a match | Nothing talks to game servers. AoE4World is a public website built from published match data, asked only between matches. |
| Hides itself, renames its process, obfuscates | **Never** | Being transparent is the point. |

## Fair play: what the coach says, and when

Anti-cheat is only half the question. The other half is whether the *advice*
gives an unfair edge. Sightline settles that with its
[policy registry](../POLICY_REGISTRY.md):
- **Live tips during play** only happen where no other players are affected:
  campaigns, matches against AI or bots, and practice.
- **Online and ranked play:** the coach stays quiet during the match and
  reviews it afterwards. Questions you ask mid-match are answered after the
  match ends.
- **It never reveals hidden information.** It only reads what is on your own
  screen and never guesses at fog-of-war or off-screen enemies.
- **Tournament switch:** everything is off. Event rules are often stricter.

## Game notes (researched October 2026; not tested with live anti-cheat)

- **Call of Duty: Modern Warfare 4** uses RICOCHET Anti-Cheat with a kernel
  driver, and needs TPM 2.0 and Secure Boot for online play.
  - Activision's Security and Enforcement Policy bans software that
    manipulates the game, reveals information that should not be known, or
    automates actions. Sightline does none of these.
  - Activision publishes no statement either allowing or banning coaching tools.
  - Overlays and recording tools that *hook* the game are reported to cause
    crashes. Sightline doesn't hook.
  - The map stitched from the minimap uses only the minimap's pixels in the
    captured frame: what the game already shows you. Enemy pings are those
    the minimap shows, kept as places, never predicted. In online and ranked
    play the map and its pins appear only after the match.
  - Capture works best in **borderless windowed** mode.
- **Age of Empires IV** has been reported to close when certain tools are
  running (for example a trainer's overlay process).
  - Sightline is a separate program and never injects or draws an overlay,
    but test it on the current build.
  - Microsoft's terms allow automatic checks for "unauthorized hardware or
    software that enables cheating or tampering".
- **EA SPORTS FC** on PC uses EA Javelin Anticheat, which closes the game
  when software on its denylist is running.
  - EA's user agreement describes prohibited programs as ones that cheat,
    change the game's interface, or "intercept or collect information from
    the game". Sightline only captures the screen, but that wording is
    broad, so live tips are kept to offline modes against the CPU outside
    Ultimate Team; everything online, Ultimate Team (rewards) and Squad
    Battles (ranked) is post-match. FC Pro rules ban third-party "helper"
    applications in competition.
  - The radar is read from the captured frame only, like any other pixels.
- **Counter-Strike 2** uses VAC and runs in Trusted Mode, which blocks
  third-party code from injecting into the game.
  - Sightline never injects, reads memory, or needs the
    `-allow_third_party_software` launch option; it captures the screen.
  - Game State Integration is Valve's own feature for tools: the game posts
    the player's own state to a local address named in a cfg file. While
    playing it sends no positions and nothing about other players, so it
    cannot reveal hidden information. The bomb-plant update is delayed on
    purpose by the game, and the coach does not time the bomb.
  - Valve's Fair Play Guidelines forbid any automation. The coach only
    talks. Live tips only in Practice, Workshop maps and your own server
    with bots; Premier, Competitive, Wingman and everything online is
    post-match. FACEIT and tournaments: post-match only (their rules ban
    outside help during matches).
  - Grenade lineups are reference material shown in Sightline's own
    window, like a guide site: nothing is drawn in the game, nothing aims
    or throws. Valve's own lineups come from the game's practice-mode
    guide. Practice positions and aim angles that research found in a
    cheat client's files were removed, never shipped.
- **VALORANT** (Vanguard): Riot's policy forbids apps that "draw conclusions
  for you during gameplay", so Sightline is post-match only for VALORANT in
  every mode.

## If a game blocks capture

Some games mark their window as protected, which gives black frames, or run
in exclusive fullscreen.
- Sightline notices the black or frozen picture, says so, and stops
  inferring. It does **not** try to get around the block.
- Try borderless windowed mode, or capture the whole screen instead. The
  app warns that a whole screen shows everything on it.

## What to check before a release

- The installer is currently **unsigned**, so Windows SmartScreen will warn
  about an unknown publisher.
  - A code-signing certificate removes the warning and also helps with
    anti-virus reputation.
  - Set `CSC_LINK` and `CSC_KEY_PASSWORD` in the Windows build workflow.
- Run the manual QA guide ([QA.md](QA.md)) with each supported game's
  anti-cheat active:
  - launch the game;
  - capture it for a full match;
  - use the talk and mark keys;
  - confirm there are no warnings, crashes or kicks.
