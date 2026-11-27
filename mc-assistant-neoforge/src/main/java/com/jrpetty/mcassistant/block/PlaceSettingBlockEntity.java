package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.DiningItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [dining] What is on a place setting (PlaceSettingBlock): the dish on the plate and how many bites of it are left, the
 * drink in the cup and its sips, whose they are (a diner's, or anybody's), which house they belong to (a café's, a
 * tavern's or a restaurant's crockery, or a player's own), the order they were served on, and how well the cook made
 * the dish. Saved with the world and sent to the client (the renderer shows a dish with no model of its own on the plate).
 */
public class PlaceSettingBlockEntity extends BlockEntity {

    /** The dish (one of it), and the bites it was cut into and has left. */
    ItemStack food = ItemStack.EMPTY;
    int bites, bitesOf;
    /** The drink (one of it), and its sips. */
    ItemStack drink = ItemStack.EMPTY;
    int sips, sipsOf;
    /** The house whose crockery it is (null: a player's own plate), the diner it was set before, and its name. */
    @Nullable UUID house, diner;
    String dinerName = "";
    /** The order it was served on (-1: none), and the cook's hand: 0 plain, 1 good, 2 a master's. */
    int order = -1, quality;
    /** Set when the house clears it away itself: no drops, nothing lost. */
    boolean cleared;
    /** What is left when it is eaten: the bowl a stew came in, the bottle a drink was poured from (back to the house). */
    ItemStack foodLeft = ItemStack.EMPTY, drinkLeft = ItemStack.EMPTY;
    /** The table's carpet the cloth was laid in place of (put back when it is cleared). */
    BlockState carpet = PlaceSettingBlock.carpet();

    public PlaceSettingBlockEntity(BlockPos pos, BlockState state) {
        super(DiningItems.PLACE_SETTING_BE.get(), pos, state);
    }

    public ItemStack food() { return food; }

    public ItemStack drink() { return drink; }

    public int bitesLeft() { return bites; }

    public int sipsLeft() { return sips; }

    @Nullable public UUID house() { return house; }

    @Nullable public UUID diner() { return diner; }

    public String dinerName() { return dinerName; }

    public int order() { return order; }

    public int quality() { return quality; }

    /** The carpet to put back on the table when the setting is cleared. */
    public BlockState carpet() { return carpet; }

    /** Laid in place of this carpet. */
    public void layOn(BlockState carpetWas) {
        if (PlaceSettingBlock.isCloth(carpetWas)) carpet = carpetWas;
        setChanged();
    }

    /** Is anything left to eat or drink? */
    public boolean anyLeft() {
        return bites > 0 && !food.isEmpty() || sips > 0 && !drink.isEmpty();
    }

    /** Served: a dish set on the plate for a diner, cut into so many bites. */
    public void serveFood(ItemStack dish, int biteCount, @Nullable UUID house, @Nullable UUID diner, String dinerName, int order, int quality) {
        this.food = dish.copyWithCount(1);
        this.bites = this.bitesOf = Math.max(1, biteCount);
        net.minecraft.world.food.FoodProperties f = PlaceSettingBlock.foodOf(dish);
        this.foodLeft = f != null && f.usingConvertsTo().isPresent() ? f.usingConvertsTo().get().copy() : ItemStack.EMPTY;
        own(house, diner, dinerName, order);
        this.quality = Math.max(0, Math.min(2, quality));
        changed();
    }

    /** Served: a drink poured into the cup for a diner. */
    public void serveDrink(ItemStack one, int sipCount, @Nullable UUID house, @Nullable UUID diner, String dinerName, int order) {
        this.drink = one.copyWithCount(1);
        this.sips = this.sipsOf = Math.max(1, sipCount);
        this.drinkLeft = PlaceSettingBlock.vesselOf(one);
        own(house, diner, dinerName, order);
        changed();
    }

    private void own(@Nullable UUID house, @Nullable UUID diner, String dinerName, int order) {
        this.house = house;
        this.diner = diner;
        this.dinerName = dinerName == null ? "" : dinerName;
        this.order = order;
        this.cleared = false;
    }

    /** One bite eaten: the bites left after it. */
    int biteTaken() {
        if (bites > 0) bites--;
        if (bites == 0) food = ItemStack.EMPTY;
        changed();
        return bites;
    }

    /** One sip drunk: the sips left after it. */
    int sipTaken() {
        if (sips > 0) sips--;
        if (sips == 0) drink = ItemStack.EMPTY;
        changed();
        return sips;
    }

    /** The bowl and the bottle, taken off it as it is cleared (once). */
    public java.util.List<ItemStack> takeLeftovers() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        if (!foodLeft.isEmpty()) out.add(foodLeft.copy());
        if (!drinkLeft.isEmpty()) out.add(drinkLeft.copy());
        foodLeft = ItemStack.EMPTY;
        drinkLeft = ItemStack.EMPTY;
        setChanged();
        return out;
    }

    /** The house takes it away (its waiter clears the table): nothing drops. */
    public void markCleared() {
        cleared = true;
    }

    /** How far through the dish: 3 whole, 2 begun, 1 nearly gone, 0 eaten (the plate's BITES). */
    int stage() {
        if (food.isEmpty() || bites <= 0) return 0;
        return Math.max(1, (int) Math.ceil(3.0 * bites / Math.max(1, bitesOf)));
    }

    /** How far through the drink, as the cup shows it. */
    PlaceSettingBlock.Cup cupStage() {
        if (drink.isEmpty() || sips <= 0) return PlaceSettingBlock.Cup.EMPTY;
        return sips * 2 > sipsOf ? PlaceSettingBlock.Cup.FULL : PlaceSettingBlock.Cup.HALF;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void write(CompoundTag tag, HolderLookup.Provider registries, boolean full) {
        if (!food.isEmpty()) tag.put("Food", food.save(registries));
        if (!drink.isEmpty()) tag.put("Drink", drink.save(registries));
        tag.putInt("Bites", bites);
        tag.putInt("BitesOf", bitesOf);
        tag.putInt("Sips", sips);
        tag.putInt("SipsOf", sipsOf);
        if (!full) return;
        if (house != null) tag.putUUID("House", house);
        if (diner != null) tag.putUUID("Diner", diner);
        tag.putString("DinerName", dinerName);
        tag.putInt("Order", order);
        tag.putInt("Quality", quality);
        tag.put("Carpet", net.minecraft.nbt.NbtUtils.writeBlockState(carpet));
        if (!foodLeft.isEmpty()) tag.put("FoodLeft", foodLeft.save(registries));
        if (!drinkLeft.isEmpty()) tag.put("DrinkLeft", drinkLeft.save(registries));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag, registries, true);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        food = tag.contains("Food") ? ItemStack.parseOptional(registries, tag.getCompound("Food")) : ItemStack.EMPTY;
        drink = tag.contains("Drink") ? ItemStack.parseOptional(registries, tag.getCompound("Drink")) : ItemStack.EMPTY;
        bites = tag.getInt("Bites");
        bitesOf = tag.getInt("BitesOf");
        sips = tag.getInt("Sips");
        sipsOf = tag.getInt("SipsOf");
        house = tag.hasUUID("House") ? tag.getUUID("House") : null;
        diner = tag.hasUUID("Diner") ? tag.getUUID("Diner") : null;
        dinerName = tag.getString("DinerName");
        order = tag.contains("Order") ? tag.getInt("Order") : -1;
        quality = tag.getInt("Quality");
        foodLeft = tag.contains("FoodLeft") ? ItemStack.parseOptional(registries, tag.getCompound("FoodLeft")) : ItemStack.EMPTY;
        drinkLeft = tag.contains("DrinkLeft") ? ItemStack.parseOptional(registries, tag.getCompound("DrinkLeft")) : ItemStack.EMPTY;
        if (tag.contains("Carpet")) {
            BlockState c = net.minecraft.nbt.NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                tag.getCompound("Carpet"));
            if (PlaceSettingBlock.isCloth(c)) carpet = c;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        write(tag, registries, false);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
