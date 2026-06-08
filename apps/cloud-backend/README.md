# Cloud Backend

FastAPI service for the V2 cold path:

- session DEL summaries
- doctrine retrieval for racing pedagogy
- Learning Plan generation and hashing
- cloud/local fallback status for air-gapped paddock operation

Run locally:

```sh
cd apps/cloud-backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8010
```

The service is intentionally usable without Vertex AI credentials. When cloud inference is unavailable it returns `local_gemma` as the inference route so the dashboard can surface the amber offline state required by the PRD.
