package com.example.humanaim.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Random;
import java.util.Set;

public class HumanTriggerBot extends Module {
    public enum CombatMode { Modern, Legacy }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMace = settings.createGroup("Mace");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // ---- General ----
    private final Setting<CombatMode> mode = sgGeneral.add(new EnumSetting.Builder<CombatMode>()
        .name("combat-mode")
        .description("Modern (1.9+): waits for the attack cooldown. Legacy (1.8): ignores the cooldown and clicks at a human CPS.")
        .defaultValue(CombatMode.Modern)
        .build());

    private final Setting<Keybind> switchKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("switch-mode-key")
        .description("Press to flip between Modern and Legacy combat on the fly.")
        .defaultValue(Keybind.none())
        .action(() -> {
            mode.set(mode.get() == CombatMode.Modern ? CombatMode.Legacy : CombatMode.Modern);
            info("Combat mode: " + mode.get());
        })
        .build());

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities the trigger bot may attack.")
        .defaultValue(EntityType.PLAYER)
        .onlyAttackable()
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Max distance to the target. Vanilla reach is 3.0, going higher is pointless and suspicious.")
        .defaultValue(3.0).min(1).max(6).sliderMax(4)
        .build());

    private final Setting<Boolean> onlyWeapons = sgGeneral.add(new BoolSetting.Builder()
        .name("only-weapons")
        .description("Only attack while holding a sword, axe, mace or trident.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlyCrits = sgGeneral.add(new BoolSetting.Builder()
        .name("only-crits")
        .description("Wait until you're falling so the hit is a critical (not used for the mace).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseUsing = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-while-using-item")
        .description("Don't attack while eating, blocking or drawing a bow.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Never attack Meteor friends.")
        .defaultValue(true)
        .build());

    // ---- Mace ----
    private final Setting<Double> maceCooldown = sgMace.add(new DoubleSetting.Builder()
        .name("mace-min-cooldown")
        .description("Attack charge (0-1) needed with a mace. Swapping items resets the charge and damage scales with it, so raise this if your smash hits are weak.")
        .defaultValue(0.0).min(0).max(1).sliderMax(1)
        .build());

    private final Setting<Boolean> maceOnlySmash = sgMace.add(new BoolSetting.Builder()
        .name("mace-only-smash")
        .description("With a mace, only attack when you're falling far enough for a smash attack.")
        .defaultValue(false)
        .build());

    private final Setting<Double> smashFall = sgMace.add(new DoubleSetting.Builder()
        .name("smash-fall-distance")
        .description("Fall distance (blocks) needed to count as a smash attack. Vanilla minimum is 1.5.")
        .defaultValue(1.5).min(1.5).max(30).sliderMax(10)
        .build());

    // ---- Timing ----
    private final Setting<Double> minCps = sgTiming.add(new DoubleSetting.Builder()
        .name("legacy-min-cps")
        .description("Legacy mode: slowest click rate (clicks per second).")
        .defaultValue(8).min(1).max(20).sliderMax(20)
        .build());

    private final Setting<Double> maxCps = sgTiming.add(new DoubleSetting.Builder()
        .name("legacy-max-cps")
        .description("Legacy mode: fastest click rate (clicks per second).")
        .defaultValue(12).min(1).max(20).sliderMax(20)
        .build());

    private final Setting<Double> cooldown = sgTiming.add(new DoubleSetting.Builder()
        .name("weapon-min-cooldown")
        .description("Attack charge (0-1) needed with any non-mace weapon.")
        .defaultValue(0.92).min(0).max(1).sliderMax(1)
        .build());

    private final Setting<Integer> delayMin = sgTiming.add(new IntSetting.Builder()
        .name("min-delay-ms")
        .description("Minimum random delay between being ready and attacking.")
        .defaultValue(20).min(0).max(500).sliderMax(200)
        .build());

    private final Setting<Integer> delayMax = sgTiming.add(new IntSetting.Builder()
        .name("max-delay-ms")
        .description("Maximum random delay between being ready and attacking.")
        .defaultValue(70).min(0).max(500).sliderMax(200)
        .build());

    private final Setting<Integer> swapDelay = sgTiming.add(new IntSetting.Builder()
        .name("after-swap-delay-ms")
        .description("Extra wait after you change the held item (e.g. weapon -> mace) before attacking.")
        .defaultValue(70).min(0).max(500).sliderMax(300)
        .build());

    private final Random rng = new Random();
    private int lastSlot = -1;
    private long swapUntil;
    private long fireAt;
    private long nextClickAt;

    public HumanTriggerBot() {
        super(Categories.Combat, "human-trigger-bot",
            "Attacks when your crosshair is on a valid target, with randomised timing and mace swap handling.");
    }

    @Override
    public void onActivate() {
        lastSlot = -1;
        fireAt = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();

        // Detect hotbar swaps (works for sudden weapon -> mace switches).
        int slot = mc.player.getInventory().getSelectedSlot();
        if (slot != lastSlot) {
            if (lastSlot != -1) swapUntil = now + jitter(swapDelay.get());
            lastSlot = slot;
            fireAt = 0;
        }

        if (mc.currentScreen != null) { fireAt = 0; return; }
        if (now < swapUntil) return;
        if (pauseUsing.get() && mc.player.isUsingItem()) { fireAt = 0; return; }

        if (!(mc.crosshairTarget instanceof EntityHitResult hit) || hit.getType() != HitResult.Type.ENTITY) {
            fireAt = 0;
            return;
        }

        Entity target = hit.getEntity();
        if (!isValid(target)) { fireAt = 0; return; }

        ItemStack held = mc.player.getMainHandStack();
        boolean mace = held.isOf(Items.MACE);

        if (onlyWeapons.get() && !(mace || held.isOf(Items.TRIDENT) || held.isIn(ItemTags.SWORDS) || held.isIn(ItemTags.AXES))) {
            fireAt = 0;
            return;
        }

        // ---- readiness ----
        float charge = mc.player.getAttackCooldownProgress(0.5f);
        if (mace) {
            if (charge < maceCooldown.get()) { fireAt = 0; return; }
            if (maceOnlySmash.get() && mc.player.fallDistance < smashFall.get()) { fireAt = 0; return; }
        } else {
            if (mode.get() == CombatMode.Modern && charge < cooldown.get()) { fireAt = 0; return; }
            if (onlyCrits.get() && !canCrit()) { fireAt = 0; return; }
        }

        // ---- legacy (1.8) mode: click at a randomised CPS, no cooldown wait ----
        if (mode.get() == CombatMode.Legacy && !mace) {
            if (now < nextClickAt) return;
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            double lo = Math.min(minCps.get(), maxCps.get());
            double hi = Math.max(minCps.get(), maxCps.get());
            double cps = lo + rng.nextDouble() * (hi - lo);
            nextClickAt = now + (long) (1000.0 / cps * (0.85 + rng.nextDouble() * 0.3));
            return;
        }

        // ---- randomised reaction delay ----
        if (fireAt == 0) {
            int lo = Math.min(delayMin.get(), delayMax.get());
            int hi = Math.max(delayMin.get(), delayMax.get());
            fireAt = now + lo + (hi > lo ? rng.nextInt(hi - lo + 1) : 0);
        }
        if (now < fireAt) return;

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        fireAt = 0;
    }

    private boolean canCrit() {
        return mc.player.fallDistance > 0.0
            && !mc.player.isOnGround()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasVehicle();
    }

    private long jitter(int ms) {
        return (long) (ms * (0.7 + rng.nextDouble() * 0.6));
    }

    private boolean isValid(Entity e) {
        if (e == mc.player || !(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (!entities.get().contains(e.getType())) return false;
        if (mc.player.distanceTo(e) > range.get()) return false;
        if (e instanceof PlayerEntity p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(p)) return false;
        }
        return true;
    }
}
