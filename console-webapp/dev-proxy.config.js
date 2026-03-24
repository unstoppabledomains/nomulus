// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

/** @type {import('@angular/build').DevServerProxyConfig} */
module.exports = {
  '/console-api': {
    target: 'http://localhost:8080',
    secure: false,
    logLevel: 'debug',
    changeOrigin: true,
    onProxyRes(proxyRes) {
      // Strip 'Secure' flag from Set-Cookie so XSRF works over HTTP in local dev
      const setCookie = proxyRes.headers['set-cookie'];
      if (setCookie) {
        proxyRes.headers['set-cookie'] = setCookie.map((cookie) =>
          cookie.replace(/;\s*Secure/gi, '')
        );
      }
    },
  },
};
