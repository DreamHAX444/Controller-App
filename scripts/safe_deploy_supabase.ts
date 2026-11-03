/**
 * TRACE V1 — Safe Supabase Deployment Wrapper
 *
 * Enforces strict project identity verification, key environment separation,
 * and operator authorization BEFORE executing any remote Supabase CLI commands.
 *
 * MANDATORY SAFEGUARDS:
 * 1. CLI Linkage Verification: Strictly verifies active CLI project is 'jyiqhqxjoahlxflaated'.
 * 2. Explicit Parameter Enforcement: Requires --project-ref jyiqhqxjoahlxflaated on all remote mutations.
 * 3. Operator Authorization Guard: Requires --authorized-by-operator to execute.
 * 4. Key Environment Verification Gate:
 *    - Strictly prevents deployment when key environment is marked DEVELOPMENT_ONLY.
 *    - Never allows uploading dev_server_signing_key.jwk.json to production.
 *    - Requires explicit operator approval (--confirm-prod-key-activation) for production keys.
 * 5. Zero Implicit Remote Defaults: Fails closed on any ambiguity.
 */

import { existsSync } from "https://deno.land/std@0.224.0/fs/exists.ts";
import * as path from "https://deno.land/std@0.224.0/path/mod.ts";

const TARGET_PROJECT_REF = "jyiqhqxjoahlxflaated";
const TARGET_PROJECT_NAME = "Live_tracker_data_send_app";

interface SupabaseProject {
  id: string;
  ref: string;
  name: string;
  linked: boolean;
}

async function verifyTargetProject(): Promise<boolean> {
  const cmd = new Deno.Command("supabase", {
    args: ["projects", "list", "--output", "json"],
    stdout: "piped",
    stderr: "piped",
  });
  const output = await cmd.output();
  if (!output.success) return false;

  try {
    const raw = JSON.parse(new TextDecoder().decode(output.stdout));
    const list: SupabaseProject[] = Array.isArray(raw) ? raw : (raw.projects || []);
    const linked = list.find((p) => p.linked === true);
    return linked?.ref === TARGET_PROJECT_REF;
  } catch {
    return false;
  }
}

/**
 * Gate: Verifies that development keys are NEVER uploaded to production.
 * Fails closed if the key is marked DEVELOPMENT_ONLY, named with 'dev',
 * or if production key activation lacks explicit operator confirmation.
 */
export function verifySigningKeyEnvironment(args: string[]): { ok: boolean; reason?: string } {
  const prodKeyArg = args.find((a) => a.startsWith("--prod-key-file="))?.split("=")[1] ||
    (args.indexOf("--prod-key-file") !== -1 ? args[args.indexOf("--prod-key-file") + 1] : null);

  const keyEnvArg = args.find((a) => a.startsWith("--key-env="))?.split("=")[1] || "production";
  const hasProdKeyConfirm = args.includes("--confirm-prod-key-activation");

  // Check if any argument or environment refers to dev_server_signing_key
  const hasDevKeyMention = args.some((a) => 
    a.toLowerCase().includes("dev_server_signing_key") || 
    a.toLowerCase().includes(".keys/dev")
  );

  if (hasDevKeyMention) {
    return {
      ok: false,
      reason: "FATAL: Detected reference to development signing key (dev_server_signing_key). Development keys must NEVER be deployed or uploaded to production!"
    };
  }

  if (keyEnvArg.toUpperCase() === "DEVELOPMENT_ONLY" || keyEnvArg.toUpperCase() === "DEV") {
    return {
      ok: false,
      reason: "FATAL: Key environment is marked DEVELOPMENT_ONLY. Deployment to production is strictly blocked."
    };
  }

  if (prodKeyArg) {
    if (!existsSync(prodKeyArg)) {
      return { ok: false, reason: `Specified production key file does not exist: ${prodKeyArg}` };
    }

    try {
      const content = Deno.readTextFileSync(prodKeyArg);
      const parsed = JSON.parse(content);
      const purpose = (parsed._purpose || parsed.environment || "").toUpperCase();
      if (purpose.includes("DEVELOPMENT") || purpose.includes("DEV") || purpose.includes("TEST")) {
        return {
          ok: false,
          reason: `Production key file contains development purpose metadata: '${parsed._purpose || parsed.environment}'. Must not deploy to production!`
        };
      }

      if (!hasProdKeyConfirm) {
        return {
          ok: false,
          reason: "Production key file provided but missing explicit operator confirmation flag: --confirm-prod-key-activation"
        };
      }
    } catch (e: any) {
      return { ok: false, reason: `Failed to parse production key file: ${e.message}` };
    }
  }

  return { ok: true };
}

