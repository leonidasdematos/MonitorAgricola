// com/example/monitoragricola/ui/TrabalhosActivity.kt
package com.example.monitoragricola.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.monitoragricola.FREE_MODE_JOB_ID
import com.example.monitoragricola.R
import com.example.monitoragricola.implementos.ImplementoSelector
import com.example.monitoragricola.implementos.ImplementosPrefs
import com.example.monitoragricola.jobs.JobState
import com.example.monitoragricola.jobs.db.JobEntity
import com.example.monitoragricola.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

/**
 * TrabalhosActivity
 * -----------------
 * Nova UX:
 * - Card no topo destacando o trabalho SELECIONADO (implemento travado por Job), mesmo se pausado.
 * - Botão no card para "Voltar ao modo livre" (pausa se estiver ativo, limpa a força e o selected_job_id).
 * - Lista abaixo SEM Pausar/Retomar; usa "Iniciar/Selecionar" (abre na Main) e "Finalizar".
 * - Mantém Excluir, Reabrir e Reabrir c/ Rastro para estados finalizados/cancelados.
 */
class TrabalhosActivity : AppCompatActivity() {

    private val app by lazy { application as com.example.monitoragricola.App }
    private val repo get() = app.jobsRepository
    private val manager get() = app.jobManager

    private lateinit var list: RecyclerView
    private lateinit var adapter: JobsAdapter
    private lateinit var btnNovo: ImageButton

    // Card do selecionado
    private lateinit var cardSelecionado: View
    private lateinit var tvSelecionadoNomeEstado: TextView
    private lateinit var btnModoLivre: Button

