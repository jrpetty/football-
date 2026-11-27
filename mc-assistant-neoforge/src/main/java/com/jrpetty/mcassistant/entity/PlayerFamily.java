package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [featfolk] A player's own family in a town: courting a folk, marrying it in the town's chapel, a home together, and
 * children of their own. Everything is done in the talk screen's buttons (TalkMenu's "Home" group) and in the world;
 * nothing is typed as a command.
 *
 * <ul>
 * <li><b>Courting.</b> "Will you walk out with me?" to a grown folk spoken for by nobody (and walking out with nobody),
 *     who likes the player ({@link #FOND}) in a town that respects them (at least Accepted: TownOpinion.rating). Then
 *     walks ("Take a walk with me": it comes along beside you a while), suppers ("Supper with me tonight?", five coins
 *     at the tavern), and gifts (the talk's Gift); each warms it, and the reply says how fond it is now.</li>
 * <li><b>The proposal</b>, a button: after {@link #WALKS} walks, a supper and a gift, with it fond enough ({@link #WED}),
 *     it says yes, and the town holds the wedding the next evening, dressed for what the town has (WeddingDecor), the
 *     player at the front with it. The guests remember seeing it (Witness: "Saw you wed Fen in our chapel").</li>
 * <li><b>A home together.</b> Wed, it moves into the player's home: a bed by the player's own (where the player sleeps,
 *     in or near the town), else the player's house in the town; with neither, the player is welcome in its house. It keeps
 *     its own trade and its own days, greets the player as its spouse and talks of what it remembers ({@link #spouseLine}).</li>
 * <li><b>Children.</b> "Shall we try for a baby?": that night, the two of them asleep in the same house, hearts over the
 *     bed and nothing more (the way the game's own villagers have children: no more is shown, or said). Soon after it is
 *     announced, the spouse puts a crib up by the bed over the next days, and on the day the child is born at home, the
 *     healer at the bedside and the bell rung. The player names it (the talk's "Let's call the baby ..."). It is a child of
 *     the town like any other: it grows up over the days (baby, child, youngster, grown), goes to school and to its
 *     parent's side at work, takes after its folk parent and is its heir (Lineage).</li>
 * </ul>
 * Kept in the town's ledger notes ("pfam/..."), so it lasts with the world.
 */
public final class PlayerFamily {

    private PlayerFamily() {}

    /** How fond a folk must be to walk out with a player, and to marry one. */
    public static final int FOND = 30, WED = 70;
    /** Walks before a proposal. */
    public static final int WALKS = 2;
    /** What a supper at the tavern costs. */
    public static final int SUPPER = 5;
    /** Days from the news to the birth. */
    public static final int CARRY_DAYS = 3;

    // ------------------------------------------------------------------ the ledger

    private static String key(UUID player, String what) { return "pfam/" + player + "/" + what; }

    static String get(UUID village, UUID player, String what) {
        String s = Ledger.note(village, key(player, what));
        return s == null ? "" : s;
    }

    static void put(UUID village, UUID player, String what, String value) {
        Ledger.note(village, key(player, what), value);
    }

    static int num(UUID village, UUID player, String what) {
        try { return Integer.parseInt(get(village, player, what)); } catch (NumberFormatException e) { return 0; }
    }

    static long day(UUID village, UUID player, String what) {
        try { return Long.parseLong(get(village, player, what)); } catch (NumberFormatException e) { return -1; }
    }

    @Nullable
    static UUID id(UUID village, UUID player, String what) {
        try { return UUID.fromString(get(village, player, what)); } catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------ who is what to whom

    /** The folk this player is courting in this town, or null. */
    @Nullable
    public static UUID courting(UUID village, UUID player) { return id(village, player, "court"); }

    /** The home this player shares with its spouse here (by its bed), or null. */
    @Nullable
    public static BlockPos homeOf(UUID village, UUID player) {
        try { return BlockPos.of(Long.parseLong(get(village, player, "home"))); } catch (NumberFormatException e) { return null; }
    }

    /** This player's spouse in this town, or null. */
    @Nullable
    public static UUID spouse(UUID village, UUID player) { return id(village, player, "spouse"); }

    public static boolean isSpouse(VillageFolkEntity f, Player p) {
        return f.ownerId() != null && f.getUUID().equals(spouse(f.ownerId(), p.getUUID()));
    }

    public static boolean isCourting(VillageFolkEntity f, Player p) {
        return f.ownerId() != null && f.getUUID().equals(courting(f.ownerId(), p.getUUID()));
    }

    /** Married to anybody, in any town it has a family in. */
    static boolean married(ServerLevel level, UUID player) {
        for (Villages.Village v : Villages.every()) if (spouse(v.id(), player) != null) return true;
        return false;
    }

    /** Can this player court this folk? Null if so, else why not (in its words). */
    @Nullable
    static String whyNot(VillageFolkEntity f, Player p) {
        RandomSource r = f.getRandom();
        UUID village = f.ownerId();
        if (village == null) return "I'm not from round here. It wouldn't work.";
        if (f.isBaby()) return "Ask me again when I'm grown. Better yet, don't.";
        if (isSpouse(f, p)) return "We're already wed, you know.";
        if (isCourting(f, p)) return "We're already walking out. Did you forget?";
        if (f.life().partner() != null) return FolkTalk.pick(r, "I'm spoken for. You knew that.", "I'm married. Happily, mostly.",
            "There's someone already. Sorry.", "I'm taken. Flattered, though.", "No. I've a partner, and they're bigger than you.",
            "I'm wed. Ask someone who isn't.");
        if (f.lines().courting() != null) return "I'm walking out with " + f.lines().courtingName() + ". So, no.";
        if (married((ServerLevel) f.level(), p.getUUID()) || courting(village, p.getUUID()) != null)
            return FolkTalk.pick(r, "Aren't you already spoken for?", "I hear you're seeing someone. No.", "One at a time, I think.",
                "I've heard about you and someone else. No.", "Sort out the one you've got first.", "No. You're taken, and I'm not daft.");
        int score = Standing.of(village, p.getUUID(), f.level().getGameTime()).score();
        if (score < 10) return FolkTalk.pick(r, "Folk here don't think enough of you yet. Nor do I, I'm afraid.",
            "Get yourself known in " + Villages.name(village) + " first. Then ask.", "My family would never hear of it. Not yet.",
            "The town hasn't made its mind up about you. I'll wait till it has.", "You're " + TownOpinion.rating(score, 1).toLowerCase(Locale.ROOT)
                + " here. That's not enough for me.", "Earn a bit of respect round here first.");
        if (f.persona().affinity(p.getUUID()) < FOND) return FolkTalk.pick(r, "I hardly know you.", "We've barely spoken.",
            "Talk to me a while first. Then we'll see.", "Not yet. Maybe not ever. Let's start with hello.", "You're getting ahead of yourself.",
            "Bring me something nice and ask again in a week.");
        return null;
    }

    // ------------------------------------------------------------------ the talk

    /**
     * Said to a folk in the talk screen (FolkTalk.answer): the courting buttons, the proposal, the baby, the naming. Null
     * if it is none of these.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("will you walk out with me")) return walkOut(f, p);
        if (t.startsWith("take a walk with me")) return stroll(f, p);
        if (t.startsWith("supper with me")) return supper(f, p);
        if (t.startsWith("will you marry me")) return propose(f, p);
        if (t.startsWith("shall we try for a baby")) return tryForBaby(f, p);
        if (t.startsWith("how was your day")) return spouseLine(f, p);
        if (t.startsWith("let's call the baby ") || t.startsWith("lets call the baby ")) return name(f, p, text.trim().substring(text.trim().indexOf("baby ") + 5));
        return null;
    }

    static String fondness(VillageFolkEntity f, Player p) {
        return " (Fondness " + f.persona().affinity(p.getUUID()) + " of 100)";
    }

    static String walkOut(VillageFolkEntity f, Player p) {
        String no = whyNot(f, p);
        if (no != null) return no;
        UUID village = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        put(village, p.getUUID(), "court", f.getUUID().toString());
        put(village, p.getUUID(), "courtName", f.displayNameCap());
        put(village, p.getUUID(), "walks", "0");
        put(village, p.getUUID(), "dates", "0");
        put(village, p.getUUID(), "gifts", "0");
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 4);
        f.persona().remember(day, "I started walking out with " + p.getName().getString(), 7);
        Villages.tell(village, day, f.displayNameCap() + " is walking out with " + p.getName().getString());
        FolkAction.mark(f, FolkAction.Mark.HEART);
        heart(f);
        return FolkTalk.pick(f.getRandom(), "Walk out? With you? ...Go on, then.", "I'd like that. Don't make me regret it.",
            "Yes. I'd wondered if you'd ask.", "All right. But you're buying supper.", "I suppose I could be persuaded. I'm persuaded.",
            "Yes. Tell nobody. Everybody will know by noon.") + fondness(f, p)
            + " (Walking out. Next: two walks, a supper and a gift; then it may say yes to 'Will you marry me?'.)";
    }

    static String stroll(VillageFolkEntity f, Player p) {
        if (!isCourting(f, p) && !isSpouse(f, p)) return "A walk? Ask me to walk out with you first.";
        UUID village = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        if (day(village, p.getUUID(), "walkDay") == day) return FolkTalk.pick(f.getRandom(), "We walked today already. My feet remember.",
            "Tomorrow. I've a day to get through.", "Once a day is plenty.", "Again? Tomorrow.", "I'm walked out for today.", "Tomorrow, same time.");
        if (f.level().isNight()) return "In the dark? Tomorrow.";
        put(village, p.getUUID(), "walkDay", Long.toString(day));
        if (isCourting(f, p)) put(village, p.getUUID(), "walks", Integer.toString(num(village, p.getUUID(), "walks") + 1));
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 6);
        f.persona().remember(day, "I went walking with " + p.getName().getString(), 4);
        // It comes along beside you a while: the hearts as it goes.
        if (!f.isFollowing(p)) FolkTalk.follow(f, p);
        heart(f);
        return FolkTalk.pick(f.getRandom(), "Lead on. Not too fast.", "Where are we going? Never mind. Anywhere.", "I know a good path. You don't.",
            "A walk. Yes. I could do with one.", "If we pass the baker's, we're stopping.", "Fine. You set the pace, I'll complain about it.")
            + fondness(f, p) + (isCourting(f, p) ? " (" + progress(village, p.getUUID(), f) + ")" : "");
    }

    static String supper(VillageFolkEntity f, Player p) {
        if (!isCourting(f, p) && !isSpouse(f, p)) return "Supper? Ask me to walk out with you first.";
        UUID village = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        if (day(village, p.getUUID(), "dateDay") == day) return "We've had supper together today already.";
        if (Market.coinsHeld(p) < SUPPER) return "Supper's " + SUPPER + " coins at the tavern. You've not got it. I'll not pay for both of us.";
        Market.payOut(p, SUPPER);
        Ledger.addCoins(village, SUPPER);
        put(village, p.getUUID(), "dateDay", Long.toString(day));
        if (isCourting(f, p)) put(village, p.getUUID(), "dates", Integer.toString(num(village, p.getUUID(), "dates") + 1));
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 8);
        f.persona().remember(day, "I had supper with " + p.getName().getString(), 5);
        if (f.level() instanceof ServerLevel sl) Witness.seen(sl, p, Witness.Kind.GIFT, "supper at the tavern", f, 0);
        heart(f);
        return FolkTalk.pick(f.getRandom(), "Supper. You're paying, so I'll have the pie.", "That's kind. The stew's good on a weekday.",
            "Supper with you. People will talk. Let them.", "I'll meet you there. Don't be late; I'll eat yours.",
            "Thank you. I'll pretend I didn't see the price.", "Supper it is. I'll wear the good coat.") + fondness(f, p)
            + (isCourting(f, p) ? " (" + progress(village, p.getUUID(), f) + ")" : "");
    }

    /** A gift given while courting (FolkTalk.gift): counted. */
    public static void gifted(VillageFolkEntity f, Player p) {
        if (!isCourting(f, p)) return;
        UUID village = f.ownerId();
        put(village, p.getUUID(), "gifts", Integer.toString(num(village, p.getUUID(), "gifts") + 1));
    }

    /** How far on the courting is, for the talk and the player's page: "2 of 2 walks, 1 supper, 1 gift; fondness 64". */
    public static String progress(UUID village, UUID player, @Nullable VillageFolkEntity f) {
        return num(village, player, "walks") + " of " + WALKS + " walks, " + num(village, player, "dates") + " supper"
            + (num(village, player, "dates") == 1 ? "" : "s") + ", " + num(village, player, "gifts") + " gift" + (num(village, player, "gifts") == 1 ? "" : "s")
            + (f == null ? "" : "; fondness " + f.persona().affinity(player) + " (to marry: " + WED + ")");
    }

    /** Ready to be asked. */
    public static boolean proposable(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        return isCourting(f, p) && num(village, p.getUUID(), "walks") >= WALKS && num(village, p.getUUID(), "dates") >= 1
            && num(village, p.getUUID(), "gifts") >= 1 && f.persona().affinity(p.getUUID()) >= WED;
    }

    static String propose(VillageFolkEntity f, Player p) {
        RandomSource r = f.getRandom();
        if (isSpouse(f, p)) return "Again? We're wed. You were there.";
        if (!isCourting(f, p)) return FolkTalk.pick(r, "Marry you? We've not so much as walked out.", "You're getting ahead of yourself.",
            "I don't know you well enough for that.", "Ask me to walk out first. Then we'll see.", "No. And that's a quick no.", "Start with a walk.");
        UUID village = f.ownerId();
        if (!proposable(f, p)) return FolkTalk.pick(r, "Not yet. Ask me again when I'm sure.", "It's too soon. Walk with me a while longer.",
            "I'm fond of you. Not that fond. Yet.", "Give it time.", "Ask me after a few more walks.", "Not yet. Nearly.")
            + " (" + progress(village, p.getUUID(), f) + ")";
        // It cannot be taken back: asked once, it is asked to be sure; said again within the minute, it is asked.
        long now = f.level().getGameTime();
        long asked = day(village, p.getUUID(), "confirmPropose");
        if (asked < 0 || now - asked > 1200L) {
            put(village, p.getUUID(), "confirmPropose", Long.toString(now));
            return "(Ready: " + progress(village, p.getUUID(), f) + ". A proposal cannot be taken back: the wedding is planned for the next"
                + " evening. Press 'Will you marry me?' again to ask.)";
        }
        put(village, p.getUUID(), "confirmPropose", "");
        long day = f.level().getDayTime() / 24000L;
        put(village, p.getUUID(), "court", "");
        put(village, p.getUUID(), "engaged", f.getUUID().toString());
        f.life().partnerWith(p.getUUID(), p.getName().getString());
        Gatherings.pledgedWithPlayer(village, f, p, day);
        f.persona().remember(day, p.getName().getString() + " asked me to marry them", 10);
        Villages.tell(village, day, f.displayNameCap() + " and " + p.getName().getString() + " are to be wed");
        FolkAction.mark(f, FolkAction.Mark.HEART);
        if (f.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.2, f.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
        return FolkTalk.pick(r, "Yes. Yes. Don't make me say it a third time.", "I will. Tomorrow evening, then. The whole town will come.",
            "Yes. I'll tell my mother. You tell nobody, I want to see their faces.", "Of course. Took you long enough.",
            "Yes. I'll want a proper wedding, mind.", "I will. And you'll wear something clean.")
            + " (Engaged. The wedding is tomorrow evening in " + (Villages.builtAt(village, "chapel") != null ? "the chapel" : "the square")
            + ", dressed for what the town can afford; be there.)";
    }

    // ------------------------------------------------------------------ the wedding, and a home together

    /** The wedding was held (Assemblies' close, the wedding of a folk and a player): wed, witnessed, and moving in. */
    public static void wed(ServerLevel level, UUID village, UUID folkId, UUID player) {
        if (!(level.getEntity(folkId) instanceof VillageFolkEntity f)) return;
        long day = level.getDayTime() / 24000L;
        put(village, player, "engaged", "");
        put(village, player, "spouse", folkId.toString());
        put(village, player, "spouseName", f.displayNameCap());
        put(village, player, "wedDay", Long.toString(day));
        f.persona().remember(day, "I married " + nameOf(village, player, f), 10);
        Player p = level.getPlayerByUUID(player);
        if (p != null) {
            boolean chapel = Villages.builtAt(village, "chapel") != null;
            Witness.seen(level, p, Witness.Kind.WED, chapel ? "in our chapel" : "on the square", f, 6);
            Witness.dealt(f, p, Witness.Kind.WED, chapel ? "in our chapel" : "on the square");
        }
        moveIn(level, f, p);
    }

    static String nameOf(UUID village, UUID player, VillageFolkEntity f) {
        String n = f.life().partnerName();
        return n.isEmpty() ? "my love" : n;
    }

    /** The spouse moves into the player's home: a bed by the player's own, else the player's house in town. */
    static void moveIn(ServerLevel level, VillageFolkEntity f, @Nullable Player p) {
        UUID village = f.ownerId();
        if (village == null) return;
        BlockPos home = null;
        if (p instanceof ServerPlayer sp && sp.getRespawnPosition() != null && sp.getRespawnDimension() == level.dimension()) home = sp.getRespawnPosition();
        if (home == null || f.villageCentre() != null && home.distSqr(f.villageCentre()) > 160 * 160) home = null;
        if (home == null) {
            put(village, f.life().partner(), "home", "");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "You'll move in with me, then. Mind the cat.", "My house is our house. Wipe your feet.",
                "There's room at mine. I've cleared a shelf.", "Come home with me. It's small, but it's dry.", "My place, then. I'll make space.",
                "We'll live at mine. You can paint the door if you must."));
            return;
        }
        put(village, f.life().partner(), "home", Long.toString(home.asLong()));
        BlockPos bed = spareBedNear(level, home);
        if (bed == null) bed = home;
        f.moveIntoBed(bed);
        f.walkTo(bed, 0.9D);
        Villages.tell(village, level.getDayTime() / 24000L, f.displayNameCap() + " moved in with " + f.life().partnerName());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'll bring my things over. Don't move them.", "Your place, then. It needs a touch. Mine, apparently.",
            "I've brought my pillow. Non-negotiable.", "Moving in. Where do the spoons go?", "Home. Ours. That'll take some getting used to.",
            "I'll take the side by the window."));
    }

    /** A bed within a few blocks of the player's own that is not the player's, or null. */
    @Nullable
    static BlockPos spareBedNear(ServerLevel level, BlockPos home) {
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-6, -2, -6), home.offset(6, 2, 6))) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD && !p.equals(home)
                    && p.distSqr(home) > 2.0) return p.immutable();
        }
        return null;
    }

    /** As its spouse: a word about its day, and something it remembers. */
    public static String spouseLine(VillageFolkEntity f, Player p) {
        if (!isSpouse(f, p)) return "My day? Fine. Why do you ask?";
        RandomSource r = f.getRandom();
        String you = p.getName().getString();
        String head = FolkTalk.pick(r, "There you are, " + you + ".", "Home at last.", "You look tired. Sit down.", "Oh, it's you. Good.",
            "Wipe your boots, love.", "I was wondering when you'd turn up.");
        String day = FolkTalk.pick(r, "Work was work.", "Long day. Short temper. Better now.", "I got a lot done. You'd be proud.",
            "Somebody at work said something daft. I'll tell you over supper.", "Quiet day. I like those.", "The usual. Nobody died.");
        Persona.Memory m = f.persona().latest();
        String mem = m == null ? "" : " " + FolkTalk.pick(r, "I keep thinking about when ", "Do you remember when ", "I was telling someone about when ",
            "I thought about when ", "Funny, earlier I remembered when ", "Remember when ") + lower(m.text()) + "?";
        UUID village = f.ownerId();
        String kids = "";
        if (village != null && pregnantDay(village, p.getUUID()) >= 0) kids = " " + FolkTalk.pick(r, "The baby's kicking. Or I ate too fast.",
            "Not long now.", "I've been thinking about names.", "The crib's coming along.", "I'm eating for two. Don't comment.", "Soon. Very soon.");
        return head + " " + day + mem + kids;
    }

    // ------------------------------------------------------------------ children

    public static long pregnantDay(UUID village, UUID player) { return day(village, player, "expecting"); }

    static String tryForBaby(VillageFolkEntity f, Player p) {
        RandomSource r = f.getRandom();
        if (!isSpouse(f, p)) return FolkTalk.pick(r, "That's a question for after a wedding.", "Wed me first. Then ask.", "No.",
            "We're not married. Ask me something else.", "Not before a wedding, thank you.", "Certainly not.");
        UUID village = f.ownerId();
        if (pregnantDay(village, p.getUUID()) >= 0) return "There's one on the way already. One at a time.";
        if (!get(village, p.getUUID(), "naming").isEmpty()) return "Let's name the one we've got first.";
        if (Villages.headcount(village) >= Villages.housing(village)) return "Where would we put it? The town's full to the rafters. Wait for a house.";
        long day = f.level().getDayTime() / 24000L;
        put(village, p.getUUID(), "trying", Long.toString(day));
        return FolkTalk.pick(r, "A baby. Yes. I'd like that.", "Our own? ...Yes. Let's.", "I'd hoped you'd ask.", "All right. Bedtime, then. Early night.",
            "A little one. Yes. I'll warn my mother.", "Yes. We'll see what the stork thinks.") + " (Trying for a baby: the two of you home in bed tonight.)";
    }

    /** Is this couple trying for a baby (for the talk's status)? */
    public static boolean trying(UUID village, UUID player) {
        long d = day(village, player, "trying");
        return d >= 0;
    }

    /**
     * Every few seconds for each player (PlayerLife's tick): the night of trying for a baby (hearts over the bed while
     * both are home and the spouse asleep; nothing more); the news; the crib; the birth at home on the day.
     */
    public static void tick(ServerLevel level, ServerPlayer p) {
        for (Villages.Village v : Villages.every()) {
            UUID village = v.id();
            UUID sid = spouse(village, p.getUUID());
            if (sid == null) continue;
            if (!(level.getEntity(sid) instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            long day = level.getDayTime() / 24000L;
            long t = level.getDayTime() % 24000L;
            if (trying(village, p.getUUID()) && pregnantDay(village, p.getUUID()) < 0 && level.isNight()
                    && f.isSleeping() && p.distanceToSqr(f) < 8 * 8) {
                BlockPos bed = f.getSleepingPos().orElse(f.blockPosition());
                level.sendParticles(ParticleTypes.HEART, bed.getX() + 0.5, bed.getY() + 1.2, bed.getZ() + 0.5, 3, 0.5, 0.3, 0.5, 0.0);
                if (level.getGameTime() % 600 < 40) conceive(level, f, p, village, day);
            }
            long expecting = pregnantDay(village, p.getUUID());
            if (expecting >= 0) {
                if (day >= expecting + 1 && get(village, p.getUUID(), "crib").isEmpty() && !level.isNight()) crib(level, f, p, village);
                if (day >= expecting + CARRY_DAYS && t > 1000 && t < 11000) born(level, f, p, village, day);
            }
        }
    }

    /** It worked: the news in the morning, said by the spouse. */
    static void conceive(ServerLevel level, VillageFolkEntity f, Player p, UUID village, long day) {
        put(village, p.getUUID(), "trying", "");
        put(village, p.getUUID(), "expecting", Long.toString(day + (level.getDayTime() % 24000L > 12000L ? 1 : 0)));
        Villages.tell(village, day, f.displayNameCap() + " and " + p.getName().getString() + " are expecting a child");
        f.persona().remember(day, "we're having a baby", 10);
        f.sayLater(FolkTalk.pick(f.getRandom(), "I've news. Sit down. We're having a baby.", "We're expecting. Don't faint.",
            "A baby. Ours. In a few days.", "I think it worked. I know it worked.", "You'll want to start on a crib. I mean you, not me.",
            "We're going to be parents. Heaven help us."), 20);
    }

    /** The nursery: a crib (a little bed) by the home bed, put up by the spouse. */
    static void crib(ServerLevel level, VillageFolkEntity f, Player p, UUID village) {
        BlockPos home = null;
        try { home = BlockPos.of(Long.parseLong(get(village, p.getUUID(), "home"))); } catch (NumberFormatException ignored) { }
        if (home == null) home = f.bedPos();
        if (home == null) return;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos foot = home.relative(d, 2), head = foot.relative(d);
            if (!level.getBlockState(foot).isAir() || !level.getBlockState(head).isAir()) continue;
            if (!level.getBlockState(foot.below()).isFaceSturdy(level, foot.below(), Direction.UP)) continue;
            if (!level.getBlockState(head.below()).isFaceSturdy(level, head.below(), Direction.UP)) continue;
            if (f.blockPosition().distSqr(foot) > 4 * 4) {
                f.walkTo(foot, 0.9D);
                return;                                                // walking over to put it up: next look, it is done
            }
            BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, d);
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
            put(village, p.getUUID(), "crib", Long.toString(head.asLong()));
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. A crib. It wobbles; so will the baby.", "The crib's up. Don't put your boots on it.",
                "Nursery's ready. Small, but ready.", "I made a crib. Admire it.", "That's the crib. Now we wait.", "Crib. Done. Next: sleep, while we can."));
            return;
        }
        put(village, p.getUUID(), "crib", "none");
    }

    /** Born at home: the healer at the bedside, the bell, the news; named by the player next. */
    @Nullable
    static VillageFolkEntity born(ServerLevel level, VillageFolkEntity f, Player p, UUID village, long day) {
        put(village, p.getUUID(), "expecting", "");
        put(village, p.getUUID(), "crib", "");
        VillageFolkEntity child = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (child == null) return null;
        BlockPos at = Lineage.birthplace(level, f);                           // on the floor by the bed at home
        child.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, f.getYRot(), 0.0F);
        child.rename("Baby " + f.displayNameCap().split(" ")[0]);
        com.jrpetty.mcassistant.VillageSpawner.childKit(child);
        child.joinVillage(village, f.villageCentre());
        f.life().hadAChild();
        child.life().roll(level.getRandom(), f.life(), null);                // takes after its folk parent, the rest its own
        child.life().setParents(f.displayNameCap(), p.getName().getString());
        child.parentIds().add(f.getUUID());
        child.parentIds().add(p.getUUID());
        child.life().feel(f.getUUID(), f.displayNameCap(), 70);
        f.life().feel(child.getUUID(), child.displayNameCap(), 70);
        level.addFreshEntity(child);
        child.setChild(true);
        child.bornDaysAgo(0);
        Villages.recordBirth(village);
        Annals.born(village);
        Individual.born(child, f, f);
        Church.born(child);                                                   // [featlife] christened the Sunday after
        put(village, p.getUUID(), "naming", child.getUUID().toString());
        String kids = get(village, p.getUUID(), "children");
        put(village, p.getUUID(), "children", kids.isEmpty() ? child.getUUID().toString() : kids + "," + child.getUUID());
        f.persona().remember(day, "our child was born", 10);
        level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 1.2F);
        level.sendParticles(ParticleTypes.HEART, at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5, 8, 0.5, 0.4, 0.5, 0.0);
        // The healer at the bedside, walking over as it is told.
        VillageFolkEntity h = null;
        double near = Double.MAX_VALUE;
        for (VillageFolkEntity c : Healers.healers(village)) {
            if (c == f || !c.isAlive()) continue;
            double d = c.distanceToSqr(f);
            if (d < near) { near = d; h = c; }
        }
        if (h != null) {
            h.walkTo(at, 1.0D);
            h.sayLater(FolkTalk.pick(h.getRandom(), "A fine, loud one. Good lungs.", "Mother and baby both well. Father, sit down.",
                "There. All fingers, all toes. I counted twice.", "Healthy as you like.", "That's my work done. Yours begins.",
                "A strong little thing. Takes after somebody."), 60);
            Villages.tell(village, day, h.displayNameCap() + " the healer saw " + f.displayNameCap() + " and " + p.getName().getString() + "'s child safely into the world");
        }
        Villages.tell(village, day, f.displayNameCap() + " and " + p.getName().getString() + " had a child at home; the bell was rung");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Look at it. Look. It's ours.", "It's here. Come and see. Quietly.",
            "Hello, little one. I'm your other parent. The tidy one.", "We need a name. You choose; I'll veto.", "Small, isn't it. Loud, though.",
            "There. Now it needs a name."));
        if (p instanceof ServerPlayer sp) sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "Your child is born. Talk to " + f.displayNameCap() + " to name the baby."), true);
        return child;
    }

    /** The player names the baby (the talk's "Let's call the baby ..."). */
    static String name(VillageFolkEntity f, Player p, String name) {
        UUID village = f.ownerId();
        if (village == null || !isSpouse(f, p)) return "Whose baby?";
        UUID cid = id(village, p.getUUID(), "naming");
        if (cid == null) return "There's no baby to name. Yet.";
        String clean = name.replaceAll("[^\\p{L} '\\-]", "").trim();
        if (clean.isEmpty() || clean.length() > 20) return "That's not a name. Try again.";
        clean = Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
        if (!(((ServerLevel) f.level()).getEntity(cid) instanceof VillageFolkEntity child)) return "Where's the baby got to?";
        // A name is for life: said once, it is asked to be sure; the same name again settles it.
        if (!clean.equals(get(village, p.getUUID(), "confirmName"))) {
            put(village, p.getUUID(), "confirmName", clean);
            return clean + "? " + FolkTalk.pick(f.getRandom(), "Say it again and it's settled.", "Are you sure? Say it again.",
                "Hm. Say it once more if you mean it.", "Once more, so I know you mean it.", "It's a name for life. Say it again.",
                "Say it again, and that's its name.") + " (A name cannot be changed: choose 'Name the baby' with the same name to confirm.)";
        }
        put(village, p.getUUID(), "confirmName", "");
        child.rename(clean);
        put(village, p.getUUID(), "naming", "");
        long day = f.level().getDayTime() / 24000L;
        Villages.tell(village, day, f.displayNameCap() + " and " + p.getName().getString() + "'s baby is named " + clean);
        f.persona().remember(day, "we named our baby " + clean, 9);
        return FolkTalk.pick(f.getRandom(), clean + ". Yes. That suits.", clean + "? ...All right. " + clean + " it is.",
            "I like it. " + clean + ".", clean + ". My mother will say it's odd. Let her.", clean + ". Hear that? That's you.", "Fine. " + clean + ". Final answer.");
    }

    /** The child's stage of life, by its years: a baby, a child, a youngster, grown. */
    public static String stage(VillageFolkEntity f) {
        int y = f.ageYears();
        if (!f.isBaby()) return "grown";
        return y < 3 ? "a baby" : y < 13 ? "a child" : "a youngster";
    }

    /** This player's children in the town that are loaded and alive. */
    public static List<VillageFolkEntity> children(ServerLevel level, UUID village, UUID player) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (String s : get(village, player, "children").split(",")) {
            try {
                if (!s.isEmpty() && level.getEntity(UUID.fromString(s)) instanceof VillageFolkEntity c && c.isAlive()) out.add(c);
            } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ the talk screen and the player's page

    /** The talk screen's flags for this folk and player (FolkTalk.talkContext). */
    public static String contextFlags(VillageFolkEntity f, ServerPlayer p) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return "";
        StringBuilder sb = new StringBuilder();
        if (isSpouse(f, p)) {
            sb.append(" spouse");
            if (!get(village, p.getUUID(), "naming").isEmpty()) sb.append(" naming");
        } else if (isCourting(f, p)) {
            sb.append(" pcourting");
            if (proposable(f, p)) sb.append(" proposable");
        } else if (f.life().partner() == null && f.lines().courting() == null && f.persona().affinity(p.getUUID()) >= FOND - 10) {
            sb.append(" courtable");
            // The first time: a hint of what the new button is for.
            if (get(village, p.getUUID(), "hinted").isEmpty()) {
                put(village, p.getUUID(), "hinted", "1");
                p.displayClientMessage(net.minecraft.network.chat.Component.literal("New: " + f.displayNameCap()
                    + " likes you. You can court folk who are free: About, Home, 'Walk out with me?'."), true);
            }
        }
        return sb.toString();
    }

    /** The player's page (LifeTown): the family, the courting and the children with how they are coming on. */
    public static void lifeRows(ServerPlayer p, LifePage page, UUID village) {
        ServerLevel level = p.serverLevel();
        UUID sid = spouse(village, p.getUUID()), cid = courting(village, p.getUUID());
        String engaged = get(village, p.getUUID(), "engaged");
        List<VillageFolkEntity> kids = children(level, village, p.getUUID());
        if (sid == null && cid == null && engaged.isEmpty() && kids.isEmpty()) return;
        page.heading("Your family");
        if (sid != null) {
            String since = get(village, p.getUUID(), "wedDay");
            page.row(new ItemStack(Items.POPPY), "Married to " + get(village, p.getUUID(), "spouseName"),
                since.isEmpty() ? "" : "Wed on day " + (Long.parseLong(since) + 1), "");
            long exp = pregnantDay(village, p.getUUID());
            if (exp >= 0) page.text("Expecting: the baby is due on day " + (exp + CARRY_DAYS + 1)
                + (get(village, p.getUUID(), "crib").isEmpty() ? "" : "; the crib is up") + ".", 4);
            else if (trying(village, p.getUUID())) page.text("Trying for a baby.", 4);
        } else if (!engaged.isEmpty()) {
            page.text("Engaged: the wedding is the next evening, in " + (Villages.builtAt(village, "chapel") != null ? "the chapel" : "the square") + ".", 4);
        } else if (cid != null) {
            VillageFolkEntity f = level.getEntity(cid) instanceof VillageFolkEntity x ? x : null;
            page.row(new ItemStack(Items.DANDELION), "Walking out with " + get(village, p.getUUID(), "courtName"), progress(village, p.getUUID(), f), "");
        }
        for (VillageFolkEntity c : kids) {
            String school = School.cardLine(c);
            page.row(new ItemStack(Items.TOTEM_OF_UNDYING), c.displayNameCap() + ", " + c.ageYears() + ", " + stage(c),
                (school == null ? "" : school + ". ") + (c.isBaby() && c.lines().skill() > 0 ? "At " + c.lines().masterName() + "'s side: "
                    + c.lines().skill() + "% of the way to journeyman." : c.isBaby() ? "" : c.tradeTitle()), "");
            if (c.isBaby()) page.bar("Schooling and apprenticeship", Math.min(1.0, c.lines().skill() / 100.0), c.lines().skill() + "%");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void heart(VillageFolkEntity f) {
        if (f.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.1, f.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the night's trying for a baby succeeded. */
    public static void conceiveForTests(ServerLevel level, VillageFolkEntity f, Player p) {
        conceive(level, f, p, f.ownerId(), level.getDayTime() / 24000L);
        put(f.ownerId(), p.getUUID(), "expecting", Long.toString(level.getDayTime() / 24000L));
    }

    /** Tests: the birth, now. */
    @Nullable
    public static VillageFolkEntity bornForTests(ServerLevel level, VillageFolkEntity f, Player p) {
        return born(level, f, p, f.ownerId(), level.getDayTime() / 24000L);
    }

    public static void forgetForTests(UUID village, UUID player) {
        for (String k : new String[]{ "court", "courtName", "walks", "dates", "gifts", "walkDay", "dateDay", "engaged", "spouse", "spouseName",
                "wedDay", "home", "trying", "expecting", "crib", "naming", "children" }) put(village, player, k, "");
    }
}
