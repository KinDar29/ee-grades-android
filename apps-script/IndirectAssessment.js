/**
 * ============================================================================
 *  IndirectAssessment.js  ·  Course-outcome self-assessment survey
 * ============================================================================
 *  The survey itself is a Google Form, and all the calculations live in the
 *  "Course Outcome Assessment Workbook" that the form feeds. The portal only
 *  reads that workbook — it never writes to it — so the dashboard always shows
 *  exactly what the workbook's own formulas say.
 *
 *  A class is linked by two columns on the Sections tab (also editable in the
 *  admin "Edit class" dialog):
 *    Survey Link          the form's student link (shown to students)
 *    Assessment Workbook  the workbook's file ID or link (read for results)
 *
 *  Tabs read from the workbook, located by their labels, not fixed cells:
 *    Indirect Assessment  class summary + one row per student
 *    Survey Responses     raw form answers (statement averages, who answered)
 *    Comparison           direct vs indirect attainment per CO
 *    Survey Items         the form's student link
 * ============================================================================
 */

var INDIRECT = {
  CACHE_SECONDS: 300,
  TABS: {
    RESULTS: 'Indirect Assessment',
    RESPONSES: 'Survey Responses',
    COMPARISON: 'Comparison',
    ITEMS: 'Survey Items'
  }
};

/* -------------------------------- helpers -------------------------------- */

function rowWhere_(values, test) {
  for (var i = 0; i < values.length; i++) if (test(values[i])) return i;
  return -1;
}

/** 0.03, "3%", "3" -> 3 (percent). Blank -> null. */
function pct_(v) {
  if (v === '' || v === null || v === undefined) return null;
  if (typeof v === 'number') return v <= 1 && v >= 0 ? round_(v * 100, 1) : round_(v, 1);
  var s = String(v).trim();
  if (!s) return null;
  var n = parseFloat(s.replace('%', ''));
  return isNaN(n) ? null : round_(n, 1);
}

