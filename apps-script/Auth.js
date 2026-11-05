/**
 * ============================================================================
 *  Auth.gs  ·  Passwords, sessions and login
 * ============================================================================
 *  Passwords are never stored in readable form. The admin types a password
 *  into the "Set Password" column; the script salts, peppers and stretches it
 *  into the "Password Hash" column. The typed value stays visible so the admin
 *  can tell the student what it is, and is cleared automatically the moment
 *  the student sets a password of their own.
 * ============================================================================
 */

/* ------------------------------- hashing --------------------------------- */

function newSalt_() {
  return Utilities.base64EncodeWebSafe(Utilities.getUuid()).replace(/=+$/, '');
}

/** A random starting password unique to one account (8 characters, no look-alike letters). */
function randomPassword_() {
  var alphabet = 'abcdefghjkmnpqrstuvwxyz23456789';
  var bytes = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256, Utilities.getUuid() + Utilities.getUuid()
  );
  var out = '';
  for (var i = 0; i < 8; i++) out += alphabet.charAt(((bytes[i] + 256) % 256) % alphabet.length);
  return out;
}

function hashPassword_(plain, salt) {
  var pepper = secret_(PROP.PEPPER);
  var acc = String(plain) + '|' + String(salt) + '|' + pepper;
  for (var i = 0; i < APP.HASH_ITERATIONS; i++) {
    acc = bytesToHex_(
      Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, acc, Utilities.Charset.UTF_8)
    );
  }
  return acc;
}

/** Short fingerprint of the "Set Password" cell, so we only apply it once. */
function fingerprint_(text) {
  if (!str_(text)) return '';
  return bytesToHex_(
    Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, String(text) + secret_(PROP.PEPPER))
  ).substring(0, 12);
}

function constantTimeEquals_(a, b) {
  a = String(a || '');
  b = String(b || '');
  if (a.length !== b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/* ------------------------------- sessions -------------------------------- */

function makeToken_(subject, role, stamp) {
  var payload = {
    s: String(subject),
    r: String(role),
    v: String(stamp || '').slice(-10),
    e: new Date().getTime() + APP.SESSION_HOURS * 3600 * 1000
  };
  var body = Utilities.base64EncodeWebSafe(JSON.stringify(payload));
  var sig = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(body, secret_(PROP.SECRET))
  );
  return body + '.' + sig;
}

function parseToken_(token) {
  if (!token || String(token).indexOf('.') < 0) return null;
  var parts = String(token).split('.');
  var body = parts[0];
  var sig = parts[1];
  var expected = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(body, secret_(PROP.SECRET))
  );
  if (!constantTimeEquals_(sig, expected)) return null;
  var json;
  try {
    json = JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(body)).getDataAsString());
  } catch (err) {
    return null;
  }
  if (!json || !json.e || json.e < new Date().getTime()) return null;
  return json;
}

/**
 * Turn a token into a live session object, or throw.
 * Also re-checks that the account still exists and is active.
 */
function requireSession_(token, requiredRole, allowPendingChange) {
  var claims = parseToken_(token);
  if (!claims) throw new Error('AUTH:Your session has expired. Please sign in again.');

  var account;
  if (claims.r === 'student') {
    account = findRow_(tab_(TAB.STUDENTS), 'Student ID', claims.s);
    if (!account) throw new Error('AUTH:That student account no longer exists.');
    if (str_(account['Status']).toLowerCase() === 'inactive') {
      throw new Error('AUTH:That student account has been deactivated.');
    }
  } else {
    account = findRow_(tab_(TAB.ADMINS), 'Email', claims.s);
    if (!account) throw new Error('AUTH:That admin account no longer exists.');
    if (str_(account['Status']).toLowerCase() === 'inactive') {
      throw new Error('AUTH:That admin account has been deactivated.');
    }
  }

  if (str_(account['Password Hash']).slice(-10) !== str_(claims.v)) {
    throw new Error('AUTH:Your password was changed. Please sign in again.');
  }

  // Accounts still on a password someone else set must change it before
  // doing anything else (skipped for students when password changes are off).
  if (!allowPendingChange && bool_(account['Must Change']) &&
      (claims.r === 'admin' || bool_(settings_()['ALLOW_PASSWORD_CHANGE']))) {
    throw new Error('MUST_CHANGE:Please choose a new password before continuing.');
  }

  if (requiredRole === 'admin' && claims.r !== 'admin') {
    throw new Error('AUTH:This action needs an administrator account.');
  }

  return { id: claims.s, role: claims.r, row: account };
}

