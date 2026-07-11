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
import io.osrsx.plugin.HasPanel
import io.osrsx.plugin.MenuIcon
import io.osrsx.plugin.MenuItem
import io.osrsx.plugin.Plugin

/**
 * Drives the global web walker from a docked sidebar panel — the standalone successor to the in-core Web
 * Walker plugin, built entirely on the public osrsx SDK.
 *
 * The panel ([onMenu]) is a game-engine-style control surface laid out in framed sections: an inline
 * **X / Y / Z** tile field with a **Start/Stop** button, plus **location** (autocomplete), **NPC** and
 * **object** lookups that *populate* the tile field (press Start to walk). The in-world route is drawn
 * through [HasOverlay] via [webWalking].routeView(), a right-click **"Walk here"** is contributed to the
 * world map via the SDK [worldMap] service, and the event log can be **popped out** into a floating
 * window ([HasPanel]).
 *
 * The panel shows/hides with the plugin itself (the sidebar item follows the enabled state — no "show
 * window" toggle), and the walker engine lives in the client; this plugin only observes and drives it.
 */
class WebWalkerPlugin : Plugin(), HasMenu, HasOverlay, HasPanel {

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
    @Volatile private var walkPopped = false
    @Volatile private var findPopped = false
    @Volatile private var eventsPopped = false
    private var dx = 3164 // Grand Exchange, a sensible default destination
    private var dy = 3486
    private var dz = 0
    private fun dest() = Tile(dx, dy, dz)

    // ---- lookup selections ----
    private var locName = ""
    private var npcSel = -1
    private var objSel = -1

    // ---- named-place catalog (world-map labels + teleport destinations), loaded once from the SDK ----
    private var placeNames: List<String> = emptyList()
    private var placesByName: Map<String, Tile> = emptyMap()
    private fun ensurePlaces() {
        if (placeNames.isNotEmpty()) return
        val ps = locations.places() // already name-ordered
        placesByName = ps.associate { it.name to it.tile }
        placeNames = ps.map { it.name }
    }

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
            // The world-map "Walk here" is an explicit action, so it both sets the tile AND starts walking.
            worldMapSub = worldMap.addMenuOption("Walk here") { setDest(it); walkingActive = true }
        } else if (!Config.worldMapWalkHere && worldMapSub != null) {
            worldMapSub?.unsubscribe(); worldMapSub = null
        }
    }

    /** Populate the destination tile (does NOT start walking — the Start button does). */
    private fun setDest(tile: Tile) { dx = tile.x; dy = tile.y; dz = tile.plane }

    private fun toggleWalk() {
        walkingActive = !walkingActive
        if (!walkingActive) webWalking.stop() // truly cancel: clear the plan/detour, reset to IDLE
    }

    override fun onLoop(): Long {
        if (!walkingActive) return 300
        if (worldMap.isOpen()) return 250 // the map covers the screen — wait for it to close
        val d = dest()
        if (webWalking.arrived(d)) { walkingActive = false; return 300 }
        // Drive the walk FIRST, then read the phase. The engine's WalkState.phase is global and sticky: after a
        // failed route it stays FAILED until the next walkTo re-plans. Checking it *before* walkTo would abort a
        // brand-new destination on the stale FAILED and the walker would "refuse to route anywhere else". walkTo(d)
        // transitions a fresh dest to PLANNING first, so the check below only trips on a real failure of THIS dest.
        webWalking.walkTo(d)
        if (webWalking.state().phase == WalkPhase.FAILED) { walkingActive = false; return 500 }
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

        gfx.section("Status") { g ->
            g.textColored(phaseColor(st.phase), st.phase.toString())
            g.textWrapped(st.message)
        }

        // Each of these sections can be popped out into its own floating window via the pop-out icon notched
        // into its top-right border. While popped, the section is drawn only in the overlay ([onPanel]).
        if (!walkPopped && gfx.section("Walk to tile", POP_OUT) { g -> renderWalkToTile(g) }) walkPopped = true
        if (!findPopped && gfx.section("Find a destination", POP_OUT) { g -> renderFind(g) }) findPopped = true
        if (!eventsPopped && gfx.section("Recent events", POP_OUT) { g -> renderEvents(g) }) eventsPopped = true
    }

    /** Popped-out sections, each in its own floating window (HasPanel) with a pin icon in the header to dock. */
    override fun onPanel(gfx: Gfx2D) {
        if (walkPopped && popWindow(gfx, "Walk to tile") { renderWalkToTile(it) }) walkPopped = false
        if (findPopped && popWindow(gfx, "Find a destination") { renderFind(it) }) findPopped = false
        if (eventsPopped && popWindow(gfx, "Recent events") { renderEvents(it) }) eventsPopped = false
    }

    /** Draw [title] as a fixed-width floating window; @return true if its header dock icon was clicked. The
     *  section fields render standalone (no wrapping frame) — the window title bar carries the dock icon. */
    private fun popWindow(gfx: Gfx2D, title: String, body: (Gfx2D) -> Unit): Boolean {
        var dock = false
        gfx.setNextWindowSize(POP_W, POP_H)
        gfx.overlay(title) { g ->
            if (g.headerButton(DOCK)) dock = true
            body(g)
        }
        return dock
    }

    // ---- section bodies (shared by the docked menu and the popped-out windows) ----
    private fun renderWalkToTile(g: Gfx2D) {
        g.field("Tile (X / Y / Z)") { f ->
            val xyz = f.inputInt3("", dx, dy, dz)
            dx = xyz[0]; dy = xyz[1]; dz = xyz[2]
        }
        if (g.button(if (walkingActive) "Stop" else "Start", true)) toggleWalk()
    }

    private fun renderFind(g: Gfx2D) {
        g.field("Location") { f ->
            ensurePlaces()
            val picked = f.searchPicker("ww_loc", placeNames, locName, "Search a place…")
            if (picked != locName) { locName = picked; placesByName[picked]?.let { setDest(it) } }
        }
        g.field("NPC") { f ->
            val ns = f.npcPicker("ww_npc", npcSel)
            if (ns != npcSel) { npcSel = ns; if (ns >= 0) locations.nearestNpc(ns)?.let { setDest(it) } }
        }
        g.field("Object") { f ->
            val os = f.objectPicker("ww_obj", objSel)
            if (os != objSel) { objSel = os; if (os >= 0) locations.nearestObject(os)?.let { setDest(it) } }
        }
        // Locate the nearest bank we can ACTUALLY reach (off the render thread — nearestBank pathfinds).
        if (g.button("Nearest bank", true)) {
            Thread({ webWalking.nearestBank()?.let { setDest(it) } }, "ww-nearest-bank").apply { isDaemon = true }.start()
        }
    }

    /** The event log, drawn most-recent-first. */
    private fun renderEvents(g: Gfx2D) {
        val recent = synchronized(eventLog) { eventLog.toList() }
        if (recent.isEmpty()) g.textColored(DIM, "No events yet")
        else for (line in recent.asReversed().take(12)) g.textWrappedColored(DIM, line)
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
        val POP_OUT = MenuIcon.custom(0xF065) // expand (arrows-out) — pop the section into a window
        val DOCK = MenuIcon.custom(0xF066)    // compress (arrows-in) — dock the window back into the menu
        const val POP_W = 340f                // ~half the previous width (the standard panel width)
        const val POP_H = 300f

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
