import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { localPhonePlugin } from './local/phone-server.mjs'

// PANDASTIC_PUBLIC_HOST: the server name of a deployed phone pair (README.md); Vite only answers known host names.
const publicHost = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.PANDASTIC_PUBLIC_HOST
export default defineConfig({ plugins: [react(), localPhonePlugin()], base: './', server: publicHost ? { allowedHosts: [publicHost] } : {} })
