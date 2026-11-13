/**
 * ============================================================================
 *  Triggers.gs  ·  Background housekeeping
 * ============================================================================
 *  Installed by installTriggers(). Runs hourly and keeps the master database
 *  in step with edits you made directly inside the section spreadsheets.
 * ============================================================================
 */

function scheduledRosterSync() {
  var started = new Date().getTime();

  // 1. Turn any newly typed "Set Password" values into real password hashes.
  try {
    applyPendingPasswords();
  } catch (err) {
    console.warn('applyPendingPasswords: ' + err);
  }

  // 1b. E-mail starting passwords that have not been sent yet (quota permitting).
  try {
    withLock_(mailPendingPasswords_);
  } catch (err) {
    console.warn('Password e-mails: ' + err);
  }

  // 1c. Exams: score attempts whose time ran out, push released scores.
  try {
    finalizeAndSyncExams_(60 * 1000);
  } catch (err) {
    console.warn('Exams: ' + err);
  }

  // 2. Refresh enrollment counts, oldest first, inside a time budget.
  try {
    var sheet = tab_(TAB.SECTIONS);
    var rows = readTable_(sheet).rows.filter(function (r) {
      var status = str_(r['Status']).toLowerCase();
      return status !== 'deleted' && status !== 'archived' && str_(r['Spreadsheet ID']);
    });

    rows.sort(function (a, b) {
      return str_(a['Last Synced']) > str_(b['Last Synced']) ? 1 : -1;
    });

    var done = 0;
    for (var i = 0; i < rows.length; i++) {
      if ((new Date().getTime() - started) / 1000 > APP.SYNC_BUDGET_SECONDS) break;
      try {
        rebuildRoster_(str_(rows[i]['Section ID']));
        done++;
      } catch (err) {
        console.warn('Roster sync failed for ' + str_(rows[i]['Section Name']) + ': ' + err);
      }
    }
    console.log('Roster sync complete: ' + done + ' of ' + rows.length + ' classes.');
  } catch (err) {
    console.error('scheduledRosterSync: ' + err);
  }
}

/** Remove every trigger this project installed. */
function uninstallTriggers() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (t) {
    ScriptApp.deleteTrigger(t);
    removed++;
  });
  return removed + ' trigger(s) removed.';
}