/* --------------------------- throttling ---------------------------------- */

function throttleKey_(identifier) {
  return 'fail:' + String(identifier).toLowerCase();
}

function checkThrottle_(identifier) {
  var cache = CacheService.getScriptCache();
  var n = Number(cache.get(throttleKey_(identifier)) || 0);
  if (n >= 8) {
    throw new Error('Too many failed attempts. Please wait 10 minutes and try again.');
  }
}

function noteFailure_(identifier) {
  var cache = CacheService.getScriptCache();
  var key = throttleKey_(identifier);
  var n = Number(cache.get(key) || 0) + 1;
  cache.put(key, String(n), 600);
}

function clearThrottle_(identifier) {
  CacheService.getScriptCache().remove(throttleKey_(identifier));
}

/* --------------------------- password provisioning ----------------------- */

/**
 * Make sure a person row has a usable password hash.
 * Applies a newly typed "Set Password" value, or falls back to the
 * portal-wide default password for a brand new account.
 * Returns the (possibly refreshed) row object.
 */
function ensurePassword_(sheet, row) {
  var typed = str_(row['Set Password']);
  var fp = fingerprint_(typed);
  var hasHash = !!str_(row['Password Hash']);

  // A newly typed / changed "Set Password" value wins.
  if (typed && fp !== str_(row['Applied Set'])) {
    var salt = newSalt_();
    var patch = {
      'Password Hash': hashPassword_(typed, salt),
      'Salt': salt,
      'Applied Set': fp,
      'Must Change': 'TRUE',
      'Password Updated': nowStamp_()
    };
    updateObject_(sheet, row._row, patch);
    for (var k in patch) row[k] = patch[k];
    return row;
  }

  // Brand new account with nothing typed. Admins never get an automatic
  // password: someone must type one into "Set Password". Students get a random
  // one of their own, shown in "Set Password" so the admin can hand it out.
  if (!hasHash && !typed) {
    if (sheet.getName() === TAB.ADMINS) return row;
    var def = randomPassword_();
    var s2 = newSalt_();
    var patch2 = {
      'Set Password': def,
      'Password Hash': hashPassword_(def, s2),
      'Salt': s2,
      'Applied Set': fingerprint_(def),
      'Must Change': 'TRUE',
      'Password Updated': nowStamp_()
    };
    updateObject_(sheet, row._row, patch2);
    for (var k2 in patch2) row[k2] = patch2[k2];
  }

  return row;
}

/** Admin action: push every pending "Set Password" value through the hasher. */
function applyPendingPasswords() {
  return withLock_(function () {
    var out = { students: 0, admins: 0 };
    [[tab_(TAB.STUDENTS), 'students'], [tab_(TAB.ADMINS), 'admins']].forEach(function (pair) {
      var sheet = pair[0];
      var t = readTable_(sheet);
      t.rows.forEach(function (row) {
        var before = str_(row['Password Hash']);
        ensurePassword_(sheet, row);
        if (str_(row['Password Hash']) !== before) out[pair[1]]++;
      });
    });
    return out;
  });
}

/* --------------------------------- login --------------------------------- */

/**
 * Students sign in with their Student ID (or their e-mail address).
 * Admins sign in with the e-mail address listed on the Admins tab.
 */
