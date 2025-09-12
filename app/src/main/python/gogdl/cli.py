#!/usr/bin/env python3
"""
Android-compatible GOGDL CLI module
Removes multiprocessing and other Android-incompatible features
"""

import gogdl.args as args
from gogdl.dl.managers import manager
import gogdl.api as api
import gogdl.auth as auth
from gogdl import version as gogdl_version
import json
import logging


def display_version():
    print(f"{gogdl_version}")


def handle_auth(arguments, api_handler):
    """Handle GOG authentication - exchange authorization code for access token or get existing credentials"""
    logger = logging.getLogger("GOGDL-AUTH")
    
    try:
        import requests
        import os
        import time
        
        # GOG OAuth constants
        GOG_CLIENT_ID = "46899977096215655"
        GOG_CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
        GOG_TOKEN_URL = "https://auth.gog.com/token"
        GOG_USER_URL = "https://embed.gog.com/userData.json"
        
        # Initialize authorization manager
        auth_manager = api_handler.auth_manager
        
        if arguments.code:
            # Exchange authorization code for access token
            logger.info("Exchanging authorization code for access token...")
            
            token_data = {
                "client_id": GOG_CLIENT_ID,
                "client_secret": GOG_CLIENT_SECRET,
                "grant_type": "authorization_code",
                "code": arguments.code,
                "redirect_uri": "https://embed.gog.com/on_login_success?origin=client"
            }
            
            response = requests.post(GOG_TOKEN_URL, data=token_data)
            
            if response.status_code != 200:
                error_msg = f"Token exchange failed: HTTP {response.status_code} - {response.text}"
                logger.error(error_msg)
                print(json.dumps({"error": True, "message": error_msg}))
                return
                
            token_response = response.json()
            access_token = token_response.get("access_token")
            refresh_token = token_response.get("refresh_token")
            
            if not access_token:
                error_msg = "No access token in response"
                logger.error(error_msg)
                print(json.dumps({"error": True, "message": error_msg}))
                return
                
            # Get user information
            logger.info("Getting user information...")
            user_response = requests.get(
                GOG_USER_URL,
                headers={"Authorization": f"Bearer {access_token}"}
            )
            
            username = "GOG User"
            user_id = "unknown"
            
            if user_response.status_code == 200:
                user_data = user_response.json()
                username = user_data.get("username", "GOG User")
                user_id = str(user_data.get("userId", "unknown"))
            else:
                logger.warning(f"Failed to get user info: HTTP {user_response.status_code}")
            
            # Save credentials with loginTime and expires_in (like original auth.py)
            auth_data = {
                GOG_CLIENT_ID: {
                    "access_token": access_token,
                    "refresh_token": refresh_token,
                    "user_id": user_id,
                    "username": username,
                    "loginTime": time.time(),
                    "expires_in": token_response.get("expires_in", 3600)
                }
            }
            
            os.makedirs(os.path.dirname(arguments.auth_config_path), exist_ok=True)
            
            with open(arguments.auth_config_path, 'w') as f:
                json.dump(auth_data, f, indent=2)
                
            logger.info(f"Authentication successful for user: {username}")
            print(json.dumps(auth_data[GOG_CLIENT_ID]))
            
        else:
            # Get existing credentials (like original auth.py get_credentials)
            logger.info("Getting existing credentials...")
            credentials = auth_manager.get_credentials()
            
            if credentials:
                logger.info(f"Retrieved credentials for user: {credentials.get('username', 'GOG User')}")
                print(json.dumps(credentials))
            else:
                logger.warning("No valid credentials found")
                print(json.dumps({"error": True, "message": "No valid credentials found"}))
        
    except Exception as e:
        logger.error(f"Authentication failed: {e}")
        print(json.dumps({"error": True, "message": str(e)}))
        raise


def main():
    arguments, unknown_args = args.init_parser()
    level = logging.INFO
    if '-d' in unknown_args or '--debug' in unknown_args:
        level = logging.DEBUG
    logging.basicConfig(format="[%(name)s] %(levelname)s: %(message)s", level=level)
    logger = logging.getLogger("GOGDL-ANDROID")
    logger.debug(arguments)
    
    if arguments.display_version:
        display_version()
        return
        
    if not arguments.command:
        print("No command provided!")
        return
        
    # Initialize Android-compatible managers
    authorization_manager = auth.AuthorizationManager(arguments.auth_config_path)
    api_handler = api.ApiHandler(authorization_manager)

    switcher = {}
    
    # Handle authentication command
    if arguments.command == "auth":
        switcher["auth"] = lambda: handle_auth(arguments, api_handler)
    
    # Handle download/info commands
    if arguments.command in ["download", "repair", "update", "info"]:
        download_manager = manager.AndroidManager(arguments, unknown_args, api_handler)
        switcher.update({
            "download": download_manager.download,
            "repair": download_manager.download,
            "update": download_manager.download,
            "info": lambda: download_manager.calculate_download_size(arguments, unknown_args),
        })
    
    # Handle save sync command
    if arguments.command == "save-sync":
        import gogdl.saves as saves
        clouds_storage_manager = saves.CloudStorageManager(api_handler, authorization_manager)
        switcher["save-sync"] = lambda: clouds_storage_manager.sync(arguments, unknown_args)

    if arguments.command in switcher:
        try:
            switcher[arguments.command]()
        except Exception as e:
            logger.error(f"Command failed: {e}")
            raise
    else:
        logger.error(f"Unknown command: {arguments.command}")


if __name__ == "__main__":
    main()
