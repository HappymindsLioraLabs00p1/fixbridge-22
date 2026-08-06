import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Standalone output is only for the Docker image. Netlify's Next.js runtime manages its own
  // output mode, and forcing "standalone" there breaks the build — so opt in explicitly.
  ...(process.env.DOCKER_BUILD === "1" ? { output: "standalone" as const } : {}),

  // Proxy API calls to the backend so the browser always talks same-origin (no CORS, no
  // mixed-content). Set BACKEND_ORIGIN in the host's environment (Netlify → Site settings → Env).
  async rewrites() {
    const backend = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};

export default nextConfig;
