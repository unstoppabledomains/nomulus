#!/usr/bin/env node
// Copyright 2026 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * Authenticated reverse proxy for local console development against alpha.
 *
 * Reads stored OAuth credentials, auto-refreshes ID tokens, and proxies
 * /console-api requests to https://console.dnex-alpha.com with proper
 * IAP authentication headers.
 *
 * Usage: node ud-local-dev/proxy.mjs [--port 9000]
 *
 * Requires credentials from: node ud-local-dev/auth.mjs
 */

import http from "node:http";
import https from "node:https";
import fs from "node:fs";
import path from "node:path";

const CREDS_FILE = path.join(
  process.env.HOME,
  ".config",
  "nomulus-local-dev",
  "credentials.json"
);

const DEFAULT_PORT = 9000;
const TOKEN_REFRESH_MARGIN_MS = 120_000; // refresh 2 min before expiry

// Parse CLI args
const port =
  parseInt(process.argv[process.argv.indexOf("--port") + 1]) || DEFAULT_PORT;

// Token cache
let cachedToken = null;
let tokenExpiry = 0;

function loadCredentials() {
  if (!fs.existsSync(CREDS_FILE)) {
    console.error(
      `No credentials found at ${CREDS_FILE}\n` +
        "Run: npm run auth:alpha"
    );
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(CREDS_FILE, "utf-8"));
}

async function refreshIdToken(creds) {
  const now = Date.now();
  if (cachedToken && now < tokenExpiry - TOKEN_REFRESH_MARGIN_MS) {
    return cachedToken;
  }

  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: creds.client_id,
      client_secret: creds.client_secret,
      refresh_token: creds.refresh_token,
      audience: creds.iap_client_id,
      grant_type: "refresh_token",
    }),
  });

  const data = await resp.json();
  if (data.error) {
    if (data.error === "invalid_grant") {
      console.error(
        "Refresh token is invalid or expired. Re-run: npm run auth:alpha"
      );
      process.exit(1);
    }
    throw new Error(`Token refresh failed: ${data.error} - ${data.error_description || ""}`);
  }

  if (!data.id_token) {
    throw new Error(
      "No id_token in response. Got keys: " + Object.keys(data).join(", ")
    );
  }

  cachedToken = data.id_token;
  // ID tokens are valid for 1 hour
  tokenExpiry = now + 3600_000;

  // Decode and log basic info (for debugging)
  try {
    const payload = JSON.parse(
      Buffer.from(data.id_token.split(".")[1], "base64url").toString()
    );
    console.log(
      `[proxy] Token refreshed for ${payload.email}, expires ${new Date(payload.exp * 1000).toLocaleTimeString()}`
    );
  } catch {
    console.log("[proxy] Token refreshed");
  }

  return cachedToken;
}

function proxyRequest(req, res, targetUrl, token) {
  const target = new URL(targetUrl);

  const headers = { ...req.headers };

  // Replace host with target host
  headers.host = target.hostname;
  // Inject IAP auth token
  headers.authorization = `Bearer ${token}`;
  // Remove origin header to avoid CORS issues on the backend
  delete headers.origin;

  const options = {
    hostname: target.hostname,
    port: 443,
    path: req.url,
    method: req.method,
    headers,
  };

  const proxyReq = https.request(options, (proxyRes) => {
    // Rewrite cookies for localhost
    const setCookies = proxyRes.headers["set-cookie"];
    if (setCookies) {
      proxyRes.headers["set-cookie"] = setCookies.map((cookie) =>
        cookie
          // Remove Secure flag (localhost is HTTP)
          .replace(/;\s*Secure/gi, "")
          // Remove domain restriction
          .replace(/;\s*Domain=[^;]*/gi, "")
          // Relax SameSite for cross-origin proxy
          .replace(/;\s*SameSite=[^;]*/gi, "; SameSite=Lax")
      );
    }

    res.writeHead(proxyRes.statusCode, proxyRes.headers);
    proxyRes.pipe(res);
  });

  proxyReq.on("error", (err) => {
    console.error(`[proxy] Request error: ${err.message}`);
    if (!res.headersSent) {
      res.writeHead(502);
      res.end(`Proxy error: ${err.message}`);
    }
  });

  req.pipe(proxyReq);
}

async function main() {
  const creds = loadCredentials();
  const target = creds.target || "https://console.dnex-alpha.com";

  // Warm up the token cache
  console.log("[proxy] Fetching initial ID token...");
  await refreshIdToken(creds);

  const server = http.createServer(async (req, res) => {
    try {
      const token = await refreshIdToken(creds);
      proxyRequest(req, res, target, token);
    } catch (err) {
      console.error(`[proxy] Error: ${err.message}`);
      if (!res.headersSent) {
        res.writeHead(500);
        res.end(`Proxy error: ${err.message}`);
      }
    }
  });

  server.listen(port, () => {
    console.log(`[proxy] Authenticated proxy to ${target}`);
    console.log(`[proxy] Listening on http://localhost:${port}`);
    console.log(`[proxy] Token auto-refreshes before expiry`);
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
