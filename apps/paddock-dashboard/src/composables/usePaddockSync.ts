import { computed, ref, type Ref } from 'vue';
import type { LearningPlanEnvelope } from '@trustable/core-telemetry';

export type SyncTopology = 'adb' | 'hotspot' | 'monolithic';

export function usePaddockSync(envelope: Ref<LearningPlanEnvelope | null>) {
  const topology = ref<SyncTopology>('adb');
  const state = ref<'idle' | 'syncing' | 'verified' | 'blocked'>('idle');
  const lastMessage = ref('No sync attempted.');

  const label = computed(() => {
    if (topology.value === 'adb') return 'USB-C ADB umbilical';
    if (topology.value === 'hotspot') return 'Private local hotspot';
    return 'Pixel-local monolithic mode';
  });

  async function pushLearningPlan() {
    if (!envelope.value) {
      state.value = 'blocked';
      lastMessage.value = 'Generate a verified Learning Plan before sync.';
      return;
    }
    state.value = 'syncing';
    await new Promise((resolve) => window.setTimeout(resolve, 350));
    state.value = 'verified';
    lastMessage.value = `${label.value} accepted ${envelope.value.plan.id}; digest ${envelope.value.digest.slice(0, 10)}...`;
  }

  return { topology, state, label, lastMessage, pushLearningPlan };
}