/** Section names compared without spaces, dashes or case ("BSEE 2-A" = "bsee2a"). */
function sectionKey_(v) {
  return str_(v).toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function studentKey_(v) {
  return str_(v).toLowerCase().replace(/\s+/g, '');
}

/** "5", "5 - Very well", "Very well" -> 5 (using the workbook's rating labels). */
function rating_(v, labels) {
  if (typeof v === 'number') return v >= 1 && v <= 5 ? v : null;
  var s = str_(v);
  if (!s) return null;
  var m = s.match(/[1-5]/);
  if (m) return Number(m[0]);
  var hit = labels[s.toLowerCase()];
  return hit === undefined ? null : hit;
}

function workbookId_(v) {
  var s = str_(v);
  if (!s) return '';
  var m = s.match(/[-\w]{25,}/);
  return m ? m[0] : s;
}

/* ----------------------------- read a workbook --------------------------- */

function readIndirectWorkbook_(workbookId) {
  var cache = CacheService.getScriptCache();
  var key = 'indirect:' + workbookId;
  var hit = cache.get(key);
  if (hit) {
    try { return JSON.parse(hit); } catch (err) { /* rebuild */ }
  }

  var ss;
  try {
    ss = SpreadsheetApp.openById(workbookId);
  } catch (err) {
    throw new Error('The assessment workbook could not be opened. Check the link on the class.');
  }
  var out = {
    title: ss.getName(), url: ss.getUrl(), formUrl: '',
    cos: [], overall: null, students: [], statements: [], responders: [], responses: 0,
    updated: nowStamp_()
  };

  // ---- Survey Items: the student link and the rating labels
  var labels = {};
  var items = ss.getSheetByName(INDIRECT.TABS.ITEMS);
  if (items) {
    var iv = items.getDataRange().getDisplayValues();
    iv.forEach(function (r) {
      for (var j = 0; j < r.length - 1; j++) {
        if (/^student link/i.test(str_(r[j])) && /^https?:\/\//.test(str_(r[j + 1]))) out.formUrl = str_(r[j + 1]);
      }
      // rating rows look like: "", "3", "With some help"
      for (var k = 0; k < r.length - 1; k++) {
        if (/^[1-5]$/.test(str_(r[k])) && str_(r[k + 1]) && !/^\d/.test(str_(r[k + 1]))) {
          labels[str_(r[k + 1]).toLowerCase()] = Number(r[k]);
          break;
        }
      }
    });
  }

  // ---- Indirect Assessment: summary + students
  var res = ss.getSheetByName(INDIRECT.TABS.RESULTS);
  if (!res) throw new Error('The workbook has no "' + INDIRECT.TABS.RESULTS + '" tab.');
  var v = res.getDataRange().getValues();
  var mRow = rowWhere_(v, function (r) { return str_(r[1]) === 'Measure'; });
  var atRow = rowWhere_(v, function (r) { return /^students rating themselves at least/i.test(str_(r[1])); });
  var ansRow = rowWhere_(v, function (r) { return /^students who answered/i.test(str_(r[1])); });
  if (mRow < 0 || atRow < 0 || ansRow < 0) throw new Error('The "' + INDIRECT.TABS.RESULTS + '" tab does not have the expected class summary.');
  var coCols = [], overallCol = -1;
  v[mRow].forEach(function (c, j) {
    var t = str_(c);
    if (/^CO\d+$/i.test(t)) coCols.push({ co: t.toUpperCase(), col: j });
    if (/^overall$/i.test(t)) overallCol = j;
  });

  var h = rowWhere_(v, function (r) { return str_(r[0]) === 'No.'; });
  var H = h < 0 ? [] : v[h].map(function (x) { return str_(x); });
  var nameCol = H.indexOf('Student name'), secCol = H.indexOf('Section');
  var subCol = H.indexOf('Survey submitted');
  var overallPctCol = rowWhere_(H.map(function (x) { return [x]; }), function (r) { return /^overall %/i.test(r[0]); });
  var ratingCol = {}, pctCol = {};
  coCols.forEach(function (c) {
    ratingCol[c.co] = H.indexOf(c.co);
    pctCol[c.co] = H.indexOf('% ' + c.co);
  });

  var weights = {};
  if (h >= 0) {
    for (var i = h + 1; i < v.length; i++) {
      var row = v[i];
      var name = nameCol >= 0 ? str_(row[nameCol]) : '';
      if (!name) continue;
      if (/^co weight$/i.test(name)) {
        coCols.forEach(function (c) { weights[c.co] = pct_(row[pctCol[c.co]]); });
        continue;
      }
      var sub = subCol >= 0 ? str_(row[subCol]) : '';
      var st = {
        name: name,
        section: secCol >= 0 ? str_(row[secCol]) : '',
        submitted: !!sub && !/^no\b|not yet|no response/i.test(sub),
        ratings: {}, pct: {},
        overall: overallPctCol >= 0 ? pct_(row[overallPctCol]) : null
      };
      coCols.forEach(function (c) {
        st.ratings[c.co] = num_(row[ratingCol[c.co]]);
        st.pct[c.co] = pct_(row[pctCol[c.co]]);
      });
      out.students.push(st);
    }
  }

  // ---- Survey Responses: who answered (latest per student) and statement averages
  var coDesc = {};
  var resp = ss.getSheetByName(INDIRECT.TABS.RESPONSES);
  if (resp && resp.getLastRow() >= 1) {
    var rv = resp.getDataRange().getValues();
    var head = rv[0].map(function (x) { return str_(x); });
    var numCol = rowWhere_(head.map(function (x) { return [x]; }), function (r) { return /student number/i.test(r[0]); });
    var rSecCol = head.indexOf('Section');
    var stmts = [];
    head.forEach(function (t, j) {
      var m = t.match(/^(CO\d+)\s*:\s*([\s\S]*?)\s*\[([\s\S]*)\]\s*$/i);
      if (m) {
        stmts.push({ col: j, co: m[1].toUpperCase(), text: m[3] });
        coDesc[m[1].toUpperCase()] = m[2];
      }
    });
    var latest = {};
    for (var r = 1; r < rv.length; r++) {
      var id = numCol >= 0 ? studentKey_(rv[r][numCol]) : '';
      if (!id) continue;
      var ts = rv[r][0] instanceof Date ? rv[r][0].getTime() : r;
      if (!latest[id] || ts >= latest[id].ts) latest[id] = { ts: ts, row: rv[r] };
    }
    out.responders = Object.keys(latest);
    out.responses = out.responders.length;
    // Section of each responder, from the form's "Section" answer.
    var responderSection = {};
    out.responders.forEach(function (id) {
      responderSection[id] = rSecCol >= 0 ? sectionKey_(latest[id].row[rSecCol]) : '';
    });
    // dist[CO][sectionKey or '*'] = [count of 1s, 2s, 3s, 4s, 5s]
    var dist = {};
    var bump = function (co, sec, x) {
      var d = dist[co] = dist[co] || {};
      [sec, '*'].forEach(function (k) {
        if (!k) return;
        var a = d[k] = d[k] || [0, 0, 0, 0, 0];
        a[x - 1]++;
      });
    };
    out.statements = stmts.map(function (s) {
      var sum = 0, n = 0, bySec = {};
      out.responders.forEach(function (id) {
        var x = rating_(latest[id].row[s.col], labels);
        if (x === null) return;
        sum += x; n++;
        var r5 = Math.max(1, Math.min(5, Math.round(x)));
        var sec = responderSection[id];
        bump(s.co, sec, r5);
        if (sec) {
          var b = bySec[sec] = bySec[sec] || { sum: 0, n: 0 };
          b.sum += x; b.n++;
        }
      });
      var bySection = {};
      Object.keys(bySec).forEach(function (k) {
        bySection[k] = { average: round_(bySec[k].sum / bySec[k].n, 2), answered: bySec[k].n };
      });
      return { co: s.co, text: s.text, average: n ? round_(sum / n, 2) : null, answered: n, bySection: bySection };
    });
    out.ratingDist = dist;
    out.ratingLabels = {};
    Object.keys(labels).forEach(function (t) { out.ratingLabels[labels[t]] = t.charAt(0).toUpperCase() + t.slice(1); });
  }

  // ---- Comparison: direct vs indirect per CO
  var comparison = {};
  var cmp = ss.getSheetByName(INDIRECT.TABS.COMPARISON);
  if (cmp) {
    var cv = cmp.getDataRange().getValues();
    var ch = rowWhere_(cv, function (r) { return /^course outcome$/i.test(str_(r[0])); });
    if (ch >= 0) {
      for (var k = ch + 1; k < cv.length && str_(cv[k][0]); k++) {
        comparison[str_(cv[k][0]).toUpperCase()] = { direct: pct_(cv[k][1]), indirect: pct_(cv[k][2]) };
      }
    }
  }

  // ---- assemble per CO
  out.cos = coCols.map(function (c) {
    var ratings = out.students.map(function (s) { return s.ratings[c.co]; }).filter(function (x) { return x !== null; });
    var pcts = out.students.map(function (s) { return s.pct[c.co]; }).filter(function (x) { return x !== null; });
    var at = num_(v[atRow][c.col]) || 0, ans = num_(v[ansRow][c.col]) || 0;
    var cm = comparison[c.co] || {};
    var indirect = cm.indirect !== null && cm.indirect !== undefined ? cm.indirect : (ans ? round_(at / ans * 100, 1) : null);
    return {
      co: c.co,
      description: coDesc[c.co] || '',
      weight: weights[c.co] === undefined ? null : weights[c.co],
      averageRating: ratings.length ? round_(ratings.reduce(function (a, b) { return a + b; }, 0) / ratings.length, 2) : null,
      averagePercent: pcts.length ? round_(pcts.reduce(function (a, b) { return a + b; }, 0) / pcts.length, 1) : null,
      atLeast70: at,
      answered: ans,
      indirect: indirect,
      direct: cm.direct === undefined ? null : cm.direct,
      gap: indirect !== null && cm.direct !== null && cm.direct !== undefined ? round_(indirect - cm.direct, 1) : null
    };
  });
  var oAt = overallCol >= 0 ? num_(v[atRow][overallCol]) || 0 : 0;
  var oAns = overallCol >= 0 ? num_(v[ansRow][overallCol]) || 0 : 0;
  var oc = comparison.OVERALL || {};
  out.overall = {
    atLeast70: oAt, answered: oAns,
    indirect: oc.indirect !== null && oc.indirect !== undefined ? oc.indirect : (oAns ? round_(oAt / oAns * 100, 1) : null),
    direct: oc.direct === undefined ? null : oc.direct
  };

  var text = JSON.stringify(out);
  if (text.length < 90000) {
    try { cache.put(key, text, INDIRECT.CACHE_SECONDS); } catch (err) { /* too big */ }
  }
  return out;
}

/* --------------------------------- admin --------------------------------- */

/** Dashboard data: one block per linked workbook in the semester. */
function indirectDashboard_(session, semesterId) {
  var s = settings_();
  var semId = str_(semesterId) || str_(s['ACTIVE_SEMESTER_ID']);
  var sections = listSections_(semId).filter(function (x) { return x.workbookId || x.surveyUrl; });

  var enrolled = {};
  readTable_(tab_(TAB.ENROLLMENTS)).rows.forEach(function (r) {
    var sid = str_(r['Section ID']);
    (enrolled[sid] = enrolled[sid] || []).push(studentKey_(r['Student ID']));
  });

  var groups = {}, order = [];
  sections.forEach(function (sec) {
    var k = sec.workbookId || ('form:' + sec.surveyUrl);
    if (!groups[k]) { groups[k] = { workbookId: sec.workbookId, surveyUrl: sec.surveyUrl, sections: [] }; order.push(k); }
    groups[k].sections.push(sec);
  });

  var blocks = order.map(function (k) {
    var g = groups[k];
    var block = {
      subject: g.sections[0].subjectCode + (g.sections[0].subjectTitle ? ' — ' + g.sections[0].subjectTitle : ''),
      surveyUrl: g.surveyUrl, workbookUrl: '', error: '', data: null, sections: []
    };
    var responders = {};
    if (g.workbookId) {
      try {
        block.data = readIndirectWorkbook_(g.workbookId);
        block.workbookUrl = block.data.url;
        if (!block.surveyUrl) block.surveyUrl = block.data.formUrl;
        block.data.responders.forEach(function (id) { responders[id] = true; });
        delete block.data.responders;
      } catch (err) {
        block.error = err.message;
      }
    } else {
      block.error = 'No assessment workbook is linked, so only the survey link is shown.';
    }
    block.sections = g.sections.map(function (sec) {
      var ids = enrolled[sec.id] || [];
      var answered = ids.filter(function (id) { return responders[id]; }).length;
      return { id: sec.id, key: sectionKey_(sec.name), name: sec.name, enrolled: ids.length, answered: answered };
    });
    return block;
  });

  return { semesterId: semId, blocks: blocks, checkedAt: nowStamp_() };
}

/** Clears the cached copy so "Refresh" on the dashboard reads the workbook again. */
function refreshIndirect_(session, semesterId) {
  var cache = CacheService.getScriptCache();
  listSections_(str_(semesterId) || str_(settings_()['ACTIVE_SEMESTER_ID'])).forEach(function (sec) {
    if (sec.workbookId) cache.remove('indirect:' + sec.workbookId);
  });
  return indirectDashboard_(session, semesterId);
}

/* -------------------------------- student -------------------------------- */

/** { url, submitted } for a student's class, or null when no survey is linked. */
function surveyForStudent_(section, studentId) {
  var url = str_(section.surveyUrl);
  var submitted = null;
  if (section.workbookId) {
    try {
      var wb = readIndirectWorkbook_(section.workbookId);
      if (!url) url = wb.formUrl;
      submitted = wb.responders.indexOf(studentKey_(studentId)) > -1;
    } catch (err) {
      console.warn('survey status: ' + err);
    }
  }
  if (!/^https:\/\//i.test(url)) return null;
  return { url: url, submitted: submitted };
}

/** ▶ Run from the editor to check the dashboard data for the active semester. */
function checkIndirectAssessment() {
  var r = indirectDashboard_({ id: 'editor', role: 'admin' }, '');
  if (!r.blocks.length) console.log('No class in the active semester has a survey or workbook linked.');
  r.blocks.forEach(function (b) {
    console.log(b.subject + (b.error ? '  !! ' + b.error : ''));
    console.log('  form: ' + (b.surveyUrl || '-') + '   workbook: ' + (b.workbookUrl || '-'));
    b.sections.forEach(function (s) { console.log('  ' + s.name + ': ' + s.answered + ' / ' + s.enrolled + ' answered'); });
    if (b.data) {
      b.data.cos.forEach(function (c) {
        console.log('  ' + c.co + ': avg ' + (c.averageRating === null ? '-' : c.averageRating) + '/5, indirect ' +
          (c.indirect === null ? '-' : c.indirect + '%') + ', direct ' + (c.direct === null ? '-' : c.direct + '%'));
      });
      console.log('  students listed: ' + b.data.students.length + ', responses: ' + b.data.responses +
        ', statements: ' + b.data.statements.length);
    }
  });
}
