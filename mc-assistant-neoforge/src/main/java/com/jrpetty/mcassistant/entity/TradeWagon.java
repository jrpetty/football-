package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.WagonRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wagons] A town's trade wagon: a handcart, a wagon or a covered wagon (WagonKind), parked by the trading post when
 * idle and out on the road with a trip's goods when not. This is the thing in the world: where it is, how it moves,
 * what is in its bed, who sits on its bench and what pulls it. The when and the why are Wagons'.
 *
 * <p>Out on the road it is driven along a {@link WagonRoute} by one number, the distance driven: on the level at its
 * kind's pace, slower uphill, waiting for whoever is in the way, halting while there is a fight, and never stuck for
 * long (a blocked road is waited out, then driven round, then backed out of and tried on the other side, then left
 * behind with a short safe nudge on). Its draught animal walks in the shafts ahead of it, set there each tick, and
 * its driver sits on the bench (or, at a handcart, walks between the handles). Out of every player's sight it goes
 * quiet and Wagons moves it on along the same route, a stretch at a time, till somebody comes near or it arrives.
 *
 * <p>The client is told little and seldom: the kind, the canvas's colours, a few words for the load (changed only when
 * the load does), how full it looks, and who is carrying a crate to it. Wheels, lamps and the load are drawn from
 * that (client/TradeWagonRenderer); nothing is sent per tick but the position.
 */
public class TradeWagon extends Entity {

    private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> CANVAS = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRIM = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> CARGO = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> FILL = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> PHASE = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> CARRYING = SynchedEntityData.defineId(TradeWagon.class, EntityDataSerializers.INT);

    /** What it is doing. */
    public enum Phase { PARKED, FETCH, LOADING, ROAD, UNLOADING, FAR }

    // ------------------------------------------------------------------ the numbers, and why

    /** Half its length, block centres to the axles (the model is drawn to these). */
    static final double HALF_LENGTH = 1.5;
    /** How far ahead of its middle the draught animal walks (shafts and harness). */
    static final double HITCH = 2.9;
    /** How far ahead of its middle a handcart's puller walks, between the handles. */
    static final double HANDLES = 1.6;
    /** Keep to the right of the road by this much, so two wagons meeting pass each other. */
    static final double LANE = 0.6;
    /** Speeding up and slowing down, in blocks a tick per tick (a laden wagon does not start or stop dead). */
    static final double ACCEL = 0.008, BRAKE = 0.03;
    /** A climb of more than this from one axle to the other is no road for a wagon (a wall, a cliff, a house). */
    static final double STEP = 1.25;
    /** A drop of more than this ahead is an edge, not a slope. */
    static final double DROP = 3.0;
    /** How long a blocked road is waited out before it is driven round, in ticks. */
    static final int WAIT_BLOCKED = 40;
    /** No progress for this long (for any reason but a fight or an absent driver): nudged on regardless. */
    static final int WATCHDOG = 600;
    /** Somebody in the way is given this long to move before the wagon goes on through. */
    static final int GIVE_WAY_MAX = 100;
    /** Loading or unloading takes this long (two trips with a crate each way). */
    static final int LOAD_TICKS = 160;
    /** Hostile things this near halt it, and its guards get down to fight. */
    static final double THREAT_RANGE = 14.0;
    /** The ground round it kept loaded while it is in sight on the road: this many chunks each way. */
    static final int WINDOW_RADIUS = 1;

    // ------------------------------------------------------------------ server state

    @Nullable UUID town;
    @Nullable UUID driver;
    @Nullable UUID animal;
    final List<UUID> guards = new ArrayList<>(2);
    /** The goods aboard: the truth, saved with it. The client sees only a summary (CARGO, FILL). */
    final SimpleContainer cargo = new SimpleContainer(27);
    Phase phase = Phase.PARKED;
    @Nullable WagonRoute route;
    /** Distance driven along the route. */
    double s;
    double speedNow;
    double lane = LANE;
    /** The leg it is on: from, to (towns), homeward, and the shipment it carries (or null for a caravan's goods). */
    @Nullable UUID from, to;
    boolean back;
    @Nullable UUID shipment;
    @Nullable BlockPos park;
    float parkYaw;
    float hp = -1;
    int phaseTicks;
    // stall handling
    double bestS;
    long bestTick;
    int stage;
    int blockedTicks;
    int giveWayTicks;
    long reverseUntil;
    long otherLaneUntilS = -1;
    boolean threatened;
    int quietChecks;
    long driverMissingSince = -1;
    @Nullable BlockPos window;
    /** Held still for the pictures (WagonStage): the crew kept in place, nothing driven. */
    boolean held;
    /** Wrecked (Wagons.wreck): its removal is not a loss to be reckoned again. */
    boolean wrecked;
    /** The leg's ambush has been rolled (once a leg). */
    boolean ambushRolled;
    private double groundY = Double.NaN, frontY, rearY;

