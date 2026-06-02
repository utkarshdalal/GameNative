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

import org.iq80.leveldb.util.Slice;

import static java.util.Objects.requireNonNull;

public class LookupResult
{
    public static LookupResult ok(LookupKey key, Slice value)
    {
        return new LookupResult(key, value, false);
    }

    public static LookupResult deleted(LookupKey key)
    {
        return new LookupResult(key, null, true);
    }

    private final LookupKey key;
    private final Slice value;
    private final boolean deleted;

    private LookupResult(LookupKey key, Slice value, boolean deleted)
    {
        requireNonNull(key, "key is null");
        this.key = key;
        if (value != null) {
            this.value = value.slice();
        }
        else {
            this.value = null;
        }
        this.deleted = deleted;
    }

    public LookupKey getKey()
    {
        return key;
    }

    public Slice getValue()
    {
        if (value == null) {
            return null;
        }
        return value;
    }

    public boolean isDeleted()
    {
        return deleted;
    }
}