function doLogin_(role, username, password) {
  var id = str_(username);
  if (!id) throw new Error('Please enter your ' + (role === 'admin' ? 'e-mail address' : 'Student number') + '.');
  if (!str_(password)) throw new Error('Please enter your password.');
  checkThrottle_(id);

  var sheet = role === 'admin' ? tab_(TAB.ADMINS) : tab_(TAB.STUDENTS);
  var row = null;

  if (role === 'admin') {
    row = findRow_(sheet, 'Email', id);
  } else {
    row = findRow_(sheet, 'Student ID', id);
  }

  if (!row) {
    noteFailure_(id);
    throw new Error('That account or password is not correct.');
  }
  if (str_(row['Status']).toLowerCase() === 'inactive') {
    throw new Error('That account has been deactivated. Please contact your instructor.');
  }

  row = ensurePassword_(sheet, row);

  var attempt = hashPassword_(password, str_(row['Salt']));
  if (!constantTimeEquals_(attempt, str_(row['Password Hash']))) {
    noteFailure_(id);
    throw new Error('That account or password is not correct.');
  }
  clearThrottle_(id);

  var subject = role === 'admin' ? str_(row['Email']) : str_(row['Student ID']);
  updateObject_(sheet, row._row, { 'Last Login': nowStamp_() });
  logAction_(subject, role, 'LOGIN', subject, '');

  return {
    token: makeToken_(subject, role, str_(row['Password Hash'])),
    profile: profileOf_(role, row),
    mustChangePassword: bool_(row['Must Change'])
  };
}

function profileOf_(role, row) {
  if (role === 'admin') {
    return {
      role: 'admin',
      id: str_(row['Email']),
      name: str_(row['Name']) || str_(row['Email']),
      email: str_(row['Email']),
      adminRole: str_(row['Role']) || 'Admin'
    };
  }
  var full = [str_(row['First Name']), str_(row['Middle Name']), str_(row['Last Name'])]
    .filter(function (p) { return !!p; }).join(' ');
  return {
    role: 'student',
    id: str_(row['Student ID']),
    name: full || str_(row['Student ID']),
    lastName: str_(row['Last Name']),
    firstName: str_(row['First Name']),
    program: str_(row['Program']),
    yearLevel: str_(row['Year Level'])
  };
}

/* ---------------------------- change password ---------------------------- */

function doChangePassword_(session, currentPassword, newPassword) {
  var settings = settings_();
  if (session.role === 'student' && !bool_(settings['ALLOW_PASSWORD_CHANGE'])) {
    throw new Error('Password changes are currently disabled by the administrator.');
  }
  var np = String(newPassword || '');
  if (np.length < APP.MIN_PASSWORD_LENGTH) {
    throw new Error('Your new password must be at least ' + APP.MIN_PASSWORD_LENGTH + ' characters.');
  }
  if (/^\s|\s$/.test(np)) {
    throw new Error('Your new password cannot start or end with a space.');
  }

  var sheet = session.role === 'admin' ? tab_(TAB.ADMINS) : tab_(TAB.STUDENTS);
  var row = session.role === 'admin'
    ? findRow_(sheet, 'Email', session.id)
    : findRow_(sheet, 'Student ID', session.id);
  if (!row) throw new Error('AUTH:Your account could not be found.');

  var current = hashPassword_(String(currentPassword || ''), str_(row['Salt']));
  if (!constantTimeEquals_(current, str_(row['Password Hash']))) {
    throw new Error('Your current password is not correct.');
  }
  if (constantTimeEquals_(hashPassword_(np, str_(row['Salt'])), str_(row['Password Hash']))) {
    throw new Error('Please choose a password different from your current one.');
  }

  var salt = newSalt_();
  var hash = hashPassword_(np, salt);
  updateObject_(sheet, row._row, {
    'Set Password': '',          // the admin no longer knows this password — by design
    'Password Hash': hash,
    'Salt': salt,
    'Applied Set': '',
    'Must Change': 'FALSE',
    'Password Updated': nowStamp_()
  });
  logAction_(session.id, session.role, 'PASSWORD_CHANGE', session.id, '');

  return { token: makeToken_(session.id, session.role, hash) };
}

/**
 * ▶ Run from the editor once: moves every student and admin who is still on a
 * shared default password (e.g. "ee2026") to a unique random one, shown in the
 * "Set Password" column. Stops after ~5 minutes; run again to finish the rest.
 */
