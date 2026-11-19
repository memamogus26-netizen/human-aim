package com.example.humanaim.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/** ESP + animated ring on the last player your crosshair was on. */
public class TargetEsp extends Module {
    public enum Style { Box, Ring, Both }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- General ----
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away a player can be and still be picked up by your crosshair.")
        .defaultValue(10).min(1).max(64).sliderMax(32)
        .build());

    private final Setting<Boolean> requireSight = sgGeneral.add(new BoolSetting.Builder()
        .name("require-line-of-sight")
        .description("Ignore players that are behind blocks.")
        .defaultValue(true)
        .build());

    private final Setting<Double> timeout = sgGeneral.add(new DoubleSetting.Builder()
        .name("forget-after-seconds")
        .description("Stop rendering this long after the crosshair left the player. 0 = keep until they die or leave.")
        .defaultValue(5).min(0).sliderMax(30)
        .build());

    // ---- Render ----
    private final Setting<Style> style = sgRender.add(new EnumSetting.Builder<Style>()
        .name("style")
        .description("What to draw on the target.")
        .defaultValue(Style.Both)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("box-shape-mode")
        .description("How the ESP box is drawn.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Box fill colour.")
        .defaultValue(new SettingColor(255, 60, 60, 40))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Box outline and ring colour.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build());

    private final Setting<Boolean> healthColor = sgRender.add(new BoolSetting.Builder()
        .name("color-by-health")
        .description("Fade the colour from green (full health) to red (low health).")
        .defaultValue(false)
        .build());

    private final Setting<Double> ringSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("ring-speed")
        .description("How fast the ring spins and bobs.")
        .defaultValue(1.0).min(0.1).max(5).sliderMax(3)
        .build());

    private final Setting<Double> ringRadius = sgRender.add(new DoubleSetting.Builder()
        .name("ring-size")
        .description("Ring radius relative to the player's width.")
        .defaultValue(1.0).min(0.5).max(3).sliderMax(2)
        .build());

    private PlayerEntity target;
    private long lastSeen;

    public TargetEsp() {
        super(Categories.Render, "target-esp",
            "ESP and a rotating ring on the last player your crosshair was on.");
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) { target = null; return; }

        long now = System.currentTimeMillis();

        // ---- find the player under the crosshair ----
        PlayerEntity hit = rayPlayer();
        if (hit != null) {
            target = hit;
            lastSeen = now;
        }

        // ---- drop stale / invalid targets ----
        if (target != null) {
            boolean gone = target.isRemoved() || !target.isAlive() || target.getEntityWorld() != mc.world;
            boolean expired = timeout.get() > 0 && (now - lastSeen) > timeout.get() * 1000.0;
            if (gone || expired) target = null;
        }
        if (target == null) return;

        // ---- smooth position ----
        float td = (float) event.tickDelta;
        Vec3d lerped = target.getLerpedPos(td);
        double ox = lerped.x - target.getX();
        double oy = lerped.y - target.getY();
        double oz = lerped.z - target.getZ();
        Box box = target.getBoundingBox().offset(ox, oy, oz);

        // ---- colours ----
        Color line = new Color(lineColor.get());
        Color side = new Color(sideColor.get());
        if (healthColor.get()) {
            double hp = Math.max(0, Math.min(1, target.getHealth() / Math.max(1f, target.getMaxHealth())));
            line = new Color((int) (255 * (1 - hp)), (int) (255 * hp), 40, lineColor.get().a);
            side = new Color(line.r, line.g, line.b, sideColor.get().a);
        }

        if (style.get() != Style.Ring) {
            event.renderer.box(box, side, line, shapeMode.get(), 0);
        }
        if (style.get() != Style.Box) {
            drawRing(event, target, lerped, line, now);
        }
    }

    private void drawRing(Render3DEvent event, LivingEntity e, Vec3d pos, Color color, long now) {
        double t = now / 1000.0 * ringSpeed.get();
        double radius = e.getWidth() * 0.5 * ringRadius.get() + 0.25;
        double height = e.getHeight();

        // Ring bobbing from feet to head and back.
        double y = pos.y + height * (0.5 + 0.5 * Math.sin(t * 2.0));

        int segments = 36;
        double spin = t * 3.0;
        for (int i = 0; i < segments; i++) {
            double a1 = spin + i * (Math.PI * 2 / segments);
            double a2 = spin + (i + 1) * (Math.PI * 2 / segments);

            // Brighter arc that fades around the ring.
            int alpha = (int) (60 + 195 * ((double) i / segments));
            Color c = new Color(color.r, color.g, color.b, Math.min(255, alpha * color.a / 255));

            event.renderer.line(
                pos.x + Math.cos(a1) * radius, y, pos.z + Math.sin(a1) * radius,
                pos.x + Math.cos(a2) * radius, y, pos.z + Math.sin(a2) * radius,
                c);
        }
    }

    /** Nearest player whose hitbox the crosshair ray passes through, or null. */
    private PlayerEntity rayPlayer() {
        double yaw = Math.toRadians(mc.player.getYaw());
        double pitch = Math.toRadians(mc.player.getPitch());
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));

        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(look.multiply(range.get()));

        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;

            Box box = p.getBoundingBox().expand(p.getTargetingMargin());
            Optional<Vec3d> hit = box.raycast(eye, end);
            if (hit.isEmpty()) continue;

            double d = eye.distanceTo(hit.get());
            if (d >= bestDist) continue;
            if (requireSight.get() && !PlayerUtils.canSeeEntity(p)) continue;

            bestDist = d;
            best = p;
        }
        return best;
    }
}
