# SD_CARD Backup — Pre-Release Audit

**Date:** 2026-08-05
**Commit audited:** `4d1caa9`
**Scope:** Full correctness + security review ahead of Google Play deployment.

**Build status at time of audit:** `assembleDebug` succeeds. No compile errors.
Every issue below is a runtime, security, or Play-policy problem.

### Local build notes
- JDK 25 breaks the AGP toolchain. Build with JDK 21:
  `JAVA_HOME=C:\Users\mures\.jdks\liberica-full-21.0.11`
- `local.properties` is gitignored and absent — set
  `ANDROID_HOME=C:\Users\mures\AppData\Local\Android\Sdk` or recreate the file.

---

## Suggested implementation order

Work in these four batches, testing on a real device with a real SD card between each.

| Batch | Goal | Items | Status |
|---|---|---|---|
| **1** | Make it actually work on a real card | #12, #13, #17 (+#14, #16, #18, #19) | ✅ done, **untested on device** |
| **2** | Close the data-exposure holes | #6, #7, #10 (+#9) | ✅ done, **untested on device** |
| **3** | Delete unused / broken attack surface | #4, #8 | ✅ done, **untested on device** |
| **4** | Clear the Play Console gate | #1 ✅ · #2 ✅ · #3 ◐ · #5 ◐ | code done; two manual steps |
| **5** | Polish & hygiene | #15, #20, #21, #22, hygiene list | ✅ done, **untested on device** |

**All 22 items are resolved in code. Two require action only you can take** — both are walked
through step by step in **[`RELEASE.md`](RELEASE.md)**:

- **#3** — generate the signing key (`RELEASE.md` step 1). Involves passwords.
- **#5** — host [`PRIVACY.md`](PRIVACY.md) at a public URL and submit the Data Safety form
  (`RELEASE.md` steps 2–3). Both are drafted; they need hosting and a contact email.

**Decided and closed:** #1 → `ro.muresanianis.sdcardbackup`. #11 → no encryption.

> ⚠️ **Nothing below has been run on a real device with a real card.** Builds and unit tests pass;
> that is not the same as working. See "Not yet verified" at the end of this file.

---

## 🚫 Hard blockers — Play Console will reject the upload

### [x] 1. Package name is `com.example.*`
**File:** `app/build.gradle.kts:10`

```kotlin
applicationId = "com.example.sdcardbackup"
```

Play rejects any package starting with `com.example`. Pick a real one, e.g. `ro.yourname.sdbackup`.

> ⚠️ **This is permanent.** The `applicationId` can never be changed after the first upload. Decide carefully now.

Also update `namespace` and the Java package directory to match.

**✅ Done (2026-08-05) — renamed to `ro.muresanianis.sdcardbackup`.**

You delegated the choice, so here's the reasoning. The convention is reverse-DNS on a domain you
control, which guarantees global uniqueness. You don't have a stated domain, so I used the next
best thing: country code + your name + the app. It reads as intentional, will never collide with
anything real, and stays correct if you later register `muresanianis.ro`. I avoided
`com.github.*` (ties your identity to a platform account) and anything client-specific (see the
caveat below).

Changed: `applicationId`, `namespace`, all three source directories, the `package` line in all
eight source files, and the string literal in `ExampleInstrumentedTest`. Verified against the
merged manifest — the FileProvider authority followed automatically because it uses
`${applicationId}`, so `ro.muresanianis.sdcardbackup.fileprovider` needed no manual change.

> ⚠️ **One caveat worth raising before you upload.** This is permanent. If the app is a deliverable
> for a client who will own the Play listing under *their* account, the package should arguably
> carry their name, not yours. It costs nothing to change now and is impossible to change later.
> This is also recorded at the end of `RELEASE.md`.

---

### [x] 2. `targetSdk = 34` is below the Play minimum
**File:** `app/build.gradle.kts:12-13`

Play has required targetSdk 35 for new apps since 2025-08-31; API 36 is the announced next step. 34 is refused at upload.

- Bump `compileSdk` and `targetSdk` to 35 (verify the current floor in Play Console before submitting).
- **Re-test the UI afterward** — API 35 forces edge-to-edge layout, which will affect `activity_main.xml`'s `LinearLayout` (content will slide under the status/nav bars unless insets are handled).

**✅ Fixed (2026-08-05) — went to 36, not 35.** The requirement steps up to API 36 around
**2026-08-31**, roughly three weeks out. Shipping 35 now would mean re-submitting almost
immediately, so `compileSdk` and `targetSdk` are both **36**. SDK 36 was already installed
locally. **Confirm the current floor in Play Console before you submit** — the date is from
Google's annual cadence, not something I can check from here.

Edge-to-edge is handled: `applyWindowInsets()` in `MainActivity` adds the system-bar and
display-cutout insets on top of the layout's own 16dp padding, so spacing is unchanged where the
bars aren't. The root `LinearLayout` gained `@+id/rootLayout` for this.

