package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.DepositGoal;
import com.jrpetty.mcassistant.entity.goal.FollowOwnerGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.world.ContainerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import java.util.UUID;

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

    @Nullable private UUID ownerId;
    private Mode mode = Mode.FOLLOW;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // One-shot task state, driven by GatherGoal / DepositGoal.
    @Nullable private GatherGoal.Request gatherRequest;
    private boolean depositRequested;

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

    // ------------------------------ task requests -----------------------------

    public void requestGather(GatherGoal.Kind kind, int amount) {
        this.gatherRequest = new GatherGoal.Request(kind, Math.max(1, Math.min(64, amount)));
    }

    @Nullable
    public GatherGoal.Request takeGatherRequest() {
        GatherGoal.Request r = this.gatherRequest;
        this.gatherRequest = null;
        return r;
    }

    public boolean hasGatherRequest() {
        return gatherRequest != null;
    }

    public void requestDeposit() {
        this.depositRequested = true;
    }

    public boolean takeDepositRequest() {
        boolean r = depositRequested;
        depositRequested = false;
        return r;
    }

    public boolean hasDepositRequest() {
        return depositRequested;
    }

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        tag.putString("Mode", mode.name());
        ContainerHelper.saveAllItems(tag, inventory, this.registryAccess());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) ownerId = tag.getUUID("Owner");
        try {
            mode = Mode.valueOf(tag.getString("Mode"));
        } catch (IllegalArgumentException ignored) {
            mode = Mode.FOLLOW;
        }
        ContainerHelper.loadAllItems(tag, inventory, this.registryAccess());
    }

    // -------------------------------- behavior --------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Don't fight the owner over friendly fire.
        if (source.getEntity() instanceof Player p && isOwner(p)) {
            return super.hurt(source, amount * 0.5F);
        }
        return super.hurt(source, amount);
    }

    /** Drop the whole inventory on death so nothing is lost. */
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
