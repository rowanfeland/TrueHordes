-- True Hordes debug map: draws the Java core's entities around a point.
-- Mouse wheel zooms, left-drag pans, right-click returns to following the player. Hordes_map() opens or closes it.

require "ISUI/ISPanel"
require "ISUI/ISCollapsableWindow"
require "ISUI/ISButton"

-- Refresh rates: each fetch builds Lua tables on the main thread, so keep them modest (frame rate and garbage).
local FETCH_MS = 1000
local MAX_POINTS = 3000
local TERRAIN_MS = 10000 -- terrain hardly changes: refetched when the view moves, else every 10 s
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

-- Sound kinds (Java truehordes.sim.SoundEvent) to colours and names for the sound viewer.
local SOUND_KINDS = {
    [0] = { 0.85, 0.85, 0.85, "other" },
    [1] = { 1.00, 0.40, 0.10, "gunfire" },
    [2] = { 0.30, 0.60, 1.00, "vehicle" },
    [3] = { 0.20, 0.90, 0.70, "appliance" },
    [4] = { 1.00, 0.90, 0.20, "alarm" },
    [5] = { 1.00, 0.30, 1.00, "test" },
    [6] = { 0.60, 1.00, 0.30, "helicopter" },
    [7] = { 0.80, 0.80, 1.00, "meta event" },
    [8] = { 0.55, 0.55, 1.00, "thunder" },
    [9] = { 1.00, 0.55, 0.55, "scream" },
    [10] = { 0.75, 0.55, 0.30, "dog" },
    [11] = { 1.00, 0.75, 0.10, "car alarm" },
    [12] = { 1.00, 0.20, 0.20, "A-Life fight" },
    [13] = { 0.45, 0.45, 0.90, "storm thunder" },
}
local SOUND_FADE_MS = 30000 -- an ended sound fades out over 30 real seconds
local SOUND_RINGS = 6
local SOUND_LIST_ROWS = 20
local SOUND_LIST_W = 230

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
    o.showSounds = true
    o.sounds = nil -- latest TrueHordes.sounds() result
    o.soundSeen = {} -- id -> { row data, endedAt (real ms) } kept until it has faded out
    o.listRows = {} -- screen rows of the list panel: { y, x, y of the sound }
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
    self.soundButton = ISButton:new(self.width - 180, 2, 86, 18, "Sounds: on", self, Hordes_MapPanel.onToggleSounds)
    self.soundButton:initialise()
    self:addChild(self.soundButton)
end

function Hordes_MapPanel:onToggleSounds()
    self.showSounds = not self.showSounds
    self.soundButton:setTitle(self.showSounds and "Sounds: on" or "Sounds: off")
end

-- Keeps every sound seen until 30 real seconds after it ended (or vanished from the list), for the fade-out.
function Hordes_MapPanel:fetchSounds(now)
    local t = TrueHordes.sounds()
    self.sounds = t
    local present = {}
    if t then
        for i = 1, (t.n or 0) * 10, 10 do
            local id = t[i]
            present[id] = true
            local seen = self.soundSeen[id]
            if not seen then
                seen = {}
                self.soundSeen[id] = seen
            end
            seen.x, seen.y, seen.r, seen.kind = t[i + 1], t[i + 2], t[i + 3], t[i + 4]
            seen.age, seen.w, seen.h, seen.split = t[i + 5], t[i + 7], t[i + 8], t[i + 9]
            if t[i + 6] == 1 and not seen.endedAt then seen.endedAt = now end
        end
    end
    for id, seen in pairs(self.soundSeen) do
        if not present[id] and not seen.endedAt then seen.endedAt = now end
        if seen.endedAt and now - seen.endedAt > SOUND_FADE_MS then self.soundSeen[id] = nil end
    end
end

-- Chance a lone zombie at distance d hears a sound of radius r (the same energy curve as the Java model).
local function hearChance(d, r, edge, certain)
    if d >= r then return 0 end
    local m = 20 * math.log(r / math.max(1, d)) / math.log(10)
    local full = 20 * math.log(1 / certain) / math.log(10)
    return edge + (1 - edge) * math.min(1, m / full)
end

-- A filled disc as horizontal strips (at most about 60 per disc, so big circles stay cheap).
function Hordes_MapPanel:fillDisc(cx, cy, rp, a, r, g, b)
    if rp < 1 then return end
    local step = math.max(2, math.ceil(rp / 30))
    for dy = -rp, rp, step do
        local half = math.sqrt(math.max(0, rp * rp - dy * dy))
        self:drawRect(cx - half, cy + dy, half * 2, step, a, r, g, b)
    end
