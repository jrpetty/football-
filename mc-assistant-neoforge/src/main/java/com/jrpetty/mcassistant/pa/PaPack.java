package com.jrpetty.mcassistant.pa;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * [pa33c-2b] Its pack, run from the panel's Pack tab: rows that open with its level, things moved between its pack, its
 * gear and yours (dragged, or clicked), and the quick buttons: Equip best, Give me everything, Dump (the junk on the
 * floor), Sort, and Keep (locks on what it should hang on to). Handing things over needs you near (8 blocks), as a
 * player's would; sorting, locks and its own gear work from anywhere.
 */
public final class PaPack {

    private PaPack() {}

    public static final int ROW = PaPackRules.ROW, SIZE = PaPackRules.SIZE, REACH = PaPackRules.REACH;

    public static int open(int level) { return PaPackRules.open(level); }

    public static int rowLevel(int row) { return PaPackRules.rowLevel(row); }

    /** The gear slots, in the order the panel shows them. */
    public static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private static int group(ItemStack s) {
        return PaPackRules.group(s.getItem() instanceof TieredItem || s.getItem() instanceof net.minecraft.world.item.BowItem || s.getItem() instanceof ShieldItem,
            s.getItem() instanceof ArmorItem, s.get(DataComponents.FOOD) != null, id(s), s.getItem() instanceof BlockItem);
    }

    // ------------------------------------------------------------------ the panel's state

