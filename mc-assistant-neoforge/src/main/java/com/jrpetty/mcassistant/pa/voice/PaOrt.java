package com.jrpetty.mcassistant.pa.voice;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * [voice37] ONNX Runtime for Java (com.microsoft.onnxruntime, MIT), shared by both neural voices: one class loader over
 * the downloaded jar, one environment, so its native library is loaded once per game. Everything goes through
 * reflection, so nothing of it joins the mod's classpath and a missing or broken runtime fails into a message.
 *
 * <p>The jar carries its own libraries for Windows x64, macOS (Apple silicon and Intel) and Linux (x64, arm64). Sessions
 * run on the CPU; on a Mac, Core ML is offered first and the CPU takes whatever it can't run.
 */
public final class PaOrt {

    private PaOrt() {}

    private static ClassLoader loader;
    private static Path from;
    private static Object env;

    /**
     * [voice42] The runtime jars, both in the Kokoro folder. 1.25.1 everywhere it is built for (Windows x64, Linux x64 and
     * arm64, Apple-silicon Macs). ONNX Runtime 1.21 to 1.24 work out Kokoro's int8 convolutions wrongly on CPUs with Intel
     * AMX (the U8U8 tile kernel's column tail), which came out as NaN, huge samples, silence and clicks; 1.25 fixed it. 1.24
     * dropped Intel Macs, which have no AMX, so they keep 1.23.2, cut down to their own library.
     */
    public static final String JAR = "onnxruntime.jar", JAR_INTEL_MAC = "onnxruntime-macx64.jar";

    /** Which runtime jar this computer loads: the Intel Mac one on an Intel Mac, else the main one. */
    public static String jarName(String osName, String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return mac(osName) && (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) ? JAR_INTEL_MAC : JAR;
    }

    /** The runtime jar for this computer, in the folder that holds Kokoro's files. */
    public static Path jar(Path kokoroDir) {
        return kokoroDir.resolve(jarName(System.getProperty("os.name"), System.getProperty("os.arch")));
    }

    /** The runtime's class loader over this jar (the same one for every caller once made). */
    public static synchronized ClassLoader loader(Path jar) throws Exception {
        if (loader != null) {
            if (!jar.equals(from)) throw new IllegalStateException("ONNX Runtime is already loaded from " + from);
            return loader;
        }
        loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, PaOrt.class.getClassLoader());
        from = jar;
        return loader;
    }

    public static synchronized Object env(ClassLoader cl) throws Exception {
        if (env == null) env = cl.loadClass("ai.onnxruntime.OrtEnvironment").getMethod("getEnvironment").invoke(null);
        return env;
    }

    /** Threads for one session: leave the game a core or two (at least one, at most eight). */
    public static int threads(int cores) {
        return Math.max(1, Math.min(8, cores <= 4 ? cores - 1 : cores - 2));
    }

    /** Open a model. coreMl: try Apple's Core ML first (a Mac); the CPU always backs it up. */
    public static Object session(ClassLoader cl, Path model, int threads, boolean coreMl) throws Exception {
        Object e = env(cl);
        Class<?> envC = cl.loadClass("ai.onnxruntime.OrtEnvironment");
        Class<?> optsC = cl.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
        Object opts = optsC.getConstructor().newInstance();
        optsC.getMethod("setIntraOpNumThreads", int.class).invoke(opts, threads);
        optsC.getMethod("setInterOpNumThreads", int.class).invoke(opts, 1);
        if (coreMl) {
            try { optsC.getMethod("addCoreML").invoke(opts); } catch (Throwable ignored) { /* not in this build: CPU */ }
        }
        return envC.getMethod("createSession", String.class, optsC).invoke(e, model.toString(), opts);
    }

    public static boolean mac(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /** A tensor of longs or floats, of this shape. */
    public static Object tensor(ClassLoader cl, long[] data, long... shape) throws Exception {
        Class<?> tc = cl.loadClass("ai.onnxruntime.OnnxTensor");
        Method m = tc.getMethod("createTensor", cl.loadClass("ai.onnxruntime.OrtEnvironment"), LongBuffer.class, long[].class);
        return m.invoke(null, env(cl), LongBuffer.wrap(data), shape);
    }

    public static Object tensor(ClassLoader cl, float[] data, long... shape) throws Exception {
        Class<?> tc = cl.loadClass("ai.onnxruntime.OnnxTensor");
        Method m = tc.getMethod("createTensor", cl.loadClass("ai.onnxruntime.OrtEnvironment"), FloatBuffer.class, long[].class);
        return m.invoke(null, env(cl), FloatBuffer.wrap(data), shape);
    }

    /** Run: the inputs by name; the result (close it when done with what it holds). */
    public static AutoCloseable run(Object session, Map<String, ?> feeds) throws Exception {
        return (AutoCloseable) session.getClass().getMethod("run", Map.class).invoke(session, feeds);
    }

    /** One output of a result, by position, still owned by the result. */
    public static Object output(AutoCloseable result, int i) throws Exception {
        return result.getClass().getMethod("get", int.class).invoke(result, i);
    }

    /** An output tensor's shape. */
    public static long[] shape(Object tensor) throws Exception {
        Object info = tensor.getClass().getMethod("getInfo").invoke(tensor);
        return (long[]) info.getClass().getMethod("getShape").invoke(info);
    }

    /** An output tensor's floats (a copy). */
    public static float[] floats(Object tensor) throws Exception {
        FloatBuffer fb = (FloatBuffer) tensor.getClass().getMethod("getFloatBuffer").invoke(tensor);
        float[] a = new float[fb.remaining()];
        fb.get(a);
        return a;
    }

    /** The names of a session's inputs, in order. */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> inputs(Object session) throws Exception {
        return new java.util.ArrayList<>((java.util.Set<String>) session.getClass().getMethod("getInputNames").invoke(session));
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> outputs(Object session) throws Exception {
        return new java.util.ArrayList<>((java.util.Set<String>) session.getClass().getMethod("getOutputNames").invoke(session));
    }

    public static void close(Object o) {
        if (o instanceof AutoCloseable c) { try { c.close(); } catch (Exception ignored) { } }
    }
}
