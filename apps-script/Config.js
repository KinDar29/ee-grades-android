/**
 * ============================================================================
 *  Electrical Engineering — Student Grades Portal
 *  Config.gs  ·  Global configuration, property keys and sheet schemas
 * ============================================================================
 *  Everything that you might reasonably want to change lives in this file
 *  or in the "Settings" tab of the master spreadsheet.
 * ============================================================================
 */

/**
 * The Google Drive folder that holds everything: the master database plus one
 * sub-folder per semester. Paste a folder ID or its full Drive link here.
 * setUp() uses this the first time it runs; after that the value is remembered
 * in Script Properties, and setRootFolder('<link>') moves everything elsewhere.
 */
var ROOT_FOLDER = '1VzIjjLVRXsKCRn1w_qWG2phBKBqQdTjw';

var APP = {
  NAME: 'Electrical Engineering — Grades Portal',
  SHORT_NAME: 'EE Grades',
  VERSION: '1.0.0',
  TIMEZONE: 'Asia/Manila',

  /** Name of the master database spreadsheet created inside your root folder. */
  MASTER_FILE_NAME: '00 · EE MASTER DATABASE',

  /** How long a login stays valid, in hours. */
  SESSION_HOURS: 8,

  /** Password stretching rounds. Higher = slower login but harder to crack. */
  HASH_ITERATIONS: 1200,

  /** How long to wait for the document lock before giving up, in ms. */
  LOCK_TIMEOUT_MS: 25000,

  /** Fallback default password used when the "Set Password" cell is empty. */
  FALLBACK_PASSWORD: 'ee-portal',

  /** Minimum length for a password a student sets themselves. */
  MIN_PASSWORD_LENGTH: 6,

  /** Max seconds the background roster-sync job may run. */
  SYNC_BUDGET_SECONDS: 240
};

/** Keys used in Script Properties. Do not rename these. */
var PROP = {
  ROOT_FOLDER: 'ROOT_FOLDER_ID',
  MASTER_ID: 'MASTER_SPREADSHEET_ID',
  SECRET: 'SESSION_SECRET',
  PEPPER: 'PASSWORD_PEPPER'
};

/** Tabs inside the master database spreadsheet. */
var TAB = {
  STUDENTS: 'Students',
  SEMESTERS: 'Semesters',
  SUBJECTS: 'Subjects',
  SECTIONS: 'Sections',
  ENROLLMENTS: 'Enrollments',
  ADMINS: 'Admins',
  SETTINGS: 'Settings',
  SCALE: 'GradeScale',
  LOG: 'AuditLog'
};

/** Tabs inside every per-section spreadsheet. */
var STAB = {
  INFO: 'Info',
  ASSESSMENTS: 'Assessments',
  GRADES: 'Grades',
  SUMMARY: 'Summary',
  STATUS: 'Final Status'
};

/** Standings an admin sets by hand; everything else comes from the grade. */
var FINAL_STATUSES = ['INC', 'Dropped'];

/**
 * Column headers for each master tab.
 * Columns the admin is meant to type into come first; system-maintained
 * columns come last and are tinted grey by the setup routine.
 */
var SCHEMA = {};

SCHEMA[TAB.STUDENTS] = [
  'Student ID', 'Last Name', 'First Name', 'Middle Name',
  'Program', 'Year Level', 'Email', 'Status', 'Set Password',
  'Password Hash', 'Salt', 'Applied Set', 'Must Change',
  'Password Updated', 'Emailed Set', 'Password Emailed', 'Last Login', 'Created At'
];

SCHEMA[TAB.SEMESTERS] = [
  'Semester ID', 'Name', 'School Year', 'Term', 'Active',
  'Folder ID', 'Folder URL', 'Created At'
];

SCHEMA[TAB.SUBJECTS] = [
  'Subject ID', 'Code', 'Title', 'Units', 'Description', 'Created At'
];

SCHEMA[TAB.SECTIONS] = [
  'Section ID', 'Section Name', 'Subject ID', 'Semester ID', 'Instructor',
  'Schedule', 'Room', 'Status', 'Spreadsheet ID', 'Spreadsheet URL',
  'Enrolled', 'Last Synced', 'Created At', 'Survey Link', 'Assessment Workbook'
];

