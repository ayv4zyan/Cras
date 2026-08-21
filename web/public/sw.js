// Cras Web Client Service Worker
// Handles server-authoritative Web Push Notifications

self.addEventListener("install", (event) => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
  if (!event.data) {
    return;
  }

  let payload;
  try {
    payload = event.data.json();
  } catch {
    payload = { title: event.data.text() };
  }

  const title = payload.title || "Cras Notification";
  const options = {
    tag: payload.occurrenceKey || undefined,
    data: {
      taskId: payload.taskId,
      occurrenceKey: payload.occurrenceKey,
    },
    icon: "/favicon.svg",
    badge: "/favicon.svg",
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();

  const taskId = event.notification.data?.taskId;
  const targetUrl = taskId ? `/?taskId=${encodeURIComponent(taskId)}` : "/";

  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clientList) => {
        for (const client of clientList) {
          if ("focus" in client) {
            if (taskId) {
              client.postMessage({ type: "CRAS_OPEN_TASK", taskId });
            }
            return client.focus();
          }
        }
        if (self.clients.openWindow) {
          return self.clients.openWindow(targetUrl);
        }
      }),
  );
});

self.addEventListener("pushsubscriptionchange", (event) => {
  // Push subscription rotated by browser
  event.waitUntil(
    self.registration.pushManager
      .subscribe(event.oldSubscription?.options || { userVisibleOnly: true })
      .then((newSubscription) => {
        // Send new subscription details to clients to sync
        return self.clients
          .matchAll({ type: "window", includeUncontrolled: true })
          .then((clients) => {
            clients.forEach((client) => {
              client.postMessage({
                type: "CRAS_PUSH_SUBSCRIPTION_CHANGE",
                subscription: newSubscription.toJSON(),
              });
            });
          });
      })
      .catch(() => {}),
  );
});
