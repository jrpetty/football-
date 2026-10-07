package com.jrpetty.mcassistant.item;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.DraughtsBoardBlock;
import com.jrpetty.mcassistant.block.DraughtsBoardBlockEntity;
import com.jrpetty.mcassistant.block.PaperLanternBlock;
import com.jrpetty.mcassistant.block.QuiltBlock;
import com.jrpetty.mcassistant.entity.FootballEntity;
import com.jrpetty.mcassistant.entity.KiteEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [leisure] Home, play and the town's evenings: seven things the town makes for itself and uses, registered here
 * with their own registers, out of the way of the mod's own, and joined to it with one line (McAssistantMod).
 * <ul>
 * <li><b>The patchwork quilt</b> (QuiltBlock, QuiltItem): six wool of three colours or more, the tailor's use for odd
 *     wool. Laid over the foot of a bed; a folk who sleeps under one wakes the better for it (entity/Quilts).</li>
 * <li><b>The lute</b> (LuteItem): three planks, two sticks and three string. The buskers' own instrument, and the
 *     tavern's band's (entity/Lutes); a player strums a tune on it.</li>
 * <li><b>The draughts board</b> (DraughtsBoardBlock): planks, black dye and white. Set on the tavern's tables and in
 *     the park; the folk play real games on it of an evening (entity/Draughts).</li>
 * <li><b>The kite</b> (KiteItem, KiteEntity): three paper, two sticks, a string and a dye, in the dye's colour. The
 *     children fly them on dry afternoons (entity/Kites).</li>
 * <li><b>The leather football</b> (FootballItem, FootballEntity): four leather round a wool. A real ball that rolls and
 *     bounces; the children's kickabouts and the league's matches are played with it (entity/Kickabout, Football).</li>
 * <li><b>The paper lanterns</b> (PaperLanternBlock), in the sixteen colours of the dyes: paper, a torch and a dye make
 *     two. Strung across the square on a festival night (entity/Lanterns).</li>
 * <li><b>The slate and chalk</b> (SlateItem): smooth stone, a stick and bone meal; the chalk wears. The schoolchildren's,
 *     and they learn the quicker for it (entity/Slates).</li>
 * </ul>
 * Who makes each, and when, is entity/Pastimes.
 */
public final class LeisureItems {

