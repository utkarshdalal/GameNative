package app.gamenative.utils

import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Date
import java.util.EnumSet
import android.util.Base64

object LicenseSerializer {

    /**
     * Serialize a single License object by extracting all its fields to a JSON string.
     * Since License may not implement Serializable, we manually extract all fields.
     * Uses JSONObject to build JSON string since Map<String, Any> is not serializable.
     */
    fun serializeLicense(license: License): String {
        return try {
            val jsonObj = JSONObject().apply {
                put("packageID", license.packageID)
                put("lastChangeNumber", license.lastChangeNumber)
                put("timeCreated", license.timeCreated.time)
                put("timeNextProcess", license.timeNextProcess.time)
                put("minuteLimit", license.minuteLimit)
                put("minutesUsed", license.minutesUsed)
                put("paymentMethod", license.paymentMethod.code())
                put("licenseFlags", org.json.JSONArray(license.licenseFlags.map { it.code() }))
                put("purchaseCode", license.purchaseCode)
                put("licenseType", license.licenseType.code())
                put("territoryCode", license.territoryCode)
                put("accessToken", license.accessToken)
                put("ownerAccountID", license.ownerAccountID)
                put("masterPackageID", license.masterPackageID)
            }
            jsonObj.toString()
        } catch (e: Exception) {
            Timber.e(e, "Failed to serialize license: ${e.message}")
            ""
        }
    }

    /**
     * Deserialize JSON string to a single License object.
     * Uses reflection to construct License by setting fields directly.
     */
    fun deserializeLicense(jsonStr: String): License? {
        return try {
            if (jsonStr.isEmpty()) return null

            val jsonObj = JSONObject(jsonStr)

            // Extract all fields
            val packageID = jsonObj.optInt("packageID", 0)
            val lastChangeNumber = jsonObj.optInt("lastChangeNumber", 0)
            val timeCreated = Date(jsonObj.optLong("timeCreated", 0L))
            val timeNextProcess = Date(jsonObj.optLong("timeNextProcess", 0L))
            val minuteLimit = jsonObj.optInt("minuteLimit", 0)
            val minutesUsed = jsonObj.optInt("minutesUsed", 0)
            val paymentMethod = EPaymentMethod.from(jsonObj.optInt("paymentMethod", 0))
            val licenseFlagsArray = jsonObj.optJSONArray("licenseFlags")
            val licenseFlags = if (licenseFlagsArray != null) {
                val flags = EnumSet.noneOf(ELicenseFlags::class.java)
                for (i in 0 until licenseFlagsArray.length()) {
                    val flagCode = licenseFlagsArray.optInt(i)
                    flags.add(ELicenseFlags.from(flagCode).first())
                }
                flags
            } else {
                EnumSet.noneOf(ELicenseFlags::class.java)
            }
            val purchaseCode = jsonObj.optString("purchaseCode", "")
            val licenseType = ELicenseType.from(jsonObj.optInt("licenseType", 0))
            val territoryCode = jsonObj.optInt("territoryCode", 0)
            val accessToken = jsonObj.optLong("accessToken", 0L)
            val ownerAccountID = jsonObj.optInt("ownerAccountID", 0)
            val masterPackageID = jsonObj.optInt("masterPackageID", 0)

            // Construct License using CMsgClientLicenseList.License.newBuilder()
            // This is the recommended approach from the JavaSteam developer
            try {
                val licenseBuilder = SteammessagesClientserver.CMsgClientLicenseList.License.newBuilder()
                    .setPackageId(packageID)
                    .setTimeCreated((timeCreated.time / 1000).toInt()) // Convert to Unix timestamp
                    .setTimeNextProcess((timeNextProcess.time / 1000).toInt())
                    .setMinuteLimit(minuteLimit)
                    .setMinutesUsed(minutesUsed)
                    .setPaymentMethod(paymentMethod.code())
                    .setFlags(ELicenseFlags.code(licenseFlags))
                    .setPurchaseCountryCode(purchaseCode)
                    .setLicenseType(licenseType.code())
                    .setTerritoryCode(territoryCode)
                    .setAccessToken(accessToken)
                    .setOwnerId(ownerAccountID)
                    .setMasterPackageId(masterPackageID)
                    .setChangeNumber(lastChangeNumber)

                val licenseProto = licenseBuilder.build()
                return License(licenseProto)
            } catch (e: Exception) {
                Timber.e(e, "Failed to construct License using builder: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize license: ${e.message}")
            null
        }
    }

    /**
     * Serialize a DepotManifest object using Java serialization.
     * Since DepotManifest is a Java object from JavaSteam, we use Java's built-in serialization.
     */
    fun serializeManifest(manifest: DepotManifest): String {
        return try {
            val baos = ByteArrayOutputStream()
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(manifest)
            }
            Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            Timber.e(e, "Failed to serialize manifest")
            ""
        }
    }

    /**
     * Deserialize Base64 string to DepotManifest object using Java deserialization.
     */
    fun deserializeManifest(encodedStr: String): DepotManifest? {
        return try {
            if (encodedStr.isEmpty()) return null

            val bytes = Base64.decode(encodedStr, Base64.DEFAULT)
            ByteArrayInputStream(bytes).use { bais ->
                ObjectInputStream(bais).use { ois ->
                    ois.readObject() as? DepotManifest
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize manifest")
            null
        }
    }
}

