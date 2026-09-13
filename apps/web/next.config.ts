import path from "node:path";
import type { NextConfig } from "next";

const config: NextConfig = {
  reactStrictMode: true,
  // The contracts package is TypeScript source, not a build artefact. Next
  // compiles it alongside the app so there is no build step to forget.
  transpilePackages: ["@opsatlas/contracts"],
  typedRoutes: true,
  // This repository sits inside a directory that has its own lockfile, so Next
  // otherwise infers the wrong workspace root and traces the wrong files.
  outputFileTracingRoot: path.join(import.meta.dirname, "../.."),
};

export default config;
