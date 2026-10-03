package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class NumberSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;
    private final String unit;

    public NumberSetting(String name, String description, double def, double min, double max, double step, String unit) {
        super(name, description, def);
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit == null ? "" : unit;
    }

    public NumberSetting(String name, String description, double def, double min, double max, double step) {
        this(name, description, def, min, max, step, "");
    }

    public double min() { return min; }

    public double max() { return max; }

    public double step() { return step; }

    public double value() { return value; }

    public int intValue() { return (int) Math.round(value); }

    public float floatValue() { return value.floatValue(); }

    /** 0..1 position of the current value on the slider. */
    public double fraction() { return (value - min) / (max - min); }

    public void setFraction(double f) { set(min + Math.max(0, Math.min(1, f)) * (max - min)); }

    @Override
    protected Double clamp(Double v) {
        if (v == null || v.isNaN()) return value;
        double c = Math.max(min, Math.min(max, v));
        if (step > 0) c = min + Math.round((c - min) / step) * step;
        c = Math.max(min, Math.min(max, c));
        return Math.round(c * 1000.0) / 1000.0;
    }

    public String display() {
        boolean integral = step >= 1 && Math.abs(step - Math.rint(step)) < 1e-9;
        String n = integral ? String.valueOf((long) Math.round(value)) : String.format(java.util.Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
        return unit.isEmpty() ? n : n + unit;
    }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement e) {
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) value = clamp(e.getAsDouble());
    }
}
