package ro.muresanianis.sdcardbackup;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * All scanning and archiving state.
 *
 * This lives outside the Activity so a rotation (or any configuration change)
 * doesn't cancel a running backup or throw away a scan of several thousand files.
 * The Activity is a thin renderer over the LiveData exposed here.
 *
 * The archive is streamed straight to the destination the user picks — it is never
 * staged in the app's own storage. That keeps it working on every Android version
 * and any card size: nothing has to fit on the phone, and no copy of the user's
 * files is ever left behind for another app to find.
 */
public class BackupViewModel extends AndroidViewModel {

    /** What the app is doing right now. Drives every button's enabled state. */
    enum Phase { IDLE, SCANNING, ARCHIVING }

    /** Guard against pathological or cyclic directory structures. */
    private static final int MAX_DEPTH = 64;

    /** Minimum gap between progress updates posted to the UI thread. */
    private static final long PROGRESS_INTERVAL_MS = 100L;

    /** Headroom left free on the phone after writing the archive to Downloads. */
    private static final long FREE_SPACE_MARGIN = 200L * 1024 * 1024;

    /** Above this, most messaging apps refuse the attachment outright. */
    static final long LARGE_SHARE_THRESHOLD = 100L * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelRequested = false;

    private final MutableLiveData<Phase> phase = new MutableLiveData<>(Phase.IDLE);
    private final MutableLiveData<String> progress = new MutableLiveData<>("");
    private final MutableLiveData<String> status = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> hasFiles = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> hasArchive = new MutableLiveData<>(false);
    private final MutableLiveData<String> folderName = new MutableLiveData<>(null);

    private final MutableLiveData<Event<String>> toast = new MutableLiveData<>();
    private final MutableLiveData<Event<List<String>>> readFailures = new MutableLiveData<>();

    private boolean autoStartAttempted = false;
    private Uri selectedRootUri;
    private final List<ScannedFile> scannedFiles = new ArrayList<>();

    private Uri archiveUri;
    private long archiveBytes;

    public BackupViewModel(@NonNull Application application) {
        super(application);

        // Older builds of this app staged archives inside app storage, where on
        // Android 5-9 any app holding READ_EXTERNAL_STORAGE could read them. Nothing
        // writes there any more, so clear out anything an upgrade left behind.
        worker.execute(this::purgeLegacyArchives);
    }

    // --- exposed state -----------------------------------------------------

    LiveData<Phase> getPhase() {
        return phase;
    }

    LiveData<String> getProgress() {
        return progress;
    }

    LiveData<String> getStatus() {
        return status;
    }

    LiveData<Boolean> getHasFiles() {
        return hasFiles;
    }

    LiveData<Boolean> getHasArchive() {
        return hasArchive;
    }

    /** Label of the granted folder, or null if none is selected yet. */
    LiveData<String> getFolderName() {
        return folderName;
    }

    boolean hasFolder() {
        return selectedRootUri != null;
    }

    LiveData<Event<String>> getToast() {
        return toast;
    }

    LiveData<Event<List<String>>> getReadFailures() {
        return readFailures;
    }

    boolean isBusy() {
        return phase.getValue() != Phase.IDLE;
    }

    /** Where the finished archive was written, for sharing. */
    Uri getArchiveUri() {
        return archiveUri;
    }

    long getArchiveSize() {
        return archiveBytes;
    }

