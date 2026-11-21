package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [war-prep] The siege stores: what a town at war puts by in case it is shut in.
 * <ul>
 * <li><b>Food.</b> The leader keeps more days' food put by on a war footing: half a day more on its guard,
 *     four more at war (Leader.reserveDays). At war its plan is WAR (Leader.Plan): the larder kept
 *     against a siege, more hands to the fields and the water while it is under that, and no food sold to
 *     the traders on market day out of it (Market.sellSurplus). A famine is still a famine, and a larder
 *     short even by peacetime's measure is still short commons.</li>
 * <li><b>Arrows.</b> The smith fletches for the militia as well as the watch (Crafts, WarFooting.armsFor), and
 *     the couriers carry them to the armoury.</li>
 * <li><b>Bandages.</b> Paper and wool put by, one for every two folk at war (one for four on its guard): each
 *     morning that the stores are short of them, sugar cane is pressed into paper, three at a time.</li>
 * <li><b>Water.</b> A well at the heart: at war the town builds one first if it has none (WarWorks.wanted).</li>
 * </ul>
 */
public final class WarStores {

    private WarStores() {}

    /** The days' food more the leader keeps on its guard, and at war (a siege's cover). Only half a day on its
     *  guard: a feud can last for weeks, and a larder under the leader's reserve is short commons, when no child
     *  is raised (Larder), so a town merely on its guard must not be made to look hungry. */
    static final double TENSION_DAYS = 0.5, WAR_DAYS = 4.0;
    /** Sugar cane pressed into paper for bandages, at most, of a morning. */
    static final int PRESS_A_MORNING = 9;

    /** [war-prep] The days' food more the leader keeps put by on a war footing (Leader.reserveDays). */
    public static double siegeDays(@Nullable UUID village) {
        return switch (WarFooting.footing(village)) {
            case WAR -> WAR_DAYS;
            case TENSION -> TENSION_DAYS;
            case PEACE -> 0.0;
        };
    }

    /**
     * [war-prep] The leader's plan at war (Leader.morning): WAR, the larder kept against a siege; but a famine is
     * a famine, and a larder short even by the peacetime reserve is short commons, with all that goes with it.
     */
    public static Leader.Plan plan(@Nullable UUID village, Leader.Plan plan, double days, double reserve) {
        if (village == null || plan == Leader.Plan.FAMINE || WarFooting.footing(village) != Wars.Footing.WAR) return plan;
        if (plan == Leader.Plan.SHORT && days < reserve - siegeDays(village)) return plan;
        return Leader.Plan.WAR;
    }

    /** [war-prep] The food-makers the WAR plan wants (Leader.foodFactor): more while the larder is under the siege reserve. */
    public static double foodFactor(@Nullable UUID village, StationTask trade) {
        Leader.Books b = Leader.books(village);
        if (b == null || b.days() >= Leader.reserveDays(village)) return 1.0;
        return trade == StationTask.HUNT ? 1.25 : 1.5;
    }

    /** [war-prep] The food a town at war keeps back from the traders on market day (Market.sellSurplus): its siege reserve. */
    public static int foodKeep(@Nullable UUID village, int keep) {
        if (village == null || WarFooting.footing(village) != Wars.Footing.WAR) return keep;
        Leader.Books b = Leader.books(village);
        double eaten = b == null ? Leader.guessedUse(village) : Math.max(b.useAvg(), Leader.guessedUse(village) * 0.6);
        return Math.max(keep, (int) Math.ceil(eaten * Leader.reserveDays(village)));
    }

    /** The arrows the town wants put by: two quivers' worth for everybody who would fight at war, one on its guard. */
    public static int arrowsWanted(UUID village) {
        Wars.Footing f = WarFooting.footing(village);
        if (f == Wars.Footing.PEACE) return 0;
        int fighters = WarFooting.militia(village).size() + (f == Wars.Footing.TENSION ? Militia.members(village).size() : 0);
        return (f == Wars.Footing.WAR ? 32 : 16) * Math.max(1, fighters);
    }

    /** The bandages (paper and wool) the town wants put by: one for two folk at war, one for four on its guard. */
    public static int bandagesWanted(UUID village) {
        Wars.Footing f = WarFooting.footing(village);
        int folk = Math.max(1, Villages.headcount(village));
        return switch (f) {
            case WAR -> (folk + 1) / 2;
            case TENSION -> (folk + 3) / 4;
            case PEACE -> 0;
        };
    }

    static int bandages(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(Items.PAPER) || s.is(ItemTags.WOOL));
    }

    /** The morning on a war footing: paper pressed for bandages, out of the sugar cane, while there are too few. */
    static void morning(ServerLevel level, Villages.Village v, long day, Wars.Footing footing) {
        int want = bandagesWanted(v.id()), have = bandages(level, v);
        int pressed = 0;
        while (have + pressed < want && pressed < PRESS_A_MORNING
                && Crafts.stock(level, v, s -> s.is(Items.SUGAR_CANE)) >= 3 && Crafts.take(level, v, s -> s.is(Items.SUGAR_CANE), 3)) {
            Crafts.store(level, v, new ItemStack(Items.PAPER, 3));
            pressed += 3;
        }
        if (pressed > 0) Villages.tell(v.id(), day, pressed + " sugar cane pressed into paper for bandages, against the war");
    }

    /** The war page's lines on the siege stores. */
    static List<String> lines(ServerLevel level, Villages.Village v, long day) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (!WarFooting.ready(id)) return out;
        Leader.Books b = Leader.books(id);
        String food = b == null ? "no books kept yet" : String.format(Locale.ROOT, "%.1f days' food", b.days());
        out.add(String.format(Locale.ROOT, "Siege stores: %s against %.1f days kept (%.1f of them for a siege)%s; arrows %d of %d; bandages (paper and wool) %d of %d; water: %s.",
            food, Leader.reserveDays(id), siegeDays(id), Leader.plan(id) == Leader.Plan.WAR ? ", the plan WAR" : "",
            Crafts.stock(level, v, s -> s.is(Items.ARROW)) + WarWorks.inArmoury(level, id, s -> s.is(Items.ARROW)), arrowsWanted(id),
            bandages(level, v), bandagesWanted(id), WarWorks.has(id, "well") ? "the well" : "no well yet"));
        return out;
    }
}
