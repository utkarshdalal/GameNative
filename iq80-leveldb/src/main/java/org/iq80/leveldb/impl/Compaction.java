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
import org.iq80.leveldb.table.UserComparator;
import org.iq80.leveldb.util.Slice;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.DbConstants.NUM_LEVELS;
import static org.iq80.leveldb.impl.VersionSet.MAX_GRAND_PARENT_OVERLAP_BYTES;

// A Compaction encapsulates information about a compaction.
public class Compaction
{
    private final Version inputVersion;
    private final int level;

    // Each compaction reads inputs from "level" and "level+1"
    private final List<FileMetaData> levelInputs;
    private final List<FileMetaData> levelUpInputs;
    private final List<FileMetaData> grandparents;
    private final List<FileMetaData>[] inputs;

    private final long maxOutputFileSize;
    private final VersionEdit edit = new VersionEdit();

    // State used to check for number of of overlapping grandparent files
    // (parent == level_ + 1, grandparent == level_ + 2)

    // Index in grandparent_starts_
    private int grandparentIndex;

    // Some output key has been seen
    private boolean seenKey;

    // Bytes of overlap between current output and grandparent files
    private long overlappedBytes;

    // State for implementing IsBaseLevelForKey

    // levelPointers holds indices into inputVersion -> levels: our state
    // is that we are positioned at one of the file ranges for each
    // higher level than the ones involved in this compaction (i.e. for
    // all L >= level_ + 2).
    private final int[] levelPointers = new int[NUM_LEVELS];

    public Compaction(Version inputVersion, int level, List<FileMetaData> levelInputs, List<FileMetaData> levelUpInputs, List<FileMetaData> grandparents)
    {
        this.inputVersion = inputVersion;
        this.level = level;
        this.levelInputs = levelInputs;
        this.levelUpInputs = levelUpInputs;
        this.grandparents = ImmutableList.copyOf(requireNonNull(grandparents, "grandparents is null"));
        this.maxOutputFileSize = VersionSet.maxFileSizeForLevel(level);
        this.inputs = new List[] {levelInputs, levelUpInputs};
    }

    public int getLevel()
    {
        return level;
    }

    public List<FileMetaData> getLevelInputs()
    {
        return levelInputs;
    }

    public List<FileMetaData> getLevelUpInputs()
    {
        return levelUpInputs;
    }

    public VersionEdit getEdit()
    {
        return edit;
    }

    // Return the ith input file at "level()+which" ("which" must be 0 or 1).
    public FileMetaData input(int which, int i)
    {
        checkArgument(which == 0 || which == 1, "which must be either 0 or 1");
        if (which == 0) {
            return levelInputs.get(i);
        }
        else {
            return levelUpInputs.get(i);
        }
    }

    // Maximum size of files to build during this compaction.
    public long getMaxOutputFileSize()
    {
        return maxOutputFileSize;
    }

    // Is this a trivial compaction that can be implemented by just
    // moving a single input file to the next level (no merging or splitting)
    public boolean isTrivialMove()
    {
        // Avoid a move if there is lots of overlapping grandparent data.
        // Otherwise, the move could create a parent file that will require
        // a very expensive merge later on.
        return (levelInputs.size() == 1 &&
                levelUpInputs.isEmpty() &&
                totalFileSize(grandparents) <= MAX_GRAND_PARENT_OVERLAP_BYTES);

    }

    public static long totalFileSize(List<FileMetaData> files)
    {
        long sum = 0;
        for (FileMetaData file : files) {
            sum += file.getFileSize();
        }
        return sum;
    }

    // Add all inputs to this compaction as delete operations to *edit.
    public void addInputDeletions(VersionEdit edit)
    {
        for (FileMetaData input : levelInputs) {
            edit.deleteFile(level, input.getNumber());
        }
        for (FileMetaData input : levelUpInputs) {
            edit.deleteFile(level + 1, input.getNumber());
        }
    }

    // Returns true if the information we have available guarantees that
    // the compaction is producing data in "level+1" for which no data exists
    // in levels greater than "level+1".
    public boolean isBaseLevelForKey(Slice userKey)
    {
        // Maybe use binary search to find right entry instead of linear search?
        UserComparator userComparator = inputVersion.getInternalKeyComparator().getUserComparator();
        for (int level = this.level + 2; level < NUM_LEVELS; level++) {
            List<FileMetaData> files = inputVersion.getFiles(level);
            while (levelPointers[level] < files.size()) {
                FileMetaData f = files.get(levelPointers[level]);
                if (userComparator.compare(userKey, f.getLargest().getUserKey()) <= 0) {
                    // We've advanced far enough
                    if (userComparator.compare(userKey, f.getSmallest().getUserKey()) >= 0) {
                        // Key falls in this file's range, so definitely not base level
                        return false;
                    }
                    break;
                }
                levelPointers[level]++;
            }
        }
        return true;
    }

    // Returns true iff we should stop building the current output
    // before processing "internal_key".
    public boolean shouldStopBefore(InternalKey internalKey)
    {
        // Scan to find earliest grandparent file that contains key.
        InternalKeyComparator internalKeyComparator = inputVersion.getInternalKeyComparator();
        while (grandparentIndex < grandparents.size() && internalKeyComparator.compare(internalKey, grandparents.get(grandparentIndex).getLargest()) > 0) {
            if (seenKey) {
                overlappedBytes += grandparents.get(grandparentIndex).getFileSize();
            }
            grandparentIndex++;
        }
        seenKey = true;

        if (overlappedBytes > MAX_GRAND_PARENT_OVERLAP_BYTES) {
            // Too much overlap for current output; start new output
            overlappedBytes = 0;
            return true;
        }
        else {
            return false;
        }
    }

    public List<FileMetaData>[] getInputs()
    {
        return inputs;
    }
}