    /** Name to suggest in the "create document" dialog. */
    String suggestedArchiveName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "SD_USB_Backup_" + timestamp + ".zip";
    }

    void cancel() {
        cancelRequested = true;
    }

    @Override
    protected void onCleared() {
        cancelRequested = true;
        worker.shutdownNow();
        super.onCleared();
    }

    // --- scanning ----------------------------------------------------------

    /**
     * Called once per process. If the user has already granted access to a card in
     * a previous session and that card is still plugged in, the grant survives — so
     * we can go straight to scanning without asking again.
     *
     * Android has no way to reach removable storage without that first manual pick
     * (short of MANAGE_EXTERNAL_STORAGE, which Play would reject here). This makes
     * it a once-ever step rather than a once-per-launch one.
     */
    void autoStart(boolean removableDetected) {
        if (autoStartAttempted) return;
        autoStartAttempted = true;

        worker.execute(() -> {
            final Uri restored = findUsableGrant();
            final String name = restored != null ? queryDisplayName(restored) : null;

            postToMain(() -> {
                if (restored != null) {
                    selectedRootUri = restored;
                    folderName.setValue(name);
                    scan();
                } else {
                    status.setValue(getApplication().getString(removableDetected
                            ? R.string.status_card_detected
                            : R.string.status_initial));
                }
            });
        });
    }

    void onRootPicked(Uri treeUri, int takeFlags) {
        selectedRootUri = treeUri;

        try {
            getApplication().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (SecurityException ignored) {
            // a non-persistable grant still works for this session
        }

        worker.execute(() -> {
            final String name = queryDisplayName(treeUri);
            postToMain(() -> folderName.setValue(name));
        });

        scan();
    }

    /** Re-scans the folder already granted, without reopening the picker. */
    void rescan() {
        scan();
    }

    private void scan() {
        if (selectedRootUri == null || isBusy()) return;

        cancelRequested = false;
        scannedFiles.clear();
        archiveUri = null;
        hasFiles.setValue(false);
        hasArchive.setValue(false);

        phase.setValue(Phase.SCANNING);
        progress.setValue(getApplication().getString(R.string.progress_scanning));

        worker.execute(() -> {
            final List<ScannedFile> found = new ArrayList<>();
            String error = null;

            try {
                walkTree(selectedRootUri, found);
            } catch (Exception e) {
                error = describe(e);
            }

            final String finalError = error;

            postToMain(() -> {
                phase.setValue(Phase.IDLE);

                if (finalError != null) {
                    status.setValue(getApplication().getString(R.string.status_scan_failed, finalError));
                    return;
                }

                if (cancelRequested) {
                    status.setValue(getApplication().getString(R.string.status_scan_cancelled));
                    return;
                }

                scannedFiles.clear();
                scannedFiles.addAll(found);

                if (scannedFiles.isEmpty()) {
                    status.setValue(getApplication().getString(R.string.status_no_files));
                } else {
                    status.setValue(getApplication().getString(
                            R.string.status_found,
                            scannedFiles.size(),
                            formatSize(totalSize(scannedFiles))));
                    hasFiles.setValue(true);
                }
            });
        });
    }

    /**
     * Walks the whole tree breadth-first, recording each file with the relative path
     * it should keep inside the ZIP.
     *
     * Queries DocumentsContract directly rather than using DocumentFile: this is one
     * cursor per directory instead of one query per attribute per file, which is the
     * difference between seconds and minutes on a card with a few thousand files.
     */
    private void walkTree(Uri treeUri, List<ScannedFile> out) {
        final String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        ContentResolver resolver = getApplication().getContentResolver();

        Deque<PendingDir> queue = new ArrayDeque<>();
        queue.add(new PendingDir(DocumentsContract.getTreeDocumentId(treeUri), "", 0));

        while (!queue.isEmpty()) {
            if (stopping()) return;

            PendingDir dir = queue.poll();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.documentId);

            try (Cursor c = resolver.query(childrenUri, projection, null, null, null)) {
                if (c == null) continue;

                while (c.moveToNext()) {
                    if (stopping()) return;

                    String docId = c.getString(0);
                    String name = ZipNames.sanitizeSegment(c.getString(1));
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0L : c.getLong(3);
                    long modified = c.isNull(4) ? 0L : c.getLong(4);

                    String path = dir.path.isEmpty() ? name : dir.path + "/" + name;

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        // depth cap keeps a cyclic or absurdly nested card from running away
                        if (dir.depth < MAX_DEPTH) {
                            queue.add(new PendingDir(docId, path, dir.depth + 1));
                        }
                    } else {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        out.add(new ScannedFile(fileUri, path, size, modified));
                    }
                }
            } catch (Exception ignored) {
                // an unreadable directory shouldn't abort the whole scan
            }
        }
    }

    private static final class PendingDir {
        final String documentId;
        final String path;
        final int depth;

        PendingDir(String documentId, String path, int depth) {
            this.documentId = documentId;
            this.path = path;
            this.depth = depth;
        }
    }

    // --- archiving ---------------------------------------------------------

    /** Rough size of the archive to come. Media barely compresses, so this is close. */
    long estimatedArchiveSize() {
        return totalSize(scannedFiles);
    }

    /**
     * Whether the archive is likely to fit in the phone's own storage.
     *
     * Uses the primary external volume, which is where Downloads lives. The estimate
     * assumes no compression — true for photos and video, pessimistic for documents,
     * which is the safe direction to be wrong in.
     */
    boolean fitsOnPhone() {
        File probe = getApplication().getExternalFilesDir(null);
        if (probe == null) probe = getApplication().getFilesDir();

        return probe.getUsableSpace() > estimatedArchiveSize() + FREE_SPACE_MARGIN;
    }

    /**
     * The default path: builds the archive and drops it in the phone's Downloads
     * folder, with no picker and no permissions.
     *
     * MediaStore lets an app own a file in a public collection from API 29 onward.
     * Below that it needs WRITE_EXTERNAL_STORAGE, so older devices fall back to the
     * picker instead of the app asking for a permission it otherwise never needs.
     */
    void archiveToDownloads() {
        if (scannedFiles.isEmpty() || isBusy()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

        beginArchive();

        worker.execute(() -> {
            ContentResolver resolver = getApplication().getContentResolver();
            Uri item = null;

            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, suggestedArchiveName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                item = resolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);

                if (item == null) throw new IOException("Could not create the file in Downloads");

                runArchive(item, true);

            } catch (Exception e) {
                if (item != null) deleteDocumentQuietly(item);
                reportArchiveFailure(e);
            }
        });
    }

    /**
     * Streams the archive into a document the user picked through the system picker.
     * Used when Downloads isn't available or isn't big enough.
     */
    void archiveTo(Uri destination) {
        if (scannedFiles.isEmpty() || isBusy()) return;

        beginArchive();
        worker.execute(() -> runArchive(destination, false));
    }

    private void beginArchive() {
        cancelRequested = false;
        phase.setValue(Phase.ARCHIVING);
        progress.setValue(getApplication().getString(R.string.progress_archiving_start));
        archiveUri = null;
        hasArchive.setValue(false);
    }

    /**
     * Writes every scanned file into {@code destination} as a ZIP.
     *
     * @param pendingMediaStore true if the destination is a MediaStore item that must
     *                          be un-flagged as pending before other apps can see it
     */
    private void runArchive(Uri destination, boolean pendingMediaStore) {
        final List<ScannedFile> batch = new ArrayList<>(scannedFiles);

        {
            ContentResolver resolver = getApplication().getContentResolver();

            List<String> failures = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            int written = 0;
            long produced = 0;

            try {
                OutputStream raw = resolver.openOutputStream(destination, "w");
                if (raw == null) throw new IOException("Could not open the destination file");

                CountingOutputStream counter = new CountingOutputStream(raw);

                try (ZipOutputStream zos = new ZipOutputStream(counter)) {

                    byte[] buffer = new byte[8192];
                    long lastProgressPost = 0L;

                    for (int i = 0; i < batch.size(); i++) {
                        if (stopping()) throw new InterruptedException("cancelled");

                        ScannedFile f = batch.get(i);

                        // Throttled: a card with 20k files would otherwise post 20k
                        // updates and jank the main thread we're trying to protect.
                        long now = System.currentTimeMillis();
                        if (now - lastProgressPost >= PROGRESS_INTERVAL_MS) {
                            lastProgressPost = now;
                            final int current = i + 1;
                            final int total = batch.size();
                            postToMain(() -> progress.setValue(getApplication().getString(
                                    R.string.progress_archiving, current, total)));
                        }

                        // Open first. A file that can't be read must not produce a
                        // silent zero-byte entry in what the user believes is a backup.
                        try (InputStream is = resolver.openInputStream(f.uri)) {
                            if (is == null) {
                                failures.add(f.relativePath);
                                continue;
                            }

                            ZipEntry entry = new ZipEntry(ZipNames.unique(usedNames, f.relativePath));
                            if (f.lastModified > 0) entry.setTime(f.lastModified);

                            zos.putNextEntry(entry);

                            int len;
                            while ((len = is.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }

                            zos.closeEntry();
                            written++;

                        } catch (IOException e) {
                            failures.add(f.relativePath);
                        }
                    }
                }

                produced = counter.count;

                // Every single file failing almost always means the card was pulled
                // mid-run, not that the files are individually bad. Reporting that
                // as "archive created, 0 of 4000 files" would be actively misleading.
                if (written == 0 && !batch.isEmpty()) {
                    throw new IOException(getApplication().getString(R.string.error_all_failed));
                }

                // Until this clears, the file is invisible to every other app.
                if (pendingMediaStore) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(destination, done, null, null);
                }

                final int okCount = written;
                final long size = produced;
                final List<String> failed = failures;
                final String where = pendingMediaStore
                        ? getApplication().getString(R.string.location_downloads)
                        : getApplication().getString(R.string.location_chosen);

                postToMain(() -> {
                    archiveUri = destination;
                    archiveBytes = size;
                    phase.setValue(Phase.IDLE);
                    hasArchive.setValue(true);

                    String summary = getApplication().getString(
                            R.string.status_archive_done,
                            queryDocumentName(destination),
                            where,
                            formatSize(size),
                            okCount,
                            batch.size());

                    if (failed.isEmpty()) {
                        status.setValue(summary);
                        toast.setValue(new Event<>(getApplication().getString(R.string.toast_archive_created)));
                    } else {
                        status.setValue(summary + "\n" + getApplication().getString(
                                R.string.status_archive_failures, failed.size()));
                        readFailures.setValue(new Event<>(Collections.unmodifiableList(failed)));
                    }
                });

            } catch (InterruptedException e) {
                // never leave a truncated archive the user could mistake for a good one
                deleteDocumentQuietly(destination);
                postToMain(() -> {
                    archiveUri = null;
                    phase.setValue(Phase.IDLE);
                    hasArchive.setValue(false);
                    status.setValue(getApplication().getString(R.string.status_archive_cancelled));
                });

            } catch (Exception e) {
                deleteDocumentQuietly(destination);
                reportArchiveFailure(e);
            }
        }
    }

    private void reportArchiveFailure(Exception e) {
        final String msg = describe(e);
        postToMain(() -> {
            archiveUri = null;
            phase.setValue(Phase.IDLE);
            hasArchive.setValue(false);
            status.setValue(getApplication().getString(R.string.status_archive_failed, msg));
        });
    }

    /** Counts what actually reached the destination, since the ZIP is compressed. */
    private static final class CountingOutputStream extends FilterOutputStream {
        long count = 0;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        // FilterOutputStream's default writes byte-by-byte through write(int),
        // which would be catastrophically slow here.
        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /**
     * Best-effort removal of a half-written archive. Not every provider supports
     * deletion; if it fails, the file stays but is never reported as usable.
     */
    private void deleteDocumentQuietly(Uri document) {
        try {
            DocumentsContract.deleteDocument(getApplication().getContentResolver(), document);
        } catch (Exception ignored) {
            // nothing sensible to do, and nothing worth interrupting the user for
        }
    }

    // --- SAF helpers -------------------------------------------------------

    /**
     * Returns a previously granted folder that is readable right now, or null.
     *
     * A grant for a card that has since been unplugged is kept, not released — the
     * user will most likely plug the same card back in, and re-picking it every time
     * would defeat the point.
     */
    private Uri findUsableGrant() {
        try {
            for (UriPermission p : getApplication().getContentResolver().getPersistedUriPermissions()) {
                if (!p.isReadPermission()) continue;
                if (isTreeReadable(p.getUri())) return p.getUri();
            }
        } catch (Exception ignored) {
            // treat any failure as "no usable grant" and fall back to asking
        }
        return null;
    }

    /** Cheapest possible check that the volume is still mounted and readable. */
    private boolean isTreeReadable(Uri treeUri) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            try (Cursor c = getApplication().getContentResolver().query(
                    children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return c != null;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** The card's own label, so the UI can say which folder it is working on. */
    private String queryDisplayName(Uri treeUri) {
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return queryDocumentName(docUri);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Display name of any document URI, or a sensible fallback. */
    private String queryDocumentName(Uri documentUri) {
        try (Cursor c = getApplication().getContentResolver().query(
                documentUri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {

            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
            // a missing label is cosmetic; carry on without one
        }
        return getApplication().getString(R.string.archive_unnamed);
    }

    /**
     * Removes archives written by older builds, which staged them inside app storage.
     * On API < 29 the external app dir is readable by any app holding
     * READ_EXTERNAL_STORAGE, so anything still sitting there is exposed.
     */
    private void purgeLegacyArchives() {
        File[] roots = {
                getApplication().getExternalFilesDir(null),
                getApplication().getFilesDir()
        };

        for (File root : roots) {
            if (root == null) continue;

            deleteArchivesIn(root);
            deleteArchivesIn(new File(root, "archives"));
        }
    }

    private static void deleteArchivesIn(File dir) {
        if (dir == null || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("SD_USB_Backup_") && f.getName().endsWith(".zip")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private boolean stopping() {
        return cancelRequested || Thread.currentThread().isInterrupted();
    }

    private void postToMain(Runnable r) {
        if (Thread.currentThread().isInterrupted()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static long totalSize(List<ScannedFile> files) {
        long total = 0;
        for (ScannedFile f : files) total += f.size;
        return total;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
