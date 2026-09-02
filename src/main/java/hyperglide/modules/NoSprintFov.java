package hyperglide.modules;

import hyperglide.Hyperglide;
import meteordevelopment.meteorclient.systems.modules.Module;

public class NoSprintFov extends Module {
    public NoSprintFov() {
        super(Hyperglide.CATEGORY, "no-sprint-fov",
            "Removes vanilla zoom effects caused by sprinting."
        );
    }
}
