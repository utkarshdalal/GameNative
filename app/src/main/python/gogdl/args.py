"""
Android-compatible argument parser for GOGDL
"""

import argparse
from gogdl import constants

def init_parser():
    """Initialize argument parser with Android-compatible defaults"""

    parser = argparse.ArgumentParser(
        description='Android-compatible GOG downloader',
        formatter_class=argparse.RawDescriptionHelpFormatter
    )

    parser.add_argument(
        '--auth-config-path',
        type=str,
        default=f"{constants.ANDROID_DATA_DIR}/gog_auth.json",
        help='Path to authentication config file'
    )

    parser.add_argument(
        '--display-version',
        action='store_true',
        help='Display version information'
    )

    subparsers = parser.add_subparsers(dest='command', help='Available commands')

    # Auth command
    auth_parser = subparsers.add_parser('auth', help='Authenticate with GOG or get existing credentials')
    auth_parser.add_argument('--code', type=str, help='Authorization code from GOG (optional - if not provided, returns existing credentials)')

    # Download command
    download_parser = subparsers.add_parser('download', help='Download a game')
    download_parser.add_argument('id', type=str, help='Game ID to download')
    download_parser.add_argument('--path', type=str, default=constants.ANDROID_GAMES_DIR, help='Download path')
    download_parser.add_argument('--platform', type=str, default='windows', choices=['windows', 'linux'], help='Platform')
    download_parser.add_argument('--branch', type=str, help='Game branch to download')
    download_parser.add_argument('--skip-dlcs', dest='dlcs', action='store_false', help='Skip DLC downloads')
    download_parser.add_argument('--with-dlcs', dest='dlcs', action='store_true', help='Download DLCs')
    download_parser.add_argument('--dlcs', dest='dlcs_list', default=[], help='List of dlc ids to download (separated by comma)')
    download_parser.add_argument('--dlc-only', dest='dlc_only', action='store_true', help='Download only DLC')

    download_parser.add_argument('--lang', type=str, default='en-US', help='Language for the download')
    download_parser.add_argument('--max-workers', dest='workers_count', type=int, default=2, help='Number of download workers')
    download_parser.add_argument('--support', dest='support_path', type=str, help='Support files path')
    download_parser.add_argument('--password', dest='password', help='Password to access other branches')
    download_parser.add_argument('--force-gen', choices=['1', '2'], dest='force_generation', help='Force specific manifest generation (FOR DEBUGGING)')
    download_parser.add_argument('--build', '-b', dest='build', help='Specify buildId')

    # Info command (same as heroic-gogdl calculate_size_parser)
    info_parser = subparsers.add_parser('info', help='Calculates estimated download size and list of DLCs')
    info_parser.add_argument('--with-dlcs', dest='dlcs', action='store_true', help='Should download all dlcs')
    info_parser.add_argument('--skip-dlcs', dest='dlcs', action='store_false', help='Should skip all dlcs')
    info_parser.add_argument('--dlcs', dest='dlcs_list', help='Comma separated list of dlc ids to download')
    info_parser.add_argument('--dlc-only', dest='dlc_only', action='store_true', help='Download only DLC')
    info_parser.add_argument('id', help='Game ID')
    info_parser.add_argument('--platform', '--os', dest='platform', help='Target operating system', choices=['windows', 'linux'], default='windows')
    info_parser.add_argument('--build', '-b', dest='build', help='Specify buildId')
    info_parser.add_argument('--branch', dest='branch', help='Choose build branch to use')
    info_parser.add_argument('--password', dest='password', help='Password to access other branches')
    info_parser.add_argument('--force-gen', choices=['1', '2'], dest='force_generation', help='Force specific manifest generation (FOR DEBUGGING)')
    info_parser.add_argument('--lang', '-l', dest='lang', help='Specify game language', default='en-US')
    info_parser.add_argument('--max-workers', dest='workers_count', type=int, default=2, help='Number of download workers')

    # Repair command
    repair_parser = subparsers.add_parser('repair', help='Repair/verify game files')
    repair_parser.add_argument('id', type=str, help='Game ID to repair')
    repair_parser.add_argument('--path', type=str, default=constants.ANDROID_GAMES_DIR, help='Game path')
    repair_parser.add_argument('--platform', type=str, default='windows', choices=['windows', 'linux'], help='Platform')
    repair_parser.add_argument('--password', dest='password', help='Password to access other branches')
    repair_parser.add_argument('--force-gen', choices=['1', '2'], dest='force_generation', help='Force specific manifest generation (FOR DEBUGGING)')
    repair_parser.add_argument('--build', '-b', dest='build', help='Specify buildId')
    repair_parser.add_argument('--branch', dest='branch', help='Choose build branch to use')

    # Save sync command
    save_parser = subparsers.add_parser('save-sync', help='Sync game saves')
    save_parser.add_argument('path', help='Path to sync files')
    save_parser.add_argument('--dirname', help='Cloud save directory name')
    save_parser.add_argument('--timestamp', type=float, default=0.0, help='Last sync timestamp')
    save_parser.add_argument('--prefered-action', choices=['upload', 'download', 'none'], help='Preferred sync action')

    # List command
    list_parser = subparsers.add_parser('list', help='List user\'s GOG games')
    list_parser.add_argument('--pretty', action='store_true', help='Pretty print JSON output')

    # Game IDs command
    game_ids_parser = subparsers.add_parser('game-ids', help='List user\'s GOG game IDs only')
    game_ids_parser.add_argument('--pretty', action='store_true', help='Pretty print JSON output')

    # Game details command
    game_details_parser = subparsers.add_parser('game-details', help='Get full details for a single game')
    game_details_parser.add_argument('game_id', type=str, help='Game ID to fetch details for')
    game_details_parser.add_argument('--pretty', action='store_true', help='Pretty print JSON output')

    return parser.parse_known_args()
