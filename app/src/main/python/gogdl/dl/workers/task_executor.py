import os
import shutil
import sys
import stat
import traceback
import time
import requests
import zlib
import hashlib
from io import BytesIO
from typing import Optional, Union
from copy import copy, deepcopy
from gogdl.dl import dl_utils
from dataclasses import dataclass
from enum import Enum, auto
from gogdl.dl.objects.generic import TaskFlag, TerminateWorker
from gogdl.xdelta import patcher


class FailReason(Enum):
    UNKNOWN = 0
    CHECKSUM = auto()
    CONNECTION = auto()
    UNAUTHORIZED = auto()
    MISSING_CHUNK = auto()


@dataclass
class DownloadTask:
    product_id: str

@dataclass
class DownloadTask1(DownloadTask):
    offset: int
    size: int
    compressed_sum: str
    temp_file: str  # Use temp file instead of memory segment

@dataclass
class DownloadTask2(DownloadTask):
    compressed_sum: str
    temp_file: str  # Use temp file instead of memory segment


@dataclass
class WriterTask:
    destination: str
    file_path: str
    flags: TaskFlag

    hash: Optional[str] = None
    size: Optional[int] = None
    temp_file: Optional[str] = None  # Use temp file instead of shared memory
    old_destination: Optional[str] = None
    old_file: Optional[str] = None
    old_offset: Optional[int] = None
    patch_file: Optional[str] = None

@dataclass
class DownloadTaskResult:
    success: bool
    fail_reason: Optional[FailReason]
    task: Union[DownloadTask2, DownloadTask1]
    temp_file: Optional[str] = None
    download_size: Optional[int] = None
    decompressed_size: Optional[int] = None

@dataclass
class WriterTaskResult:
    success: bool
    task: Union[WriterTask, TerminateWorker]
    written: int = 0


def download_worker(download_queue, results_queue, speed_queue, secure_links, temp_dir, game_id):
    """Download worker function that runs in a thread"""
    session = requests.session()
    
    while True:
        # Check for cancellation signal before processing next task
        try:
            import builtins
            flag_name = f'GOGDL_CANCEL_{game_id}'
            if hasattr(builtins, flag_name) and getattr(builtins, flag_name, False):
                session.close()
                return  # Exit worker thread if cancelled
        except:
            pass  # Continue if cancellation check fails
            
        try:
            task: Union[DownloadTask1, DownloadTask2, TerminateWorker] = download_queue.get(timeout=1)
        except:
            continue

        if isinstance(task, TerminateWorker):
            break

        if type(task) == DownloadTask2:
            download_v2_chunk(task, session, secure_links, results_queue, speed_queue, game_id)
        elif type(task) == DownloadTask1:
            download_v1_chunk(task, session, secure_links, results_queue, speed_queue, game_id)

    session.close()


def download_v2_chunk(task: DownloadTask2, session, secure_links, results_queue, speed_queue, game_id):
    retries = 5 
    urls = secure_links[task.product_id]
    compressed_md5 = task.compressed_sum

    endpoint = deepcopy(urls[0])  # Use deepcopy for thread safety
    if task.product_id != 'redist':
        endpoint["parameters"]["path"] += f"/{dl_utils.galaxy_path(compressed_md5)}"
        url = dl_utils.merge_url_with_params(
            endpoint["url_format"], endpoint["parameters"]
        )
    else:
        endpoint["url"] += "/" + dl_utils.galaxy_path(compressed_md5)
        url = endpoint["url"]

    buffer = bytes()
    compressed_sum = hashlib.md5()
    download_size = 0
    response = None
    
    while retries > 0:
        buffer = bytes()
        compressed_sum = hashlib.md5()
        download_size = 0
        decompressor = zlib.decompressobj()
        
        try:
            response = session.get(url, stream=True, timeout=10)
            response.raise_for_status()
            for chunk in response.iter_content(1024 * 512):
                # Check for cancellation during download
                try:
                    import builtins
                    flag_name = f'GOGDL_CANCEL_{game_id}'
                    if hasattr(builtins, flag_name) and getattr(builtins, flag_name, False):
                        return  # Exit immediately if cancelled
                except:
                    pass
                    
                download_size += len(chunk)
                compressed_sum.update(chunk)
                decompressed = decompressor.decompress(chunk)
                buffer += decompressed
                speed_queue.put((len(chunk), len(decompressed)))

        except Exception as e:
            print("Connection failed", e)
            if response and response.status_code == 401:
                results_queue.put(DownloadTaskResult(False, FailReason.UNAUTHORIZED, task))
                return
            retries -= 1
            time.sleep(2)
            continue
        break
    else:
        results_queue.put(DownloadTaskResult(False, FailReason.CHECKSUM, task))
        return

    decompressed_size = len(buffer)
    
    # Write to temp file instead of shared memory
    try:
        with open(task.temp_file, 'wb') as f:
            f.write(buffer)
    except Exception as e:
        print("ERROR writing temp file", e)
        results_queue.put(DownloadTaskResult(False, FailReason.UNKNOWN, task))
        return 

    if compressed_sum.hexdigest() != compressed_md5:
        results_queue.put(DownloadTaskResult(False, FailReason.CHECKSUM, task))
        return 

    results_queue.put(DownloadTaskResult(True, None, task, temp_file=task.temp_file, download_size=download_size, decompressed_size=decompressed_size))


