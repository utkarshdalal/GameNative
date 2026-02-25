package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ContainerConfigTransfer {
    suspend fun exportConfig(
        context: Context,
        appId: String,
        uri: Uri,
    ): Boolean {
        return try {
            val jsonText =
                withContext(Dispatchers.IO) {
                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                    JSONObject(container.containerJson).toString(2)
                }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonText.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            }

            SnackbarManager.show(
                context.getString(R.string.base_app_exported),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SnackbarManager.show(
                context.getString(
                    R.string.base_app_export_failed,
                    e.message ?: "IO error",
                ),
            )
            false
        } catch (e: Exception) {
            SnackbarManager.show(
                context.getString(
                    R.string.base_app_export_failed,
                    e.message ?: "Unknown error",
                ),
            )
            false
        }
    }
}

