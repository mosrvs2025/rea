package app.squeeze

import android.app.Application
import app.squeeze.engine.CompressionManager
import app.squeeze.engine.DeviceCodecs
import app.squeeze.engine.LibraryStore
import app.squeeze.model.Capabilities

class SqueezeApp : Application() {
    val capabilities: Capabilities by lazy { DeviceCodecs.detect() }
    val library: LibraryStore by lazy { LibraryStore(this) }
    val manager: CompressionManager by lazy { CompressionManager(this, library) }
}
