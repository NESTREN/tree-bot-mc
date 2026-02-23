package com.treebot;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class TreeBotClient implements ClientModInitializer {
    private static final double DANGER_RADIUS = 10.0;
    private static final double COMBAT_DISTANCE = 2.8;
    private static final double TREE_ACTION_DISTANCE = 4.0;
    private static final double TREE_REACH_RADIUS = 8.0;
    private static final long TREE_RESCAN_TICKS = 10;
    private static final long FISH_RECAST_TIMEOUT_TICKS = 120;

    private BotMode selectedMode = BotMode.IDLE;
    private BotMode runtimeMode = BotMode.IDLE;
    private long tickCounter = 0;
    private long lastFishCastTick = -200;
    private long lastTreeScanTick = -200;
    private BlockPos currentTreeTarget;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> dispatcher,
                                  CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("treebot")
            .then(ClientCommandManager.literal("mode")
                .then(ClientCommandManager.argument("value", StringArgumentType.word())
                    .executes(ctx -> {
                        setMode(ctx.getSource(), StringArgumentType.getString(ctx, "value"));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("status")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("TreeBot => selected: " + selectedMode + ", runtime: " + runtimeMode + ", treeTarget: " + (currentTreeTarget == null ? "none" : currentTreeTarget.toShortString())));
                    return 1;
                }))
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("Используй: /treebot mode <idle|tree|fish>"));
                return 1;
            }));
    }

    private void setMode(FabricClientCommandSource source, String rawMode) {
        switch (rawMode.toLowerCase(Locale.ROOT)) {
            case "idle" -> selectedMode = BotMode.IDLE;
            case "tree" -> selectedMode = BotMode.TREE;
            case "fish" -> selectedMode = BotMode.FISH;
            default -> {
                source.sendError(Text.literal("Неизвестный режим: " + rawMode));
                return;
            }
        }
        source.sendFeedback(Text.literal("Режим установлен: " + selectedMode));
    }

    private void onTick(MinecraftClient client) {
        tickCounter++;
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        releaseMovementKeys(client);

        if (isInDanger(client.player)) {
            runtimeMode = BotMode.COMBAT;
            doCombat(client, client.player);
            return;
        }

        runtimeMode = selectedMode;
        switch (runtimeMode) {
            case TREE -> doTreeFarming(client, client.player);
            case FISH -> doFishing(client, client.player);
            case IDLE, COMBAT -> {
                currentTreeTarget = null;
                client.interactionManager.cancelBlockBreaking();
            }
        }
    }

    private boolean isInDanger(ClientPlayerEntity player) {
        Box dangerBox = player.getBoundingBox().expand(DANGER_RADIUS);
        return !player.getWorld().getEntitiesByClass(HostileEntity.class, dangerBox, hostile -> hostile.isAlive() && !hostile.isTeammate(player)).isEmpty();
    }

    private void doCombat(MinecraftClient client, ClientPlayerEntity player) {
        Optional<HostileEntity> nearestHostile = player.getWorld().getEntitiesByClass(
                HostileEntity.class,
                player.getBoundingBox().expand(DANGER_RADIUS),
                hostile -> hostile.isAlive() && hostile.distanceTo(player) <= DANGER_RADIUS && hostile.canTarget(player)
            ).stream()
            .min(Comparator.comparingDouble(player::distanceTo));

        if (nearestHostile.isEmpty()) {
            return;
        }

        HostileEntity target = nearestHostile.get();
        lookAt(client, target.getPos().add(0, target.getHeight() * 0.6, 0));

        if (player.distanceTo(target) > COMBAT_DISTANCE) {
            holdForward(client, true);
            return;
        }

        holdForward(client, false);
        if (player.getAttackCooldownProgress(0.0f) >= 0.92f) {
            client.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void doTreeFarming(MinecraftClient client, ClientPlayerEntity player) {
        if (currentTreeTarget == null || !isTreeTargetValid(player, currentTreeTarget) || tickCounter - lastTreeScanTick >= TREE_RESCAN_TICKS) {
            currentTreeTarget = findClosestLog(player);
            lastTreeScanTick = tickCounter;
            if (currentTreeTarget == null) {
                client.interactionManager.cancelBlockBreaking();
                return;
            }
        }

        Vec3d targetCenter = Vec3d.ofCenter(currentTreeTarget);
        lookAt(client, targetCenter);

        if (player.squaredDistanceTo(targetCenter) > TREE_ACTION_DISTANCE * TREE_ACTION_DISTANCE) {
            holdForward(client, true);
            return;
        }

        holdForward(client, false);
        if (!isTreeTargetValid(player, currentTreeTarget)) {
            currentTreeTarget = null;
            client.interactionManager.cancelBlockBreaking();
            return;
        }

        client.interactionManager.updateBlockBreakingProgress(currentTreeTarget, Direction.UP);
        player.swingHand(Hand.MAIN_HAND);
    }

    private BlockPos findClosestLog(ClientPlayerEntity player) {
        BlockPos playerPos = player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int scanRadius = (int) TREE_REACH_RADIUS;

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, scanRadius, 4, scanRadius)) {
            BlockState blockState = player.getWorld().getBlockState(pos);
            if (!blockState.isIn(BlockTags.LOGS)) {
                continue;
            }
            double distance = pos.getSquaredDistance(playerPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private void doFishing(MinecraftClient client, ClientPlayerEntity player) {
        Hand rodHand = getFishingRodHand(player);
        if (rodHand == null) {
            return;
        }

        FishingBobberEntity bobber = player.fishHook;
        if (bobber == null) {
            if (tickCounter - lastFishCastTick > 25) {
                client.interactionManager.interactItem(player, rodHand);
                player.swingHand(rodHand);
                lastFishCastTick = tickCounter;
            }
            return;
        }

        if (tickCounter - lastFishCastTick > FISH_RECAST_TIMEOUT_TICKS || bobber.isOnGround() || bobber.horizontalCollision || bobber.verticalCollision) {
            client.interactionManager.interactItem(player, rodHand);
            player.swingHand(rodHand);
            lastFishCastTick = tickCounter;
            return;
        }

        List<Entity> closeEntities = player.getWorld().getOtherEntities(player, bobber.getBoundingBox().expand(0.25), entity -> entity != player);
        if (!closeEntities.isEmpty()) {
            client.interactionManager.interactItem(player, rodHand);
            player.swingHand(rodHand);
            lastFishCastTick = tickCounter;
        }
    }

    private Hand getFishingRodHand(ClientPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (main.getItem() instanceof FishingRodItem) {
            return Hand.MAIN_HAND;
        }

        ItemStack off = player.getOffHandStack();
        if (off.getItem() instanceof FishingRodItem) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    private boolean isTreeTargetValid(ClientPlayerEntity player, BlockPos target) {
        BlockState state = player.getWorld().getBlockState(target);
        return state.isIn(BlockTags.LOGS) && !state.isAir();
    }

    private void lookAt(MinecraftClient client, Vec3d target) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        Vec3d eyes = player.getCameraPosVec(1.0f);
        Vec3d delta = target.subtract(eyes);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontal)));

        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);

        if (client.crosshairTarget instanceof EntityHitResult hitResult) {
            Entity entity = hitResult.getEntity();
            if (entity != null && entity.distanceTo(player) < 4.0f) {
                player.setSprinting(false);
            }
        }
    }

    private void holdForward(MinecraftClient client, boolean active) {
        KeyBinding forward = client.options.forwardKey;
        if (forward != null) {
            forward.setPressed(active);
        }
    }

    private void releaseMovementKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
}
