package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [individual] Every folk its own person: its face and body ({@link Looks}), the marks its life leaves on it, the way
 * it moves and sounds ({@link Manner}), and a life of its own beyond its work and its friends.
 *
 * <ul>
 * <li><b>A face and a body of its own,</b> taken from its parents (Looks), and the marks of a life: a scar across the
 *     cheek from a real wound (a monster's, a raider's, a blast), an eyepatch, rarely, for an eye lost in a raid;
 *     spectacles once its eyes go, for the old and bookish, made by the smith (IndividualItems); soot on a smith or a
 *     smelter at its work, sunburn on a fair farmer in summer; a stoop in its eighties and a walking stick, a real one
 *     out of the stores, for the oldest.</li>
 * <li><b>Fears</b> ({@link Fears}), with what they make it do, and overcome by living through them.</li>
 * <li><b>Habits</b> and <b>a favourite place</b> ({@link Habits}): a morning walk, a pipe on the step at dusk, the
 *     birds fed, a book before bed; the bench by the well, the quay, the hill.</li>
 * <li><b>A dream</b> ({@link Dreams}): the life goal it works towards (Persona's ambition, grown), and changes.</li>
 * <li><b>A keepsake</b> ({@link Keepsakes}): a real thing it carries and will not part with.</li>
 * <li><b>Where it came from</b> ({@link Backstory}), whether it can read and what it likes to read, which hand it
 *     works with, and its favourite season.</li>
 * </ul>
 *
 * <p>All of it carried on the folk and saved with it ({@link Self}), worked out on its own slow clock: the look once
 * every five seconds at most and sent to the client only when it changes, the rest on the folk's free time.
 */
public final class Individual {

    private Individual() {}

    /** One folk's own: saved with it (VillageFolkEntity "Individual"). */
    public static final class Self {
        public final Looks.Genes genes = new Looks.Genes();
        /** The marks: a scar (Looks.SCAR_WORDS) and how and when it got it; an eye lost (1 right, 2 left). */
        public int scar, patch;
        public String scarHow = "";
        public long scarDay = -1;
        public final EnumSet<Fears.Fear> fears = EnumSet.noneOf(Fears.Fear.class);
        public final EnumMap<Fears.Fear, Integer> courage = new EnumMap<>(Fears.Fear.class);
        /** The fears it has got over, in words, for its story ("the dark, on day 31"). */
        public final List<String> overcome = new ArrayList<>();
        public final List<Habits.Habit> habits = new ArrayList<>();
        public Habits.Place place = Habits.Place.WELL;
        public Keepsakes.Kind keepsake = Keepsakes.Kind.FEATHER;
        /** Its keepsake's story ("from its mother, Ash"), and the day it came by it (-1 while it has not yet). */
        public String keepsakeStory = "";
        public long keepsakeDay = -1;
        public boolean literate, schooled;
        /** Its favourite book, if it reads. */
        public String book = "";
        public Seasons.Season season = Seasons.Season.SPRING;
        /** Where it was born and who raised it, for its story. */
        public String born = "", raised = "";
        /** Whom it lost that it visits on the day of rest (Habits.GRAVE), and their grave's place in the town's list. */
        public String mourns = "";
        /** The day its dream came true (Dreams), the dreams it had before, and what it has done that dreams ask. */
        public long dreamMetDay = -1;
        public final List<String> pastDreams = new ArrayList<>();
        public boolean seenSea, beenNether;
        public boolean rolled;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            CompoundTag g = new CompoundTag();
            genes.save(g);
            t.put("Genes", g);
            t.putInt("Scar", scar);
            t.putInt("Patch", patch);
            t.putString("ScarHow", scarHow);
            t.putLong("ScarDay", scarDay);
            ListTag fs = new ListTag();
            for (Fears.Fear f : fears) fs.add(StringTag.valueOf(f.name()));
            t.put("Fears", fs);
            CompoundTag c = new CompoundTag();
            for (var e : courage.entrySet()) c.putInt(e.getKey().name(), e.getValue());
            t.put("Courage", c);
            t.put("Overcome", strings(overcome));
            ListTag hs = new ListTag();
            for (Habits.Habit h : habits) hs.add(StringTag.valueOf(h.name()));
            t.put("Habits", hs);
            t.putString("Place", place.name());
            t.putString("Keepsake", keepsake.name());
            t.putString("KeepsakeStory", keepsakeStory);
            t.putLong("KeepsakeDay", keepsakeDay);
            t.putBoolean("Literate", literate);
            t.putBoolean("Schooled", schooled);
            t.putString("Book", book);
            t.putString("Season", season.name());
            t.putString("Born", born);
            t.putString("Raised", raised);
            t.putString("Mourns", mourns);
            t.putLong("DreamMet", dreamMetDay);
            t.put("PastDreams", strings(pastDreams));
            t.putBoolean("SeenSea", seenSea);
            t.putBoolean("BeenNether", beenNether);
            t.putBoolean("Rolled", rolled);
            return t;
        }

        public void load(CompoundTag t) {
            genes.load(t.getCompound("Genes"));
            scar = Math.max(0, Math.min(3, t.getInt("Scar")));
            patch = Math.max(0, Math.min(2, t.getInt("Patch")));
            scarHow = t.getString("ScarHow");
            scarDay = t.contains("ScarDay") ? t.getLong("ScarDay") : -1;
            fears.clear();
            for (Tag x : t.getList("Fears", Tag.TAG_STRING)) {
                Fears.Fear f = parse(Fears.Fear.class, x.getAsString());
                if (f != null) fears.add(f);
            }
            courage.clear();
            CompoundTag c = t.getCompound("Courage");
            for (String k : c.getAllKeys()) {
                Fears.Fear f = parse(Fears.Fear.class, k);
                if (f != null) courage.put(f, c.getInt(k));
            }
            overcome.clear();
            for (Tag x : t.getList("Overcome", Tag.TAG_STRING)) overcome.add(x.getAsString());
            habits.clear();
            for (Tag x : t.getList("Habits", Tag.TAG_STRING)) {
                Habits.Habit h = parse(Habits.Habit.class, x.getAsString());
                if (h != null && !habits.contains(h)) habits.add(h);
            }
            Habits.Place p = parse(Habits.Place.class, t.getString("Place"));
            place = p == null ? Habits.Place.WELL : p;
            Keepsakes.Kind k = parse(Keepsakes.Kind.class, t.getString("Keepsake"));
            keepsake = k == null ? Keepsakes.Kind.FEATHER : k;
            keepsakeStory = t.getString("KeepsakeStory");
            keepsakeDay = t.contains("KeepsakeDay") ? t.getLong("KeepsakeDay") : -1;
            literate = t.getBoolean("Literate");
            schooled = t.getBoolean("Schooled");
            book = t.getString("Book");
            Seasons.Season s = parse(Seasons.Season.class, t.getString("Season"));
            season = s == null ? Seasons.Season.SPRING : s;
            born = t.getString("Born");
            raised = t.getString("Raised");
            mourns = t.getString("Mourns");
            dreamMetDay = t.contains("DreamMet") ? t.getLong("DreamMet") : -1;
            pastDreams.clear();
            for (Tag x : t.getList("PastDreams", Tag.TAG_STRING)) pastDreams.add(x.getAsString());
            seenSea = t.getBoolean("SeenSea");
            beenNether = t.getBoolean("BeenNether");
            rolled = t.getBoolean("Rolled") && genes.rolled;
        }

        private static ListTag strings(List<String> l) {
            ListTag out = new ListTag();
            for (String s : l) out.add(StringTag.valueOf(s));
            return out;
        }
    }

    @Nullable
    static <E extends Enum<E>> E parse(Class<E> type, String name) {
        try {
            return name == null || name.isEmpty() ? null : Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ born, grown, come from away

    /**
     * Settled the first time anybody needs to know (and for a folk from before there were any): a founder's face from
     * its own id and its town's land, and its own life rolled.
     */
    public static void ensure(VillageFolkEntity f) {
        Self s = f.individual();
        if (s.rolled || f.level().isClientSide) return;
        if (!s.genes.rolled) Looks.founder(s.genes, f.getUUID(), f.ownerId() == null ? null : Homeland.known(f.ownerId()));
        RandomSource r = RandomSource.create(f.getUUID().getLeastSignificantBits() * 17L ^ f.getUUID().getMostSignificantBits());
        if (s.born.isEmpty()) {
            boolean ours = !f.life().parents().isEmpty();
            s.born = ours ? "born here in " + town(f) : Backstory.birthplace(f, r);
            s.raised = ours ? "by " + f.life().parents() : Backstory.raisedBy(r);
        }
        if (s.habits.isEmpty()) rollLife(f, s, r, null, null);
        Dreams.shape(f, r);
        s.rolled = true;
        f.setLeftHanded(s.genes.leftHanded);
    }

    /** A child of these two, born now: its looks from them, its own life rolled. (VillageFolkEntity.bear) */
    public static void born(VillageFolkEntity child, VillageFolkEntity a, VillageFolkEntity b) {
        ensure(a);
        ensure(b);
        Self s = child.individual();
        Looks.child(s.genes, a.individual().genes, b.individual().genes, child.getRandom());
        s.born = "born here in " + town(child);
        s.raised = "by " + a.displayNameCap() + " and " + b.displayNameCap();
        rollLife(child, s, child.getRandom(), a, b);
        s.rolled = true;
        child.setLeftHanded(s.genes.leftHanded);
        refreshLook(child);
    }

    /** Born together: twins are alike in their looks, whatever else they make of themselves. (VillageFolkEntity.raise) */
    public static void twins(List<VillageFolkEntity> brood) {
        if (brood.size() < 2) return;
        Looks.Genes first = brood.get(0).individual().genes;
        for (int i = 1; i < brood.size(); i++) {
            VillageFolkEntity t = brood.get(i);
            t.individual().genes.copyFrom(first);
            t.setLeftHanded(first.leftHanded);
            refreshLook(t);
        }
    }

    /** Its own life, rolled: fears, habits, a favourite place, a keepsake, whether it reads, a favourite season. */
    static void rollLife(VillageFolkEntity f, Self s, RandomSource r, @Nullable VillageFolkEntity a, @Nullable VillageFolkEntity b) {
        boolean child = f.isBaby() || a != null;
        Fears.roll(f, s, r, child);
        Habits.roll(f, s, r);
        Keepsakes.roll(f, s, r, a, b);
        if (child) {
            s.literate = false;
        } else {
            boolean bookish = f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING || f.life().has(Social.Trait.CURIOUS);
            StationTask t = f.stationTask();
            s.literate = bookish || t == StationTask.ENCHANT || t == StationTask.STORE || t == StationTask.SHOP
                || t == StationTask.BANK || r.nextInt(10) < 7;
            s.schooled = s.literate && r.nextInt(3) != 0;
        }
        if (s.literate && s.book.isEmpty()) s.book = Backstory.favouriteBook(f, r);
        Seasons.Season[] all = Seasons.Season.values();
        s.season = all[r.nextInt(all.length)];
    }

    /** Grown up (VillageFolkEntity.childhood): its hair and beard its own choice, its letters, a child's fears outgrown. */
    public static void cameOfAge(VillageFolkEntity f, long day) {
        Self s = f.individual();
        ensure(f);
        Looks.comeOfAge(s.genes, f.getRandom());
        UUID village = f.ownerId();
        boolean school = village != null && Villages.hasBuilt(village, "school");
        int readers = 0;
        for (UUID p : f.parentIds()) {
            if (f.level() instanceof ServerLevel sl && sl.getEntity(p) instanceof VillageFolkEntity parent && parent.individual().literate) readers++;
        }
        s.schooled = school;
        s.literate = school || f.getRandom().nextInt(10) < 3 + 3 * readers;
        if (s.literate && s.book.isEmpty()) s.book = Backstory.favouriteBook(f, f.getRandom());
        if (s.fears.contains(Fears.Fear.DARK) && f.getRandom().nextInt(3) != 0) {       // most outgrow it
            s.fears.remove(Fears.Fear.DARK);
            s.overcome.add("the dark, growing up");
        }
        Habits.roll(f, s, f.getRandom());
        refreshLook(f);
    }

    static String town(VillageFolkEntity f) {
        return f.ownerId() == null ? "a village long gone" : Villages.name(f.ownerId());
    }

    // ------------------------------------------------------------------ its tick

    /** From the folk's tick (VillageFolkEntity.aiStep), every tick: each part on its own slow clock. */
    public static void tick(VillageFolkEntity f) {
        if (f.isShowcase()) {
            IndividualStage.tick(f);                       // a pipe going on the stage
            if (f.tickCount % 40 != 1) return;
        }
        if (f.tickCount % 100 == 41) {
            ensure(f);
            refreshLook(f);
        }
        if (!(f.level() instanceof ServerLevel level) || f.isShowcase()) return;
        if (f.tickCount % 10 == 6) Manner.tick(f, level);
        if (f.tickCount % 200 == 87) Keepsakes.tick(f, level);
        if (f.tickCount % 400 == 133) {
            Dreams.tick(f, level);
            Fears.daily(f, level);
        }
    }

    /** True while its own life has it busy (a fear, a habit, its favourite place): the rest of its day waits. */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (!f.individual().rolled || f.isShowcase() || f.isHired()) return false;
        if (Fears.hold(f, level)) return true;
        return Habits.hold(f, level);
    }

    /** Between hold's looks (VillageFolkEntity.calledAway): is it about its own life, so its work waits? */
    public static boolean busy(VillageFolkEntity f) {
        return Fears.busy(f) || Habits.busy(f);
    }

    // ------------------------------------------------------------------ its looks, sent

    /** Its face and its marks as they are now, sent to whoever can see it when (and only when) they change. */
    public static void refreshLook(VillageFolkEntity f) {
        Self s = f.individual();
        if (!s.genes.rolled || f.level().isClientSide) return;
        StationTask t = f.stationTask();
        boolean child = f.isBaby();
        int age = child ? 0 : f.ageYears();
        long look = Looks.pack(s.genes, age, child, t, t == StationTask.NONE ? 0 : f.tradeLevel(t));
        f.showLook(look, marks(f, s, age, child));
        if (f.isLeftHanded() != s.genes.leftHanded) f.setLeftHanded(s.genes.leftHanded);
    }

    /** Its height's share of its hitbox: half as much lower as it is shorter, never taller than a villager's. */
    public static float hitboxScale(float height) {
        return Math.min(1.0F, 1.0F - (1.0F - height) * 0.5F);
    }

    // The marks, packed: scar 2 bits, patch 2, spectacles, soot, sun, stick, stoop 2, a child's growth 3, its clothes.
    static final int M_SCAR = 0, M_PATCH = 2, M_SPECS = 4, M_SOOT = 5, M_SUN = 6, M_STICK = 7, M_STOOP = 8, M_GROWTH = 10,
        M_SHIRT = 13, M_TROUSERS = 16, M_SHOES = 19;

    static int marks(VillageFolkEntity f, Self s, int age, boolean child) {
        int m = s.scar << M_SCAR | s.patch << M_PATCH | IndividualStage.FORCED.getOrDefault(f.getUUID(), 0);
        if (Keepsakes.wearsSpectacles(f)) m |= 1 << M_SPECS;
        if (sooty(f)) m |= 1 << M_SOOT;
        if (sunburnt(f, s)) m |= 1 << M_SUN;
        if (Keepsakes.hasStick(f)) m |= 1 << M_STICK;
        m |= (age >= 95 ? 3 : age >= 85 ? 2 : age >= 76 ? 1 : 0) << M_STOOP;
        if (child && f.bornDay() != VillageFolkEntity.UNKNOWN) {
            double days = f.level().getDayTime() / 24000.0 - f.bornDay();
            m |= Math.max(0, Math.min(7, (int) (days * 8 / VillageFolkEntity.GROW_DAYS))) << M_GROWTH;
        }
        long id = f.getUUID().getMostSignificantBits() ^ f.getUUID().getLeastSignificantBits() >>> 7;
        m |= (int) Math.floorMod(id, 8L) << M_SHIRT;
        m |= (int) Math.floorMod(id >>> 5, 6L) << M_TROUSERS;
        m |= (int) Math.floorMod(id >>> 11, 4L) << M_SHOES;
        return m;
    }

    public static int scarOf(int marks) { return marks >>> M_SCAR & 3; }
    public static int patchOf(int marks) { return marks >>> M_PATCH & 3; }
    public static boolean specsOf(int marks) { return (marks >>> M_SPECS & 1) != 0; }
    public static boolean sootOf(int marks) { return (marks >>> M_SOOT & 1) != 0; }
    public static boolean sunOf(int marks) { return (marks >>> M_SUN & 1) != 0; }
    public static boolean stickOf(int marks) { return (marks >>> M_STICK & 1) != 0; }
    public static int stoopOf(int marks) { return marks >>> M_STOOP & 3; }
    public static int growthOf(int marks) { return marks >>> M_GROWTH & 7; }
    public static int shirtOf(int marks) { return marks >>> M_SHIRT & 7; }
    public static int trousersOf(int marks) { return marks >>> M_TROUSERS & 7; }
    public static int shoesOf(int marks) { return marks >>> M_SHOES & 3; }

    /** A smith or a smelter at its work comes away with soot on its face and hands; supper washes it off. */
    static boolean sooty(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (t != StationTask.SMITH && t != StationTask.SMELT) return false;
        long time = f.level().getDayTime() % 24000L;
        return time >= 1500L && time < 12600L && !f.isBaby();
    }

    /** A fair folk out in the fields all summer goes red across the nose. */
    static boolean sunburnt(VillageFolkEntity f, Self s) {
        StationTask t = f.stationTask();
        boolean outdoors = t == StationTask.FARM || t == StationTask.RANCH || t == StationTask.WOOD || t == StationTask.FISH
            || t == StationTask.HUNT || t == StationTask.SCOUT || t == StationTask.BEEKEEP || t == StationTask.FERRY;
        if (!outdoors || s.genes.tone > 4 || f.isBaby()) return false;
        long day = f.level().getDayTime() / 24000L;
        return Seasons.season(f.ownerId(), day) == Seasons.Season.SUMMER;
    }

    // ------------------------------------------------------------------ a life's marks

    /**
     * Hurt (VillageFolkEntity.hurt): a real wound from a monster, a raider or a blast leaves a scar across its cheek,
     * a heavy one always, a lighter one now and then; and rarely, in a raid, an eye is lost.
     */
    public static void wounded(VillageFolkEntity f, DamageSource source, float amount) {
        if (f.level().isClientSide || f.isShowcase() || amount < 4.0F || f.isDeadOrDying()) return;
        Self s = f.individual();
        ensure(f);
        Entity by = source.getEntity();
        boolean hostile = by instanceof Enemy || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
        if (!hostile) return;
        UUID village = f.ownerId();
        boolean raid = village != null && Raids.underAlarm(village);
        long day = f.level().getDayTime() / 24000L;
        RandomSource r = f.getRandom();
        String how = raid ? "in the raid of day " + (day + 1)
            : f.stationTask() == StationTask.CAVE && f.getY() < 50 ? "in the caves, on day " + (day + 1)
            : by != null ? "fighting off " + FolkTalk.article(by.getType().getDescription().getString().toLowerCase(Locale.ROOT)) + " on day " + (day + 1)
            : "in a blast on day " + (day + 1);
        if (raid && s.patch == 0 && amount >= 8.0F && r.nextInt(12) == 0) {
            s.patch = r.nextBoolean() ? 1 : 2;
            s.scarHow = how;
            s.scarDay = day;
            f.persona().remember(day, "I lost an eye " + how, 9);
            if (village != null) Villages.tell(village, day, f.displayNameCap() + " lost an eye " + how);
            refreshLook(f);
            return;
        }
        if (s.scar != 0) return;
        if (amount >= 8.0F || r.nextInt(6) == 0) {
            s.scar = 1 + r.nextInt(3);
            s.scarHow = how;
            s.scarDay = day;
            f.persona().remember(day, "I got my scar " + how, 6);
            refreshLook(f);
        }
    }

    /** Tests: a scar, as a real wound leaves one. */
    public static int scarForTests(VillageFolkEntity f) { return f.individual().scar; }

    /**
     * Died (VillageFolkEntity.die): its keepsake to its eldest living child, now that child's to carry ("my mother's
     * ring"); and the ones who loved it will visit it on the day of rest.
     */
    public static void died(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.isShowcase()) return;
        Keepsakes.handDown(f, level);
        UUID village = f.ownerId();
        if (village == null) return;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity o) || o == f) continue;
            boolean close = f.getUUID().equals(o.life().partner()) || o.parentIds().contains(f.getUUID())
                || f.parentIds().contains(o.getUUID()) || o.life().affinity(f.getUUID()) >= Social.CLOSE;
            if (close) {
                o.individual().mourns = f.displayNameCap();
                if (!o.isBaby() && o.getRandom().nextBoolean()) Habits.takeUp(o, Habits.Habit.GRAVE);   // out to the grave each day of rest
            }
        }
    }

    // ------------------------------------------------------------------ its mood

    /** Its own life's part in its mood (VillageFolkEntity.refreshMood). */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Self s = f.individual();
        if (!s.rolled) return m;
        if (s.dreamMetDay >= 0 && day - s.dreamMetDay <= 3) { m += 18; why.add(new Object[]{"dreamtrue", 18}); }
        int habit = Habits.mood(f, day);
        if (habit > 0) { m += habit; why.add(new Object[]{"habit", habit}); }
        if (f.ownerId() != null && Seasons.season(f.ownerId(), day) == s.season) { m += 3; why.add(new Object[]{"season", 3}); }
        int scared = Fears.mood(f, day);
        if (scared > 0) { m -= scared; why.add(new Object[]{"scared", scared}); }
        return m;
    }

    // ------------------------------------------------------------------ shown

    /** Its card's "Looks" line: its face and build in words, and the marks of its life. */
    public static String looksLine(VillageFolkEntity f) {
        Self s = f.individual();
        ensure(f);
        if (!s.rolled) return "";
        String looks = Looks.describe(f.clientLook());
        List<String> marks = new ArrayList<>();
        if (s.scar > 0) marks.add("a scar across the " + (s.scar == 3 ? "brow" : Looks.SCAR_WORDS[s.scar]) + ", got " + s.scarHow);
        if (s.patch > 0) marks.add("a patch over its " + (s.patch == 1 ? "right" : "left") + " eye");
        if (Keepsakes.wearsSpectacles(f)) marks.add("spectacles");
        if (Keepsakes.hasStick(f)) marks.add("a walking stick");
        if (sunburnt(f, s)) marks.add("sunburnt");
        if (sooty(f)) marks.add("soot on its face");
        if (!marks.isEmpty()) looks += "; " + String.join("; ", marks);
        if (s.genes.leftHanded) looks += "; left-handed";
        return looks;
    }

    /** Its card's lines (FolkTalk.card): Dream, Fears, Habits, Favourite place, Keepsake, Learning, Favourites. */
    public static List<String[]> cardLines(VillageFolkEntity f) {
        List<String[]> out = new ArrayList<>();
        Self s = f.individual();
        ensure(f);
        if (!s.rolled) return out;
        out.add(new String[]{"Dream", Dreams.cardLine(f)});
        out.add(new String[]{"Fears", Fears.cardLine(f)});
        out.add(new String[]{"Habits", Habits.cardLine(f)});
        out.add(new String[]{"Favourite place", Habits.placeLine(f)});
        out.add(new String[]{"Keepsake", Keepsakes.cardLine(f)});
        out.add(new String[]{"Learning", f.isBaby() ? (s.schooled ? "at school" : "learning its letters at home")
            : !s.literate ? "cannot read" : (s.schooled ? "went to school; " : "taught itself to read; ") + "loves " + s.book});
        out.add(new String[]{"Favourites", Decor.colour(f).getName().replace('_', ' ') + "; "
            + FolkTalk.foodWords(f.persona().food()).replaceFirst("^an? ", "") + "; " + s.season.word});
        out.removeIf(l -> l[1] == null || l[1].isEmpty());
        return out;
    }

    /** "Tell me about yourself": its story, after what it says of itself (FolkTalk.aboutMe). */
    public static String about(VillageFolkEntity f) {
        ensure(f);
        String fear = Fears.talk(f);
        return Backstory.tell(f) + (fear == null ? "" : " " + fear);
    }

    /** What the town says about this one (FolkTalk.gossipFor): "Fen's always up the hill at dusk". */
    public static List<String> gossip(VillageFolkEntity teller) {
        List<String> out = new ArrayList<>();
        UUID village = teller.ownerId();
        if (village == null) return out;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == teller || f.isBaby() || !f.individual().rolled) continue;
            String n = f.displayNameCap();
            Self s = f.individual();
            for (Fears.Fear fear : s.fears) out.add(Fears.gossip(n, fear));
            if (!s.habits.isEmpty()) out.add(Habits.gossip(n, s.habits.get(0), s.place));
            if (s.keepsakeDay >= 0) out.add(n + " never goes anywhere without " + Keepsakes.what(f) + ". " + Keepsakes.story(f) + ".");
            if (!f.persona().ambitionMet()) out.add("Did you know " + n + " wants " + f.persona().ambition().hope + "? Bless them.");
            if (s.scar > 0) out.add(n + " got that scar " + s.scarHow + ". Doesn't like to talk about it.");
            if (out.size() > 40) break;
        }
        return out;
    }

    /** What it adds to an answer (FolkTalk.answer): its keepsake, when asked for one of its own. */
    public static String mention(VillageFolkEntity f, net.minecraft.world.entity.player.Player p, TalkTopic topic, String said) {
        if (topic == TalkTopic.KEEPSAKE && f.individual().keepsakeDay >= 0 && Keepsakes.carried(f) != null && !said.startsWith("A keepsake? We hardly")) {
            return "Not " + Keepsakes.what(f) + " — " + Keepsakes.story(f) + ", and I'd never part with it. " + said;
        }
        return said;
    }

    /** Tests: these fears and no others. */
    public static void fearsForTests(VillageFolkEntity f, Fears.Fear... fs) {
        ensure(f);
        f.individual().fears.clear();
        for (Fears.Fear x : fs) f.individual().fears.add(x);
    }

    /** Tests: these habits and this place. */
    public static void habitsForTests(VillageFolkEntity f, Habits.Place place, Habits.Habit... hs) {
        ensure(f);
        f.individual().habits.clear();
        for (Habits.Habit h : hs) f.individual().habits.add(h);
        f.individual().place = place;
    }

    /** Tests: an item counted as its keepsake. */
    public static boolean isTreasure(ItemStack s) {
        return Keepsakes.isTreasure(s);
    }
}
