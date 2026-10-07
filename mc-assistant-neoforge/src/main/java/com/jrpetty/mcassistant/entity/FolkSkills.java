package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A folk's own knacks: what it chooses for itself as it grows in experience.
 *
 * <p>Every five levels of its best trade (5, 10, 15, 20, 25 and 30) a grown folk earns a knack point,
 * six in a lifetime at most; a child earns none. It spends each point on a knack of its own choosing,
 * by itself, at a quiet moment of its day (its break, or the evening before bed), one a day at most,
 * and says so out loud. It keeps what it chose for life, even when it changes its trade, though a
 * trade's knack only works while it works that trade. It remembers why it chose each one, and the
 * day, and so does its card: right-click it and look at its Skills page.
 *
 * <p>Twenty-four knacks in three families, each a slight and real thing wired where the work is done:
 * <ul>
 *   <li><b>Trade</b> (fourteen): a few in every hundred quicker at its own work (Steady Hands for the
 *   miner, Green Thumb for the farmer, Clean Cut for the woodcutter, Practised Hand at the bench,
 *   Patient at the water and in the woods, Gentle Hand with the herds and the hives, Tidy Shelves in
 *   the storehouse), or a little more of what the work gives (Keen Eye, an ore now and then; Careful
 *   Harvest, a seed back; Fire Tender, fuel that goes further; Strong Back, a bigger load on the
 *   round; Friendly Face, a tip at the counter), or a better guard (Drilled, a point of armour;
 *   Sharp Eyes, a longer look from the wall).</li>
 *   <li><b>Nature</b> (seven), one for each trait it might have: Bright Spirit for the cheerful,
 *   Early Riser for the hardworking, Good Company for the sociable, Unflappable for the easygoing,
 *   Quiet Focus for the shy, Quick Study for the curious, Grim Resolve for the grumpy. Open to
 *   anybody whose nature is not the opposite (a grump does not take Bright Spirit).</li>
 *   <li><b>Purse</b> (three): Thrifty (spends a tenth less), Haggler (a twentieth more in wages)
 *   and Nest Egg, once only: about three tenths of its house's price toward buying it.</li>
 * </ul>
 *
 * <p>[perks] And now fifty in four families. The trades that had none have their own (Pathfinder for the scout,
 * Sharp Ledger at the bank, Tunnel Rat in the caves, Strong Oar at the ferry, each 8% quicker), and the new trades
 * theirs, each shared with the nearest old trade (Surveyor's Eye, a scout a quarter further; Deep Lungs, three times the breath; Fireproof, half the
 * fire; Piglin-Friend, left be by the piglins; Blaze Hunter, a rod more from a blaze; True Shot,
 * arrows a quarter harder; Featherlight, eight arrows more; Iron Whisperer, golems mended; Tinkerer, the railway a
 * quarter quicker; Circuit Sense, redstone dust from the ore; Silver Tongue, the town's takings 5% higher; Showman, a
 * feast to remember). The <b>Master</b> family (ten) opens at level thirty in a trade: a master's pace (12%) and a
 * master's gift (the Master Miner's ore, the Master Grower's seed, the Master Host's tips, the Veteran's armour and
 * blow, the Master Porter's load), and the Grand Master's for the trades without a master of their own.
 *
 * <p>How it chooses: it scores every knack open to it by its trade (the knacks of the trade it
 * works, more as it masters it), its traits (the knack of its own nature), what it cares about
 * most (Values: a Merchant minds its coin, a Homemaker wants a house of its own, a Free Spirit its
 * ease, a Guardian the watch) and how it is placed (a household renting and saving to buy reaches
 * for Nest Egg first; a lonely folk for Good Company; a low one for Unflappable). Each folk's own
 * small leaning (from its id) breaks the ties, so two miners need not choose alike.
 *
 * <p>Nest Egg's coin is the village's. The treasury pays it at once out of what it can spare (what
 * it holds over what it is saving for and a day's wages), and what it cannot spare it sets aside
 * and pays a part at a time, each afternoon it has coin to spare, until it is paid. It goes into the
 * household's savings toward the house it rents if it wants to buy it (never past the price: the
 * household still saves the rest out of its wages), and otherwise into the folk's purse. Once a
 * household: a folk whose partner has had it cannot choose it again.
 */
public final class FolkSkills {

    private FolkSkills() {}

    /** A knack point at every so many levels of its best trade, and so many at most. */
    public static final int LEVELS_A_POINT = 5, MOST_POINTS = 6;
    /** Nest Egg: this share of the house's price, in the hundred. */
    public static final int NEST_EGG_PERCENT = 30;

    /** The families of knack. */
    public enum Family {
        TRADE("Trade"), NATURE("Nature"), PURSE("Purse"),
        /** [perks] A master's knack: open only at level thirty of one of its trades, and worth more. */
        MASTER("Master");

        public final String title;

        Family(String title) { this.title = title; }
    }

