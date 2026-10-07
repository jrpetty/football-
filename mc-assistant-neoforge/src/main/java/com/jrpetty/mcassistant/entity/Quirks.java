package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [perks] A folk's quirks: one or two personal traits it has from birth (or from the founding), whatever its
 * trade, each with a real effect, on its card and in what it says. (Not its habit, the little thing it does that
 * others notice, "hums while working" (Persona): a quirk is what it is good or bad at.)
 * <ul>
 * <li><b>Early Bird</b>: at its best in the morning, five in the hundred quicker before noon.</li>
 * <li><b>Night Owl</b>: five quicker after noon; on the night watch, two harder blows and four blocks' further sight.</li>
 * <li><b>Green Fingers</b>: what it tends grows: at the fields (or its garden) a crop near it comes on a stage every
 *     eight seconds.</li>
 * <li><b>Iron Stomach</b>: a fifth longer between meals, and a missed meal hardly bothers it.</li>
 * <li><b>Lucky</b>: one ore, one harvest or one haul in twelve gives one more.</li>
 * <li><b>Clumsy</b>: three slower, and now and then drops what it is carrying (and is laughed at for it).</li>
 * <li><b>Broad Shoulders</b>: carries sixteen more on every load.</li>
 * <li><b>Fleet-footed</b>: walks eight in the hundred faster.</li>
 * <li><b>Smooth-talker</b>: pays five in the hundred less at the counter, and as an envoy is heard the warmer.</li>
 * <li><b>Homebody</b>: 3 the happier at home of an evening, 4 the lower on a trip, and the last sent on one.</li>
 * <li><b>Wanderlust</b>: 4 the happier on a trip, a scout ten quicker, and the first sent with a caravan.</li>
 * <li><b>Born Leader</b>: the town looks to it: it stands for office sooner and draws more votes.</li>
 * <li><b>Bookworm</b>: learns its trade a tenth faster, and reads in its own time.</li>
 * <li><b>Animal Lover</b>: a rancher or a beekeeper eight quicker; the herds thrive under it.</li>
 * <li><b>Fearless</b>: never shaken by a blow or the bell, hits a point harder, and runs to help a neighbour a
 *     monster has set on, whatever its trade.</li>
 * <li><b>Squeamish</b>: never a hunter, and never the healer.</li>
 * <li><b>Musical</b>: takes up music, and a passer-by is the likelier to drop a coin in its hat; 2 the happier for
 *     an evening's playing.</li>
 * <li><b>Hawk-eyed</b>: a guard on the wall picks its mark four blocks further out.</li>
 * <li><b>Hardy</b>: mends half a heart every eight seconds, and a cold is gone in half the time.</li>
 * <li><b>Frail</b>: two hearts less, and catches cold twice as easily.</li>
 * </ul>
 * A founder has its quirks from the founding (rolled once, on its first slow beat, from its own id); a child has
 * them at birth, and one time in two takes one of its parents' (its card says whose). The town's quirks show in it:
 * the trades go to the folk they suit (a Green Fingers to the fields, never a Squeamish to the hunt: the job pull,
 * {@link #pull}), and a town where a quirk runs in a fifth of its folk is known for it ("known for its luck").
 *
 * <p>Kept on the folk with the rest of what the game keeps on it (its persistent data, "mca_quirks"), and read into
 * memory once.
 */
public final class Quirks {

    private Quirks() {}

    public enum Quirk {
        EARLY_BIRD("Early Bird", "+5% pace before noon", "an early bird", "the early bird catches the worm"),
        NIGHT_OWL("Night Owl", "+5% pace after noon; better on the night watch", "a night owl", "I come alive after dark"),
        GREEN_FINGERS("Green Fingers", "crops it tends grow faster", "green-fingered", "things grow for me"),
        IRON_STOMACH("Iron Stomach", "a fifth longer between meals", "iron-stomached", "I can eat anything, and not much of it"),
        LUCKY("Lucky", "one find in twelve gives one more", "lucky", "I was born lucky"),
        CLUMSY("Clumsy", "3% slower; drops things now and then", "clumsy", "I'm all thumbs, me"),
        BROAD_SHOULDERS("Broad Shoulders", "carries 16 more on every load", "broad-shouldered", "I can carry a cart's worth"),
        FLEET_FOOTED("Fleet-footed", "walks 8% faster", "fleet-footed", "I'm quick on my feet"),
        SMOOTH_TALKER("Smooth-talker", "pays 5% less; a warmer envoy", "a smooth talker", "I can talk anybody round"),
        HOMEBODY("Homebody", "happier at home, unhappy on a trip", "a homebody", "there's no place like home"),
        WANDERLUST("Wanderlust", "happy on a trip; a quicker scout", "a wanderer", "I'd love to see what's over the hill"),
        BORN_LEADER("Born Leader", "the town looks to it: stands for office, wins votes", "a born leader", "folk listen when I speak"),
        BOOKWORM("Bookworm", "learns its trade a tenth faster", "a bookworm", "I always have my nose in a book"),
        ANIMAL_LOVER("Animal Lover", "herds and hives 8% quicker", "an animal lover", "beasts trust me"),
        FEARLESS("Fearless", "never shaken; +1 attack; runs to help", "fearless", "nothing frightens me"),
        SQUEAMISH("Squeamish", "never a hunter or the healer", "squeamish", "I can't stand the sight of blood"),
        MUSICAL("Musical", "busks well; plays of an evening", "musical", "there's always a tune in my head"),
        HAWK_EYED("Hawk-eyed", "a guard sees 4 further", "hawk-eyed", "I can spot a rabbit at a hundred paces"),
        HARDY("Hardy", "mends fast; a cold half as long", "hardy", "I'm never ill for long"),
        FRAIL("Frail", "two hearts less; colds come easy", "frail", "I catch every cold going");

        public final String title, effect, adjective, says;

        Quirk(String title, String effect, String adjective, String says) {
            this.title = title;
            this.effect = effect;
            this.adjective = adjective;
            this.says = says;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The quirk it cannot have with this one, or null. */
        @Nullable
        public Quirk opposite() {
            return switch (this) {
                case EARLY_BIRD -> NIGHT_OWL;
                case NIGHT_OWL -> EARLY_BIRD;
                case HOMEBODY -> WANDERLUST;
                case WANDERLUST -> HOMEBODY;
                case HARDY -> FRAIL;
                case FRAIL -> HARDY;
                case FEARLESS -> SQUEAMISH;
                case SQUEAMISH -> FEARLESS;
                default -> null;
            };
        }

        @Nullable
        public static Quirk byKey(@Nullable String k) {
            if (k == null) return null;
            String n = k.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Quirk q : values()) if (q.name().equals(n) || q.title.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_').equals(n)) return q;
            return null;
        }
    }

    static final String KEY = "mca_quirks", FROM = "mca_quirks_from";

    /** What each folk has, read once: by its id. */
    private static final Map<UUID, EnumSet<Quirk>> READ = new ConcurrentHashMap<>();

    static void reset() {
        READ.clear();
    }

    /** Its quirks (none until it has them). */
    public static EnumSet<Quirk> of(VillageFolkEntity f) {
        EnumSet<Quirk> got = READ.get(f.getUUID());
        if (got != null) return got;
        EnumSet<Quirk> set = EnumSet.noneOf(Quirk.class);
        CompoundTag data = f.getPersistentData();
        if (data.contains(KEY)) for (String k : data.getString(KEY).split(",")) {
            Quirk q = Quirk.byKey(k);
            if (q != null) set.add(q);
        }
        READ.put(f.getUUID(), set);
        return set;
    }

    public static boolean has(@Nullable VillageFolkEntity f, Quirk q) {
        return f != null && of(f).contains(q);
    }

    /** Has it been given its quirks yet (rolled, born with them, or set)? */
    static boolean given(VillageFolkEntity f) {
        return f.getPersistentData().contains(KEY);
    }

    /** These quirks and no others (the roll, a birth, the operators' command, the tests), and whose they came from. */
    public static void set(VillageFolkEntity f, EnumSet<Quirk> qs, String from) {
        List<String> keys = new ArrayList<>();
        for (Quirk q : qs) keys.add(q.name());
        f.getPersistentData().putString(KEY, String.join(",", keys));
        if (from.isEmpty()) f.getPersistentData().remove(FROM);
        else f.getPersistentData().putString(FROM, from);
        READ.put(f.getUUID(), EnumSet.copyOf(qs.isEmpty() ? EnumSet.noneOf(Quirk.class) : qs));
        // A musical folk takes up music, a bookworm reading, if it has a pastime to change.
        if (f.persona().rolled()) {
            if (qs.contains(Quirk.MUSICAL)) f.persona().hobby = Persona.Hobby.MUSIC;
            else if (qs.contains(Quirk.BOOKWORM)) f.persona().hobby = Persona.Hobby.READING;
            else if (qs.contains(Quirk.GREEN_FINGERS) && f.persona().hobby == Persona.Hobby.WALKING) f.persona().hobby = Persona.Hobby.GARDENING;
        }
    }

    /** Where an inherited quirk came from ("Lucky, like its mother Ada"), or "". */
    static String from(VillageFolkEntity f) {
        CompoundTag d = f.getPersistentData();
        return d.contains(FROM) ? d.getString(FROM) : "";
    }

    // ------------------------------------------------------------------ given

    /** One or two, never a pair at odds: the folk's own, from its id, so a founder's are the same every time. */
    static EnumSet<Quirk> roll(RandomSource r, @Nullable Quirk start) {
        EnumSet<Quirk> out = EnumSet.noneOf(Quirk.class);
        if (start != null) out.add(start);
        int want = r.nextInt(5) < 3 ? 1 : 2;
        Quirk[] all = Quirk.values();
        int tries = 0;
        while (out.size() < want && tries++ < 40) {
            Quirk q = all[r.nextInt(all.length)];
            if (out.contains(q)) continue;
            boolean clash = false;
            for (Quirk o : out) if (o.opposite() == q) clash = true;
            if (!clash) out.add(q);
        }
        return out;
    }

    /** A founder (or any folk from before quirks) given its own, on its first slow beat: from its id, the same every time. */
    static void ensure(VillageFolkEntity f) {
        if (given(f) || !Perks.live() || f.isShowcase()) return;
        UUID id = f.getUUID();
        RandomSource r = RandomSource.create(id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 31L));
        set(f, roll(r, null), "");
    }

    /**
     * A child born (VillageFolkEntity.bear): one time in two it takes one of its parents' quirks, the rest its own.
     * Returns the quirk it took from a parent, or null.
     */
    @Nullable
    public static Quirk born(VillageFolkEntity child, VillageFolkEntity a, VillageFolkEntity b) {
        if (!Perks.live()) return null;
        return inherit(child, a, b, child.getRandom().nextBoolean());
    }

    /** As {@link #born}, the coin already tossed: {@code fromParent} takes one of a parent's quirks, if they have any. */
    static Quirk inherit(VillageFolkEntity child, VillageFolkEntity a, VillageFolkEntity b, boolean fromParent) {
        RandomSource r = child.getRandom();
        Quirk took = null;
        String whose = "";
        if (fromParent) {
            List<Object[]> pool = new ArrayList<>();
            for (Quirk q : of(a)) pool.add(new Object[]{ q, a });
            for (Quirk q : of(b)) pool.add(new Object[]{ q, b });
            if (!pool.isEmpty()) {
                Object[] pick = pool.get(r.nextInt(pool.size()));
                took = (Quirk) pick[0];
                whose = ((VillageFolkEntity) pick[1]).displayNameCap();
            }
        }
        EnumSet<Quirk> qs = roll(r, took);
        set(child, qs, took == null ? "" : took.name() + "|" + whose);
        return took;
    }

    // ------------------------------------------------------------------ what they do

    /** Its pace, in percent: the hour of the day, its clumsy hands, its wanderlust out scouting, its way with beasts. */
    public static int workPercent(VillageFolkEntity f) {
        EnumSet<Quirk> qs = of(f);
        if (qs.isEmpty() || f.isBaby()) return 0;
        StationTask t = f.stationTask();
        if (t == StationTask.NONE) return 0;
        int p = 0;
        long tod = f.level().getDayTime() % 24000L;
        if (qs.contains(Quirk.EARLY_BIRD) && tod < 6000L) p += 5;
        if (qs.contains(Quirk.NIGHT_OWL) && tod >= 6000L) p += 5;
        if (qs.contains(Quirk.CLUMSY)) p -= 3;
        if (qs.contains(Quirk.WANDERLUST) && t == StationTask.SCOUT) p += 10;
        if (qs.contains(Quirk.ANIMAL_LOVER) && (t == StationTask.RANCH || t == StationTask.BEEKEEP)) p += 8;
        return p;
    }

    /** The time between its meals, in percent: an Iron Stomach's 120. */
    public static int upkeepPercent(VillageFolkEntity f) {
        return has(f, Quirk.IRON_STOMACH) ? 120 : 100;
    }

    /** Broad Shoulders: so many more on each load. */
    public static int haulBonus(VillageFolkEntity f) {
        return has(f, Quirk.BROAD_SHOULDERS) ? 16 : 0;
    }

    /** A Smooth-talker's price at the counter, in percent. */
    static int pricePercent(@Nullable VillageFolkEntity buyer) {
        return buyer != null && has(buyer, Quirk.SMOOTH_TALKER) ? 95 : 100;
    }

    /** Hawk-eyed, and a Night Owl at night: blocks further a guard picks its mark. */
    static double sight(VillageFolkEntity f) {
        double s = has(f, Quirk.HAWK_EYED) ? 4.0 : 0.0;
        if (has(f, Quirk.NIGHT_OWL) && f.level().isNight()) s += 4.0;
        return s;
    }

    /** Bookworm: experience more, as FolkSkills.extraXp counts it (a tenth, the odd part by chance). */
    static int extraXp(VillageFolkEntity f, int amount) {
        if (amount <= 0 || !has(f, Quirk.BOOKWORM)) return 0;
        return amount / 10 + (f.getRandom().nextInt(10) < amount % 10 ? 1 : 0);
    }

    /** Lucky: does this find give one more (one in twelve)? */
    static boolean lucky(VillageFolkEntity f) {
        return has(f, Quirk.LUCKY) && f.getRandom().nextInt(12) == 0;
    }

    /** A cold's chance: a Frail folk's odds halved (one in four, where another's are one in eight). */
    static int coldOdds(VillageFolkEntity f, int odds) {
        return has(f, Quirk.FRAIL) ? Math.max(1, odds / 2) : odds;
    }

    /** A cold's length, in percent: a Hardy folk's half. */
    static int coldPercent(VillageFolkEntity f) {
        return has(f, Quirk.HARDY) ? 50 : 100;
    }

    /** Who goes with a caravan (Caravans.choose): less is first. A wanderer first, a homebody last. */
    static double tripPull(VillageFolkEntity f) {
        return has(f, Quirk.WANDERLUST) ? -2e5 : has(f, Quirk.HOMEBODY) ? 2e5 : 0;
    }

    /** Who goes as an envoy (Envoys.chooseEnvoy): more is first. */
    static int envoyPull(VillageFolkEntity f) {
        return (has(f, Quirk.SMOOTH_TALKER) ? 25 : 0) + (has(f, Quirk.WANDERLUST) ? 15 : 0) - (has(f, Quirk.HOMEBODY) ? 30 : 0);
    }

    /** A Smooth-talker sent as an envoy: so much warmer every answer it is given. */
    static int envoyWarmth(@Nullable VillageFolkEntity envoy) {
        return envoy != null && has(envoy, Quirk.SMOOTH_TALKER) ? 8 : 0;
    }

    /** A Musical busker: its better chance of a coin from a listener. */
    static double tips(VillageFolkEntity busker) {
        return has(busker, Quirk.MUSICAL) ? 0.15 : 0.0;
    }

    /** A Born Leader: what the town thinks of it more, standing and voting. */
    public static int standing(@Nullable VillageFolkEntity f) {
        return f != null && has(f, Quirk.BORN_LEADER) ? 15 : 0;
    }

    /** Does a Fearless folk run to help a neighbour a monster has set on, whatever its trade? */
    public static boolean answersCries(VillageFolkEntity f) {
        return has(f, Quirk.FEARLESS) && !f.isBaby();
    }

    /**
     * The job pull: how well this trade suits it, in levels' worth (VillageFolkEntity.betterHandFor, JobMarket.fitFor):
     * a Green Fingers at the fields, an Animal Lover at the herds, a Wanderlust out scouting, a Hawk-eyed or Fearless
     * folk on the watch, a Bookworm at the enchanting table, a Musical folk at the café's counter; never a Squeamish
     * folk at the hunt.
     */
    public static int pull(VillageFolkEntity f, StationTask t) {
        EnumSet<Quirk> qs = of(f);
        if (qs.isEmpty()) return 0;
        int p = 0;
        if (qs.contains(Quirk.GREEN_FINGERS) && t == StationTask.FARM) p += 4;
        if (qs.contains(Quirk.ANIMAL_LOVER) && (t == StationTask.RANCH || t == StationTask.BEEKEEP)) p += 4;
        if (qs.contains(Quirk.WANDERLUST) && t == StationTask.SCOUT) p += 4;
        if ((qs.contains(Quirk.HAWK_EYED) || qs.contains(Quirk.FEARLESS)) && t == StationTask.GUARD) p += 3;
        if (qs.contains(Quirk.BOOKWORM) && t == StationTask.ENCHANT) p += 3;
        if (qs.contains(Quirk.BROAD_SHOULDERS) && (t == StationTask.HAUL || t == StationTask.MINE)) p += 2;
        if (qs.contains(Quirk.SQUEAMISH) && t == StationTask.HUNT) p -= 100;
        if (qs.contains(Quirk.HOMEBODY) && t == StationTask.SCOUT) p -= 4;
        return p;
    }

    /** Would it refuse this trade outright (a Squeamish folk the hunt)? */
    public static boolean refuses(VillageFolkEntity f, StationTask t) {
        return t == StationTask.HUNT && has(f, Quirk.SQUEAMISH);
    }

    /** The trade it takes up when the town wants this one of it: a Squeamish folk the fields, never the hunt. */
    public static StationTask instead(VillageFolkEntity f, StationTask wanted) {
        return refuses(f, wanted) ? StationTask.FARM : wanted;
    }

    // ------------------------------------------------------------------ its spirits

    /** Its spirits (Perks.mood): at home, on a trip, a blow shrugged off, an evening's music. */
    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        EnumSet<Quirk> qs = of(f);
        if (qs.isEmpty()) return m;
        boolean away = f.trip() != null;
        if (qs.contains(Quirk.HOMEBODY)) {
            BlockPos home = f.ownerId() == null ? null : Homes.homeOf(f);
            if (away) { m -= 4; why.add(new Object[]{"homesick", 4}); }
            else if (home != null && f.offWorkNow() && f.blockPosition().closerThan(home, 12)) { m += 3; why.add(new Object[]{"athome", 3}); }
        }
        if (qs.contains(Quirk.WANDERLUST) && away) { m += 4; why.add(new Object[]{"wander", 4}); }
        if (qs.contains(Quirk.FEARLESS) && day - f.persona().hurtDay <= 1) { m += 15; why.add(new Object[]{"fearless", 1}); }
        if (qs.contains(Quirk.MUSICAL) && day - f.persona().hobbyDay() <= 1 && f.persona().hobby() == Persona.Hobby.MUSIC) {
            m += 2;
            why.add(new Object[]{"tune", 2});
        }
        return m;
    }

    /** In its own words, for a reason its quirks gave its spirits. */
    static String moodWords(VillageFolkEntity f, String why) {
        RandomSource r = f.getRandom();
        return switch (why) {
            case "homesick" -> FolkTalk.pick(r, "I miss my own bed. Give me home any day.", "I'd sooner be at home, truth be told.");
            case "athome" -> FolkTalk.pick(r, "There's nothing like an evening at home.", "Home, a chair and the fire. That's all I ask.");
            case "wander" -> FolkTalk.pick(r, "On the road again — this is the life!", "New places, new faces. I love it.");
            case "fearless" -> "Somebody had a go at me. Didn't frighten me, mind.";
            case "tune" -> "I played a tune or two last night. Does the heart good.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ its slow beat

    private static final ResourceLocation WALK_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "quirk_fleet_footed");
    private static final ResourceLocation HEALTH_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "quirk_frail");
    private static final ResourceLocation HIT_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "quirk_blow");

    /**
     * Every eight seconds (CityTree.tend, through Perks.tend): its quirks given if it has none yet, their marks on it
     * (its step, its health, its blow), a Hardy folk's mending, a Green Fingers farmer's crops, a Clumsy one's slip.
     */
    static void tend(VillageFolkEntity f) {
        ensure(f);
        EnumSet<Quirk> qs = of(f);
        boolean grown = !f.isBaby();
        CityTree.modifier(f, Attributes.MOVEMENT_SPEED, WALK_ID, qs.contains(Quirk.FLEET_FOOTED) ? 0.08 : 0.0,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        CityTree.modifier(f, Attributes.MAX_HEALTH, HEALTH_ID, qs.contains(Quirk.FRAIL) ? -4.0 : 0.0, AttributeModifier.Operation.ADD_VALUE);
        double hit = grown && qs.contains(Quirk.FEARLESS) ? 1.0 : 0.0;
        if (grown && qs.contains(Quirk.NIGHT_OWL) && f.stationTask() == StationTask.GUARD && f.level().isNight()) hit += 2.0;
        CityTree.modifier(f, Attributes.ATTACK_DAMAGE, HIT_ID, hit, AttributeModifier.Operation.ADD_VALUE);
        if (f.getHealth() > f.getMaxHealth()) f.setHealth(f.getMaxHealth());
        if (qs.isEmpty() || !(f.level() instanceof ServerLevel level)) return;
        if (qs.contains(Quirk.HARDY) && f.isAlive() && f.getHealth() < f.getMaxHealth() && f.hurtTime == 0
                && f.tickCount - f.getLastHurtByMobTimestamp() > 100) f.heal(1.0F);
        if (qs.contains(Quirk.GREEN_FINGERS) && tending(f)) greenFingers(level, f);
        if (qs.contains(Quirk.CLUMSY) && grown && !f.offWorkNow() && f.getRandom().nextInt(75) == 0) slip(level, f);
    }

    /** Is it tending growing things now: a farmer at its work, or a gardener in its own time? */
    static boolean tending(VillageFolkEntity f) {
        if (f.isBaby()) return false;
        if (f.stationTask() == StationTask.FARM && !f.offWorkNow()) return true;
        return f.offWorkNow() && f.persona().rolled() && f.persona().hobby() == Persona.Hobby.GARDENING && !f.isSleeping();
    }

    /** Green Fingers: the least grown crop within four blocks comes on a stage. Returns whether one did. */
    static boolean greenFingers(ServerLevel level, VillageFolkEntity f) {
        BlockPos at = f.blockPosition();
        BlockPos best = null;
        int bestAge = Integer.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -1, -4), at.offset(4, 1, 4))) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof CropBlock crop) || crop.isMaxAge(s)) continue;
            int age = crop.getAge(s);
            if (age < bestAge) { bestAge = age; best = p.immutable(); }
        }
        if (best == null) return false;
        BlockState s = level.getBlockState(best);
        CropBlock crop = (CropBlock) s.getBlock();
        level.setBlock(best, crop.getStateForAge(crop.getAge(s) + 1), 2);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, best.getX() + 0.5, best.getY() + 0.6, best.getZ() + 0.5, 3, 0.25, 0.2, 0.25, 0.0);
        return true;
    }

    /** Clumsy: something slips out of its pack at its feet (it or the sweepers pick it up), and the nearest folk laughs. */
    static boolean slip(ServerLevel level, VillageFolkEntity f) {
        List<ItemStack> pack = f.getInventoryItems();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < pack.size(); i++) if (!pack.get(i).isEmpty() && !pack.get(i).isDamageableItem()) slots.add(i);
        if (slots.isEmpty()) return false;
        ItemStack s = pack.get(slots.get(f.getRandom().nextInt(slots.size())));
        ItemStack one = s.split(1);
        ItemEntity drop = new ItemEntity(level, f.getX(), f.getY() + 0.5, f.getZ(), one);
        drop.setPickUpDelay(40);
        level.addFreshEntity(drop);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Oops! Butterfingers.", "Not again! Where did that go?", "Whoops — dropped it."));
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0),
                o -> o != f && o.isAlive() && !o.isBaby())) {
            FolkTalk.speak(o, FolkTalk.pick(o.getRandom(), "Ha! Clumsy as ever, " + f.displayNameCap() + ".",
                "Mind your feet, " + f.displayNameCap() + "!", "Butterfingers!"));
            break;
        }
        return true;
    }

    // ------------------------------------------------------------------ telling

    /** Its card line ("Quirks"): "Lucky (like its mother Ada), Fleet-footed". */
    public static String cardLine(VillageFolkEntity f) {
        EnumSet<Quirk> qs = of(f);
        if (qs.isEmpty()) return "";
        String[] from = from(f).split("\\|", 2);
        List<String> out = new ArrayList<>();
        for (Quirk q : qs) {
            boolean inherited = from.length == 2 && from[0].equals(q.name());
            out.add(q.title + " (" + q.effect + (inherited ? "; from " + from[1] : "") + ")");
        }
        return String.join(", ", out);
    }

    /** In its own words, asked what it is like: "I was born lucky, and I'm quick on my feet." */
    public static String talk(VillageFolkEntity f) {
        EnumSet<Quirk> qs = of(f);
        if (qs.isEmpty()) return "";
        List<String> says = new ArrayList<>();
        for (Quirk q : qs) says.add(q.says);
        String s = CityTree.capital(String.join(", and ", says)) + ".";
        String[] from = from(f).split("\\|", 2);
        if (from.length == 2) {
            Quirk q = Quirk.byKey(from[0]);
            if (q != null) s += " Gets it from " + from[1] + ", they say.";
        }
        return s;
    }

    /** The town's folk's quirks, counted: the commonest first. */
    public static Map<Quirk, Integer> tally(@Nullable UUID village) {
        Map<Quirk, Integer> out = new EnumMap<>(Quirk.class);
        if (village == null) return out;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            for (Quirk q : of(f)) out.merge(q, 1, Integer::sum);
        }
        return out;
    }

    /**
     * What the town is known for in its folk, if a quirk runs in a fifth of them (four at least): "known for its
     * luck (9 of 30 are Lucky)"; or "".
     */
    public static String knownFor(@Nullable UUID village) {
        if (village == null) return "";
        int folk = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase()) folk++;
        Quirk best = null;
        int most = 0;
        for (Map.Entry<Quirk, Integer> e : tally(village).entrySet()) if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        if (best == null || most < 4 || most * 5 < folk) return "";
        return "known for " + fame(best) + " (" + most + " of " + folk + " are " + best.title + ")";
    }

    /** "its luck", "its green fingers", "its fleet feet". */
    static String fame(Quirk q) {
        return switch (q) {
            case EARLY_BIRD -> "its early risers";
            case NIGHT_OWL -> "its night owls";
            case GREEN_FINGERS -> "its green fingers";
            case IRON_STOMACH -> "its iron stomachs";
            case LUCKY -> "its luck";
            case CLUMSY -> "its clumsiness";
            case BROAD_SHOULDERS -> "its strong backs";
            case FLEET_FOOTED -> "its runners";
            case SMOOTH_TALKER -> "its silver tongues";
            case HOMEBODY -> "staying at home";
            case WANDERLUST -> "its wanderers";
            case BORN_LEADER -> "its leaders";
            case BOOKWORM -> "its readers";
            case ANIMAL_LOVER -> "its way with beasts";
            case FEARLESS -> "its courage";
            case SQUEAMISH -> "its gentle souls";
            case MUSICAL -> "its music";
            case HAWK_EYED -> "its marksmen";
            case HARDY -> "its hardy folk";
            case FRAIL -> "its frail health";
        };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: these quirks, now. */
    public static void setForTests(VillageFolkEntity f, Quirk... qs) {
        EnumSet<Quirk> set = EnumSet.noneOf(Quirk.class);
        for (Quirk q : qs) set.add(q);
        set(f, set, "");
    }

    /** Tests: a child given its quirks as at its birth, the coin tossed as asked. */
    @Nullable
    public static Quirk inheritForTests(VillageFolkEntity child, VillageFolkEntity a, VillageFolkEntity b, boolean fromParent) {
        return inherit(child, a, b, fromParent);
    }

    /** Tests: a Green Fingers beat, now. */
    public static boolean greenFingersForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level && greenFingers(level, f);
    }

    /** Tests: the quirks' slow beat, now. */
    public static void tendForTests(VillageFolkEntity f) {
        tend(f);
    }
}
