/**
 * ============================================================================
 *  Exams.js  ·  Multiple-choice exams drawn from a question bank
 * ============================================================================
 *  Question bank: one Google Sheet per subject in the root Drive folder →
 *  "Question Banks" (tab "Questions": ID, Unit, CO, Level, Question, A–D,
 *  Answer, Image). The admin fills it in Sheets; blank IDs are filled in when
 *  the bank is checked so item analysis can follow each question.
 *
 *  An exam (per class) has a blueprint — how many questions to draw from each
 *  Unit × Level — a duration and an open/close window. On creation the
 *  eligible questions are frozen into a "pool" JSON file (root → "Exams"), so
 *  editing the bank never changes a running exam. Each student gets their own
 *  random draw, in random order, with shuffled choices. The answer key never
 *  leaves the server; time is checked on the server (2-minute grace).
 *
 *  Load: starting appends a row to "ExamAttempts" and submitting appends one
 *  to "ExamResults" — no script lock, so a whole class can start and finish
 *  together. Autosaves go to CacheService only. Scores reach the gradebook in
 *  one batch (`syncExamScores_`) when the admin releases them, opens the
 *  results, or on the hourly trigger — immediately-shown exams included.
 *  Attempts nobody submitted are scored from their last autosave once the
 *  time is up (`finalizeExpired_`).
 * ============================================================================
 */

var EXAMS = {
  BANK_TAB: 'QuestionBanks',
  TAB: 'Exams',
  ATTEMPTS: 'ExamAttempts',
  RESULTS: 'ExamResults',
  BANK_FOLDER: 'Question Banks',
  POOL_FOLDER: 'Exams',
  BANK_HEADERS: ['ID', 'Unit', 'CO', 'Level', 'Question', 'A', 'B', 'C', 'D', 'Answer', 'Image'],
  GRACE_MS: 2 * 60 * 1000,
  CACHE_S: 21600,
  LETTERS: 'ABCD'
};
SCHEMA[EXAMS.BANK_TAB] = ['Subject ID', 'Spreadsheet ID', 'Created At'];
SCHEMA[EXAMS.TAB] = [
  'Exam ID', 'Section ID', 'Title', 'Assessment ID', 'Bank ID', 'Blueprint', 'Items',
  'Duration Minutes', 'Opens At', 'Closes At', 'Show Score', 'Released',
  'Pool File ID', 'Created At', 'Created By'
];
SCHEMA[EXAMS.ATTEMPTS] = ['Attempt ID', 'Exam ID', 'Section ID', 'Student ID', 'Started At', 'Deadline', 'Questions'];
SCHEMA[EXAMS.RESULTS] = [
  'Attempt ID', 'Exam ID', 'Section ID', 'Student ID', 'Submitted At', 'Score', 'Items',
  'Answers', 'Away Count', 'How'
];

/* ---------------------------- question bank ----------------------------- */

function bankIdFor_(subjectId) {
  var row = readTable_(tab_(EXAMS.BANK_TAB)).rows.filter(function (r) {
    return str_(r['Subject ID']) === str_(subjectId);
  })[0];
  return row ? str_(row['Spreadsheet ID']) : '';
}

function createBank_(session, sectionId) {
  var sec = getSectionRecord_(sectionId);
  if (bankIdFor_(sec.subjectId)) return adminExams_(session, sectionId);
  return withLock_(function () {
    if (bankIdFor_(sec.subjectId)) return adminExams_(session, sectionId);
    var ss = SpreadsheetApp.create((sec.subjectCode || 'Subject') + ' — Question Bank');
    var q = ss.getSheets()[0].setName('Questions');
    q.getRange(1, 1, 1, EXAMS.BANK_HEADERS.length).setValues([EXAMS.BANK_HEADERS])
      .setFontWeight('bold').setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG);
    q.setFrozenRows(1);
    [110, 90, 70, 90, 420, 180, 180, 180, 180, 70, 160].forEach(function (w, i) { q.setColumnWidth(i + 1, w); });
    q.getRange('E2:I').setWrap(true);
    q.getRange('J2:J').setDataValidation(SpreadsheetApp.newDataValidation()
      .requireValueInList(['A', 'B', 'C', 'D'], true).setAllowInvalid(false).build());
    var help = ss.insertSheet('How to use');
    help.getRange(1, 1, 12, 1).setValues([
      ['How to fill in this question bank'],
      ['• One question per row on the "Questions" tab. Leave ID blank — the portal fills it in.'],
      ['• Unit: e.g. "Unit V". Level: e.g. "Easy", "Average", "Difficult" (any labels; the exam draws by Unit × Level).'],
      ['• CO: the course outcome, e.g. "CO2" (used in the item analysis).'],
      ['• Question: the question text. A–D: the choices (C and D may be blank for 2-choice items).'],
      ['• Answer: the letter of the correct choice (A, B, C or D).'],
      ['• Image (optional): a diagram uploaded to Google Drive — paste its link or file ID.'],
      ['• Edit or add questions any time. An exam already created keeps the questions it had.'],
      [''],
      ['Example row:'],
      ['(ID blank) | Unit V | CO2 | Easy | A balanced 3-phase load draws 10 A per line. What is the neutral current? | 0 A | 10 A | 17.3 A | 30 A | A |'],
      ['']
    ]);
    help.getRange(1, 1).setFontWeight('bold').setFontSize(13);
    help.setColumnWidth(1, 900);
    var folder = folderByName_(rootFolder_(), EXAMS.BANK_FOLDER);
    DriveApp.getFileById(ss.getId()).moveTo(folder);
    appendObject_(tab_(EXAMS.BANK_TAB), { 'Subject ID': sec.subjectId, 'Spreadsheet ID': ss.getId(), 'Created At': nowStamp_() });
    logAction_(session.id, session.role, 'BANK_CREATE', sectionId, { subject: sec.subjectCode, id: ss.getId() });
    return adminExams_(session, sectionId);
  });
}

