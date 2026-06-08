<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  Activity,
  AlertTriangle,
  Cable,
  CloudOff,
  Cpu,
  Database,
  FileCheck,
  Gauge,
  HeartPulse,
  Play,
  Radio,
  Route,
  ShieldCheck,
  Smartphone,
  UploadCloud,
  Volume2,
  VolumeX,
  Wifi,
} from '@lucide/vue';
import { SONOMA_RACEWAY } from '@trustable/core-telemetry';
import { useBackendStatus } from './composables/useBackendStatus';
import { useCoachAudio } from './composables/useCoachAudio';
import { useDuckDbTelemetry } from './composables/useDuckDbTelemetry';
import { useLearningPlan } from './composables/useLearningPlan';
import { usePaddockSync, type SyncTopology } from './composables/usePaddockSync';
import { useRealtimeTelemetry } from './composables/useRealtimeTelemetry';
import { useRecordedSessions } from './composables/useRecordedSessions';
import { summarizeSession } from './lib/paddockDemo';

const { sessions } = useRecordedSessions();
const selectedSessionId = ref(sessions.value[0]?.id ?? '');
const backend = useBackendStatus();
const audio = useCoachAudio();
const currentSession = computed(() => sessions.value.find((session) => session.id === selectedSessionId.value) ?? sessions.value[0]!);
const frames = computed(() => currentSession.value?.frames ?? []);
const sessionSummary = computed(() => summarizeSession(currentSession.value));
const worstDelta = computed(() => sessionSummary.value.worstDelta);
const telemetry = useRealtimeTelemetry(frames);
const duck = useDuckDbTelemetry(frames);
const plan = useLearningPlan(worstDelta);
const sync = usePaddockSync(plan.envelope);

const mapPoints = computed(() => SONOMA_RACEWAY.mapPoints.map((point) => `${point.x},${point.y}`).join(' '));
const marker = computed(() => {
  const index = Math.min(SONOMA_RACEWAY.mapPoints.length - 1, Math.max(0, Math.round((telemetry.selectedIndex.value / Math.max(1, frames.value.length - 1)) * (SONOMA_RACEWAY.mapPoints.length - 1))));
  return SONOMA_RACEWAY.mapPoints[index] ?? SONOMA_RACEWAY.mapPoints[0];
});
const planTargetNames = computed(() => plan.envelope.value?.plan.targets.map((target) => target.cornerName).join(', ') ?? 'No active plan');

onMounted(async () => {
  await duck.initialize();
  await plan.generatePlan();
});

function setTopology(topology: SyncTopology) {
  sync.topology.value = topology;
}

function playCue() {
  audio.enqueueCue(worstDelta.value?.recommendedAction === 'BRAKE' ? 0 : 1);
}

const activeTab = ref<'analysis' | 'learning' | 'sync'>('analysis');

function setActiveTab(tab: 'analysis' | 'learning' | 'sync') {
  activeTab.value = tab;
}

