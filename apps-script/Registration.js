/**
 * =============================================================================
 *  Registration.js · Student self-registration and e-mail verification
 * =============================================================================
 *  New students fill in a form (student number, name, program, year, e-mail,
 *  password, classes). A 6-digit code goes to their e-mail; entering it
 *  creates the account (active at once) and enrols them in the classes they
 *  picked from the active semester.
 *
 *  Existing students must confirm an e-mail address once ("Email Verified" on
 *  the Students tab). Until then every student action answers MUST_EMAIL and
 *  the page asks for the address and a code.
 *
 *  Settings (blank = default):
 *    REGISTRATION_OPEN      TRUE  — FALSE hides "Create an account".
 *    REQUIRE_EMAIL_VERIFY   TRUE  — FALSE lets students in without a verified
 *                                   e-mail (emergency switch, e.g. mail quota).
 *  Codes live in CacheService only, signed with the token secret.
 */

var REG = {
  CODE_MINUTES: 30,
  MAX_TRIES: 5,
  MAX_SENDS_PER_HOUR: 5,       // per student number and per e-mail address
  MAX_SENDS_ALL_PER_HOUR: 150, // whole portal, so the form can't be used to spam
  ID_PATTERN: /^\d{2}-\d{4,6}$/,
  EMAIL_PATTERN: /^[^\s@,;]+@[^\s@,;]+\.[A-Za-z]{2,}$/,
  YEARS: ['1', '2', '3', '4', '5']
};

function settingOn_(key) {
  var v = str_(settings_()[key]).toUpperCase();
  return v !== 'FALSE' && v !== 'NO' && v !== '0';
}
function registrationOpen_() { return settingOn_('REGISTRATION_OPEN'); }
function emailRequired_() { return settingOn_('REQUIRE_EMAIL_VERIFY'); }

/** True when this student row still needs a verified e-mail. */
function needsEmail_(row) {
  return emailRequired_() && !str_(row['Email Verified']);
}

function cleanEmail_(v) {
  var e = str_(v).toLowerCase();
  if (!REG.EMAIL_PATTERN.test(e)) throw new Error('Please enter a valid e-mail address, e.g. juan.delacruz@gmail.com');
  return e;
}

function codeSig_(purpose, id, email, code) {
  return Utilities.base64EncodeWebSafe(Utilities.computeHmacSha256Signature(
    purpose + '|' + String(id).toLowerCase() + '|' + String(email).toLowerCase() + '|' + String(code),
    secret_(PROP.SECRET)
  ));
}

/** Counts a send against the hourly limits; throws when one is used up. */
function noteSend_(keys) {
  var cache = CacheService.getScriptCache();
  var hour = Utilities.formatDate(new Date(), 'UTC', 'yyyyMMddHH');
  var all = 'codesend:all:' + hour;
  if (Number(cache.get(all) || 0) >= REG.MAX_SENDS_ALL_PER_HOUR) {
    throw new Error('Too many codes were sent in the last hour. Please try again later.');
  }
  keys.forEach(function (k) {
    if (Number(cache.get('codesend:' + k) || 0) >= REG.MAX_SENDS_PER_HOUR) {
      throw new Error('Too many codes were requested. Please wait an hour, or check your spam folder for the last one.');
    }
  });
  keys.forEach(function (k) {
    cache.put('codesend:' + k, String(Number(cache.get('codesend:' + k) || 0) + 1), 3600);
  });
  cache.put(all, String(Number(cache.get(all) || 0) + 1), 3600);
}

function portalTitle_() {
  var s = settings_();
  return (s['PORTAL_TITLE'] || 'Electrical Engineering') + ' ' + (s['PORTAL_SUBTITLE'] || 'Student Grades Portal');
}

function sendCode_(to, name, intro, code) {
  if (MailApp.getRemainingDailyQuota() < 1) {
    throw new Error('The portal cannot send e-mail right now. Please try again tomorrow.');
  }
  var title = portalTitle_();
  var contact = str_(settings_()['CONTACT_EMAIL']);
  var msg = {
    to: to,
    subject: 'Your ' + title + ' verification code: ' + code,
    name: title,
    body: 'Hello ' + (name || '') + ',\n\n' + intro + '\n\n' +
      '    Verification code:  ' + code + '\n\n' +
      'Enter it on the portal within ' + REG.CODE_MINUTES + ' minutes.\n\n' +
      'If you did not ask for this, you can ignore this e-mail.'
  };
  if (contact) msg.replyTo = contact;
  MailApp.sendEmail(msg);
}

