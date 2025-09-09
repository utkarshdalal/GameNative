"""
Android-compatible download utilities
"""

import json
import logging
import requests
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


def get_secure_link(api_handler, path: str, game_id: str, generation: int = 2, root: str = None, logger=None):
    """Get secure download links from GOG API - this is the key to proper chunk authentication"""
    import time
    from typing import List
    
    url = ""
    if generation == 2:
        url = f"{constants.GOG_CONTENT_SYSTEM}/products/{game_id}/secure_link?_version=2&generation=2&path={path}"
    elif generation == 1:
        url = f"{constants.GOG_CONTENT_SYSTEM}/products/{game_id}/secure_link?_version=2&type=depot&path={path}"
    
    if root:
        url += f"&root={root}"
    
    # Add debugging
    if logger:
        logger.debug(f"Getting secure link from URL: {url}")
    
    try:
        response = api_handler.get_authenticated_request(url)
        
        if logger:
            logger.debug(f"Secure link response status: {response.status_code}")
            logger.debug(f"Secure link response content: {response.text[:500]}...")
        
        if response.status_code != 200:
            if logger:
                logger.warning(f"Invalid secure link response: {response.status_code}")
            time.sleep(0.2)
            return get_secure_link(api_handler, path, game_id, generation, root, logger)
        
        js = response.json()
        urls = js.get('urls', [])
        
        if logger:
            logger.debug(f"Extracted URLs: {urls}")
        
        return urls
        
    except Exception as e:
        if logger:
            logger.error(f"Failed to get secure link: {e}")
        else:
            print(f"Failed to get secure link: {e}")
        time.sleep(0.2)
        return get_secure_link(api_handler, path, game_id, generation, root, logger)

