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
        builder.define(DATA_SHOWN, "");
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(2, new PaTaskGoal(this));
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

    public void setPersonality(int p) { this.entityData.set(DATA_PERS, p); }

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
        if (source.getEntity() instanceof Player p && isOwner(p)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public void setTarget(@Nullable net.minecraft.world.entity.LivingEntity target) {
        boolean fresh = target != null && fairGame(target) && target != getTarget();
        super.setTarget(fairGame(target) ? target : null);
        if (fresh && !level().isClientSide) equipBestWeapon();      // [pa33b] sword out for a fight, as a player would
    }

    @Override
    public boolean canAttack(net.minecraft.world.entity.LivingEntity target) {
        return fairGame(target) && super.canAttack(target);
    }

    /** May it fight this? Monsters and the like yes; people, assistants, crews and folk no. */
    public static boolean fairGame(@Nullable net.minecraft.world.entity.LivingEntity target) {
        return target == null || !(target instanceof Player || target instanceof AssistantEntity
            || target instanceof net.minecraft.world.entity.npc.AbstractVillager
            || target instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame());
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
    protected int skillWorkPercent() { return PLAYER_PACE; }

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
        return super.inZone(pos) && !PaSafety.offLimits(this, pos) && PaBrain.withinRoam(this, pos);
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
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && isAlive()) PaBrain.tick(this);
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
            PaTalk.to(this, sp, PaPhrases.line("not_owner", personality(), getRandom()::nextInt, "owner", ownerName));
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
        else PaNet.open(sp, this, "talk");
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ falling and coming back

    /** It keeps its pack when it falls: what you gave it is not left on the ground. */
    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) PaLife.fell(this, source);
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
        this.entityData.set(DATA_SHOWN, displayNameCap());
    }

    @Override
    public Component getName() {
        return Component.literal(shownName());
    }
}
