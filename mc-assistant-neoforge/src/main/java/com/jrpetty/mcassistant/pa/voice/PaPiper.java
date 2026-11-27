package com.jrpetty.mcassistant.pa.voice;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [pa33b] The optional offline neural voice: Piper (rhasspy/piper, MIT; development has moved to OHF-Voice/piper1-gpl,
 * GPL-3, so the MIT release is the one fetched). Nothing ships in the mod jar: when the player picks a natural voice
 * and agrees, the Piper program for their OS and the chosen voice model are downloaded once into the config folder,
 * apart from the jar, so their licences never mix with the mod's. Each line is synthesised out of process (Piper reads
 * the text on stdin and writes a WAV) on the voice's own thread and played from there; the game thread never waits.
 * Every failure (not installed, not this OS, the program or model missing, a crash) is a message, and the voice falls
 * back to the computer's own.
 *
 * <p>Not run here: downloading and running native programs needs the player's go-ahead on their own computer. The
 * catalogue, the paths, the command and the failure handling are unit-tested; the sound is checked on a real PC.
 */
public final class PaPiper {

    /** The MIT release of the Piper program, per OS. */
    public static final String RELEASE = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/";

    /**
     * Voices for the picker: British first. Licences are those on each voice's model card (rhasspy/piper-voices on
     * Hugging Face), to be confirmed at download from the MODEL_CARD that comes with the voice; one marked "check" is
     * shown with its card and not offered by default.
     */
    public record Voice(String id, String label, String accent, String gender, String quality, String licence, boolean offered) {
        public String modelUrl() {
            String[] p = id.split("-");                      // en_GB-alan-medium
            String lang = p[0], family = lang.substring(0, 2);
            return "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/" + family + "/" + lang + "/" + p[1] + "/" + p[2] + "/" + id + ".onnx";
        }
    }

    public static final List<Voice> CATALOGUE = List.of(
        new Voice("en_GB-alan-medium", "Alan", "British (RP-ish)", "male", "medium", "check model card", true),
        new Voice("en_GB-northern_english_male-medium", "Northern English", "British (northern)", "male", "medium", "check model card", true),
        new Voice("en_GB-jenny_dioco-medium", "Jenny", "British (southern)", "female", "medium", "check model card (attribution)", true),
        new Voice("en_GB-cori-high", "Cori", "British", "female", "high", "check model card", true),
        new Voice("en_GB-alba-medium", "Alba", "Scottish", "female", "medium", "check model card", true),
        new Voice("en_GB-semaine-medium", "Semaine", "British (several speakers)", "mixed", "medium", "check model card (may be non-commercial)", false),
        new Voice("en_US-ryan-high", "Ryan", "American", "male", "high", "check model card", true),
        new Voice("en_US-amy-medium", "Amy", "American", "female", "medium", "check model card", true));

    private final Path dir;

    public PaPiper(Path dir) {
        this.dir = dir;
    }

    /** The release archive for this OS, or null where Piper has no build. */
    public static String archiveFor(String osName, String arch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT), a = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        boolean arm = a.contains("aarch64") || a.contains("arm64");
        if (os.contains("win")) return arm ? null : "piper_windows_amd64.zip";
        if (os.contains("mac") || os.contains("darwin")) return arm ? "piper_macos_aarch64.tar.gz" : "piper_macos_x64.tar.gz";
        if (os.contains("linux")) return arm ? "piper_linux_aarch64.tar.gz" : "piper_linux_x86_64.tar.gz";
        return null;
    }

    public Path program(String osName) {
        return dir.resolve("piper").resolve((osName != null && osName.toLowerCase(Locale.ROOT).contains("win")) ? "piper.exe" : "piper");
    }

    public Path model(Voice v) {
        return dir.resolve("voices").resolve(v.id() + ".onnx");
    }

    /** Is the program and this voice (with its .json) on disk? */
    public boolean installed(String osName, Voice v) {
        return Files.isRegularFile(program(osName)) && Files.isRegularFile(model(v)) && Files.isRegularFile(Path.of(model(v) + ".json"));
    }

    /** The installed voices. */
    public List<Voice> installedVoices(String osName) {
        List<Voice> out = new ArrayList<>();
        for (Voice v : CATALOGUE) if (installed(osName, v)) out.add(v);
        return out;
    }

    /** The command that turns stdin text into a WAV: rate as Piper's length scale (bigger is slower). */
    public List<String> command(String osName, Voice v, File wav, double lengthScale) {
        return List.of(program(osName).toString(), "--model", model(v).toString(), "--output_file", wav.getAbsolutePath(),
            "--length_scale", String.format(Locale.ROOT, "%.2f", Math.max(0.6, Math.min(1.8, lengthScale))));
    }

    /** Piper's length scale for the mood and the player's rate (-5 .. 5): gloomy slower, a higher rate quicker. */
    public static double lengthScale(double gloom, int rate) {
        return 1.0 + 0.25 * gloom - 0.07 * rate;
    }

    public static Voice byId(String id) {
        for (Voice v : CATALOGUE) if (v.id().equals(id)) return v;
        return null;
    }
}
