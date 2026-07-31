package com.jrp.floodworld;
import org.slf4j.Logger;
public final class FloodWorld {
    public static final String MODID = "floodworld";
    public static final Logger LOGGER = new Logger() {
        @Override public void info(String s) { harness.World.LOG.append("INFO ").append(s).append('\n'); }
        @Override public void warn(String s, Object a, Object b) {
            harness.World.LOG.append("WARN ").append(s).append(" [").append(a).append(", ").append(b).append("]\n");
        }
    };
    public static boolean flowingFluidsLoaded() { return true; }
}
