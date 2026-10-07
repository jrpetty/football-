package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [leisure] A leather football on the ground (item/FootballItem): a real ball. It falls, bounces (a little less each
 * time), rolls and slows with the grass's friction, and comes off a wall or a goal post. Walk or run into it and it
 * goes on ahead of you, faster the faster you were going (a dribble); punch it and it is kicked the way you are
 * looking, high and hard; right-click it for a tap, sneak and right-click to pick it up. The children's kickabout and
 * the league's match kick it the same way (Kickabout, Football), and it knows who touched it last, so a goal is
 * somebody's.
 *
 * <p>A ball the town took out of its stores (a kickabout's, a match's) goes back into them by itself when nobody has
 * played with it for a minute (a game cut short, a restart): nothing of the town's is left lying about, and nothing
 * is lost. A player's ball is the player's, and stays where it is.
 */
public class FootballEntity extends Entity {

    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(FootballEntity.class, EntityDataSerializers.ITEM_STACK);

    /** The ball's radius (half its eight pixels). */
    public static final float RADIUS = 0.25F;
    /** Gravity a tick; what a bounce keeps of the fall; what a wall keeps; the grass's roll and the air's drag. */
    static final double GRAVITY = 0.045, BOUNCE = 0.55, WALL = 0.5, ROLL = 0.935, AIR = 0.99;
    /** A fall slower than this does not bounce: it settles. */
    static final double SETTLE = 0.09;
    /** How long a town's ball lies unplayed with before it goes back into the stores (ticks). */
    static final long UNUSED = 1200L;

    /** The town whose ball it is (out of its stores), or null for a player's. */
    @Nullable private UUID town;
    /** Who touched it last, and when (game time). */
    @Nullable private UUID lastKicker;
    private long lastKickAt = -1000L;
    /** When a game last said it was playing with it (game time). */
    private long heldAt;
    /** Each one's last touch, so a body against it is a kick and not a kick every tick. */
    private final Map<UUID, Long> touched = new HashMap<>();

    /** Client: the way the ball is turned as it rolls, now and a tick ago. */
    public final Quaternionf spin = new Quaternionf(), spinO = new Quaternionf();

    public FootballEntity(EntityType<? extends FootballEntity> type, Level level) {
        super(type, level);
    }