/** Read (and with fixIds, give missing IDs to) every question in a bank. */
function readBank_(bankId, fixIds) {
  var ss = SpreadsheetApp.openById(bankId);
  var sh = ss.getSheetByName('Questions') || ss.getSheets()[0];
  var last = sh.getLastRow();
  var out = { url: ss.getUrl(), questions: [], problems: [] };
  if (last < 2) return out;
  var width = Math.max(sh.getLastColumn(), EXAMS.BANK_HEADERS.length);
  var values = sh.getRange(1, 1, last, width).getValues();
  var head = values[0].map(function (h) { return str_(h).toLowerCase(); });
  var col = {};
  EXAMS.BANK_HEADERS.forEach(function (h) { col[h] = head.indexOf(h.toLowerCase()); });
  var seen = {}, newIds = [];
  for (var i = 1; i < values.length; i++) {
    var r = values[i];
    var get = function (h) { return col[h] > -1 ? str_(r[col[h]]) : ''; };
    var text = get('Question');
    if (!text) continue;
    var id = get('ID');
    if (!id || seen[id]) {
      id = uid_('Q');
      if (fixIds && col.ID > -1) newIds.push({ row: i + 1, id: id });
    }
    seen[id] = true;
    var choices = ['A', 'B', 'C', 'D'].map(function (k) { return get(k); });
    var answer = get('Answer').toUpperCase().charAt(0);
    var where = 'Row ' + (i + 1);
    if (choices.filter(function (c) { return c; }).length < 2) { out.problems.push(where + ': needs at least two choices.'); continue; }
    if (EXAMS.LETTERS.indexOf(answer) < 0 || !answer || !choices[EXAMS.LETTERS.indexOf(answer)]) {
      out.problems.push(where + ': the Answer must be the letter of a filled-in choice.'); continue;
    }
    out.questions.push({
      id: id, unit: get('Unit') || '(no unit)', co: get('CO'), level: get('Level') || '(no level)',
      text: text, choices: choices, answer: answer, image: imageId_(get('Image'))
    });
  }
  newIds.forEach(function (n) { sh.getRange(n.row, col.ID + 1).setValue(n.id); });
  return out;
}

function imageId_(v) {
  var s = str_(v);
  if (!s) return '';
  var m = s.match(/\/d\/([A-Za-z0-9_-]{20,})/) || s.match(/[?&]id=([A-Za-z0-9_-]{20,})/) || s.match(/^([A-Za-z0-9_-]{20,})$/);
  return m ? m[1] : '';
}

function bankSummary_(questions) {
  var map = {}, order = [];
  questions.forEach(function (q) {
    var k = q.unit + '\u0000' + q.level;
    if (!map[k]) { map[k] = { unit: q.unit, level: q.level, count: 0 }; order.push(k); }
    map[k].count++;
  });
  return order.map(function (k) { return map[k]; });
}

/* -------------------------------- pools ---------------------------------- */

function savePool_(pool, name) {
  var folder = folderByName_(rootFolder_(), EXAMS.POOL_FOLDER);
  var file = folder.createFile(Utilities.newBlob(JSON.stringify(pool), 'application/json', name + '.json'));
  return file.getId();
}

