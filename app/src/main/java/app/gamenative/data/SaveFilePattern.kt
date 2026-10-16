package app.gamenative.data

import app.gamenative.enums.PathType
import app.gamenative.utils.SteamUtils
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal object IntOrBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.gamenative.data.IntOrBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val primitive = decoder.decodeJsonElement().jsonPrimitive
        return primitive.booleanOrNull ?: ((primitive.intOrNull ?: 0) != 0)
    }
}

@Serializable
data class SaveFilePattern(
    val root: PathType,
    val path: String,
    val pattern: String,
    @Serializable(with = IntOrBooleanSerializer::class)
    val recursive: Boolean = false,
    val uploadRoot: PathType = root,
    val uploadPath: String = path,
) {
    val prefix: String
        get() = "%${root.name}%$path"
            .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
            .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())

    val substitutedPath: String
        get() = path
            .replace("{64BitSteamID}", SteamUtils.getSteamId64().toString())
            .replace("{Steam3AccountID}", SteamUtils.getSteam3AccountId().toString())
            .replace("\\", File.separator)
}
