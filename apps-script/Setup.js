/**
 * ============================================================================
 *  Setup.gs  ·  One-time installation
 * ============================================================================
 *  RUN setUp() ONCE from the Apps Script editor, then deploy the web app.
 *  Everything in here is safe to run again — it never deletes your data.
 * ============================================================================
 */

/**
 * ▶ RUN ME FIRST.
 * Creates (or repairs) the master database, the folder structure and your
 * administrator account, then prints the details in the execution log.
 */
function setUp() {
  var report = [];

  // 1. Root folder -----------------------------------------------------------
  var folder = null;
  var rootId = getProp_(PROP.ROOT_FOLDER);

  if (!rootId && str_(ROOT_FOLDER)) {
    var configured = extractDriveId_(ROOT_FOLDER);
    try {
      folder = DriveApp.getFolderById(configured);
      setProp_(PROP.ROOT_FOLDER, folder.getId());
      report.push('Root folder (from Config.gs): ' + folder.getName());
      report.push('  → ' + folder.getUrl());
    } catch (err) {
      throw new Error(
        'The folder in Config.gs could not be opened:\n  ' + configured + '\n' +
        'Check that the ID is right and that this Google account can edit that folder. ' +
        'Clear ROOT_FOLDER in Config.gs to have setUp() make a folder in My Drive instead.'
      );
    }
  } else if (rootId) {
    folder = DriveApp.getFolderById(rootId);
    report.push('Root folder (already configured): ' + folder.getName());
  } else {
    folder = DriveApp.createFolder('EE — Grades Database');
    setProp_(PROP.ROOT_FOLDER, folder.getId());
    report.push('Root folder created in My Drive: ' + folder.getName());
    report.push('  → ' + folder.getUrl());
    report.push('  (Run setRootFolder("<your folder link>") to move it somewhere else.)');
  }

  // 2. Master spreadsheet ----------------------------------------------------
  var masterId = getProp_(PROP.MASTER_ID);
  var ss = null;
  if (masterId) {
    try {
      ss = SpreadsheetApp.openById(masterId);
    } catch (err) {
      ss = null;
    }
  }
  if (!ss) {
    var existing = folder.getFilesByName(APP.MASTER_FILE_NAME);
    if (existing.hasNext()) {
      ss = SpreadsheetApp.openById(existing.next().getId());
    } else {
      ss = SpreadsheetApp.create(APP.MASTER_FILE_NAME);
      moveFile_(ss.getId(), folder);
    }
    setProp_(PROP.MASTER_ID, ss.getId());
  }
  ss.setSpreadsheetTimeZone(APP.TIMEZONE);
  _masterCache = ss;
  report.push('Master database: ' + ss.getUrl());

  // 3. Tabs ------------------------------------------------------------------
  buildMasterTabs_(ss);
  report.push('Tabs ready: ' + Object.keys(TAB).map(function (k) { return TAB[k]; }).join(', '));

  // 4. Secrets ---------------------------------------------------------------
  secret_(PROP.SECRET);
  secret_(PROP.PEPPER);

  // 5. First administrator ---------------------------------------------------
  var email = Session.getEffectiveUser().getEmail();
  var adminSheet = tab_(TAB.ADMINS);
  var admin = email ? findRow_(adminSheet, 'Email', email) : null;
  var startingPassword = null;
  if (email && !admin) {
    startingPassword = 'ee-' + Math.floor(100000 + Math.random() * 899999);
    var salt = newSalt_();
    appendObject_(adminSheet, {
      'Email': email,
      'Name': 'Administrator',
      'Role': 'Admin',
      'Status': 'Active',
      'Set Password': startingPassword,
      'Password Hash': hashPassword_(startingPassword, salt),
      'Salt': salt,
      'Applied Set': fingerprint_(startingPassword),
      'Must Change': 'TRUE',
      'Last Login': '',
      'Created At': nowStamp_()
    });
    report.push('Administrator created: ' + email);
    report.push('  TEMPORARY PASSWORD: ' + startingPassword + '   (you will be asked to change it)');
  } else if (admin) {
    report.push('Administrator already exists: ' + email);
  }

  report.push('');
  report.push('NEXT: Deploy → New deployment → Web app');
  report.push('      Execute as: Me    ·    Who has access: Anyone');

  var text = report.join('\n');
  console.log('\n' + text + '\n');
  return text;
}

/**
 * Point the system at the Drive folder you want to use, e.g. one you have
 * shared with your department. Accepts a folder ID or a full Drive URL.
 * The master database is moved into it.
 */
function extractDriveId_(idOrUrl) {
  var s = String(idOrUrl || '').trim();
  var m = s.match(/[-\w]{25,}/);
  return m ? m[0] : s;
}

function setRootFolder(folderIdOrUrl) {
  var id = extractDriveId_(folderIdOrUrl);
  if (!id) throw new Error('Please pass the folder ID or its Drive link.');

  var folder = DriveApp.getFolderById(id);
  setProp_(PROP.ROOT_FOLDER, folder.getId());

  var masterId = getProp_(PROP.MASTER_ID);
  if (masterId) moveFile_(masterId, folder);

  // Re-home any semester folders that were created before the switch.
  var sh = tab_(TAB.SEMESTERS);
  readTable_(sh).rows.forEach(function (row) {
    var fid = str_(row['Folder ID']);
    if (!fid) return;
    try {
      var sub = DriveApp.getFolderById(fid);
      var parents = sub.getParents();
      var inPlace = false;
      while (parents.hasNext()) {
        if (parents.next().getId() === folder.getId()) inPlace = true;
      }
      if (!inPlace) {
        folder.addFolder(sub);
        var old = sub.getParents();
        while (old.hasNext()) {
          var p = old.next();
          if (p.getId() !== folder.getId()) p.removeFolder(sub);
        }
      }
    } catch (err) {
      console.warn('Could not move semester folder ' + fid + ': ' + err);
    }
  });

  var msg = 'Root folder is now: ' + folder.getName() + '\n' + folder.getUrl();
  console.log(msg);
  return msg;
}

