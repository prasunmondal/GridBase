#!/usr/bin/env node
/*
 * Local emulator for the hibernate.sheets Apps Script engine.
 *
 * Runs the REAL engine source (the appscript/ folder) inside Node, with an in-memory
 * SpreadsheetApp / ContentService, behind an HTTP endpoint that behaves like an Apps Script
 * web app (POST /macros/s/<id>/exec -> 302 -> GET echo URL -> JSON).
 *
 * Usage:
 *   node engine-emulator.js <path-to-appscript-dir> [port]
 *   ENDPOINT = http://127.0.0.1:<port>/macros/s/LOCAL/exec
 *
 * Env:
 *   HS_SPREADSHEET_IDS  comma-separated spreadsheet ids that "exist"
 *                       (default: 1C8rsAWa0XfpxfHSb-F-FALSvmCT1knQ5lBoegQ8Phwc)
 *   HS_DEPLOYMENT_ID    the only deployment id that answers (default LOCAL); others get 404 like Google
 *   TZ                  spreadsheet time zone (default Asia/Kolkata)
 *
 * Fidelity notes (approximations of Google Sheets):
 *   - setValues() parses text like a user typing it: "123" -> number, "2026-09-30" -> date
 *     (midnight in TZ), "true"/"false" -> boolean. Everything else stays text.
 *   - New sheets have a 1000 x 26 grid; ranges outside the grid throw, deleteRow() shrinks it.
 *   - Empty cells read back as "".
 */
'use strict';
process.env.TZ = process.env.TZ || 'Asia/Kolkata';

const fs = require('fs');
const path = require('path');
const http = require('http');
const vm = require('vm');
const crypto = require('crypto');

const root = process.argv[2];
const port = Number(process.argv[3] || 8765);
const deploymentId = process.env.HS_DEPLOYMENT_ID || 'LOCAL';
if (!root) {
  console.error('usage: node engine-emulator.js <appscript-dir> [port]');
  process.exit(2);
}

// ---------------------------------------------------------------- fake Sheets

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const NUMERIC = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

function coerce(v) {
  if (v === null || v === undefined) return '';
  if (v instanceof Date || typeof v === 'number' || typeof v === 'boolean') return v;
  if (typeof v === 'object') return JSON.stringify(v); // Sheets would reject; keep it visible
  const s = String(v);
  const t = s.trim();
  if (t !== '' && NUMERIC.test(t)) return Number(t);
  const d = ISO_DATE.exec(t);
  if (d) return new Date(Number(d[1]), Number(d[2]) - 1, Number(d[3]));
  if (/^(true|false)$/i.test(t)) return t.toLowerCase() === 'true';
  return s;
}

let nextSheetId = 1000;

class FakeRange {
  constructor(sheet, row, col, numRows, numCols) {
    if (row < 1 || col < 1 || numRows < 1 || numCols < 1 ||
        row + numRows - 1 > sheet.maxRows || col + numCols - 1 > sheet.maxCols) {
      throw new Error('The coordinates of the range are outside the dimensions of the sheet.');
    }
    Object.assign(this, { sheet, row, col, numRows, numCols });
  }
  getValues() {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const src = this.sheet.cells[this.row - 1 + r] || [];
      const line = [];
      for (let c = 0; c < this.numCols; c++) {
        const v = src[this.col - 1 + c];
        line.push(v === undefined ? '' : (v instanceof Date ? new Date(v.getTime()) : v));
      }
      out.push(line);
    }
    return out;
  }
  setValues(values) {
    if (!Array.isArray(values) || values.length !== this.numRows) {
      throw new Error(`The number of rows in the data does not match the number of rows in the range. The data has ${values.length} but the range has ${this.numRows}.`);
    }
    values.forEach((line, r) => {
      if (line.length !== this.numCols) {
        throw new Error(`The number of columns in the data does not match the number of columns in the range. The data has ${line.length} but the range has ${this.numCols}.`);
      }
      const target = this.sheet.cells[this.row - 1 + r] || (this.sheet.cells[this.row - 1 + r] = []);
      line.forEach((v, c) => { target[this.col - 1 + c] = coerce(v); });
    });
    return this;
  }
  clearContent() {
    for (let r = 0; r < this.numRows; r++) {
      const target = this.sheet.cells[this.row - 1 + r];
      if (!target) continue;
      for (let c = 0; c < this.numCols; c++) target[this.col - 1 + c] = '';
    }
    return this;
  }
}

class FakeSheet {
  constructor(name) {
    this.name = name;
    this.id = nextSheetId++;
    this.cells = [];
    this.maxRows = 1000;
    this.maxCols = 26;
  }
  getName() { return this.name; }
  getSheetId() { return this.id; }
  getMaxRows() { return this.maxRows; }
  getMaxColumns() { return this.maxCols; }
  getLastRow() {
    for (let r = this.cells.length - 1; r >= 0; r--) {
      if ((this.cells[r] || []).some(v => v !== '' && v !== undefined)) return r + 1;
    }
    return 0;
  }
  getLastColumn() {
    let last = 0;
    this.cells.forEach(row => (row || []).forEach((v, c) => {
      if (v !== '' && v !== undefined) last = Math.max(last, c + 1);
    }));
    return last;
  }
  getRange(row, col, numRows, numCols) {
    return new FakeRange(this, row, col, numRows || 1, numCols || 1);
  }
  deleteRow(row) {
    if (row < 1 || row > this.maxRows) throw new Error('Those rows are out of bounds.');
    this.cells.splice(row - 1, 1);
    this.maxRows--;
    return this;
  }
}

