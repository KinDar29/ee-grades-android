/**
 * ============================================================================
 *  Messages.js  ·  "✉ Message students": one click → e-mail + notification
 * ============================================================================
 *  The admin writes one message (subject + body with placeholders such as
 *  {first}, {score}, {standing}), picks who gets it — everyone in the class,
 *  everyone in all sections of the subject, or only the students that match a
 *  score / standing filter, with a checkbox per student — and sends it.
 *
 *  Each student gets their own copy:
 *    · an e-mail (MailApp, from the portal owner's account, one recipient per
 *      e-mail so nobody sees who else got it), and/or
 *    · a notification in the portal (student home, "Messages" card) and in
 *      the Android app (student.updates → phone notification).
 *
 *  Master tab "Messages": one row per student per message. Rows of one send
 *  share a Batch ID. "Read At" is stamped when the student opens it.
 *  The server re-reads the class data on send: the recipients the browser
 *  ticks are only a list of student numbers; scores, names and e-mail
 *  addresses always come from the sheets.
 * ============================================================================
 */

var MESSAGES = {
  TAB: 'Messages',
  MAX_SUBJECT: 200,
  MAX_BODY: 8000,
  STUDENT_LIMIT: 60          // newest messages a student sees
};

SCHEMA[MESSAGES.TAB] = [
  'Message ID', 'Batch ID', 'Section ID', 'Student ID', 'Subject', 'Body',
  'Sent At', 'Sent By', 'Email To', 'Email Status', 'Notify', 'Read At'
];

/* ------------------------------ placeholders ------------------------------ */

var MESSAGE_FIELDS = [
  ['first', 'First name'], ['last', 'Last name'], ['name', 'Full name'],
  ['id', 'Student number'], ['section', 'Section'], ['subject', 'Subject code and title'],
  ['score', 'Score in the chosen column'], ['max', 'Highest possible score of that column'],
  ['percent', 'Score as a percentage'], ['grade', 'Final grade'], ['standing', 'Standing (PASSED, …)']
];

/** Replace {field} with the student's value. Unknown {words} are left as they are. */
function renderMessage_(text, ctx) {
  return String(text || '').replace(/\{(\w+)\}/g, function (m, key) {
    var k = key.toLowerCase();
    return ctx.hasOwnProperty(k) && ctx[k] !== null && ctx[k] !== undefined ? String(ctx[k]) : m;
  });
}

/* -------------------------------- recipients ------------------------------ */

/**
 * Everyone in the class (or in every section of the same subject), with the
 * values the placeholders and the filters need.
 */
function messageRecipients_(sectionId, allSections) {
  var sec = getSectionRecord_(sectionId);
  var list = [sec];
  if (allSections) {
    siblingSections_(sec).forEach(function (s) { list.push(getSectionRecord_(s.id)); });
  }

  var emails = {};
  readTable_(tab_(TAB.STUDENTS)).rows.forEach(function (r) {
    emails[str_(r['Student ID']).toLowerCase()] = str_(r['Email']);
  });

  var columns = [], seenCol = {}, students = [];
  list.forEach(function (s) {
    var d = classRecordData_(s.id);
    var fin = typeof finalGradesFromWorkbook_ === 'function' ? finalGradesFromWorkbook_(s) : {};
    var finText = workbookGradeText_(s);
    d.assessments.forEach(function (a) {
      if (seenCol[a.name]) return;
      seenCol[a.name] = true;
      columns.push({ name: a.name, max: a.max });
    });
    d.rows.forEach(function (x) {
      var st = x.student, idl = st.studentId.toLowerCase();
      var standing = standingOf_(x.grade, st.finalStatus);
      if (fin[idl] && standing !== 'DROPPED' && standing !== 'INC') standing = fin[idl];
      var scores = {};
      d.assessments.forEach(function (a) {
        var v = st.scores[a.id];
        scores[a.name] = v === null || v === undefined || isNaN(v) ? null : Number(v);
      });
      students.push({
        sectionId: s.id, section: s.name,
        subject: [s.subjectCode, s.subjectTitle].filter(String).join(' – '),
        id: st.studentId, first: st.firstName, last: st.lastName,
        email: emails[idl] || '',
        scores: scores,
        grade: finText[idl] || (x.grade.grade === null || x.grade.grade === undefined ? '' : Number(x.grade.grade).toFixed(2)),
        standing: standing === 'NONE' ? '' : standing
      });
    });
  });
  return { sections: list.map(function (s) { return { id: s.id, name: s.name }; }), columns: columns, students: students };
}

