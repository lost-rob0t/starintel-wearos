package actor.starintel.quasar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small consistent line icons, drawn locally without fonts or network assets. */
internal class QuasarIcon(private val label: String, color: Int, private val size: Int) : Drawable() {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { ink.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { ink.colorFilter = colorFilter }
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        val save = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) { val path = Path(); path.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1]); canvas.drawPath(path, ink) }
        when {
            label == "Menu" -> { line(3f,6f,21f,6f); line(3f,12f,21f,12f); line(3f,18f,21f,18f) }
            label == "Close" -> { line(5f,5f,19f,19f); line(19f,5f,5f,19f) }
            label == "Targets" -> { canvas.drawCircle(12f,12f,7f,ink); canvas.drawCircle(12f,12f,2f,ink); line(12f,2f,12f,5f); line(12f,19f,12f,22f); line(2f,12f,5f,12f); line(19f,12f,22f,12f) }
            label.contains("search", true) || label == "Documents" -> { canvas.drawCircle(10f, 10f, 6f, ink); line(14.5f,14.5f,21f,21f) }
            label.contains("map", true) || label == "Field" -> { line(3f,5f,9f,3f,15f,6f,21f,4f,21f,19f,15f,21f,9f,18f,3f,20f,3f,5f); line(9f,3f,9f,18f); line(15f,6f,15f,21f) }
            label.contains("actor", true) -> { canvas.drawCircle(12f,7f,3f,ink); canvas.drawArc(5f,12f,19f,25f,190f,160f,false,ink); line(3f,12f,3f,17f); line(21f,12f,21f,17f) }
            label.contains("Settings", true) || label.contains("theme", true) || label == "Appearance" -> { for (x in listOf(5f,12f,19f)) line(x,3f,x,21f); canvas.drawCircle(5f,8f,2f,ink); canvas.drawCircle(12f,16f,2f,ink); canvas.drawCircle(19f,9f,2f,ink) }
            label.contains("Add", true) -> { canvas.drawRoundRect(3f,3f,21f,21f,4f,4f,ink); line(12f,7f,12f,17f); line(7f,12f,17f,12f) }
            label.contains("Import", true) -> { line(12f,3f,12f,16f); line(7f,11f,12f,16f,17f,11f); line(3f,15f,3f,21f,21f,21f,21f,15f) }
            label.contains("dataset", true) -> { canvas.drawOval(3f,3f,21f,9f,ink); line(3f,6f,3f,18f); line(21f,6f,21f,18f); canvas.drawArc(3f,14f,21f,21f,0f,180f,false,ink); canvas.drawArc(3f,8f,21f,15f,0f,180f,false,ink) }
            else -> { for (x in listOf(3f,14f)) for (y in listOf(3f,14f)) canvas.drawRoundRect(x,y,x+7f,y+7f,1.5f,1.5f,ink) }
        }
        canvas.restoreToCount(save)
    }
}
