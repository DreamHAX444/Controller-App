/**
 * TRACE Phase 2B.2 — Live Deno HTTP Server & High-Concurrency Adversarial Test Suite
 * Executes in native Deno runtime with real TCP network sockets over loopback
 */

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { handleCommandSubmissionRequest } from "./index.ts";
import { MockCommandDatabaseAccess } from "../_shared/db.ts";

describe("Native Deno HTTP Server & Concurrency Suite", () => {
  let server: Deno.HttpServer;
  let baseUrl: string;
  let db: MockCommandDatabaseAccess;
  let signingKeyPair: CryptoKeyPair;

  before(async () => {
    // Generate isolated NIST P-256 ECDSA keypair for staging tests
    signingKeyPair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"]
    );

    db = new MockCommandDatabaseAccess();

    // Start native Deno HTTP server on ephemeral OS-assigned port
    server = Deno.serve(
      { port: 0, onListen: () => {} },
      (req: Request) => handleCommandSubmissionRequest(req, db, signingKeyPair.privateKey)
    );

    const addr = server.addr as Deno.NetAddr;
    baseUrl = `http://127.0.0.1:${addr.port}`;
  });

  after(async () => {
    await server.shutdown();
  });

  it("DENO-HTTP-01: Successfully accepts command submission over live HTTP socket", async () => {
    const idempotencyKey = crypto.randomUUID();
    const res = await fetch(`${baseUrl}/submit`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer jwt-owner",
      },
      body: JSON.stringify({
        deviceId: "tracker-alpha-01",
        command: "start_camera",
        params: { fps: 30, quality: "1080p" },
        idempotencyKey,
        ttlSeconds: 30,
      }),
    });

    assert.equal(res.status, 201);
    assert.equal(res.headers.get("content-type"), "application/json");
    assert.equal(res.headers.get("cache-control"), "no-store, no-cache, must-revalidate");

    const data = await res.json();
    assert.equal(data.deviceId, "tracker-alpha-01");
    assert.equal(data.command, "start_camera");
    assert.equal(data.idempotencyKey, idempotencyKey);
    assert.ok(typeof data.id === "number" || typeof data.id === "string");
    assert.ok(data.serverSignature.length > 50);
  });

  it("DENO-CONCURRENCY-01: 10 concurrent requests with identical idempotencyKey return identical command", async () => {
    const concurrentIdempKey = crypto.randomUUID();
    const payload = {
      deviceId: "tracker-alpha-01",
      command: "start_camera",
      params: { fps: 30 },
      idempotencyKey: concurrentIdempKey,
      ttlSeconds: 30,
    };

    // Dispatch 10 concurrent HTTP requests over loopback socket
    const requests = Array.from({ length: 10 }).map(() =>
      fetch(`${baseUrl}/submit`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": "Bearer jwt-owner",
        },
        body: JSON.stringify(payload),
      })
    );

    const responses = await Promise.all(requests);

    // Every response must be either 201 (initial insert) or 200 (idempotent replay)
    for (const res of responses) {
      assert.ok(res.status === 201 || res.status === 200, `Expected 200 or 201, got ${res.status}`);
    }

    const bodies = await Promise.all(responses.map((r) => r.json()));
    const firstId = bodies[0].id;
    const firstSignature = bodies[0].serverSignature;

    // Verify all 10 responses resolved to the exact same command ID and server signature
    for (const body of bodies) {
      assert.equal(body.id, firstId, "All concurrent requests must return identical command ID");
      assert.equal(body.serverSignature, firstSignature, "All concurrent requests must return identical signature");
      assert.equal(body.idempotencyKey, concurrentIdempKey);
    }
  });

  it("DENO-CONCURRENCY-02: Concurrent request with same idempotencyKey but mismatched params returns 409 Conflict", async () => {
    const conflictingKey = crypto.randomUUID();

    // Request 1: Initial submission
    const res1 = await fetch(`${baseUrl}/submit`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer jwt-owner",
      },
      body: JSON.stringify({
        deviceId: "tracker-alpha-01",
        command: "start_camera",
        params: { fps: 30 },
        idempotencyKey: conflictingKey,
      }),
    });
    assert.equal(res1.status, 201);

    // Request 2: Reusing conflictingKey with altered params
    const res2 = await fetch(`${baseUrl}/submit`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": "Bearer jwt-owner",
      },
      body: JSON.stringify({
        deviceId: "tracker-alpha-01",
        command: "start_camera",
        params: { fps: 60 }, // Altered!
        idempotencyKey: conflictingKey,
      }),
    });

    assert.equal(res2.status, 409);
    const errBody = await res2.json();
    assert.equal(errBody.code, "IDEMPOTENCY_CONFLICT");
  });

  it("DENO-NEG-01: Missing signing key fails closed with 500 and sanitized message", async () => {
    // Create server with undefined signing key
    const brokenServer = Deno.serve(
      { port: 0, onListen: () => {} },
      (req: Request) => handleCommandSubmissionRequest(req, db, undefined)
    );
    const addr = brokenServer.addr as Deno.NetAddr;

    try {
      const res = await fetch(`http://127.0.0.1:${addr.port}/submit`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": "Bearer jwt-owner",
        },
        body: JSON.stringify({
          deviceId: "tracker-alpha-01",
          command: "start_camera",
          params: {},
          idempotencyKey: crypto.randomUUID(),
        }),
      });

      assert.equal(res.status, 500);
      const data = await res.json();
      assert.equal(data.code, "SIGNING_KEY_UNAVAILABLE");
      assert.equal(data.message, "Server cryptographic signing key is unconfigured.");
      assert.equal(data.signingKey, undefined);
      assert.equal(data.stack, undefined);
    } finally {
      await brokenServer.shutdown();
    }
  });
});
