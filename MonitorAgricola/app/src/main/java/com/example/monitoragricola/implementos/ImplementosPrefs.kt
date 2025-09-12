// com/example/monitoragricola/implementos/ImplementosPrefs.kt
package com.example.monitoragricola.implementos

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


object ImplementosPrefs {

    private const val PREFS_IMPL = "implementos"
    private const val PREFS_CONFIGS = "configs"

    private const val KEY_SELECTED_JOB_ID = "selected_job_id"
    private const val KEY_FORCED_SNAPSHOT_JSON = "forced_snapshot_json"

    // -----------------------------
    // Selected Job ID (novo trio)
    // -----------------------------
    fun setSelectedJobId(ctx: Context, jobId: Long) {
        ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SELECTED_JOB_ID, jobId)
            .apply()
    }

    fun getSelectedJobId(ctx: Context): Long? {
        val sp = ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
        val v = sp.getLong(KEY_SELECTED_JOB_ID, -1L)
        return if (v <= 0L) null else v
    }

    fun clearSelectedJobId(ctx: Context) {
        ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SELECTED_JOB_ID)
            .apply()
    }

    fun hasSelectedJob(ctx: Context): Boolean = getSelectedJobId(ctx) != null

    fun setForcedFromJob(ctx: Context, snap: ImplementoSnapshot) = setForcedSnapshot(ctx, snap)
    fun clearForced(ctx: Context) = clearForcedSnapshot(ctx)

    // ------------------------------------------------------
    // Força de implemento por Job (já usado no seu código)
    // ------------------------------------------------------
    /** Retorna true se existe snapshot "forçado por job" salvo. */
    fun isForcedByJob(ctx: Context): Boolean =
        getForcedSnapshot(ctx) != null

    /** Lê o snapshot forçado (JSON) se existir. */
    fun getForcedSnapshot(ctx: Context): ImplementoSnapshot? {
        val json = ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
            .getString(KEY_FORCED_SNAPSHOT_JSON, null)
            ?: return null
        return runCatching {
            Gson().fromJson(json, ImplementoSnapshot::class.java)
        }.getOrNull()
    }

    /** Salva/atualiza o snapshot forçado (usado pelo ImplementoSelector.forceFromJob). */
    fun setForcedSnapshot(ctx: Context, snap: ImplementoSnapshot) {
        val json = Gson().toJson(snap)
        ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FORCED_SNAPSHOT_JSON, json)
            .apply()
    }

    /** Limpa o snapshot forçado. */
    fun clearForcedSnapshot(ctx: Context) {
        ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FORCED_SNAPSHOT_JSON)
            .apply()
    }

    fun getImplementoSelecionadoSnapshot(ctx: Context): ImplementoSnapshot? {
        // 1) Lê o ID selecionado manualmente
        val cfg = ctx.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE)
        val selId = cfg.getInt("implemento_selecionado_id", -1)


        if (selId <= 0) return null

        // 2) Lê a lista de implementos salvos (CATÁLOGO), não snapshots
        val spImpl = ctx.getSharedPreferences(PREFS_IMPL, Context.MODE_PRIVATE)
        val jsonList: String = spImpl.getString("lista_implementos", "[]") ?: "[]"

        val type = object : com.google.gson.reflect.TypeToken<
                List<com.example.monitoragricola.data.Implemento>
                >() {}.type

        val lista: List<com.example.monitoragricola.data.Implemento> = try {
            com.google.gson.Gson().fromJson(jsonList, type)
        } catch (_: Throwable) {
            emptyList()
        }

        val impl = lista.firstOrNull { it.id == selId } ?: return null

        // 3) Mapeia Implemento (catálogo) -> ImplementoSnapshot
        val tipoLower = impl.tipo?.lowercase()
        val linhas  = impl.numLinhas ?: 0
        val espacM  = impl.espacamento ?: 0f


        var largura = (impl.largura ?: 0f)
        if (largura <= 0f && linhas > 0 && espacM > 0f) {
            largura = linhas * espacM
        }
        if (tipoLower == "plantadeira" && largura <= 0f && linhas > 0 && espacM > 0f) {
            largura = linhas * espacM
        }

        val distAntM = impl.distanciaAntena ?: 0f
        val offLatM  = impl.offsetLateral ?: 0f
        val offLonM  = impl.offsetLongitudinal ?: 0f

        val modoRastro = impl.modoRastro   // "fixo" | "entrada" | "articulado"
        val distAntArt = impl.distAntenaArticulacao
        val distArtImpl = impl.distArticulacaoImplemento

        return ImplementoSnapshot(
            id = impl.id,
            nome = impl.nome ?: "Implemento",
            tipo = tipoLower,
            numLinhas = if (linhas > 0) linhas else null,
            espacamentoM = espacM,
            larguraTrabalhoM = largura.coerceAtLeast(0.01f),
            distanciaAntenaM = distAntM,
            offsetLateralM = offLatM,
            offsetLongitudinalM = offLonM,

            // NOVOS:
            modoRastro = modoRastro,
            distAntenaArticulacaoM = distAntArt,
            distArticulacaoImplementoM = distArtImpl
        )
    }
}
