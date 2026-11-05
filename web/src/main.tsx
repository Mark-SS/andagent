import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { applyTheme, watchSystemTheme } from "./theme";
import "highlight.js/styles/github-dark.css";
import "katex/dist/katex.min.css";
import "./styles.css";
import App from "./App";

// Apply the persisted theme before first paint so startup has no flash.
applyTheme();
watchSystemTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
