package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.EarnPayload;
import com.jrpetty.mcassistant.village.EarnMath;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.ServiceHours;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [earning] Paid shifts: a player takes a job in a town for a few hours (or as a standing job, every day at its hours),
 * does real tasks with the town's own mechanics (ShiftTasks: serving at the counter, waiting tables, cooking tickets,
 * harvesting and replanting, hauling, smelting, walking the night watch's round, laying a building's missing blocks),
 * and is paid for each task the moment it is done.
 * <ul>
 * <li><b>Getting a job.</b> Open shifts ({@link #offers}) show on the Earnings page, at the workplace's counter (its
 *     Work tab) and from the boss in talk: the role, its hours, the pay a task and a day, and what it needs. One click
 *     takes it, once or standing; {@link #quit} at any time.</li>
 * <li><b>Clear while working.</b> The shift HUD (client/ShiftHud) shows the role, the task in plain words, a marker on
 *     its target, the tasks done and pay this shift, the time left and the rating; the first time a role is taken its
 *     "how this job works" card.</li>
 * <li><b>Good or bad.</b> Each task is rated one to five stars against what matters for it (EarnMath.stars: speed
 *     against par, mistakes, quality), shown with its reason ("★★★★ served in 8s, par 12s"). A job skill per role
 *     (Novice to Master, with experience) adds a tenth to pay a level, raises tips, opens the senior post (head cook,
 *     foreman: a quarter more) and the folk think the better of a good hand. Poor shifts in a row bring a warning, then
 *     the loss of a standing job, with the reason.</li>
 * <li><b>Paid at once.</b> A task's pay comes out of the treasury (the town's wage budget for players, a day's worth a
 *     role: {@link #budget}) into the pack ("+3 coin from Harbour for serving Ada"), tips out of the customer's own
 *     purse. A town that cannot pay does not offer the job ("unpaid"). The end of the shift sums it up.</li>
 * </ul>
 * <b>Cost.</b> Nothing ticks without a shift on: once a second for each player on one (its task looked at, a new one
 * found when it has none), and the HUD sent only when it changed.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Shifts {

    private Shifts() {}

    /** A shift's length (ticks: four hours of the day), and how long a player may be away from the job before it ends. */
    static final long LENGTH = 4000L, AWAY = 600L;
    /** How far from the workplace counts as away (blocks over the town's reach for the roaming jobs). */
    static final int NEAR_POST = 48, ROAM = 32;
    /** A task not finished in so many times its par is given up (no pay, one star). */
    static final int GIVE_UP_PARS = 4;
    /** The tasks a day a town budgets its player wages for, a role; the wage level's least and most. */
    static final int BUDGET_TASKS = 40;
    static final double PAY_LEAST = 0.6, PAY_MOST = 1.6, STANDARD_WAGE = 5.0;
    /** Days a player let go from a standing job waits before taking that job standing again. */
    static final int LET_GO_DAYS = 3;
    /** The tag on a thing a shift has put in the player's hands (a plate on its way to a table). */
    static final String MARK = "mca_shift";

    // ------------------------------------------------------------------ a shift and its task

    /** One task: what, where, since when, against what par, and how it is going. */
    static final class Task {
        final String kind;
        String text;
        @Nullable BlockPos target;
        int entity = -1;
        final List<BlockPos> marks = new ArrayList<>();
        final long started;
        long par;
        int mistakes, leg;
        // What it is about (each role uses its own).
        int order = -1, line = -1, table = -1, count, deposit, laid;
        /** When a farm hand broke the plot (the replanting's clock starts). */
        long since;
        long placeAnchor, readyAt, burnAt;
        @Nullable UUID folk;
        @Nullable BlockPos to;
        @Nullable net.minecraft.world.item.Item item;
        String who = "";
        /** A builder's hand's cells of the drawing, in step with its marks, and the building's facing. */
        final List<com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement> plan = new ArrayList<>();
        @Nullable net.minecraft.core.Direction facing;

        Task(String kind, String text, @Nullable BlockPos target, long started, long par) {
            this.kind = kind;
            this.text = text;
            this.target = target;
            this.started = started;
            this.par = Math.max(20, par);
        }
    }

    /** A player's shift: where, what as, until when, and how it has gone. */
    static final class Shift {
        final UUID player;
        final UUID village;
        final ShiftRole role;
        final boolean standing, senior;
        @Nullable final ServiceRole served;
        final BlockPos post;
        final long started, ends;
        final int level0, xp0;
        @Nullable Task task;
        int tasks, starTotal, pay, tips, xp, failed;
        double carry;
        long awaySince = -1, idleSince, lastFind;
        String last = "";
        int lastStars;
        long lastAt;
        boolean howTo;
        int lastPrint;
        long lastSent;
        int round;

        Shift(UUID player, UUID village, ShiftRole role, boolean standing, boolean senior, @Nullable ServiceRole served, BlockPos post,
              long started, long ends, int xp0) {
            this.player = player;
            this.village = village;
            this.role = role;
            this.standing = standing;
            this.senior = senior;
            this.served = served;
            this.post = post;
            this.started = started;
            this.ends = ends;
            this.xp0 = xp0;
            this.level0 = EarnMath.level(xp0);
            this.idleSince = started;
        }

        String title() {
            return senior ? role.senior : role.title;
        }
    }

    static final Map<UUID, Shift> ON = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ON.clear();
        ShiftTasks.resetForTests();
    }

    @Nullable
    static Shift of(Player p) {
        return ON.get(p.getUUID());
    }

    /** Is this player on a shift now? */
    public static boolean onShift(Player p) {
        return ON.containsKey(p.getUUID());
    }

    /** Is this something a shift put in the player's hands (not the player's own to sell)? */
    public static boolean isShiftItem(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().contains(MARK);
    }

    // ------------------------------------------------------------------ the job skill and the standing job

    static CompoundTag skills(Player p) {
        CompoundTag root = Earnings.data(p);
        if (!root.contains("skills")) root.put("skills", new CompoundTag());
        return root.getCompound("skills");
    }

    /** The player's experience at a job. */
    public static int xp(Player p, ShiftRole r) {
        return skills(p).getInt(r.id);
    }

    public static int level(Player p, ShiftRole r) {
        return EarnMath.level(xp(p, r));
    }

    /** The player's standing job: {town, role id}, or null. */
    @Nullable
    static String[] standing(Player p) {
        CompoundTag root = Earnings.data(p);
        if (!root.contains("standing")) return null;
        CompoundTag s = root.getCompound("standing");
        return new String[]{ s.getString("town"), s.getString("role") };
    }

    private static void setStanding(Player p, @Nullable UUID town, @Nullable ShiftRole r) {
        CompoundTag root = Earnings.data(p);
        if (town == null || r == null) { root.remove("standing"); return; }
        CompoundTag s = new CompoundTag();
        s.putString("town", town.toString());
        s.putString("role", r.id);
        root.put("standing", s);
    }

    /** A short record of the player's shifts (the page and the journal): the last few, newest first. */
    static List<String> history(Player p) {
        ListTag l = Earnings.data(p).getList("shifts", 8);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private static void remember(Player p, String line) {
        CompoundTag root = Earnings.data(p);
        ListTag l = root.getList("shifts", 8);
        l.add(0, StringTag.valueOf(line));
        while (l.size() > 8) l.remove(l.size() - 1);
        root.put("shifts", l);
    }

    // ------------------------------------------------------------------ offers

    /** An open shift: what, where, the hours, the pay a task and a day, what it needs; why not when it cannot be. */
    public record Offer(ShiftRole role, String title, UUID village, BlockPos post, String hours, double perTask, int perDay,
                        String needs, String whyNot, boolean senior) {}

    /** The town's wage level for its players' shifts: its folk's average wage against the usual, within bounds. */
    static double payLevel(UUID village) {
        int n = 0, sum = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby()) continue;
            n++;
            sum += Market.wage(a);
            if (n >= 40) break;
        }
        double avg = n == 0 ? STANDARD_WAGE : sum / (double) n;
        return Math.max(PAY_LEAST, Math.min(PAY_MOST, avg / STANDARD_WAGE));
    }

    /** What the town budgets a day for players' work in a role (in coin). */
    static int budget(UUID village, ShiftRole r) {
        return (int) Math.round(r.wage * payLevel(village) * BUDGET_TASKS);
    }

    static String payer(ShiftRole r) {
        return "shift/" + r.id;
    }

    /** The shifts this town has open now for this player: the workplace found, the hours, the pay, or why not. */
    public static List<Offer> offers(ServerLevel level, Villages.Village v, @Nullable Player p) {
        List<Offer> out = new ArrayList<>();
        double payLevel = payLevel(v.id());
        for (ShiftRole r : ShiftRole.values()) {
            Offer o = offer(level, v, p, r, payLevel);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** One job's offer in this town (null if the town has nowhere to work it). */
    @Nullable
    static Offer offer(ServerLevel level, Villages.Village v, @Nullable Player p, ShiftRole r, double payLevel) {
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        ShiftTasks.Post post = ShiftTasks.post(level, v, r, p == null ? v.centre() : p.blockPosition());
        if (post == null) return null;
        ServiceHours hours = r.hours(post.served());
        int lv = p == null ? 0 : level(p, r);
        boolean senior = lv >= EarnMath.SENIOR_LEVEL;
        double perTask = EarnMath.taskPay(r.wage * payLevel, 4, lv, senior);
        int left = budget(v.id(), r) - Earnings.spentToday(v.id(), payer(r), day);
        String why = "";
        if (Ledger.coins(v.id()) < Math.max(3, Math.ceil(perTask * 3))) why = "Unpaid: the treasury can't pay wages just now.";
        else if (left < Math.ceil(perTask)) why = "The day's wages for this job are spent. Come back tomorrow.";
        else if (!hours.isOpen(time)) why = hours.nextOpening(time) + ".";
        else if (!post.ready()) why = post.why();
        return new Offer(r, senior ? r.senior : r.title, v.id(), post.pos(), hours.words(), perTask, (int) Math.round(perTask * 12), r.needs, why, senior);
    }

    // ------------------------------------------------------------------ taking and quitting

    /** Take a shift (once, or standing: every day at its hours). What it says. */
    public static String take(ServerPlayer p, @Nullable UUID town, String roleId, boolean standing) {
        if (!(p.level() instanceof ServerLevel level)) return "";
        ShiftRole r = ShiftRole.of(roleId);
        Villages.Village v = town == null ? PlayerServices.townAt(level, p.blockPosition()) : Villages.get(town);
        if (r == null || v == null) return "There's no such job here.";
        if (ON.containsKey(p.getUUID())) return "You're on a shift already: finish it or quit first.";
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(v.id(), p.getUUID(), day) || Standing.of(v.id(), p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) {
            return "Nobody in " + Villages.name(v.id()) + " will give you work.";
        }
        if (standing) {
            CompoundTag root = Earnings.data(p);
            if (root.contains("letgo/" + r.id) && day - root.getLong("letgo/" + r.id) < LET_GO_DAYS) return "You were let go from that job lately. Take it shift by shift for now.";
        }
        Offer o = offer(level, v, p, r, payLevel(v.id()));
        if (o == null) return Villages.name(v.id()) + " has no " + r.title.toLowerCase(Locale.ROOT) + " work: it needs " + r.needs + ".";
        if (standing) {
            setStanding(p, v.id(), r);
            Earnings.data(p).putLong("standingDay", -1);
        }
        if (!o.whyNot().isEmpty()) {
            return standing ? "Your standing job as " + o.title().toLowerCase(Locale.ROOT) + " in " + Villages.name(v.id()) + " is yours. "
                + o.whyNot() + " Your shift starts then." : "Not now: " + o.whyNot();
        }
        start(level, p, v, r, standing, o);
        return "";
    }

    private static void start(ServerLevel level, ServerPlayer p, Villages.Village v, ShiftRole r, boolean standing, Offer o) {
        ShiftTasks.Post post = ShiftTasks.post(level, v, r, p.blockPosition());
        if (post == null) return;
        long now = level.getGameTime();
        long time = level.getDayTime() % 24000L;
        ServiceHours hours = r.hours(post.served());
        long toClose = hours.open() == hours.close() ? LENGTH : Math.floorMod(hours.close() - time, 24000L);
        Shift s = new Shift(p.getUUID(), v.id(), r, standing, o.senior(), post.served(), post.pos(), now, now + Math.min(LENGTH, toClose),
            xp(p, r));
        CompoundTag root = Earnings.data(p);
        s.howTo = !root.getBoolean("seen/" + r.id);
        root.putBoolean("seen/" + r.id, true);
        if (standing) root.putLong("standingDay", level.getDayTime() / 24000L);
        ON.put(p.getUUID(), s);
        p.sendSystemMessage(Component.literal("Shift started: " + s.title() + " at " + Villages.name(v.id()) + " (" + r.place + "), till "
            + ServiceHours.clock((int) ((time + (s.ends - now)) % 24000L)) + ". About " + String.format(Locale.ROOT, "%.1f", o.perTask())
            + " coin a task.").withStyle(ChatFormatting.GREEN));
        if (s.howTo) for (String line : r.howTo) p.sendSystemMessage(Component.literal("  • " + line).withStyle(ChatFormatting.GRAY));
        level.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.6F, 1.2F);
        hud(p, s, true);
    }

    /** Quit the shift now (paid for what was done), and the standing job too if asked. What it says. */
    public static String quit(ServerPlayer p, boolean standing) {
        Shift s = ON.get(p.getUUID());
        String said = "";
        if (standing && standing(p) != null) {
            setStanding(p, null, null);
            said = "You've given up your standing job. ";
        }
        if (s != null && p.level() instanceof ServerLevel level) {
            end(level, p, s, "You clocked off.");
            return said;
        }
        return said.isEmpty() ? "You're not on a shift." : said.trim();
    }

    // ------------------------------------------------------------------ a task done

    /**
     * A task finished: rated (EarnMath.stars against its par, its mistakes and the quality lost), paid at once out of
     * the treasury in coin (whole coins; the hundredths carried to the next task), the skill's experience, the folk
     * served the happier (or not), a tip from its purse for fine work. {@code units}: how many it was worth (ingots
     * smelted, blocks laid).
     */
    static void done(ServerLevel level, ServerPlayer p, Shift s, Task t, int qualityLost, double units, String what,
                     @Nullable VillageFolkEntity customer) {
        long took = level.getGameTime() - t.started;
        int stars = EarnMath.stars(t.par, took, t.mistakes, qualityLost);
        int lv = EarnMath.level(xp(p, s.role));
        double owed = EarnMath.taskPay(s.role.wage * payLevel(s.village), stars, lv, s.senior) * Math.max(0, units) + s.carry;
        int coins = (int) Math.floor(owed + 1e-9);
        long day = level.getDayTime() / 24000L;
        int left = budget(s.village, s.role) - Earnings.spentToday(s.village, payer(s.role), day);
        coins = Math.max(0, Math.min(coins, left));
        int paid = coins <= 0 ? 0 : Earnings.fromTreasury(p, s.village, coins, Earnings.Source.SHIFT, Villages.name(s.village),
            "for " + what + " (" + EarnMath.starWord(stars) + ")");
        Earnings.spend(s.village, payer(s.role), day, paid);
        s.carry = Math.max(0, owed - paid);
        if (s.carry > 3) s.carry = 0;                                       // what the budget could not pay is not owed
        s.pay += paid;
        s.tasks++;
        s.starTotal += stars;
        int gain = EarnMath.xpFor(stars);
        skills(p).putInt(s.role.id, xp(p, s.role) + gain);
        s.xp += gain;
        int lv1 = EarnMath.level(xp(p, s.role));
        if (lv1 > lv) {
            p.sendSystemMessage(Component.literal("Your skill as " + s.role.title.toLowerCase(Locale.ROOT) + " is now " + EarnMath.LEVELS[lv1]
                + ": a tenth more pay" + (lv1 >= EarnMath.SENIOR_LEVEL ? ", and the post of " + s.role.senior.toLowerCase(Locale.ROOT) + " is open to you." : ".")).withStyle(ChatFormatting.AQUA));
        }
        s.last = EarnMath.starWord(stars) + " — " + reason(t, took, qualityLost);
        s.lastStars = stars;
        s.lastAt = level.getGameTime();
        if (customer != null) {
            if (stars >= 5) customer.persona().feelFor(p.getUUID(), p.getName().getString(), 1);
            if (stars <= 1) {
                customer.persona().feelFor(p.getUUID(), p.getName().getString(), -1);
                FolkTalk.speak(customer, FolkTalk.pick(customer.getRandom(), "That took an age.", "I'll be having a word with the boss.",
                    "Not the service I'm used to."));
            }
            int chance = EarnMath.tipChance(stars, lv1);
            if (chance > 0 && level.getRandom().nextInt(100) < chance) {
                int tip = stars >= 5 && level.getRandom().nextBoolean() ? 2 : 1;
                if (customer.spend(tip)) {
                    Earnings.giveCoins(p, tip);
                    Earnings.record(p, s.village, Earnings.Source.TIPS, tip, customer.displayNameCap(), "as a tip");
                    s.tips += tip;
                }
            }
        }
        level.playSound(null, p.blockPosition(), stars >= 4 ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.NOTE_BLOCK_BASS.value(),
            SoundSource.PLAYERS, 0.5F, stars >= 4 ? 1.4F : 0.8F);
        s.task = null;
        s.idleSince = level.getGameTime();
        hud(p, s, true);
    }

    /** A task given up or spoilt: one star counted, nothing paid, the reason said. */
    static void failed(ServerLevel level, ServerPlayer p, Shift s, String why) {
        s.tasks++;
        s.starTotal += 1;
        s.failed++;
        s.last = EarnMath.starWord(1) + " — " + why;
        s.lastStars = 1;
        s.lastAt = level.getGameTime();
        s.task = null;
        s.idleSince = level.getGameTime();
        p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.RED));
        hud(p, s, true);
    }

    /** "served in 8s, par 12s", and the mistakes and quality, in words. */
    private static String reason(Task t, long took, int qualityLost) {
        StringBuilder sb = new StringBuilder(t.kind).append(" in ").append(secs(took)).append(", par ").append(secs(t.par));
        if (t.mistakes > 0) sb.append("; ").append(t.mistakes).append(t.mistakes == 1 ? " mistake" : " mistakes");
        if (qualityLost > 0) sb.append("; ").append(qualityLost >= 2 ? "poor finish" : "could be better");
        return sb.toString();
    }

    static String secs(long ticks) {
        return Math.max(0, Math.round(ticks / 20.0)) + "s";
    }

    // ------------------------------------------------------------------ the end of a shift

    /** The shift over: summed up (tasks, stars, pay, tips, the skill gained), a standing job's record kept. */
    static void end(ServerLevel level, ServerPlayer p, Shift s, String why) {
        ON.remove(p.getUUID());
        if (s.task != null) ShiftTasks.release(level, p, s, s.task);
        s.task = null;
        double avg = s.tasks == 0 ? 0 : s.starTotal / (double) s.tasks;
        boolean poor = EarnMath.poorShift(s.tasks, s.starTotal);
        CompoundTag root = Earnings.data(p);
        String streakKey = "poor/" + s.role.id;
        int streak = poor ? root.getInt(streakKey) + 1 : 0;
        root.putInt(streakKey, streak);
        String verdict = "";
        String[] job = standing(p);
        boolean standingHere = job != null && job[0].equals(s.village.toString()) && job[1].equals(s.role.id);
        if (poor && standingHere) {
            if (streak >= EarnMath.LET_GO_AFTER) {
                setStanding(p, null, null);
                root.putLong("letgo/" + s.role.id, level.getDayTime() / 24000L);
                verdict = "You've been let go from your standing job: " + streak + " poor shifts in a row (under "
                    + EarnMath.POOR_TENTHS / 10.0 + " stars, or nothing done).";
            } else if (streak >= EarnMath.WARN_AFTER) {
                verdict = "A warning from the boss: " + streak + " poor shifts in a row. One more and the job goes to somebody else.";
            }
        }
        String summary = String.format(Locale.ROOT, "%s at %s: %d %s, %s stars, %d coin pay, %d in tips, +%d skill (%s).",
            s.title(), Villages.name(s.village), s.tasks, s.tasks == 1 ? "task" : "tasks", s.tasks == 0 ? "no" : String.format(Locale.ROOT, "%.1f", avg),
            s.pay, s.tips, s.xp, EarnMath.LEVELS[level(p, s.role)]);
        remember(p, "Day " + level.getDayTime() / 24000L + ": " + summary);
        p.sendSystemMessage(Component.literal("Shift over. " + why + " " + summary).withStyle(ChatFormatting.GREEN));
        if (!verdict.isEmpty()) p.sendSystemMessage(Component.literal(verdict).withStyle(ChatFormatting.RED));
        if (s.pay + s.tips > 0) Villages.tell(s.village, level.getDayTime() / 24000L, p.getName().getString() + " worked a shift as "
            + s.title().toLowerCase(Locale.ROOT) + ", paid " + s.pay + " coin");
        CompoundTag t = hudTag(level, p, s);
        t.putBoolean("active", false);
        CompoundTag sum = new CompoundTag();
        sum.putString("title", s.title() + " — shift over");
        sum.putString("why", why);
        sum.putInt("tasks", s.tasks);
        sum.putInt("starsX10", (int) Math.round(avg * 10));
        sum.putInt("pay", s.pay);
        sum.putInt("tips", s.tips);
        sum.putInt("xp", s.xp);
        sum.putString("level", EarnMath.LEVELS[level(p, s.role)]);
        sum.putFloat("progress", (float) EarnMath.levelProgress(xp(p, s.role)));
        sum.putString("verdict", verdict);
        t.put("summary", sum);
        LAST_HUD.put(p.getUUID(), t);
        Earnings.send(p, EarnPayload.HUD, t);
    }

    // ------------------------------------------------------------------ the second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 7) return;
        try {
            for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
                if (!(p.level() instanceof ServerLevel level)) continue;
                Shift s = ON.get(p.getUUID());
                if (s != null) second(level, p, s);
                else if (Earnings.data(p).contains("standing")) standingDue(level, p);
            }
            // A player gone from the server: its shift ends with it (its task's goods put back).
            for (UUID id : List.copyOf(ON.keySet())) {
                if (event.getServer().getPlayerList().getPlayer(id) == null) {
                    Shift s = ON.remove(id);
                    if (s != null && s.task != null) ShiftTasks.releaseOffline(event.getServer().overworld(), s, s.task);
                }
            }
        } catch (RuntimeException e) {
            if (LOGGED.add("tick")) com.mojang.logging.LogUtils.getLogger().warn("[earning] a shift's second failed", e);
        }
    }

    private static final java.util.Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** A second of a shift: over? away? the task given up? a new one found? the HUD brought up to date. */
    static void second(ServerLevel level, ServerPlayer p, Shift s) {
        long now = level.getGameTime();
        Villages.Village v = Villages.get(s.village);
        if (v == null || !p.isAlive()) { end(level, p, s, "The town is gone."); return; }
        if (now >= s.ends) { end(level, p, s, "The shift's hours are up."); return; }
        if (Raids.underAlarm(s.village) && s.role != ShiftRole.WATCH) { end(level, p, s, "The bell rang: everybody to safety."); return; }
        boolean roams = s.role == ShiftRole.HAUL || s.role == ShiftRole.SMELT || s.role == ShiftRole.FARM || s.role == ShiftRole.WATCH
            || s.role == ShiftRole.HEALER;                                   // [roles-a] the laid-up at home as well as the ward
        double reach = roams ? Villages.townReach(s.village) + ROAM : NEAR_POST;
        BlockPos from = roams ? v.centre() : s.post;
        boolean away = p.level() != level || Math.sqrt(p.blockPosition().distSqr(from)) > reach && !(s.role == ShiftRole.SMELT && s.task != null);
        if (away) {
            if (s.awaySince < 0) {
                s.awaySince = now;
                p.displayClientMessage(Component.literal("You've left your post: come back within 30 seconds or the shift ends.").withStyle(ChatFormatting.RED), true);
            } else if (now - s.awaySince > AWAY) {
                end(level, p, s, "You left your post.");
                return;
            }
        } else {
            s.awaySince = -1;
        }
        Task t = s.task;
        if (t != null) {
            if (now - t.started > GIVE_UP_PARS * t.par + 600) {
                ShiftTasks.release(level, p, s, t);
                failed(level, p, s, "Too slow: the " + t.kind + " task was given up, unpaid.");
            } else {
                ShiftTasks.watch(level, p, s, t);
            }
        } else if (now - s.lastFind >= 20) {
            s.lastFind = now;
            ShiftTasks.next(level, p, s, v);
        }
        hud(p, s, false);
    }

    /** A standing job's day: when its hours open and the player is in the town, its shift starts. */
    static void standingDue(ServerLevel level, ServerPlayer p) {
        if (level.getGameTime() % 100 >= 20) return;                         // looked at every five seconds
        String[] job = standing(p);
        if (job == null) return;
        long day = level.getDayTime() / 24000L;
        if (Earnings.data(p).getLong("standingDay") == day) return;
        Villages.Village v = Villages.get(Earnings.uuid(job[0]));
        ShiftRole r = ShiftRole.of(job[1]);
        if (v == null || r == null) { setStanding(p, null, null); return; }
        Villages.Village here = PlayerServices.townAt(level, p.blockPosition());
        if (here == null || !here.id().equals(v.id())) return;
        Offer o = offer(level, v, p, r, payLevel(v.id()));
        if (o != null && o.whyNot().isEmpty()) {
            p.sendSystemMessage(Component.literal("Your standing job: your shift as " + o.title().toLowerCase(Locale.ROOT) + " starts now.")
                .withStyle(ChatFormatting.GREEN));
            start(level, p, v, r, true, o);
            return;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.level() instanceof ServerLevel level) {
            Shift s = ON.get(p.getUUID());
            if (s != null) end(level, p, s, "You left the game.");
        }
    }

    /** A plate or a load still in hand from a shift a restart cut short: back where it belongs, not kept. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        Player p = e.getEntity();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            CustomData d = st.get(DataComponents.CUSTOM_DATA);
            if (d != null && d.copyTag().contains(MARK) && !HaulContracts.MARK.equals(d.copyTag().getString(MARK))) p.getInventory().setItem(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ the HUD

    private static final Map<UUID, CompoundTag> LAST_HUD = new ConcurrentHashMap<>();

    /** The HUD's state as the server has it: sent when it changed (or every few seconds to keep the clock true). */
    static void hud(ServerPlayer p, Shift s, boolean force) {
        if (!(p.level() instanceof ServerLevel level)) return;
        CompoundTag t = hudTag(level, p, s);
        CompoundTag cmp = t.copy();
        cmp.remove("now");
        int print = cmp.hashCode();
        long now = level.getGameTime();
        LAST_HUD.put(p.getUUID(), t);
        if (!force && print == s.lastPrint && now - s.lastSent < 100) return;
        s.lastPrint = print;
        s.lastSent = now;
        Earnings.send(p, EarnPayload.HUD, t);
    }

    static CompoundTag hudTag(ServerLevel level, ServerPlayer p, Shift s) {
        CompoundTag t = new CompoundTag();
        t.putBoolean("active", true);
        t.putString("role", s.role.id);
        t.putString("title", s.title());
        t.putString("town", Villages.name(s.village));
        t.putLong("now", level.getGameTime());
        t.putLong("ends", s.ends);
        t.putInt("tasks", s.tasks);
        t.putInt("pay", s.pay);
        t.putInt("tips", s.tips);
        t.putInt("starsX10", s.tasks == 0 ? 0 : Math.round(s.starTotal * 10f / s.tasks));
        int xp = xp(p, s.role);
        t.putString("level", EarnMath.LEVELS[EarnMath.level(xp)]);
        t.putFloat("progress", (float) EarnMath.levelProgress(xp));
        Task task = s.task;
        if (task != null) {
            t.putString("task", task.text);
            t.putLong("taskStarted", task.started);
            t.putLong("par", task.par);
            if (task.target != null) t.putIntArray("target", new int[]{ task.target.getX(), task.target.getY(), task.target.getZ() });
            if (task.entity >= 0) t.putInt("entity", task.entity);
            if (!task.marks.isEmpty()) {
                int[] m = new int[task.marks.size() * 3];
                for (int i = 0; i < task.marks.size(); i++) {
                    m[3 * i] = task.marks.get(i).getX();
                    m[3 * i + 1] = task.marks.get(i).getY();
                    m[3 * i + 2] = task.marks.get(i).getZ();
                }
                t.putIntArray("marks", m);
            }
            if (task.readyAt > 0) {
                t.putLong("readyAt", task.readyAt);
                t.putLong("burnAt", task.burnAt);
            }
            t.putInt("mistakes", task.mistakes);
        } else {
            t.putString("task", ShiftTasks.idleWords(s));
        }
        if (!s.last.isEmpty()) {
            t.putString("last", s.last);
            t.putInt("lastStars", s.lastStars);
            t.putLong("lastAt", s.lastAt);
        }
        if (s.howTo && level.getGameTime() - s.started < 600) {
            ListTag how = new ListTag();
            for (String line : s.role.howTo) how.add(StringTag.valueOf(line));
            t.put("howTo", how);
        }
        return t;
    }

    // ------------------------------------------------------------------ talk

    private static final Map<String, Long> ASKED = new ConcurrentHashMap<>();

    /** "Any shifts going?": the open jobs in the folk's town in a line or two; asked again of the boss of one, it is taken. */
    public static String talk(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level) || !(p instanceof ServerPlayer sp)) return "I couldn't say.";
        Villages.Village v = Villages.get(id);
        if (v == null) return "I couldn't say.";
        List<Offer> open = new ArrayList<>();
        Offer mine = null;
        for (Offer o : offers(level, v, p)) {
            if (!o.whyNot().isEmpty()) continue;
            open.add(o);
            if (o.role().bosses.contains(f.stationTask())) mine = o;
        }
        if (onShift(p)) return "You're on a shift already. Get on with it!";
        String key = f.getUUID() + "/" + p.getUUID();
        Long asked = ASKED.get(key);
        long now = level.getGameTime();
        if (mine != null && asked != null && now - asked < 1200) {
            ASKED.remove(key);
            String said = take(sp, id, mine.role().id, false);
            return said.isEmpty() ? "Right, you're on. " + mine.role().howTo.get(0) : said;
        }
        if (open.isEmpty()) return "No shifts going just now. Try the board in the morning.";
        ASKED.put(key, now);
        if (mine != null) {
            return String.format(Locale.ROOT, "I could use a %s: %s, about %.1f coin a task. Ask me again and it's yours.",
                mine.title().toLowerCase(Locale.ROOT), mine.hours(), mine.perTask());
        }
        StringBuilder sb = new StringBuilder("There's work going: ");
        for (int i = 0; i < Math.min(3, open.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(open.get(i).title().toLowerCase(Locale.ROOT));
        }
        return sb.append(". Your Earnings page has the details.").toString();
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the HUD as last sent to this player. */
    @Nullable
    public static CompoundTag hudForTests(Player p) {
        return LAST_HUD.get(p.getUUID());
    }

    /** The shift's tasks, pay, stars, failures and pay carried (hundredths) so far, or null off shift. */
    @Nullable
    static int[] state(Player p) {
        Shift s = ON.get(p.getUUID());
        return s == null ? null : new int[]{ s.tasks, s.pay, s.starTotal, s.failed, (int) Math.round(s.carry * 100) };
    }

    /** Tests: {tasks, pay, starTotal, failed, carryX100}, or null. */
    @Nullable
    public static int[] stateForTests(Player p) {
        return state(p);
    }

    /** Tests: a right-click at work, as the block's use would be. True if it was the task's. */
    public static boolean useForTests(ServerLevel level, ServerPlayer p, BlockPos at) {
        Shift s = ON.get(p.getUUID());
        return s != null && s.task != null && ShiftTasks.use(level, p, s, s.task, at);
    }

    /** Tests: the counter screen's Serve. */
    public static String serveForTests(ServerLevel level, ServerPlayer p, UUID folk) {
        return ShiftTasks.serve(level, p, folk);
    }

    /** Tests: the last task's line ("★★★★★ — served in 1s, par 12s"). */
    public static String lastForTests(Player p) {
        Shift s = ON.get(p.getUUID());
        return s == null ? "" : s.last;
    }

    /** Tests: run the shift's second now. */
    public static void secondForTests(ServerLevel level, ServerPlayer p) {
        Shift s = ON.get(p.getUUID());
        if (s != null) {
            s.lastFind = -100;
            second(level, p, s);
        }
    }

    /** Tests: end the shift now. */
    public static void endForTests(ServerLevel level, ServerPlayer p) {
        Shift s = ON.get(p.getUUID());
        if (s != null) end(level, p, s, "Test over.");
    }

    /** Tests: the player's standing job's role id, or null. */
    @Nullable
    public static String standingForTests(Player p) {
        String[] s = standing(p);
        return s == null ? null : s[1];
    }

    /** Tests: set the player's experience at a job. */
    public static void xpForTests(Player p, ShiftRole r, int xp) {
        skills(p).putInt(r.id, xp);
    }

    /** Tests: the current task's text, or null. */
    @Nullable
    public static String taskForTests(Player p) {
        Shift s = ON.get(p.getUUID());
        return s == null || s.task == null ? null : s.task.text;
    }

    /** Tests: the current task's started tick moved back (as if the player took so long). */
    public static void ageTaskForTests(Player p, long ticks) {
        Shift s = ON.get(p.getUUID());
        if (s != null && s.task != null) {
            Task t = s.task;
            Task aged = new Task(t.kind, t.text, t.target, t.started - ticks, t.par);
            copy(t, aged);
            s.task = aged;
        }
    }

    private static void copy(Task a, Task b) {
        b.entity = a.entity;
        b.marks.addAll(a.marks);
        b.mistakes = a.mistakes;
        b.leg = a.leg;
        b.laid = a.laid;
        b.since = a.since;
        b.order = a.order;
        b.line = a.line;
        b.table = a.table;
        b.count = a.count;
        b.deposit = a.deposit;
        b.placeAnchor = a.placeAnchor;
        b.readyAt = a.readyAt;
        b.burnAt = a.burnAt;
        b.folk = a.folk;
        b.to = a.to;
        b.item = a.item;
        b.who = a.who;
        b.plan.addAll(a.plan);
        b.facing = a.facing;
    }
}
