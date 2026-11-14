/**
 * Run this from the editor whenever e-mail stops working. It only asks Google
 * how many e-mails the portal may still send today — nothing is sent.
 * If "Send email as you" was never approved, running it shows Google's
 * permission screen: tick "Send email as you" and Allow.
 */
function checkEmailSending() {
  var left = MailApp.getRemainingDailyQuota();
  console.log('E-mail sending is allowed. E-mails left today: ' + left);
  return left;
}
