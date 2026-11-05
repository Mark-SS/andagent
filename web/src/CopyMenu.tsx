import { useCallback, useEffect, useRef, useState } from "react";
import { useI18n } from "./i18n";
import { createPortal } from "react-dom";

/** Clipboard write with a legacy fallback for WebViews without async clipboard. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to execCommand */
  }
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
}

/**
 * Long-press (or right-click) detection for message bubbles. Returns handlers
 * to spread on the message root; a short drag cancels the gesture so scrolling
 * does not pop the menu.
 */
export function useLongPress(onLongPress: (x: number, y: number) => void, ms = 500) {
  const timer = useRef<number | null>(null);
  const origin = useRef({ x: 0, y: 0 });

  const clear = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  useEffect(() => clear, [clear]);

  return {
    onPointerDown: (event: React.PointerEvent) => {
      if (event.pointerType === "mouse" && event.button !== 0) return;
      origin.current = { x: event.clientX, y: event.clientY };
      clear();
      const { clientX, clientY } = event;
      timer.current = window.setTimeout(() => {
        timer.current = null;
        onLongPress(clientX, clientY);
      }, ms);
    },
    onPointerUp: clear,
    onPointerCancel: clear,
    onPointerLeave: clear,
    onPointerMove: (event: React.PointerEvent) => {
      if (
        Math.abs(event.clientX - origin.current.x) > 10 ||
        Math.abs(event.clientY - origin.current.y) > 10
      ) {
        clear();
      }
    },
    onContextMenu: (event: React.MouseEvent) => {
      event.preventDefault();
      onLongPress(event.clientX, event.clientY);
    },
  };
}

interface CopyMenuProps {
  text: string;
  x: number;
  y: number;
  onClose: () => void;
}

/** One-action context menu ("复制全文") anchored at the long-press point. */
export function CopyMenu({ text, x, y, onClose }: CopyMenuProps) {
  const { t } = useI18n();
  const [state, setState] = useState<"idle" | "done" | "failed">("idle");
  const left = Math.max(8, Math.min(x, window.innerWidth - 132));
  const top = Math.max(8, Math.min(y, window.innerHeight - 54));

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const copy = async () => {
    const ok = await copyText(text);
    setState(ok ? "done" : "failed");
    window.setTimeout(onClose, 700);
  };

  return createPortal(
    <div className="copy-backdrop" onPointerDown={onClose}>
      <div
        className="copy-menu"
        style={{ left, top }}
        onPointerDown={(event) => event.stopPropagation()}
        role="menu"
      >
        <button type="button" className="copy-menu-item" onClick={copy} role="menuitem">
          <span className="copy-menu-icon" aria-hidden="true">
            ⧉
          </span>
          {state === "idle" ? t("copyAll") : state === "done" ? t("copied") : t("copyFailed")}
        </button>
      </div>
    </div>,
    document.body,
  );
}
