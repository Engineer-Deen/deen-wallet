/**
 * deenwallet-client.js
 * Shared frontend client for DeenWallet
 * Enhanced token refresh with user‑friendly error handling
 */

let refreshPromise = null;

const CLIENT_RELEASE_MARKER = 'deenwallet-client-v2';

const DEENWALLET_CONFIG = {
  API_BASE_URL: window.DEENWALLET_API_BASE_URL || (window.location.protocol === 'http:' || window.location.protocol === 'https:' ? window.location.origin : 'http://deenwallapp.com'),
  TOKEN_STORAGE_KEY: 'deenwallet_token',
  USER_STORAGE_KEY: 'deenwallet_user',
  AUTH_PAGE: 'auth.html',
  TRANSACTION_POLL_INTERVAL_MS: 3000,
  APP_UPDATE_CHECK_INTERVAL_MS: 60000,
};

// ==============================================================
// CRASH REPORTING (auth.html, index.html, transactions.html)
// ==============================================================
// So a real customer complaint ("the app crashed when I tried to send
// money") can actually be traced afterwards. Reports go to the public
// POST /api/errors endpoint - it works whether or not the person is
// logged in (a crash on the login page itself needs to be traceable
// too), and tags the report with the user's ID automatically when a
// valid access token is present.
function reportErrorToBackend(errorType, message, stack, url, line, col, extra) {
  try {
    const token = getAccessToken();
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/errors', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({
        errorType: errorType || 'JS_ERROR',
        message: message || 'Unknown error',
        stack: stack || '',
        url: url || window.location.href,
        line: line || 0,
        col: col || 0,
        userAgent: navigator.userAgent,
        extra: extra || {}
      })
    }).catch(function () { });
  } catch (e) { /* never let error reporting itself throw */ }
}

window.addEventListener('error', function (event) {
  reportErrorToBackend(
      'JS_ERROR',
      event.message,
      event.error ? event.error.stack : (event.filename + ':' + event.lineno),
      event.filename,
      event.lineno,
      event.colno,
      { error: event.error ? String(event.error) : '' }
  );
});

window.addEventListener('unhandledrejection', function (event) {
  const reason = event.reason;
  const msg = reason && reason.message ? reason.message : String(reason);
  const stack = reason && reason.stack ? reason.stack : '';
  reportErrorToBackend('PROMISE_REJECTION', msg, stack, window.location.href, 0, 0, { reason: String(reason) });
});

