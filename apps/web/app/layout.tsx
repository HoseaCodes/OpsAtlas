import type { Metadata } from "next";
import { Archivo, IBM_Plex_Mono } from "next/font/google";
import type { ReactNode } from "react";
import { AppShell } from "@/components/AppShell";
import "./globals.css";

/**
 * Self-hosted rather than loaded from a CDN at runtime: the console should have
 * no third-party request on its critical path, least of all during an incident.
 */
const archivo = Archivo({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-archivo",
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-plex-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "OpsAtlas",
  description: "Service operations control plane",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // Dark by default, set on the server so there is no flash before hydration.
    <html lang="en" data-theme="dark" className={`${archivo.variable} ${plexMono.variable}`}>
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
