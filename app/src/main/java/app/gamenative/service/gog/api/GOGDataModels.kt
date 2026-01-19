package app.gamenative.service.gog.api

import org.json.JSONObject

/**
 * Response from GOG builds API
 */
data class BuildsResponse(
    val totalCount: Int,
    val count: Int,
    val items: List<GOGBuild>
) {
    companion object {
        fun fromJson(json: JSONObject): BuildsResponse {
            val itemsArray = json.optJSONArray("items")
            val items = mutableListOf<GOGBuild>()

            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    items.add(GOGBuild.fromJson(itemsArray.getJSONObject(i)))
                }
            }

            return BuildsResponse(
                totalCount = json.optInt("total_count", 0),
                count = json.optInt("count", 0),
                items = items
            )
        }
    }
}


data class DependencyRepository(
    val repositoryManifest: String,
    val generation: Int,
    val buildId: String
)   { companion object {
        fun fromJson(json: JSONObject): DependencyRepository {
            return DependencyRepository(
                repositoryManifest = json.optString("repository_manifest", ""),
                buildId = json.optString("build_id", ""),
                generation = json.optInt("generation", 2),
            )
        }
    }
}

/**
 * Individual build metadata
 */
data class GOGBuild(
    val buildId: String,
    val productId: String,
    val platform: String,
    val generation: Int,  // 1 = legacy, 2 = modern
    val versionName: String,
    val branch: String?,
    val link: String,  // Manifest download URL
    val legacyBuildId: String?
) {
    companion object {
        fun fromJson(json: JSONObject): GOGBuild {
            return GOGBuild(
                buildId = json.optString("build_id", ""),
                productId = json.optString("product_id", ""),
                platform = json.optString("os", "windows"),
                generation = json.optInt("generation", 2),
                versionName = json.optString("version_name", ""),
                branch = if (json.has("branch") && !json.isNull("branch")) json.getString("branch") else null,
                link = json.optString("link", ""),
                legacyBuildId = if (json.has("legacy_build_id") && !json.isNull("legacy_build_id")) json.getString("legacy_build_id") else null
            )
        }
    }
}

data class Executable(
    val arguments: String?,
    val path: String
)

data class DependencyDepot(
    val compressedSize: Long,
    val dependencyId: String,
    val executable: Executable?,
    val isInternal: Boolean,
    val languages: List<String>,
    val manifest: String,
    val osBitness: List<String>?,
    val readableName: String,
    val signature: String,
    val size: Long,
)
// repository Manifest is a URL that will give back a compressed zlib JSON
// generation is always 2 since we always use Generation 2 in the URL
data class GOGDependencyManifestMeta(
    val depots: List<DependencyDepot>,
) {
    companion object {
        fun fromJson(json: JSONObject): GOGDependencyManifestMeta {
            val depotsArray = json.optJSONArray("depots")
            val depots = mutableListOf<DependencyDepot>()

            if(depotsArray != null) {
                for (i in 0 until depotsArray.length()) {
                    val depotObj = depotsArray.getJSONObject(i)

                    // Parse Languages for this depot
                    val languagesArray = depotObj.optJSONArray("languages")
                    val languages = mutableListOf<String>()
                    if (languagesArray != null) {
                        for (j in 0 until languagesArray.length()) {
                            languages.add(languagesArray.getString(j))
                        }
                    }

                    // Parse bitness for this depot
                    val bitnessArray = depotObj.optJSONArray("osBitness")
                    val osBitness = if (bitnessArray != null) {
                        val list = mutableListOf<String>()
                        for (j in 0 until bitnessArray.length()) {
                            list.add(bitnessArray.getString(j))
                        }
                        list
                    } else null

                    // Parse executable for this depot
                    val executableObj = depotObj.optJSONObject("executable")
                    val executable = if (executableObj != null) {
                        Executable(
                            arguments = if (executableObj.has("arguments") && !executableObj.isNull("arguments"))
                                executableObj.getString("arguments") else null,
                            path = executableObj.optString("path", "")
                        )
                    } else null

                    val depot = DependencyDepot (
                        compressedSize = depotObj.optLong("compressedSize", 0),
                        dependencyId = depotObj.optString("dependencyId", ""),
                        executable = executable,
                        languages = languages,
                        osBitness = osBitness,
                        isInternal = depotObj.optBoolean("internal", false),
                        manifest = depotObj.optString("manifest", ""),
                        readableName = depotObj.optString("readableName", ""),
                        signature = depotObj.optString("signature", ""),
                        size = depotObj.optLong("size", 0),
                    )
                    depots.add(depot)
                }
            }

            return GOGDependencyManifestMeta(depots = depots)
        }
    }
}