(function () {
  const originalConsoleError = console.error;
  console.error = function () {
    const args = Array.from(arguments);
    const message = args.map(function (a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' ');
    if (message.length > 10) {
      reportErrorToBackend('CONSOLE_ERROR', message.substring(0, 1000), '', window.location.href, 0, 0, { console: true });
    }
    originalConsoleError.apply(console, args);
  };
})();

// ==============================================================
// TOKEN MANAGEMENT
// ==============================================================

function setTokens(accessToken, refreshToken) {
  localStorage.setItem('deenwallet_access_token', accessToken);
  localStorage.setItem('deenwallet_refresh_token', refreshToken);
}

function getAccessToken() {
  return localStorage.getItem('deenwallet_access_token');
}

function getRefreshToken() {
  return localStorage.getItem('deenwallet_refresh_token');
}

function clearTokens() {
  localStorage.removeItem('deenwallet_access_token');
  localStorage.removeItem('deenwallet_refresh_token');
  localStorage.removeItem(DEENWALLET_CONFIG.TOKEN_STORAGE_KEY);
  localStorage.removeItem(DEENWALLET_CONFIG.USER_STORAGE_KEY);
}

const PROVIDERS = [
  { id: 'm17', name: 'Orange Money', frozen: false },
  { id: 'm18', name: 'Africell', frozen: false },
  { id: 'm19', name: 'QMoney', frozen: true },
];

function activeProviders() {
  return PROVIDERS.filter((p) => !p.frozen);
}

const PROVIDER_PREFIX_GUESS = {
  '76': 'm17', '78': 'm17', '79': 'm17', '75': 'm17', '71': 'm17', '72': 'm17', '73': 'm17', '74': 'm17',
  '30': 'm18', '33': 'm18', '88': 'm18', '99': 'm18', '90': 'm18', '77': 'm18', '70': 'm18', '80': 'm18',
  '31': 'm19', '32': 'm19', '34': 'm19', '35': 'm19',
};

function guessProviderId(localPhone) {
  const prefix = localPhone.slice(0, 2);
  const guess = PROVIDER_PREFIX_GUESS[prefix];
  if (guess) return guess;
  return activeProviders()[0].id;
}

function providerName(providerId) {
  const found = PROVIDERS.find((p) => p.id === providerId);
  return found ? found.name : providerId;
}

function onlyDigits(value) { return value.replace(/[^\d]/g, ''); }
function toE164SL(localDigits) { return '+232' + localDigits; }
function formatMoney(value) { return Number(value).toFixed(2); }
function show(el) { if (el) el.classList.remove('hidden'); }
function hide(el) { if (el) el.classList.add('hidden'); }

function formatCountdown(totalSeconds) {
  const clamped = Math.max(0, totalSeconds);
  const m = Math.floor(clamped / 60);
  const s = clamped % 60;
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}

function formatDateTime(isoString) {
  if (!isoString) return '';
  try {
    return new Date(isoString).toLocaleString();
  } catch (_) {
    return isoString;
  }
}

// ==============================================================
// JWT HELPERS
// ==============================================================

function decodeJwtPayload(token) {
  try {
    const payload = token.split('.')[1];
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch (_) {
    return null;
  }
}

function isTokenExpired(token) {
  const payload = decodeJwtPayload(token);
  if (!payload || !payload.exp) return false;
  return Date.now() >= payload.exp * 1000;
}

// ==============================================================
// REFRESH TOKEN FUNCTION
// ==============================================================

function tokenExpiresWithin(token, seconds) {
  const payload = decodeJwtPayload(token);
  if (!payload || !payload.exp) return false;
  return (payload.exp * 1000 - Date.now()) <= seconds * 1000;
}

async function ensureFreshAccessToken(seconds = 60) {
  const token = getAccessToken();
  if (token && tokenExpiresWithin(token, seconds) && getRefreshToken()) {
    return await refreshAccessToken();
  }
  return token;
}

async function refreshAccessToken() {
  if (refreshPromise) return refreshPromise;
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new Error('No refresh token available. Please log in again.');
  }

  refreshPromise = (async () => {
    const response = await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    });
    if (!response.ok) {
      clearTokens();
      throw new Error('Session expired. Please log in again.');
    }
    const data = await response.json();
    const newAccessToken = data.accessToken || data.token;
    if (!newAccessToken) throw new Error('Refresh response missing access token.');
    setTokens(newAccessToken, data.refreshToken || refreshToken);
    return newAccessToken;
  })().finally(() => { refreshPromise = null; });

  return refreshPromise;
}

// ==============================================================
// ENHANCED API CLIENT – NO FORCED REDIRECT
// ==============================================================

