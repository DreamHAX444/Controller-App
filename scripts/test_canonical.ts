import { canonicalizeJson, computeSha256Hex } from "../supabase/functions/_shared/crypto.ts";

const obj = {
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

const canonical = canonicalizeJson(obj);
console.log("CANONICAL:");
console.log(canonical);
computeSha256Hex(canonical).then((hash) => {
  console.log("SHA256:", hash);
});
