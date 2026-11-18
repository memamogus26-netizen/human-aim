package com.example.humanaim.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;
import java.util.Set;

public class HumanAimAssist extends Module {
    public enum Priority { Crosshair, Distance }
    public enum Aim { Head, Body, Feet }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHuman = settings.createGroup("Humanisation");

    // ---- General ----
    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities the assist is allowed to target.")
        .defaultValue(EntityType.PLAYER)
        .onlyAttackable()
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum target distance in blocks.")
        .defaultValue(4.5).min(1).sliderMax(10)
        .build());

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Only targets within this angle (degrees) of your crosshair.")
        .defaultValue(60).min(5).max(180).sliderMax(180)
        .build());

    private final Setting<Priority> priority = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("priority")
        .description("How to pick between several valid targets.")
        .defaultValue(Priority.Crosshair)
        .build());

    private final Setting<Aim> aim = sgGeneral.add(new EnumSetting.Builder<Aim>()
        .name("aim-at")
        .description("Which part of the target to aim at.")
        .defaultValue(Aim.Body)
        .build());

    private final Setting<Boolean> ignoreWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls")
        .description("Target entities you can't see.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Never target Meteor friends.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> requireClick = sgGeneral.add(new BoolSetting.Builder()
        .name("require-attack-key")
        .description("Only assist while holding the attack button.")
        .defaultValue(false)
        .build());

    // ---- Humanisation ----
    private final Setting<Double> speed = sgHuman.add(new DoubleSetting.Builder()
        .name("smoothing-speed")
        .description("Higher = snappier. Rotation closes the gap exponentially at this rate.")
        .defaultValue(5).min(0.5).sliderMax(20)
        .build());

    private final Setting<Double> maxSpeed = sgHuman.add(new DoubleSetting.Builder()
        .name("max-degrees-per-second")
        .description("Hard cap on how fast the camera can be moved.")
        .defaultValue(160).min(20).sliderMax(720)
        .build());

    private final Setting<Double> speedRandom = sgHuman.add(new DoubleSetting.Builder()
        .name("speed-variation")
        .description("Random speed variation in percent.")
        .defaultValue(30).min(0).max(80).sliderMax(80)
        .build());

    private final Setting<Double> noiseAmp = sgHuman.add(new DoubleSetting.Builder()
        .name("micro-rotation-amount")
        .description("Size (degrees) of the small wandering offset added to the aim point.")
        .defaultValue(0.8).min(0).max(5).sliderMax(3)
        .build());

    private final Setting<Double> noiseSpeed = sgHuman.add(new DoubleSetting.Builder()
        .name("micro-rotation-speed")
        .description("How fast the wandering offset changes.")
        .defaultValue(1.5).min(0.1).max(10).sliderMax(6)
        .build());

    private final Setting<Integer> reaction = sgHuman.add(new IntSetting.Builder()
        .name("reaction-time-ms")
        .description("Average delay before starting to move after acquiring a target.")
        .defaultValue(80).min(0).max(500).sliderMax(300)
        .build());

    private final Setting<Boolean> mouseSnap = sgHuman.add(new BoolSetting.Builder()
        .name("mouse-sensitivity-snap")
        .description("Quantises rotation to steps a real mouse could produce at your sensitivity.")
        .defaultValue(true)
        .build());

    private final Random rng = new Random();
    private Entity lastTarget;
    private long delayUntil;
    private long lastNanos;
    private long startMs;
    private double accYaw, accPitch;
    private double curMul = 1, tgtMul = 1;
    private long nextMulChange;
    private final double[] phase = new double[6];

    public HumanAimAssist() {
        super(Categories.Combat, "human-aim-assist",
            "Aim assist that only acts when you're NOT already on target, with human-like micro rotations.");
    }

    @Override
    public void onActivate() {
        reset();
        for (int i = 0; i < phase.length; i++) phase[i] = rng.nextDouble() * Math.PI * 2;
        startMs = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        lastTarget = null;
        accYaw = accPitch = 0;
        lastNanos = 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) { reset(); return; }
        if (requireClick.get() && !mc.options.attackKey.isPressed()) { reset(); return; }

        long nowNanos = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (nowNanos - lastNanos) / 1e9);
        lastNanos = nowNanos;
        long nowMs = System.currentTimeMillis();

        Entity target = findTarget();   // null if already looking at a valid target
        if (target == null) {
            lastTarget = null;
            accYaw = accPitch = 0;
            return;
        }

        if (target != lastTarget) {
            lastTarget = target;
            delayUntil = nowMs + (long) (reaction.get() * (0.5 + rng.nextDouble()));
        }
        if (nowMs < delayUntil) return;

        // ---- aim point + micro rotation noise ----
        Vec3d eye = mc.player.getEyePos();
        double frac = switch (aim.get()) {
            case Head -> 0.92;
            case Body -> 0.55;
            case Feet -> 0.15;
        };
        double tx = target.getX();
        double ty = target.getY() + target.getHeight() * frac;
        double tz = target.getZ();

        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        double goalYaw = MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double goalPitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));

        double t = (nowMs - startMs) / 1000.0 * noiseSpeed.get();
        double amp = noiseAmp.get();
        goalYaw += amp * (Math.sin(t + phase[0]) * 0.55 + Math.sin(t * 2.3 + phase[1]) * 0.30 + Math.sin(t * 4.1 + phase[2]) * 0.15);
        goalPitch += amp * 0.6 * (Math.sin(t * 1.3 + phase[3]) * 0.55 + Math.sin(t * 2.9 + phase[4]) * 0.30 + Math.sin(t * 5.0 + phase[5]) * 0.15);

        // ---- smooth, slightly random speed ----
        if (nowMs > nextMulChange) {
            tgtMul = 1 + (rng.nextDouble() * 2 - 1) * speedRandom.get() / 100.0;
            nextMulChange = nowMs + 150 + rng.nextInt(250);
        }
        curMul += (tgtMul - curMul) * Math.min(1, dt * 8);

        double k = 1 - Math.exp(-speed.get() * curMul * dt);
        double cap = maxSpeed.get() * dt;

        double dYaw = MathHelper.wrapDegrees(goalYaw - mc.player.getYaw());
        double dPitch = goalPitch - mc.player.getPitch();

        double stepYaw = MathHelper.clamp(dYaw * k, -cap, cap);
        double stepPitch = MathHelper.clamp(dPitch * k * 0.85, -cap, cap);

        // ---- quantise to mouse-sensitivity steps ----
        if (mouseSnap.get()) {
            double s = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            double gcd = s * s * s * 8.0 * 0.15;

            accYaw += stepYaw;
            accPitch += stepPitch;

            stepYaw = Math.floor(Math.abs(accYaw) / gcd) * gcd * Math.signum(accYaw);
            stepPitch = Math.floor(Math.abs(accPitch) / gcd) * gcd * Math.signum(accPitch);

            accYaw -= stepYaw;
            accPitch -= stepPitch;
        }

        if (stepYaw == 0 && stepPitch == 0) return;

        mc.player.setYaw((float) (mc.player.getYaw() + stepYaw));
        mc.player.setPitch(MathHelper.clamp((float) (mc.player.getPitch() + stepPitch), -90f, 90f));
    }

    /** Returns the best target, or null if none or if the crosshair is already on a valid one. */
    private Entity findTarget() {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        Vec3d eye = mc.player.getEyePos();

        for (Entity e : mc.world.getEntities()) {
            if (!isValid(e)) continue;

            // Already looking at a valid target -> don't assist at all.
            if (isLookingAt(e)) return null;

            double dx = e.getX() - eye.x;
            double dy = e.getY() + e.getHeight() * 0.5 - eye.y;
            double dz = e.getZ() - eye.z;
            double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
            double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            double angle = Math.hypot(MathHelper.wrapDegrees(yaw - curYaw), pitch - curPitch);

            if (angle > fov.get()) continue;

            double score = priority.get() == Priority.Crosshair ? angle : mc.player.distanceTo(e);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private boolean isValid(Entity e) {
        if (e == mc.player || !(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (!entities.get().contains(e.getType())) return false;
        if (mc.player.distanceTo(e) > range.get()) return false;

        if (e instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(p)) return false;
        }
        return ignoreWalls.get() || PlayerUtils.canSeeEntity(e);
    }

    private boolean isLookingAt(Entity e) {
        double yaw = Math.toRadians(mc.player.getYaw());
        double pitch = Math.toRadians(mc.player.getPitch());
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));

        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(look.multiply(range.get()));
        Box box = e.getBoundingBox().expand(e.getTargetingMargin());
        return box.contains(eye) || box.raycast(eye, end).isPresent();
    }
}
