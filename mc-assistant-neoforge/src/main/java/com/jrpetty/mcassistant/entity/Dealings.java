package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player and one folk can do together, small things first.
 * <ul>
 * <li><b>A greeting by name.</b> A friend passing by is hailed by name, now and then.</li>
 * <li><b>A meal together.</b> Share some food with a folk: it eats with you, and likes you the better.</li>
 * <li><b>Dice.</b> A game of dice for a few coins, out of each other's purses.</li>
 * <li><b>Haggling.</b> Ask the storekeeper or the shopkeeper to do it cheaper: a good word goes a long
 *     way with a generous one, not far with a shrewd one; the discount holds for the day.</li>
 * <li><b>A lesson.</b> Show a folk a trick of its trade with the tool of it in your hand: once a day,
 *     it learns something (its trade's experience).</li>
 * <li><b>A godparent.</b> A friend of the village can stand godparent to a child: the child will
 *     think the world of you, and its parents the better of you.</li>
 * <li><b>A keepsake.</b> A close friend gives you something of its own to remember it by.</li>
 * <li><b>Letters.</b> Close friends write to you, once a week, when you are about.</li>
 * <li><b>Made to order.</b> Ask a craftsman to make you something: it is made from its own recipe,
 *     out of what you bring and what the village can spare, for its worth and the work.</li>
 * <li><b>Repairs.</b> The smith (or a smelter at its forge, in a town with no smith) mends a worn tool, weapon or
 *     armour: its metal out of the stores, a unit a quarter of the wear, at the market's price and a fee
 *     (PlayerServices).</li>
 * <li><b>A feast on you.</b> Pay for tonight's feast: the whole village gathers, and remembers who paid.</li>
 * </ul>
 */
public final class Dealings {

    private Dealings() {}

    // ------------------------------------------------------------------ helpers

    static long day(VillageFolkEntity f) {
        return f.level().getDayTime() / 24000L;
    }

    static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    static void giveCoins(Player p, int n) {
        if (n > 0) give(p, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), n));
    }

    /** A once-in-a-while mark: has this key been done within the last {@code days} days? Marks it now if not. */
    static boolean done(UUID village, String key, long day, int days) {
        String last = Ledger.note(village, key);
        if (last != null && !last.isEmpty()) {
            try {
                if (day - Long.parseLong(last) < days) return true;
            } catch (NumberFormatException ignored) {
                // write it afresh
            }
        }
        Ledger.note(village, key, Long.toString(day));
        return false;
    }

    // ------------------------------------------------------------------ 1. a greeting by name

    private static final Map<String, Long> GREETED = new ConcurrentHashMap<>();

    /** A friend passing by, hailed by name (now and then, by folk with a moment). */
    public static void greet(VillageFolkEntity f) {
        if (f.isBaby() || f.tickCount % 100 != 37 || !(f.level() instanceof ServerLevel level)) return;
        Player p = level.getNearestPlayer(f, 7.0);
        if (p == null || p.isSpectator()) return;
        int aff = f.persona().affinity(p.getUUID());
        if (aff < 12) return;
        String key = f.getUUID() + "/" + p.getUUID();
        long now = level.getGameTime();
        if (now - GREETED.getOrDefault(key, -100000L) < 6000L) return;
        GREETED.put(key, now);
        if (GREETED.size() > 2048) GREETED.clear();
        String name = p.getName().getString();
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        FolkTalk.speak(f, aff >= 50
            ? FolkTalk.pick(f.getRandom(), name + "! Good to see you!", "There's " + name + "! How are you keeping?", "Hello, " + name + " — come and sit a while.")
            : FolkTalk.pick(f.getRandom(), "Morning, " + name + ".", "Hello there, " + name + ".", name + "! Nice day for it."));
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ 2. a meal together

    /** "Eat with me?" — the food in the player's hand, shared. */
    public static String meal(VillageFolkEntity f, Player p) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || held.get(DataComponents.FOOD) == null) {
            return "Eat with you? Gladly — but you've nothing to eat in your hand. Bring something and we'll sit down together.";
        }
        UUID village = f.ownerId();
        long d = day(f);
        if (village != null && done(village, "meal/" + f.getUUID() + "/" + p.getUUID(), d, 1)) {
            return "We ate together already today — I couldn't manage another bite!";
        }
        String what = held.getHoverName().getString().toLowerCase(Locale.ROOT);
        held.shrink(1);
        String name = p.getName().getString();
        f.persona().feelFor(p.getUUID(), name, id(held, f) ? 9 : 6);
        f.persona().remember(d, "I shared a meal with " + name, 3);
        f.heal(2.0F);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return FolkTalk.pick(f.getRandom(), "Thank you! There's nothing like a bit of " + what + " in good company.",
            "Sit, sit. Tell me what you've been up to. This " + what + " is lovely.",
            "That was kind of you. We should do this again.");
    }

    private static boolean id(ItemStack held, VillageFolkEntity f) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).getPath().equals(f.persona().food());
    }

    // ------------------------------------------------------------------ 3. dice

    /** "Fancy a game of dice? Five coins." — out of each other's purses. */
    public static String dice(VillageFolkEntity f, Player p, String text) {
        if (f.isBaby()) return "My mum says I'm not allowed to play dice.";
        int stake = Math.max(1, Math.min(20, Commerce.number(text, 3)));
        if (f.purse() < stake) return "I've not got " + coins(stake) + " to lose. Something smaller?";
        int coins = Market.coinsHeld(p);
        if (coins < stake) return "You've not got " + coins(stake) + " on you. Put your money where your mouth is!";
        if (f.life().has(Social.Trait.SHY) && f.getRandom().nextInt(3) == 0) return "Oh, I'm no good at games. Ask somebody else.";
        int mine = 2 + f.getRandom().nextInt(6) + f.getRandom().nextInt(6);
        int yours = 2 + f.getRandom().nextInt(6) + f.getRandom().nextInt(6);
        String name = p.getName().getString();
        f.persona().feelFor(p.getUUID(), name, 1);
        if (yours > mine) {
            f.spend(stake);
            giveCoins(p, stake);
            return "You rolled " + yours + ", I rolled " + mine + ". " + FolkTalk.pick(f.getRandom(), "Curses! Here's your " + stake + ".",
                "Beginner's luck. Again tomorrow?", "Well played. " + coins(stake) + " to you.");
        }
        if (mine > yours) {
            Market.payOut(p, stake);
            f.earn(stake);
            return "You rolled " + yours + ", I rolled " + mine + ". " + FolkTalk.pick(f.getRandom(), "Ha! " + coins(stake) + " to me.",
                "The dice love me today.", "Better luck next time!");
        }
        return "We both rolled " + mine + "! A draw — nobody pays.";
    }

    // ------------------------------------------------------------------ 4. haggling

    /** The day's discount a player talked a village's keepers into (a percentage), by village and player. */
    private static final Map<String, int[]> HAGGLED = new ConcurrentHashMap<>();

    /** "Can you do it any cheaper?" — the storekeeper's or the shopkeeper's answer. */
    public static String haggle(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "Cheaper? I've nothing to sell.";
        AssistantEntity.StationTask t = f.stationTask();
        if (t != AssistantEntity.StationTask.STORE && t != AssistantEntity.StationTask.SHOP && t != AssistantEntity.StationTask.COOK) {
            return "Haggle with the storekeeper or the shopkeeper, not me — I just work here.";
        }
        long d = day(f);
        String key = village + "/" + p.getUUID();
        int[] have = HAGGLED.get(key);
        if (have != null && have[1] == (int) d) return "You've had your discount today — " + have[0] + " off. Don't push your luck.";
        int aff = f.persona().affinity(p.getUUID());
        int chance = 40 + aff / 2 + (f.life().has(Social.Trait.GENEROUS) ? 20 : 0) - (Envoys.temper(village) == Envoys.Temper.SHREWD ? 15 : 0);
        String name = p.getName().getString();
        if (f.getRandom().nextInt(100) < Math.max(10, Math.min(90, chance))) {
            int off = 5 + f.getRandom().nextInt(aff >= 30 ? 16 : 8);
            HAGGLED.put(key, new int[]{ off, (int) d });
            return FolkTalk.pick(f.getRandom(), "Oh, go on then — " + off + " in the hundred off, for today.",
                "For you, " + name + "? " + off + " off. Don't tell the others.");
        }
        HAGGLED.put(key, new int[]{ 0, (int) d });
        f.persona().feelFor(p.getUUID(), name, -1);
        return FolkTalk.pick(f.getRandom(), "The price is the price.", "Cheaper? We've families to feed!", "No. And don't ask again today.");
    }

    /** What a player talked the village down to today: a price after the haggle. */
    public static int haggled(UUID village, UUID player, long day, int price) {
        int[] have = HAGGLED.get(village + "/" + player);
        if (have == null || have[1] != (int) day || have[0] <= 0) return price;
        return Math.max(1, price - (int) Math.round(price * have[0] / 100.0));
    }

    // ------------------------------------------------------------------ 5. a lesson

    /** "Let me show you a trick" — with the tool of its trade in hand, once a day. */
    public static String teach(VillageFolkEntity f, Player p) {
        AssistantEntity.StationTask t = f.stationTask();
        if (t == AssistantEntity.StationTask.NONE) return "A trick? I've no trade to learn it for yet.";
        ItemStack held = p.getMainHandItem();
        if (!usedIn(t, held)) {
            return "Show me with the right tool in your hand — " + toolFor(t) + ", for " + t.label + ".";
        }
        UUID village = f.ownerId();
        long d = day(f);
        if (village != null && done(village, "taught/" + f.getUUID(), d, 1)) return "One lesson a day — my head's full!";
        int before = f.tradeLevel(t);
        f.creditTrade(60 + 10 * Math.max(0, 20 - before));
        int after = f.tradeLevel(t);
        String name = p.getName().getString();
        f.persona().feelFor(p.getUUID(), name, 3);
        f.persona().remember(d, name + " showed me a trick of the " + t.label + "'s trade", 3);
        return after > before ? "Oh! I see it now. I'm a better " + t.label + " for that — level " + after + "."
            : "Huh, clever. I'll try it that way. Thank you, " + name + ".";
    }

    private static boolean usedIn(AssistantEntity.StationTask t, ItemStack s) {
        Budget.Kit k = Budget.kitOf(s);
        return switch (t) {
            case FARM -> k == Budget.Kit.HOE;
            case MINE -> k == Budget.Kit.PICKAXE;
            case WOOD -> k == Budget.Kit.AXE;
            case GUARD, HUNT -> k == Budget.Kit.SWORD || k == Budget.Kit.BOW;
            case FISH -> k == Budget.Kit.ROD;
            case RANCH -> k == Budget.Kit.SHEARS || s.is(Items.LEAD);
            case SMELT, SMITH -> s.is(Items.IRON_INGOT) || k == Budget.Kit.PICKAXE;
            default -> !s.isEmpty() && k != null;
        };
    }

    private static String toolFor(AssistantEntity.StationTask t) {
        return switch (t) {
            case FARM -> "a hoe";
            case MINE -> "a pickaxe";
            case WOOD -> "an axe";
            case GUARD, HUNT -> "a sword or a bow";
            case FISH -> "a fishing rod";
            case RANCH -> "shears or a lead";
            case SMELT, SMITH -> "an iron ingot";
            default -> "the tool of the trade";
        };
    }

    // ------------------------------------------------------------------ 6. a godparent

    /** "May I be godparent to this child?" — asked of the child itself. */
    public static String godparent(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "A godparent? I'd need a village first.";
        if (!f.isBaby()) return "Ask a child that — I'm all grown up!";
        String key = "godparent/" + f.getUUID();
        String have = Ledger.note(village, key);
        String name = p.getName().getString();
        if (have != null && !have.isEmpty()) {
            return have.equals(p.getUUID().toString()) ? "You're my godparent! I tell everyone." : "I've got a godparent already. Sorry!";
        }
        Standing.Title title = Standing.of(village, p.getUUID(), f.level().getGameTime()).title();
        if (!title.atLeast(Standing.Title.FRIEND)) return "Mum says I mustn't go with strangers. Get to know us first!";
        Ledger.note(village, key, p.getUUID().toString());
        long d = day(f);
        f.persona().feelFor(p.getUUID(), name, 40);
        f.persona().remember(d, name + " became my godparent", 8);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity g && !g.isBaby() && f.life().parents().contains(g.displayNameCap())) {
                g.persona().feelFor(p.getUUID(), name, 6);
            }
        }
        Standing.stir(village, p.getUUID());
        Villages.tell(village, d, name + " stood godparent to young " + f.displayNameCap());
        return "You'll be my godparent? Really? I'm going to tell everybody!";
    }

    // ------------------------------------------------------------------ 7. a keepsake

    /** "Something to remember you by?" — a close friend's keepsake, once. */
    public static String keepsake(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        String name = p.getName().getString();
        if (f.persona().affinity(p.getUUID()) < 50) return "A keepsake? We hardly know each other yet.";
        if (village != null && done(village, "keepsake/" + f.getUUID() + "/" + p.getUUID(), day(f), 100000)) {
            return "I gave you my keepsake already. Keep it safe for me.";
        }
        ItemStack charm = new ItemStack(switch (f.stationTask()) {
            case FARM -> Items.SUNFLOWER;
            case MINE -> Items.AMETHYST_SHARD;
            case WOOD -> Items.OAK_SAPLING;
            case FISH -> Items.NAUTILUS_SHELL;
            case GUARD -> Items.IRON_NUGGET;
            case BEEKEEP -> Items.HONEYCOMB;
            default -> Items.POPPY;
        });
        charm.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s keepsake"));
        charm.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal("Given to " + name + " by " + f.displayNameCap() + " of " + (village == null ? "nowhere" : Villages.name(village))),
            Component.literal("\"To remember me by.\""))));
        give(p, charm);
        f.persona().remember(day(f), "I gave " + name + " a keepsake", 6);
        return "Here — I've had this since I was small. I'd like you to have it. Don't lose it!";
    }

    // ------------------------------------------------------------------ 8. letters from friends

    /** Once a day for a village (Commerce.daily): its closest friend of each player nearby writes, once a week. */
    static void letters(ServerLevel level, Villages.Village v, long day) {
        if (day % 7 != 2) return;
        for (Player p : level.players()) {
            if (p.distanceToSqr(v.centre().getX(), v.centre().getY(), v.centre().getZ()) > 160.0 * 160.0) continue;
            VillageFolkEntity best = null;
            int most = 39;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
                int aff = f.persona().affinity(p.getUUID());
                if (aff > most) { most = aff; best = f; }
            }
            if (best == null || done(v.id(), "wrote/" + p.getUUID(), day, 7)) continue;
            String name = p.getName().getString();
            List<Villages.News> news = Villages.news(v.id());
            String latest = news.isEmpty() ? "all is quiet here" : news.get(news.size() - 1).text();
            ItemStack letter = Services.book("A letter from " + best.displayNameCap(), best.displayNameCap(),
                "Dear " + name + ",\n\nJust a line to say we were thinking of you. The latest here: " + latest + ".\n\n"
                    + FolkTalk.pick(best.getRandom(), "Come and see us soon.", "Mind you look after yourself.", "The kettle's always on.")
                    + "\n\nYour friend,\n" + best.displayNameCap(), List.of());
            give(p, letter);
            p.displayClientMessage(Component.literal(best.displayNameCap() + " of " + Villages.name(v.id()) + " has written you a letter."), false);
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) Advancements.letter(sp);   // [batchG] You've Got Post
        }
    }

    // ------------------------------------------------------------------ 9. made to order

    /** Orders taken: by player, what is being made, when it is ready, the craftsman's village, and the
     *  craftsman's name and level at its trade when it took the order (Craftsmanship: how well it is made). */
    record Order(UUID village, Item item, long ready, String maker, int skill) {}

    private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();

    /** Has this player a craftsman's order in with this village? */
    public static boolean hasOrder(UUID player, UUID village) {
        Order o = ORDERS.get(player);
        return o != null && o.village().equals(village);
    }

    /** "Could you make me an iron sword?" — or "is my order ready?". */
    public static String order(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "I can't make anything without a workshop.";
        long d = day(f);
        Order mine = ORDERS.get(p.getUUID());
        if (mine != null && mine.village().equals(village)) {
            ItemStack made = new ItemStack(mine.item());
            if (d < mine.ready()) return "Your " + made.getHoverName().getString().toLowerCase(Locale.ROOT) + "? " + mine.maker()
                + " is still at it — come back tomorrow.";
            ORDERS.remove(p.getUUID());
            String what = made.getHoverName().getString().toLowerCase(Locale.ROOT);
            // As well made as the hand that took the order (Craftsmanship).
            Craftsmanship.Grade grade = Craftsmanship.grade(mine.skill());
            made = Craftsmanship.finish(level, made, mine.skill(), mine.maker());
            give(p, made);
            String how = switch (grade) {
                case ROUGH -> " It's a learner's work, mind — it won't last like a master's.";
                case PLAIN -> "";
                case GOOD -> " Good work, that: it'll outlast most.";
                case FINE -> " Fine work: it'll last a third longer than most.";
                case MASTER -> " A master's work: tempered, and it'll last half as long again.";
            };
            return "Here's your " + what + ", fresh from " + mine.maker() + ". Wear it well." + how;
        }
        if (!craftsman(f.stationTask())) return "That's work for a smith, a tailor or a carpenter — ask one of them.";
        Item want = Services.itemNamed(text);
        if (want == null) return "What shall I make? Say what — \"make me an iron sword\".";
        // Only what its hand is up to: a beginner at the anvil forges tools, not a diamond chestplate.
        int skill = f.veteranLevel();
        if (!Craftsmanship.canMake(skill, want)) return Craftsmanship.beyond(f, want);
        RecipeHolder<?> recipe = recipeFor(level, want);
        if (recipe == null) return "I don't know how to make that, I'm afraid.";
        // The makings: what the player carries first, then what the village can spare.
        List<Ingredient> parts = new ArrayList<>();
        for (Ingredient ing : recipe.value().getIngredients()) if (!ing.isEmpty()) parts.add(ing);
        List<String> missing = new ArrayList<>();
        List<Runnable> takes = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        if (v == null) return "I can't make anything without a workshop.";
        Map<Item, Integer> fromStores = new java.util.HashMap<>();
        Map<Integer, Integer> fromPlayer = new java.util.HashMap<>();
        for (Ingredient ing : parts) {
            int slot = playerSlot(p, ing, fromPlayer);
            if (slot >= 0) { fromPlayer.merge(slot, 1, Integer::sum); continue; }
            Item found = null;
            for (ItemStack opt : ing.getItems()) {
                ItemStack one = opt.copyWithCount(1);
                int already = fromStores.getOrDefault(opt.getItem(), 0);
                if (Budget.spare(level, village, one) > already) { found = opt.getItem(); break; }
            }
            if (found == null) {
                ItemStack[] opts = ing.getItems();
                missing.add(opts.length == 0 ? "something" : opts[0].getHoverName().getString().toLowerCase(Locale.ROOT));
                continue;
            }
            fromStores.merge(found, 1, Integer::sum);
        }
        if (!missing.isEmpty()) {
            return "I'd need " + String.join(", ", missing) + " for that, and we can't spare it. Bring the makings and I'll set to.";
        }
        double storesWorth = 0;
        for (Map.Entry<Item, Integer> e : fromStores.entrySet()) storesWorth += Prices.each(e.getKey()) * e.getValue();
        // The makings, and the work: a master's work dearer than a beginner's.
        double work = Prices.each(want) * 0.2 * Craftsmanship.grade(skill).worth;
        int price = (int) Math.max(1, Math.round(storesWorth * Budget.PLAYER_MARKUP + work));
        price = haggled(village, p.getUUID(), d, price);
        if (Market.coinsHeld(p) < price) return "That'd be " + coins(price) + ", makings and work. You've " + Market.coinsHeld(p) + ".";
        // Take it all.
        for (Map.Entry<Integer, Integer> e : fromPlayer.entrySet()) p.getInventory().getItem(e.getKey()).shrink(e.getValue());
        for (Map.Entry<Item, Integer> e : fromStores.entrySet()) {
            Item it = e.getKey();
            TownWork.take(level, v, s -> s.is(it), e.getValue());
        }
        Market.payOut(p, price);
        Ledger.addCoins(village, price);
        Economy.sold(village, price);
        Budget.forget(village);
        ORDERS.put(p.getUUID(), new Order(village, want, d + 1, f.displayNameCap(), skill));
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
        return "Right you are: " + coins(price) + ", and it'll be ready tomorrow. Come and ask for it.";
    }

    private static boolean craftsman(AssistantEntity.StationTask t) {
        return t == AssistantEntity.StationTask.SMITH || t == AssistantEntity.StationTask.TAILOR
            || t == AssistantEntity.StationTask.SMELT || t == AssistantEntity.StationTask.ENCHANT || t == AssistantEntity.StationTask.SHOP;
    }

    /** The first crafting recipe that makes this. */
    @Nullable
    static RecipeHolder<?> recipeFor(ServerLevel level, Item want) {
        var registries = level.registryAccess();
        for (RecipeHolder<?> h : level.getServer().getRecipeManager().getRecipes()) {
            Recipe<?> r = h.value();
            if (r.getType() != RecipeType.CRAFTING) continue;
            ItemStack out;
            try {
                out = r.getResultItem(registries);
            } catch (RuntimeException e) {
                continue;
            }
            if (out != null && out.is(want) && !r.getIngredients().isEmpty()) return h;
        }
        return null;
    }

    private static int playerSlot(Player p, Ingredient ing, Map<Integer, Integer> used) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !ing.test(s)) continue;
            if (s.getCount() > used.getOrDefault(i, 0)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 10. repairs

    /** "Could you mend this?" — the worn thing in the player's hand, at the smith's (PlayerServices: the metal
     *  out of the stores at the market's price, a unit for every quarter of the wear, and a fee for the work). */
    public static String repair(VillageFolkEntity f, Player p) {
        return PlayerServices.repair(f, p);
    }

    // ------------------------------------------------------------------ 11. a feast on you

    /** "I'll pay for tonight's feast." — ten coins and one for every mouth. */
    public static String sponsor(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "A feast? For whom?";
        long d = day(f);
        int cost = 10 + Villages.headcount(village);
        if (Gatherings.sponsored(village, d)) return "Somebody's paying for tonight already. Come and eat with us!";
        if (Market.coinsHeld(p) < cost) return "A feast for the whole village would be " + coins(cost) + ". You've " + Market.coinsHeld(p) + ".";
        Market.payOut(p, cost);
        Ledger.addCoins(village, cost);
        String name = p.getName().getString();
        Gatherings.sponsor(village, name, d);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity g) g.persona().feelFor(p.getUUID(), name, 2);
        }
        Standing.stir(village, p.getUUID());
        Villages.tell(village, d, name + " paid for a feast for the whole village tonight");
        return "A feast? Tonight? On you? Everybody will be there — thank you, " + name + "!";
    }

    /** "1 coin", "5 coins". */
    static String coins(int n) { return n + (n == 1 ? " coin" : " coins"); }
}
