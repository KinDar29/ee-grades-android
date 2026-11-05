/**
 * ============================================================================
 *  Utils.gs  ·  Small helpers used everywhere else
 * ============================================================================
 */

/* ------------------------------- properties ------------------------------ */

function props_() {
  return PropertiesService.getScriptProperties();
}

function getProp_(key, fallback) {
  var v = props_().getProperty(key);
  return (v === null || v === '') ? (fallback === undefined ? '' : fallback) : v;
}

function setProp_(key, value) {
  props_().setProperty(key, String(value));
}

/** Returns an existing secret or creates one on first use. */
function secret_(key) {
  var v = props_().getProperty(key);
  if (!v) {
    v = Utilities.base64EncodeWebSafe(
      Utilities.getUuid() + '|' + Utilities.getUuid() + '|' + new Date().getTime()
    );
    props_().setProperty(key, v);
  }
  return v;
}

/* -------------------------------- spreadsheets --------------------------- */

var _masterCache = null;

/** The master database spreadsheet. Throws a friendly error if not set up. */
function master_() {
  if (_masterCache) return _masterCache;
  var id = getProp_(PROP.MASTER_ID);
  if (!id) {
    throw new Error(
      'The database has not been set up yet. Open the Apps Script editor and run setUp() once.'
    );
  }
  _masterCache = SpreadsheetApp.openById(id);
  return _masterCache;
}

/** Get a tab from the master workbook, creating it from SCHEMA if missing. */
var _schemaChecked = {};

function tab_(name) {
  var ss = master_();
  var sh = ss.getSheetByName(name);
  if (!sh) sh = createTab_(ss, name, SCHEMA[name] || []);
  else if (!_schemaChecked[name] && name === TAB.STUDENTS) ensureColumns_(sh, SCHEMA[name]);
  _schemaChecked[name] = true;
  return sh;
}

/** Append any of `names` missing from the header row (used when a new version adds columns). */
function ensureColumns_(sheet, names) {
  var lastCol = Math.max(sheet.getLastColumn(), 1);
  var present = sheet.getRange(1, 1, 1, lastCol).getValues()[0].map(function (h) { return str_(h); });
  while (present.length && !present[present.length - 1]) present.pop();
  names.forEach(function (h) {
    if (present.indexOf(h) > -1) return;
    var col = present.length + 1;
    if (col > sheet.getMaxColumns()) sheet.insertColumnAfter(sheet.getMaxColumns());
    sheet.getRange(1, col).setValue(h)
      .setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG).setFontWeight('bold');
    present.push(h);
  });
}

/** Create a formatted tab with the given headers. */
function createTab_(ss, name, headers, systemFromIndex) {
  var sh = ss.getSheetByName(name);
  if (!sh) sh = ss.insertSheet(name);
  if (headers && headers.length) {
    sh.getRange(1, 1, 1, headers.length).setValues([headers]);
    var head = sh.getRange(1, 1, 1, headers.length);
    head.setBackground(BRAND.HEADER_BG)
        .setFontColor(BRAND.HEADER_FG)
        .setFontWeight('bold')
        .setVerticalAlignment('middle');
    sh.setFrozenRows(1);
    sh.setRowHeight(1, 30);
    if (typeof systemFromIndex === 'number' && systemFromIndex < headers.length) {
      sh.getRange(1, systemFromIndex + 1, 1, headers.length - systemFromIndex)
        .setBackground('#37474f');
    }
    if (sh.getMaxColumns() > headers.length) {
      sh.deleteColumns(headers.length + 1, sh.getMaxColumns() - headers.length);
    }
    sh.autoResizeColumns(1, Math.min(headers.length, 20));
  }
  return sh;
}

/* --------------------------------- tables -------------------------------- */

/**
 * Read a whole sheet as an array of plain objects.
 * Each object also carries a non-enumerable-ish `_row` = its 1-based sheet row.
 */
