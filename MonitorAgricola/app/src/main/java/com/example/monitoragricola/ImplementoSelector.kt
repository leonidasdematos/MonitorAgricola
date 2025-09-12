package com.example.monitoragricola.implementos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.round

/**
 * Ponto único de seleção de implemento.
 * - Sabe ler/gravar seleção manual e a "forçada" pelo Job (Prefs).
 * - Normaliza snapshot (largura/espacamento coerentes).
 * - Notifica ouvintes via StateFlow para a UI religar pintura/overlays.
 */
object ImplementoSelector {

    private const val TAG = "ImplementoSelector"

    private val _snapshotFlow = MutableStateFlow<ImplementoSnapshot?>(null)
    val snapshotFlow: StateFlow<ImplementoSnapshot?> = _snapshotFlow

    /** Lê o snapshot atualmente efetivo (forçado > manual). */
    fun currentSnapshot(ctx: Context): ImplementoSnapshot? {
        // prioridade: forçado pelo Job
        ImplementosPrefs.getForcedSnapshot(ctx)?.let { return normalize(it) }
        // senão, manual (catálogo -> snapshot)
        return ImplementosPrefs.getImplementoSelecionadoSnapshot(ctx)?.let { normalize(it) }
    }

    /** Força um implemento a partir do snapshot do Job (usa em onResume quando abrir/retomar Job). */
    fun forceFromJob(ctx: Context, jobSnap: ImplementoSnapshot) {
        val norm = normalize(jobSnap)
        // ANTES: ImplementosPrefs.setForcedFromJob(ctx, norm)
        ImplementosPrefs.setForcedSnapshot(ctx, norm) // <-- alinha com ImplementosPrefs
        _snapshotFlow.value = norm
        Log.d(TAG, "forceFromJob: ${norm.nome} width=${norm.larguraTrabalhoM} esp=${norm.espacamentoM}")
    }

    /** Limpa a força do Job (ex.: ao finalizar/cancelar o job). */
    fun clearForce(ctx: Context) {
        // ANTES: ImplementosPrefs.clearForced(ctx)
        ImplementosPrefs.clearForcedSnapshot(ctx) // <-- alinha com ImplementosPrefs
        _snapshotFlow.value = currentSnapshot(ctx) // cai pro manual
    }

    /** Seleção manual pelo ID do catálogo. */
    fun selectManualById(ctx: Context, id: Int) {
        // grava nas prefs (você já tem isso), depois atualiza flow:
        // basta salvar o id em "configs.implemento_selecionado_id"
        ctx.getSharedPreferences("configs", Context.MODE_PRIVATE)
            .edit().putInt("implemento_selecionado_id", id).apply()
        // limpar força, se houver:
        ImplementosPrefs.clearForced(ctx)
        _snapshotFlow.value = currentSnapshot(ctx)
    }

    // ---------- Normalização/coerência ----------

    private fun normalize(snap: ImplementoSnapshot): ImplementoSnapshot {
        val tipo = snap.tipo?.lowercase()
        val linhas = (snap.numLinhas ?: 0).coerceAtLeast(0)
        var largura = snap.larguraTrabalhoM
        var espac   = snap.espacamentoM

        // reconstituir coerência entre largura/espacamento/linhas
        if (espac <= 0f && largura > 0f && linhas > 0) espac = largura / linhas
        if (largura <= 0f && linhas > 0 && espac > 0f) largura = linhas * espac

        // sanidade mínima (apenas se ainda estiver inválido)
        if (tipo == "plantadeira" && linhas > 0) {
            if (espac <= 0f) espac = 0.45f
            if (largura <= 0f) largura = linhas * espac
        }

        return snap.copy(
            tipo = tipo,
            numLinhas = if (linhas > 0) linhas else snap.numLinhas,
            espacamentoM = espac,
            larguraTrabalhoM = largura
        )
    }
}
