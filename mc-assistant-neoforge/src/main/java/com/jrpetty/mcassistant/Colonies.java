package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A village that has grown sends people out to found another.
 *
 * <p>Ages end; a village does not have to. Once a settlement is out of the Wood Age and
 * has forty people (villageColonyAt) it sends a founding party — as many as a village
 * the world grows starts with — a couple of hundred blocks out, with the founding
 * stores and food from its own larder, and they found a village of their own that
 * climbs the ages from the beginning. Every two game days at most from any one
 * village, and never past the number of folk the whole world may hold
 * (villageWorldCap), so a long game sees settlements spread across the map without
 * the map ever being more than the machine can run.
 *
 * <p>The ground for the new village is asked for the way the game asks for ground —
 * a short-lived ticket, generated on the world's own threads — and only looked at
 * once it is there, from a later tick: nothing is ever generated on this thread.
 */
public final class Colonies {

    private Colonies() {}

    /** Two game days between founding parties from any one village. */
    public static final long INTERVAL = 48000L;
    /** How far out a colony goes: far enough to be a village of its own. */
    public static final int DISTANCE = 200;
    /** Food each colonist is sent out with, from the mother village's stores. */
    private static final int FOOD_EACH = 10;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> WAITED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TRIED = new ConcurrentHashMap<>();