class FakeSpreadsheet {
  constructor(id) { this.id = id; this.sheets = new Map(); }
  getId() { return this.id; }
  getSheetByName(name) { return this.sheets.get(name) || null; }
  insertSheet(name) {
    if (this.sheets.has(name)) {
      throw new Error(`A sheet with the name "${name}" already exists. Please enter another name.`);
    }
    const s = new FakeSheet(name);
    this.sheets.set(name, s);
    return s;
  }
}

const spreadsheets = new Map();
(process.env.HS_SPREADSHEET_IDS || '1C8rsAWa0XfpxfHSb-F-FALSvmCT1knQ5lBoegQ8Phwc')
  .split(',').map(s => s.trim()).filter(Boolean)
  .forEach(id => spreadsheets.set(id, new FakeSpreadsheet(id)));

global.SpreadsheetApp = {
  openById(id) {
    const ss = spreadsheets.get(id);
    if (!ss) {
      throw new Error('Unexpected error while getting the method or property openById on object SpreadsheetApp.');
    }
    return ss;
  }
};

global.ContentService = {
  MimeType: { JSON: 'JSON', TEXT: 'TEXT' },
  createTextOutput(content) {
    return {
      content: String(content ?? ''),
      mimeType: 'TEXT',
      setMimeType(m) { this.mimeType = m; return this; },
      getContent() { return this.content; }
    };
  }
};

// ---------------------------------------------------------------- load engine

function listJs(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return ['node_modules', '.git'].includes(e.name) ? [] : listJs(p);
    return /\.(js|gs)$/.test(e.name) ? [p] : [];
  });
}

const all = listJs(root).map(p => path.relative(root, p).split(path.sep).join('/')).sort();
let pushOrder = [];
const claspFile = path.join(root, '.clasp.json');
if (fs.existsSync(claspFile)) {
  pushOrder = JSON.parse(fs.readFileSync(claspFile, 'utf8')).filePushOrder || [];
}
// Base classes / constants that other files extend must load first (as Apps Script does via filePushOrder).
const first = ['backend/write/ValueOperation.js', 'common/State.js', ...pushOrder];
const ordered = [...new Set([...first.filter(f => all.includes(f)), ...all])];
const source = ordered.map(f => `// ---- ${f}\n` + fs.readFileSync(path.join(root, f), 'utf8')).join('\n;\n');
vm.runInThisContext(source, { filename: 'appscript-bundle.js' });
if (typeof doPost !== 'function') {
  console.error('doPost() not found in ' + root);
  process.exit(2);
}

// ---------------------------------------------------------------- HTTP

const echo = new Map();

function send(res, status, type, body, headers) {
  res.writeHead(status, Object.assign({ 'Content-Type': type }, headers || {}));
  res.end(body);
}

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname === `/macros/s/${deploymentId}/exec`) {
    if (req.method === 'GET') {
      return send(res, 200, 'text/plain', doGet({ parameter: Object.fromEntries(url.searchParams) }).getContent());
    }
    let body = '';
    req.setEncoding('utf8');
    req.on('data', c => { body += c; });
    req.on('end', () => {
      let out;
      try {
        out = doPost({ postData: { contents: body, type: req.headers['content-type'] } }).getContent();
      } catch (e) {
        // Apps Script renders uncaught errors as an HTML page
        return send(res, 200, 'text/html', `<html><head><title>Error</title></head><body>${e.message}</body></html>`);
      }
      const key = crypto.randomBytes(12).toString('hex');
      echo.set(key, out);
      send(res, 302, 'text/html', '', { Location: `/macros/echo?user_content_key=${key}` });
    });
    return;
  }
  if (url.pathname === '/macros/echo') {
    const key = url.searchParams.get('user_content_key');
    if (!echo.has(key)) return send(res, 404, 'text/html', '<html><title>Not Found</title></html>');
    const out = echo.get(key);
    echo.delete(key);
    return send(res, 200, 'application/json; charset=utf-8', out);
  }
  if (url.pathname === '/__dump') {  // debugging aid: current sheet contents
    const dump = {};
    spreadsheets.forEach((ss, id) => {
      dump[id] = {};
      ss.sheets.forEach((s, n) => { dump[id][n] = s.cells; });
    });
    return send(res, 200, 'application/json', JSON.stringify(dump, null, 1));
  }
  send(res, 404, 'text/html', '<html><head><title>Page Not Found</title></head></html>');
}).listen(port, '127.0.0.1', () => {
  console.log(`hibernate.sheets emulator: http://127.0.0.1:${port}/macros/s/${deploymentId}/exec  (TZ=${process.env.TZ}, ${ordered.length} engine files)`);
});
