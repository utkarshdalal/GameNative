"""
Android-compatible authentication module
Based on original auth.py with Android compatibility
"""

import json
import os
import logging
import requests
import time
from typing import Optional, Dict, Any

CLIENT_ID = "46899977096215655"
CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"

class AuthorizationManager:
    """Android-compatible authorization manager with token refresh"""
    
    def __init__(self, config_path: str):
        self.config_path = config_path
        self.logger = logging.getLogger("AUTH")
        self.credentials_data = {}
        self._read_config()
        
    def _read_config(self):
        """Read credentials from config file"""
        if os.path.exists(self.config_path):
            try:
                with open(self.config_path, "r") as f:
                    self.credentials_data = json.load(f)
            except Exception as e:
                self.logger.error(f"Failed to read config: {e}")
                self.credentials_data = {}
    
    def _write_config(self):
        """Write credentials to config file"""
        try:
            os.makedirs(os.path.dirname(self.config_path), exist_ok=True)
            with open(self.config_path, "w") as f:
                json.dump(self.credentials_data, f, indent=2)
        except Exception as e:
            self.logger.error(f"Failed to write config: {e}")
    
    def get_credentials(self, client_id=None, client_secret=None):
        """
        Reads data from config and returns it with automatic refresh if expired
        :param client_id: GOG client ID
        :param client_secret: GOG client secret
        :return: dict with credentials or None if not present
        """
        if not client_id:
            client_id = CLIENT_ID
        if not client_secret:
            client_secret = CLIENT_SECRET
            
        credentials = self.credentials_data.get(client_id)
        if not credentials:
            return None

        # Check if credentials are expired and refresh if needed
        if self.is_credential_expired(client_id):
            if self.refresh_credentials(client_id, client_secret):
                credentials = self.credentials_data.get(client_id)
            else:
                return None
                
        return credentials
    
    def is_credential_expired(self, client_id=None) -> bool:
        """
        Checks if provided client_id credential is expired
        :param client_id: GOG client ID
        :return: whether credentials are expired
        """
        if not client_id:
            client_id = CLIENT_ID
        credentials = self.credentials_data.get(client_id)

        if not credentials:
            return True

        # If no loginTime or expires_in, assume expired
        if "loginTime" not in credentials or "expires_in" not in credentials:
            return True

        return time.time() >= credentials["loginTime"] + credentials["expires_in"]
    
    def refresh_credentials(self, client_id=None, client_secret=None) -> bool:
        """
        Refreshes credentials and saves them to config
        :param client_id: GOG client ID
        :param client_secret: GOG client secret
        :return: bool if operation was success
        """
        if not client_id:
            client_id = CLIENT_ID
        if not client_secret:
            client_secret = CLIENT_SECRET

        credentials = self.credentials_data.get(CLIENT_ID)
        if not credentials or "refresh_token" not in credentials:
            self.logger.error("No refresh token available")
            return False

        refresh_token = credentials["refresh_token"]
        url = f"https://auth.gog.com/token?client_id={client_id}&client_secret={client_secret}&grant_type=refresh_token&refresh_token={refresh_token}"

        try:
            response = requests.get(url, timeout=10)
        except (requests.ConnectionError, requests.Timeout):
            self.logger.error("Failed to refresh credentials")
            return False
            
        if not response.ok:
            self.logger.error(f"Failed to refresh credentials: HTTP {response.status_code}")
            return False
            
        data = response.json()
        data["loginTime"] = time.time()
        self.credentials_data.update({client_id: data})
        self._write_config()
        return True
        
    def get_access_token(self) -> Optional[str]:
        """Get access token from auth config"""
        credentials = self.get_credentials()
        if credentials and 'access_token' in credentials:
            return credentials['access_token']
        return None
            
    def is_authenticated(self) -> bool:
        """Check if user is authenticated"""
        return self.get_access_token() is not None
