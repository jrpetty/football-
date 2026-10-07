package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * How a village folk is drawn: who it is, what it does, and what it is holding.
 *
 * <ol>
 *   <li>its own skin — face, eyes, hair, the plain clothes underneath (FolkLooks);</li>
 *   <li>its trade's outfit, hats and kit included (FolkModel shows the trade's parts);</li>
 *   <li>its colours: a guard wears its village's, everybody else a colour of its own
 *       on a scarf, a hood, a waistcoat or a shirt;</li>
 *   <li>the miner's lamp, which glows in the dark;</li>
 *   <li>armour, on the guards that have earned it;</li>
 *   <li>the tool in its hand.</li>
 * </ol>
 *
 * <p>The tool of its trade still floats over its head — but only from far enough
 * off that the outfit cannot be read, and a barrier always, for the folk that has
 * stopped because it is missing something.
 */
public class FolkRenderer extends MobRenderer<VillageFolkEntity, FolkModel> {

    private static final ResourceLocation[] OUTFIT = new ResourceLocation[FolkModel.TRADES.length];
    private static final ResourceLocation[] DYE = new ResourceLocation[FolkModel.TRADES.length];
    private static final ResourceLocation MINER_GLOW = texture("miner_glow");
    /** [caves] The cave dweller's helm lamp, lit. */
    private static final ResourceLocation CAVE_GLOW = texture("cavedweller_glow");
    /** [emerald] The emerald trader's little lantern at its pack, lit. */
    private static final ResourceLocation EMERALD_GLOW = texture("emerald_glow");
    /** What its wealth adds to its clothes, by standing (Wealth.Tier): patches, a belt, a collar, gold. */
    private static final ResourceLocation[] FINERY = {
        texture("wealth_0"), null, texture("wealth_2"), texture("wealth_3"), texture("wealth_4") };

    /** The trades whose outfit has a part dyed in its wearer's (or its village's) colour. */
    private static final java.util.Set<String> DYED = java.util.Set.of(
        "none", "farmer", "lumberjack", "rancher", "guard", "storekeeper", "hauler",
        "tailor", "enchanter", "shopkeeper");

    static {
        for (int i = 0; i < FolkModel.TRADES.length; i++) {
            String trade = FolkModel.TRADES[i];
            OUTFIT[i] = texture(trade);
            DYE[i] = DYED.contains(trade) ? texture(trade + "_dye") : null;
        }
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(McAssistantMod.MODID, "textures/entity/folk/" + name + ".png");
    }

    /**
     * A village's colours, for its watch's tabards and shields: heraldic, and all
     * dark enough for the gold bell to stand out on.
     */
    private static final int[] BANNERS = {
        0x324C9C, 0x9C2A2E, 0x2E7044, 0x5E3A86, 0x2A2A32, 0x22777E, 0xA65A22,
    };

    /** A folk's own colour: what a dyer in a village would have to hand. */
    private static final int[] DYES = {
        0xC0362F, 0x3A62B4, 0x3C8A4A, 0xD8A63A, 0x7C4CA0, 0x2E8A8E, 0x8A5634, 0x7E7E86, 0xD27A2E, 0x2E3C6E,
    };

    /** A newcomer's hood and cloak: a traveller's undyed and plant-dyed cloths. */
    private static final int[] CLOAKS = {
        0x6B5A3A, 0x4E5E44, 0x5A4A6A, 0x3E4E66, 0x7A4434, 0x5E5E58, 0x6E6248, 0x3F5A55,
    };

    private final ItemRenderer itemRenderer;

