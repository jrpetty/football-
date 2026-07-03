package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.DepositGoal;
import com.jrpetty.mcassistant.entity.goal.FollowOwnerGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.world.ContainerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.NonNullList;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The companion. Player-parity on purpose: it has normal mob health, walks
 * everywhere, fights with whatever damage it has, and carries loot in a real
 * 27-slot inventory that it deposits into real chests.
 *
 * Modes are standing orders (like the Node bot): FOLLOW trails the owner,
 * GUARD holds position but attacks hostiles that come near it or the owner,
 * STAY does nothing but defend itself. Gather/deposit are one-shot tasks that
 * run on top of the current mode and end by themselves.
 */
public class AssistantEntity extends PathfinderMob {

    public enum Mode { STAY, FOLLOW, GUARD }

    public static final int INVENTORY_SIZE = 27;

    // Player-parity reach: same as a survival player's default
    // block_interaction_range (4.5) and entity_interaction_range (3.0).
    public static final double BLOCK_REACH = 4.5;
    public static final double ENTITY_REACH = 3.0;

    // Owner UUID -> live assistant, so commands find it no matter how far it
    // has wandered (the old proximity-only search lost it after a gather trip,
    // which is why it "stopped listening" after one command).
    private static final Map<UUID, AssistantEntity> BY_OWNER = new ConcurrentHashMap<>();

    @Nullable
    public static AssistantEntity byOwner(UUID ownerId) {
        AssistantEntity a = BY_OWNER.get(ownerId);
        return (a != null && a.isAlive()) ? a : null;
    }

    @Nullable private UUID ownerId;
    private Mode mode = Mode.FOLLOW;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // The work queue — jobs run in order (finish one, start the next).
    private final java.util.ArrayDeque<Job> jobs = new java.util.ArrayDeque<>();
    // Bumped by requestStop() so a running goal aborts on the next check.
    private int taskGen;

    // Health feedback.
    private int lastDamageTick = -1000;
    private int lastShownHealth = -1;

    // Home block (an Assistant Spawner), if one summoned it.
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
        this.goalSelector.addGoal(2, new GatherGoal(this));
        this.goalSelector.addGoal(2, new DepositGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        // Fight back when hit, and pick fights with hostiles near us / the
        // owner when guarding. Creepers are excluded from melee brawls — the
        // fuse wins those; vanilla knockback pacing isn't enough.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this, Monster.class, 10, true, false, this::shouldAutoAttack));
    }

    private boolean shouldAutoAttack(LivingEntity target) {
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

    /** Say something to the owner (falls back to nearby players). */
    public void say(String message) {
        Player owner = getOwnerPlayer();
        Component text = Component.literal("<Assistant> " + message);
        if (owner instanceof ServerPlayer sp) {
            sp.sendSystemMessage(text);
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
        // merge pass
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
        // empty-slot pass
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

    // -------------------------------- job queue -------------------------------

    /** Add a job to the end of the queue (it runs after the current ones). */
    public void enqueue(Job job) {
        jobs.addLast(job);
    }

    @Nullable
    public Job peekJob() {
        return jobs.peekFirst();
    }

    /** Remove and return the head — a goal calls this when it FINISHES a job. */
    @Nullable
    public Job pollJob() {
        return jobs.pollFirst();
    }

    public int jobCount() {
        return jobs.size();
    }

    public java.util.List<String> jobLabels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Job j : jobs) out.add(j.label());
        return out;
    }

    // Convenience for chat/command/GUI: enqueue common jobs.
    public void requestGather(GatherGoal.Kind kind, int amount) {
        enqueue(Job.gather(kind, amount));
    }

    public void requestDeposit() {
        enqueue(Job.deposit());
    }

    /** Clear the queue and abort the running job (no chat, no mode change).
     *  Used by direct movement/mode orders so they take over immediately. */
    public void clearQueue() {
        this.jobs.clear();
        this.taskGen++; // running goal sees the mismatch and aborts
        this.getNavigation().stop();
    }

    /** Full stop: clear the queue, abort the running job, drop the target,
     *  hold position, and say so. */
    public void requestStop() {
        int had = jobs.size();
        clearQueue();
        this.setTarget(null);
        this.setMode(Mode.STAY);
        say(had > 0 ? "Stopping — cleared " + had + " queued job" + (had == 1 ? "" : "s") + "." : "Stopping.");
    }

    /** Current task generation — a one-shot goal captures this at start and
     *  aborts when it no longer matches (i.e. a newer order came in). */
    public int taskGen() {
        return taskGen;
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        if (homePos != null) tag.putLong("Home", homePos.asLong());
        tag.putString("Mode", mode.name());
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
        ContainerHelper.loadAllItems(tag, inventory, this.registryAccess());
    }

    // -------------------------------- behavior --------------------------------

    /** Right-click by the owner opens the management GUI (backpack, armor, tools, buttons). */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        // Let leads / name tags behave normally; everything else opens the menu.
        if (held.is(net.minecraft.world.item.Items.LEAD) || held.is(net.minecraft.world.item.Items.NAME_TAG)) {
            return super.mobInteract(player, hand);
        }
        if (!isOwner(player)) {
            if (!this.level().isClientSide) say("You're not my owner.");
            return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (!this.level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            sp.openMenu(
                new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new com.jrpetty.mcassistant.menu.AssistantMenu(id, inv, this),
                    net.minecraft.network.chat.Component.literal("Assistant")),
                buf -> buf.writeVarInt(this.getId()));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide());
    }

