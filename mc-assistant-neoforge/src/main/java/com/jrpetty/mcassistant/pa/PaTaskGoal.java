package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.RecipeBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * [pa33] Carries out the Player AI Assistant's own tasks (PaTask): walk there, then do it the way a player would,
 * one block or one slot at a time, and say how it went. A task it cannot finish ends with the reason, which its
 * brain turns into an honest reply with a fix to click.
 */
public class PaTaskGoal extends Goal {

    private final PlayerAssistantEntity a;
    private PaTask t;
    private final List<BlockPos> work = new ArrayList<>();
    private int idx, walkTicks, cool;

    public PaTaskGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return a.task != null && a.getTarget() == null && !a.plan.paused;
    }

    @Override
    public boolean canContinueToUse() {
        return a.task == t && t != null && a.getTarget() == null && !a.plan.paused;
    }

    @Override
    public void start() {
        t = a.task;
        work.clear();
        idx = 0;
        walkTicks = 0;
        cool = 0;
        if (t != null) t.stage = 0;
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void finish(String result, boolean failed) {
        t.result = result;
        t.failed = failed;
        t.progress = 100;
        a.task = null;
    }

    /** Walk toward a spot; true once within reach. Gives up (false forever) after a long while, which the caller reads. */
    private boolean reach(BlockPos p, double within) {
        if (a.position().distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= within * within) {
            a.getNavigation().stop();
            a.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            walkTicks = 0;
            return true;
        }
        if (walkTicks++ % 20 == 0) a.walkTo(p, 1.0D);
        return false;
    }

    private boolean gaveUpWalking() {
        return walkTicks > 20 * 45;
    }

    @Override
    public void tick() {
        if (t == null) return;
        t.ticks++;
        if (cool > 0) { cool--; return; }
        switch (t.kind) {
            case FUEL -> fuel();
            case PLACE -> place();
            case FIELD -> field();
            case BUY, SELL -> trade();
        }
    }

    // ------------------------------------------------------------------ fuel a furnace

    private static boolean isFuel(ItemStack s) {
        return s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.COAL_BLOCK) || s.is(net.minecraft.tags.ItemTags.PLANKS)
            || s.is(net.minecraft.tags.ItemTags.LOGS);
    }

    private void fuel() {
        if (t.stage == 0) {
            BlockPos from = t.pos != null ? t.pos : a.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(from.offset(-12, -4, -12), from.offset(12, 4, 12))) {
                if (a.level().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity && PaChests.allowed(a, a.level(), p)) work.add(p.immutable());
            }
            work.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(a.blockPosition())));
            if (work.isEmpty()) { finish("I can't see a furnace nearby to fuel.", true); return; }
            if (a.countMatching(PaTaskGoal::isFuel) == 0) { finish("I've no fuel: coal, charcoal, planks or logs.", true); return; }
            t.stage = 1;
        }
        if (idx >= work.size()) { finish("Furnaces fuelled.", false); return; }
        BlockPos f = work.get(idx);
        if (!reach(f, 3.5)) { if (gaveUpWalking()) { idx++; walkTicks = 0; } return; }
        if (a.level().getBlockEntity(f) instanceof AbstractFurnaceBlockEntity fb) {
            ItemStack slot = fb.getItem(1);
            for (int i = 0; i < a.getInventoryItems().size() && (slot.isEmpty() || slot.getCount() < 32); i++) {
                ItemStack s = a.getInventoryItems().get(i);
                if (!isFuel(s)) continue;
                if (slot.isEmpty()) { fb.setItem(1, s.split(Math.min(16, s.getCount()))); slot = fb.getItem(1); }
                else if (ItemStack.isSameItemSameComponents(slot, s)) {
                    int add = Math.min(s.getCount(), Math.min(32, slot.getMaxStackSize()) - slot.getCount());
                    if (add <= 0) break;
                    slot.grow(add);
                    s.shrink(add);
                }
            }
            fb.setChanged();
            a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            a.level().playSound(null, f, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 1.0F);
        }
        idx++;
        t.progress = idx * 100 / Math.max(1, work.size());
        cool = 10;
    }

    // ------------------------------------------------------------------ place a block

    private void place() {
        if (t.item.equals("bed")) { placeBed(); return; }                  // [pa33c-2b] two blocks, and its spawn after
        Item item = itemFor(t.item);
        if (item == Items.AIR || !(item instanceof BlockItem bi)) { finish("I don't know how to place " + t.item + ".", true); return; }
        int slot = -1;
        for (int i = 0; i < a.getInventoryItems().size(); i++) if (a.getInventoryItems().get(i).is(item)) { slot = i; break; }
        if (slot < 0) { finish("I haven't got " + t.item + " to put down.", true); return; }
        if (t.stage == 0) {
            BlockPos at = t.pos != null ? t.pos : a.blockPosition();
            BlockPos spot = null;
            for (int r = 1; r <= 3 && spot == null; r++) {
                for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 2, r))) {
                    if (a.level().getBlockState(p).canBeReplaced() && a.level().getBlockState(p.below()).isSolidRender(a.level(), p.below())
                        && !PaSafety.offLimits(a, p) && !p.equals(at)) { spot = p.immutable(); break; }
                }
            }
            if (spot == null) { finish("There's no room to put the " + t.item + " there.", true); return; }
            work.add(spot);
            t.stage = 1;
        }
        BlockPos spot = work.get(0);
        if (!reach(spot, 4.0)) { if (gaveUpWalking()) finish("I couldn't get to the spot for the " + t.item + ".", true); return; }
        BlockState st = bi.getBlock().defaultBlockState();
        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            Direction face = Direction.getNearest(a.getX() - spot.getX(), 0, a.getZ() - spot.getZ());
            if (face.getAxis().isHorizontal()) st = st.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, face);
        }
        a.level().setBlock(spot, st, 3);
        a.getInventoryItems().get(slot).shrink(1);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        a.placeSound(spot);
        finish("Put the " + t.item + " down.", false);
    }

    /** [pa33c-2b] Put its bed down (foot and head on solid ground, out of anyone's land) and make it its spawn. */
    private void placeBed() {
        int slot = -1;
        for (int i = 0; i < a.getInventoryItems().size(); i++) if (a.getInventoryItems().get(i).is(net.minecraft.tags.ItemTags.BEDS)) { slot = i; break; }
        if (slot < 0) { finish("I haven't got a bed to put down.", true); return; }
        if (!(a.getInventoryItems().get(slot).getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof net.minecraft.world.level.block.BedBlock bed)) {
            finish("That's not a bed I can put down.", true);
            return;
        }
        if (t.stage == 0) {
            BlockPos at = t.pos != null ? t.pos : a.blockPosition();
            BlockPos foot = null;
            Direction facing = Direction.NORTH;
            boolean barred = false;
            search:
            for (int r = 1; r <= 4; r++) {
                for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 1, r))) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos head = p.relative(d);
                        if (!free(p) || !free(head) || p.equals(at) || head.equals(at)) continue;
                        if (PaSafety.offLimits(a, p) || PaSafety.offLimits(a, head)) { barred = true; continue; }
                        foot = p.immutable();
                        facing = d;
                        break search;
                    }
                }
            }
            if (foot == null) {
                finish(barred ? "That's someone's land. I won't put a bed there without them saying so." : "There's no room for a bed there.", true);
                return;
            }
            work.add(foot);
            work.add(foot.relative(facing));
            t.stage = 1;
        }
        BlockPos foot = work.get(0), head = work.get(1);
        if (!reach(foot, 4.0)) { if (gaveUpWalking()) finish("I couldn't get to the spot for my bed.", true); return; }
        if (!free(foot) || !free(head)) { t.stage = 0; work.clear(); return; }    // something moved in: look again
        Direction facing = Direction.getNearest(head.getX() - foot.getX(), 0, head.getZ() - foot.getZ());
        BlockState st = bed.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, facing);
        a.level().setBlock(foot, st.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        a.level().setBlock(head, st.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        a.getInventoryItems().get(slot).shrink(1);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        a.placeSound(foot);
        PaOrders.setBed(a, head);
        finish("Put my bed down at " + head.getX() + " " + head.getY() + " " + head.getZ() + ". That's where I'll come back if I fall.", false);
    }

    private boolean free(BlockPos p) {
        return a.level().getBlockState(p).canBeReplaced() && a.level().getBlockState(p.above()).canBeReplaced()
            && a.level().getBlockState(p.below()).isSolidRender(a.level(), p.below());
    }

    static Item itemFor(String phrase) {
        if (phrase.isEmpty()) return Items.AIR;
        if (phrase.equals("stone") || phrase.equals("cobblestone") || phrase.equals("cobble")) return Items.COBBLESTONE;   // [pa33b] what "stone" means to a player at a market
        if (phrase.equals("logs") || phrase.equals("wood")) return Items.OAK_LOG;
        Item exact = registryItem(phrase);                  // [pa33b] the thing by its own name first ("cobblestone" is not stone)
        if (exact != Items.AIR) return exact;
        String target = CraftPlanner.matchTarget(phrase);
        if (target != null) {
            Item it = RecipeBook.item(target);
            if (it != Items.AIR) return it;
        }
        return switch (phrase) {
            case "crafting table" -> Items.CRAFTING_TABLE;
            case "furnace" -> Items.FURNACE;
            case "chest" -> Items.CHEST;
            case "torches", "torch" -> Items.TORCH;
            default -> registryItem(phrase);
        };
    }

    /** [pa33b] Any item by its own name ("cobblestone", "iron ingots"), singular or plural. */
    private static Item registryItem(String phrase) {
        String id = phrase.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        for (String s : new String[]{id, id.endsWith("es") ? id.substring(0, id.length() - 2) : id, id.endsWith("s") ? id.substring(0, id.length() - 1) : id}) {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse("minecraft:" + s);
            if (rl != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
        }
        return Items.AIR;
    }

    // ------------------------------------------------------------------ lay out a field

    private BlockPos water;

    private void field() {
        ServerLevel level = (ServerLevel) a.level();
        BlockPos at = t.pos != null ? t.pos : a.blockPosition();
        switch (t.stage) {
            case 0 -> {
                water = null;
                for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 1, 4))) {
                    if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER) && level.getBlockState(p.above()).isAir()) {
                        water = p.immutable();
                        break;
                    }
                }
                if (water == null) {
                    int bucket = -1;
                    for (int i = 0; i < a.getInventoryItems().size(); i++) if (a.getInventoryItems().get(i).is(Items.WATER_BUCKET)) bucket = i;
                    BlockPos ground = groundAt(level, at);
                    if (bucket < 0 || ground == null) { finish("I need water nearby for a field, or a bucket of water.", true); return; }
                    if (!reach(ground, 4.0)) { if (gaveUpWalking()) finish("I couldn't get to the spot for the field.", true); return; }
                    level.setBlock(ground, Blocks.WATER.defaultBlockState(), 3);
                    a.getInventoryItems().set(bucket, new ItemStack(Items.BUCKET));
                    water = ground;
                }
                t.stage = 1;
                t.progress = 10;
            }
            case 1 -> {                                    // seeds, from the grass round about if it has none
                if (a.countMatching(s -> s.is(Items.WHEAT_SEEDS)) >= 12 || idx > 60) { t.stage = 2; idx = 0; work.clear(); return; }
                if (work.isEmpty()) {
                    for (BlockPos p : BlockPos.betweenClosed(at.offset(-12, -3, -12), at.offset(12, 3, 12))) {
                        BlockState s = level.getBlockState(p);
                        if ((s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)) && !PaSafety.offLimits(a, p)) work.add(p.immutable());
                    }
                    work.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(a.blockPosition())));
                    if (work.isEmpty()) { t.stage = 2; return; }
                }
                if (idx >= work.size()) { t.stage = 2; idx = 0; work.clear(); return; }
                BlockPos g = work.get(idx);
                if (!reach(g, 4.0)) { if (gaveUpWalking()) { idx++; walkTicks = 0; } return; }
                BlockState s = level.getBlockState(g);
                for (ItemStack d : net.minecraft.world.level.block.Block.getDrops(s, level, g, null)) a.insertItem(d);
                level.destroyBlock(g, false, a);
                // grass gives a seed now and then, as it does a player
                if (a.getRandom().nextInt(3) == 0) a.insertItem(new ItemStack(Items.WHEAT_SEEDS));
                a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                idx++;
                cool = 4;
            }
            case 2 -> {                                    // till round the water
                if (work.isEmpty()) {
                    for (BlockPos p : BlockPos.betweenClosed(water.offset(-4, 0, -4), water.offset(4, 0, 4))) {
                        BlockState s = level.getBlockState(p);
                        if ((s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT)) && level.getBlockState(p.above()).canBeReplaced()
                            && !level.getBlockState(p.above()).liquid() && !PaSafety.offLimits(a, p)) work.add(p.immutable());
                    }
                    work.sort(java.util.Comparator.comparingInt(p -> p.distManhattan(water)));
                    idx = 0;
                    if (work.isEmpty()) { finish("There's no soil round the water to till.", true); return; }
                }
                if (idx >= work.size()) { t.stage = 3; idx = 0; return; }
                BlockPos p = work.get(idx);
                if (!reach(p, 4.5)) { if (gaveUpWalking()) { idx++; walkTicks = 0; } return; }
                if (!level.getBlockState(p.above()).isAir()) level.destroyBlock(p.above(), false, a);
                level.setBlock(p, Blocks.FARMLAND.defaultBlockState(), 3);
                level.playSound(null, p, SoundEvents.HOE_TILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
                a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                idx++;
                t.progress = 20 + 50 * idx / work.size();
                cool = 3;
            }
            case 3 -> {                                    // and plant
                if (idx >= work.size()) {
                    finish("The field's laid out: " + work.size() + " plots tilled and planted.", false);
                    return;
                }
                BlockPos p = work.get(idx);
                int seed = -1;
                for (int i = 0; i < a.getInventoryItems().size(); i++) if (a.getInventoryItems().get(i).is(Items.WHEAT_SEEDS)) { seed = i; break; }
                if (seed < 0) { finish("Tilled " + work.size() + " plots; I ran out of seeds to plant them all.", false); return; }
                if (!reach(p, 4.5)) { if (gaveUpWalking()) { idx++; walkTicks = 0; } return; }
                if (level.getBlockState(p).is(Blocks.FARMLAND) && level.getBlockState(p.above()).isAir()) {
                    level.setBlock(p.above(), Blocks.WHEAT.defaultBlockState(), 3);
                    a.getInventoryItems().get(seed).shrink(1);
                    a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                idx++;
                t.progress = 70 + 30 * idx / work.size();
                cool = 2;
            }
            default -> finish("", false);
        }
    }

    private static BlockPos groundAt(ServerLevel level, BlockPos at) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = at.offset(0, dy, 0);
            if (level.getBlockState(p).is(BlockTags.DIRT) && level.getBlockState(p.above()).canBeReplaced()) return p;
        }
        return null;
    }

    static boolean ripe(BlockState s) {
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    // ------------------------------------------------------------------ buy and sell at a market

    private int done, spent;

    private void trade() {
        // [pa33b] at a market the town sells from its own stores: the town is asking, not this assistant (whose chest
        // rules bar every town's stores while it thinks), so the counters see what the stores hold
        PlayerAssistantEntity was = PaChests.askAs(null);
        try {
            trade0();
        } finally {
            PaChests.askAs(was);
        }
    }

    private void trade0() {
        ServerLevel level = (ServerLevel) a.level();
        // [pa33b] the very town it was sent to (its heart is the task's spot), not whichever claim covers that spot
        // (two records can share a heart, as a town and its founder's claim: the one whose stores hold the thing)
        com.jrpetty.mcassistant.entity.Villages.Village v = null;
        Item want = itemFor(t.item);
        int most = -1;
        if (t.pos != null) for (com.jrpetty.mcassistant.entity.Villages.Village x : com.jrpetty.mcassistant.entity.Villages.every()) {
            if (!x.centre().equals(t.pos) || !x.dim().equals(level.dimension())) continue;
            int n = want == Items.AIR ? 0 : com.jrpetty.mcassistant.entity.Market.stock(level, x.id(), st -> st.is(want));
            if (n > most) { most = n; v = x; }
        }
        if (v == null && t.pos != null) v = com.jrpetty.mcassistant.entity.TownBlocks.townAt(level, t.pos);
        if (v == null) { finish("There's no town's market near enough to trade at.", true); return; }
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner)) { finish("I can only trade for you while you're here.", true); return; }
        if (t.stage == 0) {
            if (!reach(v.centre(), 10.0)) { if (gaveUpWalking()) finish("I couldn't find my way to the market.", true); return; }
            t.stage = 1;
            done = 0;
            spent = 0;
        }
        Item item = itemFor(t.item);
        if (item == Items.AIR) { finish("I don't know what \"" + t.item + "\" is to trade.", true); return; }
        String word = item == Items.COBBLESTONE ? "cobblestone" : item == Items.OAK_LOG && t.item.equals("logs") ? "logs" : t.item;   // what it bought, by name
        PaData.Rec r = a.rec();
        int limit = Math.min(PaConfig.spendMax(), r == null ? 64 : r.spend);
        String said;
        if (t.kind == PaTask.Kind.BUY) {
            int coinsBefore = coins(owner);
            // [pa33b] the price first: a lot that would take it past its limit is not bought at all
            int price = com.jrpetty.mcassistant.entity.Market.quoteFor(level, v, owner, new ItemStack(item));
            if (price > 0 && spent + price > limit) {
                finish(done > 0 ? "Bought " + done + " " + word + " for you; another lot (" + price + " coins) would go past my spending limit of " + limit + "."
                    : "That's " + price + " coins a lot, over my spending limit of " + limit + ". Raise it in my Settings (Behaviour) if you want me to.", done == 0);
                return;
            }
            said = com.jrpetty.mcassistant.entity.Market.buy(level, v, owner, new ItemStack(item));
            int paid = coinsBefore - coins(owner);
            if (paid <= 0 || !said.startsWith("Bought")) { finish(done > 0 ? "Bought " + done + " " + word + " for you, then: " + said : said, done == 0); return; }
            spent += paid;
            done += Math.max(1, countIn(said));
        } else {
            int slot = -1;
            for (int i = 0; i < a.getInventoryItems().size(); i++) if (a.getInventoryItems().get(i).is(item)) { slot = i; break; }
            if (slot < 0) { finish(done > 0 ? "Sold " + done + " " + word + " for you." : "I haven't any " + word + " to sell.", done == 0); return; }
            said = com.jrpetty.mcassistant.entity.Market.sellFor(level, v, owner, a.getInventoryItems().get(slot));
            if (!said.startsWith("Sold")) { finish(done > 0 ? "Sold " + done + " " + word + ", then: " + said : said, done == 0); return; }
            done += Math.max(1, countIn(said));
        }
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        t.progress = t.amount > 0 ? Math.min(100, done * 100 / t.amount) : 50;
        if (t.amount > 0 && done >= t.amount) finish((t.kind == PaTask.Kind.BUY ? "Bought " : "Sold ") + done + " " + word + " for you.", false);
        cool = 10;
    }

    private static int coins(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (com.jrpetty.mcassistant.entity.Market.isCoin(s)) n += s.getCount();
        }
        return n;
    }

    private static int countIn(String said) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(said);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
