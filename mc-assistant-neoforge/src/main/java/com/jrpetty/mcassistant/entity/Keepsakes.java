package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.IndividualItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [individual] What a folk carries and will not part with: its keepsake; and the things its years ask of it, its
 * spectacles and its walking stick.
 *
 * <ul>
 * <li><b>A keepsake</b>, one of its own: a feather from the first hen it kept, a pressed flower from the day it came,
 *     the first coin it ever earned, a lucky stone, a wooden toy a parent carved, the first book it read, the ring its
 *     partner gave it, a drawing by its child. Each a real thing, come by the real way: the feather, the flower, the
 *     stone and the book out of its own pack or the stores; the coin out of its own purse, its first wage; the toy, the
 *     ring and the drawing made by their own recipes out of the stores (QuestItems.make), the ring at the smith's hand,
 *     the drawing at its child's. Named and storied, marked its own (Homes.keepsake) so nothing ever banks it, sells
 *     it, auctions it, gives it away as a present or puts it away in a chest: it is carried, always. When it dies, its
 *     eldest child carries it after it ("its mother's ring").</li>
 * <li><b>Spectacles</b> (IndividualItems), for an old folk who reads, the scholar and the librarian first: made by the
 *     smith out of the stores, paid for to the smith out of its purse as far as it can, and worn from then on.</li>
 * <li><b>A walking stick</b> for the oldest (eighty and on): a stick out of the stores, its own, in its hand.</li>
 * </ul>
 */
public final class Keepsakes {

    private Keepsakes() {}

    public enum Kind {
        FEATHER("a feather"), FLOWER("a pressed flower"), COIN("a coin"), STONE("a lucky stone"), TOY("a wooden toy"),
        BOOK("a battered old book"), RING("a ring"), DRAWING("a child's drawing");

        public final String word;

        Kind(String word) { this.word = word; }
    }

    /** In a carried thing's own data: what it is to the folk ("treasure", "stick", "specs", "book", "whittle"). */
    static final String CARRIED = "mca_carried";

