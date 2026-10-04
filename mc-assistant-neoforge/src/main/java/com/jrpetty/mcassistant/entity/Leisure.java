package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A folk's own time: the evenings that belong to nobody but it.
 *
 * <p>Two evenings in three a folk does what it loves rather than simply keeping
 * company. A fisher by temperament sits at the water's edge with a rod and brings
 * home what it catches; a gardener plants a flower by its door (in a long game the
 * village fills up with them); a musician plays at the heart of the village and the
 * folk who like a tune come and stand round; a stargazer climbs to the highest
 * open ground once it is dark and looks up; a reader stands with a book; a walker
 * goes round the edge of the village; a card player finds a friend and the two of
 * them play (and like each other better for it); a whittler carves by its door.
 *
 * <p>All of it on the folk's own clock and nobody else's: work comes first, and
 * bed after.
 */
final class Leisure {

    private Leisure() {}

    /** A tune for the well: notes as semitones above F#3, rests as -1. */
    private static final int[] TUNE = {
        12, 14, 16, 12, -1, 12, 14, 16, 12, -1, 16, 17, 19, -1, 16, 17, 19, -1,
        19, 21, 19, 17, 16, 12, -1, 19, 21, 19, 17, 16, 12, -1, 12, 7, 12, -1, 12, 7, 12, -1, -1,
    };

