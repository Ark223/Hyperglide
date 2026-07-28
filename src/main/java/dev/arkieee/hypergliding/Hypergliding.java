package dev.arkieee.hypergliding;

import com.mojang.logging.LogUtils;
import dev.arkieee.hypergliding.modules.AirPlace;
import dev.arkieee.hypergliding.modules.BounceFly;
import dev.arkieee.hypergliding.modules.FastPortal;
import dev.arkieee.hypergliding.modules.FD3Crafter;
import dev.arkieee.hypergliding.modules.MiningTweaks;
import dev.arkieee.hypergliding.modules.Scaffolding;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Hypergliding extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Hyper Gliding");
    public static final HudGroup HUD_GROUP = new HudGroup("Hyper Gliding");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Hyper Gliding");
        Modules.get().add(new AirPlace());
        Modules.get().add(new BounceFly());
        Modules.get().add(new FastPortal());
        Modules.get().add(new FD3Crafter());
        Modules.get().add(new MiningTweaks());
        Modules.get().add(new Scaffolding());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.arkieee.hypergliding";
    }
}
