package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A folk's inner life: what it does with its own time, what it likes, what it
 * hopes for, how it feels today and why, what it remembers, and what it thinks
 * of each player it has met.
 *
 * <p>{@link Social.Life} is who a folk is among its own people; this is who it is
 * on its own, and to you. Carried on the folk, saved with it, and the source of
 * everything it says when you talk to it.
 */
public final class Persona {

    /** What a folk does with an evening that is its own. */
    public enum Hobby {
        FISHING("fishing", "fishing at the water's edge", "a quiet hour with a line in the water"),
        STARGAZING("stargazing", "watching the stars", "the sky on a clear night"),
        GARDENING("gardening", "growing flowers", "flowers round my door"),
        MUSIC("music", "playing music at the well", "a tune at the well of an evening"),
        READING("reading", "reading", "a good book"),
        WALKING("walking", "long walks round the village", "a long walk at dusk"),
        CARDS("cards", "playing cards with friends", "a hand of cards with a friend"),
        WHITTLING("whittling", "whittling bits of wood", "something to carve");

        public final String word, doing, love;

        Hobby(String word, String doing, String love) {
            this.word = word;
            this.doing = doing;
            this.love = love;
        }
    }

    /** What a folk hopes for. Each is something the game can see come true. */
    public enum Ambition {
        MASTER("to be the best at my trade in the village", "I've become a master of my trade"),
        FAMILY("to raise a family", "I've a family of my own now"),
        FRIENDS("to have friends all over the village", "I've more friends than I can count"),
        DIAMOND("to find a diamond", "I found a diamond — a real one"),
        NETHER("to see this village reach its last age", "I lived to see the village reach the Nether Age"),
        GREAT_WORK("to see a great work raised here", "I saw a great work raised in my own village"),
        GARDEN("to grow the finest garden in the village", "my garden is the talk of the village"),
        WELL_FED("never to go hungry again", "the stores are full and I've not gone hungry in a long while");

        public final String hope, done;

        Ambition(String hope, String done) {
            this.hope = hope;
            this.done = done;
        }
    }

    /** Things a folk can be given. Every folk loves one kind and can't abide another. */
    public enum Gift {
        FLOWERS("flowers"), SWEETS("something sweet"), GEMS("a gem"), BOOKS("a book"),
        MUSIC("music"), FISH("fish"), TOOLS("a good tool"), WOOL("wool"), GOLD("gold"),
        SPOOKY("spider eyes"), ROTTEN("rotten flesh");

        public final String words;

        Gift(String words) { this.words = words; }
    }

    private static final String[] QUIRKS = {
        "hums while working", "collects pretty stones", "never misses a sunrise",
        "talks to the chickens", "is afraid of the dark", "counts everything twice",
        "can't sit still", "keeps a diary", "names every tool", "hates the rain",
        "loves the rain", "tells terrible jokes", "always knows the time", "whistles badly",
    };

    private static final String[] FOODS = {
        "bread", "baked_potato", "cookie", "pumpkin_pie", "apple", "cooked_cod",
        "cooked_salmon", "sweet_berries", "honey_bottle", "golden_carrot", "cake", "mushroom_stew",
    };

    /** What a folk remembers. */
    public record Memory(long day, String text, int weight) {}

    /** What a folk thinks of one player. */
    public static final class Opinion {
        public String name;
        public int affinity;
        long lastTalkDay = -1;
        long lastGiftDay = -1;
        int giftsToday;
        long lastFavourDay = -1;

        Opinion(String name, int affinity) {
            this.name = name;
            this.affinity = affinity;
        }
    }

    Hobby hobby = Hobby.WALKING;
    Ambition ambition = Ambition.FRIENDS;
    boolean ambitionMet;
    String quirk = "";
    String food = "bread";
    Gift loves = Gift.FLOWERS;
    Gift hates = Gift.ROTTEN;
    /** Day it came into the world, or into this village. */
    long since = -1;
    /** "a founder of the village", "born here", "came with the colony". */
    String origin = "";
    final Deque<Memory> memories = new ArrayDeque<>();
    final Map<UUID, Opinion> players = new HashMap<>();
    /** Day on which a hobby was last enjoyed. */
    long hobbyDay = -1;
    long sleptDay = -1;
    long hurtDay = -100;
    long giftDay = -100;
    String giftFrom = "";
    int flowersPlanted;

    // Today's mood: worked out from the folk's life, never saved.
    int mood = 60;
    final List<String> moodWhy = new ArrayList<>(3);

    public boolean rolled() { return !quirk.isEmpty(); }

