-- True Hordes: seeing hordes coming (sub-project 6 spec goal 13).
-- Zombies beyond the loaded area (held by the simulation) are drawn as dark figures on the ground where the simulation
-- has them, out to FarSightTiles, so a horde can be seen walking in before it becomes real. Refreshed once a second; in
-- between each figure walks on along its heading at its speed. Fainter at night and in rain. Drawn straight with the
-- sprite renderer before the UI (no screen-sized UI element: one made zombies and cars invisible with Viewpoint).
-- With Viewpoint (3D view) the figures are placed by Viewpoint's own projection (Java compat.ViewpointBridge; it turns
-- itself off if Viewpoint changes). In the 2D view the hordes are usually beyond the screen, so figures off screen are
-- drawn at the screen's edge in their direction, stronger the closer they are.

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

local EDGE_IN = 8 -- pixels from the screen edge for the 2D edge markers

local function draw()
    local now = getTimestampMs()
    if now - lastRefresh >= REFRESH_MS then refresh(now) end
    local d = data
    if not d or not d.n or d.n == 0 then return end
    local p = getPlayer()
    if not p or not isoToScreenX then return end
    local pn = p:getPlayerNum()
    local core = getCore()
    local climate = getClimateManager()
    local light = climate and climate:getDayLightStrength() or 1
    local rain = climate and climate:getRainIntensity() or 0
    local alpha = (0.25 + 0.55 * light) * (1 - 0.5 * rain)
    local minutes = (now - stamp) / 1000 / math.max(0.01, d.rspm or 2.5)
    local sw, sh = core:getScreenWidth(), core:getScreenHeight()
    local r = getRenderer()
    local cos, sin, min, max, floor = math.cos, math.sin, math.min, math.max, math.floor
    local J = TrueHordesJ
    local in3d = J and J.vpBegin and J.vpBegin(pn)
    local zoom = core:getZoom(pn)
    local h2, w2 = floor(80 / zoom), max(2, floor(20 / zoom))
    local px, py = p:getX(), p:getY()
    local cx, cy = sw / 2, sh / 2
    for i = 0, d.n - 1 do
        local b = i * 4
        local x, y, hd = d[b + 1], d[b + 2], d[b + 3]
        local walk = min(MAX_DRIFT, d[b + 4] * minutes)
        x = x + cos(hd) * walk
        y = y + sin(hd) * walk
        if in3d then
            if J.vpFigure(x, y, 0) then
                local fx, fy, fh = J.vpX(), J.vpY(), J.vpH()
                local fw = max(1, fh * 0.3)
                if fx > -fw and fx < sw + fw and fy > 0 and fy < sh + fh then
                    r:renderRect(floor(fx - fw / 2), floor(fy - fh), max(1, floor(fw)), max(2, floor(fh)), 0.04, 0.04, 0.05, alpha)
                end
            end
        else
            local sx, sy = isoToScreenX(pn, x, y, 0), isoToScreenY(pn, x, y, 0)
            if sx > -w2 and sx < sw + w2 and sy > 0 and sy < sh + h2 then
                r:renderRect(floor(sx - w2 / 2), floor(sy - h2), w2, h2, 0.04, 0.04, 0.05, alpha)
            else
                -- Off screen: a small figure on the screen's edge, in its direction, fainter the farther it is.
                local dx, dy = sx - cx, sy - cy
                local k = min((cx - EDGE_IN) / max(1, math.abs(dx)), (cy - EDGE_IN - 14) / max(1, math.abs(dy)))
                local ex, ey = cx + dx * k, cy + dy * k + 14
                local dist = math.sqrt((x - px) ^ 2 + (y - py) ^ 2)
                local a = alpha * max(0.15, min(1, 120 / max(1, dist)))
                r:renderRect(floor(ex - 2), floor(ey - 14), 4, 14, 0.04, 0.04, 0.05, a)
            end
        end
    end
end

Events.OnPreUIDraw.Add(function()
    if isServer() then return end
    draw()
end)
