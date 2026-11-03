/**
 * TRACE Phase 7 — Supabase Target Project Safety Gate
 *
 * Verifies that the local Supabase CLI is linked to the intended application target
 * project ('jyiqhqxjoahlxflaated' - 'Live_tracker_data_send_app') and strictly blocks
 * execution if linked to any other project (such as 'rztljkjiamwnixyelxny' - 'DreamHAX444\'s Project').
 *
 * Exits with code 0 if safe and matched.
 * Exits with code 1 if mismatched, unlinked, or unauthenticated.
 */

const EXPECTED_PROJECT_REF = "jyiqhqxjoahlxflaated";
const EXPECTED_PROJECT_NAME = "Live_tracker_data_send_app";

interface SupabaseProject {
  id: string;
  ref: string;
  name: string;
  region: string;
  status: string;
  linked: boolean;
}

interface ProjectsListResponse {
  projects: SupabaseProject[];
  message?: string;
}

async function main() {
  console.log("================================================================");
  console.log("  TRACE V1 — SUPABASE TARGET PROJECT SAFETY GATE");
  console.log("================================================================");
  console.log(`Expected Target Ref:  ${EXPECTED_PROJECT_REF}`);
  console.log(`Expected Target Name: ${EXPECTED_PROJECT_NAME}`);
  console.log("----------------------------------------------------------------");

  let stdoutText = "";
  try {
    const cmd = new Deno.Command("supabase", {
      args: ["projects", "list", "--output", "json"],
      stdout: "piped",
      stderr: "piped",
    });
    const output = await cmd.output();
    if (!output.success) {
      const errText = new TextDecoder().decode(output.stderr);
      console.error(`[ERROR] Failed to execute 'supabase projects list': ${errText}`);
      Deno.exit(1);
    }
    stdoutText = new TextDecoder().decode(output.stdout);
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    console.error(`[ERROR] Supabase CLI execution failed: ${message}`);
    Deno.exit(1);
  }

  let data: unknown;
  try {
    data = JSON.parse(stdoutText);
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    console.error(`[ERROR] Failed to parse 'supabase projects list' JSON: ${message}`);
    Deno.exit(1);
  }

  const projects: SupabaseProject[] = Array.isArray(data)
    ? (data as SupabaseProject[])
    : ((data as ProjectsListResponse).projects || []);

  const linkedProject = projects.find((p) => p.linked === true);

  if (!linkedProject) {
    console.error(`\n[BLOCKED] No project is currently linked in Supabase CLI.`);
    console.error(`To link the intended target project, operator must execute:`);
    console.error(`  supabase link --project-ref ${EXPECTED_PROJECT_REF}\n`);
    Deno.exit(1);
  }

  console.log(`Currently Linked Project:`);
  console.log(`  - Ref:    ${linkedProject.ref}`);
  console.log(`  - Name:   ${linkedProject.name}`);
  console.log(`  - Region: ${linkedProject.region}`);
  console.log(`  - Status: ${linkedProject.status}`);
  console.log("----------------------------------------------------------------");

  if (linkedProject.ref !== EXPECTED_PROJECT_REF) {
    console.error(`\n[FATAL MISMATCH DETECTED]`);
    console.error(`  The CLI is currently linked to an UNINTENDED project!`);
    console.error(`  Active Link:   '${linkedProject.ref}' ('${linkedProject.name}')`);
    console.error(`  Required App:  '${EXPECTED_PROJECT_REF}' ('${EXPECTED_PROJECT_NAME}')`);
    console.error(``);
    console.error(`  DANGER: Running 'supabase db push' or 'supabase functions deploy'`);
    console.error(`  right now would mutate '${linkedProject.name}', NOT the tracker app!`);
    console.error(``);
    console.error(`  Remediation command (requires authorized operator approval):`);
    console.error(`    supabase link --project-ref ${EXPECTED_PROJECT_REF}`);
    console.error("================================================================\n");
    Deno.exit(1);
  }

  console.log(`[PASS] Verified! Supabase CLI is correctly linked to '${EXPECTED_PROJECT_REF}'.`);
  console.log("================================================================\n");
  Deno.exit(0);
}

if (import.meta.main) {
  await main();
}
