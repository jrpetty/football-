package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war] A town's army in the field, and every fighter's own step in it.
 *
 * <p><b>Who marches.</b> When a town takes the field (Campaigns) its army is raised out of those who would fight
 * for it (WarFooting.militia: the watch, and at war the militia called up and armed out of the armoury), less a
 * home guard kept back (one in five, the weakest; none when the enemy is at its own gate), less anybody away on
 * an ally's walls, held captive or on the road; the allies' guards on its walls march with it under their own
 * colours. Each has its place by its kit: a bow and arrows to the archers, the rest to the shield line, the
 * weakest of them to the reserve. Its <b>Captain</b> is the constable, else the watch's captain, else the most
 * seasoned of the guards; a <b>sergeant</b> for every six, the best of the rest; a <b>standard-bearer</b>
 * carrying the town's banner out of the stores (or woven for it there and then); and a <b>medic</b>, the cook
 * (else the brewer), with bandages out of the stores. The line is given shields out of the armoury where it has
 * them, the archers arrows out of the stores; leads for captives, wool and fences for the tents, a campfire,
 * and logs for a ram when it means to take a town, go with it as its supply. Nothing out of nothing: a town
 * without the cloth sleeps by the fire; without logs it cannot build a ram. At home again, all of it goes back.
 * Each wears a surcoat of its town's arms over its armour, the Captain a cloak, the sergeants a sash, the medic
 * a white one (client/ArmyLayer).
 *
 * <p><b>Each fighter's step</b> ({@link #hold}, from the folk's own tick): it goes to the place its army gives it
 * (the conductor works out where: Campaigns on the march and in camp, Battles on the field) and faces the way it
 * is told; in the clash it fights the enemy it is given, through its own fighting (its blade, its timing). A
 * fighter beaten down to its last hearts <b>yields</b>: it kneels, its weapon put away, and nobody strikes it
 * again; once in a long while (the config's battleDeaths, five in a hundred at most by default) one does not get
 * up. Nobody outside the battle is touched: not a passer-by, not a player who has not joined a side, not a
 * medic, not one who has yielded ({@link #onIncoming}).
 *
 * <p>Its cost: nothing at all while no army is in the field (one look at an empty map); a fighter's step is a map
 * look-up and, a few times a second, a path to its place.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Armies {

    private Armies() {}

    /** A rank in the army. */
    public enum Rank { NONE, SERGEANT, BEARER, CAPTAIN }

    /** What a fighter is about now: set by the campaign or the battle, and followed by its own step. */
    public enum Order {
        /** Going to (or standing in) its place: the muster, the column, the camp, the line. */
        PLACE,
        /** In the clash: at the enemy it was given. */
        ENGAGE,
        /** Beaten down: on its knees, out of the fight. */
        YIELDED,
        /** Taken: led by its captor. */
        CAPTIVE,
        /** Running from the field. */
        ROUT
    }

    /** At or under this much health a fighter in battle yields rather than take another blow. */
    public static final float YIELD_HEALTH = 6.0F;
    /** Fighters in the field at once, all armies together: the map of them never grows past this. */
    static final int MOST = 400;

    /** One fighter: who, its place in the army, where it is to stand, and what has become of it. */
    public static final class Fighter {
        final UUID id;
        final String name;
        /** The town it fights for (an ally's guard keeps its own colours and its own sergeant). */
        final UUID town;
        Army army;
        BattleModel.Role role;
        Rank rank = Rank.NONE;
        int power;
        boolean veteran;
        Order order = Order.PLACE;
        /** Where it is to stand, and which way it is to face (degrees, as the game's yaw). */
        double tx, ty, tz;
        float yaw;
        boolean placed;
        /** How briskly it goes there: 0.6 in step, 1.0 to catch up, 1.25 at the charge. */
        double speed = 0.7;
        /** In the clash, its man; captive, its captor. */
        @Nullable UUID foe;
        boolean dead, shieldUp;
        /** In camp: sits by the fire once it is in its place. */
        boolean sit;
        /** Wounded, and bound by its medic: out of the fight, but on its feet at the back. */
        boolean tended;
        /** The enemy it beat down, this battle (the brave are honoured). */
        int downed;
        /** Where and when it was last seen in the world (the campaign calls it back from there). */
        @Nullable BlockPos seenAt;
        long seenTick;
        /** Its stagger in the ticks, so not every fighter paths on the same one. */
        final int stagger;
        long stuckSince;
        double stuckBest = Double.MAX_VALUE;

        Fighter(UUID id, String name, UUID town, BattleModel.Role role) {
            this.id = id;
            this.name = name;
            this.town = town;
            this.role = role;
            this.stagger = Math.floorMod(id.hashCode(), 10);
        }

        public UUID id() { return id; }
        public String name() { return name; }
        public BattleModel.Role role() { return role; }
        public Rank rank() { return rank; }
        public Order order() { return order; }
        public boolean down() { return order == Order.YIELDED || order == Order.CAPTIVE || dead; }

        /** Stand here, facing this way. */
        void place(ServerLevel level, double x, double z, float facing) {
            tx = x;
            tz = z;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            ty = level.hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : ty;
            yaw = facing;
            placed = true;
        }

        /** "Brannoc the line sergeant", "Ada (archer)": as the books name it. */
        String what() {
            return switch (rank) {
                case CAPTAIN -> "Captain " + name;
                case BEARER -> name + " the standard-bearer";
                case SERGEANT -> "Sergeant " + name;
                default -> name;
            };
        }

        String encode() {
            return id + ":" + role.ordinal() + ":" + rank.ordinal() + ":" + power + ":" + (veteran ? 1 : 0) + ":" + town + ":" + WarBooks.clean(name).replace(":", " ")
                + ":" + order.ordinal() + ":" + (dead ? 1 : 0);
        }

        @Nullable
        static Fighter decode(String s) {
            String[] p = s.split(":", 9);
            if (p.length < 9) return null;
            try {
                Fighter f = new Fighter(UUID.fromString(p[0]), p[6], UUID.fromString(p[5]), BattleModel.Role.values()[Integer.parseInt(p[1])]);
                f.rank = Rank.values()[Integer.parseInt(p[2])];
                f.power = Integer.parseInt(p[3]);
                f.veteran = "1".equals(p[4]);
                f.order = Order.values()[Integer.parseInt(p[7])];
                f.dead = "1".equals(p[8]);
                return f;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** A town's army in the field: its fighters, its officers, its tactics and its spirit, and what it carries. */
    public static final class Army {
        final UUID town, enemy;
        final List<Fighter> fighters = new ArrayList<>();
        /** The strength of those it raised past what is drawn in detail: they fight on paper alongside. */
        int behind, behindPower;
        @Nullable UUID captain, bearer, medic;
        int boldness;
        boolean cunning;
        BattleModel.Tactic tactic = BattleModel.Tactic.STRAIGHT;
        int morale = 60;
        /** Its supply: leads, cloth and poles for the tents, a campfire, logs for a ram, bandages. */
        final Map<Item, Integer> supply = new LinkedHashMap<>();
        /** Its surcoat (a tabard of its town's arms), drawn on its fighters (shared; never handed out). */
        ItemStack surcoat = ItemStack.EMPTY;

        Army(UUID town, UUID enemy) {
            this.town = town;
            this.enemy = enemy;
        }

        public UUID town() { return town; }
        public UUID enemy() { return enemy; }
        public List<Fighter> fighters() { return fighters; }
        public BattleModel.Tactic tactic() { return tactic; }
        public int morale() { return morale; }

        @Nullable
        public Fighter officer(Rank r) {
            for (Fighter f : fighters) if (f.rank == r) return f;
            return null;
        }

        /** Those still on their feet. */
        public int standing() {
            int n = 0;
            for (Fighter f : fighters) if (!f.down() && f.role != BattleModel.Role.MEDIC) n++;
            return n;
        }

        /** Its strength all told: its fighters on their feet and those fighting on paper. */
        public int power() {
            int n = behindPower;
            for (Fighter f : fighters) if (!f.down() && f.role != BattleModel.Role.MEDIC) n += f.power;
            return n;
        }

        public int size() {
            int n = behind;
            for (Fighter f : fighters) if (f.role != BattleModel.Role.MEDIC) n++;
            return n;
        }

        int count(BattleModel.Role r) {
            int n = 0;
            for (Fighter f : fighters) if (f.role == r && !f.down()) n++;
            return n;
        }

        int carried(Item it) {
            return supply.getOrDefault(it, 0);
        }

        boolean use(Item it, int n) {
            int have = carried(it);
            if (have < n) return false;
            if (have == n) supply.remove(it);
            else supply.put(it, have - n);
            return true;
        }

        String supplyCode() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : supply.entrySet()) out.add(BuiltInRegistries.ITEM.getKey(e.getKey()) + "*" + e.getValue());
            return String.join(",", out);
        }

        void supplyFrom(String s) {
            supply.clear();
            if (s == null || s.isEmpty()) return;
            for (String one : s.split(",")) {
                String[] p = one.split("\\*");
                if (p.length < 2) continue;
                ResourceLocation key = ResourceLocation.tryParse(p[0]);
                if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) continue;
                supply.merge(BuiltInRegistries.ITEM.get(key), (int) WarBooks.num(p[1], 0), Integer::sum);
            }
        }
    }

    /** Every fighter in the field, by its id: the one look-up each folk's tick makes. */
    private static final Map<UUID, Fighter> FIGHTERS = new ConcurrentHashMap<>();
    /** Players who have joined a side (volunteers, or hired), and the town they fight for. */
    private static final Map<UUID, UUID> VOLUNTEERS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        FIGHTERS.clear();
        VOLUNTEERS.clear();
    }

    /** Put this army's fighters in the field (their steps follow it from their next tick). */
    static void enlist(Army a) {
        for (Fighter f : a.fighters) {
            f.army = a;
            if (FIGHTERS.size() < MOST) FIGHTERS.put(f.id, f);
        }
    }

    /** Take this army's fighters out of the field: their own lives again, their clothes back as they were. */
    static void discharge(ServerLevel level, Army a) {
        for (Fighter f : a.fighters) {
            if (FIGHTERS.get(f.id) == f) FIGHTERS.remove(f.id);
            if (level.getEntity(f.id) instanceof VillageFolkEntity e) release(e);
        }
        VOLUNTEERS.values().removeIf(t -> t.equals(a.town));
    }

    /** One of an army's fighters by id, or null. */
    @Nullable
    public static Fighter fighter(UUID id) {
        return id == null ? null : FIGHTERS.get(id);
    }

    /** Is this folk in the ranks of an army in the field (its own fear and its night shelter wait)? */
    public static boolean inLine(net.minecraft.world.entity.Entity e) {
        return !FIGHTERS.isEmpty() && e instanceof VillageFolkEntity && FIGHTERS.containsKey(e.getUUID());
    }

    /** Is any army in the field? */
    public static boolean any() {
        return !FIGHTERS.isEmpty();
    }

    // ------------------------------------------------------------------ raising an army

    /** Its blade's worth to the model: none, wood or stone, iron, diamond or better. */
    static int blade(VillageFolkEntity f) {
        float best = Militia.bestBlade(f);
        return best <= 0 ? BattleModel.NO_BLADE : best < 6 ? BattleModel.POOR_BLADE : best < 7 ? BattleModel.IRON_BLADE : BattleModel.FINE_BLADE;
    }

    static int armour(VillageFolkEntity f) {
        int n = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            if (f.getItemBySlot(s).getItem() instanceof ArmorItem a) n += a.getDefense();
        }
        return n;
    }

    static boolean shield(VillageFolkEntity f) {
        return f.getOffhandItem().getItem() instanceof ShieldItem || f.countMatching(s -> s.getItem() instanceof ShieldItem) > 0;
    }

    static boolean bow(VillageFolkEntity f) {
        return WarAndPeace.archer(f) && f.countMatching(s -> s.is(Items.ARROW)) > 0;
    }

    /** What it is worth in the line, as it is armed now. */
    static int power(VillageFolkEntity f, boolean veteran) {
        return BattleModel.power(blade(f), armour(f), shield(f), bow(f), veteran);
    }

    /** Has it fought a battle before (its books: Ledger "war.vet/")? */
    static boolean veteran(UUID town, UUID folk) {
        return battles(town, folk) >= 1;
    }

    /** The battles it has fought (and come home from). */
    static int battles(UUID town, UUID folk) {
        return (int) WarBooks.num(Ledger.note(town, "war.vet/" + folk), 0);
    }

    /**
     * Raise the town's army against that enemy: who marches and who stays (a home guard of one in five, none
     * when {@code atTheGate}), at most {@code cap} drawn in detail (the rest fight on paper), their places, their
     * officers, their kit topped up out of the armoury and the stores, its supply. Null if it has nobody to send.
     */
    @Nullable
    static Army raise(ServerLevel level, Villages.Village v, UUID enemy, boolean atTheGate, boolean siege, int cap) {
        UUID id = v.id();
        UUID elder = Villages.elder(id);
        List<VillageFolkEntity> pool = new ArrayList<>();
        for (VillageFolkEntity g : WarFooting.militia(id)) {
            if (g.getUUID().equals(elder) || WarAndPeace.awayOnGarrison(g) || Spies.held(g) || g.trip() != null || g.isShowcase()) continue;
            if (FIGHTERS.containsKey(g.getUUID())) continue;
            pool.add(g);
        }
        for (VillageFolkEntity g : WarAndPeace.garrisonAt(level, id)) if (!FIGHTERS.containsKey(g.getUUID())) pool.add(g);
        if (pool.isEmpty()) return null;
        Map<UUID, Boolean> vet = new LinkedHashMap<>();
        for (VillageFolkEntity g : pool) vet.put(g.getUUID(), veteran(g.ownerId() == null ? id : g.ownerId(), g.getUUID()));
        pool.sort((x, y) -> Integer.compare(power(y, vet.get(y.getUUID())), power(x, vet.get(x.getUUID()))));
        // The home guard: the weakest of the town's own, one in five, kept back to man the walls.
        int keep = atTheGate ? 0 : pool.size() / 5;
        for (int i = pool.size() - 1; i >= 0 && keep > 0; i--) {
            if (id.equals(pool.get(i).ownerId())) {
                pool.remove(i);
                keep--;
            }
        }
        if (pool.isEmpty()) return null;
        Army a = new Army(id, enemy);
        a.boldness = boldness(Envoys.temper(id));
        a.cunning = Envoys.temper(id) == Envoys.Temper.SHREWD || Envoys.temper(id) == Envoys.Temper.CURIOUS;
        a.surcoat = surcoat(level, id);
        VillageFolkEntity captain = captainOf(id, pool);
        for (int i = 0; i < pool.size(); i++) {
            VillageFolkEntity g = pool.get(i);
            if (a.fighters.size() >= cap && g != captain) {
                a.behind++;
                a.behindPower += power(g, vet.get(g.getUUID()));
                continue;
            }
            UUID town = g.ownerId() == null ? id : g.ownerId();
            Fighter f = new Fighter(g.getUUID(), g.displayNameCap(), town, BattleModel.Role.LINE);
            f.veteran = vet.get(g.getUUID());
            a.fighters.add(f);
        }
        Map<UUID, VillageFolkEntity> byId = new LinkedHashMap<>();
        for (VillageFolkEntity g : pool) byId.put(g.getUUID(), g);
        // Kit for the field, out of the armoury and the stores: a shield for the line, arrows for the bows.
        for (Fighter f : a.fighters) {
            VillageFolkEntity g = byId.get(f.id);
            if (WarAndPeace.archer(g)) topUpArrows(level, v, g, a);
            f.role = bow(g) ? BattleModel.Role.ARCHER : BattleModel.Role.LINE;
            if (f.role == BattleModel.Role.LINE && !shield(g)) shieldUp(level, v, g);
            f.power = power(g, f.veteran);
        }
        if (captain != null) {
            Fighter c = find(a, captain.getUUID());
            if (c != null) {
                c.rank = Rank.CAPTAIN;
                a.captain = c.id;
            }
        }
        // The reserve: the weakest quarter of the line (not the Captain).
        List<Fighter> line = new ArrayList<>();
        for (Fighter f : a.fighters) if (f.role == BattleModel.Role.LINE && f.rank == Rank.NONE) line.add(f);
        line.sort((x, y) -> Integer.compare(x.power, y.power));
        for (int i = 0; i < line.size() / 4; i++) line.get(i).role = BattleModel.Role.RESERVE;
        // The standard-bearer: one of the line's, given the town's banner to carry.
        for (Fighter f : a.fighters) {
            if (f.rank != Rank.NONE || f.role == BattleModel.Role.ARCHER || !f.town.equals(id)) continue;
            VillageFolkEntity g = byId.get(f.id);
            if (g != null && carryBanner(level, v, g)) {
                f.rank = Rank.BEARER;
                a.bearer = f.id;
            }
            break;
        }
        // A sergeant for every six: the veterans of the most battles first, then the best of the rest; the allies under their own.
        int want = Math.max(1, (a.fighters.size() + 5) / 6);
        List<Fighter> rest = new ArrayList<>();
        for (Fighter f : a.fighters) if (f.rank == Rank.NONE) rest.add(f);
        rest.sort((x, y) -> {
            int bx = battles(x.town, x.id), by = battles(y.town, y.id);
            return bx != by ? Integer.compare(by, bx) : Integer.compare(y.power, x.power);
        });
        for (Fighter f : rest) {
            if (want-- <= 0) break;
            f.rank = Rank.SERGEANT;
        }
        for (Fighter f : a.fighters) {
            if (f.town.equals(id) || f.rank != Rank.NONE || a.officer(Rank.SERGEANT) == null) continue;
            boolean has = false;
            for (Fighter o : a.fighters) if (o.town.equals(f.town) && o.rank == Rank.SERGEANT) has = true;
            if (!has) f.rank = Rank.SERGEANT;
        }
        mountUp(level, v, a, byId);
        VillageFolkEntity medic = medicOf(level, v, pool);
        if (medic != null) {
            Fighter m = new Fighter(medic.getUUID(), medic.displayNameCap(), id, BattleModel.Role.MEDIC);
            a.fighters.add(m);
            a.medic = m.id;
            List<ItemStack> got = Caravans.takeOut(level, v, Kitchen.BANDAGE, 4);
            for (ItemStack s : got) {
                ItemStack left = medic.insertGiven(s);
                if (!left.isEmpty()) TownWork.give(level, v, left);
            }
        }
        supply(level, v, a, siege);
        a.morale = BattleModel.startMorale(a.boldness, atTheGate, veterans(a), !WarAndPeace.garrisonAt(level, id).isEmpty());
        return a;
    }

    /** The most riders an army puts on the town's horses. */
    static final int RIDERS = 4;

    /**
     * The town's saddled horses, if it keeps a stable: the Captain up on one, and up to four of the line's best as
     * riders for the flank. They mount in the stable (Riding.mount: the horse goes where its rider would walk) and
     * ride back to the square; home again they get down, and the rancher brings the horses in (Stables).
     */
    static int mountUp(ServerLevel level, Villages.Village v, Army a, Map<UUID, VillageFolkEntity> byId) {
        Stables.Stable st = Stables.stableNow(v.id(), level.getGameTime());
        if (st == null || !level.isLoaded(st.anchor())) return 0;
        List<Fighter> riders = new ArrayList<>();
        Fighter cap = a.officer(Rank.CAPTAIN);
        if (cap != null) riders.add(cap);
        List<Fighter> line = new ArrayList<>();
        for (Fighter f : a.fighters) if (f.role == BattleModel.Role.LINE && f.rank == Rank.NONE && f.town.equals(v.id())) line.add(f);
        line.sort((x, y) -> Integer.compare(y.power, x.power));
        riders.addAll(line.subList(0, Math.min(RIDERS, line.size())));
        int n = 0;
        for (Fighter f : riders) {
            VillageFolkEntity g = byId.get(f.id);
            if (g == null || g.isPassenger()) continue;
            net.minecraft.world.entity.animal.horse.AbstractHorse h = Riding.freeHorse(level, v.id(), st);
            if (h == null || !Riding.mount(g, h)) break;
            if (f.rank != Rank.CAPTAIN) f.role = BattleModel.Role.RIDER;
            n++;
        }
        return n;
    }

    @Nullable
    static Fighter find(Army a, UUID id) {
        for (Fighter f : a.fighters) if (f.id.equals(id)) return f;
        return null;
    }

    static int veterans(Army a) {
        int n = 0;
        for (Fighter f : a.fighters) if (f.veteran) n++;
        return n;
    }

    /** How bold a Captain's town is, by its elder's temper: -2 timid to 2 bold. */
    static int boldness(Envoys.Temper t) {
        return switch (t) {
            case PRICKLY -> 2;
            case SHREWD, STEADY -> 1;
            case CURIOUS, EASY -> 0;
            case WARY -> -2;
            default -> -1;
        };
    }

    /** The Captain: the constable, else the watch's captain, else the strongest of the guards (the pool is strongest first). */
    @Nullable
    static VillageFolkEntity captainOf(UUID town, List<VillageFolkEntity> pool) {
        VillageFolkEntity constable = Inquiry.constable(town);
        UUID watchCaptain = Police.captainId(town);
        VillageFolkEntity guard = null;
        for (VillageFolkEntity g : pool) {
            if (g == constable) return g;
            if (g.getUUID().equals(watchCaptain)) guard = g;
        }
        if (guard != null) return guard;
        for (VillageFolkEntity g : pool) if (town.equals(g.ownerId()) && g.stationTask() == AssistantEntity.StationTask.GUARD) return g;
        return pool.isEmpty() ? null : pool.get(0);
    }

    /** The medic: the cook, else the brewer, else whoever at home holds bandages; never one of the fighters. */
    @Nullable
    static VillageFolkEntity medicOf(ServerLevel level, Villages.Village v, List<VillageFolkEntity> fighters) {
        VillageFolkEntity best = null;
        int bestScore = 0;
        for (AssistantEntity e : Villages.folkOf(v.id())) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive() || fighters.contains(f) || f.trip() != null || f.isShowcase()) continue;
            if (f.getUUID().equals(Villages.elder(v.id())) || FIGHTERS.containsKey(f.getUUID())) continue;
            int score = f.stationTask() == AssistantEntity.StationTask.COOK ? 3 : f.stationTask() == AssistantEntity.StationTask.BREW ? 2
                : f.countMatching(Kitchen.BANDAGE) > 0 ? 1 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /** Arrows for a bow, out of the stores: up to sixteen in its pack. */
    static void topUpArrows(ServerLevel level, Villages.Village v, VillageFolkEntity g, Army a) {
        int have = g.countMatching(s -> s.is(Items.ARROW));
        if (have >= 16) return;
        for (ItemStack s : Caravans.takeOut(level, v, s -> s.is(Items.ARROW), 16 - have)) {
            ItemStack left = g.insertGiven(s);
            if (!left.isEmpty()) TownWork.give(level, v, left);
        }
    }

    /** A shield out of the armoury (else the stores) for one of the line, booked to it like the militia's arms. */
    static void shieldUp(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        ItemStack s = WarWorks.takeArms(level, v, x -> x.getItem() instanceof ShieldItem);
        if (s.isEmpty()) return;
        Arms.shield(level, v, g);                                   // the town's arms on it, where the loom can run to them
        if (g.getOffhandItem().isEmpty()) g.setItemSlot(EquipmentSlot.OFFHAND, WatchKit.mark(s));
        else {
            ItemStack left = g.insertGiven(WatchKit.mark(s));
            if (!left.isEmpty()) WarWorks.returnArms(level, v, left);
        }
    }

    /** The town's banner into the bearer's hand: one of its own out of the stores, else woven now. Whether it has one. */
    static boolean carryBanner(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        if (g.getMainHandItem().getItem() instanceof BannerItem) return true;
        Heraldry.Design d = Heraldry.design(v.id());
        ItemStack banner = Arms.takeOne(level, v, s -> s.getItem() instanceof BannerItem && (d == null || Arms.bears(s, d)), g.displayNameCap());
        if (banner == null && d != null) banner = Arms.weave(level, v, d, "the standard for the field");
        if (banner == null) banner = Arms.takeOne(level, v, s -> s.getItem() instanceof BannerItem, g.displayNameCap());   // a plain one, rather than none
        if (banner == null) return false;
        stow(g, EquipmentSlot.MAINHAND);
        g.setItemSlot(EquipmentSlot.MAINHAND, Arms.lend(banner));
        return true;
    }

    /** What its hand holds, into its pack (or, with no room, kept in the hand). */
    static void stow(VillageFolkEntity g, EquipmentSlot slot) {
        ItemStack held = g.getItemBySlot(slot);
        if (held.isEmpty()) return;
        ItemStack left = g.insertGiven(held.copy());
        if (left.isEmpty()) g.setItemSlot(slot, ItemStack.EMPTY);
    }

    /** The town's surcoat: a tabard of its arms, or of its colour with none. */
    static ItemStack surcoat(ServerLevel level, UUID town) {
        ItemStack coat = new ItemStack(McAssistantMod.TABARD.get());
        Heraldry.Design d = Heraldry.design(town);
        if (d != null) Arms.emblazon(coat, Heraldry.item(level, town, d));
        else coat.set(net.minecraft.core.component.DataComponents.BASE_COLOR, COLOURS[Math.floorMod(town.hashCode(), 64) % COLOURS.length]);
        return coat;
    }

    /** The towns' colours with no arms of their own, as their watch wears them (FolkRenderer's banners). */
    private static final DyeColor[] COLOURS = { DyeColor.BLUE, DyeColor.RED, DyeColor.GREEN, DyeColor.PURPLE, DyeColor.BLACK, DyeColor.CYAN, DyeColor.ORANGE };

    /**
     * The army's supply out of the stores, what there is of it: leads for captives, cloth and poles for a tent to
     * every four, a campfire (or the logs, sticks and coal for one), and with a town to take, three logs for a ram.
     */
    static void supply(ServerLevel level, Villages.Village v, Army a, boolean siege) {
        int tents = Math.max(1, (a.fighters.size() + 3) / 4);
        carry(level, v, a, Items.LEAD, s -> s.is(Items.LEAD), 4);
        carry(level, v, a, Items.WHITE_WOOL, s -> s.is(net.minecraft.tags.ItemTags.WOOL), tents * Battlefields.TENT_WOOL);
        carry(level, v, a, Items.OAK_FENCE, s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES), tents * Battlefields.TENT_POLES);
        if (carry(level, v, a, Items.CAMPFIRE, s -> s.is(Items.CAMPFIRE), 1) == 0) {
            // The makings of one: three logs, three sticks and a coal, a campfire as the crafting table makes it.
            if (TownWork.take(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS), 3)) {
                if (TownWork.take(level, v, s -> s.is(Items.STICK), 3) && TownWork.take(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), 1)) {
                    a.supply.merge(Items.CAMPFIRE, 1, Integer::sum);
                } else TownWork.give(level, v, new ItemStack(Items.OAK_LOG, 3));
            }
        }
        if (siege) carry(level, v, a, Items.OAK_LOG, s -> s.is(net.minecraft.tags.ItemTags.LOGS), Battlefields.RAM_LOGS);
    }

    /** Up to {@code n} of a thing out of the stores into the supply, counted as {@code as}. How many. */
    static int carry(ServerLevel level, Villages.Village v, Army a, Item as, java.util.function.Predicate<ItemStack> what, int n) {
        int got = 0;
        for (ItemStack s : Caravans.takeOut(level, v, what, n)) got += s.getCount();
        if (got > 0) a.supply.merge(as, got, Integer::sum);
        return got;
    }

    /** The supply home: back into the stores, every bit of it. */
    static void unload(ServerLevel level, Army a) {
        Villages.Village v = Villages.get(a.town);
        if (v == null) return;
        for (Map.Entry<Item, Integer> e : a.supply.entrySet()) {
            int n = e.getValue();
            while (n > 0) {
                int k = Math.min(n, e.getKey().getDefaultMaxStackSize());
                TownWork.give(level, v, new ItemStack(e.getKey(), k));
                n -= k;
            }
        }
        a.supply.clear();
    }

    // ------------------------------------------------------------------ each fighter's step

    /**
     * The folk's own tick, in the field: its place, its foe, its knees. True while it is in an army (its own day
     * waits); false for everybody else, at the cost of one look in a map that is empty in peacetime.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (FIGHTERS.isEmpty()) return false;
        Fighter x = FIGHTERS.get(f.getUUID());
        if (x == null) return false;
        if (x.dead) {                                             // fallen on paper: so it is, now its ground is awake
            Battles.dies(f, x);
            return true;
        }
        try {
            step(f, x, level);
            return true;
        } catch (RuntimeException e) {
            com.jrpetty.mcassistant.Guard.struck("a fighter's step", f.displayNameCap(), e);
            return false;
        }
    }

    static void step(VillageFolkEntity f, Fighter x, ServerLevel level) {
        if (f.tickCount % 20 == x.stagger) {
            x.seenAt = f.blockPosition();
            x.seenTick = level.getGameTime();
            if (f.peekJob() != null) f.clearQueue();
            f.showWar(flags(x), x.army == null ? ItemStack.EMPTY : x.army.surcoat);
            f.hobbyNow = doing(x);
        }
        switch (x.order) {
            case YIELDED -> {
                f.setTarget(null);
                f.getNavigation().stop();
                Pose want = x.tended ? Pose.STANDING : Pose.CROUCHING;
                if (f.getPose() != want) f.setPose(want);
                if (x.placed) walk(f, x, level);                  // bound by the medic: back behind the lines
            }
            case CAPTIVE -> {
                f.setTarget(null);
                if (f.getPose() != Pose.STANDING) f.setPose(Pose.STANDING);
                Entity captor = x.foe == null ? null : level.getEntity(x.foe);
                if (captor != null && f.distanceToSqr(captor) > 9.0 && (f.tickCount + x.stagger) % 10 == 0) {
                    f.getNavigation().moveTo(captor, 0.9);
                } else if (captor == null && x.placed) walk(f, x, level);
            }
            case ENGAGE -> {
                if (f.getPose() != Pose.STANDING) f.setPose(Pose.STANDING);
                lowerShield(f, x);
                Entity foe = x.foe == null ? null : level.getEntity(x.foe);
                if (foe instanceof LivingEntity l && l.isAlive() && !yielded(l)) {
                    if (f.getTarget() != l) f.setTarget(l);
                } else {
                    x.foe = null;
                    f.setTarget(null);
                    walk(f, x, level);
                }
            }
            case ROUT -> {
                f.setTarget(null);
                lowerShield(f, x);
                if (f.getPose() != Pose.STANDING) f.setPose(Pose.STANDING);
                walk(f, x, level);
            }
            default -> {
                // In its place: nothing drawn out of the line by a stray arrow (the monsters of a night in camp aside).
                LivingEntity t = f.getTarget();
                if (t != null && !(t instanceof net.minecraft.world.entity.monster.Enemy && f.distanceToSqr(t) < 64.0)) f.setTarget(null);
                if (x.shieldUp) raiseShield(f);
                else lowerShield(f, x);
                walk(f, x, level);
                boolean there = x.placed && (x.tx - f.getX()) * (x.tx - f.getX()) + (x.tz - f.getZ()) * (x.tz - f.getZ()) <= 0.6;
                Pose want = x.sit && there ? Pose.SITTING : Pose.STANDING;
                if (f.getPose() != want) f.setPose(want);
            }
        }
    }

    /** To its place, and once there, still and facing the way it is told. */
    static void walk(VillageFolkEntity f, Fighter x, ServerLevel level) {
        if (!x.placed) return;
        double dx = x.tx - f.getX(), dz = x.tz - f.getZ(), d2 = dx * dx + dz * dz;
        if (d2 <= 0.6) {
            if (!f.getNavigation().isDone()) f.getNavigation().stop();
            face(f, x.yaw);
            x.stuckSince = 0;
            x.stuckBest = Double.MAX_VALUE;
            return;
        }
        if (d2 > 48.0 * 48.0) {
            // A long way behind (its ground was asleep while the army went on): set down in its place if nobody is there to
            // see it go, else it goes the road a leg at a time; with its place's ground asleep too, it waits.
            BlockPos to = BlockPos.containing(x.tx, x.ty, x.tz);
            if (!level.isPositionEntityTicking(to)) {
                if (!f.getNavigation().isDone()) f.getNavigation().stop();
                return;
            }
            if (level.getNearestPlayer(f, 48.0) == null && level.getNearestPlayer(x.tx, x.ty, x.tz, 48.0, false) == null) {
                f.getNavigation().stop();
                Riding.carry(f, x.tx, x.ty, x.tz);                     // its horse and all, if it rides
                return;
            }
            double d = Math.sqrt(d2);
            double lx = f.getX() + dx / d * 24.0, lz = f.getZ() + dz / d * 24.0;
            if ((f.tickCount + x.stagger) % 20 == 0 || f.getNavigation().isDone()) f.getNavigation().moveTo(lx, f.getY(), lz, 1.0);
        } else if ((f.tickCount + x.stagger) % 10 == 0 || f.getNavigation().isDone()) {
            f.getNavigation().moveTo(x.tx, x.ty, x.tz, d2 > 36.0 ? Math.max(x.speed, 1.0) : x.speed);
        }
        unstick(f, x, level, d2);
    }

    /** No nearer for twenty seconds (a river, a fence): set down in its place, where nobody is looking. */
    static void unstick(VillageFolkEntity f, Fighter x, ServerLevel level, double d2) {
        long now = level.getGameTime();
        if (d2 < x.stuckBest - 1.0) {
            x.stuckBest = d2;
            x.stuckSince = now;
            return;
        }
        if (x.stuckSince == 0) x.stuckSince = now;
        if (now - x.stuckSince < 400) return;
        x.stuckSince = now;
        x.stuckBest = Double.MAX_VALUE;
        BlockPos to = BlockPos.containing(x.tx, x.ty, x.tz);
        if (!level.isPositionEntityTicking(to) || level.getNearestPlayer(f, 32.0) != null
            || level.getNearestPlayer(x.tx, x.ty, x.tz, 32.0, false) != null) return;
        f.getNavigation().stop();
        Riding.carry(f, x.tx, x.ty, x.tz);                     // its horse and all, if it rides
    }

    static void face(VillageFolkEntity f, float yaw) {
        f.setYRot(yaw);
        f.setYHeadRot(yaw);
        f.yBodyRot = yaw;
        if (f.getVehicle() instanceof LivingEntity horse) {
            horse.setYRot(yaw);
            horse.yBodyRot = yaw;
        }
    }

    static void raiseShield(VillageFolkEntity f) {
        if (f.getOffhandItem().getItem() instanceof ShieldItem && !f.isUsingItem()) f.startUsingItem(InteractionHand.OFF_HAND);
    }

    static void lowerShield(VillageFolkEntity f, Fighter x) {
        if (f.isUsingItem() && f.getUsedItemHand() == InteractionHand.OFF_HAND) f.stopUsingItem();
    }

    /** What it is doing, for its card. */
    static String doing(Fighter x) {
        String enemy = x.army == null ? "the enemy" : Villages.name(x.army.enemy);
        return switch (x.order) {
            case YIELDED -> "wounded, out of the fight";
            case CAPTIVE -> "taken captive";
            case ROUT -> "running from the field";
            case ENGAGE -> "fighting " + enemy + "'s line";
            default -> "in the ranks against " + enemy;
        };
    }

    /** Its part, for its clothes (VillageFolkEntity.clientWar): role + 1, rank, whether down. */
    static int flags(Fighter x) {
        return (x.role.ordinal() + 1) | x.rank.ordinal() << 3 | (x.down() ? 1 << 5 : 0);
    }

    /** Back to its own life: on its feet, its clothes its own, its shield lowered. */
    static void release(VillageFolkEntity e) {
        e.showWar(0, ItemStack.EMPTY);
        if (e.getPose() == Pose.CROUCHING) e.setPose(Pose.STANDING);
        if (e.isUsingItem()) e.stopUsingItem();
        if (e.isLeashed()) e.dropLeash(true, false);
        e.setTarget(null);
        e.hobbyNow = null;
    }

    // ------------------------------------------------------------------ in arms

    /** The best blade in its pack into its hand (its tool into the pack); a bow for an archer. */
    static void arm(VillageFolkEntity f, Fighter x) {
        if (x.rank == Rank.BEARER || x.role == BattleModel.Role.MEDIC) return;
        ItemStack held = f.getMainHandItem();
        boolean wantBow = x.role == BattleModel.Role.ARCHER;
        if (wantBow ? held.getItem() instanceof ProjectileWeaponItem : held.getItem() instanceof SwordItem) return;
        int best = -1;
        float bite = -1;
        List<ItemStack> pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (wantBow && s.getItem() instanceof ProjectileWeaponItem) { best = i; break; }
            if (!wantBow && (s.getItem() instanceof SwordItem || s.getItem() instanceof net.minecraft.world.item.AxeItem) && Militia.bite(s) > bite) {
                bite = Militia.bite(s);
                best = i;
            }
        }
        if (best < 0) return;
        ItemStack weapon = pack.get(best).copy();
        pack.get(best).setCount(0);
        stow(f, EquipmentSlot.MAINHAND);
        f.setItemSlot(EquipmentSlot.MAINHAND, weapon);
    }

    /**
     * One arrow loosed in a volley, high and together with the rest, at this spot (a fighter of theirs): out of
     * its pack, as a skeleton's would be but arcing like a war bow's. Whether it shot.
     */
    static boolean loose(ServerLevel level, VillageFolkEntity archer, double tx, double ty, double tz, float spread) {
        if (!(archer.getMainHandItem().getItem() instanceof ProjectileWeaponItem)) return false;
        if (archer.removeMatching(s -> s.is(Items.ARROW), 1) < 1) return false;
        Arrow arrow = new Arrow(level, archer, new ItemStack(Items.ARROW), archer.getMainHandItem().copy());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = tx - archer.getX(), dz = tz - archer.getZ(), h = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        // A high arc, fifty-five degrees up: the speed that carries it this far, with a little for the air.
        double angle = Math.toRadians(55.0);
        double v = Math.sqrt(h * 0.05 / Math.sin(2 * angle)) * 1.1 + (ty - archer.getEyeY()) * 0.01;
        arrow.shoot(dx / h * Math.cos(angle), Math.sin(angle), dz / h * Math.cos(angle), (float) Math.min(3.0, Math.max(0.6, v)), spread);
        level.addFreshEntity(arrow);
        archer.swing(InteractionHand.MAIN_HAND);
        face(archer, (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        return true;
    }

    // ------------------------------------------------------------------ yielding, and who may strike whom

    /** Has this fighter yielded (or is it no fighter at all)? */
    static boolean yielded(LivingEntity e) {
        Fighter x = FIGHTERS.get(e.getUUID());
        return x != null && x.down();
    }

    /** Is this player fighting for that town? */
    public static boolean volunteerFor(Player p, UUID town) {
        return town.equals(VOLUNTEERS.get(p.getUUID()));
    }

    /** The town this player fights for, or null. */
    @Nullable
    public static UUID sideOf(Player p) {
        return VOLUNTEERS.get(p.getUUID());
    }

    /** A player joins a side (a citizen volunteering, or hired). */
    static void volunteer(Player p, UUID town) {
        VOLUNTEERS.put(p.getUUID(), town);
    }

    /** May a blow from this side land on that one: an enemy fighter on its feet (no medic), a volunteer of the enemy's, a monster. */
    static boolean fair(@Nullable UUID attackerTown, LivingEntity victim) {
        if (attackerTown == null) return false;
        Fighter v = FIGHTERS.get(victim.getUUID());
        if (v != null) return v.army != null && !v.down() && v.role != BattleModel.Role.MEDIC && v.army.enemy.equals(attackerTown);
        if (victim instanceof Player p) {
            UUID side = VOLUNTEERS.get(p.getUUID());
            return side != null && Wars.atWar(side, attackerTown);
        }
        return victim instanceof net.minecraft.world.entity.monster.Enemy;    // a monster in the camp at night is fair game
    }

    /** The town a blow came from, if a fighter (or a volunteer) struck it, its arrow or its own hand. */
    @Nullable
    static UUID sideOfBlow(net.minecraft.world.damagesource.DamageSource src) {
        Entity e = src.getEntity();
        if (e == null) return null;
        Fighter f = FIGHTERS.get(e.getUUID());
        if (f != null) return f.down() ? null : f.army == null ? null : f.army.town;
        if (e instanceof Player p) return VOLUNTEERS.get(p.getUUID());
        return null;
    }

    /** Nobody outside the battle is touched by it, and nobody who has yielded is struck again. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        if (FIGHTERS.isEmpty() || e.getEntity().level().isClientSide) return;
        Entity src = e.getSource().getEntity();
        boolean fromFighter = src != null && FIGHTERS.containsKey(src.getUUID());
        Fighter victim = FIGHTERS.get(e.getEntity().getUUID());
        if (fromFighter) {
            UUID side = sideOfBlow(e.getSource());
            if (side == null || !fair(side, e.getEntity())) e.setCanceled(true);
            return;
        }
        if (victim != null && victim.down() && src instanceof Player) e.setCanceled(true);      // a player does not strike the kneeling
    }

    /**
     * Wounds before deaths: a fighter in the field that a blow from the enemy would bring to its last hearts
     * yields instead, kneeling, and the blow takes it only so far; once in a long while (battleDeaths) it is
     * the blow that kills.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDamage(LivingDamageEvent.Pre e) {
        if (FIGHTERS.isEmpty() || !(e.getEntity() instanceof VillageFolkEntity f) || f.level().isClientSide) return;
        Fighter x = FIGHTERS.get(f.getUUID());
        if (x == null || x.down() || sideOfBlow(e.getSource()) == null) return;
        float left = f.getHealth() - e.getNewDamage();
        if (left > YIELD_HEALTH) return;
        if (f.getRandom().nextInt(100) < Campaigns.deathPercent()) return;      // the rare blow that kills
        e.setNewDamage(Math.max(0.0F, f.getHealth() - YIELD_HEALTH));
        Entity by = e.getSource().getEntity();
        Fighter striker = by == null ? null : FIGHTERS.get(by.getUUID());
        if (striker != null) striker.downed++;
        yieldNow(f, x, by);
    }

    /** It yields: on its knees, its weapon put away, its banner (if it bore it) fallen; the battle told. */
    static void yieldNow(VillageFolkEntity f, Fighter x, @Nullable Entity by) {
        x.order = Order.YIELDED;
        x.placed = false;
        x.foe = null;
        f.setTarget(null);
        f.getNavigation().stop();
        if (f.isUsingItem()) f.stopUsingItem();
        f.setPose(Pose.CROUCHING);
        if (x.rank == Rank.BEARER && f.level() instanceof ServerLevel level) Battles.bannerFalls(level, f, x);
        else if (!(f.getMainHandItem().getItem() instanceof BannerItem)) stow(f, EquipmentSlot.MAINHAND);
        f.showWar(flags(x), x.army == null ? ItemStack.EMPTY : x.army.surcoat);
        shout(f, FolkTalk.pick(f.getRandom(), "I yield! I yield!", "Enough — I yield!", "Quarter! I'm done!", "I can't — I yield!"));
        if (x.army != null && f.level() instanceof ServerLevel level) Battles.yielded(level, x, by);
    }

    // ------------------------------------------------------------------ voices and signals

    /**
     * Said aloud over the field, its bubble seen by every player near enough to see the speaker (an order must
     * carry further than a word at the market: FolkTalk's own bubble is only for those within two dozen blocks).
     */
    static void shout(VillageFolkEntity f, String text) {
        if (text == null || text.isBlank()) return;
        String said = text.length() > 120 ? text.substring(0, 117) + "…" : text;
        PacketDistributor.sendToPlayersTrackingEntity(f, new com.jrpetty.mcassistant.net.FolkSpeechPayload(f.getId(), said, Math.min(140, 50 + said.length() * 2)));
    }

    /** The war horn. */
    static void horn(ServerLevel level, BlockPos at, int variant) {
        List<Holder.Reference<SoundEvent>> horns = SoundEvents.GOAT_HORN_SOUND_VARIANTS;
        Holder<SoundEvent> h = horns.get(Math.floorMod(variant, horns.size()));
        level.playSound(null, at, h.value(), SoundSource.NEUTRAL, 16.0F, 1.0F);
    }

    /** A beat of the drum. */
    static void drum(ServerLevel level, BlockPos at, boolean snare) {
        level.playSound(null, at, (snare ? SoundEvents.NOTE_BLOCK_SNARE : SoundEvents.NOTE_BLOCK_BASEDRUM).value(), SoundSource.NEUTRAL, 3.0F, 0.7F);
    }

    /** A cry from the ranks: a battle cry, or a cheer. */
    static void cry(ServerLevel level, BlockPos at, boolean cheer) {
        level.playSound(null, at, cheer ? SoundEvents.VILLAGER_CELEBRATE : SoundEvents.VINDICATOR_CELEBRATE, SoundSource.NEUTRAL, 3.0F, cheer ? 1.0F : 0.8F);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the fighters in the field now, by town. */
    public static Map<UUID, List<Fighter>> fieldForTests() {
        Map<UUID, List<Fighter>> out = new LinkedHashMap<>();
        for (Fighter f : FIGHTERS.values()) if (f.army != null) out.computeIfAbsent(f.army.town, k -> new ArrayList<>()).add(f);
        return out;
    }

    /** Tests: how many of each role an army has. */
    public static Map<BattleModel.Role, Integer> rolesForTests(Army a) {
        Map<BattleModel.Role, Integer> out = new EnumMap<>(BattleModel.Role.class);
        for (Fighter f : a.fighters) out.merge(f.role, 1, Integer::sum);
        return out;
    }

    /** Where this fighter is to stand, for the tests: x, y, z, yaw. */
    public static double[] placeForTests(Fighter f) {
        return new double[]{ f.tx, f.ty, f.tz, f.yaw };
    }

    static float yawTo(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }
}
