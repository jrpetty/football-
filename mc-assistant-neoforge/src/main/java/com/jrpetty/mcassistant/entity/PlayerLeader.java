package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [player-civic] A player who leads: elected (Hustings), the town's leader in name.
 *
 * <p><b>The powers.</b> The town's own folk still run its days (its leader's nature is the steward's: Leader), but
 * the player sets its direction, from the Leader's page (/village leader) or by command:
 * <ul>
 * <li>the plan: food first, growth (timber in the Wood Age, the mines after), defence, trade, steady, or left to
 *     the steward (Orders: the order stands as the leader gave it, renewed every three days);</li>
 * <li>the next building, out of what the town would build anyway (Villages.request);</li>
 * <li>the tithe, from none to one coin in five (Market.tithe), and the wages, from eighty-five to a hundred and
 *     twenty in the hundred (Leader.usualPay);</li>
 * <li>envoys: when one comes, the leader is told, and its yes or no is the town's answer at the board (Envoys);</li>
 * <li>a referendum: a question put to the whole town. The town-wide votes on great works are another hand's
 *     (REFERENDUMS, the seam); until they are wired in, a show of hands at the board, each folk by what it cares for.</li>
 * </ul>
 * <b>The steward.</b> The one the town thinks most of after the leader speaks for it at the gatherings when the
 * leader is not there to (Assemblies), and runs the orders when the leader leaves them to it; in a famine it calls the
 * town to the fields whatever the plan.
 *
 * <p><b>Promises kept.</b> Every morning the town looks at the promises (Pledges): kept, they raise the leader's
 * approval and the town's opinion of it; broken, they lower both, folk grumble (in passing, and to each other), and
 * the gazette and the chronicle say so. Approval drifts toward how content the town is, and down a little for a
 * leader never seen in town. Under thirty, the town calls a recall vote the next morning: carried, the leader is out
 * and an election follows in two days. And at the next election the record is judged (Hustings.lean).
 */
public final class PlayerLeader {

    private PlayerLeader() {}

    /** Approval under this calls a recall vote. */
    public static final int RECALL_AT = 30;
    public static final int TITHE_LEAST = 0, TITHE_MOST = 20, WAGES_LEAST = 85, WAGES_MOST = 120;

    /** The town-wide vote: whoever builds the referendums sets this, and the leader's question goes to it. */
    @FunctionalInterface
    public interface Referendums {
        /** Put the question to the town; what to tell the leader, or null to fall back on a show of hands. */
        @Nullable String call(ServerLevel level, Villages.Village v, UUID caller, String question);
    }

    /** The seam for the town-wide votes: a great work named is put to the whole town (entity/Referendums.leaderCalls). */
    public static volatile Referendums REFERENDUMS = com.jrpetty.mcassistant.entity.Referendums::leaderCalls;

    /** The envoys a leader has been told of (once each). */
    private static final java.util.Set<UUID> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ who leads

