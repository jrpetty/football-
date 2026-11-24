package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [pa35] The assistant's ear for memory: what it keeps, answers, forgets and declines, from 236 phrasings (British and
 * American, typos, voice transcripts with no punctuation and number words), and that orders stay orders.
 */
class PaMemoryParseTest {

    private static final String[][] CASES = {
            {"Alex is a griefer, don't trust him", "SAVE FOE Alex"},
            {"Alex is a thief", "SAVE FOE Alex"},
            {"Bob stole my diamonds", "SAVE FOE Bob"},
            {"I hate creepers", "SAVE DISLIKE creepers [mob]"},
            {"I like oak not spruce", "SAVE LIKE oak [wood;over:spruce] + DISLIKE spruce"},
            {"I live at 12 Baker Street", "PRIVATE"},
            {"Jo is my best friend, look after her", "SAVE FRIEND Jo [help]"},
            {"Kim can boss you around", "SAVE FRIEND Kim [help,orders]"},
            {"Sam is my friend, help him", "SAVE FRIEND Sam [help]"},
            {"Sam's my mate", "SAVE FRIEND Sam"},
            {"after dark light the place up", "SAVE RULE light the place up=light up the area @NIGHT"},
            {"always carry a stack of torches", "SAVE STOCK torches #64"},
            {"always have some food on you", "SAVE STOCK food #16"},
            {"always keep 32 torches", "SAVE STOCK torches #32"},
            {"always use spruce wood not oak", "SAVE LIKE spruce [wood;over:oak] + DISLIKE oak"},
            {"any reminders", "RECALL reminders"},
            {"at dawn harvest the crops", "SAVE RULE harvest the crops=harvest the crops @MORNING"},
            {"at night go to bed", "SAVE RULE go to bed=go to bed @NIGHT"},
            {"build a small house here", "NONE"},
            {"call me back", "NONE"},
            {"call me boss", "SAVE NAME Boss"},
            {"call me when you're done", "NONE"},
            {"cancel my reminders", "FORGET reminders"},
            {"chop birch, not jungle", "SAVE LIKE birch [wood;over:jungle] + DISLIKE jungle"},
            {"chop that tree", "NONE"},
            {"clear your memory", "FORGET_ALL"},
            {"come home when it gets dark", "SAVE RULE come home=go home @NIGHT"},
            {"do you remember where my mine is", "RECALL_PLACE mine"},
            {"do you trust kevin", "RECALL_PERSON Kevin"},
            {"don't break my builds", "SAVE SPARE builds [#builds]"},
            {"don't break the blue flowers", "SAVE SPARE blue flowers [minecraft:cornflower,minecraft:blue_orchid]"},
            {"don't break the red flowers", "SAVE SPARE red flowers [minecraft:poppy,minecraft:red_tulip,minecraft:rose_bush]"},
            {"don't kill my cows", "SAVE SPARE cows [#animals]"},
            {"don't let me forget to feed the dog when i get home", "SAVE REMINDER feed the dog=feed the dog @HOME #-1"},
            {"don't remember that", "FORGET_LAST"},
            {"don't swear around Sam", "SAVE CLEAN Sam=keep it clean @NEAR [Sam]"},
            {"don't take orders from anyone else", "SAVE ONLY_ME only me"},
            {"don't touch the glass", "SAVE SPARE glass [~glass]"},
            {"don't trust Alex", "SAVE FOE Alex"},
            {"dont touch my poppies", "SAVE SPARE poppies [minecraft:poppy]"},
            {"every morning do a little dance", "ASK"},
            {"every morning refill the furnace", "SAVE RULE refill the furnace=refill the furnace @MORNING"},
            {"every morning, refill the furnace", "SAVE RULE refill the furnace=refill the furnace @MORNING"},
            {"follow me", "NONE"},
            {"forget about the farm", "FORGET farm"},
            {"forget alex", "FORGET alex"},
            {"forget everything", "FORGET_ALL"},
            {"forget everything about Sam", "FORGET Sam"},
            {"forget it", "FORGET_LAST it"},
            {"forget my favourite food", "FORGET favourite food"},
            {"forget that", "FORGET_LAST"},
            {"forget the furnace rule", "FORGET furnace"},
            {"forget what i just said", "FORGET_LAST"},
            {"from now on you're Gizmo", "RENAME Gizmo"},
            {"get me 16 logs", "NONE"},
            {"give me a shout in 30 seconds", "SAVE REMINDER  @TIME #600"},
            {"give me food whenever i'm low", "SAVE RULE give me food=bring me food @LOW"},
            {"go to the farm", "NONE"},
            {"golden carrots are my favourite", "SAVE FAV golden carrots=food"},
            {"good memory", "PRAISED"},
            {"here is my farm", "PLACE farm"},
            {"remember this is my farm", "PLACE farm"},             // [pa35] found filming: "this" was eaten by "remember this"
            {"remember that this is my mine", "PLACE mine"},
            {"hey basil, just so you know, i love cherry wood", "SAVE LIKE cherry wood [wood]"},
            {"i absolutely loathe phantoms", "SAVE DISLIKE phantoms [mob]"},
            {"i can't be doing with spiders", "SAVE DISLIKE spiders [mob]"},
            {"i cant stand zombies", "SAVE DISLIKE zombies [mob]"},
            {"i don't hate creepers anymore", "FORGET creepers"},
            {"i don't trust kevin", "SAVE FOE Kevin"},
            {"i dont like skeletons", "SAVE DISLIKE skeletons [mob]"},
            {"i go by Dev", "SAVE NAME Dev"},
            {"i like birch more than oak", "SAVE LIKE birch [wood;over:oak] + DISLIKE oak"},
            {"i like steak best", "SAVE LIKE steak [food]"},
            {"i love bread", "SAVE LIKE bread [food]"},
            {"i prefer birch to spruce", "SAVE LIKE birch [wood;over:spruce] + DISLIKE spruce"},
            {"i rly hate creepers", "SAVE DISLIKE creepers [mob]"},
            {"i'll call you Rex", "RENAME Rex"},
            {"i'm 12 years old", "PRIVATE"},
            {"i'm scared of endermen", "SAVE DISLIKE endermen [mob]"},
            {"if I die, fetch my stuff", "SAVE RULE fetch my stuff=#fetchdeath @DEATH"},
            {"if i get killed go and get my things", "SAVE RULE go and get my things=#fetchdeath @DEATH"},
            {"if i'm hungry bring me something to eat", "SAVE RULE bring me something to eat=bring me food @LOW"},
            {"ignore everyone else", "SAVE ONLY_ME only me"},
            {"im called dev", "SAVE NAME Dev"},
            {"im not a fan of witches", "SAVE DISLIKE witches [mob]"},
            {"in 10 minutes remind me to check the oven", "SAVE REMINDER check the oven=check the oven @TIME #12000"},
            {"in the morning sort the chests", "SAVE RULE sort the chests=sort the chests @MORNING"},
            {"is alex a griefer", "RECALL_PERSON Alex"},
            {"just call me boss from now on", "SAVE NAME Boss"},
            {"keep 64 cobblestone on you", "SAVE STOCK cobblestone #64"},
            {"keep an eye on alex", "SAVE FOE Alex"},
            {"keep at least 16 arrows", "SAVE STOCK arrows #16"},
            {"keep away from my house", "SAVE SPARE house [#builds]"},
            {"keep following me", "NONE"},
            {"keep it clean around Sam", "SAVE CLEAN Sam=keep it clean @NEAR [Sam]"},
            {"keep quiet", "NONE"},
            {"leave my chests alone", "SAVE SPARE chests [#chests]"},
            {"make sure you have 10 bread", "SAVE STOCK bread #10"},
            {"make this my base", "PLACE base"},
            {"mark this as my storage", "PLACE storage"},
            {"me favourite food is steak", "SAVE FAV steak=food"},
            {"memory on", "RESUME"},
            {"mind your language near Lily", "SAVE CLEAN Lily=keep it clean @NEAR [Lily]"},
            {"mine that iron", "NONE"},
            {"my birthday is the 3rd of May", "PRIVATE"},
            {"my email is dev@example.com", "PRIVATE"},
            {"my fav wood is dark oak", "SAVE FAV dark oak=wood"},
            {"my fave food is porkchops", "SAVE FAV porkchops=food"},
            {"my favorite color is blue", "SAVE FAV blue=colour"},
            {"my favourite block is deepslate", "SAVE FAV deepslate=block"},
            {"my favourite colour's green", "SAVE FAV green=colour"},
            {"my favourite food is steak", "SAVE FAV steak=food"},
            {"my favourite mob is the axolotl", "SAVE FAV axolotl=mob"},
            {"my favourite tree is cherry", "SAVE FAV cherry=tree"},
            {"my house is over there", "PLACE house@look"},
            {"my mine is over there", "PLACE mine@look"},
            {"my name is Dev", "SAVE NAME Dev"},
            {"my name's Dev", "SAVE NAME Dev"},
            {"my number is 07123 456789", "PRIVATE"},
            {"my password is hunter2", "PRIVATE"},
            {"my phone number is 07700 900123", "PRIVATE"},
            {"my pin is 1234", "PRIVATE"},
            {"my postcode is SW1A 1AA and I live there", "PRIVATE"},
            {"my real name is David Smith", "PRIVATE"},
            {"my school is called Oakwood", "PRIVATE"},
            {"name's Dev", "SAVE NAME Dev"},
            {"never chop the cherry trees", "SAVE SPARE cherry trees [minecraft:cherry_log,minecraft:cherry_leaves]"},
            {"never listen to steve", "SAVE FOE Steve"},
            {"never run out of torches", "SAVE STOCK torches #16"},
            {"never touch my chests", "SAVE SPARE chests [#chests]"},
            {"no swearing in front of Sam", "SAVE CLEAN Sam=keep it clean @NEAR [Sam]"},
            {"off the record", "PAUSE"},
            {"only listen to me", "SAVE ONLY_ME only me"},
            {"only take orders from me", "SAVE ONLY_ME only me"},
            {"over there is my mine", "PLACE mine@look"},
            {"people call me DJ", "SAVE NAME DJ"},
            {"please don't trample my crops", "SAVE SPARE crops [#crops]"},
            {"protect Kim", "SAVE FRIEND Kim [help]"},
            {"put everything in that chest", "NONE"},
            {"remember here as my base", "PLACE base"},
            {"remember my password is fish", "PRIVATE"},
            {"remember that the diamonds are under the big oak", "SAVE FACT the diamonds are under the big oak"},
            {"remember the diamonds are at -120 64 300", "SAVE FACT the diamonds are at -120 64 300"},
            {"remember this spot as the farm", "PLACE farm"},
            {"remember this spot as the mine", "PLACE mine"},
            {"remind me", "ASK"},
            {"remind me about the furnace in 5 minutes", "SAVE REMINDER the furnace=the furnace @TIME #6000"},
            {"remind me at dawn to plant the wheat", "SAVE REMINDER plant the wheat=plant the wheat @MORNING #-1"},
            {"remind me in 10 minutes to check the furnace", "SAVE REMINDER check the furnace=check the furnace @TIME #12000"},
            {"remind me in 2 minutes to eat", "SAVE REMINDER eat=eat @TIME #2400"},
            {"remind me in a few minutes to log off", "SAVE REMINDER log off=log off @TIME #3600"},
            {"remind me in an hour to check the farm", "SAVE REMINDER check the farm=check the farm @TIME #72000"},
            {"remind me in five minutes to check the furnace", "SAVE REMINDER check the furnace=check the furnace @TIME #6000"},
            {"remind me in half an hour to feed the dog", "SAVE REMINDER feed the dog=feed the dog @TIME #36000"},
            {"remind me in twenty five minutes to move", "SAVE REMINDER move=move @TIME #30000"},
            {"remind me to eat", "ASK"},
            {"remind me tomorrow morning to plant carrots", "SAVE REMINDER plant carrots=plant carrots @MORNING #-1"},
            {"remind me tonight to sleep", "SAVE REMINDER sleep=sleep @NIGHT #-1"},
            {"remind me when I get home to empty my pack", "SAVE REMINDER empty your pack=empty your pack @HOME #-1"},
            {"sam is a friend of mine", "SAVE FRIEND Sam"},
            {"sam isn't my friend anymore", "FORGET sam"},
            {"scratch that", "FORGET_LAST"},
            {"set a reminder for 15 minutes to feed the cat", "SAVE REMINDER feed the cat=feed the cat @TIME #18000"},
            {"show me your memories", "RECALL_ALL"},
            {"start remembering again", "RESUME"},
            {"stay out of my chests", "SAVE SPARE chests [#chests]"},
            {"steak is my favourite food", "SAVE FAV steak=food"},
            {"stop", "NONE"},
            {"stop remembering things", "PAUSE"},
            {"tell me a joke", "NONE"},
            {"tell me everything you remember", "RECALL_ALL"},
            {"that's my tower", "PLACE tower@look"},
            {"the diamonds are at 120 64 -300", "NONE"},
            {"the farm is here", "PLACE farm"},
            {"the nether portal is here", "PLACE nether portal"},
            {"the sheep are off limits", "SAVE SPARE sheep [#animals]"},
            {"the village is that way", "PLACE village@look"},
            {"this is home", "PLACE home"},
            {"this is my base", "PLACE base"},
            {"this is where i live", "PLACE home"},
            {"this spot is my mine", "PLACE mine"},
            {"ur rubbish at building", "SAVE SELF building #-1"},
            {"use oak not spruce", "SAVE LIKE oak [wood;over:spruce] + DISLIKE spruce"},
            {"warn me when steve shows up", "SAVE RULE warn me=#warn @NEAR [Steve]"},
            {"watch out for Herobrine", "SAVE FOE Herobrine"},
            {"welcome to my castle", "PLACE castle"},
            {"what are you doing", "NONE"},
            {"what are your rules", "RECALL rules"},
            {"what do i hate", "RECALL dislikes"},
            {"what do i like", "RECALL likes"},
            {"what do you call me", "RECALL name"},
            {"what do you know about me", "RECALL_ALL me"},
            {"what do you remember about sam", "RECALL_ALL sam"},
            {"what do you remember?", "RECALL_ALL"},
            {"what do you think of sam", "RECALL_PERSON Sam"},
            {"what food do i like", "RECALL favourite food"},
            {"what have i told you", "RECALL_ALL"},
            {"what is the nearest village", "NONE"},
            {"what's my favourite food?", "RECALL favourite food"},
            {"what's my name", "RECALL name"},
            {"when Alex is around warn me", "SAVE RULE warn me=#warn @NEAR [Alex]"},
            {"when i die get my stuff back", "SAVE RULE get my stuff back=#fetchdeath @DEATH"},
            {"when i get home put everything away", "SAVE RULE put everything away=put everything away @HOME"},
            {"when i'm hurt give me food", "SAVE RULE give me food=bring me food @LOW"},
            {"when it gets dark come home", "SAVE RULE come home=go home @NIGHT"},
            {"when it rains come home", "SAVE RULE come home=go home @RAIN"},
            {"when it's night, come home", "SAVE RULE come home=go home @NIGHT"},
            {"when sam comes round keep it clean", "SAVE CLEAN Sam=keep it clean @NEAR [Sam]"},
            {"when you're free sort the chests", "SAVE RULE sort the chests=sort the chests @IDLE"},
            {"whenever I'm low, give me food", "SAVE RULE give me food=bring me food @LOW"},
            {"where are you", "NONE"},
            {"where is our base", "RECALL_PLACE base"},
            {"where's my mine?", "RECALL_PLACE mine"},
            {"where's the farm", "RECALL_PLACE farm"},
            {"where's the nearest iron", "NONE"},
            {"which way to the farm", "RECALL_PLACE farm"},
            {"who am i", "RECALL name"},
            {"who are my friends", "RECALL people"},
            {"who is sam", "RECALL_PERSON Sam"},
            {"who's alex", "RECALL_PERSON Alex"},
            {"wipe your memory", "FORGET_ALL"},
            {"wow you remembered", "PRAISED"},
            {"you are terrible at jokes", "SAVE SELF jokes #-1"},
            {"you can call me the boss", "SAVE NAME Boss"},
            {"you can't fight for toffee", "SAVE SELF fighting #-1"},
            {"you never forget", "PRAISED"},
            {"you only work for me", "SAVE ONLY_ME only me"},
            {"you remembered my favourite food", "PRAISED"},
            {"you remembered!", "PRAISED"},
            {"you suck at archery", "SAVE SELF archery #-1"},
            {"you're a great builder", "SAVE SELF building #1"},
            {"you're a rubbish miner", "SAVE SELF mining #-1"},
            {"you're brilliant at farming", "SAVE SELF farming #1"},
            {"you're funny", "NONE"},
            {"you're rubbish at mining", "SAVE SELF mining #-1"},
            {"you're useless at fighting", "SAVE SELF fighting #-1"},
            {"your name is now Bob", "RENAME Bob"},   // [pa36b] a name keeps the capitals it was said with
            {"your new name is Biscuit", "RENAME Biscuit"}
    };

