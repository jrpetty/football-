package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.EarnPayload;
import com.jrpetty.mcassistant.village.EarnMath;
import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpb] The job adverts board, and everything about a player's job on one page ("jobs": the U page's Jobs board, the
 * notice board block, any folk's "Any jobs for me?", and the Town Hall's link: {@link #openFromHall}).
 * <ul>
 * <li><b>Adverts.</b> Every opening the town's employers have posted (the labour market's, with their folk applicants),
 *     the posts players are hiring for (PlayerHiring), the town's civic posts open to citizens, and the day's shifts. Each
 *     shows the employer and its respect for you, the role, both ways of pay in plain words with figures, the hours, the
 *     target, the requirements, the applicants and the closing day, with Details, Show me and Apply. Filtered by trade,
 *     pay and employer; sorted by pay, distance or fit. A gentle toast in the morning for new adverts that fit you
 *     (switch it off here).</li>
 * <li><b>Details.</b> The job card in full: "How you're paid" for each way, "Where" with a small map and Show me, the
 *     hours and targets and how stars are judged, "What the job involves" step by step, what earns a warning and what a
 *     dismissal, the requirements ticked against you, and the applicants.</li>
 * <li><b>Applications.</b> Applied → shortlisted → an interview at a time (two or more applicants: PlayerInterview's
 *     questions on the screen) → offered (accept on either pay, or decline) or turned down with the reason. An advert
 *     filled says by whom.</li>
 * <li><b>My job.</b> The card, today's output against the aim, the boss's words, warnings, the way to promotion; start
 *     the shift, hand in, book leave, ask to switch pay, give notice. <b>My week.</b> Seven days, worked and to come.
 *     <b>Hand in.</b> What counts and what doesn't, and why, before it is taken.</li>
 * </ul>
 */
public class JobAdverts implements PlayerLife.Page {

    /** An advert: where it comes from ("v" an opening, "p" a player's post, "c" a civic post), its number, its card, its
     *  employer, the wage, the hours, applicants (folk on salary / on commission), its closing day, where the work is, what
     *  it asks, and which pay it offers (0 both, 1 salary only, 2 commission only). */
    public record Advert(String src, int id, JobCards.Card card, String employer, String employerName, int wage, String hours,
                         int applicants, int folkComm, long closes, @Nullable BlockPos where, String whereWords, boolean citizensOnly,
                         int minLevel, int payOptions, @Nullable VillageFolkEntity boss, UUID town) {

        String key() {
            return src + ":" + id;
        }
    }

    // ------------------------------------------------------------------ the adverts

    /** Every advert in the town for this player. */
    static List<Advert> adverts(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<Advert> out = new ArrayList<>();
        UUID id = v.id();
        LabourBook.Town tw = LabourBook.peek(id);
        if (tw != null) {
            for (LabourBook.Vacancy vac : tw.vacancies) {
                if (!vac.open() || vac.openings <= 0) continue;
                JobCards.Card c = JobCards.of(vac.trade);
                if (c == null) continue;
                int waiting = 0, comm = 0;
                for (LabourBook.Application a : vac.apps) {
                    if (a.verdict != LabourBook.Verdict.WAITING && a.verdict != LabourBook.Verdict.SHORTLISTED) continue;
                    waiting++;
                    VillageFolkEntity f = Homes.loaded(id, a.folk);
                    if (f != null && PayTerms.folkChoice(f, a.level)) comm++;
                }
                BlockPos where = whereOf(level, v, c, p);
                VillageFolkEntity boss = bossOf(id, vac.employer);
                out.add(new Advert("v", vac.id, c, vac.employer, PlayerJobs.employerName(vac), Math.max(1, vac.wage), vac.hours, waiting, comm,
                    vac.posted + 3, where, whereWords(c, PlayerJobs.employerName(vac)), false, vac.minLevel, 0, boss, id));
            }
        }
        out.addAll(PlayerHiring.adverts(level, v, p));
        out.addAll(civic(level, v, p));
        return out;
    }

    /** The town's civic posts a citizen may hold (the labour market's own openings aside). */
    static List<Advert> civic(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<Advert> out = new ArrayList<>();
        String[][] posts = { { "TEACHER", "teacher", "The school" }, { "BANK", "banker", "The bank" }, { "FERRY", "ferryman", "The ferry" },
            { "CLERK", "planner", "The town hall" }, { "CARTOGRAPHER", "cartographer", "The map room" }, { "HEAL", "healer", "The infirmary" } };
        int n = 0;
        for (String[] s : posts) {
            n++;
            try {
                InterviewPosts.Post post = InterviewPosts.Post.of(s[1]);
                if (post == null || !InterviewPosts.stands(level, v, post)) continue;
            } catch (RuntimeException e) {
                continue;
            }
            JobCards.Card c = JobCards.of(s[0]);
            if (c == null) continue;
            int wage = PayTerms.goingWage(level, v.id(), c, 0);
            out.add(new Advert("c", n, c, "town:" + s[0], s[2], wage, "the town's hours", 0, 0, level.getDayTime() / 24000L + 2,
                whereOf(level, v, c, p), whereWords(c, s[2]), true, 0, 1, null, v.id()));
        }
        return out;
    }

    @Nullable
    static VillageFolkEntity bossOf(UUID town, String employer) {
        if (!employer.startsWith("firm:")) return null;
        Business b = Businesses.of(town, employer.substring(5));
        return b == null ? null : Temperament.owner(town, b);
    }

    @Nullable
    static BlockPos whereOf(ServerLevel level, Villages.Village v, JobCards.Card c, Player p) {
        if (c.shift() == ShiftRole.MINE) {
            WorkShifts.Face f = WorkShifts.face(level, v);
            return f == null ? null : f.head() != null ? f.head() : f.at();
        }
        ShiftTasks.Post post = ShiftTasks.post(level, v, c.shift(), p.blockPosition());
        return post == null ? null : post.pos();
    }

    static String whereWords(JobCards.Card c, String employer) {
        return employer + ", " + c.shift().place;
    }

    /** The two ways of pay for an advert, worked out for this player. */
    static PayTerms.Terms[] terms(ServerLevel level, Advert a, Player p) {
        int lv = Shifts.level(p, a.card().shift());
        int target = WorkMath.scaled(a.card().target(), lv, 0, 0, false);
        PayTerms.Terms sal = PayTerms.salary(a.card(), a.wage(), target);
        PayTerms.Terms com = PayTerms.commission(level, a.town(), a.card(), a.wage(), target, a.boss(), a.employerName());
        return new PayTerms.Terms[]{ sal, com };
    }

    /** "Warm", "Wary": how the employer regards the player. */
    static String respect(ServerLevel level, Advert a, Player p) {
        if (a.boss() != null) {
            int aff = a.boss().persona().affinity(p.getUUID());
            String word = aff >= 30 ? "thinks well of you" : aff >= 10 ? "is warm to you" : aff > -10 ? "doesn't know you yet" : aff > -30 ? "is wary of you" : "dislikes you";
            return a.boss().displayNameCap() + " " + word + " (" + (aff > 0 ? "+" : "") + aff + ")";
        }
        return "The town's respect: " + TownOpinion.ratingOf(a.town(), p.getUUID(), level.getGameTime());
    }

