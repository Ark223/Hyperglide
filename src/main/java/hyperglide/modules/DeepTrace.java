package hyperglide.modules;

import hyperglide.Hyperglide;
import hyperglide.utilities.API;
import hyperglide.utilities.Client;
import hyperglide.utilities.Render;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeepTrace extends Module {
    private static final int radius = 4;
    private static final int height = 4;
    private static final int interval = 10;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Boolean> spawners = this.general.add(new BoolSetting.Builder()
        .name("active-spawners")
        .description("Detects mobs nearby active dungeon spawners.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> items = this.general.add(new BoolSetting.Builder()
        .name("unnatural-items")
        .description("Detects unusual dropped items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> blacklist = this.general.add(new ItemListSetting.Builder()
        .name("item-blacklist")
        .description("Dropped items ignored by Deep Trace.")
        .defaultValue(
            Items.ALLIUM, Items.AZALEA, Items.AZURE_BLUET, Items.BIG_DRIPLEAF,
            Items.BLUE_ORCHID, Items.BROWN_MUSHROOM, Items.CORNFLOWER,
            Items.GLOW_BERRIES, Items.DANDELION, Items.FLOWERING_AZALEA,
            Items.GLOW_INK_SAC, Items.GRAVEL, Items.INK_SAC, Items.KELP,
            Items.LILAC, Items.LILY_OF_THE_VALLEY, Items.LILY_PAD,
            Items.MOSS_CARPET, Items.ORANGE_TULIP, Items.OXEYE_DAISY,
            Items.PEONY, Items.PINK_PETALS, Items.PINK_TULIP, Items.POPPY,
            Items.RAIL, Items.RED_MUSHROOM, Items.RED_SAND, Items.RED_TULIP,
            Items.ROSE_BUSH, Items.SAND, Items.SPORE_BLOSSOM, Items.STRING,
            Items.SUNFLOWER, Items.TORCH, Items.WHEAT_SEEDS, Items.WHITE_TULIP
        )
        .visible(this.items::get)
        .build()
    );

    private final Setting<Integer> level = this.general.add(new IntSetting.Builder()
        .name("max-level")
        .description("Maximum Y level where items are detected.")
        .defaultValue(64)
        .min(-64)
        .sliderMax(64)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How detected entity boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of detected entity boxes.")
        .defaultValue(new SettingColor(255, 80, 80, 32))
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline and tracer color.")
        .defaultValue(new SettingColor(255, 80, 80, 255))
        .build()
    );

    private final Setting<Boolean> tracers = this.visuals.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draws tracers to detected entities.")
        .defaultValue(true)
        .build()
    );

    private final List<Dungeon> dungeons = new ArrayList<>();

    private int timer;
    private int count;

    //region Module lifecycle

    public DeepTrace() {
        super(Hyperglide.CATEGORY, "deep-trace",
            "Highlights unusual entities found deep underground."
        );
    }

    /**
     * Clears cached dungeon positions.
     */
    @Override
    public void onActivate() {
        this.reset();
    }

    /**
     * Clears cached dungeon positions.
     */
    @Override
    public void onDeactivate() {
        this.reset();
    }

    /**
     * Returns the number of detected items and dungeon mobs.
     *
     * @return detected entity count
     */
    @Override
    public String getInfoString() {
        return Integer.toString(this.count);
    }

    /**
     * Clears cached dungeon and render state.
     */
    private void reset() {
        this.dungeons.clear();
        this.timer = 0;
        this.count = 0;
    }

    //endregion

    //region Event handlers

    /**
     * Refreshes nearby dungeon rooms.
     *
     * @param event pre-tick event
     */
    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!this.valid() || !this.spawners.get()) {
            this.dungeons.clear();
            this.timer = 0;
            return;
        }

        if (this.timer > 0) {
            this.timer--;
            return;
        }

        this.timer = interval - 1;
        this.scan();
    }

    /**
     * Finds and renders unusual items and dungeon mobs.
     *
     * @param event 3D render event
     */
    @EventHandler
    private void render(Render3DEvent event) {
        this.count = 0;
        if (!this.valid()) return;

        if (this.items.get()) this.items(event);
        if (this.spawners.get()) this.mobs(event);
    }

    //endregion

    //region Entity detection

    /**
     * Finds unusual dropped items.
     *
     * @param event 3D render event
     */
    private void items(Render3DEvent event) {
        for (Entity entity : this.mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) ||
                !item.isAlive() ||
                item.getBlockY() >= this.level.get() - 1 ||
                !EntityUtils.isInRenderDistance(item)) {
                continue;
            }

            ItemStack stack = item.getStack();
            if (stack.isEmpty() ||
                this.blacklist.get().contains(stack.getItem())) {
                continue;
            }

            this.draw(event, item);
        }
    }

    /**
     * Finds mobs produced by active dungeon spawners.
     *
     * @param event 3D render event
     */
    private void mobs(Render3DEvent event) {
        if (this.dungeons.isEmpty()) return;

        Set<Integer> seen = new HashSet<>();

        for (Dungeon dungeon : this.dungeons) {
            for (Entity entity : this.entities(dungeon)) {
                if (!dungeon.box.contains(API.pos(entity)) ||
                    !seen.add(entity.getId())) {
                    continue;
                }

                this.draw(event, entity);
            }
        }
    }

    /**
     * Finds dungeon spawners in nearby loaded chunks.
     */
    private void scan() {
        this.dungeons.clear();

        ClientChunkManager manager = this.mc.world.getChunkManager();
        if (manager == null) return;

        int range = this.mc.options.getViewDistance().getValue();

        int cx = this.mc.player.getBlockX() >> 4;
        int cz = this.mc.player.getBlockZ() >> 4;

        for (int x = cx - range; x <= cx + range; x++) {
            for (int z = cz - range; z <= cz + range; z++) {

                WorldChunk chunk = manager.getWorldChunk(x, z, false);
                if (chunk == null) continue;

                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof MobSpawnerBlockEntity spawner)) {
                        continue;
                    }

                    if (spawner.getPos().getY() >= this.level.get() - 1) {
                        continue;
                    }

                    Entity mob = spawner.getLogic().getRenderedEntity(
                        this.mc.world, spawner.getPos()
                    );

                    if (mob == null) continue;

                    Box room = this.room(spawner.getPos());
                    this.dungeons.add(new Dungeon(room, mob.getType()));
                }
            }
        }
    }

    /**
     * Renders a detected entity and optional tracer.
     *
     * @param event 3D render event
     * @param entity entity to render
     */
    private void draw(Render3DEvent event, Entity entity) {
        Vec3d pos = entity.getLerpedPos(event.tickDelta);
        Box box = entity.getBoundingBox().offset(
            pos.x - entity.getX(),
            pos.y - entity.getY(),
            pos.z - entity.getZ()
        );

        Render.box(event, box,
            this.side.get(), this.line.get(), this.shape.get()
        );

        if (this.tracers.get()) {
            Vec3d start = RenderUtils.center;
            Vec3d end = box.getCenter();
            Render.line(event, start, end, this.line.get());
        }

        this.count++;
    }

    //endregion

    //region Validation and utilities

    /**
     * Returns spawned mobs matching a dungeon spawner.
     *
     * @param dungeon dungeon to search
     * @return matching entities
     */
    private List<Entity> entities(Dungeon dungeon) {
        return this.mc.world.getOtherEntities(null, dungeon.box,
            entity -> this.monster(entity, dungeon.type)
        );
    }

    /**
     * Checks whether an entity was produced by a dungeon spawner.
     *
     * @param entity entity to check
     * @param type spawner entity type
     * @return true when the entity should be highlighted
     */
    private boolean monster(Entity entity, EntityType<?> type) {
        return entity != null && entity.isAlive()
            && entity.getType() == type
            && EntityUtils.isInRenderDistance(entity);
    }

    /**
     * Creates the known dungeon interior around a central spawner.
     *
     * @param pos spawner position
     * @return seven by four by seven dungeon interior
     */
    private Box room(BlockPos pos) {
        return new Box(
            pos.getX() - radius,
            pos.getY(),
            pos.getZ() - radius,
            pos.getX() + radius + 1,
            pos.getY() + height,
            pos.getZ() + radius + 1
        );
    }

    /**
     * Checks whether module can inspect the current world.
     *
     * @return true when the overworld is loaded
     */
    private boolean valid() {
        return Client.loaded() && World.OVERWORLD.equals(
            this.mc.world.getRegistryKey()
        );
    }

    //endregion

    //region Data structures

    /**
     * Stores dungeon interior and the assigned entity type.
     *
     * @param box dungeon interior
     * @param type spawner entity type
     */
    private record Dungeon(Box box, EntityType<?> type) {}

    //endregion
}
