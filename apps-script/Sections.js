/**
 * ============================================================================
 *  Sections.gs  ·  One Google Sheet per class
 * ============================================================================
 *  Every section file has four tabs:
 *    Info         — read-only description of the class
 *    Grades       — the grid you type into: 4 fixed columns, then one column
 *                   per requirement
 *    Assessments  — max score, weight, type, date and published flag for each
 *                   requirement column on the Grades tab
 *    Summary      — computed totals, rebuilt on demand
 *
 *  You may edit the Grades tab freely — add a column, rename one, type scores.
 *  The script reconciles the Assessments tab with it every time the class is
 *  opened, so the two can never drift apart for long.
 * ============================================================================
 */

/* ------------------------------ construction ----------------------------- */

function buildSectionSpreadsheet_(fileName, folder, info) {
  var ss = SpreadsheetApp.create(fileName);
  moveFile_(ss.getId(), folder);
  ss.setSpreadsheetTimeZone(APP.TIMEZONE);

  // Info
  var infoSheet = ss.getSheets()[0].setName(STAB.INFO);
  writeSectionInfo_(ss, info);

  // Grades
  var grades = ss.insertSheet(STAB.GRADES);
  grades.getRange(1, 1, 1, GRADES_FIXED.length).setValues([GRADES_FIXED]);
  grades.getRange(1, 1, 1, GRADES_FIXED.length)
    .setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG).setFontWeight('bold');
  grades.setFrozenRows(1);
  grades.setFrozenColumns(GRADES_FIXED.length);
  grades.setRowHeight(1, 34);
  grades.setColumnWidth(1, 150);
  grades.setColumnWidth(2, 180);
  grades.setColumnWidth(3, 180);

  // Assessments
  createTab_(ss, STAB.ASSESSMENTS, SCHEMA[STAB.ASSESSMENTS]);
  var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
  asSheet.setColumnWidth(1, 150);
  asSheet.setColumnWidth(2, 240);
  asSheet.getRange(1, 8).setNote('Managed by the app — this is the matching column on the Grades tab.');

  // Summary
  createTab_(ss, STAB.SUMMARY, ['Student ID', 'Name', 'Points Earned', 'Points Possible',
    'Percentage', 'Grade', 'Remark', 'Recorded', 'Updated']);

  ss.setActiveSheet(grades);
  return ss;
}

function writeSectionInfo_(ss, info) {
  var sheet = ss.getSheetByName(STAB.INFO) || ss.insertSheet(STAB.INFO, 0);
  sheet.clear();
  var rows = [
    ['Electrical Engineering — Class Record', ''],
    ['', ''],
    ['Section ID', info.sectionId || ''],
    ['Section', info.sectionName || ''],
    ['Subject Code', info.subjectCode || ''],
    ['Subject Title', info.subjectTitle || ''],
    ['Units', info.units === null || info.units === undefined ? '' : info.units],
    ['Semester', info.semesterName || ''],
    ['Instructor', info.instructor || ''],
    ['Schedule', info.schedule || ''],
    ['Room', info.room || ''],
    ['', ''],
    ['How to use this file', ''],
    ['1.', 'Type scores straight into the "Grades" tab. Leave a cell blank if the student has no score yet.'],
    ['2.', 'To add a requirement, add a new column header on "Grades" — or use the admin dashboard.'],
    ['3.', 'Set each requirement\'s max score and weight on the "Assessments" tab.'],
    ['4.', 'Do not rename or reorder the first four columns of the "Grades" tab.'],
    ['5.', 'Do not edit the "Section ID" above — the portal uses it to find this file.']
  ];
  sheet.getRange(1, 1, rows.length, 2).setValues(rows);
  sheet.getRange(1, 1, 1, 2).merge().setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG)
    .setFontWeight('bold').setFontSize(13).setHorizontalAlignment('center');
  sheet.getRange(3, 1, 9, 1).setFontWeight('bold');
  sheet.getRange(13, 1, 1, 2).merge().setBackground(BRAND.BAND).setFontWeight('bold');
  sheet.setColumnWidth(1, 190);
  sheet.setColumnWidth(2, 620);
  sheet.getRange(14, 2, 5, 1).setWrap(true);
  return sheet;
}

