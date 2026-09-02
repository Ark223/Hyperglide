package hyperglide;

import com.mojang.logging.LogUtils;
import hyperglide.hud.TruePing;
import hyperglide.modules.AirPlace;
import hyperglide.modules.AutoPilot;
import hyperglide.modules.AutoWeb;
import hyperglide.modules.BlockFarm;
import hyperglide.modules.BounceFly;
import hyperglide.modules.ControlFly;
import hyperglide.modules.CriticalHits;
import hyperglide.modules.DeepTrace;
import hyperglide.modules.EasyAccess;
import hyperglide.modules.ElytraTweaks;
import hyperglide.modules.FastFrame;
import hyperglide.modules.FastPortal;
import hyperglide.modules.FD3Crafter;
import hyperglide.modules.MiningTweaks;
import hyperglide.modules.Navigation;
import hyperglide.modules.NoSprintFov;
import hyperglide.modules.Overview;
import hyperglide.modules.RocketBoost;
import hyperglide.modules.Scaffolding;
import hyperglide.modules.SelfTrapper;
import hyperglide.modules.TriggerBot;
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
        Hud.get().register(TruePing.info);

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
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "hyperglide";
    }
}
