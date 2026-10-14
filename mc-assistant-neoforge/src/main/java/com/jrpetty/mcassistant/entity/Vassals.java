package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war] Vassal towns: a town beaten in a war fought for lordship over it (WarAndPeace.Goal.SUBJUGATE), or whose gate
 * was taken, made the vassal of its victor.
 *
 * <p><b>What it owes.</b> Every seventh day of its vassalage, <b>tribute</b>: a fifth of its treasury and the best of
 * its surplus, carried to the overlord by one of its folk on the road (an envoy's trip, Envoys: what it carries is
 * paid into the overlord's treasury and stores on arrival, never made out of nothing and never lost but with the
 * carrier). <b>Levies</b>: when the overlord is at war, two of its guards to the overlord's walls, to march in its
 * army (WarAndPeace.dispatch). <b>No wars or treaties of its own</b>, but the one for its freedom.
 *
 * <p><b>How it shows.</b> The overlord's banner (woven out of the overlord's stores) above the town's own wherever
 * those fly, and a post by the board with the overlord's banner and a sign: the reeve's post, "Ashby, vassal of
 * Brannoc" and the tribute owed. On the board, in the town's books (the war page), on the war map and on its folk's
 * cards.
 *
 * <p><b>What it does to the town.</b> Its folk resent it: lower spirits ("We work for Brannoc now"), lower
 * contentment, and the resentment builds each day (it counts as the town's war-weariness, so some look for work
 * elsewhere and a few pack up and leave). Its growth is drained by the tribute. A little good: the overlord's name
 * keeps raiders and rivals off (no war is made on a vassal but through its overlord).
 *
 * <p><b>The way out.</b> It buys its freedom (eight weeks' tribute and fifty coins) as soon as its treasury holds it;
 * it is freed in a treaty; or its resentment, high enough, with the town grown strong against its overlord or the
 * overlord at war elsewhere, boils over into a war of independence (WarAndPeace.Goal.INDEPENDENCE), fought like any
 * other: won, it is free; lost, it stays a vassal and owes the more.
 *
 * <p>Kept with the world in the vassal's books (Ledger "vassal.of" = overlord|since|tribute paid|resentment) and the
 * overlord's ("vassal.has/&lt;vassal&gt;"). Its upkeep runs once a day per town (WarAndPeace.townDaily), never per tick.
 */
public final class Vassals {

    private Vassals() {}

    static final String OF = "vassal.of", HAS = "vassal.has/";
    /** Days between tributes. */
    static final int WEEK = 7;
    /** The share of its treasury a vassal owes each week. */
    static final double SHARE = 0.2;
    /** The price of its freedom: so many weeks of the tribute it last paid, and this much more. */
    static final int FREEDOM_WEEKS = 8, FREEDOM_BASE = 50;
    /** Resentment (0-100) a day: the tribute, the garrison, the overlord's word over its own. */
    static final int RESENTS = 3;
    /** Resentment at which it rebels, given the strength or the chance. */
    static final int REBELS = 60;

    /** A vassalage: its overlord, since when, the last tribute it paid, how much it resents it. */
    public record Bond(UUID overlord, long since, int lastTribute, int resentment) {
        String encode() {
            return overlord + "|" + since + "|" + lastTribute + "|" + resentment;
        }

        @Nullable
        static Bond decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\\|");
            UUID o = p.length > 0 ? WarBooks.id(p[0]) : null;
            if (o == null) return null;
            return new Bond(o, p.length > 1 ? WarBooks.num(p[1], 0) : 0, p.length > 2 ? (int) WarBooks.num(p[2], 0) : 0,
                p.length > 3 ? (int) WarBooks.num(p[3], 0) : 0);
        }
    }

    @Nullable
    public static Bond bond(@Nullable UUID town) {
        return town == null ? null : Bond.decode(Ledger.note(town, OF));
    }

    public static boolean isVassal(@Nullable UUID town) {
        return bond(town) != null;
    }

    @Nullable
    public static UUID overlord(@Nullable UUID town) {
        Bond b = bond(town);
        return b == null ? null : b.overlord();
    }

    /** The towns this one holds as vassals. */
    public static List<UUID> vassals(UUID overlord) {
        List<UUID> out = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (overlord.equals(overlord(v.id()))) out.add(v.id());
        return out;
    }

    /** "Ashby, vassal of Brannoc", or the plain name. */
    public static String styled(UUID town) {
        UUID o = overlord(town);
        return Villages.name(town) + (o == null ? "" : ", vassal of " + Villages.name(o));
    }

    // ------------------------------------------------------------------ made, and freed

    /** Made a vassal (at the peace: WarAndPeace.meet). Words for the treaty. */
    static String make(ServerLevel level, UUID overlord, UUID vassal, long day, String how) {
        if (overlord.equals(vassal) || isVassal(overlord) && vassal.equals(overlord(overlord))) return "nothing";
        Ledger.note(vassal, OF, new Bond(overlord, day, 0, 10).encode());
        Ledger.note(overlord, HAS + vassal, Long.toString(day));
        String on = Villages.name(overlord), vn = Villages.name(vassal);
        Villages.tell(vassal, day, vn + " became the vassal of " + on + ", " + how + ": tribute every week, guards at its call, and no wars of our own");
        Villages.tell(overlord, day, vn + " became our vassal, " + how);
        for (Villages.Village o : Villages.every()) if (!o.id().equals(overlord) && !o.id().equals(vassal)) Ledger.relate(o.id(), overlord, -1);
        Villages.Village v = Villages.get(vassal);
        if (v != null && level.isLoaded(v.centre())) showLordship(level, v);
        return vn + " to be " + on + "'s vassal";
    }

    /** Freed: the bond ended, the overlord's banners and its post taken down (the cloth back to the overlord's stores). */
    static void free(ServerLevel level, UUID vassal, long day, String how) {
        Bond b = bond(vassal);
        if (b == null) return;
        Ledger.forget(vassal, OF);
        Ledger.forget(b.overlord(), HAS + vassal);
        Villages.Village v = Villages.get(vassal), o = Villages.get(b.overlord());
        for (String e : WarBooks.list(vassal, "vassal.banners")) {
            BlockPos p = BlockPos.of(WarBooks.num(e, 0));
            if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof BannerBlock) {
                ItemStack cloth = level.getBlockEntity(p) instanceof BannerBlockEntity be ? be.getItem() : ItemStack.EMPTY;
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                if (o != null && !cloth.isEmpty()) TownWork.give(level, o, cloth);
            }
        }
        Ledger.forget(vassal, "vassal.banners");
        String post = Ledger.note(vassal, "vassal.post");
        if (post != null && !post.isEmpty()) {
            BlockPos p = BlockPos.of(WarBooks.num(post, 0));
            for (BlockPos q : new BlockPos[]{ p.above(2), p.above(), p }) if (level.isLoaded(q)) level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
            Ledger.forget(vassal, "vassal.post");
        }
        String vn = Villages.name(vassal), on = Villages.name(b.overlord());
        Villages.tell(vassal, day, vn + " is free of " + on + ", " + how);
        Villages.tell(b.overlord(), day, vn + " is no longer our vassal: " + how);
        if (v != null) Raids.tellNear(level, v.centre(), 160, Component.literal("[War] " + vn + " is free of " + on + ", " + how + ".")
            .withStyle(net.minecraft.ChatFormatting.GREEN), false);
    }

    // ------------------------------------------------------------------ the day

    /** Once a day for a vassal (WarAndPeace.townDaily): its resentment, its tribute, its levies, its banners, its way out. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        Bond b = bond(v.id());
        if (b == null) {
            if (!vassals(v.id()).isEmpty()) upkeep(v.id(), day);
            return;
        }
        Villages.Village lord = Villages.get(b.overlord());
        if (lord == null) {                                         // its overlord is gone from the world: free
            free(level, v.id(), day, "its overlord gone");
            return;
        }
        int resent = Math.min(100, b.resentment() + RESENTS);
        Ledger.note(v.id(), OF, new Bond(b.overlord(), b.since(), b.lastTribute(), resent).encode());
        if (buyFreedom(level, v, day)) return;
        if (rebel(level, v, lord, day)) return;
        if ((day - b.since()) % WEEK == 0 && day > b.since()) tribute(level, v, lord, day);
        levies(level, v, lord, day);
        if (level.isLoaded(v.centre())) showLordship(level, v);
    }

    /** The overlord's cost of its vassals: a coin a day each for the reeve and the post. */
    static void upkeep(UUID overlord, long day) {
        int n = vassals(overlord).size();
        if (n > 0) Ledger.takeCoins(overlord, n);
    }

    /** The week's tribute: a fifth of the treasury into a carrier's purse, the surplus on its back, on the road to the overlord. */
    static boolean tribute(ServerLevel level, Villages.Village v, Villages.Village lord, long day) {
        int coins = (int) Math.floor(Ledger.coins(v.id()) * SHARE);
        if (!Envoys.send(level, v, lord, Envoys.Errand.GIFT, day)) {
            // Nobody to carry it (or the road shut): the coin goes by word, the overlord's due all the same.
            int paid = Ledger.takeCoins(v.id(), coins);
            Ledger.addCoins(lord.id(), paid);
            paidTribute(v.id(), paid, day);
            return paid > 0;
        }
        return true;
    }

    /** [Envoys.send] The coin a tribute carrier carries: a fifth of the treasury, on the week's day. */
    static int purse(UUID from, UUID to, Envoys.Errand errand, long day) {
        if (errand != Envoys.Errand.GIFT || !to.equals(overlord(from))) return 0;
        Bond b = bond(from);
        if (b == null || (day - b.since()) % WEEK != 0) return 0;
        return Ledger.takeCoins(from, (int) Math.floor(Ledger.coins(from) * SHARE));
    }

    /** [Envoys.answer] The tribute received at the overlord's board: the goods into its stores, the coin into its treasury. */
    @Nullable
    static Envoys.Answer received(ServerLevel level, UUID host, UUID from, Envoys.Errand errand, VillageFolkEntity envoy, Caravans.Trip t) {
        if (errand != Envoys.Errand.GIFT || !host.equals(overlord(from))) return null;
        long day = level.getDayTime() / 24000L;
        return new Envoys.Answer(true, "The tribute of " + Villages.name(from) + ", received. " + Villages.name(host) + " remembers its own.", () -> {
            Envoys.unloadGifts(level, envoy, host);
            int paid = t.purse;
            if (paid > 0) Ledger.addCoins(host, paid);
            t.purse = 0;
            paidTribute(from, paid, day);
            t.outcome = "we paid the week's tribute to " + Villages.name(host) + (paid > 0 ? ", " + paid + " coins" : "");
        });
    }

    static void paidTribute(UUID vassal, int paid, long day) {
        Bond b = bond(vassal);
        if (b == null) return;
        Ledger.note(vassal, OF, new Bond(b.overlord(), b.since(), paid, b.resentment()).encode());
        Villages.tell(vassal, day, "the week's tribute went to " + Villages.name(b.overlord()) + (paid > 0 ? ": " + paid + " coins and the surplus" : ""));
        Villages.tell(b.overlord(), day, Villages.name(vassal) + "'s tribute came in" + (paid > 0 ? ": " + paid + " coins" : ""));
    }

    /** The overlord at war: two of the vassal's guards to its walls (once a war). */
    static void levies(ServerLevel level, Villages.Village v, Villages.Village lord, long day) {
        for (UUID foe : Wars.enemies(lord.id())) {
            if (foe.equals(v.id()) || Ledger.note(v.id(), "wp.pledge/" + lord.id()) != null) continue;
            WarAndPeace.dispatch(level, v, lord.id(), foe + "|2|0|" + day, day);
            Villages.tell(v.id(), day, Villages.name(lord.id()) + " called up our levy for its war with " + Villages.name(foe));
            return;
        }
    }

    /** The price of its freedom. */
    public static int price(UUID vassal) {
        Bond b = bond(vassal);
        return b == null ? 0 : FREEDOM_BASE + FREEDOM_WEEKS * Math.max(5, b.lastTribute());
    }

    /** It buys its freedom as soon as its treasury holds the price: the coin to the overlord, the bond ended. */
    static boolean buyFreedom(ServerLevel level, Villages.Village v, long day) {
        Bond b = bond(v.id());
        int price = price(v.id());
        if (b == null || Ledger.coins(v.id()) < price + 10) return false;
        int paid = Ledger.takeCoins(v.id(), price);
        Ledger.addCoins(b.overlord(), paid);
        free(level, v.id(), day, "its freedom bought for " + paid + " coins");
        return true;
    }

    /**
     * Rebellion: resentment at REBELS and over, and the town either grown strong against its overlord (four fifths
     * its strength) or the overlord at war with somebody else: a war of independence declared.
     */
    static boolean rebel(ServerLevel level, Villages.Village v, Villages.Village lord, long day) {
        Bond b = bond(v.id());
        if (b == null || b.resentment() < REBELS || !WarAndPeace.on() || Wars.atWar(v.id(), lord.id())) return false;
        boolean strong = WarAndPeace.own(level, v.id()).strength() * 5 >= WarAndPeace.own(level, lord.id()).strength() * 4;
        boolean busy = !Wars.enemies(lord.id()).isEmpty();
        if (!strong && !busy) return false;
        boolean declared = WarAndPeace.declare(level, v.id(), lord.id(), day, WarAndPeace.Goal.INDEPENDENCE, 0, "our freedom from " + Villages.name(lord.id()));
        if (declared) Villages.tell(v.id(), day, "the town rose against " + Villages.name(lord.id()) + ": a war for its freedom");
        return declared;
    }

    // ------------------------------------------------------------------ how it shows

    /**
     * The overlord's banner above the town's own wherever those fly (woven out of the overlord's stores, a place a
     * day), and the reeve's post by the board: a fence post with the overlord's banner and a sign.
     */
    static void showLordship(ServerLevel level, Villages.Village v) {
        Bond b = bond(v.id());
        Villages.Village lord = b == null ? null : Villages.get(b.overlord());
        if (lord == null) return;
        List<String> hung = WarBooks.list(v.id(), "vassal.banners");
        for (Heraldry.Place p : Arms.places(level, v.id())) {
            BlockPos own = p.spot().at(), over = own.above();
            if (!Heraldry.up(level, own) || hung.contains(Long.toString(over.asLong())) || !level.getBlockState(over).isAir()) continue;
            ItemStack cloth = overlordsCloth(level, lord);
            if (cloth == null) break;
            if (!hang(level, over, p.spot().facing(), cloth, p.pole())) {
                TownWork.give(level, lord, cloth);
                continue;
            }
            hung.add(Long.toString(over.asLong()));
            WarBooks.list(v.id(), "vassal.banners", hung);
            break;                                                    // a place a day
        }
        post(level, v, lord);
    }

    @Nullable
    static ItemStack overlordsCloth(ServerLevel level, Villages.Village lord) {
        Heraldry.Design d = Heraldry.design(lord.id());
        ItemStack s = Arms.takeOne(level, lord, x -> x.getItem() instanceof BannerItem && (d == null || Arms.bears(x, d)), "the reeve");
        if (s == null && d != null) s = Arms.weave(level, lord, d, "a banner for its vassal's walls");
        return s;
    }

    static boolean hang(ServerLevel level, BlockPos at, Direction facing, ItemStack cloth, boolean pole) {
        if (!(cloth.getItem() instanceof BannerItem bi) || !TownBlocks.mayPlace(level, at, TownBlocks.Why.SIGN)) return false;
        if (pole) {
            level.setBlock(at, BannerBlock.byColor(bi.getColor()).defaultBlockState()
                .setValue(BannerBlock.ROTATION, RotationSegment.convertToSegment(facing)), 3);
        } else {
            level.setBlock(at, Heraldry.wallBanner(bi.getColor()).defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, facing), 3);
        }
        if (level.getBlockEntity(at) instanceof BannerBlockEntity be) {
            be.applyComponentsFromItemStack(cloth);
            be.setChanged();
            return true;
        }
        return false;
    }

    /** The reeve's post beside the board: a fence post, the overlord's banner on it, a sign of what is owed. */
    static void post(ServerLevel level, Villages.Village v, Villages.Village lord) {
        String had = Ledger.note(v.id(), "vassal.post");
        if (had != null && !had.isEmpty()) return;
        BlockPos foot = VillageBoards.lectern(v.id());
        Direction f = VillageBoards.facingOf(v.id());
        if (foot == null || f == null || !f.getAxis().isHorizontal()) return;
        BlockPos at = Battlefields.surface(level, foot.relative(f, 3).relative(f.getClockWise(), 4));
        if (!TownBlocks.mayPlace(level, at, TownBlocks.Why.CIVIC) || !Battlefields.openGround(level, at, 2)
            || !TownWork.take(level, lord, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES), 1)) return;
        ItemStack cloth = overlordsCloth(level, lord);
        level.setBlock(at, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(f)), 3);
        if (level.getBlockEntity(at.above()) instanceof SignBlockEntity s) {
            SignText text = new SignText().setMessage(0, Component.literal(Villages.name(v.id()) + ","))
                .setMessage(1, Component.literal("vassal of")).setMessage(2, Component.literal(Villages.name(lord.id())))
                .setMessage(3, Component.literal("tribute weekly"));
            s.setText(text, true);
            s.setText(text, false);
            s.setWaxed(true);
        }
        if (cloth != null && !hang(level, at.above(2), f, cloth, true)) TownWork.give(level, lord, cloth);
        Ledger.note(v.id(), "vassal.post", Long.toString(at.asLong()));
    }

    // ------------------------------------------------------------------ its folk

    /** [WarAndPeace.mood] A vassal's folk resent it: lower spirits, the more as the resentment builds. */
    static int mood(VillageFolkEntity f, int m, List<Object[]> why) {
        Bond b = bond(f.ownerId());
        if (b == null || f.isBaby()) return m;
        int hit = 3 + b.resentment() / 20;
        why.add(new Object[]{ "vassal", hit });
        return m - hit;
    }

    /** [FolkTalk] What a vassal's folk say of it. */
    public static String moodWords(VillageFolkEntity f) {
        UUID o = overlord(f.ownerId());
        String lord = o == null ? "them" : Villages.name(o);
        RandomSource r = f.getRandom();
        return FolkTalk.pick(r, "We work for " + lord + " now.", "Every week, the best of what we make goes off down the road to " + lord + ".",
            "One day we'll be our own town again.", "I don't like the look of that reeve's post by the board.");
    }

    /** [WarAndPeace.contentment] The town's contentment, the lower for the bond. */
    static int contentment(UUID town, List<String> bad) {
        Bond b = bond(town);
        if (b == null) return 0;
        bad.add("vassalage to " + Villages.name(b.overlord()));
        return -(4 + b.resentment() / 15);
    }

    /** [WarAndPeace.wear] Resentment wears on a vassal like a war. */
    static int wear(UUID town) {
        Bond b = bond(town);
        return b == null ? 0 : b.resentment() / 25;
    }

    // ------------------------------------------------------------------ in words

    /** For the board, the books and the war map. */
    public static List<String> lines(UUID town) {
        List<String> out = new ArrayList<>();
        Bond b = bond(town);
        if (b != null) {
            out.add(styled(town) + " since day " + b.since() + ": tribute every week (last " + b.lastTribute() + " coins); resentment " + b.resentment()
                + "/100; freedom costs " + price(town) + " coins");
        }
        List<UUID> held = vassals(town);
        if (!held.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID h : held) names.add(Villages.name(h));
            out.add("Our vassals: " + String.join(", ", names));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    public static String makeForTests(ServerLevel level, UUID overlord, UUID vassal, long day) {
        return make(level, overlord, vassal, day, "for the tests");
    }

    public static void dailyForTests(ServerLevel level, Villages.Village v, long day) {
        daily(level, v, day);
    }

    public static void resentForTests(UUID vassal, int resentment) {
        Bond b = bond(vassal);
        if (b != null) Ledger.note(vassal, OF, new Bond(b.overlord(), b.since(), b.lastTribute(), resentment).encode());
    }
}
