package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-prep] The town's militia: its able grown folk, enrolled when it goes on its guard, who keep their
 * own trades and drill for the watch.
 * <ul>
 * <li><b>Enrolled.</b> On its guard a town enrols a quarter of its able grown folk, at war a third (and two
 *     at the least in a small town): not the old, not the watch (it is the watch), not the elder, the
 *     storekeeper, the banker or the teacher, and not the farmers and fishers while the town is short of
 *     food. The readiest come first, by what they care for and their nature (WarFooting.volunteerScore).</li>
 * <li><b>Drill.</b> On the day of rest, in the hours the town plays games on the square, the militia drills
 *     instead: at the training yard (a thrust at a dummy, an arrow at the butts) if the town has one, before
 *     the barracks if not, on the square failing both. Every turn at it is a little more of the watch's
 *     trade learned, put by for the day it takes the trade up (VillageFolkEntity.schoolXp).</li>
 * <li><b>Called up.</b> At war each is called up: armed out of the armoury (a blade, and a helmet or a
 *     breastplate if there is one to spare; out of the stores when there is no armoury) and counted among
 *     those who fight for the town (WarFooting.militia). Each morning of the war it musters at the yard for
 *     an hour and a half and drills, away from its own work: that is what the war costs in hours.</li>
 * <li><b>Stood down.</b> At peace the arms go back to the armoury and everybody back to their trades, which
 *     they never left.</li>
 * </ul>
 * The guards themselves take a turn at the yard every day there is one, by day, when nothing is about.
 * Who is in it is kept with the world (Ledger notes "war.mil/&lt;folk&gt;").
 */
public final class Militia {

    private Militia() {}

    static final String KEY = "war.mil/";
    /** The share of the town's able grown folk enrolled on its guard, and at war. */
    static final double TENSION_SHARE = 0.25, WAR_SHARE = 1.0 / 3.0;
    /** A war's muster, each morning: from eight till half past nine (day time). */
    static final long MUSTER_FROM = 2000L, MUSTER_TO = 3500L;
    /** The hours of work a muster costs a militia hand. */
    public static final double MUSTER_HOURS = (MUSTER_TO - MUSTER_FROM) / 1000.0;
    /** Experience at the watch's trade for each turn at a dummy, and the ticks between turns. */
    static final int DRILL_XP = 30, DRILL_EVERY = 100;
    /** A guard's turn at the yard: by day, once a day, this long. */
    static final long YARD_FROM = 1000L, YARD_TO = 9000L, YARD_TURN = 1200L;

    /** One folk in the militia: called up or only enrolled, how often it has drilled and when last, and the
     *  arms it was issued (item ids), to go back to the armoury at peace. */
    public record Member(UUID folk, boolean called, int drills, long drilledOn, String issued) {
        String encode() {
            return (called ? "C" : "E") + "|" + drills + "|" + drilledOn + "|" + issued;
        }

        @Nullable
        static Member decode(UUID folk, @Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|", -1);
            try {
                return new Member(folk, "C".equals(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0,
                    p.length > 2 ? Long.parseLong(p[2]) : -1L, p.length > 3 ? p[3] : "");
            } catch (NumberFormatException e) {
                return new Member(folk, "C".equals(p[0]), 0, -1L, "");
            }
        }

        Member drilled(long day) {
            return day == drilledOn ? this : new Member(folk, called, drills + 1, day, issued);
        }
    }

    /** When each folk last had a turn at a dummy (its tickCount), and when each guard began (or had) its turn at the yard today. */
    private static final Map<UUID, Long> LAST_XP = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> YARD = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST_XP.clear();
        YARD.clear();
        STAGED.clear();
    }

    // ------------------------------------------------------------------ the roll

