package com.silverwalldiscs.partygames;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PartyGamesMod implements ModInitializer {
    public static final String MOD_ID = "partygames";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PartyGamesMod instance;
    private PartyGamesConfig config;
    private PartyGamesManager manager;

    public static PartyGamesMod instance() {
        return instance;
    }

    public boolean isProtectedPlayer(java.util.UUID uuid) {
        return manager != null && manager.isProtected(uuid);
    }

    @Override
    public void onInitialize() {
        instance = this;
        var configPath = FabricLoader.getInstance().getConfigDir().resolve("partygames").resolve("config.json");
        config = PartyGamesConfig.load(configPath);
        manager = new PartyGamesManager(config, configPath.getParent());

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
            boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                    Identifier.fromNamespaceAndPath(MOD_ID, "event_dimension"),
                    container,
                    Component.literal("PartyGames Event Dimension"),
                    ResourcePackActivationType.ALWAYS_ENABLED
            );
            if (!registered) {
                LOGGER.error("Could not register the built-in event-dimension data pack. The event dimension may be unavailable.");
            }
        });

        registerCommands();
        registerProtection();
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(manager::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(manager::onServerStopping);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> manager.onPlayerJoin(server, handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                manager.onPlayerDisconnect(server, handler.player));

        LOGGER.info("PartyGames loaded. The first minigame is Dance Floor.");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            dispatcher.register(Commands.literal("partygames")
                    .executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "PartyGames: /partygames join, status, retrieve; admins: start, stop, reload."
                        ), false);
                        return 1;
                    })
                    .then(Commands.literal("join").executes(context ->
                            manager.join(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("retrieve").executes(context ->
                            manager.retrieve(context.getSource().getPlayerOrException())))
                    .then(Commands.literal("status").executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(manager.status()), false);
                        return 1;
                    }))
                    .then(Commands.literal("start").requires(source -> source.hasPermission(2)).executes(context ->
                            manager.startRegistration(context.getSource().getServer(), false) ? 1 : 0))
                    .then(Commands.literal("stop").requires(source -> source.hasPermission(2)).executes(context -> {
                        manager.cancelEvent(context.getSource().getServer());
                        return 1;
                    }))
                    .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(context -> {
                        config = PartyGamesConfig.load(configPath());
                        manager.updateConfig(config);
                        context.getSource().sendSuccess(() -> Component.literal("PartyGames configuration reloaded."), true);
                        return 1;
                    }))
            );
        });
    }

    private java.nio.file.Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("partygames").resolve("config.json");
    }

    private void registerProtection() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                protectedInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
                protectedInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                protectedInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                protectedInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                protectedInteraction(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((Level level, Player player, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity blockEntity) ->
                !protectedInteraction(player));
    }

    private boolean protectedInteraction(Player player) {
        return player instanceof ServerPlayer serverPlayer && manager.isProtected(serverPlayer.getUUID());
    }
}
