package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import java.util.List;

public class ForceLog extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<String> prefix = this.general.add(new StringSetting.Builder()
        .name("prefix")
        .description("Prefix used to trigger a forced logout.")
        .defaultValue("!logout")
        .build()
    );

    private final Setting<List<String>> whitelist = this.general.add(new StringListSetting.Builder()
        .name("whitelist")
        .description("Player names allowed to trigger Force Log.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> reconnect = this.general.add(new BoolSetting.Builder()
        .name("reconnect")
        .description("Reconnects to the server after disconnecting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> delay = this.general.add(new DoubleSetting.Builder()
        .name("join-delay")
        .description("Seconds to wait before each reconnect attempt.")
        .defaultValue(10.0)
        .min(1.0)
        .sliderMax(30.0)
        .decimalPlaces(1)
        .visible(this.reconnect::get)
        .build()
    );

    private ServerAddress address;
    private ServerInfo server;

    private boolean retry;
    private boolean joining;
    private boolean logging;

    private long next;

    public ForceLog() {
        super(Hyperglide.CATEGORY, "force-log",
            "Disconnects when an allowed player sends the prefix."
        );

        this.runInMainMenu = true;
    }

    /**
     * Clears reconnect state when the module starts.
     */
    @Override
    public void onActivate() {
        this.clear();
    }

    /**
     * Stops pending reconnect attempts.
     */
    @Override
    public void onDeactivate() {
        this.clear();
    }

    //region Event handlers

    /**
     * Checks incoming player chat for the configured trigger.
     *
     * @param event incoming packet event
     */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (this.logging || this.mc.getNetworkHandler() == null ||
            !(event.packet instanceof GameMessageS2CPacket packet) ||
            packet.overlay()) {
            return;
        }

        Whisper whisper = this.whisper(packet.content().getString().trim());
        if (whisper == null || !this.match(whisper.message())) return;

        ClientPlayNetworkHandler handler = this.mc.getNetworkHandler();
        if (handler == null) return;

        PlayerListEntry entry = handler.getPlayerListEntry(whisper.name());
        if (entry == null) return;

        String name = API.name(entry.getProfile());

        boolean matches = name.equalsIgnoreCase(whisper.name());
        if (!matches || !this.allowed(name)) return;

        this.log(name);
    }

    /**
     * Reconnects after the configured delay until the server is joined.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.retry) return;

        if (this.mc.currentScreen instanceof MultiplayerScreen ||
            this.mc.currentScreen instanceof TitleScreen) {
            this.clear();
            return;
        }

        if (!this.reconnect.get() ||
            this.address == null || this.server == null) {
            return;
        }

        if (this.mc.currentScreen instanceof ConnectScreen) {
            this.joining = true;
            return;
        }

        if (this.joining) {
            this.joining = false;
            this.schedule();
            return;
        }

        if (System.currentTimeMillis() < this.next ||
            this.mc.currentScreen == null) {
            return;
        }

        this.connect();
    }

    /**
     * Ends reconnect mode after joining a server.
     *
     * @param event game joined event
     */
    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        this.retry = false;
        this.joining = false;
        this.logging = false;
        this.next = 0L;
    }

    //endregion

    //region Trigger management

    /**
     * Extracts the sender and message from a private whisper.
     *
     * @param text displayed whisper text
     * @return parsed whisper, or null when the message is not private
     */
    private Whisper whisper(String text) {
        String marker = " whispers: ";

        int index = text.indexOf(marker);
        if (index <= 0) return null;

        String name = text.substring(0, index).trim();
        String message = text.substring(index + marker.length()).trim();

        if (!name.matches("[A-Za-z0-9_]{1,16}") || message.isEmpty()) {
            return null;
        }

        return new Whisper(name, message);
    }

    /**
     * Checks whether the message matches the configured prefix.
     *
     * @param message raw player chat message
     * @return true when the trigger matches
     */
    private boolean match(String message) {
        String prefix = this.prefix.get().trim();
        if (prefix.isEmpty()) return false;

        if (message.equalsIgnoreCase(prefix)) return true;
        if (message.length() <= prefix.length()) return false;

        return message.regionMatches(true, 0, prefix, 0, prefix.length())
            && Character.isWhitespace(message.charAt(prefix.length()));
    }

    /**
     * Checks whether a player is allowed to trigger Force Log.
     *
     * @param name player name
     * @return true when the player is allowed
     */
    private boolean allowed(String name) {
        for (String player : this.whitelist.get()) {
            if (player.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stores the server address and disconnects the client.
     *
     * @param name player that triggered the logout
     */
    private void log(String name) {
        ServerInfo server = this.mc.getCurrentServerEntry();
        if (server == null || this.mc.getNetworkHandler() == null) {
            return;
        }

        this.server = server;
        this.address = ServerAddress.parse(server.address);

        this.retry = true;
        this.joining = false;
        this.logging = true;

        this.schedule();

        String suffix = name + ".";

        if (this.reconnect.get()) {
            suffix += "\n\nReconnecting in ";
            suffix += this.delay.get() + " seconds.";
        }

        this.mc.getNetworkHandler().getConnection().disconnect(
            Text.literal("[ForceLog] Triggered by " + suffix)
        );
    }

    //endregion

    //region Server reconnection

    /**
     * Starts another connection attempt.
     */
    private void connect() {
        this.joining = true;

        ConnectScreen.connect(
            this.mc.currentScreen, this.mc,
            this.address, this.server, false, null
        );
    }

    /**
     * Schedules the next connection attempt.
     */
    private void schedule() {
        this.next = System.currentTimeMillis() +
            (long) (this.delay.get() * 1000.0);
    }

    /**
     * Clears all runtime state.
     */
    private void clear() {
        this.address = null;
        this.server = null;

        this.retry = false;
        this.joining = false;
        this.logging = false;

        this.next = 0L;
    }

    //region Data structures

    /**
     * Stores a parsed private whisper.
     *
     * @param name sender name
     * @param message whisper message
     */
    private record Whisper(String name, String message) {}

    //endregion
}
