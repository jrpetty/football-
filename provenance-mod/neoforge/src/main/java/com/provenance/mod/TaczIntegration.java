package com.provenance.mod;

import com.provenance.core.BreakdownKind;
import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import com.provenance.core.RecordRegistry;
import com.provenance.core.StatKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Gives every firearm from Timeless and Classics Zero a lifetime record.
 *
 * <h2>Why this is reflective</h2>
 *
 * <p>Provenance must run whether or not a gun mod is installed, and must not
 * carry a compile-time dependency on one. Everything TACZ-shaped is therefore
 * resolved by name at startup: if the mod is absent, {@link #install} returns
 * immediately and nothing else here ever executes. Method handles are looked up
 * once and cached, so the per-shot cost is a virtual call, not a lookup.
 *
 * <h2>The thing that makes guns different</h2>
 *
 * <p>Every firearm in TACZ is the same item — {@code tacz:modern_kinetic_gun}.
 * An AK-47 and a Glock are one registry entry; which gun a stack actually is
 * lives inside the stack, readable through the mod's {@code IGun} interface.
 *
 * <p>That breaks the assumption the rest of Provenance makes, that an item's
 * identity can be cached per item type. So {@link #gunIdOf} reads the real gun
 * id per stack, and {@link Stamps#itemId} consults it, which is what lets a
 * record say "Ak47" rather than "Modern kinetic gun" — and lets two guns in the
 * same inventory keep genuinely separate histories.
 *
 * <h2>What is recorded</h2>
 *
 * <ul>
 *   <li>Shots fired, from the shoot event — so accuracy is a real figure rather
 *       than the division by zero a vanilla bow would give.</li>
 *   <li>Hits, damage dealt and headshots, from the hurt event.</li>
 *   <li>Kills, the most-killed mob, and longest kill distance.</li>
 *   <li>Melee hits, for guns used as a club.</li>
 * </ul>
 */
public final class TaczIntegration {

    private static final String MOD_ID = "tacz";
    private static final String EVENTS = "com.tacz.guns.api.event.common.";

    private static boolean active;

    /** {@code com.tacz.guns.api.item.IGun}, for identifying a gun stack. */
    private static Class<?> gunInterface;
    private static Method getGunId;

    // Event accessors, resolved once.
    private static Method shootGetShooter;
    private static Method shootGetStack;
    private static Method shootGetSide;

    private static Method hurtGetAttacker;
    private static Method hurtGetAmount;
    private static Method hurtIsHeadShot;
    private static Method hurtGetGunId;

    private static Method killGetAttacker;
    private static Method killGetKilled;
    private static Method killGetGunId;

    private static Method meleeGetShooter;
    private static Method meleeGetStack;

    private TaczIntegration() {
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Wires up the integration if the gun mod is present.
     *
     * <p>Any failure here disables the integration and leaves the rest of
     * Provenance untouched. A gun mod changing its API between versions must
     * never stop pickaxes recording blocks.
     */
    public static void install(IEventBus eventBus) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
            getGunId = gunInterface.getMethod("getGunId", ItemStack.class);

            Class<?> shoot = Class.forName(EVENTS + "GunShootEvent");
            shootGetShooter = shoot.getMethod("getShooter");
            shootGetStack = shoot.getMethod("getGunItemStack");
            shootGetSide = shoot.getMethod("getLogicalSide");

            Class<?> hurt = Class.forName(EVENTS + "EntityHurtByGunEvent$Post");
            hurtGetAttacker = hurt.getMethod("getAttacker");
            hurtGetAmount = hurt.getMethod("getAmount");
            hurtIsHeadShot = hurt.getMethod("isHeadShot");
            hurtGetGunId = hurt.getMethod("getGunId");

            Class<?> kill = Class.forName(EVENTS + "EntityKillByGunEvent");
            killGetAttacker = kill.getMethod("getAttacker");
            killGetKilled = kill.getMethod("getKilledEntity");
            killGetGunId = kill.getMethod("getGunId");

            Class<?> melee = Class.forName(EVENTS + "GunMeleeEvent");
            meleeGetShooter = melee.getMethod("getShooter");
            meleeGetStack = melee.getMethod("getGunItemStack");

            listen(eventBus, shoot, TaczIntegration::onShoot);
            listen(eventBus, hurt, TaczIntegration::onHurt);
            listen(eventBus, kill, TaczIntegration::onKill);
            listen(eventBus, melee, TaczIntegration::onMelee);

            active = true;
            Provenance.LOGGER.info("Provenance: Timeless and Classics Zero detected, firearms will be tracked");
        } catch (ReflectiveOperationException | RuntimeException e) {
            active = false;
            Provenance.LOGGER.warn("Provenance: found {} but could not read its API, so firearms will not be "
                    + "tracked. Everything else is unaffected.", MOD_ID, e);
        }
    }

    /**
     * A handler that may throw while reflecting. {@link java.util.function.Consumer}
     * cannot, and every method here reads the event by reflection.
     */
    @FunctionalInterface
    private interface ReflectiveHandler {
        void handle(Event event) throws ReflectiveOperationException;
    }

    @SuppressWarnings("unchecked")
    private static void listen(IEventBus bus, Class<?> type, ReflectiveHandler handler) {
        bus.addListener((Class<Event>) type, event -> {
            try {
                handler.handle(event);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // One malformed event must not spam the log or break the shot.
                Provenance.LOGGER.debug("Provenance: TACZ event handling failed", e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /**
     * The specific firearm a stack is, or null when it is not a gun.
     *
     * <p>Used in place of the item's registry id, because every gun shares one.
     */
    public static String gunIdOf(ItemStack stack) {
        if (!active || stack.isEmpty() || !gunInterface.isInstance(stack.getItem())) {
            return null;
        }
        try {
            Object id = getGunId.invoke(stack.getItem(), stack);
            return id instanceof ResourceLocation location ? location.toString() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private static void onShoot(Event event) throws ReflectiveOperationException {
        if (isClient(shootGetSide.invoke(event))) {
            return;
        }
        if (!(shootGetShooter.invoke(event) instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        ItemStack stack = (ItemStack) shootGetStack.invoke(event);
        ItemRecord record = resolve(stack, player);
        if (record == null) {
            return;
        }
        state().registry().record(record, player.getUUID(), player.getGameProfile().getName(),
                StatKey.SHOTS_FIRED, 1);
    }

    private static void onHurt(Event event) throws ReflectiveOperationException {
        if (!(hurtGetAttacker.invoke(event) instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        ItemStack stack = heldGun(player, hurtGetGunId.invoke(event));
        ItemRecord record = resolve(stack, player);
        if (record == null) {
            return;
        }

        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        RecordRegistry registry = state().registry();

        // Same hundredths-of-a-heart scale the rest of the mod uses.
        long amount = Math.round(((Number) hurtGetAmount.invoke(event)).doubleValue() * 100.0d);
        if (amount > 0) {
            registry.record(record, id, name, StatKey.DAMAGE_DEALT, amount);
        }
        registry.record(record, id, name, StatKey.PROJECTILE_HITS, 1);
        if (Boolean.TRUE.equals(hurtIsHeadShot.invoke(event))) {
            registry.record(record, id, name, StatKey.HEADSHOTS, 1);
        }
    }

    private static void onKill(Event event) throws ReflectiveOperationException {
        if (!(killGetAttacker.invoke(event) instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        Object killed = killGetKilled.invoke(event);
        if (!(killed instanceof LivingEntity victim) || victim instanceof net.minecraft.world.entity.player.Player) {
            // Ordinary mob kills only, exactly as for melee weapons.
            return;
        }

        ItemStack stack = heldGun(player, killGetGunId.invoke(event));
        ItemRecord record = resolve(stack, player);
        if (record == null) {
            return;
        }

        UUID id = player.getUUID();
        String name = player.getGameProfile().getName();
        RecordRegistry registry = state().registry();

        GameplayEvents.announce(player, registry.record(record, id, name, StatKey.KILLS, 1), record, stack);
        registry.recordBreakdown(record, id, name, BreakdownKind.MOB_KILLED,
                GameplayEvents.entityId(victim), 1);
        registry.record(record, id, name, StatKey.LONGEST_KILL_CM,
                Math.round(player.distanceTo(victim) * 100.0d));
    }

    private static void onMelee(Event event) throws ReflectiveOperationException {
        if (!(meleeGetShooter.invoke(event) instanceof ServerPlayer player) || !Stamps.shouldCount(player)) {
            return;
        }
        ItemStack stack = (ItemStack) meleeGetStack.invoke(event);
        ItemRecord record = resolve(stack, player);
        if (record == null) {
            return;
        }
        state().registry().record(record, player.getUUID(), player.getGameProfile().getName(),
                StatKey.HITS_LANDED, 1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The hurt and kill events name the gun but do not hand over the stack, so
     * it comes from the player's hand — checked against the event's gun id, so
     * a player who swapped weapons mid-flight cannot have a bullet credited to
     * the wrong firearm.
     */
    private static ItemStack heldGun(ServerPlayer player, Object eventGunId) {
        ItemStack main = player.getMainHandItem();
        String held = gunIdOf(main);
        if (held == null) {
            return ItemStack.EMPTY;
        }
        if (eventGunId instanceof ResourceLocation location && !held.equals(location.toString())) {
            return ItemStack.EMPTY;
        }
        return main;
    }

    private static ItemRecord resolve(ItemStack stack, ServerPlayer player) {
        if (stack == null || stack.isEmpty() || state() == null) {
            return null;
        }
        ItemRecord record = Stamps.track(stack, player);
        return record != null && record.category() == ItemCategory.GUN ? record : null;
    }

    private static boolean isClient(Object logicalSide) {
        return logicalSide != null && "CLIENT".equals(logicalSide.toString());
    }

    private static ProvenanceState state() {
        return ProvenanceState.get();
    }
}
