package app.ultimate.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun StudioPreview(project: StudioProject, clip: StudioClip?, playing: Boolean, seekMs: Long?, onSeekConsumed: () -> Unit,
    onPosition: (Long) -> Unit, onEnded: () -> Unit, onPause: () -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val video = remember { ExoPlayer.Builder(context).build() }
    val music = remember { ExoPlayer.Builder(context).build() }
    val endCallback by rememberUpdatedState(onEnded)
    val errorCallback by rememberUpdatedState(onError)
    DisposableEffect(video, music) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) endCallback() }
            override fun onPlayerError(error: PlaybackException) { errorCallback(error.message ?: "Video preview failed") }
        }
        video.addListener(listener)
        val audioListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errorCallback(error.message ?: "Music preview failed") }
        }
        music.addListener(audioListener)
        onDispose { video.removeListener(listener); music.removeListener(audioListener); video.release(); music.release() }
    }
    val pauseCallback by rememberUpdatedState(onPause)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { video.pause(); music.pause(); pauseCallback() } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(clip, project.shape, project.fillCanvas, project.shortSide) {
        if (clip == null) { video.stop(); return@LaunchedEffect }
        video.setVideoEffects(StudioEffects.video(clip, project, false))
        video.setMediaItem(StudioEffects.mediaItem(clip))
        video.playbackParameters = PlaybackParameters(clip.speed, 1f)
        video.volume = clip.volume.coerceIn(0f, 1f)
        video.prepare()
        video.playWhenReady = playing
    }
    LaunchedEffect(project.music) {
        val m = project.music
        if (m == null) music.stop() else {
            music.setMediaItem(MediaItem.fromUri(m.uri)); music.volume = m.volume.coerceIn(0f, 1f); music.prepare()
        }
    }
    LaunchedEffect(playing, clip, project.music, project.clips.map { it.id }) {
        video.playWhenReady = playing
        while (true) {
            val localMs = (video.currentPosition / (clip?.speed ?: 1f)).toLong().coerceIn(0, clip?.durationMs ?: 0)
            onPosition(localMs)
            project.music?.let { m ->
                val at = Timeline.offset(project, clip?.id.orEmpty()) + localMs
                val expected = m.startMs + at % (m.durationMs - m.startMs).coerceAtLeast(1)
                if (abs(music.currentPosition - expected) > 120) music.seekTo(expected)
                music.playWhenReady = playing && video.isPlaying
            }
            delay(50)
        }
    }
    LaunchedEffect(seekMs) { seekMs?.let { video.seekTo((it * (clip?.speed ?: 1f)).toLong()); onSeekConsumed() } }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (clip == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your next film starts here.", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Add your videos to begin", color = Color.LightGray)
        } else AndroidView(factory = { PlayerView(it).apply { useController = false; player = video } }, modifier = Modifier.fillMaxSize())
    }
}
