import type { SupabaseClient } from "@supabase/supabase-js";

export interface LeasedJob {
  job_id: string;
  lease_token: string;
  task_id: string;
  task_title: string;
  task_version: number;
  task_completed_at: string | null;
  operator_id: string;
  occurrence_key: string;
  interpreted_due_at: string;
  missed_delivery_enabled: boolean;
  platform: "web" | "android";
  endpoint: string | null;
  p256dh: string | null;
  auth: string | null;
  is_active: boolean;
  local_enabled: boolean;
  permission_state: string;
}

export interface PushPayload {
  taskId: string;
  occurrenceKey: string;
  title: string;
}

export interface WebPushOptions {
  vapidPublicKey?: string;
  vapidPrivateKey?: string;
  vapidSubject?: string;
}

export interface FcmOptions {
  projectId?: string;
  clientEmail?: string;
  privateKey?: string;
}

export type NotificationDeliveryOptions = WebPushOptions & {
  fcm?: FcmOptions;
};

const FCM_SEND_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const FCM_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";

interface CachedFcmToken {
  token: string;
  expiresAtMs: number;
}

const fcmTokenCache = new Map<string, CachedFcmToken>();
const fcmTokenInflight = new Map<string, Promise<string>>();

/**
 * Builds the FCM v1 notification message for an Android job: Task title plus
 * opaque routing data only. The FCM registration token carried by the job's
 * endpoint targets the installation at the message top level, as required by
 * messages:send.
 */
export function buildFcmMessage(
  job: LeasedJob,
  ttlSeconds: number,
): {
  message: {
    token: string;
    notification: { title: string };
    data: { taskId: string; occurrenceKey: string };
    android: {
      priority: string;
      ttl: string;
      tag: string;
    };
  };
} {
  return {
    message: {
      token: job.endpoint!,
      notification: {
        title: job.task_title,
      },
      data: {
        taskId: job.task_id,
        occurrenceKey: job.occurrence_key,
      },
      android: {
        priority: "HIGH",
        ttl: `${Math.max(0, Math.floor(ttlSeconds))}s`,
        tag: job.occurrence_key,
      },
    },
  };
}

/**
 * Classifies an FCM send failure. UNREGISTERED / SENDER_ID_MISMATCH are
 * permanent rejections that disable the endpoint; invalid requests are
 * cancelled without disabling; quota/availability errors retry within deadline.
 * A permanent classification always requires the provider's explicit error
 * code, because v1 reports unregistered tokens as 404 NOT_FOUND whose details
 * carry UNREGISTERED while other 404s (a wrong project id) must not disable
 * endpoints.
 */
export function classifyFcmSendFailure(
  statusCode: number,
  errorCode?: string,
): "permanent_failure" | "cancelled" | "transient_failure" {
  if (errorCode === "UNREGISTERED" || errorCode === "SENDER_ID_MISMATCH") {
    return "permanent_failure";
  }
  // A rejected or expired OAuth access token is a retryable delivery
  // condition, not a defect of the request or the endpoint.
  if (statusCode === 401) {
    return "transient_failure";
  }
  if (isNonRetryableStatus(statusCode)) {
    return "cancelled";
  }
  return "transient_failure";
}

function pemPrivateKeyToPkcs8(pem: string): Uint8Array {
  const body = pem
    // Service-account secrets are often stored as JSON strings whose PEM
    // newlines arrive as literal escape sequences rather than whitespace.
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = globalThis.atob(body);
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; ++i) {
    der[i] = binary.charCodeAt(i);
  }
  return der;
}

async function signFcmJwt(fcm: FcmOptions): Promise<string> {
  const enc = new TextEncoder();
  const header = uint8ArrayToBase64Url(
    enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })),
  );
  const nowSeconds = Math.floor(Date.now() / 1000);
  const payload = uint8ArrayToBase64Url(
    enc.encode(
      JSON.stringify({
        iss: fcm.clientEmail,
        scope: FCM_SEND_SCOPE,
        aud: FCM_OAUTH_TOKEN_URL,
        iat: nowSeconds,
        exp: nowSeconds + 3600,
      }),
    ),
  );

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemPrivateKeyToPkcs8(fcm.privateKey!),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    key,
    enc.encode(`${header}.${payload}`),
  );
  return `${header}.${payload}.${uint8ArrayToBase64Url(new Uint8Array(signature))}`;
}

