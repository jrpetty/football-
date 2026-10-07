package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.Quill.Block;
import com.jrpetty.mcassistant.village.Quill.Dice;
import com.jrpetty.mcassistant.village.Quill.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jrpetty.mcassistant.village.Quill.cap;
import static com.jrpetty.mcassistant.village.Quill.list;
import static com.jrpetty.mcassistant.village.Quill.number;
import static com.jrpetty.mcassistant.village.Quill.ordinal;
import static com.jrpetty.mcassistant.village.Quill.stop;

/**
 * A trade's book of best practice, written by its master out of the town's own books. [library]
 *
 * <p>Each trade a town works has one book ("The Farmer's Book of Thornhurst"), and the best of that trade
 * keeps it. What goes in it is what the town really did and really knows, handed over as plain facts
 * (entity/TradeBooks gathers them): what the trade brought in this week and in all, its best day and
 * whose, what works and why (a field watered, lit and composted; the mine where it is; trees replanted as
 * they are felled), what went wrong (a miner lost in lava on day fifty-one, the fire at the smeltery), what
 * the age lets it use and what the next one will, the master's own knacks and memories, and who works at
 * it. The craft's own lore (water within four blocks of the wheat; a coal fires eight, a plank one and a
 * half) is the game's, and true.
 *
 * <p>It is written in the master's voice (Quill.Voice): a grump is short with the reader, a shy one says
 * sorry, a curious one wonders aloud. Each edition is dated, and an edition after the first opens with
 * what is new in it: a new record, a new age and what it brings, a death or a fire and what it taught.
 */
public final class TradeBookWriter {

    private TradeBookWriter() {}

    // ------------------------------------------------------------------ the facts

    /** The master: who writes it. */
    public record Person(String name, int level, List<String> traits, String quirk, String hobby, int age, String origin,
                         long since, List<String> knacks, String branch, String memory, long memoryDay, String partner) {}

    /** One of the trade's hands: its name, its level, and a word about it. */
    public record Hand(String name, int level, String note) {}

    /** Something the trade makes: what, how many in all the books keep, in the last week, its best day and when. */
    public record Made(String what, long total, int week, int best, long bestOn) {}

    /**
     * Something that went wrong: the day, what kind of thing ("death", "fire", "storm", "drought", "flood",
     * "raid", "war", "famine", "collapse", "lost", "other"), who it befell, and what happened in words
     * ("in lava" for a death; the chronicle's line for the rest).
     */
    public record Lesson(long day, String kind, String who, String what) {}

    /**
     * What has changed since the last edition: its kind ("master", "age", "record", "lesson", "research",
     * "hands", "top", "revised") and the particulars: what it is now and was, a day, and numbers now and then.
     */
    public record Change(String kind, String now, String was, long day, long n, long wasN) {}

    /**
     * Everything the book is written from. The town and the trade (its key: "FARM"), the day it is
     * written and when in the town's year ("summer, the town's second year"), which edition and the days of
     * the editions before it; the master and the hands; what the trade made (most first), over how many days
     * of the books, this week's tally and last week's, the best day and its main thing, the best before it,
     * all it has made, its worth a day in coin, the master's own tally of its deeds; what went wrong; the age
     * and the day it came, the next age, the town's research; notes on what works out of the town's own books;
     * and what is new since the last edition.
     */
    public record Facts(String town, String key, long day, String date, int edition, List<Long> earlier,
                        Person master, List<Hand> hands, List<Made> made, int days, int week, int lastWeek,
                        int best, long bestOn, String bestWhat, long allTime, int worth, long deeds,
                        List<Lesson> lessons, String age, long ageSince, String nextAge, int research,
                        List<String> notes, List<Change> changes) {}

    // ------------------------------------------------------------------ the crafts

    /**
     * A trade's lore: what its folk are called ("farmer", "Farmer's"), where it works ("the fields"), what it
     * does ("farming"), what it brings in ("crops"), its deeds ("crops brought in"), what is true of the work,
     * what each age gives it (Wood, Stone, Iron, Diamond, Nether), and a joke.
     */
    public record Craft(String noun, String possessive, String place, String work, String yield, String deedWord,
                        String[] wisdom, String[] ages, String joke) {}

    private static final Map<String, Craft> CRAFTS = new HashMap<>();

    private static void craft(String key, Craft c) {
        CRAFTS.put(key, c);
    }

