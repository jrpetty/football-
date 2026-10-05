package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The leader runs the village. Every morning the elder (the reeve, the thane, the harbourmaster —
 * whatever the land calls its head) looks over the village's books and makes the calls, and its
 * own nature runs through the whole place.
 *
 * <h2>The food</h2>
 * The village keeps accounts of its food: what came into the stores yesterday and what went out
 * of them (eaten, mostly). From those the leader reckons how many days of food are put by, and
 * sets the plan:
 * <ul>
 * <li><b>Famine</b> (under a day's food left): every hand that can be spared to the fields and
 *     the water; the fields widened as soon as they are half sown; the order to fill the larder
 *     given at once; and, if the treasury has the coin, bread bought from the passing traders to
 *     tide the village over.</li>
 * <li><b>Short</b> (less put by than the leader likes to keep, or more eaten than grown): more
 *     farmers and fishers, the fields widened sooner, the larder ordered filled.</li>
 * <li><b>Steady</b>, and <b>plenty</b> (three times the reserve, and more grown than eaten): the
 *     fields can spare a hand for the village's other work.</li>
 * </ul>
 * How much the leader likes to keep put by is its nature: a shy or a grumpy leader keeps three
 * days' food or two and a half, an easygoing one a day and a half.
 *
 * <h2>The leader's nature</h2>
 * <ul>
 * <li><b>Work.</b> A hardworking leader drives the village (everybody works faster, and their
 *     breaks are shorter); an easygoing one lets it take its time (slower work, longer breaks).</li>
 * <li><b>Spirits.</b> A cheerful, generous or easygoing leader lifts everybody's mood; a grumpy or
 *     a hard-driving one wears it down. Each folk feels it more or less by how it gets on with the
 *     leader, and by whether their natures sit well together.</li>
 * <li><b>Pay.</b> A generous leader pays over the odds; a grumpy, shy or hardworking one keeps the
 *     purse tight. When the village is short of something it must buy (beds, food) and the
 *     treasury is thin, the leader holds part of the wages back for it.</li>
 * <li><b>Families.</b> A sociable, cheerful or generous leader makes a village where children come
 *     sooner; a shy or a grumpy one, later.</li>
 * <li><b>The young.</b> A child that grows up without a trade of its own is set to the work the
 *     village most needs, by the leader's word.</li>
 * </ul>
 * Everything the leader decides goes in the village's history, the morning assembly hears it,
 * and the status, the board and the elder itself can tell you the plan.
 */
public final class Leader {

    private Leader() {}

    public enum Plan {
        FAMINE("famine"), SHORT("short of food"), STEADY("steady"), PLENTY("plenty");

        public final String word;

        Plan(String word) { this.word = word; }
    }

    /** The village's food books: what was in the stores this morning, what came in and went out
     *  yesterday, the running averages, how many days are put by, and the plan made of it. */
    public record Books(int stock, int in, int use, double inAvg, double useAvg, double days, Plan plan, long day) {}

    /** The leader as the village feels it, looked up afresh every ten seconds. */
    record Nature(UUID elder, String name, EnumSet<Social.Trait> traits, long at) {}

    private static final Map<UUID, Nature> NATURE = new ConcurrentHashMap<>();
    private static final Map<UUID, Books> BOOKS = new ConcurrentHashMap<>();
    /** Food brought into the stores today, in meals (wheat is a third of one). */
    private static final Map<UUID, Integer> FOOD_IN = new ConcurrentHashMap<>();
    /** Today's pay, in the hundred of the standard wage. */
    private static final Map<UUID, Integer> PAY = new ConcurrentHashMap<>();
    /** What the leader last decided, for the status and the elder's talk. */
    private static final Map<UUID, String> DECIDED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NATURE.clear();
        BOOKS.clear();
        FOOD_IN.clear();
        PAY.clear();
        DECIDED.clear();
    }

    // ------------------------------------------------------------------ the leader's nature

    @Nullable
    static Nature nature(@Nullable UUID village, long now) {
        if (village == null) return null;
        Nature n = NATURE.get(village);
        if (n != null && now - n.at() < 200 && now >= n.at()) return n.elder() == null ? null : n;
        VillageFolkEntity elder = Orders.elderOf(village);
        Nature fresh = elder == null || elder.isBaby()
            ? new Nature(null, "", EnumSet.noneOf(Social.Trait.class), now)
            : new Nature(elder.getUUID(), elder.displayNameCap(), traitsOf(elder), now);
        NATURE.put(village, fresh);
        return fresh.elder() == null ? null : fresh;
    }

    private static EnumSet<Social.Trait> traitsOf(VillageFolkEntity f) {
        EnumSet<Social.Trait> set = EnumSet.noneOf(Social.Trait.class);
        set.addAll(f.life().traits());
        return set;
    }

    @Nullable
    private static Nature natureOf(@Nullable UUID village) {
        if (village == null) return null;
        Nature n = NATURE.get(village);
        if (n != null && n.elder() != null) return n;
        return null;
    }

    /** How much faster (or slower) the village works under this leader, in the hundred. */
    public static int pace(@Nullable UUID village) {
        Nature n = natureOf(village);
        if (n == null) return 0;
        int p = 0;
        for (Social.Trait t : n.traits()) {
            p += switch (t) {
                case HARDWORKING -> 8;
                case GRUMPY -> 3;
                case CURIOUS, CHEERFUL -> 2;
                case SHY -> 1;
                case EASYGOING -> -5;
                default -> 0;
            };
        }
        return Math.max(-8, Math.min(10, p));
    }

    /** How long a break runs under this leader, against the usual. */
    public static double restScale(@Nullable UUID village) {
        Nature n = natureOf(village);
        if (n == null) return 1.0;
        double s = 1.0;
        for (Social.Trait t : n.traits()) {
            s *= switch (t) {
                case HARDWORKING -> 0.8;
                case GRUMPY -> 0.9;
                case EASYGOING -> 1.25;
                case GENEROUS -> 1.1;
                case SOCIABLE -> 1.05;
                default -> 1.0;
            };
        }
        return s;
    }

    /** The leader's own spirits, as they spread through the village. */
    static int spiritsOfNature(Nature n) {
        int s = 0;
        for (Social.Trait t : n.traits()) {
            s += switch (t) {
                case CHEERFUL -> 5;
                case GENEROUS, EASYGOING -> 3;
                case SOCIABLE -> 2;
                case CURIOUS -> 1;
                case HARDWORKING -> -2;
                case GRUMPY -> -4;
                default -> 0;
            };
        }
        return s;
    }

    /**
     * What the leader does to this folk's mood: the leader's own spirits, and how this folk gets on
     * with it — liked, disliked, of a like mind or of the opposite. Zero for the leader itself.
     * The reason goes with it ("leader" or "leaderhard"), for the folk to give when asked.
     */
    public static int spirits(VillageFolkEntity f) {
        Nature n = nature(f.ownerId(), f.level().getGameTime());
        if (n == null || f.getUUID().equals(n.elder())) return 0;
        int s = spiritsOfNature(n);
        int regard = f.life().affinity(n.elder());
        if (regard >= Social.FRIEND) s += 3;
        else if (regard <= Social.RIVAL) s -= 5;
        for (Social.Trait t : f.life().traits()) {
            if (n.traits().contains(t)) s += 2;                           // of a like mind
            if (t.opposite() != null && n.traits().contains(t.opposite())) s -= 2;
        }
        // Driven hard, an easygoing folk feels it most; under an easygoing leader the hardworking
        // find the place slack.
        if (n.traits().contains(Social.Trait.HARDWORKING) && f.life().has(Social.Trait.EASYGOING)) s -= 3;
        if (n.traits().contains(Social.Trait.EASYGOING) && f.life().has(Social.Trait.HARDWORKING)) s -= 1;
        return Math.max(-12, Math.min(12, s));
    }

    /** What a folk says of the leader when its mood is asked about. */
    public static String moodWords(VillageFolkEntity f, boolean glad) {
        Nature n = natureOf(f.ownerId());
        if (n == null) return "";
        String who = Homeland.leaderTitle(f.ownerId());
        who = Character.toUpperCase(who.charAt(0)) + who.substring(1) + " " + n.name();
        if (glad) {
            if (n.traits().contains(Social.Trait.CHEERFUL)) return who + " keeps everybody's spirits up.";
            if (n.traits().contains(Social.Trait.GENEROUS)) return who + " looks after us well.";
            if (n.traits().contains(Social.Trait.EASYGOING)) return who + " doesn't drive us too hard. I like that.";
            return who + " runs a good village.";
        }
        if (n.traits().contains(Social.Trait.HARDWORKING)) return who + " works us to the bone.";
        if (n.traits().contains(Social.Trait.GRUMPY)) return who + " has a sharp tongue on a bad day — most days.";
        return "I don't see eye to eye with " + who + ".";
    }

    /** How many days' food the leader likes to keep put by. */
    public static double reserveDays(@Nullable UUID village) {
        Nature n = natureOf(village);
        if (n == null || n.traits().isEmpty()) return 2.0;
        double sum = 0;
        for (Social.Trait t : n.traits()) {
            sum += switch (t) {
                case SHY -> 3.0;
                case GRUMPY, GENEROUS -> 2.5;
                case HARDWORKING, CURIOUS -> 2.0;
                case CHEERFUL, SOCIABLE -> 1.75;
                case EASYGOING -> 1.5;
            };
        }
        return sum / n.traits().size();
    }

    /** The leader's usual pay, in the hundred of the standard wage. */
    static int usualPay(@Nullable UUID village) {
        Nature n = natureOf(village);
        if (n == null) return 100;
        int p = 100;
        for (Social.Trait t : n.traits()) {
            p += switch (t) {
                case GENEROUS -> 8;
                case CHEERFUL, SOCIABLE -> 2;
                case HARDWORKING, SHY -> -3;
                case GRUMPY -> -6;
                default -> 0;
            };
        }
        return Math.max(85, Math.min(115, p));
    }

    /** Today's pay, in the hundred of the standard wage (Market.payWages). */
    public static int payRate(@Nullable UUID village) {
        if (village == null) return 100;
        return PAY.getOrDefault(village, usualPay(village));
    }

    /** How long the village waits between births under this leader, against the usual. */
    public static double family(@Nullable UUID village) {
        Nature n = natureOf(village);
        if (n == null) return 1.0;
        double s = 1.0;
        for (Social.Trait t : n.traits()) {
            s *= switch (t) {
                case SOCIABLE -> 0.8;
                case CHEERFUL -> 0.85;
                case GENEROUS -> 0.9;
                case SHY -> 1.2;
                case GRUMPY -> 1.15;
                default -> 1.0;
            };
        }
        return s;
    }

    // ------------------------------------------------------------------ the food books

    /** Food came into the stores (Economy.produced): counted in meals, wheat a third of one. */
    public static void foodIn(@Nullable UUID village, ItemStack s) {
        if (village == null || s.isEmpty()) return;
        int meals = s.get(DataComponents.FOOD) != null ? s.getCount() : s.is(Items.WHEAT) ? s.getCount() / 3 : 0;
        if (meals > 0) FOOD_IN.merge(village, meals, Integer::sum);
    }

    @Nullable
    public static Books books(@Nullable UUID village) {
        if (village == null) return null;
        Books b = BOOKS.get(village);
        if (b != null) return b;
        // After a restart: the books kept with the world.
        String kept = Ledger.note(village, "leader.books");
        if (kept == null || kept.isEmpty()) return null;
        try {
            String[] p = kept.split("\\|");
            b = new Books(Integer.parseInt(p[0]), 0, 0, Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                Double.parseDouble(p[3]), Plan.valueOf(p[4]), Long.parseLong(p[5]));
        } catch (RuntimeException e) {
            return null;
        }
        BOOKS.put(village, b);
        return b;
    }

    public static Plan plan(@Nullable UUID village) {
        Books b = books(village);
        return b == null ? Plan.STEADY : b.plan();
    }

    /** How many more food-makers the leader wants than the village's usual share (Villages.target). */
    public static double foodFactor(@Nullable UUID village, StationTask trade) {
        if (village == null) return 1.0;
        boolean food = trade == StationTask.FARM || trade == StationTask.FISH || trade == StationTask.HUNT;
        if (!food) return 1.0;
        return switch (plan(village)) {
            case FAMINE -> trade == StationTask.HUNT ? 1.5 : 2.0;
            case SHORT -> trade == StationTask.HUNT ? 1.25 : 1.5;
            default -> 1.0;
        };
    }

    /** Is the leader widening the fields (they are widened when half sown, not six in ten)? */
    public static boolean widening(@Nullable UUID village) {
        Plan p = plan(village);
        return p == Plan.FAMINE || p == Plan.SHORT;
    }

    /** What a day's meals for the village come to, before there are books to go by. */
    static double guessedUse(UUID village) {
        int adults = 0, children = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.isBaby()) children++;
            else adults++;
        }
        return 4.0 * adults + 2.0 * children;
    }

    // ------------------------------------------------------------------ the morning's calls

    /** The leader's morning: the books, the plan, the pay. From Market.tick, before the wages. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        nature(id, level.getGameTime());
        Nature n = natureOf(id);
        int stock = Villages.stock(level, v.centre(), Villages.Task.FOOD, Villages.storesRadius(id));
        int in = FOOD_IN.getOrDefault(id, 0);
        FOOD_IN.remove(id);
        Books last = books(id);
        double guess = guessedUse(id);
        int use;
        double inAvg, useAvg;
        if (last == null || day - last.day() > 3 || day <= last.day()) {
            use = (int) Math.round(guess);
            inAvg = in;
            useAvg = guess;
        } else {
            use = Math.max(0, last.stock() + in - stock);
            inAvg = (last.inAvg() * 2 + in) / 3.0;
            // What went out of the stores, never much under what the village must eat: a day when
            // everybody ate from their packs is not a day the village stopped eating.
            useAvg = Math.max(guess * 0.6, (last.useAvg() * 2 + use) / 3.0);
        }
        double days = stock / Math.max(1.0, useAvg);
        double reserve = reserveDays(id);
        int heads = Math.max(1, Villages.headcount(id));
        Plan plan;
        if (days < 0.75 || stock < heads) plan = Plan.FAMINE;
        else if (days < reserve || (inAvg < useAvg * 0.9 && days < reserve * 2)) plan = Plan.SHORT;
        else if (days > reserve * 3 && inAvg >= useAvg) plan = Plan.PLENTY;
        else plan = Plan.STEADY;
        Plan was = last == null ? Plan.STEADY : last.plan();
        Books b = new Books(stock, in, use, inAvg, useAvg, days, plan, day);
        BOOKS.put(id, b);
        Ledger.note(id, "leader.books", stock + "|" + round(inAvg) + "|" + round(useAvg) + "|" + round(days) + "|" + plan.name() + "|" + day);

        List<String> calls = new ArrayList<>();
        VillageFolkEntity elder = Orders.elderOf(id);
        String who = elder == null ? "the village" : leaderName(id, elder);
        String daysWords = days >= 10 ? "more than ten days'" : String.format(Locale.ROOT, "%.1f days'", days);
        if (plan == Plan.FAMINE || plan == Plan.SHORT) {
            // The larder first, at once: not in three days' time when the orders come round.
            if (elder != null && Orders.current(id) != Orders.Order.LARDER && Orders.possible(id, Orders.Order.LARDER)) {
                Orders.give(id, Orders.Order.LARDER, day, elder.displayNameCap());
                calls.add("more hands to the fields and the water");
                Quests.paint(level, v);
            }
            calls.add("the fields widened as soon as they are half sown");
            if (plan == Plan.FAMINE) {
                int bought = buyBread(level, v, day, Math.max(heads * 4, (int) Math.ceil(useAvg)));
                if (bought > 0) calls.add("bread bought from the traders (" + bought + " coin)");
            }
        }
        if (plan != was) {
            String line = switch (plan) {
                case FAMINE -> who + " called a famine: " + daysWords + " food left";
                case SHORT -> who + " put the village on short commons: " + daysWords + " food put by, "
                    + (inAvg < useAvg ? "and more eaten than grown" : "less than " + String.format(Locale.ROOT, "%.1f", reserve) + " days'");
                case PLENTY -> who + " said the larder is full: " + daysWords + " food, and more grown than eaten";
                case STEADY -> who + " said the village is fed again: " + daysWords + " food put by";
            };
            Villages.tell(id, day, line + (calls.isEmpty() ? "" : " — " + String.join("; ", calls)));
            if (elder != null) {
                FolkTalk.speak(elder, switch (plan) {
                    case FAMINE -> "We're near out of food. Everybody who can be spared, to the fields!";
                    case SHORT -> "We're eating faster than we grow. More hands to the fields, and widen them.";
                    case PLENTY -> "The larder's full. The fields can spare a hand for the rest of the work.";
                    case STEADY -> "We're fed. Back to the work in hand.";
                });
            }
        }

        // The pay: the leader's usual, and part held back when there is buying to do and no coin.
        int pay = usualPay(id);
        int coins = Ledger.coins(id);
        int bill = Math.max(1, Market.wageBill(id));
        boolean beds = Market.bedsShort(id) >= 2;
        boolean thin = coins < bill * 2;
        String held = null;
        if (thin && (beds || plan == Plan.FAMINE)) {
            boolean generous = n != null && n.traits().contains(Social.Trait.GENEROUS);
            pay = Math.min(pay, generous ? 95 : 85);
            held = beds ? "the beds" : "the bread";
        }
        Integer before = PAY.put(id, pay);
        if (before != null && before != pay) {
            Villages.tell(id, day, who + " set the wages at " + pay + " in the hundred"
                + (held == null ? "" : ", holding the rest back for " + held));
            Market.assemblyNews(id, held == null
                ? "The wages are back to the full rate. You've earned it."
                : "I'm holding a little of the wages back till we've paid for " + held + ". We'll make it up.");
        }
        if (!calls.isEmpty() && elder != null) {
            Market.assemblyNews(id, "We've " + daysWords + " food put by. " + capital(String.join(", ", calls)) + ".");
        }
        DECIDED.put(id, plan.word + ": " + daysWords + " food (" + (int) Math.round(inAvg) + " in, "
            + (int) Math.round(useAvg) + " eaten a day)" + (calls.isEmpty() ? "" : "; " + String.join(", ", calls))
            + (pay != 100 ? "; wages at " + pay + "%" : ""));
    }

    /** Famine bread off the passing traders, out of half the treasury at most. Returns the coin spent. */
    static int buyBread(ServerLevel level, Villages.Village v, long day, int meals) {
        UUID id = v.id();
        Market.Good g = Market.goodFor(new ItemStack(Items.BREAD));
        if (g == null || meals <= 0) return 0;
        int price = Math.max(1, Market.sellPrice(g, 0, Market.marketDay(id, day)));
        int lots = Math.min(3, (meals + g.bundle() - 1) / g.bundle());
        int can = Math.max(0, Ledger.coins(id) / 2) / price;
        lots = Math.min(lots, can);
        if (lots <= 0) return 0;
        int paid = Ledger.takeCoins(id, lots * price);
        Economy.spent(id, paid);
        Market.intoStores(level, id, new ItemStack(Items.BREAD, lots * g.bundle()));
        Villages.tell(id, day, "bought " + lots * g.bundle() + " bread from the traders for " + paid + " coin, against the famine");
        return paid;
    }

    /** "Reeve Bramble", "Elder Quill". */
    static String leaderName(UUID village, VillageFolkEntity elder) {
        String title = Homeland.leaderTitle(village);
        return Character.toUpperCase(title.charAt(0)) + title.substring(1) + " " + elder.displayNameCap();
    }

    // ------------------------------------------------------------------ the young

    /** A child grown up with no trade learned: the leader sets it to what the village needs most. */
    public static StationTask calledUp(VillageFolkEntity f, long day) {
        UUID village = f.ownerId();
        if (village == null) return StationTask.NONE;
        StationTask t = Villages.needed(village);
        if (t == StationTask.NONE) return t;
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null && elder != f) {
            Villages.tell(village, day, leaderName(village, elder) + " set " + f.displayNameCap() + " to work as a "
                + t.title.toLowerCase(Locale.ROOT) + ", where the village most needed hands");
            f.persona().remember(day, leaderName(village, elder) + " made me a " + t.title.toLowerCase(Locale.ROOT), 6);
        }
        return t;
    }

    // ------------------------------------------------------------------ telling

    /** One line for the status: who leads, what it is like, and the plan. */
    public static String line(@Nullable UUID village) {
        if (village == null) return "no leader";
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder == null) return "no leader yet";
        Nature n = nature(village, elder.level().getGameTime());
        StringBuilder sb = new StringBuilder(leaderName(village, elder));
        if (n != null && !n.traits().isEmpty()) {
            List<String> t = new ArrayList<>();
            for (Social.Trait tr : n.traits()) t.add(tr.label);
            sb.append(" (").append(String.join(" and ", t)).append(")");
        }
        int pace = pace(village), spirits = n == null ? 0 : spiritsOfNature(n);
        sb.append(" — keeps ").append(String.format(Locale.ROOT, "%.1f", reserveDays(village))).append(" days' food put by; work ")
            .append(pace >= 0 ? "+" : "").append(pace).append("%, breaks ×")
            .append(String.format(Locale.ROOT, "%.2f", restScale(village))).append(", spirits ")
            .append(spirits >= 0 ? "+" : "").append(spirits);
        String d = DECIDED.get(village);
        if (d != null) sb.append(". Plan: ").append(d);
        return sb.toString();
    }

    /** The board's line: the leader, the food it reckons is put by, and the plan. */
    @Nullable
    public static String board(@Nullable UUID village) {
        if (village == null) return null;
        VillageFolkEntity elder = Orders.elderOf(village);
        Books b = books(village);
        if (elder == null || b == null) return null;
        String daysWords = b.days() >= 10 ? "over ten days'" : String.format(Locale.ROOT, "%.1f days'", b.days());
        int pace = pace(village);
        return leaderName(village, elder) + ": " + daysWords + " food put by — " + b.plan().word
            + (pace != 0 ? "; work " + (pace > 0 ? "+" : "") + pace + "%" : "")
            + (payRate(village) != 100 ? "; wages " + payRate(village) + "%" : "") + ".";
    }

    /** What the leader says of the plan, asked. */
    public static String plan(VillageFolkEntity asked) {
        UUID village = asked.ownerId();
        Books b = books(village);
        if (village == null || b == null) return "";
        String daysWords = b.days() >= 10 ? "more than ten days'" : String.format(Locale.ROOT, "%.1f days'", b.days());
        return switch (b.plan()) {
            case FAMINE -> " We're near out of food — " + daysWords + " left. Every spare hand is in the fields.";
            case SHORT -> " We've " + daysWords + " food put by and I want more. The fields are being widened.";
            case PLENTY -> " The larder's full — " + daysWords + " food. We can turn our hands to other things.";
            case STEADY -> " We've " + daysWords + " food put by. That'll do.";
        };
    }

    private static String round(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
