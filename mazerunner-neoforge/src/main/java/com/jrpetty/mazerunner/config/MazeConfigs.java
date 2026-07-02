package com.jrpetty.mazerunner.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads the bundled {@code maze_config_v2.json} once and caches it. */
public final class MazeConfigs {

    private static final String RESOURCE = "/data/mazerunner/maze/maze_config_v2.json";
    private static volatile MazeConfigData data;

    private MazeConfigs() {}

    public static MazeConfigData get() {
        MazeConfigData local = data;
        if (local == null) {
            synchronized (MazeConfigs.class) {
                if (data == null) {
                    try (Reader reader = new InputStreamReader(
                        Objects.requireNonNull(MazeConfigs.class.getResourceAsStream(RESOURCE),
                            "missing " + RESOURCE), StandardCharsets.UTF_8)) {
                        data = MazeConfigData.parse(reader);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                local = data;
            }
        }
        return local;
    }
}
