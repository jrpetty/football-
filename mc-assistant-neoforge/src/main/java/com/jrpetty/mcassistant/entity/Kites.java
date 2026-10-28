package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.DyedShapedRecipe;
import com.jrpetty.mcassistant.item.KiteItem;
import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] Kites (item/KiteItem, KiteEntity).
 *
 * <p><b>The wind.</b> Each day has its wind, the same for the whole world: its strength (a breath, a fair breeze, a good
 * stiff breeze, a blustery day) and the way it blows; a little stronger in spring and autumn, a little weaker in summer,
 * gusting about its strength through the day. No kite goes up in the rain.
 *
 * <p><b>The children.</b> On a dry afternoon with wind enough (Pastimes.afternoon: some afternoons a kickabout, some kites,
 * some their own games), the children fly kites in the park, or on the square with no park: each with a kite of its
 * own or one of the town's, lent out of the stores for the afternoon and put back after. It runs out to a place on the
 * green, its kite in its hand, and sends it up: the kite climbs downwind on its string, high over its head, swaying and
 * bobbing, higher the windier it is; now and then it runs a few steps to keep it up. The children are the happier for it
 * the next day ("my kite went ever so high!"), and remember it.
 *
 * <p><b>A player</b> right-clicks with a kite to send it up the same way; it follows wherever the player walks till it is
 * reeled in (right-click again) or put away.
 *
 * <p><b>Made</b> by the tailor or the shop's workshop, out of the stores: three paper, two sticks, a string and a dye, in
 * whichever colour the stores have the dye for (a colour the town's kites lack first), by the kite's own recipe.
 */
public final class Kites {

    private Kites() {}

    /** Where each flier's kite is (the flier's id to the kite's). */
    private static final Map<UUID, UUID> FLYING = new ConcurrentHashMap<>();
    /** Children with a kite lent out of the stores for the afternoon. */
    private static final Set<UUID> LENT = ConcurrentHashMap.newKeySet();
    /** Each child's last afternoon with a kite (the day). */
    private static final Map<UUID, Long> FLEW = new ConcurrentHashMap<>();
    /** When each child last held its kite up (game time): a kite whose child has gone off is reeled in. */
    private static final Map<UUID, Long> HELD = new ConcurrentHashMap<>();
    /** Each child's place on the green, and when it last ran to a new one. */
    private static final Map<UUID, Object[]> PLACE = new ConcurrentHashMap<>();
    /** Wind for the tests: a strength, or null for the day's own. */
    private static volatile Double windForTests;

    /** Children sent out with a kite for the photographs (LeisureStage): who, where, till when. */
    private static final Map<UUID, Object[]> STAGED = new ConcurrentHashMap<>();

    static void resetForTests() {
        STAGED.clear();
        FLYING.clear();
        LENT.clear();
        FLEW.clear();
        HELD.clear();
        PLACE.clear();
        windForTests = null;
    }

    public static boolean isKite(ItemStack s) {
        return !s.isEmpty() && s.is(LeisureItems.KITE.get());
    }

    // ------------------------------------------------------------------ the wind

    /** The day's wind where this town is (or anywhere, with no town): {strength nought to three and a half, the way it blows, radians}. */
    public static double[] wind(ServerLevel level, @Nullable UUID village, long day) {
        long h = (day * 0x9E3779B97F4A7C15L) ^ level.dimension().location().hashCode() * 31L;
        h ^= h >>> 29;
        double strength = 0.4 + Math.floorMod(h, 1000L) / 1000.0 * 2.6;
        if (village != null) {
            Seasons.Season s = Seasons.season(village, day);
            if (s == Seasons.Season.SPRING || s == Seasons.Season.AUTUMN) strength += 0.4;
            else if (s == Seasons.Season.SUMMER) strength -= 0.3;
        }
        if (windForTests != null) strength = windForTests;
        double angle = Math.floorMod(h >>> 20, 360L) * Mth.DEG_TO_RAD;
        return new double[]{ Math.max(0.0, strength), angle };
    }

    /** Wind enough for a kite, and dry. */
    static boolean flyable(ServerLevel level, @Nullable UUID village, long day) {
        return !level.isRaining() && wind(level, village, day)[0] >= 0.9;
    }

    static String windWords(double s) {
        return s < 0.9 ? "hardly a breath of wind" : s < 1.7 ? "a fair breeze" : s < 2.5 ? "a good stiff breeze" : "a blustery wind";
    }

    /**
     * Where a kite flies now, over its flier: downwind on its string, high up, swaying across the wind and bobbing, the
     * string the longer and the kite the higher the windier it is, gusting; never down into the ground.
     */
    static Vec3 spot(ServerLevel level, Entity flier, KiteEntity kite) {
        UUID village = flier instanceof VillageFolkEntity f ? f.ownerId() : null;
        long day = level.getDayTime() / 24000L;
        double[] w = wind(level, village, day);
        double s = Math.max(0.6, w[0]), a = w[1];
        double t = level.getGameTime(), ph = kite.phase();
        double gust = Math.sin(t * 0.013 + ph) * 0.35 * s;
        double reach = 6.0 + 2.2 * (s + gust), height = 5.0 + 2.6 * (s + gust);
        double sway = Math.sin(t * 0.045 + ph) * (1.2 + 0.4 * s), bob = Math.sin(t * 0.07 + ph * 1.7) * 0.7;
        double dx = Math.cos(a), dz = Math.sin(a);
        double x = flier.getX() + dx * reach - dz * sway, z = flier.getZ() + dz * reach + dx * sway;
        double y = flier.getY() + flier.getBbHeight() + height + bob;
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (level.hasChunk(bx >> 4, bz >> 4)) y = Math.max(y, level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 2.5);
        return new Vec3(x, y, z);
    }

    // ------------------------------------------------------------------ flying

    /** Sent up from this flier's hand, in its kite's colour (one kite a flier). The kite, or null. */
    @Nullable
    static KiteEntity launch(ServerLevel level, Entity flier, ItemStack kite) {
        UUID was = FLYING.get(flier.getUUID());
        if (was != null && level.getEntity(was) instanceof KiteEntity k && k.isAlive()) return k;
        KiteEntity k = KiteEntity.launch(level, flier, KiteItem.colour(kite));
        if (k == null) return null;
        FLYING.put(flier.getUUID(), k.getUUID());
        level.playSound(null, flier.getX(), flier.getY() + 1.0, flier.getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 0.6F, 1.6F);
        return k;
    }

    /** Reeled in: the kite out of the air (the kite itself stays in its flier's hand). */
    static void reelIn(ServerLevel level, UUID flier) {
        UUID k = FLYING.remove(flier);
        if (k != null && level.getEntity(k) instanceof KiteEntity e) e.discard();
    }

    /** Is the kite still flown by this flier: still in its hand, and (a child) still at it? */
    static boolean stillFlying(KiteEntity kite, Entity flier) {
        if (!kite.getUUID().equals(FLYING.get(flier.getUUID()))) return false;
        if (flier instanceof Player p) return isKite(p.getMainHandItem()) || isKite(p.getOffhandItem());
        if (flier instanceof VillageFolkEntity f) {
            Long h = HELD.get(f.getUUID());
            return h != null && kite.level().getGameTime() - h < 80L && !f.isSleeping() && isKite(f.getMainHandItem());
        }
        return false;
    }

    /** Down: its flier no longer flies it. */
    static void landed(KiteEntity kite, @Nullable Entity flier) {
        UUID id = kite.flierId();
        if (id != null) FLYING.remove(id, kite.getUUID());
    }

    /**
     * A player's kite (KiteItem): up it goes from the hand, or down again if it is up already. What the player is told.
     */
    public static String fromHand(ServerLevel level, Player p, ItemStack held) {
        UUID was = FLYING.get(p.getUUID());
        if (was != null && level.getEntity(was) instanceof KiteEntity k && k.isAlive()) {
            reelIn(level, p.getUUID());
            level.playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 0.7F, 1.4F);
            return "You reel your kite in.";
        }
        if (level.isRaining()) return "It's too wet to fly a kite.";
        double[] w = wind(level, null, level.getDayTime() / 24000L);
        if (launch(level, p, held) == null) return "";
        return "Up it goes! " + Character.toUpperCase(windWords(w[0]).charAt(0)) + windWords(w[0]).substring(1) + " today.";
    }

    // ------------------------------------------------------------------ the children's afternoon (Pastimes.hold)

    /**
     * A child's afternoon with a kite, on an afternoon for kites: its own kite or one lent out of the stores, out to its
     * place on the green, the kite sent up and flown, a run now and then to keep it up. What it is doing, or null.
     */
    @Nullable
    static String hold(ServerLevel level, VillageFolkEntity child, UUID village, long t, long day) {
        boolean staged = staged(child, level.getGameTime());
        if (!staged && Pastimes.afternoon(level, village, day, t) != Pastimes.KITES || !Families.childFree(child) || School.doing(child) != null) {
            if (FLYING.containsKey(child.getUUID())) reelIn(level, child.getUUID());
            return null;
        }
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        if (child.countCarried(Kites::isKite) == 0) {
            ItemStack lend = Crafts.takeOne(level, v, Kites::isKite);
            if (lend.isEmpty()) return watch(level, child, v);
            ItemStack left = child.insertItem(lend);
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);
                return null;
            }
            LENT.add(child.getUUID());
        }
        HELD.put(child.getUUID(), level.getGameTime());
        BlockPos place = place(level, child, v);
        String where = Pastimes.playground(village, v)[1].toString();
        String colour = colourWord(kiteOf(child));
        if (child.blockPosition().distSqr(place) > 2.0 * 2.0) {
            if (child.getNavigation().isDone() || child.tickCount - child.hobbyTick > 40 || child.tickCount < child.hobbyTick) {
                child.walkTo(place, 1.15D);
                child.hobbyTick = child.tickCount;
            }
            return FLYING.containsKey(child.getUUID()) ? "running with " + colour + " kite in " + where : "off to fly " + colour + " kite in " + where;
        }
        child.getNavigation().stop();
        inHand(child);
        KiteEntity k = launch(level, child, child.getMainHandItem());
        if (k != null) child.getLookControl().setLookAt(k.getX(), k.getY(), k.getZ());
        if (FLEW.getOrDefault(child.getUUID(), -1L) != day) {
            FLEW.put(child.getUUID(), day);
            child.persona().remember(day, "I flew " + colour + " kite in " + where, 2);
            Pastimes.news(village, day, "kites:the children flew kites in " + where + " on " + windWords(wind(level, village, day)[0]));
            FolkTalk.speak(child, FolkTalk.pick(level.getRandom(), "Up, up, up!", "Look at mine go!", "Hold tight — here comes the wind!"));
        } else if (level.getRandom().nextInt(400) == 0) {
            FolkTalk.speak(child, FolkTalk.pick(level.getRandom(), "Mine's the highest!", "Higher! Higher!", "Don't let it come down!",
                "It's pulling like anything!"));
        }
        if (level.getRandom().nextInt(60) == 0) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, child.getX(), child.getY() + 1.4, child.getZ(), 2,
            0.2, 0.2, 0.2, 0.0);
        return "flying " + colour + " kite in " + where;
    }

    /** A child with no kite to fly, on a kites afternoon: out on the green with the others, watching them. */
    @Nullable
    private static String watch(ServerLevel level, VillageFolkEntity child, Villages.Village v) {
        Object[] pg = Pastimes.playground(v.id(), v);
        BlockPos ground = (BlockPos) pg[0];
        if (child.blockPosition().distSqr(ground) > 5.0 * 5.0) {
            if (child.getNavigation().isDone() || child.tickCount - child.hobbyTick > 60) {
                child.walkTo(ground, 1.0D);
                child.hobbyTick = child.tickCount;
            }
        } else {
            child.getNavigation().stop();
            child.getLookControl().setLookAt(child.getX() + 2, child.getY() + 12, child.getZ() + 2);
        }
        return "watching the kites in " + pg[1];
    }

    /** Its place on the green: a ring round the middle, each child its own, run to anew every half minute or so. */
    private static BlockPos place(ServerLevel level, VillageFolkEntity child, Villages.Village v) {
        long now = level.getGameTime();
        Object[] st = STAGED.get(child.getUUID());
        if (st != null && now < (Long) st[1]) return (BlockPos) st[0];
        Object[] p = PLACE.get(child.getUUID());
        if (p != null && now - (Long) p[1] < 600L && now >= (Long) p[1]) return (BlockPos) p[0];
        BlockPos ground = (BlockPos) Pastimes.playground(v.id(), v)[0];
        double a = Math.floorMod(child.getUUID().hashCode(), 360) * Mth.DEG_TO_RAD + level.getRandom().nextDouble() * 0.6;
        double rad = 4.0 + level.getRandom().nextInt(4);
        int x = ground.getX() + (int) Math.round(Math.cos(a) * rad), z = ground.getZ() + (int) Math.round(Math.sin(a) * rad);
        BlockPos floor = Watch.floorAt(level, x, z, ground.getY());
        BlockPos at = floor != null ? floor : ground;
        PLACE.put(child.getUUID(), new Object[]{ at, now });
        return at;
    }

    /** Is this child out with a kite for the photographs (whatever the afternoon)? */
    static boolean staged(VillageFolkEntity child, long now) {
        Object[] st = STAGED.get(child.getUUID());
        if (st == null) return false;
        if (now >= (Long) st[1]) {
            STAGED.remove(child.getUUID());
            return false;
        }
        return true;
    }

    /** For the photographs (LeisureStage): this child flies its kite where it stands, whatever the afternoon, till then. */
    static void stage(ServerLevel level, VillageFolkEntity child, long until) {
        STAGED.put(child.getUUID(), new Object[]{ child.blockPosition(), until });
        inHand(child);
        HELD.put(child.getUUID(), level.getGameTime());
        launch(level, child, child.getMainHandItem());
    }

    /** The kite into its hand (out of its pack; what it held into the pack). */
    static void inHand(VillageFolkEntity f) {
        if (isKite(f.getMainHandItem())) return;
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (!isKite(pack.get(i))) continue;
            ItemStack was = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, pack.get(i));
            pack.set(i, was);
            return;
        }
    }

    private static ItemStack kiteOf(VillageFolkEntity f) {
        if (isKite(f.getMainHandItem())) return f.getMainHandItem();
        for (ItemStack s : f.getInventoryItems()) if (isKite(s)) return s;
        return ItemStack.EMPTY;
    }

    /** "a red", "its blue": the kite's colour in a word, the nearest dye's. */
    static String colourWord(ItemStack kite) {
        if (kite.isEmpty()) return "a";
        int rgb = KiteItem.colour(kite);
        DyeColor best = DyeColor.WHITE;
        long bd = Long.MAX_VALUE;
        for (DyeColor c : DyeColor.values()) {
            int d = DyedShapedRecipe.rgb(c);
            long dr = ((d >> 16) & 255) - ((rgb >> 16) & 255), dg = ((d >> 8) & 255) - ((rgb >> 8) & 255), db = (d & 255) - (rgb & 255);
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bd) { bd = dist; best = c; }
        }
        String w = best.getName().replace('_', ' ');
        return (Homes.isKeepsake(kite) ? "its " : w.matches("^[aeiou].*") ? "an " : "a ") + w;
    }

    /**
     * The town's round (every second): the kites lent for the afternoon put back once the kites are done (the child's own
     * kite into its pack), and any kite in the air whose child has gone off reeled in.
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        if (LENT.isEmpty() && HELD.isEmpty()) return;
        boolean afternoon = Pastimes.afternoon(level, v.id(), day, t) == Pastimes.KITES;
        long now = level.getGameTime();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            boolean kites = afternoon || staged(f, now);
            Long h = HELD.get(f.getUUID());
            boolean idle = h == null || now - h > 200L || now < h;
            if (!kites || idle) {
                if (h != null && (!kites || now - h > 200L)) HELD.remove(f.getUUID());
                if (FLYING.containsKey(f.getUUID()) && (!kites || idle)) reelIn(level, f.getUUID());
                if (LENT.remove(f.getUUID())) giveBack(level, v, f);
            }
        }
    }

    /** The stores' kite back out of the child's hands (its own it keeps). */
    static void giveBack(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (isKite(f.getMainHandItem()) && !Homes.isKeepsake(f.getMainHandItem())) {
            Crafts.store(level, v, f.getMainHandItem().copy());
            f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!isKite(s) || Homes.isKeepsake(s)) continue;
            Crafts.store(level, v, s.copy());
            pack.set(i, ItemStack.EMPTY);
            return;
        }
    }

    // ------------------------------------------------------------------ made

    /** Kites enough for the children to share: one for every two of them (four at most), once there are two. */
    static int wanted(ServerLevel level, Villages.Village v) {
        int children = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.isBaby() && a.isAlive()) children++;
        return children < 2 ? 0 : Math.min(4, (children + 1) / 2);
    }

    /** The kite's recipe as the game has it. */
    @Nullable
    static CraftingRecipe recipe(ServerLevel level) {
        Optional<RecipeHolder<?>> h = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(com.jrpetty.mcassistant.McAssistantMod.MODID, "kite"));
        return h.isPresent() && h.get().value() instanceof CraftingRecipe r ? r : null;
    }

    /** The dyes the stores have (or could make), a colour the town's kites lack first. */
    static List<DyeColor> colours(ServerLevel level, Villages.Village v) {
        Set<DyeColor> have = ConcurrentHashMap.newKeySet();
        for (net.minecraft.core.BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (isKite(s)) have.add(nearest(KiteItem.colour(s)));
            }
        }
        List<DyeColor> out = new ArrayList<>();
        List<DyeColor> later = new ArrayList<>();
        for (DyeColor c : new DyeColor[]{ DyeColor.RED, DyeColor.YELLOW, DyeColor.BLUE, DyeColor.ORANGE, DyeColor.LIME, DyeColor.PINK,
                DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.PURPLE, DyeColor.GREEN, DyeColor.CYAN, DyeColor.WHITE, DyeColor.BROWN,
                DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK }) {
            (have.contains(c) ? later : out).add(c);
        }
        out.addAll(later);
        return out;
    }

    static DyeColor nearest(int rgb) {
        DyeColor best = DyeColor.WHITE;
        long bd = Long.MAX_VALUE;
        for (DyeColor c : DyeColor.values()) {
            int d = DyedShapedRecipe.rgb(c);
            long dr = ((d >> 16) & 255) - ((rgb >> 16) & 255), dg = ((d >> 8) & 255) - ((rgb >> 8) & 255), db = (d & 255) - (rgb & 255);
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bd) { bd = dist; best = c; }
        }
        return best;
    }

    /**
     * A kite made: three paper, two sticks, a string and a dye out of the stores (made at the bench first if need be: the
     * paper of cane, the sticks of planks, the dye of a flower), laid in the grid as the kite's recipe has them, and made
     * by it in the dye's colour; into the stores. Empty if the stores cannot run to it.
     */
    static ItemStack make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Bench.Hand hand) {
        CraftingRecipe recipe = recipe(level);
        Item kite = LeisureItems.KITE.get();
        if (recipe == null) return ItemStack.EMPTY;
        String last = null;
        for (DyeColor c : colours(level, v)) {
            Item dye = DyeItem.byColor(c);
            List<Bench.Want> wants = List.of(Bench.Want.of(Items.PAPER, 3), Bench.Want.of(Items.STICK, 2), Bench.Want.of(Items.STRING, 1),
                Bench.Want.of(dye, 1));
            Bench.Plan p = Bench.plan(level, v, wants, hand);
            if (!p.ok()) {
                if (last == null) last = p.chain();
                continue;
            }
            ItemStack paper = new ItemStack(Items.PAPER), stick = new ItemStack(Items.STICK);
            CraftingInput input = CraftingInput.of(3, 3, List.of(ItemStack.EMPTY, paper, ItemStack.EMPTY, paper, stick, paper,
                new ItemStack(dye), stick, new ItemStack(Items.STRING)));
            if (!recipe.matches(input, level) || !Bench.take(level, v, p, f)) return ItemStack.EMPTY;
            ItemStack out = recipe.assemble(input, level.registryAccess());
            if (f != null) out = Craftsmanship.finish(level, out, hand.skill(), hand.maker());
            Crafts.store(level, v, out.copy());
            Pastimes.forgetShort(v.id(), kite);
            return out;
        }
        Pastimes.shortOf(v.id(), kite, last == null ? "a dye for it" : last);
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ spirits, the card, the books

    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long d = FLEW.get(f.getUUID());
        if (d == null || day - d > 1) return m;
        why.add(new Object[]{ "kite", 5 });
        return m + 5;
    }

    @Nullable
    static String card(VillageFolkEntity f) {
        if (FLYING.containsKey(f.getUUID())) return "flying " + colourWord(kiteOf(f)) + " kite";
        ItemStack k = kiteOf(f);
        if (!k.isEmpty() && Homes.isKeepsake(k)) return "has " + colourWord(k) + " kite";
        return null;
    }

    static List<String> gazette(UUID village, long day) {
        List<String> l = Pastimes.newsOf(village, day - 1, "kites:");
        if (l.isEmpty()) return List.of();
        String s = l.get(0);
        return List.of(Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".");
    }

    static String status(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        int flying = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (FLYING.containsKey(a.getUUID())) flying++;
        return Market.stock(level, v.id(), Kites::isKite) + " in the stores; today " + windWords(wind(level, v.id(), day)[0])
            + (level.isRaining() ? " (and raining)" : "") + "; " + flying + " up now";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the wind's strength held at this (null: the day's own). */
    public static void windForTests(@Nullable Double strength) {
        windForTests = strength;
    }

    /** Tests: a child's look at the kites now (as Pastimes.hold calls it). */
    @Nullable
    public static String holdForTests(ServerLevel level, VillageFolkEntity child) {
        long dt = level.getDayTime();
        return child.ownerId() == null ? null : hold(level, child, child.ownerId(), dt % 24000L, dt / 24000L);
    }

    /** Tests: the child's place on the green now (where it runs to fly its kite). */
    public static BlockPos placeForTests(ServerLevel level, VillageFolkEntity child) {
        Villages.Village v = child.ownerId() == null ? null : Villages.get(child.ownerId());
        return v == null ? child.blockPosition() : place(level, child, v);
    }

    /** Tests: the kite this flier has up, or null. */
    @Nullable
    public static KiteEntity flyingForTests(ServerLevel level, UUID flier) {
        UUID k = FLYING.get(flier);
        return k != null && level.getEntity(k) instanceof KiteEntity e && e.isAlive() ? e : null;
    }

    /** Tests: the town's round of the kites now (the lent ones put back once the kites are done). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime();
        for (UUID u : new ArrayList<>(HELD.keySet())) HELD.put(u, -10000L);
        tick(level, v, dt / 24000L, dt % 24000L);
    }

    /** Tests: the kite made now, as the maker would (null maker: the town's bench). */
    public static ItemStack makeForTests(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        return make(level, v, f, Bench.handOf(level, v, f, null));
    }
}
