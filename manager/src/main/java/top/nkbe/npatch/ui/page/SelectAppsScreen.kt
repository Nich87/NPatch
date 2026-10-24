package top.nkbe.npatch.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.R
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.ui.component.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SelectAppsScreen(
    initialSelected: List<AppInfo>,
    onConfirm: (List<AppInfo>) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = viewModel { SelectAppsViewModel() }
    val title = stringResource(R.string.screen_select_modules)
    val searchStatus = remember { SearchStatus(title) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val pullToRefreshState = rememberPullToRefreshState()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }
    val filtered = remember(viewModel.appList, searchStatus.searchText) {
        viewModel.appList.filter {
            it.label.contains(searchStatus.searchText, ignoreCase = true) ||
                it.app.packageName.contains(searchStatus.searchText, ignoreCase = true)
        }
    }
    LaunchedEffect(Unit) { viewModel.initialize(initialSelected) }
    BackHandler(onBack = onBack)

    NPatchScaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                NPatchTopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.patch_return))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            val addLabel = stringResource(R.string.add)
            FloatingActionButton(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = addLabel
                },
                onClick = { if (!viewModel.isRefreshing) onConfirm(viewModel.multiSelected.toList()) },
            ) {
                Icon(Icons.Outlined.Done, null, tint = MiuixTheme.colorScheme.onPrimary)
            }
        },
        popupHost = { searchStatus.SearchPager(searchBarTopPadding = dynamicTopPadding) },
    ) { innerPadding ->
        searchStatus.SearchBox(
            searchBarTopPadding = dynamicTopPadding,
            contentPadding = innerPadding,
        ) { boxHeight ->
            val padding = PaddingValues(
                top = innerPadding.calculateTopPadding() + boxHeight,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            )
            PullToRefresh(
                isRefreshing = viewModel.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = { scope.launch { viewModel.refresh() } },
                contentPadding = padding,
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
                    contentPadding = padding,
                    overscrollEffect = null,
                ) {
                    items(filtered, key = { it.app.packageName }) { app ->
                        val checked = viewModel.multiSelected.any { it.app.packageName == app.app.packageName }
                        AppItem(
                            modifier = Modifier.animateItem(),
                            icon = {
                                Image(
                                    bitmap = NeoPackageManager.getIcon(app),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                                )
                            },
                            label = app.label,
                            packageName = app.app.packageName,
                            checked = checked,
                            trailingContent = {
                                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = null)
                            },
                            onClick = {
                                if (checked) viewModel.multiSelected.removeAll { it.app.packageName == app.app.packageName }
                                else viewModel.multiSelected.add(app)
                            },
                        )
                    }
                    if (filtered.isEmpty() && !viewModel.isRefreshing) {
                        item {
                            Text(
                                text = viewModel.error ?: stringResource(R.string.patch_embed_modules_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                    }
                }
                if (viewModel.isRefreshing && viewModel.appList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun AppItem(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    packageName: String,
    checked: Boolean,
    trailingContent: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    modifier = Modifier.basicMarquee(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight(600),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = packageName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(500),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            trailingContent()
        }
    }
}

private class SelectAppsViewModel : ViewModel() {
    var isRefreshing by mutableStateOf(false)
        private set
    var appList by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val multiSelected = mutableStateListOf<AppInfo>()
    private var importedModules = emptyList<AppInfo>()

    suspend fun initialize(selected: List<AppInfo>) {
        importedModules = selected.filter { File(it.app.sourceDir).parentFile == lspApp.tmpApkDir }
        multiSelected.clear()
        multiSelected.addAll(selected)
        refresh(force = false)
    }

    suspend fun refresh(force: Boolean = true) {
        if (isRefreshing) return
        isRefreshing = true
        error = null
        try {
            if (NeoPackageManager.appList.isEmpty() || force) NeoPackageManager.fetchAppList()
            appList = (importedModules + NeoPackageManager.appList.filter { it.isXposedModule })
                .distinctBy { it.app.packageName }
                .sortedWith(compareBy(Collator.getInstance(), AppInfo::label))
            val selectedPackages = multiSelected.map { it.app.packageName }.toSet()
            multiSelected.clear()
            multiSelected.addAll(appList.filter { it.app.packageName in selectedPackages })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            isRefreshing = false
        }
    }
}
