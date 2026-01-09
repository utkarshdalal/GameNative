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
import gogdl.constants as constants
import json
import logging


def display_version():
    print(f"{gogdl_version}")


def get_game_ids(arguments, api_handler):
    """List user's GOG games with full details"""
    logger = logging.getLogger("GOGDL-GAME-IDS")
    try:
        # Check if we have valid credentials first
        credentials = api_handler.auth_manager.get_credentials()
        if not credentials:
            logger.error("No valid credentials found. Please authenticate first.")
            print(json.dumps([]))  # Return empty array instead of error object
            return

        logger.info("Fetching user's game library...")
        logger.debug(f"Using access token: {credentials.get('access_token', '')[:20]}...")

        # Use the same endpoint as does_user_own - it just returns owned game IDs
        response = api_handler.session.get(f'{constants.GOG_EMBED}/user/data/games')

        if not response.ok:
            logger.error(f"Failed to fetch user data - HTTP {response.status_code}")
            print(json.dumps([]))  # Return empty array instead of error object
            return

        user_data = response.json()
        owned_games = user_data.get('owned', [])
        if arguments.pretty:
            print(json.dumps(owned_games, indent=2))
        else:
            print(json.dumps(owned_games))
    except Exception as e:
        logger.error(f"List command failed: {e}")
        import traceback
        logger.error(traceback.format_exc())
        # Return empty array on error so Kotlin can parse it
        print(json.dumps([]))

def get_game_details(arguments, api_handler):
    """Fetch full details for a single game by ID"""
    logger = logging.getLogger("GOGDL-GAME-DETAILS")
    try:
        game_id = arguments.game_id
        if(not game_id):
            logger.error("No game ID provided!")
            print(json.dumps({}))
            return
         # Check if we have valid credentials first
        logger.info(f"Fetching details for game ID: {game_id}")

        # Get full game info with expanded data
        game_info = api_handler.get_item_data(game_id, expanded=['downloads', 'description', 'screenshots'])

        if game_info:
            logger.info(f"Game {game_id} API response keys: {list(game_info.keys())}")
            # Extract image URLs and ensure they have protocol
            logo2x = game_info.get('images', {}).get('logo2x', '')
            logo = game_info.get('images', {}).get('logo', '')
            icon = game_info.get('images', {}).get('icon', '')

            # Add https: protocol if missing
            if logo2x and logo2x.startswith('//'):
                logo2x = 'https:' + logo2x
            if logo and logo.startswith('//'):
                logo = 'https:' + logo
            if icon and icon.startswith('//'):
                icon = 'https:' + icon

            # Extract download size from first installer
            download_size = 0
            downloads = game_info.get('downloads', {})
            installers = downloads.get('installers', [])
            if installers and len(installers) > 0:
                download_size = installers[0].get('total_size', 0)

            # Extract relevant fields
            game_entry = {
                "id": game_id,
                "title": game_info.get('title', 'Unknown'),
                "slug": game_info.get('slug', ''),
                "imageUrl": logo2x or logo,
                "iconUrl": icon,
                "developer": game_info.get('developers', [{}])[0].get('name', '') if game_info.get('developers') else '',
                "publisher": game_info.get('publisher', {}).get('name', '') if isinstance(game_info.get('publisher'), dict) else game_info.get('publisher', ''),
                "genres": [g.get('name', '') if isinstance(g, dict) else str(g) for g in game_info.get('genres', [])],
                "languages": list(game_info.get('languages', {}).keys()),
                "description": game_info.get('description', {}).get('lead', '') if isinstance(game_info.get('description'), dict) else '',
                "releaseDate": game_info.get('release_date', ''),
                "downloadSize": download_size
            }
            # Output as JSON
            if arguments.pretty:
                print(json.dumps(game_entry, indent=2))
            else:
                print(json.dumps(game_entry))
        else:
            logger.warning(f"Failed to get details for game {game_id} - API returned None")
            print(json.dumps({}))

    except Exception as e:
        logger.error(f"Get game details command failed: {e}")
        import traceback
        logger.error(traceback.format_exc())
        # Return empty object on error so Kotlin can parse it
        print(json.dumps({}))

