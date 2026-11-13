/**
 * ============================================================================
 *  Api.gs  ·  Web app entry point and the single request router
 * ============================================================================
 *  The browser calls exactly one server function, api(request), and gets a
 *  JSON string back. Stringifying avoids every Apps Script serialisation quirk.
 * ============================================================================
 */

function doGet(e) {
  var page = HtmlService.createTemplateFromFile('Index');
  var s = {};
  try { s = settings_(); } catch (err) { s = {}; }
  page.portalTitle = s['PORTAL_TITLE'] || 'Electrical Engineering';
  page.portalSubtitle = s['PORTAL_SUBTITLE'] || 'Student Grades Portal';
  page.needsSetup = !getProp_(PROP.MASTER_ID);

  return page.evaluate()
    .setTitle((s['PORTAL_TITLE'] || 'Electrical Engineering') + ' — ' + (s['PORTAL_SUBTITLE'] || 'Grades Portal'))
    .addMetaTag('viewport', 'width=device-width, initial-scale=1, viewport-fit=cover')
    .setFaviconUrl('https://ssl.gstatic.com/docs/spreadsheets/favicon3.ico');
}

/** Used by Index.html to inline the CSS and JS partials. */
function include(filename) {
  return HtmlService.createHtmlOutputFromFile(filename).getContent();
}

/* -------------------------------- router --------------------------------- */

function api(request) {
  request = request || {};
  var action = String(request.action || '');
  var payload = request.payload || {};
  var token = request.token || '';

  try {
    var result = route_(action, payload, token);
    return JSON.stringify({ ok: true, data: result === undefined ? null : result });
  } catch (err) {
    var message = err && err.message ? String(err.message) : String(err);
    var needsAuth = message.indexOf('AUTH:') === 0;
    if (needsAuth) message = message.substring(5);
    var mustChange = message.indexOf('MUST_CHANGE:') === 0;
    if (mustChange) message = message.substring(12);
    console.error(action + ' → ' + message + (err && err.stack ? '\n' + err.stack : ''));
    return JSON.stringify({ ok: false, error: message, authRequired: needsAuth, mustChangePassword: mustChange });
  }
}

