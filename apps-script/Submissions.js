/**
 * ============================================================================
 *  Submissions.js  ·  Assignments that students answer with a PDF
 * ============================================================================
 *  An assignment belongs to one class, has a due date/time, may point to a
 *  material (usually a problem set) and is tied to a gradebook column. Each
 *  student uploads one PDF (≤ 10 MB) and may replace it until the due time —
 *  after it only when late work is allowed (marked late). The admin opens the
 *  files in the portal, types scores (saved in the gradebook as usual) and can
 *  delete the files of graded submissions to free Drive space; the record of
 *  who submitted, when and the score are kept.
 *
 *  Built for a deadline rush: an upload creates the Drive file and APPENDS a
 *  row to "Submissions" without the script lock (appendRow is atomic), so
 *  students never wait for each other. The latest row per student is the
 *  current submission; the file of the one it replaced is trashed.
 *
 *  Files: semester folder → "Submissions" → "<subject> <section>" →
 *  "<assignment title>" (folder made when the assignment is created). They
 *  are not shared; the portal hands out the bytes.
 * ============================================================================
 */

var SUBMIT = {
  ASSIGN_TAB: 'Assignments',
  TAB: 'Submissions',
  FOLDER: 'Submissions',
  MAX_BYTES: 10 * 1024 * 1024
};
SCHEMA[SUBMIT.ASSIGN_TAB] = [
  'Assignment ID', 'Section ID', 'Title', 'Instructions', 'Material ID',
  'Assessment ID', 'Due At', 'Allow Late', 'Open', 'Folder ID',
  'Created At', 'Created By'
];
SCHEMA[SUBMIT.TAB] = [
  'Submission ID', 'Assignment ID', 'Section ID', 'Student ID', 'Drive File ID',
  'File Name', 'Size', 'Submitted At', 'Late', 'File Deleted At'
];

/* ------------------------------- helpers -------------------------------- */

/** 'yyyy-MM-dd HH:mm' from text (or a Date the sheet made of it). */
function dateTimeText_(v) {
  if (v instanceof Date) return Utilities.formatDate(v, APP.TIMEZONE, 'yyyy-MM-dd HH:mm');
  var s = str_(v).replace(/^'/, '').replace('T', ' ');
  var m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(\d{1,2}):(\d{2}))?/);
  if (!m) return '';
  return m[1] + '-' + ('0' + m[2]).slice(-2) + '-' + ('0' + m[3]).slice(-2) + ' ' +
    ('0' + (m[4] || '23')).slice(-2) + ':' + (m[5] || '59');
}

function dateTimeMs_(v) {
  var t = dateTimeText_(v);
  if (!t) return null;
  return Utilities.parseDate(t, APP.TIMEZONE, 'yyyy-MM-dd HH:mm').getTime();
}

function studentEnrolled_(sectionId, studentId) {
  var sid = str_(sectionId), me = str_(studentId).toLowerCase();
  return readTable_(tab_(TAB.ENROLLMENTS)).rows.some(function (r) {
    return str_(r['Section ID']) === sid && str_(r['Student ID']).toLowerCase() === me;
  });
}

function semesterFolder_(semesterId) {
  var sem = indexBy_(listSemesters_(), 'id')[semesterId] || {};
  return sem.folderId ? DriveApp.getFolderById(sem.folderId) : rootFolder_();
}

function assignmentOut_(r) {
  return {
    id: str_(r['Assignment ID']),
    sectionId: str_(r['Section ID']),
    title: str_(r['Title']),
    instructions: str_(r['Instructions']),
    materialId: str_(r['Material ID']),
    assessmentId: str_(r['Assessment ID']),
    dueAt: dateTimeText_(r['Due At']),
    allowLate: bool_(r['Allow Late']),
    open: str_(r['Open']) === '' ? true : bool_(r['Open']),
    folderId: str_(r['Folder ID'])
  };
}

