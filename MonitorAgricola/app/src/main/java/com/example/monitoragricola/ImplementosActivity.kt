package com.example.monitoragricola.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.monitoragricola.R
import com.example.monitoragricola.data.Implemento
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.monitoragricola.implementos.ImplementoSelector
import com.example.monitoragricola.implementos.ImplementosPrefs

class ImplementosActivity : AppCompatActivity() {

    private lateinit var btnNovoImplemento: Button
    private lateinit var listViewImplementos: ListView
    private var implementosList = mutableListOf<Implemento>()
    private var selectedPosition = -1
    private lateinit var adapter: ImplementosAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_implementos)

        btnNovoImplemento = findViewById(R.id.btnNovoImplemento)
        listViewImplementos = findViewById(R.id.listViewImplementos)

        carregarImplementos()
        configurarListView()
        configurarBotoes()
    }

    override fun onResume() {
        super.onResume()
        carregarImplementos()
        adapter.notifyDataSetChanged()
    }

    private fun carregarImplementos() {
        val prefs = getSharedPreferences("implementos", MODE_PRIVATE)
        val json = prefs.getString("lista_implementos", "[]")
        val type = object : TypeToken<List<Implemento>>() {}.type
        val listaSalva = Gson().fromJson<List<Implemento>>(json, type) ?: emptyList()

        implementosList.clear()
        implementosList.addAll(listaSalva)

        // Seleção visual: apenas seleção MANUAL (ignorar forçado)
        val forced = ImplementosPrefs.isForcedByJob(this)
        selectedPosition = if (!forced) {
            val manualId = getSharedPreferences("configs", MODE_PRIVATE)
                .getInt("implemento_selecionado_id", -1)
            if (manualId > 0) {
                implementosList.indexOfFirst { it.id == manualId }
            } else -1
        } else {
            // Com Job forçado, não destacamos item na lista
            -1
        }
    }

    private fun salvarImplementos() {
        val prefs = getSharedPreferences("implementos", MODE_PRIVATE)
        prefs.edit {
            putString("lista_implementos", Gson().toJson(implementosList))
        }
    }

    private fun configurarListView() {
        adapter = ImplementosAdapter(implementosList)
        listViewImplementos.adapter = adapter

        if (selectedPosition != -1) {
            listViewImplementos.setSelection(selectedPosition)
        }
    }

    private fun configurarBotoes() {
        btnNovoImplemento.setOnClickListener {
            startActivityForResult(
                Intent(this, NovoImplementoActivity::class.java),
                REQUEST_NOVO_IMPLEMENTO
            )
        }
    }

    private fun mostrarMenuOpcoes(implemento: Implemento, position: Int) {
        val options = arrayOf("Editar", "Excluir")

        AlertDialog.Builder(this)
            .setTitle(implemento.nome)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> editarImplemento(implemento)
                    1 -> excluirImplemento(implemento, position)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editarImplemento(implemento: Implemento) {
        val intent = Intent(this, NovoImplementoActivity::class.java)
        intent.putExtra("implemento_edicao", Gson().toJson(implemento))
        startActivityForResult(intent, REQUEST_EDITAR_IMPLEMENTO)
    }

    private fun excluirImplemento(implemento: Implemento, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Excluir Implemento")
            .setMessage("Tem certeza que deseja excluir \"${implemento.nome}\"?")
            .setPositiveButton("Excluir") { dialog, _ ->
                implementosList.removeAt(position)
                adapter.notifyDataSetChanged()
                salvarImplementos()

                if (selectedPosition == position) {
                    selectedPosition = -1
                    // Se NÃO há Job forçado, limpe a seleção manual
                    val isForced = ImplementosPrefs.isForcedByJob(this)
                    if (!isForced) {
                        getSharedPreferences("configs", MODE_PRIVATE).edit {
                            remove("implemento_selecionado_id")
                        }
                    }
                }

                Toast.makeText(this, "Implemento excluído", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Adapter personalizado
    inner class ImplementosAdapter(private val implementos: List<Implemento>) : BaseAdapter() {

        override fun getCount(): Int = implementos.size
        override fun getItem(position: Int): Implemento = implementos[position]
        override fun getItemId(position: Int): Long = implementos[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_implemento, parent, false)
            val implemento = implementos[position]

            val text1 = view.findViewById<TextView>(R.id.text1)
            val text2 = view.findViewById<TextView>(R.id.text2)
            val text3 = view.findViewById<TextView>(R.id.text3)
            val btnMenu = view.findViewById<ImageButton>(R.id.btnMenu)

            text1.text = implemento.nome
            text3.text = "id: ${implemento.id}"

            val detalhes = when (implemento.tipo) {
                "Plantadeira" -> "${implemento.numLinhas} linhas, ${implemento.espacamento}m espaçamento"
                "Pulverizador" -> "Barra: ${implemento.tamanhoBarra}m, ${implemento.numSecoes} seções"
                "Adubadora" -> "Capacidade: ${implemento.capacidade}kg, Vazão: ${implemento.vazao}kg/ha"
                "Colheitadeira" -> "Largura: ${implemento.larguraColheita}m, ${implemento.tipoPlataforma}"
                else -> implemento.tipo
            }
            text2.text = "${implemento.tipo} - $detalhes"

            view.setOnClickListener {
                // Bloqueia troca se houver Job selecionado/forçado
                if (ImplementosPrefs.isForcedByJob(this@ImplementosActivity)) {
                    Toast.makeText(
                        this@ImplementosActivity,
                        "Há um trabalho selecionado com implemento travado. Volte ao modo livre para trocar.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // Seleção manual: persiste e notifica Selector
                selectedPosition = position
                notifyDataSetChanged()
                Toast.makeText(
                    this@ImplementosActivity,
                    "Implemento selecionado: ${implemento.nome}",
                    Toast.LENGTH_SHORT
                ).show()

                ImplementoSelector.selectManualById(this@ImplementosActivity, implemento.id)

                // Opcional: fechar a tela após selecionar
                // finish()
            }

            btnMenu.setOnClickListener {
                mostrarMenuOpcoes(implemento, position)
                adapter.notifyDataSetChanged()
            }

            if (position == selectedPosition) {
                view.setBackgroundColor(ContextCompat.getColor(this@ImplementosActivity, R.color.selected_item_background))
            } else {
                view.setBackgroundColor(ContextCompat.getColor(this@ImplementosActivity, android.R.color.transparent))
            }

            return view
        }
    }

    companion object {
        const val REQUEST_NOVO_IMPLEMENTO = 1001
        const val REQUEST_EDITAR_IMPLEMENTO = 1002
    }
}