    /** Everybody in the town's militia. */
    public static Map<UUID, Member> members(@Nullable UUID village) {
        Map<UUID, Member> out = new HashMap<>();
        if (village == null) return out;
        for (Map.Entry<String, String> e : Ledger.notes(village).entrySet()) {
            if (!e.getKey().startsWith(KEY) || e.getValue() == null || e.getValue().isEmpty()) continue;
            try {
                UUID u = UUID.fromString(e.getKey().substring(KEY.length()));
                Member m = Member.decode(u, e.getValue());
                if (m != null) out.put(u, m);
            } catch (IllegalArgumentException ignored) {
                // not one of ours
            }
        }
        return out;
    }

    /** This folk's place in its town's militia, or null. */
    @Nullable
    public static Member member(VillageFolkEntity f) {
        UUID v = f.ownerId();
        return v == null ? null : Member.decode(f.getUUID(), Ledger.note(v, KEY + f.getUUID()));
    }

    public static boolean enrolled(VillageFolkEntity f) {
        return member(f) != null;
    }

    /** Called up to fight (at war, armed out of the armoury)? */
    public static boolean calledUp(VillageFolkEntity f) {
        Member m = member(f);
        return m != null && m.called();
    }

    /** How many of the town's militia are called up. */
    public static int called(UUID village) {
        int n = 0;
        for (Member m : members(village).values()) if (m.called()) n++;
        return n;
    }

    static void put(UUID village, Member m) {
        Ledger.note(village, KEY + m.folk(), m.encode());
    }

    static void drop(UUID village, UUID folk) {
        Ledger.forget(village, KEY + folk);
    }

    /** Able to bear arms: grown, not old, alive, not the watch (it is the watch already), and at home. */
    static boolean able(VillageFolkEntity f) {
        return !f.isBaby() && f.isAlive() && !f.isOld() && f.stationTask() != StationTask.GUARD
            && f.expedition() == null && f.trip() == null && !f.isShowcase();
    }

    /** Work the town cannot spare a hand from, even on the day of rest's drill or a war's muster. */
    static boolean essential(UUID village, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (f.isElder() || t == StationTask.STORE || t == StationTask.BANK || School.pay(f) > 0) return true;
        return (t == StationTask.FARM || t == StationTask.FISH) && Market.hungry(village);
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The militia's morning on a war footing (WarFooting.morning): the roll kept (those who joined the watch or
     * are gone struck off), more enrolled up to the footing's share, and at war everybody called up; back on its
     * guard after a war, the called-up stood back to drilling and their arms returned.
     */
    static void morning(ServerLevel level, Villages.Village v, long day, Wars.Footing footing) {
        UUID id = v.id();
        prune(id);
        enrol(level, v, day, footing);
        if (footing == Wars.Footing.WAR) callUp(level, v, day);
        else if (footing == Wars.Footing.TENSION) standBack(level, v, day);
    }