    static {
        craft("FARM", new Craft("farmer", "Farmer's", "the fields", "farming", "the harvest", "crops brought in", new String[]{
            "Water within four squares of every crop. A dry field will not grow one jot faster for all the tending in the world.",
            "Light the edge of the field with torches before dusk. Nothing spawns in a lit field to trample the rows, and nobody meets a zombie among the wheat at dawn.",
            "Put a composter by your work chest and feed it the seed you can't use. It gives back bone meal, and bone meal on a growing crop is a day saved.",
            "Replant as you reap. A bare square of farmland is a square that earns nothing.",
            "Spring is the quickest growing, then summer; autumn slows, and in winter a tended field grows at little more than half its pace. Sow hard in spring and store in autumn.",
            "Crush the watch's bones and the hunters' for bone meal, and keep sixteen in your pack. A farmer without bone meal is a farmer waiting.",
            "Three wheat make a loaf. Count the mouths, then count the wheat, and you'll know where you stand.",
            "Walk the paths, never the rows: farmland jumped on turns back to dirt, and the seed with it.",
            "If a quarter of the field is ripe, bring it in before you take your break. The break will wait; the stores won't.",
        }, new String[]{
            "wooden hoes, and seed pulled out of the long grass",
            "stone hoes, and the composter's bone meal",
            "iron hoes, and buckets, so water goes where the brook won't reach",
            "diamond hoes, which hardly ever want replacing",
            "netherite hoes, if you can believe it, and warts from the Nether for the brewer",
        }, "Why did the scarecrow win a prize? Because he was outstanding in his field."));
        craft("MINE", new Craft("miner", "Miner's", "the mine", "mining", "the ore", "veins dug", new String[]{
            "Cut stairs, never a shaft straight down. A stair you can walk back up; a shaft you can only fall down.",
            "Never dig straight up, and never straight down. Lava and gravel both like to surprise a miner, and neither says sorry.",
            "Iron lies in the middle depths. Go deeper only when the age wants diamonds: the deep rock is slow, dark and hot.",
            "Set a torch at every turning, so the way home is lit and nothing waits for you round the corner.",
            "Bring up the coal as you go. The smelters want it as much as they want the iron.",
            "Fence the drops you leave behind you. The town's fences round the shafts are there because somebody fell.",
            "Listen at the rock. Water and lava both make a noise behind a thin wall, if you're quiet enough to hear it.",
            "Work your own face and leave the next one be: the faces lie side by side, and the galleries run into one another as they should.",
        }, new String[]{
            "wooden picks, which will take stone and coal and nothing harder",
            "stone picks: iron at last, and copper",
            "iron picks: gold, redstone, and the diamonds if we go deep enough",
            "diamond picks: obsidian, and anything else the rock has",
            "netherite, and the deep rock holds nothing back",
        }, "What did the pick say to the stone? Nothing. It just got straight to the point."));
        craft("WOOD", new Craft("woodcutter", "Woodcutter's", "the woods", "felling", "the timber", "trees felled", new String[]{
            "Replant every tree you fell. A sapling in the ground is a log for your grandchildren.",
            "Take the whole tree, top to bottom. A crown left hanging in the air is an eyesore, and a fire waiting to happen.",
            "Fell from the edge of the wood inwards, so the paths stay open behind you.",
            "Leave the trees by the houses. Shade over a doorstep is worth more than the planks.",
            "Oak and birch come quickest from a sapling. A spruce or a dark oak wants room: four saplings together for the big ones.",
            "Carry a spare axe. An axe always breaks at the far end of the wood, never by the stores.",
            "Bring the saplings home as well as the logs. The woods only give what you put back.",
        }, new String[]{
            "wooden axes, slow but honest",
            "stone axes, twice the pace",
            "iron axes, and shears for the leaves",
            "diamond axes, that fell an oak in three swings",
            "netherite axes, and the strange woods of the Nether",
        }, "Why did the woodcutter go to the doctor? He kept feeling knotty."));
        craft("FISH", new Craft("fisher", "Fisher's", "the water", "fishing", "the catch", "fish caught", new String[]{
            "Fish bite better in the rain. A wet day is a good day at the water, whatever the farmers say.",
            "Cast into open water, away from the bank and out from under the trees. A line in the shade catches less.",
            "A jetty out over deep water is worth a day's work: you'll fish from it for years.",
            "Patience. The fish come when they come, and a fidget catches nothing.",
            "Cod for the many, salmon for the feast. Bring both to the stores, and the cook will thank you.",
            "Mind the edge in the dark, and never fish alone in a storm.",
            "Keep a spare line and a spare rod. A rod breaks on the big one, never the little ones.",
        }, new String[]{
            "rods of sticks and string, and the bank to stand on",
            "rods, and boats, and the furnace to cook the catch",
            "buckets, to carry a fish home living",
            "rods that last a season, mended at the table",
            "rods of every kind, and the strange seas of the far lands",
        }, "What did the fish say when it swam into the wall? Dam."));
        craft("RANCH", new Craft("rancher", "Rancher's", "the pens", "ranching", "the herd", "animals bred", new String[]{
            "Wheat for cows and sheep, carrots for pigs, seeds for chickens. Two of a kind and their food, and soon there'll be three.",
            "Never take the last pair. A pen with two left in it is a pen with a future.",
            "Shear the sheep; don't kill them. Wool grows back. Mutton doesn't.",
            "Keep the pen's gate shut behind you. Every animal that wanders out is one somebody has to walk home.",
            "Light the pen at night. The herd sleeps sound when nothing creeps in among it.",
            "Don't crowd them. A pen packed to the fence breeds slower than one with room.",
        }, new String[]{
            "a fence and a gate and a handful of wheat",
            "leather, and the tanner's work",
            "shears and leads, and the saddles for the stable",
            "golden carrots, for the horses worth having",
            "every beast there is, and some there oughtn't to be",
        }, "Why don't sheep go to the tavern? They're happy to stay at home and be shorn."));
        craft("GUARD", new Craft("guard", "Guard's", "the wall", "keeping watch", "the watch", "hostiles seen off", new String[]{
            "Light the wall and the gates before dusk. What can't hide can't creep.",
            "When the bell rings, go to your post on the wall. Don't chase anything out of the gate: the gate is where they want you.",
            "A bow on the wall is worth three swords in the street.",
            "Keep your armour mended. The smith will see to it if you ask before the raid, not after.",
            "Watch the edges at first light. The last of the night's things linger in the shade.",
            "Know every face in the town. A stranger in the square after dark is a question to ask.",
            "Walk the round, and walk it differently every night.",
        }, new String[]{
            "a wooden sword and a loud voice",
            "stone swords, leather armour, and the wall",
            "iron swords and iron mail, shields, and the watchtower",
            "diamond blades, and armour that turns an arrow",
            "netherite, and the courage to go through the gateway",
        }, "How many guards does it take to light a torch? None. It's always someone else's shift."));
        craft("SMELT", new Craft("smelter", "Smelter's", "the smeltery", "smelting", "the iron", "loads smelted", new String[]{
            "A coal fires eight, a plank one and a half. Burn the coal and save the planks for the builders.",
            "Keep every furnace going. A cold furnace is iron sitting about doing nothing.",
            "When the coal runs short, burn logs for charcoal: a log in, a coal out, and the fire never stops.",
            "Raw iron, raw copper and raw gold all want the same fire. Fill the top, feed the bottom, empty the side.",
            "Keep a bucket of water by the furnaces. You'll be glad of it one day.",
            "Smelt the sand into glass when the iron's slow. The builders always want windows.",
        }, new String[]{
            "nothing much: no furnace yet",
            "the furnace, and bricks, glass and charcoal",
            "iron and gold in the fire, and the smithing table after",
            "a smelter's furnace never cold, and blast furnaces for the ore",
            "the Nether's gold and its ancient debris, into netherite",
        }, "Why was the furnace so popular? Everybody warmed to it."));
        craft("COOK", new Craft("cook", "Cook's", "the kitchen", "cooking", "the meals", "meals made", new String[]{
            "Three wheat make a loaf of bread, and a smoker cooks meat twice as quick as a furnace.",
            "Cook what the stores are full of, not what you fancy. A cook who wastes is no cook.",
            "Feed the workers first. A full farmer is a fast farmer.",
            "Keep the café warm and the counter clean, and folk will spend their coins there gladly.",
            "Honey and sugar make the sweets folk save their coins for. A cake on market day sells itself.",
            "Stew uses a bowl; give the bowl back to the stores.",
        }, new String[]{
            "bread, and stew in a bowl",
            "the furnace and the smoker: cooked meat and baked potatoes",
            "cake and pie, with the iron for the pans",
            "golden carrots and golden apples for the sick",
            "whatever the Nether grows, if you dare",
        }, "Why did the cook go to the café? To see what all the stir was about."));
        craft("SMITH", new Craft("smith", "Smith's", "the forge", "smithing", "the iron work", "things made", new String[]{
            "Mend before you make. A tool mended is iron saved.",
            "Make what the age allows and no more. There's no shame in a good stone pick.",
            "Your mark goes on everything you make. Make it worth putting your name to.",
            "Arm the watch first, then the miners, then everybody else.",
            "Keep an ingot or two by for the anvil. An anvil wears out, and a smith without one is just a person with a hammer.",
            "Practise on the simple things. A beginner's sword is a danger to its owner.",
        }, new String[]{
            "wooden tools at the bench, and nothing to forge",
            "stone tools and leather armour",
            "iron: tools, swords, mail and shields, and the smithing table",
            "diamond tools and armour, the finest of the age",
            "netherite at the smithing table, the best there is",
        }, "Why did the smith never lose an argument? He always had the last hammer."));
        craft("TAILOR", new Craft("tailor", "Tailor's", "the workshop", "tailoring", "the cloth", "things made", new String[]{
            "A bed for every folk before a banner for any wall.",
            "White wool takes any dye. Buy plain and colour it yourself.",
            "Leather from the hunters and the ranchers makes the watch's first armour.",
            "Weave the town's banner true: the colours and the charge as the founders chose them.",
            "Carpets for the homes in winter. A warm floor makes a happy house.",
        }, new String[]{
            "wool, string and the loom",
            "leather, and armour of it for the watch",
            "the shears of iron, and banners with a border",
            "fine cloth for the town's best homes",
            "the strangest dyes the world has",
        }, "Why did the tailor lose the race? He was always a stitch behind."));
        craft("BEEKEEP", new Craft("beekeeper", "Beekeeper's", "the hives", "beekeeping", "the honey", "things made", new String[]{
            "Shears for comb, a bottle for honey. Take it when the hive is full and dripping, and not before.",
            "Light a campfire under the hive and the bees stay calm while you take the honey.",
            "Flowers round the hives, and plenty of them. No flowers, no honey.",
            "Four hives at most for one keeper. More than that and you can't keep up.",
            "Two bees and a flower each, and there'll be a third.",
            "Make new hives of the comb and planks, when the bees fill the old ones.",
        }, new String[]{
            "a hive, and a bottle if you're lucky",
            "candles out of the comb, and the campfire's smoke",
            "iron shears for the comb",
            "honey blocks for the builders",
            "every flower there is",
        }, "What's a bee's favourite song? Anything by the Bee Gees. I'll get my hat."));
        craft("BREW", new Craft("brewer", "Brewer's", "the brewery", "brewing", "the potions", "things made", new String[]{
            "Nether wart first, then the ingredient. A water bottle with a spider's eye in it is just a nasty bottle.",
            "Blaze powder fuels the stand: one powder brews twenty times. Don't waste it.",
            "Healing for the watch before anything fancy.",
            "Label everything. A potion of healing and a potion of harming look alike in a hurry.",
        }, new String[]{
            "bottles of water and hope",
            "glass bottles of our own",
            "the brewing stand, at last",
            "the strongest healing",
            "the Nether's wart and blaze, and everything they make",
        }, "Why did the brewer cross the road? To get to the other potion."));
        craft("ENCHANT", new Craft("enchanter", "Enchanter's", "the library", "enchanting", "the enchantments", "things made", new String[]{
            "Fifteen bookshelves round the table, one block off it, for the strongest work.",
            "Lapis for every enchantment. Keep the miners looking for it.",
            "Enchant the best tools first. An enchanted iron pick outlasts two plain ones.",
            "Books keep an enchantment till it's wanted. Bind them when the lapis is plentiful.",
        }, new String[]{
            "nothing yet but a book",
            "books and bookshelves",
            "lapis, and the anvil for the books",
            "the enchanting table at its strongest",
            "everything the table can give",
        }, "Why did the enchanter fail the exam? Too many spells in the test."));
        craft("SHOP", new Craft("shopkeeper", "Shopkeeper's", "the shop", "keeping shop", "the takings", "things sold", new String[]{
            "Price fair and sell often. A full shelf earns nothing.",
            "Know what the town makes and what it lacks. Sell the first, buy the second.",
            "A smile at the counter is worth a coin. Folk come back to a friendly face.",
            "Keep the town's own wants back from the shelf. We feed ourselves before we sell.",
        }, new String[]{
            "a counter and a few planks",
            "stone tools and leather goods",
            "iron tools and shields, the workshop's best",
            "diamonds on the shelf, under lock and key",
            "netherite, for those who can pay",
        }, "Why did the shopkeeper sell the clock? It was taking up too much time."));
        craft("STORE", new Craft("storekeeper", "Storekeeper's", "the storehouse", "storekeeping", "the stores", "chests tidied", new String[]{
            "A place for everything: like with like, and what's used most at the front.",
            "Keep back what the age wants and the builders' stone; let the rest go where it's needed.",
            "Count the stores every morning. A shortage seen early is a shortage solved.",
            "Write down what goes out and who took it. A borrowed pick has a way of not coming back.",
        }, new String[]{
            "a chest or two",
            "the storehouse, and its units",
            "the couriers and their runs",
            "a storehouse that never runs out",
            "every good thing in the world, somewhere in the stores",
        }, "Why was the storekeeper so calm? Everything was in its place."));
        craft("HAUL", new Craft("hauler", "Hauler's", "the roads", "hauling", "the loads", "loads delivered", new String[]{
            "Full loads, never half. A walk with half a load is half a walk wasted.",
            "The storehouse first, then the shop, then the café.",
            "Know the roads. The paved way is quicker than the field, even when it's longer.",
            "Rest your back. A hauler who can't stand up hauls nothing.",
        }, new String[]{
            "two arms and a strong back",
            "chests on the roads",
            "the stable's horses, and a chest on a donkey",
            "the long roads to the other towns",
            "everything there is to carry",
        }, "Why did the hauler bring a ladder? To get to the next level."));
        craft("HUNT", new Craft("hunter", "Hunter's", "the wilds", "hunting", "the game", "hostiles seen off", new String[]{
            "Never take the last of a herd in the wild. Leave two, and there'll be more next year.",
            "Hunt by day. The night belongs to worse hunters than us.",
            "Bring back the hide as well as the meat. The tailor will thank you.",
            "Know the way home before you go out.",
        }, new String[]{
            "a wooden sword and quick feet",
            "a bow, and stone for the arrows",
            "iron, and a quiver that never empties",
            "diamond, and the deep wilds",
            "everything that walks",
        }, "Why did the hunter bring a pencil? To draw his bow."));
        craft("SCOUT", new Craft("scout", "Scout's", "the road", "scouting", "the finds", "finds made", new String[]{
            "Mark everything. A find that isn't on the map is a find lost.",
            "Turn back with half your food left.",
            "Ride when you can, walk when you must, and never swim when you needn't.",
            "Tell the town which way you're going. If you don't come back, somebody needs to know where to look.",
        }, new String[]{
            "our own two feet",
            "a compass, and a map of our own drawing",
            "the stable's horses",
            "the farthest lands",
            "the Nether, and beyond",
        }, "Why did the scout bring a map to the tavern? In case the conversation got lost."));
        craft("BANK", new Craft("banker", "Banker's", "the bank", "banking", "the savings", "loans made", new String[]{
            "Lend to the hardworking, and keep enough in the vault for the morning.",
            "A debt written down is a debt remembered.",
            "Every coin in the vault is somebody's work. Treat it so.",
        }, new String[]{ "a purse", "a strongbox", "the vault and its bars", "a vault of gold", "the riches of the Nether" },
            "Why did the banker leave? He'd lost interest."));
        craft("CAVE", new Craft("cave dweller", "Cave Dweller's", "the caves", "caving", "the finds", "hostiles seen off", new String[]{
            "Light every cave you go into, and mark the way out with torches on your right.",
            "A spawner is broken or lit up. Never leave one as you found it.",
            "Bring everything home from the old chests in the mineshafts. The museum may want it.",
            "Go in armour, go with a friend, and go home before your torches run out.",
        }, new String[]{
            "a torch and a prayer",
            "stone picks and leather armour",
            "iron armour from the town, and the caves round about",
            "the deep caves, in diamond",
            "everything under the world",
        }, "What did the bat say to the cave dweller? Nothing. It was hanging around."));
        // [weave] The librarian's own book: the library is kept, and keeping it is a craft too.
        craft("LIBRARY", new Craft("librarian", "Librarian's", "the library", "keeping the library", "the books", "books lent", new String[]{
            "Three days for a loan, and a fine after that: two coins a day, ten at the most. Folk bring a book back sooner when they know it.",
            "Write it down when a book goes out, and cross it off when it comes back. A library is only as good as its catalogue.",
            "A book that is lost is written out again from the catalogue. That is why we keep the words of every one.",
            "Shelve the newest on the lectern, where folk see it first. A book nobody sees is a book nobody reads.",
            "Keep a book and quill in the stores, and the ink to go with it: no ink, no book.",
            "Let the children in of an afternoon. A child who reads becomes a hand who learns quicker.",
            "Keep the old editions of the trades' books. They show how we used to do things, and why we stopped.",
        }, new String[]{
            "a shelf of planks and whatever we can write on",
            "a hall of stone, chiseled shelves, a reading table and two desks",
            "ink and paper enough for every trade's book, and a lectern for the newest",
            "a book for everything the town has done",
            "every book the world has, if we can get it",
        }, "Why did the librarian slip on the floor? She was in the non-friction section."));
        // [fletcher]
        craft("FLETCHER", new Craft("fletcher", "Fletcher's", "the fletching table", "fletching", "the arrows", "things made", new String[]{
            "Sift the gravel on a hard floor and break it clean. One block in ten gives a flint; the rest goes back to be sifted again.",
            "A flint, a stick and a feather make four arrows. Never cut a feather short: the flight is what keeps it true.",
            "Keep the raid's reserve first, thirty-two to a guard, before a single arrow goes to the shop.",
            "Every guard goes up the wall with a full quiver. A guard with an empty one is a guard with a stick.",
            "Pull every arrow out of the butts after practice. An arrow lost in the grass is a feather the coop must grow again.",
            "Practice steadies an eye for good. Send the worst shots to the butts first: they have the most to gain.",
            "Three string to a bow, two to a crossbow. Ask the hunters for the spiders' silk.",
        }, new String[]{
            "flint knapped by hand and goose feathers off the common",
            "the fletching table, and the miners' gravel by the cartload",
            "crossbows of the smith's iron for the best eyes on the wall",
            "the range's targets, and the lamps over them that light when an arrow strikes",
            "spectral arrows, glowing in the dark, of the Nether's glowstone",
        }, "Why did the arrow go to the party? It wanted to get to the point."));
        // [golems]
        craft("GOLEMS", new Craft("golem keeper", "Golem Keeper's", "the golem yard", "golem keeping", "the golems", "things made", new String[]{
            "Four blocks of iron in a T, and the pumpkin last. Clear the corners first: the golem wants room to stand up.",
            "Nine ingots to a block. Never build a golem with the watch's armour still wanting iron.",
            "An ingot mends a golem by a quarter. Mend them before the cracks show, not after.",
            "Carve the pumpkin where it sits on the golem's shoulders, and keep the seeds for the farmers.",
            "Gather a fallen golem's iron the morning after. Every ingot of it goes back into the next one.",
            "Snow golems only where it's cold enough to keep them, and only for the winter. Let them go in the spring.",
        }, new String[]{
            "nothing but our own two fists",
            "nothing yet: iron golems want iron",
            "iron golems at the gates and on the square",
            "golems in good repair, and snow golems on the towers in the winter",
            "golems enough for every gate and every road",
        }, "Why did the iron golem go to the doctor? It had a touch of rust, and nobody would give it an ingot."));
        // [fireworks] The fireworks maker (entity/FireworksMaker).
        craft("FIREWORKS", new Craft("fireworks maker", "Fireworks Maker's", "the powder hut", "fireworks", "the rockets", "rockets made", new String[]{
            "One gunpowder to a star, and one to three to a rocket. The powder for the flight, the star for the sky.",
            "Never more than a day's powder in the hut. The rest lives in the stores, well away.",
            "Keep the cauldron full and the hut cold: no lamp but a glassed lantern, no pipe, no hearth.",
            "Nobody launches in a thunderstorm. The rockets keep; folk don't.",
            "Straight up, from bare ground, and nobody near the rack but the crew. Never at anybody.",
            "A wedding in the couple's colours, a festival in the town's, Remembrance in white. Make for the night ahead.",
        }, new String[]{
            "a pinch of powder and a sheet of paper",
            "the powder hut, and the town's colours in the sky",
            "gold nuggets for stars, feathers for bursts, the elytra rockets for the shop",
            "glowstone for the twinkle, and a diamond's trail on Founding Day",
            "fire charges for the great balls of a victory",
        }, "Why did the rocket go to school? To get a little higher."));
        // [cartographer]
        craft("CARTOGRAPHER", new Craft("cartographer", "Cartographer's", "the map room", "map-making", "the maps", "things made", new String[]{
            "Walk every sheet you draw. A map fills in only round whoever carries it: stand at the middle of each sheet till it's done there.",
            "Lock a finished map under a pane of glass at the table, or it will go on changing on the wall.",
            "Set a banner at each of the town's places and touch every sheet to it: the hall, the gates, the storehouse, the market.",
            "Never sell a map of land the town hasn't seen. A map that lies gets folk drowned.",
            "Price a map by how far and how rare: a coin for every hundred blocks, and more for a monument than a mineshaft.",
            "Keep the old maps. Hung side by side in the museum, they show how the town has grown.",
            "Press paper from the cane three sheets at a time, and keep two dozen by: a wall of nine eats them.",
        }, new String[]{
            "a sheet of paper and our own two feet",
            "the cartography table, and the town's map on the hall's wall",
            "compasses of the town's iron, and explorer maps for the scouts and the cave team",
            "the country's map, carried down every road by the caravans",
            "the ruined portals on the map, and the lands beyond them",
        }, "Why did the cartographer take a pencil to the hall? To draw a crowd."));
        // [emerald] The emerald trader's book: what it has learned of the villagers and their prices.
        craft("EMERALD", new Craft("emerald trader", "Emerald Trader's", "the villagers' villages", "trading", "the emeralds",
            "trades made", new String[]{
            "Sell only what the town can spare. The stores' own needs come first, every time.",
            "Go back to the same villager. Every trade teaches it more, and a master's wares are the best there are.",
            "A villager's price rises when it is pestered: let its stall rest, and its price falls again.",
            "Never trade where pillagers are about, and never cut in while a traveller is at a villager's stall.",
            "Write down who sells Mending. There's no finer book for the town's best tools.",
        }, new String[]{
            "a pack on our backs, and wheat to sell",
            "a donkey from the stable, and a farmer who buys our carrots",
            "a librarian who knows us, and books for the enchanter",
            "masters in three villages, and a bell for the square",
            "every villager for a day's walk, and Mending for every pick",
        }, "Why did the villager raise its prices? The trader kept coming back for more."));
    }

