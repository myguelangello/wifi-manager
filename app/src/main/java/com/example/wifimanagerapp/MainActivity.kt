package com.example.wifimanagerapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager

    private var scanReceiver: BroadcastReceiver? = null

    // ---------- Snackbar bridge (Android -> Compose) ----------
    @Volatile
    private var uiMessageDispatcher: ((String) -> Unit)? = null

    private fun showUiMessage(msg: String) {
        uiMessageDispatcher?.invoke(msg)
    }
    // ---------------------------------------------------------

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
                    getCachedResults = { getScanResultsSafely() },
                    onDisconnect = { disconnectFromBoundNetwork() },
                    bindUiMessageDispatcher = { dispatcher -> uiMessageDispatcher = dispatcher }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScanReceiver()
        uiMessageDispatcher = null
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
        runCatching { wifiManager.startScan() }
            .onFailure { showUiMessage("Falha ao iniciar scan (rate limit/estado).") }
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
                    .distinctBy { it.BSSID }
                    .sortedByDescending { it.level }

                onResults(updated)
            }
        }

        registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        val cached = getScanResultsSafely()
            .filter { it.SSID.isNotBlank() }
            .distinctBy { it.BSSID }
            .sortedByDescending { it.level }

        onResults(cached)
    }

    private fun unregisterScanReceiver() {
        scanReceiver?.let { runCatching { unregisterReceiver(it) } }
        scanReceiver = null
    }

    // -------------------------
    // Connect / Disconnect (NetworkSpecifier)
    // -------------------------
    private fun connectToNetwork(scan: ScanResult, passwordOrNull: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showUiMessage("Conexão por NetworkSpecifier requer Android 10+ (API 29).")
            return
        }

        val ssid = scan.SSID
        val caps = scan.capabilities.orEmpty()

        val isOpen = isOpenNetwork(scan)

        // Observação:
        // - WPA3-Personal aparece como "SAE"
        // - WPA2-Personal normalmente contém "WPA" / "WPA2"
        // - Enterprise/EAP -> "EAP" (não suportado neste fluxo)
        val isEnterprise = caps.contains("EAP")
        val supportsWpa3 = caps.contains("SAE")
        val supportsWpa2 = caps.contains("WPA") || caps.contains("WPA2")

        if (isEnterprise) {
            showUiMessage("Rede Enterprise (EAP) não suportada neste modo.")
            return
        }

        try {
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)

            if (!isOpen) {
                val pwd = passwordOrNull.orEmpty()
                if (pwd.length < 8) {
                    showUiMessage("Senha inválida (mínimo 8 caracteres).")
                    return
                }

                // Preferir WPA3 quando disponível; em redes transition, pode funcionar melhor assim.
                when {
                    supportsWpa3 -> {
                        try {
                            specBuilder.setWpa3Passphrase(pwd)
                        } catch (e: IllegalArgumentException) {
                            // Fallback para WPA2 se a ROM/driver não aceitar WPA3 aqui
                            if (supportsWpa2) {
                                specBuilder.setWpa2Passphrase(pwd)
                            } else {
                                throw e
                            }
                        }
                    }

                    supportsWpa2 -> specBuilder.setWpa2Passphrase(pwd)

                    else -> {
                        showUiMessage("Tipo de segurança não suportado: $caps")
                        return
                    }
                }
            }

            val specifier: NetworkSpecifier = specBuilder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            currentNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            currentNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    showUiMessage("Conectado em $ssid")
                }

                override fun onUnavailable() {
                    showUiMessage("Não foi possível conectar em $ssid (cancelado ou falhou).")
                }

                override fun onLost(network: Network) {
                    connectivityManager.bindProcessToNetwork(null)
                    showUiMessage("Conexão perdida: $ssid")
                }
            }

            connectivityManager.requestNetwork(request, currentNetworkCallback!!)

        } catch (e: IllegalArgumentException) {
            showUiMessage("Falha ao conectar: senha/param inválido. (${e.message})")
        } catch (e: SecurityException) {
            showUiMessage("Sem permissão/estado inválido para conectar. (${e.message})")
        } catch (e: Exception) {
            showUiMessage("Erro inesperado ao conectar. (${e.message})")
        }
    }

    private var currentNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private fun disconnectFromBoundNetwork() {
        connectivityManager.bindProcessToNetwork(null)
        currentNetworkCallback?.let { cb -> runCatching { connectivityManager.unregisterNetworkCallback(cb) } }
        currentNetworkCallback = null
        showUiMessage("Desconectado (bind removido).")
    }

    private fun isOpenNetwork(scan: ScanResult): Boolean {
        val caps = scan.capabilities ?: ""
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
        getCachedResults: () -> List<ScanResult>,
        onDisconnect: () -> Unit,
        bindUiMessageDispatcher: ((String) -> Unit) -> Unit
    ) {
        var networks by remember { mutableStateOf(getCachedResults()) }
        var showPwdDialog by remember { mutableStateOf(false) }
        var selectedNetwork by remember { mutableStateOf<ScanResult?>(null) }
        var password by remember { mutableStateOf("") }
        var showPermissionUi by remember { mutableStateOf(false) }

        // novo: mostrar/ocultar senha
        var showPassword by remember { mutableStateOf(false) }

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            bindUiMessageDispatcher { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            showPermissionUi = !canUseWifiScan()
            networks = getCachedResults()
                .filter { it.SSID.isNotBlank() }
                .sortedByDescending { it.level }
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
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
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
                                    showPassword = false
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
                                label = { Text("Senha") },
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha"
                                        )
                                    }
                                }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val scan = selectedNetwork!!

                            // valida antes de fechar o diálogo
                            if (password.length < 8) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Senha inválida (mínimo 8 caracteres).")
                                }
                                return@TextButton
                            }

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
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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