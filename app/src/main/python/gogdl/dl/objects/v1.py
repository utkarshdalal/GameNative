import json
import os
from gogdl.dl import dl_utils
from gogdl.dl.objects import generic, v2
from gogdl import constants
from gogdl.languages import Language


class Depot:
    def __init__(self, target_lang, depot_data):
        self.target_lang = target_lang
        self.languages = depot_data["languages"]
        self.game_ids = depot_data["gameIDs"]
        self.size = int(depot_data["size"])
        self.manifest = depot_data["manifest"]

    def check_language(self):
        status = True
        for lang in self.languages:
            status = lang == "Neutral" or lang == self.target_lang
            if status:
                break
        return status

class Directory:
    def __init__(self, item_data):
        self.path = item_data["path"].replace(constants.NON_NATIVE_SEP, os.sep).lstrip(os.sep)

class Dependency:
    def __init__(self, data):
        self.id = data["redist"]
        self.size = data.get("size")
        self.target_dir = data.get("targetDir")


class File:
    def __init__(self, data, product_id):
        self.offset = data.get("offset")
        self.hash = data.get("hash")
        self.url = data.get("url")
        self.path = data["path"].lstrip("/")
        self.size = data["size"]
        self.flags = []
        if data.get("support"):
            self.flags.append("support")
        if data.get("executable"):
            self.flags.append("executble")

        self.product_id = product_id

class Manifest:
    def __init__(self, platform, meta, language, dlcs, api_handler, dlc_only):
        self.platform = platform
        self.data = meta
        self.data['HGLPlatform'] = platform
        self.data["HGLInstallLanguage"] = language.code
        self.data["HGLdlcs"] = dlcs
        self.product_id = meta["product"]["rootGameID"]
        self.dlcs = dlcs
        self.dlc_only = dlc_only 
        self.all_depots = [] 
        self.depots = self.parse_depots(language, meta["product"]["depots"])
        self.dependencies = [Dependency(depot) for depot in meta["product"]["depots"] if depot.get('redist')]
        self.dependencies_ids = [depot['redist'] for depot in meta["product"]["depots"] if depot.get('redist')]

        self.api_handler = api_handler

        self.files = []
        self.dirs = []

    @classmethod
    def from_json(cls, meta, api_handler):
        manifest = cls(meta['HGLPlatform'], meta, Language.parse(meta['HGLInstallLanguage']), meta["HGLdlcs"], api_handler, False)
        return manifest
    
    def serialize_to_json(self):
        return json.dumps(self.data)

    def parse_depots(self, language, depots):
        parsed = []
        dlc_ids = [dlc["id"] for dlc in self.dlcs]
        for depot in depots:
            if depot.get("redist"):
                continue
            
            for g_id in depot["gameIDs"]:
                if g_id in dlc_ids or (not self.dlc_only and self.product_id == g_id):
                    new_depot = Depot(language, depot)
                    parsed.append(new_depot)
                    self.all_depots.append(new_depot)
                    break
        return list(filter(lambda x: x.check_language(), parsed))

    def list_languages(self):
        languages_dict = set()
        for depot in self.all_depots:
            for language in depot.languages:
                if language != "Neutral":
                    languages_dict.add(Language.parse(language).code)

        return list(languages_dict)

    def calculate_download_size(self):
        data = dict()

        for depot in self.all_depots:
            for product_id in depot.game_ids:
                if not product_id in data:
                    data[product_id] = dict()
                product_data = data[product_id]
                for lang in depot.languages:
                    if lang == "Neutral":
                        lang = "*"
                    if not lang in product_data:
                        product_data[lang] = {"download_size": 0, "disk_size": 0}
                    
                    product_data[lang]["download_size"] += depot.size
                    product_data[lang]["disk_size"] += depot.size
        
        return data 

    
    def get_files(self):
        for depot in self.depots:
            manifest = dl_utils.get_json(self.api_handler, f"{constants.GOG_CDN}/content-system/v1/manifests/{depot.game_ids[0]}/{self.platform}/{self.data['product']['timestamp']}/{depot.manifest}")
            for record in manifest["depot"]["files"]:
                if "directory" in record:
                    self.dirs.append(Directory(record)) 
                else:
                    self.files.append(File(record, depot.game_ids[0]))

class ManifestDiff(generic.BaseDiff):
    def __init__(self):
        super().__init__()
    
    @classmethod
    def compare(cls, new_manifest, old_manifest=None):
        comparison = cls()

        if not old_manifest:
            comparison.new = new_manifest.files
            return comparison

        new_files = dict()
        for file in new_manifest.files:
            new_files.update({file.path.lower(): file})
        
        old_files = dict()
        for file in old_manifest.files:
            old_files.update({file.path.lower(): file})

        for old_file in old_files.values():
            if not new_files.get(old_file.path.lower()):
                comparison.deleted.append(old_file)
        
        if type(old_manifest) == v2.Manifest:
            comparison.new = new_manifest.files
            return comparison
    
        for new_file in new_files.values():
            old_file = old_files.get(new_file.path.lower())
            if not old_file:
                comparison.new.append(new_file)
            else:
                if new_file.hash != old_file.hash:
                    comparison.changed.append(new_file)

        return comparison