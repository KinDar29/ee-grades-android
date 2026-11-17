/**
 * ============================================================================
 *  CourseAssessment.js  ·  Course Outcome Assessment Workbook (department
 *                          template layout) + Course Assessment Data Sheet
 * ============================================================================
 *  The workbook's first tabs follow the department's template:
 *    Setup                    course info, target, COs, grading system
 *    Course Assessment Plan   tasks per CO (I/E/D, C/P/A, %), unit hours
 *    Direct Assessment        one row per student: task scores + %CO per CO
 *    Indirect Assessment      one row per student: rating + %CO per CO
 *    Final Grade              student name, number and section
 *    Survey Items             survey statements + 1-5 rating labels
 *  Everything is found by its labels, never by fixed cells, and only read.
 *
 *  readCourseWorkbook_(ss) returns the whole thing; IndirectAssessment.js uses
 *  it for the Indirect tab, and admin.cads hands it to the browser, which
 *  builds the per-section "Course Assessment Data Sheet" PDF.
 * ============================================================================
 */

var COURSE_WB = {
  CACHE_SECONDS: 300,
  TABS: {
    SETUP: 'Setup', PLAN: 'Course Assessment Plan', DIRECT: 'Direct Assessment',
    INDIRECT: 'Indirect Assessment', FINAL: 'Final Grade', ITEMS: 'Survey Items'
  },
  DEFAULT_CHAIR: 'Rigor G. Gabur',
  DEFAULT_DEAN: 'Sharona Q. Barroga'
};

/** True when the workbook uses the department-template layout. */
function isCourseTemplate_(ss) {
  var sh = ss.getSheetByName(COURSE_WB.TABS.DIRECT);
  if (!sh || !ss.getSheetByName(COURSE_WB.TABS.SETUP)) return false;
  var v = sh.getRange(1, 1, Math.min(sh.getLastRow(), 30), Math.min(sh.getLastColumn(), 6)).getDisplayValues();
  return v.some(function (r) { return str_(r[1]) === 'CO' && /^CO\d+$/i.test(str_(r[2])); });
}

function cwNum_(v) {
  var s = str_(v).replace(/[%,]/g, '');
  if (s === '') return null;
  var n = Number(s);
  return isNaN(n) ? null : n;
}

function cwValues_(ss, name) {
  var sh = ss.getSheetByName(name);
  if (!sh || sh.getLastRow() < 1) return [];
  return sh.getRange(1, 1, sh.getLastRow(), sh.getLastColumn()).getDisplayValues();
}

function cwCell_(v, r, c) { return r >= 0 && r < v.length && c < v[r].length ? str_(v[r][c]) : ''; }

