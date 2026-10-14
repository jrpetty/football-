package com.jrpetty.mcassistant.entity;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/**
 * [wagons] The three wagons a town builds as it grows, and what each is: a handcart the driver pulls in the Wood Age,
 * an open wagon with side boards behind a draught animal from the Stone Age, and from the Iron Age a bigger covered
 * wagon under a canvas in the town's colours with iron tyres and two lanterns. Each is made at the bench from real
 * makings out of the stores (Wagons.build). The client draws each from its own model (client/TradeWagonModel).
 */
public enum WagonKind {
    HANDCART("handcart", Villages.Age.WOOD, 9, 0.11, false, false, 0, 16.0F,
        List.of(new Making("planks", s -> s.is(ItemTags.PLANKS), 10), new Making("sticks", s -> s.is(Items.STICK), 4))),
    WAGON("wagon", Villages.Age.STONE, 18, 0.15, true, false, 1, 24.0F,
        List.of(new Making("planks", s -> s.is(ItemTags.PLANKS), 24), new Making("sticks", s -> s.is(Items.STICK), 8),
            new Making("leather for the harness", s -> s.is(Items.LEATHER), 2), new Making("torches", s -> s.is(Items.TORCH), 2))),
    COVERED("covered wagon", Villages.Age.IRON, 27, 0.17, true, true, 2, 32.0F,
        List.of(new Making("planks", s -> s.is(ItemTags.PLANKS), 32), new Making("sticks", s -> s.is(Items.STICK), 8),
            new Making("leather for the harness", s -> s.is(Items.LEATHER), 4), new Making("wool for the canvas", s -> s.is(ItemTags.WOOL), 6),
            new Making("iron for the tyres and fittings", s -> s.is(Items.IRON_INGOT), 4), new Making("lanterns", s -> s.is(Items.LANTERN), 2)));

    /** One of the makings: what, and how many. */
    public record Making(String words, Predicate<ItemStack> what, int count) {}

    public final String label;
    /** The age a town must have reached to build one. */
    public final Villages.Age age;
    /** Stacks of goods it carries. */
    public final int slots;
    /** Its pace on the level road, in blocks a tick (a horse at a steady walk draws the covered wagon at about 3.4 a second). */
    public final double speed;
    /** Drawn by a horse, a donkey, a mule or a llama (else the driver pulls it by its handles). */
    public final boolean drawn;
    /** A canvas over hoops, dyed in the town's colours. */
    public final boolean covered;
    /** Lamps lit at night. */
    public final int lamps;
    /** How much battering it takes before it is wrecked. */
    public final float health;
    public final List<Making> makings;

    WagonKind(String label, Villages.Age age, int slots, double speed, boolean drawn, boolean covered, int lamps, float health,
              List<Making> makings) {
        this.label = label;
        this.age = age;
        this.slots = slots;
        this.speed = speed;
        this.drawn = drawn;
        this.covered = covered;
        this.lamps = lamps;
        this.health = health;
        this.makings = makings;
    }

    /** The best a town of this age may build. */
    public static WagonKind forAge(Villages.Age age) {
        WagonKind best = HANDCART;
        for (WagonKind k : values()) if (k.age.ordinal() <= age.ordinal()) best = k;
        return best;
    }

    /** By its name, or null. */
    @Nullable
    public static WagonKind byName(@Nullable String s) {
        if (s == null) return null;
        for (WagonKind k : values()) if (k.name().equals(s)) return k;
        return null;
    }

    /** By its index (the client's synced byte), clamped. */
    public static WagonKind byIndex(int i) {
        WagonKind[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, i))];
    }

    /** "10 planks and 4 sticks". */
    public String makingsWords() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < makings.size(); i++) {
            if (i > 0) sb.append(i == makings.size() - 1 ? " and " : ", ");
            sb.append(makings.get(i).count()).append(' ').append(makings.get(i).words());
        }
        return sb.toString();
    }
}