    /** A trade's lore, or a plain one for a trade with none written. */
    public static Craft craft(String key, String noun) {
        Craft c = CRAFTS.get(key);
        if (c != null) return c;
        String n = noun == null || noun.isEmpty() ? "worker" : noun.toLowerCase(Locale.ROOT);
        return new Craft(n, cap(n) + "'s", "the work", n + "'s work", "the work", "things done", new String[]{
            "Do the work well and the rest follows.", "Keep your tools mended and your hands clean.",
            "Ask the old hands. They like to be asked." }, new String[]{ "what we have", "what we have", "what we have",
            "what we have", "what we have" }, "");
    }

    /** "The Farmer's Book of Thornhurst", or as much of it as a book's title holds. */
    public static String title(String key, String noun, String town) {
        Craft c = craft(key, noun);
        return Quill.fit("The " + c.possessive() + " Book of " + town, "The " + c.possessive() + " Book", c.possessive() + " Book");
    }

    // ------------------------------------------------------------------ the writing

    /** The book, written. */
    public static Quill.Book write(Facts f) {
        Craft c = craft(f.key(), "");
        Person m = f.master();
        Voice v = Voice.of(m.name(), m.traits(), m.quirk(), m.hobby(), c.noun());
        Dice d = Dice.of(f.town(), f.key(), m.name(), f.edition());
        List<Block> b = new ArrayList<>();
        String title = title(f.key(), c.noun(), f.town());
        titlePage(b, f, c, d);
        b.add(Block.page());
        preface(b, f, c, v, d);
        List<String> news = new ArrayList<>();
        if (f.edition() > 1) news = whatsNew(b, f, c, v, d);
        numbers(b, f, c, v, d);
        whatWorks(b, f, c, v, d);
        lessons(b, f, c, v, d);
        ages(b, f, c, v, d);
        ownHands(b, f, c, v, d);
        hands(b, f, c, v, d);
        b.add(Block.space());
        b.add(Block.para(v.farewell(d, c.work())));
        b.add(Block.para("- " + m.name() + ", day " + f.day() + "."));
        String blurb = "the " + c.noun() + "'s best practice, " + ordinal(f.edition()) + " edition, kept by " + m.name();
        return new Quill.Book("TRADE", title, m.name(), Quill.pages(b), blurb, "trade:" + f.key(), news);
    }

