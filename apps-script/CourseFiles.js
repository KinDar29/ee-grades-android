/**
 * ============================================================================
 *  CourseFiles.js  ·  Syllabus and Tables of Specifications per class
 * ============================================================================
 *  The admin uploads a PDF for each kind (Syllabus, TOS for the Midterm Exam,
 *  TOS for the Final Exam) from the class page, optionally for every section
 *  of the same subject in the semester at once. The file is kept in the
 *  semester's Drive folder under "Course Files" and is NOT shared: students
 *  get the bytes through the API, and only for a class they are enrolled in
 *  and only while the file is set "Shown to students".
 *
 *  Master tab "CourseFiles" (created on first use): one row per class + kind.
 *  Several rows may point to the same Drive file (shared across sections).
 * ============================================================================
 */

var COURSE_FILES = {
  TAB: 'CourseFiles',
  FOLDER: 'Course Files',
  MAX_BYTES: 10 * 1024 * 1024,
  KINDS: [
    { id: 'SYLLABUS', label: 'Syllabus' },
    { id: 'TOS_ME', label: 'TOS — Midterm Exam' },
    { id: 'TOS_FE', label: 'TOS — Final Exam' }
  ]
};
SCHEMA[COURSE_FILES.TAB] = [
  'Section ID', 'Kind', 'Drive File ID', 'File Name', 'Size', 'Visible',
  'Uploaded At', 'Uploaded By'
];

function courseKind_(kind) {
  var k = str_(kind).toUpperCase();
  for (var i = 0; i < COURSE_FILES.KINDS.length; i++) {
    if (COURSE_FILES.KINDS[i].id === k) return COURSE_FILES.KINDS[i];
  }
  throw new Error('Unknown file type.');
}

function courseFileRows_() {
  return readTable_(tab_(COURSE_FILES.TAB)).rows;
}

function courseFileOut_(r) {
  var kind = courseKind_(r['Kind']);
  return {
    kind: kind.id,
    label: kind.label,
    name: str_(r['File Name']),
    size: num_(r['Size']) || 0,
    visible: bool_(r['Visible']),
    uploaded: str_(r['Uploaded At'])
  };
}

/** Files of one class, in kind order. Students see only the visible ones. */
function courseFilesFor_(sectionId, studentView) {
  var rows = {};
  courseFileRows_().forEach(function (r) {
    if (str_(r['Section ID']) === str_(sectionId)) rows[str_(r['Kind']).toUpperCase()] = r;
  });
  var out = [];
  COURSE_FILES.KINDS.forEach(function (k) {
    var r = rows[k.id];
    if (!r) return;
    var f = courseFileOut_(r);
    if (studentView && !f.visible) return;
    out.push(f);
  });
  return out;
}

/** Admin: the class's files plus the other sections of the same subject. */
function adminCourseFiles_(session, sectionId) {
  var sec = getSectionRecord_(sectionId);
  var siblings = listSections_(sec.semesterId).filter(function (s) {
    return s.id !== sec.id && s.subjectId === sec.subjectId;
  }).map(function (s) { return { id: s.id, name: s.name }; });
  return {
    kinds: COURSE_FILES.KINDS,
    files: courseFilesFor_(sectionId, false),
    siblings: siblings,
    maxBytes: COURSE_FILES.MAX_BYTES
  };
}

function courseFolder_(semesterId) {
  var sem = indexBy_(listSemesters_(), 'id')[semesterId] || {};
  var parent = sem.folderId ? DriveApp.getFolderById(sem.folderId) : rootFolder_();
  return folderByName_(parent, COURSE_FILES.FOLDER);
}

/** Trash a Drive file once no row points to it any more. */
function trashIfUnused_(fileId) {
  if (!fileId) return;
  var used = courseFileRows_().some(function (r) { return str_(r['Drive File ID']) === fileId; });
  if (used) return;
  try { DriveApp.getFileById(fileId).setTrashed(true); } catch (err) { console.warn('trash: ' + err); }
}

/**
 * p: { sectionId, kind, name, mimeType, data (base64), visible, alsoSectionIds: [] }
 */
