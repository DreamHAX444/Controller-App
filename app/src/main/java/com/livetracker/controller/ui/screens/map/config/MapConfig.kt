package com.livetracker.controller.ui.screens.map.config

enum class MapProvider(
    val displayName: String,
    val description: String,
    val requiresConfiguration: Boolean,
    val configurationNotice: String? = null
) {
    OPEN_STREET_MAP(
        displayName = "OpenStreetMap",
        description = "Global open-source map ecosystem. Supports 7 unique appearances including standard, topographic, outdoors, and tactical night mode with zero API keys.",
        requiresConfiguration = false
    ),
    GOOGLE_MAPS(
        displayName = "Google Maps",
        description = "Official Google Maps Android SDK. Requires active Google Cloud billing.",
        requiresConfiguration = true,
        configurationNotice = "Notice: If Google Cloud billing is inactive, tiles will render blank beige. Use OpenStreetMap for immediate live tiles."
    ),
    ESRI_ARCGIS(
        displayName = "Esri ArcGIS",
        description = "High-quality street, satellite, and topographic maps from Esri. Free public access with intelligent zoom limits.",
        requiresConfiguration = false,
        configurationNotice = "Notice: Satellite view is optimized up to zoom level 18 to match global imagery coverage and eliminate 'No data available' areas."
    ),
    CARTO(
        displayName = "CARTO",
        description = "Beautiful minimalist maps designed for high-contrast telemetry visualization.",
        requiresConfiguration = true,
        configurationNotice = "Notice: CARTO requires a free API key from carto.com/basemaps/apikey. Enter your key below, or enjoy automatic keyless fallback."
    )
}

enum class MapStyle(
    val displayName: String,
    val description: String = ""
) {
    STANDARD(
        displayName = "Standard / Streets",
        description = "Classic detailed street and road cartography (Mapnik)"
    ),
    HUMANITARIAN(
        displayName = "Humanitarian (HOT)",
        description = "Warm pastel tones with clear roads, paths, and building footprints"
    ),
    TERRAIN(
        displayName = "Topographic (OpenTopo)",
        description = "Contour lines, hillshading, physical elevation relief"
    ),
    OUTDOORS(
        displayName = "Outdoors & Cycling (CyclOSM)",
        description = "Bicycle tracks, trails, hiking paths and points of interest"
    ),
    FRANCE(
        displayName = "OSM France",
        description = "High-contrast European cartographic design and typography"
    ),
    TRANSPORT(
        displayName = "Public Transport (ÖPNV)",
        description = "Commuter rail, metro lines, and bus transit networks"
    ),
    DARK(
        displayName = "Tactical Night / Dark",
        description = "Sleek low-light theme optimized for dark mode telemetry"
    ),
    LIGHT(
        displayName = "Minimal Light Gray",
        description = "Subdued light gray canvas prioritizing marker legibility"
    ),
    SATELLITE(
        displayName = "Satellite Imagery",
        description = "Photographic orbital view with automatic zoom limit"
    ),
    NATGEO(
        displayName = "National Geographic",
        description = "World-renowned National Geographic cartographic reference"
    ),
    HYBRID(
        displayName = "Hybrid Satellite",
        description = "Satellite photography combined with roads and labels"
    )
}

data class MapConfiguration(
    val provider: MapProvider,
    val style: MapStyle
)

fun MapProvider.getSupportedStyles(): List<MapStyle> {
    return when (this) {
        MapProvider.OPEN_STREET_MAP -> listOf(
            MapStyle.STANDARD,
            MapStyle.HUMANITARIAN,
            MapStyle.TERRAIN,
            MapStyle.OUTDOORS,
            MapStyle.FRANCE,
            MapStyle.TRANSPORT,
            MapStyle.DARK
        )
        MapProvider.ESRI_ARCGIS -> listOf(
            MapStyle.STANDARD,
            MapStyle.SATELLITE,
            MapStyle.TERRAIN,
            MapStyle.NATGEO,
            MapStyle.DARK,
            MapStyle.LIGHT
        )
        MapProvider.CARTO -> listOf(
            MapStyle.STANDARD,
            MapStyle.LIGHT,
            MapStyle.DARK
        )
        MapProvider.GOOGLE_MAPS -> listOf(
            MapStyle.STANDARD,
            MapStyle.SATELLITE,
            MapStyle.TERRAIN,
            MapStyle.HYBRID
        )
    }
}

fun MapProvider.getDefaultStyle(): MapStyle {
    return this.getSupportedStyles().first()
}

