package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.QuestBook.Kind;
import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.entity.QuestBook.Step;
import com.jrpetty.mcassistant.entity.QuestBook.StepType;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [quests] The quests a town's folk offer, out of what is really the matter: never a fixed list.
 *
 * <p><b>Favours</b>, each a folk's own, paid out of its own purse:
 * <ul>
 * <li><b>Remedies for the sick.</b> A folk down with a cold or badly hurt, and not a honey bottle, golden carrot or
 *     healing potion left in the stores for the healer's round: its partner, a parent or the folk itself asks for
 *     three. One goes to it at once; the rest into the stores.</li>
 * <li><b>A smith's masterpiece.</b> A smith of some years in an Iron Age town, and not a diamond in the stores:
 *     bring it two, and it makes a diamond sword of them with a stick from the stores, as fine as its hand can,
 *     with its mark on it. The sword is your reward.</li>
 * <li><b>A letter, or a parcel.</b> A folk with a close friend, a partner or kin living in another town writes to
 *     them (a sealed letter made of the stores' paper and wax), or sends kin a parcel of the stores' bread, paid for
 *     out of its own purse. Carried over, it is read, and, when that town can make a letter, answered: carry the
 *     reply home. Both are the closer for it, and the two towns a little warmer.</li>
 * <li><b>A cake for the wedding.</b> A couple to be wed: a parent or a friend of theirs asks for a cake (or a
 *     pumpkin pie) to be taken to them, and to be told it was.</li>
 * <li><b>A lost pet.</b> A household's cat or dog (Families) gone off far from home: find it, bring it home (a
 *     lead helps), and tell its family. A seam for the pets of a later day (lostPets).</li>
 * </ul>
 * <b>For the town</b>, offered by its elder and paid out of the treasury (and pinned on the quest board):
 * <ul>
 * <li><b>The shortfall:</b> what the town is short of for its next building or age, not already on the board
 *     ("48 iron for the smeltery").</li>
 * <li><b>A den:</b> monsters gathered about the town, three or more together: clear them.</li>
 * <li><b>The elder's letter</b> to a neighbour the town is on uneasy or middling terms with: carried and read, the
 *     two towns the warmer.</li>
 * </ul>
 * <b>War and peace</b>, a town at war (or in a feud):
 * <ul>
 * <li><b>Scout the rival:</b> go and look at it, and bring back a spy's report (made of the stores' paper and ink,
 *     filled in on the spot with what is really there): it is filed as the town's latest on them (Intel).</li>
 * <li><b>Carry the peace terms</b> (WarAndPeace.terms, written out and sealed) to the enemy's elder, after three
 *     days of war: if it will hear them, the peace is made there and then, on your word.</li>
 * <li><b>Free the captured spy</b> (Spies): go to the town that holds it, find it, and set it free. The town that
 *     held it will not thank you.</li>
 * <li><b>Supply the besieged:</b> a town at war with its larder low asks for food.</li>
 * </ul>
 * <b>Below ground</b>, from the cave dwellers' report (CaveDwellers):
 * <ul>
 * <li><b>Recover a find:</b> a vein of diamonds, emeralds, gold, lapis or redstone the cave team saw and could not
 *     take: go down, mine it, bring it up.</li>
 * <li><b>Seal a spawner:</b> one the cave team found near the town: break it.</li>
 * <li><b>Find the lost:</b> a folk the town is out searching for (SearchParties), a lost cave dweller among them:
 *     find it, bring it home.</li>
 * </ul>
 * A town has four offers standing at most (its story apart), and a new one goes up at most every couple of minutes.
 */
public final class QuestMaker {

    private QuestMaker() {}

    /** The most offers a town has standing at once, its story apart. */
    static final int MOST_OFFERS = 4;
    /** A new offer in a town at most this often (ticks). */
    static final long EVERY = 2400L;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static volatile boolean eager;

    static void eager(boolean on) {
        eager = on;
    }

    static void resetForTests() {
        LAST.clear();
        QuestTalk.resetForTests();
    }

    /** One kind of offer, made out of the town as it stands, or null when nothing is the matter. */
    interface Maker {
        @Nullable
        Quest make(ServerLevel level, Villages.Village v, long day);
    }

    private static final Map<String, Maker> MAKERS = new LinkedHashMap<>();

    static void register() {
        MAKERS.put("healer", QuestMaker::healer);
        MAKERS.put("masterpiece", QuestMaker::masterpiece);
        MAKERS.put("letter", QuestMaker::letter);
        MAKERS.put("wedding", QuestMaker::wedding);
        MAKERS.put("pet", QuestMaker::pet);
        MAKERS.put("shortfall", QuestMaker::shortfall);
        MAKERS.put("den", QuestMaker::den);
        MAKERS.put("envoy", QuestMaker::envoy);
        MAKERS.put("scout", QuestMaker::scout);
        MAKERS.put("peace", QuestMaker::peace);
        MAKERS.put("spy", QuestMaker::spy);
        MAKERS.put("supply", QuestMaker::supply);
        MAKERS.put("vein", QuestMaker::vein);
        MAKERS.put("seal", QuestMaker::seal);
        MAKERS.put("missing", QuestMaker::missing);
        QuestRun.script("favour.healer", HEALER);
        QuestRun.script("favour.masterpiece", MASTERPIECE);
        QuestRun.script("favour.letter", LETTER);
        QuestRun.script("favour.wedding", WEDDING);
        QuestRun.script("favour.pet", PET);
        QuestRun.script("town.shortfall", SHORTFALL);
        QuestRun.script("town.den", DEN);
        QuestRun.script("town.envoy", ENVOY);
        QuestRun.script("war.scout", SCOUT);
        QuestRun.script("war.peace", PEACE);
        QuestRun.script("war.spy", SPY);
        QuestRun.script("war.supply", SUPPLY);
        QuestRun.script("caves.vein", VEIN);
        QuestRun.script("caves.seal", SEAL);
        QuestRun.script("caves.missing", MISSING);
        QuestRun.script("board", BOARD);
        Weave.registerQuests(MAKERS);           // [weave] help the watch, water for the fire, the flood, the rebuilding; the real lost pets
    }

    // ------------------------------------------------------------------ the town's look

