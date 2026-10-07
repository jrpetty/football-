package com.jrpetty.mcassistant.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * [pets] The pet bowl: a household's dish for its dog or its cat, set on the floor by the hearth (entity/Pets).
 * It holds up to three servings, and shows what is in it: bones and meat for a dog, fish for a cat. The
 * household fills it from its chest or the stores of a morning; the pet eats a serving when it is hungry. A
 * player may put a bone, a piece of meat or a fish in it too, and right-click it empty-handed to look in.
 */
public class PetBowlBlock extends Block {

    /** How many servings are in it: none to three. */
    public static final IntegerProperty SERVINGS = IntegerProperty.create("servings", 0, 3);
    /** What is in it is fish (a cat's) rather than bones and meat (a dog's). */
    public static final BooleanProperty FISH = BooleanProperty.create("fish");

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 4.0, 13.0);

    public PetBowlBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SERVINGS, 0).setValue(FISH, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SERVINGS, FISH);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos under = pos.below();
        return level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    /** What a dog eats out of it: a bone, or meat raw or cooked. */
    public static boolean dogFood(ItemStack s) {
        return s.is(Items.BONE) || s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN)
            || s.is(Items.RABBIT) || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON)
            || s.is(Items.COOKED_CHICKEN) || s.is(Items.COOKED_RABBIT);
    }

    /** What a cat eats out of it: fish, raw or cooked. */
    public static boolean catFood(ItemStack s) {
        return s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
    }

    public static int servings(BlockState state) {
        return state.getBlock() instanceof PetBowlBlock ? state.getValue(SERVINGS) : 0;
    }

    /** So many servings more of fish or of meat, up to three. Returns how many went in. */
    public static int fill(Level level, BlockPos pos, int n, boolean fish) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof PetBowlBlock) || n <= 0) return 0;
        int have = st.getValue(SERVINGS);
        // Fish on top of meat would be a poor dinner for either: what is in it is one or the other.
        if (have > 0 && st.getValue(FISH) != fish) return 0;
        int put = Math.min(3 - have, n);
        if (put <= 0) return 0;
        level.setBlock(pos, st.setValue(SERVINGS, have + put).setValue(FISH, fish), 3);
        return put;
    }

    /** A serving eaten out of it. False if it was empty. */
    public static boolean eat(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof PetBowlBlock) || st.getValue(SERVINGS) <= 0) return false;
        int left = st.getValue(SERVINGS) - 1;
        level.setBlock(pos, st.setValue(SERVINGS, left).setValue(FISH, left > 0 && st.getValue(FISH)), 3);
        level.playSound(null, pos, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6F, 1.1F);
        return true;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean dog = dogFood(stack), cat = catFood(stack);
        if (!dog && !cat) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        int put = fill(level, pos, 1, cat);
        if (put <= 0) {
            player.displayClientMessage(Component.literal(state.getValue(SERVINGS) >= 3 ? "The bowl is full."
                : "There's " + (state.getValue(FISH) ? "fish" : "meat") + " in it already."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5F, 0.8F);
        player.displayClientMessage(Component.literal(words(level.getBlockState(pos))), true);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        String whose = com.jrpetty.mcassistant.entity.Pets.whoseBowl(level, pos);
        player.displayClientMessage(Component.literal(words(state) + (whose.isEmpty() ? "" : " " + whose)), true);
        return InteractionResult.CONSUME;
    }

    /** "The bowl: two servings of fish." */
    static String words(BlockState st) {
        int n = st.getValue(SERVINGS);
        if (n <= 0) return "The bowl is empty.";
        String what = st.getValue(FISH) ? "fish" : "bones and meat";
        return n >= 3 ? "The bowl is full of " + what + "." : "The bowl: " + (n == 1 ? "a serving" : "two servings") + " of " + what + ".";
    }
}
