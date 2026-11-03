/**
 * TRACE Phase 2B — Edge Function Comprehensive Test Suite
 * Validates Authentication, Authorization, RFC 8785 WebCrypto, Idempotency, and Threat Vectors
 * Engine: node --experimental-strip-types --test (Zero External Dependencies)
 */

import { describe, it, before, beforeEach } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";

import {
  handleCommandSubmissionRequest,
  handleCommandAcknowledgementRequest,
  mapToResponseDto,
} from "./index.ts";
import type { BroadcastSender } from "./index.ts";
import { MockCommandDatabaseAccess } from "../_shared/db.ts";
import {
  canonicalizeJson,
  formatCanonicalPreimage,
  formatCanonicalAckPreimage,
  computeSha256Hex,
  signPreimage,
  verifyPreimage,
  p1363ToDer,
  derToP1363,
} from "../_shared/crypto.ts";
import type { CommandSubmissionResponse, StoredCommandRecord } from "../_shared/types.ts";

const SPEC_PREIMAGE_SHA256 = "157b1ac9f9792b7052e5d95c3ec8c8112797e193d43d40db2ffcede6b10599cb";
const TEST_KEY_ID = "trace-srv-ecdsa-p256-v1";

describe("TRACE Phase 2B Edge Function Gateway & Cryptographic Security", () => {
  let db: MockCommandDatabaseAccess;
  let signingPrivateKey: CryptoKey;
  let verificationPublicKey: CryptoKey;
  let serverPublicKeyPem: string;

  before(async () => {
    // Generate authentic NIST ECDSA P-256 keypair via WebCrypto
    const keyPair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"]
    );
    signingPrivateKey = keyPair.privateKey;
    verificationPublicKey = keyPair.publicKey;
  });

  beforeEach(() => {
    db = new MockCommandDatabaseAccess();
  });

  // Helper to execute request against handler
  async function executeRequest(
    method: string,
    body: any,
    authHeader?: string
  ): Promise<{ status: number; body: any; headers: Headers }> {
    const headers = new Headers();
    headers.set("Content-Type", "application/json");
    if (authHeader) headers.set("Authorization", authHeader);

    const req = new Request("http://localhost:54321/functions/v1/commands/submit", {
      method,
      headers,
      body: body !== undefined ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
    });

    const res = await handleCommandSubmissionRequest(req, db, signingPrivateKey, TEST_KEY_ID);
    const text = await res.text();
    let parsedBody: any;
    try {
      parsedBody = JSON.parse(text);
    } catch {
      parsedBody = text;
    }
    return {
      status: res.status,
      body: parsedBody,
      headers: res.headers,
    };
  }

  describe("1. Cryptographic Wire Preimage & Reference Vectors", () => {
    it("TM-CRYPTO-01: Reference test vector SHA-256 matches Section 6.1 specification", async () => {
      const preimage = formatCanonicalPreimage({
        commandId: 1042,
        deviceId: "tracker-alpha-01",
        commandName: "take_photo",
        canonicalJsonParams: '{"camera":"back","flash":false,"quality":85}',
        issuedAtEpochSec: 1776345600,
        expiresAtEpochSec: 1776345630,
        idempotencyKey: "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234",
      });

      const hashHex = await computeSha256Hex(preimage);
      assert.equal(
        hashHex,
        SPEC_PREIMAGE_SHA256,
        "Preimage SHA-256 hex must match test vector 157b1ac9... byte-for-byte"
      );
    });

    it("TM-CRYPTO-02: RFC 8785 canonicalizeJson sorts keys and strips whitespace", () => {
      const input = {
        quality: 85,
        flash: false,
        camera: "back",
        nested: { z: 1, a: 2 },
      };
      const canonical = canonicalizeJson(input);
      assert.equal(
        canonical,
        '{"camera":"back","flash":false,"nested":{"a":2,"z":1},"quality":85}'
      );
    });

    it("TM-CRYPTO-03: Generated P-256 signature in ASN.1/DER verifies cleanly", async () => {
      const preimage = "TRACE-CMD-V1\n1001\ntracker-01\nrequest_location\n{}\n1776345600\n1776345900\n" + crypto.randomUUID();
      const derSignatureBase64 = await signPreimage(signingPrivateKey, preimage, "der");

      const derBytes = Buffer.from(derSignatureBase64, "base64");
      assert.equal(derBytes[0], 0x30, "DER signature must begin with SEQUENCE header 0x30");
      assert.ok(derBytes.length >= 68 && derBytes.length <= 73, `DER length ${derBytes.length} in range 68..73`);

      const isValid = await verifyPreimage(verificationPublicKey, preimage, derSignatureBase64);
      assert.equal(isValid, true, "Valid preimage signature must verify against public key");
    });

    it("TM-CRYPTO-04: Tampered preimage fails signature verification", async () => {
      const preimage = "TRACE-CMD-V1\n1001\ntracker-01\nrequest_location\n{}\n1776345600\n1776345900\n" + crypto.randomUUID();
      const derSignatureBase64 = await signPreimage(signingPrivateKey, preimage, "der");

      const tamperedPreimage = preimage.replace("1001", "1002");
      const isValid = await verifyPreimage(verificationPublicKey, tamperedPreimage, derSignatureBase64);
      assert.equal(isValid, false, "Tampered preimage must be rejected");
    });

    it("TM-CRYPTO-05: IEEE P1363 to ASN.1/DER bridge converts bi-directionally", () => {
      const rawP1363 = new Uint8Array(64);
      rawP1363.fill(0x01, 0, 32); // R
      rawP1363.fill(0x02, 32, 64); // S

      const der = p1363ToDer(rawP1363);
      assert.equal(der[0], 0x30);

      const recovered = derToP1363(der);
      assert.deepEqual(recovered, rawP1363);
    });

    it("TM-CRYPTO-06: Extended RFC 8785 canonical JSON with control characters, UTF-16 key sorting, and nested arrays matches reference digest", async () => {
      const complexObject = {
        z: true,
        "1": "numeric key",
        a: [
          {
            c: {
              d: 'val"with"quote\\and\\slash\nand\nnewline\t\b\f\u0000\u001f',
            },
            b: 1,
          },
        ],
        _: null,
        A: "uppercase",
        aa: "double a",
      };

      const canonical = canonicalizeJson(complexObject);
      const expectedCanonical = '{"1":"numeric key","A":"uppercase","_":null,"a":[{"b":1,"c":{"d":"val\\"with\\"quote\\\\and\\\\slash\\nand\\nnewline\\t\\b\\f\\u0000\\u001f"}}],"aa":"double a","z":true}';
      assert.equal(canonical, expectedCanonical, "Canonical JSON string must match RFC 8785 rules");

      const hashHex = await computeSha256Hex(canonical);
      assert.equal(
        hashHex,
        "9d4dfef2a455ac61377d47f932d71028883be7c581e1510ad4774ddaaeaa470c",
        "Extended canonical JSON SHA-256 digest must match cross-language reference hash"
      );
    });
  });

  describe("2. Authentication Enforcement", () => {
    it("TM-AUTH-01: Rejects missing Authorization header with 401", async () => {
      const res = await executeRequest("POST", {
        deviceId: "tracker-alpha-01",
        command: "request_location",
        idempotencyKey: crypto.randomUUID(),
      });
      assert.equal(res.status, 401);
      assert.equal(res.body.code, "MISSING_AUTHORIZATION_HEADER");
    });

    it("TM-AUTH-02: Rejects non-Bearer Authorization scheme with 401", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Basic dXNlcjpwYXNz"
      );
      assert.equal(res.status, 401);
      assert.equal(res.body.code, "MALFORMED_AUTHORIZATION_SCHEME");
    });

    it("TM-AUTH-03: Rejects empty Bearer token with 401", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer   "
      );
      assert.equal(res.status, 401);
      assert.equal(res.body.code, "EMPTY_BEARER_TOKEN");
    });

    it("TM-AUTH-04: Rejects unknown / unauthenticated JWT with 401", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer invalid-or-forged-jwt"
      );
      assert.equal(res.status, 401);
      assert.equal(res.body.code, "INVALID_OR_EXPIRED_JWT");
    });

    it("TM-AUTH-05: Strictly rejects VISITOR role with 403 Forbidden", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-visitor"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "VISITOR_ACCESS_DENIED");
      assert.match(res.body.message, /Visitor role is restricted/i);
    });

    it("TM-AUTH-06: Rejects suspended user account with 403 Forbidden", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-suspended"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "ACCOUNT_NOT_ACTIVE");
    });
  });

  describe("3. Authorization & Permissions Enforcement", () => {
    it("TM-AUTHZ-01: Rejects user without device assignment with 403", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-user-unassigned"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "DEVICE_NOT_ASSIGNED");
    });

    it("TM-AUTHZ-02: Rejects user command when feature permission is not granted", async () => {
      // jwt-user-assigned only has CAMERA and LOCATION granted on tracker-alpha-01
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "start_audio", // requires MICROPHONE_AUDIO
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-user-assigned"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "FEATURE_PERMISSION_DENIED");
    });

    it("TM-AUTHZ-03: Allows user command when feature permission IS granted", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "start_camera", // requires CAMERA (granted)
          params: { facing: "back", fps: 30 },
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-user-assigned"
      );
      assert.equal(res.status, 201);
      assert.equal(res.body.command, "start_camera");
      assert.equal(res.body.status, "pending");
    });

    it("TM-AUTHZ-04: Allows OWNER to command owned fleet device", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 201);
      assert.equal(res.body.deviceId, "tracker-alpha-01");
    });

    it("TM-AUTHZ-05: Rejects command against decommissioned device with 403", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-decommissioned",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "DEVICE_INACTIVE");
    });

    it("TM-AUTHZ-06: Rejects command against non-existent device with 403", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "non-existent-device-id",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 403);
      assert.equal(res.body.code, "DEVICE_ACCESS_DENIED");
    });

    it("TM-AUTHZ-07: Rejects unknown / disallowed command name with 400", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "wipe_device_flash",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "UNSUPPORTED_COMMAND");
    });
  });

  describe("4. Input Validation & Parameter Boundaries", () => {
    it("TM-INP-01: Rejects non-POST methods with 405 Method Not Allowed", async () => {
      const res = await executeRequest("GET", undefined, "Bearer jwt-owner");
      assert.equal(res.status, 405);
      assert.equal(res.body.code, "METHOD_NOT_ALLOWED");
    });

    it("TM-INP-02: Rejects malformed JSON body with 400", async () => {
      const res = await executeRequest("POST", '{"unclosed_json: true', "Bearer jwt-owner");
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "MALFORMED_JSON");
    });

    it("TM-INP-03: Rejects missing deviceId with 400", async () => {
      const res = await executeRequest(
        "POST",
        {
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "MISSING_DEVICE_ID");
    });

    it("TM-INP-04: Rejects missing command with 400", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "MISSING_COMMAND");
    });

    it("TM-INP-05: Rejects non-UUIDv4 idempotencyKey with 400", async () => {
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: "simple-string-not-uuid",
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "INVALID_IDEMPOTENCY_KEY");
    });

    it("TM-INP-06: Rejects out-of-range ttlSeconds with 400", async () => {
      for (const ttl of [-10, 0, 4, 301, 3600]) {
        const res = await executeRequest(
          "POST",
          {
            deviceId: "tracker-alpha-01",
            command: "request_location",
            idempotencyKey: crypto.randomUUID(),
            ttlSeconds: ttl,
          },
          "Bearer jwt-owner"
        );
        assert.equal(res.status, 400, `Expected 400 for TTL: ${ttl}`);
        assert.equal(res.body.code, "INVALID_TTL");
      }
    });

    it("TM-INP-07: Rejects non-object params (arrays or primitives) with 400", async () => {
      for (const badParam of ["string", 42, true, [1, 2, 3]]) {
        const res = await executeRequest(
          "POST",
          {
            deviceId: "tracker-alpha-01",
            command: "request_location",
            idempotencyKey: crypto.randomUUID(),
            params: badParam,
          },
          "Bearer jwt-owner"
        );
        assert.equal(res.status, 400);
        assert.equal(res.body.code, "INVALID_PARAMS");
      }
    });

    it("TM-INP-08: Rejects params exceeding 4096 bytes with 400", async () => {
      const hugeString = "X".repeat(5000);
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
          params: { data: hugeString },
        },
        "Bearer jwt-owner"
      );
      assert.equal(res.status, 400);
      assert.equal(res.body.code, "PARAMS_TOO_LARGE");
    });
  });

  describe("5. Idempotency & Replay Protection", () => {
    it("TM-IDEMP-01: Exact duplicate replay returns original record with HTTP 200", async () => {
      const testIdempotencyKey = crypto.randomUUID();
      const payload = {
        deviceId: "tracker-alpha-01",
        command: "start_camera",
        params: { facing: "front", quality: "1080p" },
        idempotencyKey: testIdempotencyKey,
      };

      // 1. Initial submission -> 201 Created
      const res1 = await executeRequest("POST", payload, "Bearer jwt-owner");
      assert.equal(res1.status, 201);
      const firstId = res1.body.id;
      const firstSig = res1.body.serverSignature;

      // 2. Duplicate submission -> 200 OK with identical record
      const res2 = await executeRequest("POST", payload, "Bearer jwt-owner");
      assert.equal(res2.status, 200);
      assert.equal(res2.body.id, firstId);
      assert.equal(res2.body.serverSignature, firstSig);
      assert.equal(res2.body.issuedAtEpochSec, res1.body.issuedAtEpochSec);
    });

    it("TM-IDEMP-02: Idempotency key reuse with mismatched parameters returns 409 Conflict", async () => {
      const testIdempotencyKey = crypto.randomUUID();

      // Initial submission
      await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "start_camera",
          params: { facing: "back" },
          idempotencyKey: testIdempotencyKey,
        },
        "Bearer jwt-owner"
      );

      // Reused with different params
      const resConflict = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "start_camera",
          params: { facing: "front" }, // mutated!
          idempotencyKey: testIdempotencyKey,
        },
        "Bearer jwt-owner"
      );

      assert.equal(resConflict.status, 409);
      assert.equal(resConflict.body.code, "IDEMPOTENCY_CONFLICT");
      assert.match(resConflict.body.message, /mismatched command parameters/i);
    });

    it("TM-IDEMP-03: Concurrency collision fallback returns winning record", async () => {
      const testKey = crypto.randomUUID();
      // Pre-seed the record in DB as if another concurrent thread committed it
      await db.insertSignedCommand({
        id: 2001,
        device_id: "tracker-alpha-01",
        command: "request_location",
        params: {},
        idempotency_key: testKey,
        status: "pending",
        key_id: TEST_KEY_ID,
        server_signature: "mock-sig",
        issued_at_epoch_sec: 1776345600,
        expires_at_epoch_sec: 1776345900,
        issuer_user_id: "user-owner-uuid",
      });

      // Force simulated unique constraint violation on insert
      db.simulateUniqueViolationOnInsert = true;

      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          params: {},
          idempotencyKey: testKey,
        },
        "Bearer jwt-owner"
      );

      assert.equal(res.status, 200);
      assert.equal(res.body.id, 2001);
    });
  });

  describe("6. Android DTO Compatibility & Contract Verification", () => {
    it("TM-DTO-01: Response body strictly matches Kotlin CommandSubmissionResponse shape", async () => {
      const testKey = crypto.randomUUID();
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          params: { accuracy: "high" },
          idempotencyKey: testKey,
          ttlSeconds: 60,
        },
        "Bearer jwt-owner"
      );

      assert.equal(res.status, 201);
      const response: CommandSubmissionResponse = res.body;

      // Validate exact camelCase properties
      assert.ok(typeof response.id === "number");
      assert.equal(response.deviceId, "tracker-alpha-01");
      assert.equal(response.command, "request_location");
      assert.deepEqual(response.params, { accuracy: "high" });
      assert.equal(response.status, "pending");
      assert.equal(response.keyId, TEST_KEY_ID);
      assert.ok(typeof response.serverSignature === "string");
      assert.ok(typeof response.issuedAtEpochSec === "number");
      assert.equal(response.expiresAtEpochSec, response.issuedAtEpochSec + 60);
      assert.equal(response.idempotencyKey, testKey);
      assert.ok(typeof response.createdAt === "string");

      // Verify the returned signature is cryptographically valid against the public key
      const canonicalParams = canonicalizeJson(response.params);
      const preimage = formatCanonicalPreimage({
        commandId: response.id,
        deviceId: response.deviceId,
        commandName: response.command,
        canonicalJsonParams: canonicalParams,
        issuedAtEpochSec: response.issuedAtEpochSec,
        expiresAtEpochSec: response.expiresAtEpochSec,
        idempotencyKey: response.idempotencyKey,
      });

      const isSigValid = await verifyPreimage(
        verificationPublicKey,
        preimage,
        response.serverSignature
      );
      assert.equal(isSigValid, true, "Response signature must verify against public key");
    });
  });

  describe("7. Information Leakage Prevention", () => {
    it("TM-LEAK-01: Database failure returns sanitized 500 without leaking credentials or stack traces", async () => {
      db.simulateDbDown = true;
      const res = await executeRequest(
        "POST",
        {
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: crypto.randomUUID(),
        },
        "Bearer jwt-owner"
      );

      assert.equal(res.status, 500);
      const bodyStr = JSON.stringify(res.body);
      assert.doesNotMatch(bodyStr, /postgres:\/\//i);
      assert.doesNotMatch(bodyStr, /connection refused/i);
      assert.doesNotMatch(bodyStr, /at MockCommandDatabaseAccess/i);
      assert.doesNotMatch(bodyStr, /service_role/i);
      assert.equal(res.body.code, "INTERNAL_ERROR");
    });
  });

  describe("8. Realtime Command Delivery Bridge", () => {
    it("TM-BRIDGE-01: Dispatches broadcast to public:commands after successful command insertion", async () => {
      let broadcastCalled = false;
      let broadcastPayload: any = null;

      const mockBroadcastSender: BroadcastSender = async (
        url,
        key,
        record
      ) => {
        broadcastCalled = true;
        broadcastPayload = mapToResponseDto(record);
        return { success: true };
      };

      const testKey = crypto.randomUUID();
      const req = new Request("http://localhost:54321/functions/v1/commands/submit", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer jwt-owner",
        },
        body: JSON.stringify({
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: testKey,
          params: { priority: "urgent" },
        }),
      });

      const res = await handleCommandSubmissionRequest(
        req,
        db,
        signingPrivateKey,
        TEST_KEY_ID,
        mockBroadcastSender
      );

      assert.equal(res.status, 201);
      assert.equal(broadcastCalled, true, "Broadcast sender must be invoked");
      assert.equal(broadcastPayload.idempotencyKey, testKey);
      assert.equal(broadcastPayload.deviceId, "tracker-alpha-01");
      assert.equal(broadcastPayload.command, "request_location");
      assert.deepEqual(broadcastPayload.params, { priority: "urgent" });
      assert.equal(broadcastPayload.status, "pending");
    });

    it("TM-BRIDGE-02: Broadcast network failure does not fail the command or lose DB record (returns 201)", async () => {
      const failingBroadcastSender: BroadcastSender = async () => {
        return { success: false, error: "Network timeout connecting to realtime broadcast endpoint" };
      };

      const testKey = crypto.randomUUID();
      const req = new Request("http://localhost:54321/functions/v1/commands/submit", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer jwt-owner",
        },
        body: JSON.stringify({
          deviceId: "tracker-alpha-01",
          command: "request_location",
          idempotencyKey: testKey,
        }),
      });

      const res = await handleCommandSubmissionRequest(
        req,
        db,
        signingPrivateKey,
        TEST_KEY_ID,
        failingBroadcastSender
      );

      // Must succeed with 201 despite broadcast transport error
      assert.equal(res.status, 201);
      const resBody = await res.json();
      assert.equal(resBody.idempotencyKey, testKey);

      // Verify the command is safely committed in DB for polling recovery
      const saved = await db.findCommandByIdempotencyKey(testKey);
      assert.ok(saved);
      assert.equal(saved.status, "pending");
      assert.equal(saved.id, resBody.id);
    });

    it("TM-BRIDGE-03: Pending duplicate replay re-attempts broadcast", async () => {
      let broadcastCallCount = 0;
      const countingBroadcastSender: BroadcastSender = async () => {
        broadcastCallCount++;
        return { success: true };
      };

      const testKey = crypto.randomUUID();
      const makeReq = () =>
        new Request("http://localhost:54321/functions/v1/commands/submit", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: "Bearer jwt-owner",
          },
          body: JSON.stringify({
            deviceId: "tracker-alpha-01",
            command: "request_location",
            idempotencyKey: testKey,
          }),
        });

      // First call (creates and broadcasts)
      const res1 = await handleCommandSubmissionRequest(
        makeReq(),
        db,
        signingPrivateKey,
        TEST_KEY_ID,
        countingBroadcastSender
      );
      assert.equal(res1.status, 201);
      assert.equal(broadcastCallCount, 1);

      // Second call (idempotent replay of pending command)
      const res2 = await handleCommandSubmissionRequest(
        makeReq(),
        db,
        signingPrivateKey,
        TEST_KEY_ID,
        countingBroadcastSender
      );
      assert.equal(res2.status, 200);
      assert.equal(broadcastCallCount, 2, "Duplicate replay of pending command must re-attempt broadcast");
    });
  });

  describe("9. Authenticated Command Acknowledgements (TRACE-ACK-V1)", () => {
    let devicePrivateKey: CryptoKey;
    let devicePublicKeyPem: string;

    before(async () => {
      // Generate device ECDSA P-256 key pair
      const keyPair = await crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,
        ["sign", "verify"]
      );
      devicePrivateKey = keyPair.privateKey;

      const spkiDer = await crypto.subtle.exportKey("spki", keyPair.publicKey);
      const base64Spki = Buffer.from(spkiDer).toString("base64");
      devicePublicKeyPem = `-----BEGIN PUBLIC KEY-----\n${base64Spki}\n-----END PUBLIC KEY-----`;
    });

    async function executeAck(
      body: any
    ): Promise<{ status: number; body: any }> {
      const req = new Request("http://localhost:54321/functions/v1/commands/acknowledge", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: typeof body === "string" ? body : JSON.stringify(body),
      });
      const res = await handleCommandAcknowledgementRequest(req, db);
      const text = await res.text();
      let parsed: any;
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = text;
      }
      return { status: res.status, body: parsed };
    }

    async function signAckPreimage(
      fields: {
        commandId: number;
        deviceId: string;
        idempotencyKey: string;
        status: string;
        timestampEpochSec: number;
        resultDigest: string;
      },
      key: CryptoKey = devicePrivateKey
    ): Promise<string> {
      const preimage = formatCanonicalAckPreimage(fields);
      const p1363Sig = new Uint8Array(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          key,
          new TextEncoder().encode(preimage)
        )
      );
      return Buffer.from(p1363ToDer(p1363Sig)).toString("base64");
    }

    it("TM-ACK-01: Valid signed ACK transitions command to executed (HTTP 200)", async () => {
      // 1. Enroll device
      db.enrolledDevices.set("tracker-alpha-01", {
        publicKey: devicePublicKeyPem,
        isRevoked: false,
      });

      // 2. Insert command in pending state
      const cmdId = await db.allocateCommandId();
      const idempotencyKey = crypto.randomUUID();
      await db.insertSignedCommand({
        id: cmdId,
        device_id: "tracker-alpha-01",
        command: "request_location",
        params: {},
        status: "pending",
        idempotency_key: idempotencyKey,
        key_id: TEST_KEY_ID,
        server_signature: "sig",
        issued_at_epoch_sec: Math.floor(Date.now() / 1000) - 10,
        expires_at_epoch_sec: Math.floor(Date.now() / 1000) + 290,
      });

      // 3. Create valid signed ACK
      const nowSec = Math.floor(Date.now() / 1000);
      const signature = await signAckPreimage({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        resultDigest: "",
      });

      const res = await executeAck({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        resultDigest: "",
        deviceSignature: signature,
      });

      assert.equal(res.status, 200);
      assert.equal(res.body.status, "SUCCESS");
      assert.equal(res.body.commandId, cmdId);
      assert.equal(res.body.newStatus, "executed");

      // Verify DB state updated
      const updated = await db.findCommandByIdempotencyKey(idempotencyKey);
      assert.equal(updated?.status, "executed");
    });

    it("TM-ACK-02: Forged signature returns 401 SIGNATURE_VERIFICATION_FAILED", async () => {
      db.enrolledDevices.set("tracker-alpha-01", {
        publicKey: devicePublicKeyPem,
        isRevoked: false,
      });

      const cmdId = await db.allocateCommandId();
      const idempotencyKey = crypto.randomUUID();
      await db.insertSignedCommand({
        id: cmdId,
        device_id: "tracker-alpha-01",
        command: "request_location",
        params: {},
        status: "pending",
        idempotency_key: idempotencyKey,
        key_id: TEST_KEY_ID,
        server_signature: "sig",
        issued_at_epoch_sec: Math.floor(Date.now() / 1000) - 10,
        expires_at_epoch_sec: Math.floor(Date.now() / 1000) + 290,
      });

      // Sign with an unauthorized adversary key
      const rogueKeyPair = await crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,
        ["sign", "verify"]
      );
      const nowSec = Math.floor(Date.now() / 1000);
      const rogueSignature = await signAckPreimage(
        {
          commandId: cmdId,
          deviceId: "tracker-alpha-01",
          idempotencyKey,
          status: "executed",
          timestampEpochSec: nowSec,
          resultDigest: "",
        },
        rogueKeyPair.privateKey
      );

      const res = await executeAck({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        deviceSignature: rogueSignature,
      });

      assert.equal(res.status, 401);
      assert.equal(res.body.code, "SIGNATURE_VERIFICATION_FAILED");

      // DB status must remain unchanged (pending)
      const unChanged = await db.findCommandByIdempotencyKey(idempotencyKey);
      assert.equal(unChanged?.status, "pending");
    });

    it("TM-ACK-03: Unenrolled or revoked device returns 403 UNAUTHORIZED_DEVICE", async () => {
      // 1. Unenrolled device
      const nowSec = Math.floor(Date.now() / 1000);
      const resUnenrolled = await executeAck({
        commandId: 999,
        deviceId: "tracker-unknown-99",
        idempotencyKey: crypto.randomUUID(),
        status: "executed",
        timestampEpochSec: nowSec,
        deviceSignature: "dummySig",
      });
      assert.equal(resUnenrolled.status, 403);
      assert.equal(resUnenrolled.body.code, "UNAUTHORIZED_DEVICE");

      // 2. Revoked device
      db.enrolledDevices.set("tracker-revoked-01", {
        publicKey: devicePublicKeyPem,
        isRevoked: true,
      });
      const resRevoked = await executeAck({
        commandId: 999,
        deviceId: "tracker-revoked-01",
        idempotencyKey: crypto.randomUUID(),
        status: "executed",
        timestampEpochSec: nowSec,
        deviceSignature: "dummySig",
      });
      assert.equal(resRevoked.status, 403);
      assert.equal(resRevoked.body.code, "UNAUTHORIZED_DEVICE");
    });

    it("TM-ACK-04: Expired timestamp returns 400 ACK_EXPIRED", async () => {
      db.enrolledDevices.set("tracker-alpha-01", {
        publicKey: devicePublicKeyPem,
        isRevoked: false,
      });

      // 301 seconds old (outside 300s window)
      const expiredTimestamp = Math.floor(Date.now() / 1000) - 301;
      const res = await executeAck({
        commandId: 1042,
        deviceId: "tracker-alpha-01",
        idempotencyKey: crypto.randomUUID(),
        status: "executed",
        timestampEpochSec: expiredTimestamp,
        deviceSignature: "dummySig",
      });

      assert.equal(res.status, 400);
      assert.equal(res.body.code, "ACK_EXPIRED");
    });

    it("TM-ACK-05: Terminal-state regression returns 409 TERMINAL_STATE_REGRESSION", async () => {
      db.enrolledDevices.set("tracker-alpha-01", {
        publicKey: devicePublicKeyPem,
        isRevoked: false,
      });

      const cmdId = await db.allocateCommandId();
      const idempotencyKey = crypto.randomUUID();
      await db.insertSignedCommand({
        id: cmdId,
        device_id: "tracker-alpha-01",
        command: "request_location",
        params: {},
        status: "executed", // Already reached terminal executed
        idempotency_key: idempotencyKey,
        key_id: TEST_KEY_ID,
        server_signature: "sig",
        issued_at_epoch_sec: Math.floor(Date.now() / 1000) - 10,
        expires_at_epoch_sec: Math.floor(Date.now() / 1000) + 290,
      });

      const nowSec = Math.floor(Date.now() / 1000);
      // Attempt to regress terminal executed command to failed
      const regressedSig = await signAckPreimage({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "failed",
        timestampEpochSec: nowSec,
        resultDigest: "",
      });

      const res = await executeAck({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "failed",
        timestampEpochSec: nowSec,
        deviceSignature: regressedSig,
      });

      assert.equal(res.status, 409);
      assert.equal(res.body.code, "TERMINAL_STATE_REGRESSION");

      // Idempotent duplicate of identical executed state returns 200 ALREADY_ACKNOWLEDGED
      const duplicateSig = await signAckPreimage({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        resultDigest: "",
      });
      const resDup = await executeAck({
        commandId: cmdId,
        deviceId: "tracker-alpha-01",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        deviceSignature: duplicateSig,
      });
      assert.equal(resDup.status, 200);
      assert.equal(resDup.body.status, "ALREADY_ACKNOWLEDGED");
    });

    it("TM-ACK-06: Cross-device spoofing returns 403 CROSS_DEVICE_ACK_DENIED", async () => {
      // tracker-beta enrolled
      db.enrolledDevices.set("tracker-beta-02", {
        publicKey: devicePublicKeyPem,
        isRevoked: false,
      });

      // Command was issued for tracker-alpha-01
      const cmdId = await db.allocateCommandId();
      const idempotencyKey = crypto.randomUUID();
      await db.insertSignedCommand({
        id: cmdId,
        device_id: "tracker-alpha-01",
        command: "request_location",
        params: {},
        status: "pending",
        idempotency_key: idempotencyKey,
        key_id: TEST_KEY_ID,
        server_signature: "sig",
        issued_at_epoch_sec: Math.floor(Date.now() / 1000) - 10,
        expires_at_epoch_sec: Math.floor(Date.now() / 1000) + 290,
      });

      const nowSec = Math.floor(Date.now() / 1000);
      // tracker-beta attempts to acknowledge tracker-alpha's command
      const spoofSig = await signAckPreimage({
        commandId: cmdId,
        deviceId: "tracker-beta-02",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        resultDigest: "",
      });

      const res = await executeAck({
        commandId: cmdId,
        deviceId: "tracker-beta-02",
        idempotencyKey,
        status: "executed",
        timestampEpochSec: nowSec,
        deviceSignature: spoofSig,
      });

      assert.equal(res.status, 403);
      assert.equal(res.body.code, "CROSS_DEVICE_ACK_DENIED");
    });
  });
});

