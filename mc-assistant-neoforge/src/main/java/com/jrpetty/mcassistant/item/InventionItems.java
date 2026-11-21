package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * [tech-tree] What the town's inventions put in its smiths' and fletchers' hands (entity/Inventions, InventionArms),
 * registered here and joined to the mod with one line (McAssistantMod), as WarItems is. Every one of them is made in
 * survival: by a player at the crafting table (the recipes under data/mc_assistant/recipe), by the town's smith or
 * fletcher out of its stores once the town knows how.
 * <ul>
 * <li><b>Steel</b>: an iron ingot blasted in a blast furnace (the town's foundry burns a coal to each). Steel is between
 *     iron and diamond: the <b>steel sword</b> bites harder than iron (2.5 to iron's 2) and lasts longer; <b>steel
 *     armour</b> is eighteen points to iron's fifteen and diamond's twenty, a point of toughness to diamond's two.</li>
 * <li><b>The pike</b> (iron): slow, a block and a half more reach, half again as deadly to a rider or its horse.</li>
 * <li><b>The halberd</b> (steel): a heavier blow, a block more reach, a third again to a rider, and its spike finds the
 *     gaps in armour.</li>
 * <li><b>The war hammer</b> (iron): slow and heavy, and it goes through armour: a quarter of the armour's points again
 *     in harm, five at most.</li>
 * <li><b>The longbow</b>: its arrow a fifth faster and a quarter harder. <b>The arbalest</b>: three quarters as long
 *     again to span, its bolt half again as hard.</li>
 * <li><b>A working model</b>: the scholars' model of an invention, labelled with what, where, by whom and when; the
 *     museum shows the first (entity/Museum, Kind.MODEL). Not craftable; it is built of real makings at the academy.</li>
 * </ul>
 */
public final class InventionItems {

    private InventionItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<ArmorMaterial> MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, McAssistantMod.MODID);

    /** Steel: iron's speed and more, a little more bite, half again the wear. */
    public static final Tier STEEL_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_IRON_TOOL, 500, 6.5F, 2.5F, 12,
        () -> Ingredient.of(InventionItems.STEEL_INGOT.get()));

    /** Steel armour: 3, 7, 6 and 2 points (eighteen), a point of toughness; between iron and diamond. */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> STEEL_ARMOUR = MATERIALS.register("steel",
        () -> new ArmorMaterial(Util.make(new EnumMap<>(ArmorItem.Type.class), m -> {
            m.put(ArmorItem.Type.BOOTS, 2);
            m.put(ArmorItem.Type.LEGGINGS, 6);
            m.put(ArmorItem.Type.CHESTPLATE, 7);
            m.put(ArmorItem.Type.HELMET, 3);
            m.put(ArmorItem.Type.BODY, 7);
        }), 11, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(InventionItems.STEEL_INGOT.get()),
            List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "steel"))), 1.0F, 0.0F));

    public static final DeferredItem<Item> STEEL_INGOT = ITEMS.registerSimpleItem("steel_ingot");

    public static final DeferredItem<SwordItem> STEEL_SWORD = ITEMS.register("steel_sword",
        () -> new SwordItem(STEEL_TIER, new Item.Properties().attributes(SwordItem.createAttributes(STEEL_TIER, 3, -2.4F))));

    public static final DeferredItem<ArmorItem> STEEL_HELMET = armour("steel_helmet", ArmorItem.Type.HELMET);
    public static final DeferredItem<ArmorItem> STEEL_CHESTPLATE = armour("steel_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ArmorItem> STEEL_LEGGINGS = armour("steel_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ArmorItem> STEEL_BOOTS = armour("steel_boots", ArmorItem.Type.BOOTS);

    private static DeferredItem<ArmorItem> armour(String name, ArmorItem.Type type) {
        return ITEMS.register(name, () -> new ArmorItem(STEEL_ARMOUR, type, new Item.Properties().durability(type.getDurability(22))));
    }

    public static final DeferredItem<PolearmItem> PIKE = ITEMS.register("pike",
        () -> new PolearmItem(net.minecraft.world.item.Tiers.IRON, PolearmItem.Kind.PIKE, 2.0F, -3.0F, 1.5,
            "Long reach; half again as deadly to a rider", new Item.Properties()));
    public static final DeferredItem<PolearmItem> HALBERD = ITEMS.register("halberd",
        () -> new PolearmItem(STEEL_TIER, PolearmItem.Kind.HALBERD, 4.0F, -3.1F, 1.0,
            "Reach, a steel edge and a hook for riders; finds the gaps in armour", new Item.Properties()));
    public static final DeferredItem<PolearmItem> WAR_HAMMER = ITEMS.register("war_hammer",
        () -> new PolearmItem(net.minecraft.world.item.Tiers.IRON, PolearmItem.Kind.HAMMER, 5.0F, -3.2F, 0.0,
            "Slow and heavy; goes through armour", new Item.Properties()));

    public static final DeferredItem<LongbowItem> LONGBOW = ITEMS.register("longbow",
        () -> new LongbowItem(new Item.Properties().durability(450)));
    public static final DeferredItem<ArbalestItem> ARBALEST = ITEMS.register("arbalest",
        () -> new ArbalestItem(new Item.Properties().stacksTo(1).durability(500)));

    public static final DeferredItem<Item> WORKING_MODEL = ITEMS.register("working_model",
        () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** Joined to the mod's bus (McAssistantMod): the items, the steel armour's material, their places in the tabs. */
    public static void register(IEventBus modBus) {
        MATERIALS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(InventionItems::onTabs);
        com.jrpetty.mcassistant.entity.InventionArms.declareMakers();
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            for (DeferredItem<?> it : List.of(STEEL_INGOT, STEEL_SWORD, STEEL_HELMET, STEEL_CHESTPLATE, STEEL_LEGGINGS, STEEL_BOOTS,
                PIKE, HALBERD, WAR_HAMMER, LONGBOW, ARBALEST)) event.accept(it);
        }
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            for (DeferredItem<?> it : List.of(STEEL_SWORD, PIKE, HALBERD, WAR_HAMMER, LONGBOW, ARBALEST, STEEL_HELMET, STEEL_CHESTPLATE,
                STEEL_LEGGINGS, STEEL_BOOTS)) event.accept(it);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(STEEL_INGOT);
    }

    /** The scholars' working model of an invention: its name, and a label of where, by whom and when. */
    public static ItemStack model(String id, String title, String town, String by, long day) {
        ItemStack s = new ItemStack(WORKING_MODEL.get());
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Working Model: " + title));
        s.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Built at " + town + "'s academy" + (by.isEmpty() ? "" : " by " + by)),
            Component.literal("Day " + (day + 1)))));
        CompoundTag t = new CompoundTag();
        t.putString("mca_invention", id);
        t.putString("town", town);
        t.putString("by", by);
        t.putLong("day", day);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** The invention a working model is of ("windmill"), or "". */
    public static String modelOf(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? "" : d.copyTag().getString("mca_invention");
    }

    /** Holder for the steel armour's material, for the checks that ask what an armour piece is made of. */
    public static Holder<ArmorMaterial> steel() {
        return STEEL_ARMOUR;
    }
}
