package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [pa33] A Player AI Assistant's life: made (by the free button, a crafted Assistant Whistle, or an Assistant
 * Spawner), always its maker's for good; carried with its player across logouts and dimensions; brought back after
 * a fall (at its spawner, else beside its player) after a while, or at once with a whistle; and dismissed, giving
 * back everything it carried. One per player unless the server allows more.
 */
public final class PaLife {

    private PaLife() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Names it may start with (the player can rename it in its panel). */
    public static final List<String> NAMES = List.of("Pip", "Juniper", "Rowan", "Echo", "Sage", "Nova", "Milo", "Ivy", "Atlas",
        "Wren", "Clover", "Fern", "Orbit", "Bramble", "Comet", "Hazel", "Basil", "Indigo", "Pixel", "Tansy");

    /** How many looks there are to choose from (PaRenderer has the pictures). */
    public static final int LOOKS = PaPersona.LOOK_KEYS.length;   // [pa33b] 18 looks

    // ------------------------------------------------------------------ finding it

    /** The player's assistant in the world, if it is loaded somewhere (their first, or by slot). */
    @Nullable
    public static PlayerAssistantEntity find(ServerPlayer p) {
        PaData.Rec r = PaData.first(p.getUUID());
        return r == null ? null : entity(p.server, r);
    }

