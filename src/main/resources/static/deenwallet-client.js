let DEENWALLET_FIREBASE_CONFIG = null;

async function loadFirebaseWebConfig() {
  if (DEENWALLET_FIREBASE_CONFIG) {
    return DEENWALLET_FIREBASE_CONFIG;
  }

  const response = await fetch(
      `${window.DEENWALLET_API_BASE_URL}/api/config/firebase-web`
  );

  if (response.status === 404) {
    // Web push is optional. A deployment without Firebase web config should
    // simply skip web push instead of producing a client error on every page load.
    return null;
  }

  if (!response.ok) {
    throw new Error(
        `Failed to load Firebase Web config: HTTP ${response.status}`
    );
  }

  DEENWALLET_FIREBASE_CONFIG = await response.json();

  return DEENWALLET_FIREBASE_CONFIG;
}

/**
 * deenwallet-client.js
 * Shared frontend client for DeenWallet
 * Enhanced token refresh with user‑friendly error handling
 */

let refreshPromise = null;
const recentErrorReports = new Map();
const ERROR_REPORT_DEDUPE_MS = 30000;

const CLIENT_RELEASE_MARKER = 'deenwallet-client-v2';

const DEENWALLET_CONFIG = {
  API_BASE_URL: window.DEENWALLET_API_BASE_URL || (window.location.protocol === 'http:' || window.location.protocol === 'https:' ? window.location.origin : 'http://deenwallapp.com'),
  TOKEN_STORAGE_KEY: 'deenwallet_token',
  USER_STORAGE_KEY: 'deenwallet_user',
  AUTH_PAGE: 'auth.html',
  TRANSACTION_POLL_INTERVAL_MS: 3000,
  APP_UPDATE_CHECK_INTERVAL_MS: 300000,
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
    const endpoint = extra && extra.endpointPath ? extra.endpointPath : '';
    const status = extra && extra.statusCode ? String(extra.statusCode) : '';
    const key = [errorType || 'JS_ERROR', endpoint, status, String(message || '').slice(0, 300)].join('|');
    const now = Date.now();
    const previous = recentErrorReports.get(key);
    if (previous && now - previous < ERROR_REPORT_DEDUPE_MS) return;
    if (recentErrorReports.size >= 200) recentErrorReports.clear();
    recentErrorReports.set(key, now);

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
        // This is the USER app (auth.html / index.html / transactions.html). admin.html
        // reports separately with 'admin', so admins can tell the two apart at a glance.
        sourceApp: 'user',
        endpointPath: (extra && extra.endpointPath) || undefined,
        httpMethod: (extra && extra.httpMethod) || undefined,
        statusCode: (extra && extra.statusCode) || undefined,
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


// ==============================================================
// ANDROID BIOMETRIC LOGIN
// ==============================================================
function getBiometricPlugin() {
  return window.Capacitor && window.Capacitor.Plugins
      ? window.Capacitor.Plugins.DeenWalletBiometric
      : null;
}

async function isBiometricAvailable() {
  const plugin = getBiometricPlugin();
  if (!plugin) return false;
  try {
    const result = await plugin.isAvailable();
    return !!result.available;
  } catch (_) {
    return false;
  }
}

// Every account that has biometric login on THIS phone:
// [{ accountId, credentialId, firstName, maskedEmail, accountNumber }]
// Older app builds only knew one credential and had no account details, so the
// list can contain an entry without accountId. The login screen treats those as
// "old setup, please set it up again" instead of guessing which account it is.
async function listBiometricAccounts() {
  const plugin = getBiometricPlugin();
  if (!plugin) return [];
  try {
    if (typeof plugin.listCredentials === 'function') {
      const result = await plugin.listCredentials();
      return Array.isArray(result.credentials) ? result.credentials : [];
    }
    const legacy = await plugin.hasCredential();
    return legacy && legacy.registered
        ? [{ accountId: '', credentialId: legacy.credentialId, firstName: '', maskedEmail: '', accountNumber: '' }]
        : [];
  } catch (_) {
    return [];
  }
}

// Kept for older callers. True when at least one account is set up on the phone.
async function getBiometricRegistration() {
  const list = await listBiometricAccounts();
  return list.length ? { registered: true, credentialId: list[0].credentialId } : { registered: false };
}

// Asks the phone to get ready (creates the key ahead of time) so the
// fingerprint prompt appears the instant the button is tapped.
async function prepareBiometricRegistration() {
  const plugin = getBiometricPlugin();
  if (!plugin || typeof plugin.prepare !== 'function') return;
  try { await plugin.prepare(); } catch (_) { /* optional speed-up */ }
}

// A registration challenge is fetched ahead of time too. It is valid for 2 minutes,
// so it is refreshed after 90 seconds.
let biometricChallengeCache = null;
function prefetchBiometricRegistrationChallenge() {
  if (!getAccessToken()) return null;
  const now = Date.now();
  if (biometricChallengeCache && now - biometricChallengeCache.at < 90000) return biometricChallengeCache.promise;
  const promise = apiRequest('/api/auth/biometric/registration-challenge', { method: 'POST', body: {} });
  biometricChallengeCache = { at: now, promise };
  promise.catch(function () { if (biometricChallengeCache && biometricChallengeCache.promise === promise) biometricChallengeCache = null; });
  return promise;
}

async function registerBiometricLogin(deviceName) {
  const plugin = getBiometricPlugin();
  if (!plugin) throw new Error('Biometric login is only available in the DeenWallet Android app.');
  if (!getAccessToken()) throw new Error('Please log in first.');

  // Use the challenge fetched ahead of time when there is one. It is used only once.
  const challengePromise = prefetchBiometricRegistrationChallenge();
  biometricChallengeCache = null;
  const challenge = await challengePromise;
  const account = challenge.account || {};

  // If this account already has a credential on this phone, it is replaced.
  const existing = (await listBiometricAccounts()).find(function (c) { return c.accountId && c.accountId === account.id; });

  let signed;
  try {
    signed = await plugin.register({
      challenge: challenge.challenge,
      accountId: account.id || '',
      firstName: account.firstName || '',
      maskedEmail: account.maskedEmail || '',
      accountNumber: account.accountNumber || ''
    });
  } catch (error) {
    // The server never learns about a credential the phone did not finish creating.
    throw error;
  }

  try {
    return await apiRequest('/api/auth/biometric/register', {
      method: 'POST',
      body: {
        credentialId: signed.credentialId,
        publicKey: signed.publicKey,
        signature: signed.signature,
        challenge: challenge.challenge,
        deviceName: deviceName || 'Android device',
        replacesCredentialId: existing ? existing.credentialId : null
      }
    });
  } catch (error) {
    // Registration failed on the server, so remove the phone-side credential.
    // Otherwise the phone would think biometric login is on when it is not.
    try { await plugin.clearCredential({ credentialId: signed.credentialId }); } catch (_) {}
    throw error;
  }
}

// Signs in to ONE specific account that the person chose.
// account = an entry from listBiometricAccounts().
async function biometricLogin(account) {
  const plugin = getBiometricPlugin();
  if (!plugin) throw new Error('Biometric login is only available in the DeenWallet Android app.');
  if (!account || !account.credentialId) {
    throw new Error('Choose which account to sign in to.');
  }
  if (!account.accountId) {
    const oldSetup = new Error('Your biometric login needs to be set up again. Log in with your password, then enable it from the menu.');
    oldSetup.code = 'LEGACY_CREDENTIAL';
    throw oldSetup;
  }

  const challenge = await apiRequest('/api/auth/biometric/challenge', {
    method: 'POST',
    body: { credentialId: account.credentialId, accountId: account.accountId }
  });
  const signed = await plugin.authenticate({
    challenge: challenge.challenge,
    credentialId: account.credentialId,
    accountId: account.accountId
  });
  const data = await apiRequest('/api/auth/biometric/login', {
    method: 'POST',
    body: {
      credentialId: signed.credentialId,
      challenge: challenge.challenge,
      signature: signed.signature,
      accountId: account.accountId
    }
  });
  const accessToken = data.accessToken || data.token;
  const refreshToken = data.refreshToken;
  if (!accessToken || !refreshToken) throw new Error('Biometric login response is missing authentication tokens.');

  // Safety net: the token must belong to the account that was chosen.
  const payload = decodeJwtPayload(accessToken);
  const tokenUser = payload && (payload.sub || payload.userId);
  if (tokenUser && tokenUser !== account.accountId) {
    throw new Error('Sign-in did not match the chosen account. Please try again.');
  }

  setTokens(accessToken, refreshToken);
  localStorage.setItem(DEENWALLET_CONFIG.USER_STORAGE_KEY, JSON.stringify({
    firstName: data.firstName, accountNumber: data.accountNumber, role: data.role
  }));
  return data;
}

// The account that is logged in right now, if it has biometric login on this phone.
async function getBiometricStateForCurrentUser() {
  const plugin = getBiometricPlugin();
  if (!plugin || !getAccessToken()) return { enabled: false, credential: null, server: [] };
  const [local, server] = await Promise.all([
    listBiometricAccounts(),
    apiRequest('/api/auth/biometric/credentials', { method: 'GET' }).catch(function () { return null; })
  ]);
  if (server === null) return { enabled: false, credential: null, server: null, offline: true };
  const match = local.find(function (c) { return server.some(function (s) { return s.credentialId === c.credentialId; }); });
  return { enabled: !!match, credential: match || null, server: server };
}

async function disableBiometricLogin() {
  const plugin = getBiometricPlugin();
  if (!plugin || !getAccessToken()) throw new Error('Biometric login is not available.');
  const state = await getBiometricStateForCurrentUser();
  if (state.server) {
    // Remove this account's credentials for this phone on the server...
    const local = await listBiometricAccounts();
    const mine = state.server.filter(function (s) { return local.some(function (c) { return c.credentialId === s.credentialId; }); });
    for (const item of mine) {
      await apiRequest('/api/auth/biometric/credentials/' + encodeURIComponent(item.id), { method: 'DELETE' });
    }
    // ...and on the phone. Other accounts on this phone are left alone.
    for (const item of mine) {
      await plugin.clearCredential({ credentialId: item.credentialId });
    }
  }
}


// ==============================================================
// IN-APP NOTIFICATION POPUP (with sound and vibration)
// ==============================================================
// Shown whenever a notification arrives while the person is using the app.
// (When the app is closed or in the background the phone or browser shows
// its own notification instead.)
const DEENWALLET_SHOWN_NOTIFICATION_IDS = new Set();
let deenWalletAudioContext = null;

function unlockDeenWalletAudio() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    if (!deenWalletAudioContext) deenWalletAudioContext = new Ctx();
    if (deenWalletAudioContext.state === 'suspended') deenWalletAudioContext.resume();
  } catch (_) { /* sound is optional */ }
}
// Browsers only allow sound after a tap, so the first tap unlocks it.
['pointerdown', 'touchstart', 'keydown', 'click'].forEach(function (name) {
  window.addEventListener(name, unlockDeenWalletAudio, { passive: true, once: false });
});

