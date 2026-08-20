/**
 * Step-by-step walkthroughs for the faults System Health finds.
 *
 * A remedy paragraph tells someone what to do. It does not tell them whether
 * they need a screwdriver, whether this is a fifteen-minute job or an
 * afternoon, what the thing they are looking for is CALLED in their particular
 * BIOS, or how to know afterwards whether it worked. Those are the details
 * that decide whether a fix actually gets made, and a paragraph that omits
 * them is advice rather than help.
 *
 * Every guide carries what could go wrong. Not as legal cover — because the
 * failure modes here are real and specific, and someone who knows an unstable
 * memory profile looks like random crashes will diagnose it in a minute rather
 * than an evening.
 */

export type Difficulty = 'settings' | 'bios' | 'inside-the-case' | 'purchase';

export interface FixStep {
  /** The instruction. Imperative, one action. */
  do: string;
  /** Detail that stops the step going wrong, where there is any. */
  detail?: string;
}

export interface FixGuide {
  /** What this actually is, in one line. */
  summary: string;
  difficulty: Difficulty;
  /** Rough working time, excluding shopping. */
  minutes: number;
  /** Tools and parts to have to hand BEFORE starting. */
  needs: string[];
  steps: FixStep[];
  /** How to know it worked. Every guide has one — a fix you cannot verify is a hope. */
  verify: string;
  /** Real failure modes, so they are recognisable rather than mysterious. */
  risks: string[];
}

export const DIFFICULTY_LABEL: Record<Difficulty, string> = {
  settings: 'software only — nothing to open',
  bios: 'a BIOS setting — a reboot, no tools',
  'inside-the-case': 'inside the case — tools and care needed',
  purchase: 'requires buying something',
};

/**
 * Keyed by finding id. A finding with no guide falls back to its remedy
 * paragraph, which is why the paragraphs stay useful on their own.
 */
