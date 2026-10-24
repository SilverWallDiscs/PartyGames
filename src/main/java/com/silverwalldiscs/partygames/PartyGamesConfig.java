package com.silverwalldiscs.partygames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PartyGamesConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int eventIntervalMinutes = 30;
    public int joinDurationSeconds = 60;
    public int minimumPlayers = 2;
    public int seatTimeSeconds = 6;
    public int musicMinSeconds = 10;
    public int musicMaxSeconds = 30;
    public int earlyRatioRounds = 3;
    public int earlySeatPercent = 80;
    public int lateSeatPercent = 50;
    public int arenaRadius = 12;
    public List<String> musicSounds = new ArrayList<>(List.of(
            "minecraft:music_disc.otherside",
            "minecraft:music_disc.tears",
            "minecraft:music_disc.precipice",
            "minecraft:music_disc.creator",
            "minecraft:music_disc.pigstep",
            "minecraft:music_disc.bounce",
            "minecraft:music_disc.lava_chicken"
    ));
    public Prize first = Prize.defaultFirst();
    public Prize second = Prize.defaultSecond();
    public Prize third = Prize.defaultThird();

    public static PartyGamesConfig load(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                PartyGamesConfig defaults = new PartyGamesConfig();
                save(path, defaults);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                PartyGamesConfig loaded = GSON.fromJson(reader, PartyGamesConfig.class);
                if (loaded == null) loaded = new PartyGamesConfig();
                loaded.normalize();
                return loaded;
            }
        } catch (Exception e) {
            PartyGamesMod.LOGGER.error("Could not load PartyGames config; using defaults.", e);
            PartyGamesConfig defaults = new PartyGamesConfig();
            save(path, defaults);
            return defaults;
        }
    }

    public static void save(Path path, PartyGamesConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            PartyGamesMod.LOGGER.error("Could not save PartyGames config.", e);
        }
    }

    public void normalize() {
        eventIntervalMinutes = clamp(eventIntervalMinutes, 1, 10080);
        joinDurationSeconds = clamp(joinDurationSeconds, 10, 600);
        minimumPlayers = clamp(minimumPlayers, 2, 200);
        seatTimeSeconds = clamp(seatTimeSeconds, 2, 30);
        // Force the requested 10–30 second dance duration, including existing config files.
        musicMinSeconds = 10;
        musicMaxSeconds = 30;
        earlyRatioRounds = clamp(earlyRatioRounds, 0, 20);
        earlySeatPercent = clamp(earlySeatPercent, 10, 100);
        lateSeatPercent = clamp(lateSeatPercent, 10, 100);
        // Upgrade older configs to the roomier arena instead of retaining the old radius.
        arenaRadius = clamp(Math.max(arenaRadius, 12), 5, 20);
        musicSounds = new ArrayList<>(List.of(
                "minecraft:music_disc.otherside",
                "minecraft:music_disc.tears",
                "minecraft:music_disc.precipice",
                "minecraft:music_disc.creator",
                "minecraft:music_disc.pigstep",
                "minecraft:music_disc.bounce",
                "minecraft:music_disc.lava_chicken"
        ));
        if (first == null) first = Prize.defaultFirst();
        if (second == null) second = Prize.defaultSecond();
        if (third == null) third = Prize.defaultThird();
        first.normalize();
        second.normalize();
        third.normalize();
    }

    public Prize prizeForPlace(int place) {
        return switch (place) {
            case 1 -> first;
            case 2 -> second;
            case 3 -> third;
            default -> null;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Prize {
        public List<ItemReward> items = new ArrayList<>();
        public List<String> commands = new ArrayList<>();

        public void normalize() {
            if (items == null) items = new ArrayList<>();
            if (commands == null) commands = new ArrayList<>();
            items.removeIf(item -> item == null || item.id == null || item.id.isBlank() || item.count <= 0);
            commands.removeIf(command -> command == null || command.isBlank());
        }

        static Prize defaultFirst() {
            Prize prize = new Prize();
            prize.items.add(new ItemReward("minecraft:diamond", 8));
            return prize;
        }

        static Prize defaultSecond() {
            Prize prize = new Prize();
            prize.items.add(new ItemReward("minecraft:diamond", 4));
            return prize;
        }

        static Prize defaultThird() {
            Prize prize = new Prize();
            prize.items.add(new ItemReward("minecraft:diamond", 2));
            return prize;
        }
    }

    public static final class ItemReward {
        public String id;
        public int count;

        public ItemReward() {
        }

        public ItemReward(String id, int count) {
            this.id = id;
            this.count = count;
        }
    }
}
