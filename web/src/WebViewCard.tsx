import { useState } from "react";
import { openEmbeddedWebview } from "./bridge";

export function WebViewCard({ uri }: { uri: string }) {
  const [error, setError] = useState("");
  return <div className="webview-card">
    <div className="webview-uri">🌐 {uri}</div>
    <button type="button" onClick={() => setError(openEmbeddedWebview(uri))}>打开内嵌浏览器</button>
    {error && <div className="webview-error">{error}</div>}
  </div>;
}
