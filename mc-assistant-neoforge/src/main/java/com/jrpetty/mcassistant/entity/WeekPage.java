package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [featlife] The week page (PlayerLife "week"): the town's week laid out and what is on each day (the market, the fair,
 * the service, the clubs, a christening, a funeral), the businesses' hours (a player's own set here), the player's
 * friendships in the town and a dinner invitation's answer, the clubs to join, and what the chapel has coming. Every
 * button says what happened and what next; one that cannot be pressed says why; "Show me" puts the arrow on the place.
 * Opened by a word to any folk ("What's on this week?"), or from the life page.
 */
public final class WeekPage implements PlayerLife.Page {

    static final String[][] TABS = { { "week", "This week" }, { "hours", "Hours" }, { "friends", "Friends" }, { "clubs", "Clubs" }, { "events", "Events" } };

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        long day = level.getDayTime() / 24000L;
        if (v == null) {
            LifePage page = new LifePage("week", "The week", Calendar.name(day) + ": no town near", p.registryAccess());
            page.text("The week's markets, fairs, services and clubs are a town's. Walk into one and ask anybody what's on.", 4);
            return page;
        }
        LifePage page = new LifePage("week", "The week in " + Villages.name(v.id()),
            Calendar.name(day) + ", " + Calendar.what(day) + "  ·  week " + Calendar.weekOfMonth(day) + " of the month  ·  the fair "
                + Calendar.when(day, day + Calendar.daysToFair(day)), p.registryAccess());
        if (c.tab.isEmpty()) c.tab = "week";
        for (String[] t : TABS) page.tab(t[0], t[1], c.tab.equals(t[0]));
        TownHall.hint(p, page, "life.week", "Monday to Friday the town works; Saturday is market day, most works shut and folk spend their own money in town; "
            + "Sunday is the day of rest, with the service in the morning. The fourth Saturday of the month is the fair.");
        switch (c.tab) {
            case "hours" -> hours(p, level, v, page, day);
            case "friends" -> friends(p, level, v, page, day);
            case "clubs" -> clubs(p, level, v, page, day);
            case "events" -> events(p, level, v, page, day);
            default -> week(p, level, v, page, day);
        }
        page.buttons().button("Your life in town", "life", null).button("Show me the square", "show", LifePage.args("what", "square"));
        return page;
    }

    // ------------------------------------------------------------------ tabs

    void week(ServerPlayer p, ServerLevel level, Villages.Village v, LifePage page, long day) {
        page.heading("The next seven days");
        for (int i = 0; i < 7; i++) {
            long d = day + i;
            List<String> on = new ArrayList<>();
            on.add(Calendar.isWeekend(d) ? (Calendar.isMarketDay(d) ? "Market day: most works shut, stalls in the square" : "The day of rest: the service, the games, the football")
                : "A working day: " + WorkHours.town(v.id()).words() + " for the town's works");
            if (Calendar.isFairDay(d)) on.add("THE FAIR: traders in from round about");
            for (Clubs.Club cl : Clubs.Club.values()) if (cl.day == Calendar.day(d) && Clubs.whyNot(level, v.id(), cl) == null) on.add(cl.title + " " + WorkHours.hour(cl.from) + ":00");
            if (Calendar.day(d) == Calendar.Day.WEDNESDAY && Clubs.whyNot(level, v.id(), Clubs.Club.SPORTS) == null) on.add("Sports training 18:00");
            Friends.Invite inv = Friends.inviteFor(p.getUUID());
            if (inv != null && inv.day == d) on.add("Dinner at " + inv.hostName + "'s, noon (" + inv.status + ")");
            ItemStack icon = new ItemStack(Calendar.isFairDay(d) ? Items.LANTERN : Calendar.isMarketDay(d) ? Items.BARREL : Calendar.isRestDay(d) ? Items.BELL : Items.IRON_PICKAXE);
            LifePage.Row row = page.row(icon, Calendar.name(d) + (i == 0 ? " (today)" : ""), String.join(" · ", on), Calendar.when(day, d));
            if (Calendar.isFairDay(d) || Calendar.isMarketDay(d)) row.button("Show me", "show", LifePage.args("what", "square"));
            if (Calendar.isRestDay(d)) row.button("Show me", "show", LifePage.args("what", "chapel"));
        }
        List<String> stalls = Stalls.lines(v.id());
        page.heading("In the square now");
        if (stalls.isEmpty()) page.text(Calendar.isMarketDay(day) ? "No stalls up yet. Folk with something spare set up on Saturday morning."
            : "No stalls today. They go up on Saturday morning, and on fair day there are traders from the next towns.", 4);
        for (String s : stalls) page.row(new ItemStack(Items.BARREL), s, "", "").button("Show me", "show", LifePage.args("what", "square"));
        String score = Clubs.score(v.id());
        if (score != null) page.row(new ItemStack(Items.LEATHER), "Match on the green", score, "now").button("Show me", "show", LifePage.args("what", "green"));
        else if (Clubs.lastResult(v.id()) != null) page.text("Last match: " + Clubs.lastResult(v.id()) + ".", 4);
    }

    void hours(ServerPlayer p, ServerLevel level, Villages.Village v, LifePage page, long day) {
        page.text("Each employer keeps its own days and hours, and its hands work them: on a day it is shut they have the day to themselves. "
            + "A folk owner sets them by its character; the council sets the town's works'; you set your own business's here.", 4);
        Business.Hours town = WorkHours.town(v.id());
        page.row(new ItemStack(Items.BELL), "The town's works", town.words(), town.why());
        String editing = PlayerLife.ctx(p).args.getString("edit");
        for (Business b : Businesses.all(v.id())) {
            if (!b.open || b.mirror()) continue;
            Business.Hours h = WorkHours.hoursOf(v.id(), b);
            boolean mine = b.ownedBy(p.getUUID());
            LifePage.Row row = page.row(new ItemStack(mine ? Items.WRITABLE_BOOK : Items.PAPER), b.name, h.words(), h.why());
            row.button("Change hours", "edit", LifePage.args("id", b.id), mine, mine ? "" : "Only its owner sets its hours: this one is " + b.ownersWords() + "'s.");
            if (mine && b.id.equals(editing)) {
                page.choice("days", "Open", new String[]{ "31", "63", "127", "15" }, new String[]{ "Mon–Fri", "Mon–Sat", "Every day", "Mon–Thu" },
                    Integer.toString(h.days()));
                page.choice("open", "Opens", new String[]{ "6", "7", "8", "9" }, new String[]{ "6:00", "7:00", "8:00", "9:00" }, Integer.toString(h.open()));
                page.choice("close", "Shuts", new String[]{ "16", "17", "18", "20", "22" }, new String[]{ "16:00", "17:00", "18:00", "20:00", "22:00" },
                    Integer.toString(h.close()));
                page.buttons().button("Save these hours", "hours", LifePage.args("id", b.id)).primary().button("Cancel", "edit", LifePage.args("id", ""));
            }
        }
    }

    void friends(ServerPlayer p, ServerLevel level, Villages.Village v, LifePage page, long day) {
        Friends.Invite inv = Friends.inviteFor(p.getUUID());
        if (inv != null && inv.village.equals(v.id())) {
            page.heading("An invitation");
            boolean open = "asked".equals(inv.status);
            LifePage.Row row = page.row(new ItemStack(Items.CAKE), "Dinner at " + inv.hostName + "'s", Calendar.name(inv.day) + " at noon, at their house"
                + (open ? ". They're waiting for your answer." : " (you said " + inv.status + ")"), Calendar.when(day, inv.day));
            row.button("I'd love to come", "yes", null, open, open ? "" : "You've answered already.");
            row.button("Sorry, I can't", "no", null, open, open ? "" : "You've answered already.");
            row.button("Show me", "show", LifePage.args("what", "host"));
        }
        page.heading("Your friends here");
        List<Object[]> rows = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || Visitors.is(f)) continue;
            int aff = f.persona().affinity(p.getUUID());
            if (aff < Friends.ACQUAINTANCE) continue;
            rows.add(new Object[]{ f, aff });
        }
        rows.sort((x, y) -> Integer.compare((Integer) y[1], (Integer) x[1]));
        if (rows.isEmpty()) page.text("Nobody here knows you well yet. Talk to folk, help them, join a club, go to dinner when you're asked.", 4);
        for (Object[] r : rows.subList(0, Math.min(14, rows.size()))) {
            VillageFolkEntity f = (VillageFolkEntity) r[0];
            Friends.Level lv = Friends.playerLevel(f, p.getUUID());
            page.row(new ItemStack(lv == Friends.Level.BEST ? Items.DIAMOND : lv == Friends.Level.CLOSE ? Items.GOLD_INGOT : lv == Friends.Level.FRIEND ? Items.IRON_INGOT : Items.COPPER_INGOT),
                f.displayNameCap(), FolkTalk.cap(lv.words) + "; " + (f.stationTask() == AssistantEntity.StationTask.NONE ? "no trade" : f.stationTask().title.toLowerCase()),
                r[1] + "/100");
        }
        page.text("Friendship grows with talk, gifts, help, a dinner and a club night. Folk who like you well ask you round on a weekend.", 4);
    }

    void clubs(ServerPlayer p, ServerLevel level, Villages.Village v, LifePage page, long day) {
        page.text("Members join by what they are like; you join here. Meetings are at weekends and in the evenings; turning up makes friends.", 4);
        for (Map.Entry<Clubs.Club, String> e : Clubs.summary(level, v.id()).entrySet()) {
            Clubs.Club cl = e.getKey();
            boolean in = Clubs.playerIn(v.id(), cl, p.getUUID());
            LifePage.Row row = page.row(new ItemStack(switch (cl) {
                case FISHING -> Items.FISHING_ROD;
                case CHOIR -> Items.NOTE_BLOCK;
                case QUIZ -> Items.BOOK;
                case SPORTS -> Items.LEATHER;
                case RAMBLERS -> Items.LEATHER_BOOTS;
            }), cl.title + (in ? " (you're a member)" : ""), e.getValue(), "next " + Calendar.when(day, Clubs.nextMeeting(cl, day)));
            if (in) row.button("Leave", "leave", LifePage.args("club", cl.name()));
            else row.button("Join", "join", LifePage.args("club", cl.name()));
            BlockPos at = Clubs.place(level, v, cl);
            row.button("Show me", "show", LifePage.args("what", "club", "club", cl.name()), at != null, at == null ? "It has nowhere to meet yet." : "");
        }
    }

    void events(ServerPlayer p, ServerLevel level, Villages.Village v, LifePage page, long day) {
        List<String> on = new ArrayList<>(Church.coming(v.id(), day));
        int fair = Calendar.daysToFair(day);
        on.add("The fair, " + Calendar.when(day, day + fair) + ": " + Fairground.traders(v.id()) + (Fairground.traders(v.id()) == 1 ? " trader" : " traders")
            + " expected from round about");
        on.add("The Sunday service, " + Calendar.when(day, day + Calendar.daysTo(day, Calendar.Day.SUNDAY)) + " morning"
            + (Villages.builtAt(v.id(), "chapel") != null ? " at the chapel" : " by the well"));
        for (String s : on) page.row(new ItemStack(s.startsWith("The funeral") ? Items.CANDLE : s.contains("christening") ? Items.WATER_BUCKET
            : s.startsWith("The fair") ? Items.LANTERN : Items.BELL), s, "", "").button("Show me", "show",
            LifePage.args("what", s.startsWith("The fair") ? "square" : "chapel"));
        List<Planner.Entry> mine = Planner.of(p.getUUID(), day);
        if (!mine.isEmpty()) {
            page.heading("On your planner");
            for (Planner.Entry e : mine.subList(0, Math.min(8, mine.size()))) {
                page.row(new ItemStack(Items.CLOCK), e.text(), e.kind(), Calendar.when(day, e.day()) + " " + WorkHours.hour(e.time()) + ":00");
            }
        }
    }

    // ------------------------------------------------------------------ buttons

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!There's no town near. Walk into one first.";
        switch (action) {
            case "hint" -> { Belonging.dismissHint(p.getUUID(), args.getString("id")); return ""; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            case "show" -> {
                String what = args.getString("what");
                BlockPos at;
                String label;
                switch (what) {
                    case "chapel" -> { at = Church.font(level, v.id()); label = Villages.builtAt(v.id(), "chapel") != null ? "The chapel" : "The well"; }
                    case "green" -> { at = Clubs.place(level, v, Clubs.Club.SPORTS); label = "The green"; }
                    case "club" -> {
                        Clubs.Club cl = club(args);
                        at = cl == null ? null : Clubs.place(level, v, cl);
                        label = cl == null ? "The club" : FolkTalk.cap(cl.words) + "'s meeting place";
                    }
                    case "host" -> {
                        Friends.Invite inv = Friends.inviteFor(p.getUUID());
                        VillageFolkEntity host = inv == null ? null : level.getEntity(inv.host) instanceof VillageFolkEntity h ? h : null;
                        at = host == null ? null : Homes.homeOf(host) != null ? Homes.homeOf(host) : host.blockPosition();
                        label = inv == null ? "The house" : inv.hostName + "'s house";
                    }
                    default -> { at = v.centre(); label = "The square"; }
                }
                return TownHall.showMe(p, at, label);
            }
            case "join", "leave" -> {
                Clubs.Club cl = club(args);
                if (cl == null) return "!That club isn't one of the town's.";
                return action.equals("join") ? Clubs.join(p, v.id(), cl) : Clubs.leave(p, v.id(), cl);
            }
            case "yes", "no" -> { return Friends.answer(p, action.equals("yes")); }
            case "edit" -> {
                c.args.putString("edit", args.getString("id"));
                return args.getString("id").isEmpty() ? "Left the hours as they were." : "Choose its days and hours, then Save.";
            }
            case "hours" -> {
                Business b = Businesses.of(v.id(), args.getString("id"));
                if (b == null) return "!That business has closed.";
                if (!b.ownedBy(p.getUUID())) return "!Only its owner sets its hours: this one is " + b.ownersWords() + "'s.";
                CompoundTag f = args.getCompound("f");
                int days = parse(f.getString("days"), b.hours().days()), open = parse(f.getString("open"), b.hours().open()),
                    close = parse(f.getString("close"), b.hours().close());
                if (close <= open) return "!It has to shut after it opens. Pick a later closing hour.";
                b.setHours(new Business.Hours(days, open, close, "set by " + p.getGameProfile().getName()));
                Businesses.dirty();
                c.args.remove("edit");
                return b.name + " now keeps " + b.hours().words() + ". Its hands work those hours from tomorrow; on its shut days they're free.";
            }
            default -> { return "!That button does nothing yet."; }
        }
    }

    @Nullable
    static Clubs.Club club(CompoundTag args) {
        try { return Clubs.Club.valueOf(args.getString("club")); } catch (IllegalArgumentException e) { return null; }
    }

    static int parse(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return dflt; }
    }

    // ------------------------------------------------------------------ said to a folk

    /** "What's on this week?" and the club and dinner words: the page opened, and a word back. Null for anything else. */
    @Nullable
    public static String heard(VillageFolkEntity f, ServerPlayer p, String t) {
        if (t.contains("what's on this week") || t.contains("whats on this week") || t.contains("the week's events") || t.contains("market day")
                && t.contains("when")) {
            PlayerLife.open(p, "week", LifePage.args("tab", "week"));
            long day = p.level().getDayTime() / 24000L;
            return "It's " + Calendar.name(day) + ". " + (Calendar.isMarketDay(day) ? "Market day: get yourself to the square."
                : "Market's " + Calendar.when(day, day + Calendar.daysTo(day, Calendar.Day.SATURDAY)) + ", the fair " + Calendar.when(day, day + Calendar.daysToFair(day)) + ".")
                + " The rest is on the page.";
        }
        if (t.contains("join your club") || t.contains("join the club") || t.contains("clubs in town")) {
            PlayerLife.open(p, "week", LifePage.args("tab", "clubs"));
            List<Clubs.Club> mine = Clubs.clubsOf(f);
            if (!mine.isEmpty() && f.ownerId() != null && t.contains("join")) {
                String said = Clubs.join(p, f.ownerId(), mine.get(0));
                return (said.startsWith("!") ? said.substring(1) : FolkTalk.pick(f.getRandom(), "Glad to have you.", "We could use another.",
                    "You'll want boots.", "Don't be late; we start without anybody.", "First meeting's free. So's the rest.", "Welcome aboard.") + " " + said);
            }
            return mine.isEmpty() ? "I'm not one for clubs. The page has them all." : "I'm in " + mine.get(0).words + ". The page has the rest.";
        }
        if (t.contains("my friends here") || t.contains("who likes me")) {
            PlayerLife.open(p, "week", LifePage.args("tab", "friends"));
            return "Here's who thinks what of you. Roughly.";
        }
        return null;
    }
}
