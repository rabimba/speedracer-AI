# @trustable/core-telemetry

Shared telemetry types and utilities for the Super App V2 monorepo.

## Contents

| Module | Status |
|--------|--------|
| `TelemetryFrame`, `CornerPhase`, track types | Migrated from `koru-application` |
| `geo/geoUtils` | Haversine distance, heading, GPS validation |
| `corner/cornerPhaseDetector` | GPS + G-force corner phase detection |
| `del/` | Placeholder for Driver Experience Layer (Sprint 1+) |
| `tracks/` | Placeholder for shared track definitions (Sprint 1+) |

## Usage

```ts
import {
  type TelemetryFrame,
  type CornerPhase,
  haversineDistance,
  CornerPhaseDetector,
} from '@trustable/core-telemetry';
```

## Development

```bash
npm run test -w @trustable/core-telemetry
npm run build -w @trustable/core-telemetry
```

`koru-application` re-exports from this package during the gradual migration; existing import paths in the app continue to work.
