package com.jrpetty.mcassistant;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [anim] The player's own settings for how much of the folk's life is drawn (config/mc_assistant-client.toml): the
 * sparks, chips, steam and notes their work and play give off, the little pictures over their heads, their faces, and
 * the camera's turn round to the player's own emote. A slow machine, or a town of hundreds, can turn the effects down.
 */
public final class AnimConfig {

    private AnimConfig() {}

    public enum Effects { ALL, SOME, NONE }

    /** [vision27] The folk's name tags: everything the server writes, the name (and a stuck folk's warning, a hurt one's
     *  health), only the one you look at, or none. */
    public enum Plates { FULL, NAME, LOOK, OFF }

    public static final ModConfigSpec SPEC;
    /** The particles of the folk's work and life: all of them, a few (one in three, and only close by), or none. */
    public static final ModConfigSpec.EnumValue<Effects> EFFECTS;
    /** How far off (blocks) a folk's effects and its face are drawn. */
    public static final ModConfigSpec.IntValue EFFECTS_RANGE;
    /** The little pictures over the folk's heads (an idea, a coin, a question, a heart, Zzz). */
    public static final ModConfigSpec.BooleanValue MARKS;
    /** Faces that blink, smile, frown and talk. */
    public static final ModConfigSpec.BooleanValue FACES;
    /** The camera turned round to watch the player's own emote in first person, and back after. */
    public static final ModConfigSpec.BooleanValue EMOTE_CAMERA;
    /** [vision27] The name tags over the folk's heads, and how far off they show (fading out towards the edge). */
    public static final ModConfigSpec.EnumValue<Plates> PLATES;
    public static final ModConfigSpec.IntValue PLATE_RANGE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("animations");
        EFFECTS = b.comment("Particles from the folk's work and life (anvil sparks, wood chips, steam, music notes): ALL, SOME or NONE.")
            .defineEnum("effects", Effects.ALL);
        EFFECTS_RANGE = b.comment("How far away (blocks) the folk's effects and faces are drawn.")
            .defineInRange("effectsRange", 24, 8, 64);
        MARKS = b.comment("Little pictures over the folk's heads when something happens (an idea, a coin, a question).")
            .define("marks", true);
        FACES = b.comment("Faces that blink, smile, frown and move as the folk talk.")
            .define("faces", true);
        EMOTE_CAMERA = b.comment("In first person, turn the camera round to watch your own emote, and back after.")
            .define("emoteCamera", true);
        b.pop();
        b.push("labels");                                                                       // [vision27]
        PLATES = b.comment("The folk's name tags: FULL (trade, level, name, badges, health), NAME (the name, a stuck folk's warning,",
                "a hurt one's health), LOOK (only the folk you look at) or OFF. F1 and the camera's clean shot hide them all.")
            .defineEnum("nameTags", Plates.NAME);
        PLATE_RANGE = b.comment("How far away (blocks) name tags show; they fade out over the last third of it.")
            .defineInRange("nameTagRange", 32, 8, 64);
        b.pop();
        SPEC = b.build();
    }

    /** Safe before the file is read (a test, the first frame): the defaults. */
    public static Effects effects() {
        try { return EFFECTS.get(); } catch (RuntimeException e) { return Effects.ALL; }
    }

    public static int range() {
        try { return EFFECTS_RANGE.get(); } catch (RuntimeException e) { return 24; }
    }

    public static boolean marks() {
        try { return MARKS.get(); } catch (RuntimeException e) { return true; }
    }

    public static boolean faces() {
        try { return FACES.get(); } catch (RuntimeException e) { return true; }
    }

    public static boolean emoteCamera() {
        try { return EMOTE_CAMERA.get(); } catch (RuntimeException e) { return true; }
    }

    /** [vision27] */
    public static Plates plates() {
        try { return PLATES.get(); } catch (RuntimeException e) { return Plates.NAME; }
    }

    public static int plateRange() {
        try { return PLATE_RANGE.get(); } catch (RuntimeException e) { return 32; }
    }

    public static void setPlates(Plates p) {
        try { PLATES.set(p); SPEC.save(); } catch (RuntimeException ignored) { }
    }
}
