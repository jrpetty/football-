package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.NetherItems;
import com.jrpetty.mcassistant.item.RunnersSatchelItem;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [nether] The Nether runners: the town's small, picked, well-paid team that goes through the gateway into the Nether and
 * comes back with what the brewer, the enchanter and the builders cannot get any other way.
 *
 * <p><b>The trade.</b> Opened in the Nether Age once the gateway is lit (Nether.opened): one runner, two at sixty folk,
 * three at a hundred. Nobody asks for the job: the town picks its runners (appoint) from its veterans, the watch's and
 * the cave team's best first, its most skilled miners after; never a new hand, never one out of a trade the town is short
 * of. The watch's health (AssistantEntity: a guard's), and among the best paid in the town (JobWorth). The most
 * experienced of them leads.
 *
 * <p><b>The kit</b> (kitUp), out of the stores, made by the town's makers: the watch's armour and blade, a bow and its
 * arrows, a shield (a ghast's fireball is turned back off it), and at least one piece of gold worn, so the piglins leave
 * them be: the Gold Charm the smith makes, or a gold piece from the stores where it costs the least armour (goldPiece);
 * potions of fire resistance from the brewer; food; cobblestone to wall and bridge with; the best pick; flint and steel
 * for the leader; a door and lights for the outpost on a first run; the gold to barter with; the Runner's Satchel the
 * tailor stitches (fire-proof, nine stacks); a crafting table's makings. Its look is its own (the scorched coat, the
 * gold-trimmed helm, the satchel: FolkModel).
 *
 * <p><b>Its day at home</b> (work): of a morning, when the plan says the town wants something out of the Nether and the
 * runners have rested a day since the last run, the team gathers at the gateway and goes through (NetherRuns.setOut).
 * Else at its post by the gateway: resting, going over the runners' chart.
 */
public final class NetherRunners {

    static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private NetherRunners() {}

    /** A town takes up the trade from this many folk, in the Nether Age, its gateway lit. */
    public static final int FROM = 20;
    /** And never keeps more than this many, however big the town. */
    public static final int MOST = 3;
    /** A veteran: this level at the watch or in the caves; a miner, this level at the rock. */
    static final int VETERAN = 5, MINER_VETERAN = 8;
    /** The days the runners rest between runs (a run that found nothing the town wanted: the same). */
    static final long REST = 1;
    /** The morning hours the team sets out in. */
    static final long MORNING_FROM = 1000, MORNING_TO = 4500;

    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>(), WAITED = new ConcurrentHashMap<>();
    private static volatile boolean demanded;

    public static void resetForTests() {
        TICKED.clear();
        WAITED.clear();
    }

    // ------------------------------------------------------------------ the team: who, and how many

