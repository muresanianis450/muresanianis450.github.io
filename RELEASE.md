# Releasing to Google Play

Everything here is a step **you** have to take — it involves passwords, a Google account, and
legal declarations. The code side is already done.

Work through it in order.

---

## 1. Create your signing key

An Android app is signed with a private key. Play uses that signature to prove that an update
genuinely comes from you. Right now this project has **no key**, which is why
`gradlew assembleRelease` currently produces `app-release-unsigned.apk`.

### Why this matters more than it looks

**If you lose this key, you can never update the app again.** Not "it's annoying" — the listing
becomes permanently frozen and you'd have to publish a new app under a new package name and ask
every user to reinstall.

Google's **Play App Signing** takes most of this risk away, and you should use it. With it enabled,
Google holds the actual app signing key; the key you create below becomes your *upload* key, which
Google can reset for you if you lose it. It is opt-in during your first release and is the default
for new apps.

### Generate it

Run this from the repo root. `keytool` ships with the JDK.

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

It will ask for:

- **A keystore password** — pick a strong one and put it in your password manager now, not later.
- **Your name, organisation, city, country** — these are embedded in the certificate. Users don't
  see them. Your existing debug-era cert used `CN=Muresan Ianis, L=Cluj-Napoca, C=RO`, which is a
  reasonable pattern to repeat.
- **A key password** — pressing Enter reuses the keystore password, which is fine.

`-validity 10000` is roughly 27 years. Play requires the certificate to remain valid past 2033, so
don't lower it.

### Wire it up

```bash
cp keystore.properties.example keystore.properties
```

Then edit `keystore.properties` with your real values. Both it and `release.jks` are gitignored —
verify with `git status` that neither shows up before you commit anything.

### Back it up

Copy `release.jks` **and** the passwords somewhere off this machine — a password manager, an
encrypted drive, wherever you keep things you cannot lose. A disk failure should not be able to end
this app.

### Confirm it worked

```bash
./gradlew assembleRelease
```

The output should now be `app/build/outputs/apk/release/app-release.apk` — note the absence of
`-unsigned`. Verify the signature:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

You should see your name, and no `DOES NOT VERIFY`.

---

## 2. Host the privacy policy

Play requires a **publicly reachable URL** — not a PDF, not a file in the repo.

[`PRIVACY.md`](PRIVACY.md) is a complete draft written to match what the code actually does.

1. Read it. Fill in the contact email at the bottom.
2. Host it anywhere public and stable. GitHub Pages is free and adequate; so is a page on any site
   you already own.
3. Keep the URL — you'll paste it into the Play Console.

> If the app ever gains internet access, analytics, or crash reporting, most of that document
> becomes false and must be rewritten before the update ships.

---

## 3. Fill in the Data Safety form

Play asks this separately from the privacy policy, and the two must agree.

Based on what the code actually does, the answers are:

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any required user data types? | **No** | The app has no `INTERNET` permission and no third-party SDKs. Nothing can leave the device. |
| Is all user data encrypted in transit? | **N/A** — no data in transit | Nothing is transmitted. |
| Do you provide a way for users to request data deletion? | **N/A** — no data collected | Uninstalling removes the archive. |
| Does your app contain ads? | **No** | |
| Does your app use third-party analytics or crash reporting? | **No** | |

**On the share feature:** handing a file to another app through the Android share sheet is a
user-initiated transfer, not collection or sharing *by your app* — Play's definitions turn on data
your app transmits, and yours transmits nothing. This is the correct answer, but the form's wording
changes periodically, so read the questions as they appear rather than assuming these labels match.

The strong position here: **the app declares zero permissions**. If anything is ever queried in
review, that's the answer.

---

## 4. Before you upload

- [ ] Signing works — `app-release.apk`, not `-unsigned` (step 1)
- [ ] Privacy policy is live at a public URL (step 2)
- [ ] Data Safety form completed (step 3)
- [ ] **Confirm the target API requirement in Play Console.** The app is built against API 36. The
      floor was 35 and steps up to 36 around 2026-08-31 — check what it actually says today.
- [ ] **Test the release build on a real device**, not just debug. R8 minification is enabled and
      its failures only appear at runtime.
- [ ] Work through the device smoke-test list in [`AUDIT.md`](AUDIT.md).
- [ ] Untrack the old committed APK: `git rm --cached -r app/release`
- [ ] Store listing: title, short and full description, screenshots, feature graphic, content
      rating questionnaire.

---

## Package name — already decided

`ro.muresanianis.sdcardbackup`

This is permanent and cannot be changed after the first upload. If this app is being delivered to a
client who will own the Play listing under their own account, tell me **before** you upload — the
package should arguably carry their name instead, and it is free to change now and impossible
later.
