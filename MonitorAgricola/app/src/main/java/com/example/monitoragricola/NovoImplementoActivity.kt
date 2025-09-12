package com.example.monitoragricola.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.monitoragricola.R
import com.example.monitoragricola.data.Implemento
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NovoImplementoActivity : AppCompatActivity() {

    private lateinit var etNomeImplemento: EditText
    private lateinit var spinnerTipoImplemento: Spinner
    private lateinit var containerCamposEspecificos: LinearLayout
    private lateinit var rgModoCadastro: RadioGroup
    private lateinit var btnSalvarImplemento: Button
    private lateinit var etDistanciaAntena: EditText
    private lateinit var etOffsetLateral: EditText
    private lateinit var etOffsetLongitudinal: EditText
    private lateinit var btnConfigAvancadas: Button
    private lateinit var containerConfigAvancadas: LinearLayout
    private lateinit var spinnerModoRastro: Spinner
    private lateinit var containerArticulado: LinearLayout
    private lateinit var etDistAntenaArticulacao: EditText
    private lateinit var etDistArticulacaoImplemento: EditText



    private val camposViews = mutableMapOf<String, EditText>()
    private var tipoSelecionado = ""
    private var implementoEdicaoId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novo_implemento)

        etNomeImplemento = findViewById(R.id.etNomeImplemento)
        spinnerTipoImplemento = findViewById(R.id.spinnerTipoImplemento)
        containerCamposEspecificos = findViewById(R.id.containerCamposEspecificos)
        rgModoCadastro = findViewById(R.id.rgModoCadastro)
        btnSalvarImplemento = findViewById(R.id.btnSalvarImplemento)

        etDistanciaAntena = findViewById(R.id.etDistanciaAntena)
        etOffsetLateral = findViewById(R.id.etOffsetLateral)
        etOffsetLongitudinal = findViewById(R.id.etOffsetLongitudinal)
        btnConfigAvancadas = findViewById(R.id.btnConfigAvancadas)
        containerConfigAvancadas = findViewById(R.id.containerConfigAvancadas)

        spinnerModoRastro = findViewById(R.id.spinnerModoRastro)
        containerArticulado = findViewById(R.id.containerArticulado)
        etDistAntenaArticulacao = findViewById(R.id.etDistAntenaArticulacao)
        etDistArticulacaoImplemento = findViewById(R.id.etDistArticulacaoImplemento)

        btnConfigAvancadas.setOnClickListener {
            containerConfigAvancadas.visibility =
                if (containerConfigAvancadas.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        configurarSpinner()
        configurarModoRastroSpinner()
        configurarBotoes()
        carregarImplementoEdicao()


    }

    private fun configurarSpinner() {
        val tipos = arrayOf("Plantadeira", "Pulverizador", "Adubadora", "Colheitadeira")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tipos)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTipoImplemento.adapter = adapter

        spinnerTipoImplemento.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                tipoSelecionado = parent.getItemAtPosition(position).toString()
                criarCamposEspecificos(tipoSelecionado)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                containerCamposEspecificos.visibility = View.GONE
            }
        }
    }

    private fun criarCamposEspecificos(tipo: String) {
        containerCamposEspecificos.removeAllViews()
        camposViews.clear()

        val campos = Implemento.getCamposPorTipo(tipo)
        if (campos.isEmpty()) {
            containerCamposEspecificos.visibility = View.GONE
            return
        }

        containerCamposEspecificos.visibility = View.VISIBLE

        for (campo in campos) {
            val textView = TextView(this).apply {
                text = campo.label
                setTextAppearance(android.R.style.TextAppearance_Medium)
            }
            containerCamposEspecificos.addView(textView)

            val editText = EditText(this).apply {
                hint = campo.label
                when (campo.inputType) {
                    "number" -> inputType = InputType.TYPE_CLASS_NUMBER
                    "numberDecimal" -> inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

                    else -> inputType = InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            containerCamposEspecificos.addView(editText)
            camposViews[campo.key] = editText
        }
    }

    private fun carregarImplementoEdicao() {
        val implementoJson = intent.getStringExtra("implemento_edicao")
        if (implementoJson != null) {
            val implemento = Gson().fromJson(implementoJson, Implemento::class.java)

            implementoEdicaoId = implemento.id

            etNomeImplemento.setText(implemento.nome)
            val pos =
                (spinnerTipoImplemento.adapter as ArrayAdapter<String>).getPosition(implemento.tipo)
            spinnerTipoImplemento.setSelection(pos)

            if (implemento.modoCadastro == "automatico") {
                rgModoCadastro.check(R.id.rbAutomatico)
            } else {
                rgModoCadastro.check(R.id.rbManual)
            }


            val modoRastroKey = implemento.modoRastro ?: "entrada" // default
            val uiModo = UIModoRastro.fromKey(modoRastroKey)
            spinnerModoRastro.setSelection(uiModo.ordinal)

// 2) Campos de distância conforme o modo
            if (uiModo == UIModoRastro.ARTICULADO) {
                etDistanciaAntena.visibility = View.GONE
                containerArticulado.visibility = View.VISIBLE

                etDistAntenaArticulacao.setText(
                    (implemento.distAntenaArticulacao ?: 0f).toString()
                )
                etDistArticulacaoImplemento.setText(
                    (implemento.distArticulacaoImplemento ?: 0f).toString()
                )
            } else {
                etDistanciaAntena.visibility = View.VISIBLE
                containerArticulado.visibility = View.GONE

                etDistanciaAntena.setText((implemento.distanciaAntena ?: 0f).toString())

            }


            btnSalvarImplemento.text = "Salvar Alterações"
        }
    }

    private fun configurarBotoes() {
        btnSalvarImplemento.setOnClickListener {
            if (validarCampos()) {
                salvarImplemento(implementoEdicaoId)
                //setResult(RESULT_OK)
                //finish()
            }
        }
    }

    private fun validarCampos(): Boolean {
        if (etNomeImplemento.text.toString().trim().isEmpty()) {
            Toast.makeText(this, "Digite um nome para o implemento", Toast.LENGTH_SHORT).show()
            return false
        }
        if (tipoSelecionado.isEmpty()) {
            Toast.makeText(this, "Selecione um tipo de implemento", Toast.LENGTH_SHORT).show()
            return false
        }
        val uiModo = UIModoRastro.values()[spinnerModoRastro.selectedItemPosition]
        if (uiModo == UIModoRastro.ARTICULADO) {
            val a = etDistAntenaArticulacao.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
            val b = etDistArticulacaoImplemento.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
            if (a <= 0f || b <= 0f) {
                Toast.makeText(this, "Informe as duas distâncias do modo Articulado.", Toast.LENGTH_SHORT).show()
                return false
            }
        } else {
            val d = etDistanciaAntena.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
            if (d < 0f) {
                Toast.makeText(this, "Distância Antena → Implemento inválida.", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        return true
    }

    private fun salvarImplemento(idExistente: Int? = null) {
        val nome = etNomeImplemento.text.toString().trim()
        val tipo = tipoSelecionado

        val modoCadastro = when (rgModoCadastro.checkedRadioButtonId) {
            R.id.rbManual -> "manual"
            R.id.rbAutomatico -> "automatico"
            else -> "manual"
        }

        val numLinhas = camposViews["numLinhas"]?.text.toString().toIntOrNull() ?: 0
        var espacamento =
            camposViews["espacamento"]?.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
        val tamanhoBarra = camposViews["tamanhoBarra"]?.text.toString().toFloatOrNull() ?: 0f
        val numSecoes = camposViews["numSecoes"]?.text.toString().toIntOrNull() ?: 0
        val capacidade = camposViews["capacidade"]?.text.toString().toFloatOrNull() ?: 0f
        val vazao = camposViews["vazao"]?.text.toString().toFloatOrNull() ?: 0f
        val larguraColheita = camposViews["larguraColheita"]?.text.toString().toFloatOrNull() ?: 0f
        val tipoPlataforma = camposViews["tipoPlataforma"]?.text.toString()

        val offsetLateral = etOffsetLateral.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
        val offsetLongitudinal = etOffsetLongitudinal.text.toString().replace(",", ".").toFloatOrNull() ?: 0f

        if (espacamento in 2f..150f) {
            espacamento = espacamento / 100f // cm -> m
        }

        val largura = when (tipo) {
            "Plantadeira" -> (numLinhas * espacamento).coerceAtLeast(0f)
            "Pulverizador" -> tamanhoBarra
            "Colheitadeira" -> larguraColheita
            else -> 0f
        }

        val uiModo = UIModoRastro.values()[spinnerModoRastro.selectedItemPosition]
        val modoRastroKey = uiModo.key

        val distanciaAntena = when (uiModo) {
            UIModoRastro.ARTICULADO -> 0f // no articulado, a distância total é decomposta
            else -> etDistanciaAntena.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
        }

        val distAntenaArticulacao =
            etDistAntenaArticulacao.text.toString().replace(",", ".").toFloatOrNull() ?: 0f
        val distArticulacaoImplemento =
            etDistArticulacaoImplemento.text.toString().replace(",", ".").toFloatOrNull() ?: 0f

        val distanciaTotalLong = if (uiModo == UIModoRastro.ARTICULADO)
            (distAntenaArticulacao + distArticulacaoImplemento)
        else
            distanciaAntena



        val novoImplemento = Implemento(
            id = idExistente ?: Implemento.getNextId(this),
            nome = nome,
            tipo = tipo,
            largura = largura,
            numLinhas = numLinhas,
            espacamento = espacamento,
            tamanhoBarra = tamanhoBarra,
            numSecoes = numSecoes,
            capacidade = capacidade,
            vazao = vazao,
            larguraColheita = larguraColheita,
            tipoPlataforma = tipoPlataforma,

            // offsets “globais”
            distanciaAntena = if (uiModo == UIModoRastro.ARTICULADO) null else distanciaAntena,
            offsetLateral = offsetLateral,
            offsetLongitudinal = offsetLongitudinal,

            // cadastro
            modoCadastro = modoCadastro,

            // NOVOS:
            modoRastro = modoRastroKey, // "fixo" | "entrada" | "articulado"
            distAntenaArticulacao = if (uiModo == UIModoRastro.ARTICULADO) distAntenaArticulacao else null,
            distArticulacaoImplemento = if (uiModo == UIModoRastro.ARTICULADO) distArticulacaoImplemento else null
        )

        val prefs = getSharedPreferences("implementos", MODE_PRIVATE)
        val json = prefs.getString("lista_implementos", "[]")
        val type = object : TypeToken<MutableList<Implemento>>() {}.type
        val implementosList =
            Gson().fromJson<MutableList<Implemento>>(json, type) ?: mutableListOf()

        if (idExistente != null) {
            val index = implementosList.indexOfFirst { it.id == idExistente }
            if (index != -1) {
                implementosList[index] = novoImplemento
            }
        } else {
            implementosList.add(novoImplemento)
        }

        val editor = prefs.edit()
        editor.putString("lista_implementos", Gson().toJson(implementosList))
        editor.apply()

        Toast.makeText(this, "Implemento salvo com sucesso!", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private enum class UIModoRastro(val label: String, val key: String) {
        FIXO("Fixo", "fixo"),
        ENTRADA("Entrada compensada", "entrada"),
        ARTICULADO("Articulado", "articulado");

        companion object {
            fun fromKey(k: String?): UIModoRastro {
                val kk = (k ?: "").lowercase()
                return values().firstOrNull { it.key == kk } ?: ENTRADA
            }
        }
    }


    private fun configurarModoRastroSpinner() {
        val modos = UIModoRastro.values().map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modos)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModoRastro.adapter = adapter

        spinnerModoRastro.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = UIModoRastro.values()[position]
                // Mostra/esconde campos conforme modo
                if (selected == UIModoRastro.ARTICULADO) {
                    etDistanciaAntena.visibility = View.GONE
                    containerArticulado.visibility = View.VISIBLE
                } else {
                    etDistanciaAntena.visibility = View.VISIBLE
                    containerArticulado.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Default ao abrir (se for novo): ENTRADA
        spinnerModoRastro.setSelection(UIModoRastro.ENTRADA.ordinal)
    }

}