    /** The title page: what it is, of which town, who keeps it, which edition and when. Thirteen lines at most. */
    private static void titlePage(List<Block> b, Facts f, Craft c, Dice d) {
        b.add(Block.title("§lThe " + c.possessive() + " Book§r"));
        b.add(Block.title("of " + f.town()));
        b.add(Block.space());
        b.add(Block.title(d.pick("the best practice of " + c.place(), f.key().equals("LIBRARY") ? "how the library is kept"   // [weave]
            : "how " + c.work() + " is done here", "all we know of " + c.place())));
        b.add(Block.space());
        b.add(Block.title("kept by " + f.master().name()));
        b.add(Block.title("master " + c.noun()));
        b.add(Block.space());
        b.add(Block.title(cap(ordinal(f.edition())) + " edition"));
        b.add(Block.title("day " + f.day() + (f.date().isEmpty() ? "" : ", " + f.date())));
    }

    /** "A word first": who writes it, how long at the trade, the trade in the town now, and what the book is. */
    private static void preface(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        Person m = f.master();
        b.add(Block.heading(d.pick("A word first", "Before you start", "To whoever picks this up")));
        long days = m.since() >= 0 ? Math.max(0, f.day() - m.since()) : -1;
        String howLong = days < 0 ? "a good while" : days == 0 ? "since this very morning" : number(days) + (days == 1 ? " day" : " days");
        String me = switch (v.tone()) {
            case "grumpy" -> "My name is " + m.name() + ". I've been at " + c.place() + " " + howLong + ", and I'm the best " + c.noun()
                + " " + f.town() + " has, which is not saying as much as it ought to.";
            case "cheerful" -> "Hello! I'm " + m.name() + ", and I've been " + c.work() + " here " + howLong + " - the happiest days of my life, most of them!";
            case "shy" -> "I'm " + m.name() + ". The others asked me to write this down, since I've the most practice at it. I'm not much of a writer, so I'll keep it short.";
            case "curious" -> "I'm " + m.name() + ", " + c.noun() + " of " + f.town() + ", and I've spent " + howLong + " finding out how " + c.place()
                + " " + d.pick("think", "work", "behave") + ".";
            case "hardworking" -> m.name() + ", " + c.noun() + ", level " + m.level() + ". " + cap(howLong) + " at " + c.place() + ", and not one of them wasted.";
            case "easygoing" -> "I'm " + m.name() + ". I've been " + c.work() + " here " + howLong + " now, and I've learned that most things sort themselves out if you let them.";
            case "sociable" -> "I'm " + m.name() + " - you'll have seen me about, most likely - and I've been " + c.work() + " here " + howLong + ".";
            case "generous" -> "I'm " + m.name() + ", and this book is for anybody who wants it. I've been at " + c.place() + " " + howLong + ".";
            default -> "I am " + m.name() + ", and I have been at " + c.place() + " " + howLong + ".";
        };
        String came = switch (m.origin()) {
            case "a founder of the village" -> d.pick(" I came with the founders, when " + f.town() + " was a fire and a chest.",
                " I was here the day " + f.town() + " was founded.");
            case "born here" -> d.pick(" I was born in " + f.town() + ", and I've worked nowhere else.", " I was born here.");
            default -> m.origin().startsWith("came") ? " I " + m.origin() + "." : "";
        };
        b.add(Block.para(me + came));
        // The trade in the town now.
        List<String> others = new ArrayList<>();
        int young = 0;
        for (Hand h : f.hands()) {
            if (h.name().equals(m.name())) continue;
            others.add(h.name());
            if (h.level() < 10) young++;
        }
        String crew;
        if (others.isEmpty()) {
            crew = d.pick("I'm the only " + c.noun() + " in " + f.town() + " just now, so this is as much for whoever comes after me as for anyone.",
                "There's only me at " + c.place() + " for now. This is for whoever comes next.");
        } else {
            crew = "There are " + number(others.size() + 1) + " of us at " + c.place() + " now: " + list(others)
                + (others.size() == 1 ? " and me." : " besides me.");
            if (young > 0) {
                String who = others.size() == 1 ? others.get(0) + " is" : young == others.size() ? "They're all" : cap(number(young))
                    + (young == 1 ? " of them is" : " of them are");
                crew += " " + who + " new to it, which is half the reason for this book.";
            }
        }
        b.add(Block.para(f.key().equals("LIBRARY") ? crew + " The books are my charge" + (v.is("grumpy") ? ", for my sins." : ", and I'm glad of it.")   // [weave]
            : crew + " I'm level " + m.level() + " at it, which makes me the master" + (v.is("grumpy") ? ", for my sins." : ", for now.")));
        if (f.edition() <= 1) {
            b.add(Block.para(d.pick("Nobody has written it down before. ", "This is the first time it's been put in a book. ")
                + "Everything here comes out of the town's own books and my own hands; none of it is guesswork."));
        } else {
            List<String> was = new ArrayList<>();
            for (Long e : f.earlier()) was.add("day " + e);
            String earlier = was.isEmpty() ? "" : " (" + list(was.subList(Math.max(0, was.size() - 3), was.size())) + ")";
            b.add(Block.para("This is the " + ordinal(f.edition()) + " edition. I bring it up to date whenever the town learns something new; "
                + d.pick("the old ones" + earlier + " are on the shelf beside it, if you want to see how we used to do things.",
                    "the earlier ones" + earlier + " are kept on the shelf, so nothing we once knew is lost.")));
        }
    }

