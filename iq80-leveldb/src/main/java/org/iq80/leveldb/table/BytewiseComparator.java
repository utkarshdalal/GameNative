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
package org.iq80.leveldb.table;

import org.iq80.leveldb.util.Slice;

public class BytewiseComparator
        implements UserComparator
{
    @Override
    public String name()
    {
        return "leveldb.BytewiseComparator";
    }

    @Override
    public int compare(Slice sliceA, Slice sliceB)
    {
        return sliceA.compareTo(sliceB);
    }

    @Override
    public Slice findShortestSeparator(
            Slice start,
            Slice limit)
    {
        // Find length of common prefix
        int sharedBytes = BlockBuilder.calculateSharedBytes(start, limit);

        // Do not shorten if one string is a prefix of the other
        if (sharedBytes < Math.min(start.length(), limit.length())) {
            // if we can add one to the last shared byte without overflow and the two keys differ by more than
            // one increment at this location.
            int lastSharedByte = start.getUnsignedByte(sharedBytes);
            if (lastSharedByte < 0xff && lastSharedByte + 1 < limit.getUnsignedByte(sharedBytes)) {
                Slice result = start.copySlice(0, sharedBytes + 1);
                result.setByte(sharedBytes, lastSharedByte + 1);

                assert (compare(result, limit) < 0) : "start must be less than last limit";
                return result;
            }
        }
        return start;
    }

    @Override
    public Slice findShortSuccessor(Slice key)
    {
        // Find first character that can be incremented
        for (int i = 0; i < key.length(); i++) {
            int b = key.getUnsignedByte(i);
            if (b != 0xff) {
                Slice result = key.copySlice(0, i + 1);
                result.setByte(i, b + 1);
                return result;
            }
        }
        // key is a run of 0xffs.  Leave it alone.
        return key;
    }
}