def handle_list(arguments, api_handler):
    """List user's GOG games with full details"""
    logger = logging.getLogger("GOGDL-LIST")

    try:
        # Check if we have valid credentials first
        credentials = api_handler.auth_manager.get_credentials()
        if not credentials:
            logger.error("No valid credentials found. Please authenticate first.")
            print(json.dumps([]))  # Return empty array instead of error object
            return

        logger.info("Fetching user's game library...")
        logger.debug(f"Using access token: {credentials.get('access_token', '')[:20]}...")

        # Use the same endpoint as does_user_own - it just returns owned game IDs
        response = api_handler.session.get(f'{constants.GOG_EMBED}/user/data/games')

        if not response.ok:
            logger.error(f"Failed to fetch user data - HTTP {response.status_code}")
            print(json.dumps([]))  # Return empty array instead of error object
            return

        user_data = response.json()
        owned_games = user_data.get('owned', [])
        logger.info(f"Found {len(owned_games)} games in library")

        # Fetch full details for each game
        games_list = []
        for index, game_id in enumerate(owned_games, 1):
            try:
                logger.info(f"Fetching details for game {index}/{len(owned_games)}: {game_id}")

                # Get full game info with expanded data
                game_info = api_handler.get_item_data(game_id, expanded=['downloads', 'description', 'screenshots', 'videos'])

                # Log what we got back
                if game_info:
                    logger.info(f"Game {game_id} API response keys: {list(game_info.keys())}")
                    logger.debug(f"Game {game_id} has developers: {'developers' in game_info}")
                    logger.debug(f"Game {game_id} has publisher: {'publisher' in game_info}")
                    logger.debug(f"Game {game_id} has genres: {'genres' in game_info}")

                if game_info:
                    # Extract image URLs and ensure they have protocol
                    logo2x = game_info.get('images', {}).get('logo2x', '')
                    logo = game_info.get('images', {}).get('logo', '')
                    icon = game_info.get('images', {}).get('icon', '')

                    # Add https: protocol if missing
                    if logo2x and logo2x.startswith('//'):
                        logo2x = 'https:' + logo2x
                    if logo and logo.startswith('//'):
                        logo = 'https:' + logo
                    if icon and icon.startswith('//'):
                        icon = 'https:' + icon

                    # Extract download size from first installer
                    download_size = 0
                    downloads = game_info.get('downloads', {})
                    installers = downloads.get('installers', [])
                    if installers and len(installers) > 0:
                        download_size = installers[0].get('total_size', 0)

                    # Extract relevant fields
                    game_entry = {
                        "id": game_id,
                        "title": game_info.get('title', 'Unknown'),
                        "slug": game_info.get('slug', ''),
                        "imageUrl": logo2x or logo,
                        "iconUrl": icon,
                        "developer": game_info.get('developers', [{}])[0].get('name', '') if game_info.get('developers') else '',
                        "publisher": game_info.get('publisher', {}).get('name', '') if isinstance(game_info.get('publisher'), dict) else game_info.get('publisher', ''),
                        "genres": [g.get('name', '') if isinstance(g, dict) else str(g) for g in game_info.get('genres', [])],
                        "languages": list(game_info.get('languages', {}).keys()),
                        "description": game_info.get('description', {}).get('lead', '') if isinstance(game_info.get('description'), dict) else '',
                        "releaseDate": game_info.get('release_date', ''),
                        "downloadSize": download_size
                    }
                    games_list.append(game_entry)
                    logger.debug(f"  ✓ {game_entry['title']}")
                else:
                    logger.warning(f"Failed to get details for game {game_id} - API returned None")
                    # Add minimal entry so we don't lose the game ID
                    games_list.append({
                        "id": game_id,
                        "title": f"Game {game_id}",
                        "slug": "",
                        "imageUrl": "",
                        "iconUrl": "",
                        "developer": "",
                        "publisher": "",
                        "genres": [],
                        "languages": [],
                        "description": "",
                        "releaseDate": ""
                    })

                # Small delay to avoid rate limiting (100ms between requests)
                if index < len(owned_games):
                    import time
                    time.sleep(0.1)

            except Exception as e:
                logger.error(f"Error fetching details for game {game_id}: {e}")
                import traceback
                logger.debug(traceback.format_exc())
                # Add minimal entry on error
                games_list.append({
                    "id": game_id,
                    "title": f"Game {game_id}",
                    "slug": "",
                    "imageUrl": "",
                    "iconUrl": "",
                    "developer": "",
                    "publisher": "",
                    "genres": [],
                    "languages": [],
                    "description": "",
                    "releaseDate": ""
                })

        logger.info(f"Successfully fetched details for {len(games_list)} games")

        # Output as JSON array (always return array, never error object)
        if arguments.pretty:
            print(json.dumps(games_list, indent=2))
        else:
            print(json.dumps(games_list))

    except Exception as e:
        logger.error(f"List command failed: {e}")
        import traceback
        logger.error(traceback.format_exc())
        # Return empty array on error so Kotlin can parse it
        print(json.dumps([]))


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

    # Handle list command
    if arguments.command == "list":
        switcher["list"] = lambda: handle_list(arguments, api_handler)

    # Handle game-ids command
    if arguments.command == "game-ids":
        switcher["game-ids"] = lambda: get_game_ids(arguments, api_handler)
    # Handle game-details command
    if arguments.command == "game-details":
        switcher["game-details"] = lambda: get_game_details(arguments, api_handler)
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
