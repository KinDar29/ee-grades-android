/**
 * ============================================================================
 *  Export.gs  ·  Printable class record (PDF)
 * ============================================================================
 *  Builds a "Class Record" tab inside the section's own spreadsheet — header
 *  block, the full roster with every requirement, totals, percentage, numeric
 *  grade, standing and rank, then a summary and signature block — and exports
 *  that tab as a landscape A4 PDF.
 *
 *  Going through Sheets rather than rendering HTML means the PDF paginates
 *  properly, repeats the header row on every page, and fits the width no
 *  matter how many requirements the class has.
 *
 *  NOTE: this file is the only part of the system that reaches outside Google
 *  Apps Script (UrlFetchApp, to ask Sheets for the PDF). The first time you run
 *  it, Google will ask you to authorise the script again.
 * ============================================================================
 */

/** Tab written inside each section spreadsheet. Rebuilt on every export. */
var RECORD_TAB = 'Class Record';

/* ------------------------------- assembly -------------------------------- */

/**
 * Work out the printable table for one class.
 * Returns { section, assessments, rows, stats } with rows already ranked.
 */
function classRecordData_(sectionId) {
  var data = readSectionData_(sectionId, false);
  var options = { countBlankAsZero: bool_(settings_()['COUNT_BLANK_AS_ZERO']) };

  var rows = data.roster.map(function (r) {
    return { student: r, grade: computeGrade_(data.assessments, r.scores, options) };
  });

  // Rank by percentage, highest first. Equal percentages share a rank.
  var ranked = rows.filter(function (x) { return x.grade.percent !== null; })
                   .sort(function (a, b) { return b.grade.percent - a.grade.percent; });
  var rankOf = {};
  var lastPercent = null, lastRank = 0;
  ranked.forEach(function (x, i) {
    if (lastPercent !== null && Math.abs(x.grade.percent - lastPercent) < 1e-9) {
      rankOf[x.student.studentId] = lastRank;
    } else {
      lastRank = i + 1;
      lastPercent = x.grade.percent;
      rankOf[x.student.studentId] = lastRank;
    }
  });
  rows.forEach(function (x) { x.rank = rankOf[x.student.studentId] || null; });

  var percents = ranked.map(function (x) { return x.grade.percent; });
  var passed = 0, failed = 0;
  rows.forEach(function (x) {
    if (x.grade.percent === null) return;
    if (isFailing_(x.grade)) failed++; else passed++;
  });

  return {
    section: data.section,
    ss: data.ss,
    assessments: data.assessments,
    rows: rows,
    stats: {
      count: rows.length,
      graded: percents.length,
      average: percents.length ? round_(percents.reduce(function (a, b) { return a + b; }, 0) / percents.length, 2) : null,
      highest: percents.length ? Math.max.apply(null, percents) : null,
      lowest: percents.length ? Math.min.apply(null, percents) : null,
      passed: passed,
      failed: failed
    }
  };
}

/** Driven by your own GradeScale tab, so it follows whatever bands you set. */
function isFailing_(grade) {
  if (grade.percent === null) return false;
  if (/fail/i.test(str_(grade.remark))) return true;
  return grade.grade !== null && grade.grade > 3.0;
}

/* ---------------------------- the printable tab -------------------------- */

/**
 * (Re)write the Class Record tab. Returns { sheet, gid, title, lastRow, lastCol }.
 */