async function apiRequest(path, { method = 'GET', params, body } = {}) {
  const url = new URL(DEENWALLET_CONFIG.API_BASE_URL + path);
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) url.searchParams.set(key, value);
    });
  }

  let token = getAccessToken();
  const headers = {
    'Content-Type': 'application/json',
    // Bypasses ngrok's free-tier interstitial page, which otherwise
    // intercepts fetch()/XHR calls with a 403 and an HTML warning body
    // instead of reaching the real backend. Harmless (silently ignored)
    // against any non-ngrok host, so this is safe to leave in permanently.
    'ngrok-skip-browser-warning': 'true'
  };
  if (token) headers['Authorization'] = 'Bearer ' + token;

  const doRequest = async (tokenToUse) => {
    if (tokenToUse) headers['Authorization'] = 'Bearer ' + tokenToUse;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 15000);
    try {
      return await fetch(url.toString(), { method, headers, body: body ? JSON.stringify(body) : undefined, signal: controller.signal });
    } finally {
      clearTimeout(timeoutId);
    }
  };

  try {
    let response;
    try {
      response = await doRequest(token);
    } catch (fetchError) {
      const friendly = new Error('Unable to reach the server. Please check your connection and try again.');
      friendly.isNetworkError = true;
      throw friendly;
    }

    if (token && (response.status === 401 || response.status === 403)) {
      const refreshToken = getRefreshToken();
      if (!refreshToken) {
        clearTokens();
        const err = new Error('Please log in again.');
        err.status = 401;
        throw err;
      }

      try {
        const newToken = await refreshAccessToken();
        const retryResponse = await doRequest(newToken);
        let retryData = null;
        try { retryData = await retryResponse.json(); } catch (_) { }

        if (retryResponse.ok) {
          return retryData;
        }

        const errorMsg = retryData?.message || retryData?.error || '';

        if (retryResponse.status === 423 || errorMsg.toLowerCase().includes('locked') || errorMsg.toLowerCase().includes('blocked')) {
          sessionStorage.setItem('deenwallet_account_locked', 'true');
          const err = new Error(errorMsg || 'Account locked.');
          err.status = retryResponse.status;
          throw err;
        }

        if (retryResponse.status === 401 || retryResponse.status === 403) {
          // The freshly-refreshed token was rejected too - this really is a
          // dead session, not a business-logic error.
          clearTokens();
          const err = new Error('Session expired. Please log in again.');
          err.status = 401;
          throw err;
        }

        // Any other status (e.g. a genuine 400 "incorrect PIN") is a real
        // business-logic result from a now-valid, authenticated request -
        // surface it as-is instead of assuming the session is dead.
        const err = new Error(errorMsg || 'Something went wrong.');
        err.status = retryResponse.status;
        const retryAfter = retryResponse.headers.get('Retry-After');
        if (retryAfter) err.retryAfter = Number(retryAfter) || 0;
        throw err;
      } catch (refreshError) {
        throw refreshError;
      }
    }

    let data = null;
    try { data = await response.json(); } catch (_) { }

    if (!response.ok) {
      const message = (data && (data.message || data.error)) || 'Something went wrong.';
      const error = new Error(message);
      error.status = response.status;
      const retryAfter = response.headers.get('Retry-After');
      if (retryAfter) error.retryAfter = Number(retryAfter) || 0;
      if ((response.status === 403 || response.status === 423) && (message.toLowerCase().includes('locked') || message.toLowerCase().includes('blocked'))) {
        sessionStorage.setItem('deenwallet_account_locked', 'true');
      }
      throw error;
    }
    return data;
  } catch (error) {
    throw error;
  }
}

// ==============================================================
// OTHER PUBLIC FUNCTIONS
// ==============================================================

function requireAuth() {
  const token = getAccessToken();
  if (!token) {
    clearTokens();
    window.location.href = DEENWALLET_CONFIG.AUTH_PAGE;
    return false;
  }
  return true;
}

function getStoredUser() {
  try {
    const raw = localStorage.getItem(DEENWALLET_CONFIG.USER_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (_) {
    return null;
  }
}

async function logout() {
  const refresh = getRefreshToken();
  if (refresh) { try { await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: refresh }) }); } catch (_) {} }
  clearTokens();
  sessionStorage.removeItem('deenwallet_session_last_active');
  sessionStorage.removeItem('deenwallet_account_locked');
  window.location.href = DEENWALLET_CONFIG.AUTH_PAGE;
}

function cleanName(name) {
  return name ? name.replace(/\s+/g, ' ').trim() : name;
}

function normalizeTransaction(raw) {
  const isCleanResponse = typeof raw.amount === 'number' || typeof raw.amount === 'string';

  if (isCleanResponse) {
    return {
      id: raw.id,
      status: raw.status,
      amount: Number(raw.amount),
      fee: Number(raw.fee),
      totalCharged: Number(raw.totalCharged),
      destinationHolderName: cleanName(raw.destinationHolderName) || null,
      destinationPhone: raw.destinationPhone || null,
      sourceProviderId: raw.sourceProviderId || null,
      destinationProviderId: raw.destinationProviderId || null,
      ussdCode: raw.ussdCode || null,
      transactionCode: raw.transactionCode || raw.id,
      failureReason: raw.failureReason || null,
      createdAt: raw.createdAt || null,
    };
  }

  const minorToDecimal = (v) => (v === null || v === undefined ? null : Number(v) / 100);

  return {
    id: raw.id,
    status: raw.status,
    amount: minorToDecimal(raw.amountValue),
    fee: minorToDecimal(raw.feeValue),
    totalCharged: minorToDecimal(raw.totalChargedValue),
    destinationHolderName: cleanName(raw.destinationHolderName) || null,
    destinationPhone: raw.destinationPhone || null,
    sourceProviderId: raw.sourceProviderId || null,
    destinationProviderId: raw.destinationProviderId || null,
    ussdCode: raw.monimeUssdCode || raw.ussdCode || null,
    transactionCode: raw.transactionCode || raw.id,
    failureReason: raw.failureReason || null,
    createdAt: raw.createdAt || null,
  };
}