> ⚠️ **Needs your eyes.** I cannot see the screen. On a device — ideally one with a notch and one
> with gesture navigation — check the title isn't under the status bar and the Share button isn't
> under the nav bar. This is the single change most likely to look wrong.

---

### [ ] 3. Release builds are signed with the debug keystore
**File:** `app/build.gradle.kts:27`

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

The debug key is a publicly known key (password `android`, present on every dev machine). Play rejects debug-signed uploads outright, and any sideloaded build is trivially re-signable by an attacker into a "legitimate update."

**Note the mismatch:** the committed APK (`app/release/SD_CARD_BACKUP.apk`) was actually signed with a real cert (`CN=Muresan Ianis`, SHA-256 `0853f167…`) via the Android Studio wizard. So the checked-in gradle config does *not* reflect how you actually build — anyone running `gradlew assembleRelease` silently gets a debug-signed artifact.

**Fix:** add a real `signingConfigs.create("release")` reading from a gitignored `keystore.properties`, and enrol in Play App Signing.

```
# keystore.properties  — MUST be gitignored
storeFile=../release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

> Your keystore is correctly **not** in the repo today. Keep it that way, and back it up somewhere safe — losing it means you can never update the app.

**◐ Half done (2026-08-05) — the build side is wired, the keystore is yours to create.**

`app/build.gradle.kts` now reads signing material from a gitignored `keystore.properties`. The
debug-key fallback is gone. If the file is absent, the release build is left **unsigned** rather
than silently debug-signed — verified: `assembleRelease` currently produces
`app-release-unsigned.apk`, and `apksigner verify` reports `DOES NOT VERIFY`. It fails loudly
instead of quietly shipping a forgeable artifact.

`.gitignore` now covers `keystore.properties`, `*.jks`, `*.keystore`, and `/app/release/`.

**Your steps — I can't do these, they involve passwords.** Rather than leave you with a bare
instruction, there is now a full walkthrough in **[`RELEASE.md`](RELEASE.md) step 1**, explaining
what the key is for, why losing it is unrecoverable, and how Play App Signing removes most of that
risk. `keystore.properties.example` is a committed template — copy it to `keystore.properties`
(gitignored) and fill in the values.

Short version:

1. `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload`
2. `cp keystore.properties.example keystore.properties`, then fill it in.
3. Back up `release.jks` **and** the passwords off this machine.
4. `./gradlew assembleRelease` → output should be `app-release.apk`, not `-unsigned`.

**Also still outstanding:** the old debug-era APK is still tracked in git. `.gitignore` doesn't
untrack what's already committed:

```bash
git rm --cached -r app/release
```

I left your git index alone rather than doing this unprompted.

---

### [x] 4. `MANAGE_EXTERNAL_STORAGE` is declared but never used
**File:** `app/src/main/AndroidManifest.xml:12`

"All files access" requires a special Play declaration form and is approved only for a narrow set of app categories. **The app does not use it at all** — every file operation goes through the Storage Access Framework.

Delete all four of these:
- `MANAGE_EXTERNAL_STORAGE` (line 12) — unused, and a likely rejection
- `android:requestLegacyExternalStorage="true"` (line 19) — ignored at targetSdk 30+
- `WRITE_EXTERNAL_STORAGE` (line 8) — output goes via SAF / app-private dir
- `READ_EXTERNAL_STORAGE` (line 6) — SAF does not need it

**✅ Fixed (2026-08-05).** All four removed. **The app now declares zero permissions.** Verified
against the merged manifest — the only `uses-permission` left is
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX injects automatically and is a
signature-level self-permission, not a capability.

That's a strong position for review: an app that reads the user's files but requests no
permissions and has no `INTERNET` access is about as easy to justify as this category gets.
The `package="com.example.sdcardbackup"` attribute was dropped from the manifest at the same
time (AGP 8 ignores it and warns).

---

### [ ] 5. No privacy policy or Data Safety declaration
The app reads arbitrary user files and can transmit them off-device via the share sheet. Play requires:
- a hosted privacy policy URL, and
- a completed Data Safety form.

Declaration should read roughly: *Files and docs — collected: no; shared: no (user-initiated share only); processed on-device only.*

**◐ Drafted (2026-08-05) — you need to host it and submit the form.**

- **[`PRIVACY.md`](PRIVACY.md)** — a complete policy written to match what the code actually does:
  no collection, no transmission, no third-party SDKs, archive auto-deleted, excluded from cloud
  backup. **You must fill in the contact email** and host it at a public URL (GitHub Pages is fine).
- **[`RELEASE.md`](RELEASE.md) step 3** — the Data Safety answers in a table, with the reasoning for
  each, so you can fill the form without guessing.

The honest position: the app declares **zero permissions** and has no `INTERNET` access, so "we
collect nothing" is not a claim — it's structurally enforced. That's the easiest possible version
of this form to complete.

> ⚠️ **Two conditions on that draft.** It's written to the code as of today, and it is not legal
> advice. If the app ever gains internet access, analytics, or crash reporting, most of the
> document becomes false and must be rewritten before that update ships. If this is a commercial
> deliverable, have the client confirm it meets their obligations.

---

## 🔴 Security & privacy vulnerabilities

### [x] 6. ZIP is written to a world-readable location on Android 5–9 ⚠️ most severe
**File:** `app/src/main/java/ro/muresanianis/sdcardbackup/MainActivity.java:188`

```java
File outDir = getExternalFilesDir(null);
```

That resolves to `/sdcard/Android/data/<pkg>/files/`. With `minSdk = 21`, on **Android 5 through 9 any app holding `READ_EXTERNAL_STORAGE` can read that directory** — a complete archive of the client's entire SD card, readable by every other app on the phone.

**✅ Fixed (2026-08-05).** The audit originally suggested `getCacheDir()`, but that trades one
problem for another: internal storage is often far smaller than external, and the system can
delete cache files under storage pressure — bad for a multi-GB archive. Implemented a tiered
`getArchiveDir()` instead:

- **API 29+** → `getExternalFilesDir(null)/archives/` — private under scoped storage, and has the
  space a card backup needs. This is where the large majority of devices land.
- **API < 29** → `getFilesDir()/archives/` — internal and private. Smaller, but on those releases
  the external dir is world-readable, so capacity loses to safety.
- Falls back to internal if external is unmounted.

`purgeLegacyArchives()` also deletes archives that older builds left directly in the external
files dir, so existing installs stop being exposed on upgrade.

**Still open:** the API < 29 path may run out of room on a large card. Item #17 (free-space
check) covers this — it must land before release, or old devices will fail confusingly.

---

### [x] 7. `allowBackup="true"` with backup rules never wired up
**File:** `app/src/main/AndroidManifest.xml:15`

`backup_rules.xml` and `data_extraction_rules.xml` exist but the manifest **never references them** (`android:fullBackupContent` / `android:dataExtractionRules` are both absent). So Android's defaults apply — and Auto Backup **includes `getExternalFilesDir()` contents**.

Result: the client's ZIP of their entire SD card is silently uploaded to their Google Drive, and is extractable via `adb backup` on older devices.

For an app whose payload is by definition "all of the user's private files":

```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```

---

### [x] 8. Exported receiver lets any installed app force-launch the activity
**Files:** `app/src/main/java/ro/muresanianis/sdcardbackup/SDCardReceiver.java:12`, `AndroidManifest.xml:42`

`SDCardReceiver` is `exported="true"` with no permission guard and calls `startActivity()` on receipt. Any third-party app can broadcast a forged `ACTION_MEDIA_MOUNTED` and pop the UI to the foreground on demand — a tapjacking / UI-spoofing primitive, and a standard automated-scanner (MASVS) finding.

**And the receiver does not work anyway:**
- `context.startActivity()` from a background receiver is silently blocked by background-activity-launch restrictions (Android 10+).
- `Toast` from a background receiver is restricted on Android 12+.
- `USB_DEVICE_ATTACHED` is **not deliverable to a manifest `<receiver>` at all** — it requires an `<activity>` intent-filter with
  `<meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" android:resource="@xml/device_filter"/>`.