function playDeenWalletNotificationSound() {
  try {
    unlockDeenWalletAudio();
    const ctx = deenWalletAudioContext;
    if (!ctx || ctx.state !== 'running') return;
    const now = ctx.currentTime;
    // Two short, friendly tones.
    [[880, 0], [1320, 0.16]].forEach(function (pair) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = pair[0];
      gain.gain.setValueAtTime(0.0001, now + pair[1]);
      gain.gain.exponentialRampToValueAtTime(0.35, now + pair[1] + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + pair[1] + 0.34);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + pair[1]);
      osc.stop(now + pair[1] + 0.36);
    });
  } catch (_) { /* sound is optional */ }
}

function vibrateDeenWalletDevice() {
  try {
    if (navigator.vibrate) navigator.vibrate([140, 70, 140]);
  } catch (_) { /* vibration is optional */ }
}

function ensureDeenWalletPopupStyles() {
  if (document.getElementById('dw-popup-styles')) return;
  const style = document.createElement('style');
  style.id = 'dw-popup-styles';
  style.textContent = [
    '.dw-popup-host{position:fixed;left:0;right:0;top:0;z-index:2147483000;display:flex;flex-direction:column;align-items:center;gap:10px;',
    'padding:calc(12px + env(safe-area-inset-top,0px)) 12px 0;pointer-events:none;}',
    '.dw-popup{pointer-events:auto;width:100%;max-width:420px;display:flex;align-items:flex-start;gap:12px;padding:13px 14px;',
    'background:#fff;border-radius:18px;border:1px solid #E4ECEF;box-shadow:0 18px 40px rgba(12,42,51,.22);',
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#12242B;',
    'transform:translateY(-130%);opacity:0;transition:transform .35s cubic-bezier(.2,.8,.2,1),opacity .25s ease;cursor:pointer;}',
    '.dw-popup.dw-in{transform:none;opacity:1;}',
    '.dw-popup img{width:40px;height:40px;flex:none;border-radius:11px;object-fit:cover;}',
    '.dw-popup-body{flex:1;min-width:0;}',
    '.dw-popup-title{margin:0;font-size:14.5px;font-weight:800;line-height:1.3;}',
    '.dw-popup-text{margin:3px 0 0;font-size:13.5px;line-height:1.4;color:#46606A;word-break:break-word;}',
    '.dw-popup-close{flex:none;width:28px;height:28px;border:0;border-radius:50%;background:#F2F7F8;color:#46606A;font-size:16px;line-height:1;cursor:pointer;}',
    '@media (prefers-reduced-motion:reduce){.dw-popup{transition:none;}}'
  ].join('');
  document.head.appendChild(style);
}

