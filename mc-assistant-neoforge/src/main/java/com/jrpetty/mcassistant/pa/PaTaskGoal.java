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
        if (t != shotFor) { shotLine = null; shotAt = null; shotAnim = null; }                      // a shot in the air carries on if its task does
        shotFor = t;
        if (t != null && shotLine == null && shotAt == null) t.stage = 0;
    }

    @Override
    public void stop() {
        a.getNavigation().stop();
        if (t != null && t.kind == PaTask.Kind.SHOOT && PaBow.drawing(a)) PaBow.cancel(a);   // [pa33g] no shot loosed for a task it has left
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
            case TAME -> tame();                                 // [pa33d]
            case VTRADE -> villagerTrade();
            case EQUIP -> { PaPack.equipBest(a); finish("Kitted out.", false); }
            case MAP -> map();
            case PILLAR -> pillar();
            case SHOOT -> shoot();
            case BREW -> brew();
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
        // [pa33d] a lever or a button stands on the floor it's put on; a door is two blocks high
        if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE))
            st = st.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        if (bi.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            if (!a.level().getBlockState(spot.above()).canBeReplaced()) { finish("There's no room for a door there.", true); return; }
            a.level().setBlock(spot.above(), st.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
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

    // ------------------------------------------------------------------ [pa33d] taming an animal for its player

    private net.minecraft.world.entity.Entity aimed;

    private static Item tameFood(net.minecraft.world.entity.Entity e) {
        if (e instanceof net.minecraft.world.entity.animal.Wolf) return Items.BONE;
        if (e instanceof net.minecraft.world.entity.animal.Cat || e instanceof net.minecraft.world.entity.animal.Ocelot) return Items.COD;
        if (e instanceof net.minecraft.world.entity.animal.Parrot) return Items.WHEAT_SEEDS;
        return Items.AIR;
    }

    private void tame() {
        if (t.stage == 0) {
            net.minecraft.world.entity.Entity e = t.amount >= 0 ? a.level().getEntity(t.amount) : null;
            if (!(e instanceof net.minecraft.world.entity.TamableAnimal ta) || ta.isTame()) {
                BlockPos at = t.pos != null ? t.pos : a.blockPosition();
                e = null;
                double bd = 12 * 12;
                for (net.minecraft.world.entity.TamableAnimal c : a.level().getEntitiesOfClass(net.minecraft.world.entity.TamableAnimal.class, new net.minecraft.world.phys.AABB(at).inflate(12))) {
                    if (c.isTame() || tameFood(c) == Items.AIR) continue;
                    String word = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(c.getType()).getPath();
                    if (!t.item.equals("any") && !word.equals(t.item)) continue;
                    double d = c.distanceToSqr(at.getX(), at.getY(), at.getZ());
                    if (d < bd) { bd = d; e = c; }
                }
            }
            if (e == null) {
                boolean horse = a.level().getEntitiesOfClass(net.minecraft.world.entity.animal.horse.AbstractHorse.class, a.getBoundingBox().inflate(12), h -> !h.isTamed()).size() > 0;
                finish(horse && (t.item.equals("horse") || t.item.equals("any")) ? "A horse is tamed by riding it till it settles. That's one for you, not me." : "I can't see an animal to tame there (a wolf, a cat or a parrot).", true);
                return;
            }
            aimed = e;
            t.stage = 1;
        }
        if (!(aimed instanceof net.minecraft.world.entity.TamableAnimal ta) || !aimed.isAlive()) { finish("It got away.", true); return; }
        if (ta.isTame()) { finish("Tamed. It's yours now.", false); return; }
        Item food = tameFood(ta);
        Item use = food == Items.COD && a.countMatching(st -> st.is(Items.COD)) == 0 ? Items.SALMON : food;
        if (a.countMatching(st -> st.is(use)) == 0) { finish("I've run out of " + (food == Items.BONE ? "bones" : food == Items.COD ? "raw fish" : "seeds") + ". It isn't tame yet.", true); return; }
        BlockPos at = aimed.blockPosition();
        if (!reach(at, 2.5)) { if (gaveUpWalking()) finish("I couldn't get close enough to it.", true); return; }
        if (cool > 0) return;
        cool = 20;
        a.removeMatching(st -> st.is(use), 1);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (a.getRandom().nextInt(3) == 0 && a.getOwnerPlayer() instanceof net.minecraft.world.entity.player.Player owner) {
            ta.tame(owner);                                            // its player's pet, not its own
            ta.setOrderedToSit(false);
            a.level().broadcastEntityEvent(ta, (byte) 7);              // hearts
            a.earn("tame");
            finish("Tamed. It's yours now.", false);
        } else {
            a.level().broadcastEntityEvent(ta, (byte) 6);              // smoke: not yet
        }
    }

    // ------------------------------------------------------------------ [pa33d] trading with a villager (its player's emeralds)

    private void villagerTrade() {
        String[] bs = t.item.split(":", 2);
        boolean sell = bs[0].equals("sell");
        String word = bs.length > 1 ? bs[1] : "anything";
        if (t.stage == 0) {
            net.minecraft.world.entity.npc.Villager best = null;
            BlockPos at = t.pos != null ? t.pos : a.blockPosition();
            double bd = 10 * 10;
            for (net.minecraft.world.entity.npc.Villager v : a.level().getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, new net.minecraft.world.phys.AABB(at).inflate(10))) {
                if (v.isBaby() || v.isSleeping()) continue;
                double d = v.distanceToSqr(at.getX(), at.getY(), at.getZ());
                if (d < bd) { bd = d; best = v; }
            }
            if (best == null) { finish("I can't see a villager there to trade with.", true); return; }
            aimed = best;
            t.stage = 1;
        }
        if (!(aimed instanceof net.minecraft.world.entity.npc.Villager v) || !v.isAlive()) { finish("The villager wandered off.", true); return; }
        if (!reach(v.blockPosition(), 2.5)) { if (gaveUpWalking()) finish("I couldn't get to the villager.", true); return; }
        Item want = word.equals("anything") ? Items.AIR : itemFor(word);
        net.minecraft.world.item.trading.MerchantOffer offer = null;
        for (net.minecraft.world.item.trading.MerchantOffer o : v.getOffers()) {
            if (o.isOutOfStock()) continue;
            boolean match = sell ? o.getResult().is(Items.EMERALD) && (want == Items.AIR || o.getCostA().is(want))
                : !o.getResult().is(Items.EMERALD) && (want == Items.AIR || o.getResult().is(want));
            if (match) { offer = o; break; }
        }
        if (offer == null) { finish("That villager doesn't " + (sell ? "buy " : "sell ") + (want == Items.AIR ? "anything I can do" : word) + ".", true); return; }
        int deals = 0, spent = 0, got = 0;
        int wantCount = t.amount > 0 ? t.amount : sell ? 64 : offer.getResult().getCount();
        while (!offer.isOutOfStock() && deals < 12 && got < wantCount) {
            ItemStack ca = offer.getCostA(), cb = offer.getCostB();
            if (a.countMatching(st -> st.is(ca.getItem())) < ca.getCount() || !cb.isEmpty() && a.countMatching(st -> st.is(cb.getItem())) < cb.getCount()) break;
            a.removeMatching(st -> st.is(ca.getItem()), ca.getCount());
            if (!cb.isEmpty()) a.removeMatching(st -> st.is(cb.getItem()), cb.getCount());
            ItemStack res = offer.getResult().copy();
            ItemStack left = a.insertGiven(res);
            if (!left.isEmpty()) a.spawnAtLocation(left);
            v.notifyTrade(offer);
            deals++;
            spent += ca.is(Items.EMERALD) ? ca.getCount() : 0;
            got += sell ? ca.getCount() : res.getCount();
        }
        if (deals == 0) {
            ItemStack ca = offer.getCostA();
            finish("I haven't enough to trade: it wants " + ca.getCount() + " " + ca.getHoverName().getString().toLowerCase() + ". Give me some and ask again.", true);
            return;
        }
        a.level().playSound(null, v.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
        a.earn("trade");
        finish(sell ? "Sold " + got + " " + word + " for " + deals * offer.getResult().getCount() + " emeralds."
            : "Bought " + got + " " + offer.getResult().getHoverName().getString().toLowerCase() + " for " + spent + " emerald" + (spent == 1 ? "" : "s") + ".", false);
    }

    // ------------------------------------------------------------------ [pa33d] mapping the area for its player

    private void map() {
        if (t.stage == 0) {
            boolean blank = a.countMatching(st -> st.is(Items.MAP)) > 0;
            boolean makings = a.countMatching(st -> st.is(Items.PAPER)) >= 8 && a.countMatching(st -> st.is(Items.COMPASS)) > 0;
            if (!blank && !makings) { finish("I need an empty map, or eight paper and a compass, to draw one.", true); return; }
            BlockPos c = t.pos != null ? t.pos : a.blockPosition();
            int r = 40;
            work.add(c.offset(r, 0, r)); work.add(c.offset(r, 0, -r)); work.add(c.offset(-r, 0, -r)); work.add(c.offset(-r, 0, r)); work.add(c);
            t.stage = 1;
        }
        if (idx < work.size()) {
            BlockPos p = work.get(idx);
            BlockPos g = a.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
            if (reach(g, 4.0) || gaveUpWalking()) { idx++; walkTicks = 0; t.progress = idx * 100 / (work.size() + 1); }
            return;
        }
        if (a.countMatching(st -> st.is(Items.MAP)) > 0) a.removeMatching(st -> st.is(Items.MAP), 1);
        else { a.removeMatching(st -> st.is(Items.PAPER), 8); a.removeMatching(st -> st.is(Items.COMPASS), 1); }
        BlockPos c = t.pos != null ? t.pos : a.blockPosition();
        ItemStack m = net.minecraft.world.item.MapItem.create(a.level(), c.getX(), c.getZ(), (byte) 1, true, true);
        if (a.level() instanceof ServerLevel sl) net.minecraft.world.item.MapItem.renderBiomePreviewMap(sl, m);
        if (a.getOwnerPlayer() instanceof net.minecraft.world.entity.player.Player owner && owner.distanceToSqr(a) < 8 * 8) {
            if (!owner.getInventory().add(m)) owner.drop(m, false);
        } else {
            ItemStack left = a.insertGiven(m);
            if (!left.isEmpty()) a.spawnAtLocation(left);
        }
        a.earn("explore");
        finish("Mapped the area round here. " + (a.getOwnerPlayer() != null && a.getOwnerPlayer().distanceToSqr(a) < 64 ? "It's in your pack." : "I've got the map for you."), false);
    }

    // ------------------------------------------------------------------ [pa33d] pillaring up, as a player does

    static boolean pillarBlock(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && (s.is(Items.COBBLESTONE) || s.is(Items.DIRT) || s.is(Items.NETHERRACK) || s.is(net.minecraft.tags.ItemTags.PLANKS)
            || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE));
    }

    private int pillarStart;

    private void pillar() {
        if (t.stage == 0) {
            if (a.countMatching(PaTaskGoal::pillarBlock) < t.amount) { finish("I need " + t.amount + " blocks to stand on: cobblestone, dirt or planks.", true); return; }
            pillarStart = a.getBlockY();
            a.getNavigation().stop();
            t.stage = 1;
        }
        int done = t.stage - 1;                                                    // blocks laid under it so far
        t.progress = done * 100 / Math.max(1, t.amount);
        if (done >= t.amount && a.onGround()) {
            a.earn("trick");
            a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.STAY);   // it stays up there till called down
            finish("Up " + t.amount + " blocks. Nice view. Say \"come here\" when you want me down.", false);
            return;
        }
        if (!a.level().getBlockState(a.blockPosition().above(2)).isAir()) { finish("There's a ceiling in the way.", true); return; }
        if (done >= t.amount) return;                                             // the last one laid: land on it
        if (a.onGround() && cool == 0 && jumpFrom == null) { jumpFrom = a.blockPosition(); a.jumpFromGround(); return; }
        if (jumpFrom != null && a.getY() >= jumpFrom.getY() + 1.05) {          // risen clear: the block goes where its feet were
            if (a.level().getBlockState(jumpFrom).canBeReplaced()) {
                for (int i = 0; i < a.getInventoryItems().size(); i++) {
                    ItemStack s = a.getInventoryItems().get(i);
                    if (!pillarBlock(s)) continue;
                    a.level().setBlock(jumpFrom, ((BlockItem) s.getItem()).getBlock().defaultBlockState(), 3);
                    s.shrink(1);
                    a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    a.placeSound(jumpFrom);
                    t.stage++;
                    break;
                }
            }
            jumpFrom = null;
            cool = 4;
        } else if (jumpFrom != null && a.onGround() && a.getBlockY() <= jumpFrom.getY() && t.ticks % 20 == 0) {
            jumpFrom = null;                                                      // came down without rising: again
        }
    }

    @javax.annotation.Nullable private BlockPos jumpFrom;

    // ------------------------------------------------------------------ [pa33d] a trick shot at a block (a ping, a target, a bell)

    /** [pa33e] A shot in the air: when it lands, and what it says then (the word after the hit, not before). */
    private int shotLandsAt;
    @javax.annotation.Nullable private String shotLine;
    @javax.annotation.Nullable private net.minecraft.world.entity.Entity shotAt;
    private int shotDist;
    @javax.annotation.Nullable private PaTask shotFor;

    private void shoot() {
        if (PaBow.drawing(a)) return;                                              // [pa33g] mid-draw: it looses in its own time
        if (shotLine != null || shotAt != null) {                                  // waiting for the arrow to get there
            if (a.tickCount < shotLandsAt) return;
            if (shotAt != null) {                                                  // a mover: did it feel it?
                boolean hit = !shotAt.isAlive() || shotAt instanceof net.minecraft.world.entity.LivingEntity le && le.hurtTime > 0
                    || shotAt instanceof net.minecraft.world.entity.vehicle.AbstractMinecart mc && mc.getHurtTime() > 0;
                net.minecraft.world.entity.Entity was = shotAt;
                shotAt = null;
                if (hit) {
                    a.earn("trick");
                    a.playAnim("cheer");
                    finish(PaTalk.line(a, "trick_moving", "dist", String.valueOf(shotDist), "thing", was.getName().getString().toLowerCase(java.util.Locale.ROOT)), false);
                    return;
                }
                if (t.stage >= Math.max(1, t.amount)) { a.playAnim("shrug"); finish(PaTalk.line(a, "trick_miss", "dist", String.valueOf(shotDist)), false); }
                return;
            }
            String line = shotLine;
            shotLine = null;
            if (shotAnim != null) a.playAnim(shotAnim);                            // the cheer (or the shrug) when it lands, not before
            shotAnim = null;
            finish(line, false);
            return;
        }
        if (!PaShots.hasBow(a) || a.countMatching(st -> st.is(Items.ARROW)) == 0) { finish(PaTalk.line(a, "need_bow"), true); return; }
        if (t.item.startsWith("e:")) { shootMover(); return; }
        BlockPos at = t.pos;
        if (at == null) { finish("Point at what to hit.", true); return; }
        if (!PaShots.canShoot(a, at)) {
            if (!reach(at, Math.max(6, PaLevels.range(a.veteranLevel()) - 4))) { if (gaveUpWalking()) finish("I can't get a clear shot at it.", true); return; }
            if (!PaShots.canShoot(a, at)) { finish("I can't get a clear shot at it from anywhere near.", true); return; }
        }
        a.getNavigation().stop();
        final PaTask task = t;
        boolean began = PaShots.shootAt(a, at, shot -> {                           // [pa33g] drawn, held on the mark, loosed
            if (a.task != task) return;
            task.stage++;
            int dist = (int) Math.round(shot.dist());
            shotLandsAt = a.tickCount + shot.flightTicks() + 4;                    // the word once it's there
            cool = 12;
            if (shot.willHit()) {
                a.earn("trick");
                shotAnim = dist >= 20 ? "bow" : "cheer";
                shotLine = PaTalk.line(a, dist >= 20 ? "trick_long" : "trick_hit", "dist", String.valueOf(dist));
            } else if (task.stage >= Math.max(1, task.amount)) {
                shotAnim = "shrug";
                shotLine = PaTalk.line(a, "trick_miss", "dist", String.valueOf(dist));
            }
        });
        if (!began) finish(PaTalk.line(a, "need_bow"), true);
    }

    @javax.annotation.Nullable private String shotAnim;

    /** [pa33e] At something moving: [pa33g] tracked through the draw and led by what it saw (as well as its level allows). */
    private void shootMover() {
        net.minecraft.world.entity.Entity e = a.level().getEntity(Integer.parseInt(t.item.substring(2)));
        if ((e == null || !e.isAlive()) && t.stage > 0) {                          // gone since it fired: that was the arrow
            a.earn("trick");
            a.playAnim("cheer");
            finish(PaTalk.line(a, "trick_moving", "dist", String.valueOf(shotDist), "thing", "target"), false);
            return;
        }
        if (e == null || !e.isAlive()) { finish("It's gone.", true); return; }
        double d = Math.sqrt(a.distanceToSqr(e));
        if (d > PaLevels.range(a.veteranLevel()) + (PaLevels.has(a.veteranLevel(), "trick") ? 12 : 0) || !a.hasLineOfSight(e)) {
            if (t.ticks > 200) finish("I can't get a clear shot at it.", true);
            else a.getNavigation().moveTo(e, 1.0);
            return;
        }
        a.getNavigation().stop();
        final PaTask task = t;
        boolean began = PaShots.shootAtMover(a, e, shot -> {
            if (a.task != task) return;
            task.stage++;
            shotAt = e;
            shotDist = (int) Math.round(shot.dist());
            shotLandsAt = a.tickCount + shot.flightTicks() + 3;
            cool = 10;
        });
        if (!began) finish(PaTalk.line(a, "need_bow"), true);
    }

    // ------------------------------------------------------------------ [pa33d] brewing basic potions at a stand

    static Item brewIngredient(String what) {
        return switch (what) {
            case "swiftness" -> Items.SUGAR;
            case "fire resistance" -> Items.MAGMA_CREAM;
            case "night vision" -> Items.GOLDEN_CARROT;
            case "strength" -> Items.BLAZE_POWDER;
            case "regeneration" -> Items.GHAST_TEAR;
            case "leaping" -> Items.RABBIT_FOOT;
            case "water breathing" -> Items.PUFFERFISH;
            default -> Items.GLISTERING_MELON_SLICE;
        };
    }

    private static boolean waterBottle(ItemStack s) {
        var pc = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        return s.is(Items.POTION) && pc != null && pc.is(net.minecraft.world.item.alchemy.Potions.WATER);
    }

    private void brew() {
        Item ingredient = brewIngredient(t.item);
        if (t.stage == 0) {
            BlockPos from = t.pos != null ? t.pos : a.blockPosition();
            BlockPos stand = null;
            for (BlockPos q : BlockPos.betweenClosed(from.offset(-12, -4, -12), from.offset(12, 4, 12)))
                if (a.level().getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity) { stand = q.immutable(); break; }
            if (stand == null) { finish("I can't see a brewing stand nearby.", true); return; }
            String missing = a.countMatching(st -> st.is(Items.NETHER_WART)) == 0 ? "nether wart"
                : a.countMatching(st -> st.is(Items.BLAZE_POWDER)) < (ingredient == Items.BLAZE_POWDER ? 2 : 1) ? "blaze powder"
                : a.countMatching(st -> st.is(ingredient)) == 0 ? new ItemStack(ingredient).getHoverName().getString().toLowerCase()
                : a.countMatching(PaTaskGoal::waterBottle) + a.countMatching(st -> st.is(Items.GLASS_BOTTLE)) == 0 ? "glass bottles" : null;
            if (missing != null) { finish("I need " + missing + " to brew that.", true); return; }
            work.add(stand);
            t.stage = 1;
        }
        if (t.stage == 1) {                                                // bottles of water first, filled at the nearest water
            if (a.countMatching(PaTaskGoal::waterBottle) >= Math.min(3, a.countMatching(PaTaskGoal::waterBottle) + a.countMatching(st -> st.is(Items.GLASS_BOTTLE)))) { t.stage = 2; return; }
            BlockPos water = null;
            for (BlockPos q : BlockPos.betweenClosed(a.blockPosition().offset(-10, -3, -10), a.blockPosition().offset(10, 3, 10)))
                if (a.level().getFluidState(q).isSource() && a.level().getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) { water = q.immutable(); break; }
            if (water == null) { t.stage = a.countMatching(PaTaskGoal::waterBottle) > 0 ? 2 : 9; if (t.stage == 9) finish("I need water near by to fill the bottles.", true); return; }
            if (!reach(water, 3.0)) { if (gaveUpWalking()) finish("I couldn't get to the water.", true); return; }
            while (a.countMatching(PaTaskGoal::waterBottle) < 3 && a.removeMatching(st -> st.is(Items.GLASS_BOTTLE), 1) == 1) {
                ItemStack wb = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER);
                ItemStack left = a.insertItem(wb);
                if (!left.isEmpty()) a.spawnAtLocation(left);
            }
            a.level().playSound(null, water, SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
            t.stage = 2;
            return;
        }
        BlockPos stand = work.get(0);
        if (!(a.level().getBlockEntity(stand) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity bs)) { finish("The brewing stand's gone.", true); return; }
        if (!reach(stand, 3.0)) { if (gaveUpWalking()) finish("I couldn't get to the brewing stand.", true); return; }
        if (t.stage == 2) {                                                // bottles in, fuel in, wart on top
            for (int i = 0; i < 3; i++) if (bs.getItem(i).isEmpty()) {
                for (int k = 0; k < a.getInventoryItems().size(); k++) if (waterBottle(a.getInventoryItems().get(k))) { bs.setItem(i, a.getInventoryItems().get(k).split(1)); break; }
            }
            if (bs.getItem(4).isEmpty()) { a.removeMatching(st -> st.is(Items.BLAZE_POWDER), 1); bs.setItem(4, new ItemStack(Items.BLAZE_POWDER)); }
            a.removeMatching(st -> st.is(Items.NETHER_WART), 1);
            bs.setItem(3, new ItemStack(Items.NETHER_WART));
            bs.setChanged();
            a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            t.stage = 3;
            t.progress = 20;
            return;
        }
        if (t.stage == 3 || t.stage == 5) {                                // brewing: wait till the top slot is used up
            if (!bs.getItem(3).isEmpty()) { if (t.ticks % 100 == 0) t.progress = Math.min(90, t.progress + 5); if (t.ticks > 20 * 120) finish("The stand isn't brewing. Has it fuel.", true); return; }
            t.stage++;
            return;
        }
        if (t.stage == 4) {                                                // awkward potions: now the ingredient
            a.removeMatching(st -> st.is(ingredient), 1);
            bs.setItem(3, new ItemStack(ingredient));
            bs.setChanged();
            a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            t.stage = 5;
            t.progress = 55;
            return;
        }
        int got = 0;                                                       // stage 6: bottles out, into its pack
        for (int i = 0; i < 3; i++) {
            ItemStack p = bs.getItem(i);
            if (p.isEmpty()) continue;
            ItemStack left = a.insertItem(p.copy());
            if (!left.isEmpty()) a.spawnAtLocation(left);
            bs.setItem(i, ItemStack.EMPTY);
            got++;
        }
        a.earn("brew");
        finish("Brewed " + got + " potion" + (got == 1 ? "" : "s") + " of " + t.item + ". They're in my pack.", false);
    }
}
