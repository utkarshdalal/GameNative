"""
Android-compatible V2 manager for Windows game downloads
"""

# Handle newer depots download
# This was introduced in GOG Galaxy 2.0, it features compression and files split by chunks
import json
from sys import exit
from gogdl.dl import dl_utils
import gogdl.dl.objects.v2 as v2
import hashlib
from gogdl.dl.managers import dependencies
from gogdl.dl.managers.task_executor import ExecutingManager
from gogdl.dl.workers import task_executor
from gogdl.languages import Language
from gogdl import constants
import os
import logging


class Manager:
    def __init__(self, generic_manager):
        self.game_id = generic_manager.game_id
        self.arguments = generic_manager.arguments
        self.unknown_arguments = generic_manager.unknown_arguments
        if "path" in self.arguments:
            self.path = self.arguments.path
        else:
            self.path = ""
        if "support_path" in self.arguments:
            self.support = self.arguments.support_path
        else:
            self.support = ""

        self.allowed_threads = generic_manager.allowed_threads

        self.api_handler = generic_manager.api_handler
        self.should_append_folder_name = generic_manager.should_append_folder_name
        self.is_verifying = generic_manager.is_verifying

        self.builds = generic_manager.builds
        self.build = generic_manager.target_build
        self.version_name = self.build["version_name"]

        self.lang = Language.parse(self.arguments.lang or "en-US")
        self.dlcs_should_be_downloaded = self.arguments.dlcs
        if self.arguments.dlcs_list:
            self.dlcs_list = self.arguments.dlcs_list.split(",")
        else:
            self.dlcs_list = list()
        self.dlc_only = self.arguments.dlc_only

        self.manifest = None
        self.stop_all_threads = False

        self.logger = logging.getLogger("V2")
        self.logger.info("Initialized V2 Download Manager")

    def get_download_size(self):
        self.get_meta()
        dlcs = self.get_dlcs_user_owns(info_command=True)
        self.manifest = v2.Manifest(self.meta, self.lang, dlcs, self.api_handler, False)
        
        build = self.api_handler.get_dependencies_repo()
        repository = dl_utils.get_zlib_encoded(self.api_handler, build['repository_manifest'])[0] or {}
    
        size_data = self.manifest.calculate_download_size()

        for depot in repository["depots"]:
            if depot["dependencyId"] in self.manifest.dependencies_ids:
                if not depot["executable"]["path"].startswith("__redist"):
                    size_data[self.game_id]['*']["download_size"] += depot.get("compressedSize") or 0
                    size_data[self.game_id]['*']["disk_size"] += depot.get("size") or 0

        available_branches = set([build["branch"] for build in self.builds["items"] if build["branch"]])
        available_branches_list = [None] + list(available_branches)
        

        for dlc in dlcs:
            dlc.update({"size": size_data[dlc["id"]]})

        response = {
            "size": size_data[self.game_id],
            "dlcs": dlcs,
            "buildId": self.build["build_id"],
            "languages": self.manifest.list_languages(),
            "folder_name": self.meta["installDirectory"],
            "dependencies": self.manifest.dependencies_ids,
            "versionEtag": self.version_etag,
            "versionName": self.version_name,
            "available_branches": available_branches_list
        }
        return response

    def download(self):
        manifest_path = os.path.join(constants.MANIFESTS_DIR, self.game_id)
        old_manifest = None

        # Load old manifest
        if os.path.exists(manifest_path):
            self.logger.debug(f"Loading existing manifest for game {self.game_id}")
            with open(manifest_path, 'r') as f_handle:
                try:
                    json_data = json.load(f_handle)
                    self.logger.info("Creating Manifest instance from existing manifest")
                    old_manifest = dl_utils.create_manifest_class(json_data, self.api_handler)
                except json.JSONDecodeError:
                    old_manifest = None
                    pass

        if self.is_verifying:
            if old_manifest:
                self.logger.warning("Verifying - ignoring obtained manifest in favor of existing one")
                self.manifest = old_manifest
                dlcs_user_owns = self.manifest.dlcs or []
                old_manifest = None
            else:
                raise Exception("No manifest stored locally, unable to verify")
        else:
            self.get_meta()
            dlcs_user_owns = self.get_dlcs_user_owns(
                requested_dlcs=self.dlcs_list
            )
    
            if self.arguments.dlcs_list:
                self.logger.info(f"Requested dlcs {self.arguments.dlcs_list}")
                self.logger.info(f"Owned dlcs {dlcs_user_owns}")

            self.logger.debug("Parsing manifest")
            self.manifest = v2.Manifest(
                self.meta, self.lang, dlcs_user_owns, self.api_handler, self.dlc_only
            )
        patch = None
        if self.manifest:
            self.logger.debug("Requesting files of primary manifest")
            self.manifest.get_files()
        if old_manifest:
            self.logger.debug("Requesting files of previous manifest")
            old_manifest.get_files()
            patch = v2.Patch.get(self.manifest, old_manifest, self.lang, dlcs_user_owns, self.api_handler)
            if not patch:
                self.logger.info("No patch found, falling back to chunk based updates")

        diff = v2.ManifestDiff.compare(self.manifest, old_manifest, patch)
        self.logger.info(diff)


        dependencies_manager = dependencies.DependenciesManager(self.manifest.dependencies_ids, self.path,
                                                                self.arguments.workers_count, self.api_handler, download_game_deps_only=True)

        # Find dependencies that are no longer used
        if old_manifest:
            removed_dependencies = [id for id in old_manifest.dependencies_ids if id not in self.manifest.dependencies_ids]
            
            for depot in dependencies_manager.repository["depots"]:
                if depot["dependencyId"] in removed_dependencies and not depot["executable"]["path"].startswith("__redist"):
                    diff.removed_redist += dependencies_manager.get_files_for_depot_manifest(depot['manifest'])


        diff.redist = dependencies_manager.get(True) or []

        if not len(diff.changed) and not len(diff.deleted) and not len(diff.new) and not len(diff.redist) and not len(diff.removed_redist):
            self.logger.info("Nothing to do")
            return
        secure_link_endpoints_ids = [product["id"] for product in dlcs_user_owns]
        if not self.dlc_only:
            secure_link_endpoints_ids.append(self.game_id)
        secure_links = dict()
        for product_id in secure_link_endpoints_ids:
            secure_links.update(
                {
                    product_id: dl_utils.get_secure_link(
                        self.api_handler, "/", product_id
                    )
                }
            )
            if patch:
                secure_links.update(
                    {
                        f"{product_id}_patch": dl_utils.get_secure_link(
                            self.api_handler, "/", product_id, root="/patches/store"
                        )
                    }
                )

        if len(diff.redist) > 0:
            secure_links.update(
                {
                    'redist': dl_utils.get_dependency_link(self.api_handler)
                }
            )
        
        if self.is_verifying:
            new_diff = v2.ManifestDiff()
            invalid = 0

            for file in diff.new:
                if len(file.chunks) == 0:
                    continue
                if 'support' in file.flags:
                    file_path = os.path.join(self.support, file.path)
                else:
                    file_path = os.path.join(self.path, file.path)
                file_path = dl_utils.get_case_insensitive_name(file_path)
                if not os.path.exists(file_path):
                    invalid += 1
                    new_diff.new.append(file)
                    continue
                valid = True
                with open(file_path, 'rb') as fh:
                    for chunk in file.chunks:
                        chunk_sum  = hashlib.md5()
                        chunk_data = fh.read(chunk['size'])
                        chunk_sum.update(chunk_data)

                        if chunk_sum.hexdigest() != chunk['md5']:
                            valid = False
                            break
                if not valid:
                    invalid += 1
                    new_diff.new.append(file)
                    continue

            for file in diff.redist:
                if len(file.chunks) == 0:
                    continue
                file_path = dl_utils.get_case_insensitive_name(os.path.join(self.path, file.path))
                if not os.path.exists(file_path):
                    invalid += 1
                    new_diff.redist.append(file)
                    continue
                valid = True
                with open(file_path, 'rb') as fh:
                    for chunk in file.chunks:
                        chunk_sum  = hashlib.md5()
                        chunk_data = fh.read(chunk['size'])
                        chunk_sum.update(chunk_data)

                        if chunk_sum.hexdigest() != chunk['md5']:
                            valid = False
                            break
                if not valid:
                    invalid += 1
                    new_diff.redist.append(file)
                    continue
            for file in diff.links:
                file_path = os.path.join(self.path, file.path)
                file_path = dl_utils.get_case_insensitive_name(file_path)
                if not os.path.exists(file_path):
                    new_diff.links.append(file)

            if not invalid:
                self.logger.info("All files look good")
                return

            self.logger.info(f"Found {invalid} broken files, repairing...")
            diff = new_diff

        executor = ExecutingManager(self.api_handler, self.allowed_threads, self.path, self.support, diff, secure_links, self.game_id)
        success = executor.setup()
        if not success:
            print('Unable to proceed, Not enough disk space')
            exit(2)
        dl_utils.prepare_location(self.path)

        for dir in self.manifest.dirs:
            manifest_dir_path = os.path.join(self.path, dir.path)
            dl_utils.prepare_location(dl_utils.get_case_insensitive_name(manifest_dir_path))
        cancelled = executor.run()

        if cancelled:
            return

        dl_utils.prepare_location(constants.MANIFESTS_DIR)
        if self.manifest:
            with open(manifest_path, 'w') as f_handle:
                data = self.manifest.serialize_to_json()
                f_handle.write(data)

    def get_meta(self):
        meta_url = self.build["link"]
        self.meta, headers = dl_utils.get_zlib_encoded(self.api_handler, meta_url)
        self.version_etag = headers.get("Etag")

        # Append folder name when downloading
        if self.should_append_folder_name:
            self.path = os.path.join(self.path, self.meta["installDirectory"])

    def get_dlcs_user_owns(self, info_command=False, requested_dlcs=None):
        if requested_dlcs is None:
            requested_dlcs = list()
        if not self.dlcs_should_be_downloaded and not info_command:
            return []
        self.logger.debug("Getting dlcs user owns")
        dlcs = []
        if len(requested_dlcs) > 0:
            for product in self.meta["products"]:
                if (
                        product["productId"] != self.game_id
                        and product["productId"] in requested_dlcs
                        and self.api_handler.does_user_own(product["productId"])
                ):
                    dlcs.append({"title": product["name"], "id": product["productId"]})
            return dlcs
        for product in self.meta["products"]:
            if product["productId"] != self.game_id and self.api_handler.does_user_own(
                    product["productId"]
            ):
                dlcs.append({"title": product["name"], "id": product["productId"]})
        return dlcs
