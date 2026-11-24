package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * [pa38] The enchanting table, worked as a player works it (from level 6). It stands at the table, counts the
 * bookshelves the game counts (a shelf two out, with clear air between it and the table), and is shown the game's own
 * three offers for the thing in hand: the requirements and the clues come from its own enchanting seed exactly as a
 * player's do (EnchantmentMenu's sums, the same seed rules: a new seed after every enchantment). It chooses as its
 * level allows (PaEnchantJudge: the learner goes by the requirement and takes the cheap-looking top offer; with practice
 * it reads the clue, takes the bottom offer for three levels, waits for thirty levels on diamond gear, and from level 7
 * turns a poor set over with a book), pays the slot's levels and lapis, and gets the enchantments the game draws for
 * that offer. Its experience is its own, counted as a player's bar counts it.
 *
 * <p>Asked to "put bookshelves round the table", it sets the bookshelves from its pack in the ring the table reads: a
 * learner just rings it (a shelf behind a torch doesn't count, and it doesn't know fifteen is all that does); from
 * level 4 it keeps the air clear and stops at fifteen.
 */
final class PaEnchantGoal extends Goal {

    private final PlayerAssistantEntity a;
    @Nullable private Job job;
    private int gen, phaseAt, walkTicks, done, rerolled, shelves;
    @Nullable private BlockPos table;
    private boolean shelfMode, said;
    private final List<BlockPos> ring = new ArrayList<>();
    private final List<ItemStack> skip = new ArrayList<>();
    private Random rnd = new Random();

    PaEnchantGoal(PlayerAssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = a.peekJob();
        return j != null && j.type() == Job.Type.ENCHANT && a.getTarget() == null && !a.level().isClientSide;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && a.getTarget() == null && a.taskGen() == gen && a.peekJob() == job;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        job = a.peekJob();
        gen = a.taskGen();
        done = 0;
        rerolled = 0;
        shelves = 0;
        said = false;
        skip.clear();
        ring.clear();
        rnd = new Random(a.getRandom().nextLong());
        phaseAt = a.tickCount;
        walkTicks = 0;
        PaField f = a.mind.field;
        if (!f.seedSet) { f.enchantSeed = a.getRandom().nextInt(); f.seedSet = true; }
        shelfMode = job != null && "shelves".equals(job.arg());
        if (!shelfMode && !PaLevels.has(a.veteranLevel(), "enchant")) {                 // the table from level 6, however the job came
            finish(PaTalk.line(a, "skill_locked", "need", String.valueOf(PaLevels.levelOf("enchant")), "level", String.valueOf(a.veteranLevel())), false);
            return;
        }
        table = findTable();
        if (table == null) { finish(PaLogiTalk.line(a, "field.enchant_no_table"), false); return; }
        if (shelfMode) {
            if (a.countMatching(s -> s.is(Items.BOOKSHELF)) == 0) { finish(PaLogiTalk.line(a, "field.shelves_none"), false); return; }
            planRing();
            if (ring.isEmpty()) { finish(PaLogiTalk.line(a, "field.shelves_full", "n", String.valueOf(power(a.level(), table))), true); return; }
        }
    }

    @Override
    public void stop() {
        job = null;
        a.getNavigation().stop();
    }

