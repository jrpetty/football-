package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.KitchenDrinkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * [dining] A place setting at a table, as a cake is a block: a plate (bare, or with a served dish on it), a cup (a drink),
 * or both, on a white cloth where the table had its carpet (the carpet is kept and goes back when the setting is
 * cleared). The dish shows as what it is (a stew in its bowl, a pie, a roast, fish, bread and cheese, a slice of cake, a
 * salad, sweets: one model each, eaten down in three stages; anything else is drawn as itself on the plate by the
 * renderer). Right-click to eat a bite (the dish's real hunger and saturation, split between its bites, a good cook's
 * a little more filling) or, on the cup, a sip (its good on the last). Folk eat the same way, a bite at a time
 * (entity/Dining). A house's crockery (a café's, a tavern's, a restaurant's) is the house's: a waiter clears it and a pot
 * washer washes it, and nobody else carries it off; a player's own plate and cup are picked up as they were put down.
 */
public class PlaceSettingBlock extends Block implements EntityBlock {

    /** What is on the plate (NONE: no plate at all, just a cup; EMPTY: a bare plate). */
    public enum Dish implements StringRepresentable {
        NONE, EMPTY, STEW, PIE, ROAST, FISH, BREAD, CAKE, SALAD, SWEETS, OTHER;

        @Override
        public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }

