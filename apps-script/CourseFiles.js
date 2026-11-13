/**
 * ============================================================================
 *  CourseFiles.js  ·  Class materials (modules, problem sets, syllabus, TOS)
 * ============================================================================
 *  The admin uploads any number of PDFs to a class, each with a title, a type,
 *  a week/unit group and a position, optionally to every section of the same
 *  subject in the semester at once. Each can be hidden or set to appear from a
 *  date. Files are kept in the semester's Drive folder under "Course Files"
 *  and are NOT shared: students get the bytes through the API, only for a
 *  class they are enrolled in and only while the material is visible.
 *
 *  Master tab "Materials": one row per class + material. Several rows may
 *  point to the same Drive file (uploaded to several sections); a Drive file
 *  is trashed once no row uses it.
 *
 *  The first version kept exactly three files per class (Syllabus, TOS
 *  Midterm, TOS Final) in the "CourseFiles" tab; those rows are copied into
 *  "Materials" once (migrateCourseFiles_), and the old tab is left as is.
 * ============================================================================
 */

var MATERIALS = {
  TAB: 'Materials',
  OLD_TAB: 'CourseFiles',
  FOLDER: 'Course Files',
  MAX_BYTES: 20 * 1024 * 1024,
  TYPES: [
    { id: 'MODULE', label: 'Module' },
    { id: 'PROBLEM_SET', label: 'Problem set' },
    { id: 'SYLLABUS', label: 'Syllabus' },
    { id: 'TOS', label: 'TOS' },
    { id: 'OTHER', label: 'Other' }
  ]
};
SCHEMA[MATERIALS.TAB] = [
  'Material ID', 'Section ID', 'Type', 'Title', 'Group', 'Order',
  'Drive File ID', 'File Name', 'Size', 'Visible', 'Visible From',
  'Uploaded At', 'Uploaded By'
];

function materialType_(type) {
  var t = str_(type).toUpperCase();
  for (var i = 0; i < MATERIALS.TYPES.length; i++) {
    if (MATERIALS.TYPES[i].id === t) return MATERIALS.TYPES[i];
  }
  return MATERIALS.TYPES[MATERIALS.TYPES.length - 1];
}

