package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaBond.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [pa35] The bond at work in the game (PaBond holds the rules): events from what its player does, the bond's effects
 * (food brought when its player is low, a better tool handed over, a guard while they stand still, its finds shared, a
 * small gift now and then), its wants and their small buff or sulk, "our story" kept on firsts, close calls, big builds,
 * deaths and anniversaries, and brought up rarely. Every line said to its player alone, never while it's told to be quiet.
 */
public final class PaBondAct {

    private PaBondAct() {}

    /** Its bond's state, with its own favourites settled the first time it's asked. */
    public static PaBond.State state(PaData.Rec r) {
        PaBond.State s = r.bond;
        if (s.favourites.isEmpty()) s.favourites.addAll(PaBond.favouritesFor(r.owner.getMostSignificantBits() ^ r.made ^ r.slot));
        String film = System.getProperty("mca.pa.filmBond", "");             // filming only: a server started with a bond to show
        if (!film.isEmpty() && FILMED.add(r.owner)) { try { s.bond = Double.parseDouble(film); } catch (NumberFormatException ignored) { } }
        return s;
    }

    private static final java.util.Set<UUID> FILMED = new java.util.HashSet<>();

    static long day(PlayerAssistantEntity a) {
        return a.level().getDayTime() / 24000L;
    }

