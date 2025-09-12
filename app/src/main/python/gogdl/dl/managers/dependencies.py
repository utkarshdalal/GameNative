from sys import exit
import logging
import os
import json
from typing import Optional
from gogdl.dl import dl_utils
import gogdl.constants as constants
from gogdl.dl.managers.task_executor import ExecutingManager
from gogdl.dl.objects import v2
from gogdl.dl.objects.generic import BaseDiff


def get_depot_list(manifest, product_id=None):
    download_list = list()
    for item in manifest["depot"]["items"]:
        if item["type"] == "DepotFile":
            download_list.append(v2.DepotFile(item, product_id))
    return download_list


# Looks like we can use V2 dependencies for V1 games too WOAH
# We are doing that obviously 
class DependenciesManager:
    def __init__(
        self, ids, path, workers_count, api_handler, print_manifest=False, download_game_deps_only=False
    ):
        self.api = api_handler

        self.logger = logging.getLogger("REDIST")

        self.path = path
        self.installed_manifest = os.path.join(self.path, '.gogdl-redist-manifest')
        self.workers_count = int(workers_count)
        self.build = self.api.get_dependencies_repo()
        self.repository = dl_utils.get_zlib_encoded(self.api, self.build['repository_manifest'])[0] or {}
        # Put version for easier serialization
        self.repository['build_id'] = self.build['build_id']

        self.ids = ids
        self.download_game_deps_only = download_game_deps_only  # Basically skip all redist with path starting with __redist
        if self.repository and print_manifest:
            print(json.dumps(self.repository))

    def get_files_for_depot_manifest(self, manifest):
        url = f'{constants.GOG_CDN}/content-system/v2/dependencies/meta/{dl_utils.galaxy_path(manifest)}'
        manifest = dl_utils.get_zlib_encoded(self.api, url)[0]

        return get_depot_list(manifest, 'redist')


    def get(self, return_files=False):
        old_depots = []
        new_depots = []
        if not self.ids:
            return []
        installed = set()

        # This will be always None for redist writen in game dir
        existing_manifest = None
        if os.path.exists(self.installed_manifest):
            try:
                with open(self.installed_manifest, 'r') as f:
                    existing_manifest = json.load(f)
            except Exception:
                existing_manifest = None
                pass
            else:
                if 'depots' in existing_manifest and 'build_id' in existing_manifest:
                    already_installed = existing_manifest.get('HGLInstalled') or []
                    for depot in existing_manifest["depots"]:
                        if depot["dependencyId"] in already_installed:
                            old_depots.append(depot)

        for depot in self.repository["depots"]:
            if depot["dependencyId"] in self.ids:
                # By default we want to download all redist beginning
                # with redist (game installation runs installation of the game's ones)
                should_download = depot["executable"]["path"].startswith("__redist")
                
                # If we want to download redist located in game dir we flip the boolean
                if self.download_game_deps_only:
                    should_download = not should_download

                if should_download:
                    installed.add(depot['dependencyId'])
                    new_depots.append(depot)

        new_files = []
        old_files = []

        # Collect files for each redistributable
        for depot in new_depots:
            new_files += self.get_files_for_depot_manifest(depot["manifest"])

        for depot in old_depots:
            old_files += self.get_files_for_depot_manifest(depot["manifest"])

        if return_files:
            return new_files


        diff = DependenciesDiff.compare(new_files, old_files)

        if not len(diff.changed) and not len(diff.deleted) and not len(diff.new):
            self.logger.info("Nothing to do")
            self._write_manifest(installed)
            return

        secure_link = dl_utils.get_dependency_link(self.api) # This should never expire
        executor = ExecutingManager(self.api, self.workers_count, self.path, os.path.join(self.path, 'gog-support'), diff, {'redist': secure_link}, 'gog-redist')
        success = executor.setup()
        if not success:
            print('Unable to proceed, Not enough disk space')
            exit(2)
        cancelled = executor.run()

        if cancelled:
            return

        self._write_manifest(installed)

    def _write_manifest(self, installed: set):
        repository = self.repository
        repository['HGLInstalled'] = list(installed)
        with open(self.installed_manifest, 'w') as f:
            json.dump(repository, f)


class DependenciesDiff(BaseDiff):
    def __init__(self):
        super().__init__()

    @classmethod
    def compare(cls, new_files: list, old_files: Optional[list]):
        comparison = cls()
        
        if not old_files:
            comparison.new = new_files
            return comparison

        new_files_paths = dict()
        for file in new_files:
            new_files_paths.update({file.path.lower(): file})

        old_files_paths = dict()
        for file in old_files:
            old_files_paths.update({file.path.lower(): file})
        
        for old_file in old_files_paths.values():
            if not new_files_paths.get(old_file.path.lower()):
                comparison.deleted.append(old_file)

        for new_file in new_files_paths.values():
            old_file = old_files_paths.get(new_file.path.lower())
            if not old_file:
                comparison.new.append(new_file)
            else:
                if len(new_file.chunks) == 1 and len(old_file.chunks) == 1:
                    if new_file.chunks[0]["md5"] != old_file.chunks[0]["md5"]:
                        comparison.changed.append(new_file)
                else:
                    if (new_file.md5 and old_file.md5 and new_file.md5 != old_file.md5) or (new_file.sha256 and old_file.sha256 != new_file.sha256):
                        comparison.changed.append(v2.FileDiff.compare(new_file, old_file))
                    elif len(new_file.chunks) != len(old_file.chunks):
                        comparison.changed.append(v2.FileDiff.compare(new_file, old_file))
        return comparison 