const STATUS_LABELS = {
  AWAITING_PAYMENT: 'Waiting for payment',
  PAID_IN: 'Payment received',
  PAYING_OUT: 'Processing transfer',
  COMPLETED: 'Transfer completed',
  FAILED: 'Transfer failed',
};

function statusLabel(status) {
  return STATUS_LABELS[status] || status;
}

const AWAITING_PAYMENT_ASSUMED_TTL_MINUTES = 10;

function effectiveStatus(txn) {
  return txn.status;
}

function maskPhone(phone) {
  if (!phone || phone.length <= 4) return phone;
  return phone.slice(0, -4) + '****';
}

function maskEmail(email) {
  if (!email || email.indexOf('@') === -1) return email;
  const [local, domain] = email.split('@');
  const visible = local.slice(0, Math.min(2, local.length));
  return visible + '***@' + domain;
}

// ==============================================================
// PROVIDER DETECTION
// ==============================================================
const QMONEY_PREFIXES = ['31', '32', '34', '35'];

async function detectProviderAndName(localPhone) {
  try {
    const data = await apiRequest('/api/accounts/detect-provider', {
      params: { phone: toE164SL(localPhone) },
    });
    return { status: 'ok', providerId: data.providerId, holderName: cleanName(data.holderName) };
  } catch (error) {
    if (error.status === 404) {
      const prefixes = await loadProviderPrefixes();
      const number = localPhone.replace(/^\+?232/, '');
      let matchedProvider = null;
      for (let len = 2; len >= 1 && !matchedProvider; len--) {
        const prefix = number.slice(0, len);
        for (const [providerId, providerList] of Object.entries(prefixes || {})) {
          if (Array.isArray(providerList) && providerList.includes(prefix)) {
            matchedProvider = providerId;
            break;
          }
        }
      }
      if (matchedProvider === 'm19') {
        return {
          status: 'frozen',
          providerId: matchedProvider,
          message: 'QMoney detected — this provider is not yet available for verified transfers.',
        };
      }
      return {
        status: 'unsupported',
        providerId: matchedProvider || null,
        message: 'This number could not be verified with the configured mobile-money providers.',
      };
    }
    if (error.status === 401 || error.status === 403) {
      throw error;
    }
    return { status: 'error', message: error.message || 'Could not verify this number right now.' };
  }
}

async function previewFee(amount) {
  try {
    const data = await apiRequest('/api/transactions/preview-fee', { params: { amount } });
    return { amount: Number(data.amount), fee: Number(data.fee), totalCharged: Number(data.totalCharged) };
  } catch (error) {
    if (error.status === 401 || error.status === 403) throw error;
    throw new Error(error.message || 'Could not calculate fee.');
  }
}

// ==============================================================
// PASSWORD STRENGTH
// ==============================================================
function checkPasswordStrength(password) {
  const criteria = {
    length: password.length >= 8,
    upper: /[A-Z]/.test(password),
    lower: /[a-z]/.test(password),
    symbol: /[^A-Za-z0-9]/.test(password),
  };
  const metCount = Object.values(criteria).filter(Boolean).length;
  return { criteria, metCount, total: 4, isValid: metCount === 4 };
}

function updateSessionLastActive() {
  sessionStorage.setItem('deenwallet_session_last_active', Date.now().toString());
}

