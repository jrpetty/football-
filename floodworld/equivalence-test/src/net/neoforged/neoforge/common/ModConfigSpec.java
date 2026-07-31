package net.neoforged.neoforge.common;
public class ModConfigSpec {
    public static class ConfigValue<T> {
        protected T v;
        public ConfigValue(T v) { this.v = v; }
        public T get() { harness.C.configGet++; return v; }
        public void set(T v) { this.v = v; }
    }
    public static class BooleanValue extends ConfigValue<Boolean> { public BooleanValue(Boolean v) { super(v); } }
    public static class IntValue extends ConfigValue<Integer> { public IntValue(Integer v) { super(v); } }
}