// notification = { id?, title, body, onClick? }
function showInAppNotification(notification) {
  try {
    if (!notification) return;
    if (notification.id) {
      if (DEENWALLET_SHOWN_NOTIFICATION_IDS.has(notification.id)) return;
      DEENWALLET_SHOWN_NOTIFICATION_IDS.add(notification.id);
    }
    ensureDeenWalletPopupStyles();

    let host = document.getElementById('dw-popup-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'dw-popup-host';
      host.className = 'dw-popup-host';
      host.setAttribute('role', 'region');
      host.setAttribute('aria-label', 'Notifications');
      document.body.appendChild(host);
    }

    const card = document.createElement('div');
    card.className = 'dw-popup';
    card.setAttribute('role', 'alert');

    const icon = document.createElement('img');
    icon.src = '/assets/icon-192.png';
    icon.alt = '';
    const body = document.createElement('div');
    body.className = 'dw-popup-body';
    const title = document.createElement('p');
    title.className = 'dw-popup-title';
    title.textContent = notification.title || 'DeenWallet';
    const text = document.createElement('p');
    text.className = 'dw-popup-text';
    text.textContent = notification.body || '';
    body.appendChild(title);
    body.appendChild(text);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'dw-popup-close';
    close.setAttribute('aria-label', 'Dismiss');
    close.textContent = '\u00D7';

    card.appendChild(icon);
    card.appendChild(body);
    card.appendChild(close);
    host.appendChild(card);
    // Keep only the three newest on screen.
    while (host.children.length > 3) host.removeChild(host.firstChild);

    let timer = null;
    function dismiss() {
      if (timer) clearTimeout(timer);
      card.classList.remove('dw-in');
      setTimeout(function () { if (card.parentNode) card.parentNode.removeChild(card); }, 350);
    }
    close.addEventListener('click', function (e) { e.stopPropagation(); dismiss(); });
    card.addEventListener('click', function () {
      dismiss();
      if (typeof notification.onClick === 'function') notification.onClick();
      else window.dispatchEvent(new CustomEvent('deenwallet:notification-open'));
    });

    requestAnimationFrame(function () { card.classList.add('dw-in'); });
    timer = setTimeout(dismiss, 7000);

    playDeenWalletNotificationSound();
    vibrateDeenWalletDevice();
  } catch (error) {
    console.warn('[DeenWallet] Could not show the in-app notification:', error);
  }
}