function loadPool_(fileId) {
  var cache = CacheService.getScriptCache();
  var key = 'pool:' + fileId;
  var meta = cache.get(key);
  if (meta) {
    var n = Number(meta), keys = [];
    for (var i = 0; i < n; i++) keys.push(key + ':' + i);
    var parts = cache.getAll(keys), text = '';
    var ok = keys.every(function (k) { return parts[k] !== undefined && parts[k] !== null; });
    if (ok) {
      keys.forEach(function (k) { text += parts[k]; });
      try { return JSON.parse(text); } catch (err) { /* reload */ }
    }
  }
  var raw = DriveApp.getFileById(fileId).getBlob().getDataAsString();
  var size = 90000, chunks = {}, count = Math.ceil(raw.length / size);
  for (var j = 0; j < count; j++) chunks[key + ':' + j] = raw.substr(j * size, size);
  try {
    cache.putAll(chunks, EXAMS.CACHE_S);
    cache.put(key, String(count), EXAMS.CACHE_S);
  } catch (err) { console.warn('pool cache: ' + err); }
  return JSON.parse(raw);
}

/* -------------------------------- exams ---------------------------------- */

function examOut_(r) {
  var bp = [];
  try { bp = JSON.parse(str_(r['Blueprint']) || '[]'); } catch (err) { bp = []; }
  return {
    id: str_(r['Exam ID']),
    sectionId: str_(r['Section ID']),
    title: str_(r['Title']),
    assessmentId: str_(r['Assessment ID']),
    bankId: str_(r['Bank ID']),
    blueprint: bp,
    items: num_(r['Items']) || 0,
    duration: num_(r['Duration Minutes']) || 0,
    opensAt: dateTimeText_(r['Opens At']),
    closesAt: dateTimeText_(r['Closes At']),
    showScore: str_(r['Show Score']) === 'IMMEDIATE' ? 'IMMEDIATE' : 'RELEASED',
    released: bool_(r['Released']),
    poolFileId: str_(r['Pool File ID'])
  };
}

function examsOf_(sectionId) {
  var sid = str_(sectionId);
  return readTable_(tab_(EXAMS.TAB)).rows
    .filter(function (r) { return str_(r['Section ID']) === sid; })
    .map(examOut_)
    .sort(function (a, b) { return a.opensAt < b.opensAt ? -1 : (a.opensAt > b.opensAt ? 1 : 0); });
}

function examById_(sectionId, id) {
  var e = examsOf_(sectionId).filter(function (x) { return x.id === str_(id); })[0];
  if (!e) throw new Error('That exam no longer exists.');
  return e;
}

function attemptOut_(r) {
  var qs = [];
  try { qs = JSON.parse(str_(r['Questions']) || '[]'); } catch (err) { qs = []; }
  return {
    id: str_(r['Attempt ID']), examId: str_(r['Exam ID']), sectionId: str_(r['Section ID']),
    studentId: str_(r['Student ID']), startedAt: str_(r['Started At']),
    deadline: num_(r['Deadline']) || 0, questions: qs
  };
}

/** First attempt and first result per student (lower-cased) for an exam. */
function examRecords_(examId) {
  var attempts = {}, results = {};
  readTable_(tab_(EXAMS.ATTEMPTS)).rows.forEach(function (r) {
    if (str_(r['Exam ID']) !== str_(examId)) return;
    var k = str_(r['Student ID']).toLowerCase();
    if (!attempts[k]) attempts[k] = attemptOut_(r);
  });
  readTable_(tab_(EXAMS.RESULTS)).rows.forEach(function (r) {
    if (str_(r['Exam ID']) !== str_(examId)) return;
    var k = str_(r['Student ID']).toLowerCase();
    if (!results[k]) results[k] = r;
  });
  return { attempts: attempts, results: results };
}

function adminExams_(session, sectionId) {
  var sec = getSectionRecord_(sectionId);
  var bankId = bankIdFor_(sec.subjectId);
  var bank = null;
  if (bankId) {
    try {
      var b = readBank_(bankId, true);
      bank = { id: bankId, url: b.url, total: b.questions.length, summary: bankSummary_(b.questions), problems: b.problems.slice(0, 20) };
    } catch (err) {
      bank = { id: bankId, url: 'https://docs.google.com/spreadsheets/d/' + bankId + '/edit', total: 0, summary: [], problems: ['The question bank could not be opened: ' + err.message] };
    }
  }
  var data = readSectionData_(sectionId, true);
  var now = new Date().getTime();
  var exams = examsOf_(sectionId).map(function (e) {
    var rec = examRecords_(e.id);
    var started = Object.keys(rec.attempts).length, done = Object.keys(rec.results).length;
    var opens = dateTimeMs_(e.opensAt), closes = dateTimeMs_(e.closesAt);
    e.state = now < opens ? 'upcoming' : (now < closes ? 'open' : 'closed');
    e.counts = { students: data.roster.length, started: started, submitted: done };
    var col = findAssessmentFor_(data, e.assessmentId, '');
    e.columnName = col ? col.name : '(column removed)';
    delete e.poolFileId;
    return e;
  });
  return {
    subjectCode: sec.subjectCode,
    bank: bank,
    exams: exams,
    columns: emptyColumns_(data),
    siblings: siblingSections_(sec),
    now: Utilities.formatDate(new Date(), APP.TIMEZONE, 'yyyy-MM-dd HH:mm')
  };
}

