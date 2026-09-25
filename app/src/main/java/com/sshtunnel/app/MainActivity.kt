package com.sshtunnel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val ssh = SshManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Screen() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlinx.coroutines.runBlocking { ssh.disconnect() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("22") }
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var state by remember { mutableStateOf<ConnState>(ConnState.Disconnected) }
        val logs = remember { mutableStateListOf<String>() }

        fun addLog(msg: String) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            runOnUiThread { logs.add("[$t] $msg") }
        }

        val busy = state is ConnState.Connecting
        val connected = state is ConnState.Connected

        Scaffold(topBar = { TopAppBar(title = { Text("SSH Tunnel") }) }) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Host") }, singleLine = true,
                    enabled = !busy && !connected,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy && !connected,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username") }, singleLine = true,
                    enabled = !busy && !connected,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy && !connected,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (connected) {
                            scope.launch {
                                ssh.disconnect()
                                state = ConnState.Disconnected
                                addLog("Disconnected")
                            }
                        } else {
                            val p = port.toIntOrNull()
                            if (host.isBlank() || user.isBlank() || p == null) {
                                state = ConnState.Failed("Fill in host, port and username")
                                return@Button
                            }
                            state = ConnState.Connecting
                            scope.launch {
                                try {
                                    ssh.connect(ServerConfig(host.trim(), p, user.trim(), pass)) { addLog(it) }
                                    state = ConnState.Connected(1080)
                                } catch (e: Exception) {
                                    addLog("Error: ${e.message}")
                                    state = ConnState.Failed(e.message ?: "Connection failed")
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        when {
                            busy -> "Connecting…"
                            connected -> "Disconnect"
                            else -> "Connect"
                        }
                    )
                }

                Text(
                    when (val s = state) {
                        is ConnState.Disconnected -> "Not connected"
                        is ConnState.Connecting -> "Connecting to server…"
                        is ConnState.Connected -> "Connected. SOCKS5 proxy at 127.0.0.1:${s.socksPort}"
                        is ConnState.Failed -> "Failed: ${s.reason}"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("Log", style = MaterialTheme.typography.titleSmall)
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        if (logs.isEmpty()) {
                            Text("No activity yet", fontSize = 12.sp)
                        }
                        logs.forEach {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
