package com.ysmef.compat.model.runtime;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Frame values captured on the render thread; item stacks are copied for async evaluation. */
final class AnimatorEvalInputs {
    private static final float MIN_SPEED = 0.05F;

    final double cameraDistance, x, y, z, oldX, oldY, oldZ;
    final float frameTime, headYaw, headPitch, health, maxHealth;
    final float moveDist, walkDist, xRot, yRot;
    final int hurtTime, ticksUsingItem, useItemRemainingTicks, useItemDuration;
    final int direction, moonPhase, playerLevel, armorValue, foodLevel;
    final long dayTime;
    final boolean firstPerson, dead, swimming, passenger, boat, flying, fallFlying;
    final boolean inWater, onGround, sprinting, sleeping, inWaterRainOrBubble;
    final boolean onFire, spectator, usingItem, swinging, vehicle, autoSpinAttack;
    final boolean hasHelmet, hasChestPlate, hasLeggings, hasBoots, hasElytra;
    final boolean eating, moving;
    final Pose pose;
    final ResourceLocation vehicleId;
    final InteractionHand usedItemHand;
    final ItemStack mainHand, offHand, useItem;

    private AnimatorEvalInputs(LivingEntity entity, float partialTick, Minecraft mc,
                               double cameraDistance, boolean copyItems) {
        this.cameraDistance = cameraDistance;
        this.frameTime = mc.getFrameTime();
        this.firstPerson = mc.options.getCameraType() == CameraType.FIRST_PERSON;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        this.oldX = entity.xo;
        this.oldY = entity.yo;
        this.oldZ = entity.zo;
        float head = entity.yHeadRotO + (entity.yHeadRot - entity.yHeadRotO) * partialTick;
        float body = entity.yBodyRotO + (entity.yBodyRot - entity.yBodyRotO) * partialTick;
        this.headYaw = net.minecraft.util.Mth.wrapDegrees(head - body);
        this.headPitch = entity.getViewXRot(partialTick);
        this.health = entity.getHealth();
        this.maxHealth = entity.getMaxHealth();
        this.moveDist = entity.moveDist;
        this.walkDist = entity.walkDist;
        this.xRot = entity.getXRot();
        this.yRot = entity.getYRot();
        this.hurtTime = entity.hurtTime;
        this.ticksUsingItem = entity.getTicksUsingItem();
        this.useItemRemainingTicks = entity.getUseItemRemainingTicks();
        ItemStack main = entity.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = entity.getItemInHand(InteractionHand.OFF_HAND);
        ItemStack used = entity.getUseItem();
        this.mainHand = copyItems ? main.copy() : main;
        this.offHand = copyItems ? off.copy() : off;
        this.useItem = copyItems ? used.copy() : used;
        this.useItemDuration = useItem.getUseDuration();
        this.eating = useItem.getUseAnimation() == net.minecraft.world.item.UseAnim.EAT;
        this.direction = entity.getDirection().get3DDataValue();
        this.dayTime = entity.level().getDayTime();
        this.moonPhase = entity.level().getMoonPhase();
        this.playerLevel = entity instanceof Player player ? player.experienceLevel : 0;
        this.armorValue = entity.getArmorValue();
        this.foodLevel = entity instanceof Player player ? player.getFoodData().getFoodLevel() : 20;
        this.dead = entity.isDeadOrDying();
        this.pose = entity.getPose();
        this.swimming = entity.isSwimming();
        this.passenger = entity.isPassenger();
        var ridden = entity.getVehicle();
        this.vehicleId = ridden == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(ridden.getType());
        this.boat = ridden instanceof net.minecraft.world.entity.vehicle.Boat;
        this.flying = entity instanceof Player player && player.getAbilities().flying;
        this.fallFlying = entity.isFallFlying();
        this.inWater = entity.isInWater();
        this.onGround = entity.onGround();
        this.sprinting = entity.isSprinting();
        this.sleeping = entity.isSleeping();
        this.inWaterRainOrBubble = entity.isInWaterRainOrBubble();
        this.onFire = entity.isOnFire();
        this.spectator = entity instanceof Player player && player.isSpectator();
        this.usingItem = entity.isUsingItem();
        this.usedItemHand = entity.getUsedItemHand();
        this.swinging = entity.swinging;
        this.vehicle = entity.isVehicle();
        this.autoSpinAttack = entity.isAutoSpinAttack();
        this.hasHelmet = !entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        ItemStack chest = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        this.hasChestPlate = !chest.isEmpty();
        this.hasLeggings = !entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty();
        this.hasBoots = !entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty();
        this.hasElytra = chest.is(Items.ELYTRA);
        this.moving = Math.abs(entity.walkAnimation.speed(frameTime)) > MIN_SPEED;
    }

    static AnimatorEvalInputs capture(LivingEntity entity, float partialTick, boolean copyItems) {
        Minecraft mc = Minecraft.getInstance();
        double cameraDistance = -1.0;
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            cameraDistance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(entity.position());
        }
        return new AnimatorEvalInputs(entity, partialTick, mc, cameraDistance, copyItems);
    }
}
