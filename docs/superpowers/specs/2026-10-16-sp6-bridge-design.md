# Sub-project 6: Materialisation bridge (design)

Date: 2026-10-16. Builds on sub-projects 3-5. Renumbered roadmap (user): 6 bridge, 7 multiplayer, 8 consequences
while away, 9 tuning and compatibility.

## Intent

The loaded area must never feel like a wall or a spawn line (D17). The feel we want is the cabin in the woods (section
"The scenario we are aiming for"): one moan, then five, then a hundred wandering past the house and on out of sight. The
other feel is the Louisville mega-horde moving through the city, part of it on screen and part of it off. Real zombies
follow the same rules as held ones: they mill, roam the roads, form and join hordes, follow sounds and lights, and walk
out of the loaded area as easily as they walked in. This has to stay cheap enough for a dedicated server.

## Goals

1. **Real zombies are piloted (spec goal 1).** Every real zombie the mod spawned, or caught, gets a light *pilot* on
   the main thread: its position, heading, horde id, follow target, lure target and state. When vanilla is not busy
   with it, the pilot steers it with `pathToLocation` toward a waypoint about 12 to 20 tiles ahead. The waypoint comes
   from the same `Movement.advance` rules held walkers use: travel along roads and trails, side roads at real junctions,
   dead ends turn back, and cross-country legs. The pilot uses its own `Movement` instance, with no loaded-area fence.
2. **Vanilla keeps fighting.** A zombie with a target (a player, an NPC or an animal), or one that is thumping, eating,
   lunging, staggered or crawling at someone, is left entirely to vanilla. The pilot also waits while vanilla walks it
   to a sound that the mod's hearing let it hear (5a). It resumes from wherever the zombie then stands. So nothing about
   combat changes.
3. **Hearing and lures are the same rule.** When a piloted zombie hears a sound (`RealZombieHearing` says heard), its
   pilot takes the sound's spot as the lure for 6 in-game hours, as held zombies do. A real horde rolls once per sound
   (the 5a horde share rule): the first member to hear it decides for the whole horde, and the whole horde follows its
   leader there. Night lights (5b `LightLure`) already order real zombies; the pilot treats such an order as a lure too.
4. **Real hordes.** Members of a real horde follow a leader: the oldest member still piloted. The leader walks with
   `Movement.advance(members)`. Followers walk to their slot in the column behind it, using the same width and spacing
   as `HandOver`. Loose real zombies follow and join by the sim's own rules and day ramps: a bump (within 1.5 tiles)
   is one roll per pair, sight-follow and join-in-sight are chances per hour, a group of 5 or more becomes a horde, and
   a group below 5 dissolves. When two real hordes touch, they merge into the bigger one. A horde half on screen and
   half off stays one horde, as it is today: `HandOver` lays out the part that walks in, and members that walk out
   rejoin it.
5. **Both ways across the edge (D17).** Held walkers and hordes already walk in. Real ones now walk out because the
   pilot keeps them moving: the existing walk-off hook takes them, and they keep their heading, horde and lure. A real
   zombie that leaves after a sound or a light keeps its lure off screen.
6. **No cap, no culling, less strain (user).** Every zombie in the loaded area exists, but only the zombies a player
   could meet are real game objects:
   - A held zombie becomes real when it is within `RealRadius` tiles of a player (default 50), or anywhere a player
     could see it: inside the loaded area and inside the player's view cone (120 degrees, out to the loaded edge, so
     Viewpoint's long view still shows real zombies).
   - A real zombie becomes held again, in place and still walking, when all of these hold:
     - it is more than `RealRadius + 15` tiles from every player;
     - it is outside every player's view cone;
     - vanilla is not busy with it (goal 2).
   - Held zombies inside the loaded area keep moving on the simulation thread like any other held zombie, so nothing
     stops or vanishes. Only the game objects come and go, behind the player.
   - Building zombies (dormant) use the same rule.
   - `RealRadius 0` turns the trick off (the whole loaded area is real, as now).
   - The vanilla cull stays off. In MP it will be set off on the server in sub-project 7.
