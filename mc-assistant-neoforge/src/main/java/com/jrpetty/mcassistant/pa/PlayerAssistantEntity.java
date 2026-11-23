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
            if (!level().isClientSide && tickCount - mind.lastHit > 400) {   // [pa33b] "Rude."
                mind.lastHit = tickCount;
                PaBanter.after(this, "quip.hit", null);
            }
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer sp && !level().isClientSide) PaBanter.stranger(this, sp, "", true);   // [pa33b] "Steady on."
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide) hunger.exhaust(0.1F);              // [pa33c-2b] a hit taken tires it, as a player
        return hit;
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

    /**
     * [pa33b] Whatever its player hands it, it uses, as a player would: the crew's rank gating on iron and diamond
     * tools is not for it (it was fighting with a pickaxe while an iron sword sat in its pack).
     */
    @Override
    public boolean mayUseTier(ItemStack s) {
        return true;
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
        return super.doHurtTarget(target);
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
        tag.putInt("PaFood", hunger.food());
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
        this.entityData.set(DATA_FOOD, hunger.food());
        this.entityData.set(DATA_SHOWN, displayNameCap());
    }

    @Override
    public Component getName() {
        return Component.literal(shownName());
    }
}
