package hyperglide.hud;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.elements.TextHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class AvgSpeed extends TextHud {
    private static final double ticks = 20.0;

    public static final HudElementInfo<TextHud> info = new HudElementInfo<>(
        Hyperglide.HUD_GROUP, "avg-speed",
        "Displays the average horizontal movement speed.",
        AvgSpeed::new
    );

    private final SettingGroup metrics = this.settings.createGroup("Metrics");

    private final Setting<Integer> time = this.metrics.add(new IntSetting.Builder()
        .name("total-time")
        .description("How many seconds are used for the test.")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> precision = this.metrics.add(new IntSetting.Builder()
        .name("precision")
        .description("How many decimal places are displayed.")
        .defaultValue(1)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Unit> unit = this.metrics.add(new EnumSetting.Builder<Unit>()
        .name("speed-unit")
        .description("The unit used to display average speed.")
        .defaultValue(Unit.Bps)
        .build()
    );

    private final Deque<Double> speeds = new ArrayDeque<>();

    private static ClientPlayerEntity player;
    private static Vec3d previous;
    private static double speed;

    private double total;
    private String value = "";

    /**
     * Defines the available units for displaying average speed.
     */
    private enum Unit {
        Bps,
        Kmh
    }

    public AvgSpeed() {
        super(info);
        this.update(Double.NaN);
    }

    /**
     * Refreshes the text using the latest speed samples.
     *
     * @param renderer HUD renderer
     */
    @Override
    public void tick(HudRenderer renderer) {
        this.update(this.average());
        super.tick(renderer);
    }

    //region Public interface

    /**
     * Tracks the player's movement speed each game tick.
     */
    public static void track() {
        ClientPlayerEntity entity = MeteorClient.mc.player;
        if (entity == null || MeteorClient.mc.world == null) {
            reset();
            return;
        }

        Vec3d current = API.pos(entity);

        if (player == entity && previous != null) {
            double dx = current.x - previous.x;
            double dz = current.z - previous.z;
            speed = Math.hypot(dx, dz) * ticks;
        } else {
            speed = 0.0;
        }

        player = entity;
        previous = current;
    }

    /**
     * Returns the movement speed in blocks per second.
     *
     * @return horizontal movement speed
     */
    public static double speed() {
        return speed;
    }

    /**
     * Clears movement tracking when leaving a world.
     */
    public static void reset() {
        player = null;
        previous = null;
        speed = 0.0;
    }

    /**
     * Clears the stored speed samples.
     */
    private void clear() {
        this.speeds.clear();
        this.total = 0.0;
    }

    //endregion

    //region Internal tracking

    /**
     * Returns the rolling average of the horizontal speed.
     *
     * @return average speed in blocks per second
     */
    private double average() {
        if (MeteorClient.mc.player == null) {
            this.clear();
            return Double.NaN;
        }

        double speed = speed();
        this.speeds.addLast(speed);
        this.total += speed;

        int limit = this.time.get() * 20;
        while (this.speeds.size() > limit) {
            this.total -= this.speeds.removeFirst();
        }

        return this.total / this.speeds.size();
    }

    /**
     * Updates the Starscript expression when the speed changes.
     *
     * @param speed average speed in blocks per second
     */
    private void update(double speed) {
        boolean kmh = this.unit.get() == Unit.Kmh;
        double shown = kmh ? speed * 3.6 : speed;

        String suffix = kmh ? "km/h" : "bps";
        String format = "%." + this.precision.get() + "f";

        String number = !Double.isFinite(shown) ? "speed"
            : String.format(Locale.ROOT, format, shown);

        String value = number + " " + suffix;
        if (this.value.equals(value)) return;

        this.text.set("Avg Speed: #1{" + number + "} " + suffix);
        this.value = value;
    }

    //endregion
}