/** Print the current configuration and the live web app URL. */
function showConfig() {
  var lines = [
    'App               : ' + APP.NAME + ' v' + APP.VERSION,
    'Root folder ID    : ' + (getProp_(PROP.ROOT_FOLDER) || '(not set)'),
    'Master sheet ID   : ' + (getProp_(PROP.MASTER_ID) || '(not set)')
  ];
  try {
    lines.push('Master sheet URL  : ' + master_().getUrl());
  } catch (err) { /* not set up yet */ }
  try {
    lines.push('Web app URL       : ' + ScriptApp.getService().getUrl());
  } catch (err) {
    lines.push('Web app URL       : (deploy the web app first)');
  }
  var text = lines.join('\n');
  console.log('\n' + text + '\n');
  return text;
}

/** Create every master tab and seed the default settings. */
function buildMasterTabs_(ss) {
  var order = [
    TAB.STUDENTS, TAB.SEMESTERS, TAB.SUBJECTS, TAB.SECTIONS,
    TAB.ENROLLMENTS, TAB.ADMINS, TAB.SETTINGS, TAB.SCALE, TAB.LOG
  ];

  var systemFrom = {};
  systemFrom[TAB.STUDENTS] = SCHEMA[TAB.STUDENTS].indexOf('Password Hash');
  systemFrom[TAB.ADMINS] = SCHEMA[TAB.ADMINS].indexOf('Password Hash');
  systemFrom[TAB.SECTIONS] = SCHEMA[TAB.SECTIONS].indexOf('Spreadsheet ID');
  systemFrom[TAB.SEMESTERS] = SCHEMA[TAB.SEMESTERS].indexOf('Folder ID');

  order.forEach(function (name) {
    var sh = ss.getSheetByName(name);
    if (!sh) {
      sh = createTab_(ss, name, SCHEMA[name], systemFrom[name]);
    } else {
      // Repair: add any headers introduced by a later version.
      var lastCol = Math.max(sh.getLastColumn(), 1);
      var present = sh.getRange(1, 1, 1, lastCol).getValues()[0]
        .map(function (h) { return str_(h); });
      SCHEMA[name].forEach(function (h) {
        if (present.indexOf(h) === -1) {
          sh.insertColumnAfter(sh.getMaxColumns());
          sh.getRange(1, sh.getMaxColumns()).setValue(h)
            .setBackground(BRAND.HEADER_BG).setFontColor(BRAND.HEADER_FG).setFontWeight('bold');
          present.push(h);
        }
      });
      sh.setFrozenRows(1);
    }
  });

  // Seed settings
  var setSheet = ss.getSheetByName(TAB.SETTINGS);
  var have = {};
  readTable_(setSheet).rows.forEach(function (r) { have[str_(r['Key'])] = true; });
  DEFAULT_SETTINGS.forEach(function (row) {
    if (!have[row[0]]) setSheet.appendRow(row);
  });
  setSheet.setColumnWidth(1, 200);
  setSheet.setColumnWidth(2, 260);
  setSheet.setColumnWidth(3, 520);

  // Seed grading scale
  var scaleSheet = ss.getSheetByName(TAB.SCALE);
  if (readTable_(scaleSheet).rows.length === 0) {
    scaleSheet.getRange(2, 1, DEFAULT_SCALE.length, 4).setValues(DEFAULT_SCALE);
    scaleSheet.getRange(2, 3, DEFAULT_SCALE.length, 1).setNumberFormat('0.00');
  }

  // Helpful widths and notes on the Students tab
  var stu = ss.getSheetByName(TAB.STUDENTS);
  stu.getRange(1, SCHEMA[TAB.STUDENTS].indexOf('Set Password') + 1).setNote(
    'Type a password here to set or reset this student\'s password.\n' +
    'It is applied automatically and stays visible so you can tell the student.\n' +
    'The cell clears itself once the student chooses their own password.'
  );
  stu.getRange(1, SCHEMA[TAB.STUDENTS].indexOf('Password Hash') + 1).setNote(
    'System columns — do not edit by hand.'
  );

  // Drop the default "Sheet1" if it is still empty
  var first = ss.getSheetByName('Sheet1');
  if (first && ss.getSheets().length > 1 && first.getLastRow() === 0) {
    ss.deleteSheet(first);
  }

  ss.setActiveSheet(ss.getSheetByName(TAB.STUDENTS));
  return ss;
}

/**
 * Install the background job that keeps the enrollment index in step with
 * rosters you edit directly in the section spreadsheets. Safe to run twice.
 */
function installTriggers() {
  var existing = ScriptApp.getProjectTriggers();
  var found = false;
  existing.forEach(function (t) {
    if (t.getHandlerFunction() === 'scheduledRosterSync') found = true;
  });
  if (!found) {
    ScriptApp.newTrigger('scheduledRosterSync').timeBased().everyHours(1).create();
  }
  return found ? 'Hourly roster sync was already installed.' : 'Hourly roster sync installed.';
}
