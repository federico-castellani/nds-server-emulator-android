package io.github.federicocastellani.ndsstation.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.data.GameInfo
import io.github.federicocastellani.ndsstation.emulation.RomBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val coverColors = listOf(
    Color(0xFF3F51B5), Color(0xFF8764B8), Color(0xFF00786E), Color(0xFFC42B1C),
    Color(0xFFCA5010), Color(0xFF107C10), Color(0xFF005B70), Color(0xFFB146C2),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(modifier: Modifier, onPlay: (GameInfo) -> Unit, onSessionExpired: () -> Unit) {
    val app = NdsApp.instance
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<GameInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cacheVersion by remember { mutableStateOf(0) }
    var coreMessage by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        refreshing = true
        error = null
        try {
            games = app.api.games()
        } catch (e: ApiException) {
            if (e.isUnauthorized) onSessionExpired() else error = e.message
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { load() }

    coreMessage?.let { MessageDialog("Emulatore non disponibile", it) { coreMessage = null } }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load() } }, modifier = modifier.fillMaxSize()) {
        val visible = games.orEmpty().filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Cerca un gioco") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Cancella") } },
                    singleLine = true, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Impossibile caricare la libreria", style = MaterialTheme.typography.titleSmall)
                            Text(message)
                            Button(onClick = { scope.launch { load() } }) { Text("Riprova") }
                        }
                    }
                }
            }
            if (games != null && visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.SportsEsports, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Text(if (query.isBlank()) "Nessun gioco disponibile" else "Nessun risultato", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (query.isNotBlank()) "Nessun gioco corrisponde a “$query”."
                            else if (app.settings.isAdmin) "Carica una ROM dalla sezione Amministrazione."
                            else "Chiedi a un amministratore di aggiungere dei giochi.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(visible, key = { it.id }) { game ->
                GameCard(
                    game, cacheVersion,
                    onPlay = {
                        if (!app.coreAvailable) coreMessage = app.coreError ?: "Core di emulazione non disponibile."
                        else onPlay(game)
                    },
                    onRemoveFromCache = {
                        app.store.deleteCachedRom(game.id)
                        cacheVersion++
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(game: GameInfo, cacheVersion: Int, onPlay: () -> Unit, onRemoveFromCache: () -> Unit) {
    val app = NdsApp.instance
    var menu by remember { mutableStateOf(false) }
    val cached = remember(game.id, cacheVersion) { app.store.isRomCached(game.id) }
    val banner by produceState<RomBanner?>(null, game.id, cacheVersion) {
        value = if (cached) withContext(Dispatchers.IO) { RomBanner.read(app.store.romFile(game.id)) } else null
    }
    val color = coverColors[Math.floorMod((game.id + game.name).hashCode(), coverColors.size)]
    val initials = game.name.split(' ').filter { it.isNotBlank() }.let { words ->
        when (words.size) {
            0 -> "?"
            1 -> words[0].take(2).uppercase()
            else -> words.take(2).joinToString("") { it.first().uppercase() }
        }
    }

    Box {
        ElevatedCard(Modifier.fillMaxWidth().clip(CardDefaults.elevatedShape).combinedClickable(onClick = onPlay, onLongClick = { menu = true })) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.25f)
                    .background(Brush.linearGradient(listOf(color, color.copy(red = color.red * 0.55f, green = color.green * 0.55f, blue = color.blue * 0.55f)))),
                contentAlignment = Alignment.Center,
            ) {
                val icon = banner?.icon
                if (icon != null) {
                    Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)) {
                        Image(
                            icon.asImageBitmap(), contentDescription = null,
                            filterQuality = FilterQuality.None, modifier = Modifier.padding(6.dp).size(72.dp),
                        )
                    }
                } else {
                    Text(initials, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                }
                if (cached) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f), shape = CircleShape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) { Icon(Icons.Outlined.CloudDone, "Disponibile offline", Modifier.padding(4.dp).size(16.dp), tint = Color.White) }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(game.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    banner?.subtitle ?: "Nintendo DS", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Gioca") }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(
                text = { Text("Rimuovi dalla cache") }, enabled = cached,
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = { menu = false; onRemoveFromCache() },
            )
        }
    }
}
