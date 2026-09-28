package com.gestaoconsultas.webview

import androidx.activity.ComponentActivity
import com.google.home.ConnectivityState
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.matter.standard.BooleanState
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.ContactSensorDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.GenericSwitchDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.OnOffSensorDevice
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.TemperatureSensorDevice
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatDevice
import com.google.home.matter.standard.WindowCovering
import com.google.home.matter.standard.WindowCoveringDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class GoogleHomeBridge(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val emit: (String, String) -> Unit,
) {
    private val registry = FactoryRegistry(
        types = listOf(
            ColorTemperatureLightDevice,
            ContactSensorDevice,
            DimmableLightDevice,
            ExtendedColorLightDevice,
            GenericSwitchDevice,
            OnOffLightDevice,
            OnOffLightSwitchDevice,
            OnOffPluginUnitDevice,
            OnOffSensorDevice,
            TemperatureSensorDevice,
            ThermostatDevice,
            WindowCoveringDevice,
        ),
        traits = listOf(
            BooleanState,
            LevelControl,
            OnOff,
            TemperatureMeasurement,
            Thermostat,
            WindowCovering,
        ),
    )

    private val client: HomeClient = Home.getClient(
        activity.applicationContext,
        HomeConfig(
            coroutineContext = Dispatchers.IO,
            factoryRegistry = registry,
            homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1,
        ),
    )

    @Volatile private var permissionState: PermissionsState = PermissionsState.PERMISSIONS_STATE_UNINITIALIZED
    private val deviceCache = ConcurrentHashMap<String, HomeDevice>()

    init {
        client.registerActivityResultCallerForPermissions(activity)
        scope.launch {
            client.hasPermissions().collectLatest { state ->
                permissionState = state
                emit("permission", JSONObject().put("state", state.name).toString())
                if (state == PermissionsState.GRANTED) {
                    runCatching { emitSnapshot() }
                }
            }
        }
    }

    fun statusJson(): String = JSONObject()
        .put("available", true)
        .put("permission", permissionState.name)
        .put("granted", permissionState == PermissionsState.GRANTED)
        .toString()

    fun requestPermissions() {
        scope.launch {
            try {
                val result = client.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH)
                val payload = JSONObject()
                    .put("status", result.status.name)
                    .put("state", permissionState.name)
                if (result.errorMessage != null) payload.put("error", result.errorMessage)
                emit("permission", payload.toString())
                if (result.status == PermissionsResultStatus.SUCCESS) {
                    delay(200)
                    emitSnapshot()
                }
            } catch (e: Exception) {
                emitError("Não foi possível abrir a autorização Google Home.", e)
            }
        }
    }

    fun snapshot() {
        scope.launch {
            try {
                emitSnapshot()
            } catch (e: Exception) {
                emitError("Não foi possível ler a casa Google Home.", e)
            }
        }
    }

    fun command(json: String) {
        scope.launch {
            var deviceId = ""
            try {
                val input = JSONObject(json)
                deviceId = input.optString("deviceId")
                val action = input.optString("action")
                val device = deviceCache[deviceId] ?: throw IllegalStateException("Dispositivo não encontrado. Atualiza a casa.")

                when (action) {
                    "onOff" -> commandOnOff(device, input.optBoolean("value"))
                    "brightness" -> commandBrightness(device, input.optDouble("value").roundToInt())
                    "position" -> commandPosition(device, input.optDouble("value").roundToInt())
                    else -> throw IllegalArgumentException("Comando Google Home não suportado: " + action)
                }

                emit("command", JSONObject().put("ok", true).put("deviceId", deviceId).put("action", action).toString())
                delay(350)
                emitSnapshot()
            } catch (e: Exception) {
                emit(
                    "command",
                    JSONObject()
                        .put("ok", false)
                        .put("deviceId", deviceId)
                        .put("error", e.message ?: e.javaClass.simpleName)
                        .toString(),
                )
            }
        }
    }

    private suspend fun commandOnOff(device: HomeDevice, value: Boolean) {
        val trait = liveTraits(device).filterIsInstance<OnOff>().firstOrNull()
            ?: throw IllegalStateException("Este dispositivo não expõe On/Off.")
        if (value) trait.on() else trait.off()
    }

    private suspend fun commandBrightness(device: HomeDevice, percent: Int) {
        val trait = liveTraits(device).filterIsInstance<LevelControl>().firstOrNull()
            ?: throw IllegalStateException("Este dispositivo não expõe controlo de intensidade.")
        val level = ((percent.coerceIn(1, 100) / 100.0) * 254.0).roundToInt().coerceIn(1, 254).toUByte()
        trait.moveToLevelWithOnOff(
            level = level,
            transitionTime = null,
            optionsMask = LevelControlTrait.OptionsBitmap(),
            optionsOverride = LevelControlTrait.OptionsBitmap(),
        )
    }

    private suspend fun commandPosition(device: HomeDevice, openPercent: Int) {
        val trait = liveTraits(device).filterIsInstance<WindowCovering>().firstOrNull()
            ?: throw IllegalStateException("Este dispositivo não expõe posição de persiana.")
        val inverted = 100 - openPercent.coerceIn(0, 100)
        trait.goToLiftPercentage((inverted * 100).toUShort())
    }

    private suspend fun liveTraits(device: HomeDevice): List<Any> {
        val types = device.types().first()
        val traits = mutableListOf<Any>()
        for (type in types) {
            val liveType = device.type(type.factory).first()
            traits.addAll(liveType.traits())
        }
        return traits
    }

    private suspend fun emitSnapshot() = withTimeout(20_000) {
        if (permissionState != PermissionsState.GRANTED) {
            emit("permission", JSONObject().put("state", permissionState.name).toString())
            return@withTimeout
        }

        val structures = client.structures().first()
        val root = JSONObject()
        val structuresJson = JSONArray()
        deviceCache.clear()

        for (structure in structures) {
            val structureJson = JSONObject()
                .put("id", structure.id.id)
                .put("name", structure.name)

            val rooms = structure.rooms().first()
            val roomNames = rooms.associate { it.id.id to it.name }
            val roomsJson = JSONArray()
            rooms.forEach { room ->
                roomsJson.put(JSONObject().put("id", room.id.id).put("name", room.name))
            }

            val devicesJson = JSONArray()
            val devices = structure.devices().first()
            for (device in devices) {
                deviceCache[device.id.id] = device
                devicesJson.put(snapshotDevice(device, roomNames))
            }

            structureJson.put("rooms", roomsJson)
            structureJson.put("devices", devicesJson)
            structuresJson.put(structureJson)
        }

        root.put("structures", structuresJson)
        emit("snapshot", root.toString())
    }

    private suspend fun snapshotDevice(device: HomeDevice, roomNames: Map<String, String>): JSONObject {
        val types = device.types().first()
        val primary = types.firstOrNull { it.metadata.isPrimaryType } ?: types.firstOrNull()
        val livePrimary = primary?.let { device.type(it.factory).first() }
        val traits = livePrimary?.traits()?.toList() ?: emptyList()

        val factories = types.map { it.factory }.toSet()
        val kind = when {
            factories.any { it == ColorTemperatureLightDevice || it == DimmableLightDevice || it == ExtendedColorLightDevice || it == OnOffLightDevice } -> "light"
            factories.any { it == WindowCoveringDevice } -> "blind"
            factories.any { it == OnOffPluginUnitDevice } -> "plug"
            factories.any { it == OnOffLightSwitchDevice || it == GenericSwitchDevice } -> "switch"
            factories.any { it == ThermostatDevice } -> "climate"
            factories.any { it == ContactSensorDevice || it == OnOffSensorDevice || it == TemperatureSensorDevice } -> "sensor"
            else -> "other"
        }

        val labels = JSONArray()
        if (traits.any { it is OnOff }) labels.put("onOff")
        if (traits.any { it is LevelControl }) labels.put("level")
        if (traits.any { it is WindowCovering }) labels.put("windowCovering")
        if (traits.any { it is Thermostat }) labels.put("thermostat")
        if (traits.any { it is TemperatureMeasurement }) labels.put("temperatureMeasurement")
        if (traits.any { it is BooleanState }) labels.put("booleanState")

        val state = JSONObject()
        traits.filterIsInstance<OnOff>().firstOrNull()?.onOff?.let { state.put("on", it) }
        traits.filterIsInstance<LevelControl>().firstOrNull()?.currentLevel?.let {
            state.put("brightness", ((it.toInt() / 254.0) * 100.0).roundToInt().coerceIn(0, 100))
        }
        traits.filterIsInstance<WindowCovering>().firstOrNull()?.currentPositionLiftPercent100ths?.let {
            state.put("position", (100 - (it.toInt() / 100)).coerceIn(0, 100))
        }
        traits.filterIsInstance<TemperatureMeasurement>().firstOrNull()?.measuredValue?.let {
            state.put("temperature", it.toInt() / 100.0)
            state.put("unit", "°C")
        }
        traits.filterIsInstance<Thermostat>().firstOrNull()?.systemMode?.let { state.put("mode", it.toString()) }
        traits.filterIsInstance<BooleanState>().firstOrNull()?.stateValue?.let { state.put("value", it) }

        val connectivity = livePrimary?.metadata?.sourceConnectivity?.connectivityState ?: device.sourceConnectivity.connectivityState
        val online = connectivity == ConnectivityState.ONLINE || connectivity == ConnectivityState.PARTIALLY_ONLINE
        val roomId = device.roomId?.id

        return JSONObject()
            .put("id", device.id.id)
            .put("name", device.name)
            .put("roomId", roomId ?: JSONObject.NULL)
            .put("roomName", roomId?.let { roomNames[it] } ?: JSONObject.NULL)
            .put("kind", kind)
            .put("kindLabel", kindLabel(kind))
            .put("online", online)
            .put("traits", labels)
            .put("state", state)
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "light" -> "Luz"
        "blind" -> "Persiana"
        "plug" -> "Tomada"
        "switch" -> "Interruptor"
        "climate" -> "Climatização"
        "sensor" -> "Sensor"
        else -> "Dispositivo"
    }

    private fun emitError(prefix: String, e: Exception) {
        emit("error", JSONObject().put("error", prefix + " " + (e.message ?: e.javaClass.simpleName)).toString())
    }
}