    private var lastJobsSnapshot: List<JobEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trabalhos)

        list = findViewById(R.id.recyclerJobs)
        btnNovo = findViewById(R.id.btnNovoJob)

        cardSelecionado = findViewById(R.id.cardSelecionado)
        tvSelecionadoNomeEstado = findViewById(R.id.tvSelecionadoNomeEstado)
        btnModoLivre = findViewById(R.id.btnModoLivre)

        adapter = JobsAdapter(
            onResume = { job ->
                // "Iniciar/Selecionar" -> volta para a Main com auto_start=true (lá força implemento + marca selecionado)
                lifecycleScope.launch {
                    val i = Intent(this@TrabalhosActivity, MainActivity::class.java).apply {
                        putExtra("resume_job_id", job.id)
                        putExtra("auto_start", true)
                    }
                    startActivity(i)
                    finish()
                }
            },
            onPause = { /* removido da UI, não usado */ _ -> },
            onFinish = { job ->
                AlertDialog.Builder(this)
                    .setTitle("Finalizar trabalho?")
                    .setMessage("Deseja finalizar '${job.name}'?")
                    .setPositiveButton("Finalizar") { _, _ ->
                        lifecycleScope.launch {
                            manager.finish(job.id, job.areaM2, job.overlapM2)
                            // Se este for o selecionado, limpamos a força e o selected_job_id
                            val selId = ImplementosPrefs.getSelectedJobId(this@TrabalhosActivity)
                            if (selId == job.id) {
                                ImplementoSelector.clearForce(this@TrabalhosActivity)
                                ImplementosPrefs.clearSelectedJobId(this@TrabalhosActivity)
                                renderSelecionadoCard(lastJobsSnapshot)
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            onDelete = { job ->
                AlertDialog.Builder(this)
                    .setTitle("Excluir trabalho?")
                    .setMessage("Isto removerá rastro e cobertura de '${job.name}'.")
                    .setPositiveButton("Excluir") { _, _ ->
                        lifecycleScope.launch {
                            manager.delete(job.id)
                            // Se este for o selecionado, limpamos a força e o selected_job_id
                            val selId = ImplementosPrefs.getSelectedJobId(this@TrabalhosActivity)
                            if (selId == job.id) {
                                ImplementoSelector.clearForce(this@TrabalhosActivity)
                                ImplementosPrefs.clearSelectedJobId(this@TrabalhosActivity)
                                renderSelecionadoCard(lastJobsSnapshot)
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            },
            onReopen = { job, copyTrack ->
                val msg = if (copyTrack)
                    "Um novo trabalho será criado copiando cobertura e rastro (decimado)."
                else
                    "Um novo trabalho será criado copiando apenas a cobertura."
                AlertDialog.Builder(this)
                    .setTitle("Reabrir '${job.name}'?")
                    .setMessage(msg)
                    .setPositiveButton("Reabrir") { _, _ ->
                        lifecycleScope.launch {
                            val newId = manager.reopenAsNew(
                                oldJobId = job.id,
                                newName = "${job.name} (retomado)",
                                copyTrack = copyTrack,
                                decimateMeters = 0.5
                            )
                            if (newId > 0) {
                                val i = Intent(this@TrabalhosActivity, MainActivity::class.java).apply {
                                    putExtra("resume_job_id", newId)
                                    putExtra("auto_start", true)
                                }
                                startActivity(i)
                                finish()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // Observa a lista de jobs (Room Flow) e atualiza card + lista
        lifecycleScope.launch {
            repo.observeAll().collectLatest { items ->
                lastJobsSnapshot = items
                renderSelecionadoCard(items)
                adapter.submit(itemsWithoutSelected(items))
            }
        }

        // "Voltar ao modo livre" no card
        btnModoLivre.setOnClickListener {
            lifecycleScope.launch {
                val selId = ImplementosPrefs.getSelectedJobId(this@TrabalhosActivity)
                val selJob = lastJobsSnapshot.firstOrNull { it.id == selId }
                // Se estiver ativo, pause antes de sair para modo livre
                if (selJob?.state == JobState.ACTIVE) {
                    // (Opcional) salvar snapshot de cobertura aqui, se necessário (manager.saveCoverageSnapshot)
                    manager.pause(selJob.id)
                }
                ImplementoSelector.clearForce(this@TrabalhosActivity)
                ImplementosPrefs.clearSelectedJobId(this@TrabalhosActivity)
                Toast.makeText(this@TrabalhosActivity, "Voltando ao modo livre.", Toast.LENGTH_SHORT).show()
                renderSelecionadoCard(lastJobsSnapshot)
                adapter.submit(itemsWithoutSelected(lastJobsSnapshot))
            }
        }

        // Novo trabalho: pergunta o nome e já inicia ativo
        btnNovo.setOnClickListener { promptNovoTrabalho() }
    }

    private fun promptNovoTrabalho() {
        val input = android.widget.EditText(this).apply { hint = "Nome do trabalho" }
        AlertDialog.Builder(this)
            .setTitle("Novo trabalho")
            .setView(input)
            .setPositiveButton("Continuar") { _, _ ->
                val nome = input.text?.toString()?.ifBlank { null } ?: return@setPositiveButton
                // Se há snapshot do modo livre disponível, ofereça as opções:
                val app = application as com.example.monitoragricola.App
                val hasFreeSnapshot = runBlocking {
                    app.rasterDb.rasterTileDao().countByJob(FREE_MODE_JOB_ID) > 0
                }
                val options = if (hasFreeSnapshot)
                    arrayOf("Criar e iniciar (vazio)", "Criar e iniciar copiando do Modo Livre")
                else
                    arrayOf("Criar e iniciar")

                AlertDialog.Builder(this)
                    .setTitle("Como deseja iniciar?")
                    .setItems(options) { _, which ->
                        lifecycleScope.launch {
                            if (ImplementosPrefs.isForcedByJob(this@TrabalhosActivity)) {
                                Toast.makeText(
                                    this@TrabalhosActivity,
                                    "Há um trabalho selecionado. Finalize ou volte ao modo livre antes de criar outro.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                            val snapshot = ImplementoSelector.currentSnapshot(this@TrabalhosActivity)
                            if (snapshot == null) {
                                Toast.makeText(this@TrabalhosActivity, "Selecione um implemento antes de iniciar.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            val source = obterFonteAtual()
                            val id = manager.createAndStart(nome, snapshot, source)

                            // Força implemento para a Main retomar já com o implemento correto
                            ImplementoSelector.forceFromJob(this@TrabalhosActivity, snapshot)

                            val importFree = hasFreeSnapshot && (which == 1)
                            val i = Intent(this@TrabalhosActivity, MainActivity::class.java).apply {
                                putExtra("resume_job_id", id)
                                putExtra("auto_start", true)
                                putExtra("import_free_mode", importFree) // << FLAG NOVA
                            }
                            startActivity(i)
                            finish()
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    // Helpers: usamos as mesmas prefs da Main para snapshot de implemento e fonte
    private fun obterFonteAtual(): String {
        val prefs = getSharedPreferences("configs", MODE_PRIVATE)
        return prefs.getString("fonteCoordenada", "gps") ?: "gps"
    }

    // =============== Renderização do card e filtragem da lista ===============

    /** Mostra/esconde o card de selecionado conforme forçado e ID disponível. */
    private fun renderSelecionadoCard(items: List<JobEntity>) {
        val forced = ImplementosPrefs.isForcedByJob(this)
        if (!forced) {
            cardSelecionado.visibility = View.GONE
            return
        }

        val selId = ImplementosPrefs.getSelectedJobId(this)
        val selJob = items.firstOrNull { it.id == selId } ?: run {
            // Fallback: se não houver ID salvo, tente destacar o primeiro ACTIVE/PAUSED
            items.firstOrNull { it.state == JobState.ACTIVE || it.state == JobState.PAUSED }
        }

        if (selJob == null) {
            cardSelecionado.visibility = View.GONE
            return
        }

        val estado = when (selJob.state) {
            JobState.ACTIVE -> "Ativo"
            JobState.PAUSED -> "Pausado"
            else -> selJob.state.name
        }
        tvSelecionadoNomeEstado.text = "${selJob.name} — $estado"
        cardSelecionado.visibility = View.VISIBLE
    }

    /** Retorna a lista sem o selecionado (para não duplicar abaixo do card). */
    private fun itemsWithoutSelected(items: List<JobEntity>): List<JobEntity> {
        val forced = ImplementosPrefs.isForcedByJob(this)
        if (!forced) return items
        val selId = ImplementosPrefs.getSelectedJobId(this)
        val idToRemove = selId ?: items.firstOrNull {
            it.state == JobState.ACTIVE || it.state == JobState.PAUSED
        }?.id
        return if (idToRemove != null) items.filter { it.id != idToRemove } else items
    }
}

/* ======================== Adapter/ViewHolder ======================== */

private class JobsAdapter(
    val onResume: (JobEntity) -> Unit, // usado para "Iniciar/Selecionar"
    val onPause: (JobEntity) -> Unit,  // não usado mais (mantido por compat)
    val onFinish: (JobEntity) -> Unit,
    val onDelete: (JobEntity) -> Unit,
    val onReopen: (JobEntity, copyTrack: Boolean) -> Unit
) : RecyclerView.Adapter<JobVH>() {

    private val items = mutableListOf<JobEntity>()

    fun submit(newItems: List<JobEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_job, parent, false)
        return JobVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: JobVH, position: Int) {
        holder.bind(items[position], onResume, onPause, onFinish, onDelete, onReopen)
    }
}

private class JobVH(v: View) : RecyclerView.ViewHolder(v) {
    private val tvTitle: TextView = v.findViewById(R.id.tvJobTitle)
    private val tvSubtitle: TextView = v.findViewById(R.id.tvJobSubtitle)
    private val btnPrimary: TextView = v.findViewById(R.id.btnPrimary)
    private val btnSecondary: TextView = v.findViewById(R.id.btnSecondary)
    private val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)

    fun bind(
        job: JobEntity,
        onResume: (JobEntity) -> Unit,
        @Suppress("UNUSED_PARAMETER") onPause: (JobEntity) -> Unit,
        onFinish: (JobEntity) -> Unit,
        onDelete: (JobEntity) -> Unit,
        onReopen: (JobEntity, copyTrack: Boolean) -> Unit
    ) {
        tvTitle.text = job.name
        val state = job.state.name
        val areaTxt = job.areaM2?.let { " • Área: %.1f Ha".format(it / 10000.0) } ?: ""
        tvSubtitle.text = "Estado: $state$areaTxt"

        when (job.state) {
            JobState.ACTIVE, JobState.PAUSED -> {
                // Sem Pausar/Retomar. Agora é "Iniciar/Selecionar" + "Finalizar".
                btnPrimary.text = "Iniciar/Selecionar"
                btnSecondary.text = "Finalizar"
                btnPrimary.setOnClickListener { onResume(job) }
                btnSecondary.setOnClickListener { onFinish(job) }
            }
            JobState.COMPLETED, JobState.CANCELED -> {
                btnPrimary.text = "Reabrir"
                btnSecondary.text = "Reabrir c/ Rastro"
                btnPrimary.setOnClickListener { onReopen(job, false) }
                btnSecondary.setOnClickListener { onReopen(job, true) }
            }
        }

        btnDelete.setOnClickListener { onDelete(job) }
    }
}