function isSessionExpired(inactivityTimeoutMs = 300000) {
  const lastActive = sessionStorage.getItem('deenwallet_session_last_active');
  if (!lastActive) return true;
  const inactiveTime = Date.now() - parseInt(lastActive);
  return inactiveTime > inactivityTimeoutMs;
}

function getSessionRemainingSeconds(inactivityTimeoutMs = 300000) {
  const lastActive = sessionStorage.getItem('deenwallet_session_last_active');
  if (!lastActive) return 0;
  const inactiveTime = Date.now() - parseInt(lastActive);
  const remaining = Math.max(0, (inactivityTimeoutMs - inactiveTime) / 1000);
  return Math.floor(remaining);
}

// ==============================================================
// ADMIN / LOCK FUNCTIONS (but these are user‑facing helpers)
// ==============================================================

function getDefaultPrefixes() {
  return {
    'm17': ['76', '78', '79', '75', '71', '72', '73', '74'],
    'm18': ['30', '33', '88', '99', '90', '77', '70', '80'],
    'm19': ['31', '32', '34', '35']
  };
}

async function fetchProviderConfig() {
  try {
    const token = getAccessToken();
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const response = await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/providers/prefixes', {
      headers: headers
    });

    if (response.status === 401 || response.status === 403) {
      console.warn('Cannot fetch provider prefixes - admin access required, using defaults');
      return getDefaultPrefixes();
    }

    if (!response.ok) {
      throw new Error('Failed to fetch prefixes');
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.warn('Could not fetch provider prefixes, using defaults:', error);
    return getDefaultPrefixes();
  }
}

function getSupportWhatsApp() {
  return '+23280613600';
}

async function checkUserLocked() {
  try {
    const profile = await apiRequest('/api/users/me');
    return profile.locked || false;
  } catch (_) {
    if (sessionStorage.getItem('deenwallet_account_locked') === 'true') {
      return true;
    }
    return false;
  }
}

async function lockUser(userId) {
  return await apiRequest('/api/admin/users/' + userId + '/lock', { method: 'PUT' });
}

async function unlockUser(userId) {
  return await apiRequest('/api/admin/users/' + userId + '/unlock', { method: 'PUT' });
}

async function getAdminStats() {
  return await apiRequest('/api/admin/stats');
}

async function getAdminUsers() {
  return await apiRequest('/api/admin/users');
}

async function getAdminTransactions() {
  return await apiRequest('/api/admin/transactions');
}

async function updateProviderPrefixes(prefixes) {
  return await apiRequest('/api/admin/providers/prefixes', { method: 'PUT', body: prefixes });
}

function getSupportToken() { return null; }

// ==============================================================
// PROVIDER PREFIX CACHING
// ==============================================================
let providerPrefixes = null;
let prefixLoadAttempted = false;

async function loadProviderPrefixes() {
  if (providerPrefixes) return providerPrefixes;
  if (prefixLoadAttempted) return providerPrefixes || getDefaultPrefixes();

  prefixLoadAttempted = true;
  try {
    providerPrefixes = await fetchProviderConfig();
    return providerPrefixes;
  } catch (error) {
    console.warn('Failed to load provider prefixes:', error);
    providerPrefixes = getDefaultPrefixes();
    return providerPrefixes;
  }
}

async function detectProviderFromPrefix(phoneNumber) {
  const prefixes = await loadProviderPrefixes() || getDefaultPrefixes();
  let number = phoneNumber.replace(/^\+?232/, '');

  for (let len = 2; len >= 1; len--) {
    if (number.length >= len) {
      const prefix = number.substring(0, len);
      for (const [providerId, providerPrefixes] of Object.entries(prefixes)) {
        if (providerPrefixes.includes(prefix)) {
          return providerId;
        }
      }
    }
  }
  return 'm17';
}


