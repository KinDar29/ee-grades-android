/**
 * ============================================================================
 *  SummaryOfGrades.js  ·  VPAA-TEMP-004 "Summary of Grades" (admin only)
 * ============================================================================
 *  Fills the university's own Word template — kept in the portal's Drive
 *  folder under Templates/ — with ONE summary per semester covering every
 *  subject taught that semester, one row per subject + course + year. Only the contents change: the semester line,
 *  the data rows and the three signature names. Header, logo, borders, fonts,
 *  column widths and footer are copied byte for byte from the template.
 *
 *  Counts per row:
 *    Total   every student in the class(es)
 *    Passed  grade 1.00–3.00
 *    "4"     grade 4.00 (Conditional)
 *    Failed  any other failing grade (5.00)
 *    Dropped / INC   set per student by the admin (Final Status tab)
 *  Students with no recorded scores and no status are noted in REMARKS.
 * ============================================================================
 */

var SUMMARY = {
  TEMPLATE_FOLDER: 'Templates',
  TEMPLATE_PREFIX: 'Summary of Grades template',
  OUTPUT_FOLDER: 'Summary of Grades',
  DOCX_MIME: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
};

/* --------------------------------- data ---------------------------------- */

/**
 * Rows for every class in one semester. Sections of the same faculty,
 * subject, course and year are combined into one row, because the form has
 * no section column.
 */
function summaryOfGradesData_(semesterId) {

  var registry = {};
  readTable_(tab_(TAB.STUDENTS)).rows.forEach(function (r) {
    registry[str_(r['Student ID']).toLowerCase()] = {
      program: str_(r['Program']),
      year: str_(r['Year Level'])
    };
  });

  var sections = listSections_(semesterId).filter(function (s) {
    return str_(s.status).toLowerCase() !== 'archived';
  });
  if (!sections.length) throw new Error('There are no classes in this semester yet.');
  sections.sort(function (a, b) {
    var ka = (str_(a.instructor) + '|' + a.subjectCode + '|' + a.name).toLowerCase();
    var kb = (str_(b.instructor) + '|' + b.subjectCode + '|' + b.name).toLowerCase();
    return ka > kb ? 1 : (ka < kb ? -1 : 0);
  });
  var facultyNames = [];

  var groups = {};
  var order = [];
  sections.forEach(function (sec) {
    var d = classRecordData_(sec.id);
    var course = mostCommon_(d.rows.map(function (x) {
      return (registry[x.student.studentId.toLowerCase()] || {}).program;
    })) || (sec.name.match(/^[A-Za-z]+/) || [''])[0].toUpperCase();
    var year = mostCommon_(d.rows.map(function (x) {
      var y = (registry[x.student.studentId.toLowerCase()] || {}).year;
      return (String(y || '').match(/\d/) || [''])[0];
    })) || (sec.name.match(/\d/) || [''])[0];

    var faculty = str_(sec.instructor).replace(/\s+/g, ' ');
    if (faculty && !facultyNames.some(function (f) { return f.toLowerCase() === faculty.toLowerCase(); })) {
      facultyNames.push(faculty);
    }
    var subject = [sec.subjectCode, sec.subjectTitle].filter(String).join(' - ');
    var k = [faculty, subject, course, year].join('|').toLowerCase();
    if (!groups[k]) {
      groups[k] = { faculty: faculty, subject: subject, course: course, year: year, sections: [],
        total: 0, passed: 0, dropped: 0, inc: 0, four: 0, failed: 0, none: 0 };
      order.push(k);
    }
    var g = groups[k];
    g.sections.push(sec.name);
    d.rows.forEach(function (x) {
      g.total++;
      var st = standingOf_(x.grade, x.student.finalStatus);
      if (st === 'PASSED') g.passed++;
      else if (st === 'DROPPED') g.dropped++;
      else if (st === 'INC') g.inc++;
      else if (st === 'CONDITIONAL') g.four++;
      else if (st === 'FAILED') g.failed++;
      else g.none++;
    });
  });

  var semester = findRow_(tab_(TAB.SEMESTERS), 'Semester ID', semesterId);
  return {
    semesterLine: semesterLine_(semester),
    semesterName: semester ? str_(semester['Name']) : '',
    // One faculty member signs; if several taught this semester, all are named.
    facultyName: facultyNames.join(' / '),
    rows: order.map(function (k) {
      var g = groups[k];
      return {
        faculty: g.faculty,
        subject: g.subject, course: g.course, year: g.year, sections: g.sections,
        total: g.total, passed: g.passed, dropped: g.dropped, inc: g.inc,
        four: g.four, failed: g.failed,
        remarks: g.none ? g.none + ' without recorded scores' : ''
      };
    })
  };
}

