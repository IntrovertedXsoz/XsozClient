package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String name, String description, boolean def) {
        super(name, description, def);
    }

    public boolean on() { return Boolean.TRUE.equals(value); }

    public void toggle() { set(!on()); }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(on()); }

    @Override
    public void fromJson(JsonElement e) {
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) value = e.getAsBoolean();
    }
}
