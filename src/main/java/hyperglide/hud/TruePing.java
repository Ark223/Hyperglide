package hyperglide.hud;

import hyperglide.Hyperglide;
import hyperglide.mixin.PlayerListAccessor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.elements.TextHud;
import net.minecraft.text.Text;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TruePing extends TextHud {
    private static final Pattern pattern = Pattern.compile(
        "\\b(\\d{1,4})\\s+ping\\b", Pattern.CASE_INSENSITIVE
    );

    public static final HudElementInfo<TextHud> info = new HudElementInfo<>(
        Hyperglide.HUD_GROUP, "true-ping",
        "Displays the ping reported by the 2b2t server.",
        TruePing::new
    );

    private int ping = Integer.MIN_VALUE;

    public TruePing() {
        super(info);
        this.update(0);
    }

    /**
     * Updates the ping before Meteor refreshes the text element.
     *
     * @param renderer HUD renderer
     */
    @Override
    public void tick(HudRenderer renderer) {
        this.update(this.parse(this.footer()));
        super.tick(renderer);
    }

    /**
     * Returns the current player list footer.
     *
     * @return player list footer, or null when unavailable
     */
    private Text footer() {
        if (MeteorClient.mc.inGameHud == null) {
            return null;
        }

        return ((PlayerListAccessor)
            MeteorClient.mc.inGameHud.getPlayerListHud()
        ).hyperglide$getFooter();
    }

    /**
     * Reads the server ping from the player list footer.
     *
     * @param footer player list footer
     * @return parsed ping, or -1 when unavailable
     */
    private int parse(Text footer) {
        if (footer == null) return 0;

        Matcher matcher = pattern.matcher(footer.getString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * Updates the Starscript expression when the ping changes.
     *
     * @param ping parsed ping value
     */
    private void update(int ping) {
        if (this.ping == ping) return;
        this.ping = ping;

        String value = ping > 0 ? Integer.toString(ping) : "ping";
        this.text.set("Ping: #1{" + value + "}");
    }
}
