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

import org.iq80.leveldb.impl.SeekingIterator;

import java.util.Map.Entry;
import java.util.NoSuchElementException;

public abstract class AbstractSeekingIterator<K, V>
        implements SeekingIterator<K, V>
{
    private Entry<K, V> nextElement;

    @Override
    public final void seekToFirst()
    {
        nextElement = null;
        seekToFirstInternal();
    }

    @Override
    public final void seek(K targetKey)
    {
        nextElement = null;
        seekInternal(targetKey);
    }

    @Override
    public final boolean hasNext()
    {
        if (nextElement == null) {
            nextElement = getNextElement();
        }
        return nextElement != null;
    }

    @Override
    public final Entry<K, V> next()
    {
        if (nextElement == null) {
            nextElement = getNextElement();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }
        }

        Entry<K, V> result = nextElement;
        nextElement = null;
        return result;
    }

    @Override
    public final Entry<K, V> peek()
    {
        if (nextElement == null) {
            nextElement = getNextElement();
            if (nextElement == null) {
                throw new NoSuchElementException();
            }
        }

        return nextElement;
    }

    @Override
    public final void remove()
    {
        throw new UnsupportedOperationException();
    }

    protected abstract void seekToFirstInternal();

    protected abstract void seekInternal(K targetKey);

    protected abstract Entry<K, V> getNextElement();
}
