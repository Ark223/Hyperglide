package hyperglide.utilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Handles common 3D boxes, lines and render settings.
 */
public final class Render {
    private final Setting<Boolean> render;
    private final Setting<ShapeMode> shape;
    private final Setting<SettingColor> side;
    private final Setting<SettingColor> line;

    /**
     * Creates the standard render settings.
     *
     * @param group visual setting group
     * @param render render-toggle description
     * @param shape shape-setting description
     * @param side side-color description
     * @param line line-color description
     * @param fill default fill color
     * @param stroke default outline color
     */
    public Render(SettingGroup group, String render, String shape,
        String side, String line, SettingColor fill, SettingColor stroke) {
        this(group, render, shape, side, line, ShapeMode.Both, fill, stroke);
    }

    /**
     * Creates render settings with a custom default shape.
     *
     * @param group visual setting group
     * @param render render-toggle description
     * @param shape shape-setting description
     * @param side side-color description
     * @param line line-color description
     * @param mode default shape mode
     * @param fill default fill color
     * @param stroke default outline color
     */
    public Render(SettingGroup group, String render, String shape, String side,
        String line, ShapeMode mode, SettingColor fill, SettingColor stroke) {

        this.render = group.add(new BoolSetting.Builder()
            .name("render")
            .description(render)
            .defaultValue(true)
            .build()
        );

        this.shape = group.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape")
            .description(shape)
            .defaultValue(mode)
            .visible(this.render::get)
            .build()
        );

        this.side = group.add(new ColorSetting.Builder()
            .name("side-color")
            .description(side)
            .defaultValue(fill)
            .visible(this.render::get)
            .build()
        );

        this.line = group.add(new ColorSetting.Builder()
            .name("line-color")
            .description(line)
            .defaultValue(stroke)
            .visible(this.render::get)
            .build()
        );
    }

    /**
     * Checks whether rendering is enabled.
     *
     * @return true when rendering should run
     */
    public boolean enabled() {
        return this.render.get();
    }

    /**
     * Renders a box around a block position.
     *
     * @param event active 3D render event
     * @param pos block position
     */
    public void box(Render3DEvent event, BlockPos pos) {
        box(event, pos, this.side.get(), this.line.get(), this.shape.get());
    }

    /**
     * Renders a box using world coordinates.
     *
     * @param event active 3D render event
     * @param box world-space box
     */
    public void box(Render3DEvent event, Box box) {
        box(event, box, this.side.get(), this.line.get(), this.shape.get());
    }

    /**
     * Renders a line using the configured line color.
     *
     * @param event active 3D render event
     * @param start line start
     * @param end line end
     */
    public void line(Render3DEvent event, Vec3d start, Vec3d end) {
        line(event, start, end, this.line.get());
    }

    /**
     * Renders a box around a block position.
     *
     * @param event active 3D render event
     * @param pos block position
     * @param side fill color
     * @param line outline color
     * @param shape box shape mode
     */
    public static void box(Render3DEvent event, BlockPos pos,
        SettingColor side, SettingColor line, ShapeMode shape) {
        event.renderer.box(pos, side, line, shape, 0);
    }

    /**
     * Renders a box using world coordinates.
     *
     * @param event active 3D render event
     * @param box world-space box
     * @param side fill color
     * @param line outline color
     * @param shape box shape mode
     */
    public static void box(Render3DEvent event, Box box,
        SettingColor side, SettingColor line, ShapeMode shape) {
        event.renderer.box(box, side, line, shape, 0);
    }

    /**
     * Renders a line between two world positions.
     *
     * @param event active 3D render event
     * @param start line start
     * @param end line end
     * @param color line color
     */
    public static void line(Render3DEvent event, Vec3d start, Vec3d end, Color color) {
        event.renderer.line(start.x, start.y, start.z, end.x, end.y, end.z, color);
    }
}
