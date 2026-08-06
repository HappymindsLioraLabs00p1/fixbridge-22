import { useAuth } from "@/store/auth";

// Default to SAME-ORIGIN ("") so the browser calls "/api/*", which Next proxies to the backend
// (see next.config rewrites). Works locally and behind an https tunnel/deploy with no mixed-content
// or CORS. Set NEXT_PUBLIC_API_URL only to point at a different backend origin explicitly.
const API_URL = process.env.NEXT_PUBLIC_API_URL || "";

export class ApiError extends Error {
  status: number;
  details: string[];
  constructor(status: number, message: string, details: string[] = []) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

async function parseError(res: Response): Promise<ApiError> {
  let message = res.statusText || "Request failed";
  let details: string[] = [];
  try {
    const body = await res.json();
    if (body?.message) message = body.message;
    if (Array.isArray(body?.details)) details = body.details;
  } catch {
    /* non-JSON error body — keep the generic message, never surface raw text */
  }
  return new ApiError(res.status, message, details);
}

async function tryRefresh(): Promise<boolean> {
  const { refreshToken, setTokens, clear } = useAuth.getState();
  if (!refreshToken) return false;
  const res = await fetch(`${API_URL}/api/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  if (!res.ok) {
    clear();
    return false;
  }
  const data = await res.json();
  setTokens(data.accessToken, data.refreshToken);
  return true;
}

/** Typed fetch wrapper: attaches the JWT, refreshes once on 401, surfaces safe ApiError shapes. */
export async function apiFetch<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const { accessToken } = useAuth.getState();
  const headers = new Headers(options.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  const res = await fetch(`${API_URL}${path}`, { ...options, headers });

  if (res.status === 401 && retry && (await tryRefresh())) {
    return apiFetch<T>(path, options, false);
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;

  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string) => apiFetch<T>(path, { method: "GET" }),
  post: <T>(path: string, body?: unknown) =>
    apiFetch<T>(path, { method: "POST", body: body ? JSON.stringify(body) : undefined }),
};

/**
 * Two-step upload: ask the API for a signed target, then PUT the file straight to storage.
 * Returns the object key to attach to a report. The raw bytes never pass through our JSON API.
 */
export async function uploadFile(file: File): Promise<string> {
  const target = await api.post<{ objectKey: string; uploadUrl: string }>("/api/media/upload-url", {
    contentType: file.type || "application/octet-stream",
  });
  const res = await fetch(target.uploadUrl, {
    method: "PUT",
    headers: { "Content-Type": file.type || "application/octet-stream" },
    body: file,
  });
  if (!res.ok) throw new ApiError(res.status, "Upload failed");
  return target.objectKey;
}
