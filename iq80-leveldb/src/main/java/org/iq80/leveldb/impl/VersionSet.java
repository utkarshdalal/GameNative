/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.impl;

import com.google.common.base.Joiner;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.iq80.leveldb.table.UserComparator;
import org.iq80.leveldb.util.InternalIterator;
import org.iq80.leveldb.util.Level0Iterator;
import org.iq80.leveldb.util.MergingIterator;
import org.iq80.leveldb.util.Slice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.DbConstants.NUM_LEVELS;
import static org.iq80.leveldb.impl.LogMonitors.throwExceptionMonitor;

public class VersionSet
        implements SeekingIterable<InternalKey, Slice>
{
    private static final int L0_COMPACTION_TRIGGER = 4;

    public static final int TARGET_FILE_SIZE = 2 * 1048576;

    // Maximum bytes of overlaps in grandparent (i.e., level+2) before we
    // stop building a single file in a level.level+1 compaction.
    public static final long MAX_GRAND_PARENT_OVERLAP_BYTES = 10 * TARGET_FILE_SIZE;

    private final AtomicLong nextFileNumber = new AtomicLong(2);
    private long manifestFileNumber = 1;
    private Version current;
    private long lastSequence;
    private long logNumber;
    private long prevLogNumber;

    private final Map<Version, Object> activeVersions = new MapMaker().weakKeys().makeMap();
    private final File databaseDir;
    private final TableCache tableCache;
    private final InternalKeyComparator internalKeyComparator;

    private LogWriter descriptorLog;
    private final Map<Integer, InternalKey> compactPointers = new TreeMap<>();

    public VersionSet(File databaseDir, TableCache tableCache, InternalKeyComparator internalKeyComparator)
            throws IOException
    {
        this.databaseDir = databaseDir;
        this.tableCache = tableCache;
        this.internalKeyComparator = internalKeyComparator;
        appendVersion(new Version(this));

        initializeIfNeeded();
    }

    private void initializeIfNeeded()
            throws IOException
    {
        File currentFile = new File(databaseDir, Filename.currentFileName());

        if (!currentFile.exists()) {
            VersionEdit edit = new VersionEdit();
            edit.setComparatorName(internalKeyComparator.name());
            edit.setLogNumber(prevLogNumber);
            edit.setNextFileNumber(nextFileNumber.get());
            edit.setLastSequenceNumber(lastSequence);

            LogWriter log = Logs.createLogWriter(new File(databaseDir, Filename.descriptorFileName(manifestFileNumber)), manifestFileNumber);
            try {
                writeSnapshot(log);
                log.addRecord(edit.encode(), false);
            }
            finally {
                log.close();
            }

            Filename.setCurrentFile(databaseDir, log.getFileNumber());
        }
    }

    public void destroy()
            throws IOException
    {
        if (descriptorLog != null) {
            descriptorLog.close();
            descriptorLog = null;
        }

        Version t = current;
        if (t != null) {
            current = null;
            t.release();
        }

        Set<Version> versions = activeVersions.keySet();
        // TODO:
        // log("DB closed with "+versions.size()+" open snapshots. This could mean your application has a resource leak.");
    }

    private void appendVersion(Version version)
    {
        requireNonNull(version, "version is null");
        checkArgument(version != current, "version is the current version");
        Version previous = current;
        current = version;
        activeVersions.put(version, new Object());
        if (previous != null) {
            previous.release();
        }
    }

    public void removeVersion(Version version)
    {
        requireNonNull(version, "version is null");
        checkArgument(version != current, "version is the current version");
        boolean removed = activeVersions.remove(version) != null;
        assert removed : "Expected the version to still be in the active set";
    }

    public InternalKeyComparator getInternalKeyComparator()
    {
        return internalKeyComparator;
    }

    public TableCache getTableCache()
    {
        return tableCache;
    }

    public Version getCurrent()
    {
        return current;
    }

    public long getManifestFileNumber()
    {
        return manifestFileNumber;
    }

    public long getNextFileNumber()
    {
        return nextFileNumber.getAndIncrement();
    }

    public long getLogNumber()
    {
        return logNumber;
    }

    public long getPrevLogNumber()
    {
        return prevLogNumber;
    }

    @Override
    public MergingIterator iterator()
    {
        return current.iterator();
    }

    public MergingIterator makeInputIterator(Compaction c)
    {
        // Level-0 files have to be merged together.  For other levels,
        // we will make a concatenating iterator per level.
        // TODO(opt): use concatenating iterator for level-0 if there is no overlap
        List<InternalIterator> list = new ArrayList<>();
        for (int which = 0; which < 2; which++) {
            if (!c.getInputs()[which].isEmpty()) {
                if (c.getLevel() + which == 0) {
                    List<FileMetaData> files = c.getInputs()[which];
                    list.add(new Level0Iterator(tableCache, files, internalKeyComparator));
                }
                else {
                    // Create concatenating iterator for the files from this level
                    list.add(Level.createLevelConcatIterator(tableCache, c.getInputs()[which], internalKeyComparator));
                }
            }
        }
        return new MergingIterator(list, internalKeyComparator);
    }

    public LookupResult get(LookupKey key)
    {
        return current.get(key);
    }

    public boolean overlapInLevel(int level, Slice smallestUserKey, Slice largestUserKey)
    {
        return current.overlapInLevel(level, smallestUserKey, largestUserKey);
    }

    public int numberOfFilesInLevel(int level)
    {
        return current.numberOfFilesInLevel(level);
    }

    public long numberOfBytesInLevel(int level)
    {
        return current.numberOfFilesInLevel(level);
    }

    public long getLastSequence()
    {
        return lastSequence;
    }

    public void setLastSequence(long newLastSequence)
    {
        checkArgument(newLastSequence >= lastSequence, "Expected newLastSequence to be greater than or equal to current lastSequence");
        this.lastSequence = newLastSequence;
    }

    public void logAndApply(VersionEdit edit)
            throws IOException
    {
        if (edit.getLogNumber() != null) {
            checkArgument(edit.getLogNumber() >= logNumber);
            checkArgument(edit.getLogNumber() < nextFileNumber.get());
        }
        else {
            edit.setLogNumber(logNumber);
        }

        if (edit.getPreviousLogNumber() == null) {
            edit.setPreviousLogNumber(prevLogNumber);
        }

        edit.setNextFileNumber(nextFileNumber.get());
        edit.setLastSequenceNumber(lastSequence);

        Version version = new Version(this);
        Builder builder = new Builder(this, current);
        builder.apply(edit);
        builder.saveTo(version);

        finalizeVersion(version);

        boolean createdNewManifest = false;
        try {
            // Initialize new descriptor log file if necessary by creating
            // a temporary file that contains a snapshot of the current version.
            if (descriptorLog == null) {
                edit.setNextFileNumber(nextFileNumber.get());
                descriptorLog = Logs.createLogWriter(new File(databaseDir, Filename.descriptorFileName(manifestFileNumber)), manifestFileNumber);
                writeSnapshot(descriptorLog);
                createdNewManifest = true;
            }

            // Write new record to MANIFEST log
            Slice record = edit.encode();
            descriptorLog.addRecord(record, true);

            // If we just created a new descriptor file, install it by writing a
            // new CURRENT file that points to it.
            if (createdNewManifest) {
                Filename.setCurrentFile(databaseDir, descriptorLog.getFileNumber());
            }
        }
        catch (IOException e) {
            // New manifest file was not installed, so clean up state and delete the file
            if (createdNewManifest) {
                descriptorLog.close();
                // todo add delete method to LogWriter
                new File(databaseDir, Filename.logFileName(descriptorLog.getFileNumber())).delete();
                descriptorLog = null;
            }
            throw e;
        }

        // Install the new version
        appendVersion(version);
        logNumber = edit.getLogNumber();
        prevLogNumber = edit.getPreviousLogNumber();
    }

    private void writeSnapshot(LogWriter log)
            throws IOException
    {
        // Save metadata
        VersionEdit edit = new VersionEdit();
        edit.setComparatorName(internalKeyComparator.name());

        // Save compaction pointers
        edit.setCompactPointers(compactPointers);

        // Save files
        edit.addFiles(current.getFiles());

        Slice record = edit.encode();
        log.addRecord(record, false);
    }

    public void recover()
            throws IOException
    {
        // Read "CURRENT" file, which contains a pointer to the current manifest file
        File currentFile = new File(databaseDir, Filename.currentFileName());
        checkState(currentFile.exists(), "CURRENT file does not exist");

        String currentName = Files.toString(currentFile, UTF_8);
        if (currentName.isEmpty() || currentName.charAt(currentName.length() - 1) != '\n') {
            throw new IllegalStateException("CURRENT file does not end with newline");
        }
        currentName = currentName.substring(0, currentName.length() - 1);

        // open file channel
        try (FileInputStream fis = new FileInputStream(new File(databaseDir, currentName));
             FileChannel fileChannel = fis.getChannel()) {
            // read log edit log
            Long nextFileNumber = null;
            Long lastSequence = null;
            Long logNumber = null;
            Long prevLogNumber = null;
            Builder builder = new Builder(this, current);

            LogReader reader = new LogReader(fileChannel, throwExceptionMonitor(), true, 0);
            for (Slice record = reader.readRecord(); record != null; record = reader.readRecord()) {
                // read version edit
                VersionEdit edit = new VersionEdit(record);

                // verify comparator
                // todo implement user comparator
                String editComparator = edit.getComparatorName();
                String userComparator = internalKeyComparator.name();
                checkArgument(editComparator == null || editComparator.equals(userComparator),
                        "Expected user comparator %s to match existing database comparator ", userComparator, editComparator);

                // apply edit
                builder.apply(edit);

                // save edit values for verification below
                logNumber = coalesce(edit.getLogNumber(), logNumber);
                prevLogNumber = coalesce(edit.getPreviousLogNumber(), prevLogNumber);
                nextFileNumber = coalesce(edit.getNextFileNumber(), nextFileNumber);
                lastSequence = coalesce(edit.getLastSequenceNumber(), lastSequence);
            }

            List<String> problems = new ArrayList<>();
            if (nextFileNumber == null) {
                problems.add("Descriptor does not contain a meta-nextfile entry");
            }
            if (logNumber == null) {
                problems.add("Descriptor does not contain a meta-lognumber entry");
            }
            if (lastSequence == null) {
                problems.add("Descriptor does not contain a last-sequence-number entry");
            }
            if (!problems.isEmpty()) {
                throw new RuntimeException("Corruption: \n\t" + Joiner.on("\n\t").join(problems));
            }

            if (prevLogNumber == null) {
                prevLogNumber = 0L;
            }

            Version newVersion = new Version(this);
            builder.saveTo(newVersion);

            // Install recovered version
            finalizeVersion(newVersion);

            appendVersion(newVersion);
            manifestFileNumber = nextFileNumber;
            this.nextFileNumber.set(nextFileNumber + 1);
            this.lastSequence = lastSequence;
            this.logNumber = logNumber;
            this.prevLogNumber = prevLogNumber;
        }
    }

    private void finalizeVersion(Version version)
    {
        // Precomputed best level for next compaction
        int bestLevel = -1;
        double bestScore = -1;

        for (int level = 0; level < version.numberOfLevels() - 1; level++) {
            double score;
            if (level == 0) {
                // We treat level-0 specially by bounding the number of files
                // instead of number of bytes for two reasons:
                //
                // (1) With larger write-buffer sizes, it is nice not to do too
                // many level-0 compactions.
                //
                // (2) The files in level-0 are merged on every read and
                // therefore we wish to avoid too many files when the individual
                // file size is small (perhaps because of a small write-buffer
                // setting, or very high compression ratios, or lots of
                // overwrites/deletions).
                score = 1.0 * version.numberOfFilesInLevel(level) / L0_COMPACTION_TRIGGER;
            }
            else {
                // Compute the ratio of current size to size limit.
                long levelBytes = 0;
                for (FileMetaData fileMetaData : version.getFiles(level)) {
                    levelBytes += fileMetaData.getFileSize();
                }
                score = 1.0 * levelBytes / maxBytesForLevel(level);
            }

            if (score > bestScore) {
                bestLevel = level;
                bestScore = score;
            }
        }

        version.setCompactionLevel(bestLevel);
        version.setCompactionScore(bestScore);
    }

    private static <V> V coalesce(V... values)
    {
        for (V value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public List<FileMetaData> getLiveFiles()
    {
        ImmutableList.Builder<FileMetaData> builder = ImmutableList.builder();
        for (Version activeVersion : activeVersions.keySet()) {
            builder.addAll(activeVersion.getFiles().values());
        }
        return builder.build();
    }

    private static double maxBytesForLevel(int level)
    {
        // Note: the result for level zero is not really used since we set
        // the level-0 compaction threshold based on number of files.
        double result = 10 * 1048576.0;  // Result for both level-0 and level-1
        while (level > 1) {
            result *= 10;
            level--;
        }
        return result;
    }

    public static long maxFileSizeForLevel(int level)
    {
        return TARGET_FILE_SIZE;  // We could vary per level to reduce number of files?
    }

    public boolean needsCompaction()
    {
        return current.getCompactionScore() >= 1 || current.getFileToCompact() != null;
    }

    public Compaction compactRange(int level, InternalKey begin, InternalKey end)
    {
        List<FileMetaData> levelInputs = getOverlappingInputs(level, begin, end);
        if (levelInputs.isEmpty()) {
            return null;
        }

        return setupOtherInputs(level, levelInputs);
    }

    public Compaction pickCompaction()
    {
        // We prefer compactions triggered by too much data in a level over
        // the compactions triggered by seeks.
        boolean sizeCompaction = (current.getCompactionScore() >= 1);
        boolean seekCompaction = (current.getFileToCompact() != null);

        int level;
        List<FileMetaData> levelInputs;
        if (sizeCompaction) {
            level = current.getCompactionLevel();
            checkState(level >= 0);
            checkState(level + 1 < NUM_LEVELS);

            // Pick the first file that comes after compact_pointer_[level]
            levelInputs = new ArrayList<>();
            for (FileMetaData fileMetaData : current.getFiles(level)) {
                if (!compactPointers.containsKey(level) ||
                        internalKeyComparator.compare(fileMetaData.getLargest(), compactPointers.get(level)) > 0) {
                    levelInputs.add(fileMetaData);
                    break;
                }
            }
            if (levelInputs.isEmpty()) {
                // Wrap-around to the beginning of the key space
                levelInputs.add(current.getFiles(level).get(0));
            }
        }
        else if (seekCompaction) {
            level = current.getFileToCompactLevel();
            levelInputs = ImmutableList.of(current.getFileToCompact());
        }
        else {
            return null;
        }

        // Files in level 0 may overlap each other, so pick up all overlapping ones
        if (level == 0) {
            Entry<InternalKey, InternalKey> range = getRange(levelInputs);
            // Note that the next call will discard the file we placed in
            // c->inputs_[0] earlier and replace it with an overlapping set
            // which will include the picked file.
            levelInputs = getOverlappingInputs(0, range.getKey(), range.getValue());

            checkState(!levelInputs.isEmpty());
        }

        Compaction compaction = setupOtherInputs(level, levelInputs);
        return compaction;
    }

    private Compaction setupOtherInputs(int level, List<FileMetaData> levelInputs)
    {
        Entry<InternalKey, InternalKey> range = getRange(levelInputs);
        InternalKey smallest = range.getKey();
        InternalKey largest = range.getValue();

        List<FileMetaData> levelUpInputs = getOverlappingInputs(level + 1, smallest, largest);

        // Get entire range covered by compaction
        range = getRange(levelInputs, levelUpInputs);
        InternalKey allStart = range.getKey();
        InternalKey allLimit = range.getValue();

        // See if we can grow the number of inputs in "level" without
        // changing the number of "level+1" files we pick up.
        if (!levelUpInputs.isEmpty()) {
            List<FileMetaData> expanded0 = getOverlappingInputs(level, allStart, allLimit);

            if (expanded0.size() > levelInputs.size()) {
                range = getRange(expanded0);
                InternalKey newStart = range.getKey();
                InternalKey newLimit = range.getValue();

                List<FileMetaData> expanded1 = getOverlappingInputs(level + 1, newStart, newLimit);
                if (expanded1.size() == levelUpInputs.size()) {
//              Log(options_->info_log,
//                  "Expanding@%d %d+%d to %d+%d\n",
//                  level,
//                  int(c->inputs_[0].size()),
//                  int(c->inputs_[1].size()),
//                  int(expanded0.size()),
//                  int(expanded1.size()));
                    smallest = newStart;
                    largest = newLimit;
                    levelInputs = expanded0;
                    levelUpInputs = expanded1;

                    range = getRange(levelInputs, levelUpInputs);
                    allStart = range.getKey();
                    allLimit = range.getValue();
                }
            }
        }

        // Compute the set of grandparent files that overlap this compaction
        // (parent == level+1; grandparent == level+2)
        List<FileMetaData> grandparents = ImmutableList.of();
        if (level + 2 < NUM_LEVELS) {
            grandparents = getOverlappingInputs(level + 2, allStart, allLimit);
        }

//        if (false) {
//            Log(options_ - > info_log, "Compacting %d '%s' .. '%s'",
//                    level,
//                    EscapeString(smallest.Encode()).c_str(),
//                    EscapeString(largest.Encode()).c_str());
//        }

        Compaction compaction = new Compaction(current, level, levelInputs, levelUpInputs, grandparents);

        // Update the place where we will do the next compaction for this level.
        // We update this immediately instead of waiting for the VersionEdit
        // to be applied so that if the compaction fails, we will try a different
        // key range next time.
        compactPointers.put(level, largest);
        compaction.getEdit().setCompactPointer(level, largest);

        return compaction;
    }

    List<FileMetaData> getOverlappingInputs(int level, InternalKey begin, InternalKey end)
    {
        ImmutableList.Builder<FileMetaData> files = ImmutableList.builder();
        Slice userBegin = begin.getUserKey();
        Slice userEnd = end.getUserKey();
        UserComparator userComparator = internalKeyComparator.getUserComparator();
        for (FileMetaData fileMetaData : current.getFiles(level)) {
            if (userComparator.compare(fileMetaData.getLargest().getUserKey(), userBegin) < 0 ||
                    userComparator.compare(fileMetaData.getSmallest().getUserKey(), userEnd) > 0) {
                // Either completely before or after range; skip it
            }
            else {
                files.add(fileMetaData);
            }
        }
        return files.build();
    }

    private Entry<InternalKey, InternalKey> getRange(List<FileMetaData>... inputLists)
    {
        InternalKey smallest = null;
        InternalKey largest = null;
        for (List<FileMetaData> inputList : inputLists) {
            for (FileMetaData fileMetaData : inputList) {
                if (smallest == null) {
                    smallest = fileMetaData.getSmallest();
                    largest = fileMetaData.getLargest();
                }
                else {
                    if (internalKeyComparator.compare(fileMetaData.getSmallest(), smallest) < 0) {
                        smallest = fileMetaData.getSmallest();
                    }
                    if (internalKeyComparator.compare(fileMetaData.getLargest(), largest) > 0) {
                        largest = fileMetaData.getLargest();
                    }
                }
            }
        }
        return Maps.immutableEntry(smallest, largest);
    }

    public long getMaxNextLevelOverlappingBytes()
    {
        long result = 0;
        for (int level = 1; level < NUM_LEVELS; level++) {
            for (FileMetaData fileMetaData : current.getFiles(level)) {
                List<FileMetaData> overlaps = getOverlappingInputs(level + 1, fileMetaData.getSmallest(), fileMetaData.getLargest());
                long totalSize = 0;
                for (FileMetaData overlap : overlaps) {
                    totalSize += overlap.getFileSize();
                }
                result = Math.max(result, totalSize);
            }
        }
        return result;
    }

    /**
     * A helper class so we can efficiently apply a whole sequence
     * of edits to a particular state without creating intermediate
     * Versions that contain full copies of the intermediate state.
     */
    private static class Builder
    {
        private final VersionSet versionSet;
        private final Version baseVersion;
        private final List<LevelState> levels;

        private Builder(VersionSet versionSet, Version baseVersion)
        {
            this.versionSet = versionSet;
            this.baseVersion = baseVersion;

            levels = new ArrayList<>(baseVersion.numberOfLevels());
            for (int i = 0; i < baseVersion.numberOfLevels(); i++) {
                levels.add(new LevelState(versionSet.internalKeyComparator));
            }
        }

        /**
         * Apply the specified edit to the current state.
         */
        public void apply(VersionEdit edit)
        {
            // Update compaction pointers
            for (Entry<Integer, InternalKey> entry : edit.getCompactPointers().entrySet()) {
                Integer level = entry.getKey();
                InternalKey internalKey = entry.getValue();
                versionSet.compactPointers.put(level, internalKey);
            }

            // Delete files
            for (Entry<Integer, Long> entry : edit.getDeletedFiles().entries()) {
                Integer level = entry.getKey();
                Long fileNumber = entry.getValue();
                levels.get(level).deletedFiles.add(fileNumber);
                // todo missing update to addedFiles?
            }

            // Add new files
            for (Entry<Integer, FileMetaData> entry : edit.getNewFiles().entries()) {
                Integer level = entry.getKey();
                FileMetaData fileMetaData = entry.getValue();

                // We arrange to automatically compact this file after
                // a certain number of seeks.  Let's assume:
                //   (1) One seek costs 10ms
                //   (2) Writing or reading 1MB costs 10ms (100MB/s)
                //   (3) A compaction of 1MB does 25MB of IO:
                //         1MB read from this level
                //         10-12MB read from next level (boundaries may be misaligned)
                //         10-12MB written to next level
                // This implies that 25 seeks cost the same as the compaction
                // of 1MB of data.  I.e., one seek costs approximately the
                // same as the compaction of 40KB of data.  We are a little
                // conservative and allow approximately one seek for every 16KB
                // of data before triggering a compaction.
                int allowedSeeks = (int) (fileMetaData.getFileSize() / 16384);
                if (allowedSeeks < 100) {
                    allowedSeeks = 100;
                }
                fileMetaData.setAllowedSeeks(allowedSeeks);

                levels.get(level).deletedFiles.remove(fileMetaData.getNumber());
                levels.get(level).addedFiles.add(fileMetaData);
            }
        }

        /**
         * Saves the current state in specified version.
         */
        public void saveTo(Version version)
                throws IOException
        {
            FileMetaDataBySmallestKey cmp = new FileMetaDataBySmallestKey(versionSet.internalKeyComparator);
            for (int level = 0; level < baseVersion.numberOfLevels(); level++) {
                // Merge the set of added files with the set of pre-existing files.
                // Drop any deleted files.  Store the result in *v.

                Collection<FileMetaData> baseFiles = baseVersion.getFiles().asMap().get(level);
                if (baseFiles == null) {
                    baseFiles = ImmutableList.of();
                }
                SortedSet<FileMetaData> addedFiles = levels.get(level).addedFiles;
                if (addedFiles == null) {
                    addedFiles = ImmutableSortedSet.of();
                }

                // files must be added in sorted order so assertion check in maybeAddFile works
                ArrayList<FileMetaData> sortedFiles = new ArrayList<>(baseFiles.size() + addedFiles.size());
                sortedFiles.addAll(baseFiles);
                sortedFiles.addAll(addedFiles);
                Collections.sort(sortedFiles, cmp);

                for (FileMetaData fileMetaData : sortedFiles) {
                    maybeAddFile(version, level, fileMetaData);
                }

                //#ifndef NDEBUG  todo
                // Make sure there is no overlap in levels > 0
                version.assertNoOverlappingFiles();
                //#endif
            }
        }

        private void maybeAddFile(Version version, int level, FileMetaData fileMetaData)
                throws IOException
        {
            if (levels.get(level).deletedFiles.contains(fileMetaData.getNumber())) {
                // File is deleted: do nothing
            }
            else {
                List<FileMetaData> files = version.getFiles(level);
                if (level > 0 && !files.isEmpty()) {
                    // Must not overlap
                    boolean filesOverlap = versionSet.internalKeyComparator.compare(files.get(files.size() - 1).getLargest(), fileMetaData.getSmallest()) >= 0;
                    if (filesOverlap) {
                        // A memory compaction, while this compaction was running, resulted in a a database state that is
                        // incompatible with the compaction.  This is rare and expensive to detect while the compaction is
                        // running, so we catch here simply discard the work.
                        throw new IOException(String.format("Compaction is obsolete: Overlapping files %s and %s in level %s",
                                files.get(files.size() - 1).getNumber(),
                                fileMetaData.getNumber(), level));
                    }
                }
                version.addFile(level, fileMetaData);
            }
        }

        private static class FileMetaDataBySmallestKey
                implements Comparator<FileMetaData>
        {
            private final InternalKeyComparator internalKeyComparator;

            private FileMetaDataBySmallestKey(InternalKeyComparator internalKeyComparator)
            {
                this.internalKeyComparator = internalKeyComparator;
            }

            @Override
            public int compare(FileMetaData f1, FileMetaData f2)
            {
                return ComparisonChain
                        .start()
                        .compare(f1.getSmallest(), f2.getSmallest(), internalKeyComparator)
                        .compare(f1.getNumber(), f2.getNumber())
                        .result();
            }
        }

        private static class LevelState
        {
            private final SortedSet<FileMetaData> addedFiles;
            private final Set<Long> deletedFiles = new HashSet<Long>();

            public LevelState(InternalKeyComparator internalKeyComparator)
            {
                addedFiles = new TreeSet<FileMetaData>(new FileMetaDataBySmallestKey(internalKeyComparator));
            }

            @Override
            public String toString()
            {
                final StringBuilder sb = new StringBuilder();
                sb.append("LevelState");
                sb.append("{addedFiles=").append(addedFiles);
                sb.append(", deletedFiles=").append(deletedFiles);
                sb.append('}');
                return sb.toString();
            }
        }
    }
}