/** 'yyyy-MM-dd' from whatever the sheet holds (text or a Date). */
function dateText_(v) {
  if (v instanceof Date) return Utilities.formatDate(v, APP.TIMEZONE, 'yyyy-MM-dd');
  var s = str_(v).replace(/^'/, '');
  var m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (!m) return '';
  return m[1] + '-' + ('0' + m[2]).slice(-2) + '-' + ('0' + m[3]).slice(-2);
}

/** Copy the first version's Syllabus/TOS rows into Materials (once). */
function migrateCourseFiles_() {
  var props = PropertiesService.getScriptProperties();
  if (props.getProperty('MATERIALS_MIGRATED')) return;
  withLock_(function () {
    if (props.getProperty('MATERIALS_MIGRATED')) return;
    var old = master_().getSheetByName(MATERIALS.OLD_TAB);
    if (old && old.getLastRow() > 1) {
      var sh = tab_(MATERIALS.TAB);
      var titles = { SYLLABUS: ['SYLLABUS', 'Syllabus'], TOS_ME: ['TOS', 'TOS — Midterm Exam'], TOS_FE: ['TOS', 'TOS — Final Exam'] };
      var order = {};
      readTable_(old).rows.forEach(function (r) {
        var sid = str_(r['Section ID']);
        var t = titles[str_(r['Kind']).toUpperCase()] || ['OTHER', str_(r['Kind'])];
        order[sid] = (order[sid] || 0) + 1;
        appendObject_(sh, {
          'Material ID': uid_('MAT'), 'Section ID': sid, 'Type': t[0], 'Title': t[1],
          'Group': 'Course information', 'Order': order[sid],
          'Drive File ID': str_(r['Drive File ID']), 'File Name': str_(r['File Name']),
          'Size': num_(r['Size']) || 0, 'Visible': bool_(r['Visible']) ? 'TRUE' : 'FALSE',
          'Visible From': '', 'Uploaded At': str_(r['Uploaded At']), 'Uploaded By': str_(r['Uploaded By'])
        });
      });
    }
    props.setProperty('MATERIALS_MIGRATED', nowStamp_());
  });
}

function materialRows_() {
  migrateCourseFiles_();
  return readTable_(tab_(MATERIALS.TAB)).rows;
}

function materialOut_(r) {
  var t = materialType_(r['Type']);
  var from = dateText_(r['Visible From']);
  return {
    id: str_(r['Material ID']),
    type: t.id,
    typeLabel: t.label,
    title: str_(r['Title']) || str_(r['File Name']),
    group: str_(r['Group']),
    order: num_(r['Order']) || 0,
    name: str_(r['File Name']),
    size: num_(r['Size']) || 0,
    visible: bool_(r['Visible']),
    visibleFrom: from,
    released: bool_(r['Visible']) && (!from || from <= today_()),
    uploaded: str_(r['Uploaded At'])
  };
}

/** A class's materials in display order. Students see only released ones. */
function materialsFor_(sectionId, studentView) {
  var sid = str_(sectionId);
  return materialRows_()
    .filter(function (r) { return str_(r['Section ID']) === sid; })
    .map(materialOut_)
    .filter(function (m) { return !studentView || m.released; })
    .sort(function (a, b) { return a.order - b.order; })
    .map(function (m) {
      if (studentView) { delete m.visible; delete m.visibleFrom; delete m.released; }
      return m;
    });
}

/** The old `files` list on student.class, kept for the Android app. */
function courseFilesFor_(sectionId, studentView) {
  return materialsFor_(sectionId, studentView).map(function (m) {
    return { kind: m.id, label: m.title, name: m.name, size: m.size, uploaded: m.uploaded };
  });
}

function siblingSections_(sec) {
  return listSections_(sec.semesterId).filter(function (s) {
    return s.id !== sec.id && s.subjectId === sec.subjectId;
  }).map(function (s) { return { id: s.id, name: s.name }; });
}

function adminMaterials_(session, sectionId) {
  var sec = getSectionRecord_(sectionId);
  var list = materialsFor_(sec.id, false);
  var groups = [];
  list.forEach(function (m) { if (m.group && groups.indexOf(m.group) < 0) groups.push(m.group); });
  return {
    types: MATERIALS.TYPES,
    materials: list,
    groups: groups,
    siblings: siblingSections_(sec),
    maxBytes: MATERIALS.MAX_BYTES,
    today: today_()
  };
}

function courseFolder_(semesterId) {
  var sem = indexBy_(listSemesters_(), 'id')[semesterId] || {};
  var parent = sem.folderId ? DriveApp.getFolderById(sem.folderId) : rootFolder_();
  return folderByName_(parent, MATERIALS.FOLDER);
}

/** Trash a Drive file once no row (new or old tab) points to it any more. */
function trashIfUnused_(fileId) {
  if (!fileId) return;
  var used = materialRows_().some(function (r) { return str_(r['Drive File ID']) === fileId; });
  if (used) return;
  try { DriveApp.getFileById(fileId).setTrashed(true); } catch (err) { console.warn('trash: ' + err); }
}

/** Decode + check an uploaded PDF and save it to the semester folder. */
function savePdf_(sec, p, label) {
  var name = str_(p.name).replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'file.pdf';
  if (!/\.pdf$/i.test(name) || (p.mimeType && p.mimeType !== 'application/pdf')) {
    throw new Error('Please upload a PDF. In Word or Excel use File → Save as → PDF first.');
  }
  var bytes = Utilities.base64Decode(str_(p.data));
  if (!bytes.length) throw new Error('The file is empty.');
  if (bytes.length > MATERIALS.MAX_BYTES) throw new Error('The file is larger than 20 MB.');
  if (String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]) !== '%PDF') {
    throw new Error('That file is not a valid PDF.');
  }
  var blob = Utilities.newBlob(bytes, 'application/pdf',
    (sec.subjectCode ? sec.subjectCode + ' ' : '') + label + ' - ' + name);
  var file = courseFolder_(sec.semesterId).createFile(blob);
  return { id: file.getId(), name: name, size: bytes.length };
}

