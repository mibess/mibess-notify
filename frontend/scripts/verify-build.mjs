import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// Production CSP disallows inline event handlers. Angular's critical CSS loader
// otherwise emits an onload handler that leaves the stylesheet in print mode.
const html = readFileSync(new URL("../dist/notify/browser/index.html", import.meta.url), "utf8");
assert(!/\son[a-z]+\s*=/i.test(html), "Built HTML must not require inline event handlers under production CSP");
assert(/<link\b[^>]*rel="stylesheet"[^>]*>/i.test(html), "Built HTML must load its stylesheet directly");
console.log("CSP build check passed: styles load without inline event handlers.");
