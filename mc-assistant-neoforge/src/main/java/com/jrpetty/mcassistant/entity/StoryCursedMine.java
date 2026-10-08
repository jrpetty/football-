package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [quests] The Cursed Mine: the miners will not go down.
 *
 * <ol>
 * <li><b>Noises below.</b> Something real is wrong under the town's mine (TownMine): a spawner the cave team found
 *     near it, or one in the rock under it, or monsters gathered down there in the dark. The miners down tools: in
 *     working hours they stand at the mine head and will not go down (their day waits on it), and their foreman
 *     asks you to find out what is there. It is a curse, they say; somebody came up white as a sheet.</li>
 * <li><b>The old miner's tale.</b> The old miner remembers the old workings, and hands you its journal (a book of
 *     the stores' paper, with the place in it, and which way and how deep).</li>
 * <li><b>Alone, or with the cave team.</b> A town with cave dwellers: ask one down with you, and it comes at your
 *     heels, armed and armoured by the town as it is, and shares the credit.</li>
 * <li><b>The deep level</b>, and the thing itself: break the spawner, or kill what is there.</li>
 * <li><b>Light it.</b> Torches about the place, so nothing breeds there again: the foreman gives you sixteen out of
 *     the stores if they hold them.</li>
 * <li><b>The mine reopens.</b> Tell the foreman; choose how the town marks it: a feast for the miners tonight (out of
 *     the treasury), or a plaque at the mine head.</li>
 * </ol>
 * The miners go back down, the lighter for it; the chronicle names you (and the cave dweller); you are the town's
 * Curse-lifter. Left too long, the miners go back down grumbling, and the town remembers who said they'd help.
 */
final class StoryCursedMine implements QuestStories.Story {

    static final StoryCursedMine STORY = new StoryCursedMine();

    @Override
    public String key() {
        return "mine";
    }

    // ------------------------------------------------------------------ the trouble