    /** What is new in this edition, a line each. Returns the lines (for the chronicle and the gazette). */
    private static List<String> whatsNew(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        List<String> out = new ArrayList<>();
        for (Change ch : f.changes()) {
            String line = change(ch, f, c, d);
            if (!line.isEmpty()) out.add(line);
        }
        if (out.isEmpty()) out.add("The numbers brought up to date, and a few things put more plainly.");
        b.add(Block.heading("New in this edition"));
        b.add(Block.para(switch (v.tone()) {
            case "grumpy" -> "Since the last edition, and pay attention:";
            case "cheerful" -> "Lots has happened since the last edition!";
            case "shy" -> "A few things have changed since the last edition, so I've put them in:";
            case "curious" -> "Here is what we've found out since the last edition:";
            default -> "Since the last edition:";
        }));
        for (String s : out) b.add(Block.item("- " + s));
        return out;
    }

    /** One change since the last edition, in a sentence. */
    static String change(Change ch, Facts f, Craft c, Dice d) {
        return switch (ch.kind()) {
            case "master" -> "I keep this book now. It was " + ch.was() + "'s, and most of what's good in it still is.";
            case "age" -> "We came into " + ch.now() + (ch.day() >= 0 ? " on day " + ch.day() : "") + ". That means " + ageWords(c, ch.now()) + ".";
            case "record" -> "A new record: " + number(ch.n()) + " " + ch.now() + " in a single day, on day " + ch.day() + "."
                + (ch.wasN() > 0 ? " The old best was " + number(ch.wasN()) + "." : "");
            case "lesson" -> stop(cap(ch.now())) + " See \"Learned the hard way\".";
            case "research" -> "The town's research has the work going " + ch.n() + "% quicker" + (ch.wasN() > 0 ? " (it was " + ch.wasN() + "%)." : ".");
            case "hands" -> "There are " + number(ch.n()) + " of us at it now, where there were " + number(ch.wasN()) + ".";
            case "top" -> cap(ch.now()) + " has overtaken " + ch.was() + " as the most we bring in.";
            case "revised" -> "The numbers brought up to date.";
            default -> ch.now();
        };
    }