    /** The player who leads this town, if a player does (while the town's elder is that player). */
    @Nullable
    public static UUID leaderId(UUID village) {
        String s = Ledger.note(village, "civic.leader");
        if (s == null || s.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(s.split("\\|", 2)[0]);
            return id.equals(Villages.elder(village)) || Villages.elder(village) == null ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The player written down as holding office, whoever the town's elder is this moment (an election just counted). */
    @Nullable
    static UUID officeHolder(UUID village) {
        String s = Ledger.note(village, "civic.leader");
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s.split("\\|", 2)[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean leads(UUID village, UUID player) {
        return player.equals(leaderId(village));
    }

    public static String leaderName(UUID village) {
        String s = Ledger.note(village, "civic.leader");
        if (s == null || s.isEmpty()) return "";
        String[] p = s.split("\\|");
        return p.length > 1 ? p[1] : "";
    }

    static long since(UUID village) {
        String s = Ledger.note(village, "civic.leader");
        if (s == null || s.isEmpty()) return -1;
        String[] p = s.split("\\|");
        try {
            return p.length > 2 ? Long.parseLong(p[2]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The leader of any town the player leads, or null. */
    @Nullable
    public static UUID townLedBy(UUID player) {
        for (Villages.Village v : Villages.every()) if (leads(v.id(), player)) return v.id();
        return null;
    }

    /** "Thane Steve". */
    static String styled(UUID village) {
        String t = Homeland.leaderTitle(village);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1) + " " + leaderName(village);
    }

    // ------------------------------------------------------------------ taking office and leaving it

    /** Elected (Hustings.installed): office, the promises written down with their deadlines, a starting approval, the steward. */
    static void take(ServerLevel level, Villages.Village v, UUID player, String name, List<Pledges.Pledge> pledges, long day) {
        UUID id = v.id();
        boolean again = leads(id, player);
        Ledger.note(id, "civic.leader", player + "|" + name + "|" + (again ? since(id) : day));
        List<Pledges.Promise> list = new ArrayList<>();
        for (Pledges.Pledge pl : pledges) list.add(Pledges.start(level, id, pl, day));
        Ledger.note(id, "civic.promises", Pledges.save(list));
        int standing = Standing.of(id, player, level.getGameTime()).score();
        if (!again) {
            approval(id, Math.max(35, Math.min(80, 45 + standing / 2)));
            Ledger.note(id, "civic.plan", "steward");
        }
        Ledger.note(id, "civic.recall", "");
        Ledger.note(id, "civic.judged", "");
        VillageFolkEntity steward = chooseSteward(id, player);
        if (steward != null) Ledger.note(id, "civic.steward", steward.getUUID() + "|" + steward.displayNameCap());
        Player p = level.getPlayerByUUID(player);
        if (p instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal((again ? "Re-elected" : "Elected") + " " + Homeland.leaderTitle(id) + " of " + Villages.name(id)
                + "! " + (pledges.isEmpty() ? "" : "You promised " + Hustings.nouns(pledges) + ": the town will hold you to it. ")
                + "Open your Leader's page with /village leader.").withStyle(ChatFormatting.GOLD));
            Advancements.grant(sp, "village/elected");
            Citizens.refresh(sp);
        }
    }

    /** The term is over (lost, or recalled): the town's rates go back to its own, and the record is kept. */
    static void leave(ServerLevel level, Villages.Village v, long day, String why) {
        UUID id = v.id();
        UUID was = officeHolder(id);                                   // (the town may have its new elder already)
        String name = leaderName(id);
        Ledger.note(id, "civic.leader", "");
        Ledger.note(id, "civic.promises", "");
        Ledger.note(id, "civic.tithe", "");
        Ledger.note(id, "civic.wages", "");
        Ledger.note(id, "civic.plan", "");
        Ledger.note(id, "civic.recall", "");
        if (was != null) {
            Player p = level.getPlayerByUUID(was);
            if (p != null) {
                p.sendSystemMessage(Component.literal("You are no longer " + Homeland.leaderTitle(id) + " of " + Villages.name(id) + ": you " + why + ".")
                    .withStyle(ChatFormatting.GOLD));
                if (p instanceof ServerPlayer sp) Citizens.refresh(sp);
            }
            if (!name.isEmpty()) Villages.tell(id, day, name + "'s time as " + Homeland.leaderTitle(id) + " is over: they " + why);
        }
    }

    // ------------------------------------------------------------------ the steward

    /** The one the town thinks most of after the leader: the council's first member who is not the leader. */
    @Nullable
    static VillageFolkEntity chooseSteward(UUID village, UUID leader) {
        for (VillageFolkEntity f : Council.members(village)) if (!f.getUUID().equals(leader) && !f.isBaby()) return f;
        return null;
    }

    @Nullable
    static VillageFolkEntity steward(UUID village) {
        String s = Ledger.note(village, "civic.steward");
        if (s != null && !s.isEmpty()) {
            try {
                VillageFolkEntity f = Elections.loaded(village, UUID.fromString(s.split("\\|", 2)[0]));
                if (f != null && f.isAlive()) return f;
            } catch (RuntimeException ignored) { }
        }
        UUID leader = leaderId(village);
        VillageFolkEntity f = leader == null ? null : chooseSteward(village, leader);
        if (f != null) Ledger.note(village, "civic.steward", f.getUUID() + "|" + f.displayNameCap());
        return f;
    }

    /** Who leads a gathering (Assemblies): the steward speaks for a player who leads. */
    @Nullable
    public static UUID host(UUID village, @Nullable UUID host) {
        if (host == null || !host.equals(leaderId(village))) return host;
        VillageFolkEntity s = steward(village);
        return s == null ? null : s.getUUID();
    }

    /**
     * The orders, every three days (Orders.consider), in a town a player leads: the leader's own plan renewed (null:
     * nothing for the town's own leader to decide), or the steward to choose them, when it is left to the steward.
     */
    @Nullable
    public static VillageFolkEntity steward(ServerLevel level, UUID village, long day) {
        if (leaderId(village) == null) return null;
        String plan = Ledger.note(village, "civic.plan");
        Orders.Order o = orderFor(village, plan);
        if (o != null) {
            if (Orders.possible(village, o)) Orders.give(village, o, day, styled(village));
            return null;
        }
        return steward(village);
    }

    /** What a plan comes to in orders: food first, growth, defence, trade, steady; null for the steward's choosing. */
    @Nullable
    static Orders.Order orderFor(UUID village, @Nullable String plan) {
        if (plan == null) return null;
        return switch (plan) {
            case "food" -> Orders.Order.LARDER;
            case "growth" -> Villages.ageOf(village) == Villages.Age.WOOD ? Orders.Order.TIMBER : Orders.Order.DIG;
            case "defence" -> Orders.Order.WATCH;
            case "trade" -> Orders.Order.MARKET;
            case "steady" -> Orders.Order.STEADY;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ the rates

    /** The tithe, in the hundred: what the leader set, or the town's own (one coin in ten). */
    public static int titheRate(UUID village) {
        String s = Ledger.note(village, "civic.tithe");
        if (s == null || s.isEmpty() || leaderId(village) == null) return Pledges.TITHE_USUAL;
        try {
            return Math.max(TITHE_LEAST, Math.min(TITHE_MOST, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return Pledges.TITHE_USUAL;
        }
    }

    /** A folk's tithe on what it holds over a dozen (Market.tithe): at the leader's rate, where a player leads. */
    public static int tithe(UUID village, int over, int due) {
        if (leaderId(village) == null) return due;
        return over <= 0 ? 0 : over * titheRate(village) / 100;
    }

    /** The wages the leader set, in the hundred, or null (Leader.usualPay: the leader's own nature sets them). */
    @Nullable
    public static Integer wages(@Nullable UUID village) {
        if (village == null || leaderId(village) == null) return null;
        String s = Ledger.note(village, "civic.wages");
        if (s == null || s.isEmpty()) return null;
        try {
            return Math.max(WAGES_LEAST, Math.min(WAGES_MOST, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int wageRate(UUID village) {
        Integer w = wages(village);
        return w == null ? Leader.payRate(village) : w;
    }

    // ------------------------------------------------------------------ the powers

    /** The town a player leads, near enough to rule from; null (and the reason said) if none. */
    @Nullable
    static Villages.Village ruled(ServerLevel level, Player p, StringBuilder why) {
        UUID id = townLedBy(p.getUUID());
        if (id == null) {
            why.append("You don't lead a town. Stand at its next election.");
            return null;
        }
        Villages.Village v = Villages.get(id);
        if (v == null) why.append("Your town is nowhere to be found.");
        return v;
    }

    public static String plan(ServerLevel level, Player p, String which) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        UUID id = v.id();
        String plan = which.toLowerCase(Locale.ROOT);
        long day = Civics.day(level);
        if (plan.equals("steward")) {
            Ledger.note(id, "civic.plan", "steward");
            VillageFolkEntity s = steward(id);
            if (s != null) Orders.consider(level, id, day, true);
            Villages.tell(id, day, styled(id) + " left the town's plan to " + (s == null ? "the steward" : s.displayNameCap()));
            return "The plan is the steward's to make" + (s == null ? "." : ": " + s.displayNameCap() + " will see to it.");
        }
        Orders.Order o = orderFor(id, plan);
        if (o == null) return "Food first, growth, defence, trade, steady — or leave it to the steward.";
        if (!Orders.possible(id, o)) return "The town has nobody for that yet: it's too small for " + o.title.toLowerCase(Locale.ROOT) + ".";
        Ledger.note(id, "civic.plan", plan);
        Orders.give(id, o, day, styled(id));
        Villages.tell(id, day, styled(id) + " ordered: " + o.title.toLowerCase(Locale.ROOT));
        Market.assemblyNews(id, styled(id) + "'s orders: " + o.title + ". " + o.words);
        Quests.paint(level, v);
        return "The plan is set: " + o.title + ". " + o.words;
    }

    public static String build(ServerLevel level, Player p, String what) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        UUID id = v.id();
        String want = what.toLowerCase(Locale.ROOT).trim();
        List<String> list = Villages.projectsWanted(id);
        String found = null;
        for (String s : list) if (s.equals(want) || Villages.spoken(s).toLowerCase(Locale.ROOT).contains(want)) { found = s; break; }
        if (found == null) {
            List<String> words = new ArrayList<>();
            for (String s : list) { words.add(s); if (words.size() >= 6) break; }
            return "The town would build none of that just now. What it wants: " + String.join(", ", words) + ".";
        }
        Villages.request(id, found);
        long day = Civics.day(level);
        Ledger.note(id, "civic.build", found + "|" + day);
        Villages.tell(id, day, styled(id) + " chose " + Villages.spoken(found) + " to go up next");
        return "Next to go up: " + Villages.spoken(found) + ". The builders will start on it as soon as they're free.";
    }

    public static String tithe(ServerLevel level, Player p, int pct) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        int set = Math.max(TITHE_LEAST, Math.min(TITHE_MOST, pct));
        UUID id = v.id();
        int was = titheRate(id);
        Ledger.note(id, "civic.tithe", Integer.toString(set));
        long day = Civics.day(level);
        if (set != was) {
            Villages.tell(id, day, styled(id) + " set the tithe at " + set + " in the hundred (it was " + was + ")");
            Market.assemblyNews(id, set < was ? "The tithe is lowered: " + set + " in the hundred from now on." : "The tithe goes up to " + set + " in the hundred.");
            // The folk feel it in their purses: a Merchant most of all.
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                    int d = (was - set) / 3 + (Values.top(f) == Values.Value.WEALTH ? Integer.signum(was - set) * 2 : 0);
                    if (d != 0) f.persona().feelFor(p.getUUID(), p.getName().getString(), Math.max(-6, Math.min(6, d)));
                }
            }
            Standing.stir(id, p.getUUID());
        }
        return "The tithe is " + set + " in the hundred of what each folk holds over a dozen" + (set != was ? " (it was " + was + ")." : ".");
    }

    public static String wages(ServerLevel level, Player p, int pct) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        int set = Math.max(WAGES_LEAST, Math.min(WAGES_MOST, pct));
        UUID id = v.id();
        int was = wageRate(id);
        Ledger.note(id, "civic.wages", Integer.toString(set));
        long day = Civics.day(level);
        if (set != was) {
            Villages.tell(id, day, styled(id) + " set the wages at " + set + " in the hundred (they were " + was + ")");
            Market.assemblyNews(id, set > was ? "Wages up: " + set + " in the hundred, from tomorrow." : "Wages down to " + set + " in the hundred. Belts in.");
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                    int d = (set - was) / 4 + (Values.top(f) == Values.Value.WEALTH ? Integer.signum(set - was) * 2 : 0);
                    if (d != 0) f.persona().feelFor(p.getUUID(), p.getName().getString(), Math.max(-6, Math.min(6, d)));
                }
            }
            Standing.stir(id, p.getUUID());
        }
        int coins = Ledger.coins(id), bill = Market.wageBill(id);
        return "Wages are " + set + " in the hundred from tomorrow's payday. The treasury holds " + coins + "; a day's wages at the standard rate are about "
            + bill + "." + (coins < bill * set / 100 ? " It can't pay that for long." : "");
    }

    /** The leader's word on the envoy waiting: the town gives that answer at the board. */
    public static String envoy(ServerLevel level, Player p, boolean yes) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        VillageFolkEntity e = Envoys.waitingAt(level, v);
        if (e == null || e.trip() == null || e.trip().errand == null) return "No envoy is waiting to be heard.";
        Caravans.Trip t = e.trip();
        if (t.errand == Envoys.Errand.TRADE || t.errand == Envoys.Errand.WAR) {
            return "An envoy " + t.errand.purpose + " is the council's business: the steward will bargain at the board.";
        }
        Ledger.note(v.id(), "civic.envoy." + e.getUUID(), yes ? "yes" : "no");
        return "Your answer to " + e.displayNameCap() + " of " + Villages.name(t.from) + " (" + t.errand.purpose + ") is " + (yes ? "yes" : "no")
            + ". It is given at the board when the town hears the envoy.";
    }

    /** The envoy's answer, as the player who leads gave it (Envoys.answer); null if it gave none. */
    @Nullable
    static Envoys.Answer ruling(ServerLevel level, UUID host, UUID from, Envoys.Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        if (leaderId(host) == null) return null;
        String r = Ledger.note(host, "civic.envoy." + envoy.getUUID());
        if (r == null || r.isEmpty()) return null;
        Ledger.forget(host, "civic.envoy." + envoy.getUUID());
        boolean yes = r.equals("yes");
        long day = Civics.day(level);
        String who = styled(host), there = Villages.name(from);
        return switch (errand) {
            case GREETING -> yes
                ? new Envoys.Answer(true, who + " bids you welcome. Tell " + there + " they're always welcome here.", () -> {
                    Ledger.relate(host, from, 6);
                    t.outcome = "the town's leader made me very welcome";
                })
                : new Envoys.Answer(false, who + " thanks you, and sends you home.", () -> {
                    Ledger.relate(host, from, -2);
                    t.outcome = "their leader sent me home without a word of welcome";
                });
            case ALLIANCE -> yes
                ? new Envoys.Answer(true, who + " accepts: " + Villages.name(host) + " and " + there + " are allies.", () -> {
                    Envoys.swear(host, from, day);
                    Diplomacy.announce(host, from, Ledger.relation(host, from), day);
                    Villages.tell(host, day, Villages.name(host) + " and " + there + " swore an alliance, on " + who + "'s word");
                    t.outcome = "we are allies now";
                })
                : new Envoys.Answer(false, who + " says: friends, but no alliance.", () -> {
                    Ledger.relate(host, from, 1);
                    t.outcome = "their leader wants no alliance yet";
                });
            case PEACE -> yes
                ? new Envoys.Answer(true, who + " accepts your gifts: let it be forgotten. Peace.", () -> {
                    Envoys.unloadGifts(level, envoy, host);
                    if (t.purse > 0) { Ledger.addCoins(host, t.purse); t.purse = 0; }
                    Ledger.relate(host, from, 25);
                    Diplomacy.announce(host, from, Ledger.relation(host, from), day);
                    t.outcome = "their leader accepted our gifts and made peace";
                })
                : new Envoys.Answer(false, who + " will not make peace. Take your gifts home.", () -> {
                    Ledger.relate(host, from, -3);
                    t.outcome = "their leader sent our gifts back";
                });
            case TRIBUTE -> {
                int want = Diplomacy.tributeAsked(from);
                if (yes && Ledger.coins(host) >= want) {
                    yield new Envoys.Answer(true, who + " pays it, and won't forget it.", () -> {
                        t.purse += Ledger.takeCoins(host, want);
                        Diplomacy.paidTribute(host, day);
                        Ledger.relate(host, from, 2);
                        t.outcome = "their leader paid the tribute";
                    });
                }
                yield new Envoys.Answer(false, who + (yes ? " would pay, but the treasury can't." : " says: not a coin."), () -> {
                    Ledger.relate(host, from, -6);
                    t.outcome = "their leader refused to pay";
                });
            }
            case COMPLAINT -> yes
                ? new Envoys.Answer(true, who + " gives you their word: our folk will keep to our side.", () -> {
                    Ledger.relate(host, from, 4);
                    t.outcome = "their leader promised to keep to their side of the boundary";
                })
                : new Envoys.Answer(false, who + " says the land is ours. Good day.", () -> {
                    Ledger.relate(host, from, -4);
                    t.outcome = "their leader said the land was theirs";
                });
            default -> null;                                       // gifts, trade and war go the town's own way
        };
    }

    /** "Should we build a tavern?" — to the whole town, through the seam, or a show of hands at the board. */
    public static String referendum(ServerLevel level, Player p, String question) {
        StringBuilder why = new StringBuilder();
        Villages.Village v = ruled(level, p, why);
        if (v == null) return why.toString();
        if (question == null || question.isBlank()) return "Put a question to the town: \"/village leader referendum should we build a tavern\".";
        Referendums seam = REFERENDUMS;
        if (seam != null) {
            String said = seam.call(level, v, p.getUUID(), question);
            if (said != null) return said;
        }
        return showOfHands(level, v, p, question);
    }

    /** The show of hands: each grown folk for or against, by what it cares for, its liking for the leader and the leader's approval. */
    static String showOfHands(ServerLevel level, Villages.Village v, Player p, String question) {
        UUID id = v.id();
        long day = Civics.day(level);
        String last = Ledger.note(id, "civic.referendum");
        if (last != null && !last.isEmpty()) {
            try {
                if (day - Long.parseLong(last) < 3) return "The town voted on a question only days ago. Give it a rest.";
            } catch (NumberFormatException ignored) { }
        }
        Ledger.note(id, "civic.referendum", Long.toString(day));
        String q = question.toLowerCase(Locale.ROOT);
        String building = Council.named(q);
        if (building == null) building = Asks.buildingNamed(q);
        Orders.Order order = Orders.named(q);
        Pledges.Pledge pledge = Pledges.named(level, id, q);
        Values.Value about = building != null ? new Pledges.Pledge(Pledges.Kind.BUILD, building).value()
            : pledge != null ? pledge.value() : order != null ? orderValue(order) : Values.Value.TRADITION;
        int yes = 0, no = 0, appr = approval(id);
        for (VillageFolkEntity f : Elections.voters(id)) {
            double score = Values.weight(f, about) - 30 + f.persona().affinity(p.getUUID()) * 0.2 + (appr - 50) * 0.2
                + Math.floorMod(java.util.Objects.hash(f.getUUID(), q), 11) - 5;
            if (score >= 0) yes++;
            else no++;
        }
        boolean carried = yes > no;
        String result = (carried ? "carried" : "lost") + ", " + yes + " to " + no;
        Villages.tell(id, day, "the town voted on " + styled(id) + "'s question, \"" + question.trim() + "\": " + result);
        String done = "";
        if (carried && building != null && Villages.projectsWanted(id).contains(building)) {
            Villages.request(id, building);
            done = " The town will build " + Villages.spoken(building) + " next.";
        } else if (carried && order != null && Orders.possible(id, order)) {
            Orders.give(id, order, day, styled(id));
            done = " Orders given: " + order.title.toLowerCase(Locale.ROOT) + ".";
        }
        return "A show of hands at the board on \"" + question.trim() + "\" (what " + about.type + "s care for): " + result + "." + done;
    }

    private static Values.Value orderValue(Orders.Order o) {
        return switch (o) {
            case LARDER, HERDS, RIVER -> Values.Value.FOOD;
            case TIMBER -> Values.Value.HOMES;
            case DIG -> Values.Value.PROGRESS;
            case WATCH -> Values.Value.SAFETY;
            case MARKET -> Values.Value.WEALTH;
            case STEADY -> Values.Value.TRADITION;
        };
    }

    // ------------------------------------------------------------------ approval and the record

    public static int approval(UUID village) {
        String s = Ledger.note(village, "civic.approval");
        try {
            return s == null || s.isEmpty() ? 50 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    static void approval(UUID village, int n) {
        Ledger.note(village, "civic.approval", Integer.toString(Math.max(0, Math.min(100, n))));
    }

    /** The promises of the one in office. */
    public static List<Pledges.Promise> promises(UUID village) {
        return Pledges.load(Ledger.note(village, "civic.promises"));
    }

    /** A player's record, to weigh it by at an election (Hustings.lean): its promises kept and broken, its approval in office. */
    static int record(UUID village, UUID player) {
        String s = Ledger.note(village, "civic.record." + player);
        int kept = 0, broken = 0;
        if (s != null && !s.isEmpty()) {
            String[] q = s.split("\\|");
            try {
                kept = Integer.parseInt(q[0]);
                broken = Integer.parseInt(q[1]);
            } catch (RuntimeException ignored) { }
        }
        int r = kept * 6 - broken * 8;
        if (leads(village, player)) r += (approval(village) - 50) / 5;
        return Math.max(-25, Math.min(25, r));
    }

    private static void recordOne(UUID village, UUID player, boolean kept) {
        String s = Ledger.note(village, "civic.record." + player);
        int k = 0, b = 0;
        if (s != null && !s.isEmpty()) {
            String[] q = s.split("\\|");
            try { k = Integer.parseInt(q[0]); b = Integer.parseInt(q[1]); } catch (RuntimeException ignored) { }
        }
        if (kept) k++;
        else b++;
        Ledger.note(village, "civic.record." + player, k + "|" + b);
    }

    // ------------------------------------------------------------------ the morning's look

    /** A few times a day (PlayerCivic.tick): the envoy told of, the famine seen to, and once a morning the promises judged. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        UUID leader = leaderId(id);
        if (leader == null) return;
        Player p = level.getPlayerByUUID(leader);
        if (p != null && p.blockPosition().closerThan(v.centre(), Villages.VILLAGE_RANGE * 2)) Ledger.note(id, "civic.seen", Long.toString(day));
        envoyNotice(level, v, p);
        // In a famine the steward calls the town to the fields, whatever the plan: nobody starves on a principle.
        if (Leader.plan(id) == Leader.Plan.FAMINE && Orders.current(id) != Orders.Order.LARDER && Orders.possible(id, Orders.Order.LARDER)) {
            VillageFolkEntity s = steward(id);
            Orders.give(id, Orders.Order.LARDER, day, s == null ? styled(id) : s.displayNameCap());
            Villages.tell(id, day, (s == null ? "the steward" : s.displayNameCap()) + " called the town to the fields: we're near out of food");
            if (p != null) p.sendSystemMessage(Component.literal(Villages.name(id) + " is near out of food: your steward called everybody to the fields.")
                .withStyle(ChatFormatting.RED));
        }
        if (t < 1500L) return;
        String judged = Ledger.note(id, "civic.judged");
        if (judged != null && judged.equals(Long.toString(day))) return;
        Ledger.note(id, "civic.judged", Long.toString(day));
        judge(level, v, day);
    }

    /** The morning's judging: every promise looked at, approval drifting, a recall called or counted. */
    static void judge(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        UUID leader = leaderId(id);
        if (leader == null) return;
        String name = leaderName(id);
        List<Pledges.Promise> list = promises(id);
        int appr = approval(id);
        for (Pledges.Promise pr : list) {
            Pledges.State before = pr.state;
            int seasons = pr.seasons;
            Pledges.State now = Pledges.judge(level, id, pr, day);
            if (pr.pledge.kind() == Pledges.Kind.FESTIVAL && pr.seasons > seasons) {
                appr += 5;
                Villages.tell(id, day, name + " kept their promise of a festival this season");
            }
            if (now == before) continue;
            pr.state = now;
            if (now == Pledges.State.KEPT) {
                appr += 12;
                kept(level, id, leader, name, pr, day);
            } else if (now == Pledges.State.BROKEN) {
                appr -= 15;
                broken(level, id, leader, name, pr, day);
            }
        }
        Ledger.note(id, "civic.promises", Pledges.save(list));
        // Toward how content the town is; down a little for a leader never seen in town.
        int content = Contentment.score(id);
        if (Math.abs(content - appr) > 5) appr += Integer.signum(content - appr) * 2;
        String seen = Ledger.note(id, "civic.seen");
        if (seen == null || !seen.equals(Long.toString(day)) && !seen.equals(Long.toString(day - 1))) appr -= 1;
        approval(id, appr);
        recall(level, v, day);
    }

    private static void kept(ServerLevel level, UUID id, UUID leader, String name, Pledges.Promise pr, long day) {
        recordOne(id, leader, true);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                f.persona().feelFor(leader, name, Values.top(f) == pr.pledge.value() ? 5 : 3);
            }
        }
        Standing.stir(id, leader);
        Villages.tell(id, day, name + " kept their word: " + pr.pledge.words(id));
        Ledger.note(id, "civic.lastword", "kept|" + day + "|" + pr.pledge.words(id));
        Player p = level.getPlayerByUUID(leader);
        if (p != null) p.sendSystemMessage(Component.literal("Promise kept in " + Villages.name(id) + ": " + pr.pledge.words(id)
            + ". The town thinks the better of you.").withStyle(ChatFormatting.GREEN));
    }

    private static void broken(ServerLevel level, UUID id, UUID leader, String name, Pledges.Promise pr, long day) {
        recordOne(id, leader, false);
        int said = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            boolean cares = Values.top(f) == pr.pledge.value() || Values.second(f) == pr.pledge.value();
            f.persona().feelFor(leader, name, cares ? -6 : -4);
            if (cares) f.persona().remember(day, name + " broke their promise of " + pr.pledge.noun(), 3);
            if (cares && said < 2 && !f.isSleeping()) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), name + " promised us " + pr.pledge.noun() + ". And what did we get?",
                    "So much for " + pr.pledge.noun() + ". Promises are cheap.", "I voted for " + pr.pledge.noun() + ", not for nothing."));
                said++;
            }
        }
        Standing.stir(id, leader);
        Villages.tell(id, day, name + " broke their promise: " + pr.pledge.words(id));
        Market.assemblyNews(id, "There's grumbling: " + name + " promised " + pr.pledge.noun() + ", and it hasn't come.");
        Ledger.note(id, "civic.lastword", "broken|" + day + "|" + pr.pledge.words(id));
        Player p = level.getPlayerByUUID(leader);
        if (p != null) p.sendSystemMessage(Component.literal("Promise broken in " + Villages.name(id) + ": " + pr.pledge.words(id)
            + ". The town won't forget it.").withStyle(ChatFormatting.RED));
    }

    /** Approval low: a recall vote called for tomorrow; on the day, counted. */
    static void recall(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        UUID leader = leaderId(id);
        if (leader == null) return;
        String name = leaderName(id);
        String due = Ledger.note(id, "civic.recall");
        long on = -1;
        try { on = due == null || due.isEmpty() ? -1 : Long.parseLong(due.split("\\|")[0]); } catch (NumberFormatException ignored) { }
        int appr = approval(id);
        if (on < 0) {
            String lastRecall = Ledger.note(id, "civic.recalled");
            long lr = -100;
            try { lr = lastRecall == null || lastRecall.isEmpty() ? -100 : Long.parseLong(lastRecall); } catch (NumberFormatException ignored) { }
            if (appr < RECALL_AT && day - since(id) >= 2 && day - lr >= 5) {
                Ledger.note(id, "civic.recall", Long.toString(day + 1));
                Villages.tell(id, day, "the town called a vote to recall " + name + ", for tomorrow");
                Market.assemblyNews(id, "There's to be a recall vote tomorrow: does " + name + " stay as " + Homeland.leaderTitle(id) + "?");
                Player p = level.getPlayerByUUID(leader);
                if (p != null) p.sendSystemMessage(Component.literal(Villages.name(id) + " has called a recall vote for tomorrow: your approval is "
                    + appr + "%. Keep a promise, quickly.").withStyle(ChatFormatting.RED));
            }
            return;
        }
        if (day < on) return;
        Ledger.note(id, "civic.recall", "");
        Ledger.note(id, "civic.recalled", Long.toString(day));
        int stay = 0, go = 0;
        int rec = record(id, leader);
        for (VillageFolkEntity f : Elections.voters(id)) {
            double keep = f.persona().affinity(leader) * 0.5 + (appr - 50) * 0.8 + rec + Math.floorMod(java.util.Objects.hash(f.getUUID(), day), 13) - 6;
            if (keep >= 0) stay++;
            else go++;
        }
        Player p = level.getPlayerByUUID(leader);
        if (go > stay) {
            Villages.tell(id, day, "the town recalled " + name + ", " + go + " to " + stay);
            leave(level, v, day, "were recalled, " + go + " votes to " + stay);
            Villages.elderGone(id, leader);
            Elections.vacancy(level, id, name, day);
            for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby()) f.persona().feelFor(leader, name, -3);
            Standing.stir(id, leader);
        } else {
            approval(id, appr + 10);
            Villages.tell(id, day, name + " survived a recall vote, " + stay + " to " + go);
            if (p != null) p.sendSystemMessage(Component.literal("You survived the recall vote in " + Villages.name(id) + ", " + stay + " to " + go
                + ". Don't make them ask again.").withStyle(ChatFormatting.GOLD));
        }
    }

    /** A player who leads is told when an envoy is waiting, with its answer a click away. */
    private static void envoyNotice(ServerLevel level, Villages.Village v, @Nullable Player p) {
        if (p == null) return;
        VillageFolkEntity e = Envoys.waitingAt(level, v);
        if (e == null || e.trip() == null || e.trip().errand == null) return;
        if (!TOLD.add(e.getUUID())) return;
        if (TOLD.size() > 512) TOLD.clear();
        Caravans.Trip t = e.trip();
        MutableComponent msg = Component.literal("An envoy from " + Villages.name(t.from) + ", " + e.displayNameCap() + ", has come to "
            + Villages.name(v.id()) + " " + t.errand.purpose + ". ").withStyle(ChatFormatting.GOLD);
        if (t.errand != Envoys.Errand.TRADE && t.errand != Envoys.Errand.WAR) {
            msg.append(button("[Yes]", "/village leader envoy yes", ChatFormatting.GREEN)).append(" ")
                .append(button("[No]", "/village leader envoy no", ChatFormatting.RED));
        }
        p.sendSystemMessage(msg);
    }

    static MutableComponent button(String label, String command, ChatFormatting colour) {
        return Component.literal(label).withStyle(s -> s.withColor(colour)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    // ------------------------------------------------------------------ for the board, the gazette and the gossip

    /** The leader's line for the status and the town's books (Leader.line), where a player leads. */
    public static String line(UUID village) {
        String plan = Ledger.note(village, "civic.plan");
        Orders.Order o = Orders.current(village);
        int kept = 0, broken = 0, open = 0;
        for (Pledges.Promise pr : promises(village)) {
            if (pr.state == Pledges.State.KEPT) kept++;
            else if (pr.state == Pledges.State.BROKEN) broken++;
            else open++;
        }
        VillageFolkEntity s = steward(village);
        return styled(village) + " (a player), approval " + approval(village) + "%" + (s == null ? "" : ", steward " + s.displayNameCap())
            + " — plan: " + (plan == null || plan.isEmpty() || plan.equals("steward") ? "the steward's" : plan) + (o == null ? "" : " (" + o.title + ")")
            + "; tithe " + titheRate(village) + "%, wages " + wageRate(village) + "%; promises: " + kept + " kept, " + broken + " broken, "
            + open + " to come";
    }

    /** The board's lines: the player who leads, approval, the promises and how they stand, a recall; players standing. */
    public static List<String> board(ServerLevel level, UUID village, long day) {
        List<String> out = new ArrayList<>();
        UUID leader = leaderId(village);
        if (leader != null) {
            VillageFolkEntity s = steward(village);
            out.add("RN|" + styled(village) + " leads: approval " + approval(village) + "%" + (s == null ? "" : "; steward " + s.displayNameCap())
                + "; tithe " + titheRate(village) + "%, wages " + wageRate(village) + "%.");
            List<String> said = new ArrayList<>();
            for (Pledges.Promise pr : promises(village)) said.add(pr.line(village));
            if (!said.isEmpty()) out.add("RN|Promised: " + String.join("; ", said) + ".");
            String due = Ledger.note(village, "civic.recall");
            if (due != null && !due.isEmpty()) out.add("RB|A vote to recall " + leaderName(village) + " on day " + due.split("\\|")[0] + ".");
        }
        Elections.Campaign c = Elections.campaign(village);
        if (c == null || c.counted) {
            List<String> names = new ArrayList<>();
            for (Hustings.Stand s : Hustings.stands(village)) if (!s.out() && !s.player().equals(leader)) names.add(s.name() + " (" + Hustings.nouns(s.pledges()) + ")");
            if (!names.isEmpty()) out.add("RG|Standing at the next election: " + String.join("; ", names) + ".");
        }
        String seat = Hustings.seatNews(village);
        if (!seat.isEmpty()) out.add("LM|" + seat.trim());
        return out;
    }

    /** The gazette's word on the leader's promises, or null if no player leads. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village, long day) {
        UUID leader = leaderId(village);
        if (leader == null) return null;
        StringBuilder sb = new StringBuilder("§l" + capital(Homeland.leaderTitle(village)) + " " + leaderName(village) + "'s promises§r");
        sb.append("\nApproval: ").append(approval(village)).append("%.");
        for (Pledges.Promise pr : promises(village)) sb.append("\n").append(capital(pr.line(village))).append(".");
        String last = Ledger.note(village, "civic.lastword");
        if (last != null && !last.isEmpty()) {
            String[] q = last.split("\\|", 3);
            if (q.length == 3 && (q[1].equals(Long.toString(day - 1)) || q[1].equals(Long.toString(day)))) {
                sb.append("\n").append(q[0].equals("kept") ? "A promise kept: " : "A promise broken: ").append(q[2]).append(".");
            }
        }
        String due = Ledger.note(village, "civic.recall");
        if (due != null && !due.isEmpty()) sb.append("\nA recall vote is called for day ").append(due.split("\\|")[0]).append(".");
        return sb.toString();
    }

    /** Two folk on the leader's promises (Smalltalk): grumbling at one broken, giving credit for one kept. */
    @Nullable
    public static String[] gossip(VillageFolkEntity a, VillageFolkEntity b) {
        UUID village = a.ownerId();
        if (village == null || leaderId(village) == null || !(a.level() instanceof ServerLevel level)) return null;
        String last = Ledger.note(village, "civic.lastword");
        if (last == null || last.isEmpty()) return null;
        String[] q = last.split("\\|", 3);
        if (q.length < 3) return null;
        long day = Civics.day(level);
        try {
            if (day - Long.parseLong(q[1]) > 3) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        String name = leaderName(village);
        if (q[0].equals("broken")) {
            return new String[]{ "Did you hear? " + name + " promised to " + q[2] + ", and nothing.",
                FolkTalk.pick(a.getRandom(), "Promises are cheap.", "I never believed it.", "Typical."), "We'll remember at the election." };
        }
        return new String[]{ name + "'s done what they said: " + q[2] + ".", "Credit where it's due.", "" };
    }

    // ------------------------------------------------------------------ the Leader's page

    /** The Leader's page, for the player who leads a town (or the hustings page, for one who does not). */
    public static void openPage(ServerPlayer p) {
        UUID id = townLedBy(p.getUUID());
        if (id == null) {
            Hustings.openPage(p);
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        long day = Civics.day(level);
        StringBuilder sb = new StringBuilder();
        List<String> buttons = new ArrayList<>();
        VillageFolkEntity s = steward(id);
        sb.append("Approval: ").append(approval(id)).append("% (the town's contentment: ").append(Contentment.score(id)).append(").");
        if (approval(id) < RECALL_AT + 10) sb.append(" Under ").append(RECALL_AT).append("%, the town calls a recall vote.");
        sb.append("\nSteward: ").append(s == null ? "none yet" : s.displayNameCap() + " — speaks for you at the gatherings, and runs the day when you leave it to them").append(".");
        String plan = Ledger.note(id, "civic.plan");
        Orders.Order o = Orders.current(id);
        sb.append("\nPlan: ").append(plan == null || plan.isEmpty() || plan.equals("steward") ? "left to the steward" : plan)
            .append(o == null ? "" : " — " + o.title).append(".");
        sb.append("\nTithe: ").append(titheRate(id)).append(" in the hundred. Wages: ").append(wageRate(id)).append(" in the hundred. Treasury: ")
            .append(Ledger.coins(id)).append(" coins.");
        String next = Villages.nextProject(id);
        sb.append("\nNext building: ").append(next == null ? "none" : Villages.spoken(next)).append(".");
        sb.append("\nNext election: day ").append(Elections.nextVote(id, day)).append(".");
        List<Pledges.Promise> list = promises(id);
        sb.append("\n\nYour promises:");
        if (list.isEmpty()) sb.append(" none.");
        for (Pledges.Promise pr : list) sb.append("\n- ").append(pr.line(id));
        Villages.Village here = Villages.get(id);
        VillageFolkEntity e = here == null ? null : Envoys.waitingAt(level, here);
        if (e != null && e.trip() != null && e.trip().errand != null) {
            sb.append("\n\nAn envoy is waiting: ").append(e.displayNameCap()).append(" of ").append(Villages.name(e.trip().from)).append(", ")
                .append(e.trip().errand.purpose).append(".");
            buttons.add("Envoy: yes\tvillage leader envoy yes\tThe town's answer at the board is yes");
            buttons.add("Envoy: no\tvillage leader envoy no\tThe town's answer at the board is no");
        }
        sb.append("\n\nA referendum: /village leader referendum <your question>.");
        buttons.add("Food first\tvillage leader plan food\tMore hands to the fields, the river and the hunt");
        buttons.add("Growth\tvillage leader plan growth\tTimber in the Wood Age, then the mines and the furnaces");
        buttons.add("Defence\tvillage leader plan defence\tMore of the town on the watch");
        buttons.add("Trade\tvillage leader plan trade\tMore for the shop, the café and the crafts");
        buttons.add("Steady\tvillage leader plan steady\tEverybody carries on as they are");
        buttons.add("Steward decides\tvillage leader plan steward\tLeave the orders to your steward");
        buttons.add("Tithe -\tvillage leader tithe " + Math.max(TITHE_LEAST, titheRate(id) - 2) + "\tLower the tithe by two in the hundred");
        buttons.add("Tithe +\tvillage leader tithe " + Math.min(TITHE_MOST, titheRate(id) + 2) + "\tRaise the tithe by two in the hundred");
        buttons.add("Wages -\tvillage leader wages " + Math.max(WAGES_LEAST, wageRate(id) - 5) + "\tCut the wages by five in the hundred");
        buttons.add("Wages +\tvillage leader wages " + Math.min(WAGES_MOST, wageRate(id) + 5) + "\tRaise the wages by five in the hundred");
        int shown = 0;
        for (String w : Villages.projectsWanted(id)) {
            if (w.equals(next) || shown >= 3) continue;
            buttons.add("Build: " + w + "\tvillage leader build " + w + "\tPut " + Villages.spoken(w) + " at the head of the town's list");
            shown++;
        }
        PlayerCivic.send(p, styled(id) + " of " + Villages.name(id), sb.toString(), buttons);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the smoke runs

    /**
     * The smoke stage (/village civic stage, as a player): the player made a citizen the town thinks the world of,
     * standing with the first two promises on offer, the election called and counted there and then; it wins, takes
     * office, and the town's plan, tithe and next building are set from its page. Returns lines for the camera:
     * "BOARD x y z facing" (the board's foot, which way its face looks) and the outcome.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        String name = p.getName().getString();
        if (!Citizens.is(id, p.getUUID())) Ledger.addCitizen(id, p.getUUID(), name);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby()) {
                f.ensurePersona();
                f.persona().met(p.getUUID());
                if (f.persona().affinity(p.getUUID()) < 45) f.persona().feelFor(p.getUUID(), name, 45 - f.persona().affinity(p.getUUID()));
            }
        }
        Standing.stir(id, p.getUUID());
        long day = Civics.day(level);
        List<Pledges.Pledge> on = Pledges.offered(level, id);
        List<Pledges.Pledge> mine = new ArrayList<>();
        for (Pledges.Pledge pl : on) {
            if (pl.kind() == Pledges.Kind.BUILD && mine.stream().noneMatch(x -> x.kind() == Pledges.Kind.BUILD)) mine.add(pl);
            if (pl.kind() == Pledges.Kind.TITHE) mine.add(pl);
            if (mine.size() >= 2) break;
        }
        Hustings.put(id, new Hustings.Stand(p.getUUID(), name, mine, day, false));
        if (!leads(id, p.getUUID())) {
            Elections.Campaign c = Elections.campaign(id);
            if (c == null || c.counted) Elections.callForTests(level, v, day);
            c = Elections.campaign(id);
            if (c != null) Hustings.enterNow(c, id, p.getUUID());
            Elections.Result r = Elections.countForTests(level, v);
            out.add("COUNT " + (r.winner() == null ? "none" : r.winner().name()) + " " + Elections.tally(r));
        }
        if (leads(id, p.getUUID())) {
            out.add(tithe(level, p, 6));
            out.add(plan(level, p, "food"));
            List<String> wanted = Villages.projectsWanted(id);
            if (!wanted.isEmpty()) out.add(build(level, p, wanted.get(0)));
        }
        net.minecraft.core.BlockPos board = VillageBoards.boardOf(id);
        net.minecraft.core.Direction facing = VillageBoards.facingOf(id);
        if (board != null && facing != null) out.add("BOARD " + board.getX() + " " + board.getY() + " " + board.getZ() + " " + facing.getName());
        out.add("LEADS " + leads(id, p.getUUID()));
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the morning's judging, as on this day. */
    public static void judgeForTests(ServerLevel level, Villages.Village v, long day) {
        judge(level, v, day);
    }

    /** Tests: each promise in office and how it stands ("tithe:KEPT", "build:shelter:BROKEN"). */
    public static List<String> promisesForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Pledges.Promise pr : promises(village)) out.add(pr.pledge.key() + ":" + pr.state.name());
        return out;
    }
}
