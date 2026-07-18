const API = '';  // same origin

function app() {
  return {
    // State
    page: 'list',
    videos: [],
    currentVideo: null,
    subtitleTracks: [],
    derivatives: [],
    activeJobs: [],
    toasts: [],
    loadingVideos: false,
    videosPerPage: 24,
    videosDisplayCount: 24,
    _loadMoreObserver: null,

    // Download & Search
    downloadUrl: '',
    downloading: false,
    currentDownload: null,
    searchResults: [],
    searchFilters: { duration: 'any', sort_by: 'relevance' },
    searchMode: false,
    searchState: 'idle',   // idle | loading | loadingMore | error
    searchError: '',
    searchAttempted: false,
    searchPage: 1,
    searchHasMore: false,
    searchPageSize: 12,
    searchMaxPages: 5,     // mirrors the API's page cap
    _searchSeq: 0,
    _searchAbort: null,
    _searchDebounce: null,

    get searching() {
      return this.searchState === 'loading' || this.searchState === 'loadingMore';
    },

    // Clip/GIF controls
    clipStartDisplay: '0:00',
    clipEndDisplay: '0:10',
    clipMode: 'copy',
    gifQuality: 'high',
    gifWidth: 480,
    gifFps: 10,

    // Crop
    cropEnabled: false,
    cropPct: 80,

    // Subtitles
    fetchingSubs: false,

    // Transcript
    transcriptLang: '',
    transcriptText: '',
    transcriptExpanded: false,
    transcriptLoading: false,

    // Detail UI
    activeAction: null,
    derivativesPanelOpen: false,

    // Audio
    audioFullVideo: true,
    extractingAudio: false,

    // Resolution downloads
    availableHeights: [],
    resolutionBusy: {},
    probingHeights: false,

    // Prune
    pruning: false,
    showPruneModal: false,

    // Delete confirmation
    showDeleteModal: false,
    showDeleteDerivativeModal: false,
    derivativeToDelete: null,

    // Polling: bumping the generation invalidates every page-scoped poll,
    // including ones whose fetch is currently in flight.
    _pollGeneration: 0,

    async init() {
      if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
      window.addEventListener('hashchange', () => this._handleRoute());
      this.$watch('derivativesPanelOpen', (open) => {
        document.body.classList.toggle('drawer-open', !!open);
      });

      const hash = window.location.hash;
      const videoMatch = hash.match(/^#\/video\/([a-f0-9]+)$/);
      if (videoMatch) {
        await this.viewVideo(videoMatch[1]);
      } else {
        await this.loadVideos();
        if (hash.startsWith('#/download')) {
          this.page = 'download';
          this._restoreSearchFromHash(hash);
        }
      }
    },

    // --- Routing ---
    // Search state lives in the hash (#/download?q=...&duration=...&sort=...),
    // so reload, back-from-detail, and deep links all restore the search.
    _handleRoute() {
      const hash = window.location.hash;
      const videoMatch = hash.match(/^#\/video\/([a-f0-9]+)$/);
      if (videoMatch) {
        const videoId = videoMatch[1];
        if (this.page === 'detail' && this.currentVideo?.id === videoId) return;
        this._clearPolling();
        this.viewVideo(videoId);
      } else if (hash.startsWith('#/download')) {
        this._clearPolling();
        this.page = 'download';
        this._restoreSearchFromHash(hash);
      } else {
        this._clearPolling();
        this._loadMoreObserver?.disconnect();
        this.page = 'list';
        this.loadVideos();
      }
    },

    _restoreSearchFromHash(hash) {
      const qs = hash.split('?')[1];
      if (!qs) return;
      const p = new URLSearchParams(qs);
      const q = (p.get('q') || '').trim();
      if (!q) return;
      const duration = p.get('duration') || 'any';
      const sort = p.get('sort') || 'relevance';
      const changed = q !== this.downloadUrl.trim()
        || duration !== this.searchFilters.duration
        || sort !== this.searchFilters.sort_by;
      if (changed || !this.searchResults.length) {
        this.downloadUrl = q;
        this.searchMode = true;
        this.searchFilters.duration = duration;
        this.searchFilters.sort_by = sort;
        this.performSearch(1);
      }
    },

    _syncSearchHash() {
      const q = this.downloadUrl.trim();
      if (!q || !this.searchMode) return;
      const params = new URLSearchParams({ q });
      if (this.searchFilters.duration !== 'any') params.set('duration', this.searchFilters.duration);
      if (this.searchFilters.sort_by !== 'relevance') params.set('sort', this.searchFilters.sort_by);
      // replaceState does not fire hashchange, so this never re-triggers a search.
      history.replaceState(null, '', `#/download?${params}`);
    },

    // --- Navigation ---
    navigate(page) {
      if (page === 'list') {
        window.location.hash = '#/';
      } else if (page === 'download') {
        window.location.hash = '#/download';
      }
    },

    navigateBack() {
      if (window.history.length > 1) {
        window.history.back();
      } else {
        window.location.hash = '#/';
      }
    },

    // --- Videos ---
    async loadVideos() {
      this.loadingVideos = true;
      try {
        const res = await fetch(`${API}/videos`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        this.videos = await res.json();
        this.videosDisplayCount = this.videosPerPage;
      } catch (e) {
        this.toast('Failed to load videos', 'error');
      }
      this.loadingVideos = false;
      this.$nextTick(() => this._setupLoadMore());
    },

    get displayedVideos() {
      return this.videos.slice(0, this.videosDisplayCount);
    },

    get hasMoreVideos() {
      return this.videosDisplayCount < this.videos.length;
    },

    loadMoreVideos() {
      this.videosDisplayCount = Math.min(
        this.videosDisplayCount + this.videosPerPage,
        this.videos.length
      );
      // Re-observe sentinel if still more
      this.$nextTick(() => this._setupLoadMore());
    },

    _setupLoadMore() {
      if (this._loadMoreObserver) {
        this._loadMoreObserver.disconnect();
      }
      const sentinel = document.getElementById('load-more-sentinel');
      if (!sentinel || !this.hasMoreVideos) return;
      this._loadMoreObserver = new IntersectionObserver((entries) => {
        if (entries[0].isIntersecting) {
          this.loadMoreVideos();
        }
      }, { rootMargin: '200px' });
      this._loadMoreObserver.observe(sentinel);
    },

    async viewVideo(videoId) {
      this._clearPolling();
      try {
        const [videoRes, subsRes, derivRes] = await Promise.all([
          fetch(`${API}/videos/${videoId}`),
          fetch(`${API}/videos/${videoId}/subtitles`),
          fetch(`${API}/videos/${videoId}/derivatives`),
        ]);
        if (!videoRes.ok || !subsRes.ok || !derivRes.ok) {
          throw new Error('Video not found');
        }
        this.currentVideo = await videoRes.json();
        this.subtitleTracks = await subsRes.json();
        this.derivatives = await derivRes.json();
        this.clipStartDisplay = '0:00';
        this.clipEndDisplay = this.secsToMmss(Math.min(10, this.currentVideo.duration || 10));
        this.activeJobs = [];
        this.showDeleteModal = false;
        this.activeAction = null;
        this.availableHeights = this.currentVideo.available_heights || [];
        this.resolutionBusy = {};
        this.probingHeights = false;
        this.derivativesPanelOpen = false;
        this.transcriptExpanded = false;
        this.transcriptText = '';
        this.transcriptLang = this.subtitleTracks.length ? this.subtitleTracks[0].language : '';
        this.page = 'detail';

        // Update hash for routing (won't re-trigger if already correct)
        if (window.location.hash !== `#/video/${videoId}`) {
          window.location.hash = `#/video/${videoId}`;
        }

        // Reload video element after Alpine renders
        this.$nextTick(() => {
          const player = this.$refs.videoPlayer;
          if (player) {
            player.load();
          }
        });

        // Auto-load transcript
        if (this.transcriptLang) {
          this.loadTranscriptText();
        }
      } catch (e) {
        this.toast('Failed to load video', 'error');
      }
    },

    // --- Download URLs ---
    derivativeDownloadUrl(jobId) {
      if (!this.currentVideo) return '#';
      return `${API}/videos/${this.currentVideo.id}/derivatives/${jobId}/download`;
    },

    // --- Download ---
    async submitDownload() {
      let url = this.downloadUrl.trim();
      if (!url || this.downloading) return;
      if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
      this.downloading = true;
      this.currentDownload = null;
      try {
        const res = await fetch(`${API}/videos`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url }),
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Download failed', 'error');
          this.downloading = false;
          return;
        }
        if (data.status === 'already_exists') {
          this.toast('Video already downloaded', 'info');
          this.downloading = false;
          this.viewVideo(data.video_id);
          return;
        }
        this.currentDownload = { ...data, status: 'queued', progress: 0 };
        // scope 'download': keeps polling across page navigation so the
        // downloading flag can never get stuck on true.
        this.pollJob(data.job_id, (job) => {
          this.currentDownload = { ...this.currentDownload, ...job };
          if (job.status === 'completed' || job.status === 'failed') {
            this.downloading = false;
            if (job.status === 'completed') {
              this.toast('Download complete!', 'success');
              this.loadVideos();
            } else {
              this.toast('Download failed: ' + (job.error || ''), 'error');
            }
          }
        }, { scope: 'download' });
      } catch (e) {
        this.toast('Request failed', 'error');
        this.downloading = false;
      }
    },

    onPaste(event) {
      // Auto-submit on paste only for URLs (not search queries)
      setTimeout(() => {
        if (this.downloadUrl.trim() && !this.downloading && !this.isSearchQuery(this.downloadUrl)) {
          this.submitDownload();
        }
      }, 100);
    },

    isSearchQuery(input) {
      const s = (input || '').trim();
      if (!s) return false;
      if (/\s/.test(s)) return true; // spaces → always a search
      let url;
      try {
        url = new URL(s);
      } catch {
        // Scheme-less URLs like "youtube.com/watch?v=x" parse once prefixed.
        try { url = new URL(`https://${s}`); } catch { return true; }
      }
      if (!['http:', 'https:'].includes(url.protocol)) return true;
      const host = url.hostname.toLowerCase();
      const ytHosts = ['youtube.com', 'youtu.be', 'youtube-nocookie.com'];
      return !ytHosts.some((d) => host === d || host.endsWith(`.${d}`));
    },

    onDownloadInput() {
      this.searchMode = this.isSearchQuery(this.downloadUrl);
      clearTimeout(this._searchDebounce);
      const q = this.downloadUrl.trim();
      if (!q) {
        this._resetSearch();
        return;
      }
      // Live search: debounce keystrokes; Enter still searches immediately.
      if (this.searchMode && q.length >= 3) {
        this._searchDebounce = setTimeout(() => this.performSearch(1), 450);
      }
    },

    submitAction() {
      if (this.isSearchQuery(this.downloadUrl)) {
        this.performSearch(1);
      } else {
        this.submitDownload();
      }
    },

    async performSearch(page = 1) {
      const q = this.downloadUrl.trim();
      if (!q || !this.isSearchQuery(q)) return;
      clearTimeout(this._searchDebounce);
      const requestedPage = Math.max(1, Math.min(this.searchMaxPages, page || 1));

      // Supersede any in-flight search: abort its fetch and invalidate its seq
      // so a slow stale response can never overwrite fresher results.
      const seq = ++this._searchSeq;
      this._searchAbort?.abort();
      const ctrl = new AbortController();
      this._searchAbort = ctrl;

      this.searchState = requestedPage === 1 ? 'loading' : 'loadingMore';
      this.searchError = '';
      try {
        const params = new URLSearchParams({
          q,
          max_results: String(this.searchPageSize),
          page: String(requestedPage),
        });
        if (this.searchFilters.duration !== 'any') params.set('duration', this.searchFilters.duration);
        if (this.searchFilters.sort_by !== 'relevance') params.set('sort_by', this.searchFilters.sort_by);
        const res = await fetch(`${API}/search?${params}`, { signal: ctrl.signal });
        const data = await res.json();
        if (seq !== this._searchSeq) return; // superseded while parsing
        if (!res.ok) {
          this.searchState = 'error';
          this.searchError = data.detail || 'Search failed';
          return;
        }
        if (requestedPage === 1) {
          this.searchResults = data.results;
        } else {
          // The server re-fetches from YouTube when a later page needs more
          // entries than cached, and YouTube's ordering shifts between calls -
          // drop anything we already show so appended pages never duplicate.
          const known = new Set(this.searchResults.map((r) => r.youtube_id));
          this.searchResults = [
            ...this.searchResults,
            ...data.results.filter((r) => !known.has(r.youtube_id)),
          ];
        }
        this.searchPage = data.page || requestedPage;
        this.searchHasMore = !!data.has_more;
        this.searchAttempted = true;
        this.searchState = 'idle';
        this._syncSearchHash();
      } catch (e) {
        if (e.name === 'AbortError' || seq !== this._searchSeq) return;
        this.searchState = 'error';
        this.searchError = 'Search failed - check your connection';
      }
    },

    loadMoreResults() {
      if (this.searchHasMore && !this.searching) {
        this.performSearch(this.searchPage + 1);
      }
    },

    async downloadFromSearch(result) {
      // Already in the library: open its detail page. Back returns to this
      // search because the query lives in the location hash.
      if (result.already_downloaded && result.video_id) {
        this.viewVideo(result.video_id);
        return;
      }
      // Download in place - results stay visible so more can be queued.
      if (result._downloading) return;
      result._downloading = true;
      try {
        const res = await fetch(`${API}/videos`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: result.url }),
        });
        const data = await res.json();
        if (!res.ok) {
          result._downloading = false;
          this.toast(data.detail || 'Download failed', 'error');
          return;
        }
        if (data.status === 'already_exists') {
          result._downloading = false;
          result.already_downloaded = true;
          result.video_id = data.video_id;
          return;
        }
        this.toast('Download queued', 'info');
        this.pollJob(data.job_id, (job) => {
          if (job.status === 'completed') {
            result._downloading = false;
            result.already_downloaded = true;
            result.video_id = data.video_id;
            this.toast('Download complete!', 'success');
            this.loadVideos();
          } else if (job.status === 'failed') {
            result._downloading = false;
            this.toast('Download failed: ' + (job.error || ''), 'error');
          }
        }, { scope: 'download' });
      } catch (e) {
        result._downloading = false;
        this.toast('Request failed', 'error');
      }
    },

    searchChannel(handle) {
      if (!handle) return;
      const q = handle.startsWith('@') ? handle : `@${handle}`;
      this.downloadUrl = q;
      this.searchMode = true;
      this.performSearch(1);
    },

    _resetSearch() {
      this._searchSeq++;
      this._searchAbort?.abort();
      clearTimeout(this._searchDebounce);
      this.searchResults = [];
      this.searchState = 'idle';
      this.searchError = '';
      this.searchAttempted = false;
      this.searchPage = 1;
      this.searchHasMore = false;
    },

    clearSearch() {
      this.downloadUrl = '';
      this.searchMode = false;
      this._resetSearch();
      this.currentDownload = null;
      history.replaceState(null, '', '#/download');
      this.$nextTick(() => this.$refs.searchInput?.focus());
    },

    onFilterChange() {
      if (this.searchAttempted && this.downloadUrl.trim()) {
        this.performSearch(1);
      }
    },

    formatViewCount(n) {
      if (n == null) return '';
      if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
      if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
      return String(n);
    },

    formatUploadDate(str) {
      if (!str || str.length !== 8) return str || '';
      return str.slice(0, 4) + '-' + str.slice(4, 6) + '-' + str.slice(6, 8);
    },

    // --- Clips ---
    async generateClip() {
      if (!this.currentVideo) return;
      try {
        const payload = {
          start_sec: this.getClipStartSecs(),
          end_sec: this.getClipEndSecs(),
          mode: this.clipMode,
        };
        if (this.cropEnabled) payload.crop_pct = this.cropPct;

        const res = await fetch(`${API}/videos/${this.currentVideo.id}/clips`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Clip request failed', 'error');
          return;
        }
        this.toast('Clip job queued', 'info');
        const jobEntry = { ...data, type: 'clip', params: payload };
        this.activeJobs.push(jobEntry);
        this.derivativesPanelOpen = true;
        this.pollJob(data.id, (job) => {
          const idx = this.activeJobs.findIndex(j => j.id === job.id);
          if (idx >= 0) this.activeJobs[idx] = { ...this.activeJobs[idx], ...job };
          if (job.status === 'completed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('Clip ready!', 'success', this.derivativeDownloadUrl(job.id));
            this.refreshDerivatives();
          } else if (job.status === 'failed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('Clip failed: ' + (job.error || ''), 'error');
          }
        });
      } catch (e) {
        this.toast('Request failed', 'error');
      }
    },

    // --- GIFs ---
    async generateGif() {
      if (!this.currentVideo) return;
      try {
        const payload = {
          start_sec: this.getClipStartSecs(),
          end_sec: this.getClipEndSecs(),
          width: this.gifWidth,
          fps: this.gifFps,
          quality: this.gifQuality,
        };
        if (this.cropEnabled) payload.crop_pct = this.cropPct;

        const res = await fetch(`${API}/videos/${this.currentVideo.id}/gifs`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'GIF request failed', 'error');
          return;
        }
        this.toast('GIF job queued', 'info');
        const jobEntry = { ...data, type: 'gif', params: payload };
        this.activeJobs.push(jobEntry);
        this.derivativesPanelOpen = true;
        this.pollJob(data.id, (job) => {
          const idx = this.activeJobs.findIndex(j => j.id === job.id);
          if (idx >= 0) this.activeJobs[idx] = { ...this.activeJobs[idx], ...job };
          if (job.status === 'completed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('GIF ready!', 'success', this.derivativeDownloadUrl(job.id));
            this.refreshDerivatives();
          } else if (job.status === 'failed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('GIF failed: ' + (job.error || ''), 'error');
          }
        });
      } catch (e) {
        this.toast('Request failed', 'error');
      }
    },

    // --- Audio ---
    async generateAudio() {
      if (!this.currentVideo || this.extractingAudio) return;
      this.extractingAudio = true;
      try {
        const payload = {};
        if (!this.audioFullVideo) {
          payload.start_sec = this.getClipStartSecs();
          payload.end_sec = this.getClipEndSecs();
        }
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/audio`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Audio extraction failed', 'error');
          this.extractingAudio = false;
          return;
        }
        this.toast('Audio extraction queued', 'info');
        const jobEntry = { ...data, type: 'audio', params: payload };
        this.activeJobs.push(jobEntry);
        this.derivativesPanelOpen = true;
        this.pollJob(data.id, (job) => {
          const idx = this.activeJobs.findIndex(j => j.id === job.id);
          if (idx >= 0) this.activeJobs[idx] = { ...this.activeJobs[idx], ...job };
          if (job.status === 'completed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('MP3 ready!', 'success', this.derivativeDownloadUrl(job.id));
            this.extractingAudio = false;
            this.refreshDerivatives();
          } else if (job.status === 'failed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast('Audio extraction failed: ' + (job.error || ''), 'error');
            this.extractingAudio = false;
          }
        });
      } catch (e) {
        this.toast('Request failed', 'error');
        this.extractingAudio = false;
      }
    },

    // --- Resolution download ---
    async downloadResolution(height) {
      if (!this.currentVideo || this.resolutionBusy[height]) return;
      this.resolutionBusy[height] = true;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/redownload`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ height }),
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Redownload failed', 'error');
          this.resolutionBusy[height] = false;
          return;
        }
        const label = this.resolutionLabel(height);
        this.toast(`${label} download queued`, 'info');
        const jobEntry = { ...data, type: 'redownload', params: { height } };
        this.activeJobs.push(jobEntry);
        this.derivativesPanelOpen = true;
        this.pollJob(data.id, (job) => {
          const idx = this.activeJobs.findIndex(j => j.id === job.id);
          if (idx >= 0) this.activeJobs[idx] = { ...this.activeJobs[idx], ...job };
          if (job.status === 'completed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast(`${label} ready!`, 'success', this.derivativeDownloadUrl(job.id));
            this.resolutionBusy[height] = false;
            this.refreshDerivatives();
          } else if (job.status === 'failed') {
            this.activeJobs = this.activeJobs.filter(j => j.id !== job.id);
            this.toast(`${label} failed: ` + (job.error || ''), 'error');
            this.resolutionBusy[height] = false;
          }
        });
      } catch (e) {
        this.toast('Request failed', 'error');
        this.resolutionBusy[height] = false;
      }
    },

    async probeHeights() {
      if (!this.currentVideo || this.probingHeights) return;
      this.probingHeights = true;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/probe`, {
          method: 'POST',
        });
        const data = await res.json();
        if (res.ok && data.available_heights) {
          this.availableHeights = data.available_heights;
          this.currentVideo.available_heights = data.available_heights;
          if (data.source_height) {
            this.currentVideo.source_height = data.source_height;
          }
        }
      } catch (e) { /* ignore */ }
      this.probingHeights = false;
    },

    resolutionLabel(h) {
      if (h >= 2160) return '4K';
      if (h >= 1440) return '1440p';
      return h + 'p';
    },

    /** Heights to show in the redownload grid (excludes source height and 1080p). */
    redownloadHeights() {
      const srcH = this.currentVideo?.source_height;
      const exclude = new Set([srcH, 1080].filter(Boolean));
      // Merge available heights with common ones, dedupe, filter, sort desc
      const common = [2160, 1440, 720, 480, 360];
      const all = [...new Set([...this.availableHeights, ...common])];
      return all.filter(h => !exclude.has(h)).sort((a, b) => b - a);
    },

    /** Whether a height is actually available on YouTube. */
    isHeightAvailable(h) {
      return this.availableHeights.includes(h);
    },

    // --- Source download ---
    downloadSource() {
      if (!this.currentVideo) return;
      const a = document.createElement('a');
      a.href = `${API}/videos/${this.currentVideo.id}/source`;
      a.download = '';
      a.click();
    },

    // --- Subtitles ---
    async fetchSubtitles() {
      if (!this.currentVideo || this.fetchingSubs) return;
      this.fetchingSubs = true;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/subtitles/fetch`, {
          method: 'POST',
        });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Subtitle fetch failed', 'error');
          this.fetchingSubs = false;
          return;
        }
        if (data.fetched.length === 0) {
          this.toast('No subtitles available for this video', 'info');
        } else {
          this.toast(`Subtitles fetched: ${data.fetched.join(', ')}`, 'success');
          // Reload subtitle tracks and rebuild video element
          await this.viewVideo(this.currentVideo.id);
        }
      } catch (e) {
        this.toast('Subtitle fetch failed', 'error');
      }
      this.fetchingSubs = false;
    },

    async copySubtitleText(lang) {
      if (!this.currentVideo) return;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/subtitles/${lang}/text`);
        if (!res.ok) {
          this.toast('Failed to fetch subtitle text', 'error');
          return;
        }
        const text = await res.text();
        // navigator.clipboard requires HTTPS; fall back to execCommand on plain HTTP
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
        } else {
          const ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.left = '-9999px';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        this.toast('Subtitles copied to clipboard', 'success');
      } catch (e) {
        this.toast('Failed to copy subtitles', 'error');
      }
    },


    // --- Transcript ---
    async loadTranscriptText() {
      if (!this.currentVideo || !this.transcriptLang) return;
      this.transcriptLoading = true;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/subtitles/${this.transcriptLang}/text`);
        if (!res.ok) {
          this.transcriptText = '';
          return;
        }
        this.transcriptText = await res.text();
      } catch (e) {
        this.transcriptText = '';
      }
      this.transcriptLoading = false;
    },

    async copyTranscriptText() {
      if (!this.transcriptText) return;
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(this.transcriptText);
        } else {
          const ta = document.createElement('textarea');
          ta.value = this.transcriptText;
          ta.style.position = 'fixed';
          ta.style.left = '-9999px';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }
        this.toast('Transcript copied to clipboard', 'success');
      } catch (e) {
        this.toast('Failed to copy transcript', 'error');
      }
    },

    // --- Action accordion ---
    toggleAction(action) {
      this.activeAction = this.activeAction === action ? null : action;
    },

    // --- Protect / Prune ---
    async toggleProtected() {
      if (!this.currentVideo) return;
      const newVal = !this.currentVideo.protected;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/protected`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ protected: newVal }),
        });
        if (!res.ok) {
          this.toast('Failed to update protection', 'error');
          return;
        }
        this.currentVideo.protected = newVal;
        const idx = this.videos.findIndex(v => v.id === this.currentVideo.id);
        if (idx >= 0) this.videos[idx].protected = newVal;
        this.toast(newVal ? 'Video protected' : 'Protection removed', 'success');
      } catch (e) {
        this.toast('Failed to update protection', 'error');
      }
    },

    async pruneVideos() {
      if (this.pruning) return;
      this.pruning = true;
      try {
        const res = await fetch(`${API}/videos/prune`, { method: 'POST' });
        const data = await res.json();
        if (!res.ok) {
          this.toast(data.detail || 'Prune failed', 'error');
          this.pruning = false;
          return;
        }
        this.toast(`Pruned ${data.deleted} video${data.deleted !== 1 ? 's' : ''}`, 'success');
        this.loadVideos();
      } catch (e) {
        this.toast('Prune failed', 'error');
      }
      this.pruning = false;
    },

    // --- Delete video ---
    async confirmDeleteVideo() {
      if (!this.currentVideo) return;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}`, { method: 'DELETE' });
        if (!res.ok) {
          const data = await res.json();
          this.toast(data.detail || 'Delete failed', 'error');
          return;
        }
        this.toast('Video deleted', 'success');
        this.showDeleteModal = false;
        this.navigate('list');
      } catch (e) {
        this.toast('Delete failed', 'error');
      }
    },

    // --- Delete derivative ---
    promptDeleteDerivative(d) {
      this.derivativeToDelete = d;
      this.showDeleteDerivativeModal = true;
    },

    async confirmDeleteDerivative() {
      if (!this.derivativeToDelete || !this.currentVideo) return;
      try {
        const res = await fetch(`${API}/videos/${this.currentVideo.id}/derivatives/${this.derivativeToDelete.job_id}`, {
          method: 'DELETE',
        });
        if (!res.ok) {
          const data = await res.json();
          this.toast(data.detail || 'Delete failed', 'error');
          return;
        }
        this.toast('Download deleted', 'success');
        this.showDeleteDerivativeModal = false;
        this.derivativeToDelete = null;
        this.refreshDerivatives();
      } catch (e) {
        this.toast('Delete failed', 'error');
      }
    },

    async refreshDerivatives() {
      if (!this.currentVideo) return;
      try {
        const [derivRes, videoRes] = await Promise.all([
          fetch(`${API}/videos/${this.currentVideo.id}/derivatives`),
          fetch(`${API}/videos/${this.currentVideo.id}`),
        ]);
        this.derivatives = await derivRes.json();
        if (videoRes.ok) this.currentVideo = await videoRes.json();
      } catch (e) { /* ignore */ }
    },

    // --- Player helpers ---
    setStartFromPlayer() {
      const player = this.$refs.videoPlayer;
      if (player) {
        const t = Math.round(player.currentTime * 10) / 10;
        this.clipStartDisplay = this.secsToMmss(t);
      }
    },
    setEndFromPlayer() {
      const player = this.$refs.videoPlayer;
      if (player) {
        const t = Math.round(player.currentTime * 10) / 10;
        this.clipEndDisplay = this.secsToMmss(t);
      }
    },

    // --- Job Polling ---
    // scope 'page' polls die when the route changes (detail-page jobs);
    // scope 'download' polls survive navigation (library downloads).
    // Generation checks run after every await, so even a poll whose fetch
    // was in flight during navigation stops instead of resurrecting itself.
    POLL_INTERVAL_MS: 1500,
    POLL_MAX_FAILURES: 20,

    pollJob(jobId, callback, opts = {}) {
      const scope = opts.scope || 'page';
      const gen = this._pollGeneration;
      const stale = () => scope === 'page' && gen !== this._pollGeneration;
      let failures = 0;

      const poll = async () => {
        if (stale()) return;
        try {
          const res = await fetch(`${API}/jobs/${jobId}`);
          if (stale()) return;
          if (res.ok) {
            failures = 0;
            const job = await res.json();
            if (stale()) return;
            callback(job);
            if (job.status === 'completed' || job.status === 'failed') return;
          } else if (res.status === 404) {
            callback({ id: jobId, status: 'failed', error: 'Job not found' });
            return;
          } else if (++failures >= this.POLL_MAX_FAILURES) {
            callback({ id: jobId, status: 'failed', error: 'Lost contact with server' });
            return;
          }
        } catch (e) {
          if (stale()) return;
          if (++failures >= this.POLL_MAX_FAILURES) {
            callback({ id: jobId, status: 'failed', error: 'Lost contact with server' });
            return;
          }
        }
        setTimeout(poll, this.POLL_INTERVAL_MS);
      };
      poll();
    },

    _clearPolling() {
      this._pollGeneration++;
    },

    // --- Time helpers ---
    secsToMmss(secs) {
      const total = Math.round(parseFloat(secs) * 10) / 10;
      const m = Math.floor(total / 60);
      const s = total % 60;
      const sInt = Math.floor(s);
      const frac = Math.round((s - sInt) * 10);
      return frac > 0
        ? `${m}:${sInt.toString().padStart(2, '0')}.${frac}`
        : `${m}:${sInt.toString().padStart(2, '0')}`;
    },

    mmssToSecs(str) {
      str = (str || '').trim();
      if (!str) return 0;
      if (!str.includes(':')) return parseFloat(str) || 0;
      const [minPart, secPart] = str.split(':');
      return (parseInt(minPart, 10) || 0) * 60 + (parseFloat(secPart) || 0);
    },

    getClipStartSecs() { return this.mmssToSecs(this.clipStartDisplay); },
    getClipEndSecs() { return this.mmssToSecs(this.clipEndDisplay); },

    // --- Formatting ---
    formatDuration(seconds) {
      if (seconds == null || seconds === '') return '';
      const t = parseFloat(seconds);
      if (isNaN(t)) return '';
      const m = Math.floor(t / 60);
      const s = Math.floor(t % 60);
      return `${m}:${s.toString().padStart(2, '0')}`;
    },

    formatFileSize(bytes) {
      if (!bytes) return '';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
      return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    },

    formatParams(params) {
      if (!params) return '';
      const parts = [];
      if (params.start_sec !== undefined) parts.push(`${this.formatDuration(params.start_sec)}-${this.formatDuration(params.end_sec)}`);
      if (params.mode) parts.push(params.mode);
      if (params.width) parts.push(`${params.width}px`);
      if (params.fps) parts.push(`${params.fps}fps`);
      if (params.quality) parts.push(params.quality);
      if (params.crop_pct) parts.push(`crop ${params.crop_pct}%`);
      if (params.height) parts.push(this.resolutionLabel(params.height));
      if (params.bitrate) parts.push(params.bitrate);
      return parts.join(' / ');
    },

    // --- Toasts ---
    toast(message, type = 'info', link = null) {
      const id = Date.now() + Math.random();
      const toast = { id, message, type, link, visible: true };
      this.toasts.push(toast);
      const duration = link ? 8000 : 4000;
      setTimeout(() => {
        // Skip auto-dismiss if the user already dismissed manually.
        if (this.toasts.find(t => t.id === id)) this.dismissToast(id);
      }, duration);
    },

    dismissToast(id) {
      const t = this.toasts.find(x => x.id === id);
      if (!t) return;
      t.visible = false;
      setTimeout(() => {
        this.toasts = this.toasts.filter(x => x.id !== id);
      }, 300);
    },
  };
}
