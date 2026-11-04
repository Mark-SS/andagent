import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import {
  AssistantRuntimeProvider,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  useAui,
  useAuiState,
  useExternalStoreRuntime,
  type TextMessagePartProps,
  type ThreadMessageLike,
} from "@assistant-ui/react";
import { AgentEngine } from "./agent";
import {
  getAppVersion,
  hasBridge,
  installLLMFetch,
  loadSettings,
  type Settings,
} from "./bridge";
import { CopyMenu, useLongPress } from "./CopyMenu";
import { Drawer } from "./Drawer";
import { MarkdownText } from "./Markdown";
import { SettingsPanel } from "./Settings";
import { ToolCard } from "./ToolCard";

function useMessageText(): string {
  return useAuiState((state) =>
    state.message.content
      .map((part) => (part.type === "text" ? part.text : ""))
      .filter(Boolean)
      .join("\n")
      .trim(),
  );
}

function UserText({ text }: TextMessagePartProps) {
  return <div className="user-text">{text}</div>;
}

function UserMessage() {
  const text = useMessageText();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPress = useLongPress((x, y) => setMenu({ x, y }));
  return (
    <MessagePrimitive.Root className="msg msg-user" {...longPress}>
      <div className="user-bubble">
        <MessagePrimitive.Parts components={{ Text: UserText }} />
      </div>
      {menu && text && (
        <CopyMenu text={text} x={menu.x} y={menu.y} onClose={() => setMenu(null)} />
      )}
    </MessagePrimitive.Root>
  );
}

function AssistantMessage() {
  const running = useAuiState((state) => state.message.status?.type === "running");
  const text = useMessageText();
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const longPress = useLongPress((x, y) => setMenu({ x, y }));
  return (
    <MessagePrimitive.Root className="msg msg-assistant" {...longPress}>
      <div className="avatar" aria-hidden="true">
        <span>A</span>
      </div>
      <div className="assistant-body">
        <MessagePrimitive.Parts
          components={{
            Text: MarkdownText,
            tools: { Fallback: ToolCard },
          }}
        />
        {running && <span className="stream-cursor" aria-hidden="true" />}
        <MessagePrimitive.Error>
          <div className="msg-error">这条消息渲染失败。</div>
        </MessagePrimitive.Error>
      </div>
      {menu && text && (
        <CopyMenu text={text} x={menu.x} y={menu.y} onClose={() => setMenu(null)} />
      )}
    </MessagePrimitive.Root>
  );
}

function Composer({ hasKey }: { hasKey: boolean }) {
  const aui = useAui();
  const running = useAuiState((state) => state.thread.isRunning);
  return (
    <div className="composer-dock">
      <ComposerPrimitive.Root className="composer">
        <ComposerPrimitive.Input
          className="composer-input"
          placeholder={hasKey ? "给 AndAgent 发消息…" : "先在设置中填入 API Key…"}
          minRows={1}
          maxRows={6}
          autoFocus
          submitMode="enter"
        />
        {running ? (
          <button
            type="button"
            className="send-btn stop"
            onClick={() => aui.thread.cancelRun()}
            aria-label="停止生成"
          >
            <span className="stop-square" />
          </button>
        ) : (
          <ComposerPrimitive.Send className="send-btn" aria-label="发送">
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
              <path
                d="M4 12l15-8-4.5 8L19 20 4 12z"
                fill="currentColor"
              />
            </svg>
          </ComposerPrimitive.Send>
        )}
      </ComposerPrimitive.Root>
      <div className="composer-hint">
        Enter 发送 · Shift+Enter 换行 · AndAgent 可能会调用设备工具，请注意核对结果
      </div>
    </div>
  );
}

