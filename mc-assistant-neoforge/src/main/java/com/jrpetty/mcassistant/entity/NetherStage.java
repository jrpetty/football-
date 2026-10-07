package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.NetherItems;
import com.jrpetty.mcassistant.item.RunnersSatchelItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [nether] The pictures' stage (/village nether stage), run where the pictures are wanted, out past the town:
 * <ul>
 * <li>at home: a gateway (an obsidian frame, lit) with three Nether runners in their gear stood before it (showcases:
 *     the soot-dark coat, the gold-banded skullcap, the gold charm or a gold piece, a bow, the satchel), trophies hung on
 *     its frame, and a wart farm of eight soul sand beside it, the wart at every age;</li>
 * <li>in the Nether, over it at an eighth of the distance: a pocket cut out of the Nether, the portal the gateway comes
 *     out of, the runners' outpost walled round it (NetherOutpost.stampForTests); a wall of quartz with two runners at
 *     it; a piglin turning a gold ingot over before a runner in gold, what it threw back on the ground; a blaze over its
 *     spawner on a step of nether brick, a runner standing off with its bow drawn on it.</li>
 * </ul>
 * The town's runners (picked for the pictures from its grown hands, if it has none) are then sent through the gateway for
 * real. Returns where to look from: "VIEW name x y z ax ay az" at home, "NVIEW ..." in the Nether.
 */
public final class NetherStage {

    private NetherStage() {}

    /** The tag on everything the stage sets down that is not the world's (the showcases, the piglin, the blaze). */
    static final String LINEUP = "nether_lineup";
    /** The Nether pocket's floor. */
    static final int NY = 70;