function assignmentsOf_(sectionId) {
  var sid = str_(sectionId);
  return readTable_(tab_(SUBMIT.ASSIGN_TAB)).rows
    .filter(function (r) { return str_(r['Section ID']) === sid; })
    .map(assignmentOut_)
    .sort(function (a, b) { return a.dueAt < b.dueAt ? -1 : (a.dueAt > b.dueAt ? 1 : 0); });
}

function assignmentById_(sectionId, id) {
  var list = assignmentsOf_(sectionId).filter(function (a) { return a.id === str_(id); });
  if (!list.length) throw new Error('That problem set no longer exists.');
  return list[0];
}

/** Latest submission row per student (lower-cased ID) for one assignment. */
function latestSubmissions_(assignmentId) {
  var out = {};
  readTable_(tab_(SUBMIT.TAB)).rows.forEach(function (r) {
    if (str_(r['Assignment ID']) !== str_(assignmentId)) return;
    var k = str_(r['Student ID']).toLowerCase();
    var prev = out[k];
    if (!prev || str_(r['Submitted At']) >= str_(prev['Submitted At'])) out[k] = r;
  });
  return out;
}

function submissionOut_(r) {
  if (!r) return null;
  return {
    id: str_(r['Submission ID']),
    fileName: str_(r['File Name']),
    size: num_(r['Size']) || 0,
    submittedAt: str_(r['Submitted At']),
    late: bool_(r['Late']),
    fileDeleted: !!str_(r['File Deleted At'])
  };
}

/** Assessment in this class with that ID, or the one with the same name. */
function findAssessmentFor_(data, id, name) {
  var a = data.assessments.filter(function (x) { return x.id === str_(id); })[0];
  if (!a && name) {
    a = data.assessments.filter(function (x) { return x.name.toLowerCase() === str_(name).toLowerCase(); })[0];
  }
  return a || null;
}

/**
 * Gradebook column for one target class: `col` = { mode: 'new', name, type,
 * max, weight } or { mode: 'existing', assessmentId, name }.
 */
/** True when any student already has a score in that column. */
function columnHasScores_(data, assessmentId) {
  return data.roster.some(function (st) {
    var v = st.scores[assessmentId];
    return v !== null && v !== undefined && v !== '' && !isNaN(v);
  });
}

/** Columns a new assignment/exam may use: only ones nobody has a score in. */
function emptyColumns_(data) {
  return data.assessments.filter(function (x) { return !columnHasScores_(data, x.id); })
    .map(function (x) { return { id: x.id, name: x.name, max: x.max, type: x.type }; });
}

function columnFor_(session, sectionId, col) {
  if (col.mode === 'existing') {
    var d0 = readSectionData_(sectionId, true);
    var a = findAssessmentFor_(d0, col.assessmentId, col.name);
    if (a && columnHasScores_(d0, a.id)) {
      throw new Error('"' + a.name + '" already has scores in ' + (getSectionRecord_(sectionId).name || 'a class') +
        '. Graded requirements are left alone — choose "New column" instead.');
    }
    if (a) return a.id;
    // a sibling class without that column gets a new one with the same name
  }
  var data = readSectionData_(sectionId, true);
  var clash = findAssessmentFor_(data, '', col.name);
  if (clash && !columnHasScores_(data, clash.id)) return clash.id;
  if (clash) throw new Error('A requirement called "' + col.name + '" already has scores. Use a different title.');
  return createAssessment_(session, sectionId, {
    name: col.name, type: col.type, max: col.max, weight: col.weight || 0, published: true
  }).id;
}

/* -------------------------------- admin --------------------------------- */

