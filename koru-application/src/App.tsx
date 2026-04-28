import { useState } from 'react';
import { BrowserRouter, HashRouter, Navigate, Outlet, Routes, Route } from 'react-router-dom';
import Navbar from './components/Navbar';
import Landing from './pages/Landing';
import Dashboard from './pages/Dashboard';
import LiveSession from './pages/LiveSession';
import Replay from './pages/Replay';
import Analysis from './pages/Analysis';

function readApiKey(): string | null {
  try {
    return localStorage.getItem('gemini_api_key');
  } catch {
    return null;
  }
}

function writeApiKey(key: string): void {
  try {
    if (key) {
      localStorage.setItem('gemini_api_key', key);
    } else {
      localStorage.removeItem('gemini_api_key');
    }
  } catch {
    // Ignore storage failures in WebView/file contexts.
  }
}

function AppLayout(props: {
  apiKey: string | null;
  onApiKeyChange: (key: string) => void;
}) {
  return (
    <>
      <Navbar apiKey={props.apiKey} onApiKeyChange={props.onApiKeyChange} />
      <main className="main-content">
        <Outlet />
      </main>
    </>
  );
}

export default function App() {
  const Router = typeof window !== 'undefined'
    && (window.location.protocol === 'file:' || Boolean(window.AndroidBridge))
    ? HashRouter
    : BrowserRouter;

  const [apiKey, setApiKey] = useState<string | null>(
    () => readApiKey()
  );

  const handleApiKeyChange = (key: string) => {
    writeApiKey(key);
    setApiKey(key || null);
  };

  return (
    <Router>
      <div className="app">
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route
            element={<AppLayout apiKey={apiKey} onApiKeyChange={handleApiKeyChange} />}
          >
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/live" element={<LiveSession apiKey={apiKey} />} />
            <Route path="/replay" element={<Replay apiKey={apiKey} />} />
            <Route path="/analysis" element={<Analysis apiKey={apiKey} />} />
          </Route>
          <Route path="*" element={<Navigate to="/live" replace />} />
        </Routes>
      </div>
    </Router>
  );
}
