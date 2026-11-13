/**
 * ============================================================================
 *  Master.gs  ·  Semesters, subjects, sections and the student registry
 * ============================================================================
 */

/* ------------------------------- semesters ------------------------------- */

function listSemesters_() {
  var t = readTable_(tab_(TAB.SEMESTERS));
  return t.rows.map(function (r) {
    return {
      id: str_(r['Semester ID']),
      name: str_(r['Name']),
      schoolYear: str_(r['School Year']),
      term: str_(r['Term']),
      active: bool_(r['Active']),
      folderId: str_(r['Folder ID']),
      folderUrl: str_(r['Folder URL']),
      createdAt: str_(r['Created At'])
    };
  }).sort(function (a, b) { return a.name < b.name ? 1 : -1; });
}

function createSemester_(session, data) {
  var name = str_(data.name);
  if (!name) throw new Error('Please give the semester a name, e.g. "2026-2027 First Semester".');

  return withLock_(function () {
    var sheet = tab_(TAB.SEMESTERS);
    if (findRow_(sheet, 'Name', name)) throw new Error('A semester called "' + name + '" already exists.');

    var folder = folderByName_(rootFolder_(), name);
    var id = uid_('SEM');
    appendObject_(sheet, {
      'Semester ID': id,
      'Name': name,
      'School Year': str_(data.schoolYear),
      'Term': str_(data.term),
      'Active': data.active === false ? 'FALSE' : 'TRUE',
      'Folder ID': folder.getId(),
      'Folder URL': folder.getUrl(),
      'Created At': nowStamp_()
    });
    if (data.makeCurrent !== false) setSetting_('ACTIVE_SEMESTER_ID', id);
    logAction_(session.id, session.role, 'SEMESTER_CREATE', id, name);
    return { id: id, folderUrl: folder.getUrl() };
  });
}

function updateSemester_(session, data) {
  return withLock_(function () {
    var sheet = tab_(TAB.SEMESTERS);
    var row = findRow_(sheet, 'Semester ID', data.id);
    if (!row) throw new Error('That semester no longer exists.');

    var patch = {};
    if (data.schoolYear !== undefined) patch['School Year'] = str_(data.schoolYear);
    if (data.term !== undefined) patch['Term'] = str_(data.term);
    if (data.active !== undefined) patch['Active'] = data.active ? 'TRUE' : 'FALSE';

    if (data.name !== undefined && str_(data.name) && str_(data.name) !== str_(row['Name'])) {
      patch['Name'] = str_(data.name);
      if (str_(row['Folder ID'])) {
        try { DriveApp.getFolderById(str_(row['Folder ID'])).setName(str_(data.name)); }
        catch (err) { console.warn('Folder rename failed: ' + err); }
      }
    }
    updateObject_(sheet, row._row, patch);
    if (data.makeCurrent) setSetting_('ACTIVE_SEMESTER_ID', str_(data.id));
    logAction_(session.id, session.role, 'SEMESTER_UPDATE', str_(data.id), patch);
    return { ok: true };
  });
}

/* -------------------------------- subjects ------------------------------- */

function listSubjects_() {
  var t = readTable_(tab_(TAB.SUBJECTS));
  return t.rows.map(function (r) {
    return {
      id: str_(r['Subject ID']),
      code: str_(r['Code']),
      title: str_(r['Title']),
      units: num_(r['Units']),
      description: str_(r['Description'])
    };
  }).sort(function (a, b) { return a.code > b.code ? 1 : -1; });
}

function createSubject_(session, data) {
  var code = str_(data.code);
  var title = str_(data.title);
  if (!code) throw new Error('Please enter a subject code, e.g. "IT 101".');
  if (!title) throw new Error('Please enter the subject title.');

  return withLock_(function () {
    var sheet = tab_(TAB.SUBJECTS);
    if (findRow_(sheet, 'Code', code)) throw new Error('Subject code "' + code + '" is already in use.');
    var id = uid_('SUB');
    appendObject_(sheet, {
      'Subject ID': id,
      'Code': code,
      'Title': title,
      'Units': data.units === '' || data.units === undefined ? '' : num_(data.units),
      'Description': str_(data.description),
      'Created At': nowStamp_()
    });
    logAction_(session.id, session.role, 'SUBJECT_CREATE', id, code + ' ' + title);
    return { id: id };
  });
}

