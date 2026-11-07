# Locator Finder

Client-side Fabric mod for Minecraft 26.2. It works out player coordinates from the data the
server sends for the locator bar. Requires Fabric API.

## Usage

Positions appear in the top right of the screen.

| Command | Effect |
| --- | --- |
| `/lf` | List tracked players in chat |
| `/lf hud` | Turn the on-screen list on or off |
| `/lf clear` | Forget all tracked positions |

## What the server sends

| Target is | Data | Shown as |
| --- | --- | --- |
| Within 332 blocks, chunk in view distance | Exact block position | `Name: x y z (exact)` |
| Within 332 blocks, chunk out of view distance | Chunk position | `Name: x ~ z (±8)` |
| More than 332 blocks away | Angle only | `Name: x ~ z (±error)` once triangulated |

Beyond 332 blocks there is no distance, so the mod combines angles from different places.
Y is unknown in the last two cases.

To get a fix on a far target, **move sideways relative to it**. Walking toward or away from them
adds nothing, because every angle is the same. The further away they are, the further you need to
move. Bearings older than 90 seconds are dropped, and bearings that disagree with the rest are
discarded oldest first, so a target that moves is picked up again.

The server won't send anything for players who are spectating, or whose waypoint range is reduced
(for example by sneaking or wearing a mob head), or if the `locatorBar` gamerule is off.

## Building

Requires JDK 25.

```bash
./gradlew build
```

The mod is written to `build/libs/`. `./gradlew test` runs the triangulation tests, and
`./gradlew runClient` starts a dev client that checks the mixins at startup.