    @Override
    @Nullable
    public Quest begin(ServerLevel level, Villages.Village v, long day, Map<String, Object> given, boolean forced) {
        UUID id = v.id();
        BlockPos site = QuestStories.pos(given, "site");
        if (site == null) site = TownMine.siteOf(id);
        if (site == null) {
            QuestStories.why(id, "the town has no mine");
            return null;
        }
        List<VillageFolkEntity> miners = new ArrayList<>();
        for (VillageFolkEntity f : Civics.grown(id)) if (f.stationTask() == AssistantEntity.StationTask.MINE) miners.add(f);
        VillageFolkEntity foreman = QuestStories.folk(given, "foreman"), old = QuestStories.folk(given, "old");
        if (foreman == null) {
            for (VillageFolkEntity m : miners) if (QuestMaker.free(m) && (foreman == null || m.veteranLevel() > foreman.veteranLevel())) foreman = m;
        }
        if (foreman == null) {
            QuestStories.why(id, "nobody works the mine");
            return null;
        }
        if (old == null) {
            for (VillageFolkEntity m : miners) if (m != foreman && !QuestStories.cast(m.getUUID()) && (old == null || m.veteranLevel() > old.veteranLevel())) old = m;
        }
        if (old == null) {
            VillageFolkEntity e = QuestMaker.elder(level, id);
            if (e != null && e != foreman) old = e;
        }
        if (old == null) {
            QuestStories.why(id, "nobody who remembers the old workings");
            return null;
        }
        // What is really down there.
        BlockPos place = QuestStories.pos(given, "place");
        String kind = QuestStories.text(given, "kind");
        if (place == null) {
            for (CaveDwellers.Find x : CaveDwellers.report(id)) {
                if ((x.kind() == CaveDwellers.Kind.SPAWNER || x.kind() == CaveDwellers.Kind.DUNGEON)
                        && Math.abs(x.at().getX() - site.getX()) <= 48 && Math.abs(x.at().getZ() - site.getZ()) <= 48
                        && (!level.isLoaded(x.at()) || level.getBlockState(x.at()).is(Blocks.SPAWNER))) {
                    place = x.at();
                    kind = "spawner";
                    break;
                }
            }
        }
        if (place == null && level.isLoaded(site)) {
            place = spawnerUnder(level, site);
            if (place != null) kind = "spawner";
        }
        if (place == null && level.isLoaded(site)) {
            int top = site.getY() - 6;
            List<Monster> below = level.getEntitiesOfClass(Monster.class, new AABB(site).inflate(24, 40, 24),
                m -> m.isAlive() && m.getY() < top);
            if (below.size() >= 2) {
                place = below.get(0).blockPosition();
                kind = "monsters";
            }
        }
        if (place == null) {
            QuestStories.why(id, "nothing is wrong under the mine");
            return null;
        }
        if (kind.isEmpty()) kind = "spawner";
        if (QuestStories.claimed(place)) {
            QuestStories.why(id, "that place is another story's");
            return null;
        }
        String fn = foreman.displayNameCap(), on = old.displayNameCap();
        int depth = Math.max(0, site.getY() - place.getY());
        String dir = QuestRun.way(place.getX() - site.getX(), place.getZ() - site.getZ());
        Ledger.Grave lost = null;
        for (Ledger.Grave g : Ledger.graves(id)) {
            if (day - g.died() <= 20 && g.cause().toLowerCase(java.util.Locale.ROOT).contains("mine")) lost = g;
        }
        String why = lost != null ? "We lost " + lost.name() + " down there" + (day - lost.died() <= 1 ? " yesterday" : "") + ", and now there's noises from the deep level"
            : "There's noises from the deep level, the lamps blow out, and " + on + " came up white as a sheet";
        Quest q = QuestMaker.newQuest("story.mine", Kind.STORY, v, foreman, "The Cursed Mine",
            QuestTalk.voice(foreman,
                "Nobody'll go down the mine. " + why + ". They're saying it's cursed. I don't hold with curses — but I'll not send anybody down till I know what's there. Will you find out?",
                "My miners won't go down. " + why + ". Curse, they say. Rubbish. Go and find out what it really is.",
                "N-nobody will go down. " + why + ". They say there's a curse… would you look? I can't ask any of them.",
                "The whole crew's downed tools! " + why + " — they say it's CURSED. Will you go and see? I'd go myself but they'd never let me hear the end of it!"));
        q.days = 4;
        q.warmth = 20;
        q.payer = "treasury";
        q.coins = QuestRewards.affordTreasury(id, 30);
        q.flags.put("standing", Standing.Title.VISITOR.name());
        QuestStories.cast(q, "foreman", foreman);
        QuestStories.cast(q, "old", old);
        int i = 0;
        for (VillageFolkEntity m : miners) if (m != foreman && m != old && i < 8) QuestStories.cast(q, "miner" + i++, m);
        QuestStories.place(q, "site", site);
        QuestStories.place(q, "place", place);
        q.flags.put("kind", kind);
        q.flags.put("depth", Integer.toString(depth));
        q.flags.put("dir", dir);
        if (lost != null) q.flags.put("lost", lost.name());
        q.flags.put("rumour", "The miners won't go down. There's a curse on the mine, they say. " + fn + " says it's nonsense, but " + fn + " won't go down either.");
        if (!QuestRun.offer(level, q)) {
            QuestStories.why(id, fn + " has somebody else's business on");
            return null;
        }
        Villages.tell(id, day, "the miners downed tools and would not go down the mine: a curse, they said");
        return q;
    }

