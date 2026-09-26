package dev.lh.volsched.storage

import android.content.Context
import dev.lh.volsched.core.Schedule
import dev.lh.volsched.core.scheduleFromJson
import dev.lh.volsched.core.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The schedule file, and the single in-memory source of truth for it.
 *
 * Lives in device-protected storage so the boot receiver can read it before the
 * phone is unlocked. Credential-encrypted storage would be unreadable at
 * LOCKED_BOOT_COMPLETED, which is exactly when an overnight reboot needs it.
 */
class ScheduleStore private constructor(context: Context) {

    private val directory: File =
        context.applicationContext.createDeviceProtectedStorageContext().filesDir

    private val file = File(directory, FILE_NAME)
    private val tempFile = File(directory, "$FILE_NAME.tmp")

    /**
     * Non-null when the file exists but could not be parsed.
     *
     * When this is set the app runs on an empty schedule but never writes over
     * the file, so a hand-editing mistake can be fixed rather than silently
     * destroying the real schedule.
     */
    var loadError: String? = null
        private set

    private val _schedule = MutableStateFlow(readFromDisk())
    val schedule: StateFlow<Schedule> = _schedule.asStateFlow()

    fun save(schedule: Schedule) {
        check(loadError == null) { "Refusing to overwrite an unparseable schedule file" }
        writeAtomically(schedule.toJson())
        _schedule.value = schedule
    }

    /** Used by the widget toggle and the main switch. */
    fun setEnabled(enabled: Boolean) {
        save(_schedule.value.copy(enabled = enabled))
    }

    /** Import replaces everything, including a previously broken file. */
    fun replaceFromImport(schedule: Schedule) {
        writeAtomically(schedule.toJson())
        loadError = null
        _schedule.value = schedule
    }

    fun exportJson(): String = _schedule.value.toJson()

    fun rawFileContents(): String? = if (file.exists()) file.readText() else null

    private fun readFromDisk(): Schedule {
        if (!file.exists()) return Schedule()
        return try {
            scheduleFromJson(file.readText()).also { loadError = null }
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
            Schedule(enabled = false)
        }
    }

    /**
     * Write to a sibling file and rename over the target.
     *
     * rename(2) is atomic within a filesystem, so a crash or battery pull
     * mid-write leaves the previous schedule intact rather than a truncated file.
     */
    private fun writeAtomically(contents: String) {
        tempFile.writeText(contents)
        if (!tempFile.renameTo(file)) {
            // Rename can fail if the target exists on some filesystems.
            file.delete()
            check(tempFile.renameTo(file)) { "Could not replace ${file.path}" }
        }
    }

    companion object {
        private const val FILE_NAME = "schedule.json"

        @Volatile
        private var instance: ScheduleStore? = null

        fun get(context: Context): ScheduleStore =
            instance ?: synchronized(this) {
                instance ?: ScheduleStore(context).also { instance = it }
            }
    }
}
