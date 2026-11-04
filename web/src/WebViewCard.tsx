import { useState } from "react";
import { openEmbeddedWebview } from "./bridge";

/**
 * Web page card rendered inside the conversation flow (#8).
 *
 * It lives in the main WebView, so AndroidBridge (and therefore andagent.tool)
 * is inherited from the host page by same-origin assets. Cross-origin pages
 * cannot reach the bridge unless they explicitly opt in, and the iframe is
 * sandboxed to keep navigation from escaping the card.
 */
export function WebViewCard({ uri }: { uri: string }) {
  const [error, setError] = useState("");
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [nonce, setNonce] = useState(0);
  const allowed = /^https:\/\/[^/\s]+/i.test(uri);

  const openExternal = () => {
    const result = openEmbeddedWebview(uri);
    setError(result.startsWith("ERROR:") ? result : "");
  };

  if (!allowed) return <div className="webview-card"><div className="webview-uri">🌐 {uri}</div><div className="webview-error">仅支持 HTTPS 网页</div></div>;

  return (
    <div className={`webview-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🌐 {uri}</span>
        {visible && <button type="button" onClick={() => setNonce((v) => v + 1)} title="刷新">↻</button>}
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? "退出全屏" : "全屏"}</button>
        <button type="button" onClick={openExternal} title="在独立窗口中打开">独立窗口</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible((v) => !v); }}>{visible ? "关闭" : "展开"}</button>
      </div>
      {visible && (
        <iframe
          key={nonce}
          title={uri}
          src={uri}
          loading="lazy"
          referrerPolicy="no-referrer"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
        />
      )}
      {error && <div className="webview-error">{error}</div>}
    </div>
  );
}
