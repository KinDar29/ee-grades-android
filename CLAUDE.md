# EE Grades — maintenance notes

This repository holds the whole EE Grades system for the MMSU Department of
Electrical Engineering:

| Part | Where | What it is |
|---|---|---|
| Web portal + API | `apps-script/` (mirror) — live copy is the Apps Script project **EE Grades Portal** | Students and admins sign in, view and edit grades. Also the HTTPS API the app uses. |
| Data | Google Sheets in the portal owner's Drive | A master database spreadsheet plus one spreadsheet per class section |
| Android app | `app/` | Kotlin + Jetpack Compose client that calls the same API |

Read this file before changing anything. Keep it up to date: add a line to the
change log at the bottom for every change you ship.

---

## Key identifiers

- Apps Script project ID: `1tHbbq3RrMrHO_pfw-Cnw6aZ9fGqOkigEHYDjVxa88ofDgDZ2VtFQkqhh`
  (editor: `https://script.google.com/home/projects/<id>/edit`)
- Web app deployment ID: `AKfycbwFo_Td8UgilzperCrImkplXfR6LQ3fCRavg-HW0BkHxoYbf6erPTWA9B2iHzT_FPc`
  — `/exec` URL is in `gradle.properties` (`EE_API_URL`). **Always update this
  deployment in place** (Manage deployments → edit → New version). Creating a new
  deployment changes the URL and breaks every installed app.
- Root Drive folder: `ROOT_FOLDER` in `apps-script/Config.js`. The master
  spreadsheet ID and the token/pepper secrets live in **Script Properties**, not
  in code. Never put secrets in this repo — it is public.
- Android app ID: `ph.edu.mmsu.ee.grades` (test builds: `ph.edu.mmsu.ee.grades.debug`)

---

## How the portal is built (apps-script/)

- `Api.js` — `doGet` serves the page; `api(request)` is the single router for
  every action (`login`, `student.*`, `admin.*`, …). Errors starting `AUTH:` sign
  the client out; `MUST_CHANGE:` sends it to the change-password screen.
- `Mobile.js` — `doPost`, the HTTPS endpoint for the app. Hands the body to
  `api()`. Students get 168-hour tokens on mobile; admins keep 8 hours.
- `Auth.js` — salted+peppered iterated SHA-256 password hashes, HMAC-signed
  tokens, login throttling, password change/reset, **password e-mails**
  (`sendPendingPasswordEmails_`), **forgot password** (`requestPasswordReset_`,
  `completePasswordReset_`, codes in CacheService), `resetSharedPasswords()`.
- `Master.js` — semesters, subjects, sections, student registry, imports,
  enrollment index.
- `Sections.js` — per-section spreadsheets (Info / Grades / Assessments /
  Summary), assessment syncing, grade computation (`computeGrade_`).
- New-score notifications: `student.updates` (`studentUpdates_` in `Api.js`)
  returns every published score a student has; `sectionSnapshot_` in
  `Sections.js` caches each class's scores for 10 min and is cleared whenever
  scores or requirements change through the portal. The server keeps no "seen"
  state — the browser (localStorage `ee.seen.<id>`) and the app (DataStore
  `scores.notified`) compare against what they showed last. First check only
  records a baseline.
