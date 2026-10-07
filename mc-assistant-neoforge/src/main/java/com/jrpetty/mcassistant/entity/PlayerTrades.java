package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.CivicItems;
import com.jrpetty.mcassistant.item.TrainedRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [player-civic] Apprentice yourself: a player learns a trade from a master folk.
 *
 * <p><b>Who to ask.</b> A folk of level twenty-five or more at a trade that teaches (Lessons: the smith, the farmer,
 * the miner, the cave dweller, the enchanter, the tailor, the brewer, the cook) takes a player on when asked ("Will
 * you take me as your apprentice?"), if it does not dislike them and the town is not owed. Its fee is six coins and
 * a coin for every five of its levels, paid into its own purse; a player without the coin pays in kind, the first
 * lesson's work half as much again. It hands over an apprentice's journal out of the stores (or binds one there and
 * then from the stores' book, feather, ink and leather), and the town hears of it.
 *
 * <p><b>Lessons.</b> Three to a trade, each a real piece of the work done with the master: brought to it (and made
 * into something there and then, the rest going into the stores as the apprentice's keep), or dug, harvested,
 * smelted, made, brewed, enchanted or put down with the master near: in its town, or at the apprentice's side
 * (asked for the lesson, the master walks along for the work out in the world). Away from the master nothing counts.
 *
 * <p><b>What they open.</b> A title ("Apprentice Smith", "Journeyman Smith", "Master Smith", shown after the
 * player's name), a bonus of the trade's own (cheaper made-to-order at the smith's and the tailor's, crops that grow
 * faster near the farmer and a fuller harvest, more ore for the miner and the caver, quicker digging below ground,
 * eyes for the dark, a lapis and a level back at the enchanting table, longer-lasting potions, a dish more from the
 * fire, longer-lasting leathers), and for the smith, the brewer and the cook a recipe only a taught player can craft
 * (TrainedRecipe): the reinforced pickaxe, the brewer's stout, the farmhouse pie. The game's advancements mark the
 * first lesson, the recipe and the master's title.
 *
 * <p><b>The master.</b> It likes its apprentice the better for every lesson, speaks of their progress when asked
 * about itself, carries them on its card, and at the end gives a graduation piece of its own make with its mark on
 * it, out of the town's stores (owed until the stores can run to it). Everything is kept with the world.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerTrades extends SavedData {

    private static final String ID = "mc_assistant_player_trades";

    /** The lesson that opens a trade's own recipe. */
    static final int RECIPE_AT = 2;
    /** How near the master must be for out-in-the-world work to count (or the work done in its town). */
    static final double NEAR = 32.0;
    /** A young apprentice's journal: the trade's experience it writes up in it, a day. */
    static final int JOURNAL_XP = 25;

    /** One player's apprenticeship in one trade. */
    public static final class Course {
        StationTask trade = StationTask.NONE;
        UUID master;
        String masterName = "";
        UUID village;
        String apprentice = "";
        int done, progress;
        long started;
        boolean inKind, giftOwed, walking;

        Lessons.Course plan() {
            return Lessons.of(trade);
        }

        boolean finished() {
            return done >= 3;
        }

        /** How many the lesson in hand asks for: half as much again for the first, paid in kind. */
        int need() {
            int n = plan().lesson(done).count();
            return done == 0 && inKind ? n * 3 / 2 : n;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Trade", trade.name());
            t.putUUID("Master", master);
            t.putString("MasterName", masterName);
            t.putUUID("Village", village);
            t.putString("Apprentice", apprentice);
            t.putInt("Done", done);
            t.putInt("Progress", progress);
            t.putLong("Started", started);
            t.putBoolean("InKind", inKind);
            t.putBoolean("GiftOwed", giftOwed);
            return t;
        }

        @Nullable
        static Course load(CompoundTag t) {
            try {
                Course c = new Course();
                c.trade = StationTask.valueOf(t.getString("Trade"));
                c.master = t.getUUID("Master");
                c.masterName = t.getString("MasterName");
                c.village = t.getUUID("Village");
                c.apprentice = t.getString("Apprentice");
                c.done = t.getInt("Done");
                c.progress = t.getInt("Progress");
                c.started = t.getLong("Started");
                c.inKind = t.getBoolean("InKind");
                c.giftOwed = t.getBoolean("GiftOwed");
                return c.plan() == null ? null : c;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final Map<UUID, List<Course>> players = new HashMap<>();
    /** With no world to keep it in (a test before the server is up). */
    private static final Map<UUID, List<Course>> LOOSE = new ConcurrentHashMap<>();
    /** When a player was last told its master was not by (so it is told once a minute, not every block). */
    private static final Map<UUID, Long> TOLD = new ConcurrentHashMap<>();

    public PlayerTrades() {}

    @Nullable
    static PlayerTrades of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(PlayerTrades::new, PlayerTrades::load, null), ID);
    }

    static List<Course> courses(UUID player) {
        PlayerTrades d = of();
        Map<UUID, List<Course>> m = d == null ? LOOSE : d.players;
        return m.computeIfAbsent(player, k -> new ArrayList<>());
    }

    /** The player's courses, without writing an empty list down for one who has none. */
    static List<Course> mine(UUID player) {
        List<Course> l = everyone().get(player);
        return l == null ? List.of() : l;
    }

    /** Is the player learning (or has it learned) any trade? */
    static boolean learning(Player p) {
        return !mine(p.getUUID()).isEmpty();
    }

    /** Every player's courses (for a master's card and talk). */
    static Map<UUID, List<Course>> everyone() {
        PlayerTrades d = of();
        return d == null ? LOOSE : d.players;
    }

    static void dirty() {
        PlayerTrades d = of();
        if (d != null) d.setDirty();
    }

    public static void resetForTests() {
        PlayerTrades d = of();
        if (d != null) {
            d.players.clear();
            d.setDirty();
        }
        LOOSE.clear();
        TOLD.clear();
    }

    public static PlayerTrades load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerTrades d = new PlayerTrades();
        for (Tag t : tag.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (!one.hasUUID("Id")) continue;
            List<Course> list = new ArrayList<>();
            for (Tag c : one.getList("Courses", Tag.TAG_COMPOUND)) {
                Course k = Course.load((CompoundTag) c);
                if (k != null) list.add(k);
            }
            d.players.put(one.getUUID("Id"), list);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, List<Course>> e : players.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            ListTag list = new ListTag();
            for (Course c : e.getValue()) list.add(c.save());
            one.put("Courses", list);
            all.add(one);
        }
        tag.put("Players", all);
        return tag;
    }

    // ------------------------------------------------------------------ what a player has learned

    /** The player's course in this trade, or null. */
    @Nullable
    static Course course(UUID player, StationTask t) {
        for (Course c : mine(player)) if (c.trade == t) return c;
        return null;
    }

    /** The lessons this player has done in this trade (nought to three). */
    public static int level(UUID player, StationTask t) {
        Course c = course(player, t);
        return c == null ? 0 : c.done;
    }

    /** Has this player been taught the recipe of the trade with this key ("smith", "brew", "cook")? (TrainedRecipe) */
    public static boolean knows(UUID player, String key) {
        Lessons.Course plan = Lessons.byKey(key);
        return plan != null && level(player, plan.trade()) >= RECIPE_AT;
    }

    /** Has the player this trade's bonus of the given lesson? */
    static boolean has(@Nullable Player p, StationTask t, int lesson) {
        return p != null && level(p.getUUID(), t) >= lesson;
    }

    /** The best the player has made of a trade: "Master Smith", or null. */
    @Nullable
    public static String title(UUID player) {
        Course best = null;
        for (Course c : mine(player)) if (c.done > 0 && (best == null || c.done > best.done)) best = c;
        return best == null ? null : best.plan().title(best.done);
    }

    // ------------------------------------------------------------------ asking a master

    /** A coin for every five levels, and six for its time. */
    static int fee(int level) {
        return 6 + level / 5;
    }

    /** The best master of a trade in the town, or null. */
    @Nullable
    static VillageFolkEntity masterOf(UUID village, StationTask t) {
        // [interviews] The trade's master chosen at interview, while it is a master at it.
        VillageFolkEntity chosen = Interviews.holder(village, "master:" + t.name());
        if (chosen != null && chosen.stationTask() == t && chosen.tradeLevel(t) >= Lessons.MASTER && !chosen.isShowcase()) return chosen;
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.stationTask() != t) continue;
            if (f.tradeLevel(t) >= Lessons.MASTER && (best == null || f.tradeLevel(t) > best.tradeLevel(t))) best = f;
        }
        return best;
    }

    /** "Will you take me as your apprentice?" — the folk's answer, and the deed if it is yes. */
    public static String ask(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Take you on? I've no workshop without a village.";
        StationTask t = f.stationTask();
        Lessons.Course plan = Lessons.of(t);
        String name = p.getName().getString();
        if (plan == null) {
            return "My trade takes no apprentices. Ask a master smith, farmer, miner, cave dweller, enchanter, tailor, brewer or cook.";
        }
        int lv = f.tradeLevel(t);
        if (lv < Lessons.MASTER) {
            VillageFolkEntity m = masterOf(village, t);
            return "Me? I'm level " + lv + " at it — still learning myself. A master takes apprentices (level " + Lessons.MASTER + ")"
                + (m != null ? ": ask " + m.displayNameCap() + "." : ", and we've none here yet.");
        }
        Course mine = course(p.getUUID(), t);
        if (mine != null) {
            if (!mine.master.equals(f.getUUID())) {
                // The old master gone from its town (dead, or moved away): another master of the trade there sees it through.
                if (!mine.finished() && village.equals(mine.village) && Elections.loaded(village, mine.master) == null) {
                    String was = mine.masterName;
                    mine.master = f.getUUID();
                    mine.masterName = f.displayNameCap();
                    dirty();
                    f.persona().feelFor(p.getUUID(), name, 3);
                    return "You were " + was + "'s apprentice? Then I'll see you through. " + next(mine);
                }
                return "You're learning " + t.label + " from " + mine.masterName + " already. A trade has one master.";
            }
            return mine.finished() ? "You're a " + plan.title(3) + " yourself now. There's nothing left I can teach you."
                : "You're my apprentice already. " + next(mine);
        }
        long day = f.level().getDayTime() / 24000L;
        if (Laws.banished(village, p.getUUID(), day) || Laws.owes(village, p.getUUID()) > 0) {
            return "Not while you owe this town. Settle with it first.";
        }
        int aff = f.persona().affinity(p.getUUID());
        if (aff < 0) return "Have you in my workshop? I don't know you well enough for that.";
        int fee = fee(lv);
        boolean paid = Market.coinsHeld(p) >= fee;
        if (paid) {
            Market.payOut(p, fee);
            f.earn(fee);
        }
        Course c = new Course();
        c.trade = t;
        c.master = f.getUUID();
        c.masterName = f.displayNameCap();
        c.village = village;
        c.apprentice = name;
        c.started = day;
        c.inKind = !paid;
        courses(p.getUUID()).add(c);
        dirty();
        f.persona().feelFor(p.getUUID(), name, 5);
        f.persona().remember(day, "I took " + name + " on as my apprentice in " + t.label, 5);
        Villages.tell(village, day, f.displayNameCap() + " the " + t.title.toLowerCase(Locale.ROOT) + " took " + name + " on as an apprentice");
        Villages.Village v = Villages.get(village);
        ItemStack journal = v == null ? ItemStack.EMPTY : TradeGoods.journalFor(level, v);
        if (!journal.isEmpty()) give(p, journal);
        if (p instanceof ServerPlayer sp) Advancements.grant(sp, "village/apprentice");
        return FolkTalk.pick(f.getRandom(), "An apprentice? ", "You want to learn " + t.label + "? ", "Learn the trade? ")
            + (paid ? "Very well: " + fee + " coins for my time, and you'll work for the rest. "
                : "You've not the " + fee + " coins for my fee, so you'll pay in kind: your first lesson's work half as much again. ")
            + (journal.isEmpty() ? "Keep your lessons in a journal (a book, a feather, an ink sac and a strap of leather). " : "Here's a journal to keep your lessons in. ")
            + "Your first lesson: " + plan.lesson(0).asks() + (c.inKind ? " (" + c.need() + ", in kind)" : "");
    }

    /** The lesson in hand, as the master puts it, and how far along it is. */
    static String next(Course c) {
        Lessons.Lesson l = c.plan().lesson(c.done);
        return "Lesson " + (c.done + 1) + " of 3: " + l.asks() + (l.task() == Lessons.Task.BRING ? "" : " So far: " + c.progress + " of " + c.need() + ".");
    }

    /**
     * "What's my lesson?" / "Here's what you asked for": a lesson brought is taken and made into something there and
     * then; a lesson out in the world has the master walk along; a graduation piece owed is handed over if the stores
     * can run to it now.
     */
    public static String lesson(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "Lessons? I've no workshop.";
        Course c = null;
        for (Course k : mine(p.getUUID())) if (k.master.equals(f.getUUID())) c = k;
        if (c == null) {
            Lessons.Course plan = Lessons.of(f.stationTask());
            if (plan != null && f.tradeLevel(f.stationTask()) >= Lessons.MASTER) return "You're not my apprentice. Ask me to take you on, if you want to learn.";
            return "I've no lessons for you. Ask a master of a trade to take you on as an apprentice.";
        }
        String name = p.getName().getString();
        if (c.finished()) {
            if (c.giftOwed) {
                return gift(level, c, p) ? "There — your piece, at last. Wear my mark with pride, " + name + "."
                    : "Your graduation piece is still owed: the stores can't run to it yet. I've not forgotten.";
            }
            return "You've learned all I can teach you, " + c.plan().title(3) + " " + name + ".";
        }
        Lessons.Lesson l = c.plan().lesson(c.done);
        if (l.task() == Lessons.Task.BRING) {
            int need = c.need();
            int have = carried(p, l.item());
            if (have < need) return l.asks() + " You've " + have + " of the " + need + " on you.";
            List<ItemStack> taken = takeFrom(p, l.item(), need);
            String made = brought(level, Villages.get(village), f, c, p, taken);
            int before = c.done;
            advance(level, p, c, need);
            return made + (c.done > before ? " That's your lesson learned: " + c.plan().title(c.done) + "." : "");
        }
        if (l.task().outside()) {
            c.walking = true;
            f.startFollowing(p);
            return l.asks() + " So far: " + c.progress + " of " + c.need() + ". Lead on — I'll come with you.";
        }
        boolean near = f.distanceToSqr(p) <= NEAR * NEAR;
        return l.asks() + " So far: " + c.progress + " of " + c.need() + "." + (near ? " Go on, then; I'm watching."
            : " Do it here in town, or with me by.");
    }

    /** What the master makes of a lesson brought to it: a piece for the apprentice, and the rest into the stores. */
    static String brought(ServerLevel level, @Nullable Villages.Village v, VillageFolkEntity master, Course c, Player p, List<ItemStack> taken) {
        int n = 0;
        for (ItemStack s : taken) n += s.getCount();
        ItemStack first = taken.isEmpty() ? ItemStack.EMPTY : taken.get(0);
        String said;
        int used = 0;
        ItemStack piece = ItemStack.EMPTY;
        int skill = master.tradeLevel(c.trade);
        String by = master.displayNameCap();
        switch (c.trade) {
            case SMITH -> {
                // Three bars and a haft of the stores' wood: the apprentice's first pick, forged before its eyes.
                if (v != null && n >= 3 && Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 1)) {
                    piece = Craftsmanship.finish(level, new ItemStack(Items.IRON_PICKAXE), skill, by);
                    used = 3;
                    said = "Watch the colour of the iron… there. An iron pickaxe, and it's yours.";
                } else {
                    said = "No wood in the stores for a haft — take three bars back and haft it yourself. Mind how I drew it out.";
                    piece = new ItemStack(Items.IRON_INGOT, 3);
                    used = 3;
                }
            }
            case ENCHANT -> {
                if (v != null && n >= 3 && Crafts.take(level, v, s -> s.is(Items.BOOK), 1)) {
                    piece = book(level, Enchantments.UNBREAKING, 1);
                    used = 3;
                    said = "Three of your lapis, a book from the shelves, and the words… there: Unbreaking, and it's yours.";
                } else {
                    said = "No book on the shelves to show you on. Watch the words, then, and the lapis goes to the stores.";
                }
            }
            case TAILOR -> {
                Item bed = bedFor(first);
                if (v != null && n >= 3 && bed != null && Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 3)) {
                    piece = new ItemStack(bed);
                    used = 3;
                    said = "Three of your wool over three planks, stitched tight: a bed, and it's yours.";
                } else {
                    said = "No planks in the stores for a frame. Watch the stitch, then; the wool goes to the stores.";
                }
            }
            case BREW -> {
                if (v != null && n >= 2 && Crafts.take(level, v, s -> s.is(Items.GLASS_BOTTLE), 1)) {
                    if (Crafts.take(level, v, s -> s.is(Items.SUGAR), 1)) {
                        piece = new ItemStack(CivicItems.BREWERS_STOUT.get());
                        used = 2;
                        said = "Two of your wheat in the mash, a spoon of sugar, into the bottle… a stout. Taste it.";
                    } else {
                        Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
                        said = "No sugar in the stores. Watch the mash, then; the wheat goes to the stores.";
                    }
                } else {
                    said = "No bottles in the stores. Watch the mash, then; the wheat goes to the stores.";
                }
            }
            default -> said = "Watch closely, then.";
        }
        if (!piece.isEmpty()) give(p, piece);
        // The rest is the apprentice's keep: into the stores the master works out of.
        int left = n - used;
        if (v != null) {
            for (ItemStack s : taken) {
                if (left <= 0) break;
                int k = Math.min(left, s.getCount());
                Crafts.store(level, v, s.copyWithCount(k));
                left -= k;
            }
            if (n - used > 0) said += " The rest goes into the stores, for your keep.";
        } else if (left > 0) {
            for (ItemStack s : taken) {
                if (left <= 0) break;
                int k = Math.min(left, s.getCount());
                give(p, s.copyWithCount(k));
                left -= k;
            }
        }
        master.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return said;
    }

    @Nullable
    private static Item bedFor(ItemStack wool) {
        if (wool.isEmpty()) return null;
        String path = BuiltInRegistries.ITEM.getKey(wool.getItem()).getPath();
        if (!path.endsWith("_wool")) return null;
        Item bed = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path.replace("_wool", "_bed")));
        return bed == Items.AIR ? null : bed;
    }

    static ItemStack book(ServerLevel level, ResourceKey<Enchantment> which, int rank) {
        var reg = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        Optional<? extends net.minecraft.core.Holder<Enchantment>> h = reg.getHolder(which);
        if (h.isEmpty()) return new ItemStack(Items.BOOK);
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(h.get(), rank));
    }

    // ------------------------------------------------------------------ the lessons, counted

    @Nullable
    static VillageFolkEntity master(ServerLevel level, Course c) {
        Entity e = level.getEntity(c.master);
        return e instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** Is the master with the apprentice for this work: at its side, or (not deep underground) both in its town? */
    static boolean withMaster(ServerLevel level, Player p, Course c, BlockPos at, boolean deep) {
        VillageFolkEntity m = master(level, c);
        if (m == null) return false;
        if (m.distanceToSqr(p) <= NEAR * NEAR) return true;
        if (deep) return false;
        Villages.Village v = Villages.get(c.village);
        return v != null && at.closerThan(v.centre(), Villages.VILLAGE_RANGE) && m.blockPosition().closerThan(v.centre(), Villages.VILLAGE_RANGE);
    }

    /** Below ground: under the sixtieth level, with rock over it. */
    static boolean deep(ServerLevel level, BlockPos at) {
        return at.getY() < 60 && !level.canSeeSky(at);
    }

    /** Some of the work a lesson asks for, done: counted toward every course it fits, with the master by. */
    static void count(ServerLevel level, Player p, Lessons.Task task, BlockPos at, int n, Predicate<Lessons.Lesson> fits) {
        if (n <= 0) return;
        for (Course c : new ArrayList<>(mine(p.getUUID()))) {
            if (c.finished()) continue;
            Lessons.Lesson l = c.plan().lesson(c.done);
            if (l.task() != task || !fits.test(l)) continue;
            if (l.deep() && !deep(level, at)) continue;
            if (!withMaster(level, p, c, at, l.deep())) {
                long now = level.getGameTime();
                if (now - TOLD.getOrDefault(p.getUUID(), -10000L) > 1200L) {
                    TOLD.put(p.getUUID(), now);
                    p.displayClientMessage(Component.literal(c.masterName + " isn't by: a lesson counts with your master near, or in its town.")
                        .withStyle(ChatFormatting.GRAY), true);
                }
                continue;
            }
            advance(level, p, c, n);
        }
    }

    /** Work toward the lesson in hand: the lesson learned once there is enough of it. */
    static void advance(ServerLevel level, Player p, Course c, int n) {
        c.progress += n;
        dirty();
        if (c.progress >= c.need()) {
            complete(level, p, c);
            return;
        }
        p.displayClientMessage(Component.literal(c.plan().word() + "'s lesson: " + c.progress + " of " + c.need()
            + " — " + c.masterName + " is watching.").withStyle(ChatFormatting.GOLD), true);
    }

    /** A lesson learned: the title, what it opens, the master's pleasure in it; at the last, the master's piece. */
    static void complete(ServerLevel level, Player p, Course c) {
        Lessons.Course plan = c.plan();
        Lessons.Lesson l = plan.lesson(c.done);
        c.done = Math.min(3, c.done + 1);
        c.progress = 0;
        c.walking = false;
        dirty();
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        String title = plan.title(c.done);
        p.sendSystemMessage(Component.literal(title + "! " + capital(l.opens()) + ".").withStyle(ChatFormatting.GOLD));
        VillageFolkEntity m = master(level, c);
        if (m != null) {
            m.persona().feelFor(p.getUUID(), name, c.done >= 3 ? 10 : 6);
            m.persona().remember(day, name + " learned lesson " + c.done + " of " + c.trade.label + " from me", c.done >= 3 ? 6 : 3);
            if (m.isFollowing(p)) m.stopFollowing();
            FolkTalk.speak(m, c.done >= 3 ? FolkTalk.pick(m.getRandom(), "A master " + plan.word().toLowerCase(Locale.ROOT) + "! I couldn't be prouder.",
                "That's it, " + name + ": nothing left I can teach you.")
                : FolkTalk.pick(m.getRandom(), "Well done, " + name + "!", "That's the knack of it.", "Good work. On to the next."));
        }
        if (p instanceof ServerPlayer sp) {
            if (c.done == RECIPE_AT) {
                List<RecipeHolder<?>> mine = new ArrayList<>();
                for (RecipeHolder<?> h : level.getRecipeManager().getRecipes()) {
                    if (h.value() instanceof TrainedRecipe tr && tr.trade().equals(plan.key())) mine.add(h);
                }
                if (!mine.isEmpty()) sp.awardRecipes(mine);
                Advancements.grant(sp, "village/journeyman");
            }
            if (c.done >= 3) Advancements.grant(sp, "village/master_craftsman");
            Citizens.refresh(sp);
        }
        if (c.done >= 3) {
            Villages.tell(c.village, day, name + " finished an apprenticeship in " + c.trade.label + " under " + c.masterName
                + ", and is a " + title + " now");
            if (!gift(level, c, p)) {
                c.giftOwed = true;
                if (m != null) FolkTalk.speak(m, "Your graduation piece is owed — the stores can't run to it today. Ask me for it.");
            }
        }
    }

    /** The master's graduation piece, out of the town's stores with its mark on it; false if the stores cannot run to it. */
    static boolean gift(ServerLevel level, Course c, Player p) {
        VillageFolkEntity m = master(level, c);
        Villages.Village v = Villages.get(c.village);
        if (m == null || v == null) return false;
        ItemStack made = switch (c.trade) {
            case SMITH -> forge(level, v, Items.IRON_AXE, 3);
            case FARM -> forge(level, v, Items.IRON_HOE, 2);
            case MINE -> forge(level, v, Items.IRON_PICKAXE, 3);
            case CAVE -> forge(level, v, Items.IRON_SWORD, 2);
            case TAILOR -> Crafts.take(level, v, s -> s.is(Items.LEATHER), 8) ? new ItemStack(Items.LEATHER_CHESTPLATE) : ItemStack.EMPTY;
            case ENCHANT -> {
                if (!Crafts.take(level, v, s -> s.is(Items.BOOK), 1)) yield ItemStack.EMPTY;
                if (!Crafts.take(level, v, s -> s.is(Items.LAPIS_LAZULI), 2)) {
                    Crafts.store(level, v, new ItemStack(Items.BOOK));
                    yield ItemStack.EMPTY;
                }
                yield book(level, Enchantments.UNBREAKING, 2);
            }
            case BREW -> fromStores(level, v, CivicItems.BREWERS_STOUT.get(), 3);
            case COOK -> fromStores(level, v, CivicItems.FARMHOUSE_PIE.get(), 2);
            default -> ItemStack.EMPTY;
        };
        if (made.isEmpty()) return false;
        String by = m.displayNameCap();
        if (made.getMaxStackSize() == 1 && made.isDamageableItem()) {
            made = Craftsmanship.finish(level, made, m.tradeLevel(c.trade), by);
        }
        ItemLore lore = made.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        made.set(DataComponents.LORE, lore.withLineAdded(Component.literal("A graduation gift from " + by + " to "
            + p.getName().getString()).withStyle(ChatFormatting.GOLD)));
        give(p, made);
        c.giftOwed = false;
        dirty();
        FolkTalk.speak(m, FolkTalk.pick(m.getRandom(), "Here — made with my own hands. Carry my mark well.", "Something to remember your master by."));
        m.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    /** A tool out of the stores' iron and a plank's worth of haft, or nothing. */
    private static ItemStack forge(ServerLevel level, Villages.Village v, Item tool, int bars) {
        if (Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) < bars || Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) < 1) {
            return ItemStack.EMPTY;
        }
        if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), bars)) return ItemStack.EMPTY;
        if (!Crafts.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 1)) {
            Crafts.store(level, v, new ItemStack(Items.IRON_INGOT, bars));
            return ItemStack.EMPTY;
        }
        return new ItemStack(tool);
    }

    /** So many of what the master makes, out of the stores (or made there and then of the stores' makings: TradeGoods). */
    private static ItemStack fromStores(ServerLevel level, Villages.Village v, Item what, int n) {
        int have = Crafts.stock(level, v, s -> s.is(what));
        while (have < n && TradeGoods.makeOne(level, v, what)) have = Crafts.stock(level, v, s -> s.is(what));
        if (have < n || !Crafts.take(level, v, s -> s.is(what), n)) return ItemStack.EMPTY;
        return new ItemStack(what, n);
    }

    // ------------------------------------------------------------------ the master's side

    /** The players learning from this folk, and how far along. */
    static List<Course> apprenticesOf(VillageFolkEntity f) {
        List<Course> out = new ArrayList<>();
        for (List<Course> list : everyone().values()) for (Course c : list) if (f.getUUID().equals(c.master)) out.add(c);
        return out;
    }

    /** The folk's card: its apprentices (players, and the town's young ones at its side), or the trade a child is learning. */
    public static String cardLine(VillageFolkEntity f) {
        if (f.isBaby()) {
            if (f.apprenticedTo() == StationTask.NONE) return "";
            boolean journal = f.countCarried(s -> s.is(CivicItems.APPRENTICE_JOURNAL.get())) > 0;
            return "learning " + f.apprenticedTo().label + " at a grown-up's side" + (journal ? ", and keeps a journal of it" : "");
        }
        List<String> parts = new ArrayList<>();
        for (Course c : apprenticesOf(f)) {
            parts.add(c.apprentice + " (" + (c.done == 0 ? "new" : c.plan().title(c.done).split(" ")[0].toLowerCase(Locale.ROOT))
                + ", " + c.done + " of 3 lessons)");
        }
        UUID village = f.ownerId();
        if (village != null && f.stationTask() != StationTask.NONE) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity k && k.isBaby() && k.apprenticedTo() == f.stationTask() && k.hobbyNow != null
                        && k.hobbyNow.contains(f.displayNameCap())) parts.add("young " + k.displayNameCap() + " at its side");
                if (parts.size() >= 5) break;
            }
        }
        if (parts.isEmpty() && Lessons.of(f.stationTask()) != null && f.tradeLevel(f.stationTask()) >= Lessons.MASTER) {
            return "none yet — a master, it takes apprentices (fee " + fee(f.tradeLevel(f.stationTask())) + " coins)";
        }
        return String.join("; ", parts);
    }

    /** A master speaks of its apprentices when asked about itself (FolkTalk, after the answer). */
    public static String mention(VillageFolkEntity f, Player p, TalkTopic topic, String said) {
        if (topic != TalkTopic.ABOUT && topic != TalkTopic.DOING && topic != TalkTopic.KNACK && topic != TalkTopic.HOW) return said;
        List<Course> mine = apprenticesOf(f);
        if (mine.isEmpty()) return said;
        Course yours = null, best = null;
        for (Course c : mine) {
            if (p.getName().getString().equals(c.apprentice)) yours = c;
            if (best == null || c.done > best.done) best = c;
        }
        if (yours != null) {
            return said + " " + (yours.finished() ? "And you, " + yours.plan().title(3) + " — the best I ever taught."
                : "And you're coming along: " + yours.done + " of 3 lessons. " + yours.plan().lesson(yours.done).asks());
        }
        String how = best.finished() ? "a " + best.plan().title(3) + " now, I'm proud to say"
            : best.done == 0 ? "only just started" : best.done + (best.done == 1 ? " lesson" : " lessons") + " done, and coming along";
        return said + " My apprentice " + best.apprentice + " is " + how + ".";
    }

    // ------------------------------------------------------------------ the bonuses

    /** Made-to-order at a craftsman's (Dealings.order): the smith's and the tailor's cheaper to one who learned the trade. */
    public static int orderPrice(Player p, StationTask maker, int price) {
        int off = 0;
        if (maker == StationTask.SMITH) off = has(p, StationTask.SMITH, 3) ? 25 : has(p, StationTask.SMITH, 1) ? 16 : 0;
        if (maker == StationTask.TAILOR && has(p, StationTask.TAILOR, 1)) off = 16;
        return off == 0 ? price : Math.max(1, price - (int) Math.round(price * off / 100.0));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.isCanceled()) return;
        Player p = e.getPlayer();
        if (p == null || !learning(p)) return;
        BlockState st = e.getState();
        count(level, p, Lessons.Task.BREAK, e.getPos(), 1, l -> l.block() != null && l.block().test(st));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.isCanceled() || !(e.getEntity() instanceof Player p)) return;
        if (!learning(p)) return;
        BlockState st = e.getPlacedBlock();
        count(level, p, Lessons.Task.PLACE, e.getPos(), 1, l -> l.block() != null && l.block().test(st));
    }

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level)) return;
        ItemStack made = e.getCrafting();
        if (made.isEmpty() || !learning(p)) return;
        // A taught hand's leathers last a third longer, and carry its mark (the tailor's second lesson).
        if (Lessons.leather(made) && has(p, StationTask.TAILOR, 2) && Craftsmanship.gradeOf(made) == null) {
            Craftsmanship.finish(level, made, 25, p.getName().getString());
        }
        count(level, p, Lessons.Task.CRAFT, p.blockPosition(), made.getCount(), l -> l.item() != null && l.item().test(made));
    }

    @SubscribeEvent
    public static void onSmelt(PlayerEvent.ItemSmeltedEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level)) return;
        ItemStack out = e.getSmelting();
        if (out.isEmpty() || !learning(p)) return;
        // The cook's knack: one dish in five comes out of the fire with one more.
        if (out.get(DataComponents.FOOD) != null && has(p, StationTask.COOK, 1)) {
            int extra = out.getCount() / 5 + (level.getRandom().nextInt(5) < out.getCount() % 5 ? 1 : 0);
            if (extra > 0) give(p, out.copyWithCount(extra));
        }
        count(level, p, Lessons.Task.SMELT, p.blockPosition(), out.getCount(), l -> l.item() != null && l.item().test(out));
    }

    @SubscribeEvent
    public static void onBrew(PlayerBrewedPotionEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level)) return;
        ItemStack s = e.getStack();
        if (s.isEmpty() || !learning(p)) return;
        if (has(p, StationTask.BREW, 1)) longer(s);
        count(level, p, Lessons.Task.BREW, p.blockPosition(), Math.max(1, s.getCount()), l -> l.item() != null && l.item().test(s));
    }

    /** The brewer's knack: what it brews lasts a quarter longer (the instant ones as they are). */
    static void longer(ItemStack s) {
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null || s.get(DataComponents.CUSTOM_DATA) != null) return;
        List<MobEffectInstance> all = new ArrayList<>();
        boolean any = false;
        for (MobEffectInstance m : pc.getAllEffects()) {
            if (m.getEffect().value().isInstantenous() || m.getDuration() <= 1) {
                all.add(m);
                continue;
            }
            all.add(new MobEffectInstance(m.getEffect(), m.getDuration() * 5 / 4, m.getAmplifier(), m.isAmbient(), m.isVisible(), m.showIcon()));
            any = true;
        }
        if (!any) return;
        Component name = s.getHoverName();
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(pc.getColor()), all));
        s.set(DataComponents.ITEM_NAME, Component.literal(name.getString()));
        ItemLore lore = s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        s.set(DataComponents.LORE, lore.withLineAdded(Component.literal("Brewed by a taught hand: a quarter longer").withStyle(ChatFormatting.GRAY)));
    }

    @SubscribeEvent
    public static void onEnchant(PlayerEnchantItemEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level) || !learning(p)) return;
        // The enchanter's knacks: a lapis back, then a level back as well.
        if (has(p, StationTask.ENCHANT, 1)) give(p, new ItemStack(Items.LAPIS_LAZULI));
        if (has(p, StationTask.ENCHANT, 2)) p.giveExperienceLevels(1);
        ItemStack s = e.getEnchantedItem();
        count(level, p, Lessons.Task.ENCHANT, p.blockPosition(), 1, l -> l.item() != null && l.item().test(s));
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !(e.getSource().getEntity() instanceof Player p)) return;
        if (!learning(p)) return;
        Entity dead = e.getEntity();
        count(level, p, Lessons.Task.KILL, dead.blockPosition(), 1, l -> l.mob() != null && l.mob().test(dead));
    }

    /** The miner's and the caver's eye: one ore in five gives one more; the farmer's: one ripe harvest in four. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof Player p) || e.getDrops().isEmpty()) return;
        ServerLevel level = e.getLevel();
        BlockState st = e.getState();
        boolean ore = st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);
        boolean crop = st.getBlock() instanceof CropBlock c && c.isMaxAge(st);
        int chance = ore && (has(p, StationTask.MINE, 1) || has(p, StationTask.CAVE, 2)) ? 5 : crop && has(p, StationTask.FARM, 2) ? 4 : 0;
        if (chance == 0 || level.getRandom().nextInt(chance) != 0) return;
        ItemStack first = e.getDrops().get(0).getItem();
        if (first.isEmpty() || ore && first.is(st.getBlock().asItem())) return;        // silk touch: the block itself, no more
        BlockPos at = e.getPos();
        e.getDrops().add(new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, first.copyWithCount(1)));
    }

    /** Crops grow faster near a player the farmer taught (its first lesson): a third of their tries come off. */
    @SubscribeEvent
    public static void onCrop(CropGrowEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos at = e.getPos();
        Player p = level.getNearestPlayer(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 12.0, false);
        if (p == null || !has(p, StationTask.FARM, 1)) return;
        if (level.getRandom().nextInt(3) == 0) e.setResult(CropGrowEvent.Pre.Result.GROW);
    }

    /** Every few seconds: the knacks below ground, and a master walking with its apprentice for a lesson. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 80 != 17 || !(p.level() instanceof ServerLevel level)) return;
        List<Course> mine = everyone().get(p.getUUID());
        if (mine == null || mine.isEmpty()) return;
        BlockPos at = p.blockPosition();
        if (deep(level, at)) {
            if (has(p, StationTask.MINE, 2)) p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 200, 0, true, false, true));
            if (has(p, StationTask.CAVE, 1)) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, true, false, true));
        }
        long t = level.getDayTime() % 24000L;
        for (Course c : mine) {
            if (!c.walking || c.finished()) continue;
            VillageFolkEntity m = master(level, c);
            if (m == null) continue;
            if (t >= 12500L || m.distanceToSqr(p) > 64.0 * 64.0) {
                // Dark, or left far behind: it goes home, and the lesson waits for tomorrow.
                c.walking = false;
                m.stopFollowing();
                FolkTalk.speak(m, t >= 12500L ? "It's getting dark — I'm for home. We'll go on tomorrow." : "I can't keep up! I'm going home.");
                p.displayClientMessage(Component.literal(c.masterName + " went home. Ask for your lesson again to have them along.")
                    .withStyle(ChatFormatting.GRAY), true);
                continue;
            }
            if (!m.isFollowing(p)) m.startFollowing(p);
        }
    }

    // ------------------------------------------------------------------ the page

    /** The player's trades, on a page (the apprentice's journal, /village trades). */
    public static void openPage(ServerPlayer p) {
        PlayerCivic.send(p, "Your trades", page(p), List.of());
    }

    static String page(ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        List<Course> mine = mine(p.getUUID());
        String best = title(p.getUUID());
        if (best != null) sb.append("Title: ").append(best).append("\n");
        if (mine.isEmpty()) {
            sb.append("No trades yet: ask a master of a trade (level ").append(Lessons.MASTER)
                .append(" or more) \"Will you take me as your apprentice?\". The smith, the farmer, the miner, the cave dweller, ")
                .append("the enchanter, the tailor, the brewer and the cook all teach.\n");
        }
        for (Course c : mine) {
            Lessons.Course plan = c.plan();
            sb.append("\n").append(plan.word()).append(": under ").append(c.masterName).append(" of ").append(Villages.name(c.village))
                .append(" — ").append(c.done == 0 ? "new" : plan.title(c.done)).append(", ").append(c.done).append(" of 3 lessons.\n");
            List<String> open = new ArrayList<>();
            for (int i = 0; i < c.done; i++) open.add(plan.lesson(i).opens());
            if (!open.isEmpty()) sb.append("Unlocked: ").append(String.join("; ", open)).append(".\n");
            if (c.finished()) {
                sb.append(c.giftOwed ? "Graduation piece: owed (" + plan.gift() + ") — ask " + c.masterName + " for it.\n"
                    : "Graduated: " + plan.gift() + ", with " + c.masterName + "'s mark.\n");
            } else {
                Lessons.Lesson l = plan.lesson(c.done);
                sb.append("Next: \"").append(l.asks()).append("\" — ").append(l.task() == Lessons.Task.BRING
                    ? "bring " + c.need() + " to " + c.masterName : c.progress + " of " + c.need()
                    + (l.deep() ? ", below ground with " + c.masterName + " by" : ", with " + c.masterName + " by or in its town")).append(".\n");
                sb.append("It opens: ").append(l.opens()).append(".\n");
            }
        }
        // Masters near enough to ask.
        Villages.Village v = Villages.nearest(p.level(), p.blockPosition(), Villages.VILLAGE_RANGE * 2);
        if (v != null) {
            List<String> masters = new ArrayList<>();
            for (Lessons.Course plan : Lessons.all()) {
                VillageFolkEntity m = masterOf(v.id(), plan.trade());
                if (m != null) masters.add(m.displayNameCap() + " (" + plan.word().toLowerCase(Locale.ROOT) + ", level " + m.tradeLevel(plan.trade()) + ")");
            }
            sb.append("\nMasters in ").append(Villages.name(v.id())).append(": ").append(masters.isEmpty() ? "none yet." : String.join(", ", masters) + ".");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    static int carried(Player p, @Nullable Predicate<ItemStack> what) {
        if (what == null) return 0;
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    static List<ItemStack> takeFrom(Player p, @Nullable Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        if (what == null) return out;
        for (int i = 0; i < p.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.copyWithCount(k));
            s.shrink(k);
            n -= k;
        }
        p.getInventory().setChanged();
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the smoke runs

    /**
     * The smoke stage (/village trades stage, as a player): the town's master smith (a smith brought up to a master's
     * level if the town has none) takes the player on, the first lesson brought (twenty iron, handed over for it), the
     * second counted as done, so the reinforced pickaxe is the player's to make; a crafting table set down beside the
     * player with the makings for one in its pack, and the master come to watch. Returns lines for the camera:
     * "VIEW x y z ax ay az" (from beside the table, at the master and the table) and how the lessons stand.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, ServerPlayer p) {
        List<String> out = new ArrayList<>();
        VillageFolkEntity m = masterOf(v.id(), StationTask.SMITH);
        if (m == null) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()
                        && (m == null || f.stationTask() == StationTask.SMITH || f.stationTask() == StationTask.SMELT)) m = f;
            }
            if (m == null) {
                out.add("NOSMITH");
                return out;
            }
            if (m.stationTask() != StationTask.SMITH) m.setJob(StationTask.SMITH);
            m.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(30));
        }
        BlockPos stand = p.blockPosition();
        m.teleportTo(stand.getX() + 2.5, stand.getY(), stand.getZ() + 0.5);
        m.ensurePersona();
        give(p, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), fee(m.tradeLevel(StationTask.SMITH))));
        out.add("ASK " + ask(m, p));
        Course c = course(p.getUUID(), StationTask.SMITH);
        if (c != null && c.done == 0) {
            give(p, new ItemStack(Items.IRON_INGOT, c.need()));
            out.add("LESSON1 " + lesson(m, p));
        }
        c = course(p.getUUID(), StationTask.SMITH);
        if (c != null && c.done == 1) advance(level, p, c, c.need() - c.progress);
        BlockPos table = stand.relative(p.getDirection(), 2);
        if (level.getBlockState(table).canBeReplaced()) level.setBlockAndUpdate(table, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
        give(p, new ItemStack(Items.IRON_PICKAXE));
        give(p, new ItemStack(Items.IRON_INGOT, 3));
        give(p, new ItemStack(Items.COPPER_INGOT, 1));
        m.startFollowing(p);
        out.add("DONE " + level(p.getUUID(), StationTask.SMITH) + " KNOWS " + knows(p.getUUID(), "smith") + " TITLE " + title(p.getUUID()));
        BlockPos eye = stand.relative(p.getDirection().getOpposite(), 3).above(2);
        out.add("VIEW " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + table.getX() + " " + table.getY() + " " + table.getZ());
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the lessons this player has done in this trade, and the progress on the one in hand. */
    public static int[] doneForTests(UUID player, StationTask t) {
        Course c = course(player, t);
        return c == null ? new int[]{ -1, 0 } : new int[]{ c.done, c.progress };
    }
}
