package com.example.sdcardbackup;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView statusText;
    private Button scanButton, archiveButton, shareButton;
    private List<File> filesList = new ArrayList<>();
    private File zipFile;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanButton);
        archiveButton = findViewById(R.id.archiveButton);
        shareButton = findViewById(R.id.shareButton);

        // Initially disable archive and share buttons
        archiveButton.setEnabled(false);
        shareButton.setEnabled(false);

        // Check and request permissions
        checkPermissions();

        // Scan SD card button
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //scanSDCard();
                //TODO
                scanAppExternalFiles();
            }
        });

        // Archive files button
        archiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                archiveFiles();
            }
        });

        // Share archive button
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareArchive();
            }
        });
    }

    // Check if we have necessary permissions
    private void checkPermissions() {
      /*  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 and above
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        } else {
            // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            }
        } */


    }

    // Scan SD card for files
    private void scanSDCard() {
        filesList.clear();

        // Get external storage directories (SD card)
        File[] externalDirs = ContextCompat.getExternalFilesDirs(this, null);

        if (externalDirs.length > 1 && externalDirs[1] != null) {
            // externalDirs[1] is typically the SD card
            File sdCard = externalDirs[1];
            scanDirectory(sdCard);

            if (filesList.isEmpty()) {
                statusText.setText("No files found on SD card");
                archiveButton.setEnabled(false);
            } else {
                statusText.setText("Found " + filesList.size() + " files on SD card");
                archiveButton.setEnabled(true);
            }
        } else {
            // Try alternative method
            File sdCard = new File("/storage");
            if (sdCard.exists()) {
                scanDirectory(sdCard);
                if (!filesList.isEmpty()) {
                    statusText.setText("Found    " + filesList.size() + "    file/s");
                    archiveButton.setEnabled(true);
                } else {
                    statusText.setText("No SD card detected or no files found");
                }
            } else {
                statusText.setText("No SD card detected");
            }
        }
    }

    // Recursively scan directory for files
    private void scanDirectory(File directory) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        scanDirectory(file); // Recursively scan subdirectories
                    } else {
                        filesList.add(file); // Add file to list
                    }
                }
            }
        }
    }

    // Create ZIP archive of all files
    private void archiveFiles() {
        if (filesList.isEmpty()) {
            Toast.makeText(this, "No files to archive", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show progress dialog
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Creating archive...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Run archiving in background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Create archive file name with timestamp
                    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            .format(new Date());
                    String zipFileName = "SDCard_Backup_" + timestamp + ".zip";

                    // Save to Downloads folder
                    File downloadDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    zipFile = new File(downloadDir, zipFileName);

                    // Create ZIP file
                    FileOutputStream fos = new FileOutputStream(zipFile);
                    ZipOutputStream zos = new ZipOutputStream(fos);

                    byte[] buffer = new byte[1024];

                    // Add each file to ZIP
                    for (File file : filesList) {
                        FileInputStream fis = new FileInputStream(file);
                        zos.putNextEntry(new ZipEntry(file.getName()));

                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }

                        zos.closeEntry();
                        fis.close();
                    }

                    zos.close();
                    fos.close();

                    // Update UI on main thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressDialog.dismiss();
                            statusText.setText("Archive created: " + zipFile.getName() +
                                    "\nSize: " + (zipFile.length() / 1024) + " KB");
                            shareButton.setEnabled(true);
                            Toast.makeText(MainActivity.this,
                                    "Archive saved to Downloads", Toast.LENGTH_LONG).show();
                        }
                    });

                } catch (IOException e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressDialog.dismiss();
                            Toast.makeText(MainActivity.this,
                                    "Error creating archive: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // Share archive via email or other apps
    private void shareArchive() {
        if (zipFile == null || !zipFile.exists()) {
            Toast.makeText(this, "No archive to share", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get URI using FileProvider
        Uri fileUri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider",
                zipFile);

        // Create share intent
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SD Card Backup");
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Please find attached SD card backup archive.");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Show app chooser
        startActivity(Intent.createChooser(shareIntent, "Share archive via"));
    }

    private void scanAppExternalFiles() {
        filesList.clear();

        //App specific external storage folder

        File dir = getExternalFilesDir(null);

        if (dir != null && dir.exists()) {
            scanDirectory(dir);
        }

        if (filesList.isEmpty()) {
            statusText.setText("No files found in app folder");
            archiveButton.setEnabled(false);
        } else {
            statusText.setText("Found" + filesList.size() + "files");
            archiveButton.setEnabled(true);
        }

    }

}