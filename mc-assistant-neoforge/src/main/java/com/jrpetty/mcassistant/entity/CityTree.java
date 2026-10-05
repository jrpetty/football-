package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The city's research: twenty civic improvements in five branches, each a slight buff, that a
 * village's leader chooses one at a time and the town works toward. (Being built out: see the
 * design notes in the scratchpad's skills-design.md.)
 */
public final class CityTree {

    private CityTree() {}

    /** What the city's research adds to the pace of this trade's work, in percent. */
    public static int workPercent(@Nullable UUID village, StationTask trade) {
        return 0;
    }
}
