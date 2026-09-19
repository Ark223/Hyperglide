package hyperglide;

import com.mojang.logging.LogUtils;

import hyperglide.hud.AvgSpeed;
import hyperglide.hud.TruePing;
import hyperglide.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import java.util.function.Supplier;

public class Hyperglide extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Hyperglide");
    public static final HudGroup HUD_GROUP = new HudGroup("Hyperglide");

    private static final Entry[] MODULES = {
        new Entry(AirPlace::new),
        new Entry(AutoPilot::new),
        new Entry(AutoWeb::new),
        new Entry(BlockFarm::new),
        new Entry(BounceFly::new),
        new Entry(ControlFly::new, "bephax"),
        new Entry(DeepTrace::new),
        new Entry(EasyAccess::new),
        new Entry(ElytraTweaks::new),
        new Entry(FastFrame::new),
        new Entry(FastPortal::new),
        new Entry(FD3Crafter::new),
        new Entry(ForceLog::new),
        new Entry(MiningTweaks::new),
        new Entry(Navigation::new),
        new Entry(NoCoordLeak::new),
        new Entry(NoSprintFov::new),
        new Entry(Overview::new),
        new Entry(RaidStarter::new),
        new Entry(RocketBoost::new),
        new Entry(Scaffolding::new),
        new Entry(SelfTrapper::new),
        new Entry(TriggerBot::new)
    };

    /**
     * Initializes modules and HUD elements.
     */
    @Override
    public void onInitialize() {
        LOG.info("Initializing Hyperglide");

        Hud.get().register(AvgSpeed.info);
        Hud.get().register(TruePing.info);

        for (Entry entry : MODULES) {
            this.add(entry);
        }
    }

    /**
     * Registers the main module category.
     */
    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    /**
     * Registers a module when no conflicting addon is loaded.
     *
     * @param entry module entry
     */
    private void add(Entry entry) {
        if (this.blocked(entry)) return;
        Modules.get().add(entry.make().get());
    }

    /**
     * Checks whether an entry conflicts with a loaded addon.
     *
     * @param entry module entry
     * @return true when a conflicting addon is loaded
     */
    private boolean blocked(Entry entry) {
        for (String id : entry.mods()) {
            if (this.loaded(id)) return true;
        }
        return false;
    }

    /**
     * Checks whether a specific Fabric mod is loaded.
     *
     * @param id mod identifier
     * @return true when the mod is loaded
     */
    private boolean loaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /**
     * Stores a module factory and conflicting addons.
     *
     * @param make module factory
     * @param mods conflicting addon identifiers
     */
    private record Entry(Supplier<Module> make, String... mods) {}

    /**
     * Returns the root package used by addon.
     *
     * @return addon package name
     */
    @Override
    public String getPackage() {
        return "hyperglide";
    }
}
