package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.navigation.Highways;
import hyperglide.navigation.Route;
import hyperglide.navigation.Search;
import hyperglide.navigation.Segment;
import hyperglide.utilities.API;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import java.util.Locale;

public class Navigation extends Module {
    private static final double border = 3750000.0;
    private static final double limit = 5000000.0;

    private static final double margin = 8.0;
    private static final double span = 100000.0;

    private static final float highway = 41.1F;
    private static final float standard = 27.0F;

    private static final Color background = new Color(0, 0, 0, 220);
    private static final Color foreground = new Color(255, 255, 255, 255);

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup display = this.settings.createGroup("Display");
    private final SettingGroup colors = this.settings.createGroup("Colors");

    private final Setting<String> goal = this.general.add(new StringSetting.Builder()
        .name("destination")
        .description("Destination position.")
        .defaultValue("0 0")
        .renderer(Mask.class)
        .onChanged(this::change)
        .build()
    );

    private final Setting<Boolean> convert = this.general.add(new BoolSetting.Builder()
        .name("eight-to-one")
        .description("Uses overworld coordinates as input.")
        .defaultValue(false)
        .onChanged(value -> this.change(this.goal.get()))
        .build()
    );

    private final Setting<Boolean> streamer = this.general.add(new BoolSetting.Builder()
        .name("streamer-mode")
        .description("Censors destination coordinates.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> render = this.display.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the navigation map.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lock = this.display.add(new BoolSetting.Builder()
        .name("lock-map")
        .description("Prevents moving the map window.")
        .defaultValue(false)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Mode> mode = this.display.add(new EnumSetting.Builder<Mode>()
        .name("view-mode")
        .description("Controls how the map view is centered.")
        .defaultValue(Mode.Free)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> xoffset = this.display.add(new IntSetting.Builder()
        .name("x-offset")
        .description("Horizontal map window offset.")
        .defaultValue(0)
        .range(-3840, 3840)
        .noSlider()
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> yoffset = this.display.add(new IntSetting.Builder()
        .name("y-offset")
        .description("Vertical map window offset.")
        .defaultValue(0)
        .range(-2160, 2160)
        .noSlider()
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> thickness = this.display.add(new IntSetting.Builder()
        .name("thickness")
        .description("Thickness of highway and path lines.")
        .defaultValue(1)
        .min(1)
        .sliderMax(3)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Double> info = this.display.add(new DoubleSetting.Builder()
        .name("info-scale")
        .description("Scale of map information text.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderMax(1.5)
        .visible(this.render::get)
        .build()
    );

    private final Setting<Integer> size = this.display.add(new IntSetting.Builder()
        .name("window-size")
        .description("Size of the navigation map in pixels.")
        .defaultValue(150)
        .min(100)
        .sliderMax(300)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> player = this.colors.add(new ColorSetting.Builder()
        .name("player")
        .description("Player position color.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> destination = this.colors.add(new ColorSetting.Builder()
        .name("destination")
        .description("Destination position color.")
        .defaultValue(new SettingColor(0, 192, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> highways = this.colors.add(new ColorSetting.Builder()
        .name("highways")
        .description("Highway network color.")
        .defaultValue(new SettingColor(144, 80, 224, 192))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> path = this.colors.add(new ColorSetting.Builder()
        .name("best-path")
        .description("Calculated path color.")
        .defaultValue(new SettingColor(255, 64, 64, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> outline = this.colors.add(new ColorSetting.Builder()
        .name("map-outline")
        .description("Map outline color.")
        .defaultValue(new SettingColor(200, 200, 200, 128))
        .visible(this.render::get)
        .build()
    );

    private BlockPos point = new BlockPos(0, 0, 0);
    private final Search search = new Search();

    private float mx;
    private float my;
    private int timer;
    private Route route;

    private int drag = -1;
    private boolean middle;
    private double zoom = 1.0;
    private Vec2f offset = Vec2f.ZERO;

    /**
     * Controls how the map view is centered.
     */
    private enum Mode {
        Free,
        Origin,
        Player
    }

    public Navigation() {
        super(Hyperglide.CATEGORY, "navigation",
            "Provides interactive map navigation through the nether."
        );
    }

    /**
     * Initializes the route and map zoom.
     */
    @Override
    public void onActivate() {
        this.offset = Vec2f.ZERO;
        this.zoom = 1.0;

        this.drag = -1;
        this.middle = false;

        this.mx = 0.0F;
        this.my = 0.0F;

        this.timer = 0;
        this.parse(this.goal.get());
        this.calculate();
    }

    //region Event handlers

    /**
     * Recalculates the route at a fixed tick interval.
     *
     * @param event post-tick event
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.nether() || this.mc.player == null) {
            this.route = null;
            return;
        }

        if (++this.timer < 10) return;

        this.timer = 0;
        this.calculate();
    }

    /**
     * Zooms the map while scrolling over it in screen.
     *
     * @param event mouse scroll event
     */
    @EventHandler
    private void onScroll(MouseScrollEvent event) {
        if (!this.render.get() || !this.nether() ||
            !this.interactive() || event.value == 0.0) {
            return;
        }

        Vec2f mouse = this.mouse();
        if (!this.hovered(mouse.x, mouse.y)) return;

        double scale = this.scale();

        this.zoom *= event.value > 0.0 ? 1.2 : 1.0 / 1.2;
        this.zoom = Math.max(0.01, Math.min(64.0, this.zoom));

        if (this.mode.get() == Mode.Free) {
            float size = this.size.get();

            float px = (float) (this.left() + size * 0.5F);
            float py = (float) (this.top() + size * 0.5F);

            Vec2f shift = mouse.add(new Vec2f(-px, -py));
            this.offset = this.bound(this.offset.add(
                shift.multiply((float) (scale - this.scale()))
            ));
        }

        event.cancel();
    }

    /**
     * Renders highway and route information around the map.
     *
     * @param event 2D render event
     */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!this.render.get() || !this.nether() ||
            this.mc.player == null || this.vanilla()) return;

        int left = (int) this.left();
        int top = (int) this.top();

        Vec2f mouse = this.mouse();
        Vec2f current = this.position();
        View view = this.view(current);

        boolean hovered = this.hovered(mouse.x, mouse.y);

        Highways.Road highway = !hovered ? null
            : this.road(mouse, view, left, top);
        if (!hovered && this.route == null) return;

        double gui = this.mc.getWindow().getScaleFactor();
        HudRenderer renderer = HudRenderer.INSTANCE;

        renderer.begin(event.drawContext);

        if (hovered) {
            if (this.streamer.get()) {
                if (highway != null) {
                    this.box(renderer, new String[] {highway.name()},
                        left * gui, top * gui, true, gui
                    );
                }
            } else {
                BlockPos point = this.world(mouse, view, left, top);
                String position = "X: " + point.getX() + " Z: " + point.getZ();

                this.box(renderer, highway == null
                    ? new String[] {position}
                    : new String[] {highway.name(), position},
                    left * gui, top * gui, true, gui
                );
            }
        }

        if (this.route != null) {
            String eta = this.time(this.route.time());
            int length = Math.round(this.route.distance());

            this.box(renderer, new String[] {"ETA: " + eta,
                String.format(Locale.ROOT, "%,d blocks left", length)
            }, left * gui, top * gui, false, gui);
        }

        renderer.end();
    }

    /**
     * Renders the navigation map.
     *
     * @param context draw context
     */
    public void render(DrawContext context) {
        if (!this.isActive() || !this.render.get() || !this.nether()
            || this.mc.player == null || this.vanilla()) return;

        int size = this.size.get();
        int left = (int) this.left();
        int top = (int) this.top();

        this.pan();
        this.click();

        Vec2f current = this.position();
        Vec2f destination = this.target();

        View view = this.view(current);
        Color road = this.highways.get();

        context.fill(left, top, left + size, top + size, background.getPacked());
        context.enableScissor(left + 1, top + 1, left + size - 1, top + size - 1);

        for (Highways.Road highway : Highways.roads()) {
            for (Segment segment : highway.segments()) {
                this.draw(context, segment.start(), segment.end(),
                    view, left, top, road, this.thickness.get()
                );
            }
        }

        if (this.route != null) {
            Color path = this.path.get();

            for (Route.Leg leg : this.route.legs()) {
                this.draw(context, leg.start(), leg.end(), view,
                    left, top, path, this.thickness.get() + 1
                );
            }
        }

        this.mark(context, current, view, left, top, this.player.get());
        this.mark(context, destination, view, left, top, this.destination.get());

        context.disableScissor();
        this.frame(context, left, top);
    }

    //endregion

    //region Public API

    /**
     * Recalculates the route immediately.
     */
    public void refresh() {
        this.calculate();
    }

    /**
     * Returns the current calculated route.
     *
     * @return current route, or null when unavailable
     */
    public Route route() {
        return this.route;
    }

    /**
     * Returns the configured destination block.
     *
     * @return configured destination
     */
    public BlockPos point() {
        return this.point;
    }

    //endregion

    //region State management

    /**
     * Recalculates the fastest route from the current position.
     */
    private void calculate() {
        if (!this.nether() || this.mc.player == null) {
            this.route = null;
            return;
        }

        this.route = this.search.find(
            this.position(), this.target(), highway, standard
        );
    }

    /**
     * Moves the window or pans the free map while a mouse button is held.
     */
    private void pan() {
        if (!this.interactive()) {
            this.drag = -1;
            return;
        }

        int button = -1;

        if (this.mode.get() == Mode.Free &&
            this.pressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            button = GLFW.GLFW_MOUSE_BUTTON_LEFT;

        } else if (!this.lock.get() &&
            this.pressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            button = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        }

        if (button == -1) {
            this.drag = -1;
            return;
        }

        Vec2f mouse = this.mouse();

        if (this.drag == -1) {
            if (!this.hovered(mouse.x, mouse.y)) {
                return;
            }

            this.drag = button;
            this.mx = mouse.x;
            this.my = mouse.y;
            return;
        }

        if (button != this.drag) return;

        float dx = mouse.x - this.mx;
        float dy = mouse.y - this.my;

        if (this.drag == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            this.xoffset.set(this.xoffset.get() + Math.round(dx));
            this.yoffset.set(this.yoffset.get() + Math.round(dy));
        } else {
            double scale = this.scale();
            this.offset = this.bound(this.offset.add(new Vec2f(
                (float) (-dx * scale), (float) (-dy * scale)
            )));
        }

        this.mx = mouse.x;
        this.my = mouse.y;
    }

    /**
     * Sets the destination from a middle clicked map position.
     */
    private void click() {
        boolean pressed = this.pressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        if (!pressed) {
            this.middle = false;
            return;
        }

        if (this.middle || !this.interactive()) return;
        this.middle = true;

        Vec2f mouse = this.mouse();
        if (!this.hovered(mouse.x, mouse.y)) return;

        int left = (int) this.left();
        int top = (int) this.top();

        BlockPos point = this.world(
            mouse, this.view(this.position()), left, top
        );

        this.goal.set(point.getX() + " " + point.getZ());
    }

    //endregion

    //region Map rendering

    /**
     * Calculates the map view around the selected position.
     *
     * @param current current player position
     * @return map view
     */
    private View view(Vec2f current) {
        if (this.mode.get() == Mode.Free) {
            this.offset = this.bound(this.offset);
        }

        Vec2f center = switch (this.mode.get()) {
            case Free -> this.offset;
            case Origin -> Vec2f.ZERO;
            case Player -> current;
        };
        return new View(center, this.scale());
    }

    /**
     * Keeps the visible area inside the navigation limit.
     *
     * @param point requested map center
     * @return bounded map center
     */
    private Vec2f bound(Vec2f point) {
        double half = this.size.get() * this.scale() * 0.5;
        float max = (float) Math.max(0.0, limit - half);

        return new Vec2f(
            Math.max(-max, Math.min(max, point.x)),
            Math.max(-max, Math.min(max, point.y))
        );
    }

    /**
     * Returns the current world blocks per screen pixel.
     *
     * @return world blocks per screen pixel
     */
    private double scale() {
        return span / this.size.get() / this.zoom;
    }

    /**
     * Converts a world position to map screen coordinates.
     *
     * @param point world position
     * @param view current map view
     * @param left map left position
     * @param top map top position
     * @return map screen position
     */
    private Vec2f screen(Vec2f point, View view, double left, double top) {
        float size = this.size.get();

        float px = (float) (left + size * 0.5F);
        float py = (float) (top + size * 0.5F);

        Vec2f screen = point.add(view.center.negate());
        screen = screen.multiply((float) (1.0 / view.scale));
        screen = screen.add(new Vec2f(px, py));

        return screen;
    }

    /**
     * Converts a map screen position to world coordinates.
     *
     * @param point map screen position
     * @param view current map view
     * @param left map left position
     * @param top map top position
     * @return world block position
     */
    private BlockPos world(Vec2f point, View view, double left, double top) {
        float size = this.size.get();

        float px = (float) (left + size * 0.5F);
        float py = (float) (top + size * 0.5F);

        Vec2f world = point.add(new Vec2f(-px, -py));
        world = world.multiply((float) view.scale).add(view.center);

        int scale = this.convert.get() ? 8 : 1;
        return new BlockPos(
            Math.round(world.x) * scale, 0,
            Math.round(world.y) * scale
        );
    }

    /**
     * Draws a world-space line inside the map.
     *
     * @param context draw context
     * @param first line starting position
     * @param second line ending position
     * @param view current map view
     * @param left map left position
     * @param top map top position
     * @param color line color
     * @param width line thickness
     */
    private void draw(DrawContext context, Vec2f first, Vec2f second,
        View view, double left, double top, Color color, int width) {

        double size = this.size.get();

        Vec2f start = this.screen(first, view, left, top);
        Vec2f end = this.screen(second, view, left, top);

        this.line(context, start.x, start.y, end.x, end.y,
            left + 1.0, top + 1.0,
            left + size - 1.0, top + size - 1.0, color, width
        );
    }

    /**
     * Draws a clipped line with configurable thickness.
     *
     * @param context draw context
     * @param x1 line starting X position
     * @param y1 line starting Y position
     * @param x2 line ending X position
     * @param y2 line ending Y position
     * @param left clipping left position
     * @param top clipping top position
     * @param right clipping right position
     * @param bottom clipping bottom position
     * @param color line color
     * @param width line thickness
     */
    private void line(DrawContext context, double x1, double y1, double x2, double y2,
        double left, double top, double right, double bottom, Color color, int width) {

        double[] line = this.clip(x1, y1, x2, y2, left, top, right, bottom);
        if (line == null) return;

        double dx = line[2] - line[0];
        double dy = line[3] - line[1];

        double length = Math.hypot(dx, dy);
        if (length == 0.0) return;

        API.line(context, line[0], line[1], length,
            Math.atan2(dy, dx), width, color.getPacked()
        );
    }

    /**
     * Clips a line to the map rectangle.
     *
     * @param x1 line starting X position
     * @param y1 line starting Y position
     * @param x2 line ending X position
     * @param y2 line ending Y position
     * @param left clipping left position
     * @param top clipping top position
     * @param right clipping right position
     * @param bottom clipping bottom position
     * @return clipped coordinates, or null when outside
     */
    private double[] clip(double x1, double y1, double x2, double y2,
        double left, double top, double right, double bottom) {

        double dx = x2 - x1;
        double dy = y2 - y1;

        double low = 0.0;
        double high = 1.0;

        if (dx == 0.0) {
            if (x1 < left || x1 > right) {
                return null;
            }
        } else {
            double first = (left - x1) / dx;
            double second = (right - x1) / dx;

            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }

            low = Math.max(low, first);
            high = Math.min(high, second);
            if (low > high) return null;
        }

        if (dy == 0.0) {
            if (y1 < top || y1 > bottom) {
                return null;
            }
        } else {
            double first = (top - y1) / dy;
            double second = (bottom - y1) / dy;

            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }

            low = Math.max(low, first);
            high = Math.min(high, second);
            if (low > high) return null;
        }

        return new double[] {
            x1 + low * dx, y1 + low * dy,
            x1 + high * dx, y1 + high * dy
        };
    }

    /**
     * Draws a position marker on the map.
     *
     * @param context draw context
     * @param point marker world position
     * @param view current map view
     * @param left map left position
     * @param top map top position
     * @param color marker color
     */
    private void mark(DrawContext context, Vec2f point,
        View view, double left, double top, Color color) {

        double size = this.size.get();
        Vec2f screen = this.screen(point, view, left, top);

        if (screen.x < left || screen.x > left + size ||
            screen.y < top || screen.y > top + size) return;

        Vec2f ox = new Vec2f(this.thickness.get() + 2, 0.0F);
        Vec2f oy = new Vec2f(0.0F, this.thickness.get() + 2);

        context.fill(
            Math.round(screen.x - ox.x), Math.round(screen.y - oy.y),
            Math.round(screen.x + ox.x), Math.round(screen.y + oy.y),
            color.getPacked()
        );
    }

    /**
     * Draws the one-pixel map outline.
     *
     * @param context draw context
     * @param left map left position
     * @param top map top position
     */
    private void frame(DrawContext context, int left, int top) {
        int size = this.size.get();
        int color = this.outline.get().getPacked();
        API.border(context, left, top, size, size, color);
    }

    //endregion

    //region Info rendering

    /**
     * Draws centered information above or below the map.
     *
     * @param renderer HUD renderer
     * @param lines information lines
     * @param left map left position
     * @param top map top position
     * @param upper true to draw above the map
     * @param gui GUI scale factor
     */
    private void box(HudRenderer renderer, String[] lines,
        double left, double top, boolean upper, double gui) {

        double scale = this.info.get();
        double padding = 3.0 * scale * gui;
        double size = this.size.get() * gui;

        double height = renderer.textHeight(false, scale);
        double box = height * lines.length + padding * 2.0;
        double level = upper ? top - box - 2.0 * gui : top + size + 2.0 * gui;

        renderer.quad(left, level, size, box, background);

        double outline = gui;
        Color color = this.outline.get();

        renderer.quad(left, level, size, outline, color);
        renderer.quad(left, level + box - outline, size, outline, color);
        renderer.quad(left, level, outline, box, color);
        renderer.quad(left + size - outline, level, outline, box, color);

        double text = level + padding;

        for (String line : lines) {
            double width = renderer.textWidth(line, false, scale);
            double shift = left + (size - width) * 0.5;

            renderer.text(line, shift, text, foreground, false, scale);
            text += height;
        }
    }

    /**
     * Returns the highway currently hovered on the map.
     *
     * @param mouse mouse position
     * @param view current map view
     * @param left map left position
     * @param top map top position
     * @return hovered highway, or null when none
     */
    private Highways.Road road(Vec2f mouse, View view, double left, double top) {
        if (this.mc.currentScreen == null) return null;

        Highways.Road result = null;
        float closest = Math.max(4.0F, this.thickness.get() + 2.0F);

        for (Highways.Road road : Highways.roads()) {
            for (Segment segment : road.segments()) {
                Vec2f first = this.screen(segment.start(), view, left, top);
                Vec2f second = this.screen(segment.end(), view, left, top);

                float distance = this.distance(mouse, first, second);
                if (distance > closest) continue;

                closest = distance;
                result = road;
            }
        }

        return result;
    }

    /**
     * Formats seconds using compact hour, minute and second units.
     *
     * @param seconds seconds to format
     * @return formatted time
     */
    private String time(float seconds) {
        int time = Math.max(0, Math.round(seconds));

        int hours = time / 3600;
        int minutes = time % 3600 / 60;
        int remaining = time % 60;

        String value = "";
        if (hours > 0) value += hours + "h ";
        if (minutes > 0 || hours > 0) {
            value += minutes + "m ";
        }

        return value + remaining + "s";
    }

    /**
     * Calculates screen distance from a point to a segment.
     *
     * @param point screen point
     * @param first segment starting position
     * @param second segment ending position
     * @return shortest screen distance
     */
    private float distance(Vec2f point, Vec2f first, Vec2f second) {
        Vec2f vector = second.add(first.negate());
        float length = vector.lengthSquared();

        if (length == 0.0F) {
            return (float) Math.sqrt(point.distanceSquared(first));
        }

        Vec2f offset = point.add(first.negate());
        float ratio = Math.max(0.0F,
            Math.min(1.0F, offset.dot(vector) / length)
        );

        Vec2f closest = first.add(vector.multiply(ratio));
        return (float) Math.sqrt(point.distanceSquared(closest));
    }

    //endregion

    //region Player and world

    /**
     * Returns the current player X/Z position.
     *
     * @return player X/Z position
     */
    private Vec2f position() {
        return new Vec2f(
            (float) this.mc.player.getX(),
            (float) this.mc.player.getZ()
        );
    }

    /**
     * Returns the configured destination.
     *
     * @return configured destination
     */
    private Vec2f target() {
        return new Vec2f(this.point.getX(), this.point.getZ());
    }

    /**
     * Checks whether the player is in the nether.
     *
     * @return true when the player is in the nether
     */
    private boolean nether() {
        return this.mc.world != null && World.NETHER.equals(
            this.mc.world.getRegistryKey()
        );
    }

    /**
     * Checks whether the map can receive mouse interaction.
     *
     * @return true when chat or a Meteor GUI screen is open
     */
    private boolean interactive() {
        if (this.mc.currentScreen == null) return false;
        if (this.mc.currentScreen instanceof ChatScreen) {
            return true;
        }

        String name = this.mc.currentScreen.getClass().getName();
        return name.startsWith("meteordevelopment.meteorclient.gui.");
    }

    /**
     * Checks whether a vanilla screen other than chat is open.
     *
     * @return true when a vanilla screen other than chat is open
     */
    private boolean vanilla() {
        if (this.mc.currentScreen == null ||
            this.mc.currentScreen instanceof ChatScreen) {
            return false;
        }

        String name = this.mc.currentScreen.getClass().getName();
        return name.startsWith("net.minecraft.client.gui.screen.");
    }

    //endregion

    //region Map interaction

    /**
     * Returns the map left position.
     *
     * @return map left position
     */
    private double left() {
        int width = this.mc.getWindow().getScaledWidth();
        return width - this.size.get() - margin + this.xoffset.get();
    }

    /**
     * Returns the map top position.
     *
     * @return map top position
     */
    private double top() {
        return margin + this.yoffset.get();
    }

    /**
     * Checks whether the mouse is inside the map.
     *
     * @param x mouse X position
     * @param y mouse Y position
     * @return true when the mouse is inside the map
     */
    private boolean hovered(double x, double y) {
        double size = this.size.get();

        double left = this.left();
        double top = this.top();

        return x >= left && x <= left + size
            && y >= top && y <= top + size;
    }

    /**
     * Checks whether a mouse button is held.
     *
     * @param button mouse button
     * @return true when the button is held
     */
    private boolean pressed(int button) {
        return GLFW.glfwGetMouseButton(
            this.mc.getWindow().getHandle(),
        button) == GLFW.GLFW_PRESS;
    }

    /**
     * Returns the mouse position in scaled screen coordinates.
     *
     * @return scaled mouse position
     */
    private Vec2f mouse() {
        int width = this.mc.getWindow().getWidth();
        int height = this.mc.getWindow().getHeight();

        int swidth = this.mc.getWindow().getScaledWidth();
        int sheight = this.mc.getWindow().getScaledHeight();

        return new Vec2f(
            (float) (this.mc.mouse.getX() * swidth / width),
            (float) (this.mc.mouse.getY() * sheight / height)
        );
    }

    //endregion

    //region Destination control

    /**
     * Applies a destination change and restarts Auto Pilot when active.
     *
     * @param value typed destination value
     */
    private void change(String value) {
        BlockPos previous = this.point;

        this.parse(value);
        if (this.point.equals(previous)) return;

        AutoPilot pilot = Modules.get().get(AutoPilot.class);
        if (pilot == null || !pilot.isActive()) return;

        pilot.toggle();
        pilot.toggle();
    }

    /**
     * Parses a destination in either 2D or 3D format.
     *
     * @param value typed destination value
     */
    private void parse(String value) {
        String clean = value.trim();
        if (clean.isEmpty()) return;

        String[] parts = clean.split("\\s+");
        if (parts.length != 2 && parts.length != 3) {
            return;
        }

        try {
            int px = Integer.parseInt(parts[0]);
            int py = parts.length == 3 ? Integer.parseInt(parts[1]) : 0;
            int pz = Integer.parseInt(parts[parts.length - 1]);

            if (this.convert.get()) {
                px = Math.floorDiv(px, 8);
                pz = Math.floorDiv(pz, 8);
            }

            if (px < -border || px > border ||
                pz < -border || pz > border) {
                return;
            }

            this.point = new BlockPos(px, py, pz);
            this.calculate();
        } catch (NumberFormatException ignored) {}
    }

    //endregion

    //region Data structures

    /**
     * Renders destination input with optional coordinate censorship.
     */
    public static class Mask implements WTextBox.Renderer {
        /**
         * Renders the destination text or its character mask.
         *
         * @param renderer GUI renderer
         * @param x text X position
         * @param y text Y position
         * @param text actual destination text
         * @param color text color
         */
        @Override
        public void render(GuiRenderer renderer,
            double px, double py, String text, Color color) {

            Navigation module = Modules.get().get(Navigation.class);
            Boolean streamer = module != null && module.streamer.get();
            String value = streamer ? "*".repeat(text.length()) : text;

            renderer.text(value, px, py, color, false);
        }
    }

    /**
     * Represents the current map center and world scale.
     *
     * @param center map center
     * @param scale world blocks per screen pixel
     */
    private record View(Vec2f center, double scale) {}

    //endregion
}
