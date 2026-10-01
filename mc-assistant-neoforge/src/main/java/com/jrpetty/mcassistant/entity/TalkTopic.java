package com.jrpetty.mcassistant.entity;

/** What a player can say to a folk: the talk screen's buttons, plus anything typed. */
public enum TalkTopic {
    OPEN("Hello"),
    HOW("How are you?"),
    DOING("What are you up to?"),
    ABOUT("Tell me about yourself"),
    PEOPLE("Friends and family?"),
    VILLAGE("Any news?"),
    DREAMS("What do you hope for?"),
    GIFT("Here, this is for you"),
    FAVOUR("Could you spare anything?"),
    FOLLOW("Come with me"),
    STAY("Go back to your day"),
    BYE("Goodbye"),
    SAY(""),
    HOBBY("What do you do for fun?"),
    JOKE("Tell me a joke");

    public final String line;

    TalkTopic(String line) { this.line = line; }

    public static TalkTopic of(int i) {
        TalkTopic[] all = values();
        return i >= 0 && i < all.length ? all[i] : OPEN;
    }
}
