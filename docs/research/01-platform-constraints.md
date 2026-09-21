# Research: platform constraints

Researched 2026-09-16. Two findings reshaped the project before any code was written.

## iOS cannot read SMS. At all.

There is no API, and this is not a restriction that is loosening — it is a core part of
Apple's security model. Third-party apps have **zero** read access to the Messages
database and cannot read, export or save a user's SMS history.

**One precise exception, which does not help us (added 2026-09-22).**
`TelephonyMessagingKit` (iOS 26.2) lets an app receive incoming SMS/MMS/RCS — but only
in the **EU**, only when the user has set that app as the **default carrier messaging
app** (a full Messages replacement), and only for *incoming* messages, with no access to
history. A finance app that asked to become your Messages app would be a different
product. It matters to the global scope only as a footnote: the claim is "no third-party
inbox access", not "no API of any kind".

The one apparent exception does not help. `ILMessageFilterExtension` (the IdentityLookup
framework) lets an app act as a spam filter, but:

- it only sees messages from **unknown senders** — a bank you have ever replied to or
  saved is invisible to it;
- its sandbox **forbids writing to arbitrary file paths and opening network connections**
  during the classification call;
- anything read from `messageBody` stays in-process and is **discarded when the XPC call
  ends**.

That last constraint is deliberate — it is precisely what lets Apple permit third-party
message filtering at all. It also means the extension structurally cannot save a
transaction.

### The one real iOS path

A **Shortcuts personal automation**: *"When I get a message containing X → run
immediately"* → a shortcut that hands the body to the app through an App Intent. People
do build expense trackers this way today.

Trade-off: the user configures it themselves, once per bank sender, and the onboarding
is genuinely fiddly. Hence [D1](../decisions.md#d1--android-first-ios-is-phase-2) —
iOS is phase 2.

**Unverified, needs a spike before it is promised:** whether the Message automation
trigger exposes the message *body* to an App Intent on current iOS, and whether its
sender filter accepts alphanumeric bank sender IDs or only phone numbers (it may need a
"contains keyword" filter instead).

## Android: the SMS permission is a distribution problem, not a technical one

`READ_SMS` and `RECEIVE_SMS` are Play-**restricted** permissions. To ship on Google Play
an app must either be the device's **default SMS handler** or win an exception via the
Restricted Permission Declaration Form.

Outside Play they are *grantable* but, from **Android 15**, gated: the platform marks
them hard-restricted for apps not installed by Play, and the user must enable
*Allow restricted settings* on the app's info page before the permission dialog can be
shown. Verified on paper only — [spike S4](../spike/README.md#s3) tests it on devices.

Play policy explicitly addresses budgeting apps and SMS history, many non-default-handler
finance apps have been refused, and Google has taken enforcement action at scale against
apps abusing SMS access. It is a genuinely hostile path.

**But it is not a closed one, and saying otherwise would be false.** PennyWiseAI ships on
Google Play today with read-only `READ_SMS` for transaction parsing — so the declaration
form *can* be won for this exact use case. What Play costs is not possibility but
**certainty**: the grant is discretionary, re-reviewable and revocable, and an app that
loses it post-launch strands its users. That is the real argument for
[D2](../decisions.md#d2--distribute-via-f-droid-and-github-apk-not-google-play), and it is
a weaker one than "Google says no".

### The two ways around it

**(a) Ship outside Play** — F-Droid and a GitHub APK. The permission is unrestricted
there, and F-Droid is the natural home for an auditable open-source privacy tool.
This is [D2](../decisions.md#d2--distribute-via-f-droid-and-github-apk-not-google-play).

**(b) `NotificationListenerService`** — read bank *notifications* rather than SMS. This
sidesteps `READ_SMS` entirely and is what several Play-distributed trackers do. But it
is itself a restricted capability requiring justification, the user must grant it through
a special settings screen, and **Android 15+ redacts notifications it classifies as
containing OTPs from untrusted listeners** — which may silently swallow exactly the bank
alerts we need. Rejected for v1; see [spike S3](../spike/README.md#s3).

## Sources

- [Play: Use of SMS or Call Log permission groups](https://support.google.com/googleplay/android-developer/answer/10208820)
- [Play: preview of the updated SMS permission policy](https://support.google.com/googleplay/android-developer/answer/17225965)
- [Android: permissions used only in default handlers](https://developer.android.com/guide/topics/permissions/default-handlers)
- [Apple Developer Forums: accessing incoming SMS](https://developer.apple.com/forums/thread/804040)
- [Android 15 sensitive-notification redaction](https://www.androidauthority.com/android-15-sensitive-notifications-3416414/)
- [Walls Have Ears: notification listener usage in Android apps (ACM)](https://dl.acm.org/doi/10.1145/3728898)
