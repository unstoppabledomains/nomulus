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
 * One-time OAuth2 authorization flow for local console development.
 *
 * Opens a browser for Google login, receives the callback, exchanges the
 * authorization code for tokens, and saves the refresh token locally.
 *
 * Usage: node ud-local-dev/auth.mjs
 *
 * Credentials are stored at ~/.config/nomulus-local-dev/credentials.json
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { URL } from "node:url";

// OAuth client in ud-registry-alpha-gke (Desktop type)
const CLIENT_ID =
  "266829374217-4rgjn4b6l514le8h5ar50ft3d60pgnkb.apps.googleusercontent.com";

// IAP client in ud-registry-alpha-gke — used as the token audience
const IAP_CLIENT_ID =
  "266829374217-u7sg2ohqnlo0je4q67o8t5itv7q5v6cj.apps.googleusercontent.com";

const SCOPES = "openid email";
const REDIRECT_PORT = 8085;
const REDIRECT_URI = `http://localhost:${REDIRECT_PORT}`;

const CREDS_DIR = path.join(
  process.env.HOME,
  ".config",
  "nomulus-local-dev"
);
const CREDS_FILE = path.join(CREDS_DIR, "credentials.json");

async function getClientSecret() {
  try {
    return execSync(
      "gcloud secrets versions access latest " +
        "--secret=oauth-client-secret-nomulus-alpha-desktop " +
        "--project=ud-registry-alpha-gke",
      { encoding: "utf-8" }
    ).trim();
  } catch {
    console.error(
      "Failed to fetch client secret from Secret Manager.\n" +
        "Make sure you have access to ud-registry-alpha-gke secrets."
    );
    process.exit(1);
  }
}

function buildAuthUrl(clientSecret) {
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: SCOPES,
    access_type: "offline",
    prompt: "consent", // Force consent to always get a refresh_token
  });
  return `https://accounts.google.com/o/oauth2/v2/auth?${params}`;
}

async function exchangeCode(code, clientSecret) {
  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: CLIENT_ID,
      client_secret: clientSecret,
      redirect_uri: REDIRECT_URI,
      grant_type: "authorization_code",
    }),
  });
  return resp.json();
}

function saveCredentials(data, clientSecret) {
  fs.mkdirSync(CREDS_DIR, { recursive: true });
  const creds = {
    client_id: CLIENT_ID,
    client_secret: clientSecret,
    refresh_token: data.refresh_token,
    iap_client_id: IAP_CLIENT_ID,
    target: "https://console.dnex-alpha.com",
    created: new Date().toISOString(),
  };
  fs.writeFileSync(CREDS_FILE, JSON.stringify(creds, null, 2) + "\n", {
    mode: 0o600,
  });
  console.log(`\nCredentials saved to ${CREDS_FILE}`);
}

async function main() {
  console.log("Fetching OAuth client secret from Secret Manager...");
  const clientSecret = await getClientSecret();

  const authUrl = buildAuthUrl(clientSecret);

  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      const url = new URL(req.url, `http://localhost:${REDIRECT_PORT}`);

      if (url.pathname !== "/" || !url.searchParams.has("code")) {
        res.writeHead(404);
        res.end("Not found");
        return;
      }

      const code = url.searchParams.get("code");
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end(
        "<html><body><h2>Authentication successful!</h2>" +
          "<p>You can close this tab and return to the terminal.</p>" +
          "</body></html>"
      );

      console.log("\nReceived authorization code, exchanging for tokens...");
      const tokenData = await exchangeCode(code, clientSecret);

      if (tokenData.error) {
        console.error(`Token exchange failed: ${tokenData.error}`);
        console.error(tokenData.error_description || "");
        server.close();
        process.exit(1);
      }

      if (!tokenData.refresh_token) {
        console.error(
          "No refresh_token received. Try revoking app access at " +
            "https://myaccount.google.com/permissions and running again."
        );
        server.close();
        process.exit(1);
      }

      saveCredentials(tokenData, clientSecret);
      console.log("You can now run: npm run start:alpha");
      server.close();
      resolve();
    });

    server.listen(REDIRECT_PORT, () => {
      console.log(`\nOpening browser for Google login...`);
      console.log(`(listening on port ${REDIRECT_PORT} for callback)\n`);
      try {
        execSync(`open "${authUrl}"`);
      } catch {
        console.log("Could not open browser. Open this URL manually:");
        console.log(authUrl);
      }
    });
  });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