// ==============================================================
// AUTOMATIC RELEASE UPDATE MONITOR
// ==============================================================
(function startDeenWalletUpdateMonitor() {
  let currentRelease = sessionStorage.getItem('deenwallet_release_id') || null;
  let updateInProgress = false;

  async function checkRelease() {
    if (updateInProgress) return;
    if (window.DEENWALLET_TRANSACTION_ACTIVE) return;
    if (document.querySelector('.modal-overlay.active, #pin-modal-overlay.active')) return;
    try {
      const response = await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/app/version?_=' + Date.now(), {
        method: 'GET',
        cache: 'no-store',
        headers: { 'Cache-Control': 'no-cache' }
      });
      if (!response.ok) return;
      const data = await response.json();
      const releaseId = data.releaseId;
      if (!releaseId) return;

      if (!currentRelease) {
        currentRelease = releaseId;
        sessionStorage.setItem('deenwallet_release_id', releaseId);
        return;
      }

      if (releaseId !== currentRelease) {
        updateInProgress = true;
        sessionStorage.setItem('deenwallet_release_id', releaseId);
        const url = new URL(window.location.href);
        url.searchParams.set('_dw_update', releaseId);
        window.location.replace(url.toString());
      }
    } catch (_) {
      // A temporary network failure must never interrupt a transaction or logout a user.
    }
  }

  checkRelease();
  setInterval(checkRelease, DEENWALLET_CONFIG.APP_UPDATE_CHECK_INTERVAL_MS);
})();

// ==============================================================
// ANDROID BACK BUTTON (Capacitor only - no-ops on plain web)
// ==============================================================
// Without this, Android's hardware/gesture back button always closes the
// whole app immediately, even when the user is deep in a screen like
// Profile or Transactions. This makes it behave like the app's own back
// arrow instead: close an open panel/modal first if there is one (a page
// can opt into this via window.dwOnBackButton = () => true/false, where
// true means "I handled it, stop here"), then fall back to real browser
// history, and only exit the app when there's truly nothing left to go
// back to.
(function () {
  if (!window.Capacitor || !window.Capacitor.isNativePlatform || !window.Capacitor.isNativePlatform()) return;
  const AppPlugin = window.Capacitor.Plugins && window.Capacitor.Plugins.App;
  if (!AppPlugin) return;

  AppPlugin.addListener('backButton', function (data) {
    if (typeof window.dwOnBackButton === 'function' && window.dwOnBackButton() === true) {
      return; // the page closed its own panel/modal - stop here
    }
    if (data && data.canGoBack) {
      window.history.back();
    } else {
      AppPlugin.exitApp();
    }
  });
})();

// ==============================================================
// NETWORK STATUS (shared across every page that loads this file)
// ==============================================================
// A fetch() call that never reaches a server (no internet, DNS failure,
// airplane mode, etc.) throws a plain Error/TypeError with NO .status
// property at all - unlike every error apiRequest() constructs from an
// actual HTTP response, which always has .status set. That distinction is
// what tells a real connectivity problem apart from a real "wrong
// password"/"incorrect PIN" business-logic response.
function isNetworkError(error) {
  if (!error) return false;
  if (error.isNetworkError === true) return true;
  if (typeof error.status !== 'undefined') return false;
  const msg = (error.message || '').toLowerCase();
  return !navigator.onLine
      || msg.includes('failed to fetch')
      || msg.includes('network')
      || msg.includes('load failed');
}

