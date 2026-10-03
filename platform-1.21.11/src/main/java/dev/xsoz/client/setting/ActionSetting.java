package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A button row in a module's settings. Holds no value and is never persisted. */
public final class ActionSetting extends Setting<Boolean> {
    private final Runnable action;

    public ActionSetting(String name, String description, Runnable action) {
        super(name, description, false);
        this.action = action;
    }

    public void run() { action.run(); }

    @Override
    public JsonElement toJson() { return JsonNull.INSTANCE; }

    @Override
    public void fromJson(JsonElement e) { }
}
