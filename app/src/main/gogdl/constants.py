"""
Android-compatible constants for GOGDL
"""

import os

# GOG API endpoints (matching original heroic-gogdl)
GOG_CDN = "https://gog-cdn-fastly.gog.com"
GOG_CONTENT_SYSTEM = "https://content-system.gog.com"
GOG_EMBED = "https://embed.gog.com"
GOG_AUTH = "https://auth.gog.com"
GOG_API = "https://api.gog.com"
GOG_CLOUDSTORAGE = "https://cloudstorage.gog.com"
DEPENDENCIES_URL = "https://content-system.gog.com/dependencies/repository?generation=2"
DEPENDENCIES_V1_URL = "https://content-system.gog.com/redists/repository"

NON_NATIVE_SEP = "\\" if os.sep == "/" else "/"

# Android-specific paths
ANDROID_DATA_DIR = "/data/user/0/app.gamenative/files"
ANDROID_GAMES_DIR = "/data/data/app.gamenative/storage/gog_games"
CONFIG_DIR = ANDROID_DATA_DIR
MANIFESTS_DIR = os.path.join(CONFIG_DIR, "manifests")

# Download settings optimized for Android
DEFAULT_CHUNK_SIZE = 1024 * 1024  # 1MB chunks for mobile
MAX_CONCURRENT_DOWNLOADS = 2      # Conservative for mobile
CONNECTION_TIMEOUT = 30           # 30 second timeout
READ_TIMEOUT = 60                # 1 minute read timeout