    /** What an age brings a trade, by the age's name ("the Iron Age"). */
    static String ageWords(Craft c, String age) {
        int i = ageIndex(age);
        return i < 0 ? "new work" : c.ages()[i];
    }

    static int ageIndex(String age) {
        String a = age.toLowerCase(Locale.ROOT);
        if (a.contains("wood")) return 0;
        if (a.contains("stone")) return 1;
        if (a.contains("iron")) return 2;
        if (a.contains("diamond")) return 3;
        if (a.contains("nether")) return 4;
        return -1;
    }

    /** The trade's numbers: this week, the best day, all of it, its worth. */
    private static void numbers(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        if (f.key().equals("LIBRARY")) {                                   // [weave] the shelves, not a harvest
            shelves(b, f, v, d);
            return;
        }
        b.add(Block.heading(d.pick("By the numbers", "The tally", "What we bring in")));
        if (f.days() <= 0 || f.made().isEmpty()) {
            b.add(Block.para("The town's books haven't a full day of our work in them yet. Ask me again in a week and I'll have numbers for you."
                + (f.deeds() > 0 ? " My own tally so far: " + number(f.deeds()) + " " + c.deedWord() + "." : "")));
            return;
        }
        List<String> week = new ArrayList<>();
        boolean figures = false;
        for (Made m : f.made()) if (m.week() >= 100) figures = true;      // a list of numbers reads all in figures or all in words
        for (Made m : f.made()) {
            if (m.week() <= 0) continue;
            week.add((figures ? Long.toString(m.week()) : number(m.week())) + " " + m.what());
            if (week.size() >= 4) break;
        }
        if (!week.isEmpty()) {
            int span = Math.min(7, f.days());
            String per = f.week() >= span ? " - about " + number(Math.round(f.week() / (double) span)) + " a day" : "";
            b.add(Block.para("In the last " + (span == 1 ? "day" : number(span) + " days") + " " + c.place() + " brought in " + list(week) + per + "."
                + trend(f, v, d)));
        }
        if (f.best() > 0) {
            String remark = switch (v.tone()) {
                case "grumpy" -> d.pick(" Beat it and I'll buy you a drink. Nobody has yet.", " That's the mark. Don't come to me with less.");
                case "cheerful" -> d.pick(" What a day that was!", " We sang all the way to the stores.");
                case "hardworking" -> " That's the mark to beat.";
                case "easygoing" -> " We had a long lie-in the day after.";
                case "curious" -> " I've wondered since what made that day different. The weather, I think.";
                default -> "";
            };
            b.add(Block.para("Our best day was day " + f.bestOn() + ", when we brought in " + number(f.best()) + " " + f.bestWhat() + "." + remark));
        }
        if (f.allTime() > 0) {
            String all = (f.days() >= 100 ? "In the hundred days the town's books keep we've brought in " : "Since the town's books began we've brought in ")
                + number(f.allTime()) + " in all";
            Made top = f.made().get(0);
            if (top.total() > 0) all += ", " + number(top.total()) + " of it " + top.what();
            b.add(Block.para(all + "." + (f.worth() > 0 ? " It's worth about " + number(f.worth()) + (f.worth() == 1 ? " coin" : " coins")
                + " a day to the town." : "")));
        }
        for (Made m : f.made()) {
            if (m == f.made().get(0) || m.best() <= 0 || m.bestOn() == f.bestOn()) continue;
            b.add(Block.para("The best day for " + m.what() + " was day " + m.bestOn() + ": " + number(m.best()) + "."));
            break;
        }
        if (f.deeds() > 0) b.add(Block.para("My own tally: " + number(f.deeds()) + " " + c.deedWord() + "."));
    }

