import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { execTool, openAppSettings } from "./bridge";

type FacingMode = "environment" | "user";
type Phase = "idle" | "live" | "captured";

/**
 * ```camera fence: an in-conversation live viewfinder. The camera is only
 * requested after the user taps 启动相机, one card may hold the stream at a
 * time, and every track is stopped on unmount / page hide / after a shot.
 */

/** Set while a card holds the camera; a new start stops the previous owner. */
let stopActiveCamera: (() => void) | null = null;

function parseOptions(code: string): { facingMode: FacingMode } {
  const text = code.trim();
  if (!text) return { facingMode: "environment" };
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("内容必须是 JSON 对象");
  }
  const facing = (parsed as { facingMode?: unknown }).facingMode;
  return { facingMode: facing === "user" ? "user" : "environment" };
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error("读取照片失败"));
    reader.readAsDataURL(blob);
  });
}

function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export function CameraCard({ code }: { code: string }) {
  const options = useMemo(() => {
    try {
      return { facingMode: parseOptions(code).facingMode, error: null as string | null };
    } catch (e) {
      return {
        facingMode: "environment" as FacingMode,
        error: `相机参数格式错误：${e instanceof Error ? e.message : String(e)}（内容须为 {} 或 {"facingMode":"environment"}）`,
      };
    }
  }, [code]);

  const [phase, setPhase] = useState<Phase>("idle");
  const [facing, setFacing] = useState<FacingMode>(options.facingMode);
  const [error, setError] = useState<string | null>(null);
  const [permDenied, setPermDenied] = useState(false);
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [savePending, setSavePending] = useState(false);
  const [saveResult, setSaveResult] = useState<string | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const mountedRef = useRef(true);
  const phaseRef = useRef<Phase>("idle");
  phaseRef.current = phase;

  const stopTracks = useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  // Attach the stream once the <video> element exists for the live phase
  // (and again when the camera is switched while live).
  useEffect(() => {
    if (phase === "live" && videoRef.current && streamRef.current) {
      videoRef.current.srcObject = streamRef.current;
      void videoRef.current.play().catch(() => undefined);
    }
  }, [phase, facing]);

  // Lifecycle: stop everything on unmount and when the page is hidden.
  useEffect(() => {
    mountedRef.current = true;
    const onVisibility = () => {
      if (document.hidden) {
        stopTracks();
        if (phaseRef.current === "live") setPhase("idle");
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      mountedRef.current = false;
      document.removeEventListener("visibilitychange", onVisibility);
      if (stopActiveCamera === stopTracks) stopActiveCamera = null;
      stopTracks();
    };
  }, [stopTracks]);

  const start = async (want: FacingMode) => {
    setError(null);
    setPermDenied(false);
    setSaveResult(null);
    setDataUrl(null);
    if (!navigator.mediaDevices?.getUserMedia) {
      setPhase("idle");
      setError("当前环境不支持相机取景（getUserMedia 需要 HTTPS 安全上下文）");
      return;
    }
    // One live camera card at a time: evict the previous owner first.
    if (stopActiveCamera && stopActiveCamera !== stopTracks) stopActiveCamera();
    stopActiveCamera = stopTracks;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: want } },
      });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      streamRef.current = stream;
      setFacing(want);
      setPhase("live");
    } catch (e) {
      if (!mountedRef.current) return;
      setPhase("idle");
      const name = (e as { name?: string })?.name ?? "";
      const text = String((e as { message?: string })?.message ?? e);
      if (name === "NotAllowedError" || name === "SecurityError" || /permission|denied/i.test(text)) {
        setPermDenied(true);
        setError("相机权限被拒绝。请允许 AndAgent 使用摄像头后重试（可在系统设置中开启）。");
      } else if (name === "NotFoundError" || name === "OverconstrainedError") {
        setError(want === "user" ? "未找到前置摄像头" : "未找到后置摄像头");
      } else {
        setError(`无法启动相机：${text}`);
      }
    }
  };

  const shoot = async () => {
    const video = videoRef.current;
    if (!video || !streamRef.current) return;
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (!w || !h) {
      setError("摄像头还未就绪，稍候再拍");
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      setError("无法创建画布");
      return;
    }
    setError(null);
    ctx.drawImage(video, 0, 0, w, h);
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", 0.9),
    );
    if (!blob) {
      setError("拍照失败（无法编码 JPEG）");
      return;
    }
    // Release the camera right after the shot; 重拍 re-acquires the stream.
    stopTracks();
    let url: string;
    try {
      url = await blobToDataUrl(blob);
    } catch (e) {
      if (mountedRef.current) setError(e instanceof Error ? e.message : String(e));
      setPhase("idle");
      return;
    }
    if (!mountedRef.current) return;
    setDataUrl(url);
    setPhase("captured");
    setSavePending(true);
    setSaveResult(null);
    const name = `camera_${stamp()}`;
    const result = await execTool("image_save", {
      base64: url.slice(url.indexOf(",") + 1),
      filename: name,
      display_name: `相机照片 ${stamp()}`,
    });
    if (!mountedRef.current) return;
    setSavePending(false);
    setSaveResult(result.result);
  };

  const retake = () => {
    setDataUrl(null);
    setSaveResult(null);
    void start(facing);
  };

  if (options.error) {
    return (
      <div className="camera-card">
        <div className="chart-head">
          <span className="chart-badge">相机</span>
          <span className="chart-error-text">{options.error}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="camera-card">
      <div className="chart-head">
        <span className="chart-badge">相机</span>
        <span className="camera-facing">
          {phase === "live" ? (facing === "environment" ? "实时取景 · 后置" : "实时取景 · 前置") : "实时取景"}
        </span>
      </div>
      {phase === "live" ? (
        <video ref={videoRef} className="camera-video" autoPlay playsInline muted />
      ) : phase === "captured" && dataUrl ? (
        <img className="camera-shot" src={dataUrl} alt="刚拍摄的照片" />
      ) : (
        <div className="camera-idle">{error ? "相机未启动" : "点击「启动相机」开始取景，拍照后自动存入相册"}</div>
      )}
      <div className="camera-actions">
        {phase === "live" ? (
          <>
            <button type="button" className="camera-shoot" onClick={() => void shoot()}>
              拍照
            </button>
            <button
              type="button"
              onClick={() => void start(facing === "environment" ? "user" : "environment")}
            >
              切换{facing === "environment" ? "前置" : "后置"}
            </button>
            <button
              type="button"
              onClick={() => {
                stopTracks();
                setPhase("idle");
              }}
            >
              关闭
            </button>
          </>
        ) : phase === "captured" ? (
          <>
            <button type="button" className="camera-shoot" onClick={retake}>
              重拍
            </button>
          </>
        ) : (
          <button type="button" className="camera-shoot" onClick={() => void start(facing)}>
            启动相机
          </button>
        )}
      </div>
      {savePending && <div className="camera-save">正在保存到相册…</div>}
      {saveResult && (
        <div className={saveResult.startsWith("ERROR:") ? "rich-error camera-result" : "camera-save"}>
          {saveResult}
        </div>
      )}
      {error && (
        <div className="camera-error">
          <span>{error}</span>
          {permDenied && (
            <button type="button" onClick={() => openAppSettings()}>
              去系统设置
            </button>
          )}
        </div>
      )}
    </div>
  );
}
