import json
import os

from gogdl.dl import dl_utils
from gogdl.dl.objects import generic, v1
from gogdl import constants
from gogdl.languages import Language


class DepotFile:
    def __init__(self, item_data, product_id):
        self.flags = item_data.get("flags") or list()
        self.path = item_data["path"].replace(constants.NON_NATIVE_SEP, os.sep).lstrip(os.sep)
        if "support" in self.flags:
            self.path = os.path.join(product_id, self.path)
        self.chunks = item_data["chunks"]
        self.md5 = item_data.get("md5")
        self.sha256 = item_data.get("sha256")
        self.product_id = product_id


# That exists in some depots, indicates directory to be created, it has only path in it
# Yes that's the thing
class DepotDirectory:
    def __init__(self, item_data):
        self.path = item_data["path"].replace(constants.NON_NATIVE_SEP, os.sep).rstrip(os.sep)
    
class DepotLink:
    def __init__(self, item_data):
        self.path = item_data["path"]
        self.target = item_data["target"]


class Depot:
    def __init__(self, target_lang, depot_data):
        self.target_lang = target_lang
        self.languages = depot_data["languages"]
        self.bitness = depot_data.get("osBitness")
        self.product_id = depot_data["productId"]
        self.compressed_size = depot_data.get("compressedSize") or 0
        self.size = depot_data.get("size") or 0
        self.manifest = depot_data["manifest"]

    def check_language(self):
        status = False
        for lang in self.languages:
            status = (
                    lang == "*"
                    or self.target_lang == lang
            )
            if status:
                break
        return status

class Manifest:
    def __init__(self, meta, language, dlcs, api_handler, dlc_only):
        self.data = meta
        self.data["HGLInstallLanguage"] = language.code
        self.data["HGLdlcs"] = dlcs
        self.product_id = meta["baseProductId"]
        self.dlcs = dlcs
        self.dlc_only = dlc_only
        self.all_depots = []
        self.depots = self.parse_depots(language, meta["depots"])
        self.dependencies_ids = meta.get("dependencies")
        if not self.dependencies_ids:
            self.dependencies_ids = list()
        self.install_directory = meta["installDirectory"]

        self.api_handler = api_handler

        self.files = []
        self.dirs = []

    @classmethod
    def from_json(cls, meta, api_handler):
        manifest = cls(meta, Language.parse(meta["HGLInstallLanguage"]), meta["HGLdlcs"], api_handler, False)
        return manifest

    def serialize_to_json(self):
        return json.dumps(self.data)

    def parse_depots(self, language, depots):
        parsed = []
        dlc_ids = [dlc["id"] for dlc in self.dlcs]
        for depot in depots:
            if depot["productId"] in dlc_ids or (
                    not self.dlc_only and self.product_id == depot["productId"]
            ):
                new_depot = Depot(language, depot)
                parsed.append(new_depot)
                self.all_depots.append(new_depot)
                

        return list(filter(lambda x: x.check_language(), parsed))

    def list_languages(self):
        languages_dict = set()
        for depot in self.all_depots:
            for language in depot.languages:
                if language != "*":
                    languages_dict.add(Language.parse(language).code)

        return list(languages_dict)

    def calculate_download_size(self):
        data = dict()

        for depot in self.all_depots:
            if not depot.product_id in data:
                data[depot.product_id] = dict()
                data[depot.product_id]['*'] = {"download_size": 0, "disk_size": 0}
            product_data = data[depot.product_id]
            for lang in depot.languages:
                if not lang in product_data:
                    product_data[lang] = {"download_size":0, "disk_size":0} 
                
                product_data[lang]["download_size"] += depot.compressed_size
                product_data[lang]["disk_size"] += depot.size

        return data 

    def get_files(self):
        for depot in self.depots:
            manifest = dl_utils.get_zlib_encoded(
                self.api_handler,
                f"{constants.GOG_CDN}/content-system/v2/meta/{dl_utils.galaxy_path(depot.manifest)}",
            )[0]
            for item in manifest["depot"]["items"]:
                if item["type"] == "DepotFile":
                    self.files.append(DepotFile(item, depot.product_id))
                elif item["type"] == "DepotLink":
                    self.files.append(DepotLink(item))
                else:
                    self.dirs.append(DepotDirectory(item))

class FileDiff:
    def __init__(self):
        self.file: DepotFile
        self.old_file_flags: list[str]
        self.disk_size_diff: int = 0

    @classmethod
    def compare(cls, new: DepotFile, old: DepotFile):
        diff = cls()
        diff.disk_size_diff = sum([ch['size'] for ch in new.chunks])
        diff.disk_size_diff -= sum([ch['size'] for ch in old.chunks])
        diff.old_file_flags = old.flags
        for new_chunk in new.chunks:
            old_offset = 0
            for old_chunk in old.chunks:
                if old_chunk["md5"] == new_chunk["md5"]:
                    new_chunk["old_offset"] = old_offset
                old_offset += old_chunk["size"]
        diff.file = new
        return diff

