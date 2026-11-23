package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.guide.GuideClient;
import com.jrpetty.mcassistant.client.guide.GuideSearch;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa33] A question about the mod, asked of the assistant ("how do I start a business?"), answered from the Guide's
 * own engine on the player's client (where the book is) and said back as the assistant's own reply, with the article
 * a click away.
 */
final class PaGuideAnswer {

    private PaGuideAnswer() {}

    static void answer(int id, String q) {
        GuideSearch s = GuideClient.searchForAssistant();
        String text;
        List<String[]> chips = new ArrayList<>();
        if (s == null) {
            text = "I can't reach the Guide just now. Press H to open it yourself.";
        } else {
            GuideSearch.Answer a = s.answer(q, GuideClient.operatorForAssistant());
            if (a.mode() == GuideSearch.Mode.NONE || a.entry() == null) {
                text = "I'm not sure about that one. The Guide might know: press H and ask it.";
                chips.add(new String[]{"Open the Guide", "#guide:"});
            } else {
                String ans = a.entry().answer().replace("{town}", "your town");
                text = ans + (a.entry().steps().isEmpty() ? "" : " (The Guide has the steps: \"" + a.entry().title() + "\".)");
                chips.add(new String[]{"Show me in the Guide", "#guide:" + a.entry().id()});
            }
        }
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        PaClient.send("log", t);
        PaClient.pendingChips = chips;
    }
}
