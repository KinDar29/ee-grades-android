/**
 * ============================================================================
 *  DirectAssessment.js  ·  Admin dashboard of exams and all requirements
 * ============================================================================
 *  Read-only. For every class in a semester it returns the requirements
 *  (exams, quizzes, labs, …) and each student's raw scores, percent, grade and
 *  standing, so the page can chart one section or all sections together
 *  without asking the server again.
 *
 *  Each class is cached for 5 minutes ("direct:<sectionId>"). The cache is
 *  cleared with the score snapshot whenever scores, requirements, enrolment
 *  or a final status change through the portal (clearSectionSnapshot_), and
 *  by the ↻ Refresh button. Edits typed straight into a class sheet show up
 *  within 5 minutes.
 * ============================================================================
 */

var DIRECT = { CACHE_SECONDS: 300 };

function directKey_(sectionId) {
  return 'direct:' + sectionId;
}

/** One class: requirements + per-student scores (in requirement order). */
function directSection_(sec, options) {
  var cache = CacheService.getScriptCache();
  var hit = cache.get(directKey_(sec.id));
  if (hit) {
    try { return JSON.parse(hit); } catch (err) { /* rebuild */ }
  }

  var data = readSectionData_(sec.id, true);
  var reqs = data.assessments.filter(function (a) { return a.max > 0; });
  var out = {
    id: sec.id,
    name: sec.name,
    subjectCode: sec.subjectCode || '',
    subjectTitle: sec.subjectTitle || '',
    assessments: reqs.map(function (a) {
      return { id: a.id, name: a.name, type: a.type || 'Other', max: a.max, weight: a.weight, date: a.date, published: a.published };
    }),
    students: data.roster.map(function (r) {
      var g = computeGrade_(data.assessments, r.scores, options);
      return {
        id: r.studentId,
        name: [r.lastName, r.firstName].filter(function (x) { return x; }).join(', ') || r.studentId,
        percent: g.percent,
        grade: g.grade,
        standing: standingOf_(g, r.finalStatus),
        scores: reqs.map(function (a) {
          var v = r.scores[a.id];
          return v === null || v === undefined || isNaN(v) ? null : Number(v);
        })
      };
    })
  };

  var text = JSON.stringify(out);
  if (text.length < 90000) {
    try { cache.put(directKey_(sec.id), text, DIRECT.CACHE_SECONDS); } catch (err) { /* too big */ }
  }
  return out;
}

/** Lowest percentage that still earns a passing grade (3.00 or better). */
function passingPercent_() {
  var min = null;
  gradeScale_().forEach(function (b) {
    if (b.grade !== null && b.grade <= 3 && b.min !== null && (min === null || b.min < min)) min = b.min;
  });
  return min === null ? 75 : min;
}

function directDashboard_(session, semesterId, refresh) {
  var s = settings_();
  var semId = str_(semesterId) || str_(s['ACTIVE_SEMESTER_ID']);
  var options = { countBlankAsZero: bool_(s['COUNT_BLANK_AS_ZERO']) };
  var cache = CacheService.getScriptCache();

  var sections = listSections_(semId).map(function (sec) {
    if (refresh) cache.remove(directKey_(sec.id));
    try {
      return directSection_(sec, options);
    } catch (err) {
      return {
        id: sec.id, name: sec.name, subjectCode: sec.subjectCode || '', subjectTitle: sec.subjectTitle || '',
        assessments: [], students: [], error: err.message
      };
    }
  });

  return {
    semesterId: semId,
    passingPercent: passingPercent_(),
    sections: sections,
    checkedAt: nowStamp_()
  };
}