/** Reads the department-template tabs. Throws a clear message when one is missing. */
function readCourseWorkbook_(ss) {
  var T = COURSE_WB.TABS;
  var out = {
    title: ss.getName(), url: ss.getUrl(), info: {}, target: 70, cos: [], components: [], ied: {},
    units: [], plan: [], direct: { cos: [], students: [] }, indirect: { students: [] },
    statements: [], scale: [], updated: nowStamp_()
  };

  // ---- Setup
  var su = cwValues_(ss, T.SETUP);
  if (!su.length) throw new Error('The workbook has no "' + T.SETUP + '" tab.');
  var label = {};
  su.forEach(function (r) {
    for (var j = 0; j < r.length - 1; j++) {
      var k = str_(r[j]).toLowerCase();
      if (k && str_(r[j + 1]) && label[k] === undefined) label[k] = str_(r[j + 1]);
    }
  });
  out.info = {
    university: label['university'] || 'Mariano Marcos State University',
    college: label['college'] || 'College of Engineering',
    department: label['department'] || '',
    courseCode: label['course code'] || '', courseTitle: label['course title'] || '',
    courseYear: label['course and year'] || '', semester: label['semester'] || '',
    schoolYear: label['school year'] || '', instructor: label['instructor'] || ''
  };
  var tgt = cwNum_(label['attainment target per student']);
  if (tgt !== null) out.target = tgt <= 1 ? tgt * 100 : tgt;
  var coHead = rowWhere_(su, function (r) { return str_(r[1]) === 'CO code'; });
  for (var i = coHead + 1; coHead >= 0 && i < su.length; i++) {
    if (!/^CO\d+$/i.test(cwCell_(su, i, 1))) { if (cwCell_(su, i, 2) === 'Total' || !cwCell_(su, i, 1)) break; continue; }
    out.cos.push({ co: cwCell_(su, i, 1).toUpperCase(), text: cwCell_(su, i, 2), weight: cwCell_(su, i, 3) });
  }
  var gHead = rowWhere_(su, function (r) { return str_(r[1]) === 'Code' && str_(r[2]) === 'Component'; });
  for (var g = gHead + 1; gHead >= 0 && g < su.length; g++) {
    var code = cwCell_(su, g, 1);
    if (!code) { if (cwCell_(su, g, 2) === 'Total') break; continue; }
    out.components.push({ code: code, name: cwCell_(su, g, 2), weight: cwCell_(su, g, 3) });
  }

  // ---- Course Assessment Plan
  var pl = cwValues_(ss, T.PLAN);
  var iHead = rowWhere_(pl, function (r) { return str_(r[3]) === 'I/E/D'; });
  for (var a = iHead + 1; iHead >= 0 && a < pl.length && cwCell_(pl, a, 1); a++) out.ied[cwCell_(pl, a, 1)] = cwCell_(pl, a, 3);
  var pHead = rowWhere_(pl, function (r) { return str_(r[1]) === 'CO' && str_(r[2]) === 'Assessment Task'; });
  for (var b = pHead + 1; pHead >= 0 && b < pl.length; b++) {
    var task = cwCell_(pl, b, 2);
    if (!task) { if (cwCell_(pl, b, 5) && !cwCell_(pl, b, 4)) break; continue; }
    out.plan.push({ co: cwCell_(pl, b, 1), task: task, cpa: cwCell_(pl, b, 3), weight: cwCell_(pl, b, 4), coTotal: cwCell_(pl, b, 5) });
  }
  pl.forEach(function (r, ri) {
    for (var j = 0; j < r.length - 1; j++) {
      if (str_(r[j]) === 'Unit' && str_(r[j + 1]) === 'CO') {
        for (var u = ri + 1; u < pl.length && /^Unit\b/i.test(cwCell_(pl, u, j)); u++) {
          out.units.push({ unit: cwCell_(pl, u, j), co: cwCell_(pl, u, j + 1), hours: cwCell_(pl, u, j + 2) });
        }
      }
    }
  });

  // ---- Final Grade: name -> student number + section
  var who = {};
  var fg = cwValues_(ss, T.FINAL);
  var fh = rowWhere_(fg, function (r) { return str_(r[0]) === 'No.'; });
  if (fh >= 0) {
    var H = fg[fh].map(function (x) { return str_(x); });
    var cn = H.indexOf('Student name'), ci = H.indexOf('Student no.'), cs = H.indexOf('Section');
    for (var f = fh + 1; f < fg.length; f++) {
      var nm = cwCell_(fg, f, cn);
      if (nm) who[studentKey_(nm)] = { id: cwCell_(fg, f, ci), section: cwCell_(fg, f, cs) };
    }
  }

  // ---- Direct Assessment
  var dv = cwValues_(ss, T.DIRECT);
  var dh = rowWhere_(dv, function (r) { return str_(r[1]) === 'CO' && /^CO\d+$/i.test(str_(r[2])); });
  if (dh < 0) throw new Error('The "' + T.DIRECT + '" tab does not have the CO header row.');
  var taskRow = rowWhere_(dv, function (r) { return str_(r[1]) === 'Assessment Task'; });
  var atRow = rowWhere_(dv, function (r) { return str_(r[1]) === '%AT'; });
  var coWRow = dh + 1;
  var head = dv[dh].map(function (x) { return str_(x); });
  var totalCol = head.indexOf('Total % CO Attainment');
  var dcols = [];
  head.forEach(function (t, j) {
    if (!/^CO\d+$/i.test(t)) return;
    var pc = head.indexOf('%' + t.toUpperCase(), j);
    var tasks = [];
    for (var k = j; k < pc; k++) {
      if (cwCell_(dv, taskRow, k)) tasks.push({ col: k, task: cwCell_(dv, taskRow, k), weight: cwCell_(dv, atRow, k) });
    }
    dcols.push({ co: t.toUpperCase(), weight: cwCell_(dv, coWRow, j), pctCol: pc, pctWeight: cwCell_(dv, atRow, pc), tasks: tasks });
  });
  out.direct.cos = dcols.map(function (c) {
    return { co: c.co, weight: c.weight, pctWeight: c.pctWeight, tasks: c.tasks.map(function (t) { return { task: t.task, weight: t.weight }; }) };
  });
  var sRow = rowWhere_(dv, function (r) { return str_(r[1]) === 'Student'; });
  for (var s = sRow + 1; sRow >= 0 && s < dv.length && cwCell_(dv, s, 1); s++) {
    var name = cwCell_(dv, s, 1), w = who[studentKey_(name)] || {};
    var st = { name: name, id: w.id || '', section: w.section || '', tasks: {}, co: {}, total: totalCol >= 0 ? cwNum_(dv[s][totalCol]) : null };
    dcols.forEach(function (c) {
      st.tasks[c.co] = c.tasks.map(function (t) { return cwNum_(dv[s][t.col]); });
      st.co[c.co] = cwNum_(dv[s][c.pctCol]);
    });
    out.direct.students.push(st);
  }

  // ---- Indirect Assessment
  var iv = cwValues_(ss, T.INDIRECT);
  var ih = rowWhere_(iv, function (r) { return str_(r[1]) === 'CO' && /^CO\d+$/i.test(str_(r[2])); });
  if (ih >= 0) {
    var ihead = iv[ih].map(function (x) { return str_(x); });
    var icos = [];
    ihead.forEach(function (t, j) {
      if (/^CO\d+$/i.test(t)) icos.push({ co: t.toUpperCase(), rCol: j, pCol: ihead.indexOf('%' + t.toUpperCase()) });
    });
    var oCol = ihead.lastIndexOf('CO');
    var isRow = rowWhere_(iv, function (r) { return str_(r[1]) === 'Student'; });
    for (var x = isRow + 1; isRow >= 0 && x < iv.length && cwCell_(iv, x, 1); x++) {
      var ist = { name: cwCell_(iv, x, 1), ratings: {}, pct: {}, total: oCol > 1 ? cwNum_(iv[x][oCol]) : null };
      icos.forEach(function (c) { ist.ratings[c.co] = cwNum_(iv[x][c.rCol]); ist.pct[c.co] = cwNum_(iv[x][c.pCol]); });
      out.indirect.students.push(ist);
    }
  }

  // ---- Survey Items: statements of the assessed COs + rating labels
  var known = {};
  out.cos.forEach(function (c) { known[c.co] = true; });
  cwValues_(ss, T.ITEMS).forEach(function (r) {
    for (var j = 0; j < r.length - 1; j++) {
      var t = str_(r[j]).toUpperCase();
      if (/^CO\d+$/.test(t) && str_(r[j + 1]).length > 15) {
        if (known[t]) out.statements.push({ co: t, text: str_(r[j + 1]) });
        return;
      }
      if (/^[1-5]$/.test(str_(r[j])) && str_(r[j + 1]) && !/^\d/.test(str_(r[j + 1])) && str_(r[j + 1]).length < 30) {
        out.scale.push({ n: Number(r[j]), label: str_(r[j + 1]) });
        return;
      }
    }
  });
  if (!out.scale.length) {
    out.scale = [[1, 'Beginning'], [2, 'Emerging'], [3, 'Capable'], [4, 'Proficient'], [5, 'Exemplary']]
      .map(function (p) { return { n: p[0], label: p[1] }; });
  }
  return out;
}

