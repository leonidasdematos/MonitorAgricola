// com/example/monitoragricola/ui/MainActivity.kt
package com.example.monitoragricola.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.monitoragricola.R
import com.example.monitoragricola.implementos.ImplementoSelector
import com.example.monitoragricola.implementos.ImplementosPrefs
import com.example.monitoragricola.implementos.ImplementoSnapshot
import com.example.monitoragricola.jobs.JobEventType
import com.example.monitoragricola.map.*
import com.example.monitoragricola.ui.routes.RoutesActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import kotlin.math.*
import android.Manifest
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.WindowManager

// Raster
import com.example.monitoragricola.raster.HotVizMode
import com.example.monitoragricola.raster.LAYER_RATE
import com.example.monitoragricola.raster.LAYER_SECTIONS
import com.example.monitoragricola.raster.LAYER_SPEED
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.RasterCoverageOverlay
import com.example.monitoragricola.raster.TileStore
import com.example.monitoragricola.raster.store.RoomTileStore
import com.example.monitoragricola.FREE_MODE_JOB_ID
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.RasterSnapshot
import com.example.monitoragricola.raster.store.JobRasterMetadata
import com.example.monitoragricola.raster.store.RasterTileCoord
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource

private const val TAG_RASTER = "RASTER"

class MainActivity : AppCompatActivity() {
    private var lastStatsLog = 0L


    /* ======================= DI / Jobs ======================= */
    private val app by lazy { application as com.example.monitoragricola.App }
    private val jobManager get() = app.jobManager
    private val jobRecorder get() = app.jobRecorder
    private val jobsRepo get() = app.jobsRepository



    /** ID do job selecionado (pode estar ACTIVE ou PAUSED). */
    private var selectedJobId: Long? = null
    /** Se está gravando (play/pause do botão). */
    private var isWorking = false
    // no topo da classe
    private var coldStart = true

    private val statePrefs by lazy { getSharedPreferences("main_state", MODE_PRIVATE) }
    private var lastWorkingFlag: Boolean = false


    /* ======================= Mapa / Raster ======================= */
    private lateinit var map: MapView
    private lateinit var tractor: Marker

    private lateinit var rasterEngine: RasterCoverageEngine
    private lateinit var rasterOverlay: RasterCoverageOverlay

    private var mapReady = false
    private var lastViewport: BoundingBox? = null
    private var viewportRestorePaused = false
    private var startViewportUpdatesAfterRestore = false
    private var rasterRestoreJob: Job? = null

    private val freeTileStore by lazy { RoomTileStore(app.rasterDb, FREE_MODE_JOB_ID, maxCacheTiles = 16) }
    private val noopTileStore = object : TileStore {
        override  fun loadTile(tx: Int, ty: Int) = null
        override suspend fun saveDirtyTilesAndClear(list: List<Pair<TileKey, TileData>>) {}

    }


    private var currentTileStore: TileStore? = null


    /* ======================= UI ======================= */
    private lateinit var tvVelocidade: TextView
    private lateinit var tvImplemento: TextView
    private lateinit var tvArea: TextView
    private lateinit var tvSobreposicao: TextView
    private lateinit var btnConfig: ImageButton
    private lateinit var btnLayerToggle: ImageButton
    private lateinit var btnImplementos: ImageButton
    private lateinit var btnLigar: ImageButton
    private lateinit var btnRotas: ImageButton
    private lateinit var btnTrabalhos: ImageButton
    private lateinit var tvJobState: TextView
    private lateinit var tvLinhaAlvo: TextView
    private lateinit var tvErroLateral: TextView
    private lateinit var rasterLoadingOverlay: View
    private lateinit var progressSavingRaster: ProgressBar

    private var currentHotVizMode: HotVizMode = HotVizMode.COBERTURA

    private data class LayerOption(
        val mode: HotVizMode,
        @StringRes val labelRes: Int,
        val requiredMask: Int? = null,
    )

    private val layerOptions = listOf(
        LayerOption(HotVizMode.COBERTURA, R.string.layer_mode_coverage),
        LayerOption(HotVizMode.SOBREPOSICAO, R.string.layer_mode_overlap),
        LayerOption(HotVizMode.TAXA, R.string.layer_mode_rate, LAYER_RATE),
        LayerOption(HotVizMode.VELOCIDADE, R.string.layer_mode_speed, LAYER_SPEED),
        LayerOption(HotVizMode.SECOES, R.string.layer_mode_sections, LAYER_SECTIONS),
    )

    private var speedEmaKmh: Double? = null   // filtro exponencial da velocidade
    private var savingOps = 0
    @Volatile private var suspendRasterUpdates = 0
    private val rasterSaveMutex = Mutex()
    @Volatile private var pendingRasterSaveJob: Job? = null



    private var followTractor = true
    private val followDelayMs = 8000L
    private val followHandler = Handler(Looper.getMainLooper())

    /* ======================= Loop / posição ======================= */
    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 10L
    private var lastHotUpdate = 0L
    private var lastViewportUpdate = 0L

    private var lastPoint: GeoPoint? = null
    private var interpolatedPosition: GeoPoint? = null
    private val interpolationFactor = 0.2f
    private var lastHeading: Float = 0f

    private var keptRunningInBackground = false
    private var navigatingAway = false
    private var mapLoopStarted = false

    private var activeImplemento: Implemento? = null
    private var positionProvider: PositionProvider? = null
    private var simulatorProvider: TractorSimulatorProvider? = null

    private var lastSpeedCalcTime: Long = 0
    private var lastSpeedPoint: GeoPoint? = null

    private var lastPositionMillis: Long = 0L
    private var isSignalLost: Boolean = false
    private val SIGNAL_LOSS_THRESHOLD_MS = 6_000L

    private var currentMinDistMeters: Double = 0.25
    private var viewportLoopJob: Job? = null
    private var viewportUpdateJob: Job? = null
    private var hotCenterJob: Job? = null



    /* ======================= Rotas ======================= */
    private var routeRenderer: com.example.monitoragricola.jobs.routes.RouteRenderer? = null
    private var activeRoute: com.example.monitoragricola.jobs.routes.db.JobRouteEntity? = null
    private var refCenterWkb: ByteArray? = null
    private var pendingA: GeoPoint? = null
    private var pendingB: GeoPoint? = null
    private var activeRouteId: Long? = null
    private val routePolylines = mutableListOf<org.osmdroid.views.overlay.Polyline>()
    private var implementBar: org.osmdroid.views.overlay.Polyline? = null
    private var implementLink: org.osmdroid.views.overlay.Polyline? = null
    private var refStartSeq: Int? = null
    private var refEndSeq: Int? = null

    private var pendingRestore = false


