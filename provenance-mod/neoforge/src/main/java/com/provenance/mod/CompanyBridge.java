package com.provenance.mod;

import net.minecraft.server.level.ServerPlayer;

/**
 * Integration point for the server's existing Shop Mod company system.
 *
 * <p>Deliberately an interface rather than a guess at that mod's internals.
 * Provenance needs exactly two facts — a stable company id and a display name —
 * and only when a craft genuinely happened on the company's behalf.
 *
 * <h2>What an implementation must not do</h2>
 *
 * <p>It must not return a company simply because the crafting player belongs to
 * one. Attribution requires the server's chosen mechanism: a company
 * workstation, a company factory block, or an explicitly activated company
 * context. A personal craft by a company member is an Independent item, and
 * recording it otherwise would falsify the manufacturer line permanently.
 *
 * <p>Until the Shop Mod hook is wired in, {@link #INDEPENDENT} is installed,
 * and every craft is honestly recorded as Independent rather than attributed to
 * an invented manufacturer.
 */
public interface CompanyBridge {

    /** A company id and display name pair. */
    record Company(String id, String name) {
    }

    /**
     * The company this craft was performed on behalf of, or null for a personal
     * craft.
     *
     * @param player       the crafting player
     * @param workstationHint a block or menu identifier the craft happened at,
     *                        or null when crafted from the inventory
     */
    Company companyForCraft(ServerPlayer player, String workstationHint);

    /** The honest default: no company system wired in, so nothing is attributed. */
    CompanyBridge INDEPENDENT = (player, workstationHint) -> null;

    final class Holder {
        private static CompanyBridge current = INDEPENDENT;

        private Holder() {
        }

        public static CompanyBridge get() {
            return current;
        }

        /** Called by the Shop Mod integration during mod setup. */
        public static void set(CompanyBridge bridge) {
            current = bridge == null ? INDEPENDENT : bridge;
            Provenance.LOGGER.info("Provenance company bridge installed: {}", current.getClass().getName());
        }
    }
}
