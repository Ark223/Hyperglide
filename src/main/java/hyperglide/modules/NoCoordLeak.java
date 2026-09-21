package hyperglide.modules;

import hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NoCoordLeak extends Module {
    private static final Pattern pattern = Pattern.compile(
        "[+-]?\\d+(?:\\.\\d+)*"
    );

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Integer> digits = this.general.add(new IntSetting.Builder()
        .name("coord-digits")
        .description("Maximum digits allowed in one coordinate.")
        .defaultValue(3)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> total = this.general.add(new IntSetting.Builder()
        .name("message-digits")
        .description("Maximum total digits allowed in a message.")
        .defaultValue(8)
        .min(4)
        .sliderMax(10)
        .build()
    );

    public NoCoordLeak() {
        super(Hyperglide.CATEGORY, "no-coord-leak",
            "Prevents sending coordinates in chat by accident."
        );
    }

    /**
     * Blocks outgoing messages that appear to contain coordinates.
     *
     * @param event outgoing chat message event
     */
    @EventHandler
    private void onMessage(SendMessageEvent event) {
        if (!this.coords(event.message)) return;

        event.cancel();
        this.error("Blocked possible coordinates!");
    }

    /**
     * Checks whether a message appears to contain coordinates.
     *
     * @param message outgoing message
     * @return true when possible coordinates are present
     */
    private boolean coords(String message) {
        Matcher matcher = pattern.matcher(message);
        boolean large = false;

        int numbers = 0;
        int total = 0;

        while (matcher.find()) {
            String value = matcher.group();

            int dot = value.indexOf('.');
            char sign = value.charAt(0);

            int start = sign == '+' || sign == '-' ? 1 : 0;
            int end = dot >= 0 ? dot : value.length();
            int length = end - start;

            if (length > this.digits.get()) {
                large = true;
            }

            total += length;
            numbers++;
        }

        boolean exceeds = total > this.total.get();
        return numbers >= 2 && (large || exceeds);
    }
}