    /** The town's runners: grown, alive, and no showcase. */
    public static List<VillageFolkEntity> runners(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.NETHER && !f.isBaby() && f.isAlive() && !f.isShowcase()) out.add(f);
        }
        return out;
    }

    /** Is this folk a veteran the runners would take: at the watch or in the caves a while, or a miner of long years? */
    static boolean veteran(VillageFolkEntity f) {
        return f.tradeLevel(StationTask.NETHER) > 0 || f.tradeLevel(StationTask.GUARD) >= VETERAN || f.tradeLevel(StationTask.CAVE) >= VETERAN
            || f.tradeLevel(StationTask.MINE) >= MINER_VETERAN;
    }

    /** Has the town what it takes to send anybody through: the gateway built and lit, and a veteran to send (or a team)? */
    public static boolean ready(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village) != Villages.Age.NETHER || !Villages.hasBuilt(village, "gateway") || !Nether.opened(village)) return false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() == StationTask.NETHER || veteran(f)) return true;
        }
        return false;
    }

    /** The team a town of this many keeps: one, two at sixty, three at a hundred. */
    public static int team(int folk) {
        return folk < FROM ? 0 : Math.min(MOST, folk >= 100 ? 3 : folk >= 60 ? 2 : 1);
    }

    /** How many runners the town wants: none before its gateway is lit in the Nether Age. */
    public static int wanted(@Nullable UUID village) {
        if (village == null || Villages.ageOf(village) != Villages.Age.NETHER) return 0;
        int n = Villages.headcount(village);
        if (n < FROM || !ready(village)) return 0;
        return team(n);
    }

    /** What a folk knows that the Nether wants: the runs, the watch, the caves; the rock a little less. */
    public static int skill(VillageFolkEntity f) {
        return Math.max(f.tradeLevel(StationTask.NETHER), Math.max(Math.max(f.tradeLevel(StationTask.GUARD), f.tradeLevel(StationTask.CAVE)),
            f.tradeLevel(StationTask.MINE) - 3));
    }

    /** How the town ranks a veteran for the runners: its skill, its years at the blade and in the dark, its nerve. */
    static int fitness(VillageFolkEntity f) {
        int s = skill(f) * 1000 + (f.tradeLevel(StationTask.GUARD) + f.tradeLevel(StationTask.CAVE)) * 30 + f.tradeLevel(StationTask.MINE) * 10
            + Math.min(19, f.lifetimeXp() / 1000);
        if (f.life().has(Social.Trait.CURIOUS)) s += 10;
        if (f.life().has(Social.Trait.HARDWORKING)) s += 5;
        return s;
    }

    /** The leader: the most experienced at the runs (its years at it on a tie). */
    static boolean better(VillageFolkEntity a, VillageFolkEntity b) {
        int la = a.tradeLevel(StationTask.NETHER), lb = b.tradeLevel(StationTask.NETHER);
        return la != lb ? la > lb : a.lifetimeXp() > b.lifetimeXp();
    }

    /** The town's lead runner (at home or through the gateway), or null with no team. */
    @Nullable
    public static VillageFolkEntity leaderOf(UUID village) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : runners(village)) if (best == null || better(f, best)) best = f;
        return best;
    }

    /** May a hand be spared from this trade for the runs? The watch, the caves and the mines only, and never a trade the
     *  town is short of. */
    static boolean spare(UUID village, StationTask t) {
        if (t != StationTask.GUARD && t != StationTask.CAVE && t != StationTask.MINE) return false;
        return Villages.share(village, t) >= 0.5;
    }

    /** The town's runners: those in the world, and those away through the gateway on a run kept with the town. */
    static int have(UUID village) {
        List<VillageFolkEntity> here = runners(village);
        return here.size() + NetherRuns.awayUnseen(village, here);
    }

    /**
     * One more runner, if the town wants one: the fittest veteran it can spare (never a new hand), given a head start at
     * the runs for what it knows of the blade and the dark. Returns who, or null.
     */
    @Nullable
    public static VillageFolkEntity appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (have(id) >= wanted(id)) return null;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.isElder()) continue;
            if (f.trip() != null || f.expedition() != null || NetherRuns.away(f)) continue;
            if (!veteran(f) || !spare(id, f.stationTask())) continue;
            int age = f.ageYears();
            if (age < 20 || age > 50) continue;
            int score = fitness(f);
            if (score > bestScore) { bestScore = score; best = f; }
        }
        if (best == null) return null;
        StationTask was = best.stationTask();
        int knows = Math.max(Math.max(best.tradeLevel(StationTask.GUARD), best.tradeLevel(StationTask.CAVE)), best.tradeLevel(StationTask.MINE) - 3);
        int has = AssistantEntity.xpForLevel(best.tradeLevel(StationTask.NETHER)), start = AssistantEntity.xpForLevel(Math.max(0, knows - 2));
        if (start > has) best.schoolXp(StationTask.NETHER, start - has);
        BlockPos post = post(level, v);
        best.setStation(post, StationTask.NETHER);
        best.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Gateway");
        int n = runners(id).size();
        Villages.tell(id, day, best.displayNameCap() + " (" + JobMarket.a(JobMarket.noun(was)) + " of level " + best.tradeLevel(was)
            + ") was picked for the Nether runners, " + n + " of " + wanted(id));
        best.persona().remember(day, "I was picked for the town's Nether runners", 6);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "The Nether runners! Through the gateway and back — and well paid for it.",
            "Picked for the runs. Gold on, eyes open, and never strike a piglin.", "The town wants its best through that gateway. I'll bring it home what it needs."));
        LOG.info("[MCA-NETHER] {} of {} picked for the Nether runners (was {} level {}, skill {}), {} of {}", best.displayNameCap(), Villages.name(id),
            was, best.tradeLevel(was), skill(best), n, wanted(id));
        return best;
    }

    /** Where a runner stands of a day at home: before the gateway, on the town's side. */
    static BlockPos post(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos portal = NetherRuns.gatePortal(level, id);
        if (portal != null) {
            BlockPos at = NetherRuns.beforeTheGate(level, v, portal);
            if (at != null) return at;
        }
        BlockPos gate = Villages.builtAt(id, "gateway");
        if (gate == null) return v.centre().relative(Direction.EAST, 4);
        Direction to = Math.abs(v.centre().getX() - gate.getX()) >= Math.abs(v.centre().getZ() - gate.getZ())
            ? (v.centre().getX() > gate.getX() ? Direction.EAST : Direction.WEST) : (v.centre().getZ() > gate.getZ() ? Direction.SOUTH : Direction.NORTH);
        return gate.relative(to, 4);
    }

    /**
     * Once in a while for each town (Nether.tick, once a minute): a town short of its runners picks one; the shop's book
     * keeps the runners' charms and satchels made where there is no smith or tailor to; the wart farm and the trophies at
     * home (NetherHome).
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        ensureDemand();
        long day = level.getDayTime() / 24000L;
        for (int i = 0; i < MOST && have(id) < wanted(id); i++) {
            if (appoint(level, v, day) == null) break;
        }
        NetherHome.tick(level, v, day);
    }

    // ------------------------------------------------------------------ the kit

    /** Is this piece of gold where a piglin looks (a gold armour piece, or the Gold Charm)? */
    public static boolean gold(ItemStack s, net.minecraft.world.entity.LivingEntity wearer) {
        return !s.isEmpty() && s.makesPiglinsNeutral(wearer);
    }

    /** Does it wear gold a piglin can see (the game's own rule: PiglinAi.isWearingGold)? */
    public static boolean wearsGold(net.minecraft.world.entity.LivingEntity f) {
        return net.minecraft.world.entity.monster.piglin.PiglinAi.isWearingGold(f);
    }

    /** [nether] For the watch's fitting (WatchKit.fit): a runner's one piece of gold stays on, whatever the stores hold
     *  that is better armour. */
    public static boolean keepsGold(VillageFolkEntity f, ItemStack worn) {
        if (f.stationTask() != StationTask.NETHER || !gold(worn, f)) return false;
        int n = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            if (gold(f.getItemBySlot(slot), f)) n++;
        }
        return n <= 1;
    }

    /** What a piece is worth as armour (the charm its one point). */
    static int armourOf(ItemStack s) {
        if (s.is(NetherItems.GOLD_CHARM.get())) return 1;
        return s.getItem() instanceof ArmorItem a ? a.getDefense() : 0;
    }

    static boolean isGoldArmour(ItemStack s) {
        return s.getItem() instanceof ArmorItem a && a.getMaterial().is(ArmorMaterials.GOLD);
    }

    /**
     * At least one piece of gold on it, where it costs the least armour: the charm it carries, or one out of the stores,
     * or a gold piece of armour out of the stores in whichever slot loses least to it. The piece it gives up goes back
     * into the stores. With none to be had, it goes without, and keeps clear of the piglins (NetherWork). Returns what
     * it put on, or null.
     */
    @Nullable
    static String goldPiece(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (wearsGold(f)) return null;
        UUID id = v.id();
        String who = f.displayNameCap();
        // Its own charm, in its pack.
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!s.is(NetherItems.GOLD_CHARM.get())) continue;
            ItemStack worn = f.getItemBySlot(EquipmentSlot.HEAD);
            f.setItemSlot(EquipmentSlot.HEAD, s.copy());
            pack.set(i, ItemStack.EMPTY);
            if (!worn.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(worn.copy()), who);
            return "its gold charm";
        }
        // The stores': the slot that loses least to gold (the charm on the brow, or gold armour), the cheaper on a tie.
        EquipmentSlot bestSlot = null;
        Workshop.Found best = null;
        int bestLoss = Integer.MAX_VALUE;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST }) {
            int now = armourOf(f.getItemBySlot(slot));
            if (slot == EquipmentSlot.HEAD) {
                Workshop.Found c = Workshop.bestInStores(level, id, s -> s.is(NetherItems.GOLD_CHARM.get()), s -> 1, -1);
                if (c != null && now - 1 < bestLoss) {
                    bestLoss = now - 1;
                    best = c;
                    bestSlot = slot;
                }
            }
            Workshop.Found g = Workshop.bestInStores(level, id,
                s -> isGoldArmour(s) && s.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == slot, NetherRunners::armourOf, -1);
            if (g != null) {
                int loss = now - g.score();
                if (loss < bestLoss) {
                    bestLoss = loss;
                    best = g;
                    bestSlot = slot;
                }
            }
        }
        if (best == null) return null;
        ItemStack worn = f.getItemBySlot(bestSlot);
        ItemStack piece = WatchKit.mark(Workshop.takeOut(level, id, best, who));
        f.setItemSlot(bestSlot, piece);
        if (!worn.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(worn.copy()), who);
        return Bench.words(piece.getItem(), 1);
    }

    /** One of this out of the stores into its pack (marked the town's when it is kit), up to so many carried; how many it got. */
    static int draw(ServerLevel level, Villages.Village v, VillageFolkEntity f, java.util.function.Predicate<ItemStack> what, int want, int keepInStores,
                    boolean mark) {
        int have = f.countMatching(what);
        int n = Math.min(want - have, Crafts.stock(level, v, what) - keepInStores);
        int got = 0;
        for (int i = 0; i < n; i++) {
            ItemStack s = Crafts.takeOne(level, v, what);
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(mark ? WatchKit.mark(s) : s);
            if (!left.isEmpty()) {
                Crafts.store(level, v, mark ? WatchKit.unmarked(left) : left);
                break;
            }
            got += s.getCount();
            if (have + got >= want) break;
        }
        return got;
    }

    /**
     * Fitted out of the stores for a run of this plan (the watch's way: WatchKit.fit, with the bow), its gold piece on,
     * and packed: fire resistance, arrows, food, cobblestone, the best pick, its satchel, the makings of a crafting table;
     * the leader the flint and steel, and on a first run the outpost's door and lights; gold to barter with when the
     * plan barters. What it carried that is not kit waits in the stores. Returns what it was given, in words.
     */
    public static List<String> kitUp(ServerLevel level, Villages.Village v, VillageFolkEntity f, NetherPlan.Plan plan, boolean leader, boolean gold) {
        List<String> got = new ArrayList<>();
        UUID id = v.id();
        String who = f.displayNameCap();
        // Travelling light: what is not its kit waits in the stores (the haul wants the room).
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || kit(s) || Homes.isKeepsake(s)) continue;
            int keep = Math.min(s.getCount(), keeps(s));
            int out = s.getCount() - keep;
            if (out <= 0) continue;
            Crafts.store(level, v, s.copyWithCount(out));
            s.shrink(out);
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
        }
        if (f.stationTask() == StationTask.NETHER || f.stationTask() == StationTask.GUARD) {
            for (ItemStack s : WatchKit.fit(level, v, f)) got.add(Bench.words(s.getItem(), s.getCount()));
        }
        String piece = goldPiece(level, v, f);
        if (piece != null) got.add(piece + ", worn for the piglins");
        else if (!wearsGold(f)) {
            f.brain("no gold to wear through the gateway: it keeps clear of the piglins");
            FolkTalk.speak(f, "No gold for me this time. I'll keep well clear of the piglins.");
        }
        // A bow and arrows: every runner, for the blazes and the ghasts.
        if (f.countCarried(s -> s.getItem() instanceof BowItem) == 0) {
            Workshop.Found bow = Workshop.bestInStores(level, id, s -> s.getItem() instanceof BowItem,
                s -> Math.min(999, s.getMaxDamage()) + (s.isEnchanted() ? 1000 : 0), -1);
            if (bow != null) {
                ItemStack b = WatchKit.mark(Workshop.takeOut(level, id, bow, who));
                ItemStack left = f.insertGiven(b);
                if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
                else got.add("a bow");
            }
        }
        int arrows = draw(level, v, f, s -> s.is(Items.ARROW), plan.arrows(), 16, false);
        if (arrows > 0) got.add(arrows + " arrows");
        if (f.countCarried(s -> s.getItem() instanceof ShieldItem) == 0 && !(f.getOffhandItem().getItem() instanceof ShieldItem)) {
            Workshop.Found sh = Workshop.bestInStores(level, id, s -> s.getItem() instanceof ShieldItem, s -> Math.min(999, s.getMaxDamage()), -1);
            if (sh != null) {
                ItemStack left = f.insertGiven(WatchKit.mark(Workshop.takeOut(level, id, sh, who)));
                if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
                else got.add("a shield");
            }
        }
        // Fire resistance: the brewer's, two a day when it goes for blazes, one in hand else.
        int pots = draw(level, v, f, NetherPlan::fireResistance, plan.potions(), 0, false);
        if (pots > 0) got.add(pots + (pots == 1 ? " potion" : " potions") + " of fire resistance");
        // Food for the days and one over, as much as the town can spare.
        int ate = 0, spare = NetherPlan.spareFood(level, id);
        for (int i = 0; i < plan.meals() + 4 && f.countMatching(CaveDwellers::food) < plan.meals() && ate < spare; i++) {
            ItemStack s = Crafts.takeOne(level, v, CaveDwellers::food);
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
            ate += s.getCount() - left.getCount();
        }
        if (ate > 0) got.add(ate + " things to eat");
        int cobble = draw(level, v, f, s -> s.is(Items.COBBLESTONE), plan.cobble(), 32, false);
        if (cobble > 0) got.add(cobble + " cobblestone");
        // The best pickaxe the stores hold, if it beats its own.
        ItemStack mine = CaveDwellers.bestPick(f);
        Workshop.Found better = Workshop.bestInStores(level, id, CaveDwellers::isPickaxe, CaveDwellers::pickScore, CaveDwellers.pickScore(mine));
        if (better != null) {
            ItemStack pick = WatchKit.mark(Workshop.takeOut(level, id, better, who));
            if (!mine.isEmpty() && WatchKit.issued(mine)) {
                ItemStack back = mine.copy();
                mine.setCount(0);
                Workshop.backIntoStores(level, v, WatchKit.unmarked(back), who);
            }
            ItemStack left = f.insertGiven(pick);
            if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
            else got.add(Bench.words(pick.getItem(), 1));
        }
        // Its satchel, the tailor's.
        if (f.countMatching(s -> s.getItem() instanceof RunnersSatchelItem) == 0) {
            Workshop.Found sat = Workshop.bestInStores(level, id, s -> s.getItem() instanceof RunnersSatchelItem && RunnersSatchelItem.count(s) == 0, s -> 1, -1);
            if (sat != null) {
                ItemStack left = f.insertGiven(WatchKit.mark(Workshop.takeOut(level, id, sat, who)));
                if (!left.isEmpty()) Workshop.backIntoStores(level, v, WatchKit.unmarked(left), who);
                else got.add("a runner's satchel");
            }
        }
        // Torches for the ways it cuts (a light every eight blocks).
        int torches = draw(level, v, f, s -> s.is(Items.TORCH), 16, Masonry.townTorches(id), false);
        if (torches > 0) got.add(torches + " torches");
        // The makings of crafting on the far side (a stone pick, if its own breaks): a table and a few sticks.
        if (f.countMatching(s -> s.is(Items.CRAFTING_TABLE)) == 0
                && (Crafts.take(level, v, s -> s.is(Items.CRAFTING_TABLE), 1) || Crafts.usePlanks(level, v, 4))) {
            f.insertGiven(WatchKit.mark(new ItemStack(Items.CRAFTING_TABLE)));
            got.add("a crafting table");
        }
        draw(level, v, f, s -> s.is(Items.STICK), 4, 0, false);
        if (leader) {
            // The flint and steel, to light a portal gone dark: the stores', or made of their flint and an iron ingot.
            if (f.countMatching(s -> s.is(Items.FLINT_AND_STEEL)) == 0) {
                if (Crafts.take(level, v, s -> s.is(Items.FLINT_AND_STEEL), 1)
                        || Crafts.stock(level, v, s -> s.is(Items.FLINT)) >= 1 && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 1
                        && Crafts.take(level, v, s -> s.is(Items.FLINT), 1) && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1)) {
                    f.insertGiven(WatchKit.mark(new ItemStack(Items.FLINT_AND_STEEL)));
                    got.add("a flint and steel");
                }
            }
            if (plan.outpost()) {
                // The outpost's door: the stores', or made of six of their planks.
                if (f.countMatching(s -> s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS)) == 0
                        && (Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS), 1) || Crafts.usePlanks(level, v, 6))) {
                    f.insertGiven(new ItemStack(Items.OAK_DOOR));
                    got.add("a door for the outpost");
                }
                // Its lights: soul lanterns (a piglin keeps off soul fire), else lanterns, else torches.
                int lights = draw(level, v, f, s -> s.is(Items.SOUL_LANTERN), 3, 0, false);
                if (lights < 3) lights += draw(level, v, f, s -> s.is(Items.LANTERN), 3 - lights, Crafts.LANTERNS_KEPT / 2, false);
                if (lights > 0) got.add(lights + " lanterns for the outpost");
            }
        }
        if (gold && plan.gold() > 0) {
            int g = draw(level, v, f, s -> s.is(Items.GOLD_INGOT), plan.gold(), NetherPlan.GOLD_KEPT, false);
            if (g > 0) got.add(g + " gold ingots to barter with");
        }
        f.setHealth(Math.max(f.getHealth(), f.getMaxHealth() * 0.5F));
        if (!got.isEmpty()) f.brain("fitted out for the Nether by the town: " + String.join(", ", got));
        return got;
    }

    /** Its kit, kept when it banks: what it wears and wields, its satchel, what the town marked as issued. */
    static boolean kit(ItemStack s) {
        boolean found = CaveDwellers.valuable(s) && !WatchKit.issued(s);
        return s.isDamageableItem() && !found || WatchKit.issued(s) || s.getItem() instanceof RunnersSatchelItem || s.is(NetherItems.GOLD_CHARM.get());
    }

    /** What a runner keeps in its pack of each thing over its kit (AssistantEntity.jobDepositReserve): the run's arrows,
     *  potions, cobblestone, food and makings; the haul goes home. */
    public static int keeps(ItemStack s) {
        if (s.is(Items.ARROW)) return 64;
        if (NetherPlan.fireResistance(s)) return 6;
        if (s.is(Items.COBBLESTONE)) return 64;
        if (s.is(Items.TORCH) || s.is(Items.SOUL_TORCH)) return 16;
        if (s.is(Items.FLINT_AND_STEEL) || s.is(Items.CRAFTING_TABLE) || s.getItem() instanceof RunnersSatchelItem || s.is(NetherItems.GOLD_CHARM.get())) return 1;
        if (s.is(Items.STICK)) return 4;
        if (s.is(Items.BOW) || s.is(Items.SHIELD)) return 1;
        if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null && !CaveDwellers.valuable(s)) return 8;
        return 0;
    }

    /** May this one draw a bow the town gave it (AssistantEntity.mayShoot)? Every runner, for the blazes and the ghasts. */
    public static boolean archer(AssistantEntity a) {
        return a.stationTask() == StationTask.NETHER;
    }

    /** The runners' bows and arrows the fletcher keeps the stores in (Crafts.fletch): one more bow's worth a runner. */
    public static int bows(@Nullable UUID village) {
        return village == null ? 0 : runners(village).size();
    }

    /** A runner leaving the trade (another trade, another town) hands back what the town issued it, into the stores. */
    public static int handBack(VillageFolkEntity f, String why) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(f.level() instanceof ServerLevel level)) return 0;
        String who = f.displayNameCap();
        List<String> back = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = f.getItemBySlot(slot);
            if (!WatchKit.issued(s) && !s.is(NetherItems.GOLD_CHARM.get())) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            f.setItemSlot(slot, ItemStack.EMPTY);
        }
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!WatchKit.issued(s) && !(s.getItem() instanceof RunnersSatchelItem)) continue;
            back.add(Bench.words(s.getItem(), s.getCount()));
            if (s.getItem() instanceof RunnersSatchelItem) for (ItemStack in : RunnersSatchelItem.unpack(s)) Crafts.store(level, v, in);
            Workshop.backIntoStores(level, v, WatchKit.unmarked(s.copy()), who);
            pack.set(i, ItemStack.EMPTY);
        }
        if (back.isEmpty()) return 0;
        f.brain("handed the runners' kit back into the stores (" + why + "): " + String.join(", ", back));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The runners' kit goes back to the stores. The next one through will want it.",
            "Back it goes: " + String.join(", ", back) + ". It was never mine to keep."));
        return back.size();
    }

    // ------------------------------------------------------------------ the makers

    /** The runners without gold to wear (worn, carried, or a charm in the stores for them). */
    static int wantingGold(ServerLevel level, UUID village) {
        int n = 0;
        for (VillageFolkEntity f : runners(village)) {
            if (wearsGold(f) || f.countMatching(s -> s.is(NetherItems.GOLD_CHARM.get())) > 0) continue;
            n++;
        }
        int inStores = Market.stock(level, village, s -> s.is(NetherItems.GOLD_CHARM.get()) || isGoldArmour(s));
        return Math.max(0, n - inStores);
    }

    /** The runners without a satchel (carried, or an empty one in the stores for them). */
    static int wantingSatchels(ServerLevel level, UUID village) {
        int n = 0;
        for (VillageFolkEntity f : runners(village)) if (f.countMatching(s -> s.getItem() instanceof RunnersSatchelItem) == 0) n++;
        return Math.max(0, n - Market.stock(level, village, s -> s.getItem() instanceof RunnersSatchelItem));
    }

    /**
     * The smith's part (Crafts.smith): a Gold Charm for each runner with no gold to wear, by its recipe (a gold ingot,
     * three nuggets and a string; the nuggets beaten from an ingot when the stores have none), and a flint and steel for
     * the leader when the stores have none. Returns what it made, or null.
     */
    @Nullable
    public static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        if (runners(id).isEmpty()) return null;
        if (wantingGold(level, id) > 0 && Crafts.stock(level, v, s -> s.is(Items.STRING)) >= 1) {
            int ingots = Crafts.stock(level, v, s -> s.is(Items.GOLD_INGOT)), nuggets = Crafts.stock(level, v, s -> s.is(Items.GOLD_NUGGET));
            boolean ok = nuggets >= 3 ? ingots >= 1 : ingots >= 2;
            if (ok) {
                if (nuggets < 3 && Crafts.take(level, v, s -> s.is(Items.GOLD_INGOT), 1)) Crafts.store(level, v, new ItemStack(Items.GOLD_NUGGET, 9));
                if (Crafts.take(level, v, s -> s.is(Items.GOLD_NUGGET), 3) && Crafts.take(level, v, s -> s.is(Items.GOLD_INGOT), 1)
                        && Crafts.take(level, v, s -> s.is(Items.STRING), 1)) {
                    ItemStack charm = new ItemStack(NetherItems.GOLD_CHARM.get());
                    Economy.produced(f, charm.copy());
                    Crafts.store(level, v, charm);
                    Villages.tell(id, level.getDayTime() / 24000L, f.displayNameCap() + " the smith beat a gold charm for the Nether runners, so the piglins leave them be");
                    return "a gold charm for the Nether runners";
                }
            }
        }
        if (Crafts.stock(level, v, s -> s.is(Items.FLINT_AND_STEEL)) < 1 && Crafts.stock(level, v, s -> s.is(Items.FLINT)) >= 1
                && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 1 + Crafts.IRON_KEPT / 4
                && Crafts.take(level, v, s -> s.is(Items.FLINT), 1) && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1)) {
            ItemStack fs = new ItemStack(Items.FLINT_AND_STEEL);
            Economy.produced(f, fs.copy());
            Crafts.store(level, v, fs);
            return "a flint and steel for the Nether runners";
        }
        return null;
    }

    /**
     * The tailor's part (Crafts.tailor): a Runner's Satchel for each runner without one, by its recipe (five leather, a
     * string, and a magma cream to wax it against the heat). Returns what it made, or null.
     */
    @Nullable
    public static String tailor(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        if (wantingSatchels(level, id) <= 0) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < 5 + WatchKit.LEATHER_KEPT || Crafts.stock(level, v, s -> s.is(Items.STRING)) < 1
            || Crafts.stock(level, v, s -> s.is(Items.MAGMA_CREAM)) < 1) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 5) || !Crafts.take(level, v, s -> s.is(Items.STRING), 1)
            || !Crafts.take(level, v, s -> s.is(Items.MAGMA_CREAM), 1)) return null;
        ItemStack satchel = new ItemStack(NetherItems.RUNNERS_SATCHEL.get());
        Economy.produced(f, satchel.copy());
        Crafts.store(level, v, satchel);
        Villages.tell(id, level.getDayTime() / 24000L, f.displayNameCap() + " the tailor stitched a runner's satchel, waxed with magma cream against the Nether's heat");
        return "a runner's satchel for the Nether runners";
    }

    /** Where there is no smith or tailor, the shop's workshop keeps the runners' charms and satchels made (Workshop.demand). */
    static void ensureDemand() {
        if (demanded) return;
        demanded = true;
        Workshop.demand("the Nether runners' kit", (level, v, want) -> {
            UUID id = v.id();
            if (runners(id).isEmpty()) return;
            boolean smith = false, tailor = false;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a.stationTask() == StationTask.SMITH) smith = true;
                if (a.stationTask() == StationTask.TAILOR) tailor = true;
            }
            if (!smith) want.accept(NetherItems.GOLD_CHARM.get(), Math.min(MOST, wantingGold(level, id)));
            if (!tailor) want.accept(NetherItems.RUNNERS_SATCHEL.get(), Math.min(MOST, wantingSatchels(level, id)));
        });
    }

    // ------------------------------------------------------------------ at home

    /**
     * A runner's day at home (its station brain): of a morning, rested and the town wanting something out of the Nether,
     * the team gathers at the gateway and goes through; else it is at its post before the gateway. True if it did
     * something.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (NetherRuns.on(f) || NetherRuns.elsewhere(f)) return true;
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        BlockPos post = post(level, v);
        if (f.stationPos() == null || f.stationPos().distSqr(post) > 16) {
            f.setStation(post, StationTask.NETHER);
            f.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Gateway");
        }
        boolean fit = f.getHealth() >= f.getMaxHealth() * 0.75F && !level.isThundering() && !Raids.underAlarm(id);
        boolean morning = time >= MORNING_FROM && time < MORNING_TO;
        if (morning && fit && rested(id, day) && !Assemblies.attending(f) && Nether.lit(level, v)) {
            // A player booked to go along: the team waits for it before the gateway (NetherGuests).
            if (NetherGuests.waitFor(level, f, id, day, time, post)) return true;
            NetherRuns.Run r = NetherRuns.setOut(level, v, day, false);
            if (r != null && r.members.contains(f.getUUID())) return true;
        }
        if (f.blockPosition().distSqr(post) > 9) {
            if (f.getNavigation().isDone()) f.walkTo(post, 0.8D);
        } else {
            BlockPos gate = NetherRuns.gatePortal(level, id);
            if (gate != null) f.getLookControl().setLookAt(gate.getX() + 0.5, gate.getY() + 1.0, gate.getZ() + 0.5);
            if (f.getHealth() < f.getMaxHealth()) {
                f.hobbyNow = "resting by the gateway, healing after the last run";
                if (level.getGameTime() % 200 == 0) f.eatFromPack();
            } else {
                f.hobbyNow = FolkTalk.pick(f.getRandom(), "by the gateway, going over the runners' chart", "by the gateway, keeping an eye on the portal");
            }
        }
        return true;
    }

    /** Have the runners rested since their last run (a day between), or is the town in want of something urgently? */
    static boolean rested(UUID village, long day) {
        String last = Ledger.note(village, "nether.last");
        long lastDay = -10;
        try {
            if (last != null && !last.isEmpty()) lastDay = Long.parseLong(last);
        } catch (NumberFormatException ignored) {
            // never been
        }
        return day - lastDay > REST;
    }

    /** The team could not go today (no food, no cobblestone for the outpost): said, and on the board once a day. */
    static void waited(ServerLevel level, Villages.Village v, VillageFolkEntity first, NetherPlan.Plan plan) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Ledger.note(id, "nether.plan", plan.words());
        Ledger.note(id, "nether.reckoning", String.join("\n", plan.reckoning()));
        if (WAITED.getOrDefault(id, -1L) >= day) return;
        WAITED.put(id, day);
        String why = plan.words().contains("not today: ") ? plan.words().substring(plan.words().indexOf("not today: ") + 11).replaceFirst("\\.$", "") : "we're not fitted out";
        FolkTalk.speak(first, "No run today: " + why + ". The stores'll have to make it up first.");
        Villages.tell(id, day, "the Nether runners could not go through the gateway: " + why);
        LOG.info("[MCA-NETHER] the runners of {} wait: {}", Villages.name(id), plan.words());
    }

    /** Two of the watch's best for a rescue party through the gateway, fit and not on the wall. */
    static List<VillageFolkEntity> rescuers(ServerLevel level, Villages.Village v, int n) {
        List<VillageFolkEntity> watch = new ArrayList<>(WatchKit.watch(v.id()));
        watch.removeIf(g -> g.isSleeping() || g.trip() != null || g.expedition() != null || NetherRuns.away(g) || g.getHealth() < g.getMaxHealth() * 0.8F
            || g.onWatch() || Health.laidUp(g));
        watch.sort((a, b) -> Integer.compare(b.tradeLevel(StationTask.GUARD), a.tradeLevel(StationTask.GUARD)));
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity g : watch) {
            if (out.size() >= n) break;
            out.add(g);
            FolkTalk.speak(g, "I'm with you. We bring them home.");
            g.brain("off through the gateway with the rescue party");
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's books

    /** The runs' tallies kept with the town: runs, blaze rods, quartz, wart, glowstone, pearls, obsidian, barters,
     *  fireballs turned, blazes, ghasts, lost, fallen. */
    static final String[] TALLY = { "runs", "blaze rods", "quartz", "nether wart", "glowstone dust", "ender pearls", "obsidian", "barters",
        "fireballs turned", "blazes", "ghasts", "lost", "fallen" };

    /** A run's tallies added to the town's (NetherRuns.report). */
    static void tally(UUID village, NetherRuns.Run r) {
        int[] t = totals(village);
        t[0]++;
        t[1] += r.storedItems.getOrDefault(Items.BLAZE_ROD, 0);
        t[2] += r.storedItems.getOrDefault(Items.QUARTZ, 0);
        t[3] += r.storedItems.getOrDefault(Items.NETHER_WART, 0);
        t[4] += r.storedItems.getOrDefault(Items.GLOWSTONE_DUST, 0) + 4 * r.storedItems.getOrDefault(Items.GLOWSTONE, 0);
        t[5] += r.storedItems.getOrDefault(Items.ENDER_PEARL, 0);
        t[6] += r.storedItems.getOrDefault(Items.OBSIDIAN, 0);
        t[7] += r.barters;
        t[8] += r.deflected;
        t[9] += r.blazes;
        t[10] += r.ghasts;
        t[11] += r.lost.size();
        t[12] += r.fallen.size();
        StringBuilder sb = new StringBuilder();
        for (int x : t) sb.append(sb.length() == 0 ? "" : ",").append(x);
        Ledger.note(village, "nether.tally", sb.toString());
    }

    /** The town's tallies, in TALLY's order. */
    public static int[] totals(@Nullable UUID village) {
        int[] out = new int[TALLY.length];
        String s = village == null ? null : Ledger.note(village, "nether.tally");
        if (s == null || s.isEmpty()) return out;
        String[] p = s.split(",");
        for (int i = 0; i < Math.min(p.length, out.length); i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException ignored) {
                // left at nought
            }
        }
        return out;
    }

    /** The tallies in words: "6 runs: 40 blaze rods, 210 quartz, ...". */
    static String totalsWords(UUID village) {
        int[] t = totals(village);
        if (t[0] == 0) return "no runs yet";
        List<String> out = new ArrayList<>();
        for (int i = 1; i < TALLY.length; i++) if (t[i] > 0) out.add(t[i] + " " + TALLY[i]);
        return t[0] + (t[0] == 1 ? " run" : " runs") + (out.isEmpty() ? "" : ": " + String.join(", ", out));
    }

    /** For the board: the run under way, or the tallies and the last haul. Null for a town with no runners. */
    @Nullable
    public static String boardLine(UUID village) {
        NetherRuns.Run r = NetherRuns.run(village);
        List<VillageFolkEntity> team = runners(village);
        if (r != null && !team.isEmpty()) {
            long now = team.get(0).level().getDayTime();
            return "Nether: the runners are through the gateway" + (r.days() > 1.0 ? " (" + r.dayOf(now) + ")" : "") + " — " + r.planWords;
        }
        if (team.isEmpty() && totals(village)[0] == 0) return null;
        List<String> hauls = NetherRuns.hauls(village);
        return "Nether: " + totalsWords(village) + (hauls.isEmpty() ? "" : "; last: " + hauls.get(0)) + ".";
    }

    /** "What have the runners found?": the outpost, the fortress, the bastion, the hauls. */
    public static String tell(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "I've no town to keep a report for.";
        List<NetherRuns.Find> all = NetherRuns.report(village);
        boolean me = f.stationTask() == StationTask.NETHER;
        if (all.isEmpty()) {
            if (wanted(village) == 0 && runners(village).isEmpty()) {
                return "Nobody goes through the gateway for us yet: that's for a Nether Age town of " + FROM + " or more, its gateway lit.";
            }
            return me ? "Nothing in the report yet. Give us a run or two." : "The runners haven't been through yet.";
        }
        List<String> out = new ArrayList<>();
        NetherOutpost.Room room = NetherOutpost.room(village);
        if (room != null) out.add(room.built() ? "an outpost walled round the portal" : "an outpost half built");
        NetherRuns.Find fort = NetherRuns.fortress(village), bastion = NetherRuns.bastion(village);
        if (fort != null) out.add("a fortress " + fort.a() + " blocks from the outpost" + (fort.b() > 0 ? " (the path " + fort.b() + " blocks out)" : ""));
        if (bastion != null) out.add("a bastion " + bastion.a() + " blocks off");
        int spawners = 0;
        for (NetherRuns.Find x : all) if (x.kind() == NetherRuns.Kind.SPAWNER) spawners++;
        if (spawners > 0) out.add(spawners == 1 ? "a blaze spawner" : spawners + " blaze spawners");
        String head = me ? "From the runners' report: " : "The Nether runners know of ";
        return head + (out.isEmpty() ? "quartz and glowstone round the outpost" : JobMarket.join(out)) + ". All told, " + totalsWords(village) + ".";
    }

    /** What it is doing, for "What are you up to?". */
    public static String doing(VillageFolkEntity f, net.minecraft.util.RandomSource r) {
        NetherRuns.Run run = NetherRuns.runOf(f);
        if (run != null) {
            if (run.homeward()) return "On my way home from the Nether with the others" + (run.haul().isEmpty() ? "." : ": " + NetherRuns.words(run.haul(), 3) + " between us.");
            NetherWork.Task t = run.task();
            if (NetherRuns.inNether(f) && t != null) return t.words() + ". Hot work — and mind the edges.";
            if (NetherRuns.inNether(f)) return FolkTalk.pick(r, "In the Nether with the runners. Keep your gold on and your voice down.",
                "Through the gateway and working. It's like standing in an oven.");
            return "Off through the gateway with the runners. Fire resistance, bow, gold — all here.";
        }
        if (NetherRuns.lost(f.getUUID())) return "Lost in the Nether! I'm waiting for the others to come back for me.";
        String last = f.ownerId() == null ? null : Ledger.note(f.ownerId(), "nether.lastrun/" + f.getUUID());
        if (last != null) return "Resting up. Last time through: " + last.substring(last.indexOf(':') + 1).trim() + ".";
        return FolkTalk.pick(r, "Getting my kit together for the Nether. Gold, arrows, fire resistance.",
            "Keeping an eye on the gateway. You never know what comes through.");
    }

    /** The runner's card: its level and place in the team, the run it is on or its last, and its kit. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        if (f.stationTask() != StationTask.NETHER || f.isBaby()) return null;
        StringBuilder sb = new StringBuilder();
        UUID village = f.ownerId();
        VillageFolkEntity leader = village == null ? null : leaderOf(village);
        sb.append("level ").append(f.tradeLevel(StationTask.NETHER)).append(leader == f ? ", leads the runners" : leader != null ? ", runs with " + leader.displayNameCap() : "");
        NetherRuns.Run r = NetherRuns.runOf(f);
        if (r != null) {
            sb.append("; ").append(NetherRuns.inNether(f) ? "in the Nether" : "on the way through the gateway").append(" (").append(r.phaseWords()).append(")");
            if (r.days() > 1.0) sb.append(", ").append(r.dayOf(f.level().getDayTime()));
            if (r.mined() + r.blazes() + r.barters() > 0) sb.append(": ").append(r.mined()).append(" dug, ").append(r.blazes()).append(" blazes, ")
                .append(r.barters()).append(" barters");
            if (!r.haul().isEmpty()) sb.append("; carrying ").append(NetherRuns.words(r.haul(), 3));
        } else {
            String last = village == null ? null : Ledger.note(village, "nether.lastrun/" + f.getUUID());
            sb.append("; ").append(last != null ? "last through " + last : "not been through yet");
        }
        String kit = kitWords(f);
        if (!kit.isEmpty()) sb.append(". Kit: ").append(kit);
        return sb.toString();
    }

    /** Its kit in a line: "iron armour with a gold charm, iron sword, bow and 64 arrows, a shield, 2 fire resistance". */
    static String kitWords(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        int armour = 0;
        String metal = null, gold = null;
        for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack s = f.getItemBySlot(slot);
            if (s.is(NetherItems.GOLD_CHARM.get())) gold = "a gold charm";
            else if (isGoldArmour(s)) gold = "gold " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace("golden_", "");
            else if (s.getItem() instanceof ArmorItem) {
                armour++;
                if (metal == null) metal = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().split("_")[0];
            }
        }
        if (armour > 0) out.add(metal + " armour" + (gold != null ? " with " + gold : ""));
        else if (gold != null) out.add(gold);
        else out.add("no gold on");
        ItemStack blade = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (s.getItem() instanceof SwordItem && Workshop.blade(s) > Workshop.blade(blade)) blade = s;
        if (f.getMainHandItem().getItem() instanceof SwordItem && Workshop.blade(f.getMainHandItem()) > Workshop.blade(blade)) blade = f.getMainHandItem();
        if (!blade.isEmpty()) out.add(blade.getHoverName().getString().toLowerCase(Locale.ROOT));
        int arrows = f.countMatching(s -> s.is(Items.ARROW));
        if (f.countCarried(s -> s.getItem() instanceof BowItem) > 0) out.add("a bow" + (arrows > 0 ? " and " + arrows + " arrows" : ""));
        if (f.countCarried(s -> s.getItem() instanceof ShieldItem) > 0 || f.getOffhandItem().getItem() instanceof ShieldItem) out.add("a shield");
        int pots = f.countMatching(NetherPlan::fireResistance);
        if (pots > 0) out.add(pots + " fire resistance");
        if (f.countMatching(s -> s.getItem() instanceof RunnersSatchelItem) > 0) out.add("a satchel");
        return String.join(", ", out);
    }

    /** The Nether page of the town's books (CityScreen's "Nether", client/NetherPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        out.putInt("wanted", wanted(id));
        out.putInt("from", FROM);
        out.putString("age", Villages.ageOf(id).label);
        out.putBoolean("gateway", Villages.hasBuilt(id, "gateway"));
        out.putBoolean("lit", Nether.opened(id));
        String plan = Ledger.note(id, "nether.plan");
        out.putString("plan", plan == null ? "" : plan);
        ListTag reck = new ListTag();
        String rk = Ledger.note(id, "nether.reckoning");
        if (rk != null && !rk.isEmpty()) for (String line : rk.split("\n")) reck.add(StringTag.valueOf(line));
        out.put("reckoning", reck);
        NetherRuns.Run r = NetherRuns.run(id);
        out.putString("run", r == null ? "" : (r.homeward() ? "homeward" : "through the gateway") + (r.days() > 1.0 ? ", " + r.dayOf(level.getDayTime()) : "")
            + " (" + r.phaseWords() + ")" + (r.task() != null ? ": " + r.task().words().toLowerCase(Locale.ROOT) : ""));
        // The totals, and what the brewer and enchanter now have of it.
        ListTag tally = new ListTag();
        int[] t = totals(id);
        for (int i = 0; i < TALLY.length; i++) {
            CompoundTag c = new CompoundTag();
            c.putString("what", TALLY[i]);
            c.putInt("n", t[i]);
            tally.add(c);
        }
        out.put("tally", tally);
        ListTag stock = new ListTag();
        for (Item it : new Item[]{ Items.NETHER_WART, Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.MAGMA_CREAM, Items.QUARTZ, Items.GLOWSTONE_DUST,
            Items.SOUL_SAND, Items.ENDER_PEARL, Items.OBSIDIAN, Items.GHAST_TEAR }) {
            CompoundTag c = new CompoundTag();
            c.putString("what", new ItemStack(it).getHoverName().getString());
            c.putInt("n", Market.stock(level, id, s -> s.is(it)));
            stock.add(c);
        }
        CompoundTag fr = new CompoundTag();
        fr.putString("what", "Fire resistance");
        fr.putInt("n", Market.stock(level, id, NetherPlan::fireResistance));
        stock.add(fr);
        out.put("stock", stock);
        out.putInt("wartPlants", NetherHome.wartPlants(id));
        // The outpost, the fortress and the bastion, and every find.
        NetherOutpost.Room room = NetherOutpost.room(id);
        out.putString("outpost", room == null ? "" : (room.built() ? "walled in" : "half built") + " round the portal at " + room.portal().getX() + " "
            + room.portal().getY() + " " + room.portal().getZ() + " (the Nether)");
        out.putString("highway", NetherOutpost.highwayPlan(id));
        ListTag finds = new ListTag();
        BlockPos from = room != null ? room.portal() : BlockPos.ZERO;
        for (NetherRuns.Find x : NetherRuns.report(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("kind", x.kind().name());
            c.putString("label", x.label());
            c.putInt("x", x.at().getX());
            c.putInt("y", x.at().getY());
            c.putInt("z", x.at().getZ());
            c.putInt("dx", x.at().getX() - from.getX());
            c.putInt("dz", x.at().getZ() - from.getZ());
            c.putInt("a", x.a());
            c.putInt("b", x.b());
            c.putLong("day", x.day());
            c.putString("by", x.by());
            finds.add(c);
        }
        out.put("finds", finds);
        ListTag folk = new ListTag();
        VillageFolkEntity leader = leaderOf(id);
        for (VillageFolkEntity f : runners(id)) {
            CompoundTag c = new CompoundTag();
            c.putString("name", f.displayNameCap());
            c.putInt("level", f.tradeLevel(StationTask.NETHER));
            c.putBoolean("leader", f == leader);
            String card = cardLine(f);
            c.putString("card", card == null ? "" : card);
            c.putBoolean("through", NetherRuns.inNether(f));
            folk.add(c);
        }
        out.put("runners", folk);
        ListTag lost = new ListTag();
        for (NetherRuns.Lost l : NetherRuns.lostOf(id)) lost.add(StringTag.valueOf(l.name() + ", lost at " + l.at().getX() + " " + l.at().getY() + " " + l.at().getZ()));
        out.put("lost", lost);
        ListTag hauls = new ListTag();
        for (String h : NetherRuns.hauls(id)) hauls.add(StringTag.valueOf(h));
        out.put("hauls", hauls);
        return out;
    }

    /** The Nether page as lines of text (/village nether). */
    static List<String> page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("The Nether runners of " + Villages.name(id) + ": " + runners(id).size() + " of " + wanted(id) + " wanted"
            + (Nether.opened(id) ? ", the gateway lit" : Villages.hasBuilt(id, "gateway") ? ", the gateway dark" : ", no gateway yet") + ".");
        for (VillageFolkEntity f : runners(id)) out.add("  " + f.displayNameCap() + ": " + cardLine(f));
        String plan = Ledger.note(id, "nether.plan");
        if (plan != null && !plan.isEmpty()) out.add("Plan: " + plan);
        String rk = Ledger.note(id, "nether.reckoning");
        if (rk != null && !rk.isEmpty()) for (String line : rk.split("\n")) out.add("  " + line);
        NetherRuns.Run r = NetherRuns.run(id);
        if (r != null) out.add("Under way: " + r.phaseWords() + (r.task() != null ? ", " + r.task().words() : "") + "; " + r.events().size() + " things happened");
        out.add("All told: " + totalsWords(id) + ".");
        out.add("The brewer and the enchanter have: " + Market.stock(level, id, s -> s.is(Items.NETHER_WART)) + " nether wart, "
            + Market.stock(level, id, s -> s.is(Items.BLAZE_ROD)) + " blaze rods, " + Market.stock(level, id, s -> s.is(Items.BLAZE_POWDER)) + " blaze powder, "
            + Market.stock(level, id, NetherPlan::fireResistance) + " fire resistance, " + Market.stock(level, id, s -> s.is(Items.ENDER_PEARL)) + " ender pearls, "
            + Market.stock(level, id, s -> s.is(Items.OBSIDIAN)) + " obsidian; the wart farm has " + NetherHome.wartPlants(id) + " plants.");
        NetherOutpost.Room room = NetherOutpost.room(id);
        if (room != null) out.add("Outpost: " + (room.built() ? "walled in" : "half built") + " at " + room.portal().toShortString() + " in the Nether.");
        for (NetherRuns.Find x : NetherRuns.report(id)) {
            if (x.kind() == NetherRuns.Kind.QUARTZ || x.kind() == NetherRuns.Kind.GLOWSTONE) continue;
            out.add("  " + x.label() + " at " + x.at().toShortString() + " (day " + (x.day() + 1) + ", " + x.by() + ")");
        }
        for (NetherRuns.Lost l : NetherRuns.lostOf(id)) out.add("Lost: " + l.name() + " at " + l.at().toShortString());
        for (String h : NetherRuns.hauls(id)) out.add("  " + h);
        String high = NetherOutpost.highwayPlan(id);
        if (!high.isEmpty()) out.add(high);
        return out;
    }

    // ------------------------------------------------------------------ the command

    /**
     * /village nether: the runners, the plan, the report and the hauls. {@code books} opens the town's books at the
     * Nether page. Operators: {@code now} sends the runners through now (picking them first, if the town wants them and
     * has none); {@code stage} sets the pictures up (NetherStage).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("nether")
            .executes(NetherRunners::cmdPage)
            .then(Commands.literal("books").executes(NetherRunners::cmdBooks))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                List<String> said = new ArrayList<>();
                long day = level.getDayTime() / 24000L;
                for (int i = 0; i < MOST && runners(v.id()).size() < wanted(v.id()); i++) {
                    VillageFolkEntity f = appoint(level, v, day);
                    if (f == null) break;
                    said.add(f.displayNameCap() + " joined the runners.");
                }
                NetherRuns.Run r = NetherRuns.sendForTests(level, v);
                said.add(r == null ? "Nobody could go (" + runners(v.id()).size() + " runners, " + wanted(v.id()) + " wanted; " + Ledger.note(v.id(), "nether.plan") + ")."
                    : "The runners set out: " + r.members().size() + " of them; " + r.planWords);
                ctx.getSource().sendSuccess(() -> Component.literal("NETHER " + String.join(" ", said)), false);
                return said.size();
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                List<String> out = NetherStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
    static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getServer().getLevel(v.dim());
        List<String> lines = page(level != null ? level : ctx.getSource().getLevel(), v);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return cmdPage(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Nether");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }
}
