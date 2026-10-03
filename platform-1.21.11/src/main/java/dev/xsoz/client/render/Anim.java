package dev.xsoz.client.render;

/** Frame-rate independent smoothing. One instance per animated value. */
public final class Anim {
    private float value;
    private float target;
    private final float speed;
    private long last = System.nanoTime();

    public Anim(float initial, float speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
    }

    public Anim target(float t) {
        this.target = t;
        return this;
    }

    public float target() { return target; }

    public float get() {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1_000_000_000f);
        last = now;
        value += (target - value) * (1f - (float) Math.exp(-speed * dt));
        if (Math.abs(target - value) < 0.0005f) value = target;
        return value;
    }

    public float peek() { return value; }

    public void snap(float v) {
        value = v;
        target = v;
        last = System.nanoTime();
    }

    public static float easeOutCubic(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