7. **Steering budget.** At most `PilotOrdersPerTick` path orders per tick (default 25, Claude's call). Each pilot is
   re-ordered when it reaches its waypoint, or every 4 to 8 real seconds, whichever is first. Pilot bookkeeping runs at
   most 2 ms per tick (Claude's call).
8. **The horde tag survives saves.** A real zombie's pilot state is written into its ModData under `TrueHordes`:
   horde id, heading, follow id, lure spot and lure end. It is read back when the game reloads the zombie, so hordes
   re-form after a reload.
9. **Millers wander anywhere; hordes sometimes cut through.** Lone millers (the sim's lingering and idle walkers) no
   longer favour roads: they drift over yards, fields and roads alike. A roaming horde now and then leaves the road
   across a yard, house lot or field (the same chance as the off-road roll, 15% per decision), carrying straight on
   until it meets another road or trail. Held and piloted walkers use the same rule, because both use `Movement`.
10. **Why the Muldraugh highway horde never turns into town.** Find out why the 2% turn-off never fires, and fix it.
    The cause may be a bug or a rule; record which in the progress notes.
11. **Map-edge respawns arrive as hordes.** Respawn groups that reach the map's edge are held back per edge stretch
    until a batch is full, then released as one horde heading inland. The batch size is
    `min(2000, 50 + 5 x days)`: about 200 at a month and 1,850 at a year (Claude's call; the user tunes it).
12. **Crowd-scaled break-in (D15).** The damage a zombie deals to a door, window, barricade or player-built wall is
    multiplied so that the time to break through follows D15. Let N be the number of real zombies thumping within 3
    tiles. A lone zombie is about 5 in-game hours for a window and 24 for a door; 10 zombies about 1 hour and 5 hours;
    1,000 zombies about 10 minutes for either. Between those points, the time is interpolated on a log-log scale. The
    vanilla base damage is measured from the decompiled game and scaled to those times. Option `CrowdBreakIn` (on).
13. **Seeing hordes coming (user).** Held zombies beyond the loaded area, out to `FarSightTiles` (default 200), are drawn
    as dark figure outlines on the ground where the simulation has them. They are interpolated along their heading so
    they walk smoothly rather than jumping each update. Drawing:
    - at most 600 figures, the nearest first;
    - only on screen;
    - fainter at night and in rain.

    This shows what is coming before it pops in. Option `FarSight` (on). Single player in this sub-project; multiplayer
    sends the list to clients in sub-project 7.
14. **Test commands (user).** Both are sim-side in this sub-project. Sub-project 7 makes them admin-only commands on
    the server.
    - `Hordes_roadHorde(n [, x, y])` spawns a held horde of `n` on the nearest highway lane about 250 tiles from the
      player (or at x, y), heading along the road towards the player. With no coordinates near Muldraugh, it uses the
      Muldraugh highway.
    - `Hordes_ringHordes(count, size, radius)` spawns `count` dormant outdoor hordes of `size` evenly spaced on a
      circle of `radius` tiles round the player, on ground they can stand on. They stay idle until a sound or a louder
      horde's moaning sets them loose, by the same rule as building hordes, so a gunshot draws them all in.

## Non-goals

- Changing vanilla combat, chasing, or what a zombie does once it has seen a player.
- A cap on zombie numbers, or any culling.
- Multiplayer wiring (sub-project 7).

## Anti-goals

- Zombies visibly appearing or vanishing near or in front of a player.
- Real zombies standing frozen in the loaded area (the day-12 house).
- A frame-time cost that grows with the pilot count beyond the per-tick budget.

## Options (new)

| Option | Default |
|---|---|
| `RealRadius` | 50 (0 = whole loaded area) |
| `PilotOrdersPerTick` | 25 |
| `CrowdBreakIn` | on |
| `FarSight` | on |
| `FarSightTiles` | 200 |
| `EdgeHordes` | on |

## Acceptance (added to docs/TESTING.md)

1. Stand still in a town and watch: real zombies keep wandering, and some walk off out of sight. None stand frozen
   unless they are building zombies or stragglers.
2. Fire a gun: the real zombies that hear it go to the spot, then wander on, and some leave the loaded area.
3. With `Hordes_roadHorde(300)`, the horde shows as outlines far up the highway, walks in, becomes real without popping,
   walks past and out again, and stays one horde (debug map).
4. With `Hordes_ringHordes(6, 100, 120)` and a gunshot, all six converge.
5. Behind the player, real zombies thin out past 65 tiles. FPS with 1,000 zombies in the loaded area is near the FPS
   with 300.
6. Save and reload with a real horde on screen: it is still one horde.
7. A real crowd of 20 or more breaks a door in well under an hour.