/**
 * Gate: Validates all deployment prerequisites, project references, operator authorization,
 * key environment safety, and action commands before executing any remote operations.
 */
export function validateDeploymentArgs(
  args: string[],
  isLinkedCorrectly: boolean
): { ok: boolean; reason?: string } {
  if (!isLinkedCorrectly) {
    return {
      ok: false,
      reason: `CLI is NOT linked to intended target '${TARGET_PROJECT_REF}'! Operator must authorize linking first via: supabase link --project-ref ${TARGET_PROJECT_REF}`
    };
  }

  const projectRefArg = args.find((a) => a.startsWith("--project-ref="))?.split("=")[1] ||
    (args.indexOf("--project-ref") !== -1 ? args[args.indexOf("--project-ref") + 1] : null);

  if (projectRefArg !== TARGET_PROJECT_REF) {
    return {
      ok: false,
      reason: `Missing or invalid explicit --project-ref! All deployment commands must include: --project-ref ${TARGET_PROJECT_REF}`
    };
  }

  const isAuthorized = args.includes("--authorized-by-operator");
  if (!isAuthorized) {
    return {
      ok: false,
      reason: "Deployment authorization flag missing. Operator must explicitly provide: --authorized-by-operator"
    };
  }

  const keyCheck = verifySigningKeyEnvironment(args);
  if (!keyCheck.ok) {
    return keyCheck;
  }

  const action = args.find((a) => a.startsWith("--action="))?.split("=")[1] || "check";
  const supportedActions = ["check", "preflight", "db-push", "deploy-functions"];
  if (!supportedActions.includes(action)) {
    return {
      ok: false,
      reason: `Unknown action '${action}'. Supported actions: ${supportedActions.join(", ")}`
    };
  }

  return { ok: true };
}

async function main() {
  console.log("================================================================");
  console.log("  TRACE V1 — SAFE SUPABASE DEPLOYMENT GUARD");
  console.log("================================================================");

  const args = Deno.args;
  console.log(`Checking CLI Project Linkage...`);
  const isLinkedCorrectly = await verifyTargetProject();

  const validation = validateDeploymentArgs(args, isLinkedCorrectly);
  if (!validation.ok) {
    console.error(`\n[FATAL SAFETY BLOCK] ${validation.reason}\n`);
    Deno.exit(1);
  }

  console.log(`[PASS] Identity, authorization, and key safety checks verified for '${TARGET_PROJECT_NAME}'.`);

  const action = args.find((a) => a.startsWith("--action="))?.split("=")[1] || "check";

  if (action === "check") {
    console.log("[DRY RUN ONLY] All safety gates passed. No remote commands executed.");
    console.log("================================================================\n");
    return;
  }

  if (action === "preflight") {
    console.log(`[EXECUTE] Running read-only migration preflight check against '${TARGET_PROJECT_NAME}'...`);
    const cmd = new Deno.Command("supabase", {
      args: ["db", "execute", "--file", "scripts/migration_preflight_check.sql"],
      stdout: "inherit",
      stderr: "inherit",
    });
    const status = await cmd.spawn().status;
    if (!status.success) {
      console.error(`[ERROR] Preflight execution failed with exit code ${status.code}`);
      Deno.exit(status.code);
    }
    console.log("================================================================\n");
    return;
  }

  if (action === "db-push") {
    console.log(`[EXECUTE] Pushing database migrations (01 through 06) to '${TARGET_PROJECT_NAME}'...`);
    const cmd = new Deno.Command("supabase", {
      args: ["db", "push"],
      stdout: "inherit",
      stderr: "inherit",
    });
    const status = await cmd.spawn().status;
    if (!status.success) {
      console.error(`[ERROR] db push failed with exit code ${status.code}`);
      Deno.exit(status.code);
    }
    console.log("================================================================\n");
    return;
  }

  if (action === "deploy-functions") {
    console.log(`[EXECUTE] Deploying 'commands' Edge Function to '${TARGET_PROJECT_NAME}' with --no-verify-jwt...`);
    const cmd = new Deno.Command("supabase", {
      args: ["functions", "deploy", "commands", "--no-verify-jwt", "--project-ref", TARGET_PROJECT_REF],
      stdout: "inherit",
      stderr: "inherit",
    });
    const status = await cmd.spawn().status;
    if (!status.success) {
      console.error(`[ERROR] functions deploy failed with exit code ${status.code}`);
      Deno.exit(status.code);
    }
    console.log("================================================================\n");
    return;
  }

  console.error(`[ERROR] Unknown action '${action}'. Supported actions: check, preflight, db-push, deploy-functions`);
  Deno.exit(1);
}

if (import.meta.main) {
  await main();
}
