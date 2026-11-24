package com.jrpetty.mcassistant.client.pa;

/** [pa35] A label with a capital (the client's own, for lines the server sends lower case). */
final class PaNetCap {

    private PaNetCap() {}

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
