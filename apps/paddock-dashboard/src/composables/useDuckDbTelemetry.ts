import { computed, ref, type Ref } from 'vue';
import type { TelemetryFrame } from '@trustable/core-telemetry';

export function useDuckDbTelemetry(frames: Readonly<Ref<TelemetryFrame[]>>) {
  const engine = ref<'duckdb-wasm' | 'memory'>('memory');
  const query = ref('why was Turn 11 apex speed slow?');
  const initialized = ref(false);

  async function initialize() {
    try {
      await import('@duckdb/duckdb-wasm');
      engine.value = 'duckdb-wasm';
    } catch {
      engine.value = 'memory';
    }
    initialized.value = true;
  }

  const lapRows = computed(() => frames.value.map((frame) => ({
    time: frame.time,
    distance: frame.distance ?? 0,
    speed: frame.speed,
    brake: frame.brake,
    throttle: frame.throttle,
  })));

  const answer = computed(() => {
    const turn11 = lapRows.value.find((row) => row.distance >= 3650 && row.distance <= 3750);
    if (!turn11) return 'No Turn 11 rows loaded.';
    if (turn11.speed > 50 && turn11.throttle > 25) {
      return 'Throttle arrived before the car finished rotating; delay the squeeze.';
    }
    if (turn11.speed < 42) {
      return 'Apex speed is below reference; release brake earlier and carry entry speed.';
    }
    return 'Turn 11 is close to reference. Protect exit commitment.';
  });

  return { engine, query, initialized, lapRows, answer, initialize };
}