export const FIX_GUIDES: Record<string, FixGuide> = {
  'mem-single-channel': {
    summary: 'Add a second memory stick so the controller can use both channels.',
    difficulty: 'inside-the-case',
    minutes: 15,
    needs: [
      'A second stick matching the one you have — same capacity, same speed, ideally the same model',
      'A Phillips screwdriver for the side panel',
      'The motherboard manual, or its model number to look up the slot diagram',
    ],
    steps: [
      { do: 'Shut down, switch off at the wall, and hold the power button for five seconds.', detail: 'This drains the residual charge. Modern boards keep the RAM slots live while the PSU is switched on at the wall.' },
      { do: 'Open the side panel and find the memory slots beside the CPU cooler.', detail: 'There will be two or four. If four, they are usually colour-paired.' },
      { do: 'Check the manual for which pair to use. Do not guess.', detail: 'On almost every four-slot board it is slots 2 and 4 counting away from the CPU — but "almost every" is not "every", and the wrong pair gives you single channel again with both sticks installed.' },
      { do: 'Push the retaining clips down, line up the notch, and press the stick in firmly until both clips snap back.', detail: 'It takes more force than feels right. A stick that is not fully seated is the most common cause of a machine that will not POST after a memory upgrade.' },
      { do: 'Close up, power on, and go straight into the BIOS.', detail: 'Check it reports the full capacity AND dual channel before booting Windows.' },
      { do: 'Re-enable XMP or EXPO if it was on before.', detail: 'Adding a stick often resets the memory profile to defaults.' },
    ],
    verify:
      'Task Manager → Performance → Memory shows "Slots used: 2 of 4" and the speed you expect. Then re-run a benchmark: the gain shows up in CPU-bound titles and barely at all in GPU-bound ones, so test something like Counter-Strike rather than Cyberpunk at 4K.',
    risks: [
      'Mismatched sticks run at the slower one\'s speed and timings, so a fast new stick beside a slow old one gives you dual channel at the old speed. Still worth it.',
      'Mixing kits can be unstable even when both are individually fine. If the machine starts crashing randomly, run MemTest86 before blaming anything else.',
      'Four sticks clock lower than two on every platform in this catalogue. If you are going from one stick to four, expect to lose some speed to gain the channel.',
    ],
  },

  'mem-xmp-off': {
    summary: 'Turn on the memory profile the kit was sold with.',
    difficulty: 'bios',
    minutes: 10,
    needs: ['Nothing — this is one BIOS setting and a reboot'],
    steps: [
      { do: 'Reboot and press Delete or F2 as the machine starts.', detail: 'Which key varies. The splash screen usually says. Mash it from the moment the fans spin.' },
      { do: 'Find the memory profile setting. It is called XMP on Intel boards and EXPO on AMD ones.', detail: 'It is usually on the first page or under "OC" / "Tweaker" / "AI Tweaker" / "Extreme Tweaker" depending on the vendor. Some boards call it "Memory Profile" or "D.O.C.P".' },
      { do: 'Select Profile 1, not Auto.', detail: 'Profile 2 exists on some kits and is usually a slower fallback. Take Profile 1 first.' },
      { do: 'Save and exit. The machine will restart, possibly twice, and may sit on a black screen for up to thirty seconds while it trains the memory.', detail: 'That is normal on the first boot after enabling a profile. Do not power-cycle it during this.' },
      { do: 'Once in Windows, confirm the new speed in Task Manager → Performance → Memory.' },
    ],
    verify:
      'Task Manager shows the rated speed rather than the JEDEC default. Then run MemTest86 for at least one full pass — an unstable profile is worse than a slow one, and it will not announce itself.',
    risks: [
      'If the machine will not POST afterwards, clear CMOS: the board has a jumper or a button, or you can pull the coin cell for a minute with the power off at the wall. Settings return to default and nothing is damaged.',
      'A profile that boots but is unstable shows up as random crashes, corrupt downloads and games that close without a message — symptoms nobody associates with memory. Test before you trust it.',
      'Some kits genuinely do not run their rated profile on some boards, especially four-stick configurations. Dropping one speed step below rated is a normal outcome, not a defeat.',
    ],
  },

  'mem-capacity': {
    summary: 'Add memory so the system stops paging during play.',
    difficulty: 'purchase',
    minutes: 20,
    needs: ['Memory matching your existing kit, or a full replacement kit', 'A screwdriver'],
    steps: [
      { do: 'Check what you have: Task Manager → Performance → Memory shows speed, slots used and form factor.' },
      { do: 'Decide between adding and replacing.', detail: 'Adding is cheaper and risks a mismatch. Replacing with a matched two-stick kit is more expensive and always works. If your current kit is more than a couple of years old, replacing is usually the better call.' },
      { do: 'Buy 32GB as a two-stick kit if you are replacing.', detail: 'Two sticks, not four — a 2x16 kit clocks higher and is more stable than 4x8 on every platform here.' },
      { do: 'Fit it in the correct slot pair per the manual, then enable XMP or EXPO.' },
    ],
    verify: 'Play the game that stuttered worst for twenty minutes and watch Task Manager. If committed memory no longer exceeds physical, the stutter should be gone.',
    risks: [
      'Capacity fixes stutter, not average frame rate. If you were expecting a higher number in the corner, you will be disappointed even though the game feels better.',
      'Mixing an old kit with a new one drops both to the slower profile.',
    ],
  },

  'pcie-width': {
    summary: 'Move the graphics card to the slot wired directly to the CPU.',
    difficulty: 'inside-the-case',
    minutes: 20,
    needs: ['A Phillips screwdriver', 'The motherboard manual, for its lane-sharing table'],
    steps: [
      { do: 'Read the manual first, specifically the PCIe and M.2 lane-sharing table.', detail: 'This is the step people skip and it is the one that matters. On many boards, populating a particular M.2 socket disables lanes on the top PCIe slot. If that is your situation, moving the card will not help — moving the SSD will.' },
      { do: 'Shut down, switch off at the wall, hold the power button for five seconds.' },
      { do: 'Check which slot the card is in. The top slot, nearest the CPU, is the one with full lanes on nearly every board.' },
      { do: 'If it is already in the top slot, the lanes are being shared. Move the M.2 drive to a socket the manual says does not share, or accept the loss.' },
      { do: 'If it is in a lower slot, unscrew the bracket, release the retention clip at the back of the slot, and move it up.', detail: 'The clip needs a deliberate push. Do not lever the card out against it — large cards flex their PCBs and that is how a slot gets damaged.' },
      { do: 'Reconnect the power cables and make sure they are fully clicked in.' },
    ],
    verify:
      'GPU-Z shows the link in its "Bus Interface" field. Click the "?" beside it to run its render test, which forces the link to full speed — an idle machine downclocks the link and reads low even when it is fine.',
    risks: [
      'A card in a lower slot may have been put there for clearance. Check it does not now foul the cooler, the RAM or the front fans.',
      'Some boards genuinely have no full-speed second slot, and some cases cannot fit a large card in the top slot. Neither is fixable by moving things.',
      'The idle-downclock behaviour above catches almost everyone. Do not conclude anything from a reading taken at the desktop.',
    ],
  },

  'storage-hdd': {
    summary: 'Move the games to solid-state storage.',
    difficulty: 'purchase',
    minutes: 45,
    needs: ['An SSD with room for the games you actually play', 'A screwdriver, and a spare SATA cable if it is a 2.5" drive'],
    steps: [
      { do: 'Check what your board takes: an M.2 socket if it has a free one, otherwise a 2.5" SATA drive.', detail: 'For gaming the difference between SATA and NVMe is small. The difference between either and a hard drive is enormous. Buy on capacity, not on the headline speed.' },
      { do: 'Fit the drive, then in Windows: Disk Management → initialise as GPT → new simple volume.' },
      { do: 'In Steam: Settings → Storage → add the new drive as a library folder.' },
      { do: 'Move games rather than reinstalling them: right-click a game → Properties → Installed Files → Move Install Folder.', detail: 'This is far quicker than re-downloading and keeps saves and settings intact.' },
      { do: 'Move the biggest open-world titles first. They are where streaming stalls hurt most.' },
    ],
    verify:
      'The improvement is in traversal stutter and texture pop-in, not in the average frame rate. Fly or drive quickly across a large map before and after — that is the test that shows it.',
    risks: [
      'This will barely change your average frame rate. If that is what you are measuring, it will look like it did nothing while the game plays noticeably better.',
      'Leave 10–15% of the drive free. SSDs slow down markedly when nearly full.',
    ],
  },

  'thermal-throttle': {
    summary: 'Get the heat out, in order of effect per pound spent.',
    difficulty: 'inside-the-case',
    minutes: 60,
    needs: [
      'Compressed air or a soft brush',
      'Isopropyl alcohol and a lint-free cloth, if you are re-pasting',
      'Thermal paste, if you are re-pasting',
      'A screwdriver',
    ],
    steps: [
      { do: 'Start with dust. Filters, the CPU heatsink fins, and the graphics card fins.', detail: 'Hold the fans still while you blow air through them. Spinning a fan with compressed air can generate enough voltage to damage it, and a fan is easier to hold than to replace.' },
      { do: 'Check the airflow direction. Front and bottom fans should draw in, rear and top should blow out.', detail: 'A fan fitted backwards is common and costs several degrees. The arrow is moulded into the fan frame.' },
      { do: 'Make sure the front intake is not blocked by a closed glass panel, a desk wall or a rug.', detail: 'A case with a solid front and no gap is a box, whatever its fans are doing.' },
      { do: 'If it is still hot and the machine is more than about three years old, re-paste the CPU.', detail: 'Clean both surfaces with isopropyl, apply a pea-sized amount in the centre, and let the cooler spread it. Do not spread it by hand.' },
      { do: 'Only then consider a better cooler or case.', detail: 'The three steps above are free or nearly free and usually recover most of it. A cooler cannot fix a case that has no air moving through it.' },
    ],
    verify:
      'Run a game for twenty minutes with HWiNFO64 open and watch the sustained clock, not the peak. The number to compare is the clock after fifteen minutes, before and after. Peak clocks look identical on a throttling machine and a healthy one.',
    risks: [
      'Removing a cooler that has been on for years can pull the CPU out of its socket with it on AM4 boards. Warm the machine up first with a few minutes of load, and twist the cooler gently rather than lifting straight up.',
      'Too much paste is worse than too little. A pea, not a stripe.',
      'If a fan is dead rather than dusty, no amount of cleaning helps. Check every fan actually spins under load.',
    ],
  },

  'psu-transient': {
    summary: 'Replace the power supply with one sized for the spikes, not the average.',
    difficulty: 'purchase',
    minutes: 60,
    needs: ['A PSU of the recommended wattage from a reputable maker', 'A screwdriver', 'Cable ties'],
    steps: [
      { do: 'Buy on the wattage this report recommends, not the sustained draw.', detail: 'Modern graphics cards spike far above their rated power for microseconds. It is the spike that trips a supply\'s protection, and an average-sized unit shuts the machine off under load while looking exactly like a faulty card.' },
      { do: 'Prefer an ATX 3.x unit if your card uses a 12V-2x6 connector.', detail: 'These are specified to ride out exactly these transients, and it saves an adapter.' },
      { do: 'Photograph every cable connection before you remove anything.', detail: 'Modular PSU cables are NOT interchangeable between makes or even between models from the same make. Using the old cables with a new unit can destroy hardware.' },
      { do: 'Swap the unit, using only the cables that came in the box with it.' },
      { do: 'Connect each PCIe power cable to the card as a separate run rather than daisy-chaining one cable\'s two connectors.', detail: 'Daisy-chaining is within spec and still worse under transients.' },
    ],
    verify: 'The shutdowns stop. There is no benchmark for this one — if the machine was cutting out under load and no longer does, that was it.',
    risks: [
      'Reusing old modular cables with a new PSU is the single most expensive mistake in this whole document. The connectors fit; the pinouts differ.',
      'A very cheap high-wattage unit can be worse than a good lower-wattage one. The wattage on the box is a claim, not a measurement.',
    ],
  },

  'driver-age': {
    summary: 'Update the graphics driver from the vendor, not from Windows Update.',
    difficulty: 'settings',
    minutes: 20,
    needs: ['Nothing'],
    steps: [
      { do: 'Download the current driver from the GPU vendor directly.', detail: 'Windows Update lags by months and sometimes ships a version older than the one you have.' },
      { do: 'Run the installer and choose the clean-install option if it offers one.', detail: 'This clears accumulated profile cruft, which is the usual cause of a "the update made it worse" experience.' },
      { do: 'Reboot even if it does not ask you to.' },
      { do: 'Note the version somewhere. If something regresses, you want to know what you came from.' },
    ],
    verify: 'Re-run the same benchmark. In most titles nothing will change. In a recently released one it can be a very large difference — that asymmetry is exactly why driver age is cheap to rule out and worth ruling out.',
    risks: [
      'Newer is not always better. Regressions happen, and rolling back is a normal thing to do rather than an admission of anything.',
      'Skip third-party "driver cleaner" tools unless you have a specific problem. They solve a rare failure and cause common ones.',
    ],
  },

  uptime: {
    summary: 'Reboot before drawing any conclusions.',
    difficulty: 'settings',
    minutes: 5,
    needs: ['Nothing'],
    steps: [
      { do: 'Restart — a full restart, not shut down and power on.', detail: 'With Fast Startup enabled, shutting down hibernates the kernel and carries the accumulated state across. Restart does not.' },
      { do: 'Let it settle for a couple of minutes before measuring. Windows does indexing and update work immediately after boot.' },
      { do: 'Re-run whatever measurement prompted this.' },
    ],
    verify: 'If the numbers improve after a reboot, nothing was wrong with the hardware and everything above this finding should be re-checked against the new figures.',
    risks: ['None. This is the cheapest thing on the list and it should be done before acting on anything else.'],
  },
};

export function guideFor(findingId: string): FixGuide | undefined {
  return FIX_GUIDES[findingId];
}
