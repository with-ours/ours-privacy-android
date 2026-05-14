@file:OptIn(ExperimentalMaterial3Api::class)

package com.oursprivacy.oursprivacydemo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.oursprivacy.android.opmetrics.OursPrivacyInitOptions


@Composable
fun UtilityPage(navController: NavHostController) {
    val showDialog = remember { mutableStateOf(false) }
    val dialogMessage = remember { mutableStateOf("") }
    val context = LocalContext.current
    val op = remember {
        OursPrivacyAPI(context.applicationContext).also {
            it.initialize(
                OURSPRIVACY_PROJECT_TOKEN,
                OursPrivacyInitOptions.builder().trackAutomaticEvents(true).build()
            )
        }
    }

    val utilityActions = listOf(
        Triple("Reset", "Rotates visitor_id and clears default bags.", {
            op.reset()
        }),
        Triple("Flush", "Forces a flush of the event queue.", {
            op.flush()
        }),
        Triple("Get visitor id", "Logs the current visitor_id.", {
            println("visitor_id = ${op.visitorId}")
        }),
        Triple("Set visitor id (stitch)", "Sets a manual visitor_id and flips is_manually_set_id.", {
            op.setVisitorId("demo-stitched-visitor")
            op.flush()
        })
    )


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Utility Calls") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
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
            items(utilityActions) { (title, message, action) ->
                TrackingButton(title, message, showDialog, dialogMessage, onButtonClick = action)
            }
        }

        if (showDialog.value) {
            AlertDialog(
                onDismissRequest = { showDialog.value = false },
                title = { Text(text = "Utility Event") },
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
