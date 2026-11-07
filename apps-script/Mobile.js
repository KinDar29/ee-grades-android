/**
 * ============================================================================
 *  Mobile.gs  ·  HTTPS bridge for the Android app
 * ============================================================================
 *  ADDITIVE ONLY. Nothing in this file changes an existing function, an
 *  existing file, or any spreadsheet. Deleting this one file returns the
 *  project to exactly what it was before.
 *
 *  Why it exists
 *  -------------
 *  The web portal reaches the server through google.script.run, a channel that
 *  only works from inside an Apps Script HTML page. A native app cannot use
 *  it, so it needs a plain HTTPS endpoint. doPost() is that endpoint, and it
 *  hands the request straight to the existing api() router -- so the phone and
 *  the browser run identical code against identical data, with identical
 *  permission checks.
 *
 *  Request   POST <the web app /exec URL>
 *            Content-Type: text/plain
 *            {"action":"student.home","payload":{},"token":"..."}
 *
 *  Response  {"ok":true,"data":{ ... }}
 *            {"ok":false,"error":"...","authRequired":true}
 * ============================================================================
 */

var MOBILE = {
  /**
   * How long an app session lasts, in hours. Deliberately longer than the
   * browser's APP.SESSION_HOURS, because retyping a password on a phone every
   * workday is miserable. This does NOT change the browser session length --
   * APP.SESSION_HOURS is untouched and still governs the web portal.
   *
   * A token still dies the instant the password changes, because
   * requireSession_() compares the stored hash tail against the token claim.
   */
  SESSION_HOURS: 168,

  /** Actions whose reply carries a freshly minted token worth lengthening. */
  TOKEN_ACTIONS: { 'login': true, 'changePassword': true }
};

/* ------------------------------- endpoint -------------------------------- */

function doPost(e) {
  var request;
  try {
    request = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return jsonOut_(JSON.stringify({
      ok: false,
      error: 'The request was not valid JSON.'
    }));
  }
  if (!request || typeof request !== 'object') request = {};

  var body = api(request);

  if (MOBILE.TOKEN_ACTIONS[String(request.action || '')]) {
    body = withMobileToken_(body);
  }
  return jsonOut_(body);
}

function jsonOut_(text) {
  return ContentService
    .createTextOutput(text)
    .setMimeType(ContentService.MimeType.JSON);
}

/* -------------------------------- tokens --------------------------------- */

/** Swap a just-issued token for one carrying the mobile lifetime. */
function withMobileToken_(body) {
  var parsed;
  try { parsed = JSON.parse(body); } catch (err) { return body; }
  if (!parsed || !parsed.ok || !parsed.data || !parsed.data.token) return body;

  var claims = parseToken_(parsed.data.token);
  if (!claims) return body;
  if (claims.r !== 'student') return body;   // admins keep the normal session length

  parsed.data.token = mobileToken_(claims.s, claims.r, claims.v);
  return JSON.stringify(parsed);
}

/**
 * Identical signing scheme, identical secret and identical claim shape as
 * makeToken_() -- only the expiry differs, so parseToken_() and
 * requireSession_() validate these with no change whatsoever.
 */
function mobileToken_(subject, role, stamp) {
  var payload = {
    s: String(subject),
    r: String(role),
    v: String(stamp || '').slice(-10),
    e: new Date().getTime() + MOBILE.SESSION_HOURS * 3600 * 1000
  };
  var body = Utilities.base64EncodeWebSafe(JSON.stringify(payload));
  var sig = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(body, secret_(PROP.SECRET))
  );
  return body + '.' + sig;
}
