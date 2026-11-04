// Pre-bundled with esbuild (platform=browser) so Rollup/Vite never has to
// resolve pi-ai's node-only optional dependencies. Loaded at runtime via
// dynamic import from /pi-bundle.mjs.
export { registerBuiltInApiProviders, stream } from "@mariozechner/pi-ai";
