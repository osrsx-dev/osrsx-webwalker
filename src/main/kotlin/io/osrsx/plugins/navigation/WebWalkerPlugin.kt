package io.osrsx.plugins.navigation

import io.osrsx.api.RouteLeg
import io.osrsx.api.Subscription
import io.osrsx.api.Tile
import io.osrsx.api.WalkEvent
import io.osrsx.api.WalkListener
import io.osrsx.api.WalkPhase
import io.osrsx.config.PluginConfig
import io.osrsx.plugin.Gfx2D
import io.osrsx.plugin.Gfx3D
import io.osrsx.plugin.HasMenu
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.MenuIcon
import io.osrsx.plugin.MenuItem
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginDescriptor

/**
 * Drives the global web walker from a docked sidebar panel — the standalone successor to the in-core Web
 * Walker plugin, built entirely on the public osrsx SDK.
 *
 * The panel ([onMenu]) is a game-engine-style control surface: an inline **X / Y / Z** tile field, plus
 * **location**, **NPC** and **object** lookups (our searchable pickers) that resolve to a nearby tile and
 * walk there on click. The in-world route is drawn through [HasOverlay] via [webWalking].routeView(), and a
 * right-click **"Walk here"** is contributed to the world map through the SDK's [worldMap] service.
 *
 * The panel shows/hides with the plugin itself (the sidebar item follows the enabled state — no "show
 * window" toggle), and the walker engine lives in the client; this plugin only observes and drives it.
 */
@PluginDescriptor(
    name = "Web Walker",
    description = "Control panel + route overlay + world-map \"Walk here\" for the global web walker.",
    author = "osrsx",
    tags = ["navigation", "utility", "walking"],
    enabledByDefault = true,
)
class WebWalkerPlugin : Plugin(), HasMenu, HasOverlay {

    object Config : PluginConfig("webwalker") {
        var routeOverlay by boolItem(
            "routeOverlay", "Draw route overlay", true,
            "Draw the planned route in-world while walking.", section = "Overlay",
        )
        var worldMapWalkHere by boolItem(
            "worldMapWalkHere", "World-map \"Walk here\"", true,
            "Add a right-click \"Walk here\" entry to the world map.", section = "World map",
        )
    }

    override fun config() = Config

    // ---- sidebar menu item (visibility follows the plugin's enabled state) ----
    override fun menu() = MenuItem("Web Walker", MenuIcon.MAP)

    // ---- walk state ----
    @Volatile private var walkingActive = false
    private var dx = 3164 // Grand Exchange, a sensible default destination
    private var dy = 3486
    private var dz = 0
    private fun dest() = Tile(dx, dy, dz)

    // ---- lookup selections (resolved tile cached until the selection changes) ----
    private var locQuery = ""
    private var npcSel = -1
    private var npcTile: Tile? = null
    private var objSel = -1
    private var objTile: Tile? = null

    // ---- event log ----
    private val eventLog = ArrayDeque<String>()
    private var walkListener: WalkListener? = null
    private var worldMapSub: Subscription? = null

    override fun onStart() {
        walkListener = webWalking.addListener(WalkListener { logEvent(it) })
        updateWorldMapOption()
    }

    override fun onStop() {
        walkListener?.let { webWalking.removeListener(it) }; walkListener = null
        worldMapSub?.unsubscribe(); worldMapSub = null
        walkingActive = false
    }

    override fun onConfigChanged(key: String) {
        if (key == "worldMapWalkHere") updateWorldMapOption()
    }

    /** Add/remove the world-map "Walk here" option to match the current config. */
    private fun updateWorldMapOption() {
        if (Config.worldMapWalkHere && worldMapSub == null) {
            worldMapSub = worldMap.addMenuOption("Walk here") { navigateTo(it) }
        } else if (!Config.worldMapWalkHere && worldMapSub != null) {
            worldMapSub?.unsubscribe(); worldMapSub = null
        }
    }

    /** Set the destination to [tile] and start walking there. */
    private fun navigateTo(tile: Tile) {
        dx = tile.x; dy = tile.y; dz = tile.plane
        walkingActive = true
    }

    override fun onLoop(): Long {
        if (!walkingActive) return 300
        if (worldMap.isOpen()) return 250 // the map covers the screen — wait for it to close
        val d = dest()
        if (webWalking.arrived(d)) { walkingActive = false; return 300 }
        if (webWalking.state().phase == WalkPhase.FAILED) { walkingActive = false; return 400 }
        webWalking.walkTo(d)
        return 900
    }

    private fun logEvent(@Suppress("UNUSED_PARAMETER") e: WalkEvent) {
        val line = webWalking.state().message
        synchronized(eventLog) {
            eventLog.addLast(line)
            while (eventLog.size > LOG_CAP) eventLog.removeFirst()
        }
    }

