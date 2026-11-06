package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [perks] The wonders of the world: the top of each branch of the town's research (CityTree), each a real
 * building of its own, and only one of each in the whole world.
 *
 * <p><b>The plans.</b> A town that has studied a wonder's civic has its plans. From then on, each morning, it
 * looks to its stores for the wonder's dues: the special goods it is raised with on top of the stone and timber
 * every building wants (the Great Forge's iron, coal and gold; the Grand Library's paper and books; the Grand
 * Bazaar's coin and wool...). When the stores hold all of them, they are taken out together and laid by
 * (written in the chronicle and told at the assembly), and the wonder goes on the town's list of what to build
 * (Villages.projectsWanted), after the houses and the age's own buildings, on a lot of its own kind (a long lot
 * by the square for most; the edge of town for the lighthouse and the gate; the square itself for the Colossus).
 * Its builders raise it out of the stores like anything else, from its drawing (blueprints/&lt;name&gt;.txt).
 *
 * <p><b>The first to finish.</b> When the last block of a wonder is laid (Villages.noteProject), the town that
 * laid it holds it, and that is written with the world (here): the town, the day, the place. Every other town
 * hears of it ("word came that Ashford has raised the Grand Library"); its civic is closed to them all from
 * then on (CityTree.locked), a town that had laid its dues by has them put back into its stores, and a town
 * whose builders were half way up it stops where it is: a fine building, but no wonder. Every player in the
 * world is told. The wonder adds thirty to its town's renown (Villages.renown) and its big perk works for as
 * long as the town holds it (CityTree, Perks).
 *
 * <p>Shown on the Research page (each wonder's node: its plans, its dues, going up, ours, or lost), the Perks
 * page (the world's wonders and who holds each), the board, the chronicle and the gazette, and /village perks
 * wonders.
 */
public final class Wonders extends SavedData {

    private static final String ID = "mc_assistant_wonders";

    /** What a wonder adds to its town's renown. */
    public static final int RENOWN = 30;

    /** One of a wonder's dues: so many of what matches, in words. A coin due is the treasury's ({@code coins}). */
    public record Due(String words, Predicate<ItemStack> what, int n, boolean coins) {
        static Due of(String words, Predicate<ItemStack> what, int n) {
            return new Due(words, what, n, false);
        }

        static Due coins(int n) {
            return new Due("coins from the treasury", s -> false, n, true);
        }
    }

    public enum Wonder {
        GREAT_FORGE(CityTree.Civic.GREAT_FORGE, "greatforge", "the Great Forge", "great",
            Due.of("iron ingots", s -> s.is(Items.IRON_INGOT), 48), Due.of("coal", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 32),
            Due.of("gold ingots", s -> s.is(Items.GOLD_INGOT), 8)),
        SKY_GARDEN(CityTree.Civic.SKY_GARDEN, "skygarden", "the Sky Garden", "great",
            Due.of("dirt", s -> s.is(Items.DIRT), 64), Due.of("saplings", s -> s.is(ItemTags.SAPLINGS), 16),
            Due.of("bone meal", s -> s.is(Items.BONE_MEAL), 24)),
        CLOCKWORK_GATE(CityTree.Civic.CLOCKWORK_GATE, "clockworkgate", "the Clockwork Gate", "edge",
            Due.of("redstone", s -> s.is(Items.REDSTONE), 32), Due.of("iron ingots", s -> s.is(Items.IRON_INGOT), 16),
            Due.of("gold ingots", s -> s.is(Items.GOLD_INGOT), 8)),
        FOUNDERS_COLOSSUS(CityTree.Civic.FOUNDERS_COLOSSUS, "colossus", "the Founders' Colossus", "monument",
            Due.of("gold ingots", s -> s.is(Items.GOLD_INGOT), 16), Due.of("diamonds", s -> s.is(Items.DIAMOND), 4)),
        GRAND_BAZAAR(CityTree.Civic.GRAND_BAZAAR, "grandbazaar", "the Grand Bazaar", "great",
            Due.coins(150), Due.of("wool", s -> s.is(ItemTags.WOOL), 24)),
        ARENA(CityTree.Civic.ARENA, "arena", "the Arena", "great",
            Due.of("sand", s -> s.is(Items.SAND), 64), Due.of("iron ingots", s -> s.is(Items.IRON_INGOT), 16)),
        GRAND_LIBRARY(CityTree.Civic.GRAND_LIBRARY, "grandlibrary", "the Grand Library", "great",
            Due.of("paper", s -> s.is(Items.PAPER), 32), Due.of("books", s -> s.is(Items.BOOK), 12)),
        CATHEDRAL(CityTree.Civic.CATHEDRAL, "cathedral", "the Cathedral", "great",
            Due.of("gold ingots", s -> s.is(Items.GOLD_INGOT), 12), Due.of("glass", s -> s.is(Items.GLASS), 32)),
        GREAT_LIGHTHOUSE(CityTree.Civic.GREAT_LIGHTHOUSE, "greatlighthouse", "the Great Lighthouse", "edge",
            Due.of("glass", s -> s.is(Items.GLASS), 32), Due.of("coal", s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 32),
            Due.of("iron ingots", s -> s.is(Items.IRON_INGOT), 8)),
        OBSERVATORY(CityTree.Civic.OBSERVATORY, "observatory", "the Observatory", "civic",
            Due.of("lapis lazuli", s -> s.is(Items.LAPIS_LAZULI), 24), Due.of("diamonds", s -> s.is(Items.DIAMOND), 4),
            Due.of("books", s -> s.is(Items.BOOK), 8));

        public final CityTree.Civic civic;
        /** The drawing's name, and the building's on the town's books. */
        public final String structure;
        /** "the Great Forge". */
        public final String name;
        /** The kind of lot it wants (TownPlan.placeFor). */
        public final String place;
        public final List<Due> dues;

        Wonder(CityTree.Civic civic, String structure, String name, String place, Due... dues) {
            this.civic = civic;
            this.structure = structure;
            this.name = name;
            this.place = place;
            this.dues = List.of(dues);
        }

        /** "The Great Forge". */
        public String title() {
            return CityTree.capital(name);
        }

        /** "48 iron ingots, 32 coal and 8 gold ingots". */
        public String duesWords() {
            List<String> out = new ArrayList<>();
            for (Due d : dues) out.add(d.n() + " " + d.words());
            return JobMarket.join(out);
        }
    }

    @Nullable
    public static Wonder of(@Nullable CityTree.Civic c) {
        if (c == null) return null;
        for (Wonder w : Wonder.values()) if (w.civic == c) return w;
        return null;
    }

    /** The wonder raised from this drawing, or null. */
    @Nullable
    public static Wonder byStructure(@Nullable String structure) {
        if (structure == null) return null;
        for (Wonder w : Wonder.values()) if (w.structure.equals(structure)) return w;
        return null;
    }

    /** Is this the name of a wonder's drawing? */
    public static boolean isWonder(@Nullable String structure) {
        return byStructure(structure) != null;
    }

    // ------------------------------------------------------------------ the world's book

    /** Who holds each wonder: by the wonder's key, the town's id and name, the day and the place. */
    private CompoundTag held = new CompoundTag();
    @Nullable private static Wonders loose;

    public Wonders() {}

    static Wonders book() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Wonders();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Wonders::new, Wonders::load, null), ID);
    }

    public static Wonders load(CompoundTag tag, HolderLookup.Provider registries) {
        Wonders w = new Wonders();
        w.held = tag.getCompound("held");
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("held", held.copy());
        return tag;
    }

    /** Who holds it: {town id, its name, the day, x, y, z}, or null while no town in the world has raised it. */
    public record Claim(UUID village, String town, long day, BlockPos at) {}

    @Nullable
    public static Claim claim(Wonder w) {
        CompoundTag all = book().held;
        if (!all.contains(w.name())) return null;
        CompoundTag t = all.getCompound(w.name());
        try {
            return new Claim(UUID.fromString(t.getString("village")), t.getString("town"), t.getLong("day"),
                new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void hold(Wonder w, UUID village, long day, @Nullable BlockPos at) {
        Wonders b = book();
        CompoundTag t = new CompoundTag();
        t.putString("village", village.toString());
        t.putString("town", Villages.name(village));
        t.putLong("day", day);
        BlockPos p = at == null ? BlockPos.ZERO : at;
        t.putInt("x", p.getX());
        t.putInt("y", p.getY());
        t.putInt("z", p.getZ());
        b.held.put(w.name(), t);
        b.setDirty();
    }

    /** Does this town hold the wonder of this civic (it raised it first in the world)? */
    public static boolean owns(@Nullable UUID village, CityTree.Civic c) {
        Wonder w = of(c);
        if (village == null || w == null) return false;
        Claim k = claim(w);
        return k != null && k.village().equals(village);
    }

    /** Has another town in the world raised the wonder of this civic? */
    public static boolean raisedElsewhere(@Nullable UUID village, CityTree.Civic c) {
        Wonder w = of(c);
        if (w == null) return false;
        Claim k = claim(w);
        return k != null && !k.village().equals(village);
    }

    /** The name of the town that holds it, or "". */
    public static String ownerName(CityTree.Civic c) {
        Wonder w = of(c);
        Claim k = w == null ? null : claim(w);
        return k == null ? "" : k.town();
    }

    /** The wonders this town holds. */
    public static List<Wonder> held(@Nullable UUID village) {
        List<Wonder> out = new ArrayList<>();
        if (village == null) return out;
        for (Wonder w : Wonder.values()) {
            Claim k = claim(w);
            if (k != null && k.village().equals(village)) out.add(w);
        }
        return out;
    }

    /** Its renown from its wonders (Villages.renown): thirty each. */
    public static int renown(@Nullable UUID village) {
        return RENOWN * held(village).size();
    }

    /**
     * Forget every claim (Perks.resetForTests, between tests only: never as a world opens, when the claims are the
     * world's own history).
     */
    static void wipeForTests() {
        Wonders b = book();
        b.held = new CompoundTag();
        b.setDirty();
    }

    // ------------------------------------------------------------------ the plans and the dues

    private static String paidKey(Wonder w) {
        return "wonder.paid." + w.structure;
    }

    /** Has the town laid the wonder's dues by (and not had them back)? */
    public static boolean paid(UUID village, Wonder w) {
        String s = Ledger.note(village, paidKey(w));
        return s != null && !s.isEmpty();
    }

    /** The plans drawn (CityTree.complete): the town is told what the wonder will want of its stores. */
    static void planned(ServerLevel level, UUID village, CityTree.Civic c, long day) {
        Wonder w = of(c);
        if (w == null) return;
        Villages.tell(village, day, "the plans for " + w.name + " are drawn: the town will raise it once the stores hold its dues, "
            + w.duesWords());
        Market.assemblyNews(village, "We have the plans for " + w.name + "! Fill the stores with " + w.duesWords()
            + ", and we'll raise it: the only one in the world, if we're first.");
        Reigns.deed(village, Reigns.Deed.WONDER, 1);
        Villages.Village v = Villages.get(village);
        if (v != null) morningFor(level, v, w, day);
    }

    /**
     * The town's morning look at its wonders (Perks.morning): the dues laid by for any whose plans it has, when the
     * stores hold them all; and the dues given back to the stores of one another town has raised first.
     */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        for (Wonder w : Wonder.values()) if (CityTree.has(v.id(), w.civic)) morningFor(level, v, w, day);
    }

    private static void morningFor(ServerLevel level, Villages.Village v, Wonder w, long day) {
        UUID id = v.id();
        Claim k = claim(w);
        if (k != null) {
            if (!k.village().equals(id) && paid(id, w)) refund(level, v, w, k, day);
            return;
        }
        if (paid(id, w)) return;
        String shortOf = shortOf(level, id, w);
        if (!shortOf.isEmpty()) {
            Ledger.note(id, "wonder.short." + w.structure, shortOf);
            return;
        }
        if (!takeDues(level, v, w)) return;
        Ledger.forget(id, "wonder.short." + w.structure);
        Ledger.note(id, paidKey(w), Long.toString(day));
        Villages.tell(id, day, "the town laid by " + w.duesWords() + " for " + w.name + ", and its builders begin");
        Market.assemblyNews(id, "The dues for " + w.name + " are laid by. Now we build it — let's be the first in the world!");
    }

    /** What the stores are short of for a wonder's dues: "12 iron ingots, 3 gold ingots", or "". */
    static String shortOf(ServerLevel level, UUID village, Wonder w) {
        List<String> out = new ArrayList<>();
        for (Due d : w.dues) {
            int have = d.coins() ? Ledger.coins(village) : Market.stock(level, village, d.what());
            if (have < d.n()) out.add((d.n() - have) + " " + d.words());
        }
        return String.join(", ", out);
    }

    /** All the dues out of the stores (and the treasury), or none of them. */
    private static boolean takeDues(ServerLevel level, Villages.Village v, Wonder w) {
        List<Due> taken = new ArrayList<>();
        for (Due d : w.dues) {
            boolean ok = d.coins() ? Ledger.coins(v.id()) >= d.n() && Ledger.takeCoins(v.id(), d.n()) == d.n()
                : Crafts.take(level, v, d.what(), d.n());
            if (!ok) {
                for (Due back : taken) giveBack(level, v, back);
                return false;
            }
            if (d.coins()) Economy.spent(v.id(), d.n());
            taken.add(d);
        }
        return true;
    }

    /** One due put back where it came from: the coin into the treasury, the goods into the stores. */
    private static void giveBack(ServerLevel level, Villages.Village v, Due d) {
        if (d.coins()) {
            Ledger.addCoins(v.id(), d.n());
            return;
        }
        ItemStack one = sample(d);
        if (one.isEmpty()) return;
        int left = d.n();
        while (left > 0) {
            int n = Math.min(left, one.getMaxStackSize());
            Crafts.store(level, v, one.copyWithCount(n));
            left -= n;
        }
    }

    /** A stack of what a due is (the first item of the game that matches it), for giving it back. */
    private static ItemStack sample(Due d) {
        for (net.minecraft.world.item.Item it : List.of(Items.IRON_INGOT, Items.COAL, Items.GOLD_INGOT, Items.DIRT, Items.OAK_SAPLING,
                Items.BONE_MEAL, Items.REDSTONE, Items.DIAMOND, Items.WHITE_WOOL, Items.SAND, Items.PAPER, Items.BOOK, Items.GLASS,
                Items.LAPIS_LAZULI)) {
            ItemStack s = new ItemStack(it);
            if (d.what().test(s)) return s;
        }
        return ItemStack.EMPTY;
    }

    /** Another town raised it first: the dues back into the stores, and the town told. */
    private static void refund(ServerLevel level, Villages.Village v, Wonder w, Claim k, long day) {
        for (Due d : w.dues) giveBack(level, v, d);
        Ledger.forget(v.id(), paidKey(w));
        Villages.tell(v.id(), day, k.town() + " raised " + w.name + " first; the dues laid by for ours went back into the stores");
    }

    /**
     * Its wonders that are wanted on the town's list of buildings (Villages.projectsWanted): those whose dues are
     * laid by, not yet up, and not raised by another town. After everything else the town wants, but before the
     * next great work of a town past its last age.
     */
    public static List<String> wanted(UUID village, List<String> out) {
        List<String> add = new ArrayList<>();
        for (Wonder w : Wonder.values()) {
            if (!CityTree.has(village, w.civic) || !paid(village, w) || claim(w) != null) continue;
            if (Villages.hasBuilt(village, w.structure) || out.contains(w.structure)) continue;
            add.add(w.structure);
        }
        if (add.isEmpty()) return out;
        List<String> all = new ArrayList<>(out);
        int at = all.size();
        if (at > 0 && Villages.GREAT_WORKS.contains(all.get(at - 1))) at--;
        all.addAll(at, add);
        return all;
    }

    /** Why the town builds it (Villages.whyBuild). */
    @Nullable
    public static String why(String structure) {
        Wonder w = byStructure(structure);
        if (w == null) return null;
        return w.name + ", a wonder of the world: " + w.civic.effect.replace("Wonder: ", "") + " — the only one there will "
            + "ever be, if we finish it first";
    }

    /** The kind of lot it wants (TownPlan.placeFor), or null if it is no wonder. */
    @Nullable
    public static String placeFor(String structure) {
        Wonder w = byStructure(structure);
        return w == null ? null : w.place;
    }

    // ------------------------------------------------------------------ raised

    /**
     * A building went up (Villages.noteProject). A wonder: the first in the world, and it is the town's for good,
     * the world told; or another town's already, and the town is told it was beaten to it.
     */
    public static void raised(UUID village, String structure, long gameTime) {
        Wonder w = byStructure(structure);
        if (w == null) return;
        long day = gameTime / 24000L;
        Claim k = claim(w);
        if (k != null && !k.village().equals(village)) {
            Villages.tell(village, day, "our " + w.name + " is finished, but " + k.town() + " raised theirs first: a fine building, "
                + "but no wonder of the world");
            return;
        }
        if (k != null) return;
        BlockPos at = Villages.builtAt(village, structure);
        hold(w, village, day, at);
        String town = Villages.name(village);
        Villages.tell(village, day, "the town raised " + w.name + ", the only one in the world: " + w.civic.effect.replace("Wonder: ", ""));
        Market.assemblyNews(village, "We raised " + w.name + " — the only one in the world, and it's ours!");
        Reigns.deed(village, Reigns.Deed.WONDER, 3);
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(village)) continue;
            Villages.tell(o.id(), day, "word came that " + town + " has raised " + w.name + ", the only one in the world"
                + (CityTree.has(o.id(), w.civic) ? "; our plans for it are shelved" : ""));
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            Component line = Component.literal(town + " has raised " + w.name + ", a wonder of the world!")
                .withStyle(net.minecraft.ChatFormatting.GOLD);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(line);
        }
    }

    // ------------------------------------------------------------------ telling

    /** Where the wonder of this civic stands for this town, in a few words (the Research page's node, /village research). */
    public static String stateWords(@Nullable UUID village, CityTree.Civic c) {
        Wonder w = of(c);
        if (w == null || village == null) return "";
        Claim k = claim(w);
        if (k != null && k.village().equals(village)) return "standing, ours since day " + k.day() + ": the only one in the world";
        if (k != null) return "raised by " + k.town() + " on day " + k.day();
        if (!CityTree.has(village, c)) return "plans not yet drawn; dues " + w.duesWords();
        if (paid(village, w)) return Villages.sitesOf(village).containsKey(w.structure) ? "going up" : "dues laid by; to be built";
        String s = Ledger.note(village, "wonder.short." + w.structure);
        return "plans drawn; laying by its dues" + (s == null || s.isEmpty() ? " (" + w.duesWords() + ")" : ", short of " + s);
    }

    /** The world's wonders, a line each: "The Great Forge: Ashford, day 120", or "not yet raised anywhere". */
    public static List<String> worldLines() {
        List<String> out = new ArrayList<>();
        for (Wonder w : Wonder.values()) {
            Claim k = claim(w);
            out.add(w.title() + " (" + w.civic.branch.title + "): " + (k == null ? "not yet raised anywhere" : k.town() + ", day " + k.day()));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the morning's look at a town's wonders, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: what it is short of, "" when the stores hold every due. */
    public static String shortForTests(ServerLevel level, UUID village, Wonder w) {
        return shortOf(level, village, w);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
