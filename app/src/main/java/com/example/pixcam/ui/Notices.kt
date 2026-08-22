package com.example.pixcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.pixcam.theme.PixOnDark
import com.example.pixcam.theme.PixSurfaceHigh
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

private const val NOTICE_MILLIS = 2000L

/** One posted notice; [id] distinguishes repeats of the same text. */
internal data class Notice(val id: Long, val text: String)

/**
 * Holder for the transient notice shown by [NoticeHost]. [show] is safe to call
 * from any thread: it publishes a whole [Notice] into a single snapshot state.
 */
class NoticeState {
    private val counter = AtomicLong(0)
    internal var current by mutableStateOf<Notice?>(null)
        private set

    fun show(message: String) {
        current = Notice(counter.incrementAndGet(), message)
    }

    internal fun clear(id: Long) {
        if (current?.id == id) current = null
    }
}

@Composable
fun rememberNoticeState(): NoticeState = remember { NoticeState() }

@Composable
fun NoticeHost(state: NoticeState, modifier: Modifier = Modifier) {
    val notice = state.current
    // keeps the last text on screen while the exit animation runs
    var lastText by remember { mutableStateOf("") }
    if (notice != null) lastText = notice.text

    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(NOTICE_MILLIS)
        state.clear(id)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                lastText,
                color = PixOnDark,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PixSurfaceHigh.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