    public FolkRenderer(EntityRendererProvider.Context context) {
        super(context, new FolkModel(context.bakeLayer(FolkModel.LAYER)), 0.5F);
        this.itemRenderer = context.getItemRenderer();
        this.addLayer(new Outfit(this));
        this.addLayer(new Colours(this));
        this.addLayer(new Finery(this));
        this.addLayer(new FashionLayer(this, new FashionModel(context.bakeLayer(FashionModel.LAYER))));   // [fashion] its own clothes
        this.addLayer(new Glow(this));
        this.addLayer(new Armour(this,                // [guard-kit] armour cut to a folk (FolkArmourModel)
            new HumanoidModel<>(context.bakeLayer(FolkArmourModel.INNER)),
            new HumanoidModel<>(context.bakeLayer(FolkArmourModel.OUTER))));
        this.addLayer(new TabardLayer(this, context.getModelSet()));      // [arms] a festival tabard of the town's arms
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(VillageFolkEntity folk) {
        return FolkFaces.texture(folk);                    // [individual] its own face, built once and kept
    }

    /**
     * A villager's own size, and [individual] its own height on it (0.90 to 1.08 of it, between its parents'), a
     * little wider for a broad build and narrower for a slim one; a child growing through its childhood, from not
     * half a grown-up's height to two thirds of it.
     */
    @Override
    protected void scale(VillageFolkEntity folk, PoseStack pose, float partialTick) {
        long look = folk.clientLook();
        boolean known = com.jrpetty.mcassistant.entity.Looks.known(look);
        float h = known ? com.jrpetty.mcassistant.entity.Looks.heightOfStep(com.jrpetty.mcassistant.entity.Looks.heightStepOf(look)) : 1.0F;
        float s;
        if (folk.isBaby()) {
            int growth = com.jrpetty.mcassistant.entity.Individual.growthOf(folk.clientMarks());
            s = 0.9375F * (0.46F + 0.03F * growth) * (0.88F + 0.12F * h);
        } else {
            s = 0.9375F * h;
        }
        int build = known ? com.jrpetty.mcassistant.entity.Looks.build(look) : 1;
        float wide = build == 0 ? 0.94F : build == 2 ? 1.07F : 1.0F;
        pose.scale(s * wide, s, s * wide);
    }

    private static int trade(AssistantEntity folk) {
        return FolkModel.outfit(folk);                     // [caves] the cave dweller in its own (FolkModel.CAVE_OUTFIT)
    }

    /** The trade's clothes, over the folk's own. */
    private static class Outfit extends RenderLayer<VillageFolkEntity, FolkModel> {
        Outfit(FolkRenderer parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (folk.isInvisible() || folk.isBaby()) return;
            renderColoredCutoutModel(getParentModel(), OUTFIT[trade(folk)], pose, buffer, light, folk, -1);
        }
    }

    /** The dyed parts: the village's colours on a guard, a folk's own on everybody else. */
    private static class Colours extends RenderLayer<VillageFolkEntity, FolkModel> {
        Colours(FolkRenderer parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (folk.isInvisible() || folk.isBaby()) return;
            int t = trade(folk);
            if (DYE[t] == null) return;
            int rgb;
            long id = folk.getUUID().getMostSignificantBits();
            if ("guard".equals(FolkModel.TRADES[t])) {
                rgb = BANNERS[Math.floorMod(Math.max(0, folk.clientBanner()), BANNERS.length)];
            } else if ("none".equals(FolkModel.TRADES[t])) {
                rgb = CLOAKS[(int) Math.floorMod(id ^ (id >>> 31), (long) CLOAKS.length)];
            } else {
                long bits = folk.getUUID().getMostSignificantBits();
                rgb = DYES[(int) Math.floorMod(bits ^ (bits >>> 29) ^ t * 7L, (long) DYES.length)];
                rgb = FashionLayer.mainColour(folk, rgb);          // [fashion] its own colour, once it has chosen one
            }
            renderColoredCutoutModel(getParentModel(), DYE[t], pose, buffer, light, folk, 0xFF000000 | rgb);
        }
    }

    /** How well off it is, worn: a poor folk's patches, a belt, a collar and buttons, a gold chain. */
    private static class Finery extends RenderLayer<VillageFolkEntity, FolkModel> {
        Finery(FolkRenderer parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (folk.isInvisible() || folk.isBaby()) return;
            int tier = folk.clientWealth();
            if (tier < 0 || tier >= FINERY.length || FINERY[tier] == null) return;
            renderColoredCutoutModel(getParentModel(), FINERY[tier], pose, buffer, light, folk, -1);
        }
    }

    /** The miner's lamp and lantern, lit whatever the light around them; [caves] and the cave dweller's helm lamp. */
    private static class Glow extends RenderLayer<VillageFolkEntity, FolkModel> {
        Glow(FolkRenderer parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (folk.isInvisible()) return;
            String t = FolkModel.TRADES[trade(folk)];
            ResourceLocation lit = "miner".equals(t) ? MINER_GLOW : "cavedweller".equals(t) ? CAVE_GLOW
                : "emerald".equals(t) ? EMERALD_GLOW : null;                                    // [emerald] its lantern
            if (lit == null || folk.isBaby()) return;
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(lit));
            getParentModel().renderToBuffer(pose, glow, 0xF000F0, OverlayTexture.NO_OVERLAY, -1);
        }
    }

    /**
     * Armour, on a body the game's armour layer does not know: the game's own armour
     * pictures on boxes cut to a folk (FolkArmourModel), posed limb for limb like the folk
     * under them. [guard-kit] A model is a child's until it is told otherwise (the game's
     * EntityModel starts out young, and its armour layer copies the wearer's age across): the
     * armour was drawn as a child's, the body half size and low, the head shrunk, all of it
     * inside the folk, and a guard in a full suit showed only a patch of grey at its collar.
     * Now it is grown, sits just outside the folk, and the folk's coat and hair (and an apron,
     * a shawl) are put away under it (FolkModel.setupAnim).
     */
    private static class Armour extends RenderLayer<VillageFolkEntity, FolkModel> {
        private final HumanoidModel<VillageFolkEntity> inner;
        private final HumanoidModel<VillageFolkEntity> outer;