function resetSharedPasswords() {
  var shared = {};
  [str_(settings_()['DEFAULT_PASSWORD']), APP.FALLBACK_PASSWORD, 'ee2026'].forEach(function (p) {
    if (p) shared[p] = true;
  });
  var started = new Date().getTime();
  var changed = 0, remaining = 0;
  withLock_(function () {
    [tab_(TAB.STUDENTS), tab_(TAB.ADMINS)].forEach(function (sheet) {
      readTable_(sheet).rows.forEach(function (row) {
        if (!shared[str_(row['Set Password'])]) return;
        if ((new Date().getTime() - started) / 1000 > 290) { remaining++; return; }
        var pw = randomPassword_();
        var salt = newSalt_();
        updateObject_(sheet, row._row, {
          'Set Password': pw,
          'Password Hash': hashPassword_(pw, salt),
          'Salt': salt,
          'Applied Set': fingerprint_(pw),
          'Must Change': 'TRUE',
          'Password Updated': nowStamp_()
        });
        changed++;
      });
    });
  });
  logAction_('editor', 'admin', 'SHARED_PASSWORD_RESET', '', { changed: changed, remaining: remaining });
  var msg = changed + ' account(s) moved to unique passwords (see the "Set Password" column).' +
    (remaining ? ' ' + remaining + ' left: run resetSharedPasswords() again.' : '');
  console.log(msg);
  return msg;
}

/** Admin action: force a specific student back to a known password. */
function adminResetPassword_(session, studentId, newPassword) {
  var sheet = tab_(TAB.STUDENTS);
  var row = findRow_(sheet, 'Student ID', studentId);
  if (!row) throw new Error('No student with ID "' + studentId + '".');
  var pw = str_(newPassword) || randomPassword_();
  var salt = newSalt_();
  updateObject_(sheet, row._row, {
    'Set Password': pw,
    'Password Hash': hashPassword_(pw, salt),
    'Salt': salt,
    'Applied Set': fingerprint_(pw),
    'Must Change': 'TRUE',
    'Password Updated': nowStamp_()
  });
  logAction_(session.id, session.role, 'PASSWORD_RESET', studentId, '');
  var mail = mailPendingPasswords_();
  return {
    studentId: str_(row['Student ID']),
    password: pw,
    emailed: !!(mail && mail.sentTo[str_(row['Student ID']).toLowerCase()])
  };
}

/* ---------------------------- password e-mails --------------------------- */

/** True for the old portal-wide passwords, which must never be e-mailed out. */
function isSharedPassword_(pw) {
  var p = str_(pw);
  if (!p) return false;
  return p === APP.FALLBACK_PASSWORD || p === 'ee2026' || p === str_(settings_()['DEFAULT_PASSWORD']);
}

/**
 * E-mail each student a starting password an admin set, once.
 * Sent only when the student has an Email, is not Inactive, still has
 * "Must Change" = TRUE, and that exact password has not been e-mailed before
 * (tracked in "Emailed Set"). Students who chose their own password have a
 * blank "Set Password" and are never contacted. Stops when the daily mail quota
 * runs out; the hourly job sends the rest. Call inside withLock_.
 */
function sendPendingPasswordEmails_() {
  var sheet = tab_(TAB.STUDENTS);
  var rows = readTable_(sheet).rows;
  var s = settings_();
  var title = (s['PORTAL_TITLE'] || 'Electrical Engineering') + ' ' +
              (s['PORTAL_SUBTITLE'] || 'Student Grades Portal');
  var contact = str_(s['CONTACT_EMAIL']);
  var url = '';
  try { url = ScriptApp.getService().getUrl(); } catch (err) { /* not deployed */ }

  var out = { sent: 0, failed: [], waiting: 0, sentTo: {} };
  rows.forEach(function (row) {
    var id = str_(row['Student ID']);
    var email = str_(row['Email']);
    var pw = str_(row['Set Password']);
    if (!email || !pw || !bool_(row['Must Change'])) return;
    if (str_(row['Status']).toLowerCase() === 'inactive') return;
    if (isSharedPassword_(pw)) return;
    var fp = fingerprint_(pw);
    if (str_(row['Emailed Set']) === fp) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { out.failed.push(id + ': "' + email + '" is not an e-mail address'); return; }
    if (MailApp.getRemainingDailyQuota() < 1) { out.waiting++; return; }

    var body =
      'Hello ' + (str_(row['First Name']) || id) + ',\n\n' +
      'Your account on the ' + title + ' is ready.\n\n' +
      '    Student number:     ' + id + '\n' +
      '    Starting password:  ' + pw + '\n\n' +
      (url ? 'Sign in here: ' + url + '\n\n' : '') +
      'You will be asked to choose your own password the first time you sign in. ' +
      'Please do not share this password with anyone.' +
      (contact ? '\n\nQuestions? Contact ' + contact + '.' : '');
    var message = { to: email, subject: 'Your ' + title + ' password', body: body, name: title };
    if (contact) message.replyTo = contact;

    try {
      MailApp.sendEmail(message);
      updateObject_(sheet, row._row, { 'Emailed Set': fp, 'Password Emailed': nowStamp_() });
      out.sent++;
      out.sentTo[id.toLowerCase()] = true;
    } catch (err) {
      out.failed.push(id + ': ' + err.message);
    }
  });
  if (out.sent || out.failed.length) {
    logAction_('system', 'system', 'PASSWORD_EMAILS', '',
      { sent: out.sent, failed: out.failed, waiting: out.waiting });
  }
  return out;
}

