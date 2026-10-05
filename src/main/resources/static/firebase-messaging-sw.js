importScripts('https://www.gstatic.com/firebasejs/11.0.2/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/11.0.2/firebase-messaging-compat.js');

// Firebase MUST be started synchronously, while this script is first run.
// Browsers ignore push handlers that are added later (for example after an
// await), and then no notification is ever shown.
//
// The page registers this worker as /firebase-messaging-sw.js?apiKey=...&...
// so the (public) web config is available right here without a network call.
const params = new URL(self.location.href).searchParams;

firebase.initializeApp({
    apiKey: params.get('apiKey'),
    authDomain: params.get('authDomain'),
    projectId: params.get('projectId'),
    storageBucket: params.get('storageBucket'),
    messagingSenderId: params.get('messagingSenderId'),
    appId: params.get('appId')
});

const messaging = firebase.messaging();

// The server sends a "notification" payload, so Firebase shows the popup by
// itself when no page of the site is visible. This handler is only for logs.
messaging.onBackgroundMessage((payload) => {
    console.log('[DeenWallet Web FCM] Background message:', payload);
});

self.addEventListener('notificationclick', (event) => {
    event.notification.close();

    const targetUrl = event.notification?.data?.url || '/index.html';

    event.waitUntil(
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
            for (const client of clientList) {
                if ('focus' in client) {
                    client.navigate(targetUrl);
                    return client.focus();
                }
            }
            if (clients.openWindow) {
                return clients.openWindow(targetUrl);
            }
        })
    );
});
 