/** Final grade as written in the assessment workbook (2.75, INC, DRP), by student number. */
function workbookGradeText_(sec) {
  var out = {};
  if (!sec.workbookId) return out;
  try {
    var c = courseWorkbook_(sec.workbookId, false);
    var key = sectionKey_(sec.name);
    (c.direct.students || []).forEach(function (s) {
      if (!s.id || !s.final || sectionKey_(s.section) !== key) return;
      var g = s.final.grade;
      out[str_(s.id).toLowerCase()] = g !== null && g !== undefined && !isNaN(g) ? Number(g).toFixed(2) : str_(s.final.gradeText);
    });
  } catch (err) { /* no workbook: the portal's lecture grade is used */ }
  return out;
}

function messageContext_(st, column, columnMax) {
  var score = column ? st.scores[column] : null;
  var has = score !== null && score !== undefined;
  return {
    first: st.first, last: st.last, name: [st.first, st.last].filter(String).join(' '),
    id: st.id, section: st.section, subject: st.subject,
    score: has ? fmtNum_(score) : '—',
    max: column && columnMax ? fmtNum_(columnMax) : '',
    percent: has && columnMax ? fmtNum_(Math.round(score / columnMax * 10000) / 100) + '%' : '—',
    grade: st.grade || '—', standing: st.standing || '—'
  };
}

function fmtNum_(n) { return String(Math.round(Number(n) * 100) / 100); }

/* ---------------------------------- admin --------------------------------- */

function adminMessageRecipients_(session, p) {
  var r = messageRecipients_(p.sectionId, p.allSections === true || p.allSections === 'true');
  r.fields = MESSAGE_FIELDS.map(function (f) { return { key: f[0], label: f[1] }; });
  r.quota = MailApp.getRemainingDailyQuota();
  r.history = messageHistory_(r.sections.map(function (s) { return s.id; }));
  return r;
}

function adminMessageSend_(session, p) {
  var subject = str_(p.subject), body = String(p.body || '').replace(/\r\n/g, '\n').trim();
  var wantEmail = p.email === true || p.email === 'true';
  var wantNotify = p.notify === true || p.notify === 'true';
  if (!subject) throw new Error('Write a subject.');
  if (!body) throw new Error('Write the message.');
  if (subject.length > MESSAGES.MAX_SUBJECT) throw new Error('The subject is too long (' + MESSAGES.MAX_SUBJECT + ' characters at most).');
  if (body.length > MESSAGES.MAX_BODY) throw new Error('The message is too long (' + MESSAGES.MAX_BODY + ' characters at most).');
  if (!wantEmail && !wantNotify) throw new Error('Choose e-mail, notification or both.');

  var picked = {};
  (p.recipients || []).forEach(function (r) { picked[str_(r.sectionId) + '|' + str_(r.studentId).toLowerCase()] = true; });
  var all = messageRecipients_(p.sectionId, p.allSections === true || p.allSections === 'true');
  var targets = all.students.filter(function (st) { return picked[st.sectionId + '|' + st.id.toLowerCase()]; });
  if (!targets.length) throw new Error('No student is ticked.');

  var column = str_(p.column), colMax = null;
  all.columns.forEach(function (c) { if (c.name === column) colMax = c.max; });
  if (column && colMax === null) column = '';

  var withEmail = targets.filter(function (st) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(st.email); });
  if (wantEmail) {
    var left = MailApp.getRemainingDailyQuota();
    if (left < withEmail.length) {
      throw new Error('Google allows only ' + left + ' more e-mail' + (left === 1 ? '' : 's') +
        ' today and this message needs ' + withEmail.length + '. Send it tomorrow, or send only the notification.');
    }
  }

  var s = settings_();
  var contact = str_(s['CONTACT_EMAIL']);
  var fromName = str_(getSectionRecord_(p.sectionId).instructor) ||
    ((s['PORTAL_TITLE'] || 'Electrical Engineering') + ' ' + (s['PORTAL_SUBTITLE'] || 'Student Grades Portal'));
  var batch = uid_('MSGB'), now = nowStamp_();
  var out = { batchId: batch, total: targets.length, emailed: 0, notified: 0, noEmail: [], failed: [] };
  var rows = [];

  targets.forEach(function (st) {
    var ctx = messageContext_(st, column, colMax);
    var subj = renderMessage_(subject, ctx), text = renderMessage_(body, ctx);
    var status = '', to = '';
    if (wantEmail) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(st.email)) {
        status = 'NO EMAIL';
        out.noEmail.push(st.last + ', ' + st.first + ' (' + st.section + ')');
      } else {
        to = st.email;
        var msg = { to: to, subject: subj, body: text, name: fromName };
        if (contact) msg.replyTo = contact;
        try { MailApp.sendEmail(msg); status = 'SENT'; out.emailed++; }
        catch (err) { status = 'FAILED: ' + err.message; out.failed.push(st.last + ', ' + st.first + ': ' + err.message); }
      }
    }
    if (wantNotify) out.notified++;
    rows.push({
      'Message ID': uid_('MSG'), 'Batch ID': batch, 'Section ID': st.sectionId, 'Student ID': st.id,
      'Subject': subj, 'Body': text, 'Sent At': now, 'Sent By': session.id,
      'Email To': to, 'Email Status': status, 'Notify': wantNotify ? 'TRUE' : 'FALSE', 'Read At': ''
    });
  });

  withLock_(function () { appendObjects_(tab_(MESSAGES.TAB), rows); });
  logAction_(session.id, session.role, 'MESSAGE_SEND', p.sectionId, {
    batch: batch, subject: subject, recipients: targets.length, emailed: out.emailed,
    notified: out.notified, noEmail: out.noEmail.length, failed: out.failed.length
  });
  out.quota = MailApp.getRemainingDailyQuota();
  return out;
}

