package com.example.sdcardbackup;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button scanButton, archiveButton, shareButton;

    // ONE source of truth: scanned files as URIs (works for SD/USB)
    private final List<Uri> fileUris = new ArrayList<>();

    private Uri selectedRootUri = null; // SD/USB folder user granted
    private File zipFile;
    private ProgressDialog progressDialog;

    // --- Pick SD/USB folder (SAF) ---
    private final ActivityResultLauncher<Intent> usbPickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show();
            return;
        }

        selectedRootUri = result.getData().getData();
        if (selectedRootUri == null) return;

        int takeFlags = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            getContentResolver().takePersistableUriPermission(selectedRootUri, takeFlags);
        } catch (SecurityException ignored) {
        }

        scanSelectedRoot();
    });

    // --- Save ZIP to user-chosen location (Downloads, Drive, etc.) ---
    private final ActivityResultLauncher<Intent> saveZipLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
        Uri dest = result.getData().getData();
        if (dest == null) return;

        // Copy zipFile -> chosen destination
        new Thread(() -> {
            try {
                if (zipFile == null || !zipFile.exists()) throw new IOException("ZIP not found");

                try (InputStream in = new FileInputStream(zipFile); OutputStream out = getContentResolver().openOutputStream(dest, "w")) {

                    if (out == null) throw new IOException("Could not open destination");

                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }

                runOnUiThread(() -> Toast.makeText(this, "Saved ZIP to selected location ✅", Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanButton);
        archiveButton = findViewById(R.id.archiveButton);


        shareButton = findViewById(R.id.shareButton);

        archiveButton.setEnabled(false);
        shareButton.setEnabled(false);

        // "scan" = pick SD/USB folder via SAF( Storage Access Framework)
        scanButton.setOnClickListener(v -> openUsbPicker());

        // create zip from scanned uris
        archiveButton.setOnClickListener(v -> archiveFiles());

        // share the created zip via other apps
        shareButton.setOnClickListener(v -> shareArchive());

        // tell user to pick folder (can't auto-detect without SAF)
        updateRemovableHint();
    }

    private void openUsbPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        usbPickerLauncher.launch(intent);
    }

    private void scanSelectedRoot() {
        fileUris.clear();

        if (selectedRootUri == null) {
            statusText.setText("No SD/USB folder selected");
            archiveButton.setEnabled(false);
            return;
        }

        DocumentFile root = DocumentFile.fromTreeUri(this, selectedRootUri);
        if (root == null || !root.isDirectory()) {
            statusText.setText("Invalid folder selected");
            archiveButton.setEnabled(false);
            return;
        }

        scanDocumentDirectory(root);

        if (fileUris.isEmpty()) {
            statusText.setText("No files found in selected folder");
            archiveButton.setEnabled(false);
        } else {
            statusText.setText("Found " + fileUris.size() + " files");
            archiveButton.setEnabled(true);
        }

        shareButton.setEnabled(false);
        zipFile = null;
    }

    private void scanDocumentDirectory(DocumentFile dir) {
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                scanDocumentDirectory(f);
            } else {
                fileUris.add(f.getUri());
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void archiveFiles() {
        if (fileUris.isEmpty()) {
            Toast.makeText(this, "No files to archive", Toast.LENGTH_SHORT).show();
            return;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Creating archive...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> {
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String zipFileName = "SD_USB_Backup_" + timestamp + ".zip";

                // creating ZIP in app storage (no special permission)
                File outDir = getExternalFilesDir(null);
                if (outDir == null) throw new IOException("External files dir is null");

                zipFile = new File(outDir, zipFileName);

                try (FileOutputStream fos = new FileOutputStream(zipFile); ZipOutputStream zos = new ZipOutputStream(fos)) {

                    byte[] buffer = new byte[8192];

                    for (Uri uri : fileUris) {
                        String entryName = resolveName(uri);

                        zos.putNextEntry(new ZipEntry(entryName));

                        try (InputStream is = getContentResolver().openInputStream(uri)) {
                            if (is != null) {
                                int len;
                                while ((len = is.read(buffer)) > 0) {
                                    zos.write(buffer, 0, len);
                                }
                            }
                        }

                        zos.closeEntry();
                    }
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    statusText.setText("Archive created: " + zipFile.getName() + "\nLocation: " + zipFile.getAbsolutePath() + "\nSize: " + (zipFile.length() / 1024) + " KB");
                    shareButton.setEnabled(true);
                    Toast.makeText(this, "Archive created", Toast.LENGTH_LONG).show();

                    // Ask where to save (Downloads) with minimal permission
                    promptSaveZip(zipFile.getName());
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "Error creating archive: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void promptSaveZip(String suggestedName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        saveZipLauncher.launch(intent);
    }

    private String resolveName(Uri uri) {
        // Try DocumentFile name for content://
        DocumentFile df = DocumentFile.fromSingleUri(this, uri);
        if (df != null && df.getName() != null) return df.getName();

        // Fallback
        return "file";
    }

    private void shareArchive() {
        if (zipFile == null || !zipFile.exists()) {
            Toast.makeText(this, "No archive to share", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", zipFile);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SD/USB Backup");
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Attached is the backup archive.");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(shareIntent, "Share archive via"));
    }

    //  “auto detect” hint (but still requires user to pick folder via SAF)
    private void updateRemovableHint() {
        File[] dirs = ContextCompat.getExternalFilesDirs(this, null);
        boolean hasRemovable = false;
        for (File d : dirs) {
            if (d != null) {
                // still need SAF to read.
                hasRemovable = true;
            }
        }
    }
}