function updateSubject_(session, data) {
  return withLock_(function () {
    var sheet = tab_(TAB.SUBJECTS);
    var row = findRow_(sheet, 'Subject ID', data.id);
    if (!row) throw new Error('That subject no longer exists.');
    var patch = {};
    if (data.code !== undefined) patch['Code'] = str_(data.code);
    if (data.title !== undefined) patch['Title'] = str_(data.title);
    if (data.units !== undefined) patch['Units'] = data.units === '' ? '' : num_(data.units);
    if (data.description !== undefined) patch['Description'] = str_(data.description);
    updateObject_(sheet, row._row, patch);
    logAction_(session.id, session.role, 'SUBJECT_UPDATE', str_(data.id), patch);
    return { ok: true };
  });
}

function deleteSubject_(session, data) {
  return withLock_(function () {
    var sections = readTable_(tab_(TAB.SECTIONS)).rows.filter(function (r) {
      return str_(r['Subject ID']) === str_(data.id) && str_(r['Status']).toLowerCase() !== 'deleted';
    });
    if (sections.length) {
      throw new Error('That subject still has ' + sections.length +
        ' class(es). Remove or archive them first.');
    }
    var sheet = tab_(TAB.SUBJECTS);
    var row = findRow_(sheet, 'Subject ID', data.id);
    if (row) sheet.deleteRow(row._row);
    logAction_(session.id, session.role, 'SUBJECT_DELETE', str_(data.id), '');
    return { ok: true };
  });
}

/* -------------------------------- sections ------------------------------- */

function sectionRowToObject_(r, subjectsById, semestersById) {
  var subject = subjectsById[str_(r['Subject ID'])] || {};
  var semester = semestersById[str_(r['Semester ID'])] || {};
  return {
    id: str_(r['Section ID']),
    name: str_(r['Section Name']),
    subjectId: str_(r['Subject ID']),
    subjectCode: subject.code || '',
    subjectTitle: subject.title || '',
    units: subject.units === undefined ? null : subject.units,
    semesterId: str_(r['Semester ID']),
    semesterName: semester.name || '',
    instructor: str_(r['Instructor']),
    schedule: str_(r['Schedule']),
    room: str_(r['Room']),
    status: str_(r['Status']) || 'Active',
    spreadsheetId: str_(r['Spreadsheet ID']),
    spreadsheetUrl: str_(r['Spreadsheet URL']),
    enrolled: num_(r['Enrolled']) || 0,
    lastSynced: str_(r['Last Synced']),
    surveyUrl: str_(r['Survey Link']),
    workbookId: workbookId_(r['Assessment Workbook'])
  };
}

/** Blank, or an https:// link (the survey is opened in a new tab by students). */
function checkSurveyUrl_(v) {
  var s = str_(v);
  if (s && !/^https:\/\/\S+$/i.test(s)) throw new Error('The survey link must be a full https:// address.');
  return s;
}

function indexBy_(list, key) {
  var out = {};
  list.forEach(function (item) { out[item[key]] = item; });
  return out;
}

function listSections_(semesterId) {
  var subjects = indexBy_(listSubjects_(), 'id');
  var semesters = indexBy_(listSemesters_(), 'id');
  var t = readTable_(tab_(TAB.SECTIONS));
  return t.rows
    .filter(function (r) {
      if (str_(r['Status']).toLowerCase() === 'deleted') return false;
      return !semesterId || str_(r['Semester ID']) === String(semesterId);
    })
    .map(function (r) { return sectionRowToObject_(r, subjects, semesters); })
    .sort(function (a, b) {
      var ka = (a.subjectCode + ' ' + a.name).toLowerCase();
      var kb = (b.subjectCode + ' ' + b.name).toLowerCase();
      return ka > kb ? 1 : (ka < kb ? -1 : 0);
    });
}