    /* ======================= Permissão ======================= */
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGpsProvider()
            else Toast.makeText(this, "Permissão de localização negada", Toast.LENGTH_SHORT).show()
        }

    /* ======================= Ciclo de vida ======================= */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            cacheMapTileCount = 5.toShort()
            tileFileSystemCacheMaxBytes = 0L
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        progressSavingRaster = findViewById(R.id.progressSavingRaster)


        coldStart = (savedInstanceState == null) // só é true no primeiro launch desse processo/atividade


        map = findViewById(R.id.map)
        tvVelocidade = findViewById(R.id.tvVelocidade)
        tvImplemento = findViewById(R.id.tvImplemento)
        tvArea = findViewById(R.id.tvArea)
        tvSobreposicao = findViewById(R.id.tvSobreposicao)
        rasterLoadingOverlay = findViewById(R.id.rasterLoadingOverlay)
        btnConfig = findViewById(R.id.btnConfigTop)
        btnLayerToggle = findViewById(R.id.btnLayerToggle)
        btnLigar = findViewById(R.id.btnLigar)
        btnRotas = findViewById(R.id.btnRotas)
        btnTrabalhos = findViewById(R.id.btnTrabalhos)
        tvJobState = findViewById(R.id.tvJobState)
        tvLinhaAlvo = findViewById(R.id.tvLinhaAlvo)
        tvErroLateral = findViewById(R.id.tvErroLateral)
        btnImplementos = findViewById(R.id.btnImplementos)


        val container = findViewById<ConstraintLayout>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }

        restorePlayState()
        syncPlayUi()
        setupMap()
        setupButtons()
        refreshJobsButtonColor()
        refreshImplementosButtonColor()
        refreshPlayButtonColor()

    }

    override fun onStart() {
        super.onStart()

        btnLigar.setImageResource(
            if (isWorking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        lifecycleScope.launch {
            if (keptRunningInBackground) {
                clearResumeExtras()
                return@launch
            }

            // 1) Retomada vinda da TrabalhosActivity
            val intentJobId = intent.getLongExtra("resume_job_id", -1L)
            if (intentJobId > 0) {
                isWorking = false
                persistPlayState()
                btnLigar.setImageResource(android.R.drawable.ic_media_play)

                val job = withContext(Dispatchers.IO) { jobManager.get(intentJobId) }

                val importFree = intent.getBooleanExtra("import_free_mode", false)
                if (importFree) {
                    val snap = withContext(Dispatchers.IO) { freeTileStore.restore() }
                    if (snap != null) {
                        rasterEngine.importSnapshot(snap)
                        if (job != null) {
                            val store = RoomTileStore(app.rasterDb, job.id)
                            rasterEngine.attachStore(store)
                            withSavingIndicator(suspendLoops = true) {
                                persistRaster(job.id, store)
                            }
                            rasterEngine.attachStore(freeTileStore)
                        }

                        freeTileStore.clear()
                        app.clearFreeModeTileStore()
                        rasterEngine.attachStore(noopTileStore)
                        ensureHotVizModeIsAvailable()
                    }
                }


            if (job == null) {
                Toast.makeText(this@MainActivity, "Trabalho não encontrado.", Toast.LENGTH_SHORT).show()
            } else {
                // força snapshot do job e persiste seleção
                selectImplementoFromJob(job)
                selectedJobId = job.id
                ImplementosPrefs.setSelectedJobId(this@MainActivity, job.id)
                    // Restaura cobertura raster no mapa (pausado)
                restoreRasterOnMap(job.id)

                btnLigar.setImageResource(android.R.drawable.ic_media_play)
                refreshJobsButtonColor()
                refreshImplementosButtonColor()
                refreshPlayButtonColor()
                refreshJobState()
                Toast.makeText(this@MainActivity, "Trabalho selecionado (aguardando ▶).", Toast.LENGTH_SHORT).show()
                clearResumeExtras()
            }
                syncPlayUi()
                clearResumeExtras()
                return@launch
            }

            // 2) recupera último estado persistido
            if (selectedJobId == null) {
                // modo livre
                ImplementoSelector.clearForce(this@MainActivity)

                ImplementoSelector.currentSnapshot(this@MainActivity)?.let { snap ->
                    val impl = buildImplementoFromSnapshot(snap)
                    selectImplemento(impl, origin = "manual")
                } ?: run {
                    activeImplemento?.stop()
                    activeImplemento = null
                    tvImplemento.text = "Nenhum implemento selecionado"
                }

                rasterEngine.attachStore(freeTileStore)
                val snap = withContext(Dispatchers.IO) { freeTileStore.restore() }
                if (snap != null) {
                    rasterEngine.importSnapshot(snap)
                    rasterOverlay.invalidateTiles()
                    map.invalidate()
                } else {
                    // se não tiver snapshot, apenas garanta o redraw
                    rasterEngine.invalidateTiles()
                    map.invalidate()
                }
                recomposeCoverageMetrics()
                ensureHotVizModeIsAvailable()
            } else {
                // reaplica força e cobertura raster (pausado)
                val selId = selectedJobId!!
                ensureSelectedForceApplied(selId)
                restoreRasterOnMap(selId)
            }

            refreshJobsButtonColor()
            refreshImplementosButtonColor()
            refreshPlayButtonColor()
            refreshJobState()
            syncPlayUi()

        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()

        // Restaura o estado salvo (1ª abertura ou recriação de processo)
        restorePlayState()

        if (coldStart) {
            // 🔒 Sempre começar pausado no 1º launch "do zero"
            isWorking = false
            persistPlayState()
            coldStart = false
        }

        val fonte = getSharedPreferences("configs", MODE_PRIVATE)
            .getString("fonteCoordenada", "gps")

        val skipRestore = keptRunningInBackground
        navigatingAway = false
        keptRunningInBackground = false

        if (skipRestore) {
            // ✅ Rodando em background: NÃO recrie providers/implemento/raster.
            // Apenas sincronize UI e mantenha tudo como estava.
            lastWorkingFlag = isWorking

            // Providers só se estiverem nulos (caso o SO os tenha matado)
            when (fonte) {
                "gps", "rtk" -> if (positionProvider == null) checkLocationPermission()
                "simulador" -> if (positionProvider == null) {
                    simulatorProvider = TractorSimulatorProvider(map, tractor)
                    positionProvider  = simulatorProvider
                    activeImplemento?.let { simulatorProvider?.setImplemento(it) } // não chama start()
                    positionProvider?.start()
                }
            }

            // UI
            refreshJobsButtonColor()
            refreshImplementosButtonColor()
            refreshPlayButtonColor()
            syncPlayUi() // atualiza ícone/label
            if (!mapLoopStarted) { startMapUpdates(); mapLoopStarted = true }

            return
        }

        // ====== Fluxo normal (NÃO estava rodando em bg): pode recriar ======
        lastWorkingFlag = isWorking

        // Reset providers/caches (garante estado limpo)
        positionProvider?.stop(); positionProvider = null
        simulatorProvider?.stop(); simulatorProvider = null
        resetCache()

        selectedJobId = ImplementosPrefs.getSelectedJobId(this)

        // Implemento / UI (não altera isWorking aqui)
        val snap = ImplementoSelector.currentSnapshot(this)
        val hasForced = (ImplementosPrefs.getForcedSnapshot(this) != null)
        if (snap == null) {
            tvImplemento.text = "Nenhum implemento selecionado"
            activeImplemento?.stop()
            activeImplemento = null
        } else {
            tvImplemento.text = if (hasForced) "Implemento (Job): ${snap.nome}" else "Implemento: ${snap.nome}"
            val impl = buildImplementoFromSnapshot(snap)

            // Se estiver forçado por Job, restaure o estado runtime (articulação etc.) ANTES de aplicar
            val selId = selectedJobId
            if (hasForced && selId != null) {
                (impl as? ImplementoBase)?.importRuntimeState(app.implementoStateStore.load(selId))
            }

            // selectImplemento respeita isWorking (start/stop coerente)
            selectImplemento(impl, origin = if (hasForced) "forced" else "manual")
        }

        // Fonte de posição
        when (fonte) {
            "gps", "rtk" -> checkLocationPermission()
            "simulador" -> {
                simulatorProvider = TractorSimulatorProvider(map, tractor)
                positionProvider  = simulatorProvider
                activeImplemento?.let { simulatorProvider?.setImplemento(it) } // não chama start()
                positionProvider?.start()
            }
        }

        // Restore do raster quando o mapa estiver pronto
        if (mapReady) {
            tryRestoreSelectedJobRaster()
        } else {
            pendingRestore = true
        }

        if (!mapLoopStarted) {
            startMapUpdates()
            mapLoopStarted = true
        }

        refreshJobsButtonColor()
        refreshImplementosButtonColor()
        refreshPlayButtonColor()
        syncPlayUi()
    }



    override fun onStop() {
        super.onStop()

        lifecycleScope.launch { flushRasterSync("onStop") }

        val shouldKeep = (isWorking || navigatingAway) && !isFinishing
        if (!shouldKeep) {
            positionProvider?.stop()
            simulatorProvider?.stop()
            handler.removeCallbacksAndMessages(null)
            persistPlayState()
            stopCheckpointLoop()
        }

        // << NOVO: se for modo livre, persista o snapshot ao parar
        if (selectedJobId == null) {
            runCatching {
                val snap = rasterEngine.exportSnapshot()
                freeTileStore.snapshot(snap)
            }
        }
    }

    override fun onPause() {
        if (::map.isInitialized) {
            map.onPause()
        }
        // 1) snapshot rápido raster antes de sair
        val selId = selectedJobId
        if (selId != null) {
            val store = currentTileStore
            if (store != null) {
                val job = lifecycleScope.launch {
                    withSavingIndicator(suspendLoops = true) {
                        withContext(Dispatchers.IO + NonCancellable) {
                            runCatching { persistRaster(selId, store) }
                        }
                    }
                }
                pendingRasterSaveJob = job
                job.invokeOnCompletion {
                    if (pendingRasterSaveJob === job) {
                        pendingRasterSaveJob = null
                    }
                }
            }
        } else {
            val snap = runCatching { rasterEngine.exportSnapshot() }.getOrNull()
            if (snap != null) freeTileStore.snapshot(snap)
        }

        // 2) decidir background
        val keep = (isWorking || navigatingAway) && !isFinishing
        keptRunningInBackground = keep

        if (!keep) {
            positionProvider?.stop()
            simulatorProvider?.stop()
            handler.removeCallbacksAndMessages(null)
            stopCheckpointLoop()
            mapLoopStarted = false
        }

        selectedJobId?.let { id ->
            (activeImplemento as? ImplementoBase)?.exportRuntimeState()?.let { st ->
                app.implementoStateStore.save(id, st)
            }
        }
        persistPlayState()
        super.onPause()
    }

    /* ======================= Setup ======================= */

    private fun setupMap() {
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)

        // qualquer tilesource só pra inicializar; não será usado
        map.setUseDataConnection(false) // <- não baixa tiles
        map.overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.WHITE)
        map.overlayManager.tilesOverlay.setLoadingLineColor(Color.WHITE) // remove grade



        val startPoint = GeoPoint(-23.4000, -54.2000)
        map.controller.setZoom(22.0)
        map.maxZoomLevel = 22.0

        val rotationGestureOverlay = RotationGestureOverlay(map).apply { isEnabled = true }
        map.overlays.add(rotationGestureOverlay)

        // Engine e overlay: crie uma vez
        rasterEngine = RasterCoverageEngine().apply {
            startJob(
                originLat = startPoint.latitude,
                originLon = startPoint.longitude,
                resolutionM = 0.10,
                tileSize = 256
            )
            setMode(HotVizMode.COBERTURA) // <- força visualização de cobertura

            Log.d(
                TAG_RASTER,
                "Engine startJob: origin=(${currentOriginLat()}, ${currentOriginLon()}) res=${currentResolutionM()} tile=${currentTileSize()}"
            )
        }
        rasterEngine.attachStore(freeTileStore)
        currentTileStore = freeTileStore
        rasterOverlay = RasterCoverageOverlay(map, rasterEngine)

        // Adicione o raster antes do trator, para o trator ficar por cima
        map.overlays.add(rasterOverlay)

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleViewportUpdate()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                if (map.zoomLevelDouble > 22.0) {
                    map.controller.setZoom(22.0)
                    return true
                }
                scheduleViewportUpdate()
                return false
            }
        })


        tractor = Marker(map).apply {
            position = startPoint
            title = getString(R.string.trator)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = android.graphics.drawable.BitmapDrawable(resources, makeGnssAntennaBitmap(56))
            setInfoWindow(null)
        }
        map.overlays.add(tractor)

        // Marca quando o mapa tiver dimensões reais
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            mapReady = true
            if (pendingRestore) {
                pendingRestore = false
                tryRestoreSelectedJobRaster()   // roda o restore agora que o mapa está pronto
            } else {
                // força primeiro redraw mesmo sem restore
                rasterEngine.invalidateTiles()
                // se seu overlay tiver invalidateTiles(), pode chamar aqui também:
                // rasterOverlay.invalidateTiles()
                map.invalidate()
            }
            scheduleViewportUpdate()
        }
    }


    private fun setupButtons() {
        btnRotas.setOnClickListener {
            navigatingAway = true
            startActivity(Intent(this, RoutesActivity::class.java))
        }
        btnRotas.setOnLongClickListener { mostrarAtalhoRotasAB(btnRotas); true }
        btnConfig.setOnClickListener {
            navigatingAway = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnLayerToggle.setOnClickListener { showLayerSelection(it) }
        btnTrabalhos.setOnClickListener {
            navigatingAway = true
            startActivity(Intent(this, TrabalhosActivity::class.java))
        }

        btnConfig.setOnLongClickListener {
            setHotLayerMode(HotVizMode.COBERTURA, showFeedback = true)
            true

        }

        btnImplementos.setOnClickListener {
            val hasJob = ImplementosPrefs.getSelectedJobId(this) != null
            if (hasJob) {
                Toast.makeText(this, "Há um trabalho selecionado. Volte ao modo livre para trocar implemento.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            navigatingAway = true
            startActivity(Intent(this, ImplementosActivity::class.java))
        }

        btnTrabalhos.setOnLongClickListener {
            lifecycleScope.launch {
                val active = jobManager.getActive()
                val hasActive = active != null
                val options = mutableListOf<String>()
                if (hasActive) {
                    if (isWorking) { options += "Pausar trabalho"; options += "Finalizar trabalho" }
                    else           { options += "Retomar trabalho"; options += "Finalizar trabalho" }
                }
                options += "Criar novo trabalho"
                options += "Abrir lista de trabalhos"

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Atalhos de Trabalhos")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (options[which]) {
                            "Retomar trabalho" -> active?.let { job ->
                                lifecycleScope.launch {
                                    selectImplementoFromJob(job)
                                    selectedJobId = job.id
                                    refreshJobsButtonColor()
                                    refreshImplementosButtonColor()
                                    refreshPlayButtonColor()
                                    val store = RoomTileStore(app.rasterDb, job.id)
                                    rasterEngine.attachStore(store)
                                    currentTileStore = store
                                    withContext(Dispatchers.IO) { jobManager.resume(job.id) }
                                    isWorking = true
                                    persistPlayState()
                                    syncPlayUi()
                                    btnLigar.setImageResource(android.R.drawable.ic_media_pause)
                                    startCheckpointLoop()
                                    Toast.makeText(this@MainActivity, "Trabalho retomado", Toast.LENGTH_SHORT).show()
                                    refreshJobState()
                                }
                            }
                            "Pausar trabalho" -> active?.let { job ->
                                lifecycleScope.launch {
                                    currentTileStore?.let { ts ->
                                        withSavingIndicator(suspendLoops = true) {
                                            persistRaster(job.id, ts)
                                        }
                                    }
                                    jobManager.pause(job.id)
                                    rasterEngine.attachStore(freeTileStore)
                                    currentTileStore = freeTileStore
                                    isWorking = false
                                    persistPlayState()
                                    syncPlayUi()
                                    stopCheckpointLoop()
                                    btnLigar.setImageResource(android.R.drawable.ic_media_play)
                                    Toast.makeText(this@MainActivity, "Trabalho pausado", Toast.LENGTH_SHORT).show()
                                    refreshJobState()
                                    refreshJobsButtonColor()
                                    refreshImplementosButtonColor()
                                    refreshPlayButtonColor()
                                }
                            }
                            "Finalizar trabalho" -> active?.let { job ->
                                lifecycleScope.launch {
                                    val areas = rasterEngine.getAreas()
                                    currentTileStore?.let { ts ->
                                        withSavingIndicator(suspendLoops = true) {
                                            persistRaster(job.id, ts)
                                        }
                                    }
                                    jobManager.finish(job.id, areas.effectiveM2, areas.overlapM2)
                                    rasterEngine.attachStore(freeTileStore)
                                    currentTileStore = freeTileStore
                                    ImplementoSelector.clearForce(this@MainActivity)
                                    ImplementosPrefs.clearSelectedJobId(this@MainActivity)
                                    selectedJobId = null
                                    isWorking = false
                                    persistPlayState()
                                    syncPlayUi()
                                    stopCheckpointLoop()
                                    btnLigar.setImageResource(android.R.drawable.ic_media_play)
                                    Toast.makeText(this@MainActivity, "Trabalho finalizado", Toast.LENGTH_SHORT).show()
                                    refreshJobState()
                                    refreshJobsButtonColor()
                                    refreshImplementosButtonColor()
                                    refreshPlayButtonColor()
                                }
                            }
                            "Criar novo trabalho" -> {
                                navigatingAway = true
                                startActivity(Intent(this@MainActivity, TrabalhosActivity::class.java))
                            }
                            "Abrir lista de trabalhos" -> {
                                navigatingAway = true
                                startActivity(Intent(this@MainActivity, TrabalhosActivity::class.java))
                            }
                        }
                    }
                    .show()
            }
            true
        }

        btnLigar.setOnClickListener {
            isWorking = !isWorking
            btnLigar.isSelected = isWorking
            lifecycleScope.launch {
                val selId = selectedJobId
                val job = if (selId != null) withContext(Dispatchers.IO) { jobManager.get(selId) } else null

                if (isWorking) {
                    if (job == null) {
                        // MODO LIVRE
                        activeImplemento?.start()
                        btnLigar.setImageResource(android.R.drawable.ic_media_pause)
                        Toast.makeText(this@MainActivity, "Modo livre iniciado. Para gravar, vá em Trabalhos e crie um novo.", Toast.LENGTH_SHORT).show()
                        refreshJobState()
                    } else {
                        val currentSnap = ImplementoSelector.currentSnapshot(this@MainActivity)
                        val forcedSnap  = try { com.google.gson.Gson().fromJson(job.implementoSnapshotJson, ImplementoSnapshot::class.java) } catch (_: Throwable) { null }

                        val isSame =
                            currentSnap != null && forcedSnap != null &&
                                    currentSnap.tipo?.lowercase() == forcedSnap.tipo?.lowercase() &&
                                    currentSnap.numLinhas == forcedSnap.numLinhas &&
                                    (currentSnap.larguraTrabalhoM ?: 0f) == (forcedSnap.larguraTrabalhoM ?: 0f) &&
                                    (currentSnap.espacamentoM    ?: 0f) == (forcedSnap.espacamentoM    ?: 0f)

                        if (!isSame) {
                            selectImplementoFromJob(job)
                        } else {
                            ImplementosPrefs.setSelectedJobId(this@MainActivity, job.id)
                        }

                        val store = RoomTileStore(app.rasterDb, job.id)
                        rasterEngine.attachStore(store)
                        currentTileStore = store
                        activeImplemento?.start()
                        btnLigar.setImageResource(android.R.drawable.ic_media_pause)
                        withContext(Dispatchers.IO) { jobManager.resume(selId!!) }
                        startCheckpointLoop()
                        Toast.makeText(this@MainActivity, "Trabalho retomado", Toast.LENGTH_SHORT).show()
                        refreshJobState()
                    }
                } else {
                    // salvar estado do implemento (ex.: theta articulado)
                    val st = (activeImplemento as? ImplementoBase)?.exportRuntimeState()
                    if (st != null && selId != null) app.implementoStateStore.save(selId, st)

                    activeImplemento?.stop()
                    btnLigar.setImageResource(android.R.drawable.ic_media_play)

                    selId?.let { id ->
                        currentTileStore?.let { ts ->
                            withSavingIndicator(suspendLoops = true) {
                                persistRaster(id, ts)
                            }
                        }
                        withContext(Dispatchers.IO) { jobManager.pause(id) }
                        rasterEngine.attachStore(freeTileStore)
                        currentTileStore = freeTileStore
                        stopCheckpointLoop()
                        Toast.makeText(this@MainActivity, "Trabalho pausado", Toast.LENGTH_SHORT).show()
                    } ?: run {
                        stopCheckpointLoop()
                        Toast.makeText(this@MainActivity, "Pausado (modo livre)", Toast.LENGTH_SHORT).show()
                    }
                    refreshJobState()
                }
                persistPlayState()
                syncPlayUi()
            }
        }

        // Desativar follow ao interagir no mapa
        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE ||
                event.action == MotionEvent.ACTION_POINTER_DOWN
            ) disableFollowTemporarily()
            false
        }

        ensureHotVizModeIsAvailable()
    }

    private fun availableLayerOptions(mask: Int): List<LayerOption> =
        layerOptions.filter { option ->
            option.requiredMask == null || option.requiredMask and mask != 0
        }

    private fun showLayerSelection(anchor: View) {
        val mask = rasterEngine.availableLayerMask()
        val options = availableLayerOptions(mask)
        if (options.isEmpty()) return

        val popup = PopupMenu(this, anchor)
        val groupId = 0
        options.forEachIndexed { index, option ->
            popup.menu.add(groupId, index, index, getString(option.labelRes)).apply {
                isCheckable = true
                isChecked = option.mode == currentHotVizMode
            }
        }
        popup.menu.setGroupCheckable(groupId, true, true)
        popup.setOnMenuItemClickListener { item ->
            val option = options.getOrNull(item.itemId)
            if (option != null) {
                setHotLayerMode(option.mode, showFeedback = true)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun setHotLayerMode(mode: HotVizMode, showFeedback: Boolean) {
        updateLayerButtonContentDescription(mode)
        if (mode == currentHotVizMode) {
            if (showFeedback) {
                showLayerSelectionFeedback(mode)
            }
            return
        }

        currentHotVizMode = mode
        rasterEngine.setMode(mode)
        rasterOverlay.invalidateTiles()
        map.invalidate()
        if (showFeedback) {
            showLayerSelectionFeedback(mode)
        }
    }

    private fun showLayerSelectionFeedback(mode: HotVizMode) {
        labelForMode(mode)?.let { labelRes ->
            Toast.makeText(
                this,
                getString(R.string.layer_mode_selected, getString(labelRes)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun labelForMode(mode: HotVizMode): Int? =
        layerOptions.firstOrNull { it.mode == mode }?.labelRes

    private fun updateLayerButtonContentDescription(mode: HotVizMode = currentHotVizMode) {
        if (!::btnLayerToggle.isInitialized) return
        val labelRes = labelForMode(mode) ?: return
        val description = getString(
            R.string.layer_button_content_description_with_value,
            getString(labelRes)
        )
        btnLayerToggle.contentDescription = description
        ViewCompat.setTooltipText(btnLayerToggle, description)
    }

    private fun ensureHotVizModeIsAvailable() {
        if (!::rasterEngine.isInitialized) return
        val mask = rasterEngine.availableLayerMask()
        val options = availableLayerOptions(mask)
        if (options.none { it.mode == currentHotVizMode }) {
            setHotLayerMode(HotVizMode.COBERTURA, showFeedback = false)
        } else {
            updateLayerButtonContentDescription()
        }

        if (::btnLayerToggle.isInitialized) {
            val enabled = options.isNotEmpty()
            btnLayerToggle.isEnabled = enabled
            btnLayerToggle.isClickable = enabled
            btnLayerToggle.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun startViewportUpdates() {
        if (viewportRestorePaused) {
            startViewportUpdatesAfterRestore = true
            return
        }
        val previousJob = viewportLoopJob
        viewportLoopJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                previousJob?.cancelAndJoin()
                rasterEngine.updateViewport(map.boundingBox)
                delay(1000L)
            }
        }
    }


    /* ======================= Loop do mapa ======================= */
    private fun scheduleViewportUpdate(force: Boolean = false): Boolean {
        if (viewportRestorePaused) return false
        val bb = map.boundingBox
        if (!force && bb == lastViewport) return false
        lastViewport = bb
        val previousJob = viewportUpdateJob
        viewportUpdateJob = lifecycleScope.launch(Dispatchers.Default) {
            previousJob?.cancelAndJoin()
            rasterEngine.updateViewport(bb)
            withContext(Dispatchers.Main) { map.postInvalidate() }
        }
        lastViewportUpdate = System.currentTimeMillis()
        return true
    }

    private fun pauseViewportUpdatesForRestore() {
        viewportRestorePaused = true
        startViewportUpdatesAfterRestore = startViewportUpdatesAfterRestore || mapLoopStarted
        if (viewportLoopJob?.isActive == true) {
            startViewportUpdatesAfterRestore = true
        }
        viewportLoopJob?.cancel()
        viewportLoopJob = null
        viewportUpdateJob?.cancel()
        viewportUpdateJob = null
    }

    private fun resumeViewportUpdatesAfterRestore() {
        if (!viewportRestorePaused) return
        viewportRestorePaused = false
        val shouldStart = startViewportUpdatesAfterRestore || mapLoopStarted
        startViewportUpdatesAfterRestore = false
        if (shouldStart) {
            startViewportUpdates()
        }
        lastViewport = null
        scheduleViewportUpdate()
    }
    private fun startMapUpdates() {
        startViewportUpdates()
        handler.post(object : Runnable {
            override fun run() {
                positionProvider?.getCurrentPosition()?.let { pos ->
                    lastPositionMillis = System.currentTimeMillis()
                    if (isSignalLost) {
                        isSignalLost = false
                        lifecycleScope.launch { logSignalEvent(lost = false) }
                        Toast.makeText(this@MainActivity, "Sinal de posição restabelecido", Toast.LENGTH_SHORT).show()
                    }

                    interpolatedPosition = interpolatedPosition?.let {
                        val lat = it.latitude + interpolationFactor * (pos.latitude - it.latitude)
                        val lon = it.longitude + interpolationFactor * (pos.longitude - it.longitude)
                        GeoPoint(lat, lon)
                    } ?: pos

                    val currentPos = interpolatedPosition!!
                    tractor.position = currentPos


                    val now = System.currentTimeMillis()
                    if (now - lastHotUpdate > 100) {
                        val lat = currentPos.latitude
                        val lon = currentPos.longitude
                        val previousJob = hotCenterJob
                        hotCenterJob = lifecycleScope.launch(Dispatchers.Default) {
                            previousJob?.cancelAndJoin()
                            rasterEngine.updateTractorHotCenter(lat, lon)
                        }
                        lastHotUpdate = now
                    }

                    if (now - lastStatsLog > 2000) {
                        val s = rasterEngine.debugStats()
                        Log.d("RASTER",
                            "tileSize=${s.tileSize} res=${"%.2f".format(s.resolutionM)} " +
                                    "HOTr=${s.hotRadius} dataTiles=${s.tilesDataCount} " +
                                    "HOT=${s.hotCount} VIZ=${s.vizCount} bmpLRU=${s.bmpLruSize} dataLRU=${s.dataLruSize}"
                        )
                        lastStatsLog = now
                    }


                    val implBase = activeImplemento as? ImplementoBase
                    if (lastWorkingFlag != isWorking) {
                        if (isWorking) implBase?.start() else implBase?.stop()
                        lastWorkingFlag = isWorking
                    }

                    val skipRasterOps = suspendRasterUpdates > 0
                    // Mantém a geometria atualizada sempre e apenas pausa a pintura/telemetria.
                    implBase?.setRasterSuspended(skipRasterOps)
                    activeImplemento?.updatePosition(lastPoint, currentPos)

                    lastPoint?.let { last ->
                        val dist = last.distanceToAsDouble(currentPos)
                        if (dist > 0.01) {
                            val heading = calculateBearing(last, currentPos)
                            val diff = ((heading - lastHeading + 540) % 360) - 180
                            if (abs(diff) > 0.1f && followTractor) {
                                lastHeading = (lastHeading + diff + 360) % 360
                                map.setMapOrientation(-lastHeading)
                            }
                        }
                    }

                    if (followTractor) {
                        map.controller.setCenter(currentPos)
                        scheduleViewportUpdate()
                    }
                    if (now - lastViewportUpdate > 500) {
                        scheduleViewportUpdate(force = true)

                    }

                    // Só grava telemetria do job quando estiver trabalhando.
                    if (!skipRasterOps && isWorking) {

                        selectedJobId?.let { id ->
                            jobRecorder.onTick(
                                jobId = id,
                                lat = currentPos.latitude,
                                lon = currentPos.longitude,
                                tMillis = System.currentTimeMillis(),
                                speedKmh = null,
                                headingDeg = lastHeading
                            )
                        }
                    }

                    tvVelocidade.text = calcularVelocidadeCache(currentPos)
                    ajustarAmostragemPorVelocidade()

                    if (!skipRasterOps) {
                        // métricas vindas do raster
                        recomposeCoverageMetrics()
                    }
                    // guias de rota (inalterado)
                    val route = activeRoute
                    if (route != null && routeRenderer != null) {
                        val guidance = routeRenderer?.update(
                            route = route,
                            refLineWkb = refCenterWkb,
                            tractorPos = currentPos,
                            spacingM = route.spacingM.toDouble()
                        )
                        guidance?.let {
                            if (::tvLinhaAlvo.isInitialized) tvLinhaAlvo.text = "Linha: ${it.laneIdx}"
                            if (::tvErroLateral.isInitialized) tvErroLateral.text = "Erro: ${"%.2f".format(it.lateralErrorM)} m"
                        }
                    }
                    updateImplementBarOverlay(lastPoint, currentPos)
                    lastPoint = currentPos
                }

                val now = System.currentTimeMillis()
                if (!isSignalLost && lastPositionMillis > 0 && (now - lastPositionMillis) > SIGNAL_LOSS_THRESHOLD_MS) {
                    isSignalLost = true
                    lifecycleScope.launch { logSignalEvent(lost = true) }
                    Toast.makeText(this@MainActivity, "Sinal de posição perdido", Toast.LENGTH_SHORT).show()
                }

                handler.postDelayed(this, updateInterval)
            }
        })
    }

    /* ======================= Checkpoint / sinal ======================= */
    private var checkpointJob: Job? = null
    private val CHECKPOINT_INTERVAL_MS = 180_000L // 3 min

    private fun startCheckpointLoop() {
        checkpointJob?.cancel()
        checkpointJob = lifecycleScope.launch {
            while (isActive && isWorking) {
                try {
                    val id = selectedJobId
                    val store = currentTileStore
                    if (id != null && store != null) {
                        withSavingIndicator {
                            persistRaster(id, store)
                        }
                    } else {
                        val snap = rasterEngine.exportSnapshot()
                        freeTileStore.snapshot(snap)
                    }
                } catch (_: Throwable) { /* silencioso */ }
                delay(CHECKPOINT_INTERVAL_MS)
            }
        }
    }


    private fun stopCheckpointLoop() {
        checkpointJob?.cancel()
        checkpointJob = null
    }

    private suspend fun logSignalEvent(lost: Boolean) {
        val id = selectedJobId ?: return
        val type = if (lost) JobEventType.SIGNAL_LOST.name else JobEventType.SIGNAL_BACK.name
        jobsRepo.addEvent(
            com.example.monitoragricola.jobs.db.JobEventEntity(
                jobId = id,
                t = System.currentTimeMillis(),
                type = type
            )
        )
    }

    /* ======================= Implemento ======================= */

    private fun buildImplementoFromSnapshot(snap: ImplementoSnapshot): Implemento {
        val tipo = snap.tipo?.lowercase()
        val linhas = (snap.numLinhas ?: 0).coerceAtLeast(0)
        var largura = snap.larguraTrabalhoM
        var espac   = snap.espacamentoM

        if (espac <= 0f && largura > 0f && linhas > 0) espac = largura / linhas
        if (largura <= 0f && linhas > 0 && espac > 0f) largura = linhas * espac
        if ((tipo == "plantadeira") && linhas > 0 && (largura < 0.2f)) {
            if (espac <= 0f) espac = 0.45f
            largura = linhas * espac
        }

        val num = if (linhas > 0) linhas else if (espac > 0f && largura > 0f)
            max(1, round(largura / espac).toInt()) else 1

        return when (tipo) {
            "plantadeira" -> com.example.monitoragricola.map.Plantadeira(
                rasterEngine = rasterEngine,
                numLinhas = num,
                espacamento = espac,
                distanciaAntena = snap.distanciaAntenaM ?: 0f,
                modoRastro = snap.modoRastro,
                distAntenaArticulacao = snap.distAntenaArticulacaoM,
                distArticulacaoImplemento = snap.distArticulacaoImplementoM,
                offsetLateral = snap.offsetLateralM ?: 0f,
                offsetLongitudinal = snap.offsetLongitudinalM ?: 0f
            )
            else -> com.example.monitoragricola.map.Plantadeira(
                rasterEngine = rasterEngine,
                numLinhas = num,
                espacamento = espac,
                distanciaAntena = snap.distanciaAntenaM ?: 0f,
                offsetLateral = snap.offsetLateralM ?: 0f,
                offsetLongitudinal = snap.offsetLongitudinalM ?: 0f
            )
        }
    }

    /** Seleciona/força o implemento do job, marca como selecionado e aplica. */
    private fun selectImplementoFromJob(job: com.example.monitoragricola.jobs.db.JobEntity) {
        val snap = try {
            com.google.gson.Gson().fromJson(job.implementoSnapshotJson, ImplementoSnapshot::class.java)
        } catch (_: Throwable) { null } ?: return

        ImplementoSelector.forceFromJob(this, snap)
        ImplementosPrefs.setSelectedJobId(this, job.id)
        selectedJobId = job.id

        val impl = buildImplementoFromSnapshot(snap)
        (impl as? ImplementoBase)?.importRuntimeState(app.implementoStateStore.load(job.id))
        selectImplemento(impl, origin = "forced")
    }

    private fun ensureSelectedForceApplied(selId: Long) {
        val forced = ImplementosPrefs.getForcedSnapshot(this)
        if (forced != null) return
        lifecycleScope.launch {
            val job = withContext(Dispatchers.IO) { jobManager.get(selId) } ?: return@launch
            selectImplementoFromJob(job)
            refreshJobsButtonColor()
            refreshImplementosButtonColor()
            refreshPlayButtonColor()
        }
    }

    private fun selectImplemento(impl: Implemento, origin: String) {
        val prevJobId = selectedJobId
        val prevState = (activeImplemento as? ImplementoBase)?.exportRuntimeState()
        if (prevJobId != null && prevState != null) app.implementoStateStore.save(prevJobId, prevState)

        activeImplemento?.stop()
        activeImplemento = impl

        // Se veio de Job, garanta que runtime state (articulação) seja importado antes de start/stop
        if (origin == "forced" && selectedJobId != null) {
            (impl as? ImplementoBase)?.importRuntimeState(app.implementoStateStore.load(selectedJobId!!))
        }

        val status = runCatching { impl.getStatus() }.getOrNull()
        val nome = (status?.get("nome") as? String) ?: "Implemento"
        tvImplemento.text = if (origin == "forced") "Implemento (Job): $nome" else "Implemento: $nome"

        if (positionProvider === simulatorProvider) simulatorProvider?.setImplemento(impl) // não chama start()

        if (isWorking) impl.start() else impl.stop()
        map.invalidate()
    }


    private fun releaseForcedAndApplyManualIfAny() {
        ImplementoSelector.clearForce(this)
        ImplementosPrefs.clearSelectedJobId(this)
        selectedJobId = null

        val snap = ImplementoSelector.currentSnapshot(this)
        if (snap != null) {
            val impl = buildImplementoFromSnapshot(snap)
            selectImplemento(impl, origin = "manual")
        } else {
            activeImplemento?.stop()
            activeImplemento = null
            tvImplemento.text = "Nenhum implemento selecionado"
            map.invalidate()
        }
        refreshJobsButtonColor()
        refreshImplementosButtonColor()
        refreshPlayButtonColor()
    }

    private fun refreshJobsButtonColor() {
        btnTrabalhos.isSelected = ImplementosPrefs.getSelectedJobId(this) != null
    }

    /* ======================= Raster helpers ======================= */

    private fun restoreRasterOnMap(jobId: Long) {
        rasterRestoreJob?.cancel()
        rasterRestoreJob = null
        pauseViewportUpdatesForRestore()
        rasterLoadingOverlay.visibility = View.VISIBLE
        val store = RoomTileStore(app.rasterDb, jobId)

        val job = lifecycleScope.launch {
            var metadata: JobRasterMetadata? = null
            var coords: List<RasterTileCoord> = emptyList()
            var tileKeys: List<TileKey> = emptyList()
            var shouldRunLegacyFallback = false

            waitForPendingRasterPersistence()
            rasterSaveMutex.withLock {
                rasterEngine.attachStore(noopTileStore)
                currentTileStore = null
            }


            suspend fun startEngine(meta: JobRasterMetadata?, applyTotals: Boolean) {
                rasterSaveMutex.withLock {
                    withContext(Dispatchers.Default) {
                        if (meta != null) {
                            rasterEngine.startJob(
                                meta.originLat,
                                meta.originLon,
                                resolutionM = meta.resolutionM,
                                tileSize = meta.tileSize
                            )
                            if (applyTotals) {
                                meta.totals?.let { totals ->
                                    rasterEngine.restorePersistedTotals(totals, tileKeys)
                                }
                            }
                        } else {
                            val center = map.mapCenter
                            rasterEngine.startJob(
                                center.latitude,
                                center.longitude,
                                resolutionM = 0.10,
                                tileSize = 256
                            )
                        }
                    }
                }
            }

            try {
                metadata = jobsRepo.getRasterMetadata(jobId)
                coords = jobsRepo.listRasterTileCoords(jobId)
                tileKeys = coords.map { TileKey(it.tx, it.ty) }
                shouldRunLegacyFallback = metadata?.totals == null && tileKeys.isNotEmpty()
                startEngine(metadata, applyTotals = true)
            } catch (ex: CancellationException) {
                throw ex
            } catch (t: Throwable) {
                Log.e(TAG_RASTER, "Falha ao restaurar raster do job $jobId", t)
                startEngine(metadata, applyTotals = false)
            } finally {
                if (this != rasterRestoreJob) return@launch
                if (shouldRunLegacyFallback) {
                    runCatching {
                        rasterSaveMutex.withLock {
                            store.preloadTiles(rasterEngine, tileKeys)
                        }
                    }.onFailure { Log.w(TAG_RASTER, "Falha ao restaurar totais legacy", it) }
                }
                val viewport = map.boundingBox
                val hotSeed = lastPoint ?: run {
                    if (::tractor.isInitialized) tractor.position else map.mapCenter
                }
                rasterSaveMutex.withLock {
                    rasterEngine.attachStore(store)
                    currentTileStore = store
                    try {
                        withContext(Dispatchers.Default) {
                            rasterEngine.updateViewport(viewport)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG_RASTER, "Falha ao recarregar tiles visíveis", t)
                    }
                    try {
                        withContext(Dispatchers.Default) {
                            rasterEngine.updateTractorHotCenter(hotSeed.latitude, hotSeed.longitude)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG_RASTER, "Falha ao hidratar HOT após restore", t)
                    }
                }
                rasterOverlay.invalidateTiles()
                map.invalidate()
                refreshAreaUiFromEngine()
                rasterLoadingOverlay.visibility = View.GONE
                resumeViewportUpdatesAfterRestore()
                rasterRestoreJob = null
                recomposeCoverageMetrics()
                ensureHotVizModeIsAvailable()
            }
        }
        rasterRestoreJob = job
    }

    private fun refreshAreaUiFromEngine() {
        val areas = rasterEngine.getAreas()
        tvArea.text = "Área: ${formatArea(areas.effectiveM2)}"
        tvSobreposicao.text = "Sobreposição: ${formatArea(areas.overlapM2)}"
    }


    private fun clearRasterFromMap() {
        rasterEngine.clearCoverage()
        rasterOverlay.invalidateTiles()
        lifecycleScope.launch {
            freeTileStore.clear()
        }
        app.clearFreeModeTileStore()
        map.invalidate()
        recomposeCoverageMetrics()
        ensureHotVizModeIsAvailable()
    }

    /* ======================= UI helpers ======================= */

    private fun calcularVelocidadeCache(current: GeoPoint): String {
        val now = System.currentTimeMillis()
        val last = lastSpeedPoint
        val lastTime = lastSpeedCalcTime

        // thresholds para reduzir jitter
        val minDtSec = 0.20          // não atualiza mais rápido que 5 Hz
        val minDistM = 0.05          // ignora passos menores que 5 cm
        val alpha = 0.35             // EMA: 0..1 (maior = responde mais rápido)

        var vKmhToShow = 0.0

        if (last != null && lastTime > 0) {
            val dtSec = ((now - lastTime) / 1000.0).coerceAtLeast(1e-3)
            val distM = last.distanceToAsDouble(current)

            // Só atualiza cálculo se passou tempo/distância suficientes
            if (dtSec >= minDtSec && distM >= minDistM) {
                val instKmh = (distM / dtSec) * 3.6
                speedEmaKmh = when (val prev = speedEmaKmh) {
                    null -> instKmh
                    else -> prev + alpha * (instKmh - prev)
                }
                lastSpeedPoint = current
                lastSpeedCalcTime = now
            }

            vKmhToShow = speedEmaKmh ?: 0.0
        } else {
            // primeira amostra: inicializa marcadores
            lastSpeedPoint = current
            lastSpeedCalcTime = now
            vKmhToShow = 0.0
        }

        // Telemetria para o raster (grava por-pixel quando pinta)
        rasterEngine.updateSpeed(
            if (vKmhToShow > 0.0) vKmhToShow.toFloat() else null
        )

        return String.format("%.1f km/h", vKmhToShow)
    }

    private fun recomposeCoverageMetrics() {
        val areas = rasterEngine.getAreas()
        tvArea.text = "Área: ${formatArea(areas.effectiveM2)}"
        tvSobreposicao.text = "Sobreposição: ${formatArea(areas.overlapM2)}"
    }



    private fun ajustarAmostragemPorVelocidade() {
        val vKmh = tvVelocidade.text.toString()
            .substringAfter(" ").substringBefore(" ").toDoubleOrNull() ?: 0.0

        val targetMinDist = when {
            vKmh > 12.0 -> 0.50
            vKmh > 6.0  -> 0.35
            else        -> 0.25
        }
        if (abs(targetMinDist - currentMinDistMeters) > 0.01) {
            currentMinDistMeters = targetMinDist
            jobRecorder.setSampling(minDistanceMeters = currentMinDistMeters)
        }
    }

    private fun formatArea(areaM2: Double): String = when {
        areaM2 < 10_000     -> "%.0f m²".format(areaM2)
        areaM2 < 1_000_000  -> "%.1f Ha".format(areaM2 / 10_000.0)
        else                -> "%.0f Ha".format(areaM2 / 10_000.0)
    }

    private fun disableFollowTemporarily() {
        followTractor = false
        followHandler.removeCallbacks(followRunnable)
        followHandler.postDelayed(followRunnable, followDelayMs)
    }

    private val followRunnable = Runnable {
        followTractor = true
        lastPoint?.let { last ->
            interpolatedPosition?.let { current ->
                val heading = calculateBearing(last, current)
                map.setMapOrientation(-heading)
                lastHeading = heading
            }
        }
    }

    private fun calculateBearing(start: GeoPoint, end: GeoPoint): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x)).toFloat()
        if (bearing < 0) bearing += 360f
        return bearing
    }

    private fun updateImplementBarOverlay(lastGps: GeoPoint?, currentGps: GeoPoint) {
        val implBase = activeImplemento as? ImplementoBase ?: return

        val bar = implBase.getImplementBarEndpoints() ?: return
        val (gp1, gp2) = bar

        val color = Color.argb(255, 128, 128, 128)
        if (implementBar == null) {
            implementBar = org.osmdroid.views.overlay.Polyline(map).apply {
                outlinePaint.strokeWidth = 6f
                outlinePaint.color = color
                isGeodesic = false
                setPoints(listOf(gp1, gp2))
            }
            map.overlays.add(implementBar)
        } else {
            implementBar?.setPoints(listOf(gp1, gp2))
        }

        val implCenter = implBase.getImplementCenter()
        if (implCenter != null) {
            val pts = mutableListOf<GeoPoint>()
            val tractorPos = interpolatedPosition ?: tractor.position
            pts += tractorPos
            val joint = if (implBase.getPaintModel() == PaintModel.ARTICULADO) implBase.getArticulationPoint() else null
            if (joint != null) pts += joint
            pts += implCenter

            val linkColor = Color.argb(180, 80, 80, 80)
            if (implementLink == null) {
                implementLink = org.osmdroid.views.overlay.Polyline(map).apply {
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.color = linkColor
                    isGeodesic = false
                    setPoints(pts)
                }
                map.overlays.add(implementLink)
            } else {
                implementLink?.setPoints(pts)
            }
        }
    }

    private fun clearResumeExtras() { intent.removeExtra("resume_job_id") }

    /* ======================= Rotas (inalteradas) ======================= */
    private fun mostrarAtalhoRotasAB(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Marcar ponto A (posição atual)")
        popup.menu.add(0, 2, 1, "Marcar ponto B (posição atual)")
        popup.menu.add(0, 3, 2, "Gerar linhas AB")
        popup.menu.add(0, 4, 3, "Iniciar gravação do trilho (Curva)")
        popup.menu.add(0, 5, 4, "Parar gravação do trilho")
        popup.menu.add(0, 6, 5, "Gerar linhas Curvas")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { positionProvider?.getCurrentPosition()?.let { pendingA = it; Toast.makeText(this, "Ponto A marcado", Toast.LENGTH_SHORT).show() }; true }
                2 -> { positionProvider?.getCurrentPosition()?.let { pendingB = it; Toast.makeText(this, "Ponto B marcado", Toast.LENGTH_SHORT).show() }; true }
                3 -> { gerarLinhasABComPreferencias(); true }
                4 -> { iniciarGravacaoTrilho(); true }
                5 -> { pararGravacaoTrilho(); true }
                6 -> { gerarLinhasCurvasComPreferencias(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun gerarLinhasABComPreferencias() {
        val prefs = getSharedPreferences("routes_prefs", MODE_PRIVATE)
        val useCustom = prefs.getBoolean("use_custom_spacing", false)
        val spacing = if (useCustom) {
            (prefs.getString("spacing_m_custom", null)?.toFloatOrNull()
                ?: defaultPassWidthFromImplemento().toFloat())
        } else {
            defaultPassWidthFromImplemento().toFloat()
        }
        generateABRoute(spacing)
    }

    private fun defaultPassWidthFromImplemento(): Float {
        ImplementoSelector.currentSnapshot(this)?.let { s ->
            var w = s.larguraTrabalhoM
            if (w <= 0f) {
                val n = (s.numLinhas ?: 0)
                val esp = s.espacamentoM
                if (n > 0 && esp > 0f) w = n * esp
            }
            if (w > 0f) return w.coerceAtLeast(0.05f)
        }
        (simulatorProvider?.getImplementoAtual() ?: activeImplemento)?.getStatus()?.let { st ->
            val w = (st["larguraTrabalhoM"] as? Number)?.toFloat()
                ?: (st["largura"] as? Number)?.toFloat()
                ?: run {
                    val n = (st["numLinhas"] as? Number)?.toInt() ?: 0
                    val esp = (st["espacamentoM"] as? Number)?.toFloat()
                        ?: (st["espacamento"] as? Number)?.toFloat() ?: 0f
                    if (n > 0 && esp > 0f) n * esp else null
                }
            if (w != null && w > 0f) return w.coerceAtLeast(0.05f)
        }
        return 3.0f
    }

    private fun generateABRoute(spacing: Float) {
        val jobId = selectedJobId ?: run {
            Toast.makeText(this, "Nenhum trabalho ativo.", Toast.LENGTH_SHORT).show(); return
        }
        val A = pendingA; val B = pendingB
        if (A == null || B == null) { Toast.makeText(this, "Marque A e B antes.", Toast.LENGTH_SHORT).show(); return }

        val bb = map.boundingBox
        val projTemp = ProjectionHelper(A.latitude, A.longitude)
        val p1 = projTemp.toLocalMeters(GeoPoint(bb.latSouth, bb.lonWest))
        val p2 = projTemp.toLocalMeters(GeoPoint(bb.latNorth, bb.lonEast))
        val bounds = com.example.monitoragricola.jobs.routes.RouteGenerator.BoundsMeters(
            min(p1.x, p2.x), min(p1.y, p2.y), max(p1.x, p2.x), max(p1.y, p2.y)
        )

        val gen = com.example.monitoragricola.jobs.routes.RouteGenerator()
        val (route, lines) = gen.generateAB(
            A.latitude, A.longitude,
            B.latitude, B.longitude,
            spacing.toDouble(),
            bounds
        )

        lifecycleScope.launch {
            val routeId = app.routesManager.createABRoute(jobId, route, lines)
            activeRouteId = routeId
            activeRoute = withContext(Dispatchers.IO) {
                app.routesRepository.activeRoutes(jobId).first { it.id == routeId }
            }
            refCenterWkb = null
            if (routeRenderer == null) routeRenderer = com.example.monitoragricola.jobs.routes.RouteRenderer(map)
            else routeRenderer?.reset()
            positionProvider?.getCurrentPosition()?.let { pos ->
                routeRenderer?.update(route = activeRoute!!, refLineWkb = null, tractorPos = pos, spacingM = spacing.toDouble())
            }
            Toast.makeText(this@MainActivity, "Rota AB criada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarGravacaoTrilho() {
        val jobId = selectedJobId ?: run { Toast.makeText(this, "Nenhum trabalho ativo.", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val start = app.jobsRepository.nextSeq(jobId)
            refStartSeq = start
            refEndSeq = null
            Toast.makeText(this@MainActivity, "Gravação do trilho (seq) iniciada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pararGravacaoTrilho() {
        val jobId = selectedJobId ?: run { Toast.makeText(this, "Nenhum trabalho ativo.", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val end = app.jobsRepository.maxSeq(jobId)
            refEndSeq = end
            Toast.makeText(this@MainActivity, "Gravação do trilho (seq) finalizada.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun gerarLinhasCurvasComPreferencias() {
        val jobId = selectedJobId ?: run { Toast.makeText(this, "Nenhum trabalho ativo.", Toast.LENGTH_SHORT).show(); return }
        val startSeq = refStartSeq
        val endSeq = refEndSeq
        if (startSeq == null || endSeq == null || endSeq < startSeq) {
            Toast.makeText(this, "Grave um trilho (iniciar e parar) antes de gerar.", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("routes_prefs", MODE_PRIVATE)
        val useCustom = prefs.getBoolean("use_custom_spacing", false)
        val spacing = if (useCustom) {
            (prefs.getString("spacing_m_custom", null)?.toDoubleOrNull()
                ?: defaultPassWidthFromImplemento().toDouble())
        } else {
            defaultPassWidthFromImplemento().toDouble()
        }.coerceAtLeast(0.05)

        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) { app.jobsRepository.getPointsBetweenSeq(jobId, startSeq, endSeq) }
            if (points.isNullOrEmpty() || points.size < 2) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Trilho vazio/curto.", Toast.LENGTH_SHORT).show() }
                return@launch
            }

            val track = points.map { p -> p.lat to p.lon }
            val generator = com.example.monitoragricola.jobs.routes.CurveRouteGenerator()
            val (routeBase, lines) = generator.generateFromTrack(
                trackLatLon = track, spacingM = spacing, simplifyToleranceM = 0.10
            )

            val routeId = withContext(Dispatchers.IO) {
                app.routesManager.createABRoute(jobId, routeBase.copy(jobId = jobId), lines)
            }
            activeRouteId = routeId
            activeRoute = withContext(Dispatchers.IO) {
                app.routesRepository.activeRoutes(jobId).first { it.id == routeId }
            }
            refCenterWkb = withContext(Dispatchers.IO) {
                app.routesManager.loadLines(routeId).firstOrNull { it.idx == 0 }?.wkbLine
            }

            if (routeRenderer == null) routeRenderer = com.example.monitoragricola.jobs.routes.RouteRenderer(map)
            else routeRenderer?.reset()
            positionProvider?.getCurrentPosition()?.let { pos ->
                routeRenderer?.update(route = activeRoute!!, refLineWkb = refCenterWkb, tractorPos = pos, spacingM = spacing)
            }

            Toast.makeText(this@MainActivity, "Rota CURVA criado.", Toast.LENGTH_SHORT).show()
        }
    }

    /* ======================= Permissão / providers ======================= */

    private fun checkLocationPermission() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
        if (hasFine) startGpsProvider() else requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startGpsProvider() {
        positionProvider?.stop()
        positionProvider = GpsPositionProvider(this)
        positionProvider?.start()
        simulatorProvider = null
    }

    /* ======================= Bits visuais ======================= */

    private fun makeGnssAntennaBitmap(size: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val cx = size / 2f; val cy = size / 2f
        val rOuter = size * 0.46f; val rInner = size * 0.18f

        val pRing = android.graphics.Paint().apply {
            isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
            strokeWidth = size * 0.08f; color = Color.rgb(60,60,60)
        }
        val pFill = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL; color = Color.WHITE }
        val pDot  = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL; color = Color.rgb(0,140,255) }

        c.drawCircle(cx, cy, rOuter, pFill)
        c.drawCircle(cx, cy, rOuter, pRing)
        c.drawCircle(cx, cy, rInner, pDot)
        return bmp
    }
    private fun clearRouteOverlays() {
        routePolylines.forEach { map.overlays.remove(it) }
        routePolylines.clear()
    }
    private fun resetCache() {
        interpolatedPosition = null
    }
    private fun refreshJobState() {
    // roda em coroutine por dentro
        val selId = selectedJobId ?: run {
            renderJobState(null, false); return
        }
        lifecycleScope.launch {
            val job = withContext(Dispatchers.IO) {
                jobManager.get(selId)
            }
            if (job != null) {
                renderJobState(job.name, isWorking)
            } else {
                renderJobState(null, false)
            }
        }
    }

    private fun renderJobState(name: String?, active: Boolean) {
        if (name == null) {
            tvJobState.visibility = View.GONE
            return
        }
        tvJobState.visibility = View.VISIBLE
        val estado = if (active) "Ativo" else "Pausado"
        tvJobState.text = "Estado: $estado — $name"
    }
    private fun tryRestoreSelectedJobRaster() {
        val selId = selectedJobId

        if (selId != null) {
            ensureSelectedForceApplied(selId)
            // IMPORTANTE: restoreRasterOnMap deve importar no *mesmo* rasterEngine já criado
            restoreRasterOnMap(selId)
            Log.d("RASTER", "mapReady=$mapReady payloadRestaurado=SIM")
            Log.d(
                "RASTER",
                "origin=${rasterEngine.currentOriginLat()},${rasterEngine.currentOriginLon()} res=${rasterEngine.currentResolutionM()} tile=${rasterEngine.currentTileSize()}"
            )
        } else {
            lifecycleScope.launch {
                waitForPendingRasterPersistence()
                val snap = withContext(Dispatchers.IO) { freeTileStore.restore() }
                rasterSaveMutex.withLock {
                    rasterEngine.attachStore(freeTileStore)
                    if (snap != null) {
                        rasterEngine.importSnapshot(snap)
                    } else {
                        rasterEngine.invalidateTiles()
                    }
                }
                rasterOverlay.invalidateTiles()
                map.invalidate()
                recomposeCoverageMetrics()
            }
        }
    }

    private fun refreshImplementosButtonColor() {
        // azul quando há um implemento efetivo (manual ou forçado)
        val snap = ImplementoSelector.currentSnapshot(this)
        btnImplementos.isSelected = (snap != null)
    }

    private fun refreshPlayButtonColor() {
        // azul quando está rodando
        btnLigar.isSelected = isWorking
    }

    private suspend fun <T> withSavingIndicator(suspendLoops: Boolean = false, block: suspend () -> T): T {
        withContext(Dispatchers.Main) {
            if (suspendLoops) {
                suspendRasterUpdates++
            }
            savingOps++
            progressSavingRaster.isVisible = savingOps > 0
        }
        return try {
            block()
        } finally {
            onSavingFinished(suspendLoops)
        }
    }

    private fun onSavingFinished(releaseLoop: Boolean) {
        val release = {
            if (releaseLoop) {
                suspendRasterUpdates = (suspendRasterUpdates - 1).coerceAtLeast(0)
            }
            savingOps = (savingOps - 1).coerceAtLeast(0)
            progressSavingRaster.isVisible = savingOps > 0
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release()
        } else {
            lifecycleScope.launch(Dispatchers.Main) { release() }
        }
    }

    private suspend fun persistRaster(jobId: Long, store: TileStore) {
        rasterSaveMutex.withLock {
            jobManager.saveRaster(jobId, store, rasterEngine)
        }
    }

    private suspend fun waitForPendingRasterPersistence() {
        val pending = pendingRasterSaveJob
        if (pending != null && pending.isActive) {
            try {
                pending.join()
            } catch (_: CancellationException) {
            }
        }
    }



    private suspend fun flushRasterSync(reason: String) {
        val selId = selectedJobId ?: return
        val store = currentTileStore ?: return
        try {
            // Tempo curto p/ não travar a UI: ajuste se necessário (400–1000 ms)
            withSavingIndicator(suspendLoops = true) {
                withContext(Dispatchers.IO) {
                    waitForPendingRasterPersistence()
                    withTimeout(800) {
                        persistRaster(selId, store)
                    }
                }
            }
            Log.d("RASTER", "flushRasterSync ok ($reason)")
        } catch (t: Throwable) {
            Log.w("RASTER", "flushRasterSync falhou ($reason): ${t.message}")
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        // Flush rápido — chamado em rota “fechar / background”
        lifecycleScope.launch { flushRasterSync("onSaveInstanceState") }
        super.onSaveInstanceState(outState)
    }
    // Salva imediatamente o estado play/pause (e job atual para telemetria visual)
    private fun persistPlayState() {
        statePrefs.edit()
            .putBoolean("isWorking", isWorking)
            .apply()
    }

    // Lê o estado salvo. Somente 1ª abertura vem como false (default).
    private fun restorePlayState() {
        isWorking = statePrefs.getBoolean("isWorking", false)
    }

    // Mantém botão e label coerentes com o estado atual
    private fun syncPlayUi() {
        btnLigar.isSelected = isWorking
        btnLigar.setImageResource(
            if (isWorking) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        // Atualiza a faixa “Estado: Ativo/Pausado — Nome”
        refreshJobState()
    }

    override fun onDestroy() {
        followHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        viewportLoopJob?.cancel()
        viewportLoopJob = null
        viewportUpdateJob?.cancel()
        viewportUpdateJob = null
        hotCenterJob?.cancel()
        hotCenterJob = null

        simulatorProvider?.stop()
        simulatorProvider = null
        positionProvider?.stop()
        positionProvider = null

        if (::map.isInitialized) {
            implementBar?.let {
                map.overlays.remove(it)
                implementBar = null
            }
            implementLink?.let {
                map.overlays.remove(it)
                implementLink = null
            }
            if (routePolylines.isNotEmpty()) {
                routePolylines.forEach { map.overlays.remove(it) }
                routePolylines.clear()
            }
            map.setOnTouchListener(null)
            map.onDetach()
        }

        activeImplemento?.stop()
        activeImplemento = null
        routeRenderer = null
        pendingA = null
        pendingB = null

        super.onDestroy()
    }


}
