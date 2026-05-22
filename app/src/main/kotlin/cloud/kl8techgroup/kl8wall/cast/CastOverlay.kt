package cloud.kl8techgroup.kl8wall.cast

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun CastOverlay(
    castUrl: String,
    castManager: CastManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackState by castManager.playbackState.collectAsState()
    val duration by castManager.duration.collectAsState()
    val position by castManager.position.collectAsState()
    val volume by castManager.volume.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    val isVideo = remember(castUrl) { isVideoOrAudioUrl(castUrl) }

    // Auto-hide controls after 5 seconds if playing
    LaunchedEffect(showControls, playbackState) {
        if (showControls && playbackState == "PLAYING") {
            delay(5000)
            showControls = false
        }
    }

    // Poll position from VideoView when playing
    LaunchedEffect(playbackState) {
        while (isActive) {
            val vv = videoViewRef
            if (vv != null && vv.isPlaying) {
                castManager.updatePosition(vv.currentPosition / 1000)
            }
            delay(1000)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setVideoURI(Uri.parse(castUrl))
                        setOnPreparedListener { mp ->
                            castManager.updateDuration(mp.duration / 1000)
                            if (playbackState == "PLAYING") {
                                start()
                            }
                        }
                        setOnCompletionListener {
                            castManager.updatePlaybackState("IDLE")
                            castManager.stop()
                            onClose()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e("CastOverlay", "VideoView Error: what=$what, extra=$extra")
                            castManager.updatePlaybackState("ERROR")
                            true
                        }
                        videoViewRef = this
                    }
                },
                update = { videoView ->
                    videoViewRef = videoView
                    if (playbackState == "PLAYING") {
                        if (!videoView.isPlaying) {
                            videoView.start()
                        }
                    } else if (playbackState == "PAUSED") {
                        if (videoView.isPlaying) {
                            videoView.pause()
                        }
                    } else if (playbackState == "IDLE") {
                        if (videoView.isPlaying) {
                            videoView.stopPlayback()
                        }
                    }
                    
                    val currentSec = videoView.currentPosition / 1000
                    if (kotlin.math.abs(currentSec - position) > 3 && duration > 0) {
                        videoView.seekTo(position * 1000)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Web page cast
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        
                        // User-Agent override to look desktop-like or TV-like
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                        webViewClient = android.webkit.WebViewClient()
                        webChromeClient = android.webkit.WebChromeClient()
                        loadUrl(getYouTubeEmbedUrl(castUrl))
                    }
                },
                update = { webView ->
                    // WebViews don't have standard remote control, but we keep it running
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay UI Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                // Top control bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isVideo) "Casting Media" else "Casting Webpage",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(
                            text = castUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    
                    IconButton(
                        onClick = {
                            castManager.stop()
                            onClose()
                        },
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop Casting",
                            tint = Color.White
                        )
                    }
                }

                // Bottom control panel (only for video/audio casts)
                if (isVideo) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Progress bar
                            if (duration > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = formatTime(position),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                    Slider(
                                        value = position.toFloat(),
                                        onValueChange = { castManager.seek(it.toInt()) },
                                        valueRange = 0f..duration.toFloat(),
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Text(
                                        text = formatTime(duration),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }

                            // Controls Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Volume slider
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.width(180.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = Color.White,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Slider(
                                        value = volume.toFloat(),
                                        onValueChange = { castManager.setVolume(it.toInt()) },
                                        valueRange = 0f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White
                                        )
                                    )
                                }

                                // Play / Pause Button
                                IconButton(
                                    onClick = {
                                        if (playbackState == "PLAYING") {
                                            castManager.pause()
                                        } else {
                                            castManager.play()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .clip(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (playbackState == "PLAYING") {
                                            Icons.Default.Pause
                                        } else {
                                            Icons.Default.PlayArrow
                                        },
                                        contentDescription = if (playbackState == "PLAYING") "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                // A spacer for balance
                                Spacer(modifier = Modifier.width(180.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isVideoOrAudioUrl(url: String): Boolean {
    val cleanUrl = url.substringBefore("?").lowercase()
    val extensions = listOf(".mp4", ".mp3", ".mkv", ".webm", ".ogg", ".wav", ".3gp", ".m3u8", ".m4a")
    return extensions.any { cleanUrl.endsWith(it) } ||
            url.startsWith("rtsp://") ||
            url.startsWith("rtmp://") ||
            url.contains("/video/") ||
            url.contains("/audio/")
}

private fun getYouTubeEmbedUrl(url: String): String {
    if (url.contains("youtube.com") || url.contains("youtu.be")) {
        val videoId = extractYouTubeVideoId(url)
        if (videoId != null) {
            return "https://www.youtube.com/embed/$videoId?autoplay=1&mute=0&controls=1"
        }
    }
    return url
}

private fun extractYouTubeVideoId(url: String): String? {
    val regex = "^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/(watch\\?v=|embed/|v/|shorts/)?([a-zA-Z0-9_-]{11})".toRegex()
    val matchResult = regex.find(url)
    return matchResult?.groupValues?.get(5)
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