    /** A spawner in the rock under the mine: looked for in a square round the shaft, down to sixty blocks. */
    @Nullable
    static BlockPos spawnerUnder(ServerLevel level, BlockPos site) {
        int bottom = Math.max(level.getMinBuildHeight() + 1, site.getY() - 60);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                if (!level.isLoaded(p.set(site.getX() + x, site.getY(), site.getZ() + z))) continue;
                for (int y = site.getY() - 4; y >= bottom; y--) {
                    if (level.getBlockState(p.set(site.getX() + x, y, site.getZ() + z)).is(Blocks.SPAWNER)) return p.immutable();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the investigation

    @Override
    public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
        String on = QuestStories.name(q, "old");
        q.steps.add(new Step(StepType.TALK, "old", "Hear " + on + "'s tale of the old workings").chapter("The old miner's tale")
            .who(QuestStories.roleId(q, "old"), on));
        // Torches for the dark, out of the stores.
        Villages.Village v = Villages.get(q.village);
        int have = v == null ? 0 : Math.min(16, Market.stock(level, q.village, s -> s.is(Items.TORCH)));
        if (have > 0 && TownWork.take(level, v, s -> s.is(Items.TORCH), have)) {
            QuestItems.give(p, new ItemStack(Items.TORCH, have));
            q.flags.put("torches", Integer.toString(have));
        }
        return QuestTalk.voice(giver, "Good. Talk to " + on + " first — " + on + " was down that mine before any of us." + (have > 0 ? " And take these torches: " + have + " of them, from the stores." : ""),
            "Talk to " + on + ". " + on + " knows the old workings." + (have > 0 ? " Here. " + have + " torches." : ""),
            "Thank you… " + on + " might know something. " + on + " remembers the old days." + (have > 0 ? " Oh — and take these torches." : ""),
            "Brilliant! Ask " + on + " — " + on + "'s full of old mine stories!" + (have > 0 ? " And have some torches — " + have + " of them!" : ""));
    }

    @Override
    public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
        String on = QuestStories.name(q, "old"), fn = QuestStories.name(q, "foreman");
        BlockPos place = QuestStories.place(q, "place"), site = QuestStories.place(q, "site");
        String depth = q.flag("depth"), dir = q.flag("dir");
        boolean spawner = "spawner".equals(q.flag("kind"));
        switch (s.key) {
            case "old" -> {
                // The journal: a book of the stores' makings, with the place in it.
                Villages.Village v = Villages.get(q.village);
                ItemStack journal = v == null ? ItemStack.EMPTY : QuestItems.make(level, v, f, McAssistantMod.MINERS_JOURNAL.get());
                String where = depth + " blocks down, " + dir + " of the shaft, at " + place.getX() + " " + place.getY() + " " + place.getZ();
                if (!journal.isEmpty()) {
                    QuestItems.stamp(journal, q.id, on + "'s journal", "\"The deep level, " + depth + " blocks down, " + dir + " of the shaft:",
                        spawner ? "an old dungeon, walled up in my father's day.\"" : "the dark goes on further than it ought.\"",
                        "(" + place.getX() + " " + place.getY() + " " + place.getZ() + ")");
                    QuestItems.give(p, journal);
                }
                s.note = on + " told of the old workings: " + where + "." + (journal.isEmpty() ? "" : " You have " + on + "'s journal.");
                VillageFolkEntity dweller = null;
                for (VillageFolkEntity d : CaveDwellers.dwellers(q.village)) if (!d.isBaby() && !QuestStories.cast(d.getUUID())) { dweller = d; break; }
                String tale = spawner ? "There was an old dungeon down there, " + where + ". My father's crew walled it up. Something's broken through." :
                    "The deep level, " + where + ". The dark goes further than it ought, and things come up out of it.";
                if (dweller != null) {
                    QuestStories.cast(q, "dweller", dweller);
                    q.steps.add(new Step(StepType.CHOOSE, "ally", "Go down alone, or ask the cave team?").chapter("Alone, or with the cave team")
                        .who(QuestStories.roleId(q, "old"), on).option("ally", "Ask " + dweller.displayNameCap() + " along").option("alone", "Go alone"));
                    return tale + (journal.isEmpty() ? "" : " Take my journal; it's all in there.") + " You'll not want to go alone. " + dweller.displayNameCap()
                        + " of the cave team knows the dark better than any of us.";
                }
                deep(q);
                return tale + (journal.isEmpty() ? "" : " Take my journal; it's all in there.") + " Mind how you go.";
            }
            case "fetch" -> {
                q.flags.put("ally", "1");
                s.note = s.whoName + " is coming down with you.";
                deep(q);
                return "The deep level? I've been wanting a look at that. Lead on — I'll be right behind you.";
            }
            case "deep" -> {
                s.note = spawner ? "A spawner, broken through the old wall, and the dark full of what it breeds." : "Monsters, gathered in the dark.";
                if (spawner) {
                    q.steps.add(new Step(StepType.SEAL, "seal", "Break the spawner").chapter("The thing below").at(place, 6));
                } else {
                    Step kill = new Step(StepType.KILL, "seal", "Kill what is down there: four of them").chapter("The thing below").at(place, 20).item("", 4);
                    kill.mob = "monsters";
                    q.steps.add(kill);
                }
                return "";
            }
            case "seal" -> {
                s.note = spawner ? "Broken." : "Cleared.";
                q.steps.add(new Step(StepType.LIGHT, "light", "Light the deep level: six torches about it").chapter("Light").at(place, 8).item("torch", 6));
                return "";
            }
            case "light" -> {
                s.note = "Lit. Nothing will breed there now.";
                q.steps.add(new Step(StepType.CHOOSE, "mark", "Tell " + fn + " the mine is safe").chapter("The mine reopens")
                    .who(QuestStories.roleId(q, "foreman"), fn).option("feast", "A feast for the miners").option("plaque", "A plaque at the mine head"));
                return "";
            }
            default -> {
                return "";
            }
        }
    }

