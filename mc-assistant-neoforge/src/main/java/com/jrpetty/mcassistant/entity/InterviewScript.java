package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.InterviewBook.Cand;
import com.jrpetty.mcassistant.entity.InterviewBook.Interview;
import com.jrpetty.mcassistant.entity.InterviewBook.Ref;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [interviews] What is said across the table, and what it is worth: each candidate's turn written out of who it really
 * is, and the parts of its score its turn earns.
 *
 * <p><b>A turn.</b> The chair calls it; it sits, greets the panel in its own manner (shy, cheerful, grumpy, proud) and
 * hands over its letter, which the chair reads; the post's master asks how long it has been at the trade (its real
 * level, what it has done in its life at it, how long it has worked); what its best work is (a piece with its maker's
 * mark, the knacks it chose, its fondest day); for a smith or a tailor the piece itself held up and looked over, a cook's
 * taste out of its own pack, a guard's tally of hostiles; a question the post really meets ("Raiders at the east gate at
 * night"); why it wants the post (what it cares for, what it hopes for, why it applied); a proud folk's boast, which the
 * master checks against the record; a record before the court, asked about, owned up to or not; and the word of a
 * friend, a partner, an old master or a rival in the town, or a player's.
 *
 * <p><b>Its score.</b> The paper (the post's own weighing) is the core. The turn adds, each part in the same points:
 * nerves (the shy, the low, the young, the first time; less with a letter written), preparation (the letter, and how
 * neat), the answers (a good one from a skilled hand, a stumbling one from a nervous or unready one), the evidence (the
 * piece's grade, the taste, the tally), the references (eight at most either way), honesty about a record, a boast seen
 * through or borne out, and not coming at all. Twelve either way at most, all told (Cand.interview).
 */
final class InterviewScript {

    private InterviewScript() {}

    /** What is done with a line, before it is said. */
    enum Act {
        NONE,
        /** The candidate walks over and sits across the table. */
        CALL,
        /** The letter goes from the candidate's hand to the chair's. */
        LETTER,
        /** The chair reads it (and holds it while it does). */
        READ,
        /** A piece of its work held up; then looked over. */
        SHOW,
        /** A taste out of its pack, eaten by the one who speaks. */
        TASTE,
        /** A referee walks over to speak; and goes back. */
        REF_COME, REF_GO,
        /** The candidate back to the bench. */
        BACK,
        /** The panel's faces: a nod, a frown, a raised eyebrow. */
        NOD, FROWN, BROW,
        /** The hand shaken. */
        SHAKE
    }

    /**
     * One line: who says it ("chair", "master", "council", "cand", or "folk:" and an id), the words, and what is done
     * first. A line with no words is only the act.
     */
    record Line(String who, String text, Act act, @Nullable String arg) {
        static Line of(String who, String text) { return new Line(who, text, Act.NONE, null); }
        static Line of(String who, String text, Act act) { return new Line(who, text, act, null); }
    }

    // ------------------------------------------------------------------ a candidate's turn

    /**
     * The turn of one candidate across the table, written out of the folk itself (loaded), and its score's parts set
     * on the candidate as the turn earns them. {@code f} null: it could not come, and its letter is read out instead.
     */
    static List<Line> turn(ServerLevel level, Interview iv, Cand c, @Nullable VillageFolkEntity f, boolean first) {
        List<Line> out = new ArrayList<>();
        RandomSource r = level.getRandom();
        UUID town = iv.village;
        InterviewPosts.Post post = InterviewPosts.Post.of(iv.post);
        StationTask t = post == null ? null : post.tradeFor(town);
        String name = c.name;
        String asker = iv.master != null ? "master" : iv.councillor != null ? "council" : "chair";
        String second = iv.councillor != null ? "council" : "chair";
        c.nerves = c.prep = c.answers = c.evidence = c.refs = c.honesty = c.boast = c.away = 0;
        if (f == null) {
            // Could not come: the letter read out, and the paper alone (less for not coming).
            c.away = -4;
            out.add(Line.of("chair", c.name + (c.outside ? " of " + c.homeName : "") + " could not be with us. "
                + (c.letter ? "Its letter, then." : "No letter came either.")));
            if (c.letter) {
                c.prep = 1;
                out.add(Line.of("chair", "\"" + c.letterWords + "\"", Act.READ));
                out.add(Line.of(asker, c.level >= 10 ? "Good years at it, on paper. A pity it isn't here." : "Thin, on paper. And not here.", c.level >= 10 ? Act.NOD : Act.FROWN));
            }
            return out;
        }
        Social.Life life = f.life();
        boolean shy = life.has(Social.Trait.SHY), grumpy = life.has(Social.Trait.GRUMPY), cheerful = life.has(Social.Trait.CHEERFUL);
        boolean proud = Interviews.proud(f);
        int mood = f.persona().mood();
        boolean before = InterviewBook.of().after.containsKey(f.getUUID());
        // Nerves: the shy, the low, the young, and the first time; a letter written takes the edge off.
        int nerves = (shy ? 3 : 0) + (mood < 40 ? 2 : 0) + (f.ageYears() < 22 ? 1 : 0) + (before ? 0 : 1) - (cheerful ? 1 : 0)
            - (life.has(Social.Trait.HARDWORKING) ? 1 : 0);
        c.prep = c.letter ? (life.has(Social.Trait.HARDWORKING) || life.has(Social.Trait.CURIOUS) ? 3 : 2) : -2;
        c.nerves = -Math.max(0, Math.min(6, nerves - (c.letter ? 1 : 0)));
        boolean nervous = c.nerves <= -3;

        // Called in.
        out.add(new Line("chair", first ? FolkTalk.pick(r, "First, " + name + ". Come and sit down.", name + (c.outside ? " of " + c.homeName : "")
                + ", you're first. Take the chair across.")
            : FolkTalk.pick(r, "Next: " + name + ". Come through.", name + ", would you come and sit down?", "Thank you. " + name + ", please."),
            Act.CALL, null));
        // The greeting, in its own manner, and the letter.
        VillageFolkEntity chair = iv.chair == null ? null : Civics.find(level, iv.chair);
        String chairFirst = iv.chairName.contains(" ") ? iv.chairName.substring(iv.chairName.lastIndexOf(' ') + 1) : iv.chairName;
        String hello;
        if (!c.letter) {
            hello = "Good morning. I've no letter, I'm afraid: " + (c.noLetter.isEmpty() ? "there was no paper to be had" : c.noLetter)
                + (c.outside && !c.noLetter.startsWith("I ") ? " in " + c.homeName : "") + ".";
        } else if (shy) {
            hello = FolkTalk.pick(r, "Oh — good morning. Here's my letter… I wrote it out twice.", "G-good morning. My letter. Sorry, my hands are cold.");
        } else if (grumpy) {
            hello = FolkTalk.pick(r, "Morning. My letter. Shall we get on with it?", "Here. My letter. I've a day's work waiting.");
        } else if (proud) {
            hello = "Good morning. You'll find my letter makes the case well enough.";
        } else if (cheerful) {
            hello = FolkTalk.pick(r, "Good morning, all! Lovely day for it. Here's my letter!", "Morning! Here we are, then. My letter, " + chairFirst + "!");
        } else if (chair != null && chair.life().affinity(f.getUUID()) >= Social.FRIEND) {
            hello = "Hello, " + chairFirst + ". Strange, being across a table from you. My letter.";
        } else if (life.has(Social.Trait.SOCIABLE)) {
            hello = "Hello, hello! We've met, I think — at the feast? My letter.";
        } else {
            hello = "Good morning. My letter of application.";
        }
        out.add(Line.of("cand", hello, c.letter ? Act.LETTER : Act.NONE));
        if (!c.letter) {
            // Heard all the same: the panel notes it, and the want of it tells in its preparation (c.prep).
            out.add(Line.of("chair", FolkTalk.pick(r, "No letter. Well — we'll hear you all the same.",
                "Then tell us yourself what it would have said."), Act.BROW));
        }
        if (c.letter) {
            String neat = c.prep >= 3 ? FolkTalk.pick(r, "A neat hand.", "Well set out.") : FolkTalk.pick(r, "A bit blotted, but it's all here.", "Short and to the point.");
            out.add(Line.of("chair", "\"" + clip(c.letterWords, 90) + "\" " + neat, Act.READ));
        }

        // How long at it: the post's own trade, or (a teacher, a steward) what it knows.
        StationTask own = f.stationTask();
        StationTask at = t != null && f.tradeLevel(t) > 0 ? t : own;
        StationTask asked = t != null ? t : own;
        int lv = at == StationTask.NONE ? 0 : f.tradeLevel(at);
        out.add(Line.of(asker, experienceQ(post, asked)));
        String exp = experienceA(post, f, t, at, lv, nervous);
        out.add(Line.of("cand", exp.trim()));
        int heldLv = t != null ? f.tradeLevel(t) : lv;
        c.answers += heldLv >= 15 ? 2 : heldLv >= 8 ? 1 : heldLv < 3 ? -1 : 0;
        Act face = heldLv >= 10 ? Act.NOD : heldLv < 3 ? Act.BROW : Act.NONE;
        String nod = face == Act.NOD ? FolkTalk.pick(r, "Good. ", "Good years. ", "That's a fair record. ")
            : face == Act.BROW ? FolkTalk.pick(r, "Hm. ", "I see. ") : "";

        // Its best: a piece of its work with its mark, its knacks, its fondest day; and the evidence.
        Piece piece = (at == StationTask.SMITH || at == StationTask.TAILOR || own == StationTask.SMITH || own == StationTask.TAILOR
                || t == StationTask.SMITH || t == StationTask.TAILOR)
            ? piece(level, iv, f) : null;
        ItemStack taste = at == StationTask.COOK || own == StationTask.COOK ? taste(f) : ItemStack.EMPTY;
        int kills = f.deedCount(AssistantEntity.Deed.MOBS_KILLED);
        out.add(Line.of(second, nod + FolkTalk.pick(r, "What's the best thing you've done at it?", "Tell us about your best day's work.",
            "What would you show us, if you could?"), face));
        if (piece != null) {
            Craftsmanship.Grade g = Craftsmanship.gradeOf(piece.stack);
            out.add(new Line("cand", "This — " + article(piece.stack) + ". I made it. My mark's on it.", Act.SHOW, null));
            String verdict = switch (g == null ? Craftsmanship.Grade.PLAIN : g) {
                case MASTER -> "A master's work. Look at the temper on that edge.";
                case FINE -> "Fine work. Even and true, all through.";
                case GOOD -> "Good, sound work. I'd sell that.";
                case PLAIN -> "Plain, but sound. It'll do the job.";
                case ROUGH -> "Hm. An apprentice's work, this. Rough at the joins.";
            };
            int worth = switch (g == null ? Craftsmanship.Grade.PLAIN : g) {
                case MASTER -> 6;
                case FINE -> 4;
                case GOOD -> 2;
                case PLAIN -> 0;
                case ROUGH -> -2;
            };
            c.evidence += worth;
            c.evidenceWords = article(piece.stack) + ", " + (g == null ? "sound work" : g.words);
            out.add(Line.of(asker, verdict, worth > 0 ? Act.NOD : worth < 0 ? Act.FROWN : Act.NONE));
        } else if (!taste.isEmpty()) {
            boolean cooked = cooked(taste);
            String food = taste.getHoverName().getString().toLowerCase(Locale.ROOT);
            out.add(new Line("cand", "Here — try this. " + capital(FolkTalk.article(food)) + ", out of my own pack.", Act.NONE, null));
            out.add(new Line(asker, cooked ? FolkTalk.pick(r, "Mm! Now that's " + FolkTalk.article(food) + " worth getting up for.",
                    "Oh, that's good. Very good. Who taught you that?") : "It's… raw, " + name + ". You do know that?", Act.TASTE, null));
            c.evidence += cooked ? 3 : -1;
            c.evidenceWords = "a taste of " + food + (cooked ? ", well made" : ", raw");
        } else if ((at == StationTask.GUARD || own == StationTask.GUARD || at == StationTask.HUNT || iv.post.equals("constable")) && kills > 0) {
            out.add(Line.of("cand", "The watch's tally has me at " + kills + (kills == 1 ? " hostile" : " hostiles") + ". I can show you the notches."));
            out.add(Line.of(asker, kills >= 30 ? "The tally speaks for itself." : kills >= 10 ? "A fair count." : "Not many yet.",
                kills >= 10 ? Act.NOD : Act.BROW));
            c.evidence += Math.min(5, kills / 6);
            c.evidenceWords = kills + " hostiles on the watch's tally";
        } else {
            out.add(Line.of("cand", best(f, r)));
        }

        // A question the post really meets.
        String[] sc = scenario(level, iv, post, asked, f);
        int q = heldLv / 5 + fit(post, asked, life) + (c.letter ? 1 : 0) + (nervous ? -2 : 0)
            + (f == null ? 0 : Quirks.inInterview(f, asked));                 // [perks] a quirk that suits the trade asked about
        boolean good = q >= 3, fair = q >= 1;
        out.add(Line.of("chair", sc[0]));
        out.add(Line.of("cand", (nervous && !good ? FolkTalk.pick(r, "Um. ", "Er — ", "Well… ") : "") + (good ? sc[1] : fair ? sc[2] : sc[3])));
        c.answers += good ? 3 : fair ? 0 : -2;
        Act said = good ? Act.NOD : fair ? Act.NONE : Act.FROWN;
        String heard = good ? FolkTalk.pick(r, "Sensible. ", "Just so. ", "Good answer. ") : fair ? "" : FolkTalk.pick(r, "Hm. ", "I see. ");

        // Why it wants it.
        out.add(Line.of(second, heard + FolkTalk.pick(r, "Why do you want it?", "And why this post, " + name + "?", "What makes you want it?"), said));
        out.add(Line.of("cand", why(level, iv, c, f)));

        // A proud folk's boast, checked against the record.
        if (proud) {
            String noun = asked == StationTask.NONE ? "hand" : JobMarket.noun(asked);
            out.add(Line.of("cand", FolkTalk.pick(r, "And I'll say it plainly: there's no better " + noun + " in three towns.",
                "There's nobody in this town who knows " + asked.label + " like I do.")));
            VillageFolkEntity better = bestAt(town, asked, f);
            int bestLv = better == null ? -1 : better.tradeLevel(asked);
            if (heldLv >= 15 && heldLv >= bestLv) {
                c.boast = 2;
                out.add(Line.of(asker, "That's no idle boast. Level " + heldLv + ", and nobody here above it.", Act.NOD));
            } else {
                c.boast = -5;
                out.add(Line.of(asker, "Level " + heldLv + ", " + name + ". I can count." + (better != null && bestLv > heldLv
                    ? " " + better.displayNameCap() + " is level " + bestLv + "." : ""), Act.FROWN));
            }
        }

        // The record, if it has one before the court.
        int convictions = Crime.convictions(f.getUUID());
        if (convictions > 0) {
            CompoundTag rec = Crime.folk(f.getUUID());
            String what = rec.getString("lastCrime").isEmpty() ? "an offence" : rec.getString("lastCrime");
            long when = rec.getLong("lastConvicted");
            out.add(Line.of(iv.councillor != null ? "council" : "chair", "Your record says you were found guilty of " + what
                + (when > 0 ? " on day " + (when + 1) : "") + ". Tell us about that.", Act.BROW));
            boolean owns = Mischief.honesty(f) >= 50 || Crime.reformed(f.getUUID());
            if (owns) {
                c.honesty = 2;
                out.add(Line.of("cand", FolkTalk.pick(r, "I did it. I paid it back, every coin, and I've not done it since.",
                    "It was me. I'm not proud of it. I'm not that folk any more.")));
                out.add(Line.of("chair", "Thank you for being straight with us.", Act.NOD));
            } else {
                c.honesty = -6;
                out.add(Line.of("cand", FolkTalk.pick(r, "That was all a misunderstanding. I never did a thing.", "I'd rather not go into it.")));
                out.add(Line.of(iv.councillor != null ? "council" : "chair", "The court didn't think it a misunderstanding.", Act.FROWN));
            }
        }

        // A word for it (or against): from the town, and from players.
        int words = 0;
        for (Ref ref : c.words) {
            if (ref.player) {
                out.add(Line.of("chair", ref.byName + " has put in a word for " + name + ": " + ref.line, Act.NOD));
                c.refs += ref.weight;
                continue;
            }
            if (words >= 2) continue;
            words++;
            out.add(new Line("folk:" + ref.by, "", Act.REF_COME, null));
            out.add(Line.of("folk:" + ref.by, ref.line));
            out.add(new Line("chair", ref.weight < 0 ? "Noted, " + ref.byName + "." : "Thank you, " + ref.byName + ".", Act.REF_GO,
                ref.weight < 0 ? "frown" : "nod"));
            c.refs += ref.weight;
        }
        c.refs = Math.max(-8, Math.min(8, c.refs));

        // Done: back to the bench.
        out.add(new Line("chair", FolkTalk.pick(r, "Thank you, " + name + ". If you'd wait with the others.",
            "That's all for now, " + name + ". Thank you.", "Thank you. We'll call you back."), Act.BACK, null));
        if (shy && r.nextBoolean()) out.add(Line.of("cand", "Thank you. Sorry. Thank you."));
        return out;
    }

    // ------------------------------------------------------------------ the questions

    /** How long at it, by the post's trade. */
    static String experienceQ(@Nullable InterviewPosts.Post post, StationTask t) {
        if (post != null) {
            switch (post.kind()) {
                case TEACHER -> { return "What do you know that's worth teaching a child?"; }
                case LIBRARIAN -> { return "What do you read, and how much?"; }
                case STEWARD -> { return "What do you know of the town's business?"; }
                case AUCTIONEER -> { return "Have you ever sold to a crowd?"; }
                default -> { }
            }
        }
        return switch (t) {
            case MINE -> "How long have you worked the mines?";
            case FARM -> "How long have you been in the fields?";
            case WOOD -> "How long have you swung an axe?";
            case SMITH -> "How long have you worked the forge?";
            case TAILOR -> "How long have you been at the loom?";
            case COOK -> "How long have you kept a kitchen?";
            case GUARD -> "How long have you stood the watch?";
            case FISH -> "How long have you fished?";
            case BREW -> "How long have you been brewing?";
            case ENCHANT -> "How long at the enchanting table?";
            case SHOP -> "How long have you kept a counter?";
            case STORE -> "How long have you kept a town's stores?";
            case HAUL -> "How long have you carried for a town?";
            case SMELT -> "How long have you tended a furnace?";
            case RANCH -> "How long have you kept beasts?";
            case BEEKEEP -> "How long have you kept bees?";
            case SCOUT -> "How long have you been scouting?";
            case HUNT -> "How long have you hunted?";
            case BANK -> "How long have you kept accounts?";
            case CAVE -> "How long have you been going underground?";
            case FERRY -> "How long have you handled a boat?";
            case FLETCHER -> "How long have you been fletching?";
            case GOLEMS -> "How long have you kept the golems?";
            case CARTOGRAPHER -> "How long have you been drawing maps?";      // [cartographer]
            case EMERALD -> "How long have you traded with the villagers?";   // [emerald]
            case DIVER -> "How long have you been diving, and how long can you hold your breath?";   // [diver]
            case NETHER -> "How long have you carried a blade, and have you ever been through a portal?";   // [nether]
            case REDSTONE -> "How long have you worked with redstone, and can you tell an observer from a comparator?";   // [redstone]
            case NONE -> "What have you done, till now?";
            // A trade come in since: asked in its own words.
            default -> "How long have you been at " + t.label + "?";
        };
    }

    /**
     * Its answer to how long it has been at it: its years and level at the post's trade and what it has done in its life
     * at it; never at it (its own trade, then); or, for a teacher, a librarian, a steward, what it knows that fits.
     */
    static String experienceA(@Nullable InterviewPosts.Post post, VillageFolkEntity f, @Nullable StationTask t, StationTask at, int lv,
                              boolean nervous) {
        Persona me = f.persona();
        String served = f.daysServed() > 0 ? " I've been at work here these " + JobMarket.words(Math.min(f.daysServed(), 99)) + " days." : "";
        if (post != null && post.kind() == InterviewPosts.Kind.TEACHER) {
            int kids = f.life().children();
            return (at == StationTask.NONE ? "Not a trade to speak of" : capital(at.label) + ", " + JobMarket.words(Math.min(lv, 99))
                + (lv == 1 ? " level" : " levels") + " of it") + (me.rolled() && me.hobby() == Persona.Hobby.READING ? ", and every book I can lay hands on." : ".")
                + (kids > 0 ? " And I've raised " + JobMarket.words(Math.min(kids, 12)) + (kids == 1 ? " child" : " children") + " of my own." : "");
        }
        if (post != null && post.kind() == InterviewPosts.Kind.LIBRARIAN) {
            if (me.rolled() && me.hobby() == Persona.Hobby.READING) return "Every evening, by the lamp. It's what I do for fun.";
            if (me.rolled() && me.quirk().equals("keeps a diary")) return "Less than I'd like. But I keep a diary, every night.";
            return "Not as much as I should. I'd read more, with the books to hand.";
        }
        if (post != null && post.kind() == InterviewPosts.Kind.STEWARD) {
            return "Who's short of what, who's quarrelled with whom, and what the leader promised." + served;
        }
        if (post != null && post.kind() == InterviewPosts.Kind.AUCTIONEER) {
            return f.stationTask() == StationTask.SHOP ? "Every day, over the counter. A crowd's only more customers." : "Never to a crowd. But I can shout.";
        }
        if (t != null && f.tradeLevel(t) == 0) {
            return at == StationTask.NONE ? "Never, I'll own. But I'm willing, and I learn quickly."
                : "Never at " + t.label + ", I'll own. But I'm level " + lv + " at " + at.label + ", and I learn quickly.";
        }
        String deeds = deeds(f, at);
        if (lv >= 15) return capital(JobMarket.words(lv)) + " levels at it now. " + deeds + served;
        if (lv >= 5) return "Level " + lv + " at " + at.label + ". " + deeds;
        return nervous ? "Not… not long. Level " + lv + ". But I learn quickly, I do." : "Not long, I'll own — level " + lv + ". But I learn quickly.";
    }

    /** What it has done in its life at the trade, in a sentence. */
    static String deeds(VillageFolkEntity f, StationTask t) {
        AssistantEntity.Deed d = switch (t) {
            case MINE, CAVE -> AssistantEntity.Deed.ORE_FOUND;
            case FARM -> AssistantEntity.Deed.CROPS_HARVESTED;
            case WOOD -> AssistantEntity.Deed.TREES_FELLED;
            case FISH -> AssistantEntity.Deed.FISH_CAUGHT;
            case GUARD, HUNT -> AssistantEntity.Deed.MOBS_KILLED;
            case HAUL -> AssistantEntity.Deed.LOADS_HAULED;
            case SMELT -> AssistantEntity.Deed.ITEMS_SMELTED;
            case RANCH -> AssistantEntity.Deed.ANIMALS_BRED;
            case STORE -> AssistantEntity.Deed.CHESTS_SORTED;
            default -> AssistantEntity.Deed.THINGS_MADE;
        };
        int n = f.deedCount(d);
        if (n <= 0) return "";
        String what = switch (d) {
            case ORE_FOUND -> n == 1 ? "vein of ore dug out" : "veins of ore dug out";
            case CROPS_HARVESTED -> "harvests brought in";
            case TREES_FELLED -> n == 1 ? "tree felled" : "trees felled";
            case FISH_CAUGHT -> "fish landed";
            case MOBS_KILLED -> n == 1 ? "hostile killed" : "hostiles killed";
            case LOADS_HAULED -> n == 1 ? "load carried" : "loads carried";
            case ITEMS_SMELTED -> "things through the furnace";
            case ANIMALS_BRED -> "beasts bred";
            case CHESTS_SORTED -> "chests set straight";
            default -> n == 1 ? "thing made" : "things made";
        };
        return capital(n <= 12 ? JobMarket.words(n) : Integer.toString(n)) + " " + what + ", all told.";
    }

    /** A question the post really meets, with a good answer, a middling one and a stumbling one: {question, good, fair, poor}. */
    static String[] scenario(ServerLevel level, Interview iv, @Nullable InterviewPosts.Post post, StationTask t, VillageFolkEntity f) {
        UUID town = iv.village;
        int mouths = Villages.headcount(town);
        boolean walled = Villages.hasBuilt(town, "fortify");
        String gate = walled ? "the " + FolkTalk.pick(level.getRandom(), "east", "north", "west", "south") + " gate" : "the edge of the fields";
        if (post != null) {
            switch (post.kind()) {
                case TEACHER -> {
                    int kids = 0;
                    for (AssistantEntity a : Villages.folkOf(town)) if (a.isBaby()) kids++;
                    return new String[]{ "A child won't learn its letters. What then?" + (kids > 0 ? " We've " + JobMarket.words(kids) + " of them." : ""),
                        f.life().has(Social.Trait.CURIOUS) ? "Find out what it does want to know — the forge, the bees — and teach it its letters through that."
                            : "Patience. Sit with it after the others go, and let it read what it likes. They all come to it in the end.",
                        "Keep at it, I suppose. Every day, a little more.",
                        f.life().has(Social.Trait.GRUMPY) ? "It'll learn, or it'll stand in the corner till it does." : "I… don't know. Tell its parents?" };
                }
                case LIBRARIAN -> {
                    return new String[]{ "A book comes back torn, and the borrower swears it was like that. What do you do?",
                        "Look in the ledger — I note every book's state when it goes out. Then a fair word, and the mending shared.",
                        "Mend it, and keep a closer eye on that one.", "Shout at them? No — I don't know." };
                }
                case STEWARD -> {
                    return new String[]{ "The leader's away and the stores are down to their last loaves. What do you do?",
                        "Food first: hands to the fields and the river, the feast put off, and word sent to the leader by nightfall.",
                        "Wait for the leader, and ration what's left.", "Hope it comes back soon." };
                }
                case BANKER -> {
                    return new String[]{ "A family can't meet its mortgage this month. What do you do?",
                        "A month's grace, and the rest spread over the next three. A family on the street pays nobody.",
                        "Give it a week, and then the rules are the rules.", "Take the house, I suppose?" };
                }
                case AUCTIONEER -> {
                    return new String[]{ "Two bidders, one lot, and both say they called first. What then?",
                        "Put it up again from their last price, there and then. Fair to both, and the town does better.",
                        "Give it to whoever I heard first.", "I'd… let them sort it out?" };
                }
                case CAVE_LEADER, CAVE_PLACE -> {
                    return new String[]{ "Your lantern's out, a mile down, and you hear a spider. What do you do?",
                        "Torch from my pack, back to back with the team, and out along my own marks. Nobody goes off alone.",
                        "Light a torch and run for it.", "Scream, most likely." };
                }
                case MASTER -> {
                    return new String[]{ "An apprentice ruins a week's work. What do you say to it?",
                        "Show it where it went wrong, then let it do it again while I watch. I ruined plenty, once.",
                        "Tell it to be more careful.", "Send it home, I expect." };
                }
                default -> { }
            }
        }
        return switch (t) {
            case GUARD -> new String[]{ "Raiders at " + gate + " at night. What do you do?",
                "Ring the bell first. Bows to the wall, the gate barred, and nobody goes out after them in the dark.",
                "Get to the gate and hold it as long as I can.", "Run at them? Or — shout for help, I suppose." };
            case SMITH -> {
                int iron = Market.stock(level, town, s -> s.is(Items.IRON_INGOT));
                yield new String[]{ (iron < 4 ? "The stores are down to " + (iron == 0 ? "no iron at all" : JobMarket.words(iron) + " iron") + " as we sit here"
                        : "Say the stores run out of iron") + ", and the watch wants blades. What then?",
                    "Blades first. I'd melt down the broken tools in the stores and send to the mine for ore — the rest can wait.",
                    "Tell the mine we need iron, and make what I can.", "Tell the watch to wait, I suppose." };
            }
            case MINE -> new String[]{ "There's a creeper in the gallery and the ladder's down. What do you do?",
                "Back out quiet, wall the gallery off, and send up for a guard with a bow.", "Get out as fast as I can.", "Hit it? Quickly?" };
            case FARM -> new String[]{ "No rain for a week, and the wheat's wilting. What then?",
                "Water from the well by the bucket, the youngest rows first, and a channel cut from the stream.", "Water what I can.",
                "Hope for rain." };
            case WOOD -> new String[]{ "The town wants timber for three houses and the near wood's thin. What do you do?",
                "Fell the far wood, plant two saplings for every tree, and leave the near wood a season.", "Fell what's there and plant after.",
                "Cut the near wood down, I suppose." };
            case COOK -> new String[]{ capital(JobMarket.words(Math.min(mouths, 99))) + " mouths and the larder's down to bread and carrots. What's for supper?",
                "Carrot soup, bread on the side, and a word with the fishers at dawn for tomorrow.", "Bread and carrots, and plenty of both.",
                "Bread. Just bread." };
            case TAILOR -> new String[]{ "The watch wants ten tabards by the feast and you've wool for six. What then?",
                "Six now, and to the rancher for the shearing — the other four the day after. I'd tell the watch so today.",
                "Make six and say sorry.", "Make ten small ones?" };
            case FISH -> new String[]{ "You're out on the water and the sky turns black. What do you do?",
                "Nets in, and home before the first drop. No catch is worth a boat.", "Fish a bit longer, then go in.", "Keep fishing, I'd think." };
            case SHOP, STORE -> new String[]{ "A customer swears you short-changed them. What do you do?",
                "Count it out again in front of them, and if I'm wrong, a coin back and an apology.", "Count it again.", "Tell them they're wrong." };
            case FERRY -> new String[]{ "A storm's coming up and there's a passenger waiting on the far bank. What then?",
                "Tie up and wait it out, and shout across that I'm coming after. Nobody crosses in a storm, not even for a coin.",
                "Go quickly before it breaks.", "Go anyway. A coin's a coin." };
            case BREW -> new String[]{ "The healer wants six potions by morning and you've glass for three. What then?",
                "Three now, the smelter told tonight for sand into glass, and the rest at first light.", "Make three.", "Water them down?" };
            case ENCHANT -> new String[]{ "The watch wants its blades enchanted and you've lapis for one. Whose first?",
                "The one who walks the wall at night. Then I'd ask the mine for lapis.", "Whoever asks first.", "My own, I suppose." };
            case HAUL -> new String[]{ "Two calls for a load at once, the forge and the fields. Which first?",
                "The forge: a smith idle wastes the most. Then the fields, and I'd tell them so.", "Whichever's nearer.", "Neither, till I've had my dinner." };
            case CAVE -> new String[]{ "Your lantern's out, a mile down, and you hear a spider. What do you do?",
                "Torch from my pack, back to back with the team, and out along my own marks.", "Light a torch and run.", "Scream, most likely." };
            case FLETCHER -> new String[]{ "The watch wants a hundred arrows by the full moon and you've feathers for forty. What then?",
                "Forty now, the rancher asked for the chickens' moult, and a word to the watch so it practises with the old ones.",
                "Make forty and ask round for feathers.", "Make them without feathers?" };
            case GOLEMS -> new String[]{ "The golem's cracked after a raid and the stores have two ingots. What do you do?",
                "Both ingots into it now, and the smelter told the golem comes before the watch's new helmets till it's mended.",
                "Mend what I can with the two.", "Leave it. Golems mend themselves, don't they?" };
            // [cartographer] The map room's first rule (its trade book): never sell a map of land the town hasn't seen.
            case CARTOGRAPHER -> new String[]{ "A traveller offers good coin for a map to a monument, and the scouts have never been out to sea. What then?",
                "Tell it straight: not yet. The scouts out that way first, and the map drawn when they're home. A map that lies gets folk drowned.",
                "Draw what I can of the coast, and say the rest is guesswork.", "Draw a monument somewhere likely. Coin's coin." };
            case EMERALD -> new String[]{ "You reach the villagers' village and there are pillagers about the bell. What do you do?",   // [emerald]
                "Turn straight round with the goods and tell the town. The villagers' fight isn't ours, and the trade will keep.",
                "Wait at the edge till they've gone.", "Sell quick and run?" };
            // [diver] The diver's first rule (its trade book): up for air before you need it, not when you do.
            case DIVER -> new String[]{ "You're on the bed with the last of the kelp to cut, and your chest is starting to burn. What then?",
                "Straight up, to open water and not under the jetty. The kelp's still there when I've my breath back.",
                "Cut the last one quickly, then up.", "Keep going. I can hold it a bit longer." };
            // [nether] The runners' first rule (School's lines): gold on before you go through, and never strike a piglin.
            case NETHER -> new String[]{ "On the far side a piglin's watching you hard, and your gold charm's come off in the scramble. What then?",
                "Charm back on before anything else, back to the others, and never a hand raised to it: strike one and they all come.",
                "Back away slowly and hope it loses interest.", "Draw my sword before it does." };
            // [redstone] The engineer's first rule (its trade book): never let an observer watch where a piston's head comes.
            case REDSTONE -> new String[]{ "The cane farm's pistons won't stop: out, in, out, in, with no cane grown. What's wrong?",
                "An observer's watching the place a piston's head comes into: it sees the head and fires it again. Move the observer.",
                "Pull the redstone up and lay it again.", "Leave it. It'll stop when it's tired." };
            default -> new String[]{ "What would you do first, in the post?",
                "Learn how it's done here first — ask who did it before me — and then do it better.", "Get to work.", "I'm not sure, to be honest." };
        };
    }

    /** How its nature suits the post's question: the patient for a child, the steady for the watch. */
    static int fit(@Nullable InterviewPosts.Post post, StationTask t, Social.Life life) {
        int s = 0;
        boolean teacher = post != null && post.kind() == InterviewPosts.Kind.TEACHER;
        if (teacher && (life.has(Social.Trait.EASYGOING) || life.has(Social.Trait.CURIOUS))) s += 2;
        if (teacher && life.has(Social.Trait.GRUMPY)) s -= 2;
        if ((t == StationTask.GUARD || post != null && post.kind() == InterviewPosts.Kind.CONSTABLE) && life.has(Social.Trait.HARDWORKING)) s += 1;
        if (post != null && post.meetsFolk() && life.has(Social.Trait.SOCIABLE)) s += 1;
        if (post != null && post.meetsFolk() && life.has(Social.Trait.SHY)) s -= 1;
        if (life.has(Social.Trait.CURIOUS) && !teacher) s += 1;
        return s;
    }

    /** Why it wants the post: why it applied (from another town), what it cares about, what it hopes for. */
    static String why(ServerLevel level, Interview iv, Cand c, VillageFolkEntity f) {
        StringBuilder sb = new StringBuilder();
        if (c.outside) {
            String kin = JobSeekers.kinIn(f, iv.village);
            if (kin != null) sb.append("My family's here — ").append(kin.replace(", its ", ", my ")).append(". I'd like to be near them.");
            else if (c.good.contains("pay") || c.good.contains("wages")) sb.append("Honestly? The pay's better than at home.");
        }
        Values.Value top = Values.top(f);
        String cares = switch (top) {
            case FOOD -> "A town's only as good as its larder, and I can help fill it.";
            case HOMES -> "I want every family here to have a roof over it.";
            case PROGRESS -> "I want to see this town reach its next age, and I can help it get there.";
            case SAFETY -> "I want the streets safe at night. That matters more than anything.";
            case WEALTH -> "Honestly? It pays well. And I'd earn every coin.";
            case LEISURE -> "I'd enjoy it — and a happy worker's a good worker.";
            case TRADITION -> "Somebody has to keep the old ways going. I'd like it to be me.";
            default -> "I think I'd be good at it. And I'd like to find out.";
        };
        if (sb.length() > 0) sb.append(' ');
        sb.append(cares);
        Persona me = f.persona();
        if (me.rolled() && !me.ambitionMet()) {
            if (me.ambition() == Persona.Ambition.MASTER) sb.append(" And I mean to be the best at my trade in the village.");
            else if (me.ambition() == Persona.Ambition.FRIENDS) sb.append(" And I'd get to know everybody.");
            else if (me.ambition() == Persona.Ambition.FAMILY) sb.append(" And it'd give a family a good start.");
        }
        return sb.toString();
    }

    /** Its best day, or its knacks, or nothing to boast of: out of its own memory and its own choices. */
    static String best(VillageFolkEntity f, RandomSource r) {
        List<FolkSkills.Chosen> chosen = f.knacks().chosen();
        Persona.Memory m = f.persona().fondest();
        if (!chosen.isEmpty() && (m == null || r.nextBoolean())) {
            FolkSkills.Chosen k = chosen.get(chosen.size() - 1);
            return "I took " + k.knack().title + " — " + k.knack().why + ". " + (chosen.size() > 1 ? "That's " + JobMarket.words(chosen.size()) + " knacks I've chosen." : "");
        }
        if (m != null) return m.text().startsWith("I ") ? "The day " + m.text() + ". I'll not forget it."
            : "I'll never forget it: " + m.text() + ".";
        return "I've no great day to tell of. I just do the work, day in, day out.";
    }

    /** The best of the town's own at a trade, not this one; or null. */
    @Nullable
    static VillageFolkEntity bestAt(UUID town, StationTask t, VillageFolkEntity not) {
        if (t == StationTask.NONE) return null;
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (!(a instanceof VillageFolkEntity f) || f == not || f.isBaby()) continue;
            if (best == null || f.tradeLevel(t) > best.tradeLevel(t)) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------ evidence

    /** A piece of its own work, with its mark, and where it lies (its pack, a chest), to be held up and put back. */
    static final class Piece {
        final ItemStack stack;
        /** -1: its pack's slot, else a chest's. */
        final int slot;
        @Nullable final BlockPos chest;

        Piece(ItemStack stack, int slot, @Nullable BlockPos chest) {
            this.stack = stack;
            this.slot = slot;
            this.chest = chest;
        }
    }

    /** Whose mark a thing carries: the maker's name, or "". */
    static String maker(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null || !d.contains(Craftsmanship.MARK)) return "";
        return d.copyTag().getCompound(Craftsmanship.MARK).getString("by");
    }

    /**
     * The best piece of its own work it can lay hands on: in its pack, in its home's chest, or (one of the town's own)
     * in the town's stores, the finest first. Null if there is none.
     */
    @Nullable
    static Piece piece(ServerLevel level, Interview iv, VillageFolkEntity f) {
        String me = f.displayNameCap();
        Piece best = null;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (!s.isEmpty() && me.equals(maker(s)) && better(s, best)) best = new Piece(s, i, null);
        }
        if (me.equals(maker(f.getMainHandItem())) && better(f.getMainHandItem(), best)) best = new Piece(f.getMainHandItem(), -2, null);
        List<BlockPos> chests = new ArrayList<>();
        UUID home = f.ownerId();
        if (home != null) {
            Homes.Home h = Homes.homeOf(home, f.getUUID());
            BlockPos chest = h == null ? null : Homes.chestOf(level, home, h);
            if (chest != null) chests.add(chest);
            if (home.equals(iv.village)) chests.addAll(Villages.storeChests(level, home));
        }
        for (BlockPos p : chests) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack s = box.getItem(i);
                if (!s.isEmpty() && me.equals(maker(s)) && better(s, best)) best = new Piece(s, i, p.immutable());
            }
        }
        return best;
    }

    private static boolean better(ItemStack s, @Nullable Piece than) {
        if (than == null) return true;
        Craftsmanship.Grade a = Craftsmanship.gradeOf(s), b = Craftsmanship.gradeOf(than.stack);
        return (a == null ? -1 : a.ordinal()) > (b == null ? -1 : b.ordinal());
    }

    /** Something to taste out of its own pack: a cooked dish before anything raw; empty if it carries no food. */
    static ItemStack taste(VillageFolkEntity f) {
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || s.get(DataComponents.FOOD) == null) continue;
            if (best.isEmpty() || cooked(s) && !cooked(best)) best = s;
        }
        return best;
    }

    /** A dish, not a thing as it came: cooked, baked, made. */
    static boolean cooked(ItemStack s) {
        String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return p.startsWith("cooked_") || p.startsWith("baked_") || p.contains("bread") || p.contains("pie") || p.contains("stew")
            || p.contains("soup") || p.contains("cake") || p.contains("cookie") || p.equals("golden_carrot") || p.contains("honey");
    }

    // ------------------------------------------------------------------ references

    /**
     * Who in the town (loaded, not on the panel, not standing) would speak for the candidate, or against it: its
     * partner or best friend, an old hand at its trade who thinks well of it, and (if it has one) a rival.
     */
    static List<Ref> referees(ServerLevel level, Interview iv, VillageFolkEntity f, java.util.Set<UUID> not) {
        List<Ref> out = new ArrayList<>();
        UUID town = iv.village;
        String name = f.displayNameCap();
        Social.Life life = f.life();
        VillageFolkEntity friend = null, master = null, rival = null;
        int friendAff = Social.FRIEND - 1, rivalAff = Social.RIVAL + 1;
        StationTask t = f.stationTask();
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (!(a instanceof VillageFolkEntity o) || o == f || o.isBaby() || not.contains(o.getUUID()) || !InterviewPosts.about(o)) continue;
            int fond = o.life().affinity(f.getUUID());
            boolean partner = f.getUUID().equals(o.life().partner());
            if ((partner || fond > friendAff) && fond >= Social.FRIEND) {
                friend = o;
                friendAff = partner ? 1000 : fond;
            }
            if (t != StationTask.NONE && o.stationTask() == t && o.tradeLevel(t) >= f.tradeLevel(t) + 5 && fond >= 10 && o.ageYears() > f.ageYears()
                    && (master == null || o.tradeLevel(t) > master.tradeLevel(t))) master = o;
            if (fond < rivalAff && fond <= Social.RIVAL) {
                rival = o;
                rivalAff = fond;
            }
        }
        RandomSource r = level.getRandom();
        if (friend != null) {
            boolean partner = f.getUUID().equals(friend.life().partner());
            Ref ref = new Ref();
            ref.by = friend.getUUID();
            ref.byName = friend.displayNameCap();
            ref.kind = partner ? "partner" : "friend";
            int known = Math.max(1, Math.min(f.daysServed(), friend.daysServed()) / 3);
            ref.line = partner ? FolkTalk.pick(r, "I'm biased, I know. But there's nobody in this town I'd trust more than " + name + ".",
                    "I'll say it though I'm its partner: " + name + " never once let anybody down.")
                : FolkTalk.pick(r, "I've worked beside " + name + " " + JobMarket.words(Math.min(known, 12)) + (known == 1 ? " year" : " years")
                    + "; there's none steadier.", "If " + name + " says a thing will be done, it's done. Ask anyone.");
            ref.weight = partner ? 2 : Math.min(4, 2 + friendAff / 30);
            out.add(ref);
        }
        if (master != null && master != friend) {
            Ref ref = new Ref();
            ref.by = master.getUUID();
            ref.byName = master.displayNameCap();
            ref.kind = "old master";
            ref.line = FolkTalk.pick(r, "I taught " + name + " the trade, near enough. Best pair of hands I ever had beside me.",
                "I'm level " + master.tradeLevel(t) + " at " + t.label + ", and I'll tell you: " + name + " will pass me one day.");
            ref.weight = Math.min(5, 3 + (master.tradeLevel(t) - f.tradeLevel(t)) / 10);
            out.add(ref);
        }
        if (rival != null && rival != friend && rival != master && (out.size() < 2 || r.nextBoolean())) {
            Ref ref = new Ref();
            ref.by = rival.getUUID();
            ref.byName = rival.displayNameCap();
            ref.kind = "rival";
            ref.line = FolkTalk.pick(r, "With respect — " + name + " talks a better job than it does.",
                "I'll say what nobody else will: " + name + " can't be trusted with it.");
            // The panel knows a grudge when it hears one: the bitterer, the less it counts.
            ref.weight = rivalAff <= -60 ? -2 : -4;
            out.add(ref);
        }
        return out;
    }

    // ------------------------------------------------------------------ the panel's huddle, the choice

    /** The panel in a huddle, whispering: what told for and against each, in a few short lines. */
    static List<Line> huddle(Interview iv, RandomSource r) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of("chair", FolkTalk.pick(r, "Well?", "So. What did we make of them?", "Right. Quietly, now.")));
        String[] voices = { iv.master != null ? "master" : "chair", iv.councillor != null ? "council" : "chair" };
        int v = 0;
        for (Cand c : iv.cands) {
            String who = voices[v++ % 2];
            String line;
            if (c.absent) line = c.name + " didn't come. That tells you something.";
            else if (c.boast < 0) line = c.name + " — the best in three towns, it says. Level " + c.level + ".";
            else if (c.honesty < 0) line = "I can't get past " + c.name + "'s answer about its record.";
            else if (c.evidence >= 4) line = c.name + "'s work spoke for itself: " + c.evidenceWords + ".";
            else if (c.refs >= 4) line = c.name + "'s referee carried weight with me.";
            else if (c.nerves <= -3) line = c.name + " was nervous, but there's a good head on it.";
            else if (c.answers >= 3) line = c.name + " answered well. Clear, sensible.";
            else if (c.answers < 0) line = c.name + " struggled. Not ready, I'd say.";
            else line = c.name + " was steady enough.";
            out.add(Line.of(who, line));
        }
        return out;
    }

    /** The reason, in a sentence, the choice went as it did. */
    static String reason(Interview iv, Cand w, @Nullable Cand next) {
        List<String> why = new ArrayList<>();
        if (next != null && w.paper > next.paper + 5) why.add("the most years at it of " + (iv.cands.size() == 2 ? "the two" : "them all"));
        if (w.evidence >= 4) why.add("the finest work we saw (" + w.evidenceWords + ")");
        if (w.answers >= 4) why.add("the best answers");
        if (w.refs >= 4) why.add("a reference we couldn't ignore");
        if (w.honesty > 0) why.add("an honest answer about the past");
        if (w.boast > 0) why.add("a boast the record bears out");
        if (why.isEmpty()) why.add(next != null && w.total - next.total <= 4 ? "a close thing, but the steadier of the two" : "the best of the field, all told");
        return why.size() == 1 ? why.get(0) : String.join(", ", why.subList(0, why.size() - 1)) + " and " + why.get(why.size() - 1);
    }

    /** Why another was not chosen, kindly, and what to work at for next time. */
    static String kindly(Cand c, Cand w) {
        if (c.absent) return "we'd have liked to meet you; come next time, and we'll hear you";
        if (c.honesty < 0) return "be straight with us about the past, next time, and it'll count for you";
        if (c.boast < 0) return "let the work do the talking next time";
        if (c.level + 5 <= w.level) return "too new to it yet; another year at it and you'll walk it";
        if (c.nerves <= -3) return "nerves got the better of you; you know more than you showed us";
        if (c.evidence < 0) return "bring us better work next time";
        if (c.answers < 0) return "think the post through, and come again";
        return "it was close; keep at it, and try again";
    }

    /** A line between two waiting their turn on the bench. */
    static String[] chatter(VillageFolkEntity a, VillageFolkEntity b, RandomSource r) {
        String bn = b.displayNameCap();
        String ask = a.life().has(Social.Trait.SHY) ? FolkTalk.pick(r, "Have… have you done this before?", "My hands won't stop shaking.")
            : a.life().has(Social.Trait.GRUMPY) ? FolkTalk.pick(r, "They're taking their time.", "Hmph. Should've worn my good boots.")
            : FolkTalk.pick(r, "Have you done this before, " + bn + "?", "Did you bring a letter? I wrote mine three times.",
                "What do you think they'll ask?", "I hope they ask about " + a.stationTask().label + ".", "Good luck, " + bn + ".");
        String back = b.life().has(Social.Trait.GRUMPY) ? FolkTalk.pick(r, "Shh. I'm thinking.", "Don't talk to me.")
            : b.life().has(Social.Trait.SHY) ? "…mm. Me neither."
            : b.life().has(Social.Trait.CHEERFUL) ? FolkTalk.pick(r, "You'll be fine! Deep breaths.", "Never! Exciting, isn't it?")
            : FolkTalk.pick(r, "Never. You?", "Once. Didn't get it.", "Shh — they'll hear us.", "You too.");
        return new String[]{ ask, back };
    }

    // ------------------------------------------------------------------ words

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String clip(String s, int most) {
        return s.length() <= most ? s : s.substring(0, most - 1) + "…";
    }

    /** "an iron sword". */
    static String article(ItemStack s) {
        return FolkTalk.article(s.getHoverName().getString().toLowerCase(Locale.ROOT));
    }
}
