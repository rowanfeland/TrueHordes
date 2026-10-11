-- True Hordes debug map: draws the Java core's entities around a point.
-- Mouse wheel zooms, left-drag pans, right-click returns to following the player. Hordes_map() opens or closes it.

require "ISUI/ISPanel"
require "ISUI/ISCollapsableWindow"

local FETCH_MS = 300
local MAX_POINTS = 4000

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

function Hordes_MapPanel:prerender()
    ISPanel.prerender(self)
    local player = getPlayer()
    if self.follow and player then
        self.centerX, self.centerY = player:getX(), player:getY()
    end
    if not self.centerX then return end
    self:fetch()
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
    end
    return true
end

function Hordes_MapPanel:onRightMouseDown(x, y)
    self.follow = true
    self.snapshot = nil
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
