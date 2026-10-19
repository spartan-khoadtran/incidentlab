type Listener = (message: string) => void;

const listeners = new Set<Listener>();

export function onCopied(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  }
}

export async function copyText(text: string): Promise<void> {
  const ok = await writeClipboard(text);
  const short = text.length > 48 ? `${text.slice(0, 45)}...` : text;
  listeners.forEach((l) => l(ok ? `Copied ${short}` : "Copy failed. Select the text instead."));
}
