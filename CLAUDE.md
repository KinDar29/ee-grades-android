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
- Short link for people: **https://kindar29.github.io/ee-grades-android/**
  (`docs/index.html`, GitHub Pages from `main` /docs) — forwards to the clean
  `/exec` URL. Google adds `/u/1/` to links opened from Drive/Gmail, which
  shows "Sorry, unable to open the file" in multi-account browsers; the short
  link never does. Update it if the deployment ID ever changes.
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
- `Registration.js` — **self-registration**: "Create your account" on the
  sign-in page (Settings `REGISTRATION_OPEN`, blank = on) → form (student
  number `^\d{2}-\d{4,6}$`, names, program from existing Students programs,
  year, e-mail, password, classes of the active semester) → 6-digit code
  e-mailed (`register.start`; pending data + password hash in CacheService,
  30 min) → `register.verify` creates the Students row (active at once, own
  password, `Email Verified` + `Registered` stamped) and enrols via
  `enrollStudents_`. **E-mail gate** for everyone else: students without
  `Email Verified` get `MUST_EMAIL:` from `requireSession_` (Settings
  `REQUIRE_EMAIL_VERIFY`, blank = on) until `account.email.send` /
  `account.email.verify`; login/session return `mustVerifyEmail`. Limits: 5
  codes/hour per number and per address, 150/hour portal-wide, 5 wrong tries.
  The Android app shows its own confirm-e-mail screen from app 1.2.0 (see
  Android app notes).
  Exam save / submit / image skip the gate (`requireSession_` 4th argument
  `skipEmailCheck`) and the page never pops the e-mail screen over a running
  exam, so turning the gate on mid-exam can't lock anyone out of an attempt.
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
  logs the same data from the editor. Workbooks in the **department-template
  layout** (EE121's since Nov 2026: tabs Setup, Course Assessment Plan, Direct
  Assessment, Indirect Assessment, Comparison, Final Grade, …) are read by
  `CourseAssessment.js` instead (`isCourseTemplate_` → `readCourseWorkbook_` →
  `indirectFromCourse_`): per-student rows only, section + student number from
  **Final Grade** (matched by name), counts worked out at Setup's
  "Attainment target per student" (`out.target`, now **50%** = passing; the
  Indirect tab's labels follow it), direct %CO per student so direct
  attainment is per section too, survey responders mapped to sections by
  student number, lab-only CO statements (CO4) dropped.
- `CourseAssessment.js` + `CadsAssets.js` — **Course Assessment Data Sheet**
  (own admin tab "Course Assessment Data Sheet", `renderCadsTab` /
  `cadsPreview` in App.html: pick a section → CO attainment table and the
  comments / analysis / CQI exactly as the PDF will start, ↻ Refresh =
  `fresh`, ⬇ Download PDF): `admin.cads {sectionId}` returns the parsed workbook, the section,
  signatories (instructor from Setup; Chair/Dean = Settings
  `SUMMARY_CHAIR_NAME` / `SUMMARY_DEAN_NAME`, defaults Rigor G. Gabur / Sharona
  Q. Barroga) and the College header/footer images (`CADS_ASSETS`, taken from
  the Program Assessment Data Sheet template; since 2026-11-18 `CADS_ASSETS.page`
  = the COE 50th-anniversary letterhead rendered as one A4 page at 200 dpi,
  drawn behind every page). The browser (`buildCadsPdf` in
  App.html) builds A4 pages (content kept inside 1-in sides, `CADS.TOP` 140 pt
  below the logos and `CADS.BOTTOM` 138 pt above the letterhead artwork; gray
  only — light gray headings, darker gray highlights) in the template's order — I Course
  information, II Direct (plan, class record, attainment, chart + comments),
  III Indirect (form, results, chart + comments), IV Comparison (+ analysis),
  V Actions for CQI, Prepared by / Noted by — pieces captured with
  html-to-image, placed with pdf-lib; comments, analysis and CQI are written
  from the numbers (`cadsTexts`) and are borderless PDF text fields, so the
  owner can change the wording in a PDF reader. Nothing on the page mentions
  editing (owner's request: it must read as a final report).
  Section II: **B. Course Assessment Record** (upper row = CO: task scores,
  %CO, Total %CO and Semestral Grade (%) — no 1.00–5.00 grades or remarks,
  owner's rule; from the Direct Assessment tab), **C. Class Record** (upper
  row = assessment task ME / FE / UE / PS: the workbook's "Class Record" tab
  via `readClassRecordTab_` — each task as its %, component totals, SEM GRADE;
  `course.record`, `student.record` / `semGrade`) followed by the **Final
  Grade** table (lecture average, lab average, final average, final grade, Remarks as
  centred single-line fields), **D. Analysis and Graphs**. Older note: class record (fixed column widths, no note under it) also has Lab
  Grade (%) (workbook Final Grade "Lab final average"; INC / DRP when the cell
  is text), Final Average (Setup's lecture/lab shares, 80/20),
  Final Grade (1.00–5.00) — both from the workbook's **Final Grade** tab, blank
  until the lab instructor's averages are pasted there — and Remarks (single-
  line PDF fields): INCOMPLETE / DROPPED from the class sheet's Final Status
  tab, else PASSED (≤ 3.00), CONDITIONAL (4.00), FAILED (5.00). The class
  record piece is drawn 800 px wide and scaled to the page; field font sizes
  follow the HTML (`px × scale`).
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
  "⬇ Download PDF" on the Indirect tab (`downloadIndirectPdf`, generic
  `buildReportPdf(title, subtitle, pieces)`): builds the PDF in the browser —
  each piece is drawn off-screen in `.pdf-report` (light theme), captured with
  html-to-image 1.11.13 (jsDelivr) and placed on A4 pages with pdf-lib 1.17.1
  (cdnjs). Follows the section dropdown. Download names are forced to ASCII
  (Chrome drops names containing "–").
- `CourseFiles.js` — class **Materials**: any number of PDFs per class
  (types Module / Problem set / Syllabus / TOS / Other), each with a title,
  a week/unit group, a position (▲▼), Shown/Hidden and an optional "show
  from" date. Admin: "📄 Materials" on the class page (`manageMaterials` in
  App.html): upload ≤ 20 MB PDF, optionally to every section of the same
  subject that semester; edit, reorder, replace the file, remove. Master tab
  `Materials` (one row per class + material; rows may share a Drive file,
  trashed when no row uses it). The first version's `CourseFiles` tab
  (Syllabus / TOS ME / TOS FE) was copied in once by `migrateCourseFiles_`
  (Script Property `MATERIALS_MIGRATED`) and is no longer read. Files live in
  the semester's Drive folder → "Course Files", NOT shared — students get the
  bytes through `student.material` (enrolled + released only; `downloadable`
  is true only for Problem sets — every other type opens view-only: no
  Download button, right-click/drag/Ctrl+S/Ctrl+P/print blocked; the app's
  `student.file` is the same call with `kind` = Material ID) and the page
  draws them with pdf.js 4.10.38 from cdnjs. `student.class` returns
  `materials` (released only) and the older `files` list for app 1.2.0.
- `Submissions.js` — **Problem sets** (called "assignments" in the code and
  tabs; the UI says Problem sets — the owner gives problem sets, not other
  assignments): per class (optionally all sibling
  sections), title, instructions, due date/time (`'yyyy-MM-dd HH:mm'`,
  Manila), optional linked material (problem set), allow-late, shown/hidden,
  and a gradebook column (new by default; an existing column only if nobody
  has a score in it — `columnHasScores_`, graded requirements are never
  touched). Students upload one PDF ≤ 10 MB, replaceable until due (late only
  if allowed, marked late). Uploads: Drive file + **appendObject_ without the
  script lock** (deadline rush); latest row per student counts, the replaced
  file is trashed. Files: semester folder → Submissions → "<subject> <section>"
  → "<title>" (folder made at creation). Admin: "📝 Problem sets" on the class
  page (the New form can take the problem-set PDF directly: it is uploaded as
  a Problem-set Material for the same sections and linked) → submissions table, View, type scores (→ `saveScores_`),
  "✎ Grade side by side" (`openGrader` in App.html: student's PDF or the
  problem set on the left, score + class list on the right; Enter = save and
  jump to the next ungraded file, next file prefetched), "🗑 Delete
  graded files" (dry-run count → confirm → trash files of students who have a
  score; row keeps `File Deleted At`, score stays). Tabs `Assignments`,
  `Submissions`.
