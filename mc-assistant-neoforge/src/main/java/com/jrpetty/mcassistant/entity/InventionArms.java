package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.item.ArbalestItem;
import com.jrpetty.mcassistant.item.InventionItems;
import com.jrpetty.mcassistant.item.LongbowItem;
import com.jrpetty.mcassistant.item.PolearmItem;
import com.jrpetty.mcassistant.village.ArmsEdge;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [tech-tree] The new arms the town's inventions bring (item/InventionItems): who makes them, who carries them, and what
 * they do, in a fight in front of a player and in a battle reckoned on paper.
 *
 * <h2>Made</h2>
 * Out of the stores, by the game's own counts, a piece of work at a time (Crafts.smith, Fletchers.piece):
 * <ul>
 * <li><b>The smith</b>, as the town knows them and the watch wants them: pikes (an iron ingot, two sticks) and war
 *     hammers (three iron, two sticks) first, then steel swords (two steel, a stick), halberds (two steel, two sticks)
 *     and steel armour (five, eight, seven and four steel): a player's recipes, the same counts. The steel for each it
 *     blasts at the foundry as it is wanted, an iron ingot and a coal (or charcoal) to an ingot, never the bars kept back
 *     for the next pick. Never the iron the town is still putting by for its age, but on a war footing.</li>
 * <li><b>The fletcher</b> (the smith, with no fletcher): longbows (four sticks, three string) for half the town's archers,
 *     and once the town knows the Arbalest, an arbalest of a crossbow out of the stores, two iron and a steel ingot.</li>
 * </ul>
 * What they make goes into the stores; the watch's kit-out (WatchKit.fit) takes the steel swords and armour on its own
 * reckoning, the bows and crossbows go out as ever (Armies.issueMissiles), and a folk reaching for a weapon reaches for
 * these before an iron blade ({@link #weaponScore}).
 *
 * <h2>Carried into the field</h2>
 * When a town raises its army (Armies.raise) the line is given the polearms out of the armoury and the stores: a pike or
 * a halberd to one in three of the line, one in two when the enemy keeps horses; a war hammer to one in four when the
 * enemy goes armoured (its age past the Stone Age). Each takes it in hand and keeps its own blade in its pack; home
 * again, the town's polearms go back where they came from ({@link #returnKit}).
 *
 * <h2>What they do</h2>
 * In a fight (a player's or a folk's: the same blow): a pike strikes a rider or its horse half again as hard, a halberd a
 * third again; a war hammer adds a quarter of its mark's armour points in harm (five at most), a halberd half that. An
 * arrow off a longbow flies a fifth faster and strikes a quarter harder, a bolt off an arbalest half again as hard. On
 * paper (BattleModel, through ArmsEdge) the same: pikes braced against riders, hammers through plate, the longbows and
 * arbalests in the archers' duel; and steel in a fighter's own power. At a siege an iron-shod ram (Siege Engines) breaks
 * a gate in six blows, a powder keg (Black Powder) in three, and the defenders take the less heart from their walls.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class InventionArms {

    private InventionArms() {}

    /** Steel ingots the stores keep. */
    static final int STEEL_KEPT = 16;
    /** A pike to a rider, a halberd: the blow, as a share. A hammer's armour through, a halberd's: a share of the armour's points. */
    static final float PIKE_VS_RIDER = 1.5F, HALBERD_VS_RIDER = 1.33F, HAMMER_THROUGH = 0.25F, HALBERD_THROUGH = 0.12F, THROUGH_MOST = 5.0F;
    /** A longbow's arrow: its speed and its blow, as shares. An arbalest's. */
    static final double LONGBOW_SPEED = 1.2, LONGBOW_HIT = 1.25, ARBALEST_SPEED = 1.1, ARBALEST_HIT = 1.5;
    /** The mark on a polearm lent out of the armoury for the field, so it goes back. */
    static final String LENT = "mca_field_kit";

    /** Each fighter's new arms, as it was kitted for the field: bits of PIKE, HALBERD, HAMMER, LONGBOW, ARBALEST. */
    private static final Map<UUID, Integer> KIT = new ConcurrentHashMap<>();
    static final int B_PIKE = 1, B_HALBERD = 2, B_HAMMER = 4, B_LONGBOW = 8, B_ARBALEST = 16;

    static void resetForTests() {
        KIT.clear();
    }

    /** Is it one of the inventions that arm the town? */
    static boolean ofArms(Invention i) {
        return switch (i) {
            case LONGBOW, PIKE, WAR_HAMMER, STEEL, ARBALEST, HALBERD, SIEGE_ENGINES -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ what a thing is

    static boolean steel(ItemStack s) {
        return s.is(InventionItems.STEEL_SWORD.get()) || s.getItem() instanceof ArmorItem a && a.getMaterial().is(InventionItems.STEEL_ARMOUR.getKey());
    }

    @Nullable
    static PolearmItem.Kind pole(ItemStack s) {
        return s.getItem() instanceof PolearmItem p ? p.kind : null;
    }

    /**
     * What a folk makes of a weapon when it reaches for its best (AssistantEntity.weaponScore, which goes by the metal's
     * name and would rank these below a wooden sword): a steel sword and a halberd over iron, a pike and a war hammer just
     * over an iron sword, all of them under diamond. 0 for anything else (the usual reckoning stands).
     */
    public static int weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.is(InventionItems.HALBERD.get())) return 54;
        if (s.is(InventionItems.STEEL_SWORD.get())) return 53;
        if (s.is(InventionItems.WAR_HAMMER.get())) return 50;
        if (s.is(InventionItems.PIKE.get())) return 49;
        return 0;
    }

    /** A fighter's worth for its new arms, over what BattleModel.power gives it (Armies.power): steel and the polearms. */
    public static int power(VillageFolkEntity f) {
        int best = 0;
        List<ItemStack> all = new ArrayList<>(f.getInventoryItems());
        all.add(f.getMainHandItem());
        for (ItemStack s : all) {
            if (s.is(InventionItems.HALBERD.get())) best = Math.max(best, 2);
            else if (s.is(InventionItems.STEEL_SWORD.get()) || s.is(InventionItems.WAR_HAMMER.get()) || s.is(InventionItems.PIKE.get())) best = Math.max(best, 1);
        }
        return best;
    }

    /** Its new arms as bits (what it carries now). */
    static int bits(VillageFolkEntity f) {
        int b = 0;
        List<ItemStack> all = new ArrayList<>(f.getInventoryItems());
        all.add(f.getMainHandItem());
        for (ItemStack s : all) {
            if (s.is(InventionItems.PIKE.get())) b |= B_PIKE;
            if (s.is(InventionItems.HALBERD.get())) b |= B_HALBERD;
            if (s.is(InventionItems.WAR_HAMMER.get())) b |= B_HAMMER;
            if (s.getItem() instanceof LongbowItem) b |= B_LONGBOW;
            if (s.getItem() instanceof ArbalestItem) b |= B_ARBALEST;
        }
        return b;
    }

    // ------------------------------------------------------------------ the smith and the fletcher

    /**
     * One of the smith's pieces of the new arms (Crafts.smith, before its iron forging), or null: the first of them the
     * stores keep too few of, the cheap iron ones first (the pike, the hammer), then the longbows if the town has no
     * fletcher, then the steel ones (the sword, the halberd, the armour), the steel for each blasted at the foundry as it
     * is wanted, an ingot a piece of work.
     */
    @Nullable
    public static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        boolean war = WarFooting.ready(id);
        boolean saving = Crafts.savingIron(level, v) && !war;
        if (saving) return null;                                     // the age's iron first, but on a war footing
        int watch = Math.max(1, WarFooting.armsFor(id, WatchKit.watch(id).size()));
        int skill = f.veteranLevel();
        if (Inventions.known(id, Invention.PIKE)) {
            String made = forge(level, v, f, skill, InventionItems.PIKE.get(), s -> s.is(Items.IRON_INGOT), 1, 2, Math.max(2, watch / 2));
            if (made != null) return made;
        }
        if (Inventions.known(id, Invention.WAR_HAMMER)) {
            String made = forge(level, v, f, skill, InventionItems.WAR_HAMMER.get(), s -> s.is(Items.IRON_INGOT), 3, 2, Math.max(1, watch / 3));
            if (made != null) return made;
        }
        if (!Fletchers.keeps(id)) {
            String made = fletch(level, v, f);
            if (made != null) return made;
        }
        if (!Inventions.known(id, Invention.STEEL)) return null;
        // The steel pieces, in turn: each one's steel blasted first if the stores have not the bars for it.
        Object[][] wants = {
            { InventionItems.STEEL_SWORD.get(), 2, 1, Math.min(3, watch / 2 + 1), true },
            { InventionItems.HALBERD.get(), 2, 2, Math.max(1, watch / 2), Inventions.known(id, Invention.HALBERD) },
            { InventionItems.STEEL_CHESTPLATE.get(), 8, 0, Math.min(2, watch / 2), true },
            { InventionItems.STEEL_HELMET.get(), 5, 0, Math.min(2, watch / 2), true },
            { InventionItems.STEEL_LEGGINGS.get(), 7, 0, Math.min(2, watch / 2), true },
            { InventionItems.STEEL_BOOTS.get(), 4, 0, Math.min(2, watch / 2), true } };
        for (Object[] w : wants) {
            if (!(Boolean) w[4]) continue;
            Item it = (Item) w[0];
            int bars = (Integer) w[1], keep = (Integer) w[3];
            if (Crafts.stock(level, v, s -> s.is(it)) + WarWorks.inArmoury(level, id, s -> s.is(it)) >= keep) continue;
            if (Crafts.stock(level, v, steelIngot()) < bars + 2) {
                String blasted = blast(level, v);
                if (blasted != null) return blasted;
                continue;                                            // no iron or coal to spare: the next, cheaper piece
            }
            String made = forge(level, v, f, skill, it, steelIngot(), bars, (Integer) w[2], keep);
            if (made != null) return made;
        }
        return null;
    }

    /**
     * An ingot of steel blasted at the foundry, once the town knows Steel and the foundry stands: an iron ingot and a coal
     * (or charcoal), the iron the smith keeps back for the next pick and blade never touched. Null if it cannot.
     */
    @Nullable
    static String blast(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!Inventions.known(id, Invention.STEEL) || !InventionWorks.stands(id, InventionWorks.FOUNDRY)) return null;
        if (Crafts.stock(level, v, steelIngot()) >= STEEL_KEPT) return null;
        if (Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) <= Crafts.IRON_KEPT || Crafts.stock(level, v, s -> s.is(ItemTags.COALS)) < 1) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 1)) return null;
        if (!Crafts.take(level, v, s -> s.is(ItemTags.COALS), 1)) {
            Crafts.store(level, v, new ItemStack(Items.IRON_INGOT));
            return null;
        }
        Crafts.store(level, v, new ItemStack(InventionItems.STEEL_INGOT.get()));
        InventionWorks.foundryFire(level, v);
        return "an ingot of steel at the foundry";
    }

    static Predicate<ItemStack> steelIngot() {
        return s -> s.is(InventionItems.STEEL_INGOT.get());
    }

    /** One piece forged if the stores keep fewer than {@code keep} and have the metal (a few bars kept back) and the sticks. */
    @Nullable
    static String forge(ServerLevel level, Villages.Village v, VillageFolkEntity f, int skill, Item it, Predicate<ItemStack> metal, int bars,
                        int sticks, int keep) {
        if (Crafts.stock(level, v, s -> s.is(it)) + WarWorks.inArmoury(level, v.id(), s -> s.is(it)) >= keep) return null;
        if (Crafts.stock(level, v, metal) < bars + 2) return null;
        if (sticks > 0 && Crafts.stock(level, v, s -> s.is(Items.STICK)) < sticks && !Crafts.planks(level, v, (sticks + 1) / 2)) return null;
        if (!Crafts.take(level, v, metal, bars)) return null;
        if (sticks > 0 && !Crafts.take(level, v, s -> s.is(Items.STICK), sticks)) {
            Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), (sticks + 1) / 2);
        }
        ItemStack made = Craftsmanship.finish(level, new ItemStack(it), skill, f.displayNameCap());
        Crafts.store(level, v, made.copy());
        Inventions.firstMade(level, v, made, f);
        return "a " + made.getHoverName().getString().toLowerCase().replace("working model: ", "");
    }

    /**
     * The fletcher's piece of the new arms (Fletchers.piece, after the crossbows; the smith's with no fletcher): longbows
     * for half the town's archers, and an arbalest or two once the town knows how. Null if nothing wanted.
     */
    @Nullable
    public static String fletch(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        int archers = 0;
        for (VillageFolkEntity g : WatchKit.watch(id)) if (WarAndPeace.archer(g)) archers++;
        archers = Math.max(2, archers);
        if (Inventions.known(id, Invention.LONGBOW)
                && Crafts.stock(level, v, s -> s.getItem() instanceof LongbowItem) + WarWorks.inArmoury(level, id, s -> s.getItem() instanceof LongbowItem) < (archers + 1) / 2
                && Crafts.stock(level, v, s -> s.is(Items.STRING)) >= 3 && (Crafts.stock(level, v, s -> s.is(Items.STICK)) >= 4 || Crafts.planks(level, v, 2))) {
            if (!Crafts.take(level, v, s -> s.is(Items.STRING), 3)) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.STICK), 4)) Crafts.take(level, v, s -> s.is(ItemTags.PLANKS), 2);
            ItemStack bow = Craftsmanship.finish(level, new ItemStack(InventionItems.LONGBOW.get()), f.veteranLevel(), f.displayNameCap());
            Crafts.store(level, v, bow.copy());
            Inventions.firstMade(level, v, bow, f);
            return "a longbow";
        }
        if (Inventions.known(id, Invention.ARBALEST)
                && Crafts.stock(level, v, s -> s.getItem() instanceof ArbalestItem) + WarWorks.inArmoury(level, id, s -> s.getItem() instanceof ArbalestItem) < Math.max(2, archers / 3)
                && Crafts.stock(level, v, s -> s.is(Items.CROSSBOW)) >= 1 && Crafts.stock(level, v, s -> s.is(Items.IRON_INGOT)) >= 2 + 2
                && Crafts.stock(level, v, steelIngot()) >= 1) {
            if (!Crafts.take(level, v, s -> s.is(Items.CROSSBOW), 1)) return null;
            if (!Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 2) || !Crafts.take(level, v, steelIngot(), 1)) {
                Crafts.store(level, v, new ItemStack(Items.CROSSBOW));
                return null;
            }
            ItemStack a = Craftsmanship.finish(level, new ItemStack(InventionItems.ARBALEST.get()), f.veteranLevel(), f.displayNameCap());
            Crafts.store(level, v, a.copy());
            Inventions.firstMade(level, v, a, f);
            return "an arbalest, a crossbow fitted with a steel prod";
        }
        return null;
    }

    /**
     * Who makes each of the new arms, and when the town wants one (Makers: the books, the docs and the item audit ask it).
     * Said at the mod's start (InventionItems.register), beside the makers themselves.
     */
    public static void declareMakers() {
        String steel = "once the town knows Steel and its foundry stands, as the steel pieces want it (sixteen at most)";
        Makers.declare("steel_ingot", "the smith, at the foundry", steel + ": an iron ingot and a coal to each", "InventionArms.smith");
        String watch = "once the town knows Steel, while the watch (and on a war footing the militia) wears worse";
        Makers.declare("steel_sword", "the smith", watch, "InventionArms.smith");
        for (String p : List.of("steel_helmet", "steel_chestplate", "steel_leggings", "steel_boots")) Makers.declare(p, "the smith", watch, "InventionArms.smith");
        Makers.declare("pike", "the smith", "once the town knows the Pike: two kept, one for every two of the watch", "InventionArms.smith");
        Makers.declare("halberd", "the smith", "once the town knows the Halberd: one for every two of the watch", "InventionArms.smith");
        Makers.declare("war_hammer", "the smith", "once the town knows the War Hammer: one for every three of the watch", "InventionArms.smith");
        Makers.declare("longbow", "the fletcher (the smith with no fletcher)", "once the town knows the Longbow: one for every two of its archers",
            "InventionArms.fletch");
        Makers.declare("arbalest", "the fletcher (the smith with no fletcher)", "once the town knows the Arbalest: two kept, one for every three archers",
            "InventionArms.fletch");
        Makers.declare("working_model", "the town's scholars, at the academy's bench",
            "once for each invention they work out, of its own makings (Inventions.pay)", "Inventions.workingModel");
        Makers.unmade("working_model", "no recipe: the scholars build one at the academy of an invention's own makings, as they work it out");
    }

    // ------------------------------------------------------------------ into the field

    /** Its army's fighter kitted (Armies.raise, as each is given its place): its new arms noted for the books. */
    public static void note(VillageFolkEntity g, UUID fighter) {
        KIT.put(fighter, bits(g));
    }

    /**
     * The line given the town's polearms for the field, out of the armoury and the stores (Armies.raise, after the shields):
     * a pike or a halberd to one in three of those in the line with no better blade (one in two when the enemy keeps
     * horses), a war hammer to one in four when the enemy goes armoured. In its hand; its own blade in its pack.
     */
    public static void kitForField(ServerLevel level, Villages.Village v, Armies.Army a, Map<UUID, VillageFolkEntity> byId) {
        UUID enemy = a.enemy();
        boolean horses = enemy != null && (InventionWorks.stands(enemy, "stable") || !Stables.animals(level, enemy).isEmpty());
        boolean armoured = enemy != null && Villages.ageOf(enemy).ordinal() >= Villages.Age.IRON.ordinal();
        List<VillageFolkEntity> line = new ArrayList<>();
        for (Armies.Fighter f : a.fighters()) {
            if (f.role() != BattleModel.Role.LINE) continue;
            VillageFolkEntity g = byId.get(f.id());
            if (g == null || weaponScore(g.getMainHandItem()) > 0) continue;
            if (Militia.bestBlade(g) >= 3.0F) continue;                       // a diamond blade is better than any of them
            line.add(g);
        }
        int poles = horses ? (line.size() + 1) / 2 : (line.size() + 2) / 3;
        int hammers = armoured ? line.size() / 4 : 0;
        int k = 0;
        for (VillageFolkEntity g : line) {
            ItemStack w = ItemStack.EMPTY;
            if (k < poles) {
                w = lend(level, v, s -> s.is(InventionItems.HALBERD.get()));
                if (w.isEmpty()) w = lend(level, v, s -> s.is(InventionItems.PIKE.get()));
            } else if (k < poles + hammers) {
                w = lend(level, v, s -> s.is(InventionItems.WAR_HAMMER.get()));
            }
            if (w.isEmpty()) {
                if (k >= poles + hammers) break;
                k++;
                continue;
            }
            k++;
            ItemStack own = g.getMainHandItem().copy();
            g.setItemSlot(EquipmentSlot.MAINHAND, w);
            if (!own.isEmpty()) {
                ItemStack left = g.insertGiven(own);
                if (!left.isEmpty()) TownWork.give(level, v, left);
            }
        }
    }

    /** One of the town's polearms lent for the field, marked to come back; empty if neither the armoury nor the stores hold one. */
    static ItemStack lend(ServerLevel level, Villages.Village v, Predicate<ItemStack> what) {
        ItemStack w = WarWorks.takeArms(level, v, what);
        if (w.isEmpty()) w = Crafts.takeOne(level, v, what);
        if (w.isEmpty()) return w;
        CustomData.update(DataComponents.CUSTOM_DATA, w, t -> t.putBoolean(LENT, true));
        return w;
    }

    static boolean lent(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.copyTag().getBoolean(LENT);
    }

    /** Home again (Armies.discharge): the town's lent polearms back into the armoury, its own blades back in hand. */
    public static void returnKit(ServerLevel level, Armies.Army a) {
        Villages.Village v = Villages.get(a.town());
        for (Armies.Fighter x : a.fighters()) {
            KIT.remove(x.id());
            if (v == null || !(level.getEntity(x.id()) instanceof VillageFolkEntity g)) continue;
            ItemStack hand = g.getMainHandItem();
            if (!lent(hand)) continue;
            ItemStack back = hand.copy();
            CustomData.update(DataComponents.CUSTOM_DATA, back, t -> t.remove(LENT));
            g.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            WarWorks.returnArms(level, v, back);
            g.equipBestWeapon();
        }
    }

    /** The side's new arms counted for the model (Battles.model): those on their feet, what each was kitted with. */
    public static void tally(Armies.Army a, BattleModel.Side s, @Nullable ServerLevel level) {
        if (level == null) {                                         // the battle's own level, by its town's
            net.minecraft.server.MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            Villages.Village home = Villages.get(a.town());
            if (srv != null && home != null) level = srv.getLevel(home.dim());
        }
        ArmsEdge.Kit k = new ArmsEdge.Kit();
        int armour = 0, n = 0;
        for (Armies.Fighter f : a.fighters()) {
            if (f.down() || f.role() == BattleModel.Role.MEDIC) continue;
            Integer bits = KIT.get(f.id());
            VillageFolkEntity g = level != null && level.getEntity(f.id()) instanceof VillageFolkEntity e ? e : null;
            if (g != null) bits = bits(g);
            if (bits == null) bits = 0;
            if (f.role() == BattleModel.Role.ARCHER) {
                if ((bits & B_ARBALEST) != 0 && f.crossbow()) k.arbalests++;
                else if ((bits & B_LONGBOW) != 0 && !f.crossbow()) k.longbows++;
            } else {
                if ((bits & B_HALBERD) != 0) k.halberds++;
                else if ((bits & B_PIKE) != 0) k.pikes++;
                else if ((bits & B_HAMMER) != 0) k.hammers++;
            }
            if (g != null) {
                armour += Armies.armour(g);
                n++;
            }
        }
        k.armour = n == 0 ? 0 : armour / n;
        s.kit = k;
    }

    /** Tests: what a folk is worth in the line as it is armed now (Armies.power, with its new arms). */
    public static int powerForTests(VillageFolkEntity f) {
        return Armies.power(f, false);
    }

    /** Tests: a fighter's new arms set as if kitted with them. */
    public static void kitForTests(UUID fighter, int bits) {
        KIT.put(fighter, bits);
    }

    // ------------------------------------------------------------------ the siege

    /** The blows a ram wants at this town's gate, by what the besiegers know (Battles.batter). */
    public static int gateBlows(UUID besiegers, int usual) {
        int b = ArmsEdge.gateBlows(Inventions.known(besiegers, Invention.SIEGE_ENGINES), Inventions.known(besiegers, Invention.BLACK_POWDER));
        return Math.min(usual, b);
    }

    /** The heart the defenders take from their gate on paper, by what the besiegers know (Battles.onPaper). */
    public static int gateHeart(UUID besiegers, int usual) {
        return ArmsEdge.gateHeart(usual, Inventions.known(besiegers, Invention.SIEGE_ENGINES), Inventions.known(besiegers, Invention.BLACK_POWDER));
    }

    /** An arbalest's span, for the town's crossbowmen (Armies.reloadTicks): three quarters as long again. */
    public static int reload(ItemStack weapon, int ticks) {
        return weapon.getItem() instanceof ArbalestItem ? ticks * ArbalestItem.SLOW / 100 : ticks;
    }

    // ------------------------------------------------------------------ the blow

    /**
     * A blow landing, whoever struck it: a pike or a halberd at a rider (or the horse under one) the harder, a war hammer
     * or a halberd through its mark's armour.
     */
    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent e) {
        Entity src = e.getSource().getDirectEntity();
        if (!(src instanceof LivingEntity attacker) || src != e.getSource().getEntity()) return;
        PolearmItem.Kind kind = pole(attacker.getMainHandItem());
        if (kind == null) return;
        LivingEntity target = e.getEntity();
        float amount = e.getAmount();
        boolean rider = target.isPassenger() || target instanceof AbstractHorse h && h.isVehicle();
        if (rider && kind == PolearmItem.Kind.PIKE) amount *= PIKE_VS_RIDER;
        if (rider && kind == PolearmItem.Kind.HALBERD) amount *= HALBERD_VS_RIDER;
        float plate = target.getArmorValue();
        if (kind == PolearmItem.Kind.HAMMER) amount += Math.min(THROUGH_MOST, plate * HAMMER_THROUGH);
        if (kind == PolearmItem.Kind.HALBERD) amount += Math.min(THROUGH_MOST, plate * HALBERD_THROUGH);
        e.setAmount(amount);
    }

    /** What a blow from this weapon at this mark comes to, for the tests (the event's own reckoning). */
    public static float blowForTests(ItemStack weapon, float amount, boolean rider, float armour) {
        PolearmItem.Kind kind = pole(weapon);
        if (kind == null) return amount;
        if (rider && kind == PolearmItem.Kind.PIKE) amount *= PIKE_VS_RIDER;
        if (rider && kind == PolearmItem.Kind.HALBERD) amount *= HALBERD_VS_RIDER;
        if (kind == PolearmItem.Kind.HAMMER) amount += Math.min(THROUGH_MOST, armour * HAMMER_THROUGH);
        if (kind == PolearmItem.Kind.HALBERD) amount += Math.min(THROUGH_MOST, armour * HALBERD_THROUGH);
        return amount;
    }

    /** An arrow loosed off a longbow or an arbalest (a player's or a folk's): faster, and harder. */
    @SubscribeEvent
    public static void onArrow(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || !(e.getEntity() instanceof AbstractArrow arrow)) return;
        ItemStack weapon = arrow.getWeaponItem();
        if (weapon == null || weapon.isEmpty()) return;
        if (weapon.getItem() instanceof LongbowItem) boost(arrow, LONGBOW_SPEED, LONGBOW_HIT);
        else if (weapon.getItem() instanceof ArbalestItem) boost(arrow, ARBALEST_SPEED, ARBALEST_HIT);
    }

    static void boost(AbstractArrow arrow, double speed, double hit) {
        arrow.setDeltaMovement(arrow.getDeltaMovement().scale(speed));
        arrow.setBaseDamage(arrow.getBaseDamage() * hit);
    }
}