    private static void deep(Quest q) {
        BlockPos place = QuestStories.place(q, "place");
        q.steps.add(new Step(StepType.GO, "deep", "Go down to the deep level, " + q.flag("depth") + " blocks down, " + q.flag("dir") + " of the shaft")
            .chapter("The deep level").at(place, 6));
    }

    @Override
    @Nullable
    public String waiting(ServerLevel level, Quest q, Step s, VillageFolkEntity f, Player p) {
        if (s.key.equals("mark")) return "Safe? Truly? Then it's to be marked. How shall we mark it?";
        if (s.key.equals("ally")) return "Will you take " + s.options.get(0)[1].replace("Ask ", "").replace(" along", "") + " with you, or go alone?";
        return null;
    }

    @Override
    public String chose(ServerLevel level, Quest q, Step s, String option, VillageFolkEntity f, Player p) {
        String fn = QuestStories.name(q, "foreman"), dn = QuestStories.name(q, "dweller");
        long day = QuestRun.day(level);
        Villages.Village v = Villages.get(q.village);
        switch (option) {
            case "ally" -> {
                q.steps.add(new Step(StepType.TALK, "fetch", "Ask " + dn + " to come down with you").chapter("Alone, or with the cave team")
                    .who(QuestStories.roleId(q, "dweller"), dn));
                return "Good. Two are safer than one, down there.";
            }
            case "alone" -> {
                q.flags.remove("cast.dweller");
                deep(q);
                return "Alone? You're braver than me. Mind how you go.";
            }
            case "feast" -> {
                int cost = 10 + Villages.headcount(q.village);
                int paid = QuestRewards.fromTreasury(level, q.village, cost);
                if (paid >= cost / 2 && v != null) {
                    Gatherings.sponsor(q.village, "the town, for the mine", day);
                    q.flags.put("marked", "a feast for the miners");
                    return "A feast! Tonight, on the town. The miners will drink your health.";
                }
                if (paid > 0) Ledger.addCoins(q.village, paid);
                q.flags.put("marked", "a toast in the tavern (the treasury could not run to a feast)");
                return "The treasury can't run to a feast, more's the pity. We'll drink your health in the tavern instead.";
            }
            case "plaque" -> {
                String[] lines = { "The mine made", "safe, day " + (day + 1), "by " + p.getName().getString(),
                    q.flag("ally").isEmpty() ? (q.flag("lost").isEmpty() ? "God keep it" : "for " + q.flag("lost")) : "and " + dn };
                Plaques.memorial(q.village, QuestStories.place(q, "site"), lines, day);
                q.flags.put("marked", "a plaque at the mine head");
                return "A plaque, at the mine head. Every miner will see it, going down and coming up.";
            }
            default -> {
                return "";
            }
        }
    }

    // ------------------------------------------------------------------ the curse

