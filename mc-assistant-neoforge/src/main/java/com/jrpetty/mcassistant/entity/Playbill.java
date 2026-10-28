package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [culture-wonders] The playbill, the tickets, the programme and the reviews: the theatre as the player meets it.
 *
 * <p><b>The playbill.</b> Once the town has a house to play in, a hand on its works puts up a sign before it (out of
 * the stores, as any of the town's signs): "PLAYBILL", the coming play's title, the night and the price, written up
 * afresh each morning. Right-click it with an empty hand to read the bill in full; with village coin in hand to buy a
 * seat (a coin into the treasury), and the box office gives the programme, a written book of the play: its title and
 * kind, the night, its cast (who plays whom), its acts, the line of the chronicle it is made from, and what the last
 * plays were thought of. With a book and quill in hand, to ask the company for a play about one's own deeds: the
 * company writes one out of every line of the chronicle with the player's name in it and puts it on next.
 *
 * <p><b>The review.</b> The night after a play the town's critic (its keenest reader, not of the cast) gives it its
 * stars: three for a play put on, one more for a cast that rehearsed it, one more for a full house, one less for a
 * part stood in for or doubled, one less for an empty one; and a line on the best of the players and on how the house
 * took it. It goes on the board, into the gazette ("At the theatre") and onto the next programme; the stars make the
 * players' names (Troupe).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Playbill {

    private Playbill() {}

    static void resetForTests() {
        LOOKED.clear();
    }

    static final String SIGN = "PLAYBILL";
    static final String REQUEST = "playbill.request", TICKETS = "playbill.tickets", AT = "playbill.at";
    private static final java.util.Map<UUID, Long> LOOKED = new java.util.concurrent.ConcurrentHashMap<>();

    /** What the critic thought: its stars, its name, its line. */
    public record Review(int stars, String critic, String text) {}

    static String stars(int n) {
        n = Math.max(0, Math.min(5, n));
        return "★".repeat(n) + "☆".repeat(5 - n);
    }

    // ------------------------------------------------------------------ the review

    /** The night's review, by the town's critic. */
    static Review review(ServerLevel level, Villages.Village v, Theatre.Show s, List<VillageFolkEntity> actors) {
        UUID id = v.id();
        int stars = 3;
        String key = s.play.about().isEmpty() ? Theatre.key(new Chronicle.Entry(s.play.storyDay(), s.play.story())) : "hero:" + s.play.about();
        int runs = Troupe.rehearsed(id, key);
        if (runs >= 1) stars++;
        int seats = s.venue.seats().length;
        if (s.watched.size() >= Math.min(8, seats)) stars++;
        boolean stood = false;
        for (String n : s.notes) if (n.contains("stood in") || n.contains("doubled")) stood = true;
        if (stood) stars--;
        if (s.watched.size() < 3) stars--;
        stars = Math.max(1, Math.min(5, stars));
        // The critic: the keenest reader of the town not in the cast.
        String critic = "our critic";
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING
                && !s.cast.contains(f.getUUID()) && !s.under.containsValue(f.getUUID())) { critic = f.displayNameCap(); break; }
        }
        VillageFolkEntity best = null;
        int most = -1;
        for (VillageFolkEntity f : actors) {
            int score = Troupe.fameOf(id, f.getUUID()) + Math.floorMod(f.getUUID().hashCode() + (int) s.day, 7);
            if (score > most) { most = score; best = f; }
        }
        RandomSource r = level.getRandom();
        List<String> bits = new ArrayList<>();
        if (best != null) {
            int part = Math.max(0, s.cast.indexOf(best.getUUID()));
            String as = s.play.parts().get(Math.min(part, s.play.parts().size() - 1)).name();
            bits.add(stars >= 4 ? best.displayNameCap() + " was " + FolkTalk.pick(r, "magnificent", "unforgettable", "a marvel") + " as " + as
                : stars <= 2 ? best.displayNameCap() + " did what could be done as " + as : best.displayNameCap() + " made a fine " + as);
        }
        if (s.play.tragedy()) bits.add(FolkTalk.pick(r, "there wasn't a dry eye on the benches", "the house wept, and so did I"));
        else if (s.play.fights() > 0) bits.add(FolkTalk.pick(r, "the swordplay had us on our feet", "the fighting was the best this town has seen on a stage"));
        else bits.add(FolkTalk.pick(r, "the house laughed till it ached", "a warm night, and a warmer house"));
        if (stood) bits.add("a part was stood in for, and it showed");
        if (runs >= 2) bits.add("well rehearsed");
        if (s.watched.size() < 3) bits.add("a thin house");
        String text = "“" + s.play.title() + "” — " + String.join("; ", bits) + ". — " + critic;
        return new Review(stars, critic, text);
    }

    /** The night's done: the review into the books, the players who bought seats thanked, a request kept. */
    static void played(ServerLevel level, Villages.Village v, Theatre.Show s) {
        UUID id = v.id();
        if (!s.play.about().isEmpty()) {
            String req = Ledger.note(id, REQUEST);
            if (req != null && req.split("\t", -1).length > 1 && req.split("\t", -1)[1].equals(s.play.about())) Ledger.forget(id, REQUEST);
        }
        List<String[]> tickets = Culture.rows(id, TICKETS);
        List<String> thanked = new ArrayList<>();
        for (String[] t : tickets) {
            if (t.length < 3 || Culture.num(t[0], -1) != s.day) continue;
            UUID u = WarBooks.id(t[2]);
            if (u != null && s.players.contains(u)) thanked.add(t[1]);
        }
        if (!thanked.isEmpty()) {
            for (UUID c : s.cast) {
                if (level.getEntity(c) instanceof VillageFolkEntity f) {
                    FolkTalk.speak(f, "And thank you, " + JobMarket.join(thanked) + ", for coming to see us!");
                    break;
                }
            }
            for (ServerPlayerRef p : players(level, s)) {
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a instanceof VillageFolkEntity f && s.watched.contains(f.getUUID())) f.persona().feelFor(p.id, p.name, 1);
                }
            }
        }
        tickets.removeIf(t -> t.length < 1 || Culture.num(t[0], 0) < s.day - 7);
        Culture.rows(id, TICKETS, tickets);
    }

    private record ServerPlayerRef(UUID id, String name) {}

    private static List<ServerPlayerRef> players(ServerLevel level, Theatre.Show s) {
        List<ServerPlayerRef> out = new ArrayList<>();
        for (UUID u : s.players) {
            Player p = level.getPlayerByUUID(u);
            if (p != null) out.add(new ServerPlayerRef(u, p.getName().getString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ a play asked for

    /**
     * The play a player asked for, if one is waiting and the chronicle has deeds of theirs to tell (every line with
     * their name in it); null if none is waiting, or there is nothing yet to tell.
     */
    @Nullable
    public static Playwright.Play requested(UUID village) {
        String req = Ledger.note(village, REQUEST);
        if (req == null || req.isEmpty()) return null;
        String[] p = req.split("\t", -1);
        if (p.length < 2) return null;
        List<Chronicle.Entry> deeds = deeds(village, p[1]);
        return deeds.isEmpty() ? null : Playwright.hero(village, p[1], deeds);
    }

    /** Every line of the chronicle with this name in it, the weightiest first. */
    static List<Chronicle.Entry> deeds(UUID village, String name) {
        List<Chronicle.Entry> out = new ArrayList<>();
        String n = name.toLowerCase(Locale.ROOT);
        for (Chronicle.Entry e : Chronicle.of(village)) {
            String t = e.text().toLowerCase(Locale.ROOT);
            if (t.contains(n) && Playwright.kind(e.text()) != null) out.add(e);
        }
        out.sort((a, b) -> Integer.compare(FoundingDay.weight(b.text()), FoundingDay.weight(a.text())));
        return out;
    }

    /** A player asks the company for a play of their own deeds. What they are told. */
    public static String request(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        String name = p.getName().getString();
        if (!Theatre.anyHouse(id)) return Villages.name(id) + " has no theatre yet to put a play on in.";
        List<Chronicle.Entry> deeds = deeds(id, name);
        if (deeds.isEmpty()) {
            return "The company looked through " + Villages.name(id) + "'s chronicle for you, " + name
                + ", and found nothing to play yet. Do something worth telling, and ask again!";
        }
        String req = Ledger.note(id, REQUEST);
        if (req != null && !req.isEmpty() && !req.startsWith(p.getUUID().toString())) {
            return "The company has a play already asked for. Ask again after it's played.";
        }
        Ledger.note(id, REQUEST, p.getUUID() + "\t" + name + "\t" + level.getDayTime() / 24000L);
        Villages.tell(id, level.getDayTime() / 24000L, name + " asked the players for a play of their deeds, and they agreed to put one on next");
        return "The company will put on “The Ballad of " + name + "” next — " + deeds.size() + (deeds.size() == 1 ? " deed" : " deeds")
            + " of yours in the chronicle to tell. Come and watch!";
    }

    // ------------------------------------------------------------------ the sign and the box office

    /** The house the playbill stands before: the playhouse, else the theatre, else the amphitheatre. */
    @Nullable
    static Ledger.Building house(UUID village) {
        Ledger.Building b = Culture.building(village, Theatre.PLAYHOUSE);
        if (b == null) b = Culture.building(village, Theatre.STRUCTURE);
        if (b == null) b = TownWonders.standing(village, TownWonders.AMPHITHEATRE);
        return b;
    }

    /** Where the playbill goes: before the house's front, to one side of its middle. */
    static BlockPos spot(Ledger.Building b) {
        int depth = Blueprints.has(b.structure()) ? Blueprints.fullHalf(b.structure())[1] : 5;
        return Culture.at(b, -2, 0, -depth - 1);
    }

    /** The sign's four lines: the bill, the coming play, its night, the price. */
    static String[] signLines(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        Playwright.Play p = Theatre.next(village, day);
        long night = day;
        for (long d = day; d < day + 8; d++) if (RestDay.today(village, d)) { night = d; break; }
        String[] title = Culture.signLines(p.title());
        return new String[]{ SIGN, title[0], title[1].isEmpty() ? "day " + (night + 1) : title[1], Theatre.TICKET + " coin a seat" };
    }

    /** The town's look, once a second (Culture): the playbill put up once there is a house, and written up each morning. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Ledger.Building b = house(id);
        long now = level.getGameTime();
        if (b == null || now - LOOKED.getOrDefault(id, -1000L) < 200) return;           // every ten seconds
        LOOKED.put(id, now);
        BlockPos at = spot(b);
        if (!level.isLoaded(at)) return;
        boolean up = level.getBlockState(at).getBlock() instanceof StandingSignBlock;
        if (!up) {
            if (!Petitions.clear(level, at) || !level.getBlockState(at.below()).isSolid()) return;
            if (!TownJobs.atWork(level, v, "signs", at, "putting up the playbill before the theatre")) return;
            if (!Crafts.sign(level, v)) return;
            Direction front = b.facing().getOpposite();
            level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(front)), 3);
            Ledger.note(id, AT, Long.toString(at.asLong()));
            Villages.tell(id, level.getDayTime() / 24000L, "the playbill was put up before the theatre");
        }
        write(level, id, at);
    }

    /** The sign written up with the coming play (both faces). */
    static void write(ServerLevel level, UUID village, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) return;
        String[] lines = signLines(level, village);
        if (sign.getFrontText().getMessage(1, false).getString().equals(lines[1])
            && sign.getFrontText().getMessage(2, false).getString().equals(lines[2])) return;
        SignText face = new SignText();
        for (int i = 0; i < 4; i++) face = face.setMessage(i, Component.literal(lines[i]));
        sign.setText(face, true);
        sign.setText(face, false);
        sign.setWaxed(true);
    }

    /** The playbill right-clicked: read it, buy a seat, or ask for a play. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        if (!(level.getBlockState(pos).getBlock() instanceof StandingSignBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        if (!SIGN.equals(sign.getFrontText().getMessage(0, false).getString())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        Villages.Village v = Villages.nearest(level, pos, 128);
        if (v == null) return;
        Player p = e.getEntity();
        ItemStack hand = p.getMainHandItem();
        String said;
        if (hand.is(Items.WRITABLE_BOOK)) said = request(level, v, p);
        else if (Market.isCoin(hand)) said = buy(level, v, p);
        else said = String.join("\n", bill(level, v.id()));
        p.displayClientMessage(Component.literal(said), false);
    }

    /** The bill in full, for the chat. */
    static List<String> bill(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        Playwright.Play p = Theatre.next(village, day);
        List<String> out = new ArrayList<>();
        out.add("§l" + Villages.name(village) + " — the playbill§r");
        out.add("Coming: “" + p.bill() + "”" + (p.story().isEmpty() ? "" : ", after the chronicle: " + Culture.capital(p.story())));
        String tonight = Theatre.occasion(village, day);
        out.add(tonight != null ? "Tonight, after supper (" + tonight + ")." : "On the evening of the day of rest, after supper.");
        out.add("A seat " + Theatre.TICKET + " coin (right-click with coin in hand for a seat and the programme); standing free.");
        out.add("Want a play of your own deeds? Right-click with a book and quill.");
        out.addAll(lines(village));
        return out;
    }

    /** A seat bought: a coin into the treasury, and the programme. */
    public static String buy(ServerLevel level, Villages.Village v, Player p) {
        UUID id = v.id();
        if (!Theatre.anyHouse(id)) return Villages.name(id) + " has no theatre yet.";
        if (!Civics.takeCoins(p, Theatre.TICKET)) return "A seat is " + Theatre.TICKET + " coin; you've none of the town's coin on you.";
        Ledger.addCoins(id, Theatre.TICKET);
        CoinCensus.flow(id, CoinCensus.Flow.PLAYER_IN, Theatre.TICKET);                                 // [business-books]
        Businesses.service(level, id, Business.Type.THEATRE, net.minecraft.world.item.ItemStack.EMPTY, 1, Theatre.TICKET * 100L, true);   // [business-books]
        long day = level.getDayTime() / 24000L;
        long night = day;
        if (Theatre.occasion(id, day) == null) for (long d = day; d < day + 8; d++) if (RestDay.today(id, d)) { night = d; break; }
        List<String[]> tickets = Culture.rows(id, TICKETS);
        tickets.add(new String[]{ Long.toString(night), p.getName().getString(), p.getUUID().toString() });
        while (tickets.size() > 32) tickets.remove(0);
        Culture.rows(id, TICKETS, tickets);
        ItemStack book = programme(level, v, night, p.getName().getString());
        if (!p.getInventory().add(book)) p.drop(book, false);
        return "A seat for day " + (night + 1) + ", after supper. Here's your programme — enjoy the play!";
    }

    /** The programme: a written book of the coming play. */
    static ItemStack programme(ServerLevel level, Villages.Village v, long night, String holder) {
        UUID id = v.id();
        Playwright.Play p = Theatre.next(id, night);
        String town = Villages.name(id);
        String front = "§l" + p.title() + "§r\n" + p.genre() + "\n\nThe " + town + " Players\nDay " + (night + 1) + ", after supper\n\n"
            + "Admit one: " + holder + "\n" + Theatre.TICKET + " coin";
        List<String> entries = new ArrayList<>();
        List<String> cast = new ArrayList<>();
        List<VillageFolkEntity> likely = Theatre.cast(level, id, night, p.parts().size());
        for (int i = 0; i < p.parts().size(); i++) {
            String who = likely.isEmpty() ? "a player" : likely.get(Math.min(i % Math.max(1, likely.size()), likely.size() - 1)).displayNameCap();
            cast.add(p.parts().get(i).name() + " — " + who);
        }
        entries.add("§lThe cast§r\n" + String.join("\n", cast));
        List<String> acts = p.acts();
        if (!acts.isEmpty()) entries.add("§lThe acts§r\n" + String.join("\n", acts));
        if (!p.story().isEmpty()) entries.add("§lFrom the chronicle§r\n" + Culture.capital(p.story()) + ".");
        List<String[]> rows = Culture.rows(id, "culture.plays");
        List<String> past = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0 && past.size() < 3; i--) {
            String[] r = rows.get(i);
            if (r.length > 7) past.add(stars((int) Culture.num(r[6], 0)) + " " + r[7]);
        }
        if (!past.isEmpty()) entries.add("§lWhat they said of the last§r\n" + String.join("\n", past));
        ItemStack book = Services.book("Programme: " + p.title(), "The " + town + " Players", front, entries);
        return book;
    }

    // ------------------------------------------------------------------ where the player reads it

    /** The books' lines: the last review, a play asked for, seats sold. */
    static List<String> lines(UUID village) {
        List<String> out = new ArrayList<>();
        List<String[]> rows = Culture.rows(village, "culture.plays");
        for (int i = rows.size() - 1; i >= 0; i--) {
            String[] r = rows.get(i);
            if (r.length > 7) { out.add("the last review: " + stars((int) Culture.num(r[6], 0)) + " " + r[7]); break; }
        }
        String req = Ledger.note(village, REQUEST);
        if (req != null && req.split("\t", -1).length > 1) out.add("asked for: the ballad of " + req.split("\t", -1)[1] + ", put on next");
        return out;
    }

    /** The gazette's "At the theatre": yesterday's play and its review. */
    @Nullable
    public static String gazette(UUID village, long day) {
        for (String[] r : Culture.rows(village, "culture.plays")) {
            if (Culture.num(r[0], -1) != day - 1 || r.length < 8) continue;
            return "§lAt the theatre§r\n“" + r[1] + "”" + (r[5].isEmpty() ? "" : ", " + r[5]) + ", at " + (r.length > 8 ? r[8] : "the theatre")
                + ": " + r[3] + " came. " + stars((int) Culture.num(r[6], 0)) + "\n" + r[7];
        }
        return null;
    }

    /** The board's line: last night's review (the day after), or null. */
    @Nullable
    static String boardLine(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        for (String[] r : Culture.rows(village, "culture.plays")) {
            long d = Culture.num(r[0], -1);
            if ((d == day || d == day - 1) && r.length > 7) return "Reviewed: " + stars((int) Culture.num(r[6], 0)) + " " + r[7];
        }
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the playbill put up now (a hand at it at once: TownJobs.instantForTests). Where, or null. */
    @Nullable
    public static BlockPos putForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building b = house(v.id());
        if (b == null) return null;
        BlockPos at = spot(b);
        long was = level.getGameTime();
        // tick() only looks every ten seconds: look now.
        boolean up = level.getBlockState(at).getBlock() instanceof StandingSignBlock;
        if (!up) {
            if (!Petitions.clear(level, at) || !Crafts.sign(level, v)) return null;
            level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,
                RotationSegment.convertToSegment(b.facing().getOpposite())), 3);
        }
        write(level, v.id(), at);
        return was >= 0 ? at : null;
    }
}