export default function App() {
  const [settings, setSettings] = useState<Settings>(() => loadSettings());
  const [showSettings, setShowSettings] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const version = useRef<string>("…").current;
  const engineRef = useRef<AgentEngine | null>(null);
  if (!engineRef.current) engineRef.current = new AgentEngine(settings);
  const engine = engineRef.current;

  const snapshot = useSyncExternalStore(engine.subscribe, engine.getSnapshot, engine.getSnapshot);
  const [appVersion, setAppVersion] = useState(version);
  useEffect(() => {
    setAppVersion(getAppVersion());
  }, []);

  useEffect(() => {
    installLLMFetch(settings.baseUrl);
    engine.setSettings(settings);
  }, [engine, settings]);

  const runtime = useExternalStoreRuntime<ThreadMessageLike>({
    messages: snapshot.messages,
    convertMessage: (message) => message,
    isRunning: snapshot.running,
    onNew: async (message) => {
      const text = message.content
        .filter((part): part is { type: "text"; text: string } => part.type === "text")
        .map((part) => part.text)
        .join("\n");
      await engine.send(text);
    },
    onCancel: async () => {
      engine.stop();
    },
  });

  const saveSettings = (next: Settings) => {
    setSettings(next);
    engine.setSettings(next);
    installLLMFetch(next.baseUrl);
  };

  const hasMessages = snapshot.messages.length > 0;

  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <div className="app">
        <header className="app-head">
          <div className="head-left">
            <button
              type="button"
              className="icon-btn"
              title="会话列表"
              aria-label="打开会话列表"
              onClick={() => setDrawerOpen(true)}
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  d="M4 6h16M4 12h16M4 18h16"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
            <div className="brand">
              <div className="brand-mark">A</div>
              <div className="brand-text">
                <strong>AndAgent</strong>
                <span>{hasBridge() ? `v${appVersion}` : `浏览器预览 v${appVersion}`}</span>
              </div>
            </div>
          </div>
          <div className="head-actions">
            <span className="model-chip" title={settings.baseUrl}>
              {settings.model}
            </span>
            <button
              type="button"
              className="icon-btn"
              title="新对话"
              aria-label="新对话"
              disabled={!hasMessages || snapshot.running}
              onClick={() => engine.newSession()}
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  d="M12 5v14M5 12h14"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
            <button
              type="button"
              className="icon-btn"
              title="设置"
              aria-label="设置"
              onClick={() => setShowSettings(true)}
            >
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  d="M12 15.2a3.2 3.2 0 100-6.4 3.2 3.2 0 000 6.4z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  fill="none"
                />
                <path
                  d="M19.4 13a7.8 7.8 0 000-2l2-1.5-2-3.4-2.3 1a7.6 7.6 0 00-1.7-1L15 3.6h-4l-.4 2.5a7.6 7.6 0 00-1.7 1l-2.3-1-2 3.4L6.6 11a7.8 7.8 0 000 2l-2 1.5 2 3.4 2.3-1a7.6 7.6 0 001.7 1l.4 2.5h4l.4-2.5a7.6 7.6 0 001.7-1l2.3 1 2-3.4-2-1.5z"
                  stroke="currentColor"
                  strokeWidth="1.4"
                  fill="none"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          </div>
        </header>

        {!settings.apiKey && (
          <div className="banner">
            <span>还没有配置 API Key，模型请求会失败。</span>
            <button type="button" onClick={() => setShowSettings(true)}>
              去设置
            </button>
          </div>
        )}

        {snapshot.error && (
          <div className="error-bar" role="alert">
            <strong>请求失败</strong>
            <span>{snapshot.error}</span>
          </div>
        )}

        <ThreadPrimitive.Root className="thread">
          <ThreadPrimitive.Viewport className="viewport" autoScroll>
            <div className="thread-inner">
              <ThreadPrimitive.Empty>
                <div className="empty">
                  <div className="empty-mark">A</div>
                  <h1>今天想让我做什么？</h1>
                  <p>我可以读取设备状态、分析存储与相册，并把结果整理成结论。</p>
                  <div className="empty-hints">
                    <span>“看看手机存储都被什么占满了”</span>
                    <span>“相册里截图占多少空间？”</span>
                    <span>“现在几点了？”</span>
                  </div>
                </div>
              </ThreadPrimitive.Empty>
              <ThreadPrimitive.Messages
                components={{
                  UserMessage,
                  AssistantMessage,
                }}
              />
            </div>
          </ThreadPrimitive.Viewport>
        </ThreadPrimitive.Root>

        <Composer hasKey={!!settings.apiKey} />

        {showSettings && (
          <SettingsPanel
            settings={settings}
            onClose={() => setShowSettings(false)}
            onSave={saveSettings}
          />
        )}

        <Drawer
          open={drawerOpen}
          sessions={snapshot.sessions}
          currentId={snapshot.sessionId}
          version={appVersion}
          onClose={() => setDrawerOpen(false)}
          onNew={() => {
            engine.newSession();
            setDrawerOpen(false);
          }}
          onSwitch={(id) => engine.switchSession(id)}
          onDelete={(id) => engine.deleteSession(id)}
        />
      </div>
    </AssistantRuntimeProvider>
  );
}
