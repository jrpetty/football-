package com.jrpetty.mcassistant.entity;

/**
 * A folk's own knacks: as it grows in experience it earns a point now and then and spends it on a
 * knack of its own choosing, for its trade, its nature or its purse. (Being built out: see the
 * design notes in the scratchpad's skills-design.md.)
 */
public final class FolkSkills {

    private FolkSkills() {}

    /** What its own knacks add to the pace of its work, in percent. */
    public static int workPercent(VillageFolkEntity f) {
        return 0;
    }
}