// One entry point for every push that arrives while the app is open.
function handleForegroundPush(payload) {
  const n = (payload && payload.notification) || {};
  const data = (payload && payload.data) || {};
  showInAppNotification({
    id: data.notificationId || null,
    title: n.title || (payload && payload.title),
    body: n.body || (payload && payload.body)
  });
  // Lets the page refresh its bell badge and list right away.
  window.dispatchEvent(new CustomEvent('deenwallet:notification', { detail: { data: data } }));
}

// ==============================================================
// ANDROID FIREBASE CLOUD MESSAGING (FCM)
// ==============================================================
// Registers the authenticated Android device token with the backend.
// This is intentionally a no-op in the normal web browser.
const DEENWALLET_FCM_TOKEN_STORAGE_KEY = 'deenwallet_fcm_token';

function isDeenWalletAndroidApp() {
  const result = !!(
      window.Capacitor &&
      typeof window.Capacitor.isNativePlatform === 'function' &&
      window.Capacitor.isNativePlatform() &&
      window.Capacitor.getPlatform &&
      window.Capacitor.getPlatform() === 'android'
  );
  console.log('[DeenWallet FCM] Android app detected:', result, {
    capacitorPresent: !!window.Capacitor,
    nativePlatform: !!(window.Capacitor && typeof window.Capacitor.isNativePlatform === 'function' && window.Capacitor.isNativePlatform()),
    platform: window.Capacitor && window.Capacitor.getPlatform ? window.Capacitor.getPlatform() : null
  });
  return result;
}