    /**
     * [weave] The library's numbers: the books on its shelves and the week's new ones, how often they are read and lent,
     * and the most read of them (the facts' made: books, readings, loans; best and bestWhat: the most read).
     */
    private static void shelves(List<Block> b, Facts f, Voice v, Dice d) {
        b.add(Block.heading(d.pick("The shelves", "What we hold", "By the shelf")));
        long books = f.made().isEmpty() ? 0 : f.made().get(0).total();
        int fresh = f.made().isEmpty() ? 0 : f.made().get(0).week();
        long reads = f.made().size() > 1 ? f.made().get(1).total() : 0, loans = f.made().size() > 2 ? f.made().get(2).total() : 0;
        b.add(Block.para("The shelves hold " + Quill.count(books, "book", "books") + (fresh > 0 ? ", " + number(fresh) + " of them new this week" : "") + "."
            + (reads > 0 ? " They've been read " + Quill.count(reads, "time", "times") + " in the chairs" + (loans > 0 ? ", and lent out " + Quill.count(loans, "time", "times") : "") + "."
                : " Nobody has sat down to one yet" + (v.is("grumpy") ? ", which says a good deal about this town." : "."))));
        if (f.best() > 0 && !f.bestWhat().isEmpty()) {
            b.add(Block.para("The most read is \"" + f.bestWhat() + "\": read " + Quill.count(f.best(), "time", "times") + "."
                + (v.is("cheerful") ? " I'm not surprised!" : v.is("curious") ? " I've wondered why." : "")));
        }
    }

    private static String trend(Facts f, Voice v, Dice d) {
        if (f.lastWeek() <= 0 || f.days() < 10) return "";
        double r = f.week() / (double) f.lastWeek();
        if (r >= 1.15) return " That's up on the week before" + (v.is("cheerful") ? ", and I'm proud of every one of us." : ", when it was " + number(f.lastWeek()) + ".");
        if (r <= 0.85) return " That's down on the week before (" + number(f.lastWeek()) + ")" + (v.is("grumpy") ? ", and I know whose fault it is." : ". We'll make it up.");
        return " About the same as the week before.";
    }

    /** What works: what the town's books show of it, and the craft's own lore, in the master's voice. */
    private static void whatWorks(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        b.add(Block.heading(d.pick("What works", "How we do it here", "The good practice")));
        for (String n : f.notes()) b.add(Block.para(v.say(d, n)));
        List<String> lore = d.some(List.of(c.wisdom()), f.notes().size() >= 3 ? 4 : 5);
        int marked = d.roll(Math.max(1, lore.size())), wondered = d.roll(Math.max(1, lore.size()));
        for (int i = 0; i < lore.size(); i++) {
            String s = i == marked ? v.mark(d) + " " + lore.get(i) : lore.get(i);
            b.add(Block.para(v.say(d, s) + (i == wondered ? v.wonder(d) : "")));
        }
    }

    /** What went wrong, and what it taught. */
    private static void lessons(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        b.add(Block.heading("Learned the hard way"));
        if (f.lessons().isEmpty()) {
            b.add(Block.para("Nobody has come to grief at " + c.place() + " yet, touch wood. Let's keep it that way: "
                + advice(f.key(), "other", "") + (v.is("shy") ? " Please." : "")));
            return;
        }
        int n = 0;
        for (Lesson l : f.lessons()) {
            if (n++ >= 6) break;
            b.add(Block.para(lessonWords(l) + " " + advice(f.key(), l.kind(), l.what())));
        }
        if (v.is("grumpy")) b.add(Block.para("None of it had to happen. Remember that."));
        else if (v.is("cheerful") || v.is("easygoing")) b.add(Block.para("We came through it, and we're wiser for it."));
    }

    /** A lesson in a sentence: "On day 51 we lost Fern, down the mine, in lava." */
    public static String lessonWords(Lesson l) {
        String when = "On day " + l.day() + " ";
        return switch (l.kind()) {
            case "death" -> when + "we lost " + l.who() + ", " + l.what() + ".";
            default -> when + stop(lowerFirst(l.what()));
        };
    }