/**
 * p: { sectionId, title, blueprint: [{unit, level, count}], duration, opensAt,
 *      closesAt, showScore, column: {mode, assessmentId, weight}, alsoSectionIds }
 */
function createExam_(session, p) {
  var sec = getSectionRecord_(p.sectionId);
  var title = str_(p.title).slice(0, 120);
  if (!title) throw new Error('Please give the exam a title.');
  var duration = Math.round(num_(p.duration) || 0);
  if (duration < 1 || duration > 600) throw new Error('Set a time limit between 1 and 600 minutes.');
  var opens = dateTimeText_(p.opensAt), closes = dateTimeText_(p.closesAt);
  if (!opens || !closes) throw new Error('Set when the exam opens and closes.');
  if (dateTimeMs_(closes) <= dateTimeMs_(opens)) throw new Error('The exam must close after it opens.');
  var bankId = bankIdFor_(sec.subjectId);
  if (!bankId) throw new Error('Create the question bank first.');

  var bank = readBank_(bankId, true);
  var entries = [], items = 0, pool = { title: title, entries: [], questions: {} };
  (p.blueprint || []).forEach(function (b) {
    var count = Math.round(num_(b.count) || 0);
    if (count <= 0) return;
    var qs = bank.questions.filter(function (q) { return q.unit === str_(b.unit) && q.level === str_(b.level); });
    if (qs.length < count) throw new Error(b.unit + ' · ' + b.level + ': only ' + qs.length + ' question(s) in the bank, ' + count + ' asked.');
    pool.entries.push({ unit: str_(b.unit), level: str_(b.level), count: count, ids: qs.map(function (q) { return q.id; }) });
    qs.forEach(function (q) {
      pool.questions[q.id] = { text: q.text, choices: q.choices, answer: q.answer, image: q.image, unit: q.unit, level: q.level, co: q.co };
    });
    entries.push({ unit: str_(b.unit), level: str_(b.level), count: count });
    items += count;
  });
  if (!items) throw new Error('Choose how many questions to draw from at least one unit and level.');

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

  var poolId = savePool_(pool, (sec.subjectCode ? sec.subjectCode + ' ' : '') + title + ' pool ' + nowStamp_());
  return withLock_(function () {
    var sh = tab_(EXAMS.TAB);
    targets.forEach(function (sid) {
      var assessmentId = columnFor_(session, sid, {
        mode: col.mode, assessmentId: sid === sec.id ? col.assessmentId : '', name: colName,
        type: 'Exam', max: items, weight: num_(col.weight) || 0
      });
      appendObject_(sh, {
        'Exam ID': uid_('EXM'), 'Section ID': sid, 'Title': title, 'Assessment ID': assessmentId,
        'Bank ID': bankId, 'Blueprint': JSON.stringify(entries), 'Items': items,
        'Duration Minutes': duration, 'Opens At': "'" + opens, 'Closes At': "'" + closes,
        'Show Score': p.showScore === 'IMMEDIATE' ? 'IMMEDIATE' : 'RELEASED', 'Released': 'FALSE',
        'Pool File ID': poolId, 'Created At': nowStamp_(), 'Created By': session.id
      });
    });
    logAction_(session.id, session.role, 'EXAM_CREATE', sec.id, { title: title, items: items, sections: targets.join(',') });
    return adminExams_(session, sec.id);
  });
}

/** p: { sectionId, id, title?, duration?, opensAt?, closesAt?, showScore? } */
function updateExam_(session, p) {
  return withLock_(function () {
    var sh = tab_(EXAMS.TAB);
    var row = findRow_(sh, 'Exam ID', p.id);
    if (!row || str_(row['Section ID']) !== str_(p.sectionId)) throw new Error('That exam no longer exists.');
    var patch = {};
    if (p.title !== undefined && str_(p.title)) patch['Title'] = str_(p.title).slice(0, 120);
    if (p.duration !== undefined) {
      var d = Math.round(num_(p.duration) || 0);
      if (d < 1 || d > 600) throw new Error('Set a time limit between 1 and 600 minutes.');
      patch['Duration Minutes'] = d;
    }
    var opens = p.opensAt !== undefined ? dateTimeText_(p.opensAt) : dateTimeText_(row['Opens At']);
    var closes = p.closesAt !== undefined ? dateTimeText_(p.closesAt) : dateTimeText_(row['Closes At']);
    if (!opens || !closes || dateTimeMs_(closes) <= dateTimeMs_(opens)) throw new Error('The exam must close after it opens.');
    if (p.opensAt !== undefined) patch['Opens At'] = "'" + opens;
    if (p.closesAt !== undefined) patch['Closes At'] = "'" + closes;
    if (p.showScore !== undefined) patch['Show Score'] = p.showScore === 'IMMEDIATE' ? 'IMMEDIATE' : 'RELEASED';
    updateObject_(sh, row._row, patch);
    logAction_(session.id, session.role, 'EXAM_UPDATE', p.sectionId, { id: p.id, fields: Object.keys(patch).join(',') });
    return adminExams_(session, p.sectionId);
  });
}

