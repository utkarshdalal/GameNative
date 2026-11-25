// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.msixvc

import java.util.UUID

sealed class MsixvcException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidPackage(message: String) : MsixvcException(message)

    /** MSIXVC2 packages are ZIP containers of CBOR-described boxes, a different format. */
    class Msixvc2Unsupported : MsixvcException("MSIXVC2 packages are not supported")

    class UnsupportedPackage(message: String) : MsixvcException(message)

    class MissingContentKey(val keyId: UUID) : MsixvcException("no content key for key id $keyId")

    class NtfsUnsupported(message: String) : MsixvcException(message)

    class UnsafePath(val path: String, reason: String) : MsixvcException("unsafe package path \"$path\": $reason")

    class IntegrityMismatch(val page: Long) : MsixvcException("hash mismatch at package page $page")

    class RangeRequestFailed(message: String, cause: Throwable? = null) : MsixvcException(message, cause)
}
