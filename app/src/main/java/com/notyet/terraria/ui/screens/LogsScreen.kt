package com.notyet.terraria.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogsScreen(logs: List<String>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Back", color = Color.White)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = Color.Green,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
