package com.radar.media

/**
 * Catálogo de stickers integrado. Un sticker viaja como su **id de 2 bytes** (cifrado): no
 * consume ancho de banda de BLE y se dibuja igual en todos los teléfonos, sin guardar imágenes.
 *
 * Los ids son parte del protocolo: no reordenar ni reutilizar; solo añadir al final.
 */
data class Sticker(
    val id: Int,
    val glyph: String,
    val label: String,
    /** Degradado de fondo (ARGB). */
    val from: Long,
    val to: Long
)

object StickerCatalog {

    val all: List<Sticker> = listOf(
        Sticker(1, "👋", "¡Hola!", 0xFF1D4E89, 0xFF3DE0FF),
        Sticker(2, "😂", "Jajaja", 0xFF7A4B00, 0xFFFFC857),
        Sticker(3, "❤️", "Te quiero", 0xFF7A1230, 0xFFFF6B8A),
        Sticker(4, "👍", "Dale", 0xFF0F5C3F, 0xFF4CE3A5),
        Sticker(5, "🙏", "Gracias", 0xFF3A2C7A, 0xFF8B7CFF),
        Sticker(6, "🔥", "Fuego", 0xFF8A2A00, 0xFFFF9F43),
        Sticker(7, "🎉", "¡Festejo!", 0xFF6A1B7A, 0xFFFF7BD5),
        Sticker(8, "😴", "Zzz", 0xFF1B2A55, 0xFF7FB2FF),
        Sticker(9, "🤔", "Mmm…", 0xFF444B5C, 0xFF9AA7C0),
        Sticker(10, "😎", "Pro", 0xFF0B3A47, 0xFF3DE0FF),
        Sticker(11, "😭", "Snif", 0xFF123A63, 0xFF5EA8FF),
        Sticker(12, "🥳", "Cumple", 0xFF7A3B00, 0xFFFFD166),
        Sticker(13, "👀", "Te vi", 0xFF2E3B2F, 0xFF8FD694),
        Sticker(14, "🍕", "¿Comemos?", 0xFF7A2A12, 0xFFFFB066),
        Sticker(15, "☕", "¿Un café?", 0xFF4A2E1B, 0xFFC99A6B),
        Sticker(16, "🍻", "¡Salud!", 0xFF6B5200, 0xFFFFD84D),
        Sticker(17, "🚨", "Ayuda", 0xFF7A1212, 0xFFFF6B7A),
        Sticker(18, "📍", "Estoy acá", 0xFF0F4D5C, 0xFF4CE3E3),
        Sticker(19, "🤝", "Trato hecho", 0xFF2C4A1F, 0xFFA3E36B),
        Sticker(20, "💪", "Fuerza", 0xFF5C2A1B, 0xFFFF8A5C),
        Sticker(21, "🙈", "Ups", 0xFF4A3A1B, 0xFFE0C068),
        Sticker(22, "😡", "Grrr", 0xFF6B1212, 0xFFFF5A5A),
        Sticker(23, "🌙", "Buenas noches", 0xFF14193A, 0xFF6D7BFF),
        Sticker(24, "☀️", "Buen día", 0xFF7A5200, 0xFFFFE066)
    )

    private val byId = all.associateBy { it.id }

    fun find(id: Int): Sticker? = byId[id]
}

/** Emojis agrupados para el selector. Todos son de un solo punto de código (o con VS16). */
object EmojiCatalog {

    data class Category(val icon: String, val name: String, val emojis: List<String>)

    private fun split(text: String): List<String> = text.trim().split(" ").filter { it.isNotEmpty() }

    val categories: List<Category> = listOf(
        Category(
            "😀", "Caras",
            split(
                "😀 😃 😄 😁 😆 😅 😂 🤣 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🤓 🥳 🤗 🤔 🤭 🤫 😐 😑 😶 🙄 " +
                    "😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 😱 😨 😰 😥 😓 🤯 😳 🥵 🥶 😴 🤤 😷 🤒 🤕"
            )
        ),
        Category(
            "👍", "Gestos",
            split(
                "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 💪 🫶 ✍️ 🤳 💅 👀 👂 👃 🧠"
            )
        ),
        Category(
            "❤️", "Símbolos",
            split(
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 🔥 💥 💯 ✅ ❌ ❓ ❗ ⚠️ 🚨 🔔"
            )
        ),
        Category(
            "🐶", "Animales",
            split(
                "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦆 🦉 🐺 🐴 🦄 🐝 🦋 🐢 🐍 🐙 🐬 🐳 🦈"
            )
        ),
        Category(
            "🍕", "Comida",
            split(
                "🍎 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥑 🌽 🥕 🍞 🧀 🍳 🥓 🍔 🍟 🌭 🍕 🌮 🍣 🍜 🍝 🍰 🍫 🍩 🍪 ☕ 🍺 🍻 🍷 🥤"
            )
        ),
        Category(
            "⚽", "Actividad",
            split(
                "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🥊 🎯 🎮 🎲 🎧 🎤 🎸 🎹 🥁 🎬 🎨 🏆 🥇 🚴 🏊 🏃 🧘 🎉 🎊 🎁 🎈"
            )
        ),
        Category(
            "🚗", "Viajes",
            split(
                "🚗 🚕 🚌 🚎 🚓 🚑 🚒 🚚 🏍️ 🚲 ✈️ 🚀 🚁 ⛵ 🚢 🚉 🏠 🏢 🏫 🏥 🌆 🌉 ⛺ 🏖️ 🏔️ 🌋 🗺️ 📍 🧭 ⛽"
            )
        ),
        Category(
            "💡", "Objetos",
            split(
                "📱 💻 ⌚ 📷 🎥 📞 🔋 💡 🔦 📦 📚 📝 ✏️ 📎 📌 🔒 🔑 🔧 🔨 🧰 💰 💳 🕐 ⏰ 🌡️ 🧲 🧯 🛒 🎒 👓"
            )
        )
    )
}