function maskEmail_(e) {
  var p = String(e).split('@');
  if (p.length !== 2) return e;
  var u = p[0];
  return (u.length <= 2 ? u.charAt(0) + '•' : u.slice(0, 2) + '•••' + u.slice(-1)) + '@' + p[1];
}

/* ------------------------------ registration ------------------------------ */

/** Classes a new student may pick: active sections of the active semester. */
function registrationClasses_() {
  var semId = str_(settings_()['ACTIVE_SEMESTER_ID']);
  if (!semId) {
    var active = listSemesters_().filter(function (s) { return s.active; })[0];
    semId = active ? active.id : '';
  }
  if (!semId) return [];
  return listSections_(semId).filter(function (s) {
    var st = str_(s.status).toLowerCase();
    return st !== 'inactive' && st !== 'archived' && st !== 'closed';
  }).map(function (s) {
    return {
      id: s.id,
      subject: s.subjectCode,
      title: s.subjectTitle,
      section: s.name,
      schedule: s.schedule,
      instructor: s.instructor
    };
  });
}

function registrationOptions_() {
  if (!registrationOpen_()) return { open: false };
  var programs = {};
  readTable_(tab_(TAB.STUDENTS)).rows.forEach(function (r) {
    var p = str_(r['Program']).toUpperCase();
    if (/^[A-Z]{2,10}$/.test(p)) programs[p] = true;
  });
  if (!Object.keys(programs).length) { programs.BSEE = true; programs.BSECE = true; }
  return {
    open: true,
    programs: Object.keys(programs).sort(),
    years: REG.YEARS,
    classes: registrationClasses_(),
    minPassword: APP.MIN_PASSWORD_LENGTH
  };
}

function regKey_(id) { return 'reg:' + String(id).toLowerCase(); }

/** Step 1: check the form, e-mail a code. Nothing is saved yet. */
function registrationStart_(p) {
  if (!registrationOpen_()) throw new Error('Registration is closed. Please contact your instructor.');
  var id = str_(p.studentId);
  if (!REG.ID_PATTERN.test(id)) throw new Error('Enter your student number as printed on your ID, e.g. 25-050366.');
  var last = str_(p.lastName).replace(/\s+/g, ' ');
  var first = str_(p.firstName).replace(/\s+/g, ' ');
  var middle = str_(p.middleName).replace(/\s+/g, ' ');
  if (!last || !first) throw new Error('Please enter your last name and first name.');
  if (last.length > 60 || first.length > 60 || middle.length > 60) throw new Error('Names must be 60 characters or fewer.');

  var opts = registrationOptions_();
  var program = str_(p.program).toUpperCase();
  if (opts.programs.indexOf(program) < 0) throw new Error('Please choose your program.');
  var year = str_(p.yearLevel);
  if (REG.YEARS.indexOf(year) < 0) throw new Error('Please choose your year level.');
  var email = cleanEmail_(p.email);

  var allowed = {};
  opts.classes.forEach(function (c) { allowed[c.id] = true; });
  var classes = (p.sectionIds || []).map(str_).filter(function (x, i, a) { return allowed[x] && a.indexOf(x) === i; });
  if (!classes.length) throw new Error('Please tick at least one class you are taking.');

  var pw = String(p.password || '');
  if (pw.length < APP.MIN_PASSWORD_LENGTH) throw new Error('Your password must be at least ' + APP.MIN_PASSWORD_LENGTH + ' characters.');
  if (/^\s|\s$/.test(pw)) throw new Error('Your password cannot start or end with a space.');
  if (isSharedPassword_(pw)) throw new Error('Please choose a password only you know.');

  if (findRow_(tab_(TAB.STUDENTS), 'Student ID', id)) {
    throw new Error('Student number ' + id + ' already has an account. Sign in, or use "Forgot password?". ' +
      'If this is not your account, contact your instructor.');
  }

  noteSend_(['reg:' + id.toLowerCase(), 'mail:' + email]);
  var salt = newSalt_();
  var code = resetCode_();
  CacheService.getScriptCache().put(regKey_(id), JSON.stringify({
    sig: codeSig_('reg', id, email, code), tries: 0, email: email,
    data: { id: id, last: last, first: first, middle: middle, program: program, year: year,
            hash: hashPassword_(pw, salt), salt: salt, classes: classes }
  }), REG.CODE_MINUTES * 60);
  sendCode_(email, first, 'Thank you for registering on the ' + portalTitle_() + ' with student number ' + id + '.', code);
  logAction_(id, 'student', 'REGISTER_CODE_SENT', id, maskEmail_(email));
  return { sentTo: maskEmail_(email), minutes: REG.CODE_MINUTES };
}

