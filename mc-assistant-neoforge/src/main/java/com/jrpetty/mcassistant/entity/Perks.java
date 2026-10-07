package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [perks] Perks: the town's, the leader's and the folk's, gathered where the rest of the game asks for them.
 *
 * <p>The town's are its research (CityTree: sixty-three civics in ten branches, eight pairs of which it may have only
 * one, and a wonder at the top of each) and its wonders (Wonders). The leader's are its perk in office, its skills and
 * its reign's legacies (Reigns). The folk's are their knacks (FolkSkills: fifty now, with the new trades' and the
 * masters') and their quirks (Quirks). Each has its own class; this one is where the game's hooks meet them, so each
 * shared place asks one question here ("how much quicker does this folk work?", "what does this cost this buyer?")
 * and gets every perk's answer in one.
 *
 * <p>It also holds what shows them: the Perks page of the town's books ({@link #report}), the board's line, the lines
 * the town's identity is told in ({@link #identityLines}, for the Identity page), the Leader's page's research and
 * skills ({@link #leaderPage}), and /village perks.
 *
 * <p><b>Quiet in the tests.</b> The resets between the tests ({@link #resetForTests}, from Villages.resetForTests) put
 * the perks that come by themselves to sleep: no folk is given quirks of its own, and the leader in office gives no
 * perk, gains no experience and leaves no legacy, so a test written before them measures what it always did. A test
 * of the perks wakes them ({@link #liveForTests}). As a world opens they are always awake.
 */
public final class Perks {

    private Perks() {}

    private static volatile boolean quiet;

    /** Are the perks that come by themselves awake (always, but in the tests until a test wakes them)? */
    public static boolean live() {
        return !quiet;
    }

    /** Tests: wake the perks (or put them back to sleep). */
    public static void liveForTests(boolean on) {
        quiet = !on;
        Reigns.reset();
    }

    /**
     * Memory forgotten (Villages.resetForTests: between the tests, and as a world opens or closes). Between the tests the
     * perks are put to sleep and the world's wonders forgotten; as a world opens they wake, and the wonders, the world's
     * own history, are left alone.
     */
    public static void resetForTests() {
        Reigns.reset();
        Quirks.reset();
        FolkSkills.resetLooks();
        if (com.jrpetty.mcassistant.SessionReset.opening()) {
            quiet = false;
            return;
        }
        quiet = true;
        Wonders.wipeForTests();
    }

    // ------------------------------------------------------------------ the clocks

    /** The town's morning (CityTree.morning): its wonders' dues, and its leaders' reigns. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        Wonders.morning(level, v, day);
        if (live()) Reigns.morning(level, v, day);
    }

    /** Every eight seconds for every folk (CityTree.tend): its quirks, and what its leader and the town's legacies put on it. */
    static void tend(VillageFolkEntity f) {
        Quirks.tend(f);
        UUID v = f.ownerId();
        CityTree.modifier(f, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, ARMOUR_ID, Reigns.armour(f, v),
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        CityTree.modifier(f, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, HIT_ID, Reigns.hit(f, v),
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);
        CityTree.modifier(f, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, WALK_ID, Reigns.walk(v),
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    private static final net.minecraft.resources.ResourceLocation ARMOUR_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "reign_armour");
    private static final net.minecraft.resources.ResourceLocation HIT_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "reign_hit");
    private static final net.minecraft.resources.ResourceLocation WALK_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "legacy_roads");

    // ------------------------------------------------------------------ the hooks' questions

    /** Its pace from its quirks, in percent (VillageFolkEntity.skillWorkPercent). */
    public static int workPercent(VillageFolkEntity f) {
        return Quirks.workPercent(f);
    }

    /** Building, in percent more (AssistantEntity.buildPaceTicks): the Clockwork Gate, the leader in office, its skills, legacies. */
    public static int buildPercent(@Nullable UUID village) {
        return CityTree.gateBuildPercent(village) + Reigns.buildPercent(village);
    }

    /** The takings, in percent (CityTree.takingsPercent): the leader's, its skills', the legacies', a Silver Tongue at work. */
    static int takingsPercent(@Nullable UUID village) {
        return Reigns.takingsPercent(village) * FolkSkills.silverTongue(village) / 100;
    }

    /**
     * The town's contentment from its perks, with the words (Contentment.compute): the new civics and the wonders
     * (CityTree), the leader in office, its skills and the legacies (Reigns), and a Showman's feast.
     */
    public static int contentment(@Nullable UUID village, List<String> good, List<String> bad) {
        if (village == null) return 0;
        int c = CityTree.contentment(village, good, bad) + Reigns.contentment(village, good);
        String show = Ledger.note(village, "perks.show");
        long day = today();
        if (show != null && !show.isEmpty() && day - CityTree.parse(show) <= 2 && day >= CityTree.parse(show)) {
            c += 2;
            good.add("a feast to remember");
        }
        return c;
    }

    /**
     * Its spirits (VillageFolkEntity.refreshMood): Vespers in hard times, Remembrance's comfort in grief, the Almshouse
     * for the poor (and Private Larders against them), a Free Spirit in office, the Cathedral's floor, and its quirks.
     */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID v = f.ownerId();
        if (v != null && !f.isBaby()) {
            int vespers = CityTree.vespers(v);
            if (vespers > 0 && Contentment.score(v) < 50) { m += vespers; why.add(new Object[]{"faith", vespers}); }
            if (day - f.griefDay <= 2 && CityTree.griefPercent(v) < 100) {
                int back = 12 * (100 - CityTree.griefPercent(v)) / 100;
                m += back;
                why.add(new Object[]{"remembrance", back});
            }
            boolean poor = Wealth.tier(f) == Wealth.Tier.POOR;
            if (poor && CityTree.alms(v) > 0) { m += CityTree.alms(v); why.add(new Object[]{"alms", CityTree.alms(v)}); }
            if (poor && CityTree.larderPoor(v) > 0) { m -= CityTree.larderPoor(v); why.add(new Object[]{"larders", CityTree.larderPoor(v)}); }
            int led = Reigns.mood(v);
            if (led > 0) { m += led; why.add(new Object[]{"reign", led}); }
        }
        m = Quirks.mood(f, day, m, why);
        int floor = CityTree.moodFloor(v);
        if (floor > 0 && m < floor) { why.add(new Object[]{"cathedral", floor - m}); m = floor; }
        return m;
    }

    /** What it says of a reason its perks gave its spirits (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        RandomSource r = f.getRandom();
        return switch (why) {
            case "faith" -> FolkTalk.pick(r, "Times are hard, but the evening service helps.", "We pray together of an evening. It helps.");
            case "remembrance" -> "We remember our dead together here. It makes the grief easier to carry.";
            case "alms" -> "I've nothing, but the almshouse will see me right.";
            case "larders" -> "Every household for itself, they say. It's hard when your larder's bare.";
            case "reign" -> "Our leader's a free spirit — and it rubs off on all of us.";
            case "cathedral" -> "Whatever happens, there's the Cathedral. It lifts you.";
            default -> Quirks.moodWords(f, why);
        };
    }

    /** The share of hands a trade wants (Villages.target): the Standing Army and a Guardian in office a guard more, the Militia one fewer (never none). */
    public static double share(@Nullable UUID village, StationTask trade, double t) {
        if (village == null || trade != StationTask.GUARD || t <= 0) return t;
        double more = CityTree.guardsMore(village) + Reigns.guardsMore(village);
        return Math.max(Math.min(1.0, t), t + more);
    }

    /** A wage's extra (Wealth.wage): a guard's coin under a Standing Army, and Private Larders' twentieth (carried day to day). */
    public static int wageExtra(VillageFolkEntity f, int wage) {
        UUID v = f.ownerId();
        if (v == null || wage <= 0) return 0;
        int extra = f.stationTask() == StationTask.GUARD ? CityTree.guardPay(v) : 0;
        int pct = CityTree.wagePercent(v) - 100;
        if (pct > 0) {
            long day = Math.max(0, f.level().getDayTime() / 24000L);
            extra += (int) ((day + 1) * wage * pct / 100 - day * wage * pct / 100);
        }
        return extra;
    }

    /**
     * A price at the town's counter (Purchases.priceEach): the guilds' (dearer under Monopolies, cheaper in a Free
     * Market), Private Larders' food, and a Smooth-talker's way with the counter.
     */
    public static double priceEach(@Nullable UUID village, ItemStack one, @Nullable VillageFolkEntity buyer, double p) {
        if (village == null) return p;
        double x = p * CityTree.craftPricePercent(village, one) / 100.0;
        if (one.get(net.minecraft.core.component.DataComponents.FOOD) != null) x = x * CityTree.foodPricePercent(village) / 100.0;
        return x * Quirks.pricePercent(buyer) / 100.0;
    }

    /** The Open Granary: is food out of the stores free to this town's folk (Purchases.pays)? */
    public static boolean foodFree(@Nullable UUID village) {
        return CityTree.openGranary(village);
    }

    /** Arrows more in a guard's quiver (WatchKit.fit): the Fletchers' Charter, Featherlight. */
    public static int quiver(@Nullable UUID village, VillageFolkEntity g) {
        return CityTree.quiver(village) + FolkSkills.featherlight(g);
    }

    /** Blocks further a guard picks its mark (FolkSkills.sightBonus): Earthworks for a guard, and its own quirks. */
    static double sight(VillageFolkEntity f) {
        double s = Quirks.sight(f);
        if (f.stationTask() == StationTask.GUARD) s += CityTree.earthworksSight(f.ownerId());
        return s;
    }

    /** Crime's chance, as a factor (Mischief.prevention): the town's ways and the leader's skills. */
    public static double crimeFactor(@Nullable UUID village) {
        return CityTree.crimeFactor(village) * Reigns.crimeFactor(village);
    }

    /** Lots more a caravan carries (Caravans.load): Open Borders, the Grand Bazaar, a Quartermaster. */
    public static int caravanLots(@Nullable UUID village) {
        return CityTree.caravanLots(village) + Reigns.caravanLots(village);
    }

    /** How a town's ways and its leader warm a neighbour to it today (Diplomacy.daily). */
    public static int warmth(@Nullable UUID village, long day) {
        return CityTree.borderWarmth(village, day) + Reigns.warmth(village, day);
    }

    /** How much warmer an envoy is heard (Envoys.answer): its town's Diplomat, and its own smooth tongue. */
    public static int envoyWarmth(@Nullable UUID from, @Nullable VillageFolkEntity envoy) {
        return Reigns.envoyWarmth(from) + Quirks.envoyWarmth(envoy);
    }

    /** An Orator leader's word for what it put to the vote (Referendums.judgeWorks). */
    public static int oratory(@Nullable UUID village, String callerId) {
        return Reigns.oratory(village, callerId);
    }

    /** A candidate's pull at an election (Elections.judge): a Born Leader's, and an Orator standing again. */
    public static int hustings(@Nullable UUID village, @Nullable VillageFolkEntity candidate, UUID id) {
        return Quirks.standing(candidate) / 2 + Reigns.hustings(village, id);
    }

    /** A busker's chance of a coin, more (Buskers.tip): Patronage, a Patron in office, a Musical busker. */
    public static double tips(VillageFolkEntity busker) {
        UUID v = busker.ownerId();
        return CityTree.patronTips(v) + Reigns.tips(v) + Quirks.tips(busker);
    }

    /** A scout's range (Scouts.setOut): the Surveyors' Office and a Surveyor's Eye, each a quarter more. */
    public static int scoutRange(VillageFolkEntity f, int range) {
        int pct = CityTree.scoutPercent(f.ownerId()) + FolkSkills.surveyorsEye(f);
        return range + range * pct / 100;
    }

    /** A cold's odds (Health.luck): a Frail folk's halved. */
    public static int coldOdds(VillageFolkEntity f, int odds) {
        return Quirks.coldOdds(f, odds);
    }

    /** A cold's length, in ticks (Health.catchCold): a Hardy folk's halved, a Traditionalist leader's three quarters. */
    public static int coldLength(VillageFolkEntity f, int ticks) {
        return ticks * Quirks.coldPercent(f) / 100 * Reigns.coldPercent(f.ownerId()) / 100;
    }

    /** A haul from the fleet's boat (Fleet.catchOne): the Great Lighthouse and a Lucky fisher, a fish more now and then. */
    public static void moreFish(VillageFolkEntity f, List<ItemStack> haul) {
        int more = (CityTree.lighthouseFish(f.ownerId(), f.getRandom()) ? 1 : 0) + (Quirks.lucky(f) ? 1 : 0);
        for (int i = 0; i < more; i++) haul.add(new ItemStack(Items.COD));
    }

    /** A crop harvested (CityTree.seedExchange's sibling, FarmGoal): a Lucky farmer one in twelve gives one more. */
    public static void luckyHarvest(AssistantEntity a, net.minecraft.world.level.block.Block crop) {
        if (!(a instanceof VillageFolkEntity f) || !Quirks.lucky(f)) return;
        net.minecraft.world.item.Item produce = CityTree.produceOf(crop);
        if (produce == null) return;
        ItemStack one = new ItemStack(produce);
        ItemStack left = a.insertItem(one.copy());
        Economy.gathered(a, one, 1 - left.getCount());
        if (!left.isEmpty()) a.spawnAtLocation(left);
    }

    /** A building went up (Villages.noteProject): a wonder's claim, and a deed of the reign. */
    public static void raised(UUID village, String structure, long gameTime) {
        Wonders.raised(village, structure, gameTime);
        if (!Wonders.isWonder(structure) && !"colony".equals(structure)) Reigns.deed(village, Reigns.Deed.BUILD, 1);
    }

    /** A great work opened (BigWorks): a deed of the reign. */
    public static void worksOpened(UUID village) {
        Reigns.deed(village, Reigns.Deed.WORKS, 1);
    }

    /** The renown its perks bring (Villages.renown): its wonders and its legacies of glory. */
    public static int renown(@Nullable UUID village) {
        return Wonders.renown(village) + Reigns.renown(village);
    }

    /**
     * The Printing Press: a new book just shelved at the library (Library.finish) is printed once more, on a plain
     * book out of the stores, and the copy goes into the stores. Nothing printed on nothing. Returns whether it was.
     */
    public static boolean printed(ServerLevel level, Villages.Village v, ItemStack copy) {
        if (!CityTree.printingPress(v.id()) || copy.isEmpty()) return false;
        if (!Crafts.take(level, v, s -> s.is(Items.BOOK), 1)) return false;
        Crafts.store(level, v, copy.copyWithCount(1));
        Villages.tell(v.id(), level.getDayTime() / 24000L, "the press printed a copy of \"" + copy.getHoverName().getString()
            + "\" for the stores");
        return true;
    }

    /** At a feast or a festival (Assemblies): a Showman there lifts the town for two days. */
    public static void feasted(VillageFolkEntity f, long day) {
        UUID v = f.ownerId();
        if (v == null || !FolkSkills.active(f, FolkSkills.Knack.SHOWMAN)) return;
        String was = Ledger.note(v, "perks.show");
        if (was != null && CityTree.parse(was) == day) return;
        Ledger.note(v, "perks.show", Long.toString(day));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Gather round, everybody — now THIS is a feast!", "A song! A dance! Who's with me?"));
    }

    /**
     * The Nether party home (Nether.comeBack): what its folk's knacks bring back more (the gold its picks dug in peace
     * with a Piglin-Friend among them, the piglins letting them be; a Blaze Hunter's rods, with a blade or a bow to take
     * them) and the Nether Charts' (quartz for the picks, a rod for the blades: the charts lead them to both), all into
     * the haul. Nothing without the means to get it, as the rest of the haul.
     */
    public static void netherHaul(ServerLevel level, UUID village, List<UUID> party, List<ItemStack> haul, boolean armed, boolean picks) {
        int gold = 0, rods = 0;
        for (UUID u : party) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            if (picks && f.knacks().has(FolkSkills.Knack.PIGLIN_FRIEND)) gold += 6 + f.getRandom().nextInt(6);
            if (armed && f.knacks().has(FolkSkills.Knack.BLAZE_HUNTER)) rods += 2;
        }
        int[] charts = CityTree.netherMore(village);
        if (gold > 0) haul.add(new ItemStack(Items.GOLD_NUGGET, gold));
        if (armed) rods += charts[1];
        if (rods > 0) haul.add(new ItemStack(Items.BLAZE_ROD, rods));
        if (picks && charts[0] > 0) haul.add(new ItemStack(Items.QUARTZ, charts[0]));
    }

    /** A Nether-goer's roll on coming home (Nether.comeBack): under the wards, or Fireproof, hurt half as often. */
    public static int netherRoll(@Nullable UUID village, VillageFolkEntity f, int roll, RandomSource r) {
        boolean warded = CityTree.blazeWarded(village) || f.knacks().has(FolkSkills.Knack.FIREPROOF);
        return warded && roll < 25 && r.nextBoolean() ? 99 : roll;
    }

    // ------------------------------------------------------------------ telling

    /** Lines for the village board, after the research line (CityTree.board): the town's ways, its wonders, its leader. */
    public static List<String> board(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        List<String> ways = new ArrayList<>();
        for (CityTree.Civic c : CityTree.ways(village)) ways.add(c.title);
        List<String> wonders = new ArrayList<>();
        for (Wonders.Wonder w : Wonders.held(village)) wonders.add(w.title());
        String leg = Reigns.legacyWords(village);
        Values.Value h = Reigns.heart(village);
        List<String> parts = new ArrayList<>();
        if (!wonders.isEmpty()) parts.add("Wonders: " + String.join(", ", wonders) + " (the only ones in the world)");
        if (!ways.isEmpty()) parts.add("Our ways: " + String.join(", ", ways));
        if (h != null) parts.add("Our leader, a " + h.type + ": " + Reigns.officeWords(h));
        if (!leg.isEmpty()) parts.add("Legacies: " + leg);
        String known = Quirks.knownFor(village);
        if (!known.isEmpty()) parts.add(CityTree.capital(known));
        if (!parts.isEmpty()) out.add("FG|" + String.join(" · ", parts) + ".");
        return out;
    }

    /**
     * The town's perks as its identity tells them, a line each (for the Identity page and its summary): its ways, its
     * wonders, its leader's perk and skills, its legacies, and what its folk are known for.
     */
    public static List<String> identityLines(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Map<CityTree.Civic, Long> done = CityTree.done(village);
        out.add("Research: " + done.size() + " of " + CityTree.ALL + " civics" + (CityTree.current(village) == null ? ""
            : ", studying " + CityTree.current(village).title) + ".");
        List<String> ways = new ArrayList<>();
        for (CityTree.Civic c : CityTree.ways(village)) ways.add(c.title + " (not " + c.rival().title + ")");
        if (!ways.isEmpty()) out.add("Its ways: " + String.join("; ", ways) + ".");
        for (Wonders.Wonder w : Wonders.held(village)) out.add("A wonder of the world: " + w.title() + ", the only one there is.");
        Values.Value h = Reigns.heart(village);
        if (h != null) out.add("Its leader is a " + h.type + " at heart: " + Reigns.officeWords(h) + ".");
        UUID l = Villages.elder(village);
        if (l != null) {
            List<String> skills = new ArrayList<>();
            for (Reigns.Skill s : Reigns.book(village, l).skills()) skills.add(s.title);
            if (!skills.isEmpty()) out.add("Its leader's skills: " + String.join(", ", skills) + ".");
        }
        for (Reigns.Legacy x : Reigns.legacies(village)) out.add("A legacy: " + x.name() + " — " + x.deed().effect + ".");
        String known = Quirks.knownFor(village);
        if (!known.isEmpty()) out.add("Its folk are " + known + ".");
        return out;
    }

    /** The Perks page of the town's books (Annals.snapshot, "perks"). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag t = new CompoundTag();
        ListTag wonders = new ListTag();
        for (Wonders.Wonder w : Wonders.Wonder.values()) {
            CompoundTag wt = new CompoundTag();
            Wonders.Claim k = Wonders.claim(w);
            wt.putString("title", w.title());
            wt.putString("branch", w.civic.branch.title);
            wt.putString("effect", w.civic.effect.replace("Wonder: ", ""));
            wt.putString("holder", k == null ? "" : k.town());
            wt.putLong("day", k == null ? -1 : k.day());
            wt.putBoolean("ours", k != null && k.village().equals(id));
            wt.putString("state", Wonders.stateWords(id, w.civic));
            wt.putString("dues", w.duesWords());
            wonders.add(wt);
        }
        t.put("wonders", wonders);
        t.put("leader", strings(Reigns.lines(id, day)));
        ListTag skills = new ListTag();
        UUID l = Villages.elder(id);
        Reigns.Book b = Reigns.book(id, l);
        for (Reigns.Skill s : Reigns.Skill.values()) {
            CompoundTag st = new CompoundTag();
            st.putString("title", s.title);
            st.putString("effect", s.effect);
            st.putString("line", s.line.words);
            st.putInt("tier", s.tier);
            st.putBoolean("has", b.skills().contains(s));
            st.putBoolean("can", l != null && Reigns.canTake(id, l, s));
            skills.add(st);
        }
        t.put("skills", skills);
        t.putInt("level", b.level());
        t.putInt("xp", b.xp());
        t.putInt("next", b.next());
        t.putInt("free", b.free());
        Values.Value heart = Reigns.heart(id);
        t.putString("heart", heart == null ? "" : heart.type);
        t.putString("office", heart == null ? "" : Reigns.officeWords(heart));
        ListTag legacies = new ListTag();
        for (Reigns.Legacy x : Reigns.legacies(id)) {
            CompoundTag lt = new CompoundTag();
            lt.putString("name", x.name());
            lt.putString("leader", x.title() + " " + x.leader());
            lt.putLong("from", x.from());
            lt.putLong("to", x.to());
            lt.putString("effect", x.deed().effect);
            legacies.add(lt);
        }
        t.put("legacies", legacies);
        ListTag ways = new ListTag();
        for (CityTree.Civic[] pr : CityTree.pairs()) {
            CompoundTag wt = new CompoundTag();
            wt.putString("a", pr[0].title);
            wt.putString("b", pr[1].title);
            wt.putString("branch", pr[0].branch.title);
            wt.putString("chosen", CityTree.has(id, pr[0]) ? pr[0].title : CityTree.has(id, pr[1]) ? pr[1].title : "");
            ways.add(wt);
        }
        t.put("ways", ways);
        ListTag quirks = new ListTag();
        Map<Quirks.Quirk, Integer> tally = Quirks.tally(id);
        List<Map.Entry<Quirks.Quirk, Integer>> qs = new ArrayList<>(tally.entrySet());
        qs.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        for (Map.Entry<Quirks.Quirk, Integer> e : qs) {
            CompoundTag qt = new CompoundTag();
            qt.putString("title", e.getKey().title);
            qt.putString("effect", e.getKey().effect);
            qt.putInt("n", e.getValue());
            quirks.add(qt);
        }
        t.put("quirks", quirks);
        t.putString("known", Quirks.knownFor(id));
        Map<FolkSkills.Knack, Integer> knacks = new EnumMap<>(FolkSkills.Knack.class);
        int masters = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            for (FolkSkills.Chosen c : f.knacks().chosen()) {
                knacks.merge(c.knack(), 1, Integer::sum);
                if (c.knack().family == FolkSkills.Family.MASTER) masters++;
            }
        }
        ListTag kl = new ListTag();
        List<Map.Entry<FolkSkills.Knack, Integer>> ks = new ArrayList<>(knacks.entrySet());
        ks.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        for (Map.Entry<FolkSkills.Knack, Integer> e : ks) {
            CompoundTag kt = new CompoundTag();
            kt.putString("title", e.getKey().title);
            kt.putString("family", e.getKey().family.title);
            kt.putInt("n", e.getValue());
            kl.add(kt);
        }
        t.put("knacks", kl);
        t.putInt("masters", masters);
        t.putInt("knack_kinds", FolkSkills.Knack.values().length);
        t.putInt("renown", Villages.renown(id));
        t.put("identity", strings(identityLines(id)));
        return t;
    }

    private static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s.length() > 400 ? s.substring(0, 400) : s));
        return l;
    }

    /** /village perks: the town's perks, a line at a time. */
    public static List<String> lines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        out.add("PERKS " + Villages.name(village) + ":");
        out.addAll(identityLines(village));
        out.addAll(Reigns.lines(village, level.getDayTime() / 24000L));
        Map<Quirks.Quirk, Integer> tally = Quirks.tally(village);
        if (!tally.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Quirks.Quirk, Integer> e : tally.entrySet()) parts.add(e.getKey().title + " " + e.getValue());
            out.add("Quirks among its folk: " + String.join(", ", parts) + ".");
        }
        return out;
    }

    // ------------------------------------------------------------------ the Leader's page

    /**
     * A player who leads, on its Leader's page (PlayerLeader.openPage): the town's research, and the next civics it
     * may set the town to (a button each); its own skills, and the ones it may take (a button each).
     */
    public static void leaderPage(UUID village, ServerPlayer p, StringBuilder sb, List<String> buttons) {
        if (!(p.level() instanceof ServerLevel level)) return;
        CityTree.Civic now = CityTree.current(village);
        sb.append("\n\nResearch: ").append(CityTree.done(village).size()).append(" of ").append(CityTree.ALL).append(" civics done")
            .append(now == null ? "; nothing chosen" : "; studying " + now.title).append(".");
        int shown = 0;
        for (CityTree.Weighed w : CityTree.weigh(level, village)) {
            if (shown >= 4) break;
            if (w.civic() == now) continue;
            buttons.add("Study: " + w.civic().title + "\tvillage perks study " + w.civic().key() + "\t" + w.civic().effect
                + (w.civic().rival() == null ? "" : " (closes " + w.civic().rival().title + ")"));
            shown++;
        }
        Reigns.Book b = Reigns.book(village, p.getUUID());
        List<String> has = new ArrayList<>();
        for (Reigns.Skill s : b.skills()) has.add(s.title);
        sb.append("\nYour skills: ").append(has.isEmpty() ? "none yet" : String.join(", ", has)).append(" (level ").append(b.level())
            .append(", ").append(b.xp()).append(" experience").append(b.free() > 0 ? ", " + b.free() + " to choose" : "").append(").");
        for (Reigns.Skill s : Reigns.Skill.values()) {
            if (Reigns.canTake(village, p.getUUID(), s)) buttons.add("Skill: " + s.title + "\tvillage perks skill " + s.key() + "\t" + s.effect);
        }
        String leg = Reigns.legacyWords(village);
        if (!leg.isEmpty()) sb.append("\nThe town's legacies: ").append(leg).append(".");
    }

    // ------------------------------------------------------------------ /village perks

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("perks")
            .executes(ctx -> say(ctx, v -> String.join("\n", lines(ctx.getSource().getLevel(), v.id()))))
            .then(Commands.literal("wonders")
                .executes(ctx -> say(ctx, v -> "WONDERS of the world:\n" + String.join("\n", Wonders.worldLines())))
                .then(Commands.literal("show").requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        List<String> out = PerksStage.showcase(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                        return out.size();
                    })))
            .then(Commands.literal("leader")
                .executes(ctx -> say(ctx, v -> String.join("\n", Reigns.lines(v.id(), ctx.getSource().getLevel().getDayTime() / 24000L)))))
            .then(Commands.literal("study")
                .then(Commands.argument("civic", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(civicKeys(), b))
                    .executes(Perks::study)))
            .then(Commands.literal("skill")
                .then(Commands.argument("skill", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(skillKeys(), b))
                    .executes(Perks::skill)))
            .then(Commands.literal("quirks")
                .executes(ctx -> say(ctx, v -> quirkLines(v.id())))
                .then(Commands.literal("give").requires(src -> src.hasPermission(2))
                    .then(Commands.argument("quirk", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(quirkKeys(), b))
                        .executes(Perks::giveQuirk))))
            .then(Commands.literal("reign").requires(src -> src.hasPermission(2))
                .then(Commands.literal("end").executes(ctx -> say(ctx, v -> {
                    Reigns.Reign r = Reigns.reign(v.id());
                    if (r == null) return "No reign to end.";
                    Reigns.end(ctx.getSource().getLevel(), v, r, ctx.getSource().getLevel().getDayTime() / 24000L);
                    return "REIGN ended: " + Reigns.legacyWords(v.id());
                })))
                .then(Commands.literal("xp").then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> say(ctx, v -> {
                        Reigns.xp(v.id(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n"));
                        return String.join("\n", Reigns.lines(v.id(), ctx.getSource().getLevel().getDayTime() / 24000L));
                    })))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", PerksStage.stage(ctx.getSource().getLevel(), v,
                    BlockPos.containing(ctx.getSource().getPosition()))))));
    }

    static List<String> civicKeys() {
        List<String> out = new ArrayList<>();
        for (CityTree.Civic c : CityTree.Civic.values()) out.add(c.key());
        return out;
    }

    static List<String> skillKeys() {
        List<String> out = new ArrayList<>();
        for (Reigns.Skill s : Reigns.Skill.values()) out.add(s.key());
        return out;
    }

    static List<String> quirkKeys() {
        List<String> out = new ArrayList<>();
        for (Quirks.Quirk q : Quirks.Quirk.values()) out.add(q.key());
        return out;
    }

    static String quirkLines(UUID village) {
        StringBuilder sb = new StringBuilder("QUIRKS " + Villages.name(village) + ":");
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            String c = Quirks.cardLine(f);
            if (!c.isEmpty()) sb.append("\n").append(f.displayNameCap()).append(": ").append(c);
        }
        String known = Quirks.knownFor(village);
        if (!known.isEmpty()) sb.append("\nThe town is ").append(known).append(".");
        return sb.toString();
    }

    /** /village perks study &lt;civic&gt;: the player who leads sets the town's study (an operator may for any town). */
    static int study(CommandContext<CommandSourceStack> ctx) {
        return say(ctx, v -> {
            CityTree.Civic c = CityTree.byKey(StringArgumentType.getString(ctx, "civic"));
            if (c == null) return "No such civic.";
            ServerPlayer p = ctx.getSource().getPlayer();
            boolean leads = p != null && PlayerLeader.leads(v.id(), p.getUUID());
            if (!leads && !ctx.getSource().hasPermission(2)) return "Only the town's leader may set its study.";
            ServerLevel level = ctx.getSource().getLevel();
            String who = leads ? PlayerLeader.styled(v.id()) : "By order";
            String said = CityTree.leaderPick(level, v.id(), c, level.getDayTime() / 24000L, who);
            if (leads) PlayerLeader.openPage(p);
            return "RESEARCH " + Villages.name(v.id()) + ": " + said;
        });
    }

    /** /village perks skill &lt;skill&gt;: the player who leads takes a skill (an operator, for the folk who leads). */
    static int skill(CommandContext<CommandSourceStack> ctx) {
        return say(ctx, v -> {
            Reigns.Skill s = Reigns.Skill.byKey(StringArgumentType.getString(ctx, "skill"));
            if (s == null) return "No such skill.";
            ServerPlayer p = ctx.getSource().getPlayer();
            boolean leads = p != null && PlayerLeader.leads(v.id(), p.getUUID());
            UUID leader = Villages.elder(v.id());
            if (!leads && !ctx.getSource().hasPermission(2) || leader == null) return "Only the town's leader may take a leader's skill.";
            long day = ctx.getSource().getLevel().getDayTime() / 24000L;
            String said = Reigns.take(v.id(), leader, s, day, leads ? PlayerLeader.styled(v.id()) : Reigns.leaderNameOf(v.id(), leader));
            if (leads) PlayerLeader.openPage(p);
            return "SKILL " + said;
        });
    }

    /** /village perks quirks give &lt;quirk&gt; (ops): the nearest folk given the quirk (on top of its own). */
    static int giveQuirk(CommandContext<CommandSourceStack> ctx) {
        Quirks.Quirk q = Quirks.Quirk.byKey(StringArgumentType.getString(ctx, "quirk"));
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.world.phys.Vec3 at = ctx.getSource().getPosition();
        VillageFolkEntity near = null;
        double best = 64 * 64;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(16))) {
            double d = f.distanceToSqr(at);
            if (d < best) { best = d; near = f; }
        }
        if (q == null || near == null) {
            ctx.getSource().sendFailure(Component.literal(q == null ? "No such quirk." : "No folk near."));
            return 0;
        }
        java.util.EnumSet<Quirks.Quirk> qs = java.util.EnumSet.copyOf(Quirks.of(near).isEmpty() ? java.util.EnumSet.of(q) : Quirks.of(near));
        qs.add(q);
        Quirks.set(near, qs, "");
        String name = near.displayNameCap();
        ctx.getSource().sendSuccess(() -> Component.literal("QUIRK " + name + ": " + q.title), true);
        return 1;
    }

    static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), 200);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village near enough."));
            return 0;
        }
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: Fleet's weather for this town ("the rain", null if the boats go out), with the tests' weather as set there. */
    @Nullable
    public static String keptInForTests(ServerLevel level, UUID village) {
        return Fleet.keptIn(level, village, level.getDayTime() / 24000L);
    }

    /** Tests: how much a town keeps crime down by itself (Mischief.prevention), the perks counted. */
    public static double preventionForTests(Villages.Village v) {
        return Mischief.prevention(v);
    }

    /** Tests: what a caravan from {@code from} to {@code to} would carry, lots, without taking anything. */
    public static int caravanLotsForTests(ServerLevel level, Villages.Village from, UUID to) {
        return Caravans.load(level, from, to, false, false).size();
    }

    /** Tests: a cold caught now, and its length in ticks. */
    public static int coldForTests(ServerLevel level, VillageFolkEntity f) {
        Health.catchCold(level, f, "caught for the test", level.getDayTime() / 24000L);
        return f.health().cold;
    }

    /** Tests: a folk's mood worked out now, and its reasons' keys. */
    public static List<String> moodKeysForTests(VillageFolkEntity f) {
        f.ensurePersona();
        f.refreshMood();
        return new ArrayList<>(f.persona().moodWhy());
    }

    /** Tests: a busker's better chance (over the plain), from its town's perks and its own. */
    public static double tipsForTests(VillageFolkEntity busker) {
        return tips(busker);
    }

    /** The world's day now (the overworld's clock), or nought with no server. */
    static long today() {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return s == null ? 0L : s.overworld().getDayTime() / 24000L;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static Direction north() {
        return Direction.NORTH;
    }
}
