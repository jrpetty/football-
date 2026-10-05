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

    /** A villager is not a hired hand on a clock: it eats a ration every four and a
     *  half minutes of work where an assistant eats one every two and a half. Nineteen
     *  mouths on a young village's first fields were eating more than five farmers
     *  grew, and by the third day half of them stood at the heart with no rations. */
    @Override
    public int traitUpkeepPercent() {
        return super.traitUpkeepPercent() * 3 / 2;
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
                || s.is(net.minecraft.world.item.Items.NOTE_BLOCK)) {
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
        return net.minecraft.sounds.SoundEvents.VILLAGER_AMBIENT;
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
        Leisure.tick(this);
        if (hiredBy != null && tickCount % 20 == 0 && level() instanceof net.minecraft.server.level.ServerLevel out) Hire.tick(this, out);
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
        // Through the gateway with a Nether party: it waits by the gateway till they come back.
        if (Nether.away(this) && !withAPlayer) return;
        // Out with a lead, fetching a wild animal home to the pen (Drover): that is the work just now.
        // The pen's gate: opened to go through it, and shut behind (Drover).
        if (tickCount % 10 == 7 && level() instanceof net.minecraft.server.level.ServerLevel penLevel) Drover.gate(this, penLevel);
        if (Drover.busy(this) && !withAPlayer && level() instanceof net.minecraft.server.level.ServerLevel herding) {
            if (tickCount % 10 == 0) Drover.drive(this, herding);
            return;
        }
        // Badly hurt: the brewer's healing, its own or (any trade) one from the stores.
        if (tickCount % 20 == 3 && level() instanceof net.minecraft.server.level.ServerLevel hurtIn) {
            if (!Links.drinkIfHurt(this) && stationTask() != StationTask.GUARD) Links.healFromTheStores(this, hurtIn);
        }
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
        // Called to the town's own work (TownJobs): to the spot, and at it.
        if (!withAPlayer && tickCount % 4 == 3 && level() instanceof net.minecraft.server.level.ServerLevel works
                && TownJobs.hold(this, works)) return;
        if (tickCount - agendaTick < 100) return;   // folk think slowly, on purpose
        agendaTick = tickCount;
        flyTheColours();
        showTheWealth();
        if (!life.rolled()) life.roll(getRandom(), null, null);
        ensurePersona();
        refreshMood();
        dreamCameTrue();
        if (ownerId() != null) {
            Villages.chooseElder(ownerId(), level().getDayTime() / 24000L);
            if (level() instanceof net.minecraft.server.level.ServerLevel orders) Orders.consider(orders, ownerId(), level().getDayTime() / 24000L);
        }
        // The town's streets, worn and paved and lit a little at a time (TownWork).
        if (ownerId() != null && level() instanceof net.minecraft.server.level.ServerLevel townLevel) {
            Villages.Village home = Villages.get(ownerId());
            if (home != null) {
                TownWork.tick(townLevel, home);
                TownLife.tick(townLevel, home);         // lit windows, chimney smoke, washing, stalls, signs
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

    // ------------------------------ money --------------------------------------

    /** What this folk has saved out of its wages (Market). */
    private int purse;
    /** The day it last spent at the market. */
    private long shoppedDay = -1;

    public int purse() { return purse; }

    public void earn(int coins) { if (coins > 0) purse += coins; }

    /** How much of its work had been paid for at its last wage (Wealth.bonus). */
    private int paidDeeds;
    /** The comforts it has bought and set up in its home: a rug, a lantern, flowers, books. */
    private int comforts;
    /** The day it last bought a comfort for its home, and the one it is carrying home now. */
    private long comfortDay = -10;
    private net.minecraft.world.item.ItemStack comfortCarried = net.minecraft.world.item.ItemStack.EMPTY;
    private int comfortSetOff = -1;

    public int paidDeeds() { return paidDeeds; }

    public int comforts() { return comforts; }

    /** Paid: what it had done so far is paid for. */
    public void paid(int coins) {
        earn(coins);
        if (coins > 0) earnedInAll += coins;
        paidDeeds = deedsTotal();
    }

    /** Every coin of wages it has ever been paid. */
    private int earnedInAll;
    /** The day its wage went up (the place came up in the world), and the last day it was paid short. */
    private long payRiseDay = -100, shortPaidDay = -100;

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

    /** A comfort for a home: what it is, what it costs, and whether it stands against a wall. */
    private record Comfort(java.util.function.Predicate<net.minecraft.world.item.ItemStack> what, int price,
                           boolean wall, Wealth.Tier from, String words) {}

    private static final java.util.List<Comfort> COMFORTS = java.util.List.of(
        new Comfort(st -> st.is(net.minecraft.tags.ItemTags.WOOL_CARPETS), 1, false, Wealth.Tier.COMFORTABLE, "a rug for my floor"),
        new Comfort(st -> st.is(net.minecraft.world.item.Items.FLOWER_POT), 1, false, Wealth.Tier.COMFORTABLE, "a pot for my windowsill"),
        new Comfort(st -> st.is(net.minecraft.tags.ItemTags.CANDLES), 1, false, Wealth.Tier.COMFORTABLE, "a candle for the evenings"),
        new Comfort(st -> st.is(net.minecraft.world.item.Items.LANTERN), 2, false, Wealth.Tier.COMFORTABLE, "a lantern for my table"),
        new Comfort(st -> st.is(net.minecraft.world.item.Items.BOOKSHELF), 4, true, Wealth.Tier.WELL_OFF, "a bookshelf, like the elder's"));

    /**
     * Its savings, spent on its home: a folk that is comfortable or better, with the coin for
     * it, walks to the stores, buys a rug, a pot, a candle, a lantern or (once it is well off)
     * a bookshelf — paying the treasury for it — carries it home and sets it up by its bed.
     * A home fills up as its owner does well: two comforts for a comfortable folk, four for a
     * well-off one, seven for the wealthy. Every second day at most; never on the way to work.
     */
    private boolean homeComfort(net.minecraft.server.level.ServerLevel server) {
        UUID village = ownerId();
        BlockPos bed = bedPos();
        if (village == null || bed == null || isBaby() || !level().isLoaded(bed)) return false;
        long day = level().getDayTime() / 24000L;
        // Carrying one home: home, and set it up.
        if (!comfortCarried.isEmpty()) {
            if (comfortSetOff < 0) comfortSetOff = tickCount;
            if (tickCount - comfortSetOff > 2400) {                    // could not get it home: back to the stores
                Villages.Village back = Villages.get(village);
                if (back != null) Crafts.store(server, back, comfortCarried);
                comfortCarried = net.minecraft.world.item.ItemStack.EMPTY;
                comfortSetOff = -1;
                return false;
            }
            if (blockPosition().distSqr(bed) > 3.5 * 3.5) {
                if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                    walkTo(bed, 0.9D);
                    socialWalkTick = tickCount;
                }
                brain("carrying " + comfortCarried.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " home");
                return true;
            }
            Comfort kind = null;
            for (Comfort c : COMFORTS) if (c.what().test(comfortCarried)) { kind = c; break; }
            BlockPos spot = kind == null ? null : comfortSpot(bed, kind.wall());
            net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Block.byItem(comfortCarried.getItem());
            if (spot != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                level().setBlock(spot, block.defaultBlockState(), 3);
                swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
                level().playSound(null, spot, block.defaultBlockState().getSoundType().getPlaceSound(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
                comforts++;
                persona.remember(day, "I bought " + kind.words() + " with my own savings", 3);
                FolkTalk.speak(this, pick("There. That's more like home.", "Lovely. Worth every coin.",
                    "Now that's a home to be proud of."));
            } else {
                Villages.Village back = Villages.get(village);              // no room for it: back it goes
                if (back != null) Crafts.store(server, back, comfortCarried);
            }
            comfortCarried = net.minecraft.world.item.ItemStack.EMPTY;
            comfortSetOff = -1;
            return true;
        }
        if (day - comfortDay < 2) return false;
        Wealth.Tier tier = Wealth.tier(this);
        if (comforts >= tier.comforts) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        // What it can afford and the stores have, the grander first once it can.
        Comfort want = null;
        int start = Math.floorMod(getUUID().hashCode() + (int) day, COMFORTS.size());
        for (int i = 0; i < COMFORTS.size(); i++) {
            Comfort c = COMFORTS.get((start + i) % COMFORTS.size());
            if (tier.ordinal() < c.from().ordinal() || purse < c.price() + 3) continue;
            if (Market.stock(server, village, c.what()) <= 0) continue;
            if (want == null || c.price() > want.price()) want = c;
        }
        if (want == null) { comfortDay = day; return false; }
        BlockPos stores = storesSpot(server, village);
        if (stores == null) return false;
        if (blockPosition().distSqr(stores) > 3.5 * 3.5) {
            if (comfortSetOff < 0) comfortSetOff = tickCount;
            if (tickCount - comfortSetOff > 1800) { comfortDay = day; comfortSetOff = -1; return false; }
            if (getNavigation().isDone() || tickCount - socialWalkTick >= 100) {
                walkTo(stores, 0.9D);
                socialWalkTick = tickCount;
            }
            brain("off to the stores to buy " + want.words());
            return true;
        }
        comfortSetOff = -1;
        comfortDay = day;
        net.minecraft.world.item.ItemStack got = Crafts.takeOne(server, v, want.what());
        if (got.isEmpty() || !spend(want.price())) {
            if (!got.isEmpty()) Crafts.store(server, v, got);
            return false;
        }
        com.jrpetty.mcassistant.village.Ledger.addCoins(village, want.price());
        comfortCarried = got;
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        FolkTalk.speak(this, pick("I've been saving for " + want.words() + ".", "Treating myself: " + want.words() + "!",
            want.price() + (want.price() == 1 ? " coin" : " coins") + " for " + want.words() + ". Money well spent."));
        return true;
    }

    /** Where in its home a comfort goes: indoors, near its bed, on a sound floor, out of the way. */
    @Nullable
    private BlockPos comfortSpot(BlockPos bed, boolean wall) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos p = bed.offset(dx, dy, dz);
                    if (!level().getBlockState(p).isAir() || !level().getBlockState(p.above()).isAir()) continue;
                    if (!level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), net.minecraft.core.Direction.UP)) continue;
                    if (level().canSeeSky(p)) continue;                                   // indoors only
                    boolean byDoor = false, byWall = false;
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        net.minecraft.world.level.block.state.BlockState n = level().getBlockState(p.relative(d));
                        if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) byDoor = true;
                        if (n.isFaceSturdy(level(), p.relative(d), d.getOpposite())) byWall = true;
                    }
                    if (byDoor || (wall && !byWall)) continue;
                    // Against a wall is tidier; nearer the bed is homelier.
                    double score = p.distSqr(bed) + (byWall ? 0 : 4);
                    if (score < bestScore) { bestScore = score; best = p; }
                }
            }
        }
        return best;
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
        boolean forWork = tool != null && countCarried(tool) == 0;
        boolean treat = Wealth.tier(this).ordinal() >= Wealth.Tier.WELL_OFF.ordinal() && Math.floorMod(getUUID().hashCode() + day, 4L) == 0;
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
        if (village == null || purse < 2 || isBaby()) return false;
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
        int food = countFood();
        if (food == 0) { m -= 14; why.add(new Object[]{"hungry", 14}); }
        else if (food >= 4) { m += 3; why.add(new Object[]{"fed", 2}); }
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
        }
        why.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Object[] w : why) keys.add((String) w[0]);
        persona.setMood(m, keys);
    }

    /** The old walk; everybody else runs when there is a long way to go. */
    @Override
    protected boolean fitToRun() {
        return super.fitToRun() && !isOld();
    }

    /** A happy village works faster, a miserable one slower (Contentment). */
    @Override
    protected int villageWorkPercent() {
        return Contentment.workPercent(ownerId()) + (isOld() ? -10 : 0);
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
        if (t != StationTask.NONE && amount > 0) tradeXp.merge(t, amount, (a, b) -> Math.min(1_000_000, a + b));
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

    /** A good mood makes for quick hands, a black one for slow ones. */
    @Override
    protected int moodWorkPercent() {
        int m = persona.mood();
        return m >= 80 ? 8 : m >= 65 ? 4 : m < 30 ? -10 : m < 45 ? -4 : 0;
    }

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
            case GREAT_WORK -> village != null && Villages.renown(village) >= 1;
            case GARDEN -> persona.flowersPlanted() >= 6;
            case WELL_FED -> village != null && villageCentre != null
                && level() instanceof net.minecraft.server.level.ServerLevel server
                && persona.since() >= 0 && level().getDayTime() / 24000L - persona.since() >= 6
                && Villages.stock(server, villageCentre, Villages.Task.FOOD, Villages.storesRadius(village))
                    >= 2 * com.jrpetty.mcassistant.village.VillageMath.foodWanted(Villages.headcount(village));
        };
        if (!met) return;
        long day = level().getDayTime() / 24000L;
        persona.meetAmbition();
        persona.remember(day, FolkTalk.cap(persona.ambition().done), 10);
        if (village != null) Villages.tell(village, day, displayNameCap() + "'s dream came true");
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
            FolkTalk.open(this, sp);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** Being hit is not forgotten — by it, or by anybody who saw. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean took = super.hurt(source, amount);
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
        } else if (took && !level().isClientSide && persona.rolled() && !showcase
                && source.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
                && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity monster) {
            // Set on by a monster with somebody near enough to help: it shouts for them.
            beset = monster.getUUID();
            besetUntil = tickCount + 600;
            net.minecraft.world.entity.player.Player near = level().getNearestPlayer(this, 24.0);
            if (near != null && !near.isSpectator() && tickCount - lastCryTick > 100) {
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
        if (trip != null && level() instanceof net.minecraft.server.level.ServerLevel road) Caravans.abandon(road, this);
        if (expedition != null && level() instanceof net.minecraft.server.level.ServerLevel land) {
            UUID home = ownerId();
            if (home != null) Villages.tell(home, level().getDayTime() / 24000L, displayNameCap() + " was lost while scouting the " + expedition.heading());
            Scouts.abandon(land, this);
        }
        UUID village = ownerId();
        if (!level().isClientSide && village != null && !showcase) {
            long day = level().getDayTime() / 24000L;
            Raids.fell(village);
            Contentment.loss(village, day);
            int age = ageYears();
            String how = passing ? "of old age" : Raids.underAlarm(village) ? "when the raiders came" : "by misfortune";
            com.jrpetty.mcassistant.village.Ledger.buried(village, new com.jrpetty.mcassistant.village.Ledger.Grave(
                displayNameCap(), bornDay, day, how, life.parents(), life.partnerName(), stationTask().title));
            Villages.tell(village, day, passing
                ? displayNameCap() + " died peacefully in their sleep, aged " + age
                : displayNameCap() + " died, aged " + age);
            Gatherings.mourn(village, displayNameCap(), day);
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
        if (DATA_CHILD.equals(key)) refreshDimensions();
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
            int delta = Social.warmth(life, other.life, other.stationTask() == stationTask(), offWork, getRandom());
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
            UUID village = ownerId();
            // An apprentice takes up the trade it learned, unless the village has more than enough
            // hands at it already, and starts it with a few years' knack already in its hands.
            String learned = null;
            if (village != null && apprenticeTo != StationTask.NONE && apprenticeTo != StationTask.GUARD
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
            if (village != null) Assemblies.cameOfAge(village, this, learned);
            if (village != null) Villages.tell(village, day, displayNameCap() + " grew up"
                + (learned == null ? "" : " and became a " + learned + ", as " + mentorName() + " taught them"));
            FolkTalk.speak(this, learned != null
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
        if (day - bornDay >= 1 && level().getDayTime() % 24000L < 7000L && apprentice(server)) return;
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

    // ------------------------------ growing old --------------------------------

    /** Years a child grows a day (it is grown at eighteen, three days old). */
    static final int CHILD_YEARS_A_DAY = 6;
    /** Years a grown folk ages in a day. */
    static final int YEARS_A_DAY = 2;
    /** The age folk are old at: slower about the place and at work. */
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
            else bornDay = day - GROW_DAYS - (2 + Math.floorMod(getUUID().hashCode(), 21)) / YEARS_A_DAY;
        }
        long days = Math.max(0, day - bornDay);
        if (isBaby()) return (int) Math.min(17, days * CHILD_YEARS_A_DAY);
        return (int) (18 + Math.max(0, days - GROW_DAYS) * YEARS_A_DAY);
    }

    /** The age it will live to: seventy to a hundred. */
    public int lifespan() { return 70 + Math.floorMod(getUUID().hashCode() >> 5, 31); }

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

    /** Tests only: a child born this many days ago. */
    public void bornDaysAgo(long days) { bornDay = level().getDayTime() / 24000L - days; }

    /** The bed in a house the village built for a player is that player's. */
    @Override
    protected boolean bedOnOffer(BlockPos pos) {
        if (!super.bedOnOffer(pos)) return false;
        UUID village = ownerId();
        return village == null || !Villages.inAGuestHouse(village, pos);
    }

    /** Hand two rations to a friend: whatever food is in the pack, as it is. */
    private boolean shareARation(VillageFolkEntity friend) {
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> pack = getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            net.minecraft.world.item.ItemStack st = pack.get(i);
            if (st.isEmpty() || st.get(net.minecraft.core.component.DataComponents.FOOD) == null) continue;
            int give = Math.min(2, st.getCount());
            net.minecraft.world.item.ItemStack left = friend.insertItem(st.copyWithCount(give));
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
        if (homeComfort(server)) return;              // its savings, spent on its home
        if (shopping(server)) return;                 // market day: a treat from the stalls
        if (lookRound(server)) return;                // the new building everybody is talking about
        if (cafeVisit(server)) return;                // a drink at the café
        if (shopVisit(server)) return;                // the shop: a tool for its work, or something nice
        if (Leisure.listen(this, server)) return;
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
        if (TownJobs.busy(this)) return true;                 // at the town's work (TownJobs)
        if (Raids.underAlarm(ownerId())) return false;       // the bell is ringing: no evening out
        long t = level().getDayTime() % 24000L;
        long bedtime = bedtimeTick();
        if (t < 12000L || t >= bedtime) return false;
        if (isBaby()) return false;
        if (familySupper(t)) return true;
        if (Tavern.evening(this, t)) return true;
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
            boolean adultWithout = false;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!a.isBaby() && a.bedPos() == null) { adultWithout = true; break; }
            }
            if (adultWithout) {
                if (near != null) setHome(near);
                return false;
            }
        }
        BlockPos from = near != null ? near : blockPosition();
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = server.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof net.minecraft.world.level.block.entity.BedBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    if (!bedOnOffer(p) || !bedFit(p)) continue;
                    double d = p.distSqr(from);
                    if (d < bestDist) { bestDist = d; best = p; }
                }
            }
        }
        if (best == null) return false;
        takeBed(best);
        return true;
    }

    /**
     * A bed down in the ground (a buried ruin's, a vault's) is nobody's home: three folk of one
     * village claimed one eighteen below sea level a hundred blocks off, could not walk to it, and
     * were set down beside it every night, to spend the morning climbing out.
     */
    @Override
    protected boolean bedFit(BlockPos bed) {
        if (!level().hasChunkAt(bed)) return true;                   // out of sight: as it was
        int surface = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            bed.getX(), bed.getZ());
        return bed.getY() >= surface - 8;
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

    @Override
    protected String debugExtra() {
        String social = " traits=" + life.traitsLabel().replace(' ', '-')
            + (life.partner() != null ? " partner=" + life.partnerName() : "")
            + " friends=" + life.friends().size();
        return social + (trail.length() == 0 ? "" : " trail=" + trail.toString().trim());
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
    @Override
    public boolean onShift() {
        // Called to the village's gathering: its work waits (the watch is never called away).
        if (Assemblies.attending(this)) return false;
        // On the town's own work (TownJobs): its trade waits till that is done.
        if (TownJobs.busy(this)) return false;
        // The bell: every guard turns out, whichever watch it keeps; nobody else works.
        UUID alarmed = ownerId();
        if (alarmed != null && Raids.underAlarm(alarmed)) return stationTask() == StationTask.GUARD;
        // The day of rest: nobody works but the watch.
        if (alarmed != null && stationTask() != StationTask.GUARD && RestDay.now(alarmed, level().getDayTime()) != null) return false;
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
        return 14000L + Math.floorMod(getUUID().getMostSignificantBits(), 600L)
            + (life.has(Social.Trait.SOCIABLE) ? 1500L : 0L) - (life.has(Social.Trait.HARDWORKING) ? 800L : 0L);
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
        if (heart.distSqr(blockPosition()) > 48.0 * 48.0) return false;     // only those who are home
        // Rations, not seed: a farmer's carrots and potatoes are for the ground.
        int have = countMatching(st -> st.get(net.minecraft.core.component.DataComponents.FOOD) != null
            && !(stationTask() == StationTask.FARM
                && (st.is(net.minecraft.world.item.Items.CARROT) || st.is(net.minecraft.world.item.Items.POTATO))));
        // A day's meals, not two: what is in a pack is not in the stores, and the village
        // reads its larder (and decides to raise children) from the stores.
        if (have >= 6) return false;
        int radius = Math.min(112, Math.max(32, Villages.storesRadius(village)));
        if (findChestWithNear(heart,
                com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor("food"), radius) == null) {
            return false;
        }
        rationTick = tickCount;
        enqueue(Job.withdrawAt("food", 8 - have, heart, radius));
        noteGate("evening: taking on " + (8 - have) + " rations");
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
                    || st.is(net.minecraft.world.item.Items.POTATO) || st.is(net.minecraft.world.item.Items.BEETROOT_SEEDS)) < 8
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
            if (isSleeping()) {                        // asleep: nothing until morning
                persona.sleptInABed(level().getDayTime() / 24000L);
                return;
            }
            // Everybody is at the heart after dark, with nothing to do — which is
            // exactly when the wheat gets baked. Indoors, next to the stores,
            // one errand at a time for the whole village.
            if (peekJob() == null && getNavigation().isDone()) {
                if (!restockRations()) bakeErrand();
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
        // A carrier's first work once the storehouse stands: the old chests, into it.
        if (stationTask() == StationTask.HAUL && peekJob() == null
                && level() instanceof net.minecraft.server.level.ServerLevel clearing
                && retireAChest(clearing, ownerId())) return;
        mindTheRoute();                                // a carrier's round is chosen, not clicked
        workInTheBuilding();                           // the smelter in the smeltery, the storekeeper in the storehouse
        putBackIfLost();
        // Tools from the stores, busy or not: they are made on the spot, and a miner
        // almost always has a mine job queued — so behind the busy check below, a miner
        // whose pickaxe had worn out stood "needing a pickaxe" for days.
        pickaxeFromTheStores();                        // the iron, and then the diamond, pickaxe
        shearsFromTheStores();                         // a rancher's shears, for the wool
        rodFromTheStores();                            // a fisher's rod, of the stores' string and wood
        stoneToolFromTheStores();                      // no more wooden tools once there is stone
        guardKitFromTheStores();                       // the smith's iron armour and sword, on the watch
        betterToolFromTheStores();                     // the smith's iron and the enchanter's work, in use
        clothesFromTheStores();                        // the tailor's boots
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
            if (Links.tend(this, supplies)) return;
        }
        // The mine's depth too: the next run digs at the new one. Behind the busy check
        // it never ran, and every mine staked in the Wood Age stayed at forty-odd for the
        // rest of the game — copper and coal by the hundred, iron one or two a day.
        if (seekTheSeam()) return;                     // dig where the village's metal is
        if (turnedToTheFields()) return;               // a hungry village needs farmers (busy or not)
        if (peekJob() != null) return;                 // already busy
        if (kitFromTheStores()) return;                // seed, saplings, torches, feed, arrows
        if (resting()) return;                         // off the clock for a bit
        if (movedOnFromSpentGround()) return;          // this patch is finished
        if (unstuckFromGround()) return;               // a plot that cannot be set up is given up
        if (changedTrade()) return;                    // the village lost a trade
        if (raisedAChild(12.0)) return;                // the village grew

        // A storekeeper works the chests directly and has nothing to haul: its
        // days were spent standing at the heart (two runs, two storekeepers, not
        // a stroke of work in three game days). It bakes and it builds.
        if (stationTask() == StationTask.STORE) tidyTheStorehouse();
        if (stationTask() == StationTask.STORE && idleHands()) return;
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
        if (zone == null || peekJob() != null || !onShift() || onBreak()
            || zone.containsColumn(blockPosition())) {
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

    /** The storekeeper sets the Village Storehouse in order once in a while: like with like,
     *  stacks topped up, in order — when it is there to do it. */
    private void tidyTheStorehouse() {
        if (tickCount - tidyTick < 6000 || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        com.jrpetty.mcassistant.block.StorehouseBlockEntity store = Storehouses.storeFor(server, ownerId());
        if (store == null || store.getBlockPos().distSqr(blockPosition()) > 10.0 * 10.0) return;
        tidyTick = tickCount;
        store.sort();
        note(Deed.CHESTS_SORTED, 1);
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "There — the storehouse is in order.",
            "Like with like. That's better.", "A tidy store is a happy village."));
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
        if (retireAChest(server, village)) return true;
        if (stashable() > 0) {
            sayRoutine("Nothing in my own line — taking this to the stores.");
            enqueue(storesDeposit());
            return true;
        }
        // Off to the woods or the quarry — but not the crafts, the storekeeper or the watch:
        // their work comes in bursts at their own bench, stand or post (a brew is twenty
        // seconds of waiting), and a brewer sent for timber between brews never brewed.
        StationTask trade = stationTask();
        if (trade.isCraft() || trade == StationTask.STORE || trade == StationTask.GUARD) return false;
        // Timber one time, stone the next, whichever has ground to get it from.
        com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind first = (tickCount / 2400) % 2 == 0
            ? com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS : com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE;
        com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind second = first == com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS
            ? com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE : com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS;
        for (var kind : java.util.List.of(first, second)) {
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

    /**
     * Ground with timber or stone on it: its own plot if that has some, else the nearest
     * woodcutter's or miner's of the village.
     */
    @Nullable
    private WorkZone groundFor(net.minecraft.server.level.ServerLevel server, UUID village,
                               com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind kind) {
        if (workZone() != null && workZone().containsColumn(blockPosition()) && resourceNearby(kind, 16)) return workZone();
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

        // Claim the trade BEFORE going to look for ground. The village works
        // out what it is short of from what its folk ARE, so a folk that has
        // decided but not yet settled used to be invisible — and every folk
        // in the village would pick the same trade, look for the same ground,
        // and fail together, for ever.
        if (stationTask() == StationTask.NONE) {
            setStation(blockPosition(), Villages.needed(ownerId()));
        }
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

    private static int radiusFor(StationTask trade) {
        return switch (trade) {
            case FARM -> 8;      // a field you can actually keep watered
            case WOOD -> 14;     // woodland is worked wide
            case MINE -> 8;
            case HUNT -> 20;     // hunting grounds are walked wide
            default -> 6;        // the smelter works at its furnaces
        };
    }

    private int depthFor(StationTask trade, BlockPos site) {
        if (trade != StationTask.MINE) return WorkZone.DEFAULT_DEPTH;
        int floor = level().getMinBuildHeight() + 8;
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
        if (at.ordinal() < Villages.Age.STONE.ordinal()) return false;
        int floor = level().getMinBuildHeight() + 8;
        int want = Math.max(floor, IRON_SEAM_Y);
        if (at.ordinal() >= Villages.Age.DIAMOND.ordinal() && pickTierCarried() >= 3 && deepMiner()) {
            want = floor;
        }
        if (zone.depth() <= want + 4) return false;              // there already, or deeper
        assignPlot(WorkZone.around(zone.center(), zone.radius(), want), patchNameFor(StationTask.MINE));
        setAutonomous(true);
        brain("mine taken down to Y" + want + " for the " + (want == floor ? "diamonds" : "iron"));
        return true;
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
     */
    private void guardKitFromTheStores() {
        if (stationTask() != StationTask.GUARD || tickCount - guardKitTick < 1200) return;
        guardKitTick = tickCount;
        UUID village = ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        net.minecraft.world.entity.EquipmentSlot[] slots = { net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET };
        net.minecraft.world.item.Item[] iron = { net.minecraft.world.item.Items.IRON_HELMET,
            net.minecraft.world.item.Items.IRON_CHESTPLATE, net.minecraft.world.item.Items.IRON_LEGGINGS,
            net.minecraft.world.item.Items.IRON_BOOTS };
        int put = 0;
        for (int i = 0; i < slots.length; i++) {
            net.minecraft.world.item.ItemStack worn = getItemBySlot(slots[i]);
            String wornPath = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(worn.getItem()).getPath();
            // An empty slot, or one with only leather, chainmail or gold in it.
            if (!worn.isEmpty() && !wornPath.startsWith("leather_") && !wornPath.startsWith("chainmail_")
                    && !wornPath.startsWith("golden_")) continue;
            final net.minecraft.world.item.Item piece = iron[i];
            net.minecraft.world.item.ItemStack got = Crafts.takeOne(server, v, st -> st.is(piece));
            if (got.isEmpty()) continue;
            if (!worn.isEmpty()) {
                net.minecraft.world.item.ItemStack off = worn.copy();
                net.minecraft.world.item.ItemStack left = insertItem(off);
                if (!left.isEmpty()) Crafts.store(server, v, left);
            }
            setItemSlot(slots[i], got);
            put++;
        }
        if (countCarried(st -> st.getItem() instanceof net.minecraft.world.item.SwordItem
                && !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith("wooden_")
                && !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith("stone_")
                && !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith("golden_")) == 0) {
            net.minecraft.world.item.ItemStack sword = Crafts.takeOne(server, v, st -> st.is(net.minecraft.world.item.Items.IRON_SWORD));
            if (!sword.isEmpty()) {
                net.minecraft.world.item.ItemStack left = insertItem(sword);
                if (!left.isEmpty()) Crafts.store(server, v, left);
                else put++;
            }
        }
        if (put > 0) brain("took " + put + " piece" + (put == 1 ? "" : "s") + " of the smith's iron from the stores");
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

    private int roundIndex = -1;
    private int roundTick = -100000;
    private int roundLegStart = -100000;

    /**
     * The night round: a guard on watch walks the ring street round the square, corner to corner
     * and past each gate, instead of only the corners of its own plot. A watch that keeps to its
     * plot meets only what comes to the plot; the streets are where the folk are coming home.
     */
    @Override
    protected boolean nightRound() {
        if (villageCentre == null || movementBlocked() || Raids.underAlarm(ownerId())) return false;
        if (!getNavigation().isDone()) {
            if (tickCount - roundLegStart < 200) return true;
            getNavigation().stop();
        } else if (tickCount - roundTick < 40) {
            return true;                                                  // a look round at each stop
        }
        int r = com.jrpetty.mcassistant.village.TownPlan.RING + 1;
        int[][] stops = { { -r, -r }, { 0, -r }, { r, -r }, { r, 0 }, { r, r }, { 0, r }, { -r, r }, { -r, 0 } };
        if (roundIndex < 0) roundIndex = Math.floorMod(getUUID().hashCode(), stops.length);   // the watch spread round the ring
        for (int i = 0; i < stops.length; i++) {
            int[] s = stops[Math.floorMod(roundIndex + i, stops.length)];
            int x = villageCentre.getX() + s[0], z = villageCentre.getZ() + s[1];
            int y = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (getNavigation().moveTo(x + 0.5, y, z + 0.5, 0.9D)) {
                roundIndex = Math.floorMod(roundIndex + i + 1, stops.length);
                roundTick = tickCount;
                roundLegStart = tickCount;
                return true;
            }
        }
        roundTick = tickCount;
        return false;
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

    private String patchNameFor(StationTask trade) {
        String base = switch (trade) {
            case FARM -> "Home Fields";
            case WOOD -> "East Wood";
            case MINE -> "The Pit";
            case SMELT -> "The Forge";
            case HUNT -> "Hunting Grounds";
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
        UUID town = ownerId();
        if (outdoor && town != null) reach = Math.max(reach, Villages.townReach(town) + radius + 8);
        java.util.function.Predicate<BlockPos> clear = p -> !outdoor || town == null
            || Villages.outsideTown(town, heart, p, radius);
        BlockPos from = heart.offset(
            (int) Math.round(Math.cos(angle) * reach), 0,
            (int) Math.round(Math.sin(angle) * reach));
        return switch (trade) {
            // One scan distance for every trade. The ring a village keeps
            // awake and the range its stores are read over are both derived
            // from how far a plot can end up, and neither can be reasoned
            // about while the woodcutter quietly reaches a third further than
            // everybody else.
            // A field goes by the nearest water to the village (just outside the town's own
            // ground), whichever way it lies: farmers used to look on their own bearing, fifty
            // blocks out and a scan beyond that, and walked seventy or eighty blocks to a pond
            // while there was a river by the town. No water anywhere near: the nearest good
            // soil, and the channel the field needs is cut to it.
            case FARM -> {
                BlockPos wet = nearestWaterField(heart, outdoor && town != null ? Villages.townReach(town) + radius + 2 : 8,
                    radius, clear);
                if (wet == null) wet = scan(from, SCAN, 6, radius, p -> clear.test(p) && farmable(p));
                yield wet != null ? wet : scan(from, SCAN, 6, radius, p -> clear.test(p) && soilField(p));
            }
            case WOOD -> scan(from, SCAN, 6, radius, p -> clear.test(p) && woodland(p));
            case MINE -> scan(from, SCAN, 6, radius, p -> clear.test(p) && diggable(p));
            // A pen goes where the animals already are and a jetty goes on
            // water — both were staking the village square, where a rancher
            // found nothing to breed and a fisher nothing to cast into, and
            // both trades were a silent no-op for the life of the settlement.
            // A pasture with animals on it; failing that, open meadow to bring them home to (Drover).
            case RANCH -> {
                BlockPos grazed = scan(from, SCAN, 6, radius, p -> clear.test(p) && pasture(p));
                yield grazed != null ? grazed : scan(from, SCAN, 6, radius, p -> clear.test(p) && meadow(p));
            }
            case FISH -> scan(from, SCAN, 6, radius, p -> clear.test(p) && fishable(p));
            // The hives go out on open grass, where there is room for flowers.
            case BEEKEEP -> scan(from, SCAN, 6, radius, p -> clear.test(p) && meadow(p));
            // Hunting grounds: wild country with game on it, out past the fields and pastures; failing
            // that, open grass or woodland, where game wanders through.
            case HUNT -> {
                BlockPos game = scan(from, SCAN, 8, radius, p -> clear.test(p) && gameAround(p));
                yield game != null ? game : scan(from, SCAN, 8, radius, p -> clear.test(p) && (meadow(p) || woodland(p)));
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
            if (level().getBlockState(p).is(Blocks.WATER) && ++water >= 12) return true;
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

    // ------------------------------ the carrier's round ---------------------

    private int routeTick = -100000;
    private int storesFullTick = -100000;

    /**
     * A hauler's round, chosen rather than clicked. There is no wand and no
     * player in a settlement, and the freight checklist will not let a carrier
     * move so much as a loaf until both ends of a route stand — so a village's
     * carrier would have been a permanent no-op.
     *
     * <p>The round a carrier would pick for itself: load wherever the goods
     * are actually piling up — the field chest, the woodpile, the mine head —
     * and unload at the storehouse in the middle. Re-read every couple of
     * minutes, because the fullest chest in a working village is a different
     * chest by the afternoon.
     */
    private void mindTheRoute() {
        if (stationTask() != StationTask.HAUL) return;
        if (tickCount - routeTick < 1200) return;
        routeTick = tickCount;
        BlockPos heart = villageCentre;
        UUID village = ownerId();
        if (heart == null || village == null || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;

        // The depot is the storehouse: its chests first, then the founding chest and the
        // other stores round the square — whichever has room (Villages.depot). It used to be
        // the one chest nearest the middle, which was the founding chest, every time; the
        // storehouse was never filled and the founding chest was full by the second day.
        BlockPos depot = Villages.depot(server, village);
        if (depot == null) {
            if (tickCount - storesFullTick > 4800) {
                storesFullTick = tickCount;
                say("The stores are full — the village needs another storehouse chest.");
            }
            return;
        }
        // The load is the fullest chest near THIS carrier's own post, not the
        // fullest in the settlement. A town of a hundred has several carriers
        // and reaches two hundred blocks out; one shared answer would have put
        // every one of them on the same chest and left three quarters of the
        // place uncollected — and a scan of the whole town, per carrier, every
        // two minutes, is a bill nobody wants to pay either. Each has its own
        // post on its own bearing, so a sector each falls out of it.
        // Everything worth fetching, out from the heart as far as the village's plots go:
        // the fields', woods' and mines' chests, and what the furnaces have made (their output
        // was taken out only while a smelt was running, and otherwise sat there for good).
        // Never the stores themselves, and never a player's guest house.
        int reach = Math.min(112, Math.max(48, Villages.storesRadius(village)));
        java.util.List<BlockPos> loads = new java.util.ArrayList<>();
        java.util.List<Integer> worth = new java.util.ArrayList<>();
        for (ZoneChests.Found f : ZoneChests.around(level(), heart, reach, 32)) {
            if (!f.stillThere()) continue;
            boolean furnace = f.blockEntity() instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
            if (!furnace && (!ZoneChests.isStashable(f) || Villages.inStoreArea(village, f.pos()))) continue;
            if (Villages.inAGuestHouse(village, f.pos())) continue;
            Integer spent = spentPickups.get(f.pos().asLong());
            if (spent != null && tickCount - spent < 2400) continue;          // just came up empty: the next one
            int held = furnace ? furnaceOutput(f) : stockIn(f);
            if (held < (furnace ? 4 : 24)) continue;      // a handful is not worth the walk
            int at = 0;
            while (at < worth.size() && worth.get(at) >= held) at++;
            loads.add(at, f.pos().immutable());
            worth.add(at, held);
        }
        if (loads.isEmpty()) return;
        // Several carriers share the round: each takes the next fullest after the ones
        // before it, so they are not all on the same chest.
        int rank = 0;
        for (AssistantEntity mate : Villages.folkOf(village)) {
            if (mate != this && mate.stationTask() == StationTask.HAUL && mate.getUUID().compareTo(getUUID()) < 0) rank++;
        }
        setHaulRoute(loads.get(rank % loads.size()), depot);
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
        String building = buildingFor(trade);
        if (building == null) return;
        if (tickCount - buildingCheckTick < 1200) return;
        buildingCheckTick = tickCount;
        UUID village = ownerId();
        if (village == null) return;
        BlockPos at = Villages.builtAt(village, building);
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
        return stationTask() == StationTask.GUARD && Raids.underAlarm(ownerId());
    }

    @Override
    protected boolean watchDuty() {
        return Raids.guardDuty(this);
    }

    /** No bed of its own when the bell rings: into the nearest of the village's buildings. */
    @Override
    protected boolean bedtime() {
        if (Raids.underAlarm(ownerId()) && bedPos() == null && !isBaby()) return Raids.shelter(this);
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
        if (from != null) Villages.recordDeath(from);
        joinVillage(to.id(), to.centre());
        Villages.recordBirth(to.id());
        BlockPos at = Contentment.arrival(level, to, getRandom());
        teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        persona.remember(day, "I left " + (from == null ? "home" : Villages.name(from)) + " for " + Villages.name(to.id()), 9);
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "A fresh start.", "Hello! I've come to live here.", "I hope it's better here."));
    }

    /** Leave for good, with nowhere to go (Contentment): out of the world. */
    public void walkOut() {
        FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I can't stay here any longer. Goodbye.", "I'm off to find a better life."));
        discard();
    }

    private static final net.minecraft.resources.ResourceLocation OLD_GAIT =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "old_age");

    /** The old walk a little slower. */
    private void refreshOldAgeGait() {
        var speed = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        if (isOld()) {
            speed.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(OLD_GAIT, -0.15D,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else {
            speed.removeModifier(OLD_GAIT);
        }
    }

    /** The crafts' work (Crafts, Cafe): a piece at a time, out of the stores and back. */
    @Override
    protected boolean craftWork() {
        return level() instanceof net.minecraft.server.level.ServerLevel server && Crafts.work(this, server);
    }

    /**
     * Never the last of a kind: a cow, pig, sheep, chicken or rabbit with fewer than three of its
     * own kind grown within twenty-four blocks is left to breed. The hunters take the spare ones,
     * and there is game again next year.
     */
    @Override
    public boolean spareForBreeding(net.minecraft.world.entity.animal.Animal a) {
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

    /** Tests: a beat of the hunter's day, as the station brain would run it. */
    public boolean huntForTests() {
        return huntWork();
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
        // Ore and fuel for the smelter, carried out to it.
        if (haulFor != null) {
            if (!(server.getEntity(haulFor) instanceof VillageFolkEntity smelter) || !smelter.isAlive()
                    || countCarried(AssistantEntity.SMELTABLE_ORE) == 0) {
                haulFor = null;
            } else if (distanceToSqr(smelter) > 3.0 * 3.0) {
                walkTo(smelter.blockPosition(), 1.1D);
                hobbyNow = "taking ore to " + smelter.displayNameCap();
                return true;
            } else {
                int given = handOver(smelter, s -> AssistantEntity.SMELTABLE_ORE.test(s)
                    || s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL));
                haulFor = null;
                if (given > 0) {
                    note(Deed.LOADS_HAULED, 1);
                    FolkTalk.speak(this, FolkTalk.pick(getRandom(), "Ore for the furnaces, " + smelter.displayNameCap() + "!", "Here — keep those fires going."));
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
        // The smelter running low, and ore in the stores.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.SMELT || !f.isAlive()) continue;
            if (f.countCarried(AssistantEntity.SMELTABLE_ORE) >= 8) continue;
            int got = drawFrom(villageCentre, AssistantEntity.SMELTABLE_ORE, 32, buildStoresRadius());
            if (got <= 0) break;
            drawFrom(villageCentre, s -> s.is(net.minecraft.world.item.Items.COAL) || s.is(net.minecraft.world.item.Items.CHARCOAL), 8, buildStoresRadius());
            haulFor = f.getUUID();
            brain("taking " + got + " ore out to " + f.displayNameCap());
            return true;
        }
        return false;
    }

    /** A worker's load (what it would bank: its output, not its kit) into this carrier's pack. */
    private int takeLoadFrom(VillageFolkEntity worker) {
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
            net.minecraft.world.item.ItemStack left = to.insertItem(s.copy());
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

    /** A village's storekeeper keeps its stores in order from the first day, not from its
     *  tenth level: nothing else a storekeeper does earns it the experience to get there. */
    @Override
    public boolean can(Ability a) {
        if (a == Ability.STORE_SORT && stationTask() == StationTask.STORE) return true;
        // A village's farmers feed its fields with bone meal (the watch's bones, the compost).
        if (a == Ability.FARM_BONEMEAL && stationTask() == StationTask.FARM) return true;
        return super.can(a);
    }

    /** What a furnace has made and nobody has taken out, weighted like a chest's goods. */
    private int furnaceOutput(ZoneChests.Found f) {
        if (!(f.blockEntity() instanceof net.minecraft.world.Container box) || box.getContainerSize() < 3) return 0;
        net.minecraft.world.item.ItemStack st = box.getItem(2);
        return st.getCount() * Math.max(1, haulWeight(st));
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

    /** How much is actually sitting in this chest. */
    /** What a chest is worth a trip: its goods weighted by how much the village
     *  wants them (stone, timber, ore over wheat over odds and ends), and its
     *  trade stock — seeds, saplings, tools — not counted at all. */
    private int stockIn(ZoneChests.Found f) {
        if (!(f.blockEntity() instanceof net.minecraft.world.Container box)) return 0;
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack st = box.getItem(i);
            n += st.getCount() * haulWeight(st);
        }
        return n;
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
        int floor = level().getMinBuildHeight() + 8;
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
        if (!onShift() || onBreak() || !workedOut()) { spentSince = 0; return false; }
        if (spentSince == 0) { spentSince = tickCount; return false; }
        // Three solid minutes of finding nothing: long enough that a slow
        // patch is not abandoned, short enough that nobody stands in a
        // clearing all afternoon.
        if (tickCount - spentSince < 3600) return false;
        spentSince = 0;
        // Look somewhere ELSE. findSite is deterministic and a mined-out patch
        // still looks like perfectly good stone from the surface, so searching
        // the same way returns the same spent ground every time. Turning the
        // bearing and standing further off is what actually moves them on.
        avoidHere = workZone();
        searchBearing++;
        BlockPos site = findSite(trade, radiusFor(trade));
        avoidHere = null;
        if (site == null) return false;
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
        if (tickCount - breedCheckTick < 6000) return false;     // five minutes apiece
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
        if (getRandom().nextInt(odds) != 0) return false;
        return raiseChildWith(partner) != null;
    }

    /**
     * Raise a child with this partner, now: what it costs, who it is, whose it is.
     * The village's rules about when (room, food put by, the pace of births) are
     * {@link #raisedAChild}'s; this is the raising itself.
     */
    @Nullable
    public VillageFolkEntity raiseChildWith(VillageFolkEntity partner) {
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

        VillageFolkEntity child = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get()
            .create(server);
        if (child == null) return null;
        child.moveTo(getX(), getY(), getZ(), getYRot(), 0.0F);
        child.rename(Names.freeFor(village));
        // Less than its parents spent on it — see childKit. A village that
        // could breed its way to a full larder would never have to farm.
        com.jrpetty.mcassistant.VillageSpawner.childKit(child);
        // BORN INTO THIS VILLAGE, not left to go and look for one. A newborn
        // settles by finding the nearest settlement within ninety-six blocks,
        // and it is born wherever its parents were STANDING — which is out on
        // their plot, which as a village grows is most of ninety-six blocks
        // from the heart already. A child born a step too far would have
        // FOUNDED A RIVAL VILLAGE on top of its own parents, split the
        // headcount, and set both halves back to the Wood Age. It is given the
        // village it was born into, and its agenda picks up from there.
        child.joinVillage(village, villageCentre);
        // A family: the two who raised it are partners from now on, and the child
        // takes after one of them and knows whose it is.
        long bornOn = level().getDayTime() / 24000L;
        if (life.partner() == null && partner.life.partner() == null) {
            Villages.tell(village, bornOn, displayNameCap() + " and " + partner.displayNameCap() + " are together now");
        }
        if (life.partner() == null) life.partnerWith(partner.getUUID(), partner.displayNameCap());
        if (partner.life.partner() == null) partner.life.partnerWith(getUUID(), displayNameCap());
        life.hadAChild();
        partner.life.hadAChild();
        child.life.roll(getRandom(), life, partner.life);
        child.life.setParents(displayNameCap(), partner.displayNameCap());
        child.life.feel(getUUID(), displayNameCap(), 70);
        child.life.feel(partner.getUUID(), partner.displayNameCap(), 70);
        life.feel(child.getUUID(), child.displayNameCap(), 70);
        partner.life.feel(child.getUUID(), child.displayNameCap(), 70);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
            getX(), getY() + 2.0, getZ(), 6, 0.6, 0.3, 0.6, 0.0);
        server.addFreshEntity(child);
        Villages.recordBirth(village);
        child.bornDay = bornOn;
        child.setChild(true);
        Villages.tell(village, bornOn, displayNameCap() + " and " + partner.displayNameCap() + " had a child, " + child.displayNameCap());
        persona.remember(bornOn, "my child " + child.displayNameCap() + " was born", 9);
        partner.persona.remember(bornOn, "my child " + child.displayNameCap() + " was born", 9);
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
        avoidHere = null;
        if (site == null) return false;
        setStation(site, vacancy);
        assignPlot(WorkZone.around(site, radiusFor(vacancy), depthFor(vacancy, site)),
            patchNameFor(vacancy));
        setAutonomous(true);
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
        return trip != null || expedition != null || Drover.busy(this) || Nether.away(this) || breakNow();
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
        long day = level().getDayTime() % 24000L;
        long bits = getUUID().getLeastSignificantBits();
        // Partners take their break together: both work it out from the same one of
        // their two ids, so it is the same hour for both.
        UUID partner = life.partner();
        if (partner != null && partner.getLeastSignificantBits() < bits) bits = partner.getLeastSignificantBits();
        long start = 1000L + Math.floorMod(bits, 8000L);
        long length = 1200L + Math.floorMod(bits >>> 24, 1200L);
        if (life.has(Social.Trait.HARDWORKING)) length /= 2;
        if (life.has(Social.Trait.EASYGOING)) length = length * 3 / 2;
        return day >= start && day < start + length;
    }

    private boolean resting() {
        if (!breakNow()) return false;
        socialise();
        return true;
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
        String project = Villages.nextProject(village);
        if (project == null) { buildNote("build: nothing wanted"); return; }
        // Only a folk standing near the village heart takes the job on — the
        // buildings go up where people live, not wherever the volunteer was.
        if (villageCentre.distSqr(blockPosition()) > 40.0 * 40.0) { buildNote("build: too far from the heart"); return; }
        // Ground for it, picked once and kept: a build interrupted at dusk must
        // pick up where it left off, not start again somewhere else.
        Villages.Site site = Villages.siteFor(server, village, project);
        if (site == null) {
            buildNote("build: no lot for the " + project + " (" + Villages.lotReport(village) + ")");
            // Set aside, so the next thing on the list goes up meanwhile (Villages.defer).
            Villages.defer(village, project, now + 6000L);
            Villages.retryShortly(village, now);
            return;
        }
        // No point taking charge of a building the village cannot yet afford —
        // the lead does not lend itself out while it holds the post.
        if (!affordsTimberFor(project, site)) {
            buildNote("build: cannot afford the " + project);
            // Something cheaper further down the list may be affordable now: the smeltery
            // need not wait while the stone for the wall piles up.
            Villages.defer(village, project, now + 2400L);
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
            if (stuckOnAPart) Villages.defer(village, project, now + 2400L);
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
        int blocks = BuildGoal.partCounts(project, site.radius()).getOrDefault(BuildGoal.Part.BLOCK, 0);
        blocks += blocks / 10 + 2;
        // A hillside takes stone to build up to the floor.
        if (!project.equals("fortify")) {
            int[] g = BuildGoal.footprint(project);
            blocks += BuildGoal.fillCells(level(), site.anchor(), site.facing(), g[0], g[1]).size();
        }
        int carried = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        // Three parts in four is enough to begin: the rest is dug while the walls go up, and
        // a build that waited for every last block stood in front of its list for days.
        int least = blocks * 3 / 4;
        return carried >= least
            || carried + storesHold(heart, buildStoresRadius(), BuildGoal::isBuildingBlock) >= least;
    }

    /** How far from the heart the builder reads and draws on the stores: as far
     *  as the village's own plan counts them, not the forty-eight blocks round
     *  the storehouse — the woodpile is in the woods and the stone is at the mine,
     *  and a plan that counted them while the builder could not reach them left
     *  the village "short of timber" beside a full chest for days. */
    private int buildStoresRadius() {
        UUID village = ownerId();
        return Math.min(112, Math.max(48, village == null ? 48 : Villages.storesRadius(village)));
    }

    /** How much of this the village's stores hold near its heart. */
    private int storesHold(BlockPos heart, java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return storesHold(heart, 48, what);
    }

    private int storesHold(BlockPos heart, int radius,
                           java.util.function.Predicate<net.minecraft.world.item.ItemStack> what) {
        return ZoneChests.countIn(
            ZoneChests.around(level(), heart, radius, 32).stream()
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
        java.util.Map<BuildGoal.Part, Integer> still = null;
        if (level() instanceof net.minecraft.server.level.ServerLevel server
                && Land.areaLoaded(server, site.anchor(), Math.max(8, site.radius()))) {
            still = new java.util.EnumMap<>(BuildGoal.Part.class);
            for (BuildGoal.Placement p : BuildGoal.plan(project, site.anchor(), site.facing(), site.radius())) {
                if (BuildGoal.soft(server.getBlockState(p.pos()))) still.merge(p.part(), 1, Integer::sum);
            }
        }
        if (Storehouses.stands(village)) need.remove(BuildGoal.Part.STOREHOUSE);
        int blocks = need.getOrDefault(BuildGoal.Part.BLOCK, 0);
        blocks += blocks / 10 + 2;                              // a margin for the cells that are lost
        if (!project.equals("fortify")) {                       // and the ground to build up
            int[] g = BuildGoal.footprint(project);
            blocks += BuildGoal.fillCells(level(), site.anchor(), site.facing(), g[0], g[1]).size();
        }

        // The right things for a drawn building first: stone for its footing, planks for its
        // walls, logs for its frame, and the stairs and slabs of its roof cut from the planks.
        int shaped = stockStyles(project, heart);
        if (shaped > 0) { Villages.leadProgress(village, getUUID(), now); drewForBuild = true; }

        // Timber and stone: only worth a trip if the village has enough.
        int carried = countCarried(BuildGoal::isBuildingBlock) + shaped;
        int least = blocks * 3 / 4;                             // enough to begin with: see affordsTimberFor
        if (carried < blocks) {
            int inStores = storesHold(heart, buildStoresRadius(), BuildGoal::isBuildingBlock);
            if (carried + inStores < least) { buildNote("build: stores hold " + inStores + ", need " + (least - carried)); return false; }      // not yet
            // The cheapest first: stone before planks, planks before logs.
            int got = 0;
            for (int tier = 0; tier <= 2 && got < blocks - carried; tier++) {
                final int cost = tier;
                got += drawFrom(heart, st -> BuildGoal.isBuildingBlock(st) && BuildGoal.blockCost(st) == cost,
                    blocks - carried - got, buildStoresRadius());
            }
            if (got > 0) { Villages.leadProgress(village, getUUID(), now); drewForBuild = true; }
        }

        // The fixtures — a chest, a furnace, a bench, ladders, fences — from the
        // stores if they are there, made if they are not. One craft a visit.
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
                BuildGoal.Part.NOTE_BLOCK)) {
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
            // And without the wool, the founders' bedding comes in from the camp.
            if (deco == BuildGoal.Part.BED && have < want) bedsFromTheCamp(want - have);
        }
        // The roof's stairs and slabs count: they are cut from the planks and laid in place of
        // blocks. Counted without them, a builder that had cut its roof out of the founding planks
        // was always "carrying 128 of 216", never began, and a village of twelve built nothing.
        int blocksNow = countCarried(BuildGoal::isBuildingBlock) + roofPiecesCarried();
        if (blocksNow < least) buildNote("build: carrying " + blocksNow + " of " + blocks + " blocks");
        return blocksNow >= least;
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
        topUp(heart, BuildGoal::isStoneLike, stone, r);
        topUp(heart, st -> st.is(net.minecraft.tags.ItemTags.LOGS), logs, r);
        // The roof first, out of the planks: it is the thing that makes a building look like one.
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> woodStairs = st -> st.is(net.minecraft.tags.ItemTags.WOODEN_STAIRS);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> woodSlabs = st -> st.is(net.minecraft.tags.ItemTags.WOODEN_SLABS);
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
            case GRINDSTONE -> { return makeFromStores(net.minecraft.world.item.Items.GRINDSTONE, wanted, heart, r, 3,
                java.util.Map.entry(COBBLE, 1)); }
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
            case ENCHANTING -> { return makeFromStores(net.minecraft.world.item.Items.ENCHANTING_TABLE, wanted, heart, r, 0,
                java.util.Map.entry(BOOKS, 1), java.util.Map.entry(DIAMONDS, 2), java.util.Map.entry(OBSIDIAN, 4)); }
            default -> { return 0; }
        }
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
     * The camp round the heart is where the founders slept before there were houses.
     * A builder with a house to furnish and no wool to make beds takes theirs up and
     * carries them in: the camp empties into the houses as they go up.
     */
    private int bedsFromTheCamp(int wanted) {
        if (villageCentre == null) return 0;
        int took = 0;
        for (BlockPos head : com.jrpetty.mcassistant.VillageSpawner.campBeds(level(), villageCentre)) {
            if (took >= wanted) break;
            net.minecraft.world.level.block.state.BlockState st = level().getBlockState(head);
            if (!(st.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                    || st.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) continue;
            BlockPos foot = head.relative(st.getValue(net.minecraft.world.level.block.BedBlock.FACING).getOpposite());
            net.minecraft.world.item.ItemStack bed = new net.minecraft.world.item.ItemStack(st.getBlock().asItem());
            level().setBlock(foot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
            level().setBlock(head, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            net.minecraft.world.item.ItemStack left = insertItem(bed);
            if (!left.isEmpty()) spawnAtLocation(left);
            took++;
        }
        if (took > 0) brain("took " + took + " bed" + (took == 1 ? "" : "s") + " in from the camp");
        return took;
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
        if (food * 2 >= Villages.larderForBirth(village)) return false;
        int surplus = mine == StationTask.MINE
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

    @Override
    protected boolean wantsGlass() {
        UUID village = ownerId();
        return village != null && level() instanceof net.minecraft.server.level.ServerLevel server
            && Market.stock(server, village, s -> s.is(net.minecraft.world.item.Items.GLASS)
                || s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)) < 16;
    }

    @Override
    protected boolean burnCharcoal() {
        UUID village = ownerId();
        if (village == null || villageCentre == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;
        if (tickCount - charcoalCheckTick < 1200) return false;
        charcoalCheckTick = tickCount;
        boolean wanted = false;
        for (Villages.Need n : Villages.needs(server, village)) {
            if (n.task() == Villages.Task.COAL) { wanted = true; break; }
        }
        if (!wanted) return false;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> logs =
            st -> st.is(net.minecraft.tags.ItemTags.LOGS);
        int have = countCarried(logs);
        if (have < 16) have += drawFrom(villageCentre, logs, 16 - have, buildStoresRadius());
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
        tag.putLong("BornDay", bornDay);
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
        tag.putInt("Purse", purse);
        tag.putInt("PaidDeeds", paidDeeds);
        tag.putInt("EarnedInAll", earnedInAll);
        CompoundTag trades = new CompoundTag();
        for (java.util.Map.Entry<StationTask, Integer> e : tradeXp.entrySet()) trades.putInt(e.getKey().name(), e.getValue());
        tag.put("TradeXp", trades);
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
        this.bornDay = tag.contains("BornDay") ? tag.getLong("BornDay") : UNKNOWN;
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