        /** A plate is there. */
        public boolean plate() { return this != NONE; }
    }

    /** The cup: none, drunk, half drunk, full. */
    public enum Cup implements StringRepresentable {
        NONE, EMPTY, HALF, FULL;

        @Override
        public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final EnumProperty<Dish> DISH = EnumProperty.create("dish", Dish.class);
    /** How far through the dish: 3 whole, 2 begun, 1 nearly gone, 0 eaten (crumbs). */
    public static final IntegerProperty BITES = IntegerProperty.create("bites", 0, 3);
    public static final EnumProperty<Cup> CUP = EnumProperty.create("cup", Cup.class);
    /** Laid on a cloth (the table's carpet taken up meanwhile). */
    public static final BooleanProperty CLOTH = BooleanProperty.create("cloth");
    /** The house's crockery, not a player's own: never dropped as loot. */
    public static final BooleanProperty HOUSE = BooleanProperty.create("house");

    /** Sips to a cup. */
    public static final int SIPS = 3;
    /** The most bites a dish is cut into, and the hunger a bite is meant to be about. */
    static final int MOST_BITES = 4;
    static final double HUNGER_A_BITE = 2.5;
    /** A good cook's dish: so much more saturation on the last bite, by the cook's hand (none, good, a master's). */
    static final float[] QUALITY_SATURATION = { 0.0F, 1.0F, 2.0F };
    /** A slice of cake: the game's own slice (two hunger, a little saturation). */
    static final FoodProperties CAKE_SLICE = new FoodProperties.Builder().nutrition(2).saturationModifier(0.1F).build();

    // The shapes: a cloth one pixel deep, the plate ten across, a cup alone in the middle or at the plate's corner.
    private static final VoxelShape[] SHAPES = new VoxelShape[8];

    static {
        for (int i = 0; i < 8; i++) {
            boolean cloth = (i & 1) != 0, plate = (i & 2) != 0, cup = (i & 4) != 0;
            double y = cloth ? 1 : 0;
            VoxelShape s = cloth ? Block.box(0, 0, 0, 16, 1, 16) : Shapes.empty();
            if (plate) s = Shapes.or(s, Block.box(3, y, 3, 13, y + 2, 13));
            if (cup) s = Shapes.or(s, plate ? Block.box(11, y, 1, 15, y + 5, 5) : Block.box(6, y, 6, 10, y + 5, 10));
            if (s.isEmpty()) s = Block.box(6, 0, 6, 10, 2, 10);
            SHAPES[i] = s;
        }
    }

    public PlaceSettingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DISH, Dish.EMPTY).setValue(BITES, 0).setValue(CUP, Cup.NONE)
            .setValue(CLOTH, false).setValue(HOUSE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DISH, BITES, CUP, CLOTH, HOUSE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int i = (state.getValue(CLOTH) ? 1 : 0) | (state.getValue(DISH).plate() ? 2 : 0) | (state.getValue(CUP) != Cup.NONE ? 4 : 0);
        return SHAPES[i];
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlaceSettingBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ where it may stand

    /** On a table: anything that holds a thing up in its middle (a fence post's top, a slab, a block). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos under = pos.below();
        return Block.canSupportCenter(level, under, Direction.UP) || level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    /** Can a setting be laid here, taking up the carpet that is here (a table's cloth)? */
    public static boolean onATable(LevelReader level, BlockPos pos) {
        BlockPos under = pos.below();
        return Block.canSupportCenter(level, under, Direction.UP) || level.getBlockState(under).isFaceSturdy(level, under, Direction.UP);
    }

    /** Is this a setting laid on a table's cloth (it stands for the table's carpet: Restore)? */
    public static boolean laidCloth(BlockState s) {
        return s.getBlock() instanceof PlaceSettingBlock && s.getValue(CLOTH);
    }

    /** Is this a table's carpet (a cloth a setting may be laid in place of)? */
    public static boolean isCloth(BlockState s) {
        return s.getBlock() instanceof CarpetBlock;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState other, LevelAccessor level, BlockPos pos, BlockPos otherPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, other, level, pos, otherPos);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** A house's setting is the house's: a player cannot break it (it is eaten from, and cleared by the waiter). */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (state.getValue(HOUSE) && !player.getAbilities().instabuild) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(state.getValue(DISH).plate() ? com.jrpetty.mcassistant.item.DiningItems.PLATE.get()
            : com.jrpetty.mcassistant.item.DiningItems.CUP.get());
    }

    /**
     * Taken away. Cleared by its house: nothing more (the house has the crockery back). Otherwise (broken, or its table
     * gone): a dish or a drink not yet begun is dropped whole (nothing is lost, nothing made twice: the loot table drops
     * a player's own plate and cup), the table's carpet is dropped, and a house's crockery goes back to its house to be
     * washed (Dining.settingLost).
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        if (!state.is(now.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be && !be.cleared) {
            if (!be.food.isEmpty() && be.bites == be.bitesOf) Block.popResource(level, pos, be.food.copy());
            if (!be.drink.isEmpty() && be.sips == be.sipsOf) Block.popResource(level, pos, be.drink.copy());
            if (state.getValue(CLOTH)) Block.popResource(level, pos, new ItemStack(be.carpet.getBlock()));
            if (!state.getValue(HOUSE)) for (ItemStack back : be.takeLeftovers()) Block.popResource(level, pos, back);
            if (state.getValue(HOUSE) && level instanceof ServerLevel server && LOST != null) LOST.accept(new Lost(server, pos, state, be));
        }
        super.onRemove(state, level, pos, now, moved);
    }

    /** A house's setting gone other than by its waiter: what the house is told (entity/Dining sets it). */
    public record Lost(ServerLevel level, BlockPos pos, BlockState state, PlaceSettingBlockEntity setting) {}

    @Nullable public static Consumer<Lost> LOST;

    /** The carpet a cloth puts back when nothing says otherwise (the setting remembers the table's own: its entity). */
    public static BlockState carpet() {
        return Blocks.WHITE_CARPET.defaultBlockState();
    }

    // ------------------------------------------------------------------ eating

    /** What a bite (or a sip) came to: its hunger and saturation, whether it was the last, and of what. */
    public record Bite(int nutrition, float saturation, boolean last, boolean drink, ItemStack of) {}

    @Override
    protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        // A player's own bare plate takes a dish off the hand; its empty cup a drink. A house's is served by its house.
        if (!state.getValue(HOUSE) && level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be) {
            if (state.getValue(DISH) == Dish.EMPTY && be.food.isEmpty() && foodOf(held) != null && !isDrink(held)) {
                if (!level.isClientSide) {
                    be.serveFood(held, bitesFor(held), null, null, "", -1, 0);
                    held.consume(1, player);
                    refresh(level, pos);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (state.getValue(CUP) == Cup.EMPTY && be.drink.isEmpty() && isDrink(held)) {
                if (!level.isClientSide) {
                    be.serveDrink(held, SIPS, null, null, "", -1);
                    held.consume(1, player);                          // its bottle stays by the cup till it is picked up
                    refresh(level, pos);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be)) return InteractionResult.PASS;
        boolean onCup = state.getValue(CUP) != Cup.NONE && (!state.getValue(DISH).plate() || be.food.isEmpty() || cupHit(pos, hit));
        boolean drink = onCup && !be.drink.isEmpty();
        if (!drink && be.food.isEmpty()) {
            // Nothing left: crouch to pick a player's own plate or cup up; a house's waits for the waiter.
            if (!state.getValue(HOUSE) && player.isShiftKeyDown()) {
                if (!level.isClientSide) pickUp(level, pos, state, player);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && state.getValue(HOUSE)) {
                player.displayClientMessage(Component.literal("All gone. The waiter will clear it."), true);
            }
            return InteractionResult.PASS;
        }
        if (be.diner != null && !be.diner.equals(player.getUUID())) {
            if (!level.isClientSide) player.displayClientMessage(Component.literal("That's " + (be.dinerName.isEmpty() ? "somebody's" : be.dinerName + "'s") + "."), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!drink && !player.canEat(false)) {
            if (!level.isClientSide) player.displayClientMessage(Component.literal("You're too full for another bite just now."), true);
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Bite b = drink ? sip(level, pos) : bite(level, pos);
        if (b == null) return InteractionResult.PASS;
        FoodData food = player.getFoodData();
        if (b.nutrition() > 0 || b.saturation() > 0) food.eat(b.nutrition(), b.nutrition() > 0 ? b.saturation() / (2.0F * b.nutrition()) : 0.0F);
        if (b.nutrition() == 0 && b.saturation() > 0) food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + b.saturation()));
        if (b.last()) for (MobEffectInstance e : effectsOf(b.of())) player.addEffect(new MobEffectInstance(e));
        level.gameEvent(player, drink ? GameEvent.DRINK : GameEvent.EAT, pos);
        if (ATE != null) ATE.accept(new Ate(level, pos, player, b));
        return InteractionResult.CONSUME;
    }

    /** Was the cup clicked (the plate's north-east corner, where the cup stands beside it)? */
    private static boolean cupHit(BlockPos pos, BlockHitResult hit) {
        double x = hit.getLocation().x - pos.getX(), z = hit.getLocation().z - pos.getZ();
        return x >= 10.5 / 16 && z <= 5.5 / 16;
    }

    /** Somebody ate or drank at a setting (a player here; folk through Dining itself): what the house is told. */
    public record Ate(Level level, BlockPos pos, LivingEntity who, Bite bite) {}

    @Nullable public static Consumer<Ate> ATE;

    /** A bite off the plate here (a player's, or a folk's at its meal): the plate a stage nearer empty. Null if none. */
    @Nullable
    public static Bite bite(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be) || be.food.isEmpty() || be.bites <= 0) return null;
        ItemStack dish = be.food.copy();
        FoodProperties f = foodOf(dish);
        int n = f == null ? 0 : f.nutrition();
        float s = f == null ? 0.0F : f.saturation();
        int of = Math.max(1, be.bitesOf), i = of - be.bites;           // this is bite i of `of`
        int nut = n * (i + 1) / of - n * i / of;
        float sat = s * (i + 1) / of - s * i / of;
        boolean last = be.biteTaken() == 0;
        if (last) sat += QUALITY_SATURATION[be.quality];
        refresh(level, pos);
        level.playSound(null, pos, SoundEvents.GENERIC_EAT, SoundSource.BLOCKS, 0.6F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        if (level instanceof ServerLevel server) {
            server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, dish), pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5,
                5, 0.12, 0.05, 0.12, 0.02);
        }
        return new Bite(nut, sat, last, false, dish);
    }

    /** A sip from the cup here: its good on the last. Null if it is empty. */
    @Nullable
    public static Bite sip(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be) || be.drink.isEmpty() || be.sips <= 0) return null;
        ItemStack one = be.drink.copy();
        FoodProperties f = foodOf(one);
        int of = Math.max(1, be.sipsOf), i = of - be.sips;
        int n = f == null ? 0 : f.nutrition();
        float s = f == null ? 0.0F : f.saturation();
        int nut = n * (i + 1) / of - n * i / of;
        float sat = s * (i + 1) / of - s * i / of;
        boolean last = be.sipTaken() == 0;
        refresh(level, pos);
        level.playSound(null, pos, SoundEvents.GENERIC_DRINK, SoundSource.BLOCKS, 0.5F, 1.0F + level.getRandom().nextFloat() * 0.2F);
        return new Bite(nut, sat, last, true, one);
    }

    /** The state put right for what the setting now holds (the plate's stage, the cup's). */
    public static void refresh(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof PlaceSettingBlock) || !(level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be)) return;
        BlockState want = st.setValue(BITES, be.stage());
        if (st.getValue(DISH).plate() && !be.food.isEmpty()) want = want.setValue(DISH, dishOf(be.food));
        if (st.getValue(CUP) != Cup.NONE) want = want.setValue(CUP, be.cupStage());
        if (want != st) level.setBlock(pos, want, 3);
    }

    /** A player's own setting picked up: the plate and the cup back to the hand, the table's carpet back on it. */
    private static void pickUp(Level level, BlockPos pos, BlockState state, Player player) {
        BlockState carpet = carpet();
        if (level.getBlockEntity(pos) instanceof PlaceSettingBlockEntity be) {
            be.markCleared();
            carpet = be.carpet;
            for (ItemStack back : be.takeLeftovers()) give(player, back);
        }
        if (state.getValue(DISH).plate()) give(player, new ItemStack(com.jrpetty.mcassistant.item.DiningItems.PLATE.get()));
        if (state.getValue(CUP) != Cup.NONE) give(player, new ItemStack(com.jrpetty.mcassistant.item.DiningItems.CUP.get()));
        level.setBlock(pos, state.getValue(CLOTH) ? carpet : Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, pos, SoundEvents.DECORATED_POT_INSERT, SoundSource.BLOCKS, 0.6F, 1.2F);
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    // ------------------------------------------------------------------ what a dish is

    /** The hunger and saturation of a dish or a drink (the cake by the slice), or null for none. */
    @Nullable
    public static FoodProperties foodOf(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.is(Items.CAKE)) return CAKE_SLICE;
        return s.get(DataComponents.FOOD);
    }

    /** A drink, poured into a cup: the café's bottles, the cellar's mead and cider, tea, honey, milk. */
    public static boolean isDrink(ItemStack s) {
        return s.is(Items.POTION) || s.getItem() instanceof KitchenDrinkItem || s.is(Items.HONEY_BOTTLE) || s.is(Items.MILK_BUCKET);
    }

    /** What a drink is poured out of and leaves behind: a glass bottle, a bucket. */
    public static ItemStack vesselOf(ItemStack s) {
        if (s.is(Items.POTION) || s.getItem() instanceof KitchenDrinkItem || s.is(Items.HONEY_BOTTLE)) return new ItemStack(Items.GLASS_BOTTLE);
        if (s.is(Items.MILK_BUCKET)) return new ItemStack(Items.BUCKET);
        return ItemStack.EMPTY;
    }

    /** What a drink or a dish does for you once it is finished: a potion's or a cellar drink's good, a food's effects. */
    public static List<MobEffectInstance> effectsOf(ItemStack s) {
        java.util.ArrayList<MobEffectInstance> out = new java.util.ArrayList<>();
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc != null) pc.forEachEffect(out::add);
        if (s.getItem() instanceof KitchenDrinkItem k) out.addAll(k.effects());
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f != null) for (FoodProperties.PossibleEffect e : f.effects()) out.add(e.effect());
        return out;
    }

    /** How many bites a dish is cut into: about a bite to two and a half hunger, one to four. */
    public static int bitesFor(ItemStack s) {
        FoodProperties f = foodOf(s);
        int n = f == null ? 2 : f.nutrition();
        return Math.max(1, Math.min(MOST_BITES, (int) Math.ceil(n / HUNGER_A_BITE)));
    }

    /** The model a dish is shown with on the plate (anything without one of its own is drawn as itself: OTHER). */
    public static Dish dishOf(ItemStack s) {
        if (s.isEmpty()) return Dish.EMPTY;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (s.is(Items.CAKE) || id.contains("cake")) return Dish.CAKE;
        if (id.contains("stew") || id.contains("soup") || id.contains("broth") || id.contains("hotpot")) return Dish.STEW;
        if (id.contains("pie") || id.contains("tart")) return Dish.PIE;
        if (s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON)) return Dish.FISH;
        if (id.startsWith("cooked_") || id.contains("mutton") || id.contains("roast")) return Dish.ROAST;
        if (s.is(Items.BREAD) || id.contains("loaf") || id.contains("cheese")) return Dish.BREAD;
        if (s.is(Items.BEETROOT) || s.is(Items.CARROT) || s.is(Items.APPLE) || s.is(Items.GOLDEN_CARROT) || id.contains("salad")) return Dish.SALAD;
        if (s.is(Items.COOKIE) || s.is(Items.SWEET_BERRIES) || s.is(Items.GLOW_BERRIES)) return Dish.SWEETS;
        return Dish.OTHER;
    }
}