/**
 * Main manifest metadata
 */
data class GOGManifestMeta(
    val baseProductId: String,
    val installDirectory: String,
    val depots: List<Depot>,
    val dependencies: List<String>,
    val products: List<Product>
) {
    companion object {
        fun fromJson(json: JSONObject): GOGManifestMeta {
            val depotsArray = json.optJSONArray("depots")
            val depots = mutableListOf<Depot>()

            if (depotsArray != null) {
                for (i in 0 until depotsArray.length()) {
                    depots.add(Depot.fromJson(depotsArray.getJSONObject(i)))
                }
            }

            val dependenciesArray = json.optJSONArray("dependencies")
            val dependencies = mutableListOf<String>()

            if (dependenciesArray != null) {
                for (i in 0 until dependenciesArray.length()) {
                    dependencies.add(dependenciesArray.getString(i))
                }
            }

            val productsArray = json.optJSONArray("products")
            val products = mutableListOf<Product>()

            if (productsArray != null) {
                for (i in 0 until productsArray.length()) {
                    products.add(Product.fromJson(productsArray.getJSONObject(i)))
                }
            }

            return GOGManifestMeta(
                baseProductId = json.optString("baseProductId", ""),
                installDirectory = json.optString("installDirectory", ""),
                depots = depots,
                dependencies = dependencies,
                products = products
            )
        }
    }
}

/**
 * Depot metadata (contains files for specific language/platform)
 */
data class Depot(
    val productId: String,
    val languages: List<String>,
    val manifest: String,  // Hash pointing to depot manifest
    val compressedSize: Long,
    val size: Long,
    val osBitness: List<String>?
) {
    companion object {
        fun fromJson(json: JSONObject): Depot {
            val languagesArray = json.optJSONArray("languages")
            val languages = mutableListOf<String>()

            if (languagesArray != null) {
                for (i in 0 until languagesArray.length()) {
                    languages.add(languagesArray.getString(i))
                }
            }

            val bitnessArray = json.optJSONArray("osBitness")
            val osBitness = if (bitnessArray != null) {
                val list = mutableListOf<String>()
                for (i in 0 until bitnessArray.length()) {
                    list.add(bitnessArray.getString(i))
                }
                list
            } else null

            return Depot(
                productId = json.optString("productId", ""),
                languages = languages,
                manifest = json.optString("manifest", ""),
                compressedSize = json.optLong("compressedSize", 0),
                size = json.optLong("size", 0),
                osBitness = osBitness
            )
        }
    }

    /**
     * Check if this depot matches the target language
     */
    fun matchesLanguage(targetLanguage: String): Boolean {
        return languages.contains("*") || languages.any {
            it.equals(targetLanguage, ignoreCase = true)
        }
    }
}

/**
 * Product metadata (base game or DLC)
 */
data class Product(
    val productId: String,
    val name: String
) {
    companion object {
        fun fromJson(json: JSONObject): Product {
            return Product(
                productId = json.optString("productId", ""),
                name = json.optString("name", "")
            )
        }
    }
}

/**
 * Depot manifest (contains file list)
 */
data class DepotManifest(
    val files: List<DepotFile>,
    val directories: List<DepotDirectory>,
    val links: List<DepotLink>
) {
    companion object {
        fun fromJson(json: JSONObject): DepotManifest {
            val depotObj = json.optJSONObject("depot") ?: json
            val itemsArray = depotObj.optJSONArray("items")

            val files = mutableListOf<DepotFile>()
            val directories = mutableListOf<DepotDirectory>()
            val links = mutableListOf<DepotLink>()

            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.getJSONObject(i)
                    when (item.optString("type", "")) {
                        "DepotFile" -> files.add(DepotFile.fromJson(item))
                        "DepotDirectory" -> directories.add(DepotDirectory.fromJson(item))
                        "DepotLink" -> links.add(DepotLink.fromJson(item))
                    }
                }
            }

            return DepotManifest(
                files = files,
                directories = directories,
                links = links
            )
        }
    }
}