/** Never lets an e-mail problem break the action that triggered it. */
function mailPendingPasswords_() {
  try {
    return sendPendingPasswordEmails_();
  } catch (err) {
    console.warn('Password e-mails: ' + err);
    return null;
  }
}

/**
 * ▶ Run once from the editor to approve the "send e-mail" permission.
 * Also sends any password e-mails still waiting.
 */
function sendPasswordEmails() {
  var r = withLock_(sendPendingPasswordEmails_);
  var msg = r.sent + ' sent, ' + r.waiting + ' waiting for tomorrow\'s quota, ' +
            r.failed.length + ' failed' + (r.failed.length ? ':\n  ' + r.failed.join('\n  ') : '.');
  console.log(msg);
  return msg;
}

/* ---------------------------- forgot password ---------------------------- */
/*  1. requestPasswordReset_ e-mails a 6-digit code to the address on file.
    2. completePasswordReset_ checks the code and sets the new password.
    The current password keeps working until step 2, so nobody can lock a
    student out just by requesting a code. Replies never reveal whether an
    account or e-mail exists.                                                */

var RESET = {
  CODE_MINUTES: 30,         // how long a code stays valid
  MAX_REQUESTS_PER_HOUR: 3, // codes per account per hour
  MAX_TRIES: 5              // wrong guesses before the code is thrown away
};

function resetKey_(role, id) {
  return 'reset:' + role + ':' + String(id).toLowerCase();
}

function resetSig_(role, id, code) {
  return Utilities.base64EncodeWebSafe(Utilities.computeHmacSha256Signature(
    role + '|' + String(id).toLowerCase() + '|' + String(code), secret_(PROP.SECRET)
  ));
}

function resetCode_() {
  var bytes = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256, Utilities.getUuid() + Utilities.getUuid()
  );
  var code = '';
  for (var i = 0; i < 6; i++) code += String(((bytes[i] + 256) % 256) % 10);
  return code;
}