function getSectionRecord_(sectionId) {
  var row = findRow_(tab_(TAB.SECTIONS), 'Section ID', sectionId);
  if (!row) throw new Error('That class could not be found.');
  var subjects = indexBy_(listSubjects_(), 'id');
  var semesters = indexBy_(listSemesters_(), 'id');
  var obj = sectionRowToObject_(row, subjects, semesters);
  obj._row = row._row;
  return obj;
}

function createSection_(session, data) {
  var name = str_(data.name);
  if (!name) throw new Error('Please enter the section name, e.g. "BSIT 1-A".');

  return withLock_(function () {
    var semester = findRow_(tab_(TAB.SEMESTERS), 'Semester ID', data.semesterId);
    if (!semester) throw new Error('Please choose a semester first.');
    var subject = findRow_(tab_(TAB.SUBJECTS), 'Subject ID', data.subjectId);
    if (!subject) throw new Error('Please choose a subject first.');

    var sectionsSheet = tab_(TAB.SECTIONS);
    var clash = readTable_(sectionsSheet).rows.filter(function (r) {
      return str_(r['Semester ID']) === str_(data.semesterId) &&
             str_(r['Subject ID']) === str_(data.subjectId) &&
             str_(r['Section Name']).toLowerCase() === name.toLowerCase() &&
             str_(r['Status']).toLowerCase() !== 'deleted';
    });
    if (clash.length) throw new Error('That subject already has a section called "' + name + '" this semester.');

    var folder = str_(semester['Folder ID'])
      ? DriveApp.getFolderById(str_(semester['Folder ID']))
      : folderByName_(rootFolder_(), str_(semester['Name']));

    var id = uid_('SEC');
    var fileName = str_(subject['Code']) + ' — ' + name;
    var ss = buildSectionSpreadsheet_(fileName, folder, {
      sectionId: id,
      sectionName: name,
      subjectCode: str_(subject['Code']),
      subjectTitle: str_(subject['Title']),
      units: str_(subject['Units']),
      semesterName: str_(semester['Name']),
      instructor: str_(data.instructor),
      schedule: str_(data.schedule),
      room: str_(data.room)
    });

    appendObject_(sectionsSheet, {
      'Section ID': id,
      'Section Name': name,
      'Subject ID': str_(data.subjectId),
      'Semester ID': str_(data.semesterId),
      'Instructor': str_(data.instructor),
      'Schedule': str_(data.schedule),
      'Room': str_(data.room),
      'Survey Link': checkSurveyUrl_(data.surveyUrl),
      'Assessment Workbook': workbookId_(data.workbook),
      'Status': 'Active',
      'Spreadsheet ID': ss.getId(),
      'Spreadsheet URL': ss.getUrl(),
      'Enrolled': 0,
      'Last Synced': nowStamp_(),
      'Created At': nowStamp_()
    });
    logAction_(session.id, session.role, 'SECTION_CREATE', id, fileName);
    return { id: id, spreadsheetUrl: ss.getUrl() };
  });
}

