package app.gamenative.utils

import android.content.Context
import com.winlator.xenvironment.ImageFs
import java.io.File

object DiagnosticsLog {
    fun file(context: Context, appId: String): File =
        File(ImageFs.find(context).rootDir, "usr/tmp/wrapper_diag_$appId.txt")

    fun exists(context: Context, appId: String): Boolean =
        file(context, appId).let { it.exists() && it.length() > 0 }
}
