-- True Hordes debug map: draws the Java core's entities around a point.
-- Mouse wheel zooms, left-drag pans, right-click returns to following the player. Hordes_map() opens or closes it.

require "ISUI/ISPanel"
require "ISUI/ISCollapsableWindow"
require "ISUI/ISButton"

local FETCH_MS = 300
local MAX_POINTS = 4000
local TERRAIN_MS = 1000
local TILE_ZOOM = 4 -- px per tile at which walls are drawn
-- Terrain codes (Java truehordes.world.Terrain) to colours.
local TERRAIN_COLOURS = {
    [1] = { 0.45, 0.45, 0.45 }, -- road
    [2] = { 0.55, 0.45, 0.30 }, -- town
    [3] = { 0.60, 0.55, 0.20 }, -- farm
    [4] = { 0.30, 0.42, 0.25 }, -- open
    [5] = { 0.15, 0.40, 0.15 }, -- forest
    [6] = { 0.05, 0.25, 0.08 }, -- deep forest
    [7] = { 0.00, 0.00, 0.00 }, -- building
    [8] = { 0.15, 0.30, 0.70 }, -- water
    [9] = { 0.50, 0.35, 0.20 }, -- trail
}

Hordes_MapPanel = ISPanel:derive("Hordes_MapPanel")

function Hordes_MapPanel:new(x, y, width, height)
    local o = ISPanel.new(self, x, y, width, height)
    o.background = true
    o.backgroundColor = { r = 0.05, g = 0.05, b = 0.05, a = 0.95 }
    o.zoom = 1.0 -- screen pixels per tile
    o.follow = true
    o.centerX = nil
    o.centerY = nil
    o.snapshot = nil
    o.lastFetch = 0
    o.dragging = false
    o.showTerrain = true
    o.terrain = nil
    o.tiles = nil
    o.lastTerrain = 0
    return o
end

function Hordes_MapPanel:worldToScreen(wx, wy)
    return (wx - self.centerX) * self.zoom + self.width / 2, (wy - self.centerY) * self.zoom + self.height / 2
end

function Hordes_MapPanel:fetch()
    local now = getTimestampMs()
    if self.snapshot and now - self.lastFetch < FETCH_MS then return end
    self.lastFetch = now
    local halfW = self.width / 2 / self.zoom
    local halfH = self.height / 2 / self.zoom
    self.snapshot = TrueHordes.snapshot(self.centerX - halfW, self.centerY - halfH,
        self.centerX + halfW, self.centerY + halfH, MAX_POINTS)
end

function Hordes_MapPanel:createChildren()
    ISPanel.createChildren(self)
    self.terrainButton = ISButton:new(self.width - 90, 2, 86, 18, "Terrain: on", self, Hordes_MapPanel.onToggleTerrain)
    self.terrainButton:initialise()
    self:addChild(self.terrainButton)
end

function Hordes_MapPanel:onToggleTerrain()
    self.showTerrain = not self.showTerrain
    self.terrainButton:setTitle(self.showTerrain and "Terrain: on" or "Terrain: off")
end

function Hordes_MapPanel:fetchTerrain()
    local now = getTimestampMs()
    if self.terrain and now - self.lastTerrain < TERRAIN_MS then return end
    self.lastTerrain = now
    local halfW = self.width / 2 / self.zoom
    local halfH = self.height / 2 / self.zoom
    local step = math.max(8, math.ceil(8 / self.zoom))
    self.terrain = TrueHordes.terrain(self.centerX - halfW, self.centerY - halfH,
        self.centerX + halfW, self.centerY + halfH, step)
    if self.zoom >= TILE_ZOOM then
        self.tiles = TrueHordes.tiles(math.floor(self.centerX - halfW), math.floor(self.centerY - halfH),
            math.ceil(self.centerX + halfW), math.ceil(self.centerY + halfH), 20000)
    else
        self.tiles = nil
    end
end

