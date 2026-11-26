package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] Players hiring players: a business a player owns (a firm or a registered farm) posts an advert on the same jobs
 * board as the folk employers, salaried or commissioned or both, with the job card, the hours and the target. Another
 * player applies there (JobAdverts); the employer sees each applicant on its business page and accepts or turns them
 * down; the applicant accepts the offer and is hired with the business as employer, paid out of its till by the same
 * payroll as the folk (WorkLife.payFrom), commission on the same provenance rules, reviewed by the same boss rules, the
 * owner named as the boss. The employer can warn a player hand, or let them go with a reason.
 */
public final class PlayerHiring extends SavedData {

    private static final String ID = "mc_assistant_wpb_hiring";
    @Nullable private static PlayerHiring loose;

    /** A post: its number, town, employer key and name, the owner, the card, the wage, pay options, openings; applicants. */
    static final class Post {
        int id;
        UUID town = new UUID(0, 0), owner = new UUID(0, 0);
        String employer = "", name = "", card = "FARM", hours = "dawn to dusk";
        int wage, payOptions, openings = 1;
        long posted;
        /** Applicants: player → {name, "c"/"s", state: APPLIED, OFFERED, TURNED_DOWN, TAKEN}. */
        final Map<UUID, String[]> apps = new LinkedHashMap<>();

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("town", town);
            t.putUUID("owner", owner);
            t.putString("employer", employer);
            t.putString("name", name);
            t.putString("card", card);
            t.putString("hours", hours);
            t.putInt("wage", wage);
            t.putInt("opts", payOptions);
            t.putInt("openings", openings);
            t.putLong("posted", posted);
            ListTag l = new ListTag();
            for (Map.Entry<UUID, String[]> e : apps.entrySet()) {
                CompoundTag x = new CompoundTag();
                x.putUUID("u", e.getKey());
                x.putString("n", e.getValue()[0]);
                x.putString("p", e.getValue()[1]);
                x.putString("s", e.getValue()[2]);
                l.add(x);
            }
            t.put("apps", l);
            return t;
        }