    /**
     * Every knack there is: its key (kept in the save and used by the commands), its name, its
     * family, what it does in a few words, how much quicker it makes its trade's work (pace knacks
     * only), the trait it belongs to (nature knacks only), why a folk would want it, and the trades
     * it works in (trade knacks only).
     */
    public enum Knack {
        // ---------------------------------------------------------------- trade
        STEADY_HANDS("steady_hands", "Steady Hands", Family.TRADE, "+5% pace at the mine", 5, null,
            "steadier hands at the rock face", StationTask.MINE),
        KEEN_EYE("keen_eye", "Keen Eye", Family.TRADE, "one ore in eight gives one more", 0, null,
            "an eye for a seam", StationTask.MINE),
        GREEN_THUMB("green_thumb", "Green Thumb", Family.TRADE, "+5% pace in the fields", 5, null,
            "the fields come easy to it", StationTask.FARM),
        CAREFUL_HARVEST("careful_harvest", "Careful Harvest", Family.TRADE, "one harvest in three gives a seed back", 0, null,
            "it wastes nothing at harvest", StationTask.FARM),
        CLEAN_CUT("clean_cut", "Clean Cut", Family.TRADE, "+5% pace felling trees", 5, null,
            "a clean swing of the axe", StationTask.WOOD),
        DRILLED("drilled", "Drilled", Family.TRADE, "+1 armour while it is a guard", 0, null,
            "drilled for the watch", StationTask.GUARD),
        SHARP_EYES("sharp_eyes", "Sharp Eyes", Family.TRADE, "picks out trouble 4 blocks further from the wall", 0, null,
            "it keeps a sharp lookout", StationTask.GUARD),
        PRACTISED_HAND("practised_hand", "Practised Hand", Family.TRADE, "+5% pace at the bench", 5, null,
            "a practised hand at the bench", StationTask.SMITH, StationTask.TAILOR, StationTask.BREW, StationTask.ENCHANT),
        FIRE_TENDER("fire_tender", "Fire Tender", Family.TRADE, "the furnaces' fuel goes about an eighth further", 0, null,
            "it knows how to keep a fire", StationTask.SMELT),
        STRONG_BACK("strong_back", "Strong Back", Family.TRADE, "carries 32 more on each load of its round", 0, null,
            "a strong back for the round", StationTask.HAUL),
        TIDY_SHELVES("tidy_shelves", "Tidy Shelves", Family.TRADE, "+5% pace in the storehouse", 5, null,
            "it keeps the stores in order", StationTask.STORE),
        PATIENT("patient", "Patient", Family.TRADE, "+5% pace fishing and hunting", 5, null,
            "the patience the water and the woods want", StationTask.FISH, StationTask.HUNT),
        GENTLE_HAND("gentle_hand", "Gentle Hand", Family.TRADE, "+5% pace with the herds and the hives", 5, null,
            "gentle with beasts and bees", StationTask.RANCH, StationTask.BEEKEEP),
        FRIENDLY_FACE("friendly_face", "Friendly Face", Family.TRADE, "+5% takings at its counter: a tip now and then", 0, null,
            "a friendly face behind the counter", StationTask.COOK, StationTask.SHOP),
        // ---------------------------------------------------------------- nature
        BRIGHT_SPIRIT("bright_spirit", "Bright Spirit", Family.NATURE, "mood +3, and its friends near it +1", 0, Social.Trait.CHEERFUL,
            "it likes to spread a little cheer"),
        EARLY_RISER("early_riser", "Early Riser", Family.NATURE, "its breaks are 15% shorter", 0, Social.Trait.HARDWORKING,
            "it can't sit still for long"),
        GOOD_COMPANY("good_company", "Good Company", Family.NATURE, "its friendships grow about a quarter quicker", 0, Social.Trait.SOCIABLE,
            "it likes people, and they like it"),
        UNFLAPPABLE("unflappable", "Unflappable", Family.NATURE, "its mood never falls below 35", 0, Social.Trait.EASYGOING,
            "it takes things as they come"),
        QUIET_FOCUS("quiet_focus", "Quiet Focus", Family.NATURE, "+3% pace with nobody within 8 blocks", 0, Social.Trait.SHY,
            "it works best on its own"),
        QUICK_STUDY("quick_study", "Quick Study", Family.NATURE, "+10% experience at its trade", 0, Social.Trait.CURIOUS,
            "it is always learning"),
        GRIM_RESOLVE("grim_resolve", "Grim Resolve", Family.NATURE, "+4% pace when its mood is low (under 45)", 0, Social.Trait.GRUMPY,
            "it works through a black mood"),
        // ---------------------------------------------------------------- purse
        THRIFTY("thrifty", "Thrifty", Family.PURSE, "spends 10% less at the shop, the café and the market", 0, null,
            "it minds its coin"),
        HAGGLER("haggler", "Haggler", Family.PURSE, "+5% on its wages: an extra coin now and then", 0, null,
            "it drives a hard bargain for its wage"),
        NEST_EGG("nest_egg", "Nest Egg", Family.PURSE, "once: about 30% of its house's price toward buying it", 0, null,
            "it wants a house of its own"),
        // ---------------------------------------------------------------- [perks] the trades that had none
        PATHFINDER("pathfinder", "Pathfinder", Family.TRADE, "+8% pace scouting", 8, null,
            "it never loses the way", StationTask.SCOUT),
        SURVEYORS_EYE("surveyors_eye", "Surveyor's Eye", Family.TRADE, "scouts a quarter further afield", 0, null,
            "an eye for the lie of the land", StationTask.CARTOGRAPHER, StationTask.SCOUT),
        SHARP_LEDGER("sharp_ledger", "Sharp Ledger", Family.TRADE, "+8% pace at the bank", 8, null,
            "it never loses a coin", StationTask.BANK),
        TUNNEL_RAT("tunnel_rat", "Tunnel Rat", Family.TRADE, "+8% pace in the caves", 8, null,
            "it is at home in the dark", StationTask.CAVE),
        STRONG_OAR("strong_oar", "Strong Oar", Family.TRADE, "+8% pace at the ferry", 8, null,
            "it pulls a steady oar", StationTask.FERRY),
        DEEP_LUNGS("deep_lungs", "Deep Lungs", Family.TRADE, "holds its breath three times as long", 0, null,
            "it is at home under water", StationTask.DIVER, StationTask.FISH),
        // ---------------------------------------------------------------- [perks] the new trades' (and the nearest old ones')
        FIREPROOF("fireproof", "Fireproof", Family.TRADE, "half the harm from fire and lava", 0, null,
            "it has walked through fire", StationTask.NETHER, StationTask.SMELT),
        PIGLIN_FRIEND("piglin_friend", "Piglin-Friend", Family.TRADE, "piglins leave it be (unless it strikes them)", 0, null,
            "it knows the piglins' ways", StationTask.NETHER),
        BLAZE_HUNTER("blaze_hunter", "Blaze Hunter", Family.TRADE, "a blaze it kills drops a rod more", 0, null,
            "it has a way with blazes", StationTask.NETHER),
        TRUE_SHOT("true_shot", "True Shot", Family.TRADE, "its arrows hit a quarter harder", 0, null,
            "its arrows fly true", StationTask.FLETCHER, StationTask.GUARD, StationTask.HUNT),
        FEATHERLIGHT("featherlight", "Featherlight", Family.TRADE, "8 more arrows in its quiver", 0, null,
            "it fletches light and true", StationTask.FLETCHER, StationTask.GUARD),
        IRON_WHISPERER("iron_whisperer", "Iron Whisperer", Family.TRADE, "iron golems near it mend", 0, null,
            "the golems trust it", StationTask.GOLEMS),
        TINKERER("tinkerer", "Tinkerer", Family.TRADE, "+8% pace at its trade; the railway laid 25% faster while it works", 8, null,
            "it can't leave a mechanism alone", StationTask.REDSTONE, StationTask.SMITH),
        CIRCUIT_SENSE("circuit_sense", "Circuit Sense", Family.TRADE, "one redstone ore in two gives four dust more", 0, null,
            "it can feel the redstone in the rock", StationTask.REDSTONE, StationTask.MINE, StationTask.CAVE),
        SILVER_TONGUE("silver_tongue", "Silver Tongue", Family.TRADE, "+5% on the town's takings while it works", 0, null,
            "it could sell sand in the desert", StationTask.EMERALD, StationTask.SHOP),
        SHOWMAN("showman", "Showman", Family.TRADE, "a feast it is at lifts the town 2 for two days", 0, null,
            "it knows how to put on a show", StationTask.FIREWORKS),
        // ---------------------------------------------------------------- [perks] the masters' (level thirty)
        MASTER_MINER("master_miner", "Master Miner", Family.MASTER, "+12% pace; one ore in five gives one more", 12, null,
            "a lifetime at the rock face", StationTask.MINE, StationTask.CAVE),
        MASTER_GROWER("master_grower", "Master Grower", Family.MASTER, "+12% pace; a seed back from every harvest", 12, null,
            "the fields know its hand", StationTask.FARM),
        MASTER_FORESTER("master_forester", "Master Forester", Family.MASTER, "+12% pace felling trees", 12, null,
            "it reads a tree like a book", StationTask.WOOD),
        MASTER_CRAFTSMAN("master_craftsman", "Master Craftsman", Family.MASTER, "+12% pace at the bench and the furnace", 12, null,
            "a master of its craft", StationTask.SMITH, StationTask.TAILOR, StationTask.BREW, StationTask.ENCHANT, StationTask.SMELT),
        MASTER_HOST("master_host", "Master Host", Family.MASTER, "+12% pace; a tip at every sale it can", 12, null,
            "nobody leaves its counter unhappy", StationTask.COOK, StationTask.SHOP),
        MASTER_HUNTSMAN("master_huntsman", "Master Huntsman", Family.MASTER, "+12% pace at the water and in the woods", 12, null,
            "it knows every fish and every deer", StationTask.FISH, StationTask.HUNT),
        MASTER_HERDSMAN("master_herdsman", "Master Herdsman", Family.MASTER, "+12% pace with the herds and the hives", 12, null,
            "the beasts come when it calls", StationTask.RANCH, StationTask.BEEKEEP),
        VETERAN("veteran", "Veteran", Family.MASTER, "+2 armour and +2 attack on the watch", 0, null,
            "it has stood a hundred watches", StationTask.GUARD),
        MASTER_PORTER("master_porter", "Master Porter", Family.MASTER, "+12% pace in the storehouse; 64 more a load", 12, null,
            "it could carry the town on its back", StationTask.HAUL, StationTask.STORE),
        GRAND_MASTER("grand_master", "Grand Master", Family.MASTER, "+12% pace at its trade", 12, null,
            "there is nothing left to teach it", StationTask.SCOUT, StationTask.BANK, StationTask.FERRY, StationTask.FLETCHER, StationTask.GOLEMS,
                StationTask.FIREWORKS, StationTask.CARTOGRAPHER, StationTask.EMERALD, StationTask.DIVER, StationTask.NETHER, StationTask.REDSTONE);

