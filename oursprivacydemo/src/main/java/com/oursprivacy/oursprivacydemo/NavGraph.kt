package com.oursprivacy.oursprivacydemo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun NavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    val op = (LocalContext.current.applicationContext as DemoApplication).sdk
    NavHost(
        navController = navController,
        startDestination = "landingPage",
        modifier = modifier
    ) {
        composable("landingPage") { LandingPage(navController) }
        composable("trackingPage") { TrackingPage(navController, op) }
        composable("utilityPage") { UtilityPage(navController, op) }
        composable("gdprPage") { GDPRPage(navController, op) }
    }
}
