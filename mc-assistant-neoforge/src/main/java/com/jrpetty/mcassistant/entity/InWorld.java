package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** [player15] Everything a player can do, and where: filled in below. */
public final class InWorld implements PlayerLife.Page {
    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        return new LifePage("all", "Everything you can do", "", p.registryAccess());
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        return null;
    }
}
