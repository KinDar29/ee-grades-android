# EE Grades — Android app

A native Android client for the **EE Grades Portal** Apps Script project. It reads
the same master spreadsheet, through the same `api()` router, with the same
permission checks. Nothing about your database changes.

Students see their classes and their published assessments. Administrators get
read-only class views. No one can write scores from the phone.

---

## 1 · The web app — done and verified

A file called `Mobile.gs` was added to your Apps Script project and is now saved
and deployed as **version 8**. It is **additive** — no existing file was
modified, and deleting it returns the project to exactly what it was.

It adds one thing: a `doPost(e)` endpoint. Your web portal talks to the server
through `google.script.run`, which only works inside an Apps Script HTML page, so
a native app needs a plain HTTPS endpoint. `doPost` hands every request straight
to the existing `api()` router.

The live endpoint was checked with three requests:

| Request | Result |
|---|---|
| `{"action":"ping"}` | `{"ok":true,"data":{"app":"Electrical Engineering — Grades Portal","version":"1.0.0","ready":true}}` |
| `{"action":"portal"}` | Returned your real `Settings` values — title, subtitle, `allowPasswordChange` |
| `{"action":"session","token":"not-a-real-token"}` | `{"ok":false,"error":"Your session has expired…","authRequired":true}` |

The third is the one that matters: the auth gate is live on the new endpoint,
rejecting a forged token exactly as it does in the browser. Round trips took
1.4–2.1 seconds, which is why the app paints from cache first.

The deployment reads **Execute as: Me** and **Who has access: Anyone**, both of
which the app relies on.

> If you ever redeploy, use *Deploy → Manage deployments → pencil → New version*,
> **not** *New deployment*. New deployment mints a different `/exec` URL and
> would strand every installed copy of the app.

### What `Mobile.gs` contains

| Function | Purpose |
|---|---|
| `doPost(e)` | Parses the JSON body, calls `api()`, returns the reply as JSON |
| `withMobileToken_` | Swaps a freshly issued token for one with the app's lifetime |
| `mobileToken_` | Same signing scheme, same secret, same claims as `makeToken_` — only the expiry differs |
| `MOBILE.SESSION_HOURS` | `168` (one week). The browser keeps `APP.SESSION_HOURS`, untouched |

A longer app session is not a weaker one: `requireSession_()` still compares the
token against the stored password hash, so changing a password kills every
existing session instantly, on phone and browser alike.

---

## 2 · Build the APK

The repository ships a GitHub Actions workflow, so you do not need Android Studio.
Your deployment URL is already set in `gradle.properties`, so there is nothing to
configure:

1. Create a repository and push this project to it
2. Push, or run the workflow manually from the **Actions** tab
3. Open the finished run and download the **ee-grades-debug-apk** artifact

If you would rather not have the URL committed, delete the `EE_API_URL` line from
`gradle.properties` and add it instead as a repository variable: **Settings →
Secrets and variables → Actions → Variables → New repository variable**, named
`EE_API_URL`. The workflow already prefers that variable when it exists.
`workflow_dispatch` also accepts a URL as an input, for building against a test
deployment without touching either.

### Building locally instead

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk

