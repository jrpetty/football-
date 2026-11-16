package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [access-3] A building taken up again, and a kind of block it cannot lay a single one of.
 *
 * <p>A builder that ran out partway (the flats' parapet, short of stone) went back to the stores for the next load, and
 * its pack, still full of the roof's stairs and slabs it had cut for the top of the building, counted as a load: so it set
 * off again, came to the parapet with no stone, "ran out of building blocks" having laid nothing, and went back, for ever;
 * and as it never said it was short, nobody brought the stone. Now, before it sets off on a building already begun, it
 * looks at the drawing's cells still empty: a kind of block among them (a wall's stone, a floor's boards, a frame's logs)
 * that nothing in its pack will do for, as the builder itself would choose (BuildGoal.choose), is a load not worth
 * walking out with: it says what it is short of, and the town knows.
 */
public final class BuildShort {

    private BuildShort() {}

    /** The kinds of block a builder lays from its own choice of material (not earth, not a hired hand's plain block). */
    private static final Set<Blueprints.Style> CHOSEN = EnumSet.of(Blueprints.Style.FOUNDATION, Blueprints.Style.WALL_LOW,
        Blueprints.Style.MASONRY, Blueprints.Style.BRICK, Blueprints.Style.FLOOR, Blueprints.Style.WALL, Blueprints.Style.POST,
        Blueprints.Style.BEAM_ACROSS, Blueprints.Style.BEAM_ALONG, Blueprints.Style.ROOF_BLOCK, Blueprints.Style.ROOF_STAIR,
        Blueprints.Style.ROOF_STAIR_TOP, Blueprints.Style.ROOF_SLAB, Blueprints.Style.ROOF_SLAB_TOP, Blueprints.Style.STONE_SLAB,
        Blueprints.Style.STONE_STAIR);

    /**
     * For a building already begun on this ground: the first kind of block among its empty cells the builder could lay
     * none of with what it carries, in words; null if there is none (or the building is not begun: a first load goes as
     * it always did).
     */
    @Nullable
    static String nothingFor(ServerLevel level, @Nullable UUID town, String project, Villages.Site site, List<ItemStack> pack) {
        List<BuildGoal.Placement> plan = BuildGoal.plan(project, site.anchor(), site.facing(), site.radius());
        Set<Blueprints.Style> empty = EnumSet.noneOf(Blueprints.Style.class);
        boolean begun = false;
        for (BuildGoal.Placement p : plan) {
            if (p.part() != BuildGoal.Part.BLOCK || !CHOSEN.contains(p.style())) continue;
            if (!level.isLoaded(p.pos())) return null;
            BlockState st = level.getBlockState(p.pos());
            if (toLay(st, p)) empty.add(p.style());
            else begun |= laid(st);
        }
        if (!begun) return null;
        for (Blueprints.Style s : empty) {
            if (BuildGoal.choose(town, s, pack) == null) return s.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        }
        return null;
    }

    /**
     * For a building already begun on this ground: how many of each kind of block its empty cells still want (what
     * BuildGoal.styleCounts counts of the whole drawing). Null if it is not begun or not all loaded: the whole drawing.
     */
    @Nullable
    static java.util.Map<Blueprints.Style, Integer> stillStyles(ServerLevel level, String project, Villages.Site site) {
        java.util.Map<Blueprints.Style, Integer> left = new java.util.EnumMap<>(Blueprints.Style.class);
        boolean begun = false;
        for (BuildGoal.Placement p : BuildGoal.plan(project, site.anchor(), site.facing(), site.radius())) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            if (!level.isLoaded(p.pos())) return null;
            BlockState st = level.getBlockState(p.pos());
            if (toLay(st, p)) left.merge(p.style(), 1, Integer::sum);
            else if (CHOSEN.contains(p.style())) begun |= laid(st);
        }
        return begun ? left : null;
    }

    /** A cell its builder will lay: nothing there, or earth or something foreign where its block goes (it comes out). */
    private static boolean toLay(BlockState st, BuildGoal.Placement p) {
        return BuildGoal.soft(st) || Restore.judge(st, p) == Restore.Kind.WRONG;
    }

    /** A cell with a block laid in it, not the ground the world made (a lot on a hillside is not a building begun). */
    private static boolean laid(BlockState st) {
        return !BuildGoal.soft(st) && !Restore.natural(st) && !Restore.earthy(st);
    }
}
