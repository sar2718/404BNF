package com.team404bnf.app.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.team404bnf.app.playback.PlaybackUiState
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchivePlayer(
    playback: PlaybackUiState, durationMs: Long, enabled: Boolean,
    onSeek: (Long) -> Unit, onToggle: () -> Unit, onSpeed: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val maximum=durationMs.coerceAtLeast(1).toFloat()
    val position=(if(dragging) dragPosition else playback.positionMs.toFloat()).coerceIn(0f,maximum)
    Column(Modifier.fillMaxWidth().background(ArchiveColors.Surface).navigationBarsPadding()) {
        HorizontalDivider()
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.widthIn(max=ArchiveDimens.ContentMaxWidth).fillMaxWidth().padding(horizontal=ArchiveDimens.PagePadding,vertical=12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(archiveTime(position.toLong()),color=ArchiveColors.Gold,style=MaterialTheme.typography.labelMedium)
                    Text(archiveTime(durationMs),color=ArchiveColors.Muted,style=MaterialTheme.typography.labelMedium)
                }
                Slider(value=position,onValueChange={dragging=true;dragPosition=it},onValueChangeFinished={onSeek(dragPosition.toLong());dragging=false},
                    valueRange=0f..maximum,enabled=enabled && durationMs>0,
                    thumb={Box(Modifier.size(10.dp).background(if(enabled) ArchiveColors.Red else ArchiveColors.Muted,CircleShape))},
                    track={ state ->
                        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                            val start=if(layoutDirection==LayoutDirection.Rtl) size.width else 0f
                            val end=if(layoutDirection==LayoutDirection.Rtl) 0f else size.width
                            drawLine(ArchiveColors.Divider,Offset(start,center.y),Offset(end,center.y),size.height,StrokeCap.Round)
                            val fraction=(state.value/maximum).coerceIn(0f,1f)
                            drawLine(if(enabled) ArchiveColors.Red else ArchiveColors.Muted,Offset(start,center.y),Offset(start+(end-start)*fraction,center.y),size.height,StrokeCap.Round)
                        }
                    },
                    modifier=Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription="Позиция воспроизведения" })
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    IconButton(onClick={onSeek((playback.positionMs-10_000).coerceAtLeast(0))},enabled=enabled,
                        modifier=Modifier.align(Alignment.CenterStart).size(52.dp).semantics {contentDescription="Назад на 10 секунд"}) {
                        Box(contentAlignment=Alignment.Center) {
                            ArchiveIcon(ArchiveSymbol.Rewind,Modifier.size(28.dp),ArchiveColors.Muted)
                            Text("10",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=5.dp))
                        }
                    }
                    Button(onClick=onToggle,enabled=enabled,shape=RoundedCornerShape(10.dp),modifier=Modifier.width(110.dp).height(52.dp)
                        .semantics {contentDescription=if(playback.isPlayRequested) "Пауза" else "Воспроизвести"}) {
                        ArchiveIcon(if(playback.isPlayRequested) ArchiveSymbol.Pause else ArchiveSymbol.Play,color=ArchiveColors.OnAccent)
                    }
                    TextButton(onClick=onSpeed,enabled=enabled,modifier=Modifier.align(Alignment.CenterEnd).widthIn(min=52.dp).semantics {contentDescription="Скорость воспроизведения"}) {
                        Text("${"%.2f".format(java.util.Locale.US,playback.speed).trimEnd('0').trimEnd('.')}×",color=ArchiveColors.Text)
                    }
                }
            }
        }
    }
}