function findMaterialRow_(sh, sectionId, id) {
  var row = null;
  readTable_(sh).rows.forEach(function (r) {
    if (str_(r['Section ID']) === str_(sectionId) && str_(r['Material ID']) === str_(id)) row = r;
  });
  if (!row) throw new Error('That material is no longer there.');
  return row;
}

/**
 * p: { sectionId, type, title, group, visible, visibleFrom, name, mimeType,
 *      data (base64), alsoSectionIds: [] }
 */
function uploadMaterial_(session, p) {
  var sec = getSectionRecord_(p.sectionId);
  var type = materialType_(p.type);
  var title = str_(p.title).slice(0, 150);
  var group = str_(p.group).slice(0, 80);
  var from = dateText_(p.visibleFrom);
  var visible = p.visible === undefined ? true : !!p.visible;

  var allowed = siblingSections_(sec).map(function (s) { return s.id; });
  var targets = [sec.id];
  (p.alsoSectionIds || []).forEach(function (id) {
    if (allowed.indexOf(id) > -1 && targets.indexOf(id) < 0) targets.push(id);
  });

  var saved = savePdf_(sec, p, title || type.label);
  if (!title) title = saved.name.replace(/\.pdf$/i, '');

  return withLock_(function () {
    var sh = tab_(MATERIALS.TAB);
    var rows = readTable_(sh).rows;
    targets.forEach(function (sid) {
      var max = 0;
      rows.forEach(function (r) { if (str_(r['Section ID']) === sid) max = Math.max(max, num_(r['Order']) || 0); });
      appendObject_(sh, {
        'Material ID': uid_('MAT'), 'Section ID': sid, 'Type': type.id, 'Title': title,
        'Group': group, 'Order': max + 1, 'Drive File ID': saved.id, 'File Name': saved.name,
        'Size': saved.size, 'Visible': visible ? 'TRUE' : 'FALSE',
        'Visible From': from ? "'" + from : '', 'Uploaded At': nowStamp_(), 'Uploaded By': session.id
      });
    });
    logAction_(session.id, session.role, 'MATERIAL_UPLOAD', sec.id,
      { title: title, type: type.id, sections: targets.join(',') });
    return adminMaterials_(session, sec.id);
  });
}

/** p: { sectionId, id, title?, type?, group?, visible?, visibleFrom? } */
function updateMaterial_(session, p) {
  return withLock_(function () {
    var sh = tab_(MATERIALS.TAB);
    var row = findMaterialRow_(sh, p.sectionId, p.id);
    var patch = {};
    if (p.title !== undefined) patch['Title'] = str_(p.title).slice(0, 150) || str_(row['File Name']).replace(/\.pdf$/i, '');
    if (p.type !== undefined) patch['Type'] = materialType_(p.type).id;
    if (p.group !== undefined) patch['Group'] = str_(p.group).slice(0, 80);
    if (p.visible !== undefined) patch['Visible'] = p.visible ? 'TRUE' : 'FALSE';
    if (p.visibleFrom !== undefined) {
      var d = dateText_(p.visibleFrom);
      patch['Visible From'] = d ? "'" + d : '';
    }
    updateObject_(sh, row._row, patch);
    logAction_(session.id, session.role, 'MATERIAL_UPDATE', p.sectionId, { id: p.id, fields: Object.keys(patch).join(',') });
    return adminMaterials_(session, p.sectionId);
  });
}