/** Removes the exam with its attempts and results; the gradebook column stays. */
function removeExam_(session, sectionId, id) {
  return withLock_(function () {
    var e = examById_(sectionId, id);
    [EXAMS.ATTEMPTS, EXAMS.RESULTS].forEach(function (t) {
      var sh = tab_(t);
      readTable_(sh).rows.filter(function (r) { return str_(r['Exam ID']) === e.id; })
        .sort(function (a, b) { return b._row - a._row; })
        .forEach(function (r) { sh.deleteRow(r._row); });
    });
    var sh = tab_(EXAMS.TAB);
    var row = findRow_(sh, 'Exam ID', e.id);
    if (row) sh.deleteRow(row._row);
    var stillUsed = readTable_(sh).rows.some(function (r) { return str_(r['Pool File ID']) === e.poolFileId; });
    if (!stillUsed && e.poolFileId) { try { DriveApp.getFileById(e.poolFileId).setTrashed(true); } catch (err) { /* gone */ } }
    logAction_(session.id, session.role, 'EXAM_REMOVE', sectionId, { id: e.id, title: e.title });
    return adminExams_(session, sectionId);
  });
}

/* ------------------------------ scoring ---------------------------------- */

function cachedAnswers_(attemptId) {
  var raw = CacheService.getScriptCache().get('ans:' + attemptId);
  if (!raw) return { a: [], away: 0 };
  try { return JSON.parse(raw); } catch (err) { return { a: [], away: 0 }; }
}

/** answers[i] = index of the choice as shown to the student (or null). */
function scoreAttempt_(att, pool, answers) {
  var score = 0, detail = [];
  att.questions.forEach(function (q, i) {
    var item = pool.questions[q[0]];
    var shown = answers && answers[i] !== undefined && answers[i] !== null ? Number(answers[i]) : -1;
    var orig = shown > -1 && shown < q[1].length ? q[1][shown] : -1;
    var letter = orig > -1 ? EXAMS.LETTERS.charAt(orig) : '';
    var ok = item && letter && letter === item.answer ? 1 : 0;
    score += ok;
    detail.push([q[0], letter, ok]);
  });
  return { score: score, detail: detail };
}

function appendResult_(att, pool, answers, away, how) {
  var s = scoreAttempt_(att, pool, answers);
  var stamp = nowStamp_();
  appendObject_(tab_(EXAMS.RESULTS), {
    'Attempt ID': att.id, 'Exam ID': att.examId, 'Section ID': att.sectionId, 'Student ID': att.studentId,
    'Submitted At': stamp, 'Score': s.score, 'Items': att.questions.length,
    'Answers': JSON.stringify(s.detail), 'Away Count': num_(away) || 0, 'How': how
  });
  return { score: s.score, items: att.questions.length, submittedAt: stamp };
}

/** Score attempts whose time is up and that were never submitted. */
function finalizeExpired_(exam) {
  var rec = examRecords_(exam.id), now = new Date().getTime(), pool = null, n = 0;
  Object.keys(rec.attempts).forEach(function (k) {
    var att = rec.attempts[k];
    if (rec.results[k] || now <= att.deadline + EXAMS.GRACE_MS) return;
    pool = pool || loadPool_(exam.poolFileId);
    var c = cachedAnswers_(att.id);
    appendResult_(att, pool, c.a, c.away, 'AUTO');
    n++;
  });
  return n;
}

/** Write results to the gradebook (released or immediately-shown exams only). */
function syncExamScores_(session, exam) {
  if (!exam.released && exam.showScore !== 'IMMEDIATE') return 0;
  var data = readSectionData_(exam.sectionId, true);
  var col = findAssessmentFor_(data, exam.assessmentId, '');
  if (!col) return 0;
  var rec = examRecords_(exam.id);
  var changes = [];
  data.roster.forEach(function (s) {
    var r = rec.results[s.studentId.toLowerCase()];
    if (!r) return;
    var items = num_(r['Items']) || exam.items || 1;
    var value = round_((num_(r['Score']) || 0) / items * (col.max || items), 2);
    var cur = s.scores[col.id];
    if (cur === null || cur === undefined || cur === '' || Number(cur) !== value) {
      changes.push({ studentId: s.studentId, assessmentId: col.id, value: value });
    }
  });
  if (!changes.length) return 0;
  return saveScores_(session, exam.sectionId, changes).saved;
}

