package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.CraftGoal;
import com.jrpetty.mcassistant.entity.goal.DepositGoal;
import com.jrpetty.mcassistant.entity.goal.FarmGoal;
import com.jrpetty.mcassistant.entity.goal.FollowOwnerGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.entity.goal.RetreatGoal;
import com.jrpetty.mcassistant.entity.goal.WithdrawGoal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The companion. Player-parity on purpose: it has normal mob health, walks
 * everywhere, eats real food to heal, uses the right tool for the job (and
 * wears tools out like a player), and carries loot in a real 27-slot
 * inventory that it deposits into real chests.
 *
 * Modes are standing orders: FOLLOW trails the owner, GUARD holds but fights,
 * STAY parks. Work (gather/deposit/craft/withdraw/farm/build) runs through a
 * sequential job queue. It can hold standing supply orders ("keep the chest
 * stocked with 64 logs"), and with autonomy on it picks role-based work by
 * itself when idle — the seed of the self-running town.
 */
public class AssistantEntity extends PathfinderMob {

    public enum Mode { STAY, FOLLOW, GUARD }

    public enum Role {
        NONE, MINER, LUMBERJACK, FARMER, BUILDER;

        @Nullable
        public static Role fromWord(String w) {
            return switch (w) {
                case "miner", "mining" -> MINER;
                case "lumberjack", "logger", "woodcutter" -> LUMBERJACK;
                case "farmer", "farming" -> FARMER;
                case "builder", "building" -> BUILDER;
                default -> null;
            };
        }
    }

    /** A supply contract: keep ~amount of kind in the nearest chest, forever. */
    public record StandingOrder(GatherGoal.Kind kind, int amount) {}

    public static final int INVENTORY_SIZE = 27;
    public static final int MAX_PER_OWNER = 10;

    // Player-parity reach: same as a survival player's default
    // block_interaction_range (4.5) and entity_interaction_range (3.0).
    public static final double BLOCK_REACH = 4.5;
    public static final double ENTITY_REACH = 3.0;

    // Owner UUID -> (lowercase name -> live assistant). One owner can run a
    // whole crew; commands route by name or to the nearest one.
    private static final Map<UUID, ConcurrentHashMap<String, AssistantEntity>> BY_OWNER = new ConcurrentHashMap<>();

    @Nullable
    public static AssistantEntity byOwner(UUID ownerId) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return null;
        for (AssistantEntity a : m.values()) {
            if (a.isAlive()) return a;
        }
        return null;
    }

    @Nullable
    public static AssistantEntity byName(UUID ownerId, String name) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return null;
        AssistantEntity a = m.get(name.toLowerCase());
        return (a != null && a.isAlive()) ? a : null;
    }

    public static List<AssistantEntity> allFor(UUID ownerId) {
        Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
        if (m == null) return List.of();
        List<AssistantEntity> out = new ArrayList<>();
        for (AssistantEntity a : m.values()) {
            if (a.isAlive()) out.add(a);
        }
        return out;
    }

    public static List<String> namesFor(UUID ownerId) {
        List<String> out = new ArrayList<>();
        for (AssistantEntity a : allFor(ownerId)) out.add(a.getAssistantName());
        return out;
    }

    @Nullable private UUID ownerId;
    private Mode mode = Mode.FOLLOW;
    private Role role = Role.NONE;
    private String assistantName = "assistant";
    private boolean autonomous;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // The work queue — jobs run in order (finish one, start the next).
    private final java.util.ArrayDeque<Job> jobs = new java.util.ArrayDeque<>();
    private int taskGen;

    // Standing supply orders, checked when idle.
    private final List<StandingOrder> standingOrders = new ArrayList<>();

    // Which chest held what, learned from every chest it touches.
    private final Map<Long, Set<String>> chestMemory = new ConcurrentHashMap<>();

    // Health / survival state.
    private int lastDamageTick = -1000;
    private int lastShownHealth = -1;
    private int eatCooldown;
    private boolean retreating;
    private int idleBackoffUntil;

    @Nullable private BlockPos homePos;

    public AssistantEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false); // we manage pickups into our own inventory
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.32D)
            .add(Attributes.ATTACK_DAMAGE, 4.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new RetreatGoal(this));
        this.goalSelector.addGoal(2, new GatherGoal(this));
        this.goalSelector.addGoal(2, new DepositGoal(this));
        this.goalSelector.addGoal(2, new CraftGoal(this));
        this.goalSelector.addGoal(2, new WithdrawGoal(this));
        this.goalSelector.addGoal(2, new FarmGoal(this));
        this.goalSelector.addGoal(2, new BuildGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this, Monster.class, 10, true, false, this::shouldAutoAttack));
    }

    private boolean shouldAutoAttack(LivingEntity target) {
        if (retreating) return false;
        if (target instanceof Creeper) return false;
        if (this.mode == Mode.STAY) return false;
        double toMe = target.distanceToSqr(this);
        if (toMe <= 8.0 * 8.0) return true;
        if (this.mode == Mode.GUARD) {
            Player owner = this.getOwnerPlayer();
            return owner != null && target.distanceToSqr(owner) <= 12.0 * 12.0;
        }
        return false;
    }

    // ------------------------------- ownership -------------------------------

    public void setOwner(@Nullable Player player) {
        this.ownerId = player == null ? null : player.getUUID();
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isOwner(Player player) {
        return ownerId != null && ownerId.equals(player.getUUID());
    }

    @Nullable
    public Player getOwnerPlayer() {
        if (ownerId == null) return null;
        return this.level().getPlayerByUUID(ownerId);
    }

    // ------------------------------ name & role ------------------------------

    public String getAssistantName() {
        return assistantName;
    }

    public String displayNameCap() {
        return assistantName.isEmpty() ? "Assistant"
            : Character.toUpperCase(assistantName.charAt(0)) + assistantName.substring(1);
    }

    public void rename(String newName) {
        String clean = newName.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (clean.isEmpty()) return;
        if (ownerId != null) {
            Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
            if (m != null) m.remove(assistantName.toLowerCase(), this);
        }
        this.assistantName = clean;
        this.lastShownHealth = -1; // refresh the nametag
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isAutonomous() {
        return autonomous;
    }

    public void setAutonomous(boolean autonomous) {
        this.autonomous = autonomous;
        this.idleBackoffUntil = 0;
    }

    /** Say something to the owner. */
    public void say(String message) {
        Player owner = getOwnerPlayer();
        if (owner instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("<" + displayNameCap() + "> " + message));
        }
    }

    // --------------------------------- modes ---------------------------------

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.STAY) {
            this.getNavigation().stop();
            this.setTarget(null);
        }
    }

    // ------------------------------- inventory -------------------------------

    public NonNullList<ItemStack> getInventoryItems() {
        return inventory;
    }

    /** Insert a stack, merging into existing piles first. Returns the leftover. */
    public ItemStack insertItem(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inventory.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int room = slot.getMaxStackSize() - slot.getCount();
                if (room > 0) {
                    int moved = Math.min(room, remaining.getCount());
                    slot.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        for (int i = 0; i < inventory.size() && !remaining.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, remaining);
                remaining = ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    public int countItems() {
        int n = 0;
        for (ItemStack s : inventory) n += s.getCount();
        return n;
    }

    /** Count backpack items matching a predicate. */
    public int countMatching(java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : inventory) {
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** Remove up to n matching items from the backpack. Returns removed count. */
    public int removeMatching(java.util.function.Predicate<ItemStack> what, int n) {
        int removed = 0;
        for (int i = 0; i < inventory.size() && removed < n; i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int take = Math.min(n - removed, s.getCount());
            s.shrink(take);
            removed += take;
            if (s.isEmpty()) inventory.set(i, ItemStack.EMPTY);
        }
        return removed;
    }

    // --------------------------- tool intelligence ---------------------------

    /** Swap the best tool for this block into the main hand (player rules:
     *  axe for logs, pickaxe for stone — whatever digs fastest wins). */
    public void equipBestTool(BlockState state) {
        float bestSpeed = this.getMainHandItem().getDestroySpeed(state);
        int bestIdx = -1;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed + 0.01F) {
                bestSpeed = speed;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            ItemStack old = this.getMainHandItem();
            this.setItemSlot(EquipmentSlot.MAINHAND, inventory.get(bestIdx));
            inventory.set(bestIdx, old);
        }
    }

    /** Ticks of work to break this block with the current tool (player-ish pacing). */
    public int workTicksFor(BlockState state) {
        float speed = Math.max(1.0F, this.getMainHandItem().getDestroySpeed(state));
        return Math.max(5, Math.round(48.0F / speed));
    }

    /** Wear the held tool by one use; announces when it breaks. */
    public void damageHeldTool() {
        ItemStack tool = this.getMainHandItem();
        if (tool.isEmpty() || !tool.isDamageableItem()) return;
        boolean nearlyDone = tool.getDamageValue() >= tool.getMaxDamage() - 2;
        tool.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
        if (nearlyDone && this.getMainHandItem().isEmpty()) {
            say("My tool just broke — I could craft a new one if I have materials (\"craft a stone pickaxe\").");
        }
    }

    // ------------------------------ food & health ----------------------------

    private int findFoodSlot() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && s.get(DataComponents.FOOD) != null) return i;
        }
        return -1;
    }

    public int countFood() {
        return countMatching(s -> s.get(DataComponents.FOOD) != null);
    }

    public int lastDamageTick() {
        return lastDamageTick;
    }

    public boolean isRetreating() {
        return retreating;
    }

    public void setRetreating(boolean retreating) {
        this.retreating = retreating;
    }

    // -------------------------------- job queue -------------------------------

    public void enqueue(Job job) {
        jobs.addLast(job);
    }

    @Nullable
    public Job peekJob() {
        return jobs.peekFirst();
    }

    @Nullable
    public Job pollJob() {
        return jobs.pollFirst();
    }

    public int jobCount() {
        return jobs.size();
    }

    public List<String> jobLabels() {
        List<String> out = new ArrayList<>();
        for (Job j : jobs) out.add(j.label());
        return out;
    }

    public void requestGather(GatherGoal.Kind kind, int amount) {
        enqueue(Job.gather(kind, amount));
    }

    public void requestDeposit() {
        enqueue(Job.deposit());
    }

    public void clearQueue() {
        this.jobs.clear();
        this.taskGen++;
        this.getNavigation().stop();
    }

    public void requestStop() {
        int had = jobs.size();
        clearQueue();
        this.setTarget(null);
        this.setMode(Mode.STAY);
        say(had > 0 ? "Stopping — cleared " + had + " queued job" + (had == 1 ? "" : "s") + "." : "Stopping.");
    }

    public int taskGen() {
        return taskGen;
    }

    /** Goals report how each job went so idle initiative can back off when
     *  the area is tapped out instead of spamming doomed jobs. */
    public void noteJobOutcome(boolean productive) {
        if (!productive) {
            idleBackoffUntil = tickCount + 2400; // ~2 min cool-off
        }
    }

    // ---------------------------- standing orders ----------------------------

    public List<StandingOrder> standingOrders() {
        return standingOrders;
    }

    public void addStandingOrder(GatherGoal.Kind kind, int amount) {
        standingOrders.removeIf(o -> o.kind() == kind);
        standingOrders.add(new StandingOrder(kind, Math.max(1, Math.min(Job.MAX_AMOUNT, amount))));
    }

    public int clearStandingOrders() {
        int n = standingOrders.size();
        standingOrders.clear();
        return n;
    }

    // ----------------------------- storage memory ----------------------------

    /** Learn what a chest holds (called whenever we touch one). */
    public void rememberChest(BlockPos pos, Container container) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
            }
        }
        chestMemory.put(pos.asLong(), ids);
    }

    /** Nearest container that (per memory, then live scan) holds a match. */
    @Nullable
    public BlockPos findChestWith(java.util.function.Predicate<ItemStack> what, int radius) {
        // Remembered chests first — verify they still match.
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (pos.distSqr(blockPosition()) > (double) radius * radius) continue;
            if (containerHas(pos, what)) {
                double d = pos.distSqr(blockPosition());
                if (d < bestDist) { bestDist = d; best = pos; }
            }
        }
        if (best != null) return best;
        // Fall back to a live scan.
        BlockPos feet = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -4, -radius), feet.offset(radius, 4, radius))) {
            if (containerHas(pos, what)) {
                double d = pos.distSqr(feet);
                if (d < bestDist) { bestDist = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    private boolean containerHas(BlockPos pos, java.util.function.Predicate<ItemStack> what) {
        BlockEntity be = level().getBlockEntity(pos);
        if (!(be instanceof Container c)) return false;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) return true;
        }
        return false;
    }

    /** Nearest container of any kind. */
    @Nullable
    public BlockPos findAnyChest(int radius) {
        BlockPos feet = blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -4, -radius), feet.offset(radius, 4, radius))) {
            if (level().getBlockEntity(pos) instanceof Container) {
                double d = pos.distSqr(feet);
                if (d < bestDist) { bestDist = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        if (homePos != null) tag.putLong("Home", homePos.asLong());
        tag.putString("Mode", mode.name());
        tag.putString("Role", role.name());
        tag.putString("Name", assistantName);
        tag.putBoolean("Auto", autonomous);
        ListTag orders = new ListTag();
        for (StandingOrder o : standingOrders) {
            CompoundTag ot = new CompoundTag();
            ot.putString("Kind", o.kind().name());
            ot.putInt("Amount", o.amount());
            orders.add(ot);
        }
        tag.put("Standing", orders);
        ContainerHelper.saveAllItems(tag, inventory, this.registryAccess());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner");
        if (tag.contains("Home")) homePos = BlockPos.of(tag.getLong("Home"));
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.FOLLOW;
        }
        try {
            role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
            role = Role.NONE;
        }
        if (tag.contains("Name")) assistantName = tag.getString("Name");
        if (assistantName.isEmpty()) assistantName = "assistant";
        autonomous = tag.getBoolean("Auto");
        standingOrders.clear();
        for (Tag t : tag.getList("Standing", Tag.TAG_COMPOUND)) {
            CompoundTag ot = (CompoundTag) t;
            try {
                standingOrders.add(new StandingOrder(
                    GatherGoal.Kind.valueOf(ot.getString("Kind")), ot.getInt("Amount")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ContainerHelper.loadAllItems(tag, inventory, this.registryAccess());
    }

    // -------------------------------- behavior --------------------------------

    /** Right-click by the owner opens the management GUI. */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(net.minecraft.world.item.Items.LEAD) || held.is(net.minecraft.world.item.Items.NAME_TAG)) {
            return super.mobInteract(player, hand);
        }
        if (!isOwner(player)) {
            if (!this.level().isClientSide) say("You're not my owner.");
            return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (!this.level().isClientSide && player instanceof ServerPlayer sp) {
            sp.openMenu(
                new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.jrpetty.mcassistant.menu.AssistantMenu(id, inv, this),
                    Component.literal(displayNameCap())),
                buf -> buf.writeVarInt(this.getId()));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;

        // Registry upkeep (owner -> name -> entity).
        if (ownerId != null) {
            BY_OWNER.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>())
                .put(assistantName.toLowerCase(), this);
        }

        // Queued mode switches / go-home apply the instant they reach the head.
        Job head = peekJob();
        if (head != null && head.type() == Job.Type.MODE && head.mode() != null) {
            pollJob();
            setMode(head.mode());
            Player owner = getOwnerPlayer();
            if (head.mode() == Mode.FOLLOW && owner != null) {
                this.getNavigation().moveTo(owner, 1.25D);
            }
            say(switch (head.mode()) {
                case FOLLOW -> "Now following you.";
                case STAY -> "Holding here.";
                case GUARD -> "Guard mode on.";
            });
        } else if (head != null && head.type() == Job.Type.GO_HOME) {
            pollJob();
            goHome();
        }

        // Eat to heal (player rules: no free lunch). Slow fallback regen when
        // starving so it's never permanently crippled.
        if (eatCooldown > 0) eatCooldown--;
        if (isAlive() && getHealth() < getMaxHealth() && tickCount - lastDamageTick > 100) {
            if (eatCooldown == 0) {
                int slot = findFoodSlot();
                if (slot >= 0) {
                    ItemStack food = inventory.get(slot);
                    FoodProperties fp = food.get(DataComponents.FOOD);
                    ItemStack rest = this.eat(this.level(), food); // vanilla: sound, particles, shrink
                    inventory.set(slot, rest.isEmpty() ? ItemStack.EMPTY : rest);
                    heal(fp != null ? Math.max(2.0F, fp.nutrition()) : 2.0F);
                    eatCooldown = 50;
                } else if (tickCount % 160 == 0) {
                    heal(1.0F);
                }
            }
        }

        // Standing orders: when idle, check stock levels and restock.
        if (!standingOrders.isEmpty() && jobs.isEmpty() && !retreating && tickCount % 600 == 0) {
            checkStandingOrders();
        }

        // Idle initiative: with autonomy on and nothing to do, pick role work.
        if (autonomous && role != Role.NONE && jobs.isEmpty() && !retreating
            && mode != Mode.STAY && tickCount % 400 == 0 && tickCount >= idleBackoffUntil) {
            enqueueRoleWork();
        }

        // Live HP nametag (name + hearts, colored by health).
        int hp = Mth.ceil(getHealth());
        if (hp != lastShownHealth) {
            lastShownHealth = hp;
            int max = (int) getMaxHealth();
            ChatFormatting color = hp > max * 0.6 ? ChatFormatting.GREEN
                : hp > max * 0.3 ? ChatFormatting.YELLOW
                : ChatFormatting.RED;
            setCustomName(Component.literal(displayNameCap() + " ")
                .append(Component.literal(hp + "/" + max + "❤").withStyle(color)));
            setCustomNameVisible(true);
        }
    }

    private void checkStandingOrders() {
        for (StandingOrder o : standingOrders) {
            java.util.function.Predicate<ItemStack> match = kindItemMatcher(o.kind());
            BlockPos chest = findChestWith(s -> true, 24); // any chest = the depot
            if (chest == null) return;
            int stock = 0;
            if (level().getBlockEntity(chest) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty() && match.test(s)) stock += s.getCount();
                }
                rememberChest(chest, c);
            }
            if (stock < o.amount()) {
                int deficit = Math.min(64, o.amount() - stock);
                say("Standing order: chest is low on " + o.kind().label + " (" + stock + "/" + o.amount() + ") — restocking.");
                enqueue(Job.gather(o.kind(), deficit));
                enqueue(Job.deposit());
                return; // one restock cycle at a time
            }
        }
    }

    public static java.util.function.Predicate<ItemStack> kindItemMatcher(GatherGoal.Kind kind) {
        return switch (kind) {
            case LOGS -> s -> s.is(ItemTags.LOGS);
            case STONE -> s -> s.is(Blocks.COBBLESTONE.asItem()) || s.is(Blocks.STONE.asItem())
                || s.is(Blocks.COBBLED_DEEPSLATE.asItem());
            case DIRT -> s -> s.is(Blocks.DIRT.asItem());
        };
    }

    private void enqueueRoleWork() {
        switch (role) {
            case MINER -> {
                say("Nothing queued — mining a bit. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.STONE, 16));
                enqueue(Job.deposit());
            }
            case LUMBERJACK -> {
                say("Nothing queued — getting wood. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 16));
                enqueue(Job.deposit());
            }
            case FARMER -> {
                say("Nothing queued — tending the crops. (Say \"take a break\" to stop.)");
                enqueue(Job.farm());
                enqueue(Job.deposit());
            }
            case BUILDER -> {
                say("Nothing queued — stocking building materials. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 8));
                enqueue(Job.craft("planks", 8));
                enqueue(Job.deposit());
            }
            default -> { }
        }
    }

    @Nullable public BlockPos getHome() { return homePos; }
    public void setHome(@Nullable BlockPos pos) { this.homePos = pos == null ? null : pos.immutable(); }

    /** Walk back to the home point and hold there. */
    public void goHome() {
        if (homePos == null) {
            say("No home set — right-click an Assistant Spawner, or tell me \"set home here\".");
            return;
        }
        setMode(Mode.STAY);
        this.getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY() + 1, homePos.getZ() + 0.5, 1.1D);
        say("Heading home.");
    }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (ownerId != null) {
            Map<String, AssistantEntity> m = BY_OWNER.get(ownerId);
            if (m != null) m.remove(assistantName.toLowerCase(), this);
        }
        super.remove(reason);
    }

    /** Melee reach like a player's ~3 blocks, instead of the stubby mob default. */
    @Override
    protected net.minecraft.world.phys.AABB getAttackBoundingBox() {
        return super.getAttackBoundingBox().inflate(1.25, 0.0, 1.25);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean fromOwner = source.getEntity() instanceof Player p && isOwner(p);
        boolean took = super.hurt(source, fromOwner ? amount * 0.5F : amount);
        if (took) this.lastDamageTick = this.tickCount;
        return took;
    }

    /** Drop the backpack AND worn gear on the ground — used on death and on
     *  dismiss, so nothing the player gave it is ever lost. */
    public void dropEverything() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                inventory.set(i, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = this.getItemBySlot(slot);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source, boolean hitByPlayer) {
        super.dropCustomDeathLoot(level, source, hitByPlayer);
        dropEverything();
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public BlockPos feetPos() {
        return this.blockPosition();
    }
}
