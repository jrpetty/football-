package com.jrpetty.mcassistant.block;

import com.jrpetty.mcassistant.item.LeisureItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * [leisure] The pieces on a draughts board, where they stand, kept with it and sent to everybody near so a game is seen
 * move by move (DraughtsBoardRenderer). The thirty-two dark squares, counted row by row from black's side: nought for
 * an empty square, then a red piece, a black piece, a red king and a black king. With who is playing which, and the
 * last move made (drawn lifted a moment as it lands).
 */
public class DraughtsBoardBlockEntity extends BlockEntity {

    public static final byte EMPTY = 0, RED = 1, BLACK = 2, RED_KING = 3, BLACK_KING = 4;

    private byte[] cells = start();
    private int from = -1, to = -1;
    private String red = "", black = "";

    public DraughtsBoardBlockEntity(BlockPos pos, BlockState state) {
        super(LeisureItems.DRAUGHTS_BOARD_BE.get(), pos, state);
    }

    /** The pieces set out for a game: black's twelve on its three rows, red's on its. */
    public static byte[] start() {
        byte[] c = new byte[32];
        for (int i = 0; i < 12; i++) c[i] = BLACK;
        for (int i = 20; i < 32; i++) c[i] = RED;
        return c;
    }

    public byte[] cells() {
        return cells;
    }

    public int lastFrom() {
        return from;
    }

    public int lastTo() {
        return to;
    }

    public String redName() {
        return red;
    }

    public String blackName() {
        return black;
    }

    /** The board as it stands now (a move made, a game set out), sent to whoever is near. */
    public void show(byte[] now, int movedFrom, int movedTo, String redName, String blackName) {
        cells = now.clone();
        from = movedFrom;
        to = movedTo;
        red = redName == null ? "" : redName;
        black = blackName == null ? "" : blackName;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void write(CompoundTag tag) {
        tag.putByteArray("Cells", cells);
        tag.putInt("From", from);
        tag.putInt("To", to);
        tag.putString("Red", red);
        tag.putString("Black", black);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        byte[] c = tag.getByteArray("Cells");
        cells = c.length == 32 ? c : start();
        from = tag.contains("From") ? tag.getInt("From") : -1;
        to = tag.contains("To") ? tag.getInt("To") : -1;
        red = tag.getString("Red");
        black = tag.getString("Black");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        write(tag);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
