# True Hordes on a dedicated server (sub-project 7)

The server runs the population, the off-screen simulation and the horde "brain". Every client steers the real zombies
it owns and plays event audio. So **the server and every player need ZombieBuddy and True Hordes**.

## Server (Windows dedicated server)

1. **ZombieBuddy on the server.** Copy `ZombieBuddy.jar` and `zbNative.dll` from your game folder
   (`C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`) into the dedicated server's install folder, next to
   `projectzomboid.jar`. Then add `-agentlib:zbNative` to the server's JVM arguments:
   - **With `StartServer64.bat`:** add it to the `java` line, before `-cp`.
   - **With a `ProjectZomboid64.json`:** add it as the first entry of `"vmArgs"`, the same as on your PC.
2. **The mods.**
   - Subscribe the server to the ZombieBuddy workshop item, as for your other workshop mods.
   - Copy this repo's `mod\Contents\mods\TrueHordes` folder to the server user's `Zomboid\mods\TrueHordes`.
   - Add `TrueHordes` (and `ZombieBuddy`) to `Mods=` in `Zomboid\Server\<servername>.ini`.
3. **First start.** ZombieBuddy asks in the server console whether to let each Java mod load ("Allow this Java mod to
   load?"). Answer yes for True Hordes. Then check `server-console.txt` for these lines:
   - `[TrueHordes] True Hordes 0.1.0 loading (dedicated server)`
   - `hook targets found: 24`
   - `seeded the population` (new world) or `started with N held zombies`
4. **Vanilla zombie cull off.** In the server `.ini` / sandbox, set `ZombiesCountBeforeDelete` high (or as you planned),
   so the server does not delete zombies the mod is holding real.
5. **Memory.** Give the server JVM `-Xmx` to suit (the simulation plus 120k zombies is about 1-2 GB on top of the
   game).

## Every player

- ZombieBuddy installed the same way as on your PC (the jar, `zbNative.dll` and `-agentlib:zbNative`).
- True Hordes: from the Workshop if published (unlisted works); otherwise the same `TrueHordes` folder in their
  `Zomboid\mods`.
- On first join, approve the True Hordes Java mod when ZombieBuddy asks.

## Admin test commands (in the in-game Lua/debug console, as an admin)

The answers come back as a chat line and in the client's `console.txt`.

- `Hordes_status()`, `Hordes_stats()`, `Hordes_hordes()`, `Hordes_save()`
- `Hordes_roadHorde(300)`: a horde of 300 on the nearest highway, about 250 tiles out, walking to you.
- `Hordes_ringHordes(6, 100, 120)`: 6 dormant hordes of 100 on a 120-tile circle round you. Any gunshot within 400
  tiles sets them loose.
- `Hordes_sound(x, y, radius)`: a real game sound.
- `Hordes_map()`: the debug map, using the server's data (admins only).
