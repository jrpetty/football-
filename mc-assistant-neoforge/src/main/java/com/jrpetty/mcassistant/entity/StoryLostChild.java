package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.State;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * [quests] Lost at Dusk: a child of a real household does not come home at dusk, and its parent comes to you.
 *
 * <ol>
 * <li><b>The alarm.</b> At dusk a child of the town, with a parent at home, is gone: off to a real place the town
 *     knows (a cave or a ravine the cave team found, else the woods out past the town), wandered there while nobody
 *     watched. The town's search party goes out (SearchParties) and turns back at dark; its parent asks you.</li>
 * <li><b>Where they played.</b> The child's friend (another child, else a neighbour) says where they were and which
 *     way it went, and that it had the wooden horse its parent whittled for it.</li>
 * <li><b>The toy in the grass.</b> The horse lies on the way (made of the stores' wood when the story began, and
 *     dropped there): pick it up. Small footprints lead on from it, and only you see them.</li>
 * <li><b>The tracks.</b> Follow them to the child: in the woods, down the cave, or at the foot of the ravine, where it
 *     fell and hurt its ankle (give it something to eat, or a remedy, and it can walk).</li>
 * <li><b>A promise.</b> It begs you not to tell where it went (its friend dared it): keep its secret, or say its
 *     parents must know.</li>
 * <li><b>The way home</b> (it keeps at your heels, by night too) and <b>home</b>: its parent's arms.</li>
 * </ol>
 * The end lasts: the parents' gratitude (coin from their purses, their warmth), a drawing the child makes for you of
 * the stores' paper and dye, the child's memory of you (it greets you by name for good), the chronicle; told, the
 * parents have a sign put up where the toy lay, warning the children off. If the search party finds the child
 * first, it is theirs to bring home, and the story ends with the parents' thanks for trying.
 */
final class StoryLostChild implements QuestStories.Story {

    static final StoryLostChild STORY = new StoryLostChild();

    private static final java.util.Map<UUID, Long> TRACKS = new java.util.concurrent.ConcurrentHashMap<>();

    static void resetForTests() {
        TRACKS.clear();
    }

    @Override
    public String key() {
        return "child";
    }

    // ------------------------------------------------------------------ the alarm