    /** Off the roll: those who have joined the watch, and those who are gone from the town. */
    static void prune(UUID village) {
        Map<UUID, VillageFolkEntity> here = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) here.put(f.getUUID(), f);
        boolean whole = Villages.loadedCount(village) * 5 >= Villages.headcount(village) * 4;
        for (Member m : members(village).values()) {
            VillageFolkEntity f = here.get(m.folk());
            if (f == null ? whole : f.stationTask() == StationTask.GUARD) drop(village, m.folk());
        }
    }

    /** The readiest of the able enrolled, up to the footing's share. Returns how many joined today. */
    static int enrol(ServerLevel level, Villages.Village v, long day, Wars.Footing footing) {
        UUID id = v.id();
        Map<UUID, Member> have = members(id);
        List<VillageFolkEntity> able = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && able(f)) able.add(f);
        int want = (int) Math.round(able.size() * (footing == Wars.Footing.WAR ? WAR_SHARE : TENSION_SHARE));
        want = Math.max(Math.min(2, able.size()), want);
        int count = 0;
        for (VillageFolkEntity f : able) if (have.containsKey(f.getUUID())) count++;
        able.removeIf(f -> have.containsKey(f.getUUID()) || essential(id, f));
        able.sort((a, b) -> Integer.compare(WarFooting.volunteerScore(b), WarFooting.volunteerScore(a)));
        List<String> joined = new ArrayList<>();
        for (VillageFolkEntity f : able) {
            if (count >= want) break;
            put(id, new Member(f.getUUID(), false, 0, -1L, ""));
            count++;
            joined.add(f.displayNameCap());
            f.persona().remember(day, "I was enrolled in the militia", 5);
            if (joined.size() <= 2) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The militia, is it? I'll drill with the rest.",
                    "Put my name down. I'll keep my " + f.stationTask().label + " and drill on the day of rest."));
            }
        }
        if (!joined.isEmpty()) {
            Villages.tell(id, day, String.join(", ", joined) + (joined.size() == 1 ? " was" : " were")
                + " enrolled in the militia, to drill on the day of rest");
        }
        return joined.size();
    }

    /** Everybody on the roll called up and armed. */
    static void callUp(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<UUID, VillageFolkEntity> here = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) here.put(f.getUUID(), f);
        List<String> called = new ArrayList<>();
        int armed = 0;
        for (Member m : members(id).values()) {
            if (m.called()) continue;
            VillageFolkEntity f = here.get(m.folk());
            if (f == null) continue;
            String issued = issue(level, v, f);
            if (!issued.isEmpty()) armed++;
            put(id, new Member(m.folk(), true, m.drills(), m.drilledOn(), issued));
            called.add(f.displayNameCap());
            f.persona().remember(day, "I was called up to the militia for the war", 7);
            if (called.size() <= 2) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Called up! To the armoury, then.", "So it's come to it. I'll muster with the rest."));
            }
        }
        if (!called.isEmpty()) {
            Villages.tell(id, day, "the militia was called up: " + String.join(", ", called)
                + (armed > 0 ? " (" + armed + " armed out of the " + (WarWorks.armoury(id) != null ? "armoury" : "stores") + ")" : ", with no arms to issue"));
        }
    }

    /** Back on its guard after a war: the called-up stand back to drilling, their arms returned. */
    static void standBack(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<UUID, VillageFolkEntity> here = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) here.put(f.getUUID(), f);
        for (Member m : members(id).values()) {
            if (!m.called()) continue;
            VillageFolkEntity f = here.get(m.folk());
            if (f == null) continue;
            giveBack(level, v, f, m);
            put(id, new Member(m.folk(), false, m.drills(), m.drilledOn(), ""));
        }
    }

    /** At peace: the arms back to the armoury and the militia stood down. Returns how many went home. */
    public static int standDown(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<UUID, Member> all = members(id);
        if (all.isEmpty()) return 0;
        Map<UUID, VillageFolkEntity> here = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) here.put(f.getUUID(), f);
        boolean whole = Villages.loadedCount(id) * 5 >= Villages.headcount(id) * 4;
        int home = 0;
        for (Member m : all.values()) {
            VillageFolkEntity f = here.get(m.folk());
            if (f == null) {
                if (whole) drop(id, m.folk());              // gone from the town: struck off
                continue;                                  // (or away: its arms come back when it does)
            }
            giveBack(level, v, f, m);
            drop(id, m.folk());
            f.persona().remember(day, "the militia was stood down, and I went back to my own work", 5);
            home++;
        }
        if (home > 0) {
            Villages.tell(id, day, "the militia was stood down: " + home + (home == 1 ? " hand" : " hands")
                + " went back to their trades, and their arms to the " + (WarWorks.armoury(id) != null ? "armoury" : "stores"));
        }
        return home;
    }

    // ------------------------------------------------------------------ arms

    static boolean blade(ItemStack s) {
        return s.getItem() instanceof SwordItem;
    }

    /** How good a blade is: its metal's bite (wood and gold nought, stone one, iron two, diamond three...); -1 for no blade. */
    static float bite(ItemStack s) {
        return s.getItem() instanceof SwordItem sw ? sw.getTier().getAttackDamageBonus() : -1.0F;
    }

    /** The best blade this folk carries (in its pack or its hands), by bite; -1 with none. */
    static float bestBlade(VillageFolkEntity f) {
        float best = -1.0F;
        for (ItemStack s : f.getInventoryItems()) best = Math.max(best, bite(s));
        for (EquipmentSlot slot : EquipmentSlot.values()) best = Math.max(best, bite(f.getItemBySlot(slot)));
        return best;
    }

    /**
     * A blade better than its own, and a helmet or a breastplate if there is one, out of the armoury (or the
     * stores). Every founder carries a stone sword of its own (VillageSpawner.starterKit): called up, it is
     * issued the armoury's iron and keeps its own in its pack. Returns the item ids issued, comma-separated;
     * empty if there was nothing better to give it.
     */
    static String issue(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<String> got = new ArrayList<>();
        float own = bestBlade(f);
        ItemStack w = WarWorks.takeArms(level, v, s -> blade(s) && bite(s) > own);
        if (!w.isEmpty()) {
            ItemStack left = f.insertGiven(w.copy());
            if (left.isEmpty()) got.add(id(w.getItem()));
            else WarWorks.returnArms(level, v, left);
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.CHEST, EquipmentSlot.HEAD }) {
            if (!f.getItemBySlot(slot).isEmpty()) continue;
            ItemStack piece = WarWorks.takeArms(level, v, s -> s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot);
            if (piece.isEmpty()) continue;
            f.setItemSlot(slot, piece);
            got.add(id(piece.getItem()));
            break;                                         // one piece each: there is seldom more to go round
        }
        return String.join(",", got);
    }

    /** What was issued, back to the armoury (or the stores): off its back or out of its pack. */
    static void giveBack(ServerLevel level, Villages.Village v, VillageFolkEntity f, Member m) {
        if (m.issued().isEmpty()) return;
        for (String s : m.issued().split(",")) {
            Item it;
            try {
                it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(s));
            } catch (RuntimeException e) {
                continue;
            }
            ItemStack back = ItemStack.EMPTY;
            for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
                if (f.getItemBySlot(slot).is(it)) {
                    back = f.getItemBySlot(slot).copy();
                    f.setItemSlot(slot, ItemStack.EMPTY);
                    break;
                }
            }
            if (back.isEmpty()) {
                if (f.getMainHandItem().is(it)) {
                    back = f.getMainHandItem().copy();
                    f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                } else {
                    for (int i = 0; i < f.getInventoryItems().size(); i++) {
                        ItemStack st = f.getInventoryItems().get(i);
                        if (!st.is(it)) continue;
                        back = st.copyWithCount(1);
                        st.shrink(1);
                        if (st.isEmpty()) f.getInventoryItems().set(i, ItemStack.EMPTY);
                        break;
                    }
                }
            }
            if (!back.isEmpty()) WarWorks.returnArms(level, v, back);    // (lost or broken in the war: nothing to give)
        }
    }

    static String id(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    // ------------------------------------------------------------------ the drill

    /**
     * [war-prep] The day of rest (RestDay.spend), in the hours of the games on the square: a folk in the
     * militia drills instead. True while it is at it.
     */
    public static boolean drill(VillageFolkEntity f) {
        try {
            UUID v = f.ownerId();
            if (v == null || f.isBaby() || !(f.level() instanceof ServerLevel level)) return false;
            Member m = member(f);
            return m != null && drillAt(level, f, v, m, "drilling with the militia");
        } catch (RuntimeException e) {
            com.jrpetty.mcassistant.Guard.struck("militia drill", e);
            return false;
        }
    }

    /** Is it the muster for this folk now: a militia hand called up, a morning of the war, the muster's hours? */
    public static boolean mustering(VillageFolkEntity f) {
        try {
            return musteringNow(f);
        } catch (RuntimeException e) {
            com.jrpetty.mcassistant.Guard.struck("militia muster", e);
            return false;
        }
    }

    private static boolean musteringNow(VillageFolkEntity f) {
        Long staged = STAGED.get(f.getUUID());
        if (staged != null && f.level().getGameTime() < staged) return true;     // drilling for the smoke stage
        long t = f.level().getDayTime() % 24000L;
        if (t < MUSTER_FROM || t >= MUSTER_TO || f.isBaby()) return false;
        UUID v = f.ownerId();
        if (v == null || WarFooting.footingNow(v, f.level().getGameTime()) != Wars.Footing.WAR) return false;
        Member m = member(f);
        return m != null && m.called() && !essential(v, f);
    }

    /** [war-prep] The war's morning muster (VillageFolkEntity.aiStep): to the yard and drill. True while at it. */
    public static boolean muster(VillageFolkEntity f) {
        if (!mustering(f) || !(f.level() instanceof ServerLevel level)) return false;
        try {
            Member m = member(f);
            return m != null && drillAt(level, f, f.ownerId(), m, "at the militia's muster");
        } catch (RuntimeException e) {
            com.jrpetty.mcassistant.Guard.struck("militia muster", e);
            return false;
        }
    }

    /** Folk set to drill for the smoke stage, till when (game time). */
    private static final Map<UUID, Long> STAGED = new ConcurrentHashMap<>();

    /**
     * The smoke stage (/village war footing stage): the armoury and the training yard put up side by side at
     * this spot on cleared ground, for nothing (a showcase), the armoury's racks filled, up to six of the town's
     * grown folk enrolled, called up, armed and set at the dummies for two minutes, and the watch sent to its
     * turn at the yard. Returns where to look: "armoury x y z yard x y z militia n".
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, now = level.getGameTime();
        Direction back = Direction.NORTH;
        BlockPos armoury = at, yard = at.offset(14, 0, 0);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, -1, -8), at.offset(21, -1, 12))) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, 0, -8), at.offset(21, 10, 12))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "armoury", armoury, back, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "trainingyard", yard, back, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "armoury", armoury, back);
        Ledger.built(id, "trainingyard", yard, back);
        // The racks: blades, breastplates, helmets, bows and arrows (a showcase's, for nothing).
        net.minecraft.world.item.Item[] arms = { net.minecraft.world.item.Items.IRON_SWORD, net.minecraft.world.item.Items.IRON_SWORD,
            net.minecraft.world.item.Items.IRON_SWORD, net.minecraft.world.item.Items.STONE_SWORD, net.minecraft.world.item.Items.IRON_CHESTPLATE,
            net.minecraft.world.item.Items.IRON_CHESTPLATE, net.minecraft.world.item.Items.IRON_HELMET, net.minecraft.world.item.Items.IRON_HELMET,
            net.minecraft.world.item.Items.BOW, net.minecraft.world.item.Items.BOW, net.minecraft.world.item.Items.SHIELD };
        for (net.minecraft.world.item.Item it : arms) WarWorks.returnArms(level, v, new ItemStack(it));
        WarWorks.returnArms(level, v, new ItemStack(net.minecraft.world.item.Items.ARROW, 64));
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !able(f) || n >= 6) continue;
            Member m = member(f);
            String issued = m != null && m.called() ? m.issued() : issue(level, v, f);
            put(id, new Member(f.getUUID(), true, m == null ? 1 : m.drills() + 1, day, issued));
            BlockPos[] spot = spot(level, id, f);
            if (spot != null) f.teleportTo(spot[0].getX() + 0.5, spot[0].getY(), spot[0].getZ() + 0.5);
            f.clearQueue();
            STAGED.put(f.getUUID(), now + 2400L);
            n++;
        }
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity g && g.stationTask() == StationTask.GUARD) {
                YARD.remove(g.getUUID());
                g.teleportTo(yard.getX() + 0.5 + g.getRandom().nextInt(5) - 2, yard.getY(), yard.getZ() - 1.5);
            }
        }
        return List.of("armoury", Integer.toString(armoury.getX()), Integer.toString(armoury.getY()), Integer.toString(armoury.getZ()),
            "yard", Integer.toString(yard.getX()), Integer.toString(yard.getY()), Integer.toString(yard.getZ()), "militia", Integer.toString(n));
    }

    /**
     * [war-prep] A guard's turn at the training yard (Raids.guardDuty, between the bells): once a day, by day,
     * with nothing to fight and nobody to walk beside, a minute at the dummies. The watch's skill rises with it.
     */
    public static boolean yard(VillageFolkEntity g) {
        try {
            return yardNow(g);
        } catch (RuntimeException e) {
            com.jrpetty.mcassistant.Guard.struck("training yard", e);
            return false;
        }
    }

    private static boolean yardNow(VillageFolkEntity g) {
        long t = g.level().getDayTime() % 24000L;
        if (t < YARD_FROM || t >= YARD_TO || g.getTarget() != null || !(g.level() instanceof ServerLevel level)) return false;
        UUID v = g.ownerId();
        if (v == null || g.stationTask() != StationTask.GUARD || Patrols.escorting(g)) return false;
        long day = level.getDayTime() / 24000L, now = level.getGameTime();
        long[] turn = YARD.get(g.getUUID());
        if (turn != null && turn[0] == day && (turn[1] < 0 || now - turn[1] > YARD_TURN || now < turn[1])) {
            if (turn[1] >= 0) {
                YARD.put(g.getUUID(), new long[]{ day, -1L });     // its turn is over for today
                FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "That'll do for today. Back to the rounds.", "Sharper than yesterday."));
            }
            return false;
        }
        if (Villages.builtStructure(v, "trainingyard") == null) {
            YARD.put(g.getUUID(), new long[]{ day, -1L });     // no yard: not looked for again till tomorrow
            return false;
        }
        if (turn == null || turn[0] != day) YARD.put(g.getUUID(), new long[]{ day, now });
        return drillAt(level, g, v, null, "at the training yard, at the dummies");
    }

    /** Where this folk drills: its place before a dummy, and the dummy (or what it thrusts at). */
    @Nullable
    static BlockPos[] spot(ServerLevel level, UUID village, VillageFolkEntity f) {
        int k = Math.floorMod(f.getUUID().hashCode(), 9);
        int col = k % 3, row = k / 3;
        com.jrpetty.mcassistant.village.Ledger.Building yard = Villages.builtStructure(village, "trainingyard");
        if (yard != null) {
            // The drawing's dummies stand two back from its middle, at two either side and in it; three can
            // drill at each, one behind another (blueprints/trainingyard.txt).
            Direction back = yard.facing(), right = back.getClockWise();
            BlockPos dummy = yard.anchor().relative(right, 2 * col - 2).relative(back, 2);
            return new BlockPos[]{ yard.anchor().relative(right, 2 * col - 2).relative(back, -row), dummy };
        }
        com.jrpetty.mcassistant.village.Ledger.Building barracks = Villages.builtStructure(village, "barracks");
        if (barracks != null) {
            Direction back = barracks.facing(), right = back.getClockWise();
            int deep = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf("barracks")[1];
            BlockPos front = barracks.anchor().relative(back, -(deep + 4));
            return new BlockPos[]{ ground(level, f, front.relative(right, 2 * col - 2).relative(back, -row)), front.relative(back, 2) };
        }
        BlockPos heart = f.villageCentre();
        if (heart == null) return null;
        BlockPos dummy = heart.offset(0, 0, 8);
        return new BlockPos[]{ ground(level, f, heart.offset(2 * col - 2, 0, 6 - row)), dummy };
    }

    private static BlockPos ground(ServerLevel level, VillageFolkEntity f, BlockPos p) {
        BlockPos g = f.surfaceAt(p.getX(), p.getZ());
        return g != null && Math.abs(g.getY() - p.getY()) <= 4 ? g : p;
    }

    /** To its place at the drill, and a turn at the dummy every few seconds: the watch's trade learned. */
    static boolean drillAt(ServerLevel level, VillageFolkEntity f, UUID village, @Nullable Member m, String what) {
        BlockPos[] at = spot(level, village, f);
        if (at == null) return false;
        BlockPos stand = at[0], dummy = at[1];
        f.hobbyNow = what;
        f.lastLeisureTick = f.tickCount;
        double dx = f.getX() - (stand.getX() + 0.5), dz = f.getZ() - (stand.getZ() + 0.5);
        if (dx * dx + dz * dz > 2.5) {
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60) {
                f.walkTo(stand, 1.0D);
                f.hobbyTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(dummy.getX() + 0.5, dummy.getY() + 1.2, dummy.getZ() + 0.5);
        long day = level.getDayTime() / 24000L;
        Long last = LAST_XP.get(f.getUUID());
        if (last == null || f.tickCount - last >= DRILL_EVERY || f.tickCount < last) {
            LAST_XP.put(f.getUUID(), (long) f.tickCount);
            turn(level, f, village, m, dummy, day);
        } else if (f.tickCount % 20 == 0) {
            f.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /** One turn at the dummy: a thrust, the trade a little better known, the drill counted once a day. */
    static void turn(ServerLevel level, VillageFolkEntity f, UUID village, @Nullable Member m, BlockPos dummy, long day) {
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, dummy, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 0.4F, 0.9F + f.getRandom().nextFloat() * 0.2F);
        f.schoolXp(StationTask.GUARD, DRILL_XP);
        if (m != null && m.drilledOn() != day) {
            Member now = member(f);
            if (now != null) put(village, now.drilled(day));
        }
        if (f.getRandom().nextInt(10) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hup!", "Thrust — and back!", "Again!", "Keep your guard up!", "Mind your feet."));
        }
    }

    /** Tests: one turn at the drill, stood at its place before the dummy. */
    public static boolean drillForTests(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null) return false;
        Member m = member(f);
        BlockPos[] at = spot(level, f.ownerId(), f);
        if (at == null) return false;
        f.teleportTo(at[0].getX() + 0.5, at[0].getY(), at[0].getZ() + 0.5);
        LAST_XP.remove(f.getUUID());
        return drillAt(level, f, f.ownerId(), m, "drilling with the militia");
    }

    // ------------------------------------------------------------------ telling

    /** The militia's line for the war page. */
    static String line(ServerLevel level, UUID village) {
        Map<UUID, Member> all = members(village);
        if (all.isEmpty()) return WarFooting.ready(village) ? "The militia: nobody enrolled yet." : "";
        int called = 0, drills = 0;
        for (Member m : all.values()) {
            if (m.called()) called++;
            drills += m.drills();
        }
        String where = Villages.builtStructure(village, "trainingyard") != null ? "at the training yard"
            : Villages.builtStructure(village, "barracks") != null ? "before the barracks" : "on the square";
        return "The militia: " + all.size() + " enrolled" + (called > 0 ? ", " + called + " called up" : "") + "; "
            + drills + (drills == 1 ? " drill" : " drills") + " in all, on the day of rest " + where
            + (called > 0 ? ", and a muster every morning of the war" : "") + ".";
    }

    /** A folk's card: its place in the militia, or null. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        Member m = member(f);
        if (m == null) return null;
        String drilled = m.drills() == 0 ? "not drilled yet" : "drilled " + m.drills() + (m.drills() == 1 ? " time" : " times");
        if (!m.called()) return "in the militia, " + drilled;
        List<String> arms = new ArrayList<>();
        if (!m.issued().isEmpty()) {
            for (String s : m.issued().split(",")) {
                try {
                    arms.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(s))).getHoverName().getString().toLowerCase(Locale.ROOT));
                } catch (RuntimeException ignored) {
                    // an item gone from the game
                }
            }
        }
        return "called up to the militia" + (arms.isEmpty() ? ", unarmed" : ", armed with " + String.join(" and ", arms)) + "; " + drilled;
    }
}
