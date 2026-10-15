package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Archive;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Museum;
import com.jrpetty.mcassistant.entity.MuseumFront;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The museum and the archive (Museum, Archive): the first diamond in the stores is chosen for the
 * museum, taken out of the stores with a frame and a sign made for it, set out on the wall with its
 * label saying who found it and when, and the town's renown rises; the year's chronicle is written
 * into a book and quill made out of the stores' book, feather and ink sac, signed, and laid open on
 * the archive's lectern with the year's title and every line of it fitting the page; and the curator
 * does it on foot, the stores keeping the find back from the makers while it walks.
 *
 * <p>Each on its own ground in x 320000-327000, z 50000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MuseumGameTests {

    private static final String EMPTY = "empty";

    /** Nothing in the village's stores but what a test puts there. */
    private static void emptyStores(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
    }

    /** A marked store chest beside the heart, filled with these. */
    private static void stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
    }

    /** The museum, stamped on cleared, level ground (as the showcase stamps a building) and in the town's register. */
    private static Ledger.Building museum(ServerLevel level, UUID village, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, 0, -7), at.offset(7, 11, 7))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, -3, -7), at.offset(7, -1, 7))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        }
        BuildGoal.stamp(level, "museum", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "museum", at, Direction.NORTH);
        return new Ledger.Building("museum", at.immutable(), Direction.NORTH);
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** Every sign in the museum, its four lines joined. */
    private static List<String> signs(ServerLevel level, Ledger.Building b) {
        List<String> out = new ArrayList<>();
        BlockPos a = b.anchor();
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-6, -1, -6), a.offset(6, 3, 6))) {
            if (!(level.getBlockEntity(p) instanceof SignBlockEntity s)) continue;
            StringBuilder sb = new StringBuilder();
            for (Component c : s.getFrontText().getMessages(false)) sb.append(c.getString()).append(" | ");
            out.add(sb.toString());
        }
        return out;
    }

    private static String chronicle(UUID village) {
        StringBuilder sb = new StringBuilder();
        for (Chronicle.Entry e : Chronicle.of(village)) sb.append("[day ").append(e.day()).append("] ").append(e.text()).append('\n');
        return sb.toString();
    }

    // ============================================================ the first diamond

    /**
     * An Iron Age village with a museum standing, and in its stores the diamond its miner found, an
     * item frame and two signs: the curator asks for the diamond and takes it out of the stores with a
     * frame and a sign (nothing more), hangs the frame on the wall with the diamond in it, writes its
     * label (what, mined by whom, the miner, on what day), and the town's renown rises by the
     * diamond's three; the chronicle says so, and the museum's page in the books lists it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mu01_first_diamond_on_show")
    public static void mu01_first_diamond_on_show(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 320000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity miner = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(miner != null, "a village");
        UUID village = miner.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.IRON);
            emptyStores(level, village);
            Ledger.Building b = museum(level, village, Kit.surface(level, x + 24, z));
            stores(level, heart, 4, 0, new ItemStack(Items.DIAMOND), new ItemStack(Items.ITEM_FRAME), new ItemStack(Items.OAK_SIGN, 2));
            Villages.forgetStock();
            miner.setJob(StationTask.MINE);
            long day = level.getDayTime() / 24000L;
            Museum.found(miner, new ItemStack(Items.DIAMOND), 1);            // the miner's find, the moment it is in its hands
            MuseumRecords.Found first = MuseumRecords.firstFind(village, "diamond");
            Kit.log("mu01 the find noted: " + (first == null ? "none" : first.finder + " the " + first.trade + ", " + first.how + ", day " + first.day));
            helper.assertTrue(first != null && first.finder.equals(miner.displayNameCap()) && first.trade.equals("miner"),
                "the miner's diamond is noted the moment it is found, by whom and at what trade");
            int before = Villages.renown(village);
            Museum.instantForTests(true);
            try {
                for (int i = 0; i < 6 && !Museum.onShow(village, "diamond"); i++) Museum.tickForTests(level, v);
            } finally {
                Museum.instantForTests(false);
            }
            int after = Villages.renown(village);
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(b.anchor()).inflate(8),
                f -> f.getItem().is(Items.DIAMOND));
            List<String> labels = signs(level, b);
            Kit.log("mu01 on show: " + Museum.status(level, v));
            Kit.log("mu01 frames with a diamond: " + frames.size() + (frames.isEmpty() ? "" : ", its name: " + frames.get(0).getItem().getHoverName().getString()
                + ", at " + frames.get(0).blockPosition().toShortString() + " facing " + frames.get(0).getDirection()));
            Kit.log("mu01 labels: " + labels);
            Kit.log("mu01 renown " + before + " -> " + after + "; the stores: diamonds " + stock(level, village, s -> s.is(Items.DIAMOND))
                + ", frames " + stock(level, village, s -> s.is(Items.ITEM_FRAME)) + ", signs " + stock(level, village, s -> s.is(Items.OAK_SIGN)));
            Kit.log("mu01 the chronicle:\n" + chronicle(village));
            helper.assertTrue(Museum.onShow(village, "diamond"), "the first diamond went on show: " + Museum.status(level, v));
            helper.assertTrue(frames.size() == 1, "in a frame on the museum's wall");
            ItemFrame frame = frames.get(0);
            helper.assertTrue(Museum.inside(b, frame.blockPosition()) && frame.getDirection().getAxis().isHorizontal(),
                "the frame hangs inside the museum, on a wall: " + frame.blockPosition().toShortString());
            helper.assertTrue(frame.getItem().getHoverName().getString().contains(miner.displayNameCap()),
                "its name says who found it: " + frame.getItem().getHoverName().getString());
            String label = "";
            for (String l : labels) if (l.startsWith("Diamond")) label = l;
            helper.assertTrue(label.contains(miner.displayNameCap()) && label.contains("the miner") && label.contains("day " + day),
                "its label: what it is, who found it, at what trade and on what day: " + labels);
            helper.assertTrue(after == before + Museum.Kind.DIAMOND.renown && Museum.renown(village) == Museum.Kind.DIAMOND.renown,
                "the town's renown rose by the diamond's: " + before + " -> " + after);
            helper.assertTrue(stock(level, village, s -> s.is(Items.DIAMOND)) == 0, "the diamond came out of the stores");
            helper.assertTrue(stock(level, village, s -> s.is(Items.ITEM_FRAME)) == 0 && stock(level, village, s -> s.is(Items.OAK_SIGN)) == 1,
                "a frame and one sign out of the stores for it, and no more");
            String told = chronicle(village);
            helper.assertTrue(told.contains("first diamond went on show in the museum") && told.contains("mined the town's first diamond"),
                "the chronicle tells the find and the showing");
            var report = Museum.report(level, v);
            helper.assertTrue(report.getInt("renown_museum") == Museum.Kind.DIAMOND.renown && report.getList("shown", 10).size() == 1,
                "the books' Museum page lists it, and its renown: " + report);
            helper.succeed();
        });
    }

    // ============================================================ the year bound

    /**
     * A village a year old with a museum: the year's chronicle (forty lines of it) is written into a
     * book and quill the curator makes out of the stores' book, a feather and an ink sac, signed as
     * "The Chronicle of ..., Year 1" (or as much of it as a title holds) by the curator, and laid open
     * on the archive's lectern: a title page, then the year day by day, every page no more lines than a
     * book shows and every line no wider than its page.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "mu02_year_bound_on_the_lectern")
    public static void mu02_year_bound_on_the_lectern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 323000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        UUID village = folk.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            emptyStores(level, village);
            Ledger.Building b = museum(level, village, Kit.surface(level, x + 24, z));
            stores(level, heart, 4, 0, new ItemStack(Items.BOOK), new ItemStack(Items.FEATHER, 8), new ItemStack(Items.INK_SAC));
            Villages.forgetStock();
            long founded = Chronicle.foundedOn(village);
            if (founded < 0) {
                Villages.tell(village, level.getDayTime() / 24000L, Villages.name(village) + " was founded");
                founded = Chronicle.foundedOn(village);
            }
            String[] nth = { "first", "second", "third", "fourth" };
            for (int d = 0; d < Archive.YEAR_DAYS; d++) {
                for (String n : nth) {
                    Villages.tell(village, founded + d, "on day " + (founded + d) + " the " + n + " thing happened in the village, and everybody "
                        + "talked about it for a good while afterwards");
                }
            }
            long day = founded + Archive.YEAR_DAYS + 1;
            while (RestDay.today(village, day)) day++;
            level.setDayTime(day * 24000L + 6000L);
            Museum.instantForTests(true);
            try {
                for (int i = 0; i < 6 && MuseumRecords.book(village).volumes.isEmpty(); i++) Museum.tickForTests(level, v);
            } finally {
                Museum.instantForTests(false);
            }
            MuseumRecords.Book book = MuseumRecords.book(village);
            BlockPos lecternAt = Museum.at(b, 0, 0, 3);
            ItemStack open = level.getBlockEntity(lecternAt) instanceof LecternBlockEntity l ? l.getBook() : ItemStack.EMPTY;
            WrittenBookContent c = open.get(DataComponents.WRITTEN_BOOK_CONTENT);
            Kit.log("mu02 the museum: " + Museum.status(level, v));
            Kit.log("mu02 on the lectern at " + lecternAt.toShortString() + ": " + (c == null ? open : c.title().raw() + " by " + c.author()
                + ", " + c.pages().size() + " pages, generation " + c.generation()));
            helper.assertTrue(c != null && open.is(Items.WRITTEN_BOOK), "a written book lies open on the archive's lectern: " + open);
            String expected = Archive.title(Villages.name(village), 1, 1, 1);
            String title = c.title().raw();
            helper.assertTrue(title.equals(expected) && title.contains("Year 1") && title.length() <= WrittenBookContent.TITLE_MAX_LENGTH,
                "titled for the year: '" + title + "', wanted '" + expected + "'");
            helper.assertTrue(c.author().equals(book.curatorName) && !c.author().isEmpty(), "by the curator: " + c.author());
            List<String> pages = new ArrayList<>();
            for (Filterable<Component> p : c.pages()) pages.add(p.raw().getString());
            for (int i = 0; i < Math.min(3, pages.size()); i++) Kit.log("mu02 page " + (i + 1) + ":\n" + pages.get(i));
            helper.assertTrue(pages.size() >= 3 && pages.size() <= Archive.MOST_PAGES, "a title page and the year over several pages: " + pages.size());
            helper.assertTrue(pages.get(0).contains("Year 1") && pages.get(0).replace('\n', ' ').contains(Villages.name(village)),
                "the title page names the town and the year");
            for (String p : pages) {
                String[] lines = p.split("\n", -1);
                helper.assertTrue(lines.length <= Archive.LINES, "no page holds more lines than a book shows: " + lines.length + " in\n" + p);
                for (String line : lines) {
                    helper.assertTrue(Archive.px(line) <= Archive.PAGE_PX, "no line runs off the page: '" + line + "' is " + Archive.px(line));
                }
            }
            String all = String.join(" ", pages).replace('\n', ' ');
            helper.assertTrue(all.contains("On day " + (founded + 3) + " the third thing happened in the village, and everybody talked about it for a good while afterwards."),
                "the year's entries are in it, word for word");
            helper.assertTrue(all.contains("Day " + (founded + Archive.YEAR_DAYS - 1) + "§r") && !all.contains("Day " + (founded + Archive.YEAR_DAYS) + "§r"),
                "the whole year, and only the year");
            helper.assertTrue(book.volumes.size() == 1 && book.volumes.get(0).where.equals("lectern") && book.volumes.get(0).year == 1,
                "the archive's books have it on the lectern");
            helper.assertTrue(stock(level, village, s -> s.is(Items.BOOK)) == 0 && stock(level, village, s -> s.is(Items.INK_SAC)) == 0
                    && stock(level, village, s -> s.is(Items.FEATHER)) == 7,
                "the book and quill was made of the stores' book, an ink sac and one feather");
            helper.assertTrue(chronicle(village).contains("Year 1 of the chronicle was written up"), "and the chronicle says so");
            helper.succeed();
        });
    }

    // ============================================================ the front

    /**
     * The museum's front: built to the drawing, its hall stands up the stair behind the portico (the double
     * door and the lectern where the museum looks for them), and out of the stores' signs and two banners in
     * the town's colours its name goes up over the door ("The Museum | of ...") and a banner either side of
     * it, all facing the street. Two signs and the two banners are taken, and nothing more; nothing is made
     * out of nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "mu04_the_museum_front")
    public static void mu04_the_museum_front(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(6000);
        int x = 324500, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        UUID village = folk.ownerId();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.IRON);
            emptyStores(level, village);
            Ledger.Building b = museum(level, village, Kit.surface(level, x + 24, z));
            DyeColor colour = MuseumFront.colour(village);
            Item banner = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(colour.getName() + "_banner"));
            stores(level, heart, 4, 0, new ItemStack(Items.OAK_SIGN, 3), new ItemStack(banner, 2));
            Villages.forgetStock();
            // The hall up the stair, behind the portico: its door and its lectern where the museum looks for them.
            helper.assertTrue(level.getBlockState(Museum.at(b, 0, 0, -4)).getBlock() instanceof DoorBlock
                && level.getBlockState(Museum.at(b, 1, 0, -4)).getBlock() instanceof DoorBlock,
                "the double door stands up the stair, behind the portico: " + level.getBlockState(Museum.at(b, 0, 0, -4)));
            helper.assertTrue(level.getBlockState(Museum.at(b, 0, 0, 3)).is(Blocks.LECTERN), "the lectern stands at the back of the hall");
            TownJobs.instantForTests(true);
            try {
                MuseumFront.putForTests(level, v, b);
            } finally {
                TownJobs.instantForTests(false);
            }
            Direction out = b.facing().getOpposite();
            String town = Villages.name(village);
            String[] read = new String[2];
            for (int i = 0; i < 2; i++) {
                BlockPos at = Museum.at(b, i, 3, -5);
                BlockState st = level.getBlockState(at);
                StringBuilder sb = new StringBuilder();
                if (level.getBlockEntity(at) instanceof SignBlockEntity s) {
                    for (Component c : s.getFrontText().getMessages(false)) sb.append(c.getString()).append(" | ");
                }
                read[i] = sb.toString();
                helper.assertTrue(st.getBlock() instanceof WallSignBlock && st.getValue(WallSignBlock.FACING) == out,
                    "a sign over the door, facing the street: " + st);
            }
            Kit.log("mu04 the name over the door: [" + read[0] + "] [" + read[1] + "] for " + town + "; the colours " + colour.getName()
                + "; the stores: signs " + stock(level, village, s -> s.is(Items.OAK_SIGN)) + ", banners " + stock(level, village, s -> s.is(banner)));
            helper.assertTrue(read[0].contains("The Museum") && read[1].contains("of")
                && read[1].contains(town.substring(0, Math.min(4, town.length()))), "the sign reads The Museum of " + town + ": " + read[0] + read[1]);
            for (int[] c : new int[][]{ { -3, 4, -5 }, { 4, 4, -5 } }) {
                BlockState st = level.getBlockState(Museum.at(b, c[0], c[1], c[2]));
                helper.assertTrue(st.getBlock() instanceof WallBannerBlock wb && wb.getColor() == colour && st.getValue(WallBannerBlock.FACING) == out,
                    "a banner in the town's colours (" + colour.getName() + ") either side of the door, facing the street: " + st);
            }
            helper.assertTrue(stock(level, village, s -> s.is(Items.OAK_SIGN)) == 1 && stock(level, village, s -> s.is(banner)) == 0,
                "two signs and the two banners out of the stores, and no more");
            helper.succeed();
        });
    }

    // ============================================================ on foot

    /**
     * The same as the first, by hand: a curator (not the village's lead builder) asks for the nautilus
     * shell the fisher brought home, the stores keep it back from the makers while it walks, it fetches
     * the shell and a frame and a sign out of the stores, carries them to the museum and hangs it up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4800, batch = "mu03_curator_carries_it_over")
    public static void mu03_curator_carries_it_over(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(5000);
        int x = 326000, z = 50000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.assertTrue(first != null && second != null, "a village of two");
        UUID village = first.ownerId();
        Museum.instantForTests(false);
        Map<String, Object> seen = new HashMap<>();
        helper.runAtTickTime(10, () -> {
            Villages.ageForTests(village, Villages.Age.IRON);
            emptyStores(level, village);
            Ledger.Building b = museum(level, village, Kit.surface(level, x + 20, z - 4));
            // Plenty of signs: the town's own works (house and street signs) may take a few while it walks.
            stores(level, heart, 4, 0, new ItemStack(Items.NAUTILUS_SHELL), new ItemStack(Items.ITEM_FRAME), new ItemStack(Items.OAK_SIGN, 16));
            Villages.forgetStock();
            // The curator: whichever of the two is not leading a build just now; the other is the fisher.
            boolean firstLeads = Villages.holdsTheLead(village, first.getUUID(), level.getGameTime());
            VillageFolkEntity curator = firstLeads ? second : first, fisher = firstLeads ? first : second;
            seen.put("curator", curator);
            seen.put("fisher", fisher);
            fisher.setJob(StationTask.FISH);
            Museum.found(fisher, new ItemStack(Items.NAUTILUS_SHELL), 1);
            Museum.appointForTests(village, curator);
            Museum.tickForTests(level, Villages.get(village));      // the museum's beat now (then the folk's own, on foot)
            seen.put("museum", b);
            Kit.log("mu03 the curator is " + curator.displayNameCap() + ", the finder " + fisher.displayNameCap()
                + "; the museum at " + b.anchor().toShortString() + "; now: " + Museum.doing(village));
        });
        helper.onEachTick(() -> {
            if (!seen.containsKey("museum")) return;
            Ledger.Building b = (Ledger.Building) seen.get("museum");
            VillageFolkEntity curator = (VillageFolkEntity) seen.get("curator");
            VillageFolkEntity fisher = (VillageFolkEntity) seen.get("fisher");
            Villages.Village v = Villages.get(village);
            long t = level.getGameTime();
            String doing = Museum.doing(village);
            if (doing != null && !seen.containsKey("doing")) {
                seen.put("doing", doing);
                Kit.log("mu03 the errand: " + doing + " at " + curator.blockPosition().toShortString());
            }
            // While it is asked for and still in the stores, the makers may not have it.
            if (doing != null && !seen.containsKey("kept") && stock(level, village, s -> s.is(Items.NAUTILUS_SHELL)) > 0 && v != null) {
                Map<Item, String> why = new HashMap<>();
                Map<Item, int[]> book = Bench.keepBook(level, v, why);
                int[] row = book.get(Items.NAUTILUS_SHELL);
                if (row != null) {
                    seen.put("kept", why.getOrDefault(Items.NAUTILUS_SHELL, "") + " (free " + row[1] + " of " + row[0] + ")");
                    Kit.log("mu03 the stores' keep-book for the shell: " + seen.get("kept"));
                }
            }
            if (t % 200 == 0) {
                Kit.log("mu03 @" + t + " curator at " + curator.blockPosition().toShortString() + " doing " + doing
                    + "; shells in the stores " + stock(level, village, s -> s.is(Items.NAUTILUS_SHELL)) + "; " + curator.debugLine());
            }
            if (!Museum.onShow(village, "nautilus")) return;
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(b.anchor()).inflate(8),
                f -> f.getItem().is(Items.NAUTILUS_SHELL));
            List<String> labels = signs(level, b);
            Kit.log("mu03 on show after " + t + ": " + Museum.status(level, v) + "; labels " + labels);
            helper.assertTrue(frames.size() == 1, "the shell hangs in a frame in the museum");
            helper.assertTrue(stock(level, village, s -> s.is(Items.NAUTILUS_SHELL)) == 0, "out of the stores");
            String kept = (String) seen.getOrDefault("kept", "");
            helper.assertTrue(kept.startsWith("kept for the museum") && kept.contains("free 0"),
                "while it walked the stores kept the shell back from the makers: " + kept);
            boolean named = false;
            for (String l : labels) if (l.contains(fisher.displayNameCap()) && l.contains("the fisher")) named = true;
            helper.assertTrue(named, "its label names the fisher who brought it up: " + labels);
            helper.assertTrue(Museum.renown(village) == Museum.Kind.NAUTILUS.renown, "the town's renown rose by the shell's");
            helper.succeed();
        });
    }
}