function adminAssignments_(session, sectionId) {
  var sec = getSectionRecord_(sectionId);
  var data = readSectionData_(sectionId, true);
  var subs = readTable_(tab_(SUBMIT.TAB)).rows;
  var list = assignmentsOf_(sectionId).map(function (a) {
    var latest = {};
    subs.forEach(function (r) {
      if (str_(r['Assignment ID']) !== a.id) return;
      var k = str_(r['Student ID']).toLowerCase();
      if (!latest[k] || str_(r['Submitted At']) >= str_(latest[k]['Submitted At'])) latest[k] = r;
    });
    var col = findAssessmentFor_(data, a.assessmentId, '');
    var submitted = 0, late = 0, graded = 0, files = 0, bytes = 0;
    data.roster.forEach(function (s) {
      var r = latest[s.studentId.toLowerCase()];
      var score = col ? s.scores[col.id] : null;
      var hasScore = score !== null && score !== undefined && score !== '' && !isNaN(score);
      if (r) {
        submitted++;
        if (bool_(r['Late'])) late++;
        if (!str_(r['File Deleted At'])) { files++; bytes += num_(r['Size']) || 0; }
      }
      if (hasScore) graded++;
    });
    a.columnName = col ? col.name : '(column removed)';
    a.max = col ? col.max : null;
    a.counts = { students: data.roster.length, submitted: submitted, late: late, graded: graded, files: files, bytes: bytes };
    return a;
  });
  return {
    assignments: list,
    materials: materialsFor_(sectionId, false).map(function (m) { return { id: m.id, title: m.title, type: m.type }; }),
    columns: emptyColumns_(data),
    siblings: siblingSections_(sec),
    now: dateTimeText_(Utilities.formatDate(new Date(), APP.TIMEZONE, 'yyyy-MM-dd HH:mm'))
  };
}

/**
 * p: { sectionId, title, instructions, materialId, dueAt, allowLate, open,
 *      column: { mode, assessmentId, max, weight }, alsoSectionIds }
 */
function createAssignment_(session, p) {
  var sec = getSectionRecord_(p.sectionId);
  var title = str_(p.title).slice(0, 120);
  if (!title) throw new Error('Please give the problem set a title.');
  var due = dateTimeText_(p.dueAt);
  if (!due) throw new Error('Please set the due date and time.');
  var col = p.column || { mode: 'new' };
  var colName = col.mode === 'existing'
    ? (findAssessmentFor_(readSectionData_(sec.id, true), col.assessmentId, '') || {}).name
    : title;
  if (!colName) throw new Error('Choose the gradebook column.');

  var allowed = siblingSections_(sec).map(function (s) { return s.id; });
  var targets = [sec.id];
  (p.alsoSectionIds || []).forEach(function (id) {
    if (allowed.indexOf(id) > -1 && targets.indexOf(id) < 0) targets.push(id);
  });

  // the linked material, matched in each sibling by its Drive file
  var mat = null;
  if (str_(p.materialId)) {
    materialRows_().forEach(function (r) { if (str_(r['Material ID']) === str_(p.materialId)) mat = r; });
  }

  return withLock_(function () {
    var sh = tab_(SUBMIT.ASSIGN_TAB);
    var parent = folderByName_(semesterFolder_(sec.semesterId), SUBMIT.FOLDER);
    var secs = indexBy_(listSections_(sec.semesterId), 'id');
    targets.forEach(function (sid) {
      var s = secs[sid] || sec;
      var assessmentId = columnFor_(session, sid, {
        mode: col.mode, assessmentId: sid === sec.id ? col.assessmentId : '', name: colName,
        type: 'Problem Set', max: num_(col.max) || 100, weight: num_(col.weight) || 0
      });
      var materialId = '';
      if (mat) {
        materialRows_().forEach(function (r) {
          if (str_(r['Section ID']) === sid && str_(r['Drive File ID']) === str_(mat['Drive File ID'])) materialId = str_(r['Material ID']);
        });
      }
      var classFolder = folderByName_(parent, ((s.subjectCode ? s.subjectCode + ' ' : '') + s.name).trim());
      var folder = classFolder.createFolder(title);
      appendObject_(sh, {
        'Assignment ID': uid_('ASG'), 'Section ID': sid, 'Title': title,
        'Instructions': str_(p.instructions).slice(0, 2000), 'Material ID': materialId,
        'Assessment ID': assessmentId, 'Due At': "'" + due, 'Allow Late': p.allowLate ? 'TRUE' : 'FALSE',
        'Open': p.open === false ? 'FALSE' : 'TRUE', 'Folder ID': folder.getId(),
        'Created At': nowStamp_(), 'Created By': session.id
      });
    });
    logAction_(session.id, session.role, 'ASSIGNMENT_CREATE', sec.id, { title: title, sections: targets.join(',') });
    return adminAssignments_(session, sec.id);
  });
}

