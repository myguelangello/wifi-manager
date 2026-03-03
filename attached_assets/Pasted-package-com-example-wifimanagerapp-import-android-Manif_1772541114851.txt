package com.example.wifimanagerapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager

    private var scanReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiManager = applicationContext.getSystemService()!!
        connectivityManager = applicationContext.getSystemService()!!

        setContent {
            MaterialTheme {
                WifiScreen(
                    onRequestScan = { startWifiScan() },
                    onConnect = { scan, passwordOrNull ->
                        connectToNetwork(scan, passwordOrNull)
                    },
                    onOpenLocationSettings = { openLocationSettings() },
                    canUseWifiScan = { hasRequiredPermissions() },
                    registerScanUpdates = { onResults -> registerScanReceiver(onResults) },
                    unregisterScanUpdates = { unregisterScanReceiver() },
                    getCachedResults = { getScanResultsSafely() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScanReceiver()
    }

    // -------------------------
    // Permissions
    // -------------------------
    private fun hasRequiredPermissions(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        return hasFine && hasNearby
    }

    private fun requiredPermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    // -------------------------
    // Wi-Fi Scan
    // -------------------------
    private fun startWifiScan() {
        // startScan pode falhar por rate limit; mesmo assim, você pode ler resultados cacheados.
        runCatching { wifiManager.startScan() }
    }

    private fun getScanResultsSafely(): List<ScanResult> {
        return try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun registerScanReceiver(onResults: (List<ScanResult>) -> Unit) {
        if (scanReceiver != null) return

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val updated = getScanResultsSafely()
                    .filter { it.SSID.isNotBlank() }
                    .distinctBy { it.BSSID } // pode ajustar para SSID se preferir
                    .sortedByDescending { it.level }

                onResults(updated)
            }
        }

        registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Entrega resultados iniciais (cacheados) para não ficar “tela vazia”
        val cached = getScanResultsSafely()
            .filter { it.SSID.isNotBlank() }
            .distinctBy { it.BSSID }
            .sortedByDescending { it.level }

        onResults(cached)
    }

    private fun unregisterScanReceiver() {
        scanReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        scanReceiver = null
    }

    // -------------------------
    // Connect / Disconnect (NetworkSpecifier)
    // -------------------------
    private fun connectToNetwork(scan: ScanResult, passwordOrNull: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val isOpen = isOpenNetwork(scan)
        val ssid = scan.SSID

        val specBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)

        if (!isOpen) {
            // WPA2/WPA3: para exemplo, usamos passphrase. (A API também tem setWpa2Passphrase / setWpa3Passphrase)
            val pwd = passwordOrNull.orEmpty()
            if (pwd.isBlank()) return
            specBuilder.setWpa2Passphrase(pwd)
        }

        val specifier = specBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        // Remove callback anterior para evitar “conexões penduradas”
        currentNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        currentNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Bind para que o tráfego do app use esta rede Wi-Fi
                connectivityManager.bindProcessToNetwork(network)
            }

            override fun onUnavailable() {
                // Falhou / usuário cancelou / senha errada / etc
            }

            override fun onLost(network: Network) {
                // Se perdeu, desfaz o bind
                connectivityManager.bindProcessToNetwork(null)
            }
        }

        connectivityManager.requestNetwork(request, currentNetworkCallback!!)
    }

    private var currentNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private fun disconnectFromBoundNetwork() {
        // “Desconectar” aqui significa: remover o bind do processo e cancelar a request.
        connectivityManager.bindProcessToNetwork(null)
        currentNetworkCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        currentNetworkCallback = null
    }

    private fun isOpenNetwork(scan: ScanResult): Boolean {
        val caps = scan.capabilities ?: ""
        // redes abertas geralmente não têm WPA/WEP/SAE/EAP etc
        return !(caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("EAP"))
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    // -------------------------
    // Compose UI
    // -------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WifiScreen(
        onRequestScan: () -> Unit,
        onConnect: (ScanResult, String?) -> Unit,
        onOpenLocationSettings: () -> Unit,
        canUseWifiScan: () -> Boolean,
        registerScanUpdates: ((List<ScanResult>) -> Unit) -> Unit,
        unregisterScanUpdates: () -> Unit,
        getCachedResults: () -> List<ScanResult>
    ) {
        var networks by remember { mutableStateOf(getCachedResults()) }
        var showPwdDialog by remember { mutableStateOf(false) }
        var selectedNetwork by remember { mutableStateOf<ScanResult?>(null) }
        var password by remember { mutableStateOf("") }
        var showPermissionUi by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            showPermissionUi = !canUseWifiScan()
            // Atualiza lista ao conceder
            networks = getCachedResults().filter { it.SSID.isNotBlank() }.sortedByDescending { it.level }
            onRequestScan()
        }

        DisposableEffect(Unit) {
            registerScanUpdates { results -> networks = results }
            onDispose { unregisterScanUpdates() }
        }

        LaunchedEffect(Unit) {
            showPermissionUi = !canUseWifiScan()
            if (!canUseWifiScan()) {
                permissionLauncher.launch(requiredPermissions())
            } else {
                onRequestScan()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Wi-Fi Manager") },
                    actions = {
                        IconButton(onClick = {
                            if (!canUseWifiScan()) {
                                permissionLauncher.launch(requiredPermissions())
                            } else {
                                onRequestScan()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (showPermissionUi) {
                    PermissionBanner(
                        onRequest = { permissionLauncher.launch(requiredPermissions()) },
                        onOpenLocationSettings = onOpenLocationSettings
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(networks) { scan ->
                        WifiRow(
                            ssid = scan.SSID,
                            rssi = scan.level,
                            secured = !isOpenNetwork(scan),
                            onClick = {
                                if (!canUseWifiScan()) {
                                    permissionLauncher.launch(requiredPermissions())
                                    return@WifiRow
                                }
                                selectedNetwork = scan
                                if (!isOpenNetwork(scan)) {
                                    password = ""
                                    showPwdDialog = true
                                } else {
                                    onConnect(scan, null)
                                }
                            }
                        )
                    }
                }
            }

            if (showPwdDialog && selectedNetwork != null) {
                AlertDialog(
                    onDismissRequest = { showPwdDialog = false },
                    title = { Text("Conectar em ${selectedNetwork!!.SSID}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Digite a senha do Wi-Fi:")
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text("Senha") }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val scan = selectedNetwork!!
                            showPwdDialog = false
                            onConnect(scan, password)
                        }) { Text("Conectar") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPwdDialog = false }) { Text("Cancelar") }
                    }
                )
            }
        }
    }

    @Composable
    private fun PermissionBanner(
        onRequest: () -> Unit,
        onOpenLocationSettings: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Permissões necessárias para listar redes Wi-Fi",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Conceda Localização (e, no Android 13+, Dispositivos Wi-Fi Próximos). " +
                        "Alguns aparelhos também exigem Localização ligada no sistema."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRequest) { Text("Solicitar permissões") }
                    OutlinedButton(onClick = onOpenLocationSettings) { Text("Abrir Localização") }
                }
            }
        }
    }

    @Composable
    private fun WifiRow(
        ssid: String,
        rssi: Int,
        secured: Boolean,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ssid, style = MaterialTheme.typography.titleMedium)
                    Text("RSSI: $rssi dBm", style = MaterialTheme.typography.bodyMedium)
                }
                if (secured) {
                    Icon(Icons.Default.Lock, contentDescription = "Protegida")
                }
            }
        }
    }
}