/// <reference types="vitest/config" />
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  base: './',
  plugins: [react()],
  resolve: {
    alias: {
      '@trustable/core-telemetry': path.resolve(__dirname, '../packages/core-telemetry/src/index.ts'),
    },
  },
  test: {
    globals: true,
    environment: 'node',
  },
})
