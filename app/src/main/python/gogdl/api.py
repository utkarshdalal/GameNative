import logging
import time
import requests
import json
from multiprocessing import cpu_count
from gogdl.dl import dl_utils
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
        self._update_auth_header()
        self.owned = []

        self.endpoints = dict()  # Map of secure link endpoints
        self.working_on_ids = list()  # List of products we are waiting for to complete getting the secure link

    def _update_auth_header(self):
        """Update authorization header with fresh token"""
        credentials = self.auth_manager.get_credentials()
        if credentials:
            token = credentials.get("access_token")
            if token:
                self.session.headers["Authorization"] = f"Bearer {token}"
                self.logger.debug(f"Authorization header updated with token: {token[:20]}...")
            else:
                self.logger.warning("No access_token found in credentials")
        else:
            self.logger.warning("No credentials available")

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
        # Refresh auth header before making request
        self._update_auth_header()

        # Try the embed endpoint which is more reliable for getting owned games
        url = f'{constants.GOG_EMBED}/user/data/games'
        self.logger.info(f"Fetching user data from: {url}")
        response = self.session.get(url)
        self.logger.debug(f"Response status: {response.status_code}")
        if response.ok:
            data = response.json()
            self.logger.debug(f"User data keys: {list(data.keys())}")
            return data
        else:
            self.logger.error(f"Request failed with status {response.status_code}: {response.text[:200]}")
            return None

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

    def does_user_own(self, game_id):
        """Check if the user owns a specific game
        
        Args:
            game_id: The GOG game ID to check
            
        Returns:
            bool: True if the user owns the game, False otherwise
        """
        # If owned games list is populated, check it
        if self.owned:
            return str(game_id) in [str(g) for g in self.owned]
        
        # Otherwise, try to fetch user data and check
        try:
            user_data = self.get_user_data()
            if user_data and 'owned' in user_data:
                self.owned = [str(g) for g in user_data['owned']]
                return str(game_id) in self.owned
        except Exception as e:
            self.logger.warning(f"Failed to check game ownership for {game_id}: {e}")
        
        # If we can't determine, assume they own it (they're trying to download it)
        return True

    def get_dependencies_repo(self, depot_version=2):
        self.logger.info("Getting Dependencies repository")
        url = constants.DEPENDENCIES_URL if depot_version == 2 else constants.DEPENDENCIES_V1_URL
        response = self.session.get(url)
        if not response.ok:
            return None

        json_data = json.loads(response.content)
        return json_data

    def get_secure_link(self, product_id, path="", generation=2, root=None, attempt=0, max_retries=3):
        """Get secure download links from GOG API with bounded retry

        Args:
            product_id: GOG product ID
            path: Optional path parameter
            generation: API generation version (1 or 2)
            root: Optional root parameter
            attempt: Current attempt number (internal, default: 0)
            max_retries: Maximum number of retry attempts (default: 3)

        Returns:
            List of secure URLs, or empty list if all retries exhausted
        """
        if attempt >= max_retries:
            self.logger.error(f"Failed to get secure link after {max_retries} attempts for product {product_id}")
            return []

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
                self.logger.warning(
                    f"Invalid secure link response: {response.status_code} "
                    f"(attempt {attempt + 1}/{max_retries}) for product {product_id}"
                )
                sleep_time = 0.2 * (2 ** attempt)
                time.sleep(sleep_time)
                return self.get_secure_link(product_id, path, generation, root, attempt + 1, max_retries)

            return response.json().get('urls', [])

        except Exception as e:
            self.logger.error(
                f"Failed to get secure link: {e} "
                f"(attempt {attempt + 1}/{max_retries}) for product {product_id}"
            )
            sleep_time = 0.2 * (2 ** attempt)
            time.sleep(sleep_time)
            return self.get_secure_link(product_id, path, generation, root, attempt + 1, max_retries)
