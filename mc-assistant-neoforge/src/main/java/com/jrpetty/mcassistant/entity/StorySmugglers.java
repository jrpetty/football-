package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [quests] The Smugglers' Cave: goods go out of the stores, and the watch is baffled.
 *
 * <ol>
 * <li><b>Short in the stores.</b> Two of the town's folk are robbing its stores: a courier or a poor hand, and the
 *     one who takes the biggest share, somebody the town looks up to (a councillor, its richest). The goods (a third
 *     of the iron, the gold, the emeralds…) really go: out of the stores into a chest in a camp in a cave out past
 *     the town, made of the stores' own planks, with a couple of the stores' torches burning at the mouth. A little
 *     more goes each night (three nights at most). The storekeeper counts, comes up short, and asks you: quietly,
 *     a friend of the town only.</li>
 * <li><b>Flush with coin.</b> Ask about: the town's gossip knows who has been standing rounds on a hauler's wage.</li>
 * <li><b>A word with the accomplice.</b> It won its money at dice, it says, and has never been near the caves.</li>
 * <li><b>Lights by night.</b> Watch the hillside it named after dark: the torches at the cave mouth.</li>
 * <li><b>The camp</b> below, and in its chest the goods and <b>the ledger</b> (made of the stores' paper): a share
 *     for the accomplice, and the biggest for "R.".</li>
 * <li><b>The choice.</b> Confront the accomplice with the ledger, and: turn it in (the ledger to the elder, a trial
 *     before the council, the goods back in the stores); take a cut and say nothing (out of their purses; it may come
 *     out, a chance in five each day for ten days); or make it name "R.", go to the camp at midnight, and find the
 *     ringleader there counting the goods, to turn in or be bribed by (and that, a chance in three a day).</li>
 * </ol>
 * The trial is the council's (QuestStories.council, or a court of a later day: QuestStories.trials): a ringleader
 * with close friends on the council may be let off. Found guilty, the ring is fined into the treasury, and the town
 * turns against them; a councillor among them loses its seat by it. The chronicle, the gazette and the gossip carry it.
 */
final class StorySmugglers implements QuestStories.Story {

    static final StorySmugglers STORY = new StorySmugglers();

    /** The goods the ring goes for, the dearest first: a third of what the stores hold, at most so many. */
    private static final Object[][] LOOT = {
        { Items.DIAMOND, 2 }, { Items.EMERALD, 6 }, { Items.GOLD_INGOT, 8 }, { Items.IRON_INGOT, 12 }, { Items.LAPIS_LAZULI, 12 },
        { Items.RAW_GOLD, 8 }, { Items.RAW_IRON, 12 }, { Items.COPPER_INGOT, 16 }, { Items.LEATHER, 8 }, { Items.BREAD, 16 },
    };

    @Override
    public String key() {
        return "smugglers";
    }

    // ------------------------------------------------------------------ the theft

    @Override
    @Nullable
    public Quest begin(ServerLevel level, Villages.Village v, long day, Map<String, Object> given, boolean forced) {
        UUID id = v.id();
        if (!forced && Villages.headcount(id) < 10) {
            QuestStories.why(id, "the town is too small for a smuggling ring");
            return null;
        }
        VillageFolkEntity keeper = QuestStories.folk(given, "keeper");
        if (keeper == null) {
            VillageFolkEntity k = Storekeeping.keeper(id);
            if (QuestMaker.free(k)) keeper = k;
        }
        VillageFolkEntity elder = QuestStories.folk(given, "elder");
        if (elder == null) elder = QuestMaker.elder(level, id);
        if (keeper == null || elder == null || keeper == elder) {
            QuestStories.why(id, "no storekeeper to miss the goods, or no elder to try the thieves");
            return null;
        }
        VillageFolkEntity ring = QuestStories.folk(given, "ring"), acc = QuestStories.folk(given, "accomplice"), gossip = QuestStories.folk(given, "gossip");
        List<VillageFolkEntity> used = new ArrayList<>(List.of(keeper, elder));
        if (ring == null) {
            // Somebody the town looks up to: a councillor, the richest of them.
            for (VillageFolkEntity m : Council.members(id)) {
                if (used.contains(m) || !QuestMaker.free(m) || QuestStories.cast(m.getUUID())) continue;
                if (ring == null || m.purse() > ring.purse()) ring = m;
            }
        }
        if (ring == null) {
            QuestStories.why(id, "nobody grand enough to run a smuggling ring");
            return null;
        }
        used.add(ring);
        if (acc == null) {
            // A courier, with the run of the stores; else the poorest hand.
            for (VillageFolkEntity f : Civics.grown(id)) {
                if (used.contains(f) || !QuestMaker.free(f) || QuestStories.cast(f.getUUID())) continue;
                boolean courier = f.stationTask() == AssistantEntity.StationTask.HAUL;
                boolean wasCourier = acc != null && acc.stationTask() == AssistantEntity.StationTask.HAUL;
                if (acc == null || courier && !wasCourier || courier == wasCourier && f.purse() < acc.purse()) acc = f;
            }
        }
        if (acc == null) {
            QuestStories.why(id, "nobody to do the carrying for a ring");
            return null;
        }
        used.add(acc);
        if (gossip == null) {
            for (VillageFolkEntity f : Civics.grown(id)) {
                if (used.contains(f) || QuestStories.cast(f.getUUID())) continue;
                if (gossip == null || f.life().has(Social.Trait.SOCIABLE) && !gossip.life().has(Social.Trait.SOCIABLE)) gossip = f;
            }
        }
        if (gossip == null) {
            QuestStories.why(id, "nobody to have noticed anything");
            return null;
        }
        // The camp: in a cave the cave team found, else a hollow under the ground round about.
        BlockPos camp = QuestStories.pos(given, "camp");
        if (camp == null) camp = camp(level, v);
        if (camp == null || !level.isLoaded(camp)) {
            QuestStories.why(id, "no cave near enough for a camp");
            return null;
        }
        BlockPos mouth = QuestStories.pos(given, "mouth");
        if (mouth == null) mouth = QuestStories.surface(level, camp.getX(), camp.getZ());
        // What the ring would take: there must be something worth it.
        List<Object[]> take = new ArrayList<>();
        double worth = 0;
        for (Object[] l : LOOT) {
            Item it = (Item) l[0];
            int have = Market.stock(level, id, s -> s.is(it));
            int n = Math.min((Integer) l[1], have / 3);
            if (n <= 0) continue;
            take.add(new Object[]{ it, n });
            worth += Prices.each(it) * n;
            if (take.size() >= 3 || worth >= 30) break;
        }
        if (take.isEmpty() || worth < 4) {
            QuestStories.why(id, "nothing in the stores worth stealing");
            return null;
        }
        // The ledger first (the stores' paper): no ledger, no ring.
        ItemStack ledger = QuestItems.make(level, v, acc, McAssistantMod.SMUGGLERS_LEDGER.get());
        if (ledger.isEmpty()) {
            QuestStories.why(id, "no paper in the stores for the ring's ledger: " + QuestItems.shortFor(level, v, acc, McAssistantMod.SMUGGLERS_LEDGER.get()));
            return null;
        }
        // The camp's chest: one out of the stores, else eight of their planks.
        if (!(level.getBlockEntity(camp) instanceof Container)) {
            boolean paid = TownWork.take(level, v, s -> s.is(Items.CHEST), 1) || Crafts.usePlanks(level, v, 8);
            if (!paid || !level.getBlockState(camp).canBeReplaced()) {
                Crafts.store(level, v, ledger);
                QuestStories.why(id, "no chest to be had for the camp");
                return null;
            }
            level.setBlock(camp, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH), 3);
        }
        String rn = ring.displayNameCap(), an = acc.displayNameCap(), kn = keeper.displayNameCap();
        String dir = QuestRun.way(camp.getX() - v.centre().getX(), camp.getZ() - v.centre().getZ());
        // The theft itself: out of the stores and into the camp.
        List<String> gone = new ArrayList<>();
        for (Object[] t : take) {
            Item it = (Item) t[0];
            int n = (Integer) t[1];
            if (!TownWork.take(level, v, s -> s.is(it), n)) continue;
            QuestItems.intoChest(level, camp, new ItemStack(it, n));
            gone.add(Bench.words(it, n));
        }
        if (gone.isEmpty()) {
            Crafts.store(level, v, ledger);
            QuestStories.why(id, "the goods moved before the ring could take them");
            return null;
        }
        Quest q = QuestMaker.newQuest("story.smugglers", Kind.STORY, v, keeper, "The Smugglers' Cave",
            QuestTalk.voice(keeper,
                "The books don't add up. " + capList(gone) + " gone out of the stores since market day, and the watch swears nobody came near. Somebody's robbing us, and I don't know who to trust. Will you look into it? Quietly.",
                capList(gone) + ". Gone. Out of my stores, and the watch saw nothing. Find out who. Quietly.",
                "I've counted three times. " + capList(gone) + "… gone. I don't know who to — would you look into it? Please don't tell anyone I asked.",
                "Somebody's pinched " + capList(gone) + " out of the stores, can you believe it? Fancy a bit of detective work? Hush-hush, mind!"));
        q.days = 5;
        q.warmth = 15;
        q.payer = "treasury";
        q.coins = QuestRewards.affordTreasury(id, 25);
        q.flags.put("standing", Standing.Title.FRIEND.name());
        QuestStories.cast(q, "keeper", keeper);
        QuestStories.cast(q, "elder", elder);
        QuestStories.cast(q, "ring", ring);
        if (Council.members(id).contains(ring)) q.flags.put("seated", "yes");   // a councillor: its seat is at stake
        QuestStories.cast(q, "acc", acc);
        QuestStories.cast(q, "gossip", gossip);
        QuestStories.place(q, "camp", camp);
        QuestStories.place(q, "mouth", mouth);
        q.flags.put("dir", dir);
        q.flags.put("stolen", String.join(", ", gone));
        q.flags.put("rumour", "Something's not right at the stores. " + kn + "'s been counting and counting, and won't say why.");
        if (!QuestRun.offer(level, q)) {
            // Somebody else's business first: the goods back where they came from.
            returnCache(level, v, camp);
            Crafts.store(level, v, ledger);
            QuestStories.why(id, kn + " is busy with somebody else's business");
            return null;
        }
        QuestItems.stamp(ledger, q.id, "The smugglers' ledger", "Iron, gold, the stores' best: so much to " + an.charAt(0) + ".,",
            "and every time the biggest share to \"R.\"");
        QuestItems.intoChest(level, camp, ledger);
        // The accomplice paid its share out of the ringleader's purse: the coin the town will notice.
        int share = Math.min(6, ring.purse());
        if (share > 0 && ring.spend(share)) acc.earn(share);
        q.flags.put("share", Integer.toString(share));
        // The lights at the mouth: a couple of the stores' torches.
        int lit = 0;
        for (BlockPos t : new BlockPos[]{ mouth, mouth.east(2) }) {
            if (!level.getBlockState(t).canBeReplaced() || level.getBlockState(t.below()).isAir()) continue;
            if (!TownWork.take(level, v, s -> s.is(Items.TORCH), 1)) break;
            level.setBlock(t, Blocks.TORCH.defaultBlockState(), 3);
            lit++;
        }
        q.flags.put("lit", Integer.toString(lit));
        Villages.tell(id, day, kn + " found the stores short: " + String.join(", ", gone) + " gone, and nobody seen");
        return q;
    }

    private static String capList(List<String> gone) {
        String s = gone.size() == 1 ? gone.get(0) : String.join(", ", gone.subList(0, gone.size() - 1)) + " and " + gone.get(gone.size() - 1);
        return QuestTalk.capFirst(s);
    }

    /** A camp: in a cave the cave team found, else a hollow under the ground fifty-odd blocks out. */
    @Nullable
    static BlockPos camp(ServerLevel level, Villages.Village v) {
        for (CaveDwellers.Find x : CaveDwellers.report(v.id())) {
            if (x.kind() != CaveDwellers.Kind.CAVE && x.kind() != CaveDwellers.Kind.MINESHAFT && x.kind() != CaveDwellers.Kind.RAVINE) continue;
            int d = QuestMaker.distance(v.centre(), x.at());
            if (d < 45 || d > 150 || !level.isLoaded(x.at())) continue;
            for (BlockPos p : BlockPos.betweenClosed(x.at().offset(-3, -2, -3), x.at().offset(3, 2, 3))) {
                if (standable(level, p)) return p.immutable();
            }
        }
        for (int i = 0; i < 16; i++) {
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            int r = 55 + level.getRandom().nextInt(50);
            int x = v.centre().getX() + (int) Math.round(Math.cos(a) * r), z = v.centre().getZ() + (int) Math.round(Math.sin(a) * r);
            if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
            int top = QuestStories.surface(level, x, z).getY();
            for (int y = top - 8; y > Math.max(level.getMinBuildHeight() + 6, top - 48); y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (standable(level, p) && !level.getBlockState(p.above(2)).isAir() && !level.getBlockState(p.above(3)).isAir()
                        && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p) == 0) return p;
            }
        }
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above()), floor = level.getBlockState(p.below());
        return feet.isAir() && head.isAir() && floor.isFaceSturdy(level, p.below(), Direction.UP) && level.getFluidState(p).isEmpty()
            && level.getFluidState(p.below()).isEmpty();
    }

    /** Whatever is in the camp's chest back into the stores (the ledger apart). */
    static int returnCache(ServerLevel level, Villages.Village v, BlockPos camp) {
        Container c = QuestItems.chest(level, camp);
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || s.is(McAssistantMod.SMUGGLERS_LEDGER.get())) continue;
            n += s.getCount();
            Crafts.store(level, v, s.copy());
            c.setItem(i, ItemStack.EMPTY);
        }
        c.setChanged();
        return n;
    }

    // ------------------------------------------------------------------ the investigation

    @Override
    public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
        String gn = QuestStories.name(q, "gossip");
        q.steps.add(new Step(StepType.TALK, "gossip", "Ask " + gn + " who's been flush with coin lately").chapter("Flush with coin")
            .who(QuestStories.roleId(q, "gossip"), gn));
        return QuestTalk.voice(giver, "Thank you. Start with " + gn + " — " + gn + " hears everything. And not a word to anybody else.",
            "Good. Try " + gn + ". Nothing gets past " + gn + ". Keep it quiet.", "Thank you… " + gn + " might know something. Please be careful.",
            "Ooh, a mystery! Try " + gn + " — biggest gossip in town!");
    }

    @Override
    public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
        String an = QuestStories.name(q, "acc"), rn = QuestStories.name(q, "ring"), en = QuestStories.name(q, "elder"), kn = QuestStories.name(q, "keeper");
        String dir = q.flag("dir");
        BlockPos camp = QuestStories.place(q, "camp"), mouth = QuestStories.place(q, "mouth");
        switch (s.key) {
            case "gossip" -> {
                s.note = an + " has been standing rounds at the tavern, and out " + dir + " after dark.";
                q.steps.add(new Step(StepType.TALK, "acc", "Have a word with " + an).chapter("A word with " + an).who(QuestStories.roleId(q, "acc"), an));
                return an + "? Funny you should ask. Stood the whole tavern a round last night — on a hauler's wage! And I've seen " + an
                    + " heading out " + dir + " after dark, more than once. I'm only saying.";
            }
            case "acc" -> {
                s.note = an + " says it won at dice, and has never been near the caves. Nobody said anything about caves.";
                boolean lit = QuestRewards.num(q.flag("lit")) > 0;
                q.steps.add(new Step(StepType.GO, "lights", "Watch the hillside " + dir + " of the town after dark: " + (lit ? "odd lights have been seen there" : "something's out there"))
                    .chapter("Lights by night").at(mouth, 10));
                q.steps.get(q.steps.size() - 1).night = true;
                return "Me? I won it at dice, didn't I. Fair and square. And I've never been anywhere near the caves, whatever anybody says!";
            }
            case "lights" -> {
                s.note = QuestRewards.num(q.flag("lit")) > 0 ? "Torches burning at a hole in the hillside. Somebody's down there." : "Tracks in and out of a hole in the hillside.";
                q.steps.add(new Step(StepType.GO, "camp", "Go down and find what's below").chapter("The camp").at(camp, 4));
                return "";
            }
            case "camp" -> {
                Container c = QuestItems.chest(level, camp);
                int goods = 0;
                if (c != null) for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) goods += c.getItem(i).getCount();
                s.note = "A camp: a cold fire, and a chest with " + goods + " of the town's goods in it.";
                q.steps.add(new Step(StepType.GET, "ledger", "Take the ledger out of the smugglers' chest").chapter("The ledger").at(camp, 4).item("token:smugglers_ledger", 1));
                return "";
            }
            case "ledger" -> {
                s.note = "So much to " + an + ", and every time the biggest share to \"R.\".";
                q.steps.add(new Step(StepType.CHOOSE, "confront", "Confront " + an + " with the ledger").chapter("The choice").who(QuestStories.roleId(q, "acc"), an)
                    .option("turn", "Turn " + an + " in").option("cut", "Take a cut, say nothing").option("name", "Make " + an + " name \"R.\""));
                return "";
            }
            case "night" -> {
                s.note = rn + " is there, counting the goods by lantern light.";
                q.steps.add(new Step(StepType.CHOOSE, "ring", "Confront " + rn).chapter(rn).who(QuestStories.roleId(q, "ring"), rn)
                    .option("turn_ring", "Turn " + rn + " in").option("bribe", "Take " + rn + "'s money"));
                return "";
            }
            case "trial" -> {
                return trial(level, q, s, p);
            }
            case "report" -> {
                return reportLine(q);
            }
            default -> {
                return "";
            }
        }
    }

    @Override
    @Nullable
    public String waiting(ServerLevel level, Quest q, Step s, VillageFolkEntity f, Player p) {
        if (s.key.equals("confront")) return "Where did you get that? Look — look, it's not what you think. What do you want?";
        if (s.key.equals("ring")) return "You. Here. Well. I suppose we had better talk about what happens now, hadn't we?";
        return null;
    }

    @Override
    public String chose(ServerLevel level, Quest q, Step s, String option, VillageFolkEntity f, Player p) {
        String an = QuestStories.name(q, "acc"), rn = QuestStories.name(q, "ring"), en = QuestStories.name(q, "elder"), kn = QuestStories.name(q, "keeper");
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        switch (option) {
            case "turn", "turn_ring" -> {
                q.flags.put("accused", option.equals("turn") ? "acc" : "acc,ring");
                q.flags.put("deliver.trial", "gone");
                q.steps.add(new Step(StepType.DELIVER, "trial", "Take the ledger to " + en + ", for the council").chapter("The trial")
                    .who(QuestStories.roleId(q, "elder"), en).item("token:smugglers_ledger", 1));
                return option.equals("turn") ? "You wouldn't. You — please. " + an + "'s not a bad sort, it was " + "R.'s idea. … Fine. Do what you must."
                    : "Turn me in? Me? Who do you think they'll believe? … Very well. Let the council decide.";
            }
            case "cut", "bribe" -> {
                // Paid to keep quiet: out of their purses, and the cache stays theirs.
                VillageFolkEntity acc = QuestStories.role(level, q, "acc"), ring = QuestStories.role(level, q, "ring");
                int most = option.equals("cut") ? 12 : 25, paid = 0;
                for (VillageFolkEntity payer : new VillageFolkEntity[]{ ring, acc }) {
                    if (payer == null || paid >= most) continue;
                    int k = Math.min(most - paid, payer.purse());
                    if (k > 0 && payer.spend(k)) paid += k;
                }
                if (paid > 0) Dealings.giveCoins(p, paid);
                q.flags.put("cut", Integer.toString(paid));
                q.flags.put("odds", option.equals("cut") ? "5" : "3");
                q.flags.put("secret", "took " + paid + " coins off the smugglers robbing our stores, and said nothing");
                QuestBook.secret(q.id, day + 10);
                for (VillageFolkEntity g : new VillageFolkEntity[]{ ring, acc }) {
                    if (g == null) continue;
                    g.persona().feelFor(p.getUUID(), name, 10);
                    g.persona().remember(day, name + " found us out, and took our money to keep quiet", 4);
                }
                // The ledger burnt: it is gone.
                QuestItems.take(p, QuestItems.matcher("token:smugglers_ledger", q.id), 1);
                q.coins = 0;
                q.flags.put("reward", "");
                q.steps.add(new Step(StepType.TALK, "report", "Tell " + kn + " what you found (or didn't)").chapter("Nothing to report").who(QuestStories.roleId(q, "keeper"), kn));
                return option.equals("cut") ? "Here — " + paid + " coins. And that's the end of it, yes? Burn that ledger."
                    : paid + " coins, and we never met. A pleasure doing business.";
            }
            case "name" -> {
                q.steps.add(new Step(StepType.GO, "night", "Go to the camp after dark, when \"R.\" comes").chapter("Midnight at the camp")
                    .at(QuestStories.place(q, "camp"), 5));
                q.steps.get(q.steps.size() - 1).night = true;
                q.flags.put("named", "1");
                return "…It's " + rn + ". " + rn + "! Everybody's so proud of " + rn + ". " + rn + " comes to the camp after dark to count it. Don't tell anyone it was me who said.";
            }
            default -> {
                return "";
            }
        }
    }

    /** The council hears it: the verdict, the cache home, and the next step (the storekeeper told). */
    private String trial(ServerLevel level, Quest q, Step s, Player p) {
        Villages.Village v = Villages.get(q.village);
        String kn = QuestStories.name(q, "keeper"), an = QuestStories.name(q, "acc"), rn = QuestStories.name(q, "ring");
        List<VillageFolkEntity> accused = new ArrayList<>();
        for (String role : q.flag("accused").split(",")) {
            VillageFolkEntity a = QuestStories.role(level, q, role);
            if (a != null) accused.add(a);
        }
        QuestStories.Verdict verdict = v == null || accused.isEmpty() ? new QuestStories.Verdict(false, 0, "There was nobody to try.")
            : QuestStories.hear(level, v, accused, "robbing the stores", true, p);
        int back = v == null ? 0 : returnCache(level, v, QuestStories.place(q, "camp"));
        q.flags.put("verdict", verdict.guilty() ? "guilty" : "off");
        q.flags.put("returned", Integer.toString(back));
        s.note = verdict.words() + (back > 0 ? " " + back + " of the goods went back into the stores." : "");
        q.steps.add(new Step(StepType.TALK, "report", "Tell " + kn + " how it came out").chapter("The stores made good").who(QuestStories.roleId(q, "keeper"), kn));
        return "A ledger? Let me see… " + (q.flag("accused").contains("ring") ? rn + "? I'd never have believed it. " : an + "! ")
            + "The council will hear it this evening. " + verdict.words();
    }

    private static String reportLine(Quest q) {
        boolean cut = !q.flag("cut").isEmpty();
        if (cut) return "Nothing? You found nothing at all? … Well. Thank you for looking.";
        boolean guilty = "guilty".equals(q.flag("verdict"));
        return guilty ? "Guilty! And the goods back where they belong. I could kiss you."
            : "Let off? After all that? … At least the goods are back. Thank you. I'll not forget it.";
    }

    // ------------------------------------------------------------------ the nights

    /** Each night while it runs (three at most), and the ledger not yet found: a little more goes. */
    @Override
    public void townTick(ServerLevel level, Villages.Village v, Quest q) {
        long day = QuestRun.day(level);
        if (!QuestRun.night(level) || Long.toString(day).equals(q.flag("night.day"))) return;
        Step ledger = q.step("ledger");
        if (ledger != null && ledger.done || QuestRewards.num(q.flag("nights")) >= 3) return;
        BlockPos camp = QuestStories.place(q, "camp");
        if (QuestItems.chest(level, camp) == null) return;
        q.flags.put("night.day", Long.toString(day));
        for (Object[] l : LOOT) {
            Item it = (Item) l[0];
            if (Market.stock(level, v.id(), s -> s.is(it)) < 6) continue;
            int n = it == Items.DIAMOND || it == Items.EMERALD ? 1 : 2;
            if (!TownWork.take(level, v, s -> s.is(it), n)) continue;
            QuestItems.intoChest(level, camp, new ItemStack(it, n));
            q.flags.put("nights", Integer.toString(QuestRewards.num(q.flag("nights")) + 1));
            q.flags.put("stolen", q.flag("stolen") + ", " + Bench.words(it, n));
            QuestBook.changed();
            break;
        }
    }

    /** The ringleader, the night it is named: to the camp, to count the goods. */
    @Override
    public boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
        if (!f.getUUID().equals(QuestStories.roleId(q, "ring")) || q.flag("named").isEmpty()) return false;
        Step s = q.current();
        if (s == null || !(s.key.equals("night") || s.key.equals("ring")) || !QuestRun.night(level)) return false;
        BlockPos camp = QuestStories.place(q, "camp");
        if (f.blockPosition().distSqr(camp) > 4 * 4) {
            if (!QuestStories.watched(level, f.blockPosition(), 24) && !QuestStories.watched(level, camp, 16)) {
                f.moveTo(camp.getX() + 0.5, camp.getY(), camp.getZ() + 1.5, f.getYRot(), 0.0F);
                f.getNavigation().stop();
            } else {
                Civics.goTo(f, camp, 2.0, 0.9);
            }
            return true;
        }
        f.getNavigation().stop();
        return true;
    }

    // ------------------------------------------------------------------ the end

    @Override
    public String ending(ServerLevel level, Quest q, Player p) {
        String an = QuestStories.name(q, "acc"), rn = QuestStories.name(q, "ring"), kn = QuestStories.name(q, "keeper");
        String name = p.getName().getString();
        String stolen = q.flag("stolen");
        if (!q.flag("cut").isEmpty()) {
            boolean ring = !q.flag("named").isEmpty();
            q.outcome = "took " + q.flag("cut") + " coins to say nothing" + (ring ? " (from " + rn + ")" : "");
            q.flags.put("chronicle", kn + " gave up looking for whoever robbed the stores");
            q.flags.put("gossip", "They never found out who took " + stolen.split(",")[0] + " out of the stores. Makes you wonder.");
            return "You told " + kn + " you had found nothing, and " + kn + " believed you. " + (ring ? rn : an) + "'s coin is in your pocket, and the town's "
                + stolen + " are in a cave " + q.flag("dir") + " of it. It may never come out. It may.";
        }
        boolean guilty = "guilty".equals(q.flag("verdict"));
        boolean ring = q.flag("accused").contains("ring");
        // Its seat went with the town's good opinion of it (the council is the five the town thinks most of): only said
        // when it really has gone.
        VillageFolkEntity rf = QuestStories.role(level, q, "ring");
        boolean unseated = ring && guilty && rf != null && !q.flag("seated").isEmpty() && !Council.members(q.village).contains(rf);
        if (guilty && ring) QuestBook.title(p.getUUID(), q.village, "Thief-taker");
        if (ring) q.coins += 10;
        q.outcome = (ring ? rn + " and " + an : an) + (guilty ? " found guilty" : " let off by the council") + "; " + q.flag("returned") + " goods back in the stores";
        q.flags.put("chronicle", name + " found the smugglers' camp in the caves " + q.flag("dir") + " of the town and brought " + (ring ? rn + " and " + an : an)
            + " to book");
        q.flags.put("gossip", ring ? (guilty ? rn + "! Behind the thefts all along! And it was {who} found it out." : "They say " + rn + " was behind the thefts, and the council let them off! {who} found it all out.")
            : "{who} caught " + an + " smuggling the town's goods out to a cave! " + (guilty ? "Fined, and serve them right." : "And the council let " + an + " off!"));
        q.flags.put("memory", "found out who was robbing my stores");
        return (ring ? "The ledger, the camp, and " + rn + " caught red-handed at midnight: the whole ring. " : "The ledger and " + an + "'s face told the council enough. ")
            + (guilty ? "The council found them guilty and fined them into the treasury, and the town will not soon trust " + (ring ? rn : an) + " again"
                + (unseated ? " — " + rn + "'s seat on the council is somebody else's now." : ".")
                : "The council let them off — friends in high places — but the town knows what it knows.")
            + " The goods that were left went back into the stores, and " + kn + "'s books add up again." + (ring ? "" : " As for \"R.\", whoever that was, the town never knew.");
    }

    @Override
    public void ended(ServerLevel level, Quest q) {
        if (q.state == State.DONE) return;
        // Left unsolved: the ring keeps what it took, and lies low.
        Villages.tell(q.village, QuestRun.day(level), "the thefts from the stores were never solved");
    }
}
