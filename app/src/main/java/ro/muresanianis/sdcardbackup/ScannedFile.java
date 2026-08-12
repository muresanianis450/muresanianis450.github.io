package ro.muresanianis.sdcardbackup;

import android.net.Uri;

/** A file found on the card, with the path it should occupy inside the ZIP. */
final class ScannedFile {

    final Uri uri;
    final String relativePath;
    final long size;
    final long lastModified;

    ScannedFile(Uri uri, String relativePath, long size, long lastModified) {
        this.uri = uri;
        this.relativePath = relativePath;
        this.size = size;
        this.lastModified = lastModified;
    }
}
