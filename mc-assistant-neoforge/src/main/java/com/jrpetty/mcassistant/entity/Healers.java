package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.BandageItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [roles-a] The healer: a trade of its own (StationTask.HEAL), where the town's care was a works its brewer was called to.
 *
 * <ul>
 * <li><b>Who.</b> A Stone Age town with its infirmary standing keeps a healer, one to every forty folk (three at most),
 *     chosen by the town from the hands it can spare: a caring nature, a brewer's knowledge of potions, an older head.
 *     The post is one the town gives its own at interview (InterviewPosts, kind HEALER) when two or more could take it.
 *     A town that loses its infirmary keeps its healers till the trade's share says otherwise (Villages.overStaffed).</li>
 * <li><b>Its day.</b> By day it is at the infirmary, minding its beds and its shelf of remedies. When somebody is laid up
 *     (Health.patients) the town's round (Health.care, every five seconds) sends it to the bedside, at the infirmary or
 *     at home; there it gives what the stores have (Health.tend: honey for a cold, a bandage or the brewer's potion for a
 *     wound, company when the stores are bare). A healer down with a cold, asleep or away leaves the round to the town's
 *     care crew as before (TownJobs "care"), so nobody goes untended for want of it.</li>
 * <li><b>The births.</b> It is at every birth it can be (a midwife's part): the parents are the gladder of it for two
 *     days, and the chronicle says who saw the child into the world.</li>
 * <li><b>The player.</b> "I'm hurt, could you see to me?": a remedy out of the stores at the market's price and a small
 *     fee (a bandage you hold is used instead, for the fee alone), or a rest and a kind word for a coin when the stores
 *     have nothing. A player may also take a paid shift as its assistant (ShiftRole.HEALER).</li>
 * </ul>
 * Cost: the town's side runs once a second a town (the post looked at once a minute); a healer's own day once a second.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Healers {

    private Healers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The healer's post in the interviews' books (InterviewPosts). */
    static final String POST = "healer";
    /** One healer to so many folk, and never more than so many. */
    static final int PER_FOLK = 40, MOST = 3;
    /** Close enough to a patient to see to it (blocks). */
    static final double BEDSIDE = 2.6;
    /** The fee for seeing to a player, on top of what the remedy costs; a rest and a kind word alone. */
    public static final int FEE = 2, REST_FEE = 1;
    /** The mood a safe birth brings its parents (Families: VillageFolkEntity.raise), and for how many days. */
    static final int BIRTH_MOOD = 4;
    static final long BIRTH_DAYS = 2L;
    static final String BIRTH_MEMORY = "the healer saw our child safely into the world";

    /** Each healer on its round: the patient it is going to. */
    private static final Map<UUID, UUID> ROUND = new ConcurrentHashMap<>();
    /** What each healer is about, in words (FolkTalk "what are you up to?"). */
    private static final Map<UUID, String> DOING = new ConcurrentHashMap<>();
    /** When each healer's day last ran, and when each town's post was last looked at. */
    private static final Map<UUID, Long> LAST_WORK = new ConcurrentHashMap<>(), LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ROUND.clear();
        DOING.clear();
        LAST_WORK.clear();
        LOOKED.clear();
    }

    // ------------------------------------------------------------------ the trade: when, how many, who

    /** Does the town want a healer at all (Villages.craftReady): the Stone Age or later, with its infirmary standing. */
    public static boolean ready(@Nullable UUID id) {
        return id != null && Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal() && Infirmary.of(id) != null;
    }

    /** How many healers the town wants: none before it is ready, one, and one more to every forty folk, three at most. */
    public static int wanted(@Nullable UUID id) {
        return id == null || !ready(id) ? 0 : wantedAt(Villages.headcount(id));
    }

    /** How many healers a ready town of this many wants. */
    public static int wantedAt(int folk) {
        return Math.min(MOST, 1 + Math.max(0, folk - 1) / PER_FOLK);
    }

    public static List<VillageFolkEntity> healers(UUID id) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() == StationTask.HEAL) out.add(f);
        }
        return out;
    }

    /** How well a folk would nurse the town: its hand at it, a brewer's potions, a kind nature, an older head. */
    public static int fitness(VillageFolkEntity f) {
        int s = f.tradeLevel(StationTask.HEAL) * 1000 + f.tradeLevel(StationTask.BREW) * 100 + f.tradeLevel(StationTask.COOK) * 30
            + Math.min(30, f.ageYears() / 2);
        if (f.life().has(Social.Trait.CHEERFUL)) s += 40;             // a bright face at the bedside
        if (f.life().has(Social.Trait.EASYGOING)) s += 30;            // patient with the patients
        if (f.life().has(Social.Trait.SOCIABLE)) s += 20;
        if (f.life().has(Social.Trait.GRUMPY)) s -= 40;
        return s;
    }

    /** May a hand be spared from this trade for the infirmary? Never the watch's one hand, a craft, the storehouse, the
     *  bank, the scouts, the couriers, the cave team, the ferry or the town's other picked trades. */
    static boolean spare(UUID id, StationTask t) {
        if (t == StationTask.NONE) return true;
        if (t == StationTask.HEAL || t.isCraft() || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.SCOUT
            || t == StationTask.HAUL || t == StationTask.CAVE || t == StationTask.FERRY || t == StationTask.NETHER
            || t == StationTask.REDSTONE || t == StationTask.EMERALD || t == StationTask.DIVER) return false;
        return Villages.share(id, t) >= 0.5;
    }

    /** May this folk stand for the infirmary: grown, at home, not the elder, from a trade the town can spare it from? */
    static boolean eligible(VillageFolkEntity f, UUID id) {
        if (f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isElder()) return false;
        if (f.trip() != null || f.expedition() != null || f.ageYears() < 20) return false;
        return spare(id, f.stationTask());
    }

    /** The hands who could take up the infirmary, the fittest first (the interview panel's choice above them all). */
    static List<VillageFolkEntity> candidates(UUID id) {
        List<VillageFolkEntity> out = new ArrayList<>();
        Map<VillageFolkEntity, Integer> score = new HashMap<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !eligible(f, id)) continue;
            score.put(f, fitness(f) + Interviews.preferred(id, POST, f));
            out.add(f);
        }
        out.sort((x, y) -> Integer.compare(score.get(y), score.get(x)));
        return out;
    }

    /** The town's look at the post (once a minute): filled now, or held open a day for its interview. */
    static void fillThePost(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (healers(id).size() >= wanted(id) || Interviews.pending(id, POST) != null) return;
        List<VillageFolkEntity> few = candidates(id);
        if (!few.isEmpty() && !Interviews.vacancy(level, id, POST, few.get(0))) appoint(level, v);
    }

    /** One more healer, if the town wants one: the fittest hand it can spare (the interview's choice first). */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Ledger.Building ward = Infirmary.of(id);
        if (ward == null || healers(id).size() >= wanted(id)) return null;
        List<VillageFolkEntity> all = candidates(id);
        if (all.isEmpty()) return null;
        VillageFolkEntity best = all.get(0);
        StationTask was = best.stationTask();
        // A head start: a brewer knows its potions, a cook its broths; a few levels short of a healer's.
        int knows = Math.max(best.tradeLevel(StationTask.BREW), best.tradeLevel(StationTask.COOK) / 2);
        int has = AssistantEntity.xpForLevel(best.tradeLevel(StationTask.HEAL)), start = AssistantEntity.xpForLevel(Math.max(0, knows - 3));
        if (start > has) best.schoolXp(StationTask.HEAL, start - has);
        best.setStation(ward.anchor(), StationTask.HEAL);
        best.assignPlot(WorkZone.around(ward.anchor(), 5, WorkZone.DEFAULT_DEPTH), "The Infirmary");
        long day = level.getDayTime() / 24000L;
        Villages.tell(id, day, best.displayNameCap() + " (" + (was == StationTask.NONE ? "with no trade" : JobMarket.a(JobMarket.noun(was))
            + " of level " + best.tradeLevel(was)) + ") became the town's healer, at the infirmary");
        best.persona().remember(day, "I became the town's healer", 5);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "The town's healer! Bring me your coughs and your cuts.",
            "I'll keep the infirmary. Honey, bandages, and a kind word: that's most of it.",
            "Nobody lies sick in this town without somebody sitting with them. Not while I'm here."));
        LOG.info("[MCA-HEALERS] {} of {} became a healer (was {} level {}, fitness {})", best.displayNameCap(), Villages.name(id), was,
            best.tradeLevel(was), fitness(best));
        return best;
    }

    // ------------------------------------------------------------------ the round (Health.care)

    /** Free to go to a bedside: up, well, about the town, not called elsewhere. */
    static boolean free(VillageFolkEntity h) {
        return h.isAlive() && !h.isBaby() && !h.isSleeping() && h.health().cold <= 0 && !Health.laidUp(h) && TownJobs.fit(h, "care");
    }

    /**
     * [Health.care] The healer for this patient: the one already going to it, else the nearest free one not on another
     * round (and it is sent). Null when the town has no healer free: its care crew sees to it instead.
     */
    @Nullable
    static VillageFolkEntity forPatient(Villages.Village v, VillageFolkEntity patient) {
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (VillageFolkEntity h : healers(v.id())) {
            if (h == patient || !free(h)) continue;
            UUID going = ROUND.get(h.getUUID());
            if (patient.getUUID().equals(going)) return h;
            if (going != null && stillLaidUp(v.id(), going)) continue;   // on another round
            double d = h.distanceToSqr(patient);
            if (d < bd) { bd = d; best = h; }
        }
        if (best != null) {
            ROUND.put(best.getUUID(), patient.getUUID());
            DOING.put(best.getUUID(), "on my way to " + patient.displayNameCap() + (patient.health().infirmary ? " at the infirmary" : ", laid up at home"));
            best.brain("sent to " + patient.displayNameCap() + "'s bedside");
        }
        return best;
    }

    private static boolean stillLaidUp(UUID village, UUID patient) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.getUUID().equals(patient)) return a instanceof VillageFolkEntity p && p.isAlive() && p.health().lying && Health.laidUp(p);
        }
        return false;
    }

    /** Is the healer at the patient's bedside? */
    static boolean atBedside(VillageFolkEntity h, VillageFolkEntity patient) {
        return h.distanceToSqr(patient) <= BEDSIDE * BEDSIDE && Math.abs(h.getY() - patient.getY()) < 2.5;
    }

    /** [Health.tend] Seen to: its round over, back to the ward. */
    static void seen(VillageFolkEntity h, VillageFolkEntity patient) {
        ROUND.remove(h.getUUID());
        DOING.put(h.getUUID(), "just seen to " + patient.displayNameCap());
        h.note(AssistantEntity.Deed.FOLK_HEALED, 1);                // its experience at the trade, by the deed
    }

    /** Who the healer is going to just now, if anybody (tests, the card). */
    @Nullable
    public static UUID goingTo(VillageFolkEntity h) {
        return ROUND.get(h.getUUID());
    }

    // ------------------------------------------------------------------ its own day

    /**
     * The healer's day (AssistantEntity's work, once a second): on its round, to the bedside; else at the infirmary,
     * minding the beds and the shelf. False outside the working day, so it sleeps and eats as everybody does.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || f.isSleeping() || f.isBaby()) return false;
        long t = level.getDayTime() % 24000L;
        if (t < 900L || t > 12300L) return false;
        long now = level.getGameTime();
        Long last = LAST_WORK.get(f.getUUID());
        if (last != null && now >= last && now - last < 20L) return true;
        LAST_WORK.put(f.getUUID(), now);
        UUID going = ROUND.get(f.getUUID());
        VillageFolkEntity patient = going == null ? null : level.getEntity(going) instanceof VillageFolkEntity p && p.isAlive() ? p : null;
        if (going != null && (patient == null || !patient.health().lying || !Health.laidUp(patient))) {
            ROUND.remove(f.getUUID());                                // up and about, or gone: the round is over
            patient = null;
        }
        if (patient != null) {
            if (!atBedside(f, patient)) {
                if (f.getNavigation().isDone()) f.walkTo(patient.blockPosition(), 1.0D);
            } else {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(patient, 30.0F, 30.0F);
            }
            return true;
        }
        Ledger.Building ward = Infirmary.of(id);
        BlockPos post = ward != null ? ward.anchor() : f.stationPos();
        if (post == null) return false;
        if (f.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > 4.5 * 4.5) {
            if (f.getNavigation().isDone()) f.walkTo(post, 0.9D);
            DOING.put(f.getUUID(), "on my way back to the infirmary");
        } else {
            f.getNavigation().stop();
            DOING.put(f.getUUID(), ward == null ? "waiting for the town to build its infirmary" : f.getRandom().nextInt(3) == 0
                ? "sorting the remedies on the infirmary's shelf" : "minding the infirmary's beds");
        }
        return true;
    }

    /** "What are you up to?" in its own words. */
    static String doing(VillageFolkEntity f, RandomSource r) {
        String d = DOING.get(f.getUUID());
        if (d == null) return FolkTalk.pick(r, "Minding the infirmary. Quiet today, thank goodness.", "Seeing who's poorly. Nobody, so far.");
        return Character.toUpperCase(d.charAt(0)) + d.substring(1) + "." + (d.startsWith("on my way to") ? " Excuse me." : "");
    }

    /** The trade's book (TradeBooks): the ward, today's care, the births. */
    static List<String> bookNotes(ServerLevel level, UUID id) {
        List<String> out = new ArrayList<>();
        Ledger.Building ward = Infirmary.of(id);
        if (ward != null) out.add("The infirmary has " + Infirmary.beds(level, ward).size() + " beds. Keep them for the sick and the hurt.");
        out.add("Honey for a cold, a bandage or a potion for a wound; and when the stores have nothing, sit with them anyway.");
        int seen = 0;
        for (VillageFolkEntity h : healers(id)) seen += h.deedCount(AssistantEntity.Deed.FOLK_HEALED);
        if (seen > 0) out.add("Between us we've seen to " + seen + (seen == 1 ? " patient." : " patients."));
        return out;
    }

    // ------------------------------------------------------------------ the births

    /**
     * [VillageFolkEntity.raise] A child born: the town's healer at the birth if it has one about (a midwife's part). The
     * parents remember it and are the gladder of it for two days (mood), the healer gains by it, and the chronicle says so.
     */
    static void attendBirth(ServerLevel level, UUID village, VillageFolkEntity mother, VillageFolkEntity father, List<VillageFolkEntity> brood, long day) {
        VillageFolkEntity h = null;
        double bd = Double.MAX_VALUE;
        for (VillageFolkEntity c : healers(village)) {
            if (c == mother || c == father || !c.isAlive() || c.trip() != null || c.expedition() != null) continue;
            double d = c.distanceToSqr(mother);
            if (d < bd) { bd = d; h = c; }
        }
        if (h == null) return;
        String kids = brood.size() == 1 ? brood.get(0).displayNameCap() : brood.size() == 2 ? "the twins" : "all " + brood.size();
        Villages.tell(village, day, h.displayNameCap() + " the healer saw " + kids + " safely into the world");
        for (VillageFolkEntity p : List.of(mother, father)) {
            p.persona().remember(day, BIRTH_MEMORY, 4);
            p.life().feel(h.getUUID(), h.displayNameCap(), 3);
        }
        h.persona().remember(day, "I saw " + kids + " safely into the world", 3);
        h.schoolXp(StationTask.HEAL, 60);
        DOING.put(h.getUUID(), "just back from a birth: " + kids);
        level.sendParticles(ParticleTypes.HEART, mother.getX(), mother.getY() + 1.6, mother.getZ(), 4, 0.4, 0.3, 0.4, 0.0);
        LOG.info("[MCA-HEALERS] {} attended the birth of {} in {}", h.displayNameCap(), kids, Villages.name(village));
    }

    /** [VillageFolkEntity.refreshMood] A safe birth, the healer at it: the parents the gladder for two days. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        for (Persona.Memory mem : f.persona().memories()) {
            if (mem.text().equals(BIRTH_MEMORY) && day - mem.day() < BIRTH_DAYS && day >= mem.day()) {
                why.add(new Object[]{ "a safe birth", BIRTH_MOOD });
                return m + BIRTH_MOOD;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ the player

    /** What the healer has for a player, by need: the wound's first, then the cold's. */
    private record Cure(String word, Predicate<ItemStack> what, float heals, boolean bottle) {}

    private static final List<Cure> CURES = List.of(
        new Cure("a bandage", Kitchen.BANDAGE, 2.0F, false),
        new Cure("a healing potion", Links::healing, 0.0F, true),
        new Cure("a golden carrot", s -> s.is(Items.GOLDEN_CARROT), 6.0F, false),
        new Cure("a honey bottle", s -> s.is(Items.HONEY_BOTTLE), 3.0F, true),
        new Cure("sweet berries", s -> s.is(Items.SWEET_BERRIES), 2.0F, false));

    /**
     * "I'm hurt, could you see to me?" — the healer (else it points the player to one): a bandage the player holds bound
     * on for the fee; else the best remedy the stores have, at the market's price and the fee; else a rest and a kind
     * word for a coin. Nothing made from nothing: the remedy leaves the stores (its bottle goes back), the coin goes to
     * the town's purse.
     */
    public static String seeTo(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "I'm no healer, I'm afraid.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "I'm no healer, I'm afraid.";
        if (f.stationTask() != StationTask.HEAL) {
            List<VillageFolkEntity> hs = healers(village);
            if (!hs.isEmpty()) return "You want " + hs.get(0).displayNameCap() + ", our healer — at the infirmary, most likely.";
            return Infirmary.of(village) == null ? "We've no healer, nor an infirmary yet. Rest, and eat something warm."
                : "We've no healer just now. The infirmary's beds are for the town's own, but rest a while there if you must.";
        }
        boolean poisoned = p.hasEffect(MobEffects.POISON);
        if (p.getHealth() >= p.getMaxHealth() - 0.5F && !poisoned) return "You look well enough to me. Come back if you're hurt.";
        long day = level.getDayTime() / 24000L;
        RandomSource r = f.getRandom();
        ItemStack held = p.getMainHandItem();
        Cure use = null;
        boolean own = Kitchen.BANDAGE.test(held);
        double each = 0;
        if (!own) {
            for (Cure c : CURES) {
                if (c.what() == Kitchen.BANDAGE || Market.stock(level, village, c.what()) <= 0) continue;
                use = c;
                break;
            }
            if (use == null && Market.stock(level, village, Kitchen.BANDAGE) > 0) use = CURES.get(0);
            if (use != null) {
                ItemStack sample = sampleOf(level, village, use.what());
                each = sample.isEmpty() ? 0 : PlayerServices.marketPrice(level, village, sample);
            }
        }
        int base = own || use != null ? FEE + (int) Math.ceil(each - 1.0e-9) : REST_FEE;
        int price = Dealings.haggled(village, p.getUUID(), day, base);
        if (Market.coinsHeld(p) < price) {
            return (use == null && !own ? "A rest and a warm word cost " : "Seeing to you would be ") + Dealings.coins(price)
                + (use != null ? " (" + use.word() + " from the stores, and my time)" : "") + ". You've " + Market.coinsHeld(p) + ".";
        }
        String what;
        if (own) {
            held.shrink(1);
            BandageItem.bind(p);
            p.heal(2.0F);
            what = "your own bandage, bound on fresh";
        } else if (use != null) {
            List<ItemStack> got = Services.take(level, village, use.what(), 1);
            if (got.isEmpty()) return "Somebody's just taken the last of it from the stores. Come back in a bit.";
            ItemStack one = got.get(0);
            Economy.storesOut(village, one, 1);
            Budget.forget(village);
            cure(p, use, one);
            if (use.bottle()) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));     // the bottle back to the stores
            what = use.word() + " from the stores";
        } else {
            p.heal(2.0F);
            what = "a rest and a kind word";
        }
        if (poisoned && (own || use != null)) p.removeEffect(MobEffects.POISON);
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, f.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.5F, 1.4F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.0, p.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
        f.persona().remember(day, "I saw to " + p.getName().getString() + " when it came in hurt", 1);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
        f.schoolXp(StationTask.HEAL, 20);
        LOG.info("[MCA-HEALERS] {} saw to the player {} with {} for {}", f.displayNameCap(), p.getName().getString(), what, price);
        return FolkTalk.pick(r, "Hold still. There — " + what + ". " + Dealings.coins(price) + ", and mind how you go.",
            "That's " + what + " seen to. " + Dealings.coins(price) + ", please; and rest it a while.");
    }

    private static ItemStack sampleOf(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        for (BlockPos pos : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) return c.getItem(i).copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** A remedy's good on a player: the bandage bound, the potion drunk, the food's healing. */
    private static void cure(Player p, Cure c, ItemStack one) {
        if (Kitchen.BANDAGE.test(one)) {
            BandageItem.bind(p);
            p.heal(c.heals());
            return;
        }
        PotionContents pc = one.get(DataComponents.POTION_CONTENTS);
        if (pc != null) {
            if (pc.is(Potions.HEALING)) p.heal(4.0F);
            else if (pc.is(Potions.STRONG_HEALING)) p.heal(8.0F);
            for (MobEffectInstance e : pc.getAllEffects()) if (!e.getEffect().value().isInstantenous()) p.addEffect(new MobEffectInstance(e));
            return;
        }
        p.heal(c.heals());
    }

    // ------------------------------------------------------------------ the town's tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 7) return;
        com.jrpetty.mcassistant.Guard.run("healers", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** The town's side, every second: the post looked at once a minute. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long looked = LOOKED.get(id);
        if (looked != null && now - looked < 1200L && now >= looked) return;
        LOOKED.put(id, now);
        if (ready(id)) fillThePost(level, v);
    }

    /** Tests: the town's look at the post, now. */
    public static void fillForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v);
    }
}
