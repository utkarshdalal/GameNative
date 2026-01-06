import os
import glob
import json
import logging
from sys import exit
from gogdl import constants
import requests


def get_info(args, unknown_args):
    logger = logging.getLogger("IMPORT")
    path = args.path
    if not os.path.exists(path):
        logger.error("Provided path is invalid!")
        exit(1)
    game_details = load_game_details(path)

    info_file = game_details[0]
    build_id_file = game_details[1]
    platform = game_details[2]
    with_dlcs = game_details[3]
    build_id = ""
    installed_language = None
    info = {}

    # Initialize variables to safe defaults to prevent UnboundLocalError
    title = None
    game_id = None
    version_name = None

    if platform != "linux":
        if not info_file:
            logger.error("Error importing, no info file found")
            print("Error importing, no info file")
            return
        f = open(info_file, "r")
        info = json.loads(f.read())
        f.close()

        title = info["name"]
        game_id = info["rootGameId"]
        build_id = info.get("buildId")
        if "languages" in info:
            installed_language = info["languages"][0]
        elif "language" in info:
            installed_language = info["language"]
        else:
            installed_language = "en-US"
        if build_id_file:
            f = open(build_id_file, "r")
            build = json.loads(f.read())
            f.close()
            build_id = build.get("buildId")

        version_name = build_id
        if build_id and platform != "linux":
            # Get version name
            try:
                builds_res = requests.get(
                    f"{constants.GOG_CONTENT_SYSTEM}/products/{game_id}/os/{platform}/builds?generation=2",
                    headers={
                        "User-Agent": "GOGGalaxyCommunicationService/2.0.4.164 (Windows_32bit)"
                    },
                    timeout=30
                )
                builds_res.raise_for_status()
                builds = builds_res.json()
                target_build = builds["items"][0]
                for build in builds["items"]:
                    if build["build_id"] == build_id:
                        target_build = build
                        break
                version_name = target_build["version_name"]
            except requests.exceptions.Timeout:
                logger.warning(f"Timeout fetching build info for game {game_id}, using build_id as version")
                version_name = build_id
            except requests.exceptions.RequestException as e:
                logger.warning(f"Error fetching build info for game {game_id}: {e}, using build_id as version")
                version_name = build_id
            except (KeyError, IndexError, json.JSONDecodeError) as e:
                logger.warning(f"Error parsing build info for game {game_id}: {e}, using build_id as version")
                version_name = build_id
    if platform == "linux" and os.path.exists(os.path.join(path, "gameinfo")):
        # Linux version installed using installer
        gameinfo_file = open(os.path.join(path, "gameinfo"), "r")
        data = gameinfo_file.read()
        lines = data.split("\n")
        title = lines[0]
        version_name = lines[1]

        if not installed_language:
            installed_language = lines[3]
        if len(lines) > 4:
            game_id = lines[4]
            build_id = lines[6]
        else:
            game_id = None
            build_id = None

    # Validate that metadata was successfully loaded
    if title is None or game_id is None or version_name is None:
        logger.error(
            f"Failed to load game metadata from path: {path}. "
            f"Platform: {platform}, gameinfo exists: {os.path.exists(os.path.join(path, 'gameinfo'))}"
        )
        print(f"Error: Unable to load game metadata. Missing gameinfo file or invalid installation.")
        return

    print(
        json.dumps(
            {
                "appName": game_id,
                "buildId": build_id,
                "title": title,
                "tasks": info["playTasks"] if info and info.get("playTasks") else None,
                "installedLanguage": installed_language,
                "dlcs": with_dlcs,
                "platform": platform,
                "versionName": version_name,
            }
        )
    )


def load_game_details(path):
    base_path = path
    found = glob.glob(os.path.join(path, "goggame-*.info"))
    build_id = glob.glob(os.path.join(path, "goggame-*.id"))
    platform = "windows"
    if not found:
        base_path = os.path.join(path, "Contents", "Resources")
        found = glob.glob(os.path.join(path, "Contents", "Resources", "goggame-*.info"))
        build_id = glob.glob(
            os.path.join(path, "Contents", "Resources", "goggame-*.id")
        )
        platform = "osx"
    if not found:
        base_path = os.path.join(path, "game")
        found = glob.glob(os.path.join(path, "game", "goggame-*.info"))
        build_id = glob.glob(os.path.join(path, "game", "goggame-*.id"))
        platform = "linux"
    if not found:
        if os.path.exists(os.path.join(path, "gameinfo")):
            return (None, None, "linux", [])

    root_id = None
    # Array of DLC game ids
    dlcs = []
    for info in found:
        with open(info) as info_file:
            data = json.load(info_file)
            if not root_id:
                root_id = data.get("rootGameId")
            if data["gameId"] == root_id:
                continue

            dlcs.append(data["gameId"])

    return (os.path.join(base_path, f"goggame-{root_id}.info"), os.path.join(base_path, f"goggame-{root_id}.id") if build_id else None, platform, dlcs)