function readTable_(sheet) {
  var lastRow = sheet.getLastRow();
  var lastCol = sheet.getLastColumn();
  if (lastRow < 1 || lastCol < 1) return { headers: [], rows: [] };
  var headers = sheet.getRange(1, 1, 1, lastCol).getValues()[0].map(function (h) {
    return String(h).trim();
  });
  if (lastRow < 2) return { headers: headers, rows: [] };
  var values = sheet.getRange(2, 1, lastRow - 1, lastCol).getValues();
  var rows = [];
  for (var i = 0; i < values.length; i++) {
    var blank = true;
    var obj = { _row: i + 2 };
    for (var c = 0; c < headers.length; c++) {
      if (!headers[c]) continue;
      var v = values[i][c];
      obj[headers[c]] = v;
      if (v !== '' && v !== null) blank = false;
    }
    if (!blank) rows.push(obj);
  }
  return { headers: headers, rows: rows };
}

/** Make sure the sheet physically has at least `needed` rows. */
function ensureRows_(sheet, needed) {
  var have = sheet.getMaxRows();
  if (needed > have) sheet.insertRowsAfter(have, needed - have + 50);
}

/** Append one object as a row, matching by header name. */
function appendObject_(sheet, obj) {
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var row = headers.map(function (h) {
    var key = String(h).trim();
    return obj.hasOwnProperty(key) && obj[key] !== undefined ? obj[key] : '';
  });
  sheet.appendRow(row);
  return sheet.getLastRow();
}

/** Update selected fields of an existing row (1-based sheet row number). */
function updateObject_(sheet, rowNumber, obj) {
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]
    .map(function (h) { return String(h).trim(); });
  var current = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
  for (var i = 0; i < headers.length; i++) {
    if (obj.hasOwnProperty(headers[i]) && obj[headers[i]] !== undefined) {
      current[i] = obj[headers[i]];
    }
  }
  sheet.getRange(rowNumber, 1, 1, headers.length).setValues([current]);
}

/** Find the first row object whose `key` column equals `value` (case-insensitive). */
function findRow_(sheet, key, value) {
  var t = readTable_(sheet);
  var needle = String(value == null ? '' : value).trim().toLowerCase();
  for (var i = 0; i < t.rows.length; i++) {
    if (String(t.rows[i][key] == null ? '' : t.rows[i][key]).trim().toLowerCase() === needle) {
      return t.rows[i];
    }
  }
  return null;
}

/* --------------------------------- misc ---------------------------------- */

function uid_(prefix) {
  var stamp = new Date().getTime().toString(36).toUpperCase();
  var rand = Math.floor(Math.random() * 1679616).toString(36).toUpperCase();
  while (rand.length < 4) rand = '0' + rand;
  return prefix + '-' + stamp + rand.substring(0, 4);
}

function nowStamp_() {
  return Utilities.formatDate(new Date(), APP.TIMEZONE, 'yyyy-MM-dd HH:mm:ss');
}

function today_() {
  return Utilities.formatDate(new Date(), APP.TIMEZONE, 'yyyy-MM-dd');
}

function str_(v) {
  return v === null || v === undefined ? '' : String(v).trim();
}

function bool_(v) {
  var s = str_(v).toUpperCase();
  return s === 'TRUE' || s === 'YES' || s === 'Y' || s === '1' || v === true;
}

/** Number or null — blank cells must stay blank, not become zero. */
function num_(v) {
  if (v === '' || v === null || v === undefined) return null;
  var n = Number(v);
  return isNaN(n) ? null : n;
}

function round_(n, places) {
  if (n === null || n === undefined || isNaN(n)) return null;
  var f = Math.pow(10, places === undefined ? 2 : places);
  return Math.round(n * f) / f;
}

/** Convert a 1-based column index to its A1 letter. */
function colLetter_(index) {
  var s = '';
  while (index > 0) {
    var m = (index - 1) % 26;
    s = String.fromCharCode(65 + m) + s;
    index = Math.floor((index - m) / 26);
  }
  return s;
}

