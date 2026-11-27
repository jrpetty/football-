package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.ScreenFit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33] The assistant's action wheel (X): look at something and the ring offers what fits it first (mine this vein,
 * put these in that chest, attack that, farm this field, build here), then the everyday orders round the rest. Click
 * one, or hold X, point and let go. The world stays in view; the order is sent with what was looked at.
 */
public final class PaWheelScreen extends Screen {

    private static final int RING_X = 150, RING_Y = 92, BOX_H = 18;
    private static final int PANEL = 0xD0102A2E, LIT = 0xF02BB5A0, RIM = 0xFF1F8A7A, INK = 0xFFF2F7F6;
    private final List<String[]> items = new ArrayList<>();   // label, what it says, icon
    private int hovered = -1;
    private String target = "";
    private int[][] laid = new int[0][];
    private int[] laidW = new int[0];

    public PaWheelScreen() {
        super(Component.literal("Assistant actions"));
        build();
    }

    private void build() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Vec3 eye = mc.player.getEyePosition(1.0F);
        HitResult hit = mc.player.pick(48.0, 1.0F, false);
        Entity looked = mc.crosshairPickEntity;
        if (looked == null) {
            var end = eye.add(mc.player.getViewVector(1.0F).scale(32));
            EntityHitResult eh = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(mc.player, eye, end,
                mc.player.getBoundingBox().expandTowards(mc.player.getViewVector(1.0F).scale(32)).inflate(1), e -> e.isAlive() && e.isPickable(), 1024);
            if (eh != null) looked = eh.getEntity();
        }
        if (looked instanceof Monster) { add("Attack that", "attack that", "fight"); target = looked.getName().getString(); }
        else if (looked instanceof Animal a) {
            String n = a.getType().getDescription().getString().toLowerCase();
            add("Breed the " + n + "s", "breed the " + n + "s", "breed");
            add("Hunt it", "hunt 1 " + n, "hunt");
            target = looked.getName().getString();
        }
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK && looked == null) {
            BlockPos p = bh.getBlockPos();
            BlockState s = mc.level.getBlockState(p);
            var be = mc.level.getBlockEntity(p);
            target = s.getBlock().getName().getString();
            if (be instanceof AbstractFurnaceBlockEntity) add("Keep it fuelled", "keep the furnace fuelled", "fuel");
            else if (be instanceof net.minecraft.world.Container) {
                add("Put these in that chest", "put everything in that chest", "deposit");
            }
            if (s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.DIAMOND_ORES)
                || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)) add("Mine this vein", "mine this vein", "mine_ore");
            if (s.getBlock() instanceof CropBlock || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) add("Farm this field", "farm this field", "farm");
            if (s.is(BlockTags.LOGS)) add("Chop these trees", "chop 32 logs", "gather");
            if (be == null && !s.is(BlockTags.LOGS)) {
                add("Build a house there", "build a small house over there", "build");
                add("Make a field there", "build a farm over there", "farm");
                add("Guard that spot", "guard over there", "guard");
            }
        }
        add("Come here", "come here", "follow");
        add("Follow me", "follow me", "follow");
        add("Guard me", "guard me", "guard");
        add("Stay", "stay here", "stay");
        add("Light it up", "light up the area", "light_area");
        add("Bring me food", "bring me food", "bring_food");
        boolean hushed = PaClient.state.getCompound("set").getInt("mute") > 0;   // [pa33b] hush it, or let it talk again
        add(hushed ? "Talk again" : "Quiet", hushed ? "you can talk again" : "be quiet", "paused");
        add("Stop", "stop", "paused");
        add("Open the panel", "#panel", "craft");
        while (items.size() > 12) items.remove(items.size() - 2);
    }

    private void add(String label, String say, String icon) {
        for (String[] i : items) if (i[0].equals(label)) return;
        items.add(new String[]{label, say, icon});
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) { }

    private int under(double mx, double my) {
        for (int i = 0; i < laid.length; i++) {
            if (Math.abs(mx - laid[i][0]) * 2 <= laidW[i] && Math.abs(my - laid[i][1]) * 2 <= BOX_H + 2) return i;
        }
        if (ScreenFit.isColumns(laid, width)) return -1;
        double dx = mx - width / 2.0, dy = my - height / 2.0;
        if (dx * dx + dy * dy < 24 * 24 || items.isEmpty()) return -1;
        double ang = Math.atan2(dx / ScreenFit.ringRadiusX(width, RING_X), -dy / ScreenFit.ringRadiusY(height, RING_Y));
        if (ang < 0) ang += Math.PI * 2;
        return (int) Math.round(ang / (Math.PI * 2 / items.size())) % items.size();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int n = items.size();
        int[] bw = new int[n];
        for (int i = 0; i < n; i++) bw[i] = Math.max(70, font.width(items.get(i)[0]) + 24);
        laid = ScreenFit.ring(width, height, bw, BOX_H, RING_X, RING_Y);
        laidW = bw;
        hovered = under(mx, my);
        int cx = width / 2, cy = height / 2;
        for (int i = 0; i < n; i++) {
            int x = laid[i][0], y = laid[i][1];
            boolean lit = i == hovered;
            int x0 = x - bw[i] / 2, y0 = y - BOX_H / 2;
            g.fill(x0 - 1, y0 - 1, x0 + bw[i] + 1, y0 + BOX_H + 1, RIM);
            g.fill(x0, y0, x0 + bw[i], y0 + BOX_H, lit ? LIT : PANEL);
            PaIcons.draw(g, items.get(i)[2], x0 + 3, y0 + 3);
            g.drawString(font, items.get(i)[0], x0 + 18, y - 4, lit ? 0xFF0B1F22 : INK, false);
        }
        String mid = hovered >= 0 ? items.get(hovered)[0] : PaClient.name();
        int w = Math.max(80, font.width(mid) + 16);
        g.fill(cx - w / 2 - 1, cy - 13, cx + w / 2 + 1, cy + 13, RIM);
        g.fill(cx - w / 2, cy - 12, cx + w / 2, cy + 12, PANEL);
        g.drawCenteredString(font, mid, cx, cy - 8, hovered >= 0 ? 0xFF8FE3D3 : INK);
        g.drawCenteredString(font, target.isEmpty() ? "what should I do?" : "at: " + target, cx, cy + 2, 0xFF9FC9C2);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = under(mx, my);
        if (i >= 0) { run(i); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (PaClient.WHEEL.matches(key, scan) && hovered >= 0) { run(hovered); return true; }
        return super.keyReleased(key, scan, mods);
    }

    private void run(int i) {
        String say = items.get(i)[1];
        onClose();
        if (say.equals("#panel")) {
            net.minecraft.nbt.CompoundTag a = new net.minecraft.nbt.CompoundTag();
            a.putString("tab", "talk");
            PaClient.send("open", a);
            return;
        }
        if (!PaClient.has()) {
            PaClient.note = "You haven't an assistant yet: press " + PaClient.PANEL.getTranslatedKeyMessage().getString() + " to get one.";
            PaClient.noteAt = System.currentTimeMillis();
            return;
        }
        PaClient.talk(say, false);
    }
}
