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
    JOKE("Tell me a joke"),
    HELP("Can I help with anything?"),
    DELIVER("Here's what you asked for"),
    MEMORY("What do you remember?"),
    REPUTE("What do people think of me?"),
    CHRONICLE("Could I read the village's history?"),
    CENSUS("Who lives here?"),
    TRADE("Got anything to trade?"),
    GOSSIP("Heard any gossip?"),
    CITIZEN("May I live here?"),
    COUNCIL("What's the council deciding?"),
    FINE("I'd like to pay what I owe"),
    RIVALS("What of the other villages?"),
    PROPOSE(""),
    PEACE(""),
    STIR(""),
    QUESTS("What's on the quest board?"),
    HIRE("Come adventuring with me?"),
    COMMISSION("Could you build me a house?"),
    LEDGER("Could I see the town ledger?"),
    STORES(""),
    ORDERS("What are the elder's orders?"),
    WORKINGS("How does your trade work?"),
    SHORT("What is the village short of?"),
    RETRADE(""),
    BUILD(""),
    PACK(""),
    GUIDE("Could you show me the way?"),
    PRAISE("Well done — you're doing a fine job"),
    WORTH("How are you doing for money?"),
    KNACK("What are you good at?");

    public final String line;

    TalkTopic(String line) { this.line = line; }

    public static TalkTopic of(int i) {
        TalkTopic[] all = values();
        return i >= 0 && i < all.length ? all[i] : OPEN;
    }
}