    static List<String> stage(ServerLevel level, BlockPos at) {
        List<String> out = new ArrayList<>();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            out.add("NETHER no Nether in this world");
            return out;
        }
        for (ServerLevel l : new ServerLevel[]{ level, nether }) {
            List<Entity> old = new ArrayList<>();
            for (Entity e : l.getAllEntities()) if (e.getTags().contains(LINEUP)) old.add(e);
            for (Entity e : old) e.discard();
        }
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos base = new BlockPos(at.getX(), g, at.getZ());
        // The ground: a level patch, stone under grass, cleared above.
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -4; dz <= 14; dz++) {
                BlockPos p = base.offset(dx, -1, dz);
                level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), 2);
                for (int y = 0; y <= 7; y++) level.setBlock(p.above(1 + y), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // The gateway: an obsidian frame four wide and five high, its face to the south, lit.
        BlockPos gate = base.offset(0, 0, 8);
        BlockPos portal = frame(level, gate);
        out.add("GATE " + portal.getX() + " " + portal.getY() + " " + portal.getZ());
        if (v != null && Villages.builtAt(v.id(), "gateway") == null) {
            Villages.builtAtForTests(v.id(), "gateway", gate);
            Ledger.built(v.id(), "gateway", gate, Direction.SOUTH);
            Villages.noteProject(v.id(), "gateway", level.getGameTime());
        }
        // Three runners in their gear before it, facing the camera.
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity show = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (show == null) continue;
            show.moveTo(gate.getX() - 2.0 + 2.0 * i + 0.5, gate.getY(), gate.getZ() - 2.5, 180.0F, 0.0F);
            show.setYHeadRot(180.0F);
            show.setYBodyRot(180.0F);
            show.makeShowcase(StationTask.NETHER);
            dress(show, i);
            show.rename(i == 1 ? "The lead runner" : "A Nether runner");
            show.addTag(LINEUP);
            level.addFreshEntity(show);
        }
        // Trophies on the gateway's frame, and the wart farm beside it.
        Direction front = Direction.NORTH;
        hang(level, gate.offset(-1, 1, 0).relative(front), front, new ItemStack(Items.GHAST_TEAR), "A ghast tear — brought through by the runners");
        hang(level, gate.offset(2, 1, 0).relative(front), front, new ItemStack(Items.BLAZE_ROD), "The first blaze rod — brought through by the runners");
        hang(level, gate.offset(-1, 0, 0).relative(front), front, new ItemStack(Items.WITHER_SKELETON_SKULL), "A wither skeleton skull");
        hang(level, gate.offset(2, 0, 0).relative(front), front, new ItemStack(Items.ENDER_PEARL), "An ender pearl — a piglin's barter");
        BlockPos farm = base.offset(6, -1, 2);
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 2; b++) {
                BlockPos s = farm.offset(a, 0, b);
                level.setBlock(s, Blocks.SOUL_SAND.defaultBlockState(), 2);
                level.setBlock(s.above(), Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, Math.min(3, a + b)), 2);
            }
        }
        out.add("VIEW nether-1-gateway " + gate.getX() + " " + (gate.getY() + 1) + " " + (gate.getZ() - 11) + " " + gate.getX() + " " + (gate.getY() + 2) + " " + gate.getZ());
        out.add("VIEW nether-5-wart-farm " + (farm.getX() + 2) + " " + (farm.getY() + 4) + " " + (farm.getZ() - 4) + " " + (farm.getX() + 2) + " " + farm.getY() + " " + farm.getZ());
        // The far side: a pocket of the Nether over the gateway, the portal it comes out of, the outpost round it.
        int nx = Math.floorDiv(gate.getX(), 8), nz = Math.floorDiv(gate.getZ(), 8);
        pocket(nether, nx, nz);
        BlockPos np = new BlockPos(nx, NY, nz);
        BlockPos netherPortal = frame(nether, np);
        UUID village = v != null ? v.id() : UUID.nameUUIDFromBytes("nether-stage".getBytes());
        NetherOutpost.Room room = NetherOutpost.stampForTests(nether, village, netherPortal, level.getDayTime() / 24000L);
        out.add("NETHER pocket at " + nx + " " + NY + " " + nz + (room != null ? ", the outpost walled in (front " + room.front().getName() + ")" : ""));
        BlockPos outside = room != null ? room.outside() : np.south(4);
        out.add("NVIEW nether-2-outpost " + (outside.getX() + 6) + " " + (NY + 3) + " " + (outside.getZ() + 6) + " " + np.getX() + " " + (NY + 2) + " " + np.getZ());
        // The quartz wall, east of the outpost, two runners at it with their picks.
        BlockPos wall = np.offset(12, 0, -3);
        for (int dz = 0; dz < 7; dz++) {
            for (int dy = 0; dy < 5; dy++) {
                BlockPos p = wall.offset(0, dy, dz);
                boolean ore = (dz * 3 + dy * 5) % 4 != 0;
                nether.setBlock(p, ore ? Blocks.NETHER_QUARTZ_ORE.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState(), 2);
            }
        }
        for (int i = 0; i < 2; i++) {
            VillageFolkEntity show = shown(nether, wall.getX() - 1.5, NY, wall.getZ() + 2.0 + 2.5 * i, -90.0F, i + 3);
            if (show != null) show.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        }
        out.add("NVIEW nether-3-quartz-wall " + (wall.getX() - 7) + " " + (NY + 2) + " " + (wall.getZ() + 3) + " " + wall.getX() + " " + (NY + 2) + " " + (wall.getZ() + 3));
        // The barter: a piglin turning a gold ingot over before a runner in gold; what it threw back on the ground.
        BlockPos bart = np.offset(-12, 0, 2);
        Piglin pig = EntityType.PIGLIN.create(nether);
        if (pig != null) {
            pig.moveTo(bart.getX() + 0.5, NY, bart.getZ() + 0.5, 90.0F, 0.0F);
            pig.setNoAi(true);
            pig.setPersistenceRequired();
            pig.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.GOLD_INGOT));
            pig.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
            pig.addTag(LINEUP);
            nether.addFreshEntity(pig);
        }
        VillageFolkEntity trader = shown(nether, bart.getX() + 3.5, NY, bart.getZ() + 0.5, 90.0F, 1);
        if (trader != null) trader.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLD_INGOT));
        for (ItemStack loot : new ItemStack[]{ new ItemStack(Items.ENDER_PEARL, 2), new ItemStack(Items.OBSIDIAN), new ItemStack(Items.STRING, 5) }) {
            ItemEntity e = new ItemEntity(nether, bart.getX() + 1.5, NY + 0.2, bart.getZ() + 1.5, loot);
            e.setNeverPickUp();
            e.setUnlimitedLifetime();
            e.addTag(LINEUP);
            nether.addFreshEntity(e);
        }
        out.add("NVIEW nether-4-barter " + (bart.getX() + 2) + " " + (NY + 2) + " " + (bart.getZ() + 6) + " " + (bart.getX() + 1) + " " + (NY + 1) + " " + bart.getZ());
        // The blazes: a spawner on a step of nether brick, a blaze over it, a runner standing off with its bow drawn.
        BlockPos sp = np.offset(0, 0, -12);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) nether.setBlock(sp.offset(dx, -1, dz), Blocks.NETHER_BRICKS.defaultBlockState(), 2);
        nether.setBlock(sp, Blocks.SPAWNER.defaultBlockState(), 2);
        if (nether.getBlockEntity(sp) instanceof SpawnerBlockEntity sbe) sbe.setEntityId(EntityType.BLAZE, nether.getRandom());
        Blaze blaze = EntityType.BLAZE.create(nether);
        if (blaze != null) {
            blaze.moveTo(sp.getX() + 0.5, NY + 2.5, sp.getZ() + 0.5, 0.0F, 0.0F);
            blaze.setNoAi(true);
            blaze.setPersistenceRequired();
            blaze.addTag(LINEUP);
            nether.addFreshEntity(blaze);
        }
        VillageFolkEntity archer = shown(nether, sp.getX() + 0.5, NY, sp.getZ() + 9.5, 180.0F, 2);
        if (archer != null) {
            archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            archer.setXRot(-12.0F);
        }
        out.add("NVIEW nether-5-blaze " + (sp.getX() + 6) + " " + (NY + 3) + " " + (sp.getZ() + 10) + " " + sp.getX() + " " + (NY + 2) + " " + (sp.getZ() + 2));
        // The town's runners through the gateway for real, picked for the pictures if it has none.
        if (v != null) {
            List<VillageFolkEntity> team = NetherRunners.runners(v.id());
            if (team.isEmpty()) {
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (team.size() >= 2) break;
                    if (a instanceof VillageFolkEntity c && Patrols.spareForStage(c)        // never the watch nor the leader
                            && c.trip() == null && c.expedition() == null) {
                        BlockPos post = NetherRunners.post(level, v);
                        c.setStation(post, StationTask.NETHER);
                        c.assignPlot(WorkZone.around(post, 4, WorkZone.DEFAULT_DEPTH), "The Gateway");
                        team.add(c);
                    }
                }
            }
            NetherPlan.daysForTests(0.5);
            NetherRuns.Run r = NetherRuns.sendForTests(level, v);
            NetherPlan.daysForTests(null);
            out.add("RUN " + (r == null ? "the runners could not go: " + Ledger.note(v.id(), "nether.plan") : r.members().size() + " runners through the gateway: " + r.planWords));
        }
        return out;
    }

    /** A runner in its gear (the town's iron with a piece of gold, a bow or a pick, the satchel at its hip), dressed for
     *  the pictures (a showcase's, for nothing). */
    static void dress(VillageFolkEntity show, int i) {
        show.setItemSlot(EquipmentSlot.HEAD, i % 2 == 0 ? new ItemStack(NetherItems.GOLD_CHARM.get()) : new ItemStack(Items.GOLDEN_HELMET));
        show.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        show.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        show.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        show.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(i == 1 ? Items.IRON_SWORD : Items.BOW));
        show.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ItemStack satchel = new ItemStack(NetherItems.RUNNERS_SATCHEL.get());
        RunnersSatchelItem.pack(satchel, new ItemStack(Items.QUARTZ, 24));
        show.insertGiven(satchel);
    }

    /** A runner in its gear stood in the Nether for the pictures. */
    static VillageFolkEntity shown(ServerLevel nether, double x, int y, double z, float yaw, int i) {
        VillageFolkEntity show = McAssistantMod.VILLAGE_FOLK.get().create(nether);
        if (show == null) return null;
        show.moveTo(x, y, z, yaw, 0.0F);
        show.setYHeadRot(yaw);
        show.setYBodyRot(yaw);
        show.makeShowcase(StationTask.NETHER);
        dress(show, i);
        show.rename("A Nether runner");
        show.addTag(LINEUP);
        nether.addFreshEntity(show);
        return show;
    }

    /** An obsidian frame four wide and five high along x, its lowest inside block here, lit. Returns a portal block. */
    static BlockPos frame(ServerLevel level, BlockPos at) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                BlockPos p = at.offset(dx, dy, 0);
                boolean edge = dx == -1 || dx == 2 || dy == -1 || dy == 3;
                level.setBlock(p, edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) level.setBlock(at.offset(dx, dy, 0), portal, 2 | 16);
        return at.immutable();
    }

    /** A pocket cut out of the Nether: forty by forty, eight high, a netherrack floor and roof and walls, glowstone in the
     *  roof for light; the ground kept awake a while for the pictures. */
    static void pocket(ServerLevel nether, int nx, int nz) {
        for (int cx = (nx - 24) >> 4; cx <= (nx + 24) >> 4; cx++) {
            for (int cz = (nz - 24) >> 4; cz <= (nz + 24) >> 4; cz++) nether.getChunk(cx, cz);
        }
        for (int x = nx - 21; x <= nx + 21; x++) {
            for (int z = nz - 21; z <= nz + 21; z++) {
                boolean edge = Math.abs(x - nx) >= 20 || Math.abs(z - nz) >= 20;
                for (int y = NY - 3; y <= NY + 10; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st;
                    if (y < NY || y >= NY + 9 || edge) st = Blocks.NETHERRACK.defaultBlockState();
                    else st = Blocks.AIR.defaultBlockState();
                    if (y == NY + 9 && !edge && Math.floorMod(x * 7 + z * 13, 23) == 0) st = Blocks.GLOWSTONE.defaultBlockState();
                    nether.setBlock(p, st, 2);
                }
            }
        }
    }

    /** An item frame hung for the pictures, the thing in it named. */
    static void hang(ServerLevel level, BlockPos at, Direction facing, ItemStack s, String name) {
        ItemFrame f = new ItemFrame(level, at, facing);
        if (!f.survives()) return;
        ItemStack shown = s.copy();
        shown.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        f.setItem(shown, false);
        f.addTag(LINEUP);
        level.addFreshEntity(f);
    }

}
