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

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBFactory;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class Iq80DBFactory
        implements DBFactory
{
    public static final int CPU_DATA_MODEL;

    static {
        boolean is64bit;
        if (System.getProperty("os.name").contains("Windows")) {
            is64bit = System.getenv("ProgramFiles(x86)") != null;
        }
        else {
            is64bit = System.getProperty("os.arch").contains("64");
        }
        CPU_DATA_MODEL = is64bit ? 64 : 32;
    }

    // We only use MMAP on 64 bit systems since it's really easy to run out of
    // virtual address space on a 32 bit system when all the data is getting mapped
    // into memory.  If you really want to use MMAP anyways, use -Dleveldb.mmap=true
    public static final boolean USE_MMAP = Boolean.parseBoolean(System.getProperty("leveldb.mmap", "" + (CPU_DATA_MODEL > 32)));

    public static final String VERSION;

    static {
        String v = "unknown";
        InputStream is = Iq80DBFactory.class.getResourceAsStream("version.txt");
        try {
            v = new BufferedReader(new InputStreamReader(is, UTF_8)).readLine();
        }
        catch (Throwable e) {
        }
        finally {
            try {
                is.close();
            }
            catch (Throwable e) {
            }
        }
        VERSION = v;
    }

    public static final Iq80DBFactory factory = new Iq80DBFactory();

    @Override
    public DB open(File path, Options options)
            throws IOException
    {
        return new DbImpl(options, path);
    }

    @Override
    public void destroy(File path, Options options)
            throws IOException
    {
        // TODO: This should really only delete leveldb-created files.
        FileUtils.deleteRecursively(path);
    }

    @Override
    public void repair(File path, Options options)
            throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        return String.format("iq80 leveldb version %s", VERSION);
    }

    public static byte[] bytes(String value)
    {
        return (value == null) ? null : value.getBytes(UTF_8);
    }

    public static String asString(byte[] value)
    {
        return (value == null) ? null : new String(value, UTF_8);
    }
}