async function getFcmAccessToken(
  fcm: FcmOptions,
  fetchFn: typeof fetch,
): Promise<string> {
  const cacheKey = fcm.clientEmail!;
  const cached = fcmTokenCache.get(cacheKey);
  if (cached && Date.now() < cached.expiresAtMs) {
    return cached.token;
  }

  // A worker tick leases many jobs at once; share one signing and OAuth
  // exchange per service account instead of racing the same request.
  const inflight = fcmTokenInflight.get(cacheKey);
  if (inflight) {
    return inflight;
  }

  const request = fetchFcmAccessToken(fcm, fetchFn, cacheKey).finally(() => {
    fcmTokenInflight.delete(cacheKey);
  });
  fcmTokenInflight.set(cacheKey, request);
  return request;
}

async function fetchFcmAccessToken(
  fcm: FcmOptions,
  fetchFn: typeof fetch,
  cacheKey: string,
): Promise<string> {
  const assertion = await signFcmJwt(fcm);
  const response = await fetchFn(FCM_OAUTH_TOKEN_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }).toString(),
    signal: AbortSignal.timeout(10000),
  });

  if (!response.ok) {
    throw new Error(
      `FCM OAuth token request failed with status ${response.status}`,
    );
  }

  const body = (await response.json()) as {
    access_token?: string;
    expires_in?: number;
  };
  if (!body.access_token) {
    throw new Error("FCM OAuth token response missing access_token");
  }

  fcmTokenCache.set(cacheKey, {
    token: body.access_token,
    expiresAtMs:
      Date.now() + Math.max(60, (body.expires_in ?? 3600) - 60) * 1000,
  });
  return body.access_token;
}

