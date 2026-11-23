package com.jrpetty.mcassistant.pa;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * [pa33d] A level gained: the chime and a shower of sparks, a cheer, a line in its own voice that names what it can do
 * now, and a toast on its player's screen.
 */
public final class PaLevelUp {

    private PaLevelUp() {}

    static void levelUp(PlayerAssistantEntity a, int from, int to) {
        if (!(a.level() instanceof ServerLevel level)) return;
        level.playSound(null, a.getX(), a.getY(), a.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.9F, 1.1F);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, a.getX(), a.getY() + 1.2, a.getZ(), 40, 0.4, 0.6, 0.4, 0.25);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY() + 1.0, a.getZ(), 12, 0.5, 0.5, 0.5, 0.0);
        a.playAnim("cheer");
        String skills = skills(to);
        String line = PaTalk.line(a, "levelup", "level", String.valueOf(to), "skill", skills.isEmpty() ? "a bit of everything" : skills);
        PaData.Rec r = a.rec();
        if (r != null) { r.say(1, line, level.getGameTime()); PaData.touch(); }
        a.mind.lastSaid = a.tickCount;
        PaNet.say(a, line, true, null, PaTalk.bubbleTicks(line));
        if (a.getOwnerPlayer() instanceof ServerPlayer owner) {
            CompoundTag t = new CompoundTag();
            t.putString("name", a.displayNameCap());
            t.putInt("level", to);
            t.putString("skills", skills);
            PaNet.send(owner, PaPayload.LEVEL, t);
        }
    }

    static String skills(int level) {
        StringBuilder b = new StringBuilder();
        for (PaLevels.Skill s : PaLevels.at(level)) b.append(b.length() > 0 ? ", " : "").append(s.label());
        return b.toString();
    }
}