    /** A ball set down here (a player's, or a town's for its game). Null if the world would not take it. */
    @Nullable
    public static FootballEntity setDown(ServerLevel level, double x, double y, double z, ItemStack stack, @Nullable UUID town) {
        FootballEntity ball = LeisureItems.FOOTBALL_ENTITY.get().create(level);
        if (ball == null) return null;
        ball.moveTo(x, y, z, level.getRandom().nextFloat() * 360.0F, 0.0F);
        ball.setItem(stack.isEmpty() ? new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()) : stack.copyWithCount(1));
        ball.town = town;
        ball.heldAt = level.getGameTime();
        return level.addFreshEntity(ball) ? ball : null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, ItemStack.EMPTY);
    }

    public ItemStack getItem() {
        ItemStack s = entityData.get(ITEM);
        return s.isEmpty() ? new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()) : s;
    }

    public void setItem(ItemStack s) {
        entityData.set(ITEM, s.copyWithCount(1));
    }

    @Nullable
    public UUID town() {
        return town;
    }

    @Nullable
    public UUID lastKicker() {
        return lastKicker;
    }

    public long lastKickAt() {
        return lastKickAt;
    }

    /** A game is playing with it now (a kickabout, a match): it is not to go home by itself. */
    public void inUse(long now) {
        heldAt = now;
    }

    public boolean usedSince(long t) {
        return heldAt >= t;
    }

    // ------------------------------------------------------------------ the physics

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (isInWater()) {
            v = new Vec3(v.x * 0.8, Math.min(0.08, v.y + 0.05), v.z * 0.8);             // it floats
        } else {
            v = v.add(0.0, -GRAVITY, 0.0);                                              // (lying still, it presses on the ground)
        }
        Vec3 before = v;
        move(MoverType.SELF, v);
        Vec3 after = getDeltaMovement();
        double vx = after.x, vy = after.y, vz = after.z;
        boolean thud = false;
        if (horizontalCollision) {
            // Off the wall (or the post) it came: the way it was going along that side turned back, and half of it lost.
            if (Math.abs(after.x) < 1.0E-6 && Math.abs(before.x) > 1.0E-3) { vx = -before.x * WALL; thud |= Math.abs(before.x) > 0.12; }
            if (Math.abs(after.z) < 1.0E-6 && Math.abs(before.z) > 1.0E-3) { vz = -before.z * WALL; thud |= Math.abs(before.z) > 0.12; }
        }
        if (verticalCollisionBelow && before.y < -SETTLE) {
            vy = -before.y * BOUNCE;                                                    // a bounce
            thud |= before.y < -0.2;
        } else if (verticalCollision && before.y > 0.0 && !verticalCollisionBelow) {
            vy = -before.y * 0.3;                                                       // off a ceiling
        }
        if (onGround() && vy <= 0.0) {
            vx *= ROLL;
            vz *= ROLL;
            if (vx * vx + vz * vz < 1.0E-5) { vx = 0.0; vz = 0.0; }
        } else {
            vx *= AIR;
            vz *= AIR;
            vy *= 0.99;
        }
        setDeltaMovement(vx, vy, vz);
        if (level().isClientSide) {
            roll();
            return;
        }
        if (thud) level().playSound(null, getX(), getY(), getZ(), SoundEvents.WOOL_HIT, SoundSource.NEUTRAL, 0.6F, 0.6F + random.nextFloat() * 0.2F);
        if (getY() < level().getMinBuildHeight() - 16) {
            discard();
            return;
        }
        bump();
        if (tickCount % 100 == 37) goHomeIfLeft();
    }

    /** Client: turned as it rolls, about the line across its way, by how far it went. */
    private void roll() {
        spinO.set(spin);
        double dx = getX() - xo, dz = getZ() - zo;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-4) return;
        Vector3f axis = new Vector3f((float) (dz / d), 0.0F, (float) (-dx / d));
        new Quaternionf().rotateAxis((float) (d / RADIUS), axis).mul(spin, spin);
        spin.normalize();
    }

    /** Whoever is against it now: a dribble, the ball going on ahead of them. */
    private void bump() {
        long now = level().getGameTime();
        for (Entity e : level().getEntities(this, getBoundingBox().inflate(0.12, 0.05, 0.12),
                x -> x instanceof LivingEntity && x.isAlive() && !x.isSpectator() && !(x instanceof Player p && p.isCreative() && p.getAbilities().flying))) {
            Long last = touched.get(e.getUUID());
            if (last != null && now - last < 6 && now >= last) continue;
            touched.put(e.getUUID(), now);
            if (touched.size() > 32) touched.clear();
            Vec3 away = new Vec3(getX() - e.getX(), 0.0, getZ() - e.getZ());
            Vec3 step = new Vec3(e.getX() - e.xo, 0.0, e.getZ() - e.zo);
            if (!(e instanceof Player)) {
                Vec3 dm = e.getDeltaMovement();
                if (dm.horizontalDistanceSqr() > step.horizontalDistanceSqr()) step = new Vec3(dm.x, 0.0, dm.z);
            }
            double speed = step.horizontalDistance();
            if (away.lengthSqr() < 1.0E-4) away = speed > 0.01 ? step : Vec3.directionFromRotation(0.0F, e.getYRot());
            Vec3 dir = away.normalize().scale(0.55);
            if (speed > 0.01) dir = dir.add(step.normalize().scale(0.45));
            dir = dir.normalize();
            double power = Mth.clamp(speed * 2.0, 0.16, 0.7);
            kick(new Vec3(dir.x * power, 0.05 + speed * 0.12, dir.z * power), e);
        }
    }

    /** Kicked: off it goes this way, and it remembers who. */
    public void kick(Vec3 velocity, @Nullable Entity by) {
        setDeltaMovement(velocity);
        hasImpulse = true;
        if (by != null) lastKicker = by.getUUID();
        lastKickAt = level().getGameTime();
        double power = velocity.horizontalDistance();
        if (!level().isClientSide) {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.WOOL_HIT, SoundSource.NEUTRAL, (float) Math.min(1.0, 0.4 + power), 0.75F);
            if (power > 0.5 && level() instanceof ServerLevel server) {
                BlockState under = level().getBlockState(blockPosition().below());
                if (!under.isAir()) server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), getX(), getY() + 0.05, getZ(), 6,
                    0.15, 0.02, 0.15, 0.05);
            }
        }
    }

    /** Punched: kicked the way the puncher looks, hard and a little up. (No harm done to it.) */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!level().isClientSide) {
            Vec3 look = attacker.getLookAngle();
            Vec3 flat = new Vec3(look.x, 0.0, look.z);
            if (flat.lengthSqr() < 1.0E-4) flat = Vec3.directionFromRotation(0.0F, attacker.getYRot());
            flat = flat.normalize();
            double power = attacker.isSprinting() ? 0.95 : 0.78;
            double lift = Mth.clamp(0.22 + look.y * 0.5, 0.12, 0.6);
            kick(new Vec3(flat.x * power, lift, flat.z * power), attacker);
            if (attacker instanceof Player p) Kickabout.playerKicked((ServerLevel) level(), this, p);
        }
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player.isSecondaryUseActive()) {
            if (town != null && level().getGameTime() - heldAt < 200L) {
                player.displayClientMessage(Component.literal("That's the town's ball, and there's a game on with it!"), true);
                return InteractionResult.CONSUME;
            }
            ItemStack s = getItem().copy();
            if (!player.getInventory().add(s)) spawnAtLocation(s);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 1.0F);
            discard();
            return InteractionResult.CONSUME;
        }
        // A tap: a short pass the way the player faces.
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize();
            kick(new Vec3(flat.x * 0.32, 0.08, flat.z * 0.32), player);
            Kickabout.playerKicked((ServerLevel) level(), this, player);
        }
        return InteractionResult.CONSUME;
    }

    /** A town's ball nobody has played with for a minute: back into its stores (Pastimes.ballHome). */
    private void goHomeIfLeft() {
        if (town == null || !(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        if (now - heldAt < UNUSED || now - lastKickAt < UNUSED) return;
        if (getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) return;
        Pastimes.ballHome(server, this);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return getItem().copy();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Item")) setItem(ItemStack.parseOptional(registryAccess(), tag.getCompound("Item")));
        town = tag.hasUUID("Town") ? tag.getUUID("Town") : null;
        lastKicker = tag.hasUUID("LastKicker") ? tag.getUUID("LastKicker") : null;
        heldAt = tag.getLong("Held");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ItemStack s = entityData.get(ITEM);
        if (!s.isEmpty()) tag.put("Item", s.save(registryAccess()));
        if (town != null) tag.putUUID("Town", town);
        if (lastKicker != null) tag.putUUID("LastKicker", lastKicker);
        tag.putLong("Held", heldAt);
    }
}
