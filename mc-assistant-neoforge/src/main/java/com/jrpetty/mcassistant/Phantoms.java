package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * No phantoms over the villages. The folk keep their own hours — up at dawn, abed after dark — and a
 * town is no place for a flight of phantoms come down out of the night on a player who has not slept:
 * they swoop at whatever stands about the square, and a town full of folk out in the evening is a town
 * where somebody gets hurt for it.
 *
 * <ul>
 * <li>A player who has not slept, standing over a village (its town's reach and a margin round it —
 *     a phantom comes down from ten blocks off), has no phantoms sent after it there
 *     (PlayerSpawnPhantomsEvent: the game's own phantom spawner asks it, once a player).</li>
 * <li>Any other phantom the world would spawn there of itself is not spawned (FinalizeSpawnEvent, and
 *     the joining of the world as a last word: the phantom spawner calls its phantom's finalizeSpawn
 *     itself, so it is caught there).</li>
 * <li>A phantom that strays in over a town from outside is seen off — a puff of smoke, and it is
 *     gone; nothing is hurt and nothing dropped. One a player brought (a spawn egg, a spawner, a
 *     command, a name tag) is left alone.</li>
 * <li>And a phantom never goes for the folk, whatever the config says: it hunts players, as in the
 *     game, and if anything ever sets it on a folk it thinks better of it.</li>
 * </ul>
 * AssistantConfig.villagePhantoms (off by default) lets them come as they always did.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Phantoms {

    private Phantoms() {}

    /** How far past a town's reach the sky is kept clear. */
    public static final int MARGIN = 24;

    /** The ways a phantom comes that a player meant: these are left alone. */
    private static final Set<MobSpawnType> MEANT = EnumSet.of(MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND, MobSpawnType.SPAWNER,
        MobSpawnType.TRIAL_SPAWNER, MobSpawnType.DISPENSER, MobSpawnType.BUCKET, MobSpawnType.BREEDING, MobSpawnType.CONVERSION,
        MobSpawnType.MOB_SUMMONED, MobSpawnType.TRIGGERED);

    /** The village whose sky this is (its town's reach and the margin round it), or null. */
    @Nullable
    public static Villages.Village townUnder(Level level, double x, double z) {
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            int r = Villages.townReach(v.id()) + MARGIN;
            if (Math.abs(x - (v.centre().getX() + 0.5)) <= r && Math.abs(z - (v.centre().getZ() + 0.5)) <= r) return v;
        }
        return null;
    }

    /** Are phantoms kept out of the sky here? */
    public static boolean keptOff(Level level, double x, double z) {
        return !AssistantConfig.villagePhantoms() && townUnder(level, x, z) != null;
    }

    /** Came of itself (the night, the world), not brought by a player. */
    static boolean ofItself(@Nullable MobSpawnType how) {
        return how == null || !MEANT.contains(how);
    }

    /** A phantom that came of itself and is nobody's: one to see off over a town. */
    public static boolean stray(Phantom p) {
        return !p.isPersistenceRequired() && !p.hasCustomName() && ofItself(p.getSpawnType());
    }

    /** The game's phantom spawner, about to send phantoms after a player who has not slept. */
    @SubscribeEvent
    public static void onPhantomsDue(PlayerSpawnPhantomsEvent event) {
        Player p = event.getEntity();
        if (!keptOff(p.level(), p.getX(), p.getZ())) return;
        event.setResult(PlayerSpawnPhantomsEvent.Result.DENY);
        event.setPhantomsToSpawn(0);
    }

    /** Any other phantom the world spawns of itself, over a town. */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof Phantom) || !ofItself(event.getSpawnType())) return;
        if (keptOff(event.getLevel().getLevel(), event.getX(), event.getZ())) event.setSpawnCancelled(true);
    }

    /** The last word: a phantom of itself does not come into the world over a town. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || event.getLevel().isClientSide() || !(event.getEntity() instanceof Phantom p)) return;
        if (stray(p) && keptOff(event.getLevel(), p.getX(), p.getZ())) event.setCanceled(true);
    }

    /** A phantom never goes for the folk. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Phantom && event.getNewAboutToBeSetTarget() instanceof AssistantEntity) {
            event.setNewAboutToBeSetTarget(null);
        }
    }

    /** Every two seconds, over every town: a phantom set on a folk let go, and a stray seen off. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 40L != 13L) return;
        boolean allowed = AssistantConfig.villagePhantoms();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
            int r = Villages.townReach(v.id()) + MARGIN;
            BlockPos c = v.centre();
            AABB sky = new AABB(c.getX() - r, c.getY() - 48, c.getZ() - r, c.getX() + r + 1, c.getY() + 128, c.getZ() + r + 1);
            for (Phantom p : level.getEntitiesOfClass(Phantom.class, sky, Entity::isAlive)) {
                if (p.getTarget() instanceof AssistantEntity) p.setTarget(null);
                if (!allowed && stray(p)) seeOff(level, p);
            }
        }
    }

    /** Gone in a puff of smoke: nothing hurt, nothing dropped. */
    static void seeOff(ServerLevel level, Phantom p) {
        level.sendParticles(ParticleTypes.POOF, p.getX(), p.getY() + 0.3, p.getZ(), 14, 0.5, 0.3, 0.5, 0.02);
        p.discard();
    }
}
