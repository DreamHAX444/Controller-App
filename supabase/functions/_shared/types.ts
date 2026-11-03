/**
 * TRACE Phase 2B — Edge Function Domain Types and Contracts
 * Matches Android Controller DTOs (CommandSubmissionDtos.kt)
 */

export type CallerRole = "OWNER" | "ADMIN" | "USER" | "VISITOR";
export type AccountStatus = "active" | "pending" | "suspended" | "disabled" | "unknown";

export type ServicePermission =
  | "LOCATION"
  | "CAMERA"
  | "MICROPHONE_AUDIO"
  | "DEVICE_AUDIO"
  | "SCREEN_CAPTURE"
  | "FILE_READ"
  | "FILE_WRITE"
  | "FILE_DELETE"
  | "FILE_RENAME"
  | "FILE_TRANSFER";

export interface AuthenticatedCaller {
  userId: string;
  email: string | null;
  role: CallerRole;
  accountStatus: AccountStatus;
}

export interface DeviceRecord {
  id: string;
  lifecycle: string;
  ownerId?: string | null;
}

export interface UserDeviceAssignment {
  userId: string;
  deviceId: string;
  allowedServices: ServicePermission[];
}

/**
 * Android Controller Request DTO
 * Corresponds to CommandSubmissionRequest in CommandSubmissionDtos.kt
 */
export interface CommandSubmissionRequest {
  deviceId: string;
  command: string;
  params?: Record<string, unknown> | null;
  idempotencyKey: string;
  ttlSeconds?: number;
}

/**
 * Raw wire format received from JSON over HTTP
 * Supports both camelCase and snake_case for interoperability
 */
export interface RawCommandSubmissionPayload {
  deviceId?: string;
  device_id?: string;
  command?: string;
  params?: Record<string, unknown> | null;
  idempotencyKey?: string;
  idempotency_key?: string;
  ttlSeconds?: number;
  ttl_seconds?: number;
}

/**
 * Android Controller Response DTO
 * Corresponds to CommandSubmissionResponse in CommandSubmissionDtos.kt
 */
export interface CommandSubmissionResponse {
  id: number;
  deviceId: string;
  command: string;
  params: Record<string, unknown> | null;
  status: string;
  keyId: string;
  serverSignature: string;
  issuedAtEpochSec: number;
  expiresAtEpochSec: number;
  idempotencyKey: string;
  createdAt: string;
}

export interface StoredCommandRecord {
  id: number;
  device_id: string;
  command: string;
  params: Record<string, unknown> | null;
  status: string;
  key_id: string;
  server_signature: string;
  issued_at_epoch_sec: number;
  expires_at_epoch_sec: number;
  idempotency_key: string;
  created_at: string;
  issuer_user_id?: string | null;
}

export interface AuthError {
  httpStatus: number;
  errorCode: string;
  message: string;
}

/**
 * Allowlist mapping normalized wire command verbs to required service permissions
 */
export const COMMAND_SERVICE_MAP: Readonly<Record<string, ServicePermission>> = {
  request_location: "LOCATION",
  start_camera: "CAMERA",
  stop_camera: "CAMERA",
  start_audio: "MICROPHONE_AUDIO",
  stop_audio: "MICROPHONE_AUDIO",
  start_screen: "SCREEN_CAPTURE",
  stop_screen: "SCREEN_CAPTURE",
  list_files: "FILE_READ",
};
