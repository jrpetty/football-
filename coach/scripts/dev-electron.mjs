// Runs the Vite dev server and Electron against it, with hot reload in the renderer.
// Main/preload changes need a restart.

import { spawn, execFileSync } from 'node:child_process'
import { createServer } from 'vite'

execFileSync(process.execPath, ['node_modules/typescript/bin/tsc', '-p', 'tsconfig.electron.json'], { stdio: 'inherit' })
const server = await createServer()
await server.listen()
const url = server.resolvedUrls.local[0]
const electronBin = (await import('electron')).default
const child = spawn(electronBin, ['.'], { stdio: 'inherit', env: { ...process.env, VITE_DEV_SERVER_URL: url } })
child.on('exit', async (code) => {
  await server.close()
  process.exit(code ?? 0)
})
