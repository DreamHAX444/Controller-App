package com.livetracker.controller.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.livetracker.controller.navigation.*
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    onLogout: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory? = null
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // When on Map, fullscreen map takes priority: hide top & bottom bars and disable drawer gestures
    val isMap = currentDestination?.route == Destination.Map.route

    val allDestinations = remember {
        primaryDestinations + serviceDestinations + adminDestinations + systemDestinations
    }
    var currentHeaderTitle by remember { mutableStateOf("Trace") }
    LaunchedEffect(currentDestination?.route) {
        val route = currentDestination?.route
        if (route != null && route != Destination.Map.route) {
            allDestinations.find { it.route == route }?.let {
                currentHeaderTitle = it.title
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // When on Map: do not intercept map panning/swiping when the drawer is closed.
    // Only enable gestures when the drawer is open so the user can fluidly swipe it closed.
    // On other screens, gestures are always enabled.
    val gesturesEnabled = if (isMap) {
        drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
    } else {
        true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            TraceAppDrawer(
                currentRoute = currentDestination?.route,
                onNavigate = { dest ->
                    scope.launch { drawerState.close() }
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                },
                onCloseDrawer = {
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                AnimatedVisibility(
                    visible = !isMap,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it }
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing),
                        targetOffsetY = { -it }
                    ) + fadeOut(animationSpec = tween(180))
                ) {
                    TopAppBar(
                        title = { 
                            Text(currentHeaderTitle)
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = onLogout) {
                                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Log out")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !isMap,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { it }
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing),
                        targetOffsetY = { it }
                    ) + fadeOut(animationSpec = tween(180))
                ) {
                    NavigationBar {
                        primaryDestinations.forEach { dest ->
                            NavigationBarItem(
                                icon = { Icon(dest.icon, contentDescription = dest.title) },
                                label = { Text(dest.title) },
                                selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            TrackMateNavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                viewModelFactory = viewModelFactory,
                onOpenDrawer = { scope.launch { drawerState.open() } }
            )
        }
    }
}
