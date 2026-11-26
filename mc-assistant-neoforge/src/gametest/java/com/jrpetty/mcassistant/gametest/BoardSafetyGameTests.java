package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.CityStatsPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * [ui53] The village board never takes a player off the server, nor the server down: the town's books are made for any
 * town (none left in it, two hundred in it, odd names, folk with no trade, no house, no bed), always come out (a report
 * that fails leaves the essentials, not nothing), and always go over the wire and come back as they went: the real
 * codec, both ways, with the game's own reckoning of a tag's size, which a grown town's books went past (the client
 * could not read them, and the server dropped the player).
 *
 * <p>Each on its own ground, x 5300000 to 5306000 on z 53000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BoardSafetyGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 53000;
    /** The game's own budget for a tag read off the wire (FriendlyByteBuf.readNbt). */
    private static final long GAME_QUOTA = 2097152L;

    private static Villages.Village town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(24000L * 40 + 3000);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null && stood >= Math.min(n, 4), "a village of " + n + ": " + stood + " stood");
        return v;
    }

    private static List<VillageFolkEntity> folkOf(Villages.Village v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f) out.add(f);
        return out;
    }

    /** What a send and a read of the books came to: the bytes on the wire, the game's reckoning, the tag read back. */
    private record Wire(int bytes, long reckoned, CompoundTag back) {}

    /** The books through the real codec, both ways, as the server writes them and the client reads them. */
    private static Wire wire(ServerLevel level, CompoundTag books) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        CityStatsPayload.STREAM_CODEC.encode(buf, new CityStatsPayload(books));
        int bytes = buf.readableBytes();
        NbtAccounter count = NbtAccounter.unlimitedHeap();
        FriendlyByteBuf.readNbt(buf.copy(), count);
        CompoundTag back = CityStatsPayload.STREAM_CODEC.decode(buf).data();
        return new Wire(bytes, count.getUsage(), back);
    }

    /** Every string in the tag no longer than what the wire takes (and the codec cuts to). */
    private static int longest(Tag t) {
        if (t instanceof StringTag s) return s.getAsString().length();
        int most = 0;
        if (t instanceof CompoundTag c) for (String k : c.getAllKeys()) most = Math.max(most, longest(c.get(k)));
        if (t instanceof ListTag l) for (Tag in : l) most = Math.max(most, longest(in));
        return most;
    }

    private static void sound(GameTestHelper helper, String what, CompoundTag books, Wire w) {
        helper.assertTrue(!books.getString("board").startsWith("Some of the town's books could not be made"),
            what + ": every report made, none fell back: " + books.getString("board"));
        helper.assertTrue(!w.back().getBoolean(CityStatsPayload.UNREADABLE), what + ": the client reads the books back");
        helper.assertTrue(w.back().getString("name").equals(books.getString("name")), what + ": the same books come back");
        helper.assertTrue(w.back().getList("people", Tag.TAG_COMPOUND).size() == books.getList("people", Tag.TAG_COMPOUND).size(),
            what + ": every folk comes back");
        helper.assertTrue(longest(w.back()) <= CityStatsPayload.MOST_CHARS, what + ": no string too long for the wire: " + longest(w.back()));
        helper.assertTrue(w.reckoned() < CityStatsPayload.READ_QUOTA / 4, what + ": well inside the books' budget: " + w.reckoned());
    }

    /** A town with nobody left in it: its books still made, sent and read (the board of an empty town opens). */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ui53_board_empty")
    public static void ui53_board_empty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 5300000, 4);
        for (VillageFolkEntity f : folkOf(v)) f.discard();
        helper.assertTrue(folkOf(v).isEmpty(), "nobody left in it");
        CompoundTag books = Annals.snapshot(level, v);
        Wire w = wire(level, books);
        Kit.log("ui53a an empty town's books: " + w.bytes() + " bytes, reckoned " + w.reckoned() + "; people "
            + books.getList("people", Tag.TAG_COMPOUND).size() + "; board " + books.getString("board_title"));
        sound(helper, "an empty town", books, w);
        helper.assertTrue(books.getList("people", Tag.TAG_COMPOUND).isEmpty(), "nobody in its books");
        helper.succeed();
    }

    /**
     * Odd names and missing things: a town named with three hundred letters of every script, marks and a line break; a folk
     * with a name two hundred letters long; folk with no trade, no house, no bed (none has one yet); a child. Its books are made, every string
     * fits the wire, and a string past the wire's 64 KiB (which would fail the write and drop the player) is cut, not sent.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ui53_board_odd")
    public static void ui53_board_odd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 5302000, 6);
        StringBuilder name = new StringBuilder();
        String[] bits = { "Ærøskøbing ", "§k§lMagic§r ", "東京都 ", "Αθήνα ", "🏰👑 ", "line\nbreak ", "\"quoted\" ", "%s%d{} " };
        while (name.length() < 300) name.append(bits[name.length() % bits.length]);
        com.jrpetty.mcassistant.village.Ledger.note(v.id(), "name", name.toString());
        List<VillageFolkEntity> folk = folkOf(v);
        folk.get(0).rename("b".repeat(40) + "_" + "c".repeat(159));
        folk.get(1).setJob(StationTask.NONE);
        if (folk.size() > 3) folk.get(3).setChild(true);
        CompoundTag books = Annals.snapshot(level, v);
        Wire w = wire(level, books);
        Kit.log("ui53b odd names: " + w.bytes() + " bytes, reckoned " + w.reckoned() + "; name " + books.getString("name").length()
            + " letters; longest string " + longest(books));
        sound(helper, "odd names", books, w);
        helper.assertTrue(w.back().getString("name").equals(name.toString()), "the town's odd name comes back whole");
        boolean longName = false;
        ListTag people = w.back().getList("people", Tag.TAG_COMPOUND);
        for (int i = 0; i < people.size(); i++) longName |= people.getCompound(i).getString("name").length() >= 200;
        helper.assertTrue(longName, "the long-named folk comes back with its name");
        // A string past what the wire can take: cut, and the books still go.
        CompoundTag huge = books.copy();
        huge.putString("board", "東".repeat(70000));                     // three bytes each: 210 KB, past the 64 KiB a string may be
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("x".repeat(100000)));
        huge.put("news", list);
        Wire hw = wire(level, huge);
        helper.assertTrue(hw.back().getString("board").length() == CityStatsPayload.MOST_CHARS
            && hw.back().getList("news", Tag.TAG_STRING).getString(0).length() == CityStatsPayload.MOST_CHARS,
            "the overlong strings cut to the wire: " + hw.back().getString("board").length());
        helper.succeed();
    }

    /**
     * Two hundred folk and a year and more of books, every trade at work: the books are made, go over the wire and come
     * back, and their size is written down (the wire's bytes, the game's reckoning against its 2 MiB).
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ui53_board_huge")
    public static void ui53_board_huge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 5304000, 100);
        BlockPos heart = Kit.surface(level, 5304000, Z);
        for (int i = 100; i < 260 && folkOf(v).size() < 200; i++) {                  // past the growth cap of a hundred, as a city grows
            double angle = i * 2.399963229728653, reach = 1.5 + 1.1 * Math.sqrt(i);
            int fx = heart.getX() + (int) Math.round(Math.cos(angle) * reach), fz = heart.getZ() + (int) Math.round(Math.sin(angle) * reach);
            VillageFolkSpawnerBlock.raise(level, Kit.surface(level, fx, fz), 0.0F, 250);
        }
        List<VillageFolkEntity> folk = folkOf(v);
        helper.assertTrue(folk.size() >= 200, "two hundred folk in it: " + folk.size());
        StationTask[] tasks = StationTask.values();
        for (int i = 0; i < folk.size(); i++) {
            folk.get(i).setJob(tasks[i % tasks.length]);
            com.jrpetty.mcassistant.entity.Economy.produced(folk.get(i), new ItemStack(Items.BREAD, 1 + i % 5));
        }
        long day = level.getDayTime() / 24000L;
        for (int d = 0; d < Annals.KEEP + 10; d++) Annals.record(level, v, day + d);
        long t0 = System.nanoTime();
        CompoundTag books = Annals.snapshot(level, v);
        long made = (System.nanoTime() - t0) / 1_000_000L;
        Wire w = wire(level, books);
        Kit.log("ui53c two hundred folk (" + folk.size() + " stood), " + Annals.daysForTests(v.id()) + " days: made in " + made + " ms, "
            + w.bytes() + " bytes on the wire, reckoned " + w.reckoned() + " (the game's own budget " + GAME_QUOTA + ")");
        sound(helper, "two hundred folk", books, w);
        helper.assertTrue(books.getList("people", Tag.TAG_COMPOUND).size() >= Math.min(folk.size(), 150), "every folk in its books: "
            + books.getList("people", Tag.TAG_COMPOUND).size());
        helper.assertTrue(w.bytes() < 8 * 1024 * 1024, "under the game's 8 MiB a packet before it is split: " + w.bytes());
        helper.succeed();
    }

    /**
     * The codec itself: books the game's own 2 MiB budget would not have read (the player dropped) are read; books that
     * cannot be read at all come in marked so (the board says so) instead of throwing on the network thread.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "ui53_board_codec")
    public static void ui53_board_codec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag big = new CompoundTag();
        big.putString("name", "Bigton");
        ListTag people = new ListTag();
        for (int i = 0; i < 4000; i++) {                                         // more rows than any town has, each with its strings
            CompoundTag p = new CompoundTag();
            p.putString("name", "Folk number " + i);
            p.putString("trade", "Farmer");
            p.putString("why", "w".repeat(200));
            p.putInt("years", i % 80);
            people.add(p);
        }
        big.put("people", people);
        Wire w = wire(level, big);
        boolean oldFailed = false;
        RegistryFriendlyByteBuf again = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        CityStatsPayload.STREAM_CODEC.encode(again, new CityStatsPayload(big));
        try {
            FriendlyByteBuf.readNbt(again);
        } catch (RuntimeException e) {
            oldFailed = true;
        }
        Kit.log("ui53d past the game's budget: " + w.bytes() + " bytes, reckoned " + w.reckoned() + "; the game's own read failed: " + oldFailed);
        helper.assertTrue(w.reckoned() > GAME_QUOTA && oldFailed, "these books are past what the game's own read takes: " + w.reckoned());
        helper.assertTrue(!w.back().getBoolean(CityStatsPayload.UNREADABLE) && w.back().getList("people", Tag.TAG_COMPOUND).size() == 4000,
            "and are read whole all the same");
        // Rubbish where the books should be: marked unreadable, nothing thrown, nothing left in the buffer.
        RegistryFriendlyByteBuf bad = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        bad.writeByte(Tag.TAG_COMPOUND);
        bad.writeByte(Tag.TAG_STRING);
        bad.writeShort(5);
        bad.writeBytes(new byte[]{ 'n', 'a' });                                   // cut short
        CompoundTag read = CityStatsPayload.STREAM_CODEC.decode(bad).data();
        helper.assertTrue(read.getBoolean(CityStatsPayload.UNREADABLE) && bad.readableBytes() == 0, "rubbish read as unreadable books: " + read);
        helper.succeed();
    }

    /** A report that fails: the books still open, with the essentials and the Board page saying why, and still go over the wire. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ui53_board_fallback")
    public static void ui53_board_fallback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = town(helper, 5306000, 5);
        CompoundTag books = Annals.snapshotSafe(level, v, new IllegalStateException("a report failed (test)"));
        Wire w = wire(level, books);
        Kit.log("ui53e the essentials: " + books.getAllKeys() + "; board " + books.getString("board"));
        helper.assertTrue(!books.getString("name").isEmpty() && !books.getList("people", Tag.TAG_COMPOUND).isEmpty()
            && books.contains("now") && books.getString("board").contains("a report failed (test)"),
            "the essentials and why: " + books.getAllKeys());
        helper.assertTrue(!w.back().getBoolean(CityStatsPayload.UNREADABLE) && w.back().getString("name").equals(books.getString("name")),
            "and they go over the wire");
        helper.succeed();
    }
}
