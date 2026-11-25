package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [pa38] The Player AI Assistant's own fishing hook: the game's bobber, worked by an assistant's hand. The game's hook
 * will only answer to a player (it throws itself away for anyone else, and the client drops it), so this is the same
 * hook with an assistant on the line: it flies, bobs and bites by the game's own numbers, tick for tick (100-600 ticks
 * before a fish is lured, less a tick for each tick of Lure; 20-80 for it to come in; a bite lasting 20-40 ticks; more
 * bites in the rain, fewer under cover; open water judged as the game judges it, so treasure comes only from open
 * water), and a catch is drawn from the game's own fishing loot table with the rod as the tool and this hook as the
 * fisher's hook. The assistant's level only adds a little Lure and Luck of its own (PaKnack); it never shortens a bite
 * or reels faster. In a fight the hook catches a mob and a tug pulls it towards the assistant, as a player's does.
 *
 * <p>Nothing about it is saved: a hook in the water when the world closes is simply gone, as a player's is.
 */
public class PaFishHook extends FishingHook {

    private static final EntityDataAccessor<Integer> DATA_HOOKED = SynchedEntityData.defineId(PaFishHook.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_BITE = SynchedEntityData.defineId(PaFishHook.class, EntityDataSerializers.BOOLEAN);

    enum State { FLYING, HOOKED, BOBBING }

    private final RandomSource syncedRandom = RandomSource.create();
    private State state = State.FLYING;
    private boolean biting, openWater = true, shot;
    private int outOfWater, life, nibble, lured, hooking;
    private float fishAngle;
    @Nullable private Entity hookedIn;
    /** Luck (the rod's Luck of the Sea, and its own little knack) and ticks off the wait (the rod's Lure, and its knack). */
    private float luck;
    private int lureTicks;
    /** Only while the client builds it from the server's word: the base class insists on a player there, once. */
    private boolean recreating, settingOwner;

    public PaFishHook(EntityType<? extends PaFishHook> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    /**
     * Cast from an assistant's hand along its look (the game's own cast: from beside its eyes, about 0.6 a tick, a
     * small triangle of chance on each axis), with this much luck and lure.
     */
    public static PaFishHook cast(PlayerAssistantEntity a, float luck, int lureTicks) {
        PaFishHook h = new PaFishHook(com.jrpetty.mcassistant.McAssistantMod.PA_FISH_HOOK.get(), a.level());
        h.setOwner(a);
        h.luck = Math.max(0, luck);
        h.lureTicks = Math.max(0, lureTicks);
        float pitch = a.getXRot(), yaw = a.getYHeadRot();
        float cy = Mth.cos(-yaw * Mth.DEG_TO_RAD - (float) Math.PI), sy = Mth.sin(-yaw * Mth.DEG_TO_RAD - (float) Math.PI);
        float cp = -Mth.cos(-pitch * Mth.DEG_TO_RAD), sp = Mth.sin(-pitch * Mth.DEG_TO_RAD);
        h.moveTo(a.getX() - sy * 0.3, a.getEyeY(), a.getZ() - cy * 0.3, yaw, pitch);
        Vec3 v = new Vec3(-sy, Mth.clamp(-(sp / cp), -5.0F, 5.0F), -cy);
        double len = v.length();
        RandomSource r = h.random;
        v = v.multiply(0.6 / len + r.triangle(0.5, 0.0103365), 0.6 / len + r.triangle(0.5, 0.0103365), 0.6 / len + r.triangle(0.5, 0.0103365));
        h.setDeltaMovement(v);
        h.setYRot((float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG));
        h.setXRot((float) (Mth.atan2(v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG));
        h.yRotO = h.getYRot();
        h.xRotO = h.getXRot();
        return h;
    }

    // ------------------------------------------------------------------ what the assistant reads

    /** Is a fish on (the float pulled under)? */
    public boolean biting() {
        return biting;
    }

    /** Is the bobber settled on the water? */
    public boolean bobbing() {
        return state == State.BOBBING;
    }

    /** Is it caught in something (a mob, an item)? */
    @Nullable
    @Override
    public Entity getHookedIn() {
        return hookedIn;
    }

    /** The ripples of a fish coming in (before it bites): a learner strikes at these too soon. */
    public boolean fishComing() {
        return hooking > 0 && nibble <= 0;
    }

    @Override
    public boolean isOpenWaterFishing() {
        return openWater;
    }

    // ------------------------------------------------------------------ the base class's player, kept out of it

    @Override
    @Nullable
    public Player getPlayerOwner() {
        // The base class drops a hook with no player owner as the client builds it; once, there, any player will do
        // for its check. Never anywhere else: no player's own rod is ever told this hook is theirs.
        if (recreating && !settingOwner && level().isClientSide && !level().players().isEmpty()) return level().players().get(0);
        return null;
    }

    @Override
    public void setOwner(@Nullable Entity owner) {
        settingOwner = true;
        try { super.setOwner(owner); } finally { settingOwner = false; }
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        recreating = true;
        try { super.recreateFromPacket(packet); } finally { recreating = false; }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    // ------------------------------------------------------------------ synced state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(DATA_HOOKED, 0);
        b.define(DATA_BITE, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_HOOKED.equals(key)) {
            int i = getEntityData().get(DATA_HOOKED);
            hookedIn = i > 0 ? level().getEntity(i - 1) : null;
        }
        if (DATA_BITE.equals(key)) {
            biting = getEntityData().get(DATA_BITE);
            if (biting) setDeltaMovement(getDeltaMovement().x, -0.4F * Mth.nextFloat(syncedRandom, 0.6F, 1.0F), getDeltaMovement().z);
        }
        super.onSyncedDataUpdated(key);
    }

    private void setHooked(@Nullable Entity e) {
        hookedIn = e;
        getEntityData().set(DATA_HOOKED, e == null ? 0 : e.getId() + 1);
    }

    // ------------------------------------------------------------------ the game's bobber, tick for tick

    @Override
    public void tick() {
        syncedRandom.setSeed(getUUID().getLeastSignificantBits() ^ level().getGameTime());
        if (!shot) { gameEvent(GameEvent.PROJECTILE_SHOOT, getOwner()); shot = true; }
        baseTick();
        Entity owner = getOwner();
        if (!level().isClientSide && (owner == null || stop(owner))) { discard(); return; }
        if (onGround()) {
            if (++life >= 1200) { discard(); return; }
        } else life = 0;

        float height = 0.0F;
        BlockPos at = blockPosition();
        FluidState fluid = level().getFluidState(at);
        if (fluid.is(FluidTags.WATER)) height = fluid.getHeight(level(), at);
        boolean inWater = height > 0.0F;
        if (state == State.FLYING) {
            if (hookedIn != null) { setDeltaMovement(Vec3.ZERO); state = State.HOOKED; return; }
            if (inWater) { setDeltaMovement(getDeltaMovement().multiply(0.3, 0.2, 0.3)); state = State.BOBBING; return; }
            checkHit();
        } else {
            if (state == State.HOOKED) {
                if (hookedIn != null) {
                    if (!hookedIn.isRemoved() && hookedIn.level().dimension() == level().dimension()) setPos(hookedIn.getX(), hookedIn.getY(0.8), hookedIn.getZ());
                    else { setHooked(null); state = State.FLYING; }
                }
                return;
            }
            if (state == State.BOBBING) {
                Vec3 v = getDeltaMovement();
                double d = getY() + v.y - at.getY() - height;
                if (Math.abs(d) < 0.01) d += Math.signum(d) * 0.1;
                setDeltaMovement(v.x * 0.9, v.y - d * random.nextFloat() * 0.2, v.z * 0.9);
                if (nibble <= 0 && hooking <= 0) openWater = true;
                else openWater = openWater && outOfWater < 10 && openWater(at);
                if (inWater) {
                    outOfWater = Math.max(0, outOfWater - 1);
                    if (biting) setDeltaMovement(getDeltaMovement().add(0.0, -0.1 * syncedRandom.nextFloat() * syncedRandom.nextFloat(), 0.0));
                    if (!level().isClientSide) catching(at);
                } else outOfWater = Math.min(10, outOfWater + 1);
            }
        }
        if (!fluid.is(FluidTags.WATER)) setDeltaMovement(getDeltaMovement().add(0.0, -0.03, 0.0));
        move(MoverType.SELF, getDeltaMovement());
        updateRotation();
        if (state == State.FLYING && (onGround() || horizontalCollision)) setDeltaMovement(Vec3.ZERO);
        setDeltaMovement(getDeltaMovement().scale(0.92));
        reapplyPosition();
    }

    /** The game's rule: the line goes when its fisher is gone, has put the rod away, or is over 32 blocks off. */
    private boolean stop(Entity owner) {
        if (owner.isRemoved() || !owner.isAlive() || !(owner instanceof net.minecraft.world.entity.LivingEntity le)) return true;
        boolean rod = le.getMainHandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.FISHING_ROD_CAST)
            || le.getOffhandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.FISHING_ROD_CAST);
        return !rod || distanceToSqr(owner) > 1024.0;
    }

    private void checkHit() {
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() == HitResult.Type.MISS || !net.neoforged.neoforge.event.EventHooks.onProjectileImpact(this, hit)) onHit(hit);
    }

