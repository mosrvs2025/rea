package app.ultimate.watch

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Environment
import androidx.lifecycle.LifecycleService
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class CameraService : LifecycleService() {
    private val executor=Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider?=null
    private var recording: Recording?=null
    private var lastFrame=0L
    private var cookie: String?=null
    private var endpoint=""
    private var cameraName=""
    private var active=false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("camera","Camera monitoring",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message:String): Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,CameraService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"camera").setContentTitle("Ultimate Watch · monitoring").setContentText(message).setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
    }
    private fun status(message:String) { getSystemService(NotificationManager::class.java).notify(1,notification(message)) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        super.onStartCommand(intent,flags,startId)
        if (intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        if (active || intent==null) return START_NOT_STICKY
        startForeground(1,notification("Connecting to your server"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        active=true; endpoint=intent.getStringExtra("url")?:""; cameraName=intent.getStringExtra("name")?:"camera"
        val secret=intent.getStringExtra("password")?:""
        val shouldRecord=intent.getBooleanExtra("record",false)
        executor.execute {
            try {
                val body="password="+URLEncoder.encode(secret,"UTF-8")
                val c=connection("/login","POST"); c.setRequestProperty("Content-Type","application/x-www-form-urlencoded"); c.doOutput=true
                try { c.outputStream.use{it.write(body.toByteArray())}; check(c.responseCode==200){"Login failed (${c.responseCode})"}; cookie=c.getHeaderField("Set-Cookie")?.substringBefore(';'); check(cookie!=null){"Missing session"} } finally { c.disconnect() }
                ContextCompat.getMainExecutor(this).execute { if(active) bindCamera(shouldRecord) }
            } catch (_:Exception) { status("Connection failed. Open app and restart after checking server/certificate."); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun connection(path:String,method:String):HttpURLConnection = (URL(endpoint+path).openConnection() as HttpURLConnection).apply {
        requestMethod=method; connectTimeout=8000; readTimeout=8000; instanceFollowRedirects=false
        setRequestProperty("Origin",endpoint); cookie?.let { setRequestProperty("Cookie",it) }
    }
    private fun bindCamera(save:Boolean) {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p=future.get(); provider=p
                val analysis=ImageAnalysis.Builder().setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(active && now-lastFrame>=1000) {
                            lastFrame=now
                            val plane=image.planes[0]
                            val paddedWidth=plane.rowStride/plane.pixelStride
                            val bitmap=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
                            plane.buffer.rewind(); bitmap.copyPixelsFromBuffer(plane.buffer)
                            val matrix=Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                            val cropped=Bitmap.createBitmap(bitmap,0,0,image.width,image.height,matrix,true)
                            val bytes=ByteArrayOutputStream().apply { cropped.compress(Bitmap.CompressFormat.JPEG,65,this) }.toByteArray()
                            if(cropped!==bitmap)cropped.recycle(); bitmap.recycle()
                            val c=connection("/api/frame/$cameraName","POST"); c.setRequestProperty("Content-Type","image/jpeg"); c.doOutput=true
                            try { c.outputStream.use{it.write(bytes)}; check(c.responseCode==204); status("Live · $cameraName"+(if(save)" · local recording requested" else "")) } finally { c.disconnect() }
                        }
                    } catch (_:Exception) { status("Stream interrupted · retrying") } finally { image.close() }
                }
                if(save) {
                    val recorder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
                    val capture=VideoCapture.withOutput(recorder)
                    p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,analysis,capture)
                    val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
                    val file=File(dir,"watch-${System.currentTimeMillis()}.mp4")
                    recording=recorder.prepareRecording(this,FileOutputOptions.Builder(file).build()).start(ContextCompat.getMainExecutor(this)) { event ->
                        if(event is VideoRecordEvent.Finalize) status(if(event.hasError())"Recording error ${event.error}" else "Recording saved")
                    }
                } else p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,analysis)
            } catch (_:Exception) { status("Camera setup failed. Stop and reopen app."); stopSelf() }
        },ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() {
        active=false; recording?.stop(); recording=null; provider?.unbindAll(); cookie=null; executor.shutdown(); super.onDestroy()
    }
}
