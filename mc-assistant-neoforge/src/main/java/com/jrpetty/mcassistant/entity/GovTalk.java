package com.jrpetty.mcassistant.entity;

import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * [featcivic] What a folk says when asked how its town is governed, or about the vote: the form in a few plain words, what
 * it thinks of it (its own grievance: Dissent), and where the Town Hall screen it has just opened says the rest.
 */
public final class GovTalk {

    private GovTalk() {}

    public static String answer(VillageFolkEntity f, boolean ballot) {
        UUID village = f.ownerId();
        if (village == null || Identity.known(village) == null) return "We've not settled how we're run yet. Ask me in a day or two.";
        RandomSource r = f.getRandom();
        Government.Form form = Government.of(village);
        if (ballot) {
            Elections.Campaign c = Elections.campaign(village);
            if (!Government.holdsElections(village)) return FolkTalk.pick(r, "Vote? Nobody votes here.", "There's no ballot in " + Villages.name(village) + ".",
                "We don't choose who leads. That's settled elsewhere.", "Vote for what? It's " + form.words + ".",
                "No elections here. Ask the " + Government.title(village) + ".", "Nothing to vote on, I'm afraid.");
            if (c == null || c.counted || c.candidates.isEmpty()) return FolkTalk.pick(r, "No election on just now. Day " + Elections.nextVote(village, f.level().getDayTime() / 24000L) + ", next.",
                "Nothing to vote on yet. Watch the board.", "The next election's a while off.", "When it's called, the box goes out by the board.",
                "You'll see the posters when it's on.", "Not yet. The board will say.");
            return FolkTalk.pick(r, "The ballot's open. Here, have a look.", "Go on, then. It's secret: nobody sees who you pick.", "Make your mark. The box is by the board.",
                "Every vote counts. Well. Most of them.", "Have a read of what they promise first.", "Here's the ballot. Choose carefully.");
        }
        int g = Dissent.grievance(village, f, form).score();
        String what = switch (form) {
            case DEMOCRACY -> "we elect a mayor and a council every ten days";
            case REEVE -> "we elect our " + Government.title(village) + " every ten days";
            case COUNCIL -> "the five eldest run things";
            case LORD -> "the lord rules, and the eldest child after";
            case GUILD -> "the masters of the trades run things";
            case COMMUNE -> "the whole town votes on everything big";
            case CHAPLAIN -> "the chaplain leads us";
            case PLANNED -> "the committee plans everything: jobs, houses, quotas";
            case JUNTA -> "the general commands, and the watch keeps order";
        };
        String verdict = g < 35 ? FolkTalk.pick(r, "It works, mostly.", "Suits me well enough.", "Can't complain. Much.", "It does the job.",
            "I'd not change it.", "Better than most, I'd say.")
            : FolkTalk.pick(r, "Between us, I'd have it otherwise.", "It doesn't suit everyone. Me, for one.", "We'll see how long it lasts.",
            "I'd rather have a say.", "Don't get me started.", "Ask me again when nobody's listening.");
        return Regimes.capital(what) + ". " + verdict + " The Town Hall has the rest.";
    }
}