export function base64UrlToUint8Array(base64String: string): Uint8Array {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = globalThis.atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

export function uint8ArrayToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  const b64 = globalThis.btoa(binary);
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Converts a raw 32-byte P-256 private key scalar to a PKCS#8 DER structure
 * suitable for Web Crypto importKey("pkcs8", ...).
 */
export function rawP256PrivateKeyToPkcs8(rawBytes: Uint8Array): Uint8Array {
  // PKCS#8 PrivateKeyInfo prefix for P-256 (secp256r1) containing a 32-byte ECPrivateKey
  const pkcs8Prefix = new Uint8Array([
    0x30,
    0x41, // SEQUENCE (65 bytes)
    0x02,
    0x01,
    0x00, // INTEGER 0 (version v1)
    0x30,
    0x13, // SEQUENCE (AlgorithmIdentifier, 19 bytes)
    0x06,
    0x07,
    0x2a,
    0x86,
    0x48,
    0xce,
    0x3d,
    0x02,
    0x01, // OID 1.2.840.10045.2.1 (id-ecPublicKey)
    0x06,
    0x08,
    0x2a,
    0x86,
    0x48,
    0xce,
    0x3d,
    0x03,
    0x01,
    0x07, // OID 1.2.840.10045.3.1.7 (secp256r1)
    0x04,
    0x27, // OCTET STRING (39 bytes)
    0x30,
    0x25, // SEQUENCE (ECPrivateKey, 37 bytes)
    0x02,
    0x01,
    0x01, // INTEGER 1 (ECPrivateKey version)
    0x04,
    0x20, // OCTET STRING (32 bytes)
  ]);
  const pkcs8 = new Uint8Array(pkcs8Prefix.length + rawBytes.length);
  pkcs8.set(pkcs8Prefix, 0);
  pkcs8.set(rawBytes, pkcs8Prefix.length);
  return pkcs8;
}

export async function deriveTopic(
  occurrenceKey: string,
  taskId: string,
): Promise<string> {
  const enc = new TextEncoder();
  const data = enc.encode(`${occurrenceKey}:${taskId}`);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return uint8ArrayToBase64Url(new Uint8Array(hash)).slice(0, 32);
}

/**
 * Encrypts a plaintext payload using RFC 8291 aes128gcm for Web Push.
 * Returns null if the keys are invalid or not 65-byte P-256 / 16-byte auth.
 */
export async function encryptWebPushPayload(
  payload: string,
  p256dh: string,
  auth: string,
): Promise<Uint8Array | null> {
  try {
    const enc = new TextEncoder();
    const clientKeyBytes = base64UrlToUint8Array(p256dh);
    const authSecret = base64UrlToUint8Array(auth);

    if (clientKeyBytes.length !== 65 || authSecret.length !== 16) {
      return null;
    }

    const localKp = await crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"],
    );
    const localPubRaw = new Uint8Array(
      await crypto.subtle.exportKey("raw", localKp.publicKey),
    );

    const clientKey = await crypto.subtle.importKey(
      "raw",
      clientKeyBytes,
      { name: "ECDH", namedCurve: "P-256" },
      false,
      [],
    );
    const ecdhSecret = new Uint8Array(
      await crypto.subtle.deriveBits(
        { name: "ECDH", public: clientKey },
        localKp.privateKey,
        256,
      ),
    );

    const infoKey = new Uint8Array(14 + 65 + 65);
    infoKey.set(enc.encode("WebPush: info\0"), 0);
    infoKey.set(clientKeyBytes, 14);
    infoKey.set(localPubRaw, 14 + 65);

    const hkdfKey = await crypto.subtle.importKey(
      "raw",
      ecdhSecret,
      "HKDF",
      false,
      ["deriveBits"],
    );
    const ikm = await crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: authSecret,
        info: infoKey,
      },
      hkdfKey,
      256,
    );

    const salt = crypto.getRandomValues(new Uint8Array(16));
    const prkKey = await crypto.subtle.importKey("raw", ikm, "HKDF", false, [
      "deriveBits",
    ]);

    const cek = await crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt,
        info: enc.encode("Content-Encoding: aes128gcm\0"),
      },
      prkKey,
      128,
    );

    const nonce = await crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt,
        info: enc.encode("Content-Encoding: nonce\0"),
      },
      prkKey,
      96,
    );

    const payloadBytes = enc.encode(payload);
    const record = new Uint8Array(payloadBytes.length + 1);
    record.set(payloadBytes, 0);
    record[payloadBytes.length] = 2; // record delimiter

    const aesKey = await crypto.subtle.importKey("raw", cek, "AES-GCM", false, [
      "encrypt",
    ]);
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, tagLength: 128 },
      aesKey,
      record,
    );

    const finalBody = new Uint8Array(16 + 4 + 1 + 65 + ciphertext.byteLength);
    finalBody.set(salt, 0);
    const view = new DataView(finalBody.buffer);
    view.setUint32(16, 4096, false);
    finalBody[20] = 65;
    finalBody.set(localPubRaw, 21);
    finalBody.set(new Uint8Array(ciphertext), 86);

    return finalBody;
  } catch {
    return null;
  }
}

/**
 * Generates an RFC 8292 VAPID Authorization header token if keys are available.
 * Handles both raw 32-byte base64url keys and PKCS#8 DER base64url keys.
 */
export async function generateVapidHeader(
  endpoint: string,
  vapidPrivateKey: string,
  vapidPublicKey: string,
  subject: string = "mailto:support@cras.app",
): Promise<string | null> {
  try {
    const enc = new TextEncoder();
    const origin = new URL(endpoint).origin;
    const header = uint8ArrayToBase64Url(
      enc.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })),
    );
    const payload = uint8ArrayToBase64Url(
      enc.encode(
        JSON.stringify({
          aud: origin,
          exp: Math.floor(Date.now() / 1000) + 12 * 3600,
          sub: subject,
        }),
      ),
    );

    const rawKeyBytes = base64UrlToUint8Array(vapidPrivateKey);
    const pkcs8Bytes =
      rawKeyBytes.length === 32
        ? rawP256PrivateKeyToPkcs8(rawKeyBytes)
        : rawKeyBytes;

    const key = await crypto.subtle.importKey(
      "pkcs8",
      pkcs8Bytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"],
    );

    const dataToSign = enc.encode(`${header}.${payload}`);
    const sig = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      dataToSign,
    );

    const jwt = `${header}.${payload}.${uint8ArrayToBase64Url(new Uint8Array(sig))}`;
    return `vapid t=${jwt}, k=${vapidPublicKey}`;
  } catch (err) {
    console.error("Failed to generate VAPID header:", err);
    return null;
  }
}