/**
 * File in depot manifest
 */
data class DepotFile(
    val path: String,
    val chunks: List<FileChunk>,
    val md5: String?,
    val sha256: String?,
    val flags: List<String>,
    val productId: String?
) {
    companion object {
        fun fromJson(json: JSONObject): DepotFile {
            val chunksArray = json.optJSONArray("chunks")
            val chunks = mutableListOf<FileChunk>()

            if (chunksArray != null) {
                for (i in 0 until chunksArray.length()) {
                    chunks.add(FileChunk.fromJson(chunksArray.getJSONObject(i)))
                }
            }

            val flagsArray = json.optJSONArray("flags")
            val flags = mutableListOf<String>()

            if (flagsArray != null) {
                for (i in 0 until flagsArray.length()) {
                    flags.add(flagsArray.getString(i))
                }
            }

            return DepotFile(
                path = json.optString("path", "").replace("\\", "/").removePrefix("/"),
                chunks = chunks,
                md5 = if (json.has("md5") && !json.isNull("md5")) json.getString("md5") else null,
                sha256 = if (json.has("sha256") && !json.isNull("sha256")) json.getString("sha256") else null,
                flags = flags,
                productId = if (json.has("productId") && !json.isNull("productId")) json.getString("productId") else null
            )
        }
    }

    /**
     * Check if this is a support file (redistributable)
     */
    fun isSupportFile(): Boolean = flags.contains("support")
}

/**
 * Chunk within a file
 */
data class FileChunk(
    val compressedMd5: String,
    val md5: String,
    val size: Long,
    val compressedSize: Long?
) {
    companion object {
        fun fromJson(json: JSONObject): FileChunk {
            return FileChunk(
                compressedMd5 = json.optString("compressedMd5", ""),
                md5 = json.optString("md5", ""),
                size = json.optLong("size", 0),
                compressedSize = if (json.has("compressedSize") && !json.isNull("compressedSize")) {
                    json.optLong("compressedSize", 0)
                } else {
                    null
                }
            )
        }
    }
}

/**
 * Directory in depot
 */
data class DepotDirectory(
    val path: String
) {
    companion object {
        fun fromJson(json: JSONObject): DepotDirectory {
            return DepotDirectory(
                path = json.optString("path", "").replace("\\", "/").removeSuffix("/")
            )
        }
    }
}

/**
 * Symbolic link in depot
 */
data class DepotLink(
    val path: String,
    val target: String
) {
    companion object {
        fun fromJson(json: JSONObject): DepotLink {
            return DepotLink(
                path = json.optString("path", ""),
                target = json.optString("target", "")
            )
        }
    }
}

/**
 * Secure download links response
 */
data class SecureLinksResponse(
    val urls: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): SecureLinksResponse {
            val urlsArray = json.optJSONArray("urls")
            val urls = mutableListOf<String>()

            if (urlsArray != null) {
                for (i in 0 until urlsArray.length()) {
                    val urlObj = urlsArray.optJSONObject(i)
                    if (urlObj != null) {
                        // GOG returns URL objects with url_format template and parameters
                        // We need to merge them: {base_url}/token=nva={expires_at}... etc.
                        val urlFormat = urlObj.optString("url_format", "")
                        val paramsObj = urlObj.optJSONObject("parameters")

                        if (urlFormat.isNotEmpty() && paramsObj != null) {
                            // Replace all {param} placeholders with actual values
                            var constructedUrl = urlFormat
                            val keys = paramsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = paramsObj.get(key).toString()
                                constructedUrl = constructedUrl.replace("{$key}", value)
                            }

                            // Clean up escaped slashes from JSON
                            constructedUrl = constructedUrl.replace("\\/", "/")

                            if (constructedUrl.isNotEmpty()) {
                                urls.add(constructedUrl)
                            }
                        }
                    }
                }
            }

            return SecureLinksResponse(urls = urls)
        }
    }
}
