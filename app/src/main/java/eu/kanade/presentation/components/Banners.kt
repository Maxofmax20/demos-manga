package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMaxBy
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.math.RoundingMode
import java.text.NumberFormat

val DownloadedOnlyBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.tertiary
val IncognitoModeBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.primary
val IndexingBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.secondary

// KMK -->
val RestoringBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.error
val SyncingBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.secondary
val UpdatingBannerBackgroundColor
    @Composable get() = MaterialTheme.colorScheme.tertiary
// KMK <--

@Composable
fun WarningBanner(
    textRes: StringResource,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(textRes),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
            .padding(16.dp),
        color = MaterialTheme.colorScheme.onError,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

// KMK -->
private val percentFormatter = NumberFormat.getPercentInstance().apply {
    roundingMode = RoundingMode.DOWN
    maximumFractionDigits = 0
}
// KMK <--

@Composable
fun AppStateBanners(
    downloadedOnlyMode: Boolean,
    incognitoMode: Boolean,
    indexing: Boolean,
    // KMK -->
    restoring: Boolean,
    syncing: Boolean,
    updating: Boolean,
    // KMK <--
    modifier: Modifier = Modifier,
    // KMK -->
    progress: Float? = null,
    // KMK <--
) {
    val density = LocalDensity.current
    val mainInsets = WindowInsets.statusBars
    val mainInsetsTop = mainInsets.getTop(density)
    SubcomposeLayout(modifier = modifier) { constraints ->
        val indexingPlaceable = subcompose(0) {
            AnimatedVisibility(
                // KMK -->
                visible = indexing || restoring || syncing || updating,
                // KMK <--
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                IndexingDownloadBanner(
                    modifier = Modifier.windowInsetsPadding(mainInsets),
                    // KMK -->
                    text = when {
                        updating -> progress?.let {
                            stringResource(
                                MR.strings.notification_updating_progress,
                                percentFormatter.format(it),
                            )
                        } ?: stringResource(MR.strings.updating_library)
                        syncing -> progress?.let {
                            stringResource(MR.strings.syncing_library) + " (${percentFormatter.format(it)})"
                        } ?: stringResource(MR.strings.syncing_library)
                        restoring -> progress?.let {
                            stringResource(MR.strings.restoring_backup) + " (${percentFormatter.format(it)})"
                        } ?: stringResource(MR.strings.restoring_backup)
                        else -> stringResource(MR.strings.download_notifier_cache_renewal)
                    },
                    // KMK <--
                    compact = updating,
                    progress = progress,
                )
            }
        }.fastMap { it.measure(constraints) }
        val indexingHeight = indexingPlaceable.fastMaxBy { it.height }?.height ?: 0

        val downloadedOnlyPlaceable = subcompose(1) {
            AnimatedVisibility(
                visible = downloadedOnlyMode,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (mainInsetsTop - indexingHeight).coerceAtLeast(0)
                DownloadedOnlyModeBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val downloadedOnlyHeight = downloadedOnlyPlaceable.fastMaxBy { it.height }?.height ?: 0

        val incognitoPlaceable = subcompose(2) {
            AnimatedVisibility(
                visible = incognitoMode,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                val top = (mainInsetsTop - indexingHeight - downloadedOnlyHeight).coerceAtLeast(0)
                IncognitoModeBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets(top = top)),
                )
            }
        }.fastMap { it.measure(constraints) }
        val incognitoHeight = incognitoPlaceable.fastMaxBy { it.height }?.height ?: 0

        layout(constraints.maxWidth, indexingHeight + downloadedOnlyHeight + incognitoHeight) {
            indexingPlaceable.fastForEach {
                it.place(0, 0)
            }
            downloadedOnlyPlaceable.fastForEach {
                it.place(0, indexingHeight)
            }
            incognitoPlaceable.fastForEach {
                it.place(0, indexingHeight + downloadedOnlyHeight)
            }
        }
    }
}

@Composable
private fun DownloadedOnlyModeBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.strings.label_downloaded_only),
        modifier = Modifier
            .background(DownloadedOnlyBannerBackgroundColor)
            .fillMaxWidth()
            .padding(4.dp)
            .then(modifier),
        color = MaterialTheme.colorScheme.onTertiary,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun IncognitoModeBanner(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(MR.strings.pref_incognito_mode),
        modifier = Modifier
            .background(IncognitoModeBannerBackgroundColor)
            .fillMaxWidth()
            .padding(4.dp)
            .then(modifier),
        color = MaterialTheme.colorScheme.onPrimary,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun IndexingDownloadBanner(
    modifier: Modifier = Modifier,
    text: String = stringResource(MR.strings.download_notifier_cache_renewal),
    compact: Boolean = false,
    progress: Float? = null,
) {
    val background = if (compact) MaterialTheme.colorScheme.surfaceVariant else IndexingBannerBackgroundColor
    val foreground = if (compact) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondary
    val normalizedProgress = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (!compact) {
                CircularProgressIndicator(
                    modifier = Modifier.requiredSize(16.dp),
                    color = foreground,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = foreground,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (compact) {
            if (normalizedProgress != null) {
                LinearProgressIndicator(
                    progress = { normalizedProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}