function route_(action, p, token) {
  switch (action) {

    /* ---- public ---- */
    case 'ping':
      return { app: APP.NAME, version: APP.VERSION, ready: !!getProp_(PROP.MASTER_ID) };

    case 'portal':
      return publicPortalInfo_();

    case 'login':
      return doLogin_(p.role === 'admin' ? 'admin' : 'student', p.username, p.password);

    case 'forgotPassword':
      return requestPasswordReset_(p.role === 'admin' ? 'admin' : 'student', p.username);

    case 'resetPassword':
      return completePasswordReset_(p.role === 'admin' ? 'admin' : 'student',
        p.username, p.code, p.newPassword);

    /* ---- signed in (either role) ---- */
    case 'session': {
      var s = requireSession_(token, null, true);
      return {
        profile: profileOf_(s.role, s.row),
        mustChangePassword: bool_(s.row['Must Change'])
      };
    }

    case 'changePassword': {
      var s2 = requireSession_(token, null, true);
      return doChangePassword_(s2, p.currentPassword, p.newPassword);
    }

    /* ---- student ---- */
    case 'student.home':
      return studentHome_(requireSession_(token));

    case 'student.updates':
      return studentUpdates_(requireSession_(token));

    case 'student.class':
      return studentClass_(requireSession_(token), p.sectionId);
    case 'student.assignment.submit':
      return submitAssignment_(requireSession_(token, 'student'), p);
    case 'student.submission':
      return getSubmissionFile_(requireSession_(token, 'student'), p.sectionId, p.assignmentId, '');
    case 'student.exam.start':
      return startExam_(requireSession_(token, 'student'), p);
    case 'student.exam.save':
      return saveExamAnswers_(requireSession_(token, 'student'), p);
    case 'student.exam.submit':
      return submitExam_(requireSession_(token, 'student'), p);
    case 'student.exam.image':
      return examImage_(requireSession_(token, 'student'), p);
    case 'student.file':        // Android app 1.2.0: `kind` is the Material ID
    case 'student.material':
      return getMaterialFile_(requireSession_(token, 'student'), p.sectionId, p.id || p.kind);

    /* ---- admin ---- */
    case 'admin.home':
      return adminHome_(requireSession_(token, 'admin'), p);

    case 'admin.semester.create':
      return createSemester_(requireSession_(token, 'admin'), p);
    case 'admin.semester.update':
      return updateSemester_(requireSession_(token, 'admin'), p);

    case 'admin.subject.create':
      return createSubject_(requireSession_(token, 'admin'), p);
    case 'admin.subject.update':
      return updateSubject_(requireSession_(token, 'admin'), p);
    case 'admin.subject.delete':
      return deleteSubject_(requireSession_(token, 'admin'), p);

    case 'admin.section.create':
      return createSection_(requireSession_(token, 'admin'), p);
    case 'admin.section.update':
      return updateSection_(requireSession_(token, 'admin'), p);
    case 'admin.section.remove':
      return removeSection_(requireSession_(token, 'admin'), p);
    case 'admin.section.data':
      return adminSectionData_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.section.sync':
      requireSession_(token, 'admin');
      return rebuildRoster_(p.sectionId);
    case 'admin.section.enroll':
      return enrollStudents_(requireSession_(token, 'admin'), p.sectionId, p.studentIds || []);
    case 'admin.section.unenroll':
      return unenrollStudent_(requireSession_(token, 'admin'), p.sectionId, p.studentId);

    case 'admin.assessment.create':
      return createAssessment_(requireSession_(token, 'admin'), p.sectionId, p);
    case 'admin.assessment.update':
      return updateAssessment_(requireSession_(token, 'admin'), p.sectionId, p);
    case 'admin.assessment.delete':
      return deleteAssessment_(requireSession_(token, 'admin'), p.sectionId, p);

    case 'admin.scores.save':
      return saveScores_(requireSession_(token, 'admin'), p.sectionId, p.changes || []);
    case 'admin.summary.rebuild':
      return rebuildSummary_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.indirect':
      return indirectDashboard_(requireSession_(token, 'admin'), p.semesterId);
    case 'admin.indirect.refresh':
      return refreshIndirect_(requireSession_(token, 'admin'), p.semesterId);
    case 'admin.materials':
      return adminMaterials_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.materials.upload':
      return uploadMaterial_(requireSession_(token, 'admin'), p);
    case 'admin.materials.update':
      return updateMaterial_(requireSession_(token, 'admin'), p);
    case 'admin.materials.move':
      return moveMaterial_(requireSession_(token, 'admin'), p.sectionId, p.id, p.dir);
    case 'admin.materials.replace':
      return replaceMaterialFile_(requireSession_(token, 'admin'), p);
    case 'admin.materials.remove':
      return removeMaterial_(requireSession_(token, 'admin'), p.sectionId, p.id);
    case 'admin.material':
      return getMaterialFile_(requireSession_(token, 'admin'), p.sectionId, p.id || p.kind);
    case 'admin.assignments':
      return adminAssignments_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.assignment.create':
      return createAssignment_(requireSession_(token, 'admin'), p);
    case 'admin.assignment.update':
      return updateAssignment_(requireSession_(token, 'admin'), p);
    case 'admin.assignment.remove':
      return removeAssignment_(requireSession_(token, 'admin'), p.sectionId, p.id);
    case 'admin.submissions':
      return adminSubmissions_(requireSession_(token, 'admin'), p.sectionId, p.assignmentId);
    case 'admin.submissions.grade':
      return gradeSubmissions_(requireSession_(token, 'admin'), p);
    case 'admin.submissions.deleteGraded':
      return deleteGradedFiles_(requireSession_(token, 'admin'), p.sectionId, p.assignmentId, !!p.dryRun);
    case 'admin.submission':
      return getSubmissionFile_(requireSession_(token, 'admin'), p.sectionId, p.assignmentId, p.studentId);
    case 'admin.exams':
      return adminExams_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.exams.bank':
      return createBank_(requireSession_(token, 'admin'), p.sectionId);
    case 'admin.exam.create':
      return createExam_(requireSession_(token, 'admin'), p);
    case 'admin.exam.update':
      return updateExam_(requireSession_(token, 'admin'), p);
    case 'admin.exam.remove':
      return removeExam_(requireSession_(token, 'admin'), p.sectionId, p.id);
    case 'admin.exam.results':
      return adminExamResults_(requireSession_(token, 'admin'), p.sectionId, p.examId);
    case 'admin.exam.release':
      return releaseExam_(requireSession_(token, 'admin'), p.sectionId, p.examId);
    case 'admin.exam.reset':
      return resetExamAttempt_(requireSession_(token, 'admin'), p.sectionId, p.examId, p.studentId);
    case 'admin.direct':
      return directDashboard_(requireSession_(token, 'admin'), p.semesterId, false);
    case 'admin.direct.refresh':
      return directDashboard_(requireSession_(token, 'admin'), p.semesterId, true);

    case 'admin.export.pdf':
      return exportClassRecordPdf_(requireSession_(token, 'admin'), p);
    case 'admin.section.status':
      return setFinalStatus_(requireSession_(token, 'admin'), p.sectionId, p.studentId, p.status);

    case 'admin.students.list':
      requireSession_(token, 'admin');
      return listStudents_(p);
    case 'admin.students.create':
      return createStudent_(requireSession_(token, 'admin'), p);
    case 'admin.students.update':
      return updateStudent_(requireSession_(token, 'admin'), p);
    case 'admin.students.delete':
      return deleteStudent_(requireSession_(token, 'admin'), p);
    case 'admin.students.import':
      return importStudents_(requireSession_(token, 'admin'), p);
    case 'admin.students.resetPassword':
      return adminResetPassword_(requireSession_(token, 'admin'), p.studentId, p.password);

    case 'admin.settings.save':
      return saveSettings_(requireSession_(token, 'admin'), p.values || {});
    case 'admin.scale.save':
      return saveGradeScale_(requireSession_(token, 'admin'), p.rows || []);

    case 'admin.tools.applyPasswords':
      requireSession_(token, 'admin');
      return applyPendingPasswords();
    case 'admin.tools.syncAll':
      requireSession_(token, 'admin');
      return syncAllRosters_(p.semesterId);

    default:
      throw new Error('Unknown action "' + action + '".');
  }
}