# or point it somewhere else for one build:
./gradlew assembleDebug -PEE_API_URL="https://script.google.com/macros/s/…/exec"
```

Requires JDK 17 and the Android SDK (compileSdk 35). Android Studio supplies both.

### Installing

The debug APK is signed with the standard debug key, which is fine for testing
and sideloading but **not** for the Play Store. For a Play release you need a
release keystore and a `signingConfigs` block; ask and I will add one, along with
notes for the Data Safety form — grades are personal data and Google asks about
it specifically.

---

## 3 · What the app does

**Students**

- Sign in with student number and password
- Forced password change when the server sets `mustChangePassword`
- Class list, filtered by semester when enrolled in more than one
- Per-class assessment breakdown: score, percentage, class average and high
- Weighted running grade with its 1.00–5.00 equivalent and remark, shown only
  when `SHOW_RUNNING_GRADE` allows it
- Change password, when `ALLOW_PASSWORD_CHANGE` allows it

**Administrators**

- Semester picker with student / class / subject counts
- Section list with enrolment numbers
- Per-section view: assessment list, a warning when weights do not total 100%,
  and a searchable roster with each student's computed grade

**Everyone**

- Every screen is cached, so the app opens instantly and stays readable with no
  signal, showing "Offline — showing what was saved 20 minutes ago"
- An expired or revoked token returns you to sign-in with the server's own message

### What it deliberately does not do

- **No score entry.** A spreadsheet grid on a phone invites mistakes in exactly
  the data that matters most. Scores stay in the web portal.
- **No student directory.** `admin.students.list` returns each student's
  plaintext `Set Password` value. That belongs nowhere near a phone, so the app
  never calls it. The admin roster comes from `admin.section.data`, which carries
  no credentials.
- **No writes of any kind.** The app only calls read actions plus `login` and
  `changePassword`.

---

## 4 · How it is put together

```
app/src/main/java/ph/edu/mmsu/ee/grades/
├── EeGradesApp.kt          Application + a plain service locator
├── MainActivity.kt
├── data/
│   ├── Models.kt           @Serializable mirrors of the Apps Script responses
│   ├── ApiClient.kt        OkHttp POST to /exec, envelope parsing
│   ├── Store.kt            DataStore: session token + JSON cache
│   └── Repository.kt       The single surface the UI talks to
└── ui/
    ├── Theme.kt            Brand colours from BRAND in Config.gs, light + dark
    ├── Components.kt       Shared pieces and formatting
    ├── ViewModels.kt       One view model per screen
    ├── AppRoot.kt          Auth phases and navigation
    ├── LoginScreen.kt  ChangePasswordScreen.kt
    ├── HomeScreen.kt   ClassScreen.kt
    ├── AdminScreens.kt SettingsScreen.kt
```

Two choices worth explaining:

**No Room.** The cache stores whole JSON payloads under a key rather than
normalising them into tables. The app never queries across cached data — it
shows one screen's worth at a time — so a database would add a schema, a
migration story and an annotation processor for nothing.

**No DI framework.** There is one repository with no variants. View models read
it from `ServiceLocator`, which keeps their constructors empty and lets Compose
build them with the default factory.

**Refresh is a button, not a pull.** Material 3's pull-to-refresh has moved
between experimental APIs across recent versions; a toolbar button behaves the
same everywhere and cannot break on a library bump.

---

## 5 · If something goes wrong

| Symptom | Cause |
|---|---|
| "The server sent a page instead of data" | The deployment's access is not set to **Anyone**, so Google returned a sign-in page |
| "This build has no server address" | `EE_API_URL` was not set when the APK was built |
| "The server replied 404" | The `/exec` URL is wrong, or the deployment was deleted |
| Everything times out | Apps Script cold starts can take several seconds; the client waits 90 s before giving up |
| Admin section screen is slow | `admin.section.data` calls `rebuildRoster_()` on every open. Expected, and worth revisiting if it becomes annoying |
| Login says "Too many failed attempts" | `checkThrottle_` locks an account for 10 minutes after 8 failures. Working as designed |

---

## 6 · Notes on privacy

The session token and cached grades live in the app's private storage, excluded
from cloud backup and device-to-device transfer. Signing out clears both. The app
talks to exactly one host — your Apps Script deployment — and to nothing else: no
analytics, no crash reporting, no third-party SDKs.

Because the endpoint is deployed as `ANYONE_ANONYMOUS`, it is reachable by
anyone who knows the URL. That was already true of your web portal, and the
protection is the same in both: every action past `ping`, `portal` and `login`
goes through `requireSession_()`, and `login` is rate-limited.