/** p: { sectionId, id, title?, instructions?, materialId?, dueAt?, allowLate?, open? } */
function updateAssignment_(session, p) {
  return withLock_(function () {
    var sh = tab_(SUBMIT.ASSIGN_TAB);
    var row = findRow_(sh, 'Assignment ID', p.id);
    if (!row || str_(row['Section ID']) !== str_(p.sectionId)) throw new Error('That problem set no longer exists.');
    var patch = {};
    if (p.title !== undefined && str_(p.title)) patch['Title'] = str_(p.title).slice(0, 120);
    if (p.instructions !== undefined) patch['Instructions'] = str_(p.instructions).slice(0, 2000);
    if (p.materialId !== undefined) patch['Material ID'] = str_(p.materialId);
    if (p.dueAt !== undefined) {
      var due = dateTimeText_(p.dueAt);
      if (!due) throw new Error('Please set the due date and time.');
      patch['Due At'] = "'" + due;
    }
    if (p.allowLate !== undefined) patch['Allow Late'] = p.allowLate ? 'TRUE' : 'FALSE';
    if (p.open !== undefined) patch['Open'] = p.open ? 'TRUE' : 'FALSE';
    updateObject_(sh, row._row, patch);
    logAction_(session.id, session.role, 'ASSIGNMENT_UPDATE', p.sectionId, { id: p.id, fields: Object.keys(patch).join(',') });
    return adminAssignments_(session, p.sectionId);
  });
}

/** Removes the assignment and its files; the gradebook column and scores stay. */
function removeAssignment_(session, sectionId, id) {
  return withLock_(function () {
    var a = assignmentById_(sectionId, id);
    var sub = tab_(SUBMIT.TAB);
    var rows = readTable_(sub).rows.filter(function (r) { return str_(r['Assignment ID']) === a.id; });
    rows.forEach(function (r) {
      if (str_(r['Drive File ID']) && !str_(r['File Deleted At'])) {
        try { DriveApp.getFileById(str_(r['Drive File ID'])).setTrashed(true); } catch (err) { /* already gone */ }
      }
    });
    rows.sort(function (x, y) { return y._row - x._row; }).forEach(function (r) { sub.deleteRow(r._row); });
    if (a.folderId) { try { DriveApp.getFolderById(a.folderId).setTrashed(true); } catch (err) { /* gone */ } }
    var sh = tab_(SUBMIT.ASSIGN_TAB);
    var row = findRow_(sh, 'Assignment ID', a.id);
    if (row) sh.deleteRow(row._row);
    logAction_(session.id, session.role, 'ASSIGNMENT_REMOVE', sectionId, { id: a.id, title: a.title, files: rows.length });
    return adminAssignments_(session, sectionId);
  });
}

