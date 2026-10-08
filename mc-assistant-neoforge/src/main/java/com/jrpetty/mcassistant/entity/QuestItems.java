package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
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
 * [quests] The quests' things, and where they are.
 *
 * <ul>
 * <li><b>Made, never found lying about.</b> A sealed letter, a parcel, the peace terms, a spy's report, an old
 *     miner's journal, a child's toy or drawing, a family's heirloom, the smugglers' ledger, the town's medal and
 *     its key: each has a recipe of its own, and when a quest wants one it is made there and then by its giver (or
 *     the smith, for the gold) out of the town's stores, the whole way from what they hold (Bench: paper from the
 *     cane, a ring from the gold). What the stores cannot run to, the quest is not offered for, or goes without.</li>
 * <li><b>Stamped.</b> Each one a quest makes carries the quest's number, a name (*"Mira's letter to Tobin"*) and a
 *     line or two on it, so the quest knows its own: another letter will not do.</li>
 * <li><b>Kept.</b> A quest's thing in a folk's pack is its own (Homes.keepsake), never banked in the stores; in a
 *     chest it stays where it was put, a family's heirloom in the family's chest, the smugglers' cache in their camp.</li>
 * </ul>
 */
public final class QuestItems {

    private QuestItems() {}

    /** The quest a thing belongs to, its town (an honour's), and whose an honour is. */
    static final String QUEST = "mca_quest", TOWN = "mca_town", HONOUR = "mca_honour";

    // ------------------------------------------------------------------ which item

    @Nullable
    static Item named(String name) {
        return switch (name) {
            case "quest_journal" -> McAssistantMod.QUEST_JOURNAL.get();
            case "sealed_letter" -> McAssistantMod.SEALED_LETTER.get();
            case "parcel" -> McAssistantMod.PARCEL.get();
            case "peace_terms" -> McAssistantMod.PEACE_TERMS.get();
            case "spy_report" -> McAssistantMod.SPY_REPORT.get();
            case "smugglers_ledger" -> McAssistantMod.SMUGGLERS_LEDGER.get();
            case "miners_journal" -> McAssistantMod.MINERS_JOURNAL.get();
            case "wooden_toy" -> McAssistantMod.WOODEN_TOY.get();
            case "childs_drawing" -> McAssistantMod.CHILDS_DRAWING.get();
            case "heirloom_ring" -> McAssistantMod.HEIRLOOM_RING.get();
            case "heirloom_locket" -> McAssistantMod.HEIRLOOM_LOCKET.get();
            case "town_medal" -> McAssistantMod.TOWN_MEDAL.get();
            case "town_key" -> McAssistantMod.TOWN_KEY.get();
            default -> null;
        };
    }

    // ------------------------------------------------------------------ making

