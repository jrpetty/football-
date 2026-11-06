package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [perks] The perks set out for the pictures (tools/realworld/smoke.py's perks_stage).
 *
 * <p>Each is set out on a stage of its own where it is asked for: on the ground, or in clear air above it if asked
 * there (as the smoke does, so nothing of the town is cleared away for it).
 *
 * <p>{@link #showcase} ("/village perks wonders show"): the ten wonders of the world side by side on ground levelled
 * for them, from a palette and not the stores (as the showcase's buildings are), claimed by nobody, so each can be
 * looked at; "WONDER name x y z" and a view of each.
 *
 * <p>{@link #stage} ("/village perks stage"): the nearest town given a history to photograph: two tiers of every
 * branch studied (the first of each pair, so the other shows closed), its Faith branch to the top and the
 * Cathedral (or the first wonder no other town holds) raised on a stage beside it, the wonder claimed for it as if
 * built; a civic set to study; its leader five days in office with three skills of the line its heart leans to; a
 * past reign's legacy and its plaque before the hall; every grown folk's quirks rolled. The words of it, and views:
 * "VIEW name x y z ax ay az" (the eyes, then what they look at).
 */
final class PerksStage {

    private PerksStage() {}

    /** The wonders in the order the stage would show them, its own first. */
    private static final Wonders.Wonder[] PREFERRED = { Wonders.Wonder.CATHEDRAL, Wonders.Wonder.GREAT_LIGHTHOUSE,
        Wonders.Wonder.GRAND_LIBRARY, Wonders.Wonder.GREAT_FORGE, Wonders.Wonder.SKY_GARDEN, Wonders.Wonder.ARENA,
        Wonders.Wonder.GRAND_BAZAAR, Wonders.Wonder.OBSERVATORY, Wonders.Wonder.CLOCKWORK_GATE, Wonders.Wonder.FOUNDERS_COLOSSUS };

    /** The tallest wonder's top, above its ground (the Great Lighthouse's lamp is at 34): the air cleared to here. */
    private static final int HEADROOM = 40;

    static List<String> showcase(ServerLevel level, BlockPos start) {
        List<String> out = new ArrayList<>();
        int x0 = start.getX(), z0 = start.getZ();
        level.getChunk(x0 >> 4, z0 >> 4);
        int y = Math.max(start.getY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0));   // in clear air if asked there
        // Two rows of five, eighteen apart along the row and thirty between the rows: every wonder fits a great lot
        // (eleven by twenty-one), so none touches its neighbour.
        int step = 18, rows = 30;
        ground(level, x0 - 10, x0 + 4 * step + 10, z0 - 14, z0 + rows + 14, y);
        Wonders.Wonder[] all = Wonders.Wonder.values();
        for (int i = 0; i < all.length; i++) {
            Wonders.Wonder w = all[i];
            BlockPos at = new BlockPos(x0 + (i % 5) * step, y, z0 + (i / 5) * rows);
            BuildGoal.stamp(level, w.structure, at, Direction.NORTH, 13, Showcase.painter(Showcase.PALETTES[i % Showcase.PALETTES.length]));
            out.add("WONDER " + w.structure + " " + at.getX() + " " + at.getY() + " " + at.getZ());
            out.add(view("wonder-" + w.structure, at, w));
        }
        return out;
    }

    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        // The wonder: the first no other town holds.
        Wonders.Wonder wonder = PREFERRED[0];
        for (Wonders.Wonder w : PREFERRED) {
            Wonders.Claim k = Wonders.claim(w);
            if (k == null || k.village().equals(id)) { wonder = w; break; }
        }
        // The research: two tiers of every branch, the wonder's branch to its top, the first of each pair taken.
        Map<CityTree.Branch, Integer> upTo = new EnumMap<>(CityTree.Branch.class);
        for (CityTree.Branch b : CityTree.Branch.values()) upTo.put(b, 2);
        upTo.put(wonder.civic.branch, wonder.civic.branch.tiers());
        upTo.put(CityTree.Branch.TRADE, Math.max(upTo.get(CityTree.Branch.TRADE), 5));
        List<CityTree.Civic> order = new ArrayList<>(List.of(CityTree.Civic.values()));
        order.sort(Comparator.comparingInt(c -> c.tier));
        int studied = 0;
        for (CityTree.Civic c : order) {
            if (c.tier > upTo.get(c.branch) || CityTree.has(id, c) || CityTree.locked(id, c)) continue;
            boolean ready = true;
            for (CityTree.Civic b : c.befores()) ready &= CityTree.has(id, b);
            if (!ready) continue;
            CityTree.doneForTests(id, c, Math.max(0, day - 1));
            studied++;
        }
        out.add("RESEARCH " + studied + " civics marked done; " + CityTree.done(id).size() + " of " + CityTree.ALL);
        CityTree.Civic next = null;
        for (CityTree.Civic c : order) if (CityTree.open(id, c) && !c.wonder()) { next = c; break; }
        if (next != null) out.add("STUDY " + CityTree.pick(level, id, next, day));
        // The wonder on a stage beside the town, on the town's books, and claimed: the world told.
        int x = at.getX(), z = at.getZ();
        level.getChunk(x >> 4, z >> 4);
        int y = Math.max(at.getY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));      // in clear air if asked there
        ground(level, x - 12, x + 12, z - 15, z + 16, y);
        BlockPos anchor = new BlockPos(x, y, z);
        BuildGoal.stamp(level, wonder.structure, anchor, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        if (!Villages.hasBuilt(id, wonder.structure)) Ledger.built(id, wonder.structure, anchor, Direction.NORTH);
        Wonders.raised(id, wonder.structure, level.getGameTime());
        out.add("WONDER " + wonder.structure + " " + x + " " + y + " " + z + " " + Wonders.stateWords(id, wonder.civic));
        out.add(view("perks-wonder", anchor, wonder));
        // The leader: its reign, its skills, a past reign's legacy and the plaque for it.
        out.addAll(Reigns.stage(level, v, "Bramble", Reigns.Deed.BUILD, day));
        Culture.Spot plaque = Plaques.upForStage(level, v, Plaques.Site.LEGACY);
        if (plaque != null) {
            BlockPos s = plaque.at(), eye = s.relative(plaque.facing(), 4);
            out.add("VIEW perks-plaque " + eye.getX() + " " + (eye.getY() + 1) + " " + eye.getZ() + " " + s.getX() + " " + (s.getY() + 1) + " " + s.getZ());
        }
        // The folk's quirks.
        int shown = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isBaby()) continue;
            Quirks.ensure(f);
            String c = Quirks.cardLine(f);
            if (!c.isEmpty() && shown++ < 6) out.add("QUIRKS " + f.displayNameCap() + ": " + c);
        }
        String known = Quirks.knownFor(id);
        if (!known.isEmpty()) out.add("KNOWN " + known);
        return out;
    }

    /** Level ground for the stage, with clear air over it to the top of the tallest wonder. */
    private static void ground(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        Showcase.stage(level, x0, x1, z0, z1, y);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int h = 25; h <= HEADROOM; h++) {
                    BlockPos p = new BlockPos(x, y + h, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    /**
     * A view of a wonder from the street before it, off to one side and above, so its front and a side both show,
     * far enough back for the whole of it: further for the tall ones.
     */
    private static String view(String name, BlockPos at, Wonders.Wonder w) {
        int tall = switch (w) {
            case GREAT_LIGHTHOUSE -> 34;
            case CATHEDRAL, GRAND_BAZAAR, FOUNDERS_COLOSSUS, CLOCKWORK_GATE -> 26;
            case ARENA -> 10;
            default -> 20;
        };
        int back = 16 + tall / 2, side = 10 + tall / 4;
        return "VIEW " + name + " " + (at.getX() + side) + " " + (at.getY() + tall / 2 + 2) + " " + (at.getZ() + back) + " "
            + at.getX() + " " + (at.getY() + tall / 3) + " " + at.getZ();
    }
}
