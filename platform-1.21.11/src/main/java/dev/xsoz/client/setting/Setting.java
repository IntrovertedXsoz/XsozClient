package dev.xsoz.client.setting;

import com.google.gson.JsonElement;
import java.util.function.BooleanSupplier;

/** One configurable value on a module. Declaration order is UI order is JSON key order. */
public abstract class Setting<T> {
    private final String name;
    private final String description;
    protected final T defaultValue;
    protected T value;
    private BooleanSupplier visible = () -> true;
    private Runnable onChange = () -> { };

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String name() { return name; }

    public String description() { return description; }

    public T get() { return value; }

    public void set(T v) {
        T clamped = clamp(v);
        if (clamped == null || clamped.equals(value)) return;
        value = clamped;
        onChange.run();
    }

    public void reset() { set(defaultValue); }

    protected T clamp(T v) { return v; }

    public boolean isVisible() { return visible.getAsBoolean(); }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier s) {
        this.visible = s;
        return (S) this;
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S onChange(Runnable r) {
        this.onChange = r;
        return (S) this;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement e);
}