    /** Registry upkeep, out-of-combat regen, and the always-visible HP name. */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;

        if (ownerId != null && BY_OWNER.get(ownerId) != this) {
            BY_OWNER.put(ownerId, this);
        }

        // A queued mode switch ("gather logs THEN follow me") applies the
        // instant it reaches the head of the queue — no goal needed.
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
        }

        // Slow regen once it's been out of combat for ~6 seconds.
        if (isAlive() && getHealth() < getMaxHealth()
            && tickCount - lastDamageTick > 120 && tickCount % 40 == 0) {
            heal(1.0F);
        }

        // Keep a live HP readout floating over its head so you can see it's
        // mortal and how it's doing.
        int hp = Mth.ceil(getHealth());
        if (hp != lastShownHealth) {
            lastShownHealth = hp;
            int max = (int) getMaxHealth();
            net.minecraft.ChatFormatting color = hp > max * 0.6 ? net.minecraft.ChatFormatting.GREEN
                : hp > max * 0.3 ? net.minecraft.ChatFormatting.YELLOW
                : net.minecraft.ChatFormatting.RED;
            setCustomName(Component.literal("Assistant ")
                .append(Component.literal(hp + "/" + max + "❤").withStyle(color)));
            setCustomNameVisible(true);
        }
    }

    @Nullable public BlockPos getHome() { return homePos; }
    public void setHome(@Nullable BlockPos pos) { this.homePos = pos == null ? null : pos.immutable(); }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (ownerId != null) {
            BY_OWNER.remove(ownerId, this);
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
        // Owner friendly-fire is softened so you can't one-shot your companion
        // by accident, but it is NOT invincible — mobs, lava, and falls all
        // hurt it at full strength, and it dies at 0 HP.
        boolean fromOwner = source.getEntity() instanceof Player p && isOwner(p);
        boolean took = super.hurt(source, fromOwner ? amount * 0.5F : amount);
        if (took) this.lastDamageTick = this.tickCount;
        return took;
    }

    /** Drop the backpack AND worn gear on death so nothing the player gave it is lost. */
    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel level, DamageSource source, boolean hitByPlayer) {
        super.dropCustomDeathLoot(level, source, hitByPlayer);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                inventory.set(i, ItemStack.EMPTY);
            }
        }
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack s = this.getItemBySlot(slot);
            if (!s.isEmpty()) {
                this.spawnAtLocation(s);
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // a companion doesn't despawn
    }

    /** Convenience used by goals: block position rounded from our feet. */
    public BlockPos feetPos() {
        return this.blockPosition();
    }
}
