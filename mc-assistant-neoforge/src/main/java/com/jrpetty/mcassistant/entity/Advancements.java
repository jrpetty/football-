package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The player's milestones in the villages, as the game's own advancements: a toast when it happens, and a
 * page of their own ("Village Life", under the village board) on the advancements screen. [batchG]
 * <ul>
 * <li>Founding a village; a town you founded, or are a citizen of, reaching twenty-five, fifty and a
 *     hundred folk, and each age after the Wood Age;</li>
 * <li>becoming a citizen; being made an honoured guest (or better);</li>
 * <li>your first trade with a folk (a folk's own trade, or the merchant from afar's);</li>
 * <li>a letter from a friend in a village;</li>
 * <li>winning at the town fair, or a football cup, for the parts of the mod that hold them: they call
 *     {@link #grant} (or {@link #wonTheFair} / {@link #wonACup}). Both are hidden until won.</li>
 * </ul>
 * Each advancement (data/mc_assistant/advancement/village/*.json) has a single criterion the game itself can
 * never meet ("minecraft:impossible"); it is granted from here. The founder of each village is written in its
 * ledger when the founding is done (Founding.finish), so the milestones of "a town you founded" survive a
 * restart; citizenship is the ledger's already.
 */
public final class Advancements {

    private Advancements() {}

    public static final String ROOT = "village/root";
    public static final String FOUNDED = "village/founded";
    public static final String TOWN_25 = "village/town_25";
    public static final String TOWN_50 = "village/town_50";
    public static final String TOWN_100 = "village/town_100";
    public static final String CITIZEN = "village/citizen";
    public static final String HONOURED = "village/honoured";
    public static final String FIRST_TRADE = "village/first_trade";
    public static final String LETTER = "village/letter";
    public static final String FAIR = "village/fair_winner";
    public static final String CUP = "village/cup_winner";

    /** Every advancement of the page, the root first (for the tests). */
    public static List<String> all() {
        List<String> out = new ArrayList<>(List.of(ROOT, FOUNDED, TOWN_25, TOWN_50, TOWN_100, CITIZEN, HONOURED, FIRST_TRADE, LETTER, FAIR, CUP));
        for (Villages.Age a : Villages.Age.values()) if (a != Villages.Age.WOOD) out.add(age(a));
        return out;
    }

    /** The advancement for reaching an age. */
    public static String age(Villages.Age a) {
        return "village/age_" + a.name().toLowerCase(Locale.ROOT);
    }

    static void resetForTests() { }

    // ------------------------------------------------------------------ granting

    /**
     * Grant one of the page's advancements to a player (and the page's root with it). For any part of the mod
     * to call: {@code Advancements.grant(player, Advancements.FAIR)}. True if it was newly granted; nothing
     * happens if it was had already, or the advancement is not loaded.
     */
    public static boolean grant(ServerPlayer p, String key) {
        MinecraftServer server = p.getServer();
        if (server == null) return false;
        AdvancementHolder root = server.getAdvancements().get(id(ROOT));
        if (root != null && !key.equals(ROOT)) award(p, root);
        AdvancementHolder h = server.getAdvancements().get(id(key));
        return h != null && award(p, h);
    }

    /** Has this player the advancement? */
    public static boolean has(ServerPlayer p, String key) {
        MinecraftServer server = p.getServer();
        AdvancementHolder h = server == null ? null : server.getAdvancements().get(id(key));
        return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
    }

    private static boolean award(ServerPlayer p, AdvancementHolder h) {
        AdvancementProgress pr = p.getAdvancements().getOrStartProgress(h);
        if (pr.isDone()) return false;
        boolean any = false;
        for (String c : pr.getRemainingCriteria()) any |= p.getAdvancements().award(h, c);
        return any;
    }

    private static ResourceLocation id(String key) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, key);
    }

    /** Won at the town fair (for the fair, wherever it is held). */
    public static void wonTheFair(ServerPlayer p) {
        grant(p, FAIR);
    }

    /** Won a football cup (for the cup, wherever it is played). */
    public static void wonACup(ServerPlayer p) {
        grant(p, CUP);
    }

    /** A first trade with a folk (Trade.close, Merchants.close). */
    public static void firstTrade(ServerPlayer p) {
        grant(p, FIRST_TRADE);
    }

    /** A letter from a friend in a village (Dealings.letters). */
    public static void letter(ServerPlayer p) {
        grant(p, LETTER);
    }

    /** A village founded (Founding.finish): its founder written in its ledger, and the founder's toast. */
    public static void founded(ServerLevel level, @Nullable UUID player, UUID village) {
        if (player == null) return;
        Ledger.note(village, "founder", player.toString());
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) grant(p, FOUNDED);
    }

    /** The player who founded this village, if one did. */
    @Nullable
    public static UUID founder(UUID village) {
        String s = Ledger.note(village, "founder");
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * The town's look at its players (Visitors.tick, every ten seconds): its founder and its citizens who
     * are about are given what the town has come to (its size and its age); anybody it holds an honoured
     * guest or better, that.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        UUID founder = founder(id);
        int folk = Villages.headcount(id);
        Villages.Age age = Villages.ageOf(id);
        long now = level.getGameTime();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            UUID who = p.getUUID();
            boolean citizen = Citizens.is(id, who);
            if (who.equals(founder)) grant(p, FOUNDED);
            if (citizen) grant(p, CITIZEN);
            if (who.equals(founder) || citizen) {
                if (folk >= 25) grant(p, TOWN_25);
                if (folk >= 50) grant(p, TOWN_50);
                if (folk >= 100) grant(p, TOWN_100);
                for (Villages.Age a : Villages.Age.values()) {
                    if (a != Villages.Age.WOOD && age.ordinal() >= a.ordinal()) grant(p, age(a));
                }
            }
            if (Standing.of(id, who, now).title().atLeast(Standing.Title.HONOURED)) grant(p, HONOURED);
        }
    }

    /** Tests: the town's look at its players, now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }
}