function getPushNotificationsPlugin() {
  return window.Capacitor && window.Capacitor.Plugins
      ? window.Capacitor.Plugins.PushNotifications
      : null;
}

async function unregisterFcmToken(token) {
  if (!token || !getAccessToken()) return;

  try {
    await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/users/me/device-token', {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAccessToken()
      },
      body: JSON.stringify({
        fcmToken: token,
        platform: 'android'
      })
    });
  } catch (_) {
    // Logout must still complete if the backend is temporarily unreachable.
  }
}

async function registerFcmTokenWithBackend(token) {
  console.log('[DeenWallet FCM] registerFcmTokenWithBackend called:', {
    hasToken: !!token,
    tokenLength: token ? token.length : 0,
    hasAccessToken: !!getAccessToken(),
    apiBaseUrl: DEENWALLET_CONFIG.API_BASE_URL
  });
  if (!token || !getAccessToken()) {
    console.warn('[DeenWallet FCM] Backend registration skipped: missing FCM token or access token.');
    return;
  }

  const registeredToken = localStorage.getItem(DEENWALLET_FCM_TOKEN_STORAGE_KEY);
  if (registeredToken === token) return;

  try {
    const response = await fetch(DEENWALLET_CONFIG.API_BASE_URL + '/api/users/me/device-token', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getAccessToken()
      },
      body: JSON.stringify({
        fcmToken: token,
        platform: 'android'
      })
    });

    console.log('[DeenWallet FCM] Backend registration response:', response.status);

    if (!response.ok) {
      if (response.status === 401 || response.status === 403) return;
      throw new Error('Device token registration failed with HTTP ' + response.status);
    }

    localStorage.setItem(DEENWALLET_FCM_TOKEN_STORAGE_KEY, token);
  } catch (error) {
    console.warn('FCM device-token registration failed:', error);
  }
}