/* ------------------------------ public info ------------------------------ */

function publicPortalInfo_() {
  if (!getProp_(PROP.MASTER_ID)) {
    return { ready: false, title: 'Electrical Engineering', subtitle: 'Grades Portal' };
  }
  var s = settings_();
  return {
    ready: true,
    title: s['PORTAL_TITLE'] || 'Electrical Engineering',
    subtitle: s['PORTAL_SUBTITLE'] || 'Student Grades Portal',
    announcement: s['ANNOUNCEMENT'] || '',
    contactEmail: s['CONTACT_EMAIL'] || '',
    allowPasswordChange: bool_(s['ALLOW_PASSWORD_CHANGE'])
  };
}

/* -------------------------------- student -------------------------------- */

function studentHome_(session) {
  if (session.role !== 'student') {
    return { classes: [], semesters: [], profile: profileOf_(session.role, session.row) };
  }
  var s = settings_();
  var enrollments = enrollmentsForStudent_(session.id);
  var subjects = indexBy_(listSubjects_(), 'id');
  var semesters = listSemesters_();
  var semById = indexBy_(semesters, 'id');
  var sectionRows = readTable_(tab_(TAB.SECTIONS)).rows;
  var byId = {};
  sectionRows.forEach(function (r) { byId[str_(r['Section ID'])] = r; });

  var classes = [];
  var usedSemesters = {};
  enrollments.forEach(function (en) {
    var r = byId[en.sectionId];
    if (!r) return;
    var status = str_(r['Status']).toLowerCase();
    if (status === 'deleted') return;
    var obj = sectionRowToObject_(r, subjects, semById);
    delete obj.spreadsheetId;
    delete obj.spreadsheetUrl;
    delete obj.workbookId;
    classes.push(obj);
    usedSemesters[obj.semesterId] = true;
  });

  classes.sort(function (a, b) {
    return (a.subjectCode + a.name) > (b.subjectCode + b.name) ? 1 : -1;
  });

  var mine = semesters.filter(function (sm) { return usedSemesters[sm.id]; });
  var active = str_(s['ACTIVE_SEMESTER_ID']);
  if (!active || !usedSemesters[active]) active = mine.length ? mine[0].id : '';

  return {
    profile: profileOf_('student', session.row),
    semesters: mine,
    activeSemesterId: active,
    classes: classes,
    announcement: s['ANNOUNCEMENT'] || '',
    contactEmail: s['CONTACT_EMAIL'] || '',
    showRunningGrade: bool_(s['SHOW_RUNNING_GRADE']),
    showClassAverage: bool_(s['SHOW_CLASS_AVERAGE']),
    allowPasswordChange: bool_(s['ALLOW_PASSWORD_CHANGE'])
  };
}

