package dev.koga.hopi.feature.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.koga.hopi.feature.details.component.GameInfoCard
import dev.koga.hopi.feature.details.component.ScreenshotsUI
import dev.koga.hopi.feature.details.component.toList
import dev.koga.hopi.model.GameDetails
import dev.koga.hopi.util.ext.fullLine


@Composable
fun GameDetailsScreen(
    viewModel: GameDetailsViewModel,
    onBack: () -> Unit,
) {

    val gameState by viewModel.gameState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { contentPadding ->
        AnimatedContent(
            modifier = Modifier.padding(contentPadding),
            targetState = gameState,
            contentKey = { gameState::class }
        ) { target ->
            when (target) {
                GameDetailsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(modifier = Modifier.align(alignment = Alignment.Center))
                    }
                }

                GameDetailsUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            modifier = Modifier.align(alignment = Alignment.Center),
                            text = "Error!"
                        )
                    }
                }

                is GameDetailsUiState.Success -> {
                    val game = (gameState as GameDetailsUiState.Success).data
                    val info = game.toList()
                    val minimumSystemRequirements = game.minimumSystemRequirements.toList()

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        fullLine {
                            Spacer(modifier = Modifier.statusBarsPadding())
                        }

                        fullLine {
                            GameHeaderUI(game)
                        }

                        items(count = info.size) { index ->
                            GameInfoCard(info = info[index])
                        }

                        fullLine {
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        fullLine {
                            Text(
                                text = "Screenshots",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        fullLine {
                            ScreenshotsUI(screenshots = game.screenshots)
                        }

                        fullLine {
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        fullLine {
                            Text(
                                text = "Minimum System Requirements",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        items(
                            count = minimumSystemRequirements.size,
                            span = { GridItemSpan(maxLineSpan) }
                        ) { index ->
                            GameInfoCard(info = minimumSystemRequirements[index])
                        }

                        fullLine {
                            Spacer(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameHeaderUI(game: GameDetails) {
    var descriptionExpanded by remember {
        mutableStateOf(false)
    }

    Column {
        AsyncImage(
            model = game.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 196.dp)
                .clip(RoundedCornerShape(12.dp)),
            clipToBounds = false,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = game.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )

            Badge(
                containerColor = MaterialTheme.colorScheme.primary.copy(
                    alpha = .1f
                ),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    modifier = Modifier.padding(
                        horizontal = 4.dp,
                        vertical = 2.dp
                    ),
                    text = game.status,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold

                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = game.description.replace("<[^>]*>".toRegex(), ""),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
        )

        TextButton(
            modifier = Modifier.align(alignment = Alignment.End),
            onClick = {
                descriptionExpanded = !descriptionExpanded
            },
        ) {
            Text(
                text = if (descriptionExpanded) "less" else "more"
            )
        }
    }
}

