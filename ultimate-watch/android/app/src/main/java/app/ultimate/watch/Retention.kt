package app.ultimate.watch

data class RecFile(val name: String, val bytes: Long, val modifiedMs: Long)

/** Decides which finished recordings to delete so storage and age stay within the limits. Never touches the file being written. */
object Retention {
    fun plan(files: List<RecFile>, activeName: String?, nowMs: Long, limits: StorageLimits): List<RecFile> {
        val finished = files.filter { it.name != activeName }
        val doomed = LinkedHashSet<RecFile>()
        finished.filter { nowMs - it.modifiedMs > limits.maxAgeMs }.forEach(doomed::add)
        var total = files.sumOf { it.bytes } - doomed.sumOf { it.bytes }
        for (file in finished.sortedBy { it.modifiedMs }) {
            if (total <= limits.maxTotalBytes) break
            if (doomed.add(file)) total -= file.bytes
        }
        return doomed.toList()
    }
}