function requestPasswordReset_(role, username) {
  var id = str_(username);
  if (!id) throw new Error('Please enter your ' + (role === 'admin' ? 'e-mail address' : 'Student number') + '.');
  var s = settings_();
  var contact = str_(s['CONTACT_EMAIL']);
  var ask = contact ? 'contact ' + contact : 'contact your instructor';

  if (role === 'student' && !bool_(s['ALLOW_PASSWORD_CHANGE'])) {
    throw new Error('Passwords are reset by your instructor. Please ' + ask + '.');
  }
  if (MailApp.getRemainingDailyQuota() < 1) {
    throw new Error('The portal cannot send e-mail right now. Please try again tomorrow or ' + ask + '.');
  }

  var generic = 'If that account has an e-mail address on file, a 6-digit code is on its way. ' +
    'It expires in ' + RESET.CODE_MINUTES + ' minutes. Nothing after a few minutes? Check your spam folder, or ' + ask + '.';

  var cache = CacheService.getScriptCache();
  var countKey = 'resetreq:' + role + ':' + id.toLowerCase();
  var n = Number(cache.get(countKey) || 0);
  if (n >= RESET.MAX_REQUESTS_PER_HOUR) return { message: generic };
  cache.put(countKey, String(n + 1), 3600);

  var sheet = role === 'admin' ? tab_(TAB.ADMINS) : tab_(TAB.STUDENTS);
  var row = findRow_(sheet, role === 'admin' ? 'Email' : 'Student ID', id);
  if (!row || str_(row['Status']).toLowerCase() === 'inactive') return { message: generic };
  var email = str_(row['Email']);
  if (!email) return { message: generic };

  var subject = role === 'admin' ? str_(row['Email']) : str_(row['Student ID']);
  var code = resetCode_();
  cache.put(resetKey_(role, subject),
    JSON.stringify({ sig: resetSig_(role, subject, code), tries: 0 }),
    RESET.CODE_MINUTES * 60);

  var title = (s['PORTAL_TITLE'] || 'Electrical Engineering') + ' ' +
              (s['PORTAL_SUBTITLE'] || 'Student Grades Portal');
  var name = role === 'admin' ? (str_(row['Name']) || email) : (str_(row['First Name']) || subject);
  var message = {
    to: email,
    subject: 'Your ' + title + ' password reset code',
    name: title,
    body:
      'Hello ' + name + ',\n\n' +
      'Someone (hopefully you) asked to reset the password for ' +
      (role === 'admin' ? email : 'student number ' + subject) + ' on the ' + title + '.\n\n' +
      '    Reset code:  ' + code + '\n\n' +
      'Enter it on the sign-in page within ' + RESET.CODE_MINUTES + ' minutes to choose a new password.\n\n' +
      'If you did not ask for this, ignore this e-mail. Your password has not changed.'
  };
  if (contact) message.replyTo = contact;
  MailApp.sendEmail(message);
  logAction_(subject, role, 'RESET_CODE_SENT', subject, '');
  return { message: generic };
}

function completePasswordReset_(role, username, code, newPassword) {
  var id = str_(username);
  var c = str_(code).replace(/\s+/g, '');
  var wrong = 'That code is not correct or has expired. You can request a new one.';
  if (!id || !c) throw new Error('Please enter your ' + (role === 'admin' ? 'e-mail address' : 'Student number') + ' and the code from the e-mail.');

  var np = String(newPassword || '');
  if (np.length < APP.MIN_PASSWORD_LENGTH) {
    throw new Error('Your new password must be at least ' + APP.MIN_PASSWORD_LENGTH + ' characters.');
  }
  if (/^\s|\s$/.test(np)) throw new Error('Your new password cannot start or end with a space.');
  if (isSharedPassword_(np)) throw new Error('Please choose a password only you know.');

  var sheet = role === 'admin' ? tab_(TAB.ADMINS) : tab_(TAB.STUDENTS);
  var row = findRow_(sheet, role === 'admin' ? 'Email' : 'Student ID', id);
  if (!row || str_(row['Status']).toLowerCase() === 'inactive') throw new Error(wrong);
  var subject = role === 'admin' ? str_(row['Email']) : str_(row['Student ID']);

  var cache = CacheService.getScriptCache();
  var key = resetKey_(role, subject);
  var raw = cache.get(key);
  if (!raw) throw new Error(wrong);
  var state = JSON.parse(raw);
  if (state.tries >= RESET.MAX_TRIES) {
    cache.remove(key);
    throw new Error('Too many wrong codes. Please request a new one.');
  }
  if (!constantTimeEquals_(resetSig_(role, subject, c), state.sig)) {
    state.tries++;
    cache.put(key, JSON.stringify(state), RESET.CODE_MINUTES * 60);
    throw new Error(wrong);
  }
  cache.remove(key);

  return withLock_(function () {
    var salt = newSalt_();
    updateObject_(sheet, row._row, {
      'Set Password': '',
      'Password Hash': hashPassword_(np, salt),
      'Salt': salt,
      'Applied Set': '',
      'Must Change': 'FALSE',
      'Password Updated': nowStamp_()
    });
    clearThrottle_(id);
    logAction_(subject, role, 'PASSWORD_RESET_SELF', subject, '');
    return { done: true };
  });
}
