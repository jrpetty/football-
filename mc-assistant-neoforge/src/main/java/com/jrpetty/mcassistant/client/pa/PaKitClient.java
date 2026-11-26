package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.pa.PaBlueprint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * [featpa] The client end of the assistant's three new screens: what the server last said for each (Blueprints, Errand
 * to town, Combat stance), the ghost of a blueprint being placed (PaGhost), and the "kit" action back.
 */
public final class PaKitClient {

    private PaKitClient() {}

    @Nullable static CompoundTag list, status, errand, stance, preview;
    static int stamp;

    /** Send a button's op. */
    static void send(String op, CompoundTag t) {
        t.putString("op", op);
        PaClient.send("kit", t);
    }

    static void send(String op) {
        send(op, new CompoundTag());
    }

    /** From the Orders tab: open a screen, and ask for its state. */
    static void openBlueprints() {
        Minecraft.getInstance().setScreen(new PaBuildScreen());
        send("bp_open");
    }

    static void openErrand() {
        Minecraft.getInstance().setScreen(new PaErrandScreen());
        send("errand_open");
    }

    static void openStance() {
        Minecraft.getInstance().setScreen(new PaStanceScreen());
        send("st_open");
    }

    /** An answer from the server. */
    public static void heard(CompoundTag d) {
        Minecraft mc = Minecraft.getInstance();
        stamp++;
        switch (d.getString("op")) {
            case "bp_list" -> {
                list = d;
                status = d;
                if (!(mc.screen instanceof PaBuildScreen)) mc.setScreen(new PaBuildScreen());
            }
            case "bp_status" -> status = d;
            case "bp_preview" -> {
                preview = d;
                if (d.contains("bp") && mc.level != null) PaGhost.blueprint = PaBlueprint.loadClient(d.getCompound("bp"), mc.level);
                if (PaGhost.blueprint != null && PaGhost.blueprint.stamp == d.getInt("stamp")) {
                    PaGhost.set(BlockPos.of(d.getLong("anchor")), d.getInt("rot"), d.getBoolean("mirror"), d.getLongArray("clash"));
                }
                if (!(mc.screen instanceof PaBuildScreen)) mc.setScreen(new PaBuildScreen());
            }
            case "bp_ghost_off" -> {
                preview = null;
                PaGhost.off();
            }
            case "er_state" -> {
                errand = d;
                if (d.getBoolean("open") && !(mc.screen instanceof PaErrandScreen)) mc.setScreen(new PaErrandScreen());
            }
            case "st_state" -> {
                stance = d;
                if (!(mc.screen instanceof PaStanceScreen)) mc.setScreen(new PaStanceScreen());
            }
            default -> { }
        }
    }
}
