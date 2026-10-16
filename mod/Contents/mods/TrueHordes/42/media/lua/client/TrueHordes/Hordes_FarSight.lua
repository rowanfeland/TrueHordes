-- True Hordes: seeing hordes coming (sub-project 6 spec goal 13).
-- Zombies beyond the loaded area (held by the simulation) are drawn as dark figures on the ground where the simulation
-- has them, out to FarSightTiles, so a horde can be seen walking in before it becomes real. Refreshed once a second; in
-- between each figure walks on along its heading at its speed. Fainter at night and in rain. Drawn straight with the
-- sprite renderer before the UI (no screen-sized UI element: one made zombies and cars invisible with Viewpoint).
-- Single player for now (sub-project 7 sends the list to clients).

local REFRESH_MS = 1000
local MAX = 250
local MAX_DRIFT = 6 -- tiles a figure may walk on before the next refresh catches up

local data = nil
local stamp = 0
local lastRefresh = 0

local function sandbox(name, fallback)
    local v = SandboxVars and SandboxVars.TrueHordes and SandboxVars.TrueHordes[name]
    if v == nil then return fallback end
    return v
end

local function refresh(now)
    lastRefresh = now
    data = nil
    if not sandbox("FarSight", true) then return end
    local p = getPlayer()
    if not p or not TrueHordes or not TrueHordes.farSight then return end
    data = TrueHordes.farSight(p:getX(), p:getY(), sandbox("FarSightTiles", 200), MAX)
    stamp = now
end

local function draw()
    local now = getTimestampMs()
    if now - lastRefresh >= REFRESH_MS then refresh(now) end
    local d = data
    if not d or not d.n or d.n == 0 then return end
    local p = getPlayer()
    if not p or not isoToScreenX then return end
    local pn = p:getPlayerNum()
    local core = getCore()
    local zoom = core:getZoom(pn)
    local climate = getClimateManager()
    local light = climate and climate:getDayLightStrength() or 1
    local rain = climate and climate:getRainIntensity() or 0
    local alpha = (0.25 + 0.55 * light) * (1 - 0.5 * rain)
    local minutes = (now - stamp) / 1000 / math.max(0.01, d.rspm or 2.5)
    local sw, sh = core:getScreenWidth(), core:getScreenHeight()
    local h, w = math.floor(80 / zoom), math.max(2, math.floor(20 / zoom))
    local r = getRenderer()
    local cos, sin, min, floor = math.cos, math.sin, math.min, math.floor
    for i = 0, d.n - 1 do
        local b = i * 4
        local x, y, hd = d[b + 1], d[b + 2], d[b + 3]
        local walk = min(MAX_DRIFT, d[b + 4] * minutes)
        x = x + cos(hd) * walk
        y = y + sin(hd) * walk
        local sx, sy = isoToScreenX(pn, x, y, 0), isoToScreenY(pn, x, y, 0)
        if sx > -w and sx < sw + w and sy > 0 and sy < sh + h then
            r:renderRect(floor(sx - w / 2), floor(sy - h), w, h, 0.04, 0.04, 0.05, alpha)
        end
    end
end

Events.OnPreUIDraw.Add(function()
    if isServer() then return end
    draw()
end)
