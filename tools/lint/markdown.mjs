import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { lint } from "markdownlint/promise";

// Use literal Git paths instead of expanding user-controlled glob patterns.
const files = execFileSync("git", [
  "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md",
], { encoding: "utf8" }).split("\0").filter(Boolean);
const config = JSON.parse(readFileSync(".markdownlint.json", "utf8"));
if (files.length === 0) throw new Error("No Markdown files found");
const results = await lint({ files, config });
const errors = Object.entries(results).flatMap(([file, findings]) =>
  findings.map((finding) =>
    `${file}:${finding.lineNumber} ${finding.ruleNames[0]} ${finding.ruleDescription}` +
    (finding.errorDetail ? ` (${finding.errorDetail})` : "")));
if (errors.length > 0) {
  console.error(errors.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`Markdown: ${files.length} files passed`);
}