/** Statuses that prove the subscription itself is gone. */
export function isEndpointGoneStatus(statusCode: number): boolean {
  return [404, 410].includes(statusCode);
}

/** Statuses that will not change on retry, but do not invalidate the endpoint. */
export function isNonRetryableStatus(statusCode: number): boolean {
  return [400, 401, 403, 413].includes(statusCode);
}

export function isPermanentFailureStatus(statusCode: number): boolean {
  return isEndpointGoneStatus(statusCode);
}

export async function recordResult(
  supabase: SupabaseClient,
  jobId: string,
  leaseToken: string,
  result: string,
  statusCode?: number,
): Promise<void> {
  const client =
    typeof supabase.schema === "function" ? supabase.schema("api") : supabase;
  const { error } = await client.rpc("record_notification_result", {
    p_job_id: jobId,
    p_lease_token: leaseToken,
    p_result: result,
    p_status_code: statusCode ?? null,
  });
  if (error) {
    throw new Error(
      `Failed to record notification result: ${error.message} (${error.code})`,
    );
  }
}

export async function processNotificationJob(
  supabase: SupabaseClient,
  job: LeasedJob,
  fetchFn: typeof fetch = fetch,
  options?: NotificationDeliveryOptions,
): Promise<{
  jobId: string;
  result: string;
  statusCode?: number;
  error?: string;
}> {
  const now = Date.now();
  const dueTime = new Date(job.interpreted_due_at).getTime();

  // Guard against malformed interpreted_due_at
  if (isNaN(dueTime)) {
    await recordResult(supabase, job.job_id, job.lease_token, "expired");
    return { jobId: job.job_id, result: "expired" };
  }

  const graceWindowMs = job.missed_delivery_enabled ? 3600000 : 120000;
  const deadline = dueTime + graceWindowMs;

  // 1. Re-validate job preconditions
  if (job.task_completed_at !== null) {
    await recordResult(supabase, job.job_id, job.lease_token, "cancelled");
    return { jobId: job.job_id, result: "cancelled" };
  }

  if (now > deadline) {
    await recordResult(supabase, job.job_id, job.lease_token, "expired");
    return { jobId: job.job_id, result: "expired" };
  }

  if (
    !job.is_active ||
    !job.local_enabled ||
    job.permission_state !== "granted" ||
    !job.endpoint
  ) {
    await recordResult(supabase, job.job_id, job.lease_token, "cancelled");
    return { jobId: job.job_id, result: "cancelled" };
  }

  // Android installations deliver through FCM notification messages; the
  // endpoint holds the Operator-bound FCM registration token and web push
  // encryption/VAPID requirements do not apply.
  if (job.platform === "android") {
    return deliverFcmNotification(supabase, job, fetchFn, options?.fcm);
  }

  // Validate endpoint format (SSRF protection)
  if (!job.endpoint.startsWith("https://")) {
    await recordResult(
      supabase,
      job.job_id,
      job.lease_token,
      "permanent_failure",
    );
    return { jobId: job.job_id, result: "permanent_failure" };
  }

  // 2. Prepare payload - strictly task title and routing identifiers
  const payload: PushPayload = {
    taskId: job.task_id,
    occurrenceKey: job.occurrence_key,
    title: job.task_title,
  };
  const jsonPayload = JSON.stringify(payload);

  const ttlSeconds = job.missed_delivery_enabled
    ? Math.max(0, Math.floor((dueTime + 3600000 - now) / 1000))
    : 0;

  const topic = await deriveTopic(job.occurrence_key, job.task_id);

  const headers: Record<string, string> = {
    TTL: String(ttlSeconds),
    Urgency: "high",
    Topic: topic,
  };

  // Optional VAPID Authorization header - fail closed if keys provided but header generation fails
  const vapidPrivateKey = options?.vapidPrivateKey;
  const vapidPublicKey = options?.vapidPublicKey;

  if (Boolean(vapidPrivateKey) !== Boolean(vapidPublicKey)) {
    console.error(
      `Incomplete VAPID configuration for job ${job.job_id}: exactly one VAPID key provided`,
    );
    await recordResult(supabase, job.job_id, job.lease_token, "cancelled");
    return {
      jobId: job.job_id,
      result: "cancelled",
      error: "Incomplete VAPID configuration",
    };
  }

  if (vapidPrivateKey && vapidPublicKey) {
    const vapidAuth = await generateVapidHeader(
      job.endpoint,
      vapidPrivateKey,
      vapidPublicKey,
      options?.vapidSubject,
    );
    if (!vapidAuth) {
      console.error(
        `Failed to generate VAPID header for job ${job.job_id}: invalid VAPID configuration or key`,
      );
      await recordResult(supabase, job.job_id, job.lease_token, "cancelled");
      return {
        jobId: job.job_id,
        result: "cancelled",
        error: "VAPID header generation failed",
      };
    }
    headers["Authorization"] = vapidAuth;
  }

  // Web Push requires encrypted payload (RFC 8291 aes128gcm) - fail permanently if encryption not possible
  if (!job.p256dh || !job.auth) {
    console.error(
      `Missing push encryption keys (p256dh/auth) for job ${job.job_id}`,
    );
    await recordResult(
      supabase,
      job.job_id,
      job.lease_token,
      "permanent_failure",
    );
    return {
      jobId: job.job_id,
      result: "permanent_failure",
      error: "Missing push encryption keys",
    };
  }

  const encrypted = await encryptWebPushPayload(
    jsonPayload,
    job.p256dh,
    job.auth,
  );
  if (!encrypted) {
    console.error(`Failed to encrypt Web Push payload for job ${job.job_id}`);
    await recordResult(
      supabase,
      job.job_id,
      job.lease_token,
      "permanent_failure",
    );
    return {
      jobId: job.job_id,
      result: "permanent_failure",
      error: "Failed to encrypt Web Push payload",
    };
  }

  headers["Content-Type"] = "application/octet-stream";
  headers["Content-Encoding"] = "aes128gcm";
  const body = encrypted as unknown as BodyInit;

  try {
    const response = await fetchFn(job.endpoint, {
      method: "POST",
      headers,
      body,
      signal: AbortSignal.timeout(10000),
    });

    if (
      response.status === 200 ||
      response.status === 201 ||
      response.status === 202
    ) {
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "delivered",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "delivered",
        statusCode: response.status,
      };
    } else if (isEndpointGoneStatus(response.status)) {
      // Endpoint gone / unsubscribed (404, 410) - deactivates installation
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "permanent_failure",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "permanent_failure",
        statusCode: response.status,
      };
    } else if (isNonRetryableStatus(response.status)) {
      // Non-retryable request/configuration rejection (400, 401, 403, 413) - cancels job without deactivating installation
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "cancelled",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "cancelled",
        statusCode: response.status,
      };
    } else {
      // Transient failure (5xx, 429, etc.)
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "transient_failure",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "transient_failure",
        statusCode: response.status,
      };
    }
  } catch (err) {
    console.error(
      `Push request failed for job ${job.job_id} to endpoint ${job.endpoint}:`,
      err,
    );
    try {
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "transient_failure",
      );
    } catch (recErr) {
      console.error(
        `Failed to record transient failure for job ${job.job_id}:`,
        recErr,
      );
      return {
        jobId: job.job_id,
        result: "error",
        error: recErr instanceof Error ? recErr.message : String(recErr),
      };
    }
    return { jobId: job.job_id, result: "transient_failure" };
  }
}

