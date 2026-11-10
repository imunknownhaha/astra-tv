package com.astratv.player.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astratv.player.BuildConfig
import com.astratv.player.R
import com.astratv.player.core.database.ProfileEntity
import com.astratv.player.core.ui.*
import kotlinx.coroutines.launch
import org.json.JSONArray

private val phoneTabs=listOf("Live TV","Movies","Series","Favorites")
private val phoneIcons=listOf(R.drawable.ic_live,R.drawable.ic_movies,R.drawable.ic_series,R.drawable.ic_saved)

/** Keep TV remote navigation while providing native touch navigation on handheld builds. */
@OptIn(ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryShell(section: String,profile: String,navigationState: LazyListState,navigate: (String)->Unit,content: @Composable ()->Unit) {
    if(!BuildConfig.IS_PHONE) {
        Row(Modifier.fillMaxSize().background(Background).padding(32.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(158.dp).fillMaxHeight()) {
                Text("Astra TV",style=MaterialTheme.typography.headlineSmall,color=Ink)
                Text(profile,style=MaterialTheme.typography.bodyMedium,color=Muted,modifier=Modifier.padding(top=6.dp,bottom=24.dp))
                LazyColumn(state=navigationState,modifier=Modifier.fillMaxSize().focusRestorer().focusGroup(),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(destinations,key={it}) { destination -> TvAction(destination,{ navigate(destination) },Modifier.fillMaxWidth(),section==destination) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        return
    }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    ModalNavigationDrawer(drawerState=drawer,drawerContent={
        ModalDrawerSheet(Modifier.testTag("phone-drawer")) {
            Text("Astra",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(24.dp,20.dp,24.dp,4.dp))
            Text(profile,color=Muted,modifier=Modifier.padding(24.dp,0.dp,24.dp,16.dp))
            LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(destinations,key={it}) { destination -> NavigationDrawerItem(
                    label={ Text(destination) },selected=section==destination,
                    onClick={ scope.launch { drawer.close() };navigate(destination) }) }
            }
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded=maxWidth>=600.dp
            Scaffold(topBar={ TopAppBar(title={ Text("Astra") },navigationIcon={
                IconButton(onClick={ scope.launch { drawer.open() } }) { Icon(painterResource(R.drawable.ic_menu),"Open navigation") }
            }) },bottomBar={ if(!expanded) NavigationBar(Modifier.testTag("phone-navigation")) {
                phoneTabs.forEachIndexed { i,tab -> NavigationBarItem(selected=section==tab,onClick={ navigate(tab) },
                    icon={ Icon(painterResource(phoneIcons[i]),null) },label={ Text(if(tab=="Live TV") "Live" else tab) }) }
            } }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    if(expanded) NavigationRail(Modifier.testTag("phone-navigation")) {
                        phoneTabs.forEachIndexed { i,tab -> NavigationRailItem(selected=section==tab,onClick={ navigate(tab) },
                            icon={ Icon(painterResource(phoneIcons[i]),null) },label={ Text(if(tab=="Live TV") "Live" else tab) }) }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal=16.dp,vertical=8.dp)) { content() }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PhoneCategories(groups: List<String>,profile: ProfileEntity?,vm: MainViewModel) {
    val current by vm.category.collectAsStateWithLifecycle()
    val hidden=remember(profile) { val a=JSONArray(profile?.restrictedGroups ?: "[]");(0 until a.length()).map { a.getString(it) }.toSet() }
    val visible=remember(groups,hidden) { groups.filterNot { it in hidden } }
    LazyRow(contentPadding=PaddingValues(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected=current.isEmpty(),onClick={ vm.category.value="";vm.limit.value=200 },label={ Text("All categories") },modifier=Modifier.heightIn(min=48.dp)) }
        items(visible,key={it}) { group -> FilterChip(selected=current==group,onClick={ vm.category.value=group;vm.limit.value=200 },label={ Text(group) },modifier=Modifier.heightIn(min=48.dp)) }
    }
}
