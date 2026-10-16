-- True Hordes: Project A-Life NPCs compatibility layer (sub-project 5b).
-- A-Life's on-screen gunfire and its own events already make world sounds that True Hordes hears. Its off-screen
-- squad fights are silent, so this wraps ProjectALife.OfflineCombat.apply: after A-Life applies a fight (it returns
-- true), True Hordes fires a burst of gunshots at the fight's spot (6 + 4 per casualty, at most 60, over about a
-- minute). A-Life's own function runs exactly as before and its result is returned unchanged. Runs only when A-Life
-- is loaded and the function exists; if A-Life replaces the function later, it is wrapped again; any error in this
-- layer is logged once and ignored. No A-Life code is copied.
-- A-Life's helicopters make small noises (ProjectALife.HeliDirector.noise); after each one True Hordes adds a sound
-- the size of the game's own helicopter, so zombies treat them alike.

local wrapper = nil
local warned = false

local function fight(outcome)
    local j = TrueHordesJ
    if j == nil or type(j.alifeFight) ~= "function" or type(outcome) ~= "table" then return end
    local x, y = tonumber(outcome.x), tonumber(outcome.y)
    if x == nil or y == nil then return end
    local dead = (tonumber(outcome.loserCasualties) or 0) + (tonumber(outcome.winnerCasualties) or 0)
    j.alifeFight(x, y, dead)
end

local function wrap()
    local pal = ProjectALife
    local combat = pal and pal.OfflineCombat
    if type(combat) ~= "table" or type(combat.apply) ~= "function" then return end
    if combat.apply == wrapper then return end
    local original = combat.apply
    wrapper = function(outcome, hours)
        local result = original(outcome, hours)
        if result == true then
            local ok, err = pcall(fight, outcome)
            if not ok and not warned then
                warned = true
                print("[TrueHordes] A-Life fight gunfire failed (ignored): " .. tostring(err))
            end
        end
        return result
    end
    combat.apply = wrapper
    print("[TrueHordes] Project A-Life compatibility on: off-screen fights make gunfire")
end

local heliWrapper = nil

local function wrapHeli()
    local d = ProjectALife and ProjectALife.HeliDirector
    if type(d) ~= "table" or type(d.noise) ~= "function" or d.noise == heliWrapper then return end
    local original = d.noise
    heliWrapper = function(x, y, z)
        local result = original(x, y, z)
        local ok, err = pcall(function()
            local j = TrueHordesJ
            if j ~= nil and type(j.heliNoise) == "function" and tonumber(x) and tonumber(y) then j.heliNoise(x, y) end
        end)
        if not ok and not warned then
            warned = true
            print("[TrueHordes] A-Life helicopter noise failed (ignored): " .. tostring(err))
        end
        return result
    end
    d.noise = heliWrapper
    print("[TrueHordes] Project A-Life compatibility on: helicopters heard like the game's helicopter")
end

local lastCheck = 0

local function onTick()
    local now = getTimestampMs()
    if now - lastCheck < 5000 then return end
    lastCheck = now
    local ok, err = pcall(function()
        wrap()
        wrapHeli()
    end)
    if not ok and not warned then
        warned = true
        print("[TrueHordes] A-Life compatibility error (ignored): " .. tostring(err))
    end
end

Events.OnTick.Add(onTick)