/** Hourly: finish expired attempts and push scores, within a time budget. */
function finalizeAndSyncExams_(budgetMs) {
  var started = new Date().getTime(), sys = { id: 'system', role: 'admin' };
  var now = started, week = 7 * 24 * 3600 * 1000;
  readTable_(tab_(EXAMS.TAB)).rows.map(examOut_).forEach(function (e) {
    if (new Date().getTime() - started > budgetMs) return;
    var closes = dateTimeMs_(e.closesAt);
    if (closes === null || now < dateTimeMs_(e.opensAt) || now - closes > week) return;
    try {
      finalizeExpired_(e);
      syncExamScores_(sys, e);
    } catch (err) { console.warn('exam ' + e.id + ': ' + err); }
  });
}

/* ------------------------------ admin views ------------------------------ */

function adminExamResults_(session, sectionId, examId) {
  var exam = examById_(sectionId, examId);
  finalizeExpired_(exam);
  var synced = syncExamScores_(session, exam);
  var data = readSectionData_(sectionId, true);
  var rec = examRecords_(exam.id);
  var pool = null;
  try { pool = loadPool_(exam.poolFileId); } catch (err) { pool = { questions: {} }; }
  var now = new Date().getTime();
  var scores = [], items = {};
  var students = data.roster.map(function (s) {
    var k = s.studentId.toLowerCase(), att = rec.attempts[k], r = rec.results[k];
    var row = {
      studentId: s.studentId,
      name: [s.lastName, s.firstName].filter(function (x) { return x; }).join(', ') || s.studentId,
      status: r ? (str_(r['How']) === 'AUTO' ? 'auto' : 'submitted') : (att ? (now > att.deadline + EXAMS.GRACE_MS ? 'expired' : 'in_progress') : 'not_started'),
      startedAt: att ? att.startedAt : '',
      submittedAt: r ? str_(r['Submitted At']) : '',
      score: r ? num_(r['Score']) : null,
      items: r ? num_(r['Items']) : (att ? att.questions.length : exam.items),
      away: r ? (num_(r['Away Count']) || 0) : 0,
      deadline: att ? Utilities.formatDate(new Date(att.deadline), APP.TIMEZONE, 'HH:mm') : ''
    };
    if (r) {
      scores.push(row.score / (row.items || 1) * 100);
      var detail = [];
      try { detail = JSON.parse(str_(r['Answers']) || '[]'); } catch (err) { detail = []; }
      detail.forEach(function (d) {
        var it = items[d[0]] = items[d[0]] || { shown: 0, correct: 0, picks: {} };
        it.shown++;
        if (d[2]) it.correct++;
        var key = d[1] || '—';
        it.picks[key] = (it.picks[key] || 0) + 1;
      });
    }
    return row;
  });
  var analysis = Object.keys(items).map(function (id) {
    var q = pool.questions[id] || {};
    var it = items[id];
    return {
      id: id, unit: q.unit || '', level: q.level || '', co: q.co || '', answer: q.answer || '',
      text: str_(q.text).slice(0, 160), shown: it.shown, correct: it.correct,
      percent: round_(it.correct / it.shown * 100, 1), picks: it.picks
    };
  }).sort(function (a, b) { return a.percent - b.percent; });
  var groups = {};
  analysis.forEach(function (a) {
    var k = a.unit + ' · ' + a.level;
    var g = groups[k] = groups[k] || { unit: a.unit, level: a.level, shown: 0, correct: 0 };
    g.shown += a.shown; g.correct += a.correct;
  });
  var mean = scores.length ? scores.reduce(function (x, y) { return x + y; }, 0) / scores.length : null;
  return {
    exam: (function () { var e = JSON.parse(JSON.stringify(exam)); delete e.poolFileId; return e; })(),
    students: students,
    stats: {
      submitted: scores.length, mean: mean === null ? null : round_(mean, 1),
      high: scores.length ? round_(Math.max.apply(null, scores), 1) : null,
      low: scores.length ? round_(Math.min.apply(null, scores), 1) : null
    },
    analysis: analysis,
    groups: Object.keys(groups).map(function (k) {
      var g = groups[k];
      return { unit: g.unit, level: g.level, percent: round_(g.correct / g.shown * 100, 1), shown: g.shown };
    }),
    synced: synced
  };
}

