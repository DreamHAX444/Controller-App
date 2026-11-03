/**
 * TRACE Phase 7 — Development Server Signing Key Generator
 *
 * Generates a standard NIST P-256 (secp256r1) ECDSA keypair for local development,
 * emulator testing, and Edge Function command signing.
 *
 * Outputs:
 *  - .keys/dev_server_signing_key.jwk.json (private JWK, gitignored)
 *  - .keys/dev_server_signing_key.pub.json (public key SPKI base64 & metadata, gitignored)
 *  - .keys/dev_server_signing_key.env      (environment variable exports, gitignored)
 *
 * STRICT SAFETY NOTICE:
 * This script generates keys strictly for local development and testing.
 * Production keys MUST be provisioned through secure hardware/KMS and must NEVER
 * be committed to source control or distributed in client application packages.
 */

import { existsSync } from "https://deno.land/std@0.224.0/fs/exists.ts";
import * as path from "https://deno.land/std@0.224.0/path/mod.ts";

const CANONICAL_KEY_ID = "trace-srv-ecdsa-p256-v1";
const KEYS_DIR = path.resolve(".keys");
const PRIVATE_JWK_PATH = path.join(KEYS_DIR, "dev_server_signing_key.jwk.json");
const PUBLIC_JSON_PATH = path.join(KEYS_DIR, "dev_server_signing_key.pub.json");
const ENV_FILE_PATH = path.join(KEYS_DIR, "dev_server_signing_key.env");

async function main() {
  const forceRegenerate = Deno.args.includes("--force") || Deno.args.includes("--regenerate");

  if (!existsSync(KEYS_DIR)) {
    await Deno.mkdir(KEYS_DIR, { recursive: true });
  }

  if (existsSync(PRIVATE_JWK_PATH) && !forceRegenerate) {
    console.log(`[INFO] Existing development signing key found at ${PRIVATE_JWK_PATH}.`);
    console.log(`[INFO] Use --regenerate or --force to create a fresh keypair.`);
    const existingJwk = JSON.parse(await Deno.readTextFile(PRIVATE_JWK_PATH));
    
    // Import to verify and derive SPKI
    const importedPriv = await crypto.subtle.importKey(
      "jwk",
      existingJwk,
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign"]
    );

    // Reconstruct public key JWK
    const publicJwk: JsonWebKey = {
      kty: "EC",
      crv: "P-256",
      x: existingJwk.x,
      y: existingJwk.y,
      ext: true,
      key_ops: ["verify"],
    };
    const importedPub = await crypto.subtle.importKey(
      "jwk",
      publicJwk,
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["verify"]
    );

    const spkiBuffer = await crypto.subtle.exportKey("spki", importedPub);
    const spkiBase64 = btoa(String.fromCharCode(...new Uint8Array(spkiBuffer)));

    displayKeyDetails(existingJwk, spkiBase64, false);
    return;
  }

  console.log(`[INFO] Generating new NIST P-256 ECDSA keypair for key ID '${CANONICAL_KEY_ID}'...`);

  const keyPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );

  const rawPrivateJwk = await crypto.subtle.exportKey("jwk", keyPair.privateKey);
  const privateJwk = {
    ...rawPrivateJwk,
    kid: CANONICAL_KEY_ID,
    _purpose: "DEVELOPMENT_ONLY - DO NOT USE IN PRODUCTION",
    _generated_at: new Date().toISOString(),
  };

  const spkiBuffer = await crypto.subtle.exportKey("spki", keyPair.publicKey);
  const spkiBase64 = btoa(String.fromCharCode(...new Uint8Array(spkiBuffer)));

  const publicInfo = {
    keyId: CANONICAL_KEY_ID,
    algorithm: "NIST P-256 (secp256r1) ECDSA with SHA-256",
    spkiBase64,
    x509Format: "X.509 SubjectPublicKeyInfo (DER) Base64",
    purpose: "DEVELOPMENT_ONLY - DO NOT USE IN PRODUCTION",
    generatedAt: privateJwk._generated_at,
  };

  const envContent = [
    `# TRACE V1 Development Signing Key Environment Exports`,
    `# STRICTLY FOR LOCAL DEVELOPMENT / TESTING - DO NOT USE IN PRODUCTION`,
    `SERVER_ECDSA_KEY_ID="${CANONICAL_KEY_ID}"`,
    `SERVER_ECDSA_PRIVATE_KEY_JWK='${JSON.stringify(rawPrivateJwk)}'`,
    `COMMAND_SIGNING_PRIVATE_KEY_JWK='${JSON.stringify(rawPrivateJwk)}'`,
    `SERVER_ECDSA_PUBLIC_KEY_SPKI_BASE64="${spkiBase64}"`,
    ``,
  ].join("\n");

  await Deno.writeTextFile(PRIVATE_JWK_PATH, JSON.stringify(privateJwk, null, 2));
  await Deno.writeTextFile(PUBLIC_JSON_PATH, JSON.stringify(publicInfo, null, 2));
  await Deno.writeTextFile(ENV_FILE_PATH, envContent);

  console.log(`[SUCCESS] Generated development signing keypair.`);
  displayKeyDetails(privateJwk, spkiBase64, true);
}

function displayKeyDetails(jwk: Record<string, unknown>, spkiBase64: string, isNew: boolean) {
  console.log(`\n================================================================`);
  console.log(`  TRACE V1 DEVELOPMENT SIGNING KEY (${isNew ? "NEWLY GENERATED" : "LOADED FROM DISK"})`);
  console.log(`================================================================`);
  console.log(`Key ID:             ${CANONICAL_KEY_ID}`);
  console.log(`Algorithm:          ECDSA with P-256 (secp256r1) / SHA256withECDSA`);
  console.log(`Classification:     DEVELOPMENT_ONLY (DO NOT USE IN PRODUCTION)`);
  console.log(`Private JWK File:   ${PRIVATE_JWK_PATH}`);
  console.log(`Public Info File:   ${PUBLIC_JSON_PATH}`);
  console.log(`Env File:           ${ENV_FILE_PATH}`);
  console.log(`----------------------------------------------------------------`);
  console.log(`DERIVED PUBLIC KEY (SPKI Base64 - for PinnedKeyProvider):`);
  console.log(`${spkiBase64}`);
  console.log(`----------------------------------------------------------------`);
  console.log(`PRIVATE KEY JWK (Compact - for Edge Function env):`);
  console.log(JSON.stringify({
    kty: jwk.kty,
    alg: jwk.alg,
    crv: jwk.crv,
    d: jwk.d,
    x: jwk.x,
    y: jwk.y,
    key_ops: ["sign"],
    ext: true,
  }));
  console.log(`================================================================\n`);
}

if (import.meta.main) {
  await main();
}
