package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * [wpb] Where a thing came from, for commission: only what a worker produced legitimately for the job counts.
 * <ul>
 * <li><b>The mark.</b> A hidden tag on the thing ("mca_prov"): what kind of work, whose it is (the employer's key, or a
 *     player's own farm), the worker, the place in words and the day. Put on at the moment of production: a commissioned
 *     farm hand's harvest of the marked plot, a woodcutter's marked trunk, a shearer's marked sheep, a miner's face, a
 *     fisher's catch at the marked water, and anything harvested on a farm the player has registered as their own
 *     business (PlayerFarms).</li>
 * <li><b>The hand-in</b> (WorkLife.handIn) counts only things marked for that employer and that worker. Bought at the
 *     market, taken from a chest, picked up off another's field, or marked for somebody else: none of it counts, and the
 *     screen says so in plain words, with the reason, and hands it back.</li>
 * <li><b>Theft.</b> Breaking a ripe crop on the town's fields, or on another player's registered farm, without the right
 *     (not your shift's marked plot, not your farm, not a farm you work for) is taking crops: a witnessed crime through the
 *     town's own law (Laws.offence: a witness must see it), and nothing harvested so is marked.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Provenance {

    private Provenance() {}

    public static final String TAG = "mca_prov";
    public static final String FIELD = "field", WOOD = "wood", WOOL = "wool", MINE = "mine", FISH = "fish", OWN = "own";
    static final int THEFT_FINE = 6;

    /** Mark a thing as produced by this worker for this employer at this place. */
    public static void tag(ItemStack st, String kind, String employer, Player worker, String place) {
        if (st.isEmpty()) return;
        long day = worker.level().getDayTime() / 24000L;
        CustomData.update(DataComponents.CUSTOM_DATA, st, t -> {
            CompoundTag m = new CompoundTag();
            m.putString("k", kind);
            m.putString("e", employer);
            m.putString("w", worker.getUUID().toString());
            m.putString("p", place);
            m.putLong("d", day);
            t.put(TAG, m);
        });
    }

    /** The mark on a thing, or null. */
    @Nullable
    public static CompoundTag of(ItemStack st) {
        CustomData d = st.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        CompoundTag t = d.copyTag();
        return t.contains(TAG) ? t.getCompound(TAG) : null;
    }

    /** Why a thing does not count for this employer and worker; "" if it does. */
    public static String whyNot(ItemStack st, String employer, UUID worker) {
        CompoundTag m = of(st);
        if (m == null) return "not grown, cut or caught on a shift of yours (bought, found or taken)";
        if (!m.getString("w").equals(worker.toString())) return "produced by somebody else";
        if (!m.getString("e").equals(employer)) return "produced for another employer (" + m.getString("p") + ")";
        return "";
    }

    /** The thing without its mark (into the employer's stock it stacks with the rest). */
    public static ItemStack clean(ItemStack st) {
        ItemStack out = st.copy();
        CustomData d = out.get(DataComponents.CUSTOM_DATA);
        if (d == null) return out;
        CompoundTag t = d.copyTag();
        t.remove(TAG);
        t.remove(Shifts.MARK);
        if (t.isEmpty()) out.remove(DataComponents.CUSTOM_DATA);
        else out.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return out;
    }

    // ------------------------------------------------------------------ the shifts' produce to hand

    /** A commissioned farm hand's marked plot: the harvest to hand, marked; one seed kept back to plant. */
    static void farmDrops(BlockDropsEvent e, ServerPlayer p, Shifts.Shift s) {
        String where = Villages.name(s.village) + "'s fields";
        for (ItemEntity ie : e.getDrops()) {
            ItemStack st = ie.getItem().copy();
            tag(st, FIELD, WorkLife.employerKey(p), p, where);
            if (!p.getInventory().add(st)) p.drop(st, false);
        }
        e.getDrops().clear();
    }

    /** A commissioned woodcutter's marked trunk: its logs to hand, marked. */
    static void woodDrops(BlockDropsEvent e, ServerPlayer p, Shifts.Shift s) {
        for (ItemEntity ie : e.getDrops()) {
            ItemStack st = ie.getItem().copy();
            tag(st, WOOD, WorkLife.employerKey(p), p, Villages.name(s.village) + "'s wood");
            if (!p.getInventory().add(st)) p.drop(st, false);
        }
        e.getDrops().clear();
    }

    /** A commissioned shearer's marked sheep: its wool to hand, marked; how many. */
    static int shearToHand(ServerLevel level, ServerPlayer p, Shifts.Shift s, Sheep sh) {
        level.playSound(null, sh, SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 1.0F);
        sh.setSheared(true);
        int n = 1 + sh.getRandom().nextInt(3);
        Item wool = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(sh.getColor().getName() + "_wool"));
        ItemStack st = new ItemStack(wool == Items.AIR ? Items.WHITE_WOOL : wool, n);
        tag(st, WOOL, WorkLife.employerKey(p), p, Villages.name(s.village) + "'s flock");
        if (!p.getInventory().add(st)) p.drop(st, false);
        return n;
    }

    // ------------------------------------------------------------------ the fields: own, worked for, or taken

    /** Whose a crop here is: "farm:<id>" for a registered farm, "town" for the town's fields, or null (nobody's). */
    @Nullable
    static String ownerOf(ServerLevel level, BlockPos pos) {
        PlayerFarms.Farm f = PlayerFarms.at(level, pos);
        if (f != null) return "farm:" + f.id;
        Villages.Village v = PlayerServices.townAt(level, pos);
        if (v == null) v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE);   // a farm out past the houses is still the town's
        if (v == null) return null;
        for (BlockPos c : ShiftTasks.fieldsOf(v)) {
            if (Math.abs(c.getX() - pos.getX()) <= 10 && Math.abs(c.getZ() - pos.getZ()) <= 10 && Math.abs(c.getY() - pos.getY()) <= 4) return "town";
        }
        return null;
    }

    /** A ripe crop broken outside a shift: the farmer's own (marked), a farm worked for (marked), or taken (a crime). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getPlayer() instanceof ServerPlayer p) || p.isCreative()) return;
        BlockState st = e.getState();
        TRACE.add("break " + st + " " + p.isCreative());
        if (!(st.getBlock() instanceof CropBlock cb) || !cb.isMaxAge(st)) return;
        Shifts.Shift s = Shifts.of(p);
        if (s != null && s.role == ShiftRole.FARM && s.task != null && e.getPos().equals(s.task.target)) return;   // the shift's own plot
        String owner = ownerOf(level, e.getPos());
        if (owner == null) return;
        if (owner.startsWith("farm:")) {
            PlayerFarms.Farm f = PlayerFarms.byId(owner.substring(5));
            if (f != null && PlayerFarms.mayHarvest(f, p)) return;          // its drops are marked below
            theft(level, p, f == null ? "a farm" : f.name + "'s fields", e.getPos());
            return;
        }
        theft(level, p, "the town's fields", e.getPos());
    }

    static void theft(ServerLevel level, ServerPlayer p, String whose, BlockPos at) {
        Villages.Village v = PlayerServices.townAt(level, at);
        if (v == null) v = Villages.nearest(level, at, Villages.VILLAGE_RANGE);
        TRACE.add("theft " + whose + " " + (v != null));
        if (v == null) return;
        boolean seen = Laws.witness(level, v.id(), p) != null;
        Laws.offence(level, v, p, "taking crops from " + whose, THEFT_FINE);
        if (seen) WorkLife.misconduct(level, p, "taking crops from " + whose, whose.contains("town") && WorkLife.employerKey(p).startsWith("town"));
        THEFTS.add(p.getUUID() + "|" + whose + "|" + seen);
    }

    /** A crop on a registered farm, harvested by its owner, a partner or a hand: marked as the farm's. */
    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p)) return;
        BlockState st = e.getState();
        if (!(st.getBlock() instanceof CropBlock)) return;
        PlayerFarms.Farm f = PlayerFarms.at(e.getLevel(), e.getPos());
        if (f == null || !PlayerFarms.mayHarvest(f, p)) return;
        for (ItemEntity ie : e.getDrops()) tag(ie.getItem(), OWN, "farm:" + f.id, p, f.name);
    }

    /** Tests: the thefts seen, "player|whose|witnessed". */
    static final List<String> THEFTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static String ownerForTests(ServerLevel level, BlockPos pos) {
        return String.valueOf(ownerOf(level, pos));
    }

    static final List<String> TRACE = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static List<String> traceForTests() {
        return List.copyOf(TRACE);
    }

    public static List<String> theftsForTests() {
        return List.copyOf(THEFTS);
    }
}