/** Swap a material with its neighbour above (dir -1) or below (dir 1). */
function moveMaterial_(session, sectionId, id, dir) {
  return withLock_(function () {
    var sh = tab_(MATERIALS.TAB);
    var rows = readTable_(sh).rows
      .filter(function (r) { return str_(r['Section ID']) === str_(sectionId); })
      .sort(function (a, b) { return (num_(a['Order']) || 0) - (num_(b['Order']) || 0); });
    // renumber 1..n so gaps or duplicates never block a move
    rows.forEach(function (r, i) {
      if ((num_(r['Order']) || 0) !== i + 1) updateObject_(sh, r._row, { 'Order': i + 1 });
      r['Order'] = i + 1;
    });
    var i = -1;
    rows.forEach(function (r, k) { if (str_(r['Material ID']) === str_(id)) i = k; });
    var j = i + (Number(dir) < 0 ? -1 : 1);
    if (i > -1 && j > -1 && j < rows.length) {
      updateObject_(sh, rows[i]._row, { 'Order': j + 1 });
      updateObject_(sh, rows[j]._row, { 'Order': i + 1 });
    }
    return adminMaterials_(session, sectionId);
  });
}

/** Put a new PDF in place of the old one for this class (title etc. kept). */
function replaceMaterialFile_(session, p) {
  var sec = getSectionRecord_(p.sectionId);
  var sh0 = tab_(MATERIALS.TAB);
  var row0 = findMaterialRow_(sh0, p.sectionId, p.id);
  var saved = savePdf_(sec, p, str_(row0['Title']) || materialType_(row0['Type']).label);
  return withLock_(function () {
    var sh = tab_(MATERIALS.TAB);
    var row = findMaterialRow_(sh, p.sectionId, p.id);
    var oldId = str_(row['Drive File ID']);
    updateObject_(sh, row._row, {
      'Drive File ID': saved.id, 'File Name': saved.name, 'Size': saved.size,
      'Uploaded At': nowStamp_(), 'Uploaded By': session.id
    });
    trashIfUnused_(oldId);
    logAction_(session.id, session.role, 'MATERIAL_REPLACE', p.sectionId, { id: p.id, name: saved.name });
    return adminMaterials_(session, p.sectionId);
  });
}

function removeMaterial_(session, sectionId, id) {
  return withLock_(function () {
    var sh = tab_(MATERIALS.TAB);
    var row = findMaterialRow_(sh, sectionId, id);
    var fileId = str_(row['Drive File ID']);
    sh.deleteRow(row._row);
    trashIfUnused_(fileId);
    logAction_(session.id, session.role, 'MATERIAL_REMOVE', sectionId, { id: id, title: str_(row['Title']) });
    return adminMaterials_(session, sectionId);
  });
}

/**
 * The PDF itself, as base64. Students: enrolled + released only.
 * `id` is a Material ID (the Android app sends it as `kind`).
 */
function getMaterialFile_(session, sectionId, id) {
  var sid = str_(sectionId);
  if (session.role === 'student') {
    var me = String(session.id).toLowerCase();
    var enrolled = readTable_(tab_(TAB.ENROLLMENTS)).rows.some(function (r) {
      return str_(r['Section ID']) === sid && str_(r['Student ID']).toLowerCase() === me;
    });
    if (!enrolled) throw new Error('You are not enrolled in this class.');
  }
  var row = null;
  materialRows_().forEach(function (r) {
    if (str_(r['Section ID']) === sid && str_(r['Material ID']) === str_(id)) row = r;
  });
  var m = row ? materialOut_(row) : null;
  if (!m || (session.role === 'student' && !m.released)) throw new Error('This file is not available.');
  var blob = DriveApp.getFileById(str_(row['Drive File ID'])).getBlob();
  return {
    kind: m.id, id: m.id, label: m.title, title: m.title, name: m.name,
    mimeType: 'application/pdf', type: m.type,
    // students may save problem sets only; everything else is view-only
    downloadable: session.role !== 'student' || m.type === 'PROBLEM_SET',
    data: Utilities.base64Encode(blob.getBytes())
  };
}
