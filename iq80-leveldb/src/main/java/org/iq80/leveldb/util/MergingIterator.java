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
package org.iq80.leveldb.util;

import org.iq80.leveldb.impl.InternalKey;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public final class MergingIterator
        extends AbstractSeekingIterator<InternalKey, Slice>
{
    private final List<? extends InternalIterator> levels;
    private final PriorityQueue<ComparableIterator> priorityQueue;
    private final Comparator<InternalKey> comparator;

    public MergingIterator(List<? extends InternalIterator> levels, Comparator<InternalKey> comparator)
    {
        this.levels = levels;
        this.comparator = comparator;

        this.priorityQueue = new PriorityQueue<>(levels.size() + 1);
        resetPriorityQueue(comparator);
    }

    @Override
    protected void seekToFirstInternal()
    {
        for (InternalIterator level : levels) {
            level.seekToFirst();
        }
        resetPriorityQueue(comparator);
    }

    @Override
    protected void seekInternal(InternalKey targetKey)
    {
        for (InternalIterator level : levels) {
            level.seek(targetKey);
        }
        resetPriorityQueue(comparator);
    }

    private void resetPriorityQueue(Comparator<InternalKey> comparator)
    {
        int i = 1;
        for (InternalIterator level : levels) {
            if (level.hasNext()) {
                priorityQueue.add(new ComparableIterator(level, comparator, i++, level.next()));
            }
        }
    }

    @Override
    protected Entry<InternalKey, Slice> getNextElement()
    {
        Entry<InternalKey, Slice> result = null;
        ComparableIterator nextIterator = priorityQueue.poll();
        if (nextIterator != null) {
            result = nextIterator.next();
            if (nextIterator.hasNext()) {
                priorityQueue.add(nextIterator);
            }
        }
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MergingIterator");
        sb.append("{levels=").append(levels);
        sb.append(", comparator=").append(comparator);
        sb.append('}');
        return sb.toString();
    }

    private static class ComparableIterator
            implements Iterator<Entry<InternalKey, Slice>>, Comparable<ComparableIterator>
    {
        private final InternalIterator iterator;
        private final Comparator<InternalKey> comparator;
        private final int ordinal;
        private Entry<InternalKey, Slice> nextElement;

        private ComparableIterator(InternalIterator iterator, Comparator<InternalKey> comparator, int ordinal, Entry<InternalKey, Slice> nextElement)
        {
            this.iterator = iterator;
            this.comparator = comparator;
            this.ordinal = ordinal;
            this.nextElement = nextElement;
        }

        @Override
        public boolean hasNext()
        {
            return nextElement != null;
        }

        @Override
        public Entry<InternalKey, Slice> next()
        {
            if (nextElement == null) {
                throw new NoSuchElementException();
            }

            Entry<InternalKey, Slice> result = nextElement;
            if (iterator.hasNext()) {
                nextElement = iterator.next();
            }
            else {
                nextElement = null;
            }
            return result;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ComparableIterator comparableIterator = (ComparableIterator) o;

            if (ordinal != comparableIterator.ordinal) {
                return false;
            }
            if (nextElement != null ? !nextElement.equals(comparableIterator.nextElement) : comparableIterator.nextElement != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode()
        {
            int result = ordinal;
            result = 31 * result + (nextElement != null ? nextElement.hashCode() : 0);
            return result;
        }

        @Override
        public int compareTo(ComparableIterator that)
        {
            int result = comparator.compare(this.nextElement.getKey(), that.nextElement.getKey());
            if (result == 0) {
                result = Integer.compare(this.ordinal, that.ordinal);
            }
            return result;
        }
    }
}
