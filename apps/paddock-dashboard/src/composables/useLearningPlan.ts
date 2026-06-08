import { computed, ref, type Ref } from 'vue';
import {
  buildLearningPlan,
  createLearningPlanEnvelope,
  type DeltaAnalysis,
  type LearningPlanEnvelope,
} from '@trustable/core-telemetry';

export function useLearningPlan(worstDelta: Ref<DeltaAnalysis | undefined>) {
  const envelope = ref<LearningPlanEnvelope | null>(null);
  const status = computed(() => envelope.value ? 'verified' : 'not generated');

  async function generatePlan() {
    const delta = worstDelta.value;
    const cornerId = delta?.corner?.id ?? 11;
    const cornerName = delta?.corner?.name ?? 'Turn 11';
    const plan = buildLearningPlan({
      id: `lp-${Date.now()}`,
      driverId: 'demo-driver',
      trackName: 'Sonoma Raceway',
      focus: 'late_apex',
      objective: `Only coach late apex timing at ${cornerName}.`,
      generatedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
      targets: [{
        cornerId,
        cornerName,
        phases: ['BRAKE_ZONE', 'TURN_IN'],
        targetDelta: 'delay turn-in until the late apex reference',
        allowedCueActions: ['WAIT', 'TURN_IN'],
      }],
      ignoredActions: ['HUSTLE', 'PUSH', 'FULL_THROTTLE'],
      notes: delta ? `Generated from ${delta.reference.phase} speed delta ${delta.apexSpeedDelta.toFixed(1)} mph.` : 'Generated from default Sonoma objective.',
    });
    envelope.value = await createLearningPlanEnvelope(plan, 'paddock-dashboard');
  }

  return { envelope, status, generatePlan };
}