(function () {
  let banner = null;
  let serverReachable = true;

  function ensureBanner() {
    if (banner) return banner;
    banner = document.createElement('div');
    banner.id = 'dw-offline-banner';
    banner.textContent = 'No internet connection';
    banner.style.cssText = [
      'position:fixed', 'top:0', 'left:0', 'right:0', 'z-index:99999',
      'background:#b91c1c', 'color:#fff', 'text-align:center',
      'font:600 13px/1 -apple-system,Segoe UI,Roboto,sans-serif',
      'padding:10px 12px', 'transform:translateY(-100%)',
      'transition:transform 0.25s ease', 'box-shadow:0 2px 8px rgba(0,0,0,0.25)'
    ].join(';');
    document.body.appendChild(banner);
    return banner;
  }

  function showOfflineBanner() {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', showOfflineBanner, { once: true });
      return;
    }
    ensureBanner().style.transform = 'translateY(0)';
  }

  function hideOfflineBanner() {
    if (banner) banner.style.transform = 'translateY(-100%)';
    // Don't just hide the banner and leave stale data - proactively refresh
    // whatever the current page knows how to refresh, the same hook used
    // by pull-to-refresh. Falls back to a full reload if a page hasn't
    // defined one (e.g. auth.html, which has no meaningful state to lose).
    if (typeof window.dwOnPullToRefresh === 'function') {
      window.dwOnPullToRefresh();
    } else {
      window.location.reload();
    }
  }

  // Covers both failure modes with one check: no internet at all (fetch
  // never completes) AND internet is fine but the backend specifically is
  // unreachable (fetch completes but fails, or times out). navigator.onLine
  // alone can't tell these apart or catch the second case at all.
  let probing = false;
  async function probeServer() {
    if (probing) return;
    probing = true;
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);
      try {
        await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/app/version', { cache: 'no-store', signal: controller.signal });
        if (!serverReachable) { serverReachable = true; hideOfflineBanner(); }
      } catch (_) {
        if (serverReachable) { serverReachable = false; showOfflineBanner(); }
      } finally {
        clearTimeout(timeoutId);
      }
    } finally {
      probing = false;
    }
  }

  window.addEventListener('offline', showOfflineBanner);
  window.addEventListener('online', probeServer);
  if (!navigator.onLine) showOfflineBanner();
  probeServer();
  setInterval(probeServer, 5000);
})();

// ==============================================================
// PULL TO REFRESH
// ==============================================================
// A wrapped WebView has no built-in pull-to-refresh the way a real mobile
// browser tab does, so this adds one. Only activates when the page is
// already scrolled to the very top (so it never interferes with normal
// scrolling anywhere else). A page can opt into a lighter-weight refresh by
// defining window.dwOnPullToRefresh = async () => { ...reload just the
// data... }; otherwise this falls back to a full page reload.
(function () {
  const THRESHOLD = 70;
  let startY = null;
  let pulling = false;
  let indicator = null;

  function ensureIndicator() {
    if (indicator) return indicator;
    indicator = document.createElement('div');
    indicator.id = 'dw-pull-refresh-indicator';
    indicator.textContent = '↓ Pull to refresh';
    indicator.style.cssText = [
      'position:fixed', 'top:0', 'left:0', 'right:0', 'z-index:9998',
      'text-align:center', 'padding:10px', 'font:600 12px/1 -apple-system,Segoe UI,Roboto,sans-serif',
      'color:#555', 'background:rgba(255,255,255,0.95)',
      'transform:translateY(-100%)', 'transition:transform 0.15s ease'
    ].join(';');
    document.body.appendChild(indicator);
    return indicator;
  }

  function onTouchStart(e) {
    if (window.scrollY > 0) { startY = null; return; }
    if (document.querySelector('.modal-overlay.active, #pin-modal-overlay.active')) { startY = null; return; }
    startY = e.touches[0].clientY;
    pulling = false;
  }

  function onTouchMove(e) {
    if (startY === null) return;
    const delta = e.touches[0].clientY - startY;
    if (delta <= 0) return;
    pulling = true;
    const el = ensureIndicator();
    const progress = Math.min(delta / THRESHOLD, 1);
    el.style.transform = 'translateY(' + (progress * 100 - 100) + '%)';
    el.textContent = progress >= 1 ? '↑ Release to refresh' : '↓ Pull to refresh';
  }

  async function onTouchEnd(e) {
    if (!pulling || startY === null) { startY = null; return; }
    const delta = (e.changedTouches[0].clientY - startY);
    startY = null;
    pulling = false;
    if (delta < THRESHOLD) {
      if (indicator) indicator.style.transform = 'translateY(-100%)';
      return;
    }
    const el = ensureIndicator();
    el.textContent = 'Refreshing…';
    el.style.transform = 'translateY(0)';
    try {
      if (typeof window.dwOnPullToRefresh === 'function') {
        await window.dwOnPullToRefresh();
        el.style.transform = 'translateY(-100%)';
      } else {
        window.location.reload();
      }
    } catch (_) {
      el.style.transform = 'translateY(-100%)';
    }
  }

  document.addEventListener('touchstart', onTouchStart, { passive: true });
  document.addEventListener('touchmove', onTouchMove, { passive: true });
  document.addEventListener('touchend', onTouchEnd, { passive: true });
})();