/* --------------------------------- access -------------------------------- */

function openSectionFile_(sectionId) {
  var record = getSectionRecord_(sectionId);
  if (!record.spreadsheetId) throw new Error('That class has no spreadsheet yet.');
  var ss;
  try {
    ss = SpreadsheetApp.openById(record.spreadsheetId);
  } catch (err) {
    throw new Error('The spreadsheet for this class could not be opened. It may have been deleted or moved out of your Drive.');
  }
  if (!ss.getSheetByName(STAB.GRADES)) {
    var g = ss.insertSheet(STAB.GRADES);
    g.getRange(1, 1, 1, GRADES_FIXED.length).setValues([GRADES_FIXED]);
    g.setFrozenRows(1);
  }
  if (!ss.getSheetByName(STAB.ASSESSMENTS)) {
    createTab_(ss, STAB.ASSESSMENTS, SCHEMA[STAB.ASSESSMENTS]);
  }
  return { record: record, ss: ss };
}

function letterToIndex_(letter) {
  var s = str_(letter).toUpperCase();
  if (!s) return 0;
  if (/^\d+$/.test(s)) return Number(s);
  if (!/^[A-Z]+$/.test(s)) return 0;
  var n = 0;
  for (var i = 0; i < s.length; i++) n = n * 26 + (s.charCodeAt(i) - 64);
  return n;
}

/* --------------------------- assessment syncing -------------------------- */

function assessmentFrom_(row, colIndex) {
  return {
    id: str_(row['Assessment ID']),
    name: str_(row['Name']),
    type: str_(row['Type']) || 'Other',
    max: num_(row['Max Score']) === null ? 100 : num_(row['Max Score']),
    weight: num_(row['Weight %']) === null ? 0 : num_(row['Weight %']),
    date: row['Date'] instanceof Date
      ? Utilities.formatDate(row['Date'], APP.TIMEZONE, 'yyyy-MM-dd')
      : str_(row['Date']),
    published: str_(row['Published']) === '' ? true : bool_(row['Published']),
    col: colIndex,
    colLetter: colLetter_(colIndex),
    notes: str_(row['Notes'])
  };
}

/**
 * Work out which Assessments row belongs to which Grades column.
 *
 * Two passes, and the order matters. Names are matched first, because that
 * survives the common case of deleting a column in the sheet — every later
 * column shifts left, and matching on the recorded column first would rename
 * each assessment to its neighbour's name, one after another. The recorded
 * column is used only for the leftovers, which is exactly the rename case.
 *
 * Returns { pairs: [{row, col, headerName}], fresh: [{col, headerName}],
 *           orphans: [row] } — all decisions, no writes.
 */
function matchAssessments_(headers, rows, fixed, lastCol) {
  var claimed = {};
  var takenCol = {};
  var pairs = [];
  var unmatched = [];

  // Pass 1 — exact name match.
  for (var c = fixed + 1; c <= lastCol; c++) {
    var headerName = str_(headers[c - 1]);
    if (!headerName) continue;
    var hit = null;
    for (var i = 0; i < rows.length; i++) {
      if (claimed[rows[i]._row]) continue;
      if (str_(rows[i]['Name']).toLowerCase() === headerName.toLowerCase()) { hit = rows[i]; break; }
    }
    if (hit) {
      claimed[hit._row] = true;
      takenCol[c] = true;
      pairs.push({ row: hit, col: c, headerName: headerName });
    } else {
      unmatched.push({ col: c, headerName: headerName });
    }
  }

  // Pass 2 — the column each leftover header sits in (a renamed column).
  var fresh = [];
  unmatched.forEach(function (u) {
    var hit = null;
    for (var i = 0; i < rows.length; i++) {
      if (claimed[rows[i]._row]) continue;
      if (letterToIndex_(rows[i]['Column']) === u.col) { hit = rows[i]; break; }
    }
    if (hit) {
      claimed[hit._row] = true;
      pairs.push({ row: hit, col: u.col, headerName: u.headerName });
    } else {
      fresh.push(u);
    }
  });

  pairs.sort(function (a, b) { return a.col - b.col; });

  var orphans = rows.filter(function (r) { return !claimed[r._row]; });
  return { pairs: pairs, fresh: fresh, orphans: orphans };
}