    /** What the opening asks, each with whether the player has it. */
    static List<String> requirements(ServerLevel level, Advert a, Player p) {
        List<String> out = new ArrayList<>();
        Standing.View view = Standing.of(a.town(), p.getUUID(), level.getGameTime());
        boolean known = view.title().atLeast(Standing.Title.VISITOR);
        out.add((known ? "✔ " : "✘ ") + "Known in town (a visitor or better): you are " + view.title().words);
        if (a.citizensOnly()) {
            boolean cit = Citizens.is(a.town(), p.getUUID());
            out.add((cit ? "✔ " : "✘ ") + "A citizen of " + Villages.name(a.town()) + (cit ? "" : " (ask at the hall or any folk: \"May I live here?\")"));
        }
        int lv = Shifts.level(p, a.card().shift());
        if (a.minLevel() > 0) out.add((lv >= Math.min(4, a.minLevel() / 5) ? "✔ " : "✘ ") + "Some skill at the trade (yours: " + EarnMath.LEVELS[lv] + ")");
        else out.add("✔ No experience needed: the trade is taught on the job (yours: " + EarnMath.LEVELS[lv] + ")");
        if (Laws.banished(a.town(), p.getUUID(), level.getDayTime() / 24000L)) out.add("✘ Not banished from the town");
        return out;
    }

    /** Why the player can't apply ("" if they can). */
    static String cannotApply(ServerLevel level, Advert a, ServerPlayer p) {
        if (PlayerJobs.employed(p)) return "You have a job: give notice first (My job)";
        if (applied(p, a) != null) return "You've applied: see Applications";
        Standing.View view = Standing.of(a.town(), p.getUUID(), level.getGameTime());
        if (!view.title().atLeast(Standing.Title.VISITOR)) return "The town doesn't know you yet: talk with the folk and trade with them first";
        if (Laws.banished(a.town(), p.getUUID(), level.getDayTime() / 24000L)) return "You're banished from this town";
        if (a.citizensOnly() && !Citizens.is(a.town(), p.getUUID())) return "Citizens only: become a citizen first";
        if (a.employer().startsWith("player:") && a.employer().contains(p.getUUID().toString())) return "That's your own advert";
        return "";
    }

    // ------------------------------------------------------------------ applications (kept with the player)

    static ListTag apps(Player p) {
        CompoundTag d = Earnings.data(p);
        if (!d.contains("wpbApps")) d.put("wpbApps", new ListTag());
        return d.getList("wpbApps", Tag.TAG_COMPOUND);
    }