end

-- Each sound: stacked discs so the shade is the chance of being heard there; lines from hordes heading to sounds.
function Hordes_MapPanel:drawSounds(now)
    local t = self.sounds
    local edge = t and t.edge or 0.4
    local certain = t and t.certain or 0.25
    -- Sub-project 5b: rain cells (True Weathers' regional weather) in blue, lit lights the mod knows as dots.
    if t and t.storms then
        local s = t.storms
        for i = 1, (s.n or 0) * 4, 4 do
            local half = s[i + 2] / 2
            local ax, ay = self:worldToScreen(s[i] - half, s[i + 1] - half)
            local bx, by = self:worldToScreen(s[i] + half, s[i + 1] + half)
            self:drawRect(ax, ay, bx - ax, by - ay, 0.08 + 0.22 * s[i + 3], 0.2, 0.35, 1)
        end
    end
    if t and t.lights then
        local l = t.lights
        for i = 1, (l.n or 0) * 3, 3 do
            local lx, ly = self:worldToScreen(l[i], l[i + 1])
            if l[i + 2] == 1 then
                self:drawRect(lx - 1, ly - 1, 3, 3, 0.9, 1, 0.6, 0.1)
            else
                self:drawRect(lx - 2, ly - 2, 4, 4, 0.9, 1, 1, 0.4)
            end
        end
    end
    for id, seen in pairs(self.soundSeen) do
        local fade = 1
        if seen.endedAt then fade = math.max(0, 1 - (now - seen.endedAt) / SOUND_FADE_MS) end
        local c = SOUND_KINDS[seen.kind] or SOUND_KINDS[0]
        local sx, sy = self:worldToScreen(seen.x, seen.y)
        for k = SOUND_RINGS, 1, -1 do
            local ringR = seen.r * k / SOUND_RINGS
            local outer = hearChance(seen.r * (k - 0.5) / SOUND_RINGS, seen.r, edge, certain)
            -- Each smaller disc adds the extra chance gained moving in, so the stack's total is the chance there.
            local a = (k == SOUND_RINGS and outer or math.max(0, outer - (hearChance(seen.r * (k + 0.5) / SOUND_RINGS,
                seen.r, edge, certain)))) * 0.35 * fade
            if a > 0.005 then self:fillDisc(sx, sy, ringR * self.zoom, a, c[1], c[2], c[3]) end
        end
        self:drawRect(sx - 2, sy - 2, 5, 5, fade, c[1], c[2], c[3])
    end
    if t and t.lines then
        local l = t.lines
        for i = 1, (l.n or 0) * 4, 4 do
            local ax, ay = self:worldToScreen(l[i], l[i + 1])
            local bx, by = self:worldToScreen(l[i + 2], l[i + 3])
            local len = math.sqrt((bx - ax) ^ 2 + (by - ay) ^ 2)
            local steps = math.min(200, math.floor(len / 6))
            for j = 0, steps do
                local f = steps > 0 and j / steps or 0
                self:drawRect(ax + (bx - ax) * f, ay + (by - ay) * f, 2, 2, 0.8, 1, 1, 1)
            end
        end
    end
end

-- The list panel: newest sounds first; click a row to centre the map on it.
function Hordes_MapPanel:drawSoundList(now)
    local list = {}
    for id, seen in pairs(self.soundSeen) do
        seen.id = id
        table.insert(list, seen)
    end
    table.sort(list, function(a, b) return a.id > b.id end)
    local x0 = self.width - SOUND_LIST_W - 4
    local rows = math.min(SOUND_LIST_ROWS, #list)
    self.listRows = {}
    if rows == 0 then return end
    local rowH = 15
    self:drawRect(x0, 24, SOUND_LIST_W, rows * rowH + 4, 0.75, 0, 0, 0)
    for i = 1, rows do
        local seen = list[i]
        local c = SOUND_KINDS[seen.kind] or SOUND_KINDS[0]
        local y = 26 + (i - 1) * rowH
        local ago = seen.endedAt and string.format("%ds ago", math.floor((now - seen.endedAt) / 1000)) or "now"
        local text = string.format("%-9s r%-5d %dw %dh %ds | %s", c[4], math.floor(seen.r), seen.w or 0, seen.h or 0,
            seen.split or 0, ago)
        self:drawText(text, x0 + 4, y, c[1], c[2], c[3], 1, UIFont.Small)
        table.insert(self.listRows, { y = y, h = rowH, x = seen.x, wy = seen.y })
    end
end

function Hordes_MapPanel:onToggleTerrain()
    self.showTerrain = not self.showTerrain
    self.terrainButton:setTitle(self.showTerrain and "Terrain: on" or "Terrain: off")
end

function Hordes_MapPanel:fetchTerrain()
    local now = getTimestampMs()
    local step = math.max(8, math.ceil(8 / self.zoom))
    -- The view, rounded to the terrain step: following a walking player only refetches when it moves a whole step.
    local key = math.floor(self.centerX / step) .. ":" .. math.floor(self.centerY / step) .. ":" .. self.zoom
    if self.terrain and key == self.terrainKey and now - self.lastTerrain < TERRAIN_MS then return end
    self.terrainKey = key
    self.lastTerrain = now
    local halfW = self.width / 2 / self.zoom
    local halfH = self.height / 2 / self.zoom
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

--- Hordes as circles (radius = spread) with their size and a heading arrow; h holds x, y, spread, size, heading,
--- dormant per horde. Building hordes (dormant) are dim, without an arrow.
function Hordes_MapPanel:drawHordes(h)
    if not h then return end
    local n = h.n or 0
    for i = 1, n * 6, 6 do
        local cx, cy = self:worldToScreen(h[i], h[i + 1])
        local dormant = h[i + 5] == 1
        if dormant and self.zoom < 1 then
            -- Zoomed out there can be thousands of building hordes: one dim dot each keeps the map cheap.
            self:drawRect(cx - 1, cy - 1, 3, 3, 0.5, 0.75, 0.2, 1.0)
        else
            local r = math.max(3, h[i + 2] * self.zoom)
            local alpha = dormant and 0.35 or 1
            local steps = dormant and 12 or 24
            for k = 0, steps - 1 do
                local a = k / steps * 2 * math.pi
                self:drawRect(cx + math.cos(a) * r - 1, cy + math.sin(a) * r - 1, 2, 2, alpha, 0.75, 0.2, 1.0)
            end
            if not dormant then
                local hx, hy = math.cos(h[i + 4]), math.sin(h[i + 4])
                for d = 0, r + 6, 2 do self:drawRect(cx + hx * d - 1, cy + hy * d - 1, 2, 2, 1, 1, 1, 1) end
                self:drawText(tostring(math.floor(h[i + 3])), cx + 4, cy - 14, 1, 1, 1, 1, UIFont.Small)
            end
        end
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
    local now = getTimestampMs()
    if self.showSounds and now - (self.lastSounds or 0) >= FETCH_MS then
        self.lastSounds = now
        self:fetchSounds(now)
    end
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
            -- Points are x, y, kind: wanderers red, stragglers (never join hordes) yellow.
            for i = 1, n * 3, 3 do
                local sx, sy = self:worldToScreen(s[i], s[i + 1])
                if s[i + 2] == 1 then
                    self:drawRect(sx - 1, sy - 1, 3, 3, 1, 1.0, 0.85, 0.2)
                else
                    self:drawRect(sx - 1, sy - 1, 3, 3, 1, 0.9, 0.3, 0.3)
                end
            end
        end
        self:drawHordes(s.hordes)
    end
    if self.showSounds then self:drawSounds(now) end
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
        status = string.format("%d wanderers in view, %d hordes (purple)", s.total, s.hordes and s.hordes.n or 0)
    end
    self:drawText(string.format("zoom %.2f px/tile | %s%s", self.zoom, status, self.follow and " | following" or ""),
        4, 4, 1, 1, 1, 1, UIFont.Small)
    if self.showSounds then self:drawSoundList(now) end
end

function Hordes_MapPanel:onMouseWheel(del)
    local factor = del > 0 and 0.8 or 1.25
    self.zoom = math.max(0.02, math.min(8, self.zoom * factor))
    self.snapshot = nil
    self.terrain = nil
    return true
end

function Hordes_MapPanel:onMouseDown(x, y)
    if self.showSounds and x >= self.width - SOUND_LIST_W - 4 then
        for _, row in ipairs(self.listRows or {}) do
            if y >= row.y and y < row.y + row.h then
                self.follow = false
                self.centerX, self.centerY = row.x, row.wy
                self.snapshot = nil
                self.terrain = nil
                return true
            end
        end
    end
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