/**
 * Reconcile the Assessments tab with the header row of the Grades tab and
 * write back anything that moved. Returns the assessment list in column order.
 */
function syncAssessments_(ss) {
  var grades = ss.getSheetByName(STAB.GRADES);
  var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
  var fixed = GRADES_FIXED.length;
  var lastCol = Math.max(grades.getLastColumn(), fixed);
  var headers = grades.getRange(1, 1, 1, lastCol).getValues()[0];

  var table = readTable_(asSheet);
  var m = matchAssessments_(headers, table.rows, fixed, lastCol);
  var byCol = {};

  m.pairs.forEach(function (p) {
    var patch = {};
    if (str_(p.row['Name']) !== p.headerName) patch['Name'] = p.headerName;
    if (letterToIndex_(p.row['Column']) !== p.col) patch['Column'] = colLetter_(p.col);
    if (Object.keys(patch).length) {
      updateObject_(asSheet, p.row._row, patch);
      for (var k in patch) p.row[k] = patch[k];
    }
    byCol[p.col] = assessmentFrom_(p.row, p.col);
  });

  m.fresh.forEach(function (f) {
    var obj = {
      'Assessment ID': uid_('ASM'),
      'Name': f.headerName,
      'Type': 'Other',
      'Max Score': 100,
      'Weight %': 0,
      'Date': '',
      'Published': 'TRUE',
      'Column': colLetter_(f.col),
      'Notes': 'Added from the Grades tab on ' + today_()
    };
    appendObject_(asSheet, obj);
    byCol[f.col] = assessmentFrom_(obj, f.col);
  });

  // Rows whose column has disappeared: keep the record, drop the link.
  m.orphans.forEach(function (r) {
    if (str_(r['Column'])) updateObject_(asSheet, r._row, { 'Column': '' });
  });

  var out = [];
  for (var c = fixed + 1; c <= lastCol; c++) if (byCol[c]) out.push(byCol[c]);
  return out;
}

/**
 * Same mapping as syncAssessments_ but without writing anything.
 * Used for student views so a page load never edits the teacher's file.
 */
function readAssessmentsReadOnly_(ss) {
  var grades = ss.getSheetByName(STAB.GRADES);
  var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
  var fixed = GRADES_FIXED.length;
  var lastCol = Math.max(grades.getLastColumn(), fixed);
  var headers = grades.getRange(1, 1, 1, lastCol).getValues()[0];
  var rows = asSheet ? readTable_(asSheet).rows : [];

  var m = matchAssessments_(headers, rows, fixed, lastCol);
  var byCol = {};
  m.pairs.forEach(function (p) {
    var a = assessmentFrom_(p.row, p.col);
    a.name = p.headerName;
    byCol[p.col] = a;
  });
  m.fresh.forEach(function (f) {
    byCol[f.col] = {
      id: 'COL' + f.col, name: f.headerName, type: 'Other', max: 100, weight: 0,
      date: '', published: true, col: f.col, colLetter: colLetter_(f.col), notes: ''
    };
  });

  var out = [];
  for (var c = fixed + 1; c <= lastCol; c++) if (byCol[c]) out.push(byCol[c]);
  return out;
}

/* ------------------------------ reading data ----------------------------- */

