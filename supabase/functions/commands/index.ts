/**
 * TRACE Phase 2B — Supabase Edge Function Command Gateway
 * Serves POST /functions/v1/commands/submit
 * Full implementation: Authentication, Authorization, RFC 8785 canonicalization,
 * NIST ECDSA P-256 signing, Idempotency, and Fail-Closed Security.
 */

import type {
  CommandSubmissionResponse,
  RawCommandSubmissionPayload,
  StoredCommandRecord,
} from "../_shared/types.ts";
import {
  canonicalizeJson,
  formatCanonicalPreimage,
  formatCanonicalAckPreimage,
  signPreimage,
  verifyPreimage,
} from "../_shared/crypto.ts";
import {
  authenticateCaller,
  authorizeCommandSubmission,
} from "../_shared/auth.ts";
import {
  SupabaseDatabaseAccess,
} from "../_shared/db.ts";
import type {
  ICommandDatabaseAccess,
} from "../_shared/db.ts";

const UUID_V4_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DEFAULT_KEY_ID = "trace-srv-ecdsa-p256-v1";
const MAX_PARAMS_BYTE_LENGTH = 4096;

export type BroadcastSender = (
  supabaseUrl: string,
  serviceRoleKey: string,
  record: StoredCommandRecord,
  fetchImpl?: typeof fetch
) => Promise<{ success: boolean; error?: string }>;