    // ---- control panel ----
    override fun onMenu(gfx: Gfx2D) {
        val st = webWalking.state()

        // Status
        gfx.textColored(phaseColor(st.phase), "● ${st.phase}")
        gfx.text(st.message)
        st.destination?.let { gfx.textColored(DIM, "Destination  ${it.x}, ${it.y}, ${it.plane}") }
        gfx.separator()

        // Manual walk — inline X / Y / Z vector field
        gfx.text("Walk to tile")
        val xyz = gfx.inputInt3("", dx, dy, dz)
        dx = xyz[0]; dy = xyz[1]; dz = xyz[2]
        if (gfx.button(if (walkingActive) "Stop walking" else "Walk to destination")) walkingActive = !walkingActive
        gfx.separator()

        // Location lookup
        gfx.text("Find a place")
        locQuery = gfx.inputText("##ww_loc", locQuery)
        gfx.sameLine()
        if (gfx.button("Go##loc") && locQuery.isNotBlank()) locations.nearest(locQuery)?.let { navigateTo(it) }

        // NPC lookup
        gfx.text("Find an NPC")
        val ns = gfx.npcPicker("ww_npc", npcSel)
        if (ns != npcSel) { npcSel = ns; npcTile = if (ns >= 0) locations.nearestNpc(ns) else null }
        npcTile?.let { t ->
            gfx.textColored(DIM, "→ ${t.x}, ${t.y}, ${t.plane}")
            gfx.sameLine()
            if (gfx.button("Walk here##npc")) navigateTo(t)
        }

        // Object lookup
        gfx.text("Find an object")
        val os = gfx.objectPicker("ww_obj", objSel)
        if (os != objSel) { objSel = os; objTile = if (os >= 0) locations.nearestObject(os) else null }
        objTile?.let { t ->
            gfx.textColored(DIM, "→ ${t.x}, ${t.y}, ${t.plane}")
            gfx.sameLine()
            if (gfx.button("Walk here##obj")) navigateTo(t)
        }
        gfx.separator()

        // Event log (most recent first)
        gfx.text("Recent events")
        val recent = synchronized(eventLog) { eventLog.toList() }
        if (recent.isEmpty()) gfx.textColored(DIM, "—")
        else for (line in recent.asReversed().take(8)) gfx.textColored(DIM, line)
    }

    private fun phaseColor(phase: WalkPhase): Int = when (phase) {
        WalkPhase.FAILED, WalkPhase.STUCK -> BAD
        WalkPhase.ARRIVED -> OK
        WalkPhase.IDLE -> DIM
        else -> ACTIVE
    }

    // ---- in-world route overlay (reproduces the old RouteOverlay via WebWalker.routeView) ----
    override fun onOverlay(gfx: Gfx3D) {
        if (!Config.routeOverlay) return
        val rv = webWalking.routeView() ?: return

        // The collision-aware local tile path to the current target.
        val path = rv.localPath
        for (i in 1 until path.size) gfx.line(path[i - 1], path[i], ROUTE, 3f)
        rv.target?.let { gfx.dot(it, 5f, CURRENT) }

        // An off-plan object shortcut being taken, otherwise the coarse "ahead" guide over the remaining legs.
        val sc = rv.shortcut
        val scFrom = sc?.from
        if (sc != null && scFrom != null) {
            gfx.line(scFrom, sc.to, SHORTCUT, 3f)
            gfx.dot(scFrom, 4f, SHORTCUT); gfx.dot(sc.to, 5f, SHORTCUT)
            gfx.drawText(sc.to, sc.name, LABEL)
        } else {
            drawAheadGuide(gfx, rv.legs, rv.target)
        }

        gfx.drawTile(rv.destination, DEST)
    }

    /** Draw the off-scene legs ahead as a route guide (walk lines + transport hops), mirroring the original. */
    private fun drawAheadGuide(gfx: Gfx3D, legs: List<RouteLeg>, target: Tile?) {
        if (legs.isEmpty()) return
        val pts = legs.map { it.to }
        val prevStart = target ?: gfx.playerTile() ?: return
        val from = if (target == null) 0 else pts.indexOf(target).let { if (it < 0) 0 else it + 1 }
        var prev = prevStart
        for (i in from until legs.size) {
            val leg = legs[i]
            val end = pts[i]
            if (leg is RouteLeg.Transport) {
                val t = leg.transport
                val tf = t.from
                if (tf != null) {
                    gfx.line(prev, tf, ROUTE, 1.5f)
                    gfx.line(tf, t.to, SHORTCUT, 3f)
                    gfx.dot(tf, 4f, SHORTCUT); gfx.dot(t.to, 5f, SHORTCUT)
                    gfx.drawText(t.to, t.name, LABEL)
                } else {
                    gfx.line(prev, end, ROUTE, 1.5f)
                    gfx.drawText(end, t.name, LABEL)
                }
            } else {
                gfx.line(prev, end, ROUTE, 1.5f)
            }
            prev = end
        }
    }

    private companion object {
        const val LOG_CAP = 40

        // Panel status colours (0xAARRGGBB).
        const val DIM = 0xFF9AA0B0.toInt()
        const val OK = 0xFF5AE07A.toInt()
        const val BAD = 0xFFE05A5A.toInt()
        const val ACTIVE = 0xFF5A9AE0.toInt()

        // Overlay colours (0xAARRGGBB).
        const val ROUTE = 0xC800E5FF.toInt()    // cyan local path
        const val CURRENT = 0xFFFFFFFF.toInt()  // current target dot
        const val SHORTCUT = 0xFFFFC24B.toInt() // amber transports/shortcuts
        const val LABEL = 0xFFEAEAEA.toInt()
        const val DEST = 0xFF5AE07A.toInt()      // green destination tile
    }
}
