set -eu
repository="$1"
exec "$repository/tools/provision-build-arm64x-wine-bridge.sh" "$repository"
