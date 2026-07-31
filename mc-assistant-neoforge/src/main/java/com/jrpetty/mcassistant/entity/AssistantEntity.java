package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BowAttackGoal;
import com.jrpetty.mcassistant.entity.goal.BreedGoal;
import com.jrpetty.mcassistant.entity.goal.BridgeGoal;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.entity.goal.CleanupGoal;
import com.jrpetty.mcassistant.entity.goal.ClearGoal;
import com.jrpetty.mcassistant.entity.goal.CraftGoal;
import com.jrpetty.mcassistant.entity.goal.CreeperDodgeGoal;
import com.jrpetty.mcassistant.entity.goal.DepositGoal;
import com.jrpetty.mcassistant.entity.goal.FarmGoal;
import com.jrpetty.mcassistant.entity.goal.FishGoal;
import com.jrpetty.mcassistant.entity.goal.FollowOwnerGoal;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.entity.goal.GiveGoal;
import com.jrpetty.mcassistant.entity.goal.HerdGoal;
import com.jrpetty.mcassistant.entity.goal.HuntGoal;
import com.jrpetty.mcassistant.entity.goal.MineGoal;
import com.jrpetty.mcassistant.entity.goal.PatrolGoal;
import com.jrpetty.mcassistant.entity.goal.RecoverGoal;
import com.jrpetty.mcassistant.entity.goal.RetreatGoal;
import com.jrpetty.mcassistant.entity.goal.ShearGoal;
import com.jrpetty.mcassistant.entity.goal.BoatGoal;
import com.jrpetty.mcassistant.entity.goal.EnchantGoal;
import com.jrpetty.mcassistant.entity.goal.EscapeGoal;
import com.jrpetty.mcassistant.entity.goal.ExploreGoal;
import com.jrpetty.mcassistant.entity.goal.DiagnosticsGoal;
import com.jrpetty.mcassistant.entity.goal.NetherGoal;
import com.jrpetty.mcassistant.entity.goal.NightShelterGoal;
import com.jrpetty.mcassistant.entity.goal.SmeltGoal;
import com.jrpetty.mcassistant.entity.goal.SortGoal;
import com.jrpetty.mcassistant.entity.goal.TorchAreaGoal;
import com.jrpetty.mcassistant.entity.goal.TravelGoal;
import com.jrpetty.mcassistant.entity.goal.WithdrawGoal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
public class AssistantEntity extends PathfinderMob implements RangedAttackMob {

    public enum Mode { STAY, FOLLOW, GUARD }

    /** A full-time specialty the bot is pinned to at a fixed spot: FARM keeps a
     *  plot planted/watered/harvested; WOOD fells trees and replants every stump;
     *  RANCH breeds/shears/culls the herd; GUARD holds the area and keeps it lit;
     *  SMELT keeps the furnaces fed from the input chest; HAUL runs cargo from
     *  the post's chest home. Set by "farm here", "chop trees here", "ranch
     *  here", "guard here", "run the smeltery", "haul here". */
    public enum StationTask {
        NONE("none", "Unassigned"),
        FARM("farming", "Farmer"),
        WOOD("forestry", "Lumberjack"),
        MINE("mining", "Miner"),
        RANCH("ranching", "Rancher"),
        GUARD("guard duty", "Guard"),
        SMELT("smeltery", "Smelter"),
        FISH("fishing", "Fisher"),
        STORE("storekeeping", "Storekeeper"),
        HAUL("hauling", "Hauler");

        public final String label;   // lower-case, for sentences
        public final String title;   // display name, for the management screen
        StationTask(String label, String title) { this.label = label; this.title = title; }

        public static StationTask byOrdinal(int i) {
            StationTask[] all = values();
            return all[Math.floorMod(i, all.length)];
        }
    }

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

    /** A recurring chore the assistant does to itself on a timer — "tend the
     *  farm every 20 minutes". Each kind maps to a normal job; the schedule
     *  just re-queues it whenever it's due and the assistant is idle. */
    public enum RoutineKind {
        FARM("tend the farm"),
        DEPOSIT("stash surplus in a chest"),
        SORT("sort the storage"),
        CLEANUP("pick up loose items"),
        HUNT("top up the food supply"),
        LIGHT("light up the area");

        public final String label;
        RoutineKind(String label) { this.label = label; }

        @Nullable
        public static RoutineKind fromWord(String w) {
            return switch (w) {
                case "farm", "farming", "crops", "field", "harvest" -> FARM;
                case "deposit", "stash", "store", "unload", "dropoff" -> DEPOSIT;
                case "sort", "organize", "organise", "tidy" -> SORT;
                case "cleanup", "clean", "pickup" -> CLEANUP;
                case "hunt", "hunting", "food" -> HUNT;
                case "light", "torch", "lighting", "lights" -> LIGHT;
                default -> null;
            };
        }

        public Job toJob() {
            return switch (this) {
                case FARM -> Job.farm();
                case DEPOSIT -> Job.deposit();
                case SORT -> Job.sort();
                case CLEANUP -> Job.cleanup();
                case HUNT -> Job.hunt(null, 4);
                case LIGHT -> Job.torchArea(8);
            };
        }
    }

    /** One scheduled chore: run `kind` every `interval` ticks. `nextTick` is the
     *  entity-age at which it next fires (re-seeded on load, since age resets). */
    public static final class Routine {
        public final RoutineKind kind;
        public int interval;
        public int nextTick;
        public Routine(RoutineKind kind, int interval, int nextTick) {
            this.kind = kind;
            this.interval = interval;
            this.nextTick = nextTick;
        }
    }

    public static final int INVENTORY_SIZE = 27;
    public static final int MAX_PER_OWNER = 10;

