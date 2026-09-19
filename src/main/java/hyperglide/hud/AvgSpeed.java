package hyperglide.hud;

import hyperglide.Hyperglide;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.elements.TextHud;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class AvgSpeed extends TextHud {
    public static final HudElementInfo<TextHud> info = new HudElementInfo<>(
        Hyperglide.HUD_GROUP, "avg-speed",
        "Displays the average horizontal movement speed.",
        AvgSpeed::new
    );

    private final SettingGroup metrics = this.settings.createGroup("Metrics");

    private final Setting<Integer> time = this.metrics.add(new IntSetting.Builder()
        .name("total-time")
        .description("How many seconds are used for the test.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Unit> unit = this.metrics.add(new EnumSetting.Builder<Unit>()
        .name("speed-unit")
        .description("The unit used to display average speed.")
        .defaultValue(Unit.Bps)
        .build()
    );

    private final Deque<Double> speeds = new ArrayDeque<>();

    private String value = "";
    private double total;

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
     * Samples speed before Meteor refreshes the text element.
     *
     * @param renderer HUD renderer
     */
    @Override
    public void tick(HudRenderer renderer) {
        this.update(this.average());
        super.tick(renderer);
    }

    /**
     * Clears the stored speed samples.
     */
    private void clear() {
        this.speeds.clear();
        this.total = 0;
    }

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

        Vec3d vel = MeteorClient.mc.player.getVelocity();
        double speed = vel.horizontalLength() * 20.0;

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

        String number = !Double.isFinite(shown) ? "speed"
            : String.format(Locale.ROOT, "%.3f", shown);

        String value = number + " " + suffix;
        if (this.value.equals(value)) return;

        this.text.set("Avg Speed: #1{" + number + "} " + suffix);
        this.value = value;
    }
}
