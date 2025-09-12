import logging
import time
import requests
import json
from multiprocessing import cpu_count
from gogdl.dl import dl_utils
from gogdl import constants
import gogdl.constants as constants


class ApiHandler:
    def __init__(self, auth_manager):
        self.auth_manager = auth_manager
        self.logger = logging.getLogger("API")
        self.session = requests.Session()
        adapter = requests.adapters.HTTPAdapter(pool_maxsize=cpu_count())
        self.session.mount("https://", adapter)
        self.session.headers = {
            'User-Agent': f'gogdl/1.0.0 (Android GameNative)'
        }
        credentials = self.auth_manager.get_credentials()
        if credentials:
            token = credentials["access_token"]
            self.session.headers["Authorization"] = f"Bearer {token}"
        self.owned = []

        self.endpoints = dict()  # Map of secure link endpoints
        self.working_on_ids = list()  # List of products we are waiting for to complete getting the secure link

    def get_item_data(self, id, expanded=None):
        if expanded is None:
            expanded = []
        self.logger.info(f"Getting info from products endpoint for id: {id}")
        url = f'{constants.GOG_API}/products/{id}'
        expanded_arg = '?expand='
        if len(expanded) > 0:
            expanded_arg += ','.join(expanded)
            url += expanded_arg
        response = self.session.get(url)
        self.logger.debug(url)
        if response.ok:
            return response.json()
        else:
            self.logger.error(f"Request failed {response}")

    def get_game_details(self, id):
        url = f'{constants.GOG_EMBED}/account/gameDetails/{id}.json'
        response = self.session.get(url)
        if response.ok:
            return response.json()
        else:
            self.logger.error(f"Request failed {response}")

    def get_user_data(self):
        url = f'{constants.GOG_API}/user/data/games'
        response = self.session.get(url)
        if response.ok:
            return response.json()
        else:
            self.logger.error(f"Request failed {response}")

    def get_builds(self, product_id, platform):
        url = f'{constants.GOG_CONTENT_SYSTEM}/products/{product_id}/os/{platform}/builds?generation=2'
        response = self.session.get(url)
        if response.ok:
            return response.json()
        else:
            self.logger.error(f"Request failed {response}")

    def get_manifest(self, manifest_id, product_id):
        url = f'{constants.GOG_CONTENT_SYSTEM}/products/{product_id}/os/windows/builds/{manifest_id}'
        response = self.session.get(url)
        if response.ok:
            return response.json()
        else:
            self.logger.error(f"Request failed {response}")

    def get_authenticated_request(self, url):
        """Make an authenticated request with proper headers"""
        return self.session.get(url)

    def get_secure_link(self, product_id, path="", generation=2, root=None):
        """Get secure download links from GOG API"""
        url = ""
        if generation == 2:
            url = f"{constants.GOG_CONTENT_SYSTEM}/products/{product_id}/secure_link?_version=2&generation=2&path={path}"
        elif generation == 1:
            url = f"{constants.GOG_CONTENT_SYSTEM}/products/{product_id}/secure_link?_version=2&type=depot&path={path}"
        
        if root:
            url += f"&root={root}"
        
        try:
            response = self.get_authenticated_request(url)
            
            if response.status_code != 200:
                self.logger.warning(f"Invalid secure link response: {response.status_code}")
                time.sleep(0.2)
                return self.get_secure_link(product_id, path, generation, root)
            
            js = response.json()
            return js.get('urls', [])
            
        except Exception as e:
            self.logger.error(f"Failed to get secure link: {e}")
            time.sleep(0.2)
            return self.get_secure_link(product_id, path, generation, root)