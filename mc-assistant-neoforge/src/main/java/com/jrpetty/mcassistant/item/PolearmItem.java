package com.jrpetty.mcassistant.item;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;

/**
 * [tech-tree] A weapon on a long haft: the pike (an iron head on a staff twice a man's height) and the halberd (a steel axe
 * blade, a spike and a hook for pulling a rider down). A sword in the hand as far as the town's armoury is concerned (it is
 * issued and kept as the blades are: Militia, WatchKit, Armies), slower than a sword, but it reaches further (the player's
 * own reach, ENTITY_INTERACTION_RANGE) and strikes a rider, or the horse under it, the harder (InventionArms.onIncoming).
 * Also used for the war hammer, which reaches no further but goes through armour.
 */
public class PolearmItem extends SwordItem {

    /** Kinds, for the battle's books and the damage. */
    public enum Kind { PIKE, HALBERD, HAMMER }

    public final Kind kind;
    private final String note;

    public PolearmItem(Tier tier, Kind kind, float damage, float speed, double reach, String note, Item.Properties props) {
        super(tier, props.attributes(attributes(tier, kind, damage, speed, reach)));
        this.kind = kind;
        this.note = note;
    }

    private static final ResourceLocation REACH_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "polearm_reach");

    /** The blow and the swing as a sword's, with the reach of the haft on top. */
    static ItemAttributeModifiers attributes(Tier tier, Kind kind, float damage, float speed, double reach) {
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage + tier.getAttackDamageBonus(),
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, speed, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND);
        if (reach > 0) b.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH_ID, reach, AttributeModifier.Operation.ADD_VALUE),
            EquipmentSlotGroup.MAINHAND);
        return b.build();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(note).withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
