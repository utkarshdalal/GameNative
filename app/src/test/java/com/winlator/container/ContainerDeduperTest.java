package com.winlator.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.winlator.container.ContainerDeduper.DedupeResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

public class ContainerDeduperTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File srcDir;
    private File dstDir;

    private void dirs() throws IOException {
        srcDir = tmp.newFolder("src");
        dstDir = tmp.newFolder("dst");
    }

    private static void write(File dir, String name, String content) throws IOException {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sameInode(File a, File b) throws IOException {
        Object ka = Files.readAttributes(a.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        Object kb = Files.readAttributes(b.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        return ka != null && ka.equals(kb);
    }

    @Test
    public void identicalFileBecomesHardlink() throws IOException {
        dirs();
        write(srcDir, "kernel32.dll", "same-bytes");
        write(dstDir, "kernel32.dll", "same-bytes");

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, null, acc);

        assertFalse(acc.linkingUnsupported);
        assertEquals(1, acc.filesLinked);
        assertEquals(0, acc.filesSkippedModified);
        assertEquals("same-bytes".length(), acc.bytesSaved);
        assertTrue(sameInode(new File(srcDir, "kernel32.dll"), new File(dstDir, "kernel32.dll")));
    }

    @Test
    public void modifiedFileIsLeftAlone() throws IOException {
        dirs();
        write(srcDir, "user32.dll", "aaaaaaaaaa");
        write(dstDir, "user32.dll", "bbbbbbbbbb");

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, null, acc);

        assertEquals(0, acc.filesLinked);
        assertEquals(1, acc.filesSkippedModified);
        assertFalse(sameInode(new File(srcDir, "user32.dll"), new File(dstDir, "user32.dll")));
        assertEquals("bbbbbbbbbb", new String(Files.readAllBytes(new File(dstDir, "user32.dll").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void differentSizeIsLeftAlone() throws IOException {
        dirs();
        write(srcDir, "d3d9.dll", "short");
        write(dstDir, "d3d9.dll", "a much longer payload");

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, null, acc);

        assertEquals(0, acc.filesLinked);
        assertEquals(1, acc.filesSkippedModified);
        assertEquals("a much longer payload", new String(Files.readAllBytes(new File(dstDir, "d3d9.dll").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void fileWithoutSourceIsLeftAlone() throws IOException {
        dirs();
        write(dstDir, "gamefile.dll", "only-in-container");

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, null, acc);

        assertEquals(0, acc.filesLinked);
        assertEquals(0, acc.filesSkippedModified);
        assertTrue(new File(dstDir, "gamefile.dll").isFile());
        assertEquals("only-in-container", new String(Files.readAllBytes(new File(dstDir, "gamefile.dll").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void alreadyLinkedFileIsNotCountedTwice() throws IOException {
        dirs();
        write(srcDir, "ntdll.dll", "payload");
        Files.createLink(new File(dstDir, "ntdll.dll").toPath(), new File(srcDir, "ntdll.dll").toPath());

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, null, acc);

        assertEquals(0, acc.filesLinked);
        assertEquals(0, acc.bytesSaved);
    }

    @Test
    public void onlyNamesRestrictsTheSet() throws IOException {
        dirs();
        write(srcDir, "a.dll", "xx");
        write(dstDir, "a.dll", "xx");
        write(srcDir, "b.dll", "yy");
        write(dstDir, "b.dll", "yy");

        DedupeResult acc = new DedupeResult();
        ContainerDeduper.dedupeDir(srcDir, dstDir, Collections.singleton("a.dll"), acc);

        assertEquals(1, acc.filesLinked);
        assertTrue(sameInode(new File(srcDir, "a.dll"), new File(dstDir, "a.dll")));
        assertFalse(sameInode(new File(srcDir, "b.dll"), new File(dstDir, "b.dll")));
    }
}