function readSectionData_(sectionId, readOnly) {
  var opened = openSectionFile_(sectionId);
  var ss = opened.ss;
  var grades = ss.getSheetByName(STAB.GRADES);
  var assessments = readOnly ? readAssessmentsReadOnly_(ss) : syncAssessments_(ss);

  var fixed = GRADES_FIXED.length;
  var lastRow = grades.getLastRow();
  var lastCol = Math.max(grades.getLastColumn(), fixed);
  var roster = [];
  var statuses = readFinalStatuses_(ss);

  if (lastRow >= 2) {
    var values = grades.getRange(2, 1, lastRow - 1, lastCol).getValues();
    for (var i = 0; i < values.length; i++) {
      var sid = str_(values[i][0]);
      if (!sid) continue;
      var scores = {};
      assessments.forEach(function (a) {
        scores[a.id] = num_(values[i][a.col - 1]);
      });
      roster.push({
        studentId: sid,
        lastName: str_(values[i][1]),
        firstName: str_(values[i][2]),
        row: i + 2,
        scores: scores,
        finalStatus: statuses[sid.toLowerCase()] || ''
      });
    }
  }

  roster.sort(function (a, b) {
    var ka = (a.lastName + a.firstName).toLowerCase();
    var kb = (b.lastName + b.firstName).toLowerCase();
    return ka > kb ? 1 : (ka < kb ? -1 : 0);
  });

  return { section: opened.record, ss: ss, assessments: assessments, roster: roster };
}

/* ----------------------------- final status ------------------------------ */
/*  INC and Dropped can't be worked out from scores, so the admin sets them per
    student. They live in a small "Final Status" tab of the class sheet
    (Student ID | Status | Updated), created the first time one is set.        */

function readFinalStatuses_(ss) {
  var out = {};
  var sh = ss.getSheetByName(STAB.STATUS);
  if (!sh) return out;
  readTable_(sh).rows.forEach(function (r) {
    var sid = str_(r['Student ID']).toLowerCase();
    var st = normalizeStatus_(r['Status']);
    if (sid && st) out[sid] = st;
  });
  return out;
}

function normalizeStatus_(v) {
  var s = str_(v).toLowerCase();
  if (s === 'inc' || s === 'incomplete') return 'INC';
  if (s === 'dropped' || s === 'drp' || s === 'drop') return 'Dropped';
  return '';
}

function setFinalStatus_(session, sectionId, studentId, status) {
  var sid = str_(studentId);
  var st = normalizeStatus_(status);
  if (!sid) throw new Error('Which student?');
  if (str_(status) && !st) throw new Error('Status must be INC, Dropped or blank.');

  return withLock_(function () {
    var ss = openSectionFile_(sectionId).ss;
    var sh = ss.getSheetByName(STAB.STATUS);
    if (!sh) {
      if (!st) return { studentId: sid, status: '' };
      sh = createTab_(ss, STAB.STATUS, ['Student ID', 'Status', 'Updated']);
    }
    var row = findRow_(sh, 'Student ID', sid);
    if (row && !st) sh.deleteRow(row._row);
    else if (row) updateObject_(sh, row._row, { 'Status': st, 'Updated': nowStamp_() });
    else if (st) appendObject_(sh, { 'Student ID': sid, 'Status': st, 'Updated': nowStamp_() });
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'FINAL_STATUS', sectionId, { student: sid, status: st || '(cleared)' });
    return { studentId: sid, status: st };
  });
}

/**
 * One standing for a student, shared by the class record and the Summary of
 * Grades: DROPPED, INC, NONE (no scores yet), CONDITIONAL (4.00), FAILED or
 * PASSED.
 */
function standingOf_(grade, finalStatus) {
  var st = normalizeStatus_(finalStatus);
  if (st === 'Dropped') return 'DROPPED';
  if (st === 'INC') return 'INC';
  if (!grade || grade.percent === null || grade.percent === undefined) return 'NONE';
  if (isConditional_(grade)) return 'CONDITIONAL';
  if (isFailing_(grade)) return 'FAILED';
  return 'PASSED';
}

function isConditional_(grade) {
  if (!grade) return false;
  if (/condition/i.test(str_(grade.remark))) return true;
  return grade.grade !== null && grade.grade !== undefined && Math.abs(Number(grade.grade) - 4) < 1e-9;
}

