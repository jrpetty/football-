package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * [pa33] The Player AI Assistant: a personal companion that belongs to one player, talks with them (in its own
 * talk box, by voice, in bubbles), takes orders by clicks, keys, the talk box or voice, and does what a player does:
 * gathers, mines, farms, crafts, smelts, builds from the mod's drawings, fights, fetches and keeps its own kit.
 *
 * <p>It is built on the original assistant engine (AssistantEntity and its shared goals) and is NOT one of the
 * village folk in any way: its own entity type, never a VillageFolkEntity, on nobody's crew register, in no town's
 * books, with its own saved data (PaData), its own screens and its own phrase bank. Its owner is a player.
 */
public class PlayerAssistantEntity extends AssistantEntity {

    private static final EntityDataAccessor<Integer> DATA_LOOK = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_OWNER = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.STRING);
    /** What it is doing, for the plate, the HUD and the panel: "icon|line|percent". */
    private static final EntityDataAccessor<String> DATA_STATE = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_PERS = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ANIM = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.INT);   // [pa33c-2b] a gesture: act | turn << 8
    private static final EntityDataAccessor<Integer> DATA_FOOD = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.INT);   // [pa33c-2b] its hunger bar, for the HUD
    /** [pa33c-2b] Its hunger bar (player rules) and where it was a tick ago (for what its walking costs). */
    public final PaHunger hunger = new PaHunger();
    @Nullable net.minecraft.world.phys.Vec3 lastPos;
    private static final EntityDataAccessor<Integer> DATA_MUTE = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.INT);   // [pa33b] for its nameplate
    private static final EntityDataAccessor<String> DATA_SHOWN = SynchedEntityData.defineId(PlayerAssistantEntity.class, EntityDataSerializers.STRING);

    private int slot;
    private long incarnation;
    private String ownerName = "";
    /** Its own queue of what it was asked, step by step (see PaPlan). */
    public final PaPlan plan = new PaPlan();
    /** A PA-only task under way (fuelling a furnace, placing a block, laying out a field...), or null. */
    @Nullable public PaTask task;
    /** The brain's scratch: what was last said, when, what it was doing (PaBrain). */
    public final PaBrain.Mind mind = new PaBrain.Mind();

    public PlayerAssistantEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LOOK, 0);
        builder.define(DATA_OWNER, "");
        builder.define(DATA_STATE, "");
        builder.define(DATA_PERS, 0);
        builder.define(DATA_MUTE, 0);
        builder.define(DATA_FOOD, 20);
        builder.define(DATA_ANIM, 0);
        builder.define(DATA_SHOWN, "");
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(2, new PaTaskGoal(this));
        this.goalSelector.addGoal(1, new PaCome.ComeGoal(this));          // [pa33g] told to come: before its work
        this.goalSelector.addGoal(3, new PaBowGoal(this));                 // [pa33g] its own archery (PaBow) in a fight
        this.goalSelector.addGoal(3, new PaCrossbowGoal(this));            // [pa35] and its crossbow (PaCrossbow)
    }

    /** Tests: the goals running just now, by name. */
    public String goalSelectorForTests() {
        StringBuilder b = new StringBuilder();
        this.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
            .forEach(g -> b.append(g.getGoal().getClass().getSimpleName()).append(' '));
        return b.toString().trim();
    }

    /** [pa33g] Its bow is drawn its own way (PaBowGoal, PaBow), never the town archers'. */
    @Override
    public boolean ownBow() {
        return true;
    }

    /** [pa33g] Told to come: on foot the whole way (PaCome). And with work in hand it stays at it: no trailing after its
     *  player (nor the catch-up hop) while it has a job here; it keeps its chunks awake instead (PaChunks). */
    @Override
    public boolean walkingToOwner() {
        return mind.come != null || task != null || !plan.idle() && !plan.paused
            || sleepWanted;                                             // on its way to bed: the follow pulled it back each time (pai46)
    }

    // ------------------------------------------------------------------ who it is

    public int slot() { return slot; }

    public long incarnation() { return incarnation; }

    public void bind(PaData.Rec r) {
        this.slot = r.slot;
        this.incarnation = r.incarnation;
        this.ownerName = r.ownerName;
        this.entityData.set(DATA_OWNER, r.ownerName);
        this.entityData.set(DATA_LOOK, r.look);
        this.entityData.set(DATA_PERS, r.personality);
        this.entityData.set(DATA_MUTE, r.mute);
        if (!r.name.isEmpty() && !r.name.equalsIgnoreCase(getAssistantName())) rename(r.name);
        this.entityData.set(DATA_SHOWN, displayNameCap());
    }

    /** Its record in the books (null on the client, or once it has been dismissed). */
    @Nullable
    public PaData.Rec rec() {
        if (level().isClientSide || getOwnerId() == null) return null;
        return PaData.find(getOwnerId(), slot);
    }

    public String ownerName() {
        return level().isClientSide ? this.entityData.get(DATA_OWNER) : ownerName;
    }

    public int look() { return this.entityData.get(DATA_LOOK); }

    /** Its name as shown over its head and in its panel (synced: the client has no other copy of it). */
    public String shownName() {
        if (!level().isClientSide) return displayNameCap();
        String n = this.entityData.get(DATA_SHOWN);
        return n.isEmpty() ? "Assistant" : n;
    }

    @Override
    public void rename(String newName) {
        super.rename(newName);
        if (!level().isClientSide) this.entityData.set(DATA_SHOWN, displayNameCap());
    }

    public void setLook(int look) { this.entityData.set(DATA_LOOK, look); }

    public PaPhrases.Personality personality() { return PaPhrases.Personality.of(this.entityData.get(DATA_PERS)); }

    /** [pa33b] Its place in the personality triangle (from its record; Banter when it has none). */
    public PaPersona.Tri tri() {
        PaData.Rec r = rec();
        if (r != null && r.mute > 0) return PaPersona.Tri.of(0, 0);          // [pa33b] hushed: the Serious corner's plain words
        return r == null ? PaPersona.Tri.of(PaPersona.DEFAULT_FUNNY, PaPersona.DEFAULT_GLOOMY) : r.tri();
    }

    public void setPersonality(int p) { this.entityData.set(DATA_PERS, p); }

    /** [pa33c-2b] Sleep in this bed from now on (its head block), as a player sets their spawn. */
    public void useBed(BlockPos head) {
        takeBed(head);
    }

    /** [pa33b] How hushed it is (0 talking, 1 no jokes, 2 quiet): its record on the server, its nameplate's copy on the client. */
    public int mute() {
        PaData.Rec r = rec();
        return r != null ? r.mute : this.entityData.get(DATA_MUTE);
    }

    /** [pa33b] Hush it, or lift it: kept in its record (saved) and shown on its nameplate. */
    public void setMute(int m) {
        PaData.Rec r = rec();
        int v = Math.max(0, Math.min(2, m));
        if (r != null) { r.mute = v; PaData.touch(); }
        this.entityData.set(DATA_MUTE, v);
    }

    /** "icon|line|percent": what it is doing, synced for the plate and the HUD. */
    public String state() { return this.entityData.get(DATA_STATE); }

    public void setState(String s) {
        if (!s.equals(this.entityData.get(DATA_STATE))) this.entityData.set(DATA_STATE, s);
    }

    /** Has its player pointed it at this chest? */
    public boolean pointedChest(BlockPos pos) {
        PaData.Rec r = rec();
        return r != null && r.pointed.contains(pos.asLong());
    }

    // ------------------------------------------------------------------ never turned against people

    /**
     * Its own player's knocks (a stray click, a swing in a fight) never hurt it and never make it hit back. Fights
     * are with monsters only: never a player, never anyone's assistant, crew or village folk, whatever hit it.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player p && isOwner(p)) {
            if (!level().isClientSide) PaBondAct.event(this, PaBond.Event.HIT);   // [pa35] the bond slips a little (capped a day)
            if (!level().isClientSide && tickCount - mind.lastHit > 400) {   // [pa33b] "Rude."
                mind.lastHit = tickCount;
                PaBanter.after(this, "quip.hit", null);
            }
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer sp && !level().isClientSide) PaBanter.stranger(this, sp, "", true);   // [pa33b] "Steady on."
        // [pa35] a monster's blow stepped out of, now and then, more often with its level (PaGrowth.dodge); never arrows, fire or falls
        if (!level().isClientSide && source.getDirectEntity() instanceof net.minecraft.world.entity.Mob m && source.getDirectEntity() == source.getEntity()
                && getRandom().nextDouble() * 100 < PaGrowth.dodge(veteranLevel())) {
            net.minecraft.world.phys.Vec3 away = position().subtract(m.position()).normalize().scale(0.35);
            setDeltaMovement(getDeltaMovement().add(away.x, 0.1, away.z));
            return false;
        }
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide) hunger.exhaust(0.1F);              // [pa33c-2b] a hit taken tires it, as a player
        if (hit && !level().isClientSide && isSleeping()) PaSleep.wake(this, "danger");   // [pa33d] up at once
        return hit;
    }

    @Override
    public void setTarget(@Nullable net.minecraft.world.entity.LivingEntity target) {
        boolean fresh = target != null && fairGame(target) && target != getTarget();
        super.setTarget(fairGame(target) && !PaMemoryAct.sparesEntity(this, target) ? target : null);   // [pa35] "don't kill my cows"
        if (fresh && !level().isClientSide) equipBestWeapon();      // [pa33b] sword out for a fight, as a player would
    }

    @Override
    public boolean canAttack(net.minecraft.world.entity.LivingEntity target) {
        return fairGame(target) && !PaMemoryAct.sparesEntity(this, target) && super.canAttack(target);   // [pa35]
    }

    /**
     * [pa33b] Whatever its player hands it, it uses, as a player would: the crew's rank gating on iron and diamond
     * tools is not for it (it was fighting with a pickaxe while an iron sword sat in its pack).
     */
    @Override
    public boolean mayUseTier(ItemStack s) {
        return PaLevels.canUse(this, s);                          // [pa35] the gear ladder (crossbow 5, diamond 6, netherite 9...)
    }

    /**
     * [pa35] Its bow and crossbow are its own, not a town guard's rank: the bow from the start, the crossbow by the gear
     * ladder (level 5). (The crew's rule asked for guard level 20 and 30, which its ten levels never reach: its bow
     * never came out in a fight.)
     */
    @Override
    public boolean mayShoot(ItemStack s) {
        if (s.is(net.minecraft.world.item.Items.BOW)) return true;
        if (s.is(net.minecraft.world.item.Items.CROSSBOW)) return PaLevels.canUse(this, s);
        return false;
    }

    /** [pa35] Armour by the gear ladder (up to iron at 4, diamond at 6, netherite at 9). */
    @Override
    public boolean mayWear(ItemStack s) {
        return PaLevels.canUse(this, s);
    }

    /** [pa35] Empty slots in the part of its pack open at its level. */
    public int packRoom() {
        net.minecraft.core.NonNullList<ItemStack> inv = getInventoryItems();
        int open = Math.min(inv.size(), PaPack.open(veteranLevel())), n = 0;
        for (int i = 0; i < open; i++) if (inv.get(i).isEmpty()) n++;
        return n;
    }

    /** May it fight this? Monsters and the like yes; people, assistants, crews and folk no. */
    public static boolean fairGame(@Nullable net.minecraft.world.entity.LivingEntity target) {
        return target == null || !(target instanceof Player || target instanceof AssistantEntity
            || target instanceof net.minecraft.world.entity.npc.AbstractVillager
            || target instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame());
    }

    /** [pa33c-2b] A build: planks, glass, a door, stairs, slabs, bricks, a bed, a chest or a table within two blocks (a stone hill has none). */
    @Override
    public boolean partOfBuild(BlockPos pos) {
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            net.minecraft.world.level.block.state.BlockState st = level().getBlockState(q);
            if (st.isAir()) continue;
            net.minecraft.world.level.block.Block b = st.getBlock();
            if (st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.DOORS) || st.is(net.minecraft.tags.BlockTags.STAIRS)
                || st.is(net.minecraft.tags.BlockTags.SLABS) || st.is(net.minecraft.tags.BlockTags.STONE_BRICKS) || st.is(net.minecraft.tags.BlockTags.BEDS)
                || b instanceof net.minecraft.world.level.block.TransparentBlock || b instanceof net.minecraft.world.level.block.IronBarsBlock
                || b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock || st.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE)
                || st.is(net.minecraft.world.level.block.Blocks.BRICKS)) return true;
        }
        return false;
    }

    /** Its player's "Break near your builds" setting, off by default: then it fells trees, not house beams. */
    @Override
    public boolean sparesBuilds() {
        PaData.Rec r = rec();
        return r == null || !r.breakNearBuilds;
    }

    /** May this player give it orders: its owner, or an operator. */
    public boolean obeys(Player p) {
        return isOwner(p) || (p instanceof ServerPlayer sp && sp.hasPermissions(2));
    }

    // ------------------------------------------------------------------ not a crew hand, not a folk

    @Override
    protected boolean onCrewRegister() { return false; }

    @Override
    public boolean levelsBuildSite() { return true; }

    @Override
    protected boolean plainNameTag() { return true; }

    /** It works at a player's pace, not a town hand's day-long one: a good third quicker at everything. */
    public static final int PLAYER_PACE = 40;

    @Override
    protected int skillWorkPercent() { return 0; }                  // [pa35] a player's pace comes from PaPace, not a bonus

    @Override
    protected void skillPaceParts(java.util.List<PacePart> parts) {
        addPacePart(parts, "a player's pace", PLAYER_PACE);
    }

    @Override
    protected boolean speaksInChat() { return false; }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    /** Everything it says goes to its own talk log, bubble and voice: never the chat. */
    @Override
    public void say(String message) {
        super.say(message);                          // keeps the last line for its debug readout; says nothing in chat
        if (!level().isClientSide) PaTalk.work(this, message);
    }

    /** Griefing safety on every block the work goals would take: a town's ground and other people's land are off. */
    @Override
    public boolean inZone(BlockPos pos) {
        return super.inZone(pos) && !PaSafety.offLimits(this, pos) && PaBrain.withinRoam(this, pos)
            && !PaMemoryAct.spared(this, pos)                          // [pa35] "don't break the blue flowers"
            && PaMineLock.allowed(this, pos);                          // [pa35] the mining ladder (PaLevels.canMine)
    }

    /** [pa35] The wood its player likes is chopped first; the wood they don't, only when there's nothing else. */
    @Override
    public double gatherRank(net.minecraft.world.level.block.state.BlockState st, double d) {
        return level().isClientSide ? d : PaMemoryAct.gatherRank(this, st, d);
    }

    @Override
    public boolean inZoneColumn(BlockPos pos) {
        return super.inZoneColumn(pos) && !PaSafety.offLimits(this, pos) && PaBrain.withinRoam(this, pos);
    }

    @Override
    public void tick() {
        PlayerAssistantEntity before = level().isClientSide ? null : PaChests.askAs(this);
        try {
            super.tick();
        } finally {
            if (!level().isClientSide) PaChests.askAs(before);
        }
        if (!level().isClientSide) PaPace.tick(this);                    // [pa35] the cracks, as a player's digging shows
    }

    // ------------------------------------------------------------------ [pa35] a real player's pace (PaPace)

    /** Breaking takes exactly a player's time with the tool in hand: no level buff, no level debuff. */
    @Override
    public int workTicksFor(net.minecraft.world.level.block.state.BlockState state) {
        int ticks = PaPace.breakTicks(this, state, level(), blockPosition());   // hardness reads the same anywhere for every vanilla block
        PaPace.began(this, ticks);
        return ticks;
    }

    /** Each hand action (planting, tilling, a stack moved...) about 0.6 s apart, never quicker. */
    @Override
    public int actionPaceTicks() {
        return PaPace.hand(this);
    }

    @Override
    public int actionPaceTicks(String toolKind) {
        return PaPace.hand(this);
    }

    /** A light touch is still a hand's action: the gap never goes under the cap. */
    @Override
    public void noteAction(float weight) {
        super.noteAction(1.0F);
    }

    /** Blocks laid at the hand's pace (in hundredths of a tick, as the builder counts). */
    @Override
    public int buildPaceHundredths() {
        return PaPace.hand(this) * 100;
    }

    /** A craft at the bench or a stack at a chest is one hand action; a wait the world sets (a fish's bite) is the world's. */
    @Override
    public int pacedTicks(int ticks, int sharePercent) {
        return sharePercent >= 100 ? PaPace.hand(this) : ticks;
    }

    /** The swing at a block tells the clock which block (for its cracks). */
    @Override
    public void workHit(BlockPos pos) {
        super.workHit(pos);
        if (!level().isClientSide) PaPace.hitting(this, pos);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && isAlive()) {
            PaVitals.tick(this);                                     // [pa33c-2b] hearts and hunger, as a player's
            if (animUntil > 0 && tickCount >= animUntil) { animUntil = 0; this.entityData.set(DATA_ANIM, (++animTurn << 8)); }
            PaBrain.tick(this);
        }
    }

    // ------------------------------------------------------------------ the player's hand on it

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(net.minecraft.world.item.Items.NAME_TAG) || held.is(net.minecraft.world.item.Items.LEAD)) {
            return super.mobInteract(player, hand);
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!obeys(sp)) {
            PaNet.openStranger(sp, this);                               // [pa33b] a little chat box: talk to it, never order it
            return InteractionResult.CONSUME;
        }
        // Food in the hand: a meal for it.
        if (held.get(net.minecraft.core.component.DataComponents.FOOD) != null && getHealth() < getMaxHealth()) {
            ItemStack one = held.split(1);
            insertGiven(one);
            eatFromPack();
            PaTalk.reply(this, "Thanks, that hit the spot.");
            return InteractionResult.CONSUME;
        }
        if (sp.isShiftKeyDown()) openManagementScreen(sp);             // sneak: straight to its pack
        else PaNet.open(sp, this, "last");                             // [pa33c-2b] the tab it was last on
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ [pa33d] bedtime

    /** It means to sleep now (PaSleep): the town folk's shift rule must not wake it. */
    public boolean sleepWanted;

    @Override
    public boolean onShift() {
        return !sleepWanted && super.onShift();
    }

    /** Asleep, it lies still (no goals walking it out of bed). */
    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || isSleeping();
    }

    // ------------------------------------------------------------------ [pa33d] its ten levels: the grind

    private long paXp;

    /** Its experience on its own ten-level ladder. */
    public long paXp() {
        return paXp;
    }

    /** Its level, 1..10, from its own ladder (the town folk's fifty rungs are not its). */
    @Override
    public int veteranLevel() {
        return PaLevels.level(paXp);
    }

    /** Paid for its work as anyone is (the deeds, the kills), and onto its own ladder, scaled by difficulty and the server's multiplier. */
    @Override
    public void awardXp(int amount) {
        super.awardXp(amount);
        gainXp(amount, xpSource == null ? "fight" : xpSource);       // [pa35] by what earned it (a kill when no deed is being paid)
    }

    /** [pa35] The deed being paid just now (so its experience is counted under it, for the repetition rule). */
    private String xpSource;
    /** [pa35] How tired it is of each kind of work (PaXp: less for the same thing over and over). */
    public final PaXp.Fatigue fatigue = new PaXp.Fatigue();

    @Override
    public void note(Deed deed, int count) {
        String was = xpSource;
        xpSource = deed.name();
        try {
            super.note(deed, count);
        } finally {
            xpSource = was;
        }
    }

    /** Experience for a kind of work ("job", "build", "trick"...), scaled. */
    public void earn(String what) {
        gainXp(PaLevels.baseFor(what), what);
    }

    void gainXp(int base) {
        gainXp(base, "work");
    }

    /** [pa35] A first's bonus (never tired: PaXp.FIRSTS). */
    public void gainXpFirst(int base) {
        gainXp(base, "first");
    }

    /** [pa35] Braver as its bond grows (PaBond.retreatsAt). */
    @Override
    public float retreatShare() {
        PaData.Rec r = level().isClientSide ? null : rec();
        return r == null ? 0.35F : (float) PaBond.retreatsAt(r.bond.tier());
    }

    /** [pa35] Paid for some work, less if it has done the same over and over lately (a "first" is never tired). */
    void gainXp(int base, String key) {
        if (base <= 0 || level().isClientSide) return;
        double share = key.equals("first") ? 1.0 : fatigue.take(key, level().getGameTime());
        int got = (int) Math.max(1, Math.round(PaLevels.pay(base, level().getDifficulty().getId(), PaConfig.xpMultiplier()) * share));
        fatigue.noted(key, got, share, level().getGameTime());
        int before = veteranLevel();
        paXp = Math.min(10_000_000L, paXp + got);
        int after = veteranLevel();
        keepXp();
        if (after > before) PaLevelUp.levelUp(this, before, after);
    }

    /** Its record keeps a copy of its experience. */
    void keepXp() {
        PaData.Rec r = rec();
        if (r != null && r.xp != paXp) { r.xp = paXp; PaData.touch(); }
    }

    /** A body stood up again never comes back lower than its record says. */
    public void restoreXp(long kept) {
        if (kept > paXp) paXp = Math.min(10_000_000L, kept);
    }

    /** Tests and admins: set its experience outright (no level-up moment). */
    public void setPaXpForTests(long xp) {
        paXp = Math.max(0, xp);
        keepXp();
    }

    /** [pa33g] Its reach grows with its level (PaLevels.reach). */
    @Override
    public double reach() {
        return PaLevels.reach(veteranLevel());
    }

    /** [pa35] Its level never quickens its hands or its digging: it works at a player's pace (PaPace); levels buy skill instead. */
    @Override
    public int experiencePercent() {
        return 0;
    }

    /** Its bow by its level: a wild shot at first, steadier each level, and from level 4 it leads a moving target. */
    @Override
    public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float velocity) {
        if (removeMatching(st -> st.is(net.minecraft.world.item.Items.ARROW), 1) < 1) return;
        int lv = veteranLevel();
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(level(), this,
            new ItemStack(net.minecraft.world.item.Items.ARROW), getMainHandItem().copy());
        net.minecraft.world.phys.Vec3 aim = PaLevels.leads(lv) ? com.jrpetty.mcassistant.entity.FolkCombat.lead(this, target, velocity)
            : target.position().add(0, target.getBbHeight() * 0.5, 0);
        double dx = aim.x - getX(), dy = aim.y - arrow.getY(), dz = aim.z - getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, velocity, PaLevels.spread(lv));
        playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        level().addFreshEntity(arrow);
        ItemStack bow = getMainHandItem();
        if (bow.is(net.minecraft.world.item.Items.BOW)) bow.hurtAndBreak(1, this, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
    }

    // ------------------------------------------------------------------ [pa33c-2b] gestures (the folk's own animations)

    private int animUntil, animTurn;

    /** A gesture by name ("wave", "shrug", "bow", "cheer", "think"...), for a while; false if there is no such one. */
    public boolean playAnim(String name) {
        com.jrpetty.mcassistant.entity.FolkAction.Act act = com.jrpetty.mcassistant.entity.FolkAction.Act.named(name);
        if (act == null || act == com.jrpetty.mcassistant.entity.FolkAction.Act.NONE || level().isClientSide) return false;
        animTurn = (animTurn + 1) & 0xFFFFFF;
        this.entityData.set(DATA_ANIM, act.ordinal() | (animTurn << 8));
        animUntil = tickCount + (act.loop ? 70 : 45);
        return true;
    }

    /** The gesture now (its act in the low byte, a turn count above so the same one twice plays twice). */
    public int animValue() {
        return this.entityData.get(DATA_ANIM);
    }

    // ------------------------------------------------------------------ [pa33c-2b] its pack: rows open with its level

    /** Piles grow in any slot; a new pile starts only in an open one (two rows at first, the third at level 3). */
    @Override
    public ItemStack insertItem(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        net.minecraft.core.NonNullList<ItemStack> inv = getInventoryItems();
        int open = Math.min(inv.size(), PaPack.open(veteranLevel()));
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inv.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inv.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int moved = Math.min(slot.getMaxStackSize() - slot.getCount(), remaining.getCount());
                if (moved > 0) { slot.grow(moved); remaining.shrink(moved); }
            }
        }
        for (int i = 0; i < open && !remaining.isEmpty(); i++) {
            if (inv.get(i).isEmpty()) { inv.set(i, remaining); remaining = ItemStack.EMPTY; }
        }
        return remaining;
    }

    @Override
    public boolean isPackFull() {
        net.minecraft.core.NonNullList<ItemStack> inv = getInventoryItems();
        int open = Math.min(inv.size(), PaPack.open(veteranLevel()));
        for (int i = 0; i < open; i++) if (inv.get(i).isEmpty()) return false;
        return true;
    }

    // ------------------------------------------------------------------ falling and coming back

    /** It keeps its pack when it falls (the server's choice, by default): what you gave it is not left on the ground. */
    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
        if (PaConfig.keepPack()) return;
        for (int i = 0; i < getInventoryItems().size(); i++) {                     // [pa33c-2b] or dropped where it fell, like a player
            ItemStack s = getInventoryItems().get(i);
            if (!s.isEmpty()) spawnAtLocation(s.copy());
            getInventoryItems().set(i, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ [pa33c-2b] hunger: player rules

    @Override
    protected boolean ownHunger() { return true; }

    /** Its food level on the client (HUD, panel). */
    public int food() {
        return level().isClientSide ? this.entityData.get(DATA_FOOD) : hunger.food();
    }

    void syncFood() {
        this.entityData.set(DATA_FOOD, hunger.food());
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        hunger.exhaust(0.1F);
        // [pa35] a critical hit now and then, more often with its level (PaGrowth.crit): half again as hard, with the sparks
        boolean crit = !level().isClientSide && getRandom().nextDouble() * 100 < PaGrowth.crit(veteranLevel());
        var dmg = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mc_assistant", "pa_crit");
        if (crit && dmg != null) dmg.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, 0.5,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        try {
            boolean hit = super.doHurtTarget(target);
            if (hit && crit && level() instanceof ServerLevel sl)
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, target.getX(), target.getY(0.6), target.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
            return hit;
        } finally {
            if (dmg != null) dmg.removeModifier(id);
        }
    }

    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        if (!level().isClientSide) hunger.exhaust(0.05F);
    }

    @Override
    public void swing(InteractionHand hand) {
        super.swing(hand);
        if (!level().isClientSide) hunger.exhaust(0.02F);                // work: digging, chopping, building
    }


    /** [pa33e] It never walks through a portal on its own: it crosses with its player (carried across beside them the
     *  moment they arrive), so it can neither run ahead into the Nether alone nor bounce back out of the portal it came by. */
    /** [pa33e] Wings open or shut (the elytra's flag; the game shuts it again on the ground or without an elytra). */
    public void glide(boolean on) {
        setSharedFlag(7, on);
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }
    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            PaChunks.removed(this);                                     // [pa33g] its window let go of
            PaLife.fell(this, source);
        }
    }

    /** [pa33g] Gone from the world for any reason: its window of awake chunks let go of (on the next tick). */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (!level().isClientSide) PaChunks.removed(this);
        super.remove(reason);
    }

    // ------------------------------------------------------------------ keeping

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PaSlot", slot);
        tag.putLong("PaInc", incarnation);
        tag.putString("PaOwnerName", ownerName);
        tag.putInt("PaLook", look());
        tag.putInt("PaPers", this.entityData.get(DATA_PERS));
        tag.put("PaPlan", plan.save());
        tag.putInt("PaFood", hunger.food());
        tag.putLong("PaXp", paXp);                                          // [pa33d] its own ten levels
        tag.putFloat("PaSat", hunger.saturation());
        tag.putFloat("PaExh", hunger.exhaustion());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        slot = tag.getInt("PaSlot");
        incarnation = tag.getLong("PaInc");
        ownerName = tag.getString("PaOwnerName");
        this.entityData.set(DATA_OWNER, ownerName);
        this.entityData.set(DATA_LOOK, tag.getInt("PaLook"));
        this.entityData.set(DATA_PERS, tag.getInt("PaPers"));
        plan.load(tag.getCompound("PaPlan"));
        if (tag.contains("PaFood")) hunger.set(tag.getInt("PaFood"), tag.getFloat("PaSat"), tag.getFloat("PaExh"));
        // [pa33d] an older assistant starts from its career so far (a real one: a new one's starting few don't count)
        paXp = tag.contains("PaXp") ? tag.getLong("PaXp") : lifetimeXp() >= 200 ? lifetimeXp() : 0;
        this.entityData.set(DATA_FOOD, hunger.food());
        this.entityData.set(DATA_SHOWN, displayNameCap());
    }

    @Override
    public Component getName() {
        return Component.literal(shownName());
    }
}