    private void finish(@Nullable String line, boolean productive) {
        if (line != null && !line.isBlank()) PaTalk.comment(a, 1, line, true);
        a.noteJobOutcome(productive);
        if (job != null && a.peekJob() == job) a.pollJob();
        job = null;
        a.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || table == null) return;
        if (!a.level().getBlockState(table).is(Blocks.ENCHANTING_TABLE)) { finish(PaLogiTalk.line(a, "field.enchant_no_table"), done > 0); return; }
        a.getLookControl().setLookAt(table.getX() + 0.5, table.getY() + 0.75, table.getZ() + 0.5);
        double d = a.getEyePosition().distanceToSqr(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5);
        double reach = Math.min(a.reach(), 4.0);
        if (d > reach * reach) {
            if (a.getNavigation().isDone()) a.getNavigation().moveTo(table.getX() + 0.5, table.getY(), table.getZ() + 0.5, 1.0);
            if (++walkTicks > 300) finish(PaLogiTalk.line(a, "field.enchant_no_table"), done > 0);
            phaseAt = a.tickCount;
            return;
        }
        a.getNavigation().stop();
        if (shelfMode) { shelfTick(); return; }
        int read = Math.max(20, 70 - a.veteranLevel() * 5);                      // reading the offers: slower when new to it
        if (a.tickCount - phaseAt < read) return;
        phaseAt = a.tickCount;
        if (!enchantOne()) {
            if (done > 0) finish(PaLogiTalk.line(a, "field.enchant_done", "n", String.valueOf(done)), true);
            else if (!said) finish(PaLogiTalk.line(a, "field.enchant_nothing"), false);
            else finish(null, false);
        }
    }

    // ------------------------------------------------------------------ the table's offers, as a player sees them

    /** The game's bookshelf count for a table (each shelf two out with clear air between, 15 at most counts). */
    static int power(Level l, BlockPos table) {
        float p = 0;
        for (BlockPos off : EnchantingTableBlock.BOOKSHELF_OFFSETS)
            if (EnchantingTableBlock.isValidBookShelf(l, table, off)) p += l.getBlockState(table.offset(off)).getEnchantPowerBonus(l, table.offset(off));
        return (int) p;
    }

    /** The bookshelves a learner would count (every one in the ring, blocked or not). */
    static int naiveShelves(Level l, BlockPos table) {
        int n = 0;
        for (BlockPos off : EnchantingTableBlock.BOOKSHELF_OFFSETS) if (l.getBlockState(table.offset(off)).is(Blocks.BOOKSHELF)) n++;
        return n;
    }

    /** The three offers for this item and seed: requirement and clue, the game's own sums (EnchantmentMenu.slotsChanged). */
    PaEnchantJudge.Offer[] offers(ItemStack stack, int seed, int power, int[] costs) {
        RandomSource r = RandomSource.create();
        r.setSeed(seed);
        PaEnchantJudge.Offer[] out = new PaEnchantJudge.Offer[3];
        for (int k = 0; k < 3; k++) {
            costs[k] = EnchantmentHelper.getEnchantmentCost(r, k, power, stack);
            if (costs[k] < k + 1) costs[k] = 0;
            costs[k] = net.neoforged.neoforge.event.EventHooks.onEnchantmentLevelSet(a.level(), table, k, power, stack, costs[k]);
        }
        for (int k = 0; k < 3; k++) {
            if (costs[k] <= 0) { out[k] = new PaEnchantJudge.Offer(0, null, 0); continue; }
            List<EnchantmentInstance> l = list(stack, seed, k, costs[k]);
            if (l.isEmpty()) { out[k] = new PaEnchantJudge.Offer(costs[k], null, 0); continue; }
            EnchantmentInstance clue = l.get(r.nextInt(l.size()));
            out[k] = new PaEnchantJudge.Offer(costs[k], id(clue.enchantment), clue.level);
        }
        return out;
    }

    /** The enchantments an offer brings (EnchantmentMenu.getEnchantmentList): seeded by the seed and the slot. */
    List<EnchantmentInstance> list(ItemStack stack, int seed, int slot, int cost) {
        RandomSource r = RandomSource.create();
        r.setSeed((long) seed + slot);
        Optional<HolderSet.Named<Enchantment>> tag = a.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getTag(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tag.isEmpty()) return List.of();
        List<EnchantmentInstance> l = new ArrayList<>(EnchantmentHelper.selectEnchantment(r, stack, cost, tag.get().stream()));
        if (stack.is(Items.BOOK) && l.size() > 1) l.remove(r.nextInt(l.size()));
        return l;
    }

    static String id(Holder<Enchantment> h) {
        return h.unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
    }

    static String says(List<EnchantmentInstance> l) {
        List<String> parts = new ArrayList<>();
        for (EnchantmentInstance e : l) parts.add(cap(id(e.enchantment).replace('_', ' ')) + " " + roman(e.level));
        return parts.size() <= 1 ? (parts.isEmpty() ? "nothing" : parts.get(0)) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** One item enchanted (or passed over, or a set turned over). False when there's nothing more it will do. */
    private boolean enchantOne() {
        PaField f = a.mind.field;
        int lv = a.veteranLevel();
        int levels = PaEnchantJudge.levelOf(a.getXp());
        int lapis = a.countMatching(s -> s.is(Items.LAPIS_LAZULI));
        if (lapis <= 0) { said = true; PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.enchant_no_lapis"), true); return false; }
        if (levels <= 0) { said = true; PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.enchant_no_xp", "n", String.valueOf(levels)), true); return false; }
        int power = power(a.level(), table);
        if (PaKnack.knowsShelfGap(lv) && power < 15 && done == 0 && !said) {
            said = true;
            int naive = naiveShelves(a.level(), table);
            PaTalk.comment(a, 2, PaLogiTalk.line(a, naive > power ? "field.enchant_blocked" : "field.enchant_shelves", "n", String.valueOf(power), "need", String.valueOf(15 - power)), true);
        }
        Slot item = next();
        if (item == null) return false;
        ItemStack stack = item.get();
        String kind = PaEnchantJudge.kind(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        int[] costs = new int[3];
        PaEnchantJudge.Offer[] offers = offers(stack, f.enchantSeed, power, costs);
        f.offersSeen++;
        // a diamond sword deserves thirty levels: from level 6 it waits rather than spend it on a poor roll
        boolean precious = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().matches("(diamond|netherite)_.*|trident|mace|bow|crossbow");
        if (precious && lv >= 6 && levels < 30 && costs[2] < 30 && power >= 15) {
            skip.add(stack);
            said = true;
            PaTalk.comment(a, 1, PaLogiTalk.line(a, "field.enchant_wait", "item", name(stack), "n", String.valueOf(levels)), true);
            return true;
        }
        if (PaEnchantJudge.poor(lv, kind, offers, levels, lapis) && rerolled < 2 && reroll(levels, lapis)) return true;
        int k = PaEnchantJudge.pick(lv, kind, offers, levels, lapis, rnd);
        if (k < 0) { skip.add(stack); return true; }
        List<EnchantmentInstance> got = list(stack, f.enchantSeed, k, costs[k]);
        if (got.isEmpty()) { skip.add(stack); return true; }
        ItemStack after = stack.getItem().applyEnchantments(stack, got);
        item.set(after);
        pay(k + 1);
        f.enchantSeed = a.getRandom().nextInt();
        f.enchants++;
        done++;
        String what = says(got);
        f.lastPick = (k + 1) + ":" + costs[k] + ":" + what;
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, a.level().random.nextFloat() * 0.1F + 0.9F);
        a.earn("enchant");
        String slot = k == 2 ? "bottom" : k == 1 ? "middle" : "top";
        int ideal = PaEnchantJudge.ideal(kind, offers, levels, lapis);
        PaTalk.comment(a, 1, PaLogiTalk.line(a, k == ideal ? "field.enchant_pick" : "field.enchant_pick_meh", "item", name(after), "what", what,
            "n", String.valueOf(k + 1), "need", String.valueOf(costs[k]), "why", slot), true);
        return true;
    }

    /** The old trick: a book enchanted on the top offer (a level and a lapis) for a fresh set. */
    private boolean reroll(int levels, int lapis) {
        if (levels < 1 || lapis < 1 || a.countMatching(s -> s.is(Items.BOOK)) < 1) return false;
        PaField f = a.mind.field;
        ItemStack book = new ItemStack(Items.BOOK);
        int[] costs = new int[3];
        offers(book, f.enchantSeed, power(a.level(), table), costs);
        if (costs[0] <= 0 || levels < costs[0]) return false;
        List<EnchantmentInstance> got = list(book, f.enchantSeed, 0, costs[0]);
        if (got.isEmpty()) return false;
        a.removeMatching(s -> s.is(Items.BOOK), 1);
        ItemStack eb = book.getItem().applyEnchantments(book, got);
        ItemStack left = a.insertGiven(eb);
        if (!left.isEmpty()) a.spawnAtLocation(left);
        pay(1);
        f.enchantSeed = a.getRandom().nextInt();
        f.rerolls++;
        rerolled++;
        a.swing(InteractionHand.MAIN_HAND);
        a.level().playSound(null, table, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 0.95F);
        PaTalk.comment(a, 2, PaLogiTalk.line(a, "field.enchant_reroll", "what", says(got)), true);
        return true;
    }

    /** Pay a slot: so many of its levels (as a player's bar loses them) and as many lapis. */
    private void pay(int n) {
        int now = a.getXp();
        a.spendXp(now - PaEnchantJudge.afterSpending(now, n));
        a.removeMatching(s -> s.is(Items.LAPIS_LAZULI), n);
    }

    static String name(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' ');
    }

    /** Somewhere an item lives (worn, in hand, in its pack), so it is enchanted in place. */
    private record Slot(PlayerAssistantEntity a, EquipmentSlot eq, int inv) {
        ItemStack get() { return eq != null ? a.getItemBySlot(eq) : a.getInventoryItems().get(inv); }
        void set(ItemStack s) { if (eq != null) a.setItemSlot(eq, s); else a.getInventoryItems().set(inv, s); }
    }

    /** The next thing it was asked to enchant that the table will take (not enchanted already, not passed over). */
    @Nullable
    private Slot next() {
        String want = job == null || job.arg() == null ? "gear" : job.arg().toLowerCase(java.util.Locale.ROOT);
        List<Slot> all = new ArrayList<>();
        for (EquipmentSlot e : EquipmentSlot.values()) all.add(new Slot(a, e, -1));
        for (int i = 0; i < a.getInventoryItems().size(); i++) all.add(new Slot(a, null, i));
        for (Slot sl : all) {
            ItemStack s = sl.get();
            if (s.isEmpty() || !s.isEnchantable() || s.is(Items.BOOK) && !want.equals("book") && !want.equals("books")) continue;
            if (skip.stream().anyMatch(x -> x == s)) continue;
            String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!wanted(want, p)) continue;
            return sl;
        }
        return null;
    }

    static boolean wanted(String want, String p) {
        return switch (want) {
            case "", "gear", "all", "everything", "kit", "stuff", "items", "things" -> !p.equals("book");
            case "tool", "tools" -> p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe");
            case "weapon", "weapons" -> p.endsWith("_sword") || p.equals("bow") || p.equals("crossbow") || p.equals("trident") || p.equals("mace");
            case "armor", "armour" -> p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots");
            case "book", "books" -> p.equals("book");
            case "it" -> true;
            default -> p.contains(want.replace(' ', '_'));
        };
    }

    @Nullable
    private BlockPos findTable() {
        BlockPos feet = a.blockPosition(), best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-16, -4, -16), feet.offset(16, 4, 16))) {
            if (!a.level().getBlockState(p).is(Blocks.ENCHANTING_TABLE)) continue;
            double d = p.distSqr(feet);
            if (d < bd) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    // ------------------------------------------------------------------ bookshelves round the table

    /** Where it will set its shelves: a learner rings the table wherever there's room; from level 4, only where they count, to fifteen. */
    private void planRing() {
        Level l = a.level();
        boolean expert = PaKnack.knowsShelfGap(a.veteranLevel());
        int have = power(l, table);
        List<BlockPos> spots = new ArrayList<>(EnchantingTableBlock.BOOKSHELF_OFFSETS);
        if (!expert) java.util.Collections.shuffle(spots, rnd);
        else spots.sort((x, y) -> Integer.compare(x.getY() * 10 + (Math.abs(x.getX()) == 2 && Math.abs(x.getZ()) == 2 ? 1 : 0),
            y.getY() * 10 + (Math.abs(y.getX()) == 2 && Math.abs(y.getZ()) == 2 ? 1 : 0)));   // the low ring first, corners last
        int books = a.countMatching(s -> s.is(Items.BOOKSHELF));
        for (BlockPos off : spots) {
            if (ring.size() >= books) break;
            if (expert && have + ring.size() >= 15) break;
            BlockPos at = table.offset(off);
            if (!l.getBlockState(at).canBeReplaced() || !l.getFluidState(at).isEmpty() || !a.inZone(at)) continue;
            BlockPos gap = table.offset(off.getX() / 2, off.getY(), off.getZ() / 2);
            if (expert && !l.getBlockState(gap).is(BlockTags.ENCHANTMENT_POWER_TRANSMITTER)) continue;   // it knows a blocked shelf counts for nothing
            if (at.equals(a.blockPosition()) || at.equals(a.blockPosition().above())) continue;
            ring.add(at.immutable());
        }
    }

    private void shelfTick() {
        if (a.tickCount - phaseAt < Math.max(12, PaPace.hand(a))) return;
        phaseAt = a.tickCount;
        while (!ring.isEmpty()) {
            BlockPos at = ring.remove(0);
            if (!a.level().getBlockState(at).canBeReplaced()) continue;
            if (a.removeMatching(s -> s.is(Items.BOOKSHELF), 1) < 1) break;
            a.level().setBlockAndUpdate(at, Blocks.BOOKSHELF.defaultBlockState());
            a.swing(InteractionHand.MAIN_HAND);
            var snd = Blocks.BOOKSHELF.defaultBlockState().getSoundType();
            a.level().playSound(null, at, snd.getPlaceSound(), SoundSource.BLOCKS, (snd.getVolume() + 1.0F) / 2.0F, snd.getPitch() * 0.8F);
            shelves++;
            a.mind.field.shelvesPlaced++;
            return;
        }
        int power = power(a.level(), table), naive = naiveShelves(a.level(), table);
        // a learner counts what it put down; from level 4 it counts what the table reads
        int says = PaKnack.knowsShelfGap(a.veteranLevel()) ? power : Math.min(naive, 32);
        finish(PaLogiTalk.line(a, "field.shelves_done", "n", String.valueOf(shelves), "need", String.valueOf(says)), shelves > 0);
    }
}
