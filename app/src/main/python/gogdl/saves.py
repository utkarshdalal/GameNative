"""
Android-compatible GOG cloud save synchronization
Adapted from heroic-gogdl saves.py
"""

import os
import sys
import logging
import requests
import hashlib
import datetime
import gzip
from enum import Enum

import gogdl.dl.dl_utils as dl_utils
import gogdl.constants as constants

LOCAL_TIMEZONE = datetime.datetime.utcnow().astimezone().tzinfo


class SyncAction(Enum):
    DOWNLOAD = 0
    UPLOAD = 1
    CONFLICT = 2
    NONE = 3


class SyncFile:
    def __init__(self, path, abs_path, md5=None, update_time=None):
        self.relative_path = path.replace('\\', '/')  # cloud file identifier
        self.absolute_path = abs_path
        self.md5 = md5
        self.update_time = update_time
        self.update_ts = (
            datetime.datetime.fromisoformat(update_time).astimezone().timestamp()
            if update_time
            else None
        )

    def get_file_metadata(self):
        ts = os.stat(self.absolute_path).st_mtime
        date_time_obj = datetime.datetime.fromtimestamp(
            ts, tz=LOCAL_TIMEZONE
        ).astimezone(datetime.timezone.utc)
        with open(self.absolute_path, "rb") as f:
            self.md5 = hashlib.md5(
                gzip.compress(f.read(), 6, mtime=0)
            ).hexdigest()
        self.update_time = date_time_obj.isoformat(timespec="seconds")
        self.update_ts = date_time_obj.timestamp()

    def __repr__(self):
        return f"{self.md5} {self.relative_path}"


