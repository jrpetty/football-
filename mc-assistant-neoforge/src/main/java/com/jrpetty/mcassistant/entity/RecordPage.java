package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.entity.Justice.Charge;
import com.jrpetty.mcassistant.entity.Justice.Ev;
import com.jrpetty.mcassistant.entity.Justice.JCase;
import com.jrpetty.mcassistant.entity.Justice.Stage;
import com.jrpetty.mcassistant.entity.Justice.Wit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpe] The player's page with the law (PlayerLife "record"): opened from Your life in town, the Town Hall's Respect
 * tab, any folk ("What's on my record?"), and by itself whenever there is news (an arrest, a summons, a sentence).
 * <ul>
 * <li><b>Notice:</b> the latest news in full: what happened, why, and what's next.</li>
 * <li><b>Case:</b> each open case against the player: the charge, where it stands, the witnesses and evidence the watch
 *     has, the court's date and "Show me"; the defence (plead guilty, an account, a lawyer, the public defender); the
 *     court's transcript as it sits; fines owed, paid here.</li>
 * <li><b>Jail:</b> custody: where, why, how long, visiting hours, meals; "Show me" the jail, the watch house and the court.</li>
 * <li><b>Record:</b> convictions, complaints, apologies accepted, and what a record costs: respect, jobs, credit.</li>
 * <li><b>How it works:</b> crimes, witnesses, arrest, the jail, the court, in a dozen plain lines.</li>
 * </ul>
 */
public final class RecordPage implements PlayerLife.Page {