    /** Marked its own and carried (never put away in a chest): its keepsake, its stick, its spectacles, its book. */
    static void carry(ItemStack s, VillageFolkEntity owner, String role) {
        Homes.keepsake(s, owner);
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putString(CARRIED, role.isEmpty() ? "kept" : role);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    /** Is this one a folk carries always (Homes.moving leaves it in its pack)? */
    public static boolean isCarried(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(CARRIED);
    }

    static String role(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? "" : d.copyTag().getString(CARRIED);
    }

    /** A folk's keepsake proper. */
    public static boolean isTreasure(ItemStack s) {
        return !s.isEmpty() && "treasure".equals(role(s));
    }

    // ------------------------------------------------------------------ rolled

    static void roll(VillageFolkEntity f, Individual.Self s, RandomSource r, @Nullable VillageFolkEntity a, @Nullable VillageFolkEntity b) {
        String town = Individual.town(f);
        if (a != null) {
            VillageFolkEntity carver = r.nextBoolean() || b == null ? a : b;
            s.keepsake = r.nextInt(3) == 0 ? Kind.FEATHER : r.nextInt(2) == 0 ? Kind.STONE : Kind.TOY;
            s.keepsakeStory = switch (s.keepsake) {
                case TOY -> "carved for it by " + carver.displayNameCap();
                case FEATHER -> "found the first time it went out of the door";
                default -> "picked out of the stream when it was small";
            };
            return;
        }
        Kind[] founders = {Kind.FEATHER, Kind.FLOWER, Kind.COIN, Kind.STONE, Kind.TOY, Kind.BOOK, Kind.RING};
        s.keepsake = founders[r.nextInt(founders.length)];
        if (s.keepsake == Kind.BOOK && !s.literate && r.nextBoolean()) s.keepsake = Kind.STONE;
        String home = s.born.isEmpty() ? "home" : s.born.replaceFirst("^born ", "");
        s.keepsakeStory = switch (s.keepsake) {
            case FEATHER -> "from the first hen it ever kept";
            case FLOWER -> "picked the day it came to " + town;
            case COIN -> "the first coin it ever earned here";
            case STONE -> "a pebble from the river, " + home;
            case TOY -> "the toy it played with as a child";
            case BOOK -> "the first book it ever read";
            case RING -> "its grandmother's, it says";
            case DRAWING -> "drawn by its child";
        };
    }

    // ------------------------------------------------------------------ come by

    /** Every ten seconds or so (Individual.tick): its keepsake come by, its spectacles, its stick. */
    static void tick(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        if (!s.rolled || f.ownerId() == null) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        long day = level.getDayTime() / 24000L;
        if (s.keepsakeDay >= 0 && carried(f) == null) {
            s.keepsakeDay = -1;                               // gone: stolen, or lost in the river
            f.persona().remember(day, "I lost " + s.keepsake.word + " I'd carried for years", 5);
        }
        // One look a day for each, at most: the stores and the bench are not asked every ten seconds for what they lack.
        if (s.keepsakeDay < 0 && tryToday(f, "keep", day)) comeBy(f, level, v, s, day);
        if (wantsSpectacles(f) && !wearsSpectacles(f) && tryToday(f, "specs", day)) spectacles(f, level, v, day);
        if (!f.isBaby() && f.ageYears() >= 80 && !hasStick(f) && tryToday(f, "stick", day)) stick(f, level, v);
        Individual.refreshLook(f);
    }

    private static void comeBy(VillageFolkEntity f, ServerLevel level, Villages.Village v, Individual.Self s, long day) {
        ItemStack got = switch (s.keepsake) {
            case FEATHER -> ownOrStores(f, level, v, st -> st.is(Items.FEATHER));
            case FLOWER -> ownOrStores(f, level, v, st -> st.is(ItemTags.SMALL_FLOWERS));
            case STONE -> ownOrStores(f, level, v, st -> st.is(Items.FLINT) || st.is(Items.AMETHYST_SHARD) || st.is(Items.QUARTZ));
            case BOOK -> ownOrStores(f, level, v, st -> st.is(Items.BOOK));
            case COIN -> f.earnedInAll() > 0 && f.spend(1) ? new ItemStack(McAssistantMod.VILLAGE_COIN.get()) : ItemStack.EMPTY;
            case TOY -> QuestItems.make(level, v, maker(f, level), McAssistantMod.WOODEN_TOY.get());
            case RING -> f.life().partner() == null && s.keepsakeStory.contains("gave") ? ItemStack.EMPTY
                : QuestItems.make(level, v, smith(f), McAssistantMod.HEIRLOOM_RING.get());
            case DRAWING -> f.life().children() == 0 ? ItemStack.EMPTY : QuestItems.make(level, v, null, McAssistantMod.CHILDS_DRAWING.get());
        };
        if (got.isEmpty()) return;
        treasure(f, got.copyWithCount(1), s.keepsake, s.keepsakeStory);
        s.keepsakeDay = day;
        f.persona().remember(day, "I have " + s.keepsake.word + " of my own: " + s.keepsakeStory, 3);
    }

    private static final java.util.Map<String, Long> TRIED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The first try today at this? (Then not again till tomorrow.) */
    private static boolean tryToday(VillageFolkEntity f, String what, long day) {
        String key = f.getUUID() + "/" + what;
        Long last = TRIED.get(key);
        if (last != null && last == day) return false;
        TRIED.put(key, day);
        if (TRIED.size() > 8192) TRIED.clear();
        return true;
    }

    public static void resetForTests() {
        TRIED.clear();
    }

    /** Make this its keepsake: named, storied, marked, in its pack. */
    static void treasure(VillageFolkEntity f, ItemStack s, Kind kind, String story) {
        s.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s " + kind.word.replaceFirst("^an? ", "")));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(capFirst(story)).withColor(0xA89A7A),
            Component.literal("Carried always; never to be parted with.").withColor(0x8A7E66))));
        carry(s, f, "treasure");
        ItemStack left = f.insertGiven(s);
        if (!left.isEmpty()) {
            // A full pack: room is made for it, the least of what it carries going to the stores.
            for (int i = 0; i < f.getInventoryItems().size(); i++) {
                ItemStack o = f.getInventoryItems().get(i);
                if (!Homes.isKeepsake(o) && !o.isDamageableItem() && f.level() instanceof ServerLevel level) {
                    Villages.Village v = Villages.get(f.ownerId());
                    if (v != null) Crafts.store(level, v, o.copy());
                    f.getInventoryItems().set(i, left);
                    return;
                }
            }
        }
    }

    /** One of these out of its own pack (not a thing it already keeps), else out of the stores. */
    private static ItemStack ownOrStores(VillageFolkEntity f, ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (ItemStack st : f.getInventoryItems()) {
            if (!st.isEmpty() && what.test(st) && !Homes.isKeepsake(st)) {
                ItemStack one = st.copyWithCount(1);
                st.shrink(1);
                return one;
            }
        }
        return Crafts.takeOne(level, v, what);
    }

    @Nullable
    private static VillageFolkEntity maker(VillageFolkEntity f, ServerLevel level) {
        for (UUID p : f.parentIds()) if (level.getEntity(p) instanceof VillageFolkEntity parent) return parent;
        return f;
    }

    @Nullable
    private static VillageFolkEntity smith(VillageFolkEntity f) {
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (a instanceof VillageFolkEntity o && o.stationTask() == StationTask.SMITH) return o;
        }
        return null;
    }

    /** The keepsake it carries, or null. */
    @Nullable
    public static ItemStack carried(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (isTreasure(s)) return s;
        return null;
    }

    /** Given a toy (a whittler's), its keepsake if it has none yet; else kept with its things. */
    static void give(VillageFolkEntity child, ItemStack toy, Kind kind, String story) {
        Individual.Self s = child.individual();
        if (carried(child) == null) {
            s.keepsake = kind;
            s.keepsakeStory = story;
            s.keepsakeDay = child.level().getDayTime() / 24000L;
            treasure(child, toy, kind, story);
        } else {
            Homes.keepsake(toy, child);
            child.insertGiven(toy);
        }
        child.persona().gotAGift(child.level().getDayTime() / 24000L, story);
    }

    /** The youngest child in the town with no keepsake of its own yet (else with no toy), for a whittler's toy. */
    @Nullable
    static VillageFolkEntity childWanting(VillageFolkEntity f, ServerLevel level) {
        VillageFolkEntity best = null;
        boolean bestBare = false;
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (!(a instanceof VillageFolkEntity c) || !c.isBaby() || !c.isAlive()) continue;
            boolean bare = carried(c) == null;
            boolean hasToy = c.countCarried(st -> st.is(McAssistantMod.WOODEN_TOY.get())) > 0;
            if (!bare && hasToy) continue;
            if (best == null || bare && !bestBare || bare == bestBare && c.bornDay() > best.bornDay()) {
                best = c;
                bestBare = bare;
            }
        }
        return best;
    }

    /**
     * Died: its keepsake to its eldest living child, to carry after it; that child's own goes in with its things.
     */
    static void handDown(VillageFolkEntity f, ServerLevel level) {
        ItemStack mine = carried(f);
        if (mine == null || f.ownerId() == null) return;
        VillageFolkEntity heir = null;
        for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
            if (!(a instanceof VillageFolkEntity c) || c == f || !c.isAlive() || !c.parentIds().contains(f.getUUID())) continue;
            if (heir == null || c.bornDay() < heir.bornDay()) heir = c;
        }
        if (heir == null) return;
        long day = level.getDayTime() / 24000L;
        Kind kind = f.individual().keepsake;
        ItemStack it = mine.copy();
        mine.setCount(0);
        ItemStack old = carried(heir);
        if (old != null) {
            CompoundTag t = old.get(DataComponents.CUSTOM_DATA).copyTag();
            t.putString(CARRIED, "kept");
            old.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        }
        Individual.Self hs = heir.individual();
        hs.keepsake = kind;
        hs.keepsakeStory = "it was " + f.displayNameCap() + "'s, its parent's; " + f.individual().keepsakeStory;
        hs.keepsakeDay = day;
        treasure(heir, it, kind, hs.keepsakeStory);
        heir.persona().remember(day, "I carry " + f.displayNameCap() + "'s " + kind.word.replaceFirst("^an? ", "") + " now", 8);
    }

    // ------------------------------------------------------------------ spectacles and a stick

    /** An old folk who reads: past fifty-five and fond of books, or the scholar, the librarian, the teacher. */
    public static boolean wantsSpectacles(VillageFolkEntity f) {
        if (f.isBaby() || f.ageYears() < 55) return false;
        Individual.Self s = f.individual();
        if (!s.literate) return false;
        StationTask t = f.stationTask();
        boolean bookish = f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING || s.habits.contains(Habits.Habit.READING)
            || f.life().has(Social.Trait.CURIOUS) || t == StationTask.ENCHANT || t == StationTask.STORE || School.isTeacher(f)
            || f.ownerId() != null && f.getUUID().equals(com.jrpetty.mcassistant.village.LibraryRecords.shelf(f.ownerId()).librarian);
        return bookish;
    }

    public static boolean wearsSpectacles(VillageFolkEntity f) {
        return f.countCarried(st -> st.is(IndividualItems.SPECTACLES.get())) > 0;
    }

    /** An old folk at close work in its spectacles sees the fine work again: five in a hundred quicker. */
    public static int spectaclesPercent(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        boolean close = t == StationTask.ENCHANT || t == StationTask.TAILOR || t == StationTask.SMITH || t == StationTask.STORE
            || t == StationTask.BREW || t == StationTask.SHOP || t == StationTask.BANK;
        return close && !f.isBaby() && f.ageYears() >= 55 && wearsSpectacles(f) ? 5 : 0;
    }

    public static boolean hasStick(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (s.is(Items.STICK) && "stick".equals(role(s))) return true;
        return false;
    }

    /** A pair made by the smith out of the stores (gold and glass), paid for to the smith as far as its purse goes. */
    private static void spectacles(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day) {
        VillageFolkEntity smith = smith(f);
        ItemStack pair = QuestItems.make(level, v, smith, IndividualItems.SPECTACLES.get());
        if (pair.isEmpty()) return;
        int price = Math.max(1, (int) Math.round(Prices.of(new ItemStack(IndividualItems.SPECTACLES.get()))));
        int paid = Math.min(price, f.purse());
        if (paid > 0 && f.spend(paid)) {
            if (smith != null) smith.earn(paid);
            else Ledger.addCoins(v.id(), paid);
        }
        pair.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s spectacles"));
        carry(pair, f, "specs");
        f.insertGiven(pair);
        f.persona().remember(day, "I got my spectacles" + (smith != null ? " from " + smith.displayNameCap() + " the smith" : ""), 4);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Spectacles! I can read the small print again!", "Well, look at that — I can see!"));
    }

    private static void stick(VillageFolkEntity f, ServerLevel level, Villages.Village v) {
        ItemStack one = ItemStack.EMPTY;
        for (ItemStack st : f.getInventoryItems()) {
            if (st.is(Items.STICK) && !Homes.isKeepsake(st)) { one = st.split(1); break; }
        }
        if (one.isEmpty()) one = Crafts.takeOne(level, v, st -> st.is(Items.STICK));
        if (one.isEmpty()) return;
        one.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s walking stick"));
        carry(one, f, "stick");
        f.insertGiven(one);
    }

    /** A player's pair of spectacles, handed over (FolkTalk gift): an old folk with none puts them on. */
    public static boolean takeSpectacles(VillageFolkEntity f, ItemStack given) {
        if (!given.is(IndividualItems.SPECTACLES.get()) || f.isBaby() || f.ageYears() < 45 || wearsSpectacles(f)) return false;
        ItemStack pair = given.copyWithCount(1);
        carry(pair, f, "specs");
        f.insertGiven(pair);
        Individual.refreshLook(f);
        return true;
    }

    // ------------------------------------------------------------------ in words

    static String what(VillageFolkEntity f) {
        return f.individual().keepsake.word;
    }

    static String story(VillageFolkEntity f) {
        return capFirst(f.individual().keepsakeStory);
    }

    static String cardLine(VillageFolkEntity f) {
        Individual.Self s = f.individual();
        String line = s.keepsake.word + ", " + s.keepsakeStory;
        return s.keepsakeDay >= 0 && carried(f) != null ? line + " — carried always" : line + " (it has not come by it yet)";
    }

    private static String capFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tests: this keepsake, not yet come by. */
    public static void kindForTests(VillageFolkEntity f, Kind kind, String story) {
        Individual.ensure(f);
        f.individual().keepsake = kind;
        f.individual().keepsakeStory = story;
        f.individual().keepsakeDay = -1;
    }

    /** Tests: the keepsake comes by now, as on its own round. */
    public static void comeByForTests(VillageFolkEntity f) {
        if (f.level() instanceof ServerLevel level) tick(f, level);
    }

    /** Tests: everything it carries for good. */
    public static List<ItemStack> carriedForTests(VillageFolkEntity f) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : f.getInventoryItems()) if (isCarried(s)) out.add(s);
        return out;
    }
}
