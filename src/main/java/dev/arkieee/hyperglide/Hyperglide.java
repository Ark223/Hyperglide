package dev.arkieee.hyperglide;

import com.mojang.logging.LogUtils;
import dev.arkieee.hyperglide.hud.TruePing;
import dev.arkieee.hyperglide.modules.AirPlace;
import dev.arkieee.hyperglide.modules.AutoPilot;
import dev.arkieee.hyperglide.modules.AutoWeb;
import dev.arkieee.hyperglide.modules.BlockFarm;
import dev.arkieee.hyperglide.modules.BounceFly;
import dev.arkieee.hyperglide.modules.ControlFly;
import dev.arkieee.hyperglide.modules.CriticalHits;
import dev.arkieee.hyperglide.modules.DeepTrace;
import dev.arkieee.hyperglide.modules.EasyAccess;
import dev.arkieee.hyperglide.modules.ElytraTweaks;
import dev.arkieee.hyperglide.modules.FastFrame;
import dev.arkieee.hyperglide.modules.FastPortal;
import dev.arkieee.hyperglide.modules.FD3Crafter;
import dev.arkieee.hyperglide.modules.MiningTweaks;
import dev.arkieee.hyperglide.modules.Navigation;
import dev.arkieee.hyperglide.modules.NoSprintFov;
import dev.arkieee.hyperglide.modules.Overview;
import dev.arkieee.hyperglide.modules.RocketBoost;
import dev.arkieee.hyperglide.modules.Scaffolding;
import dev.arkieee.hyperglide.modules.SelfTrapper;
import dev.arkieee.hyperglide.modules.TriggerBot;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Hyperglide extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Hyperglide");
    public static final HudGroup HUD_GROUP = new HudGroup("Hyperglide");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Hyperglide");
        Modules.get().add(new AirPlace());
        Modules.get().add(new AutoPilot());
        Modules.get().add(new AutoWeb());
        Modules.get().add(new BlockFarm());
        Modules.get().add(new BounceFly());
        Modules.get().add(new ControlFly());
        Modules.get().add(new CriticalHits());
        Modules.get().add(new DeepTrace());
        Modules.get().add(new EasyAccess());
        Modules.get().add(new ElytraTweaks());
        Modules.get().add(new FastFrame());
        Modules.get().add(new FastPortal());
        Modules.get().add(new FD3Crafter());
        Modules.get().add(new MiningTweaks());
        Modules.get().add(new Navigation());
        Modules.get().add(new NoSprintFov());
        Modules.get().add(new Overview());
        Modules.get().add(new RocketBoost());
        Modules.get().add(new Scaffolding());
        Modules.get().add(new SelfTrapper());
        Modules.get().add(new TriggerBot());

        Hud.get().register(TruePing.info);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.arkieee.hyperglide";
    }
}