    @Nullable
    public static PlayerAssistantEntity entity(MinecraftServer server, PaData.Rec r) {
        if (r.entity == null || server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(r.entity);
            if (e instanceof PlayerAssistantEntity a && a.isAlive()) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ getting one

    /** "You already have Bob. Dismiss Bob to make another." when at the limit, else null. */
    @Nullable
    public static String atLimit(ServerPlayer p) {
        List<PaData.Rec> recs = PaData.of(p.getUUID());
        if (recs.size() < PaConfig.perPlayer()) return null;
        if (PaConfig.perPlayer() <= 0) return "Player AI Assistants are switched off on this server.";
        String name = recs.isEmpty() ? "an assistant" : PaNet.cap(recs.get(0).name);
        return "You already have " + name + ". Dismiss " + name + " to make another.";
    }

    /**
     * Make a new assistant for this player at a spot (null: beside them). {@code how}: "button", "whistle", "spawner".
     * Returns it, or null after telling the player why not.
     */
    @Nullable
    public static PlayerAssistantEntity create(ServerPlayer p, @Nullable BlockPos at, String how) {
        String limit = atLimit(p);
        if (limit != null) {
            tell(p, limit);
            return null;
        }
        if ("button".equals(how) && !PaConfig.freeFirst() && !p.isCreative()) {
            tell(p, "Make an Assistant Whistle (or an Assistant Spawner) to bring in your assistant: the Guide has the recipe.");
            return null;
        }
        ServerLevel level = p.serverLevel();
        PaData.Rec r = PaData.create(p.getUUID(), p.getGameProfile().getName(), level.getGameTime());
        r.name = NAMES.get(Math.floorMod(p.getUUID().hashCode() + r.slot * 7, NAMES.size())).toLowerCase();
        r.look = Math.floorMod(p.getUUID().hashCode() >> 3, 14);   // a person's outfit to start; the mob looks are a choice
        r.personality = 0;
        if ("spawner".equals(how) && at != null) {
            r.spawner = at.immutable();
            r.spawnerDim = level.dimension().location().toString();
        }
        PlayerAssistantEntity a = spawn(level, r, at != null ? at : p.blockPosition().relative(p.getDirection(), 3), p);
        if (a == null) {
            PaData.remove(r);
            tell(p, "There's no room for your assistant here.");
            return null;
        }
        if (r.spawner != null) a.setHome(r.spawner);
        // the first hello, and the three things to try
        r.introduced = true;
        PaTalk.reply(a, PaTalk.line(a, "intro", "owner", p.getGameProfile().getName(), "name", a.displayNameCap()),
            List.of(new Choice("Take the tour", "#tour"), new Choice("Follow me", "follow me"), new Choice("Get me 16 logs", "get me 16 logs"),
                new Choice("Build a small house here", "build a small house here")));
        PaData.touch();
        a.mind.openPanelAt = a.tickCount + 40;          // let its player see it arrive and say hello, then the panel with the choices
        return a;
    }

    /** Stand an assistant up from its record (its saved self when it has one) at a spot. */
    @Nullable
    static PlayerAssistantEntity spawn(ServerLevel level, PaData.Rec r, BlockPos near, @Nullable ServerPlayer owner) {
        PlayerAssistantEntity a = McAssistantMod.PLAYER_ASSISTANT.get().create(level);
        if (a == null) return null;
        if (r.snapshot != null) {
            CompoundTag t = r.snapshot.copy();
            t.remove("UUID");
            t.remove("Pos");
            t.remove("Motion");
            t.remove("Dimension");
            t.remove("DeathTime");
            t.remove("HurtTime");
            t.remove("Brain");
            try {
                a.load(t);
            } catch (Exception e) {
                LOG.warn("[pa33] could not restore {} from its kept copy", r.name, e);
                a = McAssistantMod.PLAYER_ASSISTANT.get().create(level);
                if (a == null) return null;
            }
        }
        boolean fresh = r.snapshot == null;
        if (owner != null && (fresh || a.getOwnerId() == null)) a.setOwner(owner);
        Vec3 spot = safeSpot(level, near);
        if (spot == null) { LOG.info("[pa33] no safe spot near {}", near.toShortString()); return null; }
        PaData.incarnate(r);
        a.bind(r);
        a.restoreXp(r.xp);                                                 // [pa33d] never back at level 1 by accident
        a.moveTo(spot.x, spot.y, spot.z, owner != null ? owner.getYRot() + 180 : 0, 0);
        a.setHealth(!fresh && a.getHealth() > 1 ? a.getHealth() : a.getMaxHealth());
        a.setDeltaMovement(Vec3.ZERO);
        a.fallDistance = 0;
        a.clearFire();
        if (!level.addFreshEntity(a)) { LOG.info("[pa33] the world would not take the assistant at {}", spot); return null; }
        r.entity = a.getUUID();
        r.away = false;
        r.snapshot = null;
        PaData.touch();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, spot.x, spot.y + 1, spot.z, 16, 0.4, 0.6, 0.4, 0.02);
        level.playSound(null, a.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.2F);
        return a;
    }

    /** A spot a two-tall body can stand in, near this one; null when there is none. */
    @Nullable
    public static Vec3 safeSpot(ServerLevel level, BlockPos near) {
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 2; dy >= -3; dy--) {
                        BlockPos feet = near.offset(dx, dy, dz);
                        if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) continue;
                        if (level.getFluidState(feet).isSource() || level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.LAVA)) continue;
                        AABB box = McAssistantMod.PLAYER_ASSISTANT.get().getDimensions().makeBoundingBox(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        if (level.noCollision(box) && !level.containsAnyLiquid(box)) return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ calling it, carrying it across

    /** Bring it to its player: a hop beside them, or across to their dimension. True when it came. */
    public static boolean bringTo(PlayerAssistantEntity a, ServerPlayer p) {
        ServerLevel to = p.serverLevel();
        if (a.level() == to) {
            Vec3 spot = safeSpot(to, p.blockPosition().relative(p.getDirection().getOpposite(), 2));
            if (spot == null) return false;
            a.getNavigation().stop();
            a.teleportTo(spot.x, spot.y, spot.z);
            a.fallDistance = 0;
            return true;
        }
        PaData.Rec r = a.rec();
        if (r == null) return false;
        r.snapshot = a.saveWithoutId(new CompoundTag());
        a.discard();
        return spawn(to, r, p.blockPosition(), p) != null;
    }

    /** Its player is leaving (logout): it goes with them, kept in its record till they are back. */
    public static void stash(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        r.snapshot = a.saveWithoutId(new CompoundTag());
        r.away = true;
        r.leftAt = System.currentTimeMillis();                 // [pa33b] for "you've been gone ages"
        r.entity = null;
        PaData.touch();
        a.discard();
    }

    /** [pa33b] The hello: by how long its player was away and the time of day, at its place in the triangle. */
    static String greeting(PlayerAssistantEntity a, PaData.Rec r, ServerPlayer p) {
        long mins = r.leftAt <= 0 ? -1 : (System.currentTimeMillis() - r.leftAt) / 60000L;
        long time = a.level().getDayTime() % 24000L;
        String key = mins >= 0 && mins < 10 ? "quip.back_short" : mins >= 180 ? "quip.back_long"
            : time < 2000 ? "quip.morning" : time > 13000 && time < 23000 ? "quip.nightlogin" : null;
        if (key != null && r.mute == 0 && a.getRandom().nextInt(100) < PaBanter.chance(r.tri())) {
            String raw = PaPersona.remark(key, r.tri(), r.look, null, a.getRandom()::nextInt, a.mind.recent);
            if (raw != null) return PaBanter.fill(a, raw, "owner", p.getGameProfile().getName());
        }
        return PaTalk.line(a, "greet", "owner", p.getGameProfile().getName());
    }

    /** Its player is back: every assistant they took away with them stands up beside them. */
    public static void restore(ServerPlayer p) {
        for (PaData.Rec r : PaData.of(p.getUUID())) {
            r.ownerName = p.getGameProfile().getName();
            if (!r.away || r.dead(p.serverLevel().getGameTime())) continue;
            PlayerAssistantEntity a = spawn(p.serverLevel(), r, p.blockPosition().relative(p.getDirection(), 3), p);
            if (a != null && r.mute < 2) {                 // [pa33b] told to be quiet: it stands up without a word
                PaTalk.greet(a, greeting(a, r, p));   // [pa33b] by how long they were away and the time of day
            }
        }
    }

    /** A copy of it kept now and then, so a body lost with an unloaded chunk can be stood up again beside its player. */
    public static void keepCopy(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        r.snapshot = a.saveWithoutId(new CompoundTag());
    }

    // ------------------------------------------------------------------ falling and coming back

    static void fell(PlayerAssistantEntity a, DamageSource source) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        long now = a.level().getGameTime();
        CompoundTag self = a.saveWithoutId(new CompoundTag());
        self.putFloat("Health", a.getMaxHealth());
        r.snapshot = self;
        r.deadUntil = now + PaConfig.respawnSeconds() * 20L;
        r.entity = null;
        Entity killer = source.getEntity();
        r.lastDeath = "Knocked out by " + (killer != null ? killer.getName().getString().toLowerCase() : source.getMsgId()) + " on day " + (a.level().getDayTime() / 24000L) + ".";
        a.plan.clear();
        PaTalk.reply(a, PaTalk.line(a, "died", "owner", r.ownerName)
            + " (back in " + PaConfig.respawnSeconds() + "s" + (r.spawner != null ? " at my spawner" : "") + ")");
        PaData.touch();
    }

