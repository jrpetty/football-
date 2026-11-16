package com.jrpetty.mcassistant.entity;

import java.util.UUID;

/** [econ-1b] Entrepreneurs (being written). */
public final class Enterprise {

    private Enterprise() {}

    /** Does this folk own any business other than its own homestead? */
    public static boolean ownsAny(UUID village, UUID folk) {
        for (Business b : Businesses.all(village)) {
            if (b.type.size == Business.Size.YARD) continue;
            if (b.ownedBy(folk)) return true;
        }
        return false;
    }
}
