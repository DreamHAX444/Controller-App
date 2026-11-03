/**
 * TRACE V1 — Local Development Command Gateway
 *
 * Runs locally on host machine (accessible by emulator at http://10.0.2.2:54321)
 * to service POST /functions/v1/commands/submit and POST /functions/v1/commands/acknowledge
 * using the development NIST P-256 ECDSA signing key (.keys/dev_server_signing_key.jwk.json)
 * and broadcasting authenticated commands to Supabase Realtime (public:commands).
 */

import {
  canonicalizeJson,
  formatCanonicalPreimage,
  signPreimage,
} from "../supabase/functions/_shared/crypto.ts";

const PORT = 54321;
const anonKey =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imp5aXFocXhqb2FobHhmbGFhdGVkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODU0MzIwMjksImV4cCI6MjEwMTAwODAyOX0.Fel6E89P2A-RIhoHv3LYra1ycPxPZG8nYiet8IhDRzg";
const supabaseUrl = "https://jyiqhqxjoahlxflaated.supabase.co";

console.log("[DevGateway] Loading dev_server_signing_key.jwk.json...");
const keyContent = await Deno.readTextFile(".keys/dev_server_signing_key.jwk.json");
const jwk = JSON.parse(keyContent);

const privateKey = await crypto.subtle.importKey(
  "jwk",
  jwk,
  { name: "ECDSA", namedCurve: "P-256" },
  false,
  ["sign"]
);

let monotonicSequence = Date.now();

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, apikey, Content-Type, Content-Profile, Prefer, X-Client-Info",
};

async function broadcastToSupabase(payload: any) {
  const broadcastUrl = `${supabaseUrl}/realtime/v1/api/broadcast`;
  try {
    const res = await fetch(broadcastUrl, {
      method: "POST",
      headers: {
        apikey: anonKey,
        Authorization: `Bearer ${anonKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        messages: [
          {
            topic: "public:commands",
            event: "command",
            payload,
          },
        ],
      }),
    });
    console.log(`[DevGateway] Realtime broadcast sent (status: ${res.status})`);
    return res.status === 200 || res.status === 202;
  } catch (err: any) {
    console.error(`[DevGateway] Realtime broadcast failed: ${err.message}`);
    return false;
  }
}

async function handleRequest(req: Request): Promise<Response> {
  const url = new URL(req.url);

  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }

  if (req.method === "POST" && url.pathname.endsWith("/commands/submit")) {
    try {
      const body = await req.json();
      const deviceId = body.device_id || body.deviceId;
      const command = body.command;
      const params = body.params || null;
      const idempotencyKey = body.idempotency_key || body.idempotencyKey || crypto.randomUUID();
      const cmdId = ++monotonicSequence;
      const nowSec = Math.floor(Date.now() / 1000);
      const issuedAt = nowSec;
      const expiresAt = nowSec + 300;

      const canonicalParams = params ? canonicalizeJson(params) : "{}";
      const preimage = formatCanonicalPreimage({
        commandId: cmdId,
        deviceId,
        commandName: command,
        canonicalJsonParams: canonicalParams,
        issuedAtEpochSec: issuedAt,
        expiresAtEpochSec: expiresAt,
        idempotencyKey,
      });

      const signature = await signPreimage(privateKey, preimage, "der");

      const responsePayload = {
        id: cmdId,
        deviceId,
        device_id: deviceId,
        command,
        params,
        idempotencyKey,
        idempotency_key: idempotencyKey,
        issuedAtEpochSec: issuedAt,
        issued_at_epoch_sec: issuedAt,
        expiresAtEpochSec: expiresAt,
        expires_at_epoch_sec: expiresAt,
        keyId: "trace-srv-ecdsa-p256-v1",
        key_id: "trace-srv-ecdsa-p256-v1",
        serverSignature: signature,
        server_signature: signature,
        status: "pending",
        createdAt: new Date().toISOString(),
        created_at: new Date().toISOString(),
      };

      console.log(`[DevGateway] Processing command '${command}' for '${deviceId}' (ID: ${cmdId}, key: ${idempotencyKey})`);

      // Persist to Supabase commands table so queryServerCommandStatus succeeds
      try {
        let parsedParams = null;
        if (params) {
          try {
            parsedParams = typeof params === "string" ? JSON.parse(params) : params;
          } catch (_) {
            parsedParams = { raw: params };
          }
        }
        await fetch(`${supabaseUrl}/rest/v1/commands`, {
          method: "POST",
          headers: {
            apikey: anonKey,
            Authorization: `Bearer ${anonKey}`,
            "Content-Type": "application/json",
            Prefer: "return=minimal",
          },
          body: JSON.stringify({
            id: cmdId,
            device_id: deviceId,
            command,
            params: parsedParams,
            status: "pending",
            idempotency_key: idempotencyKey,
            created_at: new Date().toISOString(),
            key_id: "trace-srv-ecdsa-p256-v1",
            server_signature: signature,
            issued_at_epoch_sec: issuedAt,
            expires_at_epoch_sec: expiresAt,
          }),
        });
        console.log(`[DevGateway] Command ${cmdId} persisted to Supabase database (pending)`);
      } catch (dbErr: any) {
        console.warn(`[DevGateway] Warning: failed to persist command to database: ${dbErr.message}`);
      }

      // Broadcast to Tracker via Supabase Realtime
      await broadcastToSupabase(responsePayload);

      return new Response(JSON.stringify(responsePayload), {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json",
        },
      });
    } catch (e: any) {
      console.error(`[DevGateway] Error processing submit: ${e.message}`);
      return new Response(JSON.stringify({ error: e.message }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
  }

  if (req.method === "POST" && url.pathname.endsWith("/commands/acknowledge")) {
    try {
      const ack = await req.json();
      const cmdId = ack.commandId || ack.command_id;
      const status = ack.status || "executed";
      const idKey = ack.idempotencyKey || ack.idempotency_key;
      console.log(`[DevGateway] ACK received for command ${cmdId} (${status}) from ${ack.deviceId || ack.device_id}`);

      try {
        const filter = cmdId ? `id=eq.${cmdId}` : (idKey ? `idempotency_key=eq.${idKey}` : null);
        if (filter) {
          const patchRes = await fetch(`${supabaseUrl}/rest/v1/commands?${filter}`, {
            method: "PATCH",
            headers: {
              apikey: anonKey,
              Authorization: `Bearer ${anonKey}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              status: status,
            }),
          });
          console.log(`[DevGateway] Database updated for command ${cmdId || idKey} -> ${status} (status: ${patchRes.status})`);
        }
      } catch (patchErr: any) {
        console.warn(`[DevGateway] Warning: failed to update command status in database: ${patchErr.message}`);
      }

      return new Response(
        JSON.stringify({
          status: "SUCCESS",
          commandId: cmdId,
          newStatus: status,
        }),
        {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    } catch (e: any) {
      return new Response(JSON.stringify({ error: e.message }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
  }

  return new Response("Not Found", { status: 404, headers: corsHeaders });
}

console.log(`[DevGateway] TRACE V1 Local Command Gateway running on http://0.0.0.0:${PORT}`);
Deno.serve({ port: PORT, hostname: "0.0.0.0" }, handleRequest);
