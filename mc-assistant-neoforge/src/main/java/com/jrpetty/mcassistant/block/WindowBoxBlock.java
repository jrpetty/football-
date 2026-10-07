package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * [workitems] A window box: a painted box of earth hung on its brackets under a window, three clumps of the flower it was
 * made with growing in it. Hung by a household's gardener (entity/WindowBoxes), it makes the household a little happier
 * and the house the prettier and the dearer. Water it with a watering can or a bucket; untended it wilts. In winter the
 * flowers die back to stalks, and come again in the spring.
 */
public class WindowBoxBlock extends Block {

    /** The way the box looks out from the wall it hangs on. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Flower> FLOWER = EnumProperty.create("flower", Flower.class);
    /** In flower (or, out of season or dry, its stalks only). */
    public static final BooleanProperty BLOOM = BooleanProperty.create("bloom");

    /** The flowers a box is made with (the recipe's flower), each its own look. */
    public enum Flower implements StringRepresentable {
        POPPY("poppy"), DANDELION("dandelion"), BLUE_ORCHID("blue_orchid"), ALLIUM("allium"), AZURE_BLUET("azure_bluet"),
        RED_TULIP("red_tulip"), ORANGE_TULIP("orange_tulip"), WHITE_TULIP("white_tulip"), PINK_TULIP("pink_tulip"),
        OXEYE_DAISY("oxeye_daisy"), CORNFLOWER("cornflower"), LILY_OF_THE_VALLEY("lily_of_the_valley");

        private final String name;

        Flower(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }

        /** The flower itself, as an item. */
        public Item item() {
            return BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(name));
        }

        /** "poppies", "cornflowers", "lilies of the valley". */
        public String plural() {
            return switch (this) {
                case POPPY -> "poppies";
                case DANDELION -> "dandelions";
                case LILY_OF_THE_VALLEY -> "lilies of the valley";
                case AZURE_BLUET -> "azure bluets";
                case OXEYE_DAISY -> "oxeye daisies";
                default -> name.replace('_', ' ') + "s";
            };
        }

        /** The box's flower for a flower item, or null for one that does not go in a box. */
        @Nullable
        public static Flower of(Item flower) {
            for (Flower f : values()) if (f.item() == flower) return f;
            return null;
        }
    }

    private static final VoxelShape S = Shapes.or(box(0, 8, 0, 16, 15.5, 7.5), box(2, 4, 0, 4, 8, 5), box(12, 4, 0, 14, 8, 5));
    private static final VoxelShape N = Shapes.or(box(0, 8, 8.5, 16, 15.5, 16), box(12, 4, 11, 14, 8, 16), box(2, 4, 11, 4, 8, 16));
    private static final VoxelShape E = Shapes.or(box(0, 8, 0, 7.5, 15.5, 16), box(0, 4, 12, 5, 8, 14), box(0, 4, 2, 5, 8, 4));
    private static final VoxelShape W = Shapes.or(box(8.5, 8, 0, 16, 15.5, 16), box(11, 4, 2, 16, 8, 4), box(11, 4, 12, 16, 8, 14));

    public WindowBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(FLOWER, Flower.POPPY).setValue(BLOOM, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FLOWER, BLOOM);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case NORTH -> N;
            case EAST -> E;
            case WEST -> W;
            default -> S;
        };
    }

    /** Hung on the wall it was set against, looking out from it. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        Direction out = face.getAxis().isHorizontal() ? face : ctx.getHorizontalDirection().getOpposite();
        BlockState s = defaultBlockState().setValue(FACING, out);
        return canSurvive(s, ctx.getLevel(), ctx.getClickedPos()) ? s : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction out = state.getValue(FACING);
        BlockPos wall = pos.relative(out.getOpposite());
        return level.getBlockState(wall).isFaceSturdy(level, wall, out);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return dir == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Is this the watering can (a modpack's or a town's) or a bucket of water? */
    public static boolean waters(ItemStack s) {
        if (s.is(Items.WATER_BUCKET)) return true;
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("watering_can");
    }

    /** Watered by a player: the earth darkened, the flowers up again (out of winter), and the household's box tended. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!waters(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel server) {
            boolean bloom = com.jrpetty.mcassistant.entity.WindowBoxes.watered(server, pos, player.getName().getString());
            server.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 0.95, pos.getZ() + 0.5, 12, 0.35, 0.05, 0.2, 0.0);
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.4F, 1.4F);
            // A bucket of water is poured out, not kept full (a can is a modpack's, and minds its own water).
            if (stack.is(Items.WATER_BUCKET) && !player.getAbilities().instabuild) {
                player.setItemInHand(hand, net.minecraft.world.item.ItemUtils.createFilledResult(stack, player, new ItemStack(Items.BUCKET)));
            }
            player.displayClientMessage(Component.literal(bloom ? "You water the window box. The " + state.getValue(FLOWER).plural()
                + " lift their heads." : "You water the window box. The flowers will be back in the spring."), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        player.displayClientMessage(Component.literal(com.jrpetty.mcassistant.entity.WindowBoxes.describe((ServerLevel) level, pos, state)), true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!old.is(this) && level instanceof ServerLevel server) com.jrpetty.mcassistant.entity.WorkSites.boxHung(server, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moving) {
        if (!now.is(this) && level instanceof ServerLevel server) com.jrpetty.mcassistant.entity.WorkSites.boxGone(server, pos);
        super.onRemove(state, level, pos, now, moving);
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 20;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 5;
    }
}
