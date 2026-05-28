package com.example.readingapp.feature.settings.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.readingapp.R
import com.example.readingapp.feature.settings.data.TTSSettings
import com.example.readingapp.feature.settings.data.TTSSettingsManager
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: TTSSettingsManager
    private var textToSpeech: TextToSpeech? = null

    private lateinit var switchAutoPlayNext: SwitchCompat
    private lateinit var spinnerVoiceVi: Spinner
    private lateinit var spinnerVoiceEn: Spinner
    private lateinit var btnSaveChanges: Button
    private lateinit var btnTestVoice: Button
    private lateinit var btnResetDefaults: Button
    private lateinit var seekBarSpeechRate: SeekBar
    private lateinit var textSpeechRateValue: TextView

    data class VoiceItem(val voice: Voice?, val displayName: String) {
        override fun toString(): String = displayName
    }

    // Khởi tạo hoạt động và thiết lập giao diện ban đầu
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tts_settings_title)

        settingsManager = TTSSettingsManager(this)

        initializeViews()
        initializeTTS()
        setupListeners()
        
        // Khôi phục trạng thái ban đầu
        val currentSettings = settingsManager.getCurrentSettings()
        switchAutoPlayNext.isChecked = currentSettings.autoPlayNext

        // SeekBar: progress 0..30 ↔ speechRate 0.5..2.0 (bước 0.05)
        val initialProgress = ((currentSettings.speechRate - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        seekBarSpeechRate.progress = initialProgress
        textSpeechRateValue.text = String.format(Locale.US, "%.1fx", currentSettings.speechRate)
    }

    // Ánh xạ các thành phần giao diện từ layout XML
    private fun initializeViews() {
        switchAutoPlayNext  = findViewById(R.id.switchAutoPlayNext)
        spinnerVoiceVi     = findViewById(R.id.spinnerVoiceVi)
        spinnerVoiceEn     = findViewById(R.id.spinnerVoiceEn)
        btnSaveChanges     = findViewById(R.id.btnSaveChanges)
        btnTestVoice       = findViewById(R.id.btnTestVoice)
        btnResetDefaults   = findViewById(R.id.btnResetDefaults)
        seekBarSpeechRate  = findViewById(R.id.seekBarSpeechRate)
        textSpeechRateValue = findViewById(R.id.textSpeechRateValue)

        // Cập nhật nhãn tốc độ đọc khi kéo SeekBar
        seekBarSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + progress * 0.05f
                textSpeechRateValue.text = String.format(Locale.US, "%.1fx", rate)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // Khởi tạo công cụ TTS để lấy danh sách giọng nói
    private fun initializeTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                loadAvailableVoices()
                restoreSavedVoiceSelections()
            }
        }
    }

    // Nạp danh sách các giọng nói khả dụng từ hệ thống
    private fun loadAvailableVoices() {
        val allVoices = textToSpeech?.voices?.toList() ?: emptyList()

        val viVoices = allVoices
            .filter { it.locale.language == "vi" }
            .sortedByDescending { it.quality * 100 - it.latency }

        val enVoices = allVoices
            .filter { it.locale.language == "en" }
            .sortedByDescending { it.quality * 100 - it.latency }

        val viItems = mutableListOf<VoiceItem>()
        viItems.add(VoiceItem(null, "Mặc định hệ thống"))
        viVoices.forEach { voice ->
            val displayName = formatVoiceName(voice)
            viItems.add(VoiceItem(voice, displayName))
        }

        val viAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, viItems)
        viAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceVi.adapter = viAdapter

        val enItems = mutableListOf<VoiceItem>()
        enItems.add(VoiceItem(null, "Default (System)"))
        enVoices.forEach { voice ->
            val displayName = formatVoiceName(voice)
            enItems.add(VoiceItem(voice, displayName))
        }

        val enAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, enItems)
        enAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoiceEn.adapter = enAdapter
    }

    // Định dạng lại tên giọng nói cho dễ đọc hơn
    private fun formatVoiceName(voice: Voice): String {
        var name = voice.name

        name = name.replace("vi-vn-x-vie-", "", ignoreCase = true)
            .replace("en-us-x-", "", ignoreCase = true)
            .replace("en-gb-x-", "", ignoreCase = true)
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }

        return name
    }

    // Khôi phục các lựa chọn giọng nói đã lưu trước đó
    private fun restoreSavedVoiceSelections() {
        val currentSettings = settingsManager.getCurrentSettings()

        if (spinnerVoiceVi.adapter == null || spinnerVoiceEn.adapter == null) {
            return
        }

        if (currentSettings.voiceNameVi != null) {
            @Suppress("UNCHECKED_CAST")
            val viAdapter = spinnerVoiceVi.adapter as? ArrayAdapter<VoiceItem>
            viAdapter?.let { adapter ->
                for (i in 0 until adapter.count) {
                    val item = adapter.getItem(i)
                    if (item?.voice?.name == currentSettings.voiceNameVi) {
                        spinnerVoiceVi.setSelection(i)
                        break
                    }
                }
            }
        }

        if (currentSettings.voiceNameEn != null) {
            @Suppress("UNCHECKED_CAST")
            val enAdapter = spinnerVoiceEn.adapter as? ArrayAdapter<VoiceItem>
            enAdapter?.let { adapter ->
                for (i in 0 until adapter.count) {
                    val item = adapter.getItem(i)
                    if (item?.voice?.name == currentSettings.voiceNameEn) {
                        spinnerVoiceEn.setSelection(i)
                        break
                    }
                }
            }
        }
    }

    // Thiết lập bộ lắng nghe sự kiện cho các nút bấm
    private fun setupListeners() {
        btnSaveChanges.setOnClickListener { saveSettings() }
        btnTestVoice.setOnClickListener { testVoice() }
        btnResetDefaults.setOnClickListener { resetToDefaults() }
    }

    // Lưu các cài đặt hiện tại vào bộ nhớ
    private fun saveSettings() {
        val settings = collectSettingsFromUi()
        settingsManager.saveSettings(settings)
        Toast.makeText(this, "Đã lưu thay đổi", Toast.LENGTH_SHORT).show()
        finish()
    }

    // Thu thập các giá trị cài đặt từ giao diện người dùng
    private fun collectSettingsFromUi(): TTSSettings {
        val selectedViItem = spinnerVoiceVi.selectedItem as? VoiceItem
        val voiceNameVi = selectedViItem?.voice?.name

        val selectedEnItem = spinnerVoiceEn.selectedItem as? VoiceItem
        val voiceNameEn = selectedEnItem?.voice?.name

        val autoPlayNext = switchAutoPlayNext.isChecked
        val speechRate   = 0.5f + seekBarSpeechRate.progress * 0.05f

        return TTSSettings(
            voiceNameVi  = voiceNameVi,
            voiceNameEn  = voiceNameEn,
            autoPlayNext = autoPlayNext,
            speechRate   = speechRate
        )
    }

    // Dùng thử giọng nói với các cài đặt hiện tại
    private fun testVoice() {
        val settings = collectSettingsFromUi()

        textToSpeech?.setSpeechRate(settings.speechRate)
        textToSpeech?.setPitch(settings.pitch)

        applyPreviewVoice(Locale.forLanguageTag("vi-VN"), settings.voiceNameVi)
        val testTextVi = "Xin chào, đây là giọng đọc tiếng Việt với cài đặt hiện tại."
        textToSpeech?.speak(testTextVi, TextToSpeech.QUEUE_FLUSH, null, "test_vi")

        textToSpeech?.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, null)

        applyPreviewVoice(Locale.US, settings.voiceNameEn)
        val testTextEn = "Hello, this is the English voice with current settings."
        textToSpeech?.speak(testTextEn, TextToSpeech.QUEUE_ADD, null, "test_en")
    }

    // Áp dụng giọng nói được chọn để nghe thử
    private fun applyPreviewVoice(locale: Locale, voiceName: String?) {
        textToSpeech?.language = locale

        val selectedVoice = if (voiceName != null) {
            textToSpeech?.voices?.find { it.name == voiceName }
        } else {
            textToSpeech?.defaultVoice?.takeIf { it.locale.language == locale.language }
                ?: textToSpeech?.voices
                    ?.filter { it.locale.language == locale.language }
                    ?.maxByOrNull { it.quality * 100 - it.latency }
        }

        if (selectedVoice != null) {
            textToSpeech?.voice = selectedVoice
        }
    }

    // Đặt lại các cài đặt về giá trị mặc định
    @SuppressLint("SetTextI18n")
    private fun resetToDefaults() {
        val defaults = TTSSettings(
            autoPlayNext = true,
            voiceNameVi  = null,
            voiceNameEn  = null,
            speechRate   = 1.0f,
            pitch        = 1.0f
        )

        switchAutoPlayNext.isChecked = true
        if (spinnerVoiceVi.adapter != null) spinnerVoiceVi.setSelection(0)
        if (spinnerVoiceEn.adapter != null) spinnerVoiceEn.setSelection(0)
        seekBarSpeechRate.progress  = 10  // 0.5 + 10*0.05 = 1.0x
        textSpeechRateValue.text    = "1.0x"

        settingsManager.saveSettings(defaults)
        Toast.makeText(this, "Đã đặt lại về mặc định", Toast.LENGTH_SHORT).show()
    }

    // Xử lý khi nhấn nút quay lại trên thanh công cụ
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Giải phóng tài nguyên TTS khi hoạt động bị hủy
    override fun onDestroy() {
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}
