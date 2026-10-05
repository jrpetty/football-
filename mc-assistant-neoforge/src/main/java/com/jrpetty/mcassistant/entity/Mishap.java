package com.jrpetty.mcassistant.entity;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * What a folk died of, in words. Every death that was not old age or a raid went down as "by
 * misfortune", in the chronicle and in the books' causes, and the hundred days lost three folk in a week
 * with nothing to say what took them: the water, a fall down a shaft, a creeper in the dark. Now the
 * books and the chronicle say.
 */
public final class Mishap {

    private Mishap() {}

    /** "by drowning", "in a fall", "fighting a zombie"...; "by misfortune" for anything else. Read after "died". */
    public static String how(DamageSource cause) {
        if (cause == null) return "by misfortune";
        Entity by = cause.getEntity();
        if (by != null) {
            String what = by.getType().getDescription().getString().toLowerCase(Locale.ROOT);
            if (by instanceof net.minecraft.world.entity.player.Player) return "fighting a player";
            if (by instanceof AssistantEntity) return "in a fight with another folk";
            return "fighting " + (startsWithVowel(what) ? "an " : "a ") + what;
        }
        if (cause.is(DamageTypeTags.IS_DROWNING)) return "by drowning";
        if (cause.is(DamageTypeTags.IS_FALL)) return "in a fall";
        if (cause.is(DamageTypes.LAVA)) return "in lava";
        if (cause.is(DamageTypeTags.IS_FIRE)) return "in a fire";
        if (cause.is(DamageTypeTags.IS_EXPLOSION)) return "in an explosion";
        if (cause.is(DamageTypes.IN_WALL)) return "trapped in the ground";
        if (cause.is(DamageTypes.STARVE)) return "of hunger";
        if (cause.is(DamageTypeTags.IS_FREEZING)) return "of the cold";
        if (cause.is(DamageTypes.CACTUS) || cause.is(DamageTypes.SWEET_BERRY_BUSH)) return "among the thorns";
        if (cause.is(DamageTypes.FELL_OUT_OF_WORLD)) return "out of the world";
        return "by misfortune";
    }

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiou".indexOf(s.charAt(0)) >= 0;
    }
}
