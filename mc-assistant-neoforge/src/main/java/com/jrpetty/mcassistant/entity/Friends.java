package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.FavouritesPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] Friendships, folk with folk and folk with players, by level: an acquaintance, a friend, a close friend, a best
 * friend. The warmth they are read from is the warmth the folk already keep (Social.Life.affinity between folk;
 * Persona.affinity for a player); what builds it here is time spent together: a chat at the tavern or a stall, a call on
 * the step, a dinner, a club night (Clubs), help given.
 * <ul>
 * <li><b>Neighbours drop round</b> on a day off (WeekendLife: FRIEND): a word on the step, both the warmer for it.</li>
 * <li><b>Dinner</b>, at midday on a Saturday or Sunday: a folk asks a friend round to its house and they eat together;
 *     and a folk that likes a player well asks the player, as a planner entry and a toast, answered by a word ("I'd love
 *     to come" or "Sorry, I can't make it": talk buttons) or on the week page. The host waits at home at the hour; a
 *     player who comes sits down to a plate of whatever the host has in, and is the better friend for it; one who said
 *     yes and never came is not.</li>
 * <li><b>Best friends at the big days</b>: a couple's best friends are named at their wedding and give the toast
 *     (Assemblies), and a child's parents' best friends stand at the font at its christening (Church).</li>
 * </ul>
 */
public final class Friends {

    private Friends() {}

    public enum Level {
        STRANGER("a stranger"), ACQUAINTANCE("an acquaintance"), FRIEND("a friend"), CLOSE("a close friend"), BEST("a best friend");

        public final String words;

        Level(String words) {
            this.words = words;
        }
    }

    /** The warmth each level starts at (Social: FRIEND 30, CLOSE 60). A best friend is the warmest close friend. */
    public static final int ACQUAINTANCE = 5, FRIEND = Social.FRIEND, CLOSE = Social.CLOSE, BEST = 75;
    /** What a chat, a call, a dinner and a club night add, each side. */
    public static final int CHAT = 1, CALL = 3, DINNER = 6, PLAYER_DINNER = 12, CLUB = 2, STOOD_UP = -6;

    /** The level one folk holds another at. */
    public static Level level(VillageFolkEntity f, UUID other) {
        int a = f.life().affinity(other);
        if (a >= BEST && other.equals(best(f))) return Level.BEST;
        if (a >= CLOSE) return Level.CLOSE;
        if (a >= FRIEND) return Level.FRIEND;
        if (a >= ACQUAINTANCE || f.life().bonds.containsKey(other)) return Level.ACQUAINTANCE;
        return Level.STRANGER;
    }

    /** The level a folk holds a player at (its opinion of the player). */
    public static Level playerLevel(VillageFolkEntity f, UUID player) {
        int a = f.persona().affinity(player);
        if (a >= 80) return Level.BEST;
        if (a >= CLOSE) return Level.CLOSE;
        if (a >= FRIEND) return Level.FRIEND;
        if (a >= ACQUAINTANCE) return Level.ACQUAINTANCE;
        return Level.STRANGER;
    }

    /** Its best friend: the warmest of its friends at BEST or over, not its partner; null if none is that close. */
    @Nullable
    public static UUID best(VillageFolkEntity f) {
        UUID b = f.life().bestFriend();
        return b != null && f.life().affinity(b) >= BEST ? b : null;
    }

    /** Both warmer to each other. */
    public static void chatted(VillageFolkEntity a, VillageFolkEntity b, int n) {
        if (a == b) return;
        a.life().feel(b.getUUID(), b.displayNameCap(), n);
        b.life().feel(a.getUUID(), a.displayNameCap(), n);
    }

    /** A friend to call on today: its best friend, else a close friend, else a friend, else a neighbour it knows; loaded and up. */
    @Nullable
    public static VillageFolkEntity toCallOn(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        VillageFolkEntity pick = null;
        int warmest = ACQUAINTANCE + 4;
        for (Map.Entry<UUID, Social.Bond> e : f.life().bonds.entrySet()) {
            if (e.getKey().equals(f.life().partner())) continue;
            int a = e.getValue().affinity;
            if (a < warmest) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity o) || !o.isAlive() || o.isBaby() || o.isSleeping()
                    || Visitors.is(o) || o.distanceToSqr(f) > 120 * 120) continue;
            if (o.onShift() && !o.offWorkNow()) continue;                // not to be called on at work
            warmest = a;
            pick = o;
        }
        return pick;
    }

    /** A call on the step: a word each, both warmer, and remembered. */
    static void dropRound(ServerLevel level, VillageFolkEntity f, VillageFolkEntity o, long day) {
        chatted(f, o, CALL);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Only me. Thought I'd look in.", "Kettle on?", "Was passing. Wasn't, really.",
            "Not interrupting, am I?", "Heard you'd a new gate. It's a gate.", "Brought nothing, I'm afraid. Just me."));
        o.sayLater(FolkTalk.pick(o.getRandom(), "Come in, then.", "You're letting the warm out.", "Sit down before you fall down.",
            "I was just thinking about you. Not much, mind.", "If it's about the hedge, it's not mine.", "Go on, five minutes."), 30);
        o.getLookControl().setLookAt(f, 30.0F, 30.0F);
        f.persona().remember(day, "I called round on " + o.displayNameCap(), 2);
        o.persona().remember(day, f.displayNameCap() + " dropped round", 2);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, o.getX(), o.getEyeY() + 0.4, o.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
    }

    // ------------------------------------------------------------------ dinners

    /** Dinner is at midday, at the host's house: from a little before noon to an hour after. */
    public static final long DINNER_AT = 6000L, DINNER_FROM = 5400L, DINNER_TO = 8000L;

    /** An invitation: who asks whom (folk or player), to whose house, on what day, and how it stands. */
    public static final class Invite {
        public final UUID host, guest;
        public final String hostName, guestName;
        public final boolean player;
        public final long day;
        public final UUID village;
        public String status = "asked";            // asked, yes, no, done, missed
        boolean seated;

        Invite(UUID host, String hostName, UUID guest, String guestName, boolean player, long day, UUID village) {
            this.host = host;
            this.hostName = hostName;
            this.guest = guest;
            this.guestName = guestName;
            this.player = player;
            this.day = day;
            this.village = village;
        }

        public long when() {
            return day * 24000L + DINNER_AT;
        }
    }

    private static final Map<UUID, Invite> BY_GUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ASKED_ON = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BY_GUEST.clear();
        ASKED_ON.clear();
    }

    /** A player's invitation, if one is out to them. */
    @Nullable
    public static Invite inviteFor(UUID guest) {
        return BY_GUEST.get(guest);
    }

    /** The invitations a host has out (its own dinner). */
    static List<Invite> hosting(UUID host) {
        List<Invite> out = new ArrayList<>();
        for (Invite i : BY_GUEST.values()) if (i.host.equals(host)) out.add(i);
        return out;
    }

    /** The next weekend day's dinner from today: today if it is a weekend morning, else the coming Saturday. */
    static long nextDinnerDay(long day, long t) {
        if (Calendar.isWeekend(day) && t < DINNER_FROM) return day;
        if (Calendar.day(day) == Calendar.Day.SATURDAY) return day + 1;
        return day + Calendar.daysTo(day, Calendar.Day.SATURDAY);
    }

    /** The town's morning round: a host or two ask their friends to dinner, and a player a folk likes well is asked. */
    static void round(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        // Stale ones go.
        BY_GUEST.values().removeIf(i -> i.day < day || i.day == day && t > DINNER_TO && !"done".equals(i.status) && settle(level, i));
        if (t < 300 || t > 5000) return;
        if (Long.valueOf(day).equals(ASKED_ON.get(v.id()))) return;
        ASKED_ON.put(v.id(), day);
        long dinner = nextDinnerDay(day, t);
        if (dinner - day > 3 || !WorkHours.kept(v.id(), day) && !forceForTests) return;
        // Folk ask folk: the generous and the chatty with a friend, one in four of them.
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || Visitors.is(f) || !hosting(f.getUUID()).isEmpty()) continue;
            if (BY_GUEST.containsKey(f.getUUID())) continue;
            if (!LifeWeights.has(f, LifeWeights.Mark.CHATTY) && !LifeWeights.has(f, LifeWeights.Mark.GENEROUS) || LifeWeights.roll(f, day, 7) > 0.35) continue;
            VillageFolkEntity g = toCallOn(f);
            if (g == null) g = anyFriend(level, f);
            if (g == null || BY_GUEST.containsKey(g.getUUID()) || !hosting(g.getUUID()).isEmpty()) continue;
            Invite i = new Invite(f.getUUID(), f.displayNameCap(), g.getUUID(), g.displayNameCap(), false, dinner, v.id());
            i.status = "yes";
            BY_GUEST.put(g.getUUID(), i);
            LifeLog.note(f, day, "Asked " + g.displayNameCap() + " round for dinner " + Calendar.when(day, dinner),
                LifeWeights.why(f, LifeWeights.Mark.GENEROUS, LifeWeights.Mark.CHATTY));
        }
        // A player a folk likes well.
        for (ServerPlayer p : level.players()) {
            if (!p.blockPosition().closerThan(v.centre(), 200) || BY_GUEST.containsKey(p.getUUID())) continue;
            VillageFolkEntity host = null;
            int warmest = FRIEND - 1;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || Visitors.is(f) || !hosting(f.getUUID()).isEmpty()) continue;
                int aff = f.persona().affinity(p.getUUID()) + (LifeWeights.has(f, LifeWeights.Mark.CHATTY) ? 10 : 0)
                    + (LifeWeights.has(f, LifeWeights.Mark.SHY) ? -15 : 0);
                if (aff > warmest) { warmest = aff; host = f; }
            }
            if (host != null) invitePlayer(level, v, host, p, dinner);
        }
    }

    /** Tests: invitations go out in any town. */
    public static volatile boolean forceForTests = false;

    @Nullable
    static VillageFolkEntity anyFriend(ServerLevel level, VillageFolkEntity f) {
        for (Social.Bond b : f.life().friends()) {
            for (Map.Entry<UUID, Social.Bond> e : f.life().bonds.entrySet()) {
                if (e.getValue() == b && level.getEntity(e.getKey()) instanceof VillageFolkEntity o && o.isAlive() && !o.isBaby()) return o;
            }
        }
        return null;
    }

    /** A player asked to dinner: on their planner, a toast, a word in chat; answered by talking to the host. */
    public static Invite invitePlayer(ServerLevel level, Villages.Village v, VillageFolkEntity host, ServerPlayer p, long day) {
        Invite i = new Invite(host.getUUID(), host.displayNameCap(), p.getUUID(), p.getGameProfile().getName(), true, day, v.id());
        BY_GUEST.put(p.getUUID(), i);
        BlockPos home = Homes.homeOf(host);
        long today = level.getDayTime() / 24000L;
        String when = Calendar.when(today, day) + " at noon";
        Planner.add(p, i.when(), "dinner", "Dinner at " + host.displayNameCap() + "'s, " + Calendar.name(day) + " noon: say yes or no to them",
            home != null ? home : host.blockPosition());
        toast(p, host, "invite", "Asks you to dinner " + when + ". Talk to them to say yes or no.");
        p.sendSystemMessage(Component.literal(host.displayNameCap() + " has asked you to dinner " + when + ", at their house. "
            + "Talk to them to accept or decline; it's on your planner."));
        LifeLog.note(host, today, "Asked " + p.getGameProfile().getName() + " round for dinner " + Calendar.when(today, day), null);
        return i;
    }

    /** A toast on the player's screen, with the folk's name (the favourites' toast). */
    static void toast(ServerPlayer p, VillageFolkEntity f, String event, String text) {
        if (p.connection == null || !p.connection.hasChannel(FavouritesPayload.TYPE)) return;
        CompoundTag t = new CompoundTag();
        t.putString("kind", "toast");
        t.putUUID("uuid", f.getUUID());
        t.putString("name", f.displayNameCap());
        t.putString("event", event);
        t.putString("text", text);
        PacketDistributor.sendToPlayer(p, new FavouritesPayload(t, false));
    }

    /** "I'd love to come" / "Sorry, I can't": the answer, from talk or the week page. */
    public static String answer(ServerPlayer p, boolean yes) {
        Invite i = BY_GUEST.get(p.getUUID());
        if (i == null || !i.player) return "!Nobody has asked you to dinner just now. Folk who like you well ask on a weekday, for the weekend.";
        if (!"asked".equals(i.status)) return "!You've already said " + ("yes".equals(i.status) ? "yes" : "no") + " to " + i.hostName + ".";
        i.status = yes ? "yes" : "no";
        long today = p.level().getDayTime() / 24000L;
        VillageFolkEntity host = p.serverLevel().getEntity(i.host) instanceof VillageFolkEntity h ? h : null;
        if (yes) {
            Planner.add(p, i.when(), "dinner", "Dinner at " + i.hostName + "'s, " + Calendar.name(i.day) + " noon (you said yes)",
                host != null && Homes.homeOf(host) != null ? Homes.homeOf(host) : null);
            if (host != null) host.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 3);
            return "You said yes. Be at " + i.hostName + "'s house " + Calendar.when(today, i.day) + " around noon; it's on your planner, and Show me on the week page marks the house.";
        }
        if (host != null) host.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), -1);
        BY_GUEST.remove(p.getUUID());
        return "You said no. " + i.hostName + " took it well enough. They may ask again another week.";
    }

    /** What the host says when the player answers in words (FolkTalk). */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (!(p instanceof ServerPlayer sp)) return null;
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        Invite i = BY_GUEST.get(p.getUUID());
        if (i == null || !i.host.equals(f.getUUID())) return null;
        boolean yes = t.contains("love to come") || t.contains("i'll come to dinner") || t.contains("accept the invitation") || t.contains("yes to dinner");
        boolean no = t.contains("can't make") || t.contains("cant make") || t.contains("decline the invitation") || t.contains("no to dinner");
        if (!yes && !no) return null;
        String said = answer(sp, yes);
        if (said.startsWith("!")) return said.substring(1);
        return yes ? FolkTalk.pick(f.getRandom(), "Good. Noon. Don't bring anything.", "Noon, then. I'll do too much, I always do.",
                "Splendid. Mind the step, it's loose.", "Noon. You'll know the house, it's the one with me in it.",
                "I'll tell the others you're coming. There are no others.", "Good. Come hungry.") + " (" + said + ")"
            : FolkTalk.pick(f.getRandom(), "Another time.", "Your loss. It's a good pie.", "Fair enough.", "Next week, maybe.",
                "I'll eat yours, then.", "No matter.") + " (" + said + ")";
    }

    /** Does this host have a player's yes for today? (FolkTalk's flag for the talk buttons, the hold.) */
    public static boolean asked(UUID host, UUID player) {
        Invite i = BY_GUEST.get(player);
        return i != null && i.host.equals(host) && "asked".equals(i.status);
    }

    /** Its dinner, a beat of it, at the hour (LifeTick.hold): the host at home, its guest come to it. True while it is on. */
    static boolean hold(ServerLevel level, VillageFolkEntity f) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t < DINNER_FROM || t >= DINNER_TO) return false;
        Invite mine = BY_GUEST.get(f.getUUID());
        if (mine != null && mine.day == day && "yes".equals(mine.status) && !mine.player) {
            VillageFolkEntity host = level.getEntity(mine.host) instanceof VillageFolkEntity h ? h : null;
            if (host == null) return false;
            BlockPos home = Homes.homeOf(host);
            BlockPos to = home != null ? home : host.blockPosition();
            f.hobbyNow = "round at " + host.displayNameCap() + "'s for dinner";
            if (f.distanceToSqr(host) > 9) {
                if (f.getNavigation().isDone()) f.walkTo(host.blockPosition(), 0.8D);
                return true;
            }
            f.getNavigation().stop();
            f.getLookControl().setLookAt(host, 30.0F, 30.0F);
            if (!mine.seated) dine(level, host, f, null, mine, day);
            return true;
        }
        for (Invite i : hosting(f.getUUID())) {
            if (i.day != day || !("yes".equals(i.status) || "done".equals(i.status) && t < i.when() + 1500)) continue;
            BlockPos home = Homes.homeOf(f);
            BlockPos at = home != null ? home : f.blockPosition();
            f.hobbyNow = "at home, " + ("done".equals(i.status) ? "at dinner with " : "expecting ") + i.guestName + " for dinner";
            if (f.blockPosition().distSqr(at) > 9) {
                if (f.getNavigation().isDone()) f.walkTo(at, 0.8D);
                return true;
            }
            f.getNavigation().stop();
            if (i.player && "yes".equals(i.status) && level.getPlayerByUUID(i.guest) instanceof ServerPlayer p && p.distanceToSqr(f) < 25) {
                f.getLookControl().setLookAt(p, 30.0F, 30.0F);
                dine(level, f, null, p, i, day);
            }
            return true;
        }
        return false;
    }

    /** Sat down to dinner: a plate each, the talk, both the warmer and remembering it. */
    static void dine(ServerLevel level, VillageFolkEntity host, @Nullable VillageFolkEntity guest, @Nullable ServerPlayer player, Invite i, long day) {
        if (i.seated) return;
        i.seated = true;
        i.status = "done";
        ItemStack plate = new ItemStack(host.countCarried(s -> s.is(Items.PUMPKIN_PIE)) > 0 ? Items.PUMPKIN_PIE
            : host.countCarried(s -> s.is(Items.BAKED_POTATO)) > 0 ? Items.BAKED_POTATO : Items.BREAD);
        boolean fromPack = host.removeMatching(s -> s.is(plate.getItem()), 1) > 0;
        level.playSound(null, host.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6F, 1.0F);
        level.sendParticles(ParticleTypes.HEART, host.getX(), host.getEyeY() + 0.5, host.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
        FolkTalk.speak(host, FolkTalk.pick(host.getRandom(), "Sit anywhere. Not there.", "It's only what was in.", "Help yourself. Then help yourself again.",
            "I won't say who taught me this.", "Mind, it's hot. It was hot an hour ago.", "Grace? Go on, then: thanks. Eat."));
        if (guest != null) {
            chatted(host, guest, DINNER);
            guest.sayLater(FolkTalk.pick(guest.getRandom(), "Lovely, this.", "You've outdone yourself.", "Is there more of the gravy?",
                "My mother made it drier.", "Best meal I've had since the last one.", "I'll do you one back next week."), 40);
            LifeLog.count(guest, LifeLog.Stat.DINNERS, 1);
            LifeLog.note(guest, day, "Had dinner at " + host.displayNameCap() + "'s", null);
            guest.persona().remember(day, "dinner at " + host.displayNameCap() + "'s", 4);
            Meals.ateOut(guest, level);
        }
        if (player != null) {
            host.persona().feelFor(player.getUUID(), player.getGameProfile().getName(), PLAYER_DINNER);
            ItemStack give = fromPack ? plate : new ItemStack(Items.BREAD);
            if (!player.getInventory().add(give)) player.drop(give, false);
            player.sendSystemMessage(Component.literal("Dinner at " + host.displayNameCap() + "'s: you're handed " + Stalls.words(give)
                + ". They think the better of you for coming (now " + playerLevel(host, player.getUUID()).words + ")."));
            Standing.stir(i.village, player.getUUID());
        }
        Meals.ateOut(host, level);
        LifeLog.count(host, LifeLog.Stat.DINNERS, 1);
        LifeLog.note(host, day, "Had " + i.guestName + " round for dinner", null);
        host.persona().remember(day, i.guestName + " came to dinner", 4);
    }

    /** A dinner past its hour: a player who said yes and never came is thought the less of. True: let it go. */
    static boolean settle(ServerLevel level, Invite i) {
        if (i.player && "yes".equals(i.status) && level.getEntity(i.host) instanceof VillageFolkEntity h) {
            h.persona().feelFor(i.guest, i.guestName, STOOD_UP);
            LifeLog.note(h, i.day, "Kept a plate warm for " + i.guestName + ", who never came", null);
            if (level.getPlayerByUUID(i.guest) instanceof ServerPlayer p) {
                p.sendSystemMessage(Component.literal(h.displayNameCap() + " waited dinner for you, and you never came. They mind."));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the big days

    /** The day's toasts: who gives one, what, and when (the town's round speaks them). */
    record Toast(UUID who, String line, long at) {}

    private static final Map<UUID, List<Toast>> TOASTS = new ConcurrentHashMap<>();

    /**
     * A wedding called (Assemblies): each of the couple's best friends named, and given the toast a minute into it. Returns
     * the best friends' names, for the tests and the chronicle.
     */
    public static List<String> weddingGuests(ServerLevel level, Villages.Village v, UUID a, UUID b, long day) {
        List<String> out = new ArrayList<>();
        for (UUID u : new UUID[]{ a, b }) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity one)) continue;
            UUID bf = best(one);
            if (bf == null || bf.equals(a) || bf.equals(b) || !(level.getEntity(bf) instanceof VillageFolkEntity friend)) continue;
            out.add(friend.displayNameCap());
            friend.persona().remember(day, "I gave the toast at " + one.displayNameCap() + "'s wedding", 6);
            LifeLog.note(friend, day, "Stood with " + one.displayNameCap() + " at the wedding, as best friend", null);
            String line = FolkTalk.pick(level.getRandom(), "To " + one.displayNameCap() + ", who I've known longer than is wise. Be happy.",
                "I was asked to keep this short. To the two of them.", "I said it would never last. I've been wrong before. To them.",
                one.displayNameCap() + " is the best friend I have. Look after them. Glasses up.", "To the happy couple, and to whoever's paying.",
                "I'll not tell the story about the pig. To them.");
            TOASTS.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(new Toast(bf, line, level.getGameTime() + 1200 + out.size() * 200L));
        }
        if (!out.isEmpty()) Villages.tell(v.id(), day, String.join(" and ", out) + (out.size() == 1 ? " stood as best friend" : " stood as best friends") + " at the wedding");
        return out;
    }

    /** The toasts due, spoken (the town's round). */
    static void speakToasts(ServerLevel level, UUID village) {
        List<Toast> l = TOASTS.get(village);
        if (l == null || l.isEmpty()) return;
        long now = level.getGameTime();
        l.removeIf(t -> {
            if (now < t.at()) return false;
            if (level.getEntity(t.who()) instanceof VillageFolkEntity f && f.isAlive()) {
                FolkTalk.speak(f, t.line());
                level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ the card and the player's page

    /** Its friendships in a line: "Best friend: Wren. Close: Ash, Bryn. Friends: 3. Knows: 6." */
    public static String cardLine(VillageFolkEntity f) {
        List<String> close = new ArrayList<>();
        int friends = 0, known = 0;
        UUID bf = best(f);
        String bestName = null;
        for (Map.Entry<UUID, Social.Bond> e : f.life().bonds.entrySet()) {
            if (e.getKey().equals(f.life().partner())) continue;
            int a = e.getValue().affinity;
            if (e.getKey().equals(bf)) bestName = e.getValue().name;
            else if (a >= CLOSE) close.add(e.getValue().name);
            else if (a >= FRIEND) friends++;
            else if (a >= ACQUAINTANCE) known++;
        }
        List<String> parts = new ArrayList<>();
        if (bestName != null) parts.add("best friend " + bestName);
        if (!close.isEmpty()) parts.add("close friends " + String.join(", ", close.subList(0, Math.min(3, close.size()))));
        if (friends > 0) parts.add(friends + (friends == 1 ? " friend" : " friends"));
        if (known > 0) parts.add(known + (known == 1 ? " acquaintance" : " acquaintances"));
        return parts.isEmpty() ? "" : FolkTalk.cap(String.join("; ", parts));
    }
}
