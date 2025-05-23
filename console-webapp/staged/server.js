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

const express = require('express');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 8080;

// Determine which environment's directory to use
const environment = process.env.GAE_ENV === 'standard' ? 'console-alpha' : 'dist';

function setCustomCacheControl(res, path) {
  // Custom Cache-Control for HTML files - we don't want to cache them
  if (express.static.mime.lookup(path) === 'text/html') {
    res.setHeader('Cache-Control', 'public, max-age=0');
  }
}

// Serve static files at root
app.use(express.static(environment, {
  etag: false,
  lastModified: false,
  maxAge: '1d',
  setHeaders: setCustomCacheControl
}));

// Serve static files at /console
app.use('/console', express.static(environment, {
  etag: false,
  lastModified: false,
  maxAge: '1d',
  setHeaders: setCustomCacheControl
}));

// For SPA routing - redirect all other routes to index.html
app.get('*', (req, res) => {
  const indexPath = path.join(environment, 'index.html');
  if (fs.existsSync(indexPath)) {
    res.sendFile(path.resolve(indexPath));
  } else {
    res.status(404).send(`Cannot find ${indexPath}`);
  }
});

app.listen(PORT);

