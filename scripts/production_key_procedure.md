# TRACE V1 — Production Server Signing Key Generation & Provisioning Procedure

**Classification:** STRICT SECURITY RUNBOOK  
**Audience:** Authorized Systems Operator Only  
**Target Key ID:** `trace-srv-ecdsa-p256-v1` (or dedicated production key identifier)  

---

## 1. Security Principles & Hard Constraints

1. **Strict Key Isolation:**
   - The private JWK must **NEVER** be committed to source control (Git), placed in APK assets, embedded in client source files, printed in CI/CD logs, or included in test reports.
   - Development keys (`dev_server_signing_key.jwk.json`) must **NEVER** be uploaded to the production Supabase environment.
2. **Operator Authorization:**
   - Production key generation, secret upload, and activation require explicit, interactive operator approval.
   - No automated script may generate or upload production keys without operator sign-off.
3. **Public Key Pinning Parity:**
   - The production public key (X.509 SPKI Base64) must be pinned in both `controller_app` and `tracker_app` before production commands can be verified or executed.
   - If `PROD_SERVER_PUBLIC_KEY_SPKI_BASE64` is null or unconfigured, production verification fails closed (`EMPTY`), dropping all commands safely.

---

## 2. Production Key Generation Workflow (Offline / Air-Gapped)

When the operator decides to provision production credentials, execute the following isolated one-time generation:

```bash
# In a secure, non-recorded shell outside the repository or in an ephemeral RAM disk:
deno eval '
  const kp = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"]
  );
  const privJwk = await crypto.subtle.exportKey("jwk", kp.privateKey);
  const prodJwk = {
    ...privJwk,
    kid: "trace-srv-ecdsa-p256-v1",
    _purpose: "PRODUCTION",
    _generated_at: new Date().toISOString()
  };
  const spkiBuf = await crypto.subtle.exportKey("spki", kp.publicKey);
  const spkiB64 = btoa(String.fromCharCode(...new Uint8Array(spkiBuf)));

  console.log("=== PUBLIC KEY SPKI BASE64 (Pin in Controller & Tracker) ===");
  console.log(spkiB64);
  console.log("\n=== PRIVATE KEY JWK (Upload directly to Supabase Secrets; DO NOT COMMIT) ===");
  console.log(JSON.stringify(prodJwk));
'
```

---

## 3. Provisioning to Remote Supabase Secrets

Upload the private JWK directly to the remote project via stdin or environment variable in a secure terminal, never via committed files:

```bash
# Link intended project (requires operator authorization):
npx supabase link --project-ref jyiqhqxjoahlxflaated

# Set the production secrets directly into remote environment:
npx supabase secrets set \
  SERVER_ECDSA_PRIVATE_KEY_JWK='<PASTE_PROD_JWK_HERE>' \
  SERVER_ECDSA_KEY_ID="trace-srv-ecdsa-p256-v1"
```

---

## 4. Pinning Public Key in Mobile Clients

Update `PROD_SERVER_PUBLIC_KEY_SPKI_BASE64` in `TracePinnedKeyProvider.kt` in both repositories with the derived public key:

1. **Controller App:**  
   `controller_app/core/src/main/java/com/livetracker/controller/core/tracker/command/crypto/TracePinnedKeyProvider.kt`
2. **Tracker App:**  
   `tracker_app/app/src/main/java/com/example/livelocationservice/command/TracePinnedKeyProvider.kt`

```kotlin
val PROD_SERVER_PUBLIC_KEY_SPKI_BASE64: String? =
    "<PROD_SPKI_BASE64>"
```

---

## 5. Verification & Activation Gate

Verify deployment safety using the deployment guard (dry-run check):
```bash
deno run --allow-all scripts/safe_deploy_supabase.ts \
  --project-ref jyiqhqxjoahlxflaated \
  --authorized-by-operator \
  --confirm-prod-key-activation \
  --action=check
```

Execute deployment steps safely through the wrapper:
```bash
# 1. Run remote read-only preflight:
deno run --allow-all scripts/safe_deploy_supabase.ts \
  --project-ref jyiqhqxjoahlxflaated \
  --authorized-by-operator \
  --action=preflight

# 2. Push database migrations:
deno run --allow-all scripts/safe_deploy_supabase.ts \
  --project-ref jyiqhqxjoahlxflaated \
  --authorized-by-operator \
  --action=db-push

# 3. Deploy commands Edge Function:
deno run --allow-all scripts/safe_deploy_supabase.ts \
  --project-ref jyiqhqxjoahlxflaated \
  --authorized-by-operator \
  --confirm-prod-key-activation \
  --action=deploy-functions
```

Any attempt to use development keys or bypass the safety gate will fail closed with exit code 1.
