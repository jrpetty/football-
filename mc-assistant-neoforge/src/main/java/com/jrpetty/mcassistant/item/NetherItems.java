package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * [nether] What the Nether runners carry that the game does not have: registered here, out of the way of the mod's own
 * register, and joined to it with one line (McAssistantMod), as CivicItems is.
 * <ul>
 * <li><b>The Gold Charm</b> (GoldCharmItem): a brow band of gold, worn on the head; piglins leave its wearer be. The
 *     smith makes one for each runner with no gold to wear (NetherRunners.kitUp asks; Crafts.smith makes).</li>
 * <li><b>The Runner's Satchel</b> (RunnersSatchelItem): nine stacks of the haul, and fire-proof. The tailor stitches one
 *     for each runner (Crafts.tailor), waxed with a magma cream the runners brought home.</li>
 * </ul>
 */
public final class NetherItems {

    private NetherItems() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);

    /** A point of armour on the brow, where a gold helmet gives two: the charm is for the gold, not the guard. */
    public static final DeferredItem<GoldCharmItem> GOLD_CHARM = ITEMS.register("gold_charm",
        () -> new GoldCharmItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)
            .attributes(ItemAttributeModifiers.builder()
                .add(Attributes.ARMOR, new AttributeModifier(ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "gold_charm"), 1.0,
                    AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD)
                .build())));

    /** Fire-proof (the game's own rule for netherite: it floats on lava, whole), and nine stacks of room. */
    public static final DeferredItem<RunnersSatchelItem> RUNNERS_SATCHEL = ITEMS.register("runners_satchel",
        () -> new RunnersSatchelItem(new Item.Properties().stacksTo(1).fireResistant()
            .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY)));

    /** Joined to the mod's bus (McAssistantMod): the items and their places in the creative tab. */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(NetherItems::onTabs);
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            event.accept(GOLD_CHARM);
            event.accept(RUNNERS_SATCHEL);
        }
    }
}
