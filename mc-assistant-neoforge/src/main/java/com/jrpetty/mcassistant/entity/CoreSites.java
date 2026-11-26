package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.BusinessCoreBlock;
import com.jrpetty.mcassistant.block.BusinessCoreBlockEntity;
import com.jrpetty.mcassistant.item.CoreItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] Where every business's Core stands, and keeping it so.
 * <ul>
 * <li><b>One per site.</b> Every business has its Core: a firm (the player's or a folk's), a player's shop, a player's farm.
 *     A branch is its own business with its own Core; its owner's hub lists every one of them (the head office view).</li>
 * <li><b>Set by the business</b> at its premises when a player is about (in loaded ground): a free floor-level spot beside
 *     its premises, not in a doorway or on a road, and never within a few blocks of another Core. Its owner can pick it up
 *     and set it elsewhere at the premises (the move); a second for the same site is refused. While its owner holds it, the
 *     business doesn't set another.</li>
 * <li><b>Protected.</b> Anyone else breaking it does it slowly, gets no block for it, and commits a crime the town may see
 *     (Laws.offence); the business notes it and sets another the next morning.</li>
 * <li><b>Its life.</b> Sold: it is the new owner's (it is bound to the business, not the person). Before the court: it says
 *     so to anyone who looks. Shut (bankrupt or wound up): its sign says closed, and after three days it is taken away.</li>
 * <li><b>Its sign.</b> Over it floats the business's name, open or closed, and "Deputy in charge" when the deputy runs it;
 *     a passer-by the first time is told what it is.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class CoreSites extends SavedData {

    private static final String ID = "mc_assistant_wpb_cores";
    @Nullable private static CoreSites loose;

    /** A Core's place: its town, the business ("firm:id", "farm:id", "shop:key"), where, and whether its owner has it in hand. */
    static final class Site {
        UUID village = new UUID(0, 0);
        String key = "";
        long pos;
        boolean held;
        long brokenDay = -1, shutDay = -1;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("v", village);
            t.putString("k", key);
            t.putLong("p", pos);
            t.putBoolean("h", held);
            t.putLong("b", brokenDay);
            t.putLong("s", shutDay);
            return t;
        }

        static Site load(CompoundTag t) {
            Site s = new Site();
            s.village = t.getUUID("v");
            s.key = t.getString("k");
            s.pos = t.getLong("p");
            s.held = t.getBoolean("h");
            s.brokenDay = t.contains("b") ? t.getLong("b") : -1;
            s.shutDay = t.contains("s") ? t.getLong("s") : -1;
            return s;
        }

        BlockPos at() {
            return BlockPos.of(pos);
        }
    }

    final Map<String, Site> sites = new LinkedHashMap<>();

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
        net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
        for (Site s : sites.values()) l.add(s.save());
        t.put("sites", l);
        return t;
    }

    static CoreSites load(CompoundTag t, HolderLookup.Provider p) {
        CoreSites d = new CoreSites();
        net.minecraft.nbt.ListTag l = t.getList("sites", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) { Site s = Site.load(l.getCompound(i)); d.sites.put(s.village + "|" + s.key, s); }
        return d;
    }

    static CoreSites of() {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (s == null) {
            if (loose == null) loose = new CoreSites();
            return loose;
        }
        return s.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(CoreSites::new, CoreSites::load, null), ID);
    }

    static void changed() {
        of().setDirty();
    }

    public static void resetForTests() {
        of().sites.clear();
        changed();
    }

    @Nullable
    static Site site(UUID village, String key) {
        return of().sites.get(village + "|" + key);
    }

    // ------------------------------------------------------------------ the businesses, as the Core sees them

    /** A business as a Core knows it: its key, its name, where its premises are, its owner(s) and its books. */
    record Biz(UUID village, String key, String name, @Nullable BlockPos anchor, @Nullable Business book, @Nullable PlayerFarms.Farm farm, @Nullable PlayerShops.Shop shop) {
        boolean ownedBy(UUID p) {
            if (book != null && book.ownedBy(p)) return true;
            if (farm != null) return farm.owner.equals(p);
            return shop != null && shop.owner.equals(p);
        }
    }

    static List<Biz> businesses(UUID village) {
        List<Biz> out = new ArrayList<>();
        for (Business b : Enterprise.open(village)) out.add(new Biz(village, "firm:" + b.id, b.name, b.at, b, null, null));
        for (PlayerFarms.Farm f : PlayerFarms.ofTown(village)) {
            if (f.fields.isEmpty()) continue;
            int[] a = f.fields.get(0);
            out.add(new Biz(village, "farm:" + f.id, f.name, new BlockPos(a[0] - 1, a[4], a[1] - 1), PlayerFarms.business(f.id), f, null));
        }
        for (PlayerShops.Shop s : PlayerShops.all(village)) {
            if (s.firm != null) continue;                                   // a firm's own shop: the firm's Core
            out.add(new Biz(village, "shop:" + s.key(), s.name, s.anchor.equals(BlockPos.ZERO) ? null : s.anchor, null, null, s));
        }
        return out;
    }

    @Nullable
    static Biz biz(UUID village, String key) {
        if (key.startsWith("firm:")) {
            Business b = Businesses.of(village, key.substring(5));
            return b == null ? null : new Biz(village, key, b.name, b.at, b, null, null);
        }
        for (Biz z : businesses(village)) if (z.key().equals(key)) return z;
        return null;
    }

    // ------------------------------------------------------------------ setting it

    /** A floor-level spot beside its premises: air over a sturdy floor, not a road, not a doorway, no other Core near. */
    @Nullable
    static BlockPos spotNear(ServerLevel level, BlockPos at) {
        for (int r = 1; r <= 4; r++) {
            for (int dy = 0; dy >= -2; dy = dy == 0 ? 1 : dy > 0 ? -1 : -3) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = at.offset(dx, dy, dz);
                        if (fits(level, p)) return p;
                    }
                }
                if (dy == -3) break;
            }
        }
        return null;
    }

    static boolean fits(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p) || !level.isLoaded(p.below())) return false;
        BlockState s = level.getBlockState(p), up = level.getBlockState(p.above()), below = level.getBlockState(p.below());
        if (!s.isAir() || !up.isAir() || !below.isFaceSturdy(level, p.below(), Direction.UP)) return false;
        if (below.is(Blocks.DIRT_PATH) || below.is(Blocks.FARMLAND) || below.is(Blocks.GRAVEL) || !level.getFluidState(p).isEmpty()) return false;
        boolean ns = solid(level, p.north()) && solid(level, p.south()), ew = solid(level, p.east()) && solid(level, p.west());
        if (ns || ew) return false;                                         // a doorway or a corridor
        return coreNear(level, p, 5) == null;
    }

    static boolean solid(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isSolidRender(level, p);
    }

    /** A Core within so many blocks of here, if any. */
    @Nullable
    static Site coreNear(ServerLevel level, BlockPos p, int within) {
        for (Site s : of().sites.values()) if (!s.held && s.at().closerThan(p, within) && level.getBlockState(s.at()).is(CoreItems.CORE.get())) return s;
        return null;
    }

    static Direction facing(BlockPos spot, BlockPos toward) {
        int dx = toward.getX() - spot.getX(), dz = toward.getZ() - spot.getZ();
        if (dx == 0 && dz == 0) return Direction.NORTH;
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** The business sets its Core here. */
    static Site put(ServerLevel level, Biz z, BlockPos spot) {
        BlockPos face = z.anchor() != null ? z.anchor() : spot.north();
        level.setBlock(spot, CoreItems.CORE.get().defaultBlockState().setValue(BusinessCoreBlock.FACING, facing(spot, face).getOpposite()), 3);
        if (level.getBlockEntity(spot) instanceof BusinessCoreBlockEntity be) be.bind(z.village(), z.key());
        Site s = site(z.village(), z.key());
        if (s == null) { s = new Site(); s.village = z.village(); s.key = z.key(); of().sites.put(z.village() + "|" + z.key(), s); }
        s.pos = spot.asLong();
        s.held = false;
        s.brokenDay = -1;
        changed();
        refresh(level, s);
        if (z.key().startsWith("firm:")) com.jrpetty.mcassistant.Guard.run("firm kiosk", () -> Tills.fitFirm(level, z.village(), z.key(), spot));   // [till]
        return s;
    }

    /** Every business near a player has its Core (set where it is missing; set again where it was broken). */
    static void ensure(ServerLevel level, ServerPlayer near) {
        Villages.Village v = PlayerLife.here(near);
        if (v == null) return;
        long day = Businesses.today(level);
        for (Biz z : businesses(v.id())) {
            if (z.anchor() == null || !z.anchor().closerThan(near.blockPosition(), 96) || !level.isLoaded(z.anchor())) continue;
            Site s = site(v.id(), z.key());
            if (s != null && s.held) continue;
            if (s != null && level.isLoaded(s.at())) {
                if (level.getBlockState(s.at()).is(CoreItems.CORE.get())) { refresh(level, s); continue; }
                if (s.brokenDay >= day) continue;                           // broken today: set again tomorrow
                if (fits(level, s.at())) { put(level, z, s.at()); continue; }
            }
            if (s != null && !level.isLoaded(s.at())) continue;
            BlockPos spot = spotNear(level, z.anchor());
            if (spot != null) {
                put(level, z, spot);
                if (z.book() != null) {
                    CompoundTag r = Enterprise.rec(v.id(), z.book().id);
                    if (r != null) BusinessCore.event(r, day, "its Business Core set at its premises");
                }
            }
        }
        // Shut businesses: closed on the sign, then taken away.
        for (Site s : new ArrayList<>(of().sites.values())) {
            if (!s.village.equals(v.id()) || !level.isLoaded(s.at()) || !s.at().closerThan(near.blockPosition(), 96)) continue;
            if (biz(s.village, s.key) != null && (s.key.startsWith("firm:") ? Businesses.of(s.village, s.key.substring(5)).open : true)) continue;
            if (s.shutDay < 0) { s.shutDay = day; changed(); }
            if (level.getBlockEntity(s.at()) instanceof BusinessCoreBlockEntity be) be.show(be.name(), "Closed for good", "", false);
            if (day - s.shutDay >= 3) {
                if (level.getBlockState(s.at()).is(CoreItems.CORE.get())) level.removeBlock(s.at(), false);
                of().sites.remove(s.village + "|" + s.key);
                changed();
            }
        }
    }

    /** What floats over it: its name, open or shut, and the deputy when it is in charge. */
    static void refresh(ServerLevel level, Site s) {
        if (!(level.getBlockEntity(s.at()) instanceof BusinessCoreBlockEntity be)) return;
        if (!be.bound()) be.bind(s.village, s.key);
        Biz z = biz(s.village, s.key);
        if (z == null) return;
        Villages.Village v = Villages.get(s.village);
        String status;
        String note = "";
        boolean open;
        if (z.book() != null && s.key.startsWith("firm:")) {
            Business b = z.book();
            CompoundTag r = Enterprise.rec(s.village, b.id);
            open = isOpen(level, s.village, b, r);
            status = r != null && r.contains("petition") ? "Before the court" : open ? "Open" : "Closed";
            if (r != null && r.hasUUID("manager") && Deputy.on(r) && v != null && Deputy.charge(level, s.village, b, r, Businesses.today(level)).mode() == Deputy.Mode.DEPUTY) note = "Deputy in charge";
            else if (r != null && FirmSale.sale(s.village, b) != null) note = "For sale";
        } else if (z.farm() != null) {
            open = !z.farm().paused;
            status = open ? "Working" : "Idle";
        } else {
            PlayerShops.Shop sh = z.shop();
            int hr = BusinessCore.hourOf(level.getDayTime());
            open = sh != null && hr >= sh.opens && hr < sh.closes;
            status = open ? "Open" : "Closed";
        }
        be.show(z.name(), status, note, open);
    }

    static boolean isOpen(ServerLevel level, UUID village, Business b, @Nullable CompoundTag r) {
        if (!b.open || r == null) return b.open;
        if (r.contains("petition")) return false;
        if (r.contains("pausedDay") && r.getLong("pausedDay") == Businesses.today(level)) return false;
        return !Deputy.closedNow(level, village, b);
    }

    // ------------------------------------------------------------------ the player's hand

    /** Its owners may pick it up and move it (and an operator in creative). */
    static boolean mayMove(Player p, BusinessCoreBlockEntity be) {
        if (p.isCreative() && p.hasPermissions(2)) return true;
        if (be.village() == null) return true;
        Biz z = biz(be.village(), be.key());
        return z == null || z.ownedBy(p.getUUID());
    }

    /** Set by a player: bound to their business here (moved from where it was), else refused and handed back. */
    static void placed(ServerLevel level, ServerPlayer p, BlockPos pos, ItemStack stack) {
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);
        Biz best = null;
        double bestD = Double.MAX_VALUE;
        if (v != null) for (Biz z : businesses(v.id())) {
            if (!z.ownedBy(p.getUUID()) || z.anchor() == null) continue;
            double d = z.anchor().distSqr(pos);
            if (d <= 48 * 48 && d < bestD) { best = z; bestD = d; }
        }
        String no = null;
        if (best == null) no = "A Business Core goes at your business's premises: none of yours is near here.";
        else {
            Site near = coreNear(level, pos, 4);
            if (near != null && !near.at().equals(pos)) no = "There's a Core here already: one to a building.";
        }
        if (no != null) {
            level.removeBlock(pos, false);
            if (!p.isCreative()) p.getInventory().add(new ItemStack(CoreItems.CORE_ITEM.get()));
            p.sendSystemMessage(Component.literal(no).withStyle(ChatFormatting.YELLOW));
            return;
        }
        Site old = site(best.village(), best.key());
        if (old != null && !old.held && !old.at().equals(pos) && level.isLoaded(old.at()) && level.getBlockState(old.at()).is(CoreItems.CORE.get())) {
            level.removeBlock(old.at(), false);                             // the move: the old one taken up
        }
        put(level, best, pos);
        long day = Businesses.today(level);
        if (best.book() != null) {
            CompoundTag r = Enterprise.rec(best.village(), best.book().id);
            if (r != null) BusinessCore.event(r, day, "its Business Core moved by " + p.getGameProfile().getName());
        }
        p.sendSystemMessage(Component.literal("The Business Core of " + best.name() + " stands here now. Right-click it to run the business.").withStyle(ChatFormatting.GOLD));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !e.getState().is(CoreItems.CORE.get())) return;
        if (!(level.getBlockEntity(e.getPos()) instanceof BusinessCoreBlockEntity be) || !be.bound()) return;
        Player p = e.getPlayer();
        Site s = site(be.village(), be.key());
        long day = Businesses.today(level);
        if (mayMove(p, be)) {                                               // its owner takes it up, to set it elsewhere
            if (s != null) { s.held = true; changed(); }
            p.sendSystemMessage(Component.literal("You've taken up the Core of " + be.name() + ". Set it down at its premises; till then the business won't set another.").withStyle(ChatFormatting.GRAY));
            return;
        }
        e.setCanceled(true);
        level.removeBlock(e.getPos(), false);                               // gone, and no block for it
        if (s != null) { s.brokenDay = day; changed(); }
        Villages.Village v = Villages.get(be.village());
        if (v != null) Laws.offence(level, v, p, "breaking " + be.name() + "'s Business Core", Laws.DAMAGE_FINE);
        Biz z = biz(be.village(), be.key());
        if (z != null && z.book() != null) {
            CompoundTag r = Enterprise.rec(be.village(), z.book().id);
            if (r != null) BusinessCore.event(r, day, "its Business Core broken by " + p.getName().getString());
            z.book().note(day, "its Business Core broken by " + p.getName().getString() + "; another set tomorrow");
        }
    }

    // ------------------------------------------------------------------ opening it

    static final String HINT = "wpbCoreHint", TOUR = "wpbCoreTour";

    /** Right-click: the hub for its owners (and the partners), the staff's view for a player hand, the public face for anyone. */
    static void open(ServerPlayer p, BlockPos pos) {
        ServerLevel level = p.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof BusinessCoreBlockEntity be) || !be.bound()) {
            p.sendSystemMessage(Component.literal("This Business Core isn't any business's yet: set it at your premises.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Biz z = biz(be.village(), be.key());
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.25F, 1.4F);
        if (z == null) { PlayerLife.open(p, "core", LifePage.args("v", be.village().toString(), "k", be.key())); return; }
        boolean mine = z.ownedBy(p.getUUID());
        if (mine && z.book() != null && be.key().startsWith("firm:") && z.book().open) {
            CompoundTag a = LifePage.args("firm", z.book().id, "tab", "summary", "core", true);
            CompoundTag pd = p.getPersistentData();
            if (!pd.getBoolean(TOUR)) { pd.putBoolean(TOUR, true); a.putInt("tour", 1); }
            PlayerLife.open(p, "firm", a);
            return;
        }
        if (mine && z.farm() != null) { PlayerLife.open(p, "farm", LifePage.args("farm", z.farm().id)); return; }
        if (mine && z.shop() != null) { PlayerShops.choose(p.getUUID(), z.shop()); PlayerLife.open(p, "shop", null); return; }
        PlayerLife.open(p, "core", LifePage.args("v", be.village().toString(), "k", be.key()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        int tc = e.getServer().getTickCount();
        if (tc % 40 == 17) {                                                // a first look: what it is
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
                if (p.getPersistentData().getBoolean(HINT)) continue;
                ServerLevel level = p.serverLevel();
                for (Site s : of().sites.values()) {
                    if (s.held || !s.at().closerThan(p.blockPosition(), 6) || !(level.getBlockEntity(s.at()) instanceof BusinessCoreBlockEntity be)) continue;
                    p.getPersistentData().putBoolean(HINT, true);
                    p.displayClientMessage(Component.literal("The Business Core of " + be.name() + ": right-click it to see the business (or run it, if it's yours).").withStyle(ChatFormatting.GOLD), false);
                    break;
                }
            }
        }
        if (tc % 200 != 91) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            try { ensure(p.serverLevel(), p); } catch (RuntimeException ex) { com.mojang.logging.LogUtils.getLogger().warn("[wpb] cores near {} failed", p.getGameProfile().getName(), ex); }
        }
    }

    // ------------------------------------------------------------------ tests

    public static BlockPos ensureForTests(ServerLevel level, ServerPlayer near, UUID village, String key) {
        ensure(level, near);
        Site s = site(village, key);
        return s == null ? null : s.at();
    }

    public static void openForTests(ServerPlayer p, BlockPos pos) {
        open(p, pos);
    }

    public static void placedForTests(ServerLevel level, ServerPlayer p, BlockPos pos) {
        placed(level, p, pos, new ItemStack(CoreItems.CORE_ITEM.get()));
    }
}
