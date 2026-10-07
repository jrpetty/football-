package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.PitPropBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.FellingSawItem;
import com.jrpetty.mcassistant.item.OreSackItem;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [workitems] The tools of the mine, the woods and the roads (item/WorkItems), in the town's hands.
 *
 * <p><b>The makers.</b> Each is made by its own trade, at its bench, out of the stores, by its real recipe (Bench), whenever
 * the town wants one kept and the age allows it (Tiers): the woodcutter's pit props and shipping crates, the tailor's
 * rope coils and ore sacks, the smith's felling saws, the farmer's thatch (out of the wheat the town can spare); and the
 * shop's workshop makes all of them for a town without the trade, and the window boxes (entity/WindowBoxes). What the town
 * wants kept: props for its miners, a rope for each miner and two for each cave dweller, a sack each, a saw for each
 * woodcutter, crates for its couriers and its caravans, thatch while it roofs in thatch, the milestones its roads still
 * want, and boxes for the well-off houses with none.
 *
 * <p><b>The kit.</b> A miner draws a few props, a rope and a sack out of the stores; a woodcutter its saw; a courier two
 * crates (kitUp); the cave team its two ropes and its sack with the rest of its kit (CaveDwellers.kitUp).
 *
 * <p><b>Pit props.</b> A miner sets one at the foot of its stairs and every five steps along its gallery where there is
 * rock overhead, no other prop near, and one in its pack (propStep). Gravel and sand over a propped stretch do not fall
 * in: the fall is caught and the block put back as it was (onEntityJoin). A miner working a propped face digs a sixth
 * quicker (propPace), and the mine's report says so (mineReport).
 *
 * <p><b>The ore sack.</b> A miner or a cave dweller whose pack fills tips its ore, coal and gems into the sack and works on
 * (stowOre); the stores take the sack's load with the rest (unpackInto, unpackSacks). A player carrying one has what it
 * picks up of ore and gems go into it.
 *
 * <p><b>The felling saw.</b> A woodcutter with one, taking a tree's bottom log, fells the whole tree (fellRest): the logs
 * joined to it up to sixty-four, never a building's (no log on the town's built ground, none beside a plank, a pane or
 * anything else a hand put there) and only a tree's (leaves round its crown); the logs drop at the stump, where it sweeps
 * them up, and it replants as ever. A player sneaking does the same.
 */
public final class WorkTools {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private WorkTools() {}

    /** A prop every so many steps of gallery, and none within so many blocks of another. */
    static final int PROP_EVERY = 5, PROP_SPACE = 3;
    /** How far across and how high over a prop the roof is held. */
    static final int HOLDS_ACROSS = 3, HOLDS_UP = 6;
    /** A propped face: so many props in it; and how much quicker its miner digs there (percent of the time). */
    static final int PROPPED = 2, PROPPED_PACE = 85;
    /** What a miner carries of props, at the least and at the most. */
    static final int PROPS_LOW = 3, PROPS_KIT = 6;
    /** How often a hand looks to its kit, and a woodcutter or a farmer to its bench (ticks). */
    static final int KIT_EVERY = 600, BENCH_EVERY = 800;

    private static final Map<UUID, Integer> KIT_LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> BENCH_LOOKED = new ConcurrentHashMap<>();
    /** Each miner's steps along its gallery since its last prop. */
    private static final Map<UUID, Integer> SINCE_PROP = new ConcurrentHashMap<>();
    /** Each miner's face, looked at: {gameTime, propped 1/0}. */
    private static final Map<UUID, long[]> PROPPED_AT = new ConcurrentHashMap<>();
    /** What the town wants kept, worked out at most every ten seconds: {time, map}. */
    private static final Map<UUID, Object[]> WANTS = new ConcurrentHashMap<>();
    /** Each woodcutter's whole trees: {trees, logs}. */
    private static final Map<UUID, int[]> FELLED = new ConcurrentHashMap<>();
    private static volatile boolean demanded;

    public static void resetForTests() {
        KIT_LOOKED.clear();
        BENCH_LOOKED.clear();
        SINCE_PROP.clear();
        PROPPED_AT.clear();
        WANTS.clear();
        FELLED.clear();
        Ropes.resetForTests();
        Crates.resetForTests();
        Thatch.resetForTests();
        Milestones.resetForTests();
        WindowBoxes.resetForTests();
    }

    // ------------------------------------------------------------------ the game's events

    /** The game's events the work items answer: a fall of gravel over a prop, a saw at a tree, a sack picking up. */
    public static void listen(IEventBus bus) {
        bus.addListener(WorkTools::onEntityJoin);
        bus.addListener(WorkTools::onBreak);
        bus.addListener(WorkTools::onPickup);
    }