class CloudStorageManager:
    def __init__(self, api_handler, authorization_manager):
        self.api = api_handler
        self.auth_manager = authorization_manager
        self.session = requests.Session()
        self.logger = logging.getLogger("SAVES")

        self.session.headers.update(
            {"User-Agent": "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) dont_sync_marker/true installation_source/gog",
             "X-Object-Meta-User-Agent": "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) dont_sync_marker/true installation_source/gog"}
        )

        self.credentials = dict()
        self.client_id = str()
        self.client_secret = str()

    def create_directory_map(self, path: str) -> list:
        """
        Creates list of every file in directory to be synced
        """
        files = list()
        try:
            directory_contents = os.listdir(path)
        except (OSError, FileNotFoundError):
            self.logger.warning(f"Cannot access directory: {path}")
            return files

        for content in directory_contents:
            abs_path = os.path.join(path, content)
            if os.path.isdir(abs_path):
                files.extend(self.create_directory_map(abs_path))
            else:
                files.append(abs_path)
        return files

    @staticmethod
    def get_relative_path(root: str, path: str) -> str:
        if not root.endswith("/") and not root.endswith("\\"):
            root = root + os.sep
        return path.replace(root, "")

    def sync(self, arguments, unknown_args):
        try:
            prefered_action = getattr(arguments, 'prefered_action', None)
            self.sync_path = os.path.normpath(arguments.path.strip('"'))
            self.sync_path = self.sync_path.replace("\\", os.sep)
            self.cloud_save_dir_name = getattr(arguments, 'dirname', 'saves')
            self.arguments = arguments
            self.unknown_args = unknown_args

            if not os.path.exists(self.sync_path):
                self.logger.warning("Provided path doesn't exist, creating")
                os.makedirs(self.sync_path, exist_ok=True)

            dir_list = self.create_directory_map(self.sync_path)
            if len(dir_list) == 0:
                self.logger.info("No files in directory")

            local_files = [
                SyncFile(self.get_relative_path(self.sync_path, f), f) for f in dir_list
            ]

            for f in local_files:
                try:
                    f.get_file_metadata()
                except Exception as e:
                    self.logger.warning(f"Failed to get metadata for {f.absolute_path}: {e}")

            self.logger.info(f"Local files: {len(dir_list)}")

            # Get authentication credentials
            try:
                self.client_id, self.client_secret = self.get_auth_ids()
                self.get_auth_token()
            except Exception as e:
                self.logger.error(f"Authentication failed: {e}")
                return

            # Get cloud files
            try:
                cloud_files = self.get_cloud_files_list()
                downloadable_cloud = [f for f in cloud_files if f.md5 != "aadd86936a80ee8a369579c3926f1b3c"]
            except Exception as e:
                self.logger.error(f"Failed to get cloud files: {e}")
                return

            # Handle sync logic
            if len(local_files) > 0 and len(cloud_files) == 0:
                self.logger.info("No files in cloud, uploading")
                for f in local_files:
                    try:
                        self.upload_file(f)
                    except Exception as e:
                        self.logger.error(f"Failed to upload {f.relative_path}: {e}")
                self.logger.info("Done")
                sys.stdout.write(str(datetime.datetime.now().timestamp()))
                sys.stdout.flush()
                return

            elif len(local_files) == 0 and len(cloud_files) > 0:
                self.logger.info("No files locally, downloading")
                for f in downloadable_cloud:
                    try:
                        self.download_file(f)
                    except Exception as e:
                        self.logger.error(f"Failed to download {f.relative_path}: {e}")
                self.logger.info("Done")
                sys.stdout.write(str(datetime.datetime.now().timestamp()))
                sys.stdout.flush()
                return

            # Handle more complex sync scenarios
            timestamp = float(getattr(arguments, 'timestamp', 0.0))
            classifier = SyncClassifier.classify(local_files, cloud_files, timestamp)

            action = classifier.get_action()
            if action == SyncAction.DOWNLOAD:
                self.logger.info("Downloading newer cloud files")
                for f in classifier.updated_cloud:
                    try:
                        self.download_file(f)
                    except Exception as e:
                        self.logger.error(f"Failed to download {f.relative_path}: {e}")

            elif action == SyncAction.UPLOAD:
                self.logger.info("Uploading newer local files")
                for f in classifier.updated_local:
                    try:
                        self.upload_file(f)
                    except Exception as e:
                        self.logger.error(f"Failed to upload {f.relative_path}: {e}")

            elif action == SyncAction.CONFLICT:
                self.logger.warning("Sync conflict detected - manual intervention required")

            self.logger.info("Sync completed")
            sys.stdout.write(str(datetime.datetime.now().timestamp()))
            sys.stdout.flush()

        except Exception as e:
            self.logger.error(f"Sync failed: {e}")
            raise

    def get_auth_ids(self):
        """Get client credentials from auth manager"""
        try:
            # Use the same client ID as the main app
            client_id = "46899977096215655"
            client_secret = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
            return client_id, client_secret
        except Exception as e:
            self.logger.error(f"Failed to get auth IDs: {e}")
            raise

    def get_auth_token(self):
        """Get authentication token"""
        try:
            # Load credentials from auth file
            import json
            with open(self.auth_manager.config_path, 'r') as f:
                auth_data = json.load(f)

            # Extract credentials for our client ID
            client_creds = auth_data.get(self.client_id, {})
            self.credentials = {
                'access_token': client_creds.get('access_token', ''),
                'user_id': client_creds.get('user_id', '')
            }

            if not self.credentials['access_token']:
                raise Exception("No valid access token found")

            # Update session headers
            self.session.headers.update({
                'Authorization': f"Bearer {self.credentials['access_token']}"
            })

        except Exception as e:
            self.logger.error(f"Failed to get auth token: {e}")
            raise

    def get_cloud_files_list(self):
        """Get list of files from GOG cloud storage"""
        try:
            url = f"{constants.GOG_CLOUDSTORAGE}/v1/{self.credentials['user_id']}/{self.client_id}"
            response = self.session.get(url)

            if not response.ok:
                self.logger.error(f"Failed to get cloud files: {response.status_code}")
                return []

            cloud_data = response.json()
            cloud_files = []

            for item in cloud_data.get('items', []):
                if self.is_save_file(item):
                    cloud_file = SyncFile(
                        self.get_relative_path(f"{self.cloud_save_dir_name}/", item['name']),
                        "",  # No local path for cloud files
                        item.get('hash'),
                        item.get('last_modified')
                    )
                    cloud_files.append(cloud_file)

            return cloud_files

        except Exception as e:
            self.logger.error(f"Failed to get cloud files list: {e}")
            return []

    def is_save_file(self, item):
        """Check if cloud item is a save file"""
        return item.get("name", "").startswith(self.cloud_save_dir_name)

    def upload_file(self, file: SyncFile):
        """Upload file to GOG cloud storage"""
        try:
            url = f"{constants.GOG_CLOUDSTORAGE}/v1/{self.credentials['user_id']}/{self.client_id}/{self.cloud_save_dir_name}/{file.relative_path}"

            with open(file.absolute_path, 'rb') as f:
                headers = {
                    'X-Object-Meta-LocalLastModified': file.update_time,
                    'Content-Type': 'application/octet-stream'
                }
                response = self.session.put(url, data=f, headers=headers)

            if not response.ok:
                self.logger.error(f"Upload failed for {file.relative_path}: {response.status_code}")

        except Exception as e:
            self.logger.error(f"Failed to upload {file.relative_path}: {e}")

    def download_file(self, file: SyncFile, retries=3):
        """Download file from GOG cloud storage"""
        try:
            url = f"{constants.GOG_CLOUDSTORAGE}/v1/{self.credentials['user_id']}/{self.client_id}/{self.cloud_save_dir_name}/{file.relative_path}"
            response = self.session.get(url, stream=True)

            if not response.ok:
                self.logger.error(f"Download failed for {file.relative_path}: {response.status_code}")
                return

            # Create local directory structure
            local_path = os.path.join(self.sync_path, file.relative_path)
            os.makedirs(os.path.dirname(local_path), exist_ok=True)

            # Download file
            with open(local_path, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)

            # Set file timestamp if available
            if 'X-Object-Meta-LocalLastModified' in response.headers:
                try:
                    timestamp = datetime.datetime.fromisoformat(
                        response.headers['X-Object-Meta-LocalLastModified']
                    ).timestamp()
                    os.utime(local_path, (timestamp, timestamp))
                except Exception as e:
                    self.logger.warning(f"Failed to set timestamp for {file.relative_path}: {e}")

        except Exception as e:
            if retries > 1:
                self.logger.debug(f"Failed sync of {file.relative_path}, retrying (retries left {retries - 1})")
                self.download_file(file, retries - 1)
            else:
                self.logger.error(f"Failed to download {file.relative_path}: {e}")