function formatSigned(value: number) {
  return `${value > 0 ? '+' : ''}${value.toFixed(1)}`;
}
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">Trustable AI Racing Coach</p>
        <h1>Paddock Command</h1>
      </div>
      <div class="status-strip">
        <button class="icon-button" type="button" @click="backend.toggleCloud">
          <CloudOff :size="17" />
          <span>{{ backend.route }}</span>
        </button>
        <button class="icon-button" type="button" @click="audio.toggleAudio">
          <Volume2 v-if="audio.enabled.value" :size="17" />
          <VolumeX v-else :size="17" />
          <span>Audio {{ audio.enabled.value ? 'on' : 'off' }}</span>
        </button>
      </div>
    </header>

    <section class="offline-banner" :class="{ amber: !backend.cloudReachable.value }">
      <ShieldCheck :size="18" />
      <span>{{ backend.banner }}</span>
      <strong>{{ backend.tokenSpeed.value.toFixed(1) }} tok/s</strong>
      <strong>{{ backend.ttftMs.value }} ms TTFT</strong>
    </section>

    <section class="command-grid">
      <div class="command-primary">
        <div class="section-heading">
          <div>
            <p class="eyebrow">Session Command</p>
            <h2>{{ currentSession.trackName }}</h2>
          </div>
          <select v-model="selectedSessionId" class="select-control">
            <option v-for="session in sessions" :key="session.id" :value="session.id">
              {{ session.id }}
            </option>
          </select>
        </div>

        <div class="track-workspace">
          <svg viewBox="120 30 460 460" role="img" aria-label="Sonoma Raceway telemetry map">
            <polyline :points="mapPoints" fill="none" stroke="rgba(230,237,243,.78)" stroke-width="9" stroke-linecap="round" stroke-linejoin="round" />
            <polyline :points="mapPoints" fill="none" stroke="rgba(176,255,61,.58)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
            <circle v-if="marker" :cx="marker.x" :cy="marker.y" r="11" fill="#ff3b30" />
          </svg>
          <div class="cue-pane">
            <p class="eyebrow">Active Cue</p>
            <h3>{{ worstDelta.cue }}</h3>
            <p>{{ worstDelta.corner?.name ?? 'Reference' }} · {{ worstDelta.phase }} · {{ worstDelta.recommendedAction }}</p>
            <button class="primary-action" type="button" @click="playCue">
              <Play :size="16" />
              Audition Cue
            </button>
          </div>
        </div>
      </div>

      <aside class="side-stack">
        <section class="panel">
          <div class="section-heading compact">
            <p class="eyebrow">Edge Monitor</p>
            <Cpu :size="18" />
          </div>
          <div class="metric-row">
            <span>Sensor trust</span>
            <strong>{{ Math.round(telemetry.sensorTrust.value * 100) }}%</strong>
          </div>
          <div class="metric-row">
            <span>Audio queue</span>
            <strong>{{ audio.queueDepth.value }}</strong>
          </div>
          <div class="metric-row">
            <span>DuckDB mode</span>
            <strong>{{ duck.engine.value }}</strong>
          </div>
        </section>

        <section class="panel urgent" :class="{ critical: sessionSummary.brakeHealth.degraded }">
          <div class="section-heading compact">
            <p class="eyebrow">Vehicle Health</p>
            <AlertTriangle :size="18" />
          </div>
          <h3>{{ sessionSummary.brakeHealth.severity }}</h3>
          <p>{{ sessionSummary.brakeHealth.cue ?? 'No brake degradation detected.' }}</p>
        </section>

        <section class="panel">
          <div class="section-heading compact">
            <p class="eyebrow">Biometrics</p>
            <HeartPulse :size="18" />
          </div>
          <h3>{{ sessionSummary.biometric.state }}</h3>
          <p>{{ sessionSummary.biometric.cue ?? 'Driver load within expected band.' }}</p>
        </section>
      </aside>
    </section>

    <section class="work-surface">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'analysis' }" type="button" @click="setActiveTab('analysis')"><Database :size="15" /> Paddock Analysis</button>
        <button class="tab" :class="{ active: activeTab === 'learning' }" type="button" @click="setActiveTab('learning')"><FileCheck :size="15" /> Learning Plan</button>
        <button class="tab" :class="{ active: activeTab === 'sync' }" type="button" @click="setActiveTab('sync')"><UploadCloud :size="15" /> Sync Center</button>
      </div>

      <div v-if="activeTab === 'analysis'" class="analysis-layout">
        <section class="analysis-main">
          <div class="section-heading">
            <div>
              <p class="eyebrow">Natural Language Query</p>
              <h2>{{ duck.query.value }}</h2>
            </div>
            <span class="engine-pill">{{ duck.initialized.value ? duck.engine.value : 'loading' }}</span>
          </div>
          <p class="answer">{{ duck.answer.value }}</p>

          <div class="delta-table">
            <div class="delta-row header">
              <span>Corner</span>
              <span>Phase</span>
              <span>Speed Delta</span>
              <span>Brake Delta</span>
              <span>Cue</span>
            </div>
            <div v-for="delta in sessionSummary.deltas" :key="`${delta.reference.cornerId}-${delta.phase}`" class="delta-row">
              <span>{{ delta.corner?.name ?? `C${delta.reference.cornerId}` }}</span>
              <span>{{ delta.phase }}</span>
              <strong>{{ formatSigned(delta.apexSpeedDelta) }} mph</strong>
              <strong>{{ formatSigned(delta.brakeDelta) }}%</strong>
              <span>{{ delta.cue }}</span>
            </div>
          </div>
        </section>

        <aside class="analysis-inspector">
          <section class="panel flat">
            <div class="section-heading compact">
              <p class="eyebrow">Learning Plan</p>
              <Route :size="18" />
            </div>
            <h3>{{ plan.envelope.value?.plan.objective }}</h3>
            <p>Targets: {{ planTargetNames }}</p>
            <p>Digest: {{ plan.envelope.value?.digest.slice(0, 16) ?? 'pending' }}</p>
            <button class="primary-action" type="button" @click="plan.generatePlan">
              <FileCheck :size="16" />
              Regenerate Plan
            </button>
          </section>

          <section class="panel flat">
            <div class="section-heading compact">
              <p class="eyebrow">Sync Center</p>
              <Radio :size="18" />
            </div>
            <div class="segmented">
              <button :class="{ active: sync.topology.value === 'adb' }" type="button" @click="setTopology('adb')"><Cable :size="14" /> ADB</button>
              <button :class="{ active: sync.topology.value === 'hotspot' }" type="button" @click="setTopology('hotspot')"><Wifi :size="14" /> LAN</button>
              <button :class="{ active: sync.topology.value === 'monolithic' }" type="button" @click="setTopology('monolithic')"><Smartphone :size="14" /> Pixel</button>
            </div>
            <p>{{ sync.label.value }}</p>
            <p class="sync-message">{{ sync.lastMessage.value }}</p>
            <button class="primary-action" type="button" @click="sync.pushLearningPlan">
              <UploadCloud :size="16" />
              Push to Edge
            </button>
          </section>
        </aside>
      </div>

      <div v-else-if="activeTab === 'learning'" class="analysis-layout">
        <section class="analysis-main">
          <div class="section-heading">
            <div>
              <p class="eyebrow">Learning Plan</p>
              <h2>{{ plan.envelope.value?.plan.objective ?? 'Generate a plan from the latest session' }}</h2>
            </div>
          </div>
          <p>Targets: {{ planTargetNames }}</p>
          <p>Digest: {{ plan.envelope.value?.digest ?? 'pending' }}</p>
          <button class="primary-action" type="button" @click="plan.generatePlan">
            <FileCheck :size="16" />
            Regenerate Plan
          </button>
        </section>
      </div>

      <div v-else class="analysis-layout">
        <section class="analysis-main">
          <div class="section-heading">
            <div>
              <p class="eyebrow">Sync Center</p>
              <h2>Push learning plan to edge devices</h2>
            </div>
          </div>
          <div class="segmented">
            <button :class="{ active: sync.topology.value === 'adb' }" type="button" @click="setTopology('adb')"><Cable :size="14" /> ADB</button>
            <button :class="{ active: sync.topology.value === 'hotspot' }" type="button" @click="setTopology('hotspot')"><Wifi :size="14" /> LAN</button>
            <button :class="{ active: sync.topology.value === 'monolithic' }" type="button" @click="setTopology('monolithic')"><Smartphone :size="14" /> Pixel</button>
          </div>
          <p>{{ sync.label.value }}</p>
          <p class="sync-message">{{ sync.lastMessage.value }}</p>
          <button class="primary-action" type="button" @click="sync.pushLearningPlan">
            <UploadCloud :size="16" />
            Push to Edge
          </button>
        </section>
      </div>
    </section>

    <section class="telemetry-strip">
      <button
        v-for="(row, index) in duck.lapRows.value"
        :key="`${row.time}-${row.distance}`"
        class="sample-button"
        :class="{ active: telemetry.selectedIndex.value === index }"
        type="button"
        @click="telemetry.selectFrame(index)"
      >
        <Gauge :size="14" />
        <span>{{ row.distance.toFixed(0) }}m</span>
        <strong>{{ row.speed.toFixed(0) }} mph</strong>
      </button>
    </section>

    <footer class="footer-line">
      <Activity :size="15" />
      <span>Offline-first PWA surface. React workbench remains available during migration.</span>
    </footer>
  </main>
</template>