- `Exams.js` — **Exams**: question bank = one Google Sheet per subject (root →
  "Question Banks", tab "Questions": ID, Unit, CO, Level, Question, A–D,
  Answer, Image; blank IDs filled on check; tab `QuestionBanks` maps subject →
  sheet). Exam = blueprint (count per Unit × Level), duration, open/close
  window, show-score mode (`RELEASED` default / `IMMEDIATE`), own gradebook
  column (max = items). Creation freezes the eligible questions into a pool
  JSON (root → "Exams"; cached in CacheService in 90 KB chunks), so bank
  edits never change a running exam. Each student: random draw, random order,
  shuffled choices; answer key stays on the server; deadline = min(start +
  duration, close) + 2 min grace, checked on the server. Start appends to
  `ExamAttempts`, submit appends to `ExamResults` (no lock); autosave →
  CacheService only (`ans:<attemptId>`, also localStorage in the browser).
  Unsubmitted attempts past the deadline are scored from the autosave
  (`finalizeExpired_`). Scores reach the gradebook only via
  `syncExamScores_` (on Release, on opening Results, and the admin's
  "⇩ Copy scores" / "⇩ Copy all scores to class record" buttons in 🧪 Exams →
  `admin.exam.sync` / `syncExamNow_`; "⇩ Copy for all sections" =
  `allSections` → this class + `siblingSections_`; unreleased RELEASED-mode
  exams are skipped and named), scaled to the column max. **No hourly trigger** (owner's
  choice, to keep the system fast) — don't run `installTriggers()` unless the
  owner asks.
  Admin: "🧪 Exams" → bank status/link, new exam, Results (status, score,
  "Left exam" count — live from the autosave while running, Reset attempt,
  Release scores, item analysis by question and Unit × Level).
  **Exam guard** (App.html `examGuardOn/Off`): full screen on Start (iPhones
  can't — they still get the rest), one "exit" per trip away (tab/app switch,
  leaving full screen, or losing focus > 1.2 s once the page had focus; a
  full-screen exit within 1 s of entering is ignored), questions blurred
  behind a "Back to the exam" cover, copy/cut/paste/right-click/selection and
  print blocked, name + ID watermark (`S.profile`). Exam column `Max Exits`
  (0 = never; added by `ensureColumns_`): the server locks on autosave or
  submit once exits ≥ it — result `How` = `LOCKED` with the answers so far;
  exits only go up. Reset lets the student start over. The cover is shown
  the moment a student leaves (not only when focus comes back — iPhones can
  lose focus without hiding the page and never return it), and any tap on
  the blurred page brings it back (`examTapWhileOut`).
  **Questions shown** (Exams `Navigation`, `navigation_`): ALL (one page,
  default/blank), ONE (one at a time, Previous + numbered buttons), NOBACK
  (one at a time, Next only; skipping a blank asks first). For NOBACK the
  client sends `pos` (first question not yet final) with every save/submit
  and saves at once on Next; the autosave cache keeps `{a, away, pos}` and
  `mergeNoBack_` keeps the cached answers for every question before the
  cached pos, so a tampered or reloaded page can't change them; pos only
  goes up and a reload resumes there.
  **Re-exam** (column choice "Re-exam of a graded column", Exams `Re-exam` =
  HIGHER): scores go into an already-graded column only when higher than
  what's there (never lowers; non-takers untouched; blank → re-exam score).
  The column's scores at creation are kept in `Original Scores` (JSON) so
  Reset attempt puts the student's original score back. **Re-exam credit** (Exams
  `Re-exam Credit`, 1–100, blank = 100; `credit_` / `creditIn_`): the column
  gets max(original from `Original Scores`, re-exam score × credit%),
  recomputed on every sync (Edit's "Use this percentage … in every section",
  `creditAllSections`, sets it on the same-titled re-exam in every sibling
  section), so changing the credit in Edit re-applies (up or
  down, never below the original); manual edits to a re-exam taker's cell
  are overwritten by the next sync. Web portal only — the Android app doesn't show
  assignments or exams yet.
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
- Student numbers must be written as text: `appendObject_` / `updateObject_`
  apostrophe-prefix the `TEXT_COLUMNS` ('Student ID', 'Set Password'), and
  bulk `setValues` of IDs go through `asText_()`. Otherwise Sheets turns IDs
  like "06-5160" into dates (June 5160) — `updateObject_` rewrites the whole
  row, so one missed path re-breaks an ID on the next login.
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
   a function **that uses it** in the editor and approve, or the web app fails.
   Google's consent screen lets people untick single permissions, and running
   an unrelated function does NOT re-ask. For e-mail run `checkEmailSending()`
   (`MailCheck.js`, sends nothing) — it must log "E-mails left today: N".

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
  through `student.file`. Problem sets (`downloadable: true`) go to
  `cacheDir/course-files/` and open in the phone's PDF viewer via the
  FileProvider `${applicationId}.files`. Everything else goes to
  `cacheDir/view-only/` (not shared) and opens in `ui/ViewOnlyPdf.kt`:
  PdfRenderer pages in a full-screen dialog with `SecureFlagPolicy.SecureOn`
  (no screenshots / screen recording), file deleted on close.
