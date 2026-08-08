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

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

public class IntVector
{
    private int size;
    private int[] values;

    public IntVector(int initialCapacity)
    {
        this.values = new int[initialCapacity];
    }

    public int size()
    {
        return size;
    }

    public void clear()
    {
        size = 0;
    }

    public void add(int value)
    {
        checkArgument(size + 1 >= 0, "Invalid minLength: %s", size + 1);

        ensureCapacity(size + 1);

        values[size++] = value;
    }

    private void ensureCapacity(int minCapacity)
    {
        if (values.length >= minCapacity) {
            return;
        }

        int newLength = values.length;
        if (newLength == 0) {
            newLength = 1;
        }
        else {
            newLength <<= 1;

        }
        values = Arrays.copyOf(values, newLength);
    }

    public int[] values()
    {
        return Arrays.copyOf(values, size);
    }

    public void write(SliceOutput sliceOutput)
    {
        for (int index = 0; index < size; index++) {
            sliceOutput.writeInt(values[index]);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("IntVector");
        sb.append("{size=").append(size);
        sb.append(", values=").append(Arrays.toString(values));
        sb.append('}');
        return sb.toString();
    }
}