async function deliverFcmNotification(
  supabase: SupabaseClient,
  job: LeasedJob,
  fetchFn: typeof fetch,
  fcm?: FcmOptions,
): Promise<{
  jobId: string;
  result: string;
  statusCode?: number;
  error?: string;
}> {
  const fcmProjectId = fcm?.projectId;
  const fcmClientEmail = fcm?.clientEmail;
  const fcmPrivateKey = fcm?.privateKey;

  if (!fcmProjectId || !fcmClientEmail || !fcmPrivateKey) {
    console.error(
      `Incomplete FCM configuration for job ${job.job_id}: projectId, clientEmail, and privateKey are required`,
    );
    await recordResult(supabase, job.job_id, job.lease_token, "cancelled");
    return {
      jobId: job.job_id,
      result: "cancelled",
      error: "Incomplete FCM configuration",
    };
  }

  const now = Date.now();
  const dueTime = new Date(job.interpreted_due_at).getTime();
  const ttlSeconds = job.missed_delivery_enabled
    ? Math.max(0, Math.floor((dueTime + 3600000 - now) / 1000))
    : 0;

  // Provider retention mirrors the web TTL contract; the notification message
  // carries only the Task title plus opaque routing identifiers and replaces
  // any prior display through the stable occurrence identity tag.
  const message = buildFcmMessage(job, ttlSeconds);

  let accessToken: string;
  try {
    accessToken = await getFcmAccessToken(fcm!, fetchFn);
  } catch (err) {
    console.error(
      `Failed to obtain FCM OAuth token for job ${job.job_id}:`,
      err,
    );
    try {
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "transient_failure",
      );
    } catch (recErr) {
      console.error(
        `Failed to record transient failure for job ${job.job_id}:`,
        recErr,
      );
      return {
        jobId: job.job_id,
        result: "error",
        error: recErr instanceof Error ? recErr.message : String(recErr),
      };
    }
    return { jobId: job.job_id, result: "transient_failure" };
  }

  const sendUrl = `https://fcm.googleapis.com/v1/projects/${fcmProjectId}/messages:send`;

  try {
    const response = await fetchFn(sendUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json; UTF-8",
      },
      body: JSON.stringify(message),
      signal: AbortSignal.timeout(10000),
    });

    if (response.status >= 200 && response.status < 300) {
      // Provider acceptance is recorded as delivered, but display is not proven.
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "delivered",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "delivered",
        statusCode: response.status,
      };
    }

    if (response.status === 401) {
      // The provider rejected the cached access token; force a fresh
      // exchange on the next attempt instead of poisoning following jobs.
      fcmTokenCache.delete(fcmClientEmail);
    }

    let errorCode: string | undefined;
    try {
      const bodyText = await response.text();
      const parsed = JSON.parse(bodyText) as {
        error?: {
          status?: string;
          details?: Array<{ errorCode?: string }>;
        };
      };
      // v1 reports unregistered tokens through the nested FcmError detail,
      // not the transport status, so prefer it over the generic gRPC status.
      errorCode =
        parsed.error?.details?.find((detail) => detail.errorCode !== undefined)
          ?.errorCode ?? parsed.error?.status;
    } catch {
      errorCode = undefined;
    }

    const classification = classifyFcmSendFailure(response.status, errorCode);

    if (classification === "permanent_failure") {
      // Permanent provider rejection disables the endpoint and cancels its jobs.
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "permanent_failure",
        response.status,
      );
      return {
        jobId: job.job_id,
        result: "permanent_failure",
        statusCode: response.status,
      };
    }

    await recordResult(
      supabase,
      job.job_id,
      job.lease_token,
      classification,
      response.status,
    );
    return {
      jobId: job.job_id,
      result: classification,
      statusCode: response.status,
    };
  } catch (err) {
    console.error(`FCM send failed for job ${job.job_id}:`, err);
    try {
      await recordResult(
        supabase,
        job.job_id,
        job.lease_token,
        "transient_failure",
      );
    } catch (recErr) {
      console.error(
        `Failed to record transient failure for job ${job.job_id}:`,
        recErr,
      );
      return {
        jobId: job.job_id,
        result: "error",
        error: recErr instanceof Error ? recErr.message : String(recErr),
      };
    }
    return { jobId: job.job_id, result: "transient_failure" };
  }
}
