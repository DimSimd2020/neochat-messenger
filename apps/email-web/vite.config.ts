import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const apiOrigin = env.VITE_NEOCHAT_API_ORIGIN || "https://api.neochat.example";
  const parsed = new URL(apiOrigin);
  if (parsed.origin !== apiOrigin || (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && parsed.hostname === "localhost"))) {
    throw new Error("VITE_NEOCHAT_API_ORIGIN must be an HTTPS origin (HTTP is allowed only on localhost)");
  }

  return {
    define: {
      __NEOCHAT_API_ORIGIN__: JSON.stringify(apiOrigin)
    },
    plugins: [{
      name: "neochat-email-csp",
      transformIndexHtml(html: string) {
        return html.replaceAll("__NEOCHAT_API_ORIGIN__", apiOrigin);
      }
    }]
  };
});