function buildClassRecordSheet_(sectionId) {
  var d = classRecordData_(sectionId);
  var sec = d.section;
  var asm = d.assessments;
  var s = settings_();

  var sheet = d.ss.getSheetByName(RECORD_TAB);
  if (!sheet) sheet = d.ss.insertSheet(RECORD_TAB);
  sheet.clear();
  sheet.clearNotes();
  if (sheet.getFilter()) sheet.getFilter().remove();

  var headTail = ['TOTAL', '%', 'GRADE', 'STANDING', 'RANK'];
  var width = 3 + asm.length + headTail.length;

  /* ---- title block ---- */
  var title = str_(s['PORTAL_TITLE']) || 'Electrical Engineering';
  var block = [
    [title.toUpperCase()],
    ['CLASS RECORD'],
    ['']
  ];

  var meta = [
    ['Subject', sec.subjectCode + ' — ' + sec.subjectTitle + (sec.units ? '  (' + sec.units + ' units)' : ''),
     'Semester', sec.semesterName],
    ['Section', sec.name, 'Instructor', sec.instructor || '—'],
    ['Schedule', sec.schedule || '—', 'Room', sec.room || '—'],
    ['Students', String(d.stats.count), 'Generated', nowStamp_()]
  ];

  /* ---- table ---- */
  var header = ['#', 'STUDENT NO.', 'NAME'].concat(
    asm.map(function (a) {
      var sub = '/' + fmtNum_(a.max);
      if (a.weight) sub += '  ·  ' + fmtNum_(a.weight) + '%';
      return a.name + '\n' + sub;
    })
  ).concat(headTail);

  var body = d.rows.map(function (x, i) {
    var g = x.grade;
    var line = [
      i + 1,
      x.student.studentId,
      [str_(x.student.lastName), str_(x.student.firstName)].filter(String).join(', ')
    ];
    asm.forEach(function (a) {
      var v = x.student.scores[a.id];
      line.push(v === null || v === undefined ? '' : v);
    });
    line.push(g.earned === null ? '' : g.earned);
    line.push(g.percent === null ? '' : g.percent);
    line.push(g.grade === null ? '' : g.grade);
    line.push(g.percent === null ? 'No scores yet' : (isFailing_(g) ? 'FAILED' : str_(g.remark) || 'PASSED'));
    line.push(x.rank === null ? '' : x.rank);
    return line;
  });

  /* ---- summary + signatures ---- */
  var st = d.stats;
  var summary = [
    ['SUMMARY', '', '', ''],
    ['Enrolled', st.count, 'Class average', st.average === null ? '—' : fmtNum_(st.average) + '%'],
    ['With scores recorded', st.graded, 'Highest', st.highest === null ? '—' : fmtNum_(st.highest) + '%'],
    ['Passing', st.passed, 'Lowest', st.lowest === null ? '—' : fmtNum_(st.lowest) + '%'],
    ['Failing', st.failed, 'Grading', asm.some(function (a) { return a.weight > 0; })
      ? 'Weighted average of recorded requirements' : 'Total points earned over points possible']
  ];

  /* ---- write it ---- */
  var r = 1;
  sheet.getRange(r, 1).setValue(block[0][0]); var rTitle = r; r++;
  sheet.getRange(r, 1).setValue(block[1][0]); var rSub = r; r++;
  r++; // spacer

  var rMeta = r;
  sheet.getRange(rMeta, 1, meta.length, 4).setValues(meta);
  r += meta.length + 1;

  var rHead = r;
  sheet.getRange(rHead, 1, 1, width).setValues([header]);
  r++;

  var rBody = r;
  if (body.length) {
    ensureRows_(sheet, rBody + body.length + summary.length + 12);
    sheet.getRange(rBody, 1, body.length, width).setValues(body);
    r += body.length;
  } else {
    sheet.getRange(r, 1).setValue('No students are enrolled in this class yet.');
    r++;
  }

  r++; // spacer
  var rSummary = r;
  sheet.getRange(rSummary, 1, summary.length, 4).setValues(summary);
  r += summary.length + 2;

  var rSign = r;
  sheet.getRange(rSign, 1, 3, 4).setValues([
    ['Prepared by', '', 'Noted by', ''],
    ['', '', '', ''],
    [sec.instructor || '________________________', 'Instructor', '________________________', 'Department Chair']
  ]);
  var lastRow = rSign + 2;

  /* ---- formatting ---- */
  sheet.getRange(rTitle, 1, 1, width).merge()
    .setFontSize(14).setFontWeight('bold').setHorizontalAlignment('center');
  sheet.getRange(rSub, 1, 1, width).merge()
    .setFontSize(11).setFontWeight('bold')
    .setHorizontalAlignment('center').setFontColor('#555555');

  sheet.getRange(rMeta, 1, meta.length, 1).setFontWeight('bold');
  sheet.getRange(rMeta, 3, meta.length, 1).setFontWeight('bold');
  sheet.getRange(rMeta, 1, meta.length, 4).setFontSize(9);

  var head = sheet.getRange(rHead, 1, 1, width);
  head.setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG)
      .setFontWeight('bold').setFontSize(8).setWrap(true)
      .setVerticalAlignment('middle').setHorizontalAlignment('center');
  sheet.setRowHeight(rHead, 40);

  if (body.length) {
    var table = sheet.getRange(rHead, 1, body.length + 1, width);
    table.setBorder(true, true, true, true, true, true, '#999999', SpreadsheetApp.BorderStyle.SOLID);
    var data = sheet.getRange(rBody, 1, body.length, width);
    data.setFontSize(9).setVerticalAlignment('middle');
    sheet.getRange(rBody, 1, body.length, 1).setHorizontalAlignment('center');
    sheet.getRange(rBody, 2, body.length, 1).setHorizontalAlignment('left');
    if (asm.length) {
      sheet.getRange(rBody, 4, body.length, asm.length).setHorizontalAlignment('center');
    }
    var tailStart = 4 + asm.length;
    sheet.getRange(rBody, tailStart, body.length, 5).setHorizontalAlignment('center');
    sheet.getRange(rBody, tailStart + 1, body.length, 1).setNumberFormat('0.00');
    sheet.getRange(rBody, tailStart + 2, body.length, 1).setNumberFormat('0.00').setFontWeight('bold');
    sheet.getRange(rBody, tailStart, body.length, 1).setNumberFormat('0.##');

    // Shade the totals block and band alternate rows for readability.
    sheet.getRange(rBody, tailStart, body.length, 5).setBackground('#f2f5f3');
    for (var b = 0; b < body.length; b += 2) {
      sheet.getRange(rBody + b, 1, 1, 3).setBackground('#fafbfa');
    }
    // Mark the failing rows.
    d.rows.forEach(function (x, i) {
      if (isFailing_(x.grade)) {
        sheet.getRange(rBody + i, tailStart + 3).setFontColor('#b42318').setFontWeight('bold');
      }
    });
  }

  sheet.getRange(rSummary, 1, 1, 4).merge().setBackground('#eceee8')
    .setFontWeight('bold').setFontSize(9);
  sheet.getRange(rSummary + 1, 1, summary.length - 1, 4).setFontSize(9);
  sheet.getRange(rSummary + 1, 1, summary.length - 1, 1).setFontWeight('bold');
  sheet.getRange(rSummary + 1, 3, summary.length - 1, 1).setFontWeight('bold');

  sheet.getRange(rSign, 1, 3, 4).setFontSize(9);
  sheet.getRange(rSign, 1, 1, 4).setFontWeight('bold');
  sheet.getRange(rSign + 2, 1, 1, 4).setFontColor('#555555');

  sheet.setColumnWidth(1, 30);
  sheet.setColumnWidth(2, 95);
  sheet.setColumnWidth(3, 175);
  for (var c = 0; c < asm.length; c++) sheet.setColumnWidth(4 + c, 58);
  var t = 4 + asm.length;
  sheet.setColumnWidth(t, 55);
  sheet.setColumnWidth(t + 1, 55);
  sheet.setColumnWidth(t + 2, 50);
  sheet.setColumnWidth(t + 3, 95);
  sheet.setColumnWidth(t + 4, 45);

  // Repeat the header row on every printed page.
  sheet.setFrozenRows(rHead);
  if (sheet.getMaxColumns() > width) {
    sheet.deleteColumns(width + 1, sheet.getMaxColumns() - width);
  }
  if (sheet.getMaxRows() > lastRow + 2) {
    sheet.deleteRows(lastRow + 3, sheet.getMaxRows() - lastRow - 2);
  }
  SpreadsheetApp.flush();

  return {
    sheet: sheet,
    gid: sheet.getSheetId(),
    ss: d.ss,
    section: sec,
    lastRow: lastRow,
    lastCol: width,
    students: d.rows.length
  };
}

