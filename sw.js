// Claude Copilot service worker.
// Purpose: make the app installable and let the shell open with a weak signal.
// It never touches calls to api.anthropic.com — those always go straight to the network.

const CACHE = 'copilot-v2';   // bump this when you change icons or the manifest
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);

  // Only handle this site's own GET requests. API calls, fonts, etc. pass through untouched.
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;

  // The page itself: network first, so a push to GitHub shows up on next launch;
  // fall back to the cached copy when there's no signal.
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put('./index.html', copy));
          return res;
        })
        .catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Icons and manifest: cache first.
  e.respondWith(caches.match(req).then((hit) => hit || fetch(req)));
});
