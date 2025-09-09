import json
import os

from gogdl.dl import dl_utils
from gogdl.dl.objects import generic
from gogdl import constants


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

    def check_bitness(self, bitness):
        return self.bitness is None or self.bitness == bitness

    def is_language_compatible(self):
        return self.check_language()

    def is_bitness_compatible(self, bitness):
        return self.check_bitness(bitness)


class Manifest:
    """Android-compatible Manifest class matching heroic-gogdl structure"""
    def __init__(self, meta, language, dlcs, api_handler, dlc_only=False):
        import logging
        self.logger = logging.getLogger("Manifest")
        
        self.data = meta
        self.data["HGLInstallLanguage"] = language.code if hasattr(language, 'code') else language
        self.data["HGLdlcs"] = dlcs
        
        # Handle missing baseProductId gracefully
        if 'baseProductId' not in meta:
            self.logger.warning("No 'baseProductId' key found in meta data")
            # Try to get it from other possible keys
            if 'productId' in meta:
                self.product_id = meta['productId']
            elif 'id' in meta:
                self.product_id = meta['id']
            else:
                self.product_id = str(meta.get('game_id', 'unknown'))
            self.data["baseProductId"] = self.product_id
        else:
            self.product_id = meta["baseProductId"]
            
        self.dlcs = dlcs
        self.dlc_only = dlc_only
        self.all_depots = []
        
        # Handle missing depots gracefully
        if 'depots' not in meta:
            self.logger.warning("No 'depots' key found in meta data")
            self.depots = []
        else:
            self.depots = self.parse_depots(language, meta["depots"])
            
        self.dependencies_ids = meta.get("dependencies", [])
        
        # Handle missing installDirectory gracefully
        if 'installDirectory' not in meta:
            self.logger.warning("No 'installDirectory' key found in meta data")
            self.install_directory = f"game_{self.product_id}"
        else:
            self.install_directory = meta["installDirectory"]
            
        self.api_handler = api_handler
        self.files = []
        self.dirs = []

    @classmethod
    def from_json(cls, meta, api_handler):
        """Create Manifest from JSON data"""
        language = meta.get("HGLInstallLanguage", "en-US")
        dlcs = meta.get("HGLdlcs", [])
        return cls(meta, language, dlcs, api_handler, False)

    def serialize_to_json(self):
        """Serialize manifest to JSON"""
        return json.dumps(self.data)

    def parse_depots(self, language, depots):
        """Parse depots like heroic-gogdl does"""
        self.logger.debug(f"Parsing depots: {len(depots) if depots else 0} depots found")
        if depots:
            self.logger.debug(f"First depot structure: {depots[0]}")
        
        parsed = []
        dlc_ids = [dlc["id"] for dlc in self.dlcs] if self.dlcs else []
        
        for depot in depots:
            if depot["productId"] in dlc_ids or (
                    not self.dlc_only and self.product_id == depot["productId"]
            ):
                new_depot = Depot(language, depot)
                parsed.append(new_depot)
                self.all_depots.append(new_depot)
                
        filtered_depots = list(filter(lambda x: x.check_language(), parsed))
        self.logger.debug(f"After filtering: {len(filtered_depots)} depots remain")
        return filtered_depots

    def list_languages(self):
        """List available languages"""
        languages_dict = set()
        for depot in self.all_depots:
            for language in depot.languages:
                if language != "*":
                    languages_dict.add(language)
        return list(languages_dict)

    def get_files(self):
        """Get files from all depots - Android compatible version"""
        import logging
        logger = logging.getLogger("Manifest")
        
        for depot in self.depots:
            try:
                # Get depot manifest URL using the same pattern as heroic-gogdl
                depot_url = f"https://gog-cdn-fastly.gog.com/content-system/v2/meta/{dl_utils.galaxy_path(depot.manifest)}"
                
                # Get depot data
                depot_data, headers = dl_utils.get_zlib_encoded(self.api_handler, depot_url)
                
                if 'depot' in depot_data and 'items' in depot_data['depot']:
                    items = depot_data['depot']['items']
                    logger.debug(f"Depot {depot.product_id} contains {len(items)} files")
                    
                    for item in items:
                        if 'chunks' in item:  # It's a file
                            depot_file = DepotFile(item, depot.product_id)
                            self.files.append(depot_file)
                        elif 'target' in item:  # It's a link
                            depot_link = DepotLink(item)
                            self.files.append(depot_link)
                        else:  # It's a directory
                            depot_dir = DepotDirectory(item)
                            self.dirs.append(depot_dir)
                            
            except Exception as e:
                logger.error(f"Failed to get files for depot {depot.product_id}: {e}")
                raise


class Build:
    def __init__(self, build_data, target_lang):
        self.target_lang = target_lang
        self.id = build_data["build_id"]
        self.product_id = build_data["product_id"]
        self.os = build_data["os"]
        self.branch = build_data.get("branch")
        self.version_name = build_data["version_name"]
        self.tags = build_data.get("tags") or []
        self.public = build_data.get("public", True)
        self.date_published = build_data.get("date_published")
        self.generation = build_data.get("generation", 2)
        self.meta_url = build_data["link"]
        self.password_required = build_data.get("password_required", False)
        self.legacy_build_id = build_data.get("legacy_build_id")
        self.total_size = 0
        self.install_directory = None
        self.executable = None

    def get_info(self, api_handler, bitness=64):
        manifest_json = dl_utils.get_json(api_handler, self.meta_url)
        if not manifest_json:
            return None
        
        self.install_directory = manifest_json.get("installDirectory")
        self.executable = manifest_json.get("gameExecutables", [{}])[0].get("path")
        
        depot_files = []
        for depot_data in manifest_json.get("depots", []):
            depot = Depot(self.target_lang, depot_data)
            if not depot.is_language_compatible():
                continue
            if not depot.is_bitness_compatible(bitness):
                continue
            depot_files.append(depot)
            self.total_size += depot.size
        
        return depot_files