/** Step 2: the code from the e-mail creates the account and enrols the student. */
function registrationVerify_(p) {
  var id = str_(p.studentId);
  var code = str_(p.code).replace(/\s+/g, '');
  var cache = CacheService.getScriptCache();
  var raw = cache.get(regKey_(id));
  var wrong = 'That code is not correct or has expired. Check the latest e-mail, or start again.';
  if (!raw || !code) throw new Error(wrong);
  var st = JSON.parse(raw);
  if (st.tries >= REG.MAX_TRIES) { cache.remove(regKey_(id)); throw new Error('Too many wrong codes. Please fill in the form again.'); }
  if (!constantTimeEquals_(codeSig_('reg', id, st.email, code), st.sig)) {
    st.tries++;
    cache.put(regKey_(id), JSON.stringify(st), REG.CODE_MINUTES * 60);
    throw new Error(wrong);
  }
  var d = st.data;
  var enrolled = [], problems = [];
  withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    if (findRow_(sheet, 'Student ID', d.id)) throw new Error('Student number ' + d.id + ' already has an account. Sign in instead.');
    var stamp = nowStamp_();
    appendObject_(sheet, {
      'Student ID': d.id, 'Last Name': d.last, 'First Name': d.first, 'Middle Name': d.middle,
      'Program': d.program, 'Year Level': d.year, 'Email': st.email, 'Email Verified': stamp,
      'Status': 'Active', 'Set Password': '', 'Password Hash': d.hash, 'Salt': d.salt,
      'Applied Set': '', 'Must Change': 'FALSE', 'Password Updated': stamp,
      'Created At': stamp, 'Registered': stamp
    });
    SpreadsheetApp.flush();
    var sys = { id: d.id, role: 'admin' };   // enrolment is logged under the student's own number
    d.classes.forEach(function (sid) {
      try { enrollStudents_(sys, sid, [d.id]); enrolled.push(sid); }
      catch (err) { problems.push(sid + ': ' + err.message); console.warn('register enrol ' + sid + ': ' + err); }
    });
  });
  cache.remove(regKey_(id));
  logAction_(d.id, 'student', 'REGISTER', d.id, JSON.stringify({ classes: enrolled.join(','), problems: problems.length }));
  return { done: true, studentId: d.id, enrolled: enrolled.length, problems: problems };
}

/* ------------------------- e-mail for existing students ------------------- */

function evKey_(id) { return 'ev:' + String(id).toLowerCase(); }

function sendEmailCode_(session, p) {
  if (session.role !== 'student') throw new Error('Only student accounts confirm an e-mail here.');
  var email = cleanEmail_(p.email);
  noteSend_(['ev:' + String(session.id).toLowerCase(), 'mail:' + email]);
  var code = resetCode_();
  CacheService.getScriptCache().put(evKey_(session.id), JSON.stringify({
    sig: codeSig_('ev', session.id, email, code), tries: 0, email: email
  }), REG.CODE_MINUTES * 60);
  sendCode_(email, str_(session.row['First Name']),
    'Please confirm this e-mail address for student number ' + session.id + ' on the ' + portalTitle_() + '. ' +
    'It will be used to send you password-reset codes.', code);
  logAction_(session.id, 'student', 'EMAIL_CODE_SENT', session.id, maskEmail_(email));
  return { sentTo: maskEmail_(email), minutes: REG.CODE_MINUTES };
}

function verifyEmailCode_(session, p) {
  var code = str_(p.code).replace(/\s+/g, '');
  var cache = CacheService.getScriptCache();
  var raw = cache.get(evKey_(session.id));
  var wrong = 'That code is not correct or has expired. You can send a new one.';
  if (!raw || !code) throw new Error(wrong);
  var st = JSON.parse(raw);
  if (st.tries >= REG.MAX_TRIES) { cache.remove(evKey_(session.id)); throw new Error('Too many wrong codes. Please send a new one.'); }
  if (!constantTimeEquals_(codeSig_('ev', session.id, st.email, code), st.sig)) {
    st.tries++;
    cache.put(evKey_(session.id), JSON.stringify(st), REG.CODE_MINUTES * 60);
    throw new Error(wrong);
  }
  cache.remove(evKey_(session.id));
  return withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    var row = findRow_(sheet, 'Student ID', session.id);
    if (!row) throw new Error('AUTH:That student account no longer exists.');
    updateObject_(sheet, row._row, { 'Email': st.email, 'Email Verified': nowStamp_() });
    logAction_(session.id, 'student', 'EMAIL_VERIFIED', session.id, maskEmail_(st.email));
    row['Email'] = st.email;
    return { done: true, profile: profileOf_('student', row) };
  });
}
