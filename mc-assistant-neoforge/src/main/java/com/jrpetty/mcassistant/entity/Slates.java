package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.item.SlateItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [leisure] The slate and chalk (item/SlateItem): the schoolchildren's (School).
 *
 * <p><b>The school's set.</b> The teacher keeps a set of slates for its class out of the stores: a pupil at its desk with
 * no slate is handed one at the lesson, the school's own (marked so), and keeps it for its schooling. When it grows up
 * the slate goes back into the stores for the next.
 *
 * <p><b>Carried.</b> A child carries its slate in its hand on its way to school and at its desk, and writes the day's
 * lesson on it ("The fisher's trade: the rod, the line, the bait").
 *
 * <p><b>The lesson.</b> A child with a slate (with chalk left on it) learns about a quarter faster (School.rate), and each
 * beat of the lesson uses a little of the chalk; a slate whose chalk is gone is gone, and the teacher hands out another
 * from the stores. The shop's workshop keeps the stores in slates while the school has pupils (a slate for each with
 * none, and a spare); a town with no shop has its smelter (its mason) make them at the storehouse's bench.
 */
public final class Slates {

    private Slates() {}

    /** How much quicker a child with a slate learns, in percent. */
    public static final int BONUS = 25;
    /** The mark on the school's own slates. */
    static final String SCHOOL = "mca_school_slate";