    private static String lowerFirst(String s) {
        if (s == null || s.isEmpty() || s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s == null ? "" : s;
        // Names keep their capital: only a line that starts with a plain lower-case word in the chronicle's way is lowered.
        return s;
    }

    /** What a thing that went wrong teaches a trade. */
    static String advice(String key, String kind, String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT);
        if (kind.equals("death")) {
            if (w.contains("lava")) return "Carry a bucket of water, never dig straight down, and listen at the rock before you break it.";
            if (w.contains("drown")) return key.equals("FISH") ? "Never fish from a crumbling bank, never at night, and never alone in a storm."
                : "Mind the deep water. Nobody works at the water's edge in the dark.";
            if (w.contains("fall")) return key.equals("MINE") ? "Fence the drops and cut stairs. A shaft is a grave with the lid left off."
                : "Look where you step. Fence the drops, and never work at the edge of a height.";
            if (w.contains("fire")) return "Keep a bucket of water by every fire, and never leave a furnace untended.";
            if (w.contains("explosion")) return "Creepers come quiet. Light everything, and listen for the hiss.";
            if (w.contains("hunger")) return "Eat at the bell. Nobody works well hungry, and some don't work at all.";
            if (w.contains("cold")) return "Wrap up in winter, and come in out of the snow.";
            if (w.contains("thorn")) return "Mind the berry bushes and the cactus. They don't look dangerous, and that's the danger.";
            if (w.contains("trapped")) return "Never dig where gravel or sand hangs over your head.";
            if (w.contains("old age")) return "There's no lesson in it but this: learn from the old hands while you have them.";
            if (w.contains("fighting") || w.contains("raiders") || w.contains("bell ringing"))
                return key.equals("GUARD") ? "Hold the wall and keep together. Nobody fights alone on the watch."
                    : "When the bell rings, the work waits. Get behind the wall.";
            return "Look after one another. It's all any of us can do.";
        }
        return switch (kind) {
            case "fire" -> "Keep a bucket of water by every fire, and the fires away from the thatch.";
            case "storm" -> key.equals("FISH") ? "Come ashore when the sky goes black. No catch is worth a life."
                : "Come in when the sky goes black. The work will still be there when it clears.";
            case "drought" -> "Keep water near the fields, and dig the channels before you need them.";
            case "flood" -> "Build above where the water reaches, and keep the stores high and dry.";
            case "raid" -> key.equals("GUARD") ? "Light the wall, man the posts at dusk, and ring the bell early, not late."
                : "When the bell rings, the work waits. Get behind the wall.";
            case "war" -> "In a war the work goes on, but the bell comes first.";
            case "famine" -> "Put food by in the good times. The lean ones always come.";
            case "collapse" -> "Never dig under the houses, and never where the roof is sand.";
            case "lost" -> "Mark the way, and tell somebody where you're going.";
            default -> switch (key) {
                case "MINE" -> "light every turning and fence every drop.";
                case "FISH" -> "stay off the water in a storm.";
                case "GUARD" -> "keep the wall lit and stay at your post.";
                case "SMELT", "SMITH", "COOK", "BREW" -> "keep a bucket of water by the fire.";
                case "WOOD" -> "keep clear of a falling tree, and of the fire.";
                case "FARM" -> "be in before dark, and keep the field lit.";
                case "CAVE" -> "light every fifteen blocks, and never go past your torches.";     // [weave]
                case "LIBRARY" -> "keep the candles well away from the shelves.";              // [weave]
                default -> "mind how you go.";
            };
        };
    }

    /** The age, what it gives the trade, the next, and the town's research. */
    private static void ages(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        b.add(Block.heading(d.pick("Tools and the times", "What the age allows", "The age we're in")));
        int i = ageIndex(f.age());
        if (i >= 0) {
            b.add(Block.para("We're in " + f.age() + (f.ageSince() >= 0 ? ", and have been since day " + f.ageSince() : "") + ". For " + c.place()
                + " that means " + c.ages()[i] + "."));
            if (i < 4 && !f.nextAge().isEmpty()) {
                b.add(Block.para("When " + f.nextAge() + " comes, " + c.ages()[i + 1] + "." + (v.is("cheerful") ? " I can't wait!"
                    : v.is("grumpy") ? " Not that anybody asks me when." : "")));
            }
        }
        if (f.research() > 0) {
            b.add(Block.para("The town's research makes the work " + f.research() + "% quicker than it was. "
                + d.pick("Thank the council for that.", "That's the town's learning, and every one of us gets the good of it.")));
        }
    }

    /** The master's own: its knacks, its speciality, its quirk, a day it remembers, a tip of its nature, a joke. */
    private static void ownHands(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        Person m = f.master();
        List<String> tips = new ArrayList<>();
        if (!m.knacks().isEmpty()) {
            tips.add("I chose " + list(m.knacks()) + ". " + d.pick("Every knack is a choice; choose the one for the work you love.",
                "Choose your knacks for the work, not for show."));
        }
        if (!m.branch().isEmpty()) tips.add("At level twenty I took up " + m.branch() + ". It suits me.");
        String nature = switch (v.tone()) {
            case "hardworking" -> "Start before the bell. The first hour is worth two of the last.";
            case "easygoing" -> "Take your break. A tired " + c.noun() + " misses things.";
            case "sociable" -> "Work beside somebody when you can. The time goes quicker and so does the work.";
            case "shy" -> "If you like your own company, take a spot at the edge. I do, and I work the better for it.";
            case "generous" -> "Share what you have. A neighbour's good day is a good day for the town.";
            case "grumpy" -> "Don't let anybody hurry you, and don't let anybody tell you you're done when you're not.";
            case "curious" -> "Try things. Half of what's in this book I found out by doing it wrong first.";
            case "cheerful" -> "Sing while you work. It helps, I promise.";
            default -> "Keep at it.";
        };
        tips.add(nature);
        String quirk = v.quirkAside(d);
        if (!quirk.isEmpty()) tips.add(quirk);
        if (!m.memory().isEmpty()) {
            tips.add("I'll not forget day " + m.memoryDay() + ": " + memoryWords(m.memory()) + ". " + d.pick("That's the trade for you.",
                "You'll have days like it.", "It stays with you."));
        }
        if (m.quirk().equals("tells terrible jokes") && !c.joke().isEmpty()) tips.add("And one for the road: " + c.joke());
        b.add(Block.heading(d.pick("From my own hands", "My own tips", "What I've found")));
        for (String t : tips) b.add(Block.para(v.say(d, t)));
    }

    /** A memory as said to the reader: "I lost Fern" stays as it is, lower-cased at its start. */
    static String memoryWords(String memory) {
        String s = memory.trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Who works at it: a line each. */
    private static void hands(List<Block> b, Facts f, Craft c, Voice v, Dice d) {
        if (f.hands().size() <= 1) return;
        b.add(Block.heading("Who's who at " + c.place()));
        for (Hand h : f.hands()) {
            String note = h.note();
            if (note.isEmpty()) {
                note = h.name().equals(f.master().name()) ? "the master, and the one to ask" : h.level() >= 20 ? d.pick("an old hand", "as good as they come")
                    : h.level() >= 10 ? d.pick("a safe pair of hands", "steady", "knows the work") : h.level() >= 5 ? d.pick("coming on well", "getting there")
                    : d.pick("new to it", "still learning", "keen");
            }
            b.add(Block.item("- " + h.name() + ", level " + h.level() + ": " + note));
        }
    }
}
