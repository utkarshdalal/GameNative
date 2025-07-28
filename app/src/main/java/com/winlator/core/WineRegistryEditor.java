package com.winlator.core;

import android.util.Log;

import com.winlator.math.Mathf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.ToIntFunction;

public class WineRegistryEditor implements Closeable {
    private final File file;
    private final File cloneFile;
    private boolean modified = false;
    private boolean createKeyIfNotExist = true;
    private int lastParentKeyPosition = 0;
    private String lastParentKey = "";

    public static class Location {
        public final int end;
        public int mbCount;
        public final int offset;
        public final int start;
        private Object tag;

        public Location(int offset, int start, int end) {
            this.offset = offset;
            this.start = start;
            this.end = end;
        }

        public int length() {
            return this.end - this.start;
        }

        public String toString() {
            return this.offset + "," + this.start + "," + this.end;
        }

        public int[] toIntArray() {
            return new int[]{this.offset, this.start, this.end, this.mbCount};
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Location)) {
                return false;
            }
            Location other = (Location) obj;
            return this.offset == other.offset && this.start == other.start && this.end == other.end;
        }
    }

    public WineRegistryEditor(File file) {
        this.file = file;
        cloneFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));
        if (!file.isFile()) {
            try {
                cloneFile.createNewFile();
            }
            catch (IOException e) {
                Log.e("WineRegistryEditor", "Failed to set up editor: " + e);
            }
        }
        else FileUtils.copy(file, cloneFile);
    }

    private static String escape(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String str) {
        return str.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean lineHasName(String line) {
        int index;
        return (index = line.indexOf('"')) != -1 &&
                (index = line.indexOf('"', index)) != -1 &&
                (index = line.indexOf('=', index)) != -1;
    }

    @Override
    public void close() {
        if (modified && cloneFile.exists()) {
            cloneFile.renameTo(file);
        }
        else cloneFile.delete();
    }

    private void resetLastParentKeyPositionIfNeed(String newKey) {
        int lastIndex = newKey.lastIndexOf("\\");
        if (lastIndex == -1) {
            lastParentKeyPosition = 0;
            lastParentKey = "";
            return;
        }

        String parentKey = newKey.substring(0, lastIndex);
        if (!parentKey.equals(lastParentKey)) lastParentKeyPosition = 0;
        lastParentKey = parentKey;
    }

    public void setCreateKeyIfNotExist(boolean createKeyIfNotExist) {
        this.createKeyIfNotExist = createKeyIfNotExist;
    }

    private Location createKey(String key) {
        lastParentKeyPosition = 0;
        Location location = getParentKeyLocation(key);
        boolean success = false;
        int offset = 0;
        int totalLength = 0;

        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length;
            for (int i = 0, end = location != null ? location.end+1 : (int)cloneFile.length(); i < end; i += length) {
                length = Math.min(buffer.length, end - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
                totalLength += length;
            }

            offset = totalLength;
            long ticks1601To1970 = 86400L * (369 * 365 + 89) * 10000000;
            long currentTime = System.currentTimeMillis() + ticks1601To1970;
            String content = "\n["+escape(key)+"] "+((currentTime - ticks1601To1970) / 1000) +
                    String.format(Locale.ENGLISH, "\n#time=%x%08x", currentTime >> 32, (int)currentTime)+"\n";
            writer.write(content);
            totalLength += content.length() - 1;

            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        }
        catch (IOException e) {
            Log.e("WineRegistryEditor", "Failed to create key: " + e);
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
            return new Location(offset, totalLength, totalLength);
        }
        else {
            tempFile.delete();
            return null;
        }
    }

    public String getStringValue(String key, String name) {
        return getStringValue(key, name, null);
    }

    public String getStringValue(String key, String name, String fallback) {
        String value = getRawValue(key, name);
        return value != null ? value.substring(1, value.length() - 1) : fallback;
    }

    public void setStringValue(String key, String name, String value) {
        String str;
        if (value != null) {
            str = "\"" + escape(value) + "\"";
        } else {
            str = "\"\"";
        }
        setRawValue(key, name, str);
    }

    public void setStringValues(String key, String[]... items) {
        String[][] escapedItems = new String[items.length][];
        for (int i = 0; i < items.length; i++) {
            String[] strArr = new String[2];
            strArr[0] = items[i][0];
            strArr[1] = items[i][1] != null ? "\"" + escape(items[i][1]) + "\"" : "\"\"";
            escapedItems[i] = strArr;
        }
        setRawValues(key, escapedItems);
    }

    public Integer getDwordValue(String key, String name) {
        return getDwordValue(key, name, null);
    }

    public Integer getDwordValue(String key, String name, Integer fallback) {
        String value = getRawValue(key, name);
        return value != null ? Integer.decode("0x" + value.substring(6)) : fallback;
    }

    public void setDwordValue(String key, String name, int value) {
        setRawValue(key, name, "dword:"+String.format("%08x", value));
    }

    public void setHexValue(String key, String name, String value) {
        int start = (int)Mathf.roundTo(name.length(), 2) + 7;
        StringBuilder lines = new StringBuilder();
        for (int i = 0, j = start; i < value.length(); i++) {
            if (i > 0 && (i % 2) == 0) lines.append(",");
            if (j++ > 56) {
                lines.append("\\\n  ");
                j = 8;
            }
            lines.append(value.charAt(i));
        }
        setRawValue(key, name, "hex:"+lines);
    }

    public void setHexValue(String key, String name, byte[] bytes) {
        StringBuilder data = new StringBuilder();
        for (byte b : bytes) data.append(String.format(Locale.ENGLISH, "%02x", Byte.toUnsignedInt(b)));
        setHexValue(key, name, data.toString());
    }

    private String getRawValue(String key, String name) {
        lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) return null;

        Location valueLocation = getValueLocation(keyLocation, name);
        if (valueLocation == null) return null;
        boolean success = false;
        char[] buffer = new char[valueLocation.length()];

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            reader.skip(valueLocation.start);
            success = reader.read(buffer) == buffer.length;
        }
        catch (IOException e) {
            Log.e("WineRegistryEditor", "Failed to get raw value: " + e);
        }
        return success ? unescape(new String(buffer)) : null;
    }

    private void setRawValue(String key, String name, String value) {
        resetLastParentKeyPositionIfNeed(key);

        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) {
            if (createKeyIfNotExist) {
                keyLocation = createKey(key);
            }
            else return;
        }

        Location valueLocation = getValueLocation(keyLocation, name);
        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        boolean success = false;

        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length;
            for (int i = 0, end = valueLocation != null ? valueLocation.start : keyLocation.end; i < end; i += length) {
                length = Math.min(buffer.length, end - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
            }

            if (valueLocation == null) {
                writer.write("\n"+(name != null ? "\""+escape(name)+"\"" : "@")+"="+value);
            }
            else {
                writer.write(value);
                reader.skip(valueLocation.length());
            }

            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        }
        catch (IOException e) {
            Log.e("WineRegistryEditor", "Failed to set raw value: " + e);
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
        }
        else tempFile.delete();
    }

    private void setRawValues(String key, String[]... items) {
        int i;
        String str;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) {
            if (this.createKeyIfNotExist) {
                keyLocation = createKey(key);
            } else {
                return;
            }
        }
        ArrayList<Location> valueLocations = new ArrayList<>();
        int i2 = 0;
        while (true) {
            i = -1;
            if (i2 >= items.length) {
                break;
            }
            Location valueLocation = getValueLocation(keyLocation, items[i2][0]);
            if (valueLocation == null) {
                valueLocation = new Location(0, (Integer.MAX_VALUE - items.length) + i2, -1);
            }
            valueLocation.tag = items[i2];
            valueLocations.add(valueLocation);
            i2++;
        }
        valueLocations.sort(Comparator.comparingInt(new ToIntFunction() {
            @Override
            public final int applyAsInt(Object obj) {
                return ((WineRegistryEditor.Location) obj).start;
            }
        }));
        char[] buffer = new char[65536];
        boolean success = false;
        File tempFile = FileUtils.createTempFile(this.file.getParentFile(), FileUtils.getBasename(this.file.getPath()));
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.cloneFile), 65536);
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), 65536);
                int position = 0;
                try {
                    Iterator<Location> it = valueLocations.iterator();
                    while (it.hasNext()) {
                        Location valueLocation2 = it.next();
                        if (valueLocation2.end != i) {
                            int i3 = position;
                            while (true) {
                                int i4 = valueLocation2.start;
                                if (i3 >= i4) {
                                    break;
                                }
                                int length = Math.min(buffer.length, i4 - i3);
                                reader.read(buffer, 0, length);
                                writer.write(buffer, 0, length);
                                position += length;
                                i3 += length;
                            }
                            writer.write(((String[]) valueLocation2.tag)[1]);
                            reader.skip(valueLocation2.length());
                            position += valueLocation2.length();
                            i = -1;
                        }
                    }
                    int i5 = position;
                    while (true) {
                        int i6 = keyLocation.end;
                        if (i5 >= i6) {
                            break;
                        }
                        int length2 = Math.min(buffer.length, i6 - i5);
                        reader.read(buffer, 0, length2);
                        writer.write(buffer, 0, length2);
                        i5 += length2;
                    }
                    Iterator<Location> it2 = valueLocations.iterator();
                    while (it2.hasNext()) {
                        Location valueLocation3 = it2.next();
                        if (valueLocation3.end == -1) {
                            String[] item = (String[]) valueLocation3.tag;
                            StringBuilder sb = new StringBuilder();
                            sb.append("\n");
                            if (item[0] != null) {
                                str = "\"" + escape(item[0]) + "\"";
                            } else {
                                str = "@";
                            }
                            sb.append(str);
                            sb.append("=");
                            sb.append(item[1]);
                            writer.write(sb.toString());
                        }
                    }
                    while (true) {
                        int length3 = reader.read(buffer);
                        if (length3 == -1) {
                            break;
                        } else {
                            writer.write(buffer, 0, length3);
                        }
                    }
                    success = true;
                    writer.close();
                    reader.close();
                } finally {
                }
            } finally {
            }
        } catch (IOException e) {
        }
        if (success) {
            this.modified = true;
            tempFile.renameTo(this.cloneFile);
        } else {
            tempFile.delete();
        }
    }

    public void removeValue(String key, String name) {
        lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) return;

        Location valueLocation = getValueLocation(keyLocation, name);
        if (valueLocation == null) return;
        removeRegion(valueLocation);
    }

    public boolean removeKey(String key) {
        return removeKey(key, false);
    }

    public boolean removeKey(String key, boolean removeTree) {
        lastParentKeyPosition = 0;
        boolean removed = false;
        if (removeTree) {
            Location location;
            while ((location = getKeyLocation(key, true)) != null) {
                if (removeRegion(location)) removed = true;
            }
        }
        else {
            Location location = getKeyLocation(key, false);
            if (location != null && removeRegion(location)) removed = true;
        }
        return removed;
    }

    private boolean removeRegion(Location location) {
        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        boolean success = false;

        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length = 0;
            for (int i = 0; i < location.offset; i += length) {
                length = Math.min(buffer.length, location.offset - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
            }

            boolean skipLine = length > 1 && buffer[length-1] == '\n';
            reader.skip(location.end - location.offset + (skipLine ? 1 : 0));
            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        }
        catch (IOException e) {
            Log.e("WineRegistryEditor", "Failed to remove region: " + e);
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
        }
        else tempFile.delete();
        return success;
    }

    private Location getKeyLocation(String key) {
        return getKeyLocation(key, false);
    }

    private Location getKeyLocation(String key, boolean keyAsPrefix) {
        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            int lastIndex = key.lastIndexOf("\\");
            String parentKey = lastParentKeyPosition == 0 && lastIndex != -1 ? "["+escape(key.substring(0, lastIndex)) : null;

            if (lastParentKeyPosition > 0) reader.skip(lastParentKeyPosition);
            key = "["+escape(key)+(!keyAsPrefix ? "]" : "");
            int totalLength = lastParentKeyPosition;
            int start = -1;
            int end = -1;
            int emptyLines = 0;
            int offset = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                if (start == -1) {
                    if (parentKey != null && line.startsWith(parentKey)) {
                        lastParentKeyPosition = totalLength;
                        parentKey = null;
                    }

                    if (parentKey == null && line.startsWith(key)) {
                        offset = totalLength - 1;
                        start = totalLength + line.length() + 1;
                    }
                }
                else {
                    if (line.startsWith("[")) {
                        end = Math.max(-1, totalLength - emptyLines - 1);
                        break;
                    }
                    else emptyLines = line.isEmpty() ? emptyLines + 1 : 0;
                }
                totalLength += line.length() + 1;
            }

            if (end == -1) end = totalLength - 1;
            return start != -1 ? new Location(offset, start, end) : null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private Location getParentKeyLocation(String key) {
        String[] parts = key.split("\\\\");
        ArrayList<String> stack = new ArrayList<>(Arrays.asList(parts).subList(0, parts.length - 1));

        while (!stack.isEmpty()) {
            String currentKey = String.join("\\", stack);
            Location location = getKeyLocation(currentKey, true);
            if (location != null) return location;
            stack.remove(stack.size()-1);
        }

        return null;
    }

    private Location getValueLocation(Location keyLocation, String name) {
        if (keyLocation.start == keyLocation.end) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            reader.skip(keyLocation.start);
            name = name != null ? "\""+escape(name)+"\"=" : "@=";
            int totalLength = 0;
            int start = -1;
            int end = -1;
            int offset = 0;

            String line;
            while ((line = reader.readLine()) != null && totalLength < keyLocation.length()) {
                if (start == -1) {
                    if (line.startsWith(name)) {
                        offset = totalLength - 1;
                        start = totalLength + name.length();
                    }
                }
                else {
                    if (line.isEmpty() || lineHasName(line)) {
                        end = totalLength - 1;
                        break;
                    }
                }
                totalLength += line.length() + 1;
            }

            if (end == -1) end = totalLength - 1;
            return start != -1 ? new Location(keyLocation.start + offset, keyLocation.start + start, keyLocation.start + end) : null;
        }
        catch (IOException e) {
            return null;
        }
    }
}
