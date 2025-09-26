// com/example/monitoragricola/ui/routes/RoutesActivity.kt
package com.example.monitoragricola.ui.routes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.monitoragricola.App
import com.example.monitoragricola.R
import com.example.monitoragricola.implementos.ImplementoSnapshot
import com.example.monitoragricola.implementos.ImplementosPrefs
import com.example.monitoragricola.jobs.routes.RouteDisplayPrefs
import com.example.monitoragricola.jobs.routes.RouteType
import com.example.monitoragricola.jobs.routes.db.JobRouteEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class RoutesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROUTE_ACTION = "extra_route_action"
        const val EXTRA_ROUTE_VISIBLE = "extra_route_visible"
        const val EXTRA_ROUTE_TYPE = "extra_route_type"
        const val EXTRA_ROUTE_RESET = "extra_route_reset"
        const val EXTRA_JOB_ID = "extra_route_job_id"
        const val EXTRA_PENDING_A = "extra_route_pending_a"
        const val EXTRA_PENDING_B = "extra_route_pending_b"
        const val EXTRA_IS_RECORDING = "extra_route_recording"
        const val EXTRA_HAS_TRACK = "extra_route_has_track"

        const val ROUTE_TYPE_AB = "AB"
        const val ROUTE_TYPE_CURVE = "CURVE"

        const val ACTION_MARK_A = "action_mark_a"
        const val ACTION_MARK_B = "action_mark_b"
        const val ACTION_GENERATE_AB = "action_generate_ab"
        const val ACTION_START_TRACK = "action_start_track"
        const val ACTION_STOP_TRACK = "action_stop_track"
        const val ACTION_GENERATE_CURVE = "action_generate_curve"
    }

    private lateinit var rgTipo: RadioGroup
    private lateinit var rbAB: RadioButton
    private lateinit var rbCurva: RadioButton
    private lateinit var cbUsarCustom: CheckBox
    private lateinit var etEsp: EditText
    private lateinit var tvImplementoInfo: TextView
    private lateinit var btnSalvar: Button
    private lateinit var btnAcao: Button
    private lateinit var btnRedefinir: Button
    private lateinit var swMostrar: Switch
    private lateinit var tvStatus: TextView

    // Valor base vindo do implemento (usado quando o usuário NÃO marca “usar custom”)
    private var defaultPassoM: Float = 0.45f

    private val app by lazy { application as App }
    private var jobId: Long = -1L
    private var hasActiveRoute = false
    private var routeVisiblePref = false
    private var currentAction: String? = null
    private var pendingASet = false
    private var pendingBSet = false
    private var isRecordingTrack = false
    private var hasTrackRecorded = false
    private var routeCleared = false
    private var loadedRoute: JobRouteEntity? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rotas)

        rgTipo = findViewById(R.id.rgTipo)
        rbAB = findViewById(R.id.rbABReta)
        rbCurva = findViewById(R.id.rbCurva)
        cbUsarCustom = findViewById(R.id.cbUsarCustom)
        etEsp = findViewById(R.id.etEspacamento)
        tvImplementoInfo = findViewById(R.id.tvImplementoInfo)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnAcao = findViewById(R.id.btnAcao)
        btnRedefinir = findViewById(R.id.btnRedefinir)
        swMostrar = findViewById(R.id.swMostrarRotas)
        tvStatus = findViewById(R.id.tvStatusRota)

        jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        pendingASet = intent.getBooleanExtra(EXTRA_PENDING_A, false)
        pendingBSet = intent.getBooleanExtra(EXTRA_PENDING_B, false)
        isRecordingTrack = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
        hasTrackRecorded = intent.getBooleanExtra(EXTRA_HAS_TRACK, false)
        routeVisiblePref = intent.getBooleanExtra(EXTRA_ROUTE_VISIBLE, false)

        val routePrefs = getSharedPreferences("routes_prefs", Context.MODE_PRIVATE)

        // 1) Descobrir implemento atual (prioriza o "forçado" pelo Job)
        val forcedSnap = ImplementosPrefs.getForcedSnapshot(this)
        if (forcedSnap != null) {
            setupUIFromSnapshot(forcedSnap)
        } else {
            // fallback: seleção manual (igual à MainActivity)
            val implInfo = loadImplementoInfo()
            setupUIFromImplementoInfo(implInfo)
        }

        // 2) Carregar preferências salvas
        val savedType = routePrefs.getString("route_type", "AB") ?: "AB"
        when (savedType) {
            "AB" -> rbAB.isChecked = true
            "CURVE" -> rbCurva.isChecked = true
        }

        val useCustom = routePrefs.getBoolean("use_custom_spacing", false)
        cbUsarCustom.isChecked = useCustom

        val customSaved = routePrefs.getString(
            "spacing_m_custom",
            routePrefs.getString("spacing_m", null) // compatibilidade com versões antigas
        )

        // 3) Aplicar valor no campo conforme a flag
        if (useCustom) {
            etEsp.isEnabled = true
            etEsp.setText(customSaved ?: fmt(defaultPassoM))
        } else {
            etEsp.isEnabled = false
            etEsp.setText(fmt(defaultPassoM))
        }

        // 4) Persistir troca do tipo imediatamente (resolve "não salva o último")
        rgTipo.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.rbABReta) "AB" else "CURVE"
            routePrefs.edit().putString("route_type", type).apply()
            updateActionButtonState()
            updateStatusLabel()
        }

        // 5) CheckBox alterna e salva a flag
        cbUsarCustom.setOnCheckedChangeListener { _, isChecked ->
            etEsp.isEnabled = isChecked
            routePrefs.edit().putBoolean("use_custom_spacing", isChecked).apply()
            if (!isChecked) {
                // reset para o valor do implemento
                etEsp.setText(fmt(defaultPassoM))
            }
        }

        swMostrar.visibility = View.GONE
        swMostrar.isChecked = routeVisiblePref
        swMostrar.setOnCheckedChangeListener { _, isChecked ->
            if (hasActiveRoute) {
                routeVisiblePref = isChecked
            } else {
                routeVisiblePref = false
                swMostrar.isChecked = false
            }
        }

        btnAcao.setOnClickListener {
            if (currentAction == null) {
                Toast.makeText(this, "Nenhuma ação disponível agora.", Toast.LENGTH_SHORT).show()
            } else {
                finishWithAction(currentAction)
            }
        }

        btnRedefinir.setOnClickListener {
            if (!hasActiveRoute || jobId <= 0L) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Redefinir rota")
                .setMessage("Remover a rota atual e iniciar uma nova configuração?")
                .setPositiveButton("Remover") { _, _ ->
                    lifecycleScope.launch { clearExistingRoute() }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }


        // 6) Salvar ao clicar
        btnSalvar.setOnClickListener {
            finishWithAction(null)
        }

        updateActionButtonState()
        updateStatusLabel()

        lifecycleScope.launch { loadExistingRoute() }
    }

    private suspend fun loadExistingRoute() {
        if (jobId <= 0L) {
            hasActiveRoute = false
            loadedRoute = null
            routeVisiblePref = false
            withContext(Dispatchers.Main) { updateUiForRoute(null) }
            return
        }
        val route = withContext(Dispatchers.IO) {
            app.routesRepository.activeRoutes(jobId).firstOrNull()
        }
        loadedRoute = route
        hasActiveRoute = route != null
        if (!hasActiveRoute) routeVisiblePref = false
        withContext(Dispatchers.Main) { updateUiForRoute(route) }
    }

    private suspend fun clearExistingRoute() {
        val id = jobId
        if (id <= 0L) return
        val routes = withContext(Dispatchers.IO) { app.routesRepository.activeRoutes(id) }
        withContext(Dispatchers.IO) {
            routes.forEach { app.routesManager.delete(it.id) }
        }
        RouteDisplayPrefs.setVisible(this, id, false)
        routeCleared = true
        hasActiveRoute = false
        loadedRoute = null
        routeVisiblePref = false
        pendingASet = false
        pendingBSet = false
        isRecordingTrack = false
        hasTrackRecorded = false
        withContext(Dispatchers.Main) {
            swMostrar.isChecked = false
            swMostrar.visibility = View.GONE
            btnRedefinir.visibility = View.GONE
            Toast.makeText(this@RoutesActivity, "Rota removida. Configure novamente.", Toast.LENGTH_SHORT).show()
            updateActionButtonState()
            updateStatusLabel()
        }
    }

    private fun updateUiForRoute(route: JobRouteEntity?) {
        swMostrar.visibility = if (route != null) View.VISIBLE else View.GONE
        swMostrar.isEnabled = route != null
        swMostrar.isChecked = route != null && routeVisiblePref
        btnRedefinir.visibility = if (route != null) View.VISIBLE else View.GONE
        updateActionButtonState()
        updateStatusLabel()
    }

    private fun updateStatusLabel() {
        tvStatus.text = when {
            jobId <= 0L -> "Nenhum trabalho selecionado. Selecione um trabalho para configurar rotas."
            hasActiveRoute -> {
                val typeLabel = when (loadedRoute?.type) {
                    RouteType.AB_STRAIGHT -> "Reta AB"
                    RouteType.AB_CURVE -> "Curva"
                    else -> "Rota"
                }
                "Rota ativa: $typeLabel. Toque em Redefinir para criar novamente."
            }
            resolveSelectedType() == ROUTE_TYPE_AB -> {
                when {
                    !pendingASet -> "Preparando rota AB. Marque o ponto A."
                    !pendingBSet -> "Preparando rota AB. Marque o ponto B."
                    else -> "Preparando rota AB. Gere as linhas quando estiver pronto."
                }
            }
            else -> {
                when {
                    isRecordingTrack -> "Gravação do trilho em andamento. Pare quando finalizar."
                    hasTrackRecorded -> "Trilho gravado. Gere as linhas curvas."
                    else -> "Inicie a gravação do trilho para gerar uma rota curva."
                }
            }
        }
    }

    private fun updateActionButtonState() {
        val type = resolveSelectedType()
        if (jobId <= 0L) {
            currentAction = null
            btnAcao.isEnabled = false
            btnAcao.text = "Selecione um trabalho para criar rotas"
            btnAcao.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            return
        }
        if (hasActiveRoute) {
            currentAction = null
            btnAcao.isEnabled = false
            btnAcao.text = "Rota já configurada para este trabalho"
            btnAcao.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            return
        }
        btnAcao.isEnabled = true
        when (type) {
            ROUTE_TYPE_AB -> {
                when {
                    !pendingASet -> applyAction(android.R.drawable.ic_menu_mylocation, "Marcar ponto A", ACTION_MARK_A)
                    !pendingBSet -> applyAction(android.R.drawable.ic_menu_compass, "Marcar ponto B", ACTION_MARK_B)
                    else -> applyAction(android.R.drawable.ic_media_play, "Gerar linhas AB", ACTION_GENERATE_AB)
                }
            }
            else -> {
                when {
                    isRecordingTrack -> applyAction(android.R.drawable.ic_media_pause, "Parar gravação do trilho", ACTION_STOP_TRACK)
                    hasTrackRecorded -> applyAction(android.R.drawable.ic_media_play, "Gerar linhas curvas", ACTION_GENERATE_CURVE)
                    else -> applyAction(android.R.drawable.ic_media_play, "Iniciar gravação do trilho", ACTION_START_TRACK)
                }
            }
        }
    }

    private fun applyAction(iconRes: Int, text: String, action: String) {
        currentAction = action
        btnAcao.text = text
        val icon = ContextCompat.getDrawable(this, iconRes)
        btnAcao.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    private fun resolveSelectedType(): String = if (rbAB.isChecked) ROUTE_TYPE_AB else ROUTE_TYPE_CURVE

    private fun finishWithAction(action: String?) {
        val type = resolveSelectedType()
        val spacingToSave = etEsp.text.toString().ifBlank { fmt(defaultPassoM) }
        val routePrefs = getSharedPreferences("routes_prefs", Context.MODE_PRIVATE)
        routePrefs.edit()
            .putString("route_type", type)
            .putBoolean("use_custom_spacing", cbUsarCustom.isChecked)
            .putString("spacing_m_custom", spacingToSave)
            .apply()

        if (jobId > 0L) {
            RouteDisplayPrefs.setVisible(this, jobId, routeVisiblePref && hasActiveRoute)
            if (routeCleared) {
                RouteDisplayPrefs.setVisible(this, jobId, false)
            }
        }

        val data = Intent().apply {
            putExtra(EXTRA_ROUTE_TYPE, type)
            putExtra(EXTRA_ROUTE_VISIBLE, routeVisiblePref && hasActiveRoute)
            if (action != null) putExtra(EXTRA_ROUTE_ACTION, action)
            if (routeCleared) putExtra(EXTRA_ROUTE_RESET, true)
        }
        setResult(Activity.RESULT_OK, data)
        if (action == null) {
            Toast.makeText(this, "Configurações de rota salvas.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /**
     * Configura UI quando existe snapshot do Job.
     * Regras:
     * - Plantadeira: passo padrão = espacamentoM.
     * - Outros tipos: passo padrão = larguraTrabalhoM.
     * O label mostra info útil do implemento.
     */
    private fun setupUIFromSnapshot(snap: ImplementoSnapshot) {
        val tipo = snap.tipo?.lowercase() ?: ""
        val linhas = (snap.numLinhas ?: 0).coerceAtLeast(0)
        val espac = snap.espacamentoM.coerceAtLeast(0.01f)

        // largura do passe (preferir valor direto; senão, derivar)
        val largura = (if (snap.larguraTrabalhoM > 0f) snap.larguraTrabalhoM else linhas * espac)
            .coerceAtLeast(0.01f)

        defaultPassoM = largura

        if (tipo == "plantadeira") {
            tvImplementoInfo.text =
                "Implemento (Job): ${snap.nome} — Plantadeira • ${linhas}×${fmt(espac)} m ⇒ Largura ${fmt(largura)} m"
        } else {
            tvImplementoInfo.text =
                "Implemento (Job): ${snap.nome} — Largura de trabalho ${fmt(largura)} m"
        }
    }

    /**
     * Configura UI quando NÃO existe snapshot do Job e usamos o implemento selecionado manualmente.
     * Regras equivalentes às do snapshot, mas com campos da Entity simplificada.
     */
    private fun setupUIFromImplementoInfo(impl: ImplementoInfo?) {
        if (impl == null) {
            defaultPassoM = 3.0f
            tvImplementoInfo.text = "Implemento: —"
            return
        }

        val tipo = impl.tipo?.lowercase() ?: ""
        val linhas = (impl.numLinhas ?: 0).coerceAtLeast(0)
        val espac  = impl.espacamento.coerceAtLeast(0.01f)

        // 1ª escolha: largura salva na entidade
        // 2ª escolha: derivar por linhas × espaçamento (para plantadeira)
        // fallback: 3.0 m para outros sem largura
        val largura = when {
            (impl.largura ?: 0f) > 0f -> impl.largura!!.coerceAtLeast(0.01f)
            tipo == "plantadeira" && linhas > 0 -> (linhas * espac).coerceAtLeast(0.01f)
            else -> 3.0f
        }

        defaultPassoM = largura

        tvImplementoInfo.text = if (tipo == "plantadeira") {
            "Implemento: ${impl.nome} — Plantadeira • ${linhas}×${fmt(espac)} m ⇒ Largura ${fmt(largura)} m"
        } else {
            "Implemento: ${impl.nome} — Largura de trabalho ${fmt(largura)} m"
        }
    }

    /**
     * Lê o implemento selecionado manualmente:
     * - id salvo em prefs "configs" com chave "implemento_selecionado_id"
     * - lista de implementos em prefs "implementos" com chave "lista_implementos" (JSON)
     */
    private fun loadImplementoInfo(): ImplementoInfo? {
        val prefsConfigs = getSharedPreferences("configs", MODE_PRIVATE)
        val implId = prefsConfigs.getInt("implemento_selecionado_id", -1)
        if (implId == -1) return null

        val prefsImpl = getSharedPreferences("implementos", MODE_PRIVATE)
        val json = prefsImpl.getString("lista_implementos", "[]") ?: "[]"
        val type = object : TypeToken<List<ImplementoInfo>>() {}.type
        val implementos: List<ImplementoInfo> = try {
            Gson().fromJson(json, type)
        } catch (_: Throwable) {
            emptyList()
        }
        return implementos.find { it.id == implId }
    }

    // Modelo simplificado compatível com sua MainActivity (ajuste nomes se sua Entity real for diferente)
    data class ImplementoInfo(
        val id: Int,
        val nome: String,
        val tipo: String?,
        val numLinhas: Int?,
        val espacamento: Float,   // metros (center-to-center)
        val largura: Float?,      // <-- NOVO: largura total salva na entidade
        val distanciaAntena: Float?,
        val offsetLateral: Float?,
        val offsetLongitudinal: Float?
    )

    private fun fmt(v: Float): String = String.format("%.2f", v)
}
