/**
 * TRACE Phase 2B — Cryptographic Protocol Implementation
 * Cross-Runtime WebCrypto (Deno & Node.js 18+)
 * RFC 8785 JSON Canonicalization, NIST ECDSA P-256 Signing, IEEE P1363 <-> ASN.1/DER Bridge
 */

export interface PreimageFields {
  commandId: number | bigint;
  deviceId: string;
  commandName: string;
  canonicalJsonParams: string;
  issuedAtEpochSec: number | bigint;
  expiresAtEpochSec: number | bigint;
  idempotencyKey: string;
}

const WIRE_HEADER = "TRACE-CMD-V1";

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) serializer.
 * - Object keys lexicographically sorted by UTF-16 code units.
 * - Zero whitespace around ':' and ','.
 * - Recursively processes nested objects and arrays.
 */
export function canonicalizeJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return "[" + value.map((item) => canonicalizeJson(item)).join(",") + "]";
  }
  const obj = value as Record<string, unknown>;
  const sortedKeys = Object.keys(obj).sort();
  const entries = sortedKeys.map(
    (k) => `${JSON.stringify(k)}:${canonicalizeJson(obj[k])}`
  );
  return "{" + entries.join(",") + "}";
}

/**
 * Formats the authoritative 8-line deterministic wire preimage (LF delimited, UTF-8).
 * Exactly matches Android CommandCryptoUtil.formatCanonicalPreimage().
 */
export function formatCanonicalPreimage(fields: PreimageFields): string {
  return [
    WIRE_HEADER,
    fields.commandId.toString(),
    fields.deviceId,
    fields.commandName,
    fields.canonicalJsonParams || "{}",
    fields.issuedAtEpochSec.toString(),
    fields.expiresAtEpochSec.toString(),
    fields.idempotencyKey,
  ].join("\n");
}

export const ACK_WIRE_HEADER = "TRACE-ACK-V1";

export interface AckPreimageFields {
  commandId: number | bigint;
  deviceId: string;
  idempotencyKey: string;
  status: string;
  timestampEpochSec: number | bigint;
  resultDigest?: string;
}

export function formatCanonicalAckPreimage(fields: AckPreimageFields): string {
  return [
    ACK_WIRE_HEADER,
    fields.commandId.toString(),
    fields.deviceId,
    fields.idempotencyKey,
    fields.status.toLowerCase(),
    fields.timestampEpochSec.toString(),
    fields.resultDigest || "",
  ].join("\n");
}

/**
 * Computes SHA-256 digest using standard WebCrypto.
 */
export async function computeSha256(preimage: string): Promise<Uint8Array> {
  const enc = new TextEncoder();
  const digestBuffer = await crypto.subtle.digest("SHA-256", enc.encode(preimage));
  return new Uint8Array(digestBuffer);
}

/**
 * Computes hex-encoded SHA-256 digest for verification against reference vectors.
 */
export async function computeSha256Hex(preimage: string): Promise<string> {
  const digest = await computeSha256(preimage);
  return Array.from(digest)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Converts raw 64-byte IEEE P1363 signature (WebCrypto) to ASN.1/DER sequence (Android JCA).
 * Format: 0x30 [len] 0x02 [r_len] [r] 0x02 [s_len] [s]
 */
export function p1363ToDer(p1363: Uint8Array): Uint8Array {
  if (p1363.length !== 64) {
    throw new Error(`P1363 signature must be exactly 64 bytes, got ${p1363.length}`);
  }
  const r = p1363.subarray(0, 32);
  const s = p1363.subarray(32, 64);

  function encodeInteger(bytes: Uint8Array): Uint8Array {
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) start++;
    const trimmed = bytes.subarray(start);
    const needsPad = (trimmed[0] & 0x80) !== 0;
    const len = trimmed.length + (needsPad ? 1 : 0);
    const out = new Uint8Array(2 + len);
    out[0] = 0x02; // INTEGER tag
    out[1] = len;
    if (needsPad) {
      out[2] = 0x00;
      out.set(trimmed, 3);
    } else {
      out.set(trimmed, 2);
    }
    return out;
  }

  const rDer = encodeInteger(r);
  const sDer = encodeInteger(s);
  const totalLen = rDer.length + sDer.length;
  const der = new Uint8Array(2 + totalLen);
  der[0] = 0x30; // SEQUENCE tag
  der[1] = totalLen;
  der.set(rDer, 2);
  der.set(sDer, 2 + rDer.length);
  return der;
}

/**
 * Converts ASN.1/DER sequence signature to raw 64-byte IEEE P1363.
 */
export function derToP1363(der: Uint8Array): Uint8Array {
  if (der[0] !== 0x30) throw new Error("Invalid DER sequence tag: expected 0x30");
  let offset = 2;
  if (der[offset] !== 0x02) throw new Error("Expected INTEGER tag 0x02 for R");
  const rLen = der[offset + 1];
  const rBytes = der.subarray(offset + 2, offset + 2 + rLen);
  offset += 2 + rLen;

  if (der[offset] !== 0x02) throw new Error("Expected INTEGER tag 0x02 for S");
  const sLen = der[offset + 1];
  const sBytes = der.subarray(offset + 2, offset + 2 + sLen);

  const p1363 = new Uint8Array(64);
  const rTrimmed = rBytes.length > 32 ? rBytes.subarray(rBytes.length - 32) : rBytes;
  const sTrimmed = sBytes.length > 32 ? sBytes.subarray(sBytes.length - 32) : sBytes;

  p1363.set(rTrimmed, 32 - rTrimmed.length);
  p1363.set(sTrimmed, 64 - sTrimmed.length);
  return p1363;
}

/**
 * Signs the canonical wire preimage with a NIST P-256 ECDSA private key.
 * Returns Base64 of either ASN.1/DER (default) or raw IEEE P1363.
 */
export async function signPreimage(
  privateKey: CryptoKey,
  preimage: string,
  format: "der" | "p1363" = "der"
): Promise<string> {
  const enc = new TextEncoder();
  const rawSig = await crypto.subtle.sign(
    { name: "ECDSA", hash: { name: "SHA-256" } },
    privateKey,
    enc.encode(preimage)
  );
  const p1363Bytes = new Uint8Array(rawSig);
  const outputBytes = format === "der" ? p1363ToDer(p1363Bytes) : p1363Bytes;
  return uint8ArrayToBase64(outputBytes);
}

/**
 * Verifies NIST P-256 ECDSA signature over canonical wire preimage.
 * Automatically accepts both ASN.1/DER and raw 64-byte IEEE P1363 signatures.
 */
export async function verifyPreimage(
  publicKey: CryptoKey,
  preimage: string,
  signatureBase64: string
): Promise<boolean> {
  const sigBytes = base64ToUint8Array(signatureBase64);
  const p1363Bytes = sigBytes.length === 64 ? sigBytes : derToP1363(sigBytes);
  const enc = new TextEncoder();
  return await crypto.subtle.verify(
    { name: "ECDSA", hash: { name: "SHA-256" } },
    publicKey,
    p1363Bytes as unknown as BufferSource,
    enc.encode(preimage) as unknown as BufferSource
  );
}

/**
 * Constant-time comparison for sensitive byte sequences.
 */
export function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff === 0;
}

export function uint8ArrayToBase64(bytes: Uint8Array): string {
  if (typeof Buffer !== "undefined") {
    return Buffer.from(bytes).toString("base64");
  }
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export function base64ToUint8Array(base64: string): Uint8Array {
  if (typeof Buffer !== "undefined") {
    return new Uint8Array(Buffer.from(base64, "base64"));
  }
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}
