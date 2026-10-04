const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

function fixture() {
  const window = { scrollY: 0, location: { hash: '#/' }, scrollTo({ top }) { this.scrollY = top; } };
  const history = { replaceState(_s, _t, hash) { window.location.hash = hash; } };
  const context = vm.createContext({ window, history, URL, URLSearchParams, AbortController,
    setTimeout, clearTimeout, requestAnimationFrame: cb => cb(), fetch: async () => ({ ok: true, json: async () => [] }) });
  vm.runInContext(readFileSync(join(__dirname, '../app.js'), 'utf8'), context);
  const app = context.app();
  app.$nextTick = cb => cb();
  app._setupLoadMore = () => {};
  app.toast = () => {};
  return { app, window, context };
}

test('background refresh preserves the loaded library page count', async () => {
  const { app, context } = fixture();
  app.videosDisplayCount = 72;
  context.fetch = async () => ({ ok: true, json: async () => Array.from({length:100}, (_,id) => ({id})) });
  await app.loadVideos({ background:true });
  assert.equal(app.displayedVideos.length, 72);
});

test('list and search restore their independent scroll positions', () => {
  const { app, window } = fixture();
  window.scrollY = 2500;
  app._saveScroll();
  app.page = 'download';
  app._resultsQueryKey = 'cats';
  window.scrollY = 1500;
  app._saveScroll();
  app.page = 'detail';
  window.scrollY = 0;
  app._saveScroll();
  app.page = 'list'; app._restoreScroll();
  assert.equal(window.scrollY, 2500);
  app.page = 'download'; app._restoreScroll();
  assert.equal(window.scrollY, 1500);
});

test('same-query refresh retains cards during loading and failure', async () => {
  const { app, context } = fixture();
  app.page = 'download'; app.downloadUrl = 'cats'; app.searchMode = true;
  context.fetch = async () => ({ok:true,json:async()=>({results:[{youtube_id:'cat'}],page:1,has_more:false})});
  await app.performSearch();
  let reject;
  context.fetch = () => new Promise((_, failure) => { reject = failure; });
  const refreshing = app.performSearch();
  assert.equal(app.searchResults.length, 1);
  assert.equal(app.searchState, 'loading');
  reject(new Error('offline'));
  await refreshing;
  assert.equal(app.searchResults.length, 1);
  assert.equal(app.searchState, 'error');
});

test('late search completion cannot replace a detail route', async () => {
  const { app, window, context } = fixture();
  app.page = 'download'; app.downloadUrl = 'cats'; app.searchMode = true;
  let resolve;
  context.fetch = () => new Promise(done => { resolve=done; });
  const search=app.performSearch();
  app.page='detail'; window.location.hash='#/video/123456abcdef';
  resolve({ok:true,json:async()=>({results:[],page:1,has_more:false})});
  await search;
  assert.equal(window.location.hash,'#/video/123456abcdef');
});

test('pull only refreshes from the top after crossing the threshold', () => {
  const { app, window } = fixture();
  let refreshes=0; app.refreshCurrent=()=>refreshes++;
  const target={closest:()=>false};
  const touch=(y,x=20)=>({target,touches:[{clientX:x,clientY:y}],cancelable:true,preventDefault(){}});
  window.scrollY=30; app.startPull(touch(10)); app.movePull(touch(200)); app.endPull();
  assert.equal(refreshes,0);
  window.scrollY=0; app.startPull(touch(10)); app.movePull(touch(80)); app.endPull();
  assert.equal(refreshes,0);
  app.startPull(touch(10)); app.movePull(touch(180)); app.endPull();
  assert.equal(refreshes,1);
  app.startPull(touch(10)); app.movePull(touch(20,200)); app.endPull();
  assert.equal(refreshes,1);
});
