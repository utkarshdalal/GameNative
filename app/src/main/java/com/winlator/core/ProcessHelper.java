package com.winlator.core;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

public abstract class ProcessHelper {
    public static final boolean PRINT_DEBUG = true; // FIXME change to false
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;
    private static final byte SIGTERM = 15;
    private static final byte SIGKILL = 9;

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
    }

    public static void terminateProcess(int pid) {
        Process.sendSignal(pid, SIGTERM);
//        Log.d("ProcessHelper", "Process terminated with pid: " + pid);
    }

    public static void terminateAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            terminateProcess(Integer.parseInt(process));
        }
    }

    public static void pauseAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            suspendProcess(Integer.parseInt(process));
        }
    }

    public static void resumeAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            resumeProcess(Integer.parseInt(process));
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    public static class ProcessInfo {
        public final int pid;
        public final int ppid;
        public final String name;

        public ProcessInfo(int pid, int ppid, String name) {
            this.pid = pid;
            this.ppid = ppid;
            this.name = name;
        }
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        int pid = -1;
        java.lang.Process process = null;
        try {
            Log.d("ProcessHelper", "Executing: " + Arrays.toString(splitCommand(command)) + ", " + Arrays.toString(envp) + ", " + workingDir);
            process = Runtime.getRuntime().exec(splitCommand(command), envp, workingDir);

            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }
//            Uncomment the following lines to see logs from wine
//            createDebugThread(process.getInputStream(), "STDOUT", pid);
//            createDebugThread(process.getErrorStream(), "STDERR", pid);

            if (terminationCallback != null) createWaitForThread(process, terminationCallback);
        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Failed to execute command: " + e);
            if (process != null) process.destroyForcibly();
            if (terminationCallback != null) terminationCallback.call(-1);
        }
        return pid;
    }

    public static List<ProcessInfo> listSubProcesses() {
        List<ProcessInfo> processes = new ArrayList<>();
        String myUser = null;

        // First get our username using the id command
        try {
            java.lang.Process idProcess = Runtime.getRuntime().exec("id");
            BufferedReader idReader = new BufferedReader(new InputStreamReader(idProcess.getInputStream()));
            String idOutput = idReader.readLine();
            if (idOutput != null) {
                // id output format: uid=10290(u0_a290) gid=10290(u0_a290) ...
                int startIndex = idOutput.indexOf('(');
                int endIndex = idOutput.indexOf(')');
                if (startIndex != -1 && endIndex != -1) {
                    myUser = idOutput.substring(startIndex + 1, endIndex);
                }
            }
        } catch (IOException e) {
            Log.e("ProcessHelper", "Failed to retrieve user id in order to list processes: " + e);
            return processes;
        }

        if (myUser == null) {
            return processes;
        }

        // Log.d("ProcessHelper", "Found user value to be: " + myUser);

        // Now get the processes
        try {
            java.lang.Process process = Runtime.getRuntime().exec("ps -A -o USER,PID,PPID,VSZ,RSS,WCHAN,ADDR,S,NAME");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            // Skip header line
            reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 9) {
                    String user = parts[0];
                    int pid = Integer.parseInt(parts[1]);
                    int ppid = Integer.parseInt(parts[2]);
                    String processName = parts[8];

                    // Check if process belongs to our app (same user)
                    if (user.equals(myUser) && pid != Process.myPid()) {
                        ProcessInfo info = new ProcessInfo(pid, ppid, processName);
                        processes.add(info);
                    }
                }
            }
        } catch (IOException e) {
            Log.e("ProcessHelper", "Failed to list processes: " + e);
        }

        return processes;
    }

    private static void createDebugThread(final InputStream inputStream) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PRINT_DEBUG) System.out.println(line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                    }
                }
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Error on debug thread: " + e);
            }
        });
    }

    private static void createDebugThread(final InputStream inputStream, final String streamType, final int pid) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Always log to debug log
                    if (streamType != null && pid != -1) {
                        Log.d("ProcessOutput", "[PID:" + pid + "][" + streamType + "] " + line);
                    } else {
                        // Always log even if streamType/pid not provided
                        Log.d("ProcessOutput", line);
                    }

                    if (PRINT_DEBUG) System.out.println(line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                    }
                }
            }
            catch (IOException e) {}
        });
    }


    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int status = process.waitFor();
                    terminationCallback.call(status);
                }
                catch (InterruptedException e) {
                    Log.e("ProcessHelper", "Error waiting for process termination", e);
                }
            }
        });
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);
            char quoteChar = '"';

            if (startedQuotes) {
                if (currChar == quoteChar) {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += quoteChar;
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"' || currChar == '\'') {
                if (currChar == '\'') quoteChar = '\'';
                startedQuotes = true;
                value += quoteChar;
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static ArrayList<String> listRunningWineProcesses(){
        File proc = new File("/proc");
        String[] filters = {"wine", "exe"};
        String[] allPids;
        ArrayList<String> filteredPids = new ArrayList<String>();
        List<String> filterList = Arrays.asList(filters);
        allPids = proc.list(new FilenameFilter(){
            public boolean accept(File proc, String filename){
                return new File(proc, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });

        for (int index = 0; index < allPids.length; index++){
            String data = "";
            try {
                FileInputStream fr = new FileInputStream(proc + "/" + allPids[index] + "/stat");
                BufferedReader br = new BufferedReader(new InputStreamReader(fr));
                data = br.readLine();
            }
            catch (IOException e) {}
            for (String filter : filterList) {
                if (data.contains(filter))
                    filteredPids.add(allPids[index]);
            }
        }
        return filteredPids;
    }
}
