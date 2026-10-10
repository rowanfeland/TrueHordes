# True Hordes: decisions so far

> Superseded by `design.md` (2026-10-10), which contains all of these plus later decisions. Kept as a log.

Running list of design decisions. Background is in `TrueHordes - Limitations Report.md` and
`TrueHordes - Research 2 - Off-screen Simulation.md`.

## Decided

| # | Decision | Date | Notes |
|---|---|---|---|
| 1 | **Full replace of the vanilla off-screen population.** The mod owns every off-screen zombie; the game only handles real zombies near players. | 2026-10-10 | Chosen because individual zombies must wander too, not only hordes. Vanilla off-screen zombies never wander (they stand until a sound wakes them, then walk a straight line). |
| 2 | **Everything wanders.** Individual zombies and hordes alike, along roads and through forests; nowhere is safe. | 2026-10-10 | |
| 3 | **Hordes form naturally.** Zombies join up when they bump into each other, or go to join a horde they can see once it reaches a certain size (around 10+). Hordes merge when they meet. | 2026-10-10 | Exact rules still open (see below). |
| 4 | **No pre-programmed routes.** Each horde chooses where to go from its own surroundings. Roads may be favoured, never assigned. | 2026-10-10 | A simulation manager handles bookkeeping (updates, encounters, joining, merging, splitting, real/off-screen conversion, saving). No storyteller director so far. |
| 5 | **Real zombies near players: as many as the machine can handle.** | 2026-10-10 | Needs measuring: test T10 in the limitations report. |
| 7 | **Zombies and hordes respond to sound, off-screen too.** A sound that reaches them turns them toward its source, whatever their size. Example: a 1,000-strong horde walking the highway by Muldraugh is reached by an 850-tile gunshot. The whole horde turns and heads for where the shot came from. Once it gets near there, the simulation manager helps it decide where to wander next, realistically. | 2026-10-10 | Applies under full replace: the mod's own simulation must implement this, because the vanilla off-screen code won't do it for hordes the mod owns. Sound radius as heard by the game (including Louder Gunshots and the Hearing setting) is the reach. How a horde reacts in detail (all of it vs part of it) is still open (B). |
| 8 | **Hordes react to sound by distance (the sound fades).** Example with an 850-tile sound: a horde in the outer band (around 700–850 tiles from the source) sends only about half of itself toward the sound. The closer the horde is, the larger the share that breaks off, up to a distance threshold inside which the whole horde goes. | 2026-10-10 | Applies to hordes only. Band edges, the fractions and the "all go" threshold are tuning values (probably sandbox options); exact numbers not decided. The part that stays behind remains a horde and keeps wandering. |
| 9 | **Individual zombies (not in a horde) always respond to a sound they are inside the radius of**, all the way out to its edge (e.g. the full 850 tiles). | 2026-10-10 | Intended consequence: individuals converging on the same sound meet each other on the way and at the source, which is how new hordes form. |
| 10 | **The mod owns every zombie, individuals as well as hordes.** Individuals wander with the mod's own logic until they either see a horde and go to join it, or meet up with enough other individuals to form their own horde. | 2026-10-10 | Reinforces decision 1 (full replace). Individuals must be simulated by the mod off-screen too, not left to the game. |
| 6 | Sandbox: **Follow Sound Distance = 1000**. | 2026-10-09 | Mainly relevant while vanilla population is still in use or for comparison; under full replace the mod controls off-screen reaction to sound. |

## Open

| # | Question |
|---|---|
| A | Joining and merging rules: bump distance, "sees a horde of 10+" range and size, merge and split triggers. |
| B | Which stimuli pull wanderers and hordes off course (sound, thunder, light, other hordes, players) and how strongly. |
| C | Single player first, then dedicated server (already planned). |

## Next

The user runs the in-game tests (limitations report T1–T11, research 2 T13–T14), starting 2026-10-11.
