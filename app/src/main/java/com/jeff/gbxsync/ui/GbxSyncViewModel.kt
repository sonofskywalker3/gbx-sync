package com.jeff.gbxsync.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jeff.gbxsync.cart.CartInfo
import com.jeff.gbxsync.cart.CartSession
import com.jeff.gbxsync.library.RomEntry
import com.jeff.gbxsync.library.RomLibrary
import com.jeff.gbxsync.library.RomSystem
import com.jeff.gbxsync.serial.ConnectionState
import com.jeff.gbxsync.serial.UsbConnectionManager
import com.jeff.gbxsync.storage.SafFiles
import com.jeff.gbxsync.sync.BackupManager
import com.jeff.gbxsync.sync.SyncDecision
import com.jeff.gbxsync.sync.SyncEngine
import com.jeff.gbxsync.sync.SyncRecord
import com.jeff.gbxsync.sync.SyncStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncDirection { CART_TO_FILE, FILE_TO_CART }

data class UiState(
    val connection: ConnectionState = ConnectionState.NoDevice,
    val romFolderUri: String? = null,
    val romCount: Int = 0,
    val cart: CartInfo? = null,
    val cartKey: String? = null,
    val matches: List<RomEntry> = emptyList(),
    val selectedMatch: RomEntry? = null,
    val decision: SyncDecision? = null,
    val busyLabel: String? = null,
    val progress: Float? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val isBusy get() = busyLabel != null
}

class GbxSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val usb = UsbConnectionManager(app)
    private val store = SyncStateStore(app)
    private val library = RomLibrary(app)
    private val backups = BackupManager(app)
    private val deviceMutex = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var romIndex: List<RomEntry> = emptyList()
    private var cartSaveBytes: ByteArray? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = store.getRomFolderUri()
            romIndex = store.getRomIndex()
            _ui.value = _ui.value.copy(romFolderUri = folder, romCount = romIndex.size)
            tryConnect()
        }
    }

    // ---- connection --------------------------------------------------------

    fun tryConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val usbDevice = usb.findGbxCart()
            if (usbDevice == null) {
                _ui.value = _ui.value.copy(connection = ConnectionState.NoDevice)
                return@launch
            }
            if (!usb.hasPermission(usbDevice)) {
                usb.requestPermission(usbDevice) { granted ->
                    if (granted) tryConnect()
                    else publishConnection()
                }
                publishConnection()
                return@launch
            }
            deviceMutex.withLock {
                runCatching { usb.connect(usbDevice) }
                    .onSuccess {
                        setStatus("Connected: ${it.pcbName} ${it.pcbLabel}, firmware ${it.cfwId}${it.fwVer}")
                    }
                    .onFailure { setError(it.message ?: "Connection failed") }
            }
            publishConnection()
        }
    }

    fun onUsbDetached() {
        usb.notifyDetached()
        cartSaveBytes = null
        _ui.value = _ui.value.copy(
            connection = ConnectionState.NoDevice,
            cart = null, cartKey = null, matches = emptyList(),
            selectedMatch = null, decision = null,
        )
    }

    private fun publishConnection() {
        _ui.value = _ui.value.copy(connection = usb.state.value)
    }

    // ---- library -----------------------------------------------------------

    fun onRomFolderPicked(uri: Uri) {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModelScope.launch(Dispatchers.IO) {
            store.setRomFolderUri(uri.toString())
            _ui.value = _ui.value.copy(romFolderUri = uri.toString())
            rescanLibrary()
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = _ui.value.romFolderUri ?: return@launch
            withBusy("Scanning ROM folder…") {
                romIndex = library.scan(Uri.parse(folder), romIndex)
                store.putRomIndex(romIndex)
                _ui.value = _ui.value.copy(romCount = romIndex.size)
                setStatus("Found ${romIndex.size} ROMs")
                refreshMatchesAndDecision()
            }
        }
    }

    // ---- cartridge ---------------------------------------------------------

    fun detectCart() {
        viewModelScope.launch(Dispatchers.IO) {
            val device = usb.device ?: run { setError("Not connected"); return@launch }
            deviceMutex.withLock {
                withBusy("Reading cartridge…") {
                    cartSaveBytes = null
                    val session = CartSession(device)
                    val info = runCatching { session.detect() }
                        .getOrElse { setError(it.message ?: "Detection failed"); return@withBusy }
                    if (info == null) {
                        _ui.value = _ui.value.copy(
                            cart = null, cartKey = null,
                            matches = emptyList(), selectedMatch = null, decision = null,
                        )
                        setError("No cartridge detected — check that it's inserted firmly")
                        return@withBusy
                    }
                    val key = RomLibrary.sha256(info.headerBytes)
                    _ui.value = _ui.value.copy(cart = info, cartKey = key)

                    if (info.saveSizeBytes > 0) {
                        setBusy("Reading save from cartridge…")
                        val save = runCatching {
                            session.readSave(info) { done, total -> setProgress(done, total) }
                        }.getOrElse {
                            setError(it.message ?: "Save read failed"); return@withBusy
                        }
                        cartSaveBytes = save
                    }
                    refreshMatchesAndDecision()
                    setStatus(describeCart(info))
                }
            }
        }
    }

    fun selectMatch(entry: RomEntry) {
        _ui.value = _ui.value.copy(selectedMatch = entry)
        viewModelScope.launch(Dispatchers.IO) { refreshMatchesAndDecision(keepSelection = true) }
    }

    private suspend fun refreshMatchesAndDecision(keepSelection: Boolean = false) {
        val s = _ui.value
        val cart = s.cart ?: return
        val system = if (cart is CartInfo.Dmg) RomSystem.GB else RomSystem.GBA
        val matches = RomLibrary.match(romIndex, system, cart.headerBytes, cart.title)
        val selected = if (keepSelection && s.selectedMatch != null) s.selectedMatch
        else matches.firstOrNull()

        val decision = computeDecision(selected)
        _ui.value = _ui.value.copy(matches = matches, selectedMatch = selected, decision = decision)
    }

    private suspend fun computeDecision(match: RomEntry?): SyncDecision? {
        val cartBytes = cartSaveBytes ?: return null
        if (match == null) return null
        val cartHash = RomLibrary.sha256(cartBytes)
        val fileBytes = match.saveFileUriString?.let {
            SafFiles.readBytes(getApplication<Application>().contentResolver, Uri.parse(it))
        }
        val fileHash = fileBytes?.let { RomLibrary.sha256(normalize(it, cartBytes.size)) }
        val record = _ui.value.cartKey?.let { store.getSyncRecords()[it] }
        return SyncEngine.decide(cartHash, fileHash, record)
    }

    // ---- sync --------------------------------------------------------------

    fun sync(direction: SyncDirection) {
        viewModelScope.launch(Dispatchers.IO) {
            val device = usb.device ?: run { setError("Not connected"); return@launch }
            val s = _ui.value
            val cart = s.cart ?: return@launch
            val cartKey = s.cartKey ?: return@launch
            val match = s.selectedMatch ?: run { setError("No matching ROM selected"); return@launch }
            val app = getApplication<Application>()
            val treeUri = s.romFolderUri?.let(Uri::parse) ?: return@launch

            deviceMutex.withLock {
                withBusy(if (direction == SyncDirection.CART_TO_FILE) "Writing save file…" else "Writing save to cartridge…") {
                    val session = CartSession(device)
                    val cartBytes = cartSaveBytes ?: run { setError("No cart save read"); return@withBusy }

                    if (direction == SyncDirection.CART_TO_FILE) {
                        // Back up the file being replaced, then write cart data out.
                        val existingUri = match.saveFileUriString?.let(Uri::parse)
                        existingUri
                            ?.let { SafFiles.readBytes(app.contentResolver, it) }
                            ?.let { backups.backup(cartKey, "file", it) }

                        val written = SafFiles.writeFile(
                            app, treeUri, existingUri, match.parentDocumentId,
                            match.saveFileName, cartBytes,
                        )
                        if (written == null) { setError("Couldn't write save file"); return@withBusy }
                        val readBack = SafFiles.readBytes(app.contentResolver, written)
                        if (readBack == null || !readBack.contentEquals(cartBytes)) {
                            setError("Save file verification failed"); return@withBusy
                        }
                        finishSync(cartKey, cartBytes, "Cart save copied to ${match.saveFileName}")
                    } else {
                        val fileUri = match.saveFileUriString?.let(Uri::parse)
                            ?: run { setError("No .sav file exists for this ROM"); return@withBusy }
                        val fileBytes = SafFiles.readBytes(app.contentResolver, fileUri)
                            ?: run { setError("Couldn't read save file"); return@withBusy }
                        val payload = normalize(fileBytes, cart.saveSizeBytes)

                        backups.backup(cartKey, "cart", cartBytes)

                        runCatching {
                            session.writeSave(cart, payload) { done, total -> setProgress(done, total) }
                        }.getOrElse { setError(it.message ?: "Cart write failed"); return@withBusy }

                        setBusy("Verifying cartridge…")
                        val ok = runCatching { session.verifySave(cart, payload) }.getOrDefault(false)
                        if (!ok) {
                            setError("Cartridge verification failed — save may be corrupt, backup kept")
                            return@withBusy
                        }
                        cartSaveBytes = payload.copyOf(cart.saveSizeBytes)
                        finishSync(cartKey, cartSaveBytes!!, "Save file written to cartridge and verified")
                    }
                }
            }
        }
    }

    private suspend fun finishSync(cartKey: String, syncedBytes: ByteArray, message: String) {
        store.putSyncRecord(
            SyncRecord(
                gameKey = cartKey,
                lastSyncHash = RomLibrary.sha256(syncedBytes),
                lastSyncTimeMs = System.currentTimeMillis(),
            )
        )
        rescanCurrentFolderEntry()
        refreshMatchesAndDecision(keepSelection = true)
        setStatus(message)
    }

    private suspend fun rescanCurrentFolderEntry() {
        val folder = _ui.value.romFolderUri ?: return
        romIndex = library.scan(Uri.parse(folder), romIndex)
        store.putRomIndex(romIndex)
        _ui.value = _ui.value.copy(romCount = romIndex.size)
    }

    // ---- helpers -----------------------------------------------------------

    /** Pads (by repetition, like FlashGBX) or truncates a file to the cart's size. */
    private fun normalize(data: ByteArray, targetSize: Int): ByteArray {
        if (targetSize <= 0 || data.size == targetSize) return data
        if (data.size > targetSize) return data.copyOf(targetSize)
        var out = data
        while (out.size < targetSize) out += out
        return out.copyOf(targetSize)
    }

    private fun describeCart(info: CartInfo): String = when (info) {
        is CartInfo.Dmg -> {
            val save = info.saveType?.let {
                if (it.sizeBytes > 0) "${it.sizeBytes / 1024} KB save (${it.mbc})" else "no save RAM"
            } ?: "unsupported mapper"
            "${info.title.ifBlank { "Unknown" }} — Game Boy, $save"
        }
        is CartInfo.Agb ->
            "${info.title.ifBlank { "Unknown" }} — GBA, ${info.detection.saveType.label}"
    }

    private inline fun withBusy(label: String, body: () -> Unit) {
        setBusy(label)
        try {
            body()
        } finally {
            _ui.value = _ui.value.copy(busyLabel = null, progress = null)
        }
    }

    private fun setBusy(label: String) {
        _ui.value = _ui.value.copy(busyLabel = label, progress = null, errorMessage = null)
    }

    private fun setProgress(done: Int, total: Int) {
        _ui.value = _ui.value.copy(progress = if (total > 0) done.toFloat() / total else null)
    }

    private fun setStatus(message: String) {
        _ui.value = _ui.value.copy(statusMessage = message, errorMessage = null)
    }

    private fun setError(message: String) {
        _ui.value = _ui.value.copy(errorMessage = message)
    }

    override fun onCleared() {
        usb.disconnect()
    }
}
