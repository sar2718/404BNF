package com.team404bnf.app.ui.onboarding

import androidx.compose.foundation.layout.*
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start
        ) {

            Spacer(Modifier.height(48.dp))

            Text(
                text = "404BNF",
                style = MaterialTheme.typography.displayMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Добро пожаловать!",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Записывайте разговоры, сохраняйте аудио " +
                        "и работайте с расшифровками в удобном формате.",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = "Перед началом ознакомьтесь с документами:",
                style = MaterialTheme.typography.bodyMedium
            )

            TextButton(
                onClick = { openedDocument = "terms" }
            ) {
                Text("Пользовательское соглашение")
            }

            TextButton(
                onClick = { openedDocument = "privacy" }
            ) {
                Text("Политика конфиденциальности")
            }

            Spacer(Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = { agreed = it }
                )

                Text(
                    text = "Принимаю Пользовательское соглашение " +
                            "и ознакомлен(а) с Политикой конфиденциальности",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onAccept,
                enabled = agreed && !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    if (saving) "Сохранение..." else "Продолжить"
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))

                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}