# Using xdelta patching
class FilePatchDiff:
    def __init__(self, data):
        self.md5_source = data['md5_source']
        self.md5_target = data['md5_target']
        self.source = data['path_source'].replace('\\', '/')
        self.target = data['path_target'].replace('\\', '/')
        self.md5 = data['md5']
        self.chunks = data['chunks']

        self.old_file: DepotFile
        self.new_file: DepotFile

class ManifestDiff(generic.BaseDiff):
    def __init__(self):
        super().__init__()

    @classmethod
    def compare(cls, manifest, old_manifest=None, patch=None):
        comparison = cls()
        is_manifest_upgrade = isinstance(old_manifest, v1.Manifest)

        if not old_manifest:
            comparison.new = manifest.files
            return comparison

        new_files = dict()
        for file in manifest.files:
            new_files.update({file.path.lower(): file})

        old_files = dict()
        for file in old_manifest.files:
            old_files.update({file.path.lower(): file})

        for old_file in old_files.values():
            if not new_files.get(old_file.path.lower()):
                comparison.deleted.append(old_file)

        for new_file in new_files.values():
            old_file = old_files.get(new_file.path.lower())
            if isinstance(new_file, DepotLink):
                comparison.links.append(new_file)
                continue
            if not old_file:
                comparison.new.append(new_file)
            else:
                if is_manifest_upgrade:
                    if len(new_file.chunks) == 0:
                        continue
                    new_final_sum = new_file.md5 or new_file.chunks[0]["md5"]
                    if new_final_sum:
                        if old_file.hash != new_final_sum:
                            comparison.changed.append(new_file)
                    continue

                patch_file = None
                if patch and len(old_file.chunks):
                    for p_file in patch.files:
                        old_final_sum = old_file.md5 or old_file.chunks[0]["md5"]
                        if p_file.md5_source == old_final_sum:
                            patch_file = p_file
                            patch_file.old_file = old_file
                            patch_file.new_file = new_file 

                if patch_file:
                    comparison.changed.append(patch_file)
                    continue

                if len(new_file.chunks) == 1 and len(old_file.chunks) == 1:
                    if new_file.chunks[0]["md5"] != old_file.chunks[0]["md5"]:
                        comparison.changed.append(new_file)
                else:
                    if (new_file.md5 and old_file.md5 and new_file.md5 != old_file.md5) or (new_file.sha256 and old_file.sha256 and old_file.sha256 != new_file.sha256):
                        comparison.changed.append(FileDiff.compare(new_file, old_file))
                    elif len(new_file.chunks) != len(old_file.chunks):
                        comparison.changed.append(FileDiff.compare(new_file, old_file))
        return comparison

class Patch:
    def __init__(self):
        self.patch_data = {}
        self.files = []

    @classmethod
    def get(cls,  manifest, old_manifest, lang: str, dlcs: list, api_handler):
        if isinstance(manifest, v1.Manifest) or isinstance(old_manifest, v1.Manifest):
            return None
        from_build = old_manifest.data.get('buildId')
        to_build = manifest.data.get('buildId')
        if not from_build or not to_build:
            return None
        dlc_ids = [dlc["id"] for dlc in dlcs]
        patch_meta = dl_utils.get_zlib_encoded(api_handler, f'{constants.GOG_CONTENT_SYSTEM}/products/{manifest.product_id}/patches?_version=4&from_build_id={from_build}&to_build_id={to_build}')[0]
        if not patch_meta or patch_meta.get('error'):
            return None
        patch_data = dl_utils.get_zlib_encoded(api_handler, patch_meta['link'])[0]
        if not patch_data:
            return None
         
        if patch_data['algorithm'] != 'xdelta3':
            print("Unsupported patch algorithm")
            return None
        
        depots = []
        # Get depots we need
        for depot in patch_data['depots']:
            if depot['productId'] == patch_data['baseProductId'] or depot['productId'] in dlc_ids:
                if lang in depot['languages']:
                    depots.append(depot)

        if not depots:
            return None

        files = []
        fail = False
        for depot in depots:
            depotdiffs = dl_utils.get_zlib_encoded(api_handler, f'{constants.GOG_CDN}/content-system/v2/patches/meta/{dl_utils.galaxy_path(depot["manifest"])}')[0]
            if not depotdiffs:
                fail = True
                break
            for diff in depotdiffs['depot']['items']:
                if diff['type'] == 'DepotDiff':
                   files.append(FilePatchDiff(diff))
                else:
                    print('Unknown type in patcher', diff['type'])
                    return None
    
        if fail:
            # TODO: Handle this beter
            # Maybe exception?
            print("Failed to get patch manifests")
            return None
        
        patch = cls()
        patch.patch_data = patch_data
        patch.files = files

        return patch