    // ------------------------------------------------------------------ client state (cached; no allocation per frame)

    /** The wheels' turn, and last tick's (for the partial tick). */
    public float wheelRot, wheelRotO;
    /** The renderer's own cache of the load's layout, rebuilt only when the summary changes. */
    @Nullable public Object clientLoad;
    /** Its box for the camera's culling: the whole wagon and its animal, not the picking box (kept, not made per frame). */
    @Nullable private AABB cullBox;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ, lerpYRot, lerpXRot;

    public TradeWagon(EntityType<? extends TradeWagon> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(KIND, (byte) WagonKind.WAGON.ordinal());
        b.define(CANVAS, 0xF0EBDD);
        b.define(TRIM, 0x8C2B2B);
        b.define(CARGO, "");
        b.define(FILL, (byte) 0);
        b.define(PHASE, (byte) 0);
        b.define(CARRYING, -1);
    }

    // ------------------------------------------------------------------ what the client reads

    public WagonKind kind() { return WagonKind.byIndex(entityData.get(KIND)); }
    public int canvasColour() { return entityData.get(CANVAS); }
    public int trimColour() { return entityData.get(TRIM); }
    /** "item*count;item*count" (most first, six at most): what the load looks like. */
    public String cargoSummary() { return entityData.get(CARGO); }
    /** How full it looks, 0 to 100 (rises as it is loaded, falls as it is unloaded). */
    public int shownFill() { return entityData.get(FILL); }
    public Phase shownPhase() { return Phase.values()[Math.max(0, Math.min(Phase.values().length - 1, entityData.get(PHASE)))]; }
    /** The entity id of the folk carrying a crate to or from it just now, or -1. */
    public int carryingId() { return entityData.get(CARRYING); }

    // ------------------------------------------------------------------ set up (Wagons)

    void setKind(WagonKind k) {
        entityData.set(KIND, (byte) k.ordinal());
        if (hp < 0 || hp > k.health) hp = k.health;
    }

    void setColours(int canvas, int trim) {
        entityData.set(CANVAS, canvas);
        entityData.set(TRIM, trim);
    }

    void setPhase(Phase p) {
        if (phase != p) phaseTicks = 0;
        phase = p;
        entityData.set(PHASE, (byte) p.ordinal());
    }

    void setCarrying(@Nullable Entity e) {
        entityData.set(CARRYING, e == null ? -1 : e.getId());
    }

    void setShownFill(int pct) {
        byte b = (byte) Math.max(0, Math.min(100, pct));
        if (entityData.get(FILL) != b) entityData.set(FILL, b);
    }

    /** How full the bed really is, 0 to 100 (stacks used of what its kind carries). */
    int realFill() {
        int used = 0;
        for (int i = 0; i < cargo.getContainerSize(); i++) if (!cargo.getItem(i).isEmpty()) used++;
        return Math.min(100, Math.round(100.0F * used / Math.max(1, kind().slots)));
    }

    /** The load changed: its summary for the client worked out again (only when it really differs). */
    void cargoChanged() {
        String sum = Wagons.summary(cargo);
        if (!sum.equals(entityData.get(CARGO))) entityData.set(CARGO, sum);
    }

    /** Put on a new route at its start. */
    void setRoute(@Nullable WagonRoute r) {
        route = r;
        s = 0;
        bestS = 0;
        bestTick = level().getGameTime();
        stage = 0;
        blockedTicks = 0;
        lane = LANE;
        otherLaneUntilS = -1;
        ambushRolled = false;
    }