function studentClass_(session, sectionId) {
  if (session.role !== 'student') throw new Error('AUTH:Please sign in as a student.');
  var s = settings_();
  var data = readSectionData_(sectionId, true);

  var me = null;
  for (var i = 0; i < data.roster.length; i++) {
    if (data.roster[i].studentId.toLowerCase() === String(session.id).toLowerCase()) {
      me = data.roster[i];
      break;
    }
  }
  if (!me) throw new Error('You are not enrolled in this class.');

  var visible = data.assessments.filter(function (a) { return a.published; });
  var stats = bool_(s['SHOW_CLASS_AVERAGE']) ? assessmentStats_(visible, data.roster) : {};
  var options = { countBlankAsZero: bool_(s['COUNT_BLANK_AS_ZERO']) };
  var grade = computeGrade_(visible, me.scores, options);

  var items = visible.map(function (a) {
    var raw = me.scores[a.id];
    var has = raw !== null && raw !== undefined && !isNaN(raw);
    return {
      id: a.id,
      name: a.name,
      type: a.type,
      max: a.max,
      weight: a.weight,
      date: a.date,
      score: has ? Number(raw) : null,
      percent: has && a.max > 0 ? round_((Number(raw) / a.max) * 100, 2) : null,
      classAverage: stats[a.id] ? stats[a.id].average : null,
      classHigh: stats[a.id] ? stats[a.id].high : null,
      recordedCount: stats[a.id] ? stats[a.id].count : null
    };
  });

  var section = data.section;
  return {
    section: {
      id: section.id, name: section.name,
      subjectCode: section.subjectCode, subjectTitle: section.subjectTitle,
      units: section.units, semesterName: section.semesterName,
      instructor: section.instructor, schedule: section.schedule, room: section.room
    },
    survey: surveyForStudent_(section, session.id),
    materials: materialsFor_(section.id, true),
    assignments: studentAssignments_(section.id, me, data.assessments),
    exams: studentExams_(section.id, me.studentId, data.assessments, me.scores),
    files: courseFilesFor_(section.id, true),
    items: items,
    classSize: data.roster.length,
    grade: bool_(s['SHOW_RUNNING_GRADE']) ? grade : {
      mode: grade.mode, recorded: grade.recorded, items: grade.items,
      earned: null, possible: null, percent: null, grade: null, remark: ''
    },
    showRunningGrade: bool_(s['SHOW_RUNNING_GRADE']),
    showClassAverage: bool_(s['SHOW_CLASS_AVERAGE'])
  };
}

/**
 * Every published score this student has, across their active classes, in one
 * small list. The web portal and the Android app compare it with what they
 * showed last time to spot new or changed scores. Nothing is stored here
 * about what a student has seen, so a direct edit in the sheet counts too.
 */
function studentUpdates_(session) {
  if (session.role !== 'student') throw new Error('AUTH:Please sign in as a student.');
  var subjects = indexBy_(listSubjects_(), 'id');
  var semById = indexBy_(listSemesters_(), 'id');
  var byId = {};
  readTable_(tab_(TAB.SECTIONS)).rows.forEach(function (r) { byId[str_(r['Section ID'])] = r; });

  var me = String(session.id).toLowerCase();
  var classes = [], scores = [], failed = 0, done = {};
  enrollmentsForStudent_(session.id).forEach(function (en) {
    if (done[en.sectionId]) return;
    done[en.sectionId] = true;
    var r = byId[en.sectionId];
    if (!r) return;
    var status = str_(r['Status']).toLowerCase();
    if (status === 'deleted' || status === 'archived') return;

    var sec = sectionRowToObject_(r, subjects, semById);
    var snap;
    try {
      snap = sectionSnapshot_(sec.id);
    } catch (err) {
      failed++;
      console.warn('student.updates ' + sec.id + ': ' + err);
      return;
    }
    classes.push({ id: sec.id, subjectCode: sec.subjectCode, subjectTitle: sec.subjectTitle, name: sec.name });
    var mine = snap.scores[me] || {};
    snap.items.forEach(function (a) {
      if (!mine.hasOwnProperty(a.id)) return;
      scores.push({ sectionId: sec.id, id: a.id, name: a.name, score: mine[a.id], max: a.max });
    });
  });

  return { classes: classes, scores: scores, failed: failed, checkedAt: nowStamp_() };
}