function uploadCourseFile_(session, p) {
  var kind = courseKind_(p.kind);
  var name = str_(p.name).replace(/[\\/:*?"<>|]+/g, ' ').trim() || (kind.label + '.pdf');
  if (!/\.pdf$/i.test(name) || (p.mimeType && p.mimeType !== 'application/pdf')) {
    throw new Error('Please upload a PDF. In Word or Excel use File → Save as → PDF first.');
  }
  var bytes = Utilities.base64Decode(str_(p.data));
  if (!bytes.length) throw new Error('The file is empty.');
  if (bytes.length > COURSE_FILES.MAX_BYTES) throw new Error('The file is larger than 10 MB.');
  if (String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]) !== '%PDF') {
    throw new Error('That file is not a valid PDF.');
  }

  var sec = getSectionRecord_(p.sectionId);
  var targets = [sec.id];
  var siblings = listSections_(sec.semesterId).filter(function (s) { return s.subjectId === sec.subjectId; });
  (p.alsoSectionIds || []).forEach(function (id) {
    if (siblings.some(function (s) { return s.id === id; }) && targets.indexOf(id) < 0) targets.push(id);
  });

  var folder = courseFolder_(sec.semesterId);
  var blob = Utilities.newBlob(bytes, 'application/pdf',
    (sec.subjectCode ? sec.subjectCode + ' ' : '') + kind.label.replace(' — ', ' ') + ' - ' + name);
  var file = folder.createFile(blob);
  var visible = p.visible === undefined ? true : !!p.visible;

  return withLock_(function () {
    var sh = tab_(COURSE_FILES.TAB);
    var replaced = [];
    targets.forEach(function (sid) {
      var row = null;
      readTable_(sh).rows.forEach(function (r) {
        if (str_(r['Section ID']) === sid && str_(r['Kind']).toUpperCase() === kind.id) row = r;
      });
      var obj = {
        'Section ID': sid, 'Kind': kind.id, 'Drive File ID': file.getId(), 'File Name': name,
        'Size': bytes.length, 'Visible': visible ? 'TRUE' : 'FALSE',
        'Uploaded At': nowStamp_(), 'Uploaded By': session.id
      };
      if (row) {
        replaced.push(str_(row['Drive File ID']));
        updateObject_(sh, row._row, obj);
      } else {
        appendObject_(sh, obj);
      }
    });
    replaced.forEach(trashIfUnused_);
    logAction_(session.id, session.role, 'COURSE_FILE_UPLOAD', sec.id,
      { kind: kind.id, name: name, sections: targets.join(',') });
    return adminCourseFiles_(session, sec.id);
  });
}

function setCourseFileVisible_(session, sectionId, kind, visible) {
  var k = courseKind_(kind);
  return withLock_(function () {
    var sh = tab_(COURSE_FILES.TAB);
    var row = null;
    readTable_(sh).rows.forEach(function (r) {
      if (str_(r['Section ID']) === str_(sectionId) && str_(r['Kind']).toUpperCase() === k.id) row = r;
    });
    if (!row) throw new Error('That file is no longer there.');
    updateObject_(sh, row._row, { 'Visible': visible ? 'TRUE' : 'FALSE' });
    logAction_(session.id, session.role, 'COURSE_FILE_VISIBLE', sectionId, { kind: k.id, visible: !!visible });
    return adminCourseFiles_(session, sectionId);
  });
}

function removeCourseFile_(session, sectionId, kind) {
  var k = courseKind_(kind);
  return withLock_(function () {
    var sh = tab_(COURSE_FILES.TAB);
    var row = null;
    readTable_(sh).rows.forEach(function (r) {
      if (str_(r['Section ID']) === str_(sectionId) && str_(r['Kind']).toUpperCase() === k.id) row = r;
    });
    if (row) {
      var fileId = str_(row['Drive File ID']);
      sh.deleteRow(row._row);
      trashIfUnused_(fileId);
      logAction_(session.id, session.role, 'COURSE_FILE_REMOVE', sectionId, { kind: k.id });
    }
    return adminCourseFiles_(session, sectionId);
  });
}

/** The PDF itself, as base64. Students: enrolled + visible only. */
function getCourseFile_(session, sectionId, kind) {
  var k = courseKind_(kind);
  var sid = str_(sectionId);
  if (session.role === 'student') {
    var me = String(session.id).toLowerCase();
    var enrolled = readTable_(tab_(TAB.ENROLLMENTS)).rows.some(function (r) {
      return str_(r['Section ID']) === sid && str_(r['Student ID']).toLowerCase() === me;
    });
    if (!enrolled) throw new Error('You are not enrolled in this class.');
  }
  var row = null;
  courseFileRows_().forEach(function (r) {
    if (str_(r['Section ID']) === sid && str_(r['Kind']).toUpperCase() === k.id) row = r;
  });
  if (!row || (session.role === 'student' && !bool_(row['Visible']))) {
    throw new Error('This file is not available.');
  }
  var blob = DriveApp.getFileById(str_(row['Drive File ID'])).getBlob();
  return {
    kind: k.id, label: k.label, name: str_(row['File Name']),
    mimeType: 'application/pdf',
    data: Utilities.base64Encode(blob.getBytes())
  };
}
