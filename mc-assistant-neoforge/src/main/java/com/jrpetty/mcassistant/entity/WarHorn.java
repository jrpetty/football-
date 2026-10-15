package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.WarItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [war] The war horn's calls, and who answers them.
 *
 * <p>Each signal has a call of its own, the goat horn's: <b>muster</b> "Ponder", <b>march</b> "Seek", <b>form up</b>
 * "Admire", <b>begin</b> "Feel", <b>charge</b> "Sing", <b>rally</b> "Call", <b>retreat</b> "Yearn", <b>victory</b>
 * "Dream". An army's Captain blows them on its War Horn (WarItems: out of the stores, or made there at the muster
 * from a goat horn and a leather, else two bones, a copper ingot and a leather); an army without one beats a drum.
 * A town with a horn in its stores sounds it for its alarm. A player blowing one in the field, who leads the town
 * or fights in its army, gives the army that order: charge, rally, retreat, form up.
 */
public final class WarHorn {

    private WarHorn() {}

    /** A call: what it means, in words, and its goat horn (SoundEvents.GOAT_HORN_SOUND_VARIANTS: ponder, sing, seek, feel, admire, call, yearn, dream). */
    public enum Signal {
        MUSTER("muster", 0), MARCH("march", 2), FORM("form up", 4), BEGIN("begin", 3), CHARGE("charge", 1), RALLY("rally", 5),
        RETREAT("retreat", 6), VICTORY("victory", 7);

        public final String words;
        public final int horn;

        Signal(String words, int horn) {
            this.words = words;
            this.horn = horn;
        }
    }

    /** As loud as a goat horn: heard a long way off. */
    static final float LOUD = 16.0F;

    /** The call sounded here, on a horn. */
    public static void sound(ServerLevel level, BlockPos at, Signal s) {
        level.playSound(null, at, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(s.horn).value(), SoundSource.RECORDS, LOUD, 1.0F);
    }

    /** An army's signal: its Captain's horn if it carries one (else a roll of the drum), from where the Captain stands. */
    static void signal(ServerLevel level, @Nullable Armies.Army a, BlockPos fallback, Signal s) {
        BlockPos at = fallback;
        Armies.Fighter cap = a == null ? null : a.officer(Armies.Rank.CAPTAIN);
        VillageFolkEntity e = cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity v ? v : null;
        if (e != null) at = e.blockPosition();
        if (a != null && a.horn) {
            sound(level, at, s);
            if (e != null) e.swing(net.minecraft.world.InteractionHand.OFF_HAND);
        } else {
            Armies.drum(level, at, false);
            Armies.drum(level, at.above(), true);
        }
    }

    /**
     * The Captain's horn for the field: its own if it carries one, else out of the stores, else made there (a goat horn
     * and a leather; or two bones, a copper ingot and a leather). Whether it has one.
     */
    static boolean arm(ServerLevel level, Villages.Village v, VillageFolkEntity captain) {
        if (captain.countMatching(s -> s.is(WarItems.WAR_HORN.get())) > 0) return true;
        ItemStack h = Arms.takeOne(level, v, s -> s.is(WarItems.WAR_HORN.get()), captain.displayNameCap());
        if (h == null) {
            boolean made = TownWork.take(level, v, s -> s.is(Items.GOAT_HORN), 1) && takeOrGive(level, v, Items.LEATHER, 1, new ItemStack(Items.GOAT_HORN));
            if (!made && TownWork.take(level, v, s -> s.is(Items.BONE), 2)) {
                made = takeOrGive(level, v, Items.COPPER_INGOT, 1, new ItemStack(Items.BONE, 2))
                    && takeOrGive(level, v, Items.LEATHER, 1, new ItemStack(Items.COPPER_INGOT));
                if (!made) TownWork.give(level, v, new ItemStack(Items.BONE, 2));
            }
            if (!made) return false;
            h = new ItemStack(WarItems.WAR_HORN.get());
            Villages.tell(v.id(), level.getDayTime() / 24000L, "a war horn was made for " + captain.displayNameCap() + ", the Captain");
        }
        ItemStack left = captain.insertGiven(h);
        if (!left.isEmpty()) TownWork.give(level, v, left);
        return left.isEmpty();
    }

    /** One of the makings out of the stores, or what was taken for it given back. */
    private static boolean takeOrGive(ServerLevel level, Villages.Village v, net.minecraft.world.item.Item it, int n, ItemStack back) {
        if (TownWork.take(level, v, s -> s.is(it), n)) return true;
        TownWork.give(level, v, back);
        return false;
    }

    /** [Raids] The alarm sounded on the town's horn, if its stores hold one: the rally call over the square. */
    public static void alarm(ServerLevel level, Villages.Village v) {
        if (Market.stock(level, v.id(), s -> s.is(WarItems.WAR_HORN.get())) <= 0) return;
        sound(level, v.centre(), Signal.RALLY);
    }

    /**
     * [WarHornItem] A player's call: sounded; and if the player leads a town (PlayerLeader) or fights in its army, and that
     * army is in a battle near by, the army takes the order: a charge, a rally, a retreat in good order, the lines dressed.
     */
    public static void blown(ServerLevel level, Player p, Signal s) {
        sound(level, p.blockPosition(), s);
        for (Campaigns.Campaign c : Campaigns.activeForTests()) {
            Battles.Battle b = c.battle();
            if (b == null || b.over() || !b.detailed()) continue;
            if (p.distanceToSqr(b.centre.getX(), p.getY(), b.centre.getZ()) > 96 * 96) continue;
            for (int i = 0; i < 2; i++) {
                Armies.Army a = b.side[i];
                if (a == null || !commands(p, a.town)) continue;
                String done = b.horn(level, i, s);
                if (done != null) {
                    p.displayClientMessage(Component.literal(Villages.name(a.town) + "'s army: " + done).withStyle(ChatFormatting.GOLD), true);
                    WarBooks.course(a.town, a.enemy, level.getDayTime() / 24000L, p.getName().getString() + " sounded the " + s.words + " on the war horn");
                }
                return;
            }
        }
    }

    /** Does this player command that town's army: it leads the town, or fights in its ranks as one of its own? */
    static boolean commands(Player p, UUID town) {
        return PlayerLeader.leads(town, p.getUUID()) || Armies.volunteerFor(p, town) && Ledger.citizen(town, p.getUUID());
    }
}
