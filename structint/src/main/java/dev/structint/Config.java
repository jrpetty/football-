package dev.structint;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_COLLAPSE;
    public static final ModConfigSpec.BooleanValue ONLY_PLAYER_PLACED;
    public static final ModConfigSpec.BooleanValue COLLAPSE_WARNING_EFFECTS;
    public static final ModConfigSpec.IntValue COLLAPSE_WARN_DELAY_TICKS;
    public static final ModConfigSpec.DoubleValue COLLAPSE_IMPACT_DAMAGE_SCALE;

    public static final ModConfigSpec.IntValue SNOW_LOAD_LAYERS_PER_SPAN;
    public static final ModConfigSpec.IntValue SNOW_LOAD_MAX_PENALTY;
    public static final ModConfigSpec.IntValue SNOW_LOAD_IMMUNE_MIN_SPAN;
    public static final ModConfigSpec.IntValue SNOW_LOAD_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue SNOW_LOAD_SCAN_BUDGET;
    public static final ModConfigSpec.IntValue SNOW_LOAD_SCAN_RADIUS_CHUNKS;

    public static final ModConfigSpec.BooleanValue HYDRO_ENABLE;
    public static final ModConfigSpec.BooleanValue HYDRO_ENABLE_BREAK;
    public static final ModConfigSpec.BooleanValue HYDRO_EFFECTS;
    public static final ModConfigSpec.DoubleValue HYDRO_PRESSURE_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue HYDRO_WEEP_THRESHOLD;
    public static final ModConfigSpec.IntValue HYDRO_MAX_HEAD;
    public static final ModConfigSpec.IntValue HYDRO_MAX_THICKNESS;
    public static final ModConfigSpec.IntValue HYDRO_FAILURES_PER_SWEEP;
    public static final ModConfigSpec.IntValue HYDRO_SCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue HYDRO_SCAN_BUDGET;
    public static final ModConfigSpec.IntValue HYDRO_SCAN_RADIUS_CHUNKS;

    public static final ModConfigSpec.IntValue HYDRO_BODY_SAMPLE_CAP;
    public static final ModConfigSpec.IntValue HYDRO_BODY_REFERENCE;
    public static final ModConfigSpec.DoubleValue HYDRO_BODY_INFLUENCE;
    public static final ModConfigSpec.DoubleValue HYDRO_BODY_MAX_FACTOR;

    public static final ModConfigSpec.DoubleValue HYDRO_BRACING_SHARE;
    public static final ModConfigSpec.IntValue HYDRO_BRACING_REACH;
    public static final ModConfigSpec.IntValue HYDRO_BRACING_MAX_BONUS;

    public static final ModConfigSpec.DoubleValue HYDRO_ARCH_GAIN;
    public static final ModConfigSpec.IntValue HYDRO_ARCH_MAX_SETBACK;
    public static final ModConfigSpec.IntValue HYDRO_ARCH_SCAN_RANGE;

    public static final ModConfigSpec.IntValue HYDRO_RES_DIRT;
    public static final ModConfigSpec.IntValue HYDRO_RES_GENERIC;
    public static final ModConfigSpec.IntValue HYDRO_RES_WOOD;
    public static final ModConfigSpec.IntValue HYDRO_RES_STONE;
    public static final ModConfigSpec.IntValue HYDRO_RES_REINFORCED;
    public static final ModConfigSpec.IntValue HYDRO_RES_METAL;
    public static final ModConfigSpec.IntValue HYDRO_RES_SEALING;
    public static final ModConfigSpec.IntValue HYDRO_RES_BRITTLE;

    public static final ModConfigSpec.IntValue SPAN_DIRT;
    public static final ModConfigSpec.IntValue SPAN_GENERIC;
    public static final ModConfigSpec.IntValue SPAN_WOOD;
    public static final ModConfigSpec.IntValue SPAN_STONE;
    public static final ModConfigSpec.IntValue SPAN_REINFORCED;
    public static final ModConfigSpec.IntValue SPAN_METAL;

    public static final ModConfigSpec.IntValue SUPPORT_CAP;
    public static final ModConfigSpec.IntValue MAX_REGION_NODES;
    public static final ModConfigSpec.IntValue CHANGE_BUDGET_PER_TICK;
    public static final ModConfigSpec.IntValue COLLAPSE_BUDGET_PER_TICK;

    private Config() {
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("behaviour");
        ENABLE_COLLAPSE = b.comment(
                "Master switch. When false, structural state is still tracked but",
                "nothing is ever made to fall (useful for testing or creative servers).")
                .define("enableCollapse", true);
        ONLY_PLAYER_PLACED = b.comment(
                "When true, only blocks a player placed are subject to the rules;",
                "natural terrain is always stable. Strongly recommended — leave this true.",
                "WARNING: setting it false makes ALL natural terrain collapsible too, which",
                "with the 'every block is structural' rule will tear down unsupported",
                "overhangs, cave ceilings and floating islands across loaded chunks. Expert/",
                "experimental only.")
                .define("onlyPlayerPlaced", true);
        COLLAPSE_WARNING_EFFECTS = b.comment(
                "Play a 'creak' sound and a puff of crumbling particles the moment a block",
                "is judged unsupported, a beat before it actually falls.")
                .define("collapseWarningEffects", true);
        COLLAPSE_WARN_DELAY_TICKS = b.comment(
                "Ticks between the warning and the fall (20 ticks = 1 second). 0 = fall as",
                "soon as it is processed (the warning still plays).")
                .defineInRange("collapseWarnDelayTicks", 15, 0, 200);
        COLLAPSE_IMPACT_DAMAGE_SCALE = b.comment(
                "Collapsing blocks damage entities they land on, scaled by the",
                "material's structural strength (span) — stone lands like an anvil,",
                "metal hits harder, dirt barely stings. This multiplies both the",
                "damage-per-block-fallen and the damage cap. 0 disables impact damage.")
                .defineInRange("collapseImpactDamageScale", 1.0, 0.0, 10.0);

        b.comment(
                "Snow load — snow piled on a block eats into the span it can carry, so roofs",
                "need clearing, a steeper pitch, or stronger material through a hard winter.",
                "A flat penalty from depth alone: no accumulating weight, and clearing the",
                "snow restores the original span immediately.")
                .push("snowLoad");
        SNOW_LOAD_LAYERS_PER_SPAN = b.comment(
                "Snow layers that cost one block of span (vanilla snow is 1-8 layers deep).",
                "0 disables snow load entirely.")
                .defineInRange("layersPerSpan", 3, 0, 8);
        SNOW_LOAD_MAX_PENALTY = b.comment("Never take more than this many blocks of span, however deep the snow.")
                .defineInRange("maxPenalty", 3, 0, 64);
        SNOW_LOAD_IMMUNE_MIN_SPAN = b.comment(
                "Materials with a base span of at least this ignore snow entirely - with",
                "the default spans that is reinforced (12) and metal (20), while wood and",
                "stone roofs feel it.")
                .defineInRange("immuneMinSpan", 12, 0, 256);
        SNOW_LOAD_SCAN_INTERVAL_TICKS = b.comment(
                "Snow accumulates and melts without firing a block event, so laden blocks",
                "are re-checked by a periodic sweep. Ticks between sweeps (20 = 1 second).")
                .defineInRange("scanIntervalTicks", 80, 20, 12000);
        SNOW_LOAD_SCAN_BUDGET = b.comment(
                "Maximum managed blocks examined per sweep. A very large base is covered",
                "across several sweeps rather than in one spike.")
                .defineInRange("scanBudget", 4096, 64, 262144);
        SNOW_LOAD_SCAN_RADIUS_CHUNKS = b.comment(
                "Chunk radius around each player that the sweep considers. Snow only falls",
                "in ticking chunks near players, so this needs no more than that reach.")
                .defineInRange("scanRadiusChunks", 4, 1, 32);
        b.pop();
        b.pop();

        b.comment(
                "Water pressure — standing water pushes on the walls holding it back, harder the",
                "deeper it is. A dam has to be thick enough at the bottom, braced from behind, or",
                "bowed into the water. Only blocks a player placed are ever examined or broken:",
                "natural terrain is the abutment the thrust flows into, so a dam keyed into the",
                "valley walls is anchored there.",
                "",
                "Water on both sides cancels out, so a flooded chamber is safe and an air-filled",
                "one at the same depth is not — which is what makes an underwater base a build",
                "problem rather than a decoration.")
                .push("waterPressure");
        HYDRO_ENABLE = b.comment("Master switch for water pressure. False disables the sweep entirely.")
                .define("enable", true);
        HYDRO_ENABLE_BREAK = b.comment(
                "Let overloaded blocks actually burst. False keeps the warning effects but never",
                "breaks anything — useful for seeing where a build is marginal before committing.",
                "Also gated by behaviour.enableCollapse.")
                .define("enableBreak", true);
        HYDRO_EFFECTS = b.comment("Seeping-water particles while a wall is straining, and a splash when it goes.")
                .define("effects", true);
        HYDRO_PRESSURE_PER_BLOCK = b.comment(
                "Load contributed by each block of water depth. Real pressure is p = rho*g*h, so",
                "this is linear in depth and does NOT depend on how wide the reservoir is.")
                .defineInRange("pressurePerBlockOfDepth", 1.0, 0.05, 16.0);
        HYDRO_WEEP_THRESHOLD = b.comment(
                "Fraction of capacity at which a wall starts visibly seeping. 0.75 gives a",
                "decent warning before anything gives way.")
                .defineInRange("weepThreshold", 0.75, 0.1, 1.0);
        HYDRO_MAX_HEAD = b.comment("Deepest column of water that counts, in blocks. Bounds the depth probe.")
                .defineInRange("maxHead", 64, 1, 384);
        HYDRO_MAX_THICKNESS = b.comment(
                "How many blocks back from the wet face are counted as part of the wall. A wall",
                "thicker than this is already far beyond anything the water can do.")
                .defineInRange("maxThickness", 12, 1, 64);
        HYDRO_FAILURES_PER_SWEEP = b.comment(
                "Blocks allowed to burst in one sweep, worst-stressed first. Lower staggers a",
                "breach into something you can watch (and run from).")
                .defineInRange("failuresPerSweep", 24, 1, 4096);
        HYDRO_SCAN_INTERVAL_TICKS = b.comment(
                "Ticks between sweeps. Water level changes fire no block event we can rely on, so",
                "loaded walls are re-checked periodically (20 = 1 second).")
                .defineInRange("scanIntervalTicks", 40, 5, 12000);
        HYDRO_SCAN_BUDGET = b.comment("Maximum player-placed blocks examined per sweep, across all chunks.")
                .defineInRange("scanBudget", 2048, 64, 262144);
        HYDRO_SCAN_RADIUS_CHUNKS = b.comment("Chunk radius around each player that the sweep considers.")
                .defineInRange("scanRadiusChunks", 6, 1, 32);

        b.comment(
                "How much water is behind the wall. Strictly speaking a pond and an ocean of the",
                "same depth push equally hard per block — this is a deliberate gameplay term on",
                "top of that, standing in for the stored energy of a big reservoir and for the",
                "fact that a long dam carries proportionally more total force.",
                "",
                "The body is sampled outward from the wall and stopped at sampleCap, so a coastal",
                "wall measures the ocean near it rather than walking the whole ocean.")
                .push("body");
        HYDRO_BODY_SAMPLE_CAP = b.comment(
                "Water blocks examined before the sample stops and reports the cap. This is the",
                "hard bound on the cost of measuring a body.")
                .defineInRange("sampleCap", 4096, 64, 65536);
        HYDRO_BODY_REFERENCE = b.comment("Sampled volume that counts as 'ordinary'. At or below this the factor is 1.")
                .defineInRange("referenceVolume", 256, 1, 65536);
        HYDRO_BODY_INFLUENCE = b.comment("Added to the factor each time the sampled volume doubles past the reference.")
                .defineInRange("influencePerDoubling", 0.25, 0.0, 2.0);
        HYDRO_BODY_MAX_FACTOR = b.comment("Ceiling on the factor. 2.0 means a full ocean pushes twice as hard as a pond.")
                .defineInRange("maxFactor", 2.0, 1.0, 8.0);
        b.pop();

        b.comment(
                "Buttresses. A thicker section behind the wall lends its surplus to the thinner",
                "wall beside it, less the further away it is — so ribs work, spaced ribs work",
                "less well, and a uniform wall shares nothing because there is no surplus.")
                .push("bracing");
        HYDRO_BRACING_SHARE = b.comment("Fraction of a neighbour's surplus capacity that reaches this block. 0 disables.")
                .defineInRange("share", 0.5, 0.0, 1.0);
        HYDRO_BRACING_REACH = b.comment("How many blocks along the wall a buttress can lend support.")
                .defineInRange("reach", 3, 0, 16);
        HYDRO_BRACING_MAX_BONUS = b.comment("Ceiling on borrowed capacity, so one huge pier cannot hold up a whole wall.")
                .defineInRange("maxBonus", 64, 0, 4096);
        b.pop();

        b.comment(
                "Arch action. A wall bowed into the water throws its thrust sideways into its",
                "flanks instead of carrying it alone, which is why a real arch dam gets away with",
                "being thin. Measured from how far the wall sits back on either side of a block.")
                .push("arch");
        HYDRO_ARCH_GAIN = b.comment("Capacity multiplier added per block of curvature. 0 disables arch action.")
                .defineInRange("gainPerBlockOfCurve", 0.25, 0.0, 2.0);
        HYDRO_ARCH_MAX_SETBACK = b.comment("Curvature beyond this stops helping, so a deep V is not a free win.")
                .defineInRange("maxCurve", 4, 1, 32);
        HYDRO_ARCH_SCAN_RANGE = b.comment("How far along the water axis the flanking wall face is looked for.")
                .defineInRange("scanRange", 6, 1, 32);
        b.pop();

        b.comment(
                "Blocks of water depth a single block of each material holds back with nothing",
                "behind it. Stack them: a wall three blocks thick carries the sum. With the",
                "defaults, holding back 30 blocks of water needs 4 thick in stone, 2 in concrete",
                "or 8 in wood — and one block of anything is fine for a shallow pool.")
                .push("resistance");
        HYDRO_RES_DIRT = b.comment("Crumbly fill. An earth dam leaks and then goes.").defineInRange("dirt", 2, 0, 4096);
        HYDRO_RES_GENERIC = b.comment("Fallback for any other full block, including plain glass.").defineInRange("generic", 3, 0, 4096);
        HYDRO_RES_WOOD = b.comment("Logs and planks.").defineInRange("wood", 4, 0, 4096);
        HYDRO_RES_STONE = b.comment("Stone family.").defineInRange("stone", 8, 0, 4096);
        HYDRO_RES_REINFORCED = b.comment("Concrete and the reinforced beam.").defineInRange("reinforced", 16, 0, 4096);
        HYDRO_RES_METAL = b.comment("Metal blocks and the heavy girder.").defineInRange("metal", 24, 0, 4096);
        HYDRO_RES_SEALING = b.comment("#structint:pressure_sealing — purpose-built to hold water.").defineInRange("sealing", 32, 0, 4096);
        HYDRO_RES_BRITTLE = b.comment("#structint:pressure_brittle — glass, panes, bars. A viewing window stays a luxury.")
                .defineInRange("brittle", 2, 0, 4096);
        b.pop();
        b.pop();

        b.push("spans");
        b.comment("Maximum number of blocks each material may cantilever out from a support.");
        SPAN_DIRT = b.comment("Crumbly fill (dirt, gravel, sand-likes).").defineInRange("dirt", 1, 0, 256);
        SPAN_GENERIC = b.comment("Fallback for any other full block a player places.").defineInRange("generic", 2, 0, 256);
        SPAN_WOOD = b.comment("Logs and planks.").defineInRange("wood", 4, 0, 256);
        SPAN_STONE = b.comment("Stone family: stone, cobble, deepslate, bricks, stone bricks.").defineInRange("stone", 7, 0, 256);
        SPAN_REINFORCED = b.comment("Concrete and the reinforced beam.").defineInRange("reinforced", 12, 0, 256);
        SPAN_METAL = b.comment("Metal blocks and the heavy girder.").defineInRange("metal", 20, 0, 256);
        b.pop();

        b.push("performance");
        SUPPORT_CAP = b.comment("The 'infinite' reach assigned to anchors. Must exceed your largest span.")
                .defineInRange("supportCap", 64, 16, 4096);
        MAX_REGION_NODES = b.comment(
                "Largest connected structure analysed in a single local check. Structures",
                "bigger than this are treated as stable (never mass-collapsed) as a",
                "fail-safe. Bound on per-edit CPU cost.")
                .defineInRange("maxRegionNodes", 4096, 64, 200000);
        CHANGE_BUDGET_PER_TICK = b.comment("Max block-change origins re-analysed per server tick (per level).")
                .defineInRange("changeBudgetPerTick", 64, 1, 100000);
        COLLAPSE_BUDGET_PER_TICK = b.comment(
                "Max blocks made to fall per server tick (per level). Lower = more",
                "gradual, visually staggered collapses.")
                .defineInRange("collapseBudgetPerTick", 16, 1, 100000);
        b.pop();

        SPEC = b.build();
    }
}