    /** Something its player did that moves the bond. */
    public static void event(PlayerAssistantEntity a, Event e) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        PaBond.State s = state(r);
        int before = s.tier();
        double d = s.apply(e, day(a));
        if (d != 0) PaData.touch();
        int after = s.tier();
        if (after > before && r.mute < 2) {
            PaTalk.reply(a, PaTalk.line(a, "bond.tier_up", "tier", PaBond.TIERS[after]), null, true);
            note(a, null, "We became " + PaBond.TIERS[after].toLowerCase(Locale.ROOT) + "s. Don't tell anyone.");
        }
    }

    /** A moment for our story (a first only once): day, place, line. True when it was new. */
    public static boolean note(PlayerAssistantEntity a, @Nullable String first, String text) {
        PaData.Rec r = a.rec();
        if (r == null) return false;
        String where = PaMemoryAct.placeNear(r, a.blockPosition(), a.level().dimension().location().getPath());
        boolean added = state(r).note(first, day(a), where, text);
        if (added) PaData.touch();
        return added;
    }

    /** A first: its bonus experience and its line in our story. */
    public static void first(PlayerAssistantEntity a, String key, String text) {
        if (!note(a, key, text)) return;
        Integer bonus = PaXp.FIRSTS.get(key);
        if (bonus != null) a.gainXpFirst(bonus);
    }

    // ------------------------------------------------------------------ once a second

    private static final class Watch {
        long togetherAt = 0, starveAt = 0, wantCheckAt = 0, giftDay = -1, shareAt = -100000, toolAt = -100000, storyAt = 0, stillFor = 0, closeDay = -1;
        int lastDiamonds = -1, lastIron = -1;
        net.minecraft.world.phys.Vec3 ownerLast;
        boolean guarding, sleptNight;
    }

    private static final Map<UUID, Watch> WATCH = new HashMap<>();

    public static void tick(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || !(a.getOwnerPlayer() instanceof ServerPlayer owner)) return;
        PaBond.State s = state(r);
        Watch w = WATCH.computeIfAbsent(a.getUUID(), k -> new Watch());
        long now = a.level().getGameTime();
        s.lastSeen = System.currentTimeMillis();
        boolean near = owner.level() == a.level() && owner.distanceToSqr(a) < 16 * 16;
        int tier = s.tier();
        if (near && now >= w.togetherAt) { w.togetherAt = now + 12000; event(a, Event.TOGETHER); }
        if (a.hunger.food() == 0 && now >= w.starveAt) { w.starveAt = now + 1200; event(a, Event.STARVING); }
        // firsts it notices itself
        String dim = a.level().dimension().location().getPath();
        if (dim.equals("the_nether")) first(a, "nether", "Our first trip to the Nether. It was as warm as advertised.");
        if (dim.equals("the_end")) first(a, "end", "We went to the End. The view's better than the welcome.");
        int dia = PaItems.count(a, "diamond");
        if (w.lastDiamonds >= 0 && dia > w.lastDiamonds) {
            first(a, "diamond", "Our first diamond. I've kept the receipt.");
            if (PaBond.shares(tier) && now - w.shareAt > 6000 && r.mute < 2) {
                w.shareAt = now;
                PaTalk.reply(a, PaTalk.line(a, "bond.share", "what", "diamonds"), null, true);
            }
        }
        w.lastDiamonds = dia;
        int iron = PaItems.count(a, "iron");
        if (w.lastIron >= 0 && iron > w.lastIron) first(a, "iron", "First iron. The age of stone tools is over.");
        w.lastIron = iron;
        // a close call: badly hurt and still standing
        if (a.getHealth() > 0 && a.getHealth() < a.getMaxHealth() * 0.15F && w.closeDay != day(a)) {
            w.closeDay = day(a);
            note(a, null, "A close call" + (a.getLastHurtByMob() != null ? " with " + ("aeiou".indexOf(a.getLastHurtByMob().getName().getString().toLowerCase(Locale.ROOT).charAt(0)) >= 0 ? "an " : "a ")
                + a.getLastHurtByMob().getName().getString().toLowerCase(Locale.ROOT) : "") + ". I'd rather not do that again.");
        }
        // sleeping near each other
        if (owner.isSleeping() && a.isSleeping() && owner.distanceToSqr(a) < 16 * 16 && !w.sleptNight) { w.sleptNight = true; event(a, Event.SLEPT_NEAR); }
        if (!owner.isSleeping()) w.sleptNight = false;
        // its player standing still: a guard over them (Good Mate up)
        if (w.ownerLast != null && owner.position().distanceToSqr(w.ownerLast) < 0.01) w.stillFor += 20; else { w.stillFor = 0; w.guarding = false; }
        w.ownerLast = owner.position();
        int afk = PaBond.guardsAfkAfter(tier);
        if (afk > 0 && near && w.stillFor >= afk * 20L && !w.guarding && a.plan.idle() && a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.FOLLOW) {
            w.guarding = true;
            a.setMode(com.jrpetty.mcassistant.entity.AssistantEntity.Mode.GUARD);
            if (r.mute < 2) PaTalk.comment(a, 1, PaTalk.line(a, "bond.guarding"), true, true);
        }
        // food when its player is low (more readily as the bond grows), even with auto-help off
        int foodAt = PaBond.bringsFoodAt(tier);
        if (foodAt > 0 && near && owner.getFoodData().getFoodLevel() <= foodAt && a.countFood() > 0 && a.plan.idle() && !a.mind.vetoed.contains("food")) {
            boolean has = false;
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++) if (owner.getInventory().getItem(i).has(net.minecraft.core.component.DataComponents.FOOD)) has = true;
            if (!has) {
                PaPlan.Step st = PaOrders.step("bring you some food", PaIntent.Kind.BRING_FOOD, 20);
                st.auto = true;
                st.jobs.add(com.jrpetty.mcassistant.entity.Job.give(PaMemoryAct.foodWord(a), 4));
                a.plan.steps.add(st);
                if (r.mute < 2) PaTalk.comment(a, 1, PaTalk.line(a, "bond.food"), true, true);
            }
        }
        // a better tool than the one in its player's hand (Good Mate up), once in a while
        if (PaBond.handsTools(tier) && near && now - w.toolAt > 24000 && owner.distanceToSqr(a) < 6 * 6) {
            ItemStack held = owner.getMainHandItem();
            if (held.getItem() instanceof TieredItem ht) {
                for (int i = 0; i < a.getInventoryItems().size(); i++) {
                    ItemStack c = a.getInventoryItems().get(i);
                    if (!(c.getItem() instanceof TieredItem ct) || c.getItem().getClass() != held.getItem().getClass()) continue;
                    if (ct.getTier().getSpeed() <= ht.getTier().getSpeed() || r.keep.contains(BuiltInRegistries.ITEM.getKey(c.getItem()).toString())) continue;
                    w.toolAt = now;
                    ItemStack give = c.copy();
                    a.getInventoryItems().set(i, ItemStack.EMPTY);
                    String name = give.getHoverName().getString().toLowerCase(Locale.ROOT);   // before it moves
                    if (!owner.getInventory().add(give)) owner.drop(give, false);
                    if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "bond.tool", "item", name), null, true);
                    break;
                }
            }
        }
        // wants: rare, one at a time, never while hushed
        wants(a, r, s, w, now);
        // a small gift now and then (Best Mate up), once a game day at most
        if (near && w.giftDay != day(a) && owner.distanceToSqr(a) < 8 * 8 && a.plan.idle()) {
            w.giftDay = day(a);
            boolean film = Boolean.getBoolean("mca.pa.filmGift");           // filming only: today's gift for certain (a flower)
            if (film && tier >= 3 || a.getRandom().nextDouble() < PaBond.giftChance(tier)) gift(a, owner, r, s);
        }
        // anniversaries, and now and then a memory from our story
        long days = (a.level().getGameTime() - r.made) / 24000L;
        for (int mark : new int[]{10, 30, 100, 365}) if (days >= mark) {
            if (note(a, "days" + mark, "We've been mates for " + mark + " days. Nobody else would put up with either of us.") && r.mute < 2)
                PaTalk.reply(a, PaTalk.line(a, "bond.anniversary", "n", Integer.toString(mark)), null, true);
        }
        boolean filmStory = Boolean.getBoolean("mca.pa.filmStory");            // filming only: a story line every half minute or so
        if (near && now >= w.storyAt && s.story.size() >= (filmStory ? 1 : 3)) {
            boolean first = w.storyAt == 0 && !filmStory;
            w.storyAt = now + (filmStory ? 600 : 48000 + a.getRandom().nextInt(48000));
            if (!first && r.mute == 0 && r.chatty >= 1 && (filmStory || a.getRandom().nextInt(100) < 25)) {
                String[] e = s.story.get(a.getRandom().nextInt(Math.max(1, s.story.size() - 1)));
                String said = PaTalk.line(a, "bond.recall", "day", e[0], "what", lower(e[2]));
                if (filmStory) PaTalk.reply(a, said, null, true); else PaTalk.comment(a, 2, said, false, true);   // filming: past the chatter limit
            }
        }
    }

    private static String lower(String s) {
        if (s.isEmpty()) return s;
        String t = s.replaceAll("\\.$", "");
        boolean keep = t.startsWith("I ") || t.startsWith("I'") || t.length() > 1 && Character.isUpperCase(t.charAt(1));   // "I", and names or initials, stay as they are
        return keep ? t : Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }

    static void wants(PlayerAssistantEntity a, PaData.Rec r, PaBond.State s, Watch w, long now) {
        if (!s.want.isEmpty()) {
            boolean met = switch (s.want.split(":")[0]) {
                case "bed" -> r.bed != null || r.lastBed != null;
                case "chest" -> a.findChestWith(st -> true, 8) != null;
                default -> { Item it = item(s.want.substring(s.want.indexOf(':') + 1)); yield it != null && a.countMatching(st -> st.is(it)) > 0; }
            };
            if (met) {
                s.want = "";
                s.wantAgainAt = now + PaBond.WANT_COOLDOWN;
                event(a, Event.WANT_MET);
                a.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 0));
                a.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0));
                if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "bond.want_met"), null, true);
            } else if (now - s.wantSince > PaBond.WANT_LASTS) {
                s.want = "";
                s.wantAgainAt = now + PaBond.WANT_COOLDOWN;
                event(a, Event.WANT_IGNORED);
                a.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1200, 0));
                if (r.mute == 0) PaTalk.comment(a, 1, PaTalk.line(a, "bond.want_ignored"), false, true);
            }
            PaData.touch();
            return;
        }
        if (now < w.wantCheckAt) return;
        w.wantCheckAt = now + 12000;
        if (!PaBond.mayWant(s, r.mute, now, a.getRandom().nextDouble())) return;
        String kind, line;
        if (r.bed == null && r.lastBed == null) { kind = "bed"; line = PaTalk.line(a, "bond.want.bed"); }
        else if (a.findChestWith(st -> true, 8) == null && PaOrders.homeOf(a) != null) { kind = "chest"; line = PaTalk.line(a, "bond.want.chest"); }
        else { String f = s.favourites.get(0); kind = "item:" + f; line = PaTalk.line(a, "bond.want.food", "item", PaBond.said(f).replaceFirst("^(a|an|some) ", "")); }
        s.want = kind;
        s.wantSince = now;
        PaData.touch();
        PaTalk.reply(a, line, null, true);
    }

    static Item item(String path) {
        ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + path);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
    }

    static void gift(PlayerAssistantEntity a, ServerPlayer owner, PaData.Rec r, PaBond.State s) {
        int pick = Boolean.getBoolean("mca.pa.filmGift") ? 0 : a.getRandom().nextInt(3);
        ItemStack g;
        if (pick == 0) g = new ItemStack(Items.POPPY);
        else if (pick == 1) g = new ItemStack(Items.COOKED_BEEF, 2);
        else {
            g = new ItemStack(Items.FLINT);
            g.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(a.displayNameCap() + "'s lucky flint"));
        }
        String what = PaBond.said(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(g.getItem()).getPath());   // named before it moves (an added stack is left empty)
        if (g.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) what = g.getHoverName().getString();
        if (!owner.getInventory().add(g)) owner.drop(g, false);
        if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "bond.gift", "item", what), null, true);
        note(a, null, "I gave you " + what + ". You'd better still have it.");
    }

    // ------------------------------------------------------------------ events from elsewhere

    /** Its player put something in its pack: food, a gift, its favourite, kit; a locked weapon is said so. */
    public static void given(PlayerAssistantEntity a, ItemStack s) {
        PaData.Rec r = a.rec();
        if (r == null || s.isEmpty()) return;
        PaBond.State st = state(r);
        String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        int need = PaLevels.gearLevel(s);
        if (need > a.veteranLevel() && r.mute < 2)
            PaTalk.reply(a, PaTalk.line(a, "gear.locked", "item", s.getHoverName().getString().toLowerCase(Locale.ROOT), "level", Integer.toString(need)), null, true);
        if (st.favourites.contains(path)) { event(a, Event.FAVOURITE_GIFT); if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "bond.favourite", "item", s.getHoverName().getString().toLowerCase(Locale.ROOT)), null, true); }
        else if (s.has(net.minecraft.core.component.DataComponents.FOOD)) event(a, s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) ? Event.HEALED : Event.FED);
        else if (s.getItem() instanceof ArmorItem || s.getItem() instanceof TieredItem) event(a, Event.KITTED);
        else if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION)) event(a, Event.HEALED);
        else event(a, Event.GIFT);
    }

    /** It fell: a little experience (never a level), a dip in the bond, a line in our story. */
    public static void died(PlayerAssistantEntity a, String how) {
        int lv = a.veteranLevel();
        long lo = PaLevels.xpFor(lv), xp = a.paXp();
        long lose = Math.max(0, Math.round((xp - lo) * 0.10));
        if (lose > 0) a.setPaXpForTests(Math.max(lo, xp - lose));
        event(a, Event.DIED);
        note(a, null, "I was knocked out by " + how + ". Not my finest hour.");
    }

    /** A monster that was after it, killed by its player: they saved it. */
    public static void killed(LivingEntity victim, @Nullable net.minecraft.world.entity.Entity killer) {
        if (!(victim instanceof Mob m) || !(killer instanceof ServerPlayer p)) return;
        if (m.getTarget() instanceof PlayerAssistantEntity a && a.isOwner(p)) {
            event(a, Event.SAVED);
            if (a.getHealth() < a.getMaxHealth() * 0.4F) note(a, null, "You saved me from " + ("aeiou".indexOf(m.getName().getString().toLowerCase(Locale.ROOT).charAt(0)) >= 0 ? "an " : "a ")
                + m.getName().getString().toLowerCase(Locale.ROOT) + ". I owe you one.");
        }
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).getPath();
        if (path.equals("ender_dragon") || path.equals("wither") || path.equals("elder_guardian") || path.equals("warden")) {
            for (PlayerAssistantEntity a : victim.level().getEntitiesOfClass(PlayerAssistantEntity.class, victim.getBoundingBox().inflate(96))) {
                if (a.isOwner(p) || killer == a) first(a, "boss", "We beat the " + victim.getName().getString().toLowerCase(Locale.ROOT) + ". Mostly you. A bit me.");
            }
        }
    }

    /** Its player came back after days away: the bond slips a little for each day past three. */
    public static void returned(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        PaBond.State s = state(r);
        if (s.lastSeen > 0) {
            long days = (System.currentTimeMillis() - s.lastSeen) / 86_400_000L;
            for (long d = 3; d < Math.min(days, 8); d++) s.apply(Event.ABANDONED, -1000 - d);   // its own "day" per missed day, so each counts once
        }
        s.lastSeen = System.currentTimeMillis();
    }

    /** Game tests: forget the watch. */
    public static void resetForTests() {
        WATCH.clear();
    }

    /** Its state for the panel: bond, step, progress, effects, raises, story, want, favourites. */
    public static void state(net.minecraft.nbt.CompoundTag t, PaData.Rec r) {
        PaBond.State s = state(r);
        t.putFloat("bond", (float) s.bond);
        t.putInt("bondTier", s.tier());
        t.putFloat("bondNext", (float) PaBond.progress(s.bond));
        net.minecraft.nbt.ListTag eff = new net.minecraft.nbt.ListTag();
        for (String e : PaBond.effects(s.tier())) eff.add(net.minecraft.nbt.StringTag.valueOf(e));
        t.put("bondEffects", eff);
        net.minecraft.nbt.ListTag story = new net.minecraft.nbt.ListTag();
        for (int i = s.story.size() - 1; i >= 0; i--) {
            String[] e = s.story.get(i);
            story.add(net.minecraft.nbt.StringTag.valueOf("Day " + e[0] + (e[1].isEmpty() ? "" : ", " + e[1]) + ": " + e[2]));
        }
        t.put("story", story);
        t.putString("want", s.want.isEmpty() ? "" : s.want.startsWith("item:") ? PaBond.said(s.want.substring(5)) : s.want.equals("bed") ? "a bed of its own" : "a chest of its own");
        net.minecraft.nbt.ListTag fav = new net.minecraft.nbt.ListTag();
        for (String f : s.favourites) fav.add(net.minecraft.nbt.StringTag.valueOf(f.replace('_', ' ')));
        t.put("itsFavourites", fav);
    }
}
