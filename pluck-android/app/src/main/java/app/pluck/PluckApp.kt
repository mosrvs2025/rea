package app.pluck

import android.app.Application
import app.pluck.engine.AppRepository
import app.pluck.engine.ExtractManager
import app.pluck.engine.HistoryStore
import app.pluck.engine.ScanStore

class PluckApp : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
    val history: HistoryStore by lazy { HistoryStore(this) }
    val scans: ScanStore by lazy { ScanStore(this) }
    val manager: ExtractManager by lazy { ExtractManager(this, history) }
}