    /** Never its own player, nor itself, nor another assistant or a player: mobs, animals and loose items, as a rod may. */
    @Override
    protected boolean canHitEntity(Entity e) {
        Entity owner = getOwner();
        if (e == owner || e instanceof Player || e instanceof com.jrpetty.mcassistant.entity.AssistantEntity) return false;
        if (owner instanceof PlayerAssistantEntity a && e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame()) return false;   // nobody's pets
        if (!e.canBeHitByProjectile() && !(e.isAlive() && e instanceof ItemEntity)) return false;
        return true;
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!level().isClientSide) setHooked(hit.getEntity());
    }

    /** The game's bite: lured, then coming in (the ripples), then a bite that lasts 20-40 ticks before it's gone. */
    private void catching(BlockPos at) {
        ServerLevel sl = (ServerLevel) level();
        int step = 1;
        BlockPos above = at.above();
        if (random.nextFloat() < 0.25F && level().isRainingAt(above)) step++;
        if (random.nextFloat() < 0.5F && !level().canSeeSky(above)) step--;
        if (nibble > 0) {
            if (--nibble <= 0) {
                lured = 0;
                hooking = 0;
                getEntityData().set(DATA_BITE, false);
            }
        } else if (hooking > 0) {
            hooking -= step;
            if (hooking > 0) {
                fishAngle += (float) random.triangle(0.0, 9.188);
                float f = fishAngle * Mth.DEG_TO_RAD, s = Mth.sin(f), c = Mth.cos(f);
                double x = getX() + s * hooking * 0.1F, y = Mth.floor(getY()) + 1.0F, z = getZ() + c * hooking * 0.1F;
                BlockState b = sl.getBlockState(BlockPos.containing(x, y - 1.0, z));
                if (b.is(Blocks.WATER)) {
                    if (random.nextFloat() < 0.15F) sl.sendParticles(ParticleTypes.BUBBLE, x, y - 0.1F, z, 1, s, 0.1, c, 0.0);
                    float fx = s * 0.04F, fz = c * 0.04F;
                    sl.sendParticles(ParticleTypes.FISHING, x, y, z, 0, fz, 0.01, -fx, 1.0);
                    sl.sendParticles(ParticleTypes.FISHING, x, y, z, 0, -fz, 0.01, fx, 1.0);
                }
            } else {
                playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F, 1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
                double y = getY() + 0.5;
                int n = (int) (1.0F + getBbWidth() * 20.0F);
                sl.sendParticles(ParticleTypes.BUBBLE, getX(), y, getZ(), n, getBbWidth(), 0.0, getBbWidth(), 0.2F);
                sl.sendParticles(ParticleTypes.FISHING, getX(), y, getZ(), n, getBbWidth(), 0.0, getBbWidth(), 0.2F);
                nibble = Mth.nextInt(random, PaKnack.NIBBLE_MIN, PaKnack.NIBBLE_MAX);
                getEntityData().set(DATA_BITE, true);
            }
        } else if (lured > 0) {
            lured -= step;
            float chance = 0.15F;
            if (lured < 20) chance += (20 - lured) * 0.05F;
            else if (lured < 40) chance += (40 - lured) * 0.02F;
            else if (lured < 60) chance += (60 - lured) * 0.01F;
            if (random.nextFloat() < chance) {
                float ang = Mth.nextFloat(random, 0.0F, 360.0F) * Mth.DEG_TO_RAD, dist = Mth.nextFloat(random, 25.0F, 60.0F);
                double x = getX() + Mth.sin(ang) * dist * 0.1, y = Mth.floor(getY()) + 1.0F, z = getZ() + Mth.cos(ang) * dist * 0.1;
                if (sl.getBlockState(BlockPos.containing(x, y - 1.0, z)).is(Blocks.WATER))
                    sl.sendParticles(ParticleTypes.SPLASH, x, y, z, 2 + random.nextInt(2), 0.1F, 0.0, 0.1F, 0.0);
            }
            if (lured <= 0) {
                fishAngle = Mth.nextFloat(random, 0.0F, 360.0F);
                hooking = Mth.nextInt(random, PaKnack.HOOK_MIN, PaKnack.HOOK_MAX);
            }
        } else {
            lured = Mth.nextInt(random, PaKnack.LURE_MIN, PaKnack.LURE_MAX) - lureTicks;
        }
    }

    /** The game's open water: a 5x5 of water with air (or lily pads) above it, four layers deep. */
    private boolean openWater(BlockPos at) {
        int kind = -1;                                   // -1 none yet, 0 above water, 1 inside water
        for (int i = -1; i <= 2; i++) {
            int k = layer(at.offset(-2, i, -2), at.offset(2, i, 2));
            if (k == 2) return false;
            if (k == 0 && kind == -1) return false;
            if (k == 1 && kind == 0) return false;
            kind = k;
        }
        return true;
    }

    /** One layer: all air above water (0), all open water (1), or a mixture or anything else (2). */
    private int layer(BlockPos lo, BlockPos hi) {
        int kind = -1;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            BlockState s = level().getBlockState(p);
            int k;
            if (s.isAir() || s.is(Blocks.LILY_PAD)) k = 0;
            else {
                FluidState f = s.getFluidState();
                k = f.is(FluidTags.WATER) && f.isSource() && s.getCollisionShape(level(), p).isEmpty() ? 1 : 2;
            }
            if (kind == -1) kind = k;
            else if (kind != k) return 2;
        }
        return kind == -1 ? 2 : kind;
    }

    // ------------------------------------------------------------------ reeling in

    /** What one reel-in did. */
    public record Reel(int damage, List<ItemStack> caught, boolean hookedSomething, boolean empty) {}

    /**
     * Reel in now, as a player's right-click on the rod does: a hooked mob or item is tugged towards the assistant; a
     * fish on the line is landed (the game's fishing loot table, this hook as the fisher's, the rod as the tool, with
     * the luck it was cast with), its catch flying to the assistant's feet; nothing on the line is nothing. The rod's
     * wear is the game's: 1 for a catch, 3 for an item, 5 for a mob, 2 off the ground.
     */
    public Reel reel(ItemStack rod) {
        Entity owner = getOwner();
        if (level().isClientSide || owner == null || stop(owner)) { discard(); return new Reel(0, List.of(), false, true); }
        int dmg = 0;
        List<ItemStack> caught = List.of();
        boolean hooked = false;
        if (hookedIn != null) {
            pullEntity(hookedIn);
            level().broadcastEntityEvent(this, (byte) 31);
            dmg = hookedIn instanceof ItemEntity ? 3 : 5;
            hooked = true;
        } else if (nibble > 0) {
            LootParams params = new LootParams.Builder((ServerLevel) level())
                .withParameter(LootContextParams.ORIGIN, position())
                .withParameter(LootContextParams.TOOL, rod)
                .withParameter(LootContextParams.THIS_ENTITY, this)
                .withParameter(LootContextParams.ATTACKING_ENTITY, owner)
                .withLuck(luck)
                .create(LootContextParamSets.FISHING);
            LootTable table = level().getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
            caught = table.getRandomItems(params);
            for (ItemStack s : caught) {
                ItemEntity ie = new ItemEntity(level(), getX(), getY(), getZ(), s);
                double dx = owner.getX() - getX(), dy = owner.getY() - getY(), dz = owner.getZ() - getZ();
                ie.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
                level().addFreshEntity(ie);
            }
            dmg = 1;
        }
        if (onGround()) dmg = 2;
        boolean empty = !hooked && caught.isEmpty();
        discard();
        return new Reel(dmg, caught, hooked, empty);
    }

    /** Is a bite on and still on (the server's own count)? For the tests. */
    public int nibbleLeft() {
        return nibble;
    }
}
