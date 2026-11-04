package com.example.videodl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val input = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val engineMsg = MutableStateFlow<String?>(null)
    val updating = MutableStateFlow(false)

    fun setInput(t: String) { input.value = t }

    /** زر (+): يضيف كل الروابط الموجودة في الحقل. */
    fun addFromInput() = add(input.value)

    fun add(text: String) {
        val n = Downloader.addLinks(text)
        if (n == 0) {
            message.value = "لم أجد روابط جديدة صالحة"
        } else {
            input.value = ""
            message.value = "تمت إضافة $n رابط"
        }
    }

    fun updateEngine() {
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            engineMsg.value = "جاري تحديث المحرك…"
            engineMsg.value = try {
                withContext(Dispatchers.IO) {
                    Engine.ensure(app)
                    Engine.update(app)
                }
            } catch (e: Exception) {
                "فشل التحديث، تحقق من الإنترنت"
            }
            updating.value = false
        }
    }
}