/** "FIRST SEMESTER, AY 2026-2027" from the Semesters tab. */
function semesterLine_(row) {
  if (!row) return '';
  var term = str_(row['Term']);
  var sy = str_(row['School Year']);
  var name = str_(row['Name']);
  if (!sy) sy = (name.match(/\d{4}\s*-\s*\d{4}/) || [''])[0].replace(/\s+/g, '');
  var t = (term || name).toLowerCase();
  var label;
  if (/(^|\b)(1st|first)\b/.test(t)) label = 'FIRST SEMESTER';
  else if (/(^|\b)(2nd|second)\b/.test(t)) label = 'SECOND SEMESTER';
  else if (/mid|summer/.test(t)) label = 'MIDYEAR TERM';
  else label = (term || name).toUpperCase();
  return sy ? label + ', AY ' + sy : label;
}

function mostCommon_(values) {
  var counts = {}, best = '', bestN = 0;
  values.forEach(function (v) {
    v = str_(v);
    if (!v) return;
    counts[v] = (counts[v] || 0) + 1;
    if (counts[v] > bestN) { best = v; bestN = counts[v]; }
  });
  return best;
}

/* -------------------------------- template ------------------------------- */

function summaryTemplateFile_() {
  var id = str_(settings_()['SUMMARY_TEMPLATE_ID']);
  if (id) return DriveApp.getFileById(id);
  var folders = rootFolder_().getFoldersByName(SUMMARY.TEMPLATE_FOLDER);
  while (folders.hasNext()) {
    var files = folders.next().getFiles();
    while (files.hasNext()) {
      var f = files.next();
      if (f.getName().indexOf(SUMMARY.TEMPLATE_PREFIX) === 0 && !f.isTrashed()) return f;
    }
  }
  throw new Error('The Summary of Grades template is missing. Put the .docx in the "' +
    SUMMARY.TEMPLATE_FOLDER + '" folder with a name starting "' + SUMMARY.TEMPLATE_PREFIX + '".');
}

/* ------------------------------ fill the XML ----------------------------- */