    /** Each town's last look at its grown-up pupils' slates (the day). */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        LOOKED.clear();
    }

    static boolean isSlate(ItemStack s) {
        return !s.isEmpty() && s.is(LeisureItems.SLATE.get());
    }

    static boolean school(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().getBoolean(SCHOOL);
    }

    /** Its slate with chalk left on it (its hand first), or empty. */
    static ItemStack slateOf(VillageFolkEntity f) {
        if (isSlate(f.getMainHandItem()) && left(f.getMainHandItem()) > 0) return f.getMainHandItem();
        for (ItemStack s : f.getInventoryItems()) if (isSlate(s) && left(s) > 0) return s;
        return ItemStack.EMPTY;
    }

    static int left(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue();
    }

    /** What a slate adds to a pupil's learning (School.rate): a quarter, with chalk on it. */
    static int bonus(VillageFolkEntity pupil) {
        return pupil.isBaby() && !slateOf(pupil).isEmpty() ? BONUS : 0;
    }

    /** On its way to school and at its desk: its slate in its hand (out of its pack; what it held into the pack). */
    static void inHand(VillageFolkEntity f) {
        if (isSlate(f.getMainHandItem())) return;
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            if (!isSlate(pack.get(i))) continue;
            ItemStack was = f.getMainHandItem();
            f.setItemSlot(EquipmentSlot.MAINHAND, pack.get(i));
            pack.set(i, was);
            return;
        }
    }

    /**
     * A beat of the lesson at its desk (School.attend, before the learning): a slate handed out of the school's set if it
     * has none, the lesson chalked on it, and a little of its chalk used.
     */
    static void beat(ServerLevel level, Villages.Village v, VillageFolkEntity pupil, VillageFolkEntity teacher, StationTask topic) {
        ItemStack slate = slateOf(pupil);
        if (slate.isEmpty()) {
            ItemStack one = Crafts.takeOne(level, v, Slates::isSlate);
            if (one.isEmpty()) return;
            CompoundTag t = one.has(DataComponents.CUSTOM_DATA) ? one.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
            t.putBoolean(SCHOOL, true);
            one.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            ItemStack left = pupil.insertGiven(one);
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);
                return;
            }
            FolkTalk.speak(teacher, FolkTalk.pick(level.getRandom(), "Here's a slate for you, " + pupil.displayNameCap() + ". Mind the chalk.",
                "A slate each — and no drawing on it!"));
            inHand(pupil);
            slate = slateOf(pupil);
            if (slate.isEmpty()) return;
        }
        String lesson = lesson(topic);
        if (!lesson.equals(SlateItem.written(slate))) SlateItem.chalk(slate, lesson);
        boolean last = left(slate) <= 1;
        SlateItem.wear(slate, null);
        if (last) {
            level.playSound(null, pupil.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.5F, 1.4F);
            FolkTalk.speak(pupil, FolkTalk.pick(level.getRandom(), "My chalk's all gone!", "Teacher, my slate's finished!"));
        }
    }

    /** The day's lesson in a line, as a child chalks it up. */
    static String lesson(StationTask topic) {
        return switch (topic) {
            case FARM -> "The farmer's trade: sow, weed, reap";
            case WOOD -> "The woodcutter's trade: fell, saw, plant";
            case MINE -> "The miner's trade: prop the roof!";
            case RANCH -> "The rancher's trade: shear, milk, feed";
            case FISH -> "The fisher's trade: rod, line, bait";
            case GUARD -> "The watch: eyes open, gate shut";
            case SMELT -> "The smelter: ore + coal = iron";
            case COOK -> "The cook: wheat, flour, bread";
            case TAILOR -> "The tailor: measure twice, cut once";
            case SMITH -> "The smith: strike while it's hot";
            default -> "The " + topic.title.toLowerCase(Locale.ROOT) + "'s trade";
        };
    }

    /**
     * The town's round (once a day): the school's slates of folk who have grown up back into the stores for the next
     * pupils.
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 6000L || LOOKED.getOrDefault(v.id(), -1L) >= day) return;
        LOOKED.put(v.id(), day);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (isSlate(f.getMainHandItem()) && school(f.getMainHandItem())) {
                Crafts.store(level, v, f.getMainHandItem().copy());
                f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            List<ItemStack> pack = f.getInventoryItems();
            for (int i = 0; i < pack.size(); i++) {
                if (!isSlate(pack.get(i)) || !school(pack.get(i))) continue;
                Crafts.store(level, v, pack.get(i).copy());
                pack.set(i, ItemStack.EMPTY);
            }
        }
    }

    // ------------------------------------------------------------------ made, the card, the books

    /** Slates for the school: one for each pupil with none, and a spare (eight at most), while the school stands. */
    static int wanted(ServerLevel level, Villages.Village v) {
        if (School.schoolhouse(v.id(), level.getGameTime()) == null) return 0;
        int without = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && f.isBaby() && f.isAlive() && slateOf(f).isEmpty()) without++;
        }
        return Math.min(8, without + 1);
    }

    @Nullable
    static String card(VillageFolkEntity f) {
        if (!f.isBaby()) return null;
        ItemStack s = slateOf(f);
        if (s.isEmpty()) return null;
        String w = SlateItem.written(s);
        return "carries a slate to school (" + left(s) + " chalk left" + (w != null ? "; on it: \"" + w + "\"" : "") + ")";
    }

    static String status(ServerLevel level, Villages.Village v) {
        int pupils = 0, with = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isBaby()) continue;
            pupils++;
            if (!slateOf(f).isEmpty()) with++;
        }
        return with + " of " + pupils + " children with a slate; " + Market.stock(level, v.id(), Slates::isSlate) + " in the stores"
            + (School.schoolhouse(v.id(), level.getGameTime()) == null ? " (no school yet)" : "");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a beat of the lesson's slate for this pupil now (handed one, chalked, worn). */
    public static void beatForTests(ServerLevel level, VillageFolkEntity pupil, VillageFolkEntity teacher, StationTask topic) {
        Villages.Village v = pupil.ownerId() == null ? null : Villages.get(pupil.ownerId());
        if (v != null) beat(level, v, pupil, teacher, topic);
    }

    /** Tests: the slate this child has (with chalk), or empty. */
    public static ItemStack slateForTests(VillageFolkEntity f) {
        return slateOf(f);
    }

    /** Tests: the town's daily look at grown-ups' school slates, now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        LOOKED.remove(v.id());
        tick(level, v, level.getDayTime() / 24000L, 6500L);
    }
}