    /**
     * A block of gravel or sand about to fall (FallingBlockEntity.fall has taken it out of the world and is adding the
     * falling one): over a propped stretch the prop holds it, and it is put straight back as it was, its fall cancelled
     * and its own scheduled look at the gap under it struck off, so it does not try again until something about it
     * changes. Within three blocks across of a prop and up to six over it.
     */
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof FallingBlockEntity fb) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockState st = fb.getBlockState();
        if (!(st.getBlock() instanceof FallingBlock)) return;
        BlockPos at = fb.blockPosition();
        if (!held(level, at) || !level.getBlockState(at).canBeReplaced()) return;
        event.setCanceled(true);
        level.setBlock(at, st, 2 | 16);
        level.getBlockTicks().clearArea(new BoundingBox(at));
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || !TownMine.inMine(v.id(), at)) continue;
            Ledger.note(v.id(), "work.held", String.valueOf(count(v.id(), "work.held") + 1));
            break;
        }
    }

    /** Is this spot's roof held by a prop under it? */
    public static boolean held(ServerLevel level, BlockPos at) {
        for (BlockPos p : WorkSites.propsNear(level, at, HOLDS_ACROSS)) {
            int up = at.getY() - p.getY();
            if (up >= 2 && up <= HOLDS_UP && level.getBlockState(p).getBlock() instanceof PitPropBlock) return true;
        }
        return false;
    }

    /** A player sneaking with a felling saw, cutting a tree's log: the whole tree comes down, the logs at the stump. */
    static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (p == null || !p.isShiftKeyDown() || !(event.getLevel() instanceof ServerLevel level)) return;
        ItemStack saw = p.getMainHandItem();
        if (!(saw.getItem() instanceof FellingSawItem) || !event.getState().is(BlockTags.LOGS)) return;
        List<BlockPos> tree = tree(level, event.getPos(), null);
        if (tree.isEmpty()) return;
        int n = fell(level, tree, event.getPos(), p, saw);
        if (n > 0) {
            saw.hurtAndBreak(n, p, EquipmentSlot.MAINHAND);
            p.displayClientMessage(Component.literal("The saw takes the whole tree down: " + (n + 1) + " logs."), true);
        }
    }

    /** A player carrying an ore sack picks up ore or gems: into the sack first. */
    static void onPickup(ItemEntityPickupEvent.Pre event) {
        Player p = event.getPlayer();
        ItemStack s = event.getItemEntity().getItem();
        if (p.level().isClientSide || !OreSackItem.mineral(s) || event.getItemEntity().hasPickUpDelay()) return;
        for (ItemStack held : p.getInventory().items) {
            if (!(held.getItem() instanceof OreSackItem)) continue;
            ItemStack left = OreSackItem.add(held, s);
            if (left.getCount() == s.getCount()) continue;
            int took = s.getCount() - left.getCount();
            p.take(event.getItemEntity(), took);
            s.setCount(left.getCount());
            if (s.isEmpty()) {
                event.getItemEntity().discard();
                event.setCanPickup(TriState.FALSE);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ the ages

    /**
     * The age a work item belongs to where its recipe would say otherwise (Tiers.material): the rope coil is the Wood
     * Age's (a strip of leather off a hide is no tanner's work), the felling saw and the shipping crate the Stone Age's
     * (an iron blade and a nail are the smith's odd bars, not the Iron Age's tools). Null for anything else.
     */
    @Nullable
    public static Villages.Age ageOf(Item it) {
        try {
            if (it == WorkItems.ROPE_COIL.get()) return Villages.Age.WOOD;
            if (it == WorkItems.FELLING_SAW.get() || it == WorkItems.SHIPPING_CRATE_ITEM.get()) return Villages.Age.STONE;
        } catch (RuntimeException notYet) {
            // the registries not built yet
        }
        return null;
    }

    // ------------------------------------------------------------------ what the town wants kept, and who makes it

    /** What the town wants kept in its stores of the work items (how many of each), worked out at most every ten seconds. */
    static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Object[] c = WANTS.get(id);
        if (c != null && now - (Long) c[0] < 200L && now >= (Long) c[0]) {
            @SuppressWarnings("unchecked") Map<Item, Integer> m = (Map<Item, Integer>) c[1];
            return m;
        }
        int miners = 0, cavers = 0, woodcutters = 0, couriers = 0, ropesShort = 0, sacksShort = 0, sawsShort = 0, cratesShort = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            switch (f.stationTask()) {
                case MINE -> {
                    miners++;
                    if (f.countCarried(WorkTools::rope) == 0) ropesShort++;
                    if (f.countCarried(WorkTools::sack) == 0) sacksShort++;
                }
                case CAVE -> {
                    cavers++;
                    ropesShort += Math.max(0, 2 - f.countCarried(WorkTools::rope));
                    if (f.countCarried(WorkTools::sack) == 0) sacksShort++;
                }
                case WOOD -> {
                    woodcutters++;
                    if (f.countCarried(WorkTools::saw) == 0) sawsShort++;
                }
                case HAUL -> {
                    couriers++;
                    cratesShort += Math.max(0, Crates.KIT - f.countCarried(WorkTools::crate));
                }
                default -> { }
            }
        }
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (miners > 0) out.put(WorkItems.PIT_PROP_ITEM.get(), Math.min(32, 8 * miners));
        if (ropesShort > 0) out.put(WorkItems.ROPE_COIL.get(), ropesShort + 1);
        if (sacksShort > 0) out.put(WorkItems.ORE_SACK.get(), sacksShort);
        if (sawsShort > 0) out.put(WorkItems.FELLING_SAW.get(), sawsShort);
        int crates = cratesShort + (Crates.caravans(id) ? Crates.CARAVAN : 0);
        if (crates > 0) out.put(WorkItems.SHIPPING_CRATE_ITEM.get(), crates);
        if (Thatch.roofing(level, v)) out.put(WorkItems.THATCH_ITEM.get(), Thatch.KEPT);
        int stones = Milestones.wanted(level, v);
        if (stones > 0) out.put(WorkItems.MILESTONE_ITEM.get(), stones);
        int boxes = WindowBoxes.wanted(level, v);
        if (boxes > 0) out.put(WorkItems.WINDOW_BOX_ITEM.get(), boxes);
        WANTS.put(id, new Object[]{ now, out });
        return out;
    }

    /** Whose work each is: the woodcutter's props and crates, the tailor's ropes and sacks, the smith's saw, the farmer's
     *  thatch; the shop's workshop makes any of them, and the window boxes and milestones besides. */
    static boolean makes(StationTask t, Item it) {
        if (t == StationTask.SHOP) return true;
        return switch (t) {
            case WOOD -> it == WorkItems.PIT_PROP_ITEM.get() || it == WorkItems.SHIPPING_CRATE_ITEM.get();
            case TAILOR -> it == WorkItems.ROPE_COIL.get() || it == WorkItems.ORE_SACK.get();
            case SMITH -> it == WorkItems.FELLING_SAW.get();
            case FARM -> it == WorkItems.THATCH_ITEM.get();
            default -> false;
        };
    }

    /**
     * The shop's workshop keeps them on its order book too (and so a town with no tailor, smith or woodcutter has them
     * made there): put on the book at the first town's round, once the game's registries are ready.
     */
    static void demandOnce() {
        if (demanded) return;
        demanded = true;
        Workshop.demand("the mine's, the woods' and the roads' tools", (level, v, want) -> {
            for (Map.Entry<Item, Integer> e : wanted(level, v).entrySet()) {
                if (e.getKey() != WorkItems.WINDOW_BOX_ITEM.get()) want.accept(e.getKey(), e.getValue());
            }
        });
    }

    /**
     * A turn at the work items (Crafts.now for the crafts, kitUp for the woodcutter and the farmer, one turn in two): the
     * first the stores are short of that is this trade's work and the age allows, made at the bench out of the stores by
     * its recipe (Bench; a window box by its flower's own recipe, WindowBoxes.make). What was made, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (Math.floorMod(level.getGameTime() / Crafts.EVERY + f.getUUID().hashCode() + 1, 2L) != 0) return null;
        return craftNow(level, v, f);
    }

    @Nullable
    static String craftNow(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty()) return null;
        Villages.Age age = Villages.ageOf(v.id());
        Bench.Hand hand = null;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item it = e.getKey();
            if (!makes(t, it) || !Tiers.allows(level, age, it)) continue;
            if (Market.stock(level, v.id(), s -> s.is(it)) + ShopStock.held(level, v.id(), s -> s.is(it)) >= e.getValue()) continue;
            if (it == WorkItems.WINDOW_BOX_ITEM.get()) {
                String box = WindowBoxes.make(level, v, f);
                if (box != null) return box;
                continue;
            }
            if (it == WorkItems.THATCH_ITEM.get() && !Thatch.wheatToSpare(level, v)) continue;
            if (hand == null) hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t));
            Bench.Plan plan = Bench.plan(level, v, it, 1, hand);
            if (!plan.ok()) continue;
            ItemStack out = Bench.make(level, v, plan, f, hand);
            if (out.isEmpty()) continue;
            WANTS.remove(v.id());
            LOG.info("[MCA-WORK] {} ({}) made {} for the town", f.displayNameCap(), t.title, Bench.words(out.getItem(), out.getCount()));
            return Bench.words(out.getItem(), out.getCount()) + forWhat(it);
        }
        return null;
    }

    private static String forWhat(Item it) {
        if (it == WorkItems.PIT_PROP_ITEM.get()) return ", for the mine's roof";
        if (it == WorkItems.ROPE_COIL.get()) return ", for the shafts and the ravines";
        if (it == WorkItems.ORE_SACK.get()) return ", for the miners' ore";
        if (it == WorkItems.FELLING_SAW.get()) return ", for the woods";
        if (it == WorkItems.THATCH_ITEM.get()) return ", for the roofs";
        if (it == WorkItems.SHIPPING_CRATE_ITEM.get()) return ", for the haulers";
        if (it == WorkItems.MILESTONE_ITEM.get()) return ", for the roads";
        return "";
    }

    /** Tests: a turn at the work items now (whatever the clock says): what was made, or null. */
    @Nullable
    public static String craftForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return null;
        WANTS.remove(v.id());
        return craftNow(level, v, f);
    }

    /** Tests: what the town wants kept of the work items now. */
    public static Map<Item, Integer> wantedForTests(ServerLevel level, UUID village) {
        WANTS.remove(village);
        Villages.Village v = Villages.get(village);
        return v == null ? Map.of() : wanted(level, v);
    }

    // ------------------------------------------------------------------ the kit

    static boolean prop(ItemStack s) { return s.is(WorkItems.PIT_PROP_ITEM.get()); }
    static boolean rope(ItemStack s) { return s.is(WorkItems.ROPE_COIL.get()); }
    static boolean sack(ItemStack s) { return s.getItem() instanceof OreSackItem; }
    static boolean saw(ItemStack s) { return s.getItem() instanceof FellingSawItem; }
    static boolean crate(ItemStack s) { return s.is(WorkItems.SHIPPING_CRATE_ITEM.get()); }

    /**
     * What a hand of this trade keeps in its pack of the work items and never banks (Trades.keeps). A sack or a crate
     * with a load in it is not kept: the load is the stores' (and a deposit run is worth making for it), and once it is
     * unpacked at the chest (unpackInto) the empty sack or crate is kept again.
     */
    public static int keeps(StationTask t, ItemStack s) {
        boolean emptySack = sack(s) && OreSackItem.count(s) == 0;
        boolean emptyCrate = crate(s) && ShippingCrateBlock.contents(s).isEmpty();
        return switch (t) {
            case MINE -> prop(s) ? 16 : rope(s) || emptySack ? 1 : 0;
            case CAVE -> rope(s) ? 2 : emptySack ? 1 : 0;
            case WOOD -> saw(s) ? 1 : 0;
            case HAUL -> emptyCrate ? Crates.KIT : 0;
            default -> 0;
        };
    }

    /** The cave team's own of the work items (CaveDwellers.kit): its ropes and its sack go down with it and come home. */
    public static boolean caveKit(ItemStack s) {
        return rope(s) || sack(s);
    }

    /**
     * Its trade's work items out of the stores, busy or not (VillageFolkEntity.agenda, with the other tools from the
     * stores), looked at every half minute: a miner's props (when it is down to its last few), its rope and its sack; a
     * woodcutter's saw; a courier's crates. A woodcutter and a farmer also take their turn at their bench here (craft).
     * True if it took anything.
     */
    public static boolean kitUp(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.isBaby() || f.ownerId() == null || f.villageCentre() == null) return false;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return false;
        StationTask t = f.stationTask();
        if ((t == StationTask.WOOD || t == StationTask.FARM) && f.tickCount - BENCH_LOOKED.getOrDefault(f.getUUID(), -100000) >= BENCH_EVERY) {
            BENCH_LOOKED.put(f.getUUID(), f.tickCount);
            String made = craftNow(level, v, f);                   // (its bench is looked at every forty seconds already)
            if (made != null) {
                f.swing(InteractionHand.MAIN_HAND);
                f.note(AssistantEntity.Deed.THINGS_MADE, 1);
                f.brain("made " + made);
            }
        }
        int last = KIT_LOOKED.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < KIT_EVERY && f.tickCount >= last) return false;
        KIT_LOOKED.put(f.getUUID(), f.tickCount);
        BlockPos heart = f.villageCentre();
        int r = Math.max(48, Villages.storesRadius(v.id()));
        List<String> got = new ArrayList<>();
        switch (t) {
            case MINE -> {
                if (f.countCarried(WorkTools::prop) < PROPS_LOW) {
                    int n = f.drawFrom(heart, WorkTools::prop, PROPS_KIT - f.countCarried(WorkTools::prop), r);
                    if (n > 0) got.add(n + " pit props");
                }
                if (f.countCarried(WorkTools::rope) == 0 && f.drawFrom(heart, WorkTools::rope, 1, r) > 0) got.add("a rope coil");
                if (f.countCarried(WorkTools::sack) == 0 && f.drawFrom(heart, WorkTools::sack, 1, r) > 0) got.add("an ore sack");
            }
            case CAVE -> {
                if (f.expedition() == null) {
                    int n = f.drawFrom(heart, WorkTools::rope, 2 - f.countCarried(WorkTools::rope), r);
                    if (n > 0) got.add(n == 1 ? "a rope coil" : n + " rope coils");
                    if (f.countCarried(WorkTools::sack) == 0 && f.drawFrom(heart, WorkTools::sack, 1, r) > 0) got.add("an ore sack");
                }
            }
            case WOOD -> {
                if (f.countCarried(WorkTools::saw) == 0 && f.drawFrom(heart, WorkTools::saw, 1, r) > 0) got.add("a felling saw");
            }
            case HAUL -> {
                int n = Crates.KIT - f.countCarried(WorkTools::crate);
                if (n > 0) {
                    n = f.drawFrom(heart, s -> crate(s) && ShippingCrateBlock.contents(s).isEmpty(), n, r);
                    if (n > 0) got.add(n == 1 ? "a shipping crate" : n + " shipping crates");
                }
            }
            default -> { }
        }
        if (got.isEmpty()) return false;
        f.brain("took " + String.join(", ", got) + " out of the stores");
        return true;
    }

    /** The cave team's ropes and sack out of the stores as it fits out for a trip (CaveDwellers.kitUp). What it took. */
    public static List<String> caveKitUp(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        List<String> got = new ArrayList<>();
        int ropes = 2 - f.countCarried(WorkTools::rope);
        for (int i = 0; i < ropes; i++) {
            ItemStack s = Crafts.takeOne(level, v, WorkTools::rope);
            if (s.isEmpty()) break;
            ItemStack left = f.insertGiven(s);
            if (!left.isEmpty()) { Crafts.store(level, v, left); break; }
            got.add("a rope coil");
        }
        if (f.countCarried(WorkTools::sack) == 0) {
            ItemStack s = Crafts.takeOne(level, v, WorkTools::sack);
            if (!s.isEmpty()) {
                ItemStack left = f.insertGiven(s);
                if (left.isEmpty()) got.add("an ore sack");
                else Crafts.store(level, v, left);
            }
        }
        return got;
    }

    // ------------------------------------------------------------------ pit props in the mine

    /**
     * A step of a miner's gallery taken (MineGoal): at the foot of its stairs, and every five steps after, it stands a
     * prop in the cell it has just left, if there is rock over it, no prop near, and a prop in its pack.
     */
    public static void propStep(AssistantEntity a, BlockPos cursor, Direction dir, int tunnelSteps) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level) || f.ownerId() == null) return;
        int since = SINCE_PROP.merge(f.getUUID(), 1, Integer::sum);
        if (tunnelSteps != 1 && since < PROP_EVERY) return;
        if (f.countCarried(WorkTools::prop) == 0) return;
        BlockPos at = cursor.relative(dir.getOpposite());
        if (!propFits(level, at)) return;
        if (f.removeMatching(WorkTools::prop, 1) != 1) return;
        PitPropBlock.stand(level, at, dir.getClockWise().getAxis(), WorkItems.PIT_PROP.get());
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 0.85F);
        f.swing(InteractionHand.MAIN_HAND);
        SINCE_PROP.put(f.getUUID(), 0);
        PROPPED_AT.remove(f.getUUID());
        Ledger.note(f.ownerId(), "work.props", String.valueOf(count(f.ownerId(), "work.props") + 1));
        f.brain("stood a pit prop in its gallery at " + at.toShortString());
    }

    /** Room for a prop here: the cell and the one over it open (no torch in the way), the floor firm, rock overhead, and
     *  no prop within three blocks. */
    static boolean propFits(ServerLevel level, BlockPos at) {
        BlockState lo = level.getBlockState(at), hi = level.getBlockState(at.above());
        if (!lo.isAir() || !hi.isAir()) return false;
        if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return false;
        BlockState roof = level.getBlockState(at.above(2));
        if (roof.isAir() || roof.canBeReplaced()) return false;
        for (BlockPos p : WorkSites.propsNear(level, at, PROP_SPACE)) if (Math.abs(p.getY() - at.getY()) <= 2) return false;
        return true;
    }

    /** A new run down the mine: the steps counted from its foot again. */
    public static void newRun(AssistantEntity a) {
        SINCE_PROP.remove(a.getUUID());
    }

    /**
     * How long a block takes this miner (MineGoal.beginDig): a sixth less in a face of the town's mine with two props or
     * more stood in it (it trusts the roof). Its face looked at every twenty seconds.
     */
    public static int propPace(AssistantEntity a, int ticks) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level) || f.ownerId() == null) return ticks;
        long now = level.getGameTime();
        long[] seen = PROPPED_AT.get(f.getUUID());
        if (seen == null || now - seen[0] > 400L || now < seen[0]) {
            seen = new long[]{ now, propped(level, f) ? 1L : 0L };
            PROPPED_AT.put(f.getUUID(), seen);
        }
        return seen[1] == 1L ? Math.max(1, (ticks * PROPPED_PACE + 99) / 100) : ticks;
    }

    /** Is this miner's face of the town's mine propped (two props or more stood in it)? */
    static boolean propped(ServerLevel level, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null) return false;
        BlockPos c = TownMine.faceCentre(f.ownerId(), z.center());
        if (c == null) c = z.center();
        int n = 0;
        for (BlockPos p : WorkSites.propsNear(level, c, TownMine.PITCH / 2)) {
            if (level.getBlockState(p).getBlock() instanceof PitPropBlock && ++n >= PROPPED) return true;
        }
        return false;
    }

    /** Tests: is this miner's face propped, looked at afresh? */
    public static boolean proppedForTests(ServerLevel level, VillageFolkEntity f) {
        PROPPED_AT.remove(f.getUUID());
        return propped(level, f);
    }

    /** The mine's report: its props, the falls held, the ropes down its shafts, and what the sacks saved. */
    public static List<String> mineReport(UUID village) {
        List<String> out = new ArrayList<>();
        int props = count(village, "work.props"), held = count(village, "work.held"), sacked = count(village, "work.sacked"),
            ropes = count(village, "work.shafts");
        if (props > 0 || held > 0) {
            StringBuilder sb = new StringBuilder("Pit props: " + props + " stood in the mine");
            if (props > 0) sb.append(" (a propped face is dug a sixth quicker: its miners trust the roof)");
            if (held > 0) sb.append("; ").append(held).append(held == 1 ? " fall of gravel held" : " falls of gravel and sand held");
            out.add(sb.append('.').toString());
        }
        if (ropes > 0) out.add("Ropes: " + ropes + (ropes == 1 ? " let down a shaft" : " let down shafts") + " in the mine.");
        if (sacked > 0) out.add("Ore sacks: " + sacked + (sacked == 1 ? " time" : " times") + " a full pack was tipped into a sack and the work went on.");
        return out;
    }

    static int count(UUID village, String key) {
        String s = Ledger.note(village, key);
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void bump(UUID village, String key) {
        Ledger.note(village, key, String.valueOf(count(village, key) + 1));
    }

    // ------------------------------------------------------------------ the ore sack

    /**
     * A full pack (MineGoal, CaveDwellers): its ore, coal and gems tipped into the ore sack it carries, as far as the sack
     * will take them. True if that freed a slot of the pack, so the work goes on and the walk home waits.
     */
    public static boolean stowOre(AssistantEntity a) {
        var pack = a.getInventoryItems();
        int free = 0;
        ItemStack sack = ItemStack.EMPTY;
        for (ItemStack s : pack) {
            if (s.isEmpty()) free++;
            else if (sack.isEmpty() && sack(s)) sack = s;
        }
        if (sack.isEmpty() || free >= 2) return free > 0;
        boolean freed = false;
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || !OreSackItem.mineral(s)) continue;
            ItemStack left = OreSackItem.add(sack, s);
            if (left.getCount() == s.getCount()) continue;
            if (left.isEmpty()) {
                pack.set(i, ItemStack.EMPTY);
                freed = true;
            } else {
                pack.set(i, left);
            }
        }
        if (freed && a instanceof VillageFolkEntity f && f.ownerId() != null) {
            bump(f.ownerId(), f.stationTask() == StationTask.CAVE ? "work.sacked.cave" : "work.sacked");
            f.brain("tipped its ore into its sack and worked on");
        }
        return freed;
    }

    /**
     * At a chest (DepositGoal): what its sacks and its crates hold goes in with the rest of its load, onto the stacks
     * there first (Stacking), booked as the pack's would be. What will not go stays in the sack or the crate. Returns how
     * many went in.
     */
    public static int unpackInto(AssistantEntity a, Container into, List<ItemStack> booked) {
        int moved = 0;
        for (ItemStack holder : a.getInventoryItems()) {
            boolean isSack = sack(holder), isCrate = crate(holder);
            if (!isSack && !isCrate) continue;
            List<ItemStack> held = isSack ? OreSackItem.contents(holder) : ShippingCrateBlock.contents(holder);
            if (held.isEmpty()) continue;
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack s : held) {
                ItemStack left = Stacking.insert(into, s.copy());
                int in = s.getCount() - left.getCount();
                if (in > 0) {
                    moved += in;
                    booked.add(s.copyWithCount(in));
                    a.noteProduced(s.getItem(), in);
                    if (a instanceof VillageFolkEntity vf) Economy.produced(vf, s.copyWithCount(in));
                }
                if (!left.isEmpty()) kept.add(left);
            }
            if (isSack) {
                OreSackItem.empty(holder);
                for (ItemStack k : kept) OreSackItem.add(holder, k);
            } else {
                ShippingCrateBlock.fill(holder, kept);
                if (isCrate && moved > 0 && a instanceof VillageFolkEntity vf && vf.ownerId() != null) Crates.unpacked(vf.ownerId(), held.size() - kept.size());
            }
        }
        if (moved > 0) into.setChanged();
        return moved;
    }

    /** Its sacks' load into the free slots of its pack (the cave team's haul goes in from the pack). How many came out. */
    public static int unpackSacks(VillageFolkEntity f) {
        int moved = 0;
        for (ItemStack holder : f.getInventoryItems()) {
            if (!sack(holder)) continue;
            List<ItemStack> held = OreSackItem.empty(holder);
            for (ItemStack s : held) {
                ItemStack left = f.insertItem(s);
                moved += s.getCount() - left.getCount();
                if (!left.isEmpty()) OreSackItem.add(holder, left);
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ the felling saw

    /**
     * A woodcutter taking a tree's bottom log (GatherGoal): with a felling saw in its pack, the rest of the tree comes
     * down with it, the logs dropping at the stump, the saw worn a cut a log. The logs felled (not counting the one it is
     * taking itself), nought without a saw or with no tree here.
     */
    public static int fellRest(AssistantEntity a, BlockPos stump) {
        if (!(a instanceof VillageFolkEntity f) || !(a.level() instanceof ServerLevel level)) return 0;
        ItemStack saw = ItemStack.EMPTY;
        if (saw(f.getMainHandItem())) saw = f.getMainHandItem();
        else for (ItemStack s : f.getInventoryItems()) if (saw(s)) { saw = s; break; }
        if (saw.isEmpty()) return 0;
        UUID village = f.ownerId();
        List<BlockPos> tree = tree(level, stump, village);
        if (tree.isEmpty()) return 0;
        // The saw in its hands for the felling.
        if (!saw(f.getMainHandItem())) {
            ItemStack inHand = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, saw.copy());
            saw.shrink(1);
            if (!inHand.isEmpty()) {
                ItemStack left = f.insertItem(inHand);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
            saw = f.getMainHandItem();
        }
        int n = fell(level, tree, stump, f, saw);
        if (n <= 0) return 0;
        saw.hurtAndBreak(n, f, EquipmentSlot.MAINHAND);
        int[] t = FELLED.computeIfAbsent(f.getUUID(), k -> new int[2]);
        t[0]++;
        t[1] += n + 1;
        if (village != null) bump(village, "work.trees");
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("felled a whole tree with its saw: " + (n + 1) + " logs");
        if (f.getRandom().nextInt(4) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Timber! The saw takes it in one.", "Down she comes — the whole tree.",
                "That's a tree's worth of logs in one go."));
        }
        return n;
    }

    /**
     * The logs of the tree standing on this one, this one aside, the nearest first and no more than sixty-four: or none,
     * if it is not a tree. A tree's logs are joined, corner to corner as well (a branch), and a crown of leaves is round
     * them; none of its logs is on the town's built ground, and none is beside anything a hand put there (a plank, a
     * stair, a pane, a fence, a lamp, a door): a house's frame or a player's log cabin is never felled.
     */
    public static List<BlockPos> tree(ServerLevel level, BlockPos base, @Nullable UUID village) {
        List<BlockPos> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        todo.add(base);
        seen.add(base.asLong());
        int leaves = 0;
        int[][] ground = null;
        if (village != null) {
            Villages.Village v = Villages.get(village);
            if (v != null) ground = TownMine.builtGround(village, v.centre(), level.getGameTime());
        }
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            if (ground != null && TownMine.underTheTown(ground, p, 0)) return List.of();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos q = p.offset(dx, dy, dz);
                        boolean face = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1;
                        // Every log's faces are looked at, whatever was seen before from a corner: a plank met corner
                        // to corner from one log is still a plank against the next (a cabin's post was felled so).
                        if (face) {
                            BlockState fs = level.getBlockState(q);
                            if (!fs.is(BlockTags.LOGS) && !(fs.getBlock() instanceof LeavesBlock) && !natural(fs)) return List.of();
                        }
                        if (!seen.add(q.asLong())) continue;
                        BlockState s = level.getBlockState(q);
                        if (s.is(BlockTags.LOGS)) {
                            if (q.getY() < base.getY()) continue;            // not down into the roots or the ground
                            if (out.size() < FellingSawItem.MOST_LOGS) {
                                out.add(q.immutable());
                                todo.add(q.immutable());
                            }
                        } else if (s.getBlock() instanceof LeavesBlock) {
                            leaves++;
                        } else if (face && !natural(s)) {
                            return List.of();                               // a hand put this here: a build, not a tree
                        }
                    }
                }
            }
        }
        if (leaves < 4) return List.of();
        out.sort((x, y) -> Double.compare(x.distSqr(base), y.distSqr(base)));
        return out;
    }

    /** What stands round a tree in the world as it grew: earth, stone, plants, snow, vines, a bees' nest, water. */
    static boolean natural(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
            || s.is(Blocks.VINE) || s.is(Blocks.BEE_NEST) || s.is(Blocks.COCOA) || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK)
            || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MOSS_CARPET)
            || s.is(Blocks.GRAVEL) || s.is(Blocks.MUD) || s.is(Blocks.CLAY) || s.is(Blocks.WATER) || s.is(Blocks.BROWN_MUSHROOM)
            || s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.WEEPING_VINES) || s.is(Blocks.WEEPING_VINES_PLANT)
            || s.is(Blocks.TWISTING_VINES) || s.is(Blocks.TWISTING_VINES_PLANT) || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    /** These logs down, their drops at the stump. How many were felled. */
    static int fell(ServerLevel level, List<BlockPos> logs, BlockPos stump, @Nullable net.minecraft.world.entity.Entity by, ItemStack tool) {
        int n = 0;
        for (BlockPos p : logs) {
            BlockState s = level.getBlockState(p);
            if (!s.is(BlockTags.LOGS)) continue;
            for (ItemStack drop : Block.getDrops(s, level, p, level.getBlockEntity(p), by, tool)) Block.popResource(level, stump, drop);
            level.levelEvent(2001, p, Block.getId(s));
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            n++;
        }
        if (n > 0) level.playSound(null, stump, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0F, 0.7F);
        return n;
    }

    /** A woodcutter's whole trees felled with its saw: {trees, logs}. */
    public static int[] felled(VillageFolkEntity f) {
        int[] t = FELLED.get(f.getUUID());
        return t == null ? new int[2] : t.clone();
    }

    // ------------------------------------------------------------------ the folk's own tick

    /**
     * A tick of a folk on a rope (Ropes) or on its way to hang or water a window box (WindowBoxes): VillageFolkEntity.aiStep,
     * before its own day. True while it is at it. Nothing to look at for anybody else: two empty maps.
     */
    public static boolean hold(VillageFolkEntity f) {
        return Ropes.ride(f) || WindowBoxes.hold(f);
    }

    /** Is it on a rope or on a window box's errand (its own work waits: VillageFolkEntity.calledAway)? */
    public static boolean busy(VillageFolkEntity f) {
        return Ropes.riding(f) || WindowBoxes.onErrand(f);
    }

    // ------------------------------------------------------------------ the town's rounds, the card

    /** From the town's rounds (TownWork.tick, every quarter of a minute a town): the shop's book, the milestones, the
     *  window boxes, the thatch. */
    public static void rounds(ServerLevel level, Villages.Village v) {
        demandOnce();
        Milestones.rounds(level, v);
        WindowBoxes.rounds(level, v);
        Thatch.rounds(level, v);
    }

    /** The work items a folk has with it, and what it has done with them, for its card ("Tools"). Empty for none. */
    public static String cardLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        int props = f.countCarried(WorkTools::prop), ropes = f.countCarried(WorkTools::rope);
        if (props > 0) parts.add(props + (props == 1 ? " pit prop" : " pit props"));
        if (ropes > 0) parts.add(ropes == 1 ? "a rope coil" : ropes + " rope coils");
        for (ItemStack s : f.getInventoryItems()) {
            if (sack(s)) {
                int n = OreSackItem.count(s);
                parts.add(n == 0 ? "an ore sack, empty" : "an ore sack with " + n + " of ore and coal in it");
            }
        }
        ItemStack saw = saw(f.getMainHandItem()) ? f.getMainHandItem() : ItemStack.EMPTY;
        if (saw.isEmpty()) for (ItemStack s : f.getInventoryItems()) if (saw(s)) { saw = s; break; }
        if (!saw.isEmpty()) parts.add("a felling saw (" + (saw.getMaxDamage() - saw.getDamageValue()) + " cuts left)");
        String crates = Crates.cardWords(f);
        if (!crates.isEmpty()) parts.add(crates);
        int[] t = felled(f);
        if (t[0] > 0) parts.add("felled " + t[0] + (t[0] == 1 ? " whole tree" : " whole trees") + " with it (" + t[1] + " logs)");
        String rope = Ropes.cardWords(f);
        if (!rope.isEmpty()) parts.add(rope);
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ /village items work

    /** /village items work: the town's work items, made, carried and in use; ops: "stage" sets them all out for the camera. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("items").then(Commands.literal("work")
            .executes(WorkTools::tell)
            .then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(WorkTools::stage)));
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Villages.Village v = Villages.nearest(src.getLevel(), net.minecraft.core.BlockPos.containing(src.getPosition()));
        if (v == null) {
            src.sendFailure(Component.literal("No village here."));
            return 0;
        }
        for (String line : report(src.getLevel(), v)) src.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** The town's work items in a few lines: what it keeps, what it wants, and each in use. */
    public static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        out.add("Tools of the mine, the woods and the roads, in " + Villages.name(id) + ":");
        StringBuilder kept = new StringBuilder();
        for (ItemStack s : WorkItems.showcase()) {
            int n = Market.stock(level, id, x -> x.is(s.getItem()));
            if (n > 0) kept.append(kept.length() == 0 ? "" : ", ").append(n).append(' ').append(s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
        }
        out.add("In the stores: " + (kept.length() == 0 ? "none of them" : kept) + ".");
        Map<Item, Integer> want = wantedForTests(level, id);
        if (!want.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : want.entrySet()) w.add(e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
            out.add("Kept made: " + String.join(", ", w) + ".");
        }
        out.addAll(mineReport(id));
        int trees = count(id, "work.trees");
        if (trees > 0) out.add("Felling saws: " + trees + (trees == 1 ? " whole tree" : " whole trees") + " felled at a go.");
        out.addAll(Crates.report(id));
        out.addAll(Ropes.report(id));
        out.addAll(Thatch.report(level, v));
        out.addAll(Milestones.report(level, v));
        out.addAll(WindowBoxes.report(level, v));
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            String line = cardLine(f);
            if (!line.isEmpty()) out.add("  " + f.displayNameCap() + " (" + f.stationTask().title.toLowerCase(java.util.Locale.ROOT) + "): " + line);
        }
        return out;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        Villages.Village v = Villages.nearest(level, at);
        List<String> lines = WorkStage.stage(level, v, at);
        for (String l : lines) src.sendSuccess(() -> Component.literal(l), false);
        return lines.isEmpty() ? 0 : 1;
    }
}
