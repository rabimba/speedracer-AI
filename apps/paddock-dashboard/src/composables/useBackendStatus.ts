import { computed, ref } from 'vue';

export function useBackendStatus() {
  const cloudReachable = ref(false);
  const tokenSpeed = ref(24.6);
  const ttftMs = ref(82);
  const route = computed(() => cloudReachable.value ? 'Vertex AI' : 'Local Gemma');
  const banner = computed(() => cloudReachable.value
    ? 'Cloud online. Strategic inference available.'
    : 'Cloud offline. Local Gemma active.');

  function toggleCloud() {
    cloudReachable.value = !cloudReachable.value;
  }

  return { cloudReachable, tokenSpeed, ttftMs, route, banner, toggleCloud };
}