function updateSection_(session, data) {
  return withLock_(function () {
    var sheet = tab_(TAB.SECTIONS);
    var row = findRow_(sheet, 'Section ID', data.id);
    if (!row) throw new Error('That class no longer exists.');

    var patch = {};
    ['name:Section Name', 'instructor:Instructor', 'schedule:Schedule', 'room:Room', 'status:Status']
      .forEach(function (pair) {
        var parts = pair.split(':');
        if (data[parts[0]] !== undefined) patch[parts[1]] = str_(data[parts[0]]);
      });
    if (data.subjectId !== undefined && str_(data.subjectId)) patch['Subject ID'] = str_(data.subjectId);
    if (data.surveyUrl !== undefined) patch['Survey Link'] = checkSurveyUrl_(data.surveyUrl);
    if (data.workbook !== undefined) patch['Assessment Workbook'] = workbookId_(data.workbook);
    updateObject_(sheet, row._row, patch);
    if (patch['Assessment Workbook']) {
      try { CacheService.getScriptCache().remove('indirect:' + patch['Assessment Workbook']); } catch (err) { /* ignore */ }
    }

    // Keep the spreadsheet name and Info tab in step.
    try {
      var updated = getSectionRecord_(data.id);
      if (updated.spreadsheetId) {
        var file = DriveApp.getFileById(updated.spreadsheetId);
        file.setName(updated.subjectCode + ' — ' + updated.name);
        writeSectionInfo_(SpreadsheetApp.openById(updated.spreadsheetId), {
          sectionId: updated.id,
          sectionName: updated.name,
          subjectCode: updated.subjectCode,
          subjectTitle: updated.subjectTitle,
          units: updated.units,
          semesterName: updated.semesterName,
          instructor: updated.instructor,
          schedule: updated.schedule,
          room: updated.room
        });
      }
    } catch (err) {
      console.warn('Could not refresh section file: ' + err);
    }

    logAction_(session.id, session.role, 'SECTION_UPDATE', str_(data.id), patch);
    return { ok: true };
  });
}

/** Archive (keep the file) or delete (send the file to Drive's trash). */
function removeSection_(session, data) {
  return withLock_(function () {
    var sheet = tab_(TAB.SECTIONS);
    var row = findRow_(sheet, 'Section ID', data.id);
    if (!row) throw new Error('That class no longer exists.');

    if (data.mode === 'delete') {
      if (str_(row['Spreadsheet ID'])) {
        try { DriveApp.getFileById(str_(row['Spreadsheet ID'])).setTrashed(true); }
        catch (err) { console.warn('Could not trash section file: ' + err); }
      }
      updateObject_(sheet, row._row, { 'Status': 'Deleted' });
      clearEnrollmentIndex_(str_(data.id));
    } else {
      updateObject_(sheet, row._row, { 'Status': 'Archived' });
    }
    logAction_(session.id, session.role, 'SECTION_' + String(data.mode || 'archive').toUpperCase(),
      str_(data.id), str_(row['Section Name']));
    return { ok: true };
  });
}

/* --------------------------- student registry ---------------------------- */

function studentRowToObject_(r) {
  return {
    id: str_(r['Student ID']),
    lastName: str_(r['Last Name']),
    firstName: str_(r['First Name']),
    middleName: str_(r['Middle Name']),
    name: [str_(r['First Name']), str_(r['Last Name'])].filter(String).join(' '),
    program: str_(r['Program']),
    yearLevel: str_(r['Year Level']),
    email: str_(r['Email']),
    status: str_(r['Status']) || 'Active',
    currentPassword: str_(r['Set Password']),
    hasOwnPassword: !str_(r['Set Password']) && !!str_(r['Password Hash']),
    mustChange: bool_(r['Must Change']),
    lastLogin: str_(r['Last Login']),
    row: r._row
  };
}

function listStudents_(params) {
  params = params || {};
  var q = str_(params.q).toLowerCase();
  var all = readTable_(tab_(TAB.STUDENTS)).rows.map(studentRowToObject_);
  var filtered = !q ? all : all.filter(function (s) {
    return (s.id + ' ' + s.lastName + ' ' + s.firstName + ' ' + s.middleName + ' ' + s.program)
      .toLowerCase().indexOf(q) > -1;
  });
  filtered.sort(function (a, b) {
    var ka = (a.lastName + a.firstName).toLowerCase();
    var kb = (b.lastName + b.firstName).toLowerCase();
    return ka > kb ? 1 : (ka < kb ? -1 : 0);
  });
  var page = Math.max(1, Number(params.page) || 1);
  var size = Math.min(500, Math.max(10, Number(params.pageSize) || 100));
  return {
    total: filtered.length,
    page: page,
    pageSize: size,
    items: filtered.slice((page - 1) * size, page * size)
  };
}

