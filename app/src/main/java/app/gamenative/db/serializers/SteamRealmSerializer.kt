package app.gamenative.db.serializers

import app.gamenative.enums.SteamRealm
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object SteamRealmSerializer : KSerializer<SteamRealm> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SteamRealm", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SteamRealm) {
        encoder.encodeString(value.keyValue ?: "unknown")
    }

    override fun deserialize(decoder: Decoder): SteamRealm {
        return SteamRealm.from(decoder.decodeString())
    }
}