    @Nullable
    static CompoundTag applied(Player p, Advert a) {
        ListTag l = apps(p);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            if (t.getString("key").equals(a.key()) && t.getUUID("town").equals(a.town()) && live(t.getString("st"))) return t;
        }
        return null;
    }

    static boolean live(String st) {
        return st.equals("APPLIED") || st.equals("SHORTLISTED") || st.equals("INTERVIEW") || st.equals("OFFERED");
    }

    static String statusWords(CompoundTag t, ServerLevel level) {
        return switch (t.getString("st")) {
            case "APPLIED" -> "Applied on day " + t.getLong("day") + ". Waiting to hear.";
            case "SHORTLISTED" -> "Shortlisted.";
            case "INTERVIEW" -> "Interview at " + com.jrpetty.mcassistant.village.ServiceHours.clock((int) Math.floorMod(t.getLong("at") - (level.getGameTime() - level.getDayTime() % 24000L), 24000L))
                + (level.getGameTime() >= t.getLong("at") - 200 ? ": now. Press Attend." : ".");
            case "OFFERED" -> "Offered! Accept or decline by day " + (t.getLong("offerDay") + 1) + ".";
            case "TURNED_DOWN" -> "Turned down: " + t.getString("why");
            case "ACCEPTED" -> "Accepted: you work here.";
            case "DECLINED" -> "You declined.";
            default -> "Withdrawn" + (t.getString("why").isEmpty() ? "." : ": " + t.getString("why"));
        };
    }

    /** Apply: the application written, applied → shortlisted → interview or offer on the clock ({@link #tick}). */
    static String apply(ServerLevel level, ServerPlayer p, Advert a, boolean commission) {
        String no = cannotApply(level, a, p);
        if (!no.isEmpty()) return "!" + no + ".";
        if (a.payOptions() == 1 && commission) return "!This post is offered on a salary only.";
        if (a.payOptions() == 2 && !commission) return "!This post is offered on commission only.";
        CompoundTag t = new CompoundTag();
        t.putString("key", a.key());
        t.putString("src", a.src());
        t.putInt("id", a.id());
        t.putUUID("town", a.town());
        t.putString("card", a.card().key());
        t.putString("employer", a.employer());
        t.putString("name", a.employerName());
        t.putInt("wage", a.wage());
        t.putString("hours", a.hours());
        t.putBoolean("comm", commission);
        t.putInt("payOptions", a.payOptions());
        t.putString("st", "APPLIED");
        t.putLong("day", level.getDayTime() / 24000L);
        t.putLong("since", level.getGameTime());
        apps(p).add(0, t);
        while (apps(p).size() > 12) apps(p).remove(apps(p).size() - 1);
        if (a.src().equals("p")) PlayerHiring.applied(level, p, a, commission);
        Planner.add(p, level.getDayTime() / 24000L, (int) ((level.getDayTime() % 24000L) / 1000L + 6) % 24, "application",
            "Applied: " + a.card().title() + " at " + a.employerName(), a.where());
        return "Applied for " + a.card().title().toLowerCase(Locale.ROOT) + " at " + a.employerName() + (commission ? " on commission" : " on a salary")
            + ". What happens next: " + (a.src().equals("p") ? "the employer reads it and answers on their screen." : "you'll hear within the hour whether you're shortlisted; with other applicants, an interview.")
            + " Follow it under Applications.";
    }

    /** The applications on the clock, for every player online: shortlisting, interviews set and missed, offers lapsing. */
    static void tick(ServerLevel level) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            ListTag l = apps(p);
            long now = level.getGameTime(), day = level.getDayTime() / 24000L;
            for (int i = 0; i < l.size(); i++) {
                CompoundTag t = l.getCompound(i);
                String st = t.getString("st");
                if (!live(st)) continue;
                if (t.getString("src").equals("p")) { PlayerHiring.tickApp(level, p, t); continue; }
                LabourBook.Vacancy vac = vacancy(t);
                if (t.getString("src").equals("v") && vac == null && !st.equals("OFFERED")) {
                    turnDown(p, t, filledBy(t));
                    continue;
                }
                switch (st) {
                    case "APPLIED" -> {
                        if (now - t.getLong("since") < 600) continue;
                        Standing.View view = Standing.of(t.getUUID("town"), p.getUUID(), now);
                        if (!view.title().atLeast(Standing.Title.VISITOR)) { turnDown(p, t, "the employer wants somebody the town knows better"); continue; }
                        t.putString("st", "SHORTLISTED");
                        t.putLong("since", now);
                        WorkLife.toast(p, "Shortlisted: " + JobCards.of(t.getString("card")).title() + " at " + t.getString("name") + ".");
                    }
                    case "SHORTLISTED" -> {
                        if (now - t.getLong("since") < 200) continue;
                        int rivals = vac == null ? 0 : waiting(vac);
                        if (rivals >= 1) {
                            long at = now + 1000 - Math.floorMod(level.getDayTime() + 1000, 1000L);   // the next hour
                            t.putString("st", "INTERVIEW");
                            t.putLong("at", at);
                            t.putInt("rivals", rivals);
                            WorkLife.toast(p, "Interview at " + t.getString("name") + " at " + com.jrpetty.mcassistant.village.ServiceHours.clock((int) ((level.getDayTime() + (at - now)) % 24000L))
                                + ". Open the jobs board (U) to attend.");
                            Planner.add(p, day, (int) (((level.getDayTime() + (at - now)) % 24000L) / 1000L + 6) % 24, "interview", "Interview: " + t.getString("name"), null);
                        } else offer(level, p, t, "you were the only applicant");
                    }
                    case "INTERVIEW" -> {
                        if (now > t.getLong("at") + 2400) turnDown(p, t, "you missed the interview");
                    }
                    case "OFFERED" -> {
                        if (day > t.getLong("offerDay") + 1) { t.putString("st", "WITHDRAWN"); t.putString("why", "the offer lapsed"); }
                    }
                    default -> { }
                }
            }
        }
    }

    static int waiting(LabourBook.Vacancy vac) {
        int n = 0;
        for (LabourBook.Application a : vac.apps) if (a.verdict == LabourBook.Verdict.WAITING || a.verdict == LabourBook.Verdict.SHORTLISTED) n++;
        return n;
    }

    @Nullable
    static LabourBook.Vacancy vacancy(CompoundTag t) {
        if (!t.getString("src").equals("v")) return null;
        LabourBook.Town tw = LabourBook.peek(t.getUUID("town"));
        if (tw == null) return null;
        for (LabourBook.Vacancy v : tw.vacancies) if (v.id == t.getInt("id") && v.open() && v.openings > 0) return v;
        return null;
    }

    static String filledBy(CompoundTag t) {
        LabourBook.Town tw = LabourBook.peek(t.getUUID("town"));
        if (tw != null) for (LabourBook.Vacancy v : tw.vacancies) {
            if (v.id != t.getInt("id")) continue;
            if (v.state == LabourBook.State.FILLED) return "the post went to " + (v.by.isEmpty() ? "another applicant" : v.by.replaceFirst("^taken: ", ""));
            return "the advert came down" + (v.note.isEmpty() ? "" : ": " + v.note);
        }
        return "the advert came down";
    }

    static void turnDown(ServerPlayer p, CompoundTag t, String why) {
        t.putString("st", "TURNED_DOWN");
        t.putString("why", why);
        WorkLife.toast(p, "Not this time: " + t.getString("name") + " (" + why + ").");
    }

    static void offer(ServerLevel level, ServerPlayer p, CompoundTag t, String why) {
        t.putString("st", "OFFERED");
        t.putString("why", why);
        t.putLong("offerDay", level.getDayTime() / 24000L);
        WorkLife.toast(p, "Job offer: " + JobCards.of(t.getString("card")).title() + " at " + t.getString("name") + ". Open the jobs board (U) to accept.");
    }

    /** The interview answered: the employer weighs it and the player against the best folk applicant. */
    static String interviewed(ServerLevel level, ServerPlayer p, CompoundTag t, int marks) {
        LabourBook.Vacancy vac = vacancy(t);
        if (vac == null) { turnDown(p, t, filledBy(t)); return "!" + t.getString("why") + "."; }
        String no = PlayerJobs.judge(level, p, t.getUUID("town"), vac, PlayerInterview.weight(marks));
        t.putInt("marks", marks);
        if (no != null) {
            turnDown(p, t, PlayerInterview.verdict(marks) + "; " + no);
            return "!Turned down after " + PlayerInterview.verdict(marks) + ": " + no + ".";
        }
        offer(level, p, t, PlayerInterview.verdict(marks));
        return "Offered the job after " + PlayerInterview.verdict(marks) + ". Read the terms and accept below.";
    }

    /** Accept an offer: taken on, on the pay chosen. */
    static String accept(ServerLevel level, ServerPlayer p, CompoundTag t, boolean commission) {
        if (!t.getString("st").equals("OFFERED")) return "!There's no offer standing.";
        if (PlayerJobs.employed(p)) return "!You have a job already: give notice first.";
        int opts = t.getInt("payOptions");
        if (opts == 1) commission = false;
        if (opts == 2) commission = true;
        JobCards.Card c = JobCards.of(t.getString("card"));
        if (c == null) return "!No such job.";
        String src = t.getString("src");
        if (src.equals("v")) {
            LabourBook.Vacancy vac = vacancy(t);
            if (vac == null) { turnDown(p, t, filledBy(t)); return "!Too late: " + t.getString("why") + "."; }
            PlayerJobs.fill(level, p, vac);
        } else if (src.equals("p")) {
            String no = PlayerHiring.accepted(level, p, t);
            if (!no.isEmpty()) return "!" + no;
        }
        WorkLife.hire(level, p, t.getUUID("town"), t.getString("employer"), t.getString("name"), c, t.getInt("wage"), t.getString("hours"), commission, opts != 0);
        t.putString("st", "ACCEPTED");
        Villages.tell(t.getUUID("town"), level.getDayTime() / 24000L, p.getGameProfile().getName() + " was taken on as " + c.title().toLowerCase(Locale.ROOT) + " at " + t.getString("name"));
        CompoundTag j = PlayerJobs.job(p);
        return "You're " + c.title().toLowerCase(Locale.ROOT) + " at " + t.getString("name") + (commission ? ", on commission at " + j.getString("rateWords") : ", on " + t.getInt("wage") + "c a day")
            + ". Your shift starts at its hours: the arrow at the top of the screen shows the way. See My job for your card.";
    }

    // ------------------------------------------------------------------ the morning's toast

    static void morningToast(ServerLevel level, ServerPlayer p) {
        if (Earnings.data(p).getBoolean("wpb/toastsOff") || PlayerJobs.employed(p)) return;
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        int fits = 0;
        String first = "";
        LabourBook.Town tw = LabourBook.peek(v.id());
        if (tw == null) return;
        for (LabourBook.Vacancy vac : tw.vacancies) {
            if (!vac.open() || vac.posted < day - 1 || JobCards.of(vac.trade) == null) continue;
            fits++;
            if (first.isEmpty()) first = JobCards.of(vac.trade).title() + " at " + PlayerJobs.employerName(vac) + ", " + vac.wage + "c a day";
        }
        if (fits > 0) WorkLife.toast(p, "New on the jobs board: " + first + (fits > 1 ? " (and " + (fits - 1) + " more)" : "") + ". Press U.");
    }

    /** From the Town Hall's Jobs link (WP-A's hall): this page, on its adverts. */
    public static void openFromHall(ServerPlayer p) {
        PlayerLife.open(p, "jobs", LifePage.args("tab", "adverts", "from", "hall"));
    }

    // ------------------------------------------------------------------ the page

    static final String[][] GROUPS = { { "any", "Any trade" }, { "land", "Fields, woods and flock" }, { "mine", "Mine and quarry" },
        { "water", "The water" }, { "work", "Workshops and smelting" }, { "serve", "Counter, kitchen, dining" },
        { "haul", "Hauling and building" }, { "watch", "The watch" }, { "care", "Care" } };

    static boolean inGroup(JobCards.Card c, String g) {
        return switch (g) {
            case "land" -> c.shift() == ShiftRole.FARM || c.shift() == ShiftRole.WOOD || c.shift() == ShiftRole.SHEAR;
            case "mine" -> c.shift() == ShiftRole.MINE;
            case "water" -> c.shift() == ShiftRole.FISH;
            case "work" -> c.shift() == ShiftRole.CRAFT || c.shift() == ShiftRole.SMELT;
            case "serve" -> c.shift() == ShiftRole.COUNTER || c.shift() == ShiftRole.COOK || c.shift() == ShiftRole.WAITER || c.shift() == ShiftRole.BAKE;
            case "haul" -> c.shift() == ShiftRole.HAUL || c.shift() == ShiftRole.BUILD || c.shift() == ShiftRole.WAGON;
            case "watch" -> c.shift() == ShiftRole.WATCH;
            case "care" -> c.shift() == ShiftRole.HEALER;
            default -> true;
        };
    }

    static ItemStack icon(JobCards.Card c) {
        Item i = switch (c.shift()) {
            case FARM -> Items.WHEAT;
            case WOOD -> Items.IRON_AXE;
            case SHEAR -> Items.SHEARS;
            case MINE -> Items.IRON_PICKAXE;
            case FISH -> Items.FISHING_ROD;
            case SMELT -> Items.FURNACE;
            case CRAFT -> Items.CRAFTING_TABLE;
            case HAUL -> Items.CHEST;
            case COUNTER -> Items.EMERALD;
            case COOK -> Items.SMOKER;
            case WAITER -> Items.BOWL;
            case BAKE -> Items.BREAD;
            case BUILD -> Items.BRICKS;
            case HEALER -> Items.GOLDEN_APPLE;
            case WAGON -> Items.OAK_PLANKS;
            case WATCH -> Items.IRON_SWORD;
        };
        return new ItemStack(i);
    }

    static int distance(@Nullable BlockPos at, Player p) {
        return at == null ? 9999 : (int) Math.sqrt(at.distSqr(p.blockPosition()));
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        String view = c.args.getString("view");
        if (v != null && view.equals("advert")) {
            Advert a = find(level, v, p, c.args.getString("ad"));
            if (a != null) return advertPage(level, p, a, c);
            c.args.remove("view");
        }
        if (v != null && view.equals("offer")) {
            CompoundTag t = appByKey(p, c.args.getString("ad"));
            if (t != null) return offerPage(level, p, t);
            c.args.remove("view");
        }
        if (view.equals("interview")) {
            CompoundTag t = appByKey(p, c.args.getString("ad"));
            if (t != null && t.getString("st").equals("INTERVIEW")) return interviewPage(level, p, t, c);
            c.args.remove("view");
        }
        LifePage page = new LifePage("jobs", "Jobs", v == null ? "No town near: find one to work in" : Villages.name(v.id()) + ": its adverts, and your work",
            p.registryAccess());
        if (c.tab.isEmpty()) c.tab = PlayerJobs.employed(p) && !c.args.getString("from").equals("hall") ? "job" : "adverts";
        page.tab("adverts", "Adverts", c.tab.equals("adverts")).tab("job", "My job", c.tab.equals("job")).tab("apps", "Applications", c.tab.equals("apps"))
            .tab("week", "My week", c.tab.equals("week")).tab("handin", "Hand in", c.tab.equals("handin"));
        hint(p, page, c.tab);
        switch (c.tab) {
            case "job" -> jobTab(level, p, v, page);
            case "apps" -> appsTab(level, p, page);
            case "week" -> weekTab(level, p, page);
            case "handin" -> handInTab(level, p, page);
            default -> advertsTab(level, p, v, page, c);
        }
        return page;
    }

    /** A dismissable first-time hint for each tab. */
    static void hint(ServerPlayer p, LifePage page, String tab) {
        String key = "wpb/hint/" + tab;
        if (Earnings.data(p).getBoolean(key)) return;
        String text = switch (tab) {
            case "job" -> "Your card says what's wanted each shift. The arrow at the top of the screen always points to your workplace. The boss judges the week, not one bad day.";
            case "apps" -> "Applied, shortlisted, interview, offer. With other applicants there's a short interview here on the screen.";
            case "week" -> "Seven days at a glance: what you did, what you were paid, and what's coming. Offline days never count against you.";
            case "handin" -> "On commission, bring your output here. Only what you grew, cut or caught for this job counts; anything else is handed back with the reason.";
            default -> "Every advert offers two ways of pay: a salary (the going wage, a soft goal) or commission (pay per unit, no firing for a slow day). Press Details to read the job card.";
        };
        page.box("First time here", List.of(text), 4);
        page.buttons().button("Got it", "hint", LifePage.args("k", tab));
    }

    @Nullable
    static Advert find(ServerLevel level, Villages.Village v, ServerPlayer p, String key) {
        for (Advert a : adverts(level, v, p)) if (a.key().equals(key)) return a;
        return null;
    }

    @Nullable
    static CompoundTag appByKey(Player p, String key) {
        ListTag l = apps(p);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getString("key").equals(key)) return l.getCompound(i);
        return null;
    }

    // ---- adverts

    void advertsTab(ServerLevel level, ServerPlayer p, @Nullable Villages.Village v, LifePage page, PlayerLife.Ctx c) {
        if (v == null) {
            page.text("Find a town: its adverts show here.", 4);
            return;
        }
        String g = c.args.contains("g") ? c.args.getString("g") : "any", pay = c.args.contains("pay") ? c.args.getString("pay") : "any",
            who = c.args.contains("who") ? c.args.getString("who") : "any", sort = c.args.contains("sort") ? c.args.getString("sort") : "pay";
        String[] gv = new String[GROUPS.length], gl = new String[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) { gv[i] = GROUPS[i][0]; gl[i] = GROUPS[i][1]; }
        page.choice("g", "Trade", gv, gl, g);
        page.choice("pay", "Pay", new String[]{ "any", "salary", "commission" }, new String[]{ "Either way", "Salary on offer", "Commission on offer" }, pay);
        page.choice("who", "Employer", new String[]{ "any", "town", "firm", "player", "civic" }, new String[]{ "Anyone", "The town", "Firms and farms", "Players", "Civic posts" }, who);
        page.choice("sort", "Sort by", new String[]{ "pay", "near", "fit" }, new String[]{ "Pay", "Distance", "Fit for you" }, sort);
        boolean toastsOff = Earnings.data(p).getBoolean("wpb/toastsOff");
        page.buttons().button("Apply filters", "filter", null).primary().button(toastsOff ? "Advert toasts: off" : "Advert toasts: on", "toasts", null);
        List<Advert> all = adverts(level, v, p);
        List<Advert> shown = new ArrayList<>();
        for (Advert a : all) {
            if (!inGroup(a.card(), g)) continue;
            if (pay.equals("salary") && a.payOptions() == 2 || pay.equals("commission") && a.payOptions() == 1) continue;
            boolean town = a.employer().startsWith("town:") || a.employer().equals("market") || a.employer().equals("watch");
            if (who.equals("town") && (!town || a.src().equals("c")) || who.equals("firm") && !(a.employer().startsWith("firm:") || a.employer().startsWith("farm:"))
                || who.equals("player") && !a.src().equals("p") || who.equals("civic") && !a.src().equals("c")) continue;
            shown.add(a);
        }
        Comparator<Advert> cmp = switch (sort) {
            case "near" -> Comparator.comparingInt(a -> distance(a.where(), p));
            case "fit" -> Comparator.comparingInt(a -> -Shifts.xp(p, a.card().shift()));
            default -> Comparator.comparingInt(a -> -a.wage());
        };
        shown.sort(cmp);
        page.heading("Adverts (" + shown.size() + (shown.size() == all.size() ? "" : " of " + all.size()) + ")");
        if (all.isEmpty()) {
            page.text(LabourMarket.live(v.id()) ? "No adverts up just now. The board fills in the morning; meanwhile, take a day's shift below." :
                "The town has no labour market yet (its market isn't open). Take a day's shift below: paid by the task.", 4);
        } else if (shown.isEmpty()) page.text("None match those filters. Set them back to \"any\" to see all " + all.size() + ".", 4);
        for (Advert a : shown) {
            PayTerms.Terms[] t = terms(level, a, p);
            String payWords = a.payOptions() == 2 ? "commission " + t[1].rateWords() : a.payOptions() == 1 ? a.wage() + "c a day" : a.wage() + "c a day, or " + t[1].rateWords();
            CompoundTag app = applied(p, a);
            String sub = payWords + " · " + a.hours() + " · aim " + a.card().targetLine(t[0].target()) + " · " + a.applicants() + (a.applicants() == 1 ? " applicant" : " applicants")
                + " · closes day " + a.closes() + (a.citizensOnly() ? " · citizens only" : "") + (app != null ? " · " + statusWords(app, level) : "");
            String no = cannotApply(level, a, p);
            page.row(icon(a.card()), a.card().title() + " · " + a.employerName(), sub, a.where() == null ? "" : distance(a.where(), p) + " m")
                .button("Details", "details", LifePage.args("ad", a.key())).primary()
                .button("Show me", "showme", LifePage.args("ad", a.key()), a.where() != null, "No place to show yet")
                .button("Apply", "details", LifePage.args("ad", a.key()), no.isEmpty(), no);
        }
        LabourBook.Town tw = LabourBook.peek(v.id());                       // lately filled, and by whom
        if (tw != null) {
            long today = level.getDayTime() / 24000L;
            List<String> filled = new ArrayList<>();
            for (LabourBook.Vacancy vac : tw.vacancies) {
                if (vac.state != LabourBook.State.FILLED || vac.closed < today - 1 || JobCards.of(vac.trade) == null) continue;
                filled.add(JobCards.of(vac.trade).title() + " at " + PlayerJobs.employerName(vac) + ": " + (vac.by.isEmpty() ? "filled" : vac.by.replaceFirst("^taken: ", "taken by ")));
            }
            if (!filled.isEmpty()) {
                page.heading("Lately filled");
                for (int i = 0; i < Math.min(4, filled.size()); i++) page.text(filled.get(i), 4);
            }
        }
        page.heading("Day work (paid by the task, no interview)");
        List<Shifts.Offer> offers = Shifts.offers(level, v, p);
        if (offers.isEmpty()) page.text("The town has no day shifts going.", 4);
        for (Shifts.Offer o : offers) {
            page.row(icon(JobCards.forRole(o.role())), o.title() + " · " + o.role().place, o.hours() + " · " + String.format(Locale.ROOT, "about %.1fc a task", o.perTask())
                    + (o.whyNot().isEmpty() ? "" : " · " + o.whyNot()), "")
                .button("Take", "take", LifePage.args("r", o.role().id), o.whyNot().isEmpty() && !Shifts.onShift(p), o.whyNot().isEmpty() ? "You're on a shift" : o.whyNot());
        }
        page.buttons().button("The Earnings page", "earnings", null).button("Your life in town", "life", null)
            .button("Your crew roster", "roster", null, !AssistantEntity.allFor(p.getUUID()).isEmpty(), "You have no crew of your own");
    }

    LifePage advertPage(ServerLevel level, ServerPlayer p, Advert a, PlayerLife.Ctx c) {
        JobCards.Card card = a.card();
        LifePage page = new LifePage("jobs", card.title() + " at " + a.employerName(), Villages.name(a.town()) + " · advert", p.registryAccess());
        page.text(card.role());
        page.box("The employer", List.of(a.employerName() + (a.src().equals("c") ? " (a civic post)" : a.src().equals("p") ? " (a player's business)" : ""),
            respect(level, a, p), "You'd report to: " + card.reportTo() + "."), 0);
        PayTerms.Terms[] t = terms(level, a, p);
        if (a.payOptions() != 2) page.box("How you're paid: on salary", PayTerms.box(t[0], card, a.employerName()), 1);
        if (a.payOptions() != 1) page.box("How you're paid: on commission", PayTerms.box(t[1], card, a.employerName()), 4);
        page.heading("Where");
        page.text(a.whereWords() + (a.where() == null ? " (not marked yet)" : ", " + distance(a.where(), p) + " m from you."));
        if (a.where() != null) {
            Villages.Village v = Villages.get(a.town());
            BlockPos mid = v == null ? a.where() : v.centre();
            LifePage.Map m = page.map("The town, the workplace and you");
            m.place(0, 0, Villages.name(a.town()) + "'s heart", false, 1, null, null);
            m.place(a.where().getX() - mid.getX(), a.where().getZ() - mid.getZ(), a.whereWords(), true, 0, "showme", LifePage.args("ad", a.key()));
            m.place(p.getBlockX() - mid.getX(), p.getBlockZ() - mid.getZ(), "You", false, 2, null, null);
            page.buttons().button("Show me", "showme", LifePage.args("ad", a.key()));
        }
        page.heading("Hours and targets");
        page.text("Hours: " + a.hours() + ". One shift a day (about four hours of the day).");
        page.text("Each shift, aim for " + card.targetLine(t[0].target()) + ". That's three stars, worked out from what a competent hand gets through.");
        page.text(JobCards.starsLine(card, t[0].target()), 4);
        page.text(JobCards.excused(card), 4);
        page.heading("What the job involves");
        for (int i = 0; i < card.day().size(); i++) page.text((i + 1) + ". " + card.day().get(i));
        page.text("Tools: " + card.tools() + (card.provided() ? " Provided." : " Bring your own."));
        page.text("Where: " + card.where());
        page.text("Conditions: " + card.conditions());
        page.text("You report to: " + card.reportTo() + ".");
        page.text("Skills it trains: " + card.skills());
        page.text("Promotion: " + card.promotion() + " (a sixth more pay each step).");
        page.heading("What earns a warning or a firing");
        if (a.payOptions() != 2) { page.text("On salary:", 0); for (String r : JobCards.rules(false)) page.text("• " + r, 4); }
        if (a.payOptions() != 1) { page.text("On commission:", 0); for (String r : JobCards.rules(true)) page.text("• " + r, 4); }
        page.heading("Requirements");
        for (String r : requirements(level, a, p)) page.text(r, r.startsWith("✘") ? 3 : 1);
        page.heading("Applicants");
        page.text(a.applicants() == 0 ? "Nobody else has applied yet." : a.applicants() + " folk applied (" + (a.applicants() - a.folkComm()) + " on salary, "
            + a.folkComm() + " on commission). With two or more, there's an interview.");
        page.text("Closes about day " + a.closes() + ".", 4);
        String no = cannotApply(level, a, p);
        LifePage.Row row = page.buttons();
        if (a.payOptions() != 2) row.button("Apply on salary", "apply", LifePage.args("ad", a.key(), "comm", false), no.isEmpty(), no).primary();
        if (a.payOptions() != 1) row.button("Apply on commission", "apply", LifePage.args("ad", a.key(), "comm", true), no.isEmpty(), no);
        row.button("Back to the adverts", "back", null);
        return page;
    }

    LifePage offerPage(ServerLevel level, ServerPlayer p, CompoundTag t) {
        JobCards.Card card = JobCards.of(t.getString("card"));
        LifePage page = new LifePage("jobs", "Job offer: " + (card == null ? "" : card.title()), t.getString("name"), p.registryAccess());
        if (card == null || !t.getString("st").equals("OFFERED")) {
            page.text("That offer isn't standing any more: " + statusWords(t, level), 3);
            page.buttons().button("Back", "back", null);
            return page;
        }
        page.text(t.getString("name") + " offers you the job of " + card.title().toLowerCase(Locale.ROOT) + " (" + t.getString("why") + ").");
        int target = WorkMath.scaled(card.target(), Shifts.level(p, card.shift()), 0, 0, false);
        PayTerms.Terms sal = PayTerms.salary(card, t.getInt("wage"), target);
        PayTerms.Terms com = PayTerms.commission(level, t.getUUID("town"), card, t.getInt("wage"), target, bossOf(t.getUUID("town"), t.getString("employer")), t.getString("name"));
        int opts = t.getInt("payOptions");
        if (opts != 2) page.box("On salary", PayTerms.box(sal, card, t.getString("name")), 1);
        if (opts != 1) page.box("On commission", PayTerms.box(com, card, t.getString("name")), 4);
        page.text("You applied " + (t.getBoolean("comm") ? "on commission" : "on salary") + "; you may choose either now" + (opts != 0 ? " (this post offers one way only)" : "")
            + ", and switch at a review later.", 4);
        page.text("Hours: " + t.getString("hours") + ". Aim: " + card.targetLine(target) + " a shift.");
        String no = PlayerJobs.employed(p) ? "You have a job already: give notice first" : "";
        LifePage.Row row = page.buttons();
        if (opts != 2) row.button("Accept on salary", "accept", LifePage.args("ad", t.getString("key"), "comm", false), no.isEmpty(), no).primary();
        if (opts != 1) row.button("Accept on commission", "accept", LifePage.args("ad", t.getString("key"), "comm", true), no.isEmpty(), no);
        row.button("Decline", "decline", LifePage.args("ad", t.getString("key")));
        page.buttons().button("Back", "back", null);
        return page;
    }

    LifePage interviewPage(ServerLevel level, ServerPlayer p, CompoundTag t, PlayerLife.Ctx c) {
        JobCards.Card card = JobCards.of(t.getString("card"));
        LifePage page = new LifePage("jobs", "Interview: " + t.getString("name"), card == null ? "" : card.title(), p.registryAccess());
        List<PlayerInterview.Question> qs = PlayerInterview.questions(card == null ? ShiftRole.HAUL : card.shift(), t.getInt("id"));
        int q = t.getInt("q");
        if (q >= qs.size()) {
            page.text("That's all the questions.");
            page.buttons().button("Back", "back", null);
            return page;
        }
        page.text("Question " + (q + 1) + " of " + qs.size() + ". " + t.getInt("rivals") + " other " + (t.getInt("rivals") == 1 ? "applicant is" : "applicants are") + " being seen too.", 4);
        page.box(qs.get(q).q(), List.of("Pick the answer you'd give."), 4);
        for (int i = 0; i < qs.get(q).answers().length; i++) {
            page.buttons().button(qs.get(q).answers()[i], "answer", LifePage.args("ad", t.getString("key"), "a", i));
        }
        return page;
    }

    // ---- my job

    void jobTab(ServerLevel level, ServerPlayer p, @Nullable Villages.Village v, LifePage page) {
        CompoundTag j = PlayerJobs.job(p);
        if (!j.contains("role")) {
            page.text("No job yet. Read the adverts and apply: pick salary or commission when you do.", 4);
            page.buttons().button("See the adverts", "tab:adverts", null).primary();
            history(p, page);
            return;
        }
        JobCards.Card card = WorkLife.card(j);
        boolean comm = "commission".equals(j.getString("pay"));
        long today = level.getDayTime() / 24000L;
        CompoundTag d = WorkLife.day(j, today);
        int aim = WorkLife.baseTarget(p, j);
        List<String> lines = new ArrayList<>();
        if (card != null) lines.add(card.role());
        lines.add("Rank: " + WorkLife.rankTitle(j) + ". Since day " + j.getLong("since") + ". Hours: " + j.getString("hours") + ".");
        lines.add(comm ? "Pay: commission, " + j.getString("rateWords") + (j.getInt("base") > 0 ? " on a base of " + j.getInt("base") + "c a day" : "")
            + ". Why: " + j.getString("rateWhy") + "." : "Pay: " + Math.round(j.getInt("wage") * (1 + WorkLife.RANK_PAY * WorkLife.rank(j))) + "c a day, paid each morning for a day worked.");
        if (card != null) lines.add("Each shift: aim for " + card.targetLine(aim) + ". " + JobCards.starsLine(card, aim));
        if (!j.getString("switchTo").isEmpty()) lines.add("You've asked to go on " + j.getString("switchTo") + ": it starts at tomorrow's review.");
        if (j.getLong("leaving") > 0) lines.add("You've given notice: your last day is day " + (j.getLong("leaving") - 1) + ".");
        page.box("Your job card: " + WorkLife.rankTitle(j) + " at " + j.getString("employerName"), lines, comm ? 4 : 1);
        double u = d.getDouble("u");
        page.bar("Today", aim <= 0 ? 1 : u / aim, (int) u + " of " + aim + (card == null ? "" : " " + card.measure().many()) + " · " + EarnMath.starWord(WorkMath.dayStars(u, aim)));
        BlockPos at = null;
        CompoundTag wp = WorkWay.workplace(level, p, j);
        if (wp != null && wp.getIntArray("at").length == 3) { int[] a = wp.getIntArray("at"); at = new BlockPos(a[0], a[1], a[2]); }
        page.row(new ItemStack(Items.COMPASS), "Where: " + (wp == null ? j.getString("employerName") : wp.getString("l")),
                at == null ? "The workplace isn't marked just now." : distance(at, p) + " m from you. The arrow at the top of the screen points to it" + (WorkWay.off(p) ? " (switched off)." : "."), "")
            .button("Show me", "showjob", null, at != null, "Not marked just now")
            .button(WorkWay.off(p) ? "Arrow: off" : "Arrow: on", "arrow", null);
        String why = Shifts.onShift(p) ? "You're on a shift" : WorkLife.onLeave(j, today) ? "You're on leave today" : "";
        page.buttons().button("Start today's shift", "shift", null, why.isEmpty(), why).primary()
            .button("Hand in", "tab:handin", null, card != null && card.measure().produce() && comm, comm ? "Only produce is handed in; your pay comes as each task is done" : "On salary your output goes straight to the employer");
        page.heading("What the boss said");
        ListTag said = j.getList("said", Tag.TAG_STRING);
        if (said.isEmpty()) page.text("Nothing yet. After each shift the boss says how it went.", 4);
        for (int i = 0; i < Math.min(4, said.size()); i++) page.text(said.getString(i), i == 0 ? 0 : 4);
        int stage = j.getInt("stage");
        page.text(stage == 0 ? "No warnings. " + (comm ? "On commission only misconduct leads to a warning." : "Only a pattern of poor or missed days leads to a talk.")
            : stage == 1 ? "The boss had a word on day " + j.getLong("stageDay") + ". A better week lifts it." : "A formal warning on day " + j.getLong("stageDay")
            + ". Another, and you're let go. A good week lifts it a step.", stage == 0 ? 1 : stage == 1 ? 2 : 3);
        if (card != null && WorkLife.rank(j) < 2) {
            int good = 0;
            for (CompoundTag r : WorkLife.days(j)) if (r.getLong("d") < today && r.getLong("d") >= today - 7 && WorkLife.worked(r) && r.getInt("st") >= 4) good++;
            int lv = Shifts.level(p, card.shift());
            page.text("Promotion to " + (WorkLife.rank(j) == 0 ? card.senior() : card.head()) + ": " + (WorkLife.GOOD_FOR_PROMOTION - 1) + " four-star days in a week (you have "
                + good + "), skill " + EarnMath.LEVELS[Math.min(4, WorkLife.rank(j) + 1)] + " (yours: " + EarnMath.LEVELS[lv] + ").", 4);
        }
        page.heading("Leave, pay and leaving");
        page.choice("leave", "Leave from tomorrow", new String[]{ "1", "2", "3" }, new String[]{ "1 day", "2 days", "3 days" }, "1");
        page.buttons().button("Book leave", "leave", null, j.getInt("leaveTaken") < WorkLife.MOST_LEAVE, "No leave left this month")
            .button(comm ? "Ask to go on salary" : "Ask to go on commission", "switch", null, !j.getBoolean("fixedPay") && j.getString("switchTo").isEmpty(),
                j.getBoolean("fixedPay") ? "This post is offered one way only" : "Asked already: it starts tomorrow");
        boolean sure = PlayerLife.ctx(p).args.getBoolean("sureQuit");
        page.buttons().button("Give notice (leave after tomorrow)", "notice", null, j.getLong("leaving") <= 0, "You've given notice")
            .button(sure ? "Yes, leave now without notice" : "Leave now", "leavenow", LifePage.args("sure", sure));
        if (card != null) {
            page.heading("What the job involves");
            for (int i = 0; i < card.day().size(); i++) page.text((i + 1) + ". " + card.day().get(i), 4);
        }
        history(p, page);
    }

    static void history(ServerPlayer p, LifePage page) {
        ListTag hist = Earnings.data(p).getList("jobHistory", Tag.TAG_COMPOUND);
        if (hist.isEmpty()) return;
        page.heading("Past work");
        for (int i = 0; i < Math.min(5, hist.size()); i++) page.text(hist.getCompound(i).getString("line"), 4);
    }

    // ---- applications

    void appsTab(ServerLevel level, ServerPlayer p, LifePage page) {
        ListTag l = apps(p);
        page.heading("Your applications");
        if (l.isEmpty()) {
            page.text("None yet. Press Details on an advert and apply.", 4);
            page.buttons().button("See the adverts", "tab:adverts", null).primary();
            return;
        }
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            JobCards.Card card = JobCards.of(t.getString("card"));
            String st = t.getString("st");
            String path = "applied" + (st.equals("APPLIED") ? "" : " → shortlisted") + (t.contains("at") ? " → interview" : "")
                + (st.equals("OFFERED") || st.equals("ACCEPTED") || st.equals("DECLINED") ? " → offered" : "") + (st.equals("TURNED_DOWN") ? " → turned down" : "")
                + (st.equals("ACCEPTED") ? " → accepted" : "");
            LifePage.Row row = page.row(card == null ? null : icon(card), (card == null ? "A job" : card.title()) + " · " + t.getString("name"),
                statusWords(t, level) + " (" + path + ")", t.getBoolean("comm") ? "commission" : "salary");
            if (st.equals("INTERVIEW")) {
                boolean due = level.getGameTime() >= t.getLong("at") - 200;
                row.button("Attend interview", "attend", LifePage.args("ad", t.getString("key")), due, "Not yet: come back at the time").primary();
            }
            if (st.equals("OFFERED")) row.button("See the offer", "offer", LifePage.args("ad", t.getString("key"))).primary();
            if (live(st)) row.button("Withdraw", "withdraw", LifePage.args("ad", t.getString("key")));
        }
    }

    // ---- my week

    void weekTab(ServerLevel level, ServerPlayer p, LifePage page) {
        CompoundTag j = PlayerJobs.job(p);
        long today = level.getDayTime() / 24000L;
        page.heading("My week");
        JobCards.Card card = WorkLife.card(j);
        for (long d = today - 4; d <= today + 3; d++) {
            String label = d == today ? "Today (day " + d + ")" : "Day " + d;
            String sub;
            int colour = 0;
            if (!j.contains("role")) sub = d < today ? "No job." : "No job yet.";
            else if (d < j.getLong("since")) sub = "Before this job.";
            else if (WorkLife.onLeave(j, d)) sub = "On leave.";
            else if (d < today) {
                CompoundTag r = null;
                for (CompoundTag x : WorkLife.days(j)) if (x.getLong("d") == d) r = x;
                if (r == null || !WorkLife.worked(r) && !r.getBoolean("o")) sub = "Not in town: offline days never count.";
                else if (!WorkLife.worked(r)) sub = "In town but didn't work.";
                else sub = (int) r.getDouble("u") + " of " + r.getInt("t") + (card == null ? "" : " " + card.measure().many()) + " · " + EarnMath.starWord(r.getInt("st"))
                    + " · paid " + r.getInt("c") + "c" + (r.getBoolean("p") ? "" : " (due in the morning)");
            } else if (d == today) {
                CompoundTag r = WorkLife.day(j, d);
                sub = "Shift: " + j.getString("hours") + " at " + j.getString("employerName") + ". Done so far: " + (int) r.getDouble("u") + " of " + WorkLife.baseTarget(p, j) + ".";
            } else sub = j.getLong("leaving") > 0 && d >= j.getLong("leaving") ? "You'll have left." : "Shift: " + j.getString("hours") + " at " + j.getString("employerName") + ".";
            page.row(null, label, sub, "");
        }
        ListTag l = apps(p);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            if (t.getString("st").equals("INTERVIEW")) page.text("Interview: " + t.getString("name") + ", " + statusWords(t, level), 2);
            if (t.getString("st").equals("OFFERED")) page.text("Offer waiting: " + t.getString("name") + ".", 1);
        }
    }

    // ---- hand in

    void handInTab(ServerLevel level, ServerPlayer p, LifePage page) {
        CompoundTag j = PlayerJobs.job(p);
        JobCards.Card card = WorkLife.card(j);
        page.heading("Hand in");
        if (!j.contains("role") || card == null || !card.measure().produce() || !"commission".equals(j.getString("pay"))) {
            page.text("Only produce on commission is handed in: wheat, logs, wool, ore and fish grown, cut or caught on your job. "
                + (j.contains("role") ? "Yours goes straight to " + j.getString("employerName") + "." : "You have no job."), 4);
            return;
        }
        WorkLife.HandIn h = WorkLife.handIn(level, p, false);
        BlockPos at = WorkLife.handInAt(level, j);
        boolean near = at != null && p.blockPosition().closerThan(at, 24);
        page.box("What counts", List.of(h.counted() == 0 ? "Nothing in your pack counts yet." : h.counted() + " " + h.goods() + " from your shifts: " + h.pay() + "c at "
            + j.getString("rateWords") + (h.carry() > 0.004 ? " (" + WorkMath.coin(h.carry()) + " carried to the next hand-in)" : "") + "."), 1);
        if (!h.refused().isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (var e : h.refused().entrySet()) lines.add(e.getValue() + " of these weren't yours to hand in: " + e.getKey() + ". They don't count.");
            lines.add("They stay in your pack.");
            page.box("What doesn't count", lines, 2);
        }
        page.buttons().button("Hand in " + h.counted(), "handin", null, h.counted() > 0 && near, h.counted() == 0 ? "Nothing to hand in"
                : "Take it to " + (at == null ? "the employer" : "the storehouse") + " (within 24 blocks)").primary()
            .button("Show me where", "showhandin", null, at != null, "Nowhere to show");
        page.text("Handed in so far: " + j.getInt("handed") + ". Earned on commission: " + j.getInt("earned") + "c.", 4);
    }

    // ------------------------------------------------------------------ the buttons

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        CompoundTag f = args.getCompound("f");
        if (action.startsWith("tab:")) { c.tab = action.substring(4); c.args.remove("view"); return ""; }
        switch (action) {
            case "hint" -> { Earnings.data(p).putBoolean("wpb/hint/" + args.getString("k"), true); return ""; }
            case "filter" -> {
                for (String k : new String[]{ "g", "pay", "who", "sort" }) if (f.contains(k)) c.args.putString(k, f.getString(k));
                return "Filters set.";
            }
            case "toasts" -> {
                boolean off = !Earnings.data(p).getBoolean("wpb/toastsOff");
                Earnings.data(p).putBoolean("wpb/toastsOff", off);
                return off ? "No more toasts about new adverts. Switch them back on here." : "You'll get a toast for new adverts each morning.";
            }
            case "details" -> { c.args.putString("view", "advert"); c.args.putString("ad", args.getString("ad")); return ""; }
            case "back" -> { c.args.remove("view"); return ""; }
            case "showme" -> {
                if (v == null) return "!No town here.";
                Advert a = find(level, v, p, args.getString("ad"));
                if (a == null || a.where() == null) return "!That place isn't marked just now.";
                WorkWay.showMe(p, a.where(), a.whereWords());
                return "#close";
            }
            case "showjob" -> {
                CompoundTag wp = WorkWay.workplace(level, p, PlayerJobs.job(p));
                if (wp == null) return "!Your workplace isn't marked just now.";
                int[] at = wp.getIntArray("at");
                WorkWay.showMe(p, new BlockPos(at[0], at[1], at[2]), wp.getString("l"));
                return "#close";
            }
            case "showhandin" -> {
                BlockPos at = WorkLife.handInAt(level, PlayerJobs.job(p));
                if (at == null) return "!Nowhere to show.";
                WorkWay.showMe(p, at, "Hand in here");
                return "#close";
            }
            case "apply" -> {
                if (v == null) return "!No town here.";
                Advert a = find(level, v, p, args.getString("ad"));
                if (a == null) return "!That advert has come down.";
                String said = apply(level, p, a, args.getBoolean("comm"));
                if (!said.startsWith("!")) { c.args.remove("view"); c.tab = "apps"; }
                return said;
            }
            case "withdraw" -> {
                CompoundTag t = appByKey(p, args.getString("ad"));
                if (t == null || !live(t.getString("st"))) return "!Nothing to withdraw.";
                t.putString("st", "WITHDRAWN");
                t.putString("why", "you withdrew it");
                return "Application withdrawn.";
            }
            case "attend" -> {
                CompoundTag t = appByKey(p, args.getString("ad"));
                if (t == null || !t.getString("st").equals("INTERVIEW")) return "!There's no interview waiting.";
                if (level.getGameTime() < t.getLong("at") - 200) return "!Not yet: the interview is at the time shown.";
                c.args.putString("view", "interview");
                c.args.putString("ad", t.getString("key"));
                return "";
            }
            case "answer" -> {
                CompoundTag t = appByKey(p, args.getString("ad"));
                if (t == null || !t.getString("st").equals("INTERVIEW")) return "!There's no interview waiting.";
                JobCards.Card card = JobCards.of(t.getString("card"));
                List<PlayerInterview.Question> qs = PlayerInterview.questions(card == null ? ShiftRole.HAUL : card.shift(), t.getInt("id"));
                int q = t.getInt("q");
                if (q >= qs.size()) return "!The interview's over.";
                int a = Math.max(0, Math.min(qs.get(q).answers().length - 1, args.getInt("a")));
                t.putInt("pts", t.getInt("pts") + qs.get(q).marks()[a]);
                t.putInt("q", q + 1);
                if (q + 1 < qs.size()) return "Noted.";
                c.args.remove("view");
                c.tab = "apps";
                return interviewed(level, p, t, t.getInt("pts"));
            }
            case "offer" -> { c.args.putString("view", "offer"); c.args.putString("ad", args.getString("ad")); return ""; }
            case "accept" -> {
                CompoundTag t = appByKey(p, args.getString("ad"));
                if (t == null) return "!No such offer.";
                String said = accept(level, p, t, args.getBoolean("comm"));
                if (!said.startsWith("!")) { c.args.remove("view"); c.tab = "job"; }
                return said;
            }
            case "decline" -> {
                CompoundTag t = appByKey(p, args.getString("ad"));
                if (t == null || !t.getString("st").equals("OFFERED")) return "!No offer to decline.";
                t.putString("st", "DECLINED");
                c.args.remove("view");
                c.tab = "apps";
                return "You declined " + t.getString("name") + "'s offer. No harm done.";
            }
            case "shift" -> {
                CompoundTag j = PlayerJobs.job(p);
                if (!j.contains("role")) return "!You have no job.";
                String s = Shifts.take(p, j.getUUID("town"), j.getString("role"), true);
                return s.isEmpty() ? "Your shift has started. The arrow points to your first task." : s;
            }
            case "take" -> {
                String s = Shifts.take(p, v == null ? null : v.id(), args.getString("r"), false);
                return s.isEmpty() ? "Your shift has started. The arrow points to your first task." : s;
            }
            case "arrow" -> { return WorkWay.toggle(p); }
            case "leave" -> { return WorkLife.bookLeave(p, ShopPage.parse(f.getString("leave"), 1)); }
            case "switch" -> { return WorkLife.askSwitch(p); }
            case "notice" -> { return WorkLife.giveNotice(p); }
            case "quit" -> { return WorkLife.leaveNow(level, p, ""); }      // the old board's Quit (and its test): at once
            case "leavenow" -> {
                if (!PlayerJobs.employed(p)) return "!You have no job.";
                if (!args.getBoolean("sure")) {
                    c.args.putBoolean("sureQuit", true);
                    return "!Leaving without notice means no pay for today. Press again to leave now, or give notice instead.";
                }
                c.args.remove("sureQuit");
                return WorkLife.leaveNow(level, p, "");
            }
            case "handin" -> {
                CompoundTag j = PlayerJobs.job(p);
                BlockPos at = WorkLife.handInAt(level, j);
                if (at == null || !p.blockPosition().closerThan(at, 24)) return "!Take it to the storehouse first: press Show me where.";
                WorkLife.HandIn h = WorkLife.handIn(level, p, true);
                if (h.counted() == 0) return "!Nothing in your pack counts: " + (h.refused().isEmpty() ? "bring what you grew, cut or caught on your shifts." : "see what doesn't count, and why.");
                return "Handed in " + h.counted() + " " + h.goods() + ": paid " + h.pay() + "c." + (h.refused().isEmpty() ? "" : " Some things didn't count and stay with you: see why below.");
            }
            case "earnings" -> { LifeTown.books(p, "Earnings"); return "#close"; }
            case "roster" -> { com.jrpetty.mcassistant.block.JobBoardBlock.showRoster(p); return "#close"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static List<String> advertKeysForTests(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<String> out = new ArrayList<>();
        for (Advert a : adverts(level, v, p)) out.add(a.key());
        return out;
    }

    public static String applyForTests(ServerLevel level, ServerPlayer p, String key, boolean commission) {
        Villages.Village v = PlayerLife.here(p);
        Advert a = v == null ? null : find(level, v, p, key);
        return a == null ? "!no advert" : apply(level, p, a, commission);
    }

    /** Tests: the application's state. */
    public static String statusForTests(Player p, String key) {
        CompoundTag t = appByKey(p, key);
        return t == null ? "" : t.getString("st");
    }

    /** Tests: the clock run on so many ticks for the applications. */
    public static void ageAppsForTests(Player p, long ticks) {
        ListTag l = apps(p);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            t.putLong("since", t.getLong("since") - ticks);
            if (t.contains("at")) t.putLong("at", t.getLong("at") - ticks);
        }
    }

    public static void tickForTests(ServerLevel level) {
        tick(level);
    }

    public static String acceptForTests(ServerLevel level, ServerPlayer p, String key, boolean commission) {
        CompoundTag t = appByKey(p, key);
        return t == null ? "!none" : accept(level, p, t, commission);
    }

    public static String interviewForTests(ServerLevel level, ServerPlayer p, String key, int marks) {
        CompoundTag t = appByKey(p, key);
        return t == null ? "!none" : interviewed(level, p, t, marks);
    }
}
