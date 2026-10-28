package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [civic-land] A knight's retinue: men of the town's watch who ride at its side.
 *
 * <p>At its knighting a knight is named two of the town's guards, if the watch can spare them (two are always left
 * at home), and may take on two more at its own cost (/village knight retinue hire). They keep their posts in the
 * town until it calls them ("Retinue, to me!", to any folk of the town, or the Knights page): then they leave their
 * watch and go with it, by day and by night, wherever it goes, and fight whatever comes at it, as a hired companion
 * does (Hire). The town pays the first two two coins a day each out of its treasury; the others are paid out of the
 * knight's own purse, a day ahead; unpaid, a man goes home. "Retinue, stand down" sends them back to their posts, and
 * they go back of their own accord when the knight leaves the world. A man of the retinue who dies is dead: the
 * chronicle says where he fell, and the town names another only three days on. Stripped of the title, or the town
 * gone, the retinue is no more.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Retinue {

    private Retinue() {}

    /** Men the town pays for; the most a knight may have; their pay a day each; guards always left at home. */
    public static final int TOWN_PAID = 2, MOST = 4, WAGE = 2, KEEP_HOME = 2;
    /** Days before a fallen man is replaced. */
    static final int MOURN = 3;

    static void resetForTests() {
    }

    // ------------------------------------------------------------------ who

    static List<UUID> men(UUID town, UUID knight) {
        List<UUID> out = new ArrayList<>();
        CompoundTag k = Knights.knight(town, knight);
        if (k == null) return out;
        for (String s : CivicLand.read(CivicLand.list(k, "retinue"))) {
            UUID u = CivicLand.uuid(s);
            if (u != null) out.add(u);
        }
        return out;
    }

    public static int size(UUID town, UUID knight) {
        return men(town, knight).size();
    }

    /** Every guard already in some knight's retinue in this town. */
    static java.util.Set<UUID> taken(UUID town) {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        for (String k : Knights.knights(town).getAllKeys()) {
            UUID p = CivicLand.uuid(k);
            if (p != null) out.addAll(men(town, p));
        }
        return out;
    }

    /** How many more of its watch the town could lend a knight: all but two, less those lent already. */
    public static int spare(UUID town) {
        int watch = 0;
        for (AssistantEntity a : Villages.folkOf(town)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby()) watch++;
        }
        return Math.max(0, watch - KEEP_HOME - taken(town).size());
    }

    /** The men named to a knight, as many as it is owed (two, and those it pays for) and the watch can spare. Their names. */
    static List<String> assign(ServerLevel level, Villages.Village v, UUID knight) {
        UUID id = v.id();
        CompoundTag k = Knights.knight(id, knight);
        List<String> names = new ArrayList<>();
        if (k == null) return names;
        ListTag list = CivicLand.list(k, "retinue");
        int want = Math.min(MOST, TOWN_PAID + k.getInt("extra"));
        java.util.Set<UUID> taken = taken(id);
        List<VillageFolkEntity> guards = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby() && f.isAlive()
                && !taken.contains(f.getUUID()) && !f.isHired()) guards.add(f);
        }
        // The best of them first: the veterans.
        guards.sort(java.util.Comparator.comparingInt((VillageFolkEntity f) -> -f.veteranLevel()));
        int canSpare = spare(id);
        for (VillageFolkEntity g : guards) {
            if (list.size() >= want || canSpare <= 0) break;
            list.add(StringTag.valueOf(g.getUUID().toString()));
            canSpare--;
            names.add(g.displayNameCap());
            g.persona().remember(CivicLand.day(level), "I was named to the retinue of " + Knights.style(knight).word + " " + k.getString("name"), 5);
        }
        for (UUID u : men(id, knight)) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f != null && !names.contains(f.displayNameCap())) names.add(f.displayNameCap());
        }
        CivicLand.changed();
        return names;
    }

    // ------------------------------------------------------------------ called, sent home

    /** "Retinue, to me!": the men leave their posts and come, the day's pay found (the town's, or the knight's). */
    public static String call(ServerLevel level, UUID town, ServerPlayer p) {
        CompoundTag k = Knights.knight(town, p.getUUID());
        if (k == null) return "Only a knight of " + Villages.name(town) + " has a retinue of its watch.";
        Villages.Village v = Villages.get(town);
        if (v == null) return "";
        if (men(town, p.getUUID()).isEmpty()) assign(level, v, p.getUUID());
        List<UUID> men = men(town, p.getUUID());
        if (men.isEmpty()) return "The watch can't spare a man just now: the town keeps two at home. When it has more guards, you'll have yours.";
        if (Raids.underAlarm(town)) return "Not with the bell ringing! The watch is on the walls.";
        List<String> came = new ArrayList<>(), stayed = new ArrayList<>();
        long now = level.getGameTime();
        int i = 0;
        for (UUID u : men) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f == null || !f.isAlive()) { stayed.add("one out of reach"); i++; continue; }
            if (f.isHired() && p.getUUID().equals(f.hiredBy())) { came.add(f.displayNameCap()); i++; continue; }
            if (f.isHired()) { stayed.add(f.displayNameCap() + " (hired out)"); i++; continue; }
            if (!pay(level, town, p, i < TOWN_PAID)) {
                stayed.add(f.displayNameCap() + " (unpaid)");
                i++;
                continue;
            }
            // A blade out of the stores for one with none, as for any hired companion.
            if (f.countCarried(s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)) == 0 && f.villageCentre() != null) {
                f.drawFrom(f.villageCentre(), s -> s.is(ItemTags.SWORDS), 1, Villages.storesRadius(town));
            }
            f.hire(p, now + 24000L + 600L);
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "At your side, " + Knights.style(p.getUUID()).word + " " + p.getName().getString() + "!",
                "Coming! Lead on.", "Your man, " + Knights.style(p.getUUID()).word + "."));
            came.add(f.displayNameCap());
            i++;
        }
        k.putBoolean("out", !came.isEmpty());
        k.putLong("paidDay", CivicLand.day(level) + 1);
        CivicLand.changed();
        return (came.isEmpty() ? "Nobody could come" : CivicLand.names(came) + (came.size() == 1 ? " rides" : " ride") + " with you")
            + (stayed.isEmpty() ? "." : "; " + CivicLand.names(stayed) + " stayed behind.");
    }

    /** A day's pay for one man: out of the treasury for the town's own two, else out of the knight's coin. */
    static boolean pay(ServerLevel level, UUID town, Player p, boolean townPays) {
        if (townPays) {
            if (Ledger.takeCoins(town, WAGE) < WAGE) return false;
            Economy.spent(town, WAGE);
            return true;
        }
        if (Market.coinsHeld(p) < WAGE) return false;
        Market.payOut(p, WAGE);
        return true;
    }

    /** "Retinue, stand down": back to their posts, what they picked up handed over. */
    public static String dismiss(ServerLevel level, UUID town, Player p) {
        CompoundTag k = Knights.knight(town, p.getUUID());
        if (k == null) return "You have no retinue here.";
        int n = 0;
        for (UUID u : men(town, p.getUUID())) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f == null || !p.getUUID().equals(f.hiredBy())) continue;
            home(f, level, p, FolkTalk.pick(f.getRandom(), "Back to my post, then.", "As you say. I'll be at the watch.", "Right you are."));
            n++;
        }
        k.putBoolean("out", false);
        CivicLand.changed();
        return n == 0 ? "Your men are at their posts already." : "Your men go back to their posts.";
    }

    /** One more man, at the knight's own cost (two at most of those). */
    public static String hire(ServerLevel level, UUID town, ServerPlayer p) {
        CompoundTag k = Knights.knight(town, p.getUUID());
        if (k == null) return "Only a knight of the town has a retinue.";
        if (TOWN_PAID + k.getInt("extra") >= MOST) return "You have all the men a knight may keep: " + MOST + ".";
        if (spare(town) <= 0) return "The watch can't spare another man: the town keeps two at home.";
        if (Market.coinsHeld(p) < WAGE) return "A man costs " + WAGE + " coins a day, paid ahead. You've not got it on you.";
        Villages.Village v = Villages.get(town);
        if (v == null) return "";
        k.putInt("extra", k.getInt("extra") + 1);
        List<String> names = assign(level, v, p.getUUID());
        CivicLand.changed();
        return "Another of the watch joins your retinue: " + CivicLand.names(names) + ". You pay " + WAGE + " coins a day for each past the town's two.";
    }

    /** The retinue no more (stripped, released, the knight's own word): every man home, the list cleared. */
    public static String release(ServerLevel level, UUID town, UUID knight, String why) {
        CompoundTag k = Knights.knight(town, knight);
        if (k == null) return "There is no retinue to release.";
        Player p = level.getServer().getPlayerList().getPlayer(knight);
        for (UUID u : men(town, knight)) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f != null && knight.equals(f.hiredBy())) home(f, level, p, "Back to the watch for good, then. It was an honour.");
        }
        k.put("retinue", new ListTag());
        k.putInt("extra", 0);
        k.putBoolean("out", false);
        CivicLand.changed();
        return "Your retinue goes back to the watch for good.";
    }

    /** The town gone: whatever men were with the knight go their way (they are the dead town's). */
    static void forget(UUID knight) {
    }

    /**
     * A man of the retinue back to its post: what it picked up while it rode with the knight (all it has more of than it
     * set out with) handed over, or into the town's stores with the knight far off; no tale of adventure, it was duty.
     */
    static void home(VillageFolkEntity f, ServerLevel level, @javax.annotation.Nullable Player p, String said) {
        java.util.Map<net.minecraft.world.item.Item, Integer> had = new java.util.HashMap<>(f.hiredPack);
        List<net.minecraft.world.item.ItemStack> share = new ArrayList<>();
        for (net.minecraft.world.item.ItemStack st : f.getInventoryItems()) {
            if (st.isEmpty()) continue;
            int keep = had.getOrDefault(st.getItem(), 0);
            if (st.getCount() <= keep) {
                had.put(st.getItem(), keep - st.getCount());
                continue;
            }
            had.put(st.getItem(), 0);
            share.add(st.split(st.getCount() - keep));
        }
        UUID town = f.ownerId();
        for (net.minecraft.world.item.ItemStack st : share) {
            if (p != null && p.level() == level && f.distanceToSqr(p) < 32.0 * 32.0) {
                if (!p.getInventory().add(st)) p.drop(st, false);
            } else if (town != null) {
                net.minecraft.world.item.ItemStack left = Market.intoStores(level, town, st);
                if (!left.isEmpty()) f.spawnAtLocation(left);
            } else {
                f.spawnAtLocation(st);
            }
        }
        f.unhire();
        f.getNavigation().stop();
        FolkTalk.speak(f, said);
    }

    // ------------------------------------------------------------------ the day

    /** Every five seconds (CivicLand): a day's pay found for the men out, the knight gone from the world sent home from; a fallen man replaced. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!CivicLand.known(id)) return;
        long day = CivicLand.day(level);
        for (String key : new ArrayList<>(Knights.knights(id).getAllKeys())) {
            UUID knight = CivicLand.uuid(key);
            CompoundTag k = Knights.knights(id).getCompound(key);
            if (knight == null) continue;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(knight);
            if (k.getBoolean("out")) {
                if (p == null) {
                    for (UUID u : men(id, knight)) {
                        VillageFolkEntity f = CivicLand.find(level, u);
                        if (f != null && knight.equals(f.hiredBy())) home(f, level, null, "Back to my post till I'm called.");
                    }
                    k.putBoolean("out", false);
                    CivicLand.changed();
                } else if (k.getLong("paidDay") != day + 1) {
                    k.putLong("paidDay", day + 1);
                    int i = 0;
                    for (UUID u : men(id, knight)) {
                        VillageFolkEntity f = CivicLand.find(level, u);
                        boolean townPays = i++ < TOWN_PAID;
                        if (f == null || !knight.equals(f.hiredBy())) continue;
                        if (pay(level, id, p, townPays)) {
                            f.hire(p, level.getGameTime() + 24000L + 600L);
                        } else {
                            home(f, level, p, townPays ? "The treasury can't pay me, " + Knights.style(knight).word + ". I'm for home."
                                : "No pay, no sword. I'm away home.");
                        }
                    }
                    CivicLand.changed();
                }
            }
            // A man lost: another named after three days.
            long lost = k.getLong("lost") - 1;
            if (lost >= 0 && day - lost >= MOURN && size(id, knight) < Math.min(MOST, TOWN_PAID + k.getInt("extra"))) {
                k.remove("lost");
                List<String> names = assign(level, v, knight);
                if (!names.isEmpty()) CivicLand.tell(level.getServer(), knight, "The watch of " + Villages.name(id) + " names a new man to your retinue: "
                    + names.get(names.size() - 1) + ".", ChatFormatting.GOLD);
            }
        }
    }

    // ------------------------------------------------------------------ for real

    /** A man of a retinue dies for real: off the list, into the chronicle, the knight told. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level) || f.ownerId() == null) return;
        com.jrpetty.mcassistant.Guard.run("a retinue's loss", () -> {
            UUID id = f.ownerId();
            if (!CivicLand.known(id)) return;
            for (String key : Knights.knights(id).getAllKeys()) {
                CompoundTag k = Knights.knights(id).getCompound(key);
                ListTag list = CivicLand.list(k, "retinue");
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (!list.getString(i).equals(f.getUUID().toString())) continue;
                    list.remove(i);
                    long day = CivicLand.day(level);
                    k.putLong("lost", day + 1);
                    CivicLand.changed();
                    UUID knight = CivicLand.uuid(key);
                    String title = knight == null ? k.getString("name") : Knights.style(knight).word + " " + k.getString("name");
                    boolean beside = f.isHired();
                    Villages.tell(id, day, f.displayNameCap() + " of the watch fell " + (beside ? "fighting beside " + title : "while in " + title + "'s retinue"));
                    if (knight != null) CivicLand.tell(level.getServer(), knight, f.displayNameCap() + ", of your retinue, is dead.", ChatFormatting.RED);
                }
            }
        });
    }

    /** The knight leaves the world: its men go back to their posts. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        com.jrpetty.mcassistant.Guard.run("a retinue sent home", () -> {
            for (UUID town : Knights.townsOf(p.getUUID())) {
                CompoundTag k = Knights.knight(town, p.getUUID());
                if (k == null || !k.getBoolean("out")) continue;
                for (UUID u : men(town, p.getUUID())) {
                    VillageFolkEntity f = CivicLand.find(level, u);
                    if (f != null && p.getUUID().equals(f.hiredBy())) home(f, level, p, "We'll be at our posts till you're back.");
                }
                k.putBoolean("out", false);
                CivicLand.changed();
            }
        });
    }

    // ------------------------------------------------------------------ words

    /** "Your retinue: Ada and Bran, at their posts." */
    static String line(ServerLevel level, UUID town, UUID knight) {
        List<String> names = new ArrayList<>();
        for (UUID u : men(town, knight)) {
            VillageFolkEntity f = CivicLand.find(level, u);
            names.add(f == null ? "a man away" : f.displayNameCap());
        }
        if (names.isEmpty()) return "No retinue yet: the watch can spare nobody.";
        CompoundTag k = Knights.knight(town, knight);
        return "Your retinue: " + CivicLand.names(names) + (k != null && k.getBoolean("out") ? ", with you." : ", at their posts.");
    }

    /** Each man, for the Knights page: name, health, where. */
    static List<String> lines(ServerLevel level, UUID town, UUID knight) {
        List<String> out = new ArrayList<>();
        for (UUID u : men(town, knight)) {
            VillageFolkEntity f = CivicLand.find(level, u);
            if (f == null) { out.add("(away from the town)"); continue; }
            out.add(f.displayNameCap() + " · " + Math.round(f.getHealth()) + "/" + Math.round(f.getMaxHealth()) + " · "
                + (knight.equals(f.hiredBy()) ? "with you" : "at the watch") + " · level " + f.veteranLevel());
        }
        return out;
    }
}