async function initializeDeenWalletPushNotifications() {
  console.log('[DeenWallet FCM] initializeDeenWalletPushNotifications called:', {
    hasAccessToken: !!getAccessToken(),
    apiBaseUrl: DEENWALLET_CONFIG.API_BASE_URL
  });

  if (!isDeenWalletAndroidApp()) {
    console.warn('[DeenWallet FCM] Initialization skipped: not running as Android app.');
    return;
  }
  if (!getAccessToken()) {
    console.warn('[DeenWallet FCM] Initialization skipped: no access token.');
    return;
  }

  const PushNotifications = getPushNotificationsPlugin();
  console.log('[DeenWallet FCM] PushNotifications plugin:', !!PushNotifications);
  if (!PushNotifications) {
    console.warn('Capacitor Push Notifications plugin is unavailable.');
    return;
  }

  try {
    // Register listeners before calling register(), because the native
    // plugin may emit the token immediately after registration.
    await PushNotifications.addListener('registration', async function (token) {
      if (token && token.value) {
        console.log('[DeenWallet FCM] registration event received. Token length:', token.value.length);
        await registerFcmTokenWithBackend(token.value);
      }
    });

    await PushNotifications.addListener('registrationError', function (error) {
      console.error('[DeenWallet FCM] registrationError event:', error);
    });

    // A push that arrives while the app is open: show our own popup (with sound and vibration).
    await PushNotifications.addListener('pushNotificationReceived', function (notification) {
      handleForegroundPush({
        title: notification && notification.title,
        body: notification && notification.body,
        data: (notification && notification.data) || {}
      });
    });

    // The alert channel the server targets, so background notifications also play a sound and vibrate.
    try {
      if (typeof PushNotifications.createChannel === 'function') {
        await PushNotifications.createChannel({
          id: 'deenwallet_alerts',
          name: 'DeenWallet alerts',
          description: 'Transfers and account messages',
          importance: 5,
          visibility: 1,
          vibration: true,
          lights: true
        });
      }
    } catch (channelError) {
      console.warn('[DeenWallet FCM] Could not create the alert channel:', channelError);
    }

    const permission = await PushNotifications.checkPermissions();
    let receive = permission.receive;
    console.log('[DeenWallet FCM] permission status:', receive);

    if (receive === 'prompt') {
      const requested = await PushNotifications.requestPermissions();
      receive = requested.receive;
      console.log('[DeenWallet FCM] permission after request:', receive);
    }

    if (receive !== 'granted') {
      console.warn('Push notification permission was not granted.');
      return;
    }

    console.log('[DeenWallet FCM] calling PushNotifications.register()');
    await PushNotifications.register();
    console.log('[DeenWallet FCM] PushNotifications.register() completed');
  } catch (error) {
    console.error('[DeenWallet FCM] initialization failed:', error);
  }
}

// The authenticated token is already stored by the login flow before the
// user is redirected to the main application. This also handles app
// restarts/page refreshes while the user remains signed in.
console.log('[DeenWallet FCM] client loaded. Access token present:', !!getAccessToken());
if (getAccessToken()) {
  initializeDeenWalletPushNotifications();
} else {
  console.log('[DeenWallet FCM] initialization not started because no access token is present.');
}

