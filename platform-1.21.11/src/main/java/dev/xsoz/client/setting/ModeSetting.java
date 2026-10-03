package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> modes;

    public ModeSetting(String name, String description, String def, String... modes) {
        super(name, description, def);
        this.modes = List.of(modes);
    }

    public List<String> modes() { return modes; }

    public boolean is(String mode) { return value.equals(mode); }

    public int index() { return Math.max(0, modes.indexOf(value)); }

    public void cycle(int dir) {
        int i = Math.floorMod(index() + dir, modes.size());
        set(modes.get(i));
    }

    @Override
    protected String clamp(String v) { return modes.contains(v) ? v : value; }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement e) {
        if (e != null && e.isJsonPrimitive() && modes.contains(e.getAsString())) value = e.getAsString();
    }
}