function createStudent_(session, data) {
  var id = str_(data.id);
  if (!id) throw new Error('Please enter the Student ID.');
  if (!str_(data.lastName)) throw new Error('Please enter the last name.');

  return withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    if (findRow_(sheet, 'Student ID', id)) throw new Error('Student ID "' + id + '" is already registered.');
    var pw = str_(data.password) || randomPassword_();
    var salt = newSalt_();
    appendObject_(sheet, {
      'Student ID': id,
      'Last Name': str_(data.lastName),
      'First Name': str_(data.firstName),
      'Middle Name': str_(data.middleName),
      'Program': str_(data.program),
      'Year Level': str_(data.yearLevel),
      'Email': str_(data.email),
      'Status': 'Active',
      'Set Password': pw,
      'Password Hash': hashPassword_(pw, salt),
      'Salt': salt,
      'Applied Set': fingerprint_(pw),
      'Must Change': 'TRUE',
      'Password Updated': nowStamp_(),
      'Last Login': '',
      'Created At': nowStamp_()
    });
    logAction_(session.id, session.role, 'STUDENT_CREATE', id, str_(data.lastName));
    var mail = mailPendingPasswords_();
    return { id: id, password: pw, emailed: !!(mail && mail.sentTo[id.toLowerCase()]) };
  });
}

function updateStudent_(session, data) {
  return withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    var row = findRow_(sheet, 'Student ID', data.id);
    if (!row) throw new Error('That student is not in the registry.');
    var patch = {};
    [['lastName', 'Last Name'], ['firstName', 'First Name'], ['middleName', 'Middle Name'],
     ['program', 'Program'], ['yearLevel', 'Year Level'],
     ['status', 'Status'], ['email', 'Email']].forEach(function (pair) {
      if (data[pair[0]] !== undefined) patch[pair[1]] = str_(data[pair[0]]);
    });
    updateObject_(sheet, row._row, patch);
    logAction_(session.id, session.role, 'STUDENT_UPDATE', str_(data.id), patch);
    if (patch['Email']) mailPendingPasswords_();   // e.g. an e-mail added for a new student
    return { ok: true };
  });
}

/**
 * Remove a student from the registry, and from every class they are in.
 * This deletes their scores in those classes as well — use Status = Inactive
 * when you want the records kept but the sign-in blocked.
 */
function deleteStudent_(session, data) {
  var id = str_(data.id);
  if (!id) throw new Error('Which student?');

  return withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    var row = findRow_(sheet, 'Student ID', id);
    if (!row) throw new Error('That student is not in the registry.');
    var label = str_(row['Last Name']) + ', ' + str_(row['First Name']);

    // Pull them out of every class first, so no orphan rows are left behind.
    var cleared = 0;
    enrollmentsForStudent_(id).forEach(function (en) {
      try {
        var record = getSectionRecord_(en.sectionId);
        if (!record.spreadsheetId) return;
        var grades = SpreadsheetApp.openById(record.spreadsheetId).getSheetByName(STAB.GRADES);
        if (!grades || grades.getLastRow() < 2) return;
        var ids = grades.getRange(2, 1, grades.getLastRow() - 1, 1).getValues();
        for (var i = ids.length - 1; i >= 0; i--) {
          if (str_(ids[i][0]).toLowerCase() === id.toLowerCase()) grades.deleteRow(i + 2);
        }
        rebuildRoster_(en.sectionId);
        cleared++;
      } catch (err) {
        console.warn('Could not clear ' + id + ' from ' + en.sectionId + ': ' + err);
      }
    });

    sheet.deleteRow(row._row);
    logAction_(session.id, session.role, 'STUDENT_DELETE', id, { name: label, classes: cleared });
    return { id: id, classesCleared: cleared };
  });
}

/**
 * Bulk import from pasted text. One student per line:
 *   Student No., Last Name, First Name, Middle Name, Program, Year Level
 * Commas or tabs both work. Existing IDs are updated, not duplicated.
 */
