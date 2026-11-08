package com.team404bnf.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.team404bnf.app.ui.theme.ArchiveColors
import com.team404bnf.app.ui.theme.ArchiveDimens

enum class ArchiveSymbol { Back, Down, Next, More, Settings, Search, Wave, Upload, Play, Pause, Stop, Lock, Check, Rewind, Document }

@Composable
fun ArchiveIcon(symbol: ArchiveSymbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x,y), Offset(x2,y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float,Float>) {
                val p = Path(); points.forEachIndexed { i,v -> if(i==0) p.moveTo(v.first,v.second) else p.lineTo(v.first,v.second) }
                drawPath(p,color,style=Stroke(1.7f,cap=StrokeCap.Round))
            }
            when(symbol) {
                ArchiveSymbol.Back -> path(15f to 4f, 7f to 12f, 15f to 20f)
                ArchiveSymbol.Down -> path(4f to 8f, 12f to 16f, 20f to 8f)
                ArchiveSymbol.Next -> path(9f to 6f, 15f to 12f, 9f to 18f)
                ArchiveSymbol.More -> listOf(5f,12f,19f).forEach { drawCircle(color,1.5f,Offset(12f,it)) }
                ArchiveSymbol.Search -> { drawCircle(color,6.5f,Offset(10f,10f),style=Stroke(1.7f)); line(15f,15f,21f,21f) }
                ArchiveSymbol.Wave -> listOf(5f,11f,18f,9f,14f).forEachIndexed { i,h -> line(4f+i*4,12f-h/2,4f+i*4,12f+h/2) }
                ArchiveSymbol.Play -> { val p=Path();p.moveTo(7f,4f);p.lineTo(20f,12f);p.lineTo(7f,20f);p.close();drawPath(p,color) }
                ArchiveSymbol.Pause -> { drawRoundRect(color,Offset(6f,4f),Size(4f,16f));drawRoundRect(color,Offset(14f,4f),Size(4f,16f)) }
                ArchiveSymbol.Stop -> drawRoundRect(color,Offset(4f,4f),Size(16f,16f))
                ArchiveSymbol.Check -> path(4f to 12f, 9f to 17f, 20f to 6f)
                ArchiveSymbol.Upload -> {path(7f to 8f,12f to 3f,17f to 8f);line(12f,3f,12f,16f);path(4f to 15f,4f to 21f,20f to 21f,20f to 15f)}
                ArchiveSymbol.Lock -> {drawRoundRect(color,Offset(6f,10f),Size(12f,11f),style=Stroke(1.6f));drawArc(color,0f,-180f,false,Offset(8f,3f),Size(8f,14f),style=Stroke(1.6f));line(12f,14f,12f,17f)}
                ArchiveSymbol.Document -> {path(6f to 3f,15f to 3f,19f to 7f,19f to 21f,6f to 21f,6f to 3f);path(15f to 3f,15f to 8f,19f to 8f);line(9f,12f,16f,12f);line(9f,16f,15f,16f)}
                ArchiveSymbol.Rewind -> {drawArc(color,-80f,315f,false,Offset(3f,4f),Size(18f,18f),style=Stroke(1.6f));path(9f to 2f,13f to 4f,10f to 8f)}
                ArchiveSymbol.Settings -> {
                    drawCircle(color,7f,Offset(12f,12f),style=Stroke(1.7f));drawCircle(color,2.7f,Offset(12f,12f),style=Stroke(1.7f))
                    for(i in 0..7) {val a=i*Math.PI/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+11*kotlin.math.cos(a)).toFloat(),(12+11*kotlin.math.sin(a)).toFloat())}
                }
            }
        }
    }
}

@Composable
fun ArchiveIconButton(symbol: ArchiveSymbol, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        ArchiveIcon(symbol, color = if(enabled) ArchiveColors.Text else ArchiveColors.Muted.copy(alpha=.4f))
    }
}

@Composable
fun ArchiveEyebrow(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = ArchiveColors.Gold)
}

@Composable
fun ArchiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true, symbol: ArchiveSymbol? = null) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = ArchiveDimens.ButtonHeight),
        shape = RoundedCornerShape(ArchiveDimens.Corner),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if(accent) ArchiveColors.Red else ArchiveColors.Control,
            contentColor = if(accent) ArchiveColors.OnAccent else ArchiveColors.Text
        )) {
        symbol?.let { ArchiveIcon(it, Modifier.size(18.dp), if(accent) ArchiveColors.OnAccent else ArchiveColors.Text);Spacer(Modifier.width(8.dp)) }
        Text(text, modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
    }
}

/** A state indicator, not microphone amplitude or a measured audio waveform. */
@Composable
fun ArchiveWaveform(color: Color, modifier: Modifier = Modifier, emphasis: Boolean = true) {
    Canvas(modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = "Индикатор состояния записи" }) {
        val levels = listOf(.08f,.22f,.4f,.65f,.3f,.85f,.46f,.24f,.6f,.95f,.35f,.5f,.72f,.3f,.13f,.4f,.65f,.9f,.53f,.28f,.42f,.7f,.18f,.08f)
        drawLine(ArchiveColors.Divider,Offset(0f,size.height/2),Offset(size.width,size.height/2),1f)
        levels.forEachIndexed { i,h ->
            val x=(i+.5f)*size.width/levels.size
            val height = h*size.height*(if(emphasis) .9f else .45f)
            drawLine(color,Offset(x,(size.height-height)/2),Offset(x,(size.height+height)/2),3.dp.toPx(),StrokeCap.Round)
        }
    }
}

@Composable
fun LocalStorageNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ArchiveIcon(ArchiveSymbol.Lock,Modifier.size(16.dp),ArchiveColors.Muted)
        Text(text,style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
    }
}

fun archiveTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if(seconds >= 3600) "%02d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60)
    else "%02d:%02d".format(seconds/60, seconds%60)
}
