package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Something a folk asks a player to do.
 *
 * <p>Ask "can I help?" and a folk tells you what it needs. Usually that is what the
 * village is short of for its next age — the iron for the watch's armour, the logs
 * for the next house, food for a hungry winter. A miner asks for metal, a farmer
 * for food and a woodcutter for timber. With nothing short, it asks for something
 * of its own: the diamond it has always dreamed of, flowers for its garden, a book,
 * its favourite food. On a night with monsters about, it asks you to clear them off.
 *
 * <p>Bring it, and the village is the richer for it: what you hand over goes into
 * the stores, toward the next age. You come away with experience, something from
 * the village's own stores, and its gratitude — and for a personal favour, a
 * keepsake the folk made itself, with its name on it.
 */
public final class Errands {

    private Errands() {}

    public static final String SUPPLY = "supply", WANT = "want", HUNT = "hunt";
    private static final int LASTS_DAYS = 3;

    // ------------------------------------------------------------------ what

    static Predicate<ItemStack> matcher(String item) {
        return switch (item) {
            case "iron" -> s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON);
            case "coal" -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            case "logs" -> s -> s.is(ItemTags.LOGS);
            case "stone" -> s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE);
            case "food" -> s -> s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH)
                && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO);
            case "flowers" -> s -> s.is(ItemTags.SMALL_FLOWERS);
            case "fish" -> s -> s.is(ItemTags.FISHES);
            case "bed" -> s -> s.is(ItemTags.BEDS);
            default -> {
                net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(item));
                yield s -> !s.isEmpty() && s.is(it);
            }
        };
    }

    static String words(String item, int count) {
        boolean one = count == 1;
        return switch (item) {
            case "iron" -> count + " iron (ingots or raw)";
            case "coal" -> count + " coal";
            case "logs" -> count + " logs";
            case "stone" -> count + " cobblestone";
            case "food" -> count + " food";
            case "diamond" -> one ? "a diamond" : count + " diamonds";
            case "obsidian" -> count + " obsidian";
            case "flowers" -> count + " flowers";
            case "fish" -> count + " fish";
            case "book" -> one ? "a book" : count + " books";
            case "note_block" -> "a note block";
            case "bed" -> one ? "a bed" : count + " beds";
            default -> count + " " + item.replace('_', ' ');
        };
    }

    /** "bring the village 8 iron (ingots or raw)", "kill 5 monsters near the village". */
    public static String describe(Persona me) {
        if (!me.hasErrand()) return "";
        String head = switch (me.errandKind()) {
            case SUPPLY -> "bring the village " + words(me.errandItem(), me.errandCount());
            case WANT -> "bring " + words(me.errandItem(), me.errandCount());
            default -> "kill " + me.errandCount() + " monsters near the village";
        };
        return head + " (" + me.errandDone() + "/" + me.errandCount() + ")";
    }

    /** Is the errand this player's, and still standing? An old one lapses. */
    static boolean live(VillageFolkEntity f, UUID player) {
        Persona me = f.persona();
        if (!me.hasErrand()) return false;
        long day = f.level().getDayTime() / 24000L;
        if (day - me.errandDay() > LASTS_DAYS) {
            me.clearErrand();
            return false;
        }
        return player.equals(me.errandFor());
    }

    // ------------------------------------------------------------------ asking

    /** "Can I help?" — what it would like you to do, or what it already asked. */
    static String offer(VillageFolkEntity f, Player p) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        long day = f.level().getDayTime() / 24000L;
        if (live(f, p.getUUID())) {
            return pickOf(r, "You're already helping — ", "Still waiting on you to ") + describe(me) + ".";
        }
        if (me.hasErrand() && day - me.errandDay() <= LASTS_DAYS) {
            return "Kind of you, but somebody else is already helping me with something.";
        }
        UUID village = f.ownerId();
        // Nowhere to sleep comes before anything: a bed for itself.
        // (once it has spent a night without one: a folk that only came today has not.)
        if (village != null && f.bedPos() == null && !f.isBaby() && me.since() >= 0 && day > me.since()
                && me.sleptDay < day) {
            me.setErrand(WANT, p.getUUID(), "bed", 1, day);
            return pickOf(r, "Since you ask… ", "Well, there is one thing. ")
                + "I've nowhere to sleep — there aren't beds enough to go round yet. Could you bring me a bed? Any colour will do.";
        }
        // What the village is short of, the folk's own trade first.
        if (village != null && f.level() instanceof ServerLevel server) {
            List<Villages.Need> needs = Villages.needs(server, village);
            Villages.Need pickNeed = null;
            for (Villages.Need n : needs) {
                if (supplyItem(n.task()) == null) continue;
                if (pickNeed == null) pickNeed = n;
                if (tradeWants(f.stationTask(), n.task())) { pickNeed = n; break; }
            }
            if (pickNeed != null) {
                String item = supplyItem(pickNeed.task());
                int count = supplyCount(item, pickNeed.amount());
                me.setErrand(SUPPLY, p.getUUID(), item, count, day);
                return pickOf(r, "Since you ask — ", "Well, there is something. ", "You could, actually. ")
                    + "We need " + pickNeed.what() + " here. Could you bring " + words(item, count) + "? "
                    + pickOf(r, "Hand them to me and I'll see they reach the stores.", "It would help the whole village.");
            }
        }
        // Something of its own.
        String want = null;
        int count = 1;
        if (me.ambition() == Persona.Ambition.DIAMOND && !me.ambitionMet()) want = "diamond";
        else if (me.hobby() == Persona.Hobby.GARDENING) { want = "flowers"; count = 6; }
        else if (me.hobby() == Persona.Hobby.READING) want = "book";
        else if (me.hobby() == Persona.Hobby.FISHING) { want = "fish"; count = 3; }
        else if (me.hobby() == Persona.Hobby.MUSIC) want = "note_block";
        else if (!me.food().equals("bread") && r.nextBoolean()) { want = me.food(); count = 3; }
        if (want != null) {
            me.setErrand(WANT, p.getUUID(), want, count, day);
            return switch (want) {
                case "diamond" -> "Could you… bring me a diamond? I've dreamed of holding one my whole life.";
                case "flowers" -> "I'm growing a garden. Could you bring me six flowers? Any kind — the more colours the better.";
                case "book" -> "I've read every book in the village twice. Could you bring me a new one?";
                case "fish" -> "Bring me three fish, would you? I want to show the others I'm not the only one who can catch them.";
                case "note_block" -> "If you could find me a note block, I'd play you something lovely.";
                default -> "Could you bring me " + words(want, count) + "? It's my favourite, and I've not had any in ages.";
            };
        }
        // The monsters, then.
        me.setErrand(HUNT, p.getUUID(), "monsters", 5, day);
        return "There's no end of monsters at the edge of the village after dark. Could you thin them out? Five would let us sleep.";
    }

    private static String pickOf(RandomSource r, String... options) {
        return options[r.nextInt(options.length)];
    }

    static String supplyItem(Villages.Task task) {
        return switch (task) {
            case IRON -> "iron";
            case COAL -> "coal";
            case LOGS -> "logs";
            case STONE -> "stone";
            case FOOD -> "food";
            case DIAMOND -> "diamond";
            case OBSIDIAN -> "obsidian";
            default -> null;
        };
    }

    private static boolean tradeWants(AssistantEntity.StationTask trade, Villages.Task task) {
        return switch (trade) {
            case MINE -> task == Villages.Task.IRON || task == Villages.Task.COAL || task == Villages.Task.DIAMOND
                || task == Villages.Task.STONE || task == Villages.Task.OBSIDIAN;
            case FARM, FISH, RANCH -> task == Villages.Task.FOOD;
            case WOOD -> task == Villages.Task.LOGS;
            case SMELT -> task == Villages.Task.COAL || task == Villages.Task.IRON;
            default -> false;
        };
    }

    static int supplyCount(String item, int short_) {
        return switch (item) {
            case "iron" -> clamp(short_ / 3, 4, 16);
            case "coal" -> clamp(short_ / 2, 8, 32);
            case "logs" -> clamp(short_ / 3, 16, 64);
            case "stone" -> clamp(short_ / 3, 24, 64);
            case "food" -> clamp(short_ / 3, 8, 32);
            case "diamond" -> clamp(short_, 1, 3);
            case "obsidian" -> clamp(short_, 2, 10);
            default -> 8;
        };
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ handing over

    /** Can this player hand anything over for the errand right now? */
    public static boolean canDeliver(VillageFolkEntity f, Player p) {
        if (!live(f, p.getUUID())) return false;
        Persona me = f.persona();
        if (HUNT.equals(me.errandKind())) return me.errandDone() >= me.errandCount();
        Predicate<ItemStack> want = matcher(me.errandItem());
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && want.test(s)) return true;
        return false;
    }

    /** "Here you are": what is wanted, out of the player's pack, into the folk's. */
    static String deliver(VillageFolkEntity f, Player p) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        if (!live(f, p.getUUID())) return pickOf(r, "Hand over what? I haven't asked you for anything.",
            "You don't owe me anything!");
        if (HUNT.equals(me.errandKind())) {
            if (me.errandDone() < me.errandCount()) {
                return "You've seen off " + me.errandDone() + " of " + me.errandCount() + " so far. Keep at it!";
            }
            return complete(f, p);
        }
        Predicate<ItemStack> want = matcher(me.errandItem());
        int need = me.errandCount() - me.errandDone();
        int took = 0;
        for (ItemStack s : p.getInventory().items) {
            if (need - took <= 0) break;
            if (s.isEmpty() || !want.test(s)) continue;
            int n = Math.min(need - took, s.getCount());
            ItemStack given = s.split(n);
            ItemStack left = f.insertItem(given);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            took += n;
        }
        if (took == 0) return "You haven't any " + words(me.errandItem(), 2).replaceFirst("^\\d+ ", "") + " on you.";
        me.errandProgress(took);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (me.errandDone() < me.errandCount()) {
            return "Thank you — that's " + me.errandDone() + " of " + me.errandCount() + ". "
                + pickOf(r, "Bring the rest when you can.", "Nearly there!");
        }
        return complete(f, p);
    }

    /** Done: the village's thanks, and the rewards. */
    private static String complete(VillageFolkEntity f, Player p) {
        Persona me = f.persona();
        RandomSource r = f.getRandom();
        long day = f.level().getDayTime() / 24000L;
        String you = p.getName().getString();
        String kind = me.errandKind();
        String what = kind.equals(HUNT) ? "cleared the monsters from the village" : "brought " + words(me.errandItem(), me.errandCount())
            .replace(" (ingots or raw)", "");
        int xp = switch (kind) {
            case SUPPLY -> 10 + switch (me.errandItem()) {
                case "iron" -> 3 * me.errandCount();
                case "diamond" -> 20 * me.errandCount();
                case "obsidian" -> 5 * me.errandCount();
                case "coal", "food" -> me.errandCount() / 2;
                default -> me.errandCount() / 4;
            };
            case WANT -> 25;
            default -> 30;
        };
        p.giveExperiencePoints(xp);
        StringBuilder said = new StringBuilder(pickOf(r, "That's everything! ", "You did it! ", "Wonderful! "));
        // Work for the village is paid work: coin from the treasury.
        UUID home = f.ownerId();
        if (home != null && !kind.equals(WANT)) {
            int wage;
            if (kind.equals(SUPPLY)) {
                Market.Good g = Market.goodFor(Quests.sample(me.errandItem()));
                wage = (int) Math.max(2, Math.min(30, Math.round((g == null ? 0.5 : g.value()) * me.errandCount() * 0.8)));
            } else {
                wage = 5;
            }
            int paid = com.jrpetty.mcassistant.village.Ledger.takeCoins(home, wage);
            if (paid < wage) {
                // The treasury short: the rest out of its own purse, as far as that goes.
                int own = Math.min(f.purse(), wage - paid);
                if (own > 0) { f.spend(own); paid += own; }
            }
            if (paid > 0) {
                ItemStack coins = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), paid);
                if (!p.getInventory().add(coins)) p.drop(coins, false);
                said.append("Here's ").append(paid).append(" coins for your trouble").append(paid < wage ? " — all I could find" : "").append(". ");
            } else {
                said.append("I've no coin to give you, I'm sorry — the treasury's empty and so's my purse. ");
            }
        }
        // A bed of its own at last: laid out at the camp, and slept in tonight.
        if ("bed".equals(me.errandItem()) && f.layGivenBed()) said.append("A bed of my own! I'll sleep well tonight. ");
        // Something from the village's own stores.
        ItemStack gift = fromTheStores(f);
        if (!gift.isEmpty()) {
            said.append("Take ").append(gift.getCount()).append(' ').append(gift.getHoverName().getString().toLowerCase())
                .append(" from the stores, with our thanks. ");
            if (!p.getInventory().add(gift)) p.drop(gift, false);
        }
        // And for a personal favour, something it made itself.
        if (kind.equals(WANT)) {
            ItemStack keepsake = keepsake(f);
            if (keepsake.isEmpty()) {
                said.append("I wish I had something to give you, but I've nothing to make it of. ");
            } else {
                said.append("And this — I made it myself. ");
                if (!p.getInventory().add(keepsake)) p.drop(keepsake, false);
            }
            if ("diamond".equals(me.errandItem()) && !me.ambitionMet()) said.append("I can't believe I'm holding a diamond. ");
        }
        me.feelFor(p.getUUID(), you, kind.equals(SUPPLY) ? 20 : 15);
        me.remember(day, you + " " + what + " for me", 7);
        UUID village = f.ownerId();
        if (village != null) {
            // The whole village hears about it.
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity g && g != f && g.persona().rolled()) {
                    g.persona().feelFor(p.getUUID(), you, kind.equals(SUPPLY) ? 4 : 2);
                }
            }
            Villages.tell(village, day, you + " " + what + " for " + f.displayNameCap());
            Standing.stir(village, p.getUUID());
        }
        if (f.level() instanceof ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                f.getX(), f.getY() + 2.0, f.getZ(), 12, 0.5, 0.4, 0.5, 0.0);
        }
        f.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE, 1.0F, 1.0F);
        me.clearErrand();
        return said.toString().trim();
    }

    /** A little of what the village has most of. */
    private static ItemStack fromTheStores(VillageFolkEntity f) {
        if (f.villageCentre() == null || f.ownerId() == null) return ItemStack.EMPTY;
        int radius = Villages.storesRadius(f.ownerId());
        Object[][] options = {
            {(Predicate<ItemStack>) s -> s.is(Items.BREAD), 6},
            {(Predicate<ItemStack>) s -> s.is(Items.BAKED_POTATO), 6},
            {(Predicate<ItemStack>) s -> s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON), 4},
            {(Predicate<ItemStack>) s -> s.is(Items.TORCH), 8},
            {(Predicate<ItemStack>) s -> s.is(ItemTags.LOGS), 16},
            {(Predicate<ItemStack>) s -> s.is(Items.COAL), 8},
        };
        for (Object[] o : options) {
            @SuppressWarnings("unchecked") Predicate<ItemStack> what = (Predicate<ItemStack>) o[0];
            int n = (Integer) o[1];
            int got = f.drawFrom(f.villageCentre(), what, n, radius);
            if (got <= 0) continue;
            for (ItemStack s : f.getInventoryItems()) {
                if (!s.isEmpty() && what.test(s)) return s.split(Math.min(got, s.getCount()));
            }
        }
        return ItemStack.EMPTY;
    }

    /** A thing a folk made with its own hands, with its name on it. */
    static ItemStack keepsake(VillageFolkEntity f) {
        Persona me = f.persona();
        ItemStack s;
        String what;
        // Made of something it has, or something out of the stores: no keepsake out of thin air.
        switch (me.hobby()) {
            case WHITTLING -> { s = made(f, x -> x.is(net.minecraft.tags.ItemTags.PLANKS), Items.BOWL); what = "carved bowl"; }
            case GARDENING -> { s = one(f, x -> x.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS)); what = "pressed flower"; }
            case FISHING -> { s = one(f, x -> x.is(Items.COOKED_COD) || x.is(Items.COOKED_SALMON) || x.is(Items.COD) || x.is(Items.SALMON)); what = "smoked fish"; }
            case READING -> { s = one(f, x -> x.is(Items.BOOK)); what = "favourite book"; }
            case STARGAZING -> { s = one(f, x -> x.is(Items.PAPER)); what = "star chart"; }
            case MUSIC -> { s = one(f, x -> x.is(Items.SUGAR_CANE)); what = "reed pipe"; }
            case CARDS -> { s = one(f, x -> x.is(Items.PAPER)); what = "lucky card"; }
            default -> { s = made(f, x -> x.is(net.minecraft.tags.ItemTags.PLANKS), Items.STICK); what = "walking stick"; }
        }
        if (s.isEmpty()) return s;
        String village = f.ownerId() == null ? "" : " of " + Villages.name(f.ownerId());
        s.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s " + what));
        s.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("A keepsake from " + f.displayNameCap() + village + ",").withStyle(net.minecraft.ChatFormatting.GRAY),
            Component.literal("for your kindness.").withStyle(net.minecraft.ChatFormatting.GRAY))));
        return s;
    }

    /** One of something out of its pack, or else out of the village's stores. */
    private static ItemStack one(VillageFolkEntity f, java.util.function.Predicate<ItemStack> what) {
        for (ItemStack s : f.getInventoryItems()) {
            if (!s.isEmpty() && what.test(s)) {
                ItemStack one = s.copyWithCount(1);
                s.shrink(1);
                return one;
            }
        }
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null || !(f.level() instanceof net.minecraft.server.level.ServerLevel level)) return ItemStack.EMPTY;
        ItemStack got = Crafts.takeOne(level, v, what);
        return got.isEmpty() ? got : got.copyWithCount(1);
    }

    /** Something made of one of these, out of its pack or the stores. */
    private static ItemStack made(VillageFolkEntity f, java.util.function.Predicate<ItemStack> of, net.minecraft.world.item.Item into) {
        return one(f, of).isEmpty() ? ItemStack.EMPTY : new ItemStack(into);
    }

    // ------------------------------------------------------------------ hunting

    /** A monster killed near a village counts for everybody there who asked this player to hunt. */
    public static void monsterKilled(ServerLevel level, Player p, net.minecraft.core.BlockPos where) {
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class,
                new net.minecraft.world.phys.AABB(where).inflate(48.0), g -> g.isAlive() && g.persona().rolled())) {
            Persona me = f.persona();
            if (HUNT.equals(me.errandKind()) && p.getUUID().equals(me.errandFor()) && me.errandDone() < me.errandCount()) {
                me.errandProgress(1);
                if (me.errandDone() == me.errandCount()) {
                    FolkTalk.speak(f, "That's " + me.errandCount() + "! Come and see me, " + p.getName().getString() + "!");
                }
            }
        }
    }
}