    /**
     * One of this made out of the town's stores at this maker's hand, the whole way from what they hold (Bench), the
     * makings taken and the leftovers put back. Empty if the stores cannot run to it: nothing is taken then.
     */
    static ItemStack make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker, Item item) {
        try {
            Bench.Hand hand = Bench.handOf(level, v, maker, null);
            Bench.Plan plan = Bench.plan(level, v, item, 1, hand);
            if (!plan.ok() || plan.made <= 0) return ItemStack.EMPTY;
            if (!Bench.take(level, v, plan, maker)) return ItemStack.EMPTY;
            // A recipe that makes more than one (none of ours does): the rest to the stores.
            if (plan.made > 1) Crafts.giveBack(level, v, item, plan.made - 1);
            return new ItemStack(item);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** What the stores lack for one of these, in words ("2 paper"), or "" if they can make it. */
    static String shortFor(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker, Item item) {
        try {
            Bench.Plan plan = Bench.plan(level, v, item, 1, Bench.handOf(level, v, maker, null));
            return plan.ok() ? "" : plan.shortOf == null ? "the makings" : plan.shortOf;
        } catch (RuntimeException e) {
            return "the makings";
        }
    }

    /** A quest's thing: its number, a name and a line or two on it. */
    static ItemStack stamp(ItemStack s, int quest, String name, String... lore) {
        if (s.isEmpty()) return s;
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putInt(QUEST, quest);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(Component.literal(l).withColor(0xA89A7A));
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    /** The quest this thing belongs to, or 0. */
    static int questOf(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt(QUEST);
    }

    static String text(ItemStack s, String key) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? "" : d.copyTag().getString(key);
    }

    static void mark(ItemStack s, String key, String value) {
        CompoundTag t = s.has(DataComponents.CUSTOM_DATA) ? s.get(DataComponents.CUSTOM_DATA).copyTag() : new CompoundTag();
        t.putString(key, value);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    // ------------------------------------------------------------------ what will do

    /**
     * What a step wants, by its key: "token:sealed_letter" (that quest's own letter), "food", "remedy" (a honey
     * bottle, a golden carrot, a healing potion), "cake", "flowers", "torch", a word the quest board knows ("iron",
     * "coal", "logs", "stone", "fish"), or an item's id.
     */
    static Predicate<ItemStack> matcher(String key, int quest) {
        if (key.startsWith("token:")) {
            Item it = named(key.substring(6));
            return s -> it != null && s.is(it) && questOf(s) == quest;
        }
        return switch (key) {
            case "remedy" -> s -> s.is(Items.HONEY_BOTTLE) || s.is(Items.GOLDEN_CARROT) || s.is(Items.GLISTERING_MELON_SLICE)
                || s.is(Items.POTION) && s.getOrDefault(DataComponents.POTION_CONTENTS,
                    net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(net.minecraft.world.item.alchemy.Potions.HEALING);
            case "cake" -> s -> s.is(Items.CAKE) || s.is(Items.PUMPKIN_PIE);
            case "torch" -> s -> s.is(Items.TORCH) || s.is(Items.LANTERN) || s.is(Items.SOUL_TORCH);
            // [weave] the rebuilding's planks or wool (any kind), and a household's own things carried for a quest
            case "planks" -> s -> s.is(net.minecraft.tags.ItemTags.PLANKS) && questOf(s) == 0;
            case "wool" -> s -> s.is(net.minecraft.tags.ItemTags.WOOL) && questOf(s) == 0;
            case "quest" -> s -> !s.isEmpty() && questOf(s) == quest;
            case "iron", "coal", "logs", "stone", "food", "flowers", "fish" -> Errands.matcher(key);
            default -> {
                ResourceLocation id = ResourceLocation.tryParse(key);
                Item it = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
                yield s -> !s.isEmpty() && it != Items.AIR && s.is(it) && questOf(s) == 0;
            }
        };
    }

    /** "3 honey bottles (or golden carrots)", "the sealed letter". */
    static String words(String key, int n) {
        if (key.startsWith("token:")) {
            Item it = named(key.substring(6));
            return it == null ? "it" : "the " + new ItemStack(it).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        }
        return switch (key) {
            case "remedy" -> n + (n == 1 ? " remedy" : " remedies") + " (honey bottles, golden carrots or healing potions)";
            case "cake" -> n == 1 ? "a cake (or a pumpkin pie)" : n + " cakes";
            case "torch" -> n + " torches";
            case "planks" -> n + (n == 1 ? " plank" : " planks");                 // [weave]
            case "wool" -> n + " wool";                                            // [weave]
            case "quest" -> "their things";                                        // [weave]
            case "iron", "coal", "logs", "stone", "food", "flowers", "fish" -> Errands.words(key, n).replace(" (ingots or raw)", "");
            default -> {
                ResourceLocation id = ResourceLocation.tryParse(key);
                Item it = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
                yield it == Items.AIR ? n + " " + key : Bench.words(it, n);
            }
        };
    }

    // ------------------------------------------------------------------ a player's pack

    static int count(Player p, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** Take up to so many out of a player's pack; what was taken, stack by stack. */
    static List<ItemStack> take(Player p, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.split(k));
            n -= k;
        }
        p.getInventory().setChanged();
        return out;
    }

    static void give(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    // ------------------------------------------------------------------ a folk's pack

    /** Into a folk's pack as its own (never banked in the stores); what will not fit goes to its feet. */
    static void toFolk(VillageFolkEntity f, ItemStack s) {
        if (s.isEmpty()) return;
        Homes.keepsake(s, f);
        ItemStack left = f.insertItem(s);
        if (!left.isEmpty() && f.level() instanceof ServerLevel level) {
            net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
        }
    }

    /** Out of a folk's pack: the first that matches. */
    static ItemStack fromFolk(VillageFolkEntity f, Predicate<ItemStack> what) {
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && what.test(s)) {
                ItemStack out = s.split(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                return out;
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ a chest

    @Nullable
    static Container chest(ServerLevel level, @Nullable BlockPos at) {
        if (at == null || !level.isLoaded(at)) return null;
        return level.getBlockEntity(at) instanceof Container c ? c : null;
    }

    static boolean intoChest(ServerLevel level, @Nullable BlockPos at, ItemStack s) {
        Container c = chest(level, at);
        if (c == null) return false;
        ItemStack left = Stacking.insert(c, s);
        c.setChanged();
        return left.isEmpty();
    }

    static ItemStack outOfChest(ServerLevel level, @Nullable BlockPos at, Predicate<ItemStack> what) {
        Container c = chest(level, at);
        if (c == null) return ItemStack.EMPTY;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) {
                c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    static int inChest(ServerLevel level, @Nullable BlockPos at, Predicate<ItemStack> what) {
        Container c = chest(level, at);
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Is this quest's thing anywhere in the town's stores? Taken out (the storekeeper's buy-back). */
    static ItemStack outOfStores(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            ItemStack s = outOfChest(level, p, what);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    static boolean inStores(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, v.id())) if (inChest(level, p, what) > 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ dropped

    /** A thing dropped where it was lost (a child's toy on the path): it lies there till it is picked up. */
    static ItemEntity drop(ServerLevel level, BlockPos at, ItemStack s) {
        ItemEntity e = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, s);
        e.setUnlimitedLifetime();
        e.setDeltaMovement(0, 0, 0);
        level.addFreshEntity(e);
        return e;
    }

    // ------------------------------------------------------------------ the town's honours

    /** Is this the town's medal or key, given to this player? */
    static boolean honourOf(ItemStack s, UUID village, UUID player, Item which) {
        if (!s.is(which)) return false;
        return village.toString().equals(text(s, TOWN)) && player.toString().equals(text(s, HONOUR));
    }

    static boolean carries(Player p, UUID village, Item which) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (honourOf(p.getInventory().getItem(i), village, p.getUUID(), which)) return true;
        }
        return false;
    }
}
