package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.FolkAction.Act;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

/**
 * [anim] The tools of an act, in hands that are empty: the hammer at the anvil, the ladle in the pot, the quill at the
 * desk, the broom, the smoker at the hive, the lute, the cup at the lips, a crate carried in both arms and a sack over
 * the shoulder. A hand that holds something already keeps it (the farmer's own hoe, the guard's own sword): this only
 * fills an empty one, so the act always has its tool and the hands meet it.
 *
 * <p>Each act's things are made once, the first time they are wanted, and kept (the item renderer only reads them).
 * Not drawn past thirty-two blocks, where a hand's tool is a pixel.
 */
public final class FolkActionLayer extends RenderLayer<VillageFolkEntity, FolkModel> {

    /** An act's things: the main hand's, the other hand's, a load held in both arms, and how big the main hand's is. */
    private record Kit(Supplier<Item> main, Supplier<Item> off, Supplier<Item> load, float size) {}

    private static final Kit[] KITS = new Kit[Act.values().length];
    private static final ItemStack[][] MADE = new ItemStack[Act.values().length][];

    private static void kit(Act a, Supplier<Item> main, Supplier<Item> off) { KITS[a.ordinal()] = new Kit(main, off, null, 1.0F); }

    private static void kit(Act a, Supplier<Item> main, Supplier<Item> off, float size) { KITS[a.ordinal()] = new Kit(main, off, null, size); }

    private static void load(Act a, Supplier<Item> load) { KITS[a.ordinal()] = new Kit(null, null, load, 1.0F); }

    static {
        kit(Act.HAMMER, () -> Items.MACE, () -> Items.IRON_INGOT, 0.7F);
        kit(Act.CHOP, () -> Items.IRON_AXE, null);
        kit(Act.PICK, () -> Items.IRON_PICKAXE, null);
        kit(Act.DIG, () -> Items.IRON_SHOVEL, null);
        kit(Act.HOE, () -> Items.IRON_HOE, null);
        kit(Act.PLANT, () -> Items.WHEAT_SEEDS, null);
        kit(Act.HARVEST, () -> com.jrpetty.mcassistant.item.FieldItems.COPPER_SICKLE.get(), () -> Items.WHEAT);
        kit(Act.FISH, () -> Items.FISHING_ROD, null);
        kit(Act.STIR_POT, () -> Items.STICK, null);
        kit(Act.BREW, () -> Items.BLAZE_ROD, null);
        kit(Act.SEW, () -> Items.STRING, () -> Items.WHITE_CARPET);
        kit(Act.SAW, () -> com.jrpetty.mcassistant.item.WorkItems.FELLING_SAW.get(), null);
        kit(Act.CHISEL, () -> Items.MACE, () -> Items.FLINT, 0.55F);
        kit(Act.BUILD, () -> Items.BRICKS, null, 0.8F);
        kit(Act.SWEEP, () -> Items.BRUSH, null, 1.5F);
        load(Act.CARRY, () -> com.jrpetty.mcassistant.item.WorkItems.SHIPPING_CRATE_ITEM.get());
        kit(Act.SACK, () -> com.jrpetty.mcassistant.item.WorkItems.ORE_SACK.get(), null, 1.5F);
        kit(Act.HAND_OVER, () -> Items.BREAD, null);
        kit(Act.COUNT_COINS, () -> Items.GOLD_NUGGET, () -> Items.GOLD_NUGGET);
        kit(Act.WRITE, () -> Items.FEATHER, () -> Items.PAPER);
        kit(Act.TEACH, () -> Items.STICK, () -> Items.BOOK);
        kit(Act.TEND, () -> com.jrpetty.mcassistant.item.KitchenItems.BANDAGE.get(), () -> Items.PAPER);
        kit(Act.PREACH, null, () -> Items.BOOK);
        kit(Act.MILK, () -> Items.BUCKET, null);
        kit(Act.SHEAR, () -> Items.SHEARS, null);
        kit(Act.LEAD, () -> Items.LEAD, null);
        kit(Act.FEED, () -> Items.WHEAT_SEEDS, () -> Items.WHEAT);
        kit(Act.SMOKE_HIVE, () -> com.jrpetty.mcassistant.item.FieldItems.BEE_SMOKER.get(), null);
        kit(Act.FLETCH, () -> Items.FEATHER, () -> Items.ARROW);
        kit(Act.STOKE, () -> Items.IRON_SHOVEL, null);
        kit(Act.SPYGLASS, () -> Items.SPYGLASS, null);
        kit(Act.POUR, () -> com.jrpetty.mcassistant.item.KitchenItems.CIDER.get(), () -> Items.GLASS_BOTTLE);
        kit(Act.SERVE, null, () -> Items.MUSHROOM_STEW);
        kit(Act.MAP, () -> Items.FEATHER, () -> Items.MAP);
        kit(Act.TINKER, () -> Items.TRIPWIRE_HOOK, () -> Items.REDSTONE);
        kit(Act.FUSE, () -> Items.GUNPOWDER, () -> Items.FIREWORK_ROCKET);
        load(Act.STOCK, () -> Items.BARREL);
        kit(Act.WATER, () -> com.jrpetty.mcassistant.item.FieldItems.WATERING_CAN.get(), null);
        kit(Act.REPAIR, () -> Items.MACE, () -> Items.IRON_INGOT, 0.7F);
        kit(Act.WIPE, () -> Items.PAPER, null);
        kit(Act.EAT, () -> Items.BREAD, null);
        kit(Act.DRINK, () -> com.jrpetty.mcassistant.item.KitchenItems.MEAD.get(), null);
        kit(Act.LUTE, () -> com.jrpetty.mcassistant.item.LeisureItems.LUTE.get(), null);
        kit(Act.DRUM, () -> Items.STICK, () -> Items.STICK);
        kit(Act.THRUST, () -> Items.IRON_SWORD, null);
        kit(Act.BLOCK, () -> Items.IRON_SWORD, () -> Items.SHIELD);
        kit(Act.READ, () -> Items.BOOK, null);
        kit(Act.MOURN, null, () -> Items.POPPY);
    }

