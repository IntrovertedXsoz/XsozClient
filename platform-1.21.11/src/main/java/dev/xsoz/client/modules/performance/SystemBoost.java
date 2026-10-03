package dev.xsoz.client.modules.performance;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import dev.xsoz.client.XsozClient;
import dev.xsoz.client.module.Category;
import dev.xsoz.client.module.Module;
import dev.xsoz.client.setting.BoolSetting;
import java.util.Locale;

/**
 * Makes Windows treat the game as the foreground priority while you are playing:
 * <ul>
 *   <li><b>Priority</b> - the game process runs at Above Normal while focused (Normal when not), so
 *   background apps yield the CPU to it first. Never "High" or "Realtime": those starve the audio
 *   and input threads Windows itself needs.</li>
 *   <li><b>High-resolution timer</b> - requests 1 ms timer resolution for this process, so the frame
 *   limiter and Java's sleeps wake on time. Smoother frame pacing at capped FPS.</li>
 * </ul>
 * Touches only this process. No other program's priority is changed, nothing needs admin rights.
 */
public final class SystemBoost extends Module {
    private static final int NORMAL_PRIORITY_CLASS = 0x00000020;
    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000;

    private final BoolSetting priority = add(new BoolSetting("Game priority", "Above Normal CPU priority while the game is focused", true));
    private final BoolSetting timer = add(new BoolSetting("High-res timer", "1 ms timer resolution for smoother frame pacing", true));

    private final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private Boolean lastFocused;
    private boolean timerOn;

    interface Kernel32 extends Library {
        Pointer GetCurrentProcess();

        boolean SetPriorityClass(Pointer process, int priorityClass);
    }

    interface WinMM extends Library {
        int timeBeginPeriod(int period);

        int timeEndPeriod(int period);
    }

    private static Kernel32 k32;
    private static WinMM winmm;

    public SystemBoost() {
        super("System Boost", "Above Normal CPU priority and a 1 ms timer while you play (Windows)", Category.PERFORMANCE, true);
    }

    private boolean load() {
        if (!windows) return false;
        try {
            if (k32 == null) k32 = Native.load("kernel32", Kernel32.class);
            if (winmm == null) winmm = Native.load("winmm", WinMM.class);
            return true;
        } catch (Throwable t) {
            XsozClient.LOG.warn("System Boost unavailable: {}", t.toString());
            return false;
        }
    }

    @Override
    public void onTick() {
        if (!load()) return;
        boolean focused = mc.isWindowFocused();
        if (priority.on() && (lastFocused == null || lastFocused != focused)) {
            k32.SetPriorityClass(k32.GetCurrentProcess(), focused ? ABOVE_NORMAL_PRIORITY_CLASS : NORMAL_PRIORITY_CLASS);
            lastFocused = focused;
        }
        if (!priority.on() && lastFocused != null) {
            k32.SetPriorityClass(k32.GetCurrentProcess(), NORMAL_PRIORITY_CLASS);
            lastFocused = null;
        }
        boolean wantTimer = timer.on() && focused;
        if (wantTimer != timerOn) {
            if (wantTimer) winmm.timeBeginPeriod(1);
            else winmm.timeEndPeriod(1);
            timerOn = wantTimer;
        }
    }

    @Override
    protected void onDisable() {
        if (!load()) return;
        if (lastFocused != null) k32.SetPriorityClass(k32.GetCurrentProcess(), NORMAL_PRIORITY_CLASS);
        lastFocused = null;
        if (timerOn) winmm.timeEndPeriod(1);
        timerOn = false;
    }
}
