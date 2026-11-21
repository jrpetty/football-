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
    /** Each act's grips: grip(PoseStack...) below. */
    private static final float[][][] GRIPS = new float[Act.values().length][][];

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
        kit(Act.SAW, () -> com.jrpetty.mcassistant.item.WorkItems.FELLING_SAW.get(), null, 0.85F);
        kit(Act.CHISEL, () -> Items.MACE, () -> Items.FLINT, 0.55F);
        kit(Act.BUILD, () -> Items.BRICKS, null, 0.8F);
        kit(Act.SWEEP, () -> Items.BRUSH, null, 1.5F);
        load(Act.CARRY, () -> com.jrpetty.mcassistant.item.WorkItems.SHIPPING_CRATE_ITEM.get());
        load(Act.SACK, () -> com.jrpetty.mcassistant.item.WorkItems.ORE_SACK.get());
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
        kit(Act.WATCH, () -> Items.IRON_SWORD, null);
        // The guard's blade (its own, or this one) stood point down before it on watch.
        grip(Act.WATCH, new float[]{0, 0, 0, -125, 45, -20, 1}, null);
        // The spoon and the brewer's rod down into the pot, the broom's head down on the floor ahead, the sticks down on
        // the drum's head, the blade levelled along the thrust (the game's grip holds them all blade up out of the fist).
        grip(Act.STIR_POT, new float[]{0, 0, 0, -95, 0, -25, 1}, null);
        grip(Act.BREW, new float[]{0, 0, 0, -95, 0, -25, 1}, null);
        grip(Act.SWEEP, new float[]{0, 0, 0, -115, 0, -25, 1}, null);
        grip(Act.DRUM, new float[]{0, 0, 0, -135, 0, -25, 0.6F}, new float[]{0, 0, 0, -135, 0, -25, 0.6F});
        grip(Act.THRUST, new float[]{0, 0, 0, -50, 0, 0, 1}, null);
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
            if (act == Act.SACK) sack(pose, buffer, light, folk, m[2], right);
            else if (folk.getMainHandItem().isEmpty() && folk.getOffhandItem().isEmpty()) load(pose, buffer, light, act == Act.STOCK, mainArm);
            return;
        }
        if (act == Act.DRUM) drum(pose, buffer, light);
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
        grip(pose, Act.of(folk.animSeen().act), arm == folk.getMainArm(), left);
        hands.renderItem(folk, s, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, pose, buffer, light);
        pose.popPose();
    }

    /**
     * How an act's tool sits in the hand, past the game's own way of holding it (a sword's blade up and forward out of the
     * fist): {x, y, z in pixels, then turned about x, y, z in degrees, then its size}, turned about the grip itself. The saw
     * upright with its blade along the stroke, the guard's own blade stood point down before it.
     */

    private static void grip(Act a, float[] main, float[] off) { GRIPS[a.ordinal()] = new float[][]{main, off}; }

    /** The act's grip put on, for the main hand or the other (the left hand's the mirror of the right's). */
    static void grip(PoseStack pose, Act a, boolean main, boolean left) {
        float[][] dev = AnimDev.GRIP[a.ordinal()], own = GRIPS[a.ordinal()];
        int h = main ? 0 : 1;
        float[] g = dev != null && dev[h] != null ? dev[h] : own != null ? own[h] : null;
        if (g == null) return;
        float m = left ? -1.0F : 1.0F;
        pose.translate(m * g[0] / 16.0F, g[1] / 16.0F, g[2] / 16.0F);
        if (g[3] != 0.0F) pose.mulPose(Axis.XP.rotationDegrees(g[3]));
        if (g[4] != 0.0F) pose.mulPose(Axis.YP.rotationDegrees(m * g[4]));
        if (g[5] != 0.0F) pose.mulPose(Axis.ZP.rotationDegrees(m * g[5]));
        if (g[6] != 1.0F && g[6] > 0.0F) pose.scale(g[6], g[6], g[6]);
    }

    /**
     * The game's own layer for what a folk holds (ItemInHandLayer), with its act's grip: the guard's own sword stood point
     * down before it on watch, its hands on the pommel, where the game would hold it out blade up.
     */
    public static final class Held extends net.minecraft.client.renderer.entity.layers.ItemInHandLayer<VillageFolkEntity, FolkModel> {
        private final ItemInHandRenderer hands;

        public Held(RenderLayerParent<VillageFolkEntity, FolkModel> parent, ItemInHandRenderer hands) {
            super(parent, hands);
            this.hands = hands;
        }

        @Override
        protected void renderArmWithItem(net.minecraft.world.entity.LivingEntity e, ItemStack s, ItemDisplayContext ctx, HumanoidArm arm,
                                         PoseStack pose, MultiBufferSource buffer, int light) {
            if (s.isEmpty()) return;
            pose.pushPose();
            getParentModel().translateToHand(arm, pose);
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            boolean left = arm == HumanoidArm.LEFT;
            pose.translate((left ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
            if (e instanceof VillageFolkEntity f) grip(pose, Act.of(f.animSeen().act), arm == f.getMainArm(), left);
            hands.renderItem(e, s, ctx, left, pose, buffer, light);
            pose.popPose();
        }
    }

    /**
     * A load held in both arms: a crate against the chest (where the wagons' carriers hold theirs: WagonClient.CrateLayer),
     * a barrel lifted between the hands to a shelf. Drawn as the block itself, not its item.
     */
    private void load(PoseStack pose, MultiBufferSource buffer, int light, boolean high, HumanoidArm arm) {
        net.minecraft.world.level.block.state.BlockState st = high ? net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState()
            : com.jrpetty.mcassistant.item.WorkItems.SHIPPING_CRATE.get().defaultBlockState();
        pose.pushPose();
        if (high) {
            getParentModel().translateToHand(arm, pose);
            boolean left = arm == HumanoidArm.LEFT;
            pose.translate((left ? -5.0F : 5.0F) / 16.0F, 10.0F / 16.0F, 0.0F);
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.scale(0.45F, 0.45F, 0.45F);
            pose.translate(-0.5F, -0.5F, -0.5F);
        } else {
            // The model's space is upside down (y down, face to -z): the crate at the hands, out in front of the chest.
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(-0.25F, -0.95F, -0.74F);
            pose.scale(0.5F, 0.5F, 0.5F);
        }
        net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(st, pose, buffer, light,
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /**
     * A sack over the shoulder, held by its neck in the hand reached up over that shoulder, its weight down the back: hung
     * from the hand (it goes where the hand goes), upright whatever the arm's angle, drawn as two pictures crossed so it
     * has its bulk from the side as well as from behind.
     */
    private void sack(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk, ItemStack s, boolean right) {
        net.minecraft.client.model.geom.ModelPart arm = right ? getParentModel().rightArm() : getParentModel().leftArm();
        float m = right ? 1.0F : -1.0F;
        pose.pushPose();
        arm.translateAndRotate(pose);
        pose.translate(-m / 16.0F, 10.0F / 16.0F, 0.0F);              // the hand
        pose.mulPose(Axis.XP.rotation(-arm.xRot));                    // and upright again, in the body's frame
        pose.mulPose(Axis.YP.rotation(-arm.yRot));
        pose.mulPose(Axis.ZP.rotation(-arm.zRot));
        pose.translate(m * SACK[0] / 16.0F, SACK[1] / 16.0F, SACK[2] / 16.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F - m * SACK[3]));
        pose.scale(SACK[4], SACK[4], SACK[4]);
        net.minecraft.client.renderer.entity.ItemRenderer items = net.minecraft.client.Minecraft.getInstance().getItemRenderer();
        items.renderStatic(s, ItemDisplayContext.FIXED, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffer, folk.level(), folk.getId());
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        pose.scale(0.8F, 1.0F, 0.8F);
        items.renderStatic(s, ItemDisplayContext.FIXED, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffer, folk.level(), folk.getId());
        pose.popPose();
    }

    /** A drum slung at the waist, its head up under the sticks (a note block, small). */
    private void drum(PoseStack pose, MultiBufferSource buffer, int light) {
        pose.pushPose();
        getParentModel().body().translateAndRotate(pose);
        pose.translate(0.0F, DRUM_AT[0] / 16.0F, DRUM_AT[1] / 16.0F);
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.scale(DRUM_AT[2], DRUM_AT[2] * 0.8F, DRUM_AT[2]);
        pose.translate(-0.5F, -0.5F, -0.5F);
        net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(net.minecraft.world.level.block.Blocks.NOTE_BLOCK.defaultBlockState(),
            pose, buffer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** The drum's height on the body and how far before it (pixels), and its size (blocks). */
    static final float[] DRUM_AT = {9.0F, -6.0F, 0.42F};

    /** Where the sack hangs from the hand: across, down, back (pixels), its tilt (degrees) and its size. */
    static final float[] SACK = {0.5F, 7.5F, 3.5F, 8.0F, 1.1F};
}
