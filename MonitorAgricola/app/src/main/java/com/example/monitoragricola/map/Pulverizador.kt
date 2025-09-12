import com.example.monitoragricola.map.ImplementoBase
import com.example.monitoragricola.raster.RasterCoverageEngine

class Pulverizador(
    rasterEngine: RasterCoverageEngine,               // ⬅️ antes era AreaManager
    private var larguraBarra: Float, // largura em metros
    distanciaAntena: Float = 0f,
    offsetLateral: Float = 0f,
    offsetLongitudinal: Float = 0f
) : ImplementoBase(rasterEngine, distanciaAntena, offsetLateral, offsetLongitudinal) {

    override fun getWorkWidthMeters(): Float = larguraBarra

    // Se precisar pintar por seções, sobrescreva:
    // override fun paintStroke(lastImpl: GeoPoint, currentImpl: GeoPoint, widthMeters: Float) { ... }
}
