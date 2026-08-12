package ro.muresanianis.sdcardbackup;

import java.util.Set;

/**
 * Entry-name rules for the generated archive.
 *
 * Kept out of MainActivity so it can be unit tested without an Android runtime —
 * this is the logic that decides whether a backup succeeds or aborts partway, so
 * it is worth testing directly.
 */
final class ZipNames {

    private ZipNames() {
    }

    /**
     * Makes one path segment safe to embed in a ZIP.
     *
     * The card is untrusted input. This app never extracts archives, so Zip Slip
     * isn't a risk here — but emitting "../" entries would make the archive an
     * attack on whoever opens it later.
     */
    static String sanitizeSegment(String name) {
        if (name == null || name.trim().isEmpty()) return "unnamed";

        // Replace anything that could break out of the entry path or corrupt the
        // archive: both separators, plus control characters (a NUL or newline in
        // a filename is hostile input, not a quirk). Spaces are legal in ZIP
        // entries and are deliberately preserved.
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            sb.append((ch == '/' || ch == '\\' || ch < 0x20 || ch == 0x7F) ? '_' : ch);
        }

        String s = sb.toString().trim();

        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "_";

        return s;
    }

    /**
     * Returns a ZIP entry name not already used, recording it in {@code used}.
     *
     * Cameras routinely produce DCIM/100CANON/IMG_0001.JPG and
     * DCIM/101CANON/IMG_0001.JPG. Keeping the folder path usually separates them;
     * this is the backstop for anything that still collides, since a duplicate
     * entry aborts the whole archive.
     */
    static String unique(Set<String> used, String path) {
        if (used.add(path)) return path;

        String base = path;
        String ext = "";

        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash + 1) {
            base = path.substring(0, dot);
            ext = path.substring(dot);
        }

        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate)) return candidate;
        }
    }
}
