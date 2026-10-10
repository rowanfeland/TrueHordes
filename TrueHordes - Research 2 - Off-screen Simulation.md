# True Hordes: research 2, off-screen simulation (Build 42.21)

Investigation only, no design and no code. It follows on from `TrueHordes - Limitations Report.md`
(especially its "Native findings" section). Java line numbers are lines in the decompiled 42.21 source; DLL
references are code addresses in the 42.21 `PZPopMan64.dll`.

---

## 1. Existing mods that already move hordes around the map

The B41 mod you remembered is almost certainly **Hordes** by derLoko. Workshop pages only were read
(the files weren't downloaded):

| Mod | Build | How it works (from its page) | Known problems (from its page and comments) |
|---|---|---|---|
| [Hordes Alpha 0.5 (B41)](https://steamcommunity.com/sharedfiles/filedetails/?id=2311468324), derLoko, mod ID `Hordes` | B41 | "Adds persistent hordes that roam the map". Pure Lua: an error report in the comments points at `HordeSimulated.lua` and `HordeManager.lua` | "Hordes ignore player-built structures when the player isn't in the area", "unable to navigate on most custom maps", stuck at the Rosewood Pizza Whirled; heavy FPS when a horde materialises; buggy in MP |
| [Hordes Alpha 0.7.2 (B42)](https://steamcommunity.com/sharedfiles/filedetails/?id=3618306391), LubsonGG (update of the above) | B42 | 300–500 "virtual" hordes that keep migrating in real time; convert to real zombies inside the loaded area and back to virtual when they leave; move "primarily along Nav (road) zones", optionally through forests and towns; leader zombie; multi-segment "chain hordes"; themed outfits; grow to a cap (70) and split; small temporary hordes form from idle zombies when a cell has 25+ | Single player only ("Multiplayer has syncing issues"); off-road navigation "can get stuck"; fights Wandering Zombies |
| [Living Hordes Beta 0.8](https://steamcommunity.com/sharedfiles/filedetails/?id=3691686404), Belerion, mod ID `ZombieMigration`, [docs](https://belerion63.github.io/Living-Hordes/) | B42 | **Switches the vanilla population system off** and owns every zombie. Three layers: group (a count + position + destination + seed), individual (a coordinate with outfit), real zombie. Real only within about 65 tiles and only if the engine's visibility test says the player could see it. Groups processed in 30 buckets (far ones less often, bigger steps). Routes between points of interest read from the map (police, military, hospitals, shelters) using noise waypoints; water map shipped with the mod; wide rivers block, streams slow. Hordes split, absorb each other on contact, and keep a "wardrobe" of outfits instead of individual zombies. Tracks player walls and wears them down by their real HP, even while you're away. New hordes enter from map edges | Solo only |

**What they have in common:** all three keep hordes "on paper" (Lua tables) and only materialise zombies near the
player. None of them works in multiplayer yet. The two Hordes versions depend on map zones for navigation and
fail on custom maps. Living Hordes ships pre-computed water data because, as its docs note, the engine "only
answers for terrain it has already loaded". **Section 3 shows a Java mod does not have that limit.**

---

## 2. Follow Sound Distance = 1000

Per the native findings, with `FollowSoundDistance` 1000:
- A standing virtual zombie woken by a sound walks up to 1000 tiles toward it (about 10× the default).
- Still in a **straight line**: it stops for good at the first wall, fence or river in the way.
- It still aims at the sound ± a quarter of its distance on each axis. At 800 tiles that is up to ±200 tiles.
- It switches to any nearer active sound that covers it while walking.
- Only the off-screen (virtual) zombies use this setting. Real zombies don't (their reaction is the Java
  `RespondToSound`).
- Combined with Louder Gunshots, one rifle shot can now move every outdoor zombie in a radius of hundreds of
  tiles most of the way to the shooter. Towns empty toward gunfire much faster, which may be exactly the
  "town empties into one horde" effect you want, but it happens all at once rather than over weeks.

---

## 3. Can a Java mod do off-screen pathfinding? Yes, with real map data

### The data exists for the whole map
- The game keeps a **tile-level collision grid for the entire map**, with five bits per tile: solid, wall on
  the north edge, wall on the west edge, water, room (`MapCollisionData.java:31-35`).
- It is loaded at start from `chunkdata_X_Y.bin` files that **ship with every map**
  (`MapCollisionData.java:122-126`). The vanilla map has 4,065 of them (22 MB, cells up to 77×62).
- Each save keeps updated copies in `Saves\<mode>\<save>\chunkdata\` for cells that have been loaded, so
  **player-built walls and changes are included** (`MapCollisionData.java:226-306` feeds every loaded chunk and
  changed square into it).
- The format is simple:
  - a version number (short = 1);
  - then for each of a cell's 32×32 chunks one type byte (0 empty, 1 solid, 2 regular, 3 water, 4 room);
  - "regular" chunks are followed by 64 per-tile bytes.
  - The game's own reader is `zombie.pot.POTChunkData` (`POTChunkData.java:12-94`), a map-conversion tool. Its
    constructor isn't public, so a mod would read the format itself.
- Custom maps ship these files too, because the game needs them, so this works on modded maps without
  hand-made data.

### Who can read it
- **Java: yes**, both the map files and the save's updated copies.
- **Lua: probably only the map's copies.** `getGameFilesInput(path)` returns a binary reader for files inside
  the game folder that the game has indexed (`LuaManager.java:6967`, `ZomboidFileSystem.java:109`, `:366`). The
  save's updated copies are not in that index. Test T13 below checks the first part. Even if it works, parsing
  millions of tiles in Lua is slow; it would have to be reduced to a coarse grid.

### The DLL's own path search
- `MapCollisionData.n_pathTask(startX, startY, endX, endY, out)` runs the same native search used for Last
  Stand virtual zombies, over the map-wide grid (`0x1800242d0` → `0x180017870` / `0x180017a80`).
- It returns a status and **one point** (where the search ended), not the list of waypoints. That makes it
  useful for "can a horde get from A to B, and how close does it get", not as a full route planner.
- It is private (reflection from Java) and has no Java callers in 42.21. It must run on the popman thread,
  because that thread modifies the same data.

### Realistic approach (for your design, not a design)
- Own route planning in Java on a background thread, over a coarse grid built from that data. For example, an
  8×8-tile cell per node with costs:
  - roads cheap (`Nav` zones and `streets.xml`);
  - fields and forest moderate (zones);
  - buildings and walls blocking (solid, wall, room bits);
  - water by width (narrow is passable, wide blocks).
- Tile-level checks only near the end of a route or when a horde is about to become real.
- A* over a few million coarse nodes is cheap for a handful of route requests per minute.

---

## 4. Taking zombies into and out of the native simulation (Java)

For hordes that "pick up 1 or 2 or 10 zombies on the road", the mod needs to remove specific off-screen zombies
from the native simulation and add them to a horde. The DLL has no "remove zombie" call, but its chunk-load
command does it as a side effect:
- `n_loadChunk(wx, wy, true)` makes the DLL hand **all standing virtual zombies of that 8×8 chunk** to Java as
  records: position, direction, outfit, state flags and `persistentId` (`0x18001be00` → `0x18000c580`, read back
  by `ZombiePopulationManager.updateMain`, `ZombiePopulationManager.java:514-555`).
- Vanilla Java would try to spawn them, find no loaded square, and give them straight back
  (`ZombiePopulationManager.java:669`).
- A ZombieBuddy patch on `addZombieStanding` / `addZombieMoving` could keep them as horde members instead.
  Then `n_loadChunk(wx, wy, false)` marks the chunk unloaded again.
- Putting zombies back: `n_addZombie(x, y, z, dir, outfit, state, targetX, targetY, persistentId)` re-adds them
  as virtual zombies (standing, or moving toward a target). Near players, Lua can spawn them as real ones.
- **This needs a prototype test.** It uses private native calls, and the native side may have bookkeeping
  (for example its record of real zombies) that this upsets. It is the only precise way I found to absorb
  existing off-screen zombies; the alternatives are whole-cell (`zpopClearZombies`) or Living Hordes' approach
  of switching the vanilla population off and owning everything.

---

## 5. The behaviours you listed

| Behaviour | What the game gives a mod | Verdict |
|---|---|---|
| Wander until picked up; join on contact; hordes merge into a mega horde; part of a horde breaks away for a gunshot | Nothing in vanilla (rally groups are local, real-only, and only spread apart). All of it would be mod logic on paper hordes. | **Possible**, in the mod's own simulation. Needs the absorb step in section 4, or owning the whole population. |
| Prefer roads but leave them | `Nav` zones and `streets.xml` for roads; zones for forest and fields; collision grid for walls and water (section 3) | **Possible** for paper hordes and real zombies. Not for native virtual zombies, which only walk straight lines. |
| Lightning or thunder pulls a horde off the road | Storms create strike and rumble events at real map coordinates. Lua: `Events.OnThunderEvent(x, y, strike, lightning, rumble)` (`ThunderStorm.java:396`). **Thunder makes no world sound**, so vanilla zombies ignore it. On a dedicated server the event is only sent to clients (`ThunderStorm.java:364-373`); the server needs a Java hook on `triggerThunderEvent` or a client relay. | **Possible** |
| A gunshot splits a horde | `Events.OnWorldSound` fires on the server for all player sounds (`WorldSoundPacket.java:139`). | **Possible** (mod logic) |
| A horde sees base lights after the power goes off | Zombies never walk toward light. Light only changes how easily they spot a player (`IsoZombie.java:1866`, `:5080`). The mod would have to know where lit bases are: record generators, light switches and the building when that area is loaded, and work out grid power from the sandbox shut-off date. | **Possible with limits**: approximate. There is no off-screen line of sight; "visible from N tiles at night" would be a mod rule. |
| A generator keeps a horde captive until it runs dry | Generators don't run while unloaded. When the chunk reloads, they deduct all the fuel for the hours that passed (`IsoGenerator.java:187-213`, `lastHour` saved with the chunk). A mod can predict the run-out time from fuel and consumption recorded at unload. | **Possible** |
| Hordes leave destruction off-screen | Unloaded chunks can't be edited. The pattern that works (and Living Hordes uses) is a ledger of pending damage per chunk or structure, applied when the chunk loads (Lua `LoadGridsquare` / `LoadChunk`, or a Java hook). Broken windows and doors, player walls by their real HP, corpses and blood are all doable at that point. Player structures have to be recorded while loaded to be known off-screen. | **Possible with limits**: damage appears on arrival, not live. Editing save files directly would be risky and I'd advise against it. |
| Hordes absorb towns over weeks | Paper hordes plus the absorb step (section 4), or owning the population. Native respawn has to be off or controlled (it refills loaded cells only). | **Possible** (mostly Java for the absorbing) |

---

## 6. Corrections and smaller findings

- **Virtual census:** the native "radar" call returns every moving unit plus the standing zombies of a single
  cell (`0x18002253e-0x18002268b`). It is a debug helper, not a census. Counting still needs the renderer
  callbacks (Limitations report, item 2) or the absorb step.
- **Multiplayer:** every existing horde mod is solo-only. In MP the paper simulation must live on the server
  (all three mods run it in Lua; Java would also need ZombieBuddy on the server), and real zombies near players
  are run by their owning client (Limitations report, item 10). That is the unsolved part in existing mods.

---

## 7. The big choice this leaves for your design

1. **Keep the native population and steer it.** Lua only. Sounds wake standing zombies and aggro points steer
   moving ones; straight lines; no roads off-screen. Cheapest, but the horde behaviours above don't happen
   off-screen.
2. **Hybrid.** Native zombies stay as the "stragglers". The mod's paper hordes walk real routes, absorb
   stragglers chunk by chunk as they pass (Java, section 4), and hand them back when they split or disperse.
   Closest to "zombies wander until picked up by a horde".
3. **Replace.** Switch the vanilla population off and own every zombie (Living Hordes' route). Full control,
   largest amount of work, and it replaces how the whole game populates the world.

---

## New tests

**T13: can Lua read the collision files?** One console line:

```
local f=getGameFilesInput("media/maps/Muldraugh, KY/chunkdata_40_20.bin");print(f and f:readShort() or "nil")
```

- `1` = Lua can read the map's collision data.
- `nil` = it can't (Java only).
- An error = the reader exists but its methods aren't usable from Lua.

**T14: thunder event.** During a storm, in single player:

```
Events.OnThunderEvent.Add(function(x,y,s,l,r) print("THUNDER",x,y,s,l,r) end)
```

Lines in `console.txt` with coordinates far from you show that thunder anywhere on the map is visible to a mod.