def download_v1_chunk(task: DownloadTask1, session, secure_links, results_queue, speed_queue, game_id):
    retries = 5
    urls = secure_links[task.product_id]

    response = None
    if type(urls) == str:
        url = urls
    else:
        endpoint = deepcopy(urls[0])
        endpoint["parameters"]["path"] += "/main.bin"
        url = dl_utils.merge_url_with_params(
            endpoint["url_format"], endpoint["parameters"]
        )
    range_header = dl_utils.get_range_header(task.offset, task.size)

    # Stream directly to temp file for V1 to avoid memory issues with large files
    download_size = 0
    while retries > 0:
        download_size = 0
        try:
            response = session.get(url, stream=True, timeout=10, headers={'Range': range_header})
            response.raise_for_status()
            
            # Stream directly to temp file instead of loading into memory
            with open(task.temp_file, 'wb') as temp_f:
                for chunk in response.iter_content(1024 * 512):  # 512KB chunks
                    # Check for cancellation during download
                    try:
                        import builtins
                        flag_name = f'GOGDL_CANCEL_{game_id}'
                        if hasattr(builtins, flag_name) and getattr(builtins, flag_name, False):
                            return  # Exit immediately if cancelled
                    except:
                        pass
                        
                    temp_f.write(chunk)
                    download_size += len(chunk)
                    speed_queue.put((len(chunk), len(chunk)))
                    
        except Exception as e:
            print("Connection failed", e)
            if response and response.status_code == 401:
                results_queue.put(DownloadTaskResult(False, FailReason.UNAUTHORIZED, task))
                return
            retries -= 1
            time.sleep(2)
            continue
        break
    else:
        results_queue.put(DownloadTaskResult(False, FailReason.CHECKSUM, task))
        return

    # Verify file size
    if download_size != task.size:
        results_queue.put(DownloadTaskResult(False, FailReason.CHECKSUM, task))
        return 

    results_queue.put(DownloadTaskResult(True, None, task, temp_file=task.temp_file, download_size=download_size, decompressed_size=download_size))


def writer_worker(writer_queue, results_queue, speed_queue, cache, temp_dir):
    """Writer worker function that runs in a thread"""
    file_handle = None
    current_file = ''

    while True:
        try:
            task: Union[WriterTask, TerminateWorker] = writer_queue.get(timeout=2)
        except:
            continue

        if isinstance(task, TerminateWorker):
            results_queue.put(WriterTaskResult(True, task))
            break

        written = 0
        
        task_path = dl_utils.get_case_insensitive_name(os.path.join(task.destination, task.file_path))
        split_path = os.path.split(task_path)
        if split_path[0] and not os.path.exists(split_path[0]):
            dl_utils.prepare_location(split_path[0])

        if task.flags & TaskFlag.CREATE_FILE:
            open(task_path, 'a').close()
            results_queue.put(WriterTaskResult(True, task))
            continue

        elif task.flags & TaskFlag.OPEN_FILE:
            if file_handle:
                print("Opening on unclosed file")
                file_handle.close()
            file_handle = open(task_path, 'wb')
            current_file = task_path
            results_queue.put(WriterTaskResult(True, task))
            continue
            
        elif task.flags & TaskFlag.CLOSE_FILE:
            if file_handle:
                file_handle.close()
                file_handle = None
            results_queue.put(WriterTaskResult(True, task))
            continue

        elif task.flags & TaskFlag.COPY_FILE:
            if file_handle and task.file_path == current_file:
                print("Copy on unclosed file")
                file_handle.close()
                file_handle = None

            if not task.old_file:
                results_queue.put(WriterTaskResult(False, task))
                continue

            dest = task.old_destination or task.destination
            try:
                shutil.copy(dl_utils.get_case_insensitive_name(os.path.join(dest, task.old_file)), task_path)
            except shutil.SameFileError:
                pass
            except Exception:
                results_queue.put(WriterTaskResult(False, task))
                continue
            results_queue.put(WriterTaskResult(True, task))
            continue

        elif task.flags & TaskFlag.MAKE_EXE:
            if file_handle and task.file_path == current_file:
                print("Making exe on unclosed file")
                file_handle.close()
                file_handle = None
            if sys.platform != 'win32':
                try:
                    st = os.stat(task_path)
                    os.chmod(task_path, st.st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
                except Exception as e:
                    results_queue.put(WriterTaskResult(False, task))
                    continue
            results_queue.put(WriterTaskResult(True, task))
            continue

        try:
            if task.temp_file:
                if not task.size:
                    print("No size")
                    results_queue.put(WriterTaskResult(False, task))
                    continue
                    
                # Read from temp file instead of shared memory
                with open(task.temp_file, 'rb') as temp_f:
                    left = task.size
                    while left > 0:
                        chunk = temp_f.read(min(1024 * 1024, left))   
                        written += file_handle.write(chunk)
                        speed_queue.put((len(chunk), 0))
                        left -= len(chunk)
                        
                if task.flags & TaskFlag.OFFLOAD_TO_CACHE and task.hash:
                    cache_file_path = os.path.join(cache, task.hash)
                    dl_utils.prepare_location(cache)
                    shutil.copy(task.temp_file, cache_file_path)
                    speed_queue.put((task.size, 0))
                    
            elif task.old_file:
                if not task.size:
                    print("No size")
                    results_queue.put(WriterTaskResult(False, task))
                    continue
                dest = task.old_destination or task.destination
                old_file_path = dl_utils.get_case_insensitive_name(os.path.join(dest, task.old_file))
                old_file_handle = open(old_file_path, "rb")
                if task.old_offset:
                    old_file_handle.seek(task.old_offset)
                left = task.size
                while left > 0:
                    chunk = old_file_handle.read(min(1024*1024, left))
                    data = chunk
                    written += file_handle.write(data)
                    speed_queue.put((len(data), len(chunk)))
                    left -= len(chunk)
                old_file_handle.close()

        except Exception as e:
            print("Writer exception", e)
            results_queue.put(WriterTaskResult(False, task))
        else:
            results_queue.put(WriterTaskResult(True, task, written=written))