function fmtNum_(n) {
  if (n === null || n === undefined || n === '') return '';
  var v = Number(n);
  if (isNaN(v)) return String(n);
  return (Math.round(v * 100) / 100).toString();
}

/* -------------------------------- the PDF -------------------------------- */

/**
 * Build the record and hand back a PDF: saved into a "Class Records" folder
 * beside the semester, and returned inline so the browser can download it
 * directly without going through Drive.
 */
function exportClassRecordPdf_(session, data) {
  return withLock_(function () {
    var built = buildClassRecordSheet_(data.sectionId);
    var sec = built.section;

    var name = [sec.subjectCode, sec.name].filter(String).join(' — ') +
               ' · Class Record · ' + today_();
    name = name.replace(/[\\/:*?"<>|]/g, '-');

    var url = 'https://docs.google.com/spreadsheets/d/' + built.ss.getId() + '/export?' + [
      'format=pdf',
      'gid=' + built.gid,
      'portrait=false',
      'fitw=true',
      'size=A4',
      'scale=4',
      'gridlines=false',
      'printtitle=false',
      'sheetnames=false',
      'pagenumbers=true',
      'fzr=true',
      'horizontal_alignment=CENTER',
      'top_margin=0.40', 'bottom_margin=0.40',
      'left_margin=0.35', 'right_margin=0.35'
    ].join('&');

    var response;
    try {
      response = UrlFetchApp.fetch(url, {
        headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
        muteHttpExceptions: true
      });
    } catch (err) {
      throw new Error(
        'The PDF service could not be reached. Open the Apps Script editor, run ' +
        'showConfig() once and approve the new permission, then try again. (' + err + ')'
      );
    }

    if (response.getResponseCode() !== 200) {
      throw new Error(
        'Sheets refused to make the PDF (HTTP ' + response.getResponseCode() + '). ' +
        'This is almost always a missing authorisation — run showConfig() from the ' +
        'Apps Script editor, approve, then try again.'
      );
    }

    var blob = response.getBlob().setName(name + '.pdf');

    // File it in Drive as well, so old records stay available.
    var driveUrl = '', downloadUrl = '', folderUrl = '';
    try {
      var semester = findRow_(tab_(TAB.SEMESTERS), 'Semester ID', sec.semesterId);
      var parent = semester && str_(semester['Folder ID'])
        ? DriveApp.getFolderById(str_(semester['Folder ID']))
        : rootFolder_();
      var folder = folderByName_(parent, 'Class Records');
      var file = folder.createFile(blob);
      driveUrl = file.getUrl();
      downloadUrl = 'https://drive.google.com/uc?export=download&id=' + file.getId();
      folderUrl = folder.getUrl();
    } catch (err) {
      console.warn('Could not file the PDF in Drive: ' + err);
    }

    logAction_(session.id, session.role, 'EXPORT_PDF', data.sectionId,
      { file: name, students: built.students });

    return {
      name: name + '.pdf',
      students: built.students,
      base64: Utilities.base64Encode(blob.getBytes()),
      driveUrl: driveUrl,
      downloadUrl: downloadUrl,
      folderUrl: folderUrl,
      sheetUrl: built.ss.getUrl() + '#gid=' + built.gid
    };
  });
}
/** Run this once from the editor to approve the outbound-request permission. */
function authorizeExport() {
  var res = UrlFetchApp.fetch('https://www.googleapis.com/discovery/v1/apis?preferred=true', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
    muteHttpExceptions: true
  });
  var msg = 'Outbound requests authorised — HTTP ' + res.getResponseCode();
  console.log(msg);
  return msg;
}
