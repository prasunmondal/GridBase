#!/usr/bin/env node
// Checks the project knowledge base (CLAUDE.md, .claude/rules, .claude/context) against the repo.
// Errors (exit 1): missing sources / referenced paths, failed asserts, rules without paths or whose
// globs match nothing, pom versions that disagree. Warnings: commits to sources since last_verified,
// size budgets, ADR index drift, STALE / NEEDS_REVIEW files.
// Usage: node scripts/knowledge/lint-context.js   (no dependencies; run from anywhere in the repo)
'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..');
const CONTEXT = '.claude/context';
const RULES = '.claude/rules';
const BUDGET = { claude: 90, rule: 35, context: 150 };
const SKIP_DIRS = new Set(['.git', 'target', 'node_modules', '.idea']);
const FILE_EXT = /\.(java|js|json|xml|md|yml|yaml|bat|sh|ps1|pro|asc)$/;

const errors = [];
const warnings = [];
const err = (file, msg) => errors.push(`${file}: ${msg}`);
const warn = (file, msg) => warnings.push(`${file}: ${msg}`);

// ---- repo file list --------------------------------------------------------------------------
const files = [];
const dirs = new Set();
(function walk(rel) {
  for (const e of fs.readdirSync(path.join(ROOT, rel), { withFileTypes: true })) {
    if (SKIP_DIRS.has(e.name)) continue;
    const p = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) { dirs.add(p); walk(p); } else files.push(p);
  }
})('');
const exists = (p) => files.includes(p) || dirs.has(p);
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

