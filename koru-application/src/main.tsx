import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'

function renderFatalError(message: string): void {
  const root = document.getElementById('root');
  if (!root) return;

  root.innerHTML = `
    <div style="padding:24px;font-family:monospace;background:#140f12;color:#ffe7ef;min-height:100vh;white-space:pre-wrap">
      <h1 style="margin:0 0 12px;font-size:20px">Koru WebView Error</h1>
      <div>${message.replace(/[&<>]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char] ?? char))}</div>
    </div>
  `;
}

window.addEventListener('error', (event) => {
  const details = event.error?.stack ?? event.message ?? 'Unknown JavaScript error';
  renderFatalError(details);
});

window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason instanceof Error
    ? event.reason.stack ?? event.reason.message
    : String(event.reason ?? 'Unknown promise rejection');
  renderFatalError(reason);
});

async function bootstrap(): Promise<void> {
  try {
    const [{ default: App }, { preloadSharedPhraseCatalog }] = await Promise.all([
      import('./App'),
      import('./services/sharedPhraseCatalog'),
    ]);

    preloadSharedPhraseCatalog();

    const rootElement = document.getElementById('root');
    if (!rootElement) {
      throw new Error('Missing #root element');
    }

    createRoot(rootElement).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  } catch (error) {
    const details = error instanceof Error
      ? error.stack ?? error.message
      : String(error ?? 'Unknown bootstrap failure');
    renderFatalError(details);
    console.error('Koru bootstrap failed:', error);
  }
}

void bootstrap();