- Registration: "Create your account" on the sign-in screen (students, when
  `portal` says `registrationOpen`) → `ui/RegisterScreen.kt` (same form as the
  portal: `register.options` / `register.start` / `register.verify`), then
  signs in with the new password.
- E-mail gate: `ApiClient` reads `mustVerifyEmail` from any error and sets
  `SessionSignals.emailNeeded`; `AppViewModel` then switches to
  `AuthPhase.MustVerifyEmail` → `ConfirmEmailScreen` (`account.email.send` /
  `account.email.verify`), after the password step if both are due.

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
- **2026-11-08** — Portal deployment version 18: "⬇ Download PDF" on the
  Indirect assessment tab (all charts and results for the chosen section or
  all sections, every survey statement, who hasn't answered).
- **2026-11-12** — Data only: EE121 Final Exam scores entered from
  `EE121_Final_Exam_Scores.xlsx` (Drive › EE121 Final Exam › 1 Results; /50,
  official rule, doubled to the column's max of 100; no-sheet students left
  blank). Fixed Agbayani's Student ID (25-050550 → 25-050432; 25-050550 is
  Buenavista, BSECE) and added Buenavista's account (unique starting password,
  must change).
- **2026-11-12** — Short link https://kindar29.github.io/ee-grades-android/
  (GitHub Pages) that always opens the portal's clean address.
