/**
 * PNG output for Studio cards. The server renders with headless Chrome when it
 * has one; otherwise the card's HTML is drawn in this browser (HTML → SVG
 * <foreignObject> → canvas → PNG), so thumbnails work with no browser installed
 * on the server.
 */

/** Render a self-contained HTML page (no scripts, no external files) to a PNG blob in the browser. */
export async function htmlToPng(html: string, width: number, height: number): Promise<Blob> {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const xhtml = new XMLSerializer().serializeToString(doc.documentElement);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><foreignObject x="0" y="0" width="${width}" height="${height}">${xhtml}</foreignObject></svg>`;
  const img = new Image();
  img.width = width;
  img.height = height;
  img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  await img.decode();
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas is not available in this browser');
  ctx.drawImage(img, 0, 0, width, height);
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('This browser could not export the image'))), 'image/png'));
}

export function downloadBlob(data: Blob | string, fileName: string): void {
  const url = typeof data === 'string' ? data : URL.createObjectURL(data);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  if (typeof data !== 'string') window.setTimeout(() => URL.revokeObjectURL(url), 4000);
}

export function downloadText(text: string, fileName: string, type = 'text/plain'): void {
  downloadBlob(new Blob([text], { type: `${type};charset=utf-8` }), fileName);
}
