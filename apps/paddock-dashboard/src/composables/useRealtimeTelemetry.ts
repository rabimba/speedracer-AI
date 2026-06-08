import { computed, ref, type Ref } from 'vue';
import type { TelemetryFrame } from '@trustable/core-telemetry';

export function useRealtimeTelemetry(frames: Readonly<Ref<TelemetryFrame[]>>) {
  const selectedIndex = ref(0);
  const latestFrame = computed(() => frames.value[Math.min(selectedIndex.value, frames.value.length - 1)] ?? null);
  const sensorTrust = computed(() => {
    const health = latestFrame.value?.sourceHealth;
    if (!health) return 0.44;
    let score = 0.35;
    if (health.raceBoxFixGood) score += 0.24;
    if (health.obdConnected && !health.obdStale) score += 0.18;
    if (health.canConnected) score += 0.18;
    if (health.fallbackStage === 'full') score += 0.05;
    return Math.min(1, score);
  });

  function selectFrame(index: number) {
    selectedIndex.value = Math.max(0, Math.min(index, frames.value.length - 1));
  }

  return { selectedIndex, latestFrame, sensorTrust, selectFrame };
}