/* ---------------------------- score snapshots ----------------------------- */
/*  A compact copy of one class's published scores, shared by every student's
    "what's new" check and kept in CacheService for SNAPSHOT_SECONDS. Saving
    scores or changing requirements through the portal clears it at once;
    scores typed straight into the sheet show up within that time.           */

var SNAPSHOT_SECONDS = 600;

function snapshotKey_(sectionId) {
  return 'snap:' + String(sectionId);
}

function sectionSnapshot_(sectionId) {
  var cache = CacheService.getScriptCache();
  var key = snapshotKey_(sectionId);
  var hit = cache.get(key);
  if (hit) {
    try { return JSON.parse(hit); } catch (err) { /* rebuild below */ }
  }

  var data = readSectionData_(sectionId, true);
  var items = data.assessments
    .filter(function (a) { return a.published; })
    .map(function (a) { return { id: a.id, name: a.name, max: a.max }; });

  var scores = {};
  data.roster.forEach(function (s) {
    var row = {};
    var any = false;
    items.forEach(function (a) {
      var v = s.scores[a.id];
      if (v !== null && v !== undefined && !isNaN(v)) { row[a.id] = Number(v); any = true; }
    });
    if (any) scores[s.studentId.toLowerCase()] = row;
  });

  var snap = { items: items, scores: scores };
  var text = JSON.stringify(snap);
  if (text.length < 90000) {
    try { cache.put(key, text, SNAPSHOT_SECONDS); } catch (err) { /* too big or cache busy */ }
  }
  return snap;
}

function clearSectionSnapshot_(sectionId) {
  try { CacheService.getScriptCache().removeAll([snapshotKey_(sectionId), directKey_(sectionId)]); } catch (err) { /* ignore */ }
}

/** Per-assessment class statistics. */
function assessmentStats_(assessments, roster) {
  var stats = {};
  assessments.forEach(function (a) {
    var vals = [];
    roster.forEach(function (s) {
      var v = s.scores[a.id];
      if (v !== null && v !== undefined && !isNaN(v)) vals.push(Number(v));
    });
    if (!vals.length) {
      stats[a.id] = { count: 0, average: null, high: null, low: null };
      return;
    }
    var sum = vals.reduce(function (x, y) { return x + y; }, 0);
    stats[a.id] = {
      count: vals.length,
      average: round_(sum / vals.length, 2),
      high: Math.max.apply(null, vals),
      low: Math.min.apply(null, vals)
    };
  });
  return stats;
}

/* ---------------------------- grade computation -------------------------- */

/**
 * Running grade for one student.
 * Blank scores are treated as "not yet recorded" and left out of the average,
 * unless COUNT_BLANK_AS_ZERO is set to TRUE on the Settings tab.
 */
function computeGrade_(assessments, scores, options) {
  options = options || {};
  var countBlank = !!options.countBlankAsZero;
  var graded = assessments.filter(function (a) { return a.max > 0; });
  var weighted = graded.filter(function (a) { return a.weight > 0; });
  var useWeights = weighted.length > 0;
  var pool = useWeights ? weighted : graded;

  var earned = 0, possible = 0, weightUsed = 0, weightedSum = 0, recorded = 0;

  pool.forEach(function (a) {
    var raw = scores[a.id];
    var has = raw !== null && raw !== undefined && !isNaN(raw);
    if (has) recorded++;
    if (!has && !countBlank) return;
    var value = has ? Number(raw) : 0;
    earned += value;
    possible += a.max;
    if (useWeights) {
      weightedSum += (value / a.max) * a.weight;
      weightUsed += a.weight;
    }
  });

  var percent = null;
  if (useWeights && weightUsed > 0) percent = (weightedSum / weightUsed) * 100;
  else if (possible > 0) percent = (earned / possible) * 100;

  var scale = percent === null ? null : transmute_(round_(percent, 0));

  return {
    mode: useWeights ? 'weighted' : 'points',
    earned: round_(earned, 2),
    possible: round_(possible, 2),
    percent: percent === null ? null : round_(percent, 0),
    grade: scale ? scale.grade : null,
    remark: scale ? scale.remark : '',
    recorded: recorded,
    items: pool.length,
    weightCovered: useWeights ? round_(weightUsed, 2) : null,
    weightTotal: useWeights
      ? round_(weighted.reduce(function (s, a) { return s + a.weight; }, 0), 2)
      : null
  };
}

