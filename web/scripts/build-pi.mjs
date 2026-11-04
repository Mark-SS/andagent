import { build } from "esbuild";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));

await build({
  entryPoints: [path.join(root, "pi-entry.mjs")],
  bundle: true,
  format: "esm",
  platform: "browser",
  target: "es2020",
  outfile: path.join(root, "..", "public", "pi-bundle.js"),
  define: { "process.env.NODE_ENV": '"production"' },
  logLevel: "info",
});
