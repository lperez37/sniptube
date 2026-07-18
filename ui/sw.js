// Bump the version on every deploy that changes the app shell - the activate
// handler drops old caches, so clients pick up new HTML/JS/CSS on next load.
const CACHE_NAME = 'sniptube-v2';

const APP_SHELL = [
  '/',
  '/app.js',
  '/style.css',
  '/logo.svg',
  '/manifest.json',
  '/vendor/alpine.min.js',
  '/vendor/alpine-collapse.min.js',
];

const CDN_ORIGINS = [
  'https://fonts.googleapis.com',
  'https://fonts.gstatic.com',
];

const API_PREFIXES = ['/videos', '/jobs', '/search', '/files', '/docs', '/openapi'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;

  const url = new URL(e.request.url);

  // Network only for API routes - never serve stale search results or job
  // statuses, and never let the cache grow one entry per query string.
  if (url.origin === self.location.origin
      && API_PREFIXES.some((p) => url.pathname.startsWith(p))) return;

  // Network-first for navigations and the app shell so a deploy is picked up
  // on the first load instead of serving a stale HTML/JS mix.
  const isShell = url.origin === self.location.origin
    && (e.request.mode === 'navigate' || APP_SHELL.includes(url.pathname));
  if (isShell) {
    e.respondWith(
      fetch(e.request).then((res) => {
        if (res.ok) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((c) => c.put(e.request, clone));
        }
        return res;
      }).catch(() => caches.match(e.request))
    );
    return;
  }

  // Cache-first for CDN resources (fonts) - only cache good responses so a
  // transient CDN error is never pinned in the cache.
  if (CDN_ORIGINS.some((o) => e.request.url.startsWith(o))) {
    e.respondWith(
      caches.match(e.request).then((cached) =>
        cached || fetch(e.request).then((res) => {
          if (res.ok) {
            const clone = res.clone();
            caches.open(CACHE_NAME).then((c) => c.put(e.request, clone));
          }
          return res;
        })
      )
    );
    return;
  }

  // Stale-while-revalidate for remaining static assets (icons etc.)
  e.respondWith(
    caches.match(e.request).then((cached) => {
      const fetchPromise = fetch(e.request).then((res) => {
        if (res.ok) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((c) => c.put(e.request, clone));
        }
        return res;
      }).catch(() => cached);
      return cached || fetchPromise;
    })
  );
});