/* ------------------------------ writing data ----------------------------- */

/**
 * Save score changes.
 * `changes` is [{ studentId, assessmentId, value }] — value may be '' to clear.
 * Formulas in untouched cells are preserved.
 */
function saveScores_(session, sectionId, changes) {
  if (!changes || !changes.length) return { saved: 0 };

  return withLock_(function () {
    var data = readSectionData_(sectionId);
    var grades = data.ss.getSheetByName(STAB.GRADES);
    var fixed = GRADES_FIXED.length;
    var lastRow = grades.getLastRow();
    var lastCol = Math.max(grades.getLastColumn(), fixed);
    if (lastRow < 2) throw new Error('This class has no students yet.');

    var rowOf = {};
    data.roster.forEach(function (s) { rowOf[s.studentId.toLowerCase()] = s.row; });
    var colOf = {};
    var maxOf = {};
    data.assessments.forEach(function (a) { colOf[a.id] = a.col; maxOf[a.id] = a.max; });

    var width = lastCol - fixed;
    if (width < 1) throw new Error('This class has no requirements yet.');

    var range = grades.getRange(2, fixed + 1, lastRow - 1, width);
    var values = range.getValues();
    var formulas = range.getFormulas();
    var block = values.map(function (r, i) {
      return r.map(function (v, j) { return formulas[i][j] ? formulas[i][j] : v; });
    });

    var saved = 0;
    var problems = [];
    changes.forEach(function (ch) {
      var r = rowOf[str_(ch.studentId).toLowerCase()];
      var c = colOf[str_(ch.assessmentId)];
      if (!r || !c) { problems.push('Skipped ' + str_(ch.studentId)); return; }

      var raw = ch.value;
      var out;
      if (raw === '' || raw === null || raw === undefined) {
        out = '';
      } else {
        var n = Number(raw);
        if (isNaN(n)) { problems.push('"' + raw + '" is not a number'); return; }
        if (n < 0) { problems.push('Negative score ignored for ' + str_(ch.studentId)); return; }
        var max = maxOf[str_(ch.assessmentId)];
        if (max > 0 && n > max * 2) {
          problems.push(str_(ch.studentId) + ': ' + n + ' is more than twice the max (' + max + ')');
          return;
        }
        out = n;
      }
      block[r - 2][c - fixed - 1] = out;
      saved++;
    });

    range.setValues(block);
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'SCORES_SAVE', sectionId, { count: saved });
    return { saved: saved, problems: problems };
  });
}

/* ------------------------------ assessments ------------------------------ */

function createAssessment_(session, sectionId, data) {
  var name = str_(data.name);
  if (!name) throw new Error('Please give the requirement a name, e.g. "Midterm Exam".');

  return withLock_(function () {
    var opened = openSectionFile_(sectionId);
    var ss = opened.ss;
    var grades = ss.getSheetByName(STAB.GRADES);
    var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
    var existing = syncAssessments_(ss);

    var clash = existing.filter(function (a) { return a.name.toLowerCase() === name.toLowerCase(); });
    if (clash.length) throw new Error('This class already has a requirement called "' + name + '".');

    var col = Math.max(grades.getLastColumn(), GRADES_FIXED.length) + 1;
    grades.getRange(1, col).setValue(name)
      .setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG)
      .setFontWeight('bold').setWrap(true);
    grades.setColumnWidth(col, 120);

    var id = uid_('ASM');
    appendObject_(asSheet, {
      'Assessment ID': id,
      'Name': name,
      'Type': str_(data.type) || 'Other',
      'Max Score': num_(data.max) === null ? 100 : num_(data.max),
      'Weight %': num_(data.weight) === null ? 0 : num_(data.weight),
      'Date': str_(data.date),
      'Published': data.published === false ? 'FALSE' : 'TRUE',
      'Column': colLetter_(col),
      'Notes': str_(data.notes)
    });
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'ASSESSMENT_CREATE', sectionId, name);
    return { id: id, column: colLetter_(col) };
  });
}

