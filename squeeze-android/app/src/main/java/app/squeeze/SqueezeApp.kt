package app.squeeze

import android.app.Application
import app.squeeze.engine.CompressionManager
import app.squeeze.engine.DeviceCodecs
import app.squeeze.model.Capabilities

class SqueezeApp : Application() {
    val capabilities: Capabilities by lazy { DeviceCodecs.detect() }
    val manager: CompressionManager by lazy { CompressionManager(this) }
}
