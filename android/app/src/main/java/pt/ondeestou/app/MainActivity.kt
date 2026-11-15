package pt.ondeestou.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private val Verde = Color(0xFF0F3D2E)

data class Resultado(
    val rua: String = "Não disponível",
    val lugar: String = "Não disponível",
    val codigoPostal: String = "Não disponível",
    val freguesia: String = "Não disponível",
    val concelho: String = "Não disponível",
    val distrito: String = "Não disponível",
    val m: Double,
    val p: Double,
    val precisao: Float,
    val latitude: Double,
    val longitude: Double,
    val aviso: String = ""
) {
    fun texto() = """Rua: $rua
Lugar: $lugar
Código postal: $codigoPostal
Freguesia: $freguesia
Concelho: $concelho
Distrito: $distrito
ETRS89 / PT-TM06 (EPSG:3763): M ${"%.2f".format(Locale.US, m)}  P ${"%.2f".format(Locale.US, p)}
Precisão: ± ${precisao.toInt()} m
Latitude: $latitude
Longitude: $longitude"""
}

class MainActivity : ComponentActivity() {
    private var onPermission: (() -> Unit)? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true || it[Manifest.permission.ACCESS_COARSE_LOCATION] == true) onPermission?.invoke()
        else onPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Verde, secondary = Color(0xFF1F6B4F))) {
                OndeEstouScreen(
                    requestLocation = { callback ->
                        onPermission = callback
                        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) callback()
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    getLocation = { obterLocalizacao() }
                )
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun obterLocalizacao(): Location = suspendCancellableCoroutine { cont ->
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            cont.resumeWith(Result.failure(IllegalStateException("Activa a localização/GPS nas definições do Android.")))
            return@suspendCancellableCoroutine
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                manager.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null && cont.isActive) cont.resume(loc)
                    else if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("Não foi possível obter GPS. Tenta ao ar livre.")))
                }
            } else {
                val last = manager.getLastKnownLocation(provider)
                if (last != null && cont.isActive) cont.resume(last)
                else manager.requestSingleUpdate(provider, { loc ->
                    if (cont.isActive) cont.resume(loc)
                }, mainLooper)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }
}

