package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Village Storehouse, one unit at a time.
 *
 * <p>A storehouse unit on its own is a crate. Twenty-seven of them stacked three wide,
 * three deep and three high join into one Village Storehouse: a single store that holds
 * as much as twenty-seven chests, where a village keeps everything it owns. Every trade
 * banks its work there and draws its kit from there, instead of each hand setting a chest
 * down on its own plot. A village's builders carry the units out of the stores and lay
 * them one by one; a player can do the same.
 *
 * <p>Joined, each unit knows where it sits in the cube ({@link #PX}, {@link #PY},
 * {@link #PZ}, from the cube's low corner) and which way the cube faces, and draws its
 * part of one big picture. The goods live at the door — the middle of the bottom row of
 * the front — where anybody can stand beside them; right-click any face and the store
 * opens. Take a unit out and the rest come apart into units again, and the goods wait in
 * the door unit until the cube is whole again (break the door unit and it carries them).
 */
public class StorehouseBlock extends Block implements EntityBlock {

    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty PX = IntegerProperty.create("px", 0, 2);
    public static final IntegerProperty PY = IntegerProperty.create("py", 0, 2);
    public static final IntegerProperty PZ = IntegerProperty.create("pz", 0, 2);

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Which way a builder wants the store it is finishing to face (BuildGoal sets it). */
    private static final ThreadLocal<Direction> FRONT_HINT = new ThreadLocal<>();
    /** The cube's own joining and parting: not a unit placed or taken out. */
    private static final ThreadLocal<Boolean> CHANGING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** A creative player took it down: nothing drops. */
    private static final ThreadLocal<Boolean> NO_DROP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public StorehouseBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FORMED, Boolean.FALSE).setValue(FACING, Direction.NORTH)
            .setValue(PX, 0).setValue(PY, 0).setValue(PZ, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FORMED, FACING, PX, PY, PZ);
    }

    /** A loose unit, as placed. */
    public static BlockState loose() {
        return McAssistantMod.STOREHOUSE.get().defaultBlockState();
    }

    // ------------------------------------------------------------------ the cube

    /** Where the door sits in a cube facing this way, from its low corner. */
    public static BlockPos doorOffset(Direction facing) {
        return switch (facing) {
            case SOUTH -> new BlockPos(1, 0, 2);
            case WEST -> new BlockPos(0, 0, 1);
            case EAST -> new BlockPos(2, 0, 1);
            default -> new BlockPos(1, 0, 0);
        };
    }

    /** The low corner of the cube a joined unit is part of. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        return pos.offset(-state.getValue(PX), -state.getValue(PY), -state.getValue(PZ));
    }

    /** The door of the cube a joined unit is part of. */
    public static BlockPos door(BlockPos pos, BlockState state) {
        return origin(pos, state).offset(doorOffset(state.getValue(FACING)));
    }

    public static boolean isFormed(BlockState state) {
        return state.getBlock() instanceof StorehouseBlock && state.getValue(FORMED);
    }

    /** Is this the joined store's door, the unit that holds the goods? */
    public static boolean isDoor(BlockState state) {
        if (!isFormed(state)) return false;
        BlockPos d = doorOffset(state.getValue(FACING));
        return state.getValue(PX) == d.getX() && state.getValue(PY) == d.getY() && state.getValue(PZ) == d.getZ();
    }

    /** Where to stand to use a store: on the ground just outside its door. */
    public static BlockPos standingSpot(BlockPos door, BlockState doorState) {
        return door.relative(doorState.getValue(FACING));
    }

    /**
     * Where to walk to use the container here: for a storehouse, the ground in front of its
     * door (a path to the door block itself is a path onto the roof three blocks up, which
     * nobody can climb); for anything else, the container itself.
     */
    public static BlockPos approach(net.minecraft.world.level.BlockGetter level, BlockPos container) {
        BlockState s = level.getBlockState(container);
        return isDoor(s) ? standingSpot(container, s) : container;
    }

    /** A builder laying units: the store it finishes faces this way (null to stop saying so). */
    public static void hintFront(@Nullable Direction front) {
        FRONT_HINT.set(front);
    }

    // ------------------------------------------------------------------ joining

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level.isClientSide || CHANGING.get() || old.is(this) || state.getValue(FORMED)) return;
        tryForm(level, pos);
    }

    /**
     * If the unit here completes a cube of twenty-seven loose units, join them into a
     * storehouse. Returns its door, or null.
     */
    @Nullable
    public static BlockPos tryForm(Level level, BlockPos pos) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    BlockPos origin = pos.offset(-dx, -dy, -dz);
                    if (allLoose(level, origin)) return form(level, origin, chooseFront(level, origin));
                }
            }
        }
        return null;
    }

    /** How many of the twenty-seven places of the best cube round this unit already hold one. */
    public static int unitsTowardACube(Level level, BlockPos pos) {
        int best = 0;
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    BlockPos origin = pos.offset(-dx, -dy, -dz);
                    int n = 0;
                    for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(2, 2, 2))) {
                        BlockState s = level.getBlockState(p);
                        if (s.getBlock() instanceof StorehouseBlock && !s.getValue(FORMED)) n++;
                    }
                    best = Math.max(best, n);
                }
            }
        }
        return best;
    }

    private static boolean allLoose(Level level, BlockPos origin) {
        for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(2, 2, 2))) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof StorehouseBlock) || s.getValue(FORMED)) return false;
        }
        return true;
    }

    /**
     * Which way a new store faces: the way its builder says; else toward the player who
     * just finished it; else toward the side it can be reached from.
     */
    private static Direction chooseFront(Level level, BlockPos origin) {
        Direction hint = FRONT_HINT.get();
        if (hint != null && hint.getAxis().isHorizontal()) return hint;
        double cx = origin.getX() + 1.5, cz = origin.getZ() + 1.5;
        Player p = level.getNearestPlayer(cx, origin.getY() + 1.0, cz, 16.0, false);
        if (p != null && !p.isSpectator()) {
            double dx = p.getX() - cx, dz = p.getZ() - cz;
            return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos stand = origin.offset(doorOffset(d)).relative(d);
            if (level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()
                    && level.getBlockState(stand.above()).getCollisionShape(level, stand.above()).isEmpty()) {
                return d;
            }
        }
        return Direction.NORTH;
    }

    /** Join the cube at this corner into a store facing this way. Returns the door. */
    public static BlockPos form(Level level, BlockPos origin, Direction facing) {
        BlockPos door = origin.offset(doorOffset(facing));
        List<ItemStack> goods = new ArrayList<>();
        CHANGING.set(Boolean.TRUE);
        try {
            // Goods left waiting in a unit (the door of the store this cube was before) go
            // to the new door, wherever it is.
            for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(2, 2, 2))) {
                if (!p.equals(door) && level.getBlockEntity(p) instanceof StorehouseBlockEntity held) {
                    goods.addAll(held.takeAll());
                    level.removeBlockEntity(p);
                }
            }
            BlockState base = loose().setValue(FORMED, Boolean.TRUE).setValue(FACING, facing);
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 3; z++) {
                        level.setBlock(origin.offset(x, y, z), base.setValue(PX, x).setValue(PY, y).setValue(PZ, z), 3);
                    }
                }
            }
        } finally {
            CHANGING.set(Boolean.FALSE);
        }
        if (level.getBlockEntity(door) instanceof StorehouseBlockEntity store) {
            for (ItemStack g : goods) {
                ItemStack left = store.insert(g);
                if (!left.isEmpty()) popResource(level, door.relative(facing), left);
            }
            com.jrpetty.mcassistant.entity.Storehouses.formed(level, door);
        }
        level.playSound(null, door, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
        level.playSound(null, door, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.4F, 1.4F);
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                origin.getX() + 1.5, origin.getY() + 3.2, origin.getZ() + 1.5, 24, 1.4, 0.3, 1.4, 0.0);
        }
        return door;
    }

    // ------------------------------------------------------------------ parting

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (player.getAbilities().instabuild) NO_DROP.set(Boolean.TRUE);
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * A unit taken out: it drops (carrying the goods, if it held them), and a store it
     * was part of comes apart into loose units, the goods waiting in its door.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && !CHANGING.get()) {
            try {
                ItemStack drop = new ItemStack(McAssistantMod.STOREHOUSE_ITEM.get());
                boolean goods = false;
                if (level.getBlockEntity(pos) instanceof StorehouseBlockEntity held && held.hasGoods()) {
                    goods = true;
                    if (!held.packInto(drop, level.registryAccess())) {
                        net.minecraft.world.Containers.dropContents(level, pos, held);   // too much for one unit to carry
                    }
                    held.clearContent();
                }
                // Dropped straight into the world: a unit carrying a store's goods is never lost to
                // a rule about block drops, and a plain one follows the rule as any block does.
                if (!NO_DROP.get() && (goods || level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS))) {
                    net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(level,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
                    item.setDefaultPickUpDelay();
                    level.addFreshEntity(item);
                }
                LOG.info("[MCA-STORE] a unit at {} came out ({}{})", pos.toShortString(),
                    goods ? StorehouseBlockEntity.stacksCarried(drop) + " stacks carried" : "no goods",
                    NO_DROP.get() ? ", nothing dropped" : "");
            } finally {
                NO_DROP.set(Boolean.FALSE);
            }
            if (state.getValue(FORMED)) unform(level, origin(pos, state), door(pos, state), pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    /** Every other unit of the cube with this corner back to a loose unit. */
    private static void unform(Level level, BlockPos origin, BlockPos door, BlockPos gone) {
        com.jrpetty.mcassistant.entity.Storehouses.gone(level, door);
        CHANGING.set(Boolean.TRUE);
        try {
            for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(2, 2, 2))) {
                if (p.equals(gone)) continue;
                BlockState s = level.getBlockState(p);
                if (isFormed(s) && origin(p, s).equals(origin)) level.setBlock(p.immutable(), loose(), 3);
            }
        } finally {
            CHANGING.set(Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------ use

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isDoor(state) ? new StorehouseBlockEntity(pos, state) : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (isFormed(state)) {
            if (level.getBlockEntity(door(pos, state)) instanceof StorehouseBlockEntity store) player.openMenu(store);
            return InteractionResult.CONSUME;
        }
        int have = unitsTowardACube(level, pos);
        String goods = level.getBlockEntity(pos) instanceof StorehouseBlockEntity held && held.hasGoods()
            ? " This one holds the storehouse's goods until it is whole again." : "";
        player.displayClientMessage(Component.literal("A storehouse unit: " + have
            + " of 27. Stack 27 of them three wide, three deep and three high and they join into a Village Storehouse."
            + goods), true);
        return InteractionResult.CONSUME;
    }

    /** A unit carrying goods puts them in the store it joins, or keeps them until it does. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !StorehouseBlockEntity.carriesGoods(stack)) return;
        BlockState now = level.getBlockState(pos);
        StorehouseBlockEntity into = null;
        if (isFormed(now) && level.getBlockEntity(door(pos, now)) instanceof StorehouseBlockEntity store) {
            into = store;
        } else if (now.getBlock() instanceof StorehouseBlock) {
            into = level.getBlockEntity(pos) instanceof StorehouseBlockEntity there ? there : null;
            if (into == null) {
                into = new StorehouseBlockEntity(pos, now);
                level.setBlockEntity(into);
            }
        }
        if (into != null) into.unpackFrom(stack, level.registryAccess());
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return isDoor(state);
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StorehouseBlockEntity store
            ? net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(store) : 0;
    }
}