    private LeisureItems() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(McAssistantMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(McAssistantMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, McAssistantMod.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, McAssistantMod.MODID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, McAssistantMod.MODID);

    // ------------------------------------------------------------------ the quilt

    public static final DeferredBlock<QuiltBlock> QUILT = BLOCKS.registerBlock("patchwork_quilt", QuiltBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.2F).sound(SoundType.WOOL).noOcclusion().noCollission()
            .ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<QuiltItem> QUILT_ITEM = ITEMS.registerItem("patchwork_quilt", p -> new QuiltItem(QUILT.get(), p),
        new Item.Properties().stacksTo(16));

    // ------------------------------------------------------------------ the lute

    public static final DeferredItem<LuteItem> LUTE = ITEMS.registerItem("lute", LuteItem::new, new Item.Properties().stacksTo(1));

    // ------------------------------------------------------------------ the draughts board

    public static final DeferredBlock<DraughtsBoardBlock> DRAUGHTS_BOARD = BLOCKS.registerBlock("draughts_board", DraughtsBoardBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.6F).sound(SoundType.WOOD).noOcclusion()
            .pushReaction(PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> DRAUGHTS_BOARD_ITEM = ITEMS.registerSimpleBlockItem(DRAUGHTS_BOARD);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DraughtsBoardBlockEntity>> DRAUGHTS_BOARD_BE =
        BLOCK_ENTITIES.register("draughts_board", () -> BlockEntityType.Builder.of(DraughtsBoardBlockEntity::new, DRAUGHTS_BOARD.get()).build(null));

    // ------------------------------------------------------------------ the kite

    public static final DeferredItem<KiteItem> KITE = ITEMS.registerItem("kite", KiteItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<EntityType<?>, EntityType<KiteEntity>> KITE_ENTITY = ENTITIES.register("kite",
        () -> EntityType.Builder.<KiteEntity>of(KiteEntity::new, MobCategory.MISC).sized(0.8F, 0.8F).clientTrackingRange(10)
            .updateInterval(2).noSummon().build("kite"));

    // ------------------------------------------------------------------ the football

    public static final DeferredItem<FootballItem> LEATHER_FOOTBALL = ITEMS.registerItem("leather_football", FootballItem::new,
        new Item.Properties().stacksTo(16));
    public static final DeferredHolder<EntityType<?>, EntityType<FootballEntity>> FOOTBALL_ENTITY = ENTITIES.register("football",
        () -> EntityType.Builder.<FootballEntity>of(FootballEntity::new, MobCategory.MISC).sized(0.5F, 0.5F).clientTrackingRange(8)
            .updateInterval(1).build("football"));

    // ------------------------------------------------------------------ the paper lanterns, one to a dye

    public static final Map<DyeColor, DeferredBlock<PaperLanternBlock>> LANTERNS;
    public static final Map<DyeColor, DeferredItem<BlockItem>> LANTERN_ITEMS;

    static {
        Map<DyeColor, DeferredBlock<PaperLanternBlock>> blocks = new EnumMap<>(DyeColor.class);
        Map<DyeColor, DeferredItem<BlockItem>> items = new EnumMap<>(DyeColor.class);
        for (DyeColor c : DyeColor.values()) {
            DeferredBlock<PaperLanternBlock> b = BLOCKS.registerBlock(c.getName() + "_paper_lantern", p -> new PaperLanternBlock(p, c),
                BlockBehaviour.Properties.of().mapColor(c.getMapColor()).strength(0.3F).sound(SoundType.WOOL).lightLevel(s -> 12)
                    .noOcclusion().pushReaction(PushReaction.DESTROY).ignitedByLava());
            blocks.put(c, b);
            items.put(c, ITEMS.registerSimpleBlockItem(b));
        }
        LANTERNS = Collections.unmodifiableMap(blocks);
        LANTERN_ITEMS = Collections.unmodifiableMap(items);
    }

    // ------------------------------------------------------------------ the slate and chalk

    public static final DeferredItem<SlateItem> SLATE = ITEMS.registerItem("slate_and_chalk", SlateItem::new,
        new Item.Properties().durability(SlateItem.CHALK));

    // ------------------------------------------------------------------ the recipes that are more than a shape

    /** A shaped recipe whose result takes the colour of the dye in it (the kite). */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<DyedShapedRecipe>> DYED_SHAPED =
        SERIALIZERS.register("dyed_shaped", DyedShapedRecipe.Serializer::new);
    /** Six wool of three colours or more (the quilt). */
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<QuiltRecipe>> PATCHWORK =
        SERIALIZERS.register("patchwork_quilt", () -> new SimpleCraftingRecipeSerializer<>(QuiltRecipe::new));

    // ------------------------------------------------------------------ joined to the mod

    /** Joined to the mod's bus (McAssistantMod): the things, the board's keeping, the ball and the kite, their recipes, the tabs. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        SERIALIZERS.register(modBus);
        modBus.addListener(LeisureItems::onTabs);
        // (The town's side of them, who makes them and who uses them and when, is entity/Pastimes: an event subscriber of its own.)
    }

    /** Every paper lantern, red first (the festival's own), then the rest in the dyes' order. */
    public static List<Item> lanterns() {
        List<Item> out = new ArrayList<>();
        for (DeferredItem<BlockItem> i : LANTERN_ITEMS.values()) out.add(i.get());
        return out;
    }

    /** Is this a paper lantern of any colour? */
    public static boolean isLantern(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem b && b.getBlock() instanceof PaperLanternBlock;
    }

    /** The paper lantern of this colour (its item). */
    public static Item lantern(DyeColor c) {
        return LANTERN_ITEMS.get(c).get();
    }

    /** The colour of a paper lantern, or null for anything else. */
    @Nullable
    public static DyeColor lanternColour(ItemStack s) {
        return s.getItem() instanceof BlockItem b && b.getBlock() instanceof PaperLanternBlock l ? l.colour() : null;
    }

    /** Every one of the seven, a lantern of each colour among them: what the town's books and the tests go through. */
    public static List<Item> all() {
        List<Item> out = new ArrayList<>(List.of(QUILT_ITEM.get(), LUTE.get(), DRAUGHTS_BOARD_ITEM.get(), KITE.get(), LEATHER_FOOTBALL.get(),
            SLATE.get()));
        out.addAll(lanterns());
        return out;
    }

    private static void onTabs(BuildCreativeModeTabContentsEvent event) {
        if (McAssistantMod.TAB.getKey().equals(event.getTabKey())) {
            event.accept(QUILT_ITEM);
            event.accept(LUTE);
            event.accept(DRAUGHTS_BOARD_ITEM);
            event.accept(KITE);
            event.accept(LEATHER_FOOTBALL);
            event.accept(SLATE);
            for (DeferredItem<BlockItem> l : LANTERN_ITEMS.values()) event.accept(l);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(LUTE);
            event.accept(KITE);
            event.accept(LEATHER_FOOTBALL);
            event.accept(SLATE);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(QUILT_ITEM);
            event.accept(DRAUGHTS_BOARD_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.COLORED_BLOCKS) {
            for (DeferredItem<BlockItem> l : LANTERN_ITEMS.values()) event.accept(l);
        }
    }
}
