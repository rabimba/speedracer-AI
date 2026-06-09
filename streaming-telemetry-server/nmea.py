"""Pure NMEA parsing helpers.

This module is intentionally free of any server or serial-transport
dependencies (FastAPI, sse-starlette, pyserial-asyncio). It depends only on
``pynmea2`` and the standard library, so the parser can be imported and unit
tested without standing up the full ingest server.
"""

import logging
from datetime import datetime, timezone

import pynmea2

logger = logging.getLogger(__name__)


def parse_nmea_sentence(line: str):
    """Parses an NMEA sentence and returns a structured dict or None."""
    try:
        msg = pynmea2.parse(line)
        if isinstance(msg, (pynmea2.types.talker.RMC, pynmea2.types.talker.GGA)):
            lat = getattr(msg, "latitude", 0.0)
            lon = getattr(msg, "longitude", 0.0)
            speed_knots = getattr(msg, "spd_over_grnd", 0.0)
            speed_ms = float(speed_knots or 0) * 0.514444
            speed_kmh = float(speed_knots or 0) * 1.852
            heading = getattr(msg, "true_course", 0.0)
            heading_degrees = float(heading or 0)

            current_time = datetime.now(timezone.utc).isoformat()

            return {
                "class": "TPV",
                "type": "gps",
                "device": "/dev/serial",
                "mode": 3 if msg.is_valid else 1,  # Simplified mode logic
                "time": current_time,
                "lat": lat,
                "lon": lon,
                "alt": getattr(msg, "altitude", 0.0),
                "speed": speed_kmh,
                "speed_mps": speed_ms,
                "heading": heading_degrees,
                "track": heading_degrees,
            }
    except pynmea2.ParseError:
        pass
    except Exception as e:  # noqa: BLE001 - log and fall through to None
        logger.error(f"Error parsing NMEA: {e}")
    return None
