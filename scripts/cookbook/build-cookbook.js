#!/usr/bin/env node
//
// Builds the cookbook page from COOKBOOK.md, the single source.
//
//   node scripts/cookbook/build-cookbook.js              update scripts/cookbook/cookbook.html in place
//   node scripts/cookbook/build-cookbook.js --out DIR    also write DIR/index.html (+ .nojekyll), for GitHub Pages
//   node scripts/cookbook/build-cookbook.js --check      exit 1 if scripts/cookbook/cookbook.html is out of date
//
// scripts/cookbook/cookbook.html is the page template: the script replaces only the Markdown inside
// <script type="text/markdown" id="source"> and the version shown in the header (from the root pom).
// The page renders that Markdown in the browser with marked.
//
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const SOURCE = path.join(ROOT, 'COOKBOOK.md');
const PAGE = path.join(__dirname, 'cookbook.html');
const POM = path.join(ROOT, 'pom.xml');
const REPO_URL = 'https://github.com/prasunmondal/GridBase';

const OPEN = '<script type="text/markdown" id="source">';
const CLOSE = '</script>';

function fail(message) {
    console.error('build-cookbook: ' + message);
    process.exit(1);
}

function version() {
    const pom = fs.readFileSync(POM, 'utf8');
    const match = /<artifactId>gridbase-parent<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(pom);
    if (!match) {
        fail('no gridbase-parent version in pom.xml');
    }
    return match[1].trim();
}

//
// The page has its own title, header and section rail, so the Markdown title, the Contents list
// and the --- separators are dropped. Links to repository files point at GitHub.
//
function pageMarkdown(markdown) {
    const out = [];
    let skipping = false;
    let inFence = false;
    const lines = markdown.replace(/\r\n/g, '\n').split('\n');
    lines.forEach(function (line, i) {
        if (/^(```|~~~)/.test(line)) {
            inFence = !inFence;
        }
        if (!inFence) {
            if (i === 0 && /^# /.test(line)) {
                return;
            }
            if (i === 1 && line === '' && /^# /.test(lines[0])) {
                return;
            }
            if (line === '**Contents**') {
                skipping = true;
                return;
            }
            if (line === '---') {
                skipping = false;
                return;
            }
            if (skipping) {
                return;
            }
            line = line.replace(/\]\((?!https?:|#|mailto:)([^)\s]+)\)/g, function (all, target) {
                return '](' + REPO_URL + '/blob/main/' + target + ')';
            });
        }
        out.push(line);
    });
    const text = out.join('\n');
    if (/<\/script/i.test(text)) {
        fail('COOKBOOK.md contains "</script", which would end the embedded Markdown early');
    }
    return text;
}

function build() {
    const page = fs.readFileSync(PAGE, 'utf8');
    const eol = page.includes('\r\n') ? '\r\n' : '\n';
    const lf = page.replace(/\r\n/g, '\n');

    const start = lf.indexOf(OPEN);
    if (start < 0) {
        fail('no ' + OPEN + ' in ' + path.basename(PAGE));
    }
    const bodyStart = lf.indexOf('\n', start) + 1;
    const end = lf.indexOf('\n' + CLOSE, bodyStart);
    if (end < 0) {
        fail('the embedded Markdown in ' + path.basename(PAGE) + ' is not closed');
    }

    const v = version();
    let result = lf.slice(0, bodyStart)
        + pageMarkdown(fs.readFileSync(SOURCE, 'utf8')).replace(/\n+$/, '')
        + lf.slice(end);
    result = result
        .replace(/gridbase [0-9][^ <]* · Java SDK/, 'gridbase ' + v + ' · Java SDK')
        .replace(/compiles against [0-9][^ <]*?\.</, 'compiles against ' + v + '.<');

    return { current: page, built: result.replace(/\n/g, eol) };
}

function main(args) {
    const { current, built } = build();

    if (args.includes('--check')) {
        if (current !== built) {
            fail(path.basename(PAGE) + ' is out of date: run node scripts/cookbook/build-cookbook.js');
        }
        console.log('build-cookbook: up to date');
        return;
    }

    if (current !== built) {
        fs.writeFileSync(PAGE, built);
        console.log('build-cookbook: updated ' + path.basename(PAGE));
    } else {
        console.log('build-cookbook: ' + path.basename(PAGE) + ' already up to date');
    }

    const outIndex = args.indexOf('--out');
    if (outIndex >= 0) {
        const dir = path.resolve(args[outIndex + 1] || fail('--out needs a directory'));
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'index.html'), built);
        // Serve the page as is, without a Jekyll build.
        fs.writeFileSync(path.join(dir, '.nojekyll'), '');
        console.log('build-cookbook: wrote ' + path.join(dir, 'index.html'));
    }
}

main(process.argv.slice(2));
