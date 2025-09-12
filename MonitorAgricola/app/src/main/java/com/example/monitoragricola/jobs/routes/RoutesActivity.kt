// com/example/monitoragricola/ui/routes/RoutesActivity.kt
package com.example.monitoragricola.ui.routes

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.monitoragricola.R
import com.example.monitoragricola.implementos.ImplementoSnapshot
import com.example.monitoragricola.implementos.ImplementosPrefs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.max

class RoutesActivity : AppCompatActivity() {

    private lateinit var rgTipo: RadioGroup
    private lateinit var rbAB: RadioButton
    private lateinit var rbCurva: RadioButton
    private lateinit var cbUsarCustom: CheckBox
    private lateinit var etEsp: EditText
    private lateinit var tvImplementoInfo: TextView
    private lateinit var btnSalvar: Button

    // Valor base vindo do implemento (usado quando o usuário NÃO marca “usar custom”)
    private var defaultPassoM: Float = 0.45f

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

        // 6) Salvar ao clicar
        btnSalvar.setOnClickListener {
            val type = if (rbAB.isChecked) "AB" else "CURVE"

            // Se custom estiver ativo, salva o que o usuário digitou;
            // caso contrário, salva o default só por compat.
            val spacingToSave = etEsp.text.toString().ifBlank { fmt(defaultPassoM) }

            routePrefs.edit()
                .putString("route_type", type)
                .putBoolean("use_custom_spacing", cbUsarCustom.isChecked)
                .putString("spacing_m_custom", spacingToSave)
                .apply()

            Toast.makeText(this, "Configurações de rota salvas.", Toast.LENGTH_SHORT).show()
            finish()
        }
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
