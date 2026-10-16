# Sub-project 5b: events (design)

Branch: new branch from `main` after `sp5a-sound-core` is merged. Builds on 5a (sound model, capture of every
`WorldSoundManager.addSound`, falloff, lone roll, horde share and hive mind, 6 dB switch, sound viewer).

## Intent

"Be quiet or else." Noise brings dread: far events mean "I might have to hide" as streets slowly fill; near events mean
"get out, hide in the base, or fight." Over the first year the world gets quieter while hordes grow, so every sound
matters more. Everything emergent, everything through the 5a rules.

## Goals

1. **Far meta events** (replace vanilla's gun-event schedule and random ambients):
   - Schedule: month 1 one every 1-3 in-game days, the gap leaning towards 3 as the month ends; from month 1 to 12 the
     gap stretches to about 31 days; after month 12 about monthly (Claude's call). The day is random.
   - Kinds, kept as data (kind, sound files, world radius, burst, placement, schedule) so the user can add files
     and custom events later:
     - gunshots: vanilla's 6 files (`MetaAssaultRifle1`, `MetaPistol1-3`, `MetaShotgun1` twice). A file that sounds
       like a burst becomes that many world sounds a few tenths of a second apart (the builder measures each file's
       shots); each shot is its own 5a roll (firefights chip at hordes);
     - screams (`MetaScream`, radius 60) and dog barks (`MetaDogBark`, radius 80) attract (radii Claude's call);
     - owls and wolf howls stay ambience only (vanilla's 4-week city rule kept).
   - Gunshot radius 1:1 with the user's Louder Gunshots mod: a rifle's 200 x `LouderGunshots.Multiplier` when that mod
     is active, else vanilla's 600.
   - Placement: towns and roads; rural kinds in the countryside; a lone-survivor gunshot anywhere. Never near the
     player (vanilla puts them 500 tiles away; keep a 300-1000 tile band, Claude's call).
   - The player hears direction and distance: positional audio at the event's real spot, volume by the same falloff
     (500 tiles faint, 50-100 loud), in iso and Viewpoint.
   - A-Life noise and A-Life events do not count towards this schedule.
2. **Near events** (own schedules, can overlap far ones), 50-100 tiles from the player:
   - **Car alarm:** a real parked car in that band (loaded) has its alarm set off with the game's own alarm. The player
     turns it off or lets the battery drain. Month 1 every 2-3 days; then a 10% chance every 20 days; never after
     month 12. If the car unloads while sounding, the mod keeps a repeating ALARM sound there for the held simulation
     and, when it loads again, drains the battery by the elapsed time (stops the alarm if flat). No car in the band:
     skip this one.
   - **Near gunshot** (a soon-dead survivor): its own event on the far gunshots' schedule rules but separate rolls:
     month 1 every 1-3 days; after month 1 a 10% chance per 31 days.
3. **A-Life compatibility layer** (Project A-Life NPCs, workshop 3803984183): its on-screen gunfire and its own meta,
   raid and heli events already call `addSound` (5a captures them). Its off-screen squad fights
   (`ProjectALife.OfflineCombat.apply(outcome, hours)`, returns true when applied; `outcome` has `x`, `y`,
   `loserCasualties`, `winnerCasualties`) are silent: wrap that function and, after a true return, fire a burst of
   gunshots at (x, y): shots = 6 + 4 x casualties, capped at 60, over about a minute (Claude's call), each a 5a roll,
   radius as a rifle (Louder Gunshots aware).
4. **True Weathers compatibility layer** (`ViewpointTrueWeathersGameplay`, regional storms): reflection on
   `vtwg.Api.regional()` and `vtwg.Api.precipAt(x, y)` (0..1). Without it the whole map is inside vanilla's weather
   (`ClimateManager` precipitation).
   - Muffling: inside rain or a storm every sound carries less, by strength (a gunfight in a heavy thunderstorm draws
     far fewer zombies than on a clear day). Applied to held zombies always; to real zombies only when True Weathers'
     own hearing effect is off (it already muffles them; no double muffling). Size: up to -12 dB at full storm
     (Claude's call, tuning).
   - Thunder: heard by the player and zombies. Zombies inside a storm ignore thunder; outside a storm they hear it by
     falloff (lone zombies: the 5a lone roll, so some not all; a horde rolls once whether to turn to it, Claude's call).
     Storms away from the player thunder too, at a rate by storm strength (Claude's call), so weather moves zombies
     (a storm over Rosewood draws zombies from outside it).
   - Fog does nothing.
5. **Environment sounds:** vanilla already makes world sounds for thumping and breaking doors, windows and player-built
   fences (radius 20, breaks 10-20), generators, alarms and fires; 5a captures them, so crowds gathering on a fence are
   emergent. Nothing new.
6. **Lights as a lure**, only at night and only for lights lit after the grid shutoff (so generator-powered):
   - each lone zombie, and each horde as a whole, rolls once per light source: go to it or ignore it and wander past;
   - flashlight beam hitting a real zombie: 10% (checked for the few zombies the beam touches);
   - building lights and player-placed lights (floodlights): 25%;
   - streetlights (on a generator): 1%; one that takes it stays under the light until morning;
   - off in daylight. Seen from up to 60 tiles (buildings, floodlights) and 40 (streetlights) (Claude's call). Held
     zombies use the powered lights the mod has seen in loaded chunks (remembered while unloaded).
7. **Viewer:** the 5a sound layer labels the new kinds (meta scream, dog, car alarm, A-Life fight, thunder in storm)
   and shows True Weathers storm areas when present (Claude's call).

## Non-goals

Custom events themselves (only the data shape); A-Life's own meta schedule; changing vanilla thumping; anything about
walking in and out of the loaded area or Viewpoint draw distance (sub-project 6).

## Anti-goals

- An update to A-Life or True Weathers must never break True Hordes: each compatibility layer is its own file, runs
  only if the mod and the exact function exist, is wrapped in pcall / try-catch, logs once and does nothing on change.
- No copied code from other mods.
- No events near the player from the far schedule; no stutter (event work off the main thread except the audio).

## Constraints

PZ B42.21, ZombieBuddy Java patches plus Lua; single player first; sandbox options for every rate (defaults above);
all sounds through 5a (`SoundModel`, capture, viewer).

## Left to the builder

How to suppress vanilla's gun-event schedule and random ambients (patch `GameTime` gun-event days / `doGunEvent`,
`addRandomAmbient`) while keeping owls and wolves; measuring shots per burst file; picking a parked car in the band;
the flashlight beam test; save fields for schedules, sounding alarms and remembered lights.