- **2026-11-14** — Portal deployment version 19. Materials: modules, problem sets,
  syllabus and TOS as any number of PDFs per class, grouped by week/unit, with
  order, show/hide and show-from dates; replaces the fixed three course files
  (migrated automatically).
- **2026-11-14** — (version 19) Assignments with PDF submissions (grading, delete graded
  files) and multiple-choice exams from a per-subject question bank (random
  sets, timer, autosave, release, item analysis). Existing graded columns are
  never written. `tab_` now survives two requests creating a new tab at once.
  Verified on real data before deploying (temporary editor function, since
  removed): Materials migrated (Syllabus + TOS Final in all EE121 sections),
  only score-free columns offered, new tabs created.
- **2026-11-14** — Portal deployment version 20: "Assignments" renamed
  **Problem sets** everywhere people see it; the New form takes the problem
  set PDF directly (added to Materials and linked); new gradebook columns get
  type "Problem Set".
  "✎ Grade side by side" in Submissions: read the student's PDF (or switch to
  the problem set) and type the score in the same window; Enter saves and
  opens the next ungraded student.
- **2026-11-14** — Portal deployment version 21: exam guard — exams open in
  full screen, leaving (tab/app switch or full-screen exit) is counted and
  blurs the questions until the student returns, copy/paste/right-click
  off, student name + ID watermark, optional "lock after N exits" (default 3
  on new exams) that submits the answers so far; "🔒 locked" in Results.