@Composable
private fun OndeEstouScreen(
    requestLocation: ((() -> Unit) -> Unit),
    getLocation: suspend () -> Location
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resultado by remember { mutableStateOf<Resultado?>(null) }
    var estado by remember { mutableStateOf("Pronto para obter a localização.") }
    var ocupado by remember { mutableStateOf(false) }

    fun localizar() {
        if (ocupado) return
        requestLocation {
            scope.launch {
                ocupado = true
                estado = "A obter posição GPS…"
                try {
                    val loc = getLocation()
                    estado = "A consultar DGT e OpenStreetMap…"
                    resultado = withContext(Dispatchers.IO) { consultar(loc) }
                    estado = if (resultado?.aviso.isNullOrBlank()) "Localização actualizada." else resultado!!.aviso
                } catch (e: Exception) {
                    estado = e.message ?: "Ocorreu um erro ao obter a localização."
                } finally { ocupado = false }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("📍 Onde Estou", color = Verde, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Localização GPS e coordenadas oficiais de Portugal", color = Color.Gray, fontSize = 14.sp)
        Button(onClick = { localizar() }, enabled = !ocupado, modifier = Modifier.fillMaxWidth().height(62.dp)) {
            if (ocupado) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("ONDE ESTOU?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(estado, color = Color.DarkGray, fontSize = 13.sp)
        resultado?.let { r ->
            InfoCard("MORADA", listOf("Rua" to r.rua, "Lugar" to r.lugar, "Código postal" to r.codigoPostal))
            InfoCard("LOCALIZAÇÃO ADMINISTRATIVA", listOf("Freguesia" to r.freguesia, "Concelho" to r.concelho, "Distrito" to r.distrito))
            InfoCard("COORDENADAS", listOf("Sistema" to "ETRS89 / PT-TM06, EPSG:3763", "M" to "%.2f".format(Locale.US, r.m), "P" to "%.2f".format(Locale.US, r.p), "Precisão" to "± ${r.precisao.toInt()} m"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Onde Estou", r.texto()))
                    estado = "Informação copiada."
                }, modifier = Modifier.weight(1f)) { Text("Copiar tudo") }
                Button(onClick = {
                    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${r.latitude},${r.longitude}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }, modifier = Modifier.weight(1f)) { Text("Google Maps") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Freguesia, concelho e distrito: DGT, CAOP. Morada: OpenStreetMap. A localização é obtida no dispositivo.", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun InfoCard(titulo: String, linhas: List<Pair<String, String>>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, color = Verde, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            linhas.forEach { (rotulo, valor) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(rotulo, color = Color.Gray, modifier = Modifier.weight(0.8f), fontSize = 13.sp)
                    Text(valor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.2f), fontSize = 14.sp)
                }
            }
        }
    }
}

private fun consultar(loc: Location): Resultado {
    val (m, p) = ptTM06(loc.latitude, loc.longitude)
    var rua = "Não disponível"
    var lugar = "Não disponível"
    var cp = "Não disponível"
    var freg = "Não disponível"
    var conc = "Não disponível"
    var dist = "Não disponível"
    var aviso = ""

    try {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=18&accept-language=pt-PT&lat=${loc.latitude}&lon=${loc.longitude}"
        val j = pedidoJson(url, true)
        val a = j.optJSONObject("address") ?: JSONObject()
        val road = a.optString("road").takeIf { it.isNotBlank() }
        val house = a.optString("house_number").takeIf { it.isNotBlank() }
        rua = listOfNotNull(road, house).joinToString(", ").ifBlank { "Não disponível" }
        lugar = listOf("hamlet", "isolated_dwelling", "locality", "neighbourhood", "quarter", "village", "suburb", "town", "city")
            .firstNotNullOfOrNull { a.optString(it).takeIf(String::isNotBlank) } ?: "Não disponível"
        cp = a.optString("postcode").takeIf { it.isNotBlank() } ?: "Não disponível"
    } catch (_: Exception) { aviso = "Aviso: serviço de moradas indisponível." }

    try {
        val d = 0.00001
        val url = "https://ogcapi.dgterritorio.gov.pt/collections/freguesias/items?f=json&skipGeometry=true&limit=5&bbox=${loc.longitude-d},${loc.latitude-d},${loc.longitude+d},${loc.latitude+d}"
        val feature = pedidoJson(url, false).optJSONArray("features")?.optJSONObject(0)
        val props = feature?.optJSONObject("properties")
        if (props != null) {
            freg = props.optString("freguesia", props.optString("Freguesia", "Não disponível"))
            conc = props.optString("municipio", props.optString("concelho", props.optString("Concelho", "Não disponível")))
            dist = props.optString("distrito_ilha", props.optString("distrito", props.optString("Distrito", "Não disponível")))
        } else aviso = "Aviso: a DGT não devolveu a divisão administrativa."
    } catch (_: Exception) { aviso = "Aviso: serviço DGT indisponível." }

    return Resultado(rua, lugar, cp, freg, conc, dist, m, p, loc.accuracy, loc.latitude, loc.longitude, aviso)
}

private fun pedidoJson(endereco: String, osm: Boolean): JSONObject {
    val connection = URL(endereco).openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    connection.setRequestProperty("Accept", "application/json")
    if (osm) connection.setRequestProperty("User-Agent", "OndeEstouAndroid/1.0 (app de localização pessoal)")
    try {
        if (connection.responseCode !in 200..299) error("Erro HTTP ${connection.responseCode}")
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
}

private fun ptTM06(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
    val a = 6378137.0
    val f = 1.0 / 298.257222101
    val lat0 = Math.toRadians(39.66825833333333)
    val lon0 = Math.toRadians(-8.133108333333334)
    val e2 = f * (2 - f)
    val ep2 = e2 / (1 - e2)
    fun meridiano(phi: Double): Double = a * ((1-e2/4-3*e2*e2/64-5*e2*e2*e2/256)*phi
        -(3*e2/8+3*e2*e2/32+45*e2*e2*e2/1024)*kotlin.math.sin(2*phi)
        +(15*e2*e2/256+45*e2*e2*e2/1024)*kotlin.math.sin(4*phi)
        -(35*e2*e2*e2/3072)*kotlin.math.sin(6*phi))
    val phi = Math.toRadians(latDeg)
    val lam = Math.toRadians(lonDeg)
    val n = a / kotlin.math.sqrt(1-e2*kotlin.math.sin(phi)*kotlin.math.sin(phi))
    val t = kotlin.math.tan(phi).let { it*it }
    val c = ep2*kotlin.math.cos(phi).let { it*it }
    val aa = (lam-lon0)*kotlin.math.cos(phi)
    val x = n*(aa+(1-t+c)*aa*aa*aa/6+(5-18*t+t*t+72*c-58*ep2)*aa*aa*aa*aa*aa/120)
    val y = meridiano(phi)-meridiano(lat0)+n*kotlin.math.tan(phi)*(aa*aa/2+(5-t+9*c+4*c*c)*aa*aa*aa*aa/24+(61-58*t+t*t+600*c-330*ep2)*aa*aa*aa*aa*aa*aa/720)
    return x to y
}
