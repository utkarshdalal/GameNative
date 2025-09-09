"""
Android-compatible V2 manager for Windows game downloads
"""

import json
import logging
import os
import hashlib
import zlib
from concurrent.futures import ThreadPoolExecutor, as_completed
from gogdl.dl import dl_utils
from gogdl import constants

class V2Manager:
    """Android-compatible V2 download manager for Windows games"""
    
    def __init__(self, arguments, unknown_arguments, api_handler, max_workers=2):
        self.arguments = arguments
        self.unknown_arguments = unknown_arguments
        self.api_handler = api_handler
        self.max_workers = max_workers
        self.logger = logging.getLogger("V2Manager")
        
        self.game_id = arguments.id
        self.platform = getattr(arguments, 'platform', 'windows')
        self.install_path = getattr(arguments, 'path', constants.ANDROID_GAMES_DIR)
        self.skip_dlcs = getattr(arguments, 'skip_dlcs', False)
        
    def download(self):
        """Download game using V2 method with proper secure links"""
        try:
            self.logger.info(f"Starting V2 download for game {self.game_id}")
            
            # Get game builds
            builds_data = self.api_handler.get_builds(self.game_id, self.platform)
            
            if not builds_data.get('items'):
                raise Exception(f"No builds found for game {self.game_id}")
                
            # Get the main branch build (no branch specified) like heroic-gogdl does
            build = next((b for b in builds_data['items'] if not b.get('branch')), builds_data['items'][0])
            build_id = build.get('build_id', build.get('id'))
            generation = build.get('generation', 'unknown')
            
            self.logger.info(f"Using build {build_id} for download (generation: {generation})")
            
            # Get build manifest
            manifest_url = build['link']
            manifest_data, headers = dl_utils.get_zlib_encoded(self.api_handler, manifest_url)
            
            # Create install directory
            game_title = manifest_data.get('name', f"game_{self.game_id}")
            full_install_path = os.path.join(self.install_path, game_title)
            os.makedirs(full_install_path, exist_ok=True)
            
            self.logger.info(f"Installing to: {full_install_path}")
            
            # Download depot files
            depot_files = manifest_data.get('depots', [])
            if not depot_files:
                raise Exception("No depot files found in manifest")
                
            self.logger.info(f"Found {len(depot_files)} depot files to download")
            
            # Get secure links for chunk downloads - this is the key fix!
            self.logger.info("Getting secure download links...")
            # Get secure download links for each unique product ID
            product_ids = set([self.game_id])  # Start with main game ID
            
            # Extract product IDs from depot files
            for depot in depot_files:
                if 'productId' in depot:
                    product_ids.add(depot['productId'])
            
            self.logger.info(f"Getting secure links for product IDs: {list(product_ids)}")
            
            # Get secure links for each product ID (V2 first, V1 fallback)
            self.secure_links_by_product = {}
            self.v1_secure_links_by_product = {}
            
            for product_id in product_ids:
                # Try V2 secure links first
                secure_links = dl_utils.get_secure_link(self.api_handler, "/", product_id, generation=2, logger=self.logger)
                if secure_links:
                    self.secure_links_by_product[product_id] = secure_links
                    self.logger.info(f"Got {len(secure_links)} V2 secure links for product {product_id}")
                
                # Also get V1 secure links as fallback
                v1_secure_links = dl_utils.get_secure_link(self.api_handler, "/", product_id, generation=1, logger=self.logger)
                if v1_secure_links:
                    self.v1_secure_links_by_product[product_id] = v1_secure_links
                    self.logger.info(f"Got {len(v1_secure_links)} V1 secure links for product {product_id}")
            
            # Use main game secure links as fallback
            self.secure_links = self.secure_links_by_product.get(self.game_id, [])
            
            if self.secure_links:
                self.logger.info(f"Using {len(self.secure_links)} secure links from main game")
                self.logger.info(f"First secure link structure: {self.secure_links[0]}")
                if len(self.secure_links) > 1:
                    self.logger.info(f"Second secure link structure: {self.secure_links[1]}")
            else:
                self.logger.error("No secure links received!")
            
            # Use the same depot URL pattern as original heroic-gogdl
            for depot in depot_files:
                if 'manifest' in depot:
                    manifest_hash = depot['manifest']
                    # Use the exact same URL pattern as the original heroic-gogdl
                    depot['link'] = f"https://gog-cdn-fastly.gog.com/content-system/v2/meta/{dl_utils.galaxy_path(manifest_hash)}"
            
            # Download depots using threading
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                futures = []
                for depot in depot_files:
                    future = executor.submit(self._download_depot, depot, full_install_path)
                    futures.append(future)
                    
                # Wait for all downloads to complete
                for future in as_completed(futures):
                    try:
                        future.result()
                    except Exception as e:
                        self.logger.error(f"Depot download failed: {e}")
                        raise
                        
            self.logger.info("Download completed successfully")
            
        except Exception as e:
            self.logger.error(f"V2 download failed: {e}")
            raise
            
    def _download_depot(self, depot_info: dict, install_path: str):
        """Download a single depot"""
        try:
            depot_url = depot_info.get('link', depot_info.get('url'))
            if not depot_url:
                self.logger.warning(f"No URL found for depot: {depot_info}")
                return
                
            self.logger.info(f"Downloading depot: {depot_url}")
            
            # Get depot manifest
            depot_data, headers = dl_utils.get_zlib_encoded(self.api_handler, depot_url)
            
            # Process depot files
            if 'depot' in depot_data and 'items' in depot_data['depot']:
                items = depot_data['depot']['items']
                self.logger.info(f"Depot contains {len(items)} files")
                
                for item in items:
                    # Pass the depot's product ID for correct secure link selection
                    depot_product_id = depot_info.get('productId', self.game_id)
                    self._download_file(item, install_path, depot_product_id)
            else:
                self.logger.warning(f"Unexpected depot structure: {depot_data.keys()}")
                
        except Exception as e:
            self.logger.error(f"Failed to download depot: {e}")
            raise
            
    def _download_file(self, file_info: dict, install_path: str, product_id: str = None):
        """Download a single file from depot by assembling all chunks"""
        try:
            file_path = file_info.get('path', '')
            if not file_path:
                return
                
            # Skip files that don't match pattern if specified
            if hasattr(self.arguments, 'file_pattern') and self.arguments.file_pattern:
                if self.arguments.file_pattern not in file_path:
                    return
                    
            full_path = os.path.join(install_path, file_path.replace('\\', os.sep))
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            
            self.logger.info(f"Downloading file: {file_path}")
            
            # Download file chunks
            chunks = file_info.get('chunks', [])
            if not chunks:
                self.logger.warning(f"No chunks found for file: {file_path}")
                return
            
            self.logger.info(f"File {file_path} has {len(chunks)} chunks to download")
            
            # Download and assemble all chunks for this file
            file_data = b''
            total_size = 0
            
            for i, chunk in enumerate(chunks):
                self.logger.debug(f"Downloading chunk {i+1}/{len(chunks)} for {file_path}")
                chunk_data = self._download_chunk(chunk, product_id)
                if chunk_data:
                    file_data += chunk_data
                    total_size += len(chunk_data)
                else:
                    self.logger.error(f"Failed to download chunk {i+1} for {file_path}")
                    return
            
            # Write the complete assembled file
            with open(full_path, 'wb') as f:
                f.write(file_data)
            
            self.logger.info(f"Successfully assembled file {file_path} ({total_size} bytes from {len(chunks)} chunks)")
                        
            # Set file permissions if specified
            if 'flags' in file_info and 'executable' in file_info['flags']:
                os.chmod(full_path, 0o755)
                
        except Exception as e:
            self.logger.error(f"Failed to download file {file_path}: {e}")
            # Don't raise here to continue with other files
            
    def _try_download_chunk_with_links(self, chunk_md5: str, chunk_info: dict, secure_links: list, link_type: str) -> bytes:
        """Try to download a chunk using the provided secure links"""
        chunk_path = f"/store/{chunk_md5[:2]}/{chunk_md5[2:4]}/{chunk_md5}"
        
        for secure_link in secure_links:
            try:
                # Build URL like original heroic-gogdl
                if isinstance(secure_link, dict):
                    # Secure link has url_format and parameters structure
                    if "url_format" in secure_link and "parameters" in secure_link:
                        # Copy the secure link to avoid modifying the original
                        endpoint = secure_link.copy()
                        endpoint["parameters"] = secure_link["parameters"].copy()
                        galaxy_chunk_path = dl_utils.galaxy_path(chunk_md5)
                        
                        # Handle different CDN URL formats
                        if secure_link.get("endpoint_name") == "akamai_edgecast_proxy":
                            # For Akamai: path should not have leading slash, and chunk path is appended directly
                            endpoint["parameters"]["path"] = f"{endpoint['parameters']['path']}/{galaxy_chunk_path}"
                        else:
                            # For Fastly and others: append to existing path
                            endpoint["parameters"]["path"] += f"/{galaxy_chunk_path}"
                            
                        chunk_url = dl_utils.merge_url_with_params(
                            endpoint["url_format"], endpoint["parameters"]
                        )
                    elif "url" in secure_link:
                        # Fallback to simple URL + path
                        galaxy_chunk_path = dl_utils.galaxy_path(chunk_md5)
                        chunk_url = secure_link["url"] + "/" + galaxy_chunk_path
                    else:
                        self.logger.debug(f"Unknown {link_type} secure link structure: {secure_link}")
                        continue
                else:
                    # Fallback: treat as simple string URL
                    chunk_url = str(secure_link) + chunk_path
                
                self.logger.debug(f"Trying {link_type} chunk URL: {chunk_url}")
                
                headers = {
                    'User-Agent': 'GOGGalaxyClient/2.0.45.61 (Windows_x86_64)',
                }
                
                # Download the chunk using a clean session without Authorization header
                # CDN requests with secure links should not include API authentication
                import requests
                cdn_session = requests.Session()
                cdn_session.headers.update(headers)
                response = cdn_session.get(chunk_url)
                
                if response.status_code == 200:
                    # Always decompress chunks as they are zlib compressed by GOG
                    chunk_data = response.content
                    try:
                        # GOG chunks are always zlib compressed
                        chunk_data = zlib.decompress(chunk_data)
                        self.logger.debug(f"Successfully downloaded and decompressed chunk {chunk_md5} using {link_type} ({len(response.content)} -> {len(chunk_data)} bytes)")
                    except zlib.error as e:
                        self.logger.warning(f"Failed to decompress chunk {chunk_md5}, trying as uncompressed: {e}")
                        # If decompression fails, use raw data
                        chunk_data = response.content
                    return chunk_data
                else:
                    self.logger.warning(f"Chunk {chunk_md5} failed on {link_type} {chunk_url}: HTTP {response.status_code} - {response.text[:200]}")
                    continue  # Try next secure link
                    
            except Exception as e:
                self.logger.debug(f"Error with {link_type} secure link {secure_link}: {e}")
                continue  # Try next secure link
        
        # All links failed for this type
        return b''

    def _download_chunk(self, chunk_info: dict, product_id: str = None) -> bytes:
        """Download and decompress a file chunk using secure links with V1 fallback"""
        try:
            # Use compressed MD5 for URL path like original heroic-gogdl
            chunk_md5 = chunk_info.get('compressedMd5', chunk_info.get('compressed_md5', chunk_info.get('md5', '')))
            if not chunk_md5:
                return b''
            
            # Debug: log chunk info structure for the first few chunks
            if not hasattr(self, '_logged_chunk_structure'):
                self.logger.info(f"Chunk structure: {list(chunk_info.keys())}")
                self.logger.info(f"Using chunk_md5: {chunk_md5}")
                self._logged_chunk_structure = True
            
            # Use secure links for chunk downloads - select based on product_id
            secure_links_to_use = self.secure_links  # Default fallback
            
            if product_id and hasattr(self, 'secure_links_by_product'):
                secure_links_to_use = self.secure_links_by_product.get(product_id, self.secure_links)
                self.logger.debug(f"Using V2 secure links for product {product_id}")
            
            # Try V2 secure links first
            if secure_links_to_use:
                chunk_data = self._try_download_chunk_with_links(chunk_md5, chunk_info, secure_links_to_use, "V2")
                if chunk_data:
                    return chunk_data
            
            # If V2 failed, try V1 secure links as fallback
            if product_id and hasattr(self, 'v1_secure_links_by_product'):
                v1_secure_links = self.v1_secure_links_by_product.get(product_id, [])
                if v1_secure_links:
                    self.logger.info(f"Trying V1 fallback for chunk {chunk_md5}")
                    chunk_data = self._try_download_chunk_with_links(chunk_md5, chunk_info, v1_secure_links, "V1")
                    if chunk_data:
                        return chunk_data
            
            # If all failed, log error
            self.logger.warning(f"Failed to download chunk {chunk_md5} from all V2 and V1 secure links")
            return b''
                
        except Exception as e:
            self.logger.error(f"Error downloading chunk: {e}")
            return b''
            
    def info(self):
        """Get game information"""
        try:
            game_info = self.api_handler.get_game_info(self.game_id)
            builds_data = self.api_handler.get_builds(self.game_id, self.platform)
            
            print(f"Game ID: {self.game_id}")
            print(f"Title: {game_info.get('title', 'Unknown')}")
            print(f"Available builds: {len(builds_data.get('items', []))}")
            
            if builds_data.get('items'):
                build = builds_data['items'][0]
                print(f"Latest build ID: {build.get('build_id', build.get('id'))}")
                print(f"Build date: {build.get('date_published', 'Unknown')}")
                
        except Exception as e:
            self.logger.error(f"Failed to get game info: {e}")
            raise