- **2026-11-14** — Portal deployment version 21 is live. Data only: EE121
  question bank ("EE121 — Question Bank", root → Question Banks, private)
  filled with 300 items from `EE121_Unit8_Lesson1_Question_Bank.pdf` — IDs
  `U8L1-E001…E100 / M001…M100 / H001…H100`, Unit "Unit 8", Level Easy /
  Medium / Hard, keys from the PDF's answer key (75 per letter; Hard
  node-voltage items re-computed and matched). Imported by a temporary editor
  function reading a temporary Drive JSON (both removed). Question text is
  never committed here — the repo is public.
- **2026-11-14** — Portal deployment version 22: students can download only
  Problem-set materials; modules, syllabus, TOS and other materials are
  view-only in the portal (admins can still download everything).
  Also: student numbers are always stored as text — "06-5160" had been saved
  as the date June 5160 (owner's test account, deleted; no real student hit).
- **2026-11-14** — (version 22) Student self-registration with e-mail
  verification (owner's choices: active at once, students pick their
  classes) and a one-time "confirm your e-mail" step for every existing
  student before they can use the portal.
- **2026-11-14 09:50** — Incident: the e-mail gate went live but the project
  had never been authorized for `script.send_mail` (MailApp) — every code
  send failed with "You do not have permission to call MailApp…", locking
  students at "Confirm your e-mail" (forgot-password e-mails never worked
  either). Mitigation: Settings `REQUIRE_EMAIL_VERIFY` = FALSE and
  `REGISTRATION_OPEN` = FALSE. Fix: the owner runs any function once in the
  editor and approves; then set both back to TRUE. **Before shipping
  anything that sends mail, check the owner has approved the mail scope.**
- **2026-11-14 10:04** — Fixed: the owner ran `checkEmailSending()` (new
  `MailCheck.js`) and approved "Send email as you" (it had been unticked);
  quota 1500/day. `REGISTRATION_OPEN` back to TRUE.
- **2026-11-14 10:10** — Unit Exam No. 8 is open until 2026-11-16 01:00, so
  `REQUIRE_EMAIL_VERIFY` stays FALSE (registration on) until version 23:
  running exams are exempt from the e-mail gate.
- **2026-11-14 10:54** — Portal deployment version 23: running exams exempt
  from the e-mail gate (exam save/submit/image skip it; the page never shows
  the e-mail screen over an exam); `MailCheck.js`. `REQUIRE_EMAIL_VERIFY`
  turned back to TRUE right after.
- **2026-11-14** — App 1.2.0: confirm-e-mail screen for the portal's e-mail
  gate; view-only course files open in an in-app viewer with screenshots
  blocked (problem sets still open in the phone's PDF viewer); new-student
  registration screen, same steps as the portal.
- **2026-11-14 11:45** — Signing key created on the owner's laptop
  (Documents\EE-Grades-Key, alias `ee-grades`, RSA 4096, PKCS12, ~27 years);
  the owner added the three secrets. Merging this releases **v1.2.0**.
- **2026-11-17** — Data: EE121 workbook Setup "Attainment target per student"
  70% → **50%** (passing), owner's request. Portal deployment version 24:
  Indirect tab reads the department-template workbook again (it had changed
  layout) at the workbook's target, direct attainment per section; new
  per-section **Course Assessment Data Sheet** PDF (`CourseAssessment.js`,
  `CadsAssets.js`). Verified: `checkIndirectAssessment()` on the live workbook
  = workbook Comparison tab (CO1 92.94/13.48, CO2 80/20.69, CO3 71.76/94.25).
- **2026-11-17** — Portal deployment version 25 (live 16:41): Course Assessment Data Sheet
  class record gains Final Average (lecture 80% + lab 20%), Final Grade and
  editable Remarks (PASSED / CONDITIONAL / FAILED / INCOMPLETE / DROPPED).
- **2026-11-17** — Data only: EE121 question bank gained the **midterm bank**
  (`EE121_Midterm_Question_Bank.pdf`, Units I–IV): 300 items, rows 302–601,
  IDs `MID-L001…L100 / MID-M001…M100 / MID-H001…H100`, Unit "Unit 1"–"Unit 4"
  (per the PDF's table), CO1, Level **Low / Middle / High** (owner's names; the
  Unit 8 items keep Easy / Medium / Hard), keys from the PDF (75 per letter;
  8 numeric items re-computed and matched). Written as plain text
  (`setNumberFormat('@')`, so "50%" stays text) by a temporary editor
  function reading a temporary Drive JSON (both removed). Bank: 600 items.
- **2026-11-17** — (version 25, live 16:41)
  Re-exam mode for exams (keeps the higher score in an already-graded column
  such as the Midterm; owner's choice: higher score wins).
- **2026-11-17 17:15** — Portal deployment version 26 (during the Midterm,
  owner's go-ahead): exam guard fix — an iPhone student was left on blurred
  questions with no "Back to the exam" button (Safari took focus without
  hiding the page and never gave it back). The box now always appears when a
  student leaves, and any tap on the blurred page brings it back. Students
  already stuck: reload, open the class, Continue (answers are autosaved).
- **2026-11-17 21:10** — Data only: EE121 question bank gained the **Unit 1
  bank** (`EE121_Unit1_Question_Bank.pdf`): 300 items, rows 602–901, IDs
  `U1-E001…E100 / U1-M001…M100 / U1-H001…H100`, Unit "Unit 1", CO1, Level
  Easy / Medium / Hard (same unit name as the midterm items, whose levels are
  Low / Middle / High, so blueprints keep them apart). Keys from the PDF (75
  per letter); every Medium and Hard key recomputed and matched; "according
  to the module" wording removed (14 items, classification items say "by the
  valence-electron rule"). Written with the Sheets API into rows pre-set to
  plain text, then read back: identical. Bank: 900 items.
- **2026-11-17 21:33** — Portal deployment version 27: exam "Questions
  shown" setting (all on one page / one at a time / one at a time with no
  going back, enforced on the server).
- **2026-11-17 21:59** — Portal deployment version 28: separate "Course
  Assessment Data Sheet" tab with an on-screen preview (button removed from
  the Indirect tab).
- **2026-11-17 23:11** — Portal deployment version 29: re-exam credit %
  (only part of a re-exam score counts; adjustable per exam in Edit); students
  see the percentage as a note on the exam card and in the Start dialog.
  The hourly trigger (`scheduledRosterSync`) is still NOT installed — the
  project has 0 triggers, so exam scores copy only on Results/Release. The
  owner must run `installTriggers()` in the editor once.
- **2026-11-17 23:17** — Portal deployment version 30: "⇩ Copy scores" per exam and
  "⇩ Copy all scores to class record" in 🧪 Exams. Owner decided against the
  hourly trigger; the project has 0 triggers and stays that way.
- **2026-11-17 23:32** — Portal deployment version 31: class record reloads after the
  Exams window closes if scores were copied; "⇩ Copy for all sections"; re-exam
  credit % can be set for the same exam in every section at once.
- **2026-11-17 23:45** — Data only: EE121 workbook **Final Grade** tab, column
  "Final average (%)" (H9:H208) is now `ROUND(80% lecture + 20% lab, 0)`, so
  the final grade and remark come from the whole-number average (owner's rule:
  49.5 → 50 → 3.00 PASSED). Lecture grade (column F) still looks up the
  unrounded lecture average.
- **2026-11-17 23:55** — Data only: lab final averages pasted into the EE121
  workbook's Final Grade lab table (L9:M97, 89 students) from the lab
  instructors' files — BSECE: Direct Assessment %CO4 (15% ME + 15% FE + 70%
  lab); BSEE 2A: AVERAGE column; BSEE 2B: TOTAL AVERAGE. Domingo's lab file
  ID 24-140203 → our 24-140202 (same name); Ulep had no ID in the 2B file
  (matched by name). Avanzado (24-050384, 2B) is not in the 2B lab file —
  still "Waiting for lab average".
- **2026-11-17 23:58** — Data only: EE121 workbook Final Grade rule (owner's):
  if the lecture or the lab average (each rounded to a whole number) is below
  50, the final grade is that failing component's grade (4.00 / 5.00; the
  worse one if both) — the other component can't lift it. Lab "INC" /
  "DROPPED" text gives INC / DROPPED (remark INCOMPLETE / DROPPED). Columns
  I:J (rows 9–208); Final average (H) is still shown as 80/20.
- **2026-11-18 08:55** — Portal deployment version 32: Course
  Assessment Data Sheet on the COE 50th-anniversary letterhead (A4), gray
  tables and charts, Lab Grade column in the class record, aligned tables
  (course info full width, grading system and time stacked, time as one
  row per Unit / CO / Hours), every piece inside the margins. Checked by
  running the real `buildCadsPdf` in Chromium on sample data
  (`/opt/npm-tools` pdf-lib + html-to-image).
- **2026-11-18 09:07** — Portal deployment version 33: Course Assessment Data Sheet:
  the explanatory note under the class record removed (owner's request). The
  Indirect Assessment Form is one table that `cadsSplitPieces` cuts into
  page-sized parts (heading row repeated, a CO row never left alone at the
  bottom), so every part prints at the same full size; any `table.split`
  (form, class record) is handled the same way.
- **2026-11-18 09:35** — Portal deployment version 34: Course Assessment Data Sheet II:
  B. Course Assessment Record (CO on top) and C. Class Record (ME / FE / UE /
  PS on top, from the workbook's "Class Record" tab) as separate sections,
  Final Grade table after the class record, Analysis renumbered D; Remarks
  centred in their cells. Course Assessment Record ends at Total %CO +
  Semestral Grade (%); numerical grades and remarks only in the Class Record
  part (its Final Grade table).
- **2026-11-18 09:56** — Portal deployment version 35: Course Assessment Data
  Sheet fix — inside the portal page its tables picked up the portal's own
  table style (uppercase dark sticky headers, no-wrap), so headers turned dark,
  names wrapped and the Final Grade table ran past the margin with Remarks
  outside. `CADS_CSS` now resets those inside `.cads`; Final Grade names stay
  on one line. The test harness now loads `Styles.html` in dark mode so the
  sample matches what the portal produces.
- **2026-11-18 09:58** — Data only: Avanzado (24-050384) marked **Dropped** in
  BSEE 2B's Final Status tab (owner: dropped in lecture, so dropped in lab too;
  matches the workbook). Tariga (24-050234) was already Dropped there.
- **2026-11-18 10:15** — Data only: EE121 workbook Final Grade, Sugui
  (24-050329, BSEE 2A) lab final average 0 → **60** in the lab table (M69),
  owner's request (final grade must be 3.00): lecture 53.63, final average 55
  → 3.00 PASSED. G70 had been typed over with 70; its lookup formula is back.
- **2026-11-18 10:52** — (not deployed yet) Course Assessment Data Sheet:
  Final Grade (1.00–5.00) in the Final Grade table is now a fillable PDF field
  (centred, like Remarks). Sugui's lab average checked from the editor with a
  temporary read-only function (removed): the server reads 60 / 3.00 PASSED —
  the 0 the owner saw was a data sheet loaded before the 10:15 change.