function xmlText_(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Puts `text` in the cell's first run and empties any other text in it. */
function setCellText_(cell, text) {
  var t = xmlText_(text);
  cell = cell.replace(/(<w:t(?: [^>]*)?>)[^<]*(<\/w:t>)/g, '$1$2');
  var m = cell.match(/<w:r(?: [^>]*)?>[\s\S]*?<\/w:r>/);
  if (!m) {
    return cell.replace(/<\/w:p>/, function () {
      return '<w:r><w:t xml:space="preserve">' + t + '</w:t></w:r></w:p>';
    });
  }
  var run = m[0];
  var filled = /<w:t(?: [^>]*)?>/.test(run)
    ? run.replace(/(<w:t(?: [^>]*)?>)(<\/w:t>)/, function (_, a, b) { return a + t + b; })
    : run.replace(/<\/w:r>$/, function () { return '<w:t xml:space="preserve">' + t + '</w:t></w:r>'; });
  return cell.replace(run, function () { return filled; });
}

function fillRow_(rowXml, values) {
  var i = 0;
  var out = rowXml.replace(/<w:tc>[\s\S]*?<\/w:tc>/g, function (cell) {
    return setCellText_(cell, i < values.length ? values[i++] : '');
  });
  // Copies of one row would repeat these optional IDs; Word regenerates them.
  return out.replace(/ w14:(paraId|textId)="[^"]*"/g, '');
}

/**
 * Pure string work, no Google services, so it can be tested anywhere.
 * d = { semesterLine, facultyName, chairName, deanName,
 *       rows: [{ faculty, subject, course, year, total, passed, dropped, inc, four, failed, remarks }] }
 * The faculty name is written on the first row of each faculty's block.
 */
function fillSummaryXml_(xml, d) {
  if (!d.rows || !d.rows.length) throw new Error('There is nothing to put in the Summary of Grades.');

  // 1. Semester line.
  var heading = /(<w:t(?: [^>]*)?>)([^<]*SEMESTER[^<]*|[^<]*TERM, AY[^<]*)(<\/w:t>)/g;
  var hits = xml.match(heading) || [];
  if (hits.length !== 1) throw new Error('Template check failed: expected one semester line, found ' + hits.length + '.');
  if (d.semesterLine) {
    xml = xml.replace(heading, function (_, a, b, c) { return a + xmlText_(d.semesterLine) + c; });
  }

  // 2. Data rows: row 3 of the table is the "first row" (with the faculty
  //    name), row 4 the pattern for every row after it.
  var rows = xml.match(/<w:tr(?: [^>]*)?>[\s\S]*?<\/w:tr>/g) || [];
  if (rows.length !== 4) throw new Error('Template check failed: expected 4 table rows, found ' + rows.length + '.');
  var first = rows[2], next = rows[3];
  var at = xml.indexOf(first + next);
  if (at < 0) throw new Error('Template check failed: the two sample rows are not next to each other.');
  var built = d.rows.map(function (r, i) {
    var who = r.faculty !== undefined ? r.faculty : d.facultyName;
    var prev = i > 0 ? (d.rows[i - 1].faculty !== undefined ? d.rows[i - 1].faculty : d.facultyName) : null;
    return fillRow_(i === 0 ? first : next, [
      i === 0 || who !== prev ? who : '',
      r.subject, r.course, r.year, r.total, r.passed, r.dropped, r.inc, r.four, r.failed, r.remarks
    ]);
  }).join('');
  xml = xml.slice(0, at) + built + xml.slice(at + first.length + next.length);

  // 3. Signature names: the line just above "Name and Signature of Faculty".
  var paras = xml.match(/<w:p(?: [^>]*)?>[\s\S]*?<\/w:p>/g) || [];
  var labelAt = -1;
  for (var i = 0; i < paras.length; i++) {
    if (paras[i].indexOf('Name and Signature of Faculty') > -1) { labelAt = i; break; }
  }
  var sig = null;
  for (var j = labelAt - 1; j >= 0 && labelAt > -1; j--) {
    var texts = (paras[j].match(/<w:t(?: [^>]*)?>[^<]+<\/w:t>/g) || []);
    if (texts.length >= 3) { sig = paras[j]; break; }
    if (texts.length) break;
  }
  if (!sig) throw new Error('Template check failed: could not find the signature names.');
  var names = [String(d.facultyName || '').toUpperCase(), d.chairName, d.deanName];
  var n = 0;
  var newSig = sig.replace(/(<w:t(?: [^>]*)?>)([^<]+)(<\/w:t>)/g, function (all, a, b, c) {
    var v = names[n++];
    return v ? a + xmlText_(v) + c : all;   // blank setting = keep the template's name
  });
  xml = xml.replace(sig, function () { return newSig; });
  return xml;
}

/* ------------------------------ build the file --------------------------- */

/**
 * Builds the one .docx for the semester of `sectionId`, covering every
 * subject taught that semester, files it in Drive and returns it.
 */
function buildSummaryOfGrades_(sectionId) {
  var record = getSectionRecord_(sectionId);
  var d = summaryOfGradesData_(record.semesterId);
  var s = settings_();
  d.chairName = str_(s['SUMMARY_CHAIR_NAME']);
  d.deanName = str_(s['SUMMARY_DEAN_NAME']);

  var template = summaryTemplateFile_();
  var parts = Utilities.unzip(template.getBlob().setContentType('application/zip'));
  var found = false;
  parts = parts.map(function (p) {
    if (p.getName() !== 'word/document.xml') return p;
    found = true;
    var xml = fillSummaryXml_(p.getDataAsString('UTF-8'), d);
    return Utilities.newBlob(xml, 'application/xml', p.getName());
  });
  if (!found) throw new Error('The Summary of Grades template is not a Word document.');

  var name = ('Summary of Grades - ' + (d.semesterName || today_()))
    .replace(/[\\/:*?"<>|]/g, '-') + '.docx';
  var blob = Utilities.zip(parts, name).setContentType(SUMMARY.DOCX_MIME).setName(name);

  var driveUrl = '';
  try {
    var semester = findRow_(tab_(TAB.SEMESTERS), 'Semester ID', record.semesterId);
    var parent = semester && str_(semester['Folder ID'])
      ? DriveApp.getFolderById(str_(semester['Folder ID']))
      : rootFolder_();
    var folder = folderByName_(parent, SUMMARY.OUTPUT_FOLDER);
    var old = folder.getFilesByName(name);
    while (old.hasNext()) old.next().setTrashed(true);   // keep one current copy
    driveUrl = folder.createFile(blob).getUrl();
  } catch (err) {
    console.warn('Could not file the Summary of Grades in Drive: ' + err);
  }

  return {
    name: name,
    base64: Utilities.base64Encode(blob.getBytes()),
    driveUrl: driveUrl,
    faculty: d.facultyName,
    semester: d.semesterLine,
    rows: d.rows
  };
}
