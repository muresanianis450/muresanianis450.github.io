package ro.muresanianis.sdcardbackup;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.util.List;

/**
 * Thin renderer over {@link BackupViewModel}.
 *
 * Deliberately holds no backup state of its own: everything that must survive a
 * rotation lives in the ViewModel, and this class only observes and draws.
 */
public class MainActivity extends AppCompatActivity {

    private BackupViewModel vm;

    private TextView statusText, folderText, progressText;
    private View progressGroup;
    private Button scanButton, archiveButton, shareButton, cancelButton, changeFolderButton;

    // --- Pick SD/USB folder (SAF) ---
    private final ActivityResultLauncher<Intent> usbPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {

                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, R.string.toast_no_folder, Toast.LENGTH_SHORT).show();
                    return;
                }

                Uri treeUri = result.getData().getData();
                if (treeUri == null) return;

                int takeFlags = result.getData().getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                vm.onRootPicked(treeUri, takeFlags);
            });

    // --- Choose where the archive is written, then stream it straight there ---
    private final ActivityResultLauncher<Intent> createArchiveLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {

                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                Uri dest = result.getData().getData();
                if (dest != null) vm.archiveTo(dest);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyWindowInsets();

        statusText = findViewById(R.id.statusText);
        folderText = findViewById(R.id.folderText);
        progressText = findViewById(R.id.progressText);
        progressGroup = findViewById(R.id.progressGroup);
        scanButton = findViewById(R.id.scanButton);
        archiveButton = findViewById(R.id.archiveButton);
        shareButton = findViewById(R.id.shareButton);
        cancelButton = findViewById(R.id.cancelButton);
        changeFolderButton = findViewById(R.id.changeFolderButton);

        vm = new ViewModelProvider(this).get(BackupViewModel.class);

        // Scan re-uses the granted folder when there is one, and only falls back
        // to the picker the first time.
        scanButton.setOnClickListener(v -> {
            if (vm.hasFolder()) {
                vm.rescan();
            } else {
                openFolderPicker();
            }
        });

        changeFolderButton.setOnClickListener(v -> openFolderPicker());
        archiveButton.setOnClickListener(v -> startArchive());
        shareButton.setOnClickListener(v -> shareArchive());
        cancelButton.setOnClickListener(v -> vm.cancel());

        observeViewModel();

        // If a card was granted in an earlier session and is still plugged in, this
        // scans it immediately — no picker, no taps.
        vm.autoStart(hasRemovableVolume());
    }

    private void observeViewModel() {
        vm.getStatus().observe(this, text -> {
            if (text != null) statusText.setText(text);
        });

        vm.getFolderName().observe(this, name -> {
            boolean known = vm.hasFolder();

            folderText.setVisibility(known ? View.VISIBLE : View.GONE);
            changeFolderButton.setVisibility(known ? View.VISIBLE : View.GONE);
            scanButton.setText(known ? R.string.button_rescan : R.string.button_select_card);

            if (known) {
                folderText.setText(name != null
                        ? getString(R.string.folder_label, name)
                        : getString(R.string.folder_label_unnamed));
            }
        });

        vm.getPhase().observe(this, phase -> {
            boolean busy = phase != BackupViewModel.Phase.IDLE;

            progressGroup.setVisibility(busy ? View.VISIBLE : View.GONE);

            scanButton.setEnabled(!busy);
            changeFolderButton.setEnabled(!busy);
            archiveButton.setEnabled(!busy && Boolean.TRUE.equals(vm.getHasFiles().getValue()));
            shareButton.setEnabled(!busy && Boolean.TRUE.equals(vm.getHasArchive().getValue()));
        });

        vm.getProgress().observe(this, message -> {
            if (message != null) progressText.setText(message);
        });

        vm.getHasFiles().observe(this, has ->
                archiveButton.setEnabled(!vm.isBusy() && Boolean.TRUE.equals(has)));

        vm.getHasArchive().observe(this, has ->
                shareButton.setEnabled(!vm.isBusy() && Boolean.TRUE.equals(has)));

        vm.getToast().observe(this, event -> {
            String message = event == null ? null : event.getIfNotHandled();
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });

        vm.getReadFailures().observe(this, event -> {
            List<String> failed = event == null ? null : event.getIfNotHandled();
            if (failed != null && !failed.isEmpty()) showFailureReport(failed);
        });
    }

    /**
     * From targetSdk 35 onward the system draws the app edge-to-edge and no longer
     * insets it automatically, so without this the title sits under the status bar
     * and the bottom button under the navigation bar.
     *
     * The layout's own 16dp padding is added on top of the system insets rather than
     * replaced, so spacing stays the same everywhere the bars aren't.
     */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.rootLayout);
        final int basePadding = root.getPaddingLeft();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

            v.setPadding(
                    bars.left + basePadding,
                    bars.top + basePadding,
                    bars.right + basePadding,
                    bars.bottom + basePadding);

            return WindowInsetsCompat.CONSUMED;
        });
    }

    // --- folder picking ----------------------------------------------------

    private void openFolderPicker() {
        // A few stripped-down / AOSP builds ship without DocumentsUI. Without this
        // guard the app just crashes on those devices.
        try {
            usbPickerLauncher.launch(buildPickerIntent());
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Opens the picker already pointed at the removable card where possible, so the
     * user only has to confirm instead of navigating to it.
     */
    private Intent buildPickerIntent() {
        Intent intent = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            StorageVolume volume = findRemovableVolume();
            if (volume != null) intent = volume.createOpenDocumentTreeIntent();
        }

        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        return intent;
    }

    /** Below API 29 we can't enumerate volumes, so assume a card may be present. */
    private boolean hasRemovableVolume() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || findRemovableVolume() != null;
    }

    /** The first mounted removable, non-primary volume — i.e. the card or stick. */
    private StorageVolume findRemovableVolume() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;

        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return null;

        try {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v.isRemovable() && !v.isPrimary()) return v;
            }
        } catch (Exception ignored) {
            // volume enumeration is best-effort; the plain picker still works
        }

        return null;
    }

    // --- save & share ------------------------------------------------------

    /**
     * The one button that does the job: build the ZIP and put it in Downloads.
     *
     * Only two things divert from that. On Android 9 and below, writing to a public
     * folder needs a storage permission this app deliberately doesn't have, so the
     * picker stands in. And if the backup plainly won't fit on the phone, we say so
     * up front rather than filling their storage and failing halfway.
     */
    private void startArchive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            promptCreateArchive();
            return;
        }

        if (!vm.fitsOnPhone()) {
            showNoRoomDialog();
            return;
        }

        vm.archiveToDownloads();
    }

    private void showNoRoomDialog() {
        long free = 0;
        File probe = getExternalFilesDir(null);
        if (probe != null) free = probe.getUsableSpace();

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_no_room_title)
                .setMessage(getString(R.string.dialog_no_room_body,
                        BackupViewModel.formatSize(vm.estimatedArchiveSize()),
                        BackupViewModel.formatSize(free)))
                .setPositiveButton(R.string.button_choose_location, (d, w) -> promptCreateArchive())
                .setNegativeButton(R.string.button_close, null)
                .show();
    }

    /** Fallback: let the user name a destination, and stream the archive straight there. */
    private void promptCreateArchive() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, vm.suggestedArchiveName());

        try {
            createArchiveLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void shareArchive() {
        Uri archive = vm.getArchiveUri();

        if (archive == null) {
            Toast.makeText(this, R.string.toast_no_archive, Toast.LENGTH_SHORT).show();
            return;
        }

        // WhatsApp and email silently refuse large attachments; say so before the
        // user watches a multi-GB share fail in another app.
        if (vm.getArchiveSize() > BackupViewModel.LARGE_SHARE_THRESHOLD) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_large_title)
                    .setMessage(getString(R.string.dialog_large_body,
                            BackupViewModel.formatSize(vm.getArchiveSize())))
                    .setPositiveButton(R.string.button_share_anyway, (d, w) -> startShareChooser(archive))
                    .setNegativeButton(R.string.button_close, null)
                    .show();
            return;
        }

        startShareChooser(archive);
    }

    /**
     * Shares the document the archive was written to. The read grant we hold from
     * creating it is forwarded to whichever app the user picks, so no second copy
     * of the archive has to exist.
     */
    private void startShareChooser(Uri fileUri) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject));
        shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_body));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_share_app, Toast.LENGTH_LONG).show();
        }
    }

    /** Tells the user exactly which files didn't make it, rather than quietly dropping them. */
    private void showFailureReport(List<String> failed) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(failed.size(), 20);

        for (int i = 0; i < shown; i++) {
            sb.append("• ").append(failed.get(i)).append('\n');
        }
        if (failed.size() > shown) {
            sb.append(getString(R.string.dialog_failures_more, failed.size() - shown));
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_failures_title, failed.size()))
                .setMessage(getString(R.string.dialog_failures_body, sb.toString()))
                .setPositiveButton(R.string.button_close, null)
                .show();
    }
}
