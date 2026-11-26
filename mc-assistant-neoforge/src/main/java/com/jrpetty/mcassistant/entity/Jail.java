package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.entity.Justice.Stage;
import com.jrpetty.mcassistant.net.JusticePayload;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpe] The jail, and the players in it.
 *
 * <p><b>The building.</b> A town with a sworn special constable, or {@link JusticeConfig#jailPopulation} folk, from the
 * Iron Age, builds a jailhouse (jailhouse.txt): three cells across the back, each with a cot, a slop bucket and a
 * small barred window high in the back wall; iron bars and an iron door on each cell (the watch hangs them as soon as it
 * stands); a passage; the front room with the guard's desk, the casebook, the key on its hook by the desk, and a bench
 * for visitors. Before it stands, a prisoner is held in a cell of the watch house; before that, in a locked room at the
 * Town Hall; before that, under the watch's eye by the stocks on the square.
 *
 * <p><b>Custody.</b> Remanded till the court sits, then (sentenced to it) serving its time in the same cell. Its
 * belongings are taken at the door and handed back at release. A guard brings its meals; visitors may come at set hours
 * and pass something through the bars (not a weapon or a tool). Time counts while the player is online; logged off,
 * only at {@link JusticeConfig#offlineRate}, so nobody serves its whole time away and nobody is held for it either.
 * Getting out any other way is a jail-break: a charge of its own and the watch after it. A HUD shows what is left and why.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Jail {

    private Jail() {}

    public static final String STRUCTURE = "jailhouse";

    public enum Kind { JAILHOUSE, WATCHHOUSE, HALL, SQUARE }

    /** Where a prisoner is kept: how, the spot it stands, the cell's door and the passage before it, the cell, in words. */
    public static final class Spot {
        public final Kind kind;
        public final BlockPos inside;
        @Nullable public final BlockPos door, front;
        public final int index;
        public final String words;

        Spot(Kind kind, BlockPos inside, @Nullable BlockPos door, @Nullable BlockPos front, int index, String words) {
            this.kind = kind;
            this.inside = inside.immutable();
            this.door = door == null ? null : door.immutable();
            this.front = front == null ? null : front.immutable();
            this.index = index;
            this.words = words;
        }

        double radius() {
            return kind == Kind.JAILHOUSE || kind == Kind.WATCHHOUSE ? 3.2 : 6.0;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("k", kind.name());
            t.putLong("in", inside.asLong());
            if (door != null) t.putLong("door", door.asLong());
            if (front != null) t.putLong("front", front.asLong());
            t.putInt("i", index);
            t.putString("w", words);
            return t;
        }

        @Nullable
        static Spot load(CompoundTag t) {
            if (!t.contains("in")) return null;
            Kind k;
            try { k = Kind.valueOf(t.getString("k")); } catch (IllegalArgumentException e) { k = Kind.SQUARE; }
            return new Spot(k, BlockPos.of(t.getLong("in")), t.contains("door") ? BlockPos.of(t.getLong("door")) : null,
                t.contains("front") ? BlockPos.of(t.getLong("front")) : null, t.getInt("i"), t.getString("w"));
        }
    }

    // ------------------------------------------------------------------ the jailhouse's drawing (across, up, toward the back)

    /** Per cell: inside, door, front, bar, bar, bucket, window. */
    private static final int[][][] CELLS = {
        { { -4, 0, 4 }, { -4, 0, 2 }, { -4, 0, 1 }, { -5, 0, 2 }, { -3, 0, 2 }, { -3, 0, 5 }, { -4, 2, 6 } },
        { { 0, 0, 4 }, { 0, 0, 2 }, { 0, 0, 1 }, { -1, 0, 2 }, { 1, 0, 2 }, { 1, 0, 5 }, { 0, 2, 6 } },
        { { 4, 0, 4 }, { 4, 0, 2 }, { 4, 0, 1 }, { 3, 0, 2 }, { 5, 0, 2 }, { 5, 0, 5 }, { 4, 2, 6 } } };
    /** The guard's place behind the desk, the key's hook, where a visitor sits, and the door. */
    static final int[] DESK = { 4, 0, -2 }, KEY = { 5, 1, -1 }, BENCH = { -4, 0, -4 }, DOOR = { 0, 0, -6 };

    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        Ledger.Building b = Villages.builtStructure(village, STRUCTURE);
        if (b == null || Ledger.raising(village, b.anchor())) return null;
        return b;
    }

    static BlockPos at(Ledger.Building b, int[] c) {
        return WatchHouse.at(b, c[0], c[1], c[2]);
    }

    /** Does the town want a jailhouse: the Iron Age, a watch, and a sworn constable or folk enough; none yet. */
    public static boolean wanted(UUID village) {
        if (!JusticeConfig.enabled() || Villages.builtStructure(village, STRUCTURE) != null) return false;
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal() || Patrols.watch(village).isEmpty()) return false;
        return constable(village) || Villages.headcount(village) >= JusticeConfig.jailPopulation();
    }

    static boolean constable(UUID village) {
        CompoundTag all = Police.town(village).getCompound("constables");
        for (String k : all.getAllKeys()) if (all.getCompound(k).getLong("sworn") > 0) return true;
        return false;
    }

    public static String why(UUID village) {
        return (constable(village) ? "The town has a sworn constable and" : "The town has " + Villages.headcount(village) + " folk and")
            + " nowhere proper to hold the accused: a jailhouse, with three cells of iron bars, a cot and a bucket in each, "
            + "the guard's desk and the key, and a bench for visitors.";
    }

    /** Irons rather than rope: a smith, a jailhouse, or the Iron Age. */
    static boolean irons(ServerLevel level, Villages.Village v) {
        if (of(v.id()) != null || Villages.ageOf(v.id()).ordinal() >= Villages.Age.IRON.ordinal()) return true;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == AssistantEntity.StationTask.SMITH && !a.isBaby()) return true;
        return false;
    }

    /** The bars, the iron doors, the buckets, the key on its hook (the watch sees to them once the house stands). */
    static void fit(ServerLevel level, Villages.Village v, Ledger.Building b) {
        if (!level.isLoaded(b.anchor())) return;
        Direction right = b.facing().getClockWise();
        for (int[][] c : CELLS) {
            for (int k = 3; k <= 4; k++) {
                for (int up = 0; up <= 1; up++) {
                    BlockPos p = WatchHouse.at(b, c[k][0], up, c[k][2]);
                    BlockState st = level.getBlockState(p);
                    if (!st.is(Blocks.IRON_BARS) && (st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.FENCES))) {
                        level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, p), 3);
                    }
                }
            }
            BlockPos door = at(b, c[1]);
            if (!level.getBlockState(door).is(Blocks.IRON_DOOR)) WatchHouse.hangIronDoor(level, v, door);
            BlockPos bucket = at(b, c[5]);
            if (level.getBlockState(bucket).isAir()) level.setBlock(bucket, Blocks.CAULDRON.defaultBlockState(), 3);
            BlockPos window = at(b, c[6]);
            if (!level.getBlockState(window).is(Blocks.IRON_BARS)) {
                level.setBlock(window, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, window), 3);
            }
        }
        BlockPos key = at(b, KEY);
        if (level.getBlockState(key).isAir()) {
            level.setBlock(key, Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, right.getOpposite()), 3);
        }
    }

    // ------------------------------------------------------------------ where a prisoner goes

    static List<Spot> cells(Ledger.Building b) {
        List<Spot> out = new ArrayList<>();
        for (int i = 0; i < CELLS.length; i++) {
            int[][] c = CELLS[i];
            out.add(new Spot(Kind.JAILHOUSE, at(b, c[0]), at(b, c[1]), at(b, c[2]), i, "cell " + (i + 1) + " of the jailhouse"));
        }
        return out;
    }

    /** A free place to hold this player: a jailhouse cell, a watch house cell, the hall, the square. */
    @Nullable
    static Spot spot(ServerLevel level, Villages.Village v, UUID player) {
        Spot mine = spotOf(player);
        if (mine != null) return mine;
        Ledger.Building jh = of(v.id());
        if (jh != null && level.isLoaded(jh.anchor())) {
            fit(level, v, jh);
            for (Spot s : cells(jh)) if (!cellTaken(v.id(), Kind.JAILHOUSE, s.index)) return s;
        }
        WatchHouse.Cell c = WatchHouse.freeCell(level, v.id());
        if (c != null) return new Spot(Kind.WATCHHOUSE, c.inside(), c.door(), c.front(), c.index(), "a cell at the watch house");
        Ledger.Building hall = TownHallDesk.hall(v.id());
        if (hall != null) return new Spot(Kind.HALL, ground(level, hall.anchor()), null, ground(level, hall.anchor()), 0, "a locked room at the Town Hall");
        BlockPos stocks = Trial.stocksAt(level, v);
        BlockPos sq = stocks != null ? stocks.relative(Direction.SOUTH) : v.centre();
        return new Spot(Kind.SQUARE, ground(level, sq), null, ground(level, sq), 0, stocks != null ? "the stocks on the square" : "the square, under the watch's eye");
    }

    private static BlockPos ground(ServerLevel level, BlockPos p) {
        BlockPos at = p;
        for (int i = 0; i < 6 && !level.getBlockState(at).isAir(); i++) at = at.above();
        return at;
    }

    static boolean cellTaken(UUID village, Kind kind, int index) {
        CompoundTag all = Justice.of().custody;
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if (!t.hasUUID("v") || !t.getUUID("v").equals(village)) continue;
            Spot s = Spot.load(t.getCompound("spot"));
            if (s != null && s.kind == kind && s.index == index) return true;
        }
        return false;
    }

    /** Is a watch house cell held by a player (WatchHouse.freeCell)? */
    static boolean watchCellTaken(UUID village, int index) {
        return cellTaken(village, Kind.WATCHHOUSE, index);
    }

    // ------------------------------------------------------------------ custody

    @Nullable
    static CompoundTag custody(UUID player) {
        CompoundTag all = Justice.of().custody;
        return all.contains(player.toString()) ? all.getCompound(player.toString()) : null;
    }

    public static boolean held(UUID player) {
        CompoundTag t = custody(player);
        return t != null && !t.getBoolean("escaped");
    }

    @Nullable
    static Spot spotOf(UUID player) {
        CompoundTag t = custody(player);
        return t == null ? null : Spot.load(t.getCompound("spot"));
    }

    /** Locked up: remanded for its case (or serving, if already sentenced); its belongings taken at the door. */
    static void lockUp(ServerLevel level, Villages.Village v, UUID player, JCase c, @Nullable Spot spot, @Nullable VillageFolkEntity guard) {
        if (spot == null) spot = spot(level, v, player);
        if (spot == null) return;
        CompoundTag all = Justice.of().custody;
        CompoundTag t = custody(player);
        boolean fresh = t == null;
        if (fresh) {
            t = new CompoundTag();
            t.putUUID("v", v.id());
            t.putInt("case", c.id);
            t.putBoolean("remand", true);
            t.putDouble("served", 0);
            t.putLong("since", level.getGameTime());
        }
        t.putBoolean("escaped", false);
        t.putBoolean("court", false);
        t.put("spot", spot.save());
        t.putString("reason", c.chargeWords() + " at " + c.place);
        all.put(player.toString(), t);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null && fresh) takeBelongings(level, p, t);
        if (c.stage.ordinal() < Stage.REMAND.ordinal()) {
            c.stage = Stage.REMAND;
            for (int j : c.joined) { JCase o = Justice.get(j); if (o != null) o.stage = Stage.REMAND; }
            c.courtAt = level.getGameTime() + Math.max(Justice.pause(1200), (long) (JusticeConfig.courtDelayMinutes() * 1200 * (Justice.hurry ? 0.05 : 1.0)));
        }
        Justice.changed();
        if (guard != null) FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "There. Your things are safe with me till you're out.",
            "Make yourself at home. Meals at noon and dusk.", "The court will send for you.", "Visiting's mornings and late afternoon.",
            "Behave, and we'll get on fine.", "Shout if you're dying. Otherwise, don't."));
        if (p != null && t.getBoolean("remand")) {
            BlockPos court = Assize.courtPlace(level, v);
            Planner.add(p, level.getDayTime() + (c.courtAt - level.getGameTime()), "court", "Your trial: " + c.title(), court);
            Justice.notice(p, "Held for trial", "You are held in " + spot.words + " for " + c.chargeWords() + " at " + c.place + ". Your belongings have been taken and will be returned when you leave.",
                "The court sits on your case in " + Justice.minutes(c.courtAt - level.getGameTime()) + " (it's on your planner). Prepare your defence on the Case tab: speak for yourself, hire a lawyer, or have one found for you.");
            level.playSound(null, spot.inside, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.8F);
        }
        Police.log(v.id(), level.getDayTime(), "jail", c.accusedName + " was locked in " + spot.words, guard, 0);
    }

    /** Taken at the door: everything it carries but what it wears, kept till it leaves. */
    static void takeBelongings(ServerLevel level, ServerPlayer p, CompoundTag t) {
        ListTag l = t.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            ItemStack s = p.getInventory().items.get(i);
            if (s.isEmpty()) continue;
            l.add(s.save(level.registryAccess()));
            p.getInventory().items.set(i, ItemStack.EMPTY);
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            l.add(off.save(level.registryAccess()));
            p.getInventory().offhand.set(0, ItemStack.EMPTY);
        }
        t.put("items", l);
        p.getInventory().setChanged();
    }

    /** Handed back at the door: in its pack, or at its feet. */
    static int giveBack(ServerLevel level, ServerPlayer p, CompoundTag t) {
        int n = 0;
        for (Tag x : t.getList("items", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parse(level.registryAccess(), x).orElse(ItemStack.EMPTY);
            if (s.isEmpty()) continue;
            n += s.getCount();
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        t.put("items", new ListTag());
        p.getInventory().setChanged();
        return n;
    }

    /** Another charge for one already inside: it rides with its custody's case. */
    static void join(ServerLevel level, ServerPlayer p, JCase c) {
        CompoundTag t = custody(p.getUUID());
        JCase main = t == null ? null : Justice.get(t.getInt("case"));
        if (main == null || main == c) return;
        if (main.stage == Stage.REMAND) Arrests.join(main, c);
        else {
            c.stage = Stage.REMAND;
            c.courtAt = level.getGameTime() + Justice.pause(1200);
            t.putInt("case", c.id);
            t.putBoolean("remand", true);
        }
        Justice.changed();
    }

    static void outForCourt(UUID player, boolean out) {
        CompoundTag t = custody(player);
        if (t != null) t.putBoolean("court", out);
        Justice.changed();
    }

    /** Sentenced to the cells: so many seconds, less what it has served on remand; walked back to them if it is out. */
    static void sentence(ServerLevel level, Villages.Village v, ServerPlayer p, JCase c, double seconds) {
        CompoundTag t = custody(p.getUUID());
        if (t == null) {
            lockUp(level, v, p.getUUID(), c, null, null);
            t = custody(p.getUUID());
            if (t == null) return;
        }
        double served = t.getDouble("served");
        t.putBoolean("remand", false);
        t.putInt("case", c.id);
        t.putDouble("total", seconds);
        t.putDouble("left", Math.max(5, seconds - served * 0.5));
        t.putString("reason", c.chargeWords() + " at " + c.place);
        Justice.changed();
    }

    /** Free: the door opened, its belongings back, a word from the guard. */
    static void release(ServerLevel level, UUID player, String why) {
        CompoundTag t = custody(player);
        if (t == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        Spot s = Spot.load(t.getCompound("spot"));
        if (p == null) {                                                   // freed while away: freed when it is back
            t.putBoolean("free", true);
            t.putString("why", why);
            Justice.changed();
            return;
        }
        int items = giveBack(level, p, t);
        Justice.of().custody.remove(player.toString());
        Justice.changed();
        Restraint.off(p);
        if (s != null && s.door != null) WatchHouse.setDoor(level, s.door, true);
        hud(p, null);
        Villages.Village v = t.hasUUID("v") ? Villages.get(t.getUUID("v")) : null;
        VillageFolkEntity g = v == null ? null : WatchHouse.nearestGuard(level, v, p.blockPosition(), 24.0, null);
        if (g != null) FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Off you go. Don't let me see you back.", "You're free. Your things, as promised.",
            "Time's up. The door's open.", "Out you go. Mind how you go.", "That's you done. Stay out of trouble.", "Free to go. Try the honest life."));
        level.playSound(null, p.blockPosition(), SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        Justice.notice(p, "Released", why + (items > 0 ? " Your belongings (" + items + " items) have been handed back." : ""),
            "The cell door is open: you're free to walk out. Your record keeps the conviction; the town's opinion of you will mend with time.");
    }

    /** Fed by the guard: bread through the bars. */
    static void fed(ServerLevel level, Villages.Village v, ServerPlayer p, VillageFolkEntity g) {
        CompoundTag t = custody(p.getUUID());
        if (t != null) t.putLong("meal", level.getGameTime());
        ItemStack bread = Crafts.takeOne(level, v, s -> s.is(Items.BREAD));
        if (bread.isEmpty()) bread = new ItemStack(Items.BREAD);
        bread.setCount(Math.max(2, bread.getCount()));
        if (!p.getInventory().add(bread)) p.drop(bread, false);
        p.getFoodData().eat(4, 0.6F);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Dinner. Don't get used to it.", "Bread. It's what there is.", "Here. Eat it while it's warm.",
            "Your meal, compliments of the town.", "Eat up. The court likes them awake.", "Bread and a bit more bread."));
        p.displayClientMessage(Component.literal(g.displayNameCap() + " brought your meal.").withStyle(ChatFormatting.GRAY), true);
    }

    // ------------------------------------------------------------------ the round, each second

    static void tick(ServerLevel level) {
        CompoundTag all = Justice.of().custody;
        long now = level.getGameTime();
        double perSecond = 1.0;
        for (String k : new ArrayList<>(all.getAllKeys())) {
            CompoundTag t = all.getCompound(k);
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p == null || p.level() != level) continue;
            Villages.Village v = t.hasUUID("v") ? Villages.get(t.getUUID("v")) : null;
            if (v == null) { all.remove(k); continue; }
            if (t.getBoolean("free")) { release(level, id, t.getString("why")); continue; }
            if (t.getBoolean("escaped")) { hud(p, null); continue; }
            Spot s = Spot.load(t.getCompound("spot"));
            boolean court = t.getBoolean("court");
            t.putDouble("served", t.getDouble("served") + perSecond);
            if (!t.getBoolean("remand") && !court) {
                double left = t.getDouble("left") - perSecond;
                t.putDouble("left", left);
                if (left <= 0) {
                    JCase c = Justice.get(t.getInt("case"));
                    release(level, id, "You have served your sentence" + (c == null ? "." : " for " + c.chargeWords() + "."));
                    continue;
                }
            }
            // Out of the cell any other way than through the door with a guard: a jail-break.
            if (s != null && !court && !Restraint.restrained(p) && Arrests.guardOn(id) == null) {
                double dx = p.getX() - (s.inside.getX() + 0.5), dz = p.getZ() - (s.inside.getZ() + 0.5);
                if (dx * dx + dz * dz > s.radius() * s.radius() || Math.abs(p.getY() - s.inside.getY()) > 4) {
                    escaped(level, v, p, t);
                    continue;
                }
            }
            // A meal when it is hungry, brought by a guard.
            if (!court && p.getFoodData().getFoodLevel() < 16 && now - t.getLong("meal") > Justice.pause(2400)) {
                t.putLong("meal", now);
                JCase c = Justice.get(t.getInt("case"));
                if (!Arrests.meal(level, v, p, c == null ? 0 : c.id)) {
                    p.getInventory().add(new ItemStack(Items.BREAD, 2));
                    p.displayClientMessage(Component.literal("A meal was passed through the bars.").withStyle(ChatFormatting.GRAY), true);
                }
            }
            hud(p, t);
        }
    }

    /** Out without leave: a charge of jail-break, a warrant, and its time waiting for it when it is back. */
    static void escaped(ServerLevel level, Villages.Village v, ServerPlayer p, CompoundTag t) {
        t.putBoolean("escaped", true);
        JCase main = Justice.get(t.getInt("case"));
        String name = Belonging.called(p);
        JCase c = Justice.open(level, v.id(), Justice.Charge.JAILBREAK, p.getUUID(), name, true, null, "the jail", false,
            Justice.placeOf(level, v, p.blockPosition()), p.blockPosition());
        c.evidence.add(new Justice.Ev("RECORD", "the jail's own book: " + name + " was in custody and is gone", 5.0, true));
        c.stage = Stage.REPORTED;
        c.notes.add("broke out" + (main == null ? "" : " while held for " + main.title()));
        if (main != null && main.stage == Stage.REMAND) Arrests.join(c, main);
        hud(p, null);
        Justice.notice(p, "Jail-break", "You have broken out of custody. That is a crime of its own: jail-break.",
            "The watch has a warrant out and will come for you. Your remaining time waits for you, and the court will add to it. Giving yourself up counts in your favour.");
        Police.log(v.id(), level.getDayTime(), "break", name + " (a player) broke out of custody", null, 0);
        Villages.tell(v.id(), level.getDayTime() / 24000L, name + " broke out of the jail");
        Justice.changed();
    }

    // ------------------------------------------------------------------ the HUD

    static void hud(ServerPlayer p, @Nullable CompoundTag t) {
        CompoundTag h = new CompoundTag();
        h.putString("kind", "hud");
        if (t == null) {
            h.putBoolean("on", false);
        } else {
            h.putBoolean("on", true);
            Spot s = Spot.load(t.getCompound("spot"));
            h.putString("where", s == null ? "custody" : s.words);
            h.putString("reason", t.getString("reason"));
            boolean remand = t.getBoolean("remand");
            h.putBoolean("remand", remand);
            double daySecs = JusticeConfig.jailDayMinutes() * 60.0;
            h.putDouble("left", t.getDouble("left"));
            h.putDouble("total", Math.max(1, t.getDouble("total")));
            h.putDouble("day", daySecs);
            JCase c = Justice.get(t.getInt("case"));
            h.putLong("court", c == null ? 0 : Math.max(0, (c.courtAt - p.level().getGameTime()) / 20));
            h.putBoolean("visiting", visiting(p.level().getDayTime()));
            h.putBoolean("incourt", t.getBoolean("court"));
        }
        PacketDistributor.sendToPlayer(p, new JusticePayload(h));
    }

    // ------------------------------------------------------------------ visitors

    /** Visiting hours: mid-morning to noon, and late afternoon. */
    static boolean visiting(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 2000 && t < 6000 || t >= 9000 && t < 11000;
    }

    static final String HOURS = "from nine till noon, and three till five";

    @SubscribeEvent
    public static void onUseBars(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer visitor) || !(e.getLevel() instanceof ServerLevel level)) return;
        BlockState st = level.getBlockState(e.getPos());
        if (!st.is(Blocks.IRON_BARS) && !st.is(Blocks.IRON_DOOR)) return;
        ServerPlayer prisoner = null;
        for (ServerPlayer p : level.players()) {
            if (p == visitor || !held(p.getUUID())) continue;
            if (p.blockPosition().distSqr(e.getPos()) <= 3.5 * 3.5) { prisoner = p; break; }
        }
        if (prisoner == null) return;
        e.setCanceled(true);
        ItemStack hand = visitor.getMainHandItem();
        if (!visiting(level.getDayTime())) {
            visitor.displayClientMessage(Component.literal("Visiting hours are " + HOURS + ". Come back then.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        if (hand.isEmpty()) {
            visitor.displayClientMessage(Component.literal("Visiting " + Belonging.called(prisoner) + ". Hold something to pass it through the bars (no weapons or tools).")
                .withStyle(ChatFormatting.GRAY), true);
            prisoner.displayClientMessage(Component.literal(Belonging.called(visitor) + " has come to visit you.").withStyle(ChatFormatting.GREEN), true);
            return;
        }
        if (hand.getItem() instanceof TieredItem || hand.getItem() instanceof SwordItem || hand.getItem() instanceof ArmorItem
                || hand.is(Items.BOW) || hand.is(Items.CROSSBOW) || hand.is(Items.TNT) || hand.is(Items.ENDER_PEARL) || hand.is(Items.FLINT_AND_STEEL)) {
            visitor.displayClientMessage(Component.literal("The guard won't let that through the bars.").withStyle(ChatFormatting.RED), true);
            return;
        }
        ItemStack pass = hand.split(Math.min(hand.getCount(), 16));
        if (!prisoner.getInventory().add(pass)) prisoner.drop(pass, false);
        visitor.displayClientMessage(Component.literal("You passed " + pass.getCount() + " " + pass.getHoverName().getString() + " to " + Belonging.called(prisoner) + ".")
            .withStyle(ChatFormatting.GREEN), true);
        prisoner.displayClientMessage(Component.literal(Belonging.called(visitor) + " passed you " + pass.getHoverName().getString() + " through the bars.")
            .withStyle(ChatFormatting.GREEN), true);
    }

    /** Breaking a held cell open: a jail-break of the breaker's own, if anybody sees it. */
    @SubscribeEvent
    public static void onBreakBars(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level) || Laws.exempt(p)) return;
        BlockState st = e.getState();
        if (!st.is(Blocks.IRON_BARS) && !(st.getBlock() instanceof DoorBlock)) return;
        boolean near = false;
        for (ServerPlayer o : level.players()) if (o != p && held(o.getUUID()) && o.blockPosition().distSqr(e.getPos()) <= 4 * 4) near = true;
        if (!near && !held(p.getUUID())) return;
        Villages.Village v = Villages.nearest(level, e.getPos(), Villages.VILLAGE_RANGE);
        if (v == null || held(p.getUUID())) return;                         // its own way out is the escape (tick)
        JCase c = Justice.open(level, v.id(), Justice.Charge.JAILBREAK, p.getUUID(), Belonging.called(p), true, null, "the jail", false,
            Justice.placeOf(level, v, e.getPos()), e.getPos());
        c.notes.add(Belonging.called(p) + " broke open a cell");
        Justice.witnesses(level, c, p, null, null);
        Justice.changed();
    }

    // ------------------------------------------------------------------ logged off, and back

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        CompoundTag t = custody(e.getEntity().getUUID());
        if (t == null) return;
        t.putLong("away", System.currentTimeMillis());
        Justice.changed();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        com.jrpetty.mcassistant.Guard.run("a prisoner back", () -> back(level, p));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        CompoundTag t = custody(p.getUUID());
        if (t == null || t.getBoolean("escaped")) return;
        Spot s = Spot.load(t.getCompound("spot"));
        if (s != null) p.teleportTo(level, s.inside.getX() + 0.5, s.inside.getY(), s.inside.getZ() + 0.5, p.getYRot(), p.getXRot());
    }

    /** Back from being away: what counted of its time credited; still owing, back in its cell; else free. */
    static void back(ServerLevel level, ServerPlayer p) {
        CompoundTag t = custody(p.getUUID());
        if (t == null) return;
        long away = t.getLong("away");
        t.remove("away");
        if (away > 0 && !t.getBoolean("remand") && !t.getBoolean("escaped")) {
            double secs = Math.max(0, (System.currentTimeMillis() - away) / 1000.0) * JusticeConfig.offlineRate();
            t.putDouble("left", t.getDouble("left") - secs);
            if (secs >= 1) p.sendSystemMessage(Component.literal("While you were away, " + (int) secs / 60 + " minutes of your sentence counted.").withStyle(ChatFormatting.GRAY));
        }
        t.putBoolean("court", false);
        if (t.getBoolean("free") || !t.getBoolean("remand") && t.getDouble("left") <= 0 && !t.getBoolean("escaped")) {
            release(level, p.getUUID(), t.getBoolean("free") ? t.getString("why") : "Your sentence was served while you were away.");
            return;
        }
        if (t.getBoolean("escaped")) return;
        Spot s = Spot.load(t.getCompound("spot"));
        if (s != null) {
            double dx = p.getX() - (s.inside.getX() + 0.5), dz = p.getZ() - (s.inside.getZ() + 0.5);
            if (dx * dx + dz * dz > s.radius() * s.radius()) p.teleportTo(s.inside.getX() + 0.5, s.inside.getY(), s.inside.getZ() + 0.5);
            if (s.door != null) WatchHouse.setDoor(level, s.door, false);
        }
        Justice.changed();
    }

    // ------------------------------------------------------------------ tests

    static void resetForTests() {}

    public static CompoundTag custodyForTests(UUID player) {
        CompoundTag t = custody(player);
        return t == null ? new CompoundTag() : t.copy();
    }

    /** Tests: the sentence's time all but run out. */
    public static void serveForTests(UUID player, double secondsLeft) {
        CompoundTag t = custody(player);
        if (t != null) t.putDouble("left", secondsLeft);
    }

    /** Tests: the jailhouse stamped here facing north and put on the town's books, as its builders would leave it. */
    public static Ledger.Building jailhouseForTests(ServerLevel level, Villages.Village v, BlockPos site) {
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, STRUCTURE, site, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), STRUCTURE, site, Direction.NORTH);
        Villages.builtAtForTests(v.id(), STRUCTURE, site);
        Ledger.Building b = of(v.id());
        if (b != null) fit(level, v, b);
        return b;
    }

    public static List<BlockPos> cellsForTests(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (Spot s : cells(b)) out.add(s.inside);
        return out;
    }

    public static boolean wantedForTests(UUID village) {
        return wanted(village);
    }

    public static boolean visitingForTests(long dayTime) {
        return visiting(dayTime);
    }

    public static void lockUpForTests(ServerLevel level, Villages.Village v, ServerPlayer p, int caseId) {
        JCase c = Justice.get(caseId);
        if (c != null) lockUp(level, v, p.getUUID(), c, null, null);
    }

    public static void backForTests(ServerLevel level, ServerPlayer p, long awayMillis) {
        CompoundTag t = custody(p.getUUID());
        if (t != null) t.putLong("away", System.currentTimeMillis() - awayMillis);
        back(level, p);
    }

}
