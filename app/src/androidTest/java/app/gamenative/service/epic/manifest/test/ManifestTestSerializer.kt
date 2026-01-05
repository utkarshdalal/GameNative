package app.gamenative.service.epic.manifest.test

import app.gamenative.service.epic.manifest.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes parsed manifest to JSON for testing and validation
 */
object ManifestTestSerializer {

    /**
     * Serialize entire manifest to JSON for comparison
     */
    fun serializeManifest(manifest: EpicManifest): JSONObject {
        return JSONObject().apply {
            put("version", manifest.version)
            put("headerSize", manifest.headerSize)
            put("isCompressed", manifest.isCompressed)

            manifest.meta?.let { meta ->
                put("meta", serializeMeta(meta))
            }

            manifest.chunkDataList?.let { cdl ->
                put("chunkDataList", serializeChunkDataList(cdl))
            }

            manifest.fileManifestList?.let { fml ->
                put("fileManifestList", serializeFileManifestList(fml))
            }

            manifest.customFields?.let { cf ->
                put("customFields", serializeCustomFields(cf))
            }
        }
    }

    /**
     * Serialize manifest metadata
     */
    private fun serializeMeta(meta: ManifestMeta): JSONObject {
        return JSONObject().apply {
            put("dataVersion", meta.dataVersion.toInt())
            put("featureLevel", meta.featureLevel)
            put("isFileData", meta.isFileData)
            put("appId", meta.appId)
            put("appName", meta.appName)
            put("buildVersion", meta.buildVersion)
            put("launchExe", meta.launchExe)
            put("launchCommand", meta.launchCommand)
            put("prereqIds", JSONArray(meta.prereqIds))
            put("prereqName", meta.prereqName)
            put("prereqPath", meta.prereqPath)
            put("prereqArgs", meta.prereqArgs)
            put("buildId", meta.buildId)
            put("uninstallActionPath", meta.uninstallActionPath)
            put("uninstallActionArgs", meta.uninstallActionArgs)
        }
    }

    /**
     * Serialize chunk data list
     */
    private fun serializeChunkDataList(cdl: ChunkDataList): JSONObject {
        return JSONObject().apply {
            put("version", cdl.version.toInt())
            put("count", cdl.count)
            put("chunks", JSONArray().apply {
                cdl.elements.forEach { chunk ->
                    put(serializeChunkInfo(chunk))
                }
            })
        }
    }

    /**
     * Serialize chunk info
     */
    private fun serializeChunkInfo(chunk: ChunkInfo): JSONObject {
        return JSONObject().apply {
            put("guid", JSONArray(chunk.guid.map { it }))
            put("guidStr", chunk.guidStr)
            put("hash", chunk.hash.toString())
            put("shaHash", bytesToHex(chunk.shaHash))
            put("groupNum", chunk.groupNum)
            put("windowSize", chunk.windowSize)
            put("fileSize", chunk.fileSize)
        }
    }

    /**
     * Serialize file manifest list
     */
    private fun serializeFileManifestList(fml: FileManifestList): JSONObject {
        return JSONObject().apply {
            put("version", fml.version.toInt())
            put("count", fml.count)
            put("files", JSONArray().apply {
                fml.elements.forEach { file ->
                    put(serializeFileManifest(file))
                }
            })
        }
    }

    /**
     * Serialize file manifest
     */
    private fun serializeFileManifest(fm: FileManifest): JSONObject {
        return JSONObject().apply {
            put("filename", fm.filename)
            put("symlinkTarget", fm.symlinkTarget)
            put("hash", bytesToHex(fm.hash))
            put("flags", fm.flags)
            put("isReadOnly", fm.isReadOnly)
            put("isCompressed", fm.isCompressed)
            put("isExecutable", fm.isExecutable)
            put("installTags", JSONArray(fm.installTags))
            put("fileSize", fm.fileSize)
            put("hashMd5", bytesToHex(fm.hashMd5))
            put("mimeType", fm.mimeType)
            put("hashSha256", bytesToHex(fm.hashSha256))
            put("chunkParts", JSONArray().apply {
                fm.chunkParts.forEach { part ->
                    put(serializeChunkPart(part))
                }
            })
        }
    }

    /**
     * Serialize chunk part
     */
    private fun serializeChunkPart(part: ChunkPart): JSONObject {
        return JSONObject().apply {
            put("guid", JSONArray(part.guid.map { it }))
            put("guidStr", part.guidStr)
            put("offset", part.offset)
            put("size", part.size)
            put("fileOffset", part.fileOffset)
        }
    }

    /**
     * Serialize custom fields
     */
    private fun serializeCustomFields(cf: CustomFields): JSONObject {
        return JSONObject() // Empty for now, add fields as needed
    }

    /**
     * Convert byte array to hex string
     */
    private fun bytesToHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Create a summary for quick comparison
     */
    fun createManifestSummary(manifest: EpicManifest): JSONObject {
        return JSONObject().apply {
            put("version", manifest.version)
            put("appName", manifest.meta?.appName)
            put("buildVersion", manifest.meta?.buildVersion)
            put("chunkCount", manifest.chunkDataList?.elements?.size ?: 0)
            put("fileCount", manifest.fileManifestList?.elements?.size ?: 0)
            put("totalChunks", manifest.chunkDataList?.elements?.size ?: 0)
            put("totalFiles", manifest.fileManifestList?.elements?.size ?: 0)
            put("downloadSize", ManifestUtils.getTotalDownloadSize(manifest))
            put("installedSize", ManifestUtils.getTotalInstalledSize(manifest))

            // First 5 files as sample
            put("sampleFiles", JSONArray().apply {
                manifest.fileManifestList?.elements?.take(5)?.forEach { file ->
                    put(JSONObject().apply {
                        put("filename", file.filename)
                        put("size", file.fileSize)
                        put("hash", bytesToHex(file.hash))
                        put("chunkParts", file.chunkParts.size)
                    })
                }
            })

            // First 5 chunks as sample
            put("sampleChunks", JSONArray().apply {
                manifest.chunkDataList?.elements?.take(5)?.forEach { chunk ->
                    put(JSONObject().apply {
                        put("guid", chunk.guidStr)
                        put("hash", chunk.hash.toString())
                        put("size", chunk.fileSize)
                        put("groupNum", chunk.groupNum)
                    })
                }
            })
        }
    }
}
