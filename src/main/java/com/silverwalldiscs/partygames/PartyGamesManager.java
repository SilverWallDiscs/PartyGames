package com.silverwalldiscs.partygames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
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
    // Paste the supplied 162x8x250 schematic at X=5120 (320 chunks from the dance arena).
    private static final int RACE_TRACK_ORIGIN_X = 5120;
    private static final int RACE_TRACK_ORIGIN_Y = 64;
    private static final int RACE_TRACK_ORIGIN_Z = -125;
    private static final int RACE_TRACK_WIDTH = 162;
    private static final int RACE_TRACK_HEIGHT = 8;
    private static final int RACE_TRACK_LENGTH = 250;
    private static final int RACE_MAX_PLAYERS = 16;
    private static final int RACE_START_LOCAL_X = 96;
    private static final int RACE_START_LOCAL_Z = 1;
    private static final int RACE_START_SURFACE_LOCAL_Y = 5;
    private static final int RACE_FINISH_MIN_LOCAL_X = 45;
    private static final int RACE_FINISH_MAX_LOCAL_X = 145;
    private static final int RACE_COUNTDOWN_SECONDS = 5;
    private static final long RACE_TIMEOUT_MILLIS = 180_000L;
    private static final int RACE_HONEY_LOCAL_Z_START = 120;
    private static final int RACE_HONEY_LOCAL_Z_END = 126;
    private static final int RACE_SLIME_LOCAL_Z_START = 155;
    private static final int RACE_SLIME_LOCAL_Z_END = 159;
    private static final String RACE_BOAT_TAG = "partygames_race_boat";
    private static final String RACE_SCHEMATIC_RESOURCE = "/data/partygames/boat_track.schem.b64";
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
        IDLE, JOINING, DANCING, SEAT_RUSH, RACE_COUNTDOWN, RACE_RUNNING
    }

    private enum Minigame {
        DANCE_FLOOR, BOAT_RACE
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
    // Delay reconnect state mutations until vanilla has finished PlayerList/entity registration.
    private final Set<UUID> recoveryPendingPlayers = new HashSet<>();
    private final Set<UUID> joinAfterRecovery = new LinkedHashSet<>();
    private final Map<UUID, Entity> raceBoats = new HashMap<>();
    private final Map<UUID, Vec3> raceBoatSpawnPositions = new HashMap<>();
    private final Map<UUID, Double> raceLastX = new HashMap<>();
    private final Map<UUID, Double> raceLastZ = new HashMap<>();
    private final Map<UUID, Double> raceDistance = new HashMap<>();
    private final List<UUID> raceFinishOrder = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private Minigame activeMinigame = Minigame.DANCE_FLOOR;
    private ServerLevel eventLevel;
    private long nextAutomaticEventAt;
    private long phaseEndsAt;
    private long nextColorChangeAt;
    private int completedRounds;
    private int nextSpawnIndex;
    private Identifier activeMusicId;
    private boolean arenaPrepared;
    private boolean raceTrackPrepared;
    private boolean loggedMissingDimension;
    private int countdownValue;

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
        return "PartyGames game: " + minigameName(activeMinigame)
                + " | phase: " + phase.name().toLowerCase()
                + " | joined: " + joinedPlayers.size()
                + " | alive: " + alivePlayers.size()
                + " | completed dance rounds: " + completedRounds
                + (activeMinigame == Minigame.BOAT_RACE ? " | boats finished: " + raceFinishOrder.size() : "");
    }

    public boolean isSupportedGameSelection(String selection) {
        return parseMinigame(selection) != null;
    }

    public boolean startSelectedGame(MinecraftServer server, String selection, boolean automatic) {
        Minigame selected = parseMinigame(selection);
        if (selected == null) return false;
        return startRegistrationForGame(server, automatic, selected);
    }

    private Minigame parseMinigame(String selection) {
        if (selection == null) return null;
        String value = selection.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
        return switch (value) {
            case "dance floor", "music chair", "music chairs", "musical chairs", "musical chair",
                    "dance", "chairs", "chair" -> Minigame.DANCE_FLOOR;
            case "boat race", "boat racing", "boat", "race", "racing", "carrera de botes",
                    "carrera de barcos", "carrera", "botes", "barcos" -> Minigame.BOAT_RACE;
            default -> null;
        };
    }

    private String minigameName(Minigame game) {
        return game == Minigame.BOAT_RACE ? "Boat Race" : "Dance Floor";
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
        clearCushions();
        clearRaceBoats();
        if (eventLevel != null) paintFloor(Blocks.CONCRETE.black().defaultBlockState());

        phase = Phase.IDLE;
        activeMinigame = Minigame.DANCE_FLOOR;
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
        // Do not manually remove ServerPlayer entities here. Minecraft owns PlayerList,
        // chunk tracking and entity lifecycle; forcibly removing a stale reference can corrupt
        // DistanceManager and make the next connection for the same UUID unusable.

        // Run in every phase, not only while IDLE. A stale/unregistered player left in the
        // reserved event dimension must never remain there just because the next game is open.
        reconcileUnregisteredPlayers(server, "periodic recovery watchdog");

        // Keep event participants fed and rescue them if they escape the arena and fall into the void.
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && player.level() == eventLevel) {
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0F);
                if (phase != Phase.IDLE && player.getY() < 0.0D) {
                    if (activeMinigame == Minigame.BOAT_RACE) rescueRacer(player);
                    else rescuePlayerToArena(player);
                }
            }
        }

        long now = System.currentTimeMillis();

        if (eventLevel == null) {
            refreshEventDimension(server);
            if (eventLevel != null && !arenaPrepared) prepareArena();
        }

        if (phase == Phase.IDLE) {
            if (now >= nextAutomaticEventAt) {
                startRegistration(server, true);
            }
            return;
        }

        if (phase == Phase.JOINING && now >= phaseEndsAt) {
            if (joinedPlayers.size() < config.minimumPlayers) {
                broadcast(server, "PartyGames was cancelled because not enough players joined.");
                finishEvent(server, null, false);
            } else if (activeMinigame == Minigame.BOAT_RACE) {
                broadcast(server, "Registration is closed! Boat Race starts now.");
                startBoatRace(server);
            } else {
                broadcast(server, "Registration is closed! Dance Floor starts now.");
                startDanceRound(server);
            }
            return;
        }

        if (phase == Phase.RACE_COUNTDOWN) {
            lockRaceBoats();
            if (now >= nextColorChangeAt) {
                if (countdownValue > 0) {
                    sendRaceCountdown(server, Integer.toString(countdownValue), countdownValue);
                    countdownValue--;
                    nextColorChangeAt = now + 1_000L;
                } else {
                    startRaceRunning(server, now);
                }
            }
            return;
        }

        if (phase == Phase.RACE_RUNNING) {
            tickBoatRace(server, now);
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
        return startRegistrationForGame(server, automatic, Minigame.DANCE_FLOOR);
    }

    private boolean startRegistrationForGame(MinecraftServer server, boolean automatic, Minigame selected) {
        if (phase != Phase.IDLE) {
            broadcast(server, "A PartyGames event is already in progress.");
            return false;
        }
        refreshEventDimension(server);
        if (eventLevel == null) {
            broadcast(server, "PartyGames could not start: the event dimension is unavailable. Check the server log and restart after installing PartyGames.");
            return false;
        }
        activeMinigame = selected;
        if (selected == Minigame.DANCE_FLOOR && !arenaPrepared) prepareArena();
        if (selected == Minigame.DANCE_FLOOR && !arenaPrepared) {
            broadcast(server, "PartyGames could not prepare the dance floor.");
            activeMinigame = Minigame.DANCE_FLOOR;
            return false;
        }

        phase = Phase.JOINING;
        joinedPlayers.clear();
        alivePlayers.clear();
        eliminationOrder.clear();
        entrantNames.clear();
        seatPositions.clear();
        raceFinishOrder.clear();
        clearRaceBoats();
        completedRounds = 0;
        nextSpawnIndex = 0;
        spawnPositions.clear();
        if (selected == Minigame.DANCE_FLOOR) {
            spawnPositions.addAll(floorPositions());
            Collections.shuffle(spawnPositions, RANDOM);
        }
        phaseEndsAt = System.currentTimeMillis() + config.joinDurationSeconds * 1000L;
        nextAutomaticEventAt = System.currentTimeMillis() + config.eventIntervalMinutes * 60_000L;

        announceParticipation(server);
        PartyGamesMod.LOGGER.info("PartyGames registration opened for {} (automatic={}).",
                minigameName(selected), automatic);
        return true;
    }

    public int join(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (phase == Phase.JOINING && recoveryPendingPlayers.contains(uuid)) {
            joinAfterRecovery.add(uuid);
            player.sendSystemMessage(Component.literal(
                    "Your previous PartyGames session is being restored. You will be joined automatically.")
                    .withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        if (phase != Phase.JOINING) {
            player.sendSystemMessage(Component.literal("There is no PartyGames event accepting players right now.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (joinedPlayers.contains(uuid)) {
            player.sendSystemMessage(Component.literal("You have already joined this event."));
            return 1;
        }
        // Restore any pending session before taking a new snapshot, regardless of the
        // dimension where the player logged in. This also repairs a missed JOIN callback.
        MinecraftServer server = player.level().getServer();
        if (returnPoints.containsKey(uuid)) {
            returnPlayer(server, player);
        } else if (player.level() == eventLevel) {
            recoverPlayerWithoutReturnPoint(server, player, "join requested while still inside the event dimension");
        }

        int playerLimit = activeMinigame == Minigame.BOAT_RACE ? RACE_MAX_PLAYERS : 200;
        if (joinedPlayers.size() >= playerLimit) {
            player.sendSystemMessage(Component.literal(activeMinigame == Minigame.BOAT_RACE
                    ? "Boat Race is full (maximum 16 racers; all share one start point)." : "This event has reached its player limit."));
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
        player.sendSystemMessage(Component.literal("You joined PartyGames: " + minigameName(activeMinigame)
                + "! Your inventory is protected.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    public int retrieve(ServerPlayer player) {
        deliverPending(player, true);
        return 1;
    }

    public void markPlayerRecoveryPending(ServerPlayer player) {
        recoveryPendingPlayers.add(player.getUUID());
    }

    public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        // The scheduled callback only acts on the currently registered instance. If the player
        // disconnected again before recovery ran, never mutate that stale entity.
        if (server.getPlayerList().getPlayer(uuid) != player) {
            recoveryPendingPlayers.remove(uuid);
            joinAfterRecovery.remove(uuid);
            PartyGamesMod.LOGGER.warn(
                    "Skipping PartyGames reconnect recovery for stale player instance {} ({}).",
                    player.getGameProfile().name(), uuid);
            return;
        }

        PartyGamesMod.LOGGER.info(
                "PartyGames connection JOIN for {}: phase={}, dimension={}, gameMode={}, mayBuild={}, mayfly={}, permanentlyInvulnerable={}, joined={}, savedReturnPoint={}.",
                player.getGameProfile().name(), phase, player.level().dimension().identifier(),
                player.gameMode().getName(), player.getAbilities().mayBuild, player.getAbilities().mayfly,
                player.isPermanentlyInvulnerable(), joinedPlayers.contains(uuid), returnPoints.containsKey(uuid));
        try {
            if (!joinedPlayers.contains(uuid)) {
                if (returnPoints.containsKey(uuid)) {
                    returnPlayer(server, player);
                } else if (eventLevel != null && player.level() == eventLevel) {
                    recoverPlayerWithoutReturnPoint(server, player, "reconnected without a saved return point");
                }
            }
            deliverPending(player, false);
        } finally {
            recoveryPendingPlayers.remove(uuid);
            if (joinAfterRecovery.remove(uuid)) {
                ServerPlayer current = server.getPlayerList().getPlayer(uuid);
                if (current == player && phase == Phase.JOINING) {
                    join(player);
                } else if (current == player) {
                    player.sendSystemMessage(Component.literal(
                            "Your previous session was restored, but PartyGames is no longer accepting players.")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }

    public void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        joinedPlayers.remove(uuid);
        boolean wasAlive = alivePlayers.remove(uuid);
        awaitingPrizeCommandInput.remove(uuid);
        recoveryPendingPlayers.remove(uuid);
        joinAfterRecovery.remove(uuid);
        Entity raceBoat = raceBoats.remove(uuid);
        if (raceBoat != null) raceBoat.discard();
        raceBoatSpawnPositions.remove(uuid);
        raceLastX.remove(uuid);
        raceLastZ.remove(uuid);
        raceDistance.remove(uuid);
        if (wasAlive && phase != Phase.IDLE && phase != Phase.JOINING) {
            eliminationOrder.add(uuid);
        }

        // Never remove ServerPlayer entities or alter their level/chunk memberships here.
        // Vanilla owns the disconnect lifecycle and will safely remove the old instance.
        saveSessions();
        if (phase == Phase.JOINING) {
            broadcast(server, player.getGameProfile().name() + " left the event registration.");
        }
    }

    private void rescueRacer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Entity boat = raceBoats.get(uuid);
        Vec3 start = raceBoatSpawnPositions.get(uuid);
        if (eventLevel == null || boat == null || start == null || boat.isRemoved()) {
            returnPlayer(player.level().getServer(), player);
            return;
        }
        boat.setDeltaMovement(Vec3.ZERO);
        boat.setPos(start.x, start.y, start.z);
        player.stopRiding();
        player.teleportTo(eventLevel, start.x, start.y, start.z, Set.of(), -90.0F, 0.0F, true);
        player.startRiding(boat, true, true);
        raceLastX.put(uuid, start.x);
        raceLastZ.put(uuid, start.z);
        raceDistance.put(uuid, 0.0D);
        player.sendSystemMessage(Component.literal("You fell off the course and were returned to your starting line.")
                .withStyle(ChatFormatting.YELLOW));
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

    private void startBoatRace(MinecraftServer server) {
        clearRaceBoats();
        raceFinishOrder.clear();
        raceBoatSpawnPositions.clear();
        raceLastX.clear();
        raceLastZ.clear();
        raceDistance.clear();

        if (!prepareRaceTrack()) {
            broadcast(server, "Boat Race could not load packeticetrack.schem; check the PartyGames resource and server log.");
            finishEvent(server, null, false);
            return;
        }

        List<UUID> entrants = new ArrayList<>(joinedPlayers);
        double spawnX = RACE_TRACK_ORIGIN_X + RACE_START_LOCAL_X + 0.5D;
        double spawnY = RACE_TRACK_ORIGIN_Y + RACE_START_SURFACE_LOCAL_Y + 1.0D;
        double spawnZ = RACE_TRACK_ORIGIN_Z + RACE_START_LOCAL_Z + 0.5D;
        int created = 0;
        for (UUID uuid : entrants) {
            if (created >= RACE_MAX_PLAYERS) break;
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;
            Entity boat = summonRaceBoat(server, spawnX, spawnY, spawnZ);
            if (boat == null) {
                PartyGamesMod.LOGGER.error("Could not summon a race boat for {}.", player.getGameProfile().name());
                player.sendSystemMessage(Component.literal("Could not create your race boat. Please contact an administrator.")
                        .withStyle(ChatFormatting.RED));
                continue;
            }

            boat.addTag(RACE_BOAT_TAG);
            boat.setRot(-90.0F, 0.0F);
            boat.setDeltaMovement(Vec3.ZERO);
            player.stopRiding();
            player.teleportTo(eventLevel, spawnX, spawnY, spawnZ, Set.of(), -90.0F, 0.0F, true);
            player.startRiding(boat, true, true);
            raceBoats.put(uuid, boat);
            Vec3 start = new Vec3(spawnX, spawnY, spawnZ);
            raceBoatSpawnPositions.put(uuid, start);
            raceLastX.put(uuid, spawnX);
            raceLastZ.put(uuid, spawnZ);
            raceDistance.put(uuid, 0.0D);
            player.sendSystemMessage(Component.literal(
                    "Boat Race: everyone starts together. Keep your boat; the 5-second countdown is private to racers.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            created++;
        }

        if (raceBoats.size() < config.minimumPlayers) {
            broadcast(server, "Boat Race could not start because too few boats were available.");
            finishEvent(server, null, false);
            return;
        }

        phase = Phase.RACE_COUNTDOWN;
        countdownValue = RACE_COUNTDOWN_SECONDS;
        nextColorChangeAt = System.currentTimeMillis();
        for (UUID uuid : raceBoats.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("First boat to cross the finish line after one lap wins.")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            }
        }
        broadcast(server, "Boat Race is ready with " + raceBoats.size() + " racer(s) on one shared track!");
    }

    private Entity summonRaceBoat(MinecraftServer server, double x, double y, double z) {
        String command = "summon minecraft:oak_boat " + x + " " + y + " " + z;
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack()
                        .withLevel(eventLevel)
                        .withPosition(new Vec3(x, y, z))
                        .withMaximumPermission(PermissionSet.ALL_PERMISSIONS),
                command);
        AABB nearby = new AABB(x - 1.5D, y - 1.5D, z - 1.5D, x + 1.5D, y + 2.0D, z + 1.5D);
        return eventLevel.getEntitiesOfClass(Entity.class, nearby, this::isOakBoat).stream()
                .filter(entity -> !raceBoats.containsValue(entity))
                .findFirst().orElse(null);
    }

    private boolean prepareRaceTrack() {
        if (eventLevel == null) return false;
        if (raceTrackPrepared) return true;

        RaceSchematic schematic = loadRaceSchematic();
        if (schematic == null || schematic.width != RACE_TRACK_WIDTH
                || schematic.height != RACE_TRACK_HEIGHT || schematic.length != RACE_TRACK_LENGTH) {
            PartyGamesMod.LOGGER.error("Boat Race schematic is missing or has unexpected dimensions.");
            return false;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Remove the previous generated multi-lane prototype in its old world coordinates.
        clearBlockBox(RACE_TRACK_ORIGIN_X - 8, RACE_TRACK_ORIGIN_X + 392,
                RACE_TRACK_ORIGIN_Y - 1, RACE_TRACK_ORIGIN_Y + 4,
                -35, 35, air);

        // Clear the schematic volume once, but do not send neighbor updates for hundreds of
        // thousands of empty cells. Only actual changes are written.
        clearBlockBox(RACE_TRACK_ORIGIN_X, RACE_TRACK_ORIGIN_X + RACE_TRACK_WIDTH - 1,
                RACE_TRACK_ORIGIN_Y, RACE_TRACK_ORIGIN_Y + RACE_TRACK_HEIGHT - 1,
                RACE_TRACK_ORIGIN_Z, RACE_TRACK_ORIGIN_Z + RACE_TRACK_LENGTH - 1, air);

        BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
        for (BlockPos local : schematic.packedIceBlocks) {
            pos.set(RACE_TRACK_ORIGIN_X + local.getX(),
                    RACE_TRACK_ORIGIN_Y + local.getY(),
                    RACE_TRACK_ORIGIN_Z + local.getZ());
            if (!eventLevel.getBlockState(pos).equals(ice)) eventLevel.setBlock(pos, ice, 2);
        }

        overlayRaceObstacles(schematic);
        raceTrackPrepared = true;
        PartyGamesMod.LOGGER.info(
                "Loaded Boat Race track from bundled schematic: {} packed-ice blocks, origin=({}, {}, {}).",
                schematic.packedIceBlocks.size(), RACE_TRACK_ORIGIN_X, RACE_TRACK_ORIGIN_Y, RACE_TRACK_ORIGIN_Z);
        return true;
    }

    private void clearBlockBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, BlockState air) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (!eventLevel.getBlockState(pos).isAir()) eventLevel.setBlock(pos, air, 2);
                }
            }
        }
    }

    private void overlayRaceObstacles(RaceSchematic schematic) {
        // Add both requested obstacle types to a long, straight section of the schematic.
        // The underlying schematic remains the source of the track geometry and elevations.
        Map<Long, Integer> topYByXZ = new HashMap<>();
        for (BlockPos local : schematic.packedIceBlocks) {
            long key = (((long) local.getX()) << 32) | (local.getZ() & 0xffffffffL);
            topYByXZ.merge(key, local.getY(), Math::max);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState honey = Blocks.HONEY_BLOCK.defaultBlockState();
        BlockState slime = Blocks.SLIME_BLOCK.defaultBlockState();
        for (int z = RACE_HONEY_LOCAL_Z_START; z <= RACE_HONEY_LOCAL_Z_END; z++) {
            for (int x = 98; x <= 101; x++) {
                Integer y = topYByXZ.get((((long) x) << 32) | (z & 0xffffffffL));
                if (y != null) {
                    pos.set(RACE_TRACK_ORIGIN_X + x, RACE_TRACK_ORIGIN_Y + y, RACE_TRACK_ORIGIN_Z + z);
                    eventLevel.setBlock(pos, honey, 2);
                }
            }
        }
        for (int z = RACE_SLIME_LOCAL_Z_START; z <= RACE_SLIME_LOCAL_Z_END; z++) {
            for (int x = 98; x <= 101; x++) {
                Integer y = topYByXZ.get((((long) x) << 32) | (z & 0xffffffffL));
                if (y != null) {
                    pos.set(RACE_TRACK_ORIGIN_X + x, RACE_TRACK_ORIGIN_Y + y, RACE_TRACK_ORIGIN_Z + z);
                    eventLevel.setBlock(pos, slime, 2);
                }
            }
        }
    }

    private RaceSchematic loadRaceSchematic() {
        try (InputStream resource = PartyGamesManager.class.getResourceAsStream(RACE_SCHEMATIC_RESOURCE)) {
            if (resource == null) {
                PartyGamesMod.LOGGER.error("Could not find bundled schematic resource {}.", RACE_SCHEMATIC_RESOURCE);
                return null;
            }
            String encoded = new String(resource.readAllBytes(), StandardCharsets.US_ASCII).trim();
            byte[] compressed = Base64.getDecoder().decode(encoded);
            try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                if (input.readUnsignedByte() != 10) throw new IOException("Schematic root tag was not a compound.");
                input.readUTF(); // root name
                int width = 0, height = 0, length = 0;
                byte[] blockData = null;
                Map<String, Integer> palette = new HashMap<>();
                while (true) {
                    int type = input.readUnsignedByte();
                    if (type == 0) break;
                    String name = input.readUTF();
                    if (type == 2 && (name.equals("Width") || name.equals("Height") || name.equals("Length"))) {
                        int value = input.readShort();
                        if (name.equals("Width")) width = value;
                        else if (name.equals("Height")) height = value;
                        else if (name.equals("Length")) length = value;
                    } else if (type == 3) {
                        int value = input.readInt();
                        if (name.equals("Width")) width = value;
                        else if (name.equals("Height")) height = value;
                        else if (name.equals("Length")) length = value;
                    } else if (type == 10 && name.equals("Palette")) {
                        readSchematicPalette(input, palette);
                    } else if (type == 7 && name.equals("BlockData")) {
                        int size = input.readInt();
                        if (size < 0 || size > 32_000_000) throw new IOException("Invalid schematic block data length: " + size);
                        blockData = input.readNBytes(size);
                        if (blockData.length != size) throw new IOException("Schematic block data ended unexpectedly.");
                    } else {
                        skipSchematicTag(input, type);
                    }
                }
                Integer packedIceId = palette.get("minecraft:packed_ice");
                if (width <= 0 || height <= 0 || length <= 0 || blockData == null || packedIceId == null) {
                    throw new IOException("Schematic is missing dimensions, packed ice palette, or block data.");
                }
                List<BlockPos> iceBlocks = new ArrayList<>();
                int cursor = 0;
                int index = 0;
                int expectedBlocks = width * height * length;
                while (cursor < blockData.length && index < expectedBlocks) {
                    int value = 0;
                    int shift = 0;
                    int next;
                    do {
                        if (cursor >= blockData.length || shift > 28) throw new IOException("Invalid schematic block varint.");
                        next = blockData[cursor++] & 0xff;
                        value |= (next & 0x7f) << shift;
                        shift += 7;
                    } while ((next & 0x80) != 0);
                    if (value == packedIceId) {
                        int x = index % width;
                        int z = (index / width) % length;
                        int y = index / (width * length);
                        iceBlocks.add(new BlockPos(x, y, z));
                    }
                    index++;
                }
                if (index != expectedBlocks) throw new IOException("Schematic block count does not match dimensions.");
                return new RaceSchematic(width, height, length, iceBlocks);
            }
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not parse the bundled Boat Race schematic.", e);
            return null;
        }
    }

    private static void readSchematicPalette(DataInputStream input, Map<String, Integer> palette) throws IOException {
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            String name = input.readUTF();
            if (type == 3) palette.put(name, input.readInt());
            else skipSchematicTag(input, type);
        }
    }

    private static void skipSchematicTag(DataInputStream input, int type) throws IOException {
        switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {
                int size = input.readInt();
                if (size < 0) throw new IOException("Negative NBT byte array size.");
                input.skipNBytes(size);
            }
            case 8 -> input.readUTF();
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int size = input.readInt();
                if (size < 0) throw new IOException("Negative NBT list size.");
                for (int i = 0; i < size; i++) skipSchematicTag(input, elementType);
            }
            case 10 -> {
                while (true) {
                    int nestedType = input.readUnsignedByte();
                    if (nestedType == 0) break;
                    input.readUTF();
                    skipSchematicTag(input, nestedType);
                }
            }
            case 11 -> {
                int size = input.readInt();
                if (size < 0) throw new IOException("Negative NBT int array size.");
                input.skipNBytes((long) size * Integer.BYTES);
            }
            case 12 -> {
                int size = input.readInt();
                if (size < 0) throw new IOException("Negative NBT long array size.");
                input.skipNBytes((long) size * Long.BYTES);
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static final class RaceSchematic {
        private final int width;
        private final int height;
        private final int length;
        private final List<BlockPos> packedIceBlocks;

        private RaceSchematic(int width, int height, int length, List<BlockPos> packedIceBlocks) {
            this.width = width;
            this.height = height;
            this.length = length;
            this.packedIceBlocks = packedIceBlocks;
        }
    }

    private boolean isOakBoat(Entity entity) {
        if (entity == null) return false;
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && "minecraft:oak_boat".equals(id.toString());
    }

    private void lockRaceBoats() {
        for (Map.Entry<UUID, Entity> entry : raceBoats.entrySet()) {
            Entity boat = entry.getValue();
            Vec3 start = raceBoatSpawnPositions.get(entry.getKey());
            if (boat == null || start == null || boat.isRemoved()) continue;
            boat.setDeltaMovement(Vec3.ZERO);
            boat.setPos(start.x, start.y, start.z);
            boat.setRot(-90.0F, 0.0F);
            ServerPlayer player = eventLevel.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.getVehicle() != boat) player.startRiding(boat, true, true);
        }
    }

    private void sendRaceCountdown(MinecraftServer server, String value, int pitchStep) {
        for (UUID uuid : new ArrayList<>(raceBoats.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;
            player.sendSystemMessage(Component.literal(value)
                    .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)));
            playRaceSound(player, "minecraft:block.note_block.pling", 0.8F, 0.65F + (pitchStep * 0.12F));
        }
    }

    private void startRaceRunning(MinecraftServer server, long now) {
        phase = Phase.RACE_RUNNING;
        phaseEndsAt = now + RACE_TIMEOUT_MILLIS;
        for (UUID uuid : raceBoats.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            Entity boat = raceBoats.get(uuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("¡YA! ¡A CORRER!")
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)));
                playRaceSound(player, "minecraft:entity.player.levelup", 0.9F, 1.2F);
            }
            if (boat != null) boat.setDeltaMovement(Vec3.ZERO);
        }
        broadcast(server, "The Boat Race is underway!");
    }

    private void tickBoatRace(MinecraftServer server, long now) {
        if (now >= phaseEndsAt) {
            completeBoatRace(server, true);
            return;
        }
        for (UUID uuid : new ArrayList<>(raceBoats.keySet())) {
            Entity boat = raceBoats.get(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (boat == null || boat.isRemoved() || player == null) continue;
            if (raceFinishOrder.contains(uuid)) {
                boat.setDeltaMovement(Vec3.ZERO);
                continue;
            }
            if (player.getVehicle() != boat) player.startRiding(boat, true, true);

            double currentX = boat.getX();
            double currentZ = boat.getZ();
            double previousX = raceLastX.getOrDefault(uuid, RACE_TRACK_ORIGIN_X + RACE_START_LOCAL_X + 0.5D);
            double previousZ = raceLastZ.getOrDefault(uuid, RACE_TRACK_ORIGIN_Z + RACE_START_LOCAL_Z + 0.5D);
            double traveled = Math.hypot(currentX - previousX, currentZ - previousZ);
            double totalDistance = raceDistance.getOrDefault(uuid, 0.0D) + traveled;
            raceDistance.put(uuid, totalDistance);

            int localZ = (int) Math.floor(currentZ - RACE_TRACK_ORIGIN_Z);
            int previousLocalZ = (int) Math.floor(previousZ - RACE_TRACK_ORIGIN_Z);
            if (previousLocalZ < RACE_SLIME_LOCAL_Z_START && localZ >= RACE_SLIME_LOCAL_Z_START
                    && currentX >= RACE_TRACK_ORIGIN_X + 96 && currentX <= RACE_TRACK_ORIGIN_X + 104) {
                Vec3 velocity = boat.getDeltaMovement();
                boat.setDeltaMovement(velocity.x, Math.max(0.48D, velocity.y), velocity.z);
                player.sendSystemMessage(Component.literal("¡SALTO DE SLIME!")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                playRaceSound(player, "minecraft:entity.slime.jump", 0.8F, 1.0F);
            }

            // The start/finish line is the upper straight at local Z=0. Ignore the initial
            // crossing into the course; finish only on the return crossing after a lap.
            double finishLineZ = RACE_TRACK_ORIGIN_Z + 0.5D;
            double localX = currentX - RACE_TRACK_ORIGIN_X;
            if (previousZ > finishLineZ && currentZ <= finishLineZ
                    && localX >= RACE_FINISH_MIN_LOCAL_X && localX <= RACE_FINISH_MAX_LOCAL_X
                    && totalDistance > 100.0D) {
                raceFinishOrder.add(uuid);
                boat.setDeltaMovement(Vec3.ZERO);
                player.sendSystemMessage(Component.literal("¡Llegaste! Puesto #" + raceFinishOrder.size() + ".")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                playRaceSound(player, "minecraft:ui.toast.challenge_complete", 0.8F, 1.0F);
                broadcast(server, player.getGameProfile().name() + " finished Boat Race in place #" + raceFinishOrder.size() + ".");
            }

            raceLastX.put(uuid, currentX);
            raceLastZ.put(uuid, currentZ);
        }

        if (!joinedPlayers.isEmpty() && raceFinishOrder.size() >= joinedPlayers.size()) {
            completeBoatRace(server, false);
        } else if (joinedPlayers.isEmpty()) {
            finishEvent(server, null, false);
        }
    }

    private void completeBoatRace(MinecraftServer server, boolean timeLimitReached) {
        List<UUID> ranking = new ArrayList<>(raceFinishOrder);
        List<UUID> remaining = new ArrayList<>(joinedPlayers);
        remaining.removeAll(ranking);
        remaining.sort((first, second) -> Double.compare(raceProgress(second), raceProgress(first)));
        ranking.addAll(remaining);

        if (ranking.isEmpty()) {
            finishEvent(server, null, false);
            return;
        }
        raceFinishOrder.clear();
        raceFinishOrder.addAll(ranking);
        if (timeLimitReached) {
            broadcast(server, "Boat Race time limit reached. Remaining racers are ranked by distance traveled.");
        }
        UUID winner = ranking.get(0);
        for (int i = 0; i < ranking.size(); i++) {
            ServerPlayer player = server.getPlayerList().getPlayer(ranking.get(i));
            if (player != null) {
                player.sendSystemMessage(Component.literal("Final Boat Race position: #" + (i + 1) + ".")
                        .withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA));
            }
        }
        finishEvent(server, winner, true);
    }

    private double raceProgress(UUID uuid) {
        return raceDistance.getOrDefault(uuid, 0.0D);
    }

    private void playRaceSound(ServerPlayer player, String soundName, float volume, float pitch) {
        Identifier id = Identifier.tryParse(soundName);
        SoundEvent sound = id == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (sound != null) playSoundToPlayer(player, sound, SoundSource.MASTER, volume, pitch);
    }

    private void clearRaceBoats() {
        for (Entity boat : new ArrayList<>(raceBoats.values())) {
            if (boat != null && !boat.isRemoved()) boat.discard();
        }
        raceBoats.clear();
        raceBoatSpawnPositions.clear();
        raceLastX.clear();
        raceLastZ.clear();
        raceDistance.clear();
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
            if (activeMinigame == Minigame.BOAT_RACE) {
                ranking.addAll(raceFinishOrder);
                List<UUID> remaining = new ArrayList<>(joinedPlayers);
                remaining.removeAll(ranking);
                remaining.sort((first, second) -> Double.compare(raceProgress(second), raceProgress(first)));
                ranking.addAll(remaining);
                ranking.remove(winner);
                ranking.add(0, winner);
            } else {
                ranking.add(winner);
                for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
                    UUID uuid = eliminationOrder.get(i);
                    if (!ranking.contains(uuid)) ranking.add(uuid);
                }
            }
            for (int index = 0; index < Math.min(3, ranking.size()); index++) {
                UUID uuid = ranking.get(index);
                String name = entrantNames.getOrDefault(uuid, uuid.toString());
                givePrize(server, uuid, name, index + 1, config.prizeForPlace(index + 1));
            }
            String winnerName = entrantNames.getOrDefault(winner, "A player");
            broadcast(server, winnerName + " won PartyGames: " + minigameName(activeMinigame) + "!");
        } else if (winner == null && !joinedPlayers.isEmpty()) {
            broadcast(server, "PartyGames ended without a winner.");
        }

        clearRaceBoats();
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) returnPlayer(server, player);
        }

        // Defensive final pass: finish must not leave anyone in the event dimension,
        // including stale online players that lost their UUID from joinedPlayers.
        if (eventLevel != null) {
            for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                if (player.level() != eventLevel) continue;
                UUID uuid = player.getUUID();
                PartyGamesMod.LOGGER.warn(
                        "Player {} remained in PartyGames dimension at event finish; membership={}, hasReturnPoint={}, mode={}, mayBuild={}, invulnerable={}. Applying recovery.",
                        player.getGameProfile().name(), joinedPlayers.contains(uuid), returnPoints.containsKey(uuid),
                        player.gameMode().getName(), player.getAbilities().mayBuild, player.isPermanentlyInvulnerable());
                if (returnPoints.containsKey(uuid)) returnPlayer(server, player);
                if (player.level() == eventLevel) {
                    recoverPlayerWithoutReturnPoint(server, player, "still in event dimension after finishEvent");
                }
            }
        }

        phase = Phase.IDLE;
        activeMinigame = Minigame.DANCE_FLOOR;
        joinedPlayers.clear();
        alivePlayers.clear();
        seatPositions.clear();
        spawnPositions.clear();
        eliminationOrder.clear();
        entrantNames.clear();
        raceFinishOrder.clear();
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
        PartyGamesMod.LOGGER.info(
                "PartyGames restored {} from saved dimension {} to {}: mode={} (saved {}), mayBuild={} (saved {}), mayfly={}, invulnerable={} (saved {}, legacyCorruptSnapshot={}).",
                player.getGameProfile().name(), point.dimension, destination.dimension().identifier(),
                player.gameMode().getName(), point.gameMode, player.getAbilities().mayBuild, point.mayBuild,
                player.getAbilities().mayfly, player.isPermanentlyInvulnerable(), point.invulnerable,
                legacyCorruptSurvivalSnapshot);
        player.sendSystemMessage(Component.literal("You have been returned from PartyGames.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        returnPoints.remove(uuid);
        joinedPlayers.remove(uuid);
        alivePlayers.remove(uuid);
        saveSessions();
    }

    private void reconcileUnregisteredPlayers(MinecraftServer server, String reason) {
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            UUID uuid = player.getUUID();
            if (joinedPlayers.contains(uuid) || recoveryPendingPlayers.contains(uuid)) continue;

            // A saved session is authoritative until restored; retry it from the tick loop
            // if the JOIN callback was too early or failed to complete its teleport.
            if (returnPoints.containsKey(uuid)) {
                PartyGamesMod.LOGGER.warn(
                        "PartyGames found pending return state for online unregistered player {} during phase {}: currentDimension={}, currentMode={}, reason={}. Retrying restoration.",
                        player.getGameProfile().name(), phase, player.level().dimension().identifier(),
                        player.gameMode().getName(), reason);
                returnPlayer(server, player);
                continue;
            }

            // No unregistered player should ever remain in our reserved event dimension.
            if (eventLevel != null && player.level() == eventLevel) {
                PartyGamesMod.LOGGER.warn(
                        "Recovery watchdog found unregistered player {} in PartyGames dimension during phase {}: mode={}, mayBuild={}, invulnerable={}, reason={}.",
                        player.getGameProfile().name(), phase, player.gameMode().getName(),
                        player.getAbilities().mayBuild, player.isPermanentlyInvulnerable(), reason);
                recoverPlayerWithoutReturnPoint(server, player, "unregistered in event dimension during " + reason);
                continue;
            }

            // Older PartyGames builds could persist Survival with mayBuild=false and
            // permanent invulnerability. Repair that impossible Survival interaction state
            // even if the old sessions.json entry has already been consumed.
            if (player.gameMode().isSurvival() && !player.getAbilities().mayBuild) {
                PartyGamesMod.LOGGER.warn(
                        "Repairing stale PartyGames-like Survival abilities for {} outside the event dimension: mayBuild={}, invulnerable={}, reason={}.",
                        player.getGameProfile().name(), player.getAbilities().mayBuild,
                        player.isPermanentlyInvulnerable(), reason);
                player.getAbilities().mayBuild = true;
                player.getAbilities().instabuild = false;
                if (player.isPermanentlyInvulnerable()) {
                    player.setPermanentlyInvulnerable(false);
                }
                player.onUpdateAbilities();
                synchronizeClientGameMode(player, GameType.SURVIVAL);
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
        PartyGamesMod.LOGGER.warn(
                "PartyGames emergency recovery for {}: dimension={}, mode={}, mayBuild={}, mayfly={}, permanentlyInvulnerable={}, reason={}.",
                player.getGameProfile().name(), player.level().dimension().identifier(),
                player.gameMode().getName(), player.getAbilities().mayBuild, player.getAbilities().mayfly,
                player.isPermanentlyInvulnerable(), reason);

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
                .append(Component.literal(minigameName(activeMinigame) + " is open! ")
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
