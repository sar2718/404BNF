package com.team404bnf.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import com.team404bnf.app.ui.components.*
import com.team404bnf.app.ui.theme.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Предварительные тексты для прототипа.
// Перед публикацией необходима юридическая проверка.

private const val TERMS_TEXT = """
Пользовательское соглашение 404BNF
Версия 1.0

1. 404BNF предназначено для записи, хранения
и расшифровки аудиоразговоров.

2. Пользователь самостоятельно отвечает за
законность записи разговоров и получение
согласия участников, когда оно необходимо.

3. Записи создаются по инициативе пользователя.
Приложение не должно начинать запись скрытно.

4. Доступ к микрофону запрашивается отдельно
перед использованием функции записи.

5. Пользователь может удалять сохранённые
разговоры средствами приложения.

6. Приложение находится в разработке.
Возможны ошибки и ограничения функциональности.

7. При существенном изменении условий
пользователю может потребоваться повторное
принятие соглашения.
"""

private const val PRIVACY_TEXT = """
Политика конфиденциальности 404BNF
Версия 1.0

1. Приложение обрабатывает аудиозаписи,
их названия, длительность и другие метаданные.

2. В текущей версии записи сохраняются
локально во внутреннем хранилище приложения.

3. Текущая версия не отправляет аудиозаписи
и расшифровки на сервер разработчиков.

4. Пользователь управляет созданием записей.
Функции экспорта и удаления развиваются
в рамках проекта.

5. Разрешение на микрофон используется
для записи звука по действию пользователя.

6. Резервное копирование средствами Android
может зависеть от настроек системы и устройства.

7. Изменения способов обработки данных будут
отражаться в обновлённой политике.
"""

@Composable
fun OnboardingScreen(
    saving: Boolean,
    errorMessage: String?,
    onAccept: () -> Unit
) {
    var agreed by rememberSaveable {
        mutableStateOf(false)
    }

    var openedDocument by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    if (openedDocument != null) {
        val isTerms = openedDocument == "terms"

        AlertDialog(
            onDismissRequest = {
                openedDocument = null
            },
            title = {
                Text(
                    if (isTerms) {
                        "Пользовательское соглашение"
                    } else {
                        "Политика конфиденциальности"
                    }
                )
            },
            text = {
                Text(
                    text = if (isTerms) {
                        TERMS_TEXT.trimIndent()
                    } else {
                        PRIVACY_TEXT.trimIndent()
                    },
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { openedDocument = null }
                ) {
                    Text("Закрыть")
                }
            }
        )
    }

    Scaffold(containerColor=ArchiveColors.Background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) {
            Column(Modifier.widthIn(max=520.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=28.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("404BNF",style=MaterialTheme.typography.displayMedium,color=ArchiveColors.Gold)
                Spacer(Modifier.height(20.dp))
                Text("Ваши разговоры.\nВаши данные.",style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                    val heights=listOf(.18f,.62f,.95f,.45f,.72f,.18f)
                    val gap=size.width/7
                    heights.forEachIndexed {i,h ->
                        val x=gap*(i+1);val height=size.height*h
                        drawLine(ArchiveColors.Red,Offset(x,(size.height-height)/2),Offset(x,(size.height+height)/2),24.dp.toPx(),StrokeCap.Round)
                    }
                }
                Spacer(Modifier.height(30.dp))
                Text("Записывайте разговоры, получайте расшифровки и разделяйте реплики по говорящим. Аудиозаписи сохраняются на устройстве.",style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,color=ArchiveColors.Muted)
                Spacer(Modifier.height(24.dp))
                TextButton(onClick={openedDocument="terms"}) {Text("Пользовательское соглашение")}
                TextButton(onClick={openedDocument="privacy"}) {Text("Политика конфиденциальности")}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=agreed,onCheckedChange={agreed=it},enabled=!saving)
                    Text("Принимаю соглашение и подтверждаю ознакомление с политикой конфиденциальности",style=MaterialTheme.typography.bodySmall,color=ArchiveColors.Muted)
                }
                Spacer(Modifier.height(16.dp))
                ArchiveButton(if(saving) "Сохранение…" else "Продолжить",onAccept,Modifier.fillMaxWidth(),accent=true,enabled=agreed && !saving)
                errorMessage?.let {Text(it,color=ArchiveColors.Red,modifier=Modifier.padding(top=12.dp))}
            }
        }
    }
}
