package ro.muresanianis.sdcardbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Covers audit item #12 — duplicate ZIP entries aborting the archive, and
 * unsanitised names from an untrusted card.
 */
public class ZipNamesTest {

    // --- sanitizeSegment ---------------------------------------------------

    @Test
    public void keepsOrdinaryNamesIntact() {
        assertEquals("IMG_0001.JPG", ZipNames.sanitizeSegment("IMG_0001.JPG"));
    }

    @Test
    public void preservesSpaces() {
        // spaces are legal in ZIP entries; mangling them would rename users' files
        assertEquals("My Holiday Photo.jpg", ZipNames.sanitizeSegment("My Holiday Photo.jpg"));
    }

    @Test
    public void stripsPathSeparators() {
        assertEquals("a_b", ZipNames.sanitizeSegment("a/b"));
        assertEquals("a_b", ZipNames.sanitizeSegment("a\\b"));
    }

    @Test
    public void neutralisesTraversalSegments() {
        assertEquals("_", ZipNames.sanitizeSegment(".."));
        assertEquals("_", ZipNames.sanitizeSegment("."));
    }

    @Test
    public void traversalCannotSurviveInAnyForm() {
        // the archive must never contain an entry that escapes its own root
        String[] hostile = {"..", "../", "..\\", "../../etc/passwd", "/absolute"};
        for (String h : hostile) {
            String out = ZipNames.sanitizeSegment(h);
            assertFalse("leaked separator from " + h, out.contains("/"));
            assertFalse("leaked separator from " + h, out.contains("\\"));
            assertFalse("still traversal: " + h, out.equals(".."));
        }
    }

    @Test
    public void stripsControlCharacters() {
        // built explicitly so the test source stays plain ASCII
        assertEquals("a_b", ZipNames.sanitizeSegment("a" + (char) 0x00 + "b"));
        assertEquals("a_b", ZipNames.sanitizeSegment("a" + (char) 0x0A + "b"));
        assertEquals("a_b", ZipNames.sanitizeSegment("a" + (char) 0x1F + "b"));
        assertEquals("a_b", ZipNames.sanitizeSegment("a" + (char) 0x7F + "b"));
    }
    @Test
    public void fallsBackForEmptyOrNull() {
        assertEquals("unnamed", ZipNames.sanitizeSegment(null));
        assertEquals("unnamed", ZipNames.sanitizeSegment(""));
        assertEquals("unnamed", ZipNames.sanitizeSegment("   "));
    }

    // --- unique ------------------------------------------------------------

    @Test
    public void passesThroughFirstUse() {
        Set<String> used = new HashSet<>();
        assertEquals("DCIM/IMG_0001.JPG", ZipNames.unique(used, "DCIM/IMG_0001.JPG"));
    }

    @Test
    public void disambiguatesCollisionsKeepingExtension() {
        Set<String> used = new HashSet<>();
        assertEquals("IMG_0001.JPG", ZipNames.unique(used, "IMG_0001.JPG"));
        assertEquals("IMG_0001 (2).JPG", ZipNames.unique(used, "IMG_0001.JPG"));
        assertEquals("IMG_0001 (3).JPG", ZipNames.unique(used, "IMG_0001.JPG"));
    }

    @Test
    public void handlesNamesWithoutExtension() {
        Set<String> used = new HashSet<>();
        assertEquals("README", ZipNames.unique(used, "README"));
        assertEquals("README (2)", ZipNames.unique(used, "README"));
    }

    @Test
    public void doesNotMistakeDirectoryDotForExtension() {
        Set<String> used = new HashSet<>();
        assertEquals("my.folder/README", ZipNames.unique(used, "my.folder/README"));
        assertEquals("my.folder/README (2)", ZipNames.unique(used, "my.folder/README"));
    }

    @Test
    public void differentFoldersDoNotCollideAtAll() {
        // the real fix for #12: keeping the path means these never collide
        Set<String> used = new HashSet<>();
        assertEquals("DCIM/100CANON/IMG_0001.JPG", ZipNames.unique(used, "DCIM/100CANON/IMG_0001.JPG"));
        assertEquals("DCIM/101CANON/IMG_0001.JPG", ZipNames.unique(used, "DCIM/101CANON/IMG_0001.JPG"));
    }

    @Test
    public void everyNameIsUniqueAcrossAHostileBatch() {
        // the guarantee that matters: no duplicate can ever reach putNextEntry,
        // because one duplicate aborts the entire backup
        Set<String> used = new HashSet<>();
        Set<String> emitted = new LinkedHashSet<>();

        for (int i = 0; i < 500; i++) {
            String name = ZipNames.unique(used, "same.jpg");
            assertTrue("duplicate emitted on iteration " + i, emitted.add(name));
        }

        assertEquals(500, emitted.size());
    }
}