/** Every student of the class with their current submission and score. */
function adminSubmissions_(session, sectionId, assignmentId) {
  var a = assignmentById_(sectionId, assignmentId);
  var data = readSectionData_(sectionId, true);
  var col = findAssessmentFor_(data, a.assessmentId, '');
  var latest = latestSubmissions_(a.id);
  var dueMs = dateTimeMs_(a.dueAt);
  return {
    assignment: a,
    max: col ? col.max : null,
    columnName: col ? col.name : null,
    students: data.roster.map(function (s) {
      var score = col ? s.scores[col.id] : null;
      return {
        studentId: s.studentId,
        name: [s.lastName, s.firstName].filter(function (x) { return x; }).join(', ') || s.studentId,
        submission: submissionOut_(latest[s.studentId.toLowerCase()]),
        score: score === null || score === undefined || score === '' || isNaN(score) ? null : Number(score)
      };
    }),
    pastDue: dueMs !== null && new Date().getTime() > dueMs
  };
}

/** p: { sectionId, assignmentId, changes: [{ studentId, value }] } */
function gradeSubmissions_(session, p) {
  var a = assignmentById_(p.sectionId, p.assignmentId);
  if (!a.assessmentId) throw new Error('This problem set has no gradebook column.');
  var res = saveScores_(session, p.sectionId, (p.changes || []).map(function (c) {
    return { studentId: c.studentId, assessmentId: a.assessmentId, value: c.value };
  }));
  var out = adminSubmissions_(session, p.sectionId, p.assignmentId);
  out.saved = res.saved;
  out.problems = res.problems || [];
  return out;
}

/**
 * Trash the files of submissions that already have a score. `dryRun` only
 * counts them (for the confirmation). Records and scores are kept.
 */
function deleteGradedFiles_(session, sectionId, assignmentId, dryRun) {
  var a = assignmentById_(sectionId, assignmentId);
  var data = readSectionData_(sectionId, true);
  var col = findAssessmentFor_(data, a.assessmentId, '');
  if (!col) throw new Error('This problem set has no gradebook column, so nothing counts as graded.');
  var latest = latestSubmissions_(a.id);
  var pick = [];
  data.roster.forEach(function (s) {
    var r = latest[s.studentId.toLowerCase()];
    var score = s.scores[col.id];
    var graded = score !== null && score !== undefined && score !== '' && !isNaN(score);
    if (r && graded && !str_(r['File Deleted At']) && str_(r['Drive File ID'])) pick.push(r);
  });
  var bytes = pick.reduce(function (n, r) { return n + (num_(r['Size']) || 0); }, 0);
  if (dryRun) return { count: pick.length, bytes: bytes };

  return withLock_(function () {
    var sh = tab_(SUBMIT.TAB);
    var stamp = nowStamp_();
    pick.forEach(function (r) {
      try { DriveApp.getFileById(str_(r['Drive File ID'])).setTrashed(true); } catch (err) { /* already gone */ }
      var row = findRow_(sh, 'Submission ID', r['Submission ID']);
      if (row) updateObject_(sh, row._row, { 'File Deleted At': stamp });
    });
    logAction_(session.id, session.role, 'SUBMISSIONS_DELETE_GRADED', sectionId, { id: a.id, files: pick.length, bytes: bytes });
    var out = adminSubmissions_(session, sectionId, assignmentId);
    out.deleted = { count: pick.length, bytes: bytes };
    return out;
  });
}

/** The PDF of a student's current submission (admin: anyone; student: own). */
function getSubmissionFile_(session, sectionId, assignmentId, studentId) {
  var who = session.role === 'student' ? session.id : studentId;
  if (session.role === 'student' && !studentEnrolled_(sectionId, who)) throw new Error('You are not enrolled in this class.');
  var a = assignmentById_(sectionId, assignmentId);
  var r = latestSubmissions_(a.id)[str_(who).toLowerCase()];
  if (!r) throw new Error('No submission yet.');
  if (str_(r['File Deleted At'])) throw new Error('This file was deleted after grading to save space.');
  var blob = DriveApp.getFileById(str_(r['Drive File ID'])).getBlob();
  return {
    kind: a.id, id: a.id, label: a.title, title: a.title, name: str_(r['File Name']),
    mimeType: 'application/pdf', data: Utilities.base64Encode(blob.getBytes())
  };
}

