package com.livetracker.controller.ui.screens.map.offline

import com.livetracker.controller.ui.screens.map.EsriTileSource
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Registry of compliant tile sources configured for high-performance rendering
 * and bulk offline caching with safe thread and user-agent policies.
 */
object OfflineTileSources {
    /**
     * Standard OpenStreetMap tile source matching Mapnik endpoints and tile storage directory,
     * configured with a policy permitting controlled bulk download for offline use
     * (2 concurrent threads, meaningful & normalized user-agent).
     */
    val OPEN_STREET_MAP: OnlineTileSourceBase = XYTileSource(
        "Mapnik",
        0,
        19,
        256,
        ".png",
        arrayOf(
            "https://tile.openstreetmap.org/"
        ),
        "© OpenStreetMap contributors",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
        )
    )

    /**
     * Resolves the appropriate [OnlineTileSourceBase] for the requested provider and style,
     * ensuring all returned tile sources support offline caching.
     */
    fun resolveTileSource(
        provider: MapProvider,
        style: MapStyle,
        cartoApiKey: String = ""
    ): OnlineTileSourceBase {
        return when (provider) {
            MapProvider.OPEN_STREET_MAP -> when (style) {
                MapStyle.HUMANITARIAN -> XYTileSource(
                    "OSM_HOT",
                    0,
                    19,
                    256,
                    ".png",
                    arrayOf(
                        "https://a.tile.openstreetmap.fr/hot/",
                        "https://b.tile.openstreetmap.fr/hot/"
                    ),
                    "© OpenStreetMap contributors, Humanitarian OpenStreetMap Team"
                )
                MapStyle.TERRAIN -> XYTileSource(
                    "OpenTopoMap",
                    0,
                    17,
                    256,
                    ".png",
                    arrayOf(
                        "https://a.tile.opentopomap.org/",
                        "https://b.tile.opentopomap.org/",
                        "https://c.tile.opentopomap.org/"
                    ),
                    "Map data: © OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)"
                )
                MapStyle.OUTDOORS -> XYTileSource(
                    "CyclOSM",
                    0,
                    18,
                    256,
                    ".png",
                    arrayOf(
                        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
                        "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
                        "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
                    ),
                    "© OpenStreetMap contributors, CyclOSM"
                )
                MapStyle.FRANCE -> XYTileSource(
                    "OSM_France",
                    0,
                    19,
                    256,
                    ".png",
                    arrayOf(
                        "https://a.tile.openstreetmap.fr/osmfr/"
                    ),
                    "© OpenStreetMap France contributors"
                )
                MapStyle.TRANSPORT -> XYTileSource(
                    "OSM_Transport",
                    0,
                    18,
                    256,
                    ".png",
                    arrayOf(
                        "https://tile.memomaps.de/tilegen/"
                    ),
                    "© OpenStreetMap contributors, CC-BY-SA 2.0"
                )
                // Tactical Dark Mode is handled in MapCanvas via tile ColorMatrix filter on Mapnik
                else -> OPEN_STREET_MAP
            }

            MapProvider.ESRI_ARCGIS -> when (style) {
                // Clamped to 18 to avoid 'No data available' tiles at extreme zoom
                MapStyle.SATELLITE -> EsriTileSource("EsriSatellite", "World_Imagery", maxZoom = 18)
                MapStyle.TERRAIN -> EsriTileSource("EsriTerrain", "World_Topo_Map", maxZoom = 19)
                MapStyle.NATGEO -> EsriTileSource("EsriNatGeo", "NatGeo_World_Map", maxZoom = 16)
                MapStyle.DARK -> EsriTileSource("EsriDark", "Canvas/World_Dark_Gray_Base", maxZoom = 16)
                MapStyle.LIGHT -> EsriTileSource("EsriLight", "Canvas/World_Light_Gray_Base", maxZoom = 16)
                else -> EsriTileSource("EsriStandard", "World_Street_Map", maxZoom = 19)
            }

            MapProvider.CARTO -> {
                val trimmedKey = cartoApiKey.trim()
                if (trimmedKey.isNotEmpty()) {
                    val keySuffix = ".png?key=$trimmedKey"
                    when (style) {
                        MapStyle.LIGHT -> XYTileSource(
                            "CartoLight", 0, 20, 256, keySuffix,
                            arrayOf(
                                "https://a.basemaps.cartocdn.com/rastertiles/light_all/",
                                "https://b.basemaps.cartocdn.com/rastertiles/light_all/",
                                "https://c.basemaps.cartocdn.com/rastertiles/light_all/"
                            ),
                            "© CARTO, © OpenStreetMap contributors"
                        )
                        MapStyle.DARK -> XYTileSource(
                            "CartoDark", 0, 20, 256, keySuffix,
                            arrayOf(
                                "https://a.basemaps.cartocdn.com/rastertiles/dark_all/",
                                "https://b.basemaps.cartocdn.com/rastertiles/dark_all/",
                                "https://c.basemaps.cartocdn.com/rastertiles/dark_all/"
                            ),
                            "© CARTO, © OpenStreetMap contributors"
                        )
                        else -> XYTileSource(
                            "CartoVoyager", 0, 20, 256, keySuffix,
                            arrayOf(
                                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                                "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
                            ),
                            "© CARTO, © OpenStreetMap contributors"
                        )
                    }
                } else {
                    // Graceful fallback to keyless alternatives so map NEVER shows "API key is wrong" error tiles
                    when (style) {
                        MapStyle.LIGHT -> EsriTileSource("EsriLightFallback", "Canvas/World_Light_Gray_Base", maxZoom = 16)
                        MapStyle.DARK -> EsriTileSource("EsriDarkFallback", "Canvas/World_Dark_Gray_Base", maxZoom = 16)
                        else -> OPEN_STREET_MAP
                    }
                }
            }
            else -> OPEN_STREET_MAP
        }
    }
}
