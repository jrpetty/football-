package com.jrpetty.mcassistant.client.guide;

/**
 * [guide31] What the Guide knows of the player asking, from the server's few facts (entity/GuideFacts): the town they
 * stand in ("" out in the wild), what they are to it, whether they are its citizen or lead it, a town of their own
 * anywhere, a shop and businesses there. {@link #UNKNOWN} until the server answers (an older server never does): then
 * every answer is the plain one.
 */
public record PlayerFacts(boolean known, String town, String title, boolean citizen, boolean ownTown, boolean shop, int firms,
                          boolean leader) {

    public static final PlayerFacts UNKNOWN = new PlayerFacts(false, "", "", false, false, false, 0, false);

    public boolean inTown() {
        return known && !town.isEmpty();
    }
}