function releaseExam_(session, sectionId, examId) {
  withLock_(function () {
    var sh = tab_(EXAMS.TAB);
    var row = findRow_(sh, 'Exam ID', examId);
    if (!row || str_(row['Section ID']) !== str_(sectionId)) throw new Error('That exam no longer exists.');
    updateObject_(sh, row._row, { 'Released': 'TRUE' });
  });
  logAction_(session.id, session.role, 'EXAM_RELEASE', sectionId, { id: examId });
  return adminExamResults_(session, sectionId, examId);
}

/** Lets one student take the exam again (removes their attempt and score). */
function resetExamAttempt_(session, sectionId, examId, studentId) {
  var exam = examById_(sectionId, examId);
  withLock_(function () {
    var me = str_(studentId).toLowerCase();
    [EXAMS.ATTEMPTS, EXAMS.RESULTS].forEach(function (t) {
      var sh = tab_(t);
      readTable_(sh).rows.filter(function (r) {
        return str_(r['Exam ID']) === exam.id && str_(r['Student ID']).toLowerCase() === me;
      }).sort(function (a, b) { return b._row - a._row; }).forEach(function (r) {
        CacheService.getScriptCache().remove('ans:' + str_(r['Attempt ID']));
        sh.deleteRow(r._row);
      });
    });
  });
  if (exam.assessmentId) {
    saveScores_(session, sectionId, [{ studentId: studentId, assessmentId: exam.assessmentId, value: '' }]);
  }
  logAction_(session.id, session.role, 'EXAM_RESET', sectionId, { id: exam.id, student: studentId });
  return adminExamResults_(session, sectionId, examId);
}

/* ------------------------------- students -------------------------------- */

function studentExams_(sectionId, studentId, assessments, scores) {
  var list = examsOf_(sectionId);
  if (!list.length) return [];
  var me = str_(studentId).toLowerCase(), now = new Date().getTime();
  var attempts = readTable_(tab_(EXAMS.ATTEMPTS)).rows, results = readTable_(tab_(EXAMS.RESULTS)).rows;
  return list.map(function (e) {
    var att = attempts.filter(function (r) { return str_(r['Exam ID']) === e.id && str_(r['Student ID']).toLowerCase() === me; })[0];
    var res = results.filter(function (r) { return str_(r['Exam ID']) === e.id && str_(r['Student ID']).toLowerCase() === me; })[0];
    var opens = dateTimeMs_(e.opensAt), closes = dateTimeMs_(e.closesAt);
    var state;
    if (res) state = 'submitted';
    else if (att && now <= num_(att['Deadline']) + EXAMS.GRACE_MS) state = 'in_progress';
    else if (att) state = 'submitted';
    else if (now < opens) state = 'upcoming';
    else if (now < closes) state = 'open';
    else state = 'missed';
    var show = res && (e.released || e.showScore === 'IMMEDIATE');
    return {
      id: e.id, title: e.title, items: e.items, duration: e.duration,
      opensAt: e.opensAt, closesAt: e.closesAt, state: state,
      score: show ? num_(res['Score']) : null,
      scoreItems: res ? num_(res['Items']) : null
    };
  });
}

function myAttempt_(session, attemptId) {
  var row = findRow_(tab_(EXAMS.ATTEMPTS), 'Attempt ID', attemptId);
  if (!row || str_(row['Student ID']).toLowerCase() !== str_(session.id).toLowerCase()) {
    throw new Error('That exam attempt was not found. Reload the page.');
  }
  return attemptOut_(row);
}

function hasResult_(attemptId) {
  return readTable_(tab_(EXAMS.RESULTS)).rows.filter(function (r) {
    return str_(r['Attempt ID']) === str_(attemptId);
  })[0] || null;
}

function attemptPayload_(exam, att, pool) {
  var saved = cachedAnswers_(att.id);
  return {
    attemptId: att.id, title: exam.title, items: att.questions.length,
    deadline: att.deadline, serverNow: new Date().getTime(),
    questions: att.questions.map(function (q, i) {
      var item = pool.questions[q[0]] || { text: '(question missing)', choices: [] };
      return { n: i, text: item.text, choices: q[1].map(function (o) { return item.choices[o]; }), image: !!item.image };
    }),
    answers: saved.a || [], away: saved.away || 0
  };
}

function shuffle_(arr) {
  for (var i = arr.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
  }
  return arr;
}

