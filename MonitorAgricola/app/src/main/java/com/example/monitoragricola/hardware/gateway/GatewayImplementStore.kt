package com.example.monitoragricola.hardware.gateway

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.monitoragricola.data.Implemento
import com.example.monitoragricola.implementos.ImplementoSelector
import com.example.monitoragricola.implementos.ImplementosPrefs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class GatewayImplementStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_IMPLEMENTOS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Implemento>>() {}.type

    fun upsertFromGateway(
        info: RaspberryGatewayManager.GatewayImplementInfo,
        config: GatewayConnectionConfig,
        endpoint: RaspberryGatewayManager.ResolvedEndpoint,
    ): Int {
        val implementList = loadList()
        val implementId = info.id ?: generateId(endpoint)
        val implement = toImplement(info, implementId, config, endpoint)
        val index = implementList.indexOfFirst { it.id == implementId }
        if (index >= 0) {
            implementList[index] = implement
        } else {
            implementList.add(implement)
        }
        saveList(implementList)
        Log.i(TAG, "Implemento do gateway sincronizado id=$implementId nome=${implement.nome}")

        if (!ImplementosPrefs.isForcedByJob(context)) {
            ImplementoSelector.selectManualById(context, implementId)
        } else {
            ImplementoSelector.refresh(context)
        }
        return implementId
    }

    fun removeGatewayImplement(id: Int) {
        val implementList = loadList()
        val removed = implementList.removeAll { it.id == id }
        if (!removed) return
        saveList(implementList)
        val configs = context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE)
        val currentSelected = configs.getInt(KEY_SELECTED_IMPLEMENT_ID, -1)
        if (currentSelected == id) {
            configs.edit { remove(KEY_SELECTED_IMPLEMENT_ID) }
        }
        ImplementoSelector.refresh(context)
        Log.i(TAG, "Implemento do gateway removido id=$id")
    }

    private fun toImplement(
        info: RaspberryGatewayManager.GatewayImplementInfo,
        id: Int,
        config: GatewayConnectionConfig,
        endpoint: RaspberryGatewayManager.ResolvedEndpoint,
    ): Implemento {
        val role = info.role?.lowercase()?.trim()
        val tipo = when (role) {
            "planter", "seeder" -> "Plantadeira"
            "sprayer" -> "Pulverizador"
            "fertilizer", "spreader" -> "Adubadora"
            else -> role?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Implemento"
        }
        val nome = info.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(info.manufacturer, info.model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Implemento Gateway" }
        val rowCount = info.rowCount?.coerceAtLeast(0) ?: 0
        val rowSpacing = info.rowSpacingMeters?.toFloat() ?: 0f
        val sections = info.sections.orEmpty()
        val totalWidthFromSections = sections.sumOf { section ->
            val width = section.widthMeters ?: info.rowSpacingMeters ?: 0.0
            val count = section.count ?: 1
            width * count
        }.toFloat()
        val totalWidth = when {
            totalWidthFromSections > 0f -> totalWidthFromSections
            rowCount > 0 && rowSpacing > 0f -> rowCount * rowSpacing
            else -> rowSpacing
        }
        val numSections = sections.sumOf { it.count ?: 0 }

        val hitchDistance = info.hitchToToolMeters?.toFloat()

        val endpointString = when (endpoint) {
            is RaspberryGatewayManager.ResolvedEndpoint.Tcp -> "${endpoint.host}:${endpoint.port}"
            is RaspberryGatewayManager.ResolvedEndpoint.Bluetooth -> endpoint.address
        }

        return Implemento(
            id = id,
            nome = nome,
            tipo = tipo,
            largura = totalWidth,
            numLinhas = rowCount,
            espacamento = rowSpacing,
            tamanhoBarra = totalWidth,
            numSecoes = numSections,
            capacidade = 0f,
            vazao = 0f,
            larguraColheita = totalWidth,
            tipoPlataforma = info.model ?: "",
            distanciaAntena = hitchDistance,
            offsetLateral = 0f,
            offsetLongitudinal = 0f,
            modoCadastro = "automatico",
            modoRastro = "entrada",
            distAntenaArticulacao = null,
            distArticulacaoImplemento = null,
            hardwareManaged = true,
            hardwareTransport = config.medium.storageKey,
            hardwareEndpoint = endpointString,
        )
    }

    private fun loadList(): MutableList<Implemento> {
        val json = prefs.getString(KEY_LISTA_IMPLEMENTOS, "[]") ?: "[]"
        return runCatching { gson.fromJson<MutableList<Implemento>>(json, listType) }.getOrDefault(mutableListOf())
    }

    private fun saveList(list: MutableList<Implemento>) {
        prefs.edit { putString(KEY_LISTA_IMPLEMENTOS, gson.toJson(list)) }
    }

    private fun generateId(endpoint: RaspberryGatewayManager.ResolvedEndpoint): Int {
        val key = when (endpoint) {
            is RaspberryGatewayManager.ResolvedEndpoint.Tcp -> "${endpoint.host}:${endpoint.port}"
            is RaspberryGatewayManager.ResolvedEndpoint.Bluetooth -> endpoint.address
        }
        val hash = abs(key.hashCode())
        return ID_OFFSET + (hash % ID_MOD)
    }

    companion object {
        private const val TAG = "GatewayImplStore"
        private const val PREFS_IMPLEMENTOS = "implementos"
        private const val PREFS_CONFIGS = "configs"
        private const val KEY_LISTA_IMPLEMENTOS = "lista_implementos"
        private const val KEY_SELECTED_IMPLEMENT_ID = "implemento_selecionado_id"
        private const val ID_OFFSET = 1_000_000
        private const val ID_MOD = 100_000_000
    }
}