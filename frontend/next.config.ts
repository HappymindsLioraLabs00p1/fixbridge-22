import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emit a minimal standalone server for small Docker images.
  output: "standalone",
  // Proxy API calls to the backend so the browser talks same-origin (no CORS, works behind a tunnel).
  async rewrites() {
    const backend = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};

export default nextConfig;