        Armour(FolkRenderer parent, HumanoidModel<VillageFolkEntity> inner, HumanoidModel<VillageFolkEntity> outer) {
            super(parent);
            this.inner = inner;
            this.outer = outer;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (folk.isInvisible()) return;
            piece(pose, buffer, light, folk, EquipmentSlot.CHEST);
            piece(pose, buffer, light, folk, EquipmentSlot.LEGS);
            piece(pose, buffer, light, folk, EquipmentSlot.FEET);
            piece(pose, buffer, light, folk, EquipmentSlot.HEAD);
        }

        private void piece(PoseStack pose, MultiBufferSource buffer, int light, VillageFolkEntity folk,
                           EquipmentSlot slot) {
            ItemStack worn = folk.getItemBySlot(slot);
            if (!(worn.getItem() instanceof ArmorItem armour) || armour.getEquipmentSlot() != slot) return;
            boolean legs = slot == EquipmentSlot.LEGS;
            HumanoidModel<VillageFolkEntity> model = legs ? inner : outer;
            FolkModel folkModel = getParentModel();
            model.young = false;                     // [guard-kit] grown: a child's model is drawn half size
            model.setAllVisible(false);
            follow(folkModel.getHead(), model.head, 1.0F, 1.0F, 1.0F);
            follow(folkModel.getHead(), model.hat, 1.0F, 1.0F, 1.0F);
            follow(folkModel.body(), model.body, 1.0F, 1.0F, 1.0F);
            follow(folkModel.rightArm(), model.rightArm, 1.0F, 1.0F, 1.0F);
            follow(folkModel.leftArm(), model.leftArm, 1.0F, 1.0F, 1.0F);
            follow(folkModel.rightLeg(), model.rightLeg, 1.0F, 1.0F, 1.0F);
            follow(folkModel.leftLeg(), model.leftLeg, 1.0F, 1.0F, 1.0F);
            switch (slot) {
                case HEAD -> { model.head.visible = true; model.hat.visible = true; }
                case CHEST -> { model.body.visible = true; model.rightArm.visible = true; model.leftArm.visible = true; }
                case LEGS -> { model.body.visible = true; model.rightLeg.visible = true; model.leftLeg.visible = true; }
                case FEET -> { model.rightLeg.visible = true; model.leftLeg.visible = true; }
                default -> { return; }
            }
            int dye = worn.is(ItemTags.DYEABLE) ? FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(worn, -6265536)) : -1;
            for (ArmorMaterial.Layer layer : armour.getMaterial().value().layers()) {
                model.renderToBuffer(pose, buffer.getBuffer(RenderType.armorCutoutNoCull(layer.texture(legs))),
                    light, OverlayTexture.NO_OVERLAY, layer.dyeable() ? dye : -1);
            }
        }

