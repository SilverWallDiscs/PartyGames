# PartyGames

**Current version: 0.2.1**

PartyGames is a server-side Fabric mod for recurring SMP minigame events. Players join from a clickable chat announcement, are sent to a protected event dimension, compete, and receive configurable rewards.

## Current status

The available minigames are **Dance Floor** (also selectable as *Music Chairs*) and **Horse Race**:
- Automatic events on a configurable interval, plus an admin start command.
- A purple announcement with a green clickable **[Participate]** button.
- A dedicated flat **partygames:event** dimension supplied by an always-enabled built-in data pack.
- The dance floor changes concrete colors while a random configured Minecraft music sound plays.
- The sound stops after a random 10–30 seconds; the floor turns black and colored cushion entities appear. Players must right-click a free cushion to claim it.
- For the first three cushion rounds the seat ratio defaults to 80%; after that it defaults to 50%. Seat counts are rounded down with a minimum of one seat while more than one player remains.
- The last player standing wins. The next two places are awarded from the elimination order.
- Event participants are made invulnerable; block placement/breaking, item use and item dropping are blocked. Commands are disabled while inside the event dimension. The player's original dimension, position, view, game mode, invulnerability and flight/build abilities are restored afterward.
- First-, second- and third-place prizes support multiple full item stacks (including custom components) and multiple commands. Use **/partygames modify** to open the admin editor; click **Add a command**, enter the command in chat when prompted, and click an existing command to remove it.
- If reward items do not fit in a player's inventory, the leftovers are persisted and can be claimed with **/partygames retrieve**.
- Return locations and pending rewards are persisted so a server restart does not strand a player in the event dimension or erase unclaimed items.
- A participant who falls below Y=0 is teleported back onto the arena floor, with velocity and fall distance reset.
- Disconnect handling leaves player entity and chunk-tracking cleanup to vanilla Minecraft. PartyGames never manually removes stale ServerPlayer entities or sends custom remove-entity packets, avoiding duplicate-UUID and DistanceManager corruption.
- On server shutdown, the active event is stopped without awarding prizes, temporary event entities are cleaned up, and player return locations remain saved so participants can be restored when they reconnect after the restart.
- Reconnecting players keep their original pre-event snapshot if they rejoin before reconnect recovery runs; players stranded in the event dimension outside an active game are returned to the Overworld spawn with usable survival abilities.
- Event invulnerability is enforced temporarily by the player-protection mixin rather than setting permanent invulnerability on the player; reconnect recovery runs immediately on join and resynchronizes the client's game mode when returning.
- A per-tick recovery watchdog retries pending return sessions, moves unregistered players out of the event dimension during any phase, repairs stale Survival `mayBuild=false` state, and logs the exact player mode/abilities during reconnect and restoration for diagnosis.
- Reconnect restoration is scheduled after the JOIN callback, once vanilla has registered the new player instance. Commands to rejoin during this short recovery window are queued and processed after restoration.

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
5. Restart the server. The Horse Race schematic is bundled in the JAR; no external schematic file is required. An optional **config/partygames/horserace.schem** overrides the bundled course.

The dance arena is generated at the origin of **partygames:event**. Horse Race pastes its bundled schematic at X=5120 (320 chunks away) in that same reserved dimension.

## Commands

- **/partygames join** — join during the registration window.
- **/partygames status** — view event state and player counts.
- **/partygames retrieve** — retrieve pending prize items or queued offline command rewards.
- **/partygames start** — open Dance Floor registration immediately (operator permission required).
- **/partygames start Music Chairs** — alias for Dance Floor / musical chairs (operator permission required).
- **/partygames start Horse Race** — open registration for the schematic-based horse race.
- Horse Race supports up to 6 riders, places their saddled horses on the lime-wool start line, and gives each horse a different coat pattern with the same base movement speed. Shift-dismount is blocked until results are declared; fallen horses are teleported back above the course. The 5-second private countdown opens the stone-brick/barrier gate at GO. Crossing the oak-wood finish strip under the black-and-white wool flag records the first three places. Touching magenta glazed terracotta grants Speed III for 3 seconds.
- **/partygames stop** — cancel the current event and return participants (operator permission required).
- **/partygames reload** — reload the JSON configuration (operator permission required).
- **/partygames modify** — open the prize editor (operator permission required).

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

The **commands** list is intentionally generic and is executed with the winner as the command source/entity and server-level permissions. This means selectors such as **@s** target the winner and **@p** resolves relative to their position. Replace examples with the syntax expected by your installed economy mod or command bridge.

Item rewards that cannot fit are saved to **config/partygames/pending-rewards.json**; **/partygames retrieve** attempts to deliver them again. Console commands are executed once when a reward is awarded (or when an offline player's queued command reward is claimed). Keep reward commands trusted and admin-controlled.

## Build

The GitHub Actions workflow builds the project with Java 25 and Gradle 9.7.1 on every push and pull request. The JAR is uploaded as a workflow artifact.

## Development notes

This is the first minigame implementation, not the final minigame framework. The event lifecycle is kept separate enough to add new games in future commits. Since no server binary/world is available in CI, actual in-game testing is still required after the build passes.
