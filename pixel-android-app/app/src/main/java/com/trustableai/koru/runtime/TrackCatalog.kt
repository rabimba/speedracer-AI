package com.trustableai.koru.runtime

import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.Track

object TrackCatalog {
    val sonomaRaceway =
        Track(
            name = "Sonoma Raceway",
            lengthMeters = 4031.38,
            centerLat = 38.16152,
            centerLon = -122.45472,
            corners =
                listOf(
                    Corner(
                        id = 1,
                        name = "Turn 1",
                        entryDist = 80.0,
                        apexDist = 120.0,
                        exitDist = 170.0,
                        lat = 38.16180,
                        lon = -122.45550,
                        advice = "Brake straight, settle the platform, and commit to a clean turn-in.",
                        entryLat = 38.16200,
                        entryLon = -122.45500,
                        targetSpeed = 55.0,
                    ),
                    Corner(
                        id = 2,
                        name = "Turn 2",
                        entryDist = 300.0,
                        apexDist = 390.0,
                        exitDist = 470.0,
                        lat = 38.16120,
                        lon = -122.45680,
                        advice = "Stay wide, trail brake in, use the apex curb, and throttle at the curb.",
                        entryLat = 38.16150,
                        entryLon = -122.45620,
                        targetSpeed = 70.0,
                    ),
                    Corner(
                        id = 3,
                        name = "Turn 3",
                        entryDist = 560.0,
                        apexDist = 640.0,
                        exitDist = 720.0,
                        lat = 38.16050,
                        lon = -122.45750,
                        advice = "Slow in, fast out. Late apex and wait longer than you think for throttle.",
                        entryLat = 38.16080,
                        entryLon = -122.45720,
                        targetSpeed = 52.0,
                    ),
                    Corner(
                        id = 31,
                        name = "Turn 3A",
                        entryDist = 760.0,
                        apexDist = 850.0,
                        exitDist = 950.0,
                        lat = 38.16015,
                        lon = -122.45810,
                        advice = "Brake in a straight line, be patient over the crest, and let the car rotate.",
                        entryLat = 38.16030,
                        entryLon = -122.45780,
                        targetSpeed = 45.0,
                    ),
                    Corner(
                        id = 6,
                        name = "Turn 6",
                        entryDist = 1385.0,
                        apexDist = 1760.0,
                        exitDist = 2050.0,
                        lat = 38.15910,
                        lon = -122.45620,
                        advice = "Carousel: hug the inside, keep maintenance throttle, and never give up distance.",
                        entryLat = 38.15950,
                        entryLon = -122.45720,
                        targetSpeed = 65.0,
                    ),
                    Corner(
                        id = 7,
                        name = "Turn 7",
                        entryDist = 2100.0,
                        apexDist = 2190.0,
                        exitDist = 2280.0,
                        lat = 38.15950,
                        lon = -122.45400,
                        advice = "Double-apex mindset: stay tight, rotate in the middle, and use both apexes.",
                        entryLat = 38.15970,
                        entryLon = -122.45430,
                        targetSpeed = 52.0,
                    ),
                    Corner(
                        id = 910,
                        name = "Turns 9-10",
                        entryDist = 2900.0,
                        apexDist = 3230.0,
                        exitDist = 3500.0,
                        lat = 38.16040,
                        lon = -122.45320,
                        advice = "Open Turn 9, straighten the car, and sacrifice it for a clean Turn 10/11 run.",
                        entryLat = 38.16000,
                        entryLon = -122.45360,
                        targetSpeed = 58.0,
                    ),
                    Corner(
                        id = 11,
                        name = "Turn 11",
                        entryDist = 3550.0,
                        apexDist = 3700.0,
                        exitDist = 3850.0,
                        lat = 38.16100,
                        lon = -122.45300,
                        advice = "Big brake zone. Straight-line brake, patient release, and protect the exit.",
                        entryLat = 38.16120,
                        entryLon = -122.45330,
                        targetSpeed = 42.0,
                    ),
                    Corner(
                        id = 12,
                        name = "Turn 12",
                        entryDist = 3920.0,
                        apexDist = 3990.0,
                        exitDist = 4031.38,
                        lat = 38.16155,
                        lon = -122.45420,
                        advice = "Unwind early, free the hands, and commit to front-straight exit speed.",
                        entryLat = 38.16130,
                        entryLon = -122.45380,
                        targetSpeed = 80.0,
                    ),
                ),
        )

    val thunderhillEast =
        Track(
            name = "Thunderhill Raceway (East)",
            lengthMeters = 4612.0,
            centerLat = 39.540473,
            centerLon = -122.331475,
            corners =
                listOf(
                    Corner(1, "Turn 1", 200.0, 350.0, 500.0, 39.539283, -122.331360, "Brake early, long left"),
                    Corner(2, "Turn 2", 850.0, 1000.0, 1150.0, 39.535989, -122.327133, "Patient throttle, open up"),
                    Corner(3, "Turn 3", 1400.0, 1550.0, 1700.0, 39.539355, -122.328895, "Off-camber, stay tight"),
                    Corner(4, "The Cyclone", 2200.0, 2350.0, 2500.0, 39.544945, -122.330655, "Blind crest, aim left"),
                    Corner(5, "Turn 10", 3500.0, 3650.0, 3800.0, 39.538279, -122.333442, "Fast exit, use track"),
                ),
        )

    val defaultTrack: Track = sonomaRaceway

    fun fromName(name: String?): Track {
        if (name.isNullOrBlank()) return defaultTrack
        val normalized = name.trim().lowercase()
        return when {
            normalized == sonomaRaceway.name.lowercase() -> sonomaRaceway
            normalized.contains("sonoma") -> sonomaRaceway
            normalized == thunderhillEast.name.lowercase() -> thunderhillEast
            normalized.contains("thunderhill") -> thunderhillEast
            else -> defaultTrack
        }
    }
}