        private static void follow(ModelPart from, ModelPart to, float xs, float ys, float zs) {
            to.x = from.x;
            to.y = from.y;
            to.z = from.z;
            to.xRot = from.xRot;
            to.yRot = from.yRot;
            to.zRot = from.zRot;
            to.xScale = from.xScale * xs;          // a child's big head keeps its helmet on
            to.yScale = from.yScale * ys;
            to.zScale = from.zScale * zs;
        }
    }

    @Override
    public void render(VillageFolkEntity folk, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        // Sat on a bench (entity/Park): the whole of it lowered onto the seat, its legs out in front.
        // Crouched in a hiding place (hide-and-seek: entity/Families): a little lower, its knees up.
        boolean crouched = folk.getPose() == net.minecraft.world.entity.Pose.CROUCHING;
        boolean sat = folk.getPose() == net.minecraft.world.entity.Pose.SITTING || crouched;
        if (sat) {
            pose.pushPose();
            pose.translate(0.0F, crouched ? (folk.isBaby() ? -0.14F : -0.25F) : folk.isBaby() ? -0.32F : -0.6F, 0.0F);
        }
        super.render(folk, entityYaw, partialTick, pose, buffer, packedLight);
        if (sat) pose.popPose();
        QuestClient.draw(folk, pose, buffer, getFont(), this.entityRenderDispatcher, partialTick);   // [quests] "!" or "?" over its head

        double far = this.entityRenderDispatcher.distanceToSqr(folk);
        if (far < 24 * 24 && bubble(folk, pose, buffer)) return;
        if (far > 48 * 48 || folk.isNoAi()) return;     // a folk stood up to be looked at says nothing
        ItemStack icon = jobIcon(folk, far);
        if (icon.isEmpty()) return;
        float bob = Mth.sin((folk.tickCount + partialTick) * 0.07F) * 0.045F;
        pose.pushPose();
        pose.translate(0.0F, folk.getBbHeight() + 0.95F + bob, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
        pose.scale(0.55F, 0.55F, 0.55F);
        this.itemRenderer.renderStatic(icon, ItemDisplayContext.FIXED,
            packedLight, OverlayTexture.NO_OVERLAY, pose, buffer, folk.level(), folk.getId());
        pose.popPose();
    }

    /** The bubble's panel: dark and nearly solid, so the words stand out against a bright sky. */
    private static final int BUBBLE_PANEL = 0xC0101018;
    /** The bubble's words: a warm yellow, readable on the dark panel by day and by night. */
    private static final int BUBBLE_INK = 0xFFFFD94A;

    /**
     * What a folk is saying out loud, in a bubble over its head: yellow words on a dark
     * panel, lit whatever the hour, a few short lines at most. Folk never speak in the
     * chat — this is the only place their words appear outside a conversation.
     *
     * <p>Drawn the way the game draws a name tag, in two passes: the panel first (with a
     * faint ghost of the words, seen through walls), then the words on top of it. Drawn in
     * one pass, the panel sat in front of the words and the bubble read as a pale blank.
     */
    private boolean bubble(VillageFolkEntity folk, PoseStack pose, MultiBufferSource buffer) {
        FolkTalkClient.Said said = FolkTalkClient.saying(folk.getId(), folk.level().getGameTime());
        if (said == null) return false;
        net.minecraft.client.gui.Font font = getFont();
        java.util.List<net.minecraft.util.FormattedCharSequence> lines =
            font.split(net.minecraft.network.chat.FormattedText.of(said.text()), 150);
        if (lines.size() > 6) lines = lines.subList(0, 6);
        pose.pushPose();
        pose.translate(0.0F, folk.getBbHeight() + 0.75F + lines.size() * 0.25F, 0.0F);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);
        org.joml.Matrix4f matrix = pose.last().pose();
        for (int i = 0; i < lines.size(); i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(i);
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, i * 10.0F, 0x20FFFFFF, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, BUBBLE_PANEL,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            font.drawInBatch(line, x, i * 10.0F, BUBBLE_INK, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
        return true;
    }

    /** A barrier for a folk that is stuck, from any distance; its trade only from
     *  far enough away that its clothes cannot tell you. */
    private static ItemStack jobIcon(AssistantEntity folk, double distanceSq) {
        AssistantEntity.StationTask job = AssistantEntity.StationTask.byOrdinal(folk.clientJobOrdinal());
        if (job == AssistantEntity.StationTask.NONE) return ItemStack.EMPTY;
        String status = folk.clientStatus();
        if (status.startsWith("Needs") || status.startsWith("Out of")) return new ItemStack(Items.BARRIER);
        if (distanceSq < 14 * 14) return ItemStack.EMPTY;
        return new ItemStack(switch (job) {
            case FARM -> Items.WHEAT;
            case WOOD -> Items.IRON_AXE;
            case MINE -> Items.IRON_PICKAXE;
            case RANCH -> Items.SHEARS;
            case GUARD -> Items.IRON_SWORD;
            case SMELT -> Items.FURNACE;
            case FISH -> Items.FISHING_ROD;
            case STORE -> Items.CHEST;
            case HAUL -> Items.HOPPER;
            case SMITH -> Items.ANVIL;
            case TAILOR -> Items.WHITE_WOOL;
            case BEEKEEP -> Items.HONEYCOMB;
            case BREW -> Items.BREWING_STAND;
            case ENCHANT -> Items.ENCHANTED_BOOK;
            case COOK -> Items.CAKE;
            case SHOP -> Items.EMERALD;
            case SCOUT -> Items.COMPASS;
            case HUNT -> Items.BOW;
            case BANK -> Items.GOLD_INGOT;
            case CAVE -> Items.LANTERN;                    // [caves] a lantern held up in the dark
            case FERRY -> Items.OAK_BOAT;                  // [transport] the ferryman's boat
            case FLETCHER -> Items.FLETCHING_TABLE;        // [fletcher] the fletcher's table
            case GOLEMS -> Items.CARVED_PUMPKIN;           // [golems] a golem's head
            case FIREWORKS -> Items.FIREWORK_ROCKET;       // [fireworks] a rocket, of its own making
            case CARTOGRAPHER -> Items.FILLED_MAP;         // [cartographer] a sheet in hand
            case EMERALD -> Items.EMERALD;                 // [emerald] an emerald turned over in its fingers
            case NONE -> Items.AIR;
        });
    }
}
