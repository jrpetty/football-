package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [roles-a] The civic posts set out for the pictures ("/village offices stage", tools/realworld/smoke.py roles_a_stage),
 * from palettes and stand-ins, not the stores: the office dress in a row (the healer, the banker and the ferryman in
 * their own clothes; the elder, teacher, curator, crier, warden with its lantern, and the sexton in their offices'
 * garments); the infirmary with the healer at a bedside (Infirmary.stage); the sexton at a new grave with two mourners;
 * and a street of torch posts with the lamplighter at one, for a picture at dusk. Returns "VIEW name x y z ax ay az"
 * lines: where a camera stands, and what it looks at.
 */
public final class RolesStage {

    private RolesStage() {}

    /** One stand-in of the row: its name, its trade (its outfit), the office it dresses for (Weave.office), its colour. */
    private record Sitter(String name, StationTask trade, String office, DyeColor colour) {}

    private static final Sitter[] ROW = {
        new Sitter("Healer", StationTask.HEAL, "healer", DyeColor.LIGHT_GRAY),
        new Sitter("Banker", StationTask.BANK, "", DyeColor.BLACK),
        new Sitter("Ferryman", StationTask.FERRY, "", DyeColor.BLUE),
        new Sitter("Elder", StationTask.FARM, "elder", DyeColor.PURPLE),
        new Sitter("Teacher", StationTask.FARM, "teacher", DyeColor.GREEN),
        new Sitter("Curator", StationTask.FARM, "curator", DyeColor.BROWN),
        new Sitter("Crier", StationTask.FARM, "crier", DyeColor.RED),
        new Sitter("Warden", StationTask.FARM, "warden", DyeColor.GRAY),
        new Sitter("Sexton", StationTask.FARM, "sexton", DyeColor.BLACK),
    };

    public static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        int x = at.getX(), z = at.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 1. The office dress, in a row facing south.
        Showcase.stage(level, x - 12, x + 12, z - 4, z + 10, y);
        for (int i = 0; i < ROW.length; i++) {
            Sitter s = ROW[i];
            VillageFolkEntity f = standIn(level, new BlockPos(x - 8 + i * 2, y, z), s.trade(), s.name(), 0.0F);
            if (f == null) continue;
            dress(f, s.office(), s.colour());
            if (s.office().equals("warden")) f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.LANTERN));
        }
        out.add("VIEW dress " + x + " " + (y + 1) + " " + (z + 9) + " " + x + " " + (y + 1) + " " + z);
        // 2. The healer at the infirmary (its stand-in in the healer's clothes).
        int ix = x + 34;
        for (String l : Infirmary.stage(level, new BlockPos(ix, y, z))) if (l.startsWith("VIEW ")) out.add(l.replace("VIEW infirmary", "VIEW healer"));
        // 3. The sexton at a new grave, two of the town standing by.
        int gx = x - 34;
        Showcase.stage(level, gx - 9, gx + 9, z - 8, z + 10, y);
        BlockPos yard = new BlockPos(gx, y, z);
        BuildGoal.stamp(level, "graveyard", yard, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        BlockPos mound = yard.relative(Direction.EAST, Graves.PLOTS[0][0]).relative(Direction.NORTH, Graves.PLOTS[0][1]);
        Graves.headstone(level, mound.north(), mound, Direction.SOUTH, new Ledger.Grave("Hazel", 0L, 52L * 5, "of old age", "", "", "FARM"));
        BlockPos side = mound.south(2);
        VillageFolkEntity sexton = standIn(level, side.east(), StationTask.FARM, "Sexton", 180.0F);
        if (sexton != null) {
            dress(sexton, "sexton", DyeColor.BLACK);
            sexton.getLookControl().setLookAt(mound.getX() + 0.5, mound.getY() + 0.5, mound.getZ() + 0.5);
            FolkTalk.speak(sexton, "Rest well, Hazel. The town won't forget you.");
        }
        standIn(level, side.south().west(), StationTask.FARM, "Rowan", 180.0F);
        standIn(level, side.south().east(2), StationTask.COOK, "Tansy", 180.0F);
        out.add("VIEW sexton " + (gx + 1) + " " + (y + 3) + " " + (z + 8) + " " + mound.getX() + " " + y + " " + mound.getZ());
        // 4. A street of torch posts at dusk, the lamplighter (a quarter's warden, its lantern in hand) at one.
        int lz = z + 30;
        Showcase.stage(level, x - 12, x + 12, lz - 4, lz + 6, y);
        for (int dx = -9; dx <= 9; dx += 6) {
            BlockPos post = new BlockPos(x + dx, y, lz);
            level.setBlock(post, Blocks.OAK_FENCE.defaultBlockState(), 3);
            if (dx != 3) level.setBlock(post.above(), Blocks.TORCH.defaultBlockState(), 3);   // one still to light
        }
        for (int dx = -11; dx <= 11; dx++) {
            for (int dz = 1; dz <= 3; dz++) level.setBlock(new BlockPos(x + dx, y - 1, lz + dz), Blocks.DIRT_PATH.defaultBlockState(), 3);
        }
        VillageFolkEntity lighter = standIn(level, new BlockPos(x + 3, y, lz + 1), StationTask.FARM, "Lamplighter", 180.0F);
        if (lighter != null) {
            dress(lighter, "warden", DyeColor.GRAY);
            lighter.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.LANTERN));
            lighter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));
            lighter.getLookControl().setLookAt(x + 3.5, y + 1.5, lz + 0.5);
        }
        out.add("VIEW lamps " + (x + 7) + " " + (y + 2) + " " + (lz + 8) + " " + (x + 3) + " " + (y + 1) + " " + lz);
        return out;
    }

    /** Its office's garments (Weave.office), in this colour: a body garment and a hat. */
    static void dress(VillageFolkEntity f, String office, DyeColor colour) {
        if (office.isEmpty()) return;
        Style s = f.style();
        int c = colour.getId();
        s.main = c;
        s.accent = DyeColor.WHITE.getId();
        for (Garment g : Weave.office(office)) {
            if (!s.worn[g.slot.ordinal()].isEmpty()) continue;
            s.worn[g.slot.ordinal()] = g.dyeable() ? g.dyed(c) : new ItemStack(g.item());
        }
        s.dressed = true;
        f.showStyle(s.pack(false));
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, StationTask trade, String name, float yaw) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        f.moveTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }
}
