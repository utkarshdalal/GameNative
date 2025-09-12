"""
Android-compatible download manager
Replaces multiprocessing with threading for Android compatibility
"""

from dataclasses import dataclass
import os
import logging
import json
import threading
from concurrent.futures import ThreadPoolExecutor

from gogdl import constants
from gogdl.dl.managers import linux, v1, v2

@dataclass
class UnsupportedPlatform(Exception):
    pass

class AndroidManager:
    """Android-compatible version of GOGDL Manager that uses threading instead of multiprocessing"""
    
    def __init__(self, arguments, unknown_arguments, api_handler):
        self.arguments = arguments
        self.unknown_arguments = unknown_arguments
        self.api_handler = api_handler

        self.platform = arguments.platform
        self.should_append_folder_name = self.arguments.command == "download"
        self.is_verifying = self.arguments.command == "repair"
        self.game_id = arguments.id
        self.branch = arguments.branch or None
        
        # Use a reasonable number of threads for Android
        if hasattr(arguments, "workers_count"):
            self.allowed_threads = min(int(arguments.workers_count), 4)  # Limit threads on mobile
        else:
            self.allowed_threads = 2  # Conservative default for Android

        self.logger = logging.getLogger("AndroidManager")

    def download(self):
        """Download game using Android-compatible threading"""
        try:
            self.logger.info(f"Starting Android download for game {self.game_id}")
            
            if self.platform == "linux":
                # Use Linux manager with threading
                manager = linux.LinuxManager(
                    self.arguments,
                    self.unknown_arguments, 
                    self.api_handler,
                    max_workers=self.allowed_threads
                )
                manager.download()
                return
            
            # Get builds to determine generation
            builds = self._get_builds()
            if not builds or len(builds['items']) == 0:
                raise Exception("No builds found")
            
            # Select target build (same logic as heroic-gogdl)
            target_build = builds['items'][0]  # Default to first build
            
            # Check for specific branch
            for build in builds['items']:
                if build.get("branch") == self.branch:
                    target_build = build
                    break
            
            # Check for specific build ID
            if hasattr(self.arguments, 'build') and self.arguments.build:
                for build in builds['items']:
                    if build.get("build_id") == self.arguments.build:
                        target_build = build
                        break
            
            # Store builds and target_build as instance attributes for V2 Manager
            self.builds = builds
            self.target_build = target_build
            
            generation = target_build.get("generation", 2)
            self.logger.info(f"Using build {target_build.get('build_id', 'unknown')} for download (generation: {generation})")
            
            # Use the correct manager based on generation - same as heroic-gogdl
            if generation == 1:
                self.logger.info("Using V1Manager for generation 1 game")
                manager = v1.Manager(self)  # Pass self like V2 does
            elif generation == 2:
                self.logger.info("Using V2Manager for generation 2 game")
                manager = v2.Manager(self)
            else:
                raise Exception(f"Unsupported generation: {generation}")
            
            manager.download()
                
        except Exception as e:
            self.logger.error(f"Download failed: {e}")
            raise

    def info(self):
        """Get game info"""
        try:
            # Use existing info logic but Android-compatible
            if self.platform == "windows":
                manager = v2.Manager(self)
                manager.info()
            else:
                raise UnsupportedPlatform(f"Info for platform {self.platform} not supported")
        except Exception as e:
            self.logger.error(f"Info failed: {e}")
            raise
    
    def _get_builds(self):
        """Get builds for the game - same as heroic-gogdl"""
        password = '' if not hasattr(self.arguments, 'password') or not self.arguments.password else '&password=' + self.arguments.password
        generation = getattr(self.arguments, 'force_generation', None) or "2"
        
        builds_url = f"{constants.GOG_CONTENT_SYSTEM}/products/{self.game_id}/os/{self.platform}/builds?&generation={generation}{password}"
        response = self.api_handler.session.get(builds_url)
        
        if not response.ok:
            raise UnsupportedPlatform(f"Failed to get builds: {response.status_code}")
            
        return response.json()