    /** The server's watch: fallen assistants come back when their time is up, at their spawner or beside their player. */
    public static void watch(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (PaData.Rec r : PaData.all()) {
            if (r.deadUntil > 0 && r.deadUntil <= now && !r.away) {
                ServerPlayer owner = server.getPlayerList().getPlayer(r.owner);
                if (owner == null) continue;                         // comes back when its player is here
                revive(server, r, owner);
            } else if (!r.away && r.deadUntil <= 0 && r.entity != null && r.snapshot != null && server.getTickCount() % 200 == 0) {
                // lost with an unloaded chunk while its player is elsewhere and it should be following: stand it up again
                ServerPlayer owner = server.getPlayerList().getPlayer(r.owner);
                if (owner != null && entity(server, r) == null && followsAlong(r)) {
                    PlayerAssistantEntity a = spawn(owner.serverLevel(), r, owner.blockPosition(), owner);
                    if (a != null) PaTalk.comment(a, 1, "Caught up with you!");
                }
            }
        }
    }

    private static boolean followsAlong(PaData.Rec r) {
        CompoundTag s = r.snapshot;
        if (s == null) return false;
        String mode = s.getString("Mode");
        return !"STAY".equals(mode) && s.getCompound("PaPlan").getList("Steps", 10).isEmpty();
    }