/** Start (or resume) the exam. No script lock: one appended row. */
function startExam_(session, p) {
  var sid = str_(p.sectionId);
  if (!studentEnrolled_(sid, session.id)) throw new Error('You are not enrolled in this class.');
  var exam = examById_(sid, p.examId);
  var now = new Date().getTime();
  var me = str_(session.id).toLowerCase();
  var rec = examRecords_(exam.id);
  if (rec.results[me]) return { done: true, message: 'You have already submitted this exam.' };
  var att = rec.attempts[me];
  if (att) {
    if (now > att.deadline + EXAMS.GRACE_MS) {
      var pool0 = loadPool_(exam.poolFileId), c = cachedAnswers_(att.id);
      appendResult_(att, pool0, c.a, c.away, 'AUTO');
      return { done: true, message: 'Your time for this exam is over. Your saved answers were submitted.' };
    }
    return attemptPayload_(exam, att, loadPool_(exam.poolFileId));
  }
  var opens = dateTimeMs_(exam.opensAt), closes = dateTimeMs_(exam.closesAt);
  if (now < opens) throw new Error('This exam opens on ' + exam.opensAt + '.');
  if (now >= closes) throw new Error('This exam closed on ' + exam.closesAt + '.');

  var pool = loadPool_(exam.poolFileId);
  var picked = [];
  pool.entries.forEach(function (en) {
    shuffle_(en.ids.slice()).slice(0, en.count).forEach(function (id) { picked.push(id); });
  });
  var questions = shuffle_(picked).map(function (id) {
    var q = pool.questions[id], order = [];
    q.choices.forEach(function (c, i) { if (c) order.push(i); });
    return [id, shuffle_(order)];
  });
  var deadline = Math.min(now + exam.duration * 60000, closes);
  appendObject_(tab_(EXAMS.ATTEMPTS), {
    'Attempt ID': uid_('ATT'), 'Exam ID': exam.id, 'Section ID': sid, 'Student ID': session.id,
    'Started At': nowStamp_(), 'Deadline': deadline, 'Questions': JSON.stringify(questions)
  });
  // if two tabs started at the same moment, everyone uses the first attempt
  att = examRecords_(exam.id).attempts[me];
  logAction_(session.id, session.role, 'EXAM_START', sid, { exam: exam.id });
  return attemptPayload_(exam, att, pool);
}

/** Autosave: cache only. */
function saveExamAnswers_(session, p) {
  var att = myAttempt_(session, p.attemptId);
  if (hasResult_(att.id)) return { done: true };
  var now = new Date().getTime();
  if (now > att.deadline + EXAMS.GRACE_MS) return { late: true, serverNow: now };
  var answers = (p.answers || []).slice(0, att.questions.length);
  CacheService.getScriptCache().put('ans:' + att.id, JSON.stringify({ a: answers, away: num_(p.away) || 0 }), EXAMS.CACHE_S);
  return { saved: true, serverNow: now };
}

function submitExam_(session, p) {
  var att = myAttempt_(session, p.attemptId);
  var exam = examById_(att.sectionId, att.examId);
  var prev = hasResult_(att.id);
  var show = exam.released || exam.showScore === 'IMMEDIATE';
  if (prev) {
    return { done: true, submittedAt: str_(prev['Submitted At']), items: num_(prev['Items']), score: show ? num_(prev['Score']) : null, showScore: show };
  }
  var now = new Date().getTime();
  var inTime = now <= att.deadline + EXAMS.GRACE_MS;
  var c = cachedAnswers_(att.id);
  var answers = inTime && p.answers ? p.answers : c.a;
  var away = Math.max(num_(p.away) || 0, c.away || 0);
  var r = appendResult_(att, loadPool_(exam.poolFileId), answers, away, inTime ? 'SUBMITTED' : 'LATE');
  CacheService.getScriptCache().remove('ans:' + att.id);
  logAction_(session.id, session.role, 'EXAM_SUBMIT', att.sectionId, { exam: exam.id, inTime: inTime });
  return { done: true, submittedAt: r.submittedAt, items: r.items, score: show ? r.score : null, showScore: show, late: !inTime };
}

/** A question's diagram, for the student taking the exam (or the admin). */
function examImage_(session, p) {
  var att = myAttempt_(session, p.attemptId);
  var exam = examById_(att.sectionId, att.examId);
  var q = att.questions[Number(p.n)];
  if (!q) throw new Error('No such question.');
  var item = loadPool_(exam.poolFileId).questions[q[0]];
  if (!item || !item.image) throw new Error('This question has no image.');
  var cache = CacheService.getScriptCache(), key = 'img:' + item.image;
  var hit = cache.get(key);
  if (hit) return JSON.parse(hit);
  var blob = DriveApp.getFileById(item.image).getBlob();
  var out = { mimeType: blob.getContentType(), data: Utilities.base64Encode(blob.getBytes()) };
  if (out.data.length < 95000) { try { cache.put(key, JSON.stringify(out), EXAMS.CACHE_S); } catch (err) { /* too big */ } }
  return out;
}
