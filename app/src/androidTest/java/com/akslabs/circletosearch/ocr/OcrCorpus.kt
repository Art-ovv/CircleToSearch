package com.akslabs.circletosearch.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Synthetic versioned fixtures: no user screenshots or network resources. Sizes are pixels. */
internal object OcrCorpus {
    const val VERSION = 1

    data class Sample(
        val id: String,
        val lines: List<String>,
        val textSize: Float = 36f,
        val foreground: Int = Color.BLACK,
        val background: Int = Color.WHITE,
        val rotation: Float = 0f,
        val chat: Boolean = false,
        val mixedPolarity: Boolean = false,
    ) {
        val expected: String get() = lines.joinToString(" ")

        fun render(): Bitmap {
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    textSize = this@Sample.textSize
                }
                if (chat) {
                    // A dark chat surface with a light screenshot inside its message bubble.
                    paint.color = Color.rgb(65, 70, 80)
                    canvas.drawRoundRect(40f, 350f, 1040f, 1000f, 32f, 32f, paint)
                    paint.color = Color.WHITE
                    canvas.drawRect(70f, 380f, 1010f, 950f, paint)
                }
                canvas.save()
                try {
                    canvas.rotate(rotation, 540f, 650f)
                    paint.color = foreground
                    lines.forEachIndexed { index, line ->
                        if (mixedPolarity) {
                            val baseline = 550f + index * textSize * 1.5f
                            paint.color = if (index % 2 == 0) Color.BLACK else Color.WHITE
                            canvas.drawRect(90f, baseline - textSize, 990f, baseline + 8f, paint)
                            paint.color = if (index % 2 == 0) Color.WHITE else Color.BLACK
                        }
                        canvas.drawText(line, 110f, 550f + index * textSize * 1.5f, paint)
                    }
                } finally {
                    canvas.restore()
                }
                return bitmap
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        }
    }

    val samples = listOf(
        Sample("russian", listOf("Проверка распознавания текста", "Батарея работает до двух дней.")),
        Sample("english", listOf("Screen text recognition", "The battery lasts up to two days.")),
        Sample("mixed_languages", listOf("Настройки Settings", "Версия Version 2.5")),
        Sample("mixed_polarity", listOf("Белый текст White text", "Чёрный текст Black text"),
            mixedPolarity = true),
        Sample("small_ru", listOf("Последнее обновление: 12 сентября"), textSize = 18f),
        Sample("small_en", listOf("Last updated: September 12"), textSize = 18f),
        Sample("dark_theme", listOf("Тёмная тема Dark theme"),
            foreground = Color.WHITE, background = Color.rgb(24, 24, 24)),
        Sample("low_contrast", listOf("Светлая подпись Light caption"),
            foreground = Color.rgb(205, 205, 205), background = Color.rgb(245, 245, 245)),
        Sample("skew_positive", listOf("Наклонный текст Serial AB-1234"), rotation = 8f),
        Sample("skew_negative", listOf("Проверка текста Screen capture"), rotation = -8f),
        Sample("serial_numbers", listOf("SN: AB0O-1I5S-2026", "Model: RX-7800  Price: 1299.50")),
        Sample("screenshot_in_chat", listOf("Скриншот внутри сообщения", "Screenshot inside a chat"),
            background = Color.rgb(24, 27, 32), chat = true),
    )
}