    @Nullable
    public static PlayerAssistantEntity revive(MinecraftServer server, PaData.Rec r, ServerPlayer owner) {
        r.deadUntil = -1;
        ServerLevel level = owner.serverLevel();
        BlockPos at = owner.blockPosition();
        // [pa33c-2b] its bed first (the last it slept in, then the one it was given), as a player's spawn; then its spawner; then its player
        boolean bedGone = false, onBed = false;
        for (int k = 0; k < 2 && !onBed; k++) {
            BlockPos b = k == 0 ? r.lastBed : r.bed;
            String bd = k == 0 ? r.lastBedDim : r.bedDim;
            if (b == null) continue;
            ResourceLocation dim = ResourceLocation.tryParse(bd);
            for (ServerLevel l : server.getAllLevels()) {
                if (dim == null || !l.dimension().location().equals(dim)) continue;
                net.minecraft.world.level.block.state.BlockState s = l.getBlockState(b);
                if (l.isLoaded(b) && s.getBlock() instanceof net.minecraft.world.level.block.BedBlock && !s.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) {
                    level = l;
                    at = net.minecraft.world.level.block.BedBlock.findStandUpPosition(McAssistantMod.PLAYER_ASSISTANT.get(), l, b,
                        s.getValue(net.minecraft.world.level.block.BedBlock.FACING), 0.0F).map(BlockPos::containing).orElse(b.above());
                    onBed = true;
                } else if (l.isLoaded(b) && !(s.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
                    bedGone = true;                                       // broken: forget it, fall back
                    if (k == 0) r.lastBed = null; else r.bed = null;
                }
            }
        }
        if (!onBed && r.spawner != null) {
            ResourceLocation dim = ResourceLocation.tryParse(r.spawnerDim);
            for (ServerLevel l : server.getAllLevels()) {
                if (dim != null && l.dimension().location().equals(dim) && l.isLoaded(r.spawner)
                        && l.getBlockState(r.spawner).is(McAssistantMod.ASSISTANT_SPAWNER.get())) {
                    level = l;
                    at = r.spawner.above();
                }
            }
        }
        PlayerAssistantEntity a = spawn(level, r, at, owner);
        if (a != null) {
            if (r.spawner != null && level.dimension().location().toString().equals(r.spawnerDim)) a.setHome(r.spawner);
            a.setMode(AssistantEntity.Mode.FOLLOW);
            if (bedGone) PaTalk.reply(a, PaTalk.line(a, "bed_gone"));
            PaTalk.reply(a, PaTalk.line(a, onBed ? "respawn_bed" : "respawn", "owner", owner.getGameProfile().getName()));
        }
        PaData.touch();
        return a;
    }

    // ------------------------------------------------------------------ the summon button, the whistle, dismissing

    /** The panel's summon button: make one (the first is free), or call it here, or bring it back if fallen. */
    public static void summonButton(ServerPlayer p) {
        PaData.Rec r = PaData.first(p.getUUID());
        if (r == null) {
            create(p, null, "button");
            return;
        }
        call(p, r, false);
    }

    /** Call an existing assistant to its player (the button after its cooldown, or a whistle at once). */
    public static void call(ServerPlayer p, PaData.Rec r, boolean whistle) {
        long now = p.serverLevel().getGameTime();
        if (r.dead(now)) {
            if (whistle) {
                revive(p.server, r, p);
                return;
            }
            tell(p, PaNet.cap(r.name) + " is knocked out: back in " + (r.deadUntil - now) / 20 + "s" + (r.spawner != null ? " at the spawner" : "")
                + ". A whistle brings them back at once.");
            return;
        }
        if (!whistle && r.summonReadyAt > now) {
            tell(p, "Give it " + (r.summonReadyAt - now) / 20 + "s more before calling again.");
            return;
        }
        r.summonReadyAt = now + PaConfig.summonCooldownSeconds() * 20L;
        PlayerAssistantEntity a = entity(p.server, r);
        if (a == null) {
            // not loaded anywhere: stood up from its last kept copy
            if (r.snapshot == null && !r.away) {
                // nothing kept at all (a fresh world copy lost): a new body, same name and settings
            }
            a = spawn(p.serverLevel(), r, p.blockPosition(), p);
        } else if (!bringTo(a, p)) {
            tell(p, "I can't find a safe spot beside you.");
            return;
        } else {
            a = entity(p.server, r);
        }
        if (a != null) {
            a.setMode(AssistantEntity.Mode.FOLLOW);
            PaTalk.reply(a, PaTalk.line(a, "come"));
        }
        PaData.touch();
    }

    /** Send it away for good: everything it carried and wore goes to its player, and its record is closed. */
    public static void dismiss(ServerPlayer p, PaData.Rec r) {
        PlayerAssistantEntity a = entity(p.server, r);
        List<ItemStack> give = new java.util.ArrayList<>();
        if (a != null) {
            for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) give.add(s.copy());
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                ItemStack s = a.getItemBySlot(slot);
                if (!s.isEmpty()) give.add(s.copy());
            }
            a.discard();
        } else if (r.snapshot != null) {
            // away or fallen: its kept self's pack
            PlayerAssistantEntity ghost = McAssistantMod.PLAYER_ASSISTANT.get().create(p.serverLevel());
            if (ghost != null) {
                try {
                    ghost.load(r.snapshot);
                    for (ItemStack s : ghost.getInventoryItems()) if (!s.isEmpty()) give.add(s.copy());
                    for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                        ItemStack s = ghost.getItemBySlot(slot);
                        if (!s.isEmpty()) give.add(s.copy());
                    }
                } catch (Exception ignored) {
                    // nothing kept to give back
                }
                ghost.discard();
            }
        }
        for (ItemStack s : give) if (!p.getInventory().add(s)) p.drop(s, false);
        PaData.remove(r);
        tell(p, PaNet.cap(r.name) + " has gone. Everything they carried is in your pack.");
        PaNet.refresh(p, null);
    }

    public static void rename(PlayerAssistantEntity a, String name) {
        String clean = name.trim().replaceAll("[^A-Za-z0-9_ ]", "").trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 16) clean = clean.substring(0, 16);
        a.rename(clean);
        PaData.Rec r = a.rec();
        if (r != null) { r.name = a.getAssistantName(); PaData.touch(); }
    }

    /** A line to a player from the assistant system (no assistant to say it): their panel's footer and the action bar. */
    public static void tell(ServerPlayer p, String text) {
        LOG.info("[pa33] to {}: {}", p.getGameProfile().getName(), text);
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(text), true);
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        t.putInt("id", -1);
        t.putBoolean("owner", true);
        t.putBoolean("note", true);
        PaNet.send(p, PaPayload.SAY, t);
    }
}
