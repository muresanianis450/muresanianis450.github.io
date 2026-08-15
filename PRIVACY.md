# Privacy Policy — SD Card Backup

> The published version of this policy is [`privacy.html`](privacy.html), served at
> <https://muresanianis450.github.io/SD_CARD/privacy.html>. **If you change one, change both.**

**Last updated:** 5 August 2026

---

## The short version

SD Card Backup does not collect, transmit, or store any personal data. Everything it does happens
on your device. The app has no internet access.

## What the app does

SD Card Backup reads the files on a memory card or USB drive you connect to your device, combines
them into a single ZIP archive, and lets you save that archive or send it somewhere yourself.

## What data the app accesses

The app reads the files in the folder **you explicitly select**. It cannot see anything else.

Android's Storage Access Framework requires you to pick that folder yourself, through a system
screen the app does not control. The app has no permission to browse your device's storage on its
own — it requests **no permissions at all**.

The files are read only to copy them into the archive.

## What data leaves your device

**None, unless you send it yourself.**

The app has no `INTERNET` permission. It is technically incapable of uploading, transmitting, or
reporting anything — including analytics, crash reports, diagnostics, or usage statistics. There
are no third-party SDKs, no advertising, and no tracking of any kind.

When you tap **Share**, Android's standard share menu opens and you choose an app — email,
WhatsApp, Drive, or anything else. At that moment the archive is handed to the app you picked, and
what happens next is governed by **that** app's privacy policy, not this one. SD Card Backup is
not involved and receives nothing back.

## Where the archive is stored

The archive is written directly to the destination you choose. Nothing is staged or copied
anywhere first.

- **By default** it goes to your device's **Downloads** folder, where you can find it with any
  file manager.
- **If the backup is too large for your device**, or on Android 9 and older, the app asks you to
  choose a destination instead — the card itself, a second drive, Google Drive, or anywhere else
  your device offers.

The archive is an ordinary file that belongs to you. The app does not hide it, track it, or
delete it — move or delete it whenever you like. If an archive fails part-way through or you
cancel it, the incomplete file is removed automatically.

The app itself is excluded from Android's automatic cloud backup, so nothing belonging to the app
is uploaded to your Google account. Be aware, though, that a file in your Downloads folder is a
normal file on your device: if you have separate backup or sync software installed, that software
may treat the archive like any other file. That is outside this app's control.

Earlier versions of the app kept a copy in its own private storage. This version deletes any such
leftover copies the first time you open it.

## Children

The app collects no data from anyone, including children.

## Your rights

Because no personal data is collected, transmitted, or stored by us, there is nothing for us to
access, correct, export, or delete on your behalf. Any data involved stays on your device and
under your control, and uninstalling the app removes any archive it created.

For users in the EU/EEA: the app performs no processing of personal data by us as a controller, so
the GDPR rights of access, rectification, erasure, and portability have nothing to act on at our
end. Files on your own device remain entirely yours.

## Changes to this policy

If the app's behaviour changes in a way that affects privacy, this policy will be updated and the
date at the top changed.

## Contact

Questions about this policy:

**muresanianis450@gmail.com**
