package com.jrpetty.mcassistant.pa.voice;

/**
 * [pa35] How the voice comes, out of the box: replies spoken, the natural voice fetched by itself the first time a player
 * has an assistant (on their own computer: a server can't start or stop it), the basic system voice off (words only
 * until the natural one is ready). Pure, so the defaults and the decision are unit-tested.
 */
public final class PaVoiceDefaults {

    private PaVoiceDefaults() {}

    public static final boolean SPEAK = true;
    public static final String NATURAL = "auto";
    public static final boolean BASIC = false;

    /** Start the natural voice's download now? Only with an assistant, once a session, if not declined and not already there. */
    public static boolean autoDownload(boolean hasAssistant, boolean triedThisSession, String setting, boolean ready, boolean downloading) {
        return hasAssistant && !triedThisSession && "auto".equals(setting) && !ready && !downloading;
    }
}
