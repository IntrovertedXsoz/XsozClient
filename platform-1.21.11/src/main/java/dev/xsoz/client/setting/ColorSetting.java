package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An opaque colour edited as a hue slider (saturation and brightness come from the default). */
public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, String description, int defArgb) {
        super(name, description, 0xFF000000 | defArgb);
    }

    public int argb() { return value; }

    @Override
    protected Integer clamp(Integer v) { return v == null ? value : (0xFF000000 | v); }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(String.format("#%06X", value & 0xFFFFFF)); }

    @Override
    public void fromJson(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return;
        try {
            String s = e.getAsString().replace("#", "");
            value = 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException ignored) {
            // keep the current value
        }
    }
}