    static final String[][] TABS = { { "notice", "Notice" }, { "case", "Case" }, { "jail", "Jail" }, { "record", "Record" }, { "how", "How it works" } };

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        UUID me = p.getUUID();
        List<JCase> open = Justice.namedAgainst(me);
        boolean held = Jail.held(me);
        String sub = held ? "Held: " + Justice.nonEmpty(Jail.custodyForTests(me).getString("reason"), "in custody")
            : !open.isEmpty() ? open.size() + " open case" + (open.size() == 1 ? "" : "s") + " against you"
            : Justice.convictions(me).isEmpty() ? "A clean record" : Justice.convictions(me).size() + " conviction" + (Justice.convictions(me).size() == 1 ? "" : "s") + " on record";
        LifePage page = new LifePage("record", "You and the law", sub, p.registryAccess());
        if (c.tab.isEmpty()) c.tab = Justice.NOTICES.containsKey(me) ? "notice" : !open.isEmpty() ? "case" : "record";
        for (String[] t : TABS) {
            if (t[0].equals("notice") && !Justice.NOTICES.containsKey(me)) continue;
            page.tab(t[0], t[1], c.tab.equals(t[0]));
        }
        TownHall.confirmBox(page, c);
        switch (c.tab) {
            case "notice" -> noticeTab(p, page);
            case "case" -> caseTab(p, level, v, open, page);
            case "jail" -> jailTab(p, level, v, page);
            case "how" -> howTab(p, page);
            default -> recordTab(p, page);
        }
        page.buttons().button("Show me the court", "show", LifePage.args("w", "court"), v != null, "No town near")
            .button("Show me the jail", "show", LifePage.args("w", "jail"), v != null, "No town near")
            .button("Show me the watch house", "show", LifePage.args("w", "watch"), v != null && WatchHouse.of(v.id()) != null,
                v == null ? "No town near" : "This town has no watch house yet");
        return page;
    }

    // ------------------------------------------------------------------ tabs

    private static void noticeTab(ServerPlayer p, LifePage page) {
        String[] n = Justice.NOTICES.get(p.getUUID());
        if (n == null) { page.text("Nothing new.", 4); return; }
        page.heading(n[0]);
        page.text(n[1], n[0].startsWith("Released") || n[0].startsWith("Not guilty") || n[0].contains("done") ? 1
            : n[0].contains("arrest") || n[0].startsWith("Wanted") || n[0].startsWith("Jail-break") || n[0].startsWith("Resisting") ? 3 : 2);
        page.heading("What's next");
        page.text(n[2], 0);
        page.buttons().button("Your case", "tab", LifePage.args("tab", "case")).primary().button("Got it", "seen", null);
    }

    private static void caseTab(ServerPlayer p, ServerLevel level, @Nullable Villages.Village v, List<JCase> open, LifePage page) {
        TownHall.hint(p, page, "law.case", "When the watch names you, the case comes here: the charge, what they have on you, and the court's date. "
            + "You choose your defence here too. Nothing is decided till the court sits.");
        long now = level.getGameTime();
        int shown = 0;
        for (JCase c : open) {
            if (c.joinedTo != 0) continue;
            shown++;
            page.heading(cap(c.chargeWords()) + " at " + c.place);
            String when = c.courtAt > now && (c.stage == Stage.SUMMONED || c.stage == Stage.REMAND) ? " The court sits in " + Justice.minutes(c.courtAt - now) + "." : "";
            page.text(cap(c.stage.words) + "." + when + " Day " + c.day + "; the case is the watch's" + (c.officerName.isEmpty() ? "." : ", " + c.officerName + " in charge."),
                c.stage == Stage.WARRANT ? 3 : 2);
            for (int j : c.joined) { JCase o = Justice.get(j); if (o != null) page.text("Tried with it: " + o.title() + ".", 4); }
            // What the watch has.
            StringBuilder has = new StringBuilder();
            for (JCase k : Assize.withJoined(c)) {
                for (Wit w : k.witnesses) if (w.reported && !w.lost) has.append(w.own ? w.name + " (saw it); " : w.name + " (heard it from " + w.from + "); ");
                for (Ev e : k.evidence) if (e.found) has.append(e.text).append("; ");
            }
            page.row(new ItemStack(Items.WRITABLE_BOOK), "What the watch has", has.length() == 0 ? "Little or nothing yet." : has.substring(0, has.length() - 2) + ".",
                String.format(Locale.ROOT, "%.1f", c.score()));
            // The defence.
            boolean closed = c.stage.ordinal() >= Stage.TRIAL.ordinal() && !c.defence.isEmpty();
            String chosen = c.plea.equals("guilty") ? "You will plead guilty." : c.defence.equals("lawyer") ? c.lawyerName + " will speak for you."
                : c.defence.equals("public") ? "The public defender will speak for you." : !c.account.isEmpty() ? "You'll tell the court: \"" + Assize.accountWords(c.account) + "\""
                : "You haven't chosen a defence yet. With none, the court hears only the watch.";
            page.heading("Your defence");
            page.text(chosen, c.defence.isEmpty() ? 2 : 0);
            String why = closed ? "The court has heard your defence" : "";
            boolean canPay = Market.coinsHeld(p) >= Assize.LAWYER_FEE;
            boolean poor = Market.coinsHeld(p) < Assize.LAWYER_FEE * 2;
            page.row(new ItemStack(Items.FEATHER), "Speak for yourself", "Your account is heard before the verdict.", "")
                .button(c.charge.killing() || c.charge == Charge.ASSAULT ? "It was an accident" : "It was a mistake", "account", LifePage.args("case", c.id, "d", "accident"), !closed, why)
                .button("I wasn't there", "account", LifePage.args("case", c.id, "d", "elsewhere"), !closed, why);
            page.buttons()
                .button("I was provoked", "account", LifePage.args("case", c.id, "d", "provoked"), !closed, why)
                .button("I ask for mercy", "account", LifePage.args("case", c.id, "d", "mercy"), !closed, why);
            page.row(new ItemStack(Items.WRITTEN_BOOK), "Have someone speak for you", "A lawyer (" + Assize.LAWYER_FEE + " coins) picks holes in hearsay and weak evidence; "
                    + "the public defender is free for those who can't pay.", "")
                .button("Hire a lawyer (" + Assize.LAWYER_FEE + "c)", "lawyer", LifePage.args("case", c.id), !closed && canPay && !c.defence.equals("lawyer"),
                    closed ? why : c.defence.equals("lawyer") ? "You have a lawyer" : "You have " + Market.coinsHeld(p) + " coins; a lawyer asks " + Assize.LAWYER_FEE)
                .button("Public defender", "public", LifePage.args("case", c.id), !closed && poor && !c.defence.equals("public"),
                    closed ? why : c.defence.equals("public") ? "You have the public defender" : "Only for those with under " + Assize.LAWYER_FEE * 2 + " coins");
            page.row(new ItemStack(Items.IRON_BARS), "Plead guilty", "A quarter off the sentence for owning up. It can't be taken back.", "")
                .button("Plead guilty", "plead", LifePage.args("case", c.id), !closed && !c.plea.equals("guilty"), closed ? why : "You've pleaded guilty");
            if (c.stage == Stage.WARRANT) {
                VillageFolkEntity g = v == null ? null : WatchHouse.nearestGuard(level, v, p.blockPosition(), 48.0, null);
                page.row(new ItemStack(Items.IRON_BARS), "Give yourself up", "To a guard: the court counts it in your favour.", "")
                    .button("Give myself up", "surrender", LifePage.args("case", c.id), g != null, "No guard within sight: find one, or go to the watch house");
            }
            if (!c.transcript.isEmpty()) {
                page.heading("In court");
                int from = Math.max(0, c.transcript.size() - 12);
                for (int i = from; i < c.transcript.size(); i++) page.text(c.transcript.get(i), 0);
            }
        }
        // Fines owed.
        for (JCase c : Justice.against(p.getUUID())) {
            if (c.stage != Stage.CONVICTED || c.fine <= 0 || c.finePaid) continue;
            shown++;
            boolean can = Market.coinsHeld(p) >= c.fine;
            page.row(new ItemStack(Items.GOLD_NUGGET), "Owed to the court: " + c.fine + " coins", "For " + c.title() + ". Due by day " + c.fineDue
                    + "; unpaid after that, it's a charge of its own.", "")
                .button("Pay " + c.fine + "c", "payfine", LifePage.args("case", c.id), can, "You have " + Market.coinsHeld(p) + " coins").primary();
        }
        if (shown == 0) page.text("No case is open against you. If the watch ever names you, it shows here first.", 4);
    }

    private static void jailTab(ServerPlayer p, ServerLevel level, @Nullable Villages.Village v, LifePage page) {
        TownHall.hint(p, page, "law.jail", "The jail is for those held for trial and those serving a sentence. Time is short in real life: minutes, never hours. "
            + "Time only counts while you're online (or slower while away), and your belongings come back when you leave.");
        CompoundTag t = Jail.custodyForTests(p.getUUID());
        if (!t.isEmpty() && !t.getBoolean("escaped")) {
            boolean remand = t.getBoolean("remand");
            Jail.Spot s = Jail.Spot.load(t.getCompound("spot"));
            page.heading(remand ? "Held for trial" : "Serving your sentence");
            page.text("Where: " + (s == null ? "custody" : s.words) + ". For: " + t.getString("reason") + ".", 2);
            if (!remand) {
                double left = t.getDouble("left"), total = Math.max(1, t.getDouble("total"));
                double day = JusticeConfig.jailDayMinutes() * 60.0;
                page.bar("Sentence", 1.0 - left / total, String.format(Locale.ROOT, "%.1f days left (%d min)", left / day, (int) Math.ceil(left / 60.0)));
            } else {
                JCase c = Justice.get(t.getInt("case"));
                if (c != null) page.text("The court sits in " + Justice.minutes(Math.max(0, c.courtAt - level.getGameTime())) + ". Your defence is on the Case tab.", 0);
            }
            int items = t.getList("items", Tag.TAG_COMPOUND).size();
            page.text("Your belongings: " + items + " stacks, kept at the desk till you leave. Meals come when you're hungry. Visitors may pass things through the bars "
                + Jail.HOURS + "; " + (Jail.visiting(level.getDayTime()) ? "it is visiting time now." : "it isn't visiting time now."), 0);
            page.text("Leaving any way but through the door with a guard is a jail-break: a crime of its own.", 3);
        } else if (t.getBoolean("escaped")) {
            page.heading("Escaped");
            page.text("You broke out. Your time waits for you, and the watch has a warrant out. Giving yourself up counts in your favour.", 3);
        } else {
            page.heading("Not in custody");
            page.text("You aren't held anywhere. Visiting a friend inside? Stand by their cell's bars at visiting hours (" + Jail.HOURS
                + ") and right-click the bars with what you'd pass them.", 0);
        }
        if (v != null) {
            String where = Jail.of(v.id()) != null ? "The town's jailhouse: three cells of iron, the guard's desk and the key."
                : WatchHouse.of(v.id()) != null ? "No jailhouse yet: prisoners are held in the watch house's cells."
                : TownHallDesk.hall(v.id()) != null ? "No jail yet: prisoners are held in a locked room at the Town Hall." : "No jail yet: prisoners are held by the stocks on the square.";
            page.text(where + (Jail.of(v.id()) == null ? " A jailhouse goes up once the town has a sworn constable or " + JusticeConfig.jailPopulation()
                + " folk, from the Iron Age." : ""), 4);
        }
    }

    private static void recordTab(ServerPlayer p, LifePage page) {
        TownHall.hint(p, page, "law.record", "Your record follows you: every conviction, every complaint. It costs you respect, some employers won't take you, "
            + "and lenders think twice. It never stops you living in town.");
        UUID me = p.getUUID();
        List<String> conv = Justice.convictions(me);
        CompoundTag r = Justice.record(me);
        int score = Justice.recordScore(me);
        page.heading("Your record");
        page.bar("Record", Math.min(1.0, score / 12.0), score == 0 ? "clean" : score > 6 ? "serious" : "minor");
        if (conv.isEmpty()) page.text("No convictions.", 1);
        for (int i = conv.size() - 1; i >= 0 && i >= conv.size() - 8; i--) {
            String[] x = conv.get(i).split("\\|");
            Charge ch = x.length > 1 ? Charge.named(x[1]) : null;
            page.row(new ItemStack(Items.PAPER), cap(ch == null ? x[1] : ch.word) + (x.length > 3 ? ", " + x[3] : ""), x.length > 2 ? cap(x[2]) + "." : "",
                "day " + x[0]);
        }
        int complaints = r.getInt("complaints"), forgiven = r.getInt("forgiven");
        page.text("Complaints outstanding: " + complaints + (complaints > 0 ? " (two make an assault charge)" : "") + ". Hurts forgiven after an apology: " + forgiven + ".", 4);
        int work = r.getInt("work");
        if (work > 0) page.row(new ItemStack(Items.BRUSH), "Community work: " + work + " sweeps left", "Right-click the ground in town with the watch's broom.", "");
        page.heading("What it means");
        page.text(score == 0 ? "A clean record: no doors are closed to you." : score > 6
            ? "A serious record: the town's respect is slow to come, the better employers turn you away, and the bank lends carefully."
            : "A minor record: it weighs a little on what the town thinks, and some employers ask about it.", score > 6 ? 3 : score > 0 ? 2 : 1);
    }

    private static void howTab(ServerPlayer p, LifePage page) {
        page.heading("How the law works");
        String[] lines = {
            "Nobody knows what you did unless somebody saw it, or the watch finds a clue. A witness goes to the watch when its nature lets it: honest folk soon, shy ones late.",
            "What a witness saw lives in its own head. If it dies before telling anyone, it's gone; what it told a neighbour survives as hearsay, weaker.",
            "Hurt a folk by accident? Talk to them and choose Apologise. Offering to pay the healer helps. Left alone, it's a complaint; two complaints are an assault.",
            "A killing always goes to court, which can find it an accident, manslaughter or murder.",
            "Lesser charges bring a summons with a date (it's on your planner). Graver ones bring a warrant: a guard walks to you and puts you in irons.",
            "In irons you're slow, can't fight or use things, and you're led on a lead to the jail. Pulling free is resisting arrest; running is a pursuit and a wanted notice.",
            "In custody your things are kept at the desk and returned when you leave. Meals come; visitors may pass things through the bars at visiting hours.",
            "At court: the charges, the watch's case, the witnesses, the evidence, your defence, the jury (players on the jury roll among them), the verdict and the sentence.",
            "Sentences: a fine, compensation, community work with the watch's broom, the stocks, days in the cells, banishment. Days are minutes of real play.",
            "Your record stays: it costs respect, some jobs and some credit." };
        for (String l : lines) page.text(l, 0);
        page.buttons().button("The Guide", "guide", null);
    }

    // ------------------------------------------------------------------ buttons

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        if (action.equals("confirm")) {
            if (!c.args.contains("ask")) return "!Nothing to confirm.";
            CompoundTag a = c.args.getCompound("ask");
            c.args.remove("ask");
            CompoundTag next = a.getCompound("args").copy();
            next.putBoolean("sure", true);
            return act(p, c, a.getString("a"), next);
        }
        if (action.equals("cancel")) { c.args.remove("ask"); return "Left as it was."; }
        c.args.remove("ask");
        Villages.Village v = PlayerLife.here(p);
        JCase k = args.contains("case") ? Justice.get(args.getInt("case")) : null;
        if (k != null && !k.accused.equals(p.getUUID())) return "!That isn't your case.";
        switch (action) {
            case "hint" -> { Belonging.dismissHint(p.getUUID(), args.getString("id")); return ""; }
            case "seen" -> { Justice.NOTICES.remove(p.getUUID()); c.tab = "case"; return ""; }
            case "guide" -> { return "The Guide (open it from the U page) has \"The law, the jail and the court\" under Defence & war."; }
            case "show" -> {
                if (v == null) return "!No town near.";
                String w = args.getString("w");
                BlockPos at;
                String label;
                if (w.equals("court")) { at = Assize.courtPlace(level, v); label = "The court (" + Assize.courtWords(level, v) + ")"; }
                else if (w.equals("watch")) { var b = WatchHouse.of(v.id()); at = b == null ? null : WatchHouse.at(b, WatchHouse.INSIDE); label = "The watch house"; }
                else {
                    var b = Jail.of(v.id());
                    if (b != null) { at = Jail.at(b, Jail.DESK); label = "The jailhouse"; }
                    else { Jail.Spot s = Jail.spot(level, v, p.getUUID()); at = s == null ? null : s.inside; label = s == null ? "The jail" : cap(s.words); }
                }
                return TownHall.showMe(p, at, label);
            }
            case "account" -> { return k == null ? "!No such case." : Assize.defend(p, k, "account", args.getString("d")); }
            case "lawyer" -> {
                if (k == null) return "!No such case.";
                if (!args.getBoolean("sure")) return TownHall.ask(c, "lawyer", args, "Pay " + Assize.LAWYER_FEE + " coins for a lawyer to speak for you? The fee isn't refunded, whatever the verdict.",
                    "Yes, hire them");
                return Assize.defend(p, k, "lawyer", "");
            }
            case "public" -> { return k == null ? "!No such case." : Assize.defend(p, k, "public", ""); }
            case "plead" -> {
                if (k == null) return "!No such case.";
                if (!args.getBoolean("sure")) return TownHall.ask(c, "plead", args, "Plead guilty to " + k.chargeWords() + "? You get a lighter sentence, but it can't be taken back.",
                    "Yes, plead guilty");
                return Assize.defend(p, k, "plead", "");
            }
            case "surrender" -> {
                if (v == null) return "!No town near.";
                VillageFolkEntity g = WatchHouse.nearestGuard(level, v, p.blockPosition(), 48.0, null);
                if (g == null) return "!No guard in sight. Find one, or go to the watch house.";
                String said = Arrests.surrender(g, p);
                return said == null ? "!There's no warrant out for you." : g.displayNameCap() + ": \"" + said + "\"";
            }
            case "payfine" -> {
                if (k == null || k.fine <= 0 || k.finePaid) return "!Nothing is owed on that.";
                int fee = k.fine;
                if (Market.coinsHeld(p) < fee) return "!You owe " + fee + " coins and have " + Market.coinsHeld(p) + ".";
                if (fee > 20 && !args.getBoolean("sure")) return TownHall.ask(c, "payfine", args, "Pay the court " + fee + " coins for " + k.title() + "?", "Yes, pay " + fee + "c");
                Market.payOut(p, fee);
                Ledger_addCoins(k.village, fee);
                k.finePaid = true;
                k.fine = 0;
                Justice.changed();
                return "Paid: " + fee + " coins to the court. Nothing more is owed for " + k.title() + ".";
            }
            default -> { return "!That button does nothing here."; }
        }
    }

    private static void Ledger_addCoins(UUID village, int coins) {
        com.jrpetty.mcassistant.village.Ledger.addCoins(village, coins);
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
