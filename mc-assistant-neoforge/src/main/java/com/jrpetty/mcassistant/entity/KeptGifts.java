package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gifts kept. [batchG] A folk given something precious by a player (a diamond, an emerald, gold, an
 * enchanted book, a music disc, a fine tool: anything worth five coins or more that is not eaten) does not
 * put it in a drawer. It is its own (Homes.keepsake: never banked in the stores, carried with it when it
 * moves house), and the most precious it has is kept on show at home: in an item frame on the wall by its
 * bed.
 * <ul>
 * <li>The frame is the folk's own: bought out of its purse at the town's price (one the stores have, or one
 *     made there and then of their sticks and a leather, by the game's recipe). With no frame to be had, or
 *     no coin, the gift waits in its chest.</li>
 * <li>It hangs it when it is at home of an evening, on a wall the house's furnishing leaves free, the
 *     nearest its bed. A finer gift later takes its place, and the first goes back in its chest.</li>
 * <li>It mentions it: to the player who gave it ("I keep the diamond you gave me by my bed"), and to anybody
 *     who asks about its home. Its card has a <b>Keeps</b> line.</li>
 * <li>Moving house, it takes it down and hangs it again in the new one.</li>
 * </ul>
 */
public final class KeptGifts {

    private KeptGifts() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** In a gift's own data: who gave it, and the day. */
    private static final String MARK = "mca_gift_from";
    private static final String MARK_DAY = "mca_gift_day";
    /** The frame's tag, and (with the folk's id after it) whose it is. */
    static final String FRAME = "mca_kept_gift";
    /** What a frame costs: the town's price for one (Purchases.priceEach), in whole coins, a coin at least. */
    static int framePrice(ServerLevel level, UUID village, VillageFolkEntity f) {
        return Math.max(1, (int) Math.ceil(Purchases.priceEach(level, village, new ItemStack(Items.ITEM_FRAME), f) - 1e-6));
    }

    /** The day each folk last looked to hang a gift (it looks once an evening). */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        LOOKED.clear();
    }

    /** Precious enough to keep on show: worth five coins or more, or a gem, gold, a disc, an enchantment; never food. */
    static boolean precious(ItemStack s) {
        if (s.isEmpty() || s.get(DataComponents.FOOD) != null) return false;
        if (s.isEnchanted() || s.has(DataComponents.STORED_ENCHANTMENTS) || s.get(DataComponents.JUKEBOX_PLAYABLE) != null) return true;
        if (s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.AMETHYST_SHARD) || s.is(Items.GOLD_INGOT)
                || s.is(Items.GOAT_HORN) || s.is(Items.NETHER_STAR) || s.is(Items.HEART_OF_THE_SEA) || s.is(Items.NAUTILUS_SHELL)) return true;
        return Prices.of(s.copyWithCount(1)) >= 5.0;
    }

    /** How precious: its worth by the price list, an enchantment or a disc the more. */
    static double worth(ItemStack s) {
        return Prices.of(s.copyWithCount(1)) + (s.get(DataComponents.JUKEBOX_PLAYABLE) != null ? 20.0 : 0.0);
    }

    /**
     * A gift handed over by a player (FolkTalk.gift): if it is precious, it is marked with who gave it and
     * when, and is the folk's own from now on.
     */
    static void received(VillageFolkEntity f, ItemStack one, String from, long day) {
        if (!precious(one) || Visitors.is(f)) return;
        CompoundTag t = one.has(DataComponents.CUSTOM_DATA) ? one.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putString(MARK, from);
        t.putLong(MARK_DAY, day);
        one.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        Homes.keepsake(one, f);
    }

    static boolean isGift(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(MARK);
    }

    static String giver(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? "" : d.copyTag().getString(MARK);
    }

    static String name(ItemStack s) {
        return s.getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ on show

    /** What it has on show: the frame's place, and the gift (null if nothing). */
    record Shown(BlockPos at, ItemStack gift) {}

    @Nullable
    static Shown shown(ServerLevel level, VillageFolkEntity f) {
        String s = Ledger.note(f.ownerId(), "kept/" + f.getUUID());
        if (s == null || s.isEmpty()) return null;
        BlockPos at;
        try { at = BlockPos.of(Long.parseLong(s)); } catch (NumberFormatException e) { return null; }
        if (!level.isLoaded(at)) return new Shown(at, ItemStack.EMPTY);
        ItemFrame frame = frameAt(level, at, f.getUUID());
        if (frame == null || frame.getItem().isEmpty()) {
            Ledger.forget(f.ownerId(), "kept/" + f.getUUID());
            return null;
        }
        return new Shown(at, frame.getItem());
    }

    @Nullable
    private static ItemFrame frameAt(ServerLevel level, BlockPos at, UUID owner) {
        for (ItemFrame fr : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.1),
                e -> e.isAlive() && e.getTags().contains(FRAME + "_" + owner))) return fr;
        return null;
    }

    /** The town's look, every ten seconds (Visitors.tick): each household's folk at home of an evening, its finest gift hung. */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        long t = level.getDayTime() % 24000L;
        if (t < 11000L || t > 18000L) return;                    // of an evening
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            for (UUID m : List.copyOf(h.members)) {
                if (!(level.getEntity(m) instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby()) continue;
                Long looked = LOOKED.get(m);
                if (looked != null && looked == day) continue;
                if (f.isSleeping() || f.blockPosition().distSqr(h.anchor) > 8.0 * 8.0) continue;      // at home, and up
                LOOKED.put(m, day);
                String did = show(level, v, f, h);
                if (did != null) LOG.info("[MCA-GIFTS] {} of {}: {}", f.displayNameCap(), Villages.name(v.id()), did);
            }
        }
    }

    /** The finest gift it has (in its pack or its chest at home), and where: null if none. */
    @Nullable
    private static ItemStack finest(ServerLevel level, VillageFolkEntity f, @Nullable Container chest) {
        ItemStack best = null;
        for (ItemStack s : f.getInventoryItems()) {
            if (!s.isEmpty() && isGift(s) && Homes.ownedBy(s, f) && (best == null || worth(s) > worth(best))) best = s;
        }
        if (chest != null) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (!s.isEmpty() && isGift(s) && Homes.ownedBy(s, f) && (best == null || worth(s) > worth(best))) best = s;
            }
        }
        return best;
    }

    /**
     * Its finest gift hung on the wall by its bed, if it is finer than what hangs there: the frame bought,
     * the spot found, the old gift taken down. What it did, or null if nothing changed.
     */
    @Nullable
    static String show(ServerLevel level, Villages.Village v, VillageFolkEntity f, Homes.Home h) {
        UUID id = v.id();
        BlockPos chestAt = Homes.chestOf(level, id, h);
        Container chest = chestAt != null && level.getBlockEntity(chestAt) instanceof Container c ? c : null;
        Shown now = shown(level, f);
        // Moved house: the frame at the old one comes down, gift and frame, and goes up again here.
        if (now != null && level.isLoaded(now.at()) && now.at().distSqr(h.anchor) > 12.0 * 12.0) {
            takeDown(level, f, now.at());
            now = null;
        }
        ItemStack best = finest(level, f, chest);
        if (best == null) return null;
        if (now != null && !now.gift().isEmpty() && worth(now.gift()) >= worth(best)) return null;
        ItemFrame frame = now == null ? null : frameAt(level, now.at(), f.getUUID());
        if (frame == null) {
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b == null) return null;
            Decor.Spot spot = spot(level, id, h, b, f);
            if (spot == null) return "no wall free for its " + name(best) + "; it stays in its chest";
            frame = new ItemFrame(level, spot.at(), spot.facing());
            if (!frame.survives()) return null;
            String paid = frameFor(level, v, f);
            if (paid == null) return "no frame to be had for its " + name(best) + " (the stores have none, or it has not the coin)";
            frame.addTag(FRAME);
            frame.addTag(FRAME + "_" + f.getUUID());
            level.addFreshEntity(frame);
            frame.playPlacementSound();
            Ledger.note(id, "kept/" + f.getUUID(), Long.toString(spot.at().asLong()));
        } else if (!frame.getItem().isEmpty()) {
            // The finer gift takes the place of the old, which goes back to its pack (and its chest tonight).
            ItemStack old = frame.getItem().copy();
            ItemStack left = f.insertGiven(old);
            if (!left.isEmpty() && chest != null) Homes.insertInto(chest, left);
        }
        ItemStack one = best.split(1);
        if (chest != null) chest.setChanged();
        frame.setItem(one, false);
        level.playSound(null, frame.getPos(), net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — where I can see it from my bed.", "Up it goes. " + giver(one) + " would like that."));
        long day = level.getDayTime() / 24000L;
        f.persona().remember(day, "hung up the " + name(one) + " " + giver(one) + " gave me", 2);
        return "hung the " + name(one) + " " + giver(one) + " gave it on the wall by its bed";
    }

    /** A place on its home's wall for the frame: free of the furnishing and its luxuries, the nearest its bed. */
    @Nullable
    private static Decor.Spot spot(ServerLevel level, UUID village, Homes.Home h, Ledger.Building b, VillageFolkEntity f) {
        Decor.Room room = Decor.room(village, b);
        Set<BlockPos> taken = new HashSet<>(Decor.reserved(level, village, b));
        for (Long p : Decor.book(village, h.anchor).values()) taken.add(BlockPos.of(p));
        BlockPos near = f.bedPos() != null ? f.bedPos() : h.anchor;
        for (Decor.Spot s : Decor.wallSpots(level, room, near, 1, taken)) {
            if (new ItemFrame(level, s.at(), s.facing()).survives()) return s;
        }
        return null;
    }

    /**
     * A frame, the folk's own: one it has already (taken down from its last house), else one bought at the
     * town's price out of its purse, from the stores' stock or made there of four planks' sticks and a leather.
     * How it came by it, or null if it could not.
     */
    @Nullable
    static String frameFor(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.removeMatching(s -> s.is(Items.ITEM_FRAME), 1) == 1) return "its own frame";
        int price = framePrice(level, v.id(), f);
        if (f.purse() < price) return null;
        boolean got = Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1);
        if (!got) {
            if (Crafts.stock(level, v, s -> s.is(Items.LEATHER)) < 1) return null;
            if (!Crafts.usePlanks(level, v, 4)) return null;            // eight sticks
            if (!Crafts.take(level, v, s -> s.is(Items.LEATHER), 1)) {
                Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, 4));
                return null;
            }
        }
        f.spend(price);
        Ledger.addCoins(v.id(), price);
        Economy.spentInTown(v.id(), price);
        return got ? "a frame from the stores" : "a frame made of the stores' sticks and leather";
    }

    /** The frame at its old house taken down: the gift and the frame back in its pack, its own. */
    private static void takeDown(ServerLevel level, VillageFolkEntity f, BlockPos at) {
        ItemFrame frame = frameAt(level, at, f.getUUID());
        if (frame != null) {
            ItemStack gift = frame.getItem().copy();
            if (!gift.isEmpty()) {
                ItemStack left = f.insertGiven(gift);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            }
            ItemStack fr = new ItemStack(Items.ITEM_FRAME);
            Homes.keepsake(fr, f);
            ItemStack left = f.insertGiven(fr);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            frame.discard();
        }
        Ledger.forget(f.ownerId(), "kept/" + f.getUUID());
    }

    // ------------------------------------------------------------------ what it says

    /**
     * What it says of its gift (FolkTalk.answer): to the player who gave it, now and then when you stop to
     * talk; to anybody who asks about its home.
     */
    static String mention(VillageFolkEntity f, Player p, TalkTopic topic, String said) {
        if (topic != TalkTopic.OPEN && topic != TalkTopic.HOUSE) return said;
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null || said == null) return said;
        Shown s = shown(level, f);
        if (s == null || s.gift().isEmpty()) return said;
        String what = name(s.gift()), who = giver(s.gift());
        boolean yours = who.equals(p.getName().getString());
        if (topic == TalkTopic.OPEN) {
            if (!yours || f.getRandom().nextInt(3) != 0) return said;
            return said + " " + FolkTalk.pick(f.getRandom(), "I keep the " + what + " you gave me by my bed, you know.",
                "Your " + what + "'s on my wall at home. I look at it every night.");
        }
        return said + " " + (yours ? "And the " + what + " you gave me hangs by my bed." : "The " + what + " " + who + " gave me hangs by my bed.");
    }

    /** Its card: what it keeps on show, and who gave it. */
    static String cardLine(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null) return "";
        Shown s = shown(level, f);
        if (s != null && !s.gift().isEmpty()) return "the " + name(s.gift()) + " " + giver(s.gift()) + " gave it, on show by its bed";
        ItemStack best = finest(level, f, null);
        return best == null ? "" : "the " + name(best) + " " + giver(best) + " gave it, carried with it for now";
    }

    // ------------------------------------------------------------------ tests and the operators

    /** Tests: as if the player had handed the folk this gift (FolkTalk.gift's own path). */
    public static void receivedForTests(VillageFolkEntity f, ItemStack one, String from) {
        received(f, one, from, f.level().getDayTime() / 24000L);
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) f.spawnAtLocation(left);
    }

    /** Tests: the folk hangs its finest gift now, wherever it stands. What it did. */
    @Nullable
    public static String showForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = Villages.get(f.ownerId());
        Homes.Home h = v == null ? null : Homes.homeOf(v.id(), f.getUUID());
        return h == null ? "no home" : show(level, v, f, h);
    }

    /** Tests: the gift on show and where, as words; null if none. */
    @Nullable
    public static String shownForTests(ServerLevel level, VillageFolkEntity f) {
        Shown s = shown(level, f);
        return s == null || s.gift().isEmpty() ? null : name(s.gift()) + " at " + s.at().toShortString();
    }

    /** Tests: what it says to a player about its gift on the given topic. */
    public static String mentionForTests(VillageFolkEntity f, Player p, boolean house) {
        return mention(f, p, house ? TalkTopic.HOUSE : TalkTopic.OPEN, "");
    }

    /** /village visitors gifts: every folk with a gift hangs it now. */
    static String showAllForTests(ServerLevel level, Villages.Village v) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            for (UUID m : List.copyOf(h.members)) {
                if (!(level.getEntity(m) instanceof VillageFolkEntity f)) continue;
                String did = show(level, v, f, h);
                if (did == null) continue;
                n++;
                Shown s = shown(level, f);
                sb.append(" | ").append(f.displayNameCap()).append(": ").append(did)
                    .append(s == null ? "" : " FRAME " + s.at().getX() + " " + s.at().getY() + " " + s.at().getZ());
            }
        }
        return n + " hung" + sb;
    }
}
