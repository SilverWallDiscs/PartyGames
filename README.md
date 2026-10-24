# PartyGames

PartyGames is a server-side Fabric mod for recurring SMP minigame events. Players join from a clickable chat announcement, are sent to a protected event dimension, compete, and receive configurable rewards.

## Current status

The first playable minigame is **Dance Floor**:
- Automatic events on a configurable interval, plus an admin start command.
- A purple announcement with a green clickable **[Participate]** button.
- A dedicated flat **partygames:event** dimension supplied by an always-enabled built-in data pack.
- The dance floor changes concrete colors while a random configured Minecraft music sound plays.
- The sound stops after a random configured duration (20–60 seconds by default); the floor turns black and lime-wool cushions appear.
- For the first three cushion rounds the seat ratio defaults to 80%; after that it defaults to 50%. Seat counts are rounded down with a minimum of one seat while more than one player remains.
- The last player standing wins. The next two places are awarded from the elimination order.
- Event participants are made invulnerable; block placement/breaking, item use, entity interactions and item dropping are blocked. Their original dimension, position, view, invulnerability and flight/build abilities are restored afterward.
- First-, second- and third-place prizes support multiple item stacks and multiple console commands.
- If reward items do not fit in a player's inventory, the leftovers are persisted and can be claimed with **/partygames retrieve**.
- Return locations and pending rewards are persisted so a server restart does not strand a player in the event dimension or erase unclaimed items.

PartyGames is server-side. Players do not need to install the mod on their clients.

## Requirements

- Minecraft Java Edition **26.3**
- Fabric Loader (the project currently builds against 0.20.1)
- Fabric API for 26.3
- Java 25

## Installation

1. Install Fabric Loader and Fabric API on the server.
2. Put the PartyGames JAR into the server's **mods** folder.
3. Start the world once so the built-in event-dimension data pack can be registered and the config generated.
4. Open **config/partygames/config.json** to customize event timing and rewards.
5. Restart the server after first installation or after changing the bundled dimension data.

The arena is generated/cleared at the origin of **partygames:event**; that dimension is reserved for PartyGames.

## Commands

- **/partygames join** — join during the registration window.
- **/partygames status** — view event state and player counts.
- **/partygames retrieve** — retrieve pending prize items or queued offline command rewards.
- **/partygames start** — open registration immediately (operator permission required).
- **/partygames stop** — cancel the current event and return participants (operator permission required).
- **/partygames reload** — reload the JSON configuration (operator permission required).

## Configuration

The file is created automatically at **config/partygames/config.json**. The generated defaults are:

- **eventIntervalMinutes**: automatic event interval.
- **joinDurationSeconds**: how long players can click **[Participate]**.
- **minimumPlayers**: minimum number required to start.
- **musicMinSeconds / musicMaxSeconds**: random dance duration.
- **seatTimeSeconds**: time to reach a cushion after the music stops.
- **earlyRatioRounds / earlySeatPercent / lateSeatPercent**: cushion ratio progression.
- **arenaRadius**: radius of the concrete floor.
- **musicSounds**: registry identifiers of sound events to choose randomly.
- **first / second / third**: each prize has an **items** array (**id**, **count**) and a **commands** array.

Example prize section:

```json
{
  "first": {
    "items": [
      { "id": "minecraft:diamond", "count": 16 },
      { "id": "minecraft:gold_ingot", "count": 32 }
    ],
    "commands": [
      "eco give %player% 500"
    ]
  }
}
```

The **commands** list is intentionally generic: each command runs as the server console. Replace the example with the correct command for your installed economy mod/plugin. This enables integration through a command bridge, including economy systems such as Impactor or Vault-compatible server stacks, without bundling or falsely claiming a direct Vault API dependency. On a pure Fabric server, Vault itself is a Bukkit API and needs a compatible bridge/plugin environment; PartyGames does not provide that bridge.

Item rewards that cannot fit are saved to **config/partygames/pending-rewards.json**; **/partygames retrieve** attempts to deliver them again. Console commands are executed once when a reward is awarded (or when an offline player's queued command reward is claimed). Keep reward commands trusted and admin-controlled.

## Build

The GitHub Actions workflow builds the project with Java 25 and Gradle 9.7.1 on every push and pull request. The JAR is uploaded as a workflow artifact.

## Development notes

This is the first minigame implementation, not the final minigame framework. The event lifecycle is kept separate enough to add new games in future commits. Since no server binary/world is available in CI, actual in-game testing is still required after the build passes.
