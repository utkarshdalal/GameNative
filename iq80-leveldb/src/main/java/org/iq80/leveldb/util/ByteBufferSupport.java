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


import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

public final class ByteBufferSupport
{
    private static final MethodHandle INVOKE_CLEANER;

    static {
        MethodHandle invoker;
        try {
            // Java 9 added an invokeCleaner method to Unsafe to work around
            // module visibility issues for code that used to rely on DirectByteBuffer's cleaner()
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            invoker = MethodHandles.lookup()
                    .findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class))
                    .bindTo(theUnsafe.get(null));
        }
        catch (Exception e) {
            // fall back to pre-java 9 compatible behavior
            try {
                Class<?> directByteBufferClass = Class.forName("java.nio.DirectByteBuffer");
                Class<?> cleanerClass = Class.forName("sun.misc.Cleaner");

                Method cleanerMethod = directByteBufferClass.getDeclaredMethod("cleaner");
                cleanerMethod.setAccessible(true);
                MethodHandle getCleaner = MethodHandles.lookup().unreflect(cleanerMethod);

                Method cleanMethod = cleanerClass.getDeclaredMethod("clean");
                cleanerMethod.setAccessible(true);
                MethodHandle clean = MethodHandles.lookup().unreflect(cleanMethod);

                clean = MethodHandles.dropArguments(clean, 1, directByteBufferClass);
                invoker = MethodHandles.foldArguments(clean, getCleaner);
            }
            catch (Exception e1) {
                throw new AssertionError(e1);
            }
        }
        INVOKE_CLEANER = invoker;
    }

    private ByteBufferSupport()
    {
    }

    public static void unmap(MappedByteBuffer buffer)
    {
        try {
            INVOKE_CLEANER.invoke(buffer);
        }
        catch (Throwable ignored) {
            throw new RuntimeException(ignored);
        }
    }
}
