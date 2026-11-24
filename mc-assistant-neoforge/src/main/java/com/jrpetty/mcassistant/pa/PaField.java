package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [pa38] What the assistant is in the middle of with its rod, its throws, its mace and its slips, and its running tallies
 * (the Stats tab and the tests read them). One holder, so the shared mind needs only one line for all of it.
 */
public final class PaField {

    // ---- fishing (PaFishGoal)
    /** Casts, catches, bites lost, early strikes, treasure and junk since it was made (this session). */
    public int casts, catches, lost, early, treasure, junk, missedCasts;

    // ---- throws (PaThrowGoal)
    /** An order to throw at something: who, with what ("snowball", "egg", or "" for either), how many throws left, till when. */
    @Nullable public UUID throwAt;
    public String throwWith = "";
    public int throwsLeft, throwUntil, throwsDone, throwHits, aggroPulls, lastThrow = -100000, incidentAt = -100000, incidentThrows;
    @Nullable public UUID incidentMob;

    // ---- the rod's pull in a fight (PaRodPull)
    public int pulls, pullAt = -100000;
    @Nullable public UUID hookOrder;
    public int hookUntil;

    // ---- the mace (PaMace)
    /** Smashes landed, with the damage of the last one and its fall. */
    public int smashes, smashMisses;
    public float lastSmashDamage, lastSmashFall;
    @Nullable public UUID smashOrder;
    public int smashUntil;
    /** Launched by its own wind charge from here (fall damage counts from here, as a player's does). */
    @Nullable public Vec3 impulseFrom;
    public int impulseAt;

    // ---- enchanting (PaEnchantGoal)
    public int enchants, rerolls, offersSeen, shelvesPlaced;
    /** Its own enchanting seed (a player's, kept the same way: new after every enchantment). */
    public int enchantSeed;
    public boolean seedSet;
    public String lastPick = "";

    // ---- trading (PaTrade)
    public int deals, bargains, cures;
    /** A zombie villager it was asked to cure (and till when). */
    @Nullable public UUID cureOrder;
    public int cureUntil;
    public String lastDeal = "";

    // ---- the learner's slips (PaSlips)
    public int lastSlip = -100000, misplaced, wrongTurns, wrongTools;
    /** A misplaced block waiting to be put right: where, and when. */
    @Nullable public BlockPos fixAt;
    public int fixTick;
    /** A wrong turn under way: where it really meant to go, the wrong spot, since when. */
    @Nullable public Vec3 meant;
    @Nullable public Vec3 detour;
    public double meantSpeed;
    public int detourAt;
    /** A wrong tool in its hand for a moment: switch at this tick. */
    public int toolSwitchAt;
    @Nullable public net.minecraft.world.level.block.state.BlockState toolFor;
    public int lastToolJob = -100000;
    @Nullable public net.minecraft.world.level.block.Block lastToolBlock;
}