export async function defaultBroadcastSender(
  supabaseUrl: string,
  serviceRoleKey: string,
  record: StoredCommandRecord,
  fetchImpl: typeof fetch = fetch
): Promise<{ success: boolean; error?: string }> {
  if (!supabaseUrl || !serviceRoleKey) {
    return { success: false, error: "Missing Supabase configuration" };
  }
  try {
    const broadcastUrl = `${supabaseUrl.replace(/\/+$/, "")}/realtime/v1/api/broadcast`;
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timeoutId = controller ? setTimeout(() => controller.abort(), 3000) : null;

    const response = await fetchImpl(broadcastUrl, {
      method: "POST",
      headers: {
        apikey: serviceRoleKey,
        Authorization: `Bearer ${serviceRoleKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        messages: [
          {
            topic: "public:commands",
            event: "command",
            payload: mapToResponseDto(record),
          },
        ],
      }),
      signal: controller ? controller.signal : undefined,
    });
    if (timeoutId) clearTimeout(timeoutId);

    if (!response.ok) {
      const errText = await response.text();
      return { success: false, error: `Realtime broadcast HTTP ${response.status}: ${errText}` };
    }
    return { success: true };
  } catch (err: any) {
    return { success: false, error: err.message || "Broadcast network error" };
  }
}

/**
 * Maps StoredCommandRecord to Android-compatible CommandSubmissionResponse DTO.
 */
export function mapToResponseDto(
  record: StoredCommandRecord
): CommandSubmissionResponse {
  return {
    id: record.id,
    deviceId: record.device_id,
    command: record.command,
    params: record.params,
    status: record.status,
    keyId: record.key_id,
    serverSignature: record.server_signature,
    issuedAtEpochSec: record.issued_at_epoch_sec,
    expiresAtEpochSec: record.expires_at_epoch_sec,
    idempotencyKey: record.idempotency_key,
    createdAt: record.created_at,
  };
}

/**
 * Creates standardized, sanitized JSON error responses without leaking internal state.
 */
function createErrorResponse(
  status: number,
  errorCode: string,
  message: string
): Response {
  return new Response(
    JSON.stringify({
      error: status >= 500 ? "Internal Server Error" : status >= 400 && status < 500 ? "Client Error" : "Error",
      code: errorCode,
      message,
    }),
    {
      status,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "X-Content-Type-Options": "nosniff",
      },
    }
  );
}

/**
 * Main HTTP Request Handler for Command Submission.
 */
export async function handleCommandSubmissionRequest(
  req: Request,
  db?: ICommandDatabaseAccess,
  signingKey?: CryptoKey,
  keyId: string = DEFAULT_KEY_ID,
  broadcastSender?: BroadcastSender
): Promise<Response> {
  // 1. Only allow POST method
  if (req.method !== "POST") {
    return createErrorResponse(405, "METHOD_NOT_ALLOWED", "Only POST requests are supported.");
  }

  // 2. Resolve Database Access Layer
  let database: ICommandDatabaseAccess;
  if (db) {
    database = db;
  } else {
    const supabaseUrl = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_URL") : process.env.SUPABASE_URL) || "";
    const serviceRoleKey = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") : process.env.SUPABASE_SERVICE_ROLE_KEY) || "";
    if (!supabaseUrl || !serviceRoleKey) {
      return createErrorResponse(500, "CONFIGURATION_ERROR", "Server database configuration is missing.");
    }
    database = new SupabaseDatabaseAccess(supabaseUrl, serviceRoleKey);
  }

  // 3. Resolve Server Signing Key (if not provided, import from environment JWK)
  let resolvedSigningKey = signingKey;
  if (!resolvedSigningKey) {
    const rawJwk =
      typeof Deno !== "undefined"
        ? (Deno.env.get("COMMAND_SIGNING_PRIVATE_KEY_JWK") || Deno.env.get("SERVER_ECDSA_PRIVATE_KEY_JWK"))
        : (process.env.COMMAND_SIGNING_PRIVATE_KEY_JWK || process.env.SERVER_ECDSA_PRIVATE_KEY_JWK);
    if (rawJwk) {
      try {
        const jwkObj = JSON.parse(rawJwk);
        resolvedSigningKey = await crypto.subtle.importKey(
          "jwk",
          jwkObj,
          { name: "ECDSA", namedCurve: "P-256" },
          false,
          ["sign"]
        );
      } catch {
        return createErrorResponse(
          500,
          "SIGNING_KEY_ERROR",
          "Failed to deserialize server cryptographic signing key JWK."
        );
      }
    }
  }

  try {
    // 3. Authenticate Caller
    const authHeader = req.headers.get("Authorization");
    const { caller, error: authError } = await authenticateCaller(authHeader, database);
    if (authError || !caller) {
      return createErrorResponse(
        authError?.httpStatus || 401,
        authError?.errorCode || "UNAUTHORIZED",
        authError?.message || "Authentication failed."
      );
    }

    // 4. Parse Request Body
    let rawPayload: RawCommandSubmissionPayload;
    try {
      rawPayload = await req.json();
    } catch {
      return createErrorResponse(400, "MALFORMED_JSON", "Malformed JSON request body.");
    }

    if (!rawPayload || typeof rawPayload !== "object" || Array.isArray(rawPayload)) {
      return createErrorResponse(400, "INVALID_PAYLOAD", "Request body must be a valid JSON object.");
    }

    // 5. Input Extraction & Normalization
    const deviceId = (rawPayload.deviceId || rawPayload.device_id || "").trim();
    const command = (rawPayload.command || "").trim();
    const idempotencyKey = (rawPayload.idempotencyKey || rawPayload.idempotency_key || "").trim();
    const params = rawPayload.params !== undefined && rawPayload.params !== null ? rawPayload.params : {};
    const rawTtl = rawPayload.ttlSeconds !== undefined ? rawPayload.ttlSeconds : rawPayload.ttl_seconds;
    const ttlSeconds = rawTtl !== undefined ? rawTtl : 300;

    // 6. Strict Input Validation
    if (!deviceId) {
      return createErrorResponse(400, "MISSING_DEVICE_ID", "Missing or empty required field: deviceId.");
    }
    if (!command) {
      return createErrorResponse(400, "MISSING_COMMAND", "Missing or empty required field: command.");
    }
    if (!idempotencyKey) {
      return createErrorResponse(400, "MISSING_IDEMPOTENCY_KEY", "Missing or empty required field: idempotencyKey.");
    }

    if (!UUID_V4_REGEX.test(idempotencyKey)) {
      return createErrorResponse(400, "INVALID_IDEMPOTENCY_KEY", "Invalid idempotencyKey: must be a valid UUIDv4 string.");
    }

    if (typeof ttlSeconds !== "number" || !Number.isInteger(ttlSeconds) || ttlSeconds < 5 || ttlSeconds > 300) {
      return createErrorResponse(400, "INVALID_TTL", "ttlSeconds must be an integer between 5 and 300.");
    }

    if (typeof params !== "object" || Array.isArray(params)) {
      return createErrorResponse(400, "INVALID_PARAMS", "params must be a valid JSON object.");
    }

    const canonicalParams = canonicalizeJson(params);
    const paramsByteLength = new TextEncoder().encode(canonicalParams).length;
    if (paramsByteLength > MAX_PARAMS_BYTE_LENGTH) {
      return createErrorResponse(400, "PARAMS_TOO_LARGE", `params size (${paramsByteLength} bytes) exceeds maximum limit of ${MAX_PARAMS_BYTE_LENGTH} bytes.`);
    }

    // 7. Authorize Caller & Device
    const { authorized, error: authzError } = await authorizeCommandSubmission(
      caller,
      deviceId,
      command,
      database
    );

    if (!authorized || authzError) {
      return createErrorResponse(
        authzError?.httpStatus || 403,
        authzError?.errorCode || "FORBIDDEN",
        authzError?.message || "Operation forbidden by security policy."
      );
    }

    // 8. Upfront Idempotency Check (Prevents Burning Sequence IDs)
    const existingCommand = await database.findCommandByIdempotencyKey(idempotencyKey);
    if (existingCommand) {
      // Validate that the request parameters match the existing record
      const existingParamsCanonical = canonicalizeJson(existingCommand.params || {});
      const isDeviceMatch = existingCommand.device_id === deviceId;
      const isCommandMatch = existingCommand.command === command;
      const isParamsMatch = existingParamsCanonical === canonicalParams;

      if (!isDeviceMatch || !isCommandMatch || !isParamsMatch) {
        return createErrorResponse(
          409,
          "IDEMPOTENCY_CONFLICT",
          "Idempotency key reused with mismatched command parameters."
        );
      }

      // Safe idempotent duplicate replay: Return existing committed record with HTTP 200
      // If still pending, attempt re-broadcast to recover potential lost deliveries
      if (existingCommand.status === "pending") {
        const supabaseUrl = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_URL") : process.env.SUPABASE_URL) || "";
        const serviceRoleKey = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") : process.env.SUPABASE_SERVICE_ROLE_KEY) || "";
        const broadcastFn = broadcastSender || defaultBroadcastSender;
        try {
          await broadcastFn(supabaseUrl, serviceRoleKey, existingCommand);
        } catch (_: any) {}
      }

      return new Response(JSON.stringify(mapToResponseDto(existingCommand)), {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "no-store, no-cache, must-revalidate",
        },
      });
    }

    // 9. Sequence Allocation (Atomic nextval)
    const commandId = await database.allocateCommandId();

    // 10. Cryptographic Preimage Construction & Asymmetric Signing
    const nowEpochSec = Math.floor(Date.now() / 1000);
    const expiresAtEpochSec = nowEpochSec + ttlSeconds;

    const preimage = formatCanonicalPreimage({
      commandId,
      deviceId,
      commandName: command,
      canonicalJsonParams: canonicalParams,
      issuedAtEpochSec: nowEpochSec,
      expiresAtEpochSec: expiresAtEpochSec,
      idempotencyKey,
    });

    if (!resolvedSigningKey) {
      return createErrorResponse(500, "SIGNING_KEY_UNAVAILABLE", "Server cryptographic signing key is unconfigured.");
    }

    // Sign canonical wire preimage (emits ASN.1/DER Base64 for universal compatibility)
    const serverSignature = await signPreimage(resolvedSigningKey, preimage, "der");

    // 11. Database Insertion
    const recordToInsert: Omit<StoredCommandRecord, "created_at"> = {
      id: commandId,
      device_id: deviceId,
      command,
      params,
      idempotency_key: idempotencyKey,
      status: "pending",
      key_id: keyId,
      server_signature: serverSignature,
      issued_at_epoch_sec: nowEpochSec,
      expires_at_epoch_sec: expiresAtEpochSec,
      issuer_user_id: caller.userId,
    };

    let savedRecord: StoredCommandRecord;
    try {
      savedRecord = await database.insertSignedCommand(recordToInsert);
    } catch (insertError: any) {
      // Concurrency race: if another request won the unique constraint race
      if (insertError?.code === "23505" || insertError?.message?.includes("uq_commands_idempotency_key")) {
        const winningRecord = await database.findCommandByIdempotencyKey(idempotencyKey);
        if (winningRecord) {
          return new Response(JSON.stringify(mapToResponseDto(winningRecord)), {
            status: 200,
            headers: {
              "Content-Type": "application/json",
              "Cache-Control": "no-store, no-cache, must-revalidate",
            },
          });
        }
      }
      throw insertError;
    }

    // 11b. Reliable Realtime Broadcast Bridge
    const supabaseUrl = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_URL") : process.env.SUPABASE_URL) || "";
    const serviceRoleKey = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") : process.env.SUPABASE_SERVICE_ROLE_KEY) || "";
    const broadcastFn = broadcastSender || defaultBroadcastSender;
    try {
      const broadcastResult = await broadcastFn(supabaseUrl, serviceRoleKey, savedRecord);
      if (!broadcastResult.success) {
        // Critical resilience guarantee: A broadcast failure must NOT fail the committed database record!
        // The command is safely committed in DB; tracker recovers it via slow-path polling.
        console.warn(`[WARN] Realtime broadcast failed for command ${savedRecord.id}: ${broadcastResult.error}. Command remains committed for polling recovery.`);
      }
    } catch (bErr: any) {
      console.warn(`[WARN] Unexpected exception during Realtime broadcast for command ${savedRecord.id}: ${bErr.message}`);
    }

    // 12. Return HTTP 201 Created with Android-compatible DTO
    return new Response(JSON.stringify(mapToResponseDto(savedRecord)), {
      status: 201,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "no-store, no-cache, must-revalidate",
      },
    });
  } catch (err: any) {
    console.error("GATEWAY SUBMISSION ERROR CAUGHT:", err);
    // Fail closed with sanitized error message (zero leakage of DB URLs, passwords, or stack traces)
    return createErrorResponse(
      500,
      "INTERNAL_ERROR",
      "An unexpected server error occurred while processing command submission."
    );
  }
}

/**
 * Raw wire format for Authenticated Command Acknowledgement
 */
export interface RawCommandAcknowledgementPayload {
  commandId?: number;
  command_id?: number;
  deviceId?: string;
  device_id?: string;
  idempotencyKey?: string;
  idempotency_key?: string;
  status?: string;
  timestampEpochSec?: number;
  timestamp_epoch_sec?: number;
  resultDigest?: string;
  result_digest?: string;
  deviceSignature?: string;
  device_signature?: string;
}

/**
 * Handles Authenticated Command Acknowledgement Requests:
 * POST /functions/v1/commands/acknowledge
 *
 * Enforces:
 * 1. Method POST and JSON body
 * 2. Required fields and status ('executed' or 'failed')
 * 3. Freshness window (|now - ts| <= 300s)
 * 4. Device enrollment lookup & public key retrieval
 * 5. Asymmetric ECDSA P-256 signature verification over TRACE-ACK-V1 canonical preimage
 * 6. Database state transition enforcement (anti-spoofing, anti-regression)
 */
export async function handleCommandAcknowledgementRequest(
  req: Request,
  db?: ICommandDatabaseAccess
): Promise<Response> {
  if (req.method !== "POST") {
    return createErrorResponse(405, "METHOD_NOT_ALLOWED", "Only POST requests are supported.");
  }

  let database: ICommandDatabaseAccess;
  if (db) {
    database = db;
  } else {
    const supabaseUrl = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_URL") : process.env.SUPABASE_URL) || "";
    const serviceRoleKey = (typeof Deno !== "undefined" ? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") : process.env.SUPABASE_SERVICE_ROLE_KEY) || "";
    if (!supabaseUrl || !serviceRoleKey) {
      return createErrorResponse(500, "CONFIGURATION_ERROR", "Server database configuration is missing.");
    }
    database = new SupabaseDatabaseAccess(supabaseUrl, serviceRoleKey);
  }

  let payload: RawCommandAcknowledgementPayload;
  try {
    payload = await req.json();
  } catch {
    return createErrorResponse(400, "MALFORMED_JSON", "Request body must be valid JSON.");
  }

  const commandId = payload.commandId ?? payload.command_id;
  const deviceId = payload.deviceId ?? payload.device_id;
  const idempotencyKey = payload.idempotencyKey ?? payload.idempotency_key;
  const status = (payload.status || "").toLowerCase();
  const timestampEpochSec = payload.timestampEpochSec ?? payload.timestamp_epoch_sec;
  const resultDigest = payload.resultDigest ?? payload.result_digest ?? "";
  const deviceSignature = payload.deviceSignature ?? payload.device_signature;

  if (commandId === undefined || !deviceId || !idempotencyKey || !status || timestampEpochSec === undefined || !deviceSignature) {
    return createErrorResponse(400, "MISSING_REQUIRED_FIELDS", "Missing required acknowledgement fields.");
  }

  if (status !== "executed" && status !== "failed") {
    return createErrorResponse(400, "INVALID_STATUS", "Status must be 'executed' or 'failed'.");
  }

  if (!UUID_V4_REGEX.test(idempotencyKey)) {
    return createErrorResponse(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency key must be valid UUIDv4.");
  }

  // Freshness check: 300 seconds
  const nowEpochSec = Math.floor(Date.now() / 1000);
  if (Math.abs(nowEpochSec - timestampEpochSec) > 300) {
    return createErrorResponse(400, "ACK_EXPIRED", "Acknowledgement timestamp outside validity window.");
  }

  // Retrieve enrolled device public key
  const devicePublicKeySpki = await database.getEnrolledDevicePublicKey(deviceId);
  if (!devicePublicKeySpki) {
    return createErrorResponse(403, "UNAUTHORIZED_DEVICE", "Device is not enrolled or has been revoked.");
  }

  // Construct canonical ACK preimage
  const ackPreimage = formatCanonicalAckPreimage({
    commandId,
    deviceId,
    idempotencyKey,
    status,
    timestampEpochSec,
    resultDigest,
  });

  // Verify device cryptographic signature
  const cleanSpki = devicePublicKeySpki.trim()
    .replace("-----BEGIN PUBLIC KEY-----", "")
    .replace("-----END PUBLIC KEY-----", "")
    .replace(/[\r\n]/g, "");
  const spkiBytes = Uint8Array.from(atob(cleanSpki), (c) => c.charCodeAt(0));

  let verified = false;
  try {
    const cryptoSubtle = typeof crypto !== "undefined" && crypto.subtle ? crypto.subtle : (await import("node:crypto")).webcrypto.subtle;
    const publicKey = await cryptoSubtle.importKey(
      "spki",
      spkiBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"]
    );

    verified = await verifyPreimage(publicKey, ackPreimage, deviceSignature);
  } catch (err: any) {
    return createErrorResponse(401, "SIGNATURE_VERIFICATION_FAILED", `Device signature verification error: ${err.message}`);
  }

  if (!verified) {
    return createErrorResponse(401, "SIGNATURE_VERIFICATION_FAILED", "Device cryptographic signature is invalid.");
  }

  // Apply state transition in database
  const recordResult = await database.recordAcknowledgement(
    commandId,
    deviceId,
    idempotencyKey,
    status,
    timestampEpochSec,
    resultDigest
  );

  if (!recordResult.success) {
    const errCode = recordResult.error || "RECORD_FAILED";
    const httpStatus = errCode === "COMMAND_NOT_FOUND" ? 404
      : errCode === "CROSS_DEVICE_ACK_DENIED" ? 403
      : errCode === "IDEMPOTENCY_MISMATCH" || errCode === "TERMINAL_STATE_REGRESSION" ? 409
      : 500;
    return createErrorResponse(httpStatus, errCode, `Failed to record acknowledgement: ${recordResult.error}`);
  }

  return new Response(
    JSON.stringify({
      status: recordResult.alreadyAcknowledged ? "ALREADY_ACKNOWLEDGED" : "SUCCESS",
      commandId,
      newStatus: status,
    }),
    {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "Cache-Control": "no-store, no-cache, must-revalidate",
      },
    }
  );
}

/**
 * Standard Deno Deploy serve export (when deployed to Supabase Edge Runtime).
 */
export default {
  fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname.endsWith("/acknowledge")) {
      return handleCommandAcknowledgementRequest(req);
    }
    return handleCommandSubmissionRequest(req);
  },
};