function importStudents_(session, data) {
  var text = String(data.text || '');
  var lines = text.split(/\r?\n/).filter(function (l) { return str_(l).length > 0; });
  if (!lines.length) throw new Error('Nothing to import — paste at least one line.');

  return withLock_(function () {
    var sheet = tab_(TAB.STUDENTS);
    var existing = {};
    readTable_(sheet).rows.forEach(function (r) {
      existing[str_(r['Student ID']).toLowerCase()] = r;
    });
    var added = 0, updated = 0, skipped = [];
    var newRows = [];
    var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]
      .map(function (h) { return str_(h); });

    lines.forEach(function (line, i) {
      var cells = line.indexOf('\t') > -1 ? line.split('\t') : line.split(',');
      cells = cells.map(function (c) { return str_(c); });
      var id = cells[0];
      if (!id) { skipped.push('Line ' + (i + 1) + ': no Student ID'); return; }
      if (/student\s*id/i.test(id)) return; // header line

      var payload = {
        'Last Name': cells[1] || '',
        'First Name': cells[2] || '',
        'Middle Name': cells[3] || '',
        'Program': cells[4] || '',
        'Year Level': cells[5] || ''
      };
      if (cells[6]) payload['Email'] = cells[6];

      var found = existing[id.toLowerCase()];
      if (found) {
        updateObject_(sheet, found._row, payload);
        updated++;
      } else {
        var salt = newSalt_();
        var defaultPw = randomPassword_();   // unique per student
        payload['Student ID'] = id;
        payload['Status'] = 'Active';
        payload['Set Password'] = defaultPw;
        payload['Password Hash'] = hashPassword_(defaultPw, salt);
        payload['Salt'] = salt;
        payload['Applied Set'] = fingerprint_(defaultPw);
        payload['Must Change'] = 'TRUE';
        payload['Password Updated'] = nowStamp_();
        payload['Created At'] = nowStamp_();
        newRows.push(headers.map(function (h) {
          var v = payload[h] === undefined ? '' : payload[h];
          return TEXT_COLUMNS[str_(h)] ? asText_(v) : v;
        }));
        existing[id.toLowerCase()] = { _row: -1 };
        added++;
      }
    });

    if (newRows.length) {
      var start = sheet.getLastRow() + 1;
      ensureRows_(sheet, start + newRows.length);
      sheet.getRange(start, 1, newRows.length, headers.length).setValues(newRows);
    }
    logAction_(session.id, session.role, 'STUDENT_IMPORT', '',
      { added: added, updated: updated, skipped: skipped.length });
    var mail = mailPendingPasswords_();
    return { added: added, updated: updated, skipped: skipped, emailed: mail ? mail.sent : 0 };
  });
}

/* --------------------------- enrollment index ---------------------------- */

function clearEnrollmentIndex_(sectionId) {
  writeEnrollmentIndex_(sectionId, '', []);
}

/**
 * Replace the index entries for one section with the given student IDs.
 * Rewritten in a single block write — deleting rows one by one is far too slow
 * once the index holds a few thousand entries.
 */
function writeEnrollmentIndex_(sectionId, semesterId, studentIds) {
  var sheet = tab_(TAB.ENROLLMENTS);
  var t = readTable_(sheet);
  var target = String(sectionId);

  var keep = [];
  t.rows.forEach(function (r) {
    if (str_(r['Section ID']) === target) return;
    keep.push([str_(r['Section ID']), asText_(str_(r['Student ID'])),
               str_(r['Semester ID']), str_(r['Synced At'])]);
  });

  var stamp = nowStamp_();
  (studentIds || []).forEach(function (sid) {
    keep.push([target, asText_(String(sid)), String(semesterId || ''), stamp]);
  });

  var lastRow = sheet.getLastRow();
  if (lastRow > 1) sheet.getRange(2, 1, lastRow - 1, 4).clearContent();
  if (keep.length) {
    ensureRows_(sheet, keep.length + 1);
    sheet.getRange(2, 1, keep.length, 4).setValues(keep);
  }
}

function enrollmentsForStudent_(studentId) {
  var needle = String(studentId).toLowerCase();
  return readTable_(tab_(TAB.ENROLLMENTS)).rows.filter(function (r) {
    return str_(r['Student ID']).toLowerCase() === needle;
  }).map(function (r) {
    return { sectionId: str_(r['Section ID']), semesterId: str_(r['Semester ID']) };
  });
}