SCHEMA[TAB.ENROLLMENTS] = [
  'Section ID', 'Student ID', 'Semester ID', 'Synced At'
];

SCHEMA[TAB.ADMINS] = [
  'Email', 'Name', 'Role', 'Status', 'Set Password',
  'Password Hash', 'Salt', 'Applied Set', 'Must Change', 'Last Login', 'Created At'
];

SCHEMA[TAB.SETTINGS] = ['Key', 'Value', 'What it does'];

SCHEMA[TAB.SCALE] = ['Min %', 'Max %', 'Grade', 'Remark'];

SCHEMA[TAB.LOG] = ['Timestamp', 'Actor', 'Role', 'Action', 'Target', 'Details'];

/** Fixed (non-assessment) columns at the left of every "Grades" tab. */
var GRADES_FIXED = ['Student ID', 'Last Name', 'First Name'];

/** Column headers of the per-section "Assessments" tab. */
SCHEMA[STAB.ASSESSMENTS] = [
  'Assessment ID', 'Name', 'Type', 'Max Score', 'Weight %',
  'Date', 'Published', 'Column', 'Notes'
];

/** Assessment types offered in the admin dropdown. */
var ASSESSMENT_TYPES = [
  'Exam', 'Quiz', 'Assignment', 'Activity', 'Project',
  'Recitation', 'Laboratory', 'Attendance', 'Other'
];

/** Default rows written into the Settings tab on first setup. */
var DEFAULT_SETTINGS = [
  ['PORTAL_TITLE', 'Electrical Engineering', 'Big title shown at the top of the portal.'],
  ['PORTAL_SUBTITLE', 'Student Grades Portal', 'Small line under the title.'],
  ['ANNOUNCEMENT', '', 'Optional notice shown to every student. Leave blank to hide.'],
  ['CONTACT_EMAIL', '', 'Shown to students who cannot log in.'],
  ['SHOW_RUNNING_GRADE', 'TRUE', 'TRUE = students see the weighted running grade and its 1.00–5.00 equivalent.'],
  ['SHOW_CLASS_AVERAGE', 'TRUE', 'TRUE = students see the class average next to their own score.'],
  ['COUNT_BLANK_AS_ZERO', 'FALSE', 'FALSE = blank scores are treated as "not yet recorded". TRUE = blanks count as zero.'],
  ['ALLOW_PASSWORD_CHANGE', 'TRUE', 'TRUE = students may change their own password.'],
  ['DEFAULT_PASSWORD', 'ee2026', 'Password used for a student whose "Set Password" cell is blank and who has never logged in.'],
  ['ACTIVE_SEMESTER_ID', '', 'Semester opened by default. Managed from the admin dashboard.'],
  ['SUMMARY_CHAIR_NAME', '', 'Program Coordinator / Department Chair printed on the Summary of Grades. Blank = keep the name in the template.'],
  ['SUMMARY_DEAN_NAME', '', 'Dean printed on the Summary of Grades. Blank = keep the name in the template.'],
  ['SUMMARY_TEMPLATE_ID', '', 'Drive file ID of the Summary of Grades .docx template. Blank = use the file in the Templates folder.']
];

/**
 * Default transmutation table (percentage → numeric grade).
 * Edit the "GradeScale" tab any time; changes take effect immediately.
 */
var DEFAULT_SCALE = [
  [99, 100, 1.00, 'Excellent'],
  [96, 98.99, 1.25, 'Excellent'],
  [93, 95.99, 1.50, 'Very Good'],
  [90, 92.99, 1.75, 'Very Good'],
  [87, 89.99, 2.00, 'Good'],
  [84, 86.99, 2.25, 'Good'],
  [81, 83.99, 2.50, 'Satisfactory'],
  [78, 80.99, 2.75, 'Satisfactory'],
  [75, 77.99, 3.00, 'Passing'],
  [0, 74.99, 5.00, 'Failed']
];

/** Brand colours used when formatting the generated spreadsheets. */
var BRAND = {
  HEADER_BG: '#0f5132',
  HEADER_FG: '#ffffff',
  SYSTEM_BG: '#eceff1',
  ACCENT: '#c9a227',
  BAND: '#f4f7f5'
};