- `SummaryOfGrades.js` — admin-only VPAA-TEMP-004 "Summary of Grades" (.docx):
  ONE file per semester listing every subject taught that semester (one row per
  faculty + subject + course + year; sections combined), rebuilt with every
  class record export. Fills the university's own
  template from `Templates/` in the root Drive folder by editing only
  `word/document.xml` (semester line, data rows, signature names) — never
  change the template's layout. Chair/Dean names: Settings
  `SUMMARY_CHAIR_NAME` / `SUMMARY_DEAN_NAME` (blank keeps the template's).
- `IndirectAssessment.js` — course-outcome self-assessment survey. Each class
  can have a "Survey Link" (Google Form, shown to students as "Answer the
  survey" with a ✓ once their student number appears in the responses) and an
  "Assessment Workbook" (Sections tab columns, editable in Edit class). The
  admin "Indirect assessment" tab READS the workbook (never writes) — tabs
  Indirect Assessment, Survey Responses, Comparison, Survey Items, found by
  their labels — cached 5 min (↻ Refresh clears it). `checkIndirectAssessment()`
  logs the same data from the editor.
- `DirectAssessment.js` — admin "Direct assessment" tab (`admin.direct`,
  `admin.direct.refresh`): every class's requirements + per-student scores,
  percent, grade and standing for a semester, cached 5 min per class
  (`direct:<sectionId>`, cleared by `clearSectionSnapshot_`, which every portal
  score/requirement/enrolment/final-status change calls). Charts are drawn in
  the browser (`App.html` chart helpers: `donut`, `columns`, `stack`,
  `rangeBar` — plain HTML/SVG, theme colours, no libraries). The section
  dropdown (`S.dashSection`, "All sections · all students" = '') is shared by
  the Direct and Indirect tabs; the indirect one filters by section name
  (`sectionKey_`: letters/digits only) and the workbook's per-section data
  (`statements[].bySection`, `ratingDist`). Direct CO attainment comes only
  class-wide from the workbook's Comparison tab.
- `CourseFiles.js` — Syllabus / TOS (Midterm Exam) / TOS (Final Exam) PDFs
  per class. Admin: "📄 Course files" on the class page (upload ≤ 10 MB PDF,
  optionally to every section of the same subject that semester; replace;
  "Shown to students" toggle; remove). Files live in the semester's Drive
  folder → "Course Files" and are NOT shared — students get the bytes through
  `student.file` (enrolled + visible only) and the page draws them with pdf.js
  4.10.38 from cdnjs (falls back to Download). Master tab `CourseFiles`, one
  row per class + kind; a Drive file is trashed when no row uses it.
  `student.class` returns `files` (visible only).
- Standings (`standingOf_` in `Sections.js`), shared by the class record and
  the summary: PASSED, CONDITIONAL (4.00), FAILED, INC, DROPPED, NONE (no
  scores). INC/Dropped are set per student in the admin gradebook and stored in
  each class sheet's "Final Status" tab.
- `Export.js` — printable PDF class record. `Setup.js` — `setUp()`,
  `installTriggers()`. `Triggers.js` — hourly `scheduledRosterSync`.
- `Utils.js` — sheet helpers, `withLock_` (re-entrant script lock), settings,
  grade scale. `Index.html` / `Styles.html` / `App.html` — the web UI.

Rules that matter:
- Writes to the master sheet go through `withLock_`. `rebuildRoster_` always
  takes the lock (the enrollment index is rewritten as a whole).
- Students tab columns are found **by header name**. New columns added to
  `SCHEMA` are created automatically by `ensureColumns_` on first use.
- Passwords: never reintroduce a shared default. New accounts get
  `randomPassword_()`; admins never get an automatic password.
- All HTML built in `App.html` must go through `esc()`.

## Deploying a portal change

1. Edit in the Apps Script editor (or here, then push with
   `clasp push` from `apps-script/` — needs `clasp login` as the owner).
2. Save, then Deploy → Manage deployments → edit the existing deployment →
   **New version** → Deploy.
3. Open the `/exec` URL and confirm the sign-in page loads.
4. Mirror the code back into `apps-script/` and commit it. Export without the
   editor: Drive API export of the project with MIME
   `application/vnd.google-apps.script+json` returns every file as JSON
   (`files[].name/type/source`).
5. If a change needs a new Google permission (e.g. MailApp), the owner must run
   any function once in the editor and approve, or the web app fails.

## Android app notes

- `notify/ScoreCheckWorker` runs every 2 h (WorkManager, needs network) for a
  signed-in student, calls `Repository.findNewScores()` and posts one
  notification via `notify/ScoreNotifier` (lock screen shows no details).
  Opening a class marks its scores as seen. Students are asked once for the
  Android 13+ notification permission (`NewScoreNotificationsSetup` in AppRoot).
- Class page shows a "Course outcome self-assessment" card when `student.class`
  returns `survey` (opens the Google Form in the browser; ✓ when submitted).
- Admin roster rows show the `standing` label for INC / Dropped / Conditional /
  Failed. The admin area in the app stays read-only (no editing, exports,
  Summary of Grades or indirect dashboard — those are portal-only).
- Class page shows "Course files" (from `student.class` `files`); View downloads
  through `student.file` into `cacheDir/course-files/` and opens the phone's
  PDF viewer via the FileProvider `${applicationId}.files`.

## Releasing the Android app

- Every push builds a **test** APK (artifact `ee-grades-debug-apk`, app ID
  `.debug`, installs next to the real app).
- To release: bump `versionCode` and `versionName` in `app/build.gradle.kts`
  and merge to `main`. The workflow sees there is no Release `v<versionName>`
  yet, builds the APK signed with the permanent key, and publishes the Release
  (creating the tag). Pushing a tag `v<versionName>` also works. Claude's
  sessions can't push tags (the session proxy blocks it) — merging is the way.
- Students always download from
  `https://github.com/KinDar29/ee-grades-android/releases/latest/download/ee-grades.apk`
  and updates install over the old app.
- Signing secrets (repo Settings → Secrets → Actions): `EE_KEYSTORE_BASE64`,
  `EE_KEYSTORE_PASSWORD`, `EE_KEY_ALIAS`. The key backup is kept privately by the
  portal owner — **never commit a keystore**. Losing the key means every student
  must uninstall and reinstall.
- Minification is off for release on purpose: the release is the same code that
  was tested as a debug build.

## Health checks

A weekly scheduled task checks that the portal loads, the latest build passed,
and the AuditLog for errors. If you are that task, see its prompt for the steps.

## Open decisions (ask the owner before changing)

- `computeGrade_` rounds the percentage to a whole number **before**
  transmuting, so 74.5% → 75% → 3.00 Passing.
- New columns typed directly into a Grades sheet are published to students
  immediately with max score 100.
- The `DEFAULT_PASSWORD` setting no longer affects new accounts; it is only used
  to recognise accounts still on the old shared password.
- `ALLOWALL` framing was removed, so the portal can't be embedded in other sites
  (e.g. Google Sites). Restore one line in `doGet` if that is ever needed.
- Students who changed their password before 2026-11-05 have no e-mail on file,
  so forgot-password can't reach them until their e-mails are imported
  (import with E-mail as the 7th column; passwords are not touched).

## Change log

- **2026-11-05** — Portal (deployment version 12): unique random starting
  passwords; admins never get a default; server-side "must change password";
  mobile admin tokens 8 h; locked roster rebuilds; same error for unknown account
  and wrong password; removed `ALLOWALL`; `resetSharedPasswords()`; student
  e-mail column and automatic password e-mails; forgot password (6-digit e-mailed
  code). App: forgot-password screen. Repo: portal code mirrored in
  `apps-script/`; permanent signing + tagged GitHub Releases; this file.
- **2026-11-06** — Fixed Students header (A1 had been renamed, which broke
  every login) and added warning protection on the Students/Admins header rows.
  New-score notifications (portal deployment version 13): `student.updates` + cached class snapshots +
  "New" badges on the student pages; app 1.2.0 checks every 2 h and posts a
  phone notification. First app release will be `v1.2.0` (1.1.0 was never
  released).
- **2026-11-07** — Releases now publish automatically when `main` gets a
  versionName without a Release (tag pushes are blocked from Claude sessions).
  Merged PR #1. v1.2.0 is NOT released yet: the release run stops until the
  three signing secrets are added to the repo (see "Releasing").
- **2026-11-07** — Portal deployment version 14. Summary of Grades (admin):
  one per semester covering every subject (`makeSummaryOfGrades()` builds it
  from the editor too), generated with every class record export, from the VPAA-TEMP-004
  template; INC/Dropped per student
  ("Final Status" tab); 4.00 now shows as CONDITIONAL (was FAILED) in the class
  record, which also gained Conditional/INC/Dropped counts.
- **2026-11-07** — Portal deployment version 15. Indirect assessment: survey link on the student class page
  with submitted status; admin "Indirect assessment" dashboard (response rates by
  section, CO self-ratings, indirect vs direct ≥ 70%, lowest/highest statements,
  who hasn't answered) read from the Course Outcome Assessment Workbook.
- **2026-11-08** — App (still 1.2.0, unreleased): survey card on the class page;
  INC / Dropped / Conditional / Failed labels in the admin roster.
- **2026-11-08** — Portal deployment version 16: new admin "Direct assessment"
  dashboard (standing donut, grade distribution, average by requirement type,
  classes compared, every requirement with score range + spread on click,
  students to watch); charts added to "Indirect assessment"; section dropdown
  with "All sections · all students" on both.
- **2026-11-08** — Portal deployment version 17. Course files: Syllabus and TOS
  for Midterm / Final Exam uploaded per class by the admin, viewed by enrolled
  students in the portal (pdf.js) and in the app (still 1.2.0, unreleased).