// ==============================================================
// WEB FIREBASE CLOUD MESSAGING (FCM)
// ==============================================================
async function initializeDeenWalletWebPushNotifications() {
  // Never run this inside the native Android/iOS app.
  if (window.Capacitor?.isNativePlatform?.()) {
    return;
  }

  if (!getAccessToken()) {
    console.log('[DeenWallet Web FCM] No access token. Skipping.');
    return;
  }

  if (!('Notification' in window) || !('serviceWorker' in navigator)) {
    console.warn('[DeenWallet Web FCM] Browser does not support notifications.');
    return;
  }

  try {
    const firebaseConfig = await loadFirebaseWebConfig();
    if (!firebaseConfig) {
      console.log('[DeenWallet Web FCM] Web Firebase config is not configured. Skipping.');
      return;
    }

    if (!window.firebase) {
      console.error('[DeenWallet Web FCM] Firebase SDK is not loaded.');
      return;
    }

    if (!firebase.apps.length) {
      firebase.initializeApp(firebaseConfig);
    }

    const messaging = firebase.messaging();

    // The worker reads the public web config from its own URL so it can start
    // Firebase immediately (see firebase-messaging-sw.js).
    const swParams = new URLSearchParams({
      apiKey: firebaseConfig.apiKey,
      authDomain: firebaseConfig.authDomain,
      projectId: firebaseConfig.projectId,
      storageBucket: firebaseConfig.storageBucket,
      messagingSenderId: firebaseConfig.messagingSenderId,
      appId: firebaseConfig.appId
    });

    const registration = await navigator.serviceWorker.register(
        '/firebase-messaging-sw.js?' + swParams.toString()
    );
    await navigator.serviceWorker.ready;

    console.log('[DeenWallet Web FCM] Service worker registered.');

    let permission = Notification.permission;

    if (permission === 'default') {
      permission = await Notification.requestPermission();
    }

    if (permission !== 'granted') {
      console.warn('[DeenWallet Web FCM] Notification permission not granted.');
      return;
    }

    const token = await messaging.getToken({
      vapidKey: firebaseConfig.vapidKey,
      serviceWorkerRegistration: registration
    });

    if (!token) {
      console.warn('[DeenWallet Web FCM] No FCM token returned.');
      return;
    }

    console.log('[DeenWallet Web FCM] Browser FCM token obtained.');

    await registerWebFcmToken(token);

    // While the site is open and visible, Firebase hands the message to the
    // page instead of showing a popup, so the page shows its own popup.
    messaging.onMessage((payload) => {
      console.log('[DeenWallet Web FCM] Foreground message:', payload);
      handleForegroundPush(payload);
    });

  } catch (error) {
    console.error('[DeenWallet Web FCM] Initialization failed:', error);
  }
}


async function registerWebFcmToken(token) {
  if (!token || !getAccessToken()) {
    return;
  }

  const storageKey = 'deenwallet_web_fcm_token';
  const registeredToken = localStorage.getItem(storageKey);

  if (registeredToken === token) {
    return;
  }

  try {
    const response = await fetch(
        DEENWALLET_CONFIG.API_BASE_URL + '/api/users/me/device-token',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + getAccessToken()
          },
          body: JSON.stringify({
            fcmToken: token,
            platform: 'web'
          })
        }
    );

    if (!response.ok) {
      throw new Error(
          'Web device-token registration failed with HTTP ' +
          response.status
      );
    }

    localStorage.setItem(storageKey, token);

    console.log(
        '[DeenWallet Web FCM] Browser token registered successfully.'
    );

  } catch (error) {
    console.warn(
        '[DeenWallet Web FCM] Token registration failed:',
        error
    );
  }
}


