"""
Android-compatible download utilities
"""

import json
import logging
import os
import requests
import shutil
import zlib
from typing import Dict, Any, Tuple
from gogdl import constants

logger = logging.getLogger("DLUtils")

def get_json(api_handler, url: str) -> Dict[str, Any]:
    """Get JSON data from URL using authenticated request"""
    try:
        response = api_handler.get_authenticated_request(url)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        logger.error(f"Failed to get JSON from {url}: {e}")
        raise

def get_zlib_encoded(api_handler, url: str) -> Tuple[Dict[str, Any], Dict[str, str]]:
    """Get and decompress zlib-encoded data from URL - Android compatible version of heroic-gogdl"""
    retries = 5
    while retries > 0:
        try:
            response = api_handler.get_authenticated_request(url)
            if not response.ok:
                return None, None
            
            try:
                # Try zlib decompression first (with window size 15 like heroic-gogdl)
                decompressed_data = zlib.decompress(response.content, 15)
                json_data = json.loads(decompressed_data.decode('utf-8'))
            except zlib.error:
                # If zlib decompression fails, try parsing as regular JSON (like heroic-gogdl)
                json_data = response.json()
            
            return json_data, dict(response.headers)
        except Exception as e:
            logger.warning(f"Failed to get zlib data from {url} (retries left: {retries-1}): {e}")
            if retries > 1:
                import time
                time.sleep(2)
            retries -= 1
    
    logger.error(f"Failed to get zlib data from {url} after 5 retries")
    return None, None

def download_file_chunk(url: str, start: int, end: int, headers: Dict[str, str] = None) -> bytes:
    """Download a specific chunk of a file using Range headers"""
    try:
        chunk_headers = headers.copy() if headers else {}
        chunk_headers['Range'] = f'bytes={start}-{end}'
        
        response = requests.get(
            url, 
            headers=chunk_headers,
            timeout=(constants.CONNECTION_TIMEOUT, constants.READ_TIMEOUT),
            stream=True
        )
        response.raise_for_status()
        
        return response.content
    except Exception as e:
        logger.error(f"Failed to download chunk {start}-{end} from {url}: {e}")
        raise


def galaxy_path(manifest_hash: str):
    """Format chunk hash for GOG Galaxy path structure"""
    if manifest_hash.find("/") == -1:
        return f"{manifest_hash[0:2]}/{manifest_hash[2:4]}/{manifest_hash}"
    return manifest_hash


def merge_url_with_params(url_template: str, parameters: dict):
    """Replace parameters in URL template"""
    result_url = url_template
    for key, value in parameters.items():
        result_url = result_url.replace("{" + key + "}", str(value))
    return result_url


def get_secure_link(api_handler, path, gameId, generation=2, logger=None, root=None):
    url = ""
    if generation == 2:
        url = f"{constants.GOG_CONTENT_SYSTEM}/products/{gameId}/secure_link?_version=2&generation=2&path={path}"
    elif generation == 1:
        url = f"{constants.GOG_CONTENT_SYSTEM}/products/{gameId}/secure_link?_version=2&type=depot&path={path}"
    if root:
        url += f"&root={root}"

    try:
        r = requests.get(url, headers=api_handler.session.headers, timeout=10)
    except BaseException as exception:
        if logger:
            logger.info(exception)
        time.sleep(0.2)
        return get_secure_link(api_handler, path, gameId, generation, logger)

    if r.status_code != 200:
        if logger:
            logger.info("invalid secure link response")
        time.sleep(0.2)
        return get_secure_link(api_handler, path, gameId, generation, logger)

    js = r.json()

    return js['urls']


def get_readable_size(size):
    power = 2 ** 10
    n = 0
    power_labels = {0: "", 1: "K", 2: "M", 3: "G"}
    while size > power:
        size /= power
        n += 1
    return size, power_labels[n] + "B"


def check_free_space(size: int, path: str):
    if not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    _, _, available_space = shutil.disk_usage(path)
    
    if available_space < size:
        return False
    return True


def get_range_header(offset, size):
    from_value = offset
    to_value = (int(offset) + int(size)) - 1
    return f"bytes={from_value}-{to_value}"


def create_manifest_class(meta: dict, api_handler):
    """Creates appropriate Manifest class based on provided meta from json"""
    version = meta.get("version") 
    if version == 1:
        from gogdl.dl.objects import v1
        return v1.Manifest.from_json(meta, api_handler)
    else:
        from gogdl.dl.objects import v2
        return v2.Manifest.from_json(meta, api_handler)


def get_case_insensitive_name(path):
    """Get case-insensitive path name for cross-platform compatibility"""
    from sys import platform
    if platform == "win32" or os.path.exists(path):
        return path
    root = path
    # Find existing directory
    while not os.path.exists(root):
        root = os.path.split(root)[0]
    
    if not root[len(root) - 1] in ["/", "\\"]:
        root = root + os.sep
    # Separate unknown path from existing one
    s_working_dir = path.replace(root, "").split(os.sep)
    paths_to_find = len(s_working_dir)
    paths_found = 0
    for directory in s_working_dir:
        if not os.path.exists(root):
            break
        dir_list = os.listdir(root)
        found = False
        for existing_dir in dir_list:
            if existing_dir.lower() == directory.lower():
                root = os.path.join(root, existing_dir)
                paths_found += 1
                found = True
        if not found:
            root = os.path.join(root, directory)
            paths_found += 1

    if paths_to_find != paths_found:
        root = os.path.join(root, os.sep.join(s_working_dir[paths_found:]))
    return root


def prepare_location(path):
    """Create directory structure if it doesn't exist"""
    import os
    if not os.path.exists(path):
        os.makedirs(path, exist_ok=True)


def get_dependency_link(api_handler):
    """Get dependency download link"""
    url = f"{constants.GOG_CDN}/content-system/v2/dependencies"
    r = api_handler.session.get(url)
    if not r.ok:
        return None
    js = r.json()
    return js['url']