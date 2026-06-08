import unittest

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient

from app.main import app


class CloudBackendTest(unittest.TestCase):
    def test_health_defaults_to_local_gemma(self):
        client = TestClient(app)
        response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["inferenceRoute"], "local_gemma")

    def test_session_analysis_and_learning_plan(self):
        client = TestClient(app)
        frames = [
            {"time": 1, "distance": 3700, "speed": 58, "brake": 0, "throttle": 40, "gLat": 0.9, "gLong": -0.1},
            {"time": 2, "distance": 3990, "speed": 76, "brake": 0, "throttle": 62, "gLat": 0.8, "gLong": 0.1},
        ]
        analysis = client.post("/sessions/analyze", json={"sessionId": "s1", "frames": frames})
        self.assertEqual(analysis.status_code, 200)
        deltas = analysis.json()["deltas"]
        self.assertGreaterEqual(len(deltas), 1)

        plan = client.post("/learning-plans", json={"driverId": "d1", "deltas": deltas})
        self.assertEqual(plan.status_code, 200)
        payload = plan.json()
        self.assertEqual(payload["plan"]["schemaVersion"], 1)
        self.assertEqual(len(payload["digest"]), 64)
        self.assertLess(len(str(payload)), 50 * 1024)


if __name__ == "__main__":
    unittest.main()