        public final String key, title, effect, why;
        public final Family family;
        /** How much quicker its trade's work goes, in percent (pace knacks only). */
        public final int pace;
        /** The trait it belongs to (nature knacks), or null. */
        @Nullable public final Social.Trait trait;
        /** The trades it works in (trade knacks), or none. */
        public final EnumSet<StationTask> trades;

        Knack(String key, String title, Family family, String effect, int pace, @Nullable Social.Trait trait, String why,
              StationTask... trades) {
            this.key = key;
            this.title = title;
            this.family = family;
            this.effect = effect;
            this.pace = pace;
            this.trait = trait;
            this.why = why;
            this.trades = trades.length == 0 ? EnumSet.noneOf(StationTask.class) : EnumSet.copyOf(Arrays.asList(trades));
        }

        /** By its key ("nest_egg"), its name ("Nest Egg") or its constant, or null. */
        @Nullable
        public static Knack byKey(String s) {
            if (s == null) return null;
            String k = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Knack n : values()) if (n.key.equals(k)) return n;
            return null;
        }

        /** The trades it works in, for the Skills page: "miner", "fisher / hunter". */
        String tradesWord() {
            if (trades.isEmpty()) return "";
            List<String> out = new ArrayList<>();
            for (StationTask t : trades) out.add(t.title.toLowerCase(Locale.ROOT));
            return String.join(" / ", out);
        }
    }

    /** The level of a trade at which its master's knack opens. */
    public static final int MASTER_LEVEL = 30;

    /** A knack it chose: which, on what day, and why. */
    public record Chosen(Knack knack, long day, String why) {}

    /**
     * What a folk has chosen, kept on the folk and saved with it (VillageFolkEntity "Knacks"): the
     * knacks, the day and the reason for each; the day it last chose one; and Nest Egg's coin the
     * treasury still owes it, with the sum it came to. Missing in an older save means none.
     */
    public static final class Book {
        final List<Chosen> chosen = new ArrayList<>();
        final EnumSet<Knack> has = EnumSet.noneOf(Knack.class);
        /** Nest Egg: what the treasury still owes it, what the grant came to in all, and the day it last paid a part. */
        int nestDue, nestSum;
        long nestPaidOn = -1;
        /** The day it last chose a knack (one a day at most). */
        long choseOn = -1;
        /** Whether it is working on its own just now (Quiet Focus), looked at once in a while, not saved. */
        int aloneTick = -100000;
        boolean alone;

        public List<Chosen> chosen() { return Collections.unmodifiableList(chosen); }

        public boolean has(Knack k) { return has.contains(k); }

        public int nestDue() { return nestDue; }

        public int nestSum() { return nestSum; }

        boolean isEmpty() { return chosen.isEmpty() && nestDue == 0 && nestSum == 0; }

        void add(Knack k, long day, String why) {
            if (has.add(k)) chosen.add(new Chosen(k, day, why));
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (Chosen c : chosen) {
                CompoundTag one = new CompoundTag();
                one.putString("Key", c.knack().key);
                one.putLong("Day", c.day());
                one.putString("Why", c.why());
                list.add(one);
            }
            tag.put("List", list);
            if (nestDue > 0) tag.putInt("NestDue", nestDue);
            if (nestSum > 0) tag.putInt("NestSum", nestSum);
            tag.putLong("NestPaidOn", nestPaidOn);
            tag.putLong("ChoseOn", choseOn);
            return tag;
        }

        public void load(CompoundTag tag) {
            chosen.clear();
            has.clear();
            ListTag list = tag.getList("List", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                Knack k = Knack.byKey(one.getString("Key"));
                if (k != null) add(k, one.getLong("Day"), one.getString("Why"));     // a knack gone from the game is let go
            }
            nestDue = Math.max(0, tag.getInt("NestDue"));
            nestSum = Math.max(0, tag.getInt("NestSum"));
            nestPaidOn = tag.contains("NestPaidOn") ? tag.getLong("NestPaidOn") : -1;
            choseOn = tag.contains("ChoseOn") ? tag.getLong("ChoseOn") : -1;
        }
    }

    // ------------------------------------------------------------------ points

    /** Its best trade: the one it has the most levels in (its own trade first on a tie), or NONE. */
    public static StationTask bestTrade(VillageFolkEntity f) {
        StationTask best = f.stationTask();
        int lv = f.tradeLevel(best);
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            int l = f.tradeLevel(t);
            if (l > lv) { lv = l; best = t; }
        }
        return lv > 0 ? best : f.stationTask();
    }

    /** Its level at its best trade. */
    public static int bestLevel(VillageFolkEntity f) {
        return f.tradeLevel(bestTrade(f));
    }

    /** Knack points it has earned: one at each five levels of its best trade, six at most; none for a child. */
    public static int earned(VillageFolkEntity f) {
        if (f.isBaby()) return 0;
        return Math.max(0, Math.min(MOST_POINTS, bestLevel(f) / LEVELS_A_POINT));
    }

    /** Points it has spent: a knack each. */
    public static int spent(VillageFolkEntity f) {
        return f.knacks().chosen.size();
    }

    /** Points it has to spend. */
    public static int free(VillageFolkEntity f) {
        return Math.max(0, earned(f) - spent(f));
    }

    /** The level of its best trade at which it earns its next point, or 0 when it has all six. */
    public static int nextPointAt(VillageFolkEntity f) {
        int e = earned(f);
        return f.isBaby() || e >= MOST_POINTS ? 0 : (e + 1) * LEVELS_A_POINT;
    }

    /** How far it is from its last point to its next one, in the hundred, by its experience at its best trade. */
    public static int progressPercent(VillageFolkEntity f) {
        int next = nextPointAt(f);
        if (next <= 0) return 100;
        StationTask t = bestTrade(f);
        int prev = next - LEVELS_A_POINT;
        long k = Math.max(1, com.jrpetty.mcassistant.AssistantConfig.levelCurveFactor());
        long from = (long) prev * prev * k, to = (long) next * next * k, xp = f.xpInTrade(t);
        if (to <= from) return 0;
        return (int) Math.max(0, Math.min(100, (xp - from) * 100 / (to - from)));
    }

    // ------------------------------------------------------------------ choosing

    /** A knack it might choose, how much it wants it, and why. */
    public record Pick(Knack knack, double score, String why) {}

    /** Can it choose this knack (it has not, and nothing rules it out)? */
    public static boolean open(VillageFolkEntity f, Knack k) {
        if (f.isBaby() || f.knacks().has(k)) return false;
        switch (k.family) {
            case TRADE -> {
                // The knacks of the trade it works, or of one it has worked.
                if (k.trades.contains(f.stationTask())) return true;
                for (StationTask t : k.trades) if (f.tradeLevel(t) > 0) return true;
                return false;
            }
            case NATURE -> {
                // Anybody whose nature is not the opposite of the knack's.
                Social.Trait opp = k.trait == null ? null : k.trait.opposite();
                return opp == null || !f.life().has(opp);
            }
            case MASTER -> {
                // [perks] A master of one of its trades: level thirty at it.
                for (StationTask t : k.trades) if (f.tradeLevel(t) >= MASTER_LEVEL) return true;
                return false;
            }
            default -> {
                // Nest Egg, once a household: not if its partner has had it.
                if (k == Knack.NEST_EGG) {
                    UUID partner = f.life().partner();
                    UUID village = f.ownerId();
                    if (partner != null && village != null) {
                        for (AssistantEntity a : Villages.folkOf(village)) {
                            if (a instanceof VillageFolkEntity p && p.getUUID().equals(partner) && p.knacks().has(Knack.NEST_EGG)) return false;
                        }
                    }
                }
                return true;
            }
        }
    }

    /** Every knack open to it, the one it wants most first. */
    public static List<Pick> ranked(VillageFolkEntity f) {
        List<Pick> out = new ArrayList<>();
        for (Knack k : Knack.values()) if (open(f, k)) out.add(score(f, k));
        out.sort((a, b) -> a.score() != b.score() ? Double.compare(b.score(), a.score()) : Integer.compare(a.knack().ordinal(), b.knack().ordinal()));
        return out;
    }

    /**
     * How much it wants a knack, and the reason it would give. Trade knacks: six for its own trade's,
     * and half a point more for every five levels it has in it (up to two); one and a bit for a trade
     * it worked once. A hard worker likes the pace knacks; a Guardian the watch's; a Provider the
     * fields'. Nature knacks: six for its own nature's, and a little for what it cares about (a Free
     * Spirit's ease, a Visionary's learning) and how it is (lonely, low). Purse knacks: by how much it
     * cares for wages and trade (up to five, and four more for a Merchant at heart); Nest Egg eight and
     * up to nine more for a household that rents its house and wants to buy it, much less otherwise.
     */
    public static Pick score(VillageFolkEntity f, Knack k) {
        int[] w = Values.of(f);
        Values.Value top = Values.top(f);
        Social.Life life = f.life();
        StationTask job = f.stationTask();
        int mood = f.persona().mood();
        double s;
        String why;
        switch (k.family) {
            case TRADE -> {
                if (k.trades.contains(job)) {
                    int lv = f.tradeLevel(job);
                    s = 6 + Math.min(4, lv / LEVELS_A_POINT) * 0.5;
                    why = "a " + job.title.toLowerCase(Locale.ROOT) + " of level " + lv + ": " + k.why;
                } else {
                    StationTask was = null;
                    for (StationTask t : k.trades) if (was == null || f.tradeLevel(t) > f.tradeLevel(was)) was = t;
                    int lv = was == null ? 0 : f.tradeLevel(was);
                    s = 1 + lv / 10.0;
                    why = "an old hand at " + (was == null ? "it" : was.label) + " (level " + lv + "): " + k.why;
                }
                if (k.pace > 0 && life.has(Social.Trait.HARDWORKING)) s += 1;
                if (k == Knack.FRIENDLY_FACE && (life.has(Social.Trait.SOCIABLE) || life.has(Social.Trait.CHEERFUL))) s += 1;
                if (k == Knack.FRIENDLY_FACE && top == Values.Value.WEALTH) s += 1;
                if ((k == Knack.DRILLED || k == Knack.SHARP_EYES) && top == Values.Value.SAFETY) s += 1.5;
                if ((k == Knack.GREEN_THUMB || k == Knack.CAREFUL_HARVEST || k == Knack.PATIENT || k == Knack.GENTLE_HAND)
                        && top == Values.Value.FOOD) s += 1;
                if ((k == Knack.STEADY_HANDS || k == Knack.KEEN_EYE || k == Knack.FIRE_TENDER || k == Knack.PRACTISED_HAND)
                        && top == Values.Value.PROGRESS) s += 1;
                // [perks] The Nether's knacks are worth little to a town that never goes there, unless the Nether is its trade.
                if ((k == Knack.PIGLIN_FRIEND || k == Knack.BLAZE_HUNTER) && !netherTrade(job)
                        && Villages.ageOf(f.ownerId()) != Villages.Age.NETHER) s -= 4;
                if (k == Knack.CIRCUIT_SENSE && top == Values.Value.PROGRESS) s += 1;
                if ((k == Knack.TRUE_SHOT || k == Knack.FEATHERLIGHT || k == Knack.IRON_WHISPERER) && top == Values.Value.SAFETY) s += 1.5;
                if ((k == Knack.SILVER_TONGUE || k == Knack.SHOWMAN) && (top == Values.Value.WEALTH || life.has(Social.Trait.SOCIABLE))) s += 1;
            }
            case MASTER -> {
                // [perks] A master wants its mastery above anything.
                StationTask was = null;
                for (StationTask t : k.trades) if (was == null || f.tradeLevel(t) > f.tradeLevel(was)) was = t;
                int lv = was == null ? 0 : f.tradeLevel(was);
                s = 9 + (k.trades.contains(job) ? 2 : 0) + lv / 20.0;
                why = "a master " + (was == null ? "of its trade" : was.title.toLowerCase(Locale.ROOT)) + " at level " + lv + ": " + k.why;
            }
            case NATURE -> {
                boolean mine = k.trait != null && life.has(k.trait);
                s = mine ? 6 : 0.5;
                why = mine ? k.trait.label + " by nature: " + k.why : k.why;
                int leisure = w[Values.Value.LEISURE.ordinal()];
                switch (k) {
                    case BRIGHT_SPIRIT, UNFLAPPABLE, GOOD_COMPANY -> s += leisure / 25.0;
                    case QUICK_STUDY -> s += w[Values.Value.PROGRESS.ordinal()] / 33.0;
                    case EARLY_RISER -> s += (w[Values.Value.PROGRESS.ordinal()] + w[Values.Value.WEALTH.ordinal()]) / 80.0;
                    default -> { }
                }
                if (k == Knack.GOOD_COMPANY && life.friends().isEmpty() && life.partner() == null) {
                    s += 2;
                    why = "lonely: it wants to make friends";
                }
                if ((k == Knack.UNFLAPPABLE || k == Knack.BRIGHT_SPIRIT || k == Knack.GRIM_RESOLVE) && mood < 45) {
                    s += 1.5;
                    why += "; it has been low lately";
                }
                if (k == Knack.QUIET_FOCUS && (job == StationTask.MINE || job == StationTask.FISH || job == StationTask.HUNT
                        || job == StationTask.BEEKEEP || job == StationTask.SMELT || job == StationTask.ENCHANT || job == StationTask.SCOUT)) {
                    s += 1;
                    why += ", and its work is a lonely one";
                }
            }
            default -> {
                double wealth = w[Values.Value.WEALTH.ordinal()] / 20.0;
                boolean merchant = top == Values.Value.WEALTH;
                switch (k) {
                    case THRIFTY -> {
                        s = wealth + (merchant ? 4 : 0) + (f.purse() < 12 ? 1 : 0);
                        why = merchant ? "a Merchant at heart: it minds its coin" : f.purse() < 12 ? "its purse is thin: it minds its coin" : k.why;
                    }
                    case HAGGLER -> {
                        s = wealth + (merchant ? 4 : 0) + (Wealth.wage(f) >= 4 ? 1 : 0) + (life.has(Social.Trait.SOCIABLE) ? 0.5 : 0);
                        why = merchant ? "a Merchant at heart: it wants a better wage" : k.why;
                    }
                    default -> {
                        // Nest Egg, by how it lives.
                        int homes = w[Values.Value.HOMES.ordinal()];
                        if (Homes.rentsAndWantsToOwn(f)) {
                            s = 8 + homes / 20.0 + (top == Values.Value.HOMES ? 4 : 0);
                            why = (top == Values.Value.HOMES ? "a Homemaker at heart, renting: " : "renting, and saving to buy: ") + k.why;
                        } else if (Homes.ownsItsHouse(f)) {
                            s = wealth * 0.5;
                            why = "a little put by: its house is its own already";
                        } else if (Homes.homeOf(f) == null) {
                            s = 2 + homes / 25.0;
                            why = "for a house of its own one day";
                        } else {
                            s = 0.5 + wealth * 0.5;
                            why = "a little put by";
                        }
                    }
                }
            }
        }
        // Its own small leaning, from its id: the same folk always leans the same way.
        long bits = f.getUUID().getLeastSignificantBits() ^ f.getUUID().getMostSignificantBits();
        s += ((bits >>> ((k.ordinal() % 16) * 4)) & 0xF) / 40.0;
        return new Pick(k, s, why);
    }

    /**
     * The day's look at its knacks, from its slow beat (VillageFolkEntity, every five seconds): its
     * guard's armour kept up, a part of a Nest Egg the treasury owes it paid when it can, and, with a
     * point to spend at a quiet moment (on its break, or off work and awake), a knack chosen, one a
     * day at most.
     */
    public static void tick(ServerLevel level, VillageFolkEntity f) {
        keepUp(f);
        Book b = f.knacks();
        long day = level.getDayTime() / 24000L;
        if (b.nestDue > 0 && b.nestPaidOn != day && level.getDayTime() % 24000L >= 6000L) payOwed(level, f, day);
        if (f.isBaby() || f.ownerId() == null || f.isShowcase() || !f.persona().rolled() || b.choseOn == day) return;
        if (free(f) <= 0) return;
        if (!f.offWorkNow() || f.isSleeping()) return;          // not at its work: a quiet moment
        choose(level, f, day);
    }

    /** Choose a knack now, with a point to spend: the one it wants most. Returns it, or null. (Tests, the command.) */
    @Nullable
    public static Knack chooseNow(ServerLevel level, VillageFolkEntity f) {
        return free(f) <= 0 ? null : choose(level, f, level.getDayTime() / 24000L);
    }

    @Nullable
    private static Knack choose(ServerLevel level, VillageFolkEntity f, long day) {
        List<Pick> all = ranked(f);
        if (all.isEmpty()) return null;
        Pick p = all.get(0);
        take(level, f, p.knack(), p.why(), day);
        return p.knack();
    }

    /** Give it a knack, as though it chose it (the operators' command): with its own reason if it would have one. */
    public static boolean grant(ServerLevel level, VillageFolkEntity f, Knack k) {
        if (f.knacks().has(k)) return false;
        take(level, f, k, score(f, k).why(), level.getDayTime() / 24000L);
        return true;
    }

    /** It takes the knack: on its books, in its memory, out loud, and whatever it does at once. */
    private static void take(ServerLevel level, VillageFolkEntity f, Knack k, String why, long day) {
        Book b = f.knacks();
        b.add(k, day, why);
        b.choseOn = day;
        keepUp(f);
        if (k == Knack.NEST_EGG) {
            nestEgg(level, f, day);
            return;
        }
        f.persona().remember(day, "I took up " + k.title + " on day " + day + ": " + why, 4);
        var r = f.getRandom();
        String line = switch (k.family) {
            case TRADE -> FolkTalk.pick(r, "I've got the knack of it now: " + k.title + ".",
                k.title + " — that's my knack now. You watch.", "Practice pays. " + k.title + ", that's me.");
            case NATURE -> FolkTalk.pick(r, "I've always been this way. " + k.title + ", they'll call me.",
                "Might as well be what I am: " + k.title + ".", k.title + ". It suits me.");
            case PURSE -> k == Knack.THRIFTY
                ? FolkTalk.pick(r, "A coin saved is a coin earned. Thrifty, that's me now.", "No more spending like there's no tomorrow.")
                : FolkTalk.pick(r, "I'll drive a harder bargain for my wage from now on.", "They'll not short me again. Haggler, that's me.");
            case MASTER -> FolkTalk.pick(r, "Thirty years of it, and now I'm its master: " + k.title + ".",
                "There's nobody in the town can teach me this trade now. " + k.title + ".", k.title + " — I've earned that, I think.");
        };
        FolkTalk.speak(f, line);
    }

    // ------------------------------------------------------------------ Nest Egg

    /**
     * Nest Egg: about three tenths of its house's price (a plain house's, if it has none), out of
     * the treasury: all at once if it can spare it (what it holds over what it is saving for and a
     * day's wages), else what it can now and the rest set aside, paid a part at a time on later
     * afternoons (payOwed). Into the household's savings toward its house if it rents and wants to
     * buy (Homes.grantTowardHouse), else into its purse. The chronicle and its memory have it, and it
     * says so.
     */
    private static void nestEgg(ServerLevel level, VillageFolkEntity f, long day) {
        Book b = f.knacks();
        UUID village = f.ownerId();
        int price = Homes.priceFor(f);
        int sum = Math.max(1, (int) Math.round(price * NEST_EGG_PERCENT / 100.0));
        int paid = village == null ? 0 : Ledger.takeCoins(village, Math.min(sum, spare(level, village)));
        if (paid > 0) Economy.spent(village, paid);
        b.nestSum = sum;
        b.nestDue = sum - paid;
        b.nestPaidOn = day;
        Homes.Grant g = paid > 0 ? Homes.grantTowardHouse(level, f, paid) : new Homes.Grant(0, 0, Homes.homeOf(f), price);
        boolean house = Homes.rentsAndWantsToOwn(f);
        String name = f.displayNameCap();
        if (village != null) {
            String where = house ? "toward the house its household rents and means to buy" : "into its purse";
            String how = paid >= sum ? "paid from the treasury"
                : paid > 0 ? paid + " paid from the treasury now, the other " + (sum - paid) + " set aside to pay as it can"
                : "set aside by the treasury, to be paid as it can";
            Villages.tell(village, day, name + " chose Nest Egg: " + sum + " coins " + where + " (" + NEST_EGG_PERCENT
                + "% of a " + price + "-coin house), " + how);
        }
        boolean many = household(f) > 1;
        f.persona().remember(day, "I chose Nest Egg on day " + day + ": the village " + (b.nestDue == 0 ? "put " : "is putting ") + sum
            + " coins " + (house ? "toward " + (many ? "our" : "my") + " house" : "in my purse"), 6);
        var r = f.getRandom();
        FolkTalk.speak(f, g.towardHouse() > 0 || house
            ? FolkTalk.pick(r, "That's a good start on a house of " + (many ? "our" : "my") + " own.",
                "A nest egg toward the house! That's a good start on a place of " + (many ? "our" : "my") + " own.")
            : FolkTalk.pick(r, "A nest egg! That'll see " + (many ? "us" : "me") + " right.", "Something put by, for a rainy day. Or a house, one day."));
    }

    /** What the treasury can spare: what it holds over what it is saving for and a day's wages. */
    static int spare(ServerLevel level, UUID village) {
        return Math.max(0, Ledger.coins(village) - Market.saved(village, level.getGameTime()) - Market.wageBill(village));
    }

    /** A part of a Nest Egg the treasury set aside: paid now, as far as it can spare it. */
    static void payOwed(ServerLevel level, VillageFolkEntity f, long day) {
        Book b = f.knacks();
        b.nestPaidOn = day;
        UUID village = f.ownerId();
        if (village == null || b.nestDue <= 0) return;
        int paid = Ledger.takeCoins(village, Math.min(b.nestDue, spare(level, village)));
        if (paid <= 0) return;
        Economy.spent(village, paid);
        b.nestDue -= paid;
        Homes.grantTowardHouse(level, f, paid);
        if (b.nestDue == 0) {
            Villages.tell(village, day, "the treasury paid the last of " + f.displayNameCap() + "'s nest egg: " + b.nestSum + " coins in all");
            f.persona().remember(day, "the village paid the last of my nest egg on day " + day, 3);
        }
    }

    /** Tests: pay what the treasury owes on a Nest Egg, now. */
    public static void payOwedForTests(ServerLevel level, VillageFolkEntity f) {
        payOwed(level, f, level.getDayTime() / 24000L);
    }

    private static int household(VillageFolkEntity f) {
        return f.life().partner() != null ? 2 : 1;
    }

    // ------------------------------------------------------------------ what the knacks do

    /** Has it this knack, and does it work just now (a trade's knack only in its trade)? */
    public static boolean active(VillageFolkEntity f, Knack k) {
        if (!f.knacks().has(k) || f.isBaby()) return false;
        return k.family != Family.TRADE && k.family != Family.MASTER || k.trades.contains(f.stationTask());
    }

    /** [perks] Is this the trade of going through to the Nether (the Nether runner, when the game has it)? */
    static boolean netherTrade(StationTask t) {
        return t == StationTask.NETHER;
    }

    /** [perks] Has any grown folk of the town this knack at work just now (looked up at most once in ten seconds)? */
    static boolean atWork(@Nullable UUID village, Knack k) {
        if (village == null) return false;
        String key = village + "|" + k.name();
        long now = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() == null ? 0L
            : net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
        Object[] seen = AT_WORK.get(key);
        if (seen != null && now - (Long) seen[0] < 200 && now >= (Long) seen[0]) return (Boolean) seen[1];
        boolean yes = false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && active(f, k) && !f.offWorkNow()) { yes = true; break; }
        }
        AT_WORK.put(key, new Object[]{ now, yes });
        return yes;
    }

    private static final java.util.Map<String, Object[]> AT_WORK = new java.util.concurrent.ConcurrentHashMap<>();

    /** [perks] Forget what was looked up (Perks.resetForTests). */
    static void resetLooks() {
        AT_WORK.clear();
    }

    /** [perks] Tinkerer: the railway's rails laid so much quicker, in percent, while one is at its work (CityTree.worksPercent). */
    static final int TINKER_RAILS = 25;

    /** [perks] Featherlight: so many more arrows in this guard's quiver (WatchKit.fit, through Perks.quiver). */
    static int featherlight(VillageFolkEntity f) {
        return active(f, Knack.FEATHERLIGHT) ? 8 : 0;
    }

    /** [perks] Surveyor's Eye: a scout's range, in percent more (Scouts, through Perks.scoutRange). */
    static int surveyorsEye(VillageFolkEntity f) {
        return active(f, Knack.SURVEYORS_EYE) ? 25 : 0;
    }

    /** [perks] Silver Tongue: the town's takings, in percent, while one is at work (Perks.takingsPercent). */
    static int silverTongue(@Nullable UUID village) {
        return atWork(village, Knack.SILVER_TONGUE) ? 105 : 100;
    }

    /**
     * Pace (AssistantEntity.skillWorkPercent, through VillageFolkEntity): five in the hundred for its
     * trade's pace knack while it works that trade; three for Quiet Focus with nobody about; four for
     * Grim Resolve in a black mood.
     */
    public static int workPercent(VillageFolkEntity f) {
        Book b = f.knacks();
        if (b == null || b.has.isEmpty() || f.isBaby()) return 0;     // (null only while the folk is being made)
        StationTask t = f.stationTask();
        if (t == StationTask.NONE) return 0;
        int p = 0;
        for (Knack k : b.has) if (k.pace > 0 && k.trades.contains(t)) p += k.pace;
        if (b.has.contains(Knack.QUIET_FOCUS) && workingAlone(f)) p += 3;
        if (b.has.contains(Knack.GRIM_RESOLVE) && f.persona().mood() < 45) p += 4;
        return p;
    }

    /** Nobody else grown within eight blocks: looked at once in five seconds at most. */
    static boolean workingAlone(VillageFolkEntity f) {
        if (f.level().isClientSide) return false;
        Book b = f.knacks();
        if (f.tickCount - b.aloneTick >= 100 || f.tickCount < b.aloneTick) {
            b.aloneTick = f.tickCount;
            b.alone = f.level().getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0),
                o -> o != f && o.isAlive() && !o.isBaby()).isEmpty();
        }
        return b.alone;
    }

    /**
     * Its mood (VillageFolkEntity.refreshMood): Bright Spirit +3 to its own; a friend near it with
     * Bright Spirit +1; and Unflappable holds it at 35 at the worst. Returns the mood, with the
     * reasons added for the talk.
     */
    public static int mood(VillageFolkEntity f, int m, List<Object[]> why) {
        Book b = f.knacks();
        if (f.isBaby()) return m;
        if (b.has.contains(Knack.BRIGHT_SPIRIT)) {
            m += 3;
            why.add(new Object[]{"brightspirit", 3});
        }
        if (!f.level().isClientSide) {
            for (VillageFolkEntity o : f.level().getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(8.0),
                    o -> o != f && o.isAlive() && o.knacks().has(Knack.BRIGHT_SPIRIT))) {
                if (f.life().affinity(o.getUUID()) >= Social.FRIEND) {
                    m += 1;
                    why.add(new Object[]{"brightfriend", 1});
                    break;
                }
            }
        }
        if (b.has.contains(Knack.UNFLAPPABLE) && m < 35) {
            why.add(new Object[]{"unflappable", 35 - m});
            m = 35;
        }
        return m;
    }

    /** Its break (VillageFolkEntity.breakNow): Early Riser's is 15% shorter. */
    public static double breakScale(VillageFolkEntity f) {
        return f.knacks().has(Knack.EARLY_RISER) ? 0.85 : 1.0;
    }

    /** A spell in company (VillageFolkEntity.socialBeat): Good Company warms half a point more on average (a quarter quicker). */
    public static int warmth(VillageFolkEntity f, int delta) {
        if (delta > 0 && f.knacks().has(Knack.GOOD_COMPANY) && f.getRandom().nextBoolean()) return delta + 1;
        return delta;
    }

    /** Experience at its trade (VillageFolkEntity.creditTrade): Quick Study a tenth more, the odd part by chance. */
    public static int extraXp(VillageFolkEntity f, int amount) {
        int bookworm = Quirks.extraXp(f, amount);                    // [perks] a Bookworm learns a tenth faster
        if (amount <= 0 || !f.knacks().has(Knack.QUICK_STUDY)) return bookworm;
        return bookworm + amount / 10 + (f.getRandom().nextInt(10) < amount % 10 ? 1 : 0);
    }

    /**
     * A Haggler's extra on its day's wage (Wealth.wage): five in the hundred, the fraction carried
     * from day to day so a small wage still gets its coin now and then (a wage of four, a coin every
     * fifth day). The same all day, however often the wage is asked for.
     */
    public static int haggled(VillageFolkEntity f, int wage) {
        if (wage <= 0 || !f.knacks().has(Knack.HAGGLER)) return 0;
        long day = Math.max(0, f.level().getDayTime() / 24000L);
        return (int) ((day + 1) * wage * 5 / 100 - day * wage * 5 / 100);
    }

    /** A Thrifty folk's price at the shop, the café or the market: a tenth off, the odd part by chance, never under a coin. */
    public static int thrifty(VillageFolkEntity f, int price) {
        if (price <= 1 || !f.knacks().has(Knack.THRIFTY)) return price;
        int off = price / 10 + (f.getRandom().nextInt(10) < price % 10 ? 1 : 0);
        return Math.max(1, price - off);
    }

    /**
     * Friendly Face: a buyer at a counter whose keeper (the cook at the café, the shopkeeper at the
     * shop) has the knack and is at work leaves a tip, a twentieth of the price on average (a coin,
     * by chance), out of its purse into the treasury, if it can spare it. Returns the tip.
     */
    public static int tip(UUID village, StationTask counter, VillageFolkEntity buyer, int price) {
        if (price <= 0 || buyer.purse() < 1) return 0;
        boolean friendly = false, host = false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity k) || k == buyer || k.stationTask() != counter || k.offWorkNow()) continue;
            if (active(k, Knack.MASTER_HOST)) { friendly = true; host = true; break; }      // [perks] a Master Host: a tip at every sale
            if (active(k, Knack.FRIENDLY_FACE)) friendly = true;
        }
        if (!friendly || !host && buyer.getRandom().nextInt(100) >= Math.min(100, price * 5)) return 0;
        if (!buyer.spend(1)) return 0;
        Ledger.addCoins(village, 1);
        Economy.spentInTown(village, 1);
        return 1;
    }

    /** Keen Eye: one ore in eight dug by a miner at its trade gives one more of what it drops. */
    public static void oreLuck(VillageFolkEntity f, BlockState ore, BlockPos pos) {
        if (!(f.level() instanceof ServerLevel level)) return;
        // [perks] Circuit Sense: a redstone ore gives four dust more, one time in two.
        if ((ore.is(net.minecraft.world.level.block.Blocks.REDSTONE_ORE) || ore.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_REDSTONE_ORE))
                && active(f, Knack.CIRCUIT_SENSE) && f.getRandom().nextBoolean()) {
            Block.popResource(level, pos, new ItemStack(net.minecraft.world.item.Items.REDSTONE, 4));
        }
        // Keen Eye one in eight; [perks] a Master Miner one in five, a Lucky folk one in twelve, the Observatory one in ten.
        int more = 0;
        if (active(f, Knack.KEEN_EYE) && f.getRandom().nextInt(8) == 0) more++;
        if (active(f, Knack.MASTER_MINER) && f.getRandom().nextInt(5) == 0) more++;
        if (Quirks.lucky(f)) more++;
        if (CityTree.observatoryLuck(f.ownerId(), f.getRandom())) more++;
        if (more == 0) return;
        List<ItemStack> drops = Block.getDrops(ore, level, pos, null, f, f.getMainHandItem());
        if (drops.isEmpty() || drops.get(0).isEmpty()) return;
        Block.popResource(level, pos, drops.get(0).copyWithCount(more));
    }

    /** Careful Harvest: one harvest in three gives a farmer at its trade a seed back, into its pack. [perks] A Master Grower every harvest. */
    public static void seedBack(VillageFolkEntity f, @Nullable Item seed) {
        if (seed == null) return;
        boolean master = active(f, Knack.MASTER_GROWER);
        if (!master && (!active(f, Knack.CAREFUL_HARVEST) || f.getRandom().nextInt(3) != 0)) return;
        ItemStack left = f.insertItem(new ItemStack(seed));
        if (!left.isEmpty() && f.level() instanceof ServerLevel level) Block.popResource(level, f.blockPosition(), left);
    }

    /** Fire Tender: of a load of fuel put in a furnace (two or more), one piece in two loads comes back to it. */
    public static int fuelSaved(VillageFolkEntity f, int loaded) {
        return loaded >= 2 && active(f, Knack.FIRE_TENDER) && f.getRandom().nextBoolean() ? 1 : 0;
    }

    /** Strong Back: so many more on each load of a courier's round (AssistantEntity's HAUL work). */
    public static int haulBonus(VillageFolkEntity f) {
        return (active(f, Knack.STRONG_BACK) ? 32 : 0) + (active(f, Knack.MASTER_PORTER) ? 64 : 0)   // [perks] a Master Porter
            + Quirks.haulBonus(f);                                                                    // [perks] Broad Shoulders
    }

    /** Sharp Eyes: so many blocks further a guard on the wall picks its mark from (Raids.targetFrom). */
    public static double sightBonus(VillageFolkEntity f) {
        return (active(f, Knack.SHARP_EYES) ? 4.0 : 0.0) + Perks.sight(f);    // [perks] Earthworks, Hawk-eyed, a Night Owl by night
    }

    private static final ResourceLocation DRILLED_ARMOUR = ResourceLocation.fromNamespaceAndPath("mc_assistant", "knack_drilled");
    private static final ResourceLocation VETERAN_ARMOUR = ResourceLocation.fromNamespaceAndPath("mc_assistant", "knack_veteran_armour");
    private static final ResourceLocation VETERAN_HIT = ResourceLocation.fromNamespaceAndPath("mc_assistant", "knack_veteran_hit");
    private static final ResourceLocation DEEP_LUNGS = ResourceLocation.fromNamespaceAndPath("mc_assistant", "knack_deep_lungs");

    /**
     * Drilled: a point of armour while it is a guard, and none when it is not. Kept up from its beat. [perks] And a
     * Veteran's two of armour and two of blow on the watch, Deep Lungs' breath, and an Iron Whisperer's golems mended.
     */
    static void keepUp(VillageFolkEntity f) {
        AttributeInstance armour = f.getAttribute(Attributes.ARMOR);
        if (armour == null) return;
        boolean want = active(f, Knack.DRILLED);
        if (want && !armour.hasModifier(DRILLED_ARMOUR)) {
            armour.addOrUpdateTransientModifier(new AttributeModifier(DRILLED_ARMOUR, 1.0, AttributeModifier.Operation.ADD_VALUE));
        } else if (!want && armour.hasModifier(DRILLED_ARMOUR)) {
            armour.removeModifier(DRILLED_ARMOUR);
        }
        boolean veteran = active(f, Knack.VETERAN);
        CityTree.modifier(f, Attributes.ARMOR, VETERAN_ARMOUR, veteran ? 2.0 : 0.0, AttributeModifier.Operation.ADD_VALUE);
        CityTree.modifier(f, Attributes.ATTACK_DAMAGE, VETERAN_HIT, veteran ? 2.0 : 0.0, AttributeModifier.Operation.ADD_VALUE);
        CityTree.modifier(f, Attributes.OXYGEN_BONUS, DEEP_LUNGS, active(f, Knack.DEEP_LUNGS) ? 2.0 : 0.0, AttributeModifier.Operation.ADD_VALUE);
        if (active(f, Knack.IRON_WHISPERER) && f.level() instanceof ServerLevel level) mendGolems(level, f);
    }

    /** Iron Whisperer: every iron golem within sixteen blocks of it mends a heart (every five seconds). Returns how many. */
    static int mendGolems(ServerLevel level, VillageFolkEntity f) {
        int n = 0;
        for (net.minecraft.world.entity.animal.IronGolem g : level.getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class,
                f.getBoundingBox().inflate(16.0), g -> g.isAlive() && g.getHealth() < g.getMaxHealth())) {
            g.heal(2.0F);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, g.getX(), g.getY() + 2.2, g.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
            n++;
        }
        return n;
    }

    /** Tests: an Iron Whisperer's mending, now. */
    public static int mendGolemsForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level && active(f, Knack.IRON_WHISPERER) ? mendGolems(level, f) : 0;
    }

    /** Tests: the knacks' marks put on it now (Drilled, Veteran, Deep Lungs). */
    public static void keepUpForTests(VillageFolkEntity f) {
        keepUp(f);
    }

    // ------------------------------------------------------------------ shown

    /** One line for its card (FolkTalk.card): "Steady Hands, Nest Egg · 1 to choose · next point at level 15". */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        List<String> names = new ArrayList<>();
        for (Chosen c : f.knacks().chosen) names.add(c.knack().title + (active(f, c.knack()) ? "" : " (resting)"));
        int free = free(f), next = nextPointAt(f);
        StringBuilder sb = new StringBuilder(names.isEmpty() ? "None yet" : String.join(", ", names));
        if (free > 0) sb.append(" · ").append(free).append(free == 1 ? " point" : " points").append(" to choose");
        if (next > 0) sb.append(" · next point at level ").append(next);
        return sb.toString();
    }

    /** In its own words ("Good at?"): "I've a knack or two of my own: Steady Hands and Keen Eye." */
    public static String talk(VillageFolkEntity f) {
        if (f.isBaby()) return "";
        List<String> names = new ArrayList<>();
        for (Chosen c : f.knacks().chosen) names.add(c.knack().title);
        int next = nextPointAt(f), free = free(f);
        String s;
        if (names.isEmpty()) s = next > 0 ? "No knacks of my own yet: the first comes at level " + next + "." : "";
        else {
            String list = names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
            s = (names.size() == 1 ? "I've a knack of my own: " : "I've a few knacks of my own: ") + list + ".";
        }
        if (free > 0) s += " I've a choice to make, too — I'll think on it.";
        return s.trim();
    }

    /** For the commands: "Steady Hands (day 4: a miner of level 5: ...); 1 point to choose; next at level 10". */
    public static String describe(VillageFolkEntity f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.displayNameCap()).append(" (").append(f.isBaby() ? "a child" : bestTrade(f).title.toLowerCase(Locale.ROOT)
            + " " + bestLevel(f)).append("): ").append(earned(f)).append(" earned, ").append(spent(f)).append(" chosen, ")
            .append(free(f)).append(" to choose");
        int next = nextPointAt(f);
        if (next > 0) sb.append("; next at level ").append(next).append(" (").append(progressPercent(f)).append("% of the way)");
        for (Chosen c : f.knacks().chosen) {
            sb.append(" | ").append(c.knack().title).append(" (").append(c.knack().effect).append("; day ").append(c.day())
                .append(": ").append(c.why()).append(active(f, c.knack()) ? "" : "; resting while it works another trade").append(')');
        }
        Book b = f.knacks();
        if (b.nestDue > 0) sb.append(" | nest egg: ").append(b.nestSum - b.nestDue).append(" of ").append(b.nestSum).append(" paid");
        return sb.toString();
    }

    /**
     * The Skills page of the talk screen, sent with every answer (FolkReplyPayload.skills): a line a
     * fact, its fields split by '|'.
     * <pre>
     *   P|earned|spent|free|next point at level (0: all six)|% of the way there|best trade|its level|child (1/0)
     *   T|trade|level|works it now (1/0)                     each trade it has worked, best first
     *   K|key|name|family|effect|why|day|works now (1/0)     each knack it chose, in order
     *   O|key|name|family|effect|suits it (0, 1, 2)          each knack still open to it, the best fit first
     *   N|nest egg paid|nest egg in all                      a Nest Egg the treasury is still paying
     * </pre>
     */
    public static String encode(VillageFolkEntity f) {
        StringBuilder sb = new StringBuilder();
        StationTask best = bestTrade(f);
        row(sb, "P", Integer.toString(earned(f)), Integer.toString(spent(f)), Integer.toString(free(f)),
            Integer.toString(nextPointAt(f)), Integer.toString(progressPercent(f)),
            best == StationTask.NONE ? "" : best.title, Integer.toString(bestLevel(f)), f.isBaby() ? "1" : "0");
        List<StationTask> worked = new ArrayList<>();
        for (StationTask t : StationTask.values()) if (t != StationTask.NONE && (f.tradeLevel(t) > 0 || t == f.stationTask())) worked.add(t);
        worked.sort((a, b) -> Integer.compare(f.tradeLevel(b), f.tradeLevel(a)));
        for (int i = 0; i < Math.min(5, worked.size()); i++) {
            StationTask t = worked.get(i);
            row(sb, "T", t.title, Integer.toString(f.tradeLevel(t)), t == f.stationTask() ? "1" : "0");
        }
        for (Chosen c : f.knacks().chosen) {
            Knack k = c.knack();
            row(sb, "K", k.key, k.title, k.family.name(), k.effect + (k.trades.isEmpty() ? "" : " (" + k.tradesWord() + ")"), c.why(),
                Long.toString(c.day()), active(f, k) ? "1" : "0");
        }
        if (!f.isBaby()) {
            for (Pick p : ranked(f)) {
                Knack k = p.knack();
                int fit = p.score() >= 6 ? 2 : p.score() >= 3 ? 1 : 0;
                row(sb, "O", k.key, k.title, k.family.name(), k.effect + (k.trades.isEmpty() ? "" : " (" + k.tradesWord() + ")"),
                    Integer.toString(fit));
            }
        }
        Book b = f.knacks();
        if (b.nestDue > 0) row(sb, "N", Integer.toString(b.nestSum - b.nestDue), Integer.toString(b.nestSum));
        return sb.toString();
    }

    private static void row(StringBuilder sb, String kind, String... fields) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(kind);
        for (String s : fields) sb.append('|').append(s == null ? "" : s.replace('|', '/').replace('\n', ' '));
    }
}
