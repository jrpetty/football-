package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * [pa33] One of the Player AI Assistant's own tasks, for what the original engine has no job for: putting fuel in a
 * furnace, placing a block where it was told ("a crafting table by my house"), laying out a field, buying or selling
 * at a town's market for its player. Carried out by PaTaskGoal.
 */
public final class PaTask {

    public enum Kind { FUEL, PLACE, FIELD, BUY, SELL, TAME, VTRADE, EQUIP, MAP, PILLAR, SHOOT, BREW }   // [pa33d] the last six

    public final Kind kind;
    @Nullable public final BlockPos pos;
    public final String item;
    public final int amount;
    /** Its working state, for PaTaskGoal: how far along (0..100), and the stage it is at. */
    public int progress, stage, ticks;
    public String result = "";
    public boolean failed;

    public PaTask(Kind kind, @Nullable BlockPos pos, String item, int amount) {
        this.kind = kind;
        this.pos = pos == null ? null : pos.immutable();
        this.item = item == null ? "" : item;
        this.amount = amount;
    }

    public int percent() {
        return Math.max(0, Math.min(100, progress));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("K", kind.name());
        if (pos != null) t.putLong("P", pos.asLong());
        t.putString("I", item);
        t.putInt("N", amount);
        return t;
    }

    @Nullable
    public static PaTask load(CompoundTag t) {
        try {
            return new PaTask(Kind.valueOf(t.getString("K")), t.contains("P") ? BlockPos.of(t.getLong("P")) : null,
                t.getString("I"), t.getInt("N"));
        } catch (Exception e) {
            return null;
        }
    }
}