    public Hobby hobby() { return hobby; }
    public Ambition ambition() { return ambition; }
    public boolean ambitionMet() { return ambitionMet; }
    public String quirk() { return quirk; }
    public String food() { return food; }
    public Gift loves() { return loves; }
    public Gift hates() { return hates; }
    public long since() { return since; }
    public String origin() { return origin; }
    public int mood() { return mood; }
    public List<String> moodWhy() { return moodWhy; }
    public long hobbyDay() { return hobbyDay; }
    public int flowersPlanted() { return flowersPlanted; }

    /**
     * Who this folk is on its own: a hobby that fits its temperament and trade, a
     * quirk, a favourite food, what it loves and hates to be given, and a hope.
     */
    public void roll(RandomSource r, Social.Life life, AssistantEntity.StationTask trade, long today, String origin) {
        Hobby[] all = Hobby.values();
        Hobby h = all[r.nextInt(all.length)];
        if (life.has(Social.Trait.SOCIABLE) && r.nextBoolean()) h = r.nextBoolean() ? Hobby.CARDS : Hobby.MUSIC;
        if (life.has(Social.Trait.SHY) && r.nextBoolean()) h = r.nextBoolean() ? Hobby.READING : Hobby.STARGAZING;
        if (life.has(Social.Trait.CURIOUS) && r.nextInt(3) == 0) h = Hobby.STARGAZING;
        if (trade == AssistantEntity.StationTask.FISH && r.nextInt(3) == 0) h = Hobby.FISHING;
        if (trade == AssistantEntity.StationTask.FARM && r.nextInt(3) == 0) h = Hobby.GARDENING;
        if (trade == AssistantEntity.StationTask.WOOD && r.nextInt(3) == 0) h = Hobby.WHITTLING;
        hobby = h;
        quirk = QUIRKS[r.nextInt(QUIRKS.length)];
        food = FOODS[r.nextInt(FOODS.length)];
        loves = switch (hobby) {
            case GARDENING -> Gift.FLOWERS;
            case READING -> Gift.BOOKS;
            case MUSIC -> Gift.MUSIC;
            case FISHING -> Gift.FISH;
            case STARGAZING -> Gift.GEMS;
            default -> new Gift[]{Gift.FLOWERS, Gift.SWEETS, Gift.GEMS, Gift.TOOLS, Gift.WOOL, Gift.GOLD}[r.nextInt(6)];
        };
        hates = r.nextInt(3) == 0 ? Gift.SPOOKY : Gift.ROTTEN;
        Ambition[] hopes = Ambition.values();
        ambition = hopes[r.nextInt(hopes.length)];
        if (trade == AssistantEntity.StationTask.MINE && r.nextBoolean()) ambition = Ambition.DIAMOND;
        if (life.has(Social.Trait.HARDWORKING) && r.nextBoolean()) ambition = Ambition.MASTER;
        if (life.has(Social.Trait.SOCIABLE) && r.nextBoolean()) ambition = Ambition.FRIENDS;
        if (hobby == Hobby.GARDENING && r.nextBoolean()) ambition = Ambition.GARDEN;
        ambitionMet = false;
        since = today;
        this.origin = origin;
    }

    // ------------------------------------------------------------- memories

    public void remember(long day, String text, int weight) {
        for (Memory m : memories) if (m.text().equals(text)) return;
        memories.addFirst(new Memory(day, text, weight));
        while (memories.size() > 16) {
            // Let the least of the oldest go.
            Memory least = null;
            for (Memory m : memories) if (least == null || m.weight() <= least.weight()) least = m;
            memories.remove(least);
        }
    }

    public List<Memory> memories() { return new ArrayList<>(memories); }

    @Nullable
    public Memory fondest() {
        Memory best = null;
        for (Memory m : memories) if (m.weight() > 0 && (best == null || m.weight() > best.weight())) best = m;
        return best;
    }

    @Nullable
    public Memory latest() { return memories.peekFirst(); }

    // ------------------------------------------------------------- players

    public Opinion opinionOf(UUID player, String name) {
        Opinion o = players.computeIfAbsent(player, k -> new Opinion(name, 0));
        o.name = name;
        if (players.size() > 24) {
            UUID least = null;
            int weakest = Integer.MAX_VALUE;
            for (Map.Entry<UUID, Opinion> e : players.entrySet()) {
                if (e.getKey().equals(player)) continue;
                int w = Math.abs(e.getValue().affinity);
                if (w < weakest) { weakest = w; least = e.getKey(); }
            }
            if (least != null) players.remove(least);
        }
        return o;
    }

    public int affinity(UUID player) {
        Opinion o = players.get(player);
        return o == null ? 0 : o.affinity;
    }

    public void feelFor(UUID player, String name, int delta) {
        Opinion o = opinionOf(player, name);
        o.affinity = Math.max(-100, Math.min(100, o.affinity + delta));
    }

