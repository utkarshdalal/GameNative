#!/usr/bin/env python3
"""
Test script to compare Kotlin and Python manifest parsing implementations.
This script parses a manifest using legendary's Python implementation and outputs
JSON that can be compared with the Kotlin implementation.
"""

import json
import sys
from pathlib import Path

# Add legendary to path
sys.path.insert(0, str(Path(__file__).parent.parent / "python"))

from legendary.models.manifest import Manifest
from legendary.models.json_manifest import JSONManifest


def bytes_to_hex(data):
    """Convert bytes to hex string"""
    if not data:
        return ""
    return data.hex()


def serialize_manifest(manifest):
    """Serialize manifest to JSON for comparison"""
    return {
        "version": manifest.version,
        "headerSize": manifest.header_size,
        "isCompressed": manifest.compressed,
        "meta": serialize_meta(manifest.meta) if manifest.meta else None,
        "chunkDataList": serialize_chunk_data_list(manifest.chunk_data_list) if manifest.chunk_data_list else None,
        "fileManifestList": serialize_file_manifest_list(manifest.file_manifest_list) if manifest.file_manifest_list else None,
        "customFields": serialize_custom_fields(manifest.custom_fields) if manifest.custom_fields else None
    }


def serialize_meta(meta):
    """Serialize manifest metadata"""
    return {
        "dataVersion": meta.data_version,
        "featureLevel": meta.feature_level,
        "isFileData": meta.is_file_data,
        "appId": meta.app_id,
        "appName": meta.app_name,
        "buildVersion": meta.build_version,
        "launchExe": meta.launch_exe,
        "launchCommand": meta.launch_command,
        "prereqIds": meta.prereq_ids,
        "prereqName": meta.prereq_name,
        "prereqPath": meta.prereq_path,
        "prereqArgs": meta.prereq_args,
        "buildId": meta.build_id,
        "uninstallActionPath": meta.uninstall_action_path,
        "uninstallActionArgs": meta.uninstall_action_args
    }


def serialize_chunk_data_list(cdl):
    """Serialize chunk data list"""
    return {
        "version": cdl.version,
        "count": cdl.count,
        "chunks": [serialize_chunk_info(chunk) for chunk in cdl.elements]
    }


def serialize_chunk_info(chunk):
    """Serialize chunk info"""
    return {
        "guid": list(chunk.guid),
        "guidStr": chunk.guid_str,
        "hash": str(chunk.hash),
        "shaHash": bytes_to_hex(chunk.sha_hash),
        "groupNum": chunk.group_num,
        "windowSize": chunk.window_size,
        "fileSize": chunk.file_size
    }


def serialize_file_manifest_list(fml):
    """Serialize file manifest list"""
    return {
        "version": fml.version,
        "count": fml.count,
        "files": [serialize_file_manifest(fm) for fm in fml.elements]
    }


def serialize_file_manifest(fm):
    """Serialize file manifest"""
    return {
        "filename": fm.filename,
        "symlinkTarget": fm.symlink_target,
        "hash": bytes_to_hex(fm.hash),
        "flags": fm.flags,
        "isReadOnly": fm.read_only,
        "isCompressed": fm.compressed,
        "isExecutable": fm.executable,
        "installTags": fm.install_tags,
        "fileSize": fm.file_size,
        "hashMd5": bytes_to_hex(fm.hash_md5),
        "mimeType": fm.mime_type,
        "hashSha256": bytes_to_hex(fm.hash_sha256),
        "chunkParts": [serialize_chunk_part(part) for part in fm.chunk_parts]
    }


def serialize_chunk_part(part):
    """Serialize chunk part"""
    return {
        "guid": list(part.guid),
        "guidStr": part.guid_str,
        "offset": part.offset,
        "size": part.size,
        "fileOffset": part.file_offset
    }


def serialize_custom_fields(cf):
    """Serialize custom fields"""
    return cf._dict if hasattr(cf, '_dict') else {}


def create_manifest_summary(manifest):
    """Create a summary for quick comparison"""
    total_download_size = sum(chunk.file_size for chunk in manifest.chunk_data_list.elements) if manifest.chunk_data_list else 0
    total_installed_size = sum(fm.file_size for fm in manifest.file_manifest_list.elements) if manifest.file_manifest_list else 0

    return {
        "version": manifest.version,
        "appName": manifest.meta.app_name if manifest.meta else None,
        "buildVersion": manifest.meta.build_version if manifest.meta else None,
        "chunkCount": len(manifest.chunk_data_list.elements) if manifest.chunk_data_list else 0,
        "fileCount": len(manifest.file_manifest_list.elements) if manifest.file_manifest_list else 0,
        "totalChunks": len(manifest.chunk_data_list.elements) if manifest.chunk_data_list else 0,
        "totalFiles": len(manifest.file_manifest_list.elements) if manifest.file_manifest_list else 0,
        "downloadSize": total_download_size,
        "installedSize": total_installed_size,
        "sampleFiles": [
            {
                "filename": fm.filename,
                "size": fm.file_size,
                "hash": bytes_to_hex(fm.hash),
                "chunkParts": len(fm.chunk_parts)
            }
            for fm in (manifest.file_manifest_list.elements[:5] if manifest.file_manifest_list else [])
        ],
        "sampleChunks": [
            {
                "guid": chunk.guid_str,
                "hash": str(chunk.hash),
                "size": chunk.file_size,
                "groupNum": chunk.group_num
            }
            for chunk in (manifest.chunk_data_list.elements[:5] if manifest.chunk_data_list else [])
        ]
    }


def parse_and_serialize(manifest_path, output_format="full"):
    """Parse manifest and output JSON"""
    with open(manifest_path, 'rb') as f:
        data = f.read()

    # Auto-detect format
    if data[:4] == b'\x0c\xc0\xbe\x44':  # Binary manifest magic (little endian)
        manifest = Manifest.read_all(data)
    else:
        manifest = JSONManifest.read_all(data)

    if output_format == "summary":
        return create_manifest_summary(manifest)
    else:
        return serialize_manifest(manifest)


def main():
    if len(sys.argv) < 2:
        print("Usage: python manifest_test_python.py <manifest_file> [full|summary]")
        sys.exit(1)

    manifest_path = sys.argv[1]
    output_format = sys.argv[2] if len(sys.argv) > 2 else "summary"

    try:
        result = parse_and_serialize(manifest_path, output_format)
        print(json.dumps(result, indent=2))
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
