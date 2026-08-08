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

import org.iq80.leveldb.table.UserComparator;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

public class InternalKeyComparator
        implements Comparator<InternalKey>
{
    private final UserComparator userComparator;

    public InternalKeyComparator(UserComparator userComparator)
    {
        this.userComparator = userComparator;
    }

    public UserComparator getUserComparator()
    {
        return userComparator;
    }

    public String name()
    {
        return this.userComparator.name();
    }

    @Override
    public int compare(InternalKey left, InternalKey right)
    {
        int result = userComparator.compare(left.getUserKey(), right.getUserKey());
        if (result != 0) {
            return result;
        }

        return Long.compare(right.getSequenceNumber(), left.getSequenceNumber()); // reverse sorted version numbers
    }

    /**
     * Returns {@code true} if each element in {@code iterable} after the first is
     * greater than or equal to the element that preceded it, according to this
     * ordering. Note that this is always true when the iterable has fewer than
     * two elements.
     */
    public boolean isOrdered(InternalKey... keys)
    {
        return isOrdered(Arrays.asList(keys));
    }

    /**
     * Returns {@code true} if each element in {@code iterable} after the first is
     * greater than or equal to the element that preceded it, according to this
     * ordering. Note that this is always true when the iterable has fewer than
     * two elements.
     */
    public boolean isOrdered(Iterable<InternalKey> keys)
    {
        Iterator<InternalKey> iterator = keys.iterator();
        if (!iterator.hasNext()) {
            return true;
        }

        InternalKey previous = iterator.next();
        while (iterator.hasNext()) {
            InternalKey next = iterator.next();
            if (compare(previous, next) > 0) {
                return false;
            }
            previous = next;
        }
        return true;
    }
}