    @Test
    void everyPhrasingReadsAsExpected() {
        List<String> bad = new ArrayList<>();
        for (String[] c : CASES) {
            String got = PaMemoryParse.read(c[0], "Basil").brief();
            if (!got.equals(c[1])) bad.add("\"" + c[0] + "\": expected " + c[1] + " got " + got);
        }
        assertTrue(CASES.length >= 100, "100+ phrasings: " + CASES.length);
        assertTrue(bad.isEmpty(), bad.size() + " misread:\n" + String.join("\n", bad));
    }

    @Test
    void secretsAreNeverKept() {
        for (String s : List.of("my password is hunter2", "my pin is 1234", "remember my email is a.b@c.co.uk", "my phone number is +44 7700 900123",
                "I live at 4 Privet Drive", "my postcode is SW1A 1AA where I live", "my date of birth is 1 April 2010", "my real name is Jo Bloggs",
                "remember my bank card number is 4111 1111 1111 1111", "i'm 11 years old"))
            assertEquals(PaMemoryParse.Op.PRIVATE, PaMemoryParse.read(s, "Basil").op(), s);
        // coordinates and amounts are not phone numbers
        assertFalse(PaMemoryParse.secret("remember the diamonds are at -1200 12 3400"));
        assertFalse(PaMemoryParse.secret("always keep 64 torches"));
    }