function Hordes_MapPanel:drawTerrain()
    local t = self.terrain
    if not t then return end
    local size = t.step * self.zoom
    -- One rectangle per run of same-terrain samples in a row (terrain is mostly uniform, so this draws far fewer).
    for r = 0, t.rows - 1 do
        local row = r * t.cols
        local c = 0
        while c < t.cols do
            local code = t[row + c + 1]
            local run = 1
            while c + run < t.cols and t[row + c + run + 1] == code do run = run + 1 end
            local colour = TERRAIN_COLOURS[code]
            if colour then
                local sx, sy = self:worldToScreen(t.x0 + c * t.step, t.y0 + r * t.step)
                self:drawRect(sx, sy, size * run + 1, size + 1, 0.85, colour[1], colour[2], colour[3])
            end
            c = c + run
        end
    end
    local tiles = self.tiles
    if not tiles then return end
    local z = self.zoom
    for i = 1, tiles.n * 3, 3 do
        local sx, sy = self:worldToScreen(tiles[i], tiles[i + 1])
        local bits = tiles[i + 2]
        if bits % 2 == 1 then self:drawRect(sx, sy, z, z, 0.9, 0.1, 0.1, 0.1) end -- solid
        if math.floor(bits / 2) % 2 == 1 then self:drawRect(sx, sy, z, 2, 1, 0.95, 0.95, 0.95) end -- wall north
        if math.floor(bits / 4) % 2 == 1 then self:drawRect(sx, sy, 2, z, 1, 0.95, 0.95, 0.95) end -- wall west
    end
end

function Hordes_MapPanel:prerender()
    ISPanel.prerender(self)
    local player = getPlayer()
    if self.follow and player then
        self.centerX, self.centerY = player:getX(), player:getY()
    end
    if not self.centerX then return end
    self:fetch()
    if self.showTerrain then
        self:fetchTerrain()
        self:drawTerrain()
    end
    local s = self.snapshot
    if s then
        local n = s.n or 0
        if s.density then
            local size = math.max(1, s.cell * self.zoom)
            local maxCount = math.max(1, s.maxCount or 1)
            for i = 1, n * 3, 3 do
                local sx, sy = self:worldToScreen(s[i], s[i + 1])
                local alpha = 0.25 + 0.75 * (s[i + 2] / maxCount)
                self:drawRect(sx, sy, size, size, alpha, 0.9, 0.2, 0.2)
            end
        else
            for i = 1, n * 2, 2 do
                local sx, sy = self:worldToScreen(s[i], s[i + 1])
                self:drawRect(sx - 1, sy - 1, 3, 3, 1, 0.9, 0.3, 0.3)
            end
        end
    end
    if player then
        local px, py = self:worldToScreen(player:getX(), player:getY())
        self:drawRect(px - 3, py - 3, 7, 7, 1, 0.2, 1.0, 0.2)
    end
    local status
    if not s then
        status = "no data (Java core not running?)"
    elseif s.density then
        status = string.format("%d in view, density cells of %d tiles", s.total, s.cell)
    else
        status = string.format("%d in view", s.total)
    end
    self:drawText(string.format("zoom %.2f px/tile | %s%s", self.zoom, status, self.follow and " | following" or ""),
        4, 4, 1, 1, 1, 1, UIFont.Small)
end

function Hordes_MapPanel:onMouseWheel(del)
    local factor = del > 0 and 0.8 or 1.25
    self.zoom = math.max(0.02, math.min(8, self.zoom * factor))
    self.snapshot = nil
    self.terrain = nil
    return true
end

function Hordes_MapPanel:onMouseDown(x, y)
    self.dragging = true
    self.follow = false
    return true
end

function Hordes_MapPanel:onMouseUp(x, y)
    self.dragging = false
    return true
end

function Hordes_MapPanel:onMouseUpOutside(x, y)
    self.dragging = false
    return true
end

function Hordes_MapPanel:onMouseMove(dx, dy)
    if self.dragging and self.centerX then
        self.centerX = self.centerX - dx / self.zoom
        self.centerY = self.centerY - dy / self.zoom
        self.snapshot = nil
        self.terrain = nil
    end
    return true
end

function Hordes_MapPanel:onRightMouseDown(x, y)
    self.follow = true
    self.snapshot = nil
    self.terrain = nil
    return true
end

Hordes_DebugMap = ISCollapsableWindow:derive("Hordes_DebugMap")

function Hordes_DebugMap:createChildren()
    ISCollapsableWindow.createChildren(self)
    local top = self:titleBarHeight()
    self.map = Hordes_MapPanel:new(0, top, self.width, self.height - top)
    self.map:initialise()
    self:addChild(self.map)
end

function Hordes_DebugMap:close()
    ISCollapsableWindow.close(self)
    self:removeFromUIManager()
    Hordes_DebugMap.instance = nil
end

function Hordes_map()
    if Hordes_DebugMap.instance then
        Hordes_DebugMap.instance:close()
        print("True Hordes map closed")
        return "closed"
    end
    local window = Hordes_DebugMap:new(100, 100, 640, 640)
    window:setTitle("True Hordes debug map (wheel zoom, drag pan, right-click follow)")
    window:setResizable(false)
    window:initialise()
    window:addToUIManager()
    Hordes_DebugMap.instance = window
    print("True Hordes map opened")
    return "opened"
end
