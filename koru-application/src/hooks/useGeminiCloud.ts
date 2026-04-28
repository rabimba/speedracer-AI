import { useState, useCallback, useEffect } from 'react';
import { RACING_PHYSICS_KNOWLEDGE } from '../utils/coachingKnowledge';
import { convertToWav } from '../utils/audioUtils';
import { GoogleGenAI, Modality, ThinkingLevel } from '@google/genai';
import type { CloudModel } from '../types';

interface CloudStatus {
  state: 'idle' | 'loading' | 'error' | 'success';
  error?: string;
  hasKey: boolean;
}

const CLOUD_MODELS = {
  pro: 'gemini-3.1-pro-preview',
  flash: 'gemini-3-flash-preview',
} as const;

function readStoredApiKey(): string | null {
  try {
    return localStorage.getItem('gemini_api_key') || null;
  } catch {
    return null;
  }
}

export const useGeminiCloud = (externalApiKey?: string | null) => {
  const normalizedExternalApiKey = externalApiKey || null;
  const [apiKey, setAutoApiKey] = useState<string | null>(() =>
    normalizedExternalApiKey ?? readStoredApiKey()
  );

  const [status, setStatus] = useState<CloudStatus>({
    state: 'idle',
    hasKey: !!apiKey,
  });

  useEffect(() => {
    if (externalApiKey === undefined) return;
    setAutoApiKey(normalizedExternalApiKey);
    setStatus(prev => ({
      ...prev,
      hasKey: !!normalizedExternalApiKey,
      error: normalizedExternalApiKey ? undefined : prev.error,
    }));
  }, [externalApiKey, normalizedExternalApiKey]);

  const setApiKey = useCallback((key: string) => {
    if (key) {
      localStorage.setItem('gemini_api_key', key);
      setAutoApiKey(key);
      setStatus(prev => ({ ...prev, hasKey: true }));
    } else {
      localStorage.removeItem('gemini_api_key');
      setAutoApiKey(null);
      setStatus(prev => ({ ...prev, hasKey: false }));
    }
  }, []);

  const generateFeedback = useCallback(
    async (model: CloudModel, contextString: string) => {
      if (!apiKey) {
        setStatus({ state: 'error', hasKey: false, error: 'API Key missing' });
        return '';
      }

      setStatus(prev => ({ ...prev, state: 'loading', error: undefined }));

      try {
        const client = new GoogleGenAI({
          apiKey,
          httpOptions: { apiVersion: 'v1beta' },
        });
        const modelName = CLOUD_MODELS[model];

        const prompt = model === 'pro'
          ? `You are an Elite Driver Coach.
${RACING_PHYSICS_KNOWLEDGE}

### EXAMPLES:
**Bad:** "You went too fast. Slow down." → Too generic.
**Good:** "In Turn 2, telemetry shows a sudden lift. Keep 10-20% 'maintenance throttle'. Physics: Lift-Off Oversteer."

Analyze:
${contextString}

**Directive:** [Max 10 words]
### Analysis
[Detailed markdown with **Physics Diagnosis**, **Telemetry**, **Fix**]`
          : `You are a Race Engineer.
${RACING_PHYSICS_KNOWLEDGE}

INPUT: ${contextString}

TASK: Identify the biggest time loss. Explain the error.

**Directive:** [Short instruction]
### Analysis
[Explanation]`;
        const response = await client.models.generateContent({
          model: modelName,
          contents: prompt,
          config: {
            thinkingConfig: {
              thinkingLevel: model === 'pro' ? ThinkingLevel.HIGH : ThinkingLevel.LOW,
            },
          },
        });
        setStatus(prev => ({ ...prev, state: 'success' }));
        return response.text ?? '';
      } catch (err: unknown) {
        console.error('Gemini Cloud failed:', err);
        setStatus(prev => ({ ...prev, state: 'error', error: (err as Error).message }));
        return '';
      }
    },
    [apiKey]
  );

  const generateAudio = useCallback(async (text: string, voiceName = 'Zephyr'): Promise<Blob | null> => {
    if (!apiKey) return null;
    try {
      const client = new GoogleGenAI({ apiKey, httpOptions: { apiVersion: 'v1alpha' } });

      const response = await client.models.generateContentStream({
        model: 'models/gemini-2.5-pro-preview-tts',
        config: {
          responseModalities: [Modality.AUDIO],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } },
        },
        contents: [{ role: 'user', parts: [{ text: `Read aloud verbatim: "${text}"` }] }],
      });

      const audioParts: string[] = [];
      let audioMimeType = '';

      for await (const chunk of response) {
        const inlineData = chunk.candidates?.[0]?.content?.parts?.[0]?.inlineData;
        if (inlineData) {
          audioParts.push(inlineData.data || '');
          if (!audioMimeType && inlineData.mimeType) audioMimeType = inlineData.mimeType;
        }
      }

      if (audioParts.length > 0) {
        const wavBuffer = convertToWav(audioParts, audioMimeType || 'audio/pcm; rate=24000');
        const blobBytes = Uint8Array.from(wavBuffer);
        return new Blob([blobBytes], { type: 'audio/wav' });
      }
      return null;
    } catch (e) {
      console.error('Audio gen failed:', e);
      return null;
    }
  }, [apiKey]);

  return { status, generateFeedback, generateAudio, setApiKey, apiKey };
};
