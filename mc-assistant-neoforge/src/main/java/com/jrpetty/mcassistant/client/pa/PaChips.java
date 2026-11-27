package com.jrpetty.mcassistant.client.pa;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa33] The clickable suggestions under the talk box, worked out from where the player is: what they look at (a
 * chest, an ore, a field, a creature), what they hold, the time of day, and what the assistant last did. A new player
 * sees the three starters. Each chip is "what the button says" and "what clicking it says".
 */
final class PaChips {

    private PaChips() {}

    static List<String[]> forNow() {
        Map<String, String> c = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return List.of();
        HitResult hit = mc.player.pick(32.0, 1.0F, false);
        if (mc.crosshairPickEntity != null || mc.hitResult instanceof EntityHitResult) {
            Entity e = mc.hitResult instanceof EntityHitResult eh ? eh.getEntity() : mc.crosshairPickEntity;
            if (e instanceof Monster) c.put("Attack that", "attack that");
            else if (e instanceof Animal) {
                String n = e.getType().getDescription().getString().toLowerCase();
                c.put("Breed the " + n + "s", "breed the " + n + "s");
            }
        }
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos p = bh.getBlockPos();
            BlockState s = mc.level.getBlockState(p);
            var be = mc.level.getBlockEntity(p);
            if (be instanceof AbstractFurnaceBlockEntity) c.put("Keep this furnace fuelled", "keep the furnace fuelled");
            else if (be instanceof net.minecraft.world.Container) c.put("Put everything in that chest", "put everything in that chest");
            if (s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.DIAMOND_ORES)
                || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)) c.put("Mine this vein", "mine this vein");
            if (s.getBlock() instanceof CropBlock || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)) c.put("Farm this field", "farm this field");
            if (s.is(BlockTags.LOGS)) c.put("Chop these trees", "chop 32 logs");
            if (s.is(BlockTags.DIRT) && be == null && c.size() < 2) c.put("Build a small house there", "build a small house over there");
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.getItem() instanceof net.minecraft.world.item.PickaxeItem) c.put("Mine iron until you have 20", "mine iron until you have 20");
        if (held.getItem() instanceof net.minecraft.world.item.SwordItem) c.put("Guard me", "guard me");
        if (held.is(net.minecraft.tags.ItemTags.LOGS) || held.is(net.minecraft.tags.ItemTags.PLANKS)) c.put("Build a small house here", "build a small house here");
        if (held.getItem() instanceof net.minecraft.world.item.HoeItem || held.is(net.minecraft.world.item.Items.WHEAT_SEEDS)) c.put("Build a farm here", "build a farm here");
        long time = mc.level.getDayTime() % 24000L;
        if (time > 12000 && time < 23500) {
            c.put("Light up the area", "light up the area");
            c.put("Guard me tonight", "guard me");
        }
        if (mc.player.getFoodData().getFoodLevel() < 12) c.put("Bring me food", "bring me food");
        // after its last job: the natural next one
        String last = lastLabel();
        if (last.contains("logs")) c.put("Make planks and sticks", "make planks and sticks");
        if (last.contains("iron")) c.put("Smelt the iron", "smelt the iron");
        if (last.contains("house") || last.contains("build")) c.put("Light it up", "light up the area");
        if (PaClient.state.getList("steps", 10).isEmpty()) {
            c.putIfAbsent("Get me 16 logs", "get me 16 logs");
            c.putIfAbsent("Follow me", "follow me");
        } else {
            c.put("What are you doing?", "what are you doing");
            c.put("Stop", "stop");
        }
        if (PaClient.state.getList("log", 10).size() <= 2) {
            Map<String, String> starters = new LinkedHashMap<>();
            starters.put("Follow me", "follow me");
            starters.put("Get me 16 logs", "get me 16 logs");
            starters.put("Build a small house here", "build a small house here");
            starters.putAll(c);
            c = starters;
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : c.entrySet()) {
            out.add(new String[]{e.getKey(), e.getValue()});
            if (out.size() >= 5) break;
        }
        return out;
    }

    private static String lastLabel() {
        var log = PaClient.state.getList("log", 10);
        for (int i = log.size() - 1; i >= 0; i--) {
            var t = log.getCompound(i);
            if (t.getInt("w") == 1 && (t.getString("t").contains("Done") || t.getString("t").contains("done") || t.getString("t").contains("Finished"))) {
                return t.getString("t").toLowerCase();
            }
        }
        return "";
    }
}
