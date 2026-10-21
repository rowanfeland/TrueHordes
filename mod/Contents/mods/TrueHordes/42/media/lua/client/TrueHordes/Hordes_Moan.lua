-- True Hordes: the player hears big hordes just outside the loaded area (sub-project 5a spec section 6).
-- Once a second, the loudest held horde of 50+ whose moaning reaches the player (same energy falloff as every sound)
-- plays a few of the game's own zombie voices from a point towards it: more voices and louder the bigger and closer
-- it is. Audio only (a free emitter): it makes no world sound, so zombies do not hear it.

local POLL_MS = 1000
local MOAN_VOLUME = 0.6   -- loudest volume (0..1), tuning
-- Tiles towards the horde the voices come from. The game's zombie voices fade out within about 20 tiles of the
-- listener, so 40 (the first value) was silent; close in, the direction still reads and volume carries the distance.
local AHEAD = 10
local VOICES = { "MaleZombieVoiceA", "MaleZombieVoiceB", "MaleZombieVoiceC",
                 "FemaleZombieVoiceA", "FemaleZombieVoiceB", "FemaleZombieVoiceC" }

local lastPoll = 0
local told = false

local function onTick()
    local now = getTimestampMs()
    if now - lastPoll < POLL_MS then return end
    lastPoll = now
    local player = getPlayer()
    if not player then return end
    local px, py = player:getX(), player:getY()
    local m = TrueHordes.hordeMoan(px, py)
    if not m then return end
    local dx, dy = m.x - px, m.y - py
    local d = math.sqrt(dx * dx + dy * dy)
    if d < 1 then return end
    local margin = m.level - 30 -- dB above the hearing threshold
    local voices = math.max(1, math.min(5, 1 + math.floor(margin / 8)))
    local volume = MOAN_VOLUME * math.max(0.1, math.min(1, margin / 30))
    if not told then
        told = true
        print(string.format("[TrueHordes] horde moan heard: %d zombies, %d tiles away, %.0f dB", m.size or 0, d, m.level))
    end
    local ahead = math.min(d, AHEAD)
    local world = getWorld()
    for i = 1, voices do
        local ex = px + dx / d * ahead + ZombRand(-6, 7)
        local ey = py + dy / d * ahead + ZombRand(-6, 7)
        local emitter = world:getFreeEmitter(ex, ey, player:getZ())
        if emitter then
            local id = emitter:playSound(VOICES[ZombRand(#VOICES) + 1])
            if id and id ~= 0 then emitter:setVolume(id, volume) end
        end
    end
end

Events.OnTick.Add(onTick)