    @Override
    @Nullable
    public Quest begin(ServerLevel level, Villages.Village v, long day, Map<String, Object> given, boolean forced) {
        UUID id = v.id();
        long t = level.getDayTime() % 24000L;
        if (!forced && (t < 11000L || t > 13600L)) {
            QuestStories.why(id, "children are only lost at dusk");
            return null;
        }
        VillageFolkEntity child = QuestStories.folk(given, "child"), parent = QuestStories.folk(given, "parent");
        if (child == null) {
            int reach = Villages.townReach(id);
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity c) || !c.isBaby() || c.isShowcase() || !c.isAlive() || QuestStories.cast(c.getUUID())) continue;
                if (c.blockPosition().distSqr(v.centre()) > (double) (reach + 8) * (reach + 8)) continue;
                for (UUID u : c.parentIds()) {
                    VillageFolkEntity p = Civics.find(level, u);
                    if (QuestMaker.free(p)) { child = c; parent = p; break; }
                }
                if (child != null) break;
            }
        }
        if (child == null) {
            QuestStories.why(id, "no child with a parent at home");
            return null;
        }
        if (parent == null) {
            for (UUID u : child.parentIds()) {
                VillageFolkEntity p = Civics.find(level, u);
                if (QuestMaker.free(p)) { parent = p; break; }
            }
        }
        if (!QuestMaker.free(parent)) {
            QuestStories.why(id, "the child's parents are not at home");
            return null;
        }
        VillageFolkEntity friend = QuestStories.folk(given, "friend");
        if (friend == null) friend = friendOf(level, v, child, parent);
        if (friend == null) {
            QuestStories.why(id, "nobody who saw the child last");
            return null;
        }
        // Where it went: a cave or a ravine the cave team found, else the woods out past the town.
        BlockPos place = QuestStories.pos(given, "place");
        String variant = QuestStories.text(given, "variant");
        if (place == null) {
            for (CaveDwellers.Find x : CaveDwellers.report(id)) {
                int d = QuestMaker.distance(v.centre(), x.at());
                if ((x.kind() == CaveDwellers.Kind.CAVE || x.kind() == CaveDwellers.Kind.RAVINE) && d >= 40 && d <= 110 && level.isLoaded(x.at())) {
                    place = x.at();
                    variant = x.kind() == CaveDwellers.Kind.RAVINE ? "ravine" : "cave";
                    break;
                }
            }
        }
        if (place == null) {
            place = woods(level, v);
            variant = "woods";
        }
        if (place == null) {
            QuestStories.why(id, "nowhere near enough for a child to be lost in");
            return null;
        }
        if (variant.isEmpty()) variant = "woods";
        BlockPos clue = QuestStories.along(level, v.centre(), place, 0.6);
        String cn = child.displayNameCap(), pn = parent.displayNameCap(), fn = friend.displayNameCap();
        String dir = QuestRun.way(place.getX() - v.centre().getX(), place.getZ() - v.centre().getZ());
        Quest q = QuestMaker.newQuest("story.child", Kind.STORY, v, parent, "Lost at Dusk",
            QuestTalk.voice(parent,
                cn + " hasn't come home! It's getting dark, and nobody's seen " + cn + " since the afternoon. The search party's out but they'll not see a thing in the dark. "
                    + fn + " was playing with " + cn + " — please, will you help me look?",
                cn + "'s not home. It's dark and the search party's turning back. Help me find " + cn + ". " + fn + " was with " + cn + " last.",
                "It's " + cn + " — " + cn + " hasn't come home, and it's dark, and I don't — please. Please help me. " + fn + " might know where…",
                "Oh, please, please help me! " + cn + " hasn't come home and it's getting dark! " + fn + " was playing with " + cn + "!"));
        q.days = 2;
        q.warmth = 25;
        q.flags.put("standing", Standing.Title.VISITOR.name());
        QuestStories.cast(q, "child", child);
        QuestStories.cast(q, "parent", parent);
        QuestStories.cast(q, "friend", friend);
        QuestStories.place(q, "place", place);
        QuestStories.place(q, "clue", clue);
        q.flags.put("variant", variant);
        q.flags.put("dir", dir);
        VillageFolkEntity other = null;
        for (UUID u : child.parentIds()) {
            VillageFolkEntity o = Civics.find(level, u);
            if (o != null && o != parent) other = o;
        }
        q.payer = "purse";
        q.coins = QuestRewards.afford(parent, 12) + (other == null ? 0 : QuestRewards.afford(other, 8));
        if (other != null) {
            q.flags.put("payers", other.getStringUUID());
            QuestStories.cast(q, "other", other);
        }
        q.flags.put("reward", "something " + cn + " will make you");
        q.flags.put("rumour", "Have you heard? Little " + cn + " never came home. " + pn + "'s beside themselves.");
        if (!QuestRun.offer(level, q)) {
            QuestStories.why(id, pn + " is asking somebody about something else");
            return null;
        }
        // The world: the toy on the way (whittled for it of the stores' wood), the child gone while nobody watched.
        ItemStack toy = QuestItems.make(level, v, parent, McAssistantMod.WOODEN_TOY.get());
        if (toy.isEmpty()) toy = whittled(level, v);
        if (!toy.isEmpty()) {
            QuestItems.stamp(toy, q.id, cn + "'s wooden horse", "Whittled for " + cn + " by " + pn + ".");
            ItemEntity e = QuestItems.drop(level, clue, toy);
            q.flags.put("toy", e.getStringUUID());
        }
        Civics.folk(child.getUUID()).putLong("seenAt", child.blockPosition().asLong());
        child.clearQueue();
        child.getNavigation().stop();
        if (!QuestStories.watched(level, child.blockPosition(), 24) && !QuestStories.watched(level, place, 24)) {
            child.moveTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5, child.getYRot(), 0.0F);
        }
        q.flags.put("lostAt", Long.toString(level.getGameTime()));
        SearchParties.start(level, v, child, level.getGameTime());
        Villages.tell(id, day, cn + " did not come home at dusk; " + pn + " is asking everybody for help");
        return q;
    }

    /**
     * The toy whittled by its parent when the bench would not make it: a young town keeps every plank back for its
     * builders (Bench's keeps, the Wood Age), but a parent's whittling is a plank and two sticks, not a seller's stock,
     * so they come out of the stores all the same (a third plank split for the sticks if there are none). Nothing
     * when the stores have not the wood at all.
     */
    static ItemStack whittled(ServerLevel level, Villages.Village v) {
        java.util.function.Predicate<ItemStack> plank = s -> s.is(net.minecraft.tags.ItemTags.PLANKS) && s.getComponentsPatch().isEmpty();
        java.util.function.Predicate<ItemStack> stick = s -> s.is(net.minecraft.world.item.Items.STICK) && s.getComponentsPatch().isEmpty();
        boolean sticks = Market.stock(level, v.id(), stick) >= 2;
        if (Market.stock(level, v.id(), plank) < (sticks ? 1 : 3)) return ItemStack.EMPTY;
        if (!TownWork.take(level, v, plank, sticks ? 1 : 3)) return ItemStack.EMPTY;
        if (sticks) {
            if (!TownWork.take(level, v, stick, 2)) {
                Crafts.giveBack(level, v, net.minecraft.world.item.Items.OAK_PLANKS, 1);
                return ItemStack.EMPTY;
            }
        } else {
            Crafts.giveBack(level, v, net.minecraft.world.item.Items.STICK, 2);   // two planks split make four sticks: two over
        }
        return new ItemStack(McAssistantMod.WOODEN_TOY.get());
    }

    /** The child it plays with (another child of the town), else a grown neighbour who saw it last. */
    @Nullable
    static VillageFolkEntity friendOf(ServerLevel level, Villages.Village v, VillageFolkEntity child, VillageFolkEntity parent) {
        VillageFolkEntity best = null;
        int warmest = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity c) || c == child || !c.isBaby() || c.isShowcase() || !c.isAlive()) continue;
            int w = child.life().affinity(c.getUUID());
            if (w > warmest) { warmest = w; best = c; }
        }
        if (best != null) return best;
        double near = Double.MAX_VALUE;
        for (VillageFolkEntity g : Civics.grown(v.id())) {
            if (g == parent || child.parentIds().contains(g.getUUID()) || QuestStories.cast(g.getUUID())) continue;
            double d = g.distanceToSqr(child);
            if (d < near) { near = d; best = g; }
        }
        return best;
    }

    /** A spot in the woods (the woodcutters' way, else any) fifty to seventy-five blocks out, on dry ground. */
    @Nullable
    static BlockPos woods(ServerLevel level, Villages.Village v) {
        double angle = level.getRandom().nextDouble() * Math.PI * 2;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == AssistantEntity.StationTask.WOOD && a.workZone() != null) {
                BlockPos c = a.workZone().center();
                if (c.distSqr(v.centre()) > 20 * 20) {
                    angle = Math.atan2(c.getZ() - v.centre().getZ(), c.getX() - v.centre().getX());
                    break;
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            double a = angle + i * Math.PI / 4;
            int r = 50 + level.getRandom().nextInt(26);
            int x = v.centre().getX() + (int) Math.round(Math.cos(a) * r), z = v.centre().getZ() + (int) Math.round(Math.sin(a) * r);
            if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
            BlockPos p = QuestStories.surface(level, x, z);
            if (level.getFluidState(p.below()).isEmpty() && level.getFluidState(p).isEmpty()) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the search

    @Override
    public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
        String cn = QuestStories.name(q, "child"), fn = QuestStories.name(q, "friend");
        q.steps.add(new Step(StepType.TALK, "friend", "Ask " + fn + " where " + cn + " was last playing").chapter("Where they played")
            .who(QuestStories.roleId(q, "friend"), fn));
        return QuestTalk.voice(giver, "Thank you. Oh, thank you. Ask " + fn + " — " + fn + " was with " + cn + " this afternoon. Please hurry.",
            "Then go. Ask " + fn + ". Hurry.", "Th-thank you. " + fn + "… ask " + fn + ". Please be quick.",
            "Thank you! Ask " + fn + " where they were playing — and please, please hurry!");
    }

    @Override
    public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
        String cn = QuestStories.name(q, "child"), pn = QuestStories.name(q, "parent"), fn = QuestStories.name(q, "friend");
        String variant = q.flag("variant"), dir = q.flag("dir");
        BlockPos clue = QuestStories.place(q, "clue"), place = QuestStories.place(q, "place");
        String where = switch (variant) {
            case "cave" -> "by the hole in the ground out " + dir;
            case "ravine" -> "near the big crack in the ground out " + dir;
            default -> "in the woods out " + dir;
        };
        switch (s.key) {
            case "friend" -> {
                boolean toy = !q.flag("toy").isEmpty() && level.getEntity(UUID.fromString(q.flag("toy"))) != null;
                s.note = fn + " said they were playing " + where + ", and " + cn + " went off after a fox.";
                if (toy) {
                    q.steps.add(new Step(StepType.GET, "toy", "Look for anything " + cn + " dropped on the way out " + dir).chapter("The toy in the grass")
                        .at(clue, 8).item("token:wooden_toy", 1));
                } else {
                    q.steps.add(new Step(StepType.GO, "clue", "Go out " + dir + " and look for " + cn + "'s footprints").chapter("Small footprints").at(clue, 6));
                }
                boolean grown = f != null && !f.isBaby();
                return grown ? "I saw " + cn + " heading out " + dir + " this afternoon — " + where.replace("by the", "toward the").replace("in the", "toward the")
                        + ". After a fox, I think. " + (toy ? cn + " had that wooden horse " + pn + " made." : "")
                    : "We were playing " + where + ". " + cn + " saw a fox and went after it, and I didn't — I didn't go, I promise! "
                        + (toy ? cn + " had the wooden horse " + pn + " made." : "") + " Is " + cn + " in trouble?";
            }
            case "toy", "clue" -> {
                s.note = s.key.equals("toy") ? cn + "'s wooden horse, in the grass. Small footprints lead on " + dir + "." : "Small footprints in the soft ground, leading on " + dir + ".";
                // The child's place, a little out (the journal says which way, not the very spot).
                BlockPos about = place.offset(level.getRandom().nextInt(9) - 4, 0, level.getRandom().nextInt(9) - 4);
                Step find = new Step(StepType.FIND, "find", "Follow the tracks " + dir + " and find " + cn
                    + (variant.equals("woods") ? "" : ", somewhere below")).chapter("The tracks").who(QuestStories.roleId(q, "child"), cn).at(about, 4);
                q.steps.add(find);
                return "";
            }
            case "find" -> {
                q.flags.put("found", "you");
                QuestBook.changed();
                if (variant.equals("ravine")) {
                    s.note = "Found at the foot of the ravine, hurt.";
                    q.steps.add(new Step(StepType.GIVE, "tend", cn + " has hurt an ankle: give " + cn + " something to eat, or a remedy").chapter(cn)
                        .who(QuestStories.roleId(q, "child"), cn).item("food", 1));
                    q.flags.put("give.tend", "folk");
                    return "You — you found me! I fell. My ankle — I can't walk on it. And I'm so hungry…";
                }
                s.note = variant.equals("cave") ? "Found down in the cave, frightened." : "Found in the woods, cold and frightened.";
                promise(q);
                return "You found me! I wasn't lost, I was just — I couldn't find the way. Please… please don't tell " + pn + " where I went. " + fn + " dared me.";
            }
            case "tend" -> {
                if (f != null) f.heal(8.0F);
                s.note = "Fed, and on its feet.";
                promise(q);
                return "That's better. I can walk, if you hold my hand. Please don't tell " + pn + " I came down here — " + fn + " dared me.";
            }
            case "home" -> {
                s.note = "Home.";
                q.flags.put("home", "1");               // home: its own day again
                q.steps.add(new Step(StepType.TALK, "parent", "Bring " + cn + " to " + pn).chapter("Home").who(QuestStories.roleId(q, "parent"), pn));
                return "";
            }
            case "parent" -> {
                return reunion(q);
            }
            default -> {
                return "";
            }
        }
    }

    private static void promise(Quest q) {
        String cn = QuestStories.name(q, "child");
        q.steps.add(new Step(StepType.CHOOSE, "secret", cn + " begs you not to tell where it went").chapter("A promise")
            .who(QuestStories.roleId(q, "child"), cn).option("keep", "Your secret's safe").option("tell", "Your parents must know"));
    }

    private static String reunion(Quest q) {
        String cn = QuestStories.name(q, "child");
        return "tell".equals(q.flag("chose.secret"))
            ? cn + "! Oh, " + cn + " — where were you? … You did WHAT? Never, ever again, do you hear me? Oh, come here."
            : cn + "! Oh, " + cn + ", come here, come here. Where were you? … Never mind. You're home.";
    }

    @Override
    public String chose(ServerLevel level, Quest q, Step s, String option, VillageFolkEntity f, Player p) {
        String cn = QuestStories.name(q, "child"), pn = QuestStories.name(q, "parent");
        VillageFolkEntity child = QuestStories.role(level, q, "child");
        BlockPos home = child == null ? null : Civics.home(child);
        Villages.Village v = Villages.get(q.village);
        BlockPos to = home != null ? home : v != null ? v.centre() : QuestStories.place(q, "clue");
        int r = home != null ? 8 : v != null ? Math.max(16, Villages.townReach(q.village) / 2) : 16;
        q.steps.add(new Step(StepType.WAIT, "home", "Bring " + cn + " home: it is holding your hand").chapter("The way home").at(to, r));
        if (option.equals("keep")) {
            s.note = "You promised.";
            return "Promise? Cross your heart? Thank you. Let's go home. Can I hold your hand?";
        }
        s.note = "You said " + pn + " must know.";
        return "…All right. I suppose. Will " + pn + " be very cross? Can I hold your hand, at least?";
    }

    // ------------------------------------------------------------------ the tracks, the way home

    @Override
    public void tick(ServerLevel level, Quest q, Player p) {
        Step s = q.current();
        if (s == null) return;
        if ("party".equals(q.flag("found"))) {
            partyFound(level, q, p);
            return;
        }
        if ((s.key.equals("toy") || s.key.equals("clue") || s.key.equals("find")) && p instanceof ServerPlayer sp) tracks(level, sp, q);
        if (s.key.equals("home") && s.at != null) {
            VillageFolkEntity child = QuestStories.role(level, q, "child");
            if (child == null) return;
            double dx = child.getX() - (s.at.getX() + 0.5), dz = child.getZ() - (s.at.getZ() + 0.5);
            if (dx * dx + dz * dz <= (double) s.radius * s.radius && !MineStairs.underground(level, child.blockPosition())) {
                child.getNavigation().stop();
                QuestRun.reached(level, q, s, child, p);
            }
        }
    }

    /** Small footprints from where the toy lay toward the child, the next few ahead of the player: seen by it alone. */
    static void tracks(ServerLevel level, ServerPlayer p, Quest q) {
        long now = level.getGameTime();
        if (now - TRACKS.getOrDefault(p.getUUID(), -100L) < 30L) return;
        TRACKS.put(p.getUUID(), now);
        BlockPos from = QuestStories.place(q, "clue"), to = QuestStories.place(q, "place");
        int steps = Math.max(2, QuestMaker.distance(from, to) / 3);
        int shown = 0;
        for (int i = 0; i <= steps && shown < 6; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t), z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (p.distanceToSqr(x + 0.5, p.getY(), z + 0.5) > 20 * 20) continue;
            BlockPos ground = level.isLoaded(new BlockPos(x, 64, z)) ? QuestStories.surface(level, x, z) : new BlockPos(x, (int) p.getY(), z);
            if (i == steps) ground = to;
            double side = (i % 2 == 0 ? 0.18 : -0.18);
            level.sendParticles(p, ParticleTypes.WAX_OFF, true, x + 0.5 + side, ground.getY() + 0.05, z + 0.5, 2, 0.04, 0.0, 0.04, 0.0);
            shown++;
        }
    }

    /**
     * [VillageFolkEntity.aiStep] The lost child: making for its place (or, nobody watching, there already), then kept
     * there, calling out when somebody is near; found by the search party, it is theirs. Found by you and promised,
     * it keeps at your heels.
     */
    @Override
    public boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
        if (!f.getUUID().equals(QuestStories.roleId(q, "child"))) return false;
        Step s = q.current();
        if ("party".equals(q.flag("found")) || !q.flag("home").isEmpty()) return false;
        if (s != null && s.key.equals("home")) return QuestStories.follow(f, level, q.player);
        if (!q.flag("found").isEmpty()) {
            f.getNavigation().stop();
            return true;                                         // found: it waits for you, by your side
        }
        // The search party at its side: found by them, and theirs to bring home.
        for (VillageFolkEntity g : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(3.5),
                x -> x != f && !x.isBaby() && x.isAlive() && q.village.equals(x.ownerId()))) {
            q.flags.put("found", "party");
            q.flags.put("foundBy", g.displayNameCap());
            QuestBook.changed();
            return false;
        }
        BlockPos place = QuestStories.place(q, "place");
        if (f.blockPosition().distSqr(place) > 5 * 5) {
            if (!QuestStories.watched(level, f.blockPosition(), 24) && !QuestStories.watched(level, place, 24)) {
                f.moveTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5, f.getYRot(), 0.0F);
                f.getNavigation().stop();
            } else {
                Civics.goTo(f, place, 2.0, 0.7);
            }
            return true;
        }
        f.getNavigation().stop();
        if (f.tickCount % 160 == 0 && QuestStories.watched(level, f.blockPosition(), 16)) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hello? Is somebody there?", "Mum? MUM!", "I want to go home…", "Help! I'm down here!"));
        }
        return true;
    }

    @Override
    public void townTick(ServerLevel level, Villages.Village v, Quest q) {
        if (!"party".equals(q.flag("found"))) return;
        if (q.state == State.OFFERED) {
            QuestRun.withdraw(level, q, "the search party found " + QuestStories.name(q, "child"));
            return;
        }
        Player p = q.player == null ? null : level.getPlayerByUUID(q.player);
        if (p != null) partyFound(level, q, p);
    }

    /** The search party got there first: the child is theirs to bring home, and the story ends with thanks for trying. */
    private static void partyFound(ServerLevel level, Quest q, Player p) {
        if (q.state != State.ACTIVE) return;
        String cn = QuestStories.name(q, "child");
        for (Step st : q.steps) {
            if (st.done) continue;
            st.done = true;
            st.note = "The search party found " + cn + " first (" + q.flag("foundBy") + ").";
        }
        q.coins = 0;
        q.warmth = 6;
        q.flags.put("reward", "");
        q.outcome = "found by the search party";
        QuestRun.finish(level, q, p);
    }

    // ------------------------------------------------------------------ the end

    @Override
    public String ending(ServerLevel level, Quest q, Player p) {
        String cn = QuestStories.name(q, "child"), pn = QuestStories.name(q, "parent"), fn = QuestStories.name(q, "friend");
        String name = p.getName().getString();
        long day = QuestRun.day(level);
        Villages.Village v = Villages.get(q.village);
        if ("party".equals(q.flag("found"))) {
            q.flags.put("chronicle", cn + " was found by the search party (" + q.flag("foundBy") + "), with " + name + " out looking too");
            return "The search party found " + cn + " before you did: " + q.flag("foundBy") + " carried " + cn + " home. " + pn
                + " thanked you all the same for going out into the dark.";
        }
        VillageFolkEntity child = QuestStories.role(level, q, "child"), parent = QuestStories.role(level, q, "parent"),
            other = QuestStories.role(level, q, "other"), friend = QuestStories.role(level, q, "friend");
        boolean told = "tell".equals(q.flag("chose.secret"));
        String variant = q.flag("variant"), dir = q.flag("dir");
        String where = switch (variant) {
            case "cave" -> "down a cave " + dir + " of the town";
            case "ravine" -> "at the foot of a ravine " + dir + " of the town, hurt";
            default -> "in the woods " + dir + " of the town";
        };
        for (VillageFolkEntity g : new VillageFolkEntity[]{ parent, other }) {
            if (g == null) continue;
            g.persona().feelFor(p.getUUID(), name, told ? 35 : 25);
            g.persona().remember(day, name + " found our " + cn + " when " + cn + " was lost at dusk", 8);
            q.flags.put("thanks." + g.getUUID(), "We'll never forget what you did for " + cn + ", {who}.");
        }
        if (child != null) {
            child.persona().feelFor(p.getUUID(), name, told ? 30 : 60);
            child.persona().remember(day, name + " found me " + where + " and brought me home" + (told ? "" : ", and kept my secret"), 9);
            q.flags.put("thanks." + child.getUUID(), "That's {who}! {who} found me when I was lost!");
        }
        if (friend != null) friend.persona().feelFor(p.getUUID(), name, 5);
        // The child's drawing, of the stores' paper and a dye: you and it, under the moon.
        String gift = "";
        ItemStack drawing = v == null ? ItemStack.EMPTY : QuestItems.make(level, v, null, McAssistantMod.CHILDS_DRAWING.get());
        if (!drawing.isEmpty()) {
            QuestItems.stamp(drawing, q.id, cn + "'s drawing", "You and " + cn + ", hand in hand, under a big yellow moon.", "\"For " + name + ", love from " + cn + "\"");
            QuestItems.give(p, drawing);
            gift = cn + " drew you a picture: the two of you under the moon. ";
            // The horse back to the child, now it has drawn you something.
            for (ItemStack toy : QuestItems.take(p, QuestItems.matcher("token:wooden_toy", q.id), 1)) {
                if (child != null) QuestItems.toFolk(child, toy);
            }
        } else if (QuestItems.count(p, QuestItems.matcher("token:wooden_toy", q.id)) > 0) {
            gift = cn + " wants you to keep the wooden horse, for finding it. ";
        }
        String sign = "";
        if (told && v != null) {
            BlockPos clue = QuestStories.place(q, "clue");
            if (QuestStories.sign(level, v, clue, new String[]{ "CHILDREN!", "Not past here", "without a", "grown-up" })) {
                sign = " A sign now stands where the toy lay, warning the children off.";
            }
        }
        QuestBook.title(p.getUUID(), q.village, "Finder of the Lost");
        q.outcome = "found " + where + (told ? "; " + pn + " was told" : "; the secret kept");
        q.flags.put("chronicle", cn + ", lost " + where + " at dusk, was found by " + name + " and brought home" + (sign.isEmpty() ? "" : "; a sign was put up to warn the children off"));
        q.flags.put("gossip", told ? "{who} found little " + cn + " " + where + ". There's a sign up now — no child's to go past it."
            : "{who} found little " + cn + " when it went missing. Wouldn't say where, mind. Thick as thieves, those two.");
        q.flags.put("memory", "found our " + cn + " and brought " + cn + " home");
        return cn + " was home before the moon was high. " + pn + (other != null ? " and " + other.displayNameCap() : "")
            + " held on to " + cn + " as if they would never let go. " + gift
            + (told ? "You told them where " + cn + " had been; " + cn + " sulked all the next day, and " + fn + " was kept in for a week." + sign
                : "You kept " + cn + "'s secret, and " + cn + " will never forget it — nor you.")
            + " The town will tell of the night " + cn + " was lost, and who found " + cn + ".";
    }

    @Override
    public void ended(ServerLevel level, Quest q) {
        TRACKS.remove(q.player == null ? new UUID(0, 0) : q.player);
        if (q.state == State.DONE || "party".equals(q.flag("found"))) return;
        // Never found: it finds its own way home, cold and frightened, in the end.
        VillageFolkEntity child = QuestStories.role(level, q, "child");
        Villages.Village v = Villages.get(q.village);
        if (child == null || v == null) return;
        BlockPos home = Civics.home(child);
        BlockPos to = home != null ? home : v.centre();
        if (!QuestStories.watched(level, child.blockPosition(), 24) && level.isLoaded(to)) {
            child.moveTo(to.getX() + 0.5, to.getY() + 0.1, to.getZ() + 0.5, child.getYRot(), 0.0F);
        }
        Villages.tell(q.village, QuestRun.day(level), QuestStories.name(q, "child") + " found its own way home in the end, cold and frightened");
    }

    @Override
    public boolean stands(ServerLevel level, Quest q) {
        UUID child = QuestStories.roleId(q, "child");
        Entity e = child == null ? null : level.getEntity(child);
        return e == null || e.isAlive();
    }
}
