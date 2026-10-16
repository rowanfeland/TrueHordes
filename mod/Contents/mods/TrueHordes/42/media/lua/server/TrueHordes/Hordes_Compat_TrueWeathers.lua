-- True Hordes: True Weathers compatibility layer (sub-project 5b).
-- True Weathers - Gameplay can rain over one town and not the next. This samples its regional precipitation
-- (VTWG.precipAt) into True Hordes' coarse weather grid, a few cells per tick, so rain muffles the off-screen zombies
-- where it falls and thunder draws zombies from outside a storm. Runs only when both mods are loaded and the
-- functions it uses exist; any error switches it off for the session (logged once) and True Hordes falls back to
-- vanilla's weather. Uses only True Weathers' public Lua functions; no code of that mod is copied.

local PER_TICK = 24

local spec = nil
local index = 0
local dead = false

local function off(why)
    dead = true
    print("[TrueHordes] True Weathers compatibility switched off: " .. tostring(why))
end

local function sample()
    local j, v = TrueHordesJ, VTWG
    if j == nil or v == nil or type(v.precipAt) ~= "function" or type(j.weatherGrid) ~= "function"
            or type(j.weatherSet) ~= "function" then
        return
    end
    if type(v.regionalOn) == "function" and not v.regionalOn() then return end
    if spec == nil then
        spec = j.weatherGrid()
        if spec == nil then return end
    end
    local width, height, step = spec.width, spec.height, spec.step
    local cells = width * height
    for _ = 1, PER_TICK do
        local gx = index % width
        local gy = math.floor(index / width)
        local p = v.precipAt(spec.originX + (gx + 0.5) * step, spec.originY + (gy + 0.5) * step)
        j.weatherSet(index, tonumber(p) or 0)
        index = (index + 1) % cells
    end
end

local function onTick()
    if dead then return end
    local ok, err = pcall(sample)
    if not ok then off(err) end
end

local function onGameStart()
    spec = nil
    index = 0
end

Events.OnTick.Add(onTick)
Events.OnGameStart.Add(onGameStart)
