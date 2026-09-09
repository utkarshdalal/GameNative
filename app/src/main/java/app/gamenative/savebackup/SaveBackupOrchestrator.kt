package app.gamenative.savebackup

import android.content.Context
import android.net.Uri
import app.gamenative.data.LibraryItem
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Signalled by an injected picker callback when the picker (the in-app [ContainerBrowser] flow or
 * the SAF `OpenDocumentTree` flow) **fails to open** — as opposed to opening and being cancelled by
 * the user.
 *
 * The open-failure-vs-cancel contract (Design "Export flow", Requirements 4.2a vs 4.6):
 * - **Open failure** → the callback throws [PickerOpenException]. The orchestrator maps this to
 *   [ExportOutcome.Aborted] and reports the failure; no external changes are made (Requirement 4.2a).
 * - **User cancel** → the callback returns `null` (a `SaveLocation?` / `Uri?`). The orchestrator
 *   maps this to [ExportOutcome.Cancelled]; no external changes are made (Requirement 4.6).
 *
 * A cancel is a normal, expected outcome and is never reported as a failure; an open failure is an
 * error the user must be told about. Keeping them as two distinct signals (a thrown exception vs a
 * null return) lets the UI supply real launchers without conflating the two.
 */
class PickerOpenException(
    /** Which picker failed to open, used to build the user-facing abort message. */
    val picker: Picker,
    cause: Throwable? = null,
    message: String? = null,
) : Exception(message ?: "The ${picker.label} could not be opened", cause) {

    enum class Picker(val label: String) {
        CONTAINER_BROWSER("container browser"),
        SAF_PICKER("folder picker"),
    }
}

/**
 * The terminal outcome of an export flow, returned directly to the UI which drives its state from
 * this value (authoritative delivery — Requirement 13.5; the snackbar is a secondary channel).
 *
 * Every non-[Success] outcome guarantees **no external changes were made** unless otherwise noted:
 * the orchestrator only ever calls [SaveBackupEngine.export] after *both* endpoints (the container
 * save location and the external tree URI) have been determined, so any earlier abort/cancel path
 * cannot have touched the external location (see "atomicity" in the class docs).
 */
sealed interface ExportOutcome {
    /** The export completed and every save file was copied and verified (Requirement 4.4). */
    data object Success : ExportOutcome

    /**
     * No save files were found at the resolved save location, so nothing was copied and the
     * external location is unchanged (Requirement 4.5).
     */
    data object NoSavesFound : ExportOutcome

    /**
     * The user cancelled one of the pickers (container browser or SAF). No external changes were
     * made; never reported as success or failure (Requirements 4.6, 13.4).
     */
    data object Cancelled : ExportOutcome

    /**
     * The export was aborted before completion because a precondition could not be met — a picker
     * failed to open (Requirement 4.2a), the container could not be resolved (Requirement 6.5), or
     * a required precondition was unmet (Requirement 11.1a). [reason] is the specific, user-facing
     * cause. No external changes were made.
     */
    data class Aborted(val reason: String) : ExportOutcome

    /**
     * The transfer itself failed after both endpoints were determined; [reason] describes why
     * (Requirements 13.1–13.2). A previously completed export at the destination is never
     * overwritten by a failed run (the engine writes a fresh archive/tree — Requirement 13.2).
     */
    data class Failed(val reason: String) : ExportOutcome
}

/**
 * The terminal outcome of an import flow, returned directly to the UI which drives its state from
 * this value (authoritative delivery — Requirement 13.5; the snackbar is a secondary channel).
 *
 * This is the import-side analogue of [ExportOutcome]. It is kept as a **dedicated** type rather
 * than sharing [ExportOutcome] so the two flows stay independently evolvable and the UI can pattern
 * match on an unambiguously import-shaped result (the atomicity guarantee below is about the
 * *container*, not the external location).
 *
 * Every non-[Success] outcome guarantees **no container changes were made** unless otherwise noted:
 * the orchestrator only ever calls [SaveBackupEngine.import] after *both* endpoints (the source
 * external tree URI and the destination container save location) have been determined, so any
 * earlier abort/cancel path cannot have touched the container (Requirement 5.7; see "atomicity" in
 * the [SaveBackupOrchestrator] docs).
 */
sealed interface ImportOutcome {
    /** The import completed and every save file was copied and verified (Requirement 5.5). */
    data object Success : ImportOutcome

