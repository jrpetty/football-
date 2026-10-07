package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] The post office, and the letters that go through it.
 *
 * <p><b>The office.</b> From the Stone Age a town of ten wants a post office: a small timber office
 * with a counter across it, the clerk's lectern behind and the pigeonholes along the back wall
 * (blueprints/postoffice.txt), its name on the sign by its door (TownLife). One of the storehouse's
 * couriers is its postman (else the storekeeper, else a hand with no trade, else any grown folk): of an
 * afternoon it takes the letters that have come for the town out of the pigeonholes, a bagful at a time,
 * and walks them round to whoever they are for.
 *
 * <p><b>Letters.</b> Folk write, of an afternoon or an evening off, to family and friends who live in other
 * towns now — a parent, a child, or a close friend who went off to a job in another town, a colony or a
 * marriage — each on a sheet of the stores' paper, and walk it to the counter: no paper, no letter. A
 * letter goes no faster than somebody walking: it waits in the pigeonholes till a caravan or an envoy sets
 * out that way, goes in the carrier's bag (as it walks, kept with the world), and comes out of it at the
 * other town's counter, whose postman takes it round. Read, it is something to remember and a lift to the
 * spirits, and a warmer feeling for whoever wrote it; and about half the time it is answered, the same way.
 * A letter for somebody in the same town goes on the next round.
 *
 * <p><b>Yours.</b> Right-click the post office's sign (or a pigeonhole or the clerk's lectern, or hand it to
 * the postman) with a book and quill or a written book whose title, or first line, names a folk ("Dear
 * Ash,"), and it is posted to them, wherever they live. They read it and write back, in the book you sent,
 * and the answer comes back to the counter you posted it at: the postman brings it to you if you are in
 * town, or right-click the sign with an empty hand and it is handed over. A letter to somebody in the same
 * town is answered within the day.
 */
public final class Post {

    private Post() {}

    public static final String STRUCTURE = "postoffice";
    /** A town wants a post office from the Stone Age, at this many folk. */
    public static final int FROM = 10;
    /** How many letters the postman takes on one round, and how long between rounds. */
    static final int BAG = 6;
    static final long ROUND_EVERY = 3000L;
    /** How many days a letter nobody can be found for waits at the counter before it is given up. */
    static final int UNCLAIMED = 6;
    /** A folk writes to the same somebody no oftener than this. */
    static final int EVERY_DAYS = 3;

    /** A folk's walk to the counter with a letter: who it is for, and when it set out. */
    record Posting(UUID to, String toName, UUID toTown, int since) {}

    /** The postman's round: the letters' ids, a stop at a time, and since when at this stop. */
    static final class Round {
        final List<Integer> letters = new ArrayList<>();
        int at;
        int since;
        int stopSince;
    }

    private static final Map<UUID, Posting> POSTING = new ConcurrentHashMap<>();
    private static final Map<UUID, Round> ROUNDS = new ConcurrentHashMap<>();
    /** How many each town has sent to the counter today: {day, how many}. */
    private static final Map<UUID, long[]> WROTE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        POSTING.clear();
        ROUNDS.clear();
        WROTE.clear();
    }

    // ------------------------------------------------------------------ the building

    /** Does the town want its post office now (Villages.projectsWantedInOrder)? */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FROM && Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()
            && !Villages.hasBuilt(village, STRUCTURE) && Villages.builtStructure(village, STRUCTURE) == null;
    }

    /** Why it goes up (Villages.whyBuild). */
    public static String why(UUID village) {
        int away = 0;
        for (String k : Civics.allFolk().getAllKeys()) {
            CompoundTag t = Civics.allFolk().getCompound(k);
            if (t.contains("town") && !t.getString("town").equals(village.toString())) away++;
        }
        return "a post office, so folk can write to family and friends in other towns"
            + (away > 0 ? " (" + away + " folk we know of live elsewhere now)" : "");
    }

    @Nullable
    static Ledger.Building office(UUID village) {
        return Villages.builtStructure(village, STRUCTURE);
    }

    /** Where folk stand to post a letter: before the office's door (or its middle, if it has none). */
    @Nullable
    static BlockPos counterSpot(UUID village) {
        Ledger.Building b = office(village);
        if (b == null) return null;
        BlockPos door = TownLife.fittings(b).door();
        return door != null ? door.relative(b.facing().getOpposite()) : b.anchor();
    }

    /** The town whose post office this block belongs to (its sign, a pigeonhole, the clerk's lectern), or null. */
    @Nullable
    public static Villages.Village officeAt(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        boolean sign = st.getBlock() instanceof SignBlock;
        if (!sign && !(st.getBlock() instanceof BarrelBlock) && !(st.getBlock() instanceof LecternBlock)) return null;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Ledger.Building b = office(v.id());
            if (b == null) continue;
            BlockPos door = TownLife.fittings(b).door();
            if (sign && door != null && door.distManhattan(pos) <= 3) return v;
            if (!sign && Math.abs(pos.getX() - b.anchor().getX()) <= 3 && Math.abs(pos.getZ() - b.anchor().getZ()) <= 3
                    && Math.abs(pos.getY() - b.anchor().getY()) <= 3) return v;
        }
        return null;
    }

    // ------------------------------------------------------------------ the letters

    /** The pigeonholes of a town's post office, as a letter's "where": its letters waiting there. */
    static String holes(UUID town) { return "office:" + town; }

    static String bag(UUID folk) { return "bag:" + folk; }

    static String hands(UUID folk) { return "hands:" + folk; }

    /** The letters in one place ("office:...", "bag:...", "hands:..."). */
    static List<CompoundTag> at(String where) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : Civics.letters()) if (t instanceof CompoundTag c && where.equals(c.getString("where"))) out.add(c);
        return out;
    }

    @Nullable
    static CompoundTag letter(int n) {
        for (Tag t : Civics.letters()) if (t instanceof CompoundTag c && c.getInt("n") == n) return c;
        return null;
    }

    static UUID uuid(CompoundTag c, String key) {
        try {
            return c.getString(key).isEmpty() ? null : UUID.fromString(c.getString(key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A new letter, at the counter of the town it was posted in. */
    static CompoundTag post(UUID postedIn, @Nullable VillageFolkEntity from, String fromName, UUID fromTown,
                            @Nullable UUID to, String toName, UUID toTown, String text, long day) {
        CompoundTag root = Civics.town(postedIn);
        int n = Civics.nextId();
        CompoundTag c = new CompoundTag();
        c.putInt("n", n);
        c.putString("from", from == null ? "" : from.getUUID().toString());
        c.putString("fromName", fromName);
        c.putString("fromTown", fromTown.toString());
        c.putString("to", to == null ? "" : to.toString());
        c.putString("toName", toName);
        c.putString("toTown", toTown.toString());
        c.putString("text", text);
        c.putLong("day", day);
        c.putString("where", holes(postedIn));
        Civics.letters().add(c);
        root.putInt("posted", root.getInt("posted") + 1);
        Civics.changed();
        return c;
    }

    // ------------------------------------------------------------------ who lives where

    /** A folk of this town, written down (with its parents) so letters can find it wherever it goes. */
    static void note(VillageFolkEntity f, UUID town, long now) {
        CompoundTag t = Civics.folk(f.getUUID());
        String was = t.getString("town");
        t.putString("name", f.displayNameCap());
        t.putString("town", town.toString());
        t.putLong("seen", now);
        if (!f.parentIds().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (UUID p : f.parentIds()) sb.append(sb.length() == 0 ? "" : ",").append(p);
            t.putString("parents", sb.toString());
        }
        if (!was.equals(town.toString())) Civics.changed();
    }

    /** Who of its family and close friends lives in another town now, nearest kin first: {id, name, town, how}. */
    static List<String[]> elsewhere(VillageFolkEntity f, long now) {
        UUID home = f.ownerId();
        List<String[]> kin = new ArrayList<>(), friends = new ArrayList<>();
        if (home == null) return kin;
        CompoundTag all = Civics.allFolk();
        String me = f.getUUID().toString();
        for (String k : all.getAllKeys()) {
            if (k.equals(me)) continue;
            CompoundTag t = all.getCompound(k);
            String town = t.getString("town");
            if (town.isEmpty() || town.equals(home.toString())) continue;
            if (now - t.getLong("seen") > 3 * 24000L) continue;             // not seen these three days: gone, or dead
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            String name = t.getString("name");
            if (f.parentIds().contains(id)) kin.add(new String[]{ k, name, town, "parent" });
            else if (t.getString("parents").contains(me)) kin.add(new String[]{ k, name, town, "child" });
            else if (id.equals(f.life().partner())) kin.add(new String[]{ k, name, town, "partner" });
            else if (f.life().affinity(id) >= Social.FRIEND) friends.add(new String[]{ k, name, town, "friend" });
        }
        kin.addAll(friends);
        return kin;
    }

    // ------------------------------------------------------------------ the round of the town

    /** Every five seconds (Civics.tick): who lives where, letters into and out of the carriers' bags, the
     *  writers to the counter, the postman's round, and the letters read. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = Civics.day(level), t = level.getDayTime() % 24000L;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase()) note(f, id, now);
        }
        carriers(level, v);
        if (office(id) != null && t >= 6000 && t < 12800) {
            long[] w = WROTE.computeIfAbsent(id, k -> new long[]{ day, 0 });
            if (w[0] != day) { w[0] = day; w[1] = 0; }
            if (w[1] < 2) w[1] += writers(level, v, day, now, (int) (2 - w[1]));
        }
        if (office(id) != null && t >= 5000 && t < 12000) startRound(level, v, day);
        readLetters(level, v, day);
        tidy(level, v, day);
    }

    /**
     * A carrier in this town (its own setting out, or another town's come in with a caravan or on an
     * envoy's errand): the letters in its bag for this town out at the counter, and the letters at the
     * counter for the towns it is going between into its bag. A folk home with letters still in its bag
     * and no road ahead of it leaves them at the counter for the next carrier.
     */
    static void carriers(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int reach = Villages.townReach(id) + 8;
        AABB box = new AABB(v.centre()).inflate(reach, 48, reach);
        long day = Civics.day(level);
        for (VillageFolkEntity x : level.getEntitiesOfClass(VillageFolkEntity.class, box, VillageFolkEntity::isAlive)) {
            Caravans.Trip trip = x.trip();
            List<CompoundTag> carried = at(bag(x.getUUID()));
            if (trip == null && carried.isEmpty()) continue;
            if (round(x) != null) continue;                          // the postman's own bag, on its round
            int in = 0;
            for (CompoundTag c : carried) {
                if (c.getString("toTown").equals(id.toString()) || trip == null && id.equals(x.ownerId())) {
                    c.putString("where", holes(id));
                    c.putLong("in", day);
                    in++;
                    told(level, c);
                }
            }
            if (in > 0) {
                Villages.tell(id, day, x.displayNameCap() + " brought " + in + (in == 1 ? " letter" : " letters") + " for the post office");
                FolkTalk.speak(x, in == 1 ? "A letter for the post office!" : "Letters for the post office!");
                Civics.changed();
            }
            if (trip == null) continue;
            // Setting out from home, it takes the post for where it is going; at the other end, the post for home.
            // (On its way back through its own town it takes nothing: it is going nowhere but home.)
            UUID bound = id.equals(trip.from) && !trip.homeward() ? trip.to : id.equals(trip.to) ? trip.from : null;
            if (bound == null) continue;
            int out = 0;
            for (CompoundTag c : at(holes(id))) {
                if (!c.getString("toTown").equals(bound.toString()) || out >= 16) continue;
                c.putString("where", bag(x.getUUID()));
                out++;
            }
            if (out > 0) {
                FolkTalk.speak(x, "I'll take the post for " + Villages.name(bound) + ".");
                Civics.changed();
            }
        }
    }

    /** A letter for a player come in at a counter: they are told, if they are about. */
    private static void told(ServerLevel level, CompoundTag c) {
        UUID player = uuid(c, "player");
        if (player == null || !c.getBoolean("reply")) return;
        Player p = level.getPlayerByUUID(player);
        UUID town = uuid(c, "toTown");
        if (p != null && town != null) {
            p.displayClientMessage(Component.literal("A letter from " + c.getString("fromName") + " has come for you at the "
                + Villages.name(town) + " post office."), false);
        }
    }

    /** Of an afternoon or an evening, folk with family or friends elsewhere (two a day at most) go to post a letter. */
    static int writers(ServerLevel level, Villages.Village v, long day, long now, int most) {
        UUID id = v.id();
        if (Market.stock(level, id, s -> s.is(Items.PAPER)) <= 0) return 0;
        int sent = 0;
        List<VillageFolkEntity> folk = Civics.grown(id);
        java.util.Collections.shuffle(folk, new java.util.Random(now));
        for (VillageFolkEntity f : folk) {
            if (sent >= most) break;
            if (POSTING.containsKey(f.getUUID()) || !Civics.free(f)) continue;
            CompoundTag me = Civics.folk(f.getUUID());
            for (String[] who : elsewhere(f, now)) {
                if (day - me.getLong("wrote/" + who[0]) < EVERY_DAYS && me.contains("wrote/" + who[0])) continue;
                POSTING.put(f.getUUID(), new Posting(UUID.fromString(who[0]), who[1], UUID.fromString(who[2]), f.tickCount));
                sent++;
                break;
            }
        }
        return sent;
    }

    /** Send a folk to post a letter to somebody now (the command, the tests); false if it has nobody elsewhere. */
    static boolean sendToPost(VillageFolkEntity f, long now) {
        List<String[]> who = elsewhere(f, now);
        if (who.isEmpty()) return false;
        POSTING.put(f.getUUID(), new Posting(UUID.fromString(who.get(0)[0]), who.get(0)[1], UUID.fromString(who.get(0)[2]), f.tickCount));
        return true;
    }

    /** "/village civic letters": whoever has somebody elsewhere sent to write to them. */
    public static String writeNow(ServerLevel level, Villages.Village v) {
        List<String> who = new ArrayList<>();
        for (VillageFolkEntity f : Civics.grown(v.id())) if (sendToPost(f, level.getGameTime())) who.add(f.displayNameCap());
        return who.isEmpty() ? "nobody here has family or friends in another town that we know of" : "off to post a letter: " + String.join(", ", who);
    }

    /**
     * A letter written and handed in: a sheet of the stores' paper, at the counter. Null if there is no
     * paper (or no post office): nothing is written on nothing.
     */
    @Nullable
    static CompoundTag write(ServerLevel level, VillageFolkEntity f, UUID to, String toName, UUID toTown) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return null;
        if (!TownWork.take(level, v, s -> s.is(Items.PAPER), 1)) return null;
        long day = Civics.day(level);
        CompoundTag c = post(id, f, f.displayNameCap(), id, to, toName, toTown, letterText(level, f, toName, toTown, false), day);
        CompoundTag me = Civics.folk(f.getUUID());
        me.putLong("wrote/" + to, day);
        me.putInt("wrote", me.getInt("wrote") + 1);
        Civics.changed();
        return c;
    }

    /** What a folk writes: the news at home, its own, and a word for whoever it is writing to. */
    static String letterText(ServerLevel level, VillageFolkEntity f, String toName, UUID toTown, boolean answering) {
        UUID home = f.ownerId();
        String town = home == null ? "here" : Villages.name(home);
        List<Villages.News> news = home == null ? List.of() : Villages.news(home);
        String latest = news.isEmpty() ? "all quiet" : news.get(0).text();
        Persona.Memory m = f.persona().latest();
        String mine = m == null ? "nothing much to tell of myself" : m.text();
        String trade = f.stationTask() == AssistantEntity.StationTask.NONE ? "between trades still"
            : "still at my work as a " + f.stationTask().title.toLowerCase(Locale.ROOT);
        String ask = Villages.name(toTown);
        return "Dear " + toName + ",\n\n" + (answering ? "Thank you for your letter. " : "")
            + "All's well in " + town + ": the latest is that " + latest + ". As for me, " + mine + ", and I'm " + trade + ".\n\n"
            + FolkTalk.pick(f.getRandom(), "How are you getting on in " + ask + "? Write when you can.",
                "Give my love to everybody in " + ask + ".", "Don't be a stranger, now.")
            + "\n\nYours,\n" + f.displayNameCap();
    }

    // ------------------------------------------------------------------ the folk's part

    /** The folk's hold (Civics.hold): a letter walked to the counter, or the postman's round. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Round r = ROUNDS.get(f.getUUID());
        if (r != null) return walkRound(f, level, r);
        Posting p = POSTING.get(f.getUUID());
        if (p == null) return null;
        UUID id = f.ownerId();
        BlockPos spot = id == null ? null : counterSpot(id);
        if (spot == null || f.tickCount - p.since() > 1600 || f.tickCount < p.since() || Raids.underAlarm(id)) {
            POSTING.remove(f.getUUID());
            return null;
        }
        if (!Civics.goTo(f, spot, 2.0, 0.8)) return "on the way to post a letter to " + p.toName();
        POSTING.remove(f.getUUID());
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        CompoundTag c = write(level, f, p.to(), p.toName(), p.toTown());
        FolkTalk.speak(f, c == null ? "No paper at the post office. It'll have to wait."
            : "A letter for " + p.toName() + (p.toTown().equals(id) ? "." : ", in " + Villages.name(p.toTown()) + "."));
        return "posting a letter";
    }

    /** Is this the town's postman? */
    public static boolean isPostman(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id != null && f.getUUID().toString().equals(Civics.town(id).getString("postman"));
    }

    @Nullable
    static Round round(VillageFolkEntity f) {
        return ROUNDS.get(f.getUUID());
    }

    /** The postman: one of the storehouse's couriers (not its sweeper), else the storekeeper, else a hand with no
     *  trade, else whoever of the grown folk has been here longest. Kept until it is gone. */
    @Nullable
    static VillageFolkEntity postman(ServerLevel level, UUID village) {
        CompoundTag t = Civics.town(village);
        UUID was = uuid(t, "postman");
        VillageFolkEntity f = Civics.find(level, was);
        if (f != null && village.equals(f.ownerId()) && !f.isBaby()) return f;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VillageFolkEntity c : Civics.grown(village)) {
            int s = switch (c.stationTask()) {
                case HAUL -> Sweepers.appointed(c) ? 20 : 40;
                case STORE -> 30;
                case NONE -> 20;
                default -> 0;
            };
            s = s * 1000 + (int) Math.min(999, Math.max(0, level.getDayTime() / 24000L - c.persona().since()));
            if (c.stationTask() == AssistantEntity.StationTask.GUARD || c.isElder()) s -= 1000000;     // the watch and the elder last of all
            if (s > bestScore) { bestScore = s; best = c; }
        }
        if (best == null) return null;
        if (was == null || !was.equals(best.getUUID())) {
            t.putString("postman", best.getUUID().toString());
            Civics.changed();
            if (office(village) != null) Villages.tell(village, Civics.day(level), best.displayNameCap() + " became the town's postman");
        }
        return best;
    }

    /** A round of the town, by day, two or three hours apart: the letters for its folk (and for players in town) into the bag. */
    static void startRound(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag t = Civics.town(id);
        long now = level.getGameTime();
        if (t.contains("roundAt") && now - t.getLong("roundAt") < ROUND_EVERY && now >= t.getLong("roundAt")) return;
        List<CompoundTag> waiting = new ArrayList<>();
        for (CompoundTag c : at(holes(id))) {
            if (!c.getString("toTown").equals(id.toString())) continue;
            UUID player = uuid(c, "player");
            boolean toPlayer = c.getBoolean("reply") && player != null;
            if (toPlayer ? inTown(level, v, player) : uuid(c, "to") != null) waiting.add(c);
        }
        if (waiting.isEmpty()) return;
        VillageFolkEntity pm = postman(level, id);
        if (pm == null || pm.isSleeping() || pm.trip() != null || Assemblies.attending(pm) || TownJobs.busy(pm)
                || Raids.underAlarm(id) || ROUNDS.containsKey(pm.getUUID())) return;
        Round r = new Round();
        for (CompoundTag c : waiting) {
            if (r.letters.size() >= BAG) break;
            c.putString("where", bag(pm.getUUID()));
            r.letters.add(c.getInt("n"));
        }
        r.since = r.stopSince = pm.tickCount;
        ROUNDS.put(pm.getUUID(), r);
        t.putLong("roundAt", now);
        Civics.changed();
        pm.clearQueue();
        FolkTalk.speak(pm, r.letters.size() == 1 ? "One letter today. Off I go." : r.letters.size() + " letters today. Off I go!");
    }

    private static boolean inTown(ServerLevel level, Villages.Village v, UUID player) {
        Player p = level.getPlayerByUUID(player);
        int reach = Villages.townReach(v.id()) + 8;
        return p != null && p.blockPosition().distSqr(v.centre()) <= (double) reach * reach;
    }

    /** Walk the round: to each letter's reader in turn, and into their hands. */
    private static String walkRound(VillageFolkEntity f, ServerLevel level, Round r) {
        if (r.at >= r.letters.size() || f.tickCount - r.since > 6000 || f.tickCount < r.since || Raids.underAlarm(f.ownerId())) {
            endRound(f, r);
            return null;
        }
        CompoundTag c = letter(r.letters.get(r.at));
        if (c == null || !bag(f.getUUID()).equals(c.getString("where"))) { r.at++; r.stopSince = f.tickCount; return "on the post round"; }
        UUID to = uuid(c, "to");
        UUID player = uuid(c, "player");
        boolean toPlayer = c.getBoolean("reply") && player != null;
        net.minecraft.world.entity.LivingEntity who = toPlayer ? level.getPlayerByUUID(player) : Civics.find(level, to);
        if (who == null || f.tickCount - r.stopSince > 900) {        // not to be found, or not to be got to: back to the pigeonholes
            c.putString("where", holes(f.ownerId()));
            r.at++;
            r.stopSince = f.tickCount;
            return "on the post round";
        }
        String name = toPlayer ? who.getName().getString() : c.getString("toName");
        double d = f.distanceToSqr(who);
        if (d > 3.0 * 3.0) {
            Civics.goTo(f, who.blockPosition(), 2.5, 0.9);
            return "taking a letter round to " + name;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(who, 30.0F, 30.0F);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (who instanceof Player p) {
            handToPlayer(p, c);
            FolkTalk.speak(f, "Post for you, " + name + " — from " + c.getString("fromName") + "!");
        } else {
            c.putString("where", hands(who.getUUID()));
            String from = c.getString("fromName");
            UUID fromTown = uuid(c, "fromTown");
            FolkTalk.speak(f, "A letter for you, " + name + " — from " + from
                + (fromTown != null && !fromTown.equals(f.ownerId()) ? " in " + Villages.name(fromTown) : "") + "!");
        }
        CompoundTag me = Civics.folk(f.getUUID());
        me.putInt("delivered", me.getInt("delivered") + 1);
        Civics.changed();
        r.at++;
        r.stopSince = f.tickCount;
        return "on the post round";
    }

    private static void endRound(VillageFolkEntity f, Round r) {
        ROUNDS.remove(f.getUUID());
        UUID id = f.ownerId();
        if (id == null) return;
        for (CompoundTag c : at(bag(f.getUUID()))) c.putString("where", holes(id));
        Civics.changed();
    }

    // ------------------------------------------------------------------ read, and answered

    /** Letters in folk's hands are read, one each a look, by whoever is awake and about. */
    static void readLetters(ServerLevel level, Villages.Village v, long day) {
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            if (f.isSleeping() || Assemblies.attending(f)) continue;
            List<CompoundTag> mine = at(hands(f.getUUID()));
            if (!mine.isEmpty()) read(level, f, mine.get(0), day);
        }
    }

    /**
     * A letter read: said out loud (the gist of it), remembered, the writer the dearer for it and the day the
     * brighter; and answered — always, to a player, in the book they sent; to a folk, about half the time, on
     * a sheet of the stores' paper (none, no answer).
     */
    static void read(ServerLevel level, VillageFolkEntity f, CompoundTag c, long day) {
        c.putString("where", "read");
        c.putLong("readDay", day);
        String from = c.getString("fromName");
        UUID fromTown = uuid(c, "fromTown");
        UUID fromFolk = uuid(c, "from");
        boolean player = fromFolk == null && uuid(c, "player") != null;
        String where = fromTown == null || fromTown.equals(f.ownerId()) ? "" : " in " + Villages.name(fromTown);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A letter from " + from + where + "!", "Oh! " + from + " has written" + where + "!"));
        f.persona().remember(day, from + " wrote to me" + where, player ? 4 : 3);
        if (fromFolk != null) f.life().feel(fromFolk, from, 5);
        if (player) f.persona().feelFor(uuid(c, "player"), from, 4);
        CompoundTag me = Civics.folk(f.getUUID());
        me.putInt("got", me.getInt("got") + 1);
        me.putString("lastLetter", from + where);
        Civics.changed();
        Civics.glad(f, Civics.LETTER, day);
        if (c.getBoolean("reply")) return;                                // an answer is not answered again
        if (player) answerPlayer(level, f, c, day);
        else if (fromFolk != null && fromTown != null && f.getRandom().nextBoolean()) answerFolk(level, f, c, fromFolk, fromTown);
    }

    /** A folk's letter answered, by post, on the stores' paper. */
    static void answerFolk(ServerLevel level, VillageFolkEntity f, CompoundTag c, UUID to, UUID toTown) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !TownWork.take(level, v, s -> s.is(Items.PAPER), 1)) return;
        CompoundTag a = post(id, f, f.displayNameCap(), id, to, c.getString("fromName"), toTown,
            letterText(level, f, c.getString("fromName"), toTown, true), Civics.day(level));
        a.putBoolean("reply", true);
        Civics.folk(f.getUUID()).putInt("wrote", Civics.folk(f.getUUID()).getInt("wrote") + 1);
    }

    /** A player's letter answered, in their own book, back to the counter they posted it at. */
    static void answerPlayer(ServerLevel level, VillageFolkEntity f, CompoundTag c, long day) {
        UUID id = f.ownerId();
        UUID back = uuid(c, "fromTown");
        UUID player = uuid(c, "player");
        if (id == null || back == null || player == null) return;
        String name = c.getString("playerName");
        CompoundTag a = post(id, f, f.displayNameCap(), id, null, name, back, replyText(level, f, c, day), day);
        a.putBoolean("reply", true);
        a.putString("player", player.toString());
        a.putString("playerName", name);
        a.putBoolean("book", c.getBoolean("book"));
        FolkTalk.speak(f, "I must write back to " + name + " straight away.");
    }

    /** What a folk writes back to a player: thanks, an answer to what they asked, its news and its hopes. */
    static String replyText(ServerLevel level, VillageFolkEntity f, CompoundTag c, long day) {
        String name = c.getString("playerName");
        String theirs = c.getString("text").toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("Dear ").append(name).append(",\n\nThank you for your letter of day ")
            .append(c.getLong("day") + 1).append(". ");
        Persona me = f.persona();
        if (theirs.contains("?") || theirs.contains("how are")) {
            String feel = Persona.moodWord(me.mood());
            String why = me.moodWhy().isEmpty() ? "" : FolkTalk.reason(f, me.moodWhy().get(0));
            sb.append("You asked how I am: ").append(feel).append(", I'd say. ").append(why).append(' ');
        }
        UUID home = f.ownerId();
        List<Villages.News> news = home == null ? List.of() : Villages.news(home);
        if (!news.isEmpty()) sb.append("The news here is that ").append(news.get(0).text()).append(". ");
        if (me.rolled()) sb.append("\n\n").append(me.ambitionMet() ? "As for me: " + me.ambition().done : "As for me, I still hope " + me.ambition().hope).append('.');
        sb.append("\n\nCome and see us in ").append(home == null ? "the village" : Villages.name(home)).append(" soon.\n\nYours,\n")
            .append(f.displayNameCap());
        if (f.stationTask() != AssistantEntity.StationTask.NONE) sb.append(", ").append(f.stationTask().title.toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    /** The written answer, as the book it is written in: a player's own book, turned to a written one. */
    static ItemStack bookOf(CompoundTag c) {
        String text = c.getString("text");
        List<String> paras = new ArrayList<>(List.of(text.split("\n\n")));
        String front = paras.isEmpty() ? text : paras.remove(0);
        return Services.book("From " + c.getString("fromName"), c.getString("fromName"), front, paras);
    }

    static void handToPlayer(Player p, CompoundTag c) {
        c.putString("where", "given");
        ItemStack book = bookOf(c);
        if (!p.getInventory().add(book)) p.drop(book, false);
        Civics.changed();
    }

    // ------------------------------------------------------------------ the counter

    /** Is this something a letter can be written in: a book and quill, or a written book? */
    public static boolean isLetter(ItemStack s) {
        if (s.is(Items.WRITABLE_BOOK)) return true;
        if (!s.is(Items.WRITTEN_BOOK) || Gazette.isGazette(s)) return false;
        // An elder's letter a player is carrying to another town by hand (Bonds) is for the folk there, not the post.
        CompoundTag tag = s.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        return !tag.contains("mca_letter_to");
    }

    /** A player at the counter: a letter in hand is posted; with none, whatever has come for them is handed over. */
    public static String counter(ServerLevel level, Villages.Village v, Player p) {
        ItemStack held = p.getMainHandItem();
        if (isLetter(held)) return postFor(level, v, p, held);
        List<CompoundTag> mine = new ArrayList<>();
        for (CompoundTag c : at(holes(v.id()))) {
            if (c.getBoolean("reply") && p.getUUID().toString().equals(c.getString("player"))) mine.add(c);
        }
        if (mine.isEmpty()) {
            int waiting = 0;
            for (Tag t : Civics.letters()) {
                if (t instanceof CompoundTag c && !c.getBoolean("reply") && p.getUUID().toString().equals(c.getString("player"))
                        && !"read".equals(c.getString("where"))) waiting++;
            }
            return "Nothing for you today." + (waiting > 0 ? " (" + waiting + (waiting == 1 ? " letter" : " letters")
                + " of yours still on the way.)" : " Hold a book and quill out at the counter, \"Dear Ash,\" at the top, to write to somebody.");
        }
        List<String> from = new ArrayList<>();
        for (CompoundTag c : mine) {
            handToPlayer(p, c);
            from.add(c.getString("fromName"));
        }
        return (mine.size() == 1 ? "A letter for you, from " : mine.size() + " letters for you, from ") + Civics.names(from) + ".";
    }

    /** A player's letter posted: to whoever it names, wherever they live. */
    static String postFor(ServerLevel level, Villages.Village v, Player p, ItemStack held) {
        String text = textOf(held);
        String name = addressee(held, text);
        if (name == null) return "Who's it for? Put their name at the top (\"Dear Ash,\") or give the book a title with it.";
        UUID[] found = byName(level, v.id(), name);
        if (found == null) return "There's nobody called " + name + " that the post knows of.";
        String toName = Civics.folk(found[0]).getString("name");
        CompoundTag c = post(v.id(), null, p.getName().getString(), v.id(), found[0], toName.isEmpty() ? name : toName, found[1], text, Civics.day(level));
        c.putString("player", p.getUUID().toString());
        c.putString("playerName", p.getName().getString());
        c.putBoolean("book", true);
        held.shrink(1);
        Civics.changed();
        boolean local = found[1].equals(v.id());
        return "Posted to " + c.getString("toName") + (local ? " here in " + Villages.name(v.id()) + ": it goes out on the afternoon round."
            : " in " + Villages.name(found[1]) + ": it goes with the next caravan or envoy that way.") + " The answer comes back here.";
    }

    /** What is written in a book and quill, or a written book, as plain text. */
    static String textOf(ItemStack s) {
        StringBuilder sb = new StringBuilder();
        WritableBookContent w = s.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (w != null) for (Filterable<String> page : w.pages()) sb.append(sb.length() == 0 ? "" : "\n").append(page.raw());
        WrittenBookContent b = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (b != null) for (Filterable<Component> page : b.pages()) sb.append(sb.length() == 0 ? "" : "\n").append(page.raw().getString());
        String out = sb.toString();
        return out.length() > 1200 ? out.substring(0, 1200) : out;
    }

    /** Who a letter is for: its title ("To Ash", "Ash"), else its first line ("Dear Ash,", "To Ash"). */
    @Nullable
    static String addressee(ItemStack s, String text) {
        WrittenBookContent b = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (b != null) {
            String t = name(b.title().raw());
            if (t != null) return t;
        }
        int nl = text.indexOf('\n');
        return name(nl < 0 ? text : text.substring(0, nl));
    }

    @Nullable
    private static String name(String line) {
        String l = line.trim();
        for (String head : new String[]{ "dear ", "dearest ", "to ", "for ", "hello ", "hi " }) {
            if (l.toLowerCase(Locale.ROOT).startsWith(head)) { l = l.substring(head.length()).trim(); break; }
        }
        String[] words = l.split("[\\s,.!:;]+");
        return words.length == 0 || words[0].isEmpty() ? null : words[0];
    }

    /** A folk by name: one of this town first, then anybody the post knows of. {id, town}, or null. */
    @Nullable
    static UUID[] byName(ServerLevel level, UUID town, String name) {
        UUID[] elsewhere = null;
        CompoundTag all = Civics.allFolk();
        long now = level.getGameTime();
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (a instanceof VillageFolkEntity f && f.displayNameCap().equalsIgnoreCase(name)) return new UUID[]{ f.getUUID(), town };
        }
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if (!t.getString("name").equalsIgnoreCase(name) || t.getString("town").isEmpty()) continue;
            if (now - t.getLong("seen") > 6 * 24000L) continue;
            try {
                UUID id = UUID.fromString(k), at = UUID.fromString(t.getString("town"));
                if (at.equals(town)) return new UUID[]{ id, at };
                if (elsewhere == null) elsewhere = new UUID[]{ id, at };
            } catch (IllegalArgumentException ignored) { }
        }
        return elsewhere;
    }

    /** Letters long read are let go; one nobody could be found for these six days is given up. */
    static void tidy(ServerLevel level, Villages.Village v, long day) {
        ListTag all = Civics.letters();
        boolean changed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!(all.get(i) instanceof CompoundTag c)) continue;
            String w = c.getString("where");
            if (("read".equals(w) || "given".equals(w)) && day - Math.max(c.getLong("readDay"), c.getLong("day")) > 7) {
                all.remove(i);
                changed = true;
            } else if (w.equals(holes(v.id())) && c.getString("toTown").equals(v.id().toString())
                    && day - Math.max(c.getLong("in"), c.getLong("day")) > UNCLAIMED && !(c.getBoolean("reply") && !c.getString("player").isEmpty())) {
                c.putString("where", "read");
                c.putLong("readDay", day);
                Villages.tell(v.id(), day, "a letter for " + c.getString("toName") + " from " + c.getString("fromName")
                    + " could not be delivered");
                changed = true;
            }
        }
        // A bag nobody is carrying (its folk home with no road ahead of it, or gone): back to the pigeonholes.
        for (Tag t : all) {
            if (!(t instanceof CompoundTag c) || !c.getString("where").startsWith("bag:")) continue;
            UUID carrier = parse(c.getString("where").substring(4));
            VillageFolkEntity x = Civics.find(level, carrier);
            if (x == null && carrier != null && Civics.folk(carrier).getString("town").equals(v.id().toString())
                    && level.getGameTime() - Civics.folk(carrier).getLong("seen") > 2400) {
                c.putString("where", holes(v.id()));
                changed = true;
            }
        }
        if (changed) Civics.changed();
    }

    @Nullable
    private static UUID parse(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------ where the player sees it

    static String cardLine(VillageFolkEntity f) {
        CompoundTag t = Civics.folk(f.getUUID());
        List<String> parts = new ArrayList<>();
        if (isPostman(f)) parts.add("the town's postman" + (t.getInt("delivered") > 0 ? " (" + t.getInt("delivered") + " letters delivered)" : ""));
        int wrote = t.getInt("wrote"), got = t.getInt("got");
        if (wrote > 0 || got > 0) {
            parts.add("letters: wrote " + wrote + ", had " + got + (t.getString("lastLetter").isEmpty() ? "" : " (the last from " + t.getString("lastLetter") + ")"));
        }
        return String.join("; ", parts);
    }

    static List<String> board(ServerLevel level, UUID village) {
        if (office(village) == null) return List.of();
        int out = 0, in = 0;
        Set<String> towns = new java.util.TreeSet<>();
        for (CompoundTag c : at(holes(village))) {
            if (c.getString("toTown").equals(village.toString())) in++;
            else {
                out++;
                UUID t = uuid(c, "toTown");
                if (t != null) towns.add(Villages.name(t));
            }
        }
        VillageFolkEntity pm = Civics.find(level, uuid(Civics.town(village), "postman"));
        String line = "The post office" + (pm == null ? "" : " (" + pm.displayNameCap() + ", postman)") + ": "
            + (out == 0 ? "nothing waiting to go" : out + (out == 1 ? " letter" : " letters") + " waiting to go to " + String.join(", ", towns))
            + (in > 0 ? "; " + in + " to deliver" : "") + ".";
        return List.of("RN|" + line);
    }

    static List<String> book(ServerLevel level, UUID village) {
        if (office(village) == null) return List.of();
        CompoundTag t = Civics.town(village);
        int carrying = 0, waiting = at(holes(village)).size();
        for (Tag x : Civics.letters()) {
            if (x instanceof CompoundTag c && c.getString("fromTown").equals(village.toString()) && c.getString("where").startsWith("bag:")) carrying++;
        }
        return List.of("The post: " + t.getInt("posted") + " letters posted here in all; " + waiting + " at the counter, "
            + carrying + " of ours on the road.");
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: a letter written now by this folk to that one (the stores' paper used), or null. */
    @Nullable
    public static CompoundTag writeForTests(ServerLevel level, VillageFolkEntity from, VillageFolkEntity to) {
        UUID town = to.ownerId();
        return town == null ? null : write(level, from, to.getUUID(), to.displayNameCap(), town);
    }

    /** Tests: who of its family and close friends this folk has in other towns, by name. */
    public static List<String> elsewhereForTests(VillageFolkEntity f, long now) {
        List<String> out = new ArrayList<>();
        for (String[] w : elsewhere(f, now)) out.add(w[1] + " (" + w[3] + ")");
        return out;
    }

    /** Tests: where a letter is now ("office:...", "bag:...", "hands:...", "read", "given"). */
    public static String whereForTests(CompoundTag c) {
        CompoundTag now = letter(c.getInt("n"));
        return now == null ? "gone" : now.getString("where");
    }

    /** Tests: a town's look at who lives there, its carriers, its round and its readers, now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase()) note(f, v.id(), level.getGameTime());
        }
        carriers(level, v);
        startRound(level, v, Civics.day(level));
        readLetters(level, v, Civics.day(level));
    }

    /** Tests: the answer written to this player, wherever it is now, or null. */
    @Nullable
    public static CompoundTag answerForTests(UUID player) {
        for (Tag t : Civics.letters()) {
            if (t instanceof CompoundTag c && c.getBoolean("reply") && player.toString().equals(c.getString("player"))) return c;
        }
        return null;
    }

    /** Tests: the letters waiting at a town's counter. */
    public static List<CompoundTag> counterForTests(UUID village) {
        return at(holes(village));
    }

    /** Tests: a letter in a folk's hands read now. */
    public static void readForTests(ServerLevel level, VillageFolkEntity f) {
        List<CompoundTag> mine = at(hands(f.getUUID()));
        if (!mine.isEmpty()) read(level, f, mine.get(0), Civics.day(level));
    }

    /** Tests: the letters in a folk's hands, or bag. */
    public static int heldForTests(VillageFolkEntity f, boolean bag) {
        return at(bag ? bag(f.getUUID()) : hands(f.getUUID())).size();
    }

    /** Tests: is the postman out on its round? */
    public static boolean onRoundForTests(VillageFolkEntity f) {
        return ROUNDS.containsKey(f.getUUID());
    }

    /** Tests: this folk sets out on the road from one town to another (as a caravan's carrier does), or comes home. */
    public static void setOutForTests(VillageFolkEntity carrier, @Nullable Villages.Village from, @Nullable Villages.Village to) {
        carrier.trip(from == null || to == null ? null : new Caravans.Trip(from.id(), to.id(), Caravans.way(from, to)));
    }

    /** Tests: the town's postman, chosen now if need be. */
    @Nullable
    public static VillageFolkEntity postmanForTests(ServerLevel level, UUID village) {
        return postman(level, village);
    }
}