/** Cached copy of readCourseWorkbook_ for one workbook. */
function courseWorkbook_(workbookId, fresh) {
  var cache = CacheService.getScriptCache(), key = 'course:' + workbookId;
  if (!fresh) {
    var hit = cache.get(key);
    if (hit) { try { return JSON.parse(hit); } catch (err) { /* rebuild */ } }
  }
  var ss;
  try { ss = SpreadsheetApp.openById(workbookId); } catch (err) {
    throw new Error('The assessment workbook could not be opened. Check the link on the class.');
  }
  if (!isCourseTemplate_(ss)) throw new Error('This workbook does not use the department template (no CO rows on "Direct Assessment").');
  var out = readCourseWorkbook_(ss);
  var text = JSON.stringify(out);
  if (text.length < 95000) { try { cache.put(key, text, COURSE_WB.CACHE_SECONDS); } catch (err) { /* too big */ } }
  return out;
}

/** % of the listed values that reach the target (null when there are none). */
function shareAtTarget_(values, target) {
  var v = values.filter(function (x) { return x !== null && x !== undefined; });
  var at = v.filter(function (x) { return x >= target - 1e-9; }).length;
  return { at: at, of: v.length, pct: v.length ? round_(at / v.length * 100, 2) : null };
}

/**
 * Fills an Indirect-tab result object (see readIndirectWorkbook_) from the
 * template tabs: students with section, ratings, direct %CO; per-CO numbers
 * at the workbook's target.
 */
