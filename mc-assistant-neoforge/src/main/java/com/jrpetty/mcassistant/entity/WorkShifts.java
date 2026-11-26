package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Shifts.Shift;
import com.jrpetty.mcassistant.entity.Shifts.Task;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpb] The three shifts the folk trades had and a player could not work: the miner, the fisher and the workshop hand.
 * <ul>
 * <li><b>Miner (MINE).</b> A face of the town's mine: the deepest landing it has cut, and six solid blocks off its edge in
 *     the mine's own ground, marked. The player cuts them with their own pick; ore in them counts three, stone one. The
 *     arrow takes them to the mine head first and down the stair (or the cage, once the town has one) to the face. A
 *     town with no mine has its quarry or the stone of the mine's site. What is cut goes to the town's stores on a
 *     salary; on commission it is handed to the worker marked with where it came from (Provenance) for the hand-in.</li>
 * <li><b>Fisher (FISH).</b> The town's water (where its fishers fish, else the nearest open water), marked. Three fish
 *     caught within sixteen blocks of it are a task. A rod is lent for the shift if the player has none, and taken back.
 *     Junk and treasure do not count.</li>
 * <li><b>Workshop hand (CRAFT).</b> A batch of the trade's own goods made at any crafting table from makings out of the
 *     town's stores (a deposit held), brought to the marked chest: iron bars for the smith, carpets for the tailor, arrows
 *     for the fletcher, books for the enchanter and the teacher, paper for the cartographer, rockets for the fireworks
 *     maker, bottles for the brewer, candles for the beekeeper. A town short of the makings has no such task, and the
 *     boss does not count the want of it against the worker.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WorkShifts {

    private WorkShifts() {}

    /** Cells of a mine face a task, the ore's weight, how far round the mark a catch counts, fish a task. */
    public static final int FACE_CELLS = 6, ORE_UNITS = 3, FISH_REACH = 16, FISH_A_TASK = 3;
    static final long PER_BLOCK = 70L, PER_FISH = 420L, PER_PIECE = 60L, HANDLING = 100L;

    // ------------------------------------------------------------------ recipes

    /** A workshop's batch: its makings (item and how many) and what it makes (and how many). */
    public record Recipe(String trade, Item in, int inN, @Nullable Item in2, int in2N, Item out, int outN, String words) {}

    public static final List<Recipe> RECIPES = List.of(
        new Recipe("SMITH", Items.IRON_INGOT, 6, null, 0, Items.IRON_BARS, 16, "iron bars"),
        new Recipe("TAILOR", Items.WHITE_WOOL, 6, null, 0, Items.WHITE_CARPET, 9, "carpets"),
        new Recipe("FLETCHER", Items.FLINT, 4, Items.STICK, 4, Items.ARROW, 16, "arrows (add your own feathers if the stores have none: four)"),
        new Recipe("ENCHANT", Items.PAPER, 9, Items.LEATHER, 3, Items.BOOK, 3, "books"),
        new Recipe("TEACHER", Items.PAPER, 6, Items.LEATHER, 2, Items.BOOK, 2, "books for the school"),
        new Recipe("CARTOGRAPHER", Items.SUGAR_CANE, 9, null, 0, Items.PAPER, 9, "paper"),
        new Recipe("FIREWORKS", Items.PAPER, 3, Items.GUNPOWDER, 3, Items.FIREWORK_ROCKET, 9, "rockets"),
        new Recipe("BREW", Items.GLASS, 6, null, 0, Items.GLASS_BOTTLE, 6, "glass bottles"),
        new Recipe("BEEKEEP", Items.HONEYCOMB, 4, Items.STRING, 4, Items.CANDLE, 4, "candles"),
        new Recipe("", Items.OAK_PLANKS, 8, null, 0, Items.STICK, 16, "sticks"));

    /** The recipe for this trade, else the first whose makings the stores have. */
    @Nullable
    static Recipe recipe(ServerLevel level, UUID village, String trade) {
        for (Recipe r : RECIPES) if (!trade.isEmpty() && r.trade().equals(trade) && stocked(level, village, r)) return r;
        for (Recipe r : RECIPES) if (r.trade().equals(trade) && !trade.isEmpty()) return null;   // its own trade's makings are short
        for (Recipe r : RECIPES) if (stocked(level, village, r)) return r;
        return null;
    }

    static boolean stocked(ServerLevel level, UUID village, Recipe r) {
        if (Market.stock(level, village, x -> x.is(r.in()) && !Shifts.isShiftItem(x)) < r.inN()) return false;
        return r.in2() == null || Market.stock(level, village, x -> x.is(r.in2()) && !Shifts.isShiftItem(x)) >= r.in2N();
    }

    // ------------------------------------------------------------------ where

    private static final Map<UUID, Object[]> WATER = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> FACE_FOR_TESTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WATER.clear();
        FACE_FOR_TESTS.clear();
        LENT.clear();
    }

    /** Tests: the mine's face is here (a test town has no mine). */
    public static void faceForTests(UUID village, BlockPos at) {
        FACE_FOR_TESTS.put(village, at.immutable());
    }

    /** The town's fishing water: where its fishers work, else the nearest open water to the heart (cached two minutes). */
    @Nullable
    static BlockPos water(ServerLevel level, Villages.Village v) {
        Object[] c = WATER.get(v.id());
        long now = level.getGameTime();
        if (c != null && now - (long) c[0] < 2400 && now >= (long) c[0]) return (BlockPos) c[1];
        BlockPos found = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() != StationTask.FISH || a.workZone() == null) continue;
            found = Droughts.water(level, a.workZone().center(), 8);
            if (found != null) break;
        }
        if (found == null) found = Droughts.water(level, v.centre(), 40);
        WATER.put(v.id(), new Object[]{ now, found });
        return found;
    }

    /** The mine's face for a player: the deepest cut landing's edge, else the quarry or the mine's site; null if none. */
    @Nullable
    static Face face(ServerLevel level, Villages.Village v) {
        BlockPos test = FACE_FOR_TESTS.get(v.id());
        if (test != null) return new Face(test, null, "the test face", 0, "");
        MineWorks.Works w = MineWorks.works(level, v.id());
        if (w != null && w.sunk > 0) {
            List<MineWorks.Lvl> plan = MineWorks.plan(level, w);
            for (int i = plan.size() - 1; i >= 0; i--) {
                MineWorks.Lvl l = plan.get(i);
                BlockPos mid = MineWorks.roomMiddle(w, l);
                if (!level.isLoaded(mid) || !level.getBlockState(mid).isAir()) continue;
                return new Face(mid, MineWorks.approach(w), "the " + l.name() + " level", i + 1, MineLift.has(v.id()) ? "the cage" : "the stair");
            }
        }
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (b.structure().contains("quarry")) return new Face(b.anchor(), null, "the quarry", 0, "");
        }
        BlockPos site = TownMine.siteOf(v.id());
        return site == null ? null : new Face(site, null, "the mine's ground", 0, "");
    }

    /** A face: where, the way in from the surface (null on the surface), its name, its level, and how you go down. */
    record Face(BlockPos at, @Nullable BlockPos head, String name, int level, String via) {}

    static double mineReach(ServerLevel level, Shift s) {
        Villages.Village v = Villages.get(s.village);
        Face f = v == null ? null : face(level, v);
        return f == null ? 0 : Math.sqrt(f.at().distSqr(v.centre())) + 32;
    }

    @Nullable
    static ShiftTasks.Post post(ServerLevel level, Villages.Village v, ShiftRole r, BlockPos near) {
        switch (r) {
            case MINE -> {
                Face f = face(level, v);
                if (f == null) return null;
                return new ShiftTasks.Post(f.head() != null ? f.head() : f.at(), null, true, "");
            }
            case FISH -> {
                BlockPos w = water(level, v);
                if (w == null) return null;
                return new ShiftTasks.Post(w, null, true, "");
            }
            case CRAFT -> {
                BlockPos store = ShiftTasks.storehouse(level, v.id());
                if (store == null) return null;
                Recipe rc = recipe(level, v.id(), "");
                return new ShiftTasks.Post(store, null, rc != null, "The stores are short of every workshop's makings just now.");
            }
            default -> { return null; }
        }
    }

    static String idleWords(Shift s) {
        return switch (s.role) {
            case MINE -> "The face is cut back. Wait by the landing: the next is marked in a moment.";
            case FISH -> "Keep your line in at the marked water.";
            case CRAFT -> "The stores are short of the makings just now. The boss knows; wait by the storehouse.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ the next task

    @Nullable
    static Task task(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        return switch (s.role) {
            case MINE -> mineTask(level, p, s, v, now);
            case FISH -> fishTask(level, p, s, v, now);
            case CRAFT -> craftTask(level, p, s, v, now);
            default -> null;
        };
    }

    static long walk(BlockPos a, BlockPos b) {
        return (long) (Math.sqrt(a.distSqr(b)) / 3.5 * 20);
    }

    @Nullable
    private static Task mineTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        Face f = face(level, v);
        if (f == null) return null;
        List<BlockPos> cells = faceCells(level, v.id(), f.at());
        if (cells.isEmpty()) return null;
        String where = f.name() + (f.level() > 0 ? " (down " + f.via() + ")" : "");
        Task t = new Task("cut", "Cut the " + cells.size() + " marked blocks of the face at " + where + ". Ore counts three.", cells.get(0), now,
            walk(p.blockPosition(), f.at()) + PER_BLOCK * cells.size() + HANDLING);
        t.marks.addAll(cells);
        t.count = cells.size();
        t.to = f.at();
        return t;
    }

    /** Solid, cuttable blocks next to the open landing at the face: up to six, nearest first. */
    static List<BlockPos> faceCells(ServerLevel level, UUID village, BlockPos at) {
        List<BlockPos> out = new ArrayList<>();
        boolean test = FACE_FOR_TESTS.containsKey(village);
        for (int r = 1; r <= 6 && out.size() < FACE_CELLS; r++) {
            for (int dx = -r; dx <= r && out.size() < FACE_CELLS; dx++) {
                for (int dz = -r; dz <= r && out.size() < FACE_CELLS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 1 && out.size() < FACE_CELLS; dy++) {
                        BlockPos c = at.offset(dx, dy, dz);
                        if (!level.isLoaded(c)) continue;
                        BlockState st = level.getBlockState(c);
                        if (!cuttable(st) || !touchesAir(level, c)) continue;
                        if (!test && !MineWorks.mayCut(level, village, c, st) && !st.is(BlockTags.BASE_STONE_OVERWORLD)) continue;
                        out.add(c.immutable());
                    }
                }
            }
        }
        return out;
    }

    static boolean cuttable(BlockState st) {
        return !st.isAir() && !st.hasBlockEntity() && st.getFluidState().isEmpty()
            && (st.is(BlockTags.BASE_STONE_OVERWORLD) || ore(st) || st.is(Blocks.COBBLESTONE) || st.is(BlockTags.DIRT) || st.is(Blocks.GRAVEL)
            || st.is(Blocks.TUFF) || st.is(Blocks.CALCITE) || st.is(Blocks.SANDSTONE));
    }

    static boolean ore(BlockState st) {
        return st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
    }

    private static boolean touchesAir(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.values()) if (level.getBlockState(c.relative(d)).isAir()) return true;
        return false;
    }

    @Nullable
    private static Task fishTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        BlockPos w = water(level, v);
        if (w == null) return null;
        lendRod(p);
        Task t = new Task("caught", "Catch " + FISH_A_TASK + " fish at the marked water (within " + FISH_REACH + " blocks of it).", w, now,
            walk(p.blockPosition(), w) + PER_FISH * FISH_A_TASK);
        t.marks.add(w);
        t.count = FISH_A_TASK;
        return t;
    }

    @Nullable
    private static Task craftTask(ServerLevel level, ServerPlayer p, Shift s, Villages.Village v, long now) {
        Recipe r = recipe(level, v.id(), s.trade);
        BlockPos store = ShiftTasks.storehouse(level, v.id());
        if (r == null || store == null) return null;
        StationTask trade = AssistantEntity.StationTask.NONE;
        try { if (!r.trade().isEmpty() && !r.trade().equals("TEACHER")) trade = StationTask.valueOf(r.trade()); } catch (IllegalArgumentException ignored) { }
        BlockPos chest = trade == StationTask.NONE ? null : WorkStock.chestOf(level, v.id(), trade);
        BlockPos back = chest != null ? chest : store;
        String makings = Goods.words(new ItemStack(r.in()), r.inN()) + (r.in2() == null ? "" : " and " + Goods.words(new ItemStack(r.in2()), r.in2N()));
        Task t = new Task("made", "Collect " + makings + " at the storehouse (right-click it), make " + r.outN() + " " + r.words()
            + " at a crafting table, and bring them to the " + (chest != null ? "workshop chest." : "storehouse."), store, now,
            walk(p.blockPosition(), store) + PER_PIECE * r.outN() + walk(store, back) + 2 * HANDLING);
        t.item = r.out();
        t.count = r.outN();
        t.to = back;
        t.who = r.trade();
        return t;
    }

    // ------------------------------------------------------------------ the second

    static void watch(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        if (s.role == ShiftRole.MINE) {
            for (int i = t.marks.size() - 1; i >= 0; i--) {
                if (!cuttable(level.getBlockState(t.marks.get(i)))) {         // cut by somebody else meanwhile (or by us: onBreak)
                    t.marks.remove(i);
                }
            }
            if (t.marks.isEmpty()) {
                double units = Math.max(1, t.laid + t.leg * (ORE_UNITS - 1));
                if (t.laid > 0) Shifts.done(level, p, s, t, 0, units, "cutting " + t.laid + " blocks of the face" + (t.leg > 0 ? " (" + t.leg + " ore)" : ""), null);
                else s.task = null;
            }
        }
    }

    // ------------------------------------------------------------------ clicks

    static boolean use(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        if (s.role != ShiftRole.CRAFT) return false;
        if (t.leg == 0) return load(level, p, s, t, at);
        return t.to != null && Math.abs(at.getX() - t.to.getX()) <= 1 && Math.abs(at.getZ() - t.to.getZ()) <= 1
            && Math.abs(at.getY() - t.to.getY()) <= 2 && unload(level, p, s, t);
    }

    private static boolean load(ServerLevel level, ServerPlayer p, Shift s, Task t, BlockPos at) {
        if (t.target == null || !(at.closerThan(t.target, 4) || Villages.storeChests(level, s.village).contains(at))) return false;
        Villages.Village v = Villages.get(s.village);
        Recipe r = null;
        for (Recipe x : RECIPES) if (x.trade().equals(t.who) && x.out() == t.item) r = x;
        if (v == null || r == null) return false;
        Recipe rc = r;
        int deposit = Market.bidLot(level, s.village, new ItemStack(r.in()), r.inN()) / 2;
        if (Market.coinsHeld(p) < deposit) {
            p.sendSystemMessage(Component.literal("The storekeeper wants a deposit of " + deposit + " coin for the makings, back when the batch comes in.")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        if (!TownWork.take(level, v, x -> x.is(rc.in()) && !Shifts.isShiftItem(x), r.inN())
                || r.in2() != null && !TownWork.take(level, v, x -> x.is(rc.in2()) && !Shifts.isShiftItem(x), rc.in2N())) {
            s.task = null;
            p.sendSystemMessage(Component.literal("The stores ran short of the makings meanwhile. Not your fault: wait for the next batch.").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        if (deposit > 0) Market.payOut(p, deposit);
        t.deposit = deposit;
        give(p, new ItemStack(r.in(), r.inN()));
        if (r.in2() != null) give(p, new ItemStack(r.in2(), r.in2N()));
        t.leg = 1;
        t.target = t.to;
        t.text = "Make " + r.outN() + " " + r.words() + " at a crafting table, then put them in the marked chest (right-click it).";
        level.playSound(null, at, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, 1.1F);
        return true;
    }

    private static void give(ServerPlayer p, ItemStack st) {
        CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.putString(Shifts.MARK, "makings"));
        if (!p.getInventory().add(st)) p.drop(st, false);
    }

    private static boolean unload(ServerLevel level, ServerPlayer p, Shift s, Task t) {
        if (t.to == null || t.item == null || !(level.getBlockEntity(t.to) instanceof Container c)) return false;
        int moved = 0;
        for (int i = 0; i < p.getInventory().getContainerSize() && moved < t.count; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.is(t.item)) continue;
            int k = Math.min(st.getCount(), t.count - moved);
            ItemStack put = ShiftTasks.strip(st.copyWithCount(k));
            ItemStack left = Stacking.insert(c, put);
            if (!left.isEmpty()) Market.intoStores(level, s.village, left);
            st.shrink(k);
            moved += k;
        }
        if (moved <= 0) {
            p.displayClientMessage(Component.literal("You've none of the batch to put in yet: make it at a crafting table first.").withStyle(ChatFormatting.RED), true);
            return true;
        }
        int kept = returnMakings(level, p, s);
        if (moved < t.count) t.mistakes++;
        if (kept > 0) t.mistakes++;
        int back = (int) Math.round(t.deposit * moved / (double) t.count);
        if (back > 0) Earnings.giveCoins(p, back);
        if (t.deposit - back > 0) {
            Ledger.addCoins(s.village, t.deposit - back);
            CoinCensus.playerIn(s.village, t.deposit - back, p, PlayerFinance.Cat.LOSSES, "a shift's deposit kept");
        }
        level.playSound(null, t.to, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
        Shifts.done(level, p, s, t, 0, moved, "making " + moved + " " + Goods.name(new ItemStack(t.item)).toLowerCase(Locale.ROOT), null);
        return true;
    }

    /** Makings left in the pack after a batch go back to the stores. Returns how many. */
    static int returnMakings(ServerLevel level, ServerPlayer p, Shift s) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            CustomData d = st.get(DataComponents.CUSTOM_DATA);
            if (d == null || !"makings".equals(d.copyTag().getString(Shifts.MARK))) continue;
            n += st.getCount();
            Market.intoStores(level, s.village, ShiftTasks.strip(st.copy()));
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        return n;
    }

    // ------------------------------------------------------------------ the lent rod

    private static final Map<UUID, Boolean> LENT = new ConcurrentHashMap<>();

    static void lendRod(ServerPlayer p) {
        for (ItemStack st : p.getInventory().items) if (st.is(Items.FISHING_ROD)) return;
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        CustomData.update(DataComponents.CUSTOM_DATA, rod, tag -> tag.putString(Shifts.MARK, "rod"));
        if (p.getInventory().add(rod)) {
            LENT.put(p.getUUID(), true);
            p.displayClientMessage(Component.literal("A rod is lent you for the shift. It goes back at the end.").withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Anything ended: the lent rod back, makings back. True if this was ours (the caller does nothing more). */
    static boolean release(ServerLevel level, @Nullable ServerPlayer p, Shift s, Task t) {
        if (s.role != ShiftRole.MINE && s.role != ShiftRole.FISH && s.role != ShiftRole.CRAFT) return false;
        if (p != null && s.role == ShiftRole.CRAFT && t.leg == 1) {
            int back = returnMakings(level, p, s);
            if (back > 0) p.sendSystemMessage(Component.literal("The makings went back to the stores; the deposit is kept for the batch not made.").withStyle(ChatFormatting.YELLOW));
            if (t.deposit > 0) {
                Ledger.addCoins(s.village, t.deposit);
                CoinCensus.playerIn(s.village, t.deposit, p, PlayerFinance.Cat.LOSSES, "a shift's deposit kept");
            }
        }
        return true;
    }

    /** The shift over, however it ended: the lent rod taken back. */
    static void shiftOver(ServerPlayer p, Shift s) {
        if (s.role != ShiftRole.FISH || LENT.remove(p.getUUID()) == null) return;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            CustomData d = st.get(DataComponents.CUSTOM_DATA);
            if (st.is(Items.FISHING_ROD) && d != null && "rod".equals(d.copyTag().getString(Shifts.MARK))) p.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ the face and the water

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getLevel() instanceof ServerLevel) || !(e.getPlayer() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.MINE || s.task == null) return;
        Task t = s.task;
        int i = t.marks.indexOf(e.getPos());
        if (i < 0) return;
        t.marks.remove(i);
        t.laid++;
        if (ore(e.getState())) t.leg++;
    }

    /** The face's yield: into the stores on a salary; to the worker, marked as the face's, on commission. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getBreaker() instanceof ServerPlayer p)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.MINE || s.task == null || s.task.to == null) return;
        if (!e.getPos().closerThan(s.task.to, 9)) return;
        boolean commission = WorkLife.commissioned(p);
        for (ItemEntity ie : e.getDrops()) {
            ItemStack st = ie.getItem();
            if (commission) Provenance.tag(st, Provenance.MINE, WorkLife.employerKey(p), p, Villages.name(s.village) + "'s mine face");
            else Market.intoStores(e.getLevel(), s.village, st.copy());
        }
        if (!commission) e.getDrops().clear();
    }

    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        if (Shifts.ON.isEmpty() || !(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        Shift s = Shifts.of(p);
        if (s == null || s.role != ShiftRole.FISH || s.task == null || s.task.target == null) return;
        if (!e.getHookEntity().blockPosition().closerThan(s.task.target, FISH_REACH)) {
            p.displayClientMessage(Component.literal("That was caught away from the marked water: it doesn't count.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        int fish = 0;
        boolean commission = WorkLife.commissioned(p);
        for (ItemStack st : e.getDrops()) {
            if (!st.is(net.minecraft.tags.ItemTags.FISHES)) continue;
            fish += st.getCount();
            if (commission) Provenance.tag(st, Provenance.FISH, WorkLife.employerKey(p), p, Villages.name(s.village) + "'s water");
        }
        if (fish <= 0) {
            p.displayClientMessage(Component.literal("Not a fish: it doesn't count.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!commission) {
            for (ItemStack st : e.getDrops()) if (st.is(net.minecraft.tags.ItemTags.FISHES)) Market.intoStores(level, s.village, st.copy());
            e.setCanceled(true);
            e.damageRodBy(1);
        }
        catchFor(level, p, s, fish);
    }

    /** Fish caught on a shift (the event, or a test). */
    static void catchFor(ServerLevel level, ServerPlayer p, Shift s, int fish) {
        Task t = s.task;
        if (t == null) return;
        t.laid += fish;
        p.displayClientMessage(Component.literal("Caught: " + Math.min(t.laid, t.count) + " of " + t.count + (WorkLife.commissioned(p) ? " (yours to hand in)" : " (to the stores)"))
            .withStyle(ChatFormatting.AQUA), true);
        if (t.laid >= t.count) Shifts.done(level, p, s, t, 0, t.laid, "catching " + t.laid + " fish", null);
    }

    /** Tests: a catch of so many fish at the marked water. */
    public static void catchForTests(ServerLevel level, ServerPlayer p, int fish) {
        Shift s = Shifts.of(p);
        if (s != null && s.role == ShiftRole.FISH) catchFor(level, p, s, fish);
    }

    /** Tests: the face's cells broken as a pick would (the drops' way included). */
    public static int cutFaceForTests(ServerLevel level, ServerPlayer p) {
        Shift s = Shifts.of(p);
        if (s == null || s.task == null || s.role != ShiftRole.MINE) return 0;
        int n = 0;
        for (BlockPos c : List.copyOf(s.task.marks)) {
            BlockState st = level.getBlockState(c);
            boolean wasOre = ore(st);
            if (wasOre) s.task.leg++;
            s.task.marks.remove(c);
            s.task.laid++;
            for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(st, level, c, null)) {
                if (WorkLife.commissioned(p)) {
                    Provenance.tag(drop, Provenance.MINE, WorkLife.employerKey(p), p, "the test face");
                    p.getInventory().add(drop);
                } else Market.intoStores(level, s.village, drop);
            }
            level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
            n++;
        }
        watch(level, p, s, s.task);
        return n;
    }
}