    /**
     * [VillageFolkEntity.aiStep] The miners, while the curse is on: in working hours, at the mine head, and not a
     * step further down. The cave dweller asked along keeps at your heels.
     */
    @Override
    public boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
        if (f.getUUID().equals(QuestStories.roleId(q, "dweller"))) {
            if (q.flag("ally").isEmpty() || q.state != State.ACTIVE) return false;
            Step s = q.current();
            return s != null && (s.key.equals("deep") || s.key.equals("seal") || s.key.equals("light")) && QuestStories.follow(f, level, q.player);
        }
        if (f.stationTask() != AssistantEntity.StationTask.MINE || f.isSleeping()) return false;
        long t = level.getDayTime() % 24000L;
        if (t < 1000L || t > 12000L || f.offWorkNow()) return false;
        BlockPos site = QuestStories.place(q, "site");
        if (f.blockPosition().distSqr(site) > 6 * 6 || MineStairs.underground(level, f.blockPosition())) {
            if (MineStairs.underground(level, f.blockPosition())) {
                Job j = f.peekJob();
                if (j == null || j.type() != Job.Type.MINE) {
                    f.clearQueue();
                    f.enqueueFront(Job.mine(f.blockPosition().getY(), MineStairs.OUT));
                }
                return false;                                   // climbing out is its own work
            }
            Civics.goTo(f, site, 4.0, 0.8);
            return true;
        }
        f.getNavigation().stop();
        if (f.tickCount % 400 == 7 && QuestStories.watched(level, f.blockPosition(), 16)) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'm not going down there.", "You heard the noises. Cursed, I tell you.",
                "Not for all the iron in the hill.", "Somebody ought to see what it is. Not me."));
        }
        return true;
    }

    // ------------------------------------------------------------------ the end

    @Override
    public String ending(ServerLevel level, Quest q, Player p) {
        String fn = QuestStories.name(q, "foreman"), on = QuestStories.name(q, "old"), dn = QuestStories.name(q, "dweller");
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        boolean ally = !q.flag("ally").isEmpty();
        boolean spawner = "spawner".equals(q.flag("kind"));
        String marked = q.flag("marked");
        // The miners back to work, the lighter for it.
        for (Map.Entry<String, String> e : q.flags.entrySet()) {
            if (!e.getKey().startsWith("cast.")) continue;
            VillageFolkEntity m = Civics.find(level, UUID.fromString(e.getValue()));
            if (m == null) continue;
            m.persona().feelFor(p.getUUID(), name, 10);
            m.persona().remember(day, "the curse on the mine was lifted, by " + name, 5);
        }
        VillageFolkEntity dweller = ally ? QuestStories.role(level, q, "dweller") : null;
        if (dweller != null) {
            dweller.persona().remember(day, "I went down the deep level with " + name + " and we lifted the curse on the mine", 6);
            dweller.persona().feelFor(p.getUUID(), name, 10);
            dweller.creditTrade(150);
        }
        QuestBook.title(p.getUUID(), q.village, "Curse-lifter");
        q.outcome = (spawner ? "the spawner broken" : "the deep level cleared") + ", the mine lit and reopened" + (marked.isEmpty() ? "" : "; " + marked);
        q.flags.put("chronicle", "the curse on the mine was lifted: " + name + (ally ? " and " + dn : "") + " went down to the deep level, "
            + (spawner ? "broke the spawner that had broken through" : "cleared what was there") + " and lit it; the miners went back down"
            + (marked.isEmpty() ? "" : " (" + marked + ")"));
        q.flags.put("gossip", "Did you hear? {who} went down the deep level" + (ally ? " with " + dn : "") + " and broke the curse. "
            + (spawner ? "A spawner, would you believe — no curse at all." : "Monsters, that's all it was."));
        q.flags.put("memory", "lifted the curse on the mine");
        return "There was no curse. There was " + (spawner ? "a spawner, broken through the wall of an old dungeon " + on + "'s father's crew had sealed," : "a dark place, breeding monsters,")
            + " and now there is not: " + (spawner ? "it is broken" : "it is cleared") + ", and the deep level is lit."
            + (ally ? " " + dn + " was at your side all the way down, and tells it in the tavern every night." : "")
            + " " + fn + "'s crew went back down the next morning, grumbling that they had never really believed in it."
            + (marked.isEmpty() ? "" : " The town marked it with " + marked + ".");
    }

    @Override
    public void ended(ServerLevel level, Quest q) {
        if (q.state == State.DONE) return;
        Villages.tell(q.village, QuestRun.day(level), "the miners went back down the mine, grumbling: nobody lifted the curse");
    }
}
