// poison android.widget.Toast — use SnackbarManager instead
@file:Suppress("MatchingDeclarationName")

package android.widget

@Deprecated("Use SnackbarManager instead of Toast", level = DeprecationLevel.ERROR)
class Toast private constructor()
