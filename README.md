# AntiBaritone

AntiBaritone is a Paper/Bukkit plugin for Minecraft 1.21.x servers that flags players whose block interaction rotations look like Baritone bot rotations.

## Build

```powershell
.\gradlew.bat build
```

The compiled plugin jar is written to:

```text
build/libs/AntiBaritone-1.0.0.jar
```

## Install

Place `AntiBaritone-1.0.0.jar` in the server `plugins` folder and restart the server.

## Commands

```text
/antibaritone status <player>
/antibaritone reset <player>
/antibaritone reload
/punishment <kick|ban|command|warn staff>
```

## Permissions

```text
antibaritone.command
antibaritone.alert
```

## Detection Model

The first detector focuses on rotation-to-block precision:

- watches player rotations before block damage, block break, block place, and right-click block use events,
- compares the player's look vector to likely Baritone target points on the block,
- scores very small angle errors,
- adds weight for sharp rotation snaps before interactions,
- adds weight for almost-instant retarget snaps immediately after a block breaks,
- adds weight for straight, low-height tunnel excavation streaks,
- adds only modest extra weight for ore and high-value ore blocks,
- decays evidence over time,
- runs the configured punishment (warn staff, kick, ban, or custom command) when a player reaches a score of 100.

This is intentionally evidence-based. Server-side observation cannot honestly guarantee perfect Baritone detection, so the plugin reports suspicious precision patterns with context for staff review.