/* --------------------------------- admin --------------------------------- */

function adminHome_(session, p) {
  var s = settings_();
  var semesters = listSemesters_();
  var semesterId = str_(p && p.semesterId) || str_(s['ACTIVE_SEMESTER_ID']);
  if (!semesterId && semesters.length) semesterId = semesters[0].id;

  var studentCount = readTable_(tab_(TAB.STUDENTS)).rows.length;
  var sections = listSections_(semesterId);

  var webAppUrl = '';
  try { webAppUrl = ScriptApp.getService().getUrl(); } catch (err) { /* not deployed */ }

  var masterUrl = '';
  try { masterUrl = master_().getUrl(); } catch (err) { /* ignore */ }

  var scale = gradeScale_();
  var subjects = listSubjects_();

  return {
    profile: profileOf_(session.role, session.row),
    semesters: semesters,
    semesterId: semesterId,
    subjects: subjects,
    sections: sections,
    assessmentTypes: ASSESSMENT_TYPES,
    settings: s,
    scale: scale,
    counts: {
      students: studentCount,
      sections: sections.length,
      subjects: subjects.length,
      semesters: semesters.length
    },
    links: { webApp: webAppUrl, master: masterUrl },
    version: APP.VERSION
  };
}

function adminSectionData_(session, sectionId) {
  var data = readSectionData_(sectionId, false);
  var options = { countBlankAsZero: bool_(settings_()['COUNT_BLANK_AS_ZERO']) };
  var stats = assessmentStats_(data.assessments, data.roster);

  var students = data.roster.map(function (r) {
    var g = computeGrade_(data.assessments, r.scores, options);
    return {
      studentId: r.studentId,
      lastName: r.lastName,
      firstName: r.firstName,
      scores: r.scores,
      grade: g,
      finalStatus: r.finalStatus || '',
      standing: standingOf_(g, r.finalStatus)
    };
  });

  // Keep the enrollment index fresh whenever a class is opened.
  try { rebuildRoster_(sectionId); } catch (err) { console.warn('roster sync: ' + err); }

  return {
    section: data.section,
    assessments: data.assessments,
    students: students,
    stats: stats,
    totalWeight: round_(data.assessments.reduce(function (sum, a) {
      return sum + (a.weight || 0);
    }, 0), 2),
    spreadsheetUrl: data.section.spreadsheetUrl
  };
}

function saveSettings_(session, values) {
  return withLock_(function () {
    var allowed = {};
    DEFAULT_SETTINGS.forEach(function (row) { allowed[row[0]] = true; });
    var written = [];
    Object.keys(values).forEach(function (key) {
      if (!allowed[key]) return;
      setSetting_(key, String(values[key]));
      written.push(key);
    });
    logAction_(session.id, session.role, 'SETTINGS_SAVE', '', written.join(', '));
    return { saved: written };
  });
}

function saveGradeScale_(session, rows) {
  return withLock_(function () {
    var sheet = tab_(TAB.SCALE);
    if (sheet.getLastRow() > 1) {
      sheet.getRange(2, 1, sheet.getLastRow() - 1, 4).clearContent();
    }
    var clean = rows.filter(function (r) {
      return num_(r.min) !== null && num_(r.max) !== null && num_(r.grade) !== null;
    }).map(function (r) {
      return [num_(r.min), num_(r.max), num_(r.grade), str_(r.remark)];
    });
    if (clean.length) {
      ensureRows_(sheet, clean.length + 1);
      sheet.getRange(2, 1, clean.length, 4).setValues(clean);
    }
    _scaleCache = null;
    logAction_(session.id, session.role, 'SCALE_SAVE', '', { rows: clean.length });
    return { rows: clean.length };
  });
}

function syncAllRosters_(semesterId) {
  var sections = listSections_(semesterId || '');
  var done = 0, failed = [];
  var started = new Date().getTime();
  for (var i = 0; i < sections.length; i++) {
    if ((new Date().getTime() - started) / 1000 > APP.SYNC_BUDGET_SECONDS) break;
    try {
      rebuildRoster_(sections[i].id);
      done++;
    } catch (err) {
      failed.push(sections[i].name + ': ' + err.message);
    }
  }
  return { synced: done, total: sections.length, failed: failed };
}