        static Post load(CompoundTag t) {
            Post p = new Post();
            p.id = t.getInt("id");
            p.town = t.getUUID("town");
            p.owner = t.getUUID("owner");
            p.employer = t.getString("employer");
            p.name = t.getString("name");
            p.card = t.getString("card");
            p.hours = t.getString("hours");
            p.wage = t.getInt("wage");
            p.payOptions = t.getInt("opts");
            p.openings = t.getInt("openings");
            p.posted = t.getLong("posted");
            ListTag l = t.getList("apps", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag x = l.getCompound(i);
                p.apps.put(x.getUUID("u"), new String[]{ x.getString("n"), x.getString("p"), x.getString("s") });
            }
            return p;
        }
    }

    final Map<Integer, Post> posts = new LinkedHashMap<>();
    int seq;

    public PlayerHiring() {}

    static PlayerHiring of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new PlayerHiring();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerHiring::new, PlayerHiring::load, null), ID);
    }

    static PlayerHiring load(CompoundTag t, HolderLookup.Provider p) {
        PlayerHiring s = new PlayerHiring();
        s.seq = t.getInt("seq");
        ListTag l = t.getList("posts", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            Post x = Post.load(l.getCompound(i));
            s.posts.put(x.id, x);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider p) {
        t.putInt("seq", seq);
        ListTag l = new ListTag();
        for (Post x : posts.values()) l.add(x.save());
        t.put("posts", l);
        return t;
    }

    static void dirty() {
        of().setDirty();
    }

    public static void resetForTests() {
        of().posts.clear();
        dirty();
    }

    // ------------------------------------------------------------------ the employer's side

    /** Post an advert for a business the player owns. What it says. */
    static String post(ServerLevel level, ServerPlayer owner, UUID town, String employer, String name, String card, int wage, int payOptions) {
        JobCards.Card c = JobCards.of(card);
        if (c == null) return "!No such trade.";
        PlayerHiring s = of();
        Post p = new Post();
        p.id = ++s.seq;
        p.town = town;
        p.owner = owner.getUUID();
        p.employer = employer;
        p.name = name;
        p.card = c.key();
        p.wage = Math.max(1, wage);
        p.payOptions = payOptions;
        p.posted = level.getDayTime() / 24000L;
        s.posts.put(p.id, p);
        dirty();
        return "Advert posted on the jobs board: " + c.title().toLowerCase(java.util.Locale.ROOT) + " at " + name + ", " + p.wage + "c a day"
            + (payOptions == 1 ? " (salary only)" : payOptions == 2 ? " (commission only)" : " or commission")
            + ". Applicants show here; wages come out of " + name + "'s till.";
    }

    /** Posts by the businesses this player owns. */
    static List<Post> postsOf(UUID town, String employer) {
        List<Post> out = new ArrayList<>();
        for (Post p : of().posts.values()) if (p.town.equals(town) && p.employer.equals(employer)) out.add(p);
        return out;
    }

    /** Accept (offer) or turn down an applicant. */
    static String answer(ServerPlayer owner, int postId, UUID applicant, boolean offer) {
        Post p = of().posts.get(postId);
        if (p == null || !p.owner.equals(owner.getUUID())) return "!No such advert of yours.";
        String[] a = p.apps.get(applicant);
        if (a == null) return "!No such applicant.";
        a[2] = offer ? "OFFERED" : "TURNED_DOWN";
        dirty();
        ServerPlayer ap = owner.server.getPlayerList().getPlayer(applicant);
        if (ap != null) WorkLife.toast(ap, offer ? "Job offer from " + p.name + ". Open the jobs board (U) to accept." : p.name + " turned your application down.");
        return offer ? "Offered the job to " + a[0] + ". They accept on their jobs board." : "Turned " + a[0] + " down.";
    }

    static String withdraw(ServerPlayer owner, int postId) {
        Post p = of().posts.get(postId);
        if (p == null || !p.owner.equals(owner.getUUID())) return "!No such advert of yours.";
        of().posts.remove(postId);
        dirty();
        return "Advert taken down.";
    }

    // ------------------------------------------------------------------ the board's side

    static List<JobAdverts.Advert> adverts(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<JobAdverts.Advert> out = new ArrayList<>();
        for (Post x : of().posts.values()) {
            if (!x.town.equals(v.id()) || x.openings <= 0) continue;
            JobCards.Card c = JobCards.of(x.card);
            if (c == null) continue;
            int n = 0, comm = 0;
            for (String[] a : x.apps.values()) if (a[2].equals("APPLIED")) { n++; if (a[1].equals("c")) comm++; }
            BlockPos where = null;
            if (x.employer.startsWith("farm:")) {
                PlayerFarms.Farm f = PlayerFarms.byId(x.employer.substring(5));
                if (f != null) where = f.centre();
            }
            if (where == null) where = JobAdverts.whereOf(level, v, c, p);
            out.add(new JobAdverts.Advert("p", x.id, c, x.employer, x.name, x.wage, x.hours, n, comm, x.posted + 7, where,
                x.name + ", " + c.shift().place, false, 0, x.payOptions, null, v.id()));
        }
        return out;
    }

    static void applied(ServerLevel level, ServerPlayer p, JobAdverts.Advert a, boolean commission) {
        Post x = of().posts.get(a.id());
        if (x == null) return;
        x.apps.put(p.getUUID(), new String[]{ p.getGameProfile().getName(), commission ? "c" : "s", "APPLIED" });
        dirty();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(x.owner);
        if (owner != null) WorkLife.toast(owner, p.getGameProfile().getName() + " applied to " + x.name + ". See its Hands.");
    }

    /** The applicant's record kept in step with the employer's answer. */
    static void tickApp(ServerLevel level, ServerPlayer p, CompoundTag t) {
        Post x = of().posts.get(t.getInt("id"));
        if (x == null) { JobAdverts.turnDown(p, t, "the advert came down"); return; }
        String[] a = x.apps.get(p.getUUID());
        if (a == null) return;
        if (a[2].equals("OFFERED") && !t.getString("st").equals("OFFERED")) JobAdverts.offer(level, p, t, "the owner chose you");
        if (a[2].equals("TURNED_DOWN") && !t.getString("st").equals("TURNED_DOWN")) JobAdverts.turnDown(p, t, "the owner chose somebody else");
    }

    /** The applicant accepted: the opening taken. "" if fine, else why not. */
    static String accepted(ServerLevel level, ServerPlayer p, CompoundTag t) {
        Post x = of().posts.get(t.getInt("id"));
        if (x == null || x.openings <= 0) return "That post has been filled.";
        String[] a = x.apps.get(p.getUUID());
        if (a == null || !a[2].equals("OFFERED")) return "The owner hasn't offered it to you.";
        a[2] = "TAKEN";
        x.openings--;
        if (x.openings <= 0) of().posts.remove(x.id);
        dirty();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(x.owner);
        if (owner != null) WorkLife.toast(owner, p.getGameProfile().getName() + " accepted: they work at " + x.name + " now.");
        return "";
    }

    /** A player employer without a business till (never: the employer is always a firm or farm); nothing paid. */
    static int pay(ServerLevel level, ServerPlayer p, CompoundTag j, int coins, String what) {
        return 0;
    }

    /** The players working for this business (online), with their job tags. */
    static List<ServerPlayer> hands(ServerLevel level, String employer) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) if (employer.equals(WorkLife.employerKey(p))) out.add(p);
        return out;
    }

    /** The owner warns or lets go a player hand, with a reason. */
    static String review(ServerLevel level, ServerPlayer owner, UUID hand, boolean letGo, String reason) {
        ServerPlayer h = level.getServer().getPlayerList().getPlayer(hand);
        if (h == null) return "!They're offline: it'll have to wait till they're back.";
        if (letGo) return "Let go: " + h.getGameProfile().getName() + ". " + WorkLife.dismiss(level, h, reason, false);
        WorkLife.misconduct(level, h, reason, false);
        return "Warned " + h.getGameProfile().getName() + ": " + reason + ".";
    }

    // ------------------------------------------------------------------ tests

    public static int postForTests(ServerLevel level, ServerPlayer owner, UUID town, String employer, String name, String card, int wage, int opts) {
        post(level, owner, town, employer, name, card, wage, opts);
        return of().seq;
    }

    public static String answerForTests(ServerPlayer owner, int postId, UUID applicant, boolean offer) {
        return answer(owner, postId, applicant, offer);
    }
}
