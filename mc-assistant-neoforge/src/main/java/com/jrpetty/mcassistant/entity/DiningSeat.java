package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.DiningItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;

/**
 * [dining] A chair's seat for a player at a table (Diners.sitDown): an unseen thing the player rides, set in the chair so
 * the player sits on it with their legs under the table, facing the table. Crouch to get up; it goes when nobody sits on
 * it, or the chair is taken away. Never saved: a player who logs off at the table is stood up.
 */
public class DiningSeat extends Entity {

    /** Where the rider's feet are against the chair's block: its hips (three quarters of a block over its feet when it
     *  sits) on the stair's tread, half a block up. */
    static final double SIT_DROP = -0.25;

    public DiningSeat(EntityType<? extends DiningSeat> type, Level level) {
        super(type, level);
        noPhysics = true;
        setInvisible(true);
        setNoGravity(true);
    }

    /** Sit a player down in the chair here, facing away from the chair's back. */
    static void sit(ServerLevel level, Player p, BlockPos chair) {
        DiningSeat seat = new DiningSeat(DiningItems.SEAT.get(), level);
        float yaw = 0.0F;
        var st = level.getBlockState(chair);
        if (st.getBlock() instanceof StairBlock) yaw = st.getValue(StairBlock.FACING).getOpposite().toYRot();
        seat.moveTo(chair.getX() + 0.5, chair.getY() + SIT_DROP, chair.getZ() + 0.5, yaw, 0.0F);
        level.addFreshEntity(seat);
        p.startRiding(seat, true);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        BlockPos chair = BlockPos.containing(getX(), getY() - SIT_DROP + 0.1, getZ());
        if (getPassengers().isEmpty() || !(level().getBlockState(chair).getBlock() instanceof StairBlock)) discard();
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide && getPassengers().isEmpty()) discard();
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dims, float partialTick) {
        return Vec3.ZERO;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger) {
        Vec3 out = super.getDismountLocationForPassenger(passenger);
        return out.y < getY() ? new Vec3(out.x, getY() + 0.5, out.z) : out;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public boolean isPickable() {
        return false;
    }
}