    /** Its slots (with what's in them), its gear, how many slots are open, and its locks, for the Pack tab. */
    public static void state(CompoundTag t, PlayerAssistantEntity a, PaData.Rec r) {
        var reg = a.registryAccess();
        ListTag slots = new ListTag();
        NonNullList<ItemStack> inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty()) continue;
            CompoundTag c = new CompoundTag();
            c.putInt("i", i);
            c.put("s", inv.get(i).save(reg));
            slots.add(c);
        }
        t.put("slots", slots);
        CompoundTag gear = new CompoundTag();
        for (EquipmentSlot e : GEAR) {
            ItemStack s = a.getItemBySlot(e);
            if (!s.isEmpty()) gear.put(e.getName(), s.save(reg));
        }
        t.put("gear", gear);
        t.putInt("open", open(a.veteranLevel()));
        ListTag keep = new ListTag();
        for (String k : r.keep) keep.add(net.minecraft.nbt.StringTag.valueOf(k));
        t.put("keep", keep);
    }

    // ------------------------------------------------------------------ what the Pack tab asks for

    /** One move or button from the Pack tab. Returns a short note for the panel, or "" (nothing to say). */
    public static String op(ServerPlayer p, PlayerAssistantEntity a, PaData.Rec r, CompoundTag args) {
        NonNullList<ItemStack> inv = a.getInventoryItems();
        int open = open(a.veteranLevel());
        boolean near = a.distanceToSqr(p) <= REACH * REACH;
        String op = args.getString("op");
        int i = args.getInt("i");
        switch (op) {
            case "take" -> {                                          // its slot -> your inventory
                if (!near) return far(a, p);
                if (i < 0 || i >= inv.size() || inv.get(i).isEmpty()) return "";
                ItemStack moving = inv.get(i).copy();
                p.getInventory().add(moving);
                inv.set(i, moving.isEmpty() ? ItemStack.EMPTY : moving);
                return moving.isEmpty() ? "" : "Your inventory is full.";
            }
            case "give" -> {                                          // your slot -> its pack (a given slot if open and free)
                if (!near) return far(a, p);
                int j = args.getInt("j");
                if (j < 0 || j >= 36) return "";
                ItemStack ps = p.getInventory().getItem(j);
                if (ps.isEmpty()) return "";
                if (args.contains("i") && i >= 0 && i < open && inv.get(i).isEmpty()) {
                    inv.set(i, ps.copy());
                    PaBondAct.given(a, ps.copy());                                    // [pa35]
                    p.getInventory().setItem(j, ItemStack.EMPTY);
                    return "";
                }
                ItemStack gave = ps.copy();
                ItemStack left = a.insertGiven(ps.copy());
                p.getInventory().setItem(j, left);
                if (left.getCount() < gave.getCount()) PaBondAct.given(a, gave);   // [pa35] fed, a gift, its favourite, kit; a locked weapon said so
                return left.isEmpty() ? "" : "Its pack is full" + (open < SIZE ? " (its third row opens at level " + rowLevel(2) + ")." : ".");
            }
            case "move" -> {                                          // one of its slots to another (swapped)
                int k = args.getInt("k");
                if (i < 0 || i >= inv.size() || k < 0 || k >= open || i == k) return "";
                ItemStack x = inv.get(i);
                inv.set(i, inv.get(k));
                inv.set(k, x);
                return "";
            }
            case "equip" -> {                                         // its slot (or yours, "j") onto its gear slot "e"
                EquipmentSlot e = slot(args.getString("e"));
                if (e == null) return "";
                boolean fromYou = args.contains("j");
                if (fromYou && !near) return far(a, p);
                ItemStack s = fromYou ? p.getInventory().getItem(args.getInt("j")) : (i >= 0 && i < inv.size() ? inv.get(i) : ItemStack.EMPTY);
                if (s.isEmpty()) return "";
                if (e.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && a.getEquipmentSlotForItem(s) != e) return "That doesn't go there.";
                ItemStack worn = a.getItemBySlot(e).copy();
                a.setItemSlot(e, s.copy());
                if (fromYou) p.getInventory().setItem(args.getInt("j"), worn);
                else inv.set(i, worn);
                return "";
            }
            case "unequip" -> {                                       // its gear slot -> its pack, or to you ("to" = "you")
                EquipmentSlot e = slot(args.getString("e"));
                if (e == null || a.getItemBySlot(e).isEmpty()) return "";
                ItemStack worn = a.getItemBySlot(e).copy();
                if (args.getString("to").equals("you")) {
                    if (!near) return far(a, p);
                    p.getInventory().add(worn);
                } else {
                    worn = a.insertItem(worn);
                }
                a.setItemSlot(e, worn);
                return worn.isEmpty() ? "" : "No room for it.";
            }
            case "best" -> { equipBest(a); return ""; }
            case "all" -> {                                           // everything but its gear and what it keeps
                if (!near) return far(a, p);
                int given = 0;
                for (int k = 0; k < inv.size(); k++) {
                    ItemStack s = inv.get(k);
                    if (s.isEmpty() || r.keep.contains(id(s))) continue;
                    ItemStack moving = s.copy();
                    int before = moving.getCount();
                    p.getInventory().add(moving);
                    given += before - moving.getCount();
                    inv.set(k, moving.isEmpty() ? ItemStack.EMPTY : moving);
                }
                return given == 0 ? "Nothing to hand over (or your inventory is full)." : "";
            }
            case "dump" -> {                                          // the junk, on the floor at its feet
                int n = 0;
                for (int k = 0; k < inv.size(); k++) {
                    ItemStack s = inv.get(k);
                    if (s.isEmpty() || r.keep.contains(id(s)) || !PaPackRules.junk(id(s), s.getCount())) continue;
                    a.spawnAtLocation(s.copy());
                    inv.set(k, ItemStack.EMPTY);
                    n++;
                }
                return n == 0 ? "No junk to dump." : "";
            }
            case "sort" -> { sort(a, open); return ""; }
            case "lock" -> {                                          // keep (or stop keeping) what's in a slot
                ItemStack s = i >= 0 && i < inv.size() ? inv.get(i) : ItemStack.EMPTY;
                if (s.isEmpty()) return "";
                String id = id(s);
                if (!r.keep.remove(id)) r.keep.add(id);
                PaData.touch();
                return "";
            }
            default -> { return ""; }
        }
    }

    private static String far(PlayerAssistantEntity a, ServerPlayer p) {
        return "Come closer to hand things over (" + (int) Math.sqrt(a.distanceToSqr(p)) + " blocks away; " + REACH + " will do).";
    }

    private static EquipmentSlot slot(String name) {
        for (EquipmentSlot e : GEAR) if (e.getName().equals(name)) return e;
        return null;
    }

    /** The best armour in its pack on, piece by piece; its best weapon in hand; a shield in the other. */
    public static void equipBest(PlayerAssistantEntity a) {
        NonNullList<ItemStack> inv = a.getInventoryItems();
        for (EquipmentSlot e : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (a.getItemBySlot(e).is(net.minecraft.world.item.Items.ELYTRA) && (a.isFallFlying() || a.mind.wingsOn)) continue;   // [pa33g] not mid-flight
            int best = -1;
            int bestDef = a.getItemBySlot(e).getItem() instanceof ArmorItem w ? w.getDefense() : -1;
            for (int k = 0; k < inv.size(); k++) {
                ItemStack s = inv.get(k);
                if (s.getItem() instanceof ArmorItem ar && a.getEquipmentSlotForItem(s) == e && ar.getDefense() > bestDef && PaLevels.canUse(a, s)) { best = k; bestDef = ar.getDefense(); }   // [pa35] gear ladder
            }
            if (best >= 0) {
                ItemStack worn = a.getItemBySlot(e).copy();
                a.setItemSlot(e, inv.get(best).copy());
                inv.set(best, worn);
            }
        }
        a.equipBestWeapon();
        if (a.getOffhandItem().isEmpty()) {
            for (int k = 0; k < inv.size(); k++) {
                if (inv.get(k).getItem() instanceof ShieldItem) {
                    a.setItemSlot(EquipmentSlot.OFFHAND, inv.get(k).copy());
                    inv.set(k, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }

    /** Piles merged, then laid out by kind and name in the open slots. */
    public static void sort(PlayerAssistantEntity a, int open) {
        NonNullList<ItemStack> inv = a.getInventoryItems();
        List<ItemStack> all = new ArrayList<>();
        for (int k = 0; k < inv.size(); k++) {
            ItemStack s = inv.get(k);
            if (s.isEmpty()) continue;
            boolean merged = false;
            for (ItemStack o : all) {
                if (ItemStack.isSameItemSameComponents(o, s) && o.getCount() < o.getMaxStackSize()) {
                    int m = Math.min(o.getMaxStackSize() - o.getCount(), s.getCount());
                    o.grow(m);
                    s.shrink(m);
                    if (s.isEmpty()) { merged = true; break; }
                }
            }
            if (!merged && !s.isEmpty()) all.add(s.copy());
            inv.set(k, ItemStack.EMPTY);
        }
        all.sort(Comparator.<ItemStack>comparingInt(st -> group(st)).thenComparing(st -> st.getHoverName().getString()));
        int k = 0;
        for (ItemStack s : all) {
            if (k < inv.size()) inv.set(k++, s);
            else a.spawnAtLocation(s);                               // never: 27 slots in, 27 out
        }
    }

    /** Is this item kept (locked)? */
    public static boolean kept(Set<String> keep, ItemStack s) {
        return !s.isEmpty() && keep.contains(id(s));
    }
}
