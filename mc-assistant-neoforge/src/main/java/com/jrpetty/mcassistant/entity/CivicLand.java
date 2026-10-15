package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [civic-land] The town as a polity that taxes its people and honours its friends: what the council levies and how
 * the folk take it (TaxBook, TaxUnrest), and the players it knights, the land it grants them, the manor it raises and
 * the men it lends them (Knights, KnightManors, Retinue).
 *
 * <p>This class is what they share: the record kept with the world (a compound for each town, one for each player),
 * the round of the towns every five seconds, the folk's part (the collector's round and the strikers' picket, one hook
 * in its tick), what a player says to any folk about the taxes or the knighthood, the flags the talk screen needs, the
 * town's books (the Taxes and the Knights pages) and the clean slate for the tests.
 *
 * <p>Everything here is live in a world being played. The tests of the rest of the mod run in plain towns (Identity's
 * neutral towns), where no council taxes anybody and no unrest stirs, so a test of wages or of a market day measures
 * what it means to; this batch's own tests turn it on (liveForTests).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class CivicLand extends SavedData {

    private static final String ID = "mc_assistant_realm";
    /** The round of the towns: every five seconds, a beat after the town's affairs (Civics) so the two never share a tick. */
    static final int EVERY = 100;

    private CompoundTag root = new CompoundTag();
    @Nullable private static CivicLand loose;
    /** The tests of this batch: everything live in a plain town too. */
    private static volatile boolean liveForTests;

    public CivicLand() {}

    // ------------------------------------------------------------------ the record

    static CivicLand of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new CivicLand();
            return loose;
        }
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(CivicLand::new, CivicLand::load, null), ID);
    }

    /** A town's own compound (made the first time it is asked for). */
    static CompoundTag town(UUID village) {
        return sub(sub(of().root, "towns"), village.toString());
    }

    /** Is anything kept for this town? */
    static boolean known(UUID village) {
        return sub(of().root, "towns").contains(village.toString(), Tag.TAG_COMPOUND);
    }

    /** A player's own compound, across every town (its style of address, the notices waiting for it). */
    static CompoundTag player(UUID player) {
        return sub(sub(of().root, "players"), player.toString());
    }

    /** Every town with anything kept, by id. */
    static CompoundTag towns() {
        return sub(of().root, "towns");
    }

    static void changed() {
        of().setDirty();
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    /** The list kept under this key, itself (made the first time it is asked for), whatever it holds. */
    static ListTag list(CompoundTag t, String key) {
        if (!(t.get(key) instanceof ListTag)) t.put(key, new ListTag());
        return (ListTag) t.get(key);
    }

    public static CivicLand load(CompoundTag tag, HolderLookup.Provider registries) {
        CivicLand c = new CivicLand();
        c.root = tag.getCompound("Root");
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Root", root.copy());
        return tag;
    }

    /** Is the batch at work here: always in a world being played; in the tests only where a test of it asks. */
    public static boolean live() {
        return liveForTests || !Identity.neutral();
    }

    /** Tests: this test is about the taxes or the knights. */
    public static void liveForTests(boolean on) {
        liveForTests = on;
    }

    /** Everything forgotten (the tests share one world; Villages.resetForTests); as a world opens, only memory. */
    public static void resetForTests() {
        if (!com.jrpetty.mcassistant.SessionReset.opening()) {
            of().root = new CompoundTag();
            of().setDirty();
        }
        liveForTests = false;
        TaxBook.resetForTests();
        TaxUnrest.resetForTests();
        Knights.resetForTests();
        Retinue.resetForTests();
    }

    /** Tests: the record as a restart finds it (saved and read back). */
    public static void reloadForTests() {
        CivicLand c = of();
        CompoundTag saved = c.save(new CompoundTag(), null);
        c.root = load(saved, null).root;
    }

    // ------------------------------------------------------------------ the round of the towns

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % EVERY != 61) return;
        com.jrpetty.mcassistant.Guard.run("taxes and knights", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
            }
        });
    }

    /** One look at a town's taxes and its knights. */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (Villages.headcount(v.id()) < 1) return;
        if (live()) {
            com.jrpetty.mcassistant.Guard.run("the taxes", () -> TaxBook.tick(level, v));
            com.jrpetty.mcassistant.Guard.run("the unrest", () -> TaxUnrest.tick(level, v));
        }
        com.jrpetty.mcassistant.Guard.run("the knights", () -> Knights.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the retinues", () -> Retinue.tick(level, v));
    }

    // ------------------------------------------------------------------ the folk's part

    /**
     * From the folk's tick (VillageFolkEntity.aiStep), every few ticks: the tax collector on its round, a striker at
     * the picket before the hall. True while one of them has it in hand; its own day waits.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (!live() || f.isShowcase() || f.isHired() || f.ownerId() == null || !f.isAlive() || f.isBaby()) return false;
        if (TaxBook.collecting(f, level)) return true;
        return false;
    }

    // ------------------------------------------------------------------ what a player says

    /** Said to any folk about the taxes or the knighthood: its answer, or null when the words are not about them. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (text == null || text.isBlank() || f.isBaby()) return null;
        String low = text.toLowerCase(java.util.Locale.ROOT);
        String k = Knights.talk(f, p, low);
        if (k != null) return k;
        return TaxBook.talk(f, p, low);
    }

    /** What the talk screen needs to offer the right options (FolkTalk.talkContext): " knight", " taxowed". */
    public static String contextFlags(@Nullable UUID village, Player p) {
        if (village == null) return "";
        StringBuilder sb = new StringBuilder();
        if (Knights.isKnight(village, p.getUUID())) sb.append(" knight");
        if (TaxBook.owedBy(village, p.getUUID()) > 0) sb.append(" taxowed");
        return sb.toString();
    }

    // ------------------------------------------------------------------ the books

    /** The Taxes and the Knights pages of the town's books (Annals.snapshot): the town's side. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag out = new CompoundTag();
        out.putString("town", Villages.name(v.id()));
        out.putBoolean("live", live());
        out.put("taxes", TaxBook.report(level, v));
        out.put("unrest", TaxUnrest.report(level, v));
        out.put("knights", Knights.report(level, v));
        return out;
    }

    /** The board's lines (VillageBoards): the taxes and tax day, the town's temper at them, a protest or a revolt, its knights. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        if (!known(village) && !live()) return out;
        long day = day(level);
        if (live()) {
            long next = TaxBook.nextTaxDay(village, day);
            out.add("RN|Taxes: " + TaxBook.summary(village) + ". " + (next == day ? "Tax day today." : "Tax day: day " + next + "."));
            int u = TaxUnrest.level(village);
            if (TaxUnrest.revolt(village)) {
                CompoundTag tag = town(village);
                long strike = tag.getLong("strikeDay") - 1;
                out.add("RB|TAX REVOLT: " + list(tag, "angry").size() + " refuse to pay" + (strike >= day ? "; a strike on day " + strike : "") + ".");
            } else if (u >= TaxUnrest.GRUMBLE) {
                out.add("RW|The town is " + TaxUnrest.word(village) + " at its taxes (unrest " + u + ").");
            }
        }
        if (known(village)) {
            List<String> knights = new ArrayList<>();
            for (String k : Knights.knights(village).getAllKeys()) {
                UUID p = uuid(k);
                if (p != null) knights.add(Knights.style(p).word + " " + Knights.knights(village).getCompound(k).getString("name"));
            }
            if (!knights.isEmpty()) out.add("RG|Knights of the town: " + names(knights) + ".");
            for (String k : Knights.elect(village).getAllKeys()) {
                out.add("RG|To be knighted before the hall at dusk: " + Knights.elect(village).getCompound(k).getString("name") + ".");
            }
        }
        return out;
    }

    /** The viewer's own side (AssistantNetwork.sendCityStats): its service, its knighthood, what it owes. */
    public static void viewer(CompoundTag books, ServerLevel level, Villages.Village v, ServerPlayer p) {
        CompoundTag realm = books.getCompound("realm");
        realm.put("you", Knights.yours(level, v, p));
        books.put("realm", realm);
    }

    // ------------------------------------------------------------------ the smoke stage

    /**
     * The smoke stage (RealmCommands, /village realm stage): the operator knighted by the town it stands in (the banner's
     * makings put in the stores, by decree), its manor raised at once on its land with its banner by the door, the head
     * tax set at its heaviest, and the angry gathered before the hall to protest it, signs in hand. Each scene says where
     * to look from: "VIEW name eye target".
     */
    public static String stage(ServerLevel level, Villages.Village v, ServerPlayer p) {
        UUID id = v.id();
        liveForTests(true);
        List<String> out = new ArrayList<>();
        String name = p.getName().getString();
        if (!Citizens.is(id, p.getUUID())) com.jrpetty.mcassistant.village.Ledger.addCitizen(id, p.getUUID(), name);
        // The makings of two banners (the knight's and the one by its door) and of the protesters' signs.
        for (net.minecraft.world.item.Item it : new net.minecraft.world.item.Item[]{ net.minecraft.world.item.Items.WHITE_WOOL,
                net.minecraft.world.item.Items.YELLOW_DYE, net.minecraft.world.item.Items.STICK }) {
            TownWork.give(level, v, new net.minecraft.world.item.ItemStack(it, it == net.minecraft.world.item.Items.WHITE_WOOL ? 12 : 4));
        }
        Heraldry.Design town = Heraldry.design(id);
        if (town != null) {
            TownWork.give(level, v, new net.minecraft.world.item.ItemStack(Heraldry.bannerItem(town.field()), 2));
            for (Heraldry.Layer l : town.layers()) TownWork.give(level, v, new net.minecraft.world.item.ItemStack(Heraldry.dyeItem(l.colour()), 2));
        } else {
            TownWork.give(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHITE_BANNER, 2));
        }
        TownWork.give(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_SIGN, 12));
        out.add(Knights.isKnight(id, p.getUUID()) ? name + " is a knight already." : Knights.dub(level, v, p.getUUID(), name, false));
        out.add(KnightManors.buildNow(level, v, p.getUUID(), false));
        CompoundTag k = Knights.knight(id, p.getUUID());
        if (k != null && k.contains("plot")) {
            KnightManors.hangForTests(level, v, p.getUUID());
            net.minecraft.core.BlockPos plot = net.minecraft.core.BlockPos.of(k.getLong("plot"));
            net.minecraft.core.Direction back = net.minecraft.core.Direction.byName(k.getString("facing"));
            net.minecraft.core.Direction front = back == null ? net.minecraft.core.Direction.SOUTH : back.getOpposite();
            net.minecraft.core.BlockPos eye = plot.relative(front, 16).relative(front.getClockWise(), 6).above(6);
            out.add("VIEW manor " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + plot.getX() + " " + (plot.getY() + 4) + " " + plot.getZ());
        }
        TaxBook.set(level, v, TaxBook.Lever.HEAD, 3, "by decree");
        out.add(TaxUnrest.protestNow(level, v));
        Spot at = beforeTheHall(level, v);
        net.minecraft.core.BlockPos eye = at.focus().relative(at.audience(), 14).relative(at.audience().getClockWise(), 4).above(7);
        out.add("VIEW protest " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + at.focus().getX() + " " + (at.focus().getY() + 1) + " "
            + at.focus().getZ());
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ the town gone

    /** The town is no more (Villages.forget): its knights' titles lapse with it, its retinues are gone, its books shut. */
    public static void townGone(UUID village) {
        com.jrpetty.mcassistant.Guard.run("a town's knights", () -> Knights.townGone(village));
        if (known(village)) {
            towns().remove(village.toString());
            changed();
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Where the town gathers before its hall: two steps out from the meeting hall's door, else before the board, else the heart. */
    record Spot(net.minecraft.core.BlockPos focus, net.minecraft.core.Direction audience) {}

    static Spot beforeTheHall(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        com.jrpetty.mcassistant.village.Ledger.Building hall = Villages.builtStructure(id, "hall");
        net.minecraft.core.BlockPos door = hall != null && level.isLoaded(hall.anchor()) ? TownLife.fittings(hall).door() : null;
        if (door != null) {
            net.minecraft.core.Direction out = hall.facing().getOpposite();
            return new Spot(door.relative(out, 2), out);
        }
        net.minecraft.core.BlockPos board = VillageBoards.lectern(id);
        net.minecraft.core.Direction f = VillageBoards.facingOf(id);
        return new Spot(board != null ? board : v.centre(), f != null ? f : net.minecraft.core.Direction.SOUTH);
    }

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    static long time(ServerLevel level) {
        return level.getDayTime() % 24000L;
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "Ash", "Ash and Bree", "Ash, Bree and Cole". */
    static String names(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    static ListTag strings(List<String> lines) {
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s.length() > 300 ? s.substring(0, 300) : s));
        return l;
    }

    static List<String> read(ListTag l) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    @Nullable
    static UUID uuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A loaded folk of any town by its id, or null. */
    @Nullable
    static VillageFolkEntity find(ServerLevel level, @Nullable UUID id) {
        if (id == null) return null;
        return level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** The grown folk of a town that are loaded (not the showcase's stand-ins). */
    static List<VillageFolkEntity> grown(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase() && f.isAlive()) out.add(f);
        }
        return out;
    }

    /** A message to a player now if it is on, else kept for it till it next comes on (Knights.loggedIn). */
    static void tell(MinecraftServer server, UUID player, String text, ChatFormatting colour) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) {
            p.sendSystemMessage(Component.literal(text).withStyle(colour));
            return;
        }
        ListTag notes = list(player(player), "notices");
        notes.add(StringTag.valueOf(colour.getName() + "|" + text));
        while (notes.size() > 12) notes.remove(0);
        changed();
    }

    /** Tests: the notices kept for a player while it was away. */
    public static List<String> noticesForTests(UUID player) {
        return read(list(player(player), "notices"));
    }

    /** The notices kept for a player while it was away, sent now. */
    static void deliver(ServerPlayer p) {
        CompoundTag me = player(p.getUUID());
        ListTag notes = list(me, "notices");
        if (notes.isEmpty()) return;
        for (int i = 0; i < notes.size(); i++) {
            String s = notes.getString(i);
            int bar = s.indexOf('|');
            ChatFormatting c = bar > 0 ? ChatFormatting.getByName(s.substring(0, bar)) : null;
            p.sendSystemMessage(Component.literal(bar > 0 ? s.substring(bar + 1) : s).withStyle(c == null ? ChatFormatting.GOLD : c));
        }
        me.remove("notices");
        changed();
    }
}
