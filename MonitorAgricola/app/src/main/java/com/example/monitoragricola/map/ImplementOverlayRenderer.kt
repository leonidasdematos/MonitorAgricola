package com.example.monitoragricola.map

import android.graphics.Color
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class ImplementOverlayRenderer(
    private val map: MapView
) {
    private var implementBar: Polyline? = null
    private var implementLink: Polyline? = null

    /** Desenha a barra do implemento (gp1..gp2) e o link trator->(articulação?)->centro do implemento. */
    fun update(impl: Implemento?, tractorPos: GeoPoint) {
        val implBase = impl as? ImplementoBase ?: run {
            clear()
            return
        }

        // 1) Barra do implemento
        val bar = implBase.getImplementBarEndpoints()
        if (bar == null) {
            removeBar()
        } else {
            val (gp1, gp2) = bar
            if (implementBar == null) {
                implementBar = Polyline(map).apply {
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.color = Color.argb(255, 128, 128, 128)
                    isGeodesic = false
                    setPoints(listOf(gp1, gp2))
                }
                map.overlays.add(implementBar)
            } else {
                implementBar?.setPoints(listOf(gp1, gp2))
            }
        }

        // 2) Link (trator -> [articulação] -> centro do implemento)
        val center = implBase.getImplementCenter()
        if (center == null) {
            removeLink()
        } else {
            val pts = mutableListOf<GeoPoint>()
            pts += tractorPos
            implBase.getArticulationPoint()?.let { pts += it }
            pts += center

            if (implementLink == null) {
                implementLink = Polyline(map).apply {
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.color = Color.argb(180, 80, 80, 80)
                    isGeodesic = false
                    setPoints(pts)
                }
                map.overlays.add(implementLink)
            } else {
                implementLink?.setPoints(pts)
            }
        }

        map.invalidate()
    }

    fun clear() {
        removeBar()
        removeLink()
        map.invalidate()
    }

    private fun removeBar() {
        implementBar?.let { map.overlays.remove(it) }
        implementBar = null
    }

    private fun removeLink() {
        implementLink?.let { map.overlays.remove(it) }
        implementLink = null
    }
}
