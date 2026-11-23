package com.jrpetty.mcassistant.client.pa;

import net.minecraft.nbt.CompoundTag;

/** [pa33] Open the assistant's panel from another screen (the U page, the welcome card): the server answers with it. */
public final class PaScreenOpen {

    private PaScreenOpen() {}

    public static void open() {
        CompoundTag a = new CompoundTag();
        a.putString("tab", "talk");
        PaClient.send("open", a);
    }
}