    /** Called while it is evening and the folk is free; true when it is at its pastime. */
    static boolean evening(VillageFolkEntity f, long timeOfDay) {
        if (!(f.level() instanceof ServerLevel server) || !f.persona().rolled() || f.villageCentre() == null) return false;
        Persona me = f.persona();
        long day = f.level().getDayTime() / 24000L;
        // Two evenings in three. A sociable folk whose pastime is a lonely one keeps
        // company more often; the easygoing never miss one.
        int roll = Math.floorMod((int) (day * 31L) + f.getUUID().hashCode(), 6);
        boolean tonight = f.life().has(Social.Trait.EASYGOING) ? roll != 0 : roll < 4;
        if (f.life().has(Social.Trait.SOCIABLE) && me.hobby() != Persona.Hobby.CARDS
                && me.hobby() != Persona.Hobby.MUSIC && roll >= 3) tonight = false;
        if (me.hobby() == Persona.Hobby.STARGAZING && timeOfDay < 13000L) tonight = false;     // not dark yet
        if (!tonight) return false;
        if (f.hobbySpotDay != day || f.hobbySpot == null) {
            f.hobbySpot = spotFor(f, server, me.hobby());
            f.hobbySpotDay = day;
            f.cardPartner = null;
            if (f.hobbySpot == null) return false;
        }
        f.lastLeisureTick = f.tickCount;
        f.hobbyNow = me.hobby().doing;
        if (me.hobby() == Persona.Hobby.CARDS) {
            VillageFolkEntity friend = cardFriend(f, server);
            if (friend != null) f.hobbySpot = friend.blockPosition();
        }
        BlockPos spot = f.hobbySpot;
        double d = f.blockPosition().distSqr(spot);
        if (d > 3.0 * 3.0) {
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 100) {
                f.walkTo(spot, me.hobby() == Persona.Hobby.WALKING ? 0.7D : 0.85D);
                f.hobbyTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        if (f.persona().hobbyDay() != day) f.persona().enjoyedHobby(day);
        holdProp(f, prop(me.hobby()));
        act(f, server, me.hobby(), day);
        return true;
    }

    /** Where tonight's pastime happens. */
    @Nullable
    private static BlockPos spotFor(VillageFolkEntity f, ServerLevel server, Persona.Hobby hobby) {
        BlockPos centre = f.villageCentre();
        BlockPos home = f.bedPos() != null ? f.bedPos() : centre;
        var r = f.getRandom();
        switch (hobby) {
            case FISHING -> {
                // The nearest water's edge within reach of the village.
                for (int i = 0; i < 48; i++) {
                    int x = centre.getX() + r.nextInt(65) - 32, z = centre.getZ() + r.nextInt(65) - 32;
                    if (server.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                    int y = server.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    BlockPos w = new BlockPos(x, y, z);
                    if (!server.getFluidState(w).is(FluidTags.WATER)) continue;
                    for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos bank = w.relative(dir);
                        BlockPos stand = f.surfaceAt(bank.getX(), bank.getZ());
                        if (stand != null && server.getFluidState(stand.below()).isEmpty()
                                && Math.abs(stand.getY() - (y + 1)) <= 1) return stand;
                    }
                }
                return null;
            }
            case STARGAZING -> {
                BlockPos best = null;
                for (int i = 0; i < 10; i++) {
                    BlockPos p = f.surfaceAt(centre.getX() + r.nextInt(41) - 20, centre.getZ() + r.nextInt(41) - 20);
                    if (p != null && server.canSeeSky(p) && (best == null || p.getY() > best.getY())) best = p;
                }
                return best;
            }
            case MUSIC -> { return f.surfaceAt(centre.getX() + 2, centre.getZ() + 2); }
            case WALKING -> {
                int a = r.nextInt(360), rad = 14 + r.nextInt(16);
                return f.surfaceAt(centre.getX() + (int) (Math.cos(Math.toRadians(a)) * rad),
                    centre.getZ() + (int) (Math.sin(Math.toRadians(a)) * rad));
            }
            case CARDS -> { return f.surfaceAt(centre.getX() - 2, centre.getZ() + 1); }
            default -> { return f.surfaceAt(home.getX() + r.nextInt(5) - 2, home.getZ() + r.nextInt(5) - 2); }
        }
    }

    @Nullable
    private static VillageFolkEntity cardFriend(VillageFolkEntity f, ServerLevel server) {
        if (f.cardPartner != null && server.getEntity(f.cardPartner) instanceof VillageFolkEntity g
                && g.isAlive() && g.offWorkNow() && !g.isSleeping()) return g;
        UUID best = f.life().bestFriend();
        if (best == null) best = f.life().partner();
        if (best != null && server.getEntity(best) instanceof VillageFolkEntity g && g.isAlive()
                && g.offWorkNow() && !g.isSleeping() && g.distanceToSqr(f) < 40.0 * 40.0) {
            f.cardPartner = best;
            return g;
        }
        return null;
    }

    private static Item prop(Persona.Hobby hobby) {
        return switch (hobby) {
            case FISHING -> Items.FISHING_ROD;
            case STARGAZING -> Items.SPYGLASS;
            case GARDENING -> Items.POPPY;
            case MUSIC -> Items.GOAT_HORN;
            case READING -> Items.BOOK;
            case CARDS -> Items.PAPER;
            case WHITTLING -> Items.STICK;
            case WALKING -> Items.AIR;
        };
    }

    private static void holdProp(VillageFolkEntity f, Item item) {
        if (item == Items.AIR) return;
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.isEmpty()) {
            f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(item));
            f.propInHand = true;
        }
    }

    /** Doing it: every couple of seconds, the next bit of the pastime. */
    private static void act(VillageFolkEntity f, ServerLevel server, Persona.Hobby hobby, long day) {
        if (f.tickCount - f.hobbyTick < 40) return;
        f.hobbyTick = f.tickCount;
        var r = f.getRandom();
        Persona me = f.persona();
        switch (hobby) {
            case FISHING -> {
                BlockPos water = nearbyWater(f, server);
                if (water != null) f.getLookControl().setLookAt(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
                if (water != null && r.nextInt(7) == 0) {
                    server.sendParticles(ParticleTypes.SPLASH, water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5,
                        12, 0.3, 0.1, 0.3, 0.1);
                    f.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.6F, 1.0F);
                    f.insertItem(new ItemStack(r.nextInt(3) == 0 ? Items.SALMON : Items.COD));
                    f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                    if (r.nextBoolean()) FolkTalk.speak(f, FolkTalk.pick(r, "Got one!", "Ha! Supper.", "A beauty!"));
                    me.remember(day, "I caught a fish at the water's edge", 1);
                }
            }
            case STARGAZING -> {
                f.getLookControl().setLookAt(f.getX() + r.nextInt(9) - 4, f.getY() + 30, f.getZ() + r.nextInt(9) - 4);
                if (r.nextInt(12) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Look at them all…",
                    "There — the Great Creeper. See it?", "A shooting star! Make a wish.", "So many."));
            }
            case GARDENING -> {
                if (me.flowersPlanted() < 12 && r.nextInt(4) == 0) plant(f, server, me, day);
                else f.getLookControl().setLookAt(f.getX(), f.getY() - 1.0, f.getZ() + 1.0);
            }
            case MUSIC -> f.getLookControl().setLookAt(f.getX() + r.nextInt(5) - 2, f.getEyeY(), f.getZ() + r.nextInt(5) - 2);
            case READING -> {
                f.getLookControl().setLookAt(f.getX(), f.getY() + 0.5, f.getZ() + 1.0);
                if (r.nextInt(3) == 0) f.playSound(SoundEvents.BOOK_PAGE_TURN, 0.6F, 1.0F);
                server.sendParticles(ParticleTypes.ENCHANT, f.getX(), f.getY() + 1.8, f.getZ(), 3, 0.3, 0.2, 0.3, 0.5);
            }
            case WALKING -> {
                // On to the next stretch of the round, greeting whoever is about.
                f.hobbySpot = spotFor(f, server, hobby);
                if (r.nextInt(4) == 0) {
                    for (VillageFolkEntity g : server.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(5.0),
                            g -> g != f && g.isAlive() && !g.isSleeping())) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Evening, " + g.displayNameCap() + ".", "Lovely night for it."));
                        break;
                    }
                }
            }
            case CARDS -> {
                VillageFolkEntity friend = cardFriend(f, server);
                if (friend == null) {
                    if (r.nextInt(10) == 0) FolkTalk.speak(f, "Patience again. Nobody wants a game tonight.");
                    return;
                }
                f.getLookControl().setLookAt(friend, 30.0F, 30.0F);
                f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                f.life().feel(friend.getUUID(), friend.displayNameCap(), 1);
                friend.life().feel(f.getUUID(), f.displayNameCap(), 1);
                if (r.nextInt(5) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(r, "Read 'em and weep!", "Your deal.", "You cheat!",
                        "Ha! Mine again.", "Hmm… I'll stick."));
                    f.playSound(SoundEvents.VILLAGER_YES, 0.6F, 1.1F);
                }
                if (r.nextInt(20) == 0) me.remember(day, "I played cards with " + friend.displayNameCap(), 2);
            }
            case WHITTLING -> {
                f.getLookControl().setLookAt(f.getX(), f.getY() + 0.6, f.getZ() + 1.0);
                f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                f.playSound(SoundEvents.AXE_STRIP, 0.3F, 1.6F);
                server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.OAK_PLANKS)),
                    f.getX(), f.getY() + 1.1, f.getZ(), 4, 0.15, 0.1, 0.15, 0.02);
                if (r.nextInt(25) == 0) {
                    f.insertItem(new ItemStack(Items.BOWL));
                    FolkTalk.speak(f, FolkTalk.pick(r, "There — a bowl.", "Not bad, if I say so myself."));
                }
            }
        }
    }

    /** Every few ticks: the music itself, and putting the props away when the evening is over. */
    static void tick(VillageFolkEntity f) {
        if (f.hobbyNow == null) return;
        if (f.tickCount - f.lastLeisureTick > 200) {
            f.hobbyNow = null;
            f.cardPartner = null;
            if (f.propInHand) {
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                f.propInHand = false;
            }
            return;
        }
        if (f.persona().hobby() != Persona.Hobby.MUSIC || f.hobbySpot == null
                || f.blockPosition().distSqr(f.hobbySpot) > 9.0 || f.tickCount % 6 != 0
                || !(f.level() instanceof ServerLevel server)) return;
        int n = TUNE[f.note++ % TUNE.length];
        if (n < 0) return;
        float pitch = (float) Math.pow(2.0, (n - 12) / 12.0);
        server.playSound(null, f.getX(), f.getY(), f.getZ(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.RECORDS, 1.0F, pitch);
        server.sendParticles(ParticleTypes.NOTE, f.getX(), f.getY() + 2.2, f.getZ(), 0, n / 24.0, 0.0, 0.0, 1.0);
        if (f.note % 8 == 0) f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
    }

    /** A tune at the well draws the folk who like one: they come and stand round. */
    static boolean listen(VillageFolkEntity f, ServerLevel server) {
        if (f.persona().hobby() == Persona.Hobby.MUSIC || f.life().has(Social.Trait.SHY)) return false;
        VillageFolkEntity player = null;
        for (VillageFolkEntity g : server.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(32.0),
                g -> g != f && g.isAlive() && g.hobbyNow != null && g.persona().hobby() == Persona.Hobby.MUSIC
                    && g.hobbySpot != null && g.blockPosition().distSqr(g.hobbySpot) <= 9.0)) {
            player = g;
            break;
        }
        if (player == null) return false;
        if (f.distanceToSqr(player) > 4.0 * 4.0) {
            if (f.getNavigation().isDone()) {
                var r = f.getRandom();
                f.walkTo(player.blockPosition().offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2), 0.8D);
            }
        } else {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(player, 30.0F, 30.0F);
            if (f.getRandom().nextInt(30) == 0) {
                f.life().feel(player.getUUID(), player.displayNameCap(), 2);
                server.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.0, f.getZ(), 2, 0.2, 0.2, 0.2, 0.0);
            }
        }
        return true;
    }

    @Nullable
    private static BlockPos nearbyWater(VillageFolkEntity f, ServerLevel server) {
        BlockPos at = f.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 0, 2))) {
            if (server.getFluidState(p).is(FluidTags.WATER)) return p.immutable();
        }
        return null;
    }

    /** A flower by its door, on grass and nowhere else: one it has, out of its own pack or the
     *  village's stores. No flower to hand, none planted (not one out of nowhere). */
    private static void plant(VillageFolkEntity f, ServerLevel server, Persona me, long day) {
        var r = f.getRandom();
        BlockPos at = f.blockPosition();
        for (int i = 0; i < 8; i++) {
            BlockPos ground = at.offset(r.nextInt(5) - 2, -1, r.nextInt(5) - 2);
            BlockPos above = ground.above();
            if (!server.getBlockState(ground).is(Blocks.GRASS_BLOCK) || !server.getBlockState(above).isAir()) continue;
            Block flower = flowerToHand(f, server, above);
            if (flower == null) return;
            server.setBlockAndUpdate(above, flower.defaultBlockState());
            server.playSound(null, above, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, above.getX() + 0.5, above.getY() + 0.5, above.getZ() + 0.5,
                4, 0.3, 0.2, 0.3, 0.0);
            f.getLookControl().setLookAt(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            me.plantedAFlower();
            if (me.flowersPlanted() == 1) me.remember(day, "I planted my first flower here", 3);
            if (r.nextBoolean()) FolkTalk.speak(f, FolkTalk.pick(r, "There. Lovely.", "Grow well, little one.",
                "That's brightened the place up."));
            return;
        }
    }

    /** A flower to plant here, taken out of the folk's own pack, else out of its village's stores;
     *  null if it has none. The one it takes is the one that goes in the ground. */
    @Nullable
    private static Block flowerToHand(VillageFolkEntity f, ServerLevel server, BlockPos at) {
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !s.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS)) continue;
            Item mine = s.getItem();
            Block b = Block.byItem(mine);
            if (b == Blocks.AIR || !b.defaultBlockState().canSurvive(server, at)) continue;
            if (f.removeMatching(st -> st.is(mine), 1) == 1) return b;
        }
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        ItemStack one = Crafts.takeOne(server, v, st -> st.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS));
        if (one.isEmpty()) return null;
        Block b = Block.byItem(one.getItem());
        if (b == Blocks.AIR || !b.defaultBlockState().canSurvive(server, at)) {
            Crafts.store(server, v, one);
            return null;
        }
        return b;
    }
}
