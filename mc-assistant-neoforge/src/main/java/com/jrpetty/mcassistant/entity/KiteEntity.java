package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [leisure] A kite in the air (item/KiteItem, Kites): high over whoever holds its string, downwind of them, swaying on
 * the wind and climbing in a gust, the higher the windier the day. It follows its flier wherever it walks, its string
 * drawn down to the flier's hand and its tail of bows streaming under it (client/KiteRenderer). The kite in the hand is
 * the thing itself: this is only where it is flying, so it is never saved with the world; the moment its flier lets
 * go (puts it away, stops playing, goes in) it is reeled in and gone.
 */
public class KiteEntity extends Entity {

    private static final EntityDataAccessor<Integer> HOLDER = SynchedEntityData.defineId(KiteEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COLOUR = SynchedEntityData.defineId(KiteEntity.class, EntityDataSerializers.INT);

    @Nullable private UUID holder;
    /** Its own phase in the wind's sway, so two kites side by side do not dance as one. */
    private float phase;

    public KiteEntity(EntityType<? extends KiteEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** A kite sent up by this flier, in this colour, from just over its head. */
    @Nullable
    static KiteEntity launch(ServerLevel level, Entity flier, int colour) {
        KiteEntity k = LeisureItems.KITE_ENTITY.get().create(level);
        if (k == null) return null;
        k.holder = flier.getUUID();
        k.phase = level.getRandom().nextFloat() * Mth.TWO_PI;
        k.entityData.set(HOLDER, flier.getId());
        k.entityData.set(COLOUR, colour);
        k.moveTo(flier.getX(), flier.getY() + flier.getBbHeight() + 0.5, flier.getZ(), flier.getYRot(), 0.0F);
        return level.addFreshEntity(k) ? k : null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(HOLDER, -1);
        builder.define(COLOUR, 0xEFE6CF);
    }

    /** Whoever holds its string (client and server), or null. */
    @Nullable
    public Entity flier() {
        int id = entityData.get(HOLDER);
        return id < 0 ? null : level().getEntity(id);
    }

    @Nullable
    public UUID flierId() {
        return holder;
    }

    public int colour() {
        return entityData.get(COLOUR);
    }

    public float phase() {
        return phase;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        Entity f = flier();
        if (f == null || !f.isAlive() || f.level() != level() || holder == null || !holder.equals(f.getUUID()) || !Kites.stillFlying(this, f)) {
            Kites.landed(this, f);
            discard();
            return;
        }
        Vec3 to = Kites.spot((ServerLevel) level(), f, this);
        Vec3 at = position();
        Vec3 way = to.subtract(at);
        if (way.lengthSqr() > 40.0 * 40.0) {
            setPos(to);                                                     // left behind (a ride, a teleport): straight up there again
        } else {
            // Drawn on to where the wind holds it, a little at a time, as a kite lags its flier.
            Vec3 step = way.scale(0.1);
            setDeltaMovement(step);
            setPos(at.add(step));
        }
        // It faces its flier, nose up into the wind.
        double dx = f.getX() - getX(), dz = f.getZ() - getZ();
        setYRot((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128.0 * 128.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
