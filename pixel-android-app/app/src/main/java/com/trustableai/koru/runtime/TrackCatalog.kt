package com.trustableai.koru.runtime

import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.Track

object TrackCatalog {
    val thunderhillEast = Track(
        name = "Thunderhill Raceway (East)",
        lengthMeters = 4612.0,
        centerLat = 39.540473,
        centerLon = -122.331475,
        corners = listOf(
            Corner(1, "Turn 1", 200.0, 350.0, 500.0, 39.539283, -122.331360, "Brake early, long left"),
            Corner(2, "Turn 2", 850.0, 1000.0, 1150.0, 39.535989, -122.327133, "Patient throttle, open up"),
            Corner(3, "Turn 3", 1400.0, 1550.0, 1700.0, 39.539355, -122.328895, "Off-camber, stay tight"),
            Corner(4, "The Cyclone", 2200.0, 2350.0, 2500.0, 39.544945, -122.330655, "Blind crest, aim left"),
            Corner(5, "Turn 10", 3500.0, 3650.0, 3800.0, 39.538279, -122.333442, "Fast exit, use track"),
        ),
    )

    fun fromName(name: String?): Track {
        return if (name == thunderhillEast.name) thunderhillEast else thunderhillEast
    }
}
