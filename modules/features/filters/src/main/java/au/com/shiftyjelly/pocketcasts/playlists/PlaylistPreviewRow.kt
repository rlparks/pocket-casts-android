package au.com.shiftyjelly.pocketcasts.playlists

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.LocalRippleConfiguration
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RippleConfiguration
import androidx.compose.material.Text
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.components.HorizontalDivider
import au.com.shiftyjelly.pocketcasts.compose.components.TextH40
import au.com.shiftyjelly.pocketcasts.compose.components.TextP50
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.repositories.playlist.PlaylistPreview
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@Composable
fun PlaylistPreviewRow(
    playlist: PlaylistPreview,
    showDivider: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(IntrinsicSize.Min),
    ) {
        val draggableState = remember {
            AnchoredDraggableState(
                initialValue = SwipeToDeleteAnchor.Resting,
            )
        }
        val density = LocalDensity.current
        val windowWidth = LocalWindowInfo.current.containerSize.width
        val textMeasurer = rememberTextMeasurer()
        val deleteText = stringResource(LR.string.delete)
        val draggableAnchors = remember(windowWidth, deleteText, density, textMeasurer) {
            val textMeasureResult = textMeasurer.measure(deleteText, style = TextStyle(fontSize = 15.sp))
            val deleteTextPadding = density.run { 48.dp.toPx() }
            val deleteActionWidth = textMeasureResult.size.width.toFloat() + deleteTextPadding
            val componentWidth = windowWidth.toFloat()

            DraggableAnchors {
                SwipeToDeleteAnchor.Resting at 0f
                SwipeToDeleteAnchor.ShowDelete at -deleteActionWidth
                // Multiplied by 2 due to https://issuetracker.google.com/issues/367660226
                SwipeToDeleteAnchor.Delete at -componentWidth * 2
            }
        }
        SideEffect {
            draggableState.updateAnchors(draggableAnchors)
        }
        LaunchedEffect(draggableState, onDelete) {
            snapshotFlow { draggableState.settledValue }.collectLatest { deleteAnchor ->
                when (deleteAnchor) {
                    SwipeToDeleteAnchor.ShowDelete -> Unit
                    SwipeToDeleteAnchor.Delete -> onDelete()
                    SwipeToDeleteAnchor.Resting -> Unit
                }
            }
        }

        CompositionLocalProvider(
            LocalRippleConfiguration provides DeleteRippleConfiguration,
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .background(Color(0xFFFF4539))
                    .fillMaxHeight()
                    .clickable(
                        role = Role.Button,
                        onClick = onDelete,
                        enabled = draggableState.currentValue != SwipeToDeleteAnchor.Delete,
                    ),
            ) {
                Spacer(
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset {
                            val deleteActionWidth = draggableAnchors.positionOf(SwipeToDeleteAnchor.ShowDelete).absoluteValue
                            val maxOffset = (deleteActionWidth - windowWidth.toFloat()) / 2
                            val dragOffset = draggableState.requireOffset()
                            IntOffset(
                                x = (deleteActionWidth + dragOffset)
                                    .coerceAtLeast(maxOffset)
                                    .roundToInt(),
                                y = 0,
                            )
                        }
                        .padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = deleteText,
                        color = Color.White,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = draggableState.requireOffset().roundToInt(),
                        y = 0,
                    )
                }
                .anchoredDraggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = draggableState.currentValue != SwipeToDeleteAnchor.Delete,
                )
                .background(MaterialTheme.theme.colors.primaryUi02)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                PlaylistArtwork(
                    podcasts = playlist.podcasts,
                    artworkSize = 56.dp,

                )
                Spacer(
                    modifier = Modifier.width(16.dp),
                )
                Column {
                    TextH40(
                        text = playlist.title,
                    )
                    TextP50(
                        text = stringResource(LR.string.smart_playlist),
                        color = MaterialTheme.theme.colors.primaryText02,
                    )
                }
                Spacer(
                    modifier = Modifier.width(16.dp),
                )
                Spacer(
                    modifier = Modifier.weight(1f),
                )
                TextP50(
                    text = "${playlist.episodeCount}",
                    color = MaterialTheme.theme.colors.primaryText02,
                )
                Image(
                    painter = painterResource(IR.drawable.ic_chevron_small_right),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.theme.colors.primaryText02),
                    modifier = Modifier
                        .padding(3.dp)
                        .size(24.dp),
                )
            }
            if (showDivider) {
                HorizontalDivider(startIndent = 16.dp)
            }
        }
    }
}

private enum class SwipeToDeleteAnchor {
    Resting,
    ShowDelete,
    Delete,
}

private val DeleteRippleConfiguration = RippleConfiguration(
    color = Color.White,
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.15f,
        focusedAlpha = 0.15f,
        hoveredAlpha = 0.2f,
        pressedAlpha = 0.4f,
    ),
)

@Preview
@Composable
private fun PlaylistPreviewRowPreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) themeType: Theme.ThemeType,
) {
    AppThemeWithBackground(themeType) {
        Column {
            PlaylistPreviewRow(
                playlist = PlaylistPreview(
                    uuid = "",
                    title = "New Releases",
                    episodeCount = 0,
                    podcasts = emptyList(),
                ),
                showDivider = true,
                onDelete = {},
                modifier = Modifier.fillMaxWidth(),
            )
            PlaylistPreviewRow(
                playlist = PlaylistPreview(
                    uuid = "",
                    title = "In progress",
                    episodeCount = 1,
                    podcasts = List(1) { Podcast(uuid = "$it") },
                ),
                showDivider = true,
                onDelete = {},
                modifier = Modifier.fillMaxWidth(),
            )
            PlaylistPreviewRow(
                playlist = PlaylistPreview(
                    uuid = "",
                    title = "Starred",
                    episodeCount = 328,
                    podcasts = List(4) { Podcast(uuid = "$it") },
                ),
                showDivider = false,
                onDelete = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