    private final ItemInHandRenderer hands;

    public FolkActionLayer(RenderLayerParent<VillageFolkEntity, FolkModel> parent, ItemInHandRenderer hands) {
        super(parent);
        this.hands = hands;
    }

    /** An act's things, made the first time they are wanted: {main, off, load}. */
    private static ItemStack[] made(Act a) {
        ItemStack[] m = MADE[a.ordinal()];
        if (m != null) return m;
        Kit k = KITS[a.ordinal()];
        m = new ItemStack[3];
        m[0] = k == null || k.main() == null ? ItemStack.EMPTY : new ItemStack(k.main().get());
        m[1] = k == null || k.off() == null ? ItemStack.EMPTY : new ItemStack(k.off().get());
        m[2] = k == null || k.load() == null ? ItemStack.EMPTY : new ItemStack(k.load().get());
        MADE[a.ordinal()] = m;
        return m;
    }

    /** The act's tool for the main hand (for the effects: what the crumbs are made of), or empty. */
    static ItemStack mainOf(Act a) {
        return made(a)[0];
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (folk.isInvisible()) return;
        Act act = Act.of(folk.animSeen().act);
        if (act == Act.NONE || KITS[act.ordinal()] == null) return;
        if (net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(folk) > 32 * 32) return;
        ItemStack[] m = made(act);
        Kit k = KITS[act.ordinal()];
        boolean right = folk.getMainArm() == HumanoidArm.RIGHT;
        HumanoidArm mainArm = right ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        if (!m[2].isEmpty()) {
            if (folk.getMainHandItem().isEmpty() && folk.getOffhandItem().isEmpty()) load(pose, buffer, light, folk, m[2], mainArm, act == Act.STOCK);
            return;
        }
        if (!m[0].isEmpty() && folk.getMainHandItem().isEmpty()) hand(pose, buffer, light, folk, m[0], mainArm, k.size());
        if (!m[1].isEmpty() && folk.getOffhandItem().isEmpty()) hand(pose, buffer, light, folk, m[1], mainArm.getOpposite(), 1.0F);
    }

    /** As the game's own layer holds a thing in a hand (ItemInHandLayer), a little bigger or smaller as the tool wants. */
    private void hand(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, ItemStack s, HumanoidArm arm, float size) {
        pose.pushPose();
        getParentModel().translateToHand(arm, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        boolean left = arm == HumanoidArm.LEFT;
        pose.translate((left ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
        if (size != 1.0F) pose.scale(size, size, size);
        hands.renderItem(folk, s, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, pose, buffer, light);
        pose.popPose();
    }

    /** A load held in both arms, between the hands: a crate carried against the chest, a barrel lifted to a shelf. */
    private void load(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, ItemStack s, HumanoidArm arm, boolean high) {
        pose.pushPose();
        getParentModel().translateToHand(arm, pose);
        boolean left = arm == HumanoidArm.LEFT;
        // From the hand toward the other hand (the arms reach in toward each other), and out past the fingers.
        pose.translate((left ? -4.5F : 4.5F) / 16.0F, 10.5F / 16.0F, -2.5F / 16.0F);
        pose.mulPose(Axis.XP.rotationDegrees(high ? 0.0F : 90.0F));
        pose.scale(0.62F, 0.62F, 0.62F);
        net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(s, ItemDisplayContext.FIXED, light,
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffer, folk.level(), folk.getId());
        pose.popPose();
    }
}
