package com.livetracker.controller.ui.screens.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

class EsriTileSource(
    name: String,
    serviceName: String,
    maxZoom: Int = 18
) : OnlineTileSourceBase(
    name,
    0,
    maxZoom,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$serviceName/MapServer/tile/"),
    "Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex)
    }
}
