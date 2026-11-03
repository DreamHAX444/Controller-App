const fs = require('fs');
const path = require('path');

function parseXmls(dir) {
  let tests = 0, failures = 0, errors = 0, skipped = 0, suites = 0;
  if (!fs.existsSync(dir)) return { suites, tests, failures, errors, skipped };
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.xml'));
  for (const f of files) {
    const content = fs.readFileSync(path.join(dir, f), 'utf8');
    const mTests = content.match(/tests="(\d+)"/);
    const mFailures = content.match(/failures="(\d+)"/);
    const mErrors = content.match(/errors="(\d+)"/);
    const mSkipped = content.match(/skipped="(\d+)"/);
    if (mTests) {
      suites++;
      tests += parseInt(mTests[1], 10);
      failures += parseInt(mFailures ? mFailures[1] : '0', 10);
      errors += parseInt(mErrors ? mErrors[1] : '0', 10);
      skipped += parseInt(mSkipped ? mSkipped[1] : '0', 10);
    }
  }
  return { suites, tests, failures, errors, skipped };
}

const targetDir = process.argv[2];
if (targetDir) {
  const res = parseXmls(targetDir);
  console.log('TARGET:', JSON.stringify(res));
} else {
  const core = parseXmls('core/build/test-results/testDebugUnitTest');
  const app = parseXmls('app/build/test-results/testDebugUnitTest');
  console.log('CORE:', JSON.stringify(core));
  console.log('APP:', JSON.stringify(app));
  console.log('TOTAL:', JSON.stringify({
    suites: core.suites + app.suites,
    tests: core.tests + app.tests,
    failures: core.failures + app.failures,
    errors: core.errors + app.errors,
    skipped: core.skipped + app.skipped
  }));
}