    // ------------------------------------------------------------------ the tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        phaseTicks++;
        try {
            Wagons.tick(level, this);
        } catch (RuntimeException e) {
            Wagons.failed(this, e);
        }
    }

    /** One step along the road: the pace, the hold-ups, the wheels on the ground. Wagons.tick calls it. */
    void drive(ServerLevel level, boolean ready) {
        if (route == null) return;
        long now = level.getGameTime();
        boolean reversing = now < reverseUntil;
        double want = 0;
        String hold = ready ? null : "crew";
        if (hold == null && threatened) hold = "fight";
        if (hold == null && !groundLoaded(level, route.xAt(s + 4), route.zAt(s + 4))) hold = "ground";
        if (hold == null && !reversing && tickCount % 5 == 0) {
            giveWayTicks = inTheWay(level) ? giveWayTicks + 5 : 0;
        }
        if (hold == null && giveWayTicks > 0 && giveWayTicks < GIVE_WAY_MAX) hold = "give way";
        if (hold == null && !reversing && tickCount % 5 == 0 && blocked(level)) blockedTicks += 5;
        else if (hold == null && tickCount % 5 == 0 && !reversing) blockedTicks = 0;
        if (hold == null && blockedTicks > 0) hold = "blocked";
        if (hold == null) {
            want = reversing ? -0.08 : kind().speed * slope();
        }
        speedNow = want > speedNow ? Math.min(want, speedNow + ACCEL) : Math.max(want, speedNow - BRAKE);
        s = Math.max(0, Math.min(route.length(), s + speedNow));
        place(level);
        // Progress, and what to do when there is none.
        if (s > bestS + 2.0) {
            bestS = s;
            bestTick = now;
            stage = 0;
        }
        if ("blocked".equals(hold) && blockedTicks >= WAIT_BLOCKED) Wagons.unstick(level, this, "blocked");
        else if (!"fight".equals(hold) && !"crew".equals(hold) && !"ground".equals(hold) && now - bestTick > WATCHDOG) {
            Wagons.unstick(level, this, hold == null ? "slow" : hold);
        }
        if (otherLaneUntilS >= 0 && s > otherLaneUntilS) {
            lane = LANE;
            otherLaneUntilS = -1;
        }
    }

    /** Uphill slower, downhill a little careful: from the two axles' ground (as place last found it). */
    private double slope() {
        if (Double.isNaN(groundY)) return 1.0;
        double rise = (frontY - rearY) / (2 * HALF_LENGTH);
        if (rise > 0.05) return Math.max(0.45, 1.0 - rise * 1.6);
        if (rise < -0.05) return 0.85;
        return 1.0;
    }

    /** Where the route puts it now: on the ground, facing along the road, in its lane, its nose up or down the slope. */
    void place(ServerLevel level) {
        if (route == null) return;
        double head = route.heading(s, 3.0);
        double fx = Math.cos(head), fz = Math.sin(head);
        double ends = Math.min(s, route.length() - s);
        double laneNow = lane * Math.max(0, Math.min(1, (ends - 2.0) / 6.0));      // drawn in to the middle at each end
        double x = route.xAt(s) - fz * laneNow, z = route.zAt(s) + fx * laneNow;
        double gy = ground(level, x, z, Double.isNaN(groundY) ? getY() : groundY);
        double front = ground(level, x + fx * HALF_LENGTH, z + fz * HALF_LENGTH, gy);
        double rear = ground(level, x - fx * HALF_LENGTH, z - fz * HALF_LENGTH, gy);
        frontY = front;
        rearY = rear;
        double target = Math.max(gy, (front + rear) * 0.5);
        groundY = Double.isNaN(groundY) ? target : groundY + Mth.clamp(target - groundY, -0.35, 0.35);
        float yaw = (float) (Math.toDegrees(head) - 90.0);
        float turned = Mth.rotLerp(0.35F, getYRot(), yaw);
        float pitch = (float) Math.toDegrees(Math.atan2(rear - front, 2 * HALF_LENGTH));
        setPos(x, groundY, z);
        setYRot(turned);
        setXRot(Mth.clamp(pitch, -20.0F, 20.0F));
    }

    /** Is something alive in the road just ahead (a folk, an animal, a player, another wagon) that it should wait for? */
    private boolean inTheWay(ServerLevel level) {
        Vec3 f = forward();
        double cx = getX() + f.x * (HALF_LENGTH + (kind().drawn ? HITCH : HANDLES) + 0.8);
        double cz = getZ() + f.z * (HALF_LENGTH + (kind().drawn ? HITCH : HANDLES) + 0.8);
        AABB box = new AABB(cx - 1.1, getY() - 0.5, cz - 1.1, cx + 1.1, getY() + 2.0, cz + 1.1);
        for (Entity e : level.getEntities(this, box)) {
            if (e instanceof TradeWagon) return true;
            if (!(e instanceof LivingEntity le) || !le.isAlive() || Wagons.ofConvoy(this, e)) continue;
            if (e instanceof net.minecraft.world.entity.monster.Enemy) continue;           // not given way to: fought
            return true;
        }
        return false;
    }

    /** Is the road ahead no road for a wagon: a wall, a house, an edge, deep water, a field? */
    boolean blocked(ServerLevel level) {
        if (route == null) return false;
        double ahead = Math.min(route.length(), s + HALF_LENGTH + 1.5);
        if (ahead - s < 0.5) return false;
        // A step from the block before to this one: a climbing road rises a block at a time, a wall all at once.
        double before = Math.max(s, ahead - 1.0);
        double h1 = route.heading(before, 2.0), h2 = route.heading(ahead, 2.0);
        double x1 = route.xAt(before) - Math.sin(h1) * lane, z1 = route.zAt(before) + Math.cos(h1) * lane;
        double x2 = route.xAt(ahead) - Math.sin(h2) * lane, z2 = route.zAt(ahead) + Math.cos(h2) * lane;
        double g1 = ground(level, x1, z1, Double.isNaN(groundY) ? getY() : groundY);
        return Wagons.noRoad(level, x2, z2, g1);
    }

    /** Ground height at (x, z): the top of what a wheel would stand on; {@code fallback} if that ground is not loaded. */
    static double ground(ServerLevel level, double x, double z, double fallback) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return fallback;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    static boolean groundLoaded(ServerLevel level, double x, double z) {
        return level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    /** The way it faces, flat. */
    public Vec3 forward() {
        float r = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
    }

    /** The spot this far ahead of its middle (behind if negative) and this far to its right, on the ground there. */
    Vec3 spot(ServerLevel level, double ahead, double right) {
        float r = getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        double x = getX() + fx * ahead - fz * right, z = getZ() + fz * ahead + fx * right;
        return new Vec3(x, ground(level, x, z, getY()), z);
    }

    // ------------------------------------------------------------------ riders

    @Override
    protected boolean canAddPassenger(Entity p) {
        return getPassengers().size() < 4;
    }

    @Override
    protected void positionRider(Entity p, Entity.MoveFunction move) {
        if (!hasPassenger(p)) return;
        Vec3 at = seat(p);
        move.accept(p, at.x, at.y, at.z);
        p.setYRot(getYRot());
        if (p instanceof LivingEntity le) {
            le.yBodyRot = getYRot();
            le.setYHeadRot(getYRot());
        }
    }

    /** Where a rider sits: the driver on the bench, a guard beside it or on the load; the animal (out of sight) in the shafts. */
    private Vec3 seat(Entity p) {
        WagonKind k = kind();
        float r = getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        double ahead, right, up;
        if (p.getUUID().equals(animal)) {
            ahead = HITCH;
            right = 0;
            up = 0;
        } else if (p.getUUID().equals(driver)) {
            ahead = k == WagonKind.COVERED ? 1.55 : 1.35;
            right = k.drawn ? -0.3 : 0;
            up = k == WagonKind.COVERED ? 1.0 : 0.85;
        } else {
            int i = Math.max(0, guards.indexOf(p.getUUID()));
            ahead = i == 0 ? (k == WagonKind.COVERED ? 1.55 : 1.35) : -1.2;
            right = i == 0 ? 0.35 : 0;
            up = i == 0 ? (k == WagonKind.COVERED ? 1.0 : 0.85) : 1.05;
        }
        return new Vec3(getX() + fx * ahead - fz * right, getY() + up, getZ() + fz * ahead + fx * right);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity p) {
        float r = getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        double x = getX() - fz * 1.9, z = getZ() + fx * 1.9;
        double y = level() instanceof ServerLevel sl ? ground(sl, x, z, getY()) : getY();
        return new Vec3(x, y, z);
    }

    @Override
    @Nullable
    public LivingEntity getControllingPassenger() {
        return null;                                            // nobody steers it but Wagons
    }

    // ------------------------------------------------------------------ the player

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide && level() instanceof ServerLevel sl) Wagons.interact(sl, this, player);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isInvulnerableTo(source) || isRemoved()) return false;
        Entity by = source.getEntity();
        if (by != null && Wagons.ofConvoy(this, by)) return false;
        if (!(by instanceof Player) && !(by instanceof Mob) && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return false;
        hp -= amount;
        markHurt();
        if (hp <= 0 && level() instanceof ServerLevel sl) {
            Wagons.wreck(sl, this, by instanceof Player p ? "broken up by " + p.getName().getString()
                : by != null ? "smashed by " + by.getName().getString().toLowerCase() : "blown apart");
        }
        return true;
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
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return ItemStack.EMPTY;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return cullBox != null ? cullBox : super.getBoundingBoxForCulling();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 160.0 * 160.0;
    }

    @Override
    public void remove(RemovalReason why) {
        if (!level().isClientSide && level() instanceof ServerLevel sl) Wagons.removed(sl, this, why);
        super.remove(why);
    }

    // ------------------------------------------------------------------ the client: smooth movement and the wheels

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpXRot = xRot;
        lerpSteps = Math.max(steps, 3);
    }

    @Override
    public double lerpTargetX() { return lerpSteps > 0 ? lerpX : getX(); }
    @Override
    public double lerpTargetY() { return lerpSteps > 0 ? lerpY : getY(); }
    @Override
    public double lerpTargetZ() { return lerpSteps > 0 ? lerpZ : getZ(); }
    @Override
    public float lerpTargetYRot() { return lerpSteps > 0 ? (float) lerpYRot : getYRot(); }
    @Override
    public float lerpTargetXRot() { return lerpSteps > 0 ? (float) lerpXRot : getXRot(); }

    private void clientTick() {
        if (lerpSteps > 0) {
            lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, lerpYRot, lerpXRot);
            lerpSteps--;
        }
        // The wheels turn with the ground covered, forward or back.
        wheelRotO = wheelRot;
        double dx = getX() - xo, dz = getZ() - zo;
        float r = getYRot() * Mth.DEG_TO_RAD;
        double along = dx * -Mth.sin(r) + dz * Mth.cos(r);
        wheelRot += (float) (along / 0.45);
        if (cullBox == null || dx != 0 || dz != 0 || getY() != yo) cullBox = getBoundingBox().inflate(3.5, 1.5, 3.5);
        com.jrpetty.mcassistant.client.WagonClientHooks.carrying(this, carryingId());
    }

    @Override
    public void onClientRemoval() {
        com.jrpetty.mcassistant.client.WagonClientHooks.carrying(this, -1);
        super.onClientRemoval();
    }

    // ------------------------------------------------------------------ kept with the world

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("Kind", kind().name());
        tag.putInt("Canvas", canvasColour());
        tag.putInt("Trim", trimColour());
        if (town != null) tag.putUUID("Town", town);
        if (driver != null) tag.putUUID("Driver", driver);
        if (animal != null) tag.putUUID("Animal", animal);
        ListTag g = new ListTag();
        for (UUID u : guards) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", u);
            g.add(one);
        }
        tag.put("Guards", g);
        tag.put("Cargo", cargo.createTag(registryAccess()));
        tag.putString("Phase", phase.name());
        if (route != null) tag.putString("Route", route.encode());
        tag.putDouble("Along", s);
        tag.putDouble("Lane", lane);
        if (from != null) tag.putUUID("From", from);
        if (to != null) tag.putUUID("To", to);
        tag.putBoolean("Back", back);
        if (shipment != null) tag.putUUID("Shipment", shipment);
        if (park != null) {
            tag.putIntArray("Park", new int[]{ park.getX(), park.getY(), park.getZ() });
            tag.putFloat("ParkYaw", parkYaw);
        }
        tag.putFloat("Hp", hp);
        tag.putBoolean("Ambushed", ambushRolled);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        WagonKind k = WagonKind.byName(tag.getString("Kind"));
        setKind(k == null ? WagonKind.WAGON : k);
        if (tag.contains("Canvas")) setColours(tag.getInt("Canvas"), tag.getInt("Trim"));
        town = tag.hasUUID("Town") ? tag.getUUID("Town") : null;
        driver = tag.hasUUID("Driver") ? tag.getUUID("Driver") : null;
        animal = tag.hasUUID("Animal") ? tag.getUUID("Animal") : null;
        guards.clear();
        for (Tag t : tag.getList("Guards", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag c && c.hasUUID("Id")) guards.add(c.getUUID("Id"));
        }
        HolderLookup.Provider reg = registryAccess();
        cargo.fromTag(tag.getList("Cargo", Tag.TAG_COMPOUND), reg);
        Phase p;
        try {
            p = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException e) {
            p = Phase.PARKED;
        }
        setPhase(p);
        route = WagonRoute.decode(tag.getString("Route"));
        s = tag.getDouble("Along");
        bestS = s;
        lane = tag.contains("Lane") ? tag.getDouble("Lane") : LANE;
        from = tag.hasUUID("From") ? tag.getUUID("From") : null;
        to = tag.hasUUID("To") ? tag.getUUID("To") : null;
        back = tag.getBoolean("Back");
        shipment = tag.hasUUID("Shipment") ? tag.getUUID("Shipment") : null;
        int[] pk = tag.getIntArray("Park");
        park = pk.length == 3 ? new BlockPos(pk[0], pk[1], pk[2]) : null;
        parkYaw = tag.getFloat("ParkYaw");
        hp = tag.contains("Hp") ? tag.getFloat("Hp") : kind().health;
        ambushRolled = tag.getBoolean("Ambushed");
        cargoChanged();
        setShownFill(phase == Phase.LOADING || phase == Phase.UNLOADING ? 0 : realFill());
    }
}
