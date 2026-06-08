import { ref } from 'vue';

export function useCoachAudio() {
  const enabled = ref(true);
  const queueDepth = ref(0);
  const silenceRemainingMs = ref(0);

  function toggleAudio() {
    enabled.value = !enabled.value;
  }

  function enqueueCue(priority: number) {
    if (!enabled.value) return;
    if (priority === 0 || silenceRemainingMs.value <= 0) {
      queueDepth.value = Math.min(5, queueDepth.value + 1);
      silenceRemainingMs.value = priority === 0 ? 0 : 4000;
      window.setTimeout(() => {
        queueDepth.value = Math.max(0, queueDepth.value - 1);
      }, 700);
    }
  }

  return { enabled, queueDepth, silenceRemainingMs, toggleAudio, enqueueCue };
}
