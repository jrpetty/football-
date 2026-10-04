package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A player's place in a village.
 * <ul>
 * <li><b>Citizenship.</b> Ask any folk "May I live here?". A friend of the village becomes
 *     one of its citizens: free, if the village has built them a house; otherwise for twenty
 *     coins to the treasury, and then the village builds them one on one of its lots. A
 *     citizen has a vote on the council, ten off at the shop and the café, and may take what
 *     it needs from the village's stores without it counting as theft.</li>
 * <li><b>A title over your name.</b> "Citizen of Oakford", "Honoured guest of Oakford", "Hero of
 *     Oakford" — the best a player has anywhere, shown after its name over its head, in the
 *     list of players and in chat (unless it is on a team of its own).</li>
 * <li><b>Statues.</b> When a player becomes a village's hero, the village raises a statue to
 *     it on the square: its likeness in gold on a carved plinth, with its name on a plaque.</li>
 * </ul>
 */
public final class Citizens {

    private Citizens() {}

    /** What a place costs a player the village hasn't built a house for. */
    public static final int FEE = 20;

    public static boolean is(@Nullable UUID village, UUID player) {
        return village != null && Ledger.citizen(village, player);
    }

    /** "May I live here?" — the folk's answer, and the deed if it is yes. */
    public static String ask(VillageFolkEntity f, Player p) {
        UUID village = f.ownerId();
        if (village == null) return "Live where? I've no village myself.";
        String name = p.getName().getString();
        if (is(village, p.getUUID())) {
            return "You're one of us already, " + name + ". You've a vote on the council, and the stores are yours as much as ours.";
        }
        Standing.Title title = Standing.of(village, p.getUUID(), f.level().getGameTime()).title();
        if (!title.atLeast(Standing.Title.FRIEND)) {
            return "Live here? We hardly know you yet. Be a friend to " + Villages.name(village) + " first.";
        }
        if (Laws.owes(village, p.getUUID()) > 0 || Laws.banished(village, p.getUUID(), f.level().getDayTime() / 24000L)) {
            return "Not while you owe the village a fine.";
        }
        Chronicle.Guest guest = Chronicle.guest(village, p.getUUID());
        boolean housed = guest != null && guest.built;
        int fee = housed ? 0 : FEE;
        if (fee > 0) {
            int coins = Market.coinsHeld(p);
            if (coins < fee) return "A citizen has a house here, or pays " + fee + " coins to the treasury for a place. You've "
                + coins + ". Come back when you have it.";
            Market.payOut(p, fee);
            Ledger.addCoins(village, fee);
        }
        Ledger.addCitizen(village, p.getUUID(), name);
        long day = f.level().getDayTime() / 24000L;
        Villages.tell(village, day, name + " became a citizen of " + Villages.name(village));
        // A citizen needs a home: the village builds one, as it does for its honoured guests.
        if (guest == null) Chronicle.welcome(village, p.getUUID(), name);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o) o.persona().feelFor(p.getUUID(), name, 5);
        }
        Standing.stir(village, p.getUUID());
        if (p instanceof ServerPlayer sp) refresh(sp);
        return "Welcome, citizen " + name + "! " + (housed ? "Your house is your own. " : "We'll build you a house on one of our lots. ")
            + "You've a vote on the council now, and the stores are yours as much as ours.";
    }

    // ------------------------------------------------------------------ titles

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 200 != 37) return;
        refresh(p);
    }

    /** The best a player is anywhere, in words, or null. */
    @Nullable
    public static String best(ServerPlayer p) {
        String hero = null, citizen = null, honoured = null;
        long now = p.level().getGameTime();
        for (Villages.Village v : Villages.every()) {
            Standing.Title t = Standing.of(v.id(), p.getUUID(), now).title();
            String name = Villages.name(v.id());
            if (t == Standing.Title.HERO && hero == null) hero = "Hero of " + name;
            if (is(v.id(), p.getUUID()) && citizen == null) citizen = "Citizen of " + name;
            if (t == Standing.Title.HONOURED && honoured == null) honoured = "Honoured guest of " + name;
        }
        return hero != null ? hero : citizen != null ? citizen : honoured;
    }

    /** Put the player's title after its name (a team of its own), or take it off. */
    public static void refresh(ServerPlayer p) {
        Scoreboard board = p.getServer() == null ? null : p.getServer().getScoreboard();
        if (board == null) return;
        String key = "mca" + p.getUUID().toString().replace("-", "").substring(0, 12);
        PlayerTeam now = board.getPlayersTeam(p.getScoreboardName());
        if (now != null && !now.getName().equals(key)) return;          // on a team of its own: leave it be
        String title = best(p);
        if (title == null) {
            if (now != null) board.removePlayerFromTeam(p.getScoreboardName(), now);
            return;
        }
        PlayerTeam team = board.getPlayerTeam(key);
        if (team == null) team = board.addPlayerTeam(key);
        Component suffix = Component.literal(" · " + title).withStyle(ChatFormatting.GOLD);
        if (!team.getPlayerSuffix().getString().equals(suffix.getString())) team.setPlayerSuffix(suffix);
        if (now == null) board.addPlayerToTeam(p.getScoreboardName(), team);
    }

    // ------------------------------------------------------------------ statues

    /** Where the square's statues stand, in the order they are raised. */
    static final int[][] STATUES = { { 5, -5 }, { -5, -5 }, { 5, 5 } };

    /** The village raises a statue to its hero, if it hasn't and has room on the square. */
    public static boolean statue(ServerLevel level, Villages.Village v, Player hero) {
        if (Ledger.statue(v.id(), hero.getUUID())) return false;
        int n = Ledger.statues(v.id());
        if (n >= STATUES.length) return false;
        BlockPos spot = v.centre().offset(STATUES[n][0], 0, STATUES[n][1]);
        com.mojang.authlib.GameProfile face = hero instanceof ServerPlayer sp ? sp.getGameProfile() : null;
        if (raise(level, v, spot, v.centre(), hero.getName().getString(), face, Villages.name(v.id()), false) == null) return false;
        Ledger.raisedStatue(v.id(), hero.getUUID(), hero.getName().getString());
        Villages.tell(v.id(), level.getDayTime() / 24000L, "a statue of " + hero.getName().getString() + " was raised on the square");
        Raids.tellNear(level, v.centre(), 160, Component.literal(Villages.name(v.id()) + " has raised a statue to "
            + hero.getName().getString() + ", its hero!").withStyle(ChatFormatting.GOLD), false);
        return true;
    }

    /**
     * The statue itself, for nothing (the showcase): a carved plinth, the hero's likeness in gold
     * upon it facing the heart of the village, and a plaque. Returns the plinth, or null if the
     * spot is not clear.
     */
    @Nullable
    public static BlockPos raise(ServerLevel level, BlockPos spot, BlockPos heart, String name,
                                 @Nullable com.mojang.authlib.GameProfile face, String village) {
        return raise(level, null, spot, heart, name, face, village, true);
    }

    /**
     * The statue, out of the village's stores unless {@code free}: the stand (an armour stand put
     * by, or three planks for its sticks and a cobblestone for its foot) and the plinth (a block
     * of stone bricks or cobble) or no statue; the likeness carved out of another block of stone,
     * or the statue goes bareheaded; the gold it wears only what gold armour and blade the stores
     * hold, piece by piece; the plaque a sign (or two planks), or none. Its gear is fixed to it: a
     * passer-by can't help themselves to it.
     */
    @Nullable
    static BlockPos raise(ServerLevel level, @Nullable Villages.Village v, BlockPos spot, BlockPos heart, String name,
                          @Nullable com.mojang.authlib.GameProfile face, String village, boolean free) {
        if (!free && v == null) return null;
        if (!level.isLoaded(spot)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
        BlockPos plinth = new BlockPos(spot.getX(), y, spot.getZ());
        if (!level.getBlockState(plinth).isAir() || !level.getBlockState(plinth.above()).isAir()
                || !level.getBlockState(plinth.above(2)).isAir()) return null;
        ArmorStand st = EntityType.ARMOR_STAND.create(level);
        if (st == null) return null;
        if (!free) {
            boolean stand = Crafts.take(level, v, s -> s.is(Items.ARMOR_STAND), 1);
            if (!stand) {
                if (Crafts.stock(level, v, s -> s.is(Items.COBBLESTONE)) < 1 || !Crafts.usePlanks(level, v, 3)
                        || !Crafts.take(level, v, s -> s.is(Items.COBBLESTONE), 1)) return null;
            }
            if (!Crafts.masonry(level, v)) {
                Crafts.store(level, v, new ItemStack(Items.ARMOR_STAND));          // made, and kept for another day
                return null;
            }
        }
        level.setBlock(plinth, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
        // Facing the heart of the village.
        Direction toHeart = Direction.getNearest(heart.getX() - spot.getX(), 0, heart.getZ() - spot.getZ());
        st.moveTo(plinth.getX() + 0.5, plinth.getY() + 1, plinth.getZ() + 0.5, toHeart.toYRot(), 0.0F);
        st.setNoGravity(true);
        st.setInvulnerable(true);
        st.setShowArms(true);
        st.setNoBasePlate(true);
        if (free || Crafts.masonry(level, v)) {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            if (face != null) head.set(DataComponents.PROFILE, new ResolvableProfile(face));
            st.setItemSlot(EquipmentSlot.HEAD, head);
        }
        st.setItemSlot(EquipmentSlot.CHEST, gold(level, v, Items.GOLDEN_CHESTPLATE, free));
        st.setItemSlot(EquipmentSlot.LEGS, gold(level, v, Items.GOLDEN_LEGGINGS, free));
        st.setItemSlot(EquipmentSlot.FEET, gold(level, v, Items.GOLDEN_BOOTS, free));
        st.setItemSlot(EquipmentSlot.MAINHAND, gold(level, v, Items.GOLDEN_SWORD, free));
        // Fixed: nothing put on it or taken off it by hand (every slot of the stand shut).
        net.minecraft.nbt.CompoundTag t = st.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        t.putInt("DisabledSlots", 4144959);
        st.load(t);
        st.addTag("mca_statue");
        level.addFreshEntity(st);
        // The plaque, on the plinth's face toward the heart.
        BlockPos plaque = plinth.relative(toHeart);
        if (level.getBlockState(plaque).isAir() && (free || Crafts.sign(level, v))) {
            level.setBlock(plaque, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, toHeart), 3);
            if (level.getBlockEntity(plaque) instanceof SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ name, "Hero of", village, "day " + level.getDayTime() / 24000L });
            }
        }
        return plinth;
    }

    /** A piece of the statue's gold: out of the stores if they hold one (for nothing in the
     *  showcase), else the statue goes without it. */
    private static ItemStack gold(ServerLevel level, @Nullable Villages.Village v, net.minecraft.world.item.Item piece, boolean free) {
        if (free) return new ItemStack(piece);
        if (v == null) return ItemStack.EMPTY;
        return Crafts.takeOne(level, v, s -> s.is(piece));
    }
}