// ---- frontmatter (small YAML subset: scalars, [a, b] lists, "- item" lists, { k: v } maps) ----
function parseFrontmatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return null;
  const out = {};
  let key = null;
  for (const raw of m[1].split(/\r?\n/)) {
    const line = raw.replace(/\s+#.*$/, '');
    if (!line.trim()) continue;
    const item = /^\s+-\s+(.*)$/.exec(line);
    if (item && key) { (out[key] = out[key] || []).push(parseValue(item[1])); continue; }
    const kv = /^([\w-]+):\s*(.*)$/.exec(line);
    if (kv) { key = kv[1]; out[key] = kv[2] === '' ? undefined : parseValue(kv[2]); }
  }
  return out;
}
function parseValue(v) {
  v = v.trim();
  if (v.startsWith('[') && v.endsWith(']')) return v.slice(1, -1).split(',').map((s) => unquote(s.trim())).filter(Boolean);
  if (v.startsWith('{') && v.endsWith('}')) {
    const o = {};
    for (const m of v.slice(1, -1).matchAll(/(\w+):\s*("(?:[^"\\]|\\.)*"|[^,]+)/g)) o[m[1]] = unquote(m[2].trim());
    return o;
  }
  return unquote(v);
}
const unquote = (s) => (/^".*"$|^'.*'$/.test(s) ? s.slice(1, -1).replace(/\\"/g, '"') : s);

// ---- path references in backticks -----------------------------------------------------------
// Repo-relative paths, paths relative to .claude/ or .claude/context/, or a unique suffix of a repo
// path (e.g. `cache/CacheBackend.java`, `integrationTests/Employee.java`).
function resolveRef(token) {
  const t = token.replace(/[#:].*$/, '').replace(/\/$/, '');
  for (const base of ['', '.claude/', `${CONTEXT}/`]) if (exists(base + t)) return true;
  const suffixes = [`/${t}`, `/${t}.java`, `/${t}.js`];  // class refs may omit the extension
  return files.some((f) => suffixes.some((x) => f.endsWith(x))) || [...dirs].some((d) => d.endsWith(suffixes[0]));
}
function looksLikePath(t) {
  if (/[\s<>*{}()$~=]|\.\.\.|^https?:|^\/|^-|NNN/.test(t) || /^\.[a-z]+$/.test(t)) return false;
  return t.includes('/') || FILE_EXT.test(t);
}
function checkRefs(file, text) {
  const body = text.replace(/^---[\s\S]*?\n---/, '');
  for (const m of body.matchAll(/`([^`\n]+)`/g)) {
    const t = m[1].trim();
    if (looksLikePath(t) && !resolveRef(t)) err(file, `referenced path not found: \`${t}\``);
  }
}

// ---- globs (rules `paths:`) -------------------------------------------------------------------
function globToRegex(g) {
  let re = '';
  for (let i = 0; i < g.length; i++) {
    const c = g[i];
    if (c === '*' && g[i + 1] === '*') { re += g[i + 2] === '/' ? '(?:.*/)?' : '.*'; i += g[i + 2] === '/' ? 2 : 1; }
    else if (c === '*') re += '[^/]*';
    else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^$()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

// ---- git -----------------------------------------------------------------------------------------
function commitsSince(date, sources) {
  try {
    const out = execFileSync('git', ['log', '--oneline', `--since=${date} 23:59:59`, '--', ...sources],
      { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    return out.split('\n').filter(Boolean);
  } catch (e) {
    return [];
  }
}

// ---- checks --------------------------------------------------------------------------------------
const lines = (t) => t.split(/\r?\n/).length;

// CLAUDE.md
if (exists('CLAUDE.md')) {
  const t = read('CLAUDE.md');
  if (lines(t) > BUDGET.claude) warn('CLAUDE.md', `${lines(t)} lines > budget ${BUDGET.claude}`);
  checkRefs('CLAUDE.md', t);
}

// rules
for (const f of files.filter((p) => p.startsWith(`${RULES}/`) && p.endsWith('.md'))) {
  const t = read(f);
  const fm = parseFrontmatter(t);
  const globs = fm && fm.paths ? [].concat(fm.paths) : [];
  if (!globs.length) err(f, 'rule has no `paths:` frontmatter, so it would load in every session');
  for (const g of globs) if (!files.some((p) => globToRegex(g).test(p))) err(f, `paths glob matches no file: ${g}`);
  if (lines(t) > BUDGET.rule) warn(f, `${lines(t)} lines > budget ${BUDGET.rule}`);
  checkRefs(f, t);
}

// context
for (const f of files.filter((p) => p.startsWith(`${CONTEXT}/`) && p.endsWith('.md'))) {
  const t = read(f);
  const fm = parseFrontmatter(t);
  const historical = f.startsWith(`${CONTEXT}/history/`);
  const isAdr = /\/decisions\/ADR-/.test(f);
  if (historical) continue;                       // never checked: it describes the past
  if (lines(t) > BUDGET.context) warn(f, `${lines(t)} lines > budget ${BUDGET.context}`);
  if (isAdr) continue;                            // ADRs are immutable records; their paths may age
  checkRefs(f, t);
  if (!fm) { err(f, 'missing frontmatter (status, last_verified)'); continue; }
  if (!['VERIFIED', 'NEEDS_REVIEW', 'STALE', 'HISTORICAL'].includes(fm.status)) err(f, `bad status: ${fm.status}`);
  if (fm.status === 'STALE' || fm.status === 'NEEDS_REVIEW') warn(f, `status ${fm.status}`);
  const sources = fm.sources ? [].concat(fm.sources) : [];
  for (const s of sources) if (!exists(s.replace(/\/$/, ''))) err(f, `source not found: ${s}`);
  for (const a of fm.asserts ? [].concat(fm.asserts) : []) {
    if (!a.file || !exists(a.file)) err(f, `assert file not found: ${a.file}`);
    else if (!read(a.file).includes(a.contains)) err(f, `assert failed: ${a.file} does not contain ${JSON.stringify(a.contains)}`);
  }
  if (fm.last_verified && sources.length) {
    const live = sources.filter((s) => exists(s.replace(/\/$/, '')));
    const commits = live.length ? commitsSince(fm.last_verified, live) : [];
    if (commits.length) warn(f, `${commits.length} commit(s) touched its sources after last_verified ${fm.last_verified} (re-verify): ${commits.slice(0, 3).join(' | ')}`);
  }
}

// ADR index
const adrDir = `${CONTEXT}/decisions`;
if (exists(`${adrDir}/README.md`)) {
  const index = read(`${adrDir}/README.md`);
  const adrs = files.filter((p) => p.startsWith(`${adrDir}/ADR-`)).map((p) => path.posix.basename(p));
  for (const a of adrs) if (!index.includes(a)) warn(`${adrDir}/README.md`, `ADR not listed: ${a}`);
  for (const m of index.matchAll(/\]\((ADR-[^)]+)\)/g)) if (!adrs.includes(m[1])) err(`${adrDir}/README.md`, `link to missing ADR: ${m[1]}`);
}

// pom versions agree
function pomVersion(p, inParent) {
  const t = read(p).replace(/<!--[\s\S]*?-->/g, '');
  const scope = inParent ? (/<parent>([\s\S]*?)<\/parent>/.exec(t) || [])[1] || '' : t.replace(/<parent>[\s\S]*?<\/parent>/, '');
  return (/<version>([^<]+)<\/version>/.exec(scope) || [])[1];
}
if (exists('pom.xml')) {
  const root = pomVersion('pom.xml', false);
  for (const m of ['gridbase/pom.xml', 'gridbase-android/pom.xml'].filter(exists)) {
    const v = pomVersion(m, true);
    if (v !== root) err(m, `<parent><version> ${v} != root pom version ${root}`);
  }
}

// ---- report --------------------------------------------------------------------------------------
for (const w of warnings) console.log(`WARN  ${w}`);
for (const e of errors) console.log(`ERROR ${e}`);
console.log(`knowledge lint: ${errors.length} error(s), ${warnings.length} warning(s)`);
process.exit(errors.length ? 1 : 0);
