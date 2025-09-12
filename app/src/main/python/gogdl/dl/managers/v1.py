"""
Android-compatible V1 manager for generation 1 games
Based on heroic-gogdl v1.py but with Android compatibility
"""

import json
import logging
import os
import hashlib
from concurrent.futures import ThreadPoolExecutor, as_completed
from gogdl.dl import dl_utils
from gogdl import constants
from gogdl.dl.objects import v1

class V1Manager:
    """Android-compatible V1 download manager for generation 1 games"""
    
    def __init__(self, arguments, unknown_arguments, api_handler, max_workers=2):
        self.arguments = arguments
        self.unknown_arguments = unknown_arguments
        self.api_handler = api_handler
        self.max_workers = max_workers
        self.logger = logging.getLogger("V1Manager")
        
        self.game_id = arguments.id
        self.platform = getattr(arguments, 'platform', 'windows')
        self.install_path = getattr(arguments, 'path', constants.ANDROID_GAMES_DIR)
        self.dlcs_should_be_downloaded = self.arguments.dlcs
        if self.arguments.dlcs_list:
            self.dlcs_list = self.arguments.dlcs_list.split(",")
        else:
            self.dlcs_list = list()
        
        # Add dlc_only attribute to match heroic-gogdl interface
        self.dlc_only = getattr(arguments, 'dlc_only', False)
        
        # Language handling - default to English like heroic-gogdl
        self.lang = getattr(arguments, 'lang', 'English')
        
        self.manifest = None
        self.meta = None
        self.build = None
        
    def download(self):
        """Download game using V1 method - Android compatible version of heroic-gogdl"""
        try:
            self.logger.info(f"Starting V1 download for game {self.game_id}")
            
            # Get builds and select target build
            self.build = self._get_target_build()
            if not self.build:
                raise Exception("No suitable build found")
                
            self.logger.info(f"Using build {self.build.get('build_id', 'unknown')} for download (generation: 1)")
            
            # Get meta data
            self.get_meta()
            
            # Get DLCs user owns
            dlcs_user_owns = self.get_dlcs_user_owns()
            
            # Create manifest
            self.logger.info("Creating V1 manifest")
            self.manifest = v1.Manifest(
                self.platform, 
                self.meta, 
                self.lang, 
                dlcs_user_owns, 
                self.api_handler, 
                False  # dlc_only
            )
            
            if self.manifest:
                self.manifest.get_files()
            
            # Get secure links
            self.logger.info("Getting secure download links...")
            secure_link_endpoints_ids = [product["id"] for product in dlcs_user_owns]
            # Add main game ID if not dlc_only (same as heroic-gogdl)
            if not self.dlc_only:
                secure_link_endpoints_ids.append(self.game_id)
                
            self.logger.info(f"Secure link endpoints: {secure_link_endpoints_ids}")
            secure_links = {}
            for product_id in secure_link_endpoints_ids:
                self.logger.info(f"Getting secure link for product {product_id}")
                path = f"/{self.platform}/{self.manifest.data['product']['timestamp']}/"
                self.logger.info(f"Using path: {path}")
                
                try:
                    secure_link = dl_utils.get_secure_link(
                        self.api_handler, 
                        path, 
                        product_id, 
                        generation=1,
                        logger=self.logger
                    )
                    self.logger.info(f"Got secure link for {product_id}: {secure_link}")
                    secure_links.update({
                        product_id: secure_link
                    })
                except Exception as e:
                    self.logger.error(f"Exception getting secure link for {product_id}: {e}")
                    secure_links.update({
                        product_id: []
                    })
            
            self.logger.info(f"Got {len(secure_links)} secure links")
            
            # Download files using Android-compatible threading
            self._download_files(secure_links)
            
            self.logger.info("V1 download completed successfully")
            
        except Exception as e:
            self.logger.error(f"V1 download failed: {e}")
            raise
    
    def get_meta(self):
        """Get meta data from build - same as heroic-gogdl"""
        meta_url = self.build["link"]
        self.meta, headers = dl_utils.get_zlib_encoded(self.api_handler, meta_url)
        if not self.meta:
            raise Exception("There was an error obtaining meta")
        if headers:
            self.version_etag = headers.get("Etag")
        
        # Append folder name when downloading - same as heroic-gogdl
        if hasattr(self.arguments, 'command') and self.arguments.command == "download":
            self.install_path = os.path.join(self.install_path, self.meta["product"]["installDirectory"])
    
    def get_dlcs_user_owns(self, info_command=False, requested_dlcs=None):
        """Get DLCs user owns - same as heroic-gogdl"""
        if requested_dlcs is None:
            requested_dlcs = list()
        if not self.dlcs_should_be_downloaded and not info_command:
            return []
            
        self.logger.debug("Getting dlcs user owns")
        dlcs = []
        
        if len(requested_dlcs) > 0:
            for product in self.meta["product"]["gameIDs"]:
                if (
                    product["gameID"] != self.game_id and  # Check if not base game
                    product["gameID"] in requested_dlcs and  # Check if requested by user
                    self.api_handler.does_user_own(product["gameID"])  # Check if owned
                ):
                    dlcs.append({"title": product["name"]["en"], "id": product["gameID"]})
            return dlcs
            
        for product in self.meta["product"]["gameIDs"]:
            # Check if not base game and if owned
            if product["gameID"] != self.game_id and self.api_handler.does_user_own(product["gameID"]):
                dlcs.append({"title": product["name"]["en"], "id": product["gameID"]})
        return dlcs
    
    def _get_target_build(self):
        """Get target build - simplified for Android"""
        # For now, just get the first build
        # In a full implementation, this would match heroic-gogdl's build selection logic
        builds_url = f"{constants.GOG_CONTENT_SYSTEM}/products/{self.game_id}/os/{self.platform}/builds?generation=1"
        response = self.api_handler.session.get(builds_url)
        
        if not response.ok:
            raise Exception(f"Failed to get builds: {response.status_code}")
            
        data = response.json()
        if data['total_count'] == 0 or len(data['items']) == 0:
            raise Exception("No builds found")
            
        return data['items'][0]  # Use first build
    
    def _download_files(self, secure_links):
        """Download files using Android-compatible threading - matches heroic-gogdl V1 approach"""
        if not self.manifest or not self.manifest.files:
            self.logger.warning("No files to download")
            return
            
        self.logger.info(f"Downloading {len(self.manifest.files)} files")
        
        # V1 downloads work differently - they download from main.bin file
        # Get the secure link for the main game
        game_secure_link = secure_links.get(self.game_id)
        if not game_secure_link:
            self.logger.error("No secure link found for main game")
            return
            
        # Construct main.bin URL - matches heroic-gogdl v1 method
        if isinstance(game_secure_link, list) and len(game_secure_link) > 0:
            endpoint = game_secure_link[0].copy()
            endpoint["parameters"]["path"] += "/main.bin"
            main_bin_url = dl_utils.merge_url_with_params(
                endpoint["url_format"], endpoint["parameters"]
            )
        elif isinstance(game_secure_link, str):
            main_bin_url = game_secure_link + "/main.bin"
        else:
            self.logger.error(f"Invalid secure link format: {game_secure_link}")
            return
            
        self.logger.debug(f"Main.bin URL: {main_bin_url}")
        
        # Use ThreadPoolExecutor for Android compatibility
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            # Submit download tasks
            future_to_file = {}
            for i, file_obj in enumerate(self.manifest.files):
                self.logger.info(f"Submitting download task {i+1}/{len(self.manifest.files)}: {file_obj.path}")
                future = executor.submit(self._download_file_from_main_bin, file_obj, main_bin_url)
                future_to_file[future] = file_obj.path
            
            # Process completed downloads
            completed = 0
            for future in as_completed(future_to_file):
                file_path = future_to_file[future]
                completed += 1
                try:
                    future.result()
                    self.logger.info(f"Completed {completed}/{len(self.manifest.files)}: {file_path}")
                except Exception as e:
                    self.logger.error(f"Failed to download file {file_path}: {e}")
                    raise
            
            self.logger.info(f"All {len(self.manifest.files)} files downloaded successfully")
    
    def _download_file_from_main_bin(self, file_obj, main_bin_url):
        """Download a single file from main.bin - matches heroic-gogdl V1 approach"""
        try:
            self.logger.debug(f"[V1Manager] Starting download: {file_obj.path}")
            
            # Create the full file path
            full_path = os.path.join(self.install_path, file_obj.path)
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            
            # V1 files have offset and size - download from main.bin using range request
            if not hasattr(file_obj, 'offset') or not hasattr(file_obj, 'size'):
                self.logger.error(f"[V1Manager] File {file_obj.path} missing offset/size for V1 download")
                return
                
            offset = file_obj.offset
            size = file_obj.size
            
            self.logger.debug(f"[V1Manager] File {file_obj.path}: offset={offset}, size={size}")
            
            # Create range header for the specific chunk
            range_header = f"bytes={offset}-{offset + size - 1}"
            self.logger.debug(f"[V1Manager] Range header: {range_header}")
            
            # Download the chunk using streaming to avoid memory issues
            import requests
            session = requests.Session()
            session.headers.update({
                'User-Agent': 'GOGGalaxyClient/2.0.45.61 (Windows_x86_64)',
                'Range': range_header
            })
            
            self.logger.debug(f"[V1Manager] Making request to: {main_bin_url}")
            response = session.get(main_bin_url, stream=True, timeout=60)
            response.raise_for_status()
            
            self.logger.debug(f"[V1Manager] Response status: {response.status_code}")
            
            # Stream the content directly to file to avoid memory issues
            downloaded_bytes = 0
            with open(full_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):  # 8KB chunks
                    if chunk:  # filter out keep-alive chunks
                        f.write(chunk)
                        downloaded_bytes += len(chunk)
                        
            self.logger.info(f"[V1Manager] Successfully downloaded file {file_obj.path} ({downloaded_bytes} bytes)")
            
            # Set file permissions if executable
            if 'executable' in file_obj.flags:
                os.chmod(full_path, 0o755)
                
        except Exception as e:
            self.logger.error(f"[V1Manager] Failed to download file {file_obj.path}: {type(e).__name__}: {str(e)}")
            import traceback
            self.logger.error(f"[V1Manager] Traceback: {traceback.format_exc()}")
            raise