    /** How a folk would put what it thinks of you. */
    public static String standing(int affinity) {
        if (affinity <= -50) return "can't stand you";
        if (affinity <= -15) return "is wary of you";
        if (affinity < 10) return "barely knows you";
        if (affinity < 30) return "knows you";
        if (affinity < 55) return "likes you";
        if (affinity < 80) return "counts you a friend";
        return "thinks the world of you";
    }

    // ------------------------------------------------------------- mood

    public void setMood(int value, List<String> why) {
        mood = Math.max(0, Math.min(100, value));
        moodWhy.clear();
        moodWhy.addAll(why.subList(0, Math.min(3, why.size())));
    }

    public static String moodWord(int mood) {
        if (mood >= 85) return "delighted";
        if (mood >= 70) return "happy";
        if (mood >= 55) return "content";
        if (mood >= 40) return "so-so";
        if (mood >= 25) return "fed up";
        return "miserable";
    }

    public void sleptInABed(long day) { sleptDay = day; }
    public void hurt(long day) { hurtDay = day; }
    public void enjoyedHobby(long day) { hobbyDay = day; }
    public void plantedAFlower() { flowersPlanted++; }

    public void gotAGift(long day, String from) {
        giftDay = day;
        giftFrom = from;
    }

    public void meetAmbition() { ambitionMet = true; }

    // ------------------------------------------------------------- saving

    public void save(CompoundTag tag) {
        tag.putString("Hobby", hobby.name());
        tag.putString("Ambition", ambition.name());
        tag.putBoolean("AmbitionMet", ambitionMet);
        tag.putString("Quirk", quirk);
        tag.putString("Food", food);
        tag.putString("Loves", loves.name());
        tag.putString("Hates", hates.name());
        tag.putLong("Since", since);
        tag.putString("Origin", origin);
        tag.putLong("HobbyDay", hobbyDay);
        tag.putLong("SleptDay", sleptDay);
        tag.putLong("HurtDay", hurtDay);
        tag.putLong("GiftDay", giftDay);
        tag.putString("GiftFrom", giftFrom);
        tag.putInt("Flowers", flowersPlanted);
        ListTag mem = new ListTag();
        for (Memory m : memories) {
            CompoundTag one = new CompoundTag();
            one.putLong("Day", m.day());
            one.putString("Text", m.text());
            one.putInt("Weight", m.weight());
            mem.add(one);
        }
        tag.put("Memories", mem);
        ListTag ops = new ListTag();
        for (Map.Entry<UUID, Opinion> e : players.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID("Id", e.getKey());
            one.putString("Name", e.getValue().name);
            one.putInt("Affinity", e.getValue().affinity);
            one.putLong("Talk", e.getValue().lastTalkDay);
            one.putLong("Gift", e.getValue().lastGiftDay);
            one.putInt("Gifts", e.getValue().giftsToday);
            one.putLong("Favour", e.getValue().lastFavourDay);
            ops.add(one);
        }
        tag.put("Players", ops);
    }

    public void load(CompoundTag tag) {
        hobby = parse(Hobby.class, tag.getString("Hobby"), Hobby.WALKING);
        ambition = parse(Ambition.class, tag.getString("Ambition"), Ambition.FRIENDS);
        ambitionMet = tag.getBoolean("AmbitionMet");
        quirk = tag.getString("Quirk");
        food = tag.contains("Food") ? tag.getString("Food") : "bread";
        loves = parse(Gift.class, tag.getString("Loves"), Gift.FLOWERS);
        hates = parse(Gift.class, tag.getString("Hates"), Gift.ROTTEN);
        since = tag.contains("Since") ? tag.getLong("Since") : -1;
        origin = tag.getString("Origin");
        hobbyDay = tag.contains("HobbyDay") ? tag.getLong("HobbyDay") : -1;
        sleptDay = tag.contains("SleptDay") ? tag.getLong("SleptDay") : -1;
        hurtDay = tag.contains("HurtDay") ? tag.getLong("HurtDay") : -100;
        giftDay = tag.contains("GiftDay") ? tag.getLong("GiftDay") : -100;
        giftFrom = tag.getString("GiftFrom");
        flowersPlanted = tag.getInt("Flowers");
        memories.clear();
        for (Tag t : tag.getList("Memories", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            memories.addLast(new Memory(one.getLong("Day"), one.getString("Text"), one.getInt("Weight")));
        }
        players.clear();
        for (Tag t : tag.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            if (!one.hasUUID("Id")) continue;
            Opinion o = new Opinion(one.getString("Name"), one.getInt("Affinity"));
            o.lastTalkDay = one.getLong("Talk");
            o.lastGiftDay = one.getLong("Gift");
            o.giftsToday = one.getInt("Gifts");
            o.lastFavourDay = one.contains("Favour") ? one.getLong("Favour") : -1;
            players.put(one.getUUID("Id"), o);
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return name.isEmpty() ? fallback : Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
