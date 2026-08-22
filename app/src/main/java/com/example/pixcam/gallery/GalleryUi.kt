package com.example.pixcam.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.pixcam.theme.PixAccent
import com.example.pixcam.theme.PixDim
import com.example.pixcam.theme.PixOnDark
import com.example.pixcam.theme.PixSurface
import com.example.pixcam.theme.PixSurfaceHigh

/** How many decoded full-size bitmaps we keep alive while swiping. */
private const val BITMAP_CACHE_SIZE = 3

private val ThumbShape = RoundedCornerShape(12.dp)

/**
 * Bottom-bar thumbnail of the most recent shot. [refresh] is a counter the camera screen
 * bumps after each capture to force a reload; the box keeps its size when there are no
 * photos yet so the bar never reflows.
 */
@Composable
fun LastShotThumb(refresh: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var thumb by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(refresh) {
        val latest = queryLatestPixcamImage(context)
        thumb = latest?.let { loadThumbnail(context, it.uri, 112) }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(ThumbShape)
            .background(PixSurfaceHigh)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Last shot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Full-screen pager over this app's photos, newest first. */
@Composable
fun GalleryViewer(onClose: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        items = queryPixcamImages(context)
        loaded = true
    }

    BackHandler { onClose() }

    val cache = remember { BitmapCache(BITMAP_CACHE_SIZE) }
    val pagerState = rememberPagerState(pageCount = { items.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (items.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                GalleryPage(item = items[page], cache = cache)
            }
        } else if (loaded) {
            Text(
                "No photos yet",
                color = PixDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val current = items.getOrNull(pagerState.currentPage)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(PixSurface.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current?.name ?: "Pixcam",
                    color = PixOnDark,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                if (items.isNotEmpty()) {
                    Text(
                        "${pagerState.currentPage + 1}/${items.size}",
                        color = PixDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            CloseChip(onClick = onClose)
        }
    }
}

@Composable
private fun GalleryPage(item: GalleryItem, cache: BitmapCache) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf(cache[item.uri]) }

    LaunchedEffect(item.uri) {
        if (bitmap == null) {
            val decoded = loadFullBitmap(context, item.uri)
            if (decoded != null) cache[item.uri] = decoded
            bitmap = decoded
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ready = bitmap
        if (ready != null) {
            Image(
                bitmap = ready.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), color = PixAccent)
        }
    }
}

@Composable
private fun CloseChip(onClick: () -> Unit) {
    Text(
        "✕",
        color = PixOnDark,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PixSurface.copy(alpha = 0.8f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * Keeps the last [capacity] decoded pages so swiping back is instant. Evicted bitmaps are
 * dropped rather than recycled: a page still on screen may hold the reference.
 */
private class BitmapCache(private val capacity: Int) {
    private val entries = LinkedHashMap<Uri, Bitmap>(capacity, 0.75f, true)

    operator fun get(uri: Uri): Bitmap? = entries[uri]

    operator fun set(uri: Uri, bitmap: Bitmap) {
        entries[uri] = bitmap
        while (entries.size > capacity) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }
}