    /** Now and then, a town's folk look at what is the matter and one of them asks for help with it. */
    static void look(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) < 4) return;
        int open = 0;
        for (Quest q : QuestBook.offers(id)) if (!q.story()) open++;
        if (open >= MOST_OFFERS) return;
        long now = level.getGameTime();
        if (!eager && now - LAST.getOrDefault(id, -100000L) < EVERY) return;
        LAST.put(id, now);
        List<String> keys = new ArrayList<>(MAKERS.keySet());
        java.util.Collections.shuffle(keys, new java.util.Random(now ^ id.getLeastSignificantBits()));
        for (String k : keys) {
            Quest q = tryMake(level, v, day, k);
            if (q != null && QuestRun.offer(level, q)) return;
        }
    }

    @Nullable
    private static Quest tryMake(ServerLevel level, Villages.Village v, long day, String key) {
        Maker m = MAKERS.get(key);
        if (m == null) return null;
        try {
            return m.make(level, v, day);
        } catch (RuntimeException e) {
            com.mojang.logging.LogUtils.getLogger().warn("[MCA-QUESTS] the {} offer in {}: {}", key, Villages.name(v.id()), e.toString());
            return null;
        }
    }

    /** Tests: this kind of offer made now, if the town's state calls for it, and offered. */
    @Nullable
    public static Quest offerForTests(ServerLevel level, Villages.Village v, String which) {
        QuestRun.script(new Quest());          // the scripts registered
        Quest q = tryMake(level, v, QuestRun.day(level), which);
        return q != null && QuestRun.offer(level, q) ? q : null;
    }

    /** The stage (ops): the first kind of offer the town can make now, made and offered. */
    @Nullable
    static Quest stage(ServerLevel level, Villages.Village v, Player p) {
        for (String k : MAKERS.keySet()) {
            Quest q = tryMake(level, v, QuestRun.day(level), k);
            if (q != null && QuestRun.offer(level, q)) return q;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static Quest newQuest(String script, Kind kind, Villages.Village v, VillageFolkEntity giver, String title, String offer) {
        Quest q = QuestBook.create();
        q.script = script;
        q.kind = kind;
        q.village = v.id();
        q.giver = giver.getUUID();
        q.giverName = giver.displayNameCap();
        q.title = title;
        q.offer = offer;
        return q;
    }

    /** Is there an open quest of this script in the town with this flag? (Never two of the same.) */
    static boolean already(UUID village, String script, String flag, String value) {
        for (Quest q : QuestBook.open(village)) if (q.script.equals(script) && value.equals(q.flag(flag))) return true;
        return false;
    }

    static boolean free(@Nullable VillageFolkEntity f) {
        return f != null && f.isAlive() && !f.isShowcase() && !f.isBaby() && !QuestBook.giving(f.getUUID()) && !Visitors.is(f);
    }

    static List<VillageFolkEntity> grown(UUID village) {
        return Civics.grown(village);
    }

    /** The loaded elder, else the leader. */
    @Nullable
    static VillageFolkEntity elder(ServerLevel level, UUID village) {
        UUID e = Villages.elder(village);
        VillageFolkEntity f = Civics.find(level, e);
        if (f != null) return f;
        return Villages.leader(village) instanceof VillageFolkEntity l ? l : null;
    }

    /** Its partner, a parent or a grown child, loaded: who speaks for a folk. */
    @Nullable
    static VillageFolkEntity kin(ServerLevel level, VillageFolkEntity f) {
        VillageFolkEntity partner = Civics.find(level, f.life().partner());
        if (free(partner)) return partner;
        for (UUID u : f.parentIds()) {
            VillageFolkEntity p = Civics.find(level, u);
            if (free(p)) return p;
        }
        if (f.ownerId() != null) {
            for (VillageFolkEntity c : grown(f.ownerId())) if (c.parentIds().contains(f.getUUID()) && free(c)) return c;
        }
        return null;
    }

    static String words(Item it, int n) {
        return Bench.words(it, n);
    }

    static int distance(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    static String where(BlockPos from, BlockPos to) {
        int d = distance(from, to);
        return Math.max(10, d / 10 * 10) + " blocks " + QuestRun.way(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    // ================================================================== favours

    // ------------------------------------------------------------------ remedies for the sick

    @Nullable
    static Quest healer(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Market.stock(level, id, QuestItems.matcher("remedy", 0)) >= 2) return null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity sick) || sick.isShowcase() || !sick.isAlive() || Visitors.is(sick)) continue;
            boolean ill = sick.health().ill();
            boolean hurt = sick.getHealth() < sick.getMaxHealth() * 0.6F && sick.getTarget() == null;
            if (!ill && !hurt) continue;
            if (already(id, "favour.healer", "sick", sick.getStringUUID())) continue;
            VillageFolkEntity giver = kin(level, sick);
            if (giver == null && free(sick)) giver = sick;
            if (giver == null) continue;
            String who = giver == sick ? "me" : sick.displayNameCap();
            String what = ill ? (giver == sick ? "this cold" : "a cold") : (giver == sick ? "the hurt I took" : "a bad hurt");
            int n = 3;
            Quest q = newQuest("favour.healer", Kind.FAVOUR, v, giver, "Remedies for " + (giver == sick ? giver.displayNameCap() : sick.displayNameCap()),
                QuestTalk.voice(giver,
                    (giver == sick ? "I can't shake " + what + ". " : sick.displayNameCap() + "'s laid up with " + what + ". ")
                        + "The healer's been round, but there's not a honey bottle or a golden carrot left in the stores. Could you bring three? Honey, golden carrots, a healing potion — whatever you can find.",
                    (giver == sick ? "This " + (ill ? "cold" : "hurt") + " won't shift" : sick.displayNameCap() + "'s poorly")
                        + ", and the stores are bare. Three remedies: honey, golden carrots, healing potions.",
                    "It's — it's " + (giver == sick ? "me" : sick.displayNameCap()) + ". " + (ill ? "A cold" : "Hurt") + ", and the healer has nothing left to give. Would you… could you bring three remedies? Honey bottles, or golden carrots…",
                    (giver == sick ? "I'm a wreck, look at me!" : "Poor " + sick.displayNameCap() + "'s in a bad way!") + " And not a drop of honey in the stores. Bring three remedies and you'll be everybody's favourite!"));
            q.coins = QuestRewards.afford(giver, 4 + 2 * n);
            q.payer = "purse";
            q.warmth = 12;
            q.flags.put("sick", sick.getStringUUID());
            q.flags.put("memory", "brought remedies when " + (giver == sick ? "I was" : sick.displayNameCap() + " was") + " poorly");
            q.steps.add(new Step(StepType.GIVE, "remedies", "Bring " + giver.displayNameCap() + " " + QuestItems.words("remedy", n)
                + (giver == sick ? "" : " for " + sick.displayNameCap())).who(giver).item("remedy", n));
            return q;
        }
        return null;
    }

    static final QuestRun.Script HEALER = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            VillageFolkEntity sick = Civics.find(level, q.flagId("sick"));
            String name = p.getName().getString();
            if (sick != null) {
                // One for the patient at once (out of the stores, where it went): the rest for the healer's round.
                Villages.Village v = Villages.get(q.village);
                if (v != null && TownWork.take(level, v, QuestItems.matcher("remedy", 0), 1)) {
                    sick.heal(6.0F);
                    if (sick.health().ill()) sick.health().cold = Math.max(0, sick.health().cold - Health.REMEDY);
                }
                sick.persona().feelFor(p.getUUID(), name, 8);
                sick.persona().remember(QuestRun.day(level), name + " brought me remedies when I was poorly", 4);
                q.flags.put("thanks." + sick.getUUID(), "I'm feeling ever so much better, {who}. Thank you.");
            }
            q.flags.put("chronicle", name + " brought remedies for " + (sick == null ? "the sick" : sick.displayNameCap()));
            q.flags.put("gossip", "{who} brought honey and the like for " + (sick == null ? "the sick" : sick.displayNameCap()) + ". Kind, that.");
            return f == null ? "" : QuestTalk.voice(f, "Bless you. That'll set things right.", "About time. Thank you.",
                "Oh — thank you. Really. Thank you.", "Oh, you angel! Thank you!");
        }
    };

    // ------------------------------------------------------------------ a smith's masterpiece

    @Nullable
    static Quest masterpiece(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return null;
        if (Market.stock(level, id, s -> s.is(Items.DIAMOND)) >= 2) return null;
        if (Market.stock(level, id, s -> s.is(Items.STICK) || s.is(ItemTags.PLANKS)) <= 0) return null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity smith) || smith.stationTask() != AssistantEntity.StationTask.SMITH || !free(smith)) continue;
            if (smith.veteranLevel() < 5) continue;
            Quest q = newQuest("favour.masterpiece", Kind.FAVOUR, v, smith, "A masterpiece for " + smith.displayNameCap(),
                QuestTalk.voice(smith,
                    "I've forged more iron blades than I can count. Just once I'd like to make something truly fine — a diamond sword, the best my hands can do. Bring me two diamonds and it's yours.",
                    "Iron, iron, iron. I'm sick of it. Two diamonds and I'll make you a sword worth having. Yours to keep.",
                    "I — I've always wanted to make a diamond sword. A real one. If you brought me two diamonds… you could have it, of course.",
                    "Do you know what I dream of? A diamond sword! Bring me two diamonds and I'll make you the finest blade this town has seen!"));
            q.coins = 0;
            q.payer = "purse";
            q.warmth = 15;
            q.flags.put("reward", "the sword itself, as fine as " + smith.displayNameCap() + "'s hand can make it");
            q.flags.put("give.diamonds", "keep");
            q.flags.put("memory", "brought me the diamonds for my masterpiece");
            q.steps.add(new Step(StepType.GIVE, "diamonds", "Bring " + smith.displayNameCap() + " two diamonds").who(smith).item("minecraft:diamond", 2));
            return q;
        }
        return null;
    }

    static final QuestRun.Script MASTERPIECE = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            Villages.Village v = Villages.get(q.village);
            if (f == null || v == null) return "";
            // The hilt: a stick from the stores (or a plank sawn into two, the other put back), else the player's own.
            boolean hilt = TownWork.take(level, v, st -> st.is(Items.STICK), 1);
            if (!hilt && TownWork.take(level, v, st -> st.is(ItemTags.PLANKS), 1)) {
                hilt = true;
                Crafts.giveBack(level, v, Items.STICK, 1);
            }
            if (!hilt) hilt = !QuestItems.take(p, st -> st.is(Items.STICK), 1).isEmpty();
            if (!hilt) {
                QuestItems.give(p, new ItemStack(Items.DIAMOND, 2));
                q.outcome = "no stick for the hilt: the diamonds given back";
                return "Not a stick in the place for the hilt! Here — your diamonds back. Bring a stick, another time.";
            }
            ItemStack sword = Craftsmanship.finish(level, new ItemStack(Items.DIAMOND_SWORD), f.veteranLevel(), f.displayNameCap());
            QuestItems.give(p, sword);
            f.creditTrade(200);
            String name = p.getName().getString();
            q.outcome = "a diamond sword of " + f.displayNameCap() + "'s making";
            q.flags.put("chronicle", f.displayNameCap() + " made a diamond sword of " + name + "'s diamonds, the finest work of its life");
            q.flags.put("gossip", "Have you seen the sword " + f.displayNameCap() + " made for {who}? Diamond! I've never seen the like.");
            return QuestTalk.voice(f, "There. Feel the balance of it. My finest work, and it's yours.", "There. Best thing I've ever made. Don't you dare lose it.",
                "It's… it's done. I think it's the best thing I've ever made. Please — take it.", "Look at it! LOOK at it! It's yours — my masterpiece!");
        }
    };

    // ------------------------------------------------------------------ a letter, or a parcel

    @Nullable
    static Quest letter(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (VillageFolkEntity f : grown(id)) {
            if (!free(f)) continue;
            List<UUID> close = new ArrayList<>();
            if (f.life().partner() != null) close.add(f.life().partner());
            for (Map.Entry<UUID, Social.Bond> b : f.life().bonds.entrySet()) if (b.getValue().affinity >= Social.FRIEND) close.add(b.getKey());
            close.addAll(f.parentIds());
            for (UUID u : close) {
                VillageFolkEntity o = Civics.find(level, u);
                if (o == null || o.ownerId() == null || o.ownerId().equals(id) || o.isShowcase()) continue;
                Villages.Village there = Villages.get(o.ownerId());
                if (there == null || already(id, "favour.letter", "to", o.getStringUUID())) continue;
                boolean kin = o.parentIds().contains(f.getUUID()) || f.parentIds().contains(o.getUUID()) || u.equals(f.life().partner());
                boolean parcel = kin && Market.stock(level, id, s -> s.is(Items.BREAD)) >= 8 && f.purse() >= 3;
                Item token = parcel ? McAssistantMod.PARCEL.get() : McAssistantMod.SEALED_LETTER.get();
                if (!QuestItems.shortFor(level, v, f, token).isEmpty()) continue;
                String them = o.displayNameCap(), town = Villages.name(there.id());
                String rel = u.equals(f.life().partner()) ? "my sweetheart" : f.parentIds().contains(o.getUUID()) ? "my parent"
                    : kin ? "my child" : "my old friend";
                Quest q = newQuest("favour.letter", Kind.FAVOUR, v, f, (parcel ? "A parcel for " : "A letter to ") + them + " in " + town,
                    QuestTalk.voice(f,
                        them + ", " + rel + ", lives over in " + town + " now. I've " + (parcel ? "a parcel of bread put up for them" : "written them a letter")
                            + " — would you carry it over? It's " + where(v.centre(), there.centre()) + " of here.",
                        "There's " + (parcel ? "a parcel" : "a letter") + " wants taking to " + them + " in " + town + ". " + where(v.centre(), there.centre()) + ". Don't dawdle.",
                        "I — I wrote to " + them + ". " + rel.substring(0, 1).toUpperCase() + rel.substring(1) + ", in " + town + ". I'm too shy to go myself… would you take it?",
                        "I've " + (parcel ? "packed a parcel" : "written a letter") + " for " + them + " in " + town + " — " + rel + "! Would you carry it over? Oh, they'll be so pleased!"));
                q.coins = QuestRewards.afford(f, 5 + distance(v.centre(), there.centre()) / 60);
                q.payer = "purse";
                q.warmth = 10;
                q.days = 4;
                q.flags.put("to", o.getStringUUID());
                q.flags.put("toTown", there.id().toString());
                q.flags.put("token", parcel ? "parcel" : "sealed_letter");
                q.flags.put("memory", "carried my " + (parcel ? "parcel" : "letter") + " to " + them);
                q.steps.add(new Step(StepType.DELIVER, "letter", "Carry " + f.displayNameCap() + "'s " + (parcel ? "parcel" : "letter") + " to " + them
                    + " in " + town).who(o).at(there.centre(), 48).item("token:" + q.flag("token"), 1));
                return q;
            }
        }
        return null;
    }

    static final QuestRun.Script LETTER = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            Villages.Village v = Villages.get(q.village);
            boolean parcel = "parcel".equals(q.flag("token"));
            Item token = parcel ? McAssistantMod.PARCEL.get() : McAssistantMod.SEALED_LETTER.get();
            if (v == null) return null;
            ItemStack made = QuestItems.make(level, v, giver, token);
            if (made.isEmpty()) {
                q.flags.put("refused", "there's no " + QuestItems.shortFor(level, v, giver, token) + " in the stores to write it on");
                return null;
            }
            String to = q.steps.get(0).whoName, town = Villages.name(UUID.fromString(q.flag("toTown")));
            if (parcel) {
                // The bread out of the stores, paid for out of its own purse at the market's price (into the treasury).
                int cost = Math.max(1, (int) Math.round(Prices.each(Items.BREAD) * 4));
                if (!giver.spend(cost) || !TownWork.take(level, v, s -> s.is(Items.BREAD), 4)) {
                    q.flags.put("refused", "I can't run to the bread for it now");
                    Crafts.store(level, v, made);
                    return null;
                }
                Ledger.addCoins(q.village, cost);
                QuestItems.mark(made, "goods", "minecraft:bread*4");
            }
            QuestItems.stamp(made, q.id, giver.displayNameCap() + "'s " + (parcel ? "parcel" : "letter") + " for " + to,
                "For " + to + " of " + town + ".", parcel ? "Four loaves, wrapped and tied with string." : "Sealed, and not to be opened by anybody else.");
            QuestItems.give(p, made);
            return QuestTalk.voice(giver, "Here it is. Mind you give it to " + to + " and nobody else.", "Here. To " + to + ", nobody else.",
                "Here… please be careful with it.", "Here you go! Give " + to + " my love!") + " " + QuestTalk.capFirst(q.steps.get(0).text) + ".";
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            long day = QuestRun.day(level);
            String name = p.getName().getString();
            VillageFolkEntity giver = Civics.find(level, q.giver);
            if (s.key.equals("letter") && f != null) {
                f.life().feel(q.giver, q.giverName, 10);
                if (giver != null) giver.life().feel(f.getUUID(), f.displayNameCap(), 6);
                f.persona().feelFor(p.getUUID(), name, 6);
                f.persona().remember(day, "I had a " + ("parcel".equals(q.flag("token")) ? "parcel" : "letter") + " from " + q.giverName + ", brought by " + name, 4);
                // A parcel's bread is theirs now.
                if ("parcel".equals(q.flag("token"))) {
                    ItemStack parcel = QuestItems.fromFolk(f, QuestItems.matcher("token:parcel", q.id));
                    if (!parcel.isEmpty()) {
                        ItemStack bread = new ItemStack(Items.BREAD, 4);
                        if (!f.insertGiven(bread).isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), bread);
                    }
                }
                if (f.ownerId() != null) Ledger.relate(q.village, f.ownerId(), 1);
                // The answer, if its town has the paper and the wax for one.
                Villages.Village there = f.ownerId() == null ? null : Villages.get(f.ownerId());
                ItemStack reply = there == null ? ItemStack.EMPTY : QuestItems.make(level, there, f, McAssistantMod.SEALED_LETTER.get());
                if (!reply.isEmpty()) {
                    QuestItems.stamp(reply, q.id, f.displayNameCap() + "'s reply to " + q.giverName, "For " + q.giverName + " of " + Villages.name(q.village) + ".");
                    QuestItems.give(p, reply);
                    q.steps.add(new Step(StepType.DELIVER, "reply", "Carry " + f.displayNameCap() + "'s reply home to " + q.giverName)
                        .who(q.giver, q.giverName).item("token:sealed_letter", 1));
                    s.note = f.displayNameCap() + " read it and wrote back.";
                    return QuestTalk.voice(f, "From " + q.giverName + "? Oh, it's been too long. Wait — let me write back. Take this home for me?",
                        "From " + q.giverName + ". Hm. Hang on. Take this back with you.",
                        "From " + q.giverName + "! Oh… oh, that's lovely. W-would you take my answer back?",
                        "A letter from " + q.giverName + "! Hold on, hold on — I'm writing back this minute! Take it home for me?");
                }
                q.steps.add(new Step(StepType.TALK, "back", "Tell " + q.giverName + " it reached " + f.displayNameCap()).who(q.giver, q.giverName));
                s.note = f.displayNameCap() + " has it.";
                return QuestTalk.voice(f, "From " + q.giverName + "? Tell them I'm glad of it, and I'll write when I've the paper.",
                    "From " + q.giverName + ". Tell them thanks.", "Oh — thank you. Tell " + q.giverName + "… tell them I miss them.",
                    "From " + q.giverName + "! Tell them I said hello, and I miss them terribly!");
            }
            // Home: the reply read, or the word given.
            if (giver != null) {
                VillageFolkEntity to = Civics.find(level, q.flagId("to"));
                if (to != null) giver.life().feel(to.getUUID(), to.displayNameCap(), 6);
                giver.persona().remember(day, s.key.equals("reply") ? "I had " + s.whoName + " a reply from " + q.steps.get(0).whoName : "my letter reached "
                    + q.steps.get(0).whoName, 3);
            }
            q.flags.put("chronicle", name + " carried " + q.giverName + "'s " + ("parcel".equals(q.flag("token")) ? "parcel" : "letter") + " to "
                + q.steps.get(0).whoName + " in " + Villages.name(UUID.fromString(q.flag("toTown"))) + (s.key.equals("reply") ? ", and the answer home" : ""));
            return f == null ? "" : QuestTalk.voice(f, s.key.equals("reply") ? "A reply! Oh, thank you." : "It got there? Good. Thank you.",
                "Hm. Good.", "It got there? Oh, good. Thank you so much.", "They wrote back? Oh, I could hug you!");
        }
    };

    // ------------------------------------------------------------------ a cake for the wedding

    @Nullable
    static Quest wedding(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Gatherings.Wedding w = Gatherings.wedding(id);
        if (w == null || already(id, "favour.wedding", "couple", w.a().toString())) return null;
        VillageFolkEntity a = Civics.find(level, w.a()), b = Civics.find(level, w.b());
        if (a == null || b == null) return null;
        VillageFolkEntity giver = null;
        for (VillageFolkEntity f : grown(id)) {
            if (f == a || f == b || !free(f)) continue;
            boolean family = a.parentIds().contains(f.getUUID()) || b.parentIds().contains(f.getUUID());
            boolean friend = a.life().affinity(f.getUUID()) >= Social.FRIEND || b.life().affinity(f.getUUID()) >= Social.FRIEND;
            if (family || friend && giver == null) giver = f;
            if (family) break;
        }
        if (giver == null) return null;
        String couple = a.displayNameCap() + " and " + b.displayNameCap();
        Quest q = newQuest("favour.wedding", Kind.FAVOUR, v, giver, "A cake for " + couple,
            QuestTalk.voice(giver,
                couple + " are to be wed! I'd love them to have a proper cake for it. Would you bring one to " + a.displayNameCap() + "? A pumpkin pie would do at a pinch. Then come and tell me.",
                couple + " are getting wed. They'll want a cake. Take one to " + a.displayNameCap() + ", and tell me when it's done.",
                couple + "… they're getting married. I wanted to give them a cake, but I — would you take one to " + a.displayNameCap() + "?",
                couple + " are getting MARRIED! They simply must have a cake! Take one to " + a.displayNameCap() + " for me? Then come and tell me everything!"));
        q.coins = QuestRewards.afford(giver, 8);
        q.payer = "purse";
        q.warmth = 10;
        q.days = 2;
        q.flags.put("couple", w.a().toString());
        q.flags.put("b", w.b().toString());
        q.flags.put("give.cake", "folk");
        q.flags.put("memory", "took a cake to " + couple + " for me");
        q.steps.add(new Step(StepType.GIVE, "cake", "Take a cake (or a pumpkin pie) to " + a.displayNameCap() + " for the wedding").who(a).item("cake", 1));
        q.steps.add(new Step(StepType.TALK, "back", "Tell " + giver.displayNameCap() + " the cake is given").who(giver));
        return q;
    }

    static final QuestRun.Script WEDDING = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            String name = p.getName().getString();
            long day = QuestRun.day(level);
            if (s.key.equals("cake")) {
                VillageFolkEntity b = Civics.find(level, q.flagId("b"));
                for (VillageFolkEntity c : new VillageFolkEntity[]{ f, b }) {
                    if (c == null) continue;
                    c.persona().feelFor(p.getUUID(), name, 8);
                    c.persona().remember(day, name + " brought us a cake for our wedding, from " + q.giverName, 5);
                    q.flags.put("thanks." + c.getUUID(), "Thank you for the cake, {who}! It was the talk of the wedding.");
                }
                q.flags.put("chronicle", name + " brought a cake to " + s.whoName + "'s wedding, from " + q.giverName);
                q.flags.put("gossip", "Did you see the cake at " + s.whoName + "'s wedding? {who} brought it!");
                return f == null ? "" : QuestTalk.voice(f, "A cake? From " + q.giverName + "? Oh, how lovely!", "A cake. Well. That's… kind.",
                    "Oh! For us? Oh, I'm going to cry.", "A CAKE! Oh, it's beautiful! Thank you, thank you!");
            }
            return f == null ? "" : QuestTalk.voice(f, "They liked it? Oh, I'm so glad.", "Good. Well done.", "Oh, good. Thank you.",
                "They loved it? Of course they did! Thank you!");
        }
    };

    // ------------------------------------------------------------------ a lost pet

    /** A household's pet gone off far from home: what a family would ask somebody to find. */
    public record LostPet(UUID pet, String name, String kind, UUID owner, BlockPos home) {}

    /**
     * Where lost pets come from. The households' own (Families) by default; the pets of a later day can put
     * their own here, and the favour asks after theirs instead.
     */
    public interface LostPets {
        List<LostPet> lost(ServerLevel level, Villages.Village v);
    }

    private static volatile LostPets lostPets = QuestMaker::familyPets;

    /** [pets] Let the pets say which are lost. */
    public static void lostPets(LostPets source) {
        lostPets = source;
    }

    static List<LostPet> familyPets(ServerLevel level, Villages.Village v) {
        List<LostPet> out = new ArrayList<>();
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            Families.Pet pet = Families.pet(v.id(), h.anchor.asLong());
            if (pet == null || h.members.isEmpty()) continue;
            Entity e = level.getEntity(pet.id());
            if (!(e instanceof LivingEntity le) || !le.isAlive() || e.blockPosition().distSqr(h.anchor) <= 40 * 40) continue;
            UUID owner = h.members.contains(pet.keeper()) ? pet.keeper() : h.members.get(0);
            out.add(new LostPet(pet.id(), pet.name(), pet.kind(), owner, h.anchor));
        }
        return out;
    }

    @Nullable
    static Quest pet(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (LostPet lp : lostPets.lost(level, v)) {
            VillageFolkEntity owner = Civics.find(level, lp.owner());
            if (!free(owner) || already(id, "favour.pet", "pet", lp.pet().toString())) continue;
            Entity e = level.getEntity(lp.pet());
            BlockPos seen = e == null ? lp.home() : e.blockPosition();
            String kind = lp.kind().toLowerCase(java.util.Locale.ROOT).contains("cat") ? "cat" : "dog";
            Quest q = newQuest("favour.pet", Kind.FAVOUR, v, owner, lp.name() + " has wandered off",
                QuestTalk.voice(owner,
                    lp.name() + " hasn't come home — our " + kind + ". Somebody saw " + lp.name() + " off " + QuestRun.way(seen.getX() - lp.home().getX(), seen.getZ() - lp.home().getZ())
                        + " of the town. Would you find " + lp.name() + " and bring them home? A lead would help.",
                    "That " + kind + " of ours has gone off again. " + lp.name() + ". Find it and bring it home, would you? Take a lead.",
                    "Our " + kind + ", " + lp.name() + "… gone off, and I'm worried sick. Would you look? A lead might help…",
                    lp.name() + "'s gone walkabout! Our " + kind + "! Would you find " + lp.name() + " for us? Bring a lead!"));
            q.coins = QuestRewards.afford(owner, 6);
            q.payer = "purse";
            q.warmth = 12;
            q.flags.put("pet", lp.pet().toString());
            q.flags.put("home", Long.toString(lp.home().asLong()));
            q.flags.put("memory", "found " + lp.name() + " and brought them home");
            q.steps.add(new Step(StepType.FIND, "find", "Find " + lp.name() + ", the " + kind + ", last seen about here").who(lp.pet(), lp.name())
                .at(seen, 4));
            q.steps.add(new Step(StepType.WAIT, "home", "Bring " + lp.name() + " home (a lead will do it)").at(lp.home(), 10));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + owner.displayNameCap() + " that " + lp.name() + " is home").who(owner));
            Weave.petQuest(q, lp);                                         // [weave] Pets' lost pet: held out there, found by following you
            return q;
        }
        return null;
    }

    static final QuestRun.Script PET = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            if (Weave.petsOwn(q)) return;                                  // [weave] home when Pets says so (Pets.onFound)
            Step s = q.current();
            if (s == null || !s.key.equals("home") || s.at == null) return;
            Entity e = level.getEntity(UUID.fromString(q.flag("pet")));
            if (e != null && e.blockPosition().distSqr(s.at) <= (double) s.radius * s.radius) QuestRun.reached(level, q, s, null, p);
        }

        @Override
        public void ended(ServerLevel level, Quest q) {
            Weave.petQuestEnded(q);                                        // [weave] given up or never taken: it finds its own way home
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            Weave.petStep(level, q, s, p);                                 // [weave] found: it follows the finder home
            String name = p.getName().getString();
            if (s.key.equals("find")) s.note = "Found. Now home with it.";
            if (s.key.equals("back")) {
                q.flags.put("chronicle", name + " found " + q.giverName + "'s pet, lost far from home, and brought it back");
                q.flags.put("gossip", "{who} found " + q.giverName + "'s pet when it went missing. The whole house was in tears.");
                return f == null ? "" : QuestTalk.voice(f, "Home! Oh, thank you. We were so worried.", "Hmph. Daft animal. Thank you.",
                    "Oh — oh, you found them. Thank you.", "Home safe! You wonderful, wonderful person!");
            }
            return "";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            Entity e = level.getEntity(UUID.fromString(q.flag("pet")));
            return (e == null || e.isAlive()) && Weave.petStillLost(q);   // [weave] and still lost
        }
    };

    // ================================================================== for the town

    // ------------------------------------------------------------------ the shortfall

    @Nullable
    static Quest shortfall(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder)) return null;
        for (Villages.Need n : Villages.needs(level, id)) {
            String item = Errands.supplyItem(n.task());
            if (item == null || already(id, "town.shortfall", "item", item)) continue;
            boolean posted = false;
            for (Quests.Posting p : Quests.postings(id)) posted |= "bring".equals(p.kind) && item.equals(p.item);
            if (posted) continue;
            int count = Math.max(16, Math.min(64, n.amount()));
            Market.Good g = Market.goodFor(Quests.sample(item));
            double each = g == null ? 0.5 : g.value();
            int worth = (int) Math.max(6, Math.min(80, Math.round(each * count * 1.3)));
            String purpose = Quests.purpose(n.task(), id);
            String what = QuestItems.words(item, count);
            Quest q = newQuest("town.shortfall", Kind.TOWN, v, elder, QuestTalk.capFirst(item.replace('_', ' ')) + " for " + purpose,
                QuestTalk.voice(elder,
                    "The town's short of " + n.what() + " for " + purpose + ", and it holds everything up. Bring me " + what + " and the treasury will see you right.",
                    "We're short of " + n.what() + ". " + what + ", for " + purpose + ". The treasury pays.",
                    "We… the town needs " + what + " for " + purpose + ". If you could…",
                    "Here's a job for you! The town needs " + what + " for " + purpose + " — bring it and the treasury pays!"));
            q.coins = QuestRewards.affordTreasury(id, worth);
            q.payer = "treasury";
            q.warmth = 8;
            q.days = 4;
            q.flags.put("item", item);
            if (Market.stock(level, id, s -> s.is(Items.BREAD)) >= 48) q.goods = "minecraft:bread*8";
            q.flags.put("chronicle", "{who} brought the town " + what + " for " + purpose);
            q.steps.add(new Step(StepType.GIVE, "bring", "Bring " + elder.displayNameCap() + " " + what + " for " + purpose).who(elder).item(item, count));
            return q;
        }
        return null;
    }

    static final QuestRun.Script SHORTFALL = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            return f == null ? "" : "Into the stores it goes. The whole town's the better for it.";
        }
    };

    // ------------------------------------------------------------------ a den

    @Nullable
    static Quest den(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder) || already(id, "town.den", "den", "1")) return null;
        List<Monster> about = level.getEntitiesOfClass(Monster.class, new AABB(v.centre()).inflate(96, 48, 96), LivingEntity::isAlive);
        Monster best = null;
        int most = 2;
        for (Monster m : about) {
            int n = 0;
            for (Monster o : about) if (o.distanceToSqr(m) <= 12 * 12) n++;
            if (n > most) { most = n; best = m; }
        }
        if (best == null) return null;
        BlockPos at = best.blockPosition();
        String kind = Quests.kind(best);
        int count = Math.min(6, most);
        String place = "the " + Quests.compass(v.centre(), at) + "edge of town";
        Quest q = newQuest("town.den", Kind.TOWN, v, elder, "Clear the " + kind + " from " + place,
            QuestTalk.voice(elder,
                "There's a nest of " + kind + " gathered out by " + place.replace("the ", "") + " — " + count + " at least. The watch can't be everywhere. Clear them, and the treasury pays.",
                count + " " + kind + ", out by " + place.replace("the ", "") + ". Clear them out. The treasury pays.",
                "There are… " + kind + ". A lot of them, out by " + place.replace("the ", "") + ". Could you…?",
                "Fancy a scrap? " + count + " " + kind + " out by " + place.replace("the ", "") + "! Clear them and the treasury pays!"));
        q.coins = QuestRewards.affordTreasury(id, 4 * count);
        q.payer = "treasury";
        q.warmth = 8;
        q.days = 2;
        q.flags.put("den", "1");
        q.flags.put("chronicle", "{who} cleared a den of " + kind + " from " + place);
        Step kill = new Step(StepType.KILL, "clear", "Kill " + count + " " + kind + " out by " + place.replace("the ", "")).at(at, 24).item("", count);
        kill.mob = kind;
        q.steps.add(kill);
        q.steps.add(new Step(StepType.TALK, "back", "Tell " + elder.displayNameCap() + " it's done").who(elder));
        return q;
    }

    static final QuestRun.Script DEN = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            if (s.key.equals("clear")) s.note = "Cleared.";
            return f == null ? "" : "Cleared? The whole town will sleep the sounder for it.";
        }
    };

    // ------------------------------------------------------------------ the elder's letter

    @Nullable
    static Quest envoy(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder)) return null;
        if (!QuestItems.shortFor(level, v, elder, McAssistantMod.SEALED_LETTER.get()).isEmpty()) return null;
        for (Villages.Village o : Diplomacy.neighboursOf(id)) {
            int r = Ledger.relation(id, o.id());
            UUID theirs = Villages.elder(o.id());
            if (theirs == null || Wars.atWar(id, o.id()) || r <= -45 || r >= Diplomacy.ALLIANCE || already(id, "town.envoy", "to", o.id().toString())) continue;
            String town = Villages.name(o.id()), them = Villages.elderName(o.id());
            Quest q = newQuest("town.envoy", Kind.TOWN, v, elder, "The elder's letter to " + town,
                QuestTalk.voice(elder,
                    "I've written to " + them + ", the elder of " + town + ". We've been " + Diplomacy.terms(r).words + " too long. Will you carry it? It's " + where(v.centre(), o.centre()) + ".",
                    "A letter for " + them + " of " + town + ". It needs carrying. " + where(v.centre(), o.centre()) + ".",
                    "I wrote to the elder of " + town + "… would you take it? It's " + where(v.centre(), o.centre()) + ".",
                    "A letter for " + them + " of " + town + "! Let's be friends with them! Carry it over? It's " + where(v.centre(), o.centre()) + "!"));
            q.coins = QuestRewards.affordTreasury(id, 10 + distance(v.centre(), o.centre()) / 40);
            q.payer = "treasury";
            q.warmth = 8;
            q.days = 4;
            q.flags.put("to", o.id().toString());
            q.flags.put("chronicle", "{who} carried the elder's letter to " + town);
            q.steps.add(new Step(StepType.DELIVER, "letter", "Carry the letter to " + them + ", the elder of " + town).who(theirs, them)
                .at(o.centre(), 48).item("token:sealed_letter", 1));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + elder.displayNameCap() + " the letter was delivered").who(elder));
            return q;
        }
        return null;
    }

    static final QuestRun.Script ENVOY = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            Villages.Village v = Villages.get(q.village);
            ItemStack made = v == null ? ItemStack.EMPTY : QuestItems.make(level, v, giver, McAssistantMod.SEALED_LETTER.get());
            if (made.isEmpty()) {
                q.flags.put("refused", "we've nothing to write it on");
                return null;
            }
            String town = Villages.name(UUID.fromString(q.flag("to")));
            QuestItems.stamp(made, q.id, "The elder's letter to " + town, "Sealed with the town's wax.", "For the elder of " + town + ".");
            QuestItems.give(p, made);
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            String name = p.getName().getString();
            long day = QuestRun.day(level);
            if (s.key.equals("letter")) {
                UUID them = UUID.fromString(q.flag("to"));
                Ledger.relate(q.village, them, 5);
                Bonds.remember(them, q.village, day, 2, name + " brought us a letter from " + Villages.name(q.village) + "'s elder");
                if (f != null) f.persona().feelFor(p.getUUID(), name, 6);
                q.flags.put("chronicle", name + " carried the elder's letter to " + Villages.name(them) + ", and the two towns are the warmer for it");
                s.note = "Read, and well taken.";
                return f == null ? "" : "A letter from " + Villages.name(q.village) + "? Well, well. Tell them it was well taken.";
            }
            return f == null ? "" : "Well taken, was it? Good. That's worth the walk.";
        }
    };

    // ================================================================== war and peace

    /** The town this one is at war with, or in a feud with: the first of them. */
    @Nullable
    static UUID rival(UUID village) {
        List<UUID> e = Wars.enemies(village);
        if (!e.isEmpty()) return e.get(0);
        for (Villages.Village o : Diplomacy.neighboursOf(village)) if (Diplomacy.terms(village, o.id()) == Diplomacy.Terms.FEUD) return o.id();
        return null;
    }

    // ------------------------------------------------------------------ scout the rival

    @Nullable
    static Quest scout(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        UUID them = rival(id);
        Villages.Village there = them == null ? null : Villages.get(them);
        VillageFolkEntity elder = elder(level, id);
        if (there == null || !free(elder) || already(id, "war.scout", "them", them.toString())) return null;
        Intel.Report last = Intel.latest(id, them);
        if (last != null && Intel.age(last, day) < 2) return null;
        if (!QuestItems.shortFor(level, v, elder, McAssistantMod.SPY_REPORT.get()).isEmpty()) return null;
        String town = Villages.name(them);
        Quest q = newQuest("war.scout", Kind.WAR, v, elder, "Scout " + town,
            QuestTalk.voice(elder,
                "We know too little of " + town + ". " + (last == null ? "Nobody's been." : "Our last word of them is " + Intel.ageWords(last, day) + ".")
                    + " Go and look — the watch, the walls, the stores — and write it down. It's " + where(v.centre(), there.centre()) + ".",
                "I want eyes on " + town + ". Count their watch and their walls and bring it back written.",
                "We… we need to know about " + town + ". Their guards, their walls. Would you go and look?",
                "Fancy a bit of spying? Go and count " + town + "'s guards and walls, and bring me the report!"));
        q.coins = QuestRewards.affordTreasury(id, 15 + distance(v.centre(), there.centre()) / 30);
        q.payer = "treasury";
        q.warmth = 8;
        q.days = 4;
        q.flags.put("them", them.toString());
        q.steps.add(new Step(StepType.GO, "look", "Go to " + town + " and take a good look at its watch and walls").at(there.centre(), 40));
        q.steps.add(new Step(StepType.DELIVER, "report", "Bring your report home to " + elder.displayNameCap()).who(elder).item("token:spy_report", 1));
        q.flags.put("deliver.report", "gone");
        return q;
    }

    static final QuestRun.Script SCOUT = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            Villages.Village v = Villages.get(q.village);
            ItemStack made = v == null ? ItemStack.EMPTY : QuestItems.make(level, v, giver, McAssistantMod.SPY_REPORT.get());
            if (made.isEmpty()) {
                q.flags.put("refused", "there's no paper and ink in the stores for your report");
                return null;
            }
            String town = Villages.name(UUID.fromString(q.flag("them")));
            QuestItems.stamp(made, q.id, "A spy's report on " + town + " (blank)", "To be filled in at " + town + ".");
            QuestItems.give(p, made);
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            UUID them = UUID.fromString(q.flag("them"));
            long day = QuestRun.day(level);
            String town = Villages.name(them), name = p.getName().getString();
            if (s.key.equals("look")) {
                // The report filled in with what is really there.
                Intel.Report r = Intel.exact(level, them, day);
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    ItemStack st = p.getInventory().getItem(i);
                    if (!QuestItems.matcher("token:spy_report", q.id).test(st)) continue;
                    QuestItems.mark(st, "report", r.encode());
                    QuestItems.stamp(st, q.id, "A spy's report on " + town, "Filled in at " + town + ", day " + (day + 1) + ":", Intel.summary(r));
                }
                s.note = "Seen: " + Intel.summary(r) + ".";
                return "";
            }
            // Filed as the town's latest word on them.
            Intel.Report r = Intel.exact(level, them, QuestRun.day(level));
            Intel.file(q.village, new Intel.Report(them, day, r.folk(), r.guards(), r.armoured(), r.archers(), r.walls(), r.gates(), r.foodDays(),
                "from " + name));
            q.flags.put("chronicle", name + " scouted " + town + " for the town: " + Intel.summary(r));
            q.flags.put("gossip", "{who} went right into " + town + " and counted their guards. Brave, or daft.");
            return f == null ? "" : "So that's " + town + ": " + Intel.summary(r) + ". Good work. That goes in the war books.";
        }
    };

    // ------------------------------------------------------------------ the peace terms

    @Nullable
    static Quest peace(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder)) return null;
        for (UUID them : Wars.enemies(id)) {
            if (day - Wars.since(id, them) < 3 || already(id, "war.peace", "them", them.toString())) continue;
            UUID theirs = Villages.elder(them);
            Villages.Village there = Villages.get(them);
            if (theirs == null || there == null) continue;
            if (!QuestItems.shortFor(level, v, elder, McAssistantMod.PEACE_TERMS.get()).isEmpty()) return null;
            String town = Villages.name(them);
            Quest q = newQuest("war.peace", Kind.WAR, v, elder, "Peace terms for " + town,
                QuestTalk.voice(elder,
                    "This war has gone on long enough. I've written out our terms. Carry them to " + Villages.elderName(them) + " of " + town + " — they might hear them from you, where they'd not from one of ours.",
                    "Enough of this war. Take our terms to " + town + ". If they'll sign, it's done.",
                    "I… I want this war over. Would you carry our terms to " + town + "? Please?",
                    "Let's end this war! Here are our terms — take them to " + town + ", and let's have peace!"));
            q.coins = QuestRewards.affordTreasury(id, 25);
            q.payer = "treasury";
            q.warmth = 12;
            q.days = 4;
            q.flags.put("them", them.toString());
            q.flags.put("deliver.terms", "gone");
            q.steps.add(new Step(StepType.DELIVER, "terms", "Carry the peace terms to " + Villages.elderName(them) + ", the elder of " + town)
                .who(theirs, Villages.elderName(them)).at(there.centre(), 48).item("token:peace_terms", 1));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + elder.displayNameCap() + " what they said").who(elder));
            return q;
        }
        return null;
    }

    static final QuestRun.Script PEACE = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            Villages.Village v = Villages.get(q.village);
            UUID them = UUID.fromString(q.flag("them"));
            ItemStack made = v == null ? ItemStack.EMPTY : QuestItems.make(level, v, giver, McAssistantMod.PEACE_TERMS.get());
            if (made.isEmpty()) {
                q.flags.put("refused", "there's no paper and seal in the stores for the terms");
                return null;
            }
            WarAndPeace.Terms t = WarAndPeace.terms(level, q.village, them);
            QuestItems.stamp(made, q.id, "Peace terms for " + Villages.name(them), "From " + Villages.name(q.village) + ", sealed.", t.words());
            QuestItems.give(p, made);
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            UUID them = UUID.fromString(q.flag("them"));
            String town = Villages.name(them), name = p.getName().getString();
            long day = QuestRun.day(level);
            if (s.key.equals("terms")) {
                if (!Wars.atWar(q.village, them)) {
                    q.flags.put("peace", "already");
                    s.note = "They were at peace already.";
                    return "Peace? We've made it already. Take that home.";
                }
                if (WarAndPeace.acceptPeace(level, them, q.village, day)) {
                    String text = WarAndPeace.makePeace(level, q.village, them, day, WarAndPeace.terms(level, q.village, them), "on " + name + "'s word", null);
                    q.flags.put("peace", "made");
                    s.note = "Peace was made: " + text + ".";
                    q.flags.put("chronicle", name + " carried the peace terms to " + town + ", and peace was made on their word");
                    q.flags.put("gossip", "It was {who} carried the terms to " + town + "! The war's over, thanks to them.");
                    QuestBook.title(p.getUUID(), q.village, "Peacemaker");
                    return "Then let there be peace. We'll sign. Tell your elder.";
                }
                q.flags.put("peace", "refused");
                q.coins = q.coins / 3;
                s.note = "They would not hear them yet.";
                q.flags.put("chronicle", name + " carried the peace terms to " + town + "; they would not hear them yet");
                return "Not yet. Tell your elder we'll talk when we're ready.";
            }
            return f == null ? "" : "made".equals(q.flag("peace")) ? "Peace! You've done what none of us could." :
                "Not yet, they said? Then we wait. You did what you could, and you'll be paid for the walk.";
        }
    };

    // ------------------------------------------------------------------ free the captured spy

    @Nullable
    static Quest spy(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder)) return null;
        for (Spies.Captive c : Spies.ofOurs(id)) {
            Villages.Village holder = Villages.get(c.holder());
            if (holder == null || already(id, "war.spy", "captive", c.who().toString())) continue;
            String town = Villages.name(c.holder());
            Quest q = newQuest("war.spy", Kind.WAR, v, elder, "Free " + c.name(),
                QuestTalk.voice(elder,
                    c.name() + " was taken spying in " + town + ", and they hold " + c.name() + " still. One of ours can't go — they'd be taken too. You could. Bring " + c.name() + " home.",
                    town + " has " + c.name() + ". Get " + c.name() + " out.",
                    c.name() + "… they're holding " + c.name() + " in " + town + ". Could you… bring them home?",
                    "A rescue! " + town + " has " + c.name() + " locked up — go and set them free!"));
            q.coins = QuestRewards.affordTreasury(id, 20);
            q.payer = "treasury";
            q.warmth = 12;
            q.days = 4;
            q.flags.put("captive", c.who().toString());
            q.flags.put("holder", c.holder().toString());
            q.steps.add(new Step(StepType.GO, "go", "Go to " + town + ", where " + c.name() + " is held").at(holder.centre(), 48));
            q.steps.add(new Step(StepType.FIND, "find", "Find " + c.name() + " in " + town).who(c.who(), c.name()).item("", 1));
            q.steps.get(1).radius = 4;
            q.steps.add(new Step(StepType.TALK, "free", "Set " + c.name() + " free").who(c.who(), c.name()));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + elder.displayNameCap() + " " + c.name() + " is free").who(elder));
            return q;
        }
        return null;
    }

    static final QuestRun.Script SPY = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            String name = p.getName().getString();
            long day = QuestRun.day(level);
            UUID holder = UUID.fromString(q.flag("holder"));
            if (s.key.equals("free")) {
                boolean let = Spies.release(level, UUID.fromString(q.flag("captive")), "by " + name);
                // The town that held it saw who did it: it will not forget.
                for (AssistantEntity a : Villages.folkOf(holder)) {
                    if (a instanceof VillageFolkEntity g && !g.isShowcase()) g.persona().feelFor(p.getUUID(), name, g.distanceToSqr(p) < 32 * 32 ? -10 : -3);
                }
                Standing.stir(holder, p.getUUID());
                Villages.tell(holder, day, name + " set our captive " + s.whoName + " free");
                s.note = let ? "Free, and on the way home." : "Gone already.";
                return let ? "Free? You'd do that for me? Then I'm off home — and I'll not forget it." : "";
            }
            if (s.key.equals("back")) {
                q.flags.put("chronicle", name + " set " + q.steps.get(1).whoName + " free from " + Villages.name(holder));
                q.flags.put("gossip", "{who} walked into " + Villages.name(holder) + " and walked out with " + q.steps.get(1).whoName + "!");
                return f == null ? "" : "Free! The town owes you for that.";
            }
            return "";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            UUID who = UUID.fromString(q.flag("captive"));
            for (Spies.Captive c : Spies.ofOurs(q.village)) if (c.who().equals(who)) return true;
            return false;
        }
    };

    // ------------------------------------------------------------------ supply the besieged

    @Nullable
    static Quest supply(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Wars.enemies(id).isEmpty() || already(id, "war.supply", "war", "1")) return null;
        int folk = Math.max(1, Villages.headcount(id));
        if (Market.stock(level, id, Errands.matcher("food")) >= folk * 4) return null;
        VillageFolkEntity elder = elder(level, id);
        if (!free(elder)) return null;
        Quest q = newQuest("war.supply", Kind.WAR, v, elder, "Food for the besieged",
            QuestTalk.voice(elder,
                "The war's kept our farmers on the walls and the larder's near bare. Bring us food — thirty-two of anything — and the treasury will pay what it can.",
                "We're hungry. The war. Thirty-two food, and the treasury pays.",
                "We're… the larder's nearly empty, with the war. Could you bring us food?",
                "Help! The war's emptied our larder! Bring thirty-two food and save the day!"));
        q.coins = QuestRewards.affordTreasury(id, 16);
        q.payer = "treasury";
        q.warmth = 10;
        q.days = 3;
        q.flags.put("war", "1");
        q.flags.put("chronicle", "{who} brought food to the town while it was at war");
        q.steps.add(new Step(StepType.GIVE, "food", "Bring " + elder.displayNameCap() + " thirty-two food").who(elder).item("food", 32));
        return q;
    }

    static final QuestRun.Script SUPPLY = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            return "Food! Into the larder with it. You've no idea what this means.";
        }
    };

    // ================================================================== below ground

    private static String oreItem(String ore) {
        return switch (ore) {
            case "diamond" -> "minecraft:diamond";
            case "emerald" -> "minecraft:emerald";
            case "gold" -> "minecraft:raw_gold";
            case "lapis" -> "minecraft:lapis_lazuli";
            case "redstone" -> "minecraft:redstone";
            default -> null;
        };
    }

    /** Who speaks for the caves: a cave dweller, else a miner, else the elder. */
    @Nullable
    static VillageFolkEntity caver(ServerLevel level, UUID village) {
        for (VillageFolkEntity f : CaveDwellers.dwellers(village)) if (free(f)) return f;
        for (VillageFolkEntity f : grown(village)) if (f.stationTask() == AssistantEntity.StationTask.MINE && free(f)) return f;
        VillageFolkEntity e = elder(level, village);
        return free(e) ? e : null;
    }

    // ------------------------------------------------------------------ recover a find

    @Nullable
    static Quest vein(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (CaveDwellers.Find x : CaveDwellers.report(id)) {
            if (x.kind() != CaveDwellers.Kind.VEIN) continue;
            String item = oreItem(x.label());
            int left = x.a() - x.b();
            if (item == null || left < 3 || distance(v.centre(), x.at()) > 160) continue;
            if (already(id, "caves.vein", "at", Long.toString(x.at().asLong()))) continue;
            VillageFolkEntity giver = caver(level, id);
            if (giver == null) return null;
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item));
            int count = switch (x.label()) {
                case "diamond", "emerald" -> Math.min(4, left);
                case "gold" -> Math.min(8, left);
                default -> Math.min(24, left * 4);
            };
            int worth = (int) Math.max(6, Math.min(40, Math.round(Prices.each(it) * count * 0.6)));
            int below = Math.max(0, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x.at().getX(), x.at().getZ()) - x.at().getY());
            Quest q = newQuest("caves.vein", Kind.CAVES, v, giver, "The " + x.label() + " below",
                QuestTalk.voice(giver,
                    x.by() + " saw " + x.label() + " down there — a vein of " + x.a() + ", " + (below > 0 ? below + " blocks under the ground" : "below") + ", " + where(v.centre(), x.at())
                        + " of the town — and couldn't take it. Go down and bring up " + QuestItems.words(item, count) + ", and the treasury pays its share.",
                    "There's " + x.label() + " under the ground " + where(v.centre(), x.at()) + ". " + QuestItems.words(item, count) + ". Fetch it.",
                    "We… we found " + x.label() + ", deep down, " + where(v.centre(), x.at()) + ". Could you bring some up?",
                    "There's " + x.label() + " down there! A whole vein! Bring up " + QuestItems.words(item, count) + " and the treasury pays!"));
            q.coins = QuestRewards.affordTreasury(id, worth);
            q.payer = "treasury";
            q.warmth = 8;
            q.days = 4;
            q.flags.put("at", Long.toString(x.at().asLong()));
            q.flags.put("chronicle", "{who} brought up " + QuestItems.words(item, count) + " from the cave team's vein");
            q.steps.add(new Step(StepType.GO, "vein", "Go down to the " + x.label() + " vein" + (below > 0 ? ", " + below + " blocks below the ground" : "")).at(x.at(), 8));
            q.steps.add(new Step(StepType.GIVE, "bring", "Bring " + giver.displayNameCap() + " " + QuestItems.words(item, count)).who(giver).item(item, count));
            return q;
        }
        return null;
    }

    static final QuestRun.Script VEIN = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            if (s.key.equals("vein")) {
                s.note = "Found it.";
                return "";
            }
            return "That's the stuff. Into the stores.";
        }
    };

    // ------------------------------------------------------------------ seal a spawner

    @Nullable
    static Quest seal(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (CaveDwellers.Find x : CaveDwellers.report(id)) {
            if (x.kind() != CaveDwellers.Kind.SPAWNER && x.kind() != CaveDwellers.Kind.DUNGEON) continue;
            if (distance(v.centre(), x.at()) > 140 || already(id, "caves.seal", "at", Long.toString(x.at().asLong()))) continue;
            if (QuestStories.claimed(x.at())) continue;
            if (level.isLoaded(x.at()) && !level.getBlockState(x.at()).is(Blocks.SPAWNER)) continue;
            VillageFolkEntity giver = caver(level, id);
            if (giver == null) return null;
            Quest q = newQuest("caves.seal", Kind.CAVES, v, giver, "Seal " + x.label(),
                QuestTalk.voice(giver,
                    "There's " + x.label() + " under the ground " + where(v.centre(), x.at()) + " of the town. It'll keep breeding monsters till somebody breaks it. Will you?",
                    x.label().substring(0, 1).toUpperCase() + x.label().substring(1) + ", " + where(v.centre(), x.at()) + ". Break it.",
                    "There's a — a spawner, down there, " + where(v.centre(), x.at()) + ". It frightens me. Could you break it?",
                    "Monster-hunting! There's " + x.label() + " " + where(v.centre(), x.at()) + " — smash it!"));
            q.coins = QuestRewards.affordTreasury(id, 20);
            q.payer = "treasury";
            q.warmth = 10;
            q.days = 4;
            q.flags.put("at", Long.toString(x.at().asLong()));
            q.flags.put("chronicle", "{who} broke " + x.label() + " under the ground " + where(v.centre(), x.at()) + " of the town");
            q.steps.add(new Step(StepType.GO, "down", "Go down to " + x.label()).at(x.at(), 8));
            q.steps.add(new Step(StepType.SEAL, "seal", "Break the spawner").at(x.at(), 6));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + giver.displayNameCap() + " it's done").who(giver));
            return q;
        }
        return null;
    }

    static final QuestRun.Script SEAL = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            q.flags.put("chronicle", q.flag("chronicle").replace("{who}", p.getName().getString()));
            if (s.key.equals("seal")) s.note = "Broken.";
            return f == null ? "" : "Broken? Good riddance. That's one less worry below.";
        }

        @Override
        public boolean stands(ServerLevel level, Quest q) {
            BlockPos at = BlockPos.of(Long.parseLong(q.flag("at")));
            return !level.isLoaded(at) || level.getBlockState(at).is(Blocks.SPAWNER);
        }
    };

    // ------------------------------------------------------------------ find the lost

    @Nullable
    static Quest missing(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity lost) || !SearchParties.searchedFor(lost.getUUID()) || QuestStories.cast(lost.getUUID())) continue;
            if (already(id, "caves.missing", "lost", lost.getStringUUID())) continue;
            VillageFolkEntity giver = kin(level, lost);
            if (giver == null) giver = elder(level, id);
            if (!free(giver)) continue;
            boolean below = MineStairs.underground(level, lost.blockPosition());
            String name = lost.displayNameCap();
            Quest q = newQuest("caves.missing", Kind.CAVES, v, giver, "Find " + name,
                QuestTalk.voice(giver,
                    name + " hasn't been seen since yesterday. The search party's out, but there's a lot of ground" + (below ? ", and they think " + name + " is down in the caves" : "")
                        + ". Will you help look? Bring " + name + " home.",
                    name + "'s gone missing. Find " + name + ". Bring them home.",
                    name + "… nobody's seen " + name + " since yesterday. Please — will you look?",
                    name + "'s lost! Everyone's out looking — will you help? Bring " + name + " home!"));
            q.coins = QuestRewards.afford(giver, 10);
            q.payer = "purse";
            q.warmth = 15;
            q.days = 3;
            q.flags.put("lost", lost.getStringUUID());
            q.flags.put("chronicle", "{who} found " + name + ", lost " + (below ? "in the caves" : "out past the town") + ", and brought them home");
            q.steps.add(new Step(StepType.FIND, "find", "Find " + name + (below ? ", somewhere in the caves" : "")).who(lost).at(lost.blockPosition(), 4));
            q.steps.add(new Step(StepType.WAIT, "home", "Bring " + name + " home (they'll follow you)").at(v.centre(), Math.max(24, Villages.townReach(id))));
            q.steps.add(new Step(StepType.TALK, "back", "Tell " + giver.displayNameCap() + " " + name + " is home").who(giver));
            return q;
        }
        return null;
    }

    static final QuestRun.Script MISSING = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return QuestTalk.taken(giver, q);
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            Step s = q.current();
            if (s == null || !s.key.equals("home") || s.at == null) return;
            VillageFolkEntity lost = Civics.find(level, q.flagId("lost"));
            if (lost == null) return;
            double dx = lost.getX() - s.at.getX(), dz = lost.getZ() - s.at.getZ();
            if (dx * dx + dz * dz <= (double) s.radius * s.radius && !MineStairs.underground(level, lost.blockPosition())) {
                q.flags.remove("cast.lost");
                QuestRun.reached(level, q, s, null, p);
            }
        }

        /** Found, it keeps at the finder's heels, by night as by day, till it is home. */
        @Override
        public boolean hold(VillageFolkEntity f, ServerLevel level, Quest q) {
            Step s = q.current();
            return s != null && s.key.equals("home") && QuestStories.follow(f, level, q.player);
        }

        @Override
        public String reached(ServerLevel level, Quest q, Step s, @Nullable VillageFolkEntity f, Player p) {
            String name = p.getName().getString();
            if (s.key.equals("find") && f != null) {
                q.flags.put("cast.lost", f.getStringUUID());
                f.persona().feelFor(p.getUUID(), name, 15);
                f.persona().remember(QuestRun.day(level), name + " found me when I was lost", 7);
                q.flags.put("thanks." + f.getUUID(), "You found me, {who}. I'll never forget it.");
                s.note = "Found, and following you.";
                return "Oh, thank goodness! I couldn't find the way. Lead on — I'll stay close.";
            }
            if (s.key.equals("home")) s.note = "Home.";
            if (s.key.equals("back")) {
                q.flags.put("chronicle", q.flag("chronicle").replace("{who}", name));
                return f == null ? "" : "Home! Oh, you've no idea. Thank you.";
            }
            return "";
        }
    };

    // ================================================================== the quest board's own

    /** A posting taken off the board, in the journal: it is done at the board, as ever. */
    static final QuestRun.Script BOARD = new QuestRun.Script() {
        @Override
        public String accepted(ServerLevel level, Quest q, VillageFolkEntity giver, Player p) {
            return "";
        }

        @Override
        public void tick(ServerLevel level, Quest q, Player p) {
            Step s = q.current();
            if (s == null) return;
            int id = QuestRewards.num(q.flag("posting"));
            for (Quests.Posting post : Quests.postings(q.village)) {
                if (post.id != id) continue;
                if (post.done != s.progress) {
                    s.progress = post.done;
                    QuestBook.changed();
                }
                return;
            }
            // Gone from the board: claimed (QuestRun.boardDone finished it), or taken down.
            if (q.state == QuestBook.State.ACTIVE) QuestRun.fail(level, q, p, "the posting came down");
        }
    };
}