**Recommendation:** delete `SDCardReceiver.java` and its manifest entry entirely. If auto-launch-on-insert genuinely matters to the client, reimplement it as the USB activity-filter pattern instead.

**✅ Fixed (2026-08-05).** `SDCardReceiver.java` deleted and the manifest `<receiver>` removed. A
comment in the manifest records why, so nobody re-adds it. The only receiver left in the merged
manifest is AndroidX's `ProfileInstallReceiver`, which is permission-guarded.

**Behaviour change to be aware of:** the app no longer reacts to card insertion at all. It never
actually did — the launch was blocked and the Toast suppressed on any modern Android — so nothing
that worked has been lost. But if the client believes they saw it working on an older phone,
that's the explanation. Reimplementing it properly (activity intent-filter +
`@xml/device_filter`) is a real feature, not a bug fix; raise it separately if wanted.

---

### [x] 9. FileProvider over-exposes the shared Downloads folder
**File:** `app/src/main/res/xml/file_paths.xml:4`

```xml
<external-path name="external_files" path="Download/" />
```

The app never shares anything from `Download/`. This is dead surface area on a provider that hands out URI grants. Remove it; keep only the app-private path (see #6).

---

### [x] 10. ZIPs are never deleted
Every run leaves a full copy of the SD card on the phone permanently — storage exhaustion plus indefinite exposure of sensitive data.

**✅ Fixed (2026-08-05).** `purgeArchives()` runs on startup and again before each new archive, so
at most one archive exists at a time.

**Deliberately not done:** deleting immediately after share. `ACTION_SEND` hands a URI to another
app and gives no completion callback — deleting on return would break WhatsApp/Gmail reading the
file. Startup purge is the safe point. Worst case the archive survives until the next launch.

Delete the temp ZIP once the save/share completes, and sweep stale ZIPs from the cache dir on startup.

---

### [x] 11. No encryption option (product decision)
The app emails / WhatsApps an unencrypted archive of someone's entire card. For a client-facing tool, consider offering a password-protected AES ZIP (e.g. zip4j). **Raise this with the client** — it's their call, not a defect.

**✅ Decided (2026-08-05): no encryption. Closed, not deferred.**

Your call, and a reasonable one — it adds a dependency, a password UI, and a support burden
("I forgot the password and my backup is gone"), in exchange for protecting a file the user is
choosing to send to someone anyway.

Worth being clear about what that means, so nobody is surprised later: **the archive is a plain
ZIP.** Anyone who obtains it — a mis-sent WhatsApp message, a shared computer, a cloud account
breach — can open it and read every file from the card. The app protects the archive *on the
device* (private storage, auto-deleted, excluded from cloud backup); once shared, it is only as
protected as wherever it was sent.

That's a normal, defensible trade-off for a backup utility. Revisit only if the client's use case
turns out to involve genuinely sensitive material.

---

> ✅ **Positive finding:** the app declares **no `INTERNET` permission**, so it structurally cannot exfiltrate data on its own. Keep it that way — it's a genuine selling point and worth stating in the Play listing.

---

## 🟠 Correctness bugs (these fail on a real SD card)

### [x] 12. Duplicate ZIP entries crash the archive ⚠️ near-certain on a real card
**File:** `MainActivity.java:198-200`

```java
String entryName = resolveName(uri);   // display name ONLY — path discarded
zos.putNextEntry(new ZipEntry(entryName));
```

The folder structure is thrown away and everything is flattened into the ZIP root. The moment two files share a name in different folders — `DCIM/100CANON/IMG_0001.JPG` and `DCIM/101CANON/IMG_0001.JPG`, which camera cards **always** produce — `putNextEntry` throws `ZipException: duplicate entry` and the entire backup dies mid-way.

The `resolveName` fallback at `MainActivity.java:248` compounds it: it returns the literal string `"file"` for every unresolvable URI, so the second one collides too.

This also contradicts the requirement — *"scans everything that SD card has"* should preserve the directory tree.

**Fix:** track the relative path during the recursive scan and use `parent/child/name.ext` as the entry name.

**Sanitize it.** The card is untrusted input: strip `..` segments and leading `/`. The app never *extracts* ZIPs, so Zip Slip isn't a direct risk here — but emitting `../` entries would make *other people's* extractors vulnerable when they open your archive.

**✅ Fixed (2026-08-05).** The scan now records a relative path per file, so the ZIP mirrors the
card's folder structure and the common collisions stop existing. Naming logic lives in
`ZipNames.java`, extracted from the Activity so it can be tested without a device:

- `sanitizeSegment()` — replaces both separators and control characters (NUL, newline, `0x7F`),
  neutralises `.` / `..`, falls back to `unnamed`. **Spaces are deliberately preserved** — they're
  legal in ZIP entries and mangling them would rename the user's files.
- `unique()` — backstop that appends ` (2)`, ` (3)` … before the extension for anything that still
  collides, so a duplicate can never reach `putNextEntry` and abort the run.

**Verified by `ZipNamesTest` — 13 tests, all passing.** Covers traversal inputs
(`../../etc/passwd`, `/absolute`, `..\`), control characters, extensionless names, dots in
directory names, and a 500-way collision batch asserting every emitted name is distinct.

---

### [x] 13. The scan runs on the UI thread → guaranteed ANR
**File:** `MainActivity.java:130-168` (called from the picker callback at `:62`)

`scanDocumentDirectory` recurses with `DocumentFile.listFiles()` — one ContentResolver query per directory, and `listFiles()` is notoriously slow. A card with a few thousand files blocks the main thread for tens of seconds. Play Console vitals flags this; users see "App isn't responding."

**Fix:** move to a background thread with progress reporting.

**Performance bonus:** replace `DocumentFile` traversal with
`DocumentsContract.buildChildDocumentsUriUsingTree()` + a single cursor query per directory (selecting `DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`, `SIZE` at once). Roughly an order of magnitude faster, and it gives you the relative path needed for #12 and the size total needed for #17 for free.

**✅ Fixed (2026-08-05).** Both parts done. All scanning, archiving and saving now run on a
single-thread `ExecutorService`; the UI thread only receives results. `walkTree()` uses the
cursor approach above — one query per directory, pulling id/name/mime/size/modified at once.

Side effect worth knowing: **`DocumentFile` is no longer used anywhere**, which also removes the
undeclared-transitive-dependency risk flagged in Build hygiene.

---

### [x] 14. Race condition → `ConcurrentModificationException`
**Files:** `MainActivity.java:131` vs `MainActivity.java:197`

Nothing disables the buttons while an operation runs. Tapping "Scan" during an archive calls `fileUris.clear()` on the UI thread while the worker thread iterates the same list → crash.

**Fix:** disable all buttons for the duration of any operation; re-enable in a `finally`.

**✅ Fixed (2026-08-05).** `setBusy()` locks all three buttons for the duration of any background
operation, and the archive worker iterates a defensive copy of the file list rather than the live
one. Came along with #13 — backgrounding the scan without this would have created new races.

---

### [x] 15. Rotating the screen mid-operation crashes or wipes state
**File:** `MainActivity.java:216`

The worker thread calls `progressDialog.dismiss()` on an Activity that may already be destroyed → `IllegalArgumentException: View not attached to window manager`.

Even without the crash: `selectedRootUri`, `fileUris`, and `zipFile` are plain fields with no `onSaveInstanceState`, so any rotation sends the user back to square one.

**Fix:** move state into a `ViewModel`. On restore, re-resolve the root from `getContentResolver().getPersistedUriPermissions()` — the SAF grant survives, only the in-memory field is lost.

*(Related: `ProgressDialog` is deprecated since API 26. Replace with an inline progress bar in the layout, which also fixes the window-leak class of bug.)*

**✅ Fixed (2026-08-05).** All state and all background work moved into a new
`BackupViewModel`. A rotation no longer cancels anything — the scan or archive keeps running and
the UI re-attaches to it. `MainActivity` now holds no backup state at all; it observes LiveData
and draws. The executor is shut down in `onCleared()` (genuine teardown), not `onDestroy()`
(which fires on every rotation).

`Event.java` wraps one-shot signals — toasts, the failure dialog. Without it LiveData would
replay its last value to each new observer, so rotating after an archive finished would re-show
the failure dialog every time.

**`ProgressDialog` has since been removed** as part of the UX pass — progress is now inline in the
layout, so the deprecated API is gone entirely. See "UX pass" below.

**One thing deliberately left:**
- **Process death** (system kills the app in the background) still loses the scan. The SAF grant
  survives via `getPersistedUriPermissions()`, so a re-scan works, but the file list is gone.
  Fixing this needs `SavedStateHandle` and is a bigger job than a rotation fix — real, but rare
  enough that it wasn't worth bundling here.

---

### [x] 16. Read failures are silently swallowed — a backup tool that lies
**File:** `MainActivity.java:202-208`

```java
try (InputStream is = getContentResolver().openInputStream(uri)) {
    if (is != null) { ... }   // if null: empty entry written, no warning
}
```

Unreadable or corrupt files become **zero-byte entries** while the user is told "Archive created." For a backup product this is the worst possible failure mode — silent data loss presented as success.

**Fix:** catch per-file, collect failures, and report a summary: *"1,204 files archived, 3 failed"* — with the option to view the failed list.

**✅ Fixed (2026-08-05).** The loop now opens the stream *before* calling `putNextEntry`, so an
unreadable file produces no entry at all rather than a silent zero-byte one. Failures are
collected per-file and surfaced two ways: a count in the status text, and a dialog listing the
first 20 affected paths. The archive is still offered — but the user is told what's missing from
it before they save or send it.

---

### [x] 17. No free-space check, and partial ZIPs are left behind
**File:** `MainActivity.java:191`

Zipping a 128 GB card into phone storage will fill the device. When it throws, the partial ZIP is left on disk and the `zipFile` field still points at it — and `shareArchive()` (`MainActivity.java:252`) only checks `exists()`. **The user can share a truncated archive believing it is complete.**

**Fix:** sum the source sizes during the scan, compare against `getUsableSpace()` before starting, and `delete()` the partial file (and null the field) in the catch block.

**✅ Fixed (2026-08-05).** The scan sums `COLUMN_SIZE`, and archiving aborts up front with a
readable message ("Need about 4.2 GB, only 1.1 GB available") if `getUsableSpace()` can't cover
the total plus a 50 MB margin. Every failure path — error, cancel, out-of-space — deletes the
partial file and nulls `zipFile`, so a truncated archive can no longer be shared as if complete.

**Assumption:** the estimate treats the ZIP as roughly the size of the source. That's right for
photos and video (already compressed) and pessimistic for documents, which is the safe direction
to be wrong in.

---

### [x] 18. Unbounded recursion
**File:** `MainActivity.java:160`

No depth limit → `StackOverflowError` on pathological or deeply nested trees. Add a depth cap, or convert to an explicit work queue (which the cursor rewrite in #13 makes natural).

**✅ Fixed (2026-08-05).** Both: the traversal is now an iterative `ArrayDeque` queue (no recursion
at all), with `MAX_DEPTH = 64` as a second guard against cyclic structures. An unreadable
directory is skipped instead of aborting the whole scan.

---

### [x] 19. No way to cancel a long-running archive
`setCancelable(false)` at `MainActivity.java:179` with no cancel path. A multi-GB archive traps the user.

**✅ Fixed (2026-08-05).** Not in the agreed batch, but it came almost free once the executor
existed and the progress dialog would otherwise have trapped users for longer than before. The
dialog has a Cancel button backed by a `volatile` flag checked in both loops; cancelling deletes
the partial archive. `onDestroy` sets the same flag and shuts the executor down.

---

### [x] 20. `updateRemovableHint()` is dead code
**File:** `MainActivity.java:270-279`

It computes `hasRemovable` and discards it. The hint it promises is never shown to the user. Either implement it or delete it.

**✅ Fixed (2026-08-05).** Deleted. The initial status text now says something useful instead —
"Connect your SD card or USB stick, then tap Scan" (`@string/status_initial`). The method's
premise was wrong anyway: SAF gives no way to detect removable media without the user picking it.

---

### [x] 21. The save dialog is forced on the user
**File:** `MainActivity.java:222`

`promptSaveZip()` auto-launches the system "save file" picker after *every* archive, even when the user only wants to share. If they cancel, nothing tells them the ZIP still exists and is shareable.

**Fix:** make saving an explicit third button alongside Share.

**✅ Fixed (2026-08-05).** There's now a fourth button, **Save a Copy**, enabled alongside Share
once an archive exists. The automatic picker launch is gone — finishing an archive just reports
the result and lets the user choose.

The layout became a `ScrollView` to keep four buttons reachable on short screens once the
system-bar insets are added as padding.

---

### [x] 22. Large-file share limits (UX, not a defect)
WhatsApp (~100 MB) and Gmail (~25 MB) will silently refuse large archives. Warn the user when the ZIP exceeds a threshold and steer them toward "Save to Drive" instead.

**✅ Fixed (2026-08-05).** Sharing an archive over 100 MB (`LARGE_SHARE_THRESHOLD`) now shows a
dialog naming the actual size and both limits, offering "Share anyway" or "Save a copy instead"
— which routes straight into the Save flow from #21. Under the threshold, sharing is unchanged.

---

## 🟡 Build hygiene

- [x] **`isMinifyEnabled = false`** — ✅ R8 minify + `isShrinkResources` now on for release. Verified: `assembleRelease` succeeds and the APK is 1.5 MB. The app uses no reflection, so the default rules suffice and `proguard-rules.pro` needs nothing added. **Still smoke-test the release build on a device** — R8 problems only show at runtime.
- [x] **`androidx.documentfile` is used but never declared.** — ✅ moot: `DocumentFile` was replaced by direct `DocumentsContract` queries in #13 and is no longer used anywhere.
- [x] **`package="com.example.sdcardbackup"` in the manifest** — ✅ removed with #4.
- [x] **`app/release/SD_CARD_BACKUP.apk` is committed to git.** — ◐ `.gitignore` updated, but the file is **still tracked**; run `git rm --cached -r app/release` yourself (see #3).
- [x] **Java 8 source/target** — ✅ now `VERSION_11`.
- [x] **Hardcoded UI strings** — ✅ every user-facing string is now in `strings.xml`, including the ones that were hardcoded in Java. `android:label` uses `@string/app_name` instead of a literal.
- [ ] **`versionCode = 1`** — establish a bump policy before the first upload. Left alone: it's a release-process decision, not a code fix.
- [x] **Kotlin plugin declared but unused** — ✅ removed; the root `build.gradle.kts` is now two lines.
- [x] **`libs.versions.toml` is unused** — ✅ wired up. Both build files reference the catalog, the material version disagreement is resolved (1.11.0), and stale entries (activity, constraintlayout) were dropped.

### Known remaining lint warnings (all non-blocking)

- **`PluralsCandidate` ×4** — my new strings say "%d file(s)" rather than using `<plurals>`. Cosmetic; matters if you ever localise.
- **`UsableSpace`** — lint suggests `StorageManager#getAllocatableBytes`, which also counts clearable cache and would make the #17 check slightly less pessimistic. Minor improvement, not a defect.
- **`UnusedResources`** — `Theme_SDCardBackup` and `values-night/themes.xml` are unused: the manifest uses `Theme.AppCompat.Light.DarkActionBar` directly, so **the app has no dark mode**. Switching to the project's own theme would enable it, but it changes the look and I can't see the screen, so I left it. Your call once you've seen the app.
- **`GradleDependency` ×7** — newer AndroidX versions exist. Deliberately not bumped: upgrading dependencies at the same time as a large refactor makes any breakage impossible to attribute. Do it as its own change after device testing.

---

## Streaming redesign (2026-08-05) — supersedes parts of #6, #10, #17, #21

Asked for: *"do what it needs to do for all sorts of devices, simple stuff not very complicated."*

### The problem it solves

Staging the archive in app storage meant a size ceiling set by the *phone*, not the destination —
and on Android 5–9 the roomy external app dir is readable by every app holding
`READ_EXTERNAL_STORAGE`, so we had to use the small internal one there. A 64 GB card simply could
not be backed up on an older phone.

### What changed

**The archive is streamed directly to its final location — never staged first.**

The default flow is the simplest one: **Scan → "Create Archive" → it appears in Downloads.** No
picker, no permission, no choices. `MediaStore` lets an app own a file in a public collection from
API 29 onward, so this needs nothing granted.

Two cases divert from that, and only those two:

| Situation | What happens |
|---|---|
| Backup won't fit on the phone | Dialog up front: *"This backup is about 42 GB, the phone has 6 GB free"* → offers to pick another destination (Drive, the card, a stick) |
| Android 9 or below | Writing to a public folder there needs `WRITE_EXTERNAL_STORAGE`. Rather than request a permission the app otherwise never needs, the picker stands in. |

Streaming matters even when the destination *is* the phone: the old design needed room for the
whole archive in app storage **and then again** at the destination. Now it's written once.

| Removed entirely | Why it's gone |
|---|---|
| `getArchiveDir()` and the API-29 storage branch | nothing is written to app-private storage |
| `purgeArchives()` | there is no staged copy to clean up |
| The separate "Save a Copy" button (#21) | saving *is* archiving now |
| `FileProvider` + `file_paths.xml` | the app has no files of its own to share |

**Sharing forwards the grant** on the destination document rather than making a second copy.

**#17's free-space check came back**, in a better place: it now runs *before* any work starts and
compares the scanned size against the phone's actual free space, rather than guessing at an
app-storage directory. It only applies to the Downloads path — a destination reached through the
picker could be Drive or a network share, whose capacity can't be queried.

**Net effect on the earlier findings:**

- **#6** (world-readable archive on Android 5–9) — now structurally impossible rather than
  mitigated. The app never writes a copy of the user's files anywhere it controls.
- **#10** (archives never deleted) — nothing accumulates; the only archive is the one the user
  deliberately created, in the place they chose.
- **#17** (free-space check) — kept for the Downloads path and moved earlier, so an oversized
  backup is refused before any work happens. A failure mid-write deletes the partial document.
- **#21** — resolved differently and better: the destination is chosen *before* the work starts, so
  the user always knows where the archive is going.

`purgeLegacyArchives()` is kept and broadened — it deletes archives left in app storage by any
earlier build, including the interim one from this session, so an upgrade clears the old exposure.

### Trade-off accepted

**No free-space check on the picker path.** We can't reliably ask a `content://` destination how
much room it has — it might be Drive, a network share, or the card itself. Saving to Downloads is
checked up front; anything chosen through the picker fails during the write with the provider's
own message, and the partial file is deleted. Worth knowing if a user reports a confusing
mid-archive failure.

**Share depends on a live grant.** After process death the grant on the destination is gone, so
Share is unavailable until the archive is recreated. The scan is lost at that point anyway.

---

## Cross-device hardening (2026-08-05)

- **`android:supportsRtl="true"`** — the app was laying out left-to-right in Arabic and Hebrew.
- **All three system pickers guarded against `ActivityNotFoundException`.** Some stripped-down and
  AOSP builds ship without DocumentsUI; the app previously crashed outright on those.
- **Card pulled mid-archive is now a failure, not a success.** If every file fails to read, that
  is a removed card, not 4,000 individually bad files — reporting "archive created, 0 of 4000"
  and handing over an empty ZIP was the worst possible outcome for a backup tool.
- **minSdk stays 21.** With staging gone, the reason to raise it disappeared: the app now behaves
  identically on Android 5 as on Android 16, and card size is no longer bounded by the phone.

---

## UX pass (2026-08-05)

Requested separately from the audit: *"scan the SD card by default so the user doesn't have to
select the folder."*

### The constraint

**A true zero-touch scan is not possible.** Android's Storage Access Framework requires the user to
pick the folder at least once; there is no API to read removable storage without it. The only
bypass is `MANAGE_EXTERNAL_STORAGE` — removed in #4 precisely because Play would very likely
reject this app for requesting it.

So the goal became: make the pick **once ever** rather than once per launch, and make that one
pick a single tap.

### What was built

**Auto-scan on launch (`autoStart()`).** SAF grants persist across sessions. On startup the app
checks `getPersistedUriPermissions()`, tests each grant by querying the tree, and if one is still
readable it goes straight to scanning — no picker, no taps. Second launch onward with the same
card is fully automatic, which is what was actually being asked for.

**The picker opens on the card.** `StorageVolume.createOpenDocumentTreeIntent()` (API 29+) points
the picker directly at the removable volume, so the first-run interaction is "confirm" rather than
"navigate somewhere". Falls back to the plain picker below 29 or if no removable volume is found.

**The card is detected for messaging.** `StorageManager.getStorageVolumes()` finds a mounted
removable, non-primary volume, so the first-run text can say "Card detected. Tap Select below and
confirm — Android asks for this once, then the app scans automatically from now on," rather than a
generic instruction. This explains *why* the one-time step exists, which is the actual UX problem.

**Which card is shown.** The granted folder's label is queried and displayed ("Card: NO NAME"),
with a secondary "Use a different folder" button — so a user with two cards isn't stuck with
whichever one they picked first.

**Scan button adapts.** "Select SD Card" when nothing is granted, "Scan Again" afterwards, and it
re-scans the existing folder instead of reopening the picker.

**Inline progress replaces `ProgressDialog`.** A progress bar, message and Cancel button in the
layout itself. Removes the last deprecated API, and progress now survives rotation with the rest
of the state rather than being a separate window that had to be torn down and rebuilt.

### Trade-offs

- **Auto-scan runs on every launch.** On a very large card that's real work at startup. It's
  cancellable and shows progress, and it's what was asked for — but if it proves annoying, the
  alternative is to restore the folder and wait for a tap. One-line change in `autoStart()`.
- **Grants are never released.** A grant for an unplugged card is kept, since the user will most
  likely plug the same card back in and re-picking every time would defeat the purpose. They
  accumulate, and are revocable through Android's app settings. Not a leak — the user granted
  them — but worth knowing they persist.
- **`isRemovable() && !isPrimary()` picks the first match.** A device with both an SD card and a
  USB stick attached will pre-target whichever the system lists first. The user can still navigate
  or use "Use a different folder"; only the pre-selection is a guess.

### Needs device verification

- [ ] **Archive a card whose contents exceed the phone's free space, saving to a destination that
      is not the phone** — Google Drive, the card itself, or a second stick. This is the whole
      point of the streaming redesign and would have been impossible before.

      Note the limit that remains: saving to Downloads or anywhere on internal storage still needs
      the full archive to fit on the phone. The bytes have to land somewhere. What streaming
      removed is the *staging* copy — previously the phone needed room for the whole archive even
      when the destination was Drive, and needed it twice when the destination was also local.
- [ ] **Archive straight back onto the card itself**, or to a second USB stick.
- [ ] **Cancel mid-archive** and confirm the partial file is deleted from the destination.
- [ ] **Fill the destination** and confirm the failure message is comprehensible.
- [ ] Share works from the destination document (no FileProvider involved any more).
- [ ] First run: picker opens **on the card**, not in Documents.
- [ ] Second run with the same card still inserted: **scans automatically**, no picker.
- [ ] Launch with the card removed: falls back cleanly to the "connect a card" state, no crash.
- [ ] Remove the card *while* a scan or archive is running.
- [ ] Insert a **different** card: "Use a different folder" grants it, and the label updates.
- [ ] Cancel button works from the inline progress area, for both scan and archive.

---

## Not yet verified

**Run the unit tests with:**

```bash
./gradlew testDebugUnitTest
```

### Device smoke test — all five batches

**Nothing below has run on real hardware.** Builds, R8 release build, lint and 13 unit tests all
pass; that is not the same as working. In order:

**Added after batches 3–5:**

- [ ] **Rotate the device mid-scan and mid-archive.** This is the #15 fix and the biggest
      behavioural change: the operation should keep running and the progress dialog should
      reappear, not restart or crash.
- [ ] **Rotate after an archive completes.** The failure dialog and toasts must *not* re-appear —
      that's what `Event` guards against.
- [ ] **Check the layout on a notched device and one with gesture navigation** (#2 edge-to-edge).
      Title clear of the status bar, Share button clear of the nav bar.
- [ ] **Check all four buttons are reachable** on a short screen — the layout became a ScrollView.
- [ ] **Test the Save a Copy button** separately from Share (#21) — the save picker no longer
      opens automatically.
- [ ] **Smoke-test the release build, not just debug.** R8 minification is now on and its problems
      only appear at runtime.
- [ ] Confirm the app still appears correctly named in the launcher (label moved to `@string/app_name`).

**From batches 1 & 2:**

- [ ] Scan a card with **nested folders** — confirm the ZIP preserves the tree, not a flat dump.
- [ ] Scan a card with **duplicate filenames across folders** (any camera card: `DCIM/100*/IMG_0001.JPG`
      plus `DCIM/101*/IMG_0001.JPG`). This is the exact case that used to abort the archive.
- [ ] Scan a card with **several thousand files** — confirm no ANR, and that the progress dialog counts up.
- [ ] Press **Cancel** mid-archive — confirm it stops and leaves no ZIP behind.
- [ ] Fill the phone, then archive a large card — confirm the **out-of-space message** appears
      *before* any work starts, not after.
- [ ] Share a ZIP, force-close the app, relaunch — confirm the **old archive is gone** and a fresh
      scan/archive/share still works.
- [ ] Test on an **Android 9 or older device** if the client has one — that's the branch that now
      writes to internal storage and is most likely to hit the space limit.

### Still needs hardware

These need a physical device with a real SD card / OTG adapter:

- [ ] Behaviour when the card is physically removed mid-archive.
- [ ] Behaviour on exFAT vs FAT32 cards (filename encoding edge cases).
- [ ] Actual throughput on a large card — informs whether a foreground service is needed to survive the app being backgrounded.
- [ ] Whether the OTG adapter + card even appears in the SAF picker on the client's specific device model.
