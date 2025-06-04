@file:OptIn(ExperimentalMaterial3Api::class)

package com.oursprivacy.oursprivacydemo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.oursprivacy.android.opmetrics.OursPrivacyAPI
import org.json.JSONObject

@Composable
fun TrackingPage(navController: NavHostController) {
    val showDialog = remember { mutableStateOf(false) }
    val dialogMessage = remember { mutableStateOf("") }
    val context = LocalContext.current
    val oursprivacy = OursPrivacyAPI.getInstance(context, OURSPRIVACY_PROJECT_TOKEN, true)
    oursprivacy.setServerURL("https://dev-api.oursprivacy.com/api/v1")

    val trackingActions = listOf(
        Triple("Track Green", "Event: \"Track Event with Properties!\"", {
            println("Tracking with properties")
            val properties = JSONObject(mapOf("testInt" to 1))
            oursprivacy.track("Green",  properties)
            oursprivacy.flush()
        }),
        Triple("Identify", "Event: \"Identify\"", {
            println("Identify")
            oursprivacy.identify(oursprivacy.distinctId, null)
            oursprivacy.flush()
        })
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Tracking Calls") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(trackingActions) { (title, message, action) ->
                TrackingButton(title, message, showDialog, dialogMessage, onButtonClick = action)
            }
        }

        if (showDialog.value) {
            AlertDialog(
                onDismissRequest = { showDialog.value = false },
                title = { Text(text = "Tracking Event") },
                text = { Text(dialogMessage.value) },
                confirmButton = {
                    Button(onClick = { showDialog.value = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