    /**
     * No save files were found in the selected source, so nothing was copied and the container is
     * unchanged (Requirement 5.6).
     */
    data object NoSavesFound : ImportOutcome

    /**
     * The user cancelled one of the pickers (SAF source or container browser). No container changes
     * were made; never reported as success or failure (Requirements 5.8, 13.4).
     */
    data object Cancelled : ImportOutcome

    /**
     * The import was aborted before completion because a precondition could not be met — the SAF
     * source picker failed to open (Requirement 5.1a), the container browser failed to open, the
     * container could not be resolved (Requirement 6.5), or a persisted destination location could
     * not be resolved (Requirement 11.1a unmet precondition). [reason] is the specific, user-facing
     * cause. No container changes were made.
     */
    data class Aborted(val reason: String) : ImportOutcome

    /**
     * The transfer itself failed after both endpoints were determined; [reason] describes why
     * (Requirements 5.9, 13.3). A failed import leaves the destination save location in exactly the
     * state it held before the import began (the engine commits through a staged rollback).
     */
    data class Failed(val reason: String) : ImportOutcome
}

/**
 * Sequences the two-picker export flow and enforces its atomicity guarantee, sitting between the UI
 * (which supplies the real, asynchronous picker launchers) and the [SaveBackupEngine] (which
 * performs the transfer once both endpoints are known).
 *
 * ## Why the orchestrator is UI-agnostic and callback-driven
 *
 * The two pickers are both UI-driven and asynchronous, and they are of different kinds: the
 * [ContainerBrowser] is an in-app Compose flow, while the SAF picker is an Activity-result flow
 * (`OpenDocumentTree`, see [rememberSafPicker]). The orchestrator therefore cannot itself
 * synchronously "open a picker." Instead it is driven as a suspend flow with the picker operations
 * **injected as suspend callbacks**, so it stays free of any Compose/Activity dependency and is
 * unit-drivable with fakes (tests are task 11.3; the UI wires the real launchers in task 12):
 *
 * - [ExportPickers.openContainerBrowser] — `suspend (container, appId, gameId) -> SaveLocation?`:
 *   opens the in-app browser and returns the confirmed-and-persisted [SaveLocation], or `null` when
 *   the user cancels. Throws [PickerOpenException] if the browser cannot open.
 * - [ExportPickers.pickExternalTree] — `suspend () -> Uri?`: launches the SAF tree picker and
 *   returns the selected tree URI, or `null` when the user cancels. Throws [PickerOpenException] if
 *   the picker cannot open.
 *
 * The open-failure-vs-cancel contract is spelled out on [PickerOpenException].
 *
 * ## Export sequence (Design "Export flow")
 *
 * 1. **Resolve the container** via [ContainerUtils.getOrCreateContainer]`(context, item.appId)` —
 *    source-agnostic (Requirement 6.4). Unresolvable → [ExportOutcome.Aborted], no changes
 *    (Requirement 6.5).
 * 2. **Resolve the save location** via [SaveLocationResolutionService.resolve]:
 *    - [ResolveOutcome.Resolved] → we already have a known location; **skip the browser** and go
 *      straight to SAF (Requirement 4.2).
 *    - [ResolveOutcome.NeedsBrowser] → the location is unset; **open the container browser first**
 *      to obtain a [SaveLocation] (Requirement 4.1). Browser cancel → [ExportOutcome.Cancelled];
 *      browser open failure → [ExportOutcome.Aborted] (Requirements 4.6, 4.2a).
 *    - [ResolveOutcome.Unresolved] → a persisted location cannot be resolved; abort and report the
 *      specific reason (Requirement 11.1a unmet precondition: no resolved save location).
 * 3. **Obtain the external location**: reuse a still-valid remembered grant if present
 *    ([SafLocationManager.rememberedLocation], Requirement 10.2), else launch the SAF picker
 *    ([ExportPickers.pickExternalTree]). Picker cancel → [ExportOutcome.Cancelled]; open failure →
 *    [ExportOutcome.Aborted] (Requirements 4.6, 4.2a). On a fresh selection, attempt
 *    [SafLocationManager.tryPersist]; if it fails, warn "folder could not be remembered" but
 *    continue this operation with the selected URI (Requirement 10.3).
 * 4. **Transfer**: call [SaveBackupEngine.export]`(context, item, dest, layout, location)` and map
 *    its [BackupResult] onto an [ExportOutcome].
 *
 * ## Atomicity: no external changes until both endpoints are determined
 *
 * The engine only writes to the external location inside [SaveBackupEngine.export], and the
 * orchestrator calls that method **only after both** the [SaveLocation] and the external [Uri] have
 * been obtained (step 4, after steps 1–3 all succeed). Every earlier exit — container unresolvable
 * (step 1), unresolvable persisted location (step 2), browser cancel (step 2), SAF open failure or
 * cancel (step 3) — returns *before* `engine.export` is ever called, so no external change can have
 * occurred (Requirements 4.2a, 4.6, 6.5). The `tryPersist` warning path (step 3) touches only the
 * persistable-permission store, never the external tree contents.
 *
 * @param engine the transfer engine invoked once both endpoints are known.
 * @param resolutionService resolves/persists the container-side [SaveLocation] (store + strategy).
 * @param safLocationManager owns remembered SAF grants and persistable-permission requests.
 * @param resolveContainer resolves a container from a source-prefixed appId; injectable for tests.
 *   Defaults to [ContainerUtils.getOrCreateContainer] (Requirement 6.4).
 */