function indirectFromCourse_(course, out) {
  var T = course.target;
  var direct = {};
  course.direct.students.forEach(function (s) { direct[studentKey_(s.name)] = s; });
  out.target = T;
  out.students = course.indirect.students.map(function (s) {
    var d = direct[studentKey_(s.name)] || { co: {}, total: null, section: '', id: '' };
    var any = Object.keys(s.ratings).some(function (k) { return s.ratings[k] !== null; });
    return {
      name: s.name, id: d.id, section: d.section, submitted: any,
      ratings: s.ratings, pct: s.pct, overall: s.total,
      direct: d.co, directOverall: d.total
    };
  });
  var done = out.students.filter(function (s) { return s.submitted; });
  var avg = function (list) {
    var v = list.filter(function (x) { return x !== null && x !== undefined; });
    return v.length ? v.reduce(function (a, b) { return a + b; }, 0) / v.length : null;
  };
  out.cos = course.cos.map(function (c) {
    var ind = shareAtTarget_(done.map(function (s) { return s.pct[c.co]; }), T);
    var dir = shareAtTarget_(course.direct.students.map(function (s) { return s.co[c.co]; }), T);
    var ar = avg(done.map(function (s) { return s.ratings[c.co]; }));
    return {
      co: c.co, description: c.text, weight: cwNum_(c.weight),
      averageRating: ar === null ? null : round_(ar, 2),
      averagePercent: avg(done.map(function (s) { return s.pct[c.co]; })),
      atLeast70: ind.at, atTarget: ind.at, answered: ind.of,
      indirect: ind.pct, direct: dir.pct, directAt: dir.at, directOf: dir.of,
      gap: ind.pct !== null && dir.pct !== null ? round_(ind.pct - dir.pct, 1) : null
    };
  });
  var oi = shareAtTarget_(done.map(function (s) { return s.overall; }), T);
  var od = shareAtTarget_(course.direct.students.map(function (s) { return s.total; }), T);
  out.overall = { atLeast70: oi.at, atTarget: oi.at, answered: oi.of, indirect: oi.pct, direct: od.pct };
  return out;
}

/* ------------------------- Course Assessment Data Sheet ------------------------- */

/** Everything the browser needs to build one section's data sheet. */
function courseAssessmentSheet_(session, sectionId, fresh) {
  var sec = listSections_('').filter(function (s) { return s.id === str_(sectionId); })[0];
  if (!sec) throw new Error('That class no longer exists.');
  if (!sec.workbookId) throw new Error('This class has no course outcome assessment workbook linked. Add it in Edit class.');
  var course = courseWorkbook_(sec.workbookId, fresh === true);
  var key = sectionKey_(sec.name);
  var mine = course.direct.students.filter(function (s) { return sectionKey_(s.section) === key; });
  if (!mine.length) throw new Error('No student of ' + sec.name + ' was found on the workbook\'s Final Grade tab.');
  var s = settings_();
  logAction_(session.id, session.role, 'CADS_DOWNLOAD', sec.id, { workbook: sec.workbookId });
  return {
    course: course,
    section: { id: sec.id, name: sec.name, key: key, subjectCode: sec.subjectCode, subjectTitle: sec.subjectTitle,
      semesterName: sec.semesterName },
    signatories: {
      prepared: course.info.instructor || sec.instructor,
      chair: str_(s['SUMMARY_CHAIR_NAME']) || COURSE_WB.DEFAULT_CHAIR,
      dean: str_(s['SUMMARY_DEAN_NAME']) || COURSE_WB.DEFAULT_DEAN
    },
    assets: typeof CADS_ASSETS === 'object' ? CADS_ASSETS : {}
  };
}
