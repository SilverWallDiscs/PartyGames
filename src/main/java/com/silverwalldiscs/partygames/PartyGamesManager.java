package com.silverwalldiscs.partygames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
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
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Relative;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class PartyGamesManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SESSION_MAP_TYPE = new TypeToken<Map<String, ReturnPoint>>() {}.getType();
    private static final Type PENDING_MAP_TYPE = new TypeToken<Map<String, PendingReward>>() {}.getType();
    private static final ResourceKey<Level> EVENT_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(PartyGamesMod.MOD_ID, "event"));
    private static final int FLOOR_Y = 64;
    private static final Random RANDOM = new Random();

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
    private final Set<UUID> joinedPlayers = new LinkedHashSet<>();
    private final Set<UUID> alivePlayers = new LinkedHashSet<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final List<BlockPos> seatPositions = new ArrayList<>();
    private final List<BlockPos> spawnPositions = new ArrayList<>();

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

    public String status() {
        return "PartyGames phase: " + phase.name().toLowerCase()
                + " | joined: " + joinedPlayers.size()
                + " | alive: " + alivePlayers.size()
                + " | completed dance rounds: " + completedRounds;
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
        stopMusic();
        saveSessions();
        savePendingRewards();
    }

    public void tick(MinecraftServer server) {
        // Keep event participants fed while they are inside the minigame dimension.
        // Resetting exhaustion as well prevents sprinting/jumping from consuming hunger.
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && player.level() == eventLevel) {
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0F);
                player.getFoodData().setExhaustion(0.0F);
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

        Component announcement = Component.literal("A PartyGames event has appeared! ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("[Participate]")
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withClickEvent(new ClickEvent.RunCommand("/partygames join"))));
        server.getPlayerList().broadcastSystemMessage(announcement, false);
        broadcast(server, "Dance Floor opens for " + config.joinDurationSeconds
                + " seconds. Click [Participate] or run /partygames join!");
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
        if (joinedPlayers.size() >= 200) {
            player.sendSystemMessage(Component.literal("This event has reached its player limit."));
            return 0;
        }

        ReturnPoint returnPoint = snapshot(player);
        returnPoints.put(uuid, returnPoint);
        entrantNames.put(uuid, player.getGameProfile().name());
        joinedPlayers.add(uuid);
        alivePlayers.add(uuid);
        saveSessions();

        player.setPermanentlyInvulnerable(true);
        player.getAbilities().mayBuild = false;
        player.getAbilities().instabuild = false;
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();

        BlockPos spawn = spawnPositions.isEmpty()
                ? new BlockPos(0, FLOOR_Y + 1, 0)
                : spawnPositions.get(nextSpawnIndex++ % spawnPositions.size()).above();
        player.teleportTo(eventLevel, spawn.getX() + 0.5, FLOOR_Y + 1.0, spawn.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), true);
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
        if (!joinedPlayers.contains(uuid) && returnPoints.containsKey(uuid)) {
            returnPlayer(server, player);
        }
        deliverPending(player, false);
    }

    public void onPlayerDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        joinedPlayers.remove(uuid);
        boolean wasAlive = alivePlayers.remove(uuid);
        if (wasAlive && phase != Phase.IDLE && phase != Phase.JOINING) {
            eliminationOrder.add(uuid);
        }
        saveSessions();
        if (phase == Phase.JOINING) {
            broadcast(server, player.getGameProfile().name() + " left the event registration.");
        }
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
        seatPositions.clear();
        paintRandomFloor();
        stopMusic();

        List<String> tracks = config.musicSounds;
        String selected = tracks.get(ThreadLocalRandom.current().nextInt(tracks.size()));
        Identifier soundId = Identifier.tryParse(selected);
        SoundEvent sound = soundId == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(soundId);
        if (sound == null) {
            soundId = Identifier.fromNamespaceAndPath("minecraft", "music.game");
            sound = BuiltInRegistries.SOUND_EVENT.getValue(soundId);
        }
        if (sound != null && soundId != null) {
            activeMusicId = soundId;
            eventLevel.playSound(null, new BlockPos(0, FLOOR_Y + 1, 0), sound,
                    SoundSource.MUSIC, 4.0F, 1.0F);
        }

        int min = Math.min(config.musicMinSeconds, config.musicMaxSeconds);
        int max = Math.max(min, config.musicMaxSeconds);
        int seconds = ThreadLocalRandom.current().nextInt(min, max + 1);
        phaseEndsAt = System.currentTimeMillis() + seconds * 1000L;
        nextColorChangeAt = System.currentTimeMillis() + 700L;
        broadcast(server, "Dance Floor round " + (completedRounds + 1) + "! Keep moving while the music plays (" + seconds + " seconds).");
    }

    private void beginSeatRush(MinecraftServer server) {
        stopMusic();
        phase = Phase.SEAT_RUSH;
        paintFloor(Blocks.CONCRETE.black().defaultBlockState());
        List<BlockPos> available = floorPositions();
        Collections.shuffle(available, RANDOM);

        int seatsPercent = completedRounds < config.earlyRatioRounds
                ? config.earlySeatPercent : config.lateSeatPercent;
        int desiredSeats = Math.max(1, (int) Math.floor(alivePlayers.size() * seatsPercent / 100.0));
        desiredSeats = Math.min(desiredSeats, available.size());

        seatPositions.clear();
        seatPositions.addAll(available.subList(0, desiredSeats));
        for (BlockPos pos : seatPositions) {
            eventLevel.setBlockAndUpdate(pos, Blocks.WOOL.lime().defaultBlockState());
        }

        phaseEndsAt = System.currentTimeMillis() + config.seatTimeSeconds * 1000L;
        broadcast(server, "MUSIC STOPPED! Find a lime cushion! " + desiredSeats + " cushion(s) for "
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
            BlockPos floorUnderPlayer = player.blockPosition().below();
            if (seatPositions.contains(floorUnderPlayer) && occupiedSeats.add(floorUnderPlayer)) {
                survivors.add(uuid);
            } else {
                eliminated.add(uuid);
            }
        }

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
                if (item != null && item.id != null && item.count > 0) saved.items.add(new PendingItem(item.id, item.count));
            }
            savePendingRewards();
            return;
        }

        for (String command : prize.commands) {
            executeRewardCommand(server, command, name, place);
        }
        for (PartyGamesConfig.ItemReward rewardItem : prize.items) {
            if (rewardItem == null || rewardItem.id == null || rewardItem.count < 1) continue;
            ItemStack stack = createStack(rewardItem.id, rewardItem.count);
            if (stack.isEmpty()) {
                PartyGamesMod.LOGGER.warn("Ignoring invalid PartyGames reward item id: {}", rewardItem.id);
                continue;
            }
            player.getInventory().add(stack);
            if (!stack.isEmpty()) saved.items.add(new PendingItem(rewardItem.id, stack.getCount()));
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
            executeRewardCommand(player.level().getServer(), command, player.getGameProfile().name(), 0);
        }
        saved.commands.clear();

        List<PendingItem> leftovers = new ArrayList<>();
        for (PendingItem reward : saved.items) {
            ItemStack stack = createStack(reward.id, reward.count);
            if (stack.isEmpty()) continue;
            player.getInventory().add(stack);
            if (!stack.isEmpty()) leftovers.add(new PendingItem(reward.id, stack.getCount()));
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

    private void executeRewardCommand(MinecraftServer server, String configuredCommand, String playerName, int place) {
        if (configuredCommand == null || configuredCommand.isBlank()) return;
        String command = configuredCommand
                .replace("%player%", playerName)
                .replace("%uuid%", "")
                .replace("%place%", Integer.toString(place));
        while (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) return;
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withMaximumPermission(PermissionSet.ALL_PERMISSIONS), command);
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not execute PartyGames reward command: {}", command, e);
        }
    }

    private ItemStack createStack(String itemId, int count) {
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
            joinedPlayers.remove(uuid);
            alivePlayers.remove(uuid);
            return;
        }

        ServerLevel destination = null;
        try {
            Identifier dimensionId = Identifier.tryParse(point.dimension);
            if (dimensionId != null) {
                destination = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
            }
        } catch (Exception ignored) {
            // Fall back to the overworld below.
        }
        if (destination == null) {
            destination = server.overworld();
            BlockPos spawn = new BlockPos(0, FLOOR_Y, 0);
            point.x = spawn.getX() + 0.5;
            point.y = spawn.getY() + 1.0;
            point.z = spawn.getZ() + 0.5;
        }

        player.teleportTo(destination, point.x, point.y, point.z, Set.of(), point.yaw, point.pitch, true);
        player.setPermanentlyInvulnerable(point.invulnerable);
        player.getAbilities().mayBuild = point.mayBuild;
        player.getAbilities().instabuild = point.instabuild;
        player.getAbilities().mayfly = point.mayfly;
        player.getAbilities().flying = point.flying && point.mayfly;
        player.onUpdateAbilities();
        player.sendSystemMessage(Component.literal("You have been returned from PartyGames.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        returnPoints.remove(uuid);
        joinedPlayers.remove(uuid);
        alivePlayers.remove(uuid);
        saveSessions();
    }

    private ReturnPoint snapshot(ServerPlayer player) {
        ReturnPoint point = new ReturnPoint();
        point.dimension = player.level().dimension().identifier().toString();
        point.x = player.getX();
        point.y = player.getY();
        point.z = player.getZ();
        point.yaw = player.getYRot();
        point.pitch = player.getXRot();
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
        int radius = config.arenaRadius;
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                boolean wall = Math.abs(x) == radius + 1 || Math.abs(z) == radius + 1;
                if (wall) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++) {
                        eventLevel.setBlockAndUpdate(new BlockPos(x, y, z), glass);
                    }
                } else {
                    BlockState floor = DANCE_COLORS.get(RANDOM.nextInt(DANCE_COLORS.size()));
                    eventLevel.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), floor);
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
                        eventLevel.setBlockAndUpdate(new BlockPos(x, y, z), air);
                    }
                }
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
        ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(activeMusicId, SoundSource.MUSIC);
        for (UUID uuid : new ArrayList<>(joinedPlayers)) {
            ServerPlayer player = eventLevel == null ? null : eventLevel.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) player.connection.send(packet);
        }
        activeMusicId = null;
    }

    private void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message).withStyle(ChatFormatting.LIGHT_PURPLE), false);
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

        public PendingItem() {
        }

        public PendingItem(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }
}
