package top.nkbe.npatch.ui.component

import android.R
import top.nkbe.npatch.ui.util.LocalCardBackgroundAlpha
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.nkbe.npatch.ui.util.LocalBackgroundImagePath

@Stable
class SearchStatus(val label: String) {
    var searchText by mutableStateOf("")
    var current by mutableStateOf(Status.COLLAPSED)

    var offsetY by mutableStateOf(0.dp)

    fun isExpand() = current == Status.EXPANDED
    fun isCollapsed() = current == Status.COLLAPSED
    fun shouldExpand() = current == Status.EXPANDED || current == Status.EXPANDING
    fun shouldCollapsed() = current == Status.COLLAPSED || current == Status.COLLAPSING
    fun isAnimatingExpand() = current == Status.EXPANDING

    fun onAnimationComplete() {
        current = when (current) {
            Status.EXPANDING -> Status.EXPANDED
            Status.COLLAPSING -> {
                searchText = ""
                Status.COLLAPSED
            }

            else -> current
        }
    }

    @Composable
    fun TopAppBarAnim(
        content: @Composable () -> Unit
    ) {
        val visible = shouldCollapsed()
        val topAppBarAlpha by animateFloatAsState(
            if (visible) 1f else 0f,
            animationSpec = tween(if (visible) 300 else 0, easing = FastOutSlowInEasing),
        )
        Box(
            modifier = Modifier.alpha(topAppBarAlpha)
        ) {
            content()
        }
    }

    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
}

@Composable
fun SearchStatus.SearchBox(
    searchBarTopPadding: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (Dp) -> Unit
) {
    val searchStatus = this
    val density = LocalDensity.current

    val boxHeight = remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
            .offset(y = contentPadding.calculateTopPadding())
            .onGloballyPositioned {
                with(density) {
                    searchStatus.offsetY = it.positionInWindow().y.toDp()
                    boxHeight.value = it.size.height.toDp()
                }
            }
    ) {
        SearchBarFake(
            label = searchStatus.label,
            searchBarTopPadding = searchBarTopPadding,
            innerPadding = contentPadding,
            onClick = { searchStatus.current = SearchStatus.Status.EXPANDING },
        )
    }
    Box {
        content(boxHeight.value)
    }
}

@Composable
fun SearchStatus.SearchPager(searchBarTopPadding: Dp = 12.dp) {
    val searchStatus = this
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val hasBackgroundImage = LocalBackgroundImagePath.current.isNotEmpty()
    val topPadding by animateDpAsState(
        targetValue = if (searchStatus.shouldExpand()) {
            systemBarsPadding + 5.dp
        } else {
            max(searchStatus.offsetY, 0.dp)
        },
        animationSpec = tween(300, easing = LinearOutSlowInEasing)
    ) {
        searchStatus.onAnimationComplete()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!searchStatus.isCollapsed()) {
                        if (hasBackgroundImage) {
                            Modifier.background(colorScheme.surface.copy(alpha = LocalCardBackgroundAlpha.current))
                        } else {
                            Modifier.background(colorScheme.surface)
                        }
                    } else Modifier
                )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topPadding),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!searchStatus.isCollapsed()) {
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        SearchBar(searchStatus, searchBarTopPadding)
                    }
                }
                AnimatedVisibility(
                    visible = searchStatus.isExpand() || searchStatus.isAnimatingExpand(),
                    enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                    exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it })
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = searchBarTopPadding, bottom = 6.dp)
                            .padding(start = 4.dp, end = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary,
                            modifier = Modifier
                                .semantics { role = Role.Button }
                                .clickable(
                                    interactionSource = null,
                                    enabled = searchStatus.isExpand(),
                                    indication = null
                                ) {
                                    searchStatus.searchText = ""
                                    searchStatus.current = SearchStatus.Status.COLLAPSING
                                }
                        )
                    }
                    BackHandler {
                        searchStatus.searchText = ""
                        searchStatus.current = SearchStatus.Status.COLLAPSING
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    searchStatus: SearchStatus,
    searchBarTopPadding: Dp = 12.dp,
) {
    val focusRequester = remember { FocusRequester() }
    val clearLabel = stringResource(top.nkbe.npatch.R.string.accessibility_clear)

    InputField(
        query = searchStatus.searchText,
        onQueryChange = { searchStatus.searchText = it },
        label = "",
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                searchStatus.searchText.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    tint = colorScheme.onSurface,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 8.dp, end = 16.dp)
                        .clearAndSetSemantics {
                            contentDescription = clearLabel
                        }
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) {
                            searchStatus.searchText = ""
                        },
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 6.dp)
            .focusRequester(focusRequester),
        onSearch = { },
        expanded = searchStatus.shouldExpand(),
        onExpandedChange = {
            searchStatus.current = if (it) SearchStatus.Status.EXPANDED else SearchStatus.Status.COLLAPSED
        }
    )
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun SearchBarFake(
    label: String,
    searchBarTopPadding: Dp = 12.dp,
    innerPadding: PaddingValues = PaddingValues(0.dp),
    onClick: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection)
            )
            .padding(top = searchBarTopPadding, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            }
            .clickable(onClick = onClick)
    ) {
        InputField(
            query = "",
            onQueryChange = { },
            label = label,
            leadingIcon = {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 16.dp, end = 8.dp),
                    tint = colorScheme.onSurfaceContainerHigh,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
            onSearch = { },
            enabled = false,
            expanded = false,
            onExpandedChange = { }
        )
    }
}