    public static void reset() {
        LAST.clear();
        PENDING.clear();
        WAITED.clear();
        TRIED.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 200 != 37) return;
        Guard.run("colonies", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) tick(level);
        });
    }

    /** How many founders a colony is sent out with. */
    public static int party() {
        return Math.max(2, AssistantConfig.villageMinFolk());
    }

    static void tick(ServerLevel level) {
        if (!AssistantConfig.villageColonies()) return;
        long now = level.getGameTime();
        int world = 0;
        for (Villages.Village v : Villages.every()) world += Villages.headcount(v.id());
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            UUID id = v.id();
            BlockPos pending = PENDING.get(id);
            if (pending != null) {
                if (settle(level, v, pending, now)) world += party();
                continue;
            }
            // A village first seen now (a new one, or the world just loaded) waits a whole
            // interval before its first party, so a restart is not a reason to send one.
            long last = LAST.computeIfAbsent(id, k -> now);
            if (now - last < INTERVAL) continue;
            if (!ready(level, id, world)) continue;
            BlockPos target = spot(level, v);
            if (target == null) {
                LAST.put(id, now - INTERVAL + 6000L);            // look again in five minutes
                continue;
            }
            PENDING.put(id, target);
            WAITED.put(id, 0);
            ask(level, target);
        }
    }

    /** Is this village able to spare a founding party right now? */
    static boolean ready(ServerLevel level, UUID id, int world) {
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return false;
        if (Villages.headcount(id) < AssistantConfig.villageColonyAt()) return false;
        if (world + party() > AssistantConfig.villageWorldCap()) return false;
        // Out of what is put by, over and above a day's meals for the people staying.
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        int food = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        return food >= Villages.larderForBirth(id) + party() * FOOD_EACH;
    }

    /** Somewhere a couple of hundred blocks out with no village near it yet, or null. */
    static BlockPos spot(ServerLevel level, Villages.Village v) {
        int tried = TRIED.getOrDefault(v.id(), 0);
        for (int i = 0; i < 8; i++) {
            double angle = (tried + i) * 2.399963229728653 + (v.id().getLeastSignificantBits() & 7);
            int x = v.centre().getX() + (int) Math.round(Math.cos(angle) * DISTANCE);
            int z = v.centre().getZ() + (int) Math.round(Math.sin(angle) * DISTANCE);
            BlockPos at = new BlockPos(x, v.centre().getY(), z);
            if (Villages.nearest(level, at, Villages.VILLAGE_RANGE * 2) != null) continue;
            // [emerald] Nor on (or beside) a village of the game's own villagers: theirs is theirs.
            if (com.jrpetty.mcassistant.entity.VanillaVillages.inTheWayOfFounding(level, at) != null) continue;
            TRIED.put(v.id(), tried + i + 1);
            return at;
        }
        TRIED.put(v.id(), tried + 8);
        return null;
    }

    /** Ask for the ground there the way the game does: generated off this thread. */
    private static void ask(ServerLevel level, BlockPos at) {
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(at), 2, at);
    }

    /** The ground has (or has not yet) arrived: found the colony, or keep waiting. */
    private static boolean settle(ServerLevel level, Villages.Village mother, BlockPos at, long now) {
        UUID id = mother.id();
        if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)
                || !level.hasChunk((at.getX() + 16) >> 4, (at.getZ() + 16) >> 4)
                || !level.hasChunk((at.getX() - 16) >> 4, (at.getZ() - 16) >> 4)) {
            int waited = WAITED.merge(id, 1, Integer::sum);
            if (waited > 30) {                                   // ten minutes: try elsewhere later
                PENDING.remove(id);
                LAST.put(id, now - INTERVAL + 6000L);
            } else {
                ask(level, at);                                  // the ticket lasts fifteen seconds
            }
            return false;
        }
        PENDING.remove(id);
        BlockPos ground = VillageSpawner.groundAt(level, at.getX(), at.getZ());
        BlockPos flat = ground == null ? null : com.jrpetty.mcassistant.entity.Land.flattest(level, ground, 24);
        if (flat != null) ground = flat;
        if (ground == null || !VillageSpawner.liveable(level, ground)
                || Villages.nearest(level, ground, Villages.VILLAGE_RANGE * 2) != null
                || com.jrpetty.mcassistant.entity.VanillaVillages.inTheWayOfFounding(level, ground) != null) {   // [emerald]
            LAST.put(id, now - INTERVAL + 1200L);                // somewhere else, in a minute
            return false;
        }
        return found(level, mother, ground, now);
    }

    /** Send the party: the mother village pays the food, and a new village stands up. */
    public static boolean found(ServerLevel level, Villages.Village mother, BlockPos ground, long now) {
        UUID id = mother.id();
        int food = party() * FOOD_EACH;
        boolean founding = Villages.nearest(level, ground, Villages.VILLAGE_RANGE * 2) == null;
        // Not before she can send them properly: the storehouse's timber and the stone for their
        // tools. A party sent out with what she happened to have loose (no planks, no tools, no
        // bread) sat at its camp for good: three colonies of a hundred-day town, built [] apiece.
        if (founding && !canOutfit(level, mother)) {
            LAST.put(id, now - INTERVAL + 6000L);
            return false;
        }
        List<ItemStack> provisions = takeFood(level, mother, food);
        int took = 0;
        for (ItemStack st : provisions) took += st.getCount();
        if (took < food) {
            for (ItemStack st : provisions) intoStores(level, mother, st);   // back where it came from
            LAST.put(id, now - INTERVAL + 6000L);
            return false;
        }
        int stood = VillageFolkSpawnerBlock.raiseParty(level, ground, 0.0F, party());
        LAST.put(id, now);
        if (stood == 0) {
            for (ItemStack st : provisions) intoStores(level, mother, st);
            return false;
        }
        // The new village's founding stores and the beds of its camp are the mother's to give, not
        // something from nothing: what she could spare goes, and only that.
        if (founding) outfit(level, mother, ground);
        if (founding) packs(level, mother, ground);
        provision(level, ground, provisions);
        Villages.noteColony(id);
        long day = level.getDayTime() / 24000L;
        Villages.Village colony = Villages.nearest(level, ground, 40);
        String colonyName = colony == null || colony.id().equals(id) ? "a new village" : Villages.name(colony.id());
        Villages.tell(id, day, "settlers left to found " + colonyName);
        if (colony != null && !colony.id().equals(id)) {
            Villages.tell(colony.id(), day, "settlers from " + Villages.name(id) + " founded " + colonyName);
            // Mother and daughter: a road between them, and caravans along it (Roads, Caravans).
            com.jrpetty.mcassistant.village.Ledger.link(id, colony.id());
        }
        // Like coming of age, a new village is a thing worth being told about.
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(
            Villages.name(id) + " (" + mother.centre().getX() + ", " + mother.centre().getZ()
                + ") has founded " + colonyName + " at " + ground.getX() + ", " + ground.getZ() + ".")
            .withStyle(net.minecraft.ChatFormatting.GOLD);
        for (net.minecraft.server.level.ServerPlayer p : level.players()) p.sendSystemMessage(line);
        com.mojang.logging.LogUtils.getLogger().info("[MCA-COLONY] {} folk from {} founded a village at {}",
            stood, mother.centre(), ground);
        return true;
    }

    /**
     * Square a new colony's start with its mother's stores. Its founding chest (left where the
     * first settler stood) is emptied, and each thing that was in it is taken out of the mother's
     * stores instead, as much of it as she has (any planks for planks, any sapling for saplings),
     * and those go in. Then its camp: a bed for each that the mother can give (a bed put by, or
     * three wool and three planks), and the rest of the camp's beds are struck.
     */
    private static void outfit(ServerLevel level, Villages.Village mother, BlockPos at) {
        if (level.getBlockEntity(at) instanceof net.minecraft.world.Container chest) {
            List<ItemStack> sent = new ArrayList<>();
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack want = chest.getItem(i);
                if (want.isEmpty()) continue;
                chest.setItem(i, ItemStack.EMPTY);
                for (ItemStack got : supply(level, mother, want)) merge(sent, got);
            }
            int slot = 0;
            for (ItemStack st : sent) {
                while (slot < chest.getContainerSize() && !chest.getItem(slot).isEmpty()) slot++;
                if (slot < chest.getContainerSize()) chest.setItem(slot++, st);
                else net.minecraft.world.level.block.Block.popResource(level, at.above(), st);
            }
            chest.setChanged();
        }
        // The camp's beds round the stores.
        List<BlockPos> feet = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 2, 4))) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                    && st.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
                feet.add(p.immutable());
            }
        }
        feet.sort(java.util.Comparator.comparingDouble((BlockPos p) -> p.distSqr(at)).thenComparingLong(BlockPos::asLong));
        int paid = beds(level, mother, feet.size());
        for (int i = paid; i < feet.size(); i++) {
            BlockPos foot = feet.get(i);
            BlockState st = level.getBlockState(foot);
            if (!(st.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
            BlockPos head = foot.relative(st.getValue(net.minecraft.world.level.block.BedBlock.FACING));
            level.removeBlock(head, false);
            level.removeBlock(foot, false);
        }
    }

    /**
     * The settlers' packs squared with the mother's stores, as the founding chest is: each new
     * settler's tools, food and seed taken out of her stores in place of a kit out of nowhere, as
     * much as she has; what she has not, it goes without (and makes for itself).
     */
    private static void packs(ServerLevel level, Villages.Village mother, BlockPos at) {
        Villages.Village colony = Villages.nearest(level, at, 40);
        if (colony == null || colony.id().equals(mother.id())) return;
        List<com.jrpetty.mcassistant.entity.AssistantEntity> folk = Villages.folkOf(colony.id());
        List<List<ItemStack>> wanted = new ArrayList<>();
        for (com.jrpetty.mcassistant.entity.AssistantEntity a : folk) {
            var inv = a.getInventoryItems();
            List<ItemStack> mine = new ArrayList<>();
            for (int i = 0; i < inv.size(); i++) {
                if (inv.get(i).isEmpty()) continue;
                mine.add(inv.get(i).copy());
                inv.set(i, ItemStack.EMPTY);
            }
            wanted.add(mine);
        }
        // Everybody's tools first, then the rest: the first settlers' chests and benches used up
        // the timber, and half the party set out without an axe or a pick.
        for (boolean tools : new boolean[] { true, false }) {
            for (int k = 0; k < folk.size(); k++) {
                com.jrpetty.mcassistant.entity.AssistantEntity a = folk.get(k);
                for (ItemStack want : wanted.get(k)) {
                    if (isTool(want) != tools) continue;
                    for (ItemStack got : supply(level, mother, want)) {
                        ItemStack left = a.insertGiven(got);
                        if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, a.blockPosition(), left);
                    }
                }
            }
        }
    }

    private static boolean isTool(ItemStack st) {
        return st.is(ItemTags.AXES) || st.is(ItemTags.PICKAXES) || st.is(ItemTags.SWORDS)
            || st.is(ItemTags.HOES) || st.is(ItemTags.SHOVELS);
    }

    /**
     * One thing of a founding kit out of the mother's stores: the like of it if she has it put by,
     * else made by her hands out of her own timber and stone (a storehouse unit is six planks, a
     * chest eight, a bench four, a log four planks; a stone pick or axe three cobble and a stick
     * or two). Bread is not asked for here: the settlers' food is their provisions. What she
     * cannot find or make, they go without.
     */
    private static List<ItemStack> supply(ServerLevel level, Villages.Village mother, ItemStack want) {
        List<ItemStack> out = new ArrayList<>();
        if (want.is(net.minecraft.world.item.Items.BREAD)) return out;
        int need = want.getCount();
        for (ItemStack got : take(level, mother, like(want), need, false)) { need -= got.getCount(); out.add(got); }
        int planks = planksFor(want), cobble = cobbleFor(want);
        if (need <= 0 || planks + cobble <= 0) return out;
        int can = need;
        if (planks > 0) can = Math.min(can, timber(level, mother) / planks);
        if (cobble > 0) can = Math.min(can, count(level, mother, s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)) / cobble);
        if (can <= 0) return out;
        List<ItemStack> sawn = planks > 0 ? takeTimber(level, mother, can * planks) : List.of();
        if (cobble > 0) take(level, mother, s -> s.is(net.minecraft.world.item.Items.COBBLESTONE), can * cobble, true);
        if (want.is(ItemTags.PLANKS)) { out.addAll(sawn); return out; }     // planks wanted are the planks
        int max = want.getMaxStackSize();
        for (int left = can; left > 0; left -= max) out.add(want.copyWithCount(Math.min(max, left)));
        int spare = -can * planks;                                            // a log's odd planks come too
        for (ItemStack st : sawn) spare += st.getCount();
        if (spare > 0) out.add(new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, spare));
        return out;
    }

    /** The planks it takes the mother to make one of these, or 0. */
    private static int planksFor(ItemStack want) {
        if (want.is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get())) return 6;
        if (want.is(net.minecraft.world.item.Items.CHEST)) return 8;
        if (want.is(net.minecraft.world.item.Items.CRAFTING_TABLE)) return 4;
        if (want.is(ItemTags.PLANKS)) return 1;
        if (want.is(net.minecraft.world.item.Items.STONE_AXE) || want.is(net.minecraft.world.item.Items.STONE_PICKAXE)
            || want.is(net.minecraft.world.item.Items.STONE_SWORD) || want.is(net.minecraft.world.item.Items.STONE_HOE)
            || want.is(net.minecraft.world.item.Items.STONE_SHOVEL)) return 1;     // the sticks
        return 0;
    }

    /** The cobblestone it takes to make one of these, or 0. */
    private static int cobbleFor(ItemStack want) {
        if (want.is(net.minecraft.world.item.Items.STONE_AXE) || want.is(net.minecraft.world.item.Items.STONE_PICKAXE)) return 3;
        if (want.is(net.minecraft.world.item.Items.STONE_SWORD) || want.is(net.minecraft.world.item.Items.STONE_HOE)) return 2;
        if (want.is(net.minecraft.world.item.Items.STONE_SHOVEL)) return 1;
        return 0;
    }

    /** The mother's timber in planks: her planks, and four for every log. */
    private static int timber(ServerLevel level, Villages.Village v) {
        return count(level, v, s -> s.is(ItemTags.PLANKS)) + 4 * count(level, v, s -> s.is(ItemTags.LOGS));
    }

    /** So many planks' worth out of her stores: planks first, then logs sawn four planks apiece
     *  (a log's odd planks come too). Returns the planks. */
    private static List<ItemStack> takeTimber(ServerLevel level, Villages.Village v, int planks) {
        List<ItemStack> out = new ArrayList<>();
        int got = 0;
        for (ItemStack st : take(level, v, s -> s.is(ItemTags.PLANKS), planks, false)) { got += st.getCount(); out.add(st); }
        int logs = (planks - got + 3) / 4;
        if (logs > 0) {
            int sawn = 0;
            for (ItemStack st : take(level, v, s -> s.is(ItemTags.LOGS), logs, false)) sawn += st.getCount() * 4;
            for (int left = sawn; left > 0; left -= 64) out.add(new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, Math.min(64, left)));
        }
        return out;
    }

    /** Can the mother send a party properly: the storehouse's timber and planks, and stone for tools? */
    static boolean canOutfit(ServerLevel level, Villages.Village mother) {
        int units = count(level, mother, s -> s.is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get()));
        int planksNeed = Math.max(0, 27 - units) * 6 + 48 + party() * 2;
        int cobbleNeed = 64 + party() * 6;
        return timber(level, mother) >= planksNeed
            && count(level, mother, s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)) >= cobbleNeed;
    }

    /** The settlers' provisions shared out among them, the rest into their stores. */
    private static void provision(ServerLevel level, BlockPos at, List<ItemStack> provisions) {
        Villages.Village colony = Villages.nearest(level, at, 40);
        List<com.jrpetty.mcassistant.entity.AssistantEntity> folk = colony == null ? List.of() : Villages.folkOf(colony.id());
        int k = 0;
        for (ItemStack st : provisions) {
            while (!st.isEmpty()) {
                ItemStack part = st.split(Math.max(1, Math.min(st.getCount(), FOOD_EACH)));
                if (folk.isEmpty()) {
                    if (colony != null) intoStores(level, colony, part);
                    continue;
                }
                ItemStack left = folk.get(k++ % folk.size()).insertGiven(part);
                if (!left.isEmpty() && colony != null) intoStores(level, colony, left);
            }
        }
    }

    /** Into a village's stores, or dropped at its heart if they are full. */
    private static void intoStores(ServerLevel level, Villages.Village v, ItemStack st) {
        if (st.isEmpty()) return;
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 64)) {
                if (st.isEmpty()) break;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                // Onto the part stacks of the same first, then empty slots (entity/Stacking).
                st.setCount(com.jrpetty.mcassistant.entity.Stacking.insert(f.container(), st).getCount());
            }
        } finally {
            ZoneChests.askAs(before);
        }
        if (!st.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, v.centre().above(), st);
    }

    /** What will do in place of this in a founding kit: any planks for planks, any sapling for a
     *  sapling, anything else itself. */
    private static Predicate<ItemStack> like(ItemStack want) {
        if (want.is(ItemTags.PLANKS)) return s -> s.is(ItemTags.PLANKS);
        if (want.is(ItemTags.SAPLINGS)) return s -> s.is(ItemTags.SAPLINGS);
        // Any axe for an axe: an exact stone one was what the mother never had loose.
        for (net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tool : List.of(ItemTags.AXES, ItemTags.PICKAXES,
                ItemTags.SWORDS, ItemTags.HOES, ItemTags.SHOVELS)) {
            if (want.is(tool)) return s -> s.is(tool);
        }
        net.minecraft.world.item.Item item = want.getItem();
        return s -> s.is(item);
    }

    private static void merge(List<ItemStack> into, ItemStack st) {
        for (ItemStack there : into) {
            if (st.isEmpty()) return;
            if (!ItemStack.isSameItemSameComponents(there, st) || there.getCount() >= there.getMaxStackSize()) continue;
            int move = Math.min(st.getCount(), there.getMaxStackSize() - there.getCount());
            there.grow(move);
            st.shrink(move);
        }
        if (!st.isEmpty()) into.add(st);
    }

    /** So many beds out of the village's stores, as many as it can give: a bed put by, or three
     *  wool and three planks (or a log) to make one. Returns how many. */
    private static int beds(ServerLevel level, Villages.Village v, int n) {
        int paid = 0;
        for (int i = 0; i < n; i++) {
            if (!take(level, v, s -> s.is(ItemTags.BEDS), 1, true).isEmpty()) { paid++; continue; }
            boolean planks = count(level, v, s -> s.is(ItemTags.PLANKS)) >= 3;
            if (count(level, v, s -> s.is(ItemTags.WOOL)) < 3 || (!planks && count(level, v, s -> s.is(ItemTags.LOGS)) < 1)) break;
            if (take(level, v, s -> s.is(ItemTags.WOOL), 3, true).isEmpty()) break;
            if (planks) take(level, v, s -> s.is(ItemTags.PLANKS), 3, true);
            else take(level, v, s -> s.is(ItemTags.LOGS), 1, true);
            paid++;
        }
        return paid;
    }

    /** How many of what matches the village's stores hold. */
    private static int count(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        int have = 0;
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 64)) {
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (!st.isEmpty() && what.test(st)) have += st.getCount();
                }
            }
        } finally {
            ZoneChests.askAs(before);
        }
        return have;
    }

    /** Take up to so many of what matches out of the village's stores (all or none, if {@code all}).
     *  Returns what was taken. */
    private static List<ItemStack> take(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, int want, boolean all) {
        List<ItemStack> got = new ArrayList<>();
        if (want <= 0 || (all && count(level, v, what) < want)) return got;
        int left = want;
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 64)) {
                if (left <= 0) break;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                    ItemStack st = c.getItem(i);
                    if (st.isEmpty() || !what.test(st)) continue;
                    int k = Math.min(left, st.getCount());
                    got.add(st.split(k));
                    if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                    left -= k;
                }
                c.setChanged();
            }
        } finally {
            ZoneChests.askAs(before);
        }
        return got;
    }

    /** Take this much food out of the village's stores. Returns what was taken (it goes with the
     *  settlers: it used to be taken and given to nobody). */
    private static List<ItemStack> takeFood(ServerLevel level, Villages.Village v, int want) {
        List<ItemStack> got = new ArrayList<>();
        int taken = 0;
        boolean before = ZoneChests.askAs(true);
        try {
            for (ZoneChests.Found f : ZoneChests.around(level, v.centre(), Villages.storesRadius(v.id()), 64)) {
                if (taken >= want) break;
                if (!f.stillThere() || !ZoneChests.isStashable(f)) continue;
                net.minecraft.world.Container c = f.container();
                for (int i = 0; i < c.getContainerSize() && taken < want; i++) {
                    net.minecraft.world.item.ItemStack st = c.getItem(i);
                    if (st.isEmpty() || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
                    int n = Math.min(st.getCount(), want - taken);
                    got.add(st.split(n));
                    if (st.isEmpty()) c.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    taken += n;
                }
                c.setChanged();
            }
        } finally {
            ZoneChests.askAs(before);
        }
        return got;
    }
}
