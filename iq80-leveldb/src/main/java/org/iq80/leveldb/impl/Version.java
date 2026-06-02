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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.iq80.leveldb.util.InternalIterator;
import org.iq80.leveldb.util.InternalTableIterator;
import org.iq80.leveldb.util.LevelIterator;
import org.iq80.leveldb.util.MergingIterator;
import org.iq80.leveldb.util.Slice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.collect.Ordering.natural;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.DbConstants.MAX_MEM_COMPACT_LEVEL;
import static org.iq80.leveldb.impl.DbConstants.NUM_LEVELS;
import static org.iq80.leveldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static org.iq80.leveldb.impl.VersionSet.MAX_GRAND_PARENT_OVERLAP_BYTES;

// todo this class should be immutable
public class Version
        implements SeekingIterable<InternalKey, Slice>
{
    private final AtomicInteger retained = new AtomicInteger(1);
    private final VersionSet versionSet;
    private final Level0 level0;
    private final List<Level> levels;

    // move these mutable fields somewhere else
    private int compactionLevel;
    private double compactionScore;
    private FileMetaData fileToCompact;
    private int fileToCompactLevel;

    public Version(VersionSet versionSet)
    {
        this.versionSet = versionSet;
        checkArgument(NUM_LEVELS > 1, "levels must be at least 2");

        this.level0 = new Level0(new ArrayList<FileMetaData>(), getTableCache(), getInternalKeyComparator());

        Builder<Level> builder = ImmutableList.builder();
        for (int i = 1; i < NUM_LEVELS; i++) {
            List<FileMetaData> files = new ArrayList<>();
            builder.add(new Level(i, files, getTableCache(), getInternalKeyComparator()));
        }
        this.levels = builder.build();

    }

    public void assertNoOverlappingFiles()
    {
        for (int level = 1; level < NUM_LEVELS; level++) {
            assertNoOverlappingFiles(level);
        }
    }

    public void assertNoOverlappingFiles(int level)
    {
        if (level > 0) {
            Collection<FileMetaData> files = getFiles().asMap().get(level);
            if (files != null) {
                long previousFileNumber = 0;
                InternalKey previousEnd = null;
                for (FileMetaData fileMetaData : files) {
                    if (previousEnd != null) {
                        checkArgument(getInternalKeyComparator().compare(
                                previousEnd,
                                fileMetaData.getSmallest()
                        ) < 0, "Overlapping files %s and %s in level %s", previousFileNumber, fileMetaData.getNumber(), level);
                    }

                    previousFileNumber = fileMetaData.getNumber();
                    previousEnd = fileMetaData.getLargest();
                }
            }
        }
    }

    private TableCache getTableCache()
    {
        return versionSet.getTableCache();
    }

    public final InternalKeyComparator getInternalKeyComparator()
    {
        return versionSet.getInternalKeyComparator();
    }

    public synchronized int getCompactionLevel()
    {
        return compactionLevel;
    }

    public synchronized void setCompactionLevel(int compactionLevel)
    {
        this.compactionLevel = compactionLevel;
    }

    public synchronized double getCompactionScore()
    {
        return compactionScore;
    }

    public synchronized void setCompactionScore(double compactionScore)
    {
        this.compactionScore = compactionScore;
    }

    @Override
    public MergingIterator iterator()
    {
        Builder<InternalIterator> builder = ImmutableList.builder();
        builder.add(level0.iterator());
        builder.addAll(getLevelIterators());
        return new MergingIterator(builder.build(), getInternalKeyComparator());
    }

    List<InternalTableIterator> getLevel0Files()
    {
        Builder<InternalTableIterator> builder = ImmutableList.builder();
        for (FileMetaData file : level0.getFiles()) {
            builder.add(getTableCache().newIterator(file));
        }
        return builder.build();
    }

    List<LevelIterator> getLevelIterators()
    {
        Builder<LevelIterator> builder = ImmutableList.builder();
        for (Level level : levels) {
            if (!level.getFiles().isEmpty()) {
                builder.add(level.iterator());
            }
        }
        return builder.build();
    }

    public LookupResult get(LookupKey key)
    {
        // We can search level-by-level since entries never hop across
        // levels.  Therefore we are guaranteed that if we find data
        // in an smaller level, later levels are irrelevant.
        ReadStats readStats = new ReadStats();
        LookupResult lookupResult = level0.get(key, readStats);
        if (lookupResult == null) {
            for (Level level : levels) {
                lookupResult = level.get(key, readStats);
                if (lookupResult != null) {
                    break;
                }
            }
        }
        updateStats(readStats.getSeekFileLevel(), readStats.getSeekFile());
        return lookupResult;
    }

    int pickLevelForMemTableOutput(Slice smallestUserKey, Slice largestUserKey)
    {
        int level = 0;
        if (!overlapInLevel(0, smallestUserKey, largestUserKey)) {
            // Push to next level if there is no overlap in next level,
            // and the #bytes overlapping in the level after that are limited.
            InternalKey start = new InternalKey(smallestUserKey, MAX_SEQUENCE_NUMBER, ValueType.VALUE);
            InternalKey limit = new InternalKey(largestUserKey, 0, ValueType.VALUE);
            while (level < MAX_MEM_COMPACT_LEVEL) {
                if (overlapInLevel(level + 1, smallestUserKey, largestUserKey)) {
                    break;
                }
                long sum = Compaction.totalFileSize(versionSet.getOverlappingInputs(level + 2, start, limit));
                if (sum > MAX_GRAND_PARENT_OVERLAP_BYTES) {
                    break;
                }
                level++;
            }
        }
        return level;
    }

    public boolean overlapInLevel(int level, Slice smallestUserKey, Slice largestUserKey)
    {
        checkPositionIndex(level, levels.size(), "Invalid level");
        requireNonNull(smallestUserKey, "smallestUserKey is null");
        requireNonNull(largestUserKey, "largestUserKey is null");

        if (level == 0) {
            return level0.someFileOverlapsRange(smallestUserKey, largestUserKey);
        }
        return levels.get(level - 1).someFileOverlapsRange(smallestUserKey, largestUserKey);
    }

    public int numberOfLevels()
    {
        return levels.size() + 1;
    }

    public int numberOfFilesInLevel(int level)
    {
        if (level == 0) {
            return level0.getFiles().size();
        }
        else {
            return levels.get(level - 1).getFiles().size();
        }
    }

    public Multimap<Integer, FileMetaData> getFiles()
    {
        ImmutableMultimap.Builder<Integer, FileMetaData> builder = ImmutableMultimap.builder();
        builder = builder.orderKeysBy(natural());

        builder.putAll(0, level0.getFiles());

        for (Level level : levels) {
            builder.putAll(level.getLevelNumber(), level.getFiles());
        }
        return builder.build();
    }

    public List<FileMetaData> getFiles(int level)
    {
        if (level == 0) {
            return level0.getFiles();
        }
        else {
            return levels.get(level - 1).getFiles();
        }
    }

    public void addFile(int level, FileMetaData fileMetaData)
    {
        if (level == 0) {
            level0.addFile(fileMetaData);
        }
        else {
            levels.get(level - 1).addFile(fileMetaData);
        }
    }

    private boolean updateStats(int seekFileLevel, FileMetaData seekFile)
    {
        if (seekFile == null) {
            return false;
        }

        seekFile.decrementAllowedSeeks();
        if (seekFile.getAllowedSeeks() <= 0 && fileToCompact == null) {
            fileToCompact = seekFile;
            fileToCompactLevel = seekFileLevel;
            return true;
        }
        return false;
    }

    public FileMetaData getFileToCompact()
    {
        return fileToCompact;
    }

    public int getFileToCompactLevel()
    {
        return fileToCompactLevel;
    }

    public long getApproximateOffsetOf(InternalKey key)
    {
        long result = 0;
        for (int level = 0; level < NUM_LEVELS; level++) {
            for (FileMetaData fileMetaData : getFiles(level)) {
                if (getInternalKeyComparator().compare(fileMetaData.getLargest(), key) <= 0) {
                    // Entire file is before "ikey", so just add the file size
                    result += fileMetaData.getFileSize();
                }
                else if (getInternalKeyComparator().compare(fileMetaData.getSmallest(), key) > 0) {
                    // Entire file is after "ikey", so ignore
                    if (level > 0) {
                        // Files other than level 0 are sorted by meta.smallest, so
                        // no further files in this level will contain data for
                        // "ikey".
                        break;
                    }
                }
                else {
                    // "ikey" falls in the range for this table.  Add the
                    // approximate offset of "ikey" within the table.
                    result += getTableCache().getApproximateOffsetOf(fileMetaData, key.encode());
                }
            }
        }
        return result;
    }

    public void retain()
    {
        int was = retained.getAndIncrement();
        assert was > 0 : "Version was retain after it was disposed.";
    }

    public void release()
    {
        int now = retained.decrementAndGet();
        assert now >= 0 : "Version was released after it was disposed.";
        if (now == 0) {
            // The version is now disposed.
            versionSet.removeVersion(this);
        }
    }

    public boolean isDisposed()
    {
        return retained.get() <= 0;
    }
}
