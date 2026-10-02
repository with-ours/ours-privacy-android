@file:OptIn(ExperimentalMaterial3Api::class)

package com.oursprivacy.oursprivacydemo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController


@Composable
fun LandingPage(navController: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "OursPrivacy") }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp)) {
            for ((label, route) in listOf(
                "Tracking" to "trackingPage",
                "Utility" to "utilityPage",
                "GDPR" to "gdprPage",
            )) {
                Button(
                    onClick = { navController.navigate(route) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(red = 123, green = 128, blue = 255)),
                ) {
                    Text(label)
                }
            }
        }
    }
}