function updateAssessment_(session, sectionId, data) {
  return withLock_(function () {
    var opened = openSectionFile_(sectionId);
    var ss = opened.ss;
    syncAssessments_(ss);
    var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
    var row = findRow_(asSheet, 'Assessment ID', data.id);
    if (!row) throw new Error('That requirement no longer exists.');

    var patch = {};
    if (data.type !== undefined) patch['Type'] = str_(data.type);
    if (data.max !== undefined) patch['Max Score'] = num_(data.max) === null ? '' : num_(data.max);
    if (data.weight !== undefined) patch['Weight %'] = num_(data.weight) === null ? '' : num_(data.weight);
    if (data.date !== undefined) patch['Date'] = str_(data.date);
    if (data.published !== undefined) patch['Published'] = data.published ? 'TRUE' : 'FALSE';
    if (data.notes !== undefined) patch['Notes'] = str_(data.notes);

    if (data.name !== undefined && str_(data.name) && str_(data.name) !== str_(row['Name'])) {
      patch['Name'] = str_(data.name);
      var ci = letterToIndex_(row['Column']);
      if (ci) ss.getSheetByName(STAB.GRADES).getRange(1, ci).setValue(str_(data.name));
    }
    updateObject_(asSheet, row._row, patch);
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'ASSESSMENT_UPDATE', sectionId, patch);
    return { ok: true };
  });
}

function deleteAssessment_(session, sectionId, data) {
  return withLock_(function () {
    var opened = openSectionFile_(sectionId);
    var ss = opened.ss;
    syncAssessments_(ss);
    var asSheet = ss.getSheetByName(STAB.ASSESSMENTS);
    var row = findRow_(asSheet, 'Assessment ID', data.id);
    if (!row) throw new Error('That requirement no longer exists.');

    var ci = letterToIndex_(row['Column']);
    if (ci > GRADES_FIXED.length) ss.getSheetByName(STAB.GRADES).deleteColumn(ci);
    asSheet.deleteRow(row._row);
    syncAssessments_(ss);
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'ASSESSMENT_DELETE', sectionId, str_(row['Name']));
    return { ok: true };
  });
}

/* ------------------------------- enrollment ------------------------------ */

function enrollStudents_(session, sectionId, studentIds) {
  if (!studentIds || !studentIds.length) throw new Error('Select at least one student.');

  return withLock_(function () {
    var opened = openSectionFile_(sectionId);
    var grades = opened.ss.getSheetByName(STAB.GRADES);
    var registry = {};
    readTable_(tab_(TAB.STUDENTS)).rows.forEach(function (r) {
      registry[str_(r['Student ID']).toLowerCase()] = r;
    });

    var present = {};
    var lastRow = grades.getLastRow();
    if (lastRow >= 2) {
      grades.getRange(2, 1, lastRow - 1, 1).getValues().forEach(function (r) {
        if (str_(r[0])) present[str_(r[0]).toLowerCase()] = true;
      });
    }

    var added = [], missing = [];
    studentIds.forEach(function (raw) {
      var sid = str_(raw);
      if (!sid) return;
      var key = sid.toLowerCase();
      if (present[key]) return;
      var rec = registry[key];
      if (!rec) { missing.push(sid); return; }
      added.push([
        str_(rec['Student ID']), str_(rec['Last Name']),
        str_(rec['First Name'])
      ]);
      present[key] = true;
    });

    if (added.length) {
      var start = Math.max(grades.getLastRow(), 1) + 1;
      ensureRows_(grades, start + added.length);
      grades.getRange(start, 1, added.length, GRADES_FIXED.length).setValues(added);
    }
    rebuildRoster_(sectionId);
    clearSectionSnapshot_(sectionId);
    logAction_(session.id, session.role, 'ENROLL', sectionId,
      { added: added.length, missing: missing });
    return { added: added.length, missing: missing };
  });
}