class SaveBackupOrchestrator(
    private val engine: SaveBackupEngine,
    private val resolutionService: SaveLocationResolutionService,
    private val safLocationManager: SafLocationManager,
    private val resolveContainer: (Context, String) -> Container = { ctx, appId ->
        ContainerUtils.getOrCreateContainer(ctx, appId)
    },
) {

    /**
     * The injected, UI-supplied picker operations for an export. Both are suspend callbacks so the
     * orchestrator stays UI-agnostic; the null-vs-throw contract is on [PickerOpenException].
     */
    interface ExportPickers {
        /**
         * Open the in-app [ContainerBrowser] for [container] and return the user's
         * confirmed-and-persisted [SaveLocation], or `null` if the user cancelled (Requirement 4.6).
         *
         * @throws PickerOpenException if the browser cannot open (Requirement 4.2a).
         */
        suspend fun openContainerBrowser(
            container: Container,
            appId: String,
            gameId: Int,
        ): SaveLocation?

        /**
         * Launch the SAF `OpenDocumentTree` picker and return the selected tree URI, or `null` if
         * the user cancelled (Requirement 4.6).
         *
         * @throws PickerOpenException if the picker cannot open (Requirement 4.2a).
         */
        suspend fun pickExternalTree(): Uri?

        /**
         * Optional hook invoked when a freshly-picked external location could not be remembered as
         * a durable grant (Requirement 10.3). The operation still continues with the selected URI;
         * this is only a warning surface. Default: no-op.
         */
        suspend fun onFolderNotRemembered() {}
    }

    /**
     * The injected, UI-supplied picker operations for an import. Both are suspend callbacks so the
     * orchestrator stays UI-agnostic; the null-vs-throw contract is on [PickerOpenException].
     *
     * ## Why a dedicated import source picker (vs reusing [ExportPickers])
     *
     * For import the SAF selection is the **source to read from**, not a destination to write to.
     * The engine's [SaveBackupEngine.import] sniffs the source layout from the selected `Uri`
     * (archive `.zip` vs raw folder tree — see `DefaultSaveBackupEngine.detectImportSource`), so a
     * single source picker suffices. We back it with `OpenDocumentTree` (the same tree picker the
     * export flow uses and the same shape [SafLocationManager] persists a grant for): the engine
     * treats a tree containing exactly one top-level `.zip` as an archive and any other tree as a
     * raw tree, and also accepts a directly-picked `.zip` document. Keeping this as a distinct
     * callback (rather than reusing [ExportPickers.pickExternalTree]) documents that the returned
     * URI is a *source* and lets the two flows evolve their picker semantics independently.
     */
    interface ImportPickers {
        /**
         * Launch the SAF picker to select the **source** external location to import from, and
         * return the selected tree/document URI, or `null` if the user cancelled (Requirement 5.8).
         *
         * Backed by `OpenDocumentTree`; the engine sniffs archive-vs-raw from the returned URI.
         *
         * @throws PickerOpenException if the picker cannot open (Requirement 5.1a).
         */
        suspend fun pickImportSource(): Uri?

        /**
         * Open the in-app [ContainerBrowser] for [container] and return the user's
         * confirmed-and-persisted destination [SaveLocation], or `null` if the user cancelled
         * (Requirement 5.8).
         *
         * @throws PickerOpenException if the browser cannot open.
         */
        suspend fun openContainerBrowser(
            container: Container,
            appId: String,
            gameId: Int,
        ): SaveLocation?

        /**
         * Optional hook invoked when a freshly-picked source location could not be remembered as a
         * durable grant (Requirement 10.3). The operation still continues with the selected URI;
         * this is only a warning surface. Default: no-op.
         */
        suspend fun onFolderNotRemembered() {}
    }

    /**
     * Run the export flow for [item] with the chosen [layout], driven by the injected [pickers].
     *
     * @param context Android context used to resolve the container and SAF grants.
     * @param item the game to export; supplies the source-prefixed [LibraryItem.appId] (container +
     *   store key), the numeric [LibraryItem.gameId], and the [LibraryItem.gameSource].
     * @param layout the archive layout the user selected in the UI (Archive vs Raw tree).
     * @param pickers the UI-supplied picker callbacks.
     * @return the terminal [ExportOutcome]; the UI drives its state from this value.
     */
    suspend fun export(
        context: Context,
        item: LibraryItem,
        layout: ExportLayout,
        pickers: ExportPickers,
    ): ExportOutcome {
        // --- Step 1: resolve the container (Req 6.4) ---------------------------------------
        val container = try {
            resolveContainer(context, item.appId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unresolvable container → abort, no changes, report (Req 6.5).
            Timber.w(e, "Export aborted: container unresolvable for ${item.appId}")
            return ExportOutcome.Aborted(
                "Could not resolve the game's container. No changes were made.",
            )
        }

        // --- Step 2: determine the container-side SaveLocation -----------------------------
        val location: SaveLocation = when (
            val outcome = resolutionService.resolve(context, container, item)
        ) {
            // Known location → skip the browser, go straight to SAF (Req 4.2).
            is ResolveOutcome.Resolved -> outcome.saveLocation

            // Persisted but unresolvable → unmet precondition (no resolved save location),
            // abort and report the specific reason (Req 11.1a).
            is ResolveOutcome.Unresolved ->
                return ExportOutcome.Aborted(
                    "The saved location for this game could not be resolved: ${outcome.reason}. " +
                        "No changes were made.",
                )

            // Unset → open the container browser FIRST to obtain a location (Req 4.1).
            is ResolveOutcome.NeedsBrowser -> {
                val picked = try {
                    pickers.openContainerBrowser(container, item.appId, item.gameId)
                } catch (e: PickerOpenException) {
                    // Browser failed to open → abort + report, no external changes (Req 4.2a).
                    Timber.w(e, "Export aborted: container browser failed to open for ${item.appId}")
                    return ExportOutcome.Aborted(abortMessageFor(e))
                }
                    // Browser cancelled → abort, no external changes (Req 4.6).
                    ?: return ExportOutcome.Cancelled
                picked
            }
        }

        // --- Step 3: obtain the external location (remembered grant reuse, else SAF) -------
        val dest: Uri = when (val obtained = obtainExternalLocation(context, item.appId, pickers)) {
            is ExternalLocation.Selected -> obtained.uri
            ExternalLocation.Cancelled -> return ExportOutcome.Cancelled
            is ExternalLocation.OpenFailed -> return ExportOutcome.Aborted(obtained.reason)
        }

        // --- Step 4: transfer (both endpoints now determined → atomicity holds) ------------
        return when (val result = engine.export(context, item, dest, layout, location)) {
            BackupResult.Success -> ExportOutcome.Success
            BackupResult.NoSavesFound -> ExportOutcome.NoSavesFound
            BackupResult.Cancelled -> ExportOutcome.Cancelled
            is BackupResult.Failed -> ExportOutcome.Failed(result.message)
        }
    }

    /**
     * Run the import flow for [item], driven by the injected [pickers].
     *
     * ## Import sequence (Design "Import flow"), with SAF FIRST
     *
     * Unlike [export] (where the container browser is opened first when the location is unset), an
     * import opens the **SAF source picker first** (Requirement 5.1). The container browser (if the
     * destination location is unset) is only opened *after* the source has been selected. This
     * ordering is mandated by Requirement 5.1 and is the critical difference from the export flow.
     *
     * 1. **Resolve the container** via [ContainerUtils.getOrCreateContainer]`(context, item.appId)`
     *    — source-agnostic (Requirement 6.4). Unresolvable → [ImportOutcome.Aborted], no changes
     *    (Requirement 6.5). Container resolution is a shared precondition; it never mutates the
     *    container.
     * 2. **SAF FIRST — obtain the source external location** (Requirement 5.1): always launch the
     *    SAF source picker ([ImportPickers.pickImportSource]). Unlike export, import never reuses or
     *    persists a remembered grant — the source is chosen fresh each time and the remembered-grant
     *    store models the *export destination*, not the import source (see [obtainImportSource]).
     *    Picker open failure → [ImportOutcome.Aborted] (Requirement 5.1a); cancel →
     *    [ImportOutcome.Cancelled] (Requirement 5.8).
     * 3. **Determine the destination [SaveLocation]** via [SaveLocationResolutionService.resolve]:
     *    - [ResolveOutcome.Resolved] → known location; **import directly, skip the browser**
     *      (Requirement 5.3).
     *    - [ResolveOutcome.NeedsBrowser] → the location is unset; **open the container browser** to
     *      obtain the destination (Requirement 5.2). Browser cancel → [ImportOutcome.Cancelled]
     *      (Requirement 5.8); browser open failure → [ImportOutcome.Aborted].
     *    - [ResolveOutcome.Unresolved] → a persisted location cannot be resolved; abort and report
     *      the specific reason (Requirement 11.1a unmet precondition).
     * 4. **Transfer**: call [SaveBackupEngine.import]`(context, item, source, location)` and map its
     *    [BackupResult] onto an [ImportOutcome].
     *
     * ## Atomicity: no container changes until both endpoints are determined (Requirement 5.7)
     *
     * The engine only writes to the container inside [SaveBackupEngine.import], and the orchestrator
     * calls that method **only after both** the source [Uri] (step 2) and the destination
     * [SaveLocation] (step 3) have been obtained (step 4). Every earlier exit — container
     * unresolvable (step 1), SAF open failure or cancel (step 2), unresolvable persisted location or
     * browser open failure/cancel (step 3) — returns *before* `engine.import` is ever called, so no
     * container change can have occurred (Requirements 5.7, 5.8, 5.1a, 6.5).
     *
     * @param context Android context used to resolve the container and SAF grants.
     * @param item the game to import into; supplies the source-prefixed [LibraryItem.appId]
     *   (container + store key), the numeric [LibraryItem.gameId], and the [LibraryItem.gameSource].
     * @param pickers the UI-supplied picker callbacks.
     * @return the terminal [ImportOutcome]; the UI drives its state from this value.
     */
    suspend fun import(
        context: Context,
        item: LibraryItem,
        pickers: ImportPickers,
    ): ImportOutcome {
        // --- Step 1: resolve the container (Req 6.4). No container mutation happens here. --------
        val container = try {
            resolveContainer(context, item.appId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unresolvable container → abort, no changes, report (Req 6.5).
            Timber.w(e, "Import aborted: container unresolvable for ${item.appId}")
            return ImportOutcome.Aborted(
                "Could not resolve the game's container. No changes were made.",
            )
        }

        // --- Step 2: SAF FIRST — obtain the source external location (Req 5.1) -------------------
        val source: Uri = when (val obtained = obtainImportSource(item.appId, pickers)) {
            is ExternalLocation.Selected -> obtained.uri
            ExternalLocation.Cancelled -> return ImportOutcome.Cancelled
            is ExternalLocation.OpenFailed -> return ImportOutcome.Aborted(obtained.reason)
        }

        // --- Step 3: determine the destination SaveLocation (browser only if unset) --------------
        val location: SaveLocation = when (
            val outcome = resolutionService.resolve(context, container, item)
        ) {
            // Known location → import directly, skip the browser (Req 5.3).
            is ResolveOutcome.Resolved -> outcome.saveLocation

            // Persisted but unresolvable → unmet precondition (no resolved destination), abort and
            // report the specific reason (Req 11.1a).
            is ResolveOutcome.Unresolved ->
                return ImportOutcome.Aborted(
                    "The saved location for this game could not be resolved: ${outcome.reason}. " +
                        "No changes were made.",
                )

            // Unset → open the container browser to obtain the destination (Req 5.2).
            is ResolveOutcome.NeedsBrowser -> {
                val picked = try {
                    pickers.openContainerBrowser(container, item.appId, item.gameId)
                } catch (e: PickerOpenException) {
                    // Browser failed to open → abort + report, no container changes.
                    Timber.w(e, "Import aborted: container browser failed to open for ${item.appId}")
                    return ImportOutcome.Aborted(abortMessageFor(e))
                }
                    // Browser cancelled → abort, no container changes (Req 5.8).
                    ?: return ImportOutcome.Cancelled
                picked
            }
        }

        // --- Step 4: transfer (both endpoints now determined → atomicity holds, Req 5.7) ---------
        return when (val result = engine.import(context, item, source, location)) {
            BackupResult.Success -> ImportOutcome.Success
            BackupResult.NoSavesFound -> ImportOutcome.NoSavesFound
            BackupResult.Cancelled -> ImportOutcome.Cancelled
            is BackupResult.Failed -> ImportOutcome.Failed(result.message)
        }
    }

    /**
     * Obtain the source tree/document URI for an import by **always launching the source picker** —
     * import never reuses or persists a remembered grant.
     *
     * ## Why import does not reuse/persist grants (unlike [obtainExternalLocation])
     *
     * The remembered-grant machinery ([SafLocationManager], Req 10.2) is keyed by `appId` and models
     * the *export destination* a user backs up to repeatedly. Import is different: the source is
     * chosen fresh each time (a specific archive, a friend's backup, a different folder), so silently
     * reusing a previously remembered location would import the wrong source without asking — and
     * persisting the import source under the same `appId` key would clobber the export destination
     * memory (source and destination are not the same place). Import therefore always prompts and
     * leaves the remembered-grant store untouched; the destination-side reuse stays on export only.
     */
    private suspend fun obtainImportSource(
        appId: String,
        pickers: ImportPickers,
    ): ExternalLocation {
        val picked = try {
            pickers.pickImportSource()
        } catch (e: PickerOpenException) {
            // SAF failed to open → abort + report, no container changes (Req 5.1a).
            Timber.w(e, "Import aborted: SAF source picker failed to open for $appId")
            return ExternalLocation.OpenFailed(abortMessageFor(e))
        }
            // Picker cancelled → abort, no container changes (Req 5.8).
            ?: return ExternalLocation.Cancelled

        return ExternalLocation.Selected(picked)
    }

    /**
     * Obtain the external tree URI: reuse a still-valid remembered grant without re-prompting
     * (Requirement 10.2), otherwise launch the SAF picker and, on a fresh selection, try to persist
     * the grant (Requirements 10.1, 10.3, 10.4).
     */
    private suspend fun obtainExternalLocation(
        context: Context,
        appId: String,
        pickers: ExportPickers,
    ): ExternalLocation {
        // Reuse a valid remembered grant without re-prompting (Req 10.2). A revoked/expired/
        // unresolvable grant returns null here and is forgotten, so we fall through to the picker
        // (Req 10.4).
        safLocationManager.rememberedLocation(context, appId)?.let { remembered ->
            return ExternalLocation.Selected(remembered)
        }

        val picked = try {
            pickers.pickExternalTree()
        } catch (e: PickerOpenException) {
            // SAF failed to open → abort + report, no external changes (Req 4.2a).
            Timber.w(e, "Export aborted: SAF picker failed to open for $appId")
            return ExternalLocation.OpenFailed(abortMessageFor(e))
        }
            // Picker cancelled → abort, no external changes (Req 4.6).
            ?: return ExternalLocation.Cancelled

        // Fresh selection: request a durable grant. If it cannot be remembered, warn but continue
        // for this operation only (Req 10.3).
        val remembered = safLocationManager.tryPersist(appId, picked)
        if (!remembered) {
            pickers.onFolderNotRemembered()
        }
        return ExternalLocation.Selected(picked)
    }

    /** Build the user-facing abort message for a picker open failure (Req 4.2a). */
    private fun abortMessageFor(e: PickerOpenException): String =
        "The ${e.picker.label} could not be opened. No changes were made."

    /** Internal result of the external-location step, mapped onto [ExportOutcome] by the caller. */
    private sealed interface ExternalLocation {
        data class Selected(val uri: Uri) : ExternalLocation
        data object Cancelled : ExternalLocation
        data class OpenFailed(val reason: String) : ExternalLocation
    }
}