    /** Build stamp — say "version" to hear it. Bumped whenever features land, so
     *  you can tell at a glance whether the loaded jar is the current one. */
    public static final String BUILD_TAG =
        "2026-07-b38 · SELF-RESUPPLY: a specialist whose tool wears out (or torches run low) now takes a replacement from its own station chest instead of downing tools until you notice — stock spares and walk away for hours · ASSISTANT HANDBOOK: a real written book comes with your first specialist, so the system teaches itself in-game (nine short pages: the loop, the screen, every job and what it needs, zones, running costs, the roster, levels and revival) · CREW ROSTER: right-click a Job Board to see every specialist at once — name, level, job, distance/bearing, live status, and what it produced today, with anyone stuck flagged in red (sneak-click still cycles the colony preset) · DAILY PRODUCTION REPORT: each specialist reports at dawn what it actually stashed, so a night of unattended work leaves a trace · every job is set up entirely by clicking the bot — the hauler's drop-off point was the last thing that needed a chat command, and it is a button now (shown only for haulers, as dig depth is only shown for miners) · every specialist holds its own upkeep back instead of stashing the rations/redstone it is about to need · a guard no longer nags for a chest nobody asked it to have · chests a job can SEE are now chests it can REACH (a chest that passed the checklist could sit out of range) · only roaming jobs drift across a big zone — smelter/fisher/storekeeper/hauler stay by their furnace, pond and chest · the farmer really uses (and wears out) the hoe it asks for · job-loop fixes: stationed bots no longer narrate every cycle (repeats hushed for ~5 min, new problems always get through) · Follow/Stay now actually take a specialist off the job · fisher searches wider for its water · storekeeper sorts on a cooldown · upkeep is clock-based so rations/charges really are spent · usability pass: picking a job is now ALL you do — the bot claims the ground around it and starts "
        + "working the moment it has its tools (Zone Marker only if you want a different patch) · zones are drawn in particles "
        + "while you hold a marker · the job button names the job · Follow/Stay back on the screen · ⚠/⚒ on the nametag "
        + "shows who is stuck at a glance · nine jobs · running costs (rations + a redstone core charge) · veteran levels · "
        + "memory core revival · manual chat/slash/voice commands parked";

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
    private int lastTownRoleTick = -2000; // debounce town role reassignment
    private net.minecraft.world.phys.Vec3 lastPathPos = net.minecraft.world.phys.Vec3.ZERO; // stuck detector
    private int stuckStreak; // consecutive ~1s windows spent going nowhere while pathing
    private int quartermasterCd; // cooldown between handing the owner supplies
    private int distressCd;      // cooldown between calls for crew backup
    private String assistantName = "assistant";
    private boolean autonomous;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);

    // The work queue — jobs run in order (finish one, start the next).
    private final java.util.ArrayDeque<Job> jobs = new java.util.ArrayDeque<>();
    private int taskGen;

    // Standing supply orders, checked when idle.
    private final List<StandingOrder> standingOrders = new ArrayList<>();

    // Scheduled chores that re-fire on a timer ("tend the farm every 20 min").
    private final List<Routine> routines = new ArrayList<>();

    // Which chest held what, learned from every chest it touches.
    private final Map<Long, Set<String>> chestMemory = new ConcurrentHashMap<>();

    // Health / survival state.
    private int lastDamageTick = -1000;
    private int lastShownHealth = -1;
    private int lastShownLevel = -1;   // nametag refresh trigger for level-ups
    private char lastShownGlyph = ' '; // nametag refresh trigger for job status
    private int eatCooldown;
    private boolean retreating;
    private int idleBackoffUntil;
    private float exploreHeading;      // scouting direction, kept so it spirals out rather than pacing
    private boolean idleKick;          // run the idle brain immediately once autonomy is switched on
    @Nullable private BlockPos stationPos;              // where its full-time post is (persisted)
    private StationTask stationTask = StationTask.NONE; // what it does there (persisted)
    private int stationWarnTick = -9999;                // cooldown on "I need a chest here" nags
    private int stationTorchTick = -99999;              // a guard post re-lights its ground rarely
    private int stationBreedTick = -99999;              // pace breeding to the animals' love cooldown
    private int stationSortTick = -99999;               // a storeroom only needs tidying now and then
    @Nullable private BlockPos mobileLoadCenter;        // hauler's traveling chunk window (persisted)
    @Nullable private WorkZone workZone;                // the patch it's assigned to (persisted)
    private int upkeepFoodTick;                         // work-ticks banked toward the next meal
    private int upkeepChargeTick;                       // work-ticks banked toward the next core charge
    private boolean upkeepStalled;                      // out of rations/charge -> not working
    private int readyCheckTick = -9999;                 // throttle for the requirements scan
    private java.util.List<String> missingEssentials = java.util.List.of();

    // --- Client-visible job state, so the management screen can show the job,
    //     its work zone, and what's still missing without any custom packets. ---
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_JOB =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_STATUS =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_ZONE =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            AssistantEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_JOB, 0);
        builder.define(DATA_STATUS, "");
        builder.define(DATA_ZONE, "");
    }

    public int clientJobOrdinal() { return this.entityData.get(DATA_JOB); }
    public String clientStatus() { return this.entityData.get(DATA_STATUS); }
    public String clientZone() { return this.entityData.get(DATA_ZONE); }

    /** Re-check the job's requirements (scans the zone) and publish the result. */
    private void refreshJobState() {
        missingEssentials = JobSpec.missing(this);
        publishJobState();
    }

    /** Publish the cached job/zone/status to watching clients. Scans nothing —
     *  safe to call while the entity is still loading in. */
    private void publishJobState() {
        this.entityData.set(DATA_JOB, stationTask.ordinal());
        this.entityData.set(DATA_ZONE, workZone == null ? "No work zone set"
            : workZone.describe() + (stationTask == StationTask.MINE ? "  depth Y" + workZone.depth() : ""));
        String status;
        if (stationTask == StationTask.NONE) {
            status = "Pick a job, then mark a zone";
        } else if (!missingEssentials.isEmpty()) {
            status = "Needs " + String.join(", ", missingEssentials);
        } else if (workZone == null && stationPos == null) {
            status = "Needs a work zone";
        } else if (upkeepStalled) {
            status = "Out of upkeep — food + redstone";
        } else {
            status = "Working";
        }
        this.entityData.set(DATA_STATUS, status);
    }

    public java.util.List<String> missingEssentials() { return missingEssentials; }

    @Nullable public WorkZone workZone() { return workZone; }

    /** Assign (or clear) the patch this bot works. The zone doubles as its post,
     *  so the station machinery keeps it there and keeps the ground loaded. */
    public void setWorkZone(@Nullable WorkZone zone) {
        this.workZone = zone;
        if (zone != null) {
            setStation(zone.center(), stationTask);
        } else if (stationPos != null) {
            setStation(null, StationTask.NONE);
        }
        refreshJobState();
    }

    /**
     * Set the specialisation. If no zone has been marked, the bot claims a
     * sensible patch around where it's standing — so picking a job is all you
     * have to do to get someone working. The Zone Marker is then a refinement
     * ("actually, work THAT field"), not a hoop to jump through first.
     */
    public void setJob(StationTask task) {
        StationTask old = stationTask;
        if (task == StationTask.NONE) {
            setStation(null, StationTask.NONE);
        } else {
            if (workZone == null) {
                BlockPos here = feetPos();
                int r = DEFAULT_ZONE_RADIUS;
                workZone = WorkZone.of(here.offset(-r, -4, -r), here.offset(r, 4, r), WorkZone.DEFAULT_DEPTH);
            }
            setStation(workZone.center(), task);
        }
        if (old != task) {
            upkeepStalled = false;
            clearQueue();
        }
        refreshJobState();
    }

    private static final int DEFAULT_ZONE_RADIUS = 12; // 25x25 patch claimed on the spot

    /** Inside the assigned patch? Without a zone every position counts, so an
     *  unzoned specialist behaves the way stations did before zones existed. */
    public boolean inZone(BlockPos pos) {
        return workZone == null || workZone.contains(pos);
    }

    /**
     * Running cost. A working specialist is hungry work: it eats a ration every
     * ~2.5 min of labour and burns a redstone "core charge" every ~10 min,
     * taking both from its own pack or the chests in its zone. Run dry and it
     * downs tools and says so — a crew is an ongoing commitment, not free labour.
     */
    private boolean payUpkeep() {
        if (stationTask == StationTask.NONE) return true;
        boolean ok = true;
        // Measured against the world clock, NOT the number of times this runs —
        // the work brain only ticks every ~10s, so counting calls would stretch
        // "every 2.5 minutes" into most of a day.
        if (upkeepFoodTick == 0) upkeepFoodTick = tickCount;
        if (upkeepChargeTick == 0) upkeepChargeTick = tickCount;
        if (tickCount - upkeepFoodTick >= 3000) {             // ~2.5 minutes of work
            if (consumeUpkeep(s -> s.get(DataComponents.FOOD) != null)) {
                upkeepFoodTick = tickCount;
            } else {
                ok = false;
                if (tickCount - stationWarnTick > 2400) {
                    stationWarnTick = tickCount;
                    say("I'm out of rations — put food in my chest and I'll get back to work.");
                }
            }
        }
        if (tickCount - upkeepChargeTick >= 12000) {          // ~10 minutes of work
            if (consumeUpkeep(s -> s.is(Items.REDSTONE))) {
                upkeepChargeTick = tickCount;
            } else {
                ok = false;
                if (tickCount - stationWarnTick > 2400) {
                    stationWarnTick = tickCount;
                    say("My core needs a charge — I need redstone to keep running.");
                }
            }
        }
        upkeepStalled = !ok;
        return ok;
    }

    /** Restock the kit this job needs from the chests at the station — the
     *  tools that wear out, and the torches that get used up. Returns true if
     *  anything was actually pulled. */
    private boolean resupplyFromChests() {
        java.util.function.Predicate<ItemStack> kit = switch (stationTask) {
            case FARM -> s -> isToolNamed(s, "_hoe");
            case WOOD -> s -> isToolNamed(s, "_axe");
            case MINE -> s -> isToolNamed(s, "_pickaxe") || s.is(Items.TORCH);
            case RANCH -> s -> s.is(Items.SHEARS);
            case GUARD -> s -> isToolNamed(s, "_sword") || s.is(Items.TORCH);
            case FISH -> s -> s.is(Items.FISHING_ROD);
            case SMELT -> s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
            case STORE, HAUL, NONE -> null;
        };
        return kit != null && scoopFromChests(kit, 16, CHEST_RANGE) > 0;
    }

    private static boolean isToolNamed(ItemStack s, String suffix) {
        return !s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(s.getItem()).getPath().endsWith(suffix);
    }

    /** Spend one upkeep item from the pack, else from a chest in the zone. */
    private boolean consumeUpkeep(java.util.function.Predicate<ItemStack> what) {
        if (removeMatching(what, 1) == 1) return true;
        return scoopFromChests(what, 8, CHEST_RANGE) > 0 && removeMatching(what, 1) == 1;
    }
    /** Chests a specialist can use, matching the range its checklist accepts
     *  one at — otherwise a chest could pass the requirement check and still
     *  be out of reach, leaving the bot silently unable to work. */
    private static final int CHEST_RANGE = 12;
    private static final int STATION_RADIUS = 16;       // how far it works from the post
    private int lastWarnTick = -1000;
    private boolean nightHome;        // "head home at night" toggle
    private boolean wentHomeTonight;  // one trip per night
    private boolean parkedForNight;   // the night routine parked it (un-park at dawn)
    private int baseStage;            // how far it has built up its home base (0=just home+chest)

    // Experience points, earned fairly from its OWN kills, ore, and smelting —
    // spent (with lapis) at an enchanting table. Not vanilla XP levels; a plain
    // pool so mobs can play by the enchant-costs-effort rule.
    private int xp;
    private int lifetimeXp;            // never decreases — drives the veteran level

    // Nether expedition state. Persisted in NBT because a dimension change
    // swaps the entity instance (and drops the transient job queue), so the
    // resurrected nether/overworld entity resumes from these.
    private boolean expeditionActive;
    private int expeditionPhase;                 // 1 = gathering in nether, 2 = back
    private String expeditionTarget = "glowstone";
    private int expeditionRemaining;
    @Nullable private BlockPos expeditionReturn; // overworld spot to come back to

    @Nullable private BlockPos homePos;

    // Named waypoints: "remember this spot as the mine" -> "go to the mine".
    private final Map<String, BlockPos> waypoints = new ConcurrentHashMap<>();

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
            // Step up full blocks instead of catching on 1-block ledges (the
            // single biggest pathing improvement over vanilla mobs), a long
            // path budget (follow range feeds the pathfinder's node limit), and
            // a bit more reach for the follow/travel goals.
            .add(Attributes.STEP_HEIGHT, 1.0D)
            .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    /** A ground navigator that floats over water, opens/passes doors, and
     *  searches a bigger area — so it stops giving up on real terrain. */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true); // don't treat water as a wall — float across it
        return nav;
    }

    /** True once it's been going nowhere for ~4s while actively trying to path —
     *  a signal it's genuinely wedged, so EscapeGoal can dig/pillar it free. */
    public boolean isBadlyStuck() {
        return stuckStreak >= 4;
    }

    private int firstSlot(java.util.function.Predicate<ItemStack> p) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && p.test(s)) return i;
        }
        return -1;
    }

    private boolean hasHarmfulEffect() {
        for (var e : getActiveEffects()) {
            if (e.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos findNearestWater(int radius) {
        BlockPos feet = blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -3, -radius), feet.offset(radius, 3, radius))) {
            if (level().getFluidState(pos).is(net.minecraft.world.level.material.Fluids.WATER)) {
                double d = pos.distSqr(feet);
                if (d < bestDist) { bestDist = d; best = pos.immutable(); }
            }
        }
        return best;
    }

    private boolean noTorchNear(int radius) {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) return false;
        }
        return true;
    }

    private void placeTorchNearby() {
        BlockPos feet = feetPos();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(dir);
            if (level().getBlockState(p).canBeReplaced()
                && level().getBlockState(p.below()).isSolid()) {
                if (removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
                    level().setBlockAndUpdate(p, Blocks.TORCH.defaultBlockState());
                }
                return;
            }
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new EscapeGoal(this)); // dig/pillar out when buried or boxed in
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new CreeperDodgeGoal(this));
        this.goalSelector.addGoal(1, new RetreatGoal(this));
        this.goalSelector.addGoal(1, new NightShelterGoal(this)); // seals in at night; yields to retreat
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new GatherGoal(this));
        this.goalSelector.addGoal(2, new DepositGoal(this));
        this.goalSelector.addGoal(2, new CraftGoal(this));
        this.goalSelector.addGoal(2, new WithdrawGoal(this));
        this.goalSelector.addGoal(2, new FarmGoal(this));
        this.goalSelector.addGoal(2, new BuildGoal(this));
        this.goalSelector.addGoal(2, new SmeltGoal(this));
        this.goalSelector.addGoal(2, new MineGoal(this));
        this.goalSelector.addGoal(2, new ShearGoal(this));
        this.goalSelector.addGoal(2, new GiveGoal(this));
        this.goalSelector.addGoal(2, new TravelGoal(this));
        this.goalSelector.addGoal(2, new PatrolGoal(this));
        this.goalSelector.addGoal(2, new ClearGoal(this));
        this.goalSelector.addGoal(2, new TorchAreaGoal(this));
        this.goalSelector.addGoal(2, new BridgeGoal(this));
        this.goalSelector.addGoal(2, new BreedGoal(this));
        this.goalSelector.addGoal(2, new HerdGoal(this));
        this.goalSelector.addGoal(2, new FishGoal(this));
        this.goalSelector.addGoal(2, new CleanupGoal(this));
        this.goalSelector.addGoal(2, new RecoverGoal(this));
        this.goalSelector.addGoal(2, new SortGoal(this));
        this.goalSelector.addGoal(2, new EnchantGoal(this));
        this.goalSelector.addGoal(2, new NetherGoal(this)); // retreat (prio 1) still preempts
        this.goalSelector.addGoal(2, new BoatGoal(this));
        this.goalSelector.addGoal(2, new DiagnosticsGoal(this)); // "run diagnostics" self-test
        this.goalSelector.addGoal(2, new ExploreGoal(this)); // relocate to fresh terrain when the area's dry
        this.goalSelector.addGoal(2, new HuntGoal(this)); // flagless coordinator
        this.goalSelector.addGoal(3, new BowAttackGoal(this));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
            this, Monster.class, 10, true, false, this::shouldAutoAttack));
    }

    private boolean shouldAutoAttack(LivingEntity target) {
        if (retreating) return false;
        // Threat-aware: don't pick a fight we should be fleeing (low HP with no
        // way to heal, or outnumbered and under-armored). RetreatGoal takes over.
        if (shouldDisengage()) return false;
        // Creepers are melee suicide — but with a bow and arrows we can take
        // them from range instead of just ignoring them.
        if (target instanceof Creeper && !(hasBow() && hasArrows())) return false;
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
        // Start looking after itself the moment the order lands, not on the next
        // 10-second idle tick — so "work on your own" visibly kicks in right away.
        // Autonomy means self-directed and DETACHED from the player: it stops
        // trailing/teleporting to the owner (FollowOwnerGoal yields while autonomous)
        // and the idle brain runs regardless of mode, so it plays for itself.
        if (autonomous) {
            this.idleKick = true;
            this.getNavigation().stop(); // drop any lingering follow path immediately
        }
    }

    /** Pin (or release, pos=null) this bot to a full-time station. Stationing
     *  turns autonomy on — a specialist works detached — and anchors home at the
     *  post if none was set, so night/retreat behavior centers on its plot.
     *  (Not for a hauler: its home is the DELIVERY end, never the pickup post.)
     *  The post's chunks are kept force-loaded so the station runs while the
     *  player is far away; the window moves/frees as the station changes. */
    public void setStation(@Nullable BlockPos pos, StationTask task) {
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (stationPos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), false);
            }
            if (pos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), pos,
                    stationChunkRadius(task), true);
            }
        }
        this.stationPos = pos;
        this.stationTask = pos == null ? StationTask.NONE : task;
        freeMobileWindow(); // a task change ends any traveling window cleanly
        if (pos != null) {
            if (homePos == null && task != StationTask.HAUL) setHome(pos);
            setAutonomous(true);
        }
    }

    /** Release the hauler's traveling chunk window, re-forcing the fixed post
     *  window afterward in case the two overlapped (tickets are a set — one
     *  remove would otherwise strip a shared chunk from the post's window). */
    private void freeMobileWindow() {
        if (mobileLoadCenter == null) return;
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), mobileLoadCenter, 1, false);
            if (stationPos != null && stationTask != StationTask.NONE) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), true);
            }
        }
        mobileLoadCenter = null;
    }

    /** How many chunks around the post stay loaded: 3x3 normally; 5x5 for a
     *  mine, whose staircase runs well past the post horizontally. */
    private static int stationChunkRadius(StationTask task) {
        return task == StationTask.MINE ? 2 : 1;
    }

    @Nullable public BlockPos stationPos() { return stationPos; }

    public StationTask stationTask() { return stationTask; }

    /** How many of this item to KEEP BACK when depositing — each specialist
     *  holds onto its working kit: seed stock, saplings, breeding food and
     *  shears, torches and arrows, ore and fuel, or travel rations. */
    public int depositReserve(ItemStack s) {
        int job = jobDepositReserve(s);
        if (stationTask == StationTask.NONE) return job;
        // Every working specialist holds its own upkeep back, whatever its job.
        // Without this a miner stashes the very redstone its core charge is
        // about to need — and a farmer the food it eats — then immediately digs
        // it back out of the chest again.
        int upkeep = s.is(Items.REDSTONE) ? 4 : (s.get(DataComponents.FOOD) != null ? 8 : 0);
        return Math.max(job, upkeep);
    }

    private int jobDepositReserve(ItemStack s) {
        return switch (stationTask) {
            case FARM -> (s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
                || s.is(Items.CARROT) || s.is(Items.POTATO)) ? 12 : 0;
            case WOOD -> s.is(ItemTags.SAPLINGS) ? 16 : 0;
            case RANCH -> BREEDING_FOOD.test(s) ? 16 : (s.is(Items.SHEARS) ? 1 : 0);
            case GUARD -> s.is(Items.TORCH) ? 16 : (s.is(Items.ARROW) ? 32
                : (s.get(DataComponents.FOOD) != null ? 8 : 0));
            case SMELT -> (s.is(Items.RAW_IRON) || s.is(Items.RAW_GOLD) || s.is(Items.RAW_COPPER)) ? 64
                : ((s.is(Items.COAL) || s.is(Items.CHARCOAL)) ? 32
                : (s.get(DataComponents.FOOD) != null ? 8 : 0));
            case HAUL -> s.get(DataComponents.FOOD) != null ? 8 : 0; // rations for the road
            case MINE -> s.is(Items.TORCH) ? 16 : (s.is(Items.COBBLESTONE) ? 16
                : (s.get(DataComponents.FOOD) != null ? 8 : 0)); // torches, bridging blocks, rations
            case FISH -> s.is(Items.FISHING_ROD) ? 1 : (s.get(DataComponents.FOOD) != null ? 8 : 0);
            case STORE -> s.get(DataComponents.FOOD) != null ? 8 : 0;
            case NONE -> 0;
        };
    }

    /** Say something to the owner. */
    public void say(String message) {
        // A stationed specialist runs the same loop for hours — narrating every
        // cycle ("Dropping a line." … "Caught 8 — good haul." … forever) would
        // bury the chat. While one is quietly working its job, a line it has
        // already said recently is dropped; anything NEW still comes straight
        // through, so problems are never silenced. Counts are normalised, so
        // "Stashed 12 items" and "Stashed 37 items" count as the same line.
        if (stationTask != StationTask.NONE && autonomous) {
            String key = message.replaceAll("\\d+", "#");
            Integer last = recentlySaid.get(key);
            if (last != null && tickCount - last < 6000) return; // ~5 minutes
            if (recentlySaid.size() > 32) recentlySaid.clear();
            recentlySaid.put(key, tickCount);
        }
        Player owner = getOwnerPlayer();
        if (owner instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("<" + displayNameCap() + "> " + message));
        }
    }

    private final Map<String, Integer> recentlySaid = new java.util.HashMap<>();

    // --- What this specialist has actually produced, so hours of unattended
    //     work are legible instead of invisible. Counted as it stashes. ---
    private final Map<net.minecraft.world.item.Item, Integer> produced = new java.util.LinkedHashMap<>();
    private long lastReportDay = -1;

    /** Called as a job stashes its output — the raw material of the day's report. */
    public void noteProduced(net.minecraft.world.item.Item item, int count) {
        if (count <= 0 || stationTask == StationTask.NONE) return;
        if (produced.size() > 24 && !produced.containsKey(item)) return; // keep it bounded
        produced.merge(item, count, Integer::sum);
    }

    /** "64 wheat, 18 raw iron, 3 diamond" — the biggest hauls first, or null. */
    @Nullable
    public String productionSummary(int topN) {
        if (produced.isEmpty()) return null;
        return produced.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .map(e -> e.getValue() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(e.getKey()).getPath().replace('_', ' '))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    /** At each dawn, report the night's work and start a fresh tally. */
    private void dailyProductionReport() {
        long day = level().getDayTime() / 24000L;
        if (day == lastReportDay) return;
        if (lastReportDay >= 0) {
            String summary = productionSummary(4);
            if (summary != null) say("Yesterday's work: " + summary + ".");
        }
        lastReportDay = day;
        produced.clear();
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

    /** Put a specific kind of tool in hand by item-id suffix ("_hoe", "_axe").
     *  Lets a job use — and wear out — the exact tool it asked the player for,
     *  even when that tool isn't the fastest thing in the pack. */
    public boolean equipToolNamed(String suffix) {
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(getMainHandItem().getItem()).getPath().endsWith(suffix)) {
            return true; // already holding one
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            if (!net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).getPath().endsWith(suffix)) continue;
            ItemStack old = this.getMainHandItem();
            this.setItemSlot(EquipmentSlot.MAINHAND, s);
            inventory.set(i, old);
            return true;
        }
        return false;
    }

    /** Ticks of work to break this block with the current tool (player-ish pacing). */
    public int workTicksFor(BlockState state) {
        float speed = Math.max(1.0F, this.getMainHandItem().getDestroySpeed(state));
        int base = Math.max(5, Math.round(48.0F / speed));
        // Veteran hands: 10% faster work at level 10, 20% at 20, 30% at 35 —
        // the cap. An edge you can feel, not a cheat.
        int bonus = veteranLevel() >= 35 ? 30 : (veteranLevel() >= 20 ? 20 : (veteranLevel() >= 10 ? 10 : 0));
        return Math.max(4, base * (100 - bonus) / 100);
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

    // ------------------------- combat gear & ranged --------------------------

    public boolean hasBow() {
        return getMainHandItem().is(Items.BOW) || countMatching(s -> s.is(Items.BOW)) > 0;
    }

    public boolean hasArrows() {
        return countMatching(s -> s.is(Items.ARROW)) > 0;
    }

    /** Hostile monsters within radius (alive). */
    public int threatCount(double radius) {
        return level().getEntitiesOfClass(Monster.class,
            getBoundingBox().inflate(radius), Monster::isAlive).size();
    }

    /** Nearest alive hostile within radius, or null. */
    @Nullable
    public Monster nearestMonster(double radius) {
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : level().getEntitiesOfClass(Monster.class,
                getBoundingBox().inflate(radius), Monster::isAlive)) {
            double d = distanceToSqr(m);
            if (d < bestSq) { bestSq = d; best = m; }
        }
        return best;
    }

    /**
     * Threat-aware judgment: is this a fight to break off rather than trade
     * blows? Fleeing a losing fight (then healing behind cover/light) beats
     * dying and dropping everything — the worst outcome for an autonomous agent.
     */
    public boolean shouldDisengage() {
        float hp = getHealth() / getMaxHealth();
        if (hp < 0.35F) return true;                     // critical — get out no matter what
        int threats = threatCount(10.0);
        if (threats == 0) return false;                  // nothing to disengage from
        if (hp < 0.55F && countFood() == 0) return true; // can't out-heal a brawl
        if (hp < 0.60F && threats >= 3 && getArmorValue() < 8) return true; // outnumbered & soft
        return false;
    }

    private void equipBow() {
        if (getMainHandItem().is(Items.BOW)) return;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).is(Items.BOW)) {
                ItemStack old = getMainHandItem();
                setItemSlot(EquipmentSlot.MAINHAND, inventory.get(i));
                inventory.set(i, old);
                return;
            }
        }
    }

    private static int weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int material = path.contains("netherite") ? 60 : path.contains("diamond") ? 50
            : path.contains("iron") ? 40 : path.contains("stone") ? 30
            : path.contains("golden") ? 20 : path.contains("wooden") ? 10 : 0;
        if (path.endsWith("_sword")) return material + 8;
        if (path.endsWith("_axe")) return material + 6;
        return 0;
    }

    public void equipBestWeapon() {
        int best = weaponScore(getMainHandItem());
        int bestIdx = -1;
        for (int i = 0; i < inventory.size(); i++) {
            int score = weaponScore(inventory.get(i));
            if (score > best) {
                best = score;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            ItemStack old = getMainHandItem();
            setItemSlot(EquipmentSlot.MAINHAND, inventory.get(bestIdx));
            inventory.set(bestIdx, old);
        }
    }

    /** Pick the right weapon for the current fight. */
    private void combatTick() {
        LivingEntity t = getTarget();
        if (t == null || !t.isAlive()) return;
        boolean creeper = t instanceof Creeper;
        boolean far = distanceToSqr(t) > 49.0;
        boolean ranged = hasBow() && hasArrows();
        if ((creeper || far) && ranged) {
            equipBow();
        } else if (creeper) {
            setTarget(null); // bow broke / out of arrows — do not melee a creeper
        } else {
            equipBestWeapon();
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (removeMatching(s -> s.is(Items.ARROW), 1) < 1) return;
        Arrow arrow = new Arrow(level(), this, new ItemStack(Items.ARROW), getMainHandItem().copy());
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, 1.6F, 6.0F);
        this.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1.0F,
            1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        level().addFreshEntity(arrow);
        ItemStack bow = getMainHandItem();
        if (bow.is(Items.BOW)) {
            bow.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
        }
    }

    /** Wear the best armor in the pack, piece by piece (like tools, for the body). */
    private void autoEquipArmor() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty() || !(s.getItem() instanceof ArmorItem candidate)) continue;
            EquipmentSlot slot = this.getEquipmentSlotForItem(s);
            if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
                && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) continue;
            ItemStack current = this.getItemBySlot(slot);
            int currentDefense = current.getItem() instanceof ArmorItem worn ? worn.getDefense() : -1;
            if (current.isEmpty() || candidate.getDefense() > currentDefense) {
                this.setItemSlot(slot, s);
                inventory.set(i, current);
            }
        }
    }

    // --------------------------- companion awareness -------------------------

    /** Warn the owner about hostiles closing in that they might not see. */
    private void dangerCallouts() {
        Player owner = getOwnerPlayer();
        if (!(owner instanceof ServerPlayer) || distanceToSqr(owner) > 48.0 * 48.0) return;
        var creepers = level().getEntitiesOfClass(Creeper.class,
            owner.getBoundingBox().inflate(7.0), LivingEntity::isAlive);
        if (!creepers.isEmpty() && tickCount - lastWarnTick > 120) {
            lastWarnTick = tickCount;
            say("CREEPER " + directionFrom(owner, creepers.get(0)) + " of you — move!");
            return;
        }
        if (tickCount - lastWarnTick > 400) {
            Monster nearest = null;
            double best = Double.MAX_VALUE;
            for (Monster m : level().getEntitiesOfClass(Monster.class,
                    owner.getBoundingBox().inflate(12.0), LivingEntity::isAlive)) {
                double d = m.distanceToSqr(owner);
                if (d < best) { best = d; nearest = m; }
            }
            if (nearest != null) {
                lastWarnTick = tickCount;
                say("Heads up — " + nearest.getName().getString() + " "
                    + directionFrom(owner, nearest) + " of you.");
            }
        }
    }

    private static String directionFrom(net.minecraft.world.entity.Entity from, net.minecraft.world.entity.Entity to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        String ew = dx > 0 ? "east" : "west";
        String ns = dz > 0 ? "south" : "north";
        if (ax > 2 * az) return ew;
        if (az > 2 * ax) return ns;
        return ns + ew;
    }

    /** Totem beats shield beats nothing in the off-hand. */
    private void manageOffhand() {
        ItemStack off = getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.is(Items.TOTEM_OF_UNDYING)) return;
        int totem = slotWith(s -> s.is(Items.TOTEM_OF_UNDYING));
        if (totem >= 0) {
            ItemStack old = off;
            setItemSlot(EquipmentSlot.OFFHAND, inventory.get(totem));
            inventory.set(totem, old);
            return;
        }
        if (off.isEmpty()) {
            int shield = slotWith(s -> s.is(Items.SHIELD));
            if (shield >= 0) {
                setItemSlot(EquipmentSlot.OFFHAND, inventory.get(shield));
                inventory.set(shield, ItemStack.EMPTY);
            }
        }
    }

    private int slotWith(java.util.function.Predicate<ItemStack> what) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (!s.isEmpty() && what.test(s)) return i;
        }
        return -1;
    }

    public boolean isNightHome() {
        return nightHome;
    }

    public void setNightHome(boolean nightHome) {
        this.nightHome = nightHome;
    }

    /** At dusk, an idle worker with the toggle on walks home and holds. */
    private void nightRoutine() {
        if (!this.level().isNight()) {
            wentHomeTonight = false;
            // Morning: un-park a bot the routine sent home for the night, so it
            // resumes autonomous work at dawn instead of holding at home forever.
            if (parkedForNight) {
                parkedForNight = false;
                if (autonomous && mode == Mode.STAY) {
                    mode = Mode.FOLLOW;
                    say("Morning — back to it.");
                }
            }
            return;
        }
        if (!nightHome || wentHomeTonight || homePos == null || retreating) return;
        if (!jobs.isEmpty()) return; // explicit orders outrank bedtime
        if (homePos.distSqr(blockPosition()) < 16 * 16) return;
        wentHomeTonight = true;
        parkedForNight = true;
        say("Sun's down — heading home for the night.");
        goHome();
    }

    /** Drop a torch at our feet when working somewhere dark. */
    private void torchIfDark() {
        BlockPos pos = blockPosition();
        if (level().getMaxLocalRawBrightness(pos) >= 6) return;
        if (!level().getBlockState(pos).canBeReplaced()) return;
        if (!level().getBlockState(pos.below()).isFaceSturdy(level(), pos.below(), net.minecraft.core.Direction.UP)) return;
        if (removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
            level().setBlockAndUpdate(pos, Blocks.TORCH.defaultBlockState());
        }
    }

    // ------------------------------- waypoints --------------------------------

    public void setWaypoint(String name, BlockPos pos) {
        waypoints.put(name.toLowerCase().trim(), pos.immutable());
    }

    @Nullable
    public BlockPos getWaypoint(String name) {
        return waypoints.get(name.toLowerCase().trim());
    }

    public List<String> waypointNames() {
        return new ArrayList<>(waypoints.keySet());
    }

    public void removeWaypoint(String name) {
        waypoints.remove(name.toLowerCase().trim());
    }

    // -------------------------------- experience ------------------------------

    public int getXp() {
        return xp;
    }

    public void awardXp(int amount) {
        if (amount <= 0) return;
        int before = veteranLevel();
        this.xp = Math.min(100000, this.xp + amount);
        this.lifetimeXp = Math.min(1_000_000, this.lifetimeXp + amount);
        int after = veteranLevel();
        if (after > before) {
            applyLevelPerks();
            lastShownHealth = -1; // refresh the nametag with the new star
            if (after % 5 == 0) { // announce milestones, not every level
                String perk = switch (after) {
                    case 10 -> " I work 10% faster now.";
                    case 20 -> " +2 hearts, and 20% faster work.";
                    case 30 -> " 20% quicker on my feet now.";
                    case 35 -> " 30% faster work — as good as I'll get.";
                    default -> "";
                };
                say("Level " + after + "!" + perk);
            }
        }
    }

    /** Veteran level from LIFETIME xp — spending xp at the enchanting table
     *  never lowers it. Deliberately SLOW: level = sqrt(lifetimeXp / 10),
     *  capped at 50 (L10 = 1000 xp, L20 = 4000, L30 = 9000, L35 = 12250) —
     *  a true veteran is weeks of work, not an afternoon. */
    public int veteranLevel() {
        return Math.min(50, (int) Math.floor(Math.sqrt(lifetimeXp / 10.0)));
    }

    /** Perks with teeth but a ceiling: +2 hearts at level 20 and 20% faster
     *  movement at 30 (attribute modifiers, re-applied idempotently); the
     *  work-speed rungs (10/20/30%) live in workTicksFor. */
    private void applyLevelPerks() {
        var hp = getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.removeModifier(VETERAN_HP_ID);
            if (veteranLevel() >= 20) {
                hp.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    VETERAN_HP_ID, 4.0,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(VETERAN_SPEED_ID);
            if (veteranLevel() >= 30) {
                speed.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    VETERAN_SPEED_ID, 0.20,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }

    private static final net.minecraft.resources.ResourceLocation VETERAN_HP_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "veteran_hearts");
    private static final net.minecraft.resources.ResourceLocation VETERAN_SPEED_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "veteran_speed");

    /** Spend up to `amount` XP; returns true if it could be paid in full. */
    public boolean spendXp(int amount) {
        if (xp < amount) return false;
        xp -= amount;
        return true;
    }

    // ---------------------------- nether expedition ---------------------------

    public boolean expeditionActive() { return expeditionActive; }
    public int expeditionPhase() { return expeditionPhase; }
    public String expeditionTarget() { return expeditionTarget; }
    public int expeditionRemaining() { return expeditionRemaining; }
    @Nullable public BlockPos expeditionReturn() { return expeditionReturn; }

    public void beginExpedition(String target, int amount, BlockPos returnPos) {
        this.expeditionActive = true;
        this.expeditionPhase = 1;
        this.expeditionTarget = target;
        this.expeditionRemaining = amount;
        this.expeditionReturn = returnPos.immutable();
    }

    public void setExpeditionPhase(int phase) { this.expeditionPhase = phase; }
    public void setExpeditionRemaining(int n) { this.expeditionRemaining = n; }
    public void endExpedition() {
        this.expeditionActive = false;
        this.expeditionPhase = 0;
        this.expeditionRemaining = 0;
        this.expeditionReturn = null;
    }

    // ------------------------------ storage totals ----------------------------

    /** Total count of matching items across the pack AND every remembered,
     *  still-loaded chest. Powers "how much iron do we have?" */
    public int countAcrossStorage(java.util.function.Predicate<ItemStack> what) {
        int total = countMatching(what);
        java.util.Set<Long> counted = new java.util.HashSet<>();
        // Every chest we remember (any distance, as long as it's loaded)...
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            if (level().getBlockEntity(pos) instanceof Container c && counted.add(key)) {
                total += countIn(c, what);
            }
        }
        // ...plus a live scan of chests physically nearby it may never have opened
        // (so "how much iron do we have?" sees the chest right next to it).
        BlockPos feet = blockPosition();
        int r = 16;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-r, -4, -r), feet.offset(r, 4, r))) {
            if (level().getBlockEntity(pos) instanceof Container c && counted.add(pos.asLong())) {
                total += countIn(c, what);
                rememberChest(pos.immutable(), c); // learn it for next time
            }
        }
        return total;
    }

    private static int countIn(Container c, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** All remembered chest positions (for the sort goal). */
    public java.util.List<BlockPos> rememberedChests() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (Long key : chestMemory.keySet()) out.add(BlockPos.of(key));
        return out;
    }

    /** Every container within `radius` (live scan) — for the craft planner. */
    public java.util.List<Container> nearbyContainers(int radius) {
        java.util.List<Container> out = new java.util.ArrayList<>();
        BlockPos feet = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -4, -radius), feet.offset(radius, 4, radius))) {
            if (level().getBlockEntity(pos) instanceof Container c) out.add(c);
        }
        return out;
    }

    // --------------------------- self-assessment ------------------------------

    /** The autonomy perception layer: a prioritized read-out of what this
     *  assistant needs right now. Foundation of the survival loop — it can
     *  say these before deciding what to do about them. */
    public java.util.List<String> assessNeeds() {
        java.util.List<String> needs = new java.util.ArrayList<>();
        if (getHealth() < getMaxHealth() * 0.4F) {
            needs.add("I'm hurt (" + (int) getHealth() + "/" + (int) getMaxHealth() + ")");
        }
        if (countFood() == 0) {
            needs.add("I'm out of food");
        }
        ItemStack tool = getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() > 0
            && tool.getDamageValue() > tool.getMaxDamage() * 0.9) {
            needs.add("my " + tool.getHoverName().getString() + " is nearly broken");
        }
        if (isPackFull()) {
            needs.add("my pack is full — I should deposit");
        }
        boolean hasPick = countMatching(s -> {
            String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            return p.endsWith("_pickaxe");
        }) > 0 || getMainHandItem().getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.5F;
        if (!hasPick) {
            needs.add("I have no pickaxe — I can't mine stone or ore");
        }
        if (!standingOrders.isEmpty()) {
            needs.add(standingOrders.size() + " standing order" + (standingOrders.size() == 1 ? "" : "s") + " to keep up");
        }
        if (needs.isEmpty()) {
            needs.add("nothing pressing — I'm in good shape");
        }
        return needs;
    }

    /**
     * The 'decide' rung of autonomy. When idle, the assistant looks after
     * itself before doing any busywork — stash a full pack, get food, keep a
     * working pickaxe, replace a worn-out tool, keep wood on hand. It picks the
     * single most pressing action, announces it, and queues it; returns true if
     * it took initiative this cycle.
     *
     * This is the heart of the end goal: dropped into the world with autonomy
     * on, it keeps itself fed, armed, and alive without being told.
     */
    private boolean decideSurvival() {
        // 1) Pack full — stash so it can keep working (only if a chest exists).
        if (isPackFull() && findChestWith(s -> true, 24) != null) {
            say("Pack's full — stashing before I carry on.");
            enqueue(Job.deposit());
            return true;
        }
        // 2) Keep a food buffer — never let the larder run dry (it eats to heal,
        //    and the quartermaster shares it with the owner).
        if (countFood() < 6 && huntablePreyNearby()) {
            say(countFood() == 0 ? "I'm out of food — hunting something to eat."
                                 : "Food's running low — topping up the larder.");
            enqueue(Job.hunt(null, 4));
            return true;
        }
        // (No prey in range? Don't loop a doomed hunt — fall through so
        //  decideProgress can farm/breed renewable food instead of freezing here.)

        // 2b) A workbench — tools and the furnace all need the 3x3 grid, and a
        //     crafting table is craftable from 4 planks with NO table (2x2). Make
        //     sure one exists before any 3x3 craft so a from-scratch bot can
        //     actually forge its first pickaxe.
        boolean woodAccess = countMatching(s -> s.is(ItemTags.LOGS)) > 0
            || countMatching(s -> s.is(ItemTags.PLANKS)) > 0
            || resourceNearby(GatherGoal.Kind.LOGS, 16);
        if (!craftingTableNearby() && countCarried(s -> s.is(Items.CRAFTING_TABLE)) == 0 && woodAccess) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "crafting_table", 1);
            if (!plan.jobs().isEmpty()) {
                say("Making a crafting table so I can build tools.");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 3) No pickaxe — it can't mine stone or ore at all; make one, sourcing
        //    the wood/stone itself via the planner. (countCarried so a pickaxe
        //    currently in the main hand still counts.)
        if (countCarried(AssistantEntity::isPickaxe) == 0) {
            String target = countMatching(s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)
                || s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) >= 3
                ? "stone_pickaxe" : "wooden_pickaxe";
            CraftPlanner.Result plan = CraftPlanner.plan(this, target, 1);
            if (!plan.jobs().isEmpty()) {
                say("I need a pickaxe — " + String.join(", ", plan.narration()) + ".");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 4) Main tool nearly broken — replace it before it snaps.
        ItemStack tool = getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() > 0
            && tool.getDamageValue() > tool.getMaxDamage() * 0.9) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()).getPath();
            CraftPlanner.Result plan = CraftPlanner.plan(this, id, 1);
            if (!plan.jobs().isEmpty()) {
                say("My " + tool.getHoverName().getString() + " is nearly broken — making a fresh one.");
                for (Job j : plan.jobs()) enqueue(j);
                return true;
            }
        }
        // 5) Keep wood on hand — it's the root of the tech tree and self-repair.
        if (countMatching(s -> s.is(ItemTags.LOGS)) == 0
            && countMatching(s -> s.is(ItemTags.PLANKS)) < 4
            && resourceNearby(GatherGoal.Kind.LOGS, 16)) {
            say("Low on wood — getting some.");
            enqueue(Job.gather(GatherGoal.Kind.LOGS, 12));
            return true;
        }
        // 6) Shelter kit — keep full blocks on hand so it can wall up when night
        //    falls. Grab cheap dirt during the day when it has no home to run to
        //    (only if there's actually dirt to dig — no spinning on bare stone).
        if (!this.level().isNight() && homePos == null
            && countMatching(com.jrpetty.mcassistant.entity.goal.NightShelterGoal.SHELTER_BLOCK) < 8
            && resourceNearby(GatherGoal.Kind.DIRT, 16)) {
            say("Grabbing some dirt for an emergency shelter, just in case.");
            enqueue(Job.gather(GatherGoal.Kind.DIRT, 12));
            return true;
        }
        return false;
    }

    private static boolean isPickaxe(ItemStack s) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(s.getItem()).getPath().endsWith("_pickaxe");
    }

    /** Count matching items across the backpack AND worn/held equipment. */
    public int countCarried(java.util.function.Predicate<ItemStack> what) {
        int n = countMatching(what);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = getItemBySlot(slot);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /**
     * The 'thrive' rung. With survival covered and time to spare, it climbs the
     * tech tree by the single highest-value next step, and only reaches for the
     * next tier once the current one is stable:
     *   stone-stable  — a stone pickaxe, a furnace, torches for light
     *   iron-safe     — iron armor (auto-worn), sword, shield, iron pickaxe,
     *                   mined + smelted + forged, piece by piece
     *   food-secure   — tend a nearby farm / breed animals for renewable food
     * Returns true if it took a step. This is what turns "survives" into
     * "thrives" — the assistant materially improves itself over time.
     */
    private boolean decideProgress() {
        // --- Stone-stable ---
        if (bestPickTier() < 2) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "stone_pickaxe", 1);
            if (!plan.jobs().isEmpty()) { announcePlan("Upgrading to stone tools", plan); return true; }
        }
        if (!furnaceNearby() && countCarried(s -> s.is(Items.FURNACE)) == 0) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "furnace", 1);
            if (!plan.jobs().isEmpty()) { announcePlan("Setting up a furnace", plan); return true; }
        }
        // --- Home base: a solo bot claims one spot as home, with a storage chest,
        //     so it has somewhere to return at night and stash surplus (which also
        //     ends "pack full, nowhere to deposit" stalls). Done once. ---
        boolean solo = ownerId == null || Town.center(ownerId) == null;
        if (solo && homePos == null && onGround()) {
            if (findAnyChest(10) != null) {
                setHome(blockPosition());
                setNightHome(true);
                say("Setting up base by this chest — I'll stash here and head home at night.");
                return true;
            }
            if (countCarried(s -> s.is(Items.CHEST)) > 0) {
                if (placeChestNearby()) {
                    setHome(blockPosition());
                    setNightHome(true);
                    say("Base set — dropped a storage chest here, and I'll head home at night.");
                    return true;
                }
            } else {
                CraftPlanner.Result plan = CraftPlanner.plan(this, "chest", 1);
                if (!plan.jobs().isEmpty()) { announcePlan("Making a storage chest to set up base", plan); return true; }
            }
        }
        boolean coalObtainable = countCarried(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0
            || resourceNearby(GatherGoal.Kind.COAL, 16);
        if (bestPickTier() >= 2 && countCarried(s -> s.is(Items.TORCH)) < 8 && coalObtainable) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "torch", 8);
            if (!plan.jobs().isEmpty()) { announcePlan("Making torches for light", plan); return true; }
        }
        // (No coal to be had here? Skip torches rather than looping on them — go
        //  straight for iron, which matters more and lights its own mine.)
        // --- Iron-safe: the biggest survivability jump, built piece by piece. ---
        String piece = nextIronPiece();
        if (piece != null && bestPickTier() >= 2) {
            int ingots = countCarried(s -> s.is(Items.IRON_INGOT));
            int raw = countCarried(s -> s.is(Items.RAW_IRON));
            if (ingots >= ironCost(piece)) {
                CraftPlanner.Result plan = CraftPlanner.plan(this, piece, 1);
                if (!plan.jobs().isEmpty()) { announcePlan("Forging " + CraftPlanner.pretty(piece), plan); return true; }
            } else if (raw > 0) {
                say("Smelting raw iron for my gear.");
                enqueue(Job.smelt("iron", raw));
                return true;
            } else {
                say("Heading down to mine iron for armor and tools.");
                enqueue(Job.mine(40));
                return true;
            }
        }
        // --- Irrigation kit: with iron to spare and a farm about to go in with no
        //     natural water in reach, forge a bucket so the plot can be hydrated
        //     (FarmGoal carves a contained source from it). Also a lava/fire tool. ---
        if (piece == null && bestPickTier() >= 2
                && countCarried(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0
                && countCarried(s -> s.is(Items.IRON_INGOT)) >= 3
                && (hasSeeds() || grassNearby()) && !waterNearby(10)) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bucket", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Forging a bucket to irrigate a farm", plan); return true; }
        }
        // --- Food-secure: renewable food. Harvest ripe crops; else bootstrap a
        //     farm from scratch (FarmGoal tills + plants); else breed a herd. ---
        if (matureCropsNearby()) { say("Harvesting the crops."); enqueue(Job.farm()); return true; }
        if (!cropsNearby() && (hasSeeds() || grassNearby())) {
            say("Setting up a small farm for steady food.");
            enqueue(Job.farm());
            return true;
        }
        if (animalsNearby() && countCarried(BREEDING_FOOD) >= 4) {
            say("Breeding the animals for a steady food supply.");
            enqueue(Job.breed(null, 2));
            return true;
        }
        // Bake bread from harvested wheat — better, keeps the larder up.
        if (countMatching(s -> s.is(Items.WHEAT)) >= 3 && countFood() < 12) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bread", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Baking bread", plan); return true; }
        }
        // --- Diamond tier: only once fully iron-safe (deep mining is riskiest). ---
        if (piece == null && bestPickTier() >= 3 && countFood() > 0) {
            String gem = nextDiamondPiece();
            if (gem != null) {
                if (countCarried(s -> s.is(Items.DIAMOND)) >= diamondCost(gem)) {
                    CraftPlanner.Result plan = CraftPlanner.plan(this, gem, 1);
                    if (!plan.jobs().isEmpty()) { announcePlan("Forging " + CraftPlanner.pretty(gem), plan); return true; }
                } else {
                    say("Fully kitted in iron — digging deep for diamonds now.");
                    enqueue(Job.mine(-54));
                    return true;
                }
            }
        }
        // --- Enchanting: multiply the gear we've got (needs a table + lapis + XP). ---
        if (enchantingReady()) {
            say("Enchanting my gear at the table.");
            enqueue(Job.enchant("gear"));
            return true;
        }
        // --- Ranged kit: a bow once it has string (e.g. from spiders it fights),
        //     then a quiver of arrows if the parts are on hand. ---
        if (countCarried(s -> s.is(Items.BOW)) == 0 && countMatching(s -> s.is(Items.STRING)) >= 3) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "bow", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Crafting a bow for range", plan); return true; }
        }
        if (countCarried(s -> s.is(Items.BOW)) > 0 && countMatching(s -> s.is(Items.ARROW)) < 16) {
            CraftPlanner.Result plan = CraftPlanner.plan(this, "arrow", 16);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) { announcePlan("Fletching arrows", plan); return true; }
        }
        // --- Grow a real homestead over time: house -> storage -> smeltery ->
        //     animal pen -> farm -> perimeter wall -> lighthouse. One building per
        //     pass, only once it has (or gathers/crafts) the materials. ---
        if (solo && homePos != null && homesteadStep()) return true;
        return false;
    }

    /** One step of building out the homestead around home. Returns true if it took
     *  an action (walked home, gathered/crafted materials, or placed a building).
     *  Advances baseStage building-by-building until the compound is complete. */
    private boolean homesteadStep() {
        if (homePos == null || baseStage > 6) return false;
        // Work on the compound from home — head back if we've wandered off.
        if (homePos.distSqr(blockPosition()) > 144) {
            getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.1D);
            return true;
        }
        BlockPos h = homePos;
        Direction f = Direction.NORTH; // fixed layout so buildings tile predictably
        // Block counts are set ABOVE each blueprint's real needs on purpose: the
        // builder only hard-stops when it has zero blocks, so under-gating would
        // leave a half-built structure. Over-gating just gathers a little spare.
        //
        // Compound plan (facing NORTH → every door opens SOUTH): house at home
        // with storage east(6) and smeltery west(6) — all inside the radius-9
        // wall with a one-block gap to it. The pen sits OUTSIDE the wall to the
        // south (gate facing the compound doorway, which is also south-center),
        // and the lighthouse rises just outside to the north. Nothing overlaps.
        return switch (baseStage) {
            case 0 -> buildStage("house", h, f, 0, 120, new ItemNeed[]{
                        new ItemNeed("crafting_table", 1), new ItemNeed("furnace", 1), new ItemNeed("chest", 1) });
            case 1 -> buildStage("storage", h.east(6), f, 0, 80, new ItemNeed[]{
                        new ItemNeed("chest", 4) });
            case 2 -> buildStage("smeltery", h.west(6), f, 0, 80, new ItemNeed[]{
                        new ItemNeed("furnace", 3), new ItemNeed("chest", 2), new ItemNeed("crafting_table", 1) });
            case 3 -> buildStage("pen", h.south(13).east(6), Direction.SOUTH, 0, 0, new ItemNeed[]{
                        new ItemNeed("oak_fence", 24), new ItemNeed("oak_fence_gate", 1) });
            case 4 -> {
                say("Homestead's coming along — laying in a farm for steady food.");
                enqueue(Job.farm());
                baseStage++;
                yield true;
            }
            case 5 -> buildStage("fortify", h, f, 9, 220, new ItemNeed[]{});
            case 6 -> buildStage("lighthouse", h.north(12), f, 0, 100, new ItemNeed[]{
                        new ItemNeed("ladder", 11) });
            default -> false;
        };
    }

    /** A functional item a building needs before it can go up (a recipe key + count). */
    private record ItemNeed(String recipeKey, int count) {}

    /** Make sure the functional items and building blocks for a homestead building
     *  are on hand (crafting/gathering the shortfall first), then place it. */
    private boolean buildStage(String structure, BlockPos anchor, Direction facing,
                               int radius, int blocksNeeded, ItemNeed[] items) {
        for (ItemNeed need : items) {
            net.minecraft.world.item.Item it = RecipeBook.item(need.recipeKey());
            int have = it == Items.AIR ? 0 : countCarried(s -> s.is(it));
            if (have < need.count()) {
                CraftPlanner.Result plan = CraftPlanner.plan(this, need.recipeKey(), need.count() - have);
                if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                    announcePlan("Making " + (need.count() - have) + " "
                        + CraftPlanner.pretty(need.recipeKey()) + " for the " + structure, plan);
                    return true;
                }
                return false; // can't get it right now — retry a later cycle, don't loop-build
            }
        }
        if (blocksNeeded > 0
            && countMatching(com.jrpetty.mcassistant.entity.goal.NightShelterGoal.SHELTER_BLOCK) < blocksNeeded) {
            GatherGoal.Kind k = resourceNearby(GatherGoal.Kind.STONE, 16)
                ? GatherGoal.Kind.STONE : GatherGoal.Kind.DIRT;
            if (!resourceNearby(k, 16)) return false; // nothing to mine here — explore will relocate
            say("Gathering blocks for the " + structure + ".");
            enqueue(Job.gather(k, Math.min(64, blocksNeeded)));
            return true;
        }
        say("Expanding the homestead — building the " + structure + ".");
        enqueue(Job.buildAt(structure, anchor, facing, radius));
        baseStage++;
        return true;
    }

    private void announcePlan(String intro, CraftPlanner.Result plan) {
        say(intro + " — " + String.join(", ", plan.narration()) + ".");
        for (Job j : plan.jobs()) enqueue(j);
    }

    /** Last resort when there's genuinely nothing to work with right here: strike
     *  out to fresh terrain so the bot keeps progressing instead of standing idle.
     *  Autonomy only (it's playing for itself), and only when it's safe to travel. */
    private void decideExplore() {
        if (!autonomous) return;                         // only when playing for itself
        if (level().isNight() || isPackFull()) return;   // regroup / deposit first
        if (getHealth() < getMaxHealth() * 0.6F) return; // heal up before trekking
        if (usefulResourceNearby(20)) return;            // still stuff here — not idle yet

        BlockPos dest = pickExploreTarget();
        if (dest == null) return;
        say("Nothing left to work with here — scouting " + compass(dest) + " for more.");
        enqueue(Job.explore(dest));
    }

    /** Station duty: stay in the assigned area and run the specialty in a loop —
     *  work what's ready, stash the output in the station chest, wait for
     *  regrowth, repeat. Returns true if it acted this cycle. */
    private boolean decideStation() {
        BlockPos st = stationPos;
        if (st == null) return false;
        // Essentials gate: no tools, no chest, no work. Re-checked a few times a
        // minute (the scan reads the world), and reported by name — once — so the
        // player knows exactly what to hand over.
        if (tickCount - readyCheckTick > 60) {
            readyCheckTick = tickCount;
            refreshJobState();
        }
        if (!missingEssentials.isEmpty()) {
            // Help itself from its own chests before bothering anyone. A player
            // who stocked spare hoes, pickaxes or torches at the station should
            // be able to walk away for hours — a worn-out tool shouldn't idle a
            // specialist until they happen to come back and notice.
            if (resupplyFromChests()) {
                refreshJobState();
                if (missingEssentials.isEmpty()) {
                    say("Grabbed a replacement from the chest — back to work.");
                    return true;
                }
            }
            if (tickCount - stationWarnTick > 2400) {
                stationWarnTick = tickCount;
                say("I can't work as a " + stationTask.title.toLowerCase()
                    + " yet — I need " + String.join(", ", missingEssentials)
                    + ". Leave spares in my chest and I'll help myself next time.");
            }
            return false;
        }
        // Running costs — rations and a core charge. Dry means downing tools.
        if (!payUpkeep()) return false;
        // Wandered off (a retreat, a chase)? Head back to the zone first.
        int leash = (workZone != null ? workZone.workRadius() : STATION_RADIUS) + 6;
        if (st.distSqr(blockPosition()) > (double) leash * leash) {
            getNavigation().moveTo(st.getX() + 0.5, st.getY(), st.getZ() + 0.5, 1.1D);
            return true;
        }
        if (stationDepositDue()) return true;
        switch (stationTask) {
            case FARM -> {
                // Work only when there's something to do — ripe crops, or a plot
                // that still needs planting. While crops grow, wait quietly.
                if (matureCropsNearby() || (!cropsNearby() && (hasSeeds() || grassNearby()))) {
                    enqueue(Job.farm());
                    return true;
                }
            }
            case WOOD -> {
                // Sweep the loose drops and saplings between fellings, then chop
                // whatever has grown back; GatherGoal replants every stump.
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                if (resourceNearby(GatherGoal.Kind.LOGS, STATION_RADIUS)) {
                    enqueue(Job.gather(GatherGoal.Kind.LOGS, 16));
                    return true;
                }
            }
            case RANCH -> {
                // Shear what's woolly, cull when the herd is big, breed it back
                // up when it's small — a rotating husbandry cycle at the pen.
                if (shearableSheepNearby()) {
                    if (countCarried(s -> s.is(Items.SHEARS)) > 0) {
                        enqueue(Job.shear(4));
                        return true;
                    }
                    if (countCarried(s -> s.is(Items.IRON_INGOT)) >= 2) {
                        CraftPlanner.Result plan = CraftPlanner.plan(this, "shears", 1);
                        if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                            announcePlan("Making shears for the wool", plan);
                            return true;
                        }
                    }
                }
                int adults = adultAnimalsNearby(STATION_RADIUS);
                if (adults > 10) {
                    say("Herd's getting big — culling a couple for the larder.");
                    enqueue(Job.hunt(null, 2));
                    return true;
                }
                // Pace breeding to the animals' ~5-minute love cooldown so the
                // rancher isn't re-running empty breed jobs between litters.
                if (adults >= 2 && tickCount - stationBreedTick > 6000) {
                    if (countCarried(BREEDING_FOOD) < 4) {
                        scoopFromChests(BREEDING_FOOD, 16, CHEST_RANGE); // the farm's produce feeds the ranch
                    }
                    if (countCarried(BREEDING_FOOD) >= 4) {
                        stationBreedTick = tickCount;
                        enqueue(Job.breed(null, 2));
                        return true;
                    }
                }
            }
            case GUARD -> {
                // Hold the ground: engage hostiles in the watch radius (creepers
                // only from range), sweep the drops, and keep the area lit.
                // A guard denies its whole patch, not just arm's reach — watch
                // out to the zone's edge (capped so a huge claim stays sane).
                Monster m = nearestMonster(workZone != null
                    ? Math.min(32, Math.max(STATION_RADIUS, workZone.workRadius())) : STATION_RADIUS);
                if (m != null && !shouldDisengage()
                    && (!(m instanceof Creeper) || (hasBow() && hasArrows()))) {
                    setTarget(m);
                    return true;
                }
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                if (countMatching(s -> s.is(Items.TORCH)) >= 4
                    && tickCount - stationTorchTick > 12000) { // ~10 min between lighting passes
                    stationTorchTick = tickCount;
                    enqueue(Job.torchArea(10));
                    return true;
                }
            }
            case SMELT -> {
                // Keep the furnaces fed: pull ore (and fuel) from the input
                // chests, run the smelts, and the ingot surplus gets stashed by
                // the deposit rung above.
                if (!furnaceNearby()) return false;
                if (countMatching(SMELTABLE_ORE) == 0) {
                    scoopFromChests(SMELTABLE_ORE, 64, CHEST_RANGE);
                }
                boolean hasFuel = countMatching(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0
                    || countMatching(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)) > 0
                    || scoopFromChests(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 16, CHEST_RANGE) > 0;
                int iron = countMatching(s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE));
                int gold = countMatching(s -> s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE));
                int copper = countMatching(s -> s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE));
                if (iron + gold + copper == 0) return false; // input chest is empty — wait
                if (!hasFuel) {
                    if (tickCount - stationWarnTick > 4800) {
                        stationWarnTick = tickCount;
                        say("The smeltery's out of fuel — drop coal (or logs) in the input chest.");
                    }
                    return false;
                }
                if (iron > 0) enqueue(Job.smelt("iron", iron));
                if (gold > 0) enqueue(Job.smelt("gold", gold));
                if (copper > 0) enqueue(Job.smelt("copper", copper));
                return true;
            }
            case MINE -> {
                // A resident miner: each run reuses/extends the staircase, tunnels
                // a fresh gallery grabbing every vein, torches as it goes, then the
                // walk-back + deposit rungs bank the haul at the post chest. The
                // zone's depth setting is the floor it digs down to.
                int targetY = workZone != null ? workZone.depth()
                    : (bestPickTier() >= 3 ? -54 : 12);
                enqueue(Job.mine(targetY));
                return true;
            }
            case FISH -> {
                // A resident angler: fishes the zone's water, cooks nothing, and
                // the deposit rung banks the catch (plus whatever junk treasure).
                enqueue(Job.fish(8));
                return true;
            }
            case STORE -> {
                // A storekeeper: keeps the zone's chests consolidated and sweeps
                // up anything left lying around the storeroom.
                if (looseDropCount(8) >= 3 && !isPackFull()) {
                    enqueue(Job.cleanup());
                    return true;
                }
                // A tidy storeroom needs tidying rarely — without this it would
                // re-sort (and announce it) every single work cycle.
                if (tickCount - stationSortTick > 1200) {
                    stationSortTick = tickCount;
                    enqueue(Job.sort());
                    return true;
                }
            }
            case HAUL -> {
                // Delivery end must exist and be a real trip away from the pickup
                // post — otherwise "home" IS the outpost and cargo just cycles.
                if (homePos == null || homePos.distSqr(st) < 24 * 24) {
                    if (tickCount - stationWarnTick > 4800) {
                        stationWarnTick = tickCount;
                        say(homePos == null
                            ? "I need a home to deliver to — stand at the base and say \"set home here\"."
                            : "My home is right next to this post — set home at the base I should deliver to.");
                    }
                    return false;
                }
                // Loaded? Run it home; the walk-back rung returns us afterward.
                if (isPackFull() || countItems() >= 24) {
                    say("Hauling a load home.");
                    enqueue(Job.goHome());
                    enqueue(Job.deposit());
                    return true;
                }
                // Empty-handed at the post: load cargo from the outpost chest.
                return scoopFromChests(s -> true, 512, CHEST_RANGE) > 0;
            }
            case NONE -> { }
        }
        // Nothing to do right where it's stood. On a zone bigger than its own
        // search radius that doesn't mean the zone is finished — it means this
        // corner is. Drift to another part of the patch so a big farm, forest or
        // pasture gets worked end to end instead of hollowed out in the middle.
        // Only for jobs that roam: a smelter, fisher, storekeeper and hauler are
        // each tied to a fixed furnace, pond or chest and must stay by it, and a
        // miner belongs at its shaft head.
        boolean roams = stationTask == StationTask.FARM || stationTask == StationTask.WOOD
            || stationTask == StationTask.RANCH || stationTask == StationTask.GUARD;
        if (roams && workZone != null && workZone.workRadius() > 16 && getNavigation().isDone()) {
            BlockPos spot = randomSpotInZone();
            if (spot != null && spot.distSqr(blockPosition()) > 64) {
                getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D);
                return true;
            }
        }
        return false; // nothing ready — crops/saplings still growing, herd resting
    }

    /** A random walkable-ish spot inside the work zone, for patrolling a big patch. */
    @Nullable
    private BlockPos randomSpotInZone() {
        if (workZone == null) return null;
        net.minecraft.util.RandomSource rnd = getRandom();
        for (int attempt = 0; attempt < 6; attempt++) {
            int x = workZone.min().getX() + rnd.nextInt(Math.max(1, workZone.sizeX()));
            int z = workZone.min().getZ() + rnd.nextInt(Math.max(1, workZone.sizeZ()));
            int y = level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > level().getMinBuildHeight() + 1) return new BlockPos(x, y, z);
        }
        return null;
    }

    /** Raw or block-form ore the smeltery keeper takes as input. */
    private static final java.util.function.Predicate<ItemStack> SMELTABLE_ORE = s ->
        s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE)
        || s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE)
        || s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE);

    private int looseDropCount(int radius) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            getBoundingBox().inflate(radius)).size();
    }

    private int adultAnimalsNearby(int radius) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(radius), a -> a.isAlive() && !a.isBaby()).size();
    }

    private boolean shearableSheepNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Sheep.class,
            getBoundingBox().inflate(STATION_RADIUS),
            sh -> sh.isAlive() && !sh.isBaby() && sh.readyForShearing()).isEmpty();
    }

    /** Pull matching items out of chests near the bot into its pack — station
     *  keepers feeding themselves work: the hauler loading cargo, the smeltery
     *  keeper taking ore and fuel, the rancher grabbing breeding food. */
    private int scoopFromChests(java.util.function.Predicate<ItemStack> what, int max, int radius) {
        int moved = 0;
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -3, -radius), feet.offset(radius, 3, radius))) {
            if (!(level().getBlockEntity(pos) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && moved < max; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int take = Math.min(s.getCount(), max - moved);
                ItemStack leftover = insertItem(s.copyWithCount(take));
                int taken = take - leftover.getCount();
                if (taken <= 0) { c.setChanged(); return moved; } // pack is full
                s.shrink(taken);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                moved += taken;
            }
            c.setChanged();
            rememberChest(pos.immutable(), c);
        }
        return moved;
    }

    /** Output piling up? Stash it at the station, setting up a chest if need be.
     *  Counts only the SURPLUS above the working reserve (seed stock, saplings) —
     *  otherwise a bot holding exactly its reserve would loop stash-nothing runs. */
    private boolean stationDepositDue() {
        // A hauler's whole job is delivering ELSEWHERE — stashing into the pickup
        // chest here would just cycle its own cargo forever.
        if (stationTask == StationTask.HAUL) return false;
        int surplus = switch (stationTask) {
            case FARM -> surplusOf(Items.WHEAT, 0) + surplusOf(Items.BEETROOT, 0)
                + surplusOf(Items.CARROT, 12) + surplusOf(Items.POTATO, 12)
                + surplusOf(Items.WHEAT_SEEDS, 12) + surplusOf(Items.BEETROOT_SEEDS, 12);
            case WOOD -> countMatching(s -> s.is(ItemTags.LOGS));
            case RANCH -> countMatching(s -> s.is(ItemTags.WOOL) || s.is(Items.LEATHER)
                || s.is(Items.FEATHER) || s.is(Items.EGG)
                || s.is(Items.BEEF) || s.is(Items.COOKED_BEEF)
                || s.is(Items.PORKCHOP) || s.is(Items.COOKED_PORKCHOP)
                || s.is(Items.MUTTON) || s.is(Items.COOKED_MUTTON)
                || s.is(Items.CHICKEN) || s.is(Items.COOKED_CHICKEN)
                || s.is(Items.RABBIT) || s.is(Items.COOKED_RABBIT));
            case GUARD -> countMatching(s -> s.is(Items.ROTTEN_FLESH) || s.is(Items.BONE)
                || s.is(Items.STRING) || s.is(Items.GUNPOWDER) || s.is(Items.SPIDER_EYE));
            case SMELT -> surplusOf(Items.IRON_INGOT, 0) + surplusOf(Items.GOLD_INGOT, 0)
                + surplusOf(Items.COPPER_INGOT, 0);
            case MINE -> surplusOf(Items.COBBLESTONE, 16) + surplusOf(Items.COBBLED_DEEPSLATE, 0)
                + surplusOf(Items.ANDESITE, 0) + surplusOf(Items.DIORITE, 0)
                + surplusOf(Items.GRANITE, 0) + surplusOf(Items.TUFF, 0)
                + surplusOf(Items.DIRT, 0) + surplusOf(Items.GRAVEL, 0)
                + surplusOf(Items.RAW_IRON, 0) + surplusOf(Items.RAW_GOLD, 0)
                + surplusOf(Items.RAW_COPPER, 0) + surplusOf(Items.COAL, 0)
                + surplusOf(Items.REDSTONE, 0) + surplusOf(Items.LAPIS_LAZULI, 0)
                + surplusOf(Items.DIAMOND, 0) + surplusOf(Items.EMERALD, 0);
            case FISH -> countMatching(s -> s.is(Items.COD) || s.is(Items.SALMON)
                || s.is(Items.TROPICAL_FISH) || s.is(Items.PUFFERFISH)
                || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON));
            // A storekeeper works the chests directly — nothing to haul back.
            case STORE, HAUL, NONE -> 0;
        };
        if (!isPackFull() && surplus < 32) return false;
        if (findAnyChest(12) == null) {
            // No chest at the post yet: place one we carry, or craft one (a
            // lumberjack always has the planks for it).
            if (countCarried(s -> s.is(Items.CHEST)) > 0 && placeChestNearby()) {
                say("Set up a chest for the station's output.");
                return true;
            }
            CraftPlanner.Result plan = CraftPlanner.plan(this, "chest", 1);
            if (!plan.jobs().isEmpty() && plan.blockers().isEmpty()) {
                announcePlan("Making a chest for the station's output", plan);
                return true;
            }
            // Only nag about a chest for jobs whose checklist actually asked for
            // one. A guard was never told it needed storage — it should keep
            // guarding and simply hold its drops, not complain about a chest the
            // player was never asked to provide.
            boolean chestWasAskedFor = JobSpec.checklist(stationTask).stream()
                .anyMatch(need -> need.contains("chest"));
            if (!chestWasAskedFor) return false;
            // Can't make one (a farm rarely has wood) — tell the player, not
            // too often, instead of silently hoarding a full pack.
            if (tickCount - stationWarnTick > 4800) {
                stationWarnTick = tickCount;
                say("My pack's filling up and there's no chest at my station — drop one nearby and I'll use it.");
            }
            return false;
        }
        say("Stashing the station's output.");
        enqueue(Job.deposit());
        return true;
    }

    private int surplusOf(net.minecraft.world.item.Item it, int reserve) {
        return Math.max(0, countMatching(s -> s.is(it)) - reserve);
    }

    /** Anything within r the bot can turn into progress: wood, stone, ore, cane,
     *  water for a farm, or animals/crops for food. Public so ExploreGoal can end
     *  a leg early the moment it walks into a fresh patch. */
    public boolean usefulResourceNearby(int r) {
        return resourceNearby(GatherGoal.Kind.LOGS, r)
            || resourceNearby(GatherGoal.Kind.STONE, r)
            || resourceNearby(GatherGoal.Kind.COAL, r)
            || resourceNearby(GatherGoal.Kind.IRON, r)
            || resourceNearby(GatherGoal.Kind.SUGAR_CANE, r)
            || matureCropsNearby() || grassNearby() || animalsNearby()
            || nearestWater(r) != null;
    }

    /** A ground-level scouting spot ~30 blocks out. Keeps a heading so it spirals
     *  outward rather than pacing, re-rolls away from open water, and turns back
     *  toward base once it has drifted far so it can still get home. */
    @Nullable
    private BlockPos pickExploreTarget() {
        net.minecraft.util.RandomSource rnd = getRandom();
        BlockPos here = feetPos();
        BlockPos anchor = homePos != null ? homePos : here;
        boolean farFromBase = anchor.distSqr(here) > 140.0 * 140.0;
        for (int attempt = 0; attempt < 6; attempt++) {
            float heading;
            if (farFromBase) {
                heading = (float) Math.atan2(anchor.getZ() - here.getZ(), anchor.getX() - here.getX());
            } else if (attempt == 0 && exploreHeading != 0f && rnd.nextInt(3) != 0) {
                heading = exploreHeading;                       // keep the same way (spiral out)
            } else {
                heading = rnd.nextFloat() * ((float) Math.PI * 2f);
            }
            int dist = 28 + rnd.nextInt(12);                    // 28..39 blocks per leg
            int tx = here.getX() + Math.round((float) Math.cos(heading) * dist);
            int tz = here.getZ() + Math.round((float) Math.sin(heading) * dist);
            int ty = level().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, tx, tz);
            if (ty <= level().getMinBuildHeight() + 1) continue; // empty/unloaded column
            BlockPos surface = new BlockPos(tx, ty, tz);
            // Skip open water/lava columns so it doesn't march into the sea.
            if (!level().getBlockState(surface.below()).getFluidState().isEmpty()) continue;
            exploreHeading = heading;
            return surface;
        }
        return null;
    }

    /** Rough compass word from here to dest, for a readable "scouting east" line. */
    private String compass(BlockPos dest) {
        BlockPos here = feetPos();
        int dx = dest.getX() - here.getX();
        int dz = dest.getZ() - here.getZ();
        String ns = dz < 0 ? "north" : "south";
        String ew = dx < 0 ? "west" : "east";
        if (Math.abs(dx) > 2 * Math.abs(dz)) return ew;
        if (Math.abs(dz) > 2 * Math.abs(dx)) return ns;
        return ns + ew;
    }

    private static final java.util.function.Predicate<ItemStack> BREEDING_FOOD = s ->
        s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.WHEAT_SEEDS);

    /** Highest-value iron kit piece still missing, or null when fully iron-safe. */
    @Nullable
    private String nextIronPiece() {
        String[] order = {"iron_chestplate", "iron_helmet", "iron_sword",
                          "iron_leggings", "iron_boots", "shield", "iron_pickaxe"};
        for (String id : order) {
            if (id.equals("iron_pickaxe") && bestPickTier() >= 3) continue; // already iron+
            net.minecraft.world.item.Item it = RecipeBook.item(id);
            if (it == Items.AIR) continue;
            if (countCarried(s -> s.is(it)) == 0) return id;
        }
        return null;
    }

    private static int ironCost(String id) {
        return switch (id) {
            case "iron_chestplate" -> 8;
            case "iron_leggings" -> 7;
            case "iron_helmet" -> 5;
            case "iron_boots" -> 4;
            case "iron_pickaxe" -> 3;
            case "iron_sword" -> 2;
            default -> 1; // shield: 1 iron + planks
        };
    }

    private int bestPickTier() {
        int best = pickTier(getMainHandItem());
        for (ItemStack s : inventory) best = Math.max(best, pickTier(s));
        return best;
    }

    private static int pickTier(ItemStack s) {
        if (s.isEmpty()) return 0;
        String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (!p.endsWith("_pickaxe")) return 0;
        if (p.startsWith("netherite") || p.startsWith("diamond")) return 4;
        if (p.startsWith("iron")) return 3;
        if (p.startsWith("stone")) return 2;
        return 1; // wooden / golden
    }

    private boolean furnaceNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE)) return true;
        }
        return false;
    }

    private boolean craftingTableNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            if (level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    /** Is a block this gather-kind can harvest actually within reach? Used to stop
     *  the idle brain from looping a doomed "gather X" where none exists nearby. */
    private boolean resourceNearby(GatherGoal.Kind kind, int radius) {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -5, -radius), feet.offset(radius, 5, radius))) {
            if (kind.matches(level().getBlockState(pos))) return true;
        }
        return false;
    }

    /** Any adult, un-named animal within hunting range (so we don't loop a hunt
     *  with no prey). */
    private boolean huntablePreyNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(24.0),
            an -> an.isAlive() && !an.isBaby() && !an.hasCustomName()).isEmpty();
    }

    private boolean cropsNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-12, -2, -12), feet.offset(12, 2, 12))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.WHEAT) || st.is(Blocks.CARROTS)
                || st.is(Blocks.POTATOES) || st.is(Blocks.BEETROOTS)) return true;
        }
        return false;
    }

    private boolean animalsNearby() {
        return !level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            getBoundingBox().inflate(12.0), a -> a.isAlive() && !a.isBaby()).isEmpty();
    }

    private boolean matureCropsNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-12, -2, -12), feet.offset(12, 2, 12))) {
            if (level().getBlockState(pos).getBlock()
                    instanceof net.minecraft.world.level.block.CropBlock crop
                && crop.isMaxAge(level().getBlockState(pos))) return true;
        }
        return false;
    }

    private boolean grassNearby() {
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -2, -10), feet.offset(10, 2, 10))) {
            BlockState st = level().getBlockState(pos);
            if (st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN)) return true;
        }
        return false;
    }

    private boolean hasSeeds() {
        return countMatching(s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
            || s.is(Items.CARROT) || s.is(Items.POTATO)) > 0;
    }

    /** Nearest water block within the given radius, or null. Lets the bot skip
     *  crafting an irrigation bucket when a natural source is already at hand,
     *  and lets it top up an empty bucket from a pool it walks past. */
    @Nullable
    private BlockPos nearestWater(int radius) {
        BlockPos feet = feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
            if (!level().getBlockState(pos).is(Blocks.WATER)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        return best;
    }

    private boolean waterNearby(int radius) {
        return nearestWater(radius) != null;
    }

    /** Highest-value diamond kit piece still missing, or null when fully diamond. */
    @Nullable
    private String nextDiamondPiece() {
        String[] order = {"diamond_pickaxe", "diamond_sword", "diamond_chestplate",
                          "diamond_helmet", "diamond_leggings", "diamond_boots"};
        for (String id : order) {
            net.minecraft.world.item.Item it = RecipeBook.item(id);
            if (it == Items.AIR) continue;
            if (countCarried(s -> s.is(it)) == 0) return id;
        }
        return null;
    }

    private static int diamondCost(String id) {
        return switch (id) {
            case "diamond_chestplate" -> 8;
            case "diamond_leggings" -> 7;
            case "diamond_helmet" -> 5;
            case "diamond_boots" -> 4;
            case "diamond_pickaxe" -> 3;
            case "diamond_sword" -> 2;
            default -> 1;
        };
    }

    /** Opportunistic enchanting: a table nearby, lapis in the pack, and a level
     *  of banked XP to spend. EnchantGoal handles the fair XP/lapis accounting. */
    private boolean enchantingReady() {
        if (getXp() < 40 || countCarried(s -> s.is(Items.LAPIS_LAZULI)) == 0) return false;
        BlockPos feet = feetPos();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
            if (level().getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) return true;
        }
        return false;
    }

    /**
     * Town coordination: if the crew has an active Job Board, each autonomous
     * member self-assigns a role to fill the town's plan — divided labour. AUTO
     * balances to whatever the depot chest beside the board is short on
     * (food/wood/stone); a fixed preset biases the whole crew toward one trade.
     * Members are slotted by name so they don't all pick the same job or thrash.
     */
    private void decideTownRole() {
        if (ownerId == null) return;
        BlockPos board = Town.center(ownerId);
        if (board == null) return;
        BlockState bs = level().getBlockState(board);
        if (!(bs.getBlock() instanceof com.jrpetty.mcassistant.block.JobBoardBlock)) {
            Town.clearCenter(ownerId); // board was broken/replaced — dissolve the town
            return;
        }
        com.jrpetty.mcassistant.block.JobBoardBlock.Preset preset =
            bs.getValue(com.jrpetty.mcassistant.block.JobBoardBlock.PRESET);

        List<AssistantEntity> crew = new ArrayList<>();
        for (AssistantEntity a : allFor(ownerId)) {
            if (a.level() == this.level() && a.blockPosition().closerThan(board, 64.0)) crew.add(a);
        }
        crew.sort(java.util.Comparator.comparing(AssistantEntity::getUUID)); // stable slotting
        int idx = crew.indexOf(this);
        if (idx < 0) return;

        Role[] plan = rolePlan(preset, board);
        Role target = plan[idx % plan.length];
        // Debounce: only actually switch (and announce) at most once a minute, so
        // depot counts wobbling across a threshold don't thrash roles or spam chat.
        if (target != role && tickCount - lastTownRoleTick > 1200) {
            setRole(target);
            lastTownRoleTick = tickCount;
            say("Town duty — I'll work as the " + target.name().toLowerCase() + ".");
        }
        // The foreman keeps the town needs board in sync with the depot.
        if (idx == 0) postDepotDeficits(board);
    }

    private Role[] rolePlan(com.jrpetty.mcassistant.block.JobBoardBlock.Preset preset, BlockPos board) {
        return switch (preset) {
            case MINING -> new Role[] {Role.MINER, Role.MINER, Role.MINER, Role.FARMER, Role.LUMBERJACK};
            case FOOD -> new Role[] {Role.FARMER, Role.FARMER, Role.LUMBERJACK, Role.MINER};
            case BUILD -> new Role[] {Role.BUILDER, Role.MINER, Role.LUMBERJACK, Role.BUILDER, Role.FARMER};
            case BALANCED -> new Role[] {Role.MINER, Role.LUMBERJACK, Role.FARMER, Role.BUILDER};
            case AUTO -> autoPlan(board);
        };
    }

    /** Need-driven plan: assign to cover the depot's biggest shortfalls first. */
    private Role[] autoPlan(BlockPos board) {
        BlockPos depot = findDepotNear(board);
        int food = depotCount(depot, s -> s.get(DataComponents.FOOD) != null);
        int wood = depotCount(depot, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS));
        int stone = depotCount(depot, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
        int iron = depotCount(depot, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
        int coal = depotCount(depot, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        List<Role> plan = new ArrayList<>();
        if (food < 16) plan.add(Role.FARMER);
        if (wood < 64) plan.add(Role.LUMBERJACK);
        if (stone < 64 || iron < 16 || coal < 16) plan.add(Role.MINER);
        if (plan.isEmpty()) { // depot's healthy — keep a balanced standing crew
            plan.add(Role.MINER); plan.add(Role.LUMBERJACK); plan.add(Role.FARMER); plan.add(Role.BUILDER);
        } else if (!plan.contains(Role.FARMER)) {
            plan.add(Role.FARMER); // always keep the larder tended
        }
        return plan.toArray(new Role[0]);
    }

    @Nullable
    private BlockPos findDepotNear(BlockPos board) {
        for (BlockPos pos : BlockPos.betweenClosed(board.offset(-8, -3, -8), board.offset(8, 3, 8))) {
            if (level().getBlockEntity(pos) instanceof Container) return pos.immutable();
        }
        return null;
    }

    private int depotCount(@Nullable BlockPos depot, java.util.function.Predicate<ItemStack> pred) {
        if (depot == null || !(level().getBlockEntity(depot) instanceof Container c)) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        }
        return n;
    }

    /** The foreman (slot 0) posts the depot's material shortfalls to the town
     *  needs board, so idle members whose trade fits go fetch them. */
    private void postDepotDeficits(BlockPos board) {
        BlockPos depot = findDepotNear(board);
        if (depot == null) return;
        int wood = depotCount(depot, s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS));
        int stone = depotCount(depot, s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE));
        int iron = depotCount(depot, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
        int coal = depotCount(depot, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        if (wood < 64) Town.postNeed(ownerId, GatherGoal.Kind.LOGS, 64 - wood);
        if (stone < 64) Town.postNeed(ownerId, GatherGoal.Kind.STONE, 64 - stone);
        if (iron < 16) Town.postNeed(ownerId, GatherGoal.Kind.IRON, 16 - iron);
        if (coal < 16) Town.postNeed(ownerId, GatherGoal.Kind.COAL, 16 - coal);
    }

    /** Can I fetch this material given my town role and my pickaxe tier? */
    private boolean canDoAsRole(GatherGoal.Kind kind) {
        boolean toolOk = switch (kind) {
            case LOGS, DIRT, SAND, GRAVEL, SUGAR_CANE -> true;
            case COAL, STONE -> bestPickTier() >= 1;
            case IRON -> bestPickTier() >= 2;
        };
        if (!toolOk) return false;
        return switch (role) {
            case MINER -> kind == GatherGoal.Kind.STONE || kind == GatherGoal.Kind.IRON || kind == GatherGoal.Kind.COAL;
            case LUMBERJACK -> kind == GatherGoal.Kind.LOGS;
            case BUILDER -> kind == GatherGoal.Kind.LOGS || kind == GatherGoal.Kind.STONE;
            case FARMER, NONE -> false; // farmers tend the larder, not the mines
        };
    }

    /** Fulfill the town's most-wanted material my trade can supply — mining iron
     *  for the group, chopping wood, etc. — and haul it to the depot. */
    private boolean decideTownWork() {
        if (ownerId == null) return false;
        GatherGoal.Kind kind = Town.claimNeed(ownerId, this::canDoAsRole, 16);
        if (kind == null) return false;
        if (kind == GatherGoal.Kind.IRON) {
            say("Town needs iron — mining a batch for the depot.");
            enqueue(Job.mine(40)); // collects raw iron on the way down
        } else {
            say("Town needs " + kind.label + " — fetching a batch for the depot.");
            enqueue(Job.gather(kind, 16));
        }
        // Deliver to the town depot specifically, not just whatever chest is
        // nearest when the gather ends — otherwise the depot never fills and the
        // foreman re-posts the same need forever.
        BlockPos board = Town.center(ownerId);
        BlockPos depot = board != null ? findDepotNear(board) : null;
        enqueue(depot != null ? Job.depositAt(depot) : Job.deposit());
        return true;
    }

    /** A blocked member posts what it lacks to the town board (e.g. a crafter
     *  short on iron), so an idle miner goes and gets that specific thing. */
    public void postMaterialNeed(String label) {
        if (ownerId == null || Town.center(ownerId) == null || label == null) return;
        String l = label.toLowerCase();
        GatherGoal.Kind kind =
            l.contains("iron") ? GatherGoal.Kind.IRON
          : l.contains("coal") ? GatherGoal.Kind.COAL
          : (l.contains("cobble") || l.contains("stone")) ? GatherGoal.Kind.STONE
          : (l.contains("log") || l.contains("wood") || l.contains("plank")) ? GatherGoal.Kind.LOGS
          : null;
        if (kind != null) {
            Town.postNeed(ownerId, kind, 16);
            say("Posting to the town board — we need " + kind.label + "; someone grab it.");
        }
    }

    // ------------------------------ food & health ----------------------------

    /** Pick the smartest food to heal with: reserve golden apples for the
     *  critical-HP emergency, avoid the risky stuff (rotten flesh, spider eye,
     *  raw chicken) unless it's all we've got, and eat the LEAST-filling safe
     *  food first so a prime steak isn't wasted on a small heal. */
    private int findFoodSlot() {
        int best = -1, bestNutrition = Integer.MAX_VALUE;
        int riskyFallback = -1;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.get(i);
            if (s.isEmpty()) continue;
            FoodProperties fp = s.get(DataComponents.FOOD);
            if (fp == null) continue;
            if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue; // saved for emergencies
            if (isRiskyFood(s)) { if (riskyFallback < 0) riskyFallback = i; continue; }
            if (fp.nutrition() < bestNutrition) { bestNutrition = fp.nutrition(); best = i; }
        }
        return best >= 0 ? best : riskyFallback;
    }

    private static boolean isRiskyFood(ItemStack s) {
        return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE)
            || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
            || s.is(Items.CHICKEN); // raw chicken can give food poisoning
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

    /** Push a job in FRONT of everything — used for interjections like
     *  "pack's full, deposit first, then keep going". */
    public void enqueueFront(Job job) {
        jobs.addFirst(job);
    }

    /** No empty slot left (stacks may have room, but it's time to stash). */
    public boolean isPackFull() {
        for (ItemStack s : inventory) {
            if (s.isEmpty()) return false;
        }
        return true;
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
        if (productive) {
            idleBackoffUntil = 0; // good outcome — resume initiative right away
        } else {
            idleBackoffUntil = tickCount + 800; // ~40s cool-off on a dry attempt
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

    // ------------------------------- routines --------------------------------

    public List<Routine> routines() {
        return routines;
    }

    /** Schedule (or re-schedule) a recurring chore. Interval is clamped to a
     *  sane band; the first run happens one interval from now. */
    public void addRoutine(RoutineKind kind, int intervalTicks) {
        int interval = Math.max(1200, Math.min(72000, intervalTicks)); // 1 min .. 1 hour
        routines.removeIf(r -> r.kind == kind);
        routines.add(new Routine(kind, interval, tickCount + interval));
    }

    public int clearRoutines() {
        int n = routines.size();
        routines.clear();
        return n;
    }

    /** When idle, fire at most one due chore, then reset its clock. Keeping it to
     *  one per window stops a backlog of chores from stampeding the queue. */
    private void tickRoutines() {
        for (Routine r : routines) {
            if (tickCount >= r.nextTick) {
                enqueue(r.kind.toJob());
                say("Routine: time to " + r.kind.label + ".");
                r.nextTick = tickCount + r.interval;
                return;
            }
        }
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

    /** Put down a carried chest on solid ground beside us — the base depot. */
    private boolean placeChestNearby() {
        if (countMatching(s -> s.is(Items.CHEST)) == 0) return false;
        BlockPos feet = feetPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(d);
            if (level().getBlockState(p).canBeReplaced()
                && level().getBlockState(p.below()).isSolid()
                && removeMatching(s -> s.is(Items.CHEST), 1) >= 1) {
                level().setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
                return true;
            }
        }
        return false;
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

    /** Nearest chest we REMEMBER (no world scan — safe at long range). */
    @Nullable
    public BlockPos nearestRememberedChest(int maxRadius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Long key : chestMemory.keySet()) {
            BlockPos pos = BlockPos.of(key);
            double d = pos.distSqr(blockPosition());
            if (d > (double) maxRadius * maxRadius || d >= bestDist) continue;
            if (level().getBlockEntity(pos) instanceof Container) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
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
        tag.putBoolean("NightHome", nightHome);
        tag.putInt("BaseStage", baseStage);
        if (stationPos != null) tag.putLong("StationPos", stationPos.asLong());
        tag.putString("StationTask", stationTask.name());
        if (mobileLoadCenter != null) tag.putLong("MobileLoad", mobileLoadCenter.asLong());
        if (workZone != null) tag.put("WorkZone", workZone.save());
        tag.putInt("Xp", xp);
        tag.putInt("LifeXp", lifetimeXp);
        tag.putBoolean("ExpActive", expeditionActive);
        if (expeditionActive) {
            tag.putInt("ExpPhase", expeditionPhase);
            tag.putString("ExpTarget", expeditionTarget);
            tag.putInt("ExpRemaining", expeditionRemaining);
            if (expeditionReturn != null) tag.putLong("ExpReturn", expeditionReturn.asLong());
        }
        ListTag orders = new ListTag();
        for (StandingOrder o : standingOrders) {
            CompoundTag ot = new CompoundTag();
            ot.putString("Kind", o.kind().name());
            ot.putInt("Amount", o.amount());
            orders.add(ot);
        }
        tag.put("Standing", orders);
        ListTag chores = new ListTag();
        for (Routine r : routines) {
            CompoundTag rt = new CompoundTag();
            rt.putString("Kind", r.kind.name());
            rt.putInt("Interval", r.interval);
            chores.add(rt);
        }
        tag.put("Routines", chores);
        ListTag points = new ListTag();
        for (Map.Entry<String, BlockPos> e : waypoints.entrySet()) {
            CompoundTag wt = new CompoundTag();
            wt.putString("Name", e.getKey());
            wt.putLong("Pos", e.getValue().asLong());
            points.add(wt);
        }
        tag.put("Waypoints", points);
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
        nightHome = tag.getBoolean("NightHome");
        baseStage = tag.getInt("BaseStage");
        stationPos = tag.contains("StationPos") ? BlockPos.of(tag.getLong("StationPos")) : null;
        try {
            stationTask = StationTask.valueOf(tag.getString("StationTask"));
        } catch (IllegalArgumentException ignored) {
            stationTask = StationTask.NONE;
        }
        if (stationPos == null) stationTask = StationTask.NONE;
        // The traveling window's tickets persist with the world; restoring the
        // center lets the mover tick free them as usual instead of leaking.
        mobileLoadCenter = tag.contains("MobileLoad") ? BlockPos.of(tag.getLong("MobileLoad")) : null;
        workZone = tag.contains("WorkZone") ? WorkZone.load(tag.getCompound("WorkZone")) : null;
        publishJobState(); // the real requirement scan happens on the first work tick
        xp = tag.getInt("Xp");
        // Seed lifetime xp from banked xp for bots saved before levels existed.
        lifetimeXp = tag.contains("LifeXp") ? tag.getInt("LifeXp") : xp;
        applyLevelPerks();
        expeditionActive = tag.getBoolean("ExpActive");
        if (expeditionActive) {
            expeditionPhase = tag.getInt("ExpPhase");
            expeditionTarget = tag.contains("ExpTarget") ? tag.getString("ExpTarget") : "glowstone";
            expeditionRemaining = tag.getInt("ExpRemaining");
            expeditionReturn = tag.contains("ExpReturn") ? BlockPos.of(tag.getLong("ExpReturn")) : null;
        }
        standingOrders.clear();
        for (Tag t : tag.getList("Standing", Tag.TAG_COMPOUND)) {
            CompoundTag ot = (CompoundTag) t;
            try {
                standingOrders.add(new StandingOrder(
                    GatherGoal.Kind.valueOf(ot.getString("Kind")), ot.getInt("Amount")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        routines.clear();
        for (Tag t : tag.getList("Routines", Tag.TAG_COMPOUND)) {
            CompoundTag rt = (CompoundTag) t;
            try {
                RoutineKind kind = RoutineKind.valueOf(rt.getString("Kind"));
                int interval = Math.max(1200, Math.min(72000, rt.getInt("Interval")));
                // Age resets on load, so seed the next run one interval out.
                routines.add(new Routine(kind, interval, interval));
            } catch (IllegalArgumentException ignored) {
            }
        }
        waypoints.clear();
        for (Tag t : tag.getList("Waypoints", Tag.TAG_COMPOUND)) {
            CompoundTag wt = (CompoundTag) t;
            waypoints.put(wt.getString("Name"), BlockPos.of(wt.getLong("Pos")));
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

        // Active unstuck: once a second, if we WANT to move but barely have, hop
        // and force a fresh path. Clears the corners, gaps, and 1-block lips the
        // pathfinder alone gets wedged on. "Want to move" also covers the case
        // where the pathfinder gave up against a wall while following — otherwise
        // the stuck timer would reset and self-rescue (dig/climb) would never fire.
        if (tickCount % 20 == 0) {
            boolean wantsToMove = !this.getNavigation().isDone();
            if (!wantsToMove && mode == Mode.FOLLOW) {
                Player o = getOwnerPlayer();
                if (o != null && distanceToSqr(o) > 9.0) wantsToMove = true; // trailing, but pinned
            }
            if (wantsToMove && this.position().distanceToSqr(lastPathPos) < 0.25) { // <0.5 block in ~1s
                this.stuckStreak++;
                this.getJumpControl().jump();
                this.getNavigation().recomputePath();
            } else {
                this.stuckStreak = 0;
            }
            this.lastPathPos = this.position();
        }

        // Fireproof reflex: on fire and not already wet -> sprint to water.
        if (this.isOnFire() && !this.isInWaterOrBubble() && tickCount % 10 == 0) {
            BlockPos water = findNearestWater(6);
            if (water != null) {
                this.getNavigation().moveTo(water.getX() + 0.5, water.getY(), water.getZ() + 0.5, 1.6D);
            }
        }

        // Item magnet: pick up loose drops we walk near — mob-kill loot (bone,
        // string, gunpowder, arrows), stray resources — so nothing earned is
        // wasted. Only when working on its own or guarding, so a following bot
        // doesn't hoover up the player's dropped items.
        if ((autonomous || mode == Mode.GUARD) && tickCount % 6 == 0 && !isPackFull()) {
            for (net.minecraft.world.entity.item.ItemEntity drop
                    : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        getBoundingBox().inflate(1.4))) {
                if (!drop.isAlive() || drop.hasPickUpDelay()) continue;
                ItemStack left = insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard(); else drop.setItem(left);
            }
        }

        // Proactive lighting: standing somewhere dark with torches on hand and
        // none already nearby -> drop one to keep mobs from spawning around it.
        if (tickCount % 60 == 0 && this.onGround() && getTarget() == null
            && level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, blockPosition()) < 7
            && countMatching(s -> s.is(Items.TORCH)) > 0 && noTorchNear(5)) {
            placeTorchNearby();
        }

        // A hauler's delivery run crosses terrain its post window doesn't cover —
        // carry a small ticking window with it so unattended trips never stall in
        // an unloaded chunk. Moves when it crosses a chunk border; freed on
        // stand-down/death; heals any overlap with the fixed post window.
        if (stationTask == StationTask.HAUL && tickCount % 40 == 0
            && level() instanceof net.minecraft.server.level.ServerLevel haulLevel) {
            BlockPos here = blockPosition();
            if (mobileLoadCenter == null
                || (mobileLoadCenter.getX() >> 4) != (here.getX() >> 4)
                || (mobileLoadCenter.getZ() >> 4) != (here.getZ() >> 4)) {
                BlockPos old = mobileLoadCenter;
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), here, 1, true);
                if (old != null) {
                    com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), old, 1, false);
                    // Re-force windows the removal may have clipped (set semantics).
                    com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), here, 1, true);
                    if (stationPos != null) {
                        com.jrpetty.mcassistant.ChunkLoad.setLoaded(haulLevel, getUUID(), stationPos,
                            stationChunkRadius(stationTask), true);
                    }
                }
                mobileLoadCenter = here.immutable();
            }
        }

        // One line a day, at dawn, on what this specialist actually produced —
        // otherwise a night of chunk-loaded work leaves no trace at all.
        if (stationTask != StationTask.NONE && tickCount % 200 == 0) {
            dailyProductionReport();
        }

        // Keep the screen and the nametag honest. Re-check the checklist often
        // while someone is stood next to this bot — that's exactly when they're
        // handing over the tool it just asked for and watching for the message to
        // clear — and occasionally regardless, so the ⚒/⚠ glyph stays truthful.
        // (Unassigned bots cost nothing here: the check returns immediately.)
        if (tickCount % 40 == 0
            && (tickCount % 200 == 0 || level().getNearestPlayer(this, 12.0) != null)) {
            refreshJobState();
        }

        // Show me my patch: while the owner is holding a Zone Marker nearby, this
        // bot outlines its own work zone in particles, so zones are something you
        // can SEE and compare rather than guess at.
        if (workZone != null && tickCount % 10 == 0
            && level() instanceof net.minecraft.server.level.ServerLevel zl) {
            Player owner = getOwnerPlayer();
            if (owner != null && owner.distanceToSqr(this) < 48 * 48
                && (owner.getMainHandItem().is(com.jrpetty.mcassistant.McAssistantMod.ZONE_MARKER.get())
                    || owner.getOffhandItem().is(com.jrpetty.mcassistant.McAssistantMod.ZONE_MARKER.get()))) {
                com.jrpetty.mcassistant.item.ZoneMarkerItem.outline(zl, workZone,
                    net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER);
            }
        }

        // Top up an empty bucket at any water we pass, so an irrigation source is
        // always on hand for a farm (FarmGoal spends it to hydrate a plot).
        if (autonomous && tickCount % 20 == 0 && getTarget() == null
            && countCarried(s -> s.is(Items.WATER_BUCKET)) == 0) {
            int slot = firstSlot(s -> s.is(Items.BUCKET));
            if (slot >= 0 && waterNearby(2)) {
                inventory.set(slot, new ItemStack(Items.WATER_BUCKET));
                say("Filled my bucket at the water — ready to irrigate.");
            }
        }

        // Queued mode switches apply the instant they reach the head.
        // (GO_HOME and GOTO are real walking jobs now — TravelGoal runs them
        // to completion so "go to the mine THEN gather iron" works in order.)
        Job head = peekJob();
        if (head != null && head.type() == Job.Type.MODE && head.mode() != null) {
            pollJob();
            setAutonomous(false); // an explicit follow/stay/guard step re-attaches it
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

        // Combat loadout: right weapon for the fight (bow for creepers and
        // distant targets, best melee otherwise).
        if (getTarget() != null && tickCount % 20 == 0) {
            combatTick();
        }

        // Wear the best armor we're carrying (like tools, but for the body).
        if (tickCount % 80 == 0) {
            autoEquipArmor();
        }

        // Companion awareness: warn the owner about danger they might not see.
        if (tickCount % 40 == 0) {
            dangerCallouts();
        }

        // Proactive night defense: well-armored, healthy and armed, an autonomous
        // bot out at night seeks nearby hostiles instead of only reacting — it
        // clears its ground and banks the drops (string, bone, gunpowder) + XP.
        // Creepers are left to the ranged/dodge machinery, not chased into melee.
        if (autonomous && mode != Mode.STAY && !retreating && getTarget() == null
            && this.level().isNight() && tickCount % 40 == 0
            && getArmorValue() >= 15 && getHealth() > getMaxHealth() * 0.75F
            && weaponScore(getMainHandItem()) > 0 && !shouldDisengage()) {
            Monster m = nearestMonster(18.0);
            if (m != null && !(m instanceof Creeper)) {
                setTarget(m);
            }
        }

        // Light the worksite: working in the dark invites mobs onto our head.
        if (tickCount % 60 == 0 && !jobs.isEmpty()) {
            torchIfDark();
        }

        // Off-hand management: totem of undying first (a real second life for
        // mobs too), shield otherwise.
        if (tickCount % 40 == 0) {
            manageOffhand();
        }

        // Night routine: idle workers head home at dusk when asked to.
        if (tickCount % 100 == 0) {
            nightRoutine();
        }

        // Quartermaster: a companion looks after its player. If the owner is
        // close and running on empty while we're carrying spare food, hand some
        // over before doing anything else.
        if (distressCd > 0) distressCd--;
        if (quartermasterCd > 0) quartermasterCd--;
        if (tickCount % 40 == 0 && quartermasterCd == 0 && !retreating && getTarget() == null) {
            Player owner = getOwnerPlayer();
            if (owner instanceof ServerPlayer sp && distanceToSqr(owner) < 256.0
                && sp.getFoodData().getFoodLevel() <= 6 && countFood() >= 8) {
                enqueueFront(Job.give("food", 4));
                say("You're running on empty — here, take some food.");
                quartermasterCd = 600; // ~30s before offering again
            }
        }

        // Emergency: at critical HP scarf a golden apple even mid-fight (regen +
        // absorption), and drink milk to shake off poison/wither.
        if (isAlive() && eatCooldown == 0) {
            if (getHealth() < getMaxHealth() * 0.25F) {
                int g = firstSlot(s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
                if (g >= 0) {
                    ItemStack rest = this.eat(this.level(), inventory.get(g));
                    inventory.set(g, rest.isEmpty() ? ItemStack.EMPTY : rest);
                    eatCooldown = 40;
                    say("Critical — eating a golden apple!");
                }
            }
            if (hasHarmfulEffect()) {
                int m = firstSlot(s -> s.is(Items.MILK_BUCKET));
                if (m >= 0) {
                    removeAllEffects();
                    inventory.set(m, new ItemStack(Items.BUCKET));
                    eatCooldown = 20;
                }
            }
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

        // Scheduled chores: when idle, run any routine that's come due ("tend
        // the farm every 20 minutes"). Works whether or not autonomy is on —
        // it's an explicit standing instruction — but never interrupts a job.
        if (!routines.isEmpty() && jobs.isEmpty() && !retreating
            && mode != Mode.STAY && tickCount % 40 == 0) {
            tickRoutines();
        }

        // Town coordination: if the crew has a Job Board, self-assign a role.
        if (autonomous && ownerId != null && tickCount % 400 == 0) {
            decideTownRole();
        }

        // Idle initiative: with autonomy on and nothing to do, look after
        // survival first (the 'decide' rung). Crew members on an active town
        // then do their assigned ROLE (their town duty) before any personal
        // advancement; solo bots advance themselves, then do role work.
        boolean restingAtHome = nightHome && this.level().isNight()
            && (parkedForNight || (homePos != null && homePos.distSqr(blockPosition()) < 24 * 24));
        // Autonomy is self-direction: run the idle brain regardless of FOLLOW/STAY/
        // GUARD (an explicit mode command turns autonomy off and re-attaches it).
        if (autonomous && jobs.isEmpty() && !retreating && getTarget() == null
            && (idleKick || tickCount % 200 == 0)
            && !restingAtHome) {
            idleKick = false;
            boolean townMember = ownerId != null && Town.center(ownerId) != null;
            // Survival ALWAYS runs — never held off by the idle-work cool-off; a
            // hungry / toolless / full-pack bot must be free to act. Only the
            // discretionary tiers (progress / role / town) respect the backoff.
            // A stationed specialist works its post and NOTHING else. Crucially
            // it skips the survival brain: those rungs would send a farmer off
            // hunting for a food stockpile, a fisher off chopping wood, or any
            // of them off crafting a pickaxe they don't need — abandoning the
            // zone the player fenced off. A specialist's kit and upkeep are the
            // player's job, which is exactly what the checklist is for; it still
            // eats to heal, stashes when full, and defends itself.
            if (stationTask != StationTask.NONE && stationPos != null) {
                if (tickCount >= idleBackoffUntil) decideStation();
            } else if (!decideSurvival()) {
                {
                    if (tickCount >= idleBackoffUntil) {
                        if (townMember && role != Role.NONE) {
                            // Town duty first; if the board has no need and no role work,
                            // still advance ourselves rather than idling.
                            if (tickCount % 400 == 0 && !decideTownWork() && !decideProgress()) {
                                enqueueRoleWork();
                            }
                        } else if (!decideProgress() && tickCount % 400 == 0) {
                            enqueueRoleWork(); // role==NONE now keeps busy too (no more statue)
                        }
                    }
                    // Area tapped out and nothing queued? Relocate to fresh terrain and
                    // find more — even during the post-dry cool-off, since exploring is
                    // exactly the answer to a dry area. Solo bots only (town crew stay put).
                    if (peekJob() == null && !townMember) decideExplore();
                }
            }
        }

        // Live nametag: a job glyph you can read across the base — ⚠ means that
        // one is stuck and wants something — then the veteran star, name, hearts.
        int hp = Mth.ceil(getHealth());
        char glyph = stationTask == StationTask.NONE ? ' '
            : (!missingEssentials.isEmpty() || upkeepStalled) ? '⚠' : '⚒';
        if (hp != lastShownHealth || veteranLevel() != lastShownLevel || glyph != lastShownGlyph) {
            lastShownHealth = hp;
            lastShownLevel = veteranLevel();
            lastShownGlyph = glyph;
            int max = (int) getMaxHealth();
            ChatFormatting color = hp > max * 0.6 ? ChatFormatting.GREEN
                : hp > max * 0.3 ? ChatFormatting.YELLOW
                : ChatFormatting.RED;
            var name = Component.literal("");
            if (glyph != ' ') {
                name.append(Component.literal(glyph + " ").withStyle(
                    glyph == '⚠' ? ChatFormatting.RED : ChatFormatting.AQUA));
            }
            if (lastShownLevel >= 1) {
                name.append(Component.literal("✦" + lastShownLevel + " ").withStyle(ChatFormatting.GOLD));
            }
            name.append(Component.literal(displayNameCap() + " "))
                .append(Component.literal(hp + "/" + max + "❤").withStyle(color));
            setCustomName(name);
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
            case IRON -> s -> s.is(net.minecraft.world.item.Items.RAW_IRON)
                || s.is(net.minecraft.world.item.Items.IRON_INGOT);
            case COAL -> s -> s.is(net.minecraft.world.item.Items.COAL);
            case SAND -> s -> s.is(net.minecraft.world.item.Items.SAND)
                || s.is(net.minecraft.world.item.Items.RED_SAND);
            case GRAVEL -> s -> s.is(net.minecraft.world.item.Items.GRAVEL);
            case SUGAR_CANE -> s -> s.is(net.minecraft.world.item.Items.SUGAR_CANE);
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
            default -> {
                // No role, but autonomous, idle, and fully self-sufficient: keep
                // busy stockpiling instead of standing still like a statue.
                say("Caught up — stockpiling a bit. (Say \"take a break\" to stop.)");
                enqueue(Job.gather(GatherGoal.Kind.LOGS, 12));
                if (bestPickTier() >= 1) enqueue(Job.gather(GatherGoal.Kind.STONE, 12));
                enqueue(Job.deposit());
            }
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
        // Death/dismissal frees the station's force-loaded chunks (fixed post
        // window AND the hauler's traveling window). A plain chunk unload must
        // NOT — staying loaded while parked is the whole point.
        if (reason.shouldDestroy() && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            if (mobileLoadCenter != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), mobileLoadCenter, 1, false);
                mobileLoadCenter = null;
            }
            if (stationPos != null) {
                com.jrpetty.mcassistant.ChunkLoad.setLoaded(sl, getUUID(), stationPos,
                    stationChunkRadius(stationTask), false);
            }
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
        // A held shield soaks half of frontal hits (and wears down doing it).
        if (!fromOwner && source.getEntity() != null && amount > 0
            && getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)) {
            net.minecraft.world.phys.Vec3 toAttacker =
                source.getEntity().position().subtract(this.position());
            if (toAttacker.lengthSqr() > 0.001
                && toAttacker.normalize().dot(this.getViewVector(1.0F)) > 0.0) {
                amount *= 0.5F;
                getItemBySlot(EquipmentSlot.OFFHAND).hurtAndBreak(1, this, EquipmentSlot.OFFHAND);
            }
        }
        boolean took = super.hurt(source, fromOwner ? amount * 0.5F : amount);
        if (took) this.lastDamageTick = this.tickCount;
        // Distress call: taking real damage from a hostile while actually in
        // trouble rallies nearby crewmates to come fight it off.
        if (took && !this.level().isClientSide && distressCd == 0
            && source.getEntity() instanceof Monster foe && foe.isAlive()
            && (getHealth() < getMaxHealth() * 0.6F || threatCount(8.0) >= 2)) {
            callForHelp(foe);
            distressCd = 100; // ~5s between shouts
        }
        return took;
    }

    /** Rally the crew: idle, able crewmates within earshot drop what they're
     *  doing and come defend against the attacker. Teamwork under fire. */
    private void callForHelp(Monster attacker) {
        if (ownerId == null) return;
        int rallied = 0;
        for (AssistantEntity mate : allFor(ownerId)) {
            if (mate == this || !mate.isAlive()) continue;
            if (mate.distanceToSqr(this) > 32.0 * 32.0) continue; // out of earshot
            if (mate.respondToDistress(this, attacker)) rallied++;
        }
        if (rallied > 0) say("Under attack — " + rallied + " coming to help!");
    }

    /** Answer a crewmate's distress call. Returns true if we actually turned to
     *  help (idle, healthy enough, not on hold, not already fighting). */
    public boolean respondToDistress(AssistantEntity ally, Monster attacker) {
        if (retreating || mode == Mode.STAY) return false;
        if (getTarget() != null || attacker == null || !attacker.isAlive()) return false;
        if (getHealth() < getMaxHealth() * 0.35F) return false; // too hurt to spare
        equipBestWeapon();
        setTarget(attacker);
        this.getNavigation().moveTo(attacker, 1.3D);
        say("On my way — hold on, " + ally.displayNameCap() + "!");
        return true;
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
        // The bot's SELF survives as a Memory Core: right-click the ground with
        // it and this exact companion comes back — name, level, role, waypoints,
        // and station. Its gear stays here where it fell.
        ItemStack core = new ItemStack(com.jrpetty.mcassistant.McAssistantMod.MEMORY_CORE.get());
        core.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.of(writeMemoryCore()));
        core.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal("Memory Core — " + displayNameCap()));
        var drop = new net.minecraft.world.entity.item.ItemEntity(
            level, getX(), getY() + 0.5, getZ(), core);
        drop.setExtendedLifetime(); // don't let the bot's self despawn in 5 minutes
        level.addFreshEntity(drop);
        say("...my Memory Core is where I fell (" + blockPosition().getX() + " "
            + blockPosition().getY() + " " + blockPosition().getZ() + ") — bring me back.");
    }

    /** Everything that makes this bot ITSELF (not its gear), for the Memory Core. */
    private CompoundTag writeMemoryCore() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", assistantName);
        tag.putString("Role", role.name());
        tag.putInt("Xp", xp);
        tag.putInt("LifeXp", lifetimeXp);
        tag.putBoolean("NightHome", nightHome);
        tag.putBoolean("Auto", autonomous);
        if (homePos != null) tag.putLong("Home", homePos.asLong());
        if (stationPos != null && stationTask != StationTask.NONE) {
            tag.putLong("StationPos", stationPos.asLong());
            tag.putString("StationTask", stationTask.name());
        }
        if (workZone != null) tag.put("WorkZone", workZone.save());
        ListTag points = new ListTag();
        for (Map.Entry<String, BlockPos> e : waypoints.entrySet()) {
            CompoundTag wt = new CompoundTag();
            wt.putString("Name", e.getKey());
            wt.putLong("Pos", e.getValue().asLong());
            points.add(wt);
        }
        tag.put("Waypoints", points);
        return tag;
    }

    /** Revival: restore the identity a Memory Core carries — the same bot,
     *  minus the gear that died with it. */
    public void applyMemoryCore(CompoundTag tag) {
        rename(tag.getString("Name"));
        try {
            role = Role.valueOf(tag.getString("Role"));
        } catch (IllegalArgumentException ignored) {
            role = Role.NONE;
        }
        xp = tag.getInt("Xp");
        lifetimeXp = tag.getInt("LifeXp");
        nightHome = tag.getBoolean("NightHome");
        if (tag.contains("Home")) setHome(BlockPos.of(tag.getLong("Home")));
        waypoints.clear();
        for (Tag t : tag.getList("Waypoints", Tag.TAG_COMPOUND)) {
            CompoundTag wt = (CompoundTag) t;
            waypoints.put(wt.getString("Name"), BlockPos.of(wt.getLong("Pos")));
        }
        applyLevelPerks();
        setHealth(getMaxHealth());
        setAutonomous(tag.getBoolean("Auto"));
        workZone = tag.contains("WorkZone") ? WorkZone.load(tag.getCompound("WorkZone")) : null;
        if (tag.contains("StationPos")) {
            StationTask st = StationTask.NONE;
            try {
                st = StationTask.valueOf(tag.getString("StationTask"));
            } catch (IllegalArgumentException ignored) { }
            if (st != StationTask.NONE) setStation(BlockPos.of(tag.getLong("StationPos")), st);
        }
        lastShownHealth = -1; // refresh the nametag (level star and all)
        refreshJobState();
        say("Back from the void — level " + veteranLevel() + ", and I remember everything. "
            + "My old gear died with me, though"
            + (stationTask != StationTask.NONE ? " — heading back to my station." : "."));
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public BlockPos feetPos() {
        return this.blockPosition();
    }
}