class SyncClassifier:
    def __init__(self):
        self.action = None
        self.updated_local = list()
        self.updated_cloud = list()
        self.not_existing_locally = list()
        self.not_existing_remotely = list()

    def get_action(self):
        if len(self.updated_local) == 0 and len(self.updated_cloud) > 0:
            self.action = SyncAction.DOWNLOAD
        elif len(self.updated_local) > 0 and len(self.updated_cloud) == 0:
            self.action = SyncAction.UPLOAD
        elif len(self.updated_local) == 0 and len(self.updated_cloud) == 0:
            self.action = SyncAction.NONE
        else:
            self.action = SyncAction.CONFLICT
        return self.action

    @classmethod
    def classify(cls, local, cloud, timestamp):
        classifier = cls()

        local_paths = [f.relative_path for f in local]
        cloud_paths = [f.relative_path for f in cloud]

        for f in local:
            if f.relative_path not in cloud_paths:
                classifier.not_existing_remotely.append(f)
            if f.update_ts and f.update_ts > timestamp:
                classifier.updated_local.append(f)

        for f in cloud:
            if f.md5 == "aadd86936a80ee8a369579c3926f1b3c":
                continue
            if f.relative_path not in local_paths:
                classifier.not_existing_locally.append(f)
            if f.update_ts and f.update_ts > timestamp:
                classifier.updated_cloud.append(f)

        return classifier
