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
        self.branch = getattr(arguments, 'branch', None)

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
                # Use Linux manager - pass self as generic_manager like v2.Manager
                manager = linux.LinuxManager(self)
                manager.download()
                return

            # Get builds to determine generation
            builds = self.get_builds(self.platform)
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

    def setup_download_manager(self):
        # TODO: If content system for linux ever appears remove this if statement
        # But keep the one below so we have some sort of fallback
        # in case not all games were available in content system
        if self.platform == "linux":
            self.logger.info(
                "Platform is Linux, redirecting download to Linux Native installer manager"
            )

            self.download_manager = linux.Manager(self)

            return

        try:
            self.builds = self.get_builds(self.platform)
        except UnsupportedPlatform:
            if self.platform == "linux":
                self.logger.info(
                    "Platform is Linux, redirecting download to Linux Native installer manager"
                )

                self.download_manager = linux.Manager(self)

                return

            self.logger.error(f"Game doesn't support content system api, unable to proceed using platform {self.platform}")
            exit(1)

        # If Linux download ever progresses to this point, then it's time for some good party

        if len(self.builds["items"]) == 0:
            self.logger.error("No builds found")
            exit(1)
        self.target_build = self.builds["items"][0]

        for build in self.builds["items"]:
            if build["branch"] == None:
                self.target_build = build
                break

        for build in self.builds["items"]:
            if build["branch"] == self.branch:
                self.target_build = build
                break

        if self.arguments.build:
            # Find build
            for build in self.builds["items"]:
                if build["build_id"] == self.arguments.build:
                    self.target_build = build
                    break
        self.logger.debug(f'Found build {self.target_build}')

        generation = self.target_build["generation"]

        if self.is_verifying:
            manifest_path = os.path.join(constants.MANIFESTS_DIR, self.game_id)
            if os.path.exists(manifest_path):
                with open(manifest_path, 'r') as f:
                    manifest_data = json.load(f)
                    generation = int(manifest_data['version'])

        # This code shouldn't run at all but it's here just in case GOG decides they will return different generation than requested one
        # Of course assuming they will ever change their content system generation (I highly doubt they will)
        if generation not in [1, 2]:
            raise Exception("Unsupported depot version please report this")

        self.logger.info(f"Depot version: {generation}")

        if generation == 1:
            self.download_manager = v1.Manager(self)
        elif generation == 2:
            self.download_manager = v2.Manager(self)

    def calculate_download_size(self, arguments, unknown_arguments):
        """Calculate download size - same as heroic-gogdl"""
        try:
            self.setup_download_manager()

            download_size_response = self.download_manager.get_download_size()
            download_size_response['builds'] = self.builds

            # Print JSON output like heroic-gogdl does
            import json
            print(json.dumps(download_size_response))

        except Exception as e:
            self.logger.error(f"Calculate download size failed: {e}")
            raise

    def get_builds(self, build_platform):
        password_arg = getattr(self.arguments, 'password', None)
        password = '' if not password_arg else '&password=' + password_arg
        generation = getattr(self.arguments, 'force_generation', None) or "2"
        response = self.api_handler.session.get(
            f"{constants.GOG_CONTENT_SYSTEM}/products/{self.game_id}/os/{build_platform}/builds?&generation={generation}{password}"
        )

        if not response.ok:
            raise UnsupportedPlatform()
        data = response.json()

        if data['total_count'] == 0:
            raise UnsupportedPlatform()

        return data