function unenrollStudent_(session, sectionId, studentId) {
  return withLock_(function () {
    var opened = openSectionFile_(sectionId);
    var grades = opened.ss.getSheetByName(STAB.GRADES);
    var lastRow = grades.getLastRow();
    if (lastRow < 2) return { removed: 0 };
    var ids = grades.getRange(2, 1, lastRow - 1, 1).getValues();
    var needle = str_(studentId).toLowerCase();
    for (var i = ids.length - 1; i >= 0; i--) {
      if (str_(ids[i][0]).toLowerCase() === needle) {
        grades.deleteRow(i + 2);
        rebuildRoster_(sectionId);
        clearSectionSnapshot_(sectionId);
        logAction_(session.id, session.role, 'UNENROLL', sectionId, studentId);
        return { removed: 1 };
      }
    }
    return { removed: 0 };
  });
}

/** Refresh the master enrollment index from a section's Grades tab. */
function rebuildRoster_(sectionId) {
  // The enrollment index is read, cleared and rewritten as a whole, so every
  // rebuild must hold the lock or two rebuilds can overwrite each other.
  return withLock_(function () { return rebuildRosterUnlocked_(sectionId); });
}

function rebuildRosterUnlocked_(sectionId) {
  var record = getSectionRecord_(sectionId);
  if (!record.spreadsheetId) return { count: 0 };
  var ss = SpreadsheetApp.openById(record.spreadsheetId);
  var grades = ss.getSheetByName(STAB.GRADES);
  var ids = [];
  if (grades && grades.getLastRow() >= 2) {
    grades.getRange(2, 1, grades.getLastRow() - 1, 1).getValues().forEach(function (r) {
      var v = str_(r[0]);
      if (v) ids.push(v);
    });
  }
  writeEnrollmentIndex_(sectionId, record.semesterId, ids);
  updateObject_(tab_(TAB.SECTIONS), record._row, {
    'Enrolled': ids.length,
    'Last Synced': nowStamp_()
  });
  return { count: ids.length };
}

/* -------------------------------- summary -------------------------------- */

function rebuildSummary_(session, sectionId) {
  return withLock_(function () {
    var data = readSectionData_(sectionId);
    var options = { countBlankAsZero: bool_(settings_()['COUNT_BLANK_AS_ZERO']) };
    var sheet = data.ss.getSheetByName(STAB.SUMMARY);
    if (!sheet) {
      sheet = createTab_(data.ss, STAB.SUMMARY, ['Student ID', 'Name', 'Points Earned',
        'Points Possible', 'Percentage', 'Grade', 'Remark', 'Recorded', 'Updated']);
    }
    if (sheet.getLastRow() > 1) {
      sheet.getRange(2, 1, sheet.getLastRow() - 1, sheet.getLastColumn()).clearContent();
    }
    var stamp = nowStamp_();
    var rows = data.roster.map(function (s) {
      var g = computeGrade_(data.assessments, s.scores, options);
      return [
        s.studentId,
        [s.lastName, s.firstName].filter(String).join(', '),
        g.earned, g.possible, g.percent, g.grade, g.remark,
        g.recorded + ' / ' + g.items, stamp
      ];
    });
    if (rows.length) {
      ensureRows_(sheet, rows.length + 1);
      sheet.getRange(2, 1, rows.length, 9).setValues(rows);
    }
    sheet.getRange(2, 5, Math.max(rows.length, 1), 1).setNumberFormat('0.00');
    sheet.getRange(2, 6, Math.max(rows.length, 1), 1).setNumberFormat('0.00');
    logAction_(session.id, session.role, 'SUMMARY_REBUILD', sectionId, { rows: rows.length });
    return { rows: rows.length, url: data.ss.getUrl() + '#gid=' + sheet.getSheetId() };
  });
}