/* ------------------------------- students -------------------------------- */

/** The class's open assignments for the student class page. */
function studentAssignments_(sectionId, me, assessments) {
  var list = assignmentsOf_(sectionId).filter(function (a) { return a.open; });
  if (!list.length) return [];
  var subs = readTable_(tab_(SUBMIT.TAB)).rows;
  var myId = str_(me.studentId).toLowerCase();
  var now = new Date().getTime();
  return list.map(function (a) {
    var mine = null;
    subs.forEach(function (r) {
      if (str_(r['Assignment ID']) === a.id && str_(r['Student ID']).toLowerCase() === myId &&
          (!mine || str_(r['Submitted At']) >= str_(mine['Submitted At']))) mine = r;
    });
    var col = assessments.filter(function (x) { return x.id === a.assessmentId; })[0];
    var raw = col ? me.scores[col.id] : null;
    var scored = col && col.published && raw !== null && raw !== undefined && raw !== '' && !isNaN(raw);
    var dueMs = dateTimeMs_(a.dueAt);
    var pastDue = dueMs !== null && now > dueMs;
    return {
      id: a.id, title: a.title, instructions: a.instructions, materialId: a.materialId,
      dueAt: a.dueAt, allowLate: a.allowLate, pastDue: pastDue,
      canSubmit: !pastDue || a.allowLate,
      submission: submissionOut_(mine),
      score: scored ? Number(raw) : null,
      max: col ? col.max : null
    };
  });
}

/**
 * p: { sectionId, assignmentId, name, mimeType, data (base64) }
 * No script lock: Drive file + appendRow, so a deadline rush doesn't queue.
 */
function submitAssignment_(session, p) {
  var sid = str_(p.sectionId);
  if (!studentEnrolled_(sid, session.id)) throw new Error('You are not enrolled in this class.');
  var a = assignmentById_(sid, p.assignmentId);
  if (!a.open) throw new Error('This problem set is closed.');
  var now = new Date().getTime();
  var dueMs = dateTimeMs_(a.dueAt);
  var late = dueMs !== null && now > dueMs;
  if (late && !a.allowLate) throw new Error('The due time has passed. Late submissions are not accepted for this problem set.');

  var name = str_(p.name).replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'answer.pdf';
  if (!/\.pdf$/i.test(name)) throw new Error('Please upload a PDF. Scan your work to PDF first.');
  var bytes = Utilities.base64Decode(str_(p.data));
  if (!bytes.length) throw new Error('The file is empty.');
  if (bytes.length > SUBMIT.MAX_BYTES) throw new Error('The file is larger than 10 MB. Scan at a lower resolution and try again.');
  if (String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]) !== '%PDF') throw new Error('That file is not a valid PDF.');

  var prev = latestSubmissions_(a.id)[str_(session.id).toLowerCase()];
  var folder = a.folderId ? DriveApp.getFolderById(a.folderId) : rootFolder_();
  var file = folder.createFile(Utilities.newBlob(bytes, 'application/pdf', session.id + ' - ' + name));
  var stamp = nowStamp_();
  appendObject_(tab_(SUBMIT.TAB), {
    'Submission ID': uid_('SUB'), 'Assignment ID': a.id, 'Section ID': sid, 'Student ID': session.id,
    'Drive File ID': file.getId(), 'File Name': name, 'Size': bytes.length, 'Submitted At': stamp,
    'Late': late ? 'TRUE' : 'FALSE', 'File Deleted At': ''
  });
  if (prev && str_(prev['Drive File ID']) && !str_(prev['File Deleted At'])) {
    try { DriveApp.getFileById(str_(prev['Drive File ID'])).setTrashed(true); } catch (err) { /* gone */ }
  }
  return { submittedAt: stamp, late: late, fileName: name, size: bytes.length, replaced: !!prev };
}