if (!window.Capacitor?.isNativePlatform?.() && getAccessToken()) {
  initializeDeenWalletWebPushNotifications();
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

async function apiRequest(path, { method = 'GET', params, body, headers: extraHeaders = {}, timeoutMs = 15000 } = {}) {
  // Wraps the ENTIRE function, including building the URL/headers below - so a bug
  // there (like a past release that referenced an undefined `headers` variable and
  // crashed every request with no trace at all) is now caught, reported with a full
  // stack, and re-thrown, instead of vanishing before it could ever reach the network.
  try {
    const url = new URL(DEENWALLET_CONFIG.API_BASE_URL + path);
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        if (value !== undefined && value !== null) url.searchParams.set(key, value);
      });
    }

    let token = getAccessToken();
    const headers = { 'Content-Type': 'application/json', ...extraHeaders };
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const doRequest = async (tokenToUse) => {
      if (tokenToUse) headers['Authorization'] = 'Bearer ' + tokenToUse;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
      try {
        return await fetch(url.toString(), { method, headers, body: body ? JSON.stringify(body) : undefined, signal: controller.signal });
      } finally {
        clearTimeout(timeoutId);
      }
    };

    // Reports a failed call to THIS endpoint, so it shows up traceable to the exact
    // path + method in the admin Errors tab. Skips expected 401s (a stale token
    // refreshing is normal, not a bug worth an admin's time).
    const reportApiFailure = (message, statusCode) => {
      // 4xx responses are normally user/business validation results, not
      // application faults. Do not flood the error table when a customer
      // is correcting an input. Keep rate-limit/lock responses visible.
      if (statusCode >= 400 && statusCode < 500
          && statusCode !== 423 && statusCode !== 429) return;
      reportErrorToBackend('API_ERROR', message, '', window.location.href, 0, 0,
          { endpointPath: path, httpMethod: method, statusCode });
    };

    let response;
    try {
      response = await doRequest(token);
    } catch (fetchError) {
      // No HTTP response at all: DNS failure, connection refused, CORS block or timeout.
      reportErrorToBackend('NETWORK_ERROR', String(fetchError && fetchError.message || fetchError),
          fetchError && fetchError.stack, window.location.href, 0, 0,
          { endpointPath: path, httpMethod: method });
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
          reportApiFailure(errorMsg || 'Account locked.', retryResponse.status);
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
        reportApiFailure(errorMsg || 'Something went wrong.', retryResponse.status);
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
      // Trace WHICH endpoint failed, not just that something on the page went wrong.
      reportApiFailure(message, response.status);
      throw error;
    }
    return data;
  } catch (error) {
    // Anything NOT already one of our own recognizable errors (network/401/423/etc,
    // all of which carry isNetworkError or a .status) is an unexpected bug in this
    // function itself - report it with a full stack so it's traceable, then behave
    // exactly as before by re-throwing unchanged.
    if (!error.isNetworkError && error.status === undefined) {
      reportErrorToBackend('CLIENT_BUG', String(error && error.message || error), error && error.stack,
          window.location.href, 0, 0, { endpointPath: path, httpMethod: method });
    }
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
  const fcmToken = localStorage.getItem(DEENWALLET_FCM_TOKEN_STORAGE_KEY);

  // Remove the device token while the current user is still authenticated.
  // This prevents a signed-out account from continuing to receive pushes.
  await unregisterFcmToken(fcmToken);
  localStorage.removeItem(DEENWALLET_FCM_TOKEN_STORAGE_KEY);

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
      serviceType: raw.serviceType || 'MOBILE_MONEY',
      bankProviderId: raw.bankProviderId || null,
      bankName: raw.bankName || null,
      bankAccountNumber: raw.bankAccountNumber || null,
      bankAccountHolderName: cleanName(raw.bankAccountHolderName) || null,
      bankAccountKycVerified: raw.bankAccountKycVerified === true,
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
    serviceType: raw.serviceType || 'MOBILE_MONEY',
    bankProviderId: raw.bankProviderId || null,
    bankName: raw.bankName || null,
    bankAccountNumber: raw.bankAccountNumber || null,
    bankAccountHolderName: cleanName(raw.bankAccountHolderName) || null,
    bankAccountKycVerified: raw.bankAccountKycVerified === true,
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
    if (updateInProgress || document.hidden) return;
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
    if (typeof window.dwOnBackButton === 'function') {
      const result = window.dwOnBackButton();
      if (result === true) {
        return; // the current DeenWallet screen handled the back action.
      }
      if (result === 'exit') {
        AppPlugin.exitApp();
        return;
      }
    }

    // Pages without a custom DeenWallet back handler keep normal browser
    // history behaviour, then exit only when there is nowhere else to go.
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
    if (probing || document.hidden) return;
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
  setInterval(probeServer, 30000);
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