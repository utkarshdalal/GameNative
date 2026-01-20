import os
import json
import sys
import subprocess
import time
from gogdl.dl.dl_utils import get_case_insensitive_name
from ctypes import *
from gogdl.process import Process
import signal
import shutil
import shlex

class NoMoreChildren(Exception):
    pass

def get_flatpak_command(id: str) -> list[str]:
    if sys.platform != "linux":
        return []
    new_process_command = []
    process_command = ["flatpak", "info", id] 
    if os.path.exists("/.flatpak-info"):
        try:
            spawn_test = subprocess.run(["flatpak-spawn", "--host", "ls"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except FileNotFoundError:
            return []
        if spawn_test.returncode != 0:
            return []

        new_process_command = ["flatpak-spawn", "--host"]
        process_command = new_process_command + process_command

    try:
        output = subprocess.run(process_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        if output.returncode == 0:
            return new_process_command + ["flatpak", "run", id]

    except FileNotFoundError:
        pass
    return []


# Supports launching linux builds
def launch(arguments, unknown_args):
    # print(arguments)
    info = load_game_info(arguments.path, arguments.id, arguments.platform)

    wrapper = []
    if arguments.wrapper:
        wrapper = shlex.split(arguments.wrapper)
    envvars = {}

    unified_platform = {"win32": "windows", "darwin": "osx", "linux": "linux"}
    command = list()
    working_dir = arguments.path
    heroic_exe_wrapper = os.environ.get("HEROIC_GOGDL_WRAPPER_EXE")
    # If type is a string we know it's a path to start.sh on linux
    if type(info) != str:
        if sys.platform != "win32":
            if not arguments.dont_use_wine and arguments.platform != unified_platform[sys.platform]:
                if arguments.wine_prefix:
                    envvars["WINEPREFIX"] = arguments.wine_prefix
                wrapper.append(arguments.wine)

        primary_task = get_preferred_task(info, arguments.preferred_task)
        launch_arguments = primary_task.get("arguments")
        compatibility_flags = primary_task.get("compatibilityFlags")
        executable = os.path.join(arguments.path, primary_task["path"])
        if arguments.platform == "linux":
            executable = os.path.join(arguments.path, "game", primary_task["path"])
        if launch_arguments is None:
            launch_arguments = []
        if type(launch_arguments) == str:
            launch_arguments = launch_arguments.replace('\\', '/')
            launch_arguments = shlex.split(launch_arguments)
        if compatibility_flags is None:
            compatibility_flags = []

        relative_working_dir = (
            primary_task["workingDir"] if primary_task.get("workingDir") else ""
        )
        if sys.platform != "win32":
            relative_working_dir = relative_working_dir.replace("\\", os.sep)
            executable = executable.replace("\\", os.sep)
        working_dir = os.path.join(arguments.path, relative_working_dir)

        if not os.path.exists(executable):
            executable = get_case_insensitive_name(executable)
        # Handle case sensitive file systems
        if not os.path.exists(working_dir):
            working_dir = get_case_insensitive_name(working_dir)

        os.chdir(working_dir)
        
        if sys.platform != "win32" and arguments.platform == 'windows' and not arguments.override_exe:
            if "scummvm.exe" in executable.lower():
                flatpak_scummvm = get_flatpak_command("org.scummvm.ScummVM")
                native_scummvm = shutil.which("scummvm")
                if native_scummvm:
                    native_scummvm = [native_scummvm]
            
                native_runner = flatpak_scummvm or native_scummvm
                if native_runner:
                    wrapper = native_runner
                    executable = None
            elif "dosbox.exe" in executable.lower():
                flatpak_dosbox = get_flatpak_command("io.github.dosbox-staging")
                native_dosbox= shutil.which("dosbox")
                if native_dosbox:
                    native_dosbox = [native_dosbox]
                
                native_runner = flatpak_dosbox or native_dosbox
                if native_runner:
                    wrapper = native_runner
                    executable = None

        if len(wrapper) > 0 and wrapper[0] is not None:
            command.extend(wrapper)

        if heroic_exe_wrapper:
            command.append(heroic_exe_wrapper.strip())

        if arguments.override_exe:
            command.append(arguments.override_exe)
            working_dir = os.path.split(arguments.override_exe)[0]
            if not os.path.exists(working_dir):
                working_dir = get_case_insensitive_name(working_dir)
        elif executable:
            command.append(executable)
        command.extend(launch_arguments)
    else:
        if len(wrapper) > 0 and wrapper[0] is not None:
            command.extend(wrapper)

        if heroic_exe_wrapper:
            command.append(heroic_exe_wrapper.strip())

        if arguments.override_exe:
            command.append(arguments.override_exe)
            working_dir = os.path.split(arguments.override_exe)[0]
            # Handle case sensitive file systems
            if not os.path.exists(working_dir):
                working_dir = get_case_insensitive_name(working_dir)
        else:
            command.append(info)

    os.chdir(working_dir)
    command.extend(unknown_args)
    environment = os.environ.copy()
    environment.update(envvars)
    
    if getattr(sys, 'frozen', False) and hasattr(sys, '_MEIPASS'):
        bundle_dir = sys._MEIPASS 
        ld_library = environment.get("LD_LIBRARY_PATH")
        if ld_library:
            splitted = ld_library.split(":")
            try:
                splitted.remove(bundle_dir)
            except ValueError:
                pass
            environment.update({"LD_LIBRARY_PATH": ":".join(splitted)})
    
    print("Launch command:", command)

    status = None
    if sys.platform == 'linux':
        libc = cdll.LoadLibrary("libc.so.6")
        prctl = libc.prctl
        result = prctl(36 ,1, 0, 0, 0, 0) # PR_SET_CHILD_SUBREAPER = 36

        if result == -1:
            print("PR_SET_CHILD_SUBREAPER is not supported by your kernel (Linux 3.4 and above)")
        
        process = subprocess.Popen(command, env=environment)
        process_pid = process.pid

        def iterate_processes():
            for child in Process(os.getpid()).iter_children():
                if child.state == 'Z':
                    continue

                if child.name:
                    yield child

        def hard_sig_handler(signum, _frame):
            for _ in range(3):  # just in case we race a new process.
                for child in Process(os.getpid()).iter_children():
                    try:
                        os.kill(child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass


        def sig_handler(signum, _frame):
            signal.signal(signal.SIGTERM, hard_sig_handler)
            signal.signal(signal.SIGINT, hard_sig_handler)
            for _ in range(3):  # just in case we race a new process.
                for child in Process(os.getpid()).iter_children():
                    try:
                        os.kill(child.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass

        def is_alive():
            return next(iterate_processes(), None) is not None

        signal.signal(signal.SIGTERM, sig_handler)
        signal.signal(signal.SIGINT, sig_handler)

        def reap_children():
            nonlocal status
            while True:
                try:
                    child_pid, child_returncode, _resource_usage = os.wait3(os.WNOHANG)
                except ChildProcessError:
                    raise NoMoreChildren from None  # No processes remain.
                if child_pid == process_pid:
                    status = child_returncode

                if child_pid == 0:
                    break

        try:
            # The initial wait loop:
            #  the initial process may have been excluded. Wait for the game
            #  to be considered "started".
            if not is_alive():
                while not is_alive():
                    reap_children()
                    time.sleep(0.1)
            while is_alive():
                reap_children()
                time.sleep(0.1)
            reap_children()
        except NoMoreChildren:
            print("All processes exited")


    else:
        process = subprocess.Popen(command, env=environment, 
                                   shell=sys.platform=="win32")
        status = process.wait()

    sys.exit(status)


def get_preferred_task(info, index):
    primaryTask = None
    for task in info["playTasks"]:
        if task.get("isPrimary") == True:
            primaryTask = task
            break
    if index is None:
        return primaryTask
    indexI = int(index)
    if len(info["playTasks"]) > indexI:
        return info["playTasks"][indexI]
    
    return primaryTask




def load_game_info(path, id, platform):
    filename = f"goggame-{id}.info"
    abs_path = (
        (
            os.path.join(path, filename)
            if platform == "windows"
            else os.path.join(path, "start.sh")
        )
        if platform != "osx"
        else os.path.join(path, "Contents", "Resources", filename)
    )
    if not os.path.isfile(abs_path):
        sys.exit(1)
    if platform == "linux":
        return abs_path
    with open(abs_path) as f:
        data = f.read()
        f.close()
        return json.loads(data)


