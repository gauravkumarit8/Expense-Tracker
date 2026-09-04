package com.autoexpensetracker.data

enum class Category(val label: String, val emoji: String) {
    FOOD("Food & Dining", "🍔"),
    GROCERIES("Groceries", "🛒"),
    SHOPPING("Shopping", "🛍️"),
    BILLS("Bills & Utilities", "🧾"),
    TRANSFER("Transfer", "🔁"),
    ENTERTAINMENT("Entertainment", "🎬"),
    TRAVEL("Travel", "✈️"),
    HEALTH("Health", "💊"),
    OTHER("Other", "❓");

    companion object {
        fun fromNameOrNull(name: String?): Category? =
            entries.firstOrNull { it.name == name }
    }
}