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
        if (village != null) Villages.noteProject(village, structure, level().getGameTime());
        drewForBuild = false;                     // what is left over is cargo again
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
                || s.is(net.minecraft.tags.ItemTags.BEDS)) {
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
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.CHEST), 1, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.FURNACE), 1, r);
        back += returnTo(villageCentre, st -> st.is(net.minecraft.world.item.Items.LADDER)
            || st.is(net.minecraft.tags.ItemTags.FENCES) || st.is(net.minecraft.tags.ItemTags.FENCE_GATES), 0, r);
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
        if (tickCount % 40 == 0) greetPassersBy();
        // Somebody is talking to it, or it is out walking with somebody: its own day
        // waits until they are done.
        boolean withAPlayer = talkPartner() != null || companionPlayer() != null;
        if (tickCount - agendaTick < 100) return;   // folk think slowly, on purpose
        agendaTick = tickCount;
        flyTheColours();
        if (!life.rolled()) life.roll(getRandom(), null, null);
        ensurePersona();
        refreshMood();
        dreamCameTrue();
        if (ownerId() != null) Villages.chooseElder(ownerId(), level().getDayTime() / 24000L);
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

    /** The player it is walking with, while it is; it heads home when the time is up
     *  or the player has gone too far ahead. */
    @Nullable
    public net.minecraft.world.entity.player.Player companionPlayer() {
        if (companion == null) return null;
        net.minecraft.world.entity.player.Player p = level().getPlayerByUUID(companion);
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
        if (stationTask() == StationTask.MINE) {
            return new net.minecraft.world.item.ItemStack(getRandom().nextInt(3) == 0
                ? net.minecraft.world.item.Items.AMETHYST_SHARD : net.minecraft.world.item.Items.COAL);
        }
        if (persona.hobby() == Persona.Hobby.WHITTLING) return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOWL);
        if (persona.hobby() == Persona.Hobby.MUSIC) return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NOTE_BLOCK);
        net.minecraft.world.item.Item[] flowers = {net.minecraft.world.item.Items.POPPY, net.minecraft.world.item.Items.DANDELION,
            net.minecraft.world.item.Items.CORNFLOWER, net.minecraft.world.item.Items.OXEYE_DAISY,
            net.minecraft.world.item.Items.ALLIUM, net.minecraft.world.item.Items.AZURE_BLUET};
        return new net.minecraft.world.item.ItemStack(flowers[getRandom().nextInt(flowers.length)]);
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
        why.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Object[] w : why) keys.add((String) w[0]);
        persona.setMood(m, keys);
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
        UUID village = ownerId();
        if (!level().isClientSide && village != null && !showcase) {
            long day = level().getDayTime() / 24000L;
            Villages.tell(village, day, displayNameCap() + " died");
            Gatherings.mourn(village, displayNameCap(), day);
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f == this) continue;
                int warmth = f.life.affinity(getUUID());
                if (warmth >= Social.FRIEND || getUUID().equals(f.life.partner())) {
                    f.persona.remember(day, "I lost " + displayNameCap(), 8);
                    f.persona.hurt(day);
                }
            }
        }
        super.die(cause);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new com.jrpetty.mcassistant.entity.goal.TalkGoal(this));
        this.goalSelector.addGoal(1, new com.jrpetty.mcassistant.entity.goal.CompanionGoal(this));
    }

    // ------------------------------ who they are, and who they like ----------

    private final Social.Life life = new Social.Life();
    private int beats;
    private long driftDay = -1;
    private int socialWalkTick = -1000;

    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_SOCIAL =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

    /** Which of the village colours its watch wears; -1 for a folk of no village. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_BANNER =
        net.minecraft.network.syncher.SynchedEntityData.defineId(
            VillageFolkEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SOCIAL, "");
        builder.define(DATA_BANNER, -1);
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
        String line = life.clientLine();
        if (persona.rolled()) {
            line += "|" + Persona.moodWord(persona.mood()) + "|" + persona.hobby().doing + "|"
                + (persona.ambitionMet() ? "done — " + persona.ambition().done : persona.ambition().hope);
        }
        if (!line.equals(this.entityData.get(DATA_SOCIAL))) this.entityData.set(DATA_SOCIAL, line);
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
            if (village != null) Villages.tell(village, day, displayNameCap() + " grew up");
            FolkTalk.speak(this, FolkTalk.pick(getRandom(), "I'm all grown up!", "Time I learned a trade.",
                "No more playing — I'm a grown-up now."));
            return;
        }
        if (level().isNight()) {
            if (!isSleeping() && peekJob() == null) bedtime();
            return;
        }
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server) || villageCentre == null) return;
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
        if (Leisure.listen(this, server)) return;
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
        long t = level().getDayTime() % 24000L;
        long bedtime = 14000L + Math.floorMod(getUUID().getMostSignificantBits(), 600L)
            + (life.has(Social.Trait.SOCIABLE) ? 1500L : 0L) - (life.has(Social.Trait.HARDWORKING) ? 800L : 0L);
        if (t < 12000L || t >= bedtime) return false;
        if (isBaby()) return false;
        if (Gatherings.attend(this, t)) return true;
        if (Leisure.evening(this, t)) return true;
        socialise();
        return true;
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
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = server.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof net.minecraft.world.level.block.entity.BedBlockEntity)) continue;
                    BlockPos p = be.getBlockPos();
                    if (!bedOnOffer(p)) continue;
                    double d = p.distSqr(blockPosition());
                    if (d < bestDist) { bestDist = d; best = p; }
                }
            }
        }
        if (best == null) return false;
        takeBed(best);
        return true;
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
        enqueue(Job.deposit());
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
                ? Math.min(112, Math.max(32, Villages.storesRadius(village))) : 32;
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

    /** The founding timber, stone and chests are the storehouse's until it stands
     *  (see Villages.storehouseFirst): a hand that wants spares waits, or makes
     *  do with what it carries. */
    private boolean storehouseHasFirstCall(String ask) {
        UUID village = ownerId();
        if (village == null) return false;
        if (!(ask.equals("plank") || ask.equals("log") || ask.equals("cobble") || ask.equals("chest"))) return false;
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
    private void agenda() {
        if (ownerId() == null) { settle(); return; }
        if (isBaby()) { childhood(); return; }
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
            // Home for the night is where two folk are at last near each other: a village of
            // two, one at its field and one at its mine all day, could never have a child.
            raisedAChild(24.0);
            // Then the evening with the others, and then bed.
            if (peekJob() == null) bedtime();
            return;
        }
        mindTheRoute();                                // a carrier's round is chosen, not clicked
        putBackIfLost();
        // Tools from the stores, busy or not: they are made on the spot, and a miner
        // almost always has a mine job queued — so behind the busy check below, a miner
        // whose pickaxe had worn out stood "needing a pickaxe" for days.
        pickaxeFromTheStores();                        // the iron, and then the diamond, pickaxe
        shearsFromTheStores();                         // a rancher's shears, for the wool
        stoneToolFromTheStores();                      // no more wooden tools once there is stone
        bucketFromTheStores();                         // a farmer's water, when the village is hungry
        obsidianFromLava();                            // the gateway's obsidian, made where the lava is
        // The mine's depth too: the next run digs at the new one. Behind the busy check
        // it never ran, and every mine staked in the Wood Age stayed at forty-odd for the
        // rest of the game — copper and coal by the hundred, iron one or two a day.
        if (seekTheSeam()) return;                     // dig where the village's metal is
        if (turnedToTheFields()) return;               // a hungry village needs farmers (busy or not)
        if (peekJob() != null) return;                 // already busy
        if (resting()) return;                         // off the clock for a bit
        if (movedOnFromSpentGround()) return;          // this patch is finished
        if (unstuckFromGround()) return;               // a plot that cannot be set up is given up
        if (changedTrade()) return;                    // the village lost a trade
        if (raisedAChild(12.0)) return;                // the village grew

        // A storekeeper works the chests directly and has nothing to haul: its
        // days were spent standing at the heart (two runs, two storekeepers, not
        // a stroke of work in three game days). It bakes and it builds.
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
            enqueue(Job.deposit());
            return true;
        }

        if (tickCount - lastHelpTick < 1200) return false;   // one attempt a minute, at most
        for (Villages.Need need : Villages.needs(server, village)) {
            if (takeOn(server, need)) { lastHelpTick = tickCount; return true; }
        }
        return false;
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
                enqueue(Job.deposit());
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
                if (stashable() > 0) { enqueue(Job.deposit()); return true; }
                return false;
            }
            case OBSIDIAN -> {
                // Nothing under diamond drops obsidian, so this is a job for
                // one miner in the whole village: the one that has been given
                // the pickaxe. It is down there already; the lava is what it
                // has been walking round for weeks.
                if (stationTask() != StationTask.MINE) {
                    if (stashable() > 0) { enqueue(Job.deposit()); return true; }
                    return false;
                }
                if (countCarried(st -> st.is(net.minecraft.world.item.Items.DIAMOND_PICKAXE)) == 0) {
                    return deepenShaft();
                }
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.OBSIDIAN,
                    Math.min(16, Math.max(4, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case LOGS -> {
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.LOGS,
                    Math.min(48, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
                return true;
            }
            case STONE -> {
                if (!resourceNearby(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE, 16)) return false;
                enqueue(Job.gather(com.jrpetty.mcassistant.entity.goal.GatherGoal.Kind.STONE,
                    Math.min(64, Math.max(16, need.amount()))));
                enqueue(Job.deposit());
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
                enqueue(Job.deposit());
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
                    enqueue(Job.deposit());
                    return true;
                }
                // Then hunt. A field takes days; a herd on the doorstep is
                // meat this afternoon, and a hungry village cannot wait for
                // wheat. Farmers stay on the field — the crop is the long
                // answer and somebody has to be planting it.
                if (stationTask() != StationTask.FARM && adultAnimalsNearby(24) >= 2) {
                    enqueue(Job.hunt(null, 3));
                    enqueue(Job.deposit());
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
            v = Villages.found(level(), blockPosition());
            say("There's good ground here. This'll do for a village.");
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
            default -> 6;        // the smelter works at its furnaces
        };
    }

    private int depthFor(StationTask trade, BlockPos site) {
        if (trade != StationTask.MINE) return WorkZone.DEFAULT_DEPTH;
        int floor = level().getMinBuildHeight() + 8;
        int shallow = site.getY() - 24;
        UUID village = ownerId();
        if (village != null && Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()) {
            return Math.max(floor, Math.min(shallow, IRON_SEAM_Y));
        }
        return Math.max(floor, shallow);
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
        returnTo(villageCentre, stone, stoneBefore, r);
        returnTo(villageCentre, plank, plankBefore, r);
    }

    private String patchNameFor(StationTask trade) {
        String base = switch (trade) {
            case FARM -> "Home Fields";
            case WOOD -> "East Wood";
            case MINE -> "The Pit";
            case SMELT -> "The Forge";
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
        BlockPos from = heart.offset(
            (int) Math.round(Math.cos(angle) * reach), 0,
            (int) Math.round(Math.sin(angle) * reach));
        return switch (trade) {
            // One scan distance for every trade. The ring a village keeps
            // awake and the range its stores are read over are both derived
            // from how far a plot can end up, and neither can be reasoned
            // about while the woodcutter quietly reaches a third further than
            // everybody else.
            case FARM -> scan(from, SCAN, 6, radius, this::farmable);
            case WOOD -> scan(from, SCAN, 6, radius, this::woodland);
            case MINE -> scan(from, SCAN, 6, radius, this::diggable);
            // A pen goes where the animals already are and a jetty goes on
            // water — both were staking the village square, where a rancher
            // found nothing to breed and a fisher nothing to cast into, and
            // both trades were a silent no-op for the life of the settlement.
            case RANCH -> scan(from, SCAN, 6, radius, this::pasture);
            case FISH -> scan(from, SCAN, 6, radius, this::fishable);
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
    private boolean pasture(BlockPos pos) {
        return level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            new net.minecraft.world.phys.AABB(
                pos.getX() - 8, pos.getY() - 5, pos.getZ() - 8,
                pos.getX() + 8, pos.getY() + 5, pos.getZ() + 8),
            a -> a.isAlive() && !a.isBaby()).size() >= 2;
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
        if (tickCount - routeTick < 2400) return;
        routeTick = tickCount;
        BlockPos heart = villageCentre;
        if (heart == null) return;

        // The depot is whatever chest sits closest to the middle. A tight look:
        // the storehouse is at the heart by definition.
        BlockPos depot = null;
        double depotDist = Double.MAX_VALUE;
        for (ZoneChests.Found f : ZoneChests.around(level(), heart, 32, 24)) {
            if (!ZoneChests.isStashable(f)) continue;      // a furnace is not a depot
            double d = f.pos().distSqr(heart);
            if (d < depotDist) { depotDist = d; depot = f.pos(); }
        }
        // The load is the fullest chest near THIS carrier's own post, not the
        // fullest in the settlement. A town of a hundred has several carriers
        // and reaches two hundred blocks out; one shared answer would have put
        // every one of them on the same chest and left three quarters of the
        // place uncollected — and a scan of the whole town, per carrier, every
        // two minutes, is a bill nobody wants to pay either. Each has its own
        // post on its own bearing, so a sector each falls out of it.
        BlockPos post = stationPos() != null ? stationPos() : blockPosition();
        BlockPos load = null;
        int fullest = 0;
        // ...and far enough to reach the mines: the hill sixty blocks out is where
        // the stone is, and its chest is the fullest one there is.
        for (ZoneChests.Found f : ZoneChests.around(level(), post, 96, 32)) {
            if (!ZoneChests.isStashable(f)) continue;
            if (f.pos().equals(depot)) continue;           // never haul the depot to itself
            int held = stockIn(f);
            if (held > fullest) { fullest = held; load = f.pos(); }
        }
        // One chest in the whole village is a village with nothing to carry
        // between; leave the route alone and let the hand lend itself out. And a
        // handful of odds and ends is not worth the walk.
        if (depot != null && load != null && fullest >= 24) setHaulRoute(load, depot);
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
        if (!Villages.mayBirth(village, level().getGameTime())) return false;
        // Nor without a day's food put by: see Villages.larderForBirth.
        if (!Villages.larderFull(server, village)) return false;
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
        if (getRandom().nextInt(3) != 0) return false;
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
        if (vacancy == null || vacancy == mine) return false;
        if (!Villages.overStaffed(village, mine)) return false;

        avoidHere = workZone();          // do not simply re-stake my own field
        BlockPos site = findSite(vacancy, radiusFor(vacancy));
        avoidHere = null;
        if (site == null) return false;
        setStation(site, vacancy);
        assignPlot(WorkZone.around(site, radiusFor(vacancy), depthFor(vacancy, site)),
            patchNameFor(vacancy));
        setAutonomous(true);
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
        return breakNow();
    }

    /**
     * One break a working day, at an hour that is each folk's own. The first
     * version counted ticks since the last break, night included — so every
     * folk's clock had run out by morning and the whole village knocked off
     * together at every dawn, nine hands of nineteen standing about at once.
     * The hour comes from the folk's own id, so it is the same every day and
     * different from its neighbour's.
     */
    private boolean breakNow() {
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
        if (!project.equals("fortify")) blocks += BuildGoal.fillCells(level(), site.anchor(), BuildGoal.halfOf(project)).size();
        int carried = countCarried(BuildGoal::isBuildingBlock);
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
        int blocks = need.getOrDefault(BuildGoal.Part.BLOCK, 0);
        blocks += blocks / 10 + 2;                              // a margin for the cells that are lost
        if (!project.equals("fortify")) blocks += BuildGoal.fillCells(level(), site.anchor(), BuildGoal.halfOf(project)).size();   // and the ground to build up

        // Timber and stone: only worth a trip if the village has enough.
        int carried = countCarried(BuildGoal::isBuildingBlock);
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
                BuildGoal.Part.BED)) {
            int want = need.getOrDefault(deco, 0);
            if (want == 0) continue;
            var item = BuildGoal.itemForPart(deco);
            int have = countCarried(item);
            if (have < want) have += drawFrom(heart, item, want - have, buildStoresRadius());
            // Beds are made, not only found: three wool and three planks from the stores.
            if (deco == BuildGoal.Part.BED && have < want) makeBeds(want - have);
        }
        int blocksNow = countCarried(BuildGoal::isBuildingBlock);
        if (blocksNow < least) buildNote("build: carrying " + blocksNow + " of " + blocks + " blocks");
        return blocksNow >= least;
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
        if (ironFromTheStores(3)) {
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
            brain("a bucket of water from the stores, for the field");
        }
    }

    /** Since when this folk has been missing something its trade needs to start. */
    private int stuckSince = -1;

    /**
     * Ground that cannot be set up is given up. A farmer was staked on ground where its
     * chest would not go down ("no room beside me") and stood there sixteen game days
     * without turning a sod. A folk missing something for a whole working day looks
     * for new ground for the same trade somewhere else.
     */
    private boolean unstuckFromGround() {
        if (missingEssentials().isEmpty() || !onShift()) { stuckSince = -1; return false; }
        if (stuckSince < 0) { stuckSince = tickCount; return false; }
        if (tickCount - stuckSince < 12000) return false;
        stuckSince = -1;
        StationTask trade = stationTask();
        if (trade == StationTask.NONE || trade == StationTask.GUARD || trade == StationTask.HAUL
                || trade == StationTask.STORE) return false;
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
        if (ironFromTheStores(2)) {
            insertItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
            brain("shears made from the stores");
        }
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
            }
        }
    }
}
