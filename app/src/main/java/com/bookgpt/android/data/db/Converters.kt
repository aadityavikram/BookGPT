package com.bookgpt.android.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromBookStatus(status: BookStatus): String = status.name

    @TypeConverter
    fun toBookStatus(value: String): BookStatus = BookStatus.valueOf(value)
}