/** Many rows in one write, matched by header name; student numbers stay text. */
function appendObjects_(sheet, objs) {
  if (!objs.length) return;
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0].map(function (h) { return str_(h); });
  var values = objs.map(function (o) {
    return headers.map(function (h) {
      var v = o.hasOwnProperty(h) && o[h] !== undefined ? o[h] : '';
      return TEXT_COLUMNS[h] ? asText_(v) : v;
    });
  });
  var start = sheet.getLastRow() + 1;
  ensureRows_(sheet, start + values.length - 1);
  sheet.getRange(start, 1, values.length, headers.length).setNumberFormat('@').setValues(values);
}

/** Past sends for these classes, newest first: one line per batch. */
function messageHistory_(sectionIds) {
  var want = {};
  sectionIds.forEach(function (id) { want[id] = true; });
  var batches = {}, order = [];
  readTable_(tab_(MESSAGES.TAB)).rows.forEach(function (r) {
    if (!want[str_(r['Section ID'])]) return;
    var b = str_(r['Batch ID']);
    if (!batches[b]) {
      batches[b] = { batchId: b, subject: str_(r['Subject']), sentAt: str_(r['Sent At']), sentBy: str_(r['Sent By']),
        total: 0, emailed: 0, notify: bool_(r['Notify']), read: 0 };
      order.push(b);
    }
    var x = batches[b];
    x.total++;
    if (str_(r['Email Status']) === 'SENT') x.emailed++;
    if (str_(r['Read At'])) x.read++;
  });
  return order.reverse().slice(0, 30).map(function (b) { return batches[b]; });
}

/* --------------------------------- students ------------------------------- */

/** The student's notifications, newest first. */
function messagesForStudent_(studentId) {
  var me = str_(studentId).toLowerCase(), out = [];
  var sh = master_().getSheetByName(MESSAGES.TAB);
  if (!sh) return out;
  readTable_(sh).rows.forEach(function (r) {
    if (str_(r['Student ID']).toLowerCase() !== me || !bool_(r['Notify'])) return;
    out.push({ id: str_(r['Message ID']), sectionId: str_(r['Section ID']), subject: str_(r['Subject']),
      body: str_(r['Body']), sentAt: str_(r['Sent At']), read: !!str_(r['Read At']) });
  });
  return out.reverse().slice(0, MESSAGES.STUDENT_LIMIT);
}

function studentMessages_(session) {
  if (session.role !== 'student') throw new Error('AUTH:Please sign in as a student.');
  return { messages: messagesForStudent_(session.id) };
}

/** Stamp "Read At" on the student's own messages. */
function studentMessageRead_(session, p) {
  if (session.role !== 'student') throw new Error('AUTH:Please sign in as a student.');
  var ids = {};
  (p.ids || []).forEach(function (id) { ids[str_(id)] = true; });
  var me = str_(session.id).toLowerCase(), marked = 0;
  withLock_(function () {
    var sh = tab_(MESSAGES.TAB);
    var t = readTable_(sh), col = t.headers.indexOf('Read At') + 1, now = nowStamp_();
    if (col < 1) return;
    t.rows.forEach(function (r) {
      if (!ids[str_(r['Message ID'])] || str_(r['Student ID']).toLowerCase() !== me || str_(r['Read At'])) return;
      sh.getRange(r._row, col).setValue(now);
      marked++;
    });
  });
  return { marked: marked };
}
