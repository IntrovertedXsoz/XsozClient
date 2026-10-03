package dev.xsoz.client;

import dev.xsoz.client.config.ConfigManager;
import dev.xsoz.client.event.GameEvents;

import dev.xsoz.client.module.ModuleManager;
import dev.xsoz.client.modules.client.Interface;
import dev.xsoz.client.modules.combat.CrystalOptimizer;
import dev.xsoz.client.modules.combat.LowHealthWarning;
import dev.xsoz.client.modules.hud.HudElements;
import dev.xsoz.client.modules.movement.SnapTap;
import dev.xsoz.client.modules.movement.ToggleSprint;
import dev.xsoz.client.modules.performance.BackgroundThrottle;
import dev.xsoz.client.modules.performance.FpsBoost;
import dev.xsoz.client.modules.performance.SystemBoost;
import dev.xsoz.client.modules.visuals.Crosshair;
import dev.xsoz.client.modules.visuals.Fullbright;
import dev.xsoz.client.modules.visuals.LowFire;
import dev.xsoz.client.modules.visuals.NoHurtCam;
import dev.xsoz.client.modules.visuals.ViewModel;
import dev.xsoz.client.modules.visuals.Zoom;
import dev.xsoz.client.trainer.crystal.CrystalTrainerModule;
import dev.xsoz.client.trainer.crystal.TrainerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Xsoz Client - entry point. Everything is registered here, once. */
public final class XsozClient implements ClientModInitializer {
    public static final Logger LOG = XsozLog.LOG;
    public static String VERSION = "0.2.0";

    public static KeyBinding menuKey;
    public static KeyBinding trainerKey;
    public static KeyBinding zoomKey;

    @Override
    public void onInitializeClient() {
        VERSION = FabricLoader.getInstance().getModContainer("xsozclient")
                .map(c -> c.getMetadata().getVersion().getFriendlyString().split("\\+")[0]).orElse(VERSION);

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("xsoz", "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.clickgui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        trainerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.trainer", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.xsoz.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, category));

        registerModules();
        dev.xsoz.client.modules.combat.ComboBinds.INSTANCE.registerKeys();
        dev.xsoz.client.training.TrainingManager.register();
        ConfigManager.load();

        // ---- ticks
        ClientTickEvents.START_CLIENT_TICK.register(mc -> ModuleManager.preTick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) {
                    mc.setScreen(dev.xsoz.client.training.TrainingManager.running()
                            ? new TrainerScreen(null, TrainerScreen.Tab.TRAINING)
                            : new dev.xsoz.client.gui.ModsScreen(null));
                }
            }
            while (trainerKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new TrainerScreen(null));
            }
            ModuleManager.tick(mc);
            ConfigManager.tick();
        });

        // ---- game events
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() && player == MinecraftClient.getInstance().player) GameEvents.attack(entity);
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() && player == MinecraftClient.getInstance().player) GameEvents.useBlock(hand, hit, player.getStackInHand(hand));
            return ActionResult.PASS;
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> GameEvents.entityLoad(entity));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> GameEvents.entityUnload(entity));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(GameEvents::worldLeave));

        // ---- HUD
        HudElementRegistry.addLast(Identifier.of("xsoz", "hud"), (ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            HudElements.Cps.sample(mc.getWindow().getHandle());
            LowHealthWarning lhw = ModuleManager.get(LowHealthWarning.class);
            if (lhw != null) lhw.render(ctx);
            ModuleManager.renderHud(ctx);
            CrystalTrainerModule trainer = ModuleManager.get(CrystalTrainerModule.class);
            if (trainer != null) trainer.renderOverlay(ctx);
            dev.xsoz.client.training.TrainingManager.render(ctx);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (ctx, tick) -> {
            if (Crosshair.INSTANCE == null || !Crosshair.INSTANCE.render(ctx)) vanilla.render(ctx, tick);
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> ModuleManager.clientReady());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> ConfigManager.save());
        LOG.info("Xsoz Client {} ready: {} modules.", VERSION, ModuleManager.all().size());
    }

    static void registerModules() {
        // trainer first: it is the point of the client
        ModuleManager.register(new CrystalTrainerModule());
        // combat
        ModuleManager.register(new CrystalOptimizer());
        ModuleManager.register(new dev.xsoz.client.modules.combat.ComboBinds());
        ModuleManager.register(new LowHealthWarning());
        // movement
        ModuleManager.register(new SnapTap());
        ModuleManager.register(new ToggleSprint());
        // visuals
        ModuleManager.register(new Crosshair());
        ModuleManager.register(new Zoom());
        ModuleManager.register(new Fullbright());
        ModuleManager.register(new LowFire());
        ModuleManager.register(new dev.xsoz.client.modules.visuals.Particles());
        ModuleManager.register(new NoHurtCam());
        ModuleManager.register(new ViewModel());
        // HUD (+ the combat read-outs)
        HudElements.registerAll();
        // performance
        ModuleManager.register(new FpsBoost());
        ModuleManager.register(new BackgroundThrottle());
        ModuleManager.register(new SystemBoost());
        // client
        ModuleManager.register(new Interface());
    }
}
