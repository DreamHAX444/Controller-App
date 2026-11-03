/**
 * TRACE V1 — Deployment Safety Gate Unit Tests
 *
 * Verifies that the signing key environment verification gate in safe_deploy_supabase.ts
 * strictly enforces key separation, prevents DEVELOPMENT_ONLY keys from reaching production,
 * and requires explicit operator confirmation for production key activation.
 */

import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { verifySigningKeyEnvironment, validateDeploymentArgs } from "./safe_deploy_supabase.ts";

Deno.test("Safety Gate: Rejects unlinked CLI status", () => {
  const result = validateDeploymentArgs(
    ["--project-ref", "jyiqhqxjoahlxflaated", "--authorized-by-operator"],
    false // isLinkedCorrectly = false
  );
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("CLI is NOT linked"), true);
});

Deno.test("Safety Gate: Rejects missing --project-ref parameter", () => {
  const result = validateDeploymentArgs(
    ["--authorized-by-operator"],
    true // isLinkedCorrectly = true
  );
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("Missing or invalid explicit --project-ref"), true);
});

Deno.test("Safety Gate: Rejects mismatched --project-ref (e.g. wrong target)", () => {
  const result = validateDeploymentArgs(
    ["--project-ref", "rztljkjiamwnixyelxny", "--authorized-by-operator"],
    true
  );
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("Missing or invalid explicit --project-ref"), true);
});

Deno.test("Safety Gate: Rejects deployment without --authorized-by-operator", () => {
  const result = validateDeploymentArgs(
    ["--project-ref", "jyiqhqxjoahlxflaated"],
    true
  );
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("--authorized-by-operator"), true);
});

Deno.test("Safety Gate: Rejects unknown / unsupported deployment action", () => {
  const result = validateDeploymentArgs(
    ["--project-ref", "jyiqhqxjoahlxflaated", "--authorized-by-operator", "--action=unsupported-danger"],
    true
  );
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("Unknown action"), true);
});

Deno.test("Safety Gate: Rejects explicit --key-env=DEVELOPMENT_ONLY", () => {
  const result = verifySigningKeyEnvironment([
    "--project-ref", "jyiqhqxjoahlxflaated",
    "--key-env=DEVELOPMENT_ONLY"
  ]);
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("DEVELOPMENT_ONLY"), true);
});

Deno.test("Safety Gate: Rejects references to dev_server_signing_key", () => {
  const result = verifySigningKeyEnvironment([
    "--project-ref", "jyiqhqxjoahlxflaated",
    "--key-file", ".keys/dev_server_signing_key.jwk.json"
  ]);
  assertEquals(result.ok, false);
  assertEquals(result.reason?.includes("dev_server_signing_key"), true);
});

Deno.test("Safety Gate: Rejects key file with DEVELOPMENT_ONLY purpose metadata", () => {
  const tempPath = Deno.makeTempFileSync({ suffix: ".json" });
  try {
    Deno.writeTextFileSync(tempPath, JSON.stringify({
      kid: "trace-srv-ecdsa-p256-v1",
      _purpose: "DEVELOPMENT_ONLY - DO NOT USE IN PRODUCTION",
      kty: "EC"
    }));

    const result = verifySigningKeyEnvironment([
      "--project-ref", "jyiqhqxjoahlxflaated",
      `--prod-key-file=${tempPath}`,
      "--confirm-prod-key-activation"
    ]);
    assertEquals(result.ok, false);
    assertEquals(result.reason?.includes("development purpose metadata"), true);
  } finally {
    Deno.removeSync(tempPath);
  }
});

Deno.test("Safety Gate: Rejects production key file without --confirm-prod-key-activation", () => {
  const tempPath = Deno.makeTempFileSync({ suffix: ".json" });
  try {
    Deno.writeTextFileSync(tempPath, JSON.stringify({
      kid: "trace-srv-ecdsa-p256-v1",
      _purpose: "PRODUCTION",
      kty: "EC"
    }));

    const result = verifySigningKeyEnvironment([
      "--project-ref", "jyiqhqxjoahlxflaated",
      `--prod-key-file=${tempPath}`
    ]);
    assertEquals(result.ok, false);
    assertEquals(result.reason?.includes("--confirm-prod-key-activation"), true);
  } finally {
    Deno.removeSync(tempPath);
  }
});

Deno.test("Safety Gate: Accepts verified production key with operator confirmation", () => {
  const tempPath = Deno.makeTempFileSync({ suffix: ".json" });
  try {
    Deno.writeTextFileSync(tempPath, JSON.stringify({
      kid: "trace-srv-ecdsa-p256-v1",
      _purpose: "PRODUCTION",
      kty: "EC"
    }));

    const result = verifySigningKeyEnvironment([
      "--project-ref", "jyiqhqxjoahlxflaated",
      `--prod-key-file=${tempPath}`,
      "--confirm-prod-key-activation"
    ]);
    assertEquals(result.ok, true);
  } finally {
    Deno.removeSync(tempPath);
  }
});

Deno.test("Safety Gate: Validates all supported actions (check, preflight, db-push, deploy-functions)", () => {
  const actions = ["check", "preflight", "db-push", "deploy-functions"];
  for (const action of actions) {
    const result = validateDeploymentArgs(
      ["--project-ref", "jyiqhqxjoahlxflaated", "--authorized-by-operator", `--action=${action}`],
      true
    );
    assertEquals(result.ok, true, `Action ${action} must pass validation when properly authorized`);
  }
});
