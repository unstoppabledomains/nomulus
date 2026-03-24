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
