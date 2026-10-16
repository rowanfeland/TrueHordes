-- True Hordes: seeing hordes coming (sub-project 6 spec goal 13).
-- Zombies beyond the loaded area (held by the simulation) are drawn as dark figure outlines on the ground where the
-- simulation has them, out to FarSightTiles, so a horde can be seen walking in before it becomes real. Refreshed once a
-- second; in between each figure walks on along its heading at its speed, so they move smoothly. Fainter at night and
-- in rain. Only what is on screen is drawn, nearest first, at most MAX figures. Single player for now (sub-project 7
-- sends the list to clients).

require "ISUI/ISUIElement"

local REFRESH_MS = 1000
local MAX = 600
local MAX_DRIFT = 6 -- tiles a figure may walk on before the next refresh catches up

local FarSight = ISUIElement:derive("TrueHordesFarSight")

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

function FarSight:prerender() end

function FarSight:render()
    local now = getTimestampMs()
    if now - lastRefresh >= REFRESH_MS then refresh(now) end
    local d = data
    if not d or not d.n or d.n == 0 then return end
    local p = getPlayer()
    if not p or not isoToScreenX then return end
    local pn = p:getPlayerNum()
    local zoom = getCore():getZoom(pn)
    local climate = getClimateManager()
    local light = climate and climate:getDayLightStrength() or 1
    local rain = climate and climate:getRainIntensity() or 0
    local alpha = (0.25 + 0.55 * light) * (1 - 0.5 * rain)
    local minutes = (now - stamp) / 1000 / math.max(0.01, d.rspm or 2.5)
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    local h, w, head = 90 / zoom, 22 / zoom, 16 / zoom
    for i = 0, d.n - 1 do
        local b = i * 4
        local x, y, hd, v = d[b + 1], d[b + 2], d[b + 3], d[b + 4]
        local walk = math.min(MAX_DRIFT, v * minutes)
        x = x + math.cos(hd) * walk
        y = y + math.sin(hd) * walk
        local sx, sy = isoToScreenX(pn, x, y, 0), isoToScreenY(pn, x, y, 0)
        if sx > -w and sx < sw + w and sy > 0 and sy < sh + h then
            self:drawRect(sx - w / 2, sy - h + head, w, h - head, alpha, 0.04, 0.04, 0.05)
            self:drawRect(sx - head / 2, sy - h, head, head, alpha, 0.04, 0.04, 0.05)
        end
    end
end

function FarSight:onMouseDown() return false end
function FarSight:onMouseUp() return false end
function FarSight:onMouseMove() return false end
function FarSight:onRightMouseDown() return false end

local panel = nil

local function onGameStart()
    if isServer() then return end
    if panel then return end
    panel = FarSight:new(0, 0, getCore():getScreenWidth(), getCore():getScreenHeight())
    panel:initialise()
    panel.background = false
    panel:addToUIManager()
    panel.javaObject:setConsumeMouseEvents(false)
    panel:backMost()
end

local function onResolutionChange(_, _, w, h)
    if panel then panel:setWidth(w); panel:setHeight(h) end
end

Events.OnGameStart.Add(onGameStart)
Events.OnResolutionChange.Add(onResolutionChange)