function bytesToHex_(bytes) {
  var out = '';
  for (var i = 0; i < bytes.length; i++) {
    var b = (bytes[i] + 256) % 256;
    out += (b < 16 ? '0' : '') + b.toString(16);
  }
  return out;
}

var _lockDepth = 0;

/** Run fn while holding the script lock. Safe to nest: inner calls reuse the outer lock. */
function withLock_(fn) {
  if (_lockDepth > 0) return fn();
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(APP.LOCK_TIMEOUT_MS)) {
    throw new Error('The system is busy handling another change. Please try again in a moment.');
  }
  _lockDepth++;
  try {
    return fn();
  } finally {
    _lockDepth--;
    lock.releaseLock();
  }
}

/* --------------------------------- drive --------------------------------- */

function rootFolder_() {
  var id = getProp_(PROP.ROOT_FOLDER);
  if (!id) {
    throw new Error('No root Drive folder is configured. Run setUp() and provide the folder ID.');
  }
  return DriveApp.getFolderById(id);
}

/** Get a subfolder by name, creating it if it does not exist. */
function folderByName_(parent, name) {
  var it = parent.getFoldersByName(name);
  if (it.hasNext()) return it.next();
  return parent.createFolder(name);
}

function moveFile_(fileId, folder) {
  var file = DriveApp.getFileById(fileId);
  var parents = file.getParents();
  while (parents.hasNext()) {
    var p = parents.next();
    if (p.getId() !== folder.getId()) p.removeFile(file);
  }
  var already = false;
  var check = file.getParents();
  while (check.hasNext()) {
    if (check.next().getId() === folder.getId()) already = true;
  }
  if (!already) folder.addFile(file);
  return file;
}

/* -------------------------------- settings ------------------------------- */
/* Both of these are read many times per request, so they are memoised for the
   lifetime of one execution. Every Apps Script call gets a fresh runtime, so
   there is no risk of serving a stale value to a later request.             */

var _settingsCache = null;
var _scaleCache = null;

function settings_() {
  if (_settingsCache) return _settingsCache;
  var t = readTable_(tab_(TAB.SETTINGS));
  var out = {};
  t.rows.forEach(function (r) {
    if (str_(r['Key'])) out[str_(r['Key'])] = str_(r['Value']);
  });
  _settingsCache = out;
  return out;
}

function setSetting_(key, value) {
  var sh = tab_(TAB.SETTINGS);
  var row = findRow_(sh, 'Key', key);
  if (row) {
    updateObject_(sh, row._row, { 'Value': value });
  } else {
    appendObject_(sh, { 'Key': key, 'Value': value, 'What it does': '' });
  }
  _settingsCache = null;
}

/* ------------------------------- grade scale ----------------------------- */

function gradeScale_() {
  if (_scaleCache) return _scaleCache;
  _scaleCache = readTable_(tab_(TAB.SCALE)).rows.map(function (r) {
    return {
      min: num_(r['Min %']),
      max: num_(r['Max %']),
      grade: num_(r['Grade']),
      remark: str_(r['Remark'])
    };
  });
  return _scaleCache;
}

/** Convert a percentage into the numeric grade defined on the GradeScale tab. */
function transmute_(percent) {
  if (percent === null || percent === undefined || isNaN(percent)) return null;
  var bands = gradeScale_();
  for (var i = 0; i < bands.length; i++) {
    if (bands[i].min === null || bands[i].max === null) continue;
    if (percent >= bands[i].min && percent <= bands[i].max) {
      return { grade: bands[i].grade, remark: bands[i].remark };
    }
  }
  return null;
}

/* --------------------------------- audit --------------------------------- */

function logAction_(actor, role, action, target, details) {
  try {
    appendObject_(tab_(TAB.LOG), {
      'Timestamp': nowStamp_(),
      'Actor': str_(actor),
      'Role': str_(role),
      'Action': str_(action),
      'Target': str_(target),
      'Details': typeof details === 'string' ? details : JSON.stringify(details || {})
    });
  } catch (err) {
    // Logging must never break a real operation.
    console.warn('Audit log failed: ' + err);
  }
}