    @Test
    void ordersStayOrders() {
        for (String s : List.of("follow me", "get me 16 logs", "mine that iron", "build a small house here", "go to the farm", "stop", "come here",
                "put everything in that chest", "what are you doing", "where are you", "tell me a joke", "keep following me", "where's the nearest iron"))
            assertEquals(PaMemoryParse.Op.NONE, PaMemoryParse.read(s, "Basil").op(), s);
    }

    @Test
    void ruleActionsAreOrdersItCanRun() {
        assertEquals("go home", PaMemoryParse.action("come home"));
        assertEquals("#fetchdeath", PaMemoryParse.action("fetch my stuff"));
        assertEquals("bring me food", PaMemoryParse.action("give me food"));
        assertEquals("refill the furnace", PaMemoryParse.action("refill the furnace"));
        assertEquals(null, PaMemoryParse.action("do a little dance"));
    }

    @Test
    void sameThingSaidAgainReplacesIt() {
        PaMemory.Book b = new PaMemory.Book();
        b.put(PaMemoryParse.read("my favourite food is steak", "").entry(), 1);
        b.put(PaMemoryParse.read("my favourite food is bread", "").entry(), 2);
        assertEquals(1, b.size());
        assertEquals("bread", b.favourite("food"));
        b.put(PaMemoryParse.read("Sam is my friend", "").entry(), 3);
        b.put(PaMemoryParse.read("Sam is a griefer", "").entry(), 4);
        assertTrue(b.foe("sam") && !b.friend("sam"), "a friend turned foe is one entry");
        assertEquals(1, b.about("sam").size());
    }
}
