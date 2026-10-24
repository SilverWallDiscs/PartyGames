package com.silverwalldiscs.partygames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Prediction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

public final class PartyGamesManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SESSION_MAP_TYPE = new TypeToken<Map<String, ReturnPoint>>() {}.getType();
    private static final Type PENDING_MAP_TYPE = new TypeToken<Map<String, PendingReward>>() {}.getType();
    private static final ResourceKey<Level> EVENT_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(PartyGamesMod.MOD_ID, "event"));
    private static final int FLOOR_Y = 64;
    private static final int WALL_HEIGHT = 9;
    private static final int ROOF_Y = FLOOR_Y + WALL_HEIGHT + 1;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Random RANDOM = new Random();
    private static final List<String> MUSIC_DISC_SOUNDS = List.of(
            "minecraft:music_disc.otherside",
            "minecraft:music_disc.tears",
            "minecraft:music_disc.precipice",
            "minecraft:music_disc.creator",
            "minecraft:music_disc.pigstep",
            "minecraft:music_disc.bounce",
            "minecraft:music_disc.lava_chicken"
    );

    private static final List<BlockState> DANCE_COLORS = List.of(
            Blocks.CONCRETE.white().defaultBlockState(),
            Blocks.CONCRETE.orange().defaultBlockState(),
            Blocks.CONCRETE.magenta().defaultBlockState(),
            Blocks.CONCRETE.lightBlue().defaultBlockState(),
            Blocks.CONCRETE.yellow().defaultBlockState(),
            Blocks.CONCRETE.lime().defaultBlockState(),
            Blocks.CONCRETE.pink().defaultBlockState(),
            Blocks.CONCRETE.gray().defaultBlockState(),
            Blocks.CONCRETE.lightGray().defaultBlockState(),
            Blocks.CONCRETE.cyan().defaultBlockState(),
            Blocks.CONCRETE.purple().defaultBlockState(),
            Blocks.CONCRETE.blue().defaultBlockState(),
            Blocks.CONCRETE.brown().defaultBlockState(),
            Blocks.CONCRETE.green().defaultBlockState(),
            Blocks.CONCRETE.red().defaultBlockState(),
            Blocks.CONCRETE.black().defaultBlockState()
    );

    private enum Phase {
        IDLE, JOINING, DANCING, SEAT_RUSH
    }

    private PartyGamesConfig config;
    private final Path dataDirectory;
    private final Path sessionsFile;
    private final Path pendingFile;
    private final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();
    private final Map<UUID, PendingReward> pendingRewards = new HashMap<>();
    private final Map<UUID, String> entrantNames = new HashMap<>();
    private final Map<UUID, Integer> awaitingPrizeCommandInput = new HashMap<>();
    private final Set<UUID> joinedPlayers = new LinkedHashSet<>();
    private final Set<UUID> alivePlayers = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final List<BlockPos> seatPositions = new ArrayList<>();
    private final List<BlockPos> spawnPositions = new ArrayList<>();
    private final List<ServerPlayer> pendingDisconnectedPlayers = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private ServerLevel eventLevel;
    private long nextAutomaticEventAt;
    private long phaseEndsAt;
    private long nextColorChangeAt;
    private int completedRounds;
    private int nextSpawnIndex;
    private Identifier activeMusicId;
    private boolean arenaPrepared;
    private boolean loggedMissingDimension;

    public PartyGamesManager(PartyGamesConfig config, Path dataDirectory) {
        this.config = config;
        this.dataDirectory = dataDirectory;
        this.sessionsFile = dataDirectory.resolve("sessions.json");
        this.pendingFile = dataDirectory.resolve("pending-rewards.json");
        loadPersistentData();
    }

    public void updateConfig(PartyGamesConfig config) {
        this.config = config;
        this.config.normalize();
        PartyGamesConfig.save(dataDirectory.resolve("config.json"), config);
    }

    public boolean isProtected(UUID uuid) {
        return joinedPlayers.contains(uuid);
    }

    public boolean isCommandsBlocked(ServerPlayer player) {
        return player != null && (PartyGamesMod.MOD_ID + ":event").equals(
                player.level().dimension().identifier().toString());
    }

    public boolean tryMountCushion(ServerPlayer player, Entity entity) {
        if (player == null || entity == null || phase != Phase.SEAT_RUSH
                || player.level() != eventLevel || !alivePlayers.contains(player.getUUID())
                || !isCushion(entity)) return false;

        if (!entity.getPassengers().isEmpty() && !entity.getPassengers().contains(player)) {
            player.sendSystemMessage(Component.literal("This cushion is already occupied.")
                    .withStyle(ChatFormatting.RED));
            return true;
        }
        if (entity.getPassengers().contains(player) || player.startRiding(entity, true, true)) {
            player.sendSystemMessage(Component.literal("You got the cushion!")
                    .withStyle(ChatFormatting.GREEN));
        }
        return true;
    }

    public boolean capturePendingPrizeCommand(ServerPlayer player, String rawCommand) {
        Integer place = awaitingPrizeCommandInput.remove(player.getUUID());
        if (place == null) return false;
        String command = rawCommand == null ? "" : rawCommand.trim();
        while (command.startsWith("/")) command = command.substring(1).trim();

        if (command.equalsIgnoreCase("cancel")) {
            player.sendSystemMessage(Component.literal("Command entry cancelled.")
                    .withStyle(ChatFormatting.YELLOW));
            openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, 0);
            return true;
        }
        if (command.isBlank() || command.length() > 2048) {
            player.sendSystemMessage(Component.literal(command.isBlank()
                    ? "No command entered. Type cancel to abort."
                    : "That command is too long. Please enter at most 2048 characters.")
                    .withStyle(ChatFormatting.RED));
            openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, 0);
            return true;
        }
        addPrizeCommand(player, place, command);
        return true;
    }

    private void requestPrizeCommandInput(ServerPlayer player, int place) {
        if (config.prizeForPlace(place) == null) return;
        awaitingPrizeCommandInput.put(player.getUUID(), place);
        player.closeContainer();
        player.sendSystemMessage(Component.literal("Enter the reward command in chat for " + placeName(place) + ".")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        player.sendSystemMessage(Component.literal("Type the command with or without /; it will be saved, not executed. Type cancel to abort.")
                .withStyle(ChatFormatting.GRAY));
    }

    public String status() {
        return "PartyGames phase: " + phase.name().toLowerCase()
                + " | joined: " + joinedPlayers.size()
                + " | alive: " + alivePlayers.size()
                + " | completed dance rounds: " + completedRounds;
    }

    public void openModifyMenu(ServerPlayer player) {
        openAdminMenu(player, PartyGamesAdminMenu.View.MAIN, 0, 0);
    }

    public void addPrizeCommand(ServerPlayer player, int place, String command) {
        if (place < 1 || place > 3) {
            player.sendSystemMessage(Component.literal("Prize place must be 1, 2 or 3.").withStyle(ChatFormatting.RED));
            return;
        }
        if (command == null || command.isBlank()) {
            player.sendSystemMessage(Component.literal("Enter a command after the place number.").withStyle(ChatFormatting.RED));
            return;
        }
        String value = command.trim();
        while (value.startsWith("/")) value = value.substring(1);
        PartyGamesConfig.Prize prize = config.prizeForPlace(place);
        if (prize == null) return;
        prize.commands.add(value);
        updateConfig(config);
        player.sendSystemMessage(Component.literal("Added command to the " + placeName(place) + " prize.").withStyle(ChatFormatting.GREEN));
        openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, Math.max(0, (prize.commands.size() - 1) / 45));
    }

    private void openAdminMenu(ServerPlayer player, PartyGamesAdminMenu.View view, int place, int page) {
        int size = (view == PartyGamesAdminMenu.View.MAIN || view == PartyGamesAdminMenu.View.PLACES) ? 27 : 54;
        PartyGamesAdminMenu.AdminContainer container = new PartyGamesAdminMenu.AdminContainer(size, view);
        ItemStack filler = menuItem(Blocks.STAINED_GLASS_PANE.black().asItem(), " ");
        for (int slot = 0; slot < size; slot++) {
            if (view == PartyGamesAdminMenu.View.ITEMS && slot >= 9 && slot <= 35) continue;
            container.setItem(slot, filler.copy());
        }

        MinecraftServer server = player.level().getServer();
        String title;
        switch (view) {
            case MAIN -> {
                title = "PartyGames - Admin";
                container.setItem(13, menuItem(Items.JUKEBOX, "Dance Floor - configure"));
                container.setItem(22, menuItem(Items.BOOK, "Choose a minigame"));
            }
            case PLACES -> {
                title = "Dance Floor - Rewards";
                container.setItem(11, menuItem(Items.GOLD_INGOT, "First place - items and commands"));
                container.setItem(13, menuItem(Items.IRON_INGOT, "Second place - items and commands"));
                container.setItem(15, menuItem(Items.COPPER_INGOT, "Third place - items and commands"));
                container.setItem(22, menuItem(Items.ARROW, "Back to minigames"));
            }
            case ITEMS -> {
                title = placeName(place) + " - Reward items";
                PartyGamesConfig.Prize prize = config.prizeForPlace(place);
                if (prize != null) {
                    int first = Math.max(0, page) * 9;
                    for (int slot = 0; slot < 9 && first + slot < prize.items.size(); slot++) {
                        ItemStack display = createStack(server, prize.items.get(first + slot));
                        if (!display.isEmpty()) container.setItem(slot, display);
                    }
                }
                container.setItem(45, menuItem(Blocks.WOOL.lime().asItem(), "Save added items"));
                container.setItem(46, menuItem(Items.BARRIER, "Remove all configured item rewards"));
                container.setItem(47, menuItem(Items.COMMAND_BLOCK, "View / add / remove commands"));
                container.setItem(49, menuItem(Items.ARROW, "Back to prize places"));
                container.setItem(50, menuItem(Items.SPECTRAL_ARROW, "Previous item page"));
                container.setItem(51, menuItem(Items.ARROW, "Next item page"));
                container.setItem(53, menuItem(Items.BOOK, "Put items in slots 10-36, then Save"));
            }
            case COMMANDS -> {
                title = placeName(place) + " - Reward commands";
                PartyGamesConfig.Prize prize = config.prizeForPlace(place);
                if (prize != null) {
                    int first = Math.max(0, page) * 45;
                    for (int slot = 0; slot < 45 && first + slot < prize.commands.size(); slot++) {
                        String command = prize.commands.get(first + slot);
                        String shown = command.length() > 58 ? command.substring(0, 55) + "..." : command;
                        container.setItem(slot, menuItem(Items.PAPER, "Click to remove: " + shown));
                    }
                }
                container.setItem(45, menuItem(Items.WRITABLE_BOOK, "Add a command"));
                container.setItem(46, menuItem(Items.ARROW, "Back to reward items"));
                container.setItem(47, menuItem(Items.SPECTRAL_ARROW, "Previous command page"));
                container.setItem(48, menuItem(Items.ARROW, "Next command page"));
                container.setItem(50, menuItem(Items.BOOK, "Click a command to remove it"));
            }
            default -> throw new IllegalStateException("Unknown PartyGames menu " + view);
        }

        PartyGamesAdminMenu.open(player, this, view, place, Math.max(0, page), container, title);
    }

    private ItemStack menuItem(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private String placeName(int place) {
        return switch (place) {
            case 1 -> "First place";
            case 2 -> "Second place";
            case 3 -> "Third place";
            default -> "Prize";
        };
    }

    void handleAdminMenuClick(ServerPlayer player, PartyGamesAdminMenu.View view, int place, int page,
                              int slot, int button, PartyGamesAdminMenu.AdminContainer container) {
        switch (view) {
            case MAIN -> {
                if (slot == 13) openAdminMenu(player, PartyGamesAdminMenu.View.PLACES, 0, 0);
            }
            case PLACES -> {
                if (slot == 11) openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, 1, 0);
                else if (slot == 13) openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, 2, 0);
                else if (slot == 15) openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, 3, 0);
                else if (slot == 22) openAdminMenu(player, PartyGamesAdminMenu.View.MAIN, 0, 0);
            }
            case ITEMS -> {
                PartyGamesConfig.Prize prize = config.prizeForPlace(place);
                if (prize == null) return;
                if (slot >= 0 && slot <= 8) {
                    int index = page * 9 + slot;
                    if (index < prize.items.size()) {
                        PartyGamesConfig.ItemReward removed = prize.items.remove(index);
                        updateConfig(config);
                        player.sendSystemMessage(Component.literal("Removed configured item reward: " + removed.id)
                                .withStyle(ChatFormatting.YELLOW));
                        openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, place,
                                Math.min(page, Math.max(0, (prize.items.size() - 1) / 9)));
                    }
                } else if (slot == 45) {
                    saveAddedRewardItems(player, place, container);
                } else if (slot == 46) {
                    returnUncommittedItems(player, container);
                    prize.items.clear();
                    updateConfig(config);
                    player.sendSystemMessage(Component.literal("Removed all configured item rewards for " + placeName(place) + ".")
                            .withStyle(ChatFormatting.YELLOW));
                    openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, place, 0);
                } else if (slot == 47) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, 0);
                } else if (slot == 49) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.PLACES, 0, 0);
                } else if (slot == 50 && page > 0) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, place, page - 1);
                } else if (slot == 51 && (page + 1) * 9 < prize.items.size()) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, place, page + 1);
                } else if (slot == 53) {
                    player.sendSystemMessage(Component.literal("Shift-click items from your inventory into the empty middle slots. They are saved as exact item stacks, including custom components, then returned to you.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            case COMMANDS -> {
                PartyGamesConfig.Prize prize = config.prizeForPlace(place);
                if (prize == null) return;
                if (slot >= 0 && slot < 45) {
                    int index = page * 45 + slot;
                    if (index < prize.commands.size()) {
                        String removed = prize.commands.remove(index);
                        updateConfig(config);
                        player.sendSystemMessage(Component.literal("Removed reward command: " + removed)
                                .withStyle(ChatFormatting.YELLOW));
                        openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place,
                                Math.min(page, Math.max(0, (prize.commands.size() - 1) / 45)));
                    }
                } else if (slot == 45) {
                    requestPrizeCommandInput(player, place);
                } else if (slot == 46) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.ITEMS, place, 0);
                } else if (slot == 47 && page > 0) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, page - 1);
                } else if (slot == 48 && (page + 1) * 45 < prize.commands.size()) {
                    openAdminMenu(player, PartyGamesAdminMenu.View.COMMANDS, place, page + 1);
                }
            }
        }
    }

    private void saveAddedRewardItems(ServerPlayer player, int place, PartyGamesAdminMenu.AdminContainer container) {
        PartyGamesConfig.Prize prize = config.prizeForPlace(place);
        if (prize == null) return;
        int added = 0;
        MinecraftServer server = player.level().getServer();
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ItemStack savedStack = stack.copy();
            String itemId = BuiltInRegistries.ITEM.getKey(savedStack.getItem()).toString();
            JsonElement stackData = ItemStack.CODEC.encodeStart(
                            server.registryAccess().createSerializationContext(JsonOps.INSTANCE), savedStack)
                    .resultOrPartial(error -> PartyGamesMod.LOGGER.warn("Could not encode reward item {}: {}", itemId, error))
                    .orElse(null);
            prize.items.add(new PartyGamesConfig.ItemReward(itemId, savedStack.getCount(), stackData));
            added++;

            returnItemToPlayer(player, savedStack.copy());
            container.setItem(slot, ItemStack.EMPTY);
        }
        updateConfig(config);
        PartyGamesAdminMenu current = player.containerMenu instanceof PartyGamesAdminMenu menu ? menu : null;
        if (current != null) current.markCommitted();
        player.closeContainer();
        player.sendSystemMessage(Component.literal("Saved " + added + " reward item stack(s), including custom data, for " + placeName(place) + ".")
                .withStyle(ChatFormatting.GREEN));
    }

    void returnUncommittedItems(ServerPlayer player, PartyGamesAdminMenu.AdminContainer container) {
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            returnItemToPlayer(player, stack.copy());
            container.setItem(slot, ItemStack.EMPTY);
        }
    }

    private void returnItemToPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().add(stack);
        if (stack.isEmpty()) return;

        // Never discard an admin's item if their inventory filled while the editor was open.
        MinecraftServer server = player.level().getServer();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        JsonElement stackData = ItemStack.CODEC.encodeStart(
                        server.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack.copy())
                .resultOrPartial(error -> PartyGamesMod.LOGGER.warn("Could not encode returned editor item {}: {}", itemId, error))
                .orElse(null);
        pendingRewards.computeIfAbsent(player.getUUID(), ignored -> new PendingReward())
                .items.add(new PendingItem(itemId, stack.getCount(), stackData));
        savePendingRewards();
        player.sendSystemMessage(Component.literal("Your inventory was full; remaining editor items are saved. Use /partygames retrieve when you have space.")
                .withStyle(ChatFormatting.GOLD));
    }

    public void onServerStarted(MinecraftServer server) {
        loadPersistentData();
        nextAutomaticEventAt = System.currentTimeMillis() + config.eventIntervalMinutes * 60_000L;
        refreshEventDimension(server);
        if (eventLevel == null) {
            PartyGamesMod.LOGGER.error("PartyGames event dimension is not loaded. Check the built-in event_dimension data pack and restart the world.");
        } else {
            prepareArena();
        }
        PartyGamesMod.LOGGER.info("PartyGames is ready. Automatic events are scheduled every {} minute(s).",
                config.eventIntervalMinutes);
    }

    public void onServerStopping(MinecraftServer server) {
        // Do not award prizes or teleport players while Minecraft is shutting down.
        // Keep returnPoints persisted so players who were in the arena are restored on next login.
        stopMusic();
        purgeDisconnectedPlayerEntities(server);
        clearCushions();
        if (eventLevel != null) paintFloor(Blocks.CONCRETE.black().defaultBlockState());

        phase = Phase.IDLE;
        joinedPlayers.clear();
        alivePlayers.clear();
        seatPositions.clear();
        spawnPositions.clear();
        eliminationOrder.clear();
        entrantNames.clear();
        awaitingPrizeCommandInput.clear();

        saveSessions();
        savePendingRewards();
        PartyGamesMod.LOGGER.info("PartyGames shut down cleanly; saved return locations for reconnect recovery.");
    }

    public void tick(MinecraftServer server) {
        // A lost connection can occasionally leave a stale player entity in a level even after
        // PlayerList no longer knows about that player. Sweep it on the server thread.
        purgeDisconnectedPlayerEntities(server);

        // Keep event participants fed and rescue them if they escape the arena and fall into the void.
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && player.level() == eventLevel) {
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0F);
                if (phase != Phase.IDLE && player.getY() < 0.0D) {
                    rescuePlayerToArena(player);
                }
            }
        }

        long now = System.currentTimeMillis();

        if (eventLevel == null) {
            refreshEventDimension(server);
            if (eventLevel != null && !arenaPrepared) prepareArena();
        }

        if (phase == Phase.IDLE) {
            // No player should remain in the reserved event dimension between games.
            // This also repairs sessions left behind by an interrupted return/finish.
            recoverOrphanedEventPlayers(server);
            if (now >= nextAutomaticEventAt) {
                startRegistration(server, true);
            }
            return;
        }

        if (phase == Phase.JOINING && now >= phaseEndsAt) {
            if (joinedPlayers.size() < config.minimumPlayers) {
                broadcast(server, "PartyGames was cancelled because not enough players joined.");
                finishEvent(server, null, false);
            } else {
                broadcast(server, "Registration is closed! Dance Floor starts now.");
                startDanceRound(server);
            }
            return;
        }

        if (phase == Phase.DANCING) {
            if (now >= phaseEndsAt) {
                beginSeatRush(server);
            } else if (now >= nextColorChangeAt) {
                paintRandomFloor();
                nextColorChangeAt = now + 700L;
            }
            return;
        }

        if (phase == Phase.SEAT_RUSH && now >= phaseEndsAt) {
            resolveSeatRush(server);
        }
    }

    public boolean startRegistration(MinecraftServer server, boolean automatic) {
        if (phase != Phase.IDLE) {
            broadcast(server, "A PartyGames event is already in progress.");
            return false;
        }
        refreshEventDimension(server);
        if (eventLevel == null) {
            broadcast(server, "PartyGames could not start: the event dimension is unavailable. Check the server log and restart after installing PartyGames.");
            return false;
        }
        if (!arenaPrepared) prepareArena();

        if (!arenaPrepared) {
            broadcast(server, "PartyGames could not prepare the dance floor.");
            return false;
        }

        phase = Phase.JOINING;
        joinedPlayers.clear();
        alivePlayers.clear();
        eliminationOrder.clear();
        entrantNames.clear();
        seatPositions.clear();
        completedRounds = 0;
        nextSpawnIndex = 0;
        spawnPositions.clear();
        spawnPositions.addAll(floorPositions());
        Collections.shuffle(spawnPositions, RANDOM);
        phaseEndsAt = System.currentTimeMillis() + config.joinDurationSeconds * 1000L;
        nextAutomaticEventAt = System.currentTimeMillis() + config.eventIntervalMinutes * 60_000L;

        announceParticipation(server);
        PartyGamesMod.LOGGER.info("PartyGames registration opened (automatic={}).", automatic);
        return true;
    }

    public int join(ServerPlayer player) {
        if (phase != Phase.JOINING) {
            player.sendSystemMessage(Component.literal("There is no PartyGames event accepting players right now.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (joinedPlayers.contains(uuid)) {
            player.sendSystemMessage(Component.literal("You have already joined this event."));
            return 1;
        }
        // A login can race with /partygames join. Restore the previous session before
        // taking a new snapshot so an old event-dimension state cannot become the baseline.
        if (player.level() == eventLevel) {
            MinecraftServer server = player.level().getServer();
            if (returnPoints.containsKey(uuid)) {
                returnPlayer(server, player);
            } else {
                recoverPlayerWithoutReturnPoint(server, player, "join requested while still inside the event dimension");
            }
        }

        if (joinedPlayers.size() >= 200) {
            player.sendSystemMessage(Component.literal("This event has reached its player limit."));
            return 0;
        }

        // Preserve the original pre-event snapshot if reconnect recovery is still pending.
        returnPoints.putIfAbsent(uuid, snapshot(player));
        entrantNames.put(uuid, player.getGameProfile().name());
        joinedPlayers.add(uuid);
        alivePlayers.add(uuid);
        saveSessions();

        // Damage is prevented by ProtectedPlayerMixin while the UUID is in joinedPlayers.
        // Do not set permanent invulnerability or permanently mutate player abilities:
        // Minecraft saves player state on disconnect, which can strand them after rejoining.
        player.setGameMode(GameType.SURVIVAL);
        player.setPermanentlyInvulnerable(false);
        // Keep the server's ability flags consistent with Survival even if this player
        // disconnected while an older build had left mayBuild=false in the saved data.
        // Interaction callbacks and mixins, not mayBuild=false, enforce the minigame lock.
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();

        BlockPos spawn = spawnPositions.isEmpty()
                ? new BlockPos(0, FLOOR_Y + 1, 0)
                : spawnPositions.get(nextSpawnIndex++ % spawnPositions.size()).above();
        player.teleportTo(eventLevel, spawn.getX() + 0.5, FLOOR_Y + 1.0, spawn.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), true);
        synchronizeClientGameMode(player, GameType.SURVIVAL);
        player.onUpdateAbilities();
        player.sendSystemMessage(Component.literal("You joined PartyGames: Dance Floor! Your inventory is protected.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    public int retrieve(ServerPlayer player) {
        deliverPending(player, true);
        return 1;
    }

    public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!joinedPlayers.contains(uuid)) {
            if (returnPoints.containsKey(uuid)) {
                returnPlayer(server, player);
            } else if (eventLevel != null && player.level() == eventLevel) {
                // The saved snapshot may be missing/corrupt after an interrupted session.
                // Do not leave the player stuck in the protected event dimension.
                recoverPlayerWithoutReturnPoint(server, player, "reconnected without a saved return point");
            }
        }
        deliverPending(player, false);
    }

    public void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        joinedPlayers.remove(uuid);
        boolean wasAlive = alivePlayers.remove(uuid);
        awaitingPrizeCommandInput.remove(uuid);
        if (wasAlive && phase != Phase.IDLE && phase != Phase.JOINING) {
            eliminationOrder.add(uuid);
        }
        player.stopRiding();
        if (!pendingDisconnectedPlayers.contains(player)) pendingDisconnectedPlayers.add(player);

        saveSessions();
        if (phase == Phase.JOINING) {
            broadcast(server, player.getGameProfile().name() + " left the event registration.");
        }
        // Purging happens during the next server tick, after vanilla has removed the connection.
    }

    private void purgeDisconnectedPlayerEntities(MinecraftServer server) {
        // This is normally called only when at least one disconnect is queued, so the full
        // entity scan happens on disconnect/shutdown rather than on every server tick.
        if (pendingDisconnectedPlayers.isEmpty()) return;

        Set<ServerPlayer> candidates = new LinkedHashSet<>(pendingDisconnectedPlayers);
        pendingDisconnectedPlayers.clear();

        // ServerLevel.players() is only the active player list. A half-removed player can be
        // absent from that list while its entity is still registered in the level's entity manager.
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer levelPlayer
                        && server.getPlayerList().getPlayer(levelPlayer.getUUID()) != levelPlayer) {
                    candidates.add(levelPlayer);
                }
            }
        }

        for (ServerPlayer stalePlayer : candidates) {
            ServerPlayer listedPlayer = server.getPlayerList().getPlayer(stalePlayer.getUUID());
            if (listedPlayer == stalePlayer) {
                // The callback can fire before PlayerList finishes removing the connection.
                // Defer this specific instance until the next tick instead of dropping the cleanup.
                pendingDisconnectedPlayers.add(stalePlayer);
                continue;
            }

            UUID uuid = stalePlayer.getUUID();
            if (!(stalePlayer.level() instanceof ServerLevel level)) continue;

            List<ServerPlayer> viewers = new ArrayList<>(level.players());
            stalePlayer.stopRiding();

            if (!stalePlayer.isRemoved()) {
                level.removePlayerImmediately(stalePlayer, Entity.RemovalReason.DISCARDED);
            }

            // Explicitly tell remaining clients to forget the entity. This covers the visual
            // ghost case where the entity tracker/list became inconsistent during disconnect.
            ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(stalePlayer.getId());
            for (ServerPlayer viewer : viewers) {
                if (viewer != stalePlayer
                        && server.getPlayerList().getPlayer(viewer.getUUID()) == viewer) {
                    viewer.connection.send(removePacket);
                }
            }

            PartyGamesMod.LOGGER.warn(
                    "Removed stale player entity {} ({}) from dimension {} because it no longer matches an online connection.",
                    stalePlayer.getGameProfile().name(), uuid, level.dimension().identifier());

            if (listedPlayer == null) {
                joinedPlayers.remove(uuid);
                alivePlayers.remove(uuid);
                awaitingPrizeCommandInput.remove(uuid);
            }
        }
    }

    private void rescuePlayerToArena(ServerPlayer player) {
        if (eventLevel == null || player.level() != eventLevel) return;

        List<BlockPos> positions = floorPositions();
        if (positions.isEmpty()) {
            positions = List.of(new BlockPos(0, FLOOR_Y, 0));
        }
        int index = Math.floorMod(player.getUUID().hashCode(), positions.size());
        BlockPos safeFloor = positions.get(index);

        player.stopRiding();
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.teleportTo(eventLevel, safeFloor.getX() + 0.5D, FLOOR_Y + 1.0D,
                safeFloor.getZ() + 0.5D, Set.of(), player.getYRot(), player.getXRot(), true);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();

        player.sendSystemMessage(Component.literal(
                "You fell out of the arena and were returned safely to the Dance Floor.")
                .withStyle(ChatFormatting.YELLOW));
        PartyGamesMod.LOGGER.warn("Rescued PartyGames participant {} after falling below Y=0.",
                player.getGameProfile().name());
    }

    public void cancelEvent(MinecraftServer server) {
        if (phase == Phase.IDLE) {
            broadcast(server, "There is no active PartyGames event.");
            return;
        }
        broadcast(server, "PartyGames was stopped by an administrator.");
        finishEvent(server, null, false);
    }

    private void startDanceRound(MinecraftServer server) {
        if (alivePlayers.size() <= 1) {
            UUID winner = alivePlayers.stream().findFirst().orElse(null);
            finishEvent(server, winner, winner != null);
            return;
        }
        phase = Phase.DANCING;
        clearCushions();
        seatPositions.clear();
        stopMusic();

        List<String> tracks = config.musicSounds;
        String selected = tracks.get(ThreadLocalRandom.current().nextInt(tracks.size()));
        Identifier soundId = Identifier.tryParse(selected);
        SoundEvent sound = soundId == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(soundId);
        if (sound != null && soundId != null) {
            activeMusicId = soundId;
            for (UUID uuid : new ArrayList<>(joinedPlayers)) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && player.level() == eventLevel) {
                    playSoundToPlayer(player, sound, SoundSource.RECORDS, 1.0F, 1.0F);
                }
            }
        } else {
            PartyGamesMod.LOGGER.error("PartyGames could not find selected music disc sound: {}", selected);
        }

        // The floor is black during registration and changes only when the music begins.
        paintRandomFloor();
        int seconds = ThreadLocalRandom.current().nextInt(10, 31);
        phaseEndsAt = System.currentTimeMillis() + seconds * 1000L;
        nextColorChangeAt = System.currentTimeMillis() + 700L;
        broadcast(server, "Dance Floor round " + (completedRounds + 1) + "! Keep moving while the music plays!");
    }

    private void beginSeatRush(MinecraftServer server) {
        stopMusic();
        phase = Phase.SEAT_RUSH;
        paintFloor(Blocks.CONCRETE.black().defaultBlockState());
        clearCushions();
        List<BlockPos> available = floorPositions();
        Collections.shuffle(available, RANDOM);

        int seatsPercent = completedRounds < config.earlyRatioRounds
                ? config.earlySeatPercent : config.lateSeatPercent;
        int desiredSeats = Math.max(1, (int) Math.floor(alivePlayers.size() * seatsPercent / 100.0));
        desiredSeats = Math.min(desiredSeats, available.size());

        seatPositions.clear();
        seatPositions.addAll(available.subList(0, desiredSeats));
        for (BlockPos pos : seatPositions) {
            spawnCushion(pos);
        }

        phaseEndsAt = System.currentTimeMillis() + config.seatTimeSeconds * 1000L;
        broadcast(server, "Music stopped! Right-click a cushion to sit! " + desiredSeats + " cushion(s) for "
                + alivePlayers.size() + " player(s) — " + config.seatTimeSeconds + " seconds!");
    }

    private void resolveSeatRush(MinecraftServer server) {
        Set<BlockPos> occupiedSeats = new HashSet<>();
        List<UUID> shuffledPlayers = new ArrayList<>(alivePlayers);
        Collections.shuffle(shuffledPlayers, RANDOM);
        List<UUID> survivors = new ArrayList<>();
        List<UUID> eliminated = new ArrayList<>();

        for (UUID uuid : shuffledPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || player.level() != eventLevel) {
                eliminated.add(uuid);
                continue;
            }
            Entity vehicle = player.getVehicle();
            BlockPos floorUnderPlayer = isCushion(vehicle)
                    ? new BlockPos(net.minecraft.core.BlockPos.containing(vehicle.getX(), FLOOR_Y, vehicle.getZ()).getX(),
                                   FLOOR_Y,
                                   net.minecraft.core.BlockPos.containing(vehicle.getX(), FLOOR_Y, vehicle.getZ()).getZ())
                    : player.blockPosition().below();
            if (seatPositions.contains(floorUnderPlayer) && occupiedSeats.add(floorUnderPlayer)) {
                survivors.add(uuid);
            } else {
                eliminated.add(uuid);
            }
        }

        clearCushions();

        for (UUID uuid : eliminated) {
            alivePlayers.remove(uuid);
            eliminationOrder.add(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("You were eliminated from Dance Floor!")
                        .withStyle(ChatFormatting.RED));
                returnPlayer(server, player);
            } else {
                joinedPlayers.remove(uuid);
            }
        }

        alivePlayers.clear();
        alivePlayers.addAll(survivors);
        completedRounds++;
        seatPositions.clear();

        broadcast(server, survivors.size() + " player(s) survived round " + completedRounds + ".");
        if (alivePlayers.size() <= 1) {
            UUID winner = alivePlayers.stream().findFirst().orElse(null);
            finishEvent(server, winner, winner != null);
        } else {
            startDanceRound(server);
        }
    }

    private void finishEvent(MinecraftServer server, UUID winner, boolean awardPrizes) {
        stopMusic();
        clearCushions();
        paintFloor(Blocks.CONCRETE.black().defaultBlockState());
        if (awardPrizes && winner != null) {
            List<UUID> ranking = new ArrayList<>();
            ranking.add(winner);
            for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
                UUID uuid = eliminationOrder.get(i);
                if (!ranking.contains(uuid)) ranking.add(uuid);
            }
            for (int index = 0; index < Math.min(3, ranking.size()); index++) {
                UUID uuid = ranking.get(index);
                String name = entrantNames.getOrDefault(uuid, uuid.toString());
                givePrize(server, uuid, name, index + 1, config.prizeForPlace(index + 1));
            }
            String winnerName = entrantNames.getOrDefault(winner, "A player");
            broadcast(server, winnerName + " won PartyGames: Dance Floor!");
        } else if (winner == null && !joinedPlayers.isEmpty()) {
            broadcast(server, "PartyGames ended without a winner.");
        }

        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) returnPlayer(server, player);
        }

        phase = Phase.IDLE;
        joinedPlayers.clear();
        alivePlayers.clear();
        seatPositions.clear();
        spawnPositions.clear();
        eliminationOrder.clear();
        entrantNames.clear();
        completedRounds = 0;
        phaseEndsAt = 0L;
        nextAutomaticEventAt = System.currentTimeMillis() + config.eventIntervalMinutes * 60_000L;
        saveSessions();
        savePendingRewards();
        broadcast(server, "PartyGames event finished. Next event in about " + config.eventIntervalMinutes + " minute(s).");
    }

    private void givePrize(MinecraftServer server, UUID uuid, String name, int place, PartyGamesConfig.Prize prize) {
        if (prize == null) return;
        PendingReward saved = pendingRewards.computeIfAbsent(uuid, ignored -> new PendingReward());
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);

        if (player == null) {
            for (String command : prize.commands) saved.commands.add(command.replace("%place%", Integer.toString(place)));
            for (PartyGamesConfig.ItemReward item : prize.items) {
                if (item != null && item.id != null && item.count > 0) saved.items.add(new PendingItem(item.id, item.count, item.stackData));
            }
            savePendingRewards();
            return;
        }

        for (String command : prize.commands) {
            executeRewardCommand(player, command, place);
        }
        for (PartyGamesConfig.ItemReward rewardItem : prize.items) {
            if (rewardItem == null || rewardItem.id == null || rewardItem.count < 1) continue;
            ItemStack stack = createStack(server, rewardItem);
            if (stack.isEmpty()) {
                PartyGamesMod.LOGGER.warn("Ignoring invalid PartyGames reward item id: {}", rewardItem.id);
                continue;
            }
            player.getInventory().add(stack);
            if (!stack.isEmpty()) saved.items.add(new PendingItem(rewardItem.id, stack.getCount(), rewardItem.stackData));
        }
        if (saved.items.isEmpty() && saved.commands.isEmpty()) pendingRewards.remove(uuid);
        if (!saved.items.isEmpty()) {
            player.sendSystemMessage(Component.literal("Some prize items did not fit in your inventory. Use /partygames retrieve.")
                    .withStyle(ChatFormatting.GOLD));
        }
        savePendingRewards();
    }

    private void deliverPending(ServerPlayer player, boolean showMessages) {
        UUID uuid = player.getUUID();
        PendingReward saved = pendingRewards.get(uuid);
        if (saved == null) {
            if (showMessages) player.sendSystemMessage(Component.literal("You have no pending PartyGames rewards."));
            return;
        }

        for (String command : new ArrayList<>(saved.commands)) {
            executeRewardCommand(player, command, 0);
        }
        saved.commands.clear();

        List<PendingItem> leftovers = new ArrayList<>();
        for (PendingItem reward : saved.items) {
            ItemStack stack = createStack(player.level().getServer(), new PartyGamesConfig.ItemReward(reward.id, reward.count, reward.stackData));
            if (stack.isEmpty()) continue;
            player.getInventory().add(stack);
            if (!stack.isEmpty()) leftovers.add(new PendingItem(reward.id, stack.getCount(), reward.stackData));
        }
        saved.items.clear();
        saved.items.addAll(leftovers);

        if (saved.items.isEmpty() && saved.commands.isEmpty()) {
            pendingRewards.remove(uuid);
            if (showMessages) player.sendSystemMessage(Component.literal("All pending PartyGames rewards were delivered.")
                    .withStyle(ChatFormatting.GREEN));
        } else if (showMessages) {
            player.sendSystemMessage(Component.literal("Some rewards are still pending. Make inventory space and run /partygames retrieve again.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        savePendingRewards();
    }

    private void executeRewardCommand(ServerPlayer player, String configuredCommand, int place) {
        if (configuredCommand == null || configuredCommand.isBlank()) return;
        String playerName = player.getGameProfile().name();
        String command = configuredCommand
                .replace("%player%", playerName)
                .replace("%uuid%", player.getUUID().toString())
                .replace("%place%", Integer.toString(place));
        while (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) return;
        try {
            // Execute with the winner as the command entity and position. @s, @p and playsound targets
            // then resolve relative to the actual winner, while permission still comes from the server.
            player.level().getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withMaximumPermission(PermissionSet.ALL_PERMISSIONS), command);
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not execute PartyGames reward command for {}: {}", playerName, command, e);
        }
    }

    private ItemStack createStack(MinecraftServer server, PartyGamesConfig.ItemReward reward) {
        if (reward.stackData != null && !reward.stackData.isJsonNull()) {
            try {
                ItemStack decoded = ItemStack.CODEC.parse(
                        server.registryAccess().createSerializationContext(JsonOps.INSTANCE), reward.stackData)
                        .resultOrPartial(error -> PartyGamesMod.LOGGER.warn("Could not decode PartyGames reward stack: {}", error))
                        .orElse(ItemStack.EMPTY);
                if (!decoded.isEmpty()) {
                    decoded.setCount(Math.max(1, Math.min(reward.count, 2304)));
                    return decoded;
                }
            } catch (Exception e) {
                PartyGamesMod.LOGGER.warn("Could not decode stored PartyGames reward item {}", reward.id, e);
            }
        }
        return createStack(server, reward.id, reward.count);
    }

    private ItemStack createStack(MinecraftServer server, String itemId, int count) {
        try {
            Identifier id = Identifier.tryParse(itemId);
            if (id == null) return ItemStack.EMPTY;
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) return ItemStack.EMPTY;
            return new ItemStack(item, Math.min(Math.max(1, count), 2304));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private void returnPlayer(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        ReturnPoint point = returnPoints.get(uuid);
        if (point == null) {
            // Returning without a snapshot must still restore a usable player state.
            // Otherwise a player can be left with mayBuild=false and be unable to play.
            recoverPlayerWithoutReturnPoint(server, player, "return point was missing at event end");
            return;
        }

        // The event dimension is reserved for the minigame. If a previous version
        // accidentally saved it as the return destination, do not restore the broken
        // event abilities/coordinates from that snapshot.
        if (point.dimension == null || point.dimension.equals(EVENT_DIMENSION.identifier().toString())) {
            recoverPlayerWithoutReturnPoint(server, player, "saved return point targets the event dimension");
            return;
        }

        ServerLevel destination = null;
        try {
            Identifier dimensionId = Identifier.tryParse(point.dimension);
            if (dimensionId != null) {
                destination = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            }
        } catch (Exception ignored) {
            // A malformed or unloaded dimension requires emergency recovery below.
        }
        if (destination == null) {
            recoverPlayerWithoutReturnPoint(server, player, "saved return dimension is unavailable: " + point.dimension);
            return;
        }

        player.teleportTo(destination, point.x, point.y, point.z, Set.of(), point.yaw, point.pitch, true);
        GameType restoredGameMode = GameType.byName(point.gameMode, GameType.SURVIVAL);
        player.setGameMode(restoredGameMode);

        // Older builds could save both permanent invulnerability and mayBuild=false after
        // the minigame had already modified the player. Treat that combination in Survival
        // as corrupted event state instead of restoring it as the player's baseline.
        boolean legacyCorruptSurvivalSnapshot = restoredGameMode.isSurvival() && !point.mayBuild;
        player.setPermanentlyInvulnerable(point.invulnerable && !legacyCorruptSurvivalSnapshot);

        // In Survival and Creative, mayBuild=false is invalid and can leave the server
        // refusing block interaction.
        player.getAbilities().mayBuild = point.mayBuild
                || restoredGameMode.isSurvival()
                || restoredGameMode.isCreative();
        player.getAbilities().instabuild = point.instabuild;
        player.getAbilities().mayfly = point.mayfly;
        player.getAbilities().flying = point.flying && point.mayfly;
        player.onUpdateAbilities();
        synchronizeClientGameMode(player, restoredGameMode);
        player.sendSystemMessage(Component.literal("You have been returned from PartyGames.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        returnPoints.remove(uuid);
        joinedPlayers.remove(uuid);
        alivePlayers.remove(uuid);
        saveSessions();
    }

    private void recoverOrphanedEventPlayers(MinecraftServer server) {
        if (eventLevel == null) return;

        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.level() != eventLevel || joinedPlayers.contains(player.getUUID())) continue;

            UUID uuid = player.getUUID();
            if (returnPoints.containsKey(uuid)) {
                returnPlayer(server, player);
            } else {
                recoverPlayerWithoutReturnPoint(server, player, "orphaned in the event dimension while no game is active");
            }
        }
    }

    private void recoverPlayerWithoutReturnPoint(MinecraftServer server, ServerPlayer player, String reason) {
        UUID uuid = player.getUUID();
        ServerLevel destination = server.overworld();
        // In 26.3 the world spawn is exposed through the level's respawn data.
        BlockPos spawn = destination.getLevelData().getRespawnData().pos();

        player.stopRiding();
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(destination, spawn.getX() + 0.5D, spawn.getY() + 1.0D,
                spawn.getZ() + 0.5D, Set.of(), player.getYRot(), player.getXRot(), true);
        player.setGameMode(GameType.SURVIVAL);
        player.setPermanentlyInvulnerable(false);
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        synchronizeClientGameMode(player, GameType.SURVIVAL);

        joinedPlayers.remove(uuid);
        alivePlayers.remove(uuid);
        awaitingPrizeCommandInput.remove(uuid);
        returnPoints.remove(uuid);
        saveSessions();

        player.sendSystemMessage(Component.literal(
                "PartyGames recovered your player state and returned you to the Overworld spawn.")
                .withStyle(ChatFormatting.YELLOW));
        PartyGamesMod.LOGGER.warn("Recovered player {} to the Overworld spawn: {}.",
                player.getGameProfile().name(), reason);
    }

    private void synchronizeClientGameMode(ServerPlayer player, GameType gameMode) {
        // CHANGE_GAME_MODE refreshes the client's local interaction rules even if the
        // server was already in Survival and setGameMode therefore emitted no change.
        player.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.CHANGE_GAME_MODE, (float) gameMode.getId()));
    }

    private ReturnPoint snapshot(ServerPlayer player) {
        ReturnPoint point = new ReturnPoint();
        point.dimension = player.level().dimension().identifier().toString();
        point.x = player.getX();
        point.y = player.getY();
        point.z = player.getZ();
        point.yaw = player.getYRot();
        point.pitch = player.getXRot();
        point.gameMode = player.gameMode().getName();
        point.invulnerable = player.isPermanentlyInvulnerable();
        point.mayBuild = player.getAbilities().mayBuild;
        point.instabuild = player.getAbilities().instabuild;
        point.mayfly = player.getAbilities().mayfly;
        point.flying = player.getAbilities().flying;
        return point;
    }

    private void refreshEventDimension(MinecraftServer server) {
        eventLevel = server.getLevel(EVENT_DIMENSION);
        if (eventLevel == null) {
            if (!loggedMissingDimension) {
                PartyGamesMod.LOGGER.warn("Dimension partygames:event is not available yet. It is supplied by the always-enabled built-in datapack.");
                loggedMissingDimension = true;
            }
            return;
        }
        loggedMissingDimension = false;
    }

    private void prepareArena() {
        if (eventLevel == null) return;
        clearCushions();
        int radius = config.arenaRadius;
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState blackFloor = Blocks.CONCRETE.black().defaultBlockState();

        // Dedicated void arena: black concrete floor, tall invisible barrier walls and a roof.
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                boolean wall = Math.abs(x) == radius + 1 || Math.abs(z) == radius + 1;
                if (wall) {
                    for (int y = FLOOR_Y; y < ROOF_Y; y++) {
                        eventLevel.setBlockAndUpdate(new BlockPos(x, y, z), barrier);
                    }
                } else {
                    eventLevel.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), blackFloor);
                    for (int y = FLOOR_Y + 1; y < ROOF_Y; y++) {
                        eventLevel.setBlockAndUpdate(new BlockPos(x, y, z), air);
                    }
                }
            }
        }

        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                eventLevel.setBlockAndUpdate(new BlockPos(x, ROOF_Y, z), barrier);
            }
        }
        arenaPrepared = true;
    }

    private List<BlockPos> floorPositions() {
        List<BlockPos> positions = new ArrayList<>();
        int radius = config.arenaRadius;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                positions.add(new BlockPos(x, FLOOR_Y, z));
            }
        }
        return positions;
    }

    private void paintRandomFloor() {
        if (eventLevel == null) return;
        List<BlockPos> positions = floorPositions();
        for (BlockPos pos : positions) {
            BlockState state = DANCE_COLORS.get(RANDOM.nextInt(DANCE_COLORS.size()));
            eventLevel.setBlockAndUpdate(pos, state);
        }
    }

    private void paintFloor(BlockState state) {
        if (eventLevel == null) return;
        for (BlockPos pos : floorPositions()) {
            eventLevel.setBlockAndUpdate(pos, state);
        }
    }

    private void stopMusic() {
        if (activeMusicId == null) return;
        ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(activeMusicId, SoundSource.RECORDS);
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = eventLevel == null ? null : eventLevel.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) player.connection.send(packet);
        }
        activeMusicId = null;
    }

    private void announceParticipation(MinecraftServer server) {
        Component announcement = Component.literal("PARTY GAMES ")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true))
                .append(Component.literal("Dance Floor is open! ")
                        .withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("Click Participate")
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/partygames join"))))
                .append(Component.literal(" or type ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/partygames join")
                        .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)));
        server.getPlayerList().broadcastSystemMessage(announcement, false);

        Identifier chimeId = Identifier.fromNamespaceAndPath("minecraft", "block.note_block.chime");
        SoundEvent chime = BuiltInRegistries.SOUND_EVENT.getValue(chimeId);
        if (chime != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                playSoundToPlayer(player, chime, SoundSource.MASTER, 0.8F, 1.15F);
            }
        }
    }

    private Component partyGamesMessage(String message) {
        MutableComponent result = Component.literal("PARTY GAMES ")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true));
        Matcher matcher = NUMBER_PATTERN.matcher(message);
        int previous = 0;
        while (matcher.find()) {
            if (matcher.start() > previous) {
                result = result.append(Component.literal(message.substring(previous, matcher.start()))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            result = result.append(Component.literal(matcher.group())
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)));
            previous = matcher.end();
        }
        if (previous < message.length()) {
            result = result.append(Component.literal(message.substring(previous))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return result;
    }

    private void playSoundToPlayer(ServerPlayer player, SoundEvent sound, SoundSource source,
                                   float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                source,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, RANDOM.nextLong()));
    }

    private boolean isCushion(Entity entity) {
        if (entity == null) return false;
        Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return typeId != null && "minecraft:cushion".equals(typeId.toString());
    }

    private Entity findCushion(BlockPos floorPos) {
        if (eventLevel == null) return null;
        AABB box = new AABB(floorPos.getX(), FLOOR_Y, floorPos.getZ(),
                floorPos.getX() + 1.0, FLOOR_Y + 3.0, floorPos.getZ() + 1.0);
        return eventLevel.getEntitiesOfClass(Entity.class, box, this::isCushion).stream().findFirst().orElse(null);
    }

    private void keepPlayersSeated(MinecraftServer server) {
        for (UUID uuid : new ArrayList<>(alivePlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || player.level() != eventLevel) continue;
            if (isCushion(player.getVehicle())) continue;

            BlockPos nearestSeat = null;
            double nearestDistance = Double.MAX_VALUE;
            for (BlockPos seat : seatPositions) {
                double distance = player.distanceToSqr(seat.getX() + 0.5, FLOOR_Y + 1.0, seat.getZ() + 0.5);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSeat = seat;
                }
            }
            // Remount players who sneak off a cushion; a small radius allows the normal seat offset.
            if (nearestSeat != null && nearestDistance <= 6.25) {
                Entity cushion = findCushion(nearestSeat);
                if (cushion != null && (cushion.getPassengers().isEmpty()
                        || cushion.getPassengers().contains(player))) {
                    player.startRiding(cushion, true, true);
                }
            }
        }
    }

    private void spawnCushion(BlockPos floorPos) {
        if (eventLevel == null) return;
        MinecraftServer server = eventLevel.getServer();
        String blockPosition = floorPos.getX() + "," + floorPos.getY() + "," + floorPos.getZ();
        String command = "summon minecraft:cushion "
                + (floorPos.getX() + 0.5) + " " + (FLOOR_Y + 1) + " " + (floorPos.getZ() + 0.5)
                + " {block_pos:[I;" + blockPosition + "],color:\"lime\"}";
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack()
                        .withLevel(eventLevel)
                        .withPosition(new Vec3(floorPos.getX() + 0.5, FLOOR_Y + 1, floorPos.getZ() + 0.5))
                        .withMaximumPermission(PermissionSet.ALL_PERMISSIONS),
                command);
    }

    private void clearCushions() {
        if (eventLevel == null) return;
        try {
            MinecraftServer server = eventLevel.getServer();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack()
                            .withLevel(eventLevel)
                            .withMaximumPermission(PermissionSet.ALL_PERMISSIONS),
                    "kill @e[type=minecraft:cushion]");
        } catch (Exception e) {
            PartyGamesMod.LOGGER.warn("Could not clear PartyGames cushions.", e);
        }
    }

    private void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(partyGamesMessage(message), false);
    }

    private void loadPersistentData() {
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(sessionsFile)) {
                try (Reader reader = Files.newBufferedReader(sessionsFile)) {
                    Map<String, ReturnPoint> stored = GSON.fromJson(reader, SESSION_MAP_TYPE);
                    if (stored != null) {
                        returnPoints.clear();
                        stored.forEach((id, point) -> {
                            try {
                                returnPoints.put(UUID.fromString(id), point);
                            } catch (IllegalArgumentException ignored) { }
                        });
                    }
                }
            }
            if (Files.exists(pendingFile)) {
                try (Reader reader = Files.newBufferedReader(pendingFile)) {
                    Map<String, PendingReward> stored = GSON.fromJson(reader, PENDING_MAP_TYPE);
                    if (stored != null) {
                        pendingRewards.clear();
                        stored.forEach((id, reward) -> {
                            try {
                                if (reward != null) {
                                    reward.normalize();
                                    pendingRewards.put(UUID.fromString(id), reward);
                                }
                            } catch (IllegalArgumentException ignored) { }
                        });
                    }
                }
            }
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not load PartyGames persistent data.", e);
        }
    }

    private void saveSessions() {
        Map<String, ReturnPoint> stored = new HashMap<>();
        returnPoints.forEach((uuid, point) -> stored.put(uuid.toString(), point));
        writeJson(sessionsFile, stored);
    }

    private void savePendingRewards() {
        Map<String, PendingReward> stored = new HashMap<>();
        pendingRewards.forEach((uuid, reward) -> stored.put(uuid.toString(), reward));
        writeJson(pendingFile, stored);
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(value, writer);
            }
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not persist PartyGames data to {}.", path, e);
        }
    }

    private static final class ReturnPoint {
        public String dimension = "minecraft:overworld";
        public String gameMode = "survival";
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public boolean invulnerable;
        public boolean mayBuild = true;
        public boolean instabuild;
        public boolean mayfly;
        public boolean flying;
    }

    private static final class PendingReward {
        public List<PendingItem> items = new ArrayList<>();
        public List<String> commands = new ArrayList<>();

        public void normalize() {
            if (items == null) items = new ArrayList<>();
            if (commands == null) commands = new ArrayList<>();
        }
    }

    private static final class PendingItem {
        public String id;
        public int count;
        public JsonElement stackData;

        public PendingItem() {
        }

        public PendingItem(String id, int count) {
            this(id, count, null);
        }

        public PendingItem(String id, int count, JsonElement stackData) {
            this.id = id;
            this.count = count;
            this.stackData = stackData;
        }
    }
}
