package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Village Folk — the same hands as an assistant, with nobody telling them what
 * to do.
 *
 * <p>An assistant waits to be hired, pointed at ground and given a trade. Folk
 * do all three for themselves: they find the settlement they belong to (or
 * found one), take up whichever trade their village is short of, walk out and
 * claim ground suited to that trade, and then work it. Nothing here needs a
 * player, a wand, or a menu.
 *
 * <p>Everything BELOW that decision — how to farm, how to sink a shaft, how to
 * feed a furnace bank, the trade ladders, the claim book that stops two hands
 * reaching for the same wheat — is the assistant's, inherited whole. This class
 * is only the part that decides what to want and when to want it.
 *
 * <p>They are deliberately unhurried. The agenda is consulted once every five
 * seconds and does exactly one thing; the settlement puts up one building every
 * eight minutes at most. A village should look like it grew, not like it was
 * printed.
 */
public class VillageFolkEntity extends AssistantEntity {

    @Nullable private BlockPos villageCentre;
    private int agendaTick = -1000;
    private int searchFailTick = -100000;

    public VillageFolkEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AssistantEntity.createAttributes();
    }

    /**
     * Folk never speak. Not a word, ever — not a greeting, not a complaint,
     * not the day's tally, not "my pack is full". A settlement is meant to be
     * something you come across and watch, and ten of them narrating their
     * every decision would fill the chat with noise nobody asked for. What
     * they are doing is legible from what they are DOING.
     */
    @Override
    protected boolean speaksInChat() { return false; }

    @Override
    public boolean isSettler() { return true; }

    @Override
    protected boolean selfDirected() { return false; }

    /** Nobody hired them, so nobody owes them a wage or a charge. They eat
     *  like anyone else — a village that cannot feed itself has failed at the
     *  one thing a village is for — but a settlement does not run on redstone
     *  and does not answer to a payroll. */
    @Override
    public boolean needsCharge() { return false; }

    /** Look all you like — you just cannot give them orders. */
    @Override
    public boolean openToAnyone() { return true; }

    /** A village's folk keep everything in the village's stores, once it has any. */
    @Override
    public boolean usesVillageStores() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && ownerId() != null
            && Villages.hasStores(server, ownerId());
    }

    @Override
    protected boolean drawsWages() { return false; }

    // [mine-safety] Never carried off in a boat or a cart it did not mean to board (Aboard).
    @Override
    protected boolean canRide(net.minecraft.world.entity.Entity vehicle) {
        return Aboard.mayBoard(this, vehicle) && super.canRide(vehicle);
    }

    // [mine-safety] Below ground in the town's mine it climbs out, by the stairs or steps of its own;
    // it is never lifted out (MineSafety.climbInstead).
    @Override
    protected boolean rescueToPlot() {
        return MineSafety.climbInstead(this) || super.rescueToPlot();
    }

    @Override
    public boolean putBeside(BlockPos target) {
        return MineSafety.climbInstead(this) || super.putBeside(target);
    }

    /** A villager is not a hired hand on a clock: it eats a ration every five and a
     *  half minutes of work where an assistant eats one every two and a half (it was four
     *  and a half, and a third less now the town keeps two meals a day, not three).
     *  Nineteen mouths on a young village's first fields were eating more than five
     *  farmers grew, and by the third day half of them stood at the heart with no rations. */
    @Override
    public int traitUpkeepPercent() {
        // And the town's Granaries (CityTree) make the larder go a tenth further: meals a little further apart.
        return super.traitUpkeepPercent() * 9 / 4 * CityTree.mealPercent(ownerId()) / 100;
    }

    /**
     * When the last of a settlement dies, its chunks stop being held open.
     * The force-load ticket is taken under the VILLAGE's id, and no entity's
     * own clean-up could ever release it — so a dead village would have kept
     * eighty-one chunks ticking for the life of the world, and the id needed
     * to free them died with the last folk.
     */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        UUID village = ownerId();
        BlockPos centre = villageCentre;
        super.remove(reason);
        if (!reason.shouldDestroy() || village == null || centre == null) return;
        Villages.recordDeath(village);      // unloading is not dying
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        // Somebody still lives here — and "here" means the roll, not the room.
        // The last LOADED folk dying while ninety-nine are asleep in unloaded
        // chunks would otherwise have wiped the settlement's age, its
        // buildings and its roll, and dropped the chunks it keeps awake.
        if (!Villages.folkOf(village).isEmpty()) return;
        if (Villages.recordedPopulation(village) > 0) return;
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(
            server, village, centre,
            com.jrpetty.mcassistant.VillageSpawner.MAX_LOADED_RADIUS, false);
        Villages.forget(village);
    }

    /**
     * Belong to this settlement, now, without going to look for one. Used for
     * anyone who did not arrive by walking up: a child born on its parents'
     * plot, a villager the mod has just taken over. Both used to be left to
     * find the nearest village within ninety-six blocks, and both are routinely
     * further than that from the heart of a big one — so they founded a rival
     * settlement on top of the one they were already standing in.
     */
    public void joinVillage(UUID village, BlockPos centre) {
        // [guard-kit] A guard going to live in another town leaves the watch's kit in its old town's stores.
        if (ownerId() != null && !ownerId().equals(village) && stationTask() == StationTask.GUARD) WatchKit.handBack(this, "leaving the town");
        if (ownerId() != null && !ownerId().equals(village) && stationTask() == StationTask.CAVE) CaveDwellers.handBack(this, "leaving the town");   // [caves]
        this.villageCentre = centre;
        adoptVillage(village);
        setHome(centre);
        setAutonomous(true);
    }

    /** Only a building that actually went up counts as built. */
    @Override
    public void noteBuilt(String structure) {
        UUID village = ownerId();
        if (village != null) Assemblies.builder(village, structure, displayNameCap());
        if (village != null) Villages.noteProject(village, structure, level().getGameTime());
        drewForBuild = false;                     // what is left over is cargo again
        // ...and goes back to the storehouse, not to the builder's own field seventy blocks out.
        if (stashable() > 0) enqueue(storesDeposit());
    }

    /**
     * A builder stocking a building keeps what it has drawn and made for it. The fences
     * for a well were crafted, banked in the next deposit — in a farm chest seventy
     * blocks out, beyond the builder's reach — and crafted again, every few minutes for
     * a whole game day on a plains map, and the furnace for the house after them: the
     * village built three things in three days.
     */
    @Override
    protected int buildReserve(net.minecraft.world.item.ItemStack s) {
        if (!drewForBuild) return 0;
        if (BuildGoal.isBuildingBlock(s) || s.is(net.minecraft.world.item.Items.CHEST)
                || s.is(net.minecraft.world.item.Items.FURNACE) || s.is(net.minecraft.world.item.Items.CRAFTING_TABLE)
                || s.is(net.minecraft.world.item.Items.LADDER) || s.is(net.minecraft.world.item.Items.OBSIDIAN)
                || s.is(net.minecraft.world.item.Items.GLASS) || s.is(net.minecraft.world.item.Items.TORCH)
                || s.is(net.minecraft.tags.ItemTags.FENCES) || s.is(net.minecraft.tags.ItemTags.FENCE_GATES)
                || s.is(net.minecraft.tags.ItemTags.BEDS)
                // and the finishing it cut for the building: roof stairs and slabs, doors,
                // panes, lanterns, barrels, hay, rugs, flowers
                || s.is(net.minecraft.tags.ItemTags.STAIRS) || s.is(net.minecraft.tags.ItemTags.SLABS)
                || s.is(net.minecraft.tags.ItemTags.WOODEN_DOORS) || s.is(net.minecraft.world.item.Items.GLASS_PANE)
                || s.is(net.minecraft.world.item.Items.LANTERN) || s.is(net.minecraft.world.item.Items.BARREL)
                || s.is(net.minecraft.world.item.Items.HAY_BLOCK) || s.is(net.minecraft.tags.ItemTags.WOOL_CARPETS)
                || s.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS) || s.is(net.minecraft.world.item.Items.WATER_BUCKET)
                || s.is(net.minecraft.world.item.Items.BOOKSHELF) || s.is(net.minecraft.world.item.Items.LECTERN)
                || s.is(net.minecraft.world.item.Items.ENCHANTING_TABLE) || s.is(net.minecraft.world.item.Items.BREWING_STAND)
                || s.is(net.minecraft.world.item.Items.SMOKER) || s.is(net.minecraft.world.item.Items.LOOM)
                || s.is(net.minecraft.world.item.Items.GRINDSTONE) || s.is(net.minecraft.world.item.Items.CAMPFIRE)
                || s.is(net.minecraft.world.item.Items.NOTE_BLOCK) || s.is(net.minecraft.world.item.Items.CAULDRON)) {
            return 64 * 27;
        }
        return 0;
    }

    /** The ground chosen for a building cannot be reached: the village picks another lot. */
    @Override
    public void noteBuildAbandoned(String structure) {
        UUID village = ownerId();
        if (village != null) Villages.rejectSite(village, structure, level().getGameTime());
        handBackTheBuild();
    }

    /** Has this hand drawn materials out of the stores for a building it may not finish? */
    private boolean drewForBuild;

    /**
     * [economy] A lead that has lapsed (five minutes without getting anywhere, or another hand leading now)
     * holds what it drew as the stores' again, and its next deposit takes it in (Strays). The hand-back
     * only ever happened at the heart, when a building was next due: a farmer whose lead lapsed, made a
     * woodcutter far out, kept a hundred and twenty-eight cobblestone and seventy-one stairs as "the
     * building's" for days, and its own logs with them (logs are building blocks), so it banked nothing.
     * True if it let go.
     */
    public boolean releaseLapsedBuild() {
        if (!drewForBuild || holdsBuildLead()) return false;
        drewForBuild = false;
        buildNote("build: the lead lapsed; what it drew goes back to the stores");
        return true;
    }

    /** [economy] Stuck fast off its plot half a day (Fields.unstick): put back on it, as a lost hand is. */
    public boolean unstickToPlot() {
        boolean put = rescueToPlot();
        if (put) brain("stuck fast half a day — put back on its plot");
        return put;
    }

    /** [economy] Tests: as if this hand had drawn materials for a building. */
    public void drewForBuildForTests() { drewForBuild = true; }

    /**
     * What a builder drew for a building it is not going to raise goes back into
     * the stores, for whoever raises it. A taiga village's first builder gave up an
     * unreachable lot for its storehouse with all four founding chests, sixty-four
     * stone and twenty-six planks in its pack, and kept them: every hand that took
     * the job on after it found the stores empty, could not make the chests out of
     * nothing, and the village built nothing for three game days.
     */
    private void handBackTheBuild() {
        drewForBuild = false;
        if (villageCentre == null) return;
        int r = buildStoresRadius();
        int back = returnTo(villageCentre, BuildGoal::isBuildingBlock, 0, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.CHEST), usesVillageStores() ? 0 : 1, r);
        back += returnTo(villageCentre, st -> st.is(com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get()), 0, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.FURNACE), 1, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.LADDER)
            || st.is(net.minecraft.tags.ItemTags.FENCES) || st.is(net.minecraft.tags.ItemTags.FENCE_GATES), 0, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.tags.ItemTags.BEDS), 0, r);   // the next house's beds
        back += returnTo(villageCentre, Strays::finishing, 0, r);   // [economy] its stairs, slabs, doors and glass too
        if (back > 0) buildNote("build: handed " + back + " back to the stores");
    }

    /** The building is going up: the lead's term starts over with every block. */
    @Override
    public void noteBuildProgress() {
        UUID village = ownerId();
        if (village != null) Villages.leadProgress(village, getUUID(), level().getGameTime());
    }

    // ------------------------------ they sound like villagers too ------------
    //
    // Looking like a villager and grunting like a hired hand is worse than
    // either. These are the vanilla villager's own sounds; the folk still
    // never say a WORD in chat, which is a different thing entirely.

    @Override
    @Nullable
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return Manner.voice(this).sound;                       // [individual] a hum, a murmur, a grunt or a chirp, as it is made
    }

    /** [individual] Its own voice: by its sex, its years and its size (Manner.basePitch), on every sound it makes. */
    @Override
    public float getVoicePitch() {
        return Manner.pitch(this);
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(
            net.minecraft.world.damagesource.DamageSource source) {
        return net.minecraft.sounds.SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return net.minecraft.sounds.SoundEvents.VILLAGER_DEATH;
    }

    /**
     * The one place every folk's thinking meets the game's loop, and the last
     * thing standing between a bug in any of it and a crash report. An exception
     * that got out of here stops the server; this writes it to the log (see Guard)
     * and lets the one folk miss a beat.
     */
    @Override
    public void tick() {
        try {
            super.tick();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            com.jrpetty.mcassistant.Guard.struck("a village folk's tick",
                getName().getString() + " at " + blockPosition().toShortString(), t);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        if (showcase) return;
        if (laterLine != null && tickCount >= laterAt) {
            FolkTalk.speak(this, laterLine);
            laterLine = null;
        }
        if (tickCount % 20 == 17) Aboard.step(this);           // [mine-safety] out of a boat it never meant to board
        if (tickCount % 20 == 3) persona.town = ownerId();      // [identity] how fast it trusts a player is its town's (Treatment)
        // [transport] Sat in a cart or the ferry: the ride (or the rowing) is its day till it is off again.
        if (level() instanceof net.minecraft.server.level.ServerLevel riding && Transport.aboard(this)) {
            Transport.hold(this, riding);
            return;
        }
        // [diver] A diver on a dive is steered a tick at a time (its swim, its breath, its work); one being pulled out of
        // the water is held; a guard out with the diver at the monument keeps at its shoulder (Divers).
        if (level() instanceof net.minecraft.server.level.ServerLevel diving && Divers.hold(this, diving)) return;
        // [batchG] A visitor from afar (the bard, a tourist, the merchant), one of ours away for the day at a friend's
        // in another town, or a guard out taming a dog for the watch: that is its day (Visitors).
        if (Visitors.drive(this)) return;
        if (WorkTools.hold(this)) return;                      // [workitems] on a rope, or carrying a window box home to hang
        if (Newcomers.drive(this)) return;                     // [civic] a newcomer on the road, or camped at a town's edge
        // [fleet] Once a second for its town: the fishing fleet and the fish market (Fleet), the auction (Auctions).
        if (tickCount % 20 == 9 && ownerId() != null && level() instanceof net.minecraft.server.level.ServerLevel quay) {
            Fleet.tick(quay, ownerId());
            Auctions.tick(quay, ownerId());
        }
        Leisure.tick(this);
        Individual.tick(this);                                 // [individual] its looks, its manner, its keepsake, its dream
        if (tickCount % 100 == 53) Meals.tick(this);           // breakfast, the midday meal, supper
        if (tickCount % 20 == 15 && level() instanceof net.minecraft.server.level.ServerLevel kitchen) Kitchen.second(this, kitchen);   // [kitchen] a lunch packed, a wound bound
        // [fleet] Out with the fishing fleet: down the quay, rowing, fishing, home with the catch (Fleet). Before the storm
        // and the gatherings: a boat at sea is rowed home in a storm, not left to drift while its crew looks for a roof.
        if (level() instanceof net.minecraft.server.level.ServerLevel sea && Fleet.hold(this, sea)) return;
        // Lost underground with no way up it can walk (a mine run cut short, a fall into a cave): sent up
        // the nearest stairs, or to cut its own (MineStairs).
        if (tickCount % 100 == 71 && level() instanceof net.minecraft.server.level.ServerLevel below) MineStairs.lookForAWayUp(this, below);
        Park.tick(this);                                       // sat on a park bench, or on its way round the park
        Seats.tick(this);                                      // [townlife] sat down on its break or at a gathering (Seats)
        if (tickCount % 20 == 13) NightLight.tick(this);       // [townlife] a light in its hand out of doors after dark (NightLight)
        if (hiredBy != null && tickCount % 20 == 0 && level() instanceof net.minecraft.server.level.ServerLevel out) Hire.tick(this, out);
        if (tickCount % 160 == 80) CityTree.tend(this);          // the town's research on it: roads, drills, healers
        // The watch does not open the gates to go out after them: with the bell ringing a guard's
        // way lies inside the wall (folk going indoors still use their doors).
        if (tickCount % 20 == 7 && stationTask() == StationTask.GUARD
                && getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) {
            boolean watch = onWatch();
            if (watchKeepsDoors != watch) {
                nav.setCanOpenDoors(!watch);
                watchKeepsDoors = watch;
            }
        }
        // The bell is ringing: a guard with a bow makes for its post on the wall and lets nothing
        // outside draw it through the gate. Only a monster at arm's length is fought on the way.
        if (tickCount % 5 == 0 && onWatch() && !holdingAPost() && Raids.headingForPost(this)) {
            net.minecraft.world.entity.LivingEntity t = getTarget();
            if (t != null && distanceToSqr(t) > 9.0) setTarget(null);
            if (getTarget() == null) Raids.guardDuty(this);
        }
        if (tickCount % 40 == 0) greetPassersBy();
        // Somebody is talking to it, or it is out walking with somebody: its own day
        // waits until they are done.
        boolean withAPlayer = talkPartner() != null || companionPlayer() != null || guidePlayer() != null;
        // [townlife] A child tagging along after a player it waved to (Greetings): its own day waits a little.
        if (!withAPlayer && isBaby() && Greetings.tagAlong(this)) return;
        // Horses (Stables, Riding): a ridden horse kept at its pace, the stable's gates and its day; and a
        // horse being fetched or put away, or the rancher's work at the stable, is the work just now.
        if (level() instanceof net.minecraft.server.level.ServerLevel stableLevel) {
            if (tickCount % 2 == 0) Riding.tick(this, stableLevel);
            if (tickCount % 10 == 3) Stables.gate(this, stableLevel);
            if (tickCount % 20 == 11) Stables.tick(this, stableLevel);
            if (!withAPlayer && Stables.busy(this)) {
                if (tickCount % 5 == 0) Stables.drive(this, stableLevel);
                return;
            }
        }
        // [batchE] A traveller in a town with an inn after dusk: a room for the night, and on its way in the morning (Inn).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel innLevel && Inn.lodging(this, innLevel)) return;
        // On the road with a caravan: walked step by step, not thought about once in five seconds.
        if (trip != null && !withAPlayer && tickCount % 10 == 0 && level() instanceof net.minecraft.server.level.ServerLevel road) {
            Caravans.drive(this, road);
            return;
        }
        // Out scouting (Scouts): a stage at a time, looking about as it goes.
        if (expedition != null && !withAPlayer) {
            if (tickCount % 5 == 0 && level() instanceof net.minecraft.server.level.ServerLevel land) Scouts.drive(this, land);
            return;
        }
        // [war-scouting] Held captive at an enemy's barracks, after a spy, or on picket on the road (WarScouting):
        // that is its day just now.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel warLevel
                && WarScouting.hold(this, warLevel, tickCount % 5 == 1)) return;
        // [quests] A part in a quest (QuestRun): a lost child where it is lost, the miners at the mine head while the
        // curse is on, the found led home at a player's heels.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel questLevel && QuestRun.hold(this, questLevel)) return;
        // Through the gateway with a Nether party: it waits by the gateway till they come back.
        if (Nether.away(this) && !withAPlayer) return;
        // Out with a lead, fetching a wild animal home to the pen (Drover): that is the work just now.
        // The pen's gate: opened to go through it, and shut behind (Drover).
        if (tickCount % 10 == 7 && level() instanceof net.minecraft.server.level.ServerLevel penLevel) Drover.gate(this, penLevel);
        if (Drover.busy(this) && !withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel herding) {
            if (tickCount % 10 == 0) Drover.drive(this, herding);
            return;
        }
        // The job market between towns (JobSeekers): reading the notices at the board, saying its
        // goodbyes, or on the road to a new place; the rest of its day waits.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel market && JobSeekers.step(this, market)) return;
        // A friend passing by, hailed by name (Dealings).
        if (!withAPlayer) Dealings.greet(this);
        // Badly hurt: the brewer's healing, its own or (any trade) one from the stores.
        if (tickCount % 20 == 3 && level() instanceof net.minecraft.server.level.ServerLevel hurtIn) {
            if (!Links.drinkIfHurt(this) && stationTask() != StationTask.GUARD) Links.healFromTheStores(this, hurtIn);
        }
        // The watch between the bells (Patrols): a chase let go at the village's edge, and the
        // leader's escort at its shoulder while it is out and about — the assembly, the board, the
        // poll and the rest of its own day wait on the leader's.
        if ((stationTask() == StationTask.GUARD || Patrols.escorting(this)) && tickCount % 5 == 2
                && level() instanceof net.minecraft.server.level.ServerLevel watchIn) Patrols.step(this, watchIn);
        if (!withAPlayer && Patrols.escorting(this)) return;
        // [wf] Fire on or by the town's own blocks: the nearest hands to it, with a bucket of water or their
        // fists, before anything else (FireBrigade; the town is looked over every two seconds).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel fireLevel
                && FireBrigade.hold(this, fireLevel)) return;
        // [disasters] Out of a flood, to a neighbour's bed while its home is burnt or flooded, the fire watch's round,
        // a farmer's water carried to its parched field (Disasters).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel disasterLevel
                && Disasters.hold(this, disasterLevel)) return;
        // [wf] A thunderstorm: indoors, everybody but the watch, and there till it has passed (Weather).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel stormLevel
                && Weather.shelter(this, stormLevel)) return;
        // [watch-clears] A monster near, and it not one of the watch: indoors till it has gone (WatchClears).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel coverLevel
                && WatchClears.takeCover(this, coverLevel)) return;
        // [fireworks] A creeper it killed dropped its gunpowder: over to it, and the powder to the stores (FireworksMaker.fetch).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel powderLevel
                && FireworksMaker.fetch(this, powderLevel)) return;
        // [batchA] Laid up: a cold or its wounds, in bed at the infirmary or at home, and kept there (Health).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel careLevel && Health.hold(this, careLevel)) return;
        // [interviews] Called to an interview (Interviews): on the road to it from another town, waiting its turn on the bench
        // with its letter, across the table from the panel, or on the panel; its own day waits.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel interviewLevel
                && (tickCount % 4 == 0 ? Interviews.hold(this, interviewLevel) : Interviews.busy(this))) return;
        // [fields] A short errand with its tools: the can filled at the rain barrel, the nesting box emptied, the fish traps
        // gone round on a day the boats stay in, the garden watered of an evening (FieldTools).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel fieldLevel && FieldTools.hold(this, fieldLevel)) return;
        // [crime] The law first: in the stocks or at its community work, called to a trial, a guard on a case; or a folk up
        // to no good in its own free time (Crime).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel lawLevel
                && (tickCount % 4 == 2 ? Crime.hold(this, lawLevel) : Crime.busy(this))) return;
        // [fleet] At the auction on the square (calling it, or in the crowd), or at the fish market on the quay (Auctions).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel mart && Auctions.hold(this, mart)) return;
        // [batchD] The town's culture (Culture): a minute's silence at the bell, the choir at the morning service, the
        // play at the theatre (on the stage or a bench), the band at the tavern or a wedding, a toast, a picture painted.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel culture
                && (tickCount % 4 == 3 ? Culture.hold(this, culture) : Culture.busy(this))) return;
        // [fireworks] One of a display's crew, at the rack behind the launch spot (FireworkShows): before the gathering.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel rack && FireworkShows.hold(this, rack)) return;
        // The village coming together (Assemblies): the bell rung, it goes, finds a place and takes part.
        if (!withAPlayer && tickCount % 4 == 1 && level() instanceof net.minecraft.server.level.ServerLevel gathering
                && Assemblies.attend(this, gathering)) {
            if (tickCount - agendaTick >= 100 && ownerId() != null) {
                agendaTick = tickCount;
                Villages.Village home = Villages.get(ownerId());
                if (home != null) Assemblies.tick(gathering, home);
            }
            return;
        }
        // Moving house, or its keepsakes home of an evening (Homes).
        if (!withAPlayer && tickCount % 4 == 0 && level() instanceof net.minecraft.server.level.ServerLevel homing
                && Homes.moving(this, homing)) return;
        // The leader's morning at the leader's hall (Court).
        if (!withAPlayer && tickCount % 4 == 1 && level() instanceof net.minecraft.server.level.ServerLevel court
                && Court.hold(this, court)) return;
        // Election day (Elections): at its own hour, to the board to cast its vote.
        if (!withAPlayer && tickCount % 4 == 2 && level() instanceof net.minecraft.server.level.ServerLevel polling
                && Elections.goVote(this, polling)) return;
        // [civic] The town's vote on a great work or on newcomers (Referendums): to the board, at its own hour.
        if (!withAPlayer && tickCount % 4 == 2 && level() instanceof net.minecraft.server.level.ServerLevel referendum
                && Referendums.goVote(this, referendum)) return;
        // The school's morning (School): the children to their desks, the teacher to the lectern.
        if (!withAPlayer && tickCount % 4 == 0 && level() instanceof net.minecraft.server.level.ServerLevel schooling
                && School.hold(this, schooling)) return;
        // [batchC] Sport and play (Sport): a match to play or watch on the rest day, an away day at a neighbour's
        // pitch, the fishing contest or the children's race, the watch at the butts of a morning.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel sport && Sport.hold(this, sport)) return;
        // [transport] To the station or the ferry landing to cross, and waiting there; the ferryman at its ferry (Transport).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel transit && Transport.hold(this, transit)) return;
        // [civic] The town's great work, built together (BigWorks): everybody on the works day, and anybody free after it.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel greatWork
                && (tickCount % 4 == 1 ? BigWorks.hold(this, greatWork) : BigWorks.busy(this))) return;
        // [cartographer] Out walking the town (or the country) with its sheets, a round a second (MapSurveys).
        if (!withAPlayer && stationTask() == StationTask.CARTOGRAPHER && level() instanceof net.minecraft.server.level.ServerLevel mapping
                && Cartographers.hold(this, mapping)) return;
        // Called to the town's own work (TownJobs): to the spot, and at it.
        if (!withAPlayer && tickCount % 4 == 3 && level() instanceof net.minecraft.server.level.ServerLevel works
                && TownJobs.hold(this, works)) return;
        // The town's calendar (TownCalendar): the ringer to the town bell; to the midday meal at the noon bell,
        // home at the dusk bell; round to a friend with a birthday present.
        if (!withAPlayer && tickCount % 4 == 0 && level() instanceof net.minecraft.server.level.ServerLevel calendar
                && TownCalendar.hold(this, calendar)) return;
        // [batchB] The year's festivals (Festivals): a midwinter present bought and taken round, a snowman built by the playground.
        if (!withAPlayer && tickCount % 4 == 0 && level() instanceof net.minecraft.server.level.ServerLevel festive
                && Festivals.hold(this, festive)) return;
        // The museum's curator on its errand (Museum): a find fetched out of the stores and set out, a year bound.
        if (!withAPlayer && tickCount % 4 == 0 && level() instanceof net.minecraft.server.level.ServerLevel museum
                && Museum.hold(this, museum)) return;
        // [library] The library (Library): a writer at its desk with its book and quill, a reader in one of its chairs.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel library
                && (tickCount % 4 == 2 ? Library.hold(this, library) : Library.busy(this))) return;
        // The family's own (Families): its pet walked; a pet, a garden or a grave to see to; supper at home; a story
        // at bedtime; the children's games of an afternoon. Between its looks, nothing else takes the folk away.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel family
                && (tickCount % 4 == 2 ? Families.hold(this, family) : Families.busy(this))) return;
        // [batchA] A neighbour's errand: the old visited, a newcomer shown round, a housewarming, the poor box (Neighbourly).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel kind
                && (tickCount % 4 == 1 ? Neighbourly.hold(this, kind) : Neighbourly.busy(this))) return;
        // [batchF] The town's affairs (Civics): out with a search party, the post, a warden's round, a petition, a good turn.
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel civic
                && (tickCount % 4 == 3 ? Civics.hold(this, civic) : Civics.busy(this))) return;
        // [individual] Its own life: home before dark, off at the sight of a monster; its habits at their hours (Individual).
        if (!withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel own
                && (tickCount % 2 == 0 ? Individual.hold(this, own) : Individual.busy(this))) return;
        if (tickCount - agendaTick < 100) return;   // folk think slowly, on purpose
        agendaTick = tickCount;
        flyTheColours();
        showTheWealth();
        Fashion.look(this);                            // [fashion] its own style, its hat off work, what it wants on the tailor's book
        if (!life.rolled()) life.roll(getRandom(), null, null);
        ensurePersona();
        refreshMood();
        dreamCameTrue();
        if (level() instanceof net.minecraft.server.level.ServerLevel valuing) Values.daily(valuing, this, level().getDayTime() / 24000L);
        // Its own knacks (FolkSkills): a point to spend, chosen at a quiet moment; a guard's armour kept up.
        if (level() instanceof net.minecraft.server.level.ServerLevel knackLevel) FolkSkills.tick(knackLevel, this);
        if (ownerId() != null) {
            Villages.chooseElder(ownerId(), level().getDayTime() / 24000L);
            if (level() instanceof net.minecraft.server.level.ServerLevel polls) {
                Villages.Village home = Villages.get(ownerId());
                if (home != null) {
                    Elections.tick(polls, home);
                    Homes.tick(polls, home);
                    Families.tick(polls, home);          // pets, gardens, graves visited, anniversaries (Families)
                    Health.tick(polls, home);            // [batchA] colds passed round, the healer's round (Health)
                    Neighbourly.tick(polls, home);       // [batchA] the old, newcomers, housewarmings, the poor box (Neighbourly)
                    Bank.tick(polls, home);              // the bank opens the day it stands, and gets its banker
                    CaveDwellers.tick(polls, home);      // [caves] an Iron Age town takes up its cave dwellers
                    Fletchers.tick(polls, home);         // [fletcher] its fletcher, and the afternoon's practice at the range
                    Golems.look(polls, home);            // [golems] its golem keeper, after the raids
                    FireworksMaker.tick(polls, home);    // [fireworks] a Stone Age town takes up fireworks: the hut, the maker
                    Cartographers.tick(polls, home);     // [cartographer] the town chooses its cartographer once its map room stands
                    Divers.tick(polls, home);            // [diver] a town by the water finds it, and takes up its diver
                    PlayerCivic.tick(polls, home);       // [player-civic] the campaign, a player leader's morning, young apprentices' journals
                }
            }
            if (level() instanceof net.minecraft.server.level.ServerLevel orders) Orders.consider(orders, ownerId(), level().getDayTime() / 24000L);
        }
        // The town's streets, worn and paved and lit a little at a time (TownWork).
        if (ownerId() != null && level() instanceof net.minecraft.server.level.ServerLevel townLevel) {
            Villages.Village home = Villages.get(ownerId());
            if (home != null) {
                TownWork.tick(townLevel, home);
                TownLife.tick(townLevel, home);         // lit windows, chimney smoke, washing, stalls, signs
                Museum.tick(townLevel, home);           // the museum's finds and its archive
                Library.tick(townLevel, home);          // [library] its books written, shelved, lent and read
                Assemblies.tick(townLevel, home);       // the morning assembly, openings, feasts, the council, elections
                Contentment.daily(townLevel, home);     // how it is doing; at its worst, folk leave
            }
        }
        if (withAPlayer) return;
        keepTrail();
        agenda();
        if (++beats % 2 == 0) socialBeat();
    }

    // ------------------------------ an inner life ---------------------------
    //
    // See Persona (who it is on its own, and to you), FolkTalk (what it says) and
    // Leisure (what it does with its own time).

    private final Persona persona = new Persona();
    /** [batchA] Its health: a cold, laid up in bed, who has seen to it (Health). Saved with it. */
    private final Health.State health = new Health.State();

    public Health.State health() { return health; }
    /** Who its parents are (Homes): by their ids, for children born from now on. */
    private final java.util.List<UUID> parentIds = new java.util.ArrayList<>();

    public java.util.List<UUID> parentIds() { return parentIds; }

    /** What it cares about (Values): set up from its nature, then moved by its life. */
    final int[] values = new int[Values.N];
    boolean valuesSet;
    long valuesDay = -1;
    @Nullable private UUID talkingTo;
    private int talkUntil;
    @Nullable private UUID companion;
    private int companionUntil;
    @Nullable private String laterLine;
    private int laterAt;
    private int lastChatterTick = -100000;
    private final java.util.Map<UUID, Integer> greeted = new java.util.HashMap<>();
    /** What it is doing with its own time right now, in its own words; null at work. */
    @Nullable String hobbyNow;
    @Nullable BlockPos hobbySpot;
    long hobbySpotDay = -1;
    int hobbyTick;
    int lastLeisureTick = -100000;
    boolean propInHand;
    @Nullable UUID cardPartner;
    int note;

    public Persona persona() { return persona; }

    /** The knacks it has chosen for itself as it rose in its trades (FolkSkills): kept for life, saved with it. */
    private final FolkSkills.Book knacks = new FolkSkills.Book();

    public FolkSkills.Book knacks() { return knacks; }

    /** Its experience at a trade, nought for one it never worked (FolkSkills: how near its next knack point is). */
    public int xpInTrade(StationTask t) { return t == StationTask.NONE ? 0 : tradeXp.getOrDefault(t, 0); }

    /** Strong Back (FolkSkills): a bigger load on each trip of its round. */
    @Override
    protected int haulLoadBonus() { return FolkSkills.haulBonus(this); }

    // ------------------------------ money --------------------------------------

    /** What this folk has saved out of its wages (Market). */
    private int purse;
    /** The day it last spent at the market. */
    private long shoppedDay = -1;

    public int purse() { return purse; }

    public void earn(int coins) { if (coins > 0) purse += coins; }

    /** How much of its work had been paid for at its last wage (Wealth.bonus). */
    private int paidDeeds;
    /** The comforts it has bought and set up in its home: a carpet, a painting, candles, flowers (Luxuries). */
    private int comforts;
    /** The day it last went to the shop for something for its home. */
    private long comfortDay = -10;

    public int paidDeeds() { return paidDeeds; }

    public int comforts() { return comforts; }

    /** Luxuries: another comfort set up in its home, and when it last shopped for one. */
    void addComfort() { comforts++; }

    long comfortDay() { return comfortDay; }

    void comfortDay(long day) { comfortDay = day; }

    /** Paid: what it had done so far is paid for. */
    public void paid(int coins) {
        earn(coins);
        if (coins > 0) earnedInAll += coins;
        paidDeeds = deedsTotal();
    }

    /** Every coin of wages it has ever been paid. */
    private int earnedInAll;
    /** The day its wage went up (the place came up in the world), and the last day it was paid short. */
    long payRiseDay = -100, shortPaidDay = -100;

    public int earnedInAll() { return earnedInAll; }

    public void payRise(long day) { payRiseDay = day; }

    public void shortPaid(long day) { shortPaidDay = day; }

    public boolean spend(int coins) {
        if (coins <= 0 || purse < coins) return false;
        purse -= coins;
        return true;
    }

    /**
     * Market day, on its break: off to a stall with some of its savings, and home with a
     * treat. Each folk goes to the stall its id picks, so the square fills up evenly.
     */
    private boolean shopping(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        if (village == null || villageCentre == null || purse < 1 || isBaby()) return false;
        long day = level().getDayTime() / 24000L;
        if (shoppedDay == day || !Market.marketDay(village, day)) return false;
        if (Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        int[][] stalls = { { 5, 8 }, { -5, 8 }, { 5, -8 }, { -5, -8 } };   // in front of each counter
        int[] s = stalls[Math.floorMod(getUUID().hashCode(), stalls.length)];
        BlockPos stand = villageCentre.offset(s[0], 0, s[1]);
        if (blockPosition().distSqr(stand) > 9.0) {
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(stand, 0.9D);
                socialWalkTick = tickCount;
            }
            return true;
        }
        shoppedDay = day;
        String bought = Market.folkBuys(server, v, this);
        if (bought != null) {
            say(getRandom().nextBoolean() ? "Some " + bought + " — just what I wanted." : "Market day! I treated myself to some " + bought + ".");
            brain("bought " + bought + " at the market");
        }
        return true;
    }

    /**
     * Its savings, spent on its home (Luxuries): a folk that is comfortable or better goes to the shop for a
     * carpet, a painting, glass for its windows, a candle, a pot and a flower for it, and once it is well
     * off a lantern, a banner in its colour or a bookshelf — paid for at the counter like any sale —
     * carries it home and sets it out where it belongs. Every second day at most.
     */
    private boolean homeComfort(net.minecraft.server.level.ServerLevel server) {
        if (Homes.lodging(this)) return false;          // a spare bed in another's house is no home to furnish
        return Luxuries.forHome(this, server);
    }

    /**
     * A comfort bought somewhere other than the shop (a player's stall: PlayerStalls): into its pack, and
     * Luxuries carries it home and sets it out as one bought at the counter. False if it cannot carry it.
     */
    public boolean carryHome(net.minecraft.world.item.ItemStack comfort) {
        if (comfort.isEmpty() || bedPos() == null) return false;
        return insertItem(comfort.copyWithCount(1)).isEmpty();
    }

    private String pick(String... lines) {
        return lines[getRandom().nextInt(lines.length)];
    }

    /** The day it last dropped in to the café, and when it set off there. */
    private long cafeDay = -1;
    private int cafeSetOff = -1;
    private long shopDay = -1;
    private int shopSetOff = -1;
    private long lookedRound = -1;

    /**
     * The curious and the sociable go to have a look at a building the day it is opened: walk
     * round to it, take it in, and say what they think.
     */
    private boolean lookRound(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        if (village == null || isBaby() || !(life.has(Social.Trait.CURIOUS) || life.has(Social.Trait.SOCIABLE))) return false;
        long day = level().getDayTime() / 24000L;
        Assemblies.Opened o = Assemblies.lastOpened(village, day);
        if (o == null || lookedRound >= o.day()) return false;
        if (blockPosition().distSqr(o.at()) > 25.0) {
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(o.at(), 0.85D);
                socialWalkTick = tickCount;
            }
            hobbyNow = "going to see the new " + Villages.spoken(o.structure()).replaceFirst("^(the|a) ", "");
            return true;
        }
        lookedRound = day;
        getLookControl().setLookAt(o.at().getX() + 0.5, o.at().getY() + 2.0, o.at().getZ() + 0.5);
        String what = Villages.spoken(o.structure());
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "So this is " + what + ". Lovely work!", "Look at that — " + what + ", and we built it.",
            "I had to come and see " + what + " for myself.", capitalFirst(what) + "! Whatever next?"));
        persona.remember(day, "I went to see " + what + " when it was new", 2);
        return true;
    }

    private static String capitalFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Now and then, off work, a folk goes to the shop: for the tool its trade wants if it has
     * none (paid for out of its own savings, not taken from the stores), or, doing well, for
     * something nice. Every coin of it goes into the treasury that pays the wages.
     */
    private boolean shopVisit(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        if (village == null || purse < 3 || isBaby()) return false;
        long day = level().getDayTime() / 24000L;
        if (shopDay == day) return false;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> tool = Cafe.toolFor(stationTask());
        boolean forWork = tool != null && countCarried(tool) == 0
            && stationTask() != StationTask.GUARD;     // [guard-kit] the watch's blade is issued (WatchKit), never bought
        boolean treat = Wealth.tier(this).ordinal() >= Wealth.Tier.WELL_OFF.ordinal() && Math.floorMod(getUUID().hashCode() + day, 4L) == 0
            && !Dreams.saving(this);                    // [individual] not while it saves for its dream
        if (!forWork && !treat) { shopDay = day; return false; }
        BlockPos shop = Villages.builtAt(village, "shop");
        if (shop == null || !Cafe.open(village, "shop")) { shopDay = day; return false; }
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        if (blockPosition().distSqr(shop) > 16.0) {
            if (shopSetOff < 0) shopSetOff = tickCount;
            if (tickCount - shopSetOff > 1200) { shopDay = day; shopSetOff = -1; return false; }   // could not get in
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(shop, 0.9D);
                socialWalkTick = tickCount;
            }
            hobbyNow = "on the way to the shop";
            return true;
        }
        shopDay = day;
        shopSetOff = -1;
        String bought = Cafe.folkShops(server, v, this, forWork);
        if (bought != null) {
            FolkTalk.speak(this, forWork ? "New tools for the job: " + bought + ". Money well spent."
                : FolkTalk.pick(getRandom(), "Treated myself: " + bought + ".", "Couldn't resist — " + bought + "!"));
            brain("bought " + bought + " at the shop");
            persona.remember(day, "I bought " + bought + " at the shop", 2);
        }
        return true;
    }

    /**
     * Some breaks (about one day in three), a folk with a few coins saved drops in to the café
     * for a drink or a bite: in at the door, up to the counter, paid for out of its wages.
     */
    private boolean cafeVisit(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        if (village == null || purse < 2 || isBaby() || Dreams.saving(this)) return false;   // [individual] saving for its dream
        long day = level().getDayTime() / 24000L;
        if (cafeDay == day) return false;
        if (Math.floorMod(getUUID().hashCode() + day, 3L) != 0) { cafeDay = day; return false; }
        BlockPos cafe = Villages.builtAt(village, "cafe");
        if (cafe == null || !Cafe.open(village, "cafe")) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        if (blockPosition().distSqr(cafe) > 16.0) {
            if (cafeSetOff < 0) cafeSetOff = tickCount;
            if (tickCount - cafeSetOff > 1200) { cafeDay = day; cafeSetOff = -1; return false; }   // could not get in
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(cafe, 0.9D);
                socialWalkTick = tickCount;
            }
            return true;
        }
        cafeDay = day;
        cafeSetOff = -1;
        String had = Cafe.folkBuys(server, v, this);
        if (had != null) {
            String[] lines = { "A " + had + " — just the thing.", "Nothing like a " + had + " on my break.",
                "I always have the " + had + " here." };
            say(lines[getRandom().nextInt(lines.length)]);
            brain("had " + had + " at the café");
        }
        return true;
    }

    @Nullable public BlockPos villageCentre() { return villageCentre; }

    @Nullable public String hobbyNow() { return hobbyNow; }

    /** Is this the one its village looks up to? */
    public boolean isElder() {
        UUID village = ownerId();
        return village != null && getUUID().equals(Villages.elder(village));
    }

    /** Who this folk is on its own, settled the first time anybody needs to know. */
    public void ensurePersona() {
        if (persona.rolled() || level().isClientSide) return;
        if (!life.rolled()) life.roll(getRandom(), null, null);
        String origin = showcase ? "just visiting"
            : !life.parents().isEmpty() ? "born here, the child of " + life.parents()
            : "one of the people who founded this village";
        persona.roll(getRandom(), life, stationTask(), level().getDayTime() / 24000L, origin);
        refreshMood();
    }

    /** Say something in a moment, as if in answer. */
    public void sayLater(String text, int delayTicks) {
        laterLine = text;
        laterAt = tickCount + delayTicks;
    }

    public void startTalking(net.minecraft.world.entity.player.Player player) {
        talkingTo = player.getUUID();
        talkUntil = tickCount + 600;
        getNavigation().stop();
    }

    public void stopTalking() { talkingTo = null; }

    /** The player this folk is talking to, while it is. */
    @Nullable
    public net.minecraft.world.entity.player.Player talkPartner() {
        if (talkingTo == null) return null;
        net.minecraft.world.entity.player.Player p = level().getPlayerByUUID(talkingTo);
        if (p == null || tickCount > talkUntil || p.distanceToSqr(this) > 10.0 * 10.0) {
            talkingTo = null;
            return null;
        }
        return p;
    }

    public boolean isFollowing(net.minecraft.world.entity.player.Player player) {
        return companion != null && companion.equals(player.getUUID()) && tickCount < companionUntil;
    }

    public void startFollowing(net.minecraft.world.entity.player.Player player) {
        companion = player.getUUID();
        companionUntil = tickCount + 6000;          // five minutes of its day
        stopTalking();
    }

    public void stopFollowing() { companion = null; }

    // ------------------------------ showing somebody the way (Guide) -------------

    @Nullable private UUID guiding;
    @Nullable private BlockPos guideTo;
    private String guideWhat = "";
    private int guideUntil;

    public void startGuiding(net.minecraft.world.entity.player.Player player, BlockPos to, String what) {
        guiding = player.getUUID();
        guideTo = to.immutable();
        guideWhat = what;
        guideUntil = tickCount + 3600;                 // three minutes of its day at most
        stopTalking();
        stopFollowing();
    }

    public void stopGuiding() { guiding = null; guideTo = null; }

    /** The player it is showing the way, while it is. */
    @Nullable
    public net.minecraft.world.entity.player.Player guidePlayer() {
        if (guiding == null || guideTo == null) return null;
        net.minecraft.world.entity.player.Player p = level().getPlayerByUUID(guiding);
        if (p == null || tickCount > guideUntil || p.distanceToSqr(this) > 48.0 * 48.0) {
            guiding = null;
            return null;
        }
        return p;
    }

    @Nullable public BlockPos guideTo() { return guideTo; }

    public String guideWhat() { return guideWhat; }

    // ------------------------------ hired for an adventure (Hire) ----------------

    @Nullable UUID hiredBy;
    String hiredName = "";
    long hiredUntil, hiredSince;
    int hiredKills;
    final java.util.Set<String> hiredSaw = new java.util.LinkedHashSet<>();
    /** What it carried when it set out (what it has more of at the end is the employer's share). */
    final java.util.Map<net.minecraft.world.item.Item, Integer> hiredPack = new java.util.HashMap<>();

    public boolean isHired() { return hiredBy != null; }

    @Nullable public UUID hiredBy() { return hiredBy; }

    /** Out with somebody who hired it: it fights what threatens them, like the watch does. */
    @Override
    protected boolean hiredToFight() { return hiredBy != null; }

    void hire(net.minecraft.world.entity.player.Player p, long until) {
        if (hiredBy == null || !hiredBy.equals(p.getUUID())) {
            hiredBy = p.getUUID();
            hiredName = p.getName().getString();
            hiredSince = level().getGameTime();
            hiredKills = 0;
            hiredSaw.clear();
            hiredPack.clear();
            for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
                if (!st.isEmpty()) hiredPack.merge(st.getItem(), st.getCount(), Integer::sum);
            }
        }
        hiredUntil = until;
        companion = p.getUUID();
        companionUntil = Integer.MAX_VALUE;
        stopTalking();
    }

    void unhire() {
        hiredBy = null;
        hiredName = "";
        hiredPack.clear();
        hiredSaw.clear();
        companion = null;
    }

    /** The player it is walking with, while it is; it heads home when the time is up
     *  or the player has gone too far ahead. */
    @Nullable
    public net.minecraft.world.entity.player.Player companionPlayer() {
        if (companion == null) return null;
        net.minecraft.world.entity.player.Player p = level().getPlayerByUUID(companion);
        // Hired: it goes where its employer goes, by night as by day, however far.
        if (companion.equals(hiredBy)) return p != null && p.isAlive() ? p : null;
        if (p == null || !p.isAlive() || p.distanceToSqr(this) > 48.0 * 48.0 || tickCount >= companionUntil
                || level().isNight() && stationTask() != StationTask.GUARD) {
            if (p != null && p.isAlive()) {
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I'd best get back. Thanks for the walk!",
                    "That's me done — home I go.", "Time I was getting back."));
                persona.feelFor(p.getUUID(), p.getName().getString(), 4);
            }
            companion = null;
            return null;
        }
        return p;
    }

    /** A word for a player walking past: friends warmly, strangers politely, the shy
     *  only when they know you, and somebody it can't stand with a scowl. */
    private void greetPassersBy() {
        if (isSleeping() || talkPartner() != null || !persona.rolled()) return;
        net.minecraft.world.entity.player.Player p = level().getNearestPlayer(this, 5.0);
        if (p == null || p.isSpectator() || p.isInvisible()) return;
        // [townlife] Somebody it knows: once a day, a wave and a hello by name (Greetings); the passing word after it waits.
        if (Greetings.wave(this, p)) {
            greeted.put(p.getUUID(), tickCount);
            return;
        }
        Integer last = greeted.get(p.getUUID());
        if (last != null && tickCount - last < 4800) return;
        greeted.put(p.getUUID(), tickCount);
        if (greeted.size() > 16) greeted.clear();
        int aff = persona.affinity(p.getUUID());
        // An outcast: the watch keeps an eye on them, everybody else keeps away.
        UUID village = ownerId();
        if (village != null && Standing.of(village, p.getUUID(), level().getGameTime()).title() == Standing.Title.OUTCAST) {
            if (stationTask() == StationTask.GUARD) {
                getLookControl().setLookAt(p, 30.0F, 30.0F);
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I've got my eye on you.", "Keep walking.",
                    "One wrong move…"));
            } else {
                double dx = getX() - p.getX(), dz = getZ() - p.getZ();
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                BlockPos away = surfaceAt((int) (getX() + dx / len * 7), (int) (getZ() + dz / len * 7));
                if (away != null) walkTo(away, 1.1D);
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "…", "Stay away from me.", "Not you."));
            }
            return;
        }
        if (life.has(Social.Trait.SHY) && aff < 30 && getRandom().nextBoolean()) return;
        getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (aff >= 55 && !isBaby() && present(p)) return;
        FolkTalk.speak(this, FolkTalk.passing(this, p));
        if (aff <= -50 && level() instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
                getX(), getY() + 2.0, getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
    }

    /**
     * A folk that is fond of a player now and then gives them something, unasked:
     * whatever its days have given it to give — a fish it caught, a flower from its
     * garden, a loaf it baked, something it found down the mine, a bowl it whittled.
     * Not more than once every few days, and not every time they pass.
     */
    public boolean present(net.minecraft.world.entity.player.Player p) {
        long day = level().getDayTime() / 24000L;
        long last = persona.lastPresent(p.getUUID());
        if (last >= 0 && day - last < 3) return false;
        if (getRandom().nextInt(3) != 0) return false;
        net.minecraft.world.item.ItemStack gift = presentFor();
        if (gift.isEmpty()) return false;
        String what = gift.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        persona.gavePresent(p.getUUID(), day);
        if (!p.getInventory().add(gift)) p.drop(gift, false);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        persona.feelFor(p.getUUID(), p.getName().getString(), 2);
        persona.remember(day, "I gave " + p.getName().getString() + " a present", 3);
        String you = p.getName().getString();
        FolkTalk.speak(this, FolkTalk.pick(getRandom(),
            you + "! I've something for you — " + FolkTalk.article(what) + ".",
            "Here, " + you + ". " + cap(FolkTalk.article(what)) + ", just for you.",
            "I kept this for you, " + you + ". Go on, take it.",
            "For you, " + you + ". Don't tell the others!"));
        if (level() instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                p.getX(), p.getY() + 1.5, p.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
        }
        return true;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** What it has to give: from its own pack where its work fills it, else a small thing of its own. */
    private net.minecraft.world.item.ItemStack presentFor() {
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> fish =
            st -> st.is(net.minecraft.world.item.Items.COD) || st.is(net.minecraft.world.item.Items.SALMON)
                || st.is(net.minecraft.world.item.Items.COOKED_COD) || st.is(net.minecraft.world.item.Items.COOKED_SALMON);
        if ((stationTask() == StationTask.FISH || persona.hobby() == Persona.Hobby.FISHING) && countCarried(fish) > 0) {
            for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
                if (fish.test(st)) {
                    net.minecraft.world.item.ItemStack one = st.copyWithCount(1);
                    st.shrink(1);
                    return one;
                }
            }
        }
        if (stationTask() == StationTask.FARM && countFood() >= 6
                && countCarried(st -> st.is(net.minecraft.world.item.Items.BREAD)) > 0
                && removeMatching(st -> st.is(net.minecraft.world.item.Items.BREAD), 1) == 1) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD);
        }
        // Everything given is something it has: out of its own pack, or picked there and then.
        if (stationTask() == StationTask.MINE) {
            for (net.minecraft.world.item.Item it : new net.minecraft.world.item.Item[]{
                    net.minecraft.world.item.Items.AMETHYST_SHARD, net.minecraft.world.item.Items.COAL}) {
                if (removeMatching(st -> st.is(it), 1) == 1) return new net.minecraft.world.item.ItemStack(it);
            }
        }
        if (persona.hobby() == Persona.Hobby.WHITTLING) {
            if (removeMatching(st -> st.is(net.minecraft.world.item.Items.BOWL), 1) == 1
                    || removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PLANKS), 1) == 1) {
                return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOWL);   // whittled from a plank it had
            }
        }
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
            if (st.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS)) {
                net.minecraft.world.item.ItemStack one = st.copyWithCount(1);
                st.shrink(1);
                return one;
            }
        }
        return pickAFlower();
    }

    /** A flower picked from the ground near it, for somebody: out of the meadow, not out of the air. */
    private net.minecraft.world.item.ItemStack pickAFlower() {
        BlockPos here = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(here.offset(-6, -2, -6), here.offset(6, 2, 6))) {
            net.minecraft.world.level.block.state.BlockState st = level().getBlockState(p);
            if (!st.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)) continue;
            net.minecraft.world.item.Item it = st.getBlock().asItem();
            if (it == net.minecraft.world.item.Items.AIR) continue;
            level().removeBlock(p, false);
            swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return new net.minecraft.world.item.ItemStack(it);
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** How it feels today, and why: worked out from its own life. */
    public void refreshMood() {
        if (!persona.rolled() || level().isClientSide) return;
        long day = level().getDayTime() / 24000L;
        int m = 58;
        java.util.List<Object[]> why = new java.util.ArrayList<>();
        if (life.has(Social.Trait.CHEERFUL)) m += 12;
        if (life.has(Social.Trait.GRUMPY)) m -= 10;
        if (persona.sleptDay >= day - 1) { m += 6; why.add(new Object[]{"slept", 6}); }
        else if (persona.since() >= 0 && day > persona.since() + 1) { m -= 6; why.add(new Object[]{"rough", 6}); }
        // Hungry is a meal missed (Meals), more with every one; fed is its meals had. (It once read the
        // pack: a folk carrying bread it never ate was "fed", and a child fed at home "hungry".)
        int missed = meals.missedInRow();
        if (missed > 0) {
            int h = Math.min(24, 8 + 4 * missed);
            m -= h;
            why.add(new Object[]{"hungry", h});
        } else if (meals.eatenToday() > 0 || countFood() >= 4) {
            m += 3;
            why.add(new Object[]{"fed", 2});
        }
        int friends = life.friends().size();
        if (life.partner() != null) { m += 6; why.add(new Object[]{"partner", 7}); }
        if (friends >= 3) { m += 6; why.add(new Object[]{"friends", 6}); }
        else if (friends == 0 && life.partner() == null && persona.since() >= 0 && day - persona.since() > 2) {
            int lonely = life.has(Social.Trait.SOCIABLE) ? 12 : 5;
            m -= lonely;
            why.add(new Object[]{"lonely", lonely});
        }
        if (day - persona.giftDay <= 1) { m += 10; why.add(new Object[]{"gift", 10}); }
        if (day - persona.hurtDay <= 1) { m -= 15; why.add(new Object[]{"hurt", 15}); }
        if (level().isRaining()) {
            boolean likesIt = stationTask() == StationTask.FISH || persona.hobby() == Persona.Hobby.FISHING
                || persona.quirk().equals("loves the rain");
            if (likesIt) { m += 3; why.add(new Object[]{"rainlove", 3}); }
            else {
                int wet = persona.quirk().equals("hates the rain") ? 10 : 4;
                m -= wet;
                why.add(new Object[]{"rain", wet});
            }
        }
        if (stationTask() != StationTask.NONE && !missingEssentials().isEmpty()) { m -= 8; why.add(new Object[]{"stuck", 8}); }
        if (day - persona.hobbyDay() <= 1) { m += 6; why.add(new Object[]{"hobby", 5}); }
        UUID village = ownerId();
        if (village != null) {
            if (day - Villages.agedOn(village) <= 2) { m += 8; why.add(new Object[]{"aged", 8}); }
            if (villageCentre != null && level() instanceof net.minecraft.server.level.ServerLevel server
                    && Villages.stock(server, villageCentre, Villages.Task.FOOD, Villages.storesRadius(village))
                        < Villages.larderForBirth(village)) {
                m -= 6;
                why.add(new Object[]{"short", 6});
            }
        }
        if (persona.ambitionMet()) { m += 5; why.add(new Object[]{"dream", 9}); }
        if (day - persona.feastDay <= 1) { m += 8; why.add(new Object[]{"feast", 8}); }
        if (day - griefDay <= 2) { m -= 12; why.add(new Object[]{"grief", 12}); }
        if (day - quarrelDay <= 0) { m -= 6; why.add(new Object[]{"quarrel", 6}); }
        if (village != null && Diplomacy.sore(village, day)) { m -= 5; why.add(new Object[]{"feud", 5}); }
        if (village != null) {
            if (RestDay.justRested(village, day)) { m += 5; why.add(new Object[]{"rested", 5}); }
            if (day - payRiseDay <= 1) { m += 5; why.add(new Object[]{"payrise", 5}); }
            if (day - shortPaidDay <= 0) { m -= 4; why.add(new Object[]{"shortpaid", 4}); }
            int content = Contentment.score(village);
            if (content >= 80) { m += 4; why.add(new Object[]{"thriving", 4}); }
            else if (content < 25) { m -= 6; why.add(new Object[]{"miserable", 6}); }
            int civic = CityTree.moodBonus(village);                  // the town's Tavern Songs and Rest Day Charter
            if (civic > 0) { m += civic; why.add(new Object[]{"civic", civic}); }
            int homely = Decor.moodBonus(this);                       // a home well furnished (Decor)
            if (homely > 0) { m += homely; why.add(new Object[]{"homely", homely}); }
            int proud = Museum.pride(this, day);                      // its find on show in the museum
            if (proud > 0) { m += proud; why.add(new Object[]{"proud", proud}); }
            // The leader: its own spirits, and how this folk gets on with it.
            int led = Leader.spirits(this);
            if (led >= 3) { m += led; why.add(new Object[]{"leader", led}); }
            else if (led <= -3) { m += led; why.add(new Object[]{"leaderhard", -led}); }
            else m += led;
        }
        m = Quarters.mood(this, m, why);                // where it lives: the crafts' smoke and din, the park (Quarters)
        m = FolkSkills.mood(this, m, why);              // Bright Spirit, a bright friend near, Unflappable's floor
        m = Birthdays.mood(this, day, m, why);          // its birthday (Birthdays)
        m = Families.mood(this, day, m, why);           // its wedding anniversary (Families)
        m = WarAndPeace.mood(this, day, m, why);        // [war-peace] weary of the war (or a Guardian's pride in it)
        m = Visitors.mood(this, day, m, why);           // [batchG] a night of the bard's songs, a day with a friend from away
        m = Health.mood(this, day, m, why);             // [batchA] a cold (Health)
        m = Civics.mood(this, day, m, why);             // [batchF] a letter, the town meeting, a good turn, found and home
        m = Crime.mood(this, day, m, why);              // [crime] robbed, paid back, shamed, wrongly accused and cleared
        m = Referendums.mood(this, day, m, why);        // [civic] proud of the work it built; a newcomer's gratitude
        m = Individual.mood(this, day, m, why);         // [individual] a dream come true, a habit kept, its season, a fright
        m = WindowBoxes.mood(this, day, m, why);        // [workitems] its household's window boxes in flower
        m = Interviews.mood(this, day, m, why);          // [interviews] a post won at interview, or missed
        m = Kitchen.mood(this, day, m, why);            // [kitchen] a slice of honey cake at the wedding, a mead at the tavern
        m = Pastimes.mood(this, day, m, why);           // [leisure] a night under a quilt, a game won, a kite, a kickabout, the lanterns
        why.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Object[] w : why) keys.add((String) w[0]);
        persona.setMood(m, keys);
    }

    /** Everybody runs when there is a long way to go, the old as well. */
    @Override
    protected boolean fitToRun() {
        return super.fitToRun();
    }

    /** A happy village works faster, a miserable one slower (Contentment). */
    @Override
    protected int villageWorkPercent() {
        // How the village is doing, and how the leader drives it (Leader.pace). (Old age was
        // counted in here too; it is its own part of the pace now, ageWorkPercent.)
        return Contentment.workPercent(ownerId()) + Leader.pace(ownerId());
    }

    /** [batchA] A cold halves the pace of its work (Health). */
    @Override
    protected int healthPacePercent() {
        return Health.pacePercent(this);
    }

    /** The village's part of the pace on its card: the town's spirits and its leader's drive, each on its own. */
    @Override
    protected void villagePaceParts(java.util.List<PacePart> parts) {
        int spirits = Contentment.workPercent(ownerId());
        addPacePart(parts, "the town's spirits", spirits);
        addPacePart(parts, "how the leader drives the town", villageWorkPercent() - spirits);
    }

    /** The old are a little slower at their work, less so the more of it they have done (oldAgePercentAt). */
    @Override
    protected int ageWorkPercent() {
        return 0;                                                // the old work as they always have (oldAgePercentAt)
    }

    /**
     * What old age takes off the pace of an old folk's work, by its level at it: ten percent for
     * one new to the trade, a percent less for every three levels, and never more than five off
     * from level fifteen. Stiff knees are stiff knees, and the old walk slower besides (the gait,
     * refreshOldAgeGait); but a lifetime at the work has taught it to spare itself, so an old
     * master is still a good deal quicker than a young novice: thirty for its level less five
     * for its years at level thirty, against nought for the novice. At a flat ten off, every old
     * folk below level ten was slower at its trade than the greenest beginner.
     */
    public static int oldAgePercentAt(int level) {
        // No more: an old folk works on at its trade, as quick as it ever was, all its days. That is
        // just how it is in the village; its years cost it nothing at its work, its walk or its run.
        return 0;
    }

    /** Its mood on the pace line of its card, in its own word: "content", "fed up". */
    @Override
    protected String moodPaceWord() {
        return Persona.moodWord(persona.mood());
    }

    /** Its skills' part of the pace on its card: the town's research and its own knacks, each on its own. */
    @Override
    protected void skillPaceParts(java.util.List<PacePart> parts) {
        int research = CityTree.workPercent(ownerId(), stationTask());
        addPacePart(parts, "the town's research", research);
        addPacePart(parts, Library.paceWord(this), Library.workPercent(this));          // [library] its trade's book, read
        addPacePart(parts, FieldTools.paceWord(), FieldTools.workPercent(this));         // [fields] seed at its hip: a field sown a trip
        addPacePart(parts, "its spectacles", Keepsakes.spectaclesPercent(this));         // [individual] old eyes at close work
        addPacePart(parts, "its knacks", skillWorkPercent() - research - Library.workPercent(this) - FieldTools.workPercent(this)
            - Keepsakes.spectaclesPercent(this));
    }

    // ------------------------------ a level in every trade ------------------------

    /** What it has learned at each trade it has worked: a good farmer is not a good smith. */
    private final java.util.EnumMap<StationTask, Integer> tradeXp = new java.util.EnumMap<>(StationTask.class);

    @Override
    protected int levelXp() {
        StationTask t = stationTask();
        return t == StationTask.NONE ? 0 : tradeXp.getOrDefault(t, 0);
    }

    @Override
    protected void creditTrade(int amount) {
        StationTask t = stationTask();
        if (t != StationTask.NONE && amount > 0) tradeXp.merge(t, amount + FolkSkills.extraXp(this, amount)
            + Library.extraXp(this, amount)                                                           // [library] an apprentice who read its trade's book
            + Interviews.extraXp(this, amount), (a, b) -> Math.min(1_000_000, a + b));               // [interviews] turned down: a week's hard work
    }

    /** What a lesson at the school taught it of a trade (School): put by for the day it takes the trade up. */
    public void schoolXp(StationTask t, int amount) {
        if (t != StationTask.NONE && amount > 0) tradeXp.merge(t, amount, (a, b) -> Math.min(1_000_000, a + b));
    }

    /** [itemaudit] Tests: its experience at a trade, as it stands. */
    public int tradeXpOfForTests(StationTask t) {
        return t == StationTask.NONE ? 0 : tradeXp.getOrDefault(t, 0);
    }

    /** [itemaudit] Tests: a child learning this trade at a grown-up's side (as its morning's mentor would have it). */
    public void apprenticeForTests(StationTask t) {
        apprenticeTo = t;
    }

    /** Tests: so much experience at a trade, as though it had worked for it (xpForLevel gives a level's worth). */
    public void tradeXpForTests(StationTask t, int xp) {
        if (t == StationTask.NONE) return;
        tradeXp.put(t, Math.max(0, xp));
        refreshLevelPerks();
    }

    /** Its level at a trade (nought for one it has never worked). */
    public int tradeLevel(StationTask t) {
        return t == StationTask.NONE ? 0 : levelFor(tradeXp.getOrDefault(t, 0));
    }

    /** Every trade it has worked, best first: "farmer 12, miner 3". */
    public String tradeLevels() {
        java.util.List<java.util.Map.Entry<StationTask, Integer>> all = new java.util.ArrayList<>(tradeXp.entrySet());
        all.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<StationTask, Integer> e : all) {
            if (e.getValue() <= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey().title.toLowerCase(java.util.Locale.ROOT)).append(' ').append(levelFor(e.getValue()));
        }
        return sb.toString();
    }

    @Override
    protected void tradeTakenUp(StationTask from, StationTask to) {
        if (from == StationTask.GUARD && to != StationTask.GUARD) WatchKit.handBack(this, "off the watch");   // [guard-kit] the town's kit
        if (from == StationTask.CAVE && to != StationTask.CAVE && to != StationTask.GUARD) CaveDwellers.handBack(this, "out of the caves");   // [caves]
        if (from == StationTask.DIVER && to != StationTask.DIVER) Divers.handBack(this, "out of the water");   // [diver] its helmet to the stores
        if (tickCount < 40 || to == StationTask.NONE || !persona.rolled()) return;    // loading, or not settled yet
        long day = level().getDayTime() / 24000L;
        int lv = tradeLevel(to);
        persona.remember(day, from == StationTask.NONE
            ? "I took up " + to.label + (lv > 0 ? ", where I'd worked before" : " for the first time")
            : "I went from " + from.label + " to " + to.label + (lv > 0 ? " — back to an old trade (level " + lv + ")" : ", new to it"), 3);
    }

    /** A trade that suits its nature goes quicker; one that does not, slower (Skill). */
    @Override
    protected int personalityWorkPercent() {
        return Skill.percent(this);
    }

    /** The city's research and its own knacks (CityTree, FolkSkills). */
    @Override
    protected int skillWorkPercent() {
        return CityTree.workPercent(ownerId(), stationTask()) + FolkSkills.workPercent(this)
            + Library.workPercent(this)                                                         // [library] its trade's book, read
            + FieldTools.workPercent(this)                                                      // [fields] its seed satchel
            + Keepsakes.spectaclesPercent(this)                                                 // [individual] spectacles on old eyes
            + Ethos.workPercent(ownerId(), stationTask());                                      // [identity] a practical town; Golden Fields, Seafarers
    }

    /** A good mood makes for quick hands, a black one for slow ones. */
    @Override
    protected int moodWorkPercent() {
        int m = persona.mood();
        return m >= 80 ? 8 : m >= 65 ? 4 : m < 30 ? -10 : m < 45 ? -4 : 0;
    }

    /** [individual] Tests: its look at whether its dream has come true, now. */
    public void dreamCheckForTests() { dreamCameTrue(); }

    /** Has what it hoped for come true? Once it has, it is remembered for good. */
    private void dreamCameTrue() {
        if (persona.ambitionMet() || !persona.rolled()) return;
        UUID village = ownerId();
        boolean met = switch (persona.ambition()) {
            case MASTER -> veteranLevel() >= 10;
            case FAMILY -> life.children() >= 2;
            case FRIENDS -> life.friends().size() >= 5;
            case DIAMOND -> countCarried(st -> st.is(net.minecraft.world.item.Items.DIAMOND)) > 0;
            case NETHER -> village != null && Villages.ageOf(village) == Villages.Age.NETHER;
            case GREAT_WORK -> village != null && Villages.greatWorks(village) >= 1;
            case GARDEN -> persona.flowersPlanted() >= 6;
            case WELL_FED -> village != null && villageCentre != null
                && level() instanceof net.minecraft.server.level.ServerLevel server
                && persona.since() >= 0 && level().getDayTime() / 24000L - persona.since() >= 6
                && Villages.stock(server, villageCentre, Villages.Task.FOOD, Villages.storesRadius(village))
                    >= 2 * com.jrpetty.mcassistant.village.VillageMath.foodWanted(Villages.headcount(village));
            // [individual] The dreams of a life (Dreams).
            case MARRY, SEE_THE_SEA, OWN_HOUSE, WRITE_BOOK, GO_NETHER, LEAD, BIG_FAMILY, RICH -> Dreams.met(this);
        };
        if (!met) return;
        long day = level().getDayTime() / 24000L;
        persona.meetAmbition();
        persona.remember(day, FolkTalk.cap(persona.ambition().done), 10);
        Dreams.cameTrue(this, day);                    // [individual] days of delight, and a new dream after them
        if (village != null) Villages.tell(village, day, Dreams.news(this));
        FolkTalk.speak(this, "I did it! " + FolkTalk.cap(persona.ambition().done) + "!");
        if (level() instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                getX(), getY() + 2.0, getZ(), 12, 0.5, 0.4, 0.5, 0.0);
        }
        playSound(net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE, 1.0F, 1.0F);
        refreshMood();
    }

    /** Right-click to talk; sneak and right-click for its pack and its record. */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                               net.minecraft.world.InteractionHand hand) {
        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || player.isShiftKeyDown()
                || held.is(net.minecraft.world.item.Items.NAME_TAG) || held.is(net.minecraft.world.item.Items.LEAD)
                || held.getItem() instanceof com.jrpetty.mcassistant.item.ZoneMarkerItem) {
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (StoreFloor.serveAPlayer(this, sp)) return net.minecraft.world.InteractionResult.SUCCESS;   // [econ-store]
            if (Buskers.tipFrom(this, sp, held)) return net.minecraft.world.InteractionResult.SUCCESS;     // [arms] a coin in the busker's hat
            if (Auctions.serveAPlayer(this, sp)) return net.minecraft.world.InteractionResult.SUCCESS;     // [fleet] the auctioneer's bid screen
            FolkTalk.open(this, sp);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** Being hit is not forgotten — by it, or by anybody who saw. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean took = super.hurt(source, amount);
        if (took) Individual.wounded(this, source, amount);   // [individual] a real wound leaves a scar
        if (took && !level().isClientSide && source.getEntity() instanceof net.minecraft.world.entity.player.Player p
                && persona.rolled()) {
            long day = level().getDayTime() / 24000L;
            String who = p.getName().getString();
            persona.hurt(day);
            persona.feelFor(p.getUUID(), who, -25);
            persona.remember(day, who + " hit me", -6);
            stopTalking();
            if (isFollowing(p)) stopFollowing();
            FolkTalk.speak(this, FolkTalk.ouch(this));
            for (VillageFolkEntity saw : level().getEntitiesOfClass(VillageFolkEntity.class, getBoundingBox().inflate(12.0),
                    f -> f != this && f.isAlive() && f.persona.rolled())) {
                saw.persona.feelFor(p.getUUID(), who, -8);
            }
            refreshMood();
        } else if (took && !level().isClientSide && !showcase
                && source.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
                && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity monster) {
            // Set on by a monster: it shouts for the watch, and the nearest guard comes running
            // (Patrols). With no guard to shout for, it shouts for whoever is near enough to help.
            boolean watchComing = Patrols.cryForHelp(this, monster);
            if (watchComing) lastCryTick = tickCount;
            if (!persona.rolled()) return took;
            beset = monster.getUUID();
            besetUntil = tickCount + 600;
            net.minecraft.world.entity.player.Player near = level().getNearestPlayer(this, 24.0);
            if (!watchComing && near != null && !near.isSpectator() && tickCount - lastCryTick > 100) {
                lastCryTick = tickCount;
                String you = near.getName().getString();
                String what = monster.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Help! " + you + ", help!", "A " + what + "! Help me!",
                    you + "! Over here — a " + what + "!", "Get it off me! " + you + "!"));
            }
        }
        return took;
    }

    /** The monster that set on it lately, while it is still in danger. */
    @javax.annotation.Nullable private UUID beset;
    private int besetUntil, lastCryTick = -1000;

    /** Was it this monster that had it cornered, just now? */
    public boolean besetBy(UUID monster) {
        return beset != null && beset.equals(monster) && tickCount < besetUntil;
    }

    /**
     * A player killed the monster that was on it: that is not a thing a folk forgets.
     */
    public void rescuedBy(net.minecraft.world.entity.player.Player p, String monster) {
        beset = null;
        if (!persona.rolled()) return;
        long day = level().getDayTime() / 24000L;
        String you = p.getName().getString();
        persona.feelFor(p.getUUID(), you, 12);
        persona.remember(day, you + " saved my life", 9);
        UUID village = ownerId();
        if (village != null) {
            Villages.tell(village, day, you + " saved " + displayNameCap() + " from a " + monster);
            Standing.stir(village, p.getUUID());
        }
        for (VillageFolkEntity f : level().getEntitiesOfClass(VillageFolkEntity.class, getBoundingBox().inflate(16.0),
                f -> f != this && f.isAlive() && f.persona.rolled())) {
            int warmth = f.life.affinity(getUUID());
            if (warmth >= Social.FRIEND || getUUID().equals(f.life.partner())) f.persona.feelFor(p.getUUID(), you, 5);
        }
        getLookControl().setLookAt(p, 30.0F, 30.0F);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "You saved my life, " + you + "! I'll never forget it.",
            "Thank you, " + you + "! I thought I was done for.", "Phew… I owe you one, " + you + ". A big one."));
        if (level() instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, getX(), getY() + 2.1, getZ(),
                4, 0.4, 0.3, 0.4, 0.0);
        }
    }

    /** A death in the village is news, and the ones who loved it remember. */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        if (!level().isClientSide) WarAndPeace.died(this, cause, level().getDayTime() / 24000L);   // [war-peace] lost to the war (before its errand is let go)
        if (!level().isClientSide) Fashion.died(this);                   // [fashion] what it wore falls where it fell, with its pack
        if (!level().isClientSide) Individual.died(this);                // [individual] its keepsake to its eldest child; mourned
        if (trip != null && level() instanceof net.minecraft.server.level.ServerLevel road) Caravans.abandon(road, this);
        if (level() instanceof net.minecraft.server.level.ServerLevel horses) Riding.fell(horses, this);   // a horse it had out (Riding)
        if (expedition != null && level() instanceof net.minecraft.server.level.ServerLevel land) {
            UUID home = ownerId();
            if (home != null) Villages.tell(home, level().getDayTime() / 24000L, displayNameCap() + (expedition.delve() != null
                ? " was lost in the caves " : expedition.venture() != null ? " was lost on the road " : " was lost while scouting the ")
                + expedition.heading());          // [caves] [emerald]
            Scouts.abandon(land, this);
        }
        UUID village = ownerId();
        if (!level().isClientSide && village != null && !showcase) {
            long day = level().getDayTime() / 24000L;
            Raids.fell(village);
            Contentment.loss(village, day);
            int age = ageYears();
            // [economy] What took it, in words (Mishap): "by misfortune" said nothing about what kills folk.
            // [watch-clears] Under the bell, what took it and the raid after it, not "when the raiders came" for a fall (WatchClears.how).
            String how = passing ? "of old age" : WatchClears.how(village, cause);
            Mishap.record(village, day, level().getGameTime(), how);                    // [economy] for the books' daily line and the watch
            WatchClears.fell(this, how, day);                                           // [watch-clears] where it fell, doing what
            if (!passing) Plaques.fell(this, how, day);                                 // [batchD] a plaque where a hero fell (Plaques)
            com.jrpetty.mcassistant.village.Ledger.buried(village, new com.jrpetty.mcassistant.village.Ledger.Grave(
                displayNameCap(), bornDay, day, how, life.parents(), life.partnerName(), stationTask().title));
            Villages.tell(village, day, passing
                ? displayNameCap() + " died peacefully in their sleep, aged " + age
                : displayNameCap() + " died, aged " + age + ", " + how);
            Gatherings.mourn(village, displayNameCap(), day);
            Homes.left(village, getUUID());
            Bank.left(village, this, true);              // its savings at the bank to its partner, a child, or the village
            Annals.died(village, how);
            // The leader gone: an election to choose another (Elections).
            if (getUUID().equals(Villages.elder(village)) && level() instanceof net.minecraft.server.level.ServerLevel lost) {
                Elections.vacancy(lost, village, displayNameCap(), day);
                Villages.elderGone(village, getUUID());
            }
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f == this) continue;
                int warmth = f.life.affinity(getUUID());
                boolean partner = getUUID().equals(f.life.partner());
                if (warmth >= Social.FRIEND || partner) {
                    f.persona.remember(day, "I lost " + displayNameCap(), 8);
                    f.griefDay = day;
                    f.griefFor = displayNameCap();
                }
                // A widow or widower may love again one day; until now they stayed "partnered"
                // to the dead for good, and could neither court nor raise another child.
                if (partner) f.life.widowed();
            }
        }
        super.die(cause);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new com.jrpetty.mcassistant.entity.goal.TalkGoal(this));
        this.goalSelector.addGoal(1, new com.jrpetty.mcassistant.entity.goal.CompanionGoal(this));
        this.goalSelector.addGoal(1, new com.jrpetty.mcassistant.entity.goal.GuideGoal(this));
        // The watch turns a banished player out of the village on sight (Laws).
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
            this, net.minecraft.world.entity.player.Player.class, 10, true, false,
            e -> stationTask() == StationTask.GUARD && villageCentre != null && Laws.outlaw(ownerId(), e)
                && e.blockPosition().closerThan(villageCentre, 48)));
    }

    // ------------------------------ who they are, and who they like ----------

    private final Social.Life life = new Social.Life();
    private int beats;
    private long driftDay = -1;
    private int socialWalkTick = -1000;
    /** Whom it is grieving for, and since when. */
    long griefDay = -100;
    String griefFor = "";
    /** The last day it had words with somebody. */
    private long quarrelDay = -10;

    /** [batchF] The day it last had words with somebody (Wardens: a quarrel for the warden to settle). */
    public long quarrelledOn() { return quarrelDay; }

    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_SOCIAL =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    /** How well off it is (Wealth.Tier), for its clothes. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_WEALTH =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    public int clientWealth() { return this.entityData.get(DATA_WEALTH); }

    private void showTheWealth() {
        int tier = Wealth.tier(this).ordinal();
        if (this.entityData.get(DATA_WEALTH) != tier) this.entityData.set(DATA_WEALTH, tier);
    }

    /** Which of the village colours its watch wears; -1 for a folk of no village. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_BANNER =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SOCIAL, "");
        builder.define(DATA_BANNER, -1);
        builder.define(DATA_WEALTH, 1);
        builder.define(DATA_CHILD, false);
        builder.define(DATA_STYLE, 0L);                 // [fashion]
        builder.define(DATA_LOOK, 0L);                  // [individual]
        builder.define(DATA_MARKS, 0);
        builder.define(DATA_MANNER, 0);
    }

    // ------------------------------ [individual] its own: face, body, manner, life ------------

    /** Its genes, its marks, its fears, habits, dream, keepsake and story (Individual). Saved with it. */
    private final Individual.Self self = new Individual.Self();

    public Individual.Self individual() { return self; }

    /** Its face as it is (Looks.pack), its marks (Individual.marks), and how it carries itself (Manner.pack): the
     *  client draws it from these three numbers, sent only when they change. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Long> DATA_LOOK =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.LONG);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_MARKS =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_MANNER =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    public long clientLook() { return this.entityData.get(DATA_LOOK); }

    public int clientMarks() { return this.entityData.get(DATA_MARKS); }

    public int clientManner() { return this.entityData.get(DATA_MANNER); }

    public void showLook(long look, int marks) {
        if (this.entityData.get(DATA_LOOK) != look) this.entityData.set(DATA_LOOK, look);
        if (this.entityData.get(DATA_MARKS) != marks) this.entityData.set(DATA_MARKS, marks);
    }

    public void showManner(int manner) {
        if (this.entityData.get(DATA_MANNER) != manner) this.entityData.set(DATA_MANNER, manner);
    }

    /**
     * [individual] Its height in its hitbox, a little: a short folk's box is a touch lower, a tall one's never higher
     * than a villager's, so the tallest still walks through a door two blocks high and lies in a bed like anybody.
     * Its picture is drawn at its full height (client/FolkRenderer).
     */
    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions base = super.getDefaultDimensions(pose);
        if (this.entityData == null || pose == net.minecraft.world.entity.Pose.SLEEPING) return base;
        long look = this.entityData.get(DATA_LOOK);
        if (!Looks.known(look)) return base;
        return base.scale(1.0F, Individual.hitboxScale(Looks.heightOfStep(Looks.heightStepOf(look))));
    }

    /** [fashion] Its style (Fashion): its colours, what it wears of its own, how it stands with the season's look. */
    private final Style style = new Style();

    public Style style() { return style; }

    /** [fashion] Its style, packed for the client to draw (Style.pack, client/FashionLayer). */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Long> DATA_STYLE =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.LONG);

    public long clientStyle() { return this.entityData.get(DATA_STYLE); }

    public void showStyle(long packed) {
        if (this.entityData.get(DATA_STYLE) != packed) this.entityData.set(DATA_STYLE, packed);
    }

    /** A child of the village: small, at play, and no trade until it is grown. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_CHILD =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    /** How many days a child takes to grow up. */
    public static final int GROW_DAYS = 3;
    /** The day it was born; UNKNOWN for a folk that came into the world grown. */
    public static final long UNKNOWN = Long.MIN_VALUE;
    private long bornDay = UNKNOWN;
    private boolean tagIt;

    @Override
    public boolean isBaby() { return this.entityData.get(DATA_CHILD); }

    public void setChild(boolean child) {
        this.entityData.set(DATA_CHILD, child);
        refreshDimensions();
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_CHILD.equals(key) || DATA_LOOK.equals(key)) refreshDimensions();   // [individual] its height
    }

    /** The village's colours, for the client: a guard's tabard and shield are dyed in them. */
    public int clientBanner() { return this.entityData.get(DATA_BANNER); }

    private void flyTheColours() {
        UUID village = ownerId();
        int banner = village == null ? -1 : Math.floorMod(village.hashCode(), 64);
        if (this.entityData.get(DATA_BANNER) != banner) this.entityData.set(DATA_BANNER, banner);
    }

    /**
     * A folk stood up to be looked at (/village lineup): it belongs to no village,
     * works no ground and goes nowhere — it only wears its trade's clothes and holds
     * its trade's tool.
     */
    private boolean showcase;

    /** Its two meals a day (Meals). */
    private final Meals.Book meals = new Meals.Book();

    public Meals.Book meals() { return meals; }

    /** A folk of the showcase lineup: it stands for its picture and lives no life. */
    public boolean showcaseFolk() { return showcase; }

    @Override
    protected void ateFood(net.minecraft.world.item.ItemStack meal) {
        meals.ate(level().getGameTime(), meal.getHoverName().getString());
    }

    /** One meal's worth out of the village's stores and into the pack (Meals), booked; how many came. */
    public int mealFromTheStores() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return 0;
        return drawFromTheStores(server, Meals.FOOD, 1);
    }

    public void makeShowcase(StationTask trade) {
        this.showcase = true;
        setNoAi(true);
        setInvulnerable(true);
        setJob(trade);
    }

    public boolean isShowcase() { return showcase; }

    @Override
    protected boolean plainNameTag() { return showcase; }

    /** This folk's personality, friends, partner and family. */
    public Social.Life life() { return life; }

    /** "traits|partner|friends|rivals|family", for the folk's own screen. */
    public String clientSocial() { return this.entityData.get(DATA_SOCIAL); }

    /** Off work right now: on its break, or the day is over (the watch never is). */
    public boolean offWorkNow() { return !onShift() || onBreak(); }

    /**
     * Ten seconds of village life. Whoever this folk is standing with — the two
     * nearest, within a few steps — it warms to (or, a grump, takes against) by
     * how they are both made; a friend gets a smile, a partner a heart, a rival a
     * scowl; and a generous folk hands a ration to a friend who has none. Once a day
     * every feeling drifts back a little, so friendships are the ones kept up.
     */
    public void socialBeat() {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        long day = level().getDayTime() / 24000L;
        if (driftDay != day) {
            if (driftDay >= 0) life.drift();
            driftDay = day;
            // Time takes the edge off a grudge, quicker for an easygoing or generous soul.
            int mend = life.has(Social.Trait.EASYGOING) || life.has(Social.Trait.GENEROUS) ? 2
                : life.has(Social.Trait.GRUMPY) ? (int) (day % 2) : 1;
            if (persona.rolled()) persona.mend(day, mend);
        }
        boolean offWork = offWorkNow();
        java.util.List<VillageFolkEntity> near = level().getEntitiesOfClass(VillageFolkEntity.class,
            getBoundingBox().inflate(4.0), f -> f != this && f.isAlive() && village.equals(f.ownerId()));
        near.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(this)));
        for (int i = 0; i < Math.min(2, near.size()); i++) {
            VillageFolkEntity other = near.get(i);
            if (!other.life.rolled()) continue;
            int delta = FolkSkills.warmth(this, Social.warmth(life, other.life, other.stationTask() == stationTask(), offWork, getRandom()));
            life.feel(other.getUUID(), other.displayNameCap(), delta);
            if (isSleeping() || other.isSleeping()) continue;
            int now = life.affinity(other.getUUID());
            boolean partners = other.getUUID().equals(life.partner());
            if (offWork) getLookControl().setLookAt(other, 30.0F, 30.0F);
            if (!partners && life.partner() == null && other.life.partner() == null && !isBaby() && !other.isBaby()
                    && now >= 75 && other.life.affinity(getUUID()) >= 75
                    && !life.parents().contains(other.displayNameCap()) && !other.life.parents().contains(displayNameCap())) {
                courted(other, server, village);
            }
            if (offWork && persona.rolled() && other.persona.rolled() && tickCount - lastChatterTick > 1200
                    && getRandom().nextInt(6) == 0 && level().getNearestPlayer(this, 16.0) != null) {
                lastChatterTick = tickCount;
                FolkTalk.smallTalk(this, other);
            }
            // Word gets round: what one thinks of a player, its friends soon come to think too.
            if (persona.rolled() && other.persona.rolled() && !isBaby() && !other.isBaby() && now > Social.RIVAL
                    && getRandom().nextInt(3) == 0) {
                gossip(other, now >= Social.FRIEND, offWork, village);
            }
            if (partners && getRandom().nextInt(2) == 0) {
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
                    getX(), getY() + 2.2, getZ(), 1, 0.2, 0.1, 0.2, 0.0);
            } else if (now >= Social.FRIEND && getRandom().nextInt(3) == 0) {
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    getX(), getY() + 2.0, getZ(), 3, 0.3, 0.2, 0.3, 0.0);
                if (offWork) playSound(net.minecraft.sounds.SoundEvents.VILLAGER_AMBIENT, 0.6F,
                    0.9F + getRandom().nextFloat() * 0.3F);
            } else if (now <= Social.RIVAL && getRandom().nextInt(2) == 0) {
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
                    getX(), getY() + 2.0, getZ(), 1, 0.2, 0.1, 0.2, 0.0);
                if (offWork) playSound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 0.6F, 1.0F);
            }
            // A generous folk does not watch a friend go hungry.
            if (life.has(Social.Trait.GENEROUS) && now >= Social.FRIEND
                    && other.countFood() < 3 && countFood() >= 6 && shareARation(other)) {
                other.life.feel(getUUID(), displayNameCap(), 6);
                life.feel(other.getUUID(), other.displayNameCap(), 2);
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    other.getX(), other.getY() + 2.0, other.getZ(), 5, 0.3, 0.2, 0.3, 0.0);
            }
        }
        // A village that is going badly hears about it.
        if (offWork && !isSleeping() && !isBaby() && getRandom().nextInt(14) == 0
                && level().getNearestPlayer(this, 16.0) != null) {
            String moan = Contentment.grumble(server, village, getRandom());
            if (moan != null) FolkTalk.speak(this, moan);
        }
        // And now and then two who can't abide each other have words.
        quarrel(near, server, village, day, offWork);
        String line = life.clientLine();
        if (persona.rolled()) {
            line += "|" + Persona.moodWord(persona.mood()) + "|" + persona.hobby().doing + "|"
                + (persona.ambitionMet() ? "done — " + persona.ambition().done : persona.ambition().hope);
        }
        if (!line.equals(this.entityData.get(DATA_SOCIAL))) this.entityData.set(DATA_SOCIAL, line);
    }

    /**
     * Words between two who can't abide each other: rare (each folk a quarrel a day at most,
     * and one beat in eight when a rival is at hand off work), loud, and soon over. They face
     * each other, say their piece, and stalk off; both are the worse for it that day, and so
     * is the grudge. Once in a while, though, it clears the air.
     */
    private void quarrel(java.util.List<VillageFolkEntity> near, net.minecraft.server.level.ServerLevel server,
                         UUID village, long day, boolean offWork) {
        if (!offWork || quarrelDay >= day || isBaby() || isSleeping() || getRandom().nextInt(8) != 0) return;
        for (VillageFolkEntity other : near) {
            if (other.isBaby() || other.isSleeping() || other.quarrelDay >= day) continue;
            if (life.affinity(other.getUUID()) > Social.RIVAL) continue;
            quarrelDay = day;
            other.quarrelDay = day;
            getLookControl().setLookAt(other, 30.0F, 30.0F);
            other.getLookControl().setLookAt(this, 30.0F, 30.0F);
            net.minecraft.util.RandomSource r = getRandom();
            String[][] rows = {
                { "You took my spot at the well again!", "It's not YOUR spot!" },
                { "Your chickens were in my carrots!", "Prove it!" },
                { "Do you have to whistle all day?", "Do you have to complain all day?" },
                { "That was my bread and you know it.", "Finders keepers." },
                { "You never pull your weight.", "Says the one who naps at noon!" } };
            String[] row = rows[r.nextInt(rows.length)];
            FolkTalk.speak(this, row[0]);
            other.sayLater(row[1], 40);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, getX(), getY() + 2.1, getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, other.getX(), other.getY() + 2.1, other.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            playSound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 0.9F, 0.9F);
            if (r.nextInt(5) == 0) {
                // It clears the air.
                life.feel(other.getUUID(), other.displayNameCap(), 25);
                other.life.feel(getUUID(), displayNameCap(), 25);
                other.sayLater(FolkTalk.pick(r, "...Oh, let's not fight. Truce?", "Fine. I'm sorry. There."), 100);
                persona.remember(day, "I made it up with " + other.displayNameCap(), 4);
            } else {
                life.feel(other.getUUID(), other.displayNameCap(), -5);
                other.life.feel(getUUID(), displayNameCap(), -5);
                persona.remember(day, "I had words with " + other.displayNameCap(), 3);
                // Then off in a huff, the other way.
                double dx = getX() - other.getX(), dz = getZ() - other.getZ();
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                BlockPos away = surfaceAt((int) (getX() + dx / len * 10), (int) (getZ() + dz / len * 10));
                if (away != null) walkTo(away, 1.0D);
            }
            refreshMood();
            other.refreshMood();
            return;
        }
    }

    /** Two who have grown as close as two folk get pledge themselves; the village
     *  holds the wedding the next evening. */
    private void courted(VillageFolkEntity other, net.minecraft.server.level.ServerLevel server, UUID village) {
        long day = level().getDayTime() / 24000L;
        life.partnerWith(other.getUUID(), other.displayNameCap());
        other.life.partnerWith(getUUID(), displayNameCap());
        Villages.tell(village, day, displayNameCap() + " and " + other.displayNameCap() + " are to be wed");
        Gatherings.pledged(village, this, other, day);
        persona.remember(day, "I asked " + other.displayNameCap() + " to marry me", 9);
        other.persona.remember(day, displayNameCap() + " asked me to marry them", 9);
        getLookControl().setLookAt(other, 30.0F, 30.0F);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Will you… marry me?", "I've something to ask you…"));
        other.sayLater(FolkTalk.pick(getRandom(), "Yes! Yes, of course!", "I thought you'd never ask!"), 50);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, getX(), getY() + 2.2, getZ(),
            6, 0.4, 0.3, 0.4, 0.0);
    }

    /** Tell another folk what this one thinks of the player it has most to say about. */
    public void gossip(VillageFolkEntity other, boolean trusted, boolean aloud, UUID village) {
        UUID about = persona.talkedAbout();
        if (about == null) return;
        String name = persona.nameOf(about);
        int mine = persona.affinity(about);
        boolean news = !other.persona.knows(about);
        if (!other.persona.hearsay(about, name, mine, displayNameCap(), trusted)) return;
        Standing.stir(village, about);
        if (!aloud || isSleeping() || other.isSleeping() || tickCount - lastChatterTick < 600
                || level().getNearestPlayer(this, 16.0) == null || getRandom().nextInt(3) != 0) return;
        lastChatterTick = tickCount;
        getLookControl().setLookAt(other, 30.0F, 30.0F);
        FolkTalk.gossip(this, other, name, mine, news);
    }

    /** A child's day: play, and stay near its mother or father; bed at dark. */
    private void childhood() {
        long day = level().getDayTime() / 24000L;
        if (bornDay == UNKNOWN) bornDay = day;
        if (day - bornDay >= GROW_DAYS) {
            setChild(false);
            persona.remember(day, "I grew up", 8);
            Individual.cameOfAge(this, day);                  // [individual] its own hair, its letters, a child's fears outgrown
            UUID village = ownerId();
            // An apprentice takes up the trade it learned, unless the village has more than enough
            // hands at it already, and starts it with a few years' knack already in its hands.
            String learned = null;
            // Schooled (School): it takes up the trade it leaned to at school, what it learned there in
            // hand (and its apprenticeship's knack too, where that was the same trade).
            StationTask schooled = village == null ? StationTask.NONE : School.graduate(this, day);
            if (schooled != StationTask.NONE) {
                setStation(blockPosition(), schooled);
                if (apprenticeTo == schooled) awardXp(APPRENTICE_XP);
                learned = schooled.title.toLowerCase(java.util.Locale.ROOT);
            }
            if (village != null && learned == null && apprenticeTo != StationTask.NONE && apprenticeTo != StationTask.GUARD
                    && !Villages.overStaffed(village, apprenticeTo)) {
                setStation(blockPosition(), apprenticeTo);
                awardXp(APPRENTICE_XP);
                learned = apprenticeTo.title.toLowerCase(java.util.Locale.ROOT);
                String teacher = mentorName();
                persona.remember(day, "I learned my trade from " + teacher, 7);
                if (level() instanceof net.minecraft.server.level.ServerLevel sl && mentor != null
                        && sl.getEntity(mentor) instanceof VillageFolkEntity m) {
                    m.persona.remember(day, "I taught " + displayNameCap() + " the trade", 6);
                    m.life.feel(getUUID(), displayNameCap(), 10);
                }
            }
            // No trade learned: the leader sets it to the work the village most needs.
            boolean called = false;
            if (village != null && learned == null) {
                StationTask t = Leader.calledUp(this, day);
                if (t != StationTask.NONE) {
                    setStation(blockPosition(), t);
                    learned = t.title.toLowerCase(java.util.Locale.ROOT);
                    called = true;
                }
            }
            if (village != null) Assemblies.cameOfAge(village, this, learned);
            if (village != null) Villages.tell(village, day, displayNameCap() + " grew up"
                + (learned == null ? "" : schooled != StationTask.NONE ? " and went to work as " + School.a(schooled) + ", level "
                    + veteranLevel() + " from the school"
                    : called ? " and went to work as a " + learned
                    : " and became a " + learned + ", as " + mentorName() + " taught them"));
            FolkTalk.speak(this, schooled != StationTask.NONE
                ? FolkTalk.pick(getRandom(), "I'm " + School.a(schooled) + " at last — I learned it at school!", "All grown up, and "
                    + School.a(schooled) + " already: level " + veteranLevel() + "!")
                : called
                ? FolkTalk.pick(getRandom(), "All grown up — and I'm to be a " + learned + "!", "A " + learned + ", they say. I'll do my best.")
                : learned != null
                ? FolkTalk.pick(getRandom(), "I'm a " + learned + " now, like " + mentorName() + "!", "All grown up, and I know my trade.")
                : FolkTalk.pick(getRandom(), "I'm all grown up!", "Time I learned a trade.", "No more playing — I'm a grown-up now."));
            return;
        }
        if (level().isNight() || Raids.underAlarm(ownerId())) {
            if (!isSleeping() && peekJob() == null) bedtime();
            return;
        }
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || villageCentre == null) return;
        // Mornings, once it is old enough: at a grown-up's side at work, learning the trade.
        if (day - bornDay >= LawBook.apprenticeFrom(ownerId(), 1) && level().getDayTime() % 24000L < 7000L && apprentice(server)) return;   // [identity] the apprentice age
        // Afternoons in the park, when the town has one (Park).
        if (Park.play(this, server)) return;
        // Tag with the other children, when there are any.
        java.util.List<VillageFolkEntity> kids = level().getEntitiesOfClass(VillageFolkEntity.class,
            getBoundingBox().inflate(20.0), f -> f != this && f.isAlive() && f.isBaby() && !f.isSleeping());
        if (!kids.isEmpty() && getRandom().nextInt(3) != 0) {
            kids.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(this)));
            VillageFolkEntity near = kids.get(0);
            if (tagIt) {
                if (distanceToSqr(near) < 2.0 * 2.0) {
                    tagIt = false;
                    near.tagIt = true;
                    swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Tag! You're it!", "Got you!", "You're it!"));
                } else {
                    getNavigation().moveTo(near, 1.2D);
                }
                return;
            }
            VillageFolkEntity it = null;
            for (VillageFolkEntity k : kids) if (k.tagIt) { it = k; break; }
            if (it == null) { tagIt = getRandom().nextInt(4) == 0; return; }
            if (distanceToSqr(it) < 8.0 * 8.0) {
                double dx = getX() - it.getX(), dz = getZ() - it.getZ();
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                BlockPos away = surfaceAt((int) (getX() + dx / len * 8), (int) (getZ() + dz / len * 8));
                if (away != null && away.distSqr(villageCentre) < 40 * 40) walkTo(away, 1.25D);
                if (getRandom().nextInt(5) == 0) FolkTalk.speak(this, FolkTalk.pick(getRandom(),
                    "Can't catch me!", "Hee hee!", "Too slow!"));
            }
            return;
        }
        // Otherwise near a parent.
        for (AssistantEntity a : Villages.folkOf(ownerId())) {
            if (!(a instanceof VillageFolkEntity parent) || parent == this) continue;
            if (!life.parents().contains(parent.displayNameCap())) continue;
            if (distanceToSqr(parent) > 4.0 * 4.0) walkTo(parent.blockPosition(), 1.0D);
            else getLookControl().setLookAt(parent, 30.0F, 30.0F);
            return;
        }
        if (getNavigation().isDone() && getRandom().nextInt(3) == 0) {
            BlockPos to = surfaceAt(villageCentre.getX() + getRandom().nextInt(17) - 8,
                villageCentre.getZ() + getRandom().nextInt(17) - 8);
            if (to != null) walkTo(to, 1.0D);
        }
    }

    public long bornDay() { return bornDay; }

    /** One of the village's founders, still living rent-free in the house the village built it: it
     *  pays rent from the first payday it can afford it, and never goes back to free (Homes). */
    private boolean rentFree;

    public boolean rentFree() { return rentFree; }

    public void rentFree(boolean free) { this.rentFree = free; }

    // ------------------------------ growing old --------------------------------

    /** Years a child grows a day (it is grown at eighteen, three days old). */
    static final int CHILD_YEARS_A_DAY = 6;
    /**
     * [ageing] Days a grown folk takes to age a year: a year every fifth day. At two years a day
     * every founder grew old together and the lot of them died between about the fifteenth day and
     * the fortieth; at a year in five a founder lives most of a long game (a hundred and twenty-five
     * days at the least, four hundred-odd at the most, about two hundred and seventy as a rule) and a
     * child born in the village two hundred and sixty to four hundred and fifteen. Childhood is as
     * quick as ever: school, apprenticeships and families want it.
     */
    public static final int DAYS_A_YEAR = 5;
    /** [ageing] The founders' years when the village began: eighteen to forty-five, a spread of them. */
    static final int FOUNDER_YOUNGEST = 18, FOUNDER_OLDEST = 45;
    /** The age folk are old at. It costs them nothing at their work (oldAgePercentAt). */
    public static final int OLD_AT = 60;
    /** What an apprenticeship is worth when it is over: a few levels' knack. */
    static final int APPRENTICE_XP = 400;

    /** Whose apprentice it is (a child), and in what trade. */
    @Nullable private UUID mentor;
    private StationTask apprenticeTo = StationTask.NONE;
    private String mentorName = "";
    private boolean frailTold;
    /** It is dying of old age, in its sleep. */
    private boolean passing;

    /** How old it is, in years as folk count them. The founders were grown when the village began. */
    public int ageYears() {
        long day = level().getDayTime() / 24000L;
        if (bornDay == UNKNOWN) {
            if (isBaby()) bornDay = day;
            else {
                // [ageing] A founder's years, eighteen to forty-five, and which of the five days of its
                // year it came on: so the founders neither grow old together nor all keep their
                // birthdays on the one day.
                int h = getUUID().hashCode();
                bornDay = day - daysOldAt(FOUNDER_YOUNGEST + Math.floorMod(h, FOUNDER_OLDEST - FOUNDER_YOUNGEST + 1))
                    - Math.floorMod(h >> 10, DAYS_A_YEAR);
            }
        }
        long days = Math.max(0, day - bornDay);
        return isBaby() ? childYears(days) : grownYears(days);
    }

    /** [ageing] A child's years at so many days old: six to the day, and seventeen at the most. */
    public static int childYears(long days) {
        return (int) Math.min(17, Math.max(0, days) * CHILD_YEARS_A_DAY);
    }

    /** [ageing] A grown folk's years at so many days old: eighteen the day it is grown, a year more every fifth day after. */
    public static int grownYears(long days) {
        return (int) (18 + Math.max(0, days - GROW_DAYS) / DAYS_A_YEAR);
    }

    /** [ageing] How many days old a folk is on the day it comes to so many years (a child, the first day it is that old or more). */
    public static long daysOldAt(int years) {
        if (years < 18) return (Math.max(0, years) + CHILD_YEARS_A_DAY - 1) / CHILD_YEARS_A_DAY;
        return GROW_DAYS + (long) (years - 18) * DAYS_A_YEAR;
    }

    /**
     * [ageing] A grown folk's born day as it was saved while grown folk aged two years a day, put
     * so that it is the same age today and goes on at a year every fifth day from here: nobody
     * comes out of the change of pace thirty years younger than it went in.
     */
    static long bornAtTheOldPace(long born, long today) {
        long days = today - born;
        if (days <= GROW_DAYS) return born;
        return today - daysOldAt((int) Math.min(200, 18 + (days - GROW_DAYS) * 2));
    }

    /** The age it will live to: seventy to a hundred (a tenth more where the town keeps Healers: CityTree). */
    public int lifespan() { return CityTree.lifespan(ownerId(), 70 + Math.floorMod(getUUID().hashCode() >> 5, 31)); }

    public boolean isOld() { return !isBaby() && ageYears() >= OLD_AT; }

    /** Once a day: very old folk grow frail, and at the end of their years die in their sleep. */
    private void growingOld(long day) {
        if (isBaby() || showcase || ownerId() == null) return;
        int age = ageYears(), end = lifespan();
        if (age >= end - 4 && !frailTold) {
            frailTold = true;
            Villages.tell(ownerId(), day, displayNameCap() + " is very frail now, at " + age);
        }
        long t = level().getDayTime() % 24000L;
        if (age >= end && (isSleeping() || t < 1500L)) {
            passing = true;
            kill();
        }
    }

    private String mentorName() {
        return mentorName.isEmpty() ? "my teacher" : mentorName;
    }

    /**
     * A child's morning at a grown-up's side: a parent's if one is at work, else whoever is
     * best at the trade the village most needs. Watching, fetching, having a go. Returns
     * true while it is with its teacher.
     */
    private boolean apprentice(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        if (village == null) return false;
        VillageFolkEntity teacher = mentor == null ? null
            : server.getEntity(mentor) instanceof VillageFolkEntity m && m.isAlive() && !m.isBaby() ? m : null;
        if (teacher == null || teacher.stationTask() == StationTask.NONE) {
            teacher = chooseMentor(village);
            if (teacher == null) return false;
            mentor = teacher.getUUID();
            mentorName = teacher.displayNameCap();
            apprenticeTo = teacher.stationTask();
            persona.remember(level().getDayTime() / 24000L, "I started learning " + apprenticeTo.title.toLowerCase(java.util.Locale.ROOT)
                + " from " + mentorName, 5);
        }
        mentorName = teacher.displayNameCap();
        apprenticeTo = teacher.stationTask();
        if (!teacher.onShift() || teacher.isSleeping() || teacher.trip() != null) return false;
        hobbyNow = "learning " + apprenticeTo.title.toLowerCase(java.util.Locale.ROOT) + " from " + mentorName;
        if (distanceToSqr(teacher) > 3.0 * 3.0) {
            if (getNavigation().isDone() || tickCount % 40 == 0) walkTo(teacher.blockPosition(), 1.1D);
            return true;
        }
        getLookControl().setLookAt(teacher, 30.0F, 30.0F);
        if (getRandom().nextInt(12) == 0) swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (getRandom().nextInt(40) == 0 && level().getNearestPlayer(this, 16.0) != null) {
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Like this?", "Can I have a go?", "Why do you do it that way?",
                "I'm going to be a " + apprenticeTo.title.toLowerCase(java.util.Locale.ROOT) + " too!"));
            teacher.sayLater(FolkTalk.pick(getRandom(), "That's it — gently now.", "Watch my hands.", "You'll get the knack.",
                "Not bad at all!"), 40);
        }
        return true;
    }

    /** A parent at work, or else the most practised hand at the trade the village needs most. */
    @Nullable
    private VillageFolkEntity chooseMentor(UUID village) {
        VillageFolkEntity parent = null, best = null;
        StationTask wanted = Villages.needed(village);
        int bestLevel = -1;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == this || f.isBaby() || f.stationTask() == StationTask.NONE) continue;
            if (f.stationTask() == StationTask.GUARD) continue;
            if (life.parents().contains(f.displayNameCap()) && (parent == null || !Villages.overStaffed(village, f.stationTask()))) parent = f;
            if (f.stationTask() == wanted && f.veteranLevel() > bestLevel) { bestLevel = f.veteranLevel(); best = f; }
        }
        return parent != null ? parent : best;
    }

    /** The trade it is learning, if it is somebody's apprentice. */
    public StationTask apprenticedTo() { return apprenticeTo; }

    /** Tests only: the day's look at its years, now. */
    public void growOldForTests() { growingOld(level().getDayTime() / 24000L); }

    /** Tests: a child born this many days ago has its day (and, old enough, grows up). */
    public void childhoodForTests(int daysOld) {
        bornDay = level().getDayTime() / 24000L - daysOld;
        childhood();
    }

    /** Tests only: a child born this many days ago. */
    public void bornDaysAgo(long days) { bornDay = level().getDayTime() / 24000L - days; }

    /** [ageing] Tests only: a grown folk of just so many years, come to them today (yesterday it was a year younger). */
    public void setAgeForTests(int years) { bornDay = level().getDayTime() / 24000L - daysOldAt(years); }

    /** The bed in a house the village built for a player is that player's. */
    @Override
    protected boolean bedOnOffer(BlockPos pos) {
        if (!super.bedOnOffer(pos)) return false;
        UUID village = ownerId();
        // Not a player's guest house, and not another household's home (Homes) — unless it is a bed that
        // household has no need of, which a folk with none of its own may lodge in.
        if (village == null) return true;
        if (Villages.inAGuestHouse(village, pos)) return false;
        if (Infirmary.isInfirmaryBed(village, pos)) return false;     // [batchA] kept for the sick and the hurt
        if (Lodge.isLodgeBed(village, pos) && stationTask() != StationTask.CAVE) return false;   // [caves] the cave team's bunks
        if (Inn.isInnBed(village, pos)) return false;           // [batchE] the inn's rooms are for travellers (Inn)
        return level() instanceof net.minecraft.server.level.ServerLevel server
            ? !Homes.someoneElses(server, village, pos, this) : !Homes.someoneElses(village, pos, this);
    }

    /** Hand two rations to a friend: whatever food is in the pack, as it is. */
    private boolean shareARation(VillageFolkEntity friend) {
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> pack = getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            net.minecraft.world.item.ItemStack st = pack.get(i);
            if (st.isEmpty() || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
            int give = Math.min(2, st.getCount());
            net.minecraft.world.item.ItemStack left = friend.insertGiven(st.copyWithCount(give));
            int given = give - left.getCount();
            if (given <= 0) return false;
            st.shrink(given);
            if (st.isEmpty()) pack.set(i, net.minecraft.world.item.ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    /**
     * Time off, spent the way this folk is made: with its partner, else its best
     * friend, if they are free too; a sociable one with whoever is about; a shy one
     * on its own, a curious one wandering the edge of the village. A rival is walked
     * away from.
     */
    private void socialise() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || villageCentre == null) return;
        if (!getNavigation().isDone() && tickCount - socialWalkTick < 100) return;
        UUID rivalId = life.worstRival();
        if (rivalId != null && server.getEntity(rivalId) instanceof VillageFolkEntity rival
                && rival.isAlive() && rival.distanceToSqr(this) < 16.0) {
            double dx = getX() - rival.getX(), dz = getZ() - rival.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            BlockPos away = surfaceAt((int) (getX() + dx / len * 8), (int) (getZ() + dz / len * 8));
            if (away != null) { walkTo(away, 0.9D); socialWalkTick = tickCount; }
            return;
        }
        if (Habits.favouritePlace(this, server)) return;   // [individual] its favourite spot in town
        if (homeComfort(server)) return;              // its savings, spent on its home
        if (Fashion.shopping(this, server)) return;   // [fashion] to the shop for the season's look it wants
        if (PlayerStalls.errand(this, server)) return;   // a player's stall on the square, for what it wants (PlayerStalls)
        if (shopping(server)) return;                 // market day: a treat from the stalls
        if (lookRound(server)) return;                // the new building everybody is talking about
        if (Museum.visit(this, server)) return;       // round the museum, before a find or two
        if (cafeVisit(server)) return;                // a drink at the café
        if (shopVisit(server)) return;                // the shop: a tool for its work, or something nice
        if (Leisure.listen(this, server)) return;
        if (Park.visit(this, server)) return;                 // a sit in the park, now and then (Park)
        // Rain: indoors, under its own roof if it has one.
        if (level().isRaining() && bedPos() != null && !level().canSeeSky(blockPosition())) {
            getNavigation().stop();
            return;
        }
        if (level().isRaining() && bedPos() != null && blockPosition().distSqr(bedPos()) > 4.0) {
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                if (tickCount - socialWalkTick >= 600) FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Running for cover!", "Brr — indoors for me.", "Look at this rain!"));
                walkTo(bedPos(), 1.0D);
                socialWalkTick = tickCount;
            }
            return;
        }
        if (typeOuting(server)) return;               // an hour of its own kind (Values)
        VillageFolkEntity mate = company(server);
        if (mate != null) {
            if (distanceToSqr(mate) > 9.0) {
                if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                    walkTo(mate.blockPosition(), 0.8D);
                    socialWalkTick = tickCount;
                }
            } else {
                getNavigation().stop();
                getLookControl().setLookAt(mate, 30.0F, 30.0F);
                // A word or two about what is going on (Smalltalk).
                if (tickCount - lastSmalltalk > 1200 && getRandom().nextInt(3) == 0 && Smalltalk.chat(this, mate, server)) {
                    lastSmalltalk = tickCount;
                    mate.lastSmalltalk = mate.tickCount;
                }
            }
            return;
        }
        if (!getNavigation().isDone()) return;
        int reach = life.has(Social.Trait.CURIOUS) ? 24 : life.has(Social.Trait.SHY) ? 12 : 6;
        if (villageCentre.distSqr(blockPosition()) > (double) (reach + 4) * (reach + 4)) {
            walkTo(villageCentre, 0.9D);
            socialWalkTick = tickCount;
        } else if (getRandom().nextInt(4) == 0) {
            BlockPos to = surfaceAt(villageCentre.getX() + getRandom().nextInt(2 * reach + 1) - reach,
                villageCentre.getZ() + getRandom().nextInt(2 * reach + 1) - reach);
            if (to != null) { walkTo(to, 0.7D); socialWalkTick = tickCount; }
        }
    }

    @Nullable private BlockPos typeOutingAt;
    private String typeOutingDoing = "";
    private int typeOutingUntil;
    private long typeOutingDay = -1;

    /**
     * Once a day, now and then, an hour spent the way its kind of folk spends one (Values): a
     * Guardian walks the wall, a Traditionalist sits where the old folk sit, a Visionary watches
     * the new building go up, a Merchant looks over what is for sale, a Free Spirit idles at the
     * tavern, a Provider looks over the fields, a Homemaker tidies round its house.
     */
    private boolean typeOuting(net.minecraft.server.level.ServerLevel server) {
        if (isBaby()) return false;
        long day = level().getDayTime() / 24000L;
        if (typeOutingAt == null) {
            if (typeOutingDay == day || getRandom().nextInt(3) != 0) return false;
            typeOutingDay = day;
            String[] doing = { "" };
            BlockPos at = Values.freeTime(server, this, doing);
            if (at == null) return false;
            BlockPos ground = surfaceAt(at.getX(), at.getZ());
            typeOutingAt = ground != null ? ground : at;
            typeOutingDoing = doing[0];
            typeOutingUntil = tickCount + 600 + getRandom().nextInt(600);
        }
        if (tickCount > typeOutingUntil) {
            typeOutingAt = null;
            return false;
        }
        if (blockPosition().distSqr(typeOutingAt) > 9.0) {
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(typeOutingAt, 0.8D);
                socialWalkTick = tickCount;
            }
        } else if (Values.top(this) == Values.Value.SAFETY && getRandom().nextInt(8) == 0) {
            // Along the wall a few steps, looking out.
            String[] doing = { "" };
            BlockPos next = Values.freeTime(server, this, doing);
            if (next != null) typeOutingAt = next.offset(getRandom().nextInt(7) - 3, 0, getRandom().nextInt(7) - 3);
        } else {
            getNavigation().stop();
        }
        hobbyNow = typeOutingDoing;
        lastLeisureTick = tickCount;
        return true;
    }

    /** Who this folk wants to be with now: its partner, its best friend, or (if it is
     *  sociable) whoever is nearest — so long as they are off work too. */
    @Nullable
    private VillageFolkEntity company(net.minecraft.server.level.ServerLevel server) {
        for (UUID id : new UUID[]{life.partner(), life.bestFriend()}) {
            if (id == null) continue;
            if (server.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() && !f.isSleeping()
                    && f.offWorkNow() && f.distanceToSqr(this) < 48.0 * 48.0) {
                return f;
            }
        }
        if (!life.has(Social.Trait.SOCIABLE) || ownerId() == null) return null;
        UUID village = ownerId();
        VillageFolkEntity best = null;
        double nearest = 16.0 * 16.0;
        for (VillageFolkEntity f : level().getEntitiesOfClass(VillageFolkEntity.class, getBoundingBox().inflate(16.0),
                f -> f != this && f.isAlive() && !f.isSleeping() && village.equals(f.ownerId()) && f.offWorkNow())) {
            if (life.affinity(f.getUUID()) <= Social.RIVAL) continue;
            double d = f.distanceToSqr(this);
            if (d < nearest) { nearest = d; best = f; }
        }
        return best;
    }

    /**
     * The evening: the day's work is done and it is not yet bedtime, so folk are
     * together at the heart — partners side by side, friends with friends. The
     * sociable stay up longest and the hard workers turn in first.
     */
    @Override
    protected boolean eveningSocial() {
        if (Assemblies.attending(this)) return true;          // at the village's gathering (Assemblies)
        if (FireworkShows.crewing(this)) return true;         // [fireworks] at the rack, setting off the display
        if (TownJobs.busy(this)) return true;                 // at the town's work (TownJobs)
        if (School.teaching(this)) return true;               // at the school's lectern (School)
        if (TownCalendar.busy(this)) return true;             // ringing the bell, home at the dusk bell, a birthday present (TownCalendar)
        if (Culture.busy(this)) return true;                  // [batchD] the silence, the play, the band, a toast, a picture (Culture)
        if (Raids.underAlarm(ownerId())) return false;       // the bell is ringing: no evening out
        long t = level().getDayTime() % 24000L;
        long bedtime = bedtimeTick();
        if (t < 12000L || t >= bedtime) return false;
        if (isBaby()) return false;
        if (familySupper(t)) return true;
        if (Tavern.evening(this, t)) return true;
        if (Allotments.evening(this, t)) return true;         // [batchE] some evenings, the household's allotment (Allotments)
        if (Park.evening(this, t)) return true;               // some evenings, an hour in the park (Park)
        if (Leisure.evening(this, t)) return true;
        socialise();
        return true;
    }

    /** When it last passed the time of day with somebody (Smalltalk). */
    int lastSmalltalk = -100000;
    /** The day it last sat down to supper with its family. */
    private long supperDay = -1;

    /**
     * Supper: a folk with a family goes home at dusk for an hour with them — its partner,
     * its children — before the evening out. One sits down first and calls the rest in.
     */
    private boolean familySupper(long t) {
        if (t >= 13000L || bedPos() == null || isBaby()) return false;
        long day = level().getDayTime() / 24000L;
        if (supperDay == day && t >= 12900L) return false;
        if (life.partner() == null && life.children() == 0) return false;
        BlockPos home = bedPos();
        if (!level().isLoaded(home)) return false;
        if (blockPosition().distSqr(home) > 3.0 * 3.0) {
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(home, 0.9D);
                socialWalkTick = tickCount;
            }
            hobbyNow = "home for supper";
            return true;
        }
        getNavigation().stop();
        hobbyNow = "at supper with the family";
        if (supperDay != day) {
            supperDay = day;
            persona.remember(day, "supper at home with the family", 1);
            if (getRandom().nextInt(3) == 0) FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Supper's on!", "Come and eat, everyone!", "Smells good tonight."));
        }
        if (life.partner() != null && level() instanceof net.minecraft.server.level.ServerLevel sl
                && sl.getEntity(life.partner()) instanceof VillageFolkEntity p && p.distanceToSqr(this) < 16.0) {
            getLookControl().setLookAt(p, 30.0F, 30.0F);
        }
        return true;
    }

    /**
     * Past its bedtime with work still in hand: put it down. The evening's errands
     * (rations, the baking) get until an hour after; then they wait too.
     */
    private void leaveWorkForTheMorning() {
        Job j = peekJob();
        if (j == null || j.type() == Job.Type.DEPOSIT) return;
        long t = level().getDayTime() % 24000L;
        if (t < 12000L) return;                             // before dusk: not tonight's business
        boolean errand = j.type() == Job.Type.WITHDRAW || j.type() == Job.Type.CRAFT || j.type() == Job.Type.GO_HOME;
        long cutoff = bedtimeTick() + (errand ? 1000L : 0L);
        if (t < cutoff) return;
        clearQueue();
        brain("work left for the morning: " + j.label());
    }

    /**
     * A bed anywhere in the village, not only within a few steps of the heart: the
     * houses stand on lots up to fifty blocks out. Read from the chunks' own lists of
     * beds, so it costs nothing however big the place is; the nearest free one wins.
     */
    @Override
    protected boolean findABed(BlockPos base) {
        UUID village = ownerId();
        if (villageCentre == null || village == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) {
            return super.findABed(base);
        }
        // Its own house first (Homes): the grown-ups' pair side by side, the children's beds across the room.
        // A lodger in it (a folk with no bed of its own, put up in a bed the house could spare) gives it back.
        BlockPos own = Homes.bedFor(server, this);
        if (own != null && !bedOnOffer(own)) own = Homes.bedBack(server, this, own);
        if (own != null && bedOnOffer(own)) {
            bedLook = "its own house";
            takeBed(own);
            return true;
        }
        int reach = Math.min(6, Math.max(3, Villages.storesRadius(village) / 16));
        int cx = villageCentre.getX() >> 4, cz = villageCentre.getZ() >> 4;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // A family sleeps under one roof: the bed nearest its partner's, else its
        // mother's or father's, wins over the bed nearest where it happens to stand.
        BlockPos near = familyBed(server);
        // A child sleeps in its family's house, by their bed, while any grown-up in the village
        // still has no bed of its own: the beds go to those who work. (Two children are counted
        // as one bed's worth when the village works out how many houses it needs.)
        if (isBaby()) {
            // (Not the watch: a guard is up all night and never goes looking for a bed, and one without
            // kept every child in the village off the spare beds.)
            boolean adultWithout = false;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!a.isBaby() && a.bedPos() == null && a.shift() != Shift.ALWAYS) { adultWithout = true; break; }
            }
            if (adultWithout) {
                if (near != null) setHome(near);
                return false;
            }
        }
        BlockPos from = near != null ? near : blockPosition();
        int heads = 0, offered = 0, fit = 0;
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = server.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof net.minecraft.world.level.block.entity.BedBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    net.minecraft.world.level.block.state.BlockState st = level().getBlockState(p);
                    if (st.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
                        && st.getValue(net.minecraft.world.level.block.BedBlock.PART)
                            == net.minecraft.world.level.block.state.properties.BedPart.HEAD) heads++;
                    if (!bedOnOffer(p)) continue;
                    offered++;
                    if (!bedFit(p)) continue;
                    fit++;
                    double d = p.distSqr(from);
                    if (d < bestDist) { bestDist = d; best = p; }
                }
            }
        }
        bedLook = heads + " beds, " + offered + " free, " + fit + " fit";
        if (best == null) return false;
        takeBed(best);
        return true;
    }

    /** What the last look for a bed found (the report line). */
    private String bedLook = "";

    /**
     * A bed down in the ground (a buried ruin's, a vault's) is nobody's home: three folk of one
     * village claimed one eighteen below sea level a hundred blocks off, could not walk to it, and
     * were set down beside it every night, to spend the morning climbing out.
     */
    @Override
    protected boolean bedFit(BlockPos bed) {
        // [emerald] A bed in a village of the game's villagers is a villager's: no folk ever sleeps in one (TwoPeoples).
        if (VanillaVillages.within(level(), bed.getX(), bed.getZ(), 0)) return false;
        if (!level().hasChunkAt(bed)) return true;                   // out of sight: as it was
        UUID village = ownerId();
        if (village == null) {
            return bed.getY() >= level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bed.getX(), bed.getZ()) - 8;
        }
        return !Villages.buriedBed(level(), village, bed);           // a bed under its house's roof is a home
    }

    /** Where its partner sleeps, else one of its parents (a child sleeps by its family). */
    @Nullable
    private BlockPos familyBed(net.minecraft.server.level.ServerLevel server) {
        if (life.partner() != null && server.getEntity(life.partner()) instanceof VillageFolkEntity p && p.bedPos() != null) {
            return p.bedPos();
        }
        if (!isBaby() && life.partner() != null) return null;
        String parents = life.parents();
        if (parents.isEmpty() || ownerId() == null) return null;
        for (AssistantEntity a : Villages.folkOf(ownerId())) {
            if (a instanceof VillageFolkEntity f && f.bedPos() != null && parents.contains(f.displayNameCap())) return f.bedPos();
        }
        return null;
    }

    /**
     * [ua] A child has no trade until it is grown (childhood), and its line said "Unassigned" like a grown
     * folk with none. A hundred-day town's tallies read its ten or fifteen children as that many grown
     * folk standing about without work, and a day was spent looking for why they never took one up.
     */
    @Override
    public String tradeTitle() {
        return isBaby() ? "Child" : super.tradeTitle();
    }

    @Override
    protected String debugExtra() {
        String social = " traits=" + life.traitsLabel().replace(' ', '-') + " type=" + Values.brief(this)
            + (life.partner() != null ? " partner=" + life.partnerName() : "")
            + " friends=" + life.friends().size();
        String walk = "";
        WorkZone zone = workZone();
        if (stationTask() == StationTask.FARM && zone != null && ownerId() != null) {
            Reach r = Reach.last(ownerId());
            walk = r == null ? " reach=none" : " reach=" + r.count() + (r.reaches(zone.center(), Math.min(zone.radius(), FIELD_MOST)) ? "/ok" : "/NO");
        }
        String bed = " bed=" + (bedPos() == null ? "none" : "yes") + (bedLook.isEmpty() ? "" : "(" + bedLook + ")");
        return social + walk + bed + (trail.length() == 0 ? "" : " trail=" + trail.toString().trim());
    }

    // ------------------------------ getting started --------------------------

    /** Guards keep the watch through the night; nobody else does. */
    private void keepShift() {
        Shift want = stationTask() == StationTask.GUARD ? Shift.ALWAYS : Shift.DAY;
        if (shift() != want) setShift(want);
    }

    /** The middle of the night: the first watch hands over to the second. */
    public static final long MIDNIGHT = 18000L;

    /**
     * The watch is kept in two halves, so that every guard sleeps every night: half the
     * village's guards stand the first watch, from dusk to midnight, and then go to bed;
     * the other half sleep first and stand the second, from midnight to dawn. A village
     * with one guard has it watch until midnight and sleep after.
     */
    /** Tests and the books: why this folk is off work now, in a word or two ("" when it is at work). */
    public String offWorkWhy() {
        UUID id = ownerId();
        if (Health.laidUp(this)) return "laid up";
        if (Assemblies.attending(this)) return "at a gathering";
        if (TownJobs.busy(this)) return "on the town's work";
        if (School.teaching(this)) return "teaching";
        if (id != null && Raids.underAlarm(id)) return "the alarm";
        if (id != null && RestDay.now(id, level().getDayTime()) != null) return "the day of rest";
        Boolean bell = TownBell.shift(this);
        if (bell != null && !bell) return "the town bell";
        if (!onShift()) return "off shift";
        return onBreak() ? "on a break" : "";
    }

    @Override
    public boolean onShift() {
        // [batchA] Laid up in bed (Health): no work till it is up.
        if (Health.laidUp(this)) return false;
        // Fetching a horse or putting one away (Stables): seen through before bed.
        if (Stables.busy(this)) return true;
        // Walking with the leader (Patrols, by day only): that is the work, assembly or none.
        if (Patrols.escorting(this)) return true;
        // [watch-clears] Out after a monster the watch sent it after: at its work, whichever watch it keeps, and not
        // to be called off to bed or an evening's errand halfway through the fight (WatchClears).
        if (WatchClears.hunting(this)) return true;
        // Called to the village's gathering: its work waits (the watch is never called away).
        if (Assemblies.attending(this)) return false;
        // On the town's own work (TownJobs): its trade waits till that is done.
        if (TownJobs.busy(this)) return false;
        // At the school's lectern (School): its trade waits for the afternoon.
        if (School.teaching(this)) return false;
        // The bell: every guard turns out, whichever watch it keeps; nobody else works.
        UUID alarmed = ownerId();
        if (alarmed != null && Raids.underAlarm(alarmed)) return stationTask() == StationTask.GUARD;
        // The day of rest: nobody works but the watch.
        if (alarmed != null && stationTask() != StationTask.GUARD && RestDay.now(alarmed, level().getDayTime()) != null) return false;
        // The town bell (TownBell): the day's work from the dawn bell to the dusk bell, and the bell's own business first.
        Boolean bell = TownBell.shift(this);
        if (bell != null) return bell;
        if (stationTask() != StationTask.GUARD || shift() != Shift.ALWAYS || !level().isNight()) return super.onShift();
        return firstWatch() == (level().getDayTime() % 24000L < MIDNIGHT);
    }

    private int watchCheckTick = -100000;
    private boolean firstWatch = true;

    /** Does this guard stand the first watch? Every other guard of the village, in a fixed order. */
    public boolean firstWatch() {
        if (tickCount - watchCheckTick < 1200 && watchCheckTick >= 0) return firstWatch;
        watchCheckTick = tickCount;
        UUID village = ownerId();
        if (village == null) return firstWatch = true;
        int before = 0;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate != this && mate.stationTask() == StationTask.GUARD && mate.getUUID().compareTo(getUUID()) < 0) before++;
        }
        return firstWatch = before % 2 == 0;
    }

    /** This folk's own hour for bed: a sociable one stays up, a hard worker turns in early. */
    public long bedtimeTick() {
        return LawBook.bedtime(ownerId(), 14000L + Math.floorMod(getUUID().getMostSignificantBits(), 600L)   // [identity] a curfew
            + (life.has(Social.Trait.SOCIABLE) ? 1500L : 0L) - (life.has(Social.Trait.HARDWORKING) ? 800L : 0L));
    }

    /** Where this hand's ground was when the agenda last looked, and since when. */
    @Nullable private WorkZone lastPlot;
    private int plotSince;

    /**
     * "Worked out" means the trade HAD work and has run dry — not that it never
     * began. A hand still setting up (no chest yet, no forge) or only just
     * arrived at its ground has finished nothing, and every rung that reads
     * this — lending a hand elsewhere, giving the ground up as spent — used to
     * fire for a brand-new folk on its very first turn, before it had put down
     * a chest or turned a single sod.
     */
    @Override
    public boolean workedOut() {
        if (settingUp()) return false;
        if (workZone() != null && tickCount - plotSince < 1800) return false;
        // A hand on its way to its plot has not run out of work, it has not got
        // there yet — every morning the whole village walked out from the heart
        // "worked out", and lent itself to a woodpile in a field with no trees.
        WorkZone zone = workZone();
        if (zone != null && !zone.containsColumn(blockPosition())) return false;
        return super.workedOut();
    }

    /** A running record of what this hand has been doing — job and status,
     *  newest last — kept so that a hand which is not getting anywhere can be
     *  asked how it got there. Read by the village command and the tests. */
    private final StringBuilder trail = new StringBuilder();
    private String trailLast = "";

    private void keepTrail() {
        WorkZone zone = workZone();
        if (!java.util.Objects.equals(lastPlot, zone)) {
            lastPlot = zone;
            plotSince = tickCount;
        }
        Job j = peekJob();
        String now = (j == null ? "-" : j.type().name())
            + (missingEssentials().isEmpty() ? "" : "!" + missingEssentials().size());
        if (now.equals(trailLast)) return;
        trailLast = now;
        trail.append(tickCount).append(':').append(now).append(' ');
        if (trail.length() > 480) {
            int cut = trail.indexOf(" ", trail.length() - 360);
            trail.delete(0, cut < 0 ? trail.length() - 360 : cut + 1);
        }
    }


    // ------------------------------ hands with nothing of their own to do ----

    /** Put idle hands to the village's use. True if it queued something or is
     *  already busy, false if there is nothing worth doing. */
    private boolean idleHands() {
        if (peekJob() != null) return true;
        if (bakeErrand()) return true;
        if (onShift()) considerVillageWork();        // no building after dark
        return peekJob() != null;
    }

    /**
     * Wheat is not food until somebody bakes it, and the farmers — who are
     * never idle while there is a crop to tend — never get the moment to. So
     * the village's idle hands do: fetch a batch from the stores, bake it at
     * the bench every folk carries, and bank the bread. One errand at a time
     * across the whole village.
     */
    private boolean bakeErrand() {
        UUID village = ownerId();
        BlockPos heart = villageCentre;
        if (village == null || heart == null || peekJob() != null) return false;
        long now = level().getGameTime();
        if (!Villages.mayBake(village, now)) return false;
        int held = countCarried(st -> st.is(net.minecraft.world.item.Items.WHEAT));
        int radius = Math.min(112, Math.max(32, Villages.storesRadius(village)));
        int inStores = storesHold(heart, radius,
            st -> st.is(net.minecraft.world.item.Items.WHEAT));
        if (held + inStores < 9) return false;
        Villages.noteBake(village, now);
        int take = Math.min(24, inStores);
        if (held < 9 && take > 0) enqueue(Job.withdrawAt("wheat", take, heart, radius));
        enqueue(Job.craft("bread", Math.max(1, Math.min(8, (held + take) / 3))));
        enqueue(storesDeposit());
        return true;
    }

    // ------------------------------ the errand to the stores -----------------

    private int rationTick = -100000;

    /**
     * Evening, at home, with the larder a short walk away and tomorrow's plot a long
     * one: take on rations for the day. A village of fifty on the ten-day run had
     * four hundred and ninety-eight food in its stores and half its folk at zero,
     * because the only time a hand went to the stores was when it had nothing left
     * to eat — out on a plot a hundred blocks from them.
     */
    private boolean restockRations() {
        BlockPos heart = villageCentre;
        UUID village = ownerId();
        if (heart == null || village == null || peekJob() != null) return false;
        if (tickCount - rationTick < 6000) return false;                     // once a night
        // Only those who are home: anywhere in the town. (Forty-eight blocks round the heart was the
        // town of a young village; the hundred's houses ran out to sixty and more, and those who
        // lived in them never took on a ration of an evening.)
        int home = Math.max(48, Villages.townReach(village) + 8);
        if (Math.max(Math.abs(heart.getX() - getBlockX()), Math.abs(heart.getZ() - getBlockZ())) > home) return false;
        // Rations, not seed: a farmer's carrots and potatoes are for the ground.
        int have = countMatching(st -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null
            && !(stationTask() == StationTask.FARM
                && (st.is(net.minecraft.world.item.Items.CARROT) || st.is(net.minecraft.world.item.Items.POTATO))));
        // A couple of days' meals, not a week's: what is in a pack is not in the stores, and the
        // village reads its larder (and decides to raise children) from the stores. A hand whose plot
        // is a long walk from them takes a few days' (rationsWanted), the less to walk in for.
        int want = eatsRations() ? rationsWanted() : 8;
        if (have >= want - 2) return false;
        int radius = Math.min(112, Math.max(32, Villages.storesRadius(village)));
        if (findChestWithNear(heart,
                com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor("ration"), radius) == null) {
            return false;
        }
        rationTick = tickCount;
        enqueue(Job.withdrawAt("ration", want - have, heart, radius));
        noteGate("evening: taking on " + (want - have) + " rations");
        return true;
    }

    private int fetchTick = -100000;

    /**
     * Short of something the plot cannot supply and the chests here do not
     * hold? The settlement keeps stores of its own, at its heart, and a folk
     * that belongs to it walks there and takes what it needs — a chest to put
     * down, a furnace, a tool, a ration — exactly as a person sent out without
     * their spade would go back to the shed for it.
     */
    @Override
    protected boolean fetchFromStores() {
        BlockPos heart = villageCentre;
        if (heart == null || peekJob() != null) return false;
        if (tickCount - fetchTick < 900) return false;          // an errand every 45 s at most
        fetchTick = tickCount;

        String[] asks = null;    // what to look for, best first
        int many = 1;
        for (String gap : missingEssentials()) {
            // The tool itself first; failing that the makings, which the craft
            // rung turns into one on its next visit (it needs a bench, which
            // every folk carries).
            if (gap.startsWith("food")) { asks = new String[]{ "food" }; many = 12; }
            else if (gap.startsWith("a pickaxe")) asks = new String[]{ "pickaxe", "plank", "log" };
            else if (gap.startsWith("an axe")) asks = new String[]{ "axe", "plank", "log" };
            else if (gap.startsWith("a sword")) asks = new String[]{ "sword", "plank" };
            else if (gap.startsWith("a hoe")) asks = new String[]{ "hoe", "plank" };
            // A rod is three sticks and two string, and the village keeps its
            // string at the heart while the fisher's pond is sixty blocks out:
            // the fisher asked the stores for a ready-made rod, found none, and
            // stood at its empty pond for three game days.
            else if (gap.startsWith("a fishing rod")) { asks = new String[]{ "fishing rod", "string", "plank" }; many = 2; }
            else if (gap.startsWith("shears")) asks = new String[]{ "shears" };
            else if (gap.startsWith("a furnace")) {
                if (countMatching(st -> st.is(net.minecraft.world.item.Items.FURNACE)) == 0) {
                    asks = new String[]{ "furnace", "cobble" };
                }
            }
            else if (gap.startsWith("fuel")) { asks = new String[]{ "fuel" }; many = 16; }
            else if (gap.startsWith("raw ore")) { asks = new String[]{ "ore" }; many = 32; }
            else if (gap.equals("a chest in the zone") || gap.equals("2 chests in the zone")) {
                // Carrying one already? Then what is missing is the PLOT to
                // set it down on, and no errand to the stores will supply that.
                if (countMatching(st -> st.is(net.minecraft.world.item.Items.CHEST)) == 0) {
                    asks = new String[]{ "chest", "plank", "log" };
                }
            }
            if (asks != null) break;
        }
        if (asks == null) { noteGate("fetch: nothing to ask the stores for"); return false; }
        // Rations are worth a longer walk than a chest: they are spread through
        // every field's chest, not kept in one shed. Everything else is looked
        // for around the heart, where the village keeps what it keeps.
        UUID village = ownerId();
        for (String ask : asks) {
            if (alreadyCarrying(ask)) continue;             // have the makings: go and make it
            if (storehouseHasFirstCall(ask)) { noteGate("fetch: the timber, stone and chests are for the storehouse"); continue; }
            int amount = ask.equals("cobble") ? 8 : (ask.equals("plank") ? 8
                : (ask.equals("log") ? 2 : many));
            // Stone comes out of the ground where the mines are, a hill or two from
            // the heart, and lies in their chests until a hauler moves it: a smelter
            // that only looked round the heart for cobble to make its furnace from
            // never found any and never smelted a thing.
            int radius = ask.equals("food") || ask.equals("ore") || ask.equals("fuel")
                    || ask.equals("cobble") || ask.equals("plank") || ask.equals("log")
                ? Math.min(112, Math.max(32, Villages.storesRadius(village))) : Villages.STORE_AREA + 2;
            if (findChestWithNear(heart,
                    com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(ask), radius) == null) {
                continue;
            }
            enqueue(Job.withdrawAt(ask, amount, heart, radius));
            noteGate("fetch: going for " + amount + " " + ask);
            return true;
        }
        noteGate("fetch: the stores hold none of " + java.util.Arrays.toString(asks));
        return false;
    }

    private int kitTick = -100000;
    private int supplyTick = -100000;

    /** The kit of its trade, kept in its pack (Trades.keeps). */
    @Override
    protected int kitReserve(net.minecraft.world.item.ItemStack s) {
        // Its own chest, on its way from the old plot to the new one, is not cargo for the stores.
        if (oldProductionChest != null && s.is(net.minecraft.world.item.Items.CHEST)) return 1;
        return Trades.keeps(stationTask(), s);
    }

    /**
     * The trade's own supplies, when they run low: a farmer's seed, a woodcutter's saplings,
     * a miner's torches, a rancher's feed, a guard's arrows. They are kept in the stores at
     * the heart (the founding chest brings them, the carriers bring the rest) and were only
     * ever looked for in a folk's own plot chests, so a farmer whose field ate its seed stood
     * breaking grass for more while the storehouse had a chest of it.
     */
    private boolean kitFromTheStores() {
        if (tickCount - kitTick < 1800) return false;
        kitTick = tickCount;
        BlockPos heart = villageCentre;
        String[] kit = kitShort();
        if (kit == null || heart == null || !onShift()) return false;
        int radius = Villages.STORE_AREA + 2;
        for (String ask : kit) {
            java.util.function.Predicate<net.minecraft.world.item.ItemStack> what =
                com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(ask);
            if (ZoneChests.countIn(linkedChests(), what) >= 8) return false;   // its own chest has plenty
            if (storesHold(heart, radius, what) < 8) continue;                 // not worth the walk for a few
            // Far out on its plot, with couriers at the storehouse: a request the storekeeper cannot
            // hand over at the counter, so a courier brings it out and the work goes on (Couriers).
            if (Couriers.sendOut(this, ask, 16)) {
                brain("asked the storehouse to send " + ask + " out");
                return false;
            }
            enqueue(Job.withdrawAt(ask, 16, heart, radius));
            brain("fetching " + ask + " from the stores");
            return true;
        }
        return false;
    }

    /** The supplies of this folk's trade it is running out of, as things to ask the stores for; or null. */
    @Nullable
    private String[] kitShort() {
        return switch (stationTask()) {
            case FARM -> countCarried(st -> st.is(net.minecraft.world.item.Items.WHEAT_SEEDS) || st.is(net.minecraft.world.item.Items.CARROT)
                    || st.is(net.minecraft.world.item.Items.POTATO) || st.is(net.minecraft.world.item.Items.BEETROOT_SEEDS))
                    + FieldTools.satchelSeed(this) < 8                                // [fields] and the seed at its hip
                ? new String[]{ "wheat seeds", "carrot", "potato", "beetroot seeds" } : null;
            case WOOD -> countCarried(st -> st.is(net.minecraft.tags.ItemTags.SAPLINGS)) < 4 ? new String[]{ "sapling" } : null;
            case MINE -> countCarried(st -> st.is(net.minecraft.world.item.Items.TORCH)) < 8 ? new String[]{ "torch" } : null;
            case RANCH -> countCarried(st -> st.is(net.minecraft.world.item.Items.WHEAT)) < 8 ? new String[]{ "wheat" } : null;
            case GUARD -> countCarried(st -> st.is(net.minecraft.world.item.Items.BOW) || st.is(net.minecraft.world.item.Items.CROSSBOW)) > 0
                    && countCarried(st -> st.is(net.minecraft.tags.ItemTags.ARROWS)) < 16 ? new String[]{ "arrow" } : null;
            default -> null;
        };
    }

    /** The founding timber, stone and chests are the storehouse's until it stands
     *  (see Villages.storehouseFirst): a hand that wants spares waits, or makes
     *  do with what it carries. */
    private boolean storehouseHasFirstCall(String ask) {
        UUID village = ownerId();
        if (village == null) return false;
        if (!(ask.equals("plank") || ask.equals("log") || ask.equals("cobble") || ask.equals("chest"))) return false;
        // Except a smelter's eight stone for its furnace: a smelter without a furnace is not
        // a smelter, and every one of them stood "needing a furnace in the zone" for the
        // first half hour of every village while the stone waited for the storehouse.
        if (ask.equals("cobble") && stationTask() == StationTask.SMELT) return false;
        return Villages.storehouseFirst(village, level().getGameTime());
    }

    /** Does the pack already hold enough of what this ask is for? A hand that
     *  kept asking the stores for string it was already carrying never got round
     *  to the planks it was missing. */
    private boolean alreadyCarrying(String ask) {
        return switch (ask) {
            case "string" -> countCarried(st -> st.is(net.minecraft.world.item.Items.STRING)) >= 2;
            case "plank" -> countCarried(st -> st.is(net.minecraft.tags.ItemTags.PLANKS)) >= 4;
            case "log" -> countCarried(st -> st.is(net.minecraft.tags.ItemTags.LOGS)) >= 1;
            case "cobble" -> countCarried(st -> st.is(net.minecraft.world.item.Items.COBBLESTONE)) >= 8;
            default -> false;
        };
    }

    /**
     * One rung a visit, in the order a person would care about them: belong
     * somewhere, have a trade, have ground to work it on — and only once all
     * that is settled, look up and see what the village still needs building.
     */
    private long agedDay = -1;

    private void agenda() {
        if (ownerId() == null) { settle(); return; }
        long today = level().getDayTime() / 24000L;
        if (agedDay != today) {
            agedDay = today;
            growingOld(today);
            if (!isAlive()) return;
            refreshOldAgeGait();
        }
        if (isBaby()) { childhood(); return; }
        // On the road with a caravan: that is the day's work, day and night until it is home.
        if (trip != null && level() instanceof net.minecraft.server.level.ServerLevel road) {
            Caravans.drive(this, road);
            return;
        }
        if (expedition != null) return;                 // out scouting: the land is the day's work
        // The bell is ringing: everybody but the watch drops what it is doing and gets indoors
        // (the watch's orders are in its station brain: Raids.guardDuty).
        if (Raids.underAlarm(ownerId()) && stationTask() != StationTask.GUARD) {
            if (peekJob() != null) clearQueue();
            if (!isSleeping()) bedtime();
            return;
        }
        if (Militia.muster(this)) return;               // [war-prep] a morning of the war: the militia musters and drills
        // Nobody goes looking for ground after dark: a folk with no trade yet spends the
        // night like everybody else, and looks in the morning.
        if (workZone() == null && !onShift()) {
            if (!isSleeping() && peekJob() == null) bedtime();
            return;
        }
        if (workZone() == null) { takeUpATrade(); return; }
        // The watch works the night, everybody else works the day. Without this
        // every folk counted as "worked out" the moment the sun went down (it
        // records no work while it is parked at home), so all night it lent
        // itself out, walked its plot for a chest, and — worse — decided its
        // mine was spent and staked a new one.
        keepShift();
        if (!onShift()) {
            // [economy] The shift is over: the day's work put away before home and bed (PutAway).
            if (PutAway.look(this)) return;
            if (isSleeping()) {                        // asleep: nothing until morning
                persona.sleptInABed(level().getDayTime() / 24000L);
                return;
            }
            // Everybody is at the heart after dark, with nothing to do — which is
            // exactly when the wheat gets baked. Indoors, next to the stores,
            // one errand at a time for the whole village.
            if (peekJob() == null && getNavigation().isDone()) {
                // A tool that broke at dusk is replaced tonight, not after the morning's first dry run;
                // and whoever is at the stores sees to the rack of spares (Toolrack).
                toolFromTheRack();
                if (level() instanceof net.minecraft.server.level.ServerLevel evening) Toolrack.tend(this, evening);
                if (!restockRations()) bakeErrand();
                // What it is short of, looked at again now it has been to the stores: the checklist is
                // otherwise only read at work, and said "food" or "a pickaxe" all night and through the
                // morning's assembly with the rations and the new pick in the pack.
                if (!missingEssentials().isEmpty() && tickCount - kitRecheckTick > 600) {
                    kitRecheckTick = tickCount;
                    recheckKit();
                }
            }
            // Work stops at bedtime. Whatever is left of the day's job waits for the morning
            // (the village's builder takes its building up again then, the miner its mine):
            // a folk with a job still queued never went to bed at all.
            leaveWorkForTheMorning();
            // Home for the night is where two folk are at last near each other: a village of
            // two, one at its field and one at its mine all day, could never have a child.
            raisedAChild(24.0);
            // Then the evening with the others, and then bed.
            if (peekJob() == null) bedtime();
            return;
        }
        // Lent out to the woods or the quarry: see it through before anything else.
        if (lentTo != null && peekJob() == null && carryOnLending()) return;
        // A village with no stores at all: the first chest goes to the heart.
        if (peekJob() == null && level() instanceof net.minecraft.server.level.ServerLevel firstStores
                && foundTheStores(firstStores, ownerId())) return;
        // (A courier's old chests to clear into the storehouse come off the storehouse's run list
        // now, with the rest of its runs: Couriers.)
        mindTheRoute();                                // a courier's runs are the storehouse's to give
        workInTheBuilding();                           // the smelter in the smeltery, the storekeeper in the storehouse
        putBackIfLost();
        // Tools from the stores, busy or not: they are made on the spot, and a miner
        // almost always has a mine job queued — so behind the busy check below, a miner
        // whose pickaxe had worn out stood "needing a pickaxe" for days.
        pickaxeFromTheStores();                        // the iron, and then the diamond, pickaxe
        toolFromTheRack();                             // a spare off the storehouse's rack, before the last one breaks
        rationsAhead();                                // rations before they run out: at the counter, or sent out
        shearsFromTheStores();                         // a rancher's shears, for the wool
        rodFromTheStores();                            // a fisher's rod, of the stores' string and wood
        stoneToolFromTheStores();                      // no more wooden tools once there is stone
        guardKitFromTheStores();                       // the town's best armour and blade, on the watch (WatchKit)
        betterToolFromTheStores();                     // the smith's iron and the enchanter's work, in use
        clothesFromTheStores();                        // the tailor's boots
        WorkTools.kitUp(this);                         // [workitems] a miner's props, rope and sack, a woodcutter's saw, a courier's crates
        bucketFromTheStores();                         // a farmer's water, when the village is hungry
        obsidianFromLava();                            // the gateway's obsidian, made where the lava is
        growTheForge();                                // a smelter's furnaces: one, and up to four in a row
        // The trade's kit, if the village has not had it (the hive, the brewing stand...), and the
        // links between the trades nothing else keeps up: cane, milk, sand, the pen's animals.
        if (tickCount - supplyTick >= 600 && level() instanceof net.minecraft.server.level.ServerLevel supplies) {
            supplyTick = tickCount;
            Drover.tidy(this, supplies);
            moveIntoThePen();
            Trades.kit(this);
            Trades.buckets(this);
            keepProductionChest(supplies);
            if (mindTheHerd(supplies)) return;
            if (Stables.ranch(this, supplies)) return;      // the rancher's horses: fed, fetched, saddled, gentled (Stables)
            if (Links.tend(this, supplies)) return;
        }
        // Its plot moved on: its old production chest comes along (one chest a worker, ever).
        if (level() instanceof net.minecraft.server.level.ServerLevel moving && carryTheOldChest(moving)) return;
        // The mine's depth too: the next run digs at the new one. Behind the busy check
        // it never ran, and every mine staked in the Wood Age stayed at forty-odd for the
        // rest of the game — copper and coal by the hundred, iron one or two a day.
        if (seekTheSeam()) return;                     // dig where the village's metal is
        growTheField();                                // a full field breaks new ground
        if (turnedToTheFields()) return;               // a hungry village needs farmers (busy or not)
        PutAway.look(this);                            // [economy] midday (and the watch at dusk): the work put away, busy or not
        if (peekJob() != null) return;                 // already busy
        if (onShift() && Strays.tend(this)) return;    // [economy] the builders' stock it carries, back to the stores
        if (PackedLunch.take(this)) return;            // [economy] a day's meals before it sets out for a far plot
        if (kitFromTheStores()) return;                // seed, saplings, torches, feed, arrows
        if (resting()) return;                         // off the clock for a bit
        if (movedOnFromSpentGround()) return;          // this patch is finished
        if (unstuckFromGround()) return;               // a plot that cannot be set up is given up
        if (fieldOutOfReach()) return;                 // a field nobody can walk to is given up
        if (changedTrade()) return;                    // the village lost a trade
        if (raisedAChild(12.0)) return;                // the village grew
        // The rack of spare tools, seen to by whoever is at the stores with a moment: the storekeeper
        // at its counter, or a hand at the heart with nothing of its own to do (Toolrack).
        if (level() instanceof net.minecraft.server.level.ServerLevel rack) Toolrack.tend(this, rack);

        // A storekeeper works the chests directly and has nothing to haul: its
        // days were spent standing at the heart (two runs, two storekeepers, not
        // a stroke of work in three game days). It bakes and it builds.
        if (stationTask() == StationTask.STORE) tidyTheStorehouse();
        // A storekeeper with couriers to run keeps its counter: it serves the folk who come for
        // things and sends the couriers out (Storekeeping, Couriers). One without, bakes and builds.
        if (stationTask() == StationTask.STORE && keepTheCounter()) return;
        if (stationTask() == StationTask.STORE && idleHands()) return;
        // The storehouse's couriers wait at its door between runs; they are not lent out.
        if (stationTask() == StationTask.HAUL && Couriers.employed(this)) return;
        if (workedOut() && lendAHand()) return;        // my trade has nothing: help
        considerVillageWork();
    }

    /** The nearest this hand has got to its plot since it last stopped getting
     *  nearer, and how long it has gone without getting nearer. */
    private double lostBest = Double.MAX_VALUE;
    private int lostFor;

    /**
     * A hand that cannot get back to its plot — at the bottom of a ravine it
     * walked into after ore, in a cave, on an island the terrain made, behind a
     * cliff the pathfinder will not climb — stands where it is for the rest of
     * the game: the leash keeps asking for a route there is not. Ten of a
     * village's twenty were found in that state on a plains map, three game days
     * in, without one stroke of work between them, and a miner was holding
     * seventy cobblestone fifty blocks from its mine. Half a minute of getting
     * no nearer, in the working day with nothing to do, and it is put back on its
     * plot.
     */
    private void putBackIfLost() {
        WorkZone zone = workZone();
        // (A guard on its beat, or at the leader's shoulder, is not lost: the streets are its work.)
        if (zone == null || peekJob() != null || !onShift() || onBreak()
            || zone.containsColumn(blockPosition()) || walksAbroad()) {
            lostFor = 0;
            lostBest = Double.MAX_VALUE;
            return;
        }
        double away = Math.sqrt(zone.center().distSqr(blockPosition()));
        if (away < lostBest - 6.0) {                    // getting somewhere
            lostBest = away;
            lostFor = 0;
            return;
        }
        lostFor += 100;                                 // once a folk agenda
        if (lostFor < 1800) return;
        lostFor = 0;
        lostBest = Double.MAX_VALUE;
        rescueToPlot();
    }

    /**
     * The heart of a working village: a pair of hands with nothing to do in
     * its own trade does not stand there. A smelter with no ore does not wait
     * for ore to appear — it goes and fetches some, or carries what the
     * village has to where the village needs it, or cuts timber for the next
     * building. The village's plan says what is short; this turns that into
     * a job the folk can actually do.
     */
    private boolean lendAHand() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        UUID village = ownerId();
        if (village == null) return false;
        // Raising a building: the pack is full of it, and lending a hand would
        // mean banking it straight back into the stores it came out of.
        if (holdsBuildLead()) return false;

        // First, be a courier. Anything in the pack that somebody else's trade
        // wants is a delivery, and delivering beats fetching because the goods
        // already exist. The deposit run routes it to whoever needs it — and
        // only what is ABOVE this hand's own reserve counts (see stashable),
        // else it walked to the far end of the village every five seconds to
        // deliver nothing.
        if (tickCount - lastCourierTick >= 600 && stashable() > 0 && Supply.routeFor(this) != null) {
            lastCourierTick = tickCount;
            sayRoutine("Nothing to do in my own line — running this where it's wanted.");
            enqueue(storesDeposit());
            return true;
        }

        if (helping != null && helpTheBuilder(server, village)) return true;
        if (tickCount - lastHelpTick < 1200) return false;   // one attempt a minute, at most
        for (Villages.Need need : Villages.needs(server, village)) {
            if (takeOn(server, need)) { lastHelpTick = tickCount; return true; }
        }
        // Nothing the village is short of that this hand can fetch: it goes and helps whoever is
        // raising the village's building (and the building goes up faster for it).
        if (helpTheBuilder(server, village)) { lastHelpTick = tickCount; return true; }
        // And failing all of that, whatever its trade, it finds itself something useful.
        if (lendOut(server, village)) { lastHelpTick = tickCount; return true; }
        return false;
    }

    private int tidyTick = -100000;

    /**
     * The storekeeper keeps the Village Storehouse in order while it is on duty there: once a minute,
     * and at once after a big delivery (a few hundred goods in since the last tidy) — like with like,
     * every stack topped up, in order by kind (StorehouseBlockEntity.tidy), and the part stacks of the
     * store chests round about topped up from each other. Once every five minutes, and only when it
     * happened to be standing by, left a busy storehouse in forty half stacks of everything.
     */
    private void tidyTheStorehouse() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || ownerId() == null) return;
        boolean big = Storekeeping.bigDeliverySinceTidy(ownerId());
        if (tickCount - tidyTick < (big ? 200 : 1200) && tickCount >= tidyTick) return;
        com.jrpetty.mcassistant.block.StorehouseBlockEntity store = Storehouses.storeFor(server, ownerId());
        if (store == null || Storekeeping.onDuty(server, ownerId()) != this) return;
        tidyTick = tickCount;
        tidyNow(server, store);
    }

    /** The tidy itself: the storehouse, then the store chests round it; how it went, into the books. */
    private void tidyNow(net.minecraft.server.level.ServerLevel server, com.jrpetty.mcassistant.block.StorehouseBlockEntity store) {
        int[] r = store.tidy();
        int chests = 0;
        for (BlockPos p : Villages.storeChests(server, ownerId())) {
            if (p.equals(store.getBlockPos())) continue;
            if (server.getBlockEntity(p) instanceof net.minecraft.world.Container c
                    && !(c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity)) {
                chests += Stacking.compact(c);
            }
        }
        Storekeeping.tidied(server, ownerId(), displayNameCap(), r[0], r[1], store.getContainerSize(), chests);
        note(Deed.CHESTS_SORTED, 1);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (r[0] - r[1] + chests > 0 && getRandom().nextInt(3) == 0) {
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), "There — the storehouse is in order.",
                "Like with like. That's better.", "A tidy store is a happy village."));
        }
    }

    /** Tests: the storekeeper tidies the storehouse now, as it would on duty. False with no storehouse. */
    public boolean tidyForTests() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || ownerId() == null) return false;
        com.jrpetty.mcassistant.block.StorehouseBlockEntity store = Storehouses.storeFor(server, ownerId());
        if (store == null) return false;
        tidyTick = tickCount;
        tidyNow(server, store);
        return true;
    }

    /**
     * A storekeeper with couriers to run keeps its counter, through the working day: it stands at
     * the storehouse's door, where the folk who come for things are served and the couriers are sent
     * out (Storekeeping, Couriers). One with nobody to run lends a hand, baking and building, as
     * before (two storekeepers once stood at the heart for three game days without a stroke of work).
     */
    private boolean keepTheCounter() {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server) || !onShift() || onBreak()) return false;
        boolean staff = false;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity c && c != this && c.isAlive() && Couriers.employed(c)) { staff = true; break; }
        }
        if (!staff) return false;
        BlockPos spot = Storehouses.standingSpot(server, village);
        if (spot == null) return false;
        if (distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 3.0 * 3.0) {
            if (getNavigation().isDone()) walkTo(spot, 1.0D);
            brain("back to the counter");
            return true;
        }
        brain("at the counter");
        return true;
    }

    // ------------------------------ the forge ----------------------------------

    private int forgeTick = -100000;
    /** The most furnaces a smelter keeps, side by side in one row. */
    public static final int FORGE_MOST = 4;

    /**
     * A smelter's forge grows with its work: at least one furnace, and while there is ore
     * enough to keep more of them busy, up to four, side by side in a neat row facing the
     * same way. Each one is made of eight of the village's own stone: carried, else made
     * from cobble in the pack, else the cobble is fetched from the stores first.
     */
    private void growTheForge() {
        if (stationTask() != StationTask.SMELT || tickCount - forgeTick < 1200) return;
        WorkZone zone = workZone();
        if (zone == null || !zone.containsColumn(blockPosition()) || peekJob() != null) return;
        forgeTick = tickCount;
        java.util.List<BlockPos> bank = forgeBank();
        if (bank.isEmpty() || bank.size() >= FORGE_MOST) return;     // the first is the checklist's (setUpMissingFixture)
        // More furnaces only for more work: a backlog of ore, in the pack or the stores.
        int ore = countMatching(SMELTABLE_ORE);
        if (villageCentre != null) ore += storesHold(villageCentre, Villages.STORE_AREA + 2, SMELTABLE_ORE);
        if (ore < 16 * bank.size()) return;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> furnace = st -> st.is(net.minecraft.world.item.Items.FURNACE);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> stone = st -> st.is(net.minecraft.world.item.Items.COBBLESTONE)
            || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE) || st.is(net.minecraft.world.item.Items.BLACKSTONE);
        if (countMatching(furnace) == 0) {
            if (countMatching(stone) >= 8) {
                removeMatching(stone, 8);
                insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FURNACE));
                brain("made a furnace for the forge");
            } else if (villageCentre != null) {
                enqueue(Job.withdrawAt("cobble", 8, villageCentre, Math.min(112, Math.max(32, Villages.storesRadius(ownerId())))));
                brain("fetching stone for another furnace");
                return;
            } else {
                return;
            }
        }
        BlockPos spot = nextInTheRow(bank);
        if (spot == null) { brain("no room in the row for another furnace"); return; }
        net.minecraft.world.level.block.state.BlockState first = level().getBlockState(bank.get(0));
        net.minecraft.core.Direction faces = first.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING)
            ? first.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING) : net.minecraft.core.Direction.NORTH;
        if (removeMatching(furnace, 1) < 1) return;
        level().setBlockAndUpdate(spot, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, faces));
        ZoneChests.mark(level(), spot);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        forgetChestIndex();
        FolkTalk.speak(this, "Another furnace for the forge — " + (bank.size() + 1) + " going now.");
    }

    /** The smelter's furnaces in its ground, the first one set down first in the list. */
    private java.util.List<BlockPos> forgeBank() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        WorkZone zone = workZone();
        if (zone == null) return out;
        for (ZoneChests.Found f : ZoneChests.around(level(), zone.center(), Math.max(8, zone.radius() + 2), 8)) {
            if (!f.stillThere() || !zone.containsColumn(f.pos())) continue;
            if (f.blockEntity() instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) out.add(f.pos());
        }
        BlockPos c = zone.center();
        out.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(c)));
        return out;
    }

    /**
     * Where the next furnace goes: beside the others, in the same row, facing the same way —
     * along the line across the first furnace's front, either side, out to four in all.
     */
    @Nullable
    private BlockPos nextInTheRow(java.util.List<BlockPos> bank) {
        BlockPos first = bank.get(0);
        net.minecraft.world.level.block.state.BlockState st = level().getBlockState(first);
        net.minecraft.core.Direction faces = st.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING)
            ? st.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING) : net.minecraft.core.Direction.NORTH;
        net.minecraft.core.Direction along = faces.getClockWise();
        WorkZone zone = workZone();
        for (int k = 1; k < FORGE_MOST; k++) {
            for (int sign : new int[]{ 1, -1 }) {
                BlockPos p = first.relative(along, k * sign);
                if (bank.contains(p)) continue;
                // Only next to the row as it stands, so the row has no gaps.
                if (!bank.contains(p.relative(along, -sign))) continue;
                if (zone != null && !zone.containsColumn(p)) continue;
                if (!level().getBlockState(p).canBeReplaced() || !level().getFluidState(p).isEmpty()) continue;
                if (!level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), net.minecraft.core.Direction.UP)) continue;
                if (getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) continue;
                return p;
            }
        }
        return null;
    }

    // ------------------------------ nobody stands about ------------------------

    /** Another hand's ground this one is helping out on, what for, and until when. */
    @Nullable private WorkZone lentTo;
    @Nullable private com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind lentFor;
    private int lentUntil;
    private boolean lentSetTo;
    private double lentBest = Double.MAX_VALUE;
    private int lentStalls;
    private int retireTick = -100000;

    /** Lent to the heart, to set the village's first stores down there. */
    private boolean lentToFound;

    @Override
    @Nullable
    protected WorkZone lentZone() {
        return lentTo != null && tickCount < lentUntil ? lentTo : null;
    }

    @Override
    protected boolean chestBelongsAtTheHeart() {
        return villageCentre != null && level() instanceof net.minecraft.server.level.ServerLevel server
            && ownerId() != null && !Villages.hasStores(server, ownerId());
    }

    /**
     * A village with no stores at all — a lone settler's, one that took over a few villagers
     * — keeps its goods nowhere. The first hand carrying a chest takes it to the heart and
     * sets it down there: the village's stores, until the storehouse stands. It used to go
     * down on that hand's own plot, and every hand after it put down one of its own.
     */
    private boolean foundTheStores(net.minecraft.server.level.ServerLevel server, UUID village) {
        if (lentTo != null || villageCentre == null || !onShift() || isBaby()) return false;
        if (countMatching(st -> st.is(net.minecraft.world.item.Items.CHEST)) == 0) return false;
        if (Villages.hasStores(server, village)) return false;
        lentTo = WorkZone.around(villageCentre, 2, 4);
        lentFor = null;
        lentToFound = true;
        lentUntil = tickCount + 2400;
        lentSetTo = false;
        lentBest = Double.MAX_VALUE;
        lentStalls = 0;
        brain("taking a chest to the heart: the village's first stores");
        return true;
    }

    /** Set the chest down here, at the heart, as the village's stores. */
    private void setDownTheFirstChest() {
        BlockPos feet = blockPosition();
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = feet.offset(dx, dy, dz);
                        if (!level().getBlockState(p).canBeReplaced() || !level().getFluidState(p).isEmpty()) continue;
                        if (!level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), net.minecraft.core.Direction.UP)) continue;
                        if (getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) continue;
                        if (removeMatching(st -> st.is(net.minecraft.world.item.Items.CHEST), 1) < 1) return;
                        level().setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                        ZoneChests.mark(level(), p);
                        if (ownerId() != null) Villages.forgetStores(ownerId());
                        forgetChestIndex();
                        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        FolkTalk.speak(this, "There — the village's stores, here at the heart.");
                        return;
                    }
                }
            }
        }
    }

    /**
     * The last thing a hand with nothing to do does before standing about: anything at all
     * the village can use, its own trade notwithstanding. The old chests are cleared into
     * the storehouse; what it carries goes to the stores; and otherwise it goes to the
     * woods or the quarry — a woodcutter's ground, a miner's — and brings timber or stone
     * home, which a village never has enough of. Returns true if it found something.
     */
    private boolean lendOut(net.minecraft.server.level.ServerLevel server, UUID village) {
        // Not a miner, for the reason below: it is "worked out" for a minute between two runs (a
        // short one backs it off, and the walk back from the stores counts as no work), and an
        // old chest across the village took it off its mine for up to two thousand ticks at a
        // time: t10's miners spent up to a fifth of their first day clearing chests.
        if (stationTask() != StationTask.MINE && retireAChest(server, village)) return true;
        if (stashable() > 0) {
            sayRoutine("Nothing in my own line — taking this to the stores.");
            enqueue(storesDeposit());
            return true;
        }
        // Off to the woods or the quarry — but not the crafts, the storekeeper or the watch:
        // their work comes in bursts at their own bench, stand or post (a brew is twenty
        // seconds of waiting), and a brewer sent for timber between brews never brewed.
        StationTask trade = stationTask();
        // Nor a miner: its "nothing to do" is the walk up its own stairs, and lent out to cut stone
        // at the surface it brought home dirt and cobble all afternoon while the age waited on iron.
        if (trade.isCraft() || trade == StationTask.STORE || trade == StationTask.GUARD || trade == StationTask.MINE) return false;
        // Timber one time, stone the next, whichever has ground to get it from.
        com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind first = (tickCount / 2400) % 2 == 0
            ? com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS : com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE;
        com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind second = first == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS
            ? com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE : com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS;
        for (var kind : java.util.List.of(first, second)) {
            // Stone wants a pickaxe: one from the stores, or timber instead. A farmer sent to the
            // quarry with a hoe stood there saying it couldn't cut stone without one, day after day.
            if (kind == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE && countCarried(AssistantEntity::isPickaxe) == 0
                && (villageCentre == null || drawFrom(villageCentre, AssistantEntity::isPickaxe, 1, buildStoresRadius()) <= 0)) continue;
            WorkZone ground = groundFor(server, village, kind);
            if (ground == null) continue;
            lentTo = ground;
            lentFor = kind;
            lentUntil = tickCount + 4800;
            lentSetTo = false;
            lentBest = Double.MAX_VALUE;
            lentStalls = 0;
            lentToFound = false;
            String what = kind == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS ? "timber" : "stone";
            brain("nothing in my own line — off to fetch " + what + " for the stores");
            sayRoutine("Nothing doing in my own line. I'll fetch " + what + " for the village.");
            return true;
        }
        return false;
    }

    /** [mine-safety] Tests: the ground a hand with nothing to do would be lent out to for this, or null. */
    @Nullable
    public WorkZone groundForTests(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind kind) {
        return level() instanceof net.minecraft.server.level.ServerLevel server && ownerId() != null ? groundFor(server, ownerId(), kind) : null;
    }

    /** [mine-safety] Tests: lent out now, as a hand with nothing to do is; the ground it is lent to, or null. */
    @Nullable
    public WorkZone lendOutForTests() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || ownerId() == null) return null;
        endLending();
        lendOut(server, ownerId());
        return lentZone();
    }

    /** [mine-safety] Tests: the lend over, as when its time is up. */
    public void lendLapsedForTests() {
        lentUntil = tickCount;
    }

    /**
     * Ground with timber or stone on it: its own plot if that has some, else (timber) the nearest
     * woodcutter's of the village.
     */
    @Nullable
    private WorkZone groundFor(net.minecraft.server.level.ServerLevel server, UUID village,
                               com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind kind) {
        if (workZone() != null && workZone().containsColumn(blockPosition()) && resourceNearby(kind, 16)) return workZone();
        // [mine-safety] Never a miner's ground for stone: a hand lent out to one cut its way down the stairs after
        // the nearest rock, and only a miner at work goes down the mine. Timber instead (lendOut).
        if (kind == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE) return null;
        StationTask trade = kind == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS ? StationTask.WOOD : StationTask.MINE;
        WorkZone best = null;
        double bestDist = Double.MAX_VALUE;
        for (AssistantEntity mate : allFor(village)) {
            if (mate == this || !mate.isAlive() || mate.stationTask() != trade || mate.workZone() == null) continue;
            double d = mate.workZone().center().distSqr(blockPosition());
            if (d < bestDist && d < 160.0 * 160.0) { bestDist = d; best = mate.workZone(); }
        }
        return best;
    }

    /**
     * Lent out: walk to the ground, set to work there (a gather, then the load to the
     * stores), and come home when it is done. A hand that can get no nearer to the ground,
     * four looks running, gives it up.
     */
    private boolean carryOnLending() {
        WorkZone ground = lentZone();
        if (ground == null) { endLending(); return false; }
        if (lentSetTo) {                           // the work and the load home are done
            endLending();
            return false;
        }
        if (!ground.containsColumn(blockPosition())) {
            if (getNavigation().isDone()) {
                BlockPos c = ground.center();
                double d = Math.sqrt(c.distSqr(blockPosition()));
                if (d < lentBest - 1.5) { lentBest = d; lentStalls = 0; } else lentStalls++;
                if (!getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1.0D)) lentStalls++;
                if (lentStalls >= 4) { brain("could not get to the ground I was lending a hand on"); endLending(); return false; }
            }
            brain("on the way to lend a hand");
            return true;
        }
        if (lentToFound) {
            setDownTheFirstChest();
            endLending();
            return true;
        }
        lentSetTo = true;
        enqueue(Job.gather(lentFor, 32));
        enqueue(storesDeposit());
        brain("lending a hand: " + lentFor.label);
        return true;
    }

    private void endLending() {
        lentTo = null;
        lentFor = null;
        lentSetTo = false;
        lentToFound = false;
    }

    /** Clear an old chest into the Village Storehouse, if there are any left to clear. */
    private boolean retireAChest(net.minecraft.server.level.ServerLevel server, UUID village) {
        if (tickCount - retireTick < 400) return false;
        retireTick = tickCount;
        BlockPos chest = Retiring.next(this, server, village);
        if (chest == null) return false;
        enqueue(Job.retire(chest));
        enqueue(storesDeposit());
        brain("clearing out an old chest into the storehouse");
        return true;
    }

    /** The builder this idle hand is helping, and until when. */
    @Nullable private UUID helping;
    private int helpingUntil;
    /** Who is helping this builder, and when each last checked in. */
    private final java.util.Map<UUID, Integer> helpers = new java.util.HashMap<>();

    /**
     * An idle hand helps the village's builder: it goes to the building going up and fetches and
     * carries for the one laying the blocks, which lays them faster for it (buildPaceTicks).
     * Idle folk used to stand at their plots — "46 of 61 not worked in five minutes" — while one
     * builder raised every house in the town alone.
     */
    private boolean helpTheBuilder(net.minecraft.server.level.ServerLevel server, UUID village) {
        UUID lead = Villages.currentLead(village, server.getGameTime());
        if (lead == null || lead.equals(getUUID()) || !(server.getEntity(lead) instanceof VillageFolkEntity builder)) {
            helping = null;
            return false;
        }
        Job j = builder.peekJob();
        if (j == null || j.type() != Job.Type.BUILD) { helping = null; return false; }
        if (helping == null || !helping.equals(lead)) {
            helping = lead;
            helpingUntil = tickCount + 2400;
            brain("helping " + builder.displayNameCap() + " with the building");
        }
        if (tickCount > helpingUntil) { helping = null; return false; }
        if (distanceToSqr(builder) > 6.0 * 6.0) {
            getNavigation().moveTo(builder, 1.0D);
        } else {
            getNavigation().stop();
            getLookControl().setLookAt(builder);
            swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            builder.helpers.put(getUUID(), (int) server.getGameTime());
        }
        return true;
    }

    @Override
    protected int buildHelpers() {
        int now = (int) level().getGameTime();
        helpers.values().removeIf(t -> now - t > 240 || now < t);
        return helpers.size();
    }

    private int lastHelpTick = -100000;
    private int lastCourierTick = -100000;

    /** Can this hand do anything about that particular want? */
    private boolean takeOn(net.minecraft.server.level.ServerLevel server, Villages.Need need) {
        switch (need.task()) {
            case COAL -> {
                // Only where there is some: a gather job sent to a place with none
                // reads "nothing within sixteen blocks" and ends, and did, every
                // couple of minutes, for every hand lending itself out.
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.COAL, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.COAL,
                    Math.min(32, Math.max(8, need.amount()))));
                enqueue(storesDeposit());
                return true;
            }
            case DIAMOND -> {
                // The deep is a miner's business; everyone else helps by
                // keeping the stores moving. A mine floor set 24 blocks under
                // the hillside it was staked on sits a hundred blocks above the
                // nearest diamond, though, so the shaft has to go down before
                // the seam can be found at all — which is why no settlement
                // could ever leave the Diamond Age.
                if (stationTask() == StationTask.MINE) return deepenShaft();
                if (stashable() > 0) { enqueue(storesDeposit()); return true; }
                return false;
            }
            case OBSIDIAN -> {
                // Nothing under diamond drops obsidian, so this is a job for
                // one miner in the whole village: the one that has been given
                // the pickaxe. It is down there already; the lava is what it
                // has been walking round for weeks.
                if (stationTask() != StationTask.MINE) {
                    if (stashable() > 0) { enqueue(storesDeposit()); return true; }
                    return false;
                }
                if (countCarried(st -> st.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)) == 0) {
                    return deepenShaft();
                }
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.OBSIDIAN,
                    Math.min(16, Math.max(4, need.amount()))));
                enqueue(storesDeposit());
                return true;
            }
            case LOGS -> {
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS,
                    Math.min(48, Math.max(16, need.amount()))));
                enqueue(storesDeposit());
                return true;
            }
            case STONE -> {
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE,
                    Math.min(64, Math.max(16, need.amount()))));
                enqueue(storesDeposit());
                return true;
            }
            case IRON -> {
                // No withdraw-and-redeposit "courier" here: a deposit with no
                // route picks the NEAREST chest, which is the one the ore was
                // just taken out of, so the run moved the ore in a circle. The
                // carry-what-is-wanted path above is the real courier.
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.IRON, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.IRON,
                    Math.min(32, Math.max(8, need.amount()))));
                enqueue(storesDeposit());
                return true;
            }
            case FOOD -> {
                // Get what has been grown into the stores first — it exists
                // already, which beats anything that has to be made. Only what
                // is above this hand's own reserve, though: every folk always
                // carries its rations and its kit, so "anything in the pack"
                // was always true, the deposit moved nothing, and the hunt
                // below could never happen.
                if (countStashable(st -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null) > 0) {
                    enqueue(storesDeposit());
                    return true;
                }
                // Then hunt. A field takes days; a herd on the doorstep is
                // meat this afternoon, and a hungry village cannot wait for
                // wheat. Farmers stay on the field — the crop is the long
                // answer and somebody has to be planting it.
                if (stationTask() != StationTask.FARM && adultAnimalsNearby(24) >= 2) {
                    enqueue(Job.hunt(null, 3));
                    enqueue(storesDeposit());
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }


    // ------------------------------ belonging --------------------------------

    /** Join the settlement nearest to where we woke up, or found one here. */
    private void settle() {
        Villages.Village v = Villages.nearest(level(), blockPosition());
        if (v == null) {
            // The flattest ground about, not just where it happens to stand.
            BlockPos at = blockPosition();
            if (level() instanceof net.minecraft.server.level.ServerLevel server) {
                BlockPos flat = Land.flattest(server, at, 24);
                if (flat != null) at = flat;
            }
            v = Villages.found(level(), at);
            say("There's good flat ground here. This'll do for a village.");
        }
        joinVillage(v.id(), v.centre());
    }

    // ------------------------------ a trade ----------------------------------

    /**
     * Take up whatever the village is short of, then go and find ground for
     * it. The trade is chosen first and the ground second, because a farmer
     * needs water and a miner needs stone — what you are decides where you go.
     */
    private void takeUpATrade() {
        // Claim the trade BEFORE going to look for ground. The village works
        // out what it is short of from what its folk ARE, so a folk that has
        // decided but not yet settled used to be invisible — and every folk
        // in the village would pick the same trade, look for the same ground,
        // and fail together, for ever.
        // [ua] And claim it at once, ahead of the wait between searches: the claim is
        // cheap, only the search is not. A folk that had looked for ground a minute
        // before it lost its trade (married into another town, moved away, gave up
        // its fishing) stood about with none for up to two minutes more.
        if (stationTask() == StationTask.NONE) {
            setStation(blockPosition(), Fears.steer(this, Villages.needed(ownerId())));   // [individual] not a trade it fears
        }
        // Looking for ground is expensive and the answer rarely changes from
        // one second to the next. Once a minute is plenty, and it stops every
        // folk in a village re-running a quarter-million block reads on the
        // same tick for ever.
        // Staggered by entity id: a hundred folk all failing to find ground on
        // the same tick, once a minute, is a hundred searches in one tick.
        if (tickCount - searchFailTick < 1200 + (getId() % 12) * 100) {
            // No ground of its own yet. A pair of hands stood at the heart is
            // the village's to use — baking, building — rather than a pair
            // wandering the square until a plot turns up.
            if (!idleHands()) roam();
            return;
        }
        searchFailTick = tickCount;
        StationTask trade = stationTask();
        BlockPos site = findSite(trade, radiusFor(trade));
        if (site == null) {
            // This trade has nowhere to work HERE. Rather than stand in a
            // field looking for water that does not exist, try the next thing
            // the village wants — a settlement in a desert should end up
            // quarrying and cutting, not waiting for a farm it cannot have.
            // Look somewhere ELSE next time. The bearing only ever turned when a
            // mine was given up, so a farmer whose first octant had no water
            // scanned the very same ground every minute until it gave up the
            // trade — and a whole side of the village was never looked at.
            searchBearing++;
            triedTrades++;
            if (triedTrades >= 3) {
                triedTrades = 0;
                // [sf] Three looks on three bearings and no open water the town can walk to: no fisher is wanted
                // for a few days, so the next newcomer is not sent to look for it again (Villages.craftReady).
                if (trade == StationTask.FISH && ownerId() != null) Villages.noWaterForFishers(ownerId(), level().getGameTime());
                StationTask fallback = nextTradeAfter(trade);
                if (fallback != trade) setStation(blockPosition(), fallback);
            }
            roam();
            return;
        }
        triedTrades = 0;
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade, site));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
    }

    private int triedTrades;

    /** The next trade worth trying when this one has nowhere to work. Ordered
     *  by how little ground it is fussy about: stone and timber are almost
     *  everywhere, a farm needs water, a forge needs nothing at all. */
    private StationTask nextTradeAfter(StationTask trade) {
        // Never the forge: it is one trade for the whole village and works on
        // what the others bring it, so a farmer with no water who became a
        // smelter stood at an empty furnace for the rest of its life.
        return switch (trade) {
            case FARM -> StationTask.WOOD;
            case WOOD -> StationTask.MINE;
            case MINE -> StationTask.FARM;
            default -> StationTask.WOOD;
        };
    }

    /** A new field's reach (one nine-by-nine square round its water), and the most it grows to (three
     *  squares by three, twenty-seven across: FarmGoal.CELL). */
    static final int FIELD_FIRST = 4, FIELD_MOST = 13;
    /** The most a square of the farmland may lie above or below the town. */
    static final int FIELD_CLIMB = 10;
    private int fieldCheckTick = -100000;

    /**
     * A field that is full grows. Once most of a farmer's first square is under crops it lays out
     * the squares round it (FarmGoal digs a water hole in the middle of each and tills the eighty
     * squares round it), from nine blocks across to twenty-seven, as long as the new ground
     * is clear of the town, of the village's buildings and of the other fields. (The plots were
     * a fixed size from the first day, and the first season's few rows were all most of them ever
     * planted.) The seed for the new squares is kept back from the stores (jobDepositReserve).
     */
    private void growTheField() {
        if (stationTask() != StationTask.FARM || tickCount - fieldCheckTick < (Leader.widening(ownerId()) ? 1200 : 2400)) return;
        fieldCheckTick = tickCount;
        WorkZone z = workZone();
        UUID id = ownerId();
        if (z == null || id == null || villageCentre == null || z.radius() >= FIELD_MOST) return;
        int r = z.radius();
        BlockPos c = z.center();
        int ground = 0, field = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 3; dy >= -3; dy--) {
                    at.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    net.minecraft.world.level.block.state.BlockState st = level().getBlockState(at);
                    if (st.is(net.minecraft.world.level.block.Blocks.FARMLAND)) { ground++; field++; break; }
                    if ((st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.DIRT))
                        && level().getBlockState(at.above()).canBeReplaced()) { ground++; break; }
                }
            }
        }
        // Not full yet: six in ten under crops — half, when the leader wants the fields widened.
        if (field < 16 || field * 10 < ground * (Leader.widening(id) ? 5 : 6)) return;
        // The next ring of nine-by-nine squares round the first (FarmGoal lays them out, a water source
        // in the middle of each): from one square to three by three.
        int grown = Math.min(FIELD_MOST, r < FIELD_MOST ? FIELD_MOST : r + 1);
        // Clear of the town's own ground and of every lot it has built on (the town keeps off a
        // field once it is there: Villages.lotKeptOff).
        int fx = c.getX() - villageCentre.getX(), fz = c.getZ() - villageCentre.getZ();
        if (Math.max(Math.abs(fx), Math.abs(fz)) - grown <= Villages.FIRST_BLOCK) return;
        if (Villages.builtOver(id, fx, fz, grown, grown)) return;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(id)) {
            BlockPos a = b.anchor();
            if (Math.max(Math.abs(a.getX() - c.getX()), Math.abs(a.getZ() - c.getZ())) <= grown + 8) return;
        }
        for (AssistantEntity mate : Villages.folkOf(id)) {
            WorkZone o = mate == this ? null : mate.workZone();
            if (o == null) continue;
            int gap = Math.max(Math.abs(o.center().getX() - c.getX()), Math.abs(o.center().getZ() - c.getZ()));
            if (gap <= grown + o.radius()) return;                     // up against another plot
        }
        assignPlot(WorkZone.around(c, grown, z.depth()), patchName() != null ? patchName() : patchNameFor(StationTask.FARM));
        brain("the field is full: breaking new ground, " + (2 * grown + 1) + " across now, square by square");
        if (getRandom().nextInt(2) == 0) {
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), "The field's full. Time to dig the next water hole and lay out another square.",
                "Another square of furrows round a new water hole — the field's growing."));
        }
    }

    /** Tests: where this folk would stake a new field. */
    @Nullable
    public BlockPos farmSiteForTests() {
        return findSite(StationTask.FARM, radiusFor(StationTask.FARM));
    }

    /** Tests: the farmer's look at its field now, whatever the clock says. */
    public void growTheFieldForTests() {
        fieldCheckTick = -100000;
        growTheField();
    }

    private static int radiusFor(StationTask trade) {
        return switch (trade) {
            case FARM -> FIELD_FIRST;   // a first field; it grows as it fills (growTheField)
            case WOOD -> 14;     // woodland is worked wide
            case MINE -> 8;
            case HUNT -> 20;     // hunting grounds are walked wide
            default -> 6;        // the smelter works at its furnaces
        };
    }

    /**
     * The deepest a village's mine goes: fourteen over the bottom of the world (Y-50 in the overworld).
     * Every open cave below Y-54 is full of lava, and the diamond mines went down to eight over the
     * bottom, Y-56, through the middle of it: the hundred-day town lost nine and eleven folk a week in
     * lava once its miners went for the diamonds. There are diamonds enough at Y-50.
     */
    private int deepestMine() {
        return level().getMinBuildHeight() + 14;
    }

    private int depthFor(StationTask trade, BlockPos site) {
        if (trade != StationTask.MINE) return WorkZone.DEFAULT_DEPTH;
        int floor = deepestMine();
        int shallow = site.getY() - 24;
        // Down to the iron from the first day: the Wood Age's shallow mines worked the stone at forty-odd,
        // where there is next to none, and the Stone Age then had none to start on. (The staircase is
        // kept and walked again, so the depth costs one long dig, not one a run.)
        return Math.max(floor, Math.min(shallow, IRON_SEAM_Y));
    }

    /**
     * Where iron is thickest in the ground this game makes: around sixteen. A mine cut
     * twenty-four under a hillside at seventy works the stone at forty-six, where there
     * is next to none — every village on every real map held nine iron at most, and the
     * Iron Age asks a village of twenty for a hundred and eight.
     */
    private static final int IRON_SEAM_Y = 16;
    private int seamCheckTick = -100000;

    /**
     * A village's miners dig where its metal is. Once it is out of the Wood Age (stone it
     * has; iron is what comes next) a mine is taken down to the iron seam; from the Diamond
     * Age every other miner that carries an iron pickaxe goes on down to the diamonds,
     * near the bottom of the world. The plot keeps its place; only its depth changes.
     */
    private boolean seekTheSeam() {
        if (stationTask() != StationTask.MINE) return false;
        WorkZone zone = workZone();
        UUID village = ownerId();
        if (zone == null || village == null) return false;
        if (tickCount - seamCheckTick < 1200) return false;
        seamCheckTick = tickCount;
        Villages.Age at = Villages.ageOf(village);
        // A plot the town has built over (a house, the square): the miner takes a face of the town's mine
        // instead, at the depth it was working (TownMine). Staircases cut down from plots the town had
        // built over took up a house's floor, and once the square's. (An old plot of its own out of the
        // town's way is worked out first, and its next ground is a face of the mine.)
        if (villageCentre != null && level() instanceof net.minecraft.server.level.ServerLevel mineLevel
                && TownMine.underTheTown(TownMine.builtGround(village, villageCentre, level().getGameTime()), zone.center(), zone.radius() + 2)) {
            BlockPos face = TownMine.faceFor(mineLevel, this, villageCentre, this::diggable);
            if (face != null) {
                assignPlot(WorkZone.around(face, radiusFor(StationTask.MINE), zone.depth()), patchNameFor(StationTask.MINE));
                setAutonomous(true);
                brain("moved to a face of the town's mine");
                return true;
            }
        }
        int floor = deepestMine();
        // A mine marked down into the lava (below the deepest a mine now goes, from an older world)
        // comes up to the deepest it may go.
        if (zone.depth() < floor) {
            assignPlot(WorkZone.around(zone.center(), zone.radius(), floor), patchNameFor(StationTask.MINE));
            setAutonomous(true);
            brain("mine brought up out of the lava, to Y" + floor);
            return true;
        }
        if (at.ordinal() < Villages.Age.STONE.ordinal()) return false;
        // [caves] The cave team found a rich vein within the mine's reach: this miner's next face goes toward it, dug to
        // its depth, and is held there while it follows the lead (TownMine.leadFor).
        if (villageCentre != null && level() instanceof net.minecraft.server.level.ServerLevel leadLevel) {
            int tier = pickTierCarried();
            TownMine.Lead lead = TownMine.leadFor(leadLevel, this, villageCentre, floor,
                ore -> switch (ore) { case "diamond", "emerald", "gold" -> tier >= 3; default -> tier >= 2; });
            if (lead != null) {
                assignPlot(WorkZone.around(lead.top(), radiusFor(StationTask.MINE), lead.depth()), patchNameFor(StationTask.MINE));
                setAutonomous(true);
                brain("moved to a face of the town's mine over the cave team's " + lead.ore() + ", down to Y" + lead.depth());
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "The cave team found " + lead.ore() + " under this way. I'll follow their lead.",
                    "If the cave dwellers say there's " + lead.ore() + " down there, that's where I'm digging."));
                return true;
            }
            if (TownMine.onALead(village, zone.center(), leadLevel.getDayTime() / 24000L)) return false;
        }
        int want = Math.max(floor, IRON_SEAM_Y);
        if (at.ordinal() >= Villages.Age.DIAMOND.ordinal() && pickTierCarried() >= 3 && deepMiner()) {
            want = floor;
        }
        // [wf] Short of coal for the age: up to the coal seam, two miners in three, and there till the
        // stores have it with some to spare (Fuel.coalSeamWanted). The mountain town of seventy-seven sat
        // in the Stone Age with all twenty of its mines down at the iron, where there is a sixth of the
        // coal there is at ninety-six.
        int coal = Fuel.coalSeamFor(zone.center().getY(), want);
        boolean atCoal = coal > 0 && zone.depth() == coal;
        if (coal > 0 && want != floor && coalMiner()
                && level() instanceof net.minecraft.server.level.ServerLevel server
                && Fuel.coalSeamWanted(server, village, atCoal)) {
            if (atCoal) return false;
            assignPlot(WorkZone.around(zone.center(), zone.radius(), coal), patchNameFor(StationTask.MINE));
            setAutonomous(true);
            brain("mine taken to Y" + coal + " for the coal the age wants");
            if (getRandom().nextInt(3) == 0) {
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Coal's what we're short of. Up to the black seam with me.",
                    "No coal down at the iron worth the name. I'll dig where it's thick."));
            }
            return true;
        }
        if (!atCoal && zone.depth() <= want + 4) return false;   // there already, or deeper
        assignPlot(WorkZone.around(zone.center(), zone.radius(), want), patchNameFor(StationTask.MINE));
        setAutonomous(true);
        brain("mine taken down to Y" + want + " for the " + (want == floor ? "diamonds" : "iron"));
        return true;
    }

    /** [wf] Tests: the miner's look at where its mine should be, now, whatever the clock says. */
    public boolean seekTheSeamForTests() {
        seamCheckTick = -100000;
        return seekTheSeam();
    }

    /**
     * [wf] Two miners in three, counted in a fixed order, go up for the coal while the age is short of
     * it; the third keeps on at the iron the next age will want. A village of one or two miners sends
     * them all.
     */
    private boolean coalMiner() {
        UUID village = ownerId();
        if (village == null) return false;
        UUID me = getUUID();
        int before = 0;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate == this || mate.stationTask() != StationTask.MINE) continue;
            if (mate.getUUID().compareTo(me) < 0) before++;
        }
        return before % 3 != 2;
    }

    private int pickCheckTick = -100000;

    /**
     * A stone pickaxe breaks iron ore for its iron and diamond ore for nothing, and only
     * a diamond pickaxe takes obsidian. Nothing made a settler a better one: no village
     * could ever have dug its way past the Iron Age. A miner of a village that has come
     * to iron has one made out of the stores — three iron, and a coal to smelt each raw
     * one — and in the Nether Age a diamond one out of three diamonds.
     */
    private void pickaxeFromTheStores() {
        if (stationTask() != StationTask.MINE || villageCentre == null || ownerId() == null) return;
        if (tickCount - pickCheckTick < 2400) return;
        pickCheckTick = tickCount;
        Villages.Age at = Villages.ageOf(ownerId());
        int r = buildStoresRadius();
        net.minecraft.world.item.Item diamond = net.minecraft.world.item.Items.DIAMOND;
        // One diamond pickaxe in the village, for the one who makes the obsidian — more
        // only out of diamonds beyond what the age asks for. Three apiece for twenty
        // miners would be sixty diamonds, and the gateway waits on eighteen.
        if (at.ordinal() >= Villages.Age.NETHER.ordinal() && pickTierCarried() < 4 && deepMiner()
                && (!anyoneCarriesADiamondPick() || diamondsToSpare())) {
            int before = countCarried(st -> st.is(diamond));
            int got = before >= 3 ? 0 : drawFrom(villageCentre, st -> st.is(diamond), 3 - before, r);
            if (before + got >= 3) {
                removeMatching(st -> st.is(diamond), 3);
                insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
                brain("a diamond pickaxe made from the stores");
                return;
            }
            returnTo(villageCentre, st -> st.is(diamond), before, r);
        }
        // Iron pickaxes when the diamonds want them, not before: a stone one breaks iron
        // ore, and an Iron Age village that spent each iron that came in on pickaxes and
        // buckets held none of the hundred and fifty its age asks for.
        // Only for the miners that go down for the diamonds: the rest work the iron seam,
        // where stone does.
        if (at.ordinal() >= Villages.Age.DIAMOND.ordinal() && pickTierCarried() < 3 && deepMiner()
                && ironFromTheStores(3)) {
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
            brain("an iron pickaxe made from the stores");
        }
    }

    /**
     * Every other miner of the village, counted in a fixed order, goes deep: the first
     * always does. Odd and even ids did the same for a town of twenty, and left a village
     * whose two miners both drew odd with nobody looking for diamonds at all.
     */
    /**
     * The village decides how deep its mines go, not a ladder of levels: a novice sent
     * down for the diamonds goes all the way. Before this, no village miner below level
     * 20 could dig under Y16, the deep plot was never dug, and no village ever found a
     * diamond.
     */
    @Override
    public boolean minesWhereSent() { return true; }

    private boolean deepMiner() {
        UUID village = ownerId();
        if (village == null) return false;
        UUID me = getUUID();
        int before = 0;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate == this || mate.stationTask() != StationTask.MINE) continue;
            if (mate.getUUID().compareTo(me) < 0) before++;
        }
        return before % 2 == 0;
    }

    private boolean anyoneCarriesADiamondPick() {
        for (AssistantEntity mate : Villages.folkOf(ownerId())) {
            if (mate != this && mate.pickTierCarried() >= 4) return true;
        }
        return false;
    }

    private boolean diamondsToSpare() {
        UUID village = ownerId();
        if (village == null || villageCentre == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        int want = 2 * com.jrpetty.mcassistant.village.VillageMath.diamondsWanted(Villages.headcount(village));
        return Villages.stock(server, villageCentre, Villages.Task.DIAMOND, Villages.storesRadius(village)) >= want + 3;
    }

    private int obsidianTick = -100000;

    /**
     * Obsidian is made, not found. The gateway wants ten, and the only way the village
     * had of getting them was to come across obsidian already lying in the world, which a
     * mine at the bottom of the world almost never does — though the lava it walls off
     * every few blocks down there is everywhere. A miner with the village's diamond
     * pickaxe and a bucket of water does what a player does: water on a lava pool's
     * surface, the obsidian broken out one block at a time, and the hole stopped with
     * cobblestone where there is still lava behind it.
     */
    public void obsidianFromLava() {
        if (stationTask() != StationTask.MINE || villageCentre == null || ownerId() == null) return;
        if (tickCount - obsidianTick < 200) return;
        obsidianTick = tickCount;
        UUID village = ownerId();
        if (Villages.ageOf(village) != Villages.Age.NETHER || Villages.hasBuilt(village, "gateway")) return;
        if (pickTierCarried() < 4) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        net.minecraft.world.item.Item obsidian = net.minecraft.world.item.Items.OBSIDIAN;
        int have = Villages.stock(server, villageCentre, Villages.Task.OBSIDIAN, Villages.storesRadius(village))
            + countCarried(st -> st.is(obsidian));
        if (have >= com.jrpetty.mcassistant.village.VillageMath.obsidianWanted(Villages.headcount(village))) return;
        // Only where there is lava to pour on: the bucket is not made for nothing.
        BlockPos me = blockPosition();
        BlockPos lava = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-6, -4, -6), me.offset(6, 2, 6))) {
            net.minecraft.world.level.material.FluidState f = level().getFluidState(p);
            if (!f.is(net.minecraft.tags.FluidTags.LAVA) || !f.isSource()) continue;
            boolean open = false;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                if (level().getBlockState(p.relative(d)).isAir()) { open = true; break; }
            }
            if (!open) continue;                    // a pool's surface, not lava inside the rock
            double dist = p.distSqr(me);
            if (dist < best) { best = dist; lava = p.immutable(); }
        }
        if (lava == null) return;
        if (countCarried(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET)) == 0) {
            if (!ironFromTheStores(3)) return;
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
            brain("a bucket of water from the stores, for the lava");
        }
        boolean behind = false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!level().getFluidState(lava.relative(d)).isEmpty()) { behind = true; break; }
        }
        level().setBlockAndUpdate(lava, behind ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        level().playSound(null, lava, net.minecraft.sounds.SoundEvents.LAVA_EXTINGUISH,
            net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 2.6F);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
            lava.getX() + 0.5, lava.getY() + 1.0, lava.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.0);
        getLookControl().setLookAt(lava.getX() + 0.5, lava.getY() + 0.5, lava.getZ() + 0.5);
        equipBestTool(Blocks.OBSIDIAN.defaultBlockState());
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        damageHeldTool();
        insertItem(new net.minecraft.world.item.ItemStack(obsidian));
        brain("water on the lava: obsidian for the gateway (" + (have + 1) + ")");
    }

    private int stoneToolTick = -100000;

    /**
     * Children are sent out with wooden tools, and nothing gave them better ones: a
     * village of thirty-five on its sixteenth day had miners digging the iron seam with
     * wooden pickaxes, which break iron ore for nothing, and one with no pickaxe at all.
     * A folk whose trade tool is wood, or missing, has a stone one made from the stores:
     * three cobblestone and a plank.
     */
    private int guardKitTick = -100000;

    /**
     * The smith's iron for the watch: a guard with an empty armour slot puts on the piece the
     * stores hold, and one with no iron sword takes one. The smithy kept a rack of helmets,
     * chestplates and swords for the watch, and none ever left the stores: the guards wore what
     * they had made themselves out of the iron the smith also wanted.
     * [guard-kit] Now the best of everything the town has made its watch, whatever its metal: the
     * tailor's leather, the smith's iron and diamond, a bow and arrows, a shield, the old piece back
     * into the stores; the same fitting as the shop's round (WatchKit.fit), and free.
     */
    private void guardKitFromTheStores() {
        // [fletcher] A guard out of arrows goes for more at once, not at its next look at the stores.
        if (stationTask() != StationTask.GUARD || tickCount - guardKitTick < (Fletchers.emptyQuiver(this) ? 200 : 1200)) return;
        guardKitTick = tickCount;
        UUID village = ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        WatchKit.fit(server, v, this);                 // [guard-kit]
        if (Fletchers.emptyQuiver(this)) Fletchers.cameForArrows(server, this);   // [fletcher] nothing in the stores: the board says so
    }

    /** What its trade works with, by the end of its name: a miner's pickaxe, a woodcutter's axe. */
    @Nullable
    private String tradeTool() {
        return switch (stationTask()) {
            case MINE -> "_pickaxe";
            case WOOD -> "_axe";
            case FARM -> "_hoe";
            case GUARD -> "_sword";
            default -> null;
        };
    }

    /** The village gave it its tools: it works with the smith's iron (and the village's one
     *  diamond pickaxe) whatever its rank, and a guard wears and wields the smith's iron. */
    @Override
    public boolean mayUseTier(net.minecraft.world.item.ItemStack s) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        String tool = tradeTool();
        if (tool != null && path.endsWith(tool) && (path.startsWith("iron_") || path.startsWith("diamond_"))) return true;
        if (stationTask() == StationTask.GUARD && path.startsWith("iron_")) return true;
        if (stationTask() == StationTask.GUARD && WatchKit.kitPath(path)) return true;   // [guard-kit] the town's kit, at any level
        if (stationTask() == StationTask.CAVE && (WatchKit.kitPath(path) || path.endsWith("_pickaxe"))) return true;   // [caves] the same
        return super.mayUseTier(s);
    }

    private int toolSwapTick = -100000;

    /** How good a tool is: its metal, then its enchantments (Efficiency, Sharpness, Unbreaking...). */
    private static int toolScore(net.minecraft.world.item.ItemStack s) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int metal = path.startsWith("netherite_") ? 5 : path.startsWith("diamond_") ? 4 : path.startsWith("iron_") ? 3
            : path.startsWith("stone_") ? 2 : path.startsWith("golden_") ? 1 : 0;
        int magic = 0;
        net.minecraft.world.item.enchantment.ItemEnchantments e = s.getOrDefault(
            net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        for (var entry : e.entrySet()) magic += entry.getIntValue();
        return metal * 10 + magic;
    }

    /**
     * The best tool of its trade the stores hold, if it beats its own: the smith's iron and the
     * enchanter's work. The enchanter put Efficiency on the smith's picks and axes in the stores,
     * and they went to the shop counter: no miner ever used one. Its old tool goes back.
     */
    private void betterToolFromTheStores() {
        String kind = tradeTool();
        if (kind == null || tickCount - toolSwapTick < 2400) return;
        toolSwapTick = tickCount;
        UUID village = ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> isTool = st -> !st.isEmpty()
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith(kind);
        int mine = -1;
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) if (isTool.test(st)) mine = Math.max(mine, toolScore(st));
        if (isTool.test(getMainHandItem())) mine = Math.max(mine, toolScore(getMainHandItem()));
        BlockPos bestAt = null;
        int bestSlot = -1, best = mine;
        for (BlockPos p : Villages.storeChests(server, village)) {
            if (!(server.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = c.getItem(i);
                if (!isTool.test(st) || !mayUseTier(st)) continue;
                int score = toolScore(st);
                if (score > best) { best = score; bestAt = p; bestSlot = i; }
            }
        }
        if (bestAt == null || !(server.getBlockEntity(bestAt) instanceof net.minecraft.world.Container c)) return;
        net.minecraft.world.item.ItemStack got = c.getItem(bestSlot).split(1);
        c.setChanged();
        // The old one back to the stores (only the one it replaces: a spare stays in the pack).
        net.minecraft.world.item.ItemStack old = net.minecraft.world.item.ItemStack.EMPTY;
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
            if (isTool.test(st)) { old = st.split(1); break; }
        }
        if (!old.isEmpty()) Crafts.store(server, v, old);
        net.minecraft.world.item.ItemStack left = insertItem(got);
        if (!left.isEmpty()) Crafts.store(server, v, left);
        else brain("took a better " + kind.substring(1) + " from the stores" + (got.isEnchanted() ? ", enchanted" : ""));
    }

    private int clothesTick = -100000;

    /** The tailor's boots in the village's colour: anybody without boots takes a pair from the stores. */
    private void clothesFromTheStores() {
        if (isBaby() || tickCount - clothesTick < 2400) return;
        clothesTick = tickCount;
        if (!getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty()) return;
        UUID village = ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        net.minecraft.world.item.ItemStack boots = Crafts.takeOne(server, v, st -> st.is(net.minecraft.world.item.Items.LEATHER_BOOTS));
        if (boots.isEmpty()) return;
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, boots);
        brain("put on a pair of the tailor's boots");
    }

    /**
     * The round: a guard walks its beat of the town's streets (Patrols), by day and by night,
     * instead of only the corners of its own plot. A watch that keeps to its plot meets only what
     * comes to the plot; the streets are where the folk are.
     */
    @Override
    protected boolean streetRound() {
        if (villageCentre == null || isHired() || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        return Patrols.round(this, server);
    }

    /** At the leader's shoulder (Patrols): that is the post while the leader is out and about. */
    @Override
    protected boolean onEscort() {
        return Patrols.escorting(this);
    }

    /** A village's guard about the town's streets is at its work, wherever its plot is. */
    @Override
    protected boolean walksAbroad() {
        // (And a courier out on one of the storehouse's runs: the whole village is its ground.)
        return super.walksAbroad() || Patrols.escorting(this) || Patrols.onTheStreets(this) || Couriers.onARun(this)
            || Sweepers.sweeping(this)                 // (and the street sweeper about the town's streets)
            || Cartographers.surveying(this)          // [cartographer] out walking the town (or the country) with its sheets
            || Divers.busy(this);                      // [diver] on a dive: the water and the shed are its ground
    }

    /** What it just drew out of the Village Storehouse: one request, served by the storekeeper at the
     *  counter if one is on duty, or by itself — in the storehouse's books either way (Storekeeping). */
    @Override
    protected void drewFromStorehouse(java.util.List<net.minecraft.world.item.ItemStack> lots) {
        Storekeeping.handedOut(this, lots);
    }

    private void stoneToolFromTheStores() {
        if (villageCentre == null || ownerId() == null) return;
        net.minecraft.world.item.Item tool;
        String kind;
        switch (stationTask()) {
            case MINE -> { tool = net.minecraft.world.item.Items.STONE_PICKAXE; kind = "_pickaxe"; }
            case WOOD -> { tool = net.minecraft.world.item.Items.STONE_AXE; kind = "_axe"; }
            case GUARD -> { tool = net.minecraft.world.item.Items.STONE_SWORD; kind = "_sword"; }
            case FARM -> { tool = net.minecraft.world.item.Items.STONE_HOE; kind = "_hoe"; }
            // A hunter born in the village grew up with the wooden sword of its childhood kit, and
            // nothing ever made it a better one: the game it could not shoot it hacked at with wood.
            // Stone, like everybody's; the smith's iron blades stay the watch's (betterToolFromTheStores).
            case HUNT -> { tool = net.minecraft.world.item.Items.STONE_SWORD; kind = "_sword"; }
            default -> { return; }
        }
        final String suffix = kind;
        boolean better = countCarried(st -> {
            if (st.isEmpty()) return false;
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            return path.endsWith(suffix) && !path.startsWith("wooden") && !path.startsWith("golden");
        }) > 0;
        if (better) return;
        // Looked at often, made at once: a stone pickaxe is worn through in a hundred and
        // thirty blocks, and a miner whose pick broke stood "needing a pickaxe" for up to
        // two minutes before the next look.
        if (tickCount - stoneToolTick < 400) return;
        stoneToolTick = tickCount;
        int r = buildStoresRadius();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> stone =
            st -> st.is(net.minecraft.world.item.Items.COBBLESTONE) || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
        // A log will do for the handle: woodcutters bank logs, not planks, and a village
        // with seventeen hundred logs and no planks in its chests kept its miners on the
        // wooden pickaxes they were born with.
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> plank =
            st -> st.is(net.minecraft.tags.ItemTags.PLANKS) || st.is(net.minecraft.world.item.Items.STICK)
                || st.is(net.minecraft.tags.ItemTags.LOGS);
        int stoneBefore = countCarried(stone), plankBefore = countCarried(plank);
        if (stoneBefore < 3) drawFrom(villageCentre, stone, 3 - stoneBefore, r);
        if (plankBefore < 1) drawFrom(villageCentre, plank, 1, r);
        if (countCarried(stone) >= 3 && countCarried(plank) >= 1) {
            removeMatching(stone, 3);
            removeMatching(plank, 1);
            // The wooden one goes; a pack is not a museum.
            removeMatching(st -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())
                .getPath().equals("wooden" + suffix), 1);
            insertItem(new net.minecraft.world.item.ItemStack(tool));
            brain("a stone" + suffix.replace('_', ' ') + " made from the stores");
            return;
        }
        boolean stoneInHand = countCarried(stone) >= 3, noWood = countCarried(plank) < 1;
        returnTo(villageCentre, stone, stoneBefore, r);
        returnTo(villageCentre, plank, plankBefore, r);
        // No wood came because there was no room for it, not because the stores have none: the pack
        // is banked first (the station brain does it, AssistantEntity.decideStation), and the next look
        // finds room. Off to the woods for a handle with a full pack, it came back with nothing.
        if (noWood && (isPackFull() || storesHold(villageCentre, r, plank) > 0)) return;
        // The stone is there but not a stick of wood in the stores: it cuts its own handle. (Three
        // miners whose picks had worn through stood at the heart for half a day: the stores held
        // no timber, and nobody thought to fetch any.)
        if (stoneInHand && noWood && peekJob() == null && tickCount - handleTick > 1200
                && countCarried(st -> st.getItem().getDescriptionId().endsWith(suffix)) == 0) {
            handleTick = tickCount;
            enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS, 2));
            brain("off to cut wood for a" + suffix.replace('_', ' ') + " handle: the stores have none");
        }
    }

    private int handleTick = -100000;

    private int rackTick = -100000;
    private int kitRecheckTick = -100000;

    /**
     * The trade's tool off the storehouse's rack of spares (Toolrack). A hand whose tool is gone takes
     * a spare there and then, booked out in the storehouse's books; one whose tool is nearly worn
     * through has the spare before it breaks — brought out by a courier if its plot is far out (and it
     * works on meanwhile), else taken on its way past the counter. Looked at every ten seconds, busy or
     * not, and of an evening too: the hundred's miners whose picks broke stood "needing a pickaxe" while
     * a new one waited on a handle the full pack had no room for, or on the next morning's dry run.
     */
    private void toolFromTheRack() {
        Toolrack.Tool tool = Toolrack.of(stationTask());
        UUID village = ownerId();
        if (tool == null || village == null || villageCentre == null || isBaby()) return;
        if (tickCount - rackTick < 200 && tickCount >= rackTick) return;
        rackTick = tickCount;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        // A good one in hand (or a spare in the pack): nothing wanted.
        if (countCarried(st -> Toolrack.is(tool, st) && !Toolrack.worn(st)) > 0) return;
        boolean worn = countCarried(st -> Toolrack.is(tool, st)) > 0;
        if (worn && onShift() && Couriers.sendOut(this, tool.word, 1)) {
            brain("asked the storehouse to send a " + tool.word + " out: mine is nearly worn through");
            return;
        }
        net.minecraft.world.item.ItemStack got = Purchases.tool(server, v, this, tool);   // [econ-prices] bought once there is a shop
        if (got.isEmpty()) return;
        brain((worn ? "a spare " : "a new ") + got.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
            + " off the storehouse's rack");
        recheckKit();
    }

    /** Tests: the look at the rack, now. True if it came away with a tool. */
    public boolean toolFromTheRackForTests() {
        Toolrack.Tool tool = Toolrack.of(stationTask());
        int before = tool == null ? 0 : countCarried(st -> Toolrack.is(tool, st));
        rackTick = -100000;
        toolFromTheRack();
        return tool != null && countCarried(st -> Toolrack.is(tool, st)) > before;
    }

    /** What a hand at its work eats: food, but not what would poison it (WithdrawGoal's "ration"). */
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> RATION =
        com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor("ration");
    /** Fewer rations than this in the pack and more are sent for. */
    static final int RATIONS_LOW = 4;

    private int rationAheadTick = -100000;

    /** Does this hand's trade eat rations at its work (JobSpec)? Not the trades whose work is the food. */
    private boolean eatsRations() {
        StationTask t = stationTask();
        return com.jrpetty.mcassistant.AssistantConfig.upkeepEnabled() && t != StationTask.NONE
            && t != StationTask.FARM && t != StationTask.FISH && t != StationTask.HUNT;
    }

    /** How many rations to carry: a few days' for a hand whose plot is a long walk from the stores, a
     *  couple of days' for one near them, and no more than a day's while the village is hungry. */
    private int rationsWanted() {
        UUID village = ownerId();
        if (village != null && Market.hungry(village)) return RATIONS_LOW + 2;
        if (Droughts.rationing(village)) return RATIONS_LOW + 2;   // [disasters] short rations in a drought
        return tripToStores() >= 48 ? 12 : 8;
    }

    /**
     * Rations before they run out. A hand at the stores takes a few days' at the counter; one far out
     * on its plot has the storehouse send them out with a courier while it still has a meal or two
     * left, and works on. The only time a hand went for rations used to be when it had none — a long
     * walk from the stores, its work stopped by the checklist till it had been there and back — and
     * the evening's restock reached only those who lived near the heart: the hundred had a tenth of
     * its workers "short of food" with five thousand in the stores.
     */
    private void rationsAhead() {
        UUID village = ownerId();
        if (village == null || villageCentre == null || isBaby() || !eatsRations()) return;
        if (tickCount - rationAheadTick < 600 && tickCount >= rationAheadTick) return;
        rationAheadTick = tickCount;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        int have = countCarried(RATION);
        if (have >= RATIONS_LOW) return;
        int want = rationsWanted();
        Villages.Village v = Villages.get(village);
        if (v != null && Toolrack.atTheStores(server, v, this)) {
            int got = Purchases.get(server, this, RATION, want - have, Purchases.Need.FOOD);   // [econ-prices] bought once there is a shop
            if (got > 0) {
                brain("took " + got + " rations at the stores");
                recheckKit();
            }
            return;
        }
        if (onShift() && Couriers.sendOut(this, "ration", want - have)) brain("asked the storehouse to send rations out");
    }

    /**
     * Up to {@code n} of what matches, out of the village's own stores (the storehouse and the chests
     * round the heart: never a household's chest, a guest house's or a worker's production chest) and
     * into the pack, as a hand at the counter would be handed them; booked in the storehouse's books.
     * Returns how many.
     */
    private int drawFromTheStores(net.minecraft.server.level.ServerLevel server,
                                  java.util.function.Predicate<net.minecraft.world.item.ItemStack> what, int n) {
        UUID village = ownerId();
        if (village == null || n <= 0) return 0;
        int moved = 0;
        boolean full = false;
        java.util.List<net.minecraft.world.item.ItemStack> fromStore = new java.util.ArrayList<>();
        for (BlockPos p : Villages.storeChests(server, village)) {
            if (moved >= n || full) break;
            if (!(server.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            boolean store = c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity;
            for (int i = 0; i < c.getContainerSize() && moved < n; i++) {
                net.minecraft.world.item.ItemStack st = c.getItem(i);
                if (st.isEmpty() || !what.test(st)) continue;
                int take = Math.min(st.getCount(), n - moved);
                net.minecraft.world.item.ItemStack left = insertGiven(st.copyWithCount(take));
                int taken = take - left.getCount();
                if (taken <= 0) { full = true; break; }               // the pack is full
                if (store) fromStore.add(st.copyWithCount(taken));
                st.shrink(taken);
                if (st.isEmpty()) c.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                moved += taken;
            }
            c.setChanged();
        }
        if (!fromStore.isEmpty()) drewFromStorehouse(fromStore);
        return moved;
    }

    /** Tests: the look at its rations, now. */
    public void rationsAheadForTests() {
        rationAheadTick = -100000;
        rationsAhead();
    }

    private String patchNameFor(StationTask trade) {
        String base = switch (trade) {
            case FARM -> "Home Fields";
            case WOOD -> "East Wood";
            case MINE -> "The Pit";
            case SMELT -> "The Forge";
            case HUNT -> "Hunting Grounds";
            case BANK -> "The Bank";
            case CAVE -> "The Caves";             // [caves]
            case FERRY -> "The Ferry";            // [transport]
            case FLETCHER -> "The Fletcher's";    // [fletcher]
            case GOLEMS -> "The Golem Yard";      // [golems]
            case FIREWORKS -> "The Powder Hut";   // [fireworks]
            case CARTOGRAPHER -> "The Map Room";  // [cartographer]
            case EMERALD -> "The Trading Post";   // [emerald]
            case DIVER -> "The Kelp Beds";        // [diver]
            default -> "The Commons";
        };
        // Two farms in one village should not share a name.
        int same = 0;
        for (AssistantEntity mate : Villages.folkOf(ownerId())) {
            if (mate != this && mate.stationTask() == trade && mate.workZone() != null) same++;
        }
        return same == 0 ? base : base + " " + (same + 1);
    }

    // ------------------------------ finding ground ---------------------------

    /**
     * A square of the village's farmland for a new field (Villages.fieldSquares): the nearest one
     * still unworked whose ground will take a plough. The squares are laid out before the first
     * furrow, a full-grown field's width apart, so the fields come up side by side, never over one
     * another and never where the town will build.
     */
    @Nullable
    private BlockPos farmlandPlot(BlockPos heart, @Nullable Reach walk) {
        UUID town = ownerId();
        if (town == null) return null;
        int side = Villages.fieldsSide(town);
        if (side < 0) side = chooseFieldsSide(town, heart);
        if (side < 0) return null;
        neighbours = Villages.folkOf(town);
        try {
            for (int[] f : Villages.fieldSquares(side)) {
                // A square the farmer cannot walk to from the town is no field of the town's.
                if (walk != null && !walk.reaches(heart.getX() + f[0], heart.getZ() + f[1], FIELD_MOST / 2)) continue;
                BlockPos p = surfaceAt(heart.getX() + f[0], heart.getZ() + f[1]);
                // Not up a mountain or down a ravine from the town: a farmer walks there and back
                // every day, and a field it cannot get to is no field (the mountains' hundred days).
                if (p == null || Math.abs(p.getY() - heart.getY()) > FIELD_CLIMB || taken(p, FIELD_MOST)) continue;
                if (Bonds.overBorder(town, heart, p, FIELD_MOST)) continue;           // that side of the border is theirs
                if (Villages.builtOver(town, f[0], f[1], FIELD_MOST, FIELD_MOST) || buildingNear(town, p, FIELD_MOST + 4)) continue;
                if (soilField(p) || farmable(p)) return p;
            }
            return null;
        } finally {
            neighbours = null;
        }
    }

    /** Does any of the village's buildings stand within this many blocks of the spot? */
    private static boolean buildingNear(UUID town, BlockPos p, int within) {
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(town)) {
            BlockPos a = b.anchor();
            if (Math.max(Math.abs(a.getX() - p.getX()), Math.abs(a.getZ() - p.getZ())) <= within) return true;
        }
        return false;
    }

    /**
     * The village marks out its farmland: the side of the town whose ground suits fields best —
     * open soil, flat with the town, water about it — and the fields go out that way from then on,
     * while the town grows the other three (Villages.lotKeptOff). A village whose first field was
     * sown before this keeps the side it is on. Waits (-1) while the near ground is still loading.
     */
    private int chooseFieldsSide(UUID town, BlockPos heart) {
        BlockPos origin = fieldsOrigin(town);
        if (origin != null) {
            int dx = origin.getX() - heart.getX(), dz = origin.getZ() - heart.getZ();
            int side = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? com.jrpetty.mcassistant.village.TownPlan.EAST : com.jrpetty.mcassistant.village.TownPlan.WEST)
                : (dz >= 0 ? com.jrpetty.mcassistant.village.TownPlan.SOUTH : com.jrpetty.mcassistant.village.TownPlan.NORTH);
            Villages.setFieldsSide(town, side);
            return side;
        }
        int best = -1, bestScore = 0;
        Reach walk = level() instanceof net.minecraft.server.level.ServerLevel sl ? Reach.of(sl, town, heart) : null;
        for (int side = 0; side < 4; side++) {
            java.util.List<int[]> squares = Villages.fieldSquares(side);
            int score = 0;
            for (int i = 0; i < Math.min(6, squares.size()); i++) {
                int[] f = squares.get(i);
                BlockPos at = heart.offset(f[0], 0, f[1]);
                if (!boxReady(at, 6)) {
                    if (i < 2) return -1;                       // the first row has not come in yet
                    continue;
                }
                // Over a ridge or across a pond from the town: no use, however good the soil.
                if (walk != null && !walk.reaches(at, FIELD_MOST / 2)) continue;
                BlockPos p = surfaceAt(at.getX(), at.getZ());
                if (p == null || Math.abs(p.getY() - heart.getY()) > FIELD_CLIMB) continue;
                if (Villages.builtOver(town, f[0], f[1], FIELD_MOST, FIELD_MOST)) { score -= 4; continue; }
                // [emerald] Toward a village of the game's villagers the ground is theirs: the fields go another way.
                if (VanillaVillages.within(level(), at.getX(), at.getZ(), FIELD_MOST + VanillaVillages.MARGIN)) { score -= 4; continue; }
                if (!soilField(p) && !farmable(p)) continue;
                // The nearest row counts twice: it is the one farmed first, and walked to every day.
                int weight = i < 2 ? 2 : 1;
                int good = 2;                                                // soil that will take a plough
                if (waterOn(at, FIELD_MOST + 2)) good += 3;                  // and water on it, or by it
                if (Math.abs(p.getY() - heart.getY()) <= 4) good += 2;       // level with the town: an easy walk
                score += weight * good;
            }
            if (score > bestScore) { bestScore = score; best = side; }
        }
        if (best < 0) return -1;                                // no soil on any side: fields go where they can
        Villages.setFieldsSide(town, best);
        String way = switch (best) {
            case com.jrpetty.mcassistant.village.TownPlan.EAST -> "east";
            case com.jrpetty.mcassistant.village.TownPlan.WEST -> "west";
            case com.jrpetty.mcassistant.village.TownPlan.SOUTH -> "south";
            default -> "north";
        };
        brain("marked out the village's farmland to the " + way + " of the town, where the ground is best");
        com.jrpetty.mcassistant.village.Ledger.note(town, "fields.way", way);
        return best;
    }

    /** Is there open water at the surface anywhere in this square (every other column looked at)? */
    private boolean waterOn(BlockPos centre, int r) {
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                if (!chunkReady(x, z)) continue;
                BlockPos top = new BlockPos(x, level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                if (level().getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** Tests: have this folk's village mark out its farmland now. */
    public int chooseFieldsSideForTests() {
        BlockPos heart = villageCentre != null ? villageCentre : blockPosition();
        return ownerId() == null ? -1 : chooseFieldsSide(ownerId(), heart);
    }

    /** Where the village's fields begin (its first field), or null before it has one. */
    @Nullable
    static BlockPos fieldsOrigin(UUID town) {
        String note = com.jrpetty.mcassistant.village.Ledger.note(town, "fields.origin");
        if (note == null || note.isEmpty()) return null;
        String[] p = note.split(",");
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ground that suits the trade, searched outward from the village so a
     * settlement stays a settlement rather than scattering. Coarse on purpose:
     * this runs at most once every five seconds and only while a folk is
     * looking for work.
     */
    @Nullable
    private BlockPos findSite(StationTask trade, int radius) {
        BlockPos heart = villageCentre != null ? villageCentre : blockPosition();
        // Everybody searching outward from the same point finds the same
        // ground. Each folk starts its look in its own direction instead, so a
        // village fans out around its centre rather than piling into one
        // corner of it — which is precisely how two miners ended up trying to
        // dig the same hill.
        double angle = ((getId() + searchBearing) % 8) * (Math.PI / 4.0);
        // ...and it starts that look FURTHER OUT the bigger the village is.
        // Ten plots fit around one hillside; twenty do not, and the ground is
        // claimed whole and never shared — so past ten folk every search that
        // starts at the same place finds nothing but its neighbours' fields,
        // gives up, and the newcomer stands in the square for ever with no
        // trade. Widening the SCAN instead would have squared its cost, and
        // that scan already reads eight hundred blocks per candidate; moving
        // where it starts costs nothing at all.
        int reach = com.jrpetty.mcassistant.village.VillageMath
            .searchReach(Villages.headcount(ownerId()));
        // Fields, woods and mines are out beyond the town (village/TownPlan): the
        // ground inside it is for streets and houses.
        boolean outdoor = trade == StationTask.FARM || trade == StationTask.WOOD || trade == StationTask.MINE
            || trade == StationTask.RANCH || trade == StationTask.FISH || trade == StationTask.BEEKEEP || trade == StationTask.HUNT;
        // The land is planned. The fields go on the village's farmland — one side of the town, laid
        // out in squares side by side (farmlandPlot) — and the town grows round the other three.
        // Woods, mines, pens and hives go outside the town as it stands and off the farmland (the
        // fisher may cast from its banks: water is not a field).
        UUID town = ownerId();
        int keep = trade == StationTask.FARM ? FIELD_MOST : radius;
        if (outdoor && town != null) reach = Math.max(reach, Villages.townReach(town) + keep + 8);
        java.util.function.Predicate<BlockPos> clear = p -> !outdoor || town == null
            || Villages.outsideTown(town, heart, p, keep)
               && (trade == StationTask.FARM || trade == StationTask.FISH || !Villages.onFarmland(town, heart, p, radius))
               && !Bonds.overBorder(town, heart, p, keep)                  // never over the line toward a neighbour
               && !VanillaVillages.within(level(), p.getX(), p.getZ(), keep + VanillaVillages.MARGIN);   // [emerald] nor the villagers' ground
        BlockPos from = heart.offset(
            (int) Math.round(Math.cos(angle) * reach), 0,
            (int) Math.round(Math.sin(angle) * reach));
        return switch (trade) {
            // One scan distance for every trade. The ring a village keeps
            // awake and the range its stores are read over are both derived
            // from how far a plot can end up, and neither can be reasoned
            // about while the woodcutter quietly reaches a third further than
            // everybody else.
            // A field goes on the village's farmland, the nearest square of it free; failing that,
            // by the nearest water outside the town (farmers used to look on their own bearing and
            // walked seventy or eighty blocks to a pond while there was a river by the town), and
            // failing that the nearest good soil — a farmer digs its own water holes (FarmGoal).
            case FARM -> {
                // Only ground the farmer can walk to from the town (Reach): on a mountain map the
                // farmland went out over a ridge and across a pond, and nobody ever got there.
                Reach walk = town != null && level() instanceof net.minecraft.server.level.ServerLevel sl
                    ? Reach.of(sl, town, heart) : null;
                java.util.function.Predicate<BlockPos> near = p -> clear.test(p)
                    && (walk == null || walk.reaches(p, FIELD_MOST / 2));
                // The village's farmland first: its fields side by side, on the side of the town it chose.
                BlockPos plot = farmlandPlot(heart, walk);
                if (plot != null) yield plot;
                // Nowhere on the farmland (the ground not in yet, or no soil on it): the nearest water
                // outside the town, or the nearest good soil.
                int out = outdoor && town != null ? Villages.townReach(town) + keep + 2 : 8;
                BlockPos wet = nearestWaterField(heart, out, keep, near);
                if (wet == null) wet = scan(from, SCAN, 6, keep, p -> near.test(p) && farmable(p));
                if (wet == null) wet = scan(from, SCAN, 6, keep, p -> near.test(p) && soilField(p));
                // Nothing the town can walk to (its ground still coming in): wherever there is soil.
                if (wet == null && walk != null) {
                    plot = farmlandPlot(heart, null);
                    if (plot != null) yield plot;
                    wet = nearestWaterField(heart, out, keep, clear);
                    if (wet == null) wet = scan(from, SCAN, 6, keep, p -> clear.test(p) && farmable(p));
                    if (wet == null) wet = scan(from, SCAN, 6, keep, p -> clear.test(p) && soilField(p));
                }
                // A field sown before the village chose its farmland marks the side it will choose.
                if (wet != null && town != null && Villages.fieldsSide(town) < 0 && fieldsOrigin(town) == null) {
                    com.jrpetty.mcassistant.village.Ledger.note(town, "fields.origin", wet.getX() + "," + wet.getY() + "," + wet.getZ());
                }
                yield wet;
            }
            case WOOD -> scan(from, SCAN, 6, radius, p -> clear.test(p) && woodland(p));
            // A village's miners work the town's mine, side by side, out beyond the town (TownMine); a
            // look round for a plot of its own only while the town has no mine to give.
            case MINE -> {
                BlockPos face = town != null && level() instanceof net.minecraft.server.level.ServerLevel minesLevel
                    ? TownMine.faceFor(minesLevel, this, heart, p -> clear.test(p) && diggable(p)) : null;
                yield face != null ? face : scan(from, SCAN, 6, radius, p -> clear.test(p) && diggable(p));
            }
            // A pen goes where the animals already are and a jetty goes on
            // water — both were staking the village square, where a rancher
            // found nothing to breed and a fisher nothing to cast into, and
            // both trades were a silent no-op for the life of the settlement.
            // A pasture with animals on it; failing that, open meadow to bring them home to (Drover).
            case RANCH -> {
                BlockPos grazed = scan(from, SCAN, 6, radius, p -> clear.test(p) && pasture(p));
                yield grazed != null ? grazed : scan(from, SCAN, 6, radius, p -> clear.test(p) && meadow(p));
            }
            // [sf] Open water the town can walk to (Reach), as the farmer's field is: a mountain town's fisher
            // could be staked on a lake under ice, or on water down a cliff, and land nothing for good.
            case FISH -> {
                Reach walk = town != null && level() instanceof net.minecraft.server.level.ServerLevel sl
                    ? Reach.of(sl, town, heart) : null;
                yield scan(from, SCAN, 6, radius, p -> clear.test(p) && (walk == null || walk.reaches(p, radius + 2)) && fishable(p));
            }
            // The hives go out on open grass, where there is room for flowers.
            case BEEKEEP -> scan(from, SCAN, 6, radius, p -> clear.test(p) && meadow(p));
            // Hunting grounds: wild country with game on it, out past the fields and pastures; failing
            // that, open grass or woodland, where game wanders through.
            case HUNT -> {
                BlockPos game = scan(from, SCAN, 8, radius, p -> clear.test(p) && gameAround(p));
                yield game != null ? game : scan(from, SCAN, 8, radius, p -> clear.test(p) && (meadow(p) || woodland(p)));
            }
            // [diver] The bank of the town's diving water (Divers); none, and no ground for the trade.
            case DIVER -> {
                Divers.Waterside w = Divers.water(town);
                yield w == null ? null : w.bank();
            }
            // The indoor trades belong in the village rather than out in a
            // field — but not all three in the same square. Each takes its own
            // corner of the middle, on its own bearing, so the forge, the
            // storeroom and the carrier's post are neighbours instead of one
            // pile.
            default -> {
                BlockPos near = surfaceAt(
                    heart.getX() + (int) Math.round(Math.cos(angle) * 6),
                    heart.getZ() + (int) Math.round(Math.sin(angle) * 6));
                yield near != null && !taken(near, radius) ? near
                    : surfaceAt(heart.getX(), heart.getZ());
            }
        };
    }

    /** A spiral-ish coarse scan on the surface, nearest ring first. */
    @Nullable
    private BlockPos scan(BlockPos from, int radius, int stride, int plotRadius,
                          java.util.function.Predicate<BlockPos> good) {
        // Fetched ONCE, not once per candidate. The overlap test runs for
        // every square of every ring, and in a town of a hundred that was
        // building a hundred-element list a few hundred times per search.
        neighbours = Villages.folkOf(ownerId());
        try {
            return scanRings(from, radius, stride, plotRadius, good);
        } finally {
            neighbours = null;
        }
    }

    @Nullable private java.util.List<AssistantEntity> neighbours;

    @Nullable
    private BlockPos scanRings(BlockPos from, int radius, int stride, int plotRadius,
                               java.util.function.Predicate<BlockPos> good) {
        for (int r = stride; r <= radius; r += stride) {
            for (int dx = -r; dx <= r; dx += stride) {
                for (int dz = -r; dz <= r; dz += stride) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring only
                    BlockPos p = surfaceAt(from.getX() + dx, from.getZ() + dz);
                    if (p == null || taken(p, plotRadius)) continue;
                    if (good.test(p)) return p;
                }
            }
        }
        return null;
    }

    @Nullable
    BlockPos surfaceAt(int x, int z) {
        if (!chunkReady(x, z)) return null;
        int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level().getMinBuildHeight() + 1) return null;
        return new BlockPos(x, y, z);
    }

    /** Is the chunk holding this column fully loaded RIGHT NOW? Reading a block
     *  in one that is not makes the server generate it on the spot, on the tick
     *  thread, with the whole game held up: on a real world a survey of where to
     *  farm froze the server for four seconds at a time, in every new village. */
    private boolean chunkReady(int x, int z) {
        return level().getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    /** Are all four corners of the box round this spot loaded? A survey reads the
     *  whole box, so it may only run where every chunk it will touch is here. */
    private boolean boxReady(BlockPos pos, int r) {
        return chunkReady(pos.getX() - r, pos.getZ() - r) && chunkReady(pos.getX() + r, pos.getZ() - r)
            && chunkReady(pos.getX() - r, pos.getZ() + r) && chunkReady(pos.getX() + r, pos.getZ() + r);
    }

    /**
     * Would a plot HERE tread on anybody else's? Testing the centre alone was
     * not enough — two plots can overlap heavily without either centre being
     * inside the other, which is how a lumberjack ended up felling the trees
     * around a farmer's field. The whole prospective footprint is tested, with
     * a couple of blocks of elbow room on top.
     */
    private boolean taken(BlockPos pos, int plotRadius) {
        WorkZone mine = WorkZone.around(pos, plotRadius + 2, WorkZone.DEFAULT_DEPTH);
        // Ground we are deliberately leaving counts as somebody else's.
        if (avoidHere != null && mine.overlaps(avoidHere)) return true;
        // [emerald] And a village of the game's villagers, and its margin, is theirs: no field, wood, pen or mine on it.
        if (VanillaVillages.within(level(), pos.getX(), pos.getZ(), plotRadius + VanillaVillages.MARGIN)) return true;
        java.util.List<AssistantEntity> crew =
            neighbours != null ? neighbours : Villages.folkOf(ownerId());
        for (AssistantEntity mate : crew) {
            if (mate == this) continue;
            WorkZone theirs = mate.workZone();
            if (theirs != null && mine.overlaps(theirs)) return true;
        }
        return false;
    }

    /**
     * The field nearest the village with water beside it: rings out from the heart (from the
     * edge of the town, where fields begin), a block or three at a time, looking at the top
     * of each column for water, and putting the field on the bank beside the first water
     * whose bank has room for a field nobody else is working.
     */
    @Nullable
    private BlockPos nearestWaterField(BlockPos heart, int from, int plotRadius,
                                       java.util.function.Predicate<BlockPos> clear) {
        neighbours = Villages.folkOf(ownerId());
        try {
            for (int r = Math.max(4, from); r <= from + SCAN; r += 3) {
                for (int dx = -r; dx <= r; dx += 3) {
                    for (int dz = -r; dz <= r; dz += 3) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) < r - 2) continue;    // the ring only
                        int x = heart.getX() + dx, z = heart.getZ() + dz;
                        if (!chunkReady(x, z)) continue;
                        BlockPos top = new BlockPos(x, level().getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                        if (!level().getFluidState(top).is(net.minecraft.tags.FluidTags.WATER)) continue;
                        // The bank beside it: a few blocks off the water, on soil.
                        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                            for (int k = 3; k <= 5; k++) {
                                BlockPos site = surfaceAt(x + d.getStepX() * k, z + d.getStepZ() * k);
                                if (site == null || !level().getBlockState(site.below()).is(BlockTags.DIRT)) continue;
                                if (Math.abs(site.getY() - top.getY()) > 3 || taken(site, plotRadius)) continue;
                                if (clear.test(site) && farmable(site)) return site;
                            }
                        }
                    }
                }
            }
            return null;
        } finally {
            neighbours = null;
        }
    }

    /** Where this folk would put a field now (the tests). */
    @Nullable
    public BlockPos fieldSiteForTests() {
        return findSite(StationTask.FARM, radiusFor(StationTask.FARM));
    }

    /** Good soil and room for a field, though dry: the last resort (Waterfront cuts its channel). */
    private boolean soilField(BlockPos pos) {
        if (!boxReady(pos, 6)) return false;
        int soil = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -1, -5), pos.offset(5, 0, 5))) {
            // Open ground: a pond's bed is dirt with water over it, and no field.
            BlockState over = level().getBlockState(p.above());
            if (level().getBlockState(p).is(BlockTags.DIRT) && over.canBeReplaced() && over.getFluidState().isEmpty()
                    && ++soil >= 40) return true;
        }
        return false;
    }

    /** Water to hand and soft ground around it: a field, in other words. */
    private boolean farmable(BlockPos pos) {
        if (!boxReady(pos, 6)) return false;
        boolean water = false;
        int soil = 0;
        // Reads every block in an 13x5x13 box, and did so to the last block
        // even once the answer was settled. A hundred folk each testing a few
        // hundred candidates a minute made that the most expensive thing in
        // the mod by a wide margin. Stop as soon as it is known.
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -2, -6), pos.offset(6, 2, 6))) {
            BlockState st = level().getBlockState(p);
            if (st.is(Blocks.WATER)) water = true;
            else if (st.is(BlockTags.DIRT)) soil++;
            if (water && soil >= 20) return true;
        }
        return false;
    }

    /** Livestock on the hoof: a herd worth putting a fence round. */
    /** Three or more head of game it could fairly take, round about. */
    private boolean gameAround(BlockPos pos) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            new net.minecraft.world.phys.AABB(pos).inflate(16, 6, 16), this::fairGame).size() >= 3;
    }

    private boolean pasture(BlockPos pos) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            new net.minecraft.world.phys.AABB(
                pos.getX() - 8, pos.getY() - 5, pos.getZ() - 8,
                pos.getX() + 8, pos.getY() + 5, pos.getZ() + 8),
            a -> a.isAlive() && !a.isBaby()).size() >= 2;
    }

    /** Open grass, room for hives and the flowers round them. */
    private boolean meadow(BlockPos pos) {
        if (!boxReady(pos, 6)) return false;
        int grass = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-5, -2, -5), pos.offset(5, 1, 5))) {
            if (level().getBlockState(p).is(Blocks.GRASS_BLOCK) && level().getBlockState(p.above()).canBeReplaced()
                    && ++grass >= 30) return true;
        }
        return false;
    }

    /** Open water, and enough of it to be worth a rod. */
    private boolean fishable(BlockPos pos) {
        if (!boxReady(pos, 6)) return false;
        int water = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -3, -6), pos.offset(6, 1, 6))) {
            // [sf] The top of the water, open to the sky a line is cast from (FishGoal casts nowhere else): water
            // under ice, or under a ledge, counted here, and a fisher sent to it had nowhere to cast.
            if (!level().getBlockState(p).is(Blocks.WATER)) continue;
            BlockPos up = p.above();
            if (!level().getBlockState(up).canBeReplaced() || !level().getFluidState(up).isEmpty()) continue;
            if (++water >= 9) return true;
        }
        return false;
    }

    /** Standing timber, and enough of it to be worth walking to. */
    private boolean woodland(BlockPos pos) {
        if (!boxReady(pos, 6)) return false;
        int logs = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-6, -2, -6), pos.offset(6, 6, 6))) {
            if (level().getBlockState(p).is(BlockTags.LOGS)) logs++;
            if (logs >= 12) return true;
        }
        return false;
    }

    /** Stone under the boots, and not the middle of somebody's field. */
    private boolean diggable(BlockPos pos) {
        if (!boxReady(pos, 3)) return false;
        int stone = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -6, -3), pos.offset(3, -1, 3))) {
            if (level().getBlockState(p).is(BlockTags.BASE_STONE_OVERWORLD)) stone++;
            if (stone >= 24) return true;
        }
        return false;
    }

    // ------------------------------ the courier's round ---------------------

    private int routeTick = -100000;

    /**
     * A village's courier does not choose its own round. It is the storehouse's: it takes its runs
     * from the storehouse's run list (Couriers) — the production chests, fullest and longest waiting
     * first; ore out to the smelter; kit out to a worker far out on its plot; a worker's load; the old
     * chests — and waits at the storehouse's door between them. (Its round used to be its own: the
     * fullest chest near its post, re-chosen every minute, two chests wand-fashion.) A route of two
     * chests kept from before (a saved world) is let go, so its station work comes to the storehouse.
     */
    private void mindTheRoute() {
        if (stationTask() != StationTask.HAUL || !Couriers.employed(this)) return;
        clearHaulRoute();
    }

    private int buildingCheckTick = -100000;

    /**
     * The smelter works in the smeltery and the storekeeper in the storehouse, once they
     * stand. Their posts were staked a few blocks from the heart before there were any
     * buildings, and stayed there: the smeltery's three furnaces were never lit, and the
     * storekeeper sorted the chests round its post and never the storehouse's.
     */
    private void workInTheBuilding() {
        StationTask trade = stationTask();
        // The couriers are the storehouse's staff, and work out of it too (Couriers): their post is
        // the storehouse, where they wait between runs and report back after each.
        String building = trade == StationTask.HAUL ? "storage"
            : trade == StationTask.SHOP ? Store.buildingForShop(ownerId()) : buildingFor(trade);   // [econ-store] the store once it stands
        if (building == null) return;
        if (tickCount - buildingCheckTick < 1200) return;
        buildingCheckTick = tickCount;
        UUID village = ownerId();
        if (village == null) return;
        BlockPos at = Villages.builtAt(village, building);
        if (at == null && trade == StationTask.HAUL && level() instanceof net.minecraft.server.level.ServerLevel s) {
            at = Storehouses.doorFor(s, village);                    // a storehouse with no shed round it
        }
        WorkZone zone = workZone();
        if (at == null || (zone != null && zone.center().distSqr(at) <= 4)) return;
        assignPlot(WorkZone.around(at, 5, WorkZone.DEFAULT_DEPTH), patchNameFor(trade));
        setStation(at, trade);
        brain("moved into the " + (building.equals("storage") ? "storehouse" : building));
    }

    /** The building a trade works in, once the village has it: the smelter in the smeltery,
     *  the blacksmith in the smithy, the cook in the café... */
    @Nullable
    static String buildingFor(StationTask trade) {
        return switch (trade) {
            case SMELT -> "smeltery";
            case STORE -> "storage";
            case SMITH -> "smithy";
            case TAILOR -> "workshop";
            case BREW -> "brewery";
            case ENCHANT -> "library";
            case COOK -> "cafe";
            case SHOP -> "shop";
            case BANK -> "bank";                  // the banker (Bank)
            case FLETCHER -> Fletchers.STRUCTURE; // [fletcher] the fletcher's hut
            case GOLEMS -> Golems.STRUCTURE;      // [golems] the golem yard
            case FIREWORKS -> FireworksMaker.STRUCTURE;   // [fireworks] the powder hut
            case CARTOGRAPHER -> Cartographers.STRUCTURE;   // [cartographer] the map room
            case DIVER -> Divers.SHED;            // [diver] the diver's shed on the bank
            default -> null;
        };
    }

    // ------------------------------ the watch ------------------------------------

    /** Where this guard stands on the wall while the bell rings (Raids), or null. */
    @Nullable private BlockPos post;
    /** On the watch, its pathfinding leaves doors (the gates) shut. */
    private boolean watchKeepsDoors;

    @Nullable public BlockPos post() { return post; }

    public void holdPost(@Nullable BlockPos at) { post = at == null ? null : at.immutable(); }

    @Override
    public boolean holdingAPost() {
        return post != null && distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) < 2.5;
    }

    @Override
    public boolean onWatch() {
        // [watch-clears] Out among the houses after a monster, it is not holding the wall: it opens the gates, closes in
        // and breaks off a fight it is losing, as on any other day (WatchClears).
        return stationTask() == StationTask.GUARD && Raids.underAlarm(ownerId()) && !WatchClears.hunting(this);
    }

    /** [watch-clears] Sent after a creeper, any guard draws the town's bow, whatever its years (WatchClears.mayDraw). */
    @Override
    public boolean mayShoot(net.minecraft.world.item.ItemStack s) {
        return super.mayShoot(s) || (s.is(net.minecraft.world.item.Items.BOW) || s.is(net.minecraft.world.item.Items.CROSSBOW))
            && stationTask() == StationTask.GUARD && WatchClears.mayDraw(this);
    }

    /** [watch-clears] A shout for help is the watch's to answer: a farmer, a cook or a child that ran at the monster
     *  with its fists only gave it a second to kill (WatchClears; the shout still brings a guard, Patrols.cryForHelp). */
    @Override
    public boolean respondToDistress(AssistantEntity ally, net.minecraft.world.entity.monster.Monster attacker) {
        if (stationTask() != StationTask.GUARD && !hiredToFight()) return false;
        return super.respondToDistress(ally, attacker);
    }

    @Override
    protected boolean watchDuty() {
        return Raids.guardDuty(this);
    }

    /** No bed of its own when the bell rings: into the nearest of the village's buildings. [watch-clears] And with
     *  its bed across the town, the same, children too: not a walk through the streets with the band in them. */
    @Override
    protected boolean bedtime() {
        if (Raids.underAlarm(ownerId()) && (bedPos() == null && !isBaby() || WatchClears.bedFar(this))) return Raids.shelter(this);
        // The day of rest, by day: the service, the games, walking out — not bed at noon.
        if (!Raids.underAlarm(ownerId()) && RestDay.now(ownerId(), level().getDayTime()) != null) {
            if (!RestDay.spend(this)) socialise();
            return true;
        }
        return super.bedtime();
    }

    // ------------------------------ moving away ----------------------------------

    /**
     * Leave this village for another (Contentment): off the old roll and onto the new, its
     * plot and its bed given up, and set down at the new village's heart to start again.
     */
    public void leaveFor(net.minecraft.server.level.ServerLevel level, Villages.Village to) {
        UUID from = ownerId();
        long day = level.getDayTime() / 24000L;
        clearQueue();
        stopFollowing();
        setWorkZone(null);
        forgetBed();
        if (from != null) {
            Villages.recordDeath(from);
            Homes.left(from, getUUID());
            Bank.left(from, this, false);                // its savings at the bank go with it
        }
        Annals.moved(from, to.id());
        joinVillage(to.id(), to.centre());
        Villages.recordBirth(to.id());
        BlockPos at = Contentment.arrival(level, to, getRandom());
        teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        persona.remember(day, "I left " + (from == null ? "home" : Villages.name(from)) + " for " + Villages.name(to.id()), 9);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "A fresh start.", "Hello! I've come to live here.", "I hope it's better here."));
    }

    /**
     * Off to another town for good (JobSeekers): its production chest stays behind on the old plot,
     * the old town's for its couriers to clear (Retiring), and is never looked for from the new one.
     */
    public void leftItsPlot() {
        productionChest = null;
        oldProductionChest = null;
        oldChestTries = 0;
    }

    /** Leave for good, with nowhere to go (Contentment): out of the world. */
    public void walkOut() {
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I can't stay here any longer. Goodbye.", "I'm off to find a better life."));
        discard();
    }

    private static final net.minecraft.resources.ResourceLocation OLD_GAIT =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "old_age");

    /** The old walk as briskly as anybody now: the slower gait they once had is taken off. */
    private void refreshOldAgeGait() {
        var speed = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(OLD_GAIT);
    }

    // [econ-store] Its job at the shop on its nameplate ("[Stock keeper]"), looked at every ten seconds (ShopRoles).
    private String roleBadge = "";
    private int roleBadgeTick = -100000;

    @Override
    protected String roleBadge() {
        if (level().isClientSide) return "";
        if (tickCount - roleBadgeTick >= 200 || tickCount < roleBadgeTick) {
            roleBadgeTick = tickCount;
            String b = stationTask() == StationTask.SHOP ? ShopRoles.badge(this) : "";
            roleBadge = b.isEmpty() ? "" : "[" + b + "]";
        }
        return roleBadge;
    }

    /** The crafts' work (Crafts, Cafe): a piece at a time, out of the stores and back. */
    @Override
    protected boolean craftWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server
            && (StoreStaff.work(this, server) || Crafts.work(this, server));   // [econ-store] the counter, the stock book
    }

    /**
     * Never the last of a kind: a cow, pig, sheep, chicken or rabbit with fewer than three of its
     * own kind grown within twenty-four blocks is left to breed. The hunters take the spare ones,
     * and there is game again next year.
     */
    @Override
    public boolean spareForBreeding(net.minecraft.world.entity.animal.Animal a) {
        if (cullMark != null && cullMark.equals(a.getUUID())) return false;   // [sf] past the herd kept (cullWork counted)
        int same = level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, a.getBoundingBox().inflate(24.0),
            o -> o.isAlive() && !o.isBaby() && o.getType() == a.getType()).size();
        return same < 3;
    }

    // ------------------------------ the hunter -----------------------------

    /** When it last saw game it could take, and when it last walked to another part of its grounds. */
    private int gameSeenTick, stalkTick = -100000, bowTick = -100000;

    /** The kinds of game a hunter takes, by the word the hunt goes by. */
    @Nullable
    static String gameWord(net.minecraft.world.entity.animal.Animal a) {
        if (a instanceof net.minecraft.world.entity.animal.Cow && !(a instanceof net.minecraft.world.entity.animal.MushroomCow)) return "cow";
        if (a instanceof net.minecraft.world.entity.animal.Pig) return "pig";
        if (a instanceof net.minecraft.world.entity.animal.Sheep) return "sheep";
        if (a instanceof net.minecraft.world.entity.animal.Chicken) return "chicken";
        if (a instanceof net.minecraft.world.entity.animal.Rabbit) return "rabbit";
        return null;
    }

    /** Game it may take: grown, wild (not a herd, not named, not on a lead), and not one of the last of its kind. */
    private boolean fairGame(net.minecraft.world.entity.animal.Animal a) {
        return a.isAlive() && !a.isBaby() && !a.hasCustomName() && gameWord(a) != null && !spareTheHerd(a) && !spareForBreeding(a);
    }

    /**
     * The hunter's day, out on its grounds: it looks over the ground for game it may fairly take
     * and goes after the nearest (the hunt itself is HuntGoal: the chase, the bow or the blade, the
     * drops swept up); with nothing in sight it walks on to another part of its grounds, quietly;
     * and when a long day has turned up nothing at all, the game has gone from here and it looks
     * for new grounds. A full pack goes home to the stores (the station's own banking).
     */
    @Override
    protected boolean huntWork() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || ownerId() == null) return false;
        if (peekJob() != null) return true;
        WorkZone z = workZone();
        if (z == null) return false;
        bowFromTheStores();
        BlockPos c = z.center();
        int r = z.radius() + 16;
        net.minecraft.world.entity.animal.Animal quarry = null;
        double best = Double.MAX_VALUE;
        for (net.minecraft.world.entity.animal.Animal a : server.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(c).inflate(r, 12, r), this::fairGame)) {
            double d = a.distanceToSqr(this);
            if (d < best) { best = d; quarry = a; }
        }
        if (quarry != null) {
            gameSeenTick = tickCount;
            String word = gameWord(quarry);
            // The pens short of this kind: it comes home alive, for the rancher.
            if (Drover.fetchHome(this, server, quarry)) return true;
            if (quarry.distanceToSqr(this) > 20 * 20) {
                // Out of the hunt's reach yet: closer first, quietly.
                if (getNavigation().isDone()) getNavigation().moveTo(quarry, 0.9D);
                brain("stalking a " + word);
                return true;
            }
            enqueue(Job.hunt(word, 1));
            brain("after a " + word);
            return true;
        }
        // Nothing to take here: another part of the grounds.
        if (getNavigation().isDone() && tickCount - stalkTick > 240) {
            stalkTick = tickCount;
            int dx = getRandom().nextInt(z.radius() * 2 + 1) - z.radius(), dz = getRandom().nextInt(z.radius() * 2 + 1) - z.radius();
            BlockPos to = surfaceAt(c.getX() + dx, c.getZ() + dz);
            if (to != null) getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.8D);
            brain("walking the hunting grounds");
        }
        // A whole day of nothing: the game has gone from here.
        if (tickCount - gameSeenTick > 12000) {
            gameSeenTick = tickCount;
            newGrounds();
        }
        return true;
    }

    /** Once the village has built its pen, the rancher's ground is the pen: the herd lives inside the fence. */
    private void moveIntoThePen() {
        if (stationTask() != StationTask.RANCH || ownerId() == null) return;
        Drover.Pen p = Drover.pen(ownerId());
        if (p == null) return;
        WorkZone z = workZone();
        if (z != null && z.center().equals(p.centre())) return;
        setStation(p.centre(), StationTask.RANCH);
        assignPlot(WorkZone.around(p.centre(), 3, WorkZone.DEFAULT_DEPTH), "The Pen");
        setAutonomous(true);
        brain("the herd's ground is the pen now");
        FolkTalk.speak(this, "The pen's built! I'll bring the herd in.");
    }

    /** A rancher with a built pen looks over the herd every half-minute: one that got out (through a
     *  gate a player left open, or slipping past a folk) is fetched back while it is still near. */
    private boolean mindTheHerd(net.minecraft.server.level.ServerLevel level) {
        if (stationTask() != StationTask.RANCH || ownerId() == null || peekJob() != null || Drover.busy(this)) return false;
        if (!level.isDay() || Raids.underAlarm(ownerId())) return false;
        Drover.Pen p = Drover.pen(ownerId());
        WorkZone z = workZone();
        if (p == null || z == null || !z.center().equals(p.centre())) return false;
        return Drover.fetchStray(this, level, p);
    }

    /** Tests: a beat of the hunter's day, as the station brain would run it. */
    public boolean huntForTests() {
        return huntWork();
    }

    // ------------------------------ the pen's larder [sf] ---------------------

    /**
     * The herd a rancher keeps of each kind: a pair to breed and a pair besides. What the pen has past it
     * is the larder's: the mountain town's two ranchers never brought in a meal ("0 from the pen (2
     * ranchers, 0 each)"), because the cull went through the hunt, and the hunt spares every animal on a
     * rancher's ground.
     */
    public static final int HERD_KEPT = 4;
    /** The one animal being culled now: neither the herd's nor the breeding pair's to spare. */
    @Nullable private UUID cullMark;
    private int cullTick = -100000;

    /** A look over the pen every half-minute: one past the herd kept of any kind is culled for the larder. */
    @Override
    protected boolean cullWork() {
        if (cullMark != null && peekJob() == null) cullMark = null;       // that cull is over, one way or the other
        if (stationTask() != StationTask.RANCH || ownerId() == null || peekJob() != null || Drover.busy(this)) return false;
        if (tickCount - cullTick < 600) return false;
        return cullNow();
    }

    private boolean cullNow() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        WorkZone z = workZone();
        if (z == null) return false;
        cullTick = tickCount;
        net.minecraft.world.entity.animal.Animal one = Drover.surplus(server, z.center(), Math.max(8, Math.min(16, z.radius())), HERD_KEPT,
            Fletchers.henFirst(server, ownerId()));               // [fletcher] an old hen first, while the fletcher wants feathers
        String word = one == null ? null : gameWord(one);
        if (word == null) return false;
        // The hunt does the killing (the blade, the drops swept into the pack); the meat, the hide and the wool
        // go home to the stores with the rest of the pen's work, booked "from the pen" (Larder).
        cullMark = one.getUUID();
        enqueue(Job.hunt(word, 1));
        brain("culling a " + Drover.kind(one) + ": the pen has more than " + HERD_KEPT);
        return true;
    }

    /** Tests: look over the pen now, as the station brain would, without the half-minute's wait. */
    public boolean cullForTests() {
        if (cullMark != null && peekJob() == null) cullMark = null;
        if (stationTask() != StationTask.RANCH || peekJob() != null) return false;
        return cullNow();
    }

    // ------------------------------ the fisher [sf] ----------------------------

    /**
     * A working day at one water with nothing landed: the fish are not to be had there. The hunter has
     * always gone looking for new grounds after a day of nothing (huntWork); the fisher sat by its pond.
     * The mountain town's one fisher brought in "0 fish" day after day, and a mountain town's water is
     * under ice, or down a cliff nobody can get to: it would have cast at it for good.
     */
    static final int FISHLESS_TICKS = 9000;
    /**
     * Working time at this water since it last landed anything (counted between the station brain's looks,
     * so a night in bed is not a day without a bite), the water it is for, and casts that found no water it
     * could fish.
     */
    private int fishlessTicks, fishLookTick;
    @Nullable private BlockPos catchGround;
    private int dryCasts;
    private int waterLookTick = -100000;

    @Override
    public void landedACatch() {
        fishlessTicks = 0;
        dryCasts = 0;
    }

    @Override
    public void noWaterToFish() {
        dryCasts++;
    }

    /** True when it has moved: to other water, or (with none within reach of the town) to another trade. */
    @Override
    protected boolean fishWork() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || ownerId() == null
                || stationTask() != StationTask.FISH) return false;
        WorkZone z = workZone();
        if (z == null) return false;
        if (catchGround == null || !catchGround.equals(z.center())) {
            catchGround = z.center();
            fishlessTicks = 0;
            dryCasts = 0;
        }
        int gap = tickCount - fishLookTick;
        fishLookTick = tickCount;
        if (gap > 0 && gap < 600) fishlessTicks += gap;          // its working time, not the night or a long errand
        boolean dry = dryCasts >= 2;
        if (!dry && tickCount - waterLookTick > 1200) {
            waterLookTick = tickCount;
            dry = com.jrpetty.mcassistant.entity.goal.FishGoal.waterFor(this) == null;
        }
        boolean fishless = fishlessTicks > FISHLESS_TICKS;
        if (!dry && !fishless) return false;
        boolean couldNotCast = dry || dryCasts > 0;
        fishlessTicks = 0;
        dryCasts = 0;
        return newWaters(server, couldNotCast);
    }

    /**
     * Other water, as the hunter finds new grounds: open water (not under ice, not under a ledge) that the
     * town can walk to (findSite). With none to be had and this water no use to it either, the village
     * stops wanting a fisher for a few days (Villages.noWaterForFishers) and this one takes up the trade
     * the village is shortest of.
     */
    private boolean newWaters(net.minecraft.server.level.ServerLevel server, boolean dry) {
        UUID village = ownerId();
        if (village == null) return false;
        WorkZone was = workZone();
        avoidHere = was;
        searchBearing += 3;
        BlockPos site = findSite(StationTask.FISH, radiusFor(StationTask.FISH));
        avoidHere = null;
        long day = level().getDayTime() / 24000L;
        if (site != null && (was == null || site.distSqr(was.center()) >= 16 * 16)) {
            setStation(site, StationTask.FISH);
            assignPlot(WorkZone.around(site, radiusFor(StationTask.FISH), WorkZone.DEFAULT_DEPTH), patchNameFor(StationTask.FISH));
            setAutonomous(true);
            FolkTalk.speak(this, dry
                ? FolkTalk.pick(getRandom(), "There's no getting a line into that water. I'll find some that'll take one.",
                    "Ice and rock, no fishing here. Other water, then.")
                : FolkTalk.pick(getRandom(), "Not a bite all day. The fish are somewhere else.",
                    "Nothing landed since morning. I'll try other water."));
            brain("new waters: " + site.toShortString());
            return true;
        }
        if (!dry) {
            brain("no other water to try; this one will give a fish yet");
            return false;
        }
        Villages.noWaterForFishers(village, level().getGameTime());
        Villages.tell(village, day, displayNameCap() + " gave up fishing: no water fit to fish within reach of the town");
        FolkTalk.speak(this, "There's no water within reach of the town that'll give a fish. I'll turn my hand to something else.");
        StationTask next = Villages.needed(village);
        if (next == StationTask.FISH) next = StationTask.FARM;
        BlockPos there = findSite(next, radiusFor(next));
        if (there != null) {
            setStation(there, next);
            assignPlot(WorkZone.around(there, radiusFor(next), depthFor(next, there)), patchNameFor(next));
            setAutonomous(true);
        } else {
            setWorkZone(null);              // its trade and its ground let go: it takes up what the town wants (takeUpATrade)
        }
        brain("no water for a fisher within reach of the town: now " + stationTask().label);
        return true;
    }

    /** Tests: the fisher's look at its water, as the station brain runs it; {@code day} pretends a working day went by. */
    public boolean fishWorkForTests(boolean day) {
        WorkZone z = workZone();
        if (z != null) catchGround = z.center();
        if (day) fishlessTicks = FISHLESS_TICKS + 1;
        waterLookTick = -100000;
        return fishWork();
    }

    /** New hunting grounds, somewhere else round the village: the game has gone from the old. */
    private void newGrounds() {
        searchBearing += 3;
        BlockPos site = findSite(StationTask.HUNT, radiusFor(StationTask.HUNT));
        if (site == null || workZone() != null && site.distSqr(workZone().center()) < 32 * 32) {
            brain("no better grounds to be had; staying put");
            return;
        }
        WorkZone zone = WorkZone.around(site, radiusFor(StationTask.HUNT), WorkZone.DEFAULT_DEPTH);
        setStation(site, StationTask.HUNT);
        assignPlot(zone, "Hunting Grounds");
        setAutonomous(true);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "The game's gone from round here. I'll try further out.",
            "Nothing but tracks for a day. New grounds, I think."));
        brain("new hunting grounds");
    }

    /** A bow from the stores, or one of its own made of three string and three sticks (a log will do). */
    private void bowFromTheStores() {
        if (hasBow() || villageCentre == null || tickCount - bowTick < 1200) return;
        bowTick = tickCount;
        int r = buildStoresRadius();
        if (drawFrom(villageCentre, st -> st.is(net.minecraft.world.item.Items.BOW), 1, r) > 0) {
            brain("a bow from the stores");
            return;
        }
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> string = st -> st.is(net.minecraft.world.item.Items.STRING);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> wood = st -> st.is(net.minecraft.world.item.Items.STICK)
            || st.is(net.minecraft.tags.ItemTags.PLANKS) || st.is(net.minecraft.tags.ItemTags.LOGS);
        int s0 = countCarried(string), w0 = countCarried(wood);
        if (s0 < 3) drawFrom(villageCentre, string, 3 - s0, r);
        if (w0 < 1) drawFrom(villageCentre, wood, 1, r);
        if (countCarried(string) >= 3 && countCarried(wood) >= 1) {
            removeMatching(string, 3);
            removeMatching(wood, 1);
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
            brain("made a bow of the stores' string and wood");
            return;
        }
        returnTo(villageCentre, string, s0, r);
        returnTo(villageCentre, wood, w0, r);
    }

    /**
     * The village's herd is not game: an animal on a lead, one the rancher brought home or bought
     * (marked as the village's), or one inside a rancher's ground. A hungry village hunted every food animal within
     * twenty-four blocks, its own pens among them, and no herd ever grew.
     */
    @Override
    public boolean spareTheHerd(net.minecraft.world.entity.animal.Animal a) {
        if (cullMark != null && cullMark.equals(a.getUUID()) && !a.isLeashed()) return false;   // [sf] the one it is culling
        if (a.isLeashed() || a.getTags().contains(Drover.HERD)) return true;
        UUID village = ownerId();
        if (village == null) return false;
        for (AssistantEntity x : Villages.folkOf(village)) {
            if (x.stationTask() != StationTask.RANCH || x.workZone() == null) continue;
            BlockPos c = x.workZone().center();
            int r = x.workZone().radius() + 4;
            if (Math.abs(a.getBlockX() - c.getX()) <= r && Math.abs(a.getBlockZ() - c.getZ()) <= r) return true;
        }
        return false;
    }

    @Override
    protected int tripToStores() {
        UUID village = ownerId();
        if (village == null || villageCentre == null || workZone() == null) return 0;
        return (int) Math.sqrt(workZone().center().distSqr(villageCentre));
    }

    /** Carrying a load it took off a worker's hands, on its way to the stores. */
    private boolean haulLoad;
    /** Carrying ore and fuel for the smelter. */
    @Nullable private UUID haulFor;

    /**
     * A village's carrier. With no chests to run between (a village banks at its stores), it
     * carries for the trades instead:
     * <ul>
     * <li>a worker out on a far plot with a heavy pack has its load taken off its hands, and the
     *     carrier walks it home to the stores, so the miner keeps mining and the woodcutter keeps
     *     cutting;</li>
     * <li>the smelter is brought ore and fuel out of the stores when it is running low.</li>
     * </ul>
     */
    @Override
    protected boolean haulerRound() {
        UUID village = ownerId();
        if (village == null || villageCentre == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        // The storehouse's courier: its runs come off the storehouse's run list (Couriers). What
        // follows is only for a carrier with no storehouse (or storage) to work out of.
        if (Couriers.work(this, server)) return true;
        BlockPos depot = storesSpot(server, village);
        if (depot == null) depot = villageCentre;
        // A load on its back: home with it.
        if (haulLoad) {
            haulLoad = false;
            if (stashable() > 0) {
                enqueue(Job.depositAt(depot));
                brain("carrying a load home to the stores");
                return true;
            }
        }
        // Ore and fuel for the smelter, carried out to it; or, with no ore about, the makings of its
        // mason's work (spare cobblestone, clay, sand: Masonry.makings).
        if (haulFor != null) {
            if (!(server.getEntity(haulFor) instanceof VillageFolkEntity smelter) || !smelter.isAlive()
                    || countCarried(FOR_THE_SMELTER) == 0) {
                haulFor = null;
            } else if (distanceToSqr(smelter) > 3.0 * 3.0) {
                walkTo(smelter.blockPosition(), 1.1D);
                hobbyNow = "taking " + (countCarried(AssistantEntity.SMELTABLE_ORE) > 0 ? "ore" : "stone and clay")
                    + " to " + smelter.displayNameCap();
                return true;
            } else {
                boolean ore = countCarried(AssistantEntity.SMELTABLE_ORE) > 0;
                int given = handOver(smelter, FOR_THE_SMELTER);
                haulFor = null;
                if (given > 0) {
                    note(Deed.LOADS_HAULED, 1);
                    FolkTalk.speak(this, ore
                        ? FolkTalk.pick(getRandom(), "Ore for the furnaces, " + smelter.displayNameCap() + "!", "Here — keep those fires going.")
                        : FolkTalk.pick(getRandom(), "Stone for the bench, " + smelter.displayNameCap() + ".", "Something for your furnaces — the builders are waiting on bricks."));
                    return true;
                }
            }
        }
        // A worker far out with a heavy pack.
        VillageFolkEntity far = null;
        int most = 47;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == this || f.isBaby() || !f.isAlive() || f.workZone() == null) continue;
            StationTask t = f.stationTask();
            if (t != StationTask.MINE && t != StationTask.WOOD && t != StationTask.FARM && t != StationTask.FISH && t != StationTask.RANCH) continue;
            if (f.workZone().center().distSqr(depot) < 40 * 40) continue;     // near enough to bank its own
            int load = f.stashable();
            if (load > most) { most = load; far = f; }
        }
        if (far != null) {
            if (distanceToSqr(far) > 3.0 * 3.0) {
                walkTo(far.blockPosition(), 1.1D);
                hobbyNow = "on the way to fetch " + far.displayNameCap() + "'s load";
                return true;
            }
            int took = takeLoadFrom(far);
            if (took > 0) {
                haulLoad = true;
                note(Deed.LOADS_HAULED, 1);
                FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I'll take that off your hands, " + far.displayNameCap() + ".",
                    "Give it here — I'm going that way."));
                enqueue(Job.depositAt(depot));
                return true;
            }
        }
        // The smelter running low, and ore in the stores. With no ore to take, what the smeltery works
        // for the masons and the glaziers: the stores' spare cobblestone (stone bricks and smooth stone
        // are fired from it), their clay (bricks) and their sand (glass), each only while the village is
        // short of what it makes.
        Villages.Village vill = Villages.get(village);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.SMELT || !f.isAlive()) continue;
            // [diver] A smelter with its ore but nothing it may burn now (the stores' kelp blocks, while there are any) is
            // brought kelp blocks on their own; one with ore and fuel is left to it, as before.
            boolean stocked = f.countCarried(AssistantEntity.SMELTABLE_ORE) >= 8;
            boolean kelpWanted = FuelBook.kelpInStores(server, village) && f.countCarried(FuelBook.KELP_BLOCK) < 2;
            if (stocked && (FuelBook.fuelled(f) || !kelpWanted)) continue;
            int got = stocked ? 0 : drawFrom(villageCentre, AssistantEntity.SMELTABLE_ORE, 32, buildStoresRadius());
            String what = "ore";
            if (!stocked && got <= 0 && vill != null && f.countCarried(Masonry.MAKINGS) < 16) {
                for (Masonry.Lot lot : Masonry.makings(server, vill)) {
                    int n = drawFrom(villageCentre, lot.what(), lot.n(), buildStoresRadius());
                    if (n > 0 && got == 0) what = lot.word();
                    got += n;
                }
            }
            // [diver] The diver's kelp blocks go out first while the stores have them, and then no coal at all (FuelBook).
            int kelp = kelpWanted ? drawFrom(villageCentre, FuelBook.KELP_BLOCK, 4, buildStoresRadius()) : 0;
            if (kelp > 0) {
                FuelBook.forget(village);
                if (got <= 0) what = "kelp blocks";
            }
            if (got <= 0 && kelp <= 0) {
                if (stocked) continue;
                break;
            }
            // Fuel with it — but not the coal the age is putting by: the smelter burns wood then
            // (SmeltGoal), and what goes out to it is logs the builders can spare.
            if (kelp > 0) {
                // [diver] (the kelp blocks are its fuel)
            } else if (!savingCoal() && !coalLow()) {        // [economy] nor the last of it, under the floor (Fuel)
                drawFrom(villageCentre, s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL), 8, buildStoresRadius());
            } else {
                int wood = Math.min(8, logsToSpare(server, village));
                if (wood > 0) drawFrom(villageCentre, s -> s.is(net.minecraft.tags.ItemTags.LOGS), wood, buildStoresRadius());
            }
            haulFor = f.getUUID();
            brain("taking " + got + " " + what + " out to " + f.displayNameCap());
            return true;
        }
        return false;
    }

    /** What a courier carries out to the smelter: ore, fuel, and the makings of its mason's work. */
    static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> FOR_THE_SMELTER =
        s -> AssistantEntity.SMELTABLE_ORE.test(s) || s.is(net.minecraft.world.item.Items.COAL)
            || s.is(net.minecraft.world.item.Items.CHARCOAL) || s.is(net.minecraft.tags.ItemTags.LOGS) || Masonry.MAKINGS.test(s)
            || s.is(net.minecraft.world.item.Items.DRIED_KELP_BLOCK);          // [diver] the diver's kelp blocks

    /** A worker's load (what it would bank: its output, not its kit) into this carrier's pack. */
    int takeLoadFrom(VillageFolkEntity worker) {
        int moved = 0;
        var inv = worker.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.world.item.ItemStack s = inv.get(i);
            if (s.isEmpty() || s.isDamageableItem()) continue;
            int move = s.getCount() - Math.max(0, worker.depositReserve(s));
            if (move <= 0) continue;
            net.minecraft.world.item.ItemStack lot = s.copyWithCount(move);
            net.minecraft.world.item.ItemStack left = insertItem(lot);
            int taken = move - left.getCount();
            if (taken <= 0) break;                                  // its own pack is full
            Economy.produced(worker, s.copyWithCount(taken));       // the worker's output, handed over here
            s.shrink(taken);
            moved += taken;
        }
        return moved;
    }

    /** What matches out of this pack into another's. Returns how many went. */
    private int handOver(VillageFolkEntity to, java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        int moved = 0;
        var inv = getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.world.item.ItemStack s = inv.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            net.minecraft.world.item.ItemStack left = to.insertGiven(s.copy());
            int taken = s.getCount() - left.getCount();
            s.shrink(taken);
            moved += taken;
        }
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return moved;
    }

    /** A scout's day (Scouts): out at first light, home by dusk, the atlas in between. */
    @Override
    protected boolean scoutWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Scouts.work(this, server);
    }

    /** [caves] A cave dweller's day (CaveDwellers): kitted out and down the caves at first light, home by dusk. */
    @Override
    protected boolean caveWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && CaveDwellers.work(this, server);
    }

    /** [transport] The ferryman's day at the landings (Ferries.duty). */
    @Override
    protected boolean ferryWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Ferries.duty(this, server);
    }

    /** [fireworks] The fireworks maker's day at the powder hut: stars and rockets out of the stores (FireworksMaker.work). */
    @Override
    protected boolean fireworksWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && FireworksMaker.work(this, server);
    }

    /** [cartographer] The cartographer's day: at the map room's table, or out walking the town with a sheet (Cartographers). */
    @Override
    protected boolean cartographerWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Cartographers.work(this, server);
    }

    /** [emerald] The emerald trader's day (EmeraldTrader.work): out to the villagers in the morning, the book at home. */
    @Override
    protected boolean emeraldWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && EmeraldTrader.work(this, server);
    }

    /** [diver] The diver's day (Divers.work): its next dive, or the bank to watch the water from. */
    @Override
    protected boolean diverWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Divers.work(this, server);
    }

    /** [diver] Under the water on purpose, or being pulled out of it (Divers). */
    @Override
    protected boolean underwaterWork() {
        return Divers.underwater(this);
    }

    private int kelpCheckTick = -100000;
    private boolean kelpFuel;

    /** [diver] Has the town dried kelp blocks in its stores for its fires (FuelBook)? Looked at once a minute. */
    @Override
    public boolean kelpForFuel() {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - kelpCheckTick >= 1200 || tickCount < kelpCheckTick) {
            kelpCheckTick = tickCount;
            kelpFuel = FuelBook.kelpInStores(server, village);
        }
        return kelpFuel;
    }

    /** A village's storekeeper keeps its stores in order from the first day, not from its
     *  tenth level: nothing else a storekeeper does earns it the experience to get there. */
    @Override
    public boolean can(Ability a) {
        if (a == Ability.STORE_SORT && stationTask() == StationTask.STORE) return true;
        // A village's farmers feed its fields with bone meal (the watch's bones, the compost).
        if (a == Ability.FARM_BONEMEAL && stationTask() == StationTask.FARM) return true;
        return super.can(a);
    }

    /** The delivery chest, or the next store with room when it is full. */
    @Override
    @Nullable
    protected BlockPos freshDepot(BlockPos current) {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return current;
        if (level().getBlockEntity(current) instanceof net.minecraft.world.Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return current;
        }
        BlockPos next = Villages.depot(server, village);
        return next != null ? next : current;
    }

    /** The pickup has nothing left worth the walk: choose the next round at once. */
    @Override
    protected void routeSpent() {
        if (preferredChest() != null) spentPickups.put(preferredChest().asLong(), tickCount);
        if (tickCount - routeTick > 200) routeTick = -100000;
    }

    /** Pickups that came up empty, and when: passed over a while (a field's chest keeps its seed,
     *  and counted fuller than the furnace's ingots, it was chosen and found empty again and again). */
    private final java.util.Map<Long, Integer> spentPickups = new java.util.HashMap<>();

    /**
     * What a worker makes goes into its own production chest at its plot — and only that: the
     * couriers carry it in to the storehouse, and the worker is paid for what it put in. A chest
     * that is full (the couriers behind), or none yet: the load goes to the stores itself.
     */
    @Override
    protected Job outputDeposit() {
        if (level() instanceof net.minecraft.server.level.ServerLevel server) {
            BlockPos chest = keepProductionChest(server);
            if (chest != null && server.isLoaded(chest) && server.getBlockEntity(chest) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).isEmpty()) return Job.depositAt(chest);
                }
            }
        }
        return storesDeposit();
    }

    @Override
    public Job villageDepositJob() {
        return usesVillageStores() ? outputDeposit() : null;
    }

    /**
     * Out past the chunks its village keeps awake (VillageSpawner's ring round the heart) and its
     * own plot's window, a folk takes a window of loaded chunks with it — on the walk out to a far
     * field or wood, a long way round to the stores, a fetch for the builders — so the village
     * goes on working, every one of them, wherever they are, with nobody about.
     */
    @Override
    protected boolean carriesChunkWindow() {
        if (super.carriesChunkWindow()) return true;
        UUID village = ownerId();
        BlockPos heart = villageCentre;
        if (village == null || heart == null) return false;
        BlockPos here = blockPosition();
        int ring = com.jrpetty.mcassistant.VillageSpawner.loadedRadiusFor(Villages.headcount(village));
        if (Math.abs((here.getX() >> 4) - (heart.getX() >> 4)) < ring
            && Math.abs((here.getZ() >> 4) - (heart.getZ() >> 4)) < ring) return false;
        return !nearOwnPost(here);
    }

    // ------------------------------ the production chest ------------------------

    /** Its production chest (a producer's, at the edge of its plot), or null. */
    @Nullable private BlockPos productionChest;
    private int productionTick = -100000;
    /** Its production chest from the plot it has moved on from, which it is bringing along to the new
     *  one (carryTheOldChest), or null. Never a second chest: while it is set, none other goes down. */
    @Nullable private BlockPos oldProductionChest;
    private int oldChestTries;

    /** The trades that make things out on a plot of their own, into a production chest of their own. */
    static boolean producer(StationTask t) {
        return t == StationTask.FARM || t == StationTask.WOOD || t == StationTask.MINE || t == StationTask.FISH
            || t == StationTask.RANCH || t == StationTask.HUNT || t == StationTask.BEEKEEP;
    }

    /** Its production chest, or null. */
    @Nullable
    public BlockPos productionChest() {
        return productionChest;
    }

    /** The village's production chests (never cleared out into the storehouse), as BlockPos longs. */
    public static java.util.Set<Long> productionChests(UUID village) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.productionChest != null) out.add(f.productionChest.asLong());
        }
        return out;
    }

    /** Is this one of the village's production chests? */
    static boolean isProductionChest(UUID village, BlockPos pos) {
        return productionChests(village).contains(pos.asLong());
    }

    /** Its old production chest, which it is bringing along to its new plot, or null. */
    @Nullable
    public BlockPos oldProductionChest() {
        return oldProductionChest;
    }

    /** Every chest a worker of the village has in use: its production chest, and an old one it is
     *  bringing along to its new plot. Never an old chest to clear away, nor one of the stores. */
    static java.util.Set<Long> chestsInUse(UUID village) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            if (f.productionChest != null) out.add(f.productionChest.asLong());
            if (f.oldProductionChest != null) out.add(f.oldProductionChest.asLong());
        }
        return out;
    }

    /** Is this chest one a worker of the village has in use (chestsInUse)? */
    static boolean chestInUse(UUID village, BlockPos pos) {
        return chestsInUse(village).contains(pos.asLong());
    }

    /** Is this chest in use by a worker other than {@code who}? Only its own worker takes it up. */
    public static boolean chestInUseByAnother(UUID village, BlockPos pos, AssistantEntity who) {
        long key = pos.asLong();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == who) continue;
            if (f.productionChest != null && f.productionChest.asLong() == key) return true;
            if (f.oldProductionChest != null && f.oldProductionChest.asLong() == key) return true;
        }
        return false;
    }

    /** It has just taken its old production chest up (RetireGoal): down it goes at the new plot at once,
     *  and what was in it back into it — before anything else sends the load to the stores. */
    public void oldChestTakenUp() {
        if (level() instanceof net.minecraft.server.level.ServerLevel server) carryTheOldChest(server);
    }

    /** Tests: bring the old production chest along now, whatever the clock says (as the agenda would). */
    public boolean carryTheOldChestForTests() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && carryTheOldChest(server);
    }

    /**
     * Its plot has moved on (a spent mine, new hunting grounds, the pen built...): its production
     * chest comes along. It walks back to the old chest, takes what is in it and the chest itself
     * (RetireGoal), and sets it down at the edge of the new plot with the goods in it — one chest a
     * worker, ever. A pack that could not hold what is in it leaves it for the couriers: the chest
     * becomes an old chest for them to clear into the storehouse and take up (Retiring), and the
     * worker has another from the stores. So does one that cannot get to it twice. It used to
     * simply leave the old one standing, full, and set another down: a miner a few spent mines on
     * had a chest at every one of them.
     */
    private boolean carryTheOldChest(net.minecraft.server.level.ServerLevel server) {
        BlockPos old = oldProductionChest;
        if (old == null) return false;
        if (!producer(stationTask()) || workZone() == null || ownerId() == null) {
            letTheOldChestGo(false);
            return false;
        }
        boolean loaded = server.isLoaded(old);
        boolean standing = !loaded || server.getBlockEntity(old) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity;
        if (!standing) {
            // Taken up (by its own hands, or it is gone): down it goes at the new plot, from the pack.
            oldProductionChest = null;
            oldChestTries = 0;
            boolean carried = countCarried(s -> s.is(net.minecraft.world.item.Items.CHEST)) > 0;
            productionTick = -100000;
            BlockPos fresh = keepProductionChest(server);
            if (fresh != null) {
                if (carried) FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Brought my chest along — it goes here now.",
                    "Same chest, new plot. The couriers will find it at the edge, as ever."));
                brain("brought its production chest along to " + fresh.toShortString());
                if (stashable() > 0) enqueue(outputDeposit());             // what was in it goes back in
            }
            return fresh != null;
        }
        if (peekJob() != null || !onShift() || onBreak()) return false;
        if (oldChestTries >= 2) {
            letTheOldChestGo(true);
            return false;
        }
        // Room in its pack for everything in it, and for the chest? If not, it is the couriers'.
        if (loaded && server.getBlockEntity(old) instanceof net.minecraft.world.Container box) {
            int stacks = 0, free = 0;
            for (int i = 0; i < box.getContainerSize(); i++) if (!box.getItem(i).isEmpty()) stacks++;
            for (net.minecraft.world.item.ItemStack st : getInventoryItems()) if (st.isEmpty()) free++;
            if (stacks + 1 > free) {
                letTheOldChestGo(true);
                return false;
            }
        }
        oldChestTries++;
        enqueue(Job.retire(old));
        brain("going back for its production chest at " + old.toShortString());
        return true;
    }

    /** It will not be bringing its old chest along: the couriers clear it into the storehouse and take
     *  it up (Retiring), and the chest goes back to the stores. */
    private void letTheOldChestGo(boolean say) {
        BlockPos old = oldProductionChest;
        oldProductionChest = null;
        oldChestTries = 0;
        productionTick = -100000;
        if (old == null) return;
        brain("left its old production chest at " + old.toShortString() + " for the couriers");
        if (say) FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I can't bring my old chest along — the couriers can clear it into the storehouse.",
            "My old chest's too full to carry. I'll leave it for the couriers."));
    }

    /** Tests: the production chest now, set down if it can be. */
    @Nullable
    public BlockPos productionChestForTests() {
        productionTick = -100000;
        return level() instanceof net.minecraft.server.level.ServerLevel server ? keepProductionChest(server) : null;
    }

    /**
     * Its production chest, standing — set down if it has none yet: on its own plot, at the edge
     * towards the town (a field's in the corner of the field it will grow to), where the couriers
     * come for it and nobody else's ground is touched. Its own chest from its pack, or one from the
     * stores, or one made of eight planks (or two logs) from the stores. Null if it cannot have
     * one yet, or keeps no plot.
     */
    @Nullable
    private BlockPos keepProductionChest(net.minecraft.server.level.ServerLevel level) {
        StationTask t = stationTask();
        WorkZone z = workZone();
        if (!producer(t) || z == null) {
            // No plot of its own to keep one on (another trade now, or none): its chest is the village's
            // to clear — the couriers empty it into the storehouse and take it up (Retiring).
            productionChest = null;
            oldProductionChest = null;
            return null;
        }
        if (villageCentre == null || ownerId() == null || isBaby()) return null;
        int reach = (t == StationTask.FARM ? Math.max(FIELD_MOST, z.radius()) : z.radius()) + 2;
        if (productionChest != null) {
            if (!level.isLoaded(productionChest)) return productionChest;
            BlockPos c = z.center();
            // And on the plot's own level: a riverside fisher's chest set down on the clifftop fourteen blocks
            // over its bank was never reached again, and three days' catch went nowhere.
            boolean near = Math.max(Math.abs(productionChest.getX() - c.getX()), Math.abs(productionChest.getZ() - c.getZ())) <= reach + 4
                && Math.abs(productionChest.getY() - c.getY()) <= CHEST_RISE + 1;
            boolean standing = level.getBlockEntity(productionChest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity;
            if (near && standing) return productionChest;
            if (standing && oldProductionChest == null) {
                // The plot has moved on: the chest comes along to the new one (carryTheOldChest).
                oldProductionChest = productionChest;
                oldChestTries = 0;
                brain("its plot moved on: bringing its production chest along");
            }
            productionChest = null;                              // gone, or the plot has moved
        }
        // One chest a worker: while the old one is on its way, no other goes down.
        if (oldProductionChest != null) return null;
        if (tickCount - productionTick < 600) return null;
        productionTick = tickCount;
        BlockPos c = z.center();
        double dx = villageCentre.getX() - c.getX(), dz = villageCentre.getZ() - c.getZ();
        BlockPos spot;
        if (t == StationTask.FARM) {
            // The corner of the field (as it will grow) nearest the town: one square of eighty given up,
            // on its own ground, by the way in from the town.
            int sx = dx >= 0 ? 1 : -1, sz = dz >= 0 ? 1 : -1;
            spot = chestSpot(level, c.getX() + sx * FIELD_MOST, c.getZ() + sz * FIELD_MOST, c.getY());
        } else {
            // Just inside the edge of its plot, on the side towards the town.
            double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
            int in = Math.max(1, z.radius() - 1);
            spot = chestSpot(level, c.getX() + (int) Math.round(dx / len * in), c.getZ() + (int) Math.round(dz / len * in), c.getY());
        }
        // Nowhere on its own level by the edge towards the town (a bank under a cliff): by the middle of the plot.
        if (spot == null) spot = chestSpot(level, c.getX() + 1, c.getZ() + 1, c.getY());
        if (spot == null) return null;
        if (countCarried(s -> s.is(net.minecraft.world.item.Items.CHEST)) == 0) {
            drawFrom(villageCentre, s -> s.is(net.minecraft.world.item.Items.CHEST), 1, buildStoresRadius());
        }
        if (countCarried(s -> s.is(net.minecraft.world.item.Items.CHEST)) == 0) {
            int r = buildStoresRadius();
            if (drawFrom(villageCentre, s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 8, r) >= 8
                    && removeMatching(s -> s.is(net.minecraft.tags.ItemTags.PLANKS), 8) == 8) {
                insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST));
            } else if (drawFrom(villageCentre, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 2, r) >= 2
                    && removeMatching(s -> s.is(net.minecraft.tags.ItemTags.LOGS), 2) == 2) {
                insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST));
            }
        }
        if (removeMatching(s -> s.is(net.minecraft.world.item.Items.CHEST), 1) != 1) return null;
        level.setBlockAndUpdate(spot, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, spot);
        productionChest = spot.immutable();
        brain("set down its production chest at the edge of " + (patchName() != null ? patchName() : "its plot"));
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "A chest at the edge of my plot: everything I make goes in, and the couriers take it in.",
            "There — my production chest. The couriers will fetch from it."));
        return productionChest;
    }

    /** How far above or below its plot's ground a worker's chest may stand: a step or two, never a cliff. */
    private static final int CHEST_RISE = 3;

    /** Open, level ground for a chest near here, on the plot's own level (within {@link #CHEST_RISE} of
     *  {@code y}): a solid floor, nothing in the way, no water, and on nobody else's plot. Null if there
     *  is none within three blocks. */
    @Nullable
    private BlockPos chestSpot(net.minecraft.server.level.ServerLevel level, int x, int z, int y) {
        UUID village = ownerId();
        for (int r = 0; r <= 3; r++) {
            for (int ix = -r; ix <= r; ix++) {
                for (int iz = -r; iz <= r; iz++) {
                    if (Math.max(Math.abs(ix), Math.abs(iz)) != r) continue;
                    BlockPos p = surfaceAt(x + ix, z + iz);
                    if (p == null || Math.abs(p.getY() - y) > CHEST_RISE) continue;
                    BlockState below = level.getBlockState(p.below());
                    if (!below.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) continue;
                    if (!level.getBlockState(p).canBeReplaced() || !level.getBlockState(p).getFluidState().isEmpty()) continue;
                    if (below.is(Blocks.FARMLAND)) continue;
                    boolean onAPlot = false;
                    if (village != null) {
                        for (AssistantEntity a : Villages.folkOf(village)) {
                            WorkZone o = a == this ? null : a.workZone();
                            if (o != null && o.containsColumn(p)) { onAPlot = true; break; }
                        }
                    }
                    if (!onAPlot) return p;
                }
            }
        }
        return null;
    }

    /** A load for the village's stores: to the storehouse (or the next store with room). */
    @Override
    protected Job storesDeposit() {
        UUID village = ownerId();
        if (village != null && level() instanceof net.minecraft.server.level.ServerLevel server) {
            BlockPos depot = Villages.depot(server, village);
            if (depot != null) return Job.depositAt(depot);
        }
        return Job.deposit();
    }

    /**
     * Sink the same shaft properly. A village mine is staked on the surface
     * and floored 24 blocks under it, which is the right depth for stone, coal
     * and iron and nowhere near deep enough for anything else. An experienced
     * miner whose village is asking for diamonds re-marks its own plot down to
     * the bedrock and keeps digging — same ground, same claim, a real shaft.
     */
    private boolean deepenShaft() {
        WorkZone zone = workZone();
        // An iron pickaxe, not twenty levels of experience: a village's miners stood at
        // level one after three game days, and diamond ore wants iron to break.
        if (zone == null || pickTierCarried() < 3) return false;
        int floor = deepestMine();
        if (zone.depth() <= floor + 4) return false;          // already down there
        assignPlot(WorkZone.around(zone.center(), zone.radius(), floor),
            patchNameFor(StationTask.MINE));
        setAutonomous(true);
        return true;
    }

    /** A short stroll while looking for somewhere to settle to work. */
    private void roam() {
        if (!getNavigation().isDone()) return;
        BlockPos from = villageCentre != null ? villageCentre : blockPosition();
        int x = from.getX() + getRandom().nextInt(49) - 24;
        int z = from.getZ() + getRandom().nextInt(49) - 24;
        BlockPos to = surfaceAt(x, z);
        if (to != null) walkTo(to, 1.0D);
    }

    // ------------------------------ moving on, and knocking off --------------

    /**
     * A worked-out patch is not a patch. A miner whose seam is exhausted, or a
     * woodcutter left standing in stumps, does not walk the same empty ground
     * for ever — it lets the claim go and finds fresh ground. This is the only
     * reason a village can keep growing past its first hillside.
     *
     * <p>Only for the trades whose ground genuinely runs out. A field does not
     * run out; it comes round again.
     */
    private boolean movedOnFromSpentGround() {
        StationTask trade = stationTask();
        if (trade != StationTask.MINE && trade != StationTask.WOOD) return false;
        // Off the clock is not "found nothing": a night parked at home records
        // no work either, and a folk that gave up its shaft every dusk had a
        // new plot, no chest, and no way of making one out on bare stone.
        // Three empty galleries in a row say it plainly. The clock below never saw them:
        // a miner whose runs came home empty lent a hand to the woodpile in between,
        // which counted as work, and it went back to the same spent hole all game.
        boolean barren = trade == StationTask.MINE && barrenMineRuns >= 3 && onShift() && !onBreak();
        if (!barren) {
            if (!onShift() || onBreak() || !workedOut()) { spentSince = 0; return false; }
            if (spentSince == 0) { spentSince = tickCount; return false; }
            // Three solid minutes of finding nothing: long enough that a slow
            // patch is not abandoned, short enough that nobody stands in a
            // clearing all afternoon.
            if (tickCount - spentSince < 3600) return false;
        }
        spentSince = 0;
        barrenMineRuns = 0;
        // The face of the town's mine it worked is worked out: nobody is sent to it again (TownMine).
        if (trade == StationTask.MINE && ownerId() != null && workZone() != null) TownMine.spent(ownerId(), workZone().center());
        // Look somewhere ELSE. findSite is deterministic and a mined-out patch
        // still looks like perfectly good stone from the surface, so searching
        // the same way returns the same spent ground every time. Turning the
        // bearing and standing further off is what actually moves them on.
        avoidHere = workZone();
        searchBearing++;
        BlockPos site = findSite(trade, radiusFor(trade));
        avoidHere = null;
        if (site == null) {
            // Nowhere else yet. The empty galleries are no less empty for that: the very next
            // one looks again (on the next bearing round). Forgetting them meant three more
            // empty runs before it so much as looked, a good part of a working day, and on
            // ground as scarce as a hill or two the first look often finds nothing: one of t10's
            // miners went the best part of five thousand ticks before it found its first plot.
            if (barren) barrenMineRuns = 2;
            return false;
        }
        WorkZone zone = WorkZone.around(site, radiusFor(trade), depthFor(trade, site));
        setStation(site, trade);
        assignPlot(zone, patchNameFor(trade));
        setAutonomous(true);
        return true;
    }

    /** When this one last CHECKED whether to raise a child. Both clocks start at
     *  the folk's birth, not at "long ago": a hand that has only just been
     *  stood up (or reloaded, which resets nothing that is saved) must not be
     *  asked to raise a child on its first turn — a dozen founders standing at
     *  the heart produced two babies in the first ten seconds. */
    private int breedCheckTick;
    /** When this one last actually DID — the only thing a partner is judged on.
     *  These were one field, and every folk stamped it on its own check every
     *  five minutes, so at any moment every folk in the village had "just
     *  bred" and nobody ever qualified as a partner. No child was ever born. */
    private int breedTick;

    /**
     * How a settlement grows its own people.
     *
     * <p>Everything else about these villages scales with headcount — the
     * watch at eleven, the carrier at twelve, the storekeeper at thirteen, the
     * pen at fourteen — and none of it could ever happen, because a village
     * was founded with eight to twelve folk and had no way on earth to reach
     * thirteen. A settlement could only ever shrink. Every loss was permanent
     * and every specialist trade was a rung nobody would ever stand on.
     *
     * <p>The bar is deliberately low, because a village that has to jump
     * through hoops to grow is a village that does not grow: BE FED, and BE IN
     * WORK. Two folk who are both eating and both doing a job have a chance of
     * raising a child between them, and it costs them the food it takes — so a
     * settlement that cannot feed itself stops growing on its own, without
     * anybody having to write a rule about it. That is the whole check.
     */
    private boolean raisedAChild(double range) {
        if (!com.jrpetty.mcassistant.AssistantConfig.villageBreeding()) return false;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        UUID village = ownerId();
        if (village == null || villageCentre == null) return false;
        if (tickCount - breedCheckTick < TownTraits.breedEvery(village, 6000)) return false;     // five minutes apiece ([identity] Well-wed: sooner)
        breedCheckTick = tickCount;
        if (tickCount - breedTick < 6000) return false;          // not straight after the last

        if (stationTask() == StationTask.NONE) return false;      // in work
        if (countFood() < 2) return false;                        // and fed
        if (Villages.headcount(village)
            >= com.jrpetty.mcassistant.AssistantConfig.villageGrowthCap()) {
            return false;
        }
        // Nobody is born without somewhere to live: see Villages.housing.
        if (Villages.headcount(village) >= Villages.housing(village)) return false;
        // Nor past what the whole world may hold. Colonies stopped at the world's cap but
        // went on raising children: a long game held three hundred folk in six villages
        // by its thirtieth day, at twenty milliseconds a tick.
        if (Villages.worldHeadcount() >= com.jrpetty.mcassistant.AssistantConfig.villageWorldCap()) return false;
        // How the village is doing sets the pace: a thriving village wants less put by and has
        // children readily; a hard-pressed one has them rarely — but still has them, if there is
        // any food put by at all, so a bad spell is never the end of a village.
        int content = Contentment.of(server, village).score();
        int food = Villages.stock(server, villageCentre, Villages.Task.FOOD, Villages.storesRadius(village));
        int want = Villages.larderForBirth(village);
        boolean plenty = food * 5 >= want * (content >= 70 ? 3 : content >= 50 ? 4 : 5);
        boolean lean = !plenty && food * 5 >= Math.max(20, want * 2);
        if (!plenty && !lean) return false;
        // [economy] A full larder is not a fed village: the fields must grow what the town eats, the
        // child's mouth counted, and never while the leader has it on short commons (Larder).
        if (!Larder.oneMore(village, food).yes()) return false;
        if (!Villages.mayBirth(village, level().getGameTime(), lean ? 3 : 1)) return false;
        // Somebody to raise it with, near enough to count as living together,
        // in the same trade-less sense: fed, in work, and not this one. Its
        // partner if it has one about, else the one it is closest to, else (so a
        // village of strangers still grows) whoever is there.
        VillageFolkEntity partner = null;
        int warmest = Integer.MIN_VALUE;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate == this || !(mate instanceof VillageFolkEntity other)) continue;
            if (other.stationTask() == StationTask.NONE) continue;
            if (other.countFood() < 2) continue;
            if (other.tickCount - other.breedTick < 6000) continue;
            if (other.distanceToSqr(this) > range * range) continue;
            // Somebody else's partner is not on offer, and nor is a rival.
            if (other.life.partner() != null && !other.life.partner().equals(getUUID())) continue;
            int warmth = other.getUUID().equals(life.partner()) ? 1000 : life.affinity(other.getUUID());
            if (warmth <= Social.RIVAL) continue;
            if (warmth > warmest) { warmest = warmth; partner = other; }
        }
        if (partner == null) return false;
        if (life.partner() != null && !life.partner().equals(partner.getUUID())) return false;
        // A chance, not a certainty. Two fed hands in work who happen to be
        // stood together, once every five minutes, one time in three: a
        // settlement fills out over a few in-game days rather than doubling
        // overnight.
        int odds = lean ? 8 : content >= 70 ? 2 : content >= 50 ? 3 : 4;
        odds = Dreams.birthOdds(this, partner, odds);          // [individual] a couple who dream of a big family
        if (getRandom().nextInt(odds) != 0) return false;
        return raiseChildWith(partner) != null;
    }

    /**
     * Raise a child with this partner, now: what it costs, who it is, whose it is.
     * The village's rules about when (room, food put by, the pace of births) are
     * {@link #raisedAChild}'s; this is the raising itself. Now and then two come at once (one birth
     * in seventeen or so), more rarely three, four once in a long while ({@link #litter}).
     */
    @Nullable
    public VillageFolkEntity raiseChildWith(VillageFolkEntity partner) {
        return raise(partner);
    }

    /** One child born to this folk and its partner, into the village: named, kitted, knowing whose it is. */
    @Nullable
    private VillageFolkEntity bear(net.minecraft.server.level.ServerLevel server, VillageFolkEntity partner, UUID village, long bornOn) {
        VillageFolkEntity child = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(server);
        if (child == null) return null;
        child.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
        child.rename(Names.freshFor(village, server.getRandom()));
        // Less than its parents spent on it — see childKit. A village that
        // could breed its way to a full larder would never have to farm.
        com.jrpetty.mcassistant.VillageSpawner.childKit(child);
        // BORN INTO THIS VILLAGE, not left to go and look for one: born a step too far out on its
        // parents' plot, it would have founded a rival village on top of them.
        child.joinVillage(village, villageCentre);
        life.hadAChild();
        partner.life.hadAChild();
        child.life.roll(getRandom(), life, partner.life);
        child.life.setParents(displayNameCap(), partner.displayNameCap());
        Individual.born(child, this, partner);                // [individual] its looks from its parents, a life of its own
        child.parentIds.add(getUUID());
        child.parentIds.add(partner.getUUID());
        child.life.feel(getUUID(), displayNameCap(), 70);
        child.life.feel(partner.getUUID(), partner.displayNameCap(), 70);
        life.feel(child.getUUID(), child.displayNameCap(), 70);
        partner.life.feel(child.getUUID(), child.displayNameCap(), 70);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
            getX(), getY() + 2.0, getZ(), 6, 0.6, 0.3, 0.6, 0.0);
        server.addFreshEntity(child);
        Villages.recordBirth(village);
        Annals.born(village);
        child.bornDay = bornOn;
        child.setChild(true);
        persona.remember(bornOn, "my child " + child.displayNameCap() + " was born", 9);
        partner.persona.remember(bornOn, "my child " + child.displayNameCap() + " was born", 9);
        return child;
    }

    /** The odds of one more at a birth: of a second, then of a third given two, then of a fourth given three. */
    private static final double[] ONE_MORE = {0.06, 0.08, 0.05};

    /**
     * How many are born at once: one, mostly; twins about one birth in seventeen; triplets once in
     * two hundred; quadruplets once in four thousand.
     */
    public static int litter(net.minecraft.util.RandomSource r) {
        int n = 1;
        while (n < 4 && r.nextDouble() < ONE_MORE[n - 1]) n++;
        return n;
    }

    private VillageFolkEntity raise(VillageFolkEntity partner) {
        if (isBaby() || partner.isBaby()) return null;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return null;
        UUID village = ownerId();
        if (village == null || villageCentre == null) return null;
        // It costs what it costs. Both parents put the food in, which is what
        // makes a hungry village stop growing by itself.
        if (removeMatching(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 2) < 2) {
            return null;
        }
        partner.removeMatching(
            s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null, 2);
        partner.breedTick = partner.tickCount;
        this.breedTick = tickCount;

        long bornOn = level().getDayTime() / 24000L;
        if (life.partner() == null && partner.life.partner() == null) {
            Villages.tell(village, bornOn, displayNameCap() + " and " + partner.displayNameCap() + " are together now");
        }
        if (life.partner() == null) life.partnerWith(partner.getUUID(), partner.displayNameCap());
        if (partner.life.partner() == null) partner.life.partnerWith(getUUID(), displayNameCap());
        VillageFolkEntity child = bear(server, partner, village, bornOn);
        if (child == null) return null;
        int more = litter(getRandom()) - 1;
        java.util.List<VillageFolkEntity> brood = new java.util.ArrayList<>();
        brood.add(child);
        for (int i = 0; i < more; i++) {
            VillageFolkEntity twin = bear(server, partner, village, bornOn);
            if (twin != null) brood.add(twin);
        }
        Individual.twins(brood);                               // [individual] born together, alike to look at
        if (brood.size() == 1) {
            Villages.tell(village, bornOn, displayNameCap() + " and " + partner.displayNameCap() + " had a child, " + child.displayNameCap());
        } else {
            String word = brood.size() == 2 ? "twins" : brood.size() == 3 ? "triplets" : "quadruplets";
            java.util.List<String> names = new java.util.ArrayList<>();
            for (VillageFolkEntity c : brood) names.add(c.displayNameCap());
            Villages.tell(village, bornOn, displayNameCap() + " and " + partner.displayNameCap() + " had " + word + ": " + String.join(", ", names));
            persona.remember(bornOn, "we had " + word + "!", 10);
            partner.persona.remember(bornOn, "we had " + word + "!", 10);
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), word.substring(0, 1).toUpperCase() + word.substring(1) + "! All at once!", "More than we bargained for — and every one perfect."));
        }
        Villages.noteBirth(village, level().getGameTime());
        // The settlement is bigger than it was, so it keeps more ground awake.
        // Re-taken at the new radius, which is a superset of the old one, so
        // nothing is dropped and nothing is doubled.
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(server, village, villageCentre,
            com.jrpetty.mcassistant.VillageSpawner.loadedRadiusFor(
                Villages.headcount(village)), true);
        // Choosing whichever trade the village is now short of, and finding
        // ground for it, happen on the child's own agenda a moment from now —
        // exactly as they did for its parents.
        return child;
    }


    private int tradeCheckTick = -100000;

    /**
     * A trade is not for life. A settlement decides its shape when its people
     * arrive and then never revisits it — so the day its only smelter falls
     * down a hole, the forge goes cold for good and the village sits at the
     * age that forge was meant to carry it through. There are no newcomers to
     * fill the gap: the ten who founded the place are the ten it has.
     *
     * <p>The rule is deliberately narrow. A hand only re-badges when its OWN
     * trade has more people in it than the village's shape calls for AND some
     * other trade has nobody left at all — so a spare farmer picks up the
     * forge, and a village never strips a working trade to staff another. It
     * finds the ground before it gives up the old plot, so a folk can never
     * end up between trades with nowhere to stand.
     */
    /**
     * Take up another trade now, on new ground for it (a player asked, Asks.retrade). The
     * ground is found before the old plot is given up. Returns whether it changed.
     */
    public boolean takeUpTrade(StationTask want) {
        if (ownerId() == null || villageCentre == null) return false;
        avoidHere = workZone();
        BlockPos site = findSite(want, radiusFor(want));
        avoidHere = null;
        if (site == null) return false;
        setStation(site, want);
        assignPlot(WorkZone.around(site, radiusFor(want), depthFor(want, site)), patchNameFor(want));
        setAutonomous(true);
        tradeCheckTick = tickCount;                       // and not straight back by the village's own sums
        return true;
    }

    /** How many times it has stood aside for somebody better at the trade wanted. */
    private int tradeWaits;

    /**
     * Somebody who could be spared from their own trade and knows the wanted one better than
     * this folk does, or null if this folk is the best hand to send.
     */
    @Nullable
    private VillageFolkEntity betterHandFor(UUID village, StationTask wanted) {
        int mine = tradeLevel(wanted);
        VillageFolkEntity best = null;
        int bestLevel = mine;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == this || f.isBaby() || !f.isAlive()) continue;
            StationTask theirs = f.stationTask();
            if (theirs == wanted || theirs == StationTask.NONE && !f.isAutonomous()) continue;
            if (theirs != StationTask.NONE && !Villages.overStaffed(village, theirs)) continue;
            int lv = f.tradeLevel(wanted);
            if (lv > bestLevel) { bestLevel = lv; best = f; }
        }
        return best;
    }

    private boolean changedTrade() {
        UUID village = ownerId();
        if (village == null) return false;
        if (tickCount - tradeCheckTick < 6000) return false;      // once every five minutes
        tradeCheckTick = tickCount;
        // Only on a full view. The shape is judged from who is LOADED, and in
        // a big village that is whoever is near the player: stand by the
        // farms and the miners are all asleep in unloaded chunks, so "miners:
        // nobody" and "farmers: too many" are both true of the view and
        // false of the village — and farmers would have walked off to mine,
        // and back again from the other side of the valley.
        if (Villages.loadedCount(village) * 5 < Villages.headcount(village) * 4) return false;
        StationTask mine = stationTask();
        StationTask vacancy = Villages.vacancy(village);
        // A trade with nowhere to work at it — a shopkeeper with no shop, a storekeeper with no
        // storehouse — takes up whatever the village is shortest of meanwhile.
        if (!Villages.craftReady(village, mine) && (vacancy == null || vacancy == mine)) vacancy = Villages.needed(village);
        boolean ordered = false;
        if (vacancy == null || vacancy == mine || !Villages.overStaffed(village, mine)) {
            // Or the elder's order: a pair of hands this trade can spare, to the trade it wants.
            vacancy = Orders.move(village, this, level().getDayTime() / 24000L);
            if (vacancy == null || vacancy == mine) return false;
            ordered = true;
        }

        // [individual] Not a trade it is frightened of (Fears): the fishing to one not afraid of deep water.
        if (Fears.shuns(this, vacancy)) {
            brain("leaving the " + vacancy.label + " to somebody braver");
            return false;
        }
        // Who goes: whoever has worked that trade before goes first. A hand that has never
        // smelted waits while one that has — and could be spared — takes the place; after
        // two waits it goes all the same, so the work never goes undone for want of one.
        VillageFolkEntity better = betterHandFor(village, vacancy);
        if (better != null && tradeWaits < 2) {
            tradeWaits++;
            brain("leaving the " + vacancy.label + " to " + better.displayNameCap() + ", who knows it better");
            return false;
        }
        tradeWaits = 0;
        avoidHere = workZone();          // do not simply re-stake my own field
        BlockPos site = findSite(vacancy, radiusFor(vacancy));
        // No ground for the trade shortest of hands (no water within reach for a fisher, no grass for
        // a pen): the next trade well short of hands that has some — the couriers as often as not,
        // whose ground is the storehouse and always to be had. A hand from a trade over its share
        // otherwise stayed where it was, however short the rest were.
        // Under an order, the next trade it wants that has ground: no water near for the fishers,
        // the fields; no game for the hunters, the river.
        if (site == null && ordered) {
            for (StationTask other : Orders.moves(village, this, level().getDayTime() / 24000L)) {
                if (other == vacancy || other == mine) continue;
                BlockPos there = findSite(other, radiusFor(other));
                if (there == null) continue;
                vacancy = other;
                site = there;
                break;
            }
        }
        if (site == null && !ordered && Villages.overStaffed(village, mine)) {
            for (StationTask other : JobWorth.byPay(this, Villages.shortOfHands(village))) {   // [econ-wages] the best paid first
                if (other == vacancy || other == mine) continue;
                BlockPos there = findSite(other, radiusFor(other));
                if (there == null) continue;
                vacancy = other;
                site = there;
                break;
            }
        }
        avoidHere = null;
        if (site == null) return false;
        setStation(site, vacancy);
        assignPlot(WorkZone.around(site, radiusFor(vacancy), depthFor(vacancy, site)),
            patchNameFor(vacancy));
        setAutonomous(true);
        if (!ordered) JobWorth.tookUp(this, mine, vacancy);   // [econ-wages] better pay there: it says so
        if (ordered) {
            long day = level().getDayTime() / 24000L;
            Orders.moved(village, day);                // the day's move, now that it has its ground
            Orders.Order o = Orders.current(village);
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), "The elder wants more hands at the " + vacancy.label + " — off I go!",
                "Orders are orders. " + FolkTalk.cap(vacancy.label) + " it is."));
            Villages.tell(village, day, displayNameCap() + " gave up " + mine.label + " for " + vacancy.label
                + (o == null ? "" : ", as the elder ordered"));
        }
        return true;
    }

    /** How far a ground search reaches out from where it starts. */
    private static final int SCAN = com.jrpetty.mcassistant.village.VillageMath.SCAN;

    private int spentSince;
    private int searchBearing;
    @Nullable private WorkZone avoidHere;

    /**
     * Nobody works every waking hour. After a long stretch a folk knocks off
     * for a minute or two — wanders back toward the middle of the village and
     * stands about — before going back to it. It costs a little output and
     * buys the thing that makes a settlement look inhabited rather than
     * operated: people who are sometimes just there.
     */
    /**
     * Off the clock, and the work brain is told so. The agenda only PLANS
     * work; the station brain inherited from the assistant is what actually
     * swings the hoe, and it runs from the tick regardless — so until this
     * was wired through, a folk on a break carried on working and the break
     * was a thing you could only see in the code.
     */
    @Override
    protected boolean onBreak() {
        // On the road with a caravan, its own work waits until it is home.
        return trip != null || expedition != null || Drover.busy(this) || Stables.busy(this) || Nether.away(this) || JobSeekers.busy(this) || breakNow()
            || Militia.mustering(this);                  // [war-prep] at the militia's muster, its own work waits
    }

    /** The caravan this folk is taking to a colony and back, or null (Caravans). */
    @Nullable private Caravans.Trip trip;

    @Nullable public Caravans.Trip trip() { return trip; }

    public void trip(@Nullable Caravans.Trip t) { this.trip = t; }

    /** The scout's day out on the land, or null (Scouts). */
    @Nullable private Scouts.Expedition expedition;

    @Nullable public Scouts.Expedition expedition() { return expedition; }

    public void expedition(@Nullable Scouts.Expedition e) { this.expedition = e; }

    /**
     * One break a working day, at an hour that is each folk's own. The first
     * version counted ticks since the last break, night included — so every
     * folk's clock had run out by morning and the whole village knocked off
     * together at every dawn, nine hands of nineteen standing about at once.
     * The hour comes from the folk's own id, so it is the same every day and
     * different from its neighbour's.
     */
    private boolean breakNow() {
        // A carrier with a load in hand takes it to the stores first: the break waits. (One went
        // on its break holding the furnace's sixteen ingots and held them for the whole of it.)
        if (stationTask() == StationTask.HAUL && stashable() > 0) return false;
        // [economy] A farmer with a ripe field takes the harvest in first: the break waits (Fields).
        if (Fields.harvestWaits(this)) return false;
        long day = level().getDayTime() % 24000L;
        long bits = getUUID().getLeastSignificantBits();
        // Partners take their break together: both work it out from the same one of
        // their two ids, so it is the same hour for both.
        UUID partner = life.partner();
        if (partner != null && partner.getLeastSignificantBits() < bits) bits = partner.getLeastSignificantBits();
        long start = 1000L + Math.floorMod(bits, 8000L);
        // A town that keeps the bell takes its break together, at the noon bell (TownBell).
        start = TownBell.breakFrom(ownerId(), level(), start);
        long length = 1200L + Math.floorMod(bits >>> 24, 1200L);
        if (life.has(Social.Trait.HARDWORKING)) length /= 2;
        if (life.has(Social.Trait.EASYGOING)) length = length * 3 / 2;
        // A hard-driving leader cuts the breaks short; an easygoing one lets them run on.
        length = (long) (length * Leader.restScale(ownerId()));
        length = (long) (length * FolkSkills.breakScale(this));      // Early Riser: a shorter break
        return day >= start && day < start + length;
    }

    /** Tests: is this its break hour (a test of a trade's pace skips it)? */
    public boolean breakNowForTests() {
        return breakNow();
    }

    private boolean resting() {
        if (!breakNow()) return false;
        // [townlife] Most breaks, a sit down on a bench, a step or a chair near it first (Seats).
        if (level() instanceof net.minecraft.server.level.ServerLevel seatLevel && Seats.onBreak(this, seatLevel)) return true;
        socialise();
        return true;
    }

    /** Is this its break (Seats: it gets up when the break is over)? */
    boolean onItsBreak() {
        return breakNow();
    }

    // ------------------------------ the village's work -----------------------

    /**
     * Once a folk has its own ground in hand, it starts noticing what the
     * settlement itself is missing. One project at a time, one folk at a time,
     * and a long wait between them — this is the part that must never feel
     * like a construction crew descending on a field.
     */
    private void considerVillageWork() {
        UUID village = ownerId();
        if (village == null || villageCentre == null) return;
        if (peekJob() != null || !getNavigation().isDone()) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        long now = level().getGameTime();
        if (!Villages.projectDue(village, now)) { buildNote("build: not due yet"); return; }
        // The first building no other crew is raising (a thriving town has two or three going up at once:
        // Villages.crewsAllowed), or the one this hand's crew is already on.
        String project = Villages.projectFor(village, getUUID());
        if (project == null) { buildNote("build: nothing wanted"); return; }
        // Only a folk standing near the village heart takes the job on — the
        // buildings go up where people live, not wherever the volunteer was.
        if (villageCentre.distSqr(blockPosition()) > 40.0 * 40.0) { buildNote("build: too far from the heart"); return; }
        // The hand raising the village's building is the one that judges it. Every passer-by by
        // the heart used to look at the stores for itself, with an empty pack, after the lead had
        // drawn its load out of them: it found them short by that load, set the building aside
        // for two minutes, and the lead's next look went to whatever stood behind it on the list —
        // a house, raised out of the load drawn for the meeting hall.
        if (Villages.ledByAnother(village, getUUID(), now)) {
            if (drewForBuild) handBackTheBuild();
            buildNote("build: another hand leads");
            return;
        }
        // Ground for it, picked once and kept: a build interrupted at dusk must
        // pick up where it left off, not start again somewhere else.
        Villages.Site site = Villages.siteFor(server, village, project);
        if (site == null) {
            buildNote("build: no lot for the " + project + " (" + Villages.lotReport(village) + ")");
            // Set aside, so the next thing on the list goes up meanwhile (Villages.defer).
            Villages.defer(village, project, now + 6000L, "no lot: " + Villages.lotReport(village));
            Villages.retryShortly(village, now);
            return;
        }
        // No point taking charge of a building the village cannot yet afford —
        // the lead does not lend itself out while it holds the post.
        if (!affordsTimberFor(project, site)) {
            buildNote("build: cannot afford the " + project);
            // Something cheaper further down the list may be affordable now: the smeltery
            // need not wait while the stone for the wall piles up.
            Villages.defer(village, project, now + 2400L, "cannot afford it yet");
            Villages.retrySoon(village, now);
            return;
        }
        // One hand raises a building from first load to last block, so the
        // materials pile up in one pack rather than being scattered.
        if (!Villages.isLead(village, getUUID(), now)) {
            // A lead that lapsed with the building in its pack gives it up to the new one.
            if (drewForBuild) handBackTheBuild();
            buildNote("build: another hand leads");
            return;
        }
        Villages.leadOn(village, getUUID(), project);
        // The builder fetches what it builds with itself: it walks to the stores, loads up
        // there, and carries the load to the site — the storehouse's units, the timber, the
        // stone — and lays it block by block. (Three tries to get there: a store it cannot
        // walk to is loaded from where it stands, rather than never building again.)
        BlockPos stores = storesSpot(server, village);
        if (stores != null && horizontalDistSqr(stores) > 6.0 * 6.0 && toTheStoresTries < 3) {
            toTheStoresTries++;
            getNavigation().moveTo(stores.getX() + 0.5, stores.getY(), stores.getZ() + 0.5, 1.0D);
            buildNote("build: going to the stores for the " + project);
            return;
        }
        toTheStoresTries = 0;
        // Load up FIRST. The builder places real items out of its own pack —
        // no cheating — and nothing was putting them there, so every volunteer
        // walked to the site empty-handed, read out a list of what it still
        // needed and gave up on the spot. No village ever built anything, which
        // means no village ever left the Wood Age either.
        //
        // A look that finds the stores not yet up to it is not a project begun,
        // so it costs two minutes and not the eight that pace real building.
        if (!stockedFor(project, site)) {
            // A part nobody can make (obsidian before anybody has found any), or a making
            // that has not come to anything four visits running: set this one aside a while.
            if (stuckOnAPart) Villages.defer(village, project, now + 2400L, "a part nobody can make yet");
            // Setting about making a chest or a furnace takes a few seconds, so look again in
            // thirty; a wait for stone takes minutes.
            // (Not if the same making has been set in hand four times running: whatever is
            // stopping it will not have changed in thirty seconds.)
            if (madeAFixture && fixtureTries < 4) { madeAFixture = false; Villages.retryIn(village, now, 600L); }
            else { madeAFixture = false; Villages.retrySoon(village, now); }
            return;
        }
        Villages.noteAttempt(village, now);
        buildNote("build: raising the " + project);
        enqueue(Job.buildAt(project, site.anchor(), site.facing(), site.radius()));
    }

    private int toTheStoresTries;

    /** Where a folk stands to take from or put into the village's stores: at the storehouse's
     *  door, or by the heart while there is none. */
    @Nullable
    public BlockPos storesSpot(net.minecraft.server.level.ServerLevel server, UUID village) {
        BlockPos spot = Storehouses.standingSpot(server, village);
        if (spot != null) return spot;
        java.util.List<BlockPos> chests = Villages.storeChests(server, village);
        return chests.isEmpty() ? villageCentre : chests.get(0);
    }

    private double horizontalDistSqr(BlockPos p) {
        double dx = getX() - (p.getX() + 0.5), dz = getZ() - (p.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private static final org.slf4j.Logger BUILD_LOG = com.mojang.logging.LogUtils.getLogger();
    private static final java.util.Map<UUID, String> LAST_BUILD_NOTE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Why the village is not building (or that it is), in the server log: a line
     *  whenever the answer changes. A village that builds one house a game day
     *  has a reason, and it is different every time. */
    private void buildNote(String note) {
        brain(note);
        UUID village = ownerId();
        if (village == null) return;
        // Only the answers that say something: "not due yet" and "too far from the
        // heart" are what every idle hand thinks every couple of seconds.
        if (!(note.contains("afford") || note.contains("stores hold") || note.contains("making")
                || note.contains("cannot make") || note.contains("carrying")
                || note.contains("raising") || note.contains("no lot") || note.contains("handed"))) {
            return;
        }
        String key = note.replaceAll("[0-9]+", "#");
        if (key.equals(LAST_BUILD_NOTE.get(village))) return;
        LAST_BUILD_NOTE.put(village, key);
        BUILD_LOG.info("[MCA-BUILD] tick {}: {} — {}", level().getGameTime(), assistantNameForLog(), note);
    }

    private String assistantNameForLog() { return getName().getString(); }

    /** Does this hand hold the village's building lead right now? Such a hand
     *  does not go off lending itself out with a pack full of a building. */
    private boolean holdsBuildLead() {
        UUID village = ownerId();
        return village != null && Villages.holdsTheLead(village, getUUID(), level().getGameTime());
    }

    /** Enough timber and stone in the pack and the stores for the shell alone? */
    private boolean affordsTimberFor(String project, Villages.Site site) {
        BlockPos heart = villageCentre;
        if (heart == null) return false;
        int blocks = blocksToLay(project, site, stillToLay(project, site));
        int carried = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        // Three parts in four is enough to begin: the rest is dug while the walls go up, and
        // a build that waited for every last block stood in front of its list for days.
        int least = blocks * 3 / 4;
        return carried >= least
            || carried + storesHold(heart, buildStoresRadius(), BuildGoal::isBuildingBlock) >= least;
    }

    /**
     * The blocks a building still wants: the cells of its drawing not yet laid, a tenth more for
     * the cells that are lost, and the ground to build up to its floor on a hillside.
     *
     * <p>Counted from the ground as it stands, when the ground is loaded: a building a load short
     * of done (BuildGoal stops when the pack runs out, and the next run picks up where it left
     * off) used to be asked for its whole drawing again before the builder would go back to it —
     * a meeting hall with its walls and its terrace up would still have wanted its six hundred
     * blocks, and the terrace again, before it was taken up. The wall is the exception: it
     * follows the ground as it is laid (BuildGoal), so its drawing is what it costs.
     */
    private int blocksToLay(String project, Villages.Site site, @Nullable java.util.Map<BuildGoal.Part, Integer> still) {
        int drawn = still != null && !project.equals("fortify")
            ? still.getOrDefault(BuildGoal.Part.BLOCK, 0)
            : BuildGoal.partCounts(project, site.radius()).getOrDefault(BuildGoal.Part.BLOCK, 0);
        int blocks = drawn + drawn / 10 + 2;                    // a margin for the cells that are lost
        if (!project.equals("fortify")) {                       // and the ground to build up
            int[] g = BuildGoal.footprint(project);
            blocks += BuildGoal.fillCells(level(), site.anchor(), site.facing(), g[0], g[1]).size();
        }
        return blocks;
    }

    /**
     * What of a building's drawing is still open ground, part by part — or null when its ground is
     * not all loaded, in which case nothing is read (a block read off an unloaded chunk would make
     * the server load it, on this thread, while everything waited).
     */
    @Nullable
    private java.util.Map<BuildGoal.Part, Integer> stillToLay(String project, Villages.Site site) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return null;
        int[] spread = com.jrpetty.mcassistant.entity.goal.Blueprints.has(project)
            ? com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(project)
            : new int[]{ BuildGoal.halfOf(project), BuildGoal.halfOf(project) };
        int reach = Math.max(Math.max(8, site.radius()), Math.max(spread[0], spread[1]) + 1);
        if (!Land.areaLoaded(server, site.anchor(), reach)) return null;
        java.util.Map<BuildGoal.Part, Integer> still = new java.util.EnumMap<>(BuildGoal.Part.class);
        for (BuildGoal.Placement p : BuildGoal.plan(project, site.anchor(), site.facing(), site.radius())) {
            if (BuildGoal.soft(server.getBlockState(p.pos()))) still.merge(p.part(), 1, Integer::sum);
        }
        return still;
    }

    /** How far from the heart the builder reads and draws on the stores: as far
     *  as the village's own plan counts them, not the forty-eight blocks round
     *  the storehouse — the woodpile is in the woods and the stone is at the mine,
     *  and a plan that counted them while the builder could not reach them left
     *  the village "short of timber" beside a full chest for days.
     *
     *  <p>It was held to a hundred and twelve when the plan's own count stopped at the
     *  village's core; the plan has since counted as far as its plots reach (Villages.storesRadius:
     *  two hundred and twenty for a town of sixty-six), and the builders, still at a hundred and
     *  twelve, saw too little of what the plan said was there to pay for a meeting hall (see
     *  Villages.STORES_TALL). Now they see what the plan sees, as a
     *  folk trading with a player, the drover and a hired hand drawing on the stores already did. */
    private int buildStoresRadius() {
        UUID village = ownerId();
        return Math.max(48, village == null ? 48 : Villages.storesRadius(village));
    }

    /** How much of this the village's stores hold near its heart. */
    private int storesHold(BlockPos heart, java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return storesHold(heart, 48, what);
    }

    /** How much of this the stores within {@code radius} of the heart hold, as high and as deep as
     *  the village's own count of them reaches (Villages.STORES_TALL). */
    private int storesHold(BlockPos heart, int radius,
                           java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return ZoneChests.countIn(
            ZoneChests.around(level(), heart, radius, Villages.STORES_TALL).stream()
                .filter(f -> f.stillThere() && ZoneChests.isStashable(f)).toList(),
            what);
    }

    /**
     * Fill the pack from the village stores, and make up whatever the
     * blueprint is short of. Returns false when the settlement genuinely does
     * not have the materials yet — nothing is drawn in that case, so a village
     * that cannot afford a building does not strip its own stores to find out.
     *
     * <p>Counted from the blueprint itself (BuildGoal.partCounts), so what is
     * fetched is exactly what the builder will place.
     */
    private boolean stockedFor(String project, Villages.Site site) {
        stuckOnAPart = false;
        BlockPos heart = villageCentre;
        UUID village = ownerId();
        if (heart == null || village == null) return false;
        long now = level().getGameTime();
        java.util.Map<BuildGoal.Part, Integer> need =
            BuildGoal.partCounts(project, site.radius());
        // A building taken up again (the last run ran out of blocks) needs only the fixtures
        // its empty cells want: counted from the drawing alone, the storage's second run made
        // twenty-seven more storehouse units out of the village's planks for a cube already
        // standing, and carried them about for ever. And a village with its storehouse lays
        // no second one (BuildGoal drops those cells).
        java.util.Map<BuildGoal.Part, Integer> still = stillToLay(project, site);
        if (Storehouses.stands(village)) need.remove(BuildGoal.Part.STOREHOUSE);
        int blocks = blocksToLay(project, site, still);
        int least = blocks * 3 / 4;                             // enough to begin with: see affordsTimberFor

        // Timber and stone: only worth a trip if the village has enough. (The roof's stairs and
        // slabs in the pack count: they are laid in place of blocks.)
        int carried = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        if (carried < least) {
            int inStores = storesHold(heart, buildStoresRadius(), BuildGoal::isBuildingBlock);
            if (carried + inStores < least) { buildNote("build: stores hold " + inStores + ", need " + (least - carried)); return false; }      // not yet
        }

        // The fixtures — a chest, a furnace, a bench, ladders, fences — from the
        // stores if they are there, made if they are not. One craft a visit.
        // They come first, before the drawing's timber and stone: there is one of each and no
        // making do without it, and a big building's stairs, planks, beams and footing, a stack
        // or three of each and of more than one wood, can fill a pack before its chest is in it —
        // and a building that "cannot make" its chest is set aside as one nobody can build.
        for (Fixture fx : FIXTURES) {
            int want = need.getOrDefault(fx.part(), 0);
            if (still != null) want = Math.min(want, still.getOrDefault(fx.part(), 0));
            if (want == 0) continue;
            var item = BuildGoal.itemForPart(fx.part());
            int have = countCarried(item);
            if (have < want) {
                int got = drawFrom(heart, item, want - have, buildStoresRadius());
                have += got;
                if (got > 0) { Villages.leadProgress(village, getUUID(), now); drewForBuild = true; }
            }
            if (have < want) {
                // Made on the spot from what the stores hold, the way the tools are: the
                // crafting planner only looks in chests twenty-four blocks round the builder,
                // and a town's stores are spread over a hundred and more — a long game sat in
                // the Stone Age for thirteen days with three thousand stone and seventeen
                // hundred logs in its chests, "cannot make 1 furnace", and no smeltery.
                int made = madeFromStores(fx.part(), want - have);
                if (made > 0) { have += made; drewForBuild = true; Villages.leadProgress(village, getUUID(), now); }
            }
            if (have < want) {
                boolean making = fx.recipe() != null && craftNow(fx.recipe(), want - have);
                String what = fx.recipe() != null ? fx.recipe() : fx.part().name().toLowerCase();
                buildNote("build: " + (making ? "making " : "cannot make ") + (want - have) + " " + what);
                if (making) { Villages.leadProgress(village, getUUID(), now); madeAFixture = true; drewForBuild = true; }
                String ask = what + " x" + (want - have);
                fixtureTries = ask.equals(lastFixtureAsk) ? fixtureTries + 1 : 0;
                lastFixtureAsk = ask;
                stuckOnAPart = !making || fixtureTries >= 4;
                return false;                                    // made, or cannot be: either way, not this visit
            }
        }
        // The right things for a drawn building next: stone for its footing, planks for its
        // walls, logs for its frame, and the stairs and slabs of its roof cut from the planks.
        int shaped = stockStyles(project, heart);
        if (shaped > 0) { Villages.leadProgress(village, getUUID(), now); drewForBuild = true; }

        // Decorations are taken if the stores have them, never waited for.
        for (var deco : java.util.List.of(
                BuildGoal.Part.TORCH,
                BuildGoal.Part.WINDOW,
                BuildGoal.Part.BED,
                BuildGoal.Part.DOOR,
                BuildGoal.Part.LANTERN,
                BuildGoal.Part.HAY,
                BuildGoal.Part.BARREL,
                BuildGoal.Part.FLOWER,
                BuildGoal.Part.CARPET,
                BuildGoal.Part.ANVIL,
                BuildGoal.Part.CAULDRON,
                BuildGoal.Part.BELL,
                BuildGoal.Part.WATER,
                BuildGoal.Part.BOOKSHELF,
                BuildGoal.Part.LECTERN,
                BuildGoal.Part.ENCHANTING,
                BuildGoal.Part.BREWING,
                BuildGoal.Part.SMOKER,
                BuildGoal.Part.LOOM,
                BuildGoal.Part.GRINDSTONE,
                BuildGoal.Part.CAMPFIRE,
                BuildGoal.Part.NOTE_BLOCK,
                BuildGoal.Part.CARTOGRAPHY)) {                           // [cartographer] the map room's table
            int want = need.getOrDefault(deco, 0);
            if (want == 0) continue;
            var item = BuildGoal.itemForPart(deco);
            int have = countCarried(item);
            if (have < want) have += drawFrom(heart, item, want - have, buildStoresRadius());
            // The finishing that is quickly made: doors and barrels from planks, panes from
            // glass, hay from the wheat.
            if (have < want) have += finishing(deco, want - have);
            // Beds are made, not only found: three wool and three planks from the stores.
            if (deco == BuildGoal.Part.BED && have < want) have += makeBeds(want - have);
            // Without the wool, the founders' bedding comes in from the camp: but a bed at a
            // time, as each is laid (BuildGoal, bedFromTheCamp), not all of them up front.
        }
        // A lantern the village has not got (a lantern is iron: Masonry) is a torch on the day: the
        // drawing's lantern cells are lit with the torches drawn for them.
        int lanterns = need.getOrDefault(BuildGoal.Part.LANTERN, 0);
        if (lanterns > 0) {
            int dark = Math.max(0, lanterns - countCarried(BuildGoal.itemForPart(BuildGoal.Part.LANTERN)));
            if (dark > 0) {
                topUp(heart, st -> st.is(net.minecraft.world.item.Items.TORCH),
                    need.getOrDefault(BuildGoal.Part.TORCH, 0) + dark, buildStoresRadius());
            }
        }
        // Then the bulk of it, timber and stone, into whatever room the pack has left once the
        // fixtures and the finishing are in it (see above).
        int carriedNow = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        if (carriedNow < blocks) {
            // The cheapest first: stone before planks, planks before logs.
            int got = 0;
            for (int tier = 0; tier <= 2 && got < blocks - carriedNow; tier++) {
                final int cost = tier;
                got += drawFrom(heart, st -> BuildGoal.isBuildingBlock(st) && BuildGoal.blockCost(st) == cost,
                    blocks - carriedNow - got, buildStoresRadius());
            }
            if (got > 0) { Villages.leadProgress(village, getUUID(), now); drewForBuild = true; }
        }
        // The roof's stairs and slabs count: they are cut from the planks and laid in place of
        // blocks. Counted without them, a builder that had cut its roof out of the founding planks
        // was always "carrying 128 of 216", never began, and a village of twelve built nothing.
        int blocksNow = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        // A full pack is a load to begin on. Three parts in four of a building bigger than one pack
        // can hold never fit in it: the meeting hall's drawing alone (five hundred and forty-odd
        // blocks, its roof stairs, planks, beams and footing each a stack or three of their own, its
        // windows, chests and bench) fills most of a pack's twenty-seven slots, and on a mountainside
        // its terrace wants hundreds more under the floor (up to eight a column, a hundred and
        // fifty-three columns) — the builder would load up, come short of three in four, and look
        // again in two minutes, for ever. It goes up a pack at a time instead: BuildGoal lays what
        // the pack holds, and the next load picks up where that one ran out (blocksToLay).
        boolean fullLoad = packFull() && countCarried(BuildGoal::isBuildingBlock) >= FULL_LOAD;
        if (blocksNow < least && !fullLoad) buildNote("build: carrying " + blocksNow + " of " + blocks + " blocks");
        return blocksNow >= least || fullLoad;
    }

    /** The least of timber and stone a full pack must hold to count as a load to begin on (six
     *  stacks): a pack full of its trade's own goods with a handful of planks in it is no load. */
    static final int FULL_LOAD = 6 * 64;

    /** No empty slot left in the pack. */
    private boolean packFull() {
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) if (st.isEmpty()) return false;
        return true;
    }

    /** Tests: would this hand say the village can afford this building on this ground? */
    public boolean affordsForTests(String project, Villages.Site site) {
        return affordsTimberFor(project, site);
    }

    /** Tests: stock up for this building as its lead would; true if it would set off to build. */
    public boolean stockedForTests(String project, Villages.Site site) {
        return stockedFor(project, site);
    }

    /** Tests: the village's work looked at now (considerVillageWork), as this hand would standing idle where it
     *  is: the next project, its lot, whether the stores can pay for it, stocking up, and setting off. True if
     *  it set off to build. (What it had in hand of its own trade is put down first, as an idle hand has none.) */
    public boolean villageWorkForTests() {
        clearQueue();
        getNavigation().stop();
        considerVillageWork();
        Job j = peekJob();
        return j != null && j.type() == Job.Type.BUILD;
    }

    /** Tests: the blocks this building still wants laid on this ground (blocksToLay). */
    public int blocksToLayForTests(String project, Villages.Site site) {
        return blocksToLay(project, site, stillToLay(project, site));
    }

    /** Tests: how much of this the stores hold, as far and as high as the builders look. */
    public int buildersSeeForTests(java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return villageCentre == null ? 0 : storesHold(villageCentre, buildStoresRadius(), what);
    }

    /** Roof stairs and slabs in the pack, which stand in for blocks of the roof. */
    private int roofPiecesCarried() {
        return countCarried(st -> (st.is(net.minecraft.tags.ItemTags.STAIRS) || st.is(net.minecraft.tags.ItemTags.SLABS))
            && !BuildGoal.isBuildingBlock(st));
    }

    // ------------------------------ the right materials for a drawn building ----------------

    /**
     * Stock what a drawn building's blocks are for: stone for the footing and the stone
     * walls, planks for the boards, logs for the frame, and the roof's stairs and slabs,
     * cut on the spot from planks out of the stores (the village's own wood: oak roofs in
     * oak country, spruce in the taiga). Best effort: whatever it cannot have, the builder
     * makes do with plain blocks. Returns how many stairs and slabs it now carries, which
     * are not building blocks but stand in for them.
     */
    private int stockStyles(String project, BlockPos heart) {
        java.util.Map<com.jrpetty.mcassistant.entity.goal.Blueprints.Style, Integer> want = BuildGoal.styleCounts(project);
        if (want.isEmpty()) return 0;
        int r = buildStoresRadius();
        java.util.function.ToIntFunction<com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]> sum = styles -> {
            int n = 0;
            for (var st : styles) n += want.getOrDefault(st, 0);
            return n;
        };
        int stone = sum.applyAsInt(new com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]{
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.FOUNDATION, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL_LOW,
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.MASONRY, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.BRICK});
        int boards = sum.applyAsInt(new com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]{
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.FLOOR, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL,
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_BLOCK});
        int logs = sum.applyAsInt(new com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]{
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.POST, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.BEAM_ACROSS,
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.BEAM_ALONG});
        int stairs = sum.applyAsInt(new com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]{
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_STAIR, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_STAIR_TOP});
        int slabs = sum.applyAsInt(new com.jrpetty.mcassistant.entity.goal.Blueprints.Style[]{
            com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_SLAB, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_SLAB_TOP});
        int stoneSlabs = want.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.STONE_SLAB, 0);
        int stoneStairs = want.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.STONE_STAIR, 0);
        // Dressed stone for the drawing's dressed stone, and brick for its chimney, when the stores have
        // them (the smelter's work: Masonry); a block of brick made up of four fired bricks if need be.
        // Short of either, rough stone does instead: never a stone brick that nobody cut.
        int masonry = want.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.MASONRY, 0);
        int brick = want.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.BRICK, 0);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> brickBlocks = st -> st.is(net.minecraft.world.item.Items.BRICKS);
        topUp(heart, brickBlocks, brick, r);
        if (countCarried(brickBlocks) < brick && level() instanceof net.minecraft.server.level.ServerLevel server
                && ownerId() != null && Villages.get(ownerId()) != null) {
            Villages.Village vill = Villages.get(ownerId());
            int more = Math.min(brick - countCarried(brickBlocks),
                Market.stock(server, ownerId(), st -> st.is(net.minecraft.world.item.Items.BRICK)) / 4);
            if (more > 0 && Masonry.take(server, vill, net.minecraft.world.item.Items.BRICKS, more)) {
                net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BRICKS, more));
                if (!left.isEmpty()) Crafts.store(server, vill, left);
            }
        }
        int bricksHeld = countCarried(brickBlocks);
        // The village's own stone and timber first (Palettes), then whatever else will do.
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> dressed = BuildGoal.preferred(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.MASONRY);
        topUpRanked(heart, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.MASONRY, dressed, masonry + Math.max(0, brick - bricksHeld), r);
        topUp(heart, dressed, masonry + Math.max(0, brick - bricksHeld), r);
        topUpRanked(heart, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.FOUNDATION, BuildGoal::isStoneLike, Math.max(0, stone - bricksHeld), r);
        topUp(heart, BuildGoal::isStoneLike, Math.max(0, stone - bricksHeld), r);
        topUpRanked(heart, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.POST, st -> st.is(net.minecraft.tags.ItemTags.LOGS), logs, r);
        topUp(heart, st -> st.is(net.minecraft.tags.ItemTags.LOGS), logs, r);
        topUpRanked(heart, com.jrpetty.mcassistant.entity.goal.Blueprints.Style.WALL, st -> st.is(net.minecraft.tags.ItemTags.PLANKS), boards, r);
        // The roof first, out of the planks: it is the thing that makes a building look like one.
        // [workitems] A town roofing in thatch: the roof's thatch first (Thatch), and it stands for the wooden pieces.
        Thatch.stock(this, heart, stairs, slabs, want.getOrDefault(com.jrpetty.mcassistant.entity.goal.Blueprints.Style.ROOF_BLOCK, 0), r);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> woodStairs = st -> st.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS)
            || st.is(com.jrpetty.mcassistant.item.WorkItems.THATCH_STAIRS_ITEM.get());
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> woodSlabs = st -> st.is(net.minecraft.tags.ItemTags.WOODEN_SLABS)
            || st.is(com.jrpetty.mcassistant.item.WorkItems.THATCH_SLAB_ITEM.get());
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> rockSlabs =
            st -> st.is(net.minecraft.tags.ItemTags.SLABS) && !st.is(net.minecraft.tags.ItemTags.WOODEN_SLABS);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> rockStairs =
            st -> st.is(net.minecraft.tags.ItemTags.STAIRS) && !st.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS);
        topUp(heart, woodStairs, stairs, r);
        if (countCarried(woodStairs) < stairs) cutFromPlanks("_stairs", 6, 4, stairs - countCarried(woodStairs), heart, r);
        topUp(heart, woodSlabs, slabs, r);
        if (countCarried(woodSlabs) < slabs) cutFromPlanks("_slab", 3, 6, slabs - countCarried(woodSlabs), heart, r);
        topUp(heart, rockSlabs, stoneSlabs, r);
        if (countCarried(rockSlabs) < stoneSlabs) cutFromStone(net.minecraft.world.item.Items.COBBLESTONE_SLAB, 3, 6,
            stoneSlabs - countCarried(rockSlabs), heart, r);
        topUp(heart, rockStairs, stoneStairs, r);
        if (countCarried(rockStairs) < stoneStairs) cutFromStone(net.minecraft.world.item.Items.COBBLESTONE_STAIRS, 6, 4,
            stoneStairs - countCarried(rockStairs), heart, r);
        planksInHand(boards, heart, r);
        return countCarried(woodStairs) + countCarried(woodSlabs) + countCarried(rockSlabs) + countCarried(rockStairs);
    }

    /** As topUp, the village's own kinds for this part first, one kind after another (Palettes). */
    private void topUpRanked(BlockPos heart, com.jrpetty.mcassistant.entity.goal.Blueprints.Style style,
                             java.util.function.Predicate<net.minecraft.world.item.ItemStack> group, int want, int r) {
        java.util.List<net.minecraft.world.item.Item> ranked = com.jrpetty.mcassistant.entity.Palettes.ranked(ownerId(), style);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> any = group.or(st -> ranked.contains(st.getItem()));
        for (net.minecraft.world.item.Item it : ranked) {
            int have = countCarried(any);
            if (have >= want) return;
            drawFrom(heart, st -> st.is(it), want - have, r);
        }
    }

    /** Have at least this many of a thing in the pack, out of the stores if they have it. */
    private void topUp(BlockPos heart, java.util.function.Predicate<net.minecraft.world.item.ItemStack> what, int want, int r) {
        int have = countCarried(what);
        if (have < want) drawFrom(heart, what, want - have, r);
    }

    /** Planks in the pack: from the stores, or sawn from logs (four a log, of the log's own wood). */
    private boolean planksInHand(int want, BlockPos heart, int r) {
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> planks = st -> st.is(net.minecraft.tags.ItemTags.PLANKS);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> logs = st -> st.is(net.minecraft.tags.ItemTags.LOGS);
        if (countCarried(planks) >= want) return true;
        drawFrom(heart, planks, want - countCarried(planks), r);
        if (countCarried(planks) >= want) return true;
        int short_ = want - countCarried(planks);
        int needLogs = (short_ + 3) / 4;
        if (countCarried(logs) < needLogs) drawFrom(heart, logs, needLogs - countCarried(logs), r);
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
            if (short_ <= 0) break;
            if (!logs.test(st)) continue;
            net.minecraft.world.item.Item board = woodOf(st, "_planks");
            while (short_ > 0 && !st.isEmpty()) {
                st.shrink(1);
                net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(board, 4));
                if (!left.isEmpty()) spawnAtLocation(left);
                short_ -= 4;
            }
        }
        return countCarried(planks) >= want;
    }

    /** "oak_planks" + "_stairs" is "oak_stairs"; a log's wood likewise. Oak where there is no such thing. */
    private static net.minecraft.world.item.Item woodOf(net.minecraft.world.item.ItemStack from, String suffix) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(from.getItem()).getPath();
        String wood = path.replace("stripped_", "").replace("_planks", "").replace("_log", "").replace("_wood", "")
            .replace("_stem", "").replace("_hyphae", "").replace("_block", "");
        net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(wood + suffix));
        if (it == net.minecraft.world.item.Items.AIR) {
            it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak" + suffix));
        }
        return it;
    }

    /** Cut so many of a wooden thing (stairs, slabs, a door) from planks, the planks' own wood. */
    private int cutFromPlanks(String suffix, int planksPer, int makesPer, int wanted, BlockPos heart, int r) {
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> planks = st -> st.is(net.minecraft.tags.ItemTags.PLANKS);
        int made = 0;
        int batches = (wanted + makesPer - 1) / makesPer;
        for (int i = 0; i < batches; i++) {
            if (!planksInHand(planksPer, heart, r)) break;
            // The wood there is most of, so a roof is one colour.
            net.minecraft.world.item.ItemStack most = net.minecraft.world.item.ItemStack.EMPTY;
            for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
                if (planks.test(st) && st.getCount() >= planksPer && st.getCount() > most.getCount()) most = st;
            }
            if (most.isEmpty()) break;
            net.minecraft.world.item.Item product = woodOf(most, suffix);
            most.shrink(planksPer);
            net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(product, makesPer));
            if (!left.isEmpty()) spawnAtLocation(left);
            made += makesPer;
        }
        if (made > 0) brain("cut " + made + " " + suffix.substring(1) + " from planks");
        return made;
    }

    /** Cut stone slabs or steps from cobblestone. */
    private int cutFromStone(net.minecraft.world.item.Item product, int stonePer, int makesPer, int wanted, BlockPos heart, int r) {
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> cobble = st -> st.is(net.minecraft.world.item.Items.COBBLESTONE);
        int made = 0;
        while (made < wanted) {
            if (countCarried(cobble) < stonePer) drawFrom(heart, cobble, stonePer - countCarried(cobble), r);
            if (countCarried(cobble) < stonePer) break;
            removeMatching(cobble, stonePer);
            net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(product, makesPer));
            if (!left.isEmpty()) spawnAtLocation(left);
            made += makesPer;
        }
        return made;
    }

    /** Doors and barrels from planks, panes from glass, hay from wheat: the finishing made on the spot. */
    private int finishing(BuildGoal.Part part, int wanted) {
        if (villageCentre == null || wanted <= 0) return 0;
        int r = buildStoresRadius();
        BlockPos heart = villageCentre;
        switch (part) {
            case DOOR -> { return cutFromPlanks("_door", 6, 3, wanted, heart, r); }
            case BARREL -> {
                int made = 0;
                for (int i = 0; i < wanted && planksInHand(7, heart, r); i++) {
                    removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PLANKS), 7);
                    insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARREL));
                    made++;
                }
                return made;
            }
            case WINDOW -> {
                java.util.function.Predicate<net.minecraft.world.item.ItemStack> glass = st -> st.is(net.minecraft.world.item.Items.GLASS);
                int made = 0;
                while (made < wanted) {
                    if (countCarried(glass) < 6) drawFrom(heart, glass, 6 - countCarried(glass), r);
                    if (countCarried(glass) < 6) break;
                    removeMatching(glass, 6);
                    insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_PANE, 16));
                    made += 16;
                }
                return Math.min(made, wanted);
            }
            case WATER -> {
                // Water for a well or a fountain: a bucket (the smith's, out of the stores) filled at the
                // nearest water to the heart. The water is the world's; only the bucket is made.
                java.util.function.Predicate<net.minecraft.world.item.ItemStack> bucket = st -> st.is(net.minecraft.world.item.Items.BUCKET);
                BlockPos water = null;
                int made = 0;
                while (made < wanted) {
                    if (countCarried(bucket) < 1) drawFrom(heart, bucket, 1, r);
                    if (countCarried(bucket) < 1) break;
                    if (water == null) water = waterNear(heart, 32);
                    if (water == null) break;
                    if (removeMatching(bucket, 1) < 1) break;
                    net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
                    if (!left.isEmpty()) { spawnAtLocation(left); break; }
                    made++;
                }
                if (made > 0) brain("filled " + made + (made == 1 ? " bucket" : " buckets") + " at the water for the well");
                return made;
            }
            case HAY -> {
                java.util.function.Predicate<net.minecraft.world.item.ItemStack> wheat = st -> st.is(net.minecraft.world.item.Items.WHEAT);
                int made = 0;
                while (made < wanted) {
                    if (countCarried(wheat) < 9) drawFrom(heart, wheat, 9 - countCarried(wheat), r);
                    if (countCarried(wheat) < 9) break;
                    removeMatching(wheat, 9);
                    insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HAY_BLOCK));
                    made++;
                }
                return made;
            }
            // The crafts' furniture, made from what the stores hold if they hold the makings.
            case SMOKER -> { return makeFromStores(net.minecraft.world.item.Items.SMOKER, wanted, heart, r, 0,
                java.util.Map.entry(COBBLE, 8), java.util.Map.entry(LOGS, 4)); }
            case LOOM -> { return makeFromStores(net.minecraft.world.item.Items.LOOM, wanted, heart, r, 2,
                java.util.Map.entry(STRING, 2)); }
            // A grindstone's middle is a slab of smelted stone, not cobble (the smelter's: Masonry).
            case GRINDSTONE -> { return makeFromStores(net.minecraft.world.item.Items.GRINDSTONE, wanted, heart, r, 3,
                java.util.Map.entry(st -> st.is(net.minecraft.world.item.Items.STONE), 1)); }
            case BOOKSHELF -> { return makeFromStores(net.minecraft.world.item.Items.BOOKSHELF, wanted, heart, r, 6,
                java.util.Map.entry(BOOKS, 3)); }
            case LECTERN -> { return makeFromStores(net.minecraft.world.item.Items.LECTERN, wanted, heart, r, 8,
                java.util.Map.entry(BOOKS, 3)); }
            case BREWING -> { return makeFromStores(net.minecraft.world.item.Items.BREWING_STAND, wanted, heart, r, 0,
                java.util.Map.entry(BLAZE_RODS, 1), java.util.Map.entry(COBBLE, 3)); }
            case CAMPFIRE -> { return makeFromStores(net.minecraft.world.item.Items.CAMPFIRE, wanted, heart, r, 2,
                java.util.Map.entry(LOGS, 3), java.util.Map.entry(COAL, 1)); }
            case NOTE_BLOCK -> { return makeFromStores(net.minecraft.world.item.Items.NOTE_BLOCK, wanted, heart, r, 8,
                java.util.Map.entry(REDSTONE, 1)); }
            // [cartographer] The map room's cartography table: two of the stores' paper on four planks, as the game makes one.
            case CARTOGRAPHY -> { return makeFromStores(net.minecraft.world.item.Items.CARTOGRAPHY_TABLE, wanted, heart, r, 4,
                java.util.Map.entry(st -> st.is(net.minecraft.world.item.Items.PAPER), 2)); }
            case ENCHANTING -> { return makeFromStores(net.minecraft.world.item.Items.ENCHANTING_TABLE, wanted, heart, r, 0,
                java.util.Map.entry(BOOKS, 1), java.util.Map.entry(DIAMONDS, 2), java.util.Map.entry(OBSIDIAN, 4)); }
            default -> { return 0; }
        }
    }

    /** A block of still water near here, open to the sky (a pond, a river, the sea), or null. */
    @Nullable
    private BlockPos waterNear(BlockPos near, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int x = near.getX() + dx, z = near.getZ() + dz;
                if (!level().hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = new BlockPos(x, level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
                if (!level().getFluidState(top).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) continue;
                double d = top.distSqr(near);
                if (d < bd) { bd = d; best = top; }
            }
        }
        return best;
    }

    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> COBBLE =
        st -> st.is(net.minecraft.world.item.Items.COBBLESTONE);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> LOGS =
        st -> st.is(net.minecraft.tags.ItemTags.LOGS);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> STRING =
        st -> st.is(net.minecraft.world.item.Items.STRING);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> BOOKS =
        st -> st.is(net.minecraft.world.item.Items.BOOK);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> BLAZE_RODS =
        st -> st.is(net.minecraft.world.item.Items.BLAZE_ROD);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> DIAMONDS =
        st -> st.is(net.minecraft.world.item.Items.DIAMOND);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> COAL =
        st -> st.is(net.minecraft.world.item.Items.COAL) || st.is(net.minecraft.world.item.Items.CHARCOAL);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> REDSTONE =
        st -> st.is(net.minecraft.world.item.Items.REDSTONE);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> OBSIDIAN =
        st -> st.is(net.minecraft.world.item.Items.OBSIDIAN);

    /** So many of a thing made from planks and other makings out of the stores: all the makings
     *  for one, or none of it. Returns how many it made. */
    @SafeVarargs
    private int makeFromStores(net.minecraft.world.item.Item product, int wanted, BlockPos heart, int r, int planks,
                               java.util.Map.Entry<java.util.function.Predicate<net.minecraft.world.item.ItemStack>, Integer>... makings) {
        int made = 0;
        while (made < wanted) {
            boolean all = planks <= 0 || planksInHand(planks, heart, r);
            for (var m : makings) {
                if (!all) break;
                int have = countCarried(m.getKey());
                if (have < m.getValue()) drawFrom(heart, m.getKey(), m.getValue() - have, r);
                all = countCarried(m.getKey()) >= m.getValue();
            }
            if (!all) break;
            if (planks > 0) removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PLANKS), planks);
            for (var m : makings) removeMatching(m.getKey(), m.getValue());
            net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(product));
            if (!left.isEmpty()) spawnAtLocation(left);
            made++;
        }
        if (made > 0) brain("made " + made + " " + product.getDescription().getString().toLowerCase());
        return made;
    }

    /**
     * The camp round the heart is where the founders slept before there were houses. A builder
     * laying a house's bed with no wool to make one takes one up from the camp and carries it in:
     * the camp empties into the houses a bed at a time (VillageSpawner.liftCampBed).
     */
    @Override
    public boolean bedFromTheCamp() {
        if (villageCentre == null || ownerId() == null) return false;
        net.minecraft.world.level.block.Block bed = com.jrpetty.mcassistant.VillageSpawner.liftCampBed(
            level(), villageCentre, Villages.bedsClaimed(ownerId()));
        if (bed == null) return false;
        net.minecraft.world.item.ItemStack left = insertItem(new net.minecraft.world.item.ItemStack(bed.asItem()));
        if (!left.isEmpty()) spawnAtLocation(left);
        brain("carried a bed in from the camp");
        return true;
    }

    /** A bed somebody gave it: laid at the camp by the heart, and its own from tonight. */
    public boolean layGivenBed() {
        if (villageCentre == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
            if (!st.is(net.minecraft.tags.ItemTags.BEDS)
                    || !(net.minecraft.world.level.block.Block.byItem(st.getItem()) instanceof net.minecraft.world.level.block.BedBlock bed)) continue;
            if (!com.jrpetty.mcassistant.VillageSpawner.campBed(server, villageCentre, bed)) return false;
            st.shrink(1);
            for (BlockPos head : com.jrpetty.mcassistant.VillageSpawner.campBeds(server, villageCentre)) {
                if (bedOnOffer(head) && level().getBlockState(head).getBlock() == bed) { takeBed(head); break; }
            }
            return true;
        }
        return false;
    }

    /**
     * Beds for a house, made from what the stores hold — three wool and three planks
     * each, the colour of the wool. Nothing made a bed before: a house went up with
     * none unless a bed happened to be lying in the stores, and nobody slept in one.
     */
    private int makeBeds(int wanted) {
        if (villageCentre == null) return 0;
        int r = buildStoresRadius();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> wool =
            st -> st.is(net.minecraft.tags.ItemTags.WOOL);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> planks =
            st -> st.is(net.minecraft.tags.ItemTags.PLANKS) || st.is(net.minecraft.tags.ItemTags.LOGS);
        int made = 0;
        for (int i = 0; i < wanted; i++) {
            int woolBefore = countCarried(wool), planksBefore = countCarried(planks);
            if (woolBefore < 3) drawFrom(villageCentre, wool, 3 - woolBefore, r);
            if (planksBefore < 3) drawFrom(villageCentre, planks, 3 - planksBefore, r);
            if (countCarried(wool) < 3 || countCarried(planks) < 3) {
                returnTo(villageCentre, wool, woolBefore, r);
                returnTo(villageCentre, planks, planksBefore, r);
                break;
            }
            net.minecraft.world.item.Item bed = net.minecraft.world.item.Items.WHITE_BED;
            for (net.minecraft.world.item.ItemStack st : getInventoryItems()) {
                if (!wool.test(st)) continue;
                String colour = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())
                    .getPath().replace("_wool", "");
                net.minecraft.world.item.Item matching = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace(colour + "_bed"));
                if (matching != net.minecraft.world.item.Items.AIR) bed = matching;
                break;
            }
            removeMatching(wool, 3);
            removeMatching(planks, 3);
            insertItem(new net.minecraft.world.item.ItemStack(bed));
            made++;
        }
        return made;
    }

    /**
     * A building's fixtures made out of the stores' own materials: a chest of eight
     * planks, a furnace of eight cobblestone, a bench of four planks, fences, gates and
     * ladders of planks (a log is four planks). All or nothing for each one; returns how
     * many were made.
     */
    private int madeFromStores(BuildGoal.Part part, int wanted) {
        if (villageCentre == null || wanted <= 0) return 0;
        net.minecraft.world.item.Item product;
        int planksEach = 0, stoneEach = 0, perBatch = 1;
        switch (part) {
            case CHEST -> { product = net.minecraft.world.item.Items.CHEST; planksEach = 8; }
            case FURNACE -> { product = net.minecraft.world.item.Items.FURNACE; stoneEach = 8; }
            case CRAFTING_TABLE -> { product = net.minecraft.world.item.Items.CRAFTING_TABLE; planksEach = 4; }
            case FENCE -> { product = net.minecraft.world.item.Items.OAK_FENCE; planksEach = 5; perBatch = 3; }
            case GATE -> { product = net.minecraft.world.item.Items.OAK_FENCE_GATE; planksEach = 4; }
            case LADDER -> { product = net.minecraft.world.item.Items.LADDER; planksEach = 4; perBatch = 3; }
            // Four planks and four sticks (two planks' worth) a unit.
            case STOREHOUSE -> { product = com.jrpetty.mcassistant.McAssistantMod.STOREHOUSE_ITEM.get(); planksEach = 6; }
            default -> { return 0; }
        }
        int r = buildStoresRadius();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> planks =
            st -> st.is(net.minecraft.tags.ItemTags.PLANKS);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> logs =
            st -> st.is(net.minecraft.tags.ItemTags.LOGS);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> stone =
            st -> st.is(net.minecraft.world.item.Items.COBBLESTONE) || st.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE);
        int made = 0;
        while (made < wanted) {
            int needPlanks = planksEach, needStone = stoneEach;
            int planksBefore = countCarried(planks), logsBefore = countCarried(logs), stoneBefore = countCarried(stone);
            if (needStone > 0 && stoneBefore < needStone) drawFrom(villageCentre, stone, needStone - stoneBefore, r);
            if (needPlanks > 0) {
                int wood = countCarried(planks) + 4 * countCarried(logs);
                if (wood < needPlanks) drawFrom(villageCentre, planks, needPlanks - wood, r);
                wood = countCarried(planks) + 4 * countCarried(logs);
                if (wood < needPlanks) drawFrom(villageCentre, logs, (needPlanks - wood + 3) / 4, r);
            }
            boolean enough = countCarried(stone) >= needStone
                && countCarried(planks) + 4 * countCarried(logs) >= needPlanks;
            if (!enough) {
                returnTo(villageCentre, stone, stoneBefore, r);
                returnTo(villageCentre, planks, planksBefore, r);
                returnTo(villageCentre, logs, logsBefore, r);
                break;
            }
            if (needStone > 0) removeMatching(stone, needStone);
            if (needPlanks > 0) {
                int fromPlanks = removeMatching(planks, needPlanks);
                int rest = needPlanks - fromPlanks;
                if (rest > 0) {
                    int logsUsed = (rest + 3) / 4;
                    removeMatching(logs, logsUsed);
                    int spare = logsUsed * 4 - rest;
                    if (spare > 0) insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, spare));
                }
            }
            insertItem(new net.minecraft.world.item.ItemStack(product, perBatch));
            made += perBatch;
        }
        if (made > 0) brain("made " + made + " " + part.name().toLowerCase() + " from the stores");
        return Math.min(made, wanted);
    }

    private int bucketCheckTick = -100000;

    /**
     * A farmer whose plot has no water grows its crops three times slower on dry
     * farmland. A bucket of water (three iron) is a pond in the middle of the field and
     * eighty squares of wet ground round it: once the village has the iron, a farmer
     * with dry ground and no bucket has one made from the stores.
     */
    private void bucketFromTheStores() {
        if (stationTask() != StationTask.FARM || villageCentre == null || ownerId() == null) return;
        if (tickCount - bucketCheckTick < 2400) return;
        bucketCheckTick = tickCount;
        WorkZone zone = workZone();
        if (zone == null) return;
        if (countCarried(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET)
                || st.is(net.minecraft.world.item.Items.BUCKET)) > 0) return;
        // Only while the village is hungry: dry ground feeds a village that is not, and
        // the iron is what its age asks for.
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)
                || Villages.stock(server, villageCentre, Villages.Task.FOOD, Villages.storesRadius(ownerId()))
                    >= Villages.larderForBirth(ownerId())) return;
        BlockPos c = zone.center();
        int r = Math.min(8, zone.radius());
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 3, r))) {
            if (level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) return;   // water already
        }
        // A bucket the stores already have before a new one out of the age's iron, and a new one at
        // most once a day for the whole village (every dry field had one made, three iron each).
        Villages.Village home = Villages.get(ownerId());
        net.minecraft.world.item.ItemStack kept = home == null ? net.minecraft.world.item.ItemStack.EMPTY
            : Crafts.takeOne(server, home, st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET) || st.is(net.minecraft.world.item.Items.BUCKET));
        if (!kept.isEmpty()) {
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));   // filled at the river on the way
            brain("a bucket from the stores, filled for the field");
            return;
        }
        long today = level().getDayTime() / 24000L;
        if (Long.toString(today).equals(com.jrpetty.mcassistant.village.Ledger.note(ownerId(), "bucket.day"))) return;
        if (ironFromTheStores(3)) {
            com.jrpetty.mcassistant.village.Ledger.note(ownerId(), "bucket.day", Long.toString(today));
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
            brain("a bucket of water from the stores, for the field");
        }
    }

    /** Since when this folk has been missing something its trade needs to start. */
    private int stuckSince = -1;
    /** Whole days in a row it has been stuck at its trade. */
    private int stuckDays;

    /**
     * Ground that cannot be set up is given up. A farmer was staked on ground where its
     * chest would not go down ("no room beside me") and stood there sixteen game days
     * without turning a sod. A folk missing something for a whole working day looks
     * for new ground for the same trade somewhere else.
     */
    private boolean unstuckFromGround() {
        if (missingEssentials().isEmpty()) stuckDays = 0;
        if (missingEssentials().isEmpty() || !onShift()) { stuckSince = -1; return false; }
        if (stuckSince < 0) { stuckSince = tickCount; return false; }
        if (tickCount - stuckSince < 12000) return false;
        stuckSince = -1;
        StationTask trade = stationTask();
        if (trade == StationTask.NONE || trade == StationTask.GUARD || trade == StationTask.HAUL
                || trade == StationTask.STORE) return false;
        // Twice a whole day stuck at it (new ground or not): the trade cannot be got going here,
        // so it takes up one the village needs instead of standing about holding the place —
        // a hand that cannot work counted as one of its trade, and nobody else was sent.
        if (++stuckDays >= 2) {
            stuckDays = 0;
            StationTask next = Villages.needed(ownerId());
            if (next == trade) next = nextTradeAfter(trade);
            if (next != trade && next != StationTask.NONE) {
                UUID village = ownerId();
                if (village != null) Villages.tell(village, level().getDayTime() / 24000L, displayNameCap() + " gave up "
                    + trade.label + " (it could never get started: " + missingEssentials().get(0) + ") and took up " + next.label);
                brain("gave up " + trade.label + " for " + next.label);
                setStation(blockPosition(), next);
                return true;
            }
        }
        // The smeltery is the smelter's ground for good (workInTheBuilding): short of ore
        // or coal it waits for the carriers, it does not wander off to stake new ground.
        if (buildingFor(trade) != null && ownerId() != null && Villages.builtAt(ownerId(), buildingFor(trade)) != null) return false;
        avoidHere = workZone();
        searchBearing++;
        BlockPos site = findSite(trade, radiusFor(trade));
        avoidHere = null;
        if (site == null) return false;
        setStation(site, trade);
        assignPlot(WorkZone.around(site, radiusFor(trade), depthFor(trade, site)), patchNameFor(trade));
        setAutonomous(true);
        brain("gave up ground it could not set up, for new ground");
        return true;
    }

    private int reachCheckTick = -100000;

    @Override
    @Nullable
    protected BlockPos wayToward(BlockPos dest) {
        UUID village = ownerId();
        BlockPos heart = villageCentre;
        if (village == null || heart == null || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        Reach walk = Reach.of(sl, village, heart);
        return walk == null ? null : walk.waypoint(blockPosition(), dest, 28);
    }

    @Override
    protected boolean onWalkedGround(BlockPos p) {
        Reach walk = ownerId() == null ? null : Reach.last(ownerId());
        return walk != null && walk.reaches(p, 0);
    }

    /**
     * A field the town cannot walk to (Reach) is given up for one it can. Staked before the
     * village knew its ground — or by an older village that marked its farmland out over a ridge
     * — it was a field its farmer was carried to and stranded on, or never reached at all.
     */
    private boolean fieldOutOfReach() {
        if (stationTask() != StationTask.FARM || tickCount - reachCheckTick < 2400) return false;
        reachCheckTick = tickCount;
        WorkZone zone = workZone();
        UUID village = ownerId();
        BlockPos heart = villageCentre;
        if (zone == null || village == null || heart == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        Reach walk = Reach.of(sl, village, heart);
        if (walk == null || walk.reaches(zone.center(), Math.min(zone.radius(), FIELD_MOST))) return false;
        avoidHere = zone;
        BlockPos site = findSite(StationTask.FARM, radiusFor(StationTask.FARM));
        avoidHere = null;
        if (site == null || !walk.reaches(site, FIELD_MOST / 2)) return false;
        setStation(site, StationTask.FARM);
        assignPlot(WorkZone.around(site, radiusFor(StationTask.FARM), depthFor(StationTask.FARM, site)),
            patchNameFor(StationTask.FARM));
        setAutonomous(true);
        brain("gave up a field the town could not walk to, for one it can");
        return true;
    }

    private int fieldsCheckTick = -100000;

    /**
     * A hungry village needs farmers more than it needs another thousand stone. When
     * the stores hold less than half a day's meals and this folk's own trade has piled
     * up twice what the village asks for of it — a miner with the stone, a woodcutter
     * with the timber — it takes up farming, unless it is the last of its trade or half
     * the village already farms. (The b159 long game: twelve folk, four farmers, stone
     * eleven hundred, food eight, for sixteen game days.)
     */
    private boolean turnedToTheFields() {
        StationTask mine = stationTask();
        if (mine != StationTask.MINE && mine != StationTask.WOOD) return false;
        UUID village = ownerId();
        if (village == null || villageCentre == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - fieldsCheckTick < 6000) return false;
        fieldsCheckTick = tickCount;
        int folk = Villages.headcount(village);
        int r = Villages.storesRadius(village);
        int food = Villages.stock(server, villageCentre, Villages.Task.FOOD, r);
        // [economy] ...or the forecast has turned: short commons, with more eaten than grown by a quarter
        // (Larder.fieldsWanted). New fields take days to come in, so the hands go before the larder is low.
        if (food * 2 >= Villages.larderForBirth(village) && !Larder.fieldsWanted(village)) return false;
        // Next to nothing put by is famine: hands go to the fields whatever the stone or timber
        // wants (a town raising its walls had never any to spare, and starved with three farmers).
        boolean famine = food < Math.max(8, 2 * folk);
        int surplus = famine ? 1 : mine == StationTask.MINE
            ? Villages.stock(server, villageCentre, Villages.Task.STONE, r)
                - 2 * com.jrpetty.mcassistant.village.VillageMath.stoneWanted(folk)
            : Villages.stock(server, villageCentre, Villages.Task.LOGS, r)
                - 2 * com.jrpetty.mcassistant.village.VillageMath.timberWanted(folk);
        if (surplus <= 0) return false;
        int farmers = 0, same = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() == StationTask.FARM) farmers++;
            if (a.stationTask() == mine) same++;
        }
        if (same <= 1 || farmers * 2 >= folk) return false;
        avoidHere = workZone();
        BlockPos site = findSite(StationTask.FARM, radiusFor(StationTask.FARM));
        avoidHere = null;
        if (site == null) return false;
        setStation(site, StationTask.FARM);
        assignPlot(WorkZone.around(site, radiusFor(StationTask.FARM), depthFor(StationTask.FARM, site)),
            patchNameFor(StationTask.FARM));
        setAutonomous(true);
        brain("the village is hungry: took up farming");
        return true;
    }

    /**
     * A settler spends iron on kit only out of what its village holds beyond what its
     * age is saving: the Iron Age's own stock (the watch's armour and a smith's stock),
     * twice that from the Diamond Age. Every hand used to make itself iron armour and
     * iron tools out of whatever came in, and the stores never filled.
     */
    @Override
    protected boolean maySpendIron() {
        UUID village = ownerId();
        if (village == null || villageCentre == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        int target = com.jrpetty.mcassistant.village.VillageMath.ironWanted(Villages.headcount(village));
        if (Villages.ageOf(village).ordinal() >= Villages.Age.DIAMOND.ordinal()) target *= 2;
        return Villages.stock(server, villageCentre, Villages.Task.IRON, Villages.storesRadius(village)) > target + 8;
    }

    private int charcoalCheckTick = -100000;

    /**
     * Coal is what the Stone Age asks for, and a mine meets it only where a seam
     * happens to be: four Stone Age villages on real maps held none. A smelter with no
     * ore to run, in a village short of coal, burns logs from the stores into charcoal
     * (which counts as coal): half of them to burn, half as fuel.
     */
    private int coalCheckTick = -100000;
    private boolean coalShort;

    @Override
    public boolean savingCoal() {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - coalCheckTick >= 1200) {
            coalCheckTick = tickCount;
            coalShort = false;
            for (Villages.Need n : Villages.needs(server, village)) {
                if (n.task() == Villages.Task.COAL) { coalShort = true; break; }
            }
        }
        return coalShort;
    }

    private int coalLowTick = -100000;
    private boolean coalLow;

    /** [economy] Are the stores under the floor of coal a village keeps whatever its age (Fuel)? Looked at once a minute. */
    @Override
    public boolean coalLow() {
        UUID village = ownerId();
        if (village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - coalLowTick >= 1200) {
            coalLowTick = tickCount;
            coalLow = Fuel.low(server, village);
        }
        return coalLow;
    }

    /** Next to no glass at all (no bottles for the brewer, the beekeeper or the café): glass before iron.
     *  Short of glass for the windows only, the sand waits for a gap in the ore (Masonry.glassShort is
     *  what the smelter fetches and digs sand for: enough for the windows, not sixteen blocks). */
    @Override
    protected boolean wantsGlass() {
        UUID village = ownerId();
        return village != null && level() instanceof net.minecraft.server.level.ServerLevel server
            && Market.stock(server, village, s -> s.is(net.minecraft.world.item.Items.GLASS)
                || s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)) < 16;
    }

    /** [wf] At a fire (FireBrigade), or in out of a thunderstorm (Weather): its own work waits. */
    @Override
    protected boolean calledAway() {
        return FireBrigade.onIt(this) || Weather.sheltering(this) || Health.laidUp(this) || Neighbourly.busy(this)   // [batchA]
            || Inn.lodged(this)                                  // [batchE] asleep in a room at an inn on the road
            || WatchClears.sheltering(this)                      // [watch-clears] indoors out of a monster's way
            || Transport.busy(this)                              // [transport] on a ride, a crossing, or at the ferry
            || Crime.calledAway(this)                            // [crime] on a case, at a trial, in the stocks, at community work
            || Disasters.busy(this)                              // [disasters] a bucket chain, a flood, a night away, the fire watch
            || Individual.busy(this)                             // [individual] home before dark, a habit at its hour
            || WorkTools.busy(this)                              // [workitems] on a rope, or hanging or watering a window box
            || Interviews.busy(this)                             // [interviews] at an interview, or on the road to one
            || FireworksMaker.fetching(this) || FireworkShows.crewing(this)   // [fireworks] a creeper's powder, a display's rack
            || Divers.busy(this);                                // [diver] on a dive, or being pulled out of the water
    }

    /** [wf] The woodcutter's wood kept growing between its fellings (Woods). */
    @Override
    protected boolean woodsWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Woods.tend(this, server);
    }

    /** The mason's work at the smeltery, when there is no ore to run (Masonry). */
    @Override
    protected boolean masonWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Masonry.work(this, server);
    }

    /**
     * Logs the builders can spare: what the stores hold past half the timber the village's houses
     * want, and never the last sixty-four. Eight smelters burning sixteen apiece kept a town of
     * sixty-eight at no logs at all for days, and the meeting hall it needed for the Iron Age could
     * never be paid for. (The charcoal burnt for a village short of coal, and the wood a courier
     * takes out to the smelter in place of the coal.)
     */
    private int logsToSpare(net.minecraft.server.level.ServerLevel server, UUID village) {
        int stored = Market.stock(server, village, st -> st.is(net.minecraft.tags.ItemTags.LOGS));
        int keep = Math.max(64, com.jrpetty.mcassistant.village.VillageMath.timberWanted(Villages.headcount(village)) / 2);
        return Math.max(0, stored - keep);
    }

    @Override
    public boolean burnCharcoal() {
        UUID village = ownerId();
        if (village == null || villageCentre == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - charcoalCheckTick < 1200) return false;
        charcoalCheckTick = tickCount;
        // [economy] Wanted when the age asks for coal, as it always was, and now too when the stores are
        // under the floor in any age (save the Wood Age while it still wants timber): Fuel.charcoalWanted.
        if (!Fuel.charcoalWanted(server, village)) return false;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> logs =
            st -> st.is(net.minecraft.tags.ItemTags.LOGS);
        int spare = Math.min(16, logsToSpare(server, village));
        int have = countCarried(logs);
        if (have < 4 && spare < 4) return false;
        if (have < 16 && spare > 0) have += drawFrom(villageCentre, logs, Math.min(16 - have, spare), buildStoresRadius());
        if (have < 4) return false;
        enqueue(Job.smelt("logs", have / 2));
        brain("burning " + have / 2 + " logs into charcoal for the village");
        return true;
    }

    private int shearsCheckTick = -100000;

    /**
     * A rancher needs shears for the wool the beds are made of, and shears are two
     * iron: once the village has some (the Stone Age on, when the mines reach the
     * iron), a rancher with none has a pair made from the stores.
     */
    private void shearsFromTheStores() {
        if (stationTask() != StationTask.RANCH || villageCentre == null || ownerId() == null) return;
        if (tickCount - shearsCheckTick < 2400) return;
        shearsCheckTick = tickCount;
        if (Villages.ageOf(ownerId()).ordinal() < Villages.Age.STONE.ordinal()) return;
        if (countCarried(st -> st.is(net.minecraft.world.item.Items.SHEARS)) > 0) return;
        // The smith's shears first, if the stores have a pair; else made from the stores' iron.
        if (drawFrom(villageCentre, st -> st.is(net.minecraft.world.item.Items.SHEARS), 1, buildStoresRadius()) > 0) {
            brain("shears from the stores");
            return;
        }
        if (ironFromTheStores(2)) {
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
            brain("shears made from the stores");
        }
    }

    private int rodCheckTick = -100000;

    /**
     * A fisher with no rod has one made there and then out of the stores: a rod put by, or two
     * string and a plank (three sticks' worth). Fishers stood at the water "needing a fishing
     * rod" in every real-world game: the makings went back into the stores before the rod was
     * ever made of them.
     */
    private void rodFromTheStores() {
        if (stationTask() != StationTask.FISH || villageCentre == null || ownerId() == null) return;
        if (tickCount - rodCheckTick < 1200) return;
        rodCheckTick = tickCount;
        if (countCarried(st -> st.is(net.minecraft.world.item.Items.FISHING_ROD)) > 0) return;
        int r = buildStoresRadius();
        if (drawFrom(villageCentre, st -> st.is(net.minecraft.world.item.Items.FISHING_ROD), 1, r) > 0) {
            brain("a fishing rod from the stores");
            return;
        }
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> string = st -> st.is(net.minecraft.world.item.Items.STRING);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> wood =
            st -> st.is(net.minecraft.tags.ItemTags.PLANKS) || st.is(net.minecraft.tags.ItemTags.LOGS) || st.is(net.minecraft.world.item.Items.STICK);
        int haveString = countCarried(string), haveWood = countCarried(wood);
        if (haveString < 2) haveString += drawFrom(villageCentre, string, 2 - haveString, r);
        // No string in the stores: a lock of wool spun into a line (a tailor spins it, but a village
        // with no tailor, or one making beds of every scrap, left its fishers rodless for good).
        if (haveString < 2 && drawFrom(villageCentre, st -> st.is(net.minecraft.tags.ItemTags.WOOL), 1, r) > 0) {
            removeMatching(st -> st.is(net.minecraft.tags.ItemTags.WOOL), 1);
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STRING, 4));
            haveString += 4;
            brain("spun a lock of wool into a fishing line");
        }
        if (haveWood < 1) haveWood += drawFrom(villageCentre, wood, 1, r);
        if (haveString < 2 || haveWood < 1) return;                    // the rest waits in its pack for more
        removeMatching(string, 2);
        removeMatching(wood, 1);
        insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FISHING_ROD));
        brain("made a fishing rod of the stores' string and wood");
    }

    /** Take this much iron out of the stores to make something with: ingots first, raw
     *  iron and a coal to smelt each after. All or nothing — what was drawn goes back. */
    private boolean ironFromTheStores(int amount) {
        int r = buildStoresRadius();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> ingot =
            st -> st.is(net.minecraft.world.item.Items.IRON_INGOT);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> raw =
            st -> st.is(net.minecraft.world.item.Items.RAW_IRON);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> fuel =
            st -> st.is(net.minecraft.world.item.Items.COAL) || st.is(net.minecraft.world.item.Items.CHARCOAL);
        int ingotsBefore = countCarried(ingot), rawBefore = countCarried(raw), fuelBefore = countCarried(fuel);
        int ingots = Math.min(amount, ingotsBefore + drawFrom(villageCentre, ingot, Math.max(0, amount - ingotsBefore), r));
        int raws = 0;
        if (ingots < amount) {
            int want = amount - ingots;
            raws = Math.min(want, rawBefore + drawFrom(villageCentre, raw, Math.max(0, want - rawBefore), r));
            drawFrom(villageCentre, fuel, Math.max(0, raws - fuelBefore), r);
        }
        if (ingots + raws >= amount && countCarried(fuel) >= raws) {
            removeMatching(ingot, ingots);
            removeMatching(raw, raws);
            removeMatching(fuel, raws);
            return true;
        }
        returnTo(villageCentre, ingot, ingotsBefore, r);
        returnTo(villageCentre, raw, rawBefore, r);
        returnTo(villageCentre, fuel, fuelBefore, r);
        return false;
    }

    /** This visit set about making a fixture (so the next look is soon). */
    private boolean madeAFixture;
    /** This visit found a part that cannot be had, or a making that keeps coming to nothing. */
    private boolean stuckOnAPart;
    /** The last fixture set in hand, and how many visits running it has been the same one. */
    private String lastFixtureAsk = "";
    private int fixtureTries;

    /** A blueprint part a builder must have in hand, and what makes one (null: nothing does). */
    private record Fixture(BuildGoal.Part part, @Nullable String recipe) {}

    private static final java.util.List<Fixture> FIXTURES = java.util.List.of(
        new Fixture(BuildGoal.Part.CHEST, "chest"),
        new Fixture(BuildGoal.Part.FURNACE, "furnace"),
        new Fixture(BuildGoal.Part.CRAFTING_TABLE, "crafting_table"),
        new Fixture(BuildGoal.Part.LADDER, "ladder"),
        new Fixture(BuildGoal.Part.FENCE, "oak_fence"),
        new Fixture(BuildGoal.Part.GATE, "oak_fence_gate"),
        // The Village Storehouse's units: made from the stores' planks by the builder that
        // lays them (madeFromStores), never asked of anybody else.
        new Fixture(BuildGoal.Part.STOREHOUSE, null),
        // Dug, never made: from the stores or not at all.
        new Fixture(BuildGoal.Part.OBSIDIAN, null));

    // ------------------------------ persistence ------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag social = new CompoundTag();
        life.save(social);
        tag.put("Social", social);
        if (persona.rolled()) {
            CompoundTag inner = new CompoundTag();
            persona.save(inner);
            tag.put("Persona", inner);
        }
        if (showcase) tag.putBoolean("Showcase", true);
        tag.put("Meals", meals.save());
        Health.save(this, tag);                         // [batchA]
        Fashion.save(this, tag);                        // [fashion] its colours, what it wears, what it wants
        if (self.rolled || self.genes.rolled) tag.put("Individual", self.save());   // [individual]
        if (productionChest != null) tag.putLong("ProductionChest", productionChest.asLong());
        if (oldProductionChest != null) tag.putLong("OldProductionChest", oldProductionChest.asLong());
        tag.putLong("BornDay", bornDay);
        tag.putInt("DaysAYear", DAYS_A_YEAR);           // [ageing] counted at a year every fifth day
        if (rentFree) tag.putBoolean("RentFree", true);
        if (mentor != null) tag.putUUID("Mentor", mentor);
        if (apprenticeTo != StationTask.NONE) tag.putString("Apprentice", apprenticeTo.name());
        tag.putBoolean("FrailTold", frailTold);
        if (hiredBy != null) {
            tag.putUUID("HiredBy", hiredBy);
            tag.putLong("HiredUntil", hiredUntil);
            tag.putLong("HiredSince", hiredSince);
            tag.putInt("HiredKills", hiredKills);
            tag.putString("HiredSaw", String.join("|", hiredSaw));
            tag.putString("HiredName", hiredName);
        }
        if (!parentIds.isEmpty()) {
            net.minecraft.nbt.ListTag ps = new net.minecraft.nbt.ListTag();
            for (UUID u : parentIds) ps.add(net.minecraft.nbt.NbtUtils.createUUID(u));
            tag.put("ParentIds", ps);
        }
        if (valuesSet) {
            tag.putIntArray("Values", values.clone());
            tag.putLong("ValuesDay", valuesDay);
        }
        tag.putInt("Purse", purse);
        tag.putInt("PaidDeeds", paidDeeds);
        tag.putInt("EarnedInAll", earnedInAll);
        CompoundTag trades = new CompoundTag();
        for (java.util.Map.Entry<StationTask, Integer> e : tradeXp.entrySet()) trades.putInt(e.getKey().name(), e.getValue());
        tag.put("TradeXp", trades);
        if (!knacks.isEmpty()) tag.put("Knacks", knacks.save());
        tag.putInt("Comforts", comforts);
        tag.putLong("ComfortDay", comfortDay);
        if (isBaby()) tag.putBoolean("Child", true);
        if (villageCentre != null) tag.putLong("VillageCentre", villageCentre.asLong());
        UUID village = ownerId();
        if (village != null) {
            // The settlement's own progress rides on its people. The register
            // is memory-only, so without this a village that had reached the
            // Iron Age came back from a restart as a camp and set about
            // building the storehouse it already had.
            tag.putString("VillageAge", Villages.ageOf(village).name());
            net.minecraft.nbt.ListTag built = new net.minecraft.nbt.ListTag();
            for (String s : Villages.builtList(village)) {
                built.add(net.minecraft.nbt.StringTag.valueOf(s));
            }
            tag.put("VillageBuilt", built);
            // ...and where the stores and the smeltery stand, so the carriers fill the
            // storehouse after a restart, not whatever chest is nearest the heart.
            net.minecraft.nbt.CompoundTag at = new net.minecraft.nbt.CompoundTag();
            for (String b : new String[]{ "storage", "smeltery" }) {
                BlockPos p = Villages.builtAt(village, b);
                if (p != null) at.putLong(b, p.asLong());
            }
            if (!at.isEmpty()) tag.put("VillageBuiltAt", at);
            // The roll rides on its people, the same as the age and the
            // buildings do. Without it a restart forgets how many live here
            // and the place sizes its larder for whoever happens to be loaded.
            tag.putInt("VillagePop", Villages.recordedPopulation(village));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Social")) life.load(tag.getCompound("Social"));
        if (tag.contains("Persona")) persona.load(tag.getCompound("Persona"));
        this.showcase = tag.getBoolean("Showcase");
        if (tag.contains("Meals")) meals.load(tag.getCompound("Meals"));
        Health.load(this, tag);                         // [batchA]
        Fashion.load(this, tag);                        // [fashion]
        if (tag.contains("Individual")) self.load(tag.getCompound("Individual"));   // [individual]
        this.productionChest = tag.contains("ProductionChest") ? BlockPos.of(tag.getLong("ProductionChest")) : null;
        this.oldProductionChest = tag.contains("OldProductionChest") ? BlockPos.of(tag.getLong("OldProductionChest")) : null;
        this.bornDay = tag.contains("BornDay") ? tag.getLong("BornDay") : UNKNOWN;
        // [ageing] Saved while grown folk aged two years a day: the years it had then, at the new pace from here.
        if (bornDay != UNKNOWN && !tag.contains("DaysAYear") && !tag.getBoolean("Child")) {
            bornDay = bornAtTheOldPace(bornDay, level().getDayTime() / 24000L);
        }
        this.rentFree = tag.getBoolean("RentFree");
        this.mentor = tag.hasUUID("Mentor") ? tag.getUUID("Mentor") : null;
        try {
            this.apprenticeTo = tag.contains("Apprentice") ? StationTask.valueOf(tag.getString("Apprentice")) : StationTask.NONE;
        } catch (IllegalArgumentException e) {
            this.apprenticeTo = StationTask.NONE;
        }
        this.frailTold = tag.getBoolean("FrailTold");
        if (tag.hasUUID("HiredBy")) {
            this.hiredBy = tag.getUUID("HiredBy");
            this.hiredUntil = tag.getLong("HiredUntil");
            this.hiredSince = tag.getLong("HiredSince");
            this.hiredKills = tag.getInt("HiredKills");
            this.hiredName = tag.getString("HiredName");
            this.hiredSaw.clear();
            for (String b : tag.getString("HiredSaw").split("\\|")) if (!b.isEmpty()) hiredSaw.add(b);
            this.companion = hiredBy;
            this.companionUntil = Integer.MAX_VALUE;
        }
        parentIds.clear();
        if (tag.contains("ParentIds")) {
            for (net.minecraft.nbt.Tag t : tag.getList("ParentIds", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) parentIds.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        }
        if (tag.contains("Values")) {
            int[] saved = tag.getIntArray("Values");
            if (saved.length == Values.N) {
                System.arraycopy(saved, 0, values, 0, Values.N);
                valuesSet = true;
                valuesDay = tag.getLong("ValuesDay");
            }
        }
        this.purse = tag.getInt("Purse");
        this.paidDeeds = tag.getInt("PaidDeeds");
        this.earnedInAll = tag.getInt("EarnedInAll");
        tradeXp.clear();
        if (tag.contains("TradeXp")) {
            CompoundTag trades = tag.getCompound("TradeXp");
            for (String k : trades.getAllKeys()) {
                try { tradeXp.put(StationTask.valueOf(k), trades.getInt(k)); } catch (IllegalArgumentException ignored) { }
            }
        } else if (lifetimeXp() > 0 && stationTask() != StationTask.NONE) {
            tradeXp.put(stationTask(), lifetimeXp());       // from before trades had levels of their own
        }
        refreshLevelPerks();
        knacks.load(tag.getCompound("Knacks"));                 // none in an older save
        this.comforts = tag.getInt("Comforts");
        this.comfortDay = tag.contains("ComfortDay") ? tag.getLong("ComfortDay") : -10;
        if (tag.getBoolean("Child")) setChild(true);
        if (tag.contains("VillageCentre")) {
            this.villageCentre = BlockPos.of(tag.getLong("VillageCentre"));
            // The register lives in memory only; the first folk to load puts
            // its settlement back on the map for the rest — age, buildings
            // and all.
            UUID id = ownerId();
            if (id != null) {
                Villages.Age age = Villages.Age.WOOD;
                try {
                    if (tag.contains("VillageAge")) age = Villages.Age.valueOf(tag.getString("VillageAge"));
                } catch (IllegalArgumentException ignored) { }
                java.util.List<String> built = new java.util.ArrayList<>();
                for (net.minecraft.nbt.Tag t : tag.getList("VillageBuilt", net.minecraft.nbt.Tag.TAG_STRING)) {
                    built.add(t.getAsString());
                }
                Villages.restore(level(), id, villageCentre, age, built,
                    tag.getInt("VillagePop"));
                net.minecraft.nbt.CompoundTag at = tag.getCompound("VillageBuiltAt");
                for (String b : at.getAllKeys()) Villages.rememberBuiltAt(id, b, BlockPos.of(at.getLong(b)));
            }
        }
